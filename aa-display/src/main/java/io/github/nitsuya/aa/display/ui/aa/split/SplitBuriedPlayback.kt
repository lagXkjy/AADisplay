package io.github.nitsuya.aa.display.ui.aa.split

import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import io.github.nitsuya.aa.display.util.MusicAppClassifier
import io.github.nitsuya.aa.display.xposed.util.logDebug

/**
 * Pauses/resumes buried stack mates via MediaSession when [moveTaskToBack] alone
 * cannot stop non-music FGS audio (e.g. Douyin under a music app on the same VD pane).
 *
 * Buried music may keep playing only when the pane front is not AvMedia (maps/browser).
 * When the front is AvMedia (QQ / 汽水), buried music is paused — stack-top wins.
 * Short-video (Douyin) is not AvMedia; buried under a music front is still paused as non-music.
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
        val buriedSet = buried.toSet()
        val frontIsAv = MusicAppClassifier.isAvMediaPackage(c.context, frontPkg)

        val msm = c.context.getSystemService(MediaSessionManager::class.java) ?: return
        val sessions = try {
            msm.getActiveSessions(null)
        } catch (e: Throwable) {
            logDebug(SplitDisplayController.TAG, "pauseBuried getActiveSessions failed: ${e.message}")
            return
        }
        val now = SystemClock.uptimeMillis()
        for (controller in sessions) {
            val pkg = controller.packageName?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (pkg !in buriedSet) continue
            // Maps/browser on top: keep buried music. AvMedia on top: pause all buried AvMedia.
            if (!frontIsAv && MusicAppClassifier.isMusicSession(c.context, controller)) continue
            val state = controller.playbackState?.state ?: PlaybackState.STATE_NONE
            if (!isPlayingState(state)) continue
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

    private fun isPlayingState(state: Int): Boolean {
        return state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_FAST_FORWARDING ||
            state == PlaybackState.STATE_REWINDING
    }

    private companion object {
        const val PAUSE_THROTTLE_MS = 500L
    }
}
