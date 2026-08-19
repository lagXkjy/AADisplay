package io.github.nitsuya.aa.display.xposed.hook.aa

import android.media.MediaMetadata
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.cluster.ClusterLyricStore
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug

/**
 * M3 fallback: when Gearhead reads [MediaMetadata] Title for AA Now Playing,
 * substitute [ClusterLyricStore] ticker text published by system_server.
 *
 * Keeps Dashboard UI suppressed elsewhere; only rewrites metadata strings.
 */
object AaClusterLyricEgressHook : AaHook() {
    override val tagName: String = "AAD_AaClusterLyricEgressHook"

    private val titleKeys = setOf(
        MediaMetadata.METADATA_KEY_TITLE,
        MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
    )

    private val subtitleKeys = setOf(
        MediaMetadata.METADATA_KEY_ARTIST,
        MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
        MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
    )

    override fun isSupportProcess(processName: String): Boolean {
        return processProjection == processName || processCar == processName
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookPlatformMediaMetadata()
        hookCompatMediaMetadata(
            "android.support.v4.media.MediaMetadataCompat",
            lpparam.classLoader,
        )
        hookCompatMediaMetadata(
            "androidx.media.MediaMetadataCompat",
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
                rewriteTitleArg(param.args[0] as? String, param)
            }
            findMethod(MediaMetadata::class.java) {
                name == "getText" &&
                    parameterCount == 1 &&
                    parameterTypes[0] == String::class.java
            }.hookAfter { param ->
                rewriteTitleArg(param.args[0] as? String, param, asCharSequence = true)
            }
            log(tagName, "hooked android.media.MediaMetadata getString/getText")
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
                rewriteTitleArg(param.args[0] as? String, param)
            }
            log(tagName, "hooked $className.getString")
        } catch (e: Throwable) {
            logDebug(tagName, "hook $className skipped: ${e.message}")
        }
    }

    private fun rewriteTitleArg(
        key: String?,
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam,
        asCharSequence: Boolean = false,
    ) {
        if (key.isNullOrEmpty()) return
        val cr = runCatching { InitFields.appContext.contentResolver }.getOrNull() ?: return
        when {
            key in titleKeys -> {
                val override = ClusterLyricStore.readFreshTitle(cr) ?: return
                param.result = if (asCharSequence) override else override
            }
            key in subtitleKeys -> {
                val override = ClusterLyricStore.readFreshSubtitle(cr) ?: return
                param.result = if (asCharSequence) override else override
            }
        }
    }
}
