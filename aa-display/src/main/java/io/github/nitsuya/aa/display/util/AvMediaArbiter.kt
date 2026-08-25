package io.github.nitsuya.aa.display.util

import android.content.Context
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.service.ClusterLyricMediaService
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.xposed.cluster.LyricLineExtractor
import io.github.nitsuya.aa.display.xposed.util.logDebug

/**
 * Single-sounder among AvMedia sessions: stack-top beats buried; focused pane front
 * beats the other pane; cluster follows the winner.
 */
object AvMediaArbiter {
    private const val TAG = "AAD_AvMediaArbiter"
    private const val PAUSE_THROTTLE_MS = 500L

    data class StackLayout(
        val focusedPane: Int,
        val primaryFront: String?,
        val secondaryFront: String?,
        val buried: Set<String>,
    ) {
        fun focusedFront(): String? = frontFor(focusedPane)

        fun otherFront(): String? {
            val other = when (focusedPane) {
                SplitPane.PRIMARY -> SplitPane.SECONDARY
                SplitPane.SECONDARY -> SplitPane.PRIMARY
                else -> SplitPane.SECONDARY
            }
            return frontFor(other)
        }

        private fun frontFor(pane: Int): String? = when (pane) {
            SplitPane.PRIMARY -> primaryFront
            SplitPane.SECONDARY -> secondaryFront
            else -> null
        }

        /** 0 = focused front, 1 = other front, 2 = buried, 3 = off-stack. */
        fun stackRank(packageName: String?): Int {
            val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return 3
            val focused = focusedFront()
            if (focused != null && pkg == focused) return 0
            val other = otherFront()
            if (other != null && pkg == other) return 1
            if (pkg in buried) return 2
            return 3
        }
    }

    private val lastPauseAtMs = HashMap<String, Long>(8)

    fun eligibleControllers(
        context: Context,
        sessions: List<MediaController>?,
        layout: StackLayout?,
    ): List<MediaController> {
        if (sessions.isNullOrEmpty()) return emptyList()
        val selfPkg = BuildConfig.APPLICATION_ID
        val buried = layout?.buried.orEmpty()
        return sessions.filter { c ->
            val pkg = c.packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return@filter false
            if (pkg == selfPkg) return@filter false
            if (pkg == "android") return@filter false
            if (pkg.startsWith("com.google.android.projection.gearhead")) return@filter false
            val mediaId = c.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID)
            if (mediaId?.startsWith(ClusterLyricMediaService.MEDIA_ID_PREFIX) == true) {
                return@filter false
            }
            if (!MusicAppClassifier.isAvMediaSession(context, c)) return@filter false
            // Buried non-music never eligible; buried music OK under maps.
            if (pkg in buried && !MusicAppClassifier.isMusicSession(context, c)) {
                return@filter false
            }
            true
        }
    }

    fun pickWinner(
        context: Context,
        sessions: List<MediaController>?,
        layout: StackLayout?,
    ): MediaController? {
        val eligible = eligibleControllers(context, sessions, layout)
        if (eligible.isEmpty()) return null

        if (layout != null) {
            // Focused front first when it has an active AvMedia session; else other front;
            // else buried music under non-AV fronts (maps).
            pickForFront(context, eligible, layout.focusedFront())?.let { return it }
            pickForFront(context, eligible, layout.otherFront())?.let { return it }
            pickBuriedMusic(eligible, layout)?.let { return it }
            return null
        }

        return pickWithoutLayout(eligible)
    }

    /**
     * Pause other PLAYING AvMedia sessions so only [winner] keeps sounding.
     * Best-effort — some short-video apps ignore [MediaController.TransportControls.pause].
     */
    fun pauseLosers(
        context: Context,
        sessions: List<MediaController>?,
        winner: MediaController?,
    ) {
        if (sessions.isNullOrEmpty()) return
        val winnerToken = winner?.sessionToken
        val winnerPkg = winner?.packageName
        val now = SystemClock.uptimeMillis()
        for (controller in sessions) {
            val pkg = controller.packageName?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (winnerToken != null && controller.sessionToken == winnerToken) continue
            if (winnerPkg != null && pkg == winnerPkg) continue
            if (!MusicAppClassifier.isAvMediaSession(context, controller)) continue
            val state = controller.playbackState?.state ?: PlaybackState.STATE_NONE
            if (!isPlayingState(state)) continue
            val last = lastPauseAtMs[pkg] ?: 0L
            if (now - last < PAUSE_THROTTLE_MS) continue
            try {
                controller.transportControls.pause()
                lastPauseAtMs[pkg] = now
                logDebug(TAG, "pauseLoser pkg=$pkg winner=$winnerPkg")
            } catch (e: Throwable) {
                logDebug(TAG, "pauseLoser failed pkg=$pkg: ${e.message}")
            }
        }
    }

    private fun pickForFront(
        context: Context,
        eligible: List<MediaController>,
        frontPkg: String?,
    ): MediaController? {
        val pkg = frontPkg?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!MusicAppClassifier.isAvMediaPackage(context, pkg) &&
            eligible.none { it.packageName == pkg }
        ) {
            return null
        }
        val forPkg = eligible.filter { it.packageName == pkg }
        if (forPkg.isEmpty()) return null
        forPkg.firstOrNull { isPlayingState(it.playbackState?.state ?: PlaybackState.STATE_NONE) }
            ?.let { return it }
        return forPkg.firstOrNull {
            !isIdlePlayback(it.playbackState?.state ?: PlaybackState.STATE_NONE)
        }
    }

    private fun pickBuriedMusic(
        eligible: List<MediaController>,
        layout: StackLayout,
    ): MediaController? {
        val buried = eligible.filter { c ->
            val pkg = c.packageName ?: return@filter false
            pkg in layout.buried
        }
        if (buried.isEmpty()) return null
        val playing = buried.filter {
            isPlayingState(it.playbackState?.state ?: PlaybackState.STATE_NONE)
        }
        if (playing.isNotEmpty()) {
            return playing.maxWithOrNull(sameTierComparator())
        }
        return buried
            .filter { !isIdlePlayback(it.playbackState?.state ?: PlaybackState.STATE_NONE) }
            .maxWithOrNull(sameTierComparator())
    }

    private fun pickWithoutLayout(eligible: List<MediaController>): MediaController? {
        val playing = eligible.filter {
            isPlayingState(it.playbackState?.state ?: PlaybackState.STATE_NONE)
        }
        if (playing.isNotEmpty()) {
            return playing.maxWithOrNull(sameTierComparator())
        }
        return eligible
            .filter { !isIdlePlayback(it.playbackState?.state ?: PlaybackState.STATE_NONE) }
            .maxWithOrNull(sameTierComparator())
    }

    /** Preferred lyric packages first, then freshest. */
    private fun sameTierComparator(): Comparator<MediaController> =
        Comparator { a, b ->
            val prefA = preferredIndex(a.packageName)
            val prefB = preferredIndex(b.packageName)
            if (prefA != prefB) return@Comparator prefB.compareTo(prefA)
            val freshA = a.playbackState?.lastPositionUpdateTime ?: 0L
            val freshB = b.playbackState?.lastPositionUpdateTime ?: 0L
            freshA.compareTo(freshB)
        }

    private fun preferredIndex(packageName: String?): Int {
        val pkg = packageName ?: return Int.MAX_VALUE
        val i = LyricLineExtractor.PREFERRED_PACKAGE_ORDER.indexOf(pkg)
        return if (i >= 0) i else Int.MAX_VALUE - 1
    }

    fun isPlayingState(state: Int): Boolean {
        return state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_FAST_FORWARDING ||
            state == PlaybackState.STATE_REWINDING
    }

    fun isIdlePlayback(state: Int): Boolean {
        return state == PlaybackState.STATE_PAUSED ||
            state == PlaybackState.STATE_STOPPED ||
            state == PlaybackState.STATE_NONE
    }
}
