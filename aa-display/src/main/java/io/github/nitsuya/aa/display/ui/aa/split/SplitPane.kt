package io.github.nitsuya.aa.display.ui.aa.split

/** Pane indices shared across AIDL / UI / controller. */
object SplitPane {
    const val PRIMARY = 0
    const val SECONDARY = 1

    /** Not in fullscreen; [getSplitFullscreenPane] / persist sentinel. */
    const val FULLSCREEN_NONE = -1

    const val MIN_RATIO = 0.2f
    const val MAX_RATIO = 0.8f
    const val DEFAULT_RATIO = 0.5f

    /**
     * Raw divider settle ratio at/below this → SECONDARY fullscreen;
     * at/above `1 - this` → PRIMARY fullscreen.
     * Slightly inside the old clamp edges so a firm drag past the usual 20/80 stop
     * enters fullscreen without needing to hit the absolute screen edge.
     */
    const val FULLSCREEN_ENTER_RATIO = 0.12f

    /**
     * Peel drag from the fixed left/top handle: when revealed primary share
     * exceeds this, exit fullscreen back to split.
     */
    const val FULLSCREEN_EXIT_RATIO = 0.15f

    /**
     * Peel inset from the outer screen edge. 0 = flush adsorb to the frame
     * (FacetBar is usually GONE; an 80dp inset left the short tab floating in content).
     * Coolwalk left-rail steal must route the peel hit-band into AaDisplay UI
     * ([ICoreManager.touchAaDisplay]) so the flush handle stays tappable.
     */
    const val FULLSCREEN_PEEL_INSET_DP = 0

    /**
     * Minimum peel hit strip along the short axis when flush to the edge, so Coolwalk
     * rail-band coordinates (typically ~80px) still land on [SplitDividerView] after
     * [ICoreManager.touchAaDisplay] inject. Visual tab stays [PEEL_TAB_THICKNESS_DP].
     */
    const val PEEL_EDGE_HIT_MIN_DP = 80

    /** Fullscreen peel visual: edge-docked tab length (long axis). */
    const val PEEL_TAB_LENGTH_DP = 56

    /** Fullscreen peel visual: tab thickness protruding inward from the edge. */
    const val PEEL_TAB_THICKNESS_DP = 8

    /**
     * Extra hit length beyond [PEEL_TAB_LENGTH_DP] on each end of the peel tab
     * (same idea as [DIVIDER_TOUCH_EXPAND_DP] for the seam).
     */
    const val PEEL_TAB_HIT_EXPAND_DP = 12

    /** Divider thickness in logical pixels (applied in display pixels via density). */
    const val DIVIDER_DP = 8

    /** Grip dots on [SplitDividerView] — shared with BT mouse chrome hit-test. */
    const val DIVIDER_DOT_RADIUS_DP = 2.5f
    const val DIVIDER_DOT_GAP_DP = 7f

    /** Extra padding around the three-dot grip for BT mouse LMB chrome. */
    const val DIVIDER_GRIP_HIT_EXPAND_DP = 5f

    /**
     * Extra hit area on each side of the divider seam (visual stays [DIVIDER_DP]).
     * Applied as view size + negative margins so the divider view itself receives
     * touches over adjacent panes (sibling TextureViews would otherwise win).
     * Keep narrow to limit accidental drag/swap.
     */
    const val DIVIDER_TOUCH_EXPAND_DP = 12

    /**
     * Dead zone at each end of the divider along its long axis (top/bottom when
     * side-by-side). Keeps pane-corner chrome (back, etc.) from swapping splits.
     * Thickness of the hit target is unchanged ([DIVIDER_TOUCH_EXPAND_DP]).
     */
    const val DIVIDER_TOUCH_END_INSET_DP = 80

    /** Divider tap-to-swap max press (must stay below [DIVIDER_TAP_STACK_MIN_MS]). */
    const val DIVIDER_TAP_SWAP_MAX_MS = 480L

    /** Divider long-press min hold before Recent opens on UP. */
    const val DIVIDER_TAP_STACK_MIN_MS = 550L

    /** Ratio delta below this with tiny finger jitter still counts as tap-swap. */
    const val DIVIDER_TAP_RATIO_SLOP = 0.025f

    /**
     * Below [DIVIDER_TAP_STACK_MIN_MS]: suppress swap when hold + micro jitter suggest
     * a long-press attempt rather than an intentional tap.
     */
    const val DIVIDER_TAP_SWAP_ATTEMPT_MS = 400L

    /**
     * Long-press for Recent on UP: Handler timeout **or** input hold duration.
     * Cold launch / busy main thread can delay [postDelayed]; input timestamps stay accurate.
     */
    fun qualifiesDividerLongPress(runnableFired: Boolean, heldMs: Long): Boolean =
        runnableFired || heldMs >= DIVIDER_TAP_STACK_MIN_MS

    /**
     * Do not enter ratio drag until the long-press window expires — cold-start finger
     * jitter stays in the long-press lane. Obvious drags ([dist] > 2× slop) still win early.
     */
    fun shouldBeginDividerDrag(
        dist: Float,
        touchSlop: Float,
        longPressFired: Boolean,
        heldMs: Long,
    ): Boolean {
        if (longPressFired) return false
        if (heldMs >= DIVIDER_TAP_STACK_MIN_MS) return false
        return dist > touchSlop * 2f
    }

    /** Long-press Recent on UP even when micro jitter set [wasDragging]. */
    fun qualifiesDividerRecentOnUp(
        wasLongPress: Boolean,
        wasDragging: Boolean,
        dist: Float,
        touchSlop: Float,
        ratioDelta: Float,
    ): Boolean {
        if (!wasLongPress) return false
        if (!wasDragging) return true
        return dist <= touchSlop * 3f && kotlin.math.abs(ratioDelta) <= DIVIDER_TAP_RATIO_SLOP
    }

    /** Tap-to-swap on UP — shared by [SplitDividerView] and [SplitLockedPeelController]. */
    fun shouldDividerSwapOnUp(
        wasDragging: Boolean,
        wasLongPress: Boolean,
        heldMs: Long,
        dist: Float,
        touchSlop: Float,
        ratioDelta: Float,
    ): Boolean {
        if (wasLongPress) return false
        if (heldMs >= DIVIDER_TAP_STACK_MIN_MS) return false
        val maxMs = if (wasDragging) DIVIDER_TAP_SWAP_MAX_MS + 80L else DIVIDER_TAP_SWAP_MAX_MS
        if (heldMs > maxMs) return false
        val absRatio = kotlin.math.abs(ratioDelta)
        if (heldMs >= DIVIDER_TAP_SWAP_ATTEMPT_MS &&
            dist <= touchSlop * 3f &&
            absRatio <= DIVIDER_TAP_RATIO_SLOP
        ) {
            return false
        }
        if (!wasDragging) return true
        if (absRatio <= DIVIDER_TAP_RATIO_SLOP) return true
        if (dist > touchSlop * 3f) return false
        return absRatio <= DIVIDER_TAP_RATIO_SLOP
    }

    fun clampRatio(ratio: Float): Float = ratio.coerceIn(MIN_RATIO, MAX_RATIO)

    /** Divider gap in px — shared by shell layout and VD pane sizing ([SplitVdLifecycle]). */
    fun dividerPx(densityDpi: Int): Int {
        val dpi = densityDpi.coerceAtLeast(160)
        return (DIVIDER_DP * dpi / 160f).toInt().coerceAtLeast(8)
    }

    fun isValid(pane: Int): Boolean = pane == PRIMARY || pane == SECONDARY

    /** Soft reconnect / surface-ready ensure reasons (shared by launch + ownership). */
    fun isSoftReconnectReason(reason: String): Boolean =
        reason == "reconnect" || reason == "reconnect-late" || reason == "surfaces-ready"

    fun isFullscreenPane(pane: Int): Boolean = pane == PRIMARY || pane == SECONDARY

    /** Which pane becomes fullscreen when a raw settle ratio crosses the edge. */
    fun fullscreenPaneForRawRatio(rawRatio: Float): Int? = when {
        rawRatio < FULLSCREEN_ENTER_RATIO -> SECONDARY
        rawRatio > 1f - FULLSCREEN_ENTER_RATIO -> PRIMARY
        else -> null
    }

    /**
     * Whether a Coolwalk rail-steal point lands on the flush peel tab hit band
     * (centered on the long axis). Shared by AA UI geometry and `:car` steal routing.
     * When [parentW]/[parentH] are unknown (≤0), returns true so steal still tries
     * [ICoreManager.touchAaDisplay] rather than falling through to a pane inject.
     */
    fun peelHitContains(x: Float, y: Float, parentW: Int, parentH: Int): Boolean {
        if (parentW <= 0 || parentH <= 0) return true
        val sideBySide = parentW >= parentH
        val hit = (
            PEEL_TAB_LENGTH_DP + 2f * PEEL_TAB_HIT_EXPAND_DP
            ).coerceAtMost(if (sideBySide) parentH.toFloat() else parentW.toFloat())
        val half = hit / 2f
        return if (sideBySide) {
            val cy = parentH / 2f
            y in (cy - half)..(cy + half)
        } else {
            val cx = parentW / 2f
            x in (cx - half)..(cx + half)
        }
    }
}
