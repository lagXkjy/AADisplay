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
 * Same [ClusterLyricStore] / [ClusterArtStore] source as CarPlay. Pushes
 * [MediaPlaybackChannel] metadata (song = current lyric line) and status.
 * Track identity follows [ClusterLyricStore.SETTINGS_TRACK_MEDIA_ID] so a
 * lyric-only title change is not treated as a new song.
 *
 * HU MediaPlaybackMetadata is a full card replace (same lesson as gearhead
 * HU MediaInfo — see docs/CLUSTER_LYRIC_CLOCK.md): every metadata send must carry
 * song + artist + album + duration + album art. Lyric-only partial protobufs
 * clear cover / artist / album on the cluster and reset the clock to 0:00.
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

        val songChanged = song != pushedSong
        val artistChanged = artist != pushedArtist
        val albumChanged = album != pushedAlbum
        val durationChanged = durationSec != pushedDurationSec
        val artChanged = artRevision != pushedArtRevision || (trackChanged && artRevision > 0L)
        val metaDirty = full || trackChanged || songChanged || artistChanged ||
            albumChanged || durationChanged || artChanged

        if (artRevision <= 0L && artChanged) {
            pushedArtRevision = artRevision
            pushedArtTrackId = null
            cachedArtJpeg = null
        }

        if (metaDirty && (
                song != null || artist != null || album != null || durationSec != null || artRevision > 0L
                )
        ) {
            // Full card every time — HU replaces the whole MediaPlaybackMetadata.
            val metaBuilder = MediaPlaybackMetadata.newBuilder()
                .setSong(song.orEmpty())
                .setArtist(artist.orEmpty())
                .setAlbum(album.orEmpty())
            if (durationSec != null) metaBuilder.setDurationSeconds(durationSec)
            if (artRevision > 0L) {
                // Lyric ticks must re-attach cached JPEG; only fetch on revision/track change.
                attachArt(
                    metaBuilder,
                    artRevision,
                    trackId,
                    forceFetch = artChanged || cachedArtJpeg == null,
                )
            }
            pushedSong = song
            pushedArtist = artist
            pushedAlbum = album
            pushedDurationSec = durationSec
            runCatching { ch.metadata(metaBuilder.build()) }
                .onFailure { Log.w(AapConsts.TAG, "MediaPlayback metadata failed", it) }
        }

        val statusBuilder = MediaPlaybackStatus.newBuilder().setState(state)
        var statusDirty = state != pushedState || full || metaDirty
        if (state != pushedState) pushedState = state
        // Always re-assert source on status pushes that accompany meta (HU may clear).
        if (sourcePkg != pushedSource || full || metaDirty) {
            pushedSource = sourcePkg
            statusBuilder.setMediaSource(sourcePkg.orEmpty())
            statusDirty = true
        }
        if (full) pushedElapsedSec = -1L
        // T2-A+1s: after MediaInfo the HU often resets to 0:00; cover with Store whole-second +1.
        val elapsedSec = if (state == MediaPlaybackStatus.State.PLAYING && progress != null) {
            val rawSec = ClusterLyricStore.extrapolatePosition(progress) / 1000L
            val durSec = progress.durationMs.takeIf { it > 0L }?.let { it / 1000L }
            val bumped = if (metaDirty) rawSec + 1L else rawSec
            if (durSec != null) bumped.coerceAtMost(durSec) else bumped
        } else {
            -1L
        }
        if (elapsedSec >= 0L && (elapsedSec != pushedElapsedSec || full || metaDirty)) {
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
