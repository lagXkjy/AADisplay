package io.github.nitsuya.aa.display.ui.aa.split

import android.app.ActivityTaskManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.getObjectAs
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import io.github.nitsuya.aa.display.xposed.hook.VdDensityPin
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import io.github.nitsuya.aa.display.xposed.util.Instances
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class SplitOwnership(private val c: SplitDisplayController) {

    internal data class PaneTaskRef(val taskId: Int, val packageName: String?)

    companion object {
        /** ActivityTaskManager.RESIZE_MODE_SYSTEM */
        private const val RESIZE_MODE_SYSTEM = 0
        /** ActivityTaskManager.RESIZE_MODE_SYSTEM | RESIZE_MODE_FORCED */
        private const val RESIZE_MODE_SYSTEM_FORCED = 2
        /**
         * Name heuristics for transient splash/welcome activities left after a bad VD resize.
         * Do **not** include markers that are also permanent MAIN/LAUNCHER hosts
         * (QQ Music Car = AppStarterActivity, 网易云 IoT = LoadingActivity) — those are
         * filtered out via [isPackageLauncherComponent] instead of being listed here.
         */
        private val STALE_FRONT_ACTIVITY_MARKERS = listOf(
            "Splash",
            "Welcome",
            "SplashActivity",
            "WelcomeActivity",
        )
    }
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

    /** Like [runOnHandlerBlocking] but jumps ahead of debounced settle / ensure on the queue. */
    fun <T> runOnHandlerBlockingAtFront(default: T, block: () -> T): T {
        if (Looper.myLooper() == c.mHandler.looper) return block()
        val box = arrayOfNulls<Any?>(1)
        val latch = CountDownLatch(1)
        val posted = c.mHandler.postAtFrontOfQueue {
            try {
                box[0] = block()
            } catch (e: Throwable) {
                log(SplitDisplayController.TAG, "runOnHandlerBlockingAtFront failed:", e)
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return default
        return try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                log(SplitDisplayController.TAG, "runOnHandlerBlockingAtFront timeout")
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
        VdDensityPin.markPackageOnVirtualDisplay(pkg, displayId)
        trackPackage(pkg, 0)
    }

    /** Forget VD ownership when [packageName] is no longer in either pane stack. */
    fun releaseOwnershipIfUnused(packageName: String?) {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (c.stacks.containsAnywhere(pkg)) return
        c.mVdPackages.remove(pkg)
        VdDensityPin.clearPackageVirtualDisplay(pkg)
    }

    fun forgetOwnership(taskId: Int, packageName: String?) {
        packageName?.let { c.mVdPackages.remove(it) }
    }

    fun reclaimOwnedPackages(reason: String) {
        if (c.mIsDestroying) return
        if (SystemClock.uptimeMillis() < c.mSuppressReclaimUntil) return
        if (c.mVdPackages.isEmpty()) return
        for (pkg in c.mVdPackages.toList()) {
            if (SplitChromePackages.BOUNCE_EXCLUDED.contains(pkg)) {
                continue
            }
            val onPrimary = hasPackageOnDisplay(pkg, c.primaryDisplayId)
            val onSecondary = hasPackageOnDisplay(pkg, c.secondaryDisplayId)
            if (onPrimary || onSecondary) {
                continue
            }
            val phoneTask = findPackageTaskOnDisplay(pkg, Display.DEFAULT_DISPLAY)
            if (phoneTask == null) {
                continue
            }
            val targetPane = c.stacks.paneContaining(pkg)
            if (targetPane == null) {
                // Package is owned but no longer assigned to a pane — drop it.
                releaseOwnershipIfUnused(pkg)
                continue
            }
            val targetDisplay = c.input.displayIdFor(targetPane)
            if (targetDisplay == null) {
                continue
            }
            logDebug(SplitDisplayController.TAG, "reclaim[$reason]: $pkg#$phoneTask -> display=$targetDisplay")
            try {
                Instances.iActivityTaskManager.moveRootTaskToDisplay(phoneTask, targetDisplay)
                VdDensityPin.markPackageOnVirtualDisplay(pkg, targetDisplay)
                // moveRootTaskToDisplay lands on top — if this pkg is only a buried stack
                // member (e.g. Douyin under 汽水), demote it and restore the intentional front
                // or it steals audio focus while the UI still shows the old front.
                val stackFront = c.stacks.front(targetPane)
                if (!stackFront.isNullOrBlank() && stackFront != pkg) {
                    moveTaskToBackQuiet(phoneTask)
                    promoteStackFronts(listOf(targetPane))
                }
            } catch (e: Throwable) {
                log(SplitDisplayController.TAG, "reclaim move failed:", e)
            }
        }
    }

    fun hasPackageOnDisplay(packageName: String, displayId: Int): Boolean {
        if (displayId == Display.INVALID_DISPLAY) return false
        return findPackageTaskOnDisplay(packageName, displayId) != null
    }

    /** True when [packageName] owns the visible front root on [displayId]. */
    fun isPackageFrontVisibleOnDisplay(packageName: String, displayId: Int): Boolean {
        if (displayId == Display.INVALID_DISPLAY) return false
        val front = frontRootTaskOnDisplay(displayId) ?: return false
        if (!isRootTaskVisible(front)) return false
        return front.topActivity?.packageName == packageName
    }

    /**
     * Soft reconnect: WM may keep a true splash/welcome on the VD while the Surface pipe is
     * stale. Permanent single-activity hosts (QQ Music Car [AppStarterActivity], etc.) must
     * not match — they look like splash names but are the live UI; cold-relaunching them on
     * every `surfaces-ready` blanks the pane (white screen).
     */
    fun isPackageFrontStaleOnReconnect(packageName: String, displayId: Int): Boolean {
        if (displayId == Display.INVALID_DISPLAY) return false
        val front = frontRootTaskOnDisplay(displayId) ?: return false
        val top = front.topActivity ?: return false
        if (top.packageName != packageName) return false
        val simple = top.className.substringAfterLast('.')
        if (simple.isEmpty()) return false
        if (!STALE_FRONT_ACTIVITY_MARKERS.any { simple.contains(it, ignoreCase = true) }) {
            return false
        }
        // LAUNCHER component is the app's real entry UI — never treat as transient splash.
        if (isPackageLauncherComponent(packageName, top)) return false
        return true
    }

    private fun isPackageLauncherComponent(packageName: String, activity: ComponentName): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName)
            val ri = c.context.packageManager.resolveActivity(intent, 0) ?: return false
            ri.activityInfo?.let { info ->
                info.packageName == activity.packageName && info.name == activity.className
            } == true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * @return true when [ensurePanePackages] should skip relaunching [packageName] on [displayId].
     *
     * Buried-but-alive stack members must be skipped: bringing them to front (even briefly)
     * steals audio focus (Douyin under 汽水) while a later re-promote restores only the picture.
     */
    fun shouldSkipRelaunchOnDisplay(
        packageName: String,
        displayId: Int,
        reason: String,
        isStackFront: Boolean,
    ): Boolean {
        if (!hasPackageOnDisplay(packageName, displayId)) return false
        // On this VD and not the intentional stack front → leave buried (do not bring).
        if (!isStackFront) return true
        // Soft reconnect: splash/launcher still showing as front → force cold relaunch.
        if (SplitPane.isSoftReconnectReason(reason) &&
            isPackageFrontStaleOnReconnect(packageName, displayId)
        ) {
            return false
        }
        // Healthy visible front — nothing to do.
        if (isPackageFrontVisibleOnDisplay(packageName, displayId)) return true
        // Present but not visible top (order drift) → allow bring/relaunch.
        return false
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
        return findPackageTaskInRoots(packageName, tasks, liveOnly)
    }

    fun findPackageTaskInRoots(
        packageName: String,
        roots: List<ActivityTaskManager.RootTaskInfo>,
        liveOnly: Boolean = true,
    ): Int? {
        return roots.firstOrNull { info ->
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
        val toPromote = mutableListOf<Int>()
        for (p in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
            if (p == keepPane) continue
            if (!c.stacks.contains(p, packageName) && c.mPanePackages[p] != packageName) continue
            c.stacks.remove(p, packageName)
            c.mPanePackages[p] = c.stacks.front(p)
            // Only strip chrome when the other pane's stack became empty.
            if (c.stacks.front(p) == null) {
                c.input.displayIdFor(p)?.let { removeChromeTasksOnDisplay(it) }
            } else {
                // Bring the new bookkeeping front so picture/audio leave the vacated pkg.
                toPromote += p
            }
        }
        if (toPromote.isNotEmpty()) {
            promoteStackFronts(toPromote)
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

    fun bringTaskToFront(
        taskId: Int,
        /**
         * Optional ATMS roots already fetched for one or more displays (displayId → raw list).
         * Skips repeated [getAllRootTaskInfosOnDisplay] in topmost verification.
         */
        cachedRootsByDisplay: Map<Int, List<ActivityTaskManager.RootTaskInfo>>? = null,
    ): Boolean {
        return try {
            // Samsung VDs often ignore bare moveTaskToFront unless the root task is focused.
            runCatching {
                val atm = Instances.iActivityTaskManager
                val method = atm.javaClass.methods.firstOrNull { m ->
                    m.name == "setFocusedTask" &&
                        m.parameterTypes.size == 1 &&
                        m.parameterTypes[0] == Int::class.javaPrimitiveType
                }
                method?.invoke(atm, taskId)
            }
            Instances.activityManager.moveTaskToFront(taskId, 0)
            if (isTaskTopmostOnItsDisplay(taskId, cachedRootsByDisplay)) return true
            // Prefer the task's live topActivity: 高德 LAUNCHER is UsbFillActivity, but the
            // VD root is often MainMapActivity — MAIN/LAUNCHER REORDER misses that task.
            if (reorderExistingTask(taskId, preferTopActivity = true) &&
                isTaskTopmostOnItsDisplay(taskId, cachedRootsByDisplay)
            ) {
                return true
            }
            if (reorderExistingTask(taskId, preferTopActivity = false) &&
                isTaskTopmostOnItsDisplay(taskId, cachedRootsByDisplay)
            ) {
                return true
            }
            logDebug(
                SplitDisplayController.TAG,
                "bringTaskToFront still not top task=$taskId"
            )
            false
        } catch (e: Throwable) {
            log(SplitDisplayController.TAG, "moveTaskToFront error:", e)
            false
        }
    }

    /** True when [taskId] is the topmost root on whatever display currently hosts it. */
    private fun isTaskTopmostOnItsDisplay(
        taskId: Int,
        cachedRootsByDisplay: Map<Int, List<ActivityTaskManager.RootTaskInfo>>? = null,
    ): Boolean {
        for (displayId in listOf(c.primaryDisplayId, c.secondaryDisplayId, Display.DEFAULT_DISPLAY)) {
            if (displayId == Display.INVALID_DISPLAY) continue
            val tasks = cachedRootsByDisplay?.get(displayId)
                ?: tryOrNull {
                    Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
                }.orEmpty()
            if (tasks.none { it.taskId == taskId }) continue
            // Prefer the visible root — list order alone is wrong on Samsung (top→bottom).
            val visible = tasks.firstOrNull { isRootTaskVisible(it) }
            if (visible != null) return visible.taskId == taskId
            return normalizeRootTasksBottomToTop(tasks).lastOrNull()?.taskId == taskId
        }
        return false
    }

    /** [ActivityTaskManager.RootTaskInfo.visible] (public field; reflect as fallback). */
    fun isRootTaskVisible(info: ActivityTaskManager.RootTaskInfo): Boolean {
        return runCatching { info.visible }.getOrElse {
            runCatching {
                (info as Any).getObjectAs("visible", Boolean::class.javaPrimitiveType) as? Boolean
            }.getOrNull() == true
        }
    }

    /**
     * Normalize ATMS root-task enumeration to **bottom → top** (last = front).
     *
     * AOSP usually walks bottom→top, but Samsung OneUI dumpsys / `getAllRootTaskInfosOnDisplay`
     * often returns **top → bottom**. Using [List.lastOrNull] then marks the buried task
     * (e.g. Google Maps) as front while 高德 is actually `visible=true`.
     */
    fun normalizeRootTasksBottomToTop(
        tasks: List<ActivityTaskManager.RootTaskInfo>,
    ): List<ActivityTaskManager.RootTaskInfo> {
        if (tasks.size <= 1) return tasks
        val firstVis = isRootTaskVisible(tasks.first())
        val lastVis = isRootTaskVisible(tasks.last())
        return when {
            firstVis && !lastVis -> tasks.asReversed()
            else -> tasks
        }
    }

    /** Visible front root on [displayId], else last after bottom→top normalize. */
    fun frontRootTaskOnDisplay(displayId: Int): ActivityTaskManager.RootTaskInfo? {
        if (displayId == Display.INVALID_DISPLAY) return null
        val tasks = tryOrNull {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        }.orEmpty()
        if (tasks.isEmpty()) return null
        tasks.firstOrNull { isRootTaskVisible(it) }?.let { return it }
        return normalizeRootTasksBottomToTop(tasks).lastOrNull()
    }

    /**
     * Phone panel is showing a real user app (Maps, browser, …), not Home / SystemUI chrome.
     * Used by pseudo screen-off: do not force-sleep over an active handset FG app.
     */
    fun phoneHasForegroundUserApp(): Boolean {
        return try {
            val front = frontRootTaskOnDisplay(Display.DEFAULT_DISPLAY) ?: return false
            if (c.input.isSystemHomeTask(front)) return false
            val pkg = front.topActivity?.packageName?.trim().orEmpty()
            if (pkg.isEmpty()) return false
            if (SplitChromePackages.BOUNCE_EXCLUDED.contains(pkg)) return false
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun findTopActivityForTask(taskId: Int): ComponentName? {
        for (displayId in listOf(c.primaryDisplayId, c.secondaryDisplayId, Display.DEFAULT_DISPLAY)) {
            if (displayId == Display.INVALID_DISPLAY) continue
            val tasks = tryOrNull {
                Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
            }.orEmpty()
            tasks.firstOrNull { it.taskId == taskId }?.topActivity?.let { return it }
        }
        return null
    }

    /**
     * Fallback when [ActivityManager.moveTaskToFront] no-ops on a VirtualDisplay:
     * re-deliver an intent without MULTIPLE_TASK so ATMS reorders the existing task
     * (same effect as `am start` → "current task has been brought to the front").
     *
     * @param preferTopActivity when true, target the task's current topActivity first
     * (needed when LAUNCHER ≠ the activity actually hosting the VD root).
     */
    private fun reorderExistingTask(taskId: Int, preferTopActivity: Boolean): Boolean {
        val packageName = findPackageForTask(taskId) ?: return false
        val displayId = findLivePackageTaskAnywhere(packageName)?.second ?: return false
        if (displayId == Display.INVALID_DISPLAY) return false
        val top = findTopActivityForTask(taskId)
        val launcher = c.launch.resolveLaunchComponent(packageName)
        val component = if (preferTopActivity) {
            // 高德: LAUNCHER is UsbFillActivity, live root is MainMapActivity — prefer top.
            top ?: launcher
        } else {
            launcher?.takeIf { it != top } ?: return false
        } ?: return false
        val targetingLauncher = launcher != null && component == launcher
        return try {
            VdDensityPin.markPackageOnVirtualDisplay(packageName, displayId)
            val ok = AaLaunchHelper.startActivityOnDisplay(
                context = c.context,
                component = component,
                userId = 0,
                displayId = displayId,
                mode = AaLaunchHelper.Mode.REORDER,
                addLauncherCategory = targetingLauncher,
            )
            if (ok) {
                logDebug(
                    SplitDisplayController.TAG,
                    "bringTaskToFront reorder pkg=$packageName cmp=${component.className} " +
                        "task=$taskId display=$displayId topPreferred=$preferTopActivity",
                )
            }
            ok
        } catch (e: Throwable) {
            log(SplitDisplayController.TAG, "bringTaskToFront reorder failed:", e)
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
     * Remove [packageName] from pane stacks when a task intentionally leaves a VD.
     * Promotes the new stack front when the removed package was on top.
     * @return panes that lost this package (stack may still be non-empty)
     */
    fun clearPanePackageForTask(taskId: Int, packageName: String?): List<Int> {
        if (!packageName.isNullOrBlank()) {
            val vacated = c.stacks.removeFromAll(packageName)
            if (vacated.isNotEmpty()) {
                promoteStackFronts(vacated)
                return vacated
            }
        }
        for (pane in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
            val displayId = c.input.displayIdFor(pane) ?: continue
            val onPane = tryOrNull {
                Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
            }.orEmpty().any { it.taskId == taskId }
            if (onPane) {
                if (!packageName.isNullOrBlank()) {
                    c.stacks.remove(pane, packageName)
                } else {
                    // Unknown package: clear front only if stack is empty after ATMS walk.
                    c.mPanePackages[pane] = c.stacks.front(pane)
                }
                promoteStackFronts(listOf(pane))
                return listOf(pane)
            }
        }
        return emptyList()
    }

    /**
     * Bring the current stack-front task to the foreground for each pane.
     * @param settleAv when false, skip cross-pane MediaSession arbitration (close / swap fast path).
     */
    fun promoteStackFronts(panes: Collection<Int>, settleAv: Boolean = true) {
        val rootsCache = HashMap<Int, List<ActivityTaskManager.RootTaskInfo>>(2)
        val fronts = ArrayList<String>(2)
        for (pane in panes) {
            val frontPkg = c.stacks.front(pane)
            c.mPanePackages[pane] = frontPkg
            if (frontPkg.isNullOrBlank()) {
                c.input.displayIdFor(pane)?.let { removeChromeTasksOnDisplay(it) }
                continue
            }
            val displayId = c.input.displayIdFor(pane) ?: continue
            val roots = rootsCache.getOrPut(displayId) {
                tryOrNull {
                    Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
                }.orEmpty()
            }
            val taskId = findPackageTaskInRoots(frontPkg, roots, liveOnly = true)
            if (taskId == null) {
                // Stack says front but no live root — clear chrome so a zombie/HOME shell
                // cannot linger as a "residual" picture.
                removeChromeTasksOnDisplay(displayId)
                continue
            }
            bringTaskToFront(taskId, cachedRootsByDisplay = rootsCache)
            // Push non-front stack mates off for picture; same-pane Av pause if front PLAYING.
            rootsCache.remove(displayId)
            enforceStackFrontAudio(pane, rootsCache = rootsCache)
            fronts += frontPkg
        }
        if (!settleAv) return
        // Cross-pane: restore / dual Av fronts must not both sound.
        c.buriedPlayback.enforceSingleSounder("promote")
        for (frontPkg in fronts) {
            c.buriedPlayback.resumeFrontPlaybackIfPausedByUs(frontPkg)
        }
        // Resume may have started a second player — re-assert single sounder.
        if (fronts.size > 1) {
            c.buriedPlayback.enforceSingleSounder("promote-after-resume")
        }
    }

    /**
     * Task demotion (picture) plus conditional MediaSession pause for buried mates.
     * Sticky AvMedia: [SplitBuriedPlayback] only pauses when another AvMedia on this
     * pane is actually PLAYING — maps/browser or idle Av front never steal focus.
     */
    fun enforceStackFrontAudio(
        pane: Int,
        rootsCache: MutableMap<Int, List<ActivityTaskManager.RootTaskInfo>>? = null,
    ) {
        demoteBuriedStackTasks(pane, rootsCache = rootsCache)
        c.buriedPlayback.pauseBuriedStackPlayback(pane)
    }

    /**
     * Move non-front stack packages on [pane]'s VD to the back so the intentional
     * front owns the picture. Does not itself decide AvMedia pause (see
     * [SplitBuriedPlayback] sticky rule). Keeps tasks alive for fast stack switch.
     */
    fun demoteBuriedStackTasks(
        pane: Int,
        rootsCache: MutableMap<Int, List<ActivityTaskManager.RootTaskInfo>>? = null,
    ) {
        if (!SplitPane.isValid(pane)) return
        val frontPkg = c.stacks.front(pane)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val displayId = c.input.displayIdFor(pane) ?: return
        if (displayId == Display.INVALID_DISPLAY) return
        val buried = c.stacks.packagesBottomToTop(pane)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != frontPkg }
            .toSet()
        if (buried.isEmpty()) return
        val roots = rootsCache?.getOrPut(displayId) {
            tryOrNull {
                Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
            }.orEmpty()
        } ?: tryOrNull {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        }.orEmpty()
        for (info in roots) {
            val pkg = info.topActivity?.packageName?.trim() ?: continue
            if (pkg !in buried) continue
            moveTaskToBackQuiet(info.taskId)
        }
    }

    private fun moveTaskToBackQuiet(taskId: Int) {
        try {
            // Not on our compile stub — resolve at runtime (system_server has it).
            val am = Instances.activityManager
            val method = am.javaClass.methods.firstOrNull { m ->
                m.name == "moveTaskToBack" &&
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (method == null) {
                logDebug(SplitDisplayController.TAG, "moveTaskToBack Method missing")
                return
            }
            method.invoke(am, taskId)
        } catch (e: Throwable) {
            logDebug(SplitDisplayController.TAG, "moveTaskToBack failed task=$taskId: ${e.message}")
        }
    }

    /**
     * Evict [packageName] from [pane]: remove its tasks on that display and drop stack ownership.
     */
    fun evictPackageFromPane(pane: Int, packageName: String) {
        val pkg = packageName.trim().takeIf { it.isNotEmpty() } ?: return
        val displayId = c.input.displayIdFor(pane) ?: return
        removePackageTasksOnDisplay(pkg, displayId)
        c.stacks.remove(pane, pkg)
        releaseOwnershipIfUnused(pkg)
        VdDensityPin.clearPackageVirtualDisplay(pkg)
        untrackPackage(pkg)
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

    /** Non-chrome user root tasks on [displayId], bottom → top (last = visible front). */
    fun snapshotUserRootTasks(displayId: Int): List<PaneTaskRef> {
        if (displayId == Display.INVALID_DISPLAY) return emptyList()
        val tasks = normalizeRootTasksBottomToTop(
            tryOrNull {
                Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
            }.orEmpty()
        )
        return snapshotUserRootTasks(tasks)
    }

    /**
     * Same filter as [snapshotUserRootTasks] for an already bottom→top normalized list
     * (avoids a second [getAllRootTaskInfosOnDisplay] when the caller already has it).
     */
    fun snapshotUserRootTasks(
        tasksBottomToTop: List<ActivityTaskManager.RootTaskInfo>,
    ): List<PaneTaskRef> {
        return tasksBottomToTop.mapNotNull { info ->
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
                    VdDensityPin.markPackageOnVirtualDisplay(pkg, toDisplayId)
                }
            } catch (e: Throwable) {
                log(SplitDisplayController.TAG, "moveTaskStack failed task=${ref.taskId} -> $toDisplayId:", e)
            }
        }
        tasks.lastOrNull()?.let { bringTaskToFront(it.taskId) }
    }

    /**
     * After VD resize / cross-display move, OEM WM can leave Window frames at the old size
     * while ActivityRecord config already matches the VD (ADB: 386×480 on a 436 VD).
     * `resizeTask` is a no-op for fullscreen roots on OneUI; nudge the VirtualDisplay by 1px
     * so WM re-dispatches configuration, then re-front the stack top to force a layout pass.
     */
    fun ensureTasksFillDisplay(
        displayId: Int,
        width: Int,
        height: Int,
        reason: String,
        nudgeVd: Boolean = true,
    ) {
        if (displayId == Display.INVALID_DISPLAY || width <= 0 || height <= 0) return
        val pane = when (displayId) {
            c.primaryDisplayId -> SplitPane.PRIMARY
            c.secondaryDisplayId -> SplitPane.SECONDARY
            else -> return
        }
        val vd = if (pane == SplitPane.PRIMARY) c.mPrimary else c.mSecondary
        if (nudgeVd && vd != null) {
            try {
                val nudgeW = (width - 1).coerceAtLeast(1)
                vd.resize(nudgeW, height, c.mDensityDpi)
                vd.resize(width, height, c.mDensityDpi)
                if (pane == SplitPane.PRIMARY) {
                    c.mLastPrimaryW = width
                    c.mLastPrimaryH = height
                } else {
                    c.mLastSecondaryW = width
                    c.mLastSecondaryH = height
                }
            } catch (e: Throwable) {
                log(SplitDisplayController.TAG, "ensureTasksFillDisplay nudge failed display=$displayId:", e)
            }
        }
        // One ATMS walk: bring front then forced-resize the same snapshot (task ids stable).
        val userTasks = snapshotUserRootTasks(displayId)
        val front = userTasks.lastOrNull()
        front?.let { bringTaskToFront(it.taskId) }
        // Still try FORCED resize as a secondary path on OEMs that honor it for VD tasks.
        val bounds = Rect(0, 0, width, height)
        var forced = 0
        for (ref in userTasks) {
            if (resizeTaskToBounds(ref.taskId, bounds, RESIZE_MODE_SYSTEM_FORCED)) forced++
        }
        logDebug(
            SplitDisplayController.TAG,
            "ensureTasksFillDisplay[$reason] display=$displayId " +
                "${width}x$height nudged=${nudgeVd && vd != null} forced=$forced front=${front?.taskId}"
        )
    }

    private fun resizeTaskToBounds(
        taskId: Int,
        bounds: Rect,
        resizeMode: Int = RESIZE_MODE_SYSTEM,
    ): Boolean {
        if (bounds.isEmpty) return false
        val atm = Instances.iActivityTaskManager as Any
        try {
            atm.invokeMethod(
                "resizeTask",
                args(taskId, bounds, resizeMode),
                argTypes(Integer.TYPE, Rect::class.java, Integer.TYPE)
            )
            return true
        } catch (_: Throwable) {
        }
        if (resizeMode != RESIZE_MODE_SYSTEM) {
            try {
                atm.invokeMethod(
                    "resizeTask",
                    args(taskId, bounds, RESIZE_MODE_SYSTEM),
                    argTypes(Integer.TYPE, Rect::class.java, Integer.TYPE)
                )
                return true
            } catch (_: Throwable) {
            }
        }
        return try {
            atm.invokeMethod(
                "resizeTask",
                args(taskId, bounds),
                argTypes(Integer.TYPE, Rect::class.java)
            )
            true
        } catch (e: Throwable) {
            log(SplitDisplayController.TAG, "resizeTaskToBounds($taskId) failed:", e)
            false
        }
    }
}
