package io.github.nitsuya.aa.display.ui.aa.split

/** Pane indices shared across AIDL / UI / controller. */
object SplitPane {
    const val PRIMARY = 0
    const val SECONDARY = 1

    const val MIN_RATIO = 0.2f
    const val MAX_RATIO = 0.8f
    const val DEFAULT_RATIO = 0.5f

    /** Divider thickness in logical pixels (applied in display pixels via density). */
    const val DIVIDER_DP = 8

    /**
     * Extra hit area on each side of the divider seam (visual stays [DIVIDER_DP]).
     * Applied as view size + negative margins so the divider view itself receives
     * touches over adjacent panes (sibling TextureViews would otherwise win and
     * open the app picker on long-press).
     */
    const val DIVIDER_TOUCH_EXPAND_DP = 24

    fun clampRatio(ratio: Float): Float = ratio.coerceIn(MIN_RATIO, MAX_RATIO)

    fun isValid(pane: Int): Boolean = pane == PRIMARY || pane == SECONDARY
}
