package io.github.nitsuya.aa.display.aap

import aap_protobuf.service.mediaplayback.message.MediaPlaybackMetadataOuterClass.MediaPlaybackMetadata
import aap_protobuf.service.mediaplayback.message.MediaPlaybackStatusOuterClass.MediaPlaybackStatus
import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.google.protobuf.ByteString
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.xposed.cluster.ClusterArtStore
import io.github.nitsuya.aa.display.xposed.cluster.ClusterLyricStore
import net.prototux.aare.aap.channels.MediaPlaybackChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AAP Now Playing / cluster card driver.
 *
 * Same [ClusterLyricStore] / [ClusterArtStore] source as CarPlay and the gearhead
 * Title path. Pushes [MediaPlaybackChannel] metadata (song = current lyric line) and
 * status; track identity follows [ClusterLyricStore.SETTINGS_TRACK_MEDIA_ID] so a
 * lyric-only title change does not reset the clock.
 */
class AapNowPlaying {
    private val active = AtomicBoolean(false)

    @SuppressLint("StaticFieldLeak")
    @Volatile private var context: Context? = null
    @Volatile private var channel: MediaPlaybackChannel? = null
    private var handler: Handler? = null
    private var observer: ContentObserver? = null
    private var tick: Runnable? = null

    @Volatile private var pushedSong: String? = null
    @Volatile private var pushedTrackId: String? = null
    @Volatile private var pushedArtist: String? = null
    @Volatile private var pushedAlbum: String? = null
    @Volatile private var pushedSource: String? = null
    @Volatile private var pushedDurationSec: Int? = null
    @Volatile private var pushedState: MediaPlaybackStatus.State? = null
    @Volatile private var pushedElapsedSec: Long = -1L
    @Volatile private var pushedArtRevision: Long = -1L
    @Volatile private var pushedArtTrackId: String? = null
    @Volatile private var cachedArtJpeg: ByteArray? = null

    fun start(context: Context) {
        if (!active.compareAndSet(false, true)) return
        val app = context.applicationContext
        this.context = app
        val h = Handler(Looper.getMainLooper())
        handler = h
        val obs = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                h.post { refresh() }
            }
        }
        observer = obs
        try {
            val cr = app.contentResolver
            for (key in OBSERVED_KEYS) {
                cr.registerContentObserver(Settings.Global.getUriFor(key), false, obs)
            }
        } catch (e: Throwable) {
            Log.w(AapConsts.TAG, "AapNowPlaying observer failed", e)
        }
        val r = object : Runnable {
            override fun run() {
                if (!active.get()) return
                refresh()
                h.postDelayed(this, ELAPSED_TICK_MS)
            }
        }
        tick = r
        h.postDelayed(r, ELAPSED_TICK_MS)
        // Channel may have been attached during discovery before start().
        h.post { refresh(full = true) }
        Log.i(AapConsts.TAG, "AapNowPlaying observer armed")
    }

    fun attach(channel: MediaPlaybackChannel) {
        this.channel = channel
        clearDiff()
        handler?.post { refresh(full = true) }
        Log.i(AapConsts.TAG, "AapNowPlaying channel attached")
    }

    fun stop() {
        if (!active.compareAndSet(true, false)) return
        handler?.removeCallbacksAndMessages(null)
        tick = null
        handler = null
        observer?.let {
            try {
                context?.contentResolver?.unregisterContentObserver(it)
            } catch (_: Throwable) {
            }
        }
        observer = null
        channel = null
        context = null
        clearDiff()
        Log.i(AapConsts.TAG, "AapNowPlaying stopped")
    }

    private fun clearDiff() {
        pushedSong = null
        pushedTrackId = null
        pushedArtist = null
        pushedAlbum = null
        pushedSource = null
        pushedDurationSec = null
        pushedState = null
        pushedElapsedSec = -1L
        pushedArtRevision = -1L
        pushedArtTrackId = null
        cachedArtJpeg = null
    }

    private fun refresh(full: Boolean = false) {
        if (!active.get()) return
        val ctx = context ?: return
        val ch = channel ?: return
        val cr = ctx.contentResolver
        val fresh = runCatching { ClusterLyricStore.readFresh(cr) }.getOrNull()
        val progress = runCatching { ClusterLyricStore.readProgress(cr) }.getOrNull()
        val sourcePkg =
            Settings.Global.getString(cr, ClusterLyricStore.SETTINGS_SOURCE_PKG)?.trim().orEmpty()
                .ifEmpty { null }
        val trackId =
            Settings.Global.getString(cr, ClusterLyricStore.SETTINGS_TRACK_MEDIA_ID)?.trim().orEmpty()
                .ifEmpty { null }
        val artRevision =
            Settings.Global.getString(cr, ClusterArtStore.SETTINGS_ART_REVISION)?.toLongOrNull()
                ?: 0L

        val song = fresh?.title?.ifBlank { null }
        val artist = fresh?.subtitle?.ifBlank { null }
        val album = fresh?.album?.ifBlank { null }
        val durationSec = progress?.durationMs?.takeIf { it > 0L }?.let { (it / 1000L).toInt() }
        val playing =
            progress?.playbackState == PlaybackState.STATE_PLAYING && fresh != null
        val state = when {
            fresh == null -> MediaPlaybackStatus.State.STOPPED
            playing -> MediaPlaybackStatus.State.PLAYING
            else -> MediaPlaybackStatus.State.PAUSED
        }

        val trackChanged = trackId != pushedTrackId
        if (trackChanged) pushedTrackId = trackId

        val metaBuilder = MediaPlaybackMetadata.newBuilder()
        var metaDirty = full || trackChanged
        if (song != pushedSong || full) {
            pushedSong = song
            metaBuilder.setSong(song.orEmpty())
            metaDirty = true
        }
        if (artist != pushedArtist || full) {
            pushedArtist = artist
            metaBuilder.setArtist(artist.orEmpty())
            metaDirty = true
        }
        if (album != pushedAlbum || full) {
            pushedAlbum = album
            metaBuilder.setAlbum(album.orEmpty())
            metaDirty = true
        }
        if (durationSec != pushedDurationSec || full) {
            pushedDurationSec = durationSec
            if (durationSec != null) metaBuilder.setDurationSeconds(durationSec)
            metaDirty = true
        }

        if (artRevision != pushedArtRevision || (trackChanged && artRevision > 0L)) {
            if (artRevision > 0L) {
                if (attachArt(metaBuilder, artRevision, trackId, forceFetch = true)) metaDirty = true
            } else {
                pushedArtRevision = artRevision
                pushedArtTrackId = null
                cachedArtJpeg = null
            }
        } else if (
            pushedArtTrackId == trackId &&
            artRevision > 0L &&
            (full || trackChanged) &&
            cachedArtJpeg != null
        ) {
            cachedArtJpeg?.let { metaBuilder.setAlbumArt(ByteString.copyFrom(it)) }
            metaDirty = true
        }

        if (metaDirty && (
                song != null || artist != null || album != null ||
                    durationSec != null || metaBuilder.hasAlbumArt()
                )
        ) {
            // Always include song on any meta push so HU refreshes the lyric strip.
            if (!metaBuilder.hasSong()) metaBuilder.setSong(song.orEmpty())
            runCatching { ch.metadata(metaBuilder.build()) }
                .onFailure { Log.w(AapConsts.TAG, "MediaPlayback metadata failed", it) }
        }

        val statusBuilder = MediaPlaybackStatus.newBuilder().setState(state)
        var statusDirty = state != pushedState || full
        if (state != pushedState) pushedState = state
        if (sourcePkg != pushedSource || full) {
            pushedSource = sourcePkg
            statusBuilder.setMediaSource(sourcePkg.orEmpty())
            statusDirty = true
        }
        if (full) pushedElapsedSec = -1L
        val elapsedSec = if (state == MediaPlaybackStatus.State.PLAYING && progress != null) {
            ClusterLyricStore.extrapolatePosition(progress) / 1000L
        } else {
            -1L
        }
        if (elapsedSec >= 0L && (elapsedSec != pushedElapsedSec || full || (song != null && metaDirty))) {
            pushedElapsedSec = elapsedSec
            statusBuilder.setPlaybackSeconds(elapsedSec.toInt().coerceAtLeast(0))
            statusDirty = true
        }
        if (statusDirty) {
            runCatching { ch.status(statusBuilder.build()) }
                .onFailure { Log.w(AapConsts.TAG, "MediaPlayback status failed", it) }
        }
    }

    private fun attachArt(
        builder: MediaPlaybackMetadata.Builder,
        revision: Long,
        trackId: String?,
        forceFetch: Boolean,
    ): Boolean {
        val jpeg = if (
            !forceFetch &&
            pushedArtRevision == revision &&
            pushedArtTrackId == trackId
        ) {
            cachedArtJpeg
        } else {
            try {
                CoreApi.getClusterArtJpeg()
            } catch (e: Throwable) {
                Log.w(AapConsts.TAG, "cluster art fetch failed", e)
                null
            }
        }
        if (jpeg == null || jpeg.isEmpty()) return false
        if (jpeg.size > ART_MAX_BYTES) {
            Log.w(AapConsts.TAG, "cluster art too large (${jpeg.size}B) — skip")
            return false
        }
        builder.setAlbumArt(ByteString.copyFrom(jpeg))
        pushedArtRevision = revision
        pushedArtTrackId = trackId
        cachedArtJpeg = jpeg
        Log.i(AapConsts.TAG, "AapNowPlaying art ${jpeg.size}B rev=$revision")
        return true
    }

    companion object {
        private const val ELAPSED_TICK_MS = 1_000L
        private const val ART_MAX_BYTES = 512 * 1024
        private val OBSERVED_KEYS = listOf(
            ClusterLyricStore.SETTINGS_TITLE,
            ClusterLyricStore.SETTINGS_SUBTITLE,
            ClusterLyricStore.SETTINGS_ALBUM,
            ClusterLyricStore.SETTINGS_TRACK_MEDIA_ID,
            ClusterLyricStore.SETTINGS_UPDATED_MS,
            ClusterLyricStore.SETTINGS_POSITION_MS,
            ClusterLyricStore.SETTINGS_DURATION_MS,
            ClusterLyricStore.SETTINGS_PLAYBACK_STATE,
            ClusterLyricStore.SETTINGS_SOURCE_PKG,
            ClusterArtStore.SETTINGS_ART_REVISION,
        )
    }
}
