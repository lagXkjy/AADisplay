package io.github.nitsuya.aa.display.ui.aa.split

import android.view.MotionEvent
import android.view.ViewConfiguration
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.util.logDebug
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Phone BT mouse → Host chrome (divider / peel / Recents) after Car SDK
 * [SplitDividerView] / AaDisplay presentation was removed.
 *
 * Coordinates are Host encode / VD-profile canvas (same space as [HidSplitLayout]).
 * Locked-keyguard peel stays in [SplitLockedPeelController]; this handles the
 * unlocked / normal-split path that used to hit the presentation View.
 */
internal class SplitHidChromeController(private val c: SplitDisplayController) {

    private var tracking = false
    private var dragging = false
    private var longPressFired = false
    private var downOnGrip = false
    private var downFullscreen = false
    private var downX = 0f
    private var downY = 0f
    private var downUptimeMs = 0L
    private var ratioAtDown = 0.5f

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

    fun reset() {
        tracking = false
        dragging = false
        longPressFired = false
        downOnGrip = false
        downFullscreen = false
        c.mHandler.removeCallbacks(longPressRunnable)
        CoreManagerService.publishLockedPeelPreview(false)
        CoreManagerService.publishHidDividerPreview(false)
    }

    /**
     * @return true when the event was consumed as Host chrome (do not inject into a pane).
     */
    fun tryHandle(event: MotionEvent): Boolean {
        if (CoreManagerService.aaUiShellCapture) {
            if (tracking) reset()
            return false
        }
        val layout = c.hidSplitLayout()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = layout.hit(event.x, event.y, chromeInteract = true)
                if (hit !is HidCursorHit.Divider) {
                    if (tracking) reset()
                    return false
                }
                tracking = true
                dragging = false
                longPressFired = false
                downX = event.x
                downY = event.y
                downUptimeMs = event.downTime
                ratioAtDown = SplitPane.clampRatio(c.mRatio)
                downFullscreen = SplitPane.isFullscreenPane(c.mFullscreenPane)
                downOnGrip = !downFullscreen && layout.isDividerGripHit(event.x, event.y)
                c.mHandler.removeCallbacks(longPressRunnable)
                c.mHandler.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val dist = hypot(
                    (event.x - downX).toDouble(),
                    (event.y - downY).toDouble(),
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
                if (!dragging) return true
                if (downFullscreen) {
                    val ratio = (event.x / c.mWidth.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                    CoreManagerService.publishLockedPeelPreview(true, ratio)
                } else {
                    // Preview only — same as CarPlayInputBridge.previewRatio / EXECUTION §15.10.
                    // Live setSplitRatio during HID drag storms VD resize + USB AAP.
                    // Unclamped 0..1 so Host seam can pass the 20/80 stop into the
                    // fullscreen-enter band (finger raw-X settle).
                    val raw = (event.x / c.mWidth.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                    CoreManagerService.publishHidDividerPreview(true, raw)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                c.mHandler.removeCallbacks(longPressRunnable)
                val cancel = event.actionMasked == MotionEvent.ACTION_CANCEL
                val wasDragging = dragging
                val wasLong = longPressFired
                val wasFs = downFullscreen
                val wasGrip = downOnGrip
                reset()
                if (cancel) return true
                when {
                    wasLong && !wasDragging -> {
                        CoreManagerService.showHidRecentTask()
                        logDebug(SplitDisplayController.TAG, "hidChrome long-press → Recents")
                    }
                    wasDragging && wasFs -> {
                        val release = (event.x / c.mWidth.coerceAtLeast(1).toFloat())
                        if (release > SplitPane.FULLSCREEN_EXIT_RATIO) {
                            val clamped = release.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
                            c.setSplitRatio(clamped)
                            c.setSplitFullscreen(SplitPane.FULLSCREEN_NONE)
                            logDebug(
                                SplitDisplayController.TAG,
                                "hidChrome peel → split ratio=$clamped",
                            )
                        } else {
                            // Below exit threshold — stay fullscreen (AA onPeelCancelled).
                            CoreManagerService.publishLockedPeelPreview(false)
                            logDebug(
                                SplitDisplayController.TAG,
                                "hidChrome peel cancel → stay fullscreen",
                            )
                        }
                    }
                    wasDragging -> {
                        // Same settle as CarPlayInputBridge.settleRatioOrFullscreen:
                        // edge drag → fullscreen; otherwise one VD resize on UP.
                        val raw = event.x / c.mWidth.coerceAtLeast(1).toFloat()
                        val fsPane = SplitPane.fullscreenPaneForRawRatio(raw)
                        if (fsPane != null) {
                            c.setSplitFullscreen(fsPane)
                            logDebug(
                                SplitDisplayController.TAG,
                                "hidChrome drag → fullscreen pane=$fsPane raw=$raw",
                            )
                        } else {
                            val clamped = raw.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
                            c.setSplitRatio(clamped)
                            logDebug(
                                SplitDisplayController.TAG,
                                "hidChrome drag settle ratio=$clamped",
                            )
                        }
                    }
                    wasFs -> {
                        val other = if (c.mFullscreenPane == SplitPane.PRIMARY) {
                            SplitPane.SECONDARY
                        } else {
                            SplitPane.PRIMARY
                        }
                        c.setSplitFullscreen(other)
                        CoreManagerService.publishLockedFullscreenFlip(other)
                        logDebug(
                            SplitDisplayController.TAG,
                            "hidChrome peel tap → fullscreen pane=$other",
                        )
                    }
                    wasGrip || abs(event.x - downX) < touchSlop -> {
                        c.swapPanesFromUser()
                        logDebug(SplitDisplayController.TAG, "hidChrome tap → swap")
                    }
                }
                return true
            }
            else -> return tracking
        }
    }

}
