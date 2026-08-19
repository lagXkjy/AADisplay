package io.github.nitsuya.aa.display.xposed.cluster

import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug

/**
 * Metadata-only [MediaSession] so Gearhead can publish AA Now Playing to the HU/cluster.
 * Never takes AudioFocus or media buttons — real player stays in control.
 */
class ShadowNowPlayingSession(context: Context) {
    companion object {
        const val TAG = "AAD_ShadowNowPlaying"
        const val SESSION_TAG = "AADisplay.ClusterLyric"
    }

    private val session = MediaSession(context, SESSION_TAG).apply {
        setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean = false
        })
    }

    @Volatile
    private var lastTitle: String = ""

    @Volatile
    private var lastMediaId: String = ""

    fun update(
        title: String,
        artist: String,
        mediaId: String,
        durationMs: Long,
        playbackState: Int,
        positionMs: Long,
    ) {
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist)
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, mediaId)
            .apply {
                if (durationMs > 0L) {
                    putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                }
            }
            .build()
        val state = PlaybackState.Builder()
            .setState(
                playbackState,
                positionMs.coerceAtLeast(0L),
                if (playbackState == PlaybackState.STATE_PLAYING) 1f else 0f,
            )
            // Advertise nothing actionable — media keys must stay on the real app.
            .setActions(0)
            .build()
        try {
            session.setMetadata(meta)
            session.setPlaybackState(state)
            if (!session.isActive) {
                session.isActive = true
                log(TAG, "shadow active title=$title mediaId=$mediaId")
            } else if (title != lastTitle || mediaId != lastMediaId) {
                logDebug(TAG, "shadow update title=$title mediaId=$mediaId state=$playbackState")
            }
            lastTitle = title
            lastMediaId = mediaId
        } catch (e: Throwable) {
            log(TAG, "shadow update failed", e)
        }
    }

    fun deactivate() {
        try {
            if (session.isActive) {
                session.isActive = false
                log(TAG, "shadow deactivated")
            }
            session.setMetadata(null)
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_NONE, 0L, 0f)
                    .setActions(0)
                    .build(),
            )
            lastTitle = ""
            lastMediaId = ""
        } catch (e: Throwable) {
            log(TAG, "shadow deactivate failed", e)
        }
    }

    fun release() {
        deactivate()
        runCatching { session.release() }
    }
}
