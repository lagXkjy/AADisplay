package io.github.nitsuya.aa.display.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.xposed.cluster.ClusterArtStore
import io.github.nitsuya.aa.display.xposed.cluster.ClusterLyricStore

/**
 * Invisible AA media shell: exposes a [MediaSessionCompat] whose Title is the
 * instrument-cluster lyric line published by [ClusterLyricStore] (system_server).
 *
 * Runs in process `:cluster` so lifecycle cannot tear down projection / split VDs.
 * No AudioFocus, no media-button ownership, empty browse tree.
 */
class ClusterLyricMediaService : MediaBrowserServiceCompat() {

    companion object {
        private const val TAG = "AAD_ClusterLyricMedia"
        private const val ROOT_ID = "aadisplay_cluster_root"
        /** Shared with [io.github.nitsuya.aa.display.xposed.hook.aa.AaClusterLyricEgressHook] scope gate. */
        const val MEDIA_ID_PREFIX = "aadisplay.cluster:"
        private const val PLAYBACK_EXTRAS_MEDIA_ID =
            "androidx.media.PlaybackStateCompat.Extras.KEY_MEDIA_ID"
        private const val GEARHEAD = "com.google.android.projection.gearhead"
        /** Re-push position after Title metadata so the HU clock can recover. */
        private val PROGRESS_REASSERT_MS = longArrayOf(40L, 80L, 160L, 320L)
        /** [MediaMetadata.getDescription] prefers this over [METADATA_KEY_ALBUM] for the 3rd line. */
        private const val METADATA_KEY_DISPLAY_DESCRIPTION =
            "android.media.metadata.DISPLAY_DESCRIPTION"
        private const val CLUSTER_ACTIONS =
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

        val COMPONENT: ComponentName =
            ComponentName(
                BuildConfig.APPLICATION_ID,
                ClusterLyricMediaService::class.java.name,
            )

        /** Best-effort wake of `:cluster` from system_server after lyric publish. */
        fun warmStart(context: Context) {
            runCatching {
                context.startService(Intent().setComponent(COMPONENT))
            }.onFailure { e ->
                Log.w(TAG, "warmStart failed", e)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var session: MediaSessionCompat? = null
    private var observer: ContentObserver? = null
    private var lastTitle: String = ""
    private var lastSubtitle: String = ""
    private var lastAlbum: String = ""
    private var lastArtMediaId: String = ""
    private var lastArtRevision: Long = 0L
    private var lastShellMediaId: String = ""
    private var lastDurationMs: Long = -1L
    private var lastPositionMs: Long = -1L
    private val reassertProgress = Runnable {
        applyProgress("reassert", force = true)
    }

    override fun onCreate() {
        super.onCreate()
        val sess = MediaSessionCompat(this, "AADisplay.ClusterLyricShell").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                // Never consume media keys — real QQ session must stay in control.
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean = false
                override fun onPlay() {}
                override fun onPause() {}
                override fun onSkipToNext() {}
                override fun onSkipToPrevious() {}
                override fun onSeekTo(pos: Long) {}
            })
            setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setPlaybackState(idleState())
        }
        session = sess
        sessionToken = sess.sessionToken
        registerStoreObserver()
        applyStore("onCreate")
        Log.i(TAG, "shell created process=${android.app.Application.getProcessName()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        applyStore("onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(reassertProgress)
        unregisterStoreObserver()
        session?.run {
            isActive = false
            setMetadata(null)
            setPlaybackState(idleState())
            release()
        }
        session = null
        super.onDestroy()
        Log.i(TAG, "shell destroyed")
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot? {
        if (clientPackageName == GEARHEAD ||
            clientPackageName.startsWith("$GEARHEAD:") ||
            clientPackageName == "android" ||
            clientPackageName == BuildConfig.APPLICATION_ID
        ) {
            return BrowserRoot(ROOT_ID, null)
        }
        Log.w(TAG, "onGetRoot denied pkg=$clientPackageName uid=$clientUid")
        return null
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        // Empty browse — metadata egress only; avoid Coolwalk media-card content.
        result.sendResult(mutableListOf())
    }

    private fun registerStoreObserver() {
        if (observer != null) return
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val key = uri?.lastPathSegment
                if (key == ClusterLyricStore.SETTINGS_POSITION_MS ||
                    key == ClusterLyricStore.SETTINGS_DURATION_MS
                ) {
                    applyProgress("observer-position")
                } else {
                    applyStore("observer")
                }
            }
        }
        observer = obs
        val cr = contentResolver
        // Do not observe updated_ms — touch() keepalive would wake applyStore with no title change.
        listOf(
            ClusterLyricStore.SETTINGS_TITLE,
            ClusterLyricStore.SETTINGS_SUBTITLE,
            ClusterLyricStore.SETTINGS_ALBUM,
            ClusterArtStore.SETTINGS_ART_MEDIA_ID,
            ClusterArtStore.SETTINGS_ART_REVISION,
            ClusterLyricStore.SETTINGS_TRACK_MEDIA_ID,
            ClusterLyricStore.SETTINGS_POSITION_MS,
        ).forEach { key ->
            runCatching {
                cr.registerContentObserver(
                    Settings.Global.getUriFor(key),
                    false,
                    obs,
                )
            }
        }
    }

    private fun unregisterStoreObserver() {
        val obs = observer ?: return
        observer = null
        runCatching { contentResolver.unregisterContentObserver(obs) }
    }

    private fun scheduleProgressReassert() {
        handler.removeCallbacks(reassertProgress)
        for (delay in PROGRESS_REASSERT_MS) {
            handler.postDelayed(reassertProgress, delay)
        }
    }

    private fun applyStore(reason: String) {
        val cr = contentResolver
        val fresh = ClusterLyricStore.readFresh(cr)
        val sess = session ?: return
        val title = fresh?.title
        if (title.isNullOrEmpty()) {
            if (sess.isActive || lastTitle.isNotEmpty()) {
                lastTitle = ""
                lastSubtitle = ""
                lastAlbum = ""
                lastArtMediaId = ""
                lastArtRevision = 0L
                lastShellMediaId = ""
                lastDurationMs = -1L
                lastPositionMs = -1L
                handler.removeCallbacks(reassertProgress)
                sess.isActive = false
                sess.setMetadata(null)
                sess.setPlaybackState(idleState())
                Log.d(TAG, "deactivate reason=$reason")
            }
            return
        }
        val subtitle = fresh.subtitle
        val album = fresh.album.ifEmpty {
            Settings.Global.getString(cr, ClusterLyricStore.SETTINGS_ALBUM)?.trim().orEmpty()
        }
        val artMediaId = Settings.Global.getString(cr, ClusterArtStore.SETTINGS_ART_MEDIA_ID)
            ?.trim()
            .orEmpty()
        val trackMediaId = Settings.Global.getString(cr, ClusterLyricStore.SETTINGS_TRACK_MEDIA_ID)
            ?.trim()
            .orEmpty()
        val artRevision = ClusterArtStore.readRevision(cr)
        val artReady = artMediaId.isNotEmpty() &&
            (trackMediaId.isEmpty() || artMediaId == trackMediaId)
        val progress = ClusterLyricStore.readProgress(cr)
        val durationMs = progress?.durationMs ?: 0L
        val artFileMissing = !artReady || ClusterArtStore.artUriString(artRevision) == null
        if (title == lastTitle &&
            subtitle == lastSubtitle &&
            album == lastAlbum &&
            artMediaId == lastArtMediaId &&
            artMediaId.isEmpty() &&
            artFileMissing &&
            artRevision != lastArtRevision &&
            sess.isActive
        ) {
            lastArtRevision = artRevision
            return
        }
        if (title == lastTitle &&
            subtitle == lastSubtitle &&
            album == lastAlbum &&
            artMediaId == lastArtMediaId &&
            artRevision == lastArtRevision &&
            durationMs == lastDurationMs &&
            sess.isActive
        ) {
            return
        }
        val shellMediaId = when {
            artReady -> MEDIA_ID_PREFIX + artMediaId
            lastShellMediaId.isNotEmpty() -> lastShellMediaId
            else -> MEDIA_ID_PREFIX + (album.ifEmpty { subtitle }).hashCode().toUInt().toString(16)
        }
        val artChanged = (artReady && artMediaId != lastArtMediaId) ||
            (artReady && artRevision != lastArtRevision)
        val lyricOnly = sess.isActive &&
            lastDurationMs >= 0L &&
            durationMs == lastDurationMs &&
            subtitle == lastSubtitle &&
            album == lastAlbum &&
            !artChanged &&
            shellMediaId == lastShellMediaId &&
            title != lastTitle
        lastTitle = title
        lastSubtitle = subtitle
        lastAlbum = album
        if (artReady) {
            lastArtMediaId = artMediaId
            lastArtRevision = artRevision
            lastShellMediaId = shellMediaId
        }
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, shellMediaId)
        if (album.isNotEmpty()) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            builder.putString(METADATA_KEY_DISPLAY_DESCRIPTION, album)
        }
        if (durationMs > 0L) {
            builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
        }
        lastDurationMs = durationMs
        // URI-only in the live session — never putBitmap here. SystemUI / Bluetooth
        // parcel session metadata across Binder; recycled multi-key bitmaps crash with
        // "Can't parcel a recycled bitmap".
        // A13 gearhead Glide can open the world-readable system JPEG; A16 priv_app is
        // SELinux-denied on that path — cover still reaches HU via egress getBitmap
        // rewrite + full-MediaInfo ByteArray inject (CoreApi JPEG).
        if (artReady) {
            ClusterArtStore.artUriString(artRevision).let { uri ->
                if (uri == null) return@let
                builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, uri)
                builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, uri)
                builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, uri)
            }
        } else if (lastArtMediaId.isNotEmpty() && lastArtRevision > 0L) {
            ClusterArtStore.artUriString(lastArtRevision).let { uri ->
                if (uri == null) return@let
                builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, uri)
                builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, uri)
                builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, uri)
            }
        }
        val meta = builder.build()
        if (lyricOnly) {
            sess.setMetadata(meta)
            Log.d(TAG, "lyric-only reason=$reason title=$title")
            return
        }
        applyProgress("pre-meta-$reason", sess, force = true)
        sess.setMetadata(meta)
        applyProgress("applyStore-$reason", sess, force = true)
        scheduleProgressReassert()
        if (!sess.isActive) {
            sess.isActive = true
            Log.d(TAG, "active reason=$reason title=$title")
        } else {
            Log.d(TAG, "update reason=$reason title=$title album=$album art=$artMediaId rev=$artRevision")
        }
    }

    private fun applyProgress(
        reason: String,
        sess: MediaSessionCompat? = session,
        force: Boolean = false,
    ) {
        val sessionRef = sess ?: return
        if (!sessionRef.isActive && !force) return
        val cr = contentResolver
        val progress = ClusterLyricStore.readProgress(cr) ?: return
        if (sessionRef.isActive &&
            lastDurationMs >= 0L &&
            progress.durationMs != lastDurationMs
        ) {
            applyStore("duration-catchup")
            return
        }
        val positionMs = ClusterLyricStore.extrapolatePosition(progress)
        if (!force &&
            progress.durationMs == lastDurationMs &&
            kotlin.math.abs(positionMs - lastPositionMs) < 250L
        ) {
            return
        }
        lastDurationMs = progress.durationMs
        lastPositionMs = positionMs
        val compatState = mapCompatPlaybackState(progress, positionMs)
        sessionRef.setPlaybackState(compatState)
        if (force || reason.contains("position")) {
            Log.d(
                TAG,
                "progress reason=$reason pos=${positionMs / 1000f}s dur=${progress.durationMs / 1000f}s",
            )
        }
    }

    private fun mapCompatPlaybackState(
        progress: ClusterLyricStore.Progress,
        positionMs: Long,
    ): PlaybackStateCompat {
        val compatState = when (progress.playbackState) {
            android.media.session.PlaybackState.STATE_PLAYING -> PlaybackStateCompat.STATE_PLAYING
            android.media.session.PlaybackState.STATE_PAUSED -> PlaybackStateCompat.STATE_PAUSED
            android.media.session.PlaybackState.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            android.media.session.PlaybackState.STATE_STOPPED -> PlaybackStateCompat.STATE_STOPPED
            else -> PlaybackStateCompat.STATE_PLAYING
        }
        return PlaybackStateCompat.Builder()
            .setActions(CLUSTER_ACTIONS)
            .setActiveQueueItemId(queueItemId(lastShellMediaId))
            .setExtras(playbackExtras(lastShellMediaId))
            .setState(
                compatState,
                positionMs.coerceAtLeast(0L),
                progress.playbackSpeed,
                SystemClock.elapsedRealtime(),
            )
            .build()
    }

    private fun playbackExtras(mediaId: String): Bundle {
        if (mediaId.isEmpty()) return Bundle.EMPTY
        return Bundle().apply {
            putString(PLAYBACK_EXTRAS_MEDIA_ID, mediaId)
            putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaId)
        }
    }

    private fun queueItemId(mediaId: String): Long {
        if (mediaId.isEmpty()) return PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
        return mediaId.hashCode().toLong() and 0x7fff_ffffL
    }

    private fun idleState(): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setActions(0)
            .setState(
                PlaybackStateCompat.STATE_NONE,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                0f,
            )
            .build()
}
