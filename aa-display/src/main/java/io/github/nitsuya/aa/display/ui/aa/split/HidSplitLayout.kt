package io.github.nitsuya.aa.display.ui.aa.split

/**
 * Dual-pane geometry for phone BT mouse: cursor can cross the divider like one canvas.
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
) {
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
}
