package io.github.nitsuya.aa.display.xposed.cluster

import android.content.ContentResolver
import android.provider.Settings
import android.os.SystemClock

/**
 * Cross-process Now Playing strings for instrument-cluster ticker (AA Title path).
 * Written from system_server; read from gearhead egress rewrite.
 *
 * Settings.Global keys must stay stable — gearhead and system_server both use them.
 */
object ClusterLyricStore {
    const val SETTINGS_TITLE = "aadisplay_cluster_np_title"
    const val SETTINGS_SUBTITLE = "aadisplay_cluster_np_subtitle"
    const val SETTINGS_UPDATED_MS = "aadisplay_cluster_np_updated_ms"

    /** Stale after this — gearhead must not keep injecting an old lyric. */
    const val STALE_AFTER_MS = 15_000L

    @Volatile
    var cachedTitle: String = ""
        private set

    @Volatile
    var cachedSubtitle: String = ""
        private set

    @Volatile
    var cachedUpdatedElapsedMs: Long = 0L
        private set

    fun publish(cr: ContentResolver, title: String, subtitle: String) {
        val t = title.trim()
        val s = subtitle.trim()
        cachedTitle = t
        cachedSubtitle = s
        runCatching {
            Settings.Global.putString(cr, SETTINGS_TITLE, t)
            Settings.Global.putString(cr, SETTINGS_SUBTITLE, s)
        }
        writeUpdatedMs(cr)
    }

    /** Refresh egress freshness while title unchanged (歌名兜底 / 同句歌词 hold). */
    fun touch(cr: ContentResolver) {
        if (cachedTitle.isEmpty()) return
        writeUpdatedMs(cr)
    }

    private fun writeUpdatedMs(cr: ContentResolver) {
        cachedUpdatedElapsedMs = SystemClock.elapsedRealtime()
        runCatching {
            Settings.Global.putString(
                cr,
                SETTINGS_UPDATED_MS,
                System.currentTimeMillis().toString(),
            )
        }
    }

    fun clear(cr: ContentResolver) {
        cachedTitle = ""
        cachedSubtitle = ""
        cachedUpdatedElapsedMs = 0L
        runCatching {
            Settings.Global.putString(cr, SETTINGS_TITLE, "")
            Settings.Global.putString(cr, SETTINGS_SUBTITLE, "")
            Settings.Global.putString(cr, SETTINGS_UPDATED_MS, "0")
        }
    }

    /** Gearhead-side read; empty when unset or stale. */
    fun readFreshTitle(cr: ContentResolver): String? {
        val title = Settings.Global.getString(cr, SETTINGS_TITLE)?.trim().orEmpty()
        if (title.isEmpty()) return null
        val updated = Settings.Global.getString(cr, SETTINGS_UPDATED_MS)?.toLongOrNull() ?: 0L
        if (updated <= 0L) return null
        if (System.currentTimeMillis() - updated > STALE_AFTER_MS) return null
        return title
    }

    fun readFreshSubtitle(cr: ContentResolver): String? {
        if (readFreshTitle(cr) == null) return null
        return Settings.Global.getString(cr, SETTINGS_SUBTITLE)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
