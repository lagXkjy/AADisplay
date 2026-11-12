package io.github.nitsuya.aa.display.xposed

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.model.RecentTask
import io.github.nitsuya.aa.display.ui.aa.split.HidSplitLayout
import io.github.nitsuya.aa.display.ui.aa.split.SplitDisplayController
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.ui.window.DisplaySessionPolicy
import io.github.nitsuya.aa.display.util.AvMediaArbiter
import io.github.nitsuya.aa.display.util.DisplayDpiStore
import io.github.nitsuya.aa.display.util.EgressChannelMirror
import io.github.nitsuya.aa.display.xposed.cluster.ClusterArtStore
import io.github.nitsuya.aa.display.xposed.cluster.ClusterLyricMirror
import android.graphics.Point
import android.view.Display
import android.view.KeyEvent
import io.github.nitsuya.aa.display.xposed.carplay.CarPlayAudioCapture
import io.github.nitsuya.aa.display.xposed.carplay.CarPlayBootArm
import io.github.nitsuya.aa.display.xposed.carplay.CarPlayProcessBoost
import io.github.nitsuya.aa.display.xposed.carplay.ThreadRealtimeBoost
import io.github.nitsuya.aa.display.xposed.hook.PanePresentationGuard
import io.github.nitsuya.aa.display.xposed.hook.PaneDisplayGroupForce
import io.github.nitsuya.aa.display.xposed.hook.PhoneHidRedirect
import io.github.nitsuya.aa.display.xposed.hook.VdImeDisplayPin
import io.github.nitsuya.aa.display.xposed.hook.VdMetricsMirror
import io.github.nitsuya.aa.display.xposed.hook.VdOrientationFill
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug

import io.github.nitsuya.aa.display.xposed.util.CommonContextWrapper
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class CoreManagerService private constructor() : ICoreManager.Stub() {

    companion object {
        const val TAG = "CoreManagerService"
        private const val ACCESS_REJECTED = 2
        private const val CONNECTION_POLICY_FORBIDDEN = 0
        private const val PROFILE_PBAP = 6
        private const val PROFILE_MAP = 9
        private const val ACTION_CONNECTION_ACCESS_REQUEST =
            "android.bluetooth.device.action.CONNECTION_ACCESS_REQUEST"
        private const val EXTRA_ACCESS_REQUEST_TYPE =
            "android.bluetooth.device.extra.ACCESS_REQUEST_TYPE"
        private const val REQUEST_PHONEBOOK = 2
        private const val REQUEST_MESSAGE = 3
        private const val REQUEST_SIM = 4
        private val headUnitAddresses = ConcurrentHashMap.newKeySet<String>()
        private var headUnitAccessReceiver: BroadcastReceiver? = null

        @Volatile
        var carPlaySessionLive: Boolean = false
            private set

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

        // Written on the system_server main handler (create/destroy), read from Binder
        // threads (getSplitRatio / touchPane / hidCursorOverlaySnapshot / gates) — volatile
        // so a freshly created / torn-down controller is visible to those readers.
        @Volatile
        private var mSessionPolicy: DisplaySessionPolicy? = null
        @Volatile
        private var mSplitController: SplitDisplayController? = null
        private var mDisplayCreateInProgress = false

        private data class DisplayProfile(
            val width: Int,
            val height: Int,
            val densityDpi: Int
        )

        private var mLockedDisplayProfile: DisplayProfile? = null
        private val mMainHandler = Handler(Looper.getMainLooper())

        private fun persistVdDensityDpi(dpi: Int): Boolean {
            if (!hasSystemContext) {
                log(TAG, "setVdDensityDpi aborted: systemContext not initialized")
                return false
            }
            val normalized = DisplayDpiStore.normalize(dpi)
            val ok = DisplayDpiStore.save(normalized, systemContext.contentResolver)
            if (!ok) {
                log(TAG, "setVdDensityDpi persist failed dpi=$normalized")
                return false
            }
            return true
        }

        private fun sanitizeDisplayProfile(width: Int, height: Int, densityDpi: Int): DisplayProfile {
            return DisplayProfile(
                width = width.coerceAtLeast(1),
                height = height.coerceAtLeast(1),
                densityDpi = densityDpi.coerceAtLeast(1)
            )
        }

        private fun contentResolverOrNull() =
            if (hasSystemContext) systemContext.contentResolver else null

        /** Prefer client-reported host density (AAP / CarPlay encode canvas). */
        private fun resolveReportedHostDensityDpi(clientReported: Int): Int {
            return clientReported
        }

        private fun resolveEffectiveDensityDpi(reportedHostDpi: Int): Int {
            val override = DisplayDpiStore.loadConfigured(contentResolverOrNull())
            if (override > 0) return override
            return reportedHostDpi.coerceAtLeast(1)
        }

        /** Apply configured override / latest host dpi to a live AAP / CarPlay session. */
        private fun applyDensityOverrideLive() {
            val controller = mSplitController ?: return
            val profile = mLockedDisplayProfile ?: return
            val configured = DisplayDpiStore.loadConfigured(contentResolverOrNull())
            val newDpi = if (configured > 0) {
                configured
            } else {
                profile.densityDpi.coerceAtLeast(1)
            }
            if (newDpi == profile.densityDpi && controller.mDensityDpi == newDpi) return
            mLockedDisplayProfile = profile.copy(densityDpi = newDpi)
            logDebug(
                TAG,
                "vdDensityDpi live apply: ${profile.densityDpi} -> $newDpi " +
                    "configured=$configured carPlay=$carPlaySessionLive " +
                    "controller=${controller.mDensityDpi}",
            )
            controller.onReconnected(profile.width, profile.height, newDpi)
        }

        /**
         * Lock the client-reported encode / HU size. Coolwalk FacetBar content-slot
         * settle is gone — AAP and CarPlay self-render the full canvas.
         */
        private fun resolveDisplayProfile(
            width: Int,
            height: Int,
            densityDpi: Int,
            @Suppress("UNUSED_PARAMETER") newSession: Boolean,
        ): DisplayProfile {
            val reported = sanitizeDisplayProfile(width, height, densityDpi)
            val current = mLockedDisplayProfile
            if (current != reported) {
                mLockedDisplayProfile = reported
                logDebug(
                    TAG,
                    "displayProfile locked: ${reported.width}*${reported.height}," +
                        "${reported.densityDpi}" +
                        if (current != null) {
                            " (was ${current.width}*${current.height})"
                        } else {
                            ""
                        },
                )
            }
            return reported
        }

        private fun clearDisplayProfileLock() {
            mLockedDisplayProfile?.also {
                logDebug(TAG, "displayProfile cleared: ${it.width}*${it.height},${it.densityDpi}")
            }
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
            runCatching { VdMetricsMirror.ensureHooked() }
                .onFailure { log(TAG, "VdMetricsMirror.ensureHooked failed", it) }
            runCatching { PhoneHidRedirect.ensureHooked() }
                .onFailure { log(TAG, "PhoneHidRedirect.ensureHooked failed", it) }
            runCatching { PaneDisplayGroupForce.ensureResolved() }
                .onFailure { log(TAG, "PaneDisplayGroupForce.ensureResolved failed", it) }
            runCatching { ClusterLyricMirror.start(systemContext) }
                .onFailure { log(TAG, "ClusterLyricMirror.start failed", it) }
            runCatching { CarPlayAudioCapture.install(systemContext) }
                .onFailure { log(TAG, "CarPlayAudioCapture.install failed", it) }
            runCatching { CarPlayProcessBoost.install(systemContext) }
                .onFailure { log(TAG, "CarPlayProcessBoost.install failed", it) }
            runCatching { ThreadRealtimeBoost.install() }
                .onFailure { log(TAG, "ThreadRealtimeBoost.install failed", it) }
            runCatching { CarPlayBootArm.install(systemContext) }
                .onFailure { log(TAG, "CarPlayBootArm.install failed", it) }
        }

        fun isAaVirtualDisplay(displayId: Int): Boolean {
            return mSplitController?.isAaVirtualDisplay(displayId) == true
        }

        /** Cheap gate for WM hooks: false when no AA pane VDs exist. */
        fun hasAaVirtualDisplays(): Boolean = mSplitController != null

        /** AA UI attached (not Delay Destroy). Used by [PhoneHidRedirect]. */
        fun isAaSessionLive(): Boolean =
            mSplitController != null && mSessionPolicy?.isAaSessionLive == true

        /** CarPlay Source session live (reported by the app). Used by [PhoneHidRedirect]. */
        fun isCarPlaySessionLive(): Boolean =
            mSplitController != null && carPlaySessionLive

        /**
         * True while Host Recents / picker chrome should own HID (not locked-peel).
         * Set by [showHidRecentTask] / cleared by [PhoneHidRedirect].
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
            if (capture) {
                mSplitController?.lockedPeel?.reset()
                mSplitController?.hidChrome?.reset()
            } else {
                clearHidShellTouchQueue()
            }
            PhoneHidRedirect.onShellCaptureChanged(capture)
        }

        fun hidTargetDisplayId(): Int =
            mSplitController?.resolveHidInjectionDisplayId() ?: Display.INVALID_DISPLAY

        fun hidPrimaryDisplayId(): Int =
            mSplitController?.primaryDisplayId?.takeIf { it != Display.INVALID_DISPLAY }
                ?: Display.INVALID_DISPLAY

        fun hidSecondaryDisplayId(): Int =
            mSplitController?.secondaryDisplayId?.takeIf { it != Display.INVALID_DISPLAY }
                ?: Display.INVALID_DISPLAY

        fun hidSplitLayout(): HidSplitLayout? = mSplitController?.hidSplitLayout()

        /** BT mouse click on shell 「收起键盘」 — do not inject into the pane VD. */
        fun hideImeFromHid() {
            runCatching { mSplitController?.hideIme() }
        }

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

        /**
         * Phone HID chrome inject.
         * - Shell capture (Recents / picker): queue for Host [drainHidShellTouches]
         *   — AaDisplay presentation / View dispatch is gone.
         * - Otherwise: locked peel and/or [SplitHidChromeController].
         */
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
                if (aaUiShellCapture) {
                    enqueueHidShellTouch(action, x, y)
                    return true
                }
                val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
                    source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                }
                try {
                    mSplitController?.onTouchAaDisplay(event) == true
                } finally {
                    event.recycle()
                }
            } catch (e: Throwable) {
                logDebug(TAG, "injectHidAaUiTouch: ${e.message}")
                false
            }
        }

        private const val HID_SHELL_TOUCH_QUEUE_MAX = 64
        private val hidShellTouchLock = Any()
        /** Flat triplets: action, xNorm, yNorm. */
        private val hidShellTouchQueue = ArrayDeque<Float>(HID_SHELL_TOUCH_QUEUE_MAX * 3)

        private fun enqueueHidShellTouch(action: Int, xPx: Float, yPx: Float) {
            val layout = hidSplitLayout()
            val tw = (layout?.let { l ->
                l.shellTotalW.takeIf { it > 0 } ?: l.totalW
            } ?: 1).toFloat().coerceAtLeast(1f)
            val th = (layout?.let { l ->
                l.shellTotalH.takeIf { it > 0 } ?: l.totalH
            } ?: 1).toFloat().coerceAtLeast(1f)
            val xN = (xPx / tw).coerceIn(0f, 1f)
            val yN = (yPx / th).coerceIn(0f, 1f)
            synchronized(hidShellTouchLock) {
                while (hidShellTouchQueue.size >= HID_SHELL_TOUCH_QUEUE_MAX * 3) {
                    hidShellTouchQueue.removeFirst()
                    hidShellTouchQueue.removeFirst()
                    hidShellTouchQueue.removeFirst()
                }
                hidShellTouchQueue.addLast(action.toFloat())
                hidShellTouchQueue.addLast(xN)
                hidShellTouchQueue.addLast(yN)
            }
        }

        fun drainHidShellTouchesLocked(): FloatArray {
            synchronized(hidShellTouchLock) {
                if (hidShellTouchQueue.isEmpty()) return FloatArray(0)
                val out = FloatArray(hidShellTouchQueue.size)
                var i = 0
                while (hidShellTouchQueue.isNotEmpty()) {
                    out[i++] = hidShellTouchQueue.removeFirst()
                }
                return out
            }
        }

        fun clearHidShellTouchQueue() {
            synchronized(hidShellTouchLock) {
                hidShellTouchQueue.clear()
            }
        }

        /** Adjust split ratio by [delta] (−0.05 / +0.05) on the dual-VD profile. */
        fun nudgeHidSplitRatio(delta: Float): Boolean {
            return try {
                val c = mSplitController ?: return false
                if (SplitPane.isFullscreenPane(c.mFullscreenPane)) return false
                val next = SplitPane.clampRatio(c.mRatio + delta)
                if (kotlin.math.abs(next - c.mRatio) < 0.001f) return true
                c.setSplitRatio(next)
                mSessionPolicy?.onVirtualDisplayUserInteraction()
                true
            } catch (e: Throwable) {
                logDebug(TAG, "nudgeHidSplitRatio: ${e.message}")
                false
            }
        }

        fun clearPendingHidShellRatio() {
            // no-op — shell-optimistic ratio path removed with Car SDK UI
        }

        fun showHidRecentTask(): Boolean {
            return try {
                // Host Recents is drawn in-app (CarPlayRecentsOverlay); no Car SDK shell.
                // Bump [hidOpenRecentsSeq] so Host GL opens the overlay (cross-process).
                setAaUiShellCapture(true)
                hidOpenRecentsSeq++
                hidCursorGen++
                PhoneHidRedirect.revealCursorForKeyboardRecent()
                mSessionPolicy?.onVirtualDisplayUserInteraction()
                true
            } catch (e: Throwable) {
                logDebug(TAG, "showHidRecentTask: ${e.message}")
                false
            }
        }

        fun swapHidPanes(): Boolean {
            return try {
                mSplitController?.swapPanesFromUser()
                mSessionPolicy?.onVirtualDisplayUserInteraction()
                true
            } catch (e: Throwable) {
                logDebug(TAG, "swapHidPanes: ${e.message}")
                false
            }
        }

        /**
         * Phone BT mouse cursor tip + Host shell state.
         * Host GL pulls via [hidCursorOverlaySnapshot] each encode frame
         * (replaces deleted AaDisplayActivity / HidCursorOverlayView poll).
         *
         * Indices: 0 visible, 1 x, 2 y, 3 gen, 4 peelPreview, 5 peelRatio,
         * 6 flipSeq, 7 flipPane, 8 openRecentsSeq, 9 splitRatio, 10 fullscreenPane.
         * Index 9 may be an unclamped 0..1 divider-drag preview (past MIN/MAX) while
         * [hidDividerPreviewActive] so Host seam can reach fullscreen-enter edges.
         */
        @Volatile private var hidCursorVisible = false
        @Volatile private var hidCursorX = 0f
        @Volatile private var hidCursorY = 0f
        @Volatile private var hidCursorGen = 0
        /** Locked fullscreen peel preview — UI pulls via [hidCursorOverlaySnapshot]. */
        @Volatile private var lockedPeelPreviewActive = false
        @Volatile private var lockedPeelPreviewRatio = 0.5f
        /** Split divider edge-drag preview (0..1, may be outside MIN/MAX). */
        @Volatile private var hidDividerPreviewActive = false
        @Volatile private var hidDividerPreviewRatio = 0.5f
        /** Locked peel tap-flip — broadcast is slow under keyguard; Host GL pulls each frame. */
        @Volatile private var lockedFullscreenFlipSeq = 0
        @Volatile private var lockedFullscreenFlipPane = SplitPane.FULLSCREEN_NONE
        /** Host [CarPlayRecentsOverlay.open] request from system_server HID chrome. */
        @Volatile private var hidOpenRecentsSeq = 0

        fun publishHidCursorState(visible: Boolean, x: Float = 0f, y: Float = 0f) {
            hidCursorVisible = visible
            if (visible) {
                hidCursorX = x
                hidCursorY = y
            }
            hidCursorGen++
        }

        fun publishLockedPeelPreview(active: Boolean, ratio: Float = 0.5f) {
            lockedPeelPreviewActive = active
            if (active) lockedPeelPreviewRatio = ratio
            hidCursorGen++
        }

        /** Phone BT mouse divider drag — Host seam follows [ratio] even past 20/80. */
        fun publishHidDividerPreview(active: Boolean, ratio: Float = 0.5f) {
            hidDividerPreviewActive = active
            if (active) hidDividerPreviewRatio = ratio.coerceIn(0f, 1f)
            hidCursorGen++
        }

        fun publishLockedFullscreenFlip(pane: Int) {
            lockedFullscreenFlipPane = pane
            lockedFullscreenFlipSeq++
            hidCursorGen++
        }

        fun hidCursorOverlaySnapshot(): FloatArray {
            val splitRatio = when {
                hidDividerPreviewActive -> hidDividerPreviewRatio
                else -> mSplitController?.mRatio ?: SplitPane.DEFAULT_RATIO
            }
            return floatArrayOf(
                if (hidCursorVisible) 1f else 0f,
                hidCursorX,
                hidCursorY,
                hidCursorGen.toFloat(),
                if (lockedPeelPreviewActive) 1f else 0f,
                lockedPeelPreviewRatio,
                lockedFullscreenFlipSeq.toFloat(),
                lockedFullscreenFlipPane.toFloat(),
                hidOpenRecentsSeq.toFloat(),
                splitRatio,
                (mSplitController?.mFullscreenPane ?: SplitPane.FULLSCREEN_NONE).toFloat(),
            )
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

        /**
         * Phone HID media keys (飞鼠 Consumer Control): same path as steering-wheel
         * [pressKey] — live-room swipe + [AvMediaArbiter] media-key target.
         * Do not raw-inject MEDIA_* into the focused VD (MediaSession often delivers
         * them to idle Douyin FeedPlayerSession).
         */
        fun dispatchHidMediaKey(keyCode: Int): Boolean {
            return try {
                mSessionPolicy?.onVirtualDisplayUserInteraction()
                mSplitController?.onPressKey(keyCode)
                true
            } catch (e: Throwable) {
                logDebug(TAG, "dispatchHidMediaKey: ${e.message}")
                false
            }
        }

        /** Retired Car SDK presentation key inject — always false. */
        fun injectHidAaUiKeyEvent(event: KeyEvent): Boolean = false

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

    /**
     * Client binders die when the app process is killed mid-create/reconnect (USB accessory
     * rebind, LMK, etc.). [DeadObjectException] must never escape onto system_server main —
     * Samsung treats that as soft reboot (`P|EX`).
     */
    private fun notifyDisplayAvailable(
        listener: IVirtualDisplayCreatedListener,
        displayId: Int,
        create: Boolean,
    ) {
        runCatching { listener.onAvailableDisplay(displayId, create) }
            .onFailure { log(TAG, "onAvailableDisplay dropped (client dead) id=$displayId create=$create", it) }
    }

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
            val hostDpi = resolveReportedHostDensityDpi(densityDpi)
            val effectiveDpi = resolveEffectiveDensityDpi(hostDpi)
            val profile = resolveDisplayProfile(
                width = width,
                height = height,
                densityDpi = effectiveDpi,
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
                // Density-only HU changes can leave controller/profile briefly desynced when
                // the client still echoes a stale dpi; re-assert effective density.
                applyDensityOverrideLive()
                // Exit ColorFade if panes went OFF while AA was disconnected.
                mSessionPolicy?.keepVirtualDisplayAwake("soft-reconnect")
                // Controller retains live ratio across Delay Destroy; AA echo may lag
                // LastSplitStore — UI reconciles via SPLIT_STATE_CHANGED.
                notifyDisplayAvailable(listener, primaryDisplayId, create = false)
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
                        notifyDisplayAvailable(listener, displayId, create = true)
                        ClusterLyricMirror.onAaConnected()
                        // Session policy (delay-destroy / keep-awake) after first frame callback.
                        runMain {
                            if (mSplitController !== controller) return@runMain
                            mSessionPolicy?.onDestroyPromptly()
                            mSessionPolicy = DisplaySessionPolicy(
                                CommonContextWrapper.createModuleContext(systemContext),
                                controller,
                            )
                            runCatching { VdImeDisplayPin.onAaDisplaysActive(systemContext) }
                                .onFailure { log(TAG, "VdImeDisplayPin session-active failed", it) }
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

    override fun getFocusedPane(): Int {
        return mSplitController?.mFocusedPane ?: SplitPane.PRIMARY
    }

    override fun swapSplitPanes() {
        mSplitController?.swapPanesFromUser()
    }

    override fun onDestroyDisplay() {
        runMain {
            if (carPlaySessionLive) {
                logDebug(
                    TAG,
                    "onDestroyDisplay while CarPlay live — Delay Destroy still armed; CP egress may keep using VDs",
                )
            }
            mSplitController?.launch?.flushPersistOnAaDisconnect()
            val finishTeardown: () -> Unit = {
                mSplitController?.onDestroy()
                mSessionPolicy = null
                mSplitController = null
                mDisplayCreateInProgress = false
                clearDisplayProfileLock()
                runCatching { VdImeDisplayPin.onAaDisplaysInactive(systemContext) }
                    .onFailure { log(TAG, "VdImeDisplayPin session-inactive failed", it) }
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

    override fun getHidCursorOverlay(): FloatArray = hidCursorOverlaySnapshot()

    override fun getClusterArtJpeg(): ByteArray? =
        if (hasSystemContext) {
            ClusterArtStore.loadJpegBytes(systemContext.contentResolver)
        } else {
            ClusterArtStore.loadJpegBytes()
        }

    /**
     * Persist runs on the Binder thread (a plain Settings.Global/file write — no main
     * thread needed) and MUST complete before this returns: MainActivity reads back
     * [getVdDensityDpi] right after the call to confirm the save. Only the live session
     * apply hops to the main handler. Replaces the old CountDownLatch runMainSync, which
     * blocked a Binder thread waiting on system_server main — a latent deadlock if main
     * is busy in a synchronous binder exchange.
     */
    override fun setVdDensityDpi(dpi: Int) {
        if (!persistVdDensityDpi(dpi)) return
        mMainHandler.post { applyDensityOverrideLive() }
    }

    override fun getVdDensityDpi(): Int {
        if (!hasSystemContext) return 0
        return DisplayDpiStore.loadConfigured(systemContext.contentResolver)
    }

    override fun getEffectiveVdDensityDpi(): Int = getDensityDpi()

    override fun moveTaskToFront(taskId: Int) {
        mSplitController?.moveTaskToFrontAsync(taskId)
    }

    @SuppressLint("MissingPermission")
    override fun removeTask(taskId: Int) {
        mSplitController?.removeTaskAsync(taskId)
    }

    override fun pressKey(action: Int) {
        runIO {
            noteUserInteraction()
            mSplitController?.onPressKey(action)
        }
    }

    override fun touchPane(pane: Int, event: MotionEvent) {
        val a = event.actionMasked
        // POINTER_DOWN = a further finger joined: same user interaction / focus refresh.
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            noteUserInteractionAsync()
            mSplitController?.setFocusedPane(pane)
        }
        mSplitController?.onTouchPane(pane, event)
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

    override fun getRecentTask(): RecentTask {
        return mSplitController?.getRecentTask()
            ?: RecentTask(emptyList(), emptyList(), emptyList())
    }

    /**
     * Secondary egress live bit (CarPlay ∪ AAP). AIDL name kept for Binder stability;
     * policy side is [DisplaySessionPolicy.onSecondaryEgressLiveChanged].
     */
    override fun reportCarPlaySessionLive(live: Boolean) {
        carPlaySessionLive = live
        mSessionPolicy?.onSecondaryEgressLiveChanged(live)
        runCatching { CarPlayProcessBoost.setBoost(live) }
            .onFailure { logDebug(TAG, "CarPlayProcessBoost.setBoost failed") }
        if (!live) {
            runCatching { ThreadRealtimeBoost.clearAll() }
                .onFailure { logDebug(TAG, "ThreadRealtimeBoost.clearAll failed") }
        }
        // Capture + phone mute follow secondary-egress FGS (CarPlay / AAP), not individual RTSP
        // audio SETUP/TEARDOWN. DiPlay soft-reconnect briefly drops UDP egresses; if we
        // disarmed there, phone speaker came back before the next SETUP remuted.
        runCatching { CarPlayAudioCapture.setEnabled(live) }
            .onFailure {
                logDebug(
                    TAG,
                    "CarPlayAudioCapture setEnabled($live) on session live failed",
                )
            }
        logDebug(TAG, "reportCarPlaySessionLive live=$live")
    }

    override fun setThreadRealtimeBoost(tid: Int, enable: Boolean) {
        runCatching { ThreadRealtimeBoost.setBoost(tid, enable) }
            .onFailure { logDebug(TAG, "ThreadRealtimeBoost.setBoost failed tid=$tid") }
    }

    override fun isCarPlaySessionLive(): Boolean = carPlaySessionLive

    override fun setCarPlayAudioEgressEnabled(enabled: Boolean) {
        if (!enabled && carPlaySessionLive) {
            // Soft reconnect / TEARDOWN: keep tap + local mute until FGS tearDown.
            logDebug(TAG, "setCarPlayAudioEgressEnabled(false) ignored — session still live")
            return
        }
        CarPlayAudioCapture.setEnabled(enabled)
    }

    override fun pullCarPlayAudio(maxBytes: Int): ByteArray = CarPlayAudioCapture.pull(maxBytes)

    override fun pullCarPlaySpeechAudio(maxBytes: Int): ByteArray =
        CarPlayAudioCapture.pullSpeech(maxBytes)

    override fun isCarPlaySpeechCaptureReady(): Boolean =
        CarPlayAudioCapture.isSpeechCaptureReady()

    @Suppress("DEPRECATION")
    override fun setWifiEnabled(enabled: Boolean) {
        if (!hasSystemContext) {
            log(TAG, "setWifiEnabled($enabled) skipped: systemContext not initialized")
            return
        }
        val wm = systemContext.getSystemService(android.net.wifi.WifiManager::class.java)
        if (wm == null) {
            log(TAG, "setWifiEnabled($enabled) skipped: no WifiManager")
            return
        }
        val token = android.os.Binder.clearCallingIdentity()
        try {
            val ok = runCatching { wm.setWifiEnabled(enabled) }.getOrDefault(false)
            log(TAG, "setWifiEnabled($enabled) ok=$ok now=${runCatching { wm.isWifiEnabled }.getOrNull()}")
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    @Suppress("DEPRECATION", "HardwareIds", "MissingPermission")
    override fun localWifiMac(): String {
        if (!hasSystemContext) return ""
        // Binder calling uid is the app. WifiManager rejects that against the
        // system package ("Package android does not belong to <appUid>").
        val token = android.os.Binder.clearCallingIdentity()
        try {
            return localWifiMacCleared()
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    @Suppress("DEPRECATION", "HardwareIds", "MissingPermission")
    private fun localWifiMacCleared(): String {
        val cm = systemContext.getSystemService(android.net.ConnectivityManager::class.java)
        val networks = runCatching { cm?.allNetworks }.getOrNull().orEmpty()
        for (network in networks) {
            val caps = runCatching { cm?.getNetworkCapabilities(network) }.getOrNull() ?: continue
            if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) continue
            val info = caps.transportInfo as? android.net.wifi.WifiInfo ?: continue
            val mac = usableMac(info.macAddress) ?: continue
            log(TAG, "localWifiMac $mac")
            return mac
        }
        val wm = systemContext.getSystemService(android.net.wifi.WifiManager::class.java)
        val fromInfo = usableMac(wm?.connectionInfo?.macAddress)
        if (fromInfo != null) {
            log(TAG, "localWifiMac $fromInfo")
            return fromInfo
        }
        return ""
    }

    @Suppress("HardwareIds", "MissingPermission")
    override fun localBluetoothMac(): String {
        if (!hasSystemContext) return ""
        val token = android.os.Binder.clearCallingIdentity()
        try {
            val adapter = systemContext
                .getSystemService(android.bluetooth.BluetoothManager::class.java)
                ?.adapter
            usableMac(adapter?.address)?.let {
                log(TAG, "localBluetoothMac $it")
                return it
            }
            val fromSettings = android.provider.Settings.Secure.getString(
                systemContext.contentResolver,
                "bluetooth_address",
            )
            usableMac(fromSettings)?.let {
                log(TAG, "localBluetoothMac $it")
                return it
            }
            return ""
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    /**
     * Samsung keeps an unvalidated car hotspot as Active default when the user
     * once chose "stay connected" (ACCEPT_UNVALIDATED). That installs a wlan0
     * policy route with no default gateway → whole-phone "no network" while LTE
     * is still up. Prefer cellular via network_avoid_bad_wifi + noInternetExpected
     * + allowAutojoin(false). Do **not** call setAcceptUnvalidated(false) or
     * setAvoidUnvalidated: both drop the WIFI NetworkAgent while wlan0 may stay
     * associated, leaving AirPlay with nothing to bindSocket.
     */
    @SuppressLint("MissingPermission")
    override fun preferCellularOverUnvalidatedWifi() {
        if (!hasSystemContext) return
        val token = android.os.Binder.clearCallingIdentity()
        try {
            preferCellularOverUnvalidatedWifiUnlocked()
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    @SuppressLint("MissingPermission")
    override fun armBluetoothDiscoverable() {
        if (!hasSystemContext) return
        val token = android.os.Binder.clearCallingIdentity()
        try {
            val adapter = systemContext.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) return
            val wanted = BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
            if (adapter.scanMode == wanted) return
            val ok = invokeSetScanMode(adapter, wanted)
            log(TAG, "armBluetoothDiscoverable ok=$ok scanMode=${adapter.scanMode}")
        } catch (e: Throwable) {
            log(TAG, "armBluetoothDiscoverable failed ${e.message}")
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    override fun reportHidShellOverlay(open: Boolean) {
        setAaUiShellCapture(open)
    }

    override fun drainHidShellTouches(): FloatArray = drainHidShellTouchesLocked()

    override fun reportEgressChannel(channel: Int) {
        val token = android.os.Binder.clearCallingIdentity()
        try {
            EgressChannelMirror.write(channel)
        } catch (e: Throwable) {
            log(TAG, "reportEgressChannel failed channel=$channel", e)
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    @SuppressLint("MissingPermission")
    private fun invokeSetScanMode(adapter: BluetoothAdapter, mode: Int): Boolean {
        val methods = adapter.javaClass.methods.filter { it.name == "setScanMode" }
        for (m in methods.sortedBy { it.parameterTypes.size }) {
            val pts = m.parameterTypes
            val invoked = runCatching {
                when {
                    pts.size == 1 && pts[0] == Integer.TYPE ->
                        m.invoke(adapter, mode)
                    pts.size == 2 && pts[0] == Integer.TYPE && pts[1] == Integer.TYPE ->
                        m.invoke(adapter, mode, 300)
                    else -> null
                }
            }.getOrNull()
            if (invoked == true || adapter.scanMode == mode) return true
        }
        return adapter.scanMode == mode
    }

    @Suppress("DEPRECATION")
    private fun preferCellularOverUnvalidatedWifiUnlocked() {
        try {
            android.provider.Settings.Global.putInt(
                systemContext.contentResolver,
                "network_avoid_bad_wifi",
                1,
            )
        } catch (e: Throwable) {
            log(TAG, "network_avoid_bad_wifi put failed", e)
        }
        val wm = systemContext.getSystemService(android.net.wifi.WifiManager::class.java)
        try {
            @Suppress("UNCHECKED_CAST")
            val configs = wm?.configuredNetworks as? List<android.net.wifi.WifiConfiguration>
            configs?.forEach { cfg ->
                val bare = cfg.SSID?.trim('"').orEmpty()
                val armedCp = bare.equals(armedCarAccessorySsid, ignoreCase = true)
                if (!isCarHotspotSsid(bare) && !armedCp) return@forEach
                try {
                    val f = cfg.javaClass.getField("noInternetAccessExpected")
                    f.setBoolean(cfg, true)
                    wm.updateNetwork(cfg)
                    log(TAG, "noInternetAccessExpected=true ssid=$bare")
                } catch (e: Throwable) {
                    log(TAG, "noInternetAccessExpected failed ssid=$bare", e)
                }
                if (armedCp) {
                    // CarPlay handoff outranks the avoid-bad-wifi heuristic: the active
                    // accessory must stay autojoin-able. network_avoid_bad_wifi=1 +
                    // noInternetAccessExpected keep it off the default route, so
                    // cellular stays the INTERNET path.
                    try {
                        android.net.wifi.WifiManager::class.java
                            .getMethod("allowAutojoin", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                            .invoke(wm, cfg.networkId, true)
                        log(TAG, "allowAutojoin(true) armed CP ssid=$bare id=${cfg.networkId}")
                    } catch (e: Throwable) {
                        log(TAG, "allowAutojoin failed ssid=$bare", e)
                    }
                    return@forEach
                }
                // Stop WifiNetworkFactory from auto-joining the saved STA (that path
                // stamps ACCEPT_UNVALIDATED and steals the default route). Specifier
                // joins from CarPlayWifiHandoff still work with autojoin off.
                try {
                    android.net.wifi.WifiManager::class.java
                        .getMethod("allowAutojoin", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                        .invoke(wm, cfg.networkId, false)
                    log(TAG, "allowAutojoin(false) ssid=$bare id=${cfg.networkId}")
                } catch (e: Throwable) {
                    log(TAG, "allowAutojoin failed ssid=$bare", e)
                }
            }
            runCatching { wm?.saveConfiguration() }
        } catch (e: Throwable) {
            log(TAG, "wifi config mark failed", e)
        }
    }

    private fun isCarHotspotSsid(ssid: String): Boolean {
        if (ssid.isEmpty()) return false
        val s = ssid.lowercase()
        return s.contains("audi") ||
            s.contains("mmi") ||
            s.contains("carplay") ||
            s.contains("diplay") ||
            s.startsWith("carplay")
    }

    /** SSID armed by [armCarAccessoryWifi]; preferCellular keeps its autojoin on. */
    @Volatile
    private var armedCarAccessorySsid: String? = null

    /**
     * CarPlay handoff is the top-priority network action: persist the accessory
     * hotspot as a saved network (known PSK, noInternetAccessExpected,
     * allowAutojoin(true), saved) and connect it explicitly. An SSID the user
     * never saved manually is treated by OEM WifiNetworkFactory as untrusted /
     * unwanted and specifier-only joins get torn down within seconds — the
     * "works only after I joined the Wi-Fi manually" failure.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    override fun armCarAccessoryWifi(ssidRaw: String?, passphraseRaw: String?) {
        if (!hasSystemContext) return
        val ssid = ssidRaw?.trim().orEmpty()
        if (ssid.isEmpty()) return
        val token = android.os.Binder.clearCallingIdentity()
        try {
            armCarAccessoryWifiCleared(ssid, passphraseRaw?.trim().orEmpty())
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun armCarAccessoryWifiCleared(ssid: String, passphrase: String) {
        val wm = systemContext.getSystemService(android.net.wifi.WifiManager::class.java)
            ?: return
        // Mark before any preferCellular so concurrent avoid-bad-wifi sweeps
        // keep this SSID autojoin-able (settings "自动重新连接").
        armedCarAccessorySsid = ssid
        // null configuredNetworks must not abort — treat as empty and addNetwork.
        val configs = runCatching { wm.configuredNetworks }
            .getOrNull() as? List<android.net.wifi.WifiConfiguration>
            ?: emptyList()
        val existing = configs.firstOrNull {
            it.SSID?.trim('"').equals(ssid, ignoreCase = true) == true
        }
        val cfg = existing ?: android.net.wifi.WifiConfiguration()
        cfg.SSID = "\"$ssid\""
        // AOSP KeyMgmt: NONE=0, WPA_PSK=1, WPA2_PSK(deprecated)=4, SAE=8.
        // Prior bit map (WEP=4 / WPA2=8) was wrong: SAE-only configs auth-fail on
        // Audi WPA2/transition APs → permanently disabled → Settings shows
        // auto-reconnect off and Specifier-only (isSaved=false) joins.
        val psk = passphrase.length in 8..63
        val bits = java.util.BitSet(16)
        if (psk) {
            bits.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
            bits.set(android.net.wifi.WifiConfiguration.KeyMgmt.SAE)
            cfg.preSharedKey = "\"$passphrase\""
        } else {
            bits.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
            cfg.preSharedKey = ""
        }
        cfg.allowedKeyManagement = bits
        runCatching {
            cfg.javaClass.getField("noInternetAccessExpected").setBoolean(cfg, true)
        }
        val id = if (existing != null) wm.updateNetwork(cfg) else wm.addNetwork(cfg)
        if (id < 0) {
            log(TAG, "armCarAccessoryWifi addNetwork rejected ssid=$ssid")
            return
        }
        // Re-enable selection after a prior auth-fail / autojoin-off disable.
        runCatching { wm.enableNetwork(id, false) }
        try {
            android.net.wifi.WifiManager::class.java
                .getMethod("allowAutojoin", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(wm, id, true)
        } catch (e: Throwable) {
            log(TAG, "armCarAccessoryWifi allowAutojoin failed ${e.message}")
        }
        runCatching { wm.saveConfiguration() }
        log(
            TAG,
            "armCarAccessoryWifi saved ssid=$ssid id=$id new=${existing == null} psk=$psk",
        )
        if (!wm.isWifiEnabled) {
            log(TAG, "armCarAccessoryWifi radio off — saved only")
            return
        }
        val current = runCatching { wm.connectionInfo?.ssid }.getOrNull()?.trim('"').orEmpty()
        if (current.isEmpty() || current == "<unknown ssid>" ||
            !current.equals(ssid, ignoreCase = true)
        ) {
            // connect(int) is hidden/removed on API 31+ — invoke by name.
            val ok = runCatching {
                for (name in listOf("connect", "connectNetwork")) {
                    val m = wm.javaClass.methods.firstOrNull { it.name == name &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType
                    } ?: continue
                    if (m.invoke(wm, id) == true) return@runCatching true
                }
                false
            }.getOrDefault(false)
            log(TAG, "armCarAccessoryWifi connect id=$id ok=$ok")
        } else {
            log(TAG, "armCarAccessoryWifi already on ssid=$ssid")
        }
    }

    @SuppressLint("MissingPermission")
    override fun prepareCarPlayHeadUnit(address: String?) {
        val mac = address?.trim()?.uppercase().orEmpty()
        if (mac.length < 11 || !hasSystemContext) return
        headUnitAddresses.add(mac)
        armHeadUnitAccessWatch()
        val token = android.os.Binder.clearCallingIdentity()
        try {
            val adapter = systemContext.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) return
            val device = adapter.getRemoteDevice(mac)
            // Confirm any in-flight SSP here (app has no BLUETOOTH_PRIVILEGED).
            // Harmless when no pairing is pending; required so PAIRING_REQUEST never
            // falls through to Settings after prepareHeadUnit from the app watch.
            val pairOk = try {
                device.setPairingConfirmation(true)
            } catch (e: Throwable) {
                log(TAG, "prepareCarPlayHeadUnit pairingConfirm failed ${e.message}")
                false
            }
            val book = setAccess(device, "setPhonebookAccessPermission")
            val msg = setAccess(device, "setMessageAccessPermission")
            val sim = setAccess(device, "setSimAccessPermission")
            log(
                TAG,
                "prepareCarPlayHeadUnit $mac pair=$pairOk pbap=$book map=$msg sim=$sim",
            )
            forbidProfile(adapter, device, BluetoothProfile.A2DP, "a2dp")
            forbidProfile(adapter, device, PROFILE_PBAP, "pbap")
            forbidProfile(adapter, device, PROFILE_MAP, "map")
        } catch (e: Throwable) {
            log(TAG, "prepareCarPlayHeadUnit failed ${e.message}")
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    /**
     * PBAP/MAP dialogs and the HU pairing prompt are ordered broadcasts.
     * The app process cannot call `setPairingConfirmation` (no BLUETOOTH_PRIVILEGED);
     * system_server can, and aborting here keeps Settings from cancelling the bond.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag", "MissingPermission")
    private fun armHeadUnitAccessWatch() {
        if (headUnitAccessReceiver != null || !hasSystemContext) return
        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                val action = intent?.action ?: return
                val device = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                val mac = device.address?.trim()?.uppercase().orEmpty()
                if (mac !in headUnitAddresses) return
                when (action) {
                    ACTION_CONNECTION_ACCESS_REQUEST -> {
                        val type = intent.getIntExtra(EXTRA_ACCESS_REQUEST_TYPE, -1)
                        if (type != REQUEST_PHONEBOOK && type != REQUEST_MESSAGE && type != REQUEST_SIM) return
                        val token = android.os.Binder.clearCallingIdentity()
                        try {
                            setAccess(device, "setPhonebookAccessPermission")
                            setAccess(device, "setMessageAccessPermission")
                            setAccess(device, "setSimAccessPermission")
                        } finally {
                            android.os.Binder.restoreCallingIdentity(token)
                        }
                        try {
                            abortBroadcast()
                        } catch (_: Throwable) {
                        }
                        log(TAG, "reject HU access type=$type $mac")
                    }
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                        val token = android.os.Binder.clearCallingIdentity()
                        val ok = try {
                            device.setPairingConfirmation(true)
                        } catch (e: Throwable) {
                            log(TAG, "HU pairing confirm failed ${e.message}")
                            false
                        } finally {
                            android.os.Binder.restoreCallingIdentity(token)
                        }
                        if (!ok) return
                        try {
                            abortBroadcast()
                        } catch (_: Throwable) {
                        }
                        log(TAG, "HU pairing confirmed $mac")
                    }
                }
            }
        }
        headUnitAccessReceiver = rx
        val filter = IntentFilter().apply {
            addAction(ACTION_CONNECTION_ACCESS_REQUEST)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                systemContext.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                systemContext.registerReceiver(rx, filter)
            }
        } catch (e: Throwable) {
            headUnitAccessReceiver = null
            log(TAG, "HU access watch failed ${e.message}")
        }
    }

    private fun setAccess(device: BluetoothDevice, method: String): Boolean {
        return try {
            val m = device.javaClass.getMethod(method, Int::class.javaPrimitiveType)
            m.invoke(device, ACCESS_REJECTED) as? Boolean ?: false
        } catch (e: Throwable) {
            log(TAG, "$method failed ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun forbidProfile(
        adapter: android.bluetooth.BluetoothAdapter,
        device: BluetoothDevice,
        profile: Int,
        label: String,
    ) {
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                val token = android.os.Binder.clearCallingIdentity()
                val ok = try {
                    val method = proxy.javaClass.getMethod(
                        "setConnectionPolicy",
                        BluetoothDevice::class.java,
                        Int::class.javaPrimitiveType,
                    )
                    method.invoke(proxy, device, CONNECTION_POLICY_FORBIDDEN) as? Boolean ?: false
                } catch (e: Throwable) {
                    log(TAG, "forbid $label failed ${e.message}")
                    false
                } finally {
                    android.os.Binder.restoreCallingIdentity(token)
                }
                log(TAG, "forbid $label ${device.address} ok=$ok")
                try {
                    adapter.closeProfileProxy(profileId, proxy)
                } catch (_: Throwable) {
                }
            }

            override fun onServiceDisconnected(profileId: Int) {}
        }
        try {
            adapter.getProfileProxy(systemContext, listener, profile)
        } catch (e: Throwable) {
            log(TAG, "forbid $label proxy failed ${e.message}")
        }
    }

    private fun usableMac(raw: String?): String? {
        val mac = raw?.trim()?.uppercase().orEmpty()
        if (mac.length < 11 || mac == "02:00:00:00:00:00" || mac == "00:00:00:00:00:00") return null
        return mac
    }

    override fun scrollPane(pane: Int, x: Int, y: Int, vScroll: Int, hScroll: Int) {
        runIO {
            mSessionPolicy?.onVirtualDisplayUserInteraction()
            runCatching {
                mSplitController?.onHidScrollOnPane(pane, x.toFloat(), y.toFloat(), vScroll.toFloat(), hScroll.toFloat())
            }.onFailure { logDebug(TAG, "scrollPane: ${it.message}") }
        }
    }

    private fun noteUserInteraction() {
        mSessionPolicy?.onVirtualDisplayUserInteraction()
    }

    private fun noteUserInteractionAsync() {
        runIO { noteUserInteraction() }
    }
}

/** Main-thread coroutine shorthand (singleton service, process-lifetime scope). */
private fun runMain(block: suspend CoroutineScope.() -> Unit) =
    GlobalScope.launch(Dispatchers.Main, block = block)

/** IO-thread coroutine shorthand (singleton service, process-lifetime scope). */
private fun runIO(block: suspend CoroutineScope.() -> Unit) =
    GlobalScope.launch(Dispatchers.IO, block = block)


