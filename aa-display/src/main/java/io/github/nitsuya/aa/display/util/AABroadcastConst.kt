package io.github.nitsuya.aa.display.util

/**
 * Cross-process signals that still have live senders/receivers.
 * Car SDK shell / Coolwalk / gearhead bus constants were removed with that path
 * (see [docs/GEARHEAD_LEGACY.md]).
 */
interface AABroadcastConst {
    companion object {
        /**
         * system_server → app: IME visibility on a pane VD.
         * [EXTRA_PANE] is -1 when hidden; PRIMARY/SECONDARY when showing.
         * Received by [io.github.nitsuya.aa.display.carplay.CarPlayImeChip].
         */
        const val ACTION_IME_VISIBILITY = "aa.display.action.IME_VISIBILITY"
        const val EXTRA_PANE = "aa.display.extra.PANE"
    }
}
