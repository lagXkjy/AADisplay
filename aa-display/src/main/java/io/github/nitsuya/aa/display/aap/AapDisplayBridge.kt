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
import io.github.nitsuya.aa.display.carplay.CarPlayVideoEncoder
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
    private var displayId = Display.INVALID_DISPLAY

    private val encoder = CarPlayVideoEncoder { bytes, ptsUs, key, config ->
        val ch = video ?: return@CarPlayVideoEncoder
        if (config) {
            ch.sendCodecConfig(bytes, 0, bytes.size)
            return@CarPlayVideoEncoder
        }
        // VideoChannel needs 10 bytes of headroom before the NAL payload.
        val buf = ByteArray(10 + bytes.size)
        System.arraycopy(bytes, 0, buf, 10, bytes.size)
        ch.sendFrame(buf, 10, bytes.size, ptsUs, key) { /* pooled one-shot */ }
    }

    private val vdListener = object : IVirtualDisplayCreatedListener.Stub() {
        override fun onAvailableDisplay(displayId: Int, create: Boolean) {
            this@AapDisplayBridge.displayId = displayId
            Log.i(AapConsts.TAG, "AapDisplayBridge VD ready id=$displayId create=$create")
            onStatus("画面就绪 display=$displayId")
            compositor?.primarySurface()?.let { CoreApi.setPaneSurface(SplitPane.PRIMARY, it) }
            compositor?.secondarySurface()?.let { CoreApi.setPaneSurface(SplitPane.SECONDARY, it) }
            if (create) maybeLaunchDefaults()
        }
    }

    val videoWidth: Int get() = width
    val videoHeight: Int get() = height

    fun startFor(channel: VideoChannel, cfgWidth: Int, cfgHeight: Int, cfgFps: Int) {
        main.post {
            video = channel
            width = cfgWidth.coerceAtLeast(16)
            height = cfgHeight.coerceAtLeast(16)
            fps = cfgFps.coerceIn(1, 60)
            val configured = runCatching { CoreApi.vdDensityDpi }.getOrDefault(0)
            dpi = if (configured > 0) configured else CarPlayConsts.ENCODE_UI_DENSITY_DPI
            boot()
        }
    }

    fun requestKeyFrame() {
        encoder.requestKeyFrame()
    }

    fun stop() {
        main.post { tearDown("stop") }
    }

    private fun boot() {
        if (!running.compareAndSet(false, true)) {
            // Already up — rebind encoder size if needed.
            ensureEncoderAttached()
            return
        }
        onStatus("启动 VirtualDisplay ${width}x$height@$fps")
        // Canvas picker loads launchables via PackageManager — same bind as CarPlay Host.
        CarPlayAppPickerOverlay.bindContext(context)
        CarPlayAppPickerOverlay.prefetch()
        val paneW = (width / 2).coerceAtLeast(16)
        val gl = CarPlayGlCompositor()
        try {
            gl.start(paneW, height, dpi)
        } catch (e: Throwable) {
            Log.e(AapConsts.TAG, "GL boot failed", e)
            running.set(false)
            onStatus("GL 启动失败")
            return
        }
        val primary = gl.primarySurface()
        val secondary = gl.secondarySurface()
        if (primary == null || secondary == null) {
            gl.stop()
            running.set(false)
            onStatus("GL surface 缺失")
            return
        }
        compositor = gl
        val ratio = resolveRatio()
        val gap = SplitPane.dividerPx(dpi)
        val usable = (width - gap).coerceAtLeast(2)
        val left = (usable * ratio).toInt().coerceAtLeast(1)
        val right = (usable - left).coerceAtLeast(1)
        gl.syncPaneBuffers(left, right, height)
        ensureEncoderAttached()
        try {
            CoreApi.onCreateSplitDisplay(width, height, dpi, ratio, primary, secondary, vdListener)
        } catch (e: Throwable) {
            Log.e(AapConsts.TAG, "onCreateSplitDisplay failed", e)
            onStatus("创建分屏失败")
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

    private fun resolveRatio(): Float {
        val snap = runCatching { LastSplitStore.load(context.contentResolver) }.getOrNull()
        val r = snap?.primaryRatio ?: 0.5f
        return r.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
    }

    private fun maybeLaunchDefaults() {
        main.postDelayed({
            if (!running.get()) return@postDelayed
            runCatching {
                val snap = LastSplitStore.load(context.contentResolver)
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
        video = null
        runCatching { CoreApi.setPaneSurface(SplitPane.PRIMARY, null) }
        runCatching { CoreApi.setPaneSurface(SplitPane.SECONDARY, null) }
        runCatching { CoreApi.onDestroyDisplay() }
        runCatching { compositor?.stop() }
        compositor = null
        encoder.stop()
        displayId = Display.INVALID_DISPLAY
        CarPlayAppPickerOverlay.close()
        CarPlayAppPickerOverlay.unbind()
    }
}
