package io.github.nitsuya.aa.display.ui.aa.split

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.hook.VdDensityPin
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
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
        /** Debounce peel-inject recovery broadcasts / latch clears. */
        private const val AA_UI_DISPLAY_ID_RECOVERY_MS = 3000L
        internal const val MAX_RESTORE_VERIFY_ATTEMPTS = 3
        /** Token for post-fullscreen focus restore kicks (cancel on destroy / re-enter). */
        internal val FULLSCREEN_FOCUS_TOKEN = Any()
    }

    internal val vd = SplitVdLifecycle(this)
    internal val launch = SplitLaunchRestore(this)
    internal val ownership = SplitOwnership(this)
    internal val input = SplitInputRecents(this)
    internal val stacks = PaneAppStack(this)
    internal val lockedPeel = SplitLockedPeelController(this)

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
    /**
     * [SplitPane.FULLSCREEN_NONE] or PRIMARY/SECONDARY. When set, both VDs are full
     * size; AA UI shows one pane and stacks the other underneath.
     */
    var mFullscreenPane: Int = SplitPane.FULLSCREEN_NONE
        internal set
    /** Split ratio cached when entering fullscreen; restored on exit / persist. */
    var mRatioBeforeFullscreen: Float = SplitPane.DEFAULT_RATIO
        internal set
    /** Landscape → side-by-side; portrait → stacked. */
    val isSideBySide: Boolean
        get() = mWidth >= mHeight

    /**
     * Cached so [isAaVirtualDisplay] / DPI pin can early-out without
     * `VirtualDisplay.getDisplay()` on every WM configuration pass.
     */
    @Volatile
    private var mCachedPrimaryDisplayId: Int = Display.INVALID_DISPLAY
    @Volatile
    private var mCachedSecondaryDisplayId: Int = Display.INVALID_DISPLAY

    val primaryDisplayId: Int
        get() = mCachedPrimaryDisplayId
    val secondaryDisplayId: Int
        get() = mCachedSecondaryDisplayId

    /**
     * AaDisplayActivity presentation displayId reported from the app process.
     * Private VDs are invisible to system_server [DisplayManager.getDisplays].
     */
    @Volatile
    var mAaUiDisplayId: Int = Display.INVALID_DISPLAY
        private set

    /**
     * One-shot ATMS fallback already failed this session (no reported id).
     * Avoids scanning 1..64 on every peel touch after a miss.
     */
    @Volatile
    private var mAaUiDisplayIdLookupFailed = false
    /** Uptime of last peel-inject recovery (latch clear + UI re-report ask). */
    @Volatile
    private var mLastAaUiDisplayIdRecoveryUptime = 0L

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
    /** Displays with IME/decor policies already applied; skip WMS calls on pure size resize. */
    internal val mImePolicyAppliedDisplays = HashSet<Int>()

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
        mFullscreenPane = SplitPane.FULLSCREEN_NONE
        mRatioBeforeFullscreen = mRatio
        mPrimarySurface = primarySurface
        mSecondarySurface = secondarySurface
        mPanePackages[0] = null
        mPanePackages[1] = null
        stacks.clearAll()
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
            mCachedPrimaryDisplayId =
                mPrimary?.display?.displayId ?: Display.INVALID_DISPLAY
            mSecondary = Instances.displayManager.createVirtualDisplay(
                "AADisplay-S-${System.currentTimeMillis()}",
                sizes.secondaryW, sizes.secondaryH, mDensityDpi,
                secondarySurface, flags
            )
            mCachedSecondaryDisplayId =
                mSecondary?.display?.displayId ?: Display.INVALID_DISPLAY
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
        // Fullscreen owns layout until exit — stash peel/UI settle ratio so
        // setSplitFullscreen(NONE) can restore it in one VD resize (avoid
        // 800→ratioBefore→releaseRatio leaving Window Requested stuck).
        if (SplitPane.isFullscreenPane(mFullscreenPane)) {
            mRatioBeforeFullscreen = clamped
            return
        }
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

    /**
     * Enter fullscreen for [pane] (PRIMARY/SECONDARY) or exit with [SplitPane.FULLSCREEN_NONE].
     * Both VDs resize to full buffer while one pane is hidden under the other in AA UI.
     *
     * Samsung ExtraDisplayController often drops window / top-resumed focus on a pane during
     * that resize (seen: LivePlay → silence audio). Restore both pane tasks afterward so the
     * behind media app keeps playing and the visible pane remains focusable.
     *
     * Exit path: UI should [setSplitRatio] first while still fullscreen (updates
     * [mRatioBeforeFullscreen]), then call this with [SplitPane.FULLSCREEN_NONE] so
     * buffers jump full→split pane sizes once. After resize, nudge like swap — OneUI
     * often leaves Window Requested at an intermediate width.
     */
    fun setSplitFullscreen(pane: Int) {
        if (pane != SplitPane.FULLSCREEN_NONE && !SplitPane.isFullscreenPane(pane)) return
        if (pane == mFullscreenPane) return
        val exiting = pane == SplitPane.FULLSCREEN_NONE
        if (SplitPane.isFullscreenPane(pane)) {
            if (!SplitPane.isFullscreenPane(mFullscreenPane)) {
                mRatioBeforeFullscreen = mRatio
            }
            mFullscreenPane = pane
            mFocusedPane = pane
        } else {
            mFullscreenPane = SplitPane.FULLSCREEN_NONE
            mRatio = SplitPane.clampRatio(mRatioBeforeFullscreen)
        }
        mSuppressReclaimUntil = SystemClock.uptimeMillis() + 800L
        mHandler.removeCallbacks(mPendingResize)
        val reason = if (exiting) "fullscreen-exit" else "fullscreen-enter"
        vd.resizePanesInternal(reason)
        // Same as swap: VD resize alone often leaves Window Requested at the prior size.
        val sizes = vd.computePaneSizes()
        val primaryDisplay = primaryDisplayId
        val secondaryDisplay = secondaryDisplayId
        if (primaryDisplay != Display.INVALID_DISPLAY) {
            ownership.ensureTasksFillDisplay(
                primaryDisplay, sizes.primaryW, sizes.primaryH, reason
            )
        }
        if (secondaryDisplay != Display.INVALID_DISPLAY) {
            ownership.ensureTasksFillDisplay(
                secondaryDisplay, sizes.secondaryW, sizes.secondaryH, reason
            )
        }
        scheduleRestoreFocusAfterFullscreen()
        launch.schedulePersistSnapshot()
        notifySplitStateChanged()
        log(
            TAG,
            "setSplitFullscreen pane=$mFullscreenPane ratio=$mRatio " +
                "ratioBefore=$mRatioBeforeFullscreen"
        )
    }

    private fun scheduleRestoreFocusAfterFullscreen() {
        mHandler.removeCallbacksAndMessages(FULLSCREEN_FOCUS_TOKEN)
        val now = SystemClock.uptimeMillis()
        // ExtraDisplayController reorders asynchronously after VD resize — kick a few times.
        for (delay in longArrayOf(0L, 120L, 400L)) {
            mHandler.postAtTime(
                { restoreFocusAfterFullscreen() },
                FULLSCREEN_FOCUS_TOKEN,
                now + delay
            )
        }
    }

    /**
     * Bring each pane's [PaneAppStack] front forward. Behind pane first, then visible fullscreen
     * pane (or focused pane on exit) so media behind FS keeps resumed and the front pane owns
     * focus.
     *
     * Must not use raw ATMS tops: on a behind VD, Samsung often still reports the old front after
     * Recent 置顶, and re-bringing that would undo [PaneAppStack.moveToTop].
     */
    private fun restoreFocusAfterFullscreen() {
        val identity = Binder.clearCallingIdentity()
        try {
            val visible = mFullscreenPane
            val panes = if (SplitPane.isFullscreenPane(visible)) {
                val behind =
                    if (visible == SplitPane.PRIMARY) SplitPane.SECONDARY else SplitPane.PRIMARY
                listOf(behind, visible)
            } else {
                val focus = if (SplitPane.isValid(mFocusedPane)) mFocusedPane else SplitPane.PRIMARY
                val other =
                    if (focus == SplitPane.PRIMARY) SplitPane.SECONDARY else SplitPane.PRIMARY
                listOf(other, focus)
            }
            ownership.promoteStackFronts(panes)
            val focusPane = panes.last()
            val frontPkg = stacks.front(focusPane) ?: return
            val displayId = input.displayIdFor(focusPane) ?: return
            if (displayId == Display.INVALID_DISPLAY) return
            val taskId =
                ownership.findPackageTaskOnDisplay(frontPkg, displayId, liveOnly = true) ?: return
            trySetFocusedTask(taskId)
        } catch (e: Throwable) {
            log(TAG, "restoreFocusAfterFullscreen failed:", e)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun trySetFocusedTask(taskId: Int) {
        runCatching {
            val atm = Instances.iActivityTaskManager
            val method = atm.javaClass.methods.firstOrNull { m ->
                m.name == "setFocusedTask" &&
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: return
            method.invoke(atm, taskId)
        }
    }

    fun setFocusedPane(pane: Int) {
        if (!SplitPane.isValid(pane)) return
        mFocusedPane = pane
    }

    fun getPanePackage(pane: Int): String? {
        if (!SplitPane.isValid(pane)) return null
        // Prefer [PaneAppStack] front while that package still has a live root on the VD.
        // Blindly syncing ATMS→stack demotes Recent 置顶 when the behind pane's ATMS order lags
        // (common during fullscreen of the other side). Fall back to ATMS only when the stack
        // front is missing/dead. During settle, keep bookkeeping through transient empty walks.
        // Outside settle: no user-app roots → vacant (OWN_CONTENT_ONLY VDs often empty after close).
        val displayId = input.displayIdFor(pane)
        if (displayId != null && displayId != Display.INVALID_DISPLAY) {
            val identity = Binder.clearCallingIdentity()
            try {
                val tasks = ownership.normalizeRootTasksBottomToTop(
                    tryOrNull {
                        Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
                    }.orEmpty()
                )
                val alive = tasks.mapNotNull { info ->
                    info.topActivity?.packageName?.takeIf {
                        it.isNotBlank() && !SplitChromePackages.BOUNCE_EXCLUDED.contains(it)
                    }
                }
                val aliveSet = alive.toSet()
                val stackFront = stacks.front(pane)
                if (!stackFront.isNullOrBlank() && stackFront in aliveSet) {
                    if (mPanePackages[pane] != stackFront) mPanePackages[pane] = stackFront
                    return stackFront
                }
                // Prefer visible root; after normalize, last user task is the front.
                val topPkg = tasks.firstOrNull { info ->
                    ownership.isRootTaskVisible(info) &&
                        info.topActivity?.packageName?.let { pkg ->
                            pkg.isNotBlank() && !SplitChromePackages.BOUNCE_EXCLUDED.contains(pkg)
                        } == true
                }?.topActivity?.packageName
                    ?: tasks.lastOrNull { info ->
                        val pkg = info.topActivity?.packageName
                        !pkg.isNullOrBlank() && !SplitChromePackages.BOUNCE_EXCLUDED.contains(pkg)
                    }?.topActivity?.packageName
                if (!topPkg.isNullOrBlank()) {
                    if (mPanePackages[pane] != topPkg) mPanePackages[pane] = topPkg
                    // Read path: only reorder if already tracked — never evict here.
                    if (stacks.contains(pane, topPkg)) {
                        stacks.moveToTop(pane, topPkg)
                    }
                    return topPkg
                }
                val settling = SystemClock.uptimeMillis() < mSuppressReclaimUntil
                if (settling) {
                    return mPanePackages[pane]
                }
                // Vacant ATMS: trim dead packages from the stack.
                stacks.trimToAlive(pane, alive)
                return stacks.front(pane)
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
        return mPanePackages[pane]
    }

    fun onDestroy() {
        try {
            launch.persistSnapshot(force = true, logSettingsFailures = true)
        } catch (e: Throwable) {
            log(TAG, "onDestroy snapshot failed:", e)
        }
        mIsDestroying = true
        mAaUiDisplayId = Display.INVALID_DISPLAY
        mAaUiDisplayIdLookupFailed = false
        mLastAaUiDisplayIdRecoveryUptime = 0L
        lockedPeel.reset()
        mOrientationLockedDisplays.clear()
        mImePolicyAppliedDisplays.clear()
        mHandler.removeCallbacks(ownership.mDebouncedReclaim)
        mHandler.removeCallbacks(launch.mDebouncedPersist)
        mHandler.removeCallbacks(mPendingResize)
        mHandler.removeCallbacks(launch.mDebouncedNotifyState)
        mHandler.removeCallbacksAndMessages(launch.RESTORE_TOKEN)
        mHandler.removeCallbacksAndMessages(launch.ENSURE_TOKEN)
        mHandler.removeCallbacksAndMessages(launch.VERIFY_RESTORE_TOKEN)
        mHandler.removeCallbacksAndMessages(FULLSCREEN_FOCUS_TOKEN)
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
        mCachedPrimaryDisplayId = Display.INVALID_DISPLAY
        mCachedSecondaryDisplayId = Display.INVALID_DISPLAY
        mPrimarySurface = null
        mSecondarySurface = null
        mPanePackages[0] = null
        mPanePackages[1] = null
        stacks.clearAll()
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

    /**
     * Relay flush-left peel / AA UI touches into the AaDisplayActivity presentation
     * display (not a pane VirtualDisplay).
     *
     * While the phone keyguard is locked, Gearhead's presentation is often occluded
     * (unlike ALWAYS_UNLOCKED pane VDs) — peel inject is dropped. Handle fullscreen
     * peel gestures in [lockedPeel] instead so exit / swap / Recent still work.
     */
    fun onTouchAaDisplay(event: MotionEvent) {
        if (lockedPeel.tryHandle(event)) return
        val displayId = resolveAaUiDisplayId()
        if (displayId == Display.INVALID_DISPLAY) {
            log(TAG, "onTouchAaDisplay: AaDisplayActivity display not found")
            maybeRecoverAaUiDisplayId()
            return
        }
        input.injectInputEvent(displayId, event)
    }

    /**
     * Peel inject missed the presentation id (report race or latched ATMS miss).
     * Clear the one-shot latch so a later [setAaUiDisplayId] / one ATMS retry can
     * succeed, and ask the AA UI to re-report — debounced so MOVE floods do not
     * re-scan 1..64 every frame.
     */
    private fun maybeRecoverAaUiDisplayId() {
        val now = SystemClock.uptimeMillis()
        if (now - mLastAaUiDisplayIdRecoveryUptime < AA_UI_DISPLAY_ID_RECOVERY_MS) return
        mLastAaUiDisplayIdRecoveryUptime = now
        mAaUiDisplayIdLookupFailed = false
        try {
            context.sendBroadcast(Intent(AABroadcastConst.ACTION_REQUEST_AA_UI_DISPLAY_ID))
            log(TAG, "maybeRecoverAaUiDisplayId: asked UI to re-report")
        } catch (e: Throwable) {
            log(TAG, "maybeRecoverAaUiDisplayId broadcast failed", e)
        }
    }

    /** Called from the AA UI process; [displayId] may be INVALID_DISPLAY to clear. */
    fun setAaUiDisplayId(displayId: Int) {
        val next = if (displayId == Display.DEFAULT_DISPLAY) Display.INVALID_DISPLAY else displayId
        if (mAaUiDisplayId == next) {
            if (next != Display.INVALID_DISPLAY) {
                lockedPeel.applyAaUiDisplayKeyguardPolicy(next, "re-report")
            }
            return
        }
        mAaUiDisplayId = next
        // Fresh report (or clear) → allow one ATMS fallback again if still unknown.
        mAaUiDisplayIdLookupFailed = false
        if (next != Display.INVALID_DISPLAY) {
            mLastAaUiDisplayIdRecoveryUptime = 0L
            lockedPeel.applyAaUiDisplayKeyguardPolicy(next, "report")
        } else {
            lockedPeel.reset()
        }
        log(TAG, "setAaUiDisplayId id=$next")
    }

    /**
     * Prefer the id reported by AaDisplayActivity. Do **not** validate via
     * [DisplayManager.getDisplay]: FLAG_PRIVATE presentations owned by the app uid
     * are invisible to system_server's DisplayManager client and would discard a
     * good reported id (peel inject then fails with "display not found").
     *
     * ATMS 1..64 is a one-shot session fallback when report races; never re-scan
     * on every peel touch after a miss.
     */
    private fun resolveAaUiDisplayId(): Int {
        val reported = mAaUiDisplayId
        if (reported != Display.INVALID_DISPLAY && reported != Display.DEFAULT_DISPLAY) {
            return reported
        }
        if (mAaUiDisplayIdLookupFailed) return Display.INVALID_DISPLAY
        // Fallback once: ATMS can see tasks on private presentation displays.
        for (id in 1..64) {
            val tasks = tryOrNull {
                Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(id)
            }.orEmpty()
            if (tasks.any { it.topActivity?.packageName == BuildConfig.APPLICATION_ID }) {
                mAaUiDisplayId = id
                lockedPeel.applyAaUiDisplayKeyguardPolicy(id, "atms")
                log(TAG, "resolveAaUiDisplayId via ATMS id=$id")
                return id
            }
        }
        mAaUiDisplayIdLookupFailed = true
        log(TAG, "resolveAaUiDisplayId: no report and ATMS miss (latched)")
        return Display.INVALID_DISPLAY
    }

    fun onPressKey(action: Int) {
        // Douyin LivePlay attaches a foreign Presentation on the other pane that leaves
        // that display with no FOCUSABLE window; keys injected there are dropped. Evict
        // first, then bring the target task to front so InputDispatcher has a focus sink.
        ownership.runOnHandlerBlocking(false) {
            SplitPresentationGuard.evictForeignPresentations(this, "pressKey")
            val nextPrev =
                action == KeyEvent.KEYCODE_MEDIA_NEXT || action == KeyEvent.KEYCODE_MEDIA_PREVIOUS
            // After pane swap, mFocusedPane may still sit on 高德 while LivePlay is the other
            // VD — MEDIA_* would then hit the system media session (QQ). Prefer any live pane.
            var knownLive = false
            val displayId = if (nextPrev) {
                val liveId = resolvePaneDisplayId { input.isLiveStyleTopActivity(it) }
                if (liveId != Display.INVALID_DISPLAY) {
                    knownLive = true
                    liveId
                } else {
                    resolveKeyInjectionDisplayId()
                }
            } else {
                resolveKeyInjectionDisplayId()
            }
            if (displayId == Display.INVALID_DISPLAY) return@runOnHandlerBlocking false
            val topTask = ownership.snapshotUserRootTasks(displayId).lastOrNull()
            topTask?.let { ref ->
                ownership.bringTaskToFront(ref.taskId)
                paneForDisplayId(displayId)?.let { mFocusedPane = it }
            }
            // knownLive: already confirmed by resolvePaneDisplayId — skip a second ATMS walk.
            val live = knownLive || input.isLiveStyleTopActivity(displayId)
            // Live rooms ignore MEDIA_NEXT/PREV; swipe the current VD bounds instead
            // (size read at inject time — split ratio / resize can change anytime).
            if (live && nextPrev) {
                return@runOnHandlerBlocking input.injectLiveRoomSwipe(
                    displayId,
                    next = action == KeyEvent.KEYCODE_MEDIA_NEXT
                )
            }
            val down = input.createKeyEvent(KeyEvent.ACTION_DOWN, action)
            val up = input.createKeyEvent(KeyEvent.ACTION_UP, action)
            input.injectInputEvent(displayId, down)
            input.injectInputEvent(displayId, up)
            // Live UI often ignores injected MEDIA_* (FeedPlayerSession is torn down);
            // play/pause etc. may still reach the active media-button session.
            if (live && input.isMediaKeyCode(action)) {
                input.dispatchMediaKeyFallback(action)
            }
            true
        }
    }

    /** Focused pane first, then secondary, then primary. */
    private fun resolvePaneDisplayId(predicate: (Int) -> Boolean): Int {
        val candidates = intArrayOf(
            input.displayIdFor(mFocusedPane) ?: Display.INVALID_DISPLAY,
            secondaryDisplayId,
            primaryDisplayId,
        )
        for (displayId in candidates) {
            if (displayId == Display.INVALID_DISPLAY) continue
            if (predicate(displayId)) return displayId
        }
        return Display.INVALID_DISPLAY
    }

    /** Prefer the focused pane when it still has a user task; otherwise any occupied AA pane. */
    private fun resolveKeyInjectionDisplayId(): Int {
        val found = resolvePaneDisplayId { ownership.snapshotUserRootTasks(it).isNotEmpty() }
        if (found != Display.INVALID_DISPLAY) return found
        return input.displayIdFor(mFocusedPane) ?: primaryDisplayId
    }

    fun getRecentTask(): RecentTask {
        return try {
            val primary = if (primaryDisplayId != Display.INVALID_DISPLAY) {
                input.recentTaskInfo(
                    primaryDisplayId,
                    maxCount = PaneAppStack.MAX_PER_PANE,
                    topFirst = true,
                )
            } else {
                emptyList()
            }
            val secondary = if (secondaryDisplayId != Display.INVALID_DISPLAY) {
                input.recentTaskInfo(
                    secondaryDisplayId,
                    maxCount = PaneAppStack.MAX_PER_PANE,
                    topFirst = true,
                )
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
        // Already on this pane stack → just bring to front (no reload).
        if (stacks.contains(pane, packageName)) {
            val taskId = ownership.findPackageTaskOnDisplay(packageName, displayId, liveOnly = true)
            if (taskId != null && ownership.bringTaskToFront(taskId)) {
                stacks.moveToTop(pane, packageName)
                // 置顶 on the behind pane while the other side is fullscreen: keep the visible
                // FS pane focused for input/media, but promote stack fronts so ATMS catches up.
                if (SplitPane.isFullscreenPane(mFullscreenPane) && mFullscreenPane != pane) {
                    mFocusedPane = mFullscreenPane
                    ownership.promoteStackFronts(listOf(pane, mFullscreenPane))
                    val fsDisplay = input.displayIdFor(mFullscreenPane)
                    val fsFront = stacks.front(mFullscreenPane)
                    if (fsDisplay != null &&
                        fsDisplay != Display.INVALID_DISPLAY &&
                        !fsFront.isNullOrBlank()
                    ) {
                        ownership.findPackageTaskOnDisplay(fsFront, fsDisplay, liveOnly = true)
                            ?.let { trySetFocusedTask(it) }
                    }
                } else {
                    mFocusedPane = pane
                }
                ownership.markOwnership(packageName, displayId)
                launch.schedulePersistSnapshot()
                notifySplitStateChanged()
                log(TAG, "startActivityOnPane front-existing pkg=$packageName pane=$pane")
                return true
            }
            // Bring failed (zombie / LAUNCHER≠topActivity) or task missing — drop and relaunch.
            if (taskId != null) {
                log(TAG, "startActivityOnPane front-existing bring failed, relaunch pkg=$packageName")
                ownership.removePackageTasksOnDisplay(packageName, displayId)
            }
            stacks.remove(pane, packageName)
        }
        // Cap at MAX_PER_PANE: evict bottom before pushing a new package.
        if (!stacks.contains(pane, packageName) &&
            stacks.packagesBottomToTop(pane).size >= PaneAppStack.MAX_PER_PANE
        ) {
            val bottom = stacks.packagesBottomToTop(pane).firstOrNull()
            if (!bottom.isNullOrBlank() && bottom != packageName) {
                log(TAG, "startActivityOnPane evict bottom=$bottom pane=$pane")
                ownership.evictPackageFromPane(pane, bottom)
            }
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
                // Same pane but buried / bring no-op: must relaunch (mirrors stacks.contains).
                if (ownership.bringTaskToFront(taskId)) {
                    true
                } else {
                    log(TAG, "startActivityOnPane same-display bring failed, relaunch pkg=$packageName")
                    ownership.removePackageTasksEverywhere(packageName)
                    launch.launchOnDisplay(component, userId, displayId)
                }
            } else {
                ownership.vacateOtherPanesHolding(packageName, keepPane = pane)
                val relocated = try {
                    Instances.iActivityTaskManager.moveRootTaskToDisplay(taskId, displayId)
                    VdDensityPin.markPackageOnVirtualDisplay(
                        packageName,
                        displayId
                    )
                    log(TAG, "startActivityOnPane relocate $packageName#$taskId $fromDisplay->$displayId")
                    ownership.bringTaskToFront(taskId)
                    // bringTaskToFront can succeed on the *phone* while move was ignored —
                    // require the live root to actually sit on the target VD.
                    ownership.findPackageTaskOnDisplay(packageName, displayId, liveOnly = true) != null
                } catch (e: Throwable) {
                    log(TAG, "startActivityOnPane relocate failed:", e)
                    false
                }
                if (!relocated) {
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
            stacks.pushToTop(pane, packageName)
            mFocusedPane = pane
            ownership.markOwnership(packageName, displayId)
            launch.schedulePersistSnapshot()
            notifySplitStateChanged()
            log(TAG, "startActivityOnPane ok pkg=$packageName pane=$pane display=$displayId stack=${stacks.packagesBottomToTop(pane)}")
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
            val pane = targetPane!!
            // Push onto stack (evict bottom if full); do not kill other stack apps.
            if (!packageName.isNullOrBlank() &&
                !stacks.contains(pane, packageName) &&
                stacks.packagesBottomToTop(pane).size >= PaneAppStack.MAX_PER_PANE
            ) {
                val bottom = stacks.packagesBottomToTop(pane).firstOrNull()
                if (!bottom.isNullOrBlank() && bottom != packageName) {
                    ownership.evictPackageFromPane(pane, bottom)
                }
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
            if (!packageName.isNullOrBlank()) {
                stacks.pushToTop(pane, packageName)
                ownership.markOwnership(packageName, targetDisplayId)
                VdDensityPin.markPackageOnVirtualDisplay(packageName, targetDisplayId)
            }
            mFocusedPane = pane
        } else {
            VdDensityPin.clearPackageVirtualDisplay(packageName)
            // Promote remaining stack fronts; strip chrome only on emptied panes.
            vacatedPanes.distinct().forEach { pane ->
                if (stacks.front(pane) == null) {
                    input.displayIdFor(pane)?.let { ownership.removeChromeTasksOnDisplay(it) }
                }
            }
        }
        launch.schedulePersistSnapshot()
        notifySplitStateChanged()
        return ownership.bringTaskToFront(taskId)
    }

    fun moveTaskToFront(taskId: Int): Boolean {
        return ownership.runOnHandlerBlocking(false) {
            val packageName = ownership.findPackageForTask(taskId)
            val ok = ownership.bringTaskToFront(taskId)
            if (ok && !packageName.isNullOrBlank()) {
                val pane = stacks.paneContaining(packageName)
                    ?: paneForDisplayId(
                        ownership.findLivePackageTaskAnywhere(packageName)?.second
                            ?: Display.INVALID_DISPLAY
                    )
                if (pane != null) {
                    if (stacks.contains(pane, packageName)) {
                        stacks.moveToTop(pane, packageName)
                    } else {
                        stacks.pushToTop(pane, packageName)
                    }
                    mFocusedPane = pane
                    launch.schedulePersistSnapshot()
                    notifySplitStateChanged()
                }
            }
            ok
        }
    }

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
                val vacated = stacks.removeFromAll(packageName)
                ownership.releaseOwnershipIfUnused(packageName)
                VdDensityPin.clearPackageVirtualDisplay(packageName)
                ownership.untrackPackage(packageName)
                ownership.promoteStackFronts(vacated)
                vacated.forEach { pane ->
                    if (stacks.front(pane) == null) {
                        input.displayIdFor(pane)?.let { ownership.removeChromeTasksOnDisplay(it) }
                    }
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
        // Prefer stack front; fall back to ATMS top (list is bottom → top).
        val frontPkg = stacks.front(other)
        val taskId = if (!frontPkg.isNullOrBlank()) {
            ownership.findPackageTaskOnDisplay(frontPkg, displayId, liveOnly = true)
        } else {
            ownership.snapshotUserRootTasks(displayId).lastOrNull()?.taskId
        } ?: return
        mFocusedPane = other
        ownership.bringTaskToFront(taskId)
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
        val stackPrimary = stacks.packagesBottomToTop(SplitPane.PRIMARY).ifEmpty {
            snapPrimary.mapNotNull { it.packageName }.distinct()
        }
        val stackSecondary = stacks.packagesBottomToTop(SplitPane.SECONDARY).ifEmpty {
            snapSecondary.mapNotNull { it.packageName }.distinct()
        }

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

        stacks.setStackBottomToTop(SplitPane.PRIMARY, stackSecondary)
        stacks.setStackBottomToTop(SplitPane.SECONDARY, stackPrimary)
        // Prefer remembered fronts when still present after swap.
        frontSecondary?.let { stacks.moveToTop(SplitPane.PRIMARY, it) }
        frontPrimary?.let { stacks.moveToTop(SplitPane.SECONDARY, it) }
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
            stacks.packagesBottomToTop(SplitPane.PRIMARY).forEach { add(it) }
            stacks.packagesBottomToTop(SplitPane.SECONDARY).forEach { add(it) }
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

        // Sizes follow the apps (unless fullscreen — both stay full buffer).
        if (SplitPane.isFullscreenPane(mFullscreenPane)) {
            mFullscreenPane = if (mFullscreenPane == SplitPane.PRIMARY) {
                SplitPane.SECONDARY
            } else {
                SplitPane.PRIMARY
            }
            // Keep the pre-fullscreen split ratio; do not invert while FS.
        } else {
            mRatio = SplitPane.clampRatio(1f - mRatio)
        }
        mSuppressReclaimUntil = SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_MS
        vd.resizePanesInternal("swap")
        // Move+resize often leaves Window frames at the pre-swap size (ADB: 386 on a 436 VD).
        // OneUI ignores resizeTask on fullscreen roots — nudge VD ±1px to re-dispatch config.
        val sizes = vd.computePaneSizes()
        ownership.ensureTasksFillDisplay(
            primaryDisplay, sizes.primaryW, sizes.primaryH, "swap"
        )
        ownership.ensureTasksFillDisplay(
            secondaryDisplay, sizes.secondaryW, sizes.secondaryH, "swap"
        )
        mHandler.postDelayed({
            if (mIsDestroying) return@postDelayed
            if (primaryDisplayId != primaryDisplay || secondaryDisplayId != secondaryDisplay) {
                return@postDelayed
            }
            ownership.snapshotUserRootTasks(primaryDisplay).lastOrNull()?.let {
                ownership.bringTaskToFront(it.taskId)
            }
            ownership.snapshotUserRootTasks(secondaryDisplay).lastOrNull()?.let {
                ownership.bringTaskToFront(it.taskId)
            }
        }, 220L)

        launch.schedulePersistSnapshot()
        notifySplitStateChanged()
        log(
            TAG,
            "swapPanes ok primary=${mPanePackages[SplitPane.PRIMARY]} " +
                "secondary=${mPanePackages[SplitPane.SECONDARY]} ratio=$mRatio " +
                "fullscreen=$mFullscreenPane " +
                "tasksP=${afterPrimary.map { it.taskId }} tasksS=${afterSecondary.map { it.taskId }}"
        )
        return true
    }

    fun notifySplitStateChanged() {
        launch.notifySplitStateChangedImmediate()
    }
}
