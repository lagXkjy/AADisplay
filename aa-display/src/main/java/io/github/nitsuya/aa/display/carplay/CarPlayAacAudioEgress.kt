package io.github.nitsuya.aa.display.carplay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.carplay.protocol.BinaryPlist
import io.github.nitsuya.aa.display.carplay.protocol.HkdfSha512
import io.github.nitsuya.aa.display.carplay.protocol.RtspMessage
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Audi / MIB3 media audio: AirPlay stream **type 102** AAC-LC (CSM audi.11 path).
 *
 * Wire matches CSM [AudioRtpSender]: RTP PT=102, ChaCha20-Poly1305 over the AAC
 * access unit, AAD = RTP timestamp+SSRC, trailing 8-byte LE nonce.
 *
 * Encode and RTP pacing run on separate threads so MediaCodec / Binder stalls cannot
 * tear the 1024-sample RTP timeline (that underrun used to become 杂音 then silence).
 * Cadence stays locked to 1024 samples / sampleRate (≈21.3 ms @ 48 kHz).
 */
class CarPlayAacAudioEgress(
    private val host: InetAddress,
    private val dataPort: Int,
    private val controlPort: Int,
    private val key: ByteArray,
    private val sampleRate: Int,
    private val formatBit: Long,
) {
    private val running = AtomicBoolean(false)
    @Volatile private var encodeWorker: Thread? = null
    @Volatile private var txWorker: Thread? = null
    @Volatile private var txSocket: DatagramSocket? = null
    /** One encoded silence frame reused when hold budget is exhausted. */
    @Volatile private var silenceAu: ByteArray? = null
    private val auQueue = ArrayBlockingQueue<ByteArray>(MAX_QUEUED_AUS)

    private val silenceSends = AtomicInteger(0)
    private val holdSends = AtomicInteger(0)
    private val realignCount = AtomicInteger(0)
    private val peakBehindMs = AtomicLong(0L)
    private val pulledBytes = AtomicLong(0L)

    /**
     * Optional hook when the RTP pacer falls far behind (~500ms). Kept for diagnostics;
     * RtspSession intentionally leaves this null — posting modesChanged during a gap
     * latches Audi MainAudio mute while AAC still flows.
     */
    @Volatile var onCadenceGap: (() -> Unit)? = null

    /** @return false when TX is already running. */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        try {
            CoreApi.setCarPlayAudioEgressEnabled(true)
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "setCarPlayAudioEgressEnabled(true) failed", e)
        }
        encodeWorker = thread(name = "carplay-audio-aac-enc", isDaemon = true) { encodeLoop() }
        txWorker = thread(name = "carplay-audio-aac-tx", isDaemon = true) { txLoop() }
        Log.i(
            CarPlayConsts.TAG,
            "CarPlay AAC media TX → $host:$dataPort control=$controlPort " +
                "rate=$sampleRate format=0x${formatBit.toString(16)}",
        )
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            txSocket?.close()
        } catch (_: Throwable) {
        }
        txSocket = null
        encodeWorker?.interrupt()
        txWorker?.interrupt()
        encodeWorker = null
        txWorker = null
        auQueue.clear()
        Log.i(CarPlayConsts.TAG, "CarPlay AAC media TX stopped")
    }

    private fun encodeLoop() {
        val encoder = AacEncoder(sampleRate, CHANNELS, BITRATE)
        if (!encoder.start()) {
            Log.e(CarPlayConsts.TAG, "AAC encoder start failed — no media audio")
            running.set(false)
            return
        }
        val frameBytes = SAMPLES_PER_FRAME * CHANNELS * 2
        // Drop capture filled before TX arm so the first AU is live.
        repeat(8) {
            val got = try {
                CoreApi.pullCarPlayAudio(64 * 1024)
            } catch (_: Throwable) {
                ByteArray(0)
            }
            if (got.isEmpty()) return@repeat
        }
        // Prime AAC-LC (first frames often yield no AU). TX must never call
        // MediaCodec. The primed AU is only a mid-session cadence hold —
        // txLoop does not send it until a real frame has gone out.
        val zeros = ByteArray(frameBytes)
        repeat(8) {
            val au = encoder.encodeExactFrame(zeros)
            if (au != null && silenceAu == null) silenceAu = au
        }
        if (silenceAu == null) {
            Log.w(CarPlayConsts.TAG, "AAC silence AU warm-up failed")
        }
        val accum = ByteArray(frameBytes)
        var accumLen = 0
        val pullCap = frameBytes * 2
        try {
            while (running.get()) {
                // Keep ~1–2 AUs ahead of the pacer; only nap when the queue is full.
                if (auQueue.size >= MAX_QUEUED_AUS) {
                    Thread.sleep(1)
                    continue
                }
                val lowWater = auQueue.size < TARGET_QUEUED_AUS
                val got = try {
                    CoreApi.pullCarPlayAudio(pullCap)
                } catch (_: Throwable) {
                    ByteArray(0)
                }
                if (got.isEmpty()) {
                    // Empty capture: shorter nap when TX is already short on AUs.
                    if (lowWater) Thread.sleep(0, 500_000) else Thread.sleep(1)
                    continue
                }
                pulledBytes.addAndGet(got.size.toLong())
                var off = 0
                while (off < got.size && running.get() && auQueue.size < MAX_QUEUED_AUS) {
                    val need = frameBytes - accumLen
                    val n = minOf(need, got.size - off)
                    System.arraycopy(got, off, accum, accumLen, n)
                    accumLen += n
                    off += n
                    if (accumLen < frameBytes) break
                    // encodeExactFrame may return null on a MediaCodec spike; retry
                    // pending drain so we do not leave a hole for the RTP pacer.
                    val au = encoder.encodeExactFrame(accum) ?: encoder.pollPendingAu()
                    accumLen = 0
                    if (au == null) continue
                    offerAu(au)
                }
            }
        } catch (e: Throwable) {
            if (running.get()) Log.w(CarPlayConsts.TAG, "CarPlay AAC encode ended", e)
        } finally {
            encoder.close()
            Log.i(CarPlayConsts.TAG, "CarPlay AAC encode thread done pulled=${pulledBytes.get() / 1024}KB")
        }
    }

    private fun offerAu(au: ByteArray) {
        while (!auQueue.offer(au)) {
            auQueue.poll() ?: break
        }
    }

    private fun txLoop() {
        val socket = CarPlayAccessoryNet.openUdp()
        txSocket = socket
        val target = InetSocketAddress(host, dataPort)
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        val rtp = ByteArray(12)
        val aad = ByteArray(8)
        var sequence = 0
        // CSM increments before first seal → first nonce counter = 1.
        var nonceCounter = 1L
        var packets = 0
        val ssrc = 0
        val frameNs = SAMPLES_PER_FRAME * 1_000_000_000L / sampleRate
        var nextSendAt = System.nanoTime()
        // One step of exactly 1024 samples per sent AU. Deriving this from
        // microseconds ((n * 1024 * 1e6 / rate) * rate / 1e6) truncates to 1023
        // @ 48 kHz, so the HU jitter buffer slips ~1 sample/frame and after a
        // minute or two underruns into 杂音 then silence.
        // First packet must be ts=0. RTP is armed only after MainAudio is claimed,
        // ~2s after the picture; seeding from MediaClock at that moment made Audi
        // hold the whole stream by that offset (sound late vs the screen).
        var rtpTimestamp = 0
        // False until the first captured AU is sent. Opening silence is not put
        // on the wire — it fills the HU jitter buffer and the mixer drops MainAudio.
        var paced = false
        /** Last real AU; briefly repeated on underrun instead of hard silence. */
        var lastRealAu: ByteArray? = null
        var consecutiveGapFrames = 0
        try {
            while (running.get()) {
                if (paced) {
                    val now = System.nanoTime()
                    val sleepNs = nextSendAt - now
                    if (sleepNs > 0L) {
                        val ms = sleepNs / 1_000_000L
                        val ns = (sleepNs % 1_000_000L).toInt()
                        Thread.sleep(ms, ns)
                    } else if (sleepNs < -frameNs * REALIGN_FRAMES) {
                        val behindMs = -sleepNs / 1_000_000L
                        nextSendAt = System.nanoTime()
                        realignCount.incrementAndGet()
                        peakBehindMs.updateAndGet { maxOf(it, behindMs) }
                        Log.i(
                            CarPlayConsts.TAG,
                            "CarPlay AAC cadence realign behindMs=$behindMs " +
                                "packet=$packets q=${auQueue.size}",
                        )
                        runCatching { onCadenceGap?.invoke() }
                            .onFailure { e ->
                                Log.w(CarPlayConsts.TAG, "onCadenceGap failed", e)
                            }
                    }
                    // Mildly behind: skip sleep and send ASAP to catch up (no clock snap).
                }

                var au = auQueue.poll()
                var usedSilence = false
                var usedHold = false
                if (au == null) {
                    if (!paced) {
                        Thread.sleep(2)
                        continue
                    }
                    consecutiveGapFrames++
                    val held = lastRealAu
                    // Prefer repeating the last real AU for a few frames (~42–64 ms)
                    // so a short encode/Binder stall is not heard as a mute click.
                    if (held != null && consecutiveGapFrames <= HOLD_GAP_FRAMES) {
                        au = held
                        usedHold = true
                        holdSends.incrementAndGet()
                    } else {
                        au = silenceAu
                        usedSilence = au != null
                        if (au == null) {
                            Thread.sleep(2)
                            continue
                        }
                        silenceSends.incrementAndGet()
                    }
                } else {
                    consecutiveGapFrames = 0
                    lastRealAu = au
                    if (!paced) {
                        paced = true
                        nextSendAt = System.nanoTime()
                        rtpTimestamp = 0
                    }
                }

                var payload = stripAdtsIfPresent(au)
                if (payload.size > MAX_AU) {
                    Log.w(CarPlayConsts.TAG, "AAC AU too large ${payload.size}B — drop")
                    nextSendAt += frameNs
                    continue
                }
                val ts = rtpTimestamp
                rtpTimestamp = (rtpTimestamp + SAMPLES_PER_FRAME) and 0x7fffffff
                rtp[0] = 0x80.toByte()
                rtp[1] = RTP_PT.toByte()
                rtp[2] = ((sequence ushr 8) and 0xff).toByte()
                rtp[3] = (sequence and 0xff).toByte()
                rtp[4] = ((ts ushr 24) and 0xff).toByte()
                rtp[5] = ((ts ushr 16) and 0xff).toByte()
                rtp[6] = ((ts ushr 8) and 0xff).toByte()
                rtp[7] = (ts and 0xff).toByte()
                rtp[8] = ((ssrc ushr 24) and 0xff).toByte()
                rtp[9] = ((ssrc ushr 16) and 0xff).toByte()
                rtp[10] = ((ssrc ushr 8) and 0xff).toByte()
                rtp[11] = (ssrc and 0xff).toByte()
                System.arraycopy(rtp, 4, aad, 0, 8)
                val nonce = ByteBuffer.allocate(12)
                var nc = nonceCounter
                for (i in 0 until 8) {
                    nonce.put(4 + i, (nc and 0xff).toByte())
                    nc = nc ushr 8
                }
                nonceCounter++
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(key, "ChaCha20"),
                    IvParameterSpec(nonce.array()),
                )
                cipher.updateAAD(aad)
                val sealed = cipher.doFinal(payload)
                val datagram = ByteArray(12 + sealed.size + 8)
                System.arraycopy(rtp, 0, datagram, 0, 12)
                System.arraycopy(sealed, 0, datagram, 12, sealed.size)
                nonce.position(4)
                for (i in 0 until 8) {
                    datagram[12 + sealed.size + i] = nonce.get()
                }
                socket.send(DatagramPacket(datagram, datagram.size, target))
                sequence = (sequence + 1) and 0xffff
                packets++
                nextSendAt += frameNs
                // Periodic + first hold of a streak + every silence (silence is rarer).
                val logGap = usedSilence || (usedHold && consecutiveGapFrames == 1)
                if (packets <= 5 || packets % 500 == 0 || logGap) {
                    val head = payload.take(4).joinToString("") { "%02x".format(it) }
                    val gapTag = when {
                        usedSilence -> " (silence)"
                        usedHold -> " (hold)"
                        else -> ""
                    }
                    Log.i(
                        CarPlayConsts.TAG,
                        "CarPlay AAC packet=$packets au=${payload.size}B " +
                            "wire=${datagram.size}B ts=$ts head=$head " +
                            "pulled=${pulledBytes.get() / 1024}KB q=${auQueue.size} " +
                            "hold=${holdSends.get()} silence=${silenceSends.get()} " +
                            "realign=${realignCount.get()} peakBehindMs=${peakBehindMs.get()}" +
                            gapTag,
                    )
                }
            }
        } catch (e: Throwable) {
            if (running.get()) Log.w(CarPlayConsts.TAG, "CarPlay AAC TX ended", e)
        } finally {
            if (txSocket === socket) txSocket = null
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            Log.i(
                CarPlayConsts.TAG,
                "CarPlay AAC TX thread done packets=$packets " +
                    "hold=${holdSends.get()} silence=${silenceSends.get()} " +
                    "realign=${realignCount.get()} peakBehindMs=${peakBehindMs.get()}",
            )
        }
    }

    /** MediaCodec sometimes prepends a 7/9-byte ADTS header; CarPlay wants raw AAC. */
    private fun stripAdtsIfPresent(au: ByteArray): ByteArray {
        if (au.size < 7) return au
        val b0 = au[0].toInt() and 0xff
        val b1 = au[1].toInt() and 0xff
        if (b0 != 0xff || (b1 and 0xf0) != 0xf0) return au
        val protectionAbsent = (b1 and 0x01) != 0
        val hdr = if (protectionAbsent) 7 else 9
        if (au.size <= hdr) return au
        return au.copyOfRange(hdr, au.size)
    }

    private class AacEncoder(
        private val sampleRate: Int,
        private val channels: Int,
        private val bitRate: Int,
    ) {
        private val bufferInfo = MediaCodec.BufferInfo()
        private var codec: MediaCodec? = null
        private var queuedSamples = 0L
        private var closed = false
        private val framePcmBytes = SAMPLES_PER_FRAME * channels * 2
        /** AUs produced ahead of the 1:1 PCM→AU call (encoder delay / burst). */
        private val pendingAus = ArrayDeque<ByteArray>()

        fun start(): Boolean {
            return try {
                try {
                    startWithProfile(lc = true, cbr = true)
                } catch (_: Throwable) {
                    release()
                    try {
                        startWithProfile(lc = true, cbr = false)
                    } catch (_: Throwable) {
                        release()
                        startWithProfile(lc = false, cbr = false)
                    }
                }
                Log.i(
                    CarPlayConsts.TAG,
                    "AAC encoder ${codec?.name} rate=$sampleRate ch=$channels br=$bitRate",
                )
                true
            } catch (e: Throwable) {
                Log.w(CarPlayConsts.TAG, "AAC encoder start failed", e)
                close()
                false
            }
        }

        private fun startWithProfile(lc: Boolean, cbr: Boolean) {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channels,
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, framePcmBytes)
            // Raw AAC access units (no ADTS). CarPlay type-102 expects this.
            format.setInteger(MediaFormat.KEY_IS_ADTS, 0)
            if (lc) {
                format.setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                )
            }
            if (cbr) {
                format.setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                )
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
        }

        /**
         * Feed exactly one AAC frame (1024 stereo samples). Returns one AU when
         * available (may be null while the encoder is still priming). Encode-thread only.
         */
        fun encodeExactFrame(pcm: ByteArray): ByteArray? {
            val c = codec ?: return null
            if (closed || pcm.size < framePcmBytes) return null
            // Encode thread may wait — RTP pacer is separate, so this cannot open an RTP hole.
            val inIdx = try {
                c.dequeueInputBuffer(INPUT_DEQUEUE_TIMEOUT_US)
            } catch (_: Throwable) {
                return pendingAus.removeFirstOrNull()
            }
            if (inIdx < 0) {
                // Input stuck: still emit any primed AU so TX keeps getting real frames.
                drainAvailable()
                return pendingAus.removeFirstOrNull()
            }
            val input = c.getInputBuffer(inIdx) ?: return pendingAus.removeFirstOrNull()
            input.clear()
            input.put(pcm, 0, framePcmBytes)
            c.queueInputBuffer(inIdx, 0, framePcmBytes, ptsUs(), 0)
            queuedSamples += SAMPLES_PER_FRAME
            val deadline = System.nanoTime() + ENCODE_DRAIN_BUDGET_NS
            while (!closed && System.nanoTime() < deadline) {
                val produced = drainAvailable()
                if (produced == 0 && pendingAus.isNotEmpty()) break
                if (produced == 0) {
                    try {
                        Thread.sleep(0, 200_000) // 0.2 ms
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
            drainAvailable()
            val first = pendingAus.removeFirstOrNull()
            if (first != null) return first
            // Spike: give the codec one more short drain window before returning null
            // so the encode loop does not leave the RTP pacer without an AU.
            val retryDeadline = System.nanoTime() + ENCODE_RETRY_BUDGET_NS
            while (!closed && System.nanoTime() < retryDeadline) {
                if (drainAvailable() > 0) break
                try {
                    Thread.sleep(0, 200_000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            return pendingAus.removeFirstOrNull()
        }

        /** Non-blocking peek of a previously drained AU (encode-thread only). */
        fun pollPendingAu(): ByteArray? {
            if (closed) return null
            drainAvailable()
            return pendingAus.removeFirstOrNull()
        }

        private fun drainAvailable(): Int {
            val c = codec ?: return 0
            var n = 0
            while (!closed) {
                val outIdx = try {
                    c.dequeueOutputBuffer(bufferInfo, 0L)
                } catch (_: Throwable) {
                    return n
                }
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return n
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                    outIdx < 0 -> continue
                    else -> {
                        try {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                continue
                            }
                            if (bufferInfo.size <= 0) continue
                            val output = c.getOutputBuffer(outIdx) ?: continue
                            val au = ByteArray(bufferInfo.size)
                            output.position(bufferInfo.offset)
                            output.limit(bufferInfo.offset + bufferInfo.size)
                            output.get(au)
                            pendingAus.addLast(au)
                            n++
                        } finally {
                            c.releaseOutputBuffer(outIdx, false)
                        }
                    }
                }
            }
            return n
        }

        private fun ptsUs(): Long = (queuedSamples * 1_000_000L) / sampleRate

        fun close() {
            if (closed) return
            closed = true
            pendingAus.clear()
            release()
        }

        private fun release() {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            codec = null
        }
    }

    companion object {
        private const val CHANNELS = 2
        private const val BITRATE = 192_000
        private const val SAMPLES_PER_FRAME = 1024
        private const val RTP_PT = 102
        private const val MAX_AU = 1400
        /** ~85 ms @ 48 kHz. Above this, encode back-pressures instead of bursting. */
        private const val MAX_QUEUED_AUS = 4
        /** Target cushion ahead of the RTP pacer (~42 ms @ 48 kHz). */
        private const val TARGET_QUEUED_AUS = 2
        /**
         * Repeat the last real AU for this many empty pacer slots before falling
         * back to the silence AU (~64 ms @ 48 kHz).
         */
        private const val HOLD_GAP_FRAMES = 3
        /** Encode-thread only — can wait longer without opening an RTP hole. */
        private const val INPUT_DEQUEUE_TIMEOUT_US = 20_000L
        /** Budget to wait for one AU after queueing a full PCM frame. */
        private const val ENCODE_DRAIN_BUDGET_NS = 12_000_000L
        /** Extra drain after a spike that produced no AU in the primary budget. */
        private const val ENCODE_RETRY_BUDGET_NS = 8_000_000L
        /** ~500ms @ 48 kHz — snap clock + async reclaim only past this. */
        private const val REALIGN_FRAMES = 24

        const val AUDIO_FORMAT_AAC_LC_44100_STEREO = 4_194_304L // 0x400000
        const val AUDIO_FORMAT_AAC_LC_48000_STEREO = 8_388_608L // 0x800000
        const val STREAM_TYPE = 102L
        const val AUDIO_LATENCY_MS = 80L

        fun open(
            host: InetAddress,
            setupResponse: RtspMessage,
            sharedSecret: ByteArray,
            streamConnectionId: Long,
            sampleRate: Int,
            formatBit: Long,
        ): CarPlayAacAudioEgress? {
            val dict = BinaryPlist.asDict(BinaryPlist.decode(setupResponse.body))
            val streams = dict?.get("streams") as? List<*>
            val first = BinaryPlist.asDict(streams?.firstOrNull())
            val dataPort = BinaryPlist.intOf(first?.get("dataPort"))?.toInt()?.and(0xffff) ?: 0
            val controlPort = BinaryPlist.intOf(first?.get("controlPort"))?.toInt()?.and(0xffff) ?: 0
            if (dataPort <= 0) {
                Log.w(CarPlayConsts.TAG, "AAC audio SETUP missing dataPort dict=$dict")
                return null
            }
            val key = HkdfSha512.derive(
                sharedSecret,
                "DataStream-Salt${java.lang.Long.toUnsignedString(streamConnectionId)}",
                "DataStream-Output-Encryption-Key",
                32,
            )
            Log.i(
                CarPlayConsts.TAG,
                "CarPlay AAC SETUP ok dataPort=$dataPort controlPort=$controlPort " +
                    "format=0x${formatBit.toString(16)} rate=$sampleRate key=${key.size}B",
            )
            return CarPlayAacAudioEgress(host, dataPort, controlPort, key, sampleRate, formatBit)
        }
    }
}
