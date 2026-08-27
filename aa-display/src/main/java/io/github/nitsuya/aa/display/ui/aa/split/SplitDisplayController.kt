package io.github.nitsuya.aa.display.ui.aa.split

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Point
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
import io.github.nitsuya.aa.display.util.AvMediaArbiter
import io.github.nitsuya.aa.display.util.LastSplitStore
import io.github.nitsuya.aa.display.util.PmCaches
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
        /** Token for post-reconnect task fill / layout nudge kicks. */
        internal val RECONNECT_FILL_TOKEN = Any()
        /** Token for debounced ratio-settle config refresh (no VD nudge). */
        internal val RATIO_SETTLE_FILL_TOKEN = Any()
        internal const val RATIO_SETTLE_FILL_DELAY_MS = 180L
    }

    internal val vd = SplitVdLifecycle(this)
    internal val launch = SplitLaunchRestore(this)
    internal val ownership = SplitOwnership(this)
    internal val buriedPlayback = SplitBuriedPlayback(this)
    internal val input = SplitInputRecents(this)
    internal val recentProvider = RecentTaskProvider(this)
    internal val stacks = PaneAppStack(this)
    internal val lockedPeel = SplitLockedPeelController(this)
    internal val ime = SplitImeController(this)

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
    private var mHidShellGeometry: HidShellGeometry? = null

    @Volatile
    private var mAaUiDisplayIdLookupFailed = false

    fun aaUiDisplayId(): Int = mAaUiDisplayId

    fun updateHidShellGeometry(geometry: HidShellGeometry) {
        mHidShellGeometry = geometry
    }

    fun clearHidShellGeometry() {
        mHidShellGeometry = null
    }
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
    internal val mVdPackages = mutableSetOf<String>()
    /** Explicit Recent/stack closes this session — must not backfill or restore from snapshot. */
    internal val mExplicitlyClosedPackages = mutableSetOf<String>()
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
    private var mPendingShrinkPrimary = false
    private var mPendingShrinkSecondary = false
    internal val mPendingResize = Runnable {
        vd.resizePanesInternal("ratio-throttled")
        scheduleEnsureTasksFillAfterRatioSettle(mPendingShrinkPrimary, mPendingShrinkSecondary)
    }

    private var packageReceiverRegistered = false
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pkg = intent?.data?.schemeSpecificPart
            if (pkg.isNullOrBlank()) {
                PmCaches.invalidateAll()
            } else {
                PmCaches.invalidatePackage(pkg)
            }
        }
    }

    init {
        registerPackageReceiver()
        scope.launch {
            onReady()
        }
    }

    private fun registerPackageReceiver() {
        if (packageReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        try {
            context.registerReceiver(
                packageReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED,
            )
            packageReceiverRegistered = true
        } catch (e: Throwable) {
            log(TAG, "register package receiver failed", e)
        }
    }

    private fun unregisterPackageReceiver() {
        if (!packageReceiverRegistered) return
        try {
            context.unregisterReceiver(packageReceiver)
        } catch (_: Throwable) {
        }
        packageReceiverRegistered = false
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
        mVdPackages.clear()
        mTrackedPackageUsers.clear()
        mSuppressReclaimUntil = 0L
        mHandler.removeCallbacks(launch.mDebouncedAtmsSettle)
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

        logDebug(
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
            ime.start()
        }

        if (launch.shouldRestoreLastSplitOnConnect()) {
            mSuppressReclaimUntil =
                SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_AFTER_RESTORE_MS
            launch.prefillRestoreFromSnapshot()
            notifySplitStateChanged()
            launch.scheduleRestoreLastSplit()
        } else {
            notifySplitStateChanged()
        }
    }

    fun onReconnected(width: Int, height: Int, densityDpi: Int) {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val dpi = densityDpi.coerceAtLeast(1)
        mWidth = w
        mHeight = h
        mDensityDpi = dpi
        // VD resize / WM settle is async — do not let refreshPanePackagesFromAtms trim stacks
        // while a pane is briefly empty (e.g. Luna left VD after disconnect).
        mSuppressReclaimUntil = maxOf(
            mSuppressReclaimUntil,
            SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_AFTER_RESTORE_MS,
        )
        // Soft reconnect: always resize — profile may match while a pane Surface/task is stale.
        vd.resizePanesInternal("reconnect")
        vd.applyPolicies(SplitPane.PRIMARY, "reconnect")
        vd.applyPolicies(SplitPane.SECONDARY, "reconnect")
        ime.start()
        scheduleEnsureTasksFillAfterReconnect()
        if (launch.shouldRestoreLastSplitOnConnect() && launch.bothPanesVacantOnDisplays()) {
            mSuppressReclaimUntil = maxOf(
                mSuppressReclaimUntil,
                SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_AFTER_RESTORE_MS,
            )
            launch.prefillRestoreFromSnapshot()
            notifySplitStateChanged()
            launch.scheduleRestoreLastSplit()
        }
        launch.scheduleReconnectEnsurePasses()
        SplitPresentationGuard.scheduleEvictForeignPresentations(this, "reconnect")
    }

    private fun scheduleEnsureTasksFillAfterReconnect() {
        mHandler.removeCallbacksAndMessages(RECONNECT_FILL_TOKEN)
        val now = SystemClock.uptimeMillis()
        // ExtraDisplayController / WM reorder asynchronously after surface rebind.
        for (delay in longArrayOf(0L, 400L)) {
            mHandler.postAtTime(
                { ensureTasksFillBothPanes("reconnect") },
                RECONNECT_FILL_TOKEN,
                now + delay,
            )
        }
    }

    private fun ensureTasksFillBothPanes(
        reason: String,
        nudgeVd: Boolean = true,
        nudgePrimary: Boolean? = null,
        nudgeSecondary: Boolean? = null,
    ) {
        val sizes = vd.computePaneSizes()
        val primaryDisplay = primaryDisplayId
        val secondaryDisplay = secondaryDisplayId
        val nudgeP = nudgePrimary ?: nudgeVd
        val nudgeS = nudgeSecondary ?: nudgeVd
        if (primaryDisplay != Display.INVALID_DISPLAY) {
            ownership.ensureTasksFillDisplay(
                primaryDisplay, sizes.primaryW, sizes.primaryH, reason, nudgeP
            )
        }
        if (secondaryDisplay != Display.INVALID_DISPLAY) {
            ownership.ensureTasksFillDisplay(
                secondaryDisplay, sizes.secondaryW, sizes.secondaryH, reason, nudgeS
            )
        }
    }

    /** Ratio settle: push WM/task config; nudge only panes that shrank (OneUI letterbox). */
    private fun scheduleEnsureTasksFillAfterRatioSettle(
        nudgePrimary: Boolean = false,
        nudgeSecondary: Boolean = false,
    ) {
        if (SplitPane.isFullscreenPane(mFullscreenPane)) return
        mHandler.removeCallbacksAndMessages(RATIO_SETTLE_FILL_TOKEN)
        val now = SystemClock.uptimeMillis()
        mHandler.postAtTime(
            {
                ensureTasksFillBothPanes(
                    "ratio-settle",
                    nudgePrimary = nudgePrimary,
                    nudgeSecondary = nudgeSecondary,
                )
            },
            RATIO_SETTLE_FILL_TOKEN,
            now + RATIO_SETTLE_FILL_DELAY_MS,
        )
    }

    private fun ratioShrinkFlags(): Pair<Boolean, Boolean> {
        val sizes = vd.computePaneSizes()
        val shrinkPrimary =
            sizes.primaryW < mLastPrimaryW || sizes.primaryH < mLastPrimaryH
        val shrinkSecondary =
            sizes.secondaryW < mLastSecondaryW || sizes.secondaryH < mLastSecondaryH
        return shrinkPrimary to shrinkSecondary
    }

    fun setPaneSurface(pane: Int, surface: Surface?) {
        if (!SplitPane.isValid(pane)) return
        val bothWereReady = mPrimarySurface != null && mSecondarySurface != null
        if (pane == SplitPane.PRIMARY) {
            mPrimarySurface = surface
            mPrimary?.surface = surface
        } else {
            mSecondarySurface = surface
            mSecondary?.surface = surface
        }
        logDebug(TAG, "setPaneSurface pane=$pane surface=${surface != null}")
        if (mIsDestroying) return
        val bothReady = mPrimarySurface != null && mSecondarySurface != null
        // Only ensure on first both-ready or reconnect — quiet rebind after ratio settle
        // must not re-run ensure (cold relaunch / resolution rebuild on every drag).
        if (bothReady && !bothWereReady) {
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
        val (shrinkPrimary, shrinkSecondary) = ratioShrinkFlags()
        // Resizing VDs makes tasks churn; suppress reclaim + ATMS stack refresh until settle.
        mSuppressReclaimUntil = SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_MS
        val now = SystemClock.uptimeMillis()
        if (now - mLastResizeAt < RESIZE_THROTTLE_MS) {
            mPendingShrinkPrimary = shrinkPrimary
            mPendingShrinkSecondary = shrinkSecondary
            mHandler.removeCallbacks(mPendingResize)
            mHandler.postDelayed(mPendingResize, RESIZE_THROTTLE_MS)
            return
        }
        vd.resizePanesInternal("ratio")
        scheduleEnsureTasksFillAfterRatioSettle(shrinkPrimary, shrinkSecondary)
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
        logDebug(
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

    /** Non-front stack packages on either AA VD pane (for [AvMediaArbiter.StackLayout]). */
    fun buriedPackagesOnAaDisplays(): Set<String> {
        val buried = linkedSetOf<String>()
        for (pane in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
            val front = stacks.front(pane)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            stacks.packagesBottomToTop(pane)
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != front }
                .forEach { buried += it }
        }
        return buried
    }

    /** Stack fronts + focus for [io.github.nitsuya.aa.display.util.AvMediaArbiter]. */
    fun avStackLayout(): AvMediaArbiter.StackLayout {
        return AvMediaArbiter.StackLayout(
            focusedPane = mFocusedPane,
            primaryFront = stacks.front(SplitPane.PRIMARY)?.trim()?.takeIf { it.isNotEmpty() },
            secondaryFront = stacks.front(SplitPane.SECONDARY)?.trim()?.takeIf { it.isNotEmpty() },
            buried = buriedPackagesOnAaDisplays(),
        )
    }

    fun getPanePackage(pane: Int): String? {
        if (!SplitPane.isValid(pane)) return null
        // Read-only: intentional stack front is truth; reconcile happens in scheduleAtmsSettle.
        stacks.front(pane)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (SystemClock.uptimeMillis() < mSuppressReclaimUntil) {
            return mPanePackages[pane]?.trim()?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    fun onDestroy() {
        unregisterPackageReceiver()
        try {
            launch.persistSnapshot(force = true, logSettingsFailures = true)
        } catch (e: Throwable) {
            log(TAG, "onDestroy snapshot failed:", e)
        }
        mIsDestroying = true
        launch.resetSettlement()
        mExplicitlyClosedPackages.clear()
        ime.stop()
        mAaUiDisplayId = Display.INVALID_DISPLAY
        mAaUiDisplayIdLookupFailed = false
        mLastAaUiDisplayIdRecoveryUptime = 0L
        lockedPeel.reset()
        mOrientationLockedDisplays.clear()
        mImePolicyAppliedDisplays.clear()
        mHandler.removeCallbacks(launch.mDebouncedAtmsSettle)
        mHandler.removeCallbacks(launch.mDebouncedPersist)
        mHandler.removeCallbacks(mPendingResize)
        mHandler.removeCallbacksAndMessages(launch.RESTORE_TOKEN)
        mHandler.removeCallbacksAndMessages(launch.ENSURE_TOKEN)
        mHandler.removeCallbacksAndMessages(launch.VERIFY_RESTORE_TOKEN)
        mHandler.removeCallbacksAndMessages(FULLSCREEN_FOCUS_TOKEN)
        mHandler.removeCallbacksAndMessages(RECONNECT_FILL_TOKEN)
        buriedPlayback.cancelScheduledEnforceSingleSounder()
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

    /** Recents / picker overlay — inject BT keyboard onto AaDisplay presentation. */
    fun onHidKeyEventToAaDisplay(event: KeyEvent): Boolean {
        val displayId = resolveAaUiDisplayId()
        if (displayId == Display.INVALID_DISPLAY) {
            log(TAG, "onHidKeyEventToAaDisplay: AaDisplayActivity display not found")
            maybeRecoverAaUiDisplayId()
            return false
        }
        return input.injectKeyEvent(displayId, event)
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
            logDebug(TAG, "maybeRecoverAaUiDisplayId: asked UI to re-report")
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
        logDebug(TAG, "setAaUiDisplayId id=$next")
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
                logDebug(TAG, "resolveAaUiDisplayId via ATMS id=$id")
                return id
            }
        }
        mAaUiDisplayIdLookupFailed = true
        log(TAG, "resolveAaUiDisplayId: no report and ATMS miss (latched)")
        return Display.INVALID_DISPLAY
    }

    fun hideIme() {
        ime.hide()
    }

    fun getImePane(): Int = ime.currentPane()

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
            val roots = tryOrNull {
                Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
            }.orEmpty()
            val userTasks = ownership.snapshotUserRootTasks(
                ownership.normalizeRootTasksBottomToTop(roots),
            )
            val topTask = userTasks.lastOrNull()
            topTask?.let { ref ->
                ownership.bringTaskToFront(
                    ref.taskId,
                    cachedRootsByDisplay = mapOf(displayId to roots),
                )
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

    /** Target VD for phone BT keyboard / mouse redirect. */
    fun resolveHidInjectionDisplayId(): Int = resolveKeyInjectionDisplayId()

    fun onHidKeyEvent(event: KeyEvent): Boolean {
        val displayId = resolveHidInjectionDisplayId()
        if (displayId == Display.INVALID_DISPLAY) return false
        return input.injectKeyEvent(displayId, event)
    }

    fun onHidTouch(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
    ): Boolean {
        val displayId = resolveHidInjectionDisplayId()
        if (displayId == Display.INVALID_DISPLAY) return false
        if (action == MotionEvent.ACTION_DOWN) {
            paneForDisplayId(displayId)?.let { mFocusedPane = it }
        }
        return input.injectTouchAt(displayId, action, x, y, downTime, eventTime)
    }

    fun onHidScroll(x: Float, y: Float, vScroll: Float, hScroll: Float): Boolean {
        val displayId = resolveHidInjectionDisplayId()
        if (displayId == Display.INVALID_DISPLAY) return false
        return input.injectScrollAt(displayId, x, y, vScroll, hScroll)
    }

    fun hidTargetSize(): Point? {
        val displayId = resolveHidInjectionDisplayId()
        if (displayId == Display.INVALID_DISPLAY) return null
        return input.displaySizePx(displayId)
    }

    fun hidSizeForPane(pane: Int): Point? {
        val displayId = input.displayIdFor(pane) ?: return null
        return input.displaySizePx(displayId)
    }

    fun onHidTouchOnPane(
        pane: Int,
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
    ): Boolean {
        if (!SplitPane.isValid(pane)) return false
        val displayId = input.displayIdFor(pane) ?: return false
        if (action == MotionEvent.ACTION_DOWN) {
            mFocusedPane = pane
        }
        return input.injectTouchAt(displayId, action, x, y, downTime, eventTime)
    }

    fun onHidScrollOnPane(
        pane: Int,
        x: Float,
        y: Float,
        vScroll: Float,
        hScroll: Float,
    ): Boolean {
        if (!SplitPane.isValid(pane)) return false
        val displayId = input.displayIdFor(pane) ?: return false
        return input.injectScrollAt(displayId, x, y, vScroll, hScroll)
    }

    /**
     * Snapshot for BT mouse cross-pane cursor.
     *
     * Pane sizes **must** match the real VirtualDisplays (inject + pointer bind), not the
     * AA UI TextureView metrics. UI density / Coolwalk chrome can differ from [mDensityDpi]
     * and previously made right-edge clicks look hundreds of px early / "wrong resolution".
     * Shell geometry only supplies fullscreen / expand hints and optional touch scaling.
     */
    fun hidSplitLayout(): HidSplitLayout {
        val sizes = vd.computePaneSizes()
        val livePrimary = hidSizeForPane(SplitPane.PRIMARY)
        val liveSecondary = hidSizeForPane(SplitPane.SECONDARY)
        val primaryW = (livePrimary?.x?.takeIf { it > 0 } ?: sizes.primaryW).coerceAtLeast(1)
        val primaryH = (livePrimary?.y?.takeIf { it > 0 } ?: sizes.primaryH).coerceAtLeast(1)
        val secondaryW = (liveSecondary?.x?.takeIf { it > 0 } ?: sizes.secondaryW).coerceAtLeast(1)
        val secondaryH = (liveSecondary?.y?.takeIf { it > 0 } ?: sizes.secondaryH).coerceAtLeast(1)
        val geo = mHidShellGeometry
        val sideBySide = geo?.sideBySide ?: isSideBySide
        val totalW = mWidth.coerceAtLeast(1)
        val totalH = mHeight.coerceAtLeast(1)
        // Gap closes the canvas: primary + gap + secondary == total (not UI density gap).
        val gap = if (SplitPane.isFullscreenPane(geo?.fullscreenPane ?: mFullscreenPane)) {
            0
        } else if (sideBySide) {
            (totalW - primaryW - secondaryW).coerceAtLeast(1)
        } else {
            (totalH - primaryH - secondaryH).coerceAtLeast(1)
        }
        return HidSplitLayout(
            sideBySide = sideBySide,
            fullscreenPane = geo?.fullscreenPane ?: mFullscreenPane,
            focusedPane = mFocusedPane,
            primaryDisplayId = primaryDisplayId,
            secondaryDisplayId = secondaryDisplayId,
            primaryW = primaryW,
            primaryH = primaryH,
            secondaryW = secondaryW,
            secondaryH = secondaryH,
            totalW = totalW,
            totalH = totalH,
            densityDpi = mDensityDpi.coerceAtLeast(160),
            shellGap = gap,
            shellExpand = geo?.expand ?: -1,
            shellTotalW = geo?.parentW ?: 0,
            shellTotalH = geo?.parentH ?: 0,
        )
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
            recentProvider.buildSnapshot()
        } catch (e: Throwable) {
            log(TAG, "RecentTask Exception", e)
            RecentTask(emptyList(), emptyList(), emptyList())
        }
    }

    fun reorderPaneStack(pane: Int, packagesTopToBottom: Array<out String>): Boolean {
        if (!SplitPane.isValid(pane)) return false
        return ownership.runOnHandlerBlocking(false) {
            reorderPaneStackOnHandler(pane, packagesTopToBottom.toList())
        }
    }

    /** Drag-reordered VD stack; [packagesTopToBottom] index 0 = front (visible). */
    private fun reorderPaneStackOnHandler(pane: Int, packagesTopToBottom: List<String>): Boolean {
        if (!SplitPane.isValid(pane)) return false
        val bottomToTop = packagesTopToBottom.asReversed()
        stacks.setStackBottomToTop(pane, bottomToTop)
        ownership.promoteStackFronts(listOf(pane))
        mSuppressReclaimUntil = SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_MS
        launch.schedulePersistSnapshot()
        notifySplitStateChanged()
        return true
    }

    fun startActivity(packageName: String, userId: Int): Boolean {
        return startActivityOnPane(packageName, userId, mFocusedPane)
    }

    fun startActivityOnPane(packageName: String, userId: Int, pane: Int): Boolean {
        // Same queue as move/remove/reclaim — Binder/IO must not interleave stack mutations.
        // runOnHandlerBlocking already runs inline when already on mHandler (restore loops).
        return ownership.runOnHandlerBlocking(false) {
            startActivityOnPaneOnHandler(packageName, userId, pane)
        }
    }

    /** Picker / Recent tap — jump ahead of ensure/reclaim on the shared launch handler. */
    fun startActivityOnPaneAsync(packageName: String, userId: Int, pane: Int) {
        postUserAction { startActivityOnPaneOnHandler(packageName, userId, pane) }
    }

    /**
     * Recent VD-column tap — sync IPC; cancels settle and blocks until promote/launch
     * finishes on [mHandler] (mirrors [reorderPaneStack] + [postUserAction] ordering).
     */
    fun startActivityOnPaneForUser(packageName: String, userId: Int, pane: Int): Boolean {
        cancelBackgroundSettleForUserAction()
        return ownership.runOnHandlerBlockingAtFront(false) {
            if (mIsDestroying) return@runOnHandlerBlockingAtFront false
            startActivityOnPaneOnHandler(packageName, userId, pane)
        }
    }

    fun startActivityAsync(packageName: String, userId: Int) {
        startActivityOnPaneAsync(packageName, userId, mFocusedPane)
    }

    /** User close / swipe-off / picker — cancel background settle and run next. */
    private fun postUserAction(block: () -> Unit) {
        cancelBackgroundSettleForUserAction()
        mHandler.postAtFrontOfQueue {
            if (mIsDestroying) return@postAtFrontOfQueue
            try {
                block()
            } catch (e: Throwable) {
                log(TAG, "postUserAction failed:", e)
            }
        }
    }

    private fun cancelBackgroundSettleForUserAction() {
        mHandler.removeCallbacks(launch.mDebouncedAtmsSettle)
        mHandler.removeCallbacks(launch.mDebouncedPersist)
        // Do not cancel restore/ensure/verify — user picks must not abort connect memory restore.
        mHandler.removeCallbacksAndMessages(FULLSCREEN_FOCUS_TOKEN)
        mHandler.removeCallbacksAndMessages(RECONNECT_FILL_TOKEN)
        mHandler.removeCallbacksAndMessages(RATIO_SETTLE_FILL_TOKEN)
        mSuppressReclaimUntil = SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_MS
    }

    private fun finishLaunchOnPane(pane: Int, packageName: String, displayId: Int) {
        ownership.enforceStackFrontAudio(pane)
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
        mPanePackages[pane] = packageName
        mExplicitlyClosedPackages.remove(packageName.trim())
        ownership.markOwnership(packageName, displayId)
        launch.schedulePersistSnapshot()
        notifySplitStateChanged()
    }

    private fun startActivityOnPaneOnHandler(packageName: String, userId: Int, pane: Int): Boolean {
        if (!SplitPane.isValid(pane)) return false
        val displayId = input.displayIdFor(pane) ?: return false
        val pkg = packageName.trim()
        if (pkg.isNotEmpty()) {
            // User picked / Recent tapped — allow relaunch after an explicit close this session.
            mExplicitlyClosedPackages.remove(pkg)
        }
        val component = launch.resolveLaunchComponent(packageName) ?: run {
            log(TAG, "startActivityOnPane: no launcher for $packageName")
            return false
        }
        // Already on this pane stack → just bring to front (no reload).
        if (stacks.contains(pane, packageName)) {
            val taskId = ownership.findPackageTaskOnDisplay(packageName, displayId, liveOnly = true)
            if (taskId != null && ownership.bringTaskToFront(taskId)) {
                stacks.moveToTop(pane, packageName)
                finishLaunchOnPane(pane, packageName, displayId)
                logDebug(TAG, "startActivityOnPane front-existing pkg=$packageName pane=$pane")
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
                logDebug(TAG, "startActivityOnPane evict bottom=$bottom pane=$pane")
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
                // Vacate→pushToTop leaves paneContaining null; suppress reclaim so it cannot
                // releaseOwnershipIfUnused mid-relocate and bounce the task back.
                mHandler.removeCallbacks(launch.mDebouncedAtmsSettle)
                mSuppressReclaimUntil = SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_MS
                ownership.vacateOtherPanesHolding(packageName, keepPane = pane)
                val relocated = try {
                    Instances.iActivityTaskManager.moveRootTaskToDisplay(taskId, displayId)
                    VdDensityPin.markPackageOnVirtualDisplay(
                        packageName,
                        displayId
                    )
                    logDebug(TAG, "startActivityOnPane relocate $packageName#$taskId $fromDisplay->$displayId")
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
            finishLaunchOnPane(pane, packageName, displayId)
            logDebug(TAG, "startActivityOnPane ok pkg=$packageName pane=$pane display=$displayId stack=${stacks.packagesBottomToTop(pane)}")
        } else {
            log(TAG, "startActivityOnPane failed pkg=$packageName pane=$pane display=$displayId")
        }
        return ok
    }

    fun moveTaskId(taskId: Int, isVirtualDisplay: Boolean): Boolean {
        // Ownership + reclaim run on mHandler; Binder/IO callers must not race them.
        return ownership.runOnHandlerBlocking(false) {
            moveTaskIdOnHandler(taskId, if (isVirtualDisplay) mFocusedPane else null)
        }
    }

    fun moveTaskIdAsync(taskId: Int, isVirtualDisplay: Boolean) {
        postUserAction {
            moveTaskIdOnHandler(taskId, if (isVirtualDisplay) mFocusedPane else null)
        }
    }

    /** Move [taskId] onto PRIMARY/SECONDARY; [pane] must be a valid [SplitPane]. */
    fun moveTaskIdToPane(taskId: Int, pane: Int): Boolean {
        if (!SplitPane.isValid(pane)) return false
        return ownership.runOnHandlerBlocking(false) { moveTaskIdOnHandler(taskId, targetPane = pane) }
    }

    fun moveTaskIdToPaneAsync(taskId: Int, pane: Int) {
        if (!SplitPane.isValid(pane)) return
        postUserAction { moveTaskIdOnHandler(taskId, targetPane = pane) }
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
            mHandler.removeCallbacks(launch.mDebouncedAtmsSettle)
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
                // Same vacate→push window as startActivityOnPane: hold reclaim until stacked.
                mHandler.removeCallbacks(launch.mDebouncedAtmsSettle)
                mSuppressReclaimUntil = SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_MS
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
                ownership.enforceStackFrontAudio(pane)
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
        return ownership.runOnHandlerBlocking(false) { moveTaskToFrontOnHandler(taskId) }
    }

    fun moveTaskToFrontAsync(taskId: Int) {
        postUserAction { moveTaskToFrontOnHandler(taskId) }
    }

    private fun moveTaskToFrontOnHandler(taskId: Int): Boolean {
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
                    // Match startActivityOnPane: evict bottom tasks before bookkeeping push.
                    if (stacks.packagesBottomToTop(pane).size >= PaneAppStack.MAX_PER_PANE) {
                        val bottom = stacks.packagesBottomToTop(pane).firstOrNull()
                        if (!bottom.isNullOrBlank() && bottom != packageName) {
                            ownership.evictPackageFromPane(pane, bottom)
                        }
                    }
                    stacks.pushToTop(pane, packageName)
                }
                ownership.enforceStackFrontAudio(pane)
                mFocusedPane = pane
                launch.schedulePersistSnapshot()
                notifySplitStateChanged()
            }
        }
        return ok
    }

    fun removeTask(taskId: Int): Boolean {
        return ownership.runOnHandlerBlocking(false) { removeTaskOnHandler(taskId) }
    }

    fun removeTaskAsync(taskId: Int) {
        postUserAction { removeTaskOnHandler(taskId) }
    }

    fun reorderPaneStackAsync(pane: Int, packagesTopToBottom: Array<out String>) {
        postUserAction { reorderPaneStackOnHandler(pane, packagesTopToBottom.toList()) }
    }

    private fun removeTaskOnHandler(taskId: Int): Boolean {
        val packageName = ownership.findPackageForTask(taskId)
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() }
        val onVd = ownership.isTaskOnAaDisplay(taskId)
        return try {
            if (onVd) {
                mHandler.removeCallbacks(launch.mDebouncedAtmsSettle)
                mSuppressReclaimUntil = SystemClock.uptimeMillis() + SUPPRESS_RECLAIM_MS
                ownership.forgetOwnership(taskId, packageName)
            }
            if (onVd && pkg != null) {
                // Bookkeeping + broadcast first so UI/Recent drop the row immediately.
                launch.onExplicitPackageClosed(pkg)
                val vacated = stacks.removeFromAll(pkg)
                ownership.releaseOwnershipIfUnused(pkg)
                VdDensityPin.clearPackageVirtualDisplay(pkg)
                ownership.untrackPackage(pkg)
                vacated.forEach { pane ->
                    mPanePackages[pane] = stacks.front(pane)
                }
                notifySplitStateChanged()
                val needsPromote = vacated.filter { pane ->
                    !stacks.front(pane).isNullOrBlank()
                }
                vacated.filter { stacks.front(it).isNullOrBlank() }.forEach { pane ->
                    input.displayIdFor(pane)?.let { ownership.removeChromeTasksOnDisplay(it) }
                }
                if (needsPromote.isNotEmpty()) {
                    ownership.promoteStackFronts(needsPromote, settleAv = false)
                }
                launch.schedulePersistSnapshot()
            }
            // ATMS removeTask can block seconds (QQ 音乐车机等) — keep off mHandler.
            Thread(
                {
                    try {
                        Instances.iActivityTaskManager.removeTask(taskId)
                    } catch (e: Throwable) {
                        log(TAG, "removeTask ATMS error taskId=$taskId:", e)
                    }
                },
                "AADisplay-close",
            ).apply { isDaemon = true; start() }
            true
        } catch (e: Throwable) {
            log(TAG, "removeTask error:", e)
            false
        }
    }

    /**
     * Swap user root-task stacks between PRIMARY and SECONDARY VirtualDisplays.
     * Surfaces / VD identities stay fixed; packages, ownership, focus, and ratio follow the apps.
     */
    fun swapPanes(): Boolean {
        return ownership.runOnHandlerBlocking(false) { swapPanesOnHandler() }
    }

    /** Divider / steering tap — jump ahead of ratio settle / reclaim on the handler. */
    fun swapPanesFromUser() {
        postUserAction {
            if (!swapPanesOnHandler()) {
                notifySwapFailed()
            }
        }
    }

    private fun notifySwapFailed() {
        try {
            context.sendBroadcast(Intent(AABroadcastConst.ACTION_SPLIT_SWAP_FAILED))
        } catch (e: Throwable) {
            logDebug(TAG, "notifySwapFailed: ${e.message}")
        }
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

        mHandler.removeCallbacks(launch.mDebouncedAtmsSettle)
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
        notifySplitStateChanged()
        launch.schedulePersistSnapshot()
        // VD nudge + per-task resize are heavy — defer so tap-swap returns immediately.
        mHandler.postDelayed({
            if (mIsDestroying) return@postDelayed
            if (primaryDisplayId != primaryDisplay || secondaryDisplayId != secondaryDisplay) {
                return@postDelayed
            }
            val sizes = vd.computePaneSizes()
            ownership.ensureTasksFillDisplay(
                primaryDisplay, sizes.primaryW, sizes.primaryH, "swap"
            )
            ownership.ensureTasksFillDisplay(
                secondaryDisplay, sizes.secondaryW, sizes.secondaryH, "swap"
            )
            ownership.snapshotUserRootTasks(primaryDisplay).lastOrNull()?.let {
                ownership.bringTaskToFront(it.taskId)
            }
            ownership.snapshotUserRootTasks(secondaryDisplay).lastOrNull()?.let {
                ownership.bringTaskToFront(it.taskId)
            }
            buriedPlayback.scheduleEnforceSingleSounder("swap")
        }, 80L)
        logDebug(
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
