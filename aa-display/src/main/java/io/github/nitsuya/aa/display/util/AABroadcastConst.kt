package io.github.nitsuya.aa.display.util

interface AABroadcastConst {
    companion object {
        const val ACTION_STEERING_WHEEL_CONTROL = "aa.display.action.STEERING_WHEEL_CONTROL"
        const val ACTION_OPEN_SPLIT_PICKER = "aa.display.action.OPEN_SPLIT_PICKER"
        /** AA CarActivity resumed — cancel pending Auto Open retries in gearhead. */
        const val ACTION_AA_DISPLAY_SHOWN = "aa.display.action.AA_DISPLAY_SHOWN"
        /** gearhead → AA: content_bounds expanded to full HU; re-measure and recreate VDs. */
        const val ACTION_COOLWALK_FULL_BLEED = "aa.display.action.COOLWALK_FULL_BLEED"
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
         * system_server → AA: presentation may be OFF / ColorFade / surface pipe stale;
         * UI should re-report id, rebind pane surfaces, and nudge [requestDisplay].
         */
        const val ACTION_REQUEST_DISPLAY_RECOVERY = "aa.display.action.REQUEST_DISPLAY_RECOVERY"
        /**
         * system_server → AA: open Recent (locked-phone peel long-press cannot hit
         * [SplitDividerView] while keyguard occludes the presentation).
         */
        const val ACTION_SHOW_RECENT_TASK = "aa.display.action.SHOW_RECENT_TASK"
        /** system_server → AA: ATMS stack changed while Recents may be visible. */
        const val ACTION_RECENT_TASK_DIRTY = "aa.display.action.RECENT_TASK_DIRTY"
        /**
         * system_server → AA: apply split ratio on the **shell** first
         * ([AaMainFragment] layout + divider), then settle VD via [ICoreManager.setSplitRatio].
         * Used by phone BT keyboard Ctrl+arrows (do not call setSplitRatio alone).
         */
        const val ACTION_HID_APPLY_SPLIT_RATIO = "aa.display.action.HID_APPLY_SPLIT_RATIO"
        /**
         * system_server → AA: BT Ctrl+S / HID shortcut — run the same optimistic
         * [AaMainFragment.performSwapClick] path as divider tap (not swapPanes alone).
         */
        const val ACTION_SPLIT_SWAP = "aa.display.action.SPLIT_SWAP"
        /** system_server → AA: [SplitDisplayController.swapPanesOnHandler] failed; revert UI. */
        const val ACTION_SPLIT_SWAP_FAILED = "aa.display.action.SPLIT_SWAP_FAILED"
        /**
         * AA UI → gearhead :car (+ system_server): when true, Coolwalk left-rail steal
         * and phone BT mouse inject into AaDisplay presentation (app picker / Recents)
         * instead of pane VirtualDisplays.
         */
        const val ACTION_AA_UI_RAIL_CONSUME = "aa.display.action.AA_UI_RAIL_CONSUME"
        const val EXTRA_AA_UI_RAIL_CONSUME = "aa.display.extra.AA_UI_RAIL_CONSUME"
        /**
         * AA UI → system_server: measured shell layout for BT mouse hit-test
         * ([HidSplitLayout] must match [AaMainFragment] divider position).
         */
        const val ACTION_HID_SHELL_GEOMETRY = "aa.display.action.HID_SHELL_GEOMETRY"
        /** system_server → AA: phone BT mouse cursor on the shell (presentation coords). */
        const val ACTION_HID_CURSOR = "aa.display.action.HID_CURSOR"
        const val EXTRA_CURSOR_VISIBLE = "aa.display.extra.CURSOR_VISIBLE"
        const val EXTRA_CURSOR_X = "aa.display.extra.CURSOR_X"
        const val EXTRA_CURSOR_Y = "aa.display.extra.CURSOR_Y"
        const val EXTRA_SHELL_PARENT_W = "aa.display.extra.SHELL_PARENT_W"
        const val EXTRA_SHELL_PARENT_H = "aa.display.extra.SHELL_PARENT_H"
        const val EXTRA_SHELL_SIDEBYSIDE = "aa.display.extra.SHELL_SIDEBYSIDE"
        /**
         * system_server → AA: IME visibility on a pane VD.
         * [EXTRA_PANE] is -1 when hidden; PRIMARY/SECONDARY when showing.
         */
        const val ACTION_IME_VISIBILITY = "aa.display.action.IME_VISIBILITY"
    }
}
