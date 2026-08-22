package io.github.nitsuya.aa.display.xposed.hook.aa

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.service.ClusterLyricMediaService
import io.github.nitsuya.aa.display.xposed.cluster.ClusterArtStore
import io.github.nitsuya.aa.display.xposed.cluster.ClusterLyricStore
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug

/**
 * Fallback: when Gearhead reads our cluster shell [MediaMetadata] Title/Artist,
 * substitute [ClusterLyricStore] ticker text published by system_server.
 *
 * Only rewrites metadata whose MEDIA_ID starts with `aadisplay.cluster:` so QQ
 * (and other real players) keep their own Title for non-cluster UI / steering.
 */
object AaClusterLyricEgressHook : AaHook() {
    override val tagName: String = "AAD_AaClusterLyricEgressHook"

    private const val READ_CACHE_TTL_MS = 200L

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

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookPlatformMediaMetadata()
        hookPlatformMediaController()
        hookCompatMediaMetadata(
            "android.support.v4.media.MediaMetadataCompat",
            lpparam.classLoader,
        )
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
                param.result = buildPlatformPlaybackState(cr) ?: return@hookAfter
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

    private fun buildPlatformPlaybackState(cr: android.content.ContentResolver): PlaybackState? {
        val progress = readProgressCached(cr) ?: return null
        val positionMs = ClusterLyricStore.extrapolatePosition(progress)
        return PlaybackState.Builder()
            .setState(
                progress.playbackState,
                positionMs,
                progress.playbackSpeed,
                SystemClock.elapsedRealtime(),
            )
            .setActions(0)
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
}
