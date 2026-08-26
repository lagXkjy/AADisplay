package io.github.nitsuya.aa.display.ui.aa.split

import android.app.ActivityManager
import android.app.ITaskStackListener
import android.content.ComponentName
import android.os.SystemClock
import android.view.Display
import android.window.TaskSnapshot
import io.github.nitsuya.aa.display.xposed.hook.VdDensityPin

/**
 * Samsung AIDL deltas — empty overrides required; not StageCoordinator logic.
 */
internal class SplitTaskStackListener(
    private val c: SplitDisplayController,
) : ITaskStackListener.Stub() {

    override fun onTaskStackChanged() {
        if (c.mIsDestroying) return
        c.mHandler.removeCallbacks(c.ownership.mDebouncedReclaim)
        c.mHandler.postDelayed(c.ownership.mDebouncedReclaim, SplitDisplayController.RECLAIM_DEBOUNCE_MS)
        c.launch.scheduleStackSettle()
        c.launch.schedulePersistSnapshot()
        // Douyin LivePlay (and similar) may attach a foreign Presentation on the other pane.
        SplitPresentationGuard.scheduleEvictOnStackChanged(c)
    }

    override fun onActivityPinned(packageName: String?, userId: Int, taskId: Int, stackId: Int) {}
    override fun onActivityUnpinned() {}
    override fun onActivityRestartAttempt(
        task: ActivityManager.RunningTaskInfo?,
        homeTaskVisible: Boolean,
        clearedTask: Boolean,
        wasVisible: Boolean
    ) {
    }

    override fun onActivityForcedResizable(packageName: String?, taskId: Int, reason: Int) {}
    override fun onActivityDismissingDockedTask() {}
    override fun onActivityLaunchOnSecondaryDisplayFailed(
        taskInfo: ActivityManager.RunningTaskInfo?,
        requestedDisplayId: Int
    ) {
    }

    override fun onActivityLaunchOnSecondaryDisplayRerouted(
        taskInfo: ActivityManager.RunningTaskInfo?,
        requestedDisplayId: Int
    ) {
    }

    override fun onTaskCreated(taskId: Int, componentName: ComponentName?) {
        val pkg = componentName?.packageName ?: return
        c.ownership.trackPackage(pkg, 0)
        if (!SplitChromePackages.BOUNCE_EXCLUDED.contains(pkg)) {
            c.mHandler.post {
                if (c.ownership.isTaskOnAaDisplay(taskId)) {
                    c.mVdTaskIds.add(taskId)
                    c.mVdPackages.add(pkg)
                } else if (c.mVdPackages.contains(pkg)) {
                    c.mHandler.removeCallbacks(c.ownership.mDebouncedReclaim)
                    c.mHandler.postDelayed(c.ownership.mDebouncedReclaim, SplitDisplayController.RECLAIM_DEBOUNCE_MS)
                }
            }
        }
    }

    override fun onTaskRemoved(taskId: Int) {
        c.mVdTaskIds.remove(taskId)
        if (!c.mIsDestroying) {
            c.mHandler.removeCallbacks(c.ownership.mDebouncedReclaim)
            c.mHandler.postDelayed(c.ownership.mDebouncedReclaim, SplitDisplayController.RECLAIM_DEBOUNCE_MS)
            c.launch.scheduleStackSettle()
        }
    }

    override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
        val displayId = try {
            val field = taskInfo.javaClass.getField("displayId")
            field.getInt(taskInfo)
        } catch (_: Throwable) {
            return
        }
        val pane = c.paneForDisplayId(displayId) ?: return
        c.mFocusedPane = pane
        val pkg = taskInfo.topActivity?.packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val front = c.stacks.front(pane)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (pkg != front && c.stacks.contains(pane, pkg)) {
            if (SystemClock.uptimeMillis() < c.mSuppressReclaimUntil) return
            // Ratio resize / WM churn can briefly bring a buried mate forward — re-promote stack front.
            c.mHandler.post { c.ownership.promoteStackFronts(listOf(pane), settleAv = false) }
        }
    }

    override fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo?) {}
    override fun onActivityRequestedOrientationChanged(taskId: Int, requestedOrientation: Int) {}
    override fun onTaskRemovalStarted(taskInfo: ActivityManager.RunningTaskInfo?) {}
    override fun onTaskProfileLocked(taskInfo: ActivityManager.RunningTaskInfo?) {}
    override fun onTaskProfileLocked(taskInfo: ActivityManager.RunningTaskInfo?, userId: Int) {}
    override fun onTaskSnapshotChanged(taskId: Int, snapshot: TaskSnapshot?) {}
    override fun onBackPressedOnTaskRoot(taskInfo: ActivityManager.RunningTaskInfo?) {}
    override fun onTaskDisplayChanged(taskId: Int, newDisplayId: Int) {
        val pkg = c.ownership.findPackageForTask(taskId)
        VdDensityPin.onTaskDisplayChanged(pkg, newDisplayId)
        if (c.isAaVirtualDisplay(newDisplayId)) {
            c.mVdTaskIds.add(taskId)
            pkg?.let { c.mVdPackages.add(it) }
        } else if (newDisplayId == Display.DEFAULT_DISPLAY) {
            // During intentional swipe-off / close, suppress is armed and ownership is
            // already dropped on the controller thread — do not re-arm reclaim or the
            // task snaps straight back onto the VD (~200ms later).
            if (SystemClock.uptimeMillis() < c.mSuppressReclaimUntil) return
            c.mHandler.removeCallbacks(c.ownership.mDebouncedReclaim)
            c.mHandler.postDelayed(c.ownership.mDebouncedReclaim, SplitDisplayController.RECLAIM_DEBOUNCE_MS)
        }
    }

    override fun onRecentTaskListUpdated() {
        if (c.mIsDestroying) return
        c.launch.scheduleStackSettle()
    }
    override fun onRecentTaskRemovedForAddTask(taskId: Int) {}
    override fun onRecentTaskListFrozenChanged(frozen: Boolean) {}
    override fun onTaskFocusChanged(taskId: Int, focused: Boolean) {}
    override fun onTaskRequestedOrientationChanged(taskId: Int, requestedOrientation: Int) {}
    override fun onActivityRotation(displayId: Int) {}
    override fun onTaskMovedToBack(taskInfo: ActivityManager.RunningTaskInfo?) {}
    override fun onLockTaskModeChanged(mode: Int) {}
    override fun onTaskSnapshotInvalidated(taskId: Int) {}
    override fun onActivityDismissingSplitTask(str: String?) {}
    override fun onTaskWindowingModeChanged(taskId: Int) {}
    override fun onOccludeChangeNotice(componentName: ComponentName?, z: Boolean) {}
    override fun onTaskbarIconVisibleChangeRequest(componentName: ComponentName?, z: Boolean) {}
}
