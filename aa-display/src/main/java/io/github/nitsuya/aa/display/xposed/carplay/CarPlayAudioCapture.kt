package io.github.nitsuya.aa.display.xposed.carplay

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * system_server tap that feeds **two exclusive** CarPlay audio streams:
 * - **media** — entertainment (SETUP `audioType=media`, 48 kHz stereo) via [pull]
 * - **speech** — navigation / assistant (SETUP `audioType=speech`, 16 kHz mono) via [pullSpeech]
 *
 * Separation rules (DiPlay mixes both; duplicate PCM = echo):
 * - Media is unfiltered [MediaRecorder.AudioSource.REMOTE_SUBMIX] (speaker-mix tap).
 *   This is the scheme that still hears 高德 TTS: those tracks bake
 *   [android.media.AudioAttributes.FLAG_NO_MEDIA_PROJECTION] while using USAGE_MEDIA, so
 *   AudioPolicy RENDER / MediaProjection APC succeed yet exclude them (music OK,
 *   nav silent). The render-arm helper in [CarPlayAudioPolicy] stays unused — do
 *   not re-arm RENDER (no deferred / in-place upgrade).
 * - Speech is AudioPolicy [AudioMix] for nav/assistant only when that mix opens;
 *   never a second remotesubmix (echo). Gaode TTS rides the media ring.
 *
 * Mute: remotesubmix → **no** mute (device mute zeros the tap on some OEMs). Mute
 * state is owned by [CarPlayPhoneMute] and released on disarm with restore.
 *
 * Scope: phone→car **playback** (media, nav, ringtone, call downlink). Car mic
 * uplink / full CarPlay telephony protocol is out of scope here.
 *
 * Arm follows [reportCarPlaySessionLive] true (HU dial / screen SETUP).
 * Disarm + volume restore follows live=false: FGS tearDown, or HU media actually
 * gone (peer close / TEARDOWN) after a short debounce so a soft reconnect that
 * SETUPs again does not flash the phone speaker. UDP-only egress stop does not disarm.
 *
 * Split map (same package):
 * - [CarPlayAudioFormats] — stream formats / ring sizes / usages
 * - [CarPlayPcmRing] — SPSC ring with drop-to-live watermarks
 * - [CarPlayPhoneMute] — phone sink mute state + sticky enforce thread
 * - [CarPlayAudioPolicy] — AudioMix / AudioPolicy reflection (speech arm; render arm unused)
 * - [CarPlayMediaProjectionAcquire] — MediaProjection acquisition ladder (speech register)
 */
object CarPlayAudioCapture {
    private const val TAG = "AAD_CarPlayAudioCap"

    private const val WATCHDOG_PERIOD_MS = 30_000L
    private const val PULL_TIMEOUT_MS = 5 * 60_000L
    /** Floor between projection-stop / pull-stall rearms while session still desired. */
    private const val REARM_MIN_GAP_MS = 8_000L
    /**
     * remote-submix overflow over one watchdog window that triggers a force rearm
     * to rebuild the stuck tap (~50 KB ≈ 260 ms of stereo PCM @ 48 kHz).
     */
    private const val REMOTE_SUBMIX_DROP_REARM_BYTES = 50L * 1024

    private val opLock = Any()
    /** FGS / AIDL still wants capture — survives soft reconnect and transient tap death. */
    private val desired = AtomicBoolean(false)
    private val armed = AtomicBoolean(false)
    private val lastPullMs = AtomicLong(0L)
    private val lastRearmMs = AtomicLong(0L)
    /** mediaRing.dropped snapshot from the previous watchdog tick. */
    private val lastWatchdogDropMedia = AtomicLong(0L)
    /** Reason of the in-flight [rearmIfDesired] (null on first cold [enableLocked]). */
    @Volatile private var lastRearmReason: String? = null

    private val mediaRing = CarPlayPcmRing(
        CarPlayAudioFormats.MEDIA_RING_BYTES,
        CarPlayAudioFormats.MEDIA_HIGH_WATERMARK_BYTES,
        CarPlayAudioFormats.MEDIA_LOW_WATERMARK_BYTES,
    )
    private val speechRing = CarPlayPcmRing(
        CarPlayAudioFormats.SPEECH_RING_BYTES,
        CarPlayAudioFormats.SPEECH_HIGH_WATERMARK_BYTES,
        CarPlayAudioFormats.SPEECH_LOW_WATERMARK_BYTES,
    )

    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    /** Phone sink mute engine (stream / device index / enforce thread / volume receiver). */
    @SuppressLint("StaticFieldLeak")
    private var mute: CarPlayPhoneMute? = null
    private var mediaRecord: AudioRecord? = null
    private var speechRecord: AudioRecord? = null
    private var audioPolicy: Any? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var readerRunning = AtomicBoolean(false)
    @Volatile
    private var mediaReaderThread: Thread? = null
    @Volatile
    private var speechReaderThread: Thread? = null
    @Volatile private var mediaMode: String = "none"
    @Volatile private var speechMode: String = "none"
    private var totalMediaPulled = 0L
    private var totalSpeechPulled = 0L
    /** Peak |s16| seen by media reader since arm (0 ⇒ likely muted tap). */
    private val mediaPcmPeak = AtomicInteger(0)

    fun install(context: Context) {
        synchronized(opLock) {
            if (audioManager != null) return
            val ctx = context.applicationContext ?: context
            appContext = ctx
            val am = context.getSystemService(AudioManager::class.java)
            audioManager = am
            // Gate lambdas close over capture state; the engine itself never takes opLock.
            mute = CarPlayPhoneMute(
                context = ctx,
                audioManager = am,
                liveGate = { armed.get() && mediaMode == "playback-capture" },
                holdGate = { desired.get() },
            )
            logDebug(TAG, "installed (idle)")
        }
    }

    fun setEnabled(enabled: Boolean) {
        var reassertMute = false
        // Tap already down (rearm gap / enable failed) but stream mute still held.
        // enabled==armed used to return here and leave setStreamMute until reboot.
        var restoreWhileIdle = false
        synchronized(opLock) {
            desired.set(enabled)
            // Already armed: still re-assert phone mute (OEM may restore MUSIC on playback).
            if (enabled && armed.get()) {
                if (mediaMode == "playback-capture") {
                    // Mute thread can die while capture stays armed (uncaught OEM
                    // AudioManager throw / interrupt). Restart before one-shot enforce.
                    mute?.ensureMuteEnforceLocked()
                    reassertMute = true
                }
                // Fall through: enforce outside opLock (see below).
            } else if (enabled == armed.get()) {
                restoreWhileIdle = !enabled &&
                    (mute?.phoneMuted == true || mute?.speakerDeviceMuted == true)
            } else {
                // AIDL arrives with the app uid; MediaProjection / REMOTE_SUBMIX / AudioPolicy
                // all check Binder.getCallingUid() for CAPTURE_AUDIO_OUTPUT /
                // MANAGE_MEDIA_PROJECTION. Drop to system identity for the arm window.
                val identity = Binder.clearCallingIdentity()
                try {
                    if (enabled) {
                        enableLocked()
                    } else {
                        disableLocked(restoreAudio = true)
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity)
                }
                return
            }
        }
        if (restoreWhileIdle) {
            val identity = Binder.clearCallingIdentity()
            try {
                if (mute != null) {
                    synchronized(opLock) {
                        if (!desired.get() && !armed.get()) {
                            mute?.clearHeldMute("setEnabled(false)-idle")
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
        if (reassertMute) {
            val identity = Binder.clearCallingIdentity()
            try {
                // Do not hold opLock across setStreamVolume — VOLUME_CHANGED receiver
                // used to re-enter opLock and deadlock / kill the mute thread.
                mute?.enforceNow()
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
    }

    // ------------------------------------------------------------------
    // Arm / disarm
    // ------------------------------------------------------------------

    private fun enableLocked() {
        val am = audioManager ?: run {
            log(TAG, "setEnabled(true) before install — ignored")
            return
        }
        val ctx = appContext ?: run {
            log(TAG, "setEnabled(true) without context — ignored")
            return
        }
        pruneArmingStateLocked()

        val threads = HandlerThread("aad-cp-audio-capture").also { it.start() }
        val handler = Handler(threads.looper)
        var mediaRec: AudioRecord? = null
        var speechRec: AudioRecord? = null
        var policy: Any? = null
        var mMode = "none"
        var sMode = "none"
        try {
            // REMOTE_SUBMIX only (rolled back from RENDER/APC). RENDER was excluding
            // FLAG_NO_MEDIA_PROJECTION players (高德 16 kHz USAGE_MEDIA TTS) while still
            // capturing normal music — car heard media, never nav. Remotesubmix taps the
            // speaker mix and still includes those tracks. Never upgrade away.
            mediaRec = openRemoteSubmixRecord(
                CarPlayAudioFormats.MEDIA_SAMPLE_RATE,
                CarPlayAudioFormats.MEDIA_CHANNEL_MASK,
            )
            mMode = "remote-submix"
            // Optional speech tap for true NAVIGATION/ASSISTANT usages only. Do not open
            // a second remotesubmix (same PCM on media+speech = DiPlay echo). Gaode TTS
            // is USAGE_MEDIA and stays on the media ring above.
            val speechPair = openSpeechRecord(ctx)
            speechRec = speechPair.first
            policy = speechPair.second
            sMode = speechPair.third

            mediaRec = startMediaRecordLocked(mediaRec, mMode)
            val speechResult = validateSpeechRecordLocked(am, speechRec, policy, sMode)
            speechRec = speechResult.first
            policy = speechResult.second
            sMode = speechResult.third

            mediaRecord = mediaRec
            speechRecord = speechRec
            audioPolicy = policy
            captureThread = threads
            captureHandler = handler
            mediaMode = mMode
            speechMode = sMode
            armed.set(true)
            lastPullMs.set(System.currentTimeMillis())
            // Start readers BEFORE mute so the first packs are not racing a silent mix.
            startMediaReaderLocked(mediaRec, mMode)
            speechRec?.let { startSpeechReaderLocked(it) }
            applyArmMuteLocked(mMode)

            log(
                TAG,
                "armed: media=$mMode speech=$sMode usagesMedia=${CarPlayAudioFormats.MEDIA_CAPTURE_USAGES.toList()} " +
                    "usagesSpeech=${CarPlayAudioFormats.SPEECH_CAPTURE_USAGES.toList()} phoneMuted=${mute?.phoneMuted == true} " +
                    "speakerDeviceMuted=${mute?.speakerDeviceMuted == true} (vols=${mute?.savedVolumes?.toList()})",
            )
            // Stuck-tap recovery is force rearm only (no upgrade path exists).
            scheduleWatchdog(handler)
        } catch (e: Throwable) {
            log(TAG, "enable failed — staying idle", e)
            runCatching { mediaRec?.release() }
            runCatching { speechRec?.release() }
            CarPlayAudioPolicy.unregisterAudioPolicy(am, policy)
            threads.quitSafely()
            mediaRecord = null
            speechRecord = null
            audioPolicy = null
            captureThread = null
            captureHandler = null
            mediaMode = "none"
            speechMode = "none"
            armed.set(false)
            // Keep mute if session still desired; retry after gap (no capture looper).
            if (desired.get()) {
                Thread(
                    {
                        try {
                            Thread.sleep(REARM_MIN_GAP_MS)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                        if (desired.get() && !armed.get()) {
                            lastRearmMs.set(0L)
                            rearmIfDesired("enable-failed")
                        }
                    },
                    "aad-cp-audio-rearm-retry",
                ).also {
                    it.isDaemon = true
                    it.start()
                }
            }
        }
    }

    /** Reset per-session counters; keep mute tiers held from a prior arm across rearm. */
    private fun pruneArmingStateLocked() {
        mediaRing.clear()
        speechRing.clear()
        totalMediaPulled = 0L
        totalSpeechPulled = 0L
        mediaPcmPeak.set(0)
        lastWatchdogDropMedia.set(0L)
        mute?.pruneUnheldState()
    }

    /** Validate + start the media tap; throws on a failed start (caller rolls back). */
    private fun startMediaRecordLocked(
        rec: AudioRecord?,
        mode: String,
    ): AudioRecord {
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            val st = rec?.state ?: -1
            throw IllegalStateException(
                "media AudioRecord not initialized (mode=$mode state=$st)",
            )
        }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("media AudioRecord failed to start (mode=$mode)")
        }
        return rec
    }

    /**
     * Validate + start the speech tap; on failure drop it (and its standalone
     * AudioPolicy).
     */
    private fun validateSpeechRecordLocked(
        am: AudioManager,
        rec: AudioRecord?,
        policy: Any?,
        sMode: String,
    ): Triple<AudioRecord?, Any?, String> {
        if (rec == null) return Triple(null, policy, sMode)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            log(TAG, "speech AudioRecord not initialized (mode=$sMode) — speech silence")
            runCatching { rec.release() }
            CarPlayAudioPolicy.unregisterAudioPolicy(am, policy)
            return Triple(null, null, "none")
        }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            log(TAG, "speech AudioRecord failed to start (mode=$sMode) — speech silence")
            runCatching { rec.release() }
            CarPlayAudioPolicy.unregisterAudioPolicy(am, policy)
            return Triple(null, null, "none")
        }
        return Triple(rec, policy, sMode)
    }

    /**
     * Arm-phase mute policy per media mode.
     * Kill phone-side sinks only for playback-capture (copy still hits speaker).
     * remotesubmix: do NOT mute — Samsung device mute zeros the tap; also release
     * a mute held from a prior arm.
     */
    private fun applyArmMuteLocked(mMode: String) {
        when (mMode) {
            "playback-capture" -> {
                // OEM stream tables differ — STREAM_VOICE_ASSISTANCE(13) throws
                // "Bad stream type" on Samsung API 33 and used to abort the whole arm
                // after playback-capture was already live (phone unmuted again).
                mute?.armMute()
            }
            else -> {
                mute?.clearHeldMute(mMode)
                log(TAG, "media=$mMode — skip local mute so remotesubmix tap stays live")
            }
        }
    }

    /**
     * Tear down AudioRecord / policy.
     * [restoreAudio]=false keeps phone mute across rearm (pull stall).
     */
    private fun disableLocked(restoreAudio: Boolean) {
        readerRunning.set(false)
        mediaReaderThread?.interrupt()
        mediaReaderThread = null
        speechReaderThread?.interrupt()
        speechReaderThread = null
        try {
            mediaRecord?.stop()
        } catch (e: Throwable) {
            logDebug(TAG, "media AudioRecord.stop failed: ${e.message}")
        }
        try {
            mediaRecord?.release()
        } catch (e: Throwable) {
            logDebug(TAG, "media AudioRecord.release failed: ${e.message}")
        }
        mediaRecord = null
        try {
            speechRecord?.stop()
        } catch (e: Throwable) {
            logDebug(TAG, "speech AudioRecord.stop failed: ${e.message}")
        }
        try {
            speechRecord?.release()
        } catch (e: Throwable) {
            logDebug(TAG, "speech AudioRecord.release failed: ${e.message}")
        }
        speechRecord = null
        CarPlayAudioPolicy.unregisterAudioPolicy(audioManager, audioPolicy)
        audioPolicy = null
        captureHandler?.removeCallbacksAndMessages(null)
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        armed.set(false)
        if (restoreAudio) {
            // Stop enforce thread, restore held mute, drop saved volumes.
            mute?.teardown()
        }
        // else: keep mute + saved volumes; mute enforce thread stays for playback-capture
        log(
            TAG,
            "disarmed: media=$mediaMode speech=$speechMode restoreAudio=$restoreAudio " +
                "pulledMedia=${totalMediaPulled / 1024}KB pulledSpeech=${totalSpeechPulled / 1024}KB " +
                "dropMedia=${mediaRing.dropped / 1024}KB dropSpeech=${speechRing.dropped / 1024}KB",
        )
        mediaMode = "none"
        speechMode = "none"
    }

    /**
     * Rebuild tap while [desired] without unmuting the phone.
     * Used when the capture tap dies or TX stalls mid-session.
     *
     * @param force when true, tear down an already-armed tap (e.g. remotesubmix
     * ring-overflow recovery) instead of no-op'ing.
     */
    private fun scheduleRearm(reason: String, force: Boolean = false) {
        if (!desired.get()) {
            setEnabled(false)
            return
        }
        val now = System.currentTimeMillis()
        val last = lastRearmMs.get()
        if (last != 0L && now - last < REARM_MIN_GAP_MS) {
            logDebug(TAG, "rearm skipped (gap) reason=$reason force=$force")
            return
        }
        val postTarget = captureHandler
        val task = Runnable { rearmIfDesired(reason, force) }
        if (postTarget != null) {
            postTarget.post(task)
        } else {
            Thread(task, "aad-cp-audio-rearm").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    private fun rearmIfDesired(reason: String, force: Boolean = false) {
        synchronized(opLock) {
            if (!desired.get()) {
                if (armed.get() || mute?.phoneMuted == true || mute?.speakerDeviceMuted == true) {
                    val identity = Binder.clearCallingIdentity()
                    try {
                        disableLocked(restoreAudio = true)
                    } finally {
                        Binder.restoreCallingIdentity(identity)
                    }
                }
                return
            }
            if (!force && armed.get() && mediaRecord != null) {
                logDebug(TAG, "rearm noop — already armed reason=$reason")
                return
            }
            lastRearmMs.set(System.currentTimeMillis())
            lastRearmReason = reason
            log(TAG, "rearm keep-mute reason=$reason force=$force modeWas=$mediaMode")
            val identity = Binder.clearCallingIdentity()
            try {
                if (armed.get()) {
                    disableLocked(restoreAudio = false)
                }
                enableLocked()
            } finally {
                lastRearmReason = null
                Binder.restoreCallingIdentity(identity)
            }
        }
    }

    // ------------------------------------------------------------------
    // Readers
    // ------------------------------------------------------------------

    private fun startMediaReaderLocked(record: AudioRecord, mode: String) {
        readerRunning.set(true)
        mediaReaderThread = Thread(
            {
                // Realtime audio path: a reader starved by system_server load leaves the
                // capture ring to drain, and the car hears it as a short silence.
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val buf = ByteArray(CarPlayAudioFormats.MEDIA_READ_BYTES)
                var written = 0L
                var firstNonZeroLogged = false
                while (readerRunning.get() && !Thread.currentThread().isInterrupted) {
                    val n = try {
                        record.read(buf, 0, buf.size)
                    } catch (e: Throwable) {
                        log(TAG, "media AudioRecord.read failed", e)
                        break
                    }
                    if (n > 0) {
                        // AudioRecord partial reads are not guaranteed frame-aligned
                        // (stereo s16 = 4 B). An odd byte count shifts every downstream
                        // s16 pair by one byte — the egress byte-swap then splices two
                        // samples into one: persistent crackle until the next ring
                        // clear on re-arm. Floor to whole frames.
                        val len = n - n % 4
                        if (len <= 0) continue
                        val chunk = if (len == buf.size) buf else buf.copyOf(len)
                        mediaRing.write(chunk)
                        written += len
                        var peak = 0
                        var i = 0
                        while (i + 1 < len) {
                            val s = (chunk[i].toInt() and 0xff) or (chunk[i + 1].toInt() shl 8)
                            val abs = if (s < 0) -s else s
                            if (abs > peak) peak = abs
                            i += 2
                        }
                        if (peak > mediaPcmPeak.get()) mediaPcmPeak.set(peak)
                        if (!firstNonZeroLogged && peak > 0) {
                            firstNonZeroLogged = true
                            log(TAG, "media capture first non-zero PCM mode=$mode written=${written}B peak=$peak")
                        }
                    } else if (n < 0) {
                        log(TAG, "media AudioRecord.read error=$n — stopping reader")
                        break
                    }
                }
            },
            "aad-cp-audio-media",
        ).also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun startSpeechReaderLocked(record: AudioRecord) {
        speechReaderThread = Thread(
            {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val buf = ByteArray(CarPlayAudioFormats.SPEECH_READ_BYTES)
                var written = 0L
                var firstNonZeroLogged = false
                while (readerRunning.get() && !Thread.currentThread().isInterrupted) {
                    val n = try {
                        record.read(buf, 0, buf.size)
                    } catch (e: Throwable) {
                        log(TAG, "speech AudioRecord.read failed", e)
                        break
                    }
                    if (n > 0) {
                        // mono s16 frame = 2 B — same unaligned-partial-read guard.
                        val len = n - n % 2
                        if (len <= 0) continue
                        val chunk = if (len == buf.size) buf else buf.copyOf(len)
                        speechRing.write(chunk)
                        written += len
                        if (!firstNonZeroLogged && chunk.any { it != 0.toByte() }) {
                            firstNonZeroLogged = true
                            log(TAG, "speech capture first non-zero PCM mode=$speechMode written=${written}B")
                        }
                    } else if (n < 0) {
                        log(TAG, "speech AudioRecord.read error=$n — stopping reader")
                        break
                    }
                }
            },
            "aad-cp-audio-speech",
        ).also {
            it.isDaemon = true
            it.start()
        }
    }

    /** True when an exclusive nav/assistant AudioPolicy tap is armed (not media bleed). */
    fun isSpeechCaptureReady(): Boolean = synchronized(opLock) {
        armed.get() && speechMode == "audio-policy-mix" && speechRecord != null
    }

    // ------------------------------------------------------------------
    // Tap open
    // ------------------------------------------------------------------

    // Runs in system_server only (Xposed hook); system_server holds RECORD_AUDIO,
    // so the module manifest / checkPermission is not applicable.
    @SuppressLint("MissingPermission")
    private fun openRemoteSubmixRecord(sampleRate: Int, channelMask: Int): AudioRecord? {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(
                if (sampleRate >= 40_000) {
                    CarPlayAudioFormats.MEDIA_READ_BYTES
                } else {
                    CarPlayAudioFormats.SPEECH_READ_BYTES
                },
            )
            AudioRecord(
                MediaRecorder.AudioSource.REMOTE_SUBMIX,
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf,
            )
        } catch (e: Throwable) {
            log(TAG, "REMOTE_SUBMIX open failed rate=$sampleRate", e)
            null
        }
    }

    /**
     * @return Triple(record, audioPolicyOrNull, modeLabel)
     */
    private fun openSpeechRecord(ctx: Context): Triple<AudioRecord?, Any?, String> {
        // Media is remotesubmix (no MediaProjection) — openSpeechMix creates a
        // register-only projection for the speech policy and stops it after register.
        CarPlayAudioPolicy.openSpeechMix(ctx, audioManager, null)?.let { (rec, policy) ->
            return Triple(rec, policy, "audio-policy-mix")
        }
        // Do not open a second REMOTE_SUBMIX for speech: it captures the same mix as media
        // and DiPlay plays both → music echo. Prefer silence over duplicate content.
        log(TAG, "no speech tap — leaving speech silent (avoid media/speech double)")
        return Triple(null, null, "none")
    }

    // ------------------------------------------------------------------
    // Pull
    // ------------------------------------------------------------------

    /** Media: little-endian s16 stereo 48 kHz. */
    fun pull(maxBytes: Int): ByteArray {
        if (!armed.get()) return ByteArray(0)
        lastPullMs.set(System.currentTimeMillis())
        val out = mediaRing.pull(maxBytes)
        totalMediaPulled += out.size
        return out
    }

    /** Speech: little-endian s16 mono 16 kHz. */
    fun pullSpeech(maxBytes: Int): ByteArray {
        if (!armed.get()) return ByteArray(0)
        lastPullMs.set(System.currentTimeMillis())
        val out = speechRing.pull(maxBytes)
        totalSpeechPulled += out.size
        return out
    }

    // ------------------------------------------------------------------
    // Watchdog
    // ------------------------------------------------------------------

    private fun scheduleWatchdog(handler: Handler) {
        handler.postDelayed({ watchdogTick(handler) }, WATCHDOG_PERIOD_MS)
    }

    private fun watchdogTick(handler: Handler) {
        if (!desired.get()) {
            // Session released. Also restore when the tap is already down but
            // setStreamMute / device index was left held across a rearm gap.
            if (armed.get() || mute?.phoneMuted == true || mute?.speakerDeviceMuted == true) {
                setEnabled(false)
            }
            return
        }
        if (System.currentTimeMillis() - lastPullMs.get() > PULL_TIMEOUT_MS) {
            // App TX may be stuck or projection died; never unmute while FGS
            // still desires capture — rebuild tap and keep phone mute.
            log(
                TAG,
                "no pull for ${PULL_TIMEOUT_MS / 1000}s — rearm keep-mute " +
                    "(desired=true, not unmuting) " +
                    "availMedia=${mediaRing.availableBytes}B availSpeech=${speechRing.availableBytes}B",
            )
            lastPullMs.set(System.currentTimeMillis())
            scheduleRearm("pull-timeout")
            // Rearm may quit this handler; if still alive, keep ticking.
            if (desired.get() && captureHandler === handler) {
                scheduleWatchdog(handler)
            }
            return
        }
        if (!armed.get()) {
            scheduleRearm("watchdog-unarmed")
            if (desired.get() && captureHandler === handler) {
                scheduleWatchdog(handler)
            }
            return
        }
        // Revive sticky-mute if One UI killed / deadlocked the enforce thread.
        if (mediaMode == "playback-capture") {
            synchronized(opLock) {
                if (armed.get()) mute?.ensureMuteEnforceLocked()
            }
        }
        // Lifetime max stays for "ever heard audio"; window peak shows if tap is live now.
        val peak = mediaPcmPeak.getAndSet(0)
        val dropNow = mediaRing.dropped
        val dropPrev = lastWatchdogDropMedia.getAndSet(dropNow)
        val dropDelta = (dropNow - dropPrev).coerceAtLeast(0L)
        watchdogDiagnose(peak, dropDelta)
        val muteAlive = mute?.muteThreadAlive == true
        log(
            TAG,
            "watchdog media=$mediaMode speech=$speechMode desired=true " +
                "pulledMedia=${totalMediaPulled / 1024}KB pulledSpeech=${totalSpeechPulled / 1024}KB " +
                "pcmPeak=$peak dropMedia=${dropNow / 1024}KB " +
                "dropDelta=${dropDelta / 1024}KB muteThread=$muteAlive",
        )
        scheduleWatchdog(handler)
    }

    /** Silent-tap commentary + stuck-remotesubmix overflow recovery. */
    private fun watchdogDiagnose(peak: Int, dropDelta: Long) {
        if (peak == 0 && totalMediaPulled > 0L &&
            (mediaMode == "remote-submix" || mediaMode == "playback-capture")
        ) {
            log(
                TAG,
                "watchdog: $mediaMode windowPeak=0 — capture silence " +
                    "(HU may still get empty AAC; check app volume / route)",
            )
        }
        // Stuck remotesubmix tap: sustained ring overflow → force rearm rebuilds the
        // tap (fresh reader, cleared ring). Self-limiting: REARM_MIN_GAP_MS throttles
        // repeats and the cleared ring resets dropDelta.
        if (mediaMode == "remote-submix" &&
            dropDelta >= REMOTE_SUBMIX_DROP_REARM_BYTES &&
            totalMediaPulled > 0L
        ) {
            log(
                TAG,
                "watchdog: remote-submix dropDelta=${dropDelta / 1024}KB — force rearm",
            )
            scheduleRearm("remote-submix-drop", force = true)
        }
    }
}
