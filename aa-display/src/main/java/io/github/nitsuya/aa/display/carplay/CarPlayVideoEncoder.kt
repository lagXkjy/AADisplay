package io.github.nitsuya.aa.display.carplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * AVC encoder for the CarPlay screen stream.
 *
 * Two input modes:
 *  - Surface input (preferred): [CarPlayGlCompositor] (or Canvas tee) feeds
 *    [createInputSurface] — C2 does RGB→YUV in hardware.
 *  - Buffer input (fallback): rotating ARGB8888 slots + worker NV12 conversion.
 *
 * Host GPU path consumes [inputSurfaceOrNull] exclusively via EGL; Canvas tee must not
 * [encodeCanvas] the same Surface concurrently.
 */
class CarPlayVideoEncoder(
    /**
     * bytes, ptsUs, isKey, isCodecConfig. The `bytes` array is a pooled buffer that
     * is reclaimed as soon as this callback returns — consumers must not retain it
     * (screen / RTP egress write synchronously; the AAP bridge copies out).
     */
    private val onEncodedAccessUnit: (ByteArray, Long, Boolean, Boolean) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val surfaceFrameFailLogged = AtomicBoolean(false)
    private val drainFailLogged = AtomicBoolean(false)
    private val droppedFrames = AtomicLong(0)

    /**
     * Reusable drain buffers — the old per-frame ByteArray churn (~2–3 arrays per
     * frame at 60 fps) caused GC pauses in the background FGS that showed up as
     * encode hitches. Codec-config AUs are excluded: the screen egress retains the
     * config buffer across re-SETUP.
     */
    private val auPoolLock = Any()
    private val auPool = ArrayDeque<ByteArray>()

    private fun acquireAuBuffer(size: Int): ByteArray {
        synchronized(auPoolLock) {
            var i = 0
            while (i < auPool.size) {
                val b = auPool[i]
                if (b.size >= size) {
                    auPool.removeAt(i)
                    return b
                }
                i++
            }
        }
        return ByteArray(size)
    }

    private fun releaseAuBuffer(buf: ByteArray, isCodecConfig: Boolean) {
        if (isCodecConfig || buf.size > MAX_POOL_BUFFER) return
        synchronized(auPoolLock) {
            if (auPool.size < POOL_CAPACITY) auPool.addLast(buf)
        }
    }

    /** Frames dropped for back-pressure (converter busy / surface unusable), since process start. */
    fun dropCount(): Long = droppedFrames.get()
    private var width = CarPlayConsts.DEFAULT_VIDEO_WIDTH
    private var height = CarPlayConsts.DEFAULT_VIDEO_HEIGHT
    @Volatile
    private var fps = CarPlayConsts.DEFAULT_VIDEO_FPS
    private var frameIndex = 0L

    // Buffer-input mode: rotating conversion slots; the pump fills the opposite slot the
    // converter reads.
    private var argbSlots = arrayOf(IntArray(0), IntArray(0))
    private var nv12Slots = arrayOf(ByteArray(0), ByteArray(0))
    private var slot = 0
    @Volatile
    private var converterBusy = false
    private val converterQueue = LinkedBlockingQueue<Int>(1)
    private var converterThread: Thread? = null

    fun start(width: Int, height: Int, fps: Int = CarPlayConsts.DEFAULT_VIDEO_FPS) {
        stop()
        // CSM ScreenH264Encoder configures MediaCodec at /info pixels as-is
        // (Audi 1412×654). Forcing 16-align made SPS disagree with SETUP/CONFIG.
        this.width = width.coerceAtLeast(16)
        this.height = height.coerceAtLeast(16)
        this.fps = fps.coerceIn(1, 120)
        val pixels = this.width * this.height
        argbSlots = arrayOf(IntArray(pixels), IntArray(pixels))
        nv12Slots = arrayOf(ByteArray(pixels + pixels / 2), ByteArray(pixels + pixels / 2))
        slot = 0
        converterBusy = false
        surfaceFrameFailLogged.set(false)
        drainFailLogged.set(false)
        val encoder = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "AVC encoder create failed", e)
            return
        }
        // Surface input: configure(COLOR_FormatSurface) → createInputSurface → start.
        // Calling createInputSurface before configure always throws IllegalStateException
        // and forced the expensive ARGB→NV12 buffer path (~40–250ms/frame).
        var surface: Surface? = null
        val surfaceOk = try {
            val surfaceFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                this.width,
                this.height,
            ).apply {
                applyCommonVideoKeys(this, fps)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
            }
            encoder.configure(surfaceFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = encoder.createInputSurface()
            encoder.start()
            true
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "AVC surface input unavailable; buffer mode", e)
            try {
                surface?.release()
            } catch (_: Throwable) {
            }
            surface = null
            try {
                encoder.reset()
            } catch (_: Throwable) {
                try {
                    encoder.release()
                } catch (_: Throwable) {
                }
                codec = null
                running.set(false)
                return
            }
            false
        }
        if (!surfaceOk) {
            val bufferFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                this.width,
                this.height,
            ).apply {
                applyCommonVideoKeys(this, fps)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                )
            }
            try {
                encoder.configure(bufferFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "AVC encoder cannot support advertised dimensions", e)
                try {
                    encoder.release()
                } catch (_: Throwable) {
                }
                codec = null
                running.set(false)
                return
            }
        }
        codec = encoder
        inputSurface = surface
        running.set(true)
        if (surface == null) {
            converterThread = thread(name = "carplay-avc-convert", isDaemon = true) { converterLoop(encoder) }
        }
        thread(name = "carplay-avc-drain", isDaemon = true) { drainLoop(encoder) }
        Log.i(
            CarPlayConsts.TAG,
            "AVC encoder ${this.width}x${this.height}@${this.fps}fps " +
                "input=${if (surface != null) "surface" else "buffer"}",
        )
    }

    /** Surface-input mode active? */
    fun hasSurfaceInput(): Boolean = inputSurface != null

    /** MediaCodec input Surface for GLES / Canvas producers; null in buffer mode. */
    fun inputSurfaceOrNull(): Surface? = inputSurface

    /** Current supply frame rate (0 when stopped). */
    fun currentFps(): Int = if (running.get()) fps else 0

    /**
     * True when already running at the given size + rate — lets callers skip
     * redundant restarts, including the 60→30 degrade re-arm.
     */
    fun isRunningAt(width: Int, height: Int, fps: Int = CarPlayConsts.DEFAULT_VIDEO_FPS): Boolean =
        running.get() &&
            this.width == width.coerceAtLeast(16) &&
            this.height == height.coerceAtLeast(16) &&
            this.fps == fps.coerceIn(1, 120)

    /** DiPlay recovery / showUI asks for an IDR via event `forceKeyFrame`. */
    fun requestKeyFrame() {
        val encoder = codec ?: return
        if (!running.get()) return
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            encoder.setParameters(params)
            Log.i(CarPlayConsts.TAG, "AVC requestKeyFrame")
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "AVC requestKeyFrame failed", e)
        }
    }

    /**
     * Surface-input feed: [draw] renders the full frame into the encoder's hardware canvas
     * (`lockHardwareCanvas` — zero-copy for the pump; C2 converts in hardware). Frame
     * timestamps are component-assigned; DiPlay's pacer self-heals any offset.
     */
    fun encodeCanvas(draw: (Canvas) -> Unit) {
        val encoder = codec ?: return
        if (!running.get()) return
        val surface = inputSurface ?: return
        try {
            // Prefer software lockCanvas: lockHardwareCanvas floods FrameEvents
            // "Did not find frame" on Samsung and contributes to DiPlay strobing even when
            // the Handler pump hits 60 Hz. Software lock still feeds the C2 surface path.
            val canvas = try {
                surface.lockCanvas(null)
            } catch (_: Throwable) {
                surface.lockHardwareCanvas()
            }
            // Buffer rings are undefined until fully painted; letterbox / partial draws
            // otherwise flash prior frames on the HU (seen as DiPlay flicker).
            canvas.drawColor(android.graphics.Color.BLACK)
            draw(canvas)
            surface.unlockCanvasAndPost(canvas)
            frameIndex++
        } catch (e: Throwable) {
            droppedFrames.incrementAndGet()
            if (!surfaceFrameFailLogged.compareAndSet(false, true)) return
            Log.w(CarPlayConsts.TAG, "AVC surface canvas unusable — surface input will drop frames", e)
        }
    }

    /**
     * Buffer-input feed (fallback): copies pixels into the free slot and hands it to the
     * converter thread; drops the frame when the converter is behind.
     */
    fun encodeBitmap(bitmap: Bitmap) {
        val encoder = codec ?: return
        if (!running.get() || bitmap.isRecycled) return
        if (inputSurface != null) {
            // Surface mode ignores bitmap feeds.
            return
        }
        val scaled = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        if (converterBusy) {
            droppedFrames.incrementAndGet()
            if (scaled !== bitmap) scaled.recycle()
            return
        }
        slot = 1 - slot
        converterBusy = true
        try {
            bitmap.getPixels(argbSlots[slot], 0, width, 0, 0, width, height)
        } catch (e: Throwable) {
            converterBusy = false
            if (scaled !== bitmap) scaled.recycle()
            return
        }
        if (scaled !== bitmap) scaled.recycle()
        if (!converterQueue.offer(slot)) {
            converterBusy = false
            droppedFrames.incrementAndGet()
        }
    }

    private fun converterLoop(encoder: MediaCodec) {
        var iters = 0
        var sumConvert = 0L
        var sumDequeue = 0L
        var lastStat = android.os.SystemClock.uptimeMillis()
        while (running.get() || !converterQueue.isEmpty()) {
            val slotIndex = try {
                converterQueue.poll(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
            if (slotIndex == null) continue
            val t0 = android.os.SystemClock.uptimeMillis()
            try {
                convertSlot(slotIndex)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "AVC convert failed", e)
            } finally {
                // The ARGD slot is free for the pump to refill once the conversion finished.
                converterBusy = false
            }
            val t1 = android.os.SystemClock.uptimeMillis()
            sumConvert += t1 - t0
            val inIndex = try {
                encoder.dequeueInputBuffer(10_000)
            } catch (_: Throwable) {
                continue
            }
            sumDequeue += android.os.SystemClock.uptimeMillis() - t1
            iters++
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastStat > 30_000) {
                Log.i(
                    CarPlayConsts.TAG,
                    "AVC worker ${(iters * 1000.0 / (now - lastStat)).toInt()}fps " +
                        "convertAvg=${sumConvert / iters}ms dequeueWaitAvg=${sumDequeue / iters}ms",
                )
                iters = 0
                sumConvert = 0
                sumDequeue = 0
                lastStat = now
            }
            if (inIndex < 0) continue
            try {
                val input: ByteBuffer = encoder.getInputBuffer(inIndex) ?: continue
                val nv12 = nv12Slots[slotIndex]
                input.clear()
                input.put(nv12)
                CarPlayMediaClock.ensureRunning()
                val pts = CarPlayMediaClock.videoTimestampNs() / 1_000L
                frameIndex++
                encoder.queueInputBuffer(inIndex, 0, nv12.size, pts, 0)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "queueInputBuffer failed", e)
            }
        }
        converterBusy = false
    }

    private fun convertSlot(slotIndex: Int) {
        val w = width
        val h = height
        val argb = argbSlots[slotIndex]
        val out = nv12Slots[slotIndex]
        val ySize = w * h
        var yIndex = 0
        var uvIndex = ySize
        var index = 0
        for (j in 0 until h) {
            for (i in 0 until w) {
                val c = argb[index++]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    out[uvIndex++] = u.coerceIn(0, 255).toByte()
                    out[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
    }

    private fun drainLoop(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            // The codec can be released by a concurrent start()/stop() (DiPlay re-SETUP);
            // a stray call must never take down the app — fail the iteration instead.
            try {
                drainOnce(encoder, info)
            } catch (e: Throwable) {
                if (!drainFailLogged.compareAndSet(false, true)) continue
                Log.w(CarPlayConsts.TAG, "AVC drain loop stopping (codec released mid-drain?)", e)
                break
            }
        }
    }

    private fun drainOnce(encoder: MediaCodec, info: MediaCodec.BufferInfo) {
        if (!running.get()) return
        val outIndex = try {
            encoder.dequeueOutputBuffer(info, 10_000)
        } catch (_: Throwable) {
            return
        }
        when {
            outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                try {
                    Log.i(CarPlayConsts.TAG, "AVC output format ${encoder.outputFormat}")
                } catch (_: Throwable) {
                    // Codec released between dequeue and getOutputFormat — ignore.
                }
            }
            outIndex >= 0 -> {
                    var bytes: ByteArray? = null
                    var config = false
                    try {
                        val buffer = encoder.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            bytes = acquireAuBuffer(info.size)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.get(bytes)
                            val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            onEncodedAccessUnit(bytes, info.presentationTimeUs, key, config)
                        }
                    } catch (e: Throwable) {
                        Log.e(CarPlayConsts.TAG, "AVC drain failed", e)
                    } finally {
                        // Codec-config AUs are retained by the screen egress across
                        // re-SETUP — never pool those.
                        bytes?.let { releaseAuBuffer(it, config) }
                        try {
                            encoder.releaseOutputBuffer(outIndex, false)
                        } catch (_: Throwable) {
                        }
                    }
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
        try {
            inputSurface?.release()
        } catch (_: Throwable) {
        }
        inputSurface = null
        converterThread?.interrupt()
        converterThread = null
        frameIndex = 0L
        fps = CarPlayConsts.DEFAULT_VIDEO_FPS
    }

    /** CSM ScreenH264Encoder.bitrateFor — floor 8 Mbps, soft cap w×h×3.6. */
    private fun bitrateFor(fps: Int): Int {
        val soft = ((width.toLong() * height.toLong() * 360L) / 100L).toInt()
        return soft.coerceIn(8_000_000, 30_000_000)
    }

    /** CSM-derived bitrate for the current size/rate — the adaptive ceiling. */
    fun baseBitrate(): Int = bitrateFor(fps.coerceIn(1, 120))

    /**
     * Live bitrate change via [MediaCodec.setParameters] (no codec recycle, no
     * re-CONFIG). Used by the AAP adaptive controller under link congestion.
     */
    fun setBitrate(bps: Int) {
        val encoder = codec ?: return
        if (!running.get()) return
        try {
            val params = Bundle()
            params.putInt(MediaFormat.KEY_BIT_RATE, bps)
            encoder.setParameters(params)
            Log.i(CarPlayConsts.TAG, "AVC bitrate → ${bps / 1_000_000}M")
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "AVC setBitrate failed", e)
        }
    }

    private fun applyCommonVideoKeys(format: MediaFormat, fps: Int) {
        val rate = fps.coerceIn(1, 120)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(rate))
        format.setInteger(MediaFormat.KEY_FRAME_RATE, rate)
        // CSM Audi.11: High + real IDRs (i-frame-interval=1). High + intra-refresh
        // (I_FRAME_INTERVAL=-1) produced tiny non-IDR "keyframes" Audi never showed.
        applyHighProfile(format)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, CarPlayConsts.I_FRAME_INTERVAL_SEC)
        Log.i(
            CarPlayConsts.TAG,
            "AVC gop=${CarPlayConsts.I_FRAME_INTERVAL_SEC}s profile=High " +
                "bitrate=${bitrateFor(rate)}",
        )
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, rate)
        format.setInteger(MediaFormat.KEY_OPERATING_RATE, rate)
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        // KEY_REPEAT_PREVIOUS_FRAME_AFTER is in NANOSECONDS: the old 1_000_000/rate
        // (16 µs @ 60) made C2 re-emit the last frame at drain-loop speed whenever
        // supply hiccuped — a full P-frame TCP burst into the HU. One frame interval
        // caps repeats at the supply cadence; static scenes (GL compositor skip) then
        // emit one tiny repeat per frame, keeping the wire paced.
        format.setLong(
            MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
            1_000_000_000L / rate,
        )
        try {
            format.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            )
        } catch (_: Throwable) {
            // Some OEM codecs reject CBR; VBR default is fine.
        }
    }

    /** CSM ScreenH264Encoder: profile=8 (High) + requiredAvcLevel. */
    private fun applyHighProfile(format: MediaFormat) {
        try {
            format.setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
            )
            format.setInteger(
                MediaFormat.KEY_LEVEL,
                requiredAvcLevel(width, height, fps),
            )
        } catch (_: Throwable) {
            Log.w(CarPlayConsts.TAG, "AVC High@level hint rejected; codec default")
        }
    }

    private fun requiredAvcLevel(w: Int, h: Int, fps: Int): Int {
        val mbs = ((w + 15) / 16).toLong() * ((h + 15) / 16).toLong()
        val mbsPerSec = mbs * fps
        return when {
            mbs <= 8192 && mbsPerSec <= 245_760 ->
                MediaCodecInfo.CodecProfileLevel.AVCLevel31
            mbs <= 22_080 && mbsPerSec <= 589_824 ->
                MediaCodecInfo.CodecProfileLevel.AVCLevel4
            mbs <= 36_864 && mbsPerSec <= 983_040 ->
                MediaCodecInfo.CodecProfileLevel.AVCLevel41
            else -> MediaCodecInfo.CodecProfileLevel.AVCLevel5
        }
    }

    companion object {
        /** 1920×1080@60 IDR bursts can exceed this; keep such buffers out of the pool. */
        private const val MAX_POOL_BUFFER = 1_000_000
        private const val POOL_CAPACITY = 12
    }
}
