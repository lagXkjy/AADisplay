package io.github.nitsuya.aa.display.ui.aa.split

/** Live shell geometry reported from [AaMainFragment] (matches divider layout). */
data class HidShellGeometry(
    val parentW: Int,
    val parentH: Int,
    val sideBySide: Boolean,
    val fullscreenPane: Int,
    /** 「收起键盘」in parent coords; empty when hidden. */
    val imeChipL: Int = 0,
    val imeChipT: Int = 0,
    val imeChipR: Int = 0,
    val imeChipB: Int = 0,
) {
    fun hasImeChip(): Boolean = imeChipR > imeChipL && imeChipB > imeChipT
}

/**
 * Dual-pane geometry for phone BT mouse: one continuous canvas
 * `[primary][divider][secondary]` so the cursor can rest on the shell seam
 * instead of teleporting into the sibling VD.
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
    /** HU content size (AaDisplay presentation). */
    val totalW: Int,
    val totalH: Int,
    val densityDpi: Int,
    /** Seam gap so primary + gap + secondary == total; 0 → derive from [densityDpi]. */
    val shellGap: Int = 0,
    /**
     * AaDisplay presentation size when known. Divider / Recents injects scale from
     * [totalW]/[totalH] (VD profile) into this space when they differ.
     */
    val shellTotalW: Int = 0,
    val shellTotalH: Int = 0,
    /** 「收起键盘」in shell parent coords (same space as [shellTotalW]/[shellTotalH]). */
    val imeChipL: Int = 0,
    val imeChipT: Int = 0,
    val imeChipR: Int = 0,
    val imeChipB: Int = 0,
) {
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
            return (SplitPane.DIVIDER_DP * dpi / 160f).toInt().coerceAtLeast(4)
        }

    fun sizeOf(pane: Int): Pair<Int, Int>? = when (pane) {
        SplitPane.PRIMARY ->
            if (primaryW > 0 && primaryH > 0) primaryW to primaryH else null
        SplitPane.SECONDARY ->
            if (secondaryW > 0 && secondaryH > 0) secondaryW to secondaryH else null
        else -> null
    }

    fun displayIdOf(pane: Int): Int = when (pane) {
        SplitPane.PRIMARY -> primaryDisplayId
        SplitPane.SECONDARY -> secondaryDisplayId
        else -> android.view.Display.INVALID_DISPLAY
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
     * Three-dot grip on the split seam — BT mouse LMB chrome only (finger uses wide
     * [SplitDividerView] hit target).
     */
    fun isDividerGripHit(canvasX: Float, canvasY: Float): Boolean {
        if (!isSplit()) return false
        val (cx, cy) = clampCanvas(canvasX, canvasY)
        val dpi = densityDpi.coerceAtLeast(160)
        val dotRadius = SplitPane.DIVIDER_DOT_RADIUS_DP * dpi / 160f
        val dotGap = SplitPane.DIVIDER_DOT_GAP_DP * dpi / 160f
        val expand = SplitPane.DIVIDER_GRIP_HIT_EXPAND_DP * dpi / 160f
        val longHalf = dotGap + dotRadius + expand
        val div = seamGapPx()
        val shortHalf = (div / 2f + expand).coerceAtLeast(dotRadius + expand)
        return if (sideBySide) {
            val seamX = primaryW + div / 2f
            val seamY = totalH / 2f
            cx in (seamX - shortHalf)..(seamX + shortHalf) &&
                cy in (seamY - longHalf)..(seamY + longHalf)
        } else {
            val seamX = totalW / 2f
            val seamY = primaryH + div / 2f
            cx in (seamX - longHalf)..(seamX + longHalf) &&
                cy in (seamY - shortHalf)..(seamY + shortHalf)
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

    /** Map VD-profile canvas coords onto the AaDisplay presentation for [touchAaDisplay]. */
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

/** Result of mapping the HID cursor on the AaDisplay presentation canvas. */
sealed class HidCursorHit {
    /** Shell chrome (divider / peel) — inject via [touchAaDisplay]. */
    data class Divider(val x: Float, val y: Float) : HidCursorHit()

    /** App VirtualDisplay — inject into [pane] at local coords. */
    data class Pane(val pane: Int, val x: Float, val y: Float) : HidCursorHit()

    /** Full presentation (Recents / picker overlay) — inject via [touchAaDisplay]. */
    data class Shell(val x: Float, val y: Float) : HidCursorHit()
}
