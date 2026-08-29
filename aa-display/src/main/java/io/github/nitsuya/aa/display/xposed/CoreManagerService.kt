package io.github.nitsuya.aa.display.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextParams
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.model.RecentTask
import io.github.nitsuya.aa.display.ui.aa.split.HidShellGeometry
import io.github.nitsuya.aa.display.ui.aa.split.HidSplitLayout
import io.github.nitsuya.aa.display.ui.aa.split.SplitDisplayController
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.ui.window.DisplaySessionPolicy
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.util.AvMediaArbiter
import io.github.nitsuya.aa.display.util.CoolwalkRailStore
import io.github.nitsuya.aa.display.util.DisplayProfileSettle
import io.github.nitsuya.aa.display.xposed.cluster.ClusterLyricMirror
import android.graphics.Point
import android.view.Display
import android.view.KeyEvent
import io.github.nitsuya.aa.display.xposed.hook.PanePresentationGuard
import io.github.nitsuya.aa.display.xposed.hook.PhoneHidRedirect
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.CoolwalkRailMath
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.RailPhase
import io.github.nitsuya.aa.display.xposed.hook.VdImeDisplayPin
import io.github.nitsuya.aa.display.xposed.hook.VdOrientationFill
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import io.github.duzhaokun123.template.utils.runIO
import io.github.duzhaokun123.template.utils.runMain
import io.github.qauxv.ui.CommonContextWrapper

class CoreManagerService private constructor() : ICoreManager.Stub() {

    @Volatile
    private var coolwalkReconnectEpochMs = 0L

    companion object {
        const val TAG = "CoreManagerService"

        val instance: CoreManagerService by lazy {
            CoreManagerService().apply {
                log(TAG, "AADisplay service initialized")
            }
        }

        @SuppressLint("StaticFieldLeak")
        private lateinit var systemContextHost: Context
        val hasSystemContext: Boolean
            get() = ::systemContextHost.isInitialized
        var systemContext: Context
            get() = systemContextHost
            set(value) {
                if (value.params == null) {
                    log(TAG, "SystemContext.params is null; using empty ContextParams")
                }
                systemContextHost = value.createContext(value.params ?: ContextParams.Builder().build())
            }

        private var mSessionPolicy: DisplaySessionPolicy? = null
        private var mSplitController: SplitDisplayController? = null
        private var mDisplayCreateInProgress = false

        private data class DisplayProfile(
            val width: Int,
            val height: Int,
            val densityDpi: Int
        ) {
            val isLandscape: Boolean
                get() = width >= height
        }

        private var mLockedDisplayProfile: DisplayProfile? = null
        /** Soft-reconnect: rail VD may appear shortly after first create call. */
        private const val RAIL_SETTLE_RETRY_MS = 450L
        private val mMainHandler = Handler(Looper.getMainLooper())
        private val mRailSettleRetryRunnable = Runnable { retryRailAwareSettle() }

        private fun sanitizeDisplayProfile(width: Int, height: Int, densityDpi: Int): DisplayProfile {
            return DisplayProfile(
                width = width.coerceAtLeast(1),
                height = height.coerceAtLeast(1),
                densityDpi = densityDpi.coerceAtLeast(1)
            )
        }

        private fun cancelRailSettleRetry() {
            mMainHandler.removeCallbacks(mRailSettleRetryRunnable)
        }

        private fun scheduleRailSettleRetry() {
            cancelRailSettleRetry()
            mMainHandler.postDelayed(mRailSettleRetryRunnable, RAIL_SETTLE_RETRY_MS)
        }

        /**
         * After soft reconnect, GhFacetBar may appear (or be starved) a beat after the
         * first create. Re-settle so we neither stay at full-HU with a live 80px strip
         * nor stay at HU−rail after the strip is gone.
         */
        private fun retryRailAwareSettle() {
            val current = mLockedDisplayProfile ?: return
            if (mSplitController == null) return
            if (!hasSystemContext) return
            val railW = resolveRailWidthPx()
            val fullW = resolveObservedFullHuWidthPx(current.width, current.height)
            val settled = DisplayProfileSettle.settle(
                DisplayProfileSettle.Size(current.width, current.height, current.densityDpi),
                railWidthPx = railW,
                fullHuWidthPx = fullW,
            )
            val next = sanitizeDisplayProfile(settled.width, settled.height, settled.densityDpi)
            if (next == current) return
            mLockedDisplayProfile = next
            logDebug(
                TAG,
                "displayProfile relocked(rail-settle): ${current.width}*${current.height} -> " +
                    "${next.width}*${next.height} rail=$railW full=$fullW"
            )
            if (next.width > current.width &&
                DisplayProfileSettle.isContentSlotVsFull(current.width, next.width)
            ) {
                instance.notifyCoolwalkFullBleed()
            }
            mSplitController?.onReconnected(next.width, next.height, next.densityDpi)
        }

        private fun resolveRailWidthPx(): Int {
            if (!hasSystemContext) return 0
            val liveRail = DisplayProfileSettle.observeLiveRailWidthPx(systemContext)
            // Compositor FacetBar strip is the only profile input — never touchRailWidthPx
            // or cached FullBleed phase (stale after reconnect blacks the left gutter).
            return if (liveRail <= 1) 0 else liveRail
        }

        private fun resolveObservedFullHuWidthPx(reportedWidth: Int, reportedHeight: Int): Int {
            val snap = CoolwalkRailStore.effectiveSnapshot(
                if (hasSystemContext) systemContext.contentResolver else null,
            )
            val observed = DisplayProfileSettle.observeFullHuWidthPx(
                systemContext,
                reportedWidth.coerceAtLeast(1),
                reportedHeight,
            )
            val measured = observed.coerceAtLeast(reportedWidth)
            return CoolwalkRailMath.pickConnectionFullHuWidth(
                snap.fullHuWidthPx,
                snap.layoutHeightPx,
                measured,
                reportedHeight,
                snap.touchRailWidthPx,
            )
        }

        /**
         * Single settle rule (see [DisplayProfileSettle]): live rail strip → HU−rail;
         * otherwise full HU. Replaces the old grow-immediate / shrink-confirm tug-of-war.
         */
        private fun resolveDisplayProfile(
            width: Int,
            height: Int,
            densityDpi: Int,
            newSession: Boolean
        ): DisplayProfile {
            val reported = sanitizeDisplayProfile(width, height, densityDpi)
            val railW = resolveRailWidthPx()
            val fullW = if (hasSystemContext) {
                resolveObservedFullHuWidthPx(reported.width, reported.height)
            } else {
                reported.width
            }
            val settledSize = DisplayProfileSettle.settle(
                DisplayProfileSettle.Size(reported.width, reported.height, reported.densityDpi),
                railWidthPx = railW,
                fullHuWidthPx = fullW,
            )
            val candidate = sanitizeDisplayProfile(
                settledSize.width,
                settledSize.height,
                settledSize.densityDpi,
            )
            val current = mLockedDisplayProfile
            if (current == null) {
                mLockedDisplayProfile = candidate
                logDebug(
                    TAG,
                    "displayProfile locked: ${candidate.width}*${candidate.height},${candidate.densityDpi} " +
                        "(reported=${reported.width} rail=$railW full=$fullW)"
                )
                if (!newSession) scheduleRailSettleRetry()
                return candidate
            }
            if (newSession) {
                cancelRailSettleRetry()
                if (current != candidate) {
                    mLockedDisplayProfile = candidate
                    logDebug(
                        TAG,
                        "displayProfile relocked(new-session): ${current.width}*${current.height} -> " +
                            "${candidate.width}*${candidate.height} rail=$railW full=$fullW"
                    )
                }
                return mLockedDisplayProfile!!
            }
            if (current.isLandscape != candidate.isLandscape) {
                cancelRailSettleRetry()
                mLockedDisplayProfile = candidate
                logDebug(
                    TAG,
                    "displayProfile relocked(orientation): ${current.width}*${current.height} -> " +
                        "${candidate.width}*${candidate.height}"
                )
                return candidate
            }
            if (current != candidate) {
                mLockedDisplayProfile = candidate
                logDebug(
                    TAG,
                    "displayProfile relocked(settle): ${current.width}*${current.height} -> " +
                        "${candidate.width}*${candidate.height} " +
                        "reported=${reported.width} rail=$railW full=$fullW"
                )
                if (candidate.width > current.width &&
                    DisplayProfileSettle.isContentSlotVsFull(current.width, candidate.width)
                ) {
                    instance.notifyCoolwalkFullBleed()
                }
            }
            // Rail / starve often lands after the first soft-reconnect create; one retry.
            scheduleRailSettleRetry()
            return mLockedDisplayProfile!!
        }

        private fun clearDisplayProfileLock() {
            mLockedDisplayProfile?.also {
                logDebug(TAG, "displayProfile cleared: ${it.width}*${it.height},${it.densityDpi}")
            }
            cancelRailSettleRetry()
            mLockedDisplayProfile = null
        }

        fun systemReady() {
            if (!hasSystemContext) {
                log(TAG, "systemReady skipped: systemContext not initialized")
                return
            }
            Instances.init(systemContext)
            // Isolate each install: one OEM-missing method must not skip the rest.
            runCatching { PanePresentationGuard.ensureHooked() }
                .onFailure { log(TAG, "PanePresentationGuard.ensureHooked failed", it) }
            runCatching { VdImeDisplayPin.ensureHooked() }
                .onFailure { log(TAG, "VdImeDisplayPin.ensureHooked failed", it) }
            runCatching { VdOrientationFill.ensureHooked() }
                .onFailure { log(TAG, "VdOrientationFill.ensureHooked failed", it) }
            runCatching { PhoneHidRedirect.ensureHooked() }
                .onFailure { log(TAG, "PhoneHidRedirect.ensureHooked failed", it) }
            runCatching { registerAaUiBroadcastReceivers() }
                .onFailure { log(TAG, "registerAaUiBroadcastReceivers failed", it) }
            runCatching { ClusterLyricMirror.start(systemContext) }
                .onFailure { log(TAG, "ClusterLyricMirror.start failed", it) }
        }

        private fun registerAaUiBroadcastReceivers() {
            if (aaUiBroadcastReceiver != null || !hasSystemContext) return
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: android.content.Intent?) {
                    when (intent?.action) {
                        AABroadcastConst.ACTION_AA_UI_RAIL_CONSUME -> {
                            setAaUiShellCapture(
                                intent.getBooleanExtra(
                                    AABroadcastConst.EXTRA_AA_UI_RAIL_CONSUME,
                                    false,
                                ),
                            )
                        }
                        AABroadcastConst.ACTION_HID_SHELL_GEOMETRY -> {
                            val w = intent.getIntExtra(AABroadcastConst.EXTRA_SHELL_PARENT_W, 0)
                            val h = intent.getIntExtra(AABroadcastConst.EXTRA_SHELL_PARENT_H, 0)
                            if (w <= 0 || h <= 0) return
                            mSplitController?.updateHidShellGeometry(
                                HidShellGeometry(
                                    parentW = w,
                                    parentH = h,
                                    sideBySide = intent.getBooleanExtra(
                                        AABroadcastConst.EXTRA_SHELL_SIDEBYSIDE,
                                        true,
                                    ),
                                    fullscreenPane = intent.getIntExtra(
                                        AABroadcastConst.EXTRA_FULLSCREEN_PANE,
                                        SplitPane.FULLSCREEN_NONE,
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
            try {
                systemContext.registerReceiver(
                    receiver,
                    android.content.IntentFilter().apply {
                        addAction(AABroadcastConst.ACTION_AA_UI_RAIL_CONSUME)
                        addAction(AABroadcastConst.ACTION_HID_SHELL_GEOMETRY)
                    },
                    Context.RECEIVER_EXPORTED,
                )
                aaUiBroadcastReceiver = receiver
            } catch (e: Throwable) {
                log(TAG, "registerAaUiBroadcastReceivers failed", e)
            }
        }

        fun isAaVirtualDisplay(displayId: Int): Boolean {
            return mSplitController?.isAaVirtualDisplay(displayId) == true
        }

        /** Cheap gate for WM hooks: false when no AA pane VDs exist. */
        fun hasAaVirtualDisplays(): Boolean = mSplitController != null

        /** AA UI attached (not Delay Destroy). Used by [PhoneHidRedirect]. */
        fun isAaSessionLive(): Boolean =
            mSplitController != null && mSessionPolicy?.isAaSessionLive == true

        /**
         * True while app picker / Recents covers the shell — phone BT mouse must
         * inject via [touchAaDisplay], not pane VDs. Driven by [ACTION_AA_UI_RAIL_CONSUME].
         */
        @Volatile
        var aaUiShellCapture: Boolean = false
            private set

        fun clearAaUiShellCapture() {
            setAaUiShellCapture(false)
        }

        fun setAaUiShellCapture(capture: Boolean) {
            if (aaUiShellCapture == capture) return
            aaUiShellCapture = capture
            logDebug(TAG, "aaUiShellCapture=$capture")
            PhoneHidRedirect.onShellCaptureChanged(capture)
        }

        private var aaUiBroadcastReceiver: android.content.BroadcastReceiver? = null

        fun hidAaUiDisplayId(): Int =
            mSplitController?.aaUiDisplayId() ?: Display.INVALID_DISPLAY

        fun hidTargetDisplayId(): Int =
            mSplitController?.resolveHidInjectionDisplayId() ?: Display.INVALID_DISPLAY

        fun hidPrimaryDisplayId(): Int =
            mSplitController?.primaryDisplayId?.takeIf { it != Display.INVALID_DISPLAY }
                ?: Display.INVALID_DISPLAY

        fun hidSecondaryDisplayId(): Int =
            mSplitController?.secondaryDisplayId?.takeIf { it != Display.INVALID_DISPLAY }
                ?: Display.INVALID_DISPLAY

        fun hidSplitLayout(): HidSplitLayout? = mSplitController?.hidSplitLayout()

        fun focusHidPane(pane: Int): Boolean {
            val c = mSplitController ?: return false
            if (!SplitPane.isValid(pane)) return false
            c.setFocusedPane(pane)
            return true
        }

        fun injectHidTouchOnPane(
            pane: Int,
            action: Int,
            x: Float,
            y: Float,
            downTime: Long,
            eventTime: Long,
        ): Boolean {
            return try {
                if (action == MotionEvent.ACTION_DOWN) {
                    mSessionPolicy?.onVirtualDisplayUserInteraction()
                }
                mSplitController?.onHidTouchOnPane(pane, action, x, y, downTime, eventTime) == true
            } catch (e: Throwable) {
                logDebug(TAG, "injectHidTouchOnPane: ${e.message}")
                false
            }
        }

        fun injectHidScrollOnPane(
            pane: Int,
            x: Float,
            y: Float,
            vScroll: Float,
            hScroll: Float,
        ): Boolean {
            return try {
                mSessionPolicy?.onVirtualDisplayUserInteraction()
                mSplitController?.onHidScrollOnPane(pane, x, y, vScroll, hScroll) == true
            } catch (e: Throwable) {
                logDebug(TAG, "injectHidScrollOnPane: ${e.message}")
                false
            }
        }

        fun injectHidAaUiTouch(
            action: Int,
            x: Float,
            y: Float,
            downTime: Long,
            eventTime: Long,
        ): Boolean {
            return try {
                if (action == MotionEvent.ACTION_DOWN) {
                    mSessionPolicy?.onVirtualDisplayUserInteraction()
                }
                val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
                    source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                }
                try {
                    mSplitController?.onTouchAaDisplay(event)
                    true
                } finally {
                    event.recycle()
                }
            } catch (e: Throwable) {
                logDebug(TAG, "injectHidAaUiTouch: ${e.message}")
                false
            }
        }

        /** Adjust split ratio by [delta] (−0.05 / +0.05) on the **shell**, then settle VD. */
        fun nudgeHidSplitRatio(delta: Float): Boolean {
            return try {
                val c = mSplitController ?: return false
                if (SplitPane.isFullscreenPane(c.mFullscreenPane)) return false
                // Accumulate while UI may still be applying the previous broadcast.
                val base = pendingHidShellRatio ?: c.mRatio
                val next = SplitPane.clampRatio(base + delta)
                if (kotlin.math.abs(next - base) < 0.001f) return true
                pendingHidShellRatio = next
                // Do not call setSplitRatio here — that resizes VDs while TextureViews lag
                // (shell ratio stays old). Ask AaMainFragment to layout + settle.
                if (!hasSystemContext) return false
                systemContext.sendBroadcast(
                    android.content.Intent(AABroadcastConst.ACTION_HID_APPLY_SPLIT_RATIO)
                        .putExtra(AABroadcastConst.EXTRA_RATIO, next)
                )
                mSessionPolicy?.onVirtualDisplayUserInteraction()
                true
            } catch (e: Throwable) {
                logDebug(TAG, "nudgeHidSplitRatio: ${e.message}")
                false
            }
        }

        /** Cleared when UI settles [ACTION_HID_APPLY_SPLIT_RATIO] via setSplitRatio. */
        @Volatile
        private var pendingHidShellRatio: Float? = null

        fun clearPendingHidShellRatio() {
            pendingHidShellRatio = null
        }

        fun showHidRecentTask(): Boolean {
            return try {
                if (!hasSystemContext) return false
                // Shell overlay must steal HID before the UI broadcast round-trip.
                setAaUiShellCapture(true)
                PhoneHidRedirect.snapShellToRecentColumn()
                systemContext.sendBroadcast(
                    android.content.Intent(AABroadcastConst.ACTION_SHOW_RECENT_TASK)
                )
                mSessionPolicy?.onVirtualDisplayUserInteraction()
                true
            } catch (e: Throwable) {
                logDebug(TAG, "showHidRecentTask: ${e.message}")
                false
            }
        }

        fun swapHidPanes(): Boolean {
            return try {
                if (!hasSystemContext) return false
                // Same path as divider tap — optimistic shell layout in AaMainFragment.
                systemContext.sendBroadcast(
                    android.content.Intent(AABroadcastConst.ACTION_SPLIT_SWAP),
                )
                mSessionPolicy?.onVirtualDisplayUserInteraction()
                true
            } catch (e: Throwable) {
                logDebug(TAG, "swapHidPanes: ${e.message}")
                false
            }
        }

        /** Phone BT mouse — shell-drawn cursor in [HidCursorOverlayView] (presentation coords). */
        fun notifyHidCursorOverlay(visible: Boolean, x: Float, y: Float) {
            if (!hasSystemContext) return
            try {
                systemContext.sendBroadcast(
                    android.content.Intent(AABroadcastConst.ACTION_HID_CURSOR).apply {
                        putExtra(AABroadcastConst.EXTRA_CURSOR_VISIBLE, visible)
                        putExtra(AABroadcastConst.EXTRA_CURSOR_X, x)
                        putExtra(AABroadcastConst.EXTRA_CURSOR_Y, y)
                    },
                )
            } catch (e: Throwable) {
                logDebug(TAG, "notifyHidCursorOverlay: ${e.message}")
            }
        }

        fun displaySizeFor(displayId: Int): Point? {
            if (displayId < 0) return null
            return runCatching {
                val display = Instances.displayManager.getDisplay(displayId)
                    ?: return@runCatching null
                val point = Point()
                @Suppress("DEPRECATION")
                display.getRealSize(point)
                if (point.x <= 0 || point.y <= 0) {
                    @Suppress("DEPRECATION")
                    display.getSize(point)
                }
                point.takeIf { it.x > 0 && it.y > 0 }
            }.getOrNull()
        }

        fun injectHidKeyEvent(event: KeyEvent): Boolean {
            return try {
                mSessionPolicy?.onVirtualDisplayUserInteraction()
                mSplitController?.onHidKeyEvent(event) == true
            } catch (e: Throwable) {
                logDebug(TAG, "injectHidKeyEvent: ${e.message}")
                false
            }
        }

        fun injectHidAaUiKeyEvent(event: KeyEvent): Boolean {
            return try {
                mSessionPolicy?.onVirtualDisplayUserInteraction()
                mSplitController?.onHidKeyEventToAaDisplay(event) == true
            } catch (e: Throwable) {
                logDebug(TAG, "injectHidAaUiKeyEvent: ${e.message}")
                false
            }
        }

        fun panePackageForDisplay(displayId: Int): String? {
            val controller = mSplitController ?: return null
            val pane = when (displayId) {
                controller.primaryDisplayId -> SplitPane.PRIMARY
                controller.secondaryDisplayId -> SplitPane.SECONDARY
                else -> return null
            }
            return controller.getPanePackage(pane)
        }

        fun avStackLayout(): AvMediaArbiter.StackLayout? {
            return mSplitController?.avStackLayout()
        }

        fun getDensityDpi(): Int {
            return mSplitController?.mDensityDpi ?: 0
        }

        /** Live AA pane buffer size (px) for [displayId]; null when not a split VD. */
        fun aaPaneSizePx(displayId: Int): Pair<Int, Int>? {
            val controller = mSplitController ?: return null
            val sizes = controller.vd.computePaneSizes()
            return when (displayId) {
                controller.primaryDisplayId -> sizes.primaryW to sizes.primaryH
                controller.secondaryDisplayId -> sizes.secondaryW to sizes.secondaryH
                else -> null
            }
        }
    }

    override fun getVersionName(): String = BuildConfig.VERSION_NAME

    override fun getBuildTime(): Long = BuildConfig.BUILD_TIME

    override fun onCreateSplitDisplay(
        width: Int,
        height: Int,
        densityDpi: Int,
        ratio: Float,
        primarySurface: Surface?,
        secondarySurface: Surface?,
        listener: IVirtualDisplayCreatedListener
    ) {
        runMain {
            logDebug(
                TAG,
                "onCreateSplitDisplay: ${width}x$height,$densityDpi ratio=$ratio " +
                    "existing=${mSplitController != null}"
            )
            if (!hasSystemContext) {
                log(TAG, "onCreateSplitDisplay aborted: systemContext not initialized")
                return@runMain
            }
            val profile = resolveDisplayProfile(
                width = width,
                height = height,
                densityDpi = densityDpi,
                newSession = mSplitController == null
            )
            mSplitController?.apply {
                // Soft reconnect: always cancel Delay Destroy and rebind surfaces/policies.
                mSessionPolicy?.onResume()
                ClusterLyricMirror.onAaConnected()
                setPaneSurface(SplitPane.PRIMARY, primarySurface)
                setPaneSurface(SplitPane.SECONDARY, secondarySurface)
                // Always kick resize/policies/ensure after surface rebind (null→live).
                onReconnected(profile.width, profile.height, profile.densityDpi)
                // Exit ColorFade if panes went OFF while AA was disconnected.
                mSessionPolicy?.keepVirtualDisplayAwake("soft-reconnect")
                // Controller retains live ratio across Delay Destroy; AA echo may lag
                // LastSplitStore — UI reconciles via SPLIT_STATE_CHANGED.
                listener.onAvailableDisplay(primaryDisplayId, false)
                return@runMain
            }
            if (mDisplayCreateInProgress) {
                logDebug(TAG, "onCreateSplitDisplay ignored: create already in progress")
                return@runMain
            }
            mDisplayCreateInProgress = true
            SplitDisplayController(systemContext) {
                try {
                    val controller = this
                    mSplitController = controller
                    onConnected(
                        profile.width,
                        profile.height,
                        profile.densityDpi,
                        SplitPane.clampRatio(ratio),
                        primarySurface,
                        secondarySurface,
                    ) { displayId ->
                        listener.onAvailableDisplay(displayId, true)
                        ClusterLyricMirror.onAaConnected()
                        // Session policy (delay-destroy / keep-awake) after first frame callback.
                        runMain {
                            if (mSplitController !== controller) return@runMain
                            mSessionPolicy?.onDestroyPromptly()
                            mSessionPolicy = DisplaySessionPolicy(
                                CommonContextWrapper.createModuleContext(systemContext),
                                controller,
                            )
                            runCatching { PhoneHidRedirect.onSessionLiveChanged(true) }
                                .onFailure { log(TAG, "PhoneHidRedirect session-live failed", it) }
                        }
                    }
                } finally {
                    mDisplayCreateInProgress = false
                }
            }
        }
    }

    override fun setPaneSurface(pane: Int, surface: Surface?) {
        runMain {
            mSplitController?.setPaneSurface(pane, surface)
        }
    }

    override fun setSplitRatio(ratio: Float) {
        clearPendingHidShellRatio()
        runMain {
            mSplitController?.setSplitRatio(ratio)
        }
    }

    override fun getSplitRatio(): Float {
        return mSplitController?.mRatio ?: SplitPane.DEFAULT_RATIO
    }

    override fun setSplitFullscreen(pane: Int) {
        runMain {
            mSplitController?.setSplitFullscreen(pane)
        }
    }

    override fun getSplitFullscreenPane(): Int {
        return mSplitController?.mFullscreenPane ?: SplitPane.FULLSCREEN_NONE
    }

    override fun getPanePackage(pane: Int): String? {
        return mSplitController?.getPanePackage(pane)
    }

    override fun setFocusedPane(pane: Int) {
        mSplitController?.setFocusedPane(pane)
    }

    override fun getFocusedPane(): Int {
        return mSplitController?.mFocusedPane ?: SplitPane.PRIMARY
    }

    override fun swapSplitPanes() {
        mSplitController?.swapPanesFromUser()
    }

    override fun onDestroyDisplay() {
        runMain {
            mSplitController?.launch?.flushPersistOnAaDisconnect()
            val finishTeardown = {
                mSplitController?.onDestroy()
                mSessionPolicy = null
                mSplitController = null
                mDisplayCreateInProgress = false
                clearDisplayProfileLock()
                CoolwalkRailStore.clear(
                    if (hasSystemContext) systemContext.contentResolver else null,
                )
            }
            // Session policy is created async after the create callback; destroy before that
            // must still tear down the controller and clear the profile lock.
            val policy = mSessionPolicy
            if (policy != null) {
                policy.onDestroy(finishTeardown)
            } else {
                finishTeardown()
            }
        }
    }

    override fun startActivity(packageName: String, userId: Int) {
        mSplitController?.startActivityAsync(packageName, userId)
    }

    override fun startActivityOnPane(packageName: String, userId: Int, pane: Int) {
        mSplitController?.startActivityOnPaneAsync(packageName, userId, pane)
    }

    override fun startActivityOnPaneForUser(packageName: String, userId: Int, pane: Int): Boolean {
        val controller = mSplitController ?: return false
        return controller.startActivityOnPaneForUser(packageName, userId, pane)
    }

    override fun moveTaskId(taskId: Int, isVirtualDisplay: Boolean) {
        mSplitController?.moveTaskIdAsync(taskId, isVirtualDisplay)
    }

    override fun moveTaskIdToPane(taskId: Int, pane: Int) {
        mSplitController?.moveTaskIdToPaneAsync(taskId, pane)
    }

    override fun moveTaskToFront(taskId: Int) {
        mSplitController?.moveTaskToFrontAsync(taskId)
    }

    @SuppressLint("MissingPermission")
    override fun removeTask(taskId: Int) {
        mSplitController?.removeTaskAsync(taskId)
    }

    override fun reorderPaneStack(pane: Int, packagesTopToBottom: Array<out String>?): Boolean {
        val pkgs = packagesTopToBottom ?: return false
        val controller = mSplitController ?: return false
        // Synchronous — Recent drag must not reload before stack order is persisted.
        return controller.reorderPaneStack(pane, pkgs)
    }

    override fun pressKey(action: Int) {
        runIO {
            noteUserInteraction()
            mSplitController?.onPressKey(action)
        }
    }

    override fun touchPane(pane: Int, event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            noteUserInteractionAsync()
            mSplitController?.setFocusedPane(pane)
        }
        mSplitController?.onTouchPane(pane, event)
    }

    override fun touchPrimaryPane(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            noteUserInteractionAsync()
        }
        mSplitController?.onTouchPrimaryPane(event)
    }

    override fun touchAaDisplay(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            noteUserInteractionAsync()
        }
        mSplitController?.onTouchAaDisplay(event)
    }

    override fun reportAaUiDisplayId(displayId: Int) {
        mSplitController?.setAaUiDisplayId(displayId)
        mSessionPolicy?.onAaUiDisplayIdChanged(displayId)
    }

    override fun hideIme() {
        runIO {
            noteUserInteraction()
            mSplitController?.hideIme()
        }
    }

    override fun getImePane(): Int {
        return mSplitController?.getImePane() ?: SplitPane.FULLSCREEN_NONE
    }

    override fun reportCoolwalkRailSnapshot(
        phase: Int,
        touchRailWidthPx: Int,
        fullHuWidthPx: Int,
        facetDisplayId: Int,
    ) {
        if (phase == RailPhase.ReconnectSettling.code) {
            coolwalkReconnectEpochMs = android.os.SystemClock.uptimeMillis()
        }
        val snapshot = io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.RailSnapshot(
            phase = RailPhase.fromCode(phase),
            effectiveRailWidthPx = 0,
            touchRailWidthPx = touchRailWidthPx,
            fullHuWidthPx = fullHuWidthPx,
            facetDisplayId = facetDisplayId,
            updatedUptimeMs = android.os.SystemClock.uptimeMillis(),
            lastEvent = "client-report",
        )
        val prevFull = CoolwalkRailStore.serverSnapshot.fullHuWidthPx
        val cr = if (hasSystemContext) systemContext.contentResolver else null
        CoolwalkRailStore.rememberFromReport(snapshot, cr)
        // Publish live client report only; session merge on read respects reconnect defer gate.
        val published = snapshot
        CoolwalkRailStore.publishServer(published)
        if (hasSystemContext) {
            CoolwalkRailStore.write(systemContext.contentResolver, published)
        }
        logDebug(
            TAG,
            "CoolwalkRail snapshot phase=${snapshot.phase} touch=$touchRailWidthPx full=$fullHuWidthPx facet=$facetDisplayId",
        )
        if (fullHuWidthPx > prevFull || snapshot.phase == RailPhase.FullBleed) {
            scheduleRailSettleRetry()
        }
    }

    override fun getCoolwalkRailSnapshot(): IntArray {
        val s = CoolwalkRailStore.effectiveSnapshot(if (hasSystemContext) systemContext.contentResolver else null)
        return intArrayOf(s.phase.code, s.touchRailWidthPx, s.fullHuWidthPx, s.facetDisplayId)
    }

    override fun getCoolwalkReconnectEpochMs(): Long = coolwalkReconnectEpochMs

    override fun notifyCoolwalkFullBleed() {
        mSessionPolicy?.sendCoolwalkFullBleedBroadcast()
            ?: sendAaDisplayBroadcastFromSystem(AABroadcastConst.ACTION_COOLWALK_FULL_BLEED)
    }

    private fun sendAaDisplayBroadcastFromSystem(action: String) {
        if (!hasSystemContext) return
        try {
            systemContext.sendBroadcast(
                android.content.Intent(action).setPackage(BuildConfig.APPLICATION_ID),
            )
        } catch (e: Throwable) {
            log(TAG, "sendAaDisplayBroadcastFromSystem failed action=$action", e)
        }
    }

    override fun getRecentTask(): RecentTask {
        // Client already loads on IO ([AaRecentTaskFragment]); avoid runBlocking on the
        // Binder thread which only adds a dispatcher hop while still blocking the caller.
        return mSplitController?.getRecentTask()
            ?: RecentTask(emptyList(), emptyList(), emptyList())
    }

    private fun noteUserInteraction() {
        mSessionPolicy?.onVirtualDisplayUserInteraction()
    }

    private fun noteUserInteractionAsync() {
        runIO { noteUserInteraction() }
    }
}


