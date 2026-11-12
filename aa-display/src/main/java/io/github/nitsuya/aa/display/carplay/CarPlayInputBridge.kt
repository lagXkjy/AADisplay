package io.github.nitsuya.aa.display.carplay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import kotlin.math.abs

/**
 * Maps normalized HU screen coordinates (0..1) onto AADisplay injection paths.
 *
 * Host chrome (divider / peel / Recents / picker) handles first; remaining touches
 * land on the pane VirtualDisplay that owns the touched half ([CoreApi.touchPane]),
 * re-normalized into that pane's pixel space.
 *
 * Split seam hit policy (side-by-side):
 * - Three-dot grip → chrome immediately (tap-swap / drag / long-press Recents).
 * - Expanded seam band outside the grip → pane first; chrome steals only after
 *   horizontal drag slop or long-press. Otherwise short taps on left-pane
 *   top-right / right-pane top-left (app chrome near the seam) were swallowed
 *   with a no-op restore of [primaryRatio].
 */
object CarPlayInputBridge {
    @Volatile
    var shellWidthPx: Int = CarPlayConsts.ENCODE_WIDTH

    @Volatile
    var shellHeightPx: Int = CarPlayConsts.ENCODE_HEIGHT

    /** Encode dpi. Hit tests use dp the same way [SplitPane.dividerPx] does. */
    @Volatile
    var densityDpi: Int = CarPlayConsts.ENCODE_UI_DENSITY_DPI

    /** Primary pane share of the split display width (matches CoreApi.onCreateSplitDisplay). */
    @Volatile
    var primaryRatio: Float = SplitPane.DEFAULT_RATIO

    /**
     * Visible fullscreen pane while Host is in peel mode; [SplitPane.FULLSCREEN_NONE] = split.
     * Mirrored for [CarPlayGlCompositor] and gesture routing.
     */
    @Volatile
    var fullscreenPane: Int = SplitPane.FULLSCREEN_NONE

    /**
     * Fullscreen peel drag in progress — GL draws AA-style clip drawer preview
     * (both full-size panes scissored) instead of a single fullscreen pane.
     * [primaryRatio] is the live finger seam while this is true.
     */
    @Volatile
    var peelPreviewActive: Boolean = false

    private const val RATIO_PREVIEW_INTERVAL_MS = 100L
    /** Normalized move slop before a press becomes a drag (~AA touchSlop × 2). */
    private const val DRAG_SLOP = 0.02f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val seamLongPressRunnable = Runnable { onSeamLongPress() }

    @Volatile
    private var chromeArmed = false
    @Volatile
    private var chromeDragging = false
    /** DOWN landed on the three-dot grip — only then may UP tap-swap. */
    @Volatile
    private var chromeDownOnGrip = false
    /**
     * Non-grip seam DOWN: pane owns the pointer until drag / long-press steals.
     * Cleared on UP/CANCEL or when chrome takes over.
     */
    @Volatile
    private var seamPending = false
    /** Long-press timer stole from the pane; UP should open Recents. */
    @Volatile
    private var seamLongPressFired = false
    @Volatile
    private var chromeDownX = 0f
    @Volatile
    private var chromeDownY = 0f
    @Volatile
    private var chromeDownAt = 0L
    /** Last chrome MOVE x (raw 0..1); settle prefers this over UP — some HUs echo DOWN on lift. */
    @Volatile
    private var chromeLastX = 0f
    @Volatile
    private var ratioAtDown = SplitPane.DEFAULT_RATIO
    @Volatile
    private var lastPreviewAt = 0L
    @Volatile
    private var paneDownX = 0f
    @Volatile
    private var paneDownY = 0f
    @Volatile
    private var paneDownAt = 0L
    /** HU pointer id for [paneDownX]/[paneDownY] — CANCEL must match the live id. */
    @Volatile
    private var paneDownPointerId = 0
    @Volatile
    private var paneDownPane = SplitPane.PRIMARY
    @Volatile
    private var paneMoved = false
    /**
     * DOWN landed in the non-grip seam band ([seamPending]). Empty-pane picker must
     * not fire on that gesture — aborted divider drags were opening the picker.
     */
    @Volatile
    private var paneEmptyPickerSuppressed = false
    @Volatile
    private var imeChipArmed = false

    /**
     * True while divider / peel owns the pointer (skip layout sync that resets ratio).
     * Includes [seamPending]: non-grip seam may still become a drag, and a mid-gesture
     * CoreApi pull would snap [primaryRatio] back to the pre-drag value.
     */
    fun isChromeGestureActive(): Boolean = chromeArmed || chromeDragging || seamPending

    /**
     * Single-pointer convenience entry (BT mouse / HID shell drains): wraps one
     * (x, y, action) as a one-pointer event and delegates to [injectEvent], which
     * owns (recycles) the event.
     */
    fun injectNormalized(xNorm: Float, yNorm: Float, action: Int) {
        // Own clock: HID shell drains must not reset the HU TouchGestureClock mid-gesture.
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            now, now, action, xNorm.coerceIn(0f, 1f), yNorm.coerceIn(0f, 1f), 0,
        )
        injectEvent(event)
    }

    /**
     * Multi-pointer entry: [event] carries normalized canvas coords (0..1) with full
     * pointer data (AAP / CarPlay decode). Pointer 0 drives the single-finger chrome
     * state machines (overlays / IME chip / divider / peel); the whole event goes to
     * the pane owning pointer 0 — pointers whose own x falls in the other pane are
     * dropped (cross-pane gestures unsupported). Recycles [event].
     */
    fun injectEvent(event: MotionEvent) {
        try {
            if (event.pointerCount <= 0) {
                Log.w(CarPlayConsts.TAG, "CarPlay touch dropped: empty pointer table")
                return
            }
            val x = event.getX(0).coerceIn(0f, 1f)
            val y = event.getY(0).coerceIn(0f, 1f)
            // Chrome / overlay state machines are single-finger.
            // Last-finger POINTER_UP must become UP — otherwise divider/Recents/drag
            // stay armed forever (lift looks ignored).
            val action = chromeAction(event)

            // Canvas picker (Host has no Window — View snapshot touch is broken).
            if (CarPlayAppPickerOverlay.isVisible()) {
                CarPlayAppPickerOverlay.onTouch(x, y, action)
                return
            }
            if (CarPlayRecentsOverlay.isVisible()) {
                CarPlayRecentsOverlay.onTouch(x, y, action)
                return
            }

            // Host chrome (hide-IME chip / divider / peel / overlays).
            if (handleImeChip(x, y, action)) return
            if (handleChrome(x, y, action)) return

            routeEventToPane(event)
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "CarPlay touch inject failed", e)
        } finally {
            event.recycle()
        }
    }

    /** Single-finger chrome view of a possibly multi-pointer HU event. */
    private fun chromeAction(event: MotionEvent): Int {
        val masked = event.actionMasked
        val n = event.pointerCount
        return when {
            masked == MotionEvent.ACTION_POINTER_UP && n <= 1 -> MotionEvent.ACTION_UP
            masked == MotionEvent.ACTION_POINTER_DOWN && n == 1 -> MotionEvent.ACTION_DOWN
            masked == MotionEvent.ACTION_POINTER_DOWN ||
                masked == MotionEvent.ACTION_POINTER_UP -> MotionEvent.ACTION_MOVE
            else -> masked
        }
    }

    /**
     * 「收起键盘」on the encode canvas. The whole gesture is consumed; UP calls
     * [CoreApi.hideIme] so the tap does not land on the space bar underneath.
     */
    private fun handleImeChip(x: Float, y: Float, action: Int): Boolean {
        val rect = imeChipRect()
        val inside = rect != null && rect.contains(x * shellWidth(), y * shellHeight())
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                imeChipArmed = inside
                return imeChipArmed
            }
            MotionEvent.ACTION_MOVE -> return imeChipArmed
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (!imeChipArmed) return false
                imeChipArmed = false
                if (action == MotionEvent.ACTION_UP) {
                    CarPlayImeChip.dismissLocal()
                    try {
                        CoreApi.hideIme()
                    } catch (e: Throwable) {
                        Log.w(CarPlayConsts.TAG, "hideIme failed", e)
                    }
                }
                return true
            }
            else -> return imeChipArmed
        }
    }

    /**
     * Side-by-side seam only: AA thickness ([SplitPane.DIVIDER_DP] + expand) and
     * [SplitPane.DIVIDER_TOUCH_END_INSET_DP] dead zones at the top and bottom.
     * Grip → chrome immediately. Non-grip expand band → [seamPending] (pane first).
     * Ends return false so the pane still receives the touch.
     */
    private fun dividerSeamHit(xNorm: Float, yNorm: Float): Boolean {
        val w = shellWidth()
        val h = shellHeight()
        val dpi = densityDpi.coerceAtLeast(160)
        val scale = dpi / 160f
        val ratio = primaryRatio.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
        val (left, gap, _) = CarPlayChromePainter.splitStrip(w, ratio, dpi)
        val seamX = left + gap / 2f
        val half = (SplitPane.DIVIDER_DP / 2f + SplitPane.DIVIDER_TOUCH_EXPAND_DP) * scale
        val inset = SplitPane.DIVIDER_TOUCH_END_INSET_DP * scale
        val xPx = xNorm * w
        val yPx = yNorm * h
        if (h > inset * 2f && (yPx < inset || yPx > h - inset)) return false
        return abs(xPx - seamX) <= half
    }

    /** Three-dot grip on the seam (centered). Finger expand — HU tap-to-swap only. */
    private fun dividerGripHit(xNorm: Float, yNorm: Float): Boolean {
        if (!dividerSeamHit(xNorm, yNorm)) return false
        val h = shellHeight().toFloat()
        return SplitPane.isAlongDividerGrip(
            alongPx = yNorm * h,
            longAxisSizePx = h,
            densityDpi = densityDpi.coerceAtLeast(160),
            finger = true,
        )
    }

    /**
     * Fullscreen peel short-tab hit — same geometry as [HidSplitLayout.isPeelHit] /
     * [SplitPane.peelHitContains]. Must not arm a full-height left strip: that
     * swallowed app chrome (e.g. 返回) along the entire Y axis.
     */
    private fun peelTabHit(xNorm: Float, yNorm: Float): Boolean {
        val w = shellWidth()
        val h = shellHeight()
        val dpi = densityDpi.coerceAtLeast(160)
        val scale = dpi / 160f
        val xPx = xNorm * w
        val yPx = yNorm * h
        val edge = (SplitPane.PEEL_EDGE_HIT_MIN_DP * scale).coerceAtLeast(1f)
        val sideBySide = w >= h
        if (sideBySide) {
            if (xPx > edge) return false
        } else {
            if (yPx > edge) return false
        }
        return SplitPane.peelHitContains(xPx, yPx, w, h, scale)
    }

    /** Debug: how far the finger is from the live seam (encode px). */
    private fun dividerSeamDeltaPx(xNorm: Float): Float {
        val w = shellWidth()
        val dpi = densityDpi.coerceAtLeast(160)
        val ratio = primaryRatio.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
        val (left, gap, _) = CarPlayChromePainter.splitStrip(w, ratio, dpi)
        val seamX = left + gap / 2f
        return xNorm * w - seamX
    }

    private val imeChipBounds = android.graphics.RectF()

    private fun imeChipRect(): android.graphics.RectF? {
        if (CarPlayRecentsOverlay.isVisible() || CarPlayAppPickerOverlay.isVisible()) return null
        if (!SplitPane.isValid(CarPlayImeChip.pane)) return null
        val ok = CarPlayChromePainter.layoutHideImeChip(
            imeChipBounds,
            shellWidth(),
            shellHeight(),
            densityDpi.coerceAtLeast(160),
            CarPlayImeChip.pane,
            SplitPane.isFullscreenPane(fullscreenPane),
            primaryRatio,
            CarPlayImeChip.label,
        )
        return if (ok) imeChipBounds else null
    }

    private fun shellWidth(): Int = shellWidthPx.coerceAtLeast(1)

    private fun shellHeight(): Int = shellHeightPx.coerceAtLeast(1)

    /**
     * Divider / peel gestures. Returns true when the event was consumed as chrome
     * (do not forward into a pane).
     */
    private fun handleChrome(x: Float, y: Float, action: Int): Boolean {
        val fs = fullscreenPane
        val inFullscreen = SplitPane.isFullscreenPane(fs)
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                clearSeamPending(cancelPane = false)
                chromeDragging = false
                peelPreviewActive = false
                seamLongPressFired = false
                chromeDownX = x
                chromeDownY = y
                chromeLastX = x
                chromeDownAt = SystemClock.uptimeMillis()
                ratioAtDown = primaryRatio.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)

                if (inFullscreen) {
                    chromeArmed = peelTabHit(x, y)
                    chromeDownOnGrip = false
                    if (!chromeArmed) return false
                    Log.i(
                        CarPlayConsts.TAG,
                        "divider arm x=$x y=$y grip=false ratio=$ratioAtDown fs=true",
                    )
                    return true
                }

                val onSeam = dividerSeamHit(x, y)
                val onGrip = onSeam && dividerGripHit(x, y)
                if (!onSeam) {
                    chromeArmed = false
                    chromeDownOnGrip = false
                    val dpi = densityDpi.coerceAtLeast(160)
                    val half = (SplitPane.DIVIDER_DP / 2f + SplitPane.DIVIDER_TOUCH_EXPAND_DP) *
                        (dpi / 160f)
                    val delta = dividerSeamDeltaPx(x)
                    // Only log near-misses so we can tune hit width without spamming.
                    if (abs(delta) <= half * 3f) {
                        Log.i(
                            CarPlayConsts.TAG,
                            "divider miss x=$x y=$y deltaPx=$delta half=$half " +
                                "ratio=$primaryRatio dpi=$dpi ${shellWidth()}x${shellHeight()}",
                        )
                    }
                    return false
                }

                if (onGrip) {
                    // Grip: chrome owns immediately (tap-swap / drag / long-press).
                    chromeArmed = true
                    chromeDownOnGrip = true
                    Log.i(
                        CarPlayConsts.TAG,
                        "divider arm x=$x y=$y grip=true ratio=$ratioAtDown fs=false",
                    )
                    return true
                }

                // Expanded seam outside grip: pane gets the tap; steal on drag / long-press.
                chromeArmed = false
                chromeDownOnGrip = false
                seamPending = true
                mainHandler.removeCallbacks(seamLongPressRunnable)
                mainHandler.postDelayed(
                    seamLongPressRunnable,
                    SplitPane.DIVIDER_TAP_STACK_MIN_MS,
                )
                Log.i(
                    CarPlayConsts.TAG,
                    "divider seam pending→pane x=$x y=$y ratio=$ratioAtDown",
                )
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (seamPending) {
                    val heldMs = SystemClock.uptimeMillis() - chromeDownAt
                    val dist = abs(x - chromeDownX)
                    val begin = if (heldMs >= SplitPane.DIVIDER_TAP_STACK_MIN_MS) {
                        // Long-press lane has expired. A busy main thread can delay the
                        // postDelayed runnable so seamLongPressFired stays false — the
                        // input timestamp is the source of truth; plain slop starts the drag.
                        !seamLongPressFired && dist > DRAG_SLOP
                    } else {
                        SplitPane.shouldBeginDividerDrag(
                            dist, DRAG_SLOP, seamLongPressFired, heldMs,
                        )
                    }
                    if (begin) {
                        stealSeamToChrome(startDrag = true, x = x)
                        return true
                    }
                    return false
                }
                if (!chromeArmed) return false
                chromeLastX = x
                val heldMs = SystemClock.uptimeMillis() - chromeDownAt
                val dist = abs(x - chromeDownX)
                if (!chromeDragging) {
                    // Same 2×-slop gate as AA [SplitLockedPeelController] / seam pending —
                    // 1× DRAG_SLOP turned HU digitizer jitter into wasDragging and killed
                    // fullscreen peel tap-to-switch.
                    if (!SplitPane.shouldBeginDividerDrag(
                            dist, DRAG_SLOP, seamLongPressFired, heldMs,
                        )
                    ) {
                        return true
                    }
                    chromeDragging = true
                }
                if (chromeDragging) {
                    if (inFullscreen) {
                        // Inward peel preview: finger X = seam; GL clips both full panes.
                        primaryRatio = x.coerceIn(0f, 1f)
                        peelPreviewActive = true
                    } else {
                        peelPreviewActive = false
                        previewRatio(x)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (seamPending) {
                    // HU digitizers often omit MOVE and jump DOWN→UP (fast flick / coarse
                    // panel): a horizontal jump is a divider drag, not a tap — steal the
                    // pointer from the pane and settle the seam at the release point.
                    val jumped = action == MotionEvent.ACTION_UP &&
                        abs(x - chromeDownX) > DRAG_SLOP * 2f
                    if (jumped) {
                        mainHandler.removeCallbacks(seamLongPressRunnable)
                        seamPending = false
                        cancelPanePointer()
                        seamLongPressFired = false
                        settleRatioOrFullscreen(x)
                        Log.i(
                            CarPlayConsts.TAG,
                            "divider seam jump-drag (no MOVE) upX=$x settle",
                        )
                        return true
                    }
                    // Short tap / scroll stayed with the pane — do not consume UP.
                    clearSeamPending(cancelPane = false)
                    return false
                }
                if (!chromeArmed) return false
                val heldMs = SystemClock.uptimeMillis() - chromeDownAt
                val wasDragging = chromeDragging
                // Prefer last MOVE x when dragging: some HUs echo the DOWN sample on UP,
                // which would settle back to ratioAtDown after a successful preview drag.
                val settleX = if (wasDragging) chromeLastX else x
                val dist = abs(settleX - chromeDownX)
                val ratioDelta = abs(settleX - ratioAtDown)
                val downOnGrip = chromeDownOnGrip
                val longPress = seamLongPressFired
                // Settle while chrome still counts as active so GL/layout sync cannot
                // overwrite primaryRatio with the pre-drag CoreApi value mid-UP.
                peelPreviewActive = false
                if (inFullscreen) {
                    handlePeelUp(
                        settleX, heldMs, dist, wasDragging,
                        action == MotionEvent.ACTION_CANCEL,
                    )
                } else {
                    handleDividerUp(
                        settleX, heldMs, dist, ratioDelta, wasDragging, downOnGrip,
                        action == MotionEvent.ACTION_CANCEL,
                        longPressFired = longPress,
                    )
                }
                chromeArmed = false
                chromeDragging = false
                chromeDownOnGrip = false
                seamLongPressFired = false
                return true
            }
            else -> return chromeArmed || seamPending
        }
    }

    /** Long-press on non-grip seam: cancel the pane pointer and let chrome own UP → Recents. */
    private fun onSeamLongPress() {
        if (!seamPending) return
        stealSeamToChrome(startDrag = false, x = chromeDownX)
        seamLongPressFired = true
        Log.i(CarPlayConsts.TAG, "divider seam long-press steal x=$chromeDownX y=$chromeDownY")
    }

    /**
     * Take the pointer from the pane for divider drag / long-press Recents.
     * Sends [MotionEvent.ACTION_CANCEL] so the app does not keep a stuck DOWN.
     */
    private fun stealSeamToChrome(startDrag: Boolean, x: Float) {
        mainHandler.removeCallbacks(seamLongPressRunnable)
        if (seamPending) {
            seamPending = false
            cancelPanePointer()
        }
        chromeArmed = true
        chromeDownOnGrip = false
        peelPreviewActive = false
        if (startDrag) {
            chromeDragging = true
            chromeLastX = x
            previewRatio(x)
            Log.i(CarPlayConsts.TAG, "divider seam drag steal x=$x")
        }
    }

    private fun clearSeamPending(cancelPane: Boolean) {
        mainHandler.removeCallbacks(seamLongPressRunnable)
        if (!seamPending) return
        seamPending = false
        if (cancelPane) cancelPanePointer()
    }

    /**
     * Cancel the in-flight pane gesture at the last DOWN point. A single ACTION_CANCEL
     * ends all active pointers on the injected device.
     */
    private fun cancelPanePointer() {
        val (down, eventTime) = TouchGestureClock.stamp(MotionEvent.ACTION_CANCEL)
        val event = buildMultiPointerEvent(
            down,
            eventTime,
            MotionEvent.ACTION_CANCEL,
            0,
            listOf(paneDownPointerId to floatArrayOf(paneDownX, paneDownY)),
            remapIds = false,
        )
        try {
            routeEventToPane(event)
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "cancel pane after seam steal failed", e)
        } finally {
            event.recycle()
        }
    }

    private fun handleDividerUp(
        x: Float,
        heldMs: Long,
        dist: Float,
        ratioDelta: Float,
        wasDragging: Boolean,
        downOnGrip: Boolean,
        cancelled: Boolean,
        longPressFired: Boolean = false,
    ) {
        if (cancelled) {
            primaryRatio = ratioAtDown
            return
        }
        if (SplitPane.qualifiesDividerLongPress(longPressFired, heldMs) &&
            SplitPane.qualifiesDividerRecentOnUp(
                longPressFired || heldMs >= SplitPane.DIVIDER_TAP_STACK_MIN_MS,
                wasDragging, dist, DRAG_SLOP, ratioDelta,
            )
        ) {
            primaryRatio = ratioAtDown
            CarPlayRecentsOverlay.open()
            return
        }
        // Tap-to-swap only from the three-dot grip — seam ends must not swap.
        if (downOnGrip &&
            SplitPane.shouldDividerSwapOnUp(wasDragging, false, heldMs, dist, DRAG_SLOP, ratioDelta)
        ) {
            performSwap()
            return
        }
        if (wasDragging || dist > DRAG_SLOP) {
            settleRatioOrFullscreen(x)
        } else {
            primaryRatio = ratioAtDown
        }
    }

    private fun handlePeelUp(
        x: Float,
        heldMs: Long,
        dist: Float,
        wasDragging: Boolean,
        cancelled: Boolean,
    ) {
        if (cancelled) {
            // Keep fullscreen; snap seam back so the next peel starts from stored ratio.
            primaryRatio = ratioAtDown
            return
        }
        val ratioDelta = x - chromeDownX
        val longPress = SplitPane.qualifiesDividerLongPress(false, heldMs) &&
            dist <= DRAG_SLOP * 3f
        // Match AA [SplitLockedPeelController] UP order: drag settle → Recents → tap flip.
        // Tap uses [shouldDividerSwapOnUp] so micro-jitter on lift still switches panes.
        when {
            wasDragging -> {
                if (x > SplitPane.FULLSCREEN_EXIT_RATIO) {
                    exitFullscreen(x.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO))
                } else {
                    // Below exit threshold — same as AA onPeelCancelled: stay FS, restore ratio.
                    primaryRatio = ratioAtDown
                }
            }
            longPress -> {
                primaryRatio = ratioAtDown
                CarPlayRecentsOverlay.open()
            }
            SplitPane.shouldDividerSwapOnUp(
                wasDragging, longPress, heldMs, dist, DRAG_SLOP, ratioDelta,
            ) -> {
                primaryRatio = ratioAtDown
                val other = if (fullscreenPane == SplitPane.PRIMARY) {
                    SplitPane.SECONDARY
                } else {
                    SplitPane.PRIMARY
                }
                enterFullscreen(other)
            }
            else -> primaryRatio = ratioAtDown
        }
    }

    private fun performSwap() {
        val next = SplitPane.clampRatio(1f - primaryRatio)
        primaryRatio = next
        try {
            CoreApi.swapSplitPanes()
            Log.i(CarPlayConsts.TAG, "CarPlay divider tap → swap ratio=$next")
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "swapSplitPanes failed", e)
        }
    }

    private fun previewRatio(x: Float) {
        val ratio = x.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
        primaryRatio = ratio
        val now = SystemClock.uptimeMillis()
        if (now - lastPreviewAt < RATIO_PREVIEW_INTERVAL_MS) return
        lastPreviewAt = now
        // Preview only updates local ratio for GL; VD resize waits for settle on UP.
    }

    private fun settleRatioOrFullscreen(rawX: Float) {
        SplitPane.fullscreenPaneForRawRatio(rawX)?.let { pane ->
            enterFullscreen(pane)
            return
        }
        val ratio = rawX.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
        primaryRatio = ratio
        try {
            CoreApi.setSplitRatio(ratio)
            // GL drawFrame also syncs ST buffers; hint immediately so the next
            // producer frames match the new VD sizes before the first post-settle tick.
            syncGlPaneBuffers(ratio)
            Log.i(CarPlayConsts.TAG, "CarPlay divider settle ratio=$ratio")
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "setSplitRatio($ratio) failed", e)
        }
    }

    private fun syncGlPaneBuffers(ratio: Float) {
        // HostRuntime for CarPlay; activeOrNull also covers AAP (no HostRuntime).
        val gl = CarPlayHostRuntime.compositorOrNull()
            ?: CarPlayGlCompositor.activeOrNull()
            ?: return
        val w = shellWidthPx.coerceAtLeast(2)
        val h = shellHeightPx.coerceAtLeast(1)
        // Same dpi as HostRuntime / GlCompositor / VD (manual override or encode default).
        val gap = SplitPane.dividerPx(gl.currentDensityDpi())
        val usable = (w - gap).coerceAtLeast(2)
        val left = (usable * ratio).toInt().coerceAtLeast(1)
        val right = (usable - left).coerceAtLeast(1)
        gl.syncPaneBuffers(left, right, h)
    }

    private fun enterFullscreen(pane: Int) {
        if (!SplitPane.isFullscreenPane(pane)) return
        fullscreenPane = pane
        try {
            CoreApi.setSplitFullscreen(pane)
            Log.i(CarPlayConsts.TAG, "CarPlay enter fullscreen pane=$pane")
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "setSplitFullscreen($pane) failed", e)
        }
    }

    private fun exitFullscreen(ratio: Float) {
        val clamped = SplitPane.clampRatio(ratio)
        primaryRatio = clamped
        fullscreenPane = SplitPane.FULLSCREEN_NONE
        try {
            // AA order: write ratio first, then clear fullscreen (one VD resize).
            CoreApi.setSplitRatio(clamped)
            CoreApi.setSplitFullscreen(SplitPane.FULLSCREEN_NONE)
            Log.i(CarPlayConsts.TAG, "CarPlay exit fullscreen ratio=$clamped")
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "exit fullscreen failed", e)
        }
    }

    /** Pane owning normalized canvas x (fullscreen-aware). */
    private fun paneFor(xNorm: Float): Int {
        val fs = fullscreenPane
        if (SplitPane.isFullscreenPane(fs)) return fs
        val ratio = primaryRatio.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
        return if (xNorm < ratio) SplitPane.PRIMARY else SplitPane.SECONDARY
    }

    /** Normalized canvas x → (x within [pane] 0..1, pane width in encode px). */
    private fun mapToPane(xNorm: Float, pane: Int, w: Int): Pair<Float, Int> {
        val fs = fullscreenPane
        // Fullscreen: canvas == pane; keep the finger's x (do not pin to 1f — that
        // collapsed every touch to the right edge and killed scroll/drag).
        if (SplitPane.isFullscreenPane(fs)) return xNorm.coerceIn(0f, 1f) to w
        val ratio = primaryRatio.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
        return if (pane == SplitPane.PRIMARY) {
            (xNorm / ratio) to (w * ratio).toInt().coerceAtLeast(1)
        } else {
            val share = (1f - ratio).coerceAtLeast(0.01f)
            ((xNorm - ratio) / share) to (w - w * ratio).toInt().coerceAtLeast(1)
        }
    }

    /**
     * Routes the whole event to the pane owning pointer 0: each pointer is re-mapped
     * into that pane's pixel space, pointers in the other pane dropped (cross-pane
     * gestures unsupported). Does NOT recycle [event] — the caller owns it.
     * Short tap on an empty pane opens the app picker (single-finger only).
     * HU digitizers often omit MOVE and only jump DOWN→UP — displacement is
     * checked on UP so a drag is not mistaken for a tap.
     */
    private fun routeEventToPane(event: MotionEvent) {
        val w = shellWidthPx.coerceAtLeast(1)
        val h = shellHeightPx.coerceAtLeast(1)
        val x0 = event.getX(0).coerceIn(0f, 1f)
        val y0 = event.getY(0).coerceIn(0f, 1f)
        val pane = paneFor(x0)
        val maskedIn = event.actionMasked

        when (maskedIn) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (maskedIn == MotionEvent.ACTION_DOWN || event.pointerCount == 1) {
                    paneDownX = x0
                    paneDownY = y0
                    paneDownPointerId = event.getPointerId(0)
                    paneDownAt = SystemClock.uptimeMillis()
                    paneDownPane = pane
                    paneMoved = false
                    // handleChrome may have armed seamPending just before this DOWN.
                    paneEmptyPickerSuppressed = seamPending
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(x0 - paneDownX) > DRAG_SLOP || abs(y0 - paneDownY) > DRAG_SLOP) {
                    paneMoved = true
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                // Cars often skip MOVE; treat DOWN→UP jump as a drag.
                if (abs(x0 - paneDownX) > DRAG_SLOP || abs(y0 - paneDownY) > DRAG_SLOP) {
                    paneMoved = true
                }
                // Empty-pane picker is a single-finger tap; skip it for multi gestures.
                val lastFinger = maskedIn == MotionEvent.ACTION_UP || event.pointerCount <= 1
                if (lastFinger && event.pointerCount == 1) {
                    val heldMs = SystemClock.uptimeMillis() - paneDownAt
                    if (!paneMoved &&
                        !paneEmptyPickerSuppressed &&
                        heldMs <= SplitPane.DIVIDER_TAP_SWAP_MAX_MS
                    ) {
                        val empty = try {
                            CoreApi.getPanePackage(paneDownPane).isNullOrEmpty()
                        } catch (_: Throwable) {
                            false
                        }
                        if (empty) {
                            // Finish the pane DOWN before opening the picker, otherwise
                            // the empty VD stays pressed under the overlay.
                            injectPaneEnd(event, paneDownPane, w, h, MotionEvent.ACTION_CANCEL)
                            CarPlayAppPickerOverlay.show(paneDownPane)
                            return
                        }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                paneMoved = true
                paneEmptyPickerSuppressed = true
            }
            else -> Unit
        }

        // SDK 36 dropped incremental MotionEvent construction: build the pane-local
        // event in one obtain() with the filtered pointer table.
        val pts = ArrayList<Pair<Int, FloatArray>>(event.pointerCount)
        for (i in 0 until event.pointerCount) {
            val nx = event.getX(i).coerceIn(0f, 1f)
            if (paneFor(nx) != pane) continue // Cross-pane unsupported → pointer dropped
            val (xPane, paneWidth) = mapToPane(nx, pane, w)
            val px = xPane * (paneWidth - 1).toFloat()
            val py = event.getY(i).coerceIn(0f, 1f) * (h - 1).toFloat()
            pts.add(event.getPointerId(i) to floatArrayOf(px, py))
        }
        injectFilteredPaneEvent(event, pane, w, h, maskedIn, pts)
    }

    /** Finish a pane gesture when the UP is consumed by Host chrome (empty picker). */
    private fun injectPaneEnd(
        event: MotionEvent,
        pane: Int,
        w: Int,
        h: Int,
        masked: Int,
    ) {
        val (xPane, paneWidth) = mapToPane(paneDownX, pane, w)
        val pts = listOf(
            paneDownPointerId to floatArrayOf(
                xPane * (paneWidth - 1).toFloat(),
                paneDownY.coerceIn(0f, 1f) * (h - 1).toFloat(),
            ),
        )
        val out = buildMultiPointerEvent(
            event.downTime, event.eventTime, masked, 0, pts, remapIds = false,
        )
        try {
            CoreApi.touchPane(pane, out)
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "pane end inject failed pane=$pane", e)
        } finally {
            out.recycle()
        }
    }

    private fun injectFilteredPaneEvent(
        event: MotionEvent,
        pane: Int,
        w: Int,
        h: Int,
        maskedIn: Int,
        pts: ArrayList<Pair<Int, FloatArray>>,
    ) {
        var injectPane = pane
        if (pts.isEmpty()) {
            // Filtered away every pointer on an end event — still must finish the
            // gesture or the VD stays pressed ("拖到哪里停在哪里").
            if (maskedIn == MotionEvent.ACTION_UP ||
                maskedIn == MotionEvent.ACTION_CANCEL ||
                (maskedIn == MotionEvent.ACTION_POINTER_UP && event.pointerCount <= 1)
            ) {
                injectPane = paneDownPane
                val (xPane, paneWidth) = mapToPane(paneDownX, injectPane, w)
                pts.add(
                    paneDownPointerId to floatArrayOf(
                        xPane * (paneWidth - 1).toFloat(),
                        paneDownY.coerceIn(0f, 1f) * (h - 1).toFloat(),
                    ),
                )
            } else {
                return
            }
        }

        var outMasked = maskedIn
        var outIndex = 0
        when (maskedIn) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                val srcIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
                val affectedId = event.getPointerId(srcIndex)
                val mapped = pts.indexOfFirst { it.first == affectedId }
                if (mapped < 0) {
                    // Affected finger was cross-pane-dropped.
                    outMasked = if (maskedIn == MotionEvent.ACTION_POINTER_UP && pts.size <= 1) {
                        MotionEvent.ACTION_UP
                    } else {
                        MotionEvent.ACTION_MOVE
                    }
                    outIndex = 0
                } else {
                    outIndex = mapped
                    if (maskedIn == MotionEvent.ACTION_POINTER_UP && pts.size <= 1) {
                        outMasked = MotionEvent.ACTION_UP
                        outIndex = 0
                    }
                }
            }
            else -> outIndex = 0
        }

        val out = buildMultiPointerEvent(
            event.downTime, event.eventTime, outMasked, outIndex, pts,
            remapIds = false,
        )
        try {
            val ok = try {
                CoreApi.touchPane(injectPane, out)
                true
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "touchPane($injectPane) from CarPlay failed", e)
                false
            }
            if (outMasked != MotionEvent.ACTION_MOVE && !ok) {
                Log.w(
                    CarPlayConsts.TAG,
                    "CarPlay pane touch dropped pane=$injectPane action=$outMasked n=${out.pointerCount}",
                )
            }
        } finally {
            out.recycle()
        }
    }
}

/**
 * Builds a multi-pointer [MotionEvent] from (pointerId, [x, y]) pairs in table order
 * (x/y in the producer's space — normalized canvas or pane-px). Pointer ids stay
 * stable across the gesture so releasing a finger does not re-identify the others.
 * SDK 36 removed the incremental constructors, so the whole pointer table goes into
 * one [MotionEvent.obtain]; the POINTER_* index is packed into the action bits per
 * the classic input protocol.
 */
/**
 * @param remapIds true at the HU protocol boundary (AAP / DiPlay). false when
 *   rebuilding an already-mapped event (pane coord transform) — remapping twice
 *   changed pointer ids between DOWN and UP and left the VD pressed.
 */
internal fun buildMultiPointerEvent(
    downTime: Long,
    eventTime: Long,
    maskedAction: Int,
    actionIndex: Int,
    pointers: List<Pair<Int, FloatArray>>,
    remapIds: Boolean = true,
): MotionEvent {
    require(pointers.isNotEmpty()) { "buildMultiPointerEvent requires ≥1 pointer" }
    val n = pointers.size
    // Kotlin `and`/`shl` precedence previously packed index as `actionIndex and 0xff00`
    // (= always 0). POINTER_* with a non-zero index must set bits 8..15.
    val safeIndex = actionIndex.coerceIn(0, n - 1)
    val action = when (maskedAction) {
        MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP ->
            maskedAction or (safeIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

        else -> maskedAction
    }
    val props = Array(n) { MotionEvent.PointerProperties() }
    val coords = Array(n) { MotionEvent.PointerCoords() }
    for (i in 0 until n) {
        val (id, xy) = pointers[i]
        props[i].apply {
            // InputDispatcher rejects ids outside 0..31.
            this.id = if (remapIds) TouchPointerIds.androidId(id) else id.coerceIn(0, 31)
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        coords[i].apply {
            x = xy[0]
            y = xy[1]
            // Single-pointer obtain(down,event,action,x,y,meta) defaults pressure/size to 1f;
            // leaving 0 made some feed/fling detectors treat the pointer as unused.
            pressure = 1f
            size = 1f
        }
    }
    if (remapIds) {
        if (maskedAction == MotionEvent.ACTION_UP || maskedAction == MotionEvent.ACTION_CANCEL) {
            TouchPointerIds.clear()
        } else if (maskedAction == MotionEvent.ACTION_POINTER_UP && safeIndex in pointers.indices) {
            TouchPointerIds.release(pointers[safeIndex].first)
        }
    }
    return MotionEvent.obtain(
        downTime, eventTime, action, n,
        props, coords,
        0, // metaState
        0, // buttonState
        // Match single-pointer obtain(down,event,action,x,y,meta): 1px precision.
        // 0f made some scroll/gesture detectors treat motion as unknown/unusable.
        1f, 1f, // xPrecision, yPrecision
        0, // deviceId
        0, // edgeFlags
        android.view.InputDevice.SOURCE_TOUCHSCREEN,
        0, // flags
    )
}

/**
 * Maps HU pointer ids into Android's 0..31 range for the life of a gesture.
 * Cleared on UP/CANCEL so the next DOWN starts fresh.
 */
internal object TouchPointerIds {
    private val huToAndroid = LinkedHashMap<Int, Int>()

    @Synchronized
    fun androidId(huId: Int): Int {
        huToAndroid[huId]?.let { return it }
        val used = huToAndroid.values.toHashSet()
        val next = (0..31).firstOrNull { it !in used } ?: (huId and 0x1f)
        huToAndroid[huId] = next
        return next
    }

    @Synchronized
    fun release(huId: Int) {
        huToAndroid.remove(huId)
    }

    @Synchronized
    fun clear() {
        huToAndroid.clear()
    }
}

/**
 * Sticky [MotionEvent] downTime + monotonic eventTime for synthesized HU touches.
 *
 * Stamp with the report's **arrival** uptime (reader thread), not the Binder-inject
 * drain time: a queued Handler burst otherwise collapses every sample onto one
 * millisecond → VelocityTracker velocity ≈ 0 → drag follows the finger but never flings
 * (Douyin / feed swipe).
 */
internal object TouchGestureClock {
    @Volatile
    private var downTime = 0L
    @Volatile
    private var lastEventTime = 0L

    fun stamp(
        maskedAction: Int,
        arrivalUptimeMs: Long = SystemClock.uptimeMillis(),
    ): Pair<Long, Long> {
        when (maskedAction) {
            MotionEvent.ACTION_DOWN -> {
                downTime = arrivalUptimeMs
                lastEventTime = arrivalUptimeMs
                return arrivalUptimeMs to arrivalUptimeMs
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val eventTime = if (lastEventTime <= 0L) {
                    arrivalUptimeMs
                } else {
                    arrivalUptimeMs.coerceAtLeast(lastEventTime + 1L)
                }
                val down = if (downTime > 0L) downTime else eventTime
                downTime = 0L
                lastEventTime = 0L
                return down to eventTime
            }
            else -> {
                val eventTime = if (lastEventTime <= 0L) {
                    arrivalUptimeMs
                } else {
                    arrivalUptimeMs.coerceAtLeast(lastEventTime + 1L)
                }
                if (downTime <= 0L) downTime = eventTime
                lastEventTime = eventTime
                return downTime to eventTime
            }
        }
    }

    fun reset() {
        downTime = 0L
        lastEventTime = 0L
        TouchPointerIds.clear()
    }
}
