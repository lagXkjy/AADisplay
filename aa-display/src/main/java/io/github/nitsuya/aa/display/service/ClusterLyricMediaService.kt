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
        private const val GEARHEAD = "com.google.android.projection.gearhead"

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

    override fun onCreate() {
        super.onCreate()
        val sess = MediaSessionCompat(this, "AADisplay.ClusterLyricShell").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                // Never consume media keys — real QQ session must stay in control.
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean = false
            })
            setFlags(0)
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
                applyStore("observer")
            }
        }
        observer = obs
        val cr = contentResolver
        // Do not observe updated_ms — touch() keepalive would wake applyStore with no title change.
        listOf(
            ClusterLyricStore.SETTINGS_TITLE,
            ClusterLyricStore.SETTINGS_SUBTITLE,
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

    private fun applyStore(reason: String) {
        val cr = contentResolver
        val fresh = ClusterLyricStore.readFresh(cr)
        val sess = session ?: return
        val title = fresh?.first
        if (title.isNullOrEmpty()) {
            if (sess.isActive || lastTitle.isNotEmpty()) {
                lastTitle = ""
                lastSubtitle = ""
                sess.isActive = false
                sess.setMetadata(null)
                sess.setPlaybackState(idleState())
                Log.d(TAG, "deactivate reason=$reason")
            }
            return
        }
        val subtitle = fresh.second
        if (title == lastTitle && subtitle == lastSubtitle && sess.isActive) {
            return
        }
        lastTitle = title
        lastSubtitle = subtitle
        val meta = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
            .putString(
                MediaMetadataCompat.METADATA_KEY_MEDIA_ID,
                MEDIA_ID_PREFIX + title.hashCode().toUInt().toString(16),
            )
            .build()
        // Playing state keeps AA Now Playing egress alive; actions=0 so keys stay on QQ.
        val state = PlaybackStateCompat.Builder()
            .setActions(0)
            .setState(
                PlaybackStateCompat.STATE_PLAYING,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                1f,
                SystemClock.elapsedRealtime(),
            )
            .build()
        sess.setMetadata(meta)
        sess.setPlaybackState(state)
        if (!sess.isActive) {
            sess.isActive = true
            Log.d(TAG, "active reason=$reason title=$title")
        } else {
            Log.d(TAG, "update reason=$reason title=$title")
        }
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
