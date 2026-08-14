package io.github.nitsuya.aa.display.ui.aa.split

import android.app.ActivityTaskManager
import android.content.ComponentName
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import com.github.kyuubiran.ezxhelper.utils.getObjectAs
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.Instances
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class SplitOwnership(private val c: SplitDisplayController) {

    internal val mDebouncedReclaim = Runnable { reclaimOwnedPackages("stack") }

    internal data class PaneTaskRef(val taskId: Int, val packageName: String?)

    /**
     * Marshal ownership-sensitive work onto [SplitDisplayController.mHandler] so Binder/IO callers
     * cannot race [SplitTaskStackListener] reclaim. Avoids snapping a swipe-off task back onto the VD.
     */
    fun <T> runOnHandlerBlocking(default: T, block: () -> T): T {
        if (Looper.myLooper() == c.mHandler.looper) return block()
        val box = arrayOfNulls<Any?>(1)
        val latch = CountDownLatch(1)
        val posted = c.mHandler.post {
            try {
                box[0] = block()
            } catch (e: Throwable) {
                log(SplitDisplayController.TAG, "runOnHandlerBlocking failed:", e)
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return default
        return try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                log(SplitDisplayController.TAG, "runOnHandlerBlocking timeout")
                default
            } else {
                @Suppress("UNCHECKED_CAST")
                (box[0] as? T) ?: default
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            default
        }
    }

    fun markOwnership(packageName: String?, displayId: Int) {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        c.mVdPackages.add(pkg)
        AndroidHook.VdDensityPin.markPackageOnVirtualDisplay(pkg, displayId)
        trackPackage(pkg, 0)
    }

    /** Forget VD ownership when [packageName] is no longer assigned to either pane. */
    fun releaseOwnershipIfUnused(packageName: String?) {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (c.mPanePackages[SplitPane.PRIMARY] == pkg || c.mPanePackages[SplitPane.SECONDARY] == pkg) {
            return
        }
        c.mVdPackages.remove(pkg)
        AndroidHook.VdDensityPin.clearPackageVirtualDisplay(pkg)
    }

    fun forgetOwnership(taskId: Int, packageName: String?) {
        c.mVdTaskIds.remove(taskId)
        packageName?.let { c.mVdPackages.remove(it) }
    }

    fun reclaimOwnedPackages(reason: String) {
        if (c.mIsDestroying) return
        if (SystemClock.uptimeMillis() < c.mSuppressReclaimUntil) return
        if (c.mVdPackages.isEmpty()) return
        for (pkg in c.mVdPackages.toList()) {
            if (SplitChromePackages.BOUNCE_EXCLUDED.contains(pkg)) continue
            val onPrimary = hasPackageOnDisplay(pkg, c.primaryDisplayId)
            val onSecondary = hasPackageOnDisplay(pkg, c.secondaryDisplayId)
            if (onPrimary || onSecondary) continue
            val phoneTask = findPackageTaskOnDisplay(pkg, Display.DEFAULT_DISPLAY) ?: continue
            val targetPane = when {
                c.mPanePackages[SplitPane.PRIMARY] == pkg -> SplitPane.PRIMARY
                c.mPanePackages[SplitPane.SECONDARY] == pkg -> SplitPane.SECONDARY
                else -> {
                    // Package is owned but no longer assigned to a pane — drop it.
                    releaseOwnershipIfUnused(pkg)
                    continue
                }
            }
            val targetDisplay = c.input.displayIdFor(targetPane) ?: continue
            log(SplitDisplayController.TAG, "reclaim[$reason]: $pkg#$phoneTask -> display=$targetDisplay")
            try {
                Instances.iActivityTaskManager.moveRootTaskToDisplay(phoneTask, targetDisplay)
                AndroidHook.VdDensityPin.markPackageOnVirtualDisplay(pkg, targetDisplay)
            } catch (e: Throwable) {
                log(SplitDisplayController.TAG, "reclaim move failed:", e)
            }
        }
    }

    fun hasPackageOnDisplay(packageName: String, displayId: Int): Boolean {
        if (displayId == Display.INVALID_DISPLAY) return false
        return findPackageTaskOnDisplay(packageName, displayId) != null
    }

    fun findPackageTaskOnDisplay(
        packageName: String,
        displayId: Int,
        liveOnly: Boolean = true,
    ): Int? {
        if (displayId == Display.INVALID_DISPLAY) return null
        val tasks = tryOrNull {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        }.orEmpty()
        return tasks.firstOrNull { info ->
            taskInfoMatchesPackage(info, packageName, requireTop = liveOnly)
        }?.taskId
    }

    /** Prefer AA VD tasks, then the phone default display. Live activities only. */
    fun findLivePackageTaskAnywhere(packageName: String): Pair<Int, Int>? {
        for (displayId in listOf(c.primaryDisplayId, c.secondaryDisplayId, Display.DEFAULT_DISPLAY)) {
            if (displayId == Display.INVALID_DISPLAY) continue
            val taskId = findPackageTaskOnDisplay(packageName, displayId, liveOnly = true) ?: continue
            return taskId to displayId
        }
        return null
    }

    /**
     * @param requireTop when true, only match tasks with a live topActivity (ignore empty
     * zombies that still expose baseActivity / affinity after close).
     */
    fun taskInfoMatchesPackage(
        info: ActivityTaskManager.RootTaskInfo,
        packageName: String,
        requireTop: Boolean = false,
    ): Boolean {
        if (info.topActivity?.packageName == packageName) return true
        if (requireTop) return false
        val base = runCatching {
            (info as Any).getObjectAs("baseActivity", ComponentName::class.java) as? ComponentName
        }.getOrNull()
        return base?.packageName == packageName
    }

    fun vacateOtherPanesHolding(packageName: String, keepPane: Int) {
        for (p in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
            if (p == keepPane) continue
            if (c.mPanePackages[p] != packageName) continue
            c.mPanePackages[p] = null
            c.input.displayIdFor(p)?.let { removeChromeTasksOnDisplay(it) }
        }
    }

    fun removePackageTasksEverywhere(packageName: String) {
        for (displayId in listOf(c.primaryDisplayId, c.secondaryDisplayId, Display.DEFAULT_DISPLAY)) {
            if (displayId == Display.INVALID_DISPLAY) continue
            removePackageTasksOnDisplay(packageName, displayId)
        }
    }

    fun removePackageTasksOnDisplay(packageName: String, displayId: Int) {
        if (displayId == Display.INVALID_DISPLAY) return
        val tasks = tryOrNull {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        }.orEmpty()
        // Match top or base so empty affinity zombies are removed before relaunch.
        tasks.filter { taskInfoMatchesPackage(it, packageName, requireTop = false) }.forEach { task ->
            tryOrNull { Instances.iActivityTaskManager.removeTask(task.taskId) }
        }
    }

    fun findPackageForTask(taskId: Int): String? {
        for (displayId in listOf(c.primaryDisplayId, c.secondaryDisplayId, Display.DEFAULT_DISPLAY)) {
            if (displayId == Display.INVALID_DISPLAY) continue
            val tasks = tryOrNull {
                Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
            }.orEmpty()
            tasks.firstOrNull { it.taskId == taskId }?.topActivity?.packageName?.let { return it }
        }
        return null
    }

    fun isTaskOnAaDisplay(taskId: Int): Boolean {
        for (displayId in listOf(c.primaryDisplayId, c.secondaryDisplayId)) {
            if (displayId == Display.INVALID_DISPLAY) continue
            val tasks = tryOrNull {
                Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
            }.orEmpty()
            if (tasks.any { it.taskId == taskId }) return true
        }
        return false
    }

    fun bringTaskToFront(taskId: Int): Boolean {
        return try {
            Instances.activityManager.moveTaskToFront(taskId, 0)
            true
        } catch (e: Throwable) {
            log(SplitDisplayController.TAG, "moveTaskToFront error:", e)
            false
        }
    }

    fun trackPackage(packageName: String?, userId: Int = 0) {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        c.mTrackedPackageUsers.getOrPut(pkg) { linkedSetOf() }.add(userId)
    }

    fun untrackPackage(packageName: String?) {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        c.mTrackedPackageUsers.remove(pkg)
    }

    fun trackPackageFromTask(taskInfo: Any) {
        val userId = runCatching {
            taskInfo.getObjectAs("userId", Int::class.javaPrimitiveType) as? Int
        }.getOrNull() ?: 0
        runCatching {
            taskInfo.getObjectAs("topActivity", ComponentName::class.java) as? ComponentName
        }.getOrNull()?.packageName?.let { trackPackage(it, userId) }
    }

    /**
     * Clear [SplitDisplayController.mPanePackages] for a task that is intentionally leaving a VD pane.
     * @return panes that were vacated
     */
    fun clearPanePackageForTask(taskId: Int, packageName: String?): List<Int> {
        val vacated = mutableListOf<Int>()
        if (!packageName.isNullOrBlank()) {
            for (i in 0..1) {
                if (c.mPanePackages[i] == packageName) {
                    c.mPanePackages[i] = null
                    vacated += i
                }
            }
        }
        if (vacated.isNotEmpty()) return vacated
        for (pane in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
            val displayId = c.input.displayIdFor(pane) ?: continue
            val onPane = tryOrNull {
                Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
            }.orEmpty().any { it.taskId == taskId }
            if (onPane) {
                c.mPanePackages[pane] = null
                return listOf(pane)
            }
        }
        return emptyList()
    }

    /** Remove launcher / systemui chrome left on an emptied VD (Samsung Secondary HOME, etc.). */
    fun removeChromeTasksOnDisplay(displayId: Int) {
        if (displayId == Display.INVALID_DISPLAY) return
        val tasks = tryOrNull {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        }.orEmpty()
        for (task in tasks) {
            val pkg = task.topActivity?.packageName
            val chrome = (!pkg.isNullOrBlank() && SplitChromePackages.BOUNCE_EXCLUDED.contains(pkg)) ||
                c.input.isSystemHomeTask(task)
            if (!chrome) continue
            tryOrNull { Instances.iActivityTaskManager.removeTask(task.taskId) }
        }
    }

    /** Non-chrome user root tasks on [displayId], bottom → top. */
    fun snapshotUserRootTasks(displayId: Int): List<PaneTaskRef> {
        if (displayId == Display.INVALID_DISPLAY) return emptyList()
        val tasks = tryOrNull {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        }.orEmpty()
        return tasks.mapNotNull { info ->
            if (c.input.isSystemHomeTask(info)) return@mapNotNull null
            val pkg = info.topActivity?.packageName
                ?: runCatching {
                    (info as Any).getObjectAs("baseActivity", ComponentName::class.java) as? ComponentName
                }.getOrNull()?.packageName
            if (!pkg.isNullOrBlank() && SplitChromePackages.BOUNCE_EXCLUDED.contains(pkg)) return@mapNotNull null
            // Empty chrome-less zombies without package are skipped.
            if (pkg.isNullOrBlank() && info.topActivity == null) return@mapNotNull null
            PaneTaskRef(info.taskId, pkg)
        }
    }

    fun moveTaskStack(tasks: List<PaneTaskRef>, toDisplayId: Int) {
        if (tasks.isEmpty() || toDisplayId == Display.INVALID_DISPLAY) return
        for (ref in tasks) {
            try {
                Instances.iActivityTaskManager.moveRootTaskToDisplay(ref.taskId, toDisplayId)
                ref.packageName?.let { pkg ->
                    AndroidHook.VdDensityPin.markPackageOnVirtualDisplay(pkg, toDisplayId)
                }
            } catch (e: Throwable) {
                log(SplitDisplayController.TAG, "moveTaskStack failed task=${ref.taskId} -> $toDisplayId:", e)
            }
        }
        tasks.lastOrNull()?.let { bringTaskToFront(it.taskId) }
    }
}
