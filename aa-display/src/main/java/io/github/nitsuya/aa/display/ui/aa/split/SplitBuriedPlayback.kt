package io.github.nitsuya.aa.display.ui.aa.split

import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import io.github.nitsuya.aa.display.util.AvMediaArbiter
import io.github.nitsuya.aa.display.util.MusicAppClassifier
import io.github.nitsuya.aa.display.xposed.util.logDebug

/**
 * Same-pane MediaSession pause/resume when stack promote outruns global
 * [AvMediaArbiter.pauseLosers] (e.g. Douyin FGS under another AvMedia).
 *
 * Sticky AvMedia: pause buried only when this pane's front AvMedia is PLAYING.
 * Non-Av / idle Av fronts never steal focus — but [enforceSingleSounder] still
 * keeps only one AvMedia sounding across both panes (restore / cross-pane).
 */
internal class SplitBuriedPlayback(private val c: SplitDisplayController) {

    private val pausedByAADisplay = linkedSetOf<String>()
    private val lastPauseAtMs = HashMap<String, Long>(4)

    fun pauseBuriedStackPlayback(pane: Int) {
        if (!SplitPane.isValid(pane)) return
        val frontPkg = c.stacks.front(pane)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val buried = c.stacks.packagesBottomToTop(pane)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != frontPkg }
        if (buried.isEmpty()) return
        if (!MusicAppClassifier.isAvMediaPackage(c.context, frontPkg)) return

        val msm = c.context.getSystemService(MediaSessionManager::class.java) ?: return
        val sessions = try {
            msm.getActiveSessions(null)
        } catch (e: Throwable) {
            logDebug(SplitDisplayController.TAG, "pauseBuried getActiveSessions failed: ${e.message}")
            return
        }
        if (!isPackagePlaying(sessions, frontPkg)) return

        val buriedSet = buried.toSet()
        val now = SystemClock.uptimeMillis()
        for (controller in sessions) {
            val pkg = controller.packageName?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (pkg !in buriedSet) continue
            if (!MusicAppClassifier.isAvMediaSession(c.context, controller)) continue
            val state = controller.playbackState?.state ?: PlaybackState.STATE_NONE
            if (!AvMediaArbiter.isPlayingState(state)) continue
            val last = lastPauseAtMs[pkg] ?: 0L
            if (now - last < PAUSE_THROTTLE_MS) continue
            try {
                controller.transportControls.pause()
                pausedByAADisplay += pkg
                lastPauseAtMs[pkg] = now
                logDebug(SplitDisplayController.TAG, "pauseBuried pkg=$pkg pane=$pane front=$frontPkg")
            } catch (e: Throwable) {
                logDebug(SplitDisplayController.TAG, "pauseBuried failed pkg=$pkg: ${e.message}")
            }
        }
    }

    /**
     * Global single-sounder: at most one AvMedia PLAYING across both panes / buried.
     * Call after restore / ensure / multi-pane promote — apps often auto-resume together.
     */
    fun enforceSingleSounder(reason: String) {
        if (c.mIsDestroying) return
        val msm = c.context.getSystemService(MediaSessionManager::class.java) ?: return
        val sessions = try {
            msm.getActiveSessions(null)
        } catch (e: Throwable) {
            logDebug(SplitDisplayController.TAG, "enforceSingle getActiveSessions failed: ${e.message}")
            return
        }
        val layout = c.avStackLayout()
        val winner = AvMediaArbiter.pickWinner(c.context, sessions, layout)
        AvMediaArbiter.pauseLosers(c.context, sessions, winner)
        val winnerPkg = winner?.packageName
        if (winnerPkg != null) {
            for (controller in sessions) {
                val pkg = controller.packageName?.trim() ?: continue
                if (pkg == winnerPkg) continue
                if (!MusicAppClassifier.isAvMediaSession(c.context, controller)) continue
                if (!AvMediaArbiter.isPlayingState(
                        controller.playbackState?.state ?: PlaybackState.STATE_NONE,
                    )
                ) {
                    continue
                }
                pausedByAADisplay += pkg
            }
        }
        logDebug(
            SplitDisplayController.TAG,
            "enforceSingleSounder[$reason] winner=$winnerPkg",
        )
    }

    /**
     * Restore / reconnect: sessions often flip to PLAYING after launch settles.
     * Kick now plus a few delayed passes so multi-Av stacks stay single-sounder.
     */
    fun scheduleEnforceSingleSounder(reason: String) {
        c.mHandler.removeCallbacksAndMessages(SINGLE_SOUNDER_TOKEN)
        enforceSingleSounder(reason)
        val now = SystemClock.uptimeMillis()
        for (delay in ENFORCE_RETRY_DELAYS_MS) {
            c.mHandler.postAtTime(
                {
                    if (c.mIsDestroying) return@postAtTime
                    enforceSingleSounder("$reason+$delay")
                },
                SINGLE_SOUNDER_TOKEN,
                now + delay,
            )
        }
    }

    fun cancelScheduledEnforceSingleSounder() {
        c.mHandler.removeCallbacksAndMessages(SINGLE_SOUNDER_TOKEN)
    }

    /**
     * Resume a front we previously paused — only if it would not break single-sounder
     * (another AvMedia already PLAYING wins).
     */
    fun resumeFrontPlaybackIfPausedByUs(frontPkg: String) {
        val pkg = frontPkg.trim().takeIf { it.isNotEmpty() } ?: return
        if (pkg !in pausedByAADisplay) return
        val msm = c.context.getSystemService(MediaSessionManager::class.java) ?: return
        val sessions = try {
            msm.getActiveSessions(null)
        } catch (_: Throwable) {
            return
        }
        val layout = c.avStackLayout()
        val winner = AvMediaArbiter.pickWinner(c.context, sessions, layout)
        val winnerPkg = winner?.packageName
        val winnerPlaying = winner != null &&
            AvMediaArbiter.isPlayingState(winner.playbackState?.state ?: PlaybackState.STATE_NONE)
        // Another Av already owns the speaker — keep this one paused for later promote.
        if (winnerPlaying && winnerPkg != null && winnerPkg != pkg) {
            logDebug(
                SplitDisplayController.TAG,
                "resumeFront skip pkg=$pkg winner=$winnerPkg",
            )
            return
        }
        pausedByAADisplay.remove(pkg)
        val controller = sessions.firstOrNull { it.packageName == pkg } ?: return
        try {
            controller.transportControls.play()
            logDebug(SplitDisplayController.TAG, "resumeFront pkg=$pkg")
        } catch (e: Throwable) {
            logDebug(SplitDisplayController.TAG, "resumeFront failed pkg=$pkg: ${e.message}")
        }
    }

    private fun isPackagePlaying(sessions: List<MediaController>, packageName: String): Boolean {
        return sessions.any { c ->
            c.packageName == packageName &&
                AvMediaArbiter.isPlayingState(
                    c.playbackState?.state ?: PlaybackState.STATE_NONE,
                )
        }
    }

    private companion object {
        const val PAUSE_THROTTLE_MS = 500L
        val SINGLE_SOUNDER_TOKEN = Any()
        val ENFORCE_RETRY_DELAYS_MS = longArrayOf(600L, 1600L, 3200L)
    }
}
