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
import io.github.nitsuya.aa.display.ui.aa.split.SplitDisplayController
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.ui.window.DisplaySessionPolicy
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.util.AvMediaArbiter
import io.github.nitsuya.aa.display.util.CoolwalkRailStore
import io.github.nitsuya.aa.display.util.DisplayProfileSettle
import io.github.nitsuya.aa.display.xposed.cluster.ClusterLyricMirror
import io.github.nitsuya.aa.display.xposed.hook.PanePresentationGuard
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
            runCatching { ClusterLyricMirror.start(systemContext) }
                .onFailure { log(TAG, "ClusterLyricMirror.start failed", it) }
        }

        fun isAaVirtualDisplay(displayId: Int): Boolean {
            return mSplitController?.isAaVirtualDisplay(displayId) == true
        }

        /** Cheap gate for WM hooks: false when no AA pane VDs exist. */
        fun hasAaVirtualDisplays(): Boolean = mSplitController != null

        fun panePackageForDisplay(displayId: Int): String? {
            val controller = mSplitController ?: return null
            return when (displayId) {
                controller.primaryDisplayId -> controller.mPanePackages[SplitPane.PRIMARY]
                controller.secondaryDisplayId -> controller.mPanePackages[SplitPane.SECONDARY]
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }
        }

        fun avStackLayout(): AvMediaArbiter.StackLayout? {
            return mSplitController?.avStackLayout()
        }

        fun getDensityDpi(): Int {
            return mSplitController?.mDensityDpi ?: 0
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
                // Ratio is owned by divider drag / restore — do not push AA's echo back
                // unless it meaningfully differs (avoids resize thrash).
                val clamped = SplitPane.clampRatio(ratio)
                if (kotlin.math.abs(clamped - mRatio) >= 0.01f) {
                    setSplitRatio(clamped)
                }
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
        // Controller marshals onto its handler; keep off the Binder thread.
        runIO { mSplitController?.swapPanes() }
    }

    override fun onDestroyDisplay() {
        runMain {
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
        runIO { mSplitController?.startActivity(packageName, userId) }
    }

    override fun startActivityOnPane(packageName: String, userId: Int, pane: Int) {
        runIO { mSplitController?.startActivityOnPane(packageName, userId, pane) }
    }

    override fun moveTaskId(taskId: Int, isVirtualDisplay: Boolean) {
        // Controller marshals onto its handler; keep off the Binder thread.
        runIO { mSplitController?.moveTaskId(taskId, isVirtualDisplay) }
    }

    override fun moveTaskIdToPane(taskId: Int, pane: Int) {
        runIO { mSplitController?.moveTaskIdToPane(taskId, pane) }
    }

    override fun moveTaskToFront(taskId: Int) {
        runIO { mSplitController?.moveTaskToFront(taskId) }
    }

    @SuppressLint("MissingPermission")
    override fun removeTask(taskId: Int) {
        runIO { mSplitController?.removeTask(taskId) }
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
