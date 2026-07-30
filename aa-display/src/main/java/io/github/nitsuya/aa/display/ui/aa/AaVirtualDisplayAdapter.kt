package io.github.nitsuya.aa.display.ui.aa

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
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.*
import android.view.*
import android.window.TaskSnapshot
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toBitmap
import com.github.kyuubiran.ezxhelper.utils.*
import de.robv.android.xposed.XSharedPreferences
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.model.RecentTask
import io.github.nitsuya.aa.display.model.RecentTaskInfo
import io.github.nitsuya.aa.display.service.ShellManagerService
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.IShellManager
import io.github.nitsuya.aa.display.xposed.TipUtil
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.template.bases.runMain


class AaVirtualDisplayAdapter(
      private val context: Context
    , private val config: XSharedPreferences?
    , private val onReady: (suspend AaVirtualDisplayAdapter.(it:AaVirtualDisplayAdapter) -> Unit)
) {
    companion object {
        const val TAG = "AADisplay_AaVirtualDisplayAdapter"
        private const val WINDOWING_MODE_UNDEFINED = 0
        private const val WINDOWING_MODE_FULLSCREEN = 1
        private const val WINDOWING_MODE_PINNED = 2
        private const val WINDOWING_MODE_SPLIT_SCREEN_PRIMARY = 3
        private const val WINDOWING_MODE_SPLIT_SCREEN_SECONDARY = 4
        private const val WINDOWING_MODE_FREEFORM = 5
        private const val WINDOWING_MODE_MULTI_WINDOW = 6
        private const val DISPLAY_IME_POLICY_LOCAL = 0

        /** Package names to ignore in recent task list (also see BOUNCE_EXCLUDED_PACKAGES). */
        private val IGNORE_RECENT_PACKAGE = setOf(
            BuildConfig.APPLICATION_ID,
            "android",
            "com.android.systemui",
            "com.android.launcher3",
            // Samsung One UI Home — phone HOME + VD SECONDARY_HOME share this package.
            "com.sec.android.app.launcher",
        )

        /** WindowConfiguration.ACTIVITY_TYPE_HOME — system desktop tasks must not be closable in UI. */
        private const val ACTIVITY_TYPE_HOME = 2

        private val BLOCKED_HOME_PACKAGES = setOf(
            BuildConfig.APPLICATION_ID,
            "android",
            "com.android.internal.app",
            "com.android.settings"
        )

        private val MULTI_WINDOW_MODES = setOf(
            WINDOWING_MODE_SPLIT_SCREEN_PRIMARY,
            WINDOWING_MODE_SPLIT_SCREEN_SECONDARY,
            WINDOWING_MODE_FREEFORM,
            WINDOWING_MODE_MULTI_WINDOW
        )

        /**
         * OneUI split stages (not lone FREEFORM). While these are present, never force freeform
         * bounds/reclaim — that fights the divider and snaps the ratio back.
         */
        private val SPLIT_STAGE_MODES = setOf(
            WINDOWING_MODE_SPLIT_SCREEN_PRIMARY,
            WINDOWING_MODE_SPLIT_SCREEN_SECONDARY,
            WINDOWING_MODE_MULTI_WINDOW
        )

        /** ActivityTaskManager.RESIZE_MODE_SYSTEM — resize without preserving window. */
        private const val RESIZE_MODE_SYSTEM = 0
        /** Freeform window as fraction of VD size (centered inset so caption is visible). */
        private const val FREEFORM_INSET_RATIO = 0.88f
        /** After split bounce/reclaim, do not force FREEFORM (would collapse OneUI split). */
        private const val SUPPRESS_ENSURE_FREEFORM_MS = 1800L
        /** After intentional close/replace, keep reclaim from resurrecting the vacated package. */
        private const val SUPPRESS_RECLAIM_AFTER_REPLACE_MS = 2500L
        /**
         * After an app enters PiP (WINDOWING_MODE_PINNED), suppress reclaim/bounce so we do not
         * pull the pinned window back onto the AA VD (that poisons OneUI split/freeform).
         */
        private const val SUPPRESS_RECLAIM_AFTER_PIP_MS = 2500L

        private val ENSURE_FREEFORM_DELAYS_MS = longArrayOf(0L, 200L, 500L, 1000L, 1800L)
        private val RECLAIM_FOLLOWUP_DELAYS_MS = longArrayOf(0L, 400L, 1000L)
        /** Debounce empty OneUI stage-shell cleanup (thermal abort leaves them on the VD). */
        private const val EMPTY_SPLIT_CLEANUP_MIN_INTERVAL_MS = 400L
        /** Require shells to stay empty this long so split-entry races are not wiped. */
        private const val EMPTY_SPLIT_CONFIRM_MS = 600L
        private val EMPTY_SPLIT_CLEANUP_FOLLOWUP_DELAYS_MS = longArrayOf(0L, 350L, 800L, 1400L)
        private val EMPTY_SPLIT_CLEANUP_TOKEN = Any()

        /**
         * Never bounce these phone-side surfaces back onto the VD.
         * OneUI split entry often opens an Apps/Home chooser ("应用…") on DEFAULT_DISPLAY.
         */
        private val BOUNCE_EXCLUDED_PACKAGES = setOf(
            BuildConfig.APPLICATION_ID,
            "android",
            "com.android.systemui",
            "com.android.launcher3",
            "com.sec.android.app.launcher",
            "com.samsung.android.app.appsedge",
            "com.samsung.android.apps.applock",
            "com.samsung.android.app.galaxyfinder",
            "com.samsung.android.honeyboard",
            "com.samsung.android.knox.containercore"
        )
    }

    /** Default launch package name: the app package to launch when virtual display is created, can be null */
    private var mLauncherPackage: String? = null

    /** Home package for AADisplay task-view behavior, internally synced to launch package. */
    private var mHomePackage: String? = null
    
    /** Task ID of the Home package */
    private var mHomeTaskId: Int? = null
    
    /** Task ID of the default launch package */
    private var mLauncherPackageTaskId: Int? = null
    private var mIsDestroying = false
    private val mTrackedPackageUsers = linkedMapOf<String, MutableSet<Int>>()
    private val mTaskStackListener = TaskStackListener()
    /** Tasks last known on the AA virtual display (for OneUI split bounce-back). */
    private val mVdTaskIds = mutableSetOf<Int>()
    /**
     * App packages owned by the VD session. OneUI split often recreates tasks with new IDs on the
     * phone, so package-based reclaim is required (task-id tracking alone misses amapauto etc.).
     */
    private val mVdPackages = mutableSetOf<String>()
    /** Ignore onTaskDisplayChanged bounce while we intentionally move VD ↔ phone. */
    private var mSuppressDisplayBounceUntil = 0L
    /**
     * After bounce/reclaim for OneUI split, skip ensureFreeform so we do not force FREEFORM
     * over an in-progress MULTI_WINDOW / split transition.
     */
    private var mSuppressEnsureFreeformUntil = 0L
    private val mLastFreeformEnsureAt = mutableMapOf<Int, Long>()
    private val mLastDisplayBounceAt = mutableMapOf<Int, Long>()
    private var mLastReclaimAt = 0L
    private var mLastEmptySplitCleanupAt = 0L
    /** Uptime when empty split shells were first observed; 0 = none. */
    private var mEmptySplitShellsSeenAt = 0L
    /**
     * ATM display ids that are missing from DisplayManager (orphaned TaskDisplayAreas left after
     * a prior AA VD / split abort). OneUI StageCoordinator trees stuck here cause
     * `moveFreeformTaskToSplit` → "no display" until reboot.
     */
    private val mSuspectOrphanDisplayIds = mutableSetOf<Int>()
    private val mHandler = Handler(Looper.getMainLooper())
    private val mDebouncedStackReclaim = Runnable {
        reclaimVirtualDisplayTasks("stack-changed")
    }
    var mDisplayId = Display.INVALID_DISPLAY
    var mDensityDpi: Int = 0

    private val mTransaction = SurfaceControl.Transaction()
    private var mSurfaceControls = mutableMapOf<SurfaceControl, SurfaceControl>()
    public lateinit var mVirtualDisplay: VirtualDisplay
    private lateinit var mDisplayWindowManager: WindowManager
    private val mForceView = View(context)

    private var mDoInit = false
    private var mShellManager: IShellManager? = null
    private val mShellDeathRecipient = IBinder.DeathRecipient {
        log(TAG, "ShellManagerService binder died")
        mShellManager = null
    }
    private var mServiceConnection = object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            log(TAG, "ShellManagerService connected: $name")
            tryOrNull { mShellManager?.asBinder()?.unlinkToDeath(mShellDeathRecipient, 0) }
            mShellManager = IShellManager.Stub.asInterface(service)
            try {
                service.linkToDeath(mShellDeathRecipient, 0)
            } catch (e: Throwable) {
                log(TAG, "ShellManagerService linkToDeath failed:", e)
                mShellManager = null
            }
            if(mDoInit) return
            mDoInit = true
            // Scripts are best-effort; never block VD creation if the app process is already dead.
            invokeShellManager("createVirtualDisplayBefore") { it.createVirtualDisplayBefore() }
            runMain {
               onReady(this@AaVirtualDisplayAdapter)
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            log(TAG, "ShellManagerService disconnected: $name")
            tryOrNull { mShellManager?.asBinder()?.unlinkToDeath(mShellDeathRecipient, 0) }
            mShellManager = null
        }
    }

    init {
        refreshLauncherPackage("init")
        val bound = CoreManagerService.systemContext.bindService(
            Intent(ShellManagerService::class.java.name).apply {
                setPackage(BuildConfig.APPLICATION_ID)
            }
            , mServiceConnection
            , AppCompatActivity.BIND_AUTO_CREATE
        )
        log(TAG, "bind ShellManagerService requested=$bound")
    }

    fun setSurface(surface: Surface?){
        if (!::mVirtualDisplay.isInitialized) {
            log(TAG, "setSurface ignored before virtual display init: surface=${surface != null}")
            return
        }
        log(TAG, "setSurface: surface=${surface != null}, display=$mDisplayId")
        mVirtualDisplay.surface = surface
    }

    @SuppressLint("WrongConstant")
    fun onConnected(width: Int, height: Int, densityDpi: Int, surface: Surface?, onVirtualDisplayCreated: ((Int) -> Unit)) {
        mIsDestroying = false
        mTrackedPackageUsers.clear()
        mVdTaskIds.clear()
        mVdPackages.clear()
        mLastFreeformEnsureAt.clear()
        mLastDisplayBounceAt.clear()
        mSuppressDisplayBounceUntil = 0L
        mSuppressEnsureFreeformUntil = 0L
        mLastReclaimAt = 0L
        mLastEmptySplitCleanupAt = 0L
        mEmptySplitShellsSeenAt = 0L
        mSuspectOrphanDisplayIds.clear()
        mHandler.removeCallbacks(mDebouncedStackReclaim)
        mHandler.removeCallbacksAndMessages(EMPTY_SPLIT_CLEANUP_TOKEN)
        refreshLauncherPackage("connect")
        trackPackage(mLauncherPackage, 0)
        trackPackage(mHomePackage, 0)
        val indent = Binder.clearCallingIdentity()
        try {
            mVirtualDisplay = Instances.displayManager.createVirtualDisplay(
                "AADisplay-${System.currentTimeMillis()}",
                width,
                height,
                densityDpi,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    or DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
                    or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                    or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    //or (1 shl 8) //DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
                    or (1 shl 10) //DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED
                    or (1 shl 11) //DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                    or (1 shl 12) //DisplayManager.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                    or (1 shl 13) //DisplayManager.VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
            )
        } finally {
            Binder.restoreCallingIdentity(indent)
        }
        mDisplayId = mVirtualDisplay.display.displayId
        mDensityDpi = densityDpi
        log(TAG, "virtual display created: id=$mDisplayId, ${width}x$height,$densityDpi, surface=${surface != null}, launcher=${mLauncherPackage.orEmpty()}")

        try {
            applyVirtualDisplayPolicies("connect")
        } catch (e : Throwable){
            log(TAG, "设置虚拟屏幕参数失败: ", e)
        }
        //mDisplayWindowManager = context.createDisplayContext(mVirtualDisplay.display).getSystemService(WindowManager::class.java).apply {
        mDisplayWindowManager = context.createDisplayContext(mVirtualDisplay.display).createWindowContext(mVirtualDisplay.display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null).getSystemService(WindowManager::class.java).apply {
            addView(
                mForceView,
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // KEEP_SCREEN_ON contributes STAY_AWAKE for this OWN_DISPLAY_GROUP so
                    // Samsung/OneUI will not DOZE/OFF the VD when the phone sleeps or times out
                    // (same class of signal VirtualDevice uses to stay BRIGHT on AA).
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSPARENT
                ).also {
                    it.gravity = Gravity.START or Gravity.TOP
                    it.screenOrientation = if (width > height) {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                    it.alpha = 0f
                    it.width = 0
                    it.height = 0
                }
            )
        }
        Instances.iActivityTaskManager.registerTaskStackListener(mTaskStackListener)
        // Phone-side empty split stages (left by PiP / aborted MW) block OneUI freeform→split.
        scheduleCleanupEmptySplitOrganizerTasks("connect")
        // When virtual display is created, launch default package first (if configured)
        if(mLauncherPackage != null) {
            startDefaultPackage()
        } else {
            // If no default package is configured, launch Home package as fallback
            startHomeLauncher()
        }
        onVirtualDisplayCreated(mDisplayId)
    }

    fun onReconnected(width: Int, height: Int, densityDpi: Int){
        mVirtualDisplay.resize(width, height, densityDpi)
        mDensityDpi = densityDpi
        refreshLauncherPackage("reconnect")
        trackPackage(mLauncherPackage, 0)
        trackPackage(mHomePackage, 0)
        // AA reconnect must re-assert OneUI-friendly display policies; otherwise split
        // only works until the first surface/policy churn after boot.
        try {
            applyVirtualDisplayPolicies("reconnect")
        } catch (e: Throwable) {
            log(TAG, "onReconnected display policies failed:", e)
        }
        scheduleCleanupEmptySplitOrganizerTasks("reconnect")
    }

    fun onDestroy() {
        // Tear down orphan StageCoordinator trees before flipping mIsDestroying (cleanup no-ops after).
        try {
            cleanupEmptySplitOrganizerTasks("destroy", force = true)
        } catch (e: Throwable) {
            log(TAG, "onDestroy orphan split cleanup failed:", e)
        }
        mIsDestroying = true
        mVdTaskIds.clear()
        mVdPackages.clear()
        mLastFreeformEnsureAt.clear()
        mLastDisplayBounceAt.clear()
        mSuppressDisplayBounceUntil = 0L
        mSuppressEnsureFreeformUntil = 0L
        mLastReclaimAt = 0L
        mLastEmptySplitCleanupAt = 0L
        mEmptySplitShellsSeenAt = 0L
        mSuspectOrphanDisplayIds.clear()
        mHandler.removeCallbacks(mDebouncedStackReclaim)
        mHandler.removeCallbacksAndMessages(EMPTY_SPLIT_CLEANUP_TOKEN)
        trackPackage(mLauncherPackage, 0)
        trackPackage(mHomePackage, 0)
        clearForcedVirtualDisplayDensity()
        val protectedPackages = linkedSetOf<String>().apply {
            add(BuildConfig.APPLICATION_ID)
            mLauncherPackage?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            mHomePackage?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            addAll(getForegroundPackagesOnDisplay(Display.DEFAULT_DISPLAY))
        }
        tryOrNull { Instances.iActivityTaskManager.unregisterTaskStackListener(mTaskStackListener) }
        tryOrNull {
            val taskInfos = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
            taskInfos.forEach { task ->
                trackPackageFromTask(task)
                removeTask(task.taskId)
            }
            mTrackedPackageUsers
                .filterKeys { pkg -> pkg.isNotBlank() && !protectedPackages.contains(pkg) }
                .forEach { (pkg, userIds) ->
                    userIds.ifEmpty { mutableSetOf(0) }.forEach { userId ->
                        try {
                            Instances.activityManagerHidden.forceStopPackageAsUser(pkg, userId)
                            log(TAG, "onDestroy forceStop: $pkg (user=$userId)")
                        } catch (e: Throwable) {
                            log(TAG, "onDestroy forceStop failed: $pkg (user=$userId)", e)
                        }
                    }
                }
            mTrackedPackageUsers.clear()
        }
        tryOrNull {
            if(mDisplayId != Display.INVALID_DISPLAY) {
                // Second pass after initial removals to catch tasks recreated during teardown races.
                val remains = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
                remains.forEach { task ->
                    removeTask(task.taskId)
                }
            }
        }
        // ShellManager may already be dead during teardown; never let this crash system_server.
        invokeShellManager("destroyVirtualDisplayAfter") { it.destroyVirtualDisplayAfter() }
        tryOrNull { mShellManager?.asBinder()?.unlinkToDeath(mShellDeathRecipient, 0) }
        mShellManager = null
        tryOrNull { CoreManagerService.systemContext.unbindService(mServiceConnection) }
        mSurfaceControls.values.forEach { it.release() }
        mSurfaceControls.clear()
        tryOrNull { mDisplayWindowManager.removeView(mForceView) }
        mVirtualDisplay.release()
        mDisplayId = Display.INVALID_DISPLAY
        mDensityDpi = 0
    }

    private fun getForegroundPackagesOnDisplay(displayId: Int): Set<String> {
        val tasks = try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        } catch (_: Throwable) {
            emptyList()
        }
        if (tasks.isEmpty()) return emptySet()
        val topMost = tasks.firstOrNull { it.topActivity != null }
        val packages = linkedSetOf<String>()
        topMost?.topActivity?.packageName?.let { pkg ->
            if (pkg.isNotBlank()) packages.add(pkg)
        }
        runCatching {
            topMost?.getObjectAs("baseActivity", ComponentName::class.java) as? ComponentName
        }.getOrNull()?.packageName?.let { pkg ->
            if (pkg.isNotBlank()) packages.add(pkg)
        }
        return packages
    }

    fun onTouch(event: MotionEvent) = injectInputEvent(event)

    fun onPressKey(action: Int) {
        injectInputEvent(createKeyEvent(KeyEvent.ACTION_DOWN, action))
        injectInputEvent(createKeyEvent(KeyEvent.ACTION_UP, action))
        if (action == KeyEvent.KEYCODE_BACK) {
            // Match Home-button behavior: if back navigation returns to home and a PiP task is left
            // pinned, remove it so the launcher view is clean. Never touch OneUI split/MW tasks.
            Handler(Looper.getMainLooper()).post {
                if (!shouldPreserveMultiWindowLayout()) {
                    clearPinnedTasksIfHomeFront("back")
                }
            }
            Handler(Looper.getMainLooper()).postDelayed({
                if (!shouldPreserveMultiWindowLayout()) {
                    clearPinnedTasksIfHomeFront("back-delay")
                }
            }, 350L)
        }
    }

    fun addMirror(surfaceControl: SurfaceControl){
        val sc = SurfaceControl::class.java.newInstance(args(), argTypes()) as SurfaceControl
        try{
            if(!Instances.iWindowManager.mirrorDisplay(mDisplayId, sc)){
                sc.release()
                return
            }
        } catch (e: Throwable){
            TipUtil.showToast("addMirror error: ${e.message}")
            log(TAG, "addMirror error:", e)
            sc.release()
            return
        }
        if (!sc.isValid) {
            sc.release()
            TipUtil.showToast("addMirror not Valid")
            return
        }
        try {
            mTransaction
                .apply {
                    invokeMethod("show", args(sc), argTypes(SurfaceControl::class.java))
                }
                .reparent(sc, surfaceControl)
                .apply()
        } catch (e: Throwable){
            log(TAG, "addMirror show error:", e)
            return
        }
        mSurfaceControls.put(surfaceControl, sc)?.release()
    }

    fun removeMirror(surfaceControl: SurfaceControl){
        mSurfaceControls.remove(surfaceControl)?.also {sc ->
            mTransaction.apply {
                invokeMethod("remove", args(sc), argTypes(SurfaceControl::class.java))
            }.apply()
            sc.release()
        }
    }

    fun getRecentTask(): RecentTask {
        return try{
            RecentTask(
                recentTaskInfo(0),
                if(mDisplayId == Display.INVALID_DISPLAY) emptyList() else recentTaskInfo(mDisplayId)
            )
        } catch (e: Throwable){
            log(TAG, "RecentTask Exception", e)
            RecentTask(emptyList(), emptyList())
        }
    }

    /**
     * Launch the app corresponding to Home key (usually the launcher)
     * Called when user presses Home key
     */
    fun startLauncher(){
        startHomeLauncher()
        // On some ROMs, moving Home to front auto-pins the previous video task (PiP).
        // Clean up pinned tasks on the AA virtual display so Home returns to a normal state.
        // Only clears WINDOWING_MODE_PINNED; split/MW tasks are never removed here.
        clearPinnedTasksOnDisplay("home")
        Handler(Looper.getMainLooper()).postDelayed({
            clearPinnedTasksOnDisplay("home-delay")
        }, 350L)
    }

    /**
     * Launch the app corresponding to Home package name
     * If the app is already running, bring it to front; otherwise start a new instance
     */
    private fun startHomeLauncher(){
        if(mHomePackage == null) {
            log(TAG, "startHomeLauncher skipped: no launcher package")
            return
        }
        if(mHomeTaskId != null){
            moveTaskToFront(mHomeTaskId!!)
        } else {
            startActivity(mHomePackage!!, 0)
        }
    }

    /**
     * Launch the app corresponding to default launch package name
     * Called when virtual display is created. If the app is already running, bring it to front; otherwise start a new instance
     */
    private fun startDefaultPackage(){
        if(mLauncherPackage == null) {
            log(TAG, "startDefaultPackage skipped: no launcher package")
            return
        }
        if(mLauncherPackageTaskId != null){
            moveTaskToFront(mLauncherPackageTaskId!!)
        } else {
            startActivity(mLauncherPackage!!, 0)
        }
    }

    fun startActivity(packageName: String, userId: Int): Boolean{
        if(mDisplayId == Display.INVALID_DISPLAY) return false
        // OneUI caption close is broken on the VD; replace the focused split pane instead.
        if (isOneUiSplitEnabled()
            && isDisplayInSplitStages()
            && packageName != mHomePackage
            && !IGNORE_RECENT_PACKAGE.contains(packageName)
        ) {
            val replaced = replaceFocusedSplitSide(packageName, userId)
            if (replaced) return true
            log(TAG, "startActivity: replaceSplitSide failed for $packageName; falling back")
        }
        val componentName = resolveLaunchComponent(packageName) ?: return false
        val launchAdjacent = shouldLaunchAdjacent(packageName)
        log(TAG, "startActivity: $componentName on display=$mDisplayId user=$userId adjacent=$launchAdjacent")
        if (launchAdjacent) {
            val adjacentOk = launchActivityAsUser(componentName, userId, adjacent = true)
            if (adjacentOk) return true
            log(TAG, "startActivity adjacent failed; falling back to non-adjacent launch")
        }
        return launchActivityAsUser(componentName, userId, adjacent = false)
    }

    /**
     * Replace the focused OneUI split pane with [packageName].
     * Used because OneUI's caption close/apps-picker does not work on the AA virtual display.
     */
    private fun replaceFocusedSplitSide(packageName: String, userId: Int): Boolean {
        val componentName = resolveLaunchComponent(packageName) ?: return false
        val sides = getSplitAppTasksOnDisplay()
        if (sides.isEmpty()) return false

        val already = sides.firstOrNull { it.second == packageName }
        if (already != null) {
            log(TAG, "replaceSplitSide: $packageName already on stage task=${already.first}; focus")
            return setFocusedTaskSafe(already.first) || moveTaskToFront(already.first)
        }

        val (victimTaskId, victimPkg) = sides.first()
        log(
            TAG,
            "replaceSplitSide: remove $victimPkg#$victimTaskId then adjacent-launch $packageName"
        )
        dismissSplitSideForReplace(victimTaskId, victimPkg)
        val adjacentOk = launchActivityAsUser(componentName, userId, adjacent = true)
        if (adjacentOk) return true
        log(TAG, "replaceSplitSide: adjacent launch failed; trying non-adjacent")
        return launchActivityAsUser(componentName, userId, adjacent = false)
    }

    /** Close one split pane and stop reclaim from pulling that package back. */
    private fun dismissSplitSideForReplace(taskId: Int, packageName: String) {
        val now = SystemClock.uptimeMillis()
        mSuppressDisplayBounceUntil = now + SUPPRESS_RECLAIM_AFTER_REPLACE_MS
        mSuppressEnsureFreeformUntil = now + SUPPRESS_ENSURE_FREEFORM_MS
        forgetVirtualDisplayOwnership(taskId, packageName)
        AndroidHook.FuckAppUseApplicationContext.clearPackageVirtualDisplay(packageName)
        untrackPackage(packageName)
        try {
            val removed = Instances.iActivityTaskManager.removeTask(taskId)
            log(TAG, "dismissSplitSideForReplace: removeTask($taskId)=$removed pkg=$packageName")
        } catch (e: Throwable) {
            log(TAG, "dismissSplitSideForReplace removeTask failed: task=$taskId", e)
        }
    }

    /**
     * App tasks currently filling OneUI left/right stages (not freeform/organizer containers).
     * Ordered top-first so index 0 is the focused pane. Defaults to the AA virtual display.
     */
    private fun getSplitAppTasksOnDisplay(
        displayId: Int = mDisplayId
    ): List<Pair<Int, String>> {
        if (displayId == Display.INVALID_DISPLAY) return emptyList()
        return try {
            val triples = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
                .mapNotNull { taskInfo ->
                    val pkg = taskInfo.topActivity?.packageName?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    if (isBounceExcludedPackage(pkg) || pkg == mHomePackage) return@mapNotNull null
                    val mode = getWindowingMode(taskInfo)
                    if (!isSplitStageMode(mode)) return@mapNotNull null
                    Triple(taskInfo.taskId, pkg, isCreatedByOrganizer(taskInfo))
                }
            // Keep top-first z-order; prefer leaf app tasks over organizer stage parents.
            val preferred = triples.filter { !it.third }.ifEmpty { triples }
            preferred.distinctBy { it.second }.map { it.first to it.second }
        } catch (e: Throwable) {
            log(TAG, "getSplitAppTasksOnDisplay($displayId) error:", e)
            emptyList()
        }
    }

    private fun isCreatedByOrganizer(taskInfo: Any): Boolean {
        val readers: List<() -> Boolean?> = listOf(
            {
                taskInfo.getObjectAs("createdByOrganizer", Boolean::class.javaPrimitiveType) as? Boolean
            },
            {
                taskInfo.getObjectAs("mCreatedByOrganizer", Boolean::class.javaPrimitiveType) as? Boolean
            },
            {
                taskInfo.getObjectAs("createdByOrganizer", Boolean::class.java) as? Boolean
            },
            {
                taskInfo.invokeMethod("isCreatedByOrganizer", args(), argTypes()) as? Boolean
            }
        )
        for (read in readers) {
            try {
                if (read() == true) return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun launchActivityAsUser(componentName: ComponentName, userId: Int, adjacent: Boolean): Boolean {
        return try {
            val flags = if (adjacent) {
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
            } else {
                Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val launchWindowingMode = resolveLaunchWindowingMode(
                packageName = componentName.packageName,
                adjacent = adjacent
            )
            AndroidHook.FuckAppUseApplicationContext.markPackageOnVirtualDisplay(
                componentName.packageName,
                mDisplayId
            )
            context.invokeMethod(
                "startActivityAsUser",
                args(
                    Intent().apply {
                        component = componentName
                        `package` = componentName.packageName
                        action = Intent.ACTION_VIEW
                        putExtra("displayId", mDisplayId)
                        setFlags(flags)
                    },
                    ActivityOptions.makeBasic().apply {
                        launchDisplayId = mDisplayId
                        this.invokeMethod("setCallerDisplayId", args(mDisplayId), argTypes(Integer.TYPE))
                        // Freeform gives OneUI the caption/handle bar for drag + split entry.
                        if (launchWindowingMode != WINDOWING_MODE_UNDEFINED) {
                            try {
                                this.invokeMethod(
                                    "setLaunchWindowingMode",
                                    args(launchWindowingMode),
                                    argTypes(Integer.TYPE)
                                )
                            } catch (e: Throwable) {
                                log(TAG, "setLaunchWindowingMode($launchWindowingMode) failed:", e)
                            }
                        }
                        if (launchWindowingMode == WINDOWING_MODE_FREEFORM) {
                            val bounds = buildFreeformInsetBounds()
                            if (bounds != null) {
                                try {
                                    launchBounds = bounds
                                } catch (e: Throwable) {
                                    log(TAG, "setLaunchBounds failed:", e)
                                }
                            }
                        }
                    }.toBundle(),
                    UserHandle::class.java.newInstance(
                        args(userId),
                        argTypes(Integer.TYPE)
                    )
                ), argTypes(Intent::class.java, Bundle::class.java, UserHandle::class.java)
            )
            // Launch options are often ignored on VD; correct fullscreen after the task appears.
            if (launchWindowingMode == WINDOWING_MODE_FREEFORM) {
                mHandler.postDelayed({
                    try {
                        Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
                            .filter { info ->
                                info.topActivity?.packageName == componentName.packageName
                            }
                            .forEach { info ->
                                markVirtualDisplayOwnership(info.taskId, componentName.packageName)
                                scheduleEnsureFreeform(info.taskId, "launchActivityAsUser", force = true)
                            }
                    } catch (e: Throwable) {
                        log(TAG, "post-launch ensureFreeform failed:", e)
                    }
                }, 300L)
            }
            true
        } catch (e: Throwable) {
            log(TAG, "launchActivityAsUser adjacent=$adjacent error:", e)
            false
        }
    }

    /**
     * Home stays fullscreen behind freeform apps; normal apps open freeform when OneUI split is on
     * so the caption bar (resize / split entry) is available. Adjacent launches leave mode unset.
     */
    private fun resolveLaunchWindowingMode(packageName: String, adjacent: Boolean): Int {
        if (!isOneUiSplitEnabled() || adjacent) return WINDOWING_MODE_UNDEFINED
        if (packageName == mHomePackage || packageName == mLauncherPackage) {
            return WINDOWING_MODE_FULLSCREEN
        }
        return WINDOWING_MODE_FREEFORM
    }

    private fun shouldLaunchAdjacent(packageName: String): Boolean {
        if (!isOneUiSplitEnabled()) return false
        if (packageName == mHomePackage) return false
        if (IGNORE_RECENT_PACKAGE.contains(packageName)) return false
        val foreground = getForegroundPackagesOnDisplay(mDisplayId)
        if (foreground.isEmpty()) return false
        if (foreground.all { it == mHomePackage || IGNORE_RECENT_PACKAGE.contains(it) }) return false
        if (foreground.contains(packageName) && foreground.size == 1) return false
        return true
    }

    private fun refreshLauncherPackage(reason: String) {
        config?.reload()
        val configured = AADisplayConfig.LauncherPackage.get(config)
        val resolved = resolveLauncherPackage(configured)
        if (mLauncherPackage != resolved) {
            log(TAG, "launcher[$reason]: configured=${configured.orEmpty()}, resolved=${resolved.orEmpty()}")
        }
        mLauncherPackage = resolved
        mHomePackage = resolved
    }

    private fun resolveLauncherPackage(configured: String?): String? {
        val requested = configured?.trim()?.takeIf { it.isNotEmpty() }
        if (requested != null && resolveLaunchComponent(requested) != null) {
            return requested
        }
        if (requested != null) {
            log(TAG, "configured launcher unavailable: $requested")
        }

        val defaultHome = resolveDefaultHomePackage()
        val homeCandidates = queryHomeLauncherPackages()
        val fallback = homeCandidates.firstOrNull { pkg -> pkg != defaultHome }

        log(
            TAG,
            "launcher fallback: defaultHome=${defaultHome.orEmpty()}, candidates=${homeCandidates.joinToString()}, selected=${fallback.orEmpty()}"
        )
        return fallback
    }

    private fun resolveLaunchComponent(packageName: String): ComponentName? {
        val requested = packageName.trim()
        if (requested.isEmpty()) return null
        return if (requested.contains("/")) {
            val packageComponent = requested.split("/", limit = 2)
            if (packageComponent.size != 2) return null
            ComponentName.createRelative(packageComponent[0], packageComponent[1])
        } else {
            Instances.packageManager.getLaunchIntentForPackage(requested)?.component
        }
    }

    private fun resolveDefaultHomePackage(): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(
                homeIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        } ?: return null

        val pkg = resolveInfo.activityInfo?.packageName?.trim().orEmpty()
        val cls = resolveInfo.activityInfo?.name?.trim().orEmpty()
        return pkg.takeIf { isHomeCandidateAllowed(it, cls) }
    }

    private fun queryHomeLauncherPackages(): List<String> {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val candidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                homeIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
        }

        return candidates
            .mapNotNull { resolveInfo ->
                val pkg = resolveInfo.activityInfo?.packageName?.trim().orEmpty()
                val cls = resolveInfo.activityInfo?.name?.trim().orEmpty()
                pkg.takeIf { isHomeCandidateAllowed(it, cls) && resolveLaunchComponent(it) != null }
            }
            .distinct()
    }

    private fun isHomeCandidateAllowed(pkg: String, cls: String): Boolean {
        if (pkg.isBlank() || BLOCKED_HOME_PACKAGES.contains(pkg)) return false
        if (cls.contains("FallbackHome", ignoreCase = true)) return false
        if (cls.contains("ResolverActivity", ignoreCase = true)) return false
        return true
    }

    fun startTaskId(taskId: Int?, packageName: String, userId: Int): Boolean {
        if(mDisplayId == Display.INVALID_DISPLAY) return false
        if(taskId == null){
            return startActivity(packageName, userId)
        }
        return try {
            moveTaskId(taskId, true)
        } catch (e: Throwable){
            log(TAG,"startTaskId error:", e)
            startActivity(packageName, userId)
        }
    }

    fun moveTaskId(taskId: Int, isVirtualDisplay: Boolean): Boolean {
        if(mDisplayId == Display.INVALID_DISPLAY) return false
        val packageName = findPackageForTask(taskId)
        // Swiping a phone task onto the VD while split: replace focused pane (caption close is dead).
        if (isVirtualDisplay
            && isOneUiSplitEnabled()
            && isDisplayInSplitStages()
            && !packageName.isNullOrBlank()
        ) {
            log(TAG, "moveTaskId: split active → replaceSplitSide with $packageName (task=$taskId)")
            return startActivity(packageName, 0)
        }
        val targetDisplayId = if (isVirtualDisplay) mDisplayId else Display.DEFAULT_DISPLAY
        log(
            TAG,
            "moveTaskId: task=$taskId pkg=${packageName.orEmpty()} -> display=$targetDisplayId (vd=$isVirtualDisplay)"
        )
        // Intentional recent-task swipe must not be bounced back by onTaskDisplayChanged.
        mSuppressDisplayBounceUntil = SystemClock.uptimeMillis() + 2000L
        if (!isVirtualDisplay) {
            forgetVirtualDisplayOwnership(taskId, packageName)
        }
        try {
            Instances.iActivityTaskManager.moveRootTaskToDisplay(taskId, targetDisplayId)
        } catch (e: Throwable){
            log(TAG,"moveTaskId moveRootTaskToDisplay error:", e)
        }
        // Cross-display move must update DPI map immediately (bindApplication will not re-run).
        if (isVirtualDisplay) {
            markVirtualDisplayOwnership(taskId, packageName)
            AndroidHook.FuckAppUseApplicationContext.markPackageOnVirtualDisplay(packageName, mDisplayId)
            // Intentional move back onto VD: always allow freeform ensure (clear split suppress).
            mSuppressEnsureFreeformUntil = 0L
            scheduleEnsureFreeform(taskId, "moveTaskId", force = true)
        } else {
            AndroidHook.FuckAppUseApplicationContext.clearPackageVirtualDisplay(packageName)
        }
        // Never use setFocusedTask here — that only switches panes inside an existing MW layout
        // and leaves VD↔phone moves half-applied (split + density chaos).
        return bringTaskToFrontOnDisplay(taskId)
    }

    @SuppressLint("MissingPermission")
    fun moveTaskToFront(taskId: Int): Boolean {
        if(mDisplayId == Display.INVALID_DISPLAY) return false
        // Preserve OneUI split only when the task is already on this virtual display.
        if (isOneUiSplitEnabled()
            && isDisplayInMultiWindow()
            && isTaskOnVirtualDisplay(taskId)
        ) {
            if (setFocusedTaskSafe(taskId)) {
                log(TAG, "moveTaskToFront: setFocusedTask($taskId) to preserve multi-window")
                return true
            }
        }
        return bringTaskToFrontOnDisplay(taskId)
    }

    private fun bringTaskToFrontOnDisplay(taskId: Int): Boolean {
        return try {
            Instances.activityManager.moveTaskToFront(taskId, 0)
            true
        } catch (e: Throwable){
            log(TAG,"moveTaskToFront error:", e)
            false
        }
    }

    fun removeTask(taskId: Int): Boolean {
        if(mDisplayId == Display.INVALID_DISPLAY) return false
        val packageName = findPackageForTask(taskId)
        val onVirtualDisplay = isTaskOnVirtualDisplay(taskId)
        return try {
            // Intentional close: forget ownership first so onTaskRemoved reclaim cannot resurrect.
            if (onVirtualDisplay) {
                mSuppressDisplayBounceUntil =
                    SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_AFTER_REPLACE_MS
                forgetVirtualDisplayOwnership(taskId, packageName)
            }
            val removed = Instances.iActivityTaskManager.removeTask(taskId)
            if (removed && onVirtualDisplay && !packageName.isNullOrBlank()) {
                if (!hasPackageTaskOnDisplay(packageName, mDisplayId)) {
                    AndroidHook.FuckAppUseApplicationContext.clearPackageVirtualDisplay(packageName)
                    untrackPackage(packageName)
                }
            }
            removed
        } catch (e: Throwable){
            log(TAG,"removeTask error:", e)
            false
        }
    }

    /**
     * Move the second task to front
     * If the second task is the Home package app, move the third task to front instead
     * When OneUI split is active, cycle focus among multi-window tasks instead of collapsing split.
     */
    fun moveSecondTaskToFront(){
        if(mDisplayId == Display.INVALID_DISPLAY)
            return
        if (isOneUiSplitEnabled() && isDisplayInMultiWindow()) {
            val mwTasks = getMultiWindowTasksOnDisplay()
            if (mwTasks.size >= 2) {
                val focused = mwTasks.firstOrNull()?.taskId
                val next = mwTasks.firstOrNull { it.taskId != focused } ?: mwTasks[1]
                log(TAG, "moveSecondTaskToFront: focus multi-window task=${next.taskId}")
                setFocusedTaskSafe(next.taskId)
                return
            }
            if (mwTasks.size == 1) {
                setFocusedTaskSafe(mwTasks[0].taskId)
                return
            }
        }
        val allRootTaskInfosOnDisplay = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId).filter { i -> i.topActivity != null }
        if(allRootTaskInfosOnDisplay.size < 2){
            return
        }
        moveTaskToFront(
            if(allRootTaskInfosOnDisplay.size == 2 || allRootTaskInfosOnDisplay[1].topActivity!!.packageName != mHomePackage){
                allRootTaskInfosOnDisplay[1].taskId
            } else {
                allRootTaskInfosOnDisplay[2].taskId
            }
        )
    }

    private fun isOneUiSplitEnabled(): Boolean {
        config?.reload()
        return AADisplayConfig.EnableOneUiSplit.get(config)
    }

    private fun shouldPreserveMultiWindowLayout(): Boolean {
        return isOneUiSplitEnabled() && isDisplayInMultiWindow()
    }

    private fun getWindowingMode(taskInfo: Any): Int? {
        return try {
            taskInfo.invokeMethod("getWindowingMode", args(), argTypes()) as? Int
        } catch (_: Throwable) {
            null
        }
    }

    private fun isMultiWindowMode(mode: Int?): Boolean {
        return mode != null && MULTI_WINDOW_MODES.contains(mode)
    }

    private fun isSplitStageMode(mode: Int?): Boolean {
        return mode != null && SPLIT_STAGE_MODES.contains(mode)
    }

    private fun isDisplayInMultiWindow(): Boolean {
        if (mDisplayId == Display.INVALID_DISPLAY) return false
        return try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
                .any { taskInfo ->
                    taskInfo.topActivity != null && isMultiWindowMode(getWindowingMode(taskInfo))
                }
        } catch (e: Throwable) {
            log(TAG, "isDisplayInMultiWindow error:", e)
            false
        }
    }

    /**
     * True when OneUI left/right stages still host a real app on the VD.
     * Empty organizer shells (sz=0 after thermal / APP_DOES_NOT_SUPPORT exit) must not count —
     * they poison freeform ensure and wrongly trigger replaceSplitSide.
     */
    private fun isDisplayInSplitStages(): Boolean {
        return getSplitAppTasksOnDisplay().isNotEmpty()
    }

    private fun scheduleCleanupEmptySplitOrganizerTasks(reason: String) {
        if (!isOneUiSplitEnabled() || mDisplayId == Display.INVALID_DISPLAY) return
        mHandler.removeCallbacksAndMessages(EMPTY_SPLIT_CLEANUP_TOKEN)
        val now = SystemClock.uptimeMillis()
        for (delay in EMPTY_SPLIT_CLEANUP_FOLLOWUP_DELAYS_MS) {
            val taggedReason = if (delay == 0L) reason else "$reason-$delay"
            mHandler.postAtTime(
                { cleanupEmptySplitOrganizerTasks(taggedReason) },
                EMPTY_SPLIT_CLEANUP_TOKEN,
                now + delay
            )
        }
    }

    /**
     * After OneUI aborts split (SSRM / PiP / APP_DOES_NOT_SUPPORT_MULTIWINDOW), empty stage
     * containers often remain — on the AA VD, the phone DEFAULT_DISPLAY, and **orphaned**
     * ATM TaskDisplayAreas (display ids still visible to ATM but gone from DisplayManager,
     * e.g. a prior AA VD session). Those ghost trees occupy OneUI main/side stages so
     * `moveFreeformTaskToSplit` fails with "no display" after reconnect.
     *
     * Shells must stay empty for [EMPTY_SPLIT_CONFIRM_MS] so an in-progress split entry
     * (briefly empty side stage) is not destroyed — unless [force] (destroy path).
     */
    private fun cleanupEmptySplitOrganizerTasks(reason: String, force: Boolean = false) {
        if (mIsDestroying && !force) return
        if (!isOneUiSplitEnabled()) return

        val zombieIds = linkedSetOf<Int>()
        val displayIds = collectDisplaysForEmptySplitCleanup()
        for (displayId in displayIds) {
            val orphan = !isDisplayKnownToDisplayManager(displayId)
            // Live displays: only wipe when no real split apps remain.
            // Orphan displays: also wipe chooser-only (AppsEdge) trees — they poison global stages.
            if (!orphan && getSplitAppTasksOnDisplay(displayId).isNotEmpty()) continue
            zombieIds += if (orphan) {
                collectOrphanDisplaySplitTaskIds(displayId, reason)
            } else {
                collectEmptySplitOrganizerTaskIdsOnDisplay(displayId, reason, treatChooserAsEmpty = false)
            }
        }

        if (zombieIds.isEmpty()) {
            mEmptySplitShellsSeenAt = 0L
            return
        }

        val now = SystemClock.uptimeMillis()
        if (!force) {
            if (mEmptySplitShellsSeenAt == 0L) {
                mEmptySplitShellsSeenAt = now
                log(
                    TAG,
                    "cleanupEmptySplit[$reason]: empty organizer shells=${zombieIds.joinToString()} — confirm in ${EMPTY_SPLIT_CONFIRM_MS}ms"
                )
                return
            }
            if (now - mEmptySplitShellsSeenAt < EMPTY_SPLIT_CONFIRM_MS) return
            if (now - mLastEmptySplitCleanupAt < EMPTY_SPLIT_CLEANUP_MIN_INTERVAL_MS) return
        }
        mLastEmptySplitCleanupAt = now

        // Remove leaves before parents (higher ids are usually stages; parent root last).
        var removed = 0
        for (taskId in zombieIds.sortedDescending()) {
            if (removeOrganizerTaskQuietly(taskId, reason)) removed++
        }
        mEmptySplitShellsSeenAt = 0L
        if (removed > 0) {
            log(TAG, "cleanupEmptySplit[$reason]: removed $removed empty organizer task(s)")
            // Drop suspects that no longer host any root tasks.
            mSuspectOrphanDisplayIds.removeAll { id ->
                try {
                    Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(id).isEmpty()
                } catch (_: Throwable) {
                    true
                }
            }
        }
    }

    /**
     * Displays to scan for zombie StageCoordinator trees: AA VD, phone, tracked orphans,
     * plus any ATM display that hosts organizer/split stages (discovers ghost TDAs).
     */
    private fun collectDisplaysForEmptySplitCleanup(): Set<Int> {
        val ids = linkedSetOf(Display.DEFAULT_DISPLAY)
        if (mDisplayId != Display.INVALID_DISPLAY) ids += mDisplayId
        ids += mSuspectOrphanDisplayIds
        for (info in getAllRootTaskInfosReflect()) {
            val displayId = readTaskDisplayId(info) ?: continue
            if (displayId == Display.INVALID_DISPLAY) continue
            if (!isDisplayKnownToDisplayManager(displayId) ||
                isCreatedByOrganizer(info) ||
                isSplitStageMode(getWindowingMode(info)) ||
                isSplitChooserPackage(info.topActivity?.packageName)
            ) {
                ids += displayId
                if (!isDisplayKnownToDisplayManager(displayId)) {
                    mSuspectOrphanDisplayIds += displayId
                }
            }
        }
        return ids
    }

    private fun isDisplayKnownToDisplayManager(displayId: Int): Boolean {
        if (displayId == Display.DEFAULT_DISPLAY) return true
        return try {
            Instances.displayManager.getDisplays().any { it.displayId == displayId }
        } catch (_: Throwable) {
            true
        }
    }

    private fun isSplitChooserPackage(packageName: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        return pkg == "com.samsung.android.app.appsedge" ||
            pkg.startsWith("com.samsung.android.app.appsedge.")
    }

    @Suppress("UNCHECKED_CAST")
    private fun getAllRootTaskInfosReflect(): List<ActivityTaskManager.RootTaskInfo> {
        return try {
            val result = Instances.iActivityTaskManager.invokeMethod(
                "getAllRootTaskInfos",
                args(),
                argTypes()
            ) as? List<*>
            result?.filterIsInstance<ActivityTaskManager.RootTaskInfo>() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun readTaskDisplayId(taskInfo: Any): Int? {
        val readers: List<() -> Int?> = listOf(
            { taskInfo.getObjectAs("displayId", Int::class.javaPrimitiveType) as? Int },
            { taskInfo.getObjectAs("displayId", Int::class.java) as? Int },
            { taskInfo.invokeMethod("getDisplayId", args(), argTypes()) as? Int }
        )
        for (read in readers) {
            try {
                val id = read()
                if (id != null) return id
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /**
     * Ghost TaskDisplayArea: wipe every organizer / split-stage / AppsEdge chooser task.
     * Real user apps stuck here are already broken (DM has no such display).
     */
    private fun collectOrphanDisplaySplitTaskIds(displayId: Int, reason: String): List<Int> {
        val tasks = try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        } catch (e: Throwable) {
            log(TAG, "cleanupEmptySplit[$reason] orphan list display=$displayId failed:", e)
            return emptyList()
        }
        if (tasks.isEmpty()) return emptyList()
        val toRemove = linkedSetOf<Int>()
        for (taskInfo in tasks) {
            val mode = getWindowingMode(taskInfo)
            val chooser = isSplitChooserPackage(taskInfo.topActivity?.packageName)
            if (!isCreatedByOrganizer(taskInfo) && !isSplitStageMode(mode) && !chooser) continue
            toRemove.add(taskInfo.taskId)
            readChildTaskIds(taskInfo)?.forEach { toRemove.add(it) }
        }
        // Also match empty-shell patterns (parent with only empty stage children).
        toRemove += collectEmptySplitOrganizerTaskIds(tasks, treatChooserAsEmpty = true)
        if (toRemove.isNotEmpty()) {
            log(
                TAG,
                "cleanupEmptySplit[$reason]: orphan display=$displayId tasks=${toRemove.joinToString()}"
            )
        }
        return toRemove.toList()
    }

    private fun collectEmptySplitOrganizerTaskIdsOnDisplay(
        displayId: Int,
        reason: String,
        treatChooserAsEmpty: Boolean = false
    ): List<Int> {
        val tasks = try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        } catch (e: Throwable) {
            log(TAG, "cleanupEmptySplit[$reason] list display=$displayId failed:", e)
            return emptyList()
        }
        return collectEmptySplitOrganizerTaskIds(tasks, treatChooserAsEmpty)
    }

    /**
     * Empty stage shells and their StageCoordinator parent. Children may only appear in
     * childTaskIds (not as separate RootTaskInfo entries on some OneUI builds).
     *
     * Also matches phone-side zombies left after PiP/split abort: parent may be fullscreen
     * organizer root `#3` with empty `#4/#5` multi-window stages (blocks moveFreeformTaskToSplit).
     */
    private fun collectEmptySplitOrganizerTaskIds(
        tasks: List<ActivityTaskManager.RootTaskInfo>,
        treatChooserAsEmpty: Boolean = false
    ): List<Int> {
        val toRemove = linkedSetOf<Int>()
        for (taskInfo in tasks) {
            val topPkg = taskInfo.topActivity?.packageName
            if (topPkg != null) {
                // Live app in this root — leave it. Chooser-only counts as empty when requested.
                if (!(treatChooserAsEmpty && isSplitChooserPackage(topPkg))) continue
            }

            val mode = getWindowingMode(taskInfo)
            // Empty left/right stage roots — do not require createdByOrganizer (flag flaky on phone).
            if (isSplitStageMode(mode)) {
                val childIds = readChildTaskIds(taskInfo)
                val hasAppChild = childIds?.any {
                    taskHasAppActivity(it, tasks, treatChooserAsEmpty)
                } == true
                if (!hasAppChild) {
                    childIds?.forEach { id ->
                        if (!taskHasAppActivity(id, tasks, treatChooserAsEmpty)) toRemove.add(id)
                    }
                    toRemove.add(taskInfo.taskId)
                }
                continue
            }

            if (!isCreatedByOrganizer(taskInfo)) {
                // Non-organizer parent that only hosts empty split stages (Samsung #3→#4/#5).
                val childIds = readChildTaskIds(taskInfo) ?: continue
                if (childIds.isEmpty()) continue
                if (isZombieSplitChildTree(childIds, tasks, treatChooserAsEmpty)) {
                    childIds.forEach { toRemove.add(it) }
                    toRemove.add(taskInfo.taskId)
                }
                continue
            }

            val childIds = readChildTaskIds(taskInfo)
            if (childIds == null || childIds.isEmpty()) {
                if (mode == null ||
                    mode == WINDOWING_MODE_FULLSCREEN ||
                    mode == WINDOWING_MODE_UNDEFINED ||
                    isSplitStageMode(mode) ||
                    (treatChooserAsEmpty && isSplitChooserPackage(topPkg))
                ) {
                    // Orphan empty organizer root (parent already lost children).
                    toRemove.add(taskInfo.taskId)
                }
                continue
            }

            if (isZombieSplitChildTree(childIds, tasks, treatChooserAsEmpty) ||
                childIds.all { !taskHasAppActivity(it, tasks, treatChooserAsEmpty) }
            ) {
                // Parent of only-empty children: zombie split tree (e.g. #3 → #4/#5).
                toRemove.addAll(
                    childIds.filter { !taskHasAppActivity(it, tasks, treatChooserAsEmpty) }
                )
                toRemove.add(taskInfo.taskId)
            }
        }
        return toRemove.toList()
    }

    /** True when every child has no app and at least one child is a split-stage shell. */
    private fun isZombieSplitChildTree(
        childIds: IntArray,
        known: List<ActivityTaskManager.RootTaskInfo>,
        treatChooserAsEmpty: Boolean = false
    ): Boolean {
        var sawSplitStage = false
        for (childId in childIds) {
            if (taskHasAppActivity(childId, known, treatChooserAsEmpty)) return false
            val child = known.firstOrNull { it.taskId == childId } ?: resolveTaskObject(childId)
            val childMode = child?.let { getWindowingMode(it) }
            if (isSplitStageMode(childMode)) sawSplitStage = true
        }
        return sawSplitStage
    }

    private fun taskHasAppActivity(
        taskId: Int,
        known: List<ActivityTaskManager.RootTaskInfo>,
        treatChooserAsEmpty: Boolean = false
    ): Boolean {
        known.firstOrNull { it.taskId == taskId }?.topActivity?.let { top ->
            if (treatChooserAsEmpty && isSplitChooserPackage(top.packageName)) return false
            return true
        }
        val task = resolveTaskObject(taskId) ?: return false
        val probes: List<() -> Any?> = listOf(
            { task.invokeMethod("getTopNonFinishingActivity", args(), argTypes()) },
            { task.invokeMethod("topRunningActivity", args(), argTypes()) },
            { task.invokeMethod("getTopMostActivity", args(), argTypes()) },
            { task.invokeMethod("getTopActivity", args(), argTypes()) }
        )
        for (probe in probes) {
            try {
                val activity = probe() ?: continue
                if (treatChooserAsEmpty) {
                    val pkg = try {
                        (activity as? ComponentName)?.packageName
                            ?: activity.getObjectAs("packageName", String::class.java) as? String
                            ?: (activity.invokeMethod("getPackageName", args(), argTypes()) as? String)
                    } catch (_: Throwable) {
                        null
                    }
                    if (isSplitChooserPackage(pkg)) continue
                }
                return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    /** Empty shells on the AA VD that block ensureFreeform. Phone zombies are cleaned separately. */
    private fun hasEmptySplitOrganizerShells(): Boolean {
        return hasEmptySplitOrganizerShellsOnDisplay(mDisplayId)
    }

    private fun hasEmptySplitOrganizerShellsOnDisplay(displayId: Int): Boolean {
        if (displayId == Display.INVALID_DISPLAY) return false
        if (getSplitAppTasksOnDisplay(displayId).isNotEmpty()) return false
        return try {
            val tasks = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
            collectEmptySplitOrganizerTaskIds(tasks).isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    private fun removeOrganizerTaskQuietly(taskId: Int, reason: String): Boolean {
        return try {
            // Bypass removeTask(): no VD ownership / home-restart side effects for shells.
            mVdTaskIds.remove(taskId)
            val ok = Instances.iActivityTaskManager.removeTask(taskId)
            log(TAG, "cleanupEmptySplit[$reason]: removeTask($taskId)=$ok")
            ok
        } catch (e: Throwable) {
            log(TAG, "cleanupEmptySplit[$reason]: removeTask($taskId) failed:", e)
            false
        }
    }

    private fun readChildTaskIds(taskInfo: Any): IntArray? {
        val readers: List<() -> IntArray?> = listOf(
            { taskInfo.getObjectAs("childTaskIds", IntArray::class.java) as? IntArray },
            {
                @Suppress("UNCHECKED_CAST")
                (taskInfo.getObjectAs("childTaskIds", Array::class.java) as? Array<*>)
                    ?.mapNotNull { (it as? Number)?.toInt() }
                    ?.toIntArray()
            }
        )
        for (read in readers) {
            try {
                val ids = read()
                if (ids != null) return ids
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun getMultiWindowTasksOnDisplay(): List<ActivityTaskManager.RootTaskInfo> {
        if (mDisplayId == Display.INVALID_DISPLAY) return emptyList()
        return try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
                .filter { taskInfo ->
                    taskInfo.topActivity != null && isMultiWindowMode(getWindowingMode(taskInfo))
                }
        } catch (e: Throwable) {
            log(TAG, "getMultiWindowTasksOnDisplay error:", e)
            emptyList()
        }
    }

    private fun setFocusedTaskSafe(taskId: Int): Boolean {
        return try {
            Instances.iActivityTaskManager.setFocusedTask(taskId)
            true
        } catch (e: Throwable) {
            log(TAG, "setFocusedTask error:", e)
            false
        }
    }

    private fun scheduleEnsureFreeform(taskId: Int, reason: String, force: Boolean = false) {
        if (!isOneUiSplitEnabled()) return
        for (delay in ENSURE_FREEFORM_DELAYS_MS) {
            if (delay == 0L) {
                mHandler.post { ensureTaskFreeformOnVirtualDisplay(taskId, reason, force) }
            } else {
                mHandler.postDelayed(
                    { ensureTaskFreeformOnVirtualDisplay(taskId, "$reason-$delay", force) },
                    delay
                )
            }
        }
    }

    /**
     * OneUI often ignores launch FREEFORM on virtual displays (and launcher icon starts never
     * see our ActivityOptions). Force task windowing mode + inset bounds after the task lands
     * on the VD — same end state as the user's "move to phone then back" workaround.
     *
     * Never touch tasks while OneUI split stages are active, and never re-shrink an already
     * freeform window (that fights caption/divider resize and snaps the split ratio back).
     */
    private fun ensureTaskFreeformOnVirtualDisplay(
        taskId: Int,
        reason: String,
        force: Boolean = false
    ) {
        if (mIsDestroying || mDisplayId == Display.INVALID_DISPLAY) return
        if (!isOneUiSplitEnabled()) return
        if (isDisplayInSplitStages()) {
            log(TAG, "ensureFreeform skipped (split stages): task=$taskId [$reason]")
            return
        }
        // Thermal / APP_DOES_NOT_SUPPORT exit often leaves empty stage shells that block freeform.
        if (hasEmptySplitOrganizerShells()) {
            scheduleCleanupEmptySplitOrganizerTasks("before-ensure:$reason")
            cleanupEmptySplitOrganizerTasks("before-ensure:$reason")
            if (hasEmptySplitOrganizerShells()) {
                log(TAG, "ensureFreeform deferred (empty split shells): task=$taskId [$reason]")
                return
            }
        }
        if (!force && SystemClock.uptimeMillis() < mSuppressEnsureFreeformUntil) {
            log(TAG, "ensureFreeform skipped (suppress): task=$taskId [$reason]")
            return
        }
        val now = SystemClock.uptimeMillis()
        val last = mLastFreeformEnsureAt[taskId] ?: 0L
        if (now - last < 180L) return
        mLastFreeformEnsureAt[taskId] = now

        val taskInfo = findRootTaskInfoOnDisplay(taskId, mDisplayId) ?: return
        val pkg = taskInfo.topActivity?.packageName
        if (pkg.isNullOrBlank()) return
        if (pkg == mHomePackage || pkg == mLauncherPackage) return
        if (IGNORE_RECENT_PACKAGE.contains(pkg)) return
        if (isBounceExcludedPackage(pkg)) return

        markVirtualDisplayOwnership(taskId, pkg)
        val mode = getWindowingMode(taskInfo)
        // Leave split / multi-window / existing freeform alone.
        // Only correct plain fullscreen (or unknown) into freeform + inset once.
        if (mode != null && mode != WINDOWING_MODE_FULLSCREEN && mode != WINDOWING_MODE_UNDEFINED) {
            return
        }

        if (!setTaskWindowingModeSafe(taskId, WINDOWING_MODE_FREEFORM)) {
            log(TAG, "ensureFreeform[$reason]: setTaskWindowingMode failed task=$taskId pkg=$pkg mode=${mode ?: "?"}")
            return
        }
        val resized = resizeTaskToFreeformBounds(taskId)
        val after = findRootTaskInfoOnDisplay(taskId, mDisplayId)?.let { getWindowingMode(it) }
        if (after == WINDOWING_MODE_FREEFORM) {
            log(
                TAG,
                "ensureFreeform[$reason]: task=$taskId pkg=$pkg mode=${mode ?: "?"} -> FREEFORM bounds=$resized"
            )
        } else {
            log(
                TAG,
                "ensureFreeform[$reason]: still not freeform task=$taskId pkg=$pkg mode=${mode ?: "?"} after=${after ?: "?"} bounds=$resized"
            )
        }
    }

    private fun buildFreeformInsetBounds(): Rect? {
        if (mDisplayId == Display.INVALID_DISPLAY) return null
        return try {
            val display = Instances.displayManager.getDisplay(mDisplayId) ?: return null
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            val w = metrics.widthPixels
            val h = metrics.heightPixels
            if (w <= 0 || h <= 0) return null
            val insetW = (w * FREEFORM_INSET_RATIO).toInt().coerceAtLeast(1)
            val insetH = (h * FREEFORM_INSET_RATIO).toInt().coerceAtLeast(1)
            val left = ((w - insetW) / 2).coerceAtLeast(0)
            val top = ((h - insetH) / 2).coerceAtLeast(0)
            Rect(left, top, left + insetW, top + insetH)
        } catch (e: Throwable) {
            log(TAG, "buildFreeformInsetBounds failed:", e)
            null
        }
    }

    private fun resizeTaskToFreeformBounds(taskId: Int): Boolean {
        val bounds = buildFreeformInsetBounds() ?: return false
        val atm = Instances.iActivityTaskManager as Any
        try {
            atm.invokeMethod(
                "resizeTask",
                args(taskId, bounds, RESIZE_MODE_SYSTEM),
                argTypes(Integer.TYPE, Rect::class.java, Integer.TYPE)
            )
            return true
        } catch (_: Throwable) {
        }
        try {
            atm.invokeMethod(
                "resizeTask",
                args(taskId, bounds),
                argTypes(Integer.TYPE, Rect::class.java)
            )
            return true
        } catch (_: Throwable) {
        }
        return try {
            val task = resolveTaskObject(taskId) ?: return false
            task.invokeMethod("setBounds", args(bounds), argTypes(Rect::class.java))
            true
        } catch (e: Throwable) {
            log(TAG, "resizeTaskToFreeformBounds($taskId) failed:", e)
            false
        }
    }

    private fun isBounceExcludedPackage(packageName: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return true
        if (BOUNCE_EXCLUDED_PACKAGES.contains(pkg)) return true
        if (pkg == mHomePackage || pkg == mLauncherPackage) return true
        if (IGNORE_RECENT_PACKAGE.contains(pkg)) return true
        return false
    }

    private fun markVirtualDisplayOwnership(taskId: Int, packageName: String?) {
        val info = findRootTaskInfoOnDisplay(taskId, mDisplayId)
            ?: findRootTaskInfoOnDisplay(taskId, Display.DEFAULT_DISPLAY)
        // Never track OneUI organizer roots/stages as owned task ids — reclaim would
        // moveRootTaskToDisplay the whole split tree (resets ratio / fights divider).
        if (info != null && isCreatedByOrganizer(info)) {
            val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() }
                ?: info.topActivity?.packageName?.trim()?.takeIf { it.isNotEmpty() }
            if (!pkg.isNullOrBlank() && !isBounceExcludedPackage(pkg)) {
                if (mVdPackages.add(pkg)) {
                    log(TAG, "VD ownership mark (pkg only, skip organizer task=$taskId): pkg=$pkg")
                }
            }
            return
        }
        mVdTaskIds.add(taskId)
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (isBounceExcludedPackage(pkg)) return
        if (mVdPackages.add(pkg)) {
            log(TAG, "VD ownership mark: pkg=$pkg task=$taskId")
        }
    }

    private fun forgetVirtualDisplayOwnership(taskId: Int, packageName: String?) {
        mVdTaskIds.remove(taskId)
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (mVdPackages.remove(pkg)) {
            log(TAG, "VD ownership forget: pkg=$pkg task=$taskId")
        }
    }

    private fun findRootTaskInfoOnDisplay(taskId: Int, displayId: Int): ActivityTaskManager.RootTaskInfo? {
        if (displayId == Display.INVALID_DISPLAY) return null
        return try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
                .firstOrNull { it.taskId == taskId }
        } catch (_: Throwable) {
            null
        }
    }

    private fun setTaskWindowingModeSafe(taskId: Int, mode: Int): Boolean {
        val atm = Instances.iActivityTaskManager as Any
        // Older AIDL: setTaskWindowingMode(taskId, mode, toTop)
        try {
            atm.invokeMethod(
                "setTaskWindowingMode",
                args(taskId, mode, true),
                argTypes(Integer.TYPE, Integer.TYPE, java.lang.Boolean.TYPE)
            )
            return true
        } catch (_: Throwable) {
        }
        try {
            atm.invokeMethod(
                "setTaskWindowingMode",
                args(taskId, mode),
                argTypes(Integer.TYPE, Integer.TYPE)
            )
            return true
        } catch (_: Throwable) {
        }
        // system_server local ATMS: Task.setWindowingMode
        return try {
            val task = resolveTaskObject(taskId) ?: return false
            task.invokeMethod("setWindowingMode", args(mode), argTypes(Integer.TYPE))
            true
        } catch (e: Throwable) {
            log(TAG, "setTaskWindowingModeSafe($taskId, $mode) failed:", e)
            false
        }
    }

    private fun resolveTaskObject(taskId: Int): Any? {
        val atm = Instances.iActivityTaskManager as Any
        val attempts: List<() -> Any?> = listOf(
            {
                atm.invokeMethod(
                    "anyTaskForId",
                    args(taskId, 2),
                    argTypes(Integer.TYPE, Integer.TYPE)
                )
            },
            {
                atm.invokeMethod(
                    "anyTaskForId",
                    args(taskId),
                    argTypes(Integer.TYPE)
                )
            },
            {
                atm.invokeMethod(
                    "anyTaskForId",
                    args(taskId, false),
                    argTypes(Integer.TYPE, java.lang.Boolean.TYPE)
                )
            }
        )
        for (attempt in attempts) {
            try {
                val task = attempt()
                if (task != null) return task
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun scheduleReclaimVirtualDisplayTasks(reason: String) {
        if (!isOneUiSplitEnabled()) return
        for (delay in RECLAIM_FOLLOWUP_DELAYS_MS) {
            if (delay == 0L) {
                mHandler.post { reclaimVirtualDisplayTasks(reason) }
            } else {
                mHandler.postDelayed({ reclaimVirtualDisplayTasks("$reason-$delay") }, delay)
            }
        }
    }

    /**
     * OneUI caption "split" often moves (or recreates) the freeform task onto DEFAULT_DISPLAY and
     * shows a phone Apps/Home chooser ("应用…"). Pull owned packages back onto the AA VD.
     */
    private fun maybeBounceTaskBackToVirtualDisplay(taskId: Int, newDisplayId: Int) {
        if (mIsDestroying || mDisplayId == Display.INVALID_DISPLAY) return
        if (!isOneUiSplitEnabled()) return
        if (newDisplayId == mDisplayId) return
        val pkg = findPackageForTask(taskId)
        // PiP (PINNED) must stay on the phone/system display — bouncing it onto the AA VD
        // leaves empty split shells / kills freeform caption so OneUI split stops working.
        if (isPinnedTask(taskId)) {
            log(TAG, "bounce skipped (pinned/PiP): task=$taskId pkg=${pkg.orEmpty()} display=$newDisplayId")
            mVdTaskIds.remove(taskId)
            scheduleCleanupEmptySplitOrganizerTasks("display-changed-pinned")
            return
        }
        val owned = mVdTaskIds.contains(taskId) ||
            (!pkg.isNullOrBlank() && mVdPackages.contains(pkg))
        if (!owned) {
            // Still scan — OneUI may have recreated the task under a new id.
            scheduleReclaimVirtualDisplayTasks("display-changed-unowned")
            return
        }
        if (isBounceExcludedPackage(pkg)) {
            mVdTaskIds.remove(taskId)
            return
        }
        bounceTaskToVirtualDisplay(taskId, pkg, "display-changed->$newDisplayId")
        scheduleReclaimVirtualDisplayTasks("display-changed")
    }

    private fun bounceTaskToVirtualDisplay(taskId: Int, packageName: String?, reason: String): Boolean {
        if (mIsDestroying || mDisplayId == Display.INVALID_DISPLAY) return false
        if (SystemClock.uptimeMillis() < mSuppressDisplayBounceUntil) {
            log(TAG, "bounce skipped (suppressed): task=$taskId pkg=${packageName.orEmpty()} [$reason]")
            return false
        }
        if (isPinnedTask(taskId)) {
            log(TAG, "bounce skipped (pinned/PiP): task=$taskId pkg=${packageName.orEmpty()} [$reason]")
            mVdTaskIds.remove(taskId)
            return false
        }
        if (isTaskOnVirtualDisplay(taskId)) {
            markVirtualDisplayOwnership(taskId, packageName)
            return false
        }
        val now = SystemClock.uptimeMillis()
        val last = mLastDisplayBounceAt[taskId] ?: 0L
        if (now - last < 300L) return false
        mLastDisplayBounceAt[taskId] = now

        log(TAG, "bounce to VD[$reason]: task=$taskId pkg=${packageName.orEmpty()}")
        // Short suppress only to avoid re-entrancy on our own moveRootTaskToDisplay.
        mSuppressDisplayBounceUntil = now + 250L
        // Do not force FREEFORM over OneUI split entry after we pull the task back.
        mSuppressEnsureFreeformUntil = now + SUPPRESS_ENSURE_FREEFORM_MS
        return try {
            Instances.iActivityTaskManager.moveRootTaskToDisplay(taskId, mDisplayId)
            AndroidHook.FuckAppUseApplicationContext.markPackageOnVirtualDisplay(packageName, mDisplayId)
            markVirtualDisplayOwnership(taskId, packageName)
            bringTaskToFrontOnDisplay(taskId)
            true
        } catch (e: Throwable) {
            log(TAG, "bounce to VD failed: task=$taskId [$reason]", e)
            false
        }
    }

    /**
     * Package-based reclaim: OneUI split frequently assigns a new taskId on the phone, so
     * task-id tracking alone misses amapauto / etc. Scan DEFAULT_DISPLAY for owned packages.
     *
     * Must never throw into system_server's main looper: bounce/moveRootTaskToDisplay can
     * re-enter TaskStackListener and mutate ownership sets while we iterate.
     */
    private fun reclaimVirtualDisplayTasks(reason: String) {
        try {
            doReclaimVirtualDisplayTasks(reason)
        } catch (e: Throwable) {
            // ConcurrentModificationException etc. must not crash system_server.
            log(TAG, "reclaim[$reason] failed:", e)
        }
    }

    private fun doReclaimVirtualDisplayTasks(reason: String) {
        if (mIsDestroying || mDisplayId == Display.INVALID_DISPLAY) return
        if (!isOneUiSplitEnabled()) return
        if (SystemClock.uptimeMillis() < mSuppressDisplayBounceUntil) return
        if (mVdPackages.isEmpty() && mVdTaskIds.isEmpty()) return

        val now = SystemClock.uptimeMillis()
        if (now - mLastReclaimAt < 150L) return
        mLastReclaimAt = now

        // Snapshot before binder calls: moveRootTaskToDisplay / getRootTaskInfo re-enter
        // listeners that mutate mVdTaskIds / mVdPackages (LinkedHashSet → CME).
        val ownedTaskIds = mVdTaskIds.toList()
        val ownedPackages = mVdPackages.toSet()

        val splitActive = isDisplayInSplitStages()
        val phoneTasks = try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(Display.DEFAULT_DISPLAY)
        } catch (e: Throwable) {
            log(TAG, "reclaim[$reason] list phone tasks failed:", e)
            return
        }

        var bounced = 0
        for (taskInfo in phoneTasks) {
            val taskId = taskInfo.taskId
            val pkg = taskInfo.topActivity?.packageName
                ?: runCatching {
                    taskInfo.getObjectAs("baseActivity", ComponentName::class.java) as? ComponentName
                }.getOrNull()?.packageName
            if (isBounceExcludedPackage(pkg)) continue
            // Organizer freeform/stage roots briefly appear on the phone during split entry
            // with a child app as topActivity — bouncing them collapses/resets the ratio.
            if (isCreatedByOrganizer(taskInfo)) {
                mVdTaskIds.remove(taskId)
                continue
            }

            val ownedById = ownedTaskIds.contains(taskId)
            val ownedByPkg = !pkg.isNullOrBlank() && ownedPackages.contains(pkg)
            if (!ownedById && !ownedByPkg) continue

            // Never reclaim PiP windows — system places them on the phone; pulling them
            // back onto the AA VD breaks OneUI freeform/split until reboot.
            if (isPinnedWindowMode(taskInfo)) {
                mVdTaskIds.remove(taskId)
                continue
            }

            // Split already on VD: only reclaim packages that are missing from the VD.
            // Divider drag must not trigger moveRootTaskToDisplay on organizer roots.
            if (splitActive && !pkg.isNullOrBlank() && hasPackageTaskOnDisplay(pkg, mDisplayId)) {
                mVdTaskIds.remove(taskId)
                continue
            }

            if (bounceTaskToVirtualDisplay(taskId, pkg, "reclaim:$reason")) {
                bounced++
            }
        }

        // Drop stale task ids that no longer exist on either display.
        // Iterate the snapshot — never mVdTaskIds.iterator() across findRootTaskInfo*.
        val staleIds = ownedTaskIds.filter { id ->
            findRootTaskInfoOnDisplay(id, mDisplayId) == null &&
                findRootTaskInfoOnDisplay(id, Display.DEFAULT_DISPLAY) == null
        }
        staleIds.forEach { mVdTaskIds.remove(it) }

        if (bounced > 0) {
            log(TAG, "reclaim[$reason]: bounced=$bounced ownedPkgs=$mVdPackages ownedTasks=$mVdTaskIds")
        }
    }

    private fun applyVirtualDisplayPolicies(reason: String) {
        if (mDisplayId == Display.INVALID_DISPLAY) return
        Instances.iWindowManager.apply {
            val configuredImePolicy = AADisplayConfig.DisplayImePolicy.get(config)
            if (configuredImePolicy != DISPLAY_IME_POLICY_LOCAL) {
                log(TAG, "override display IME policy[$reason]: $configuredImePolicy -> $DISPLAY_IME_POLICY_LOCAL")
            }
            setDisplayImePolicy(mDisplayId, DISPLAY_IME_POLICY_LOCAL)
            setShouldShowWithInsecureKeyguard(mDisplayId, false)
            val oneUiSplit = isOneUiSplitEnabled()
            setShouldShowSystemDecors(mDisplayId, oneUiSplit)
            log(TAG, "setShouldShowSystemDecors[$reason]=$oneUiSplit (EnableOneUiSplit)")
            // Only push FREEFORM when OneUI split is on. Do not force FULLSCREEN when off —
            // that can regress devices that already open freeform on the VD by default.
            if (oneUiSplit) {
                try {
                    val before = tryOrNull { getWindowingMode(mDisplayId) }
                    setWindowingMode(mDisplayId, WINDOWING_MODE_FREEFORM)
                    log(TAG, "setWindowingMode[$reason]=$WINDOWING_MODE_FREEFORM (was=${before ?: "?"})")
                } catch (e: Throwable) {
                    log(TAG, "setWindowingMode[$reason] failed:", e)
                }
            }
        }
        applyForcedVirtualDisplayDensity(reason)
    }

    private fun invokeShellManager(op: String, block: (IShellManager) -> Unit) {
        val sm = mShellManager ?: return
        val binder = try {
            sm.asBinder()
        } catch (_: Throwable) {
            null
        }
        if (binder == null || !binder.isBinderAlive || !binder.pingBinder()) {
            log(TAG, "$op skipped: ShellManager binder dead")
            mShellManager = null
            return
        }
        try {
            block(sm)
        } catch (e: DeadObjectException) {
            log(TAG, "$op ignored (DeadObject):", e)
            mShellManager = null
        } catch (e: Throwable) {
            log(TAG, "$op ignored:", e)
        }
    }

    private fun applyForcedVirtualDisplayDensity(reason: String) {
        if (mDisplayId == Display.INVALID_DISPLAY || mDensityDpi <= 0) return
        try {
            Instances.iWindowManager.setForcedDisplayDensityForUser(mDisplayId, mDensityDpi, 0)
            log(TAG, "setForcedDisplayDensityForUser[$reason]: display=$mDisplayId dpi=$mDensityDpi")
        } catch (e: Throwable) {
            log(TAG, "setForcedDisplayDensityForUser[$reason] failed:", e)
        }
    }

    private fun clearForcedVirtualDisplayDensity() {
        if (mDisplayId == Display.INVALID_DISPLAY) return
        try {
            Instances.iWindowManager.clearForcedDisplayDensityForUser(mDisplayId, 0)
            log(TAG, "clearForcedDisplayDensityForUser: display=$mDisplayId")
        } catch (e: Throwable) {
            log(TAG, "clearForcedDisplayDensityForUser failed:", e)
        }
    }

    private fun findPackageForTask(taskId: Int): String? {
        fun fromDisplay(displayId: Int): String? {
            return try {
                Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
                    .firstOrNull { it.taskId == taskId }
                    ?.topActivity
                    ?.packageName
            } catch (_: Throwable) {
                null
            }
        }
        return fromDisplay(mDisplayId) ?: fromDisplay(Display.DEFAULT_DISPLAY)
    }

    private fun isTaskOnVirtualDisplay(taskId: Int): Boolean {
        if (mDisplayId == Display.INVALID_DISPLAY) return false
        return try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
                .any { it.taskId == taskId }
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasPackageTaskOnDisplay(packageName: String, displayId: Int): Boolean {
        if (displayId == Display.INVALID_DISPLAY) return false
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return false
        return try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId).any { taskInfo ->
                taskInfo.topActivity?.packageName == pkg
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun syncDensityMapForDisplayChange(taskId: Int, newDisplayId: Int) {
        val packageName = findPackageForTask(taskId)
        AndroidHook.FuckAppUseApplicationContext.onTaskDisplayChanged(packageName, newDisplayId)
        log(
            TAG,
            "onTaskDisplayChanged: task=$taskId pkg=${packageName.orEmpty()} -> display=$newDisplayId"
        )
    }

    private fun clearPinnedTasksOnDisplay(reason: String) {
        if (mDisplayId == Display.INVALID_DISPLAY) return
        val tasks = try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
        } catch (e: Throwable) {
            log(TAG, "clearPinnedTasksOnDisplay error:", e)
            return
        }
        tasks
            .filter { taskInfo ->
                taskInfo.topActivity != null && isPinnedWindowMode(taskInfo)
            }
            .forEach { taskInfo ->
                log(
                    TAG,
                    "clearPinnedTasksOnDisplay[$reason]: remove task=${taskInfo.taskId}, top=${taskInfo.topActivity?.flattenToShortString()}"
                )
                removeTask(taskInfo.taskId)
            }
    }

    private fun clearPinnedTasksIfHomeFront(reason: String) {
        if (mDisplayId == Display.INVALID_DISPLAY) return
        val tasks = try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
        } catch (e: Throwable) {
            log(TAG, "clearPinnedTasksIfHomeFront error:", e)
            return
        }
        if (tasks.isEmpty()) return
        val front = tasks.firstOrNull { it.topActivity != null }
        val frontPackage = front?.topActivity?.packageName
        if (frontPackage == null || mHomePackage == null) return
        if (frontPackage == mHomePackage) {
            clearPinnedTasksOnDisplay(reason)
        }
    }

    private fun isPinnedWindowMode(taskInfo: Any): Boolean {
        return getWindowingMode(taskInfo) == WINDOWING_MODE_PINNED
    }

    private fun isPinnedTask(taskId: Int): Boolean {
        val info = findRootTaskInfoOnDisplay(taskId, Display.DEFAULT_DISPLAY)
            ?: findRootTaskInfoOnDisplay(taskId, mDisplayId)
            ?: return false
        return isPinnedWindowMode(info)
    }

    /**
     * Video apps (e.g. 央视影音) entering PiP set WINDOWING_MODE_PINNED and often move to the
     * phone. AADisplay must not reclaim that task; also clean empty OneUI stage shells and
     * re-assert freeform display policies so split can still be entered afterwards.
     */
    private fun onVirtualDisplayActivityPinned(packageName: String?, taskId: Int) {
        if (mIsDestroying || mDisplayId == Display.INVALID_DISPLAY) return
        log(TAG, "onActivityPinned handled: pkg=${packageName.orEmpty()} task=$taskId")
        mVdTaskIds.remove(taskId)
        val now = SystemClock.uptimeMillis()
        mSuppressDisplayBounceUntil = now + SUPPRESS_RECLAIM_AFTER_PIP_MS
        // Allow remaining VD apps to regain FREEFORM caption (do not keep post-bounce suppress).
        mSuppressEnsureFreeformUntil = 0L
        scheduleCleanupEmptySplitOrganizerTasks("activity-pinned:${packageName.orEmpty()}")
        cleanupEmptySplitOrganizerTasks("activity-pinned:${packageName.orEmpty()}")
        if (isOneUiSplitEnabled()) {
            applyVirtualDisplayPolicies("activity-pinned")
            ensureFreeformForVirtualDisplayApps("activity-pinned")
        }
    }

    /**
     * After PiP exits, restore freeform on the AA VD so the OneUI caption/split handle returns.
     */
    private fun onVirtualDisplayActivityUnpinned() {
        if (mIsDestroying || mDisplayId == Display.INVALID_DISPLAY) return
        log(TAG, "onActivityUnpinned handled")
        mSuppressEnsureFreeformUntil = 0L
        scheduleCleanupEmptySplitOrganizerTasks("activity-unpinned")
        cleanupEmptySplitOrganizerTasks("activity-unpinned")
        if (isOneUiSplitEnabled()) {
            applyVirtualDisplayPolicies("activity-unpinned")
            ensureFreeformForVirtualDisplayApps("activity-unpinned")
        }
    }

    private fun ensureFreeformForVirtualDisplayApps(reason: String) {
        if (mDisplayId == Display.INVALID_DISPLAY || !isOneUiSplitEnabled()) return
        if (isDisplayInSplitStages()) return
        val tasks = try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
        } catch (e: Throwable) {
            log(TAG, "ensureFreeformForVdApps[$reason] list failed:", e)
            return
        }
        for (taskInfo in tasks) {
            val pkg = taskInfo.topActivity?.packageName ?: continue
            if (isBounceExcludedPackage(pkg) || pkg == mHomePackage || pkg == mLauncherPackage) continue
            if (isPinnedWindowMode(taskInfo)) continue
            if (isCreatedByOrganizer(taskInfo)) continue
            scheduleEnsureFreeform(taskInfo.taskId, reason, force = true)
        }
    }

    private fun injectInputEvent(event: InputEvent): Boolean {
        if (mDisplayId == Display.INVALID_DISPLAY) return false
        return try {
            event.invokeMethod("setDisplayId", args(mDisplayId), argTypes(Integer.TYPE))
            val result = Instances.iInputManager.injectInputEvent(event, 0)
            if (!result) {
                log(TAG, "injectInputEvent failed: ${event.javaClass.simpleName}")
            }
            result
        } catch (e: Throwable) {
            log(TAG, "injectInputEvent exception:", e)
            false
        }
    }

    /**
     * Matches Android's VirtualDisplayTaskEmbedder key event shape for back navigation.
     * See AOSP: VirtualDisplayTaskEmbedder#createKeyEvent()
     */
    private fun createKeyEvent(action: Int, keyCode: Int): KeyEvent {
        val whenMillis = SystemClock.uptimeMillis()
        return KeyEvent(
            whenMillis,
            whenMillis,
            action,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY,
            InputDevice.SOURCE_KEYBOARD
        )
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
        runCatching {
            taskInfo.getObjectAs("baseActivity", ComponentName::class.java) as? ComponentName
        }.getOrNull()?.packageName?.let { trackPackage(it, userId) }
        runCatching {
            taskInfo.getObjectAs("baseIntent", Intent::class.java) as? Intent
        }.getOrNull()?.component?.packageName?.let { trackPackage(it, userId) }
    }

    /**
     * True for system Home / Secondary Home root tasks (phone One UI + VD SecondaryDisplayLauncher).
     * These must stay out of the recent-task UI so users cannot swipe/close the desktop.
     */
    private fun isSystemHomeTask(taskInfo: ActivityTaskManager.RootTaskInfo): Boolean {
        return try {
            val conf = (taskInfo as Any).invokeMethod("getConfiguration", args(), argTypes())
                ?: return false
            val winConf = conf.invokeMethod("getWindowConfiguration", args(), argTypes())
                ?: return false
            val activityType = winConf.invokeMethod("getActivityType", args(), argTypes()) as? Int
            activityType == ACTIVITY_TYPE_HOME
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Get recent task list for the specified display.
     * Hides system Home / launcher / SystemUI and the configured AADisplay home package so
     * they cannot be mis-closed or moved between phone ↔ VD stacks.
     */
    private fun recentTaskInfo(displayId: Int): List<RecentTaskInfo> {
        val allRootTaskInfosOnDisplay = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        log(TAG, "RecentTask $displayId, ${allRootTaskInfosOnDisplay.size}")
        return allRootTaskInfosOnDisplay
            .map { taskInfo ->
                if (isSystemHomeTask(taskInfo)) {
                    return@map null
                }
                val topActivity = taskInfo.topActivity ?: return@map null
                // Same exclusion set as bounce/reclaim: One UI Home, SystemUI, configured home, etc.
                if (isBounceExcludedPackage(topActivity.packageName)) {
                    return@map null
                }

                var taskDescription = taskInfo.taskDescription
                if(taskDescription == null){
                    taskDescription = Instances.iActivityTaskManager.getTaskDescription(taskInfo.taskId) ?: return@map null
                }

                var icon = runCatching { taskDescription.icon }.getOrNull()
                if (icon == null) {
                    icon = Instances.packageManager.getActivityIcon(topActivity).toBitmap()
                }
                var label = taskDescription.label
                if(label == null){
                    label = Instances.packageManager.getActivityInfo(topActivity, 0).loadLabel(Instances.packageManager).toString()
                }

                val packageName = topActivity.packageName

                var snapshot: Bitmap? = runCatching {
                    try {
                        if (Build.VERSION.SDK_INT >= 34) {//14+
                            Instances.iActivityTaskManager.getTaskSnapshot(taskInfo.taskId, true, true)
                        } else {
                            Instances.iActivityTaskManager.getTaskSnapshot(taskInfo.taskId, true)
                        }
                    } catch (e: Throwable){
                        Instances.iActivityTaskManager.getTaskSnapshot(taskInfo.taskId, true)
                    }?.let { taskSnapshot ->
                        taskSnapshot.hardwareBuffer?.let { buffer ->
                            Bitmap.wrapHardwareBuffer(buffer, taskSnapshot.colorSpace)
                        }
                    }
                }.let { result ->
                    if(result.isFailure){
                        log(TAG,"load snapshot exception", result.exceptionOrNull())
                        null
                    } else {
                        result.getOrNull()
                    }
                }

                log(TAG, "RecentTask: $packageName, ${taskInfo.taskId}, snapshot:${snapshot != null}")

                RecentTaskInfo(
                    icon,
                    taskInfo.taskId,
                    label,
                    snapshot,
                    packageName
                )
            }
            .filterNotNull()
    }


    inner class TaskStackListener : ITaskStackListener.Stub() {
        override fun onTaskStackChanged() {
            if (!isOneUiSplitEnabled() || mIsDestroying) return
            // Divider drag fires this continuously. Reclaiming while split stages are up
            // fights the layout and can reset the ratio.
            if (isDisplayInSplitStages()) return
            mHandler.removeCallbacks(mDebouncedStackReclaim)
            mHandler.postDelayed(mDebouncedStackReclaim, 120L)
        }
        override fun onActivityPinned(packageName: String?, userId: Int, taskId: Int, stackId: Int) {
            mHandler.post { onVirtualDisplayActivityPinned(packageName, taskId) }
        }
        override fun onActivityUnpinned() {
            mHandler.post { onVirtualDisplayActivityUnpinned() }
        }
        override fun onActivityRestartAttempt(task: ActivityManager.RunningTaskInfo?, homeTaskVisible: Boolean, clearedTask: Boolean, wasVisible: Boolean) {}
        override fun onActivityForcedResizable(packageName: String?, taskId: Int, reason: Int) {}
        override fun onActivityDismissingDockedTask() {}
        override fun onActivityLaunchOnSecondaryDisplayFailed(taskInfo: ActivityManager.RunningTaskInfo?, requestedDisplayId: Int) {}
        override fun onActivityLaunchOnSecondaryDisplayRerouted(taskInfo: ActivityManager.RunningTaskInfo?, requestedDisplayId: Int) {}
        /**
         * Called when a new task is created
         * Record task IDs for Home package and default launch package
         */
        override fun onTaskCreated(taskId: Int, componentName: ComponentName?) {
            val packageName = componentName?.packageName ?: return
            trackPackage(packageName, 0)
            if(packageName == mHomePackage) {
                mHomeTaskId = taskId
            } else if(packageName == mLauncherPackage) {
                mLauncherPackageTaskId = taskId
            }
            // Launcher icon starts never pass through launchActivityAsUser FREEFORM options.
            mHandler.post {
                if (isTaskOnVirtualDisplay(taskId)) {
                    markVirtualDisplayOwnership(taskId, packageName)
                    scheduleEnsureFreeform(taskId, "onTaskCreated")
                } else if (mVdPackages.contains(packageName)) {
                    // OneUI split often recreates the app task on the phone with a new taskId.
                    bounceTaskToVirtualDisplay(taskId, packageName, "onTaskCreated-phone")
                    scheduleReclaimVirtualDisplayTasks("onTaskCreated-phone")
                }
            }
        }
        
        /**
         * Called when a task is removed
         * If the removed task is the Home package, restart it
         */
        override fun onTaskRemoved(taskId: Int) {
            mVdTaskIds.remove(taskId)
            mLastFreeformEnsureAt.remove(taskId)
            mLastDisplayBounceAt.remove(taskId)
            if(mIsDestroying) {
                if(mHomeTaskId == taskId) {
                    mHomeTaskId = null
                } else if(mLauncherPackageTaskId == taskId) {
                    mLauncherPackageTaskId = null
                }
                return
            }
            if(mHomeTaskId == taskId) {
                mHomeTaskId = null
                // Do not restart Home while OneUI split/MW is active — that collapses the layout.
                if (shouldPreserveMultiWindowLayout()) {
                    log(TAG, "onTaskRemoved: skip startHomeLauncher while multi-window active, task=$taskId")
                } else {
                    startHomeLauncher()
                }
            } else if(mLauncherPackageTaskId == taskId) {
                mLauncherPackageTaskId = null
            }
            // Keep mVdPackages: OneUI may recreate the same package under a new taskId on phone.
            scheduleReclaimVirtualDisplayTasks("onTaskRemoved")
            scheduleCleanupEmptySplitOrganizerTasks("onTaskRemoved")
        }
        override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
            val taskId = taskInfo.taskId
            val pkg = taskInfo.topActivity?.packageName
            mHandler.post {
                if (isTaskOnVirtualDisplay(taskId)) {
                    markVirtualDisplayOwnership(taskId, pkg)
                    scheduleEnsureFreeform(taskId, "onTaskMovedToFront")
                } else if (!pkg.isNullOrBlank() && mVdPackages.contains(pkg)) {
                    bounceTaskToVirtualDisplay(taskId, pkg, "onTaskMovedToFront-phone")
                    scheduleReclaimVirtualDisplayTasks("onTaskMovedToFront-phone")
                }
            }
        }
        override fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo) {}
        override fun onActivityRequestedOrientationChanged(taskId: Int, requestedOrientation: Int) {}
        override fun onTaskRemovalStarted(taskInfo: ActivityManager.RunningTaskInfo?) {}
        override fun onTaskProfileLocked(taskInfo: ActivityManager.RunningTaskInfo?) {}
        override fun onTaskProfileLocked(taskInfo: ActivityManager.RunningTaskInfo?, userId: Int) {}
        override fun onTaskSnapshotChanged(taskId: Int, snapshot: TaskSnapshot?) {}
        override fun onBackPressedOnTaskRoot(taskInfo: ActivityManager.RunningTaskInfo?) {}
        override fun onTaskDisplayChanged(taskId: Int, newDisplayId: Int) {
            syncDensityMapForDisplayChange(taskId, newDisplayId)
            // Ghost TaskDisplayArea (ATM yes / DisplayManager no) — track + clean so split stages
            // do not stay occupied after AA reconnect (moveFreeformTaskToSplit → "no display").
            if (newDisplayId != Display.DEFAULT_DISPLAY &&
                newDisplayId != mDisplayId &&
                newDisplayId != Display.INVALID_DISPLAY &&
                !isDisplayKnownToDisplayManager(newDisplayId)
            ) {
                mSuspectOrphanDisplayIds += newDisplayId
                scheduleCleanupEmptySplitOrganizerTasks("orphan-display=$newDisplayId")
            }
            if (mDisplayId != Display.INVALID_DISPLAY && newDisplayId == mDisplayId) {
                val pkg = findPackageForTask(taskId)
                markVirtualDisplayOwnership(taskId, pkg)
                scheduleEnsureFreeform(taskId, "onTaskDisplayChanged")
            } else if (mDisplayId != Display.INVALID_DISPLAY && newDisplayId != mDisplayId) {
                maybeBounceTaskBackToVirtualDisplay(taskId, newDisplayId)
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

        //Samsung OneUi
        override fun onActivityDismissingSplitTask(str: String?) {
            log(TAG, "onActivityDismissingSplitTask: $str")
            scheduleCleanupEmptySplitOrganizerTasks("dismissing-split:$str")
        }
        override fun onOccludeChangeNotice(componentName: ComponentName?, z: Boolean) {}
        override fun onTaskbarIconVisibleChangeRequest(componentName: ComponentName?, z: Boolean) {}
        //Samsung OneUi 7
        override fun onTaskWindowingModeChanged(i: Int) {
            log(TAG, "onTaskWindowingModeChanged: mode=$i, displayInMw=${isDisplayInMultiWindow()} split=${isDisplayInSplitStages()}")
            if (!isOneUiSplitEnabled() || mDisplayId == Display.INVALID_DISPLAY) return
            // Split entry dumps VD apps onto the phone chooser — reclaim packages missing from VD.
            // Do NOT ensureFreeform here: forcing FREEFORM collapses OneUI split/MW.
            // reclaimVirtualDisplayTasks itself no-ops bounce when the package is already on VD.
            scheduleReclaimVirtualDisplayTasks("windowing-mode=$i")
            // After abort/exit, empty stage shells often remain on the VD.
            scheduleCleanupEmptySplitOrganizerTasks("windowing-mode=$i")
        }
    }
}
