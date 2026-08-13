package io.github.nitsuya.aa.display.ui.aa.split

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.model.RecentTask
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.logDebug
import io.github.nitsuya.aa.display.xposed.util.Instances
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Vendor-independent dual-[VirtualDisplay] split controller.
 * PRIMARY / SECONDARY panes are independent fullscreen displays; AA UI composites them.
 */
class SplitDisplayController(
    internal val context: Context,
    private val onReady: suspend SplitDisplayController.() -> Unit,
) {
    companion object {
        const val TAG = "AADisplay_SplitDisplayController"
        internal const val ACTIVITY_TYPE_HOME = 2
        internal const val SNAPSHOT_DEBOUNCE_MS = 2500L
        /** VD resize + freezeDisplayRotation is expensive; keep drag/reconnect from flooding. */
        internal const val RESIZE_THROTTLE_MS = 120L
        internal const val RECLAIM_DEBOUNCE_MS = 200L
        internal const val SUPPRESS_RECLAIM_MS = 2000L
        /** Longer suppress after restore so reclaim cannot overwrite panes during settle. */
        internal const val SUPPRESS_RECLAIM_AFTER_RESTORE_MS = 8000L
        internal const val ENSURE_PANES_DELAY_MS = 500L
        internal const val RESTORE_VERIFY_DELAY_MS = 2000L
        internal const val RESTORE_VERIFY_RETRY_MS = 2000L
        internal const val MAX_RESTORE_VERIFY_ATTEMPTS = 3
    }

    internal val vd = SplitVdLifecycle(this)
    internal val launch = SplitLaunchRestore(this)
    internal val ownership = SplitOwnership(this)
    internal val input = SplitInputRecents(this)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val mHandler = Handler(Looper.getMainLooper())

    var mWidth: Int = 0
        private set
    var mHeight: Int = 0
        private set
    var mDensityDpi: Int = 0
        private set
    var mRatio: Float = SplitPane.DEFAULT_RATIO
        internal set
    var mFocusedPane: Int = SplitPane.PRIMARY
        internal set
    /** Landscape → side-by-side; portrait → stacked. */
    val isSideBySide: Boolean
        get() = mWidth >= mHeight

    val primaryDisplayId: Int
        get() = mPrimary?.display?.displayId ?: Display.INVALID_DISPLAY
    val secondaryDisplayId: Int
        get() = mSecondary?.display?.displayId ?: Display.INVALID_DISPLAY

    internal var mPrimary: VirtualDisplay? = null
    internal var mSecondary: VirtualDisplay? = null
    internal var mPrimarySurface: Surface? = null
    internal var mSecondarySurface: Surface? = null
    internal var mIsDestroying = false

    internal val mPanePackages = arrayOfNulls<String>(2)
    internal val mTrackedPackageUsers = linkedMapOf<String, MutableSet<Int>>()
    internal val mVdTaskIds = mutableSetOf<Int>()
    internal val mVdPackages = mutableSetOf<String>()
    /**
     * Written from Binder/IO ([moveTaskId]/[removeTask]) and read on the main handler
     * ([SplitOwnership.reclaimOwnedPackages]). Must be volatile so intentional move-off is not raced by reclaim.
     */
    @Volatile
    internal var mSuppressReclaimUntil = 0L

    internal var mPrimaryWm: WindowManager? = null
    internal var mSecondaryWm: WindowManager? = null
    internal val mPrimaryForceView = View(context)
    internal val mSecondaryForceView = View(context)

    private val mTaskStackListener = SplitTaskStackListener(this)
    internal var mLastResizeAt = 0L
    /** Displays already freeze-locked to ROTATION_0; skip re-freeze on resize (OEM walks all DCs). */
    internal val mOrientationLockedDisplays = HashSet<Int>()

    internal var mLastPrimaryW = 0
    internal var mLastPrimaryH = 0
    internal var mLastSecondaryW = 0
    internal var mLastSecondaryH = 0
    internal val mPendingResize = Runnable { vd.resizePanesInternal("ratio-throttled") }

    init {
        scope.launch {
            onReady()
        }
    }

    fun isAaVirtualDisplay(displayId: Int): Boolean {
        if (displayId == Display.INVALID_DISPLAY) return false
        return displayId == primaryDisplayId || displayId == secondaryDisplayId
    }

    fun paneForDisplayId(displayId: Int): Int? = when (displayId) {
        primaryDisplayId -> SplitPane.PRIMARY
        secondaryDisplayId -> SplitPane.SECONDARY
        else -> null
    }

    @SuppressLint("WrongConstant")
    fun onConnected(
        width: Int,
        height: Int,
        densityDpi: Int,
        ratio: Float,
        primarySurface: Surface?,
        secondarySurface: Surface?,
        onCreated: (primaryDisplayId: Int) -> Unit,
    ) {
        mIsDestroying = false
        mWidth = width.coerceAtLeast(1)
        mHeight = height.coerceAtLeast(1)
        mDensityDpi = densityDpi.coerceAtLeast(1)
        mRatio = SplitPane.clampRatio(ratio)
        mPrimarySurface = primarySurface
        mSecondarySurface = secondarySurface
        mPanePackages[0] = null
        mPanePackages[1] = null
        mVdTaskIds.clear()
        mVdPackages.clear()
        mTrackedPackageUsers.clear()
        mSuppressReclaimUntil = 0L
        mHandler.removeCallbacks(ownership.mDebouncedReclaim)
        mHandler.removeCallbacks(launch.mDebouncedPersist)
        launch.mPersistFirstScheduledAt = 0L

        val sizes = vd.computePaneSizes()
        val flags = vd.vdFlags()
        val identity = Binder.clearCallingIdentity()
        try {
            mPrimary = Instances.displayManager.createVirtualDisplay(
                "AADisplay-P-${System.currentTimeMillis()}",
                sizes.primaryW, sizes.primaryH, mDensityDpi,
                primarySurface, flags
            )
            mSecondary = Instances.displayManager.createVirtualDisplay(
                "AADisplay-S-${System.currentTimeMillis()}",
                sizes.secondaryW, sizes.secondaryH, mDensityDpi,
                secondarySurface, flags
            )
        } finally {
            Binder.restoreCallingIdentity(identity)
        }

        log(
            TAG,
            "split VD created: primary=${primaryDisplayId} ${sizes.primaryW}x${sizes.primaryH}, " +
                "secondary=${secondaryDisplayId} ${sizes.secondaryW}x${sizes.secondaryH}, " +
                "ratio=$mRatio sideBySide=$isSideBySide"
        )

        try {
            Instances.iActivityTaskManager.registerTaskStackListener(mTaskStackListener)
        } catch (e: Throwable) {
            log(TAG, "registerTaskStackListener failed:", e)
        }

        // Notify AA first so TextureViews bind; policies/freeze are expensive on Samsung
        // (~600–900ms each freezeDisplayRotation) and must not delay first frame.
        onCreated(primaryDisplayId)

        mHandler.post {
            if (mIsDestroying) return@post
            vd.applyPolicies(SplitPane.PRIMARY, "connect")
            vd.applyPolicies(SplitPane.SECONDARY, "connect")
            vd.addKeepAwakeOverlay(SplitPane.PRIMARY)
            vd.addKeepAwakeOverlay(SplitPane.SECONDARY)
        }

        if (launch.shouldRestoreLastSplitOnConnect()) {
            mSuppressReclaimUntil =
                SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_AFTER_RESTORE_MS
            launch.scheduleRestoreLastSplit()
        } else {
            notifySplitStateChanged()
        }
    }

    fun onReconnected(width: Int, height: Int, densityDpi: Int) {
        mWidth = width.coerceAtLeast(1)
        mHeight = height.coerceAtLeast(1)
        mDensityDpi = densityDpi.coerceAtLeast(1)
        vd.resizePanesInternal("reconnect")
        vd.applyPolicies(SplitPane.PRIMARY, "reconnect")
        vd.applyPolicies(SplitPane.SECONDARY, "reconnect")
        launch.scheduleEnsurePanePackages("reconnect")
        SplitPresentationGuard.scheduleEvictForeignPresentations(this, "reconnect")
    }

    fun setPaneSurface(pane: Int, surface: Surface?) {
        if (!SplitPane.isValid(pane)) return
        if (pane == SplitPane.PRIMARY) {
            mPrimarySurface = surface
            mPrimary?.surface = surface
        } else {
            mSecondarySurface = surface
            mSecondary?.surface = surface
        }
        logDebug(TAG, "setPaneSurface pane=$pane surface=${surface != null}")
        // Soft reconnect / AA UI recreate often nulls then restores surfaces; re-ensure apps.
        if (mPrimarySurface != null && mSecondarySurface != null && !mIsDestroying) {
            launch.scheduleEnsurePanePackages("surfaces-ready")
        }
    }

    fun setSplitRatio(ratio: Float) {
        val clamped = SplitPane.clampRatio(ratio)
        if (abs(clamped - mRatio) < 0.001f) return
        mRatio = clamped
        // Resizing VDs makes tasks churn; suppress reclaim so bounce-back does not jitter.
        mSuppressReclaimUntil = SystemClock.uptimeMillis() + 800L
        val now = SystemClock.uptimeMillis()
        if (now - mLastResizeAt < RESIZE_THROTTLE_MS) {
            mHandler.removeCallbacks(mPendingResize)
            mHandler.postDelayed(mPendingResize, RESIZE_THROTTLE_MS)
            return
        }
        vd.resizePanesInternal("ratio")
        launch.schedulePersistSnapshot()
    }

    fun setFocusedPane(pane: Int) {
        if (!SplitPane.isValid(pane)) return
        mFocusedPane = pane
    }

    fun getPanePackage(pane: Int): String? {
        if (!SplitPane.isValid(pane)) return null
        // Prefer live ATMS top so AA empty overlays match reality after external closes.
        // During settle, keep bookkeeping through transient empty ATMS walks.
        // Outside settle: no user-app top (fully empty OR only SecondaryDisplayLauncher /
        // systemui chrome) → vacant. OWN_CONTENT_ONLY VDs often stay fully empty after
        // an app closes; previously that path kept stale mPanePackages and hid "Tap to choose".
        val displayId = input.displayIdFor(pane)
        if (displayId != null && displayId != Display.INVALID_DISPLAY) {
            val identity = Binder.clearCallingIdentity()
            try {
                val tasks = tryOrNull {
                    Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
                }.orEmpty()
                val topPkg = tasks.firstOrNull { info ->
                    val pkg = info.topActivity?.packageName
                    !pkg.isNullOrBlank() && !SplitChromePackages.BOUNCE_EXCLUDED.contains(pkg)
                }?.topActivity?.packageName
                if (!topPkg.isNullOrBlank()) {
                    if (mPanePackages[pane] != topPkg) mPanePackages[pane] = topPkg
                    return topPkg
                }
                val settling = SystemClock.uptimeMillis() < mSuppressReclaimUntil
                if (settling) {
                    return mPanePackages[pane]
                }
                if (mPanePackages[pane] != null) mPanePackages[pane] = null
                return null
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
        return mPanePackages[pane]
    }

    fun onDestroy() {
        try {
            launch.persistSnapshot(force = true, mirrorSettings = true)
        } catch (e: Throwable) {
            log(TAG, "onDestroy snapshot failed:", e)
        }
        mIsDestroying = true
        mOrientationLockedDisplays.clear()
        mHandler.removeCallbacks(ownership.mDebouncedReclaim)
        mHandler.removeCallbacks(launch.mDebouncedPersist)
        mHandler.removeCallbacks(mPendingResize)
        mHandler.removeCallbacks(launch.mDebouncedNotifyState)
        mHandler.removeCallbacksAndMessages(launch.RESTORE_TOKEN)
        mHandler.removeCallbacksAndMessages(launch.ENSURE_TOKEN)
        mHandler.removeCallbacksAndMessages(launch.VERIFY_RESTORE_TOKEN)
        tryOrNull { Instances.iActivityTaskManager.unregisterTaskStackListener(mTaskStackListener) }

        val protectedPackages = linkedSetOf(BuildConfig.APPLICATION_ID)
        for (displayId in listOf(primaryDisplayId, secondaryDisplayId)) {
            if (displayId == Display.INVALID_DISPLAY) continue
            tryOrNull {
                Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId).forEach { task ->
                    ownership.trackPackageFromTask(task)
                    tryOrNull { Instances.iActivityTaskManager.removeTask(task.taskId) }
                }
            }
        }
        mTrackedPackageUsers
            .filterKeys { it.isNotBlank() && !protectedPackages.contains(it) }
            .forEach { (pkg, userIds) ->
                userIds.ifEmpty { mutableSetOf(0) }.forEach { userId ->
                    try {
                        Instances.activityManagerHidden.forceStopPackageAsUser(pkg, userId)
                    } catch (e: Throwable) {
                        log(TAG, "forceStop failed: $pkg", e)
                    }
                }
            }
        mTrackedPackageUsers.clear()

        vd.removeKeepAwakeOverlay(SplitPane.PRIMARY)
        vd.removeKeepAwakeOverlay(SplitPane.SECONDARY)
        tryOrNull { mPrimary?.release() }
        tryOrNull { mSecondary?.release() }
        mPrimary = null
        mSecondary = null
        mPrimarySurface = null
        mSecondarySurface = null
        mPanePackages[0] = null
        mPanePackages[1] = null
        mVdTaskIds.clear()
        mVdPackages.clear()
        mDensityDpi = 0
        mWidth = 0
        mHeight = 0
    }

    fun onTouchPane(pane: Int, event: MotionEvent) {
        val displayId = input.displayIdFor(pane) ?: return
        input.injectInputEvent(displayId, event)
    }

    /**
     * Relay Coolwalk left-rail touches (HU x &lt; rail width) into the PRIMARY pane VD.
     */
    fun onTouchPrimaryPane(event: MotionEvent) {
        onTouchPane(SplitPane.PRIMARY, event)
    }

    fun onPressKey(action: Int) {
        val displayId = input.displayIdFor(mFocusedPane) ?: primaryDisplayId
        if (displayId == Display.INVALID_DISPLAY) return
        input.injectInputEvent(displayId, input.createKeyEvent(KeyEvent.ACTION_DOWN, action))
        input.injectInputEvent(displayId, input.createKeyEvent(KeyEvent.ACTION_UP, action))
    }

    fun getRecentTask(): RecentTask {
        return try {
            val primary = if (primaryDisplayId != Display.INVALID_DISPLAY) {
                input.recentTaskInfo(primaryDisplayId)
            } else {
                emptyList()
            }
            val secondary = if (secondaryDisplayId != Display.INVALID_DISPLAY) {
                input.recentTaskInfo(secondaryDisplayId)
            } else {
                emptyList()
            }
            RecentTask(input.recentTaskInfo(Display.DEFAULT_DISPLAY), primary, secondary)
        } catch (e: Throwable) {
            log(TAG, "RecentTask Exception", e)
            RecentTask(emptyList(), emptyList(), emptyList())
        }
    }

    fun startActivity(packageName: String, userId: Int): Boolean {
        return startActivityOnPane(packageName, userId, mFocusedPane)
    }

    fun startActivityOnPane(packageName: String, userId: Int, pane: Int): Boolean {
        if (!SplitPane.isValid(pane)) return false
        val displayId = input.displayIdFor(pane) ?: return false
        val component = launch.resolveLaunchComponent(packageName) ?: run {
            log(TAG, "startActivityOnPane: no launcher for $packageName")
            return false
        }
        // Replace existing task on this pane when launching a different package.
        val previous = mPanePackages[pane]
        if (!previous.isNullOrBlank() && previous != packageName) {
            ownership.removePackageTasksOnDisplay(previous, displayId)
            mPanePackages[pane] = null
            // Drop ownership so reclaim cannot pull the old app onto the other/focused pane.
            ownership.releaseOwnershipIfUnused(previous)
        }
        // If this package already has a *live* root task (phone / other pane), relocate it.
        // NEW_TASK + launchDisplayId alone often no-ops on Samsung and leaves the car pane
        // unchanged — looks like "picker click did nothing" (seen with alook.browser.dlna).
        // Empty/zombie tasks (Activities=[], sz=0 after force-stop/close) must not win:
        // bringTaskToFront on them is a no-op and blocks relaunch.
        val existing = ownership.findLivePackageTaskAnywhere(packageName)
        val ok = if (existing != null) {
            val (taskId, fromDisplay) = existing
            if (fromDisplay == displayId) {
                ownership.bringTaskToFront(taskId)
            } else {
                ownership.vacateOtherPanesHolding(packageName, keepPane = pane)
                val moved = try {
                    Instances.iActivityTaskManager.moveRootTaskToDisplay(taskId, displayId)
                    AndroidHook.VdDensityPin.markPackageOnVirtualDisplay(
                        packageName,
                        displayId
                    )
                    log(TAG, "startActivityOnPane relocate $packageName#$taskId $fromDisplay->$displayId")
                    ownership.bringTaskToFront(taskId)
                    true
                } catch (e: Throwable) {
                    log(TAG, "startActivityOnPane relocate failed:", e)
                    false
                }
                if (!moved) {
                    ownership.removePackageTasksEverywhere(packageName)
                    launch.launchOnDisplay(component, userId, displayId)
                } else {
                    true
                }
            }
        } else {
            // Sweep affinity zombies so Samsung NEW_TASK reuse cannot revive an empty root.
            ownership.removePackageTasksEverywhere(packageName)
            launch.launchOnDisplay(component, userId, displayId)
        }
        if (ok) {
            mPanePackages[pane] = packageName
            mFocusedPane = pane
            ownership.markOwnership(packageName, displayId)
            launch.schedulePersistSnapshot()
            notifySplitStateChanged()
            log(TAG, "startActivityOnPane ok pkg=$packageName pane=$pane display=$displayId")
        } else {
            log(TAG, "startActivityOnPane failed pkg=$packageName pane=$pane display=$displayId")
        }
        return ok
    }

    fun moveTaskId(taskId: Int, isVirtualDisplay: Boolean): Boolean {
        // Ownership + reclaim run on mHandler; Binder/IO callers must not race them.
        return ownership.runOnHandlerBlocking(false) {
            if (isVirtualDisplay) {
                moveTaskIdOnHandler(taskId, targetPane = mFocusedPane)
            } else {
                moveTaskIdOnHandler(taskId, targetPane = null)
            }
        }
    }

    /** Move [taskId] onto PRIMARY/SECONDARY; [pane] must be a valid [SplitPane]. */
    fun moveTaskIdToPane(taskId: Int, pane: Int): Boolean {
        if (!SplitPane.isValid(pane)) return false
        return ownership.runOnHandlerBlocking(false) { moveTaskIdOnHandler(taskId, targetPane = pane) }
    }

    /**
     * @param targetPane null = phone (DEFAULT_DISPLAY); otherwise PRIMARY/SECONDARY VD.
     */
    private fun moveTaskIdOnHandler(taskId: Int, targetPane: Int?): Boolean {
        val isVirtualDisplay = targetPane != null
        val targetDisplayId = if (isVirtualDisplay) {
            input.displayIdFor(targetPane!!) ?: return false
        } else {
            Display.DEFAULT_DISPLAY
        }
        if (targetDisplayId == Display.INVALID_DISPLAY) return false
        val packageName = ownership.findPackageForTask(taskId)
        val vacatedPanes = mutableListOf<Int>()
        if (!isVirtualDisplay) {
            // Cancel any armed reclaim before ownership is cleared — otherwise a prior
            // stack-changed debounce can yank the task straight back onto the VD.
            mHandler.removeCallbacks(ownership.mDebouncedReclaim)
            mSuppressReclaimUntil = SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_MS
            ownership.forgetOwnership(taskId, packageName)
            // Must clear pane bookkeeping: getPanePackage prefers mPanePackages when ATMS
            // is empty / chrome-only, so a stale pkg keeps the AA empty overlay hidden
            // forever after recent-task swipe moves the app to the phone.
            vacatedPanes += ownership.clearPanePackageForTask(taskId, packageName)
        } else {
            // Replacing pane content — drop previous occupancy like startActivityOnPane.
            val pane = targetPane!!
            val previous = mPanePackages[pane]
            if (!previous.isNullOrBlank() && previous != packageName) {
                ownership.removePackageTasksOnDisplay(previous, targetDisplayId)
                mPanePackages[pane] = null
                ownership.releaseOwnershipIfUnused(previous)
                vacatedPanes += pane
            }
            if (!packageName.isNullOrBlank()) {
                ownership.vacateOtherPanesHolding(packageName, pane)
            }
        }
        try {
            Instances.iActivityTaskManager.moveRootTaskToDisplay(taskId, targetDisplayId)
        } catch (e: Throwable) {
            log(TAG, "moveRootTaskToDisplay error:", e)
        }
        if (isVirtualDisplay) {
            val pane = targetPane!!
            ownership.markOwnership(packageName, targetDisplayId)
            mPanePackages[pane] = packageName
            mFocusedPane = pane
            AndroidHook.VdDensityPin.markPackageOnVirtualDisplay(packageName, targetDisplayId)
        } else {
            AndroidHook.VdDensityPin.clearPackageVirtualDisplay(packageName)
            // Best-effort: drop SecondaryDisplayLauncher so the empty overlay is obvious.
            vacatedPanes.distinct().forEach { pane ->
                input.displayIdFor(pane)?.let { ownership.removeChromeTasksOnDisplay(it) }
            }
        }
        launch.schedulePersistSnapshot()
        notifySplitStateChanged()
        return ownership.bringTaskToFront(taskId)
    }

    fun moveTaskToFront(taskId: Int): Boolean = ownership.bringTaskToFront(taskId)

    fun removeTask(taskId: Int): Boolean {
        return ownership.runOnHandlerBlocking(false) { removeTaskOnHandler(taskId) }
    }

    private fun removeTaskOnHandler(taskId: Int): Boolean {
        val packageName = ownership.findPackageForTask(taskId)
        val onVd = ownership.isTaskOnAaDisplay(taskId)
        return try {
            if (onVd) {
                mHandler.removeCallbacks(ownership.mDebouncedReclaim)
                mSuppressReclaimUntil = SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_MS
                ownership.forgetOwnership(taskId, packageName)
            }
            val removed = Instances.iActivityTaskManager.removeTask(taskId)
            if (removed && onVd && !packageName.isNullOrBlank()) {
                val vacated = mutableListOf<Int>()
                for (i in 0..1) {
                    if (mPanePackages[i] == packageName) {
                        mPanePackages[i] = null
                        vacated += i
                    }
                }
                AndroidHook.VdDensityPin.clearPackageVirtualDisplay(packageName)
                ownership.untrackPackage(packageName)
                vacated.forEach { pane ->
                    input.displayIdFor(pane)?.let { ownership.removeChromeTasksOnDisplay(it) }
                }
                launch.schedulePersistSnapshot()
                notifySplitStateChanged()
            }
            removed
        } catch (e: Throwable) {
            log(TAG, "removeTask error:", e)
            false
        }
    }

    fun moveSecondTaskToFront() {
        val other = if (mFocusedPane == SplitPane.PRIMARY) SplitPane.SECONDARY else SplitPane.PRIMARY
        val displayId = input.displayIdFor(other) ?: return
        val tasks = tryOrNull {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        }.orEmpty()
        val task = tasks.firstOrNull { it.topActivity != null } ?: return
        mFocusedPane = other
        ownership.bringTaskToFront(task.taskId)
    }

    /**
     * Swap user root-task stacks between PRIMARY and SECONDARY VirtualDisplays.
     * Surfaces / VD identities stay fixed; packages, ownership, focus, and ratio follow the apps.
     */
    fun swapPanes(): Boolean {
        return ownership.runOnHandlerBlocking(false) { swapPanesOnHandler() }
    }

    private fun swapPanesOnHandler(): Boolean {
        if (mIsDestroying) return false
        val primaryDisplay = primaryDisplayId
        val secondaryDisplay = secondaryDisplayId
        if (primaryDisplay == Display.INVALID_DISPLAY || secondaryDisplay == Display.INVALID_DISPLAY) {
            return false
        }

        val snapPrimary = ownership.snapshotUserRootTasks(primaryDisplay)
        val snapSecondary = ownership.snapshotUserRootTasks(secondaryDisplay)
        if (snapPrimary.isEmpty() && snapSecondary.isEmpty()) {
            log(TAG, "swapPanes: both panes empty")
            return false
        }

        val frontPrimary = mPanePackages[SplitPane.PRIMARY]
            ?: snapPrimary.lastOrNull()?.packageName
        val frontSecondary = mPanePackages[SplitPane.SECONDARY]
            ?: snapSecondary.lastOrNull()?.packageName

        mHandler.removeCallbacks(ownership.mDebouncedReclaim)
        mSuppressReclaimUntil = SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_MS

        val identity = Binder.clearCallingIdentity()
        try {
            // Cross-move by pre-swap snapshot IDs (bottom → top) so stacks stay intact.
            ownership.moveTaskStack(snapPrimary, secondaryDisplay)
            ownership.moveTaskStack(snapSecondary, primaryDisplay)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }

        mPanePackages[SplitPane.PRIMARY] = frontSecondary
        mPanePackages[SplitPane.SECONDARY] = frontPrimary
        mFocusedPane = if (mFocusedPane == SplitPane.PRIMARY) {
            SplitPane.SECONDARY
        } else {
            SplitPane.PRIMARY
        }

        val afterPrimary = ownership.snapshotUserRootTasks(primaryDisplay)
        val afterSecondary = ownership.snapshotUserRootTasks(secondaryDisplay)
        mVdTaskIds.clear()
        mVdTaskIds.addAll(afterPrimary.map { it.taskId })
        mVdTaskIds.addAll(afterSecondary.map { it.taskId })

        // Re-point package ownership at the new displays (both sides stay on VDs).
        linkedSetOf<String>().apply {
            frontPrimary?.let { add(it) }
            frontSecondary?.let { add(it) }
            afterPrimary.mapNotNullTo(this) { it.packageName }
            afterSecondary.mapNotNullTo(this) { it.packageName }
        }.forEach { pkg ->
            val onPrimary = afterPrimary.any { it.packageName == pkg }
            val target = when {
                onPrimary -> primaryDisplay
                else -> secondaryDisplay
            }
            ownership.markOwnership(pkg, target)
        }

        // Sizes follow the apps.
        mRatio = SplitPane.clampRatio(1f - mRatio)
        mSuppressReclaimUntil = SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_MS
        vd.resizePanesInternal("swap")

        launch.schedulePersistSnapshot()
        notifySplitStateChanged()
        log(
            TAG,
            "swapPanes ok primary=${mPanePackages[SplitPane.PRIMARY]} " +
                "secondary=${mPanePackages[SplitPane.SECONDARY]} ratio=$mRatio " +
                "tasksP=${afterPrimary.map { it.taskId }} tasksS=${afterSecondary.map { it.taskId }}"
        )
        return true
    }

    fun notifySplitStateChanged() {
        launch.notifySplitStateChangedImmediate()
    }
}
