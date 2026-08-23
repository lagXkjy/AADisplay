package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.ContentResolver
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.session.PlaybackStateCompat
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
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * When Gearhead reads our cluster shell [MediaMetadata] Title/Artist,
 * substitute [ClusterLyricStore] ticker text published by system_server.
 *
 * Only rewrites metadata whose MEDIA_ID starts with `aadisplay.cluster:` so QQ
 * (and other real players) keep their own Title for non-cluster UI / steering.
 *
 * StatusBar [setTitle] is updated in-place (no layout switch). HU metadata push
 * injects lyric line and album. Same-track lyric-only lines open a short HU
 * PlaybackStatus window: at most one natural packet, then [pushPlaybackNow] if
 * none arrived after MediaInfo (T2-A: push uses Store extrapolated whole seconds).
 */
object AaClusterLyricEgressHook : AaHook() {
    override val tagName: String = "AAD_AaClusterLyricEgressHook"
    override val usesDexKit: Boolean = true

    private const val READ_CACHE_TTL_MS = 200L
    private const val CACHE_SET_TITLE = "hook.AaClusterLyricEgressHook.set_title"
    private const val CACHE_META_P = "hook.AaClusterLyricEgressHook.meta_p"
    private const val CACHE_PLAY_Q = "hook.AaClusterLyricEgressHook.play_q"
    private const val CACHE_PLAY_L = "hook.AaClusterLyricEgressHook.play_l"
    private const val LYRIC_ONLY_PLAYBACK_SUPPRESS_MS = 500L
    /** Gearhead [AaPlaybackState] parent wrapper field holding [PlaybackStateCompat]. */
    private const val GEARHEAD_STATE_WRAPPER_FIELD = "b"
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
    /** [MediaMetadata.getDescription] reads this before [METADATA_KEY_ALBUM]. */
    private const val METADATA_KEY_DISPLAY_DESCRIPTION =
        "android.media.metadata.DISPLAY_DESCRIPTION"

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
        METADATA_KEY_DISPLAY_DESCRIPTION,
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
    @Volatile
    private var pendingLyricOnlyUntilElapsedMs = 0L
    @Volatile
    private var lyricOnlyPlaybackSent = false
    @Volatile
    private var cachedWrapperField: Field? = null

    private data class PushPlaybackBuild(
        val state: Any,
        val storeSec: Long,
    )

    override fun applyCache(
        cache: DexKitMethodCache.Session,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ): Boolean {
        if (!applyRequiredMethod(cache, lpparam, CACHE_SET_TITLE) { setTitleMethod = it } ||
            !applyRequiredMethod(cache, lpparam, CACHE_META_P) { metadataPushMethod = it } ||
            !applyRequiredMethod(cache, lpparam, CACHE_PLAY_Q) { playbackPushMethod = it } ||
            !applyRequiredMethod(cache, lpparam, CACHE_PLAY_L) { playbackCacheMethod = it }
        ) {
            return false
        }
        logDebug(
            tagName,
            "gearhead methods setTitle=${setTitleMethod != null} " +
                "metaP=${metadataPushMethod != null} playQ=${playbackPushMethod != null} " +
                "playL=${playbackCacheMethod != null} (cache)",
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
        if (setTitleMethod == null || metadataPushMethod == null ||
            playbackPushMethod == null || playbackCacheMethod == null
        ) {
            throw IllegalStateException(
                "DexKit egress incomplete setTitle=${setTitleMethod != null} " +
                    "metaP=${metadataPushMethod != null} playQ=${playbackPushMethod != null} " +
                    "playL=${playbackCacheMethod != null}",
            )
        }
        logDebug(
            tagName,
            "gearhead methods setTitle=${setTitleMethod != null} " +
                "metaP=${metadataPushMethod != null} playQ=${playbackPushMethod != null} " +
                "playL=${playbackCacheMethod != null} (live)",
        )
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookPlatformMediaMetadata()
        hookPlatformMediaController()
        hookCompatMediaMetadata(
            "android.support.v4.media.MediaMetadataCompat",
            lpparam.classLoader,
        )
        hookGearheadStatusBarAndHu()
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

    /** Push path bypasses the 200ms progress cache for a fresh extrapolation. */
    private fun readProgressFresh(cr: ContentResolver): ClusterLyricStore.Progress? =
        ClusterLyricStore.readProgress(cr)

    private fun alignWholeSecondMs(positionMs: Long): Long =
        (positionMs.coerceAtLeast(0L) / 1000L) * 1000L

    private fun unwrapPlaybackCompat(aaState: Any): PlaybackStateCompat? = runCatching {
        val field = cachedWrapperField ?: run {
            val parent = aaState.javaClass.superclass ?: return@runCatching null
            parent.getDeclaredField(GEARHEAD_STATE_WRAPPER_FIELD).also {
                it.isAccessible = true
                cachedWrapperField = it
            }
        }
        field.get(aaState) as? PlaybackStateCompat
    }.getOrNull()

    private fun mapCompatPlaybackState(playbackState: Int): Int = when (playbackState) {
        PlaybackState.STATE_PLAYING -> PlaybackStateCompat.STATE_PLAYING
        PlaybackState.STATE_PAUSED -> PlaybackStateCompat.STATE_PAUSED
        PlaybackState.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
        PlaybackState.STATE_STOPPED -> PlaybackStateCompat.STATE_STOPPED
        else -> PlaybackStateCompat.STATE_PLAYING
    }

    /**
     * Clone the cached Gearhead [AaPlaybackState] with Store-extrapolated position
     * (whole seconds) instead of replaying a stale [lastPlaybackState] snapshot.
     */
    private fun buildAaPlaybackStateForPush(template: Any): PushPlaybackBuild? = runCatching {
        val cr = InitFields.appContext.contentResolver
        val progress = readProgressFresh(cr) ?: return@runCatching null
        val compat = unwrapPlaybackCompat(template) ?: return@runCatching null
        val positionMs = alignWholeSecondMs(ClusterLyricStore.extrapolatePosition(progress))
        val patchedCompat = PlaybackStateCompat.Builder(compat)
            .setState(
                mapCompatPlaybackState(progress.playbackState),
                positionMs,
                progress.playbackSpeed,
                SystemClock.elapsedRealtime(),
            )
            .build()
        val ctor = template.javaClass.getConstructor(compat.javaClass)
        val aaState = ctor.newInstance(patchedCompat)
        PushPlaybackBuild(aaState, positionMs / 1000L)
    }.getOrNull()

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

    /**
     * Resolve a required DexKit target from cache. Missing key, cached null ("-"), or
     * classloader resolve failure drops the entry and returns false so [loadDexClass]
     * rescans and [saveCache] rewrites coordinates.
     */
    private fun applyRequiredMethod(
        cache: DexKitMethodCache.Session,
        lpparam: XC_LoadPackage.LoadPackageParam,
        key: String,
        setter: (Method) -> Unit,
    ): Boolean {
        if (!cache.hasKey(key)) return false
        val ref = cache.getRef(key) ?: run {
            cache.putString(key, null)
            logDebug(tagName, "cache miss $key (null ref), drop for rescan")
            return false
        }
        val method = cache.resolve(lpparam.classLoader, ref) ?: run {
            cache.putString(key, null)
            logDebug(tagName, "cache stale $key, drop for rescan")
            return false
        }
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

    private fun isLyricOnlyPlaybackWindow(): Boolean {
        val until = pendingLyricOnlyUntilElapsedMs
        return until > 0L && SystemClock.elapsedRealtime() < until
    }

    private fun hookGearheadStatusBarAndHu() {
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
                        val fresh = clusterFreshForHu(song, artist)
                        if (fresh == null) {
                            pendingLyricOnlyUntilElapsedMs = 0L
                            return
                        }
                        val artUnchanged =
                            artLen == lastHuArtLen ||
                                (artLen == 0 && lastHuArtLen <= 0)
                        val lyricOnly =
                            lastHuArtist == artist &&
                                lastHuAlbum == album &&
                                lastHuDuration == duration &&
                                lastHuDuration >= 0L &&
                                artUnchanged
                        lyricOnlyPlaybackSent = false
                        pendingLyricOnlyUntilElapsedMs = if (lyricOnly) {
                            logDebug(tagName, "HU metadata lyric-only window")
                            SystemClock.elapsedRealtime() + LYRIC_ONLY_PLAYBACK_SUPPRESS_MS
                        } else {
                            0L
                        }
                        lastHuArtist = artist
                        lastHuAlbum = album
                        lastHuDuration = duration
                        lastHuArtLen = artLen
                        // setTitle is swallowed in-place; Gearhead may keep the previous
                        // song on this push. Always write the current lyric line.
                        param.args[0] = fresh.title
                        injectShellAlbumArg(param, fresh)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isLyricOnlyPlaybackWindow()) return
                        if (lyricOnlyPlaybackSent) return
                        val song = param.args[0] as? String
                        val artist = param.args[1] as? String
                        if (clusterFreshForHu(song, artist) == null) return
                        pushPlaybackNow(param.thisObject)
                    }
                })
                log(tagName, "hooked HU metadata push ${method.declaringClass.name}#${method.name}")
            } catch (e: Throwable) {
                log(tagName, "hook metadata push failed", e)
            }
        }
    }

    private fun pushPlaybackNow(monitor: Any) {
        if (reenteringPlayback.get() == true) return
        val q = playbackPushMethod ?: return
        val template = lastPlaybackState ?: return
        val pkg = lastPlaybackPkg ?: return
        val built = buildAaPlaybackStateForPush(template)
        val state = built?.state ?: template
        reenteringPlayback.set(true)
        try {
            q.invoke(monitor, state, pkg)
            if (built != null) {
                logDebug(
                    tagName,
                    "pushPlaybackNow storeSec=${built.storeSec} fallback=${state === template}",
                )
            } else {
                logDebug(tagName, "pushPlaybackNow fallback=true")
            }
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

    /**
     * Cluster shell HU packet. Artist match is enough — song may still be the
     * previous lyric after [setTitle] is skipped in-place.
     */
    private fun clusterFreshForHu(
        song: String?,
        artist: String?,
    ): ClusterLyricStore.Fresh? {
        val cr = runCatching { InitFields.appContext.contentResolver }.getOrNull() ?: return null
        val fresh = readFreshCached(cr) ?: return null
        if (!artist.isNullOrEmpty() && fresh.subtitle.isNotEmpty()) {
            if (artist == fresh.subtitle) return fresh
        }
        if (!song.isNullOrEmpty() && song == fresh.title) return fresh
        return null
    }

    /** Audi cluster shows「未知专辑」when HU album arg is empty — inject from store. */
    private fun injectShellAlbumArg(
        param: XC_MethodHook.MethodHookParam,
        fresh: ClusterLyricStore.Fresh,
    ): String? {
        if (param.args.size <= 2) return null
        val current = param.args[2] as? String
        if (!current.isNullOrEmpty()) return current
        val album = fresh.album.takeIf { it.isNotEmpty() } ?: return null
        param.args[2] = album
        logDebug(tagName, "inject HU album=$album")
        return album
    }

    private fun applyStatusBarTitleInPlace(controller: Any, title: CharSequence): Boolean {
        val sbv = findTypedField(controller, STATUS_BAR_VIEW) ?: return false
        val tv = findTitleTextView(sbv) ?: return false
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
