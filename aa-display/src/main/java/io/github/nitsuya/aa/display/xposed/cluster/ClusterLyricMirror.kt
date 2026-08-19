package io.github.nitsuya.aa.display.xposed.cluster

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug

/**
 * Mirrors the active music [MediaController] into [ShadowNowPlayingSession] and
 * [ClusterLyricStore] so AA Now Playing Title can feed the instrument-cluster ticker.
 *
 * Runs in system_server after [io.github.nitsuya.aa.display.xposed.CoreManagerService.systemReady].
 */
object ClusterLyricMirror {
    private const val TAG = "AAD_ClusterLyricMirror"
    private const val TRACK_SWITCH_DEBOUNCE_MS = 400L
    private const val TITLE_MIN_INTERVAL_MS = 200L
    private const val PAUSED_CLEAR_MS = 120_000L
    private const val POSITION_TICK_MS = 300L
    /** Keep gearhead egress alive when title/lyric line is unchanged (below [ClusterLyricStore.STALE_AFTER_MS]). */
    private const val STALE_TOUCH_MS = 5_000L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var started = false

    private var appContext: Context? = null
    private var sessionManager: MediaSessionManager? = null
    private var shadow: ShadowNowPlayingSession? = null
    private var boundController: MediaController? = null
    private var boundPackage: String? = null

    private var phase: Phase = Phase.Idle
    private var trackingMediaId: String = ""
    private var lastSongTitle: String = ""
    private var lastArtist: String = ""
    private var lastPushedTitle: String = ""
    private var lastPushElapsedMs: Long = 0L
    private var dumpedExtrasForMediaId: String = ""
    private var pausedSinceElapsedMs: Long = 0L
    private var positionTickArmed = false
    private var staleKeepaliveArmed = false

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
            handler.post { onSessionsChanged(sessions) }
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            handler.post { refreshFromBound("metadata") }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            handler.post { refreshFromBound("state") }
        }

        override fun onSessionDestroyed() {
            handler.post {
                unbindController()
                onSessionsChanged(sessionManager?.getActiveSessionsSafe())
            }
        }
    }

    private val trackSwitchSettle = Runnable {
        if (phase == Phase.TrackSwitching) {
            phase = Phase.Tracking
            refreshFromBound("settle")
        }
    }

    private val pausedClear = Runnable {
        if (phase == Phase.Idle) return@Runnable
        val state = boundController?.playbackState?.state
        if (state == PlaybackState.STATE_PAUSED ||
            state == PlaybackState.STATE_STOPPED ||
            state == PlaybackState.STATE_NONE
        ) {
            clearOutput("paused-timeout")
        }
    }

    private val positionTick = object : Runnable {
        override fun run() {
            if (!positionTickArmed) return
            refreshFromBound("tick")
            if (positionTickArmed) {
                handler.postDelayed(this, POSITION_TICK_MS)
            }
        }
    }

    private val staleKeepalive = object : Runnable {
        override fun run() {
            if (!staleKeepaliveArmed) return
            appContext?.contentResolver?.let { ClusterLyricStore.touch(it) }
            if (staleKeepaliveArmed) {
                handler.postDelayed(this, STALE_TOUCH_MS)
            }
        }
    }

    private enum class Phase {
        Idle,
        Tracking,
        TrackSwitching,
    }

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext ?: context
        try {
            sessionManager = context.getSystemService(MediaSessionManager::class.java)
            shadow = ShadowNowPlayingSession(context)
            val sm = sessionManager
            if (sm == null) {
                log(TAG, "MediaSessionManager null; cluster lyric mirror idle")
                return
            }
            sm.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                null as ComponentName?,
                handler,
            )
            onSessionsChanged(sm.getActiveSessionsSafe())
            log(TAG, "started")
        } catch (e: Throwable) {
            log(TAG, "start failed", e)
            started = false
        }
    }

    fun stop() {
        if (!started) return
        handler.removeCallbacksAndMessages(null)
        runCatching {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        }
        unbindController()
        clearOutput("stop")
        shadow?.release()
        shadow = null
        sessionManager = null
        appContext = null
        started = false
        log(TAG, "stopped")
    }

    private fun MediaSessionManager.getActiveSessionsSafe(): List<MediaController>? {
        return try {
            getActiveSessions(null)
        } catch (e: Throwable) {
            log(TAG, "getActiveSessions failed", e)
            null
        }
    }

    private fun onSessionsChanged(sessions: List<MediaController>?) {
        val pick = pickController(sessions)
        if (pick == null) {
            unbindController()
            clearOutput("no-session")
            return
        }
        val pkg = pick.packageName
        if (boundController?.sessionToken == pick.sessionToken && boundPackage == pkg) {
            refreshFromBound("sessions-same")
            return
        }
        unbindController()
        boundController = pick
        boundPackage = pkg
        try {
            pick.registerCallback(controllerCallback, handler)
        } catch (e: Throwable) {
            log(TAG, "registerCallback failed pkg=$pkg", e)
            boundController = null
            boundPackage = null
            return
        }
        log(TAG, "bound pkg=$pkg")
        phase = Phase.TrackSwitching
        trackingMediaId = ""
        handler.removeCallbacks(trackSwitchSettle)
        handler.postDelayed(trackSwitchSettle, TRACK_SWITCH_DEBOUNCE_MS)
        refreshFromBound("bind")
    }

    private fun pickController(sessions: List<MediaController>?): MediaController? {
        if (sessions.isNullOrEmpty()) return null
        val selfPkg = BuildConfig.APPLICATION_ID
        val filtered = sessions.filter { c ->
            val pkg = c.packageName ?: return@filter false
            if (pkg == selfPkg) return@filter false
            // Shadow session is created in system_server (package often "android").
            if (pkg == "android") return@filter false
            if (pkg.startsWith("com.google.android.projection.gearhead")) return@filter false
            val mediaId = c.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID)
            if (mediaId?.startsWith("aadisplay.cluster:") == true) return@filter false
            true
        }
        if (filtered.isEmpty()) return null

        fun isPlaying(c: MediaController): Boolean {
            val st = c.playbackState?.state ?: return false
            return st == PlaybackState.STATE_PLAYING ||
                st == PlaybackState.STATE_BUFFERING ||
                st == PlaybackState.STATE_FAST_FORWARDING ||
                st == PlaybackState.STATE_REWINDING
        }

        val preferredPlaying = filtered.firstOrNull {
            LyricLineExtractor.isPreferredPackage(it.packageName) && isPlaying(it)
        }
        if (preferredPlaying != null) return preferredPlaying

        val anyPlaying = filtered.firstOrNull { isPlaying(it) }
        if (anyPlaying != null) return anyPlaying

        val preferred = filtered.firstOrNull {
            LyricLineExtractor.isPreferredPackage(it.packageName)
        }
        if (preferred != null) return preferred

        return filtered.firstOrNull()
    }

    private fun refreshFromBound(reason: String) {
        val controller = boundController ?: return
        val extracted = LyricLineExtractor.extract(controller) ?: run {
            logDebug(TAG, "extract null reason=$reason pkg=$boundPackage")
            return
        }
        val state = controller.playbackState
        val playbackState = state?.state ?: PlaybackState.STATE_NONE
        val position = LyricLineExtractor.estimatePositionMs(state)

        if (extracted.mediaId != dumpedExtrasForMediaId) {
            dumpedExtrasForMediaId = extracted.mediaId
            LyricLineExtractor.dumpExtrasOnce(
                boundPackage.orEmpty(),
                state,
                controller.metadata,
            )
        }

        val trackChanged =
            trackingMediaId.isNotEmpty() &&
                (extracted.mediaId != trackingMediaId ||
                    extracted.songTitle != lastSongTitle ||
                    extracted.artist != lastArtist)

        if (trackChanged) {
            phase = Phase.TrackSwitching
            handler.removeCallbacks(trackSwitchSettle)
            handler.postDelayed(trackSwitchSettle, TRACK_SWITCH_DEBOUNCE_MS)
            // Never leave the previous lyric on the ticker while switching.
            push(
                title = extracted.songTitle,
                artist = extracted.artist,
                mediaId = extracted.mediaId,
                durationMs = extracted.durationMs,
                playbackState = playbackState,
                positionMs = position,
                force = true,
                reason = "track-switch",
            )
            trackingMediaId = extracted.mediaId
            lastSongTitle = extracted.songTitle
            lastArtist = extracted.artist
            return
        }

        if (phase == Phase.TrackSwitching) {
            // Still debouncing: keep song title only.
            push(
                title = extracted.songTitle,
                artist = extracted.artist,
                mediaId = extracted.mediaId,
                durationMs = extracted.durationMs,
                playbackState = playbackState,
                positionMs = position,
                force = false,
                reason = "switching-$reason",
            )
            trackingMediaId = extracted.mediaId
            lastSongTitle = extracted.songTitle
            lastArtist = extracted.artist
            return
        }

        phase = Phase.Tracking
        trackingMediaId = extracted.mediaId
        lastSongTitle = extracted.songTitle
        lastArtist = extracted.artist

        when (playbackState) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            -> {
                pausedSinceElapsedMs = 0L
                handler.removeCallbacks(pausedClear)
                if (extracted.needsPositionTick) {
                    armPositionTick()
                } else {
                    disarmPositionTick()
                }
                armStaleKeepalive()
            }
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_STOPPED,
            -> {
                disarmPositionTick()
                disarmStaleKeepalive()
                if (pausedSinceElapsedMs == 0L) {
                    pausedSinceElapsedMs = SystemClock.elapsedRealtime()
                    handler.removeCallbacks(pausedClear)
                    handler.postDelayed(pausedClear, PAUSED_CLEAR_MS)
                }
            }
            else -> {
                disarmPositionTick()
                disarmStaleKeepalive()
            }
        }

        push(
            title = extracted.tickerTitle,
            artist = extracted.artist,
            mediaId = extracted.mediaId,
            durationMs = extracted.durationMs,
            playbackState = playbackState,
            positionMs = position,
            force = false,
            reason = if (extracted.fromLyric) "lyric-$reason" else "title-$reason",
        )
    }

    private fun armPositionTick() {
        if (positionTickArmed) return
        positionTickArmed = true
        handler.removeCallbacks(positionTick)
        handler.postDelayed(positionTick, POSITION_TICK_MS)
    }

    private fun disarmPositionTick() {
        if (!positionTickArmed) return
        positionTickArmed = false
        handler.removeCallbacks(positionTick)
    }

    private fun armStaleKeepalive() {
        if (staleKeepaliveArmed) return
        staleKeepaliveArmed = true
        handler.removeCallbacks(staleKeepalive)
        handler.postDelayed(staleKeepalive, STALE_TOUCH_MS)
    }

    private fun disarmStaleKeepalive() {
        if (!staleKeepaliveArmed) return
        staleKeepaliveArmed = false
        handler.removeCallbacks(staleKeepalive)
    }

    private fun push(
        title: String,
        artist: String,
        mediaId: String,
        durationMs: Long,
        playbackState: Int,
        positionMs: Long,
        force: Boolean,
        reason: String,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (!force &&
            title == lastPushedTitle &&
            mediaId == trackingMediaId &&
            now - lastPushElapsedMs < TITLE_MIN_INTERVAL_MS
        ) {
            return
        }
        if (!force &&
            title == lastPushedTitle &&
            now - lastPushElapsedMs < TITLE_MIN_INTERVAL_MS
        ) {
            return
        }
        lastPushedTitle = title
        lastPushElapsedMs = now
        val ctx = appContext
        if (ctx != null) {
            ClusterLyricStore.publish(ctx.contentResolver, title, artist)
        }
        shadow?.update(
            title = title,
            artist = artist,
            mediaId = "aadisplay.cluster:$mediaId",
            durationMs = durationMs,
            playbackState = playbackState,
            positionMs = positionMs,
        )
        logDebug(TAG, "push reason=$reason title=$title pkg=$boundPackage")
    }

    private fun clearOutput(reason: String) {
        phase = Phase.Idle
        trackingMediaId = ""
        lastSongTitle = ""
        lastArtist = ""
        lastPushedTitle = ""
        dumpedExtrasForMediaId = ""
        pausedSinceElapsedMs = 0L
        disarmPositionTick()
        disarmStaleKeepalive()
        handler.removeCallbacks(trackSwitchSettle)
        handler.removeCallbacks(pausedClear)
        appContext?.let { ClusterLyricStore.clear(it.contentResolver) }
        shadow?.deactivate()
        logDebug(TAG, "clear reason=$reason")
    }

    private fun unbindController() {
        disarmPositionTick()
        disarmStaleKeepalive()
        boundController?.let { c ->
            runCatching { c.unregisterCallback(controllerCallback) }
        }
        boundController = null
        boundPackage = null
    }
}
