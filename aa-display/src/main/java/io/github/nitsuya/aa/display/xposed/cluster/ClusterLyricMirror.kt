package io.github.nitsuya.aa.display.xposed.cluster

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.nitsuya.aa.display.service.ClusterLyricMediaService
import io.github.nitsuya.aa.display.util.AvMediaArbiter
import io.github.nitsuya.aa.display.util.MusicAppClassifier
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug

/**
 * Mirrors the active AvMedia [MediaController] into [ClusterLyricStore] so
 * [io.github.nitsuya.aa.display.service.ClusterLyricMediaService] / gearhead egress
 * can feed the instrument-cluster ticker.
 *
 * Winner = stack-top AvMedia (focused front > other front > buried music); single-sounder
 * pauses other PLAYING AvMedia. Runs in system_server after
 * [io.github.nitsuya.aa.display.xposed.CoreManagerService.systemReady].
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
    private const val ART_RETRY_ON_TICK_MS = 300L
    /** After track change, poll cover independently of lyric line updates (long intro). */
    private const val ART_BURST_AFTER_TRACK_MS = 60_000L
    /**
     * After AA connect, QQ + Luna may both report PLAYING briefly — lock the first pick so
     * title/lyric/progress cannot flap between sessions during gearhead bind.
     */
    private const val CONNECT_PLAYER_LOCK_MS = 20_000L

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
    private var artBurstUntilElapsedMs: Long = 0L
    private var pendingTitle: String? = null
    private var pendingArtist: String = ""
    private var pendingAlbum: String = ""
    private var pendingMediaId: String = ""
    private var pendingMetadata: android.media.MediaMetadata? = null
    @Volatile
    private var connectLockPackage: String? = null
    @Volatile
    private var connectLockUntilElapsedMs = 0L

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

    /** AA display session resumed (first connect or soft reconnect). */
    fun onAaConnected() {
        if (!started) return
        handler.post {
            clearConnectLockIfExpired()
            val sessions = sessionManager?.getActiveSessionsSafe()
            val preferredPlaying = countPreferredPlaying(sessions)
            if (preferredPlaying >= 2) {
                val winner = pickControllerUnlocked(sessions)
                if (winner != null) {
                    armConnectLock(winner.packageName, "aa-connect-race($preferredPlaying)")
                }
            } else {
                releaseConnectLock("single-active")
            }
            onSessionsChanged(sessions)
        }
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
        val ctx = appContext
        val layout = stackLayout()
        val pick = pickController(sessions)
        if (ctx != null) {
            AvMediaArbiter.pauseLosers(ctx, sessions, pick)
        }
        if (pick == null) {
            unbindController()
            clearOutput("no-session")
            return
        }
        val pkg = pick.packageName
        if (ctx == null || !MusicAppClassifier.isAvMediaSession(ctx, pick)) {
            unbindController()
            clearOutput("non-av")
            return
        }
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
            LyricLineExtractor.isPreferredPackage(pkg) &&
            sameStackRank(boundPackage, pkg, layout)
        ) {
            refreshFromBound("sessions-keep-bound")
            return
        }
        if (shouldKeepBoundPlaying(pick, layout)) {
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

    private fun stackLayout(): AvMediaArbiter.StackLayout? {
        return runCatching { CoreManagerService.avStackLayout() }.getOrNull()
    }

    private fun sameStackRank(
        a: String?,
        b: String?,
        layout: AvMediaArbiter.StackLayout?,
    ): Boolean {
        if (layout == null) return true
        return layout.stackRank(a) == layout.stackRank(b)
    }

    private fun pickController(sessions: List<MediaController>?): MediaController? {
        clearConnectLockIfExpired()
        val locked = pickLockedController(sessions)
        if (locked != null) return locked
        return pickControllerUnlocked(sessions)
    }

    private fun pickControllerUnlocked(sessions: List<MediaController>?): MediaController? {
        val ctx = appContext ?: return null
        return AvMediaArbiter.pickWinner(ctx, sessions, stackLayout())
    }

    private fun pickLockedController(sessions: List<MediaController>?): MediaController? {
        if (!isConnectLockActive()) return null
        val lockPkg = connectLockPackage ?: return null
        val ctx = appContext ?: return null
        val layout = stackLayout()
        val locked = AvMediaArbiter.eligibleControllers(ctx, sessions, layout)
            .firstOrNull { it.packageName == lockPkg } ?: run {
            releaseConnectLock("missing")
            return null
        }
        val state = locked.playbackState?.state ?: PlaybackState.STATE_NONE
        if (isIdlePlayback(state)) {
            releaseConnectLock("idle")
            return null
        }
        return locked
    }

    private fun countPreferredPlaying(sessions: List<MediaController>?): Int {
        val ctx = appContext ?: return 0
        val layout = stackLayout()
        return AvMediaArbiter.eligibleControllers(ctx, sessions, layout).count { c ->
            LyricLineExtractor.isPreferredPackage(c.packageName) &&
                isPlayingState(c.playbackState?.state ?: PlaybackState.STATE_NONE)
        }
    }

    private fun armConnectLock(packageName: String, reason: String) {
        connectLockPackage = packageName
        connectLockUntilElapsedMs = SystemClock.elapsedRealtime() + CONNECT_PLAYER_LOCK_MS
        log(TAG, "connect lock pkg=$packageName reason=$reason ms=$CONNECT_PLAYER_LOCK_MS")
    }

    private fun releaseConnectLock(reason: String) {
        if (connectLockPackage == null && connectLockUntilElapsedMs == 0L) return
        logDebug(TAG, "connect lock release reason=$reason pkg=$connectLockPackage")
        connectLockPackage = null
        connectLockUntilElapsedMs = 0L
    }

    private fun isConnectLockActive(): Boolean =
        connectLockUntilElapsedMs > 0L && SystemClock.elapsedRealtime() < connectLockUntilElapsedMs

    private fun clearConnectLockIfExpired() {
        if (connectLockUntilElapsedMs > 0L && !isConnectLockActive()) {
            releaseConnectLock("expired")
        }
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
            artBurstUntilElapsedMs = SystemClock.elapsedRealtime() + ART_BURST_AFTER_TRACK_MS
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
        if (!isBoundAvMedia()) return
        if (shouldAttemptArtPublish(reason, mediaId)) {
            val ctx = appContext
            val forceRescan = ctx != null &&
                isArtBurstActive() &&
                ClusterArtStore.needsArtForMediaId(ctx.contentResolver, mediaId)
            publishArtIfNeeded(metadata, mediaId, forceRescan = forceRescan)
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
     * After track change, [ART_BURST_AFTER_TRACK_MS] keeps polling cover even when
     * the lyric line is static (long intro). Also retry when JPEG is still missing.
     */
    private fun shouldAttemptArtPublish(reason: String, mediaId: String): Boolean {
        if (!reason.endsWith("-tick")) return true
        val ctx = appContext ?: return false
        val burst = isArtBurstActive()
        if (burst && !ClusterArtStore.needsArtForMediaId(ctx.contentResolver, mediaId)) {
            artBurstUntilElapsedMs = 0L
            return false
        }
        if (!burst && !ClusterArtStore.needsArtForMediaId(ctx.contentResolver, mediaId)) {
            return false
        }
        val now = SystemClock.elapsedRealtime()
        val intervalMs = ClusterArtStore.artRetryIntervalMs(mediaId)
        if (now - lastArtRetryElapsedMs < intervalMs) return false
        lastArtRetryElapsedMs = now
        return true
    }

    private fun isArtBurstActive(): Boolean =
        artBurstUntilElapsedMs > 0L && SystemClock.elapsedRealtime() < artBurstUntilElapsedMs

    /** Luna often publishes [METADATA_KEY_ART] after the track title; retry on metadata/state/switch. */
    private fun publishArtIfNeeded(
        metadata: android.media.MediaMetadata?,
        mediaId: String,
        forceRescan: Boolean = false,
    ) {
        if (!isBoundAvMedia()) return
        if (metadata == null || mediaId.isEmpty()) return
        val ctx = appContext ?: return
        ClusterArtStore.publishFromMetadata(
            ctx.contentResolver,
            metadata,
            mediaId,
            boundPackage.orEmpty(),
            forceRescan = forceRescan,
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
        artBurstUntilElapsedMs = 0L
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
     * PLAYING ghost left behind after switching stack-top sources.
     */
    private fun maybeSwitchToPlayingSession(reason: String): Boolean {
        if (isConnectLockActive() && connectLockPackage != null) return false
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

        if (shouldKeepBoundPlaying(pick, stackLayout())) return false
        logDebug(TAG, "switch stale pkg=$boundPackage -> ${pick.packageName} reason=$reason")
        onSessionsChanged(sessions)
        return true
    }

    /**
     * Stack-top outranks freshness. Same-tier PLAYING pairs keep a 1.5s deadband
     * so QQ↔汽水 list jitter cannot flap the ticker.
     */
    private fun shouldKeepBoundPlaying(
        pick: MediaController,
        layout: AvMediaArbiter.StackLayout?,
    ): Boolean {
        if (isConnectLockActive()) {
            val lockPkg = connectLockPackage ?: boundPackage
            if (lockPkg != null && pick.packageName != lockPkg) return true
        }
        val bound = boundController ?: return false
        if (pick.sessionToken == bound.sessionToken) return false
        val pickRank = layout?.stackRank(pick.packageName) ?: 3
        val boundRank = layout?.stackRank(boundPackage) ?: 3
        if (pickRank < boundRank) return false
        if (pickRank > boundRank) return true
        val pickPlaying = isPlayingState(pick.playbackState?.state ?: PlaybackState.STATE_NONE)
        val boundPlaying = isPlayingState(bound.playbackState?.state ?: PlaybackState.STATE_NONE)
        if (!pickPlaying || !boundPlaying) return false
        val pickFresh = pick.playbackState?.lastPositionUpdateTime ?: 0L
        val boundFresh = bound.playbackState?.lastPositionUpdateTime ?: 0L
        return pickFresh <= boundFresh + STALE_PLAYING_MS
    }

    private fun isBoundAvMedia(): Boolean {
        val ctx = appContext ?: return false
        val c = boundController ?: return false
        return MusicAppClassifier.isAvMediaSession(ctx, c)
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
