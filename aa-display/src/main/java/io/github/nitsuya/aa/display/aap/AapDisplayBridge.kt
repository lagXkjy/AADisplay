package io.github.nitsuya.aa.display.aap

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.carplay.CarPlayAppPickerOverlay
import io.github.nitsuya.aa.display.carplay.CarPlayConsts
import io.github.nitsuya.aa.display.carplay.CarPlayGlCompositor
import io.github.nitsuya.aa.display.carplay.CarPlayImeChip
import io.github.nitsuya.aa.display.carplay.CarPlayInputBridge
import io.github.nitsuya.aa.display.carplay.CarPlayVideoEncoder
import io.github.nitsuya.aa.display.egress.ProjectionSessionGate
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.util.LastSplitStore
import io.github.nitsuya.aa.display.xposed.IVirtualDisplayCreatedListener
import net.prototux.aare.aap.channels.VideoChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dual-pane VirtualDisplay + H.264 encoder feeding an AAP [VideoChannel].
 * Reuses CarPlay GL compositor / AVC encoder; frames go to AAP instead of RTP.
 */
class AapDisplayBridge(
    private val context: Context,
    private val onStatus: (String) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var compositor: CarPlayGlCompositor? = null
    private var video: VideoChannel? = null
    private var width = AapConsts.DEFAULT_VIDEO_WIDTH
    private var height = AapConsts.DEFAULT_VIDEO_HEIGHT
    private var fps = AapConsts.DEFAULT_VIDEO_FPS
    private var dpi = CarPlayConsts.ENCODE_UI_DENSITY_DPI
    /** Total L+R / T+B margins from VideoConfiguration (HUIG crop). */
    private var widthMargin = 0
    private var heightMargin = 0
    private var displayId = Display.INVALID_DISPLAY

    private var vdCreateRetries = 0

    private val vdCreateRetryRunnable = Runnable { retryCreateSplitDisplayIfNeeded() }

    @Volatile
    private var pendingCreateW = 0

    @Volatile
    private var pendingCreateH = 0

    @Volatile
    private var pendingCreateDpi = 0

    @Volatile
    private var pendingCreateRatio = SplitPane.DEFAULT_RATIO

    private val encoder = CarPlayVideoEncoder { bytes, ptsUs, key, config ->
        val ch = video ?: return@CarPlayVideoEncoder
        if (config) {
            ch.sendCodecConfig(bytes, 0, bytes.size)
            return@CarPlayVideoEncoder
        }
        // VideoChannel needs 10 bytes of headroom before the NAL payload. The wrapper
        // is pooled — aare returns it via the recycle callback after write / drop.
        val buf = acquireFrameBuf(bytes.size)
        System.arraycopy(bytes, 0, buf, 10, bytes.size)
        ch.sendFrame(buf, 10, bytes.size, ptsUs, key) { releaseFrameBuf(it) }
    }

    /**
     * Reusable 10-byte-headroom frame wrappers. The old per-frame ByteArray(10+size)
     // churn (~35 KB at 8 Mbps / 30 fps) added GC pressure on top of the drain copy.
     */
    private val frameBufPoolLock = Any()
    private val frameBufPool = ArrayDeque<ByteArray>()

    private fun acquireFrameBuf(payloadSize: Int): ByteArray {
        val need = 10 + payloadSize
        synchronized(frameBufPoolLock) {
            var i = 0
            while (i < frameBufPool.size) {
                val b = frameBufPool[i]
                if (b.size >= need) {
                    frameBufPool.removeAt(i)
                    return b
                }
                i++
            }
        }
        return ByteArray(need)
    }

    private fun releaseFrameBuf(buf: ByteArray) {
        if (buf.size > 1_000_000) return
        synchronized(frameBufPoolLock) {
            if (frameBufPool.size < 16) frameBufPool.addLast(buf)
        }
    }

    private val vdListener = object : IVirtualDisplayCreatedListener.Stub() {
        override fun onAvailableDisplay(displayId: Int, create: Boolean) {
            this@AapDisplayBridge.displayId = displayId
            vdCreateRetries = 0
            main.removeCallbacks(vdCreateRetryRunnable)
            Log.i(AapConsts.TAG, "AapDisplayBridge VD ready id=$displayId create=$create")
            onStatus("画面已打开")
            compositor?.primarySurface()?.let { CoreApi.setPaneSurface(SplitPane.PRIMARY, it) }
            compositor?.secondarySurface()?.let { CoreApi.setPaneSurface(SplitPane.SECONDARY, it) }
            if (create) {
                maybeLaunchDefaults()
                // restoreLastSplit applies fullscreen *after* this callback; without a
                // delayed CoreApi pull the GL compositor keeps drawing split strips over
                // two full-size VDs (Douyin pillarbox squashed into a thin right strip).
                scheduleSyncBridgeLayoutFromCore()
            }
        }
    }

    private val syncBridgeLayoutRunnable = Runnable { syncBridgeLayoutFromCore() }

    fun startFor(
        channel: VideoChannel,
        cfgWidth: Int,
        cfgHeight: Int,
        cfgFps: Int,
        /** Encode-canvas dpi: session resolves override / HU / default 160. */
        densityDpi: Int = 0,
        /** HUIG width_margin / height_margin — content is centered inside the codec frame. */
        cfgWidthMargin: Int = 0,
        cfgHeightMargin: Int = 0,
    ) {
        main.post {
            video = channel
            width = cfgWidth.coerceAtLeast(16)
            height = cfgHeight.coerceAtLeast(16)
            fps = cfgFps.coerceIn(1, 60)
            widthMargin = cfgWidthMargin.coerceIn(0, width - 16).let { if (it and 1 != 0) it - 1 else it }
            heightMargin = cfgHeightMargin.coerceIn(0, height - 16).let { if (it and 1 != 0) it - 1 else it }
            // Prefer session-resolved dpi (override → HU → default).
            val configured = runCatching { CoreApi.vdDensityDpi }.getOrDefault(0)
            dpi = when {
                densityDpi > 0 -> densityDpi
                configured > 0 -> configured
                else -> CarPlayConsts.ENCODE_UI_DENSITY_DPI
            }
            CarPlayInputBridge.densityDpi = dpi
            compositor?.updateDensityDpi(dpi)
            boot()
        }
    }

    fun requestKeyFrame() {
        encoder.requestKeyFrame()
    }

    /** CSM-derived bitrate for the current encode size/rate — adaptive ceiling. */
    fun baseVideoBitrate(): Int = encoder.baseBitrate()

    /** Live [CarPlayVideoEncoder.setBitrate] for the AAP adaptive controller. */
    fun setVideoBitrate(bps: Int) {
        main.post { encoder.setBitrate(bps) }
    }

    /**
     * Congestion / thermal pressure cap on the GL pump only (no MediaCodec recycle).
     * Pass 0 to clear. Used by [AapSourceSession] for 60→30 under link pressure.
     */
    fun setPressureFps(fps: Int) {
        main.post { compositor?.setPressureFps(fps) }
    }

    /** Negotiated encode fps from the last [startFor] (HU preference). */
    fun negotiatedFps(): Int = fps

    fun stop() {
        main.post { tearDown("stop") }
    }

    private fun boot() {
        if (!running.compareAndSet(false, true)) {
            // Already up — rebind encoder size if needed.
            ensureEncoderAttached()
            return
        }
        onStatus("正在打开车机画面…")
        // Drop Delay-Destroy VDs from CarPlay / prior AAP so pane apps reload at the
        // encode dpi. Reusing a 213-dpi layout after switching to 160 looks "magnified".
        runCatching { CoreApi.onDestroyDisplay() }
            .onFailure { Log.w(AapConsts.TAG, "pre-boot onDestroyDisplay failed", it) }
        // Canvas picker loads launchables via PackageManager — same bind as CarPlay Host.
        CarPlayAppPickerOverlay.bindContext(context)
        CarPlayAppPickerOverlay.prefetch()
        // VD / touch / chrome live in the HUIG content rect; encoder stays at codec size
        // with black margin bands the HU crops away.
        val contentW = (width - widthMargin).coerceAtLeast(16)
        val contentH = (height - heightMargin).coerceAtLeast(16)
        val insetX = widthMargin / 2
        val insetY = heightMargin / 2
        val paneW = (contentW / 2).coerceAtLeast(16)
        val gl = CarPlayGlCompositor()
        try {
            gl.start(paneW, contentH, dpi)
            gl.setContentRect(insetX, insetY, contentW, contentH)
        } catch (e: Throwable) {
            Log.e(AapConsts.TAG, "GL boot failed", e)
            running.set(false)
            onStatus("打开画面失败，请重试")
            return
        }
        val primary = gl.primarySurface()
        val secondary = gl.secondarySurface()
        if (primary == null || secondary == null) {
            gl.stop()
            running.set(false)
            onStatus("打开画面失败，请重试")
            return
        }
        compositor = gl
        // Phone BT mouse tip painted into Host chrome (AaDisplayActivity poll retired).
        gl.bindHidCursorDrawable(context)
        // After GL is live — paint/hit-test 「收起键盘」 via CarPlayImeChip + InputBridge.
        CarPlayImeChip.bind(context)
        CarPlayInputBridge.shellWidthPx = contentW
        CarPlayInputBridge.shellHeightPx = contentH
        val snap = loadSnapshot()
        val ratio = (snap?.primaryRatio ?: 0.5f).coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
        CarPlayInputBridge.primaryRatio = ratio
        // Mirror CarPlayHostRuntime: seed Host GL fullscreen from snapshot so restore
        // cannot leave dual full buffers composited as split (black gutters / thin video).
        val fs = snap?.fullscreenPane?.takeIf { SplitPane.isFullscreenPane(it) }
            ?: SplitPane.FULLSCREEN_NONE
        CarPlayInputBridge.fullscreenPane = fs
        if (SplitPane.isFullscreenPane(fs)) {
            gl.syncPaneBuffers(contentW, contentW, contentH)
        } else {
            val gap = SplitPane.dividerPx(dpi)
            val usable = (contentW - gap).coerceAtLeast(2)
            val left = (usable * ratio).toInt().coerceAtLeast(1)
            val right = (usable - left).coerceAtLeast(1)
            gl.syncPaneBuffers(left, right, contentH)
        }
        ensureEncoderAttached()
        Log.i(
            AapConsts.TAG,
            "AapDisplayBridge content=${contentW}x$contentH inset=${insetX}x$insetY " +
                "encode=${width}x$height dpi=$dpi ratio=$ratio fullscreen=$fs",
        )
        try {
            pendingCreateW = contentW
            pendingCreateH = contentH
            pendingCreateDpi = dpi
            pendingCreateRatio = ratio
            CoreApi.onCreateSplitDisplay(contentW, contentH, dpi, ratio, primary, secondary, vdListener)
            // CoreApi silently skips when the AADD binder is cold — retry until the VD
            // ready callback lands (same pattern as CarPlayHostRuntime).
            vdCreateRetries = 0
            main.removeCallbacks(vdCreateRetryRunnable)
            main.postDelayed(vdCreateRetryRunnable, VD_CREATE_RETRY_MS)
        } catch (e: Throwable) {
            Log.e(AapConsts.TAG, "onCreateSplitDisplay failed", e)
            onStatus("打开分屏失败，请重试")
        }
    }

    private fun ensureEncoderAttached() {
        val gl = compositor ?: return
        if (!encoder.isRunningAt(width, height, fps) || !encoder.hasSurfaceInput()) {
            encoder.start(width, height, fps)
        }
        val surface = encoder.inputSurfaceOrNull() ?: return
        gl.attachEncoder(surface, width, height, fps)
        encoder.requestKeyFrame()
    }

    private fun loadSnapshot(): LastSplitStore.Snapshot? =
        runCatching {
            LastSplitStore.invalidateCache()
            LastSplitStore.load(context.contentResolver)
        }.getOrNull()

    private fun scheduleSyncBridgeLayoutFromCore() {
        main.removeCallbacks(syncBridgeLayoutRunnable)
        // Immediate + post-restore: system_server restoreLastSplit runs after VD ready.
        main.post(syncBridgeLayoutRunnable)
        main.postDelayed(syncBridgeLayoutRunnable, 400L)
        main.postDelayed(syncBridgeLayoutRunnable, 1200L)
    }

    private fun retryCreateSplitDisplayIfNeeded() {
        if (!running.get()) return
        val gl = compositor ?: return
        if (displayId != Display.INVALID_DISPLAY) return
        if (vdCreateRetries >= VD_CREATE_RETRY_MAX) {
            Log.w(
                AapConsts.TAG,
                "AapDisplayBridge VD create gave up after $VD_CREATE_RETRY_MAX tries",
            )
            onStatus("打开分屏失败，请重试")
            return
        }
        val primary = gl.primarySurface()
        val secondary = gl.secondarySurface()
        if (primary == null || secondary == null) return
        vdCreateRetries++
        Log.w(
            AapConsts.TAG,
            "AapDisplayBridge VD create retry $vdCreateRetries/$VD_CREATE_RETRY_MAX " +
                "(CoreApi bridge may be cold)",
        )
        try {
            CoreApi.onCreateSplitDisplay(
                pendingCreateW,
                pendingCreateH,
                pendingCreateDpi,
                pendingCreateRatio,
                primary,
                secondary,
                vdListener,
            )
        } catch (e: Throwable) {
            Log.e(AapConsts.TAG, "AapDisplayBridge VD create retry failed", e)
        }
        main.postDelayed(vdCreateRetryRunnable, VD_CREATE_RETRY_MS)
    }

    private fun syncBridgeLayoutFromCore() {
        if (!running.get()) return
        val ratio = runCatching { CoreApi.getSplitRatio() }.getOrNull()
            ?.takeIf { it > 0f }
            ?.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
        if (ratio != null) {
            CarPlayInputBridge.primaryRatio = ratio
        }
        val fs = runCatching { CoreApi.getSplitFullscreenPane() }.getOrNull()
            ?: return
        if (CarPlayInputBridge.fullscreenPane != fs) {
            Log.i(AapConsts.TAG, "AapDisplayBridge sync fullscreen $fs (was ${CarPlayInputBridge.fullscreenPane})")
        }
        CarPlayInputBridge.fullscreenPane = fs
    }

    private fun maybeLaunchDefaults() {
        main.postDelayed({
            if (!running.get()) return@postDelayed
            syncBridgeLayoutFromCore()
            runCatching {
                val snap = loadSnapshot()
                snap?.primaryPackage?.takeIf { it.isNotBlank() }?.let {
                    CoreApi.startActivityOnPane(it, 0, SplitPane.PRIMARY)
                }
                snap?.secondaryPackage?.takeIf { it.isNotBlank() }?.let {
                    CoreApi.startActivityOnPane(it, 0, SplitPane.SECONDARY)
                }
            }
        }, 800)
    }

    private fun tearDown(reason: String) {
        if (!running.getAndSet(false) && compositor == null) return
        Log.i(AapConsts.TAG, "AapDisplayBridge tearDown $reason")
        main.removeCallbacks(syncBridgeLayoutRunnable)
        main.removeCallbacks(vdCreateRetryRunnable)
        vdCreateRetries = 0
        video = null
        // CarPlay may already own the shared split VDs after a channel switch.
        val destroyShared = !ProjectionSessionGate.isOwnedByOther(
            ProjectionSessionGate.OWNER_AAP,
        )
        if (destroyShared) {
            runCatching { CoreApi.setPaneSurface(SplitPane.PRIMARY, null) }
            runCatching { CoreApi.setPaneSurface(SplitPane.SECONDARY, null) }
            runCatching { CoreApi.onDestroyDisplay() }
            CarPlayInputBridge.fullscreenPane = SplitPane.FULLSCREEN_NONE
            CarPlayAppPickerOverlay.close()
            CarPlayAppPickerOverlay.unbind()
            CarPlayImeChip.unbind()
        } else {
            Log.i(
                AapConsts.TAG,
                "AapDisplayBridge tearDown skip shared VD — other egress owns displays",
            )
        }
        runCatching { compositor?.stop() }
        compositor = null
        encoder.stop()
        displayId = Display.INVALID_DISPLAY
    }

    private val VD_CREATE_RETRY_MS = 500L
    private val VD_CREATE_RETRY_MAX = 12
}
