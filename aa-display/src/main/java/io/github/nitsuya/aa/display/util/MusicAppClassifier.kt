package io.github.nitsuya.aa.display.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.os.Binder
import java.util.concurrent.ConcurrentHashMap

/**
 * Heuristic music-app detection without a hardcoded package allowlist.
 * Prefers declared media capabilities; falls back to session metadata shape.
 */
object MusicAppClassifier {
    /** Same as [android.media.browse.MediaBrowserService.SERVICE_INTERFACE]. */
    private const val MEDIA_BROWSER_SERVICE = "android.media.browse.MediaBrowserService"

    private val byPackage = ConcurrentHashMap<String, Boolean>()

    /** Package declares a music player / MediaBrowserService. */
    fun isMusicPackage(context: Context, packageName: String?): Boolean {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return false
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
     * True if [controller]'s package is a music app, or the active session looks like
     * long-form music (so buried NetEase / Spotify keep playing even without browser service).
     */
    fun isMusicSession(context: Context, controller: MediaController): Boolean {
        val pkg = controller.packageName
        if (isMusicPackage(context, pkg)) return true
        return looksLikeMusicMetadata(controller.metadata)
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
        // Legacy music-player launcher intent (still common on Chinese OEMs).
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
        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (duration < MUSIC_DURATION_MIN_MS) return false
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        val album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty()
        return artist.isNotEmpty() || album.isNotEmpty()
    }

    private const val MUSIC_DURATION_MIN_MS = 60_000L
}
