package io.github.nitsuya.aa.display.xposed.hook.aa

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.service.ClusterLyricMediaService
import io.github.nitsuya.aa.display.xposed.cluster.ClusterArtStore
import io.github.nitsuya.aa.display.xposed.cluster.ClusterLyricStore
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.hook.DexKitMethodCache
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

/**
 * Fallback: when Gearhead reads our cluster shell [MediaMetadata] Title/Artist,
 * substitute [ClusterLyricStore] ticker text published by system_server.
 *
 * Only rewrites metadata whose MEDIA_ID starts with `aadisplay.cluster:` so QQ
 * (and other real players) keep their own Title for non-cluster UI / steering.
 *
 * Also stops Gearhead from treating each lyric [setTitle] as a new track:
 * StatusBar title is updated in-place (no layout switch). Same-track lyric
 * lines still send HU MediaInfo (cluster Title is that packet's song) but
 * the GAL protobuf omits duration / rating / album art so the HU can update
 * the text without treating it as a new track (clock to 0:00). Extra
 * PlaybackStatus packets in that window are dropped; seconds are left to
 * Gearhead so the HU clock is not snapped to a different whole second.
 */
object AaClusterLyricEgressHook : AaHook() {
    override val tagName: String = "AAD_AaClusterLyricEgressHook"
    override val usesDexKit: Boolean = true

    private const val READ_CACHE_TTL_MS = 200L
    private const val CACHE_SET_TITLE = "hook.AaClusterLyricEgressHook.set_title"
    private const val CACHE_META_P = "hook.AaClusterLyricEgressHook.meta_p"
    private const val CACHE_PLAY_Q = "hook.AaClusterLyricEgressHook.play_q"
    private const val CACHE_PLAY_L = "hook.AaClusterLyricEgressHook.play_l"
    private const val CACHE_GAL_H = "hook.AaClusterLyricEgressHook.gal_h"
    private const val CACHE_GAL_K = "hook.AaClusterLyricEgressHook.gal_k"
    private const val LYRIC_ONLY_PLAYBACK_SUPPRESS_MS = 500L
    private const val PROTO_HAS_SONG = 0x01
    private const val PROTO_HAS_ART = 0x08
    private const val PROTO_HAS_DURATION = 0x20
    private const val PROTO_HAS_RATING = 0x40
    private const val PLAYBACK_EXTRAS_MEDIA_ID =
        "androidx.media.PlaybackStateCompat.Extras.KEY_MEDIA_ID"
    private const val STATUS_BAR_VIEW =
        "com.google.android.gearhead.appdecor.StatusBarView"

    private const val METADATA_KEY_ALBUM_ART = "android.media.metadata.ALBUM_ART"
    private const val METADATA_KEY_ART = "android.media.metadata.ART"
    private const val METADATA_KEY_DISPLAY_ICON = "android.media.metadata.DISPLAY_ICON"
    private const val METADATA_KEY_ALBUM_ART_URI = "android.media.metadata.ALBUM_ART_URI"
    private const val METADATA_KEY_ART_URI = "android.media.metadata.ART_URI"
    private const val METADATA_KEY_DISPLAY_ICON_URI = "android.media.metadata.DISPLAY_ICON_URI"

    private val titleKeys = setOf(
        MediaMetadata.METADATA_KEY_TITLE,
        MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
    )

    private val subtitleKeys = setOf(
        MediaMetadata.METADATA_KEY_ARTIST,
        MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
        MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
    )

    private val albumKeys = setOf(
        MediaMetadata.METADATA_KEY_ALBUM,
    )

    private val artKeys = setOf(
        METADATA_KEY_ALBUM_ART,
        METADATA_KEY_ART,
        METADATA_KEY_DISPLAY_ICON,
    )

    private val artUriKeys = setOf(
        METADATA_KEY_ALBUM_ART_URI,
        METADATA_KEY_ART_URI,
        METADATA_KEY_DISPLAY_ICON_URI,
        MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
        MediaMetadata.METADATA_KEY_ART_URI,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
    )

    override fun isSupportProcess(processName: String): Boolean {
        return processProjection == processName || processCar == processName
    }

    private var setTitleMethod: Method? = null
    private var metadataPushMethod: Method? = null
    private var playbackPushMethod: Method? = null
    private var playbackCacheMethod: Method? = null
    private var lastPlaybackState: Any? = null
    private var lastPlaybackPkg: String? = null
    private var lastHuArtist: String? = null
    private var lastHuAlbum: String? = null
    private var lastHuDuration: Long = -1L
    private var lastHuArtLen: Int = -1
    private val reenteringPlayback = ThreadLocal.withInitial { false }
    private val lyricOnlyGal = ThreadLocal.withInitial { false }
    private val stripLyricOnlyGal = ThreadLocal.withInitial { false }
    @Volatile
    private var pendingLyricOnlyUntilElapsedMs = 0L
    @Volatile
    private var lyricOnlyPlaybackSent = false
    private var galMediaInfoMethod: Method? = null
    private var galSendMethod: Method? = null

    override fun applyCache(
        cache: DexKitMethodCache.Session,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ): Boolean {
        if (!applyOptionalMethod(cache, lpparam, CACHE_SET_TITLE) { setTitleMethod = it } ||
            !applyOptionalMethod(cache, lpparam, CACHE_META_P) { metadataPushMethod = it } ||
            !applyOptionalMethod(cache, lpparam, CACHE_PLAY_Q) { playbackPushMethod = it } ||
            !applyOptionalMethod(cache, lpparam, CACHE_PLAY_L) { playbackCacheMethod = it } ||
            !applyOptionalMethod(cache, lpparam, CACHE_GAL_H) { galMediaInfoMethod = it } ||
            !applyOptionalMethod(cache, lpparam, CACHE_GAL_K) { galSendMethod = it }
        ) {
            return false
        }
        logDebug(
            tagName,
            "gearhead methods setTitle=${setTitleMethod != null} " +
                "metaP=${metadataPushMethod != null} playQ=${playbackPushMethod != null} " +
                "playL=${playbackCacheMethod != null} galH=${galMediaInfoMethod != null} " +
                "galK=${galSendMethod != null} (cache)",
        )
        return true
    }

    override fun saveCache(
        cache: DexKitMethodCache.Session,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ) {
        cache.putRef(CACHE_SET_TITLE, setTitleMethod)
        cache.putRef(CACHE_META_P, metadataPushMethod)
        cache.putRef(CACHE_PLAY_Q, playbackPushMethod)
        cache.putRef(CACHE_PLAY_L, playbackCacheMethod)
        cache.putRef(CACHE_GAL_H, galMediaInfoMethod)
        cache.putRef(CACHE_GAL_K, galSendMethod)
    }

    override fun loadDexClass(bridge: DexKitBridge, lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        setTitleMethod = findUniqueMethod(bridge, cl, "setTitle %s") { method ->
            method.returnType == Void.TYPE &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == CharSequence::class.java
        }
        metadataPushMethod = findUniqueMethod(bridge, cl, "Error updating metadata status.") { method ->
            method.returnType == Void.TYPE &&
                method.parameterTypes.size == 5 &&
                method.parameterTypes[0] == String::class.java &&
                method.parameterTypes[3] == ByteArray::class.java
        }
        playbackPushMethod = findUniqueMethod(bridge, cl, "Error updating playback status.") { method ->
            method.returnType == Void.TYPE && method.parameterTypes.size == 2
        }
        playbackCacheMethod = findUniqueMethod(bridge, cl, "playbackstate cannot be null") { method ->
            method.returnType == Void.TYPE && method.parameterTypes.size == 2
        }
        galMediaInfoMethod = findGalMediaInfoSend(bridge, cl)
        galSendMethod = findGalSendMethod(galMediaInfoMethod)
        logDebug(
            tagName,
            "gearhead methods setTitle=${setTitleMethod != null} " +
                "metaP=${metadataPushMethod != null} playQ=${playbackPushMethod != null} " +
                "playL=${playbackCacheMethod != null} galH=${galMediaInfoMethod != null} " +
                "galK=${galSendMethod != null} (live)",
        )
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookPlatformMediaMetadata()
        hookPlatformMediaController()
        hookCompatMediaMetadata(
            "android.support.v4.media.MediaMetadataCompat",
            lpparam.classLoader,
        )
        hookGearheadTitleAndProgress()
    }

    private fun hookPlatformMediaMetadata() {
        try {
            findMethod(MediaMetadata::class.java) {
                name == "getString" &&
                    parameterCount == 1 &&
                    parameterTypes[0] == String::class.java
            }.hookAfter { param ->
                rewriteStringArg(param.args[0] as? String, param)
            }
            findMethod(MediaMetadata::class.java) {
                name == "getText" &&
                    parameterCount == 1 &&
                    parameterTypes[0] == String::class.java
            }.hookAfter { param ->
                rewriteTitleArg(param.args[0] as? String, param)
            }
            findMethod(MediaMetadata::class.java) {
                name == "getBitmap" &&
                    parameterCount == 1 &&
                    parameterTypes[0] == String::class.java
            }.hookAfter { param ->
                rewriteArtArg(param.args[0] as? String, param)
            }
            findMethod(MediaMetadata::class.java) {
                name == "getLong" &&
                    parameterCount == 1 &&
                    parameterTypes[0] == String::class.java
            }.hookAfter { param ->
                rewriteLongArg(param.args[0] as? String, param)
            }
            log(tagName, "hooked android.media.MediaMetadata getString/getText/getBitmap/getLong")
        } catch (e: Throwable) {
            log(tagName, "hook MediaMetadata failed", e)
        }
    }

    private fun hookCompatMediaMetadata(className: String, cl: ClassLoader) {
        val clazz = runCatching { Class.forName(className, false, cl) }.getOrNull() ?: return
        try {
            findMethod(clazz) {
                name == "getString" &&
                    parameterCount == 1 &&
                    parameterTypes[0] == String::class.java
            }.hookAfter { param ->
                rewriteStringArg(param.args[0] as? String, param)
            }
            findMethod(clazz) {
                name == "getText" &&
                    parameterCount == 1 &&
                    parameterTypes[0] == String::class.java
            }.hookAfter { param ->
                rewriteTitleArg(param.args[0] as? String, param)
            }
            findMethod(clazz) {
                name == "getBitmap" &&
                    parameterCount == 1 &&
                    parameterTypes[0] == String::class.java
            }.hookAfter { param ->
                rewriteArtArg(param.args[0] as? String, param)
            }
            findMethod(clazz) {
                name == "getLong" &&
                    parameterCount == 1 &&
                    parameterTypes[0] == String::class.java
            }.hookAfter { param ->
                rewriteLongArg(param.args[0] as? String, param)
            }
            log(tagName, "hooked $className.getString/getText/getBitmap/getLong")
        } catch (e: Throwable) {
            logDebug(tagName, "hook $className skipped: ${e.message}")
        }
    }

    private fun hookPlatformMediaController() {
        try {
            findMethod(MediaController::class.java) {
                name == "getPlaybackState"
            }.hookAfter { param ->
                val controller = param.thisObject as? MediaController ?: return@hookAfter
                if (!isClusterShellMetadata(controller.metadata)) return@hookAfter
                val cr = runCatching { InitFields.appContext.contentResolver }.getOrNull() ?: return@hookAfter
                param.result = buildPlatformPlaybackState(
                    cr,
                    controller.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                ) ?: return@hookAfter
            }
            log(tagName, "hooked android.media.session.MediaController.getPlaybackState")
        } catch (e: Throwable) {
            log(tagName, "hook MediaController failed", e)
        }
    }

    private fun rewriteLongArg(
        key: String?,
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam,
    ) {
        if (key.isNullOrEmpty()) return
        if (key != MediaMetadata.METADATA_KEY_DURATION) return
        if (!isClusterShellMetadata(param.thisObject)) return
        val cr = runCatching { InitFields.appContext.contentResolver }.getOrNull() ?: return
        val durationMs = readProgressCached(cr)?.durationMs ?: return
        if (durationMs <= 0L) return
        param.result = durationMs
    }

    private fun buildPlatformPlaybackState(
        cr: android.content.ContentResolver,
        mediaId: String?,
    ): PlaybackState? {
        val progress = readProgressCached(cr) ?: return null
        val positionMs = ClusterLyricStore.extrapolatePosition(progress)
        val extras = Bundle()
        if (!mediaId.isNullOrEmpty()) {
            extras.putString(PLAYBACK_EXTRAS_MEDIA_ID, mediaId)
            extras.putString(MediaMetadata.METADATA_KEY_MEDIA_ID, mediaId)
        }
        return PlaybackState.Builder()
            .setState(
                progress.playbackState,
                positionMs,
                progress.playbackSpeed,
                SystemClock.elapsedRealtime(),
            )
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS,
            )
            .setExtras(extras)
            .build()
    }

    private fun rewriteStringArg(
        key: String?,
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam,
    ) {
        if (key.isNullOrEmpty()) return
        if (key in artUriKeys) {
            rewriteArtUriArg(key, param)
            return
        }
        rewriteTitleArg(key, param)
    }

    private fun rewriteTitleArg(
        key: String?,
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam,
    ) {
        if (key.isNullOrEmpty()) return
        if (key !in titleKeys && key !in subtitleKeys && key !in albumKeys) return
        if (!isClusterShellMetadata(param.thisObject)) return
        val cr = runCatching { InitFields.appContext.contentResolver }.getOrNull() ?: return
        when {
            key in titleKeys -> {
                param.result = readFreshCached(cr)?.title ?: return
            }
            key in subtitleKeys -> {
                param.result = readFreshCached(cr)?.subtitle?.takeIf { it.isNotEmpty() } ?: return
            }
            key in albumKeys -> {
                param.result = readFreshCached(cr)?.album?.takeIf { it.isNotEmpty() } ?: return
            }
        }
    }

    private fun rewriteArtUriArg(
        key: String?,
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam,
    ) {
        if (key.isNullOrEmpty() || key !in artUriKeys) return
        if (!isClusterShellMetadata(param.thisObject)) return
        val cr = runCatching { InitFields.appContext.contentResolver }.getOrNull() ?: return
        val revision = ClusterArtStore.readRevision(cr)
        param.result = ClusterArtStore.artUriString(revision) ?: return
    }

    private fun rewriteArtArg(
        key: String?,
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam,
    ) {
        if (key.isNullOrEmpty() || key !in artKeys) return
        if (!isClusterShellMetadata(param.thisObject)) return
        val cr = runCatching { InitFields.appContext.contentResolver }.getOrNull() ?: return
        param.result = ClusterArtStore.loadBitmap(cr) ?: return
    }

    private var compatGetString: java.lang.reflect.Method? = null
    private var compatGetStringClass: Class<*>? = null
    private var cachedFresh: ClusterLyricStore.Fresh? = null
    private var cachedFreshAtElapsedMs: Long = 0L
    private var cachedProgress: ClusterLyricStore.Progress? = null
    private var cachedProgressAtElapsedMs: Long = 0L

    private fun readFreshCached(cr: android.content.ContentResolver): ClusterLyricStore.Fresh? {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedFreshAtElapsedMs in 0L until READ_CACHE_TTL_MS) {
            return cachedFresh
        }
        val fresh = ClusterLyricStore.readFresh(cr)
        cachedFresh = fresh
        cachedFreshAtElapsedMs = now
        return fresh
    }

    private fun readProgressCached(cr: android.content.ContentResolver): ClusterLyricStore.Progress? {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedProgressAtElapsedMs in 0L until READ_CACHE_TTL_MS) {
            return cachedProgress
        }
        val progress = ClusterLyricStore.readProgress(cr)
        cachedProgress = progress
        cachedProgressAtElapsedMs = now
        return progress
    }

    /** Only our invisible shell session — never rewrite QQ / Spotify titles. */
    private fun isClusterShellMetadata(metadata: Any?): Boolean {
        if (metadata == null) return false
        return runCatching {
            when (metadata) {
                is MediaMetadata -> {
                    metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                        ?.startsWith(ClusterLyricMediaService.MEDIA_ID_PREFIX) == true
                }
                else -> {
                    val clazz = metadata.javaClass
                    val getString = if (compatGetStringClass == clazz) {
                        compatGetString
                    } else {
                        clazz.methods.firstOrNull { m ->
                            m.name == "getString" &&
                                m.parameterTypes.size == 1 &&
                                m.parameterTypes[0] == String::class.java
                        }.also {
                            compatGetString = it
                            compatGetStringClass = clazz
                        }
                    } ?: return@runCatching false
                    val mediaId = getString.invoke(
                        metadata,
                        MediaMetadata.METADATA_KEY_MEDIA_ID,
                    ) as? String
                    mediaId?.startsWith(ClusterLyricMediaService.MEDIA_ID_PREFIX) == true
                }
            }
        }.getOrDefault(false)
    }

    private fun applyOptionalMethod(
        cache: DexKitMethodCache.Session,
        lpparam: XC_LoadPackage.LoadPackageParam,
        key: String,
        setter: (Method?) -> Unit,
    ): Boolean {
        if (!cache.hasKey(key)) return false
        val ref = cache.getRef(key)
        if (ref == null) {
            setter(null)
            return true
        }
        val method = cache.resolve(lpparam.classLoader, ref) ?: return false
        setter(method)
        return true
    }

    private fun findUniqueMethod(
        bridge: DexKitBridge,
        cl: ClassLoader,
        needle: String,
        extra: (Method) -> Boolean,
    ): Method? {
        val found = linkedMapOf<String, Method>()
        runCatching {
            bridge.findMethod {
                matcher {
                    usingStrings {
                        add(needle, StringMatchType.Equals, false)
                    }
                }
            }.forEach { md ->
                val method = runCatching { md.getMethodInstance(cl) }.getOrNull() ?: return@forEach
                if (!extra(method)) return@forEach
                val key =
                    "${method.declaringClass.name}#${method.name}#" +
                        method.parameterTypes.joinToString { it.name }
                found.putIfAbsent(key, method)
            }
        }.onFailure { e ->
            log(tagName, "DexKit $needle failed", e)
        }
        if (found.size != 1) {
            logDebug(tagName, "DexKit '$needle' hits=${found.size} ${found.keys}")
            return null
        }
        return found.values.first().also { it.isAccessible = true }
    }

    private fun findGalMediaInfoSend(bridge: DexKitBridge, cl: ClassLoader): Method? {
        val found = linkedMapOf<String, Method>()
        runCatching {
            bridge.findClass {
                matcher {
                    usingStrings {
                        add("CAR.GAL.INST", StringMatchType.Equals, false)
                        add("Invalid message type: %d", StringMatchType.Equals, false)
                    }
                }
            }.forEach { cd ->
                val clazz = runCatching {
                    Class.forName(cd.name, false, cl)
                }.getOrNull() ?: return@forEach
                clazz.declaredMethods.forEach { method ->
                    if (method.returnType != Void.TYPE || method.parameterTypes.size != 7) {
                        return@forEach
                    }
                    if (method.parameterTypes[0] != String::class.java ||
                        method.parameterTypes[3] != ByteArray::class.java ||
                        method.parameterTypes[4] != String::class.java ||
                        method.parameterTypes[5] != Int::class.javaPrimitiveType
                    ) {
                        return@forEach
                    }
                    method.isAccessible = true
                    val key =
                        "${method.declaringClass.name}#${method.name}#" +
                            method.parameterTypes.joinToString { it.name }
                    found.putIfAbsent(key, method)
                }
            }
        }.onFailure { e ->
            log(tagName, "DexKit GAL MediaInfo failed", e)
        }
        if (found.size != 1) {
            logDebug(tagName, "GAL MediaInfo hits=${found.size} ${found.keys}")
            return null
        }
        return found.values.first()
    }

    private fun findGalSendMethod(galMediaInfo: Method?): Method? {
        var cls: Class<*>? = galMediaInfo?.declaringClass ?: return null
        while (cls != null && cls != Any::class.java) {
            val hit = cls.declaredMethods.filter { method ->
                method.returnType == Void.TYPE &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    method.parameterTypes[1] != java.nio.ByteBuffer::class.java &&
                    !method.parameterTypes[1].isPrimitive
            }
            if (hit.size == 1) {
                return hit.first().also { it.isAccessible = true }
            }
            cls = cls.superclass
        }
        logDebug(tagName, "GAL send method not found")
        return null
    }

    private fun stripLyricOnlyProtoBits(msg: Any) {
        var cls: Class<*>? = msg.javaClass
        var stripped = false
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                if (field.type != Int::class.javaPrimitiveType) continue
                field.isAccessible = true
                if (field.name == "b" && cls == msg.javaClass) {
                    val bits = field.getInt(msg)
                    if (bits and PROTO_HAS_SONG == 0) continue
                    field.setInt(
                        msg,
                        bits and PROTO_HAS_DURATION.inv() and
                            PROTO_HAS_RATING.inv() and
                            PROTO_HAS_ART.inv(),
                    )
                    stripped = true
                    logDebug(tagName, "stripped GAL duration bits ${bits.toString(16)}")
                } else if (field.name == "ak" || field.name == "am") {
                    if (field.getInt(msg) >= 0) field.setInt(msg, -1)
                }
            }
            cls = cls.superclass
        }
        if (!stripped) {
            logDebug(tagName, "GAL proto bitfield not found ${msg.javaClass.name}")
        }
    }

    private fun isLyricOnlyGalPacket(song: String?, artist: String?): Boolean {
        if (lyricOnlyGal.get() == true) return true
        val until = pendingLyricOnlyUntilElapsedMs
        if (until <= 0L || SystemClock.elapsedRealtime() >= until) return false
        return isClusterShellSong(song, artist)
    }

    private fun isLyricOnlyPlaybackWindow(): Boolean {
        if (lyricOnlyGal.get() == true) return true
        val until = pendingLyricOnlyUntilElapsedMs
        return until > 0L && SystemClock.elapsedRealtime() < until
    }

    private fun hookGearheadTitleAndProgress() {
        setTitleMethod?.let { method ->
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val title = param.args[0] as? CharSequence ?: return
                        if (!isClusterLyricTitle(title)) return
                        if (!applyStatusBarTitleInPlace(param.thisObject, title)) return
                        param.result = null
                        logDebug(tagName, "setTitle in-place len=${title.length}")
                    }
                })
                log(tagName, "hooked StatusBar setTitle ${method.declaringClass.name}#${method.name}")
            } catch (e: Throwable) {
                log(tagName, "hook setTitle failed", e)
            }
        }
        playbackCacheMethod?.let { method ->
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        lastPlaybackState = param.args[0]
                        lastPlaybackPkg = param.args[1] as? String
                    }
                })
                logDebug(tagName, "hooked playback cache ${method.declaringClass.name}#${method.name}")
            } catch (e: Throwable) {
                log(tagName, "hook playback cache failed", e)
            }
        }
        playbackPushMethod?.let { method ->
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isLyricOnlyPlaybackWindow()) return
                        if (lyricOnlyPlaybackSent) {
                            param.result = null
                            logDebug(tagName, "drop duplicate HU playback")
                            return
                        }
                        lyricOnlyPlaybackSent = true
                    }
                })
                logDebug(tagName, "hooked HU playback push ${method.declaringClass.name}#${method.name}")
            } catch (e: Throwable) {
                log(tagName, "hook playback push failed", e)
            }
        }
        metadataPushMethod?.let { method ->
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val song = param.args[0] as? String
                        val artist = param.args[1] as? String
                        val album = param.args[2] as? String
                        val artLen = (param.args[3] as? ByteArray)?.size ?: 0
                        val duration = (param.args[4] as? Number)?.toLong() ?: -1L
                        if (!isClusterShellSong(song, artist)) {
                            lyricOnlyGal.set(false)
                            return
                        }
                        val lyricOnly =
                            lastHuArtist == artist &&
                                lastHuAlbum == album &&
                                lastHuDuration == duration &&
                                lastHuDuration >= 0L &&
                                (artLen == lastHuArtLen || artLen == 0 || lastHuArtLen == 0)
                        lyricOnlyGal.set(lyricOnly)
                        lyricOnlyPlaybackSent = false
                        pendingLyricOnlyUntilElapsedMs = if (lyricOnly) {
                            SystemClock.elapsedRealtime() + LYRIC_ONLY_PLAYBACK_SUPPRESS_MS
                        } else {
                            0L
                        }
                        lastHuArtist = artist
                        lastHuAlbum = album
                        lastHuDuration = duration
                        lastHuArtLen = artLen
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (!isLyricOnlyPlaybackWindow()) return
                            if (lyricOnlyPlaybackSent) return
                            if (!isClusterShellSong(
                                    param.args[0] as? String,
                                    param.args[1] as? String,
                                )
                            ) {
                                return
                            }
                            pushPlaybackNow(param.thisObject)
                        } finally {
                            lyricOnlyGal.set(false)
                        }
                    }
                })
                log(tagName, "hooked HU metadata push ${method.declaringClass.name}#${method.name}")
            } catch (e: Throwable) {
                log(tagName, "hook metadata push failed", e)
            }
        }
        galMediaInfoMethod?.let { method ->
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isLyricOnlyGalPacket(
                                param.args[0] as? String,
                                param.args[1] as? String,
                            )
                        ) {
                            return
                        }
                        param.args[3] = null
                        stripLyricOnlyGal.set(true)
                        logDebug(tagName, "GAL MediaInfo lyric-only drop art/duration")
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        stripLyricOnlyGal.set(false)
                    }
                })
                log(tagName, "hooked GAL MediaInfo ${method.declaringClass.name}#${method.name}")
            } catch (e: Throwable) {
                log(tagName, "hook GAL MediaInfo failed", e)
            }
        }
        galSendMethod?.let { method ->
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (stripLyricOnlyGal.get() != true) return
                        val msg = param.args[1] ?: return
                        stripLyricOnlyProtoBits(msg)
                    }
                })
                logDebug(tagName, "hooked GAL send ${method.declaringClass.name}#${method.name}")
            } catch (e: Throwable) {
                log(tagName, "hook GAL send failed", e)
            }
        }
    }

    private fun pushPlaybackNow(monitor: Any) {
        if (reenteringPlayback.get() == true) return
        val q = playbackPushMethod ?: return
        val state = lastPlaybackState ?: return
        val pkg = lastPlaybackPkg ?: return
        reenteringPlayback.set(true)
        try {
            q.invoke(monitor, state, pkg)
        } catch (e: Throwable) {
            logDebug(tagName, "pushPlaybackNow failed: ${e.message}")
        } finally {
            reenteringPlayback.set(false)
        }
    }

    private fun isClusterLyricTitle(title: CharSequence): Boolean {
        val cr = runCatching { InitFields.appContext.contentResolver }.getOrNull() ?: return false
        val fresh = readFreshCached(cr) ?: return false
        return title.toString() == fresh.title
    }

    private fun isClusterShellSong(song: String?, artist: String?): Boolean {
        if (song.isNullOrEmpty()) return false
        val cr = runCatching { InitFields.appContext.contentResolver }.getOrNull() ?: return false
        val fresh = readFreshCached(cr) ?: return false
        if (song != fresh.title) return false
        if (artist.isNullOrEmpty() || fresh.subtitle.isEmpty()) return true
        return artist == fresh.subtitle
    }

    private fun applyStatusBarTitleInPlace(controller: Any, title: CharSequence): Boolean {
        val sbv = findTypedField(controller, STATUS_BAR_VIEW) ?: return false
        val tv = findTitleTextView(sbv) ?: return false
        if (tv.text.isNullOrEmpty()) return false
        if (tv.text.toString() == title.toString()) return true
        tv.text = title
        syncStatusBarTitleFields(sbv, title)
        return true
    }

    private fun findTitleTextView(statusBarView: Any): TextView? {
        val tvs = mutableListOf<TextView>()
        var cls: Class<*>? = statusBarView.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (field.type != TextView::class.java) continue
                field.isAccessible = true
                val view = field.get(statusBarView) as? TextView ?: continue
                tvs += view
            }
            cls = cls.superclass
        }
        return tvs.firstOrNull { it.visibility == View.VISIBLE && !it.text.isNullOrEmpty() }
            ?: tvs.firstOrNull { it.visibility == View.VISIBLE }
            ?: tvs.firstOrNull()
    }

    private fun syncStatusBarTitleFields(statusBarView: Any, title: CharSequence) {
        var cls: Class<*>? = statusBarView.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (field.type != CharSequence::class.java) continue
                field.isAccessible = true
                field.set(statusBarView, title)
            }
            cls = cls.superclass
        }
    }

    private fun findTypedField(host: Any, typeName: String): Any? {
        var cls: Class<*>? = host.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (field.type.name != typeName) continue
                field.isAccessible = true
                return field.get(host)
            }
            cls = cls.superclass
        }
        return null
    }
}
