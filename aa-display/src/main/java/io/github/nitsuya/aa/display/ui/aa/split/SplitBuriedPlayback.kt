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
 * Non-Av / idle Av fronts never steal focus.
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

    fun resumeFrontPlaybackIfPausedByUs(frontPkg: String) {
        val pkg = frontPkg.trim().takeIf { it.isNotEmpty() } ?: return
        if (!pausedByAADisplay.remove(pkg)) return
        val msm = c.context.getSystemService(MediaSessionManager::class.java) ?: return
        val sessions = try {
            msm.getActiveSessions(null)
        } catch (_: Throwable) {
            return
        }
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
    }
}
