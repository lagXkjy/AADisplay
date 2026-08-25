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
 * Single-sounder among AvMedia (music ∪ video) + cluster source among the lyric trio.
 *
 * Rules:
 * 1. Audio and video cannot both play — one [pauseLosers] winner among all AvMedia.
 * 2. QQ 车载 / HD / 汽水 — at most one plays; [pickClusterSource] follows that player.
 * 3. Among PLAYING sessions, focused front → other front → stack rank / preferred / freshness.
 * 4. Sticky AvMedia focus: no PLAYING → no sounder (do not invent one from idle stack-top).
 *    A player yields only when it stops, leaves the stack, or another AvMedia starts PLAYING
 *    (same-pane pause: SplitBuriedPlayback).
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
    ): List<MediaController> {
        if (sessions.isNullOrEmpty()) return emptyList()
        val selfPkg = BuildConfig.APPLICATION_ID
        return sessions.filter { c ->
            val pkg = c.packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return@filter false
            if (pkg == selfPkg) return@filter false
            if (pkg == "android") return@filter false
            if (pkg.startsWith("com.google.android.projection.gearhead")) return@filter false
            val mediaId = c.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID)
            if (mediaId?.startsWith(ClusterLyricMediaService.MEDIA_ID_PREFIX) == true) {
                return@filter false
            }
            MusicAppClassifier.isAvMediaSession(context, c)
        }
    }

    /**
     * Single sounder: exactly one PLAYING AvMedia session, or null.
     * Prefer focused/other stack front when that front is PLAYING; else rank/preferred/freshness.
     */
    fun pickWinner(
        context: Context,
        sessions: List<MediaController>?,
        layout: StackLayout?,
    ): MediaController? {
        val playing = eligibleControllers(context, sessions).filter {
            isPlayingState(it.playbackState?.state ?: PlaybackState.STATE_NONE)
        }
        if (playing.isEmpty()) return null
        if (layout != null) {
            pickPlayingForFront(playing, layout.focusedFront())?.let { return it }
            pickPlayingForFront(playing, layout.otherFront())?.let { return it }
        }
        return playing.maxWithOrNull(playingComparator(layout))
    }

    /**
     * Dashboard lyric source: the one preferred music app that is PLAYING
     * (QQ 车载 / HD / 汽水). When video (or other non-trio) owns the speaker,
     * preferred are paused and this returns null.
     */
    fun pickClusterSource(
        context: Context,
        sessions: List<MediaController>?,
        layout: StackLayout?,
    ): MediaController? {
        val preferredPlaying = eligibleControllers(context, sessions).filter { c ->
            LyricLineExtractor.isPreferredPackage(c.packageName) &&
                isPlayingState(c.playbackState?.state ?: PlaybackState.STATE_NONE)
        }
        if (preferredPlaying.isEmpty()) return null
        if (layout != null) {
            pickPlayingForFront(preferredPlaying, layout.focusedFront())?.let { return it }
            pickPlayingForFront(preferredPlaying, layout.otherFront())?.let { return it }
        }
        return preferredPlaying.maxWithOrNull(playingComparator(layout))
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
        // No winner → do not mass-pause; that fights the real player and clears cluster.
        if (winner == null || sessions.isNullOrEmpty()) return
        val winnerToken = winner.sessionToken
        val winnerPkg = winner.packageName
        val now = SystemClock.uptimeMillis()
        for (controller in sessions) {
            val pkg = controller.packageName?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (controller.sessionToken == winnerToken) continue
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

    private fun pickPlayingForFront(
        playing: List<MediaController>,
        frontPkg: String?,
    ): MediaController? {
        val pkg = frontPkg?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return playing.firstOrNull { it.packageName == pkg }
    }

    /** Lower stack rank wins; then preferred lyric index; then freshest position update. */
    private fun playingComparator(layout: StackLayout?): Comparator<MediaController> =
        Comparator { a, b ->
            if (layout != null) {
                val rankA = layout.stackRank(a.packageName)
                val rankB = layout.stackRank(b.packageName)
                if (rankA != rankB) return@Comparator rankB.compareTo(rankA) // lower rank better → reverse
            }
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
