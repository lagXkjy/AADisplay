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
 * When Gearhead reads our cluster shell [MediaMetadata] Title/Artist,
 * substitute [ClusterLyricStore] ticker text published by system_server.
 *
 * Only rewrites metadata whose MEDIA_ID starts with `aadisplay.cluster:` so QQ
 * (and other real players) keep their own Title for non-cluster UI / steering.
 *
 * StatusBar [setTitle] is updated in-place (no layout switch). HU metadata push
 * injects album when Gearhead would send an empty third line.
 */
object AaClusterLyricEgressHook : AaHook() {
    override val tagName: String = "AAD_AaClusterLyricEgressHook"
    override val usesDexKit: Boolean = true

    private const val READ_CACHE_TTL_MS = 200L
    private const val CACHE_SET_TITLE = "hook.AaClusterLyricEgressHook.set_title"
    private const val CACHE_META_P = "hook.AaClusterLyricEgressHook.meta_p"
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

    override fun applyCache(
        cache: DexKitMethodCache.Session,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ): Boolean {
        if (!applyOptionalMethod(cache, lpparam, CACHE_SET_TITLE) { setTitleMethod = it } ||
            !applyOptionalMethod(cache, lpparam, CACHE_META_P) { metadataPushMethod = it }
        ) {
            return false
        }
        logDebug(
            tagName,
            "gearhead methods setTitle=${setTitleMethod != null} " +
                "metaP=${metadataPushMethod != null} (cache)",
        )
        return true
    }

    override fun saveCache(
        cache: DexKitMethodCache.Session,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ) {
        cache.putRef(CACHE_SET_TITLE, setTitleMethod)
        cache.putRef(CACHE_META_P, metadataPushMethod)
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
        logDebug(
            tagName,
            "gearhead methods setTitle=${setTitleMethod != null} " +
                "metaP=${metadataPushMethod != null} (live)",
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
        metadataPushMethod?.let { method ->
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val song = param.args[0] as? String
                        val artist = param.args[1] as? String
                        val fresh = clusterFreshForHu(song, artist) ?: return
                        // setTitle is swallowed in-place; Gearhead may keep the previous
                        // song on this push. Always write the current lyric line.
                        param.args[0] = fresh.title
                        injectShellAlbumArg(param, fresh)
                    }
                })
                log(tagName, "hooked HU metadata push ${method.declaringClass.name}#${method.name}")
            } catch (e: Throwable) {
                log(tagName, "hook metadata push failed", e)
            }
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
