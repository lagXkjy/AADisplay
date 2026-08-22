package io.github.nitsuya.aa.display.xposed.cluster

import android.content.ContentResolver
import android.provider.Settings
import io.github.nitsuya.aa.display.xposed.util.log

/**
 * Cross-process Now Playing strings for instrument-cluster ticker (AA Title path).
 * Written from system_server; read from gearhead egress rewrite / `:cluster` media shell.
 *
 * Settings.Global keys must stay stable — gearhead and system_server both use them.
 */
object ClusterLyricStore {
    const val SETTINGS_TITLE = "aadisplay_cluster_np_title"
    /** Car panel artist line — typically「歌名 — 歌手」. */
    const val SETTINGS_SUBTITLE = "aadisplay_cluster_np_subtitle"
    const val SETTINGS_ALBUM = "aadisplay_cluster_np_album"
    const val SETTINGS_UPDATED_MS = "aadisplay_cluster_np_updated_ms"

    /** Stale after this — gearhead must not keep injecting an old lyric. */
    const val STALE_AFTER_MS = 15_000L

    private const val TAG = "AAD_ClusterLyricStore"

    @Volatile
    var cachedTitle: String = ""
        private set

    @Volatile
    var cachedSubtitle: String = ""
        private set

    @Volatile
    var cachedAlbum: String = ""
        private set

    data class Fresh(
        val title: String,
        val subtitle: String,
        val album: String,
    )

    fun publish(
        cr: ContentResolver,
        title: String,
        subtitle: String,
        album: String,
    ) {
        val t = title.trim()
        val s = subtitle.trim()
        val a = album.trim()
        // Same line / same artist / same album: only refresh updated_ms (egress stale gate).
        if (t == cachedTitle && s == cachedSubtitle && a == cachedAlbum) {
            touch(cr)
            return
        }
        cachedTitle = t
        cachedSubtitle = s
        cachedAlbum = a
        runCatching {
            Settings.Global.putString(cr, SETTINGS_TITLE, t)
            Settings.Global.putString(cr, SETTINGS_SUBTITLE, s)
            Settings.Global.putString(cr, SETTINGS_ALBUM, a)
            writeUpdatedMs(cr)
        }.onFailure { e ->
            log(TAG, "publish Settings.Global failed", e)
        }
    }

    /** Refresh egress freshness while title unchanged (歌名兜底 / 同句歌词 hold). */
    fun touch(cr: ContentResolver) {
        if (cachedTitle.isEmpty()) return
        runCatching {
            writeUpdatedMs(cr)
        }.onFailure { e ->
            log(TAG, "touch Settings.Global failed", e)
        }
    }

    private fun writeUpdatedMs(cr: ContentResolver) {
        Settings.Global.putString(
            cr,
            SETTINGS_UPDATED_MS,
            System.currentTimeMillis().toString(),
        )
    }

    fun clear(cr: ContentResolver) {
        cachedTitle = ""
        cachedSubtitle = ""
        cachedAlbum = ""
        runCatching {
            Settings.Global.putString(cr, SETTINGS_TITLE, "")
            Settings.Global.putString(cr, SETTINGS_SUBTITLE, "")
            Settings.Global.putString(cr, SETTINGS_ALBUM, "")
            Settings.Global.putString(cr, SETTINGS_UPDATED_MS, "0")
            ClusterArtStore.clear(cr)
        }.onFailure { e ->
            log(TAG, "clear Settings.Global failed", e)
        }
    }

    /** Gearhead-side read; empty when unset or stale. Title + subtitle + album in one Settings pass. */
    fun readFresh(cr: ContentResolver): Fresh? {
        val title = Settings.Global.getString(cr, SETTINGS_TITLE)?.trim().orEmpty()
        if (title.isEmpty()) return null
        val updated = Settings.Global.getString(cr, SETTINGS_UPDATED_MS)?.toLongOrNull() ?: 0L
        if (updated <= 0L) return null
        if (System.currentTimeMillis() - updated > STALE_AFTER_MS) return null
        val subtitle = Settings.Global.getString(cr, SETTINGS_SUBTITLE)?.trim().orEmpty()
        val album = Settings.Global.getString(cr, SETTINGS_ALBUM)?.trim().orEmpty()
        return Fresh(title, subtitle, album)
    }

    fun readFreshTitle(cr: ContentResolver): String? = readFresh(cr)?.title

    fun readFreshSubtitle(cr: ContentResolver): String? =
        readFresh(cr)?.subtitle?.takeIf { it.isNotEmpty() }

    fun readFreshAlbum(cr: ContentResolver): String? =
        readFresh(cr)?.album?.takeIf { it.isNotEmpty() }
}
