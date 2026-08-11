package io.github.nitsuya.aa.display.ui.aa.split

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.ActivityTaskManager
import android.app.ITaskStackListener
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import android.window.TaskSnapshot
import androidx.core.graphics.drawable.toBitmap
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.getObjectAs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.github.kyuubiran.ezxhelper.utils.newInstance
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import de.robv.android.xposed.XSharedPreferences
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.model.RecentTask
import io.github.nitsuya.aa.display.model.RecentTaskInfo
import io.github.nitsuya.aa.display.service.ShellManagerService
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.LastSplitStore
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.IShellManager
import io.github.nitsuya.aa.display.xposed.TipUtil
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
    private val context: Context,
    private val config: XSharedPreferences?,
    private val onReady: suspend SplitDisplayController.() -> Unit,
) {
    companion object {
        const val TAG = "AADisplay_SplitDisplayController"
        private const val ACTIVITY_TYPE_HOME = 2
        private const val SNAPSHOT_DEBOUNCE_MS = 2500L
        /** VD resize + freezeDisplayRotation is expensive; keep drag/reconnect from flooding. */
        private const val RESIZE_THROTTLE_MS = 120L
        private const val RECLAIM_DEBOUNCE_MS = 200L
        private const val SUPPRESS_RECLAIM_MS = 2000L
        /** Longer suppress after restore so reclaim cannot overwrite panes during settle. */
        private const val SUPPRESS_RECLAIM_AFTER_RESTORE_MS = 8000L
        private const val ENSURE_PANES_DELAY_MS = 500L
        private const val RESTORE_VERIFY_DELAY_MS = 1200L

        private val IGNORE_RECENT_PACKAGE = setOf(
            BuildConfig.APPLICATION_ID,
            "android",
            "com.android.systemui",
            "com.android.launcher3",
            "com.sec.android.app.launcher",
        )

        private val BOUNCE_EXCLUDED = setOf(
            BuildConfig.APPLICATION_ID,
            "android",
            "com.android.systemui",
            "com.android.launcher3",
            "com.sec.android.app.launcher",
            "com.samsung.android.app.appsedge",
        )
    }

    enum class ManualRestoreResult {
        Started, NoDisplay, NoSnapshot, PackageUnavailable
    }

    /** Phone overlay / other observers refresh layout when VD sizes change. */
    var onSplitLayoutChanged: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mHandler = Handler(Looper.getMainLooper())

    var mWidth: Int = 0
        private set
    var mHeight: Int = 0
        private set
    var mDensityDpi: Int = 0
        private set
    var mRatio: Float = SplitPane.DEFAULT_RATIO
        private set
    var mFocusedPane: Int = SplitPane.PRIMARY
        private set
    /** Landscape → side-by-side; portrait → stacked. */
    val isSideBySide: Boolean
        get() = mWidth >= mHeight

    val primaryDisplayId: Int
        get() = mPrimary?.display?.displayId ?: Display.INVALID_DISPLAY
    val secondaryDisplayId: Int
        get() = mSecondary?.display?.displayId ?: Display.INVALID_DISPLAY

    private var mPrimary: VirtualDisplay? = null
    private var mSecondary: VirtualDisplay? = null
    private var mPrimarySurface: Surface? = null
    private var mSecondarySurface: Surface? = null
    private var mIsDestroying = false

    private val mPanePackages = arrayOfNulls<String>(2)
    private val mTrackedPackageUsers = linkedMapOf<String, MutableSet<Int>>()
    private val mVdTaskIds = mutableSetOf<Int>()
    private val mVdPackages = mutableSetOf<String>()
    /**
     * Written from Binder/IO ([moveTaskId]/[removeTask]) and read on the main handler
     * ([reclaimOwnedPackages]). Must be volatile so intentional move-off is not raced by reclaim.
     */
    @Volatile
    private var mSuppressReclaimUntil = 0L

    private val mPrimaryMirrors = HashMap<SurfaceControl, SurfaceControl>()
    private val mSecondaryMirrors = HashMap<SurfaceControl, SurfaceControl>()
    private val mTransaction = SurfaceControl.Transaction()

    private var mPrimaryWm: WindowManager? = null
    private var mSecondaryWm: WindowManager? = null
    private val mPrimaryForceView = View(context)
    private val mSecondaryForceView = View(context)

    private var mShellManager: IShellManager? = null
    private val mShellDeathRecipient = IBinder.DeathRecipient {
        log(TAG, "ShellManagerService binder died")
        mShellManager = null
    }
    private val mServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            logDebug(TAG, "ShellManagerService connected: $name")
            tryOrNull { mShellManager?.asBinder()?.unlinkToDeath(mShellDeathRecipient, 0) }
            mShellManager = IShellManager.Stub.asInterface(service)
            try {
                mShellManager?.asBinder()?.linkToDeath(mShellDeathRecipient, 0)
            } catch (e: Throwable) {
                log(TAG, "ShellManagerService linkToDeath failed:", e)
                mShellManager = null
            }
            invokeShellManager("createVirtualDisplayBefore") { it.createVirtualDisplayBefore() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            logDebug(TAG, "ShellManagerService disconnected: $name")
            tryOrNull { mShellManager?.asBinder()?.unlinkToDeath(mShellDeathRecipient, 0) }
            mShellManager = null
        }
    }

    private val mTaskStackListener = TaskStackListener()
    private val ENSURE_TOKEN = Any()
    private val VERIFY_RESTORE_TOKEN = Any()
    private var mLastResizeAt = 0L
    /** Displays already freeze-locked to ROTATION_0; skip re-freeze on resize (OEM walks all DCs). */
    private val mOrientationLockedDisplays = HashSet<Int>()
    private var mPersistFirstScheduledAt = 0L
    private val mDebouncedReclaim = Runnable { reclaimOwnedPackages("stack") }
    private val mDebouncedPersist = Runnable {
        mPersistFirstScheduledAt = 0L
        persistSnapshot(force = false, mirrorSettings = false)
    }
    private val mDebouncedNotifyState = Runnable {
        if (refreshPanePackagesFromAtms()) {
            notifySplitStateChangedImmediate()
        }
    }

    init {
        bindShellManager()
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
        mHandler.removeCallbacks(mDebouncedReclaim)
        mHandler.removeCallbacks(mDebouncedPersist)
        mPersistFirstScheduledAt = 0L

        val sizes = computePaneSizes()
        val flags = vdFlags()
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

        applyPolicies(SplitPane.PRIMARY, "connect")
        applyPolicies(SplitPane.SECONDARY, "connect")
        addKeepAwakeOverlay(SplitPane.PRIMARY)
        addKeepAwakeOverlay(SplitPane.SECONDARY)

        try {
            Instances.iActivityTaskManager.registerTaskStackListener(mTaskStackListener)
        } catch (e: Throwable) {
            log(TAG, "registerTaskStackListener failed:", e)
        }

        if (shouldRestoreLastSplitOnConnect()) {
            mSuppressReclaimUntil =
                SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_AFTER_RESTORE_MS
            scheduleRestoreLastSplit(manual = false)
        } else {
            notifySplitStateChanged()
        }
        onCreated(primaryDisplayId)
    }

    fun onReconnected(width: Int, height: Int, densityDpi: Int) {
        mWidth = width.coerceAtLeast(1)
        mHeight = height.coerceAtLeast(1)
        mDensityDpi = densityDpi.coerceAtLeast(1)
        resizePanesInternal("reconnect")
        applyPolicies(SplitPane.PRIMARY, "reconnect")
        applyPolicies(SplitPane.SECONDARY, "reconnect")
        scheduleEnsurePanePackages("reconnect")
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
            scheduleEnsurePanePackages("surfaces-ready")
        }
    }

    private var mLastPrimaryW = 0
    private var mLastPrimaryH = 0
    private var mLastSecondaryW = 0
    private var mLastSecondaryH = 0
    private val mDebouncedMirrorLayout = Runnable { onSplitLayoutChanged?.invoke() }

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
        resizePanesInternal("ratio")
        schedulePersistSnapshot()
    }

    private val mPendingResize = Runnable { resizePanesInternal("ratio-throttled") }

    fun setFocusedPane(pane: Int) {
        if (!SplitPane.isValid(pane)) return
        mFocusedPane = pane
    }

    fun getPanePackage(pane: Int): String? {
        if (!SplitPane.isValid(pane)) return null
        // Prefer live ATMS top so AA empty overlays match reality after external closes.
        // Never wipe bookkeeping on a fully empty/failed query during settle — AA Binder
        // callers often see a transient empty ATMS walk that used to clear mPanePackages.
        // Samsung (and others) place SecondaryDisplayLauncher on vacant VDs; that chrome
        // is not a user app — treat it as vacant so "Tap to choose" can appear.
        val displayId = displayIdFor(pane)
        if (displayId != null && displayId != Display.INVALID_DISPLAY) {
            val identity = Binder.clearCallingIdentity()
            try {
                val tasks = tryOrNull {
                    Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
                }.orEmpty()
                val topPkg = tasks.firstOrNull { info ->
                    val pkg = info.topActivity?.packageName
                    !pkg.isNullOrBlank() && !BOUNCE_EXCLUDED.contains(pkg)
                }?.topActivity?.packageName
                if (!topPkg.isNullOrBlank()) {
                    if (mPanePackages[pane] != topPkg) mPanePackages[pane] = topPkg
                    return topPkg
                }
                val hasAnyTop = tasks.any { it.topActivity != null }
                val settling = SystemClock.uptimeMillis() < mSuppressReclaimUntil
                if (hasAnyTop && !settling) {
                    // Only launcher/systemui chrome left → vacant for split UI.
                    if (mPanePackages[pane] != null) mPanePackages[pane] = null
                    return null
                }
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
        return mPanePackages[pane]
    }

    fun onDestroy() {
        try {
            persistSnapshot(force = true, mirrorSettings = true)
        } catch (e: Throwable) {
            log(TAG, "onDestroy snapshot failed:", e)
        }
        mIsDestroying = true
        mOrientationLockedDisplays.clear()
        mHandler.removeCallbacks(mDebouncedReclaim)
        mHandler.removeCallbacks(mDebouncedPersist)
        mHandler.removeCallbacks(mPendingResize)
        mHandler.removeCallbacks(mDebouncedNotifyState)
        mHandler.removeCallbacks(mDebouncedMirrorLayout)
        mHandler.removeCallbacksAndMessages(RESTORE_TOKEN)
        mHandler.removeCallbacksAndMessages(ENSURE_TOKEN)
        mHandler.removeCallbacksAndMessages(VERIFY_RESTORE_TOKEN)
        tryOrNull { Instances.iActivityTaskManager.unregisterTaskStackListener(mTaskStackListener) }

        val protectedPackages = linkedSetOf(BuildConfig.APPLICATION_ID)
        for (displayId in listOf(primaryDisplayId, secondaryDisplayId)) {
            if (displayId == Display.INVALID_DISPLAY) continue
            tryOrNull {
                Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId).forEach { task ->
                    trackPackageFromTask(task)
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

        invokeShellManager("destroyVirtualDisplayAfter") { it.destroyVirtualDisplayAfter() }
        tryOrNull { mShellManager?.asBinder()?.unlinkToDeath(mShellDeathRecipient, 0) }
        mShellManager = null
        tryOrNull { CoreManagerService.systemContext.unbindService(mServiceConnection) }

        releaseMirrors(mPrimaryMirrors)
        releaseMirrors(mSecondaryMirrors)
        removeKeepAwakeOverlay(SplitPane.PRIMARY)
        removeKeepAwakeOverlay(SplitPane.SECONDARY)
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
        val displayId = displayIdFor(pane) ?: return
        injectInputEvent(displayId, event)
    }

    fun onPressKey(action: Int) {
        val displayId = displayIdFor(mFocusedPane) ?: primaryDisplayId
        if (displayId == Display.INVALID_DISPLAY) return
        injectInputEvent(displayId, createKeyEvent(KeyEvent.ACTION_DOWN, action))
        injectInputEvent(displayId, createKeyEvent(KeyEvent.ACTION_UP, action))
    }

    fun addMirrorPane(pane: Int, surfaceControl: SurfaceControl) {
        val displayId = displayIdFor(pane) ?: return
        val map = if (pane == SplitPane.PRIMARY) mPrimaryMirrors else mSecondaryMirrors
        val sc = SurfaceControl::class.java.newInstance(args(), argTypes()) as SurfaceControl
        try {
            if (!Instances.iWindowManager.mirrorDisplay(displayId, sc)) {
                sc.release()
                return
            }
        } catch (e: Throwable) {
            TipUtil.showToast("addMirror error: ${e.message}")
            log(TAG, "addMirrorPane error:", e)
            sc.release()
            return
        }
        if (!sc.isValid) {
            sc.release()
            return
        }
        try {
            mTransaction
                .apply { invokeMethod("show", args(sc), argTypes(SurfaceControl::class.java)) }
                .reparent(sc, surfaceControl)
                .apply()
        } catch (e: Throwable) {
            log(TAG, "addMirrorPane show error:", e)
            sc.release()
            return
        }
        map.put(surfaceControl, sc)?.release()
    }

    fun removeMirrorPane(pane: Int, surfaceControl: SurfaceControl) {
        val map = if (pane == SplitPane.PRIMARY) mPrimaryMirrors else mSecondaryMirrors
        map.remove(surfaceControl)?.also { sc ->
            mTransaction.apply {
                invokeMethod("remove", args(sc), argTypes(SurfaceControl::class.java))
            }.apply()
            sc.release()
        }
    }

    fun getRecentTask(): RecentTask {
        return try {
            val primary = if (primaryDisplayId != Display.INVALID_DISPLAY) {
                recentTaskInfo(primaryDisplayId)
            } else {
                emptyList()
            }
            val secondary = if (secondaryDisplayId != Display.INVALID_DISPLAY) {
                recentTaskInfo(secondaryDisplayId)
            } else {
                emptyList()
            }
            RecentTask(recentTaskInfo(Display.DEFAULT_DISPLAY), primary, secondary)
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
        val displayId = displayIdFor(pane) ?: return false
        val component = resolveLaunchComponent(packageName) ?: return false
        // Replace existing task on this pane when launching a different package.
        val previous = mPanePackages[pane]
        if (!previous.isNullOrBlank() && previous != packageName) {
            removePackageTasksOnDisplay(previous, displayId)
            mPanePackages[pane] = null
            // Drop ownership so reclaim cannot pull the old app onto the other/focused pane.
            releaseOwnershipIfUnused(previous)
        }
        val ok = launchOnDisplay(component, userId, displayId)
        if (ok) {
            mPanePackages[pane] = packageName
            mFocusedPane = pane
            markOwnership(packageName, displayId)
            schedulePersistSnapshot()
            notifySplitStateChanged()
        }
        return ok
    }

    fun startTaskId(taskId: Int?, packageName: String, userId: Int): Boolean {
        if (taskId == null) return startActivity(packageName, userId)
        return try {
            moveTaskId(taskId, true)
        } catch (e: Throwable) {
            log(TAG, "startTaskId error:", e)
            startActivity(packageName, userId)
        }
    }

    fun moveTaskId(taskId: Int, isVirtualDisplay: Boolean): Boolean {
        // Ownership + reclaim run on mHandler; Binder/IO callers must not race them.
        return runOnHandlerBlocking(false) { moveTaskIdOnHandler(taskId, isVirtualDisplay) }
    }

    private fun moveTaskIdOnHandler(taskId: Int, isVirtualDisplay: Boolean): Boolean {
        val targetDisplayId = if (isVirtualDisplay) {
            displayIdFor(mFocusedPane) ?: primaryDisplayId
        } else {
            Display.DEFAULT_DISPLAY
        }
        if (targetDisplayId == Display.INVALID_DISPLAY) return false
        val packageName = findPackageForTask(taskId)
        val vacatedPanes = mutableListOf<Int>()
        if (!isVirtualDisplay) {
            // Cancel any armed reclaim before ownership is cleared — otherwise a prior
            // stack-changed debounce can yank the task straight back onto the VD.
            mHandler.removeCallbacks(mDebouncedReclaim)
            mSuppressReclaimUntil = SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_MS
            forgetOwnership(taskId, packageName)
            // Must clear pane bookkeeping: getPanePackage prefers mPanePackages when ATMS
            // is empty / chrome-only, so a stale pkg keeps the AA empty overlay hidden
            // forever after recent-task swipe moves the app to the phone.
            vacatedPanes += clearPanePackageForTask(taskId, packageName)
        } else {
            // Replacing focused pane content — drop previous occupancy like startActivityOnPane.
            paneForDisplayId(targetDisplayId)?.let { pane ->
                val previous = mPanePackages[pane]
                if (!previous.isNullOrBlank() && previous != packageName) {
                    removePackageTasksOnDisplay(previous, targetDisplayId)
                    mPanePackages[pane] = null
                    releaseOwnershipIfUnused(previous)
                    vacatedPanes += pane
                }
            }
        }
        try {
            Instances.iActivityTaskManager.moveRootTaskToDisplay(taskId, targetDisplayId)
        } catch (e: Throwable) {
            log(TAG, "moveRootTaskToDisplay error:", e)
        }
        if (isVirtualDisplay) {
            markOwnership(packageName, targetDisplayId)
            paneForDisplayId(targetDisplayId)?.let { mPanePackages[it] = packageName }
            AndroidHook.FuckAppUseApplicationContext.markPackageOnVirtualDisplay(packageName, targetDisplayId)
        } else {
            AndroidHook.FuckAppUseApplicationContext.clearPackageVirtualDisplay(packageName)
            // Best-effort: drop SecondaryDisplayLauncher so the empty overlay is obvious.
            vacatedPanes.distinct().forEach { pane ->
                displayIdFor(pane)?.let { removeChromeTasksOnDisplay(it) }
            }
        }
        schedulePersistSnapshot()
        notifySplitStateChanged()
        return bringTaskToFront(taskId)
    }

    /**
     * Clear [mPanePackages] for a task that is intentionally leaving a VD pane.
     * @return panes that were vacated
     */
    private fun clearPanePackageForTask(taskId: Int, packageName: String?): List<Int> {
        val vacated = mutableListOf<Int>()
        if (!packageName.isNullOrBlank()) {
            for (i in 0..1) {
                if (mPanePackages[i] == packageName) {
                    mPanePackages[i] = null
                    vacated += i
                }
            }
        }
        if (vacated.isNotEmpty()) return vacated
        for (pane in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
            val displayId = displayIdFor(pane) ?: continue
            val onPane = tryOrNull {
                Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
            }.orEmpty().any { it.taskId == taskId }
            if (onPane) {
                mPanePackages[pane] = null
                return listOf(pane)
            }
        }
        return emptyList()
    }

    /** Remove launcher / systemui chrome left on an emptied VD (Samsung Secondary HOME, etc.). */
    private fun removeChromeTasksOnDisplay(displayId: Int) {
        if (displayId == Display.INVALID_DISPLAY) return
        val tasks = tryOrNull {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        }.orEmpty()
        for (task in tasks) {
            val pkg = task.topActivity?.packageName
            val chrome = (!pkg.isNullOrBlank() && BOUNCE_EXCLUDED.contains(pkg)) || isSystemHomeTask(task)
            if (!chrome) continue
            tryOrNull { Instances.iActivityTaskManager.removeTask(task.taskId) }
        }
    }

    fun moveTaskToFront(taskId: Int): Boolean = bringTaskToFront(taskId)

    fun removeTask(taskId: Int): Boolean {
        return runOnHandlerBlocking(false) { removeTaskOnHandler(taskId) }
    }

    private fun removeTaskOnHandler(taskId: Int): Boolean {
        val packageName = findPackageForTask(taskId)
        val onVd = isTaskOnAaDisplay(taskId)
        return try {
            if (onVd) {
                mHandler.removeCallbacks(mDebouncedReclaim)
                mSuppressReclaimUntil = SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_MS
                forgetOwnership(taskId, packageName)
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
                AndroidHook.FuckAppUseApplicationContext.clearPackageVirtualDisplay(packageName)
                untrackPackage(packageName)
                vacated.forEach { pane ->
                    displayIdFor(pane)?.let { removeChromeTasksOnDisplay(it) }
                }
                schedulePersistSnapshot()
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
        val displayId = displayIdFor(other) ?: return
        val tasks = tryOrNull {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        }.orEmpty()
        val task = tasks.firstOrNull { it.topActivity != null } ?: return
        mFocusedPane = other
        bringTaskToFront(task.taskId)
    }

    fun requestRestoreLastSplitManual(): ManualRestoreResult {
        if (primaryDisplayId == Display.INVALID_DISPLAY) return ManualRestoreResult.NoDisplay
        val snap = LastSplitStore.load(context.contentResolver) ?: return ManualRestoreResult.NoSnapshot
        if (resolveLaunchComponent(snap.leftPackage) == null ||
            resolveLaunchComponent(snap.rightPackage) == null
        ) {
            return ManualRestoreResult.PackageUnavailable
        }
        scheduleRestoreLastSplit(manual = true)
        return ManualRestoreResult.Started
    }

    // region sizing / policies

    private data class PaneSizes(
        val primaryW: Int,
        val primaryH: Int,
        val secondaryW: Int,
        val secondaryH: Int,
    )

    private fun dividerPx(): Int {
        val dpi = mDensityDpi.coerceAtLeast(160)
        return (SplitPane.DIVIDER_DP * dpi / 160f).toInt().coerceAtLeast(8)
    }

    private fun computePaneSizes(): PaneSizes {
        val gap = dividerPx()
        return if (isSideBySide) {
            val usable = (mWidth - gap).coerceAtLeast(2)
            val pw = (usable * mRatio).toInt().coerceAtLeast(1)
            val sw = (usable - pw).coerceAtLeast(1)
            PaneSizes(pw, mHeight, sw, mHeight)
        } else {
            val usable = (mHeight - gap).coerceAtLeast(2)
            val ph = (usable * mRatio).toInt().coerceAtLeast(1)
            val sh = (usable - ph).coerceAtLeast(1)
            PaneSizes(mWidth, ph, mWidth, sh)
        }
    }

    private fun vdFlags(): Int {
        return DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            (1 shl 10) or // TRUSTED
            (1 shl 11) or // OWN_DISPLAY_GROUP
            (1 shl 12) or // ALWAYS_UNLOCKED
            (1 shl 13)    // TOUCH_FEEDBACK_DISABLED
    }

    private fun resizePanesInternal(reason: String) {
        val primary = mPrimary ?: return
        val secondary = mSecondary ?: return
        val sizes = computePaneSizes()
        if (sizes.primaryW == mLastPrimaryW &&
            sizes.primaryH == mLastPrimaryH &&
            sizes.secondaryW == mLastSecondaryW &&
            sizes.secondaryH == mLastSecondaryH &&
            reason != "reconnect"
        ) {
            return
        }
        mLastPrimaryW = sizes.primaryW
        mLastPrimaryH = sizes.primaryH
        mLastSecondaryW = sizes.secondaryW
        mLastSecondaryH = sizes.secondaryH
        mLastResizeAt = SystemClock.uptimeMillis()
        mSuppressReclaimUntil = SystemClock.uptimeMillis() + 800L
        try {
            primary.resize(sizes.primaryW, sizes.primaryH, mDensityDpi)
            secondary.resize(sizes.secondaryW, sizes.secondaryH, mDensityDpi)
            // Resize can flip pane aspect (wide↔tall); re-lock so landscape apps cannot
            // rotate a newly-narrow pane.
            applyPolicies(SplitPane.PRIMARY, "resize-$reason")
            applyPolicies(SplitPane.SECONDARY, "resize-$reason")
            logDebug(
                TAG,
                "resize[$reason]: P ${sizes.primaryW}x${sizes.primaryH} " +
                    "S ${sizes.secondaryW}x${sizes.secondaryH} ratio=$mRatio"
            )
            // Debounce phone-mirror layout updates to avoid overlay thrash during drag.
            mHandler.removeCallbacks(mDebouncedMirrorLayout)
            mHandler.postDelayed(mDebouncedMirrorLayout, 48L)
        } catch (e: Throwable) {
            log(TAG, "resize failed:", e)
        }
    }

    private fun applyPolicies(pane: Int, reason: String) {
        val displayId = displayIdFor(pane) ?: return
        val imePolicy = AADisplayConfig.DisplayImePolicy.get(config).coerceIn(0, 1)
        try {
            Instances.iWindowManager.apply {
                setDisplayImePolicy(displayId, imePolicy)
                setShouldShowWithInsecureKeyguard(displayId, false)
                setShouldShowSystemDecors(displayId, false)
            }
            // Narrow side-by-side panes are taller than wide (e.g. 278×480). Landscape apps
            // then rotate the VD to ROTATION_90 (logical 480×278) while the TextureView stays
            // physical W×H → letterbox bars top/bottom. Lock physical orientation.
            lockPaneDisplayOrientation(displayId, reason)
            logDebug(TAG, "policies[$reason] pane=$pane display=$displayId ime=$imePolicy")
        } catch (e: Throwable) {
            log(TAG, "applyPolicies failed pane=$pane:", e)
        }
    }

    /**
     * Keep each pane VD at ROTATION_0 matching its create/resize buffer so TextureView
     * aspect equals app layout. Reflection: OEM IWindowManager signatures differ.
     */
    private fun lockPaneDisplayOrientation(displayId: Int, reason: String) {
        if (displayId == Display.INVALID_DISPLAY) return
        // Samsung freezeDisplayRotation re-walks every DisplayContent; during drag-ratio
        // resize this alone was 600–900ms on system_server main. Keep lock sticky.
        if (reason.startsWith("resize-") && mOrientationLockedDisplays.contains(displayId)) {
            return
        }
        val iwm = Instances.iWindowManager
        val identity = Binder.clearCallingIdentity()
        try {
            runCatching {
                iwm.javaClass.methods.firstOrNull { m ->
                    m.name == "setIgnoreOrientationRequest" &&
                        m.parameterTypes.size >= 2 &&
                        m.parameterTypes[0] == Int::class.javaPrimitiveType
                }?.invoke(iwm, displayId, true)
            }.onFailure {
                logDebug(TAG, "setIgnoreOrientationRequest unavailable: ${it.message}")
            }
            runCatching {
                val methods = iwm.javaClass.methods.filter { it.name == "freezeDisplayRotation" }
                val withCaller = methods.firstOrNull { it.parameterTypes.size == 3 }
                val without = methods.firstOrNull { it.parameterTypes.size == 2 }
                when {
                    withCaller != null -> withCaller.invoke(iwm, displayId, Surface.ROTATION_0, "AADisplay")
                    without != null -> without.invoke(iwm, displayId, Surface.ROTATION_0)
                    else -> error("no freezeDisplayRotation")
                }
                mOrientationLockedDisplays.add(displayId)
            }.onFailure {
                logDebug(TAG, "freezeDisplayRotation unavailable: ${it.message}")
            }
            logDebug(TAG, "lockOrientation[$reason] display=$displayId rot=0")
        } catch (e: Throwable) {
            log(TAG, "lockPaneDisplayOrientation failed display=$displayId:", e)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun addKeepAwakeOverlay(pane: Int) {
        val vd = if (pane == SplitPane.PRIMARY) mPrimary else mSecondary
        val forceView = if (pane == SplitPane.PRIMARY) mPrimaryForceView else mSecondaryForceView
        val display = vd?.display ?: return
        try {
            val wm = context.createDisplayContext(display)
                .createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                .getSystemService(WindowManager::class.java)
            wm.addView(
                forceView,
                WindowManager.LayoutParams(
                    0, 0,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSPARENT
                ).also {
                    it.gravity = Gravity.START or Gravity.TOP
                    // Do not force LANDSCAPE from overall HU orientation: a narrow pane is often
                    // taller than wide; overlay LANDSCAPE would rotate that VD and letterbox.
                    it.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
                    it.alpha = 0f
                }
            )
            if (pane == SplitPane.PRIMARY) mPrimaryWm = wm else mSecondaryWm = wm
        } catch (e: Throwable) {
            log(TAG, "keepAwake overlay failed pane=$pane:", e)
        }
    }

    private fun removeKeepAwakeOverlay(pane: Int) {
        try {
            if (pane == SplitPane.PRIMARY) {
                mPrimaryWm?.removeView(mPrimaryForceView)
                mPrimaryWm = null
            } else {
                mSecondaryWm?.removeView(mSecondaryForceView)
                mSecondaryWm = null
            }
        } catch (_: Throwable) {
        }
    }

    // endregion

    // region launch / restore

    private fun shouldRestoreLastSplitOnConnect(): Boolean {
        if (!AADisplayConfig.RestoreLastSplit.get(config)) return false
        val snap = LastSplitStore.load(context.contentResolver) ?: return false
        return resolveLaunchComponent(snap.leftPackage) != null &&
            resolveLaunchComponent(snap.rightPackage) != null
    }

    private val RESTORE_TOKEN = Any()

    private fun scheduleRestoreLastSplit(manual: Boolean) {
        mHandler.removeCallbacksAndMessages(RESTORE_TOKEN)
        mHandler.postDelayed({
            if (mIsDestroying) return@postDelayed
            restoreLastSplitNow(manual)
        }, RESTORE_TOKEN, 400L)
    }

    private fun restoreLastSplitNow(manual: Boolean) {
        val snap = LastSplitStore.load(context.contentResolver)
        if (snap == null) {
            notifySplitStateChanged()
            return
        }
        mSuppressReclaimUntil = SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_AFTER_RESTORE_MS
        mRatio = SplitPane.clampRatio(snap.primaryRatio)
        resizePanesInternal("restore")
        val leftOk = startActivityOnPane(snap.leftPackage, 0, SplitPane.PRIMARY)
        val rightOk = startActivityOnPane(snap.rightPackage, 0, SplitPane.SECONDARY)
        log(
            TAG,
            "restoreLastSplit manual=$manual left=${snap.leftPackage}:$leftOk " +
                "right=${snap.rightPackage}:$rightOk ratio=$mRatio"
        )
        if (!leftOk) openPickerForPane(SplitPane.PRIMARY)
        if (!rightOk) openPickerForPane(SplitPane.SECONDARY)
        notifySplitStateChanged()
        // Launch returning true only means startActivity was accepted — verify panes stuck.
        scheduleVerifyRestore(snap)
    }

    private fun scheduleVerifyRestore(snap: LastSplitStore.Snapshot) {
        mHandler.removeCallbacksAndMessages(VERIFY_RESTORE_TOKEN)
        mHandler.postDelayed({
            if (mIsDestroying) return@postDelayed
            mSuppressReclaimUntil = SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_MS
            val leftDisplay = primaryDisplayId
            val rightDisplay = secondaryDisplayId
            val leftPresent = leftDisplay != Display.INVALID_DISPLAY &&
                hasPackageOnDisplay(snap.leftPackage, leftDisplay)
            val rightPresent = rightDisplay != Display.INVALID_DISPLAY &&
                hasPackageOnDisplay(snap.rightPackage, rightDisplay)
            if (!leftPresent) {
                log(TAG, "restore verify: left missing ${snap.leftPackage}, relaunch")
                val ok = startActivityOnPane(snap.leftPackage, 0, SplitPane.PRIMARY)
                if (!ok) openPickerForPane(SplitPane.PRIMARY)
            }
            if (!rightPresent) {
                log(TAG, "restore verify: right missing ${snap.rightPackage}, relaunch")
                val ok = startActivityOnPane(snap.rightPackage, 0, SplitPane.SECONDARY)
                if (!ok) openPickerForPane(SplitPane.SECONDARY)
            }
            if (leftPresent && rightPresent) {
                mPanePackages[SplitPane.PRIMARY] = snap.leftPackage
                mPanePackages[SplitPane.SECONDARY] = snap.rightPackage
                persistSnapshot(force = true, mirrorSettings = true)
            }
            notifySplitStateChanged()
        }, VERIFY_RESTORE_TOKEN, RESTORE_VERIFY_DELAY_MS)
    }

    private fun scheduleEnsurePanePackages(reason: String) {
        mHandler.removeCallbacksAndMessages(ENSURE_TOKEN)
        mHandler.postDelayed({
            if (mIsDestroying) return@postDelayed
            ensurePanePackages(reason)
        }, ENSURE_TOKEN, ENSURE_PANES_DELAY_MS)
    }

    /**
     * Soft reconnect / surface restore: if remembered pane apps left the VDs, bring them back.
     * When both panes are vacant, fall back to last-split restore (or leave empty for picker).
     */
    private fun ensurePanePackages(reason: String) {
        if (primaryDisplayId == Display.INVALID_DISPLAY) return
        // During restore/connect settle, do not treat empty ATMS as vacant (would wipe mPanePackages
        // and race restore over the restored left app).
        val settling = SystemClock.uptimeMillis() < mSuppressReclaimUntil
        if (!settling) {
            refreshPanePackagesFromAtms()
        }
        val left = mPanePackages[SplitPane.PRIMARY]
        val right = mPanePackages[SplitPane.SECONDARY]
        if (!settling && left.isNullOrBlank() && right.isNullOrBlank()) {
            log(TAG, "ensurePanes[$reason]: both empty → restore or idle")
            if (shouldRestoreLastSplitOnConnect()) {
                scheduleRestoreLastSplit(manual = false)
            } else {
                notifySplitStateChanged()
            }
            return
        }
        var relaunched = false
        for (pane in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
            val expected = mPanePackages[pane]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val displayId = displayIdFor(pane) ?: continue
            if (hasPackageOnDisplay(expected, displayId)) continue
            log(TAG, "ensurePanes[$reason]: relaunch $expected on pane=$pane")
            if (startActivityOnPane(expected, 0, pane)) {
                relaunched = true
            }
        }
        if (relaunched) {
            mSuppressReclaimUntil =
                maxOf(mSuppressReclaimUntil, SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_MS)
            notifySplitStateChanged()
        }
    }

    private fun openPickerForPane(pane: Int) {
        try {
            context.sendBroadcast(
                Intent(AABroadcastConst.ACTION_OPEN_SPLIT_PICKER).apply {
                    putExtra(AABroadcastConst.EXTRA_PANE, pane)
                }
            )
        } catch (e: Throwable) {
            log(TAG, "openPickerForPane failed pane=$pane:", e)
        }
    }

    fun notifySplitStateChanged() {
        notifySplitStateChangedImmediate()
    }

    private fun notifySplitStateChangedImmediate() {
        try {
            context.sendBroadcast(Intent(AABroadcastConst.ACTION_SPLIT_STATE_CHANGED))
        } catch (e: Throwable) {
            logDebug(TAG, "notifySplitStateChanged failed: ${e.message}")
        }
    }

    private fun scheduleNotifySplitState() {
        mHandler.removeCallbacks(mDebouncedNotifyState)
        mHandler.postDelayed(mDebouncedNotifyState, 180L)
    }

    private fun launchOnDisplay(
        componentName: ComponentName,
        userId: Int,
        displayId: Int,
    ): Boolean {
        return try {
            AndroidHook.FuckAppUseApplicationContext.markPackageOnVirtualDisplay(
                componentName.packageName,
                displayId
            )
            context.invokeMethod(
                "startActivityAsUser",
                args(
                    Intent().apply {
                        component = componentName
                        `package` = componentName.packageName
                        action = Intent.ACTION_MAIN
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        putExtra("displayId", displayId)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    },
                    ActivityOptions.makeBasic().apply {
                        launchDisplayId = displayId
                        try {
                            invokeMethod("setCallerDisplayId", args(displayId), argTypes(Integer.TYPE))
                        } catch (_: Throwable) {
                        }
                    }.toBundle(),
                    UserHandle::class.java.newInstance(
                        args(userId),
                        argTypes(Integer.TYPE)
                    )
                ),
                argTypes(Intent::class.java, Bundle::class.java, UserHandle::class.java)
            )
            trackPackage(componentName.packageName, userId)
            true
        } catch (e: Throwable) {
            log(TAG, "launchOnDisplay error display=$displayId:", e)
            false
        }
    }

    private fun resolveLaunchComponent(packageName: String): ComponentName? {
        val pkg = packageName.trim().takeIf { it.isNotEmpty() } ?: return null
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
            val ri = context.packageManager.resolveActivity(intent, 0) ?: return null
            ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
        } catch (_: Throwable) {
            null
        }
    }

    // endregion

    // region snapshot

    private fun schedulePersistSnapshot() {
        val now = SystemClock.uptimeMillis()
        if (mPersistFirstScheduledAt == 0L) {
            mPersistFirstScheduledAt = now
        }
        mHandler.removeCallbacks(mDebouncedPersist)
        val wait = if (now - mPersistFirstScheduledAt >= 8000L) {
            0L
        } else {
            SNAPSHOT_DEBOUNCE_MS
        }
        mHandler.postDelayed(mDebouncedPersist, wait)
    }

    private fun persistSnapshot(force: Boolean, mirrorSettings: Boolean) {
        refreshPanePackagesFromAtms()
        val left = mPanePackages[SplitPane.PRIMARY]?.trim().orEmpty()
        val right = mPanePackages[SplitPane.SECONDARY]?.trim().orEmpty()
        if (left.isEmpty() || right.isEmpty() || left == right) {
            if (force) {
                logDebug(TAG, "persist skip: incomplete panes left=$left right=$right")
            }
            return
        }
        val snap = LastSplitStore.Snapshot(
            leftPackage = left,
            rightPackage = right,
            primaryRatio = mRatio,
            landscape = isSideBySide,
            sideBySide = isSideBySide,
        )
        LastSplitStore.save(snap, context.contentResolver, mirrorSettings = mirrorSettings || force)
    }

    /** Re-walk ATMS tops so snapshots / empty-pane state match reality after external closes. */
    private fun refreshPanePackagesFromAtms(): Boolean {
        var changed = false
        val settling = SystemClock.uptimeMillis() < mSuppressReclaimUntil
        val identity = Binder.clearCallingIdentity()
        try {
            for (pane in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
                val displayId = displayIdFor(pane) ?: continue
                val tasks = tryOrNull {
                    Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
                }.orEmpty()
                val topPkg = tasks.firstOrNull { info ->
                    val pkg = info.topActivity?.packageName
                    !pkg.isNullOrBlank() && !BOUNCE_EXCLUDED.contains(pkg)
                }?.topActivity?.packageName
                val next = when {
                    topPkg != null -> topPkg
                    // During restore/connect settle, keep bookkeeping through empty or
                    // chrome-only ATMS walks (SecondaryDisplayLauncher flash).
                    settling -> mPanePackages[pane]
                    // Not settling: empty or only launcher/systemui → vacant.
                    else -> null
                }
                if (mPanePackages[pane] != next) {
                    mPanePackages[pane] = next
                    changed = true
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        return changed
    }

    // endregion

    // region ownership / reclaim

    /**
     * Marshal ownership-sensitive work onto [mHandler] so Binder/IO callers cannot race
     * [TaskStackListener] reclaim. Avoids snapping a swipe-off task back onto the VD.
     */
    private fun <T> runOnHandlerBlocking(default: T, block: () -> T): T {
        if (Looper.myLooper() == mHandler.looper) return block()
        val box = arrayOfNulls<Any?>(1)
        val latch = CountDownLatch(1)
        val posted = mHandler.post {
            try {
                box[0] = block()
            } catch (e: Throwable) {
                log(TAG, "runOnHandlerBlocking failed:", e)
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return default
        return try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                log(TAG, "runOnHandlerBlocking timeout")
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

    private fun markOwnership(packageName: String?, displayId: Int) {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        mVdPackages.add(pkg)
        AndroidHook.FuckAppUseApplicationContext.markPackageOnVirtualDisplay(pkg, displayId)
        trackPackage(pkg, 0)
    }

    /** Forget VD ownership when [packageName] is no longer assigned to either pane. */
    private fun releaseOwnershipIfUnused(packageName: String?) {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (mPanePackages[SplitPane.PRIMARY] == pkg || mPanePackages[SplitPane.SECONDARY] == pkg) {
            return
        }
        mVdPackages.remove(pkg)
        AndroidHook.FuckAppUseApplicationContext.clearPackageVirtualDisplay(pkg)
    }

    private fun forgetOwnership(taskId: Int, packageName: String?) {
        mVdTaskIds.remove(taskId)
        packageName?.let { mVdPackages.remove(it) }
    }

    private fun reclaimOwnedPackages(reason: String) {
        if (mIsDestroying) return
        if (SystemClock.uptimeMillis() < mSuppressReclaimUntil) return
        if (mVdPackages.isEmpty()) return
        for (pkg in mVdPackages.toList()) {
            if (BOUNCE_EXCLUDED.contains(pkg)) continue
            val onPrimary = hasPackageOnDisplay(pkg, primaryDisplayId)
            val onSecondary = hasPackageOnDisplay(pkg, secondaryDisplayId)
            if (onPrimary || onSecondary) continue
            val phoneTask = findPackageTaskOnDisplay(pkg, Display.DEFAULT_DISPLAY) ?: continue
            val targetPane = when {
                mPanePackages[SplitPane.PRIMARY] == pkg -> SplitPane.PRIMARY
                mPanePackages[SplitPane.SECONDARY] == pkg -> SplitPane.SECONDARY
                else -> {
                    // Package is owned but no longer assigned to a pane — drop it.
                    releaseOwnershipIfUnused(pkg)
                    continue
                }
            }
            val targetDisplay = displayIdFor(targetPane) ?: continue
            log(TAG, "reclaim[$reason]: $pkg#$phoneTask -> display=$targetDisplay")
            try {
                Instances.iActivityTaskManager.moveRootTaskToDisplay(phoneTask, targetDisplay)
                AndroidHook.FuckAppUseApplicationContext.markPackageOnVirtualDisplay(pkg, targetDisplay)
            } catch (e: Throwable) {
                log(TAG, "reclaim move failed:", e)
            }
        }
    }

    private fun hasPackageOnDisplay(packageName: String, displayId: Int): Boolean {
        if (displayId == Display.INVALID_DISPLAY) return false
        return findPackageTaskOnDisplay(packageName, displayId) != null
    }

    private fun findPackageTaskOnDisplay(packageName: String, displayId: Int): Int? {
        val tasks = tryOrNull {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        }.orEmpty()
        return tasks.firstOrNull { info ->
            info.topActivity?.packageName == packageName ||
                runCatching {
                    (info as Any).getObjectAs("baseActivity", ComponentName::class.java) as? ComponentName
                }.getOrNull()?.packageName == packageName
        }?.taskId
    }

    private fun removePackageTasksOnDisplay(packageName: String, displayId: Int) {
        val tasks = tryOrNull {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        }.orEmpty()
        tasks.filter {
            it.topActivity?.packageName == packageName
        }.forEach { task ->
            tryOrNull { Instances.iActivityTaskManager.removeTask(task.taskId) }
        }
    }

    private fun findPackageForTask(taskId: Int): String? {
        for (displayId in listOf(primaryDisplayId, secondaryDisplayId, Display.DEFAULT_DISPLAY)) {
            if (displayId == Display.INVALID_DISPLAY) continue
            val tasks = tryOrNull {
                Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
            }.orEmpty()
            tasks.firstOrNull { it.taskId == taskId }?.topActivity?.packageName?.let { return it }
        }
        return null
    }

    private fun isTaskOnAaDisplay(taskId: Int): Boolean {
        for (displayId in listOf(primaryDisplayId, secondaryDisplayId)) {
            if (displayId == Display.INVALID_DISPLAY) continue
            val tasks = tryOrNull {
                Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
            }.orEmpty()
            if (tasks.any { it.taskId == taskId }) return true
        }
        return false
    }

    private fun bringTaskToFront(taskId: Int): Boolean {
        return try {
            Instances.activityManager.moveTaskToFront(taskId, 0)
            true
        } catch (e: Throwable) {
            log(TAG, "moveTaskToFront error:", e)
            false
        }
    }

    private fun trackPackage(packageName: String?, userId: Int = 0) {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        mTrackedPackageUsers.getOrPut(pkg) { linkedSetOf() }.add(userId)
    }

    private fun untrackPackage(packageName: String?) {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        mTrackedPackageUsers.remove(pkg)
    }

    private fun trackPackageFromTask(taskInfo: Any) {
        val userId = runCatching {
            taskInfo.getObjectAs("userId", Int::class.javaPrimitiveType) as? Int
        }.getOrNull() ?: 0
        runCatching {
            taskInfo.getObjectAs("topActivity", ComponentName::class.java) as? ComponentName
        }.getOrNull()?.packageName?.let { trackPackage(it, userId) }
    }

    // endregion

    // region input / recent / shell

    private fun displayIdFor(pane: Int): Int? = when (pane) {
        SplitPane.PRIMARY -> primaryDisplayId.takeIf { it != Display.INVALID_DISPLAY }
        SplitPane.SECONDARY -> secondaryDisplayId.takeIf { it != Display.INVALID_DISPLAY }
        else -> null
    }

    private fun injectInputEvent(displayId: Int, event: InputEvent): Boolean {
        val identity = Binder.clearCallingIdentity()
        return try {
            event.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            Instances.iInputManager.injectInputEvent(event, 0)
        } catch (e: Throwable) {
            log(TAG, "injectInputEvent exception:", e)
            false
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun createKeyEvent(action: Int, keyCode: Int): KeyEvent {
        val whenMillis = SystemClock.uptimeMillis()
        return KeyEvent(
            whenMillis, whenMillis, action, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY,
            InputDevice.SOURCE_KEYBOARD
        )
    }

    private fun isSystemHomeTask(taskInfo: ActivityTaskManager.RootTaskInfo): Boolean {
        return try {
            val conf = (taskInfo as Any).invokeMethod("getConfiguration", args(), argTypes()) ?: return false
            val winConf = conf.invokeMethod("getWindowConfiguration", args(), argTypes()) ?: return false
            val activityType = winConf.invokeMethod("getActivityType", args(), argTypes()) as? Int
            activityType == ACTIVITY_TYPE_HOME
        } catch (_: Throwable) {
            false
        }
    }

    private fun recentTaskInfo(displayId: Int): List<RecentTaskInfo> {
        val all = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        return all.mapNotNull { taskInfo ->
            if (isSystemHomeTask(taskInfo)) return@mapNotNull null
            val topActivity = taskInfo.topActivity ?: return@mapNotNull null
            if (BOUNCE_EXCLUDED.contains(topActivity.packageName)) return@mapNotNull null
            var taskDescription = taskInfo.taskDescription
                ?: Instances.iActivityTaskManager.getTaskDescription(taskInfo.taskId)
                ?: return@mapNotNull null
            var icon = runCatching { taskDescription.icon }.getOrNull()
            if (icon == null) {
                icon = Instances.packageManager.getActivityIcon(topActivity).toBitmap()
            }
            var label = taskDescription.label
            if (label == null) {
                label = Instances.packageManager.getActivityInfo(topActivity, 0)
                    .loadLabel(Instances.packageManager).toString()
            }
            val snapshot: Bitmap? = runCatching {
                val snap: TaskSnapshot? = try {
                    if (Build.VERSION.SDK_INT >= 34) {
                        Instances.iActivityTaskManager.getTaskSnapshot(taskInfo.taskId, true, true)
                    } else {
                        Instances.iActivityTaskManager.getTaskSnapshot(taskInfo.taskId, true)
                    }
                } catch (_: Throwable) {
                    Instances.iActivityTaskManager.getTaskSnapshot(taskInfo.taskId, true)
                }
                snap?.hardwareBuffer?.let { buffer ->
                    Bitmap.wrapHardwareBuffer(buffer, snap.colorSpace)
                }
            }.getOrNull()
            RecentTaskInfo(icon, taskInfo.taskId, label, snapshot, topActivity.packageName)
        }
    }

    private fun releaseMirrors(map: HashMap<SurfaceControl, SurfaceControl>) {
        map.values.forEach { sc ->
            try {
                mTransaction.apply {
                    invokeMethod("remove", args(sc), argTypes(SurfaceControl::class.java))
                }.apply()
            } catch (_: Throwable) {
            }
            sc.release()
        }
        map.clear()
    }

    private fun bindShellManager() {
        val bound = try {
            CoreManagerService.systemContext.bindService(
                Intent(ShellManagerService::class.java.name).apply {
                    setPackage(BuildConfig.APPLICATION_ID)
                },
                mServiceConnection,
                Context.BIND_AUTO_CREATE
            )
        } catch (e: Throwable) {
            log(TAG, "bind ShellManager failed:", e)
            false
        }
        logDebug(TAG, "bind ShellManagerService requested=$bound")
    }

    private fun invokeShellManager(op: String, block: (IShellManager) -> Unit) {
        val sm = mShellManager ?: return
        try {
            if (!sm.asBinder().isBinderAlive) {
                mShellManager = null
                return
            }
            block(sm)
        } catch (e: Throwable) {
            log(TAG, "$op failed:", e)
            mShellManager = null
        }
    }

    // endregion

    private inner class TaskStackListener : ITaskStackListener.Stub() {
        override fun onTaskStackChanged() {
            if (mIsDestroying) return
            mHandler.removeCallbacks(mDebouncedReclaim)
            mHandler.postDelayed(mDebouncedReclaim, RECLAIM_DEBOUNCE_MS)
            // Only notify AA when pane packages actually change (debounced). Never push ratio.
            scheduleNotifySplitState()
            schedulePersistSnapshot()
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
            trackPackage(pkg, 0)
            if (!BOUNCE_EXCLUDED.contains(pkg)) {
                mHandler.post {
                    if (isTaskOnAaDisplay(taskId)) {
                        mVdTaskIds.add(taskId)
                        mVdPackages.add(pkg)
                    } else if (mVdPackages.contains(pkg)) {
                        mHandler.removeCallbacks(mDebouncedReclaim)
                        mHandler.postDelayed(mDebouncedReclaim, RECLAIM_DEBOUNCE_MS)
                    }
                }
            }
        }

        override fun onTaskRemoved(taskId: Int) {
            mVdTaskIds.remove(taskId)
            if (!mIsDestroying) {
                mHandler.removeCallbacks(mDebouncedReclaim)
                mHandler.postDelayed(mDebouncedReclaim, RECLAIM_DEBOUNCE_MS)
            }
        }

        override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
            val displayId = try {
                val field = taskInfo.javaClass.getField("displayId")
                field.getInt(taskInfo)
            } catch (_: Throwable) {
                return
            }
            paneForDisplayId(displayId)?.let { pane -> mFocusedPane = pane }
        }

        override fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo?) {}
        override fun onActivityRequestedOrientationChanged(taskId: Int, requestedOrientation: Int) {}
        override fun onTaskRemovalStarted(taskInfo: ActivityManager.RunningTaskInfo?) {}
        override fun onTaskProfileLocked(taskInfo: ActivityManager.RunningTaskInfo?) {}
        override fun onTaskProfileLocked(taskInfo: ActivityManager.RunningTaskInfo?, userId: Int) {}
        override fun onTaskSnapshotChanged(taskId: Int, snapshot: TaskSnapshot?) {}
        override fun onBackPressedOnTaskRoot(taskInfo: ActivityManager.RunningTaskInfo?) {}
        override fun onTaskDisplayChanged(taskId: Int, newDisplayId: Int) {
            val pkg = findPackageForTask(taskId)
            AndroidHook.FuckAppUseApplicationContext.onTaskDisplayChanged(pkg, newDisplayId)
            if (isAaVirtualDisplay(newDisplayId)) {
                mVdTaskIds.add(taskId)
                pkg?.let { mVdPackages.add(it) }
            } else if (newDisplayId == Display.DEFAULT_DISPLAY) {
                // During intentional swipe-off / close, suppress is armed and ownership is
                // already dropped on the controller thread — do not re-arm reclaim or the
                // task snaps straight back onto the VD (~200ms later).
                if (SystemClock.uptimeMillis() < mSuppressReclaimUntil) return
                mHandler.removeCallbacks(mDebouncedReclaim)
                mHandler.postDelayed(mDebouncedReclaim, RECLAIM_DEBOUNCE_MS)
            }
        }

        override fun onRecentTaskListUpdated() {}
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
}
