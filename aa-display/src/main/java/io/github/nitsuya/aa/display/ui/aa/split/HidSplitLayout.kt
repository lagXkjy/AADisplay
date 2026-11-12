package io.github.nitsuya.aa.display.ui.aa.split

import android.graphics.Paint
import android.graphics.RectF

/**
 * Dual-pane geometry for phone BT mouse: one continuous canvas
 * `[primary][divider][secondary]` so the cursor can rest on the seam
 * instead of teleporting into the sibling VD.
 *
 * Built from live VirtualDisplay sizes in [SplitDisplayController] (Host GL path;
 * Car SDK shell geometry reporting is gone).
 */
data class HidSplitLayout(
    val sideBySide: Boolean,
    /** [SplitPane.FULLSCREEN_NONE] or PRIMARY / SECONDARY. */
    val fullscreenPane: Int,
    val focusedPane: Int,
    val primaryDisplayId: Int,
    val secondaryDisplayId: Int,
    val primaryW: Int,
    val primaryH: Int,
    val secondaryW: Int,
    val secondaryH: Int,
    /** HU / Host encode canvas size. */
    val totalW: Int,
    val totalH: Int,
    val densityDpi: Int,
    /** Seam gap so primary + gap + secondary == total; 0 → derive from [densityDpi]. */
    val shellGap: Int = 0,
    /**
     * Optional shell/parent size when it differs from the VD profile canvas.
     * Host GL keeps these equal to [totalW]/[totalH].
     */
    val shellTotalW: Int = 0,
    val shellTotalH: Int = 0,
    /** 「收起键盘」in shell parent coords; Host GL IME chip is hit-tested in-app. */
    val imeChipL: Int = 0,
    val imeChipT: Int = 0,
    val imeChipR: Int = 0,
    val imeChipB: Int = 0,
) {
    companion object {
        private val measureText = Paint(Paint.ANTI_ALIAS_FLAG)

        /**
         * Host GL 「收起键盘」geometry — same math for paint
         * ([io.github.nitsuya.aa.display.carplay.CarPlayChromePainter]) and phone BT
         * mouse hit-test ([imeChipL]..[imeChipB]). After the shell View was retired,
         * [SplitDisplayController.hidSplitLayout] stopped filling those fields and
         * [isImeChipHit] was always false (clicks fell through onto the keyboard).
         */
        fun layoutHideImeChip(
            out: RectF,
            canvasW: Int,
            canvasH: Int,
            densityDpi: Int,
            pane: Int,
            fullscreen: Boolean,
            ratio: Float,
            label: String,
        ): Boolean {
            if (!SplitPane.isValid(pane) || canvasW <= 0 || canvasH <= 0) return false
            val d = densityDpi.coerceAtLeast(160) / 160f
            val text = label.ifEmpty { "收起键盘" }
            val textW: Float
            val textH: Float
            synchronized(measureText) {
                measureText.textSize = 15f * d
                textW = measureText.measureText(text)
                val fm = measureText.fontMetrics
                textH = fm.descent - fm.ascent
            }
            val cw = textW + 40f * d
            val ch = textH + 20f * d
            val margin = 16f * d
            val paneLeft: Float
            val paneWidth: Float
            if (fullscreen) {
                paneLeft = 0f
                paneWidth = canvasW.toFloat()
            } else {
                val gap = SplitPane.dividerPx(densityDpi)
                val usable = (canvasW - gap).coerceAtLeast(2)
                val clamped = ratio.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
                val left = (usable * clamped).toInt().coerceAtLeast(1)
                val right = (usable - left).coerceAtLeast(1)
                if (pane == SplitPane.PRIMARY) {
                    paneLeft = 0f
                    paneWidth = left.toFloat()
                } else {
                    paneLeft = (left + gap).toFloat()
                    paneWidth = right.toFloat()
                }
            }
            val leftEdge = paneLeft + ((paneWidth - cw) / 2f).coerceAtLeast(0f)
            val top = (canvasH - ch - margin).coerceAtLeast(margin)
            out.set(leftEdge, top, leftEdge + cw, top + ch)
            return true
        }
    }

    private fun seamGapPx(): Float =
        if (shellGap > 0) shellGap.toFloat() else dividerVisualPx.toFloat()

    fun hasImeChip(): Boolean = imeChipR > imeChipL && imeChipB > imeChipT

    /** Map VD-profile canvas point into shell parent space for chip hit-test. */
    private fun canvasToShell(cx: Float, cy: Float): Pair<Float, Float> {
        val sw = (shellTotalW.takeIf { it > 0 } ?: totalW).toFloat().coerceAtLeast(1f)
        val sh = (shellTotalH.takeIf { it > 0 } ?: totalH).toFloat().coerceAtLeast(1f)
        return (cx * sw / totalW.coerceAtLeast(1)) to (cy * sh / totalH.coerceAtLeast(1))
    }

    fun isImeChipHit(canvasX: Float, canvasY: Float): Boolean {
        if (!hasImeChip()) return false
        val (sx, sy) = canvasToShell(canvasX, canvasY)
        return sx >= imeChipL && sx < imeChipR && sy >= imeChipT && sy < imeChipB
    }

    /** Visual seam + touch expand — used for peel edge sizing. */
    val dividerHitPx: Int
        get() {
            val dpi = densityDpi.coerceAtLeast(160)
            val dp = SplitPane.DIVIDER_DP + 2 * SplitPane.DIVIDER_TOUCH_EXPAND_DP
            return (dp * dpi / 160f).toInt().coerceAtLeast(24)
        }

    val dividerVisualPx: Int
        get() {
            val dpi = densityDpi.coerceAtLeast(160)
            return (SplitPane.DIVIDER_DP * dpi / 160f).toInt().coerceAtLeast(1)
        }

    fun sizeOf(pane: Int): Pair<Int, Int>? = when (pane) {
        SplitPane.PRIMARY ->
            if (primaryW > 0 && primaryH > 0) primaryW to primaryH else null
        SplitPane.SECONDARY ->
            if (secondaryW > 0 && secondaryH > 0) secondaryW to secondaryH else null
        else -> null
    }

    fun otherPane(pane: Int): Int =
        if (pane == SplitPane.PRIMARY) SplitPane.SECONDARY else SplitPane.PRIMARY

    /** Visible panes for mouse: one when fullscreen, both when split. */
    fun activePanes(): IntArray =
        if (SplitPane.isFullscreenPane(fullscreenPane) &&
            (fullscreenPane == SplitPane.PRIMARY || fullscreenPane == SplitPane.SECONDARY)
        ) {
            intArrayOf(fullscreenPane)
        } else {
            intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)
        }

    fun isSplit(): Boolean = activePanes().size >= 2

    /**
     * Three-dot grip on the split seam — BT mouse LMB chrome only (finger / HU uses
     * [CarPlayInputBridge] seam + grip gate).
     */
    fun isDividerGripHit(canvasX: Float, canvasY: Float): Boolean {
        if (!isSplit()) return false
        val (cx, cy) = clampCanvas(canvasX, canvasY)
        val dpi = densityDpi.coerceAtLeast(160)
        val expand = SplitPane.DIVIDER_GRIP_HIT_EXPAND_DP * dpi / 160f
        val div = seamGapPx()
        val shortHalf = (div / 2f + expand).coerceAtLeast(
            SplitPane.DIVIDER_DOT_RADIUS_DP * dpi / 160f + expand,
        )
        return if (sideBySide) {
            val seamX = primaryW + div / 2f
            cx in (seamX - shortHalf)..(seamX + shortHalf) &&
                SplitPane.isAlongDividerGrip(cy, totalH.toFloat(), dpi, finger = false)
        } else {
            val seamY = primaryH + div / 2f
            cy in (seamY - shortHalf)..(seamY + shortHalf) &&
                SplitPane.isAlongDividerGrip(cx, totalW.toFloat(), dpi, finger = false)
        }
    }

    /** Map presentation canvas coords into [pane] local space (clamped). */
    fun paneLocalOf(pane: Int, canvasX: Float, canvasY: Float): Pair<Float, Float> {
        val (cx, cy) = clampCanvas(canvasX, canvasY)
        val div = seamGapPx()
        return if (!isSplit()) {
            val size = sizeOf(pane) ?: return cx to cy
            cx.coerceIn(0f, size.first - 1f) to cy.coerceIn(0f, size.second - 1f)
        } else if (sideBySide) {
            when (pane) {
                SplitPane.PRIMARY ->
                    cx.coerceIn(0f, primaryW - 1f) to
                        cy.coerceIn(0f, (primaryH.coerceAtLeast(1) - 1).toFloat())
                else -> {
                    val localX = (cx - primaryW - div).coerceIn(0f, secondaryW - 1f)
                    localX to cy.coerceIn(0f, (secondaryH.coerceAtLeast(1) - 1).toFloat())
                }
            }
        } else {
            when (pane) {
                SplitPane.PRIMARY ->
                    cx.coerceIn(0f, (primaryW.coerceAtLeast(1) - 1).toFloat()) to
                        cy.coerceIn(0f, primaryH - 1f)
                else -> {
                    val localY = (cy - primaryH - div).coerceIn(0f, secondaryH - 1f)
                    cx.coerceIn(0f, (secondaryW.coerceAtLeast(1) - 1).toFloat()) to localY
                }
            }
        }
    }

    /** Map pane-local cursor into presentation canvas coords. */
    fun toCanvas(pane: Int, x: Float, y: Float): Pair<Float, Float> {
        val div = seamGapPx()
        val tw = totalW.toFloat().coerceAtLeast(1f)
        val th = totalH.toFloat().coerceAtLeast(1f)
        return if (!isSplit()) {
            x.coerceIn(0f, tw - 1f) to y.coerceIn(0f, th - 1f)
        } else if (sideBySide) {
            val cx = when (pane) {
                SplitPane.PRIMARY -> x.coerceIn(0f, primaryW.toFloat())
                else -> primaryW + div + x.coerceIn(0f, secondaryW.toFloat())
            }
            cx.coerceIn(0f, tw - 1f) to y.coerceIn(0f, th - 1f)
        } else {
            val cy = when (pane) {
                SplitPane.PRIMARY -> y.coerceIn(0f, primaryH.toFloat())
                else -> primaryH + div + y.coerceIn(0f, secondaryH.toFloat())
            }
            x.coerceIn(0f, tw - 1f) to cy.coerceIn(0f, th - 1f)
        }
    }

    fun clampCanvas(x: Float, y: Float): Pair<Float, Float> {
        val tw = totalW.toFloat().coerceAtLeast(1f)
        val th = totalH.toFloat().coerceAtLeast(1f)
        return x.coerceIn(0f, tw - 1f) to y.coerceIn(0f, th - 1f)
    }

    /** Map VD-profile canvas coords onto Host shell / encode space. */
    fun toShellTouch(canvasX: Float, canvasY: Float): Pair<Float, Float> {
        val (cx, cy) = clampCanvas(canvasX, canvasY)
        val sw = shellTotalW
        val sh = shellTotalH
        if (sw <= 0 || sh <= 0 || (sw == totalW && sh == totalH)) return cx to cy
        val tw = totalW.toFloat().coerceAtLeast(1f)
        val th = totalH.toFloat().coerceAtLeast(1f)
        return (cx / tw * sw).coerceIn(0f, sw - 1f) to (cy / th * sh).coerceIn(0f, sh - 1f)
    }

    /** Edge peel tab thickness (short axis) for fullscreen mouse hit. */
    val peelEdgeHitPx: Int
        get() {
            val dpi = densityDpi.coerceAtLeast(160)
            val min = (SplitPane.PEEL_EDGE_HIT_MIN_DP * dpi / 160f).toInt()
            return dividerHitPx.coerceAtLeast(min)
        }

    /** Peel tab length along the long axis (hit band). */
    val peelLongHitPx: Int
        get() {
            val dpi = densityDpi.coerceAtLeast(160)
            val dp = SplitPane.PEEL_TAB_LENGTH_DP + 2 * SplitPane.PEEL_TAB_HIT_EXPAND_DP
            return (dp * dpi / 160f).toInt().coerceAtLeast(1)
        }

    /** Fullscreen peel tab — same geometry as [SplitDividerView.applyPeelLayoutParams]. */
    fun isPeelHit(canvasX: Float, canvasY: Float): Boolean {
        if (isSplit()) return false
        val (cx, cy) = clampCanvas(canvasX, canvasY)
        val edge = peelEdgeHitPx.toFloat()
        val half = peelLongHitPx / 2f
        return if (sideBySide) {
            cx <= edge && cy in (totalH / 2f - half)..(totalH / 2f + half)
        } else {
            cy <= edge && cx in (totalW / 2f - half)..(totalW / 2f + half)
        }
    }

    /**
     * Hit-test presentation canvas → shell divider / peel or a pane (local coords).
     *
     * @param chromeInteract `false` (default): seam / peel bands map to the nearest pane so
     *   BT mouse hover does not hop VD near the divider. `true`: LMB chrome gesture — seam /
     *   peel → [HidCursorHit.Divider] for drag / tap-swap / long-press stack.
     */
    fun hit(canvasX: Float, canvasY: Float, chromeInteract: Boolean = false): HidCursorHit {
        val (cx, cy) = clampCanvas(canvasX, canvasY)
        // Soft-keyboard is painted inside the pane TextureView; the shell chip sits on top
        // visually but HU/HID still hit-test as pane unless we claim the chip rect for Shell.
        if (isImeChipHit(cx, cy)) {
            val (sx, sy) = canvasToShell(cx, cy)
            return HidCursorHit.Shell(sx, sy)
        }
        if (!isSplit()) {
            if (chromeInteract && isPeelHit(cx, cy)) return HidCursorHit.Divider(cx, cy)
            val pane = activePanes().firstOrNull() ?: SplitPane.PRIMARY
            val size = sizeOf(pane) ?: return HidCursorHit.Shell(cx, cy)
            return HidCursorHit.Pane(
                pane,
                cx.coerceIn(0f, size.first - 1f),
                cy.coerceIn(0f, size.second - 1f),
            )
        }
        if (chromeInteract && isDividerGripHit(cx, cy)) {
            return HidCursorHit.Divider(cx, cy)
        }
        val div = seamGapPx()
        return if (sideBySide) {
            if (cx < primaryW) {
                HidCursorHit.Pane(
                    SplitPane.PRIMARY,
                    cx.coerceIn(0f, primaryW - 1f),
                    cy.coerceIn(0f, (primaryH.coerceAtLeast(1) - 1).toFloat()),
                )
            } else {
                val localX = (cx - primaryW - div).coerceIn(0f, secondaryW - 1f)
                HidCursorHit.Pane(
                    SplitPane.SECONDARY,
                    localX,
                    cy.coerceIn(0f, (secondaryH.coerceAtLeast(1) - 1).toFloat()),
                )
            }
        } else {
            if (cy < primaryH) {
                HidCursorHit.Pane(
                    SplitPane.PRIMARY,
                    cx.coerceIn(0f, (primaryW.coerceAtLeast(1) - 1).toFloat()),
                    cy.coerceIn(0f, primaryH - 1f),
                )
            } else {
                val localY = (cy - primaryH - div).coerceIn(0f, secondaryH - 1f)
                HidCursorHit.Pane(
                    SplitPane.SECONDARY,
                    cx.coerceIn(0f, (secondaryW.coerceAtLeast(1) - 1).toFloat()),
                    localY,
                )
            }
        }
    }
}

/** Result of mapping the HID cursor on the Host / dual-VD canvas. */
sealed class HidCursorHit {
    /** Shell chrome (divider / peel) — handled by HID chrome / locked peel. */
    data class Divider(val x: Float, val y: Float) : HidCursorHit()

    /** App VirtualDisplay — inject into [pane] at local coords. */
    data class Pane(val pane: Int, val x: Float, val y: Float) : HidCursorHit()

    /** Full canvas chrome (Recents / picker / IME chip). */
    data class Shell(val x: Float, val y: Float) : HidCursorHit()
}
