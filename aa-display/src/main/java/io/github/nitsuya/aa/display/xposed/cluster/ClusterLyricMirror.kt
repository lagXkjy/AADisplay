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
import io.github.nitsuya.aa.display.service.ClusterLyricMediaService
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug

/**
 * Mirrors the active music [MediaController] into [ClusterLyricStore] so
 * [io.github.nitsuya.aa.display.service.ClusterLyricMediaService] / gearhead egress
 * can feed the instrument-cluster ticker.
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
    /** Re-pick when bound to a paused session — list listener misses playback-only changes. */
    private const val REPICK_INTERVAL_MS = 500L
    /** Prefer a fresher playing session over a stale preferred-package PLAYING ghost. */
    private const val STALE_PLAYING_MS = 1_500L
    private const val START_RETRY_FIRST_MS = 2_000L
    private const val START_RETRY_NEXT_MS = 10_000L
    private const val START_RETRY_MAX = 8
    /** Re-scan other playing sessions from LRC ticks at most this often. */
    private const val TICK_SWITCH_INTERVAL_MS = 1_000L
    /** LRC ticks skip art by default; retry when the track still has no JPEG (long intro / static title). */
    private const val ART_RETRY_ON_TICK_MS = 1_500L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var started = false

    private var appContext: Context? = null
    private var sessionManager: MediaSessionManager? = null
    private var boundController: MediaController? = null
    private var boundPackage: String? = null

    private var phase: Phase = Phase.Idle
    private var trackingMediaId: String = ""
    private var lastSongTitle: String = ""
    private var lastArtist: String = ""
    private var lastPushedTitle: String = ""
    private var lastPushedArtist: String = ""
    private var lastPushedAlbum: String = ""
    private var lastPushElapsedMs: Long = 0L
    private var dumpedExtrasForMediaId: String = ""
    private var pausedClearScheduled = false
    private var positionTickArmed = false
    private var staleKeepaliveArmed = false
    private var repickArmed = false
    /** After clear / start, wake `:cluster` once on the next publish (not every lyric line). */
    private var warmStartPending = true
    private var startRetryCount = 0
    private var lastTickSwitchElapsedMs: Long = 0L
    private var lastArtRetryElapsedMs: Long = 0L
    private var pendingTitle: String? = null
    private var pendingArtist: String = ""
    private var pendingAlbum: String = ""
    private var pendingMediaId: String = ""
    private var pendingMetadata: android.media.MediaMetadata? = null

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

    private val repickRunnable = object : Runnable {
        override fun run() {
            if (!repickArmed) return
            onSessionsChanged(sessionManager?.getActiveSessionsSafe())
            if (repickArmed) {
                handler.postDelayed(this, REPICK_INTERVAL_MS)
            }
        }
    }

    private val pendingFlush = Runnable {
        val title = pendingTitle ?: return@Runnable
        pendingTitle = null
        val artist = pendingArtist
        val album = pendingAlbum
        val mediaId = pendingMediaId
        val metadata = pendingMetadata
        pendingMetadata = null
        push(
            title = title,
            panelArtist = artist,
            album = album,
            mediaId = mediaId,
            force = true,
            reason = "pending-flush",
            metadata = metadata,
        )
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
            val sm = sessionManager
            if (sm == null) {
                log(TAG, "MediaSessionManager null; cluster lyric mirror idle")
                started = false
                scheduleStartRetry(context)
                return
            }
            startRetryCount = 0
            sm.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                null as ComponentName?,
                handler,
            )
            onSessionsChanged(sm.getActiveSessionsSafe())
            // Wake :cluster MediaBrowserService once so Title can egress before AA binds.
            warmStartPending = true
            runCatching { ClusterLyricMediaService.warmStart(appContext!!) }
                .onFailure { log(TAG, "ClusterLyricMediaService.warmStart failed", it) }
                .onSuccess { warmStartPending = false }
            log(TAG, "started")
        } catch (e: Throwable) {
            log(TAG, "start failed", e)
            started = false
        }
    }

    private fun scheduleStartRetry(context: Context) {
        if (startRetryCount >= START_RETRY_MAX) {
            log(TAG, "MediaSessionManager start retries exhausted")
            return
        }
        val delay = if (startRetryCount == 0) START_RETRY_FIRST_MS else START_RETRY_NEXT_MS
        startRetryCount++
        handler.postDelayed({ start(context) }, delay)
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
        // Track-change gaps can leave every preferred player idle briefly; do not
        // steal binding from qqmusicpad back to a paused qqmusiccar (or vice versa).
        if (boundController != null &&
            boundPackage != null &&
            isIdlePlayback(pick.playbackState?.state ?: PlaybackState.STATE_NONE) &&
            LyricLineExtractor.isPreferredPackage(boundPackage) &&
            LyricLineExtractor.isPreferredPackage(pkg)
        ) {
            refreshFromBound("sessions-keep-bound")
            return
        }
        if (shouldKeepBoundPlaying(pick)) {
            refreshFromBound("sessions-keep-fresh")
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
        logDebug(TAG, "bound pkg=$pkg")
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
            // Skip system / gearhead sessions; never re-bind our :cluster shell.
            if (pkg == "android") return@filter false
            if (pkg.startsWith("com.google.android.projection.gearhead")) return@filter false
            val mediaId = c.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID)
            if (mediaId?.startsWith(ClusterLyricMediaService.MEDIA_ID_PREFIX) == true) return@filter false
            true
        }
        if (filtered.isEmpty()) return null

        fun isPlaying(c: MediaController): Boolean =
            isPlayingState(c.playbackState?.state ?: PlaybackState.STATE_NONE)

        fun isActive(c: MediaController): Boolean =
            !isIdlePlayback(c.playbackState?.state ?: PlaybackState.STATE_NONE)

        fun pickPreferred(predicate: (MediaController) -> Boolean): MediaController? {
            for (pkg in LyricLineExtractor.PREFERRED_PACKAGE_ORDER) {
                filtered.firstOrNull { it.packageName == pkg && predicate(it) }?.let { return it }
            }
            return null
        }

        fun freshness(c: MediaController): Long =
            c.playbackState?.lastPositionUpdateTime ?: 0L

        // Freshest playing session wins — do not let a ghost qqmusiccar PLAYING block Luna.
        val playing = filtered.filter { isPlaying(it) }
        if (playing.isNotEmpty()) {
            return playing.maxByOrNull { freshness(it) }
        }

        // Handoff between qqmusiccar / qqmusicpad when both idle briefly.
        pickPreferred { isActive(it) }?.let { return it }

        return filtered.filter { isActive(it) }.maxByOrNull { freshness(it) }
            ?: filtered.firstOrNull()
    }

    private fun refreshFromBound(reason: String) {
        val controller = boundController ?: return
        if (maybeSwitchToPlayingSession(reason)) return
        val extracted = LyricLineExtractor.extract(controller) ?: run {
            logDebug(TAG, "extract null reason=$reason pkg=$boundPackage")
            return
        }
        val state = controller.playbackState
        val playbackState = state?.state ?: PlaybackState.STATE_NONE
        publishProgress(controller, state, playbackState)

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
            lastArtRetryElapsedMs = 0L
            handler.removeCallbacks(trackSwitchSettle)
            handler.postDelayed(trackSwitchSettle, TRACK_SWITCH_DEBOUNCE_MS)
            // Never leave the previous lyric on the ticker while switching.
            push(
                title = extracted.songTitle,
                panelArtist = extracted.panelArtist,
                album = extracted.album,
                mediaId = extracted.mediaId,
                force = true,
                reason = "track-switch",
                metadata = controller.metadata,
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
                panelArtist = extracted.panelArtist,
                album = extracted.album,
                mediaId = extracted.mediaId,
                force = false,
                reason = "switching-$reason",
                metadata = controller.metadata,
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
                pausedClearScheduled = false
                handler.removeCallbacks(pausedClear)
                disarmRepick()
                // Progress egress needs ticks even without timed LRC (Luna / QQ often
                // stop updating PlaybackState.position until the next metadata event).
                armPositionTick()
                armStaleKeepalive()
            }
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_STOPPED,
            -> {
                disarmPositionTick()
                disarmStaleKeepalive()
                armRepickIfIdle()
                if (!pausedClearScheduled) {
                    pausedClearScheduled = true
                    handler.removeCallbacks(pausedClear)
                    handler.postDelayed(pausedClear, PAUSED_CLEAR_MS)
                }
            }
            else -> {
                disarmPositionTick()
                disarmStaleKeepalive()
                armRepickIfIdle()
            }
        }

        push(
            title = extracted.tickerTitle,
            panelArtist = extracted.panelArtist,
            album = extracted.album,
            mediaId = extracted.mediaId,
            force = false,
            reason = if (extracted.fromLyric) "lyric-$reason" else "title-$reason",
            metadata = controller.metadata,
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
        panelArtist: String,
        album: String,
        mediaId: String,
        force: Boolean,
        reason: String,
        metadata: android.media.MediaMetadata? = null,
    ) {
        if (shouldAttemptArtPublish(reason, mediaId)) {
            publishArtIfNeeded(metadata, mediaId)
        }
        val now = SystemClock.elapsedRealtime()
        if (!force) {
            // Identical ticker: rely on 5s staleKeepalive touch — no Settings spam each 300ms tick.
            if (title == lastPushedTitle &&
                panelArtist == lastPushedArtist &&
                album == lastPushedAlbum
            ) {
                return
            }
            val wait = TITLE_MIN_INTERVAL_MS - (now - lastPushElapsedMs)
            val delayMs = if (wait > 0L) wait else 0L
            if (delayMs > 0L) {
                pendingTitle = title
                pendingArtist = panelArtist
                pendingAlbum = album
                pendingMediaId = mediaId
                pendingMetadata = metadata
                handler.removeCallbacks(pendingFlush)
                handler.postDelayed(pendingFlush, delayMs)
                return
            }
        }
        cancelPendingFlush()
        lastPushedTitle = title
        lastPushedArtist = panelArtist
        lastPushedAlbum = album
        lastPushElapsedMs = now
        val ctx = appContext
        if (ctx != null) {
            ClusterLyricStore.publish(ctx.contentResolver, title, panelArtist, album)
            if (warmStartPending) {
                ClusterLyricMediaService.warmStart(ctx)
                warmStartPending = false
            }
        }
        logDebug(
            TAG,
            "push reason=$reason title=$title artist=$panelArtist album=$album mediaId=$mediaId pkg=$boundPackage",
        )
    }

    /**
     * LRC position ticks skip art by default (avoid 300ms metadata.getBitmap spam).
     * When the current track still has no cached JPEG, retry on tick at [ART_RETRY_ON_TICK_MS].
     */
    private fun shouldAttemptArtPublish(reason: String, mediaId: String): Boolean {
        if (!reason.endsWith("-tick")) return true
        val ctx = appContext ?: return false
        if (!ClusterArtStore.needsArtForMediaId(ctx.contentResolver, mediaId)) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastArtRetryElapsedMs < ART_RETRY_ON_TICK_MS) return false
        lastArtRetryElapsedMs = now
        return true
    }

    /** Luna often publishes [METADATA_KEY_ART] after the track title; retry on metadata/state/switch. */
    private fun publishArtIfNeeded(
        metadata: android.media.MediaMetadata?,
        mediaId: String,
    ) {
        if (metadata == null || mediaId.isEmpty()) return
        val ctx = appContext ?: return
        ClusterArtStore.publishFromMetadata(
            ctx.contentResolver,
            metadata,
            mediaId,
            boundPackage.orEmpty(),
        )
    }

    private fun publishProgress(
        controller: MediaController,
        state: PlaybackState?,
        playbackState: Int,
    ) {
        val ctx = appContext ?: return
        val metadata = controller.metadata ?: return
        val durationMs = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
            .coerceAtLeast(0L)
        val positionMs = LyricLineExtractor.extrapolatePositionMs(state)
        val speed = state?.playbackSpeed?.let { if (it == 0f) 1f else it } ?: 1f
        ClusterLyricStore.publishProgress(
            ctx.contentResolver,
            positionMs,
            durationMs,
            SystemClock.elapsedRealtime(),
            playbackState,
            speed,
        )
    }

    private fun clearOutput(reason: String) {
        phase = Phase.Idle
        lastArtRetryElapsedMs = 0L
        trackingMediaId = ""
        lastSongTitle = ""
        lastArtist = ""
        lastPushedTitle = ""
        lastPushedArtist = ""
        lastPushedAlbum = ""
        dumpedExtrasForMediaId = ""
        pausedClearScheduled = false
        warmStartPending = true
        cancelPendingFlush()
        disarmPositionTick()
        disarmStaleKeepalive()
        disarmRepick()
        handler.removeCallbacks(trackSwitchSettle)
        handler.removeCallbacks(pausedClear)
        appContext?.let { ClusterLyricStore.clear(it.contentResolver) }
        logDebug(TAG, "clear reason=$reason")
    }

    private fun cancelPendingFlush() {
        pendingTitle = null
        pendingMetadata = null
        handler.removeCallbacks(pendingFlush)
    }

    private fun unbindController() {
        disarmPositionTick()
        disarmStaleKeepalive()
        disarmRepick()
        pausedClearScheduled = false
        handler.removeCallbacks(pausedClear)
        boundController?.let { c ->
            runCatching { c.unregisterCallback(controllerCallback) }
        }
        boundController = null
        boundPackage = null
    }

    /**
     * Re-bind when another session is actually playing, or when the current bind is a stale
     * preferred-package PLAYING ghost left behind after switching to e.g. Luna.
     */
    private fun maybeSwitchToPlayingSession(reason: String): Boolean {
        if (reason == "tick") {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTickSwitchElapsedMs < TICK_SWITCH_INTERVAL_MS) return false
            lastTickSwitchElapsedMs = now
        }
        val controller = boundController ?: return false
        val sessions = sessionManager?.getActiveSessionsSafe() ?: return false
        val pick = pickController(sessions) ?: return false
        if (pick.sessionToken == controller.sessionToken) return false

        val pickPlaying = isPlayingState(pick.playbackState?.state ?: PlaybackState.STATE_NONE)
        val boundPlaying = isPlayingState(controller.playbackState?.state ?: PlaybackState.STATE_NONE)
        if (!pickPlaying) return false

        if (!boundPlaying) {
            logDebug(TAG, "switch pkg=$boundPackage -> ${pick.packageName} reason=$reason")
            onSessionsChanged(sessions)
            return true
        }

        if (shouldKeepBoundPlaying(pick)) return false
        logDebug(TAG, "switch stale pkg=$boundPackage -> ${pick.packageName} reason=$reason")
        onSessionsChanged(sessions)
        return true
    }

    /**
     * Both sessions report PLAYING but [pick] is not meaningfully fresher.
     * Keeps sessions-changed in line with the 1.5s tick deadband so list
     * callbacks cannot flap QQ ghost PLAYING vs Luna.
     */
    private fun shouldKeepBoundPlaying(pick: MediaController): Boolean {
        val bound = boundController ?: return false
        if (pick.sessionToken == bound.sessionToken) return false
        val pickPlaying = isPlayingState(pick.playbackState?.state ?: PlaybackState.STATE_NONE)
        val boundPlaying = isPlayingState(bound.playbackState?.state ?: PlaybackState.STATE_NONE)
        if (!pickPlaying || !boundPlaying) return false
        val pickFresh = pick.playbackState?.lastPositionUpdateTime ?: 0L
        val boundFresh = bound.playbackState?.lastPositionUpdateTime ?: 0L
        return pickFresh <= boundFresh + STALE_PLAYING_MS
    }

    private fun armRepickIfIdle() {
        if (repickArmed) return
        val state = boundController?.playbackState?.state ?: PlaybackState.STATE_NONE
        if (isPlayingState(state)) return
        repickArmed = true
        handler.removeCallbacks(repickRunnable)
        handler.postDelayed(repickRunnable, REPICK_INTERVAL_MS)
    }

    private fun disarmRepick() {
        if (!repickArmed) return
        repickArmed = false
        handler.removeCallbacks(repickRunnable)
    }

    private fun isPlayingState(state: Int): Boolean {
        return state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_FAST_FORWARDING ||
            state == PlaybackState.STATE_REWINDING
    }

    private fun isIdlePlayback(state: Int): Boolean {
        return state == PlaybackState.STATE_PAUSED ||
            state == PlaybackState.STATE_STOPPED ||
            state == PlaybackState.STATE_NONE
    }
}
