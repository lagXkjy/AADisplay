package io.github.nitsuya.aa.display.aap

import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import android.os.Process
import android.util.Log
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.util.AudioPullRing
import net.prototux.aare.aap.channels.AudioChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * AAP PCM audio egress: reuses system_server [CarPlayAudioCapture] rings via
 * [CoreApi.pullCarPlayAudio] / [CoreApi.pullCarPlaySpeechAudio], pumps into HU
 * [AudioChannel] sinks. No RTP / ChaCha — protocol is AAP only.
 *
 * Media (48 kHz stereo) and guidance/system (16 kHz mono) never share the same ring.
 * Per HUIG: wait for audio-focus grant before MEDIA TX; [LOSS_TRANSIENT_CAN_DUCK] keeps MEDIA.
 */
class AapAudioEgress {
    private val running = AtomicBoolean(false)
    private val mediaFocused = AtomicBoolean(false)

    @Volatile private var mediaChannel: AudioChannel? = null
    @Volatile private var guidanceChannel: AudioChannel? = null
    @Volatile private var mediaWorker: Thread? = null
    @Volatile private var guidanceWorker: Thread? = null
    private val mediaPumping = AtomicBoolean(false)
    private val guidancePumping = AtomicBoolean(false)
    private val mediaWatchRunning = AtomicBoolean(false)
    @Volatile private var mediaWatcher: Thread? = null

    /**
     * Fired from the watcher thread when the phone is audible but the car hasn't granted
     * media focus. The session re-requests AUDIO_FOCUS_GAIN at a patient cadence.
     */
    @Volatile
    var onMediaAudible: (() -> Unit)? = null

    fun attach(channel: AudioChannel) {
        val cfg = channel.config
        if (cfg == null) {
            Log.w(AapConsts.TAG, "AapAudioEgress defer ${channel.streamType}: no config yet")
            return
        }
        bind(channel, cfg)
    }

    fun onChannelReady(channel: AudioChannel, config: AudioConfiguration) {
        bind(channel, config)
    }

    private fun bind(channel: AudioChannel, cfg: AudioConfiguration) {
        when (channel.streamType) {
            AudioStreamType.AUDIO_STREAM_MEDIA -> {
                if (!matchesMedia(cfg)) {
                    Log.w(
                        AapConsts.TAG,
                        "AapAudioEgress skip MEDIA cfg=${cfg.samplingRate}/${cfg.numberOfChannels}/" +
                            "${cfg.numberOfBits} (need ${MEDIA_RATE}/${MEDIA_CH}/${BITS})",
                    )
                    return
                }
                mediaChannel = channel
                Log.i(AapConsts.TAG, "AapAudioEgress MEDIA bound ready=${channel.ready}")
                if (running.get() && mediaFocused.get()) startMediaPump()
            }
            AudioStreamType.AUDIO_STREAM_GUIDANCE,
            AudioStreamType.AUDIO_STREAM_SYSTEM_AUDIO,
            -> {
                if (!matchesGuidance(cfg)) {
                    Log.w(
                        AapConsts.TAG,
                        "AapAudioEgress skip ${channel.streamType} cfg=${cfg.samplingRate}/" +
                            "${cfg.numberOfChannels}/${cfg.numberOfBits} " +
                            "(need ${GUIDANCE_RATE}/${GUIDANCE_CH}/${BITS})",
                    )
                    return
                }
                // Prefer GUIDANCE over SYSTEM when both are advertised.
                val cur = guidanceChannel
                if (cur != null &&
                    cur.streamType == AudioStreamType.AUDIO_STREAM_GUIDANCE &&
                    channel.streamType == AudioStreamType.AUDIO_STREAM_SYSTEM_AUDIO
                ) {
                    Log.i(AapConsts.TAG, "AapAudioEgress keep GUIDANCE; ignore SYSTEM")
                    return
                }
                val replacing = cur != null && cur !== channel
                if (replacing) stopGuidancePump()
                guidanceChannel = channel
                Log.i(AapConsts.TAG, "AapAudioEgress ${channel.streamType} bound ready=${channel.ready}")
                if (running.get()) startGuidancePump()
            }
            else -> {
                Log.i(AapConsts.TAG, "AapAudioEgress ignore streamType=${channel.streamType}")
            }
        }
    }

    /** The car's last focus word lets the MEDIA pump run (read by the focus-retry check). */
    fun hasMediaFocus(): Boolean = mediaFocused.get()

    fun onAudioFocus(state: AudioFocusStateType) {
        when (state) {
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN,
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY,
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT,
            -> {
                mediaFocused.set(true)
                Log.i(AapConsts.TAG, "AapAudioEgress focus=$state mediaGain=true")
                if (running.get()) startMediaPump()
            }
            AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK -> {
                // HUIG: MD may keep MEDIA at reduced level — keep the pump.
                mediaFocused.set(true)
                Log.i(AapConsts.TAG, "AapAudioEgress focus=$state (duck — keep MEDIA)")
                if (running.get()) startMediaPump()
            }
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY -> {
                mediaFocused.set(false)
                stopMediaPump()
                Log.i(AapConsts.TAG, "AapAudioEgress focus=$state (guidance-only)")
            }
            else -> {
                mediaFocused.set(false)
                stopMediaPump()
                Log.i(AapConsts.TAG, "AapAudioEgress focus=$state mediaGain=false")
            }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            CoreApi.setCarPlayAudioEgressEnabled(true)
        } catch (e: Throwable) {
            Log.w(AapConsts.TAG, "setCarPlayAudioEgressEnabled(true) failed", e)
        }
        if (mediaFocused.get()) startMediaPump()
        startGuidancePump()
        if (mediaWatchRunning.compareAndSet(false, true)) {
            mediaWatcher = thread(name = "aap-audio-watch", isDaemon = true) { runMediaWatch() }
        }
        Log.i(AapConsts.TAG, "AapAudioEgress started")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        stopMediaPump()
        stopGuidancePump()
        if (mediaWatchRunning.compareAndSet(true, false)) {
            mediaWatcher?.interrupt()
            mediaWatcher = null
        }
        mediaChannel = null
        guidanceChannel = null
        mediaFocused.set(false)
        Log.i(AapConsts.TAG, "AapAudioEgress stopped")
    }

    /**
     * The HUIG focus dance is one-shot: once the car takes the focus back (radio, a call)
     * many head units never re-grant it when the user switches the source back to Android
     * Auto — the MEDIA pump (and the car's sound) stays off until a full reconnect. Re-ask
     * instead: the phone keeps rendering its music (the capture tap is the speaker mix),
     * so audible content in the [MEDIA] system ring means we still have audio to play.
     */
    private fun runMediaWatch() {
        while (mediaWatchRunning.get() && running.get()) {
            try {
                Thread.sleep(MEDIA_WATCH_TICK_MS)
            } catch (_: InterruptedException) {
                break
            }
            if (!mediaWatchRunning.get() || !running.get() || mediaFocused.get()) continue
            val got = try {
                MEDIA.pull(MEDIA_WATCH_PULL_BYTES)
            } catch (_: InterruptedException) {
                break
            } catch (_: Throwable) {
                ByteArray(0)
            }
            if (peakSample(got) < AUDIBLE_PEAK) continue
            onMediaAudible?.invoke()
        }
    }

    /** Max |s16| over a little-endian PCM buffer (interleaved channels both covered). */
    private fun peakSample(pcm: ByteArray): Int {
        var peak = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = pcm[i].toInt() or (pcm[i + 1].toInt() shl 8)
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            i += 2
        }
        return peak
    }

    private fun startMediaPump() {
        val ch = mediaChannel ?: return
        if (!running.get() || !mediaFocused.get()) return
        if (!ch.ready) {
            Log.i(AapConsts.TAG, "AapAudioEgress MEDIA wait channel ready")
            return
        }
        if (!mediaPumping.compareAndSet(false, true)) return
        MEDIA.ring.start()
        try {
            ch.startPlayback()
        } catch (e: Throwable) {
            mediaPumping.set(false)
            Log.w(AapConsts.TAG, "MEDIA startPlayback failed", e)
            return
        }
        if (!ch.started) {
            mediaPumping.set(false)
            Log.w(AapConsts.TAG, "MEDIA startPlayback no-op (not started)")
            return
        }
        flushPreTx(MEDIA)
        mediaWorker = thread(name = "aap-audio-media", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            pump(MEDIA, ch, mediaPumping)
        }
    }

    private fun stopMediaPump() {
        if (!mediaPumping.compareAndSet(true, false)) return
        MEDIA.ring.stop()
        mediaWorker?.interrupt()
        mediaWorker = null
        runCatching { mediaChannel?.stopPlayback() }
    }

    private fun startGuidancePump() {
        val ch = guidanceChannel ?: return
        if (!running.get()) return
        if (!ch.ready) {
            Log.i(AapConsts.TAG, "AapAudioEgress GUIDANCE wait channel ready")
            return
        }
        if (!guidancePumping.compareAndSet(false, true)) return
        GUIDANCE.ring.start()
        try {
            ch.startPlayback()
        } catch (e: Throwable) {
            guidancePumping.set(false)
            Log.w(AapConsts.TAG, "GUIDANCE startPlayback failed", e)
            return
        }
        if (!ch.started) {
            guidancePumping.set(false)
            Log.w(AapConsts.TAG, "GUIDANCE startPlayback no-op (not started)")
            return
        }
        flushPreTx(GUIDANCE)
        guidanceWorker = thread(name = "aap-audio-guidance", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            pump(GUIDANCE, ch, guidancePumping)
        }
    }

    private fun stopGuidancePump() {
        if (!guidancePumping.compareAndSet(true, false)) return
        GUIDANCE.ring.stop()
        guidanceWorker?.interrupt()
        guidanceWorker = null
        runCatching { guidanceChannel?.stopPlayback() }
    }

    private fun pump(profile: Profile, channel: AudioChannel, flag: AtomicBoolean) {
        val framesPerPacket = (profile.sampleRate * PACKET_INTERVAL_MS / 1000L).toInt()
        val bytesPerPacket = framesPerPacket * profile.channels * 2
        var packets = 0
        var pulledTotal = 0L
        var nextPacketAt = System.nanoTime()
        var pullFailLogged = false
        var lastShortLogMs = 0L
        try {
            while (flag.get() && running.get()) {
                val now = System.nanoTime()
                val sleepNs = nextPacketAt - now
                if (sleepNs > 0L) {
                    val ms = sleepNs / 1_000_000L
                    val ns = (sleepNs % 1_000_000L).toInt()
                    Thread.sleep(ms, ns)
                } else if (sleepNs < -PACKET_INTERVAL_NS) {
                    // Drop the backlog locally; the system ring is already drop-to-live.
                    profile.ring.clear()
                    nextPacketAt = System.nanoTime()
                }

                // Fresh buffer each packet: AudioChannel.sendData may queue the array without copy
                // when offset headroom starts at 0 (see MediaSinkChannel.sendData).
                val packet = ByteArray(10 + bytesPerPacket)
                val got = try {
                    profile.ring.read(bytesPerPacket, 20)
                } catch (e: Throwable) {
                    if (!pullFailLogged) {
                        pullFailLogged = true
                        Log.w(AapConsts.TAG, "pull ${profile.label} failed — silence", e)
                    }
                    ByteArray(0)
                }
                val n = minOf(got.size, bytesPerPacket)
                if (n < bytesPerPacket && System.currentTimeMillis() - lastShortLogMs > 2_000L) {
                    // Short/empty read → the packet is silence-padded: the app ring ran dry
                    // (puller starvation or upstream drop). Throttled: this is the marker of a
                    // phone-side supply gap, distinct from the car-side underflow/dropped counters.
                    lastShortLogMs = System.currentTimeMillis()
                    val deficitMs = (bytesPerPacket - n) * 1_000L / (profile.sampleRate * profile.channels * 2L)
                    Log.i(
                        AapConsts.TAG,
                        "AapAudio ${profile.label} short read deficit=${deficitMs}ms " +
                            "avail=${profile.ring.availableBytes}B ringDrop=${profile.ring.droppedBytes}B " +
                            "underflow=${channel.underflows} dropped=${channel.droppedChunks}",
                    )
                }
                if (n > 0) {
                    System.arraycopy(got, 0, packet, 10, n)
                    pulledTotal += n
                }
                val tsUs = System.nanoTime() / 1_000L
                channel.sendPcm(packet, 10, bytesPerPacket, tsUs)
                packets++
                if (packets <= 3 || packets % 500 == 0) {
                    Log.i(
                        AapConsts.TAG,
                        "AapAudio ${profile.label} pkt=$packets pulled=${pulledTotal / 1024}KB " +
                            "underflow=${channel.underflows} dropped=${channel.droppedChunks} " +
                            "avail=${profile.ring.availableBytes}B ringDrop=${profile.ring.droppedBytes}B",
                    )
                }
                nextPacketAt += PACKET_INTERVAL_NS
            }
        } catch (_: InterruptedException) {
            // stop
        } catch (e: Throwable) {
            if (flag.get()) Log.w(AapConsts.TAG, "AapAudio ${profile.label} pump ended", e)
        } finally {
            flag.set(false)
            Log.i(AapConsts.TAG, "AapAudio ${profile.label} pump done packets=$packets")
        }
    }

    private class Profile(
        val label: String,
        val sampleRate: Int,
        val channels: Int,
        val pull: (Int) -> ByteArray,
    ) {
        /**
         * App-local drop-to-live ring in front of the system capture ring: the
         * puller keeps the Binder round trips off the 20 ms pacing loop.
         */
        val ring = AudioPullRing(
            "aap-$label",
            capacityBytes = (sampleRate * channels * 2L / 1000L).toInt() * 600,
            // Tight latency budget: content older than this is shipped anyway (video is
            // latest-frame), so a fat ring only adds steady A/V lag. 100 ms high / 20 ms low.
            highWatermarkBytes = (sampleRate * channels * 2L / 1000L).toInt() * 100,
            lowWatermarkBytes = (sampleRate * channels * 2L / 1000L).toInt() * 20,
            pull,
        )
    }

    companion object {
        private const val PACKET_INTERVAL_MS = 20L
        private const val PACKET_INTERVAL_NS = PACKET_INTERVAL_MS * 1_000_000L
        private const val BITS = 16
        private const val MEDIA_RATE = 48_000
        private const val MEDIA_CH = 2
        private const val GUIDANCE_RATE = 16_000
        private const val GUIDANCE_CH = 1
        /** Watcher loop period; the re-ask cadence itself is enforced by the session. */
        private const val MEDIA_WATCH_TICK_MS = 2_000L
        /** The system ring is drop-to-live (≤100 ms); this cap pulls whatever it holds. */
        private const val MEDIA_WATCH_PULL_BYTES = 64 * 1024
        /** ≈ -40 dBFS: real playback sits far above, silence/ground noise far below. */
        private const val AUDIBLE_PEAK = 32

        private val MEDIA = Profile(
            label = "media",
            sampleRate = MEDIA_RATE,
            channels = MEDIA_CH,
            pull = { max -> CoreApi.pullCarPlayAudio(max) },
        )
        private val GUIDANCE = Profile(
            label = "guidance",
            sampleRate = GUIDANCE_RATE,
            channels = GUIDANCE_CH,
            pull = { max -> CoreApi.pullCarPlaySpeechAudio(max) },
        )

        private fun matchesMedia(c: AudioConfiguration): Boolean =
            c.samplingRate == MEDIA_RATE &&
                c.numberOfChannels == MEDIA_CH &&
                c.numberOfBits == BITS

        private fun matchesGuidance(c: AudioConfiguration): Boolean =
            c.samplingRate == GUIDANCE_RATE &&
                c.numberOfChannels == GUIDANCE_CH &&
                c.numberOfBits == BITS

        private fun flushPreTx(profile: Profile) {
            val dropped = profile.ring.flushUpstream()
            if (dropped > 0) {
                Log.i(AapConsts.TAG, "AapAudio ${profile.label} flushed pre-TX ${dropped / 1024}KB")
            }
        }
    }
}
