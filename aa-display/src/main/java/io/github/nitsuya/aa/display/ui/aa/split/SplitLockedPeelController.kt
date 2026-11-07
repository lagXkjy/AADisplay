package io.github.nitsuya.aa.display.ui.aa.split

import android.app.KeyguardManager
import android.view.MotionEvent
import android.view.ViewConfiguration
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import kotlin.math.hypot

/**
 * While phone keyguard is locked and we are in fullscreen, interpret peel gestures here
 * (same semantics as Host chrome peel). Live preview is published via
 * [CoreManagerService.publishLockedPeelPreview] for HID / Host consumers.
 */
internal class SplitLockedPeelController(private val c: SplitDisplayController) {

    private var tracking = false
    private var dragging = false
    private var longPressFired = false
    private var downX = 0f
    private var downY = 0f
    private var downUptimeMs = 0L
    private var downRatio = 0f
    private var lastRawRatio = 0f

    private val touchSlop: Int by lazy {
        ViewConfiguration.get(c.context).scaledTouchSlop
    }
    private val longPressTimeout: Long by lazy {
        ViewConfiguration.getLongPressTimeout().toLong()
            .coerceAtLeast(SplitPane.DIVIDER_TAP_STACK_MIN_MS)
    }

    private val longPressRunnable = Runnable {
        if (tracking && !dragging) {
            longPressFired = true
        }
    }

    /** True when phone keyguard is showing (secure or swipe) — includes screen-on lock UI. */
    fun isPhoneKeyguardLocked(): Boolean {
        return try {
            val km = c.context.getSystemService(KeyguardManager::class.java) ?: return false
            km.isKeyguardLocked
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * @return true if the event was consumed as a locked-phone peel gesture.
     */
    fun tryHandle(event: MotionEvent): Boolean {
        // Recents / picker owns the chrome — never peel-swap under the overlay.
        if (CoreManagerService.aaUiShellCapture) {
            if (tracking) reset()
            return false
        }
        if (!isPhoneKeyguardLocked()) {
            if (tracking) reset()
            return false
        }
        if (!SplitPane.isFullscreenPane(c.mFullscreenPane)) {
            if (tracking) reset()
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val density = c.mDensityDpi.coerceAtLeast(160) / 160f
                if (!SplitPane.peelHitContains(event.x, event.y, c.mWidth, c.mHeight, density)) {
                    return false
                }
                tracking = true
                dragging = false
                longPressFired = false
                downX = event.x
                downY = event.y
                downUptimeMs = event.downTime
                lastRawRatio = rawRatio(event)
                downRatio = lastRawRatio
                c.mHandler.removeCallbacks(longPressRunnable)
                c.mHandler.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val dist = hypot(
                    (event.x - downX).toDouble(),
                    (event.y - downY).toDouble()
                ).toFloat()
                val heldMs = (event.eventTime - downUptimeMs).coerceAtLeast(0L)
                if (!dragging &&
                    SplitPane.shouldBeginDividerDrag(
                        dist, touchSlop.toFloat(), longPressFired, heldMs,
                    )
                ) {
                    dragging = true
                    c.mHandler.removeCallbacks(longPressRunnable)
                    longPressFired = false
                }
                if (dragging) {
                    lastRawRatio = rawRatio(event)
                    publishPreview(lastRawRatio)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                val wasDragging = dragging
                val runnableLongPress = longPressFired
                val isUp = event.actionMasked == MotionEvent.ACTION_UP
                val heldMs = (event.eventTime - downUptimeMs).coerceAtLeast(0L)
                val wasLongPress =
                    SplitPane.qualifiesDividerLongPress(runnableLongPress, heldMs)
                if (wasDragging && !wasLongPress) {
                    lastRawRatio = rawRatio(event)
                }
                c.mHandler.removeCallbacks(longPressRunnable)
                tracking = false
                dragging = false
                longPressFired = false
                val dist = hypot(
                    (event.x - downX).toDouble(),
                    (event.y - downY).toDouble(),
                ).toFloat()
                val ratioDelta = lastRawRatio - downRatio
                when {
                    // Drag (incl. peel-out-then-back) never opens Recent — heldMs≥550
                    // used to false-trigger qualifiesDividerRecentOnUp after cancel.
                    isUp && wasDragging -> settleDrag()
                    isUp && wasLongPress -> {
                        publishPreviewCancel()
                        openRecent()
                    }
                    isUp && SplitPane.shouldDividerSwapOnUp(
                        wasDragging, wasLongPress, heldMs, dist, touchSlop.toFloat(), ratioDelta,
                    ) -> {
                        publishPreviewCancel()
                        tapSwapFullscreenPane()
                    }
                    else -> publishPreviewCancel()
                }
                return true
            }
            else -> return tracking
        }
    }

    fun reset() {
        c.mHandler.removeCallbacks(longPressRunnable)
        tracking = false
        dragging = false
        longPressFired = false
        publishPreviewCancel()
    }

    private fun publishPreview(ratio: Float) {
        CoreManagerService.publishLockedPeelPreview(active = true, ratio = ratio)
    }

    private fun publishPreviewCancel() {
        CoreManagerService.publishLockedPeelPreview(active = false)
    }

    private fun rawRatio(event: MotionEvent): Float {
        val w = c.mWidth.coerceAtLeast(1).toFloat()
        val h = c.mHeight.coerceAtLeast(1).toFloat()
        val ratio = if (c.isSideBySide) event.x / w else event.y / h
        return ratio.coerceIn(0f, 1f)
    }

    private fun settleDrag() {
        if (lastRawRatio < SplitPane.FULLSCREEN_EXIT_RATIO) {
            publishPreviewCancel()
            return
        }
        val release = SplitPane.clampRatio(lastRawRatio)
        publishPreviewCancel()
        // Same order as Host chrome exitFullscreen: stash ratio then one exit resize.
        c.setSplitRatio(release)
        c.setSplitFullscreen(SplitPane.FULLSCREEN_NONE)
        log(
            SplitDisplayController.TAG,
            "lockedPeel exitFullscreen ratio=$release"
        )
    }

    private fun tapSwapFullscreenPane() {
        val cur = c.mFullscreenPane
        if (!SplitPane.isFullscreenPane(cur)) return
        val other = if (cur == SplitPane.PRIMARY) SplitPane.SECONDARY else SplitPane.PRIMARY
        c.setSplitFullscreen(other)
        logDebug(SplitDisplayController.TAG, "lockedPeel tap → fullscreen pane=$other")
    }

    private fun openRecent() {
        // Car SDK shell broadcast path deleted; Host GL Recents is opened in-app
        // (CarPlayRecentsOverlay). Under keyguard, arm shell-capture + cursor reveal.
        try {
            CoreManagerService.showHidRecentTask()
            logDebug(SplitDisplayController.TAG, "lockedPeel long-press → showHidRecentTask")
        } catch (e: Throwable) {
            log(SplitDisplayController.TAG, "lockedPeel openRecent failed:", e)
        }
    }
}
