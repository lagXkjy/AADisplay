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
     * Left peel inset from the screen edge so the handle sits outside Coolwalk's
     * LHD rail steal band (~80dp on many HUs), while staying driver-reachable.
     */
    const val FULLSCREEN_PEEL_INSET_DP = 80

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

    /**
     * Extra hit area on each side of the divider seam (visual stays [DIVIDER_DP]).
     * Applied as view size + negative margins so the divider view itself receives
     * touches over adjacent panes (sibling TextureViews would otherwise win and
     * open the app picker on long-press). Keep narrow to limit accidental drag/swap.
     */
    const val DIVIDER_TOUCH_EXPAND_DP = 12

    /**
     * Dead zone at each end of the divider along its long axis (top/bottom when
     * side-by-side). Keeps pane-corner chrome (back, etc.) from swapping splits.
     * Thickness of the hit target is unchanged ([DIVIDER_TOUCH_EXPAND_DP]).
     */
    const val DIVIDER_TOUCH_END_INSET_DP = 80

    fun clampRatio(ratio: Float): Float = ratio.coerceIn(MIN_RATIO, MAX_RATIO)

    fun isValid(pane: Int): Boolean = pane == PRIMARY || pane == SECONDARY

    fun isFullscreenPane(pane: Int): Boolean = pane == PRIMARY || pane == SECONDARY

    /** Which pane becomes fullscreen when a raw settle ratio crosses the edge. */
    fun fullscreenPaneForRawRatio(rawRatio: Float): Int? = when {
        rawRatio < FULLSCREEN_ENTER_RATIO -> SECONDARY
        rawRatio > 1f - FULLSCREEN_ENTER_RATIO -> PRIMARY
        else -> null
    }
}
