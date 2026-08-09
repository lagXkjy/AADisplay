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
import android.provider.Settings
import android.view.*
import android.window.TaskSnapshot
import io.github.nitsuya.aa.display.xposed.hook.SystemUiSplitHook
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
        /**
         * Treat freeform as "fake fullscreen" when bounds cover more than this of the display.
         * Caption maximize often keeps WINDOWING_MODE_FREEFORM with near-full bounds (no close
         * affordance); without demoting, reopen stays maximized and split entry stays broken.
         */
        private const val FREEFORM_MAX_FILL_RATIO = 0.95f
        /**
         * Caption drag on the small AA VD can leave freeform mostly outside the display
         * (still "visible" to WM but blank on screen). Stack-tap restore uses this threshold;
         * auto-restore must not, or live move fights the inset snap.
         */
        private const val FREEFORM_MIN_VISIBLE_RATIO = 0.45f
        /**
         * OneUI caption swipe-down minimize keeps on-screen bounds but sets visible=false.
         * Caption *move* can flicker the same flags while bounds keep changing — require this
         * long of stable bounds before auto-unminimize, or drag looks like minimize→snap.
         */
        private const val FREEFORM_MINIMIZE_CONFIRM_MS = 900L
        /** Debounce stack-changed restore past live caption/border drag event bursts. */
        private const val FREEFORM_RESTORE_DEBOUNCE_MS = 600L
        /**
         * Top-corner diagonal shrink on the small AA VD is often misread by OneUI as caption
         * swipe-down minimize. After a real width/height change, confirm unminimize faster.
         */
        private const val FREEFORM_RECENT_RESIZE_MS = 2500L
        private const val FREEFORM_RESIZE_MINIMIZE_CONFIRM_MS = 220L
        /** After split bounce/reclaim, do not force FREEFORM (would collapse OneUI split). */
        private const val SUPPRESS_ENSURE_FREEFORM_MS = 1800L
        /** After intentional close/replace, keep reclaim from resurrecting the vacated package. */
        private const val SUPPRESS_RECLAIM_AFTER_REPLACE_MS = 2500L
        /**
         * After an app enters PiP (WINDOWING_MODE_PINNED), suppress reclaim/bounce so we do not
         * pull the pinned window back onto the AA VD (that poisons OneUI split/freeform).
         */
        private const val SUPPRESS_RECLAIM_AFTER_PIP_MS = 2500L

        private val ENSURE_FREEFORM_DELAYS_MS = longArrayOf(0L, 300L, 800L, 1500L, 2500L, 4000L, 6000L)
        /** How long a package stays in "must open as inset freeform" after launch/close. */
        private const val PENDING_INSET_FREEFORM_MS = 12_000L
        private val RECLAIM_FOLLOWUP_DELAYS_MS = longArrayOf(0L, 400L, 1000L)
        /** Debounce empty OneUI stage-shell cleanup (thermal abort leaves them on the VD). */
        private const val EMPTY_SPLIT_CLEANUP_MIN_INTERVAL_MS = 400L
        /** Require shells to stay empty this long so split-entry races are not wiped. */
        private const val EMPTY_SPLIT_CONFIRM_MS = 600L
        private val EMPTY_SPLIT_CLEANUP_FOLLOWUP_DELAYS_MS = longArrayOf(0L, 350L, 800L, 1400L)
        /**
         * After wipe / connect, SystemUI often recreates empty phone `#3→#4/#5` within seconds.
         * Killing them just respawns on the phone and keeps blocking `moveFreeformTaskToSplit`.
         * Steal (moveRootTaskToDisplay → AA VD) repeatedly for a while instead.
         */
        private val PHONE_EMPTY_SPLIT_STEAL_DELAYS_MS =
            longArrayOf(0L, 250L, 600L, 1200L, 2000L, 3500L, 5500L, 8000L, 12000L, 20000L)
        private val PHONE_EMPTY_SPLIT_STEAL_TOKEN = Any()
        /**
         * Caption freeform→split briefly leaves stages "empty" and may park the app on the phone.
         * Relocating/killing `#3→#4/#5` in that window aborts OneUI split (app vanishes).
         */
        private const val SPLIT_ENTRY_SHELL_GUARD_MS = 4000L
        private val EMPTY_SPLIT_CLEANUP_TOKEN = Any()
        /**
         * One side closed / failed replace: OneUI on the AA VD often leaves the survivor in
         * `multi-window` + an empty opposite stage (half-width zombie). Wait past mid-entry
         * empty-side races, then tear the empty shell and force FREEFORM on the survivor.
         */
        private const val ASYMMETRIC_SPLIT_CONFIRM_MS = 1200L
        private const val ASYMMETRIC_SPLIT_MIN_INTERVAL_MS = 500L
        private val ASYMMETRIC_SPLIT_FOLLOWUP_DELAYS_MS = longArrayOf(0L, 500L, 1000L, 1600L, 2400L)
        private val ASYMMETRIC_SPLIT_TOKEN = Any()
        /**
         * Entering split from inset freeform keeps the StageCoordinator root at ~88% bounds
         * (`[43,29][676,451]` on 720×480). Expand to full VD after Shell settles; multi-delay
         * because OneUI may re-apply `mLastNonFullscreenBounds` once or twice.
         */
        private val EXPAND_SPLIT_SHELL_DELAYS_MS =
            longArrayOf(0L, 150L, 400L, 900L, 1600L, 2800L, 4500L, 7000L)
        private const val EXPAND_SPLIT_SHELL_MIN_INTERVAL_MS = 200L
        /** ActivityTaskManager.RESIZE_MODE_SYSTEM | RESIZE_MODE_FORCED */
        private const val RESIZE_MODE_SYSTEM_FORCED = 2
        private val EXPAND_SPLIT_SHELL_TOKEN = Any()

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
    private val mLastFreeformRelaunchAt = mutableMapOf<Int, Long>()
    /**
     * Packages that must land as inset freeform (caption visible). Marked on launch / stack-close
     * so OneUI restoring maximized/fullscreen bounds after our first ensure still gets demoted.
     * Cleared only after a verified inset freeform, or after [PENDING_INSET_FREEFORM_MS].
     */
    private val mPendingInsetFreeformPkgs = mutableMapOf<String, Long>()
    private val mLastDisplayBounceAt = mutableMapOf<Int, Long>()
    /**
     * Auto-unminimize candidates: taskId → (firstSeenUptime, boundsFingerprint).
     * Bounds changing resets the timer so live caption/border drag is not treated as minimize.
     */
    private val mMinimizeConfirmAt = mutableMapOf<Int, Pair<Long, String>>()
    /** Last observed freeform width/height — size change marks a resize (vs caption move). */
    private val mLastFreeformSize = mutableMapOf<Int, Pair<Int, Int>>()
    /** Uptime of last width/height change per task (diagonal/edge resize). */
    private val mLastResizeAt = mutableMapOf<Int, Long>()
    private var mLastReclaimAt = 0L
    private var mLastEmptySplitCleanupAt = 0L
    /** Uptime when empty split shells were first observed; 0 = none. */
    private var mEmptySplitShellsSeenAt = 0L
    /**
     * While set, do not relocate/kill phone StageCoordinator shells — split entry / MW is in
     * progress and mutating `#3→#4/#5` makes the freeform app vanish on caption-split click.
     */
    private var mSuppressPhoneSplitStealUntil = 0L
    /** Uptime when one-app + empty-opposite-stage was first observed; 0 = none. */
    private var mAsymmetricSplitSeenAt = 0L
    private var mLastAsymmetricSplitCollapseAt = 0L
    /** Fingerprint of the asymmetric layout under observation (reset when layout changes). */
    private var mAsymmetricSplitFingerprint: String? = null
    private var mLastExpandSplitShellAt = 0L
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
    private val mDebouncedRestoreHiddenFreeform = Runnable {
        restoreHiddenFreeformTasksOnVirtualDisplay("stack-changed")
    }
    /** Keep Home/fullscreen from stealing focus while OneUI split stages own the VD. */
    private val mDebouncedSplitFocusGuard = Runnable {
        maintainSplitForegroundFocus("stack-changed")
        expandSplitShellToFullDisplay("stack-changed")
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
        mPendingInsetFreeformPkgs.clear()
        mLastFreeformRelaunchAt.clear()
        mLastDisplayBounceAt.clear()
        mMinimizeConfirmAt.clear()
        mLastFreeformSize.clear()
        mLastResizeAt.clear()
        mSuppressDisplayBounceUntil = 0L
        mSuppressEnsureFreeformUntil = 0L
        mLastReclaimAt = 0L
        mLastEmptySplitCleanupAt = 0L
        mEmptySplitShellsSeenAt = 0L
        mSuppressPhoneSplitStealUntil = 0L
        mAsymmetricSplitSeenAt = 0L
        mLastAsymmetricSplitCollapseAt = 0L
        mAsymmetricSplitFingerprint = null
        mLastExpandSplitShellAt = 0L
        mSuspectOrphanDisplayIds.clear()
        mHandler.removeCallbacks(mDebouncedStackReclaim)
        mHandler.removeCallbacks(mDebouncedRestoreHiddenFreeform)
        mHandler.removeCallbacks(mDebouncedSplitFocusGuard)
        mHandler.removeCallbacksAndMessages(EMPTY_SPLIT_CLEANUP_TOKEN)
        mHandler.removeCallbacksAndMessages(ASYMMETRIC_SPLIT_TOKEN)
        mHandler.removeCallbacksAndMessages(EXPAND_SPLIT_SHELL_TOKEN)
        mHandler.removeCallbacksAndMessages(PHONE_EMPTY_SPLIT_STEAL_TOKEN)
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
        // Prefer stealing them onto this VD over kill (SystemUI respawns killed shells on phone).
        scheduleCleanupEmptySplitOrganizerTasks("connect")
        schedulePhoneEmptySplitSteal("connect")
        scheduleCollapseAsymmetricSplit("connect")
        // When virtual display is created, launch default package first (if configured)
        if(mLauncherPackage != null) {
            startDefaultPackage()
        } else {
            // If no default package is configured, launch Home package as fallback
            startHomeLauncher()
        }
        scheduleStartupFreeformRecovery("connect")
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
        mSuppressEnsureFreeformUntil = 0L
        scheduleCleanupEmptySplitOrganizerTasks("reconnect")
        schedulePhoneEmptySplitSteal("reconnect")
        scheduleCollapseAsymmetricSplit("reconnect")
        scheduleStartupFreeformRecovery("reconnect")
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
        mPendingInsetFreeformPkgs.clear()
        mLastFreeformRelaunchAt.clear()
        mLastDisplayBounceAt.clear()
        mMinimizeConfirmAt.clear()
        mLastFreeformSize.clear()
        mLastResizeAt.clear()
        mSuppressDisplayBounceUntil = 0L
        mSuppressEnsureFreeformUntil = 0L
        mLastReclaimAt = 0L
        mLastEmptySplitCleanupAt = 0L
        mEmptySplitShellsSeenAt = 0L
        mSuppressPhoneSplitStealUntil = 0L
        mAsymmetricSplitSeenAt = 0L
        mLastAsymmetricSplitCollapseAt = 0L
        mAsymmetricSplitFingerprint = null
        mLastExpandSplitShellAt = 0L
        mSuspectOrphanDisplayIds.clear()
        mHandler.removeCallbacks(mDebouncedStackReclaim)
        mHandler.removeCallbacks(mDebouncedRestoreHiddenFreeform)
        mHandler.removeCallbacks(mDebouncedSplitFocusGuard)
        mHandler.removeCallbacksAndMessages(EMPTY_SPLIT_CLEANUP_TOKEN)
        mHandler.removeCallbacksAndMessages(ASYMMETRIC_SPLIT_TOKEN)
        mHandler.removeCallbacksAndMessages(EXPAND_SPLIT_SHELL_TOKEN)
        mHandler.removeCallbacksAndMessages(PHONE_EMPTY_SPLIT_STEAL_TOKEN)
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
        publishVirtualDisplayIdForSystemUi("destroy")
    }

    /** SystemUI reads this to rebind StageCoordinator onto the live AA VD. */
    private fun publishVirtualDisplayIdForSystemUi(reason: String) {
        try {
            val value = if (mDisplayId == Display.INVALID_DISPLAY) {
                Display.INVALID_DISPLAY
            } else {
                mDisplayId
            }
            Settings.Global.putInt(
                context.contentResolver,
                SystemUiSplitHook.SETTINGS_VD_DISPLAY_ID,
                value
            )
            log(TAG, "publish VD displayId[$reason]=$value")
        } catch (e: Throwable) {
            log(TAG, "publish VD displayId[$reason] failed:", e)
        }
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
     * Launch the app corresponding to Home package name.
     * Must land on the AA virtual display: a phone-side instance of the same package
     * (e.g. 嘟嘟mini opened on the handset) must be moved onto the VD — plain
     * moveTaskToFront / NEW_TASK often only resumes the phone task and the car never launches.
     */
    private fun startHomeLauncher(){
        if(mHomePackage == null) {
            log(TAG, "startHomeLauncher skipped: no launcher package")
            return
        }
        bringConfiguredPackageToVirtualDisplay(mHomePackage!!, trackAsHome = true)
    }

    /**
     * Launch the app corresponding to default launch package name
     * Called when virtual display is created. Same display-aware path as Home.
     */
    private fun startDefaultPackage(){
        if(mLauncherPackage == null) {
            log(TAG, "startDefaultPackage skipped: no launcher package")
            return
        }
        bringConfiguredPackageToVirtualDisplay(mLauncherPackage!!, trackAsHome = false)
    }

    /**
     * Ensure [packageName] is shown on the AA virtual display.
     * Order: existing VD task → move phone task to VD → startActivity on VD (with reclaim).
     */
    private fun bringConfiguredPackageToVirtualDisplay(packageName: String, trackAsHome: Boolean) {
        if (mDisplayId == Display.INVALID_DISPLAY) return
        val label = if (trackAsHome) "home" else "launch"
        fun remember(taskId: Int) {
            if (trackAsHome) mHomeTaskId = taskId else mLauncherPackageTaskId = taskId
        }
        fun clearRemembered() {
            if (trackAsHome) mHomeTaskId = null else mLauncherPackageTaskId = null
        }
        val cached = if (trackAsHome) mHomeTaskId else mLauncherPackageTaskId

        val onVd = findPackageTaskIdOnDisplay(packageName, mDisplayId)
            ?: cached?.takeIf { isTaskOnVirtualDisplay(it) }
        if (onVd != null) {
            remember(onVd)
            log(TAG, "bringToVD[$label]: already on VD task=$onVd")
            moveTaskToFront(onVd)
            if (!trackAsHome) {
                markVirtualDisplayOwnership(onVd, packageName)
                mSuppressEnsureFreeformUntil = 0L
                scheduleEnsureFreeform(onVd, "bringToVD-resume", force = true)
                scheduleEnsureFreeformForPackage(packageName, "bringToVD-resume")
            }
            return
        }

        val onPhone = findPackageTaskIdOnDisplay(packageName, Display.DEFAULT_DISPLAY)
            ?: cached?.takeIf { findRootTaskInfoOnDisplay(it, Display.DEFAULT_DISPLAY) != null }
        if (onPhone != null) {
            log(TAG, "bringToVD[$label]: move phone task=$onPhone onto VD")
            if (moveConfiguredPackageTaskToVirtualDisplay(onPhone, packageName, trackAsHome)) {
                remember(onPhone)
                return
            }
            log(TAG, "bringToVD[$label]: move failed; falling back to startActivity")
        }

        clearRemembered()
        startActivity(packageName, 0)
        // singleTask / affinity may still resume the phone instance despite launchDisplayId.
        mHandler.postDelayed({
            ensureConfiguredPackageOnVirtualDisplay(packageName, trackAsHome, "$label-reclaim")
        }, 250L)
    }

    /** moveRootTaskToDisplay for Home/default-launch without split-replace side effects. */
    private fun moveConfiguredPackageTaskToVirtualDisplay(
        taskId: Int,
        packageName: String,
        trackAsHome: Boolean
    ): Boolean {
        if (mDisplayId == Display.INVALID_DISPLAY) return false
        return try {
            // Short suppress only for our own move; Home is bounce-excluded so reclaim won't fight.
            mSuppressDisplayBounceUntil = SystemClock.uptimeMillis() + 250L
            Instances.iActivityTaskManager.moveRootTaskToDisplay(taskId, mDisplayId)
            AndroidHook.FuckAppUseApplicationContext.markPackageOnVirtualDisplay(packageName, mDisplayId)
            if (!trackAsHome) {
                markVirtualDisplayOwnership(taskId, packageName)
                mSuppressEnsureFreeformUntil = 0L
                scheduleEnsureFreeform(taskId, "bringToVD-launch", force = true)
            }
            bringTaskToFrontOnDisplay(taskId)
            true
        } catch (e: Throwable) {
            log(TAG, "moveConfiguredPackageTaskToVirtualDisplay failed: task=$taskId", e)
            false
        }
    }

    private fun ensureConfiguredPackageOnVirtualDisplay(
        packageName: String,
        trackAsHome: Boolean,
        reason: String
    ) {
        if (mIsDestroying || mDisplayId == Display.INVALID_DISPLAY) return
        if (hasPackageTaskOnDisplay(packageName, mDisplayId)) {
            findPackageTaskIdOnDisplay(packageName, mDisplayId)?.let { taskId ->
                if (trackAsHome) mHomeTaskId = taskId else mLauncherPackageTaskId = taskId
                if (!trackAsHome) {
                    markVirtualDisplayOwnership(taskId, packageName)
                    mSuppressEnsureFreeformUntil = 0L
                    scheduleEnsureFreeform(taskId, reason, force = true)
                    scheduleEnsureFreeformForPackage(packageName, reason)
                }
            }
            return
        }
        val onPhone = findPackageTaskIdOnDisplay(packageName, Display.DEFAULT_DISPLAY) ?: return
        log(TAG, "bringToVD[$reason]: package still on phone task=$onPhone; moving")
        if (moveConfiguredPackageTaskToVirtualDisplay(onPhone, packageName, trackAsHome)) {
            if (trackAsHome) mHomeTaskId = onPhone else mLauncherPackageTaskId = onPhone
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
     *
     * Walks organizer `childTaskIds` too: on OneUI, stage leaves are often nested under a
     * freeform StageCoordinator root and may not appear as their own RootTaskInfo entries.
     */
    private fun getSplitAppTasksOnDisplay(
        displayId: Int = mDisplayId
    ): List<Pair<Int, String>> {
        if (displayId == Display.INVALID_DISPLAY) return emptyList()
        return try {
            val tasks = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
            // pkg -> taskId (first writer wins = top-most)
            val preferred = linkedMapOf<String, Int>()
            val fallback = linkedMapOf<String, Int>()

            fun consider(taskId: Int, pkg: String?, mode: Int?, organizer: Boolean) {
                val name = pkg?.takeIf { it.isNotBlank() } ?: return
                if (isBounceExcludedPackage(name) || name == mHomePackage) return
                if (!isSplitStageMode(mode)) return
                if (!organizer) {
                    preferred.putIfAbsent(name, taskId)
                } else {
                    fallback.putIfAbsent(name, taskId)
                }
            }

            fun walk(taskInfo: Any, depth: Int) {
                if (depth > 3) return
                val taskId = try {
                    (taskInfo as? ActivityTaskManager.RootTaskInfo)?.taskId
                        ?: taskInfo.getObjectAs("taskId", Int::class.javaPrimitiveType) as? Int
                        ?: taskInfo.invokeMethod("getTaskId", args(), argTypes()) as? Int
                } catch (_: Throwable) {
                    null
                } ?: return
                val pkg = try {
                    (taskInfo as? ActivityTaskManager.RootTaskInfo)?.topActivity?.packageName
                        ?: (taskInfo.getObjectAs("topActivity", ComponentName::class.java) as? ComponentName)
                            ?.packageName
                } catch (_: Throwable) {
                    null
                }
                consider(taskId, pkg, getWindowingMode(taskInfo), isCreatedByOrganizer(taskInfo))
                readChildTaskIds(taskInfo)?.forEach { childId ->
                    val child = tasks.firstOrNull { it.taskId == childId } ?: resolveTaskObject(childId)
                    if (child != null) walk(child, depth + 1)
                }
            }

            for (info in tasks) walk(info, 0)
            val merged = linkedMapOf<String, Int>()
            preferred.forEach { (pkg, id) -> merged[pkg] = id }
            fallback.forEach { (pkg, id) -> merged.putIfAbsent(pkg, id) }
            merged.map { (pkg, id) -> id to pkg }
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
                taskInfo.getObjectAs("mCreatedByOrganizer", Boolean::class.java) as? Boolean
            },
            {
                taskInfo.invokeMethod("isCreatedByOrganizer", args(), argTypes()) as? Boolean
            },
            {
                taskInfo.invokeMethod("getCreatedByOrganizer", args(), argTypes()) as? Boolean
            },
        )
        for (read in readers) {
            try {
                if (read() == true) return true
            } catch (_: Throwable) {
            }
        }
        // OneUI dumpsys shows mCreatedByOrganizer=true but ezxhelper field access often misses it.
        var clazz: Class<*>? = taskInfo.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField("mCreatedByOrganizer")
                field.isAccessible = true
                if (field.getBoolean(taskInfo)) return true
            } catch (_: Throwable) {
            }
            try {
                val field = clazz.getDeclaredField("createdByOrganizer")
                field.isAccessible = true
                if (field.getBoolean(taskInfo)) return true
            } catch (_: Throwable) {
            }
            clazz = clazz.superclass
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
            // Launch options are often ignored on VD; OneUI may also restore maximized bounds
            // after the first ensure — keep demoting this package across long retries.
            if (launchWindowingMode == WINDOWING_MODE_FREEFORM) {
                scheduleEnsureFreeformForPackage(componentName.packageName, "launchActivityAsUser")
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
        if (!isVirtualDisplay) {
            // Intentional swipe to phone: do not bounce/reclaim back for a short window.
            // Do NOT suppress when moving onto the VD — that blocks OneUI freeform→split bounce.
            mSuppressDisplayBounceUntil = SystemClock.uptimeMillis() + 2000L
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
            // Intentional move back onto VD: allow freeform ensure + split bounce immediately.
            mSuppressEnsureFreeformUntil = 0L
            mSuppressDisplayBounceUntil = 0L
            // Phone↔VD can leave empty stage shells that make moveFreeformTaskToSplit → "no display".
            // Steal phone shells onto this VD; do not force-wipe healthy idle stages on the VD.
            relocateEmptyPhoneSplitShellsToVirtualDisplay("moveTaskId-to-vd")
            cleanupEmptySplitOrganizerTasks("moveTaskId-to-vd", force = false)
            scheduleCleanupEmptySplitOrganizerTasks("moveTaskId-to-vd")
            schedulePhoneEmptySplitSteal("moveTaskId-to-vd")
            if (!packageName.isNullOrBlank()) {
                scheduleEnsureFreeformForPackage(packageName, "moveTaskId")
            } else {
                scheduleEnsureFreeform(taskId, "moveTaskId", force = true)
            }
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
        // Caption swipe-down minimize / drag-off-screen leave freeform invisible; plain
        // moveTaskToFront / setFocusedTask does not restore it (stack tap then looks broken).
        if (isOneUiSplitEnabled() && isTaskOnVirtualDisplay(taskId)) {
            restoreHiddenFreeformIfNeeded(taskId, "moveTaskToFront", userRequested = true)
        }
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
                // Next open of this app must demote maximized/fullscreen left by caption maximize.
                markPendingInsetFreeform(packageName)
                if (!hasPackageTaskOnDisplay(packageName, mDisplayId)) {
                    AndroidHook.FuckAppUseApplicationContext.clearPackageVirtualDisplay(packageName)
                    untrackPackage(packageName)
                }
            }
            // Caption maximize → fullscreen often leaves empty stage shells; wipe so the next
            // freeform open can enter split without a phone↔VD round-trip.
            if (removed && onVirtualDisplay && isOneUiSplitEnabled()) {
                scheduleCleanupEmptySplitOrganizerTasks("removeTask")
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
        val readers: List<() -> Int?> = listOf(
            { taskInfo.invokeMethod("getWindowingMode", args(), argTypes()) as? Int },
            {
                val conf = taskInfo.invokeMethod("getConfiguration", args(), argTypes()) ?: return@listOf null
                val win = conf.invokeMethod("getWindowConfiguration", args(), argTypes())
                    ?: return@listOf null
                win.invokeMethod("getWindowingMode", args(), argTypes()) as? Int
            },
            { taskInfo.getObjectAs("windowingMode", Int::class.javaPrimitiveType) as? Int },
            { taskInfo.getObjectAs("mWindowingMode", Int::class.javaPrimitiveType) as? Int },
        )
        for (read in readers) {
            try {
                val mode = read()
                if (mode != null) return mode
            } catch (_: Throwable) {
            }
        }
        return null
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
     * User-triggered wipe of **all** OneUI StageCoordinator / split-stage shells (empty or live)
     * on the AA VD, phone, and orphan ATM displays. Skips confirm delays; always force-removes.
     * No-op when EnableOneUiSplit is off.
     */
    fun forceCleanupAllSplitShells(reason: String = "manual"): Int {
        if (!isOneUiSplitEnabled()) {
            log(TAG, "forceCleanupAllSplitShells[$reason]: skipped (OneUI split off)")
            return 0
        }
        mEmptySplitShellsSeenAt = 0L
        clearAsymmetricSplitObservation()

        val displayIds = linkedSetOf<Int>()
        displayIds += collectDisplaysForEmptySplitCleanup()
        // Manual wipe must not miss live split: RootTaskInfo.createdByOrganizer is often unset
        // so discovery-by-organizer skips the AA VD; probe every known display id.
        try {
            Instances.displayManager.getDisplays().forEach { displayIds += it.displayId }
        } catch (_: Throwable) {
        }
        for (info in getAllRootTaskInfosReflect()) {
            readTaskDisplayId(info)?.takeIf { it != Display.INVALID_DISPLAY }?.let { displayIds += it }
        }
        for (displayId in displayIds.toList()) {
            if (getSplitAppTasksOnDisplay(displayId).isNotEmpty()) {
                displayIds += displayId
            }
        }

        log(
            TAG,
            "forceCleanupAllSplitShells[$reason]: mDisplayId=$mDisplayId scanning=${displayIds.sorted()}"
        )

        val toRemove = linkedSetOf<Int>()
        for (displayId in displayIds) {
            val orphan = !isDisplayKnownToDisplayManager(displayId)
            toRemove += if (orphan) {
                collectOrphanDisplaySplitTaskIds(displayId, "manual:$reason")
            } else {
                collectAllSplitOrganizerTaskIdsOnDisplay(displayId, "manual:$reason")
            }
        }

        var removed = 0
        for (taskId in toRemove.sortedDescending()) {
            if (removeOrganizerTaskQuietly(taskId, "manual:$reason", forceAlways = true)) removed++
        }

        mSuspectOrphanDisplayIds.removeAll { id ->
            try {
                Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(id).isEmpty()
            } catch (_: Throwable) {
                true
            }
        }

        try {
            cleanupEmptySplitOrganizerTasks("after-manual:$reason", force = true)
        } catch (e: Throwable) {
            log(TAG, "forceCleanupAllSplitShells[$reason] follow-up failed:", e)
        }

        // Wiping StageCoordinator / live split often leaves VD apps fullscreen and sets
        // ensureFreeform suppress (reclaim path). Re-enable freeform caption so the user can
        // enter split again without reconnecting AA.
        mSuppressEnsureFreeformUntil = 0L
        mEmptySplitShellsSeenAt = 0L
        clearAsymmetricSplitObservation()
        if (mDisplayId != Display.INVALID_DISPLAY) {
            try {
                applyVirtualDisplayPolicies("manual-cleanup")
            } catch (e: Throwable) {
                log(TAG, "forceCleanupAllSplitShells[$reason] applyPolicies failed:", e)
            }
            try {
                val vdTasks = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
                for (info in vdTasks) {
                    val pkg = info.topActivity?.packageName?.takeIf { it.isNotBlank() } ?: continue
                    if (pkg == mHomePackage || pkg == mLauncherPackage) continue
                    if (isBounceExcludedPackage(pkg) || IGNORE_RECENT_PACKAGE.contains(pkg)) continue
                    markPendingInsetFreeform(pkg)
                    markVirtualDisplayOwnership(info.taskId, pkg)
                    scheduleEnsureFreeform(info.taskId, "manual-cleanup", force = true)
                    scheduleEnsureFreeformForPackage(pkg, "manual-cleanup")
                }
            } catch (e: Throwable) {
                log(TAG, "forceCleanupAllSplitShells[$reason] restore freeform failed:", e)
            }
            scheduleCleanupEmptySplitOrganizerTasks("after-manual:$reason")
            // SystemUI respawns empty `#3→#4/#5` on the phone; steal them onto the VD so
            // the next caption split can use those stages (killing alone never sticks).
            schedulePhoneEmptySplitSteal("after-manual:$reason")
        }

        log(TAG, "forceCleanupAllSplitShells[$reason]: removed=$removed candidates=${toRemove.size}")
        return removed
    }

    /**
     * Move idle phone StageCoordinator trees (`#3→#4/#5`) onto the AA VD and, if any remain
     * on the phone, remove only those phone leftovers. Never force-wipes the VD copy.
     * Skips while split entry / multi-window is active — mutating shells then makes the app vanish.
     */
    private fun schedulePhoneEmptySplitSteal(reason: String) {
        if (!isOneUiSplitEnabled() || mDisplayId == Display.INVALID_DISPLAY) return
        mHandler.removeCallbacksAndMessages(PHONE_EMPTY_SPLIT_STEAL_TOKEN)
        val now = SystemClock.uptimeMillis()
        for (delay in PHONE_EMPTY_SPLIT_STEAL_DELAYS_MS) {
            val tagged = if (delay == 0L) "phone-steal:$reason" else "phone-steal:$reason-$delay"
            mHandler.postAtTime(
                {
                    if (shouldSuppressPhoneSplitShellMutation()) {
                        log(TAG, "phone-steal skipped (split entry/MW): $tagged")
                        return@postAtTime
                    }
                    // Rescue orphan/ghost TDA shells first (reconnect leaves #3→#4/#5 on old VD),
                    // then phone DEFAULT_DISPLAY. Kill phone leftovers only after VD has a shell.
                    val movedOrphan = relocateEmptyOrphanSplitShellsToVirtualDisplay(tagged)
                    val movedPhone = relocateEmptyPhoneSplitShellsToVirtualDisplay(tagged)
                    val moved = movedOrphan + movedPhone
                    val vdHasShell = hasIdleSplitShellOnVirtualDisplay()
                    // Never kill phone shells unless VD already has a StageCoordinator.
                    // Kill-without-steal leaves zero shells → moveFreeformTaskToSplit "no display".
                    if (moved > 0 || vdHasShell) {
                        cleanupPhoneEmptySplitShellsOnly(tagged)
                    } else {
                        log(
                            TAG,
                            "phone-steal: skip phone kill (moved=$moved vdShell=$vdHasShell) [$tagged]"
                        )
                    }
                    if (vdHasShell || moved > 0) {
                        scheduleExpandSplitShellToFullDisplay(tagged)
                    }
                },
                PHONE_EMPTY_SPLIT_STEAL_TOKEN,
                now + delay
            )
        }
    }

    /**
     * True while caption-split entry / live split / AppsEdge chooser must not see shell
     * relocate/kill. Must NOT treat lone FREEFORM as suppress — freeform on the VD is the
     * idle state where phone empty `#3→#4/#5` must be stolen so caption-split can work.
     */
    private fun shouldSuppressPhoneSplitShellMutation(): Boolean {
        if (SystemClock.uptimeMillis() < mSuppressPhoneSplitStealUntil) return true
        if (isDisplayInSplitStages()) return true
        if (isSplitChooserActiveOnPhone()) return true
        return false
    }

    private fun noteSplitEntryShellGuard(reason: String) {
        mSuppressPhoneSplitStealUntil =
            SystemClock.uptimeMillis() + SPLIT_ENTRY_SHELL_GUARD_MS
        log(TAG, "split-entry shell guard ${SPLIT_ENTRY_SHELL_GUARD_MS}ms [$reason]")
    }

    /**
     * Caption freeform→split: companions were moved to the phone so OneUI does not auto-pair
     * them into main/left (leaving the freeform app on side/right). Suppress reclaim for the
     * AppsEdge window and drop VD ownership of those companions.
     */
    fun onFreeformToSplitCompanionsParked(
        splitTaskId: Int,
        parked: List<Pair<Int, String>>
    ) {
        if (!isOneUiSplitEnabled()) return
        noteSplitEntryShellGuard("freeform-to-split:$splitTaskId")
        val now = SystemClock.uptimeMillis()
        mSuppressDisplayBounceUntil = now + SUPPRESS_RECLAIM_AFTER_REPLACE_MS
        mSuppressEnsureFreeformUntil = now + SUPPRESS_ENSURE_FREEFORM_MS
        for ((taskId, pkg) in parked) {
            forgetVirtualDisplayOwnership(taskId, pkg)
            AndroidHook.FuckAppUseApplicationContext.clearPackageVirtualDisplay(pkg)
            log(TAG, "freeform→split park: forgot ownership task=$taskId pkg=$pkg")
        }
        if (parked.isNotEmpty()) {
            log(
                TAG,
                "freeform→split park: ${parked.size} companion(s) suppressed for splitTask=$splitTaskId"
            )
        }
    }

    /**
     * Only steal phone shells from intentional idle paths — never from windowing-mode /
     * dismissing mid-transition (that aborts OneUI caption split).
     */
    private fun mayRelocatePhoneSplitShells(reason: String): Boolean {
        if (shouldSuppressPhoneSplitShellMutation()) return false
        return reason.contains("phone-steal") ||
            reason.contains("connect") ||
            reason.contains("reconnect") ||
            reason.contains("before-ensure") ||
            reason.contains("after-manual") ||
            reason.contains("moveTaskId") ||
            reason.contains("orphan")
    }

    /**
     * After OneUI aborts split (SSRM / PiP / APP_DOES_NOT_SUPPORT_MULTIWINDOW), empty stage
     * containers often remain — on the AA VD, the phone DEFAULT_DISPLAY, and **orphaned**
     * ATM TaskDisplayAreas (display ids still visible to ATM but gone from DisplayManager,
     * e.g. a prior AA VD session). Those ghost trees occupy OneUI main/side stages so
     * `moveFreeformTaskToSplit` fails with "no display" after reconnect.
     *
     * Prefer relocating phone idle shells onto the AA VD (reuse) over deleting them —
     * SystemUI recreates killed shells on the phone within seconds.
     *
     * Shells must stay empty for [EMPTY_SPLIT_CONFIRM_MS] so an in-progress split entry
     * (briefly empty side stage) is not destroyed — unless [force] (destroy path).
     * Healthy idle StageCoordinator on the AA VD is preserved unless [force]/manual/
     * asymmetric — those shells are what the next split entry needs. Orphan/reconnect paths
     * relocate ghost-TDA shells onto the live VD instead of wiping the VD copy.
     */
    private fun cleanupEmptySplitOrganizerTasks(reason: String, force: Boolean = false) {
        if (mIsDestroying && !force) return
        if (!isOneUiSplitEnabled()) return

        // Prefer relocating reusable idle StageCoordinator trees onto the live AA VD.
        // Orphan/ghost TDAs (prior AA VD after reconnect) are always rescued — killing them
        // just makes SystemUI respawn `#3→#4/#5` on the phone and breaks caption-split.
        // Phone steal: do it whenever we are not mid split-entry. Gating on reason alone
        // (old mayRelocatePhoneSplitShells) let windowing-mode cleanup kill phone `#3`
        // while steal still said "no empty stage roots" — permanent "no display".
        if (mDisplayId != Display.INVALID_DISPLAY && !mIsDestroying) {
            relocateEmptyOrphanSplitShellsToVirtualDisplay(reason)
            if (!shouldSuppressPhoneSplitShellMutation()) {
                relocateEmptyPhoneSplitShellsToVirtualDisplay(reason)
            }
        }

        // Never wipe healthy idle StageCoordinator on the live VD during ensureFreeform
        // demote (`force=true` + before-ensure): that deletes the shells OneUI needs for
        // freeform caption / caption-split and leaves apps stuck fullscreen on the VD.
        val wipeHealthyIdleOnVd =
            !reason.contains("before-ensure") &&
                (force ||
                    reason.contains("manual") ||
                    reason.contains("destroy") ||
                    reason.contains("asymmetric"))
        // Orphan cleanup used to wipe healthy idle shells on the new VD too (reason contains
        // "orphan"). That fights reconnect steal — keep VD idle shells unless manual/destroy.
        val wipeOrphanDisplays = force ||
            reason.contains("manual") ||
            reason.contains("destroy") ||
            reason.contains("asymmetric") ||
            reason.contains("orphan") ||
            reason.contains("windowing-mode") ||
            reason.contains("connect") ||
            reason.contains("reconnect")
        val guardPhoneShells = !force && shouldSuppressPhoneSplitShellMutation()

        val zombieIds = linkedSetOf<Int>()
        val displayIds = collectDisplaysForEmptySplitCleanup()
        for (displayId in displayIds) {
            val orphan = !isDisplayKnownToDisplayManager(displayId)
            // Live displays: only wipe when no real split apps remain.
            // Orphan displays: also wipe chooser-only (AppsEdge) trees — they poison global stages.
            if (!orphan && getSplitAppTasksOnDisplay(displayId).isNotEmpty()) continue
            // Mid split-entry: never kill phone `#3→#4/#5` (OneUI still wiring stages).
            if (!orphan && displayId == Display.DEFAULT_DISPLAY && guardPhoneShells) continue
            // After a successful steal, orphan TDAs should be empty; if shells remain and we
            // could not move them (and this is not a wipe path), leave them for the next steal.
            if (orphan && !wipeOrphanDisplays && !force) continue
            val ids = if (orphan) {
                // Reusable empty StageCoordinator roots should already have been moved to the
                // live VD above. Only collect leftovers (chooser / stuck removal / non-roots).
                collectOrphanDisplaySplitTaskIds(displayId, reason).filter { taskId ->
                    // Do not kill a shell that now lives on the AA VD (steal just succeeded).
                    val onVd = findRootTaskInfoOnDisplay(taskId, mDisplayId) != null ||
                        resolveTaskObject(taskId)?.let { readTaskDisplayId(it) } == mDisplayId
                    !onVd
                }
            } else {
                // Phone empty `#3→#4/#5` (and AppsEdge-only) poison global stages for the AA VD.
                collectEmptySplitOrganizerTaskIdsOnDisplay(
                    displayId,
                    reason,
                    treatChooserAsEmpty = displayId == Display.DEFAULT_DISPLAY
                )
            }
            if (displayId == mDisplayId && !wipeHealthyIdleOnVd) {
                // Reusable idle StageCoordinator on the live VD must stay — even when
                // mIsRemovalRequested is stuck true (ATM.removeTask lie). Clearing the flag
                // keeps caption-split working; deleting makes SystemUI respawn on the phone.
                for (taskId in ids) {
                    if (isTaskRemovalRequested(taskId)) {
                        clearTaskRemovalRequested(taskId)
                    }
                }
            } else {
                zombieIds += ids
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
        // Phone idle shells often need forceRemove — ATM.removeTask lies while leaving stages.
        var removed = 0
        for (taskId in zombieIds.sortedDescending()) {
            if (removeOrganizerTaskQuietly(
                    taskId,
                    reason,
                    forceAlways = force || reason.contains("orphan") || reason.contains("manual") ||
                        reason.contains("phone-steal") || reason.contains("phone-rekill")
                )
            ) {
                removed++
            }
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

    /** Remove empty split shells that are still on the phone only (never touch the AA VD). */
    private fun cleanupPhoneEmptySplitShellsOnly(reason: String) {
        if (!isOneUiSplitEnabled() || mIsDestroying) return
        if (shouldSuppressPhoneSplitShellMutation()) {
            log(TAG, "cleanupPhoneEmpty[$reason]: skipped (split entry/MW)")
            return
        }
        val zombieIds = try {
            collectEmptySplitOrganizerTaskIdsOnDisplay(
                Display.DEFAULT_DISPLAY,
                reason,
                treatChooserAsEmpty = true
            )
        } catch (e: Throwable) {
            log(TAG, "cleanupPhoneEmpty[$reason] failed:", e)
            return
        }
        if (zombieIds.isEmpty()) return
        var removed = 0
        for (taskId in zombieIds.sortedDescending()) {
            if (removeOrganizerTaskQuietly(taskId, reason, forceAlways = true)) removed++
        }
        if (removed > 0) {
            log(TAG, "cleanupPhoneEmpty[$reason]: removed $removed phone empty organizer task(s)")
        }
    }

    /**
     * Move empty OneUI StageCoordinator roots from the phone onto the AA VD so
     * `moveFreeformTaskToSplit` can bind stages to the display hosting freeform apps.
     * Returns how many roots were successfully relocated.
     */
    private fun relocateEmptyPhoneSplitShellsToVirtualDisplay(reason: String): Int {
        if (!isOneUiSplitEnabled() || mDisplayId == Display.INVALID_DISPLAY || mIsDestroying) return 0
        if (shouldSuppressPhoneSplitShellMutation()) {
            log(TAG, "relocatePhoneSplit[$reason]: skipped (split entry/MW)")
            return 0
        }
        return relocateEmptySplitStageRoots(
            findEmptySplitStageRootsOnDisplay(Display.DEFAULT_DISPLAY),
            reason,
            "relocatePhoneSplit"
        )
    }

    /**
     * After AA disconnect/reconnect the prior VD id often becomes an ATM-only ghost TDA while
     * idle `#3→#4/#5` stay there. Steal those onto the live VD instead of removeTask (SystemUI
     * respawns killed shells on the phone and caption-split keeps failing with "no display").
     */
    private fun relocateEmptyOrphanSplitShellsToVirtualDisplay(reason: String): Int {
        if (!isOneUiSplitEnabled() || mDisplayId == Display.INVALID_DISPLAY || mIsDestroying) return 0
        return relocateEmptySplitStageRoots(
            findEmptyOrphanSplitStageRoots(),
            reason,
            "relocateOrphanSplit"
        )
    }

    private fun relocateEmptySplitStageRoots(
        roots: List<Int>,
        reason: String,
        logPrefix: String
    ): Int {
        if (roots.isEmpty()) {
            log(TAG, "$logPrefix[$reason]: no empty stage roots")
            return 0
        }
        log(TAG, "$logPrefix[$reason]: candidates=${roots.joinToString()} -> vd=$mDisplayId")

        var moved = 0
        val now = SystemClock.uptimeMillis()
        // Only suppress bounce for our own organizer move re-entrancy — a long suppress
        // blocks reclaim when the user immediately caption-splits (app dumps to phone and
        // the car stays blank until they tap the task).
        mSuppressDisplayBounceUntil = now + 300L
        for (rootId in roots) {
            if (!moveRootTaskToDisplaySafe(rootId, mDisplayId, reason)) {
                log(TAG, "$logPrefix[$reason]: root=$rootId not on vd after move")
                continue
            }
            // Stolen shells often keep mIsRemovalRequested from a prior failed removeTask;
            // cleanup would then wipe the VD copy and SystemUI respawns on the phone.
            clearTaskRemovalRequested(rootId)
            resolveTaskObject(rootId)?.let { root ->
                readChildTaskIds(root)?.forEach { childId ->
                    clearTaskRemovalRequested(childId)
                }
            }
            moved++
            log(TAG, "$logPrefix[$reason]: moved root=$rootId -> vd=$mDisplayId")
        }
        if (moved > 0) {
            mEmptySplitShellsSeenAt = 0L
        }
        return moved
    }

    /**
     * `IActivityTaskManager.moveRootTaskToDisplay` often no-ops for OneUI fullscreen
     * StageCoordinator on the phone; `cmd activity display move-stack` succeeds.
     *
     * Success is **only** "task is on [displayId]". Do not treat "left the phone" as success —
     * reconnect shells sit on orphan TDAs (not phone), and that heuristic false-positives.
     */
    private fun moveRootTaskToDisplaySafe(taskId: Int, displayId: Int, reason: String): Boolean {
        fun onTarget(): Boolean {
            if (findRootTaskInfoOnDisplay(taskId, displayId) != null) return true
            val taskObj = resolveTaskObject(taskId) ?: return false
            if (readTaskDisplayId(taskObj) == displayId) return true
            return try {
                Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
                    .any { it.taskId == taskId || readChildTaskIds(it)?.contains(taskId) == true }
            } catch (_: Throwable) {
                false
            }
        }
        if (onTarget()) return true

        try {
            Instances.iActivityTaskManager.moveRootTaskToDisplay(taskId, displayId)
            if (onTarget()) return true
        } catch (e: Throwable) {
            log(TAG, "relocateSplit[$reason]: moveRootTaskToDisplay($taskId) failed:", e)
        }

        // Same path as `cmd activity display move-stack <id> <display>` (works when binder no-ops).
        try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("cmd", "activity", "display", "move-stack", taskId.toString(), displayId.toString())
            )
            val code = proc.waitFor()
            if (code == 0 && onTarget()) {
                log(TAG, "relocateSplit[$reason]: move-stack cmd ok task=$taskId -> $displayId")
                return true
            }
            log(TAG, "relocateSplit[$reason]: move-stack cmd exit=$code onTarget=${onTarget()} task=$taskId")
        } catch (e: Throwable) {
            log(TAG, "relocateSplit[$reason]: move-stack cmd failed task=$taskId:", e)
        }
        return onTarget()
    }

    /** True when the AA VD already hosts an idle (or live) StageCoordinator / split stages. */
    private fun hasIdleSplitShellOnVirtualDisplay(): Boolean {
        if (mDisplayId == Display.INVALID_DISPLAY) return false
        if (isDisplayInSplitStages()) return true
        return try {
            val tasks = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
            for (info in tasks) {
                val node = resolveTaskObject(info.taskId) ?: info
                val kids = readChildTaskIds(node) ?: readChildTaskIds(info) ?: continue
                if (kids.size < 2 && !kids.any { kid ->
                        val c = resolveTaskObject(kid) ?: return@any false
                        isSplitStageMode(getWindowingMode(c)) || hasSplitStageConfig(c)
                    }
                ) {
                    continue
                }
                if (isCreatedByOrganizer(node) || isCreatedByOrganizer(info) ||
                    getWindowingMode(node) == WINDOWING_MODE_FREEFORM
                ) {
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Phone DEFAULT_DISPLAY StageCoordinator roots that only host empty main/side stages
     * (classic `#3→#4/#5` with `sz=0`). These idle shells occupy global stages and make
     * AA VD `moveFreeformTaskToSplit` fail with "no display".
     */
    private fun findEmptyPhoneSplitStageRoots(): List<Int> =
        findEmptySplitStageRootsOnDisplay(Display.DEFAULT_DISPLAY)

    /**
     * Idle StageCoordinator roots stuck on ATM ghost displays (prior AA VD after reconnect).
     */
    private fun findEmptyOrphanSplitStageRoots(): List<Int> {
        if (mDisplayId == Display.INVALID_DISPLAY) return emptyList()
        val roots = linkedSetOf<Int>()
        val displayIds = linkedSetOf<Int>()
        displayIds += mSuspectOrphanDisplayIds
        for (displayId in collectDisplaysForEmptySplitCleanup()) {
            if (displayId == mDisplayId || displayId == Display.DEFAULT_DISPLAY) continue
            if (!isDisplayKnownToDisplayManager(displayId)) displayIds += displayId
        }
        for (displayId in displayIds) {
            if (displayId == mDisplayId || displayId == Display.DEFAULT_DISPLAY) continue
            if (isDisplayKnownToDisplayManager(displayId)) continue
            roots += findEmptySplitStageRootsOnDisplay(displayId)
        }
        return roots.toList()
    }

    /**
     * Empty StageCoordinator roots on [displayId] (phone or orphan TDA): parent with only
     * empty main/side stage children, no real app activities.
     *
     * Prefer the same [collectEmptySplitOrganizerTaskIds] detector as cleanup. Stage leaves
     * (`#4`/`#5`, `sz=0`) often have null/empty childTaskIds — the previous fallback did
     * `readChildTaskIds ?: continue` and never walked `rootTaskId` up to `#3`, so phone/orphan
     * steal logged "no empty stage roots" while cleanup still saw `shells=4,5,3` and killed them.
     */
    private fun findEmptySplitStageRootsOnDisplay(displayId: Int): List<Int> {
        val tasks = try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        } catch (e: Throwable) {
            log(TAG, "findEmptySplitStageRootsOnDisplay($displayId) failed:", e)
            return emptyList()
        }
        val roots = linkedSetOf<Int>()

        fun readRootTaskId(taskInfo: Any, selfId: Int): Int? {
            val readers: List<() -> Int?> = listOf(
                { taskInfo.getObjectAs("rootTaskId", Int::class.javaPrimitiveType) as? Int },
                { taskInfo.getObjectAs("rootTaskId", Int::class.java) as? Int },
                { taskInfo.invokeMethod("getRootTaskId", args(), argTypes()) as? Int },
                { readParentOrRootTaskId(taskInfo) }
            )
            for (read in readers) {
                try {
                    val id = read() ?: continue
                    if (id > 0) return id
                } catch (_: Throwable) {
                }
            }
            return if (selfId > 0) selfId else null
        }

        // Primary: same empty-shell set as cleanup, then collapse stage leaves → coordinator root.
        val zombieIds = collectEmptySplitOrganizerTaskIds(tasks, treatChooserAsEmpty = true)
        if (zombieIds.isNotEmpty()) {
            for (id in zombieIds) {
                val info = tasks.firstOrNull { it.taskId == id }
                val node = resolveTaskObject(id)
                val probe = node ?: info ?: continue
                val mode = getWindowingMode(probe) ?: info?.let { getWindowingMode(it) }
                val stageLeaf = isSplitStageMode(mode) || hasSplitStageConfig(probe) ||
                    (info != null && hasSplitStageConfig(info))
                if (stageLeaf) {
                    readRootTaskId(probe, id)?.takeIf { it > 0 }?.let { roots += it }
                    info?.let { readRootTaskId(it, id) }?.takeIf { it > 0 }?.let { roots += it }
                } else {
                    // Organizer / fullscreen `#3` parent (or freeform StageCoordinator).
                    roots += id
                }
            }
            // Drop stage ids that slipped in without a resolvable parent.
            val stageOnly = roots.filter { rootId ->
                val probe = resolveTaskObject(rootId)
                    ?: tasks.firstOrNull { it.taskId == rootId }
                    ?: return@filter false
                val mode = getWindowingMode(probe)
                if (!isSplitStageMode(mode)) return@filter false
                val kids = readChildTaskIds(probe)
                kids == null || kids.isEmpty()
            }
            roots.removeAll(stageOnly.toSet())
            if (roots.isEmpty() && zombieIds.isNotEmpty()) {
                // Last resort: classic OneUI numbering — lowest empty organizer id is `#3`.
                zombieIds.minOrNull()?.let { roots += it }
            }
            if (roots.isNotEmpty()) {
                log(
                    TAG,
                    "findEmptySplitStageRootsOnDisplay($displayId): " +
                        "roots=${roots.joinToString()} from zombies=${zombieIds.joinToString()}"
                )
                return roots.toList()
            }
            log(
                TAG,
                "findEmptySplitStageRootsOnDisplay($displayId): " +
                    "zombies=${zombieIds.joinToString()} but no movable root"
            )
        }

        // Secondary walk when collectEmpty is empty but RootTaskInfo still shows `#3→#4/#5`.
        for (info in tasks) {
            val node = resolveTaskObject(info.taskId) ?: info
            val childIds = readChildTaskIds(node) ?: readChildTaskIds(info) ?: continue
            if (childIds.isEmpty()) continue
            if (childIds.any { taskHasAppActivity(it, tasks, treatChooserAsEmpty = true) }) {
                continue
            }
            var stageChildren = 0
            for (childId in childIds) {
                val child = resolveTaskObject(childId)
                    ?: tasks.firstOrNull { it.taskId == childId }
                    ?: continue
                val childMode = getWindowingMode(child)
                if (isSplitStageMode(childMode) || hasSplitStageConfig(child) ||
                    isCreatedByOrganizer(child)
                ) {
                    stageChildren++
                }
            }
            val organizerParent = isCreatedByOrganizer(node) || isCreatedByOrganizer(info)
            if (stageChildren >= 1 || (organizerParent && childIds.size >= 2)) {
                roots += info.taskId
            }
        }
        return roots.toList()
    }

    private fun scheduleCollapseAsymmetricSplit(reason: String) {
        if (!isOneUiSplitEnabled() || mDisplayId == Display.INVALID_DISPLAY) return
        mHandler.removeCallbacksAndMessages(ASYMMETRIC_SPLIT_TOKEN)
        val now = SystemClock.uptimeMillis()
        for (delay in ASYMMETRIC_SPLIT_FOLLOWUP_DELAYS_MS) {
            val taggedReason = if (delay == 0L) reason else "$reason-$delay"
            mHandler.postAtTime(
                { collapseAsymmetricSplitOnVirtualDisplay(taggedReason) },
                ASYMMETRIC_SPLIT_TOKEN,
                now + delay
            )
        }
    }

    private data class AsymmetricSplitState(
        val survivorTaskId: Int,
        val survivorPkg: String,
        val emptyShellTaskIds: List<Int>
    )

    /**
     * OneUI on the AA VD often fails to collapse when one split pane is closed: the survivor
     * stays `multi-window`/`stage=main|side` at half width with an empty opposite stage shell.
     * Empty-shell cleanup skips this case (a real split app is still present) and ensureFreeform
     * refuses MULTI_WINDOW — so without this path the layout stays a zombie forever.
     *
     * Confirm for [ASYMMETRIC_SPLIT_CONFIRM_MS] so mid-entry (briefly empty side) is not collapsed.
     * Abort while AppsEdge chooser is up on the phone (user still picking the second app).
     */
    private fun collapseAsymmetricSplitOnVirtualDisplay(reason: String, force: Boolean = false) {
        if (mIsDestroying && !force) return
        if (!isOneUiSplitEnabled() || mDisplayId == Display.INVALID_DISPLAY) return

        val state = detectAsymmetricSplitOnVirtualDisplay()
        if (state == null) {
            clearAsymmetricSplitObservation()
            return
        }
        if (isSplitChooserActiveOnPhone()) {
            // Mid-entry: leave empty opposite stage alone while user picks the second app.
            clearAsymmetricSplitObservation()
            return
        }

        val fingerprint =
            "${state.survivorTaskId}:${state.survivorPkg}:${state.emptyShellTaskIds.sorted().joinToString()}"
        val now = SystemClock.uptimeMillis()
        if (!force) {
            if (mAsymmetricSplitFingerprint != fingerprint) {
                mAsymmetricSplitFingerprint = fingerprint
                mAsymmetricSplitSeenAt = now
                log(
                    TAG,
                    "asymmetricSplit[$reason]: survivor=${state.survivorPkg}#${state.survivorTaskId} " +
                        "empty=${state.emptyShellTaskIds.joinToString()} — confirm in ${ASYMMETRIC_SPLIT_CONFIRM_MS}ms"
                )
                return
            }
            if (mAsymmetricSplitSeenAt == 0L ||
                now - mAsymmetricSplitSeenAt < ASYMMETRIC_SPLIT_CONFIRM_MS
            ) {
                return
            }
            if (now - mLastAsymmetricSplitCollapseAt < ASYMMETRIC_SPLIT_MIN_INTERVAL_MS) return
        }
        mLastAsymmetricSplitCollapseAt = now
        clearAsymmetricSplitObservation()

        var removed = 0
        for (taskId in state.emptyShellTaskIds.sortedDescending()) {
            if (removeOrganizerTaskQuietly(taskId, "asymmetric:$reason")) removed++
        }

        // ensureFreeform leaves MULTI_WINDOW alone — force the survivor out explicitly.
        markPendingInsetFreeform(state.survivorPkg)
        markVirtualDisplayOwnership(state.survivorTaskId, state.survivorPkg)
        setTaskWindowingModeSafe(state.survivorTaskId, WINDOWING_MODE_FREEFORM)
        var after = findRootTaskInfoOnDisplay(state.survivorTaskId, mDisplayId)
        var afterMode = after?.let { getWindowingMode(it) }
        if (afterMode != WINDOWING_MODE_FREEFORM) {
            val relaunched = relaunchTaskAsFreeform(
                state.survivorTaskId,
                state.survivorPkg,
                "asymmetric:$reason"
            )
            if (!relaunched) {
                setTaskWindowingModeSafe(state.survivorTaskId, WINDOWING_MODE_FULLSCREEN)
                setTaskWindowingModeSafe(state.survivorTaskId, WINDOWING_MODE_FREEFORM)
            }
            after = findRootTaskInfoOnDisplay(state.survivorTaskId, mDisplayId)
                ?: findRootTaskInfoOnDisplay(state.survivorTaskId, Display.DEFAULT_DISPLAY)
            afterMode = after?.let { getWindowingMode(it) }
        }
        resizeTaskToFreeformBounds(state.survivorTaskId)
        // Package-scoped retries: relaunch may recreate the task id.
        scheduleEnsureFreeformForPackage(state.survivorPkg, "asymmetric-collapse:$reason")
        // Exiting split often leaves isMinimized / not-visible freeform — looks "gone" until
        // the user taps the task. Force unminimize immediately (do not wait for auto-confirm).
        mHandler.post {
            restoreHiddenFreeformIfNeeded(
                state.survivorTaskId,
                "asymmetric-collapse:$reason",
                userRequested = true
            )
            ensureTaskFreeformOnVirtualDisplay(
                state.survivorTaskId,
                "asymmetric-collapse-visible:$reason",
                force = true
            )
        }
        scheduleCleanupEmptySplitOrganizerTasks("after-asymmetric:$reason")

        log(
            TAG,
            "asymmetricSplit[$reason]: collapsed survivor=${state.survivorPkg}#${state.survivorTaskId} " +
                "removedEmpty=$removed afterMode=${afterMode ?: "?"}"
        )
    }

    private fun clearAsymmetricSplitObservation() {
        mAsymmetricSplitSeenAt = 0L
        mAsymmetricSplitFingerprint = null
    }

    /**
     * Exactly one real split-stage app on the AA VD plus at least one empty opposite stage shell.
     */
    private fun detectAsymmetricSplitOnVirtualDisplay(): AsymmetricSplitState? {
        if (mDisplayId == Display.INVALID_DISPLAY) return null
        val splitApps = getSplitAppTasksOnDisplay()
        if (splitApps.size != 1) return null
        val (survivorTaskId, survivorPkg) = splitApps.first()

        val tasks = try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
        } catch (e: Throwable) {
            log(TAG, "detectAsymmetricSplit list failed:", e)
            return null
        }

        val emptyShells = linkedSetOf<Int>()
        fun considerEmptyStage(taskId: Int, info: Any?) {
            if (taskId == survivorTaskId) return
            if (taskHasAppActivity(taskId, tasks)) return
            val mode = info?.let { getWindowingMode(it) }
                ?: resolveTaskObject(taskId)?.let { getWindowingMode(it) }
            if (!isSplitStageMode(mode)) return
            emptyShells.add(taskId)
            val childIds = info?.let { readChildTaskIds(it) } ?: return
            childIds.forEach { childId ->
                if (childId != survivorTaskId && !taskHasAppActivity(childId, tasks)) {
                    emptyShells.add(childId)
                }
            }
        }

        for (info in tasks) {
            considerEmptyStage(info.taskId, info)
            // Organizer parent may only expose empty opposite stages via childTaskIds.
            if (isCreatedByOrganizer(info) || getWindowingMode(info) == WINDOWING_MODE_FREEFORM) {
                readChildTaskIds(info)?.forEach { childId ->
                    if (childId == survivorTaskId || taskHasAppActivity(childId, tasks)) return@forEach
                    val child = tasks.firstOrNull { it.taskId == childId }
                    considerEmptyStage(childId, child)
                }
            }
        }
        if (emptyShells.isEmpty()) return null
        return AsymmetricSplitState(survivorTaskId, survivorPkg, emptyShells.toList())
    }

    /** True when OneUI AppsEdge / split chooser is up on the phone (mid split-entry). */
    private fun isSplitChooserActiveOnPhone(): Boolean {
        return try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(Display.DEFAULT_DISPLAY)
                .any { info -> isSplitChooserPackage(info.topActivity?.packageName) }
        } catch (_: Throwable) {
            false
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
     * Ghost TaskDisplayArea leftovers: empty StageCoordinator / split-stage shells and
     * AppsEdge chooser trees. Do **not** blanket-remove every organizer child — reconnect
     * races can park a live app on the old VD id for a moment (killing it is user-visible).
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
        // Empty organizer trees only (same predicate as phone empty-shell cleanup).
        toRemove += collectEmptySplitOrganizerTaskIds(tasks, treatChooserAsEmpty = true)
        // AppsEdge chooser roots that are not covered by the empty-shell walk.
        for (taskInfo in tasks) {
            if (!isSplitChooserPackage(taskInfo.topActivity?.packageName)) continue
            toRemove.add(taskInfo.taskId)
            readChildTaskIds(taskInfo)?.forEach { childId ->
                if (!taskHasAppActivity(childId, tasks, treatChooserAsEmpty = true)) {
                    toRemove.add(childId)
                }
            }
        }
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
     * All StageCoordinator / split-stage / AppsEdge shells on [displayId], including those
     * still hosting live apps (manual one-click wipe).
     */
    private fun collectAllSplitOrganizerTaskIdsOnDisplay(displayId: Int, reason: String): List<Int> {
        val tasks = try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        } catch (e: Throwable) {
            log(TAG, "forceCleanup[$reason] list display=$displayId failed:", e)
            return emptyList()
        }
        val ids = collectAllSplitOrganizerTaskIds(tasks)
        if (ids.isNotEmpty()) {
            log(TAG, "forceCleanup[$reason]: display=$displayId tasks=${ids.joinToString()}")
        } else if (tasks.isNotEmpty()) {
            // Diagnose empty collection while dumpsys still shows #3→#4/#5.
            val summary = tasks.joinToString("; ") { info ->
                val task = resolveTaskObject(info.taskId)
                val mode = getWindowingMode(info) ?: task?.let { getWindowingMode(it) }
                val orgInfo = isCreatedByOrganizer(info)
                val orgTask = task?.let { isCreatedByOrganizer(it) } == true
                val kids = readChildTaskIds(info) ?: task?.let { readChildTaskIds(it) }
                "#${info.taskId} mode=$mode org=$orgInfo/$orgTask kids=${kids?.joinToString() ?: "-"}"
            }
            log(TAG, "forceCleanup[$reason]: display=$displayId collected=0 roots=${tasks.size} [$summary]")
        }
        return ids
    }

    private fun collectAllSplitOrganizerTaskIds(
        tasks: List<ActivityTaskManager.RootTaskInfo>
    ): List<Int> {
        val toRemove = linkedSetOf<Int>()

        fun isHomeishTask(taskInfo: Any): Boolean {
            if (taskInfo is ActivityTaskManager.RootTaskInfo && isSystemHomeTask(taskInfo)) {
                return true
            }
            val pkg = try {
                (taskInfo as? ActivityTaskManager.RootTaskInfo)?.topActivity?.packageName
                    ?: (taskInfo.getObjectAs("topActivity", ComponentName::class.java) as? ComponentName)
                        ?.packageName
            } catch (_: Throwable) {
                null
            }
            if (pkg != null && (pkg == mHomePackage || pkg == mLauncherPackage ||
                    isBounceExcludedPackage(pkg))
            ) {
                return true
            }
            return try {
                val conf = taskInfo.invokeMethod("getConfiguration", args(), argTypes())
                    ?: return false
                val winConf = conf.invokeMethod("getWindowConfiguration", args(), argTypes())
                    ?: return false
                val activityType = winConf.invokeMethod("getActivityType", args(), argTypes()) as? Int
                activityType == ACTIVITY_TYPE_HOME
            } catch (_: Throwable) {
                false
            }
        }

        fun readTaskId(taskInfo: Any): Int? {
            return try {
                (taskInfo as? ActivityTaskManager.RootTaskInfo)?.taskId
                    ?: taskInfo.getObjectAs("taskId", Int::class.javaPrimitiveType) as? Int
                    ?: taskInfo.getObjectAs("mTaskId", Int::class.javaPrimitiveType) as? Int
                    ?: taskInfo.invokeMethod("getTaskId", args(), argTypes()) as? Int
            } catch (_: Throwable) {
                null
            }
        }

        fun readPkg(taskInfo: Any): String? {
            return try {
                (taskInfo as? ActivityTaskManager.RootTaskInfo)?.topActivity?.packageName
                    ?: (taskInfo.getObjectAs("topActivity", ComponentName::class.java) as? ComponentName)
                        ?.packageName
            } catch (_: Throwable) {
                null
            }
        }

        fun hasStageConfig(taskInfo: Any): Boolean {
            val readers: List<() -> Any?> = listOf(
                {
                    val conf = taskInfo.invokeMethod("getConfiguration", args(), argTypes())
                        ?: return@listOf null
                    val win = conf.invokeMethod("getWindowConfiguration", args(), argTypes())
                        ?: return@listOf null
                    win.invokeMethod("getStageConfig", args(), argTypes())
                        ?: win.getObjectAs("mStageConfig", Int::class.javaPrimitiveType)
                        ?: win.getObjectAs("mStageConfig", Any::class.java)
                },
                { taskInfo.getObjectAs("mStageConfig", Any::class.java) },
            )
            for (read in readers) {
                try {
                    val stage = read() ?: continue
                    when (stage) {
                        is Number -> if (stage.toInt() != 0) return true
                        is String -> {
                            val s = stage.lowercase()
                            if (s.contains("main") || s.contains("side") || s.contains("left") ||
                                s.contains("right")
                            ) {
                                return true
                            }
                        }
                        else -> {
                            val s = stage.toString().lowercase()
                            if (s.contains("main") || s.contains("side") || s.contains("left") ||
                                s.contains("right")
                            ) {
                                return true
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            return false
        }

        // Prefer the live Task object: RootTaskInfo often omits createdByOrganizer / childTaskIds
        // on OneUI while Task.mCreatedByOrganizer and mChildren are correct (#3→#4/#5).
        fun resolveForWalk(taskId: Int, fallback: Any): Any {
            return resolveTaskObject(taskId) ?: fallback
        }

        fun walk(taskInfo: Any, depth: Int, ancestorIds: List<Int>) {
            if (depth > 5) return
            val taskId = readTaskId(taskInfo) ?: return
            val node = resolveForWalk(taskId, taskInfo)
            val mode = getWindowingMode(node) ?: getWindowingMode(taskInfo)
            val organizer = isCreatedByOrganizer(node) || isCreatedByOrganizer(taskInfo)
            val chooser = isSplitChooserPackage(readPkg(node) ?: readPkg(taskInfo))
            val splitStage = isSplitStageMode(mode) || hasStageConfig(node) || hasStageConfig(taskInfo)
            val childIds = readChildTaskIds(node) ?: readChildTaskIds(taskInfo)
            // OneUI StageCoordinator trees show up in several broken Reflection shapes:
            //  - VD live split: FREEFORM #3 with kids=app,app (mode=5)
            //  - phone/ATM view: FULLSCREEN #3 with kids=4,5 stage shells (mode=1) — common!
            //  - mCreatedByOrganizer often unreadable via RootTaskInfo / ezxhelper
            // Treat any non-home root with ≥2 child task ids as a split shell tree.
            val multiChildSplitShell = (childIds?.size ?: 0) >= 2 && !isHomeishTask(node) && !isHomeishTask(taskInfo)
            val freeformSplitShell =
                mode == WINDOWING_MODE_FREEFORM && (childIds?.size ?: 0) >= 2
            var childLooksLikeStage = false
            if (!multiChildSplitShell && !freeformSplitShell && childIds != null && childIds.isNotEmpty()) {
                for (childId in childIds) {
                    val child = resolveTaskObject(childId) ?: continue
                    val childMode = getWindowingMode(child)
                    if (isSplitStageMode(childMode) || isCreatedByOrganizer(child) ||
                        hasStageConfig(child)
                    ) {
                        childLooksLikeStage = true
                        break
                    }
                }
            }
            if (organizer || splitStage || chooser || freeformSplitShell ||
                multiChildSplitShell || childLooksLikeStage
            ) {
                toRemove.add(taskId)
                ancestorIds.forEach { toRemove.add(it) }
                childIds?.forEach { toRemove.add(it) }
            }
            childIds?.forEach { childId ->
                val child = tasks.firstOrNull { it.taskId == childId }
                    ?: resolveTaskObject(childId)
                if (child != null) {
                    walk(child, depth + 1, ancestorIds + taskId)
                } else if (organizer || splitStage || freeformSplitShell || multiChildSplitShell ||
                    childLooksLikeStage
                ) {
                    toRemove.add(childId)
                }
            }
        }

        for (info in tasks) walk(info, 0, emptyList())

        // Second pass: any RootTaskInfo that reports rootTaskId → organizer parent (stages as
        // separate roots with empty childTaskIds on the parent RootTaskInfo).
        for (info in tasks) {
            val rootId = try {
                info.getObjectAs("rootTaskId", Int::class.javaPrimitiveType) as? Int
                    ?: (info as Any).invokeMethod("getRootTaskId", args(), argTypes()) as? Int
            } catch (_: Throwable) {
                null
            } ?: continue
            if (rootId == info.taskId || rootId <= 0) continue
            val node = resolveTaskObject(info.taskId) ?: info
            val mode = getWindowingMode(node) ?: getWindowingMode(info)
            if (isSplitStageMode(mode) || isCreatedByOrganizer(node) || hasStageConfig(node)) {
                toRemove.add(info.taskId)
                toRemove.add(rootId)
            }
        }

        // Also resolve known stage ids that dumpsys shows under freeform parents but that never
        // appear as RootTaskInfo / childTaskIds (OneUI #4/#5 under #3).
        for (parentId in toRemove.toList()) {
            val parent = resolveTaskObject(parentId) ?: continue
            readChildTaskIds(parent)?.forEach { childId ->
                toRemove.add(childId)
                resolveTaskObject(childId)?.let { child ->
                    readChildTaskIds(child)?.forEach { toRemove.add(it) }
                }
            }
        }

        toRemove += collectEmptySplitOrganizerTaskIds(tasks, treatChooserAsEmpty = true)
        return toRemove.toList()
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
                // Prefer live Task children — RootTaskInfo.childTaskIds / createdByOrganizer often lie.
                val node = resolveTaskObject(taskInfo.taskId) ?: taskInfo
                if (isCreatedByOrganizer(node)) {
                    val childIds = readChildTaskIds(node) ?: readChildTaskIds(taskInfo)
                    if (childIds != null && childIds.isNotEmpty() &&
                        (isZombieSplitChildTree(childIds, tasks, treatChooserAsEmpty) ||
                            childIds.all { !taskHasAppActivity(it, tasks, treatChooserAsEmpty) })
                    ) {
                        childIds.forEach { id ->
                            if (!taskHasAppActivity(id, tasks, treatChooserAsEmpty)) toRemove.add(id)
                        }
                        toRemove.add(taskInfo.taskId)
                    }
                    continue
                }
                val childIds = readChildTaskIds(node) ?: readChildTaskIds(taskInfo) ?: continue
                if (childIds.isEmpty()) continue
                if (isZombieSplitChildTree(childIds, tasks, treatChooserAsEmpty) ||
                    (childIds.size >= 2 &&
                        childIds.all { !taskHasAppActivity(it, tasks, treatChooserAsEmpty) })
                ) {
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
            if (isSplitStageMode(childMode) ||
                (child != null && isCreatedByOrganizer(child)) ||
                (child != null && hasSplitStageConfig(child))
            ) {
                sawSplitStage = true
            }
        }
        return sawSplitStage
    }

    private fun hasSplitStageConfig(taskInfo: Any): Boolean {
        val readers: List<() -> Any?> = listOf(
            {
                val conf = taskInfo.invokeMethod("getConfiguration", args(), argTypes())
                    ?: return@listOf null
                val win = conf.invokeMethod("getWindowConfiguration", args(), argTypes())
                    ?: return@listOf null
                win.invokeMethod("getStageConfig", args(), argTypes())
                    ?: win.getObjectAs("mStageConfig", Int::class.javaPrimitiveType)
                    ?: win.getObjectAs("mStageConfig", Any::class.java)
            },
            { taskInfo.getObjectAs("mStageConfig", Any::class.java) },
        )
        for (read in readers) {
            try {
                val stage = read() ?: continue
                when (stage) {
                    is Number -> if (stage.toInt() != 0) return true
                    else -> {
                        val s = stage.toString().lowercase()
                        if (s.contains("main") || s.contains("side") || s.contains("left") ||
                            s.contains("right") || s.contains("top") || s.contains("bottom")
                        ) {
                            return true
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return false
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

    private fun removeOrganizerTaskQuietly(
        taskId: Int,
        reason: String,
        forceAlways: Boolean = false
    ): Boolean {
        return try {
            // Bypass removeTask(): no VD ownership / home-restart side effects for shells.
            mVdTaskIds.remove(taskId)
            val ok = try {
                Instances.iActivityTaskManager.removeTask(taskId)
            } catch (e: Throwable) {
                log(TAG, "cleanupEmptySplit[$reason]: removeTask($taskId) failed:", e)
                false
            }
            log(TAG, "cleanupEmptySplit[$reason]: removeTask($taskId)=$ok")
            if (!organizerTaskStillPresent(taskId)) return true

            // ATM.removeTask often returns true while leaving mIsRemovalRequested shells on
            // ghost TaskDisplayAreas (or even the AA VD). StageCoordinator keeps those roots
            // and moveFreeformTaskToSplit then fails with "no display" until SystemUI/reboot.
            // Auto cleanup: only force-remove stuck/orphan shells — never live idle roots.
            // Manual one-click ([forceAlways]): wipe live shells too.
            val stuck = isTaskRemovalRequested(taskId)
            val orphanCleanup = reason.contains("orphan")
            if (forceAlways || stuck || orphanCleanup) {
                forceRemoveOrganizerTask(taskId, reason)
            } else {
                false
            }
        } catch (e: Throwable) {
            log(TAG, "cleanupEmptySplit[$reason]: removeTask($taskId) failed:", e)
            false
        }
    }

    private fun organizerTaskStillPresent(taskId: Int): Boolean {
        if (resolveTaskObject(taskId) != null) return true
        return try {
            getAllRootTaskInfosReflect().any { it.taskId == taskId }
        } catch (_: Throwable) {
            false
        }
    }

    private fun isTaskRemovalRequested(taskId: Int): Boolean {
        val task = resolveTaskObject(taskId) ?: return false
        val readers: List<() -> Boolean?> = listOf(
            {
                task.getObjectAs("mIsRemovalRequested", Boolean::class.javaPrimitiveType) as? Boolean
            },
            {
                task.getObjectAs("mIsRemovalRequested", Boolean::class.java) as? Boolean
            },
            {
                task.invokeMethod("isRemovalRequested", args(), argTypes()) as? Boolean
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

    /** Clear stuck ATM removal flag so a relocated StageCoordinator stays usable on the AA VD. */
    private fun clearTaskRemovalRequested(taskId: Int) {
        if (taskId <= 0 || !isTaskRemovalRequested(taskId)) return
        val task = resolveTaskObject(taskId) ?: return
        val writers: List<() -> Boolean> = listOf(
            {
                task.putObject("mIsRemovalRequested", false)
                true
            },
            {
                var cls: Class<*>? = task.javaClass
                while (cls != null) {
                    try {
                        val field = cls.getDeclaredField("mIsRemovalRequested")
                        field.isAccessible = true
                        field.setBoolean(task, false)
                        return@listOf true
                    } catch (_: Throwable) {
                        cls = cls.superclass
                    }
                }
                false
            }
        )
        for (write in writers) {
            try {
                if (write() && !isTaskRemovalRequested(taskId)) {
                    log(TAG, "clearTaskRemovalRequested: task=$taskId")
                    return
                }
            } catch (_: Throwable) {
            }
        }
        if (isTaskRemovalRequested(taskId)) {
            log(TAG, "clearTaskRemovalRequested: still set task=$taskId")
        }
    }

    private fun forceRemoveOrganizerTask(taskId: Int, reason: String): Boolean {
        // Ghost TDAs often need the root on a real display before removeImmediately works.
        try {
            Instances.iActivityTaskManager.moveRootTaskToDisplay(taskId, Display.DEFAULT_DISPLAY)
        } catch (_: Throwable) {
        }
        val task = resolveTaskObject(taskId)
        if (task == null) {
            log(TAG, "cleanupEmptySplit[$reason]: force-remove($taskId) already gone after move")
            return true
        }
        val attempts: List<Pair<String, () -> Unit>> = listOf(
            "removeImmediately" to {
                task.invokeMethod("removeImmediately", args(), argTypes())
                Unit
            },
            "removeIfPossible" to {
                task.invokeMethod("removeIfPossible", args(), argTypes())
                Unit
            },
            "remove(false,reason)" to {
                task.invokeMethod(
                    "remove",
                    args(false, "AADisplay-empty-split"),
                    argTypes(java.lang.Boolean.TYPE, String::class.java)
                )
                Unit
            },
            "remove(false)" to {
                task.invokeMethod(
                    "remove",
                    args(false),
                    argTypes(java.lang.Boolean.TYPE)
                )
                Unit
            }
        )
        for ((label, attempt) in attempts) {
            try {
                attempt()
                if (!organizerTaskStillPresent(taskId)) {
                    log(TAG, "cleanupEmptySplit[$reason]: force $label($taskId) ok")
                    return true
                }
                log(TAG, "cleanupEmptySplit[$reason]: force $label($taskId) still present")
            } catch (e: Throwable) {
                log(TAG, "cleanupEmptySplit[$reason]: force $label($taskId) failed:", e)
            }
        }
        // Last resort: ATM.removeTask again after move-to-default.
        return try {
            val ok = Instances.iActivityTaskManager.removeTask(taskId)
            val gone = !organizerTaskStillPresent(taskId)
            log(
                TAG,
                "cleanupEmptySplit[$reason]: force removeTask-retry($taskId)=$ok gone=$gone"
            )
            gone
        } catch (e: Throwable) {
            log(TAG, "cleanupEmptySplit[$reason]: force-remove($taskId) exhausted:", e)
            false
        }
    }

    private fun readChildTaskIds(taskInfo: Any): IntArray? {
        readChildTaskIdsDirect(taskInfo)?.let { return it }
        // RootTaskInfo.childTaskIds is often empty on OneUI while live Task has mChildren
        // (#3→#4/#5). Resolve the Task and read children from there.
        val taskId = try {
            (taskInfo as? ActivityTaskManager.RootTaskInfo)?.taskId
                ?: taskInfo.getObjectAs("taskId", Int::class.javaPrimitiveType) as? Int
                ?: taskInfo.invokeMethod("getTaskId", args(), argTypes()) as? Int
        } catch (_: Throwable) {
            null
        } ?: return null
        val live = resolveTaskObject(taskId) ?: return null
        if (live === taskInfo) return null
        return readChildTaskIdsDirect(live)
    }

    /** Child ids from the given object only (no anyTaskForId fallback — avoids recursion). */
    private fun readChildTaskIdsDirect(taskInfo: Any): IntArray? {
        val readers: List<() -> IntArray?> = listOf(
            { taskInfo.getObjectAs("childTaskIds", IntArray::class.java) as? IntArray },
            {
                @Suppress("UNCHECKED_CAST")
                (taskInfo.getObjectAs("childTaskIds", Array::class.java) as? Array<*>)
                    ?.mapNotNull { (it as? Number)?.toInt() }
                    ?.toIntArray()
            },
            // Live Task objects (from anyTaskForId): OneUI often leaves RootTaskInfo.childTaskIds
            // empty while stages exist as mChildren (#3→#4/#5).
            {
                val count = taskInfo.invokeMethod("getChildCount", args(), argTypes()) as? Int
                    ?: return@listOf null
                if (count <= 0) return@listOf null
                val ids = ArrayList<Int>(count)
                for (i in 0 until count) {
                    val child = try {
                        taskInfo.invokeMethod(
                            "getChildAt",
                            args(i),
                            argTypes(Integer.TYPE)
                        )
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    val id = try {
                        child.getObjectAs("mTaskId", Int::class.javaPrimitiveType) as? Int
                            ?: child.invokeMethod("getTaskId", args(), argTypes()) as? Int
                    } catch (_: Throwable) {
                        null
                    }
                    if (id != null) ids.add(id)
                }
                ids.toIntArray().takeIf { it.isNotEmpty() }
            },
            {
                @Suppress("UNCHECKED_CAST")
                val children = taskInfo.getObjectAs("mChildren", List::class.java) as? List<*>
                    ?: return@listOf null
                children.mapNotNull { child ->
                    if (child == null) return@mapNotNull null
                    try {
                        child.getObjectAs("mTaskId", Int::class.javaPrimitiveType) as? Int
                            ?: child.invokeMethod("getTaskId", args(), argTypes()) as? Int
                    } catch (_: Throwable) {
                        null
                    }
                }.toIntArray().takeIf { it.isNotEmpty() }
            },
        )
        for (read in readers) {
            try {
                val ids = read()
                if (ids != null && ids.isNotEmpty()) return ids
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

    private fun moveTaskToBackSafe(taskId: Int): Boolean {
        val am = Instances.activityManager as Any
        val atm = Instances.iActivityTaskManager as Any
        val attempts: List<() -> Boolean> = listOf(
            {
                am.invokeMethod(
                    "moveTaskToBack",
                    args(taskId),
                    argTypes(Integer.TYPE)
                ) as? Boolean ?: true
            },
            {
                atm.invokeMethod(
                    "moveTaskToBack",
                    args(taskId),
                    argTypes(Integer.TYPE)
                ) as? Boolean ?: true
            },
            {
                atm.invokeMethod(
                    "moveTaskTreeToBack",
                    args(taskId),
                    argTypes(Integer.TYPE)
                )
                true
            },
        )
        for (attempt in attempts) {
            try {
                if (attempt()) return true
            } catch (_: Throwable) {
            }
        }
        log(TAG, "moveTaskToBack failed: task=$taskId")
        return false
    }

    /**
     * While OneUI split stages are on the AA VD, the configured Home / fullscreen launcher often
     * stays resumed underneath and steals focus. That:
     *  - hides/disables Shell's StageCoordinatorSplitDivider (gap shows Home through the 8px seam)
     *  - leaves ratio stuck near 50/50 because divider drag never reaches a live SplitLayout
     *
     * Push Home behind always while split is up. Re-focus a split pane only when Home actually
     * owns focus / climbs above the split tree — never during normal divider drag.
     */
    private fun maintainSplitForegroundFocus(reason: String) {
        if (!isOneUiSplitEnabled() || mIsDestroying || mDisplayId == Display.INVALID_DISPLAY) return
        val splitApps = getSplitAppTasksOnDisplay()
        if (splitApps.isEmpty()) return

        val homeTasks = mutableListOf<Pair<Int, String>>()
        var homeAboveSplit = false
        try {
            val roots = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
            val firstSplitIdx = roots.indexOfFirst { info ->
                val mode = getWindowingMode(info)
                isSplitStageMode(mode) ||
                    (mode == WINDOWING_MODE_FREEFORM && isCreatedByOrganizer(info))
            }
            roots.forEachIndexed { index, info ->
                val pkg = info.topActivity?.packageName ?: return@forEachIndexed
                if (pkg != mHomePackage && pkg != mLauncherPackage) return@forEachIndexed
                val mode = getWindowingMode(info)
                if (mode != null && isSplitStageMode(mode)) return@forEachIndexed
                homeTasks.add(info.taskId to pkg)
                if (firstSplitIdx < 0 || index < firstSplitIdx) homeAboveSplit = true
            }
        } catch (e: Throwable) {
            log(TAG, "splitFocusGuard[$reason] scan failed:", e)
            return
        }

        if (homeTasks.isEmpty()) return

        var pushedHome = false
        for ((taskId, pkg) in homeTasks) {
            if (moveTaskToBackSafe(taskId)) {
                pushedHome = true
                log(TAG, "splitFocusGuard[$reason]: moveTaskToBack homeish=$pkg#$taskId")
            }
        }

        val focusedIsHome = isFocusedRootHomeOnVirtualDisplay()
        if (homeAboveSplit || focusedIsHome) {
            val focusId = splitApps.first().first
            if (setFocusedTaskSafe(focusId)) {
                log(
                    TAG,
                    "splitFocusGuard[$reason]: focus split task=$focusId pkg=${splitApps.first().second} homeAbove=$homeAboveSplit focusedHome=$focusedIsHome"
                )
            }
        } else if (pushedHome) {
            log(TAG, "splitFocusGuard[$reason]: pushed home behind split (focus unchanged)")
        }
    }

    /** True when the display-focused root task on the AA VD is the configured Home/launcher. */
    private fun isFocusedRootHomeOnVirtualDisplay(): Boolean {
        if (mDisplayId == Display.INVALID_DISPLAY) return false
        val readers: List<() -> Any?> = listOf(
            {
                val atm = Instances.iActivityTaskManager as Any
                atm.invokeMethod("getFocusedRootTaskInfo", args(), argTypes())
            },
            {
                val atm = Instances.iActivityTaskManager as Any
                atm.invokeMethod(
                    "getRootTaskInfoOnDisplay",
                    args(mDisplayId),
                    argTypes(Integer.TYPE)
                )
            },
        )
        for (read in readers) {
            try {
                val info = read() ?: continue
                val displayId = try {
                    info.getObjectAs("displayId", Integer.TYPE) as? Int
                        ?: info.getObjectAs("displayId", Int::class.java) as? Int
                } catch (_: Throwable) {
                    null
                }
                if (displayId != null && displayId != mDisplayId) continue
                val pkg = try {
                    (info.getObjectAs("topActivity", ComponentName::class.java) as? ComponentName)
                        ?.packageName
                } catch (_: Throwable) {
                    null
                } ?: continue
                if (pkg == mHomePackage || pkg == mLauncherPackage) return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun scheduleSplitFocusGuard(reason: String) {
        if (!isOneUiSplitEnabled()) return
        mHandler.removeCallbacks(mDebouncedSplitFocusGuard)
        mHandler.postDelayed(mDebouncedSplitFocusGuard, 80L)
        // One more pass after Shell settles (Home often steals resume a beat later).
        mHandler.postDelayed({ maintainSplitForegroundFocus("$reason-late") }, 350L)
        scheduleExpandSplitShellToFullDisplay(reason)
    }

    private fun scheduleExpandSplitShellToFullDisplay(reason: String) {
        if (!isOneUiSplitEnabled() || mDisplayId == Display.INVALID_DISPLAY) return
        mHandler.removeCallbacksAndMessages(EXPAND_SPLIT_SHELL_TOKEN)
        val now = SystemClock.uptimeMillis()
        for (delay in EXPAND_SPLIT_SHELL_DELAYS_MS) {
            val tagged = if (delay == 0L) reason else "$reason-$delay"
            mHandler.postAtTime(
                { expandSplitShellToFullDisplay(tagged) },
                EXPAND_SPLIT_SHELL_TOKEN,
                now + delay
            )
        }
    }

    /**
     * OneUI `moveFreeformTaskToSplit` keeps the StageCoordinator root at the prior freeform
     * inset bounds (`FREEFORM_INSET_RATIO`). Resizing the organizer parent to the full VD lets
     * Shell's SplitLayout remap stages. Parent detection must not rely solely on
     * `childTaskIds` — OneUI often leaves that array empty while stages exist as separate roots
     * with `rootTaskId` pointing at the freeform organizer (especially after 央视影音 PiP).
     */
    private fun expandSplitShellToFullDisplay(reason: String) {
        if (mIsDestroying || !isOneUiSplitEnabled() || mDisplayId == Display.INVALID_DISPLAY) return
        val full = buildFullDisplayBounds() ?: return
        val now = SystemClock.uptimeMillis()
        if (now - mLastExpandSplitShellAt < EXPAND_SPLIT_SHELL_MIN_INTERVAL_MS) return

        val tasks = try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
        } catch (e: Throwable) {
            log(TAG, "expandSplitShell[$reason] list failed:", e)
            return
        }

        val stageInfos = tasks.filter {
            isSplitStageMode(getWindowingMode(it)) || hasSplitStageConfig(it)
        }
        val splitApps = getSplitAppTasksOnDisplay()
        val parentIds = linkedSetOf<Int>()

        fun isSplitishChild(childId: Int): Boolean {
            if (stageInfos.any { it.taskId == childId }) return true
            val child = tasks.firstOrNull { it.taskId == childId } ?: resolveTaskObject(childId)
                ?: return false
            return isSplitStageMode(getWindowingMode(child)) ||
                hasSplitStageConfig(child) ||
                isCreatedByOrganizer(child)
        }

        for (info in tasks) {
            val kids = readChildTaskIds(info) ?: continue
            if (kids.isEmpty()) continue
            if (kids.any { isSplitishChild(it) }) parentIds += info.taskId
        }
        for (stage in stageInfos) {
            readParentOrRootTaskId(stage)?.let { parentIds += it }
        }
        // Shrunken freeform/organizer with stage children — do not require splitApps.
        // getSplitAppTasks can miss AppsEdge-only / mid-entry layouts, and RootTaskInfo
        // often omits nested #4/#5 until we resolve the live Task.
        for (info in tasks) {
            if (!isCreatedByOrganizer(info) && getWindowingMode(info) != WINDOWING_MODE_FREEFORM) {
                continue
            }
            val bounds = getTaskBoundsSafe(info.taskId, info) ?: continue
            if (isNearlyFullscreenBounds(bounds)) continue
            val kids = readChildTaskIds(info) ?: intArrayOf()
            if (kids.size >= 2 || kids.any { isSplitishChild(it) }) {
                parentIds += info.taskId
            }
        }

        if (parentIds.isEmpty()) {
            if (stageInfos.isNotEmpty() || splitApps.isNotEmpty()) {
                log(
                    TAG,
                    "expandSplitShell[$reason]: no parent candidate " +
                        "stages=${stageInfos.map { it.taskId }} apps=$splitApps"
                )
            }
            return
        }

        var expandedAny = false
        for (parentId in parentIds) {
            val info = tasks.firstOrNull { it.taskId == parentId }
            val parentBounds = getTaskBoundsSafe(parentId, info) ?: continue
            if (isNearlyFullscreenBounds(parentBounds)) continue

            clearRememberedFreeformBounds(parentId, full)
            var ok = resizeTaskToBounds(parentId, full)
            var after = getTaskBoundsSafe(parentId, null)
            if (after == null || !isNearlyFullscreenBounds(after)) {
                // Same path as `am task resize` with FORCED — plain SYSTEM sometimes no-ops
                // on OneUI StageCoordinator until Shell finishes applying inset bounds.
                ok = resizeTaskToBounds(parentId, full, RESIZE_MODE_SYSTEM_FORCED) || ok
                after = getTaskBoundsSafe(parentId, null)
            }
            // Remap stages still clipped inside the old inset shell (not a live full-VD divider).
            val pw = parentBounds.width().coerceAtLeast(1)
            val ph = parentBounds.height().coerceAtLeast(1)
            val stageIds = linkedSetOf<Int>().apply {
                val parentObj = resolveTaskObject(parentId) ?: info
                if (parentObj != null) {
                    readChildTaskIds(parentObj)?.forEach { add(it) }
                }
                stageInfos.filter { readParentOrRootTaskId(it) == parentId }
                    .forEach { add(it.taskId) }
            }
            for (childId in stageIds) {
                val child = tasks.firstOrNull { it.taskId == childId } ?: resolveTaskObject(childId)
                if (child == null) continue
                if (!isSplitStageMode(getWindowingMode(child)) && !hasSplitStageConfig(child)) {
                    continue
                }
                val cb = getTaskBoundsSafe(childId, child) ?: continue
                val stillInOldShell =
                    cb.left >= parentBounds.left - 4 &&
                        cb.top >= parentBounds.top - 4 &&
                        cb.right <= parentBounds.right + 4 &&
                        cb.bottom <= parentBounds.bottom + 4
                if (!stillInOldShell) continue
                val mapped = Rect(
                    full.left + (cb.left - parentBounds.left) * full.width() / pw,
                    full.top + (cb.top - parentBounds.top) * full.height() / ph,
                    full.left + (cb.right - parentBounds.left) * full.width() / pw,
                    full.top + (cb.bottom - parentBounds.top) * full.height() / ph
                )
                resizeTaskToBounds(childId, mapped, RESIZE_MODE_SYSTEM_FORCED)
                val leafIds = readChildTaskIds(child) ?: continue
                leafIds.forEach { leafId ->
                    resizeTaskToBounds(leafId, mapped, RESIZE_MODE_SYSTEM_FORCED)
                }
            }
            expandedAny = true
            val finalBounds = after ?: getTaskBoundsSafe(parentId, null)
            log(
                TAG,
                "expandSplitShell[$reason]: parent=#$parentId " +
                    "${parentBounds.toShortString()} -> ${full.toShortString()} " +
                    "ok=$ok after=${finalBounds?.toShortString() ?: "?"}"
            )
        }
        if (expandedAny) mLastExpandSplitShellAt = now
    }

    private fun readParentOrRootTaskId(taskInfo: Any): Int? {
        val selfId = try {
            (taskInfo as? ActivityTaskManager.RootTaskInfo)?.taskId
                ?: taskInfo.getObjectAs("taskId", Int::class.javaPrimitiveType) as? Int
        } catch (_: Throwable) {
            null
        }
        val readers: List<() -> Int?> = listOf(
            { taskInfo.getObjectAs("parentTaskId", Int::class.javaPrimitiveType) as? Int },
            { taskInfo.getObjectAs("parentTaskId", Int::class.java) as? Int },
            { taskInfo.getObjectAs("rootTaskId", Int::class.javaPrimitiveType) as? Int },
            { taskInfo.getObjectAs("rootTaskId", Int::class.java) as? Int },
            { taskInfo.invokeMethod("getParentTaskId", args(), argTypes()) as? Int },
            { taskInfo.invokeMethod("getRootTaskId", args(), argTypes()) as? Int }
        )
        for (read in readers) {
            try {
                val id = read() ?: continue
                if (id > 0 && id != selfId) return id
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun buildFullDisplayBounds(): Rect? {
        if (mDisplayId == Display.INVALID_DISPLAY) return null
        return try {
            val display = Instances.displayManager.getDisplay(mDisplayId) ?: return null
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return null
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        } catch (e: Throwable) {
            log(TAG, "buildFullDisplayBounds failed:", e)
            null
        }
    }

    /** Stop OneUI from re-applying the prior freeform inset after we expand the split root. */
    private fun clearRememberedFreeformBounds(taskId: Int, full: Rect) {
        val task = resolveTaskObject(taskId) ?: return
        val fields = listOf(
            "mLastNonFullscreenBounds",
            "mLastFreeformBoundsBeforeStash",
            "mLastNonFullscreenBoundsForResume"
        )
        for (name in fields) {
            try {
                var clazz: Class<*>? = task.javaClass
                while (clazz != null) {
                    try {
                        val field = clazz.getDeclaredField(name)
                        field.isAccessible = true
                        field.set(task, Rect(full))
                        break
                    } catch (_: NoSuchFieldException) {
                        clazz = clazz.superclass
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * After AA connect/reconnect, re-assert inset freeform on VD apps so caption split works
     * without the user's maximize→close→reopen workaround. Retries at 350ms for tasks that
     * land on the VD slightly after default launch / reclaim.
     */
    private fun scheduleStartupFreeformRecovery(reason: String) {
        if (!isOneUiSplitEnabled() || mDisplayId == Display.INVALID_DISPLAY) return
        mSuppressEnsureFreeformUntil = 0L
        mHandler.post { recoverFreeformOnVirtualDisplayApps(reason) }
        mHandler.postDelayed({ recoverFreeformOnVirtualDisplayApps("$reason-350") }, 350L)
    }

    private fun recoverFreeformOnVirtualDisplayApps(reason: String) {
        if (mIsDestroying || mDisplayId == Display.INVALID_DISPLAY) return
        if (!isOneUiSplitEnabled()) return
        if (isDisplayInSplitStages()) return
        try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
                .forEach { info ->
                    val pkg = info.topActivity?.packageName?.takeIf { it.isNotBlank() } ?: return@forEach
                    if (pkg == mHomePackage || pkg == mLauncherPackage) return@forEach
                    if (isBounceExcludedPackage(pkg) || IGNORE_RECENT_PACKAGE.contains(pkg)) return@forEach
                    markVirtualDisplayOwnership(info.taskId, pkg)
                    scheduleEnsureFreeform(info.taskId, reason, force = true)
                    scheduleEnsureFreeformForPackage(pkg, reason)
                }
        } catch (e: Throwable) {
            log(TAG, "recoverFreeformOnVirtualDisplayApps[$reason] failed:", e)
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
     * Package-scoped ensure: finds the task id on each tick (OneUI often recreates task ids)
     * and keeps demoting until inset freeform sticks.
     */
    private fun scheduleEnsureFreeformForPackage(packageName: String, reason: String) {
        if (!isOneUiSplitEnabled() || packageName.isBlank()) return
        if (isBounceExcludedPackage(packageName)) return
        markPendingInsetFreeform(packageName)
        for (delay in ENSURE_FREEFORM_DELAYS_MS) {
            val tagged = if (delay == 0L) reason else "$reason-$delay"
            val run = Runnable {
                try {
                    Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
                        .filter { info -> info.topActivity?.packageName == packageName }
                        .forEach { info ->
                            markVirtualDisplayOwnership(info.taskId, packageName)
                            ensureTaskFreeformOnVirtualDisplay(info.taskId, tagged, force = true)
                        }
                } catch (e: Throwable) {
                    log(TAG, "ensureFreeformForPkg[$tagged] failed:", e)
                }
            }
            if (delay == 0L) mHandler.post(run) else mHandler.postDelayed(run, delay)
        }
    }

    private fun markPendingInsetFreeform(packageName: String?) {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (isBounceExcludedPackage(pkg)) return
        if (pkg == mHomePackage || pkg == mLauncherPackage) return
        mPendingInsetFreeformPkgs[pkg] = SystemClock.uptimeMillis() + PENDING_INSET_FREEFORM_MS
        log(TAG, "pending inset freeform: pkg=$pkg for ${PENDING_INSET_FREEFORM_MS}ms")
    }

    private fun isPendingInsetFreeform(packageName: String?): Boolean {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val until = mPendingInsetFreeformPkgs[pkg] ?: return false
        if (SystemClock.uptimeMillis() > until) {
            mPendingInsetFreeformPkgs.remove(pkg)
            return false
        }
        return true
    }

    private fun clearPendingInsetFreeform(packageName: String?) {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (mPendingInsetFreeformPkgs.remove(pkg) != null) {
            log(TAG, "pending inset freeform cleared: pkg=$pkg")
        }
    }

    /**
     * OneUI often ignores launch FREEFORM on virtual displays (and launcher icon starts never
     * see our ActivityOptions). Force task windowing mode + inset bounds after the task lands
     * on the VD — same end state as the user's "move to phone then back" workaround.
     *
     * Never touch tasks while OneUI split stages are active. Normal freeform (user-resized /
     * intentionally maximized mid-session) is left alone once pending inset is cleared.
     * Launch / stack-close mark the package pending so restored maximized bounds are demoted.
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

        val taskInfo = findRootTaskInfoOnDisplay(taskId, mDisplayId) ?: return
        val pkg = taskInfo.topActivity?.packageName
        if (pkg.isNullOrBlank()) return
        if (pkg == mHomePackage || pkg == mLauncherPackage) return
        if (IGNORE_RECENT_PACKAGE.contains(pkg)) return
        if (isBounceExcludedPackage(pkg)) return

        val pending = isPendingInsetFreeform(pkg)
        val demote = force || pending
        if (!demote && SystemClock.uptimeMillis() < mSuppressEnsureFreeformUntil) {
            log(TAG, "ensureFreeform skipped (suppress): task=$taskId [$reason]")
            return
        }

        // Phone/orphan idle StageCoordinator blocks freeform→split with "no display". Steal
        // onto VD first, then expand. Never force-wipe during demote — that deletes the VD
        // shells OneUI needs and leaves the app stuck fullscreen (no caption).
        relocateEmptyOrphanSplitShellsToVirtualDisplay("before-ensure:$reason")
        relocateEmptyPhoneSplitShellsToVirtualDisplay("before-ensure:$reason")
        if (hasIdleSplitShellOnVirtualDisplay()) {
            scheduleExpandSplitShellToFullDisplay("before-ensure:$reason")
        } else if (hasEmptySplitOrganizerShells()) {
            // Only remove stuck removal-requested shells on the VD (force=false).
            scheduleCleanupEmptySplitOrganizerTasks("before-ensure:$reason")
            cleanupEmptySplitOrganizerTasks("before-ensure:$reason", force = false)
        }

        markVirtualDisplayOwnership(taskId, pkg)
        val mode = getWindowingMode(taskInfo)
        // Leave split / multi-window alone.
        if (mode != null &&
            mode != WINDOWING_MODE_FULLSCREEN &&
            mode != WINDOWING_MODE_UNDEFINED &&
            mode != WINDOWING_MODE_FREEFORM
        ) {
            return
        }

        var needMode = mode == null || mode == WINDOWING_MODE_FULLSCREEN || mode == WINDOWING_MODE_UNDEFINED
        var needBounds = false
        val currentBounds = getTaskBoundsSafe(taskId, taskInfo)
        // OneUI sometimes applies inset freeform bounds while still reporting fullscreen —
        // still need a real FREEFORM mode for caption chrome / split entry.
        val insetButNotFreeform =
            needMode &&
                currentBounds != null &&
                !currentBounds.isEmpty &&
                !isNearlyFullscreenBounds(currentBounds)
        if (mode == WINDOWING_MODE_FREEFORM) {
            // Caption minimize may leave isMinimized / visible=false; restore path confirms
            // stable on-screen bounds so live caption/border drag is not snapped.
            if (restoreHiddenFreeformIfNeeded(taskId, reason, userRequested = false)) {
                return
            }
            // Mid-session user maximize: leave alone once pending inset expired.
            if (!demote) return
            val boundsUnknown = currentBounds == null || currentBounds.isEmpty
            needBounds = boundsUnknown || isNearlyFullscreenBounds(currentBounds)
            // Re-assert FREEFORM even when already freeform — refreshes OneUI caption chrome
            // after a maximized/fullscreen restore.
            if (needBounds) needMode = true
            if (!needBounds) {
                clearPendingInsetFreeform(pkg)
                return
            }
        }

        if (!needMode && !needBounds) return

        val now = SystemClock.uptimeMillis()
        val last = mLastFreeformEnsureAt[taskId] ?: 0L
        if (now - last < 180L) return
        mLastFreeformEnsureAt[taskId] = now

        if (needMode) {
            if (!setTaskWindowingModeSafe(taskId, WINDOWING_MODE_FREEFORM)) {
                log(TAG, "ensureFreeform[$reason]: setTaskWindowingMode failed task=$taskId pkg=$pkg mode=${mode ?: "?"}")
                // Still push inset bounds — OneUI may accept bounds before mode sticks.
                needBounds = true
            } else {
                needBounds = true
            }
        }
        if (insetButNotFreeform) needBounds = true

        var resized = if (needBounds) resizeTaskToFreeformBounds(taskId) else false
        var afterInfo = findRootTaskInfoOnDisplay(taskId, mDisplayId)
        var afterMode = afterInfo?.let { getWindowingMode(it) }
        var afterBounds = getTaskBoundsSafe(taskId, afterInfo)
        var afterStillMax =
            afterBounds != null && !afterBounds.isEmpty && isNearlyFullscreenBounds(afterBounds)

        // OneUI on VD often no-ops setTaskWindowingMode for maximized/fullscreen tasks.
        // Re-delivering with launch FREEFORM options (same as `am start --windowingMode 5`) works.
        if (demote && (afterMode != WINDOWING_MODE_FREEFORM || afterStillMax)) {
            val nowR = SystemClock.uptimeMillis()
            val lastR = mLastFreeformRelaunchAt[taskId] ?: 0L
            val canRelaunch = nowR - lastR >= 900L
            val relaunched = if (canRelaunch) {
                mLastFreeformRelaunchAt[taskId] = nowR
                relaunchTaskAsFreeform(taskId, pkg, reason)
            } else {
                false
            }
            if (relaunched) {
                resized = resizeTaskToFreeformBounds(taskId) || resized
            }
            afterInfo = findRootTaskInfoOnDisplay(taskId, mDisplayId)
            afterMode = afterInfo?.let { getWindowingMode(it) }
            afterBounds = getTaskBoundsSafe(taskId, afterInfo)
            afterStillMax =
                afterBounds != null && !afterBounds.isEmpty && isNearlyFullscreenBounds(afterBounds)
            // Relaunch API often returns ok while mode stays fullscreen (inset bounds only).
            // Always mode-toggle when FREEFORM still did not stick.
            if (afterMode != WINDOWING_MODE_FREEFORM && canRelaunch) {
                setTaskWindowingModeSafe(taskId, WINDOWING_MODE_FULLSCREEN)
                setTaskWindowingModeSafe(taskId, WINDOWING_MODE_FREEFORM)
                resized = resizeTaskToFreeformBounds(taskId) || resized
                log(TAG, "ensureFreeform[$reason]: mode-toggle fallback task=$taskId pkg=$pkg")
                afterInfo = findRootTaskInfoOnDisplay(taskId, mDisplayId)
                afterMode = afterInfo?.let { getWindowingMode(it) }
                afterBounds = getTaskBoundsSafe(taskId, afterInfo)
                afterStillMax =
                    afterBounds != null && !afterBounds.isEmpty && isNearlyFullscreenBounds(afterBounds)
            }
        }

        if (afterMode == WINDOWING_MODE_FREEFORM && !afterStillMax) {
            clearPendingInsetFreeform(pkg)
            log(
                TAG,
                "ensureFreeform[$reason]: task=$taskId pkg=$pkg mode=${mode ?: "?"} -> FREEFORM inset bounds=$resized"
            )
        } else if (afterMode == WINDOWING_MODE_FREEFORM) {
            // Freeform restored (often last non-fullscreen bounds); caption is back — good enough.
            clearPendingInsetFreeform(pkg)
            if (demote) resizeTaskToFreeformBounds(taskId)
            log(
                TAG,
                "ensureFreeform[$reason]: task=$taskId pkg=$pkg mode=${mode ?: "?"} -> FREEFORM (nearFull=$afterStillMax) bounds=$resized"
            )
        } else {
            log(
                TAG,
                "ensureFreeform[$reason]: still not freeform task=$taskId pkg=$pkg mode=${mode ?: "?"} after=${afterMode ?: "?"} bounds=$resized nearFull=$afterStillMax"
            )
        }
    }

    /**
     * OneUI ignores [setTaskWindowingMode] for many VD fullscreen tasks. Re-start from recents
     * (or re-deliver the activity) with FREEFORM launch options — verified on device via
     * `am start --windowingMode 5 --display <vd>`.
     *
     * @param launchBounds when non-null, use these instead of the centered inset (minimize
     *   restore must keep the pre-minimize frame so caption move is not snapped to center).
     */
    private fun relaunchTaskAsFreeform(
        taskId: Int,
        packageName: String,
        reason: String,
        launchBounds: Rect? = null
    ): Boolean {
        val bounds = launchBounds?.takeUnless { it.isEmpty } ?: buildFreeformInsetBounds()
        val options = try {
            ActivityOptions.makeBasic().apply {
                launchDisplayId = mDisplayId
                try {
                    invokeMethod("setCallerDisplayId", args(mDisplayId), argTypes(Integer.TYPE))
                } catch (_: Throwable) {
                }
                try {
                    invokeMethod(
                        "setLaunchWindowingMode",
                        args(WINDOWING_MODE_FREEFORM),
                        argTypes(Integer.TYPE)
                    )
                } catch (e: Throwable) {
                    log(TAG, "relaunchAsFreeform setLaunchWindowingMode failed:", e)
                    return false
                }
                if (bounds != null) {
                    try {
                        this.launchBounds = bounds
                    } catch (_: Throwable) {
                    }
                }
            }.toBundle()
        } catch (e: Throwable) {
            log(TAG, "relaunchAsFreeform options failed:", e)
            return false
        }

        val atm = Instances.iActivityTaskManager as Any
        // Preferred: startActivityFromRecents(taskId, options)
        try {
            atm.invokeMethod(
                "startActivityFromRecents",
                args(taskId, options),
                argTypes(Integer.TYPE, Bundle::class.java)
            )
            log(TAG, "relaunchAsFreeform[$reason]: startActivityFromRecents task=$taskId pkg=$packageName")
            return true
        } catch (_: Throwable) {
        }
        try {
            atm.invokeMethod(
                "startActivityFromRecents",
                args(taskId, options),
                argTypes(Integer.TYPE, android.os.Bundle::class.java)
            )
            log(TAG, "relaunchAsFreeform[$reason]: startActivityFromRecents(Bundle) task=$taskId")
            return true
        } catch (_: Throwable) {
        }

        // Fallback: re-deliver component start with FREEFORM options (am start equivalent).
        val componentName = resolveLaunchComponent(packageName) ?: return false
        return try {
            context.invokeMethod(
                "startActivityAsUser",
                args(
                    Intent().apply {
                        component = componentName
                        `package` = componentName.packageName
                        action = Intent.ACTION_VIEW
                        putExtra("displayId", mDisplayId)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    options,
                    UserHandle::class.java.newInstance(args(0), argTypes(Integer.TYPE))
                ),
                argTypes(Intent::class.java, Bundle::class.java, UserHandle::class.java)
            )
            log(TAG, "relaunchAsFreeform[$reason]: startActivityAsUser pkg=$packageName")
            true
        } catch (e: Throwable) {
            log(TAG, "relaunchAsFreeform[$reason] failed task=$taskId pkg=$packageName", e)
            false
        }
    }

    private fun isNearlyFullscreenBounds(bounds: Rect?): Boolean {
        if (bounds == null || bounds.isEmpty) return false
        if (mDisplayId == Display.INVALID_DISPLAY) return false
        return try {
            val display = Instances.displayManager.getDisplay(mDisplayId) ?: return false
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            val displayArea = metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
            if (displayArea <= 0L) return false
            val taskArea = bounds.width().toLong() * bounds.height().toLong()
            taskArea.toDouble() / displayArea.toDouble() >= FREEFORM_MAX_FILL_RATIO
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * OneUI freeform caption swipe-down sets Task.isMinimized (visible=false, STOPPED) while
     * keeping freeform mode + last bounds. RootTaskInfo / RunningTaskInfo may expose the flag.
     */
    private fun isTaskMinimized(taskInfo: Any?): Boolean {
        if (taskInfo == null) return false
        val readers = listOf(
            { taskInfo.getObjectAs("isMinimized", Boolean::class.javaPrimitiveType) as? Boolean },
            { taskInfo.getObjectAs("mIsMinimized", Boolean::class.javaPrimitiveType) as? Boolean },
            { taskInfo.getObjectAs("isMinimized", Boolean::class.java) as? Boolean },
            {
                try {
                    taskInfo.invokeMethod("isMinimized", args(), argTypes()) as? Boolean
                } catch (_: Throwable) {
                    null
                }
            },
        )
        for (read in readers) {
            try {
                val v = read()
                if (v != null) return v
            } catch (_: Throwable) {
            }
        }
        return false
    }

    /** Freeform not drawn (minimized/stashed) — used only on explicit stack tap. */
    private fun isFreeformNotVisible(taskInfo: Any?): Boolean {
        if (taskInfo == null) return false
        return try {
            if (getWindowingMode(taskInfo) != WINDOWING_MODE_FREEFORM) return false
            val visible = taskInfo.getObjectAs("visible", Boolean::class.javaPrimitiveType) as? Boolean
                ?: taskInfo.getObjectAs("visible", Boolean::class.java) as? Boolean
            val visibleRequested =
                taskInfo.getObjectAs("visibleRequested", Boolean::class.javaPrimitiveType) as? Boolean
                    ?: taskInfo.getObjectAs("visibleRequested", Boolean::class.java) as? Boolean
            visible == false || visibleRequested == false
        } catch (_: Throwable) {
            false
        }
    }

    /** True when less than [FREEFORM_MIN_VISIBLE_RATIO] of the freeform rect intersects the VD. */
    private fun isMostlyOffDisplay(bounds: Rect): Boolean {
        if (bounds.isEmpty || mDisplayId == Display.INVALID_DISPLAY) return false
        return try {
            val display = Instances.displayManager.getDisplay(mDisplayId) ?: return false
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return false
            val displayRect = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
            val visible = Rect()
            if (!visible.setIntersect(bounds, displayRect)) return true
            val taskArea = bounds.width().toLong() * bounds.height().toLong()
            if (taskArea <= 0L) return true
            val visibleArea = visible.width().toLong() * visible.height().toLong()
            visibleArea.toDouble() / taskArea.toDouble() < FREEFORM_MIN_VISIBLE_RATIO
        } catch (_: Throwable) {
            false
        }
    }

    private fun restoreHiddenFreeformTasksOnVirtualDisplay(reason: String) {
        if (!isOneUiSplitEnabled() || mIsDestroying || mDisplayId == Display.INVALID_DISPLAY) return
        if (isDisplayInSplitStages()) return
        try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId).forEach { info ->
                restoreHiddenFreeformIfNeeded(info.taskId, reason, userRequested = false)
            }
        } catch (e: Throwable) {
            log(TAG, "restoreHiddenFreeformTasks[$reason] failed:", e)
        }
    }

    /**
     * Undo OneUI freeform caption minimize / (on stack tap) drag-off-screen on the AA VD.
     * Verified: `am start --windowingMode 5` unminimizes; `am task resize` restores off-screen.
     *
     * Auto path: true caption minimize only — on-screen bounds + (isMinimized or notVisible),
     * confirmed stable so live caption/border *move* (bounds changing, OneUI may flicker
     * minimize/visible) is not treated as hide. Relaunch keeps existing bounds (do not snap
     * to inset — that made move look like minimize).
     *
     * @param userRequested stack tap — also restores mostly-off / not-visible freeform when
     *   the `isMinimized` field is missing from RootTaskInfo.
     */
    private fun restoreHiddenFreeformIfNeeded(
        taskId: Int,
        reason: String,
        userRequested: Boolean = false
    ): Boolean {
        if (!isOneUiSplitEnabled() || mIsDestroying || mDisplayId == Display.INVALID_DISPLAY) return false
        if (isDisplayInSplitStages()) return false
        val taskInfo = findRootTaskInfoOnDisplay(taskId, mDisplayId) ?: return false
        val mode = getWindowingMode(taskInfo)
        if (mode != WINDOWING_MODE_FREEFORM) return false
        val pkg = taskInfo.topActivity?.packageName
        if (pkg.isNullOrBlank()) return false
        if (pkg == mHomePackage || pkg == mLauncherPackage) return false
        if (IGNORE_RECENT_PACKAGE.contains(pkg) || isBounceExcludedPackage(pkg)) return false

        val minimized = isTaskMinimized(taskInfo)
        val bounds = getTaskBoundsSafe(taskId, taskInfo)
        noteFreeformBoundsObservation(taskId, bounds)
        val off = bounds != null && !bounds.isEmpty && isMostlyOffDisplay(bounds)
        val notVisible = isFreeformNotVisible(taskInfo)
        val shouldRestore = if (userRequested) {
            minimized || off || notVisible
        } else {
            // True swipe-down minimize keeps bounds on-screen. Mostly-off is caption move/stash.
            // RootTaskInfo often lacks isMinimized — notVisible + on-screen is the proxy, but only
            // after bounds stay put (live drag flickers visible while moving).
            // Diagonal/edge resize on the tiny AA VD is often mis-classified by OneUI as minimize;
            // after a real w/h change, confirm much sooner so shrink does not stick minimized.
            val confirmMs = if (wasRecentlyResized(taskId)) {
                FREEFORM_RESIZE_MINIMIZE_CONFIRM_MS
            } else {
                FREEFORM_MINIMIZE_CONFIRM_MS
            }
            isConfirmedCaptionMinimize(taskId, minimized, notVisible, off, bounds, confirmMs)
        }
        if (!shouldRestore) return false

        log(
            TAG,
            "restoreHiddenFreeform[$reason]: task=$taskId pkg=$pkg minimized=$minimized offDisplay=$off notVisible=$notVisible userRequested=$userRequested recentResize=${wasRecentlyResized(taskId)} bounds=$bounds"
        )
        markVirtualDisplayOwnership(taskId, pkg)
        mMinimizeConfirmAt.remove(taskId)

        val clamped = bounds?.let { clampBoundsToDisplay(it) }
        val keepBounds = clamped != null && !clamped.isEmpty &&
            !(clamped.let { isMostlyOffDisplay(it) })
        var ok = false
        if (minimized || notVisible || userRequested) {
            // moveTaskToFront alone leaves isMinimized=true; relaunch with FREEFORM unstashes.
            // Pass current on-screen bounds so we do not snap back to centered inset.
            ok = relaunchTaskAsFreeform(
                taskId,
                pkg,
                "restore-$reason",
                launchBounds = if (keepBounds) clamped else null
            )
        }
        // Snap to inset only when bounds are gone / mostly-off (stack tap or broken minimize).
        if (!keepBounds && (minimized || userRequested || notVisible)) {
            ok = resizeTaskToFreeformBounds(taskId) || ok
        } else if (keepBounds && clamped != null && bounds != null && clamped != bounds) {
            // OneUI may leave freeform taller/wider than the VD after corner resize; clamp in place.
            ok = resizeTaskToBounds(taskId, clamped) || ok
        }
        if (!ok && (minimized || userRequested || notVisible)) {
            ok = relaunchTaskAsFreeform(
                taskId,
                pkg,
                "restore-$reason-fallback",
                launchBounds = if (keepBounds) clamped else null
            )
            if (!keepBounds) resizeTaskToFreeformBounds(taskId)
            else if (clamped != null) resizeTaskToBounds(taskId, clamped)
        }
        try {
            Instances.activityManager.moveTaskToFront(taskId, 0)
        } catch (_: Throwable) {
        }
        setFocusedTaskSafe(taskId)
        return ok
    }

    private fun noteFreeformBoundsObservation(taskId: Int, bounds: Rect?) {
        if (bounds == null || bounds.isEmpty) return
        val w = bounds.width()
        val h = bounds.height()
        val prev = mLastFreeformSize[taskId]
        if (prev != null && (prev.first != w || prev.second != h)) {
            mLastResizeAt[taskId] = SystemClock.uptimeMillis()
        }
        mLastFreeformSize[taskId] = w to h
    }

    private fun wasRecentlyResized(taskId: Int): Boolean {
        val at = mLastResizeAt[taskId] ?: return false
        return SystemClock.uptimeMillis() - at < FREEFORM_RECENT_RESIZE_MS
    }

    /**
     * Auto-unminimize gate: require on-screen freeform that looks minimized, with bounds
     * unchanged for [confirmMs]. Live caption/border drag changes bounds (and may flicker
     * isMinimized/visible) — resetting the timer avoids snap/relaunch.
     */
    private fun isConfirmedCaptionMinimize(
        taskId: Int,
        minimized: Boolean,
        notVisible: Boolean,
        off: Boolean,
        bounds: Rect?,
        confirmMs: Long = FREEFORM_MINIMIZE_CONFIRM_MS
    ): Boolean {
        if (off) {
            mMinimizeConfirmAt.remove(taskId)
            return false
        }
        if (!minimized && !notVisible) {
            mMinimizeConfirmAt.remove(taskId)
            return false
        }
        if (bounds == null || bounds.isEmpty) {
            // No bounds to track — only trust an explicit minimize flag.
            return minimized
        }
        val fp = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
        val now = SystemClock.uptimeMillis()
        val prev = mMinimizeConfirmAt[taskId]
        if (prev == null || prev.second != fp) {
            mMinimizeConfirmAt[taskId] = now to fp
            // Stack may go quiet after minimize — re-check once bounds have had time to stay put.
            mHandler.postDelayed({
                if (mIsDestroying || mDisplayId == Display.INVALID_DISPLAY) return@postDelayed
                restoreHiddenFreeformIfNeeded(taskId, "minimize-confirm", userRequested = false)
            }, confirmMs + 80L)
            return false
        }
        return now - prev.first >= confirmMs
    }

    /** Keep freeform launch/restore frames inside the AA VD (OneUI corner-resize can overrun). */
    private fun clampBoundsToDisplay(bounds: Rect): Rect? {
        if (bounds.isEmpty || mDisplayId == Display.INVALID_DISPLAY) return null
        return try {
            val display = Instances.displayManager.getDisplay(mDisplayId) ?: return null
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            val dw = metrics.widthPixels
            val dh = metrics.heightPixels
            if (dw <= 0 || dh <= 0) return null
            val out = Rect(bounds)
            if (out.width() > dw) {
                out.left = 0
                out.right = dw
            }
            if (out.height() > dh) {
                out.top = 0
                out.bottom = dh
            }
            if (out.left < 0) out.offset(-out.left, 0)
            if (out.top < 0) out.offset(0, -out.top)
            if (out.right > dw) out.offset(dw - out.right, 0)
            if (out.bottom > dh) out.offset(0, dh - out.bottom)
            if (out.isEmpty) null else out
        } catch (_: Throwable) {
            null
        }
    }

    private fun getTaskBoundsSafe(taskId: Int, taskInfo: Any?): Rect? {
        try {
            val atm = Instances.iActivityTaskManager as Any
            val fromAtm = atm.invokeMethod(
                "getTaskBounds",
                args(taskId),
                argTypes(Integer.TYPE)
            ) as? Rect
            if (fromAtm != null && !fromAtm.isEmpty) return Rect(fromAtm)
        } catch (_: Throwable) {
        }
        if (taskInfo != null) {
            try {
                val bounds = taskInfo.getObjectAs("bounds", Rect::class.java) as? Rect
                if (bounds != null && !bounds.isEmpty) return Rect(bounds)
            } catch (_: Throwable) {
            }
            try {
                val conf = taskInfo.getObject("configuration") ?: return null
                val winConf = conf.invokeMethod("getWindowConfiguration", args(), argTypes()) ?: return null
                val bounds = winConf.invokeMethod("getBounds", args(), argTypes()) as? Rect
                if (bounds != null && !bounds.isEmpty) return Rect(bounds)
            } catch (_: Throwable) {
            }
        }
        return null
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
        return resizeTaskToBounds(taskId, bounds)
    }

    private fun resizeTaskToBounds(
        taskId: Int,
        bounds: Rect,
        resizeMode: Int = RESIZE_MODE_SYSTEM
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
            log(TAG, "resizeTaskToBounds($taskId) failed:", e)
            false
        }
    }

    /**
     * StageCoordinator / stage shells (`#3→#4/#5`). RootTaskInfo often omits
     * `createdByOrganizer`; resolve the live Task so reclaim does not treat them as owned apps.
     */
    private fun isSplitOrganizerOrStageTask(taskId: Int): Boolean {
        if (taskId <= 0) return false
        val node = resolveTaskObject(taskId)
        val info = findRootTaskInfoOnDisplay(taskId, mDisplayId)
            ?: findRootTaskInfoOnDisplay(taskId, Display.DEFAULT_DISPLAY)
        val target = node ?: info ?: return false
        if (isCreatedByOrganizer(target) || (info != null && isCreatedByOrganizer(info))) return true
        if (isSplitStageMode(getWindowingMode(target)) || hasSplitStageConfig(target)) return true
        if (info != null &&
            (isSplitStageMode(getWindowingMode(info)) || hasSplitStageConfig(info))
        ) {
            return true
        }
        val kids = readChildTaskIds(target) ?: (info?.let { readChildTaskIds(it) }) ?: return false
        if (kids.size < 2) return false
        return kids.any { kid ->
            val c = resolveTaskObject(kid) ?: return@any false
            isSplitStageMode(getWindowingMode(c)) || hasSplitStageConfig(c) || isCreatedByOrganizer(c)
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
        // moveRootTaskToDisplay the whole split tree (aborts freeform→split on cold boot).
        if (isSplitOrganizerOrStageTask(taskId) ||
            (info != null && isCreatedByOrganizer(info))
        ) {
            mVdTaskIds.remove(taskId)
            val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() }
                ?: info?.topActivity?.packageName?.trim()?.takeIf { it.isNotEmpty() }
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
        fun applied(): Boolean {
            val info = findRootTaskInfoOnDisplay(taskId, mDisplayId)
                ?: findRootTaskInfoOnDisplay(taskId, Display.DEFAULT_DISPLAY)
            return info != null && getWindowingMode(info) == mode
        }
        // Older AIDL: setTaskWindowingMode(taskId, mode, toTop)
        try {
            atm.invokeMethod(
                "setTaskWindowingMode",
                args(taskId, mode, true),
                argTypes(Integer.TYPE, Integer.TYPE, java.lang.Boolean.TYPE)
            )
            if (applied()) return true
        } catch (_: Throwable) {
        }
        try {
            atm.invokeMethod(
                "setTaskWindowingMode",
                args(taskId, mode),
                argTypes(Integer.TYPE, Integer.TYPE)
            )
            if (applied()) return true
        } catch (_: Throwable) {
        }
        // system_server local ATMS: Task.setWindowingMode — AIDL often no-ops on OneUI VD.
        return try {
            val task = resolveTaskObject(taskId) ?: return false
            try {
                task.invokeMethod(
                    "setWindowingMode",
                    args(mode, false),
                    argTypes(Integer.TYPE, java.lang.Boolean.TYPE)
                )
            } catch (_: Throwable) {
                task.invokeMethod("setWindowingMode", args(mode), argTypes(Integer.TYPE))
            }
            applied()
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
        // Never bounce StageCoordinator / stage shells. Cold-boot freeform→split briefly parks
        // `#3` on the phone; reclaim used to moveRootTaskToDisplay(#3) and abort caption-split.
        if (isSplitOrganizerOrStageTask(taskId)) {
            mVdTaskIds.remove(taskId)
            log(TAG, "bounce skipped (organizer/stage): task=$taskId pkg=${packageName.orEmpty()} [$reason]")
            return false
        }
        // Leaf tasks under an active StageCoordinator are not ATM "root" tasks — bouncing them
        // throws Unknown rootTaskId and can fight an in-progress split layout.
        if (!packageName.isNullOrBlank() &&
            getSplitAppTasksOnDisplay().any { it.second == packageName }
        ) {
            log(TAG, "bounce skipped (already in split): task=$taskId pkg=$packageName [$reason]")
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
            // Organizer / stage shells first — RootTaskInfo often lies about createdByOrganizer
            // (`#3` looks like a normal owned task and used to get bounced mid freeform→split).
            if (isCreatedByOrganizer(taskInfo) || isSplitOrganizerOrStageTask(taskId)) {
                mVdTaskIds.remove(taskId)
                bounced += reclaimOwnedLeavesUnderOrganizer(taskInfo, ownedPackages, splitActive, reason)
                continue
            }
            if (isBounceExcludedPackage(pkg)) continue

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

        // Owned packages missing from the VD entirely (nested under stages / new task ids).
        for (pkg in ownedPackages) {
            if (isBounceExcludedPackage(pkg)) continue
            if (hasPackageTaskOnDisplay(pkg, mDisplayId)) continue
            val phoneId = findPackageTaskIdOnDisplay(pkg, Display.DEFAULT_DISPLAY) ?: continue
            if (isPinnedTask(phoneId)) continue
            if (isSplitOrganizerOrStageTask(phoneId)) continue
            if (bounceTaskToVirtualDisplay(phoneId, pkg, "reclaim-missing:$reason")) {
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
            // After pulling a MW/split leaf back, OneUI often leaves it minimized/not-visible.
            mHandler.post {
                for (pkg in ownedPackages) {
                    if (isBounceExcludedPackage(pkg)) continue
                    val vdId = findPackageTaskIdOnDisplay(pkg, mDisplayId) ?: continue
                    restoreHiddenFreeformIfNeeded(vdId, "reclaim-visible:$reason", userRequested = true)
                    if (!isDisplayInSplitStages()) {
                        ensureTaskFreeformOnVirtualDisplay(vdId, "reclaim-visible:$reason", force = true)
                    }
                }
            }
        }
    }

    /**
     * Caption freeform→split often nests the VD app under phone StageCoordinator children
     * (`#3→#4/#5`). The organizer root must not be bounced, but owned leaves must return to VD.
     */
    private fun reclaimOwnedLeavesUnderOrganizer(
        organizerInfo: ActivityTaskManager.RootTaskInfo,
        ownedPackages: Set<String>,
        splitActiveOnVd: Boolean,
        reason: String
    ): Int {
        var bounced = 0
        val childIds = readChildTaskIds(organizerInfo)
            ?: resolveTaskObject(organizerInfo.taskId)?.let { readChildTaskIds(it) }
            ?: return 0
        fun visit(taskId: Int, depth: Int) {
            if (depth > 4) return
            val node = resolveTaskObject(taskId) ?: return
            if (isCreatedByOrganizer(node)) {
                readChildTaskIds(node)?.forEach { visit(it, depth + 1) }
                return
            }
            val pkg = try {
                (node.getObjectAs("topActivity", ComponentName::class.java) as? ComponentName)
                    ?.packageName
                    ?: (node.invokeMethod("getTopNonFinishingActivity", args(), argTypes()) as? ComponentName)
                        ?.packageName
                    ?: run {
                        // Fall back to ATM RootTaskInfo if this id is listed.
                        findRootTaskInfoOnDisplay(taskId, Display.DEFAULT_DISPLAY)
                            ?.topActivity?.packageName
                    }
            } catch (_: Throwable) {
                findRootTaskInfoOnDisplay(taskId, Display.DEFAULT_DISPLAY)?.topActivity?.packageName
            }
            if (pkg.isNullOrBlank() || !ownedPackages.contains(pkg)) {
                readChildTaskIds(node)?.forEach { visit(it, depth + 1) }
                return
            }
            if (isBounceExcludedPackage(pkg) || isPinnedTask(taskId)) return
            if (splitActiveOnVd && hasPackageTaskOnDisplay(pkg, mDisplayId)) return
            if (bounceTaskToVirtualDisplay(taskId, pkg, "reclaim-leaf:$reason")) {
                bounced++
            }
        }
        childIds.forEach { visit(it, 0) }
        return bounced
    }

    private fun applyVirtualDisplayPolicies(reason: String) {
        if (mDisplayId == Display.INVALID_DISPLAY) return
        publishVirtualDisplayIdForSystemUi(reason)
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
        return findPackageTaskIdOnDisplay(packageName, displayId) != null
    }

    private fun findPackageTaskIdOnDisplay(packageName: String, displayId: Int): Int? {
        if (displayId == Display.INVALID_DISPLAY) return null
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return null
        return try {
            val tasks = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
            fun matches(info: Any): Boolean {
                val top = try {
                    (info as? ActivityTaskManager.RootTaskInfo)?.topActivity?.packageName
                        ?: (info.getObjectAs("topActivity", ComponentName::class.java) as? ComponentName)
                            ?.packageName
                } catch (_: Throwable) {
                    null
                }
                if (top == pkg) return true
                val base = runCatching {
                    (info.getObjectAs("baseActivity", ComponentName::class.java) as? ComponentName)
                        ?.packageName
                }.getOrNull()
                return base == pkg
            }
            fun walk(info: Any, depth: Int): Int? {
                if (depth > 4) return null
                val taskId = try {
                    (info as? ActivityTaskManager.RootTaskInfo)?.taskId
                        ?: info.getObjectAs("taskId", Int::class.javaPrimitiveType) as? Int
                } catch (_: Throwable) {
                    null
                } ?: return null
                // Never treat StageCoordinator / stage shells as the app task — OneUI often
                // leaves a stale top/baseActivity on `#3`, and reclaim-missing then bounces the
                // whole split tree (aborts caption freeform→split).
                if (matches(info) &&
                    !isCreatedByOrganizer(info) &&
                    !isSplitOrganizerOrStageTask(taskId)
                ) {
                    return taskId
                }
                val childIds = readChildTaskIds(info)
                    ?: resolveTaskObject(taskId)?.let { readChildTaskIds(it) }
                childIds?.forEach { childId ->
                    val child = tasks.firstOrNull { it.taskId == childId }
                        ?: resolveTaskObject(childId)
                        ?: return@forEach
                    walk(child, depth + 1)?.let { return it }
                }
                // Organizer whose topActivity is the app (OneUI sometimes reports that way).
                if (matches(info)) return taskId
                return null
            }
            for (info in tasks) {
                walk(info, 0)?.let { return it }
            }
            null
        } catch (_: Throwable) {
            null
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
     * Video apps (e.g. 央视影音) entering PiP set WINDOWING_MODE_PINNED. OneUI often leaves the
     * pinned task on the AA VD; that poisons StageCoordinator so the next freeform→split keeps
     * the inset freeform shell (`[43,29][676,451]`). Move PiP off the VD, clean empty shells,
     * and re-assert freeform policies for remaining apps.
     */
    private fun onVirtualDisplayActivityPinned(packageName: String?, taskId: Int) {
        if (mIsDestroying || mDisplayId == Display.INVALID_DISPLAY) return
        log(TAG, "onActivityPinned handled: pkg=${packageName.orEmpty()} task=$taskId")
        mVdTaskIds.remove(taskId)
        packageName?.let { mVdPackages.remove(it) }
        val now = SystemClock.uptimeMillis()
        mSuppressDisplayBounceUntil = now + SUPPRESS_RECLAIM_AFTER_PIP_MS
        // Allow remaining VD apps to regain FREEFORM caption (do not keep post-bounce suppress).
        mSuppressEnsureFreeformUntil = 0L
        relocatePinnedTasksOffVirtualDisplay("activity-pinned:${packageName.orEmpty()}", taskId)
        scheduleCleanupEmptySplitOrganizerTasks("activity-pinned:${packageName.orEmpty()}")
        cleanupEmptySplitOrganizerTasks("activity-pinned:${packageName.orEmpty()}")
        if (isOneUiSplitEnabled()) {
            applyVirtualDisplayPolicies("activity-pinned")
            ensureFreeformForVirtualDisplayApps("activity-pinned")
            // Next split entry often reuses inset bounds left by ensureFreeform — expand early/late.
            scheduleExpandSplitShellToFullDisplay("activity-pinned")
        }
    }

    /**
     * After PiP exits, restore freeform on the AA VD so the OneUI caption/split handle returns.
     */
    private fun onVirtualDisplayActivityUnpinned() {
        if (mIsDestroying || mDisplayId == Display.INVALID_DISPLAY) return
        log(TAG, "onActivityUnpinned handled")
        mSuppressEnsureFreeformUntil = 0L
        relocatePinnedTasksOffVirtualDisplay("activity-unpinned", pinnedTaskId = null)
        scheduleCleanupEmptySplitOrganizerTasks("activity-unpinned")
        cleanupEmptySplitOrganizerTasks("activity-unpinned")
        if (isOneUiSplitEnabled()) {
            applyVirtualDisplayPolicies("activity-unpinned")
            ensureFreeformForVirtualDisplayApps("activity-unpinned")
            scheduleExpandSplitShellToFullDisplay("activity-unpinned")
        }
    }

    /**
     * Keep WINDOWING_MODE_PINNED off the AA virtual display. PiP on the VD makes OneUI treat the
     * freeform inset as the StageCoordinator root size for subsequent split.
     */
    private fun relocatePinnedTasksOffVirtualDisplay(reason: String, pinnedTaskId: Int?) {
        if (mDisplayId == Display.INVALID_DISPLAY) return
        val ids = linkedSetOf<Int>()
        if (pinnedTaskId != null && pinnedTaskId > 0) ids += pinnedTaskId
        try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId).forEach { info ->
                if (isPinnedWindowMode(info)) ids += info.taskId
            }
        } catch (e: Throwable) {
            log(TAG, "relocatePinned[$reason] list failed:", e)
        }
        if (ids.isEmpty()) return
        mSuppressDisplayBounceUntil =
            SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_AFTER_PIP_MS
        for (taskId in ids) {
            mVdTaskIds.remove(taskId)
            try {
                Instances.iActivityTaskManager.moveRootTaskToDisplay(
                    taskId,
                    Display.DEFAULT_DISPLAY
                )
                log(TAG, "relocatePinned[$reason]: moved task=$taskId -> DEFAULT_DISPLAY")
            } catch (e: Throwable) {
                log(TAG, "relocatePinned[$reason]: move task=$taskId failed:", e)
            }
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
            // Always probe expand: split-app detection can miss nested #4/#5 when
            // RootTaskInfo.childTaskIds is empty, leaving the shell stuck at freeform inset.
            scheduleExpandSplitShellToFullDisplay("stack-changed")
            // Divider drag fires this continuously. Reclaiming while split stages are up
            // fights the layout and can reset the ratio — but Home stealing focus still
            // needs a light guard so the Shell divider stays live.
            if (isDisplayInSplitStages()) {
                scheduleCollapseAsymmetricSplit("stack-changed")
                scheduleSplitFocusGuard("stack-changed")
                return
            }
            mHandler.removeCallbacks(mDebouncedStackReclaim)
            mHandler.postDelayed(mDebouncedStackReclaim, 120L)
            // Caption swipe-down minimize only (debounce past live caption/border drag).
            mHandler.removeCallbacks(mDebouncedRestoreHiddenFreeform)
            mHandler.postDelayed(mDebouncedRestoreHiddenFreeform, FREEFORM_RESTORE_DEBOUNCE_MS)
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
            // Launcher icon starts never pass through launchActivityAsUser FREEFORM options.
            mHandler.post {
                if (isTaskOnVirtualDisplay(taskId)) {
                    // Only bind Home/default-launch task ids to VD instances — a phone-side
                    // open of the same package must not steal startHomeLauncher onto DEFAULT_DISPLAY.
                    if (packageName == mHomePackage) {
                        mHomeTaskId = taskId
                    } else if (packageName == mLauncherPackage) {
                        mLauncherPackageTaskId = taskId
                    }
                    markVirtualDisplayOwnership(taskId, packageName)
                    markPendingInsetFreeform(packageName)
                    scheduleEnsureFreeform(taskId, "onTaskCreated", force = true)
                } else if (mVdPackages.contains(packageName) || isPendingInsetFreeform(packageName)) {
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
            mLastFreeformRelaunchAt.remove(taskId)
            mLastDisplayBounceAt.remove(taskId)
            mMinimizeConfirmAt.remove(taskId)
            mLastFreeformSize.remove(taskId)
            mLastResizeAt.remove(taskId)
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
            scheduleCollapseAsymmetricSplit("onTaskRemoved")
        }
        override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
            val taskId = taskInfo.taskId
            val pkg = taskInfo.topActivity?.packageName
            mHandler.post {
                if (isTaskOnVirtualDisplay(taskId)) {
                    markVirtualDisplayOwnership(taskId, pkg)
                    if (isDisplayInSplitStages()) {
                        scheduleCollapseAsymmetricSplit("onTaskMovedToFront")
                        // Home/fullscreen often jumps in front of stages and kills the divider.
                        if (pkg == mHomePackage || pkg == mLauncherPackage) {
                            maintainSplitForegroundFocus("onTaskMovedToFront-home")
                        } else {
                            scheduleSplitFocusGuard("onTaskMovedToFront")
                        }
                        return@post
                    }
                    val demote = isPendingInsetFreeform(pkg)
                    scheduleEnsureFreeform(taskId, "onTaskMovedToFront", force = demote)
                } else if (!pkg.isNullOrBlank() &&
                    (mVdPackages.contains(pkg) || isPendingInsetFreeform(pkg))
                ) {
                    if (getSplitAppTasksOnDisplay().any { it.second == pkg }) {
                        // Leaf under split often reports as not "on VD" to root-task probes.
                        return@post
                    }
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
                // Steal reusable `#3→#4/#5` onto the live VD before/while orphan wipe runs.
                schedulePhoneEmptySplitSteal("orphan-display=$newDisplayId")
                scheduleCleanupEmptySplitOrganizerTasks("orphan-display=$newDisplayId")
            }
            if (mDisplayId != Display.INVALID_DISPLAY && newDisplayId == mDisplayId) {
                val pkg = findPackageForTask(taskId)
                if (pkg == mHomePackage) {
                    mHomeTaskId = taskId
                } else if (pkg == mLauncherPackage) {
                    mLauncherPackageTaskId = taskId
                }
                markVirtualDisplayOwnership(taskId, pkg)
                val demote = isPendingInsetFreeform(pkg)
                scheduleEnsureFreeform(taskId, "onTaskDisplayChanged", force = demote)
            } else if (mDisplayId != Display.INVALID_DISPLAY && newDisplayId != mDisplayId) {
                // Home left the VD (often opened/recreated on the phone): drop the cached id so
                // the next Home press resolves from live stacks instead of moveTaskToFront(phone).
                if (mHomeTaskId == taskId) mHomeTaskId = null
                if (mLauncherPackageTaskId == taskId) mLauncherPackageTaskId = null
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
            scheduleCollapseAsymmetricSplit("dismissing-split:$str")
        }
        override fun onOccludeChangeNotice(componentName: ComponentName?, z: Boolean) {}
        override fun onTaskbarIconVisibleChangeRequest(componentName: ComponentName?, z: Boolean) {}
        //Samsung OneUi 7
        override fun onTaskWindowingModeChanged(i: Int) {
            log(TAG, "onTaskWindowingModeChanged: mode=$i, displayInMw=${isDisplayInMultiWindow()} split=${isDisplayInSplitStages()}")
            if (!isOneUiSplitEnabled() || mDisplayId == Display.INVALID_DISPLAY) return
            // Guard only when split/chooser is actually active — NOT on every freeform ensure
            // (that would keep mSuppressPhoneSplitStealUntil forever and block phone-steal).
            if (isDisplayInSplitStages() || isSplitChooserActiveOnPhone()) {
                noteSplitEntryShellGuard("windowing-mode=$i")
            }
            // Split entry dumps VD apps onto the phone chooser — reclaim packages missing from VD.
            // Do NOT broadly ensureFreeform here: forcing FREEFORM collapses OneUI split/MW.
            scheduleReclaimVirtualDisplayTasks("windowing-mode=$i")
            scheduleCleanupEmptySplitOrganizerTasks("windowing-mode=$i")
            // Expand even when split-app walk is still empty (AppsEdge / nested stages).
            scheduleExpandSplitShellToFullDisplay("windowing-mode=$i")
            if (isDisplayInSplitStages()) {
                scheduleCollapseAsymmetricSplit("windowing-mode=$i")
                scheduleSplitFocusGuard("windowing-mode=$i")
                return
            }
            // Close→reopen / launch pending: OneUI may restore maximized after our first ensure.
            if (mPendingInsetFreeformPkgs.isNotEmpty()) {
                val pendingPkgs = mPendingInsetFreeformPkgs.keys.toList().filter { isPendingInsetFreeform(it) }
                if (pendingPkgs.isNotEmpty()) {
                    mHandler.post {
                        try {
                            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
                                .forEach { info ->
                                    val pkg = info.topActivity?.packageName ?: return@forEach
                                    if (!pendingPkgs.contains(pkg)) return@forEach
                                    ensureTaskFreeformOnVirtualDisplay(
                                        info.taskId,
                                        "windowing-mode=$i",
                                        force = true
                                    )
                                }
                        } catch (e: Throwable) {
                            log(TAG, "windowing-mode pending ensure failed:", e)
                        }
                    }
                }
            }
        }
    }
}
