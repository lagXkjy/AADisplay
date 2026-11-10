package io.github.nitsuya.aa.display.carplay

import android.os.Process
import android.util.Log
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.carplay.protocol.BinaryPlist
import io.github.nitsuya.aa.display.carplay.protocol.HkdfSha512
import io.github.nitsuya.aa.display.carplay.protocol.RtspMessage
import io.github.nitsuya.aa.display.util.AudioPullRing
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * CarPlay realtime audio egress (SETUP type 100).
 *
 * Two profiles against DiPlay:
 * - **media** — `audioType=media`, bit **0x8000** = PCM/48000/16/2
 * - **speech** — `audioType=speech`, bit **0x10** = PCM/16000/16/1 (nav / voice prompts)
 *
 * Wire: UDP datagram = 12-byte RTP + ChaCha20-Poly1305 ciphertext+tag + 8-byte LE nonce;
 * AAD = RTP bytes 4..12 (timestamp+SSRC). LPCM samples are big-endian on the wire.
 *
 * Content from system_server [io.github.nitsuya.aa.display.xposed.carplay.CarPlayAudioCapture]
 * via [CoreApi.pullCarPlayAudio] (media) / [CoreApi.pullCarPlaySpeechAudio] (nav/speech).
 * Rings are exclusive — never feed the same PCM to both. Gaps send silence.
 */
class CarPlayAudioEgress(
    private val host: InetAddress,
    private val dataPort: Int,
    val controlPort: Int,
    private val key: ByteArray,
    private val profile: Profile,
) {
    private val running = AtomicBoolean(false)
    @Volatile
    private var worker: Thread? = null
    @Volatile
    private var txSocket: DatagramSocket? = null

    /**
     * App-local drop-to-live ring in front of the system capture ring: the puller
     * keeps the Binder round trips off the RTP pacing thread.
     */
    private val bytesPerMs = (profile.sampleRate * profile.channels * 2L / 1000L).toInt()
    private val ring = AudioPullRing(
        "cp-${profile.label}",
        capacityBytes = bytesPerMs * 600,
        highWatermarkBytes = bytesPerMs * 300,
        lowWatermarkBytes = bytesPerMs * 50,
    ) { profile.pull(it) }

    val isRunning: Boolean
        get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        // Re-assert capture arm (session live already arms; this recovers after
        // MediaProjection onStop). Do not pair with disable on stop — DiPlay soft
        // reconnect TEARDOWNs UDP briefly and must not unmute the phone speaker.
        activeEgresses.incrementAndGet()
        try {
            CoreApi.setCarPlayAudioEgressEnabled(true)
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "setCarPlayAudioEgressEnabled(true) failed — silence expected", e)
        }
        ring.start()
        worker = thread(name = "carplay-audio-tx-${profile.label}", isDaemon = true) { pump() }
        Log.i(
            CarPlayConsts.TAG,
            "CarPlay ${profile.label} audio TX started → $host:$dataPort (control=$controlPort)",
        )
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // Close UDP so send() cannot block the TX thread after TEARDOWN; interrupt
        // alone does not cancel an in-flight Binder pull.
        try {
            txSocket?.close()
        } catch (_: Throwable) {
        }
        txSocket = null
        ring.stop()
        worker?.interrupt()
        worker = null
        activeEgresses.decrementAndGet()
        // Capture stays armed while CarPlayProjectionService reports session live;
        // reportCarPlaySessionLive(false) is the only disarm path for soft reconnect.
        Log.i(CarPlayConsts.TAG, "CarPlay ${profile.label} audio TX stopped")
    }

    private fun pump() {
        // Same process hosts GL compose / video encode / screen TCP — a single
        // scheduling dip >20 ms must not land in the audio cadence.
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
        }
        val socket = CarPlayAccessoryNet.openUdp()
        txSocket = socket
        val target = InetSocketAddress(host, dataPort)
        val rtp = ByteArray(12)
        val ssrc = profile.ssrc
        var sequence = 0
        var nonceCounter = 0L
        val framesPerPacket = (profile.sampleRate * PACKET_INTERVAL_MS / 1000L).toInt()
        val bytesPerPacket = framesPerPacket * profile.channels * 2
        val payload = ByteArray(bytesPerPacket)
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        val aad = ByteArray(8)
        var packets = 0
        var pulledTotal = 0L
        var pullFailLogged = false
        var lastShortLogMs = 0L
        var lastMildLateLogMs = 0L
        CarPlayMediaClock.ensureRunning()
        // Seed RTP from the shared media clock (same epoch as screen NTP), then advance
        // by exactly one packet of samples. Wall-clock RTP on every packet makes DiPlay
        // insert silence whenever the TX thread runs late (resync / Binder stall).
        var rtpTimestamp = CarPlayMediaClock.rtpTimestamp(profile.sampleRate)
        // Session-live arms capture before RTSP audio SETUP; the ring fills to LIVE_*
        // while phone mute is already on. Flush that pre-TX backlog so DiPlay does not
        // start ~100–200ms behind the phone tap.
        val flushed = ring.flushUpstream()
        if (flushed > 0) {
            Log.i(
                CarPlayConsts.TAG,
                "CarPlay ${profile.label} audio flushed pre-TX backlog=${flushed / 1024}KB",
            )
        }
        // Absolute cadence: at most one packet per interval. Mild "catch-up" bursts used
        // to fire several UDP packets in one wall slice and DiPlay's jitter FIFO grew
        // without bound (queue=192 ≈ 3.8s, drop-newest keeps stale PCM).
        var nextPacketAt = System.nanoTime()
        // Media only: DiPlay starts AudioTrack on the first RTP, then createTrack() runs
        // on its worker while we would still be flooding — UDP+queue pin at 192 forever.
        // Arm with one packet, pause so createTrack can finish, then under-send briefly
        // to bleed any residue (we cannot change the HU).
        val paceHuStartup = profile.audioType == "media"
        var huSettlePending = paceHuStartup
        var softPaceUntilNs = 0L
        try {
            while (running.get()) {
                val intervalNs =
                    if (softPaceUntilNs != 0L && System.nanoTime() < softPaceUntilNs) {
                        SOFT_PACE_INTERVAL_NS
                    } else {
                        PACKET_INTERVAL_NS
                    }
                val now = System.nanoTime()
                val sleepNs = nextPacketAt - now
                if (sleepNs > 0L) {
                    val ms = sleepNs / 1_000_000L
                    val ns = (sleepNs % 1_000_000L).toInt()
                    Thread.sleep(ms, ns)
                } else if (-sleepNs >= RESYNC_LATE_NS) {
                    // Past the declared audioLatencyMs budget: buffered content is
                    // stale — drop it (the system ring is already drop-to-live) and
                    // realign the pacer clock.
                    Log.i(
                        CarPlayConsts.TAG,
                        "CarPlay ${profile.label} audio cadence drop-live " +
                            "behindMs=${-sleepNs / 1_000_000L} " +
                            "packet=$packets pulled=${pulledTotal / 1024}KB",
                    )
                    ring.clear()
                    nextPacketAt = System.nanoTime()
                } else if (sleepNs < -intervalNs) {
                    // 1–3 packets late (GL / video-encode / TCP burst in this process):
                    // the ring holds at most ~60 ms of content (system ring
                    // drop-to-live) — still inside the 80 ms audioLatencyMs budget.
                    // Send ASAP without discarding; the old unconditional clear()
                    // here turned every scheduling dip into an audible glitch.
                    val t = System.currentTimeMillis()
                    if (t - lastMildLateLogMs > 2_000L) {
                        lastMildLateLogMs = t
                        Log.i(
                            CarPlayConsts.TAG,
                            "CarPlay ${profile.label} audio mild-late " +
                                "behindMs=${-sleepNs / 1_000_000L} packet=$packets — " +
                                "sending live (no drop)",
                        )
                    }
                }

                val timestamp = rtpTimestamp
                rtpTimestamp = (rtpTimestamp + framesPerPacket) and 0x7fffffff
                Arrays.fill(payload, 0.toByte())
                val got = try {
                    ring.read(payload.size, 20)
                } catch (e: Throwable) {
                    if (!pullFailLogged) {
                        pullFailLogged = true
                        Log.w(
                            CarPlayConsts.TAG,
                            "pull ${profile.label} audio failed — silence until bridge recovers",
                            e,
                        )
                    }
                    ByteArray(0)
                }
                val n = minOf(got.size, payload.size)
                if (n > 0) {
                    System.arraycopy(got, 0, payload, 0, n)
                    for (i in 0 until n / 2) {
                        val lo = payload[i * 2]
                        payload[i * 2] = payload[i * 2 + 1]
                        payload[i * 2 + 1] = lo
                    }
                    pulledTotal += n
                }
                if (n < bytesPerPacket && System.currentTimeMillis() - lastShortLogMs > 2_000L) {
                    // Short/empty read → packet is silence-padded: the local ring ran dry
                    // (puller starvation or upstream system-ring drop). Throttled marker of
                    // a phone-side supply gap — distinct from the car-side underflow/queue
                    // counters, and from the TX-late "cadence drop-live" marker.
                    lastShortLogMs = System.currentTimeMillis()
                    val deficitMs = (bytesPerPacket - n) * 1_000L / (profile.sampleRate * profile.channels * 2L)
                    Log.i(
                        CarPlayConsts.TAG,
                        "CarPlay ${profile.label} audio short read deficit=${deficitMs}ms " +
                            "avail=${ring.availableBytes}B ringDrop=${ring.droppedBytes}B " +
                            "pulled=${pulledTotal / 1024}KB",
                    )
                }
                rtp[0] = 0x80.toByte()
                rtp[1] = PAYLOAD_TYPE.toByte()
                rtp[2] = ((sequence ushr 8) and 0xff).toByte()
                rtp[3] = (sequence and 0xff).toByte()
                rtp[4] = ((timestamp ushr 24) and 0xff).toByte()
                rtp[5] = ((timestamp ushr 16) and 0xff).toByte()
                rtp[6] = ((timestamp ushr 8) and 0xff).toByte()
                rtp[7] = (timestamp and 0xff).toByte()
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
                if (packets <= 3 || packets % 500 == 0) {
                    Log.i(
                        CarPlayConsts.TAG,
                        "CarPlay ${profile.label} audio packet=$packets bytes=${datagram.size} " +
                            "ts=$timestamp clockMs=${CarPlayMediaClock.videoTimestampNs() / 1_000_000L} " +
                            "pulled=${pulledTotal / 1024}KB " +
                            "avail=${ring.availableBytes}B ringDrop=${ring.droppedBytes}B",
                    )
                }
                nextPacketAt += intervalNs

                if (huSettlePending && packets == 1) {
                    huSettlePending = false
                    Log.i(
                        CarPlayConsts.TAG,
                        "CarPlay ${profile.label} audio HU settle pause ${HU_SETTLE_MS}ms " +
                            "(DiPlay createTrack window)",
                    )
                    val settleEnd = System.nanoTime() + HU_SETTLE_MS * 1_000_000L
                    while (running.get() && System.nanoTime() < settleEnd) {
                        try {
                            ring.read(bytesPerPacket, 20)
                        } catch (_: Throwable) {
                        }
                        Thread.sleep(PACKET_INTERVAL_MS)
                    }
                    softPaceUntilNs = System.nanoTime() + SOFT_PACE_MS * 1_000_000L
                    nextPacketAt = System.nanoTime()
                    Log.i(
                        CarPlayConsts.TAG,
                        "CarPlay ${profile.label} audio soft-pace ${SOFT_PACE_MS}ms " +
                            "intervalMs=${SOFT_PACE_INTERVAL_NS / 1_000_000L}",
                    )
                }
            }
        } catch (e: Throwable) {
            if (running.get()) Log.w(CarPlayConsts.TAG, "CarPlay ${profile.label} audio TX ended", e)
        } finally {
            if (txSocket === socket) txSocket = null
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            Log.i(CarPlayConsts.TAG, "CarPlay ${profile.label} audio TX thread done packets=$packets")
        }
    }

    /**
     * Stream geometry + pull + SETUP bits for media vs speech.
     */
    class Profile(
        val label: String,
        val audioType: String,
        val audioFormatBit: Long,
        val sampleRate: Int,
        val channels: Int,
        val ssrc: Int,
        val pull: (Int) -> ByteArray,
    )

    companion object {
        private const val PACKET_INTERVAL_MS = 20L
        private const val PACKET_INTERVAL_NS = PACKET_INTERVAL_MS * 1_000_000L
        /**
         * After the first media RTP, DiPlay's audio worker is still in createTrack().
         * Pause so its queue does not fill to 192 (~3.8s) before the consumer runs.
         */
        private const val HU_SETTLE_MS = 1_200L
        /** ~10% under realtime to bleed any residual HU FIFO after settle. */
        private const val SOFT_PACE_INTERVAL_NS = PACKET_INTERVAL_NS * 11 / 10
        private const val SOFT_PACE_MS = 8_000L
        /**
         * Behind this much (~80 ms = [AUDIO_LATENCY_MS]) the buffered PCM is stale
         * and must be dropped; milder lateness sends the still-live content.
         */
        val RESYNC_LATE_NS = PACKET_INTERVAL_NS * 4
        private const val PAYLOAD_TYPE = 96
        private val activeEgresses = AtomicInteger(0)

        val MEDIA = Profile(
            label = "media",
            audioType = "media",
            audioFormatBit = AUDIO_FORMAT_PCM_48K_STEREO,
            sampleRate = 48_000,
            channels = 2,
            ssrc = 0xAAD15A01.toInt(),
            pull = { max -> CoreApi.pullCarPlayAudio(max) },
        )

        val SPEECH = Profile(
            label = "speech",
            audioType = "speech",
            audioFormatBit = AUDIO_FORMAT_PCM_16K_MONO,
            sampleRate = 16_000,
            channels = 1,
            ssrc = 0xAAD15A02.toInt(),
            pull = { max -> CoreApi.pullCarPlaySpeechAudio(max) },
        )

        /**
         * Told to DiPlay as stream buffer depth. Slightly above screen [latencyMs] so the
         * HU holds audio until the AVC pipeline (compose→encode→TCP) catches up. Keep
         * tight (near capture LIVE_LOW) — large values make the car permanently behind
         * the phone tap even when TX is live.
         */
        const val AUDIO_LATENCY_MS = 80L

        fun open(
            host: InetAddress,
            setupResponse: RtspMessage,
            sharedSecret: ByteArray,
            streamConnectionId: Long,
            profile: Profile,
        ): CarPlayAudioEgress? {
            val dict = BinaryPlist.asDict(BinaryPlist.decode(setupResponse.body))
            val streams = dict?.get("streams") as? List<*>
            val first = BinaryPlist.asDict(streams?.firstOrNull())
            val dataPort = BinaryPlist.intOf(first?.get("dataPort"))?.toInt()?.and(0xffff) ?: 0
            val controlPort = BinaryPlist.intOf(first?.get("controlPort"))?.toInt()?.and(0xffff) ?: 0
            if (dataPort <= 0) {
                Log.w(CarPlayConsts.TAG, "CarPlay ${profile.label} audio SETUP missing dataPort dict=$dict")
                return null
            }
            val key = HkdfSha512.derive(
                sharedSecret,
                "DataStream-Salt${java.lang.Long.toUnsignedString(streamConnectionId)}",
                "DataStream-Output-Encryption-Key",
                32,
            )
            val egress = CarPlayAudioEgress(host, dataPort, controlPort, key, profile)
            Log.i(
                CarPlayConsts.TAG,
                "CarPlay ${profile.label} audio SETUP ok dataPort=$dataPort controlPort=$controlPort " +
                    "format=0x${profile.audioFormatBit.toString(16)} key=${key.size}B",
            )
            return egress
        }

        /**
         * DiPlay [AudioStreamCodec.PCM_FORMAT] bit for media entertainment:
         * `0x8000` → PCM 48 kHz stereo s16.
         */
        const val AUDIO_FORMAT_PCM_48K_STEREO = 0x8000L

        /**
         * DiPlay voice / speech bit: `0x10` → PCM 16 kHz mono s16 (nav prompts, telephony-adjacent).
         */
        const val AUDIO_FORMAT_PCM_16K_MONO = 0x10L
    }
}
