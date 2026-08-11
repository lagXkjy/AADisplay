package io.github.nitsuya.aa.display.util

interface AABroadcastConst {
    companion object {
        const val ACTION_SCREEN_CONTROL = "aa.display.action.SCREEN_CONTROL"
        const val ACTION_STEERING_WHEEL_CONTROL = "aa.display.action.STEERING_WHEEL_CONTROL"
        const val ACTION_OPEN_SPLIT_PICKER = "aa.display.action.OPEN_SPLIT_PICKER"
        /** AA CarActivity resumed — cancel pending Auto Open retries in gearhead. */
        const val ACTION_AA_DISPLAY_SHOWN = "aa.display.action.AA_DISPLAY_SHOWN"
        /** system_server → AA: pane occupancy / ratio changed (restore, launch, close). */
        const val ACTION_SPLIT_STATE_CHANGED = "aa.display.action.SPLIT_STATE_CHANGED"
        const val EXTRA_ACTION = "aa.display.extra.ACTION"
        const val EXTRA_TYPE = "aa.display.extra.TYPE"
        const val EXTRA_PANE = "aa.display.extra.PANE"
        const val EXTRA_RATIO = "aa.display.extra.RATIO"
    }
}
