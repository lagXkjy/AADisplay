package io.github.nitsuya.aa.display.carplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.Drawable
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.carplay.gles.EglCore
import io.github.nitsuya.aa.display.carplay.gles.EglWindowSurface
import io.github.nitsuya.aa.display.carplay.gles.GlUtil
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * GPU zero-copy Host compositor: dual VirtualDisplay → [SurfaceTexture] (OES) → GLES
 * split composite → MediaCodec [createInputSurface] at a fixed supply cadence.
 *
 * Replaces the CPU ImageReader → sticky Bitmap path that capped content refresh ≈30 Hz
 * and starved the Canvas pump. Render owns the encoder Surface exclusively while attached —
 * [FrameEgressTee] must not [CarPlayVideoEncoder.encodeCanvas] the same surface.
 */
class CarPlayGlCompositor {
    /** (targetFps, actualFps) after each 10 s window window. */
    var onRateReport: ((Int, Float) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private val encoderAttached = AtomicBoolean(false)
    private val primarySurfaceRef = AtomicReference<Surface?>(null)
    private val secondarySurfaceRef = AtomicReference<Surface?>(null)

    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    private var eglCore: EglCore? = null
    private var offscreenSurface: android.opengl.EGLSurface? = null
    private var encoderWindow: EglWindowSurface? = null

    private var texIds = intArrayOf(0, 0)
    private var surfaceTextures = arrayOfNulls<SurfaceTexture>(2)
    private var paneSurfaces = arrayOfNulls<Surface>(2)
    private val frameAvailable = booleanArrayOf(false, false)
    private val texMatrix = FloatArray(16)

    private var oesProgram = 0
    private var tex2dProgram = 0
    private var aPositionLoc = -1
    private var aTexCoordLoc = -1
    private var uMvpLoc = -1
    private var uTexMatrixLoc = -1
    private var aPositionLoc2d = -1
    private var aTexCoordLoc2d = -1
    private var uMvpLoc2d = -1
    private var uTexMatrixLoc2d = -1

    private var overlayTexId = 0
    private var overlayBitmap: Bitmap? = null
    /**
     * True after the GPU overlay texture matches [overlayBitmap]. Cleared on GL recreate /
     * bitmap resize so the first chrome frame always uploads.
     */
    private var chromeGpuSynced = false
    /** Last chrome content signature that was drawn + uploaded; skip idle full-frame uploads. */
    private var lastChromeKey: ChromeKey? = null
    /**
     * Geometry the overlay actually painted on the previous chrome frame. The incremental
     * clear/upload rects track this mirror, NOT [ChromeKey]: the draw uses live values
     * (e.g. [CarPlayInputBridge.primaryRatio], written from the main thread) which can
     * move between key build and canvas write within one tick. Clearing by the key-derived
     * rect would leave stale divider pixels behind on a fast drag (double bar); mirroring
     * what was really painted closes that race.
     */
    private var lastDrawnState = ChromeDrawnState.EMPTY
    private val imeChipLayout = RectF()
    /** Last pulled shell state; reused on quiet ticks that skip the Binder pull ([dueForHidPull]). */
    private var lastHidState = HidShellPull()
    /** Ticks since the last shell pull; armed so the first tick always pulls. */
    private var quietTicks = QUIET_PULL_TICKS
    /** Phone BT mouse sprite (Lucide); loaded once when [bindHidCursorDrawable] is called. */
    private var hidCursorDrawable: Drawable? = null
    private var lastHidOpenRecentsSeq = Float.NaN
    private var lastHidFlipSeq = Float.NaN
    private val mainHandler = Handler(Looper.getMainLooper())

    private var encodeWidth = CarPlayConsts.ENCODE_WIDTH
    private var encodeHeight = CarPlayConsts.ENCODE_HEIGHT
    /**
     * HUIG content rect inside the codec frame (margins cropped by the HU).
     * VD / chrome / touch use [layoutWidth]×[layoutHeight]; encoder stays full size.
     */
    @Volatile private var contentLeft = 0
    @Volatile private var contentTop = 0
    @Volatile private var layoutWidth = 0
    @Volatile private var layoutHeight = 0
    /** Matches [SplitDisplayController.mDensityDpi] so divider / pane strips == VD buffers. */
    @Volatile
    private var densityDpi: Int = 160
    private val paneBufferW = intArrayOf(0, 0)
    private val paneBufferH = intArrayOf(0, 0)
    private var fps = CarPlayConsts.DEFAULT_VIDEO_FPS
    private var frameIntervalMs = 1_000L / CarPlayConsts.DEFAULT_VIDEO_FPS
    private var frameIndex = 0L

    private val deliveredFrames = AtomicInteger(0)
    private var windowStart = 0L
    private var rateLogTick = 0

    /**
     * Pending forced frames. Bumped from any thread on layout / encoder changes
     * (AtomicInteger); consumed (getAndSet(0)) only on the GL thread in [drawFrame].
     */
    private val forceFrames = AtomicInteger(0)

    private val mvpMatrix = FloatArray(16)
    private val identityTex = GlUtil.IDENTITY_MATRIX.copyOf()

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(FULL_RECT.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(FULL_RECT)
            position(0)
        }
    private val texCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(TEX_COORDS)
            position(0)
        }
    /**
     * Canvas (picker / Recents / chrome) is top-down in bitmap space; after
     * [GLUtils.texImage2D] + MediaCodec, it needs a V flip relative to OES panes
     * (which already stand upright via SurfaceTexture transform alone).
     */
    private val overlayTexCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(TEX_COORDS_FLIPPED.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(TEX_COORDS_FLIPPED)
            position(0)
        }

    private val renderTick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val handler = renderHandler ?: return
            val tickStart = SystemClock.uptimeMillis()
            try {
                drawFrame()
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "CarPlayGlCompositor draw failed", e)
            }
            evaluateRate()
            if (!running.get()) return
            val nextAt = tickStart + frameIntervalMs
            val delay = (nextAt - SystemClock.uptimeMillis()).coerceAtLeast(1L)
            handler.postDelayed(this, delay)
        }
    }

    fun primarySurface(): Surface? = primarySurfaceRef.get()
    fun secondarySurface(): Surface? = secondarySurfaceRef.get()
    fun isEncoderAttached(): Boolean = encoderAttached.get()

    /**
     * Boots the GL render thread, creates dual OES pane Surfaces for VirtualDisplay sinks.
     * Encoder binding happens later via [attachEncoder] once MediaCodec is ready.
     *
     * @param densityDpi same dpi passed to [CoreApi.onCreateSplitDisplay] so
     * [SplitPane.dividerPx] matches system_server pane sizing.
     */
    fun start(paneWidth: Int, paneHeight: Int, densityDpi: Int = 160) {
        if (!running.compareAndSet(false, true)) return
        this.densityDpi = densityDpi.coerceAtLeast(1)
        val thread = HandlerThread("carplay-gl-compose", Thread.MAX_PRIORITY).also { it.start() }
        renderThread = thread
        val handler = Handler(thread.looper)
        renderHandler = handler
        val ready = CountDownLatch(1)
        var bootError: Throwable? = null
        handler.post {
            try {
                bootGl(paneWidth.coerceAtLeast(16), paneHeight.coerceAtLeast(16))
            } catch (e: Throwable) {
                bootError = e
                Log.e(CarPlayConsts.TAG, "CarPlayGlCompositor boot failed", e)
                running.set(false)
            } finally {
                ready.countDown()
            }
        }
        ready.await(3, TimeUnit.SECONDS)
        bootError?.let { throw it }
        if (!running.get()) return
        activeInstance = this
        // Do not start the 60 Hz renderTick until [attachEncoder] — Host can warm
        // dual OES panes without burning GPU while waiting for screen SETUP.
        windowStart = SystemClock.uptimeMillis()
        Log.i(
            CarPlayConsts.TAG,
            "CarPlayGlCompositor start panes=${paneWidth}x$paneHeight dpi=${this.densityDpi} " +
                "primary=${primarySurfaceRef.get() != null}",
        )
    }

    /** Keep in lockstep with VD dpi after a status-page override. */
    /** Load Lucide pointer once for Host chrome overlay (phone BT mouse). */
    fun bindHidCursorDrawable(context: Context) {
        if (hidCursorDrawable != null) return
        hidCursorDrawable = runCatching {
            ContextCompat.getDrawable(context.applicationContext, R.drawable.ic_hid_cursor_pointer)
                ?.mutate()
        }.getOrNull()
    }

    fun updateDensityDpi(dpi: Int) {
        densityDpi = dpi.coerceAtLeast(1)
        forceFrames.addAndGet(2)
    }

    fun currentDensityDpi(): Int = densityDpi

    /**
     * Place dual-pane + chrome inside the HU-visible content rect of the encode
     * buffer. Pass zeros / full encode size when the HU reports no margins.
     */
    fun setContentRect(left: Int, top: Int, width: Int, height: Int) {
        contentLeft = left.coerceAtLeast(0)
        contentTop = top.coerceAtLeast(0)
        layoutWidth = width.coerceAtLeast(0)
        layoutHeight = height.coerceAtLeast(0)
        forceFrames.addAndGet(3)
        Log.i(
            CarPlayConsts.TAG,
            "CarPlayGlCompositor contentRect=${contentLeft},${contentTop} " +
                "${layoutW()}x${layoutH()} encode=${encodeWidth}x$encodeHeight",
        )
    }

    private fun layoutW(): Int =
        layoutWidth.takeIf { it > 0 }?.coerceAtMost(encodeWidth) ?: encodeWidth

    private fun layoutH(): Int =
        layoutHeight.takeIf { it > 0 }?.coerceAtMost(encodeHeight) ?: encodeHeight

    /**
     * Keep OES [SurfaceTexture] default buffers in lockstep with VD pane sizes after a
     * ratio settle. Without this, grow-side panes keep producing at the old buffer size
     * and DiPlay stretches UI vs video (ADB: VD/Window already match, image still wrong).
     */
    fun syncPaneBuffers(primaryW: Int, secondaryW: Int, height: Int) {
        val handler = renderHandler ?: return
        val pw = primaryW.coerceAtLeast(1)
        val sw = secondaryW.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        handler.post {
            applyPaneBufferSize(0, pw, h)
            applyPaneBufferSize(1, sw, h)
            forceFrames.addAndGet(3)
        }
    }

    /**
     * Binds [surface] as the GLES draw target. Runs on the GL thread and waits so callers
     * can detect [eglCreateWindowSurface] failures (e.g. Surface previously CPU-fed via
     * [Surface.lockCanvas]) and mint a fresh MediaCodec input Surface.
     *
     * @return true when the encoder window is live
     */
    fun attachEncoder(surface: Surface, width: Int, height: Int, fps: Int): Boolean {
        val handler = renderHandler ?: return false
        val ok = AtomicBoolean(false)
        val run = Runnable {
            try {
                detachEncoderLocked()
                encodeWidth = width.coerceAtLeast(16)
                encodeHeight = height.coerceAtLeast(16)
                this.fps = fps.coerceIn(1, 120)
                frameIntervalMs = (1_000L / this.fps).coerceAtLeast(1L)
                val core = eglCore ?: return@Runnable
                encoderWindow = EglWindowSurface(core, surface)
                encoderAttached.set(true)
                deliveredFrames.set(0)
                forceFrames.set(2)
                lastHidState = HidShellPull()
                quietTicks = QUIET_PULL_TICKS
                windowStart = SystemClock.uptimeMillis()
                handler.removeCallbacks(renderTick)
                handler.post(renderTick)
                ok.set(true)
                Log.i(
                    CarPlayConsts.TAG,
                    "CarPlayGlCompositor attachEncoder ${encodeWidth}x$encodeHeight@${this.fps}fps",
                )
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "CarPlayGlCompositor attachEncoder failed", e)
                encoderAttached.set(false)
                ok.set(false)
            }
        }
        if (Looper.myLooper() == handler.looper) {
            run.run()
        } else {
            val done = CountDownLatch(1)
            handler.post {
                try {
                    run.run()
                } finally {
                    done.countDown()
                }
            }
            if (!done.await(2, TimeUnit.SECONDS)) {
                Log.e(CarPlayConsts.TAG, "CarPlayGlCompositor attachEncoder timed out")
                return false
            }
        }
        return ok.get()
    }

    fun detachEncoder() {
        val handler = renderHandler
        if (handler == null) {
            encoderAttached.set(false)
            return
        }
        val done = CountDownLatch(1)
        handler.post {
            try {
                detachEncoderLocked()
            } finally {
                done.countDown()
            }
        }
        done.await(1, TimeUnit.SECONDS)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        if (activeInstance === this) activeInstance = null
        val handler = renderHandler
        val thread = renderThread
        if (handler != null) {
            val done = CountDownLatch(1)
            handler.post {
                try {
                    handler.removeCallbacks(renderTick)
                    releaseGl()
                } finally {
                    done.countDown()
                }
            }
            done.await(2, TimeUnit.SECONDS)
            thread?.quitSafely()
        }
        renderHandler = null
        renderThread = null
        primarySurfaceRef.set(null)
        secondarySurfaceRef.set(null)
        encoderAttached.set(false)
        onRateReport = null
        lastHidState = HidShellPull()
        quietTicks = QUIET_PULL_TICKS
        hidCursorDrawable = null
        lastHidOpenRecentsSeq = Float.NaN
        lastHidFlipSeq = Float.NaN
        Log.i(CarPlayConsts.TAG, "CarPlayGlCompositor stopped")
    }

    private fun bootGl(paneWidth: Int, paneHeight: Int) {
        val core = EglCore(flags = EglCore.FLAG_RECORDABLE)
        eglCore = core
        val offscreen = core.createOffscreenSurface(16, 16)
        offscreenSurface = offscreen
        core.makeCurrent(offscreen)

        oesProgram = GlUtil.createProgramOes()
        tex2dProgram = GlUtil.createProgram2d()
        if (oesProgram == 0 || tex2dProgram == 0) {
            throw RuntimeException("GLES program link failed")
        }
        aPositionLoc = GLES20.glGetAttribLocation(oesProgram, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(oesProgram, "aTextureCoord")
        uMvpLoc = GLES20.glGetUniformLocation(oesProgram, "uMVPMatrix")
        uTexMatrixLoc = GLES20.glGetUniformLocation(oesProgram, "uTexMatrix")
        aPositionLoc2d = GLES20.glGetAttribLocation(tex2dProgram, "aPosition")
        aTexCoordLoc2d = GLES20.glGetAttribLocation(tex2dProgram, "aTextureCoord")
        uMvpLoc2d = GLES20.glGetUniformLocation(tex2dProgram, "uMVPMatrix")
        uTexMatrixLoc2d = GLES20.glGetUniformLocation(tex2dProgram, "uTexMatrix")

        for (i in 0..1) {
            val texId = GlUtil.createExternalTexture()
            texIds[i] = texId
            val st = SurfaceTexture(texId)
            st.setDefaultBufferSize(paneWidth, paneHeight)
            paneBufferW[i] = paneWidth
            paneBufferH[i] = paneHeight
            st.setOnFrameAvailableListener({
                synchronized(frameAvailable) { frameAvailable[i] = true }
            }, renderHandler)
            surfaceTextures[i] = st
            val surface = Surface(st)
            paneSurfaces[i] = surface
            if (i == 0) primarySurfaceRef.set(surface) else secondarySurfaceRef.set(surface)
        }
        overlayTexId = GlUtil.createTexture2d()
        chromeGpuSynced = false
        lastChromeKey = null
        lastDrawnState = ChromeDrawnState.EMPTY
    }

    private fun applyPaneBufferSize(pane: Int, width: Int, height: Int) {
        if (pane !in 0..1) return
        if (paneBufferW[pane] == width && paneBufferH[pane] == height) return
        val st = surfaceTextures[pane] ?: return
        try {
            st.setDefaultBufferSize(width, height)
            paneBufferW[pane] = width
            paneBufferH[pane] = height
            Log.i(
                CarPlayConsts.TAG,
                "CarPlayGlCompositor pane[$pane] buffer → ${width}x$height",
            )
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "pane[$pane] setDefaultBufferSize failed", e)
        }
    }

    /** Same math as [io.github.nitsuya.aa.display.ui.aa.split.SplitVdLifecycle.computePaneSizes]. */
    private fun splitStripSizes(ratio: Float): Pair<Int, Int> {
        val (left, _, right) = CarPlayChromePainter.splitStrip(layoutW(), ratio, densityDpi)
        return left to right
    }

    private fun detachEncoderLocked() {
        renderHandler?.removeCallbacks(renderTick)
        try {
            encoderWindow?.release()
        } catch (_: Throwable) {
        }
        encoderWindow = null
        encoderAttached.set(false)
        // Keep a current context so SurfaceTexture updateTexImage still works.
        offscreenSurface?.let { eglCore?.makeCurrent(it) }
    }

    private fun releaseGl() {
        detachEncoderLocked()
        for (i in 0..1) {
            try {
                paneSurfaces[i]?.release()
            } catch (_: Throwable) {
            }
            paneSurfaces[i] = null
            try {
                surfaceTextures[i]?.release()
            } catch (_: Throwable) {
            }
            surfaceTextures[i] = null
            if (texIds[i] != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(texIds[i]), 0)
                texIds[i] = 0
            }
        }
        if (overlayTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(overlayTexId), 0)
            overlayTexId = 0
        }
        overlayBitmap?.recycle()
        overlayBitmap = null
        chromeGpuSynced = false
        lastChromeKey = null
        lastDrawnState = ChromeDrawnState.EMPTY
        if (oesProgram != 0) {
            GLES20.glDeleteProgram(oesProgram)
            oesProgram = 0
        }
        if (tex2dProgram != 0) {
            GLES20.glDeleteProgram(tex2dProgram)
            tex2dProgram = 0
        }
        offscreenSurface?.let {
            try {
                eglCore?.releaseSurface(it)
            } catch (_: Throwable) {
            }
        }
        offscreenSurface = null
        try {
            eglCore?.release()
        } catch (_: Throwable) {
        }
        eglCore = null
    }

    private fun drawFrame() {
        // Drain pending OES frames even when encoder is not yet attached (avoid queue stall).
        var newPaneFrame = false
        for (i in 0..1) {
            val pending = synchronized(frameAvailable) {
                val v = frameAvailable[i]
                frameAvailable[i] = false
                v
            }
            if (!pending) continue
            newPaneFrame = true
            try {
                surfaceTextures[i]?.updateTexImage()
            } catch (e: Throwable) {
                Log.w(CarPlayConsts.TAG, "updateTexImage pane=$i failed", e)
            }
        }

        if (!encoderAttached.get()) return
        val window = encoderWindow ?: return

        // Quiet shell (no cursor / peel / Recents / picker / car-side gesture) pulls the
        // system_server snapshot every [QUIET_PULL_TICKS] ticks instead of every frame —
        // a static screen otherwise burns 2 Binder round trips per tick (120 IPC/s).
        // Edge events (openRecents / flip seqs) are delta-dispatched on pull, so the
        // catch-up pull just fires them a few ticks late.
        val hid = if (dueForHidPull()) {
            pullHidShellState().also { lastHidState = it }
        } else {
            lastHidState
        }
        val key = buildChromeKey(hid)
        val force = forceFrames.getAndSet(0)
        val dirty = newPaneFrame || force > 0 || key != lastChromeKey ||
            CarPlayAppPickerOverlay.isVisible() ||
            CarPlayRecentsOverlay.isVisible()
        if (!dirty) {
            // Static scene: no new pane frame, chrome / layout unchanged. Skip the GL
            // redraw + swap — C2's REPEAT_PREVIOUS_FRAME_AFTER (one frame interval)
            // then emits one tiny repeat so the wire stays paced. Count it as
            // delivered: the supply cadence is intact, the rate report must not
            // mistake a static screen for a stall.
            CarPlayMediaClock.ensureRunning()
            deliveredFrames.incrementAndGet()
            return
        }
        window.makeCurrent()

        GLES20.glViewport(0, 0, encodeWidth, encodeHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val ox = contentLeft.coerceIn(0, (encodeWidth - 1).coerceAtLeast(0))
        val oy = contentTop.coerceIn(0, (encodeHeight - 1).coerceAtLeast(0))
        val lw = layoutW()
        val lh = layoutH()

        val fs = CarPlayInputBridge.fullscreenPane
        val seamX: Int
        val chromeFullscreen: Boolean
        if (SplitPane.isFullscreenPane(fs) && CarPlayInputBridge.peelPreviewActive) {
            // AA peel drawer: both VDs stay full-size; scissor reveals L/R strips.
            // Do not scale into strips (that is split-mode preview).
            val ratio = CarPlayInputBridge.primaryRatio.coerceIn(0f, 1f)
            val gap = SplitPane.dividerPx(densityDpi)
            val usable = (lw - gap).coerceAtLeast(2)
            val leftW = (usable * ratio).toInt().coerceIn(0, usable)
            val rightW = (usable - leftW).coerceAtLeast(0)
            applyPaneBufferSize(0, lw, lh)
            applyPaneBufferSize(1, lw, lh)
            drawPeelClipPreview(ox, oy, lw, lh, leftW, gap, rightW)
            seamX = leftW + gap / 2
            // Morph peel tab → full split seam while dragging (AA setPeelDragSplitVisual).
            chromeFullscreen = false
        } else if (SplitPane.isFullscreenPane(fs)) {
            // Both VDs stay full-size while fullscreen (AA dual full buffers) so peel
            // can reveal the buried pane without a first-frame buffer resize flash.
            applyPaneBufferSize(0, lw, lh)
            applyPaneBufferSize(1, lw, lh)
            val paneIdx = if (fs == SplitPane.PRIMARY) 0 else 1
            drawPane(paneIdx, ox, oy, lw, lh)
            seamX = -1
            chromeFullscreen = true
        } else {
            val raw = CarPlayInputBridge.primaryRatio.coerceIn(0f, 1f)
            val gap = SplitPane.dividerPx(densityDpi)
            val usable = (lw - gap).coerceAtLeast(2)
            // HID divider drag past 20/80: morph both full panes (peel-style) so the
            // seam can reach fullscreen-enter edges instead of hard-stopping.
            if (raw < SplitPane.MIN_RATIO || raw > SplitPane.MAX_RATIO) {
                val leftW = (usable * raw).toInt().coerceIn(0, usable)
                val rightW = (usable - leftW).coerceAtLeast(0)
                applyPaneBufferSize(0, lw, lh)
                applyPaneBufferSize(1, lw, lh)
                drawPeelClipPreview(ox, oy, lw, lh, leftW, gap, rightW)
                seamX = leftW + gap / 2
                chromeFullscreen = false
            } else {
                val (leftW, rightW) = splitStripSizes(raw)
                // Keep ST buffers == VD pane sizes (grow/shrink) every frame is cheap (no-op if same).
                applyPaneBufferSize(0, leftW, lh)
                applyPaneBufferSize(1, rightW, lh)
                drawPane(0, ox, oy, leftW, lh)
                drawPane(1, ox + leftW + gap, oy, rightW, lh)
                seamX = leftW + gap / 2
                chromeFullscreen = false
            }
        }
        drawChromeLayer(hid, key, seamX, chromeFullscreen, ox, oy, lw, lh)

        // Shared media clock (not frameIndex): audio RTP uses the same epoch so DiPlay
        // can keep lip-sync when audio SETUP starts after the encoder is already running.
        CarPlayMediaClock.ensureRunning()
        val ptsNs = CarPlayMediaClock.videoTimestampNs()
        frameIndex++
        window.setPresentationTime(ptsNs)
        if (window.swapBuffers()) {
            deliveredFrames.incrementAndGet()
        } else {
            // Swap failed — force one redraw next tick so the frame is not lost.
            forceFrames.addAndGet(1)
        }
    }

    /**
     * Content signature for chrome / layout, built once per tick in [drawFrame]. The
     * seam formula mirrors [drawChromeLayer] (fullscreen = -1, else divider position)
     * so any visible layout / chrome change flips the skip decision.
     */
    private fun buildChromeKey(hid: HidShellPull): ChromeKey {
        val ox = contentLeft.coerceIn(0, (encodeWidth - 1).coerceAtLeast(0))
        val oy = contentTop.coerceIn(0, (encodeHeight - 1).coerceAtLeast(0))
        val lw = layoutW()
        val lh = layoutH()
        val gap = SplitPane.dividerPx(densityDpi)
        val usable = (lw - gap).coerceAtLeast(2)
        val fs = CarPlayInputBridge.fullscreenPane
        val raw = CarPlayInputBridge.primaryRatio.coerceIn(0f, 1f)
        val leftW = when {
            hid.peelPreview ->
                (usable * hid.peelRatio.coerceIn(0f, 1f)).toInt().coerceIn(0, usable)
            SplitPane.isFullscreenPane(fs) -> 0
            raw < SplitPane.MIN_RATIO || raw > SplitPane.MAX_RATIO ->
                (usable * raw).toInt().coerceIn(0, usable)
            else -> splitStripSizes(raw).first
        }
        val seam = if (SplitPane.isFullscreenPane(fs) && !hid.peelPreview) -1 else leftW + gap / 2
        return ChromeKey(
            seam = seam,
            fullscreen = if (hid.peelPreview) false else SplitPane.isFullscreenPane(fs),
            ox = ox,
            oy = oy,
            lw = lw,
            lh = lh,
            dpi = densityDpi,
            showPicker = CarPlayAppPickerOverlay.isVisible(),
            showRecents = CarPlayRecentsOverlay.isVisible(),
            imePane = CarPlayImeChip.pane,
            imeLabel = CarPlayImeChip.label,
            ratioQ = (CarPlayInputBridge.primaryRatio * 1000f).toInt(),
            peelQ = if (hid.peelPreview) (hid.peelRatio * 1000f).toInt() else -1,
            cursorVisible = hid.cursorVisible,
            cursorX = if (hid.cursorVisible) hid.cursorX.toInt() else 0,
            cursorY = if (hid.cursorVisible) hid.cursorY.toInt() else 0,
        )
    }

    /**
     * Fullscreen peel drag preview: draw both panes at content size and
     * scissor to L/R strips (Host peel preview).
     * Window scissor origin is bottom-left.
     */
    private fun drawPeelClipPreview(
        ox: Int,
        oy: Int,
        lw: Int,
        lh: Int,
        leftW: Int,
        gap: Int,
        rightW: Int,
    ) {
        // Buffer Y grows down; GL scissor Y grows up from the bottom of the encode frame.
        val scissorY = (encodeHeight - oy - lh).coerceAtLeast(0)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        if (leftW > 0) {
            GLES20.glScissor(ox, scissorY, leftW, lh)
            drawPane(0, ox, oy, lw, lh)
        }
        if (rightW > 0) {
            val x = (ox + leftW + gap).coerceAtMost(ox + lw)
            val w = rightW.coerceAtMost((ox + lw - x).coerceAtLeast(0))
            if (w > 0) {
                GLES20.glScissor(x, scissorY, w, lh)
                drawPane(1, ox, oy, lw, lh)
            }
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    private fun drawPane(pane: Int, x: Int, y: Int, w: Int, h: Int) {
        val texId = texIds[pane]
        if (texId == 0) return
        val st = surfaceTextures[pane] ?: return
        // SurfaceTexture transform already includes the VD buffer orientation; an extra
        // flipV here inverted apps on DiPlay while Canvas chrome/picker stayed mismatched.
        st.getTransformMatrix(texMatrix)

        // NDC for this pane strip inside the full encode frame (Y: buffer-top → NDC +1).
        val left = (x.toFloat() / encodeWidth) * 2f - 1f
        val right = ((x + w).toFloat() / encodeWidth) * 2f - 1f
        val top = 1f - (y.toFloat() / encodeHeight) * 2f
        val bottom = 1f - ((y + h).toFloat() / encodeHeight) * 2f
        Matrix.setIdentityM(mvpMatrix, 0)
        // Scale+translate unit quad (-1..1) into pane NDC.
        val sx = (right - left) / 2f
        val sy = (top - bottom) / 2f
        val tx = (right + left) / 2f
        val ty = (top + bottom) / 2f
        Matrix.translateM(mvpMatrix, 0, tx, ty, 0f)
        Matrix.scaleM(mvpMatrix, 0, sx, sy, 1f)

        GLES20.glUseProgram(oesProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)
        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    /**
     * Divider / peel (always) + Recents / picker overlays into one Canvas texture.
     * [seamX] is local to the content rect; &lt; 0 means fullscreen peel mode.
     * Drawn into the content rect of the encode frame so HU margin crop keeps chrome aligned.
     *
     * Also pulls [CoreApi.getHidCursorOverlay] each frame (AaDisplayActivity poll replacement):
     * phone BT cursor tip, locked-peel preview, Recents open, live split ratio / fullscreen.
     *
     * Idle frames (static divider / peel, no cursor, no shell overlay) reuse the previous
     * GPU texture — skip Canvas clear/redraw and the overlay upload entirely.
     * When the key changes without a shell overlay, only the affected element regions
     * (divider / peel tab / IME chip / cursor, prev ∪ new) are cleared + re-uploaded via
     * [android.opengl.GLUtils.texSubImage2D] — a moving BT cursor no longer costs a
     * full 1280×720 ARGB (~3.7 MB) [android.opengl.GLUtils.texImage2D] every tick.
     * Recents / picker (or a freshly minted texture) take the full-frame path.
     */
    private fun drawChromeLayer(
        hid: HidShellPull,
        key: ChromeKey,
        seamX: Int,
        fullscreen: Boolean,
        ox: Int,
        oy: Int,
        lw: Int,
        lh: Int,
    ) {
        val showPicker = key.showPicker
        val showRecents = key.showRecents
        val chromeFs = if (hid.peelPreview) false else fullscreen
        val chromeSeam = if (hid.peelPreview) {
            val gap = SplitPane.dividerPx(densityDpi)
            val usable = (lw - gap).coerceAtLeast(2)
            val leftW = (usable * hid.peelRatio.coerceIn(0f, 1f)).toInt().coerceIn(0, usable)
            leftW + gap / 2
        } else {
            seamX
        }
        val imePane = key.imePane
        val imeLabel = key.imeLabel
        val bmp = ensureOverlayBitmap()
        val liveShell = showPicker || showRecents
        val dirty = liveShell ||
            !chromeGpuSynced ||
            key != lastChromeKey
        if (dirty) {
            val prev = lastChromeKey
            val canvas = Canvas(bmp)
            // Freeze the chip layout for this frame (feeds both the paint and the upload
            // rect) so nothing the canvas writes can outrun the rect.
            val chipShown = !showRecents && !showPicker && SplitPane.isValid(imePane) &&
                CarPlayChromePainter.layoutHideImeChip(
                    imeChipLayout,
                    lw,
                    lh,
                    densityDpi,
                    imePane,
                    chromeFs,
                    CarPlayInputBridge.primaryRatio,
                    imeLabel,
                )
            val drawn = ChromeDrawnState(
                ox,
                oy,
                lw,
                lh,
                densityDpi,
                chromeFs,
                if (chromeFs) -1 else chromeSeam,
                chipShown,
                imeChipLayout.left,
                imeChipLayout.top,
                imeChipLayout.right,
                imeChipLayout.bottom,
                hid.cursorVisible,
                hid.cursorX,
                hid.cursorY,
            )
            // Shell overlays animate across the whole content rect and a fresh GPU texture
            // must be fully seeded — both take the full-frame path.
            val shellOpen = liveShell || (prev?.let { it.showRecents || it.showPicker } ?: false)
            val rects = if (chromeGpuSynced && !shellOpen) {
                drawn.dirtyRegion(lastDrawnState, bmp.width, bmp.height)
            } else {
                null
            }
            if (rects == null) {
                canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            } else {
                for (r in rects) {
                    canvas.save()
                    canvas.clipRect(r)
                    canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                    canvas.restore()
                }
            }
            canvas.save()
            canvas.translate(ox.toFloat(), oy.toFloat())
            if (chromeFs) {
                CarPlayChromePainter.drawPeelTab(canvas, lw, lh)
            } else if (chromeSeam >= 0) {
                CarPlayChromePainter.drawSplitDivider(canvas, lw, lh, chromeSeam)
            }
            if (chipShown) {
                CarPlayChromePainter.drawHideImeChip(canvas, imeChipLayout, densityDpi, imeLabel)
            }
            if (showRecents) {
                CarPlayRecentsOverlay.draw(canvas, lw, lh)
            }
            if (showPicker) {
                CarPlayAppPickerOverlay.draw(canvas, lw, lh)
            }
            if (hid.cursorVisible) {
                CarPlayChromePainter.drawHidCursor(
                    canvas,
                    hid.cursorX,
                    hid.cursorY,
                    densityDpi,
                    hidCursorDrawable,
                )
            }
            canvas.restore()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
            if (rects == null) {
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            } else {
                // 5-arg overload only — the (bitmap, format, type) form treated width/height
                // as GL enums, threw, and left the GPU overlay stuck on the first center seam.
                for (r in rects) {
                    val sub = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
                    try {
                        // Overlay UV is v-flipped: bitmap top → GL row (H - bottom).
                        android.opengl.GLUtils.texSubImage2D(
                            GLES20.GL_TEXTURE_2D, 0, r.left, bmp.height - r.bottom, sub,
                        )
                    } finally {
                        sub.recycle()
                    }
                }
            }
            // Only after upload succeeds — otherwise retry next tick instead of sticking.
            lastChromeKey = key
            chromeGpuSynced = true
            lastDrawnState = drawn
        }

        if (!chromeGpuSynced) return

        Matrix.setIdentityM(mvpMatrix, 0)
        GLES20.glUseProgram(tex2dProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
        GLES20.glUniformMatrix4fv(uMvpLoc2d, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc2d, 1, false, identityTex, 0)
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc2d)
        GLES20.glVertexAttribPointer(aPositionLoc2d, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)
        overlayTexCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc2d)
        GLES20.glVertexAttribPointer(aTexCoordLoc2d, 2, GLES20.GL_FLOAT, false, 8, overlayTexCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPositionLoc2d)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc2d)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /**
     * Content signature for chrome dirty upload. Pixel-quantized cursor so subpixel HID
     * noise does not force a full ARGB upload every tick while the tip is still.
     */
    private data class ChromeKey(
        val seam: Int,
        val fullscreen: Boolean,
        val ox: Int,
        val oy: Int,
        val lw: Int,
        val lh: Int,
        val dpi: Int,
        val showPicker: Boolean,
        val showRecents: Boolean,
        val imePane: Int,
        val imeLabel: String,
        val ratioQ: Int,
        val peelQ: Int,
        val cursorVisible: Boolean,
        val cursorX: Int,
        val cursorY: Int,
    )

    /**
     * Clear/upload region for the incremental chrome path: union of every element this
     * state and [prev] painted (each in its own content origin, bitmap coords, clamped
     * to the bitmap). Empty → null → caller falls back to the full-frame path.
     */
    private fun ChromeDrawnState.dirtyRegion(prev: ChromeDrawnState, bmpW: Int, bmpH: Int): List<Rect>? {
        val all = ArrayList<Rect>(4)
        collectStateElementRects(prev, all)
        collectStateElementRects(this, all)
        val out = ArrayList<Rect>(all.size)
        for (r in all) {
            val c = Rect(r)
            c.intersect(0, 0, bmpW, bmpH)
            if (!c.isEmpty) out.add(c)
        }
        // Defensive: a key change always moves at least one painted element; if the
        // geometry says otherwise, fall back to the full-frame path rather than a no-op.
        return out.takeIf { it.isNotEmpty() }
    }

    private fun collectStateElementRects(state: ChromeDrawnState, out: ArrayList<Rect>) {
        if (state.lw <= 0 || state.lh <= 0) return
        val d = state.dpi.coerceAtLeast(160) / 160f
        if (state.fullscreen) {
            CarPlayChromePainter.peelTabRect(state.lw, state.lh)?.let {
                it.offset(state.ox, state.oy)
                out.add(it)
            }
        } else if (state.seam >= 0) {
            CarPlayChromePainter.dividerRect(state.seam, state.lh)?.let {
                it.offset(state.ox, state.oy)
                out.add(it)
            }
        }
        if (state.chipShown) {
            val m = (6f * d).toInt() + 2
            val r = Rect(
                state.chipLeft.toInt() - m,
                state.chipTop.toInt() - m,
                state.chipRight.toInt() + m,
                state.chipBottom.toInt() + m,
            )
            r.offset(state.ox, state.oy)
            out.add(r)
        }
        if (state.cursorVisible) {
            CarPlayChromePainter.cursorRect(state.cursorX, state.cursorY, state.dpi)?.let {
                it.offset(state.ox, state.oy)
                out.add(it)
            }
        }
    }

    /**
     * What the overlay canvas actually painted on the last chrome frame (content-rect
     * coords). See [lastDrawnState].
     */
    private data class ChromeDrawnState(
        val ox: Int,
        val oy: Int,
        val lw: Int,
        val lh: Int,
        val dpi: Int,
        /** Fullscreen peel tab was painted (vs. split divider at [seam]; seam < 0 = none). */
        val fullscreen: Boolean,
        val seam: Int,
        val chipShown: Boolean,
        val chipLeft: Float,
        val chipTop: Float,
        val chipRight: Float,
        val chipBottom: Float,
        val cursorVisible: Boolean,
        val cursorX: Float,
        val cursorY: Float,
    ) {
        companion object {
            val EMPTY = ChromeDrawnState(0, 0, 0, 0, 160, false, -1, false, 0f, 0f, 0f, 0f, false, 0f, 0f)
        }
    }

    private fun ensureOverlayBitmap(): Bitmap {
        val existing = overlayBitmap
        if (existing != null &&
            !existing.isRecycled &&
            existing.width == encodeWidth &&
            existing.height == encodeHeight
        ) {
            return existing
        }
        existing?.recycle()
        chromeGpuSynced = false
        lastChromeKey = null
        lastDrawnState = ChromeDrawnState.EMPTY
        forceFrames.addAndGet(2)
        return Bitmap.createBitmap(encodeWidth, encodeHeight, Bitmap.Config.ARGB_8888).also {
            overlayBitmap = it
        }
    }

    private data class HidShellPull(
        val cursorVisible: Boolean = false,
        val cursorX: Float = 0f,
        val cursorY: Float = 0f,
        val peelPreview: Boolean = false,
        val peelRatio: Float = 0.5f,
    )

    /**
     * Shell-state pull cadence: per frame while any interactive chrome is live (phone BT
     * cursor, peel preview, Recents / picker, car-side finger gesture); every
     * [QUIET_PULL_TICKS] ticks while quiet. A fully static screen then costs ~12 IPC/s
     * instead of 120.
     */
    private fun dueForHidPull(): Boolean {
        val s = lastHidState
        val active = s.cursorVisible || s.peelPreview ||
            CarPlayRecentsOverlay.isVisible() || CarPlayAppPickerOverlay.isVisible() ||
            CarPlayInputBridge.isChromeGestureActive()
        if (active) {
            quietTicks = 0
            return true
        }
        if (quietTicks >= QUIET_PULL_TICKS) {
            quietTicks = 0
            return true
        }
        quietTicks++
        return false
    }

    /**
     * One Binder pull per *due* encode frame ([dueForHidPull] decimates quiet ticks) —
     * replaces AaDisplayActivity's 16 ms poll of [CoreApi.getHidCursorOverlay]. Applies
     * live split/fullscreen into [CarPlayInputBridge] so phone HID chrome stays in sync
     * with VD sizes.
     */
    private fun pullHidShellState(): HidShellPull {
        val s = runCatching { CoreApi.hidCursorOverlay }.getOrNull() ?: return HidShellPull()
        drainAndDispatchHidShellTouches()
        val fingerChrome = CarPlayInputBridge.isChromeGestureActive()
        if (!fingerChrome && s.size >= 11) {
            // Allow 0..1 so HID divider edge-drag preview can pass MIN/MAX.
            val ratio = s[9].coerceIn(0f, 1f)
            val fs = s[10].toInt()
            if (CarPlayInputBridge.primaryRatio != ratio) {
                CarPlayInputBridge.primaryRatio = ratio
            }
            if (CarPlayInputBridge.fullscreenPane != fs) {
                CarPlayInputBridge.fullscreenPane = fs
            }
        }
        if (s.size >= 9 && s[8] != lastHidOpenRecentsSeq) {
            lastHidOpenRecentsSeq = s[8]
            // Seq starts at 0; only open on a real bump from system_server.
            if (s[8] > 0f) {
                mainHandler.post { CarPlayRecentsOverlay.open() }
            }
        }
        if (s.size >= 8 && s[6] != lastHidFlipSeq) {
            lastHidFlipSeq = s[6]
            val pane = s[7].toInt()
            if (s[6] > 0f && SplitPane.isFullscreenPane(pane)) {
                CarPlayInputBridge.fullscreenPane = pane
            }
        }
        val peel = s.size >= 6 && s[4] > 0.5f
        if (peel) {
            CarPlayInputBridge.peelPreviewActive = true
            CarPlayInputBridge.primaryRatio = s[5].coerceIn(0f, 1f)
        } else if (CarPlayInputBridge.peelPreviewActive && !fingerChrome) {
            // Clear HID-driven preview when system_server drops the bit; leave finger chrome alone.
            CarPlayInputBridge.peelPreviewActive = false
        }
        return HidShellPull(
            cursorVisible = s.isNotEmpty() && s[0] > 0.5f,
            cursorX = s.getOrElse(1) { 0f },
            cursorY = s.getOrElse(2) { 0f },
            peelPreview = peel,
            peelRatio = if (peel) s[5] else 0.5f,
        )
    }

    /**
     * Phone BT mouse → Host Recents / picker (replaces retired AaDisplay View inject).
     * Drain on the GL thread; dispatch on main so overlay launch / dismiss stay ordered.
     */
    private fun drainAndDispatchHidShellTouches() {
        val touches = runCatching { CoreApi.drainHidShellTouches() }.getOrNull() ?: return
        if (touches.isEmpty()) return
        val copy = touches.copyOf()
        mainHandler.post {
            var i = 0
            while (i + 2 < copy.size) {
                val action = copy[i].toInt()
                val x = copy[i + 1].coerceIn(0f, 1f)
                val y = copy[i + 2].coerceIn(0f, 1f)
                i += 3
                runCatching { CarPlayInputBridge.injectNormalized(x, y, action) }
            }
        }
    }

    private fun evaluateRate() {
        if (!encoderAttached.get()) return
        val now = SystemClock.uptimeMillis()
        val dt = now - windowStart
        if (dt < RATE_WINDOW_MS) return
        val actual = deliveredFrames.get() * 1000f / dt
        deliveredFrames.set(0)
        windowStart = now
        rateLogTick++
        if (rateLogTick % 3 == 0) {
            Log.i(
                CarPlayConsts.TAG,
                "CarPlayGlCompositor rate ~${"%.1f".format(actual)}fps target=$fps",
            )
        }
        onRateReport?.invoke(fps, actual)
    }

    companion object {
        private const val RATE_WINDOW_MS = 10_000L
        /** Quiet-tick shell pull interval (60 fps → 5 Hz). Edge events catch up on the next pull. */
        private const val QUIET_PULL_TICKS = 12

        /** Live Host or AAP compositor — divider settle syncs pane buffers through this. */
        @Volatile
        private var activeInstance: CarPlayGlCompositor? = null

        fun activeOrNull(): CarPlayGlCompositor? = activeInstance

        // Full-screen triangle strip in NDC: x,y
        private val FULL_RECT = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )

        // Texture coords matching typical SurfaceTexture upright content after transform.
        private val TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )

        /** Canvas overlay only — flip V so picker/chrome match upright OES panes. */
        private val TEX_COORDS_FLIPPED = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f,
        )
    }
}
