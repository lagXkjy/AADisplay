package io.github.nitsuya.aa.display.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.os.Binder
import java.util.concurrent.ConcurrentHashMap

/**
 * Music vs short-video heuristics for single-sounder + cluster binding.
 *
 * - [isMusicPackage] / [isMusicSession]: long-form audio (sticky under maps; see AvMediaArbiter §4).
 * - [isVideoPackage] / [isVideoSession]: short-video / Feed players (Douyin; same sticky rule).
 * - [isAvMediaPackage] / [isAvMediaSession]: music ∪ video — only one may sound.
 *
 * Cluster lyric sources are the known trio in [KNOWN_MUSIC_PACKAGES] (QQ 车载 / HD / 汽水);
 * see [io.github.nitsuya.aa.display.xposed.cluster.LyricLineExtractor.PREFERRED_PACKAGE_ORDER].
 */
object MusicAppClassifier {
    /** Same as [android.media.browse.MediaBrowserService.SERVICE_INTERFACE]. */
    private const val MEDIA_BROWSER_SERVICE = "android.media.browse.MediaBrowserService"

    /** Douyin main package — Feed has MediaBrowserService but no useful Now Playing for cluster. */
    private const val DOUYIN_PKG = "com.ss.android.ugc.aweme"

    private val VIDEO_PACKAGES = setOf(DOUYIN_PKG)

    /**
     * Preferred cluster lyric sources. Often omit MediaBrowserService / APP_MUSIC
     * (QQ Music Car exposes proprietary services only). Keep in sync with
     * [io.github.nitsuya.aa.display.xposed.cluster.LyricLineExtractor.PREFERRED_PACKAGE_ORDER].
     */
    val KNOWN_MUSIC_PACKAGES = setOf(
        "com.tencent.qqmusiccar",
        "com.tencent.qqmusicpad",
        "com.luna.music",
    )

    private val byPackage = ConcurrentHashMap<String, Boolean>()

    fun isVideoPackage(packageName: String?): Boolean {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return pkg in VIDEO_PACKAGES
    }

    fun isVideoSession(controller: MediaController): Boolean =
        isVideoPackage(controller.packageName)

    /** Package declares a music player / MediaBrowserService, or is a known lyric source. */
    fun isMusicPackage(context: Context, packageName: String?): Boolean {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (isVideoPackage(pkg)) {
            byPackage[pkg] = false
            return false
        }
        if (pkg in KNOWN_MUSIC_PACKAGES) {
            byPackage[pkg] = true
            return true
        }
        byPackage[pkg]?.let { return it }
        val identity = Binder.clearCallingIdentity()
        val result = try {
            detectPackage(context, pkg)
        } catch (_: Throwable) {
            false
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        byPackage[pkg] = result
        return result
    }

    /**
     * True if [controller]'s package is music, or the session looks like long-form music.
     * Video packages are never music.
     */
    fun isMusicSession(context: Context, controller: MediaController): Boolean {
        val pkg = controller.packageName
        if (isVideoPackage(pkg)) return false
        if (isMusicPackage(context, pkg)) return true
        return looksLikeMusicMetadata(controller.metadata)
    }

    /** Music or video — single-sounder set. */
    fun isAvMediaPackage(context: Context, packageName: String?): Boolean {
        if (isVideoPackage(packageName)) return true
        return isMusicPackage(context, packageName)
    }

    /** Eligible for single-sounder arbitration. */
    fun isAvMediaSession(context: Context, controller: MediaController): Boolean {
        if (isVideoSession(controller)) return true
        return isMusicSession(context, controller)
    }

    fun invalidate(packageName: String? = null) {
        if (packageName.isNullOrBlank()) {
            byPackage.clear()
        } else {
            byPackage.remove(packageName.trim())
        }
    }

    private fun detectPackage(context: Context, pkg: String): Boolean {
        val pm = context.packageManager
        if (hasService(pm, Intent(MEDIA_BROWSER_SERVICE).setPackage(pkg))) {
            return true
        }
        if (hasActivity(pm, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC).setPackage(pkg))) {
            return true
        }
        if (hasActivity(pm, Intent("android.intent.action.MUSIC_PLAYER").setPackage(pkg))) {
            return true
        }
        return false
    }

    private fun hasService(pm: PackageManager, intent: Intent): Boolean {
        return pm.queryIntentServices(intent, 0).isNotEmpty()
    }

    private fun hasActivity(pm: PackageManager, intent: Intent): Boolean {
        return pm.queryIntentActivities(intent, 0).isNotEmpty()
    }

    private fun looksLikeMusicMetadata(meta: MediaMetadata?): Boolean {
        if (meta == null) return false
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        val album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty()
        if (artist.isEmpty() && album.isEmpty()) return false
        // Prefer long-form tracks; still accept artist/album when duration is missing
        // (QQ Music Car often omits or zeros DURATION on the session).
        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
        return duration <= 0L || duration >= MUSIC_DURATION_MIN_MS
    }

    private const val MUSIC_DURATION_MIN_MS = 60_000L
}
