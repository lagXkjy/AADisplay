package io.github.nitsuya.aa.display.xposed.cluster

import android.content.ContentResolver
import android.media.session.PlaybackState
import android.os.SystemClock
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
    const val SETTINGS_POSITION_MS = "aadisplay_cluster_np_position_ms"
    const val SETTINGS_DURATION_MS = "aadisplay_cluster_np_duration_ms"
    /** [android.os.SystemClock.elapsedRealtime] when [SETTINGS_POSITION_MS] was sampled. */
    const val SETTINGS_POSITION_AT_MS = "aadisplay_cluster_np_position_at_ms"
    const val SETTINGS_PLAYBACK_STATE = "aadisplay_cluster_np_playback_state"
    const val SETTINGS_PLAYBACK_SPEED = "aadisplay_cluster_np_playback_speed"

    /** Stale after this — gearhead must not keep injecting an old lyric. */
    const val STALE_AFTER_MS = 15_000L

    private const val TAG = "AAD_ClusterLyricStore"
    private const val PROGRESS_MIN_WRITE_MS = 1_000L
    private const val PROGRESS_POS_DEDUP_MS = 400L
    private const val PROGRESS_SEEK_MS = 1_500L

    @Volatile
    var cachedTitle: String = ""
        private set

    @Volatile
    var cachedSubtitle: String = ""
        private set

    @Volatile
    var cachedAlbum: String = ""
        private set

    private var lastProgressPositionMs: Long = -1L
    private var lastProgressDurationMs: Long = -1L
    private var lastProgressState: Int = PlaybackState.STATE_NONE
    private var lastProgressSpeed: Float = Float.NaN
    private var lastProgressWriteElapsedMs: Long = 0L

    data class Fresh(
        val title: String,
        val subtitle: String,
        val album: String,
    )

    data class Progress(
        val positionMs: Long,
        val durationMs: Long,
        val positionAtElapsedMs: Long,
        val playbackState: Int,
        val playbackSpeed: Float,
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

    /** Push playback position / duration from the bound QQ / Luna session (may run every 300ms). */
    fun publishProgress(
        cr: ContentResolver,
        positionMs: Long,
        durationMs: Long,
        positionAtElapsedMs: Long,
        playbackState: Int,
        playbackSpeed: Float,
    ) {
        if (cachedTitle.isEmpty()) return
        val pos = positionMs.coerceAtLeast(0L)
        val dur = durationMs.coerceAtLeast(0L)
        val speed = playbackSpeed.let { if (it == 0f) 1f else it }
        val now = SystemClock.elapsedRealtime()
        val stateChanged =
            playbackState != lastProgressState ||
                speed != lastProgressSpeed ||
                dur != lastProgressDurationMs
        val delta = if (lastProgressPositionMs >= 0L) kotlin.math.abs(pos - lastProgressPositionMs) else Long.MAX_VALUE
        val seek = delta > PROGRESS_SEEK_MS
        val withinMinWrite = now - lastProgressWriteElapsedMs < PROGRESS_MIN_WRITE_MS
        if (!stateChanged && !seek && delta < PROGRESS_POS_DEDUP_MS && withinMinWrite) {
            return
        }
        lastProgressPositionMs = pos
        lastProgressDurationMs = dur
        lastProgressState = playbackState
        lastProgressSpeed = speed
        lastProgressWriteElapsedMs = now
        runCatching {
            Settings.Global.putString(cr, SETTINGS_POSITION_MS, pos.toString())
            Settings.Global.putString(cr, SETTINGS_DURATION_MS, dur.toString())
            Settings.Global.putString(cr, SETTINGS_POSITION_AT_MS, positionAtElapsedMs.toString())
            Settings.Global.putString(cr, SETTINGS_PLAYBACK_STATE, playbackState.toString())
            Settings.Global.putString(cr, SETTINGS_PLAYBACK_SPEED, speed.toString())
            cr.notifyChange(Settings.Global.getUriFor(SETTINGS_POSITION_MS), null)
        }.onFailure { e ->
            log(TAG, "publishProgress Settings.Global failed", e)
        }
    }

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
        lastProgressPositionMs = -1L
        lastProgressDurationMs = -1L
        lastProgressState = PlaybackState.STATE_NONE
        lastProgressSpeed = Float.NaN
        lastProgressWriteElapsedMs = 0L
        runCatching {
            Settings.Global.putString(cr, SETTINGS_TITLE, "")
            Settings.Global.putString(cr, SETTINGS_SUBTITLE, "")
            Settings.Global.putString(cr, SETTINGS_ALBUM, "")
            Settings.Global.putString(cr, SETTINGS_UPDATED_MS, "0")
            Settings.Global.putString(cr, SETTINGS_POSITION_MS, "0")
            Settings.Global.putString(cr, SETTINGS_DURATION_MS, "0")
            Settings.Global.putString(cr, SETTINGS_POSITION_AT_MS, "0")
            Settings.Global.putString(cr, SETTINGS_PLAYBACK_STATE, "0")
            Settings.Global.putString(cr, SETTINGS_PLAYBACK_SPEED, "1")
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

    fun readProgress(cr: ContentResolver): Progress? {
        if (readFresh(cr) == null) return null
        val positionMs = Settings.Global.getString(cr, SETTINGS_POSITION_MS)?.toLongOrNull() ?: 0L
        val durationMs = Settings.Global.getString(cr, SETTINGS_DURATION_MS)?.toLongOrNull() ?: 0L
        val positionAtElapsedMs =
            Settings.Global.getString(cr, SETTINGS_POSITION_AT_MS)?.toLongOrNull() ?: 0L
        val playbackState =
            Settings.Global.getString(cr, SETTINGS_PLAYBACK_STATE)?.toIntOrNull()
                ?: PlaybackState.STATE_NONE
        val playbackSpeed =
            Settings.Global.getString(cr, SETTINGS_PLAYBACK_SPEED)?.toFloatOrNull()?.let {
                if (it == 0f) 1f else it
            } ?: 1f
        return Progress(
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            positionAtElapsedMs = positionAtElapsedMs,
            playbackState = playbackState,
            playbackSpeed = playbackSpeed,
        )
    }

    /** Extrapolate position for playing / seeking states (same math as [LyricLineExtractor]). */
    fun extrapolatePosition(progress: Progress): Long {
        val base = progress.positionMs.coerceAtLeast(0L)
        val updated = progress.positionAtElapsedMs
        if (updated <= 0L) return clampPosition(base, progress.durationMs)
        val st = progress.playbackState
        if (st != PlaybackState.STATE_PLAYING &&
            st != PlaybackState.STATE_FAST_FORWARDING &&
            st != PlaybackState.STATE_REWINDING
        ) {
            return clampPosition(base, progress.durationMs)
        }
        val elapsed = (SystemClock.elapsedRealtime() - updated).coerceAtLeast(0L)
        val raw = (base + (elapsed * progress.playbackSpeed).toLong()).coerceAtLeast(0L)
        return clampPosition(raw, progress.durationMs)
    }

    fun clampPosition(positionMs: Long, durationMs: Long): Long {
        val pos = positionMs.coerceAtLeast(0L)
        return if (durationMs > 0L) pos.coerceAtMost(durationMs) else pos
    }
}
