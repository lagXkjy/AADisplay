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

    /** True while divider / peel gesture owns the pointer (skip layout sync that resets ratio). */
    fun isChromeGestureActive(): Boolean = chromeArmed || chromeDragging

    fun injectNormalized(xNorm: Float, yNorm: Float, action: Int) {
        val x = xNorm.coerceIn(0f, 1f)
        val y = yNorm.coerceIn(0f, 1f)

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

        val w = shellWidthPx.coerceAtLeast(1)
        val h = shellHeightPx.coerceAtLeast(1)
        val ok = try {
            routeToPane(x, y, action, w, h)
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "CarPlay touch inject failed", e)
            false
        }
        if (!ok) {
            Log.w(CarPlayConsts.TAG, "CarPlay touch dropped action=$action x=$x y=$y")
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
                val heldMs = SystemClock.uptimeMillis() - chromeDownAt
                val dist = abs(x - chromeDownX)
                if (!chromeDragging) {
                    // Hold still during the long-press window so jitter does not start a drag.
                    if (heldMs < SplitPane.DIVIDER_TAP_STACK_MIN_MS && dist <= DRAG_SLOP) {
                        return true
                    }
                    if (dist > DRAG_SLOP) {
                        chromeDragging = true
                    }
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
                chromeArmed = false
                val heldMs = SystemClock.uptimeMillis() - chromeDownAt
                val dist = abs(x - chromeDownX)
                val ratioDelta = abs(x - ratioAtDown)
                val wasDragging = chromeDragging
                val downOnGrip = chromeDownOnGrip
                val longPress = seamLongPressFired
                chromeDragging = false
                chromeDownOnGrip = false
                seamLongPressFired = false
                peelPreviewActive = false

                if (inFullscreen) {
                    handlePeelUp(x, heldMs, dist, wasDragging, action == MotionEvent.ACTION_CANCEL)
                } else {
                    handleDividerUp(
                        x, heldMs, dist, ratioDelta, wasDragging, downOnGrip,
                        action == MotionEvent.ACTION_CANCEL,
                        longPressFired = longPress,
                    )
                }
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

    /** Cancel the in-flight pane gesture at the last DOWN point. */
    private fun cancelPanePointer() {
        val w = shellWidthPx.coerceAtLeast(1)
        val h = shellHeightPx.coerceAtLeast(1)
        try {
            routeToPane(paneDownX, paneDownY, MotionEvent.ACTION_CANCEL, w, h)
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "cancel pane after seam steal failed", e)
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
        // Drag first (incl. peel-out-then-back): never open Recents. Final dist can be
        // tiny after returning to the edge while heldMs already ≥ long-press — same false
        // trigger SplitLockedPeelController already guards against.
        if (wasDragging) {
            if (x > SplitPane.FULLSCREEN_EXIT_RATIO) {
                exitFullscreen(x.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO))
            } else {
                // Below exit threshold — same as AA onPeelCancelled: stay FS, restore ratio.
                primaryRatio = ratioAtDown
            }
            return
        }
        if (SplitPane.qualifiesDividerLongPress(false, heldMs) && dist <= DRAG_SLOP * 3f) {
            primaryRatio = ratioAtDown
            CarPlayRecentsOverlay.open()
            return
        }
        if (heldMs <= SplitPane.DIVIDER_TAP_SWAP_MAX_MS && dist <= DRAG_SLOP) {
            // Tap peel → flip visible fullscreen pane (AA peel tap).
            primaryRatio = ratioAtDown
            val other = if (fullscreenPane == SplitPane.PRIMARY) {
                SplitPane.SECONDARY
            } else {
                SplitPane.PRIMARY
            }
            enterFullscreen(other)
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

    /**
     * Splits the full CarPlay image at [primaryRatio]: left half → PRIMARY pane VD,
     * right half → SECONDARY pane VD, each in its own display pixel space.
     * Short tap on an empty pane opens the app picker (AA empty-pane parity).
     * HU digitizers often omit MOVE and only jump DOWN→UP — displacement is
     * checked on UP so a drag is not mistaken for a tap.
     */
    private fun routeToPane(xNorm: Float, yNorm: Float, action: Int, w: Int, h: Int): Boolean {
        val fs = fullscreenPane
        val (pane, xPane, paneWidth) = if (SplitPane.isFullscreenPane(fs)) {
            Triple(fs, xNorm.coerceIn(0f, 1f), w)
        } else {
            val ratio = primaryRatio.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
            val x = xNorm.coerceIn(0f, 1f)
            if (x < ratio) {
                Triple(SplitPane.PRIMARY, x / ratio, (w * ratio).toInt().coerceAtLeast(1))
            } else {
                val share = (1f - ratio).coerceAtLeast(0.01f)
                Triple(
                    SplitPane.SECONDARY,
                    (x - ratio) / share,
                    (w - w * ratio).toInt().coerceAtLeast(1),
                )
            }
        }
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                paneDownX = xNorm
                paneDownY = yNorm
                paneDownAt = SystemClock.uptimeMillis()
                paneDownPane = pane
                paneMoved = false
                // handleChrome may have armed seamPending just before this DOWN.
                paneEmptyPickerSuppressed = seamPending
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(xNorm - paneDownX) > DRAG_SLOP || abs(yNorm - paneDownY) > DRAG_SLOP) {
                    paneMoved = true
                }
            }
            MotionEvent.ACTION_UP -> {
                // Cars often skip MOVE; treat DOWN→UP jump as a drag.
                if (abs(xNorm - paneDownX) > DRAG_SLOP || abs(yNorm - paneDownY) > DRAG_SLOP) {
                    paneMoved = true
                }
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
                        CarPlayAppPickerOverlay.show(paneDownPane)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                paneMoved = true
                paneEmptyPickerSuppressed = true
            }
            else -> Unit
        }
        val px = (xPane * (paneWidth - 1)).toFloat()
        val py = (yNorm.coerceIn(0f, 1f) * (h - 1)).toFloat()
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, px, py, 0)
        try {
            val ok = try {
                CoreApi.touchPane(pane, event)
                true
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "touchPane($pane) from CarPlay failed", e)
                false
            }
            if (action != MotionEvent.ACTION_MOVE && !ok) {
                Log.w(CarPlayConsts.TAG, "CarPlay pane touch dropped pane=$pane action=$action x=$px y=$py")
            }
            return ok
        } finally {
            event.recycle()
        }
    }
}
