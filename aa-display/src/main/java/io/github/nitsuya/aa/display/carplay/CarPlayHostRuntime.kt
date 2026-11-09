package io.github.nitsuya.aa.display.carplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.egress.ProjectionSessionGate
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.util.LastSplitStore
import io.github.nitsuya.aa.display.xposed.IVirtualDisplayCreatedListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay-only Host runtime: dual GLES [SurfaceTexture] → [CarPlayGlCompositor] + split VDs.
 *
 * Owned by [CarPlayProjectionService], **not** [CarPlayHostActivity]. Activity is only an
 * optional trampoline for BAL / process visibility. Video must not depend on Activity
 * lifecycle — otherwise DiPlay stays on the FrameEgressTee test pattern.
 */
object CarPlayHostRuntime {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val bootLock = Any()

    @Volatile
    private var compositor: CarPlayGlCompositor? = null

    @Volatile
    private var primarySurface: Surface? = null

    @Volatile
    private var secondarySurface: Surface? = null

    @Volatile
    private var displayId: Int = Display.INVALID_DISPLAY

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var pendingCreateW = 0

    @Volatile
    private var pendingCreateH = 0

    @Volatile
    private var pendingCreateDpi = 0

    @Volatile
    private var pendingCreateRatio = SplitPane.DEFAULT_RATIO

    private var vdCreateRetries = 0

    private val vdCreateRetryRunnable = Runnable { retryCreateSplitDisplayIfNeeded() }

    private val listener = object : IVirtualDisplayCreatedListener.Stub() {
        override fun onAvailableDisplay(displayId: Int, create: Boolean) {
            this@CarPlayHostRuntime.displayId = displayId
            vdCreateRetries = 0
            mainHandler.removeCallbacks(vdCreateRetryRunnable)
            Log.i(CarPlayConsts.TAG, "CarPlayHostRuntime onAvailableDisplay id=$displayId create=$create")
            primarySurface?.let { CoreApi.setPaneSurface(SplitPane.PRIMARY, it) }
            secondarySurface?.let { CoreApi.setPaneSurface(SplitPane.SECONDARY, it) }
            if (create) maybeLaunchDefaultApps()
        }
    }

    fun isRunning(): Boolean = compositor != null && started.get()

    fun compositorOrNull(): CarPlayGlCompositor? = compositor

    /**
     * Destroy leftover system_server split VDs when this process is listen-only
     * (Host never started). force-stop / crash used to leave Douyin/maps rendering
     * into orphan 60fps VirtualDisplays and keep the phone hot.
     *
     * Must not run while AAP (or any secondary egress) is live: AAP owns the same
     * split VDs via [io.github.nitsuya.aa.display.aap.AapDisplayBridge]. Guarding only
     * on [isRunning] is CarPlay-local and would null AAP's pane surfaces → chrome OK,
     * panes black.
     */
    fun releaseOrphansIfIdle(reason: String) {
        if (isRunning()) return
        if (ProjectionSessionGate.isCarPlayLive()) {
            Log.i(
                CarPlayConsts.TAG,
                "releaseOrphansIfIdle($reason) skipped — secondary egress live",
            )
            return
        }
        Log.i(CarPlayConsts.TAG, "CarPlayHostRuntime releaseOrphansIfIdle reason=$reason")
        runCatching { CoreApi.setPaneSurface(SplitPane.PRIMARY, null) }
        runCatching { CoreApi.setPaneSurface(SplitPane.SECONDARY, null) }
        runCatching { CoreApi.onDestroyDisplay() }
    }

    /** Fire-and-forget; prefer [ensureStartedSync] before arming the encoder. */
    fun ensureStarted(context: Context) {
        ensureStartedSync(context, timeoutMs = 0L)
    }

    /**
     * Boots GL + split VDs and returns when ready (or [timeoutMs] elapses).
     * [timeoutMs] <= 0 means: run sync on main, or post without waiting off-main.
     */
    fun ensureStartedSync(context: Context, timeoutMs: Long = 5_000L): Boolean {
        if (isRunning()) {
            compositor?.let { CarPlayProjectionService.attachGlCompositor(it) }
            return true
        }
        val app = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startMain(app)
            return isRunning()
        }
        if (timeoutMs <= 0L) {
            mainHandler.post { startMain(app) }
            return isRunning()
        }
        val done = CountDownLatch(1)
        mainHandler.post {
            try {
                startMain(app)
            } finally {
                done.countDown()
            }
        }
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            Log.w(CarPlayConsts.TAG, "CarPlayHostRuntime ensureStartedSync timed out ${timeoutMs}ms")
        }
        return isRunning()
    }

    private fun startMain(app: Context) {
        synchronized(bootLock) {
            if (isRunning()) {
                compositor?.let { CarPlayProjectionService.attachGlCompositor(it) }
                return
            }
            // Another starter may be mid-boot (started=true, compositor still null).
            if (started.get() && compositor == null) {
                Log.w(CarPlayConsts.TAG, "CarPlayHostRuntime boot already in progress")
                return
            }
            if (!started.compareAndSet(false, true)) {
                compositor?.let { CarPlayProjectionService.attachGlCompositor(it) }
                return
            }
            appContext = app
            val w = CarPlayConsts.ENCODE_WIDTH
            val h = CarPlayConsts.ENCODE_HEIGHT
            // CarPlay has no HU density negotiate. Default ENCODE_UI_DENSITY_DPI (1dp≈1px
            // on 1280×720); manual override from DisplayDpiStore / status page.
            // Never use phone displayMetrics (360–480) — that fattened the divider gutter.
            val dpi = resolveEncodeDpi()
            val paneW = (w / 2).coerceAtLeast(16)
            val gl = CarPlayGlCompositor()
            try {
                gl.start(paneW, h, dpi)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "CarPlayHostRuntime GL boot failed", e)
                started.set(false)
                return
            }
            val primary = gl.primarySurface()
            val secondary = gl.secondarySurface()
            if (primary == null || secondary == null) {
                Log.e(CarPlayConsts.TAG, "CarPlayHostRuntime GL pane surfaces missing")
                gl.stop()
                started.set(false)
                return
            }
            compositor = gl
            primarySurface = primary
            secondarySurface = secondary
            CarPlayInputBridge.shellWidthPx = w
            CarPlayInputBridge.shellHeightPx = h
            CarPlayInputBridge.densityDpi = dpi
            // Soft reconnect keeps system_server VDs + live ratio; resetting GL to 0.5
            // draws half-strips over 302/968 buffers → black gutters around the divider
            // (seen on DiPlay after HostRuntime restart). Prefer live → snapshot → default.
            val bootRatio = resolveBootRatio(app)
            CarPlayInputBridge.primaryRatio = bootRatio
            syncGlBuffersForRatio(gl, bootRatio, w, h, dpi)
            CarPlayProjectionService.attachGlCompositor(gl)
            CarPlayAppPickerOverlay.bindContext(app)
            CarPlayImeChip.bind(app)
            CarPlayWheelController.onRecentsToggle = { CarPlayRecentsOverlay.toggle() }
            pendingCreateW = w
            pendingCreateH = h
            pendingCreateDpi = dpi
            pendingCreateRatio = bootRatio
            vdCreateRetries = 0
            requestCreateSplitDisplay("boot")
            // Phone BT mouse tip painted into Host chrome (AaDisplayActivity poll retired).
            compositor?.bindHidCursorDrawable(app)
            // Binder / system_server may still be cold right after reboot — CoreApi
            // silently skips when the AADD bridge is null. Retry until panes exist.
            mainHandler.removeCallbacks(vdCreateRetryRunnable)
            mainHandler.postDelayed(vdCreateRetryRunnable, VD_CREATE_RETRY_MS)
            Log.i(
                CarPlayConsts.TAG,
                "CarPlayHostRuntime started ${w}x$h dpi=$dpi ratio=$bootRatio gl=true",
            )
        }
    }

    private fun requestCreateSplitDisplay(reason: String) {
        val primary = primarySurface
        val secondary = secondarySurface
        if (primary == null || secondary == null) return
        try {
            CoreApi.onCreateSplitDisplay(
                pendingCreateW,
                pendingCreateH,
                pendingCreateDpi,
                pendingCreateRatio,
                primary,
                secondary,
                listener,
            )
            Log.i(CarPlayConsts.TAG, "CarPlayHostRuntime onCreateSplitDisplay ($reason)")
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "CarPlayHostRuntime onCreateSplitDisplay failed ($reason)", e)
        }
    }

    private fun retryCreateSplitDisplayIfNeeded() {
        if (!started.get() || compositor == null) return
        if (displayId != Display.INVALID_DISPLAY) return
        if (vdCreateRetries >= VD_CREATE_RETRY_MAX) {
            Log.w(
                CarPlayConsts.TAG,
                "CarPlayHostRuntime VD create gave up after $VD_CREATE_RETRY_MAX tries",
            )
            return
        }
        vdCreateRetries++
        Log.w(
            CarPlayConsts.TAG,
            "CarPlayHostRuntime VD create retry $vdCreateRetries/$VD_CREATE_RETRY_MAX " +
                "(CoreApi bridge may have been cold)",
        )
        requestCreateSplitDisplay("retry-$vdCreateRetries")
        mainHandler.postDelayed(vdCreateRetryRunnable, VD_CREATE_RETRY_MS)
    }

    private fun resolveBootRatio(app: Context): Float {
        val live = runCatching { CoreApi.splitRatio }.getOrNull()?.takeIf { it > 0f }
        if (live != null) return SplitPane.clampRatio(live)
        val snap = runCatching {
            LastSplitStore.invalidateCache()
            LastSplitStore.load(app.contentResolver)?.primaryRatio
        }.getOrNull()?.takeIf { it > 0f }
        if (snap != null) return SplitPane.clampRatio(snap)
        return SplitPane.DEFAULT_RATIO
    }

    private fun syncGlBuffersForRatio(
        gl: CarPlayGlCompositor,
        ratio: Float,
        width: Int,
        height: Int,
        densityDpi: Int,
    ) {
        val gap = SplitPane.dividerPx(densityDpi)
        val usable = (width - gap).coerceAtLeast(2)
        val left = (usable * ratio).toInt().coerceAtLeast(1)
        val right = (usable - left).coerceAtLeast(1)
        gl.syncPaneBuffers(left, right, height)
    }

    /**
     * Keep Host shell / split VDs / touch space equal to the negotiated encode size.
     * Host boots at [CarPlayConsts.ENCODE_WIDTH]×[ENCODE_HEIGHT]; Audi MIB3 SETUP
     * switches to `/info` (e.g. 1412×654). Without this, GL stretches 16:9 panes into
     * an ultrawide canvas → slight zoom + bottom crop, and VD/GL strip desync leaves
     * a left black gutter.
     */
    fun resizeShell(width: Int, height: Int): Boolean {
        val w = width.coerceAtLeast(16)
        val h = height.coerceAtLeast(16)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return resizeShellMain(w, h)
        }
        val ok = AtomicBoolean(false)
        val done = CountDownLatch(1)
        mainHandler.post {
            try {
                ok.set(resizeShellMain(w, h))
            } finally {
                done.countDown()
            }
        }
        done.await(3, TimeUnit.SECONDS)
        return ok.get()
    }

    private fun resizeShellMain(width: Int, height: Int): Boolean {
        synchronized(bootLock) {
            if (!started.get()) return false
            val gl = compositor ?: return false
            if (pendingCreateW == width && pendingCreateH == height) {
                return true
            }
            val dpi = resolveEncodeDpi()
            val ratio = CarPlayInputBridge.primaryRatio.coerceIn(
                SplitPane.MIN_RATIO,
                SplitPane.MAX_RATIO,
            )
            pendingCreateW = width
            pendingCreateH = height
            pendingCreateDpi = dpi
            pendingCreateRatio = ratio
            CarPlayInputBridge.shellWidthPx = width
            CarPlayInputBridge.shellHeightPx = height
            CarPlayInputBridge.densityDpi = dpi
            syncGlBuffersForRatio(gl, ratio, width, height, dpi)
            requestCreateSplitDisplay("resize-${width}x$height")
            Log.i(
                CarPlayConsts.TAG,
                "CarPlayHostRuntime resizeShell ${width}x$height dpi=$dpi ratio=$ratio",
            )
            return true
        }
    }

    /**
     * Status-page DPI apply while Host is live: keep GL divider + split VD density matched.
     * No-op when Host is idle (listen-only); next boot picks up [resolveEncodeDpi].
     */
    fun applyConfiguredDpi() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyConfiguredDpiMain()
        } else {
            mainHandler.post { applyConfiguredDpiMain() }
        }
    }

    private fun applyConfiguredDpiMain() {
        synchronized(bootLock) {
            if (!started.get()) return
            val gl = compositor ?: return
            val dpi = resolveEncodeDpi()
            if (dpi == pendingCreateDpi && gl.currentDensityDpi() == dpi) return
            pendingCreateDpi = dpi
            CarPlayInputBridge.densityDpi = dpi
            gl.updateDensityDpi(dpi)
            val w = pendingCreateW.takeIf { it > 0 } ?: CarPlayConsts.ENCODE_WIDTH
            val h = pendingCreateH.takeIf { it > 0 } ?: CarPlayConsts.ENCODE_HEIGHT
            val ratio = pendingCreateRatio.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
            syncGlBuffersForRatio(gl, ratio, w, h, dpi)
            requestCreateSplitDisplay("dpi-$dpi")
            Log.i(CarPlayConsts.TAG, "CarPlayHostRuntime applyConfiguredDpi dpi=$dpi")
        }
    }

    /**
     * Manual override from CoreApi, else fixed encode default.
     * CarPlay never follows leftover Android Auto HU-reported density.
     */
    private fun resolveEncodeDpi(): Int {
        val configured = runCatching { CoreApi.vdDensityDpi }.getOrDefault(0)
        return if (configured > 0) configured else CarPlayConsts.ENCODE_UI_DENSITY_DPI
    }

    fun stop(reason: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopMain(reason)
        } else {
            val done = CountDownLatch(1)
            mainHandler.post {
                try {
                    stopMain(reason)
                } finally {
                    done.countDown()
                }
            }
            done.await(3, TimeUnit.SECONDS)
        }
    }

    private fun stopMain(reason: String) {
        synchronized(bootLock) {
            if (!started.get() && compositor == null) return
            Log.i(
                CarPlayConsts.TAG,
                "CarPlayHostRuntime stop reason=$reason displayId=$displayId",
            )
            started.set(false)
            mainHandler.removeCallbacks(vdCreateRetryRunnable)
            vdCreateRetries = 0
            CarPlayProjectionService.attachGlCompositor(null)
            // Same rule as releaseOrphansIfIdle: AAP may already own the shared split
            // VDs / overlays after a channel switch. Only tear local GL; leave panes alone.
            val destroyShared = !ProjectionSessionGate.isOwnedByOther(
                ProjectionSessionGate.OWNER_CARPLAY,
            )
            if (destroyShared) {
                CarPlayAppPickerOverlay.unbind()
                CarPlayImeChip.unbind()
                runCatching { CoreApi.setPaneSurface(SplitPane.PRIMARY, null) }
                runCatching { CoreApi.setPaneSurface(SplitPane.SECONDARY, null) }
                runCatching { CoreApi.onDestroyDisplay() }
            } else {
                Log.i(
                    CarPlayConsts.TAG,
                    "CarPlayHostRuntime stop skip shared VD teardown — other egress owns displays",
                )
            }
            try {
                compositor?.stop()
            } catch (_: Throwable) {
            }
            compositor = null
            primarySurface = null
            secondarySurface = null
            displayId = Display.INVALID_DISPLAY
            appContext = null
        }
    }

    private fun maybeLaunchDefaultApps() {
        // Short settle only — the old 2s delay left DiPlay on empty panes after audio.
        mainHandler.postDelayed({
            if (!isRunning()) return@postDelayed
            val ctx = appContext ?: return@postDelayed
            runCatching { LastSplitStore.invalidateCache() }
            val snap = runCatching { LastSplitStore.load(ctx.contentResolver) }.getOrNull()
            snap?.primaryRatio?.let { r ->
                val clamped = r.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
                if (Math.abs(clamped - CarPlayInputBridge.primaryRatio) > 0.02f) {
                    CarPlayInputBridge.primaryRatio = clamped
                    runCatching { CoreApi.setSplitRatio(clamped) }
                }
            }
            snap?.fullscreenPane?.takeIf { SplitPane.isFullscreenPane(it) }?.let { fs ->
                CarPlayInputBridge.fullscreenPane = fs
                runCatching { CoreApi.setSplitFullscreen(fs) }
            }
            val defaults = arrayOf(
                "com.google.android.apps.maps",
                "com.android.chrome",
            )
            for (pane in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
                val stored = if (pane == SplitPane.PRIMARY) snap?.primaryPackage else snap?.secondaryPackage
                val candidate = stored?.takeIf { it.isNotBlank() } ?: defaults[pane]
                try {
                    val currentPkg = CoreApi.getPanePackage(pane)
                    if (currentPkg.isNullOrEmpty()) {
                        CoreApi.startActivityOnPane(candidate, 0, pane)
                        Log.i(CarPlayConsts.TAG, "CarPlayHostRuntime launched $candidate into pane=$pane")
                    } else {
                        Log.i(CarPlayConsts.TAG, "CarPlayHostRuntime pane=$pane keeps $currentPkg")
                    }
                } catch (e: Throwable) {
                    Log.w(CarPlayConsts.TAG, "CarPlayHostRuntime pane=$pane launch failed", e)
                }
            }
        }, DEFAULT_APP_LAUNCH_DELAY_MS)
    }

    private const val VD_CREATE_RETRY_MS = 500L
    private const val VD_CREATE_RETRY_MAX = 12
    private const val DEFAULT_APP_LAUNCH_DELAY_MS = 400L
}
