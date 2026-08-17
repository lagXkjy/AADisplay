package io.github.nitsuya.aa.display.util

interface AABroadcastConst {
    companion object {
        const val ACTION_STEERING_WHEEL_CONTROL = "aa.display.action.STEERING_WHEEL_CONTROL"
        const val ACTION_OPEN_SPLIT_PICKER = "aa.display.action.OPEN_SPLIT_PICKER"
        /** AA CarActivity resumed — cancel pending Auto Open retries in gearhead. */
        const val ACTION_AA_DISPLAY_SHOWN = "aa.display.action.AA_DISPLAY_SHOWN"
        /** system_server → AA: pane occupancy changed (restore, launch, close). */
        const val ACTION_SPLIT_STATE_CHANGED = "aa.display.action.SPLIT_STATE_CHANGED"
        const val EXTRA_ACTION = "aa.display.extra.ACTION"
        const val EXTRA_TYPE = "aa.display.extra.TYPE"
        const val EXTRA_PANE = "aa.display.extra.PANE"
        /**
         * When true with [ACTION_OPEN_SPLIT_PICKER], do not clear the empty overlay
         * (stack already has apps — user is adding another).
         */
        const val EXTRA_KEEP_OCCUPANCY = "aa.display.extra.KEEP_OCCUPANCY"
        /** Optional package names so AA can update empty overlays without Binder round-trips. */
        const val EXTRA_PRIMARY_PACKAGE = "aa.display.extra.PRIMARY_PACKAGE"
        const val EXTRA_SECONDARY_PACKAGE = "aa.display.extra.SECONDARY_PACKAGE"
        /** -1 split; 0 PRIMARY / 1 SECONDARY fullscreen. */
        const val EXTRA_FULLSCREEN_PANE = "aa.display.extra.FULLSCREEN_PANE"
        /** Primary pane ratio after controller change (swap / restore); UI applies when not dragging. */
        const val EXTRA_RATIO = "aa.display.extra.RATIO"
        /**
         * system_server → AA: peel inject failed to resolve AaDisplay presentation id;
         * UI should re-call [io.github.nitsuya.aa.display.xposed.ICoreManager.reportAaUiDisplayId].
         */
        const val ACTION_REQUEST_AA_UI_DISPLAY_ID = "aa.display.action.REQUEST_AA_UI_DISPLAY_ID"
        /**
         * system_server → AA: open Recent (locked-phone peel long-press cannot hit
         * [SplitDividerView] while keyguard occludes the presentation).
         */
        const val ACTION_SHOW_RECENT_TASK = "aa.display.action.SHOW_RECENT_TASK"
        /**
         * AA UI → gearhead :car: when true, Coolwalk left-rail steal injects into
         * AaDisplay presentation (app picker) instead of the primary pane VD.
         */
        const val ACTION_AA_UI_RAIL_CONSUME = "aa.display.action.AA_UI_RAIL_CONSUME"
        const val EXTRA_AA_UI_RAIL_CONSUME = "aa.display.extra.AA_UI_RAIL_CONSUME"
    }
}
