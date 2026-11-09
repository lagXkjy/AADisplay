package io.github.nitsuya.aa.display.xposed.carplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.UserHandle
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * system_server tap that feeds **two exclusive** CarPlay audio streams:
 * - **media** — entertainment (SETUP `audioType=media`, 48 kHz stereo) via [pull]
 * - **speech** — navigation / assistant (SETUP `audioType=speech`, 16 kHz mono) via [pullSpeech]
 *
 * Separation rules (DiPlay mixes both; duplicate PCM = echo):
 * - Media prefers unfiltered [MediaRecorder.AudioSource.REMOTE_SUBMIX] (speaker-mix
 *   tap). This is the scheme that still hears 高德 TTS: those tracks bake
 *   [AudioAttributes.FLAG_NO_MEDIA_PROJECTION] while using USAGE_MEDIA, so
 *   AudioPolicy RENDER / MediaProjection APC succeed yet exclude them (music OK,
 *   nav silent). RENDER/APC code paths remain as unused helpers; do not re-arm
 *   them via deferred upgrade.
 * - Speech is AudioPolicy [AudioMix] for nav/assistant only when that mix opens;
 *   never a second remotesubmix (echo). Gaode TTS rides the media ring.
 *
 * Mute: remotesubmix → **no** mute (device mute zeros the tap on some OEMs).
 * playback-capture / audio-policy-render mute rules kept for the dormant helpers.
 *
 * Scope: phone→car **playback** (media, nav, ringtone, call downlink). Car mic
 * uplink / full CarPlay telephony protocol is out of scope here.
 *
 * Arm follows [reportCarPlaySessionLive] true (HU dial / screen SETUP).
 * Disarm + volume restore follows live=false: FGS tearDown, or HU media actually
 * gone (peer close / TEARDOWN) after a short debounce so a soft reconnect that
 * SETUPs again does not flash the phone speaker. UDP-only egress stop does not disarm.
 */
object CarPlayAudioCapture {
    private const val TAG = "AAD_CarPlayAudioCap"

    // STREAM_VOICE_ASSISTANCE = 13 (removed from the API 36 AudioManager surface).
    private const val STREAM_VOICE_ASSISTANCE = 13

    /** Streams muted on the phone while playback-capture media is armed (car is the sink). */
    private val MUTED_STREAMS = listOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_NOTIFICATION,
        STREAM_VOICE_ASSISTANCE,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_ALARM,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_VOICE_CALL,
        AudioManager.STREAM_DTMF,
    )

    /**
     * RENDER media usages that never trip CAPTURE_VOICE_COMMUNICATION_OUTPUT.
     * Voice/call usages are appended only when that permission is granted
     * (see [mediaCaptureUsagesForRender]).
     */
    private val MEDIA_CAPTURE_USAGES_BASE = intArrayOf(
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_GAME,
        AudioAttributes.USAGE_UNKNOWN,
        AudioAttributes.USAGE_NOTIFICATION,
        AudioAttributes.USAGE_NOTIFICATION_EVENT,
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        AudioAttributes.USAGE_ALARM,
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
    )

    private val MEDIA_CAPTURE_USAGES_VOICE = intArrayOf(
        AudioAttributes.USAGE_VOICE_COMMUNICATION,
        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
    )

    /** Full list for logging / mute commentary; RENDER may use a subset. */
    private val MEDIA_CAPTURE_USAGES =
        MEDIA_CAPTURE_USAGES_BASE + MEDIA_CAPTURE_USAGES_VOICE


    /** Nav / assistant on the 16 kHz speech CarPlay stream (DiPlay; skipped on MIB3). */
    private val SPEECH_CAPTURE_USAGES = intArrayOf(
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
        AudioAttributes.USAGE_ASSISTANT,
    )

    /** DiPlay entertainment media: PCM_FORMAT[0x8000] = 48 kHz stereo. */
    private const val MEDIA_SAMPLE_RATE = 48_000
    /** AudioRecord / playback-capture use IN masks. */
    private const val MEDIA_CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO
    /** AudioMix RENDER/LOOP_BACK mixes use OUT masks (CTS / AOSP). */
    private const val MEDIA_MIX_CHANNEL_MASK = AudioFormat.CHANNEL_OUT_STEREO
    /** DiPlay voice bit PCM_FORMAT[0x10] = 16 kHz mono. */
    private const val SPEECH_SAMPLE_RATE = 16_000
    private const val SPEECH_MIX_CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO

    private const val MEDIA_BYTES_PER_MS = MEDIA_SAMPLE_RATE * 2 /* ch */ * 2 /* s16 */ / 1000
    private const val SPEECH_BYTES_PER_MS = SPEECH_SAMPLE_RATE * 1 /* ch */ * 2 /* s16 */ / 1000
    /**
     * Hard cap ~200ms @ 48 kHz stereo s16. Prefer a short skip over lip-lag; a taller
     * ring only let Binder stalls ship stale PCM while video always takes the latest frame.
     */
    private const val MEDIA_RING_BYTES = MEDIA_BYTES_PER_MS * 200
    /** Hard cap ~400ms @ 16 kHz mono s16. */
    private const val SPEECH_RING_BYTES = SPEECH_BYTES_PER_MS * 400
    /**
     * Drop-to-live hysteresis: trim when backlog exceeds [LIVE_HIGH_MS], down to
     * [LIVE_LOW_MS] (under DiPlay audioLatencyMs). Keep the floor near one–two packets
     * so a matched producer/consumer cannot sit forever at ~200ms of sticky latency.
     * Applied on both write and pull so capture drops stale PCM before TX recovers.
     */
    private const val LIVE_HIGH_MS = 60
    private const val LIVE_LOW_MS = 20
    private const val MEDIA_HIGH_WATERMARK_BYTES = MEDIA_BYTES_PER_MS * LIVE_HIGH_MS
    private const val MEDIA_LOW_WATERMARK_BYTES = MEDIA_BYTES_PER_MS * LIVE_LOW_MS
    private const val SPEECH_HIGH_WATERMARK_BYTES = SPEECH_BYTES_PER_MS * LIVE_HIGH_MS
    private const val SPEECH_LOW_WATERMARK_BYTES = SPEECH_BYTES_PER_MS * LIVE_LOW_MS
    private const val WATCHDOG_PERIOD_MS = 30_000L
    /** CSM re-enforces local mute every 250ms — some OEMs restore MUSIC when apps start. */
    private const val MUTE_ENFORCE_MS = 250L
    private const val PULL_TIMEOUT_MS = 5 * 60_000L
    /** Floor between projection-stop / pull-stall rearms while session still desired. */
    private const val REARM_MIN_GAP_MS = 8_000L
    /** After remotesubmix arms, wait for TYPE_REMOTE_SUBMIX then try RENDER once. */
    private const val RENDER_UPGRADE_DELAY_MS = 1_500L
    /**
     * remote-submix overflow over one watchdog window that triggers a force rearm
     * to retry audio-policy-render (~50 KB ≈ 260 ms of stereo PCM @ 48 kHz).
     */
    private const val REMOTE_SUBMIX_DROP_REARM_BYTES = 50L * 1024
    private const val MEDIA_READ_BYTES = 3_840 // 20 ms @ 48 kHz stereo s16
    private const val SPEECH_READ_BYTES = 640 // 20 ms @ 16 kHz mono s16
    private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

    private val opLock = Any()
    /** FGS / AIDL still wants capture — survives soft reconnect and transient tap death. */
    private val desired = AtomicBoolean(false)
    private val armed = AtomicBoolean(false)
    private val lastPullMs = AtomicLong(0L)
    private val lastRearmMs = AtomicLong(0L)
    /** mediaRing.dropped snapshot from the previous watchdog tick. */
    private val lastWatchdogDropMedia = AtomicLong(0L)
    private val muteEnforceActive = AtomicBoolean(false)
    /** Suppress MediaProjection.onStop → rearm while we intentionally stop() the projection. */
    @Volatile private var ignoringProjectionStop = false
    /**
     * Sticky for this capture session: RENDER upgrade already tried and still failed
     * (OEM setDevice / createAudioRecordSource). Skip remotesubmix dropDelta force-rearm
     * so we do not tear a live tap for a route we cannot open.
     */
    @Volatile private var audioPolicyRenderUnavailable = false
    /** Reason of the in-flight [rearmIfDesired] (null on first cold [enableLocked]). */
    @Volatile private var lastRearmReason: String? = null

    private val mediaRing = PcmRing(
        MEDIA_RING_BYTES,
        MEDIA_HIGH_WATERMARK_BYTES,
        MEDIA_LOW_WATERMARK_BYTES,
    )
    private val speechRing = PcmRing(
        SPEECH_RING_BYTES,
        SPEECH_HIGH_WATERMARK_BYTES,
        SPEECH_LOW_WATERMARK_BYTES,
    )

    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var savedVolumes: IntArray? = null
    /** Parallel to [savedVolumes] — only streams that accepted get/set on this OEM. */
    private var mutedStreamTypes: IntArray? = null
    /** Parallel to [mutedStreamTypes]. -1 = this OEM has no fine-volume API. */
    private var savedFineVolumes: IntArray? = null
    private var phoneMuted = false
    /** Remotesubmix path: speaker-device volume zeroed (stream mute would silence the mix). */
    private var speakerDeviceMuted = false
    private var savedSpeakerVolumes: IntArray? = null
    private var savedSpeakerStreamTypes: IntArray? = null
    /** API 33 3-arg vs API 36 4-arg / setStreamVolumeIndexAS. */
    @Volatile private var volumeIndexFns: VolumeIndexFns? = null
    private var mediaProjection: MediaProjection? = null
    /**
     * Tiny VirtualDisplay held for the lifetime of [mediaProjection].
     * Some OEMs (Samsung) reject playback-capture AudioPolicy registration unless the
     * projection is backing an active display — same requirement scrcpy/sndcpy hit.
     */
    private var projectionVirtualDisplay: VirtualDisplay? = null
    private var projectionImageReader: ImageReader? = null
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
    private var volumeReceiver: BroadcastReceiver? = null
    @Volatile private var muteMonitorThread: Thread? = null

    fun install(context: Context) {
        synchronized(opLock) {
            if (audioManager != null) return
            appContext = context.applicationContext ?: context
            audioManager = context.getSystemService(AudioManager::class.java)
            logDebug(TAG, "installed (idle)")
        }
    }

    fun isEnabled(): Boolean = armed.get()

    /** True while FGS/AIDL last requested capture (may be briefly disarmed during rearm). */
    fun isDesired(): Boolean = desired.get()

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
                    ensureMuteEnforceLocked()
                    reassertMute = true
                }
                // Fall through: enforce outside opLock (see below).
            } else if (enabled == armed.get()) {
                restoreWhileIdle = !enabled && (phoneMuted || speakerDeviceMuted)
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
                val am = audioManager
                if (am != null) {
                    synchronized(opLock) {
                        if (!desired.get() && !armed.get()) {
                            clearHeldMuteLocked(am, "setEnabled(false)-idle")
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
                enforcePhoneMute(audioManager)
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
        mediaRing.clear()
        speechRing.clear()
        totalMediaPulled = 0L
        totalSpeechPulled = 0L
        mediaPcmPeak.set(0)
        lastWatchdogDropMedia.set(0L)
        // Rearm keep-mute: preserve saved volumes / phoneMuted across tap rebuild.
        if (!phoneMuted) {
            mutedStreamTypes = null
            savedVolumes = null
            savedFineVolumes = null
        }
        if (!speakerDeviceMuted) {
            savedSpeakerVolumes = null
            savedSpeakerStreamTypes = null
        }

        val threads = HandlerThread("aad-cp-audio-capture").also { it.start() }
        val handler = Handler(threads.looper)
        var proj: MediaProjection? = null
        var mediaRec: AudioRecord? = null
        var speechRec: AudioRecord? = null
        var policy: Any? = null
        var mMode = "none"
        var sMode = "none"
        try {
            // REMOTE_SUBMIX first (rolled back from RENDER/APC). RENDER was excluding
            // FLAG_NO_MEDIA_PROJECTION players (高德 16 kHz USAGE_MEDIA TTS) while still
            // capturing normal music — car heard media, never nav. Remotesubmix taps the
            // speaker mix and still includes those tracks. Never deferred-upgrade away.
            audioPolicyRenderUnavailable = true
            mediaRec = openRemoteSubmixRecord(MEDIA_SAMPLE_RATE, MEDIA_CHANNEL_MASK)
            mMode = "remote-submix"
            // Optional speech tap for true NAVIGATION/ASSISTANT usages only. Do not open
            // a second remotesubmix (same PCM on media+speech = DiPlay echo). Gaode TTS
            // is USAGE_MEDIA and stays on the media ring above.
            val speechPair = openSpeechRecord(
                ctx,
                mediaIsPlaybackCapture = false,
                mediaProjection = null,
            )
            speechRec = speechPair.first
            policy = speechPair.second
            sMode = speechPair.third

            if (mediaRec == null || mediaRec.state != AudioRecord.STATE_INITIALIZED) {
                val st = mediaRec?.state ?: -1
                throw IllegalStateException(
                    "media AudioRecord not initialized (mode=$mMode state=$st)",
                )
            }
            mediaRec.startRecording()
            if (mediaRec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                // APC can fail if projection was invalidated mid-arm; drop to remotesubmix
                // instead of leaving the session silent / rearm-looping.
                if (mMode == "playback-capture") {
                    log(TAG, "playback-capture startRecording failed — fallback remote-submix")
                    runCatching { mediaRec.stop() }
                    runCatching { mediaRec.release() }
                    releaseProjectionVirtualDisplay()
                    ignoringProjectionStop = true
                    runCatching { proj?.stop() }
                    ignoringProjectionStop = false
                    proj = null
                    mediaRec = openRemoteSubmixRecord(MEDIA_SAMPLE_RATE, MEDIA_CHANNEL_MASK)
                    mMode = "remote-submix"
                    if (mediaRec == null || mediaRec.state != AudioRecord.STATE_INITIALIZED) {
                        throw IllegalStateException(
                            "media AudioRecord failed to start (mode=playback-capture→remote-submix)",
                        )
                    }
                    mediaRec.startRecording()
                    if (mediaRec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        throw IllegalStateException(
                            "media AudioRecord failed to start (mode=remote-submix)",
                        )
                    }
                } else {
                    throw IllegalStateException("media AudioRecord failed to start (mode=$mMode)")
                }
            }

            if (speechRec != null) {
                if (speechRec.state != AudioRecord.STATE_INITIALIZED) {
                    log(TAG, "speech AudioRecord not initialized (mode=$sMode) — speech silence")
                    runCatching { speechRec.release() }
                    speechRec = null
                    if (mMode != "audio-policy-render") {
                        unregisterAudioPolicy(am, policy)
                        policy = null
                    }
                    sMode = "none"
                } else {
                    speechRec.startRecording()
                    if (speechRec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        log(TAG, "speech AudioRecord failed to start (mode=$sMode) — speech silence")
                        runCatching { speechRec.release() }
                        speechRec = null
                        if (mMode != "audio-policy-render") {
                            unregisterAudioPolicy(am, policy)
                            policy = null
                        }
                        sMode = "none"
                    }
                }
            }

            // Start readers BEFORE mute so the first packs are not racing a silent mix.
            // playback-capture needs projection + VirtualDisplay; RENDER may also hold
            // a projection used only for AudioPolicy.register (no VirtualDisplay).
            if (mMode != "playback-capture" && mMode != "audio-policy-render" && proj != null) {
                releaseProjectionVirtualDisplay()
                ignoringProjectionStop = true
                runCatching { proj.stop() }
                ignoringProjectionStop = false
                proj = null
            }
            mediaProjection = proj
            mediaRecord = mediaRec
            speechRecord = speechRec
            audioPolicy = policy
            captureThread = threads
            captureHandler = handler
            mediaMode = mMode
            speechMode = sMode
            armed.set(true)
            lastPullMs.set(System.currentTimeMillis())
            startMediaReaderLocked(mediaRec, mMode)
            speechRec?.let { startSpeechReaderLocked(it) }

            // Kill phone-side sinks only for playback-capture (copy still hits speaker).
            // audio-policy-render: true route — speaker out of path, never mute.
            // remotesubmix: do NOT mute — Samsung device mute zeros the tap.
            when (mMode) {
                "playback-capture" -> {
                    // OEM stream tables differ — STREAM_VOICE_ASSISTANCE(13) throws
                    // "Bad stream type" on Samsung API 33 and used to abort the whole arm
                    // after playback-capture was already live (phone unmuted again).
                    phoneMuted = mutePhoneStreams(am)
                    speakerDeviceMuted = muteLocalPlaybackDevices()
                    ensureMuteEnforceLocked()
                }
                "audio-policy-render" -> {
                    // Rearm may have held mute from a prior playback-capture arm.
                    clearHeldMuteLocked(am, "audio-policy-render")
                    log(TAG, "media=audio-policy-render — skip mute (RENDER off speaker)")
                }
                else -> {
                    clearHeldMuteLocked(am, mMode)
                    log(TAG, "media=$mMode — skip local mute so remotesubmix tap stays live")
                }
            }

            log(
                TAG,
                "armed: media=$mMode speech=$sMode usagesMedia=${MEDIA_CAPTURE_USAGES.toList()} " +
                    "usagesSpeech=${SPEECH_CAPTURE_USAGES.toList()} phoneMuted=$phoneMuted " +
                    "speakerDeviceMuted=$speakerDeviceMuted (vols=${savedVolumes?.toList()})",
            )
            // Stay on remotesubmix — no deferred RENDER/APC upgrade (breaks 高德 TTS).
            scheduleWatchdog(handler)
        } catch (e: Throwable) {
            log(TAG, "enable failed — staying idle", e)
            runCatching { mediaRec?.release() }
            runCatching { speechRec?.release() }
            unregisterAudioPolicy(am, policy)
            releaseProjectionVirtualDisplay()
            ignoringProjectionStop = true
            runCatching { proj?.stop() }
            ignoringProjectionStop = false
            threads.quitSafely()
            mediaProjection = null
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

    /** Drop stream/device mute left over from a keep-mute rearm into a non-mute mode. */
    private fun clearHeldMuteLocked(am: AudioManager, reason: String) {
        if (!phoneMuted && !speakerDeviceMuted) return
        log(TAG, "clear held mute for $reason")
        stopMuteEnforceLocked()
        if (phoneMuted) restorePhoneStreams(am)
        if (speakerDeviceMuted) restoreLocalPlaybackDevices()
        savedVolumes = null
        savedFineVolumes = null
        mutedStreamTypes = null
        phoneMuted = false
        speakerDeviceMuted = false
        savedSpeakerVolumes = null
        savedSpeakerStreamTypes = null
    }

    /**
     * Tear down AudioRecord / projection / policy.
     * [restoreAudio]=false keeps phone mute across rearm (projection death, pull stall).
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
        unregisterAudioPolicy(audioManager, audioPolicy)
        audioPolicy = null
        releaseProjectionVirtualDisplay()
        ignoringProjectionStop = true
        try {
            mediaProjection?.stop()
        } catch (e: Throwable) {
            logDebug(TAG, "MediaProjection.stop failed: ${e.message}")
        } finally {
            ignoringProjectionStop = false
        }
        mediaProjection = null
        captureHandler?.removeCallbacksAndMessages(null)
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        armed.set(false)
        if (restoreAudio) {
            stopMuteEnforceLocked()
            if (phoneMuted) {
                restorePhoneStreams(audioManager)
            }
            if (speakerDeviceMuted) {
                restoreLocalPlaybackDevices()
            }
            savedVolumes = null
            savedFineVolumes = null
            mutedStreamTypes = null
            phoneMuted = false
            speakerDeviceMuted = false
            savedSpeakerVolumes = null
            savedSpeakerStreamTypes = null
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
        // Next session may retry RENDER (OEM device list / setDevice can change).
        if (restoreAudio) {
            audioPolicyRenderUnavailable = false
        }
    }

    /**
     * Rebuild tap while [desired] without unmuting the phone.
     * Used when MediaProjection dies or TX stalls mid-session.
     *
     * @param force when true, tear down an already-armed tap (e.g. upgrade off
     * remote-submix) instead of no-op'ing.
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
                if (armed.get() || phoneMuted || speakerDeviceMuted) {
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

    private fun startMediaReaderLocked(record: AudioRecord, mode: String) {
        readerRunning.set(true)
        mediaReaderThread = Thread(
            {
                // Realtime audio path: a reader starved by system_server load leaves the
                // capture ring to drain, and the car hears it as a short silence.
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val buf = ByteArray(MEDIA_READ_BYTES)
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
                        val chunk = if (n == buf.size) buf else buf.copyOf(n)
                        mediaRing.write(chunk)
                        written += n
                        var peak = 0
                        var i = 0
                        while (i + 1 < n) {
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
                val buf = ByteArray(SPEECH_READ_BYTES)
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
                        val chunk = if (n == buf.size) buf else buf.copyOf(n)
                        speechRing.write(chunk)
                        written += n
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

    /**
     * Per-stream mute that tolerates OEM-missing types (e.g. Samsung rejects stream 13).
     * Returns true if at least MUSIC (or any listed stream) was muted.
     */
    private fun mutePhoneStreams(am: AudioManager): Boolean {
        val firstArm = mutedStreamTypes == null || savedVolumes == null
        val vols = ArrayList<Int>(MUTED_STREAMS.size)
        val fines = ArrayList<Int>(MUTED_STREAMS.size)
        val types = ArrayList<Int>(MUTED_STREAMS.size)
        for (stream in MUTED_STREAMS) {
            val cur = runCatching { am.getStreamVolume(stream) }.getOrNull() ?: continue
            val fine = if (firstArm) readOemFineVolume(am, stream) else -1
            runCatching { am.setStreamMute(stream, true) }
            val setOk = runCatching { am.setStreamVolume(stream, 0, 0) }
            if (setOk.isFailure) {
                logDebug(TAG, "setStreamVolume($stream) skipped: ${setOk.exceptionOrNull()?.message}")
                continue
            }
            if (firstArm) zeroOemFineVolume(am, stream)
            types.add(stream)
            if (firstArm) {
                vols.add(cur)
                fines.add(fine)
            }
        }
        if (types.isEmpty()) {
            log(TAG, "phone stream mute: no usable streams")
            return false
        }
        // First arm wins for saved volumes; sticky enforce must not clobber them with 0.
        if (firstArm) {
            mutedStreamTypes = types.toIntArray()
            savedVolumes = vols.toIntArray()
            savedFineVolumes = fines.toIntArray()
            log(TAG, "phone stream mute ok streams=${types} saved=${vols} fine=${fines}")
        }
        return true
    }

    private fun restorePhoneStreams(am: AudioManager?) {
        if (am == null) return
        val types = mutedStreamTypes ?: MUTED_STREAMS.toIntArray()
        val vols = savedVolumes
        val fines = savedFineVolumes
        // Always clear setStreamMute. A system-uid mute survives module disable
        // until process restart, and volume keys will not move the stream.
        for (i in types.indices) {
            val stream = types[i]
            runCatching { am.setStreamMute(stream, false) }
            val vol = vols?.getOrNull(i)
            if (vol != null && vol > 0) {
                runCatching { am.setStreamVolume(stream, vol, 0) }
            }
            val fine = fines?.getOrNull(i) ?: -1
            if (fine >= 0) writeOemFineVolume(am, stream, fine)
        }
    }

    /**
     * CSM-style sticky mute: some OEMs restore STREAM_MUSIC when media apps start,
     * so a one-shot mute leaves the phone speaker live while AAC still flows.
     *
     * Must NOT be called while holding [opLock]: [AudioManager.setStreamVolume] can
     * dispatch [VOLUME_CHANGED_ACTION] synchronously; the receiver used to take
     * [opLock] again → deadlock, mute thread dies, phone speaker comes back.
     */
    private fun enforcePhoneMute(am: AudioManager?) {
        if (am == null) return
        // Hold mute across rearm windows (desired + phoneMuted) even while briefly unarmed.
        val liveCapture = armed.get() && mediaMode == "playback-capture"
        val holdAcrossRearm = phoneMuted && desired.get()
        if (!liveCapture && !holdAcrossRearm) return
        val streams = mutedStreamTypes?.toList() ?: MUTED_STREAMS
        var touched = false
        var restoredVol: Int? = null
        for (stream in streams) {
            val muted = runCatching { am.isStreamMute(stream) }.getOrNull()
            if (muted == false) {
                runCatching { am.setStreamMute(stream, true) }
                touched = true
            }
            val vol = runCatching { am.getStreamVolume(stream) }.getOrNull()
            if (vol != null && vol != 0) {
                if (stream == AudioManager.STREAM_MUSIC) restoredVol = vol
                runCatching { am.setStreamVolume(stream, 0, 0) }
                // OEM fine-volume APIs can leave speaker audible after stream index 0.
                zeroOemFineVolume(am, stream)
                touched = true
            }
        }
        if (speakerDeviceMuted || phoneMuted) {
            // Re-zero local OUT devices without rewriting savedSpeakerVolumes.
            rezeroLocalPlaybackDevices()
        }
        if (restoredVol != null) {
            log(TAG, "phone mute re-enforced streams=$streams musicWas=$restoredVol")
        }
    }

    /** Best-effort OEM fine / sem volume → 0 (no-op when APIs are absent). */
    private fun zeroOemFineVolume(am: AudioManager, stream: Int) {
        val names = listOf("semSetFineVolume", "setFineVolume", "setFineVolumeIndex")
        for (name in names) {
            val ok = runCatching {
                val methods = am.javaClass.methods.filter { it.name == name }
                for (m in methods) {
                    val pts = m.parameterTypes
                    when (pts.size) {
                        2 -> if (pts[0] == Int::class.javaPrimitiveType &&
                            pts[1] == Int::class.javaPrimitiveType
                        ) {
                            m.invoke(am, stream, 0)
                            return@runCatching true
                        }
                        3 -> if (pts.all { it == Int::class.javaPrimitiveType }) {
                            m.invoke(am, stream, 0, 0)
                            return@runCatching true
                        }
                        4 -> if (pts.all { it == Int::class.javaPrimitiveType }) {
                            m.invoke(am, stream, 0, 0, 2 /* SPEAKER */)
                            return@runCatching true
                        }
                    }
                }
                false
            }.getOrNull() == true
            if (ok) return
        }
    }

    /** @return fine index, or -1 when this OEM has no readable fine-volume API. */
    private fun readOemFineVolume(am: AudioManager, stream: Int): Int {
        val names = listOf("semGetFineVolume", "getFineVolume", "getFineVolumeIndex")
        for (name in names) {
            val value = runCatching {
                val methods = am.javaClass.methods.filter { it.name == name }
                for (m in methods) {
                    val pts = m.parameterTypes
                    if (pts.size == 1 && pts[0] == Int::class.javaPrimitiveType) {
                        val got = m.invoke(am, stream)
                        if (got is Int) return@runCatching got
                    }
                }
                null
            }.getOrNull()
            if (value != null) return value
        }
        return -1
    }

    private fun writeOemFineVolume(am: AudioManager, stream: Int, index: Int) {
        val names = listOf("semSetFineVolume", "setFineVolume", "setFineVolumeIndex")
        for (name in names) {
            val ok = runCatching {
                val methods = am.javaClass.methods.filter { it.name == name }
                for (m in methods) {
                    val pts = m.parameterTypes
                    when (pts.size) {
                        2 -> if (pts[0] == Int::class.javaPrimitiveType &&
                            pts[1] == Int::class.javaPrimitiveType
                        ) {
                            m.invoke(am, stream, index)
                            return@runCatching true
                        }
                        3 -> if (pts.all { it == Int::class.javaPrimitiveType }) {
                            m.invoke(am, stream, index, 0)
                            return@runCatching true
                        }
                        4 -> if (pts.all { it == Int::class.javaPrimitiveType }) {
                            m.invoke(am, stream, index, 0, 2 /* SPEAKER */)
                            return@runCatching true
                        }
                    }
                }
                false
            }.getOrNull() == true
            if (ok) return
        }
    }

    /**
     * Start or revive the sticky-mute thread. Safe to call while already armed —
     * a dead thread with [muteEnforceActive]=true previously left the phone unmuted.
     */
    private fun ensureMuteEnforceLocked() {
        val living = muteMonitorThread?.isAlive == true
        if (living && muteEnforceActive.get()) return
        if (!living) {
            muteEnforceActive.set(false)
            muteMonitorThread = null
        }
        startMuteEnforceLocked()
    }

    private fun startMuteEnforceLocked() {
        if (!muteEnforceActive.compareAndSet(false, true)) {
            // Flag stuck true with a dead thread — force restart.
            if (muteMonitorThread?.isAlive != true) {
                muteEnforceActive.set(false)
                if (!muteEnforceActive.compareAndSet(false, true)) return
            } else {
                return
            }
        }
        registerVolumeReceiverLocked()
        val thread = Thread(
            {
                val self = Thread.currentThread()
                try {
                    while (muteEnforceActive.get() &&
                        !self.isInterrupted &&
                        muteMonitorThread === self
                    ) {
                        try {
                            if ((armed.get() && mediaMode == "playback-capture") ||
                                (desired.get() && phoneMuted)
                            ) {
                                enforcePhoneMute(audioManager)
                            }
                        } catch (e: Throwable) {
                            log(TAG, "phone mute enforce tick failed", e)
                        }
                        try {
                            Thread.sleep(MUTE_ENFORCE_MS)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                } finally {
                    // Only the *current* monitor may clear the flag — a replaced
                    // thread's finally must not disarm a newly started successor.
                    if (muteMonitorThread === self) {
                        muteEnforceActive.set(false)
                        log(TAG, "phone mute enforce thread exited")
                    }
                }
            },
            "aad-cp-audio-mute",
        )
        thread.isDaemon = true
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { t, e ->
            log(TAG, "phone mute enforce crashed", e)
            if (muteMonitorThread === t) muteEnforceActive.set(false)
        }
        muteMonitorThread = thread
        thread.start()
        log(TAG, "phone mute enforce thread started")
    }

    private fun stopMuteEnforceLocked() {
        muteEnforceActive.set(false)
        muteMonitorThread?.interrupt()
        muteMonitorThread = null
        unregisterVolumeReceiverLocked()
    }

    private fun registerVolumeReceiverLocked() {
        val ctx = appContext ?: return
        if (volumeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!muteEnforceActive.get() || intent == null) return
                val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, Int.MIN_VALUE)
                if (stream != Int.MIN_VALUE && stream != AudioManager.STREAM_MUSIC) return
                // No opLock here — see enforcePhoneMute. Snapshot gates only.
                if ((armed.get() && mediaMode == "playback-capture") ||
                    (desired.get() && phoneMuted)
                ) {
                    val am = audioManager ?: return
                    val vol = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
                    val muted = runCatching { am.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrNull()
                    if (vol == 0 && muted == true) return
                    enforcePhoneMute(am)
                }
            }
        }
        val filter = IntentFilter(VOLUME_CHANGED_ACTION)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                ctx.registerReceiver(receiver, filter)
            }
            volumeReceiver = receiver
        }.onFailure {
            logDebug(TAG, "volume receiver register failed: ${it.message}")
        }
    }

    private fun unregisterVolumeReceiverLocked() {
        val ctx = appContext
        val receiver = volumeReceiver ?: return
        volumeReceiver = null
        if (ctx != null) {
            runCatching { ctx.unregisterReceiver(receiver) }
        }
    }

    /** Re-apply device-index 0 without changing the first-arm saved restore snapshot. */
    private fun rezeroLocalPlaybackDevices() {
        val streams = savedSpeakerStreamTypes?.toList()
            ?: mutedStreamTypes?.toList()
            ?: MUTED_STREAMS
        try {
            val audioSystem = Class.forName("android.media.AudioSystem")
            val fns = resolveVolumeIndexFns(audioSystem) ?: return
            val devices = localPlaybackDeviceMasks(audioSystem)
            for (stream in streams) {
                for (device in devices) {
                    fns.setIndex(stream, 0, device)
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Zero MUSIC (etc.) on every **local** output device via
     * [AudioSystem.setStreamVolumeIndex], leaving [DEVICE_OUT_REMOTE_SUBMIX] alone so
     * remotesubmix capture still hears the mix. Includes A2DP — a phone still bonded to
     * the car over BT music while CarPlay WiFi audio runs is a common 「重音」 source.
     */
    private fun muteLocalPlaybackDevices(): Boolean {
        return try {
            val audioSystem = Class.forName("android.media.AudioSystem")
            val fns = resolveVolumeIndexFns(audioSystem)
                ?: throw NoSuchElementException("no setStreamVolumeIndex / setStreamVolumeIndexAS")
            val devices = localPlaybackDeviceMasks(audioSystem)
            if (devices.isEmpty()) return false
            // Prefer streams already validated by mutePhoneStreams; else probe MUTED_STREAMS.
            val streams = mutedStreamTypes?.toList() ?: MUTED_STREAMS
            val saved = ArrayList<Int>(streams.size * devices.size)
            val applied = ArrayList<Int>()
            for (stream in streams) {
                var ok = true
                val before = saved.size
                for (device in devices) {
                    val cur = runCatching {
                        fns.getIndex.invoke(null, stream, device) as? Int
                    }.getOrNull()
                    if (cur == null) {
                        ok = false
                        break
                    }
                    saved.add(cur)
                    if (!fns.setIndex(stream, 0, device)) {
                        ok = false
                        break
                    }
                }
                if (!ok) {
                    while (saved.size > before) saved.removeAt(saved.lastIndex)
                    continue
                }
                applied.add(stream)
            }
            if (applied.isEmpty()) return false
            savedSpeakerVolumes = saved.toIntArray()
            savedSpeakerStreamTypes = applied.toIntArray()
            log(
                TAG,
                "local-device mute applied streams=$applied " +
                    "devices=${devices.map { "0x${it.toString(16)}" }}",
            )
            true
        } catch (e: Throwable) {
            log(TAG, "local-device mute failed", e)
            false
        }
    }

    private fun restoreLocalPlaybackDevices() {
        val saved = savedSpeakerVolumes ?: return
        val streams = savedSpeakerStreamTypes?.toList()
            ?: mutedStreamTypes?.toList()
            ?: MUTED_STREAMS
        try {
            val audioSystem = Class.forName("android.media.AudioSystem")
            val fns = resolveVolumeIndexFns(audioSystem) ?: return
            val devices = localPlaybackDeviceMasks(audioSystem)
            var si = 0
            for (stream in streams) {
                for (device in devices) {
                    if (si >= saved.size) return
                    fns.setIndex(stream, saved[si++], device)
                }
            }
            log(TAG, "local-device mute restored")
        } catch (e: Throwable) {
            logDebug(TAG, "local-device mute restore failed: ${e.message}")
        }
    }

    private class VolumeIndexFns(
        val getIndex: Method,
        val setIndex: (stream: Int, index: Int, device: Int) -> Boolean,
    )

    /**
     * API 33: `setStreamVolumeIndex(stream, index, device)`.
     * API 36: 3-arg gone; public `setStreamVolumeIndexAS(stream, index, isPrivileged, device)`
     * and private 4-arg `setStreamVolumeIndex`.
     */
    private fun resolveVolumeIndexFns(audioSystem: Class<*>): VolumeIndexFns? {
        volumeIndexFns?.let { return it }
        val methods = LinkedHashMap<String, Method>()
        for (m in audioSystem.methods + audioSystem.declaredMethods) {
            methods.putIfAbsent("${m.name}:${m.parameterTypes.joinToString { it.simpleName }}", m)
        }
        val all = methods.values
        val get = all.firstOrNull {
            it.name == "getStreamVolumeIndex" && it.parameterTypes.size == 2
        } ?: return null
        get.isAccessible = true
        val set3 = all.firstOrNull {
            it.name == "setStreamVolumeIndex" && it.parameterTypes.size == 3
        }
        val setAs = all.firstOrNull {
            it.name == "setStreamVolumeIndexAS" && it.parameterTypes.size == 4
        }
        val set4 = all.firstOrNull {
            it.name == "setStreamVolumeIndex" && it.parameterTypes.size == 4
        }
        val setter = when {
            set3 != null -> {
                set3.isAccessible = true
                { stream: Int, index: Int, device: Int ->
                    runCatching { set3.invoke(null, stream, index, device) }.isSuccess
                }
            }
            setAs != null -> {
                setAs.isAccessible = true
                volumeIndexSetter4(setAs)
            }
            set4 != null -> {
                set4.isAccessible = true
                volumeIndexSetter4(set4)
            }
            else -> return null
        }
        val fns = VolumeIndexFns(get, setter)
        volumeIndexFns = fns
        return fns
    }

    private fun volumeIndexSetter4(method: Method): (Int, Int, Int) -> Boolean {
        val pts = method.parameterTypes
        return { stream, index, device ->
            runCatching {
                when {
                    pts.size >= 4 &&
                        pts[2] == java.lang.Boolean.TYPE &&
                        pts[3] == Integer.TYPE ->
                        method.invoke(null, stream, index, false, device)
                    pts.size >= 4 &&
                        pts[2] == Integer.TYPE &&
                        pts[3] == java.lang.Boolean.TYPE ->
                        method.invoke(null, stream, index, device, false)
                    else -> method.invoke(null, stream, index, false, device)
                }
            }.isSuccess
        }
    }

    /** All OUT sinks except REMOTE_SUBMIX (and 0). */
    private fun localPlaybackDeviceMasks(audioSystem: Class<*>): IntArray {
        val names = listOf(
            "DEVICE_OUT_EARPIECE",
            "DEVICE_OUT_SPEAKER",
            "DEVICE_OUT_SPEAKER_SAFE",
            "DEVICE_OUT_WIRED_HEADSET",
            "DEVICE_OUT_WIRED_HEADPHONE",
            "DEVICE_OUT_BLUETOOTH_SCO",
            "DEVICE_OUT_BLUETOOTH_SCO_HEADSET",
            "DEVICE_OUT_BLUETOOTH_SCO_CARKIT",
            "DEVICE_OUT_BLUETOOTH_A2DP",
            "DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES",
            "DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER",
            "DEVICE_OUT_USB_ACCESSORY",
            "DEVICE_OUT_USB_DEVICE",
            "DEVICE_OUT_USB_HEADSET",
            "DEVICE_OUT_HDMI",
            "DEVICE_OUT_HDMI_ARC",
            "DEVICE_OUT_LINE",
        )
        return names.mapNotNull { name ->
            runCatching { audioSystem.getField(name).getInt(null) }.getOrNull()
                ?.takeIf { it != 0 }
        }.distinct().toIntArray()
    }

    /** True when an exclusive nav/assistant AudioPolicy tap is armed (not media bleed). */
    fun isSpeechCaptureReady(): Boolean = synchronized(opLock) {
        armed.get() && speechMode == "audio-policy-mix" && speechRecord != null
    }

    private fun releaseProjectionVirtualDisplay() {
        runCatching { projectionVirtualDisplay?.release() }
        projectionVirtualDisplay = null
        runCatching { projectionImageReader?.close() }
        projectionImageReader = null
    }

    /**
     * Swap a live remotesubmix / playback-capture tap to AudioPolicy RENDER.
     * Seeds REMOTE_SUBMIX when needed so Samsung setDevice(AudioDeviceInfo) can bind,
     * without tearing an existing remotesubmix tap first.
     */
    private fun tryUpgradeToAudioPolicyRenderLocked(): Boolean {
        val ctx = appContext ?: return false
        val fromMode = mediaMode
        if ((fromMode != "remote-submix" && fromMode != "playback-capture") || !armed.get()) {
            return false
        }
        // Field-poke bind does not need a live REMOTE_SUBMIX seed. On Samsung,
        // registerAudioPolicy returns -1 while a REMOTE_SUBMIX AudioRecord is open —
        // drop the old tap (and projection) *before* registering RENDER.
        val oldMedia = mediaRecord
        val oldSpeech = speechRecord
        val oldPolicy = audioPolicy
        val oldProjection = mediaProjection

        readerRunning.set(false)
        mediaReaderThread?.interrupt()
        mediaReaderThread = null
        speechReaderThread?.interrupt()
        speechReaderThread = null
        runCatching { oldMedia?.stop() }
        runCatching { oldMedia?.release() }
        runCatching { oldSpeech?.stop() }
        runCatching { oldSpeech?.release() }
        if (oldPolicy != null) {
            unregisterAudioPolicy(audioManager, oldPolicy)
        }
        mediaRecord = null
        speechRecord = null
        audioPolicy = null
        if (oldProjection != null) {
            releaseProjectionVirtualDisplay()
            ignoringProjectionStop = true
            runCatching { oldProjection.stop() }
            ignoringProjectionStop = false
            mediaProjection = null
        }

        val renderArm = tryOpenAudioPolicyRender(ctx)
        if (renderArm == null) {
            mediaMode = "none"
            speechMode = "none"
            armed.set(false)
            enableLocked()
            return false
        }

        mediaRecord = renderArm.mediaRecord
        speechRecord = renderArm.speechRecord
        audioPolicy = renderArm.policy
        mediaProjection = renderArm.projection
        mediaMode = "audio-policy-render"
        speechMode = renderArm.speechMode
        audioPolicyRenderUnavailable = false

        runCatching { renderArm.mediaRecord.startRecording() }
        if (renderArm.mediaRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            log(TAG, "in-place RENDER upgrade: media record failed to start — rearm from=$fromMode")
            runCatching { renderArm.mediaRecord.release() }
            runCatching { renderArm.speechRecord?.release() }
            unregisterAudioPolicy(audioManager, renderArm.policy)
            mediaRecord = null
            speechRecord = null
            audioPolicy = null
            mediaMode = "none"
            speechMode = "none"
            armed.set(false)
            // Rebuild previous-tier tap without unmuting; caller marks RENDER unavailable.
            enableLocked()
            return false
        }
        renderArm.speechRecord?.let { rec ->
            runCatching { rec.startRecording() }
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                log(TAG, "in-place RENDER upgrade: speech failed to start — speech silence")
                runCatching { rec.release() }
                speechRecord = null
                speechMode = "none"
            }
        }

        readerRunning.set(true)
        startMediaReaderLocked(renderArm.mediaRecord, "audio-policy-render")
        speechRecord?.let { startSpeechReaderLocked(it) }
        // RENDER routes off speaker — drop playback-capture mute if held.
        audioManager?.let { clearHeldMuteLocked(it, "audio-policy-render") }
        log(
            TAG,
            "in-place upgrade ok: $fromMode → media=audio-policy-render speech=$speechMode",
        )
        return true
    }

    private fun openRemoteSubmixRecord(sampleRate: Int, channelMask: Int): AudioRecord? {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(if (sampleRate >= 40_000) MEDIA_READ_BYTES else SPEECH_READ_BYTES)
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
    private fun openSpeechRecord(
        ctx: Context,
        mediaIsPlaybackCapture: Boolean,
        mediaProjection: MediaProjection? = null,
    ): Triple<AudioRecord?, Any?, String> {
        openSpeechViaAudioPolicy(ctx, mediaProjection)?.let { (rec, policy) ->
            return Triple(rec, policy, "audio-policy-mix")
        }
        // Do not open a second REMOTE_SUBMIX for speech: it captures the same mix as media
        // and DiPlay plays both → music echo. Prefer silence over duplicate content.
        if (mediaIsPlaybackCapture) {
            log(TAG, "no speech AudioPolicy mix — nav prompts not in MediaProjection allowlist")
        } else {
            log(TAG, "no speech tap — leaving speech silent (avoid media/speech double)")
        }
        return Triple(null, null, "none")
    }

    private data class AudioPolicyRenderArm(
        val policy: Any,
        val mediaRecord: AudioRecord,
        val speechRecord: AudioRecord?,
        val speechMode: String,
        /** Kept alive when [registerAudioPolicy] used setMediaProjection. */
        val projection: MediaProjection? = null,
    )

    /**
     * Usages for RENDER media mix. AOSP always demands
     * CAPTURE_VOICE_COMMUNICATION_OUTPUT for VOICE_* + LOOP_BACK_RENDER.
     * Samsung system_server does **not** hold that grant (AS.AudioService denies even
     * with MediaProjection). Do not trust checkSelfPermission(uid=1000) — it can
     * spuriously return GRANTED while AudioService still rejects.
     */
    private fun mediaCaptureUsagesForRender(): IntArray {
        log(
            TAG,
            "audio-policy-render: omit VOICE_* usages " +
                "(CAPTURE_VOICE_COMMUNICATION_OUTPUT unavailable) — calls stay on phone",
        )
        return MEDIA_CAPTURE_USAGES_BASE
    }

    /**
     * True-route media + speech: both RENDER|LOOP_BACK onto REMOTE_SUBMIX so matching
     * players leave the phone speaker; capture via [createAudioRecordSource].
     * Soft-fails → playback-capture / remotesubmix.
     *
     * Samsung/AOSP gates (logcat AS.AudioService):
     * - allowPrivilegedPlaybackCapture → CAPTURE_MEDIA_OUTPUT (we lack it) — never set
     * - VOICE_* + LOOP_BACK_RENDER → CAPTURE_VOICE_COMMUNICATION_OUTPUT — omit unless granted
     * - LOOP_BACK_RENDER + MediaProjection → allowed via canProjectAudio (preferred)
     */
    private fun tryOpenAudioPolicyRender(ctx: Context): AudioPolicyRenderArm? {
        return try {
            val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
            val routeRender = mixClass.getField("ROUTE_FLAG_RENDER").getInt(null)
            val routeLoopBack = mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null)
            val renderLoop = routeRender or routeLoopBack
            val remoteSubmix = runCatching {
                Class.forName("android.media.AudioSystem")
                    .getField("DEVICE_OUT_REMOTE_SUBMIX")
                    .getInt(null)
            }.getOrNull() ?: run {
                log(TAG, "audio-policy-render: DEVICE_OUT_REMOTE_SUBMIX missing")
                return null
            }

            val mediaUsages = mediaCaptureUsagesForRender()
            val mediaMix = buildAudioMix(
                usages = mediaUsages,
                sampleRate = MEDIA_SAMPLE_RATE,
                channelMask = MEDIA_MIX_CHANNEL_MASK,
                routeFlags = renderLoop,
                deviceSystemType = remoteSubmix,
                deviceAddress = "0",
            ) ?: run {
                log(TAG, "audio-policy-render: media mix build failed")
                return null
            }
            // Prefer RENDER for nav (off speaker); LOOP_BACK-only still captures but
            // leaves those players on the phone — only used if RENDER mix build fails.
            val speechMix = buildAudioMix(
                usages = SPEECH_CAPTURE_USAGES,
                sampleRate = SPEECH_SAMPLE_RATE,
                channelMask = SPEECH_MIX_CHANNEL_MASK,
                routeFlags = renderLoop,
                deviceSystemType = remoteSubmix,
                deviceAddress = "0",
            ) ?: buildAudioMix(
                usages = SPEECH_CAPTURE_USAGES,
                sampleRate = SPEECH_SAMPLE_RATE,
                channelMask = SPEECH_MIX_CHANNEL_MASK,
                routeFlags = routeLoopBack,
                deviceSystemType = null,
                deviceAddress = null,
            )

            // MediaProjection satisfies LOOP_BACK_RENDER without CAPTURE_* (AOSP).
            val proj = obtainMediaProjection(ctx)
            if (proj != null) {
                log(TAG, "audio-policy-render: MediaProjection attached for register")
            }

            // Register media+speech together; if OEM rejects the combo, media-only still
            // gives true route off speaker (speech stays none).
            var usedProjection = false
            var policy = registerAudioPolicy(
                ctx,
                buildList {
                    add(mediaMix)
                    if (speechMix != null) add(speechMix)
                },
                proj,
            )
            if (policy != null) usedProjection = proj != null
            var speechMixRegistered = speechMix != null && policy != null
            if (policy == null && speechMix != null) {
                log(TAG, "audio-policy-render: media+speech register failed — retry media-only")
                policy = registerAudioPolicy(ctx, listOf(mediaMix), proj)
                if (policy != null) usedProjection = proj != null
                speechMixRegistered = false
            }
            if (policy == null && proj != null) {
                // Some OEMs reject projection-backed RENDER; retry without.
                log(TAG, "audio-policy-render: projection register failed — retry without")
                policy = registerAudioPolicy(ctx, buildList {
                    add(mediaMix)
                    if (speechMix != null) add(speechMix)
                }, null)
                speechMixRegistered = speechMix != null && policy != null
                if (policy == null && speechMix != null) {
                    policy = registerAudioPolicy(ctx, listOf(mediaMix), null)
                    speechMixRegistered = false
                }
                usedProjection = false
            }
            if (policy == null) {
                log(TAG, "audio-policy-render: registerAudioPolicy failed")
                runCatching { proj?.stop() }
                return null
            }
            val heldProj = if (usedProjection) proj else null
            if (heldProj == null) {
                runCatching { proj?.stop() }
            }

            val mediaRec = createAudioRecordSource(policy, mediaMix)
            if (mediaRec == null || mediaRec.state != AudioRecord.STATE_INITIALIZED) {
                log(
                    TAG,
                    "audio-policy-render: media record not ready state=${mediaRec?.state}",
                )
                runCatching { mediaRec?.release() }
                unregisterAudioPolicy(audioManager, policy)
                runCatching { heldProj?.stop() }
                return null
            }

            var speechRec: AudioRecord? = null
            var sMode = "none"
            if (speechMixRegistered && speechMix != null) {
                val rec = createAudioRecordSource(policy, speechMix)
                if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                    speechRec = rec
                    sMode = "audio-policy-mix"
                } else {
                    runCatching { rec?.release() }
                    log(TAG, "audio-policy-render: speech mix present but record failed — speech silence")
                }
            } else if (speechMix == null) {
                log(TAG, "audio-policy-render: speech mix build failed — speech silence")
            } else {
                log(TAG, "audio-policy-render: speech omitted (media-only policy registered)")
            }

            log(
                TAG,
                "audio-policy-render armed policy mixes=${if (speechMixRegistered) 2 else 1} " +
                    "usagesMedia=${mediaUsages.toList()} " +
                    "usagesSpeech=${SPEECH_CAPTURE_USAGES.toList()} speechMode=$sMode " +
                    "projection=${heldProj != null}",
            )
            AudioPolicyRenderArm(policy, mediaRec, speechRec, sMode, heldProj)
        } catch (e: Throwable) {
            log(TAG, "audio-policy-render unavailable — fallback capture", e)
            null
        }
    }

    /**
     * Speech-only policy (fallback when media is playback-capture / remotesubmix).
     * [sharedProjection] must be the media tap's live projection when APC is arming —
     * never create a second MediaProjection (stops the current one → silent APC).
     */
    private fun openSpeechViaAudioPolicy(
        ctx: Context,
        sharedProjection: MediaProjection? = null,
    ): Pair<AudioRecord, Any>? {
        return try {
            val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
            val routeRender = mixClass.getField("ROUTE_FLAG_RENDER").getInt(null)
            val routeLoopBack = mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null)
            val remoteSubmix = runCatching {
                Class.forName("android.media.AudioSystem")
                    .getField("DEVICE_OUT_REMOTE_SUBMIX")
                    .getInt(null)
            }.getOrNull()
            // Prefer shared / already-held projection. Only create one when media has none
            // (remotesubmix path) — and keep it only if register needs it (don't stop a
            // freshly created proj that became current for a failed attempt while APC lives).
            val proj = sharedProjection ?: mediaProjection
            val createdProj = if (proj == null) obtainMediaProjection(ctx) else null
            val useProj = proj ?: createdProj
            // Prefer RENDER so nav leaves the speaker; fall back to LOOP_BACK-only.
            val candidates = ArrayList<Any>(2)
            if (remoteSubmix != null) {
                buildAudioMix(
                    usages = SPEECH_CAPTURE_USAGES,
                    sampleRate = SPEECH_SAMPLE_RATE,
                    channelMask = SPEECH_MIX_CHANNEL_MASK,
                    routeFlags = routeRender or routeLoopBack,
                    deviceSystemType = remoteSubmix,
                    deviceAddress = "0",
                )?.let { candidates += it }
            }
            buildAudioMix(
                usages = SPEECH_CAPTURE_USAGES,
                sampleRate = SPEECH_SAMPLE_RATE,
                channelMask = SPEECH_MIX_CHANNEL_MASK,
                routeFlags = routeLoopBack,
                deviceSystemType = null,
                deviceAddress = null,
            )?.let { candidates += it }
            if (candidates.isEmpty()) {
                runCatching { createdProj?.stop() }
                return null
            }
            var policy: Any? = null
            var mix: Any? = null
            for (candidate in candidates) {
                policy = registerAudioPolicy(ctx, listOf(candidate), useProj)
                    ?: if (useProj != null) {
                        registerAudioPolicy(ctx, listOf(candidate), null)
                    } else {
                        null
                    }
                if (policy != null) {
                    mix = candidate
                    break
                }
            }
            if (policy == null || mix == null) {
                // Only stop a projection we created; never stop media's shared projection.
                runCatching { createdProj?.stop() }
                return null
            }
            // Speech policy does not retain projection; if we created one solely for
            // register, stop it only when media is not using projection capture.
            if (createdProj != null && sharedProjection == null && mediaProjection == null) {
                runCatching { createdProj.stop() }
            }
            val record = createAudioRecordSource(policy, mix)
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record?.release() }
                unregisterAudioPolicy(audioManager, policy)
                return null
            }
            log(TAG, "speech AudioPolicy mix registered usages=${SPEECH_CAPTURE_USAGES.toList()}")
            record to policy
        } catch (e: Throwable) {
            logDebug(TAG, "speech AudioPolicy mix unavailable: ${e.message}")
            null
        }
    }

    /**
     * Reflect [AudioMix] for matching [usages]. When [deviceSystemType] is set (RENDER),
     * binds that sink via [AudioMix.Builder.setDevice]; LOOP_BACK-only mixes omit device.
     */
    private fun buildAudioMix(
        usages: IntArray,
        sampleRate: Int,
        channelMask: Int,
        routeFlags: Int,
        deviceSystemType: Int?,
        deviceAddress: String?,
    ): Any? {
        return try {
            val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val ruleBuilder = ruleBuilderClass.getDeclaredConstructor().newInstance()
            // MIX_ROLE_PLAYERS divert matching AudioTracks into this mix (VirtualAudioSession).
            runCatching {
                val mixRuleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
                val rolePlayers = mixRuleClass.getField("MIX_ROLE_PLAYERS").getInt(null)
                ruleBuilderClass.getMethod("setTargetMixRole", Int::class.javaPrimitiveType)
                    .invoke(ruleBuilder, rolePlayers)
            }
            // Never set allowPrivilegedPlaybackCapture: it requires CAPTURE_MEDIA_OUTPUT,
            // which Samsung system_server lacks (AS.AudioService → register -1). checkSelfPermission
            // on uid 1000 can lie GRANTED. Opt-out apps stay uncaptured.

            val addRule = ruleBuilderClass.methods.first { m ->
                m.name == "addRule" && m.parameterTypes.size == 2
            }
            val ruleMatchUsage = Class.forName("android.media.audiopolicy.AudioMixingRule")
                .getField("RULE_MATCH_ATTRIBUTE_USAGE")
                .getInt(null)
            for (usage in usages) {
                val attrs = AudioAttributes.Builder().setUsage(usage).build()
                addRule.invoke(ruleBuilder, attrs, ruleMatchUsage)
            }
            val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

            val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val mixBuilder = mixBuilderClass.getConstructor(
                Class.forName("android.media.audiopolicy.AudioMixingRule"),
            ).newInstance(rule)
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
            mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
                .invoke(mixBuilder, routeFlags)
            if (deviceSystemType != null) {
                // RENDER requires a device. Samsung: setDevice(int,String) is MAX-TARGET-O
                // (blocked for our targetSdk) — must use setDevice(AudioDeviceInfo).
                bindMixRenderDevice(
                    mixBuilderClass,
                    mixBuilder,
                    deviceSystemType,
                    deviceAddress ?: "0",
                )
            }
            mixBuilderClass.getMethod("build").invoke(mixBuilder)
        } catch (e: Throwable) {
            log(
                TAG,
                "buildAudioMix failed usages=${usages.toList()} routeFlags=$routeFlags " +
                    "device=$deviceSystemType/${deviceAddress}: ${formatThrowable(e)}",
            )
            null
        }
    }

    private fun formatThrowable(e: Throwable): String {
        val parts = ArrayList<String>(3)
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 4) {
            val msg = cur.message?.takeIf { it.isNotBlank() }
            parts += if (msg != null) "${cur.javaClass.simpleName}: $msg" else cur.javaClass.simpleName
            cur = cur.cause
            depth++
        }
        return parts.joinToString(" → ")
    }

    /**
     * Bind RENDER sink onto [mixBuilder].
     *
     * Samsung One UI rejects seeded REMOTE_SUBMIX [AudioDeviceInfo] with
     * "Unsupported device type on mix, not a sink" (AudioRecord seed is a source).
     * `setDevice(int,String)` is MAX-TARGET-O for our targetSdk.
     * So poke Builder fields `mDeviceSystemType` / `mDeviceAddress` first (same state
     * setDevice would write), then fall through to sink Info / Attributes / int,String.
     */
    private fun bindMixRenderDevice(
        mixBuilderClass: Class<*>,
        mixBuilder: Any,
        deviceSystemType: Int,
        deviceAddress: String,
    ) {
        val errors = ArrayList<String>(4)

        runCatching {
            val typeField = mixBuilderClass.getDeclaredField("mDeviceSystemType").also {
                it.isAccessible = true
            }
            val addrField = mixBuilderClass.getDeclaredField("mDeviceAddress").also {
                it.isAccessible = true
            }
            typeField.setInt(mixBuilder, deviceSystemType)
            addrField.set(mixBuilder, deviceAddress)
            log(
                TAG,
                "bindMixRenderDevice via Builder fields type=$deviceSystemType addr=$deviceAddress",
            )
            return
        }.onFailure { errors += "fields: ${formatThrowable(it)}" }

        val setInfo = mixBuilderClass.methods.firstOrNull { m ->
            m.name == "setDevice" &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == AudioDeviceInfo::class.java
        }
        if (setInfo != null) {
            val info = findRemoteSubmixDeviceInfo(deviceAddress)
            if (info != null) {
                runCatching {
                    setInfo.invoke(mixBuilder, info)
                    log(
                        TAG,
                        "bindMixRenderDevice via AudioDeviceInfo type=${info.type} " +
                            "addr=${info.address} id=${info.id} sink=${info.isSink}",
                    )
                    return
                }.onFailure { errors += "AudioDeviceInfo: ${formatThrowable(it)}" }
            } else {
                errors += "no sink TYPE_REMOTE_SUBMIX AudioDeviceInfo (addr=$deviceAddress)"
            }
        } else {
            errors += "no setDevice(AudioDeviceInfo)"
        }

        runCatching {
            val attrsClass = Class.forName("android.media.AudioDeviceAttributes")
            val attrs = attrsClass
                .getConstructor(Int::class.javaPrimitiveType, String::class.java)
                .newInstance(deviceSystemType, deviceAddress)
            val setAttrs = mixBuilderClass.methods.firstOrNull { m ->
                m.name == "setDevice" &&
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == attrsClass
            } ?: return@runCatching
            setAttrs.invoke(mixBuilder, attrs)
            log(TAG, "bindMixRenderDevice via AudioDeviceAttributes type=$deviceSystemType")
            return
        }.onFailure { errors += "Attributes: ${formatThrowable(it)}" }

        runCatching {
            val setDeviceIS = mixBuilderClass.getMethod(
                "setDevice",
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            setDeviceIS.invoke(mixBuilder, deviceSystemType, deviceAddress)
            log(TAG, "bindMixRenderDevice via setDevice(int,String) type=$deviceSystemType")
            return
        }.onFailure { errors += "int,String: ${formatThrowable(it)}" }

        throw IllegalStateException(
            "AudioMix.Builder.setDevice bind failed: ${errors.joinToString("; ")}",
        )
    }

    /** Resolve a **sink** REMOTE_SUBMIX for [AudioMix.Builder.setDevice]. */
    private fun findRemoteSubmixDeviceInfo(preferredAddress: String): AudioDeviceInfo? {
        val am = audioManager ?: return null
        val seen = LinkedHashSet<AudioDeviceInfo>()
        // Prefer outputs — AudioRecord seed only publishes a source and must not win.
        for (flag in intArrayOf(AudioManager.GET_DEVICES_OUTPUTS, AudioManager.GET_DEVICES_ALL)) {
            runCatching { am.getDevices(flag) }.getOrNull()?.let { seen.addAll(it) }
        }
        val remotes = seen.filter {
            it.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX && it.isSink
        }
        if (remotes.isNotEmpty()) {
            return remotes.firstOrNull {
                it.address.isNullOrEmpty() || it.address == preferredAddress
            } ?: remotes.first()
        }
        return seen.firstOrNull { d ->
            if (!d.isSink) return@firstOrNull false
            val name = d.productName?.toString().orEmpty()
            (d.address == preferredAddress || d.address == "0" || d.address.isNullOrEmpty()) &&
                (name.contains("Remote Submix", ignoreCase = true) ||
                    name.contains("remote_submix", ignoreCase = true))
        }
    }

    private fun registerAudioPolicy(
        ctx: Context,
        mixes: List<Any>,
        projection: MediaProjection? = null,
    ): Any? {
        if (mixes.isEmpty()) return null
        return try {
            val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(ctx)
            val addMix = policyBuilderClass.getMethod(
                "addMix",
                Class.forName("android.media.audiopolicy.AudioMix"),
            )
            for (mix in mixes) {
                addMix.invoke(policyBuilder, mix)
            }
            if (projection != null) {
                runCatching {
                    policyBuilderClass.getMethod(
                        "setMediaProjection",
                        MediaProjection::class.java,
                    ).invoke(policyBuilder, projection)
                }.onFailure {
                    log(TAG, "AudioPolicy.Builder.setMediaProjection failed: ${formatThrowable(it)}")
                }
            }
            val policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)
            val am = audioManager ?: return null
            val register = AudioManager::class.java.methods.first { m ->
                m.name == "registerAudioPolicy" && m.parameterTypes.size == 1
            }
            // App AIDL calling uid must not leak into AudioPolicyService permission checks.
            val identity = Binder.clearCallingIdentity()
            val regResult = try {
                register.invoke(am, policy)
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
            // @SystemApi registerAudioPolicy returns int (0 = success) or void/boolean on OEMs.
            if (regResult is Int && regResult != 0) {
                log(TAG, "registerAudioPolicy failed code=$regResult mixes=${mixes.size}")
                return null
            }
            if (regResult is Boolean && !regResult) {
                log(TAG, "registerAudioPolicy returned false mixes=${mixes.size}")
                return null
            }
            log(
                TAG,
                "registerAudioPolicy ok mixes=${mixes.size} projection=${projection != null}",
            )
            policy
        } catch (e: Throwable) {
            log(TAG, "registerAudioPolicy failed: ${formatThrowable(e)}")
            null
        }
    }

    /**
     * AOSP names this [AudioPolicy.createAudioRecordSink] (LOOP_BACK captors).
     * Older notes / OEMs may still expose createAudioRecordSource / ForMix.
     * Samsung framework.jar may hide @SystemApi from Class.methods — also walk
     * declaredMethods and fall back to a manual REMOTE_SUBMIX AudioRecord with the
     * mix registration address tag (same as AOSP createAudioRecordSink body).
     */
    private fun createAudioRecordSource(policy: Any, mix: Any): AudioRecord? {
        val preferred = listOf(
            "createAudioRecordSink",
            "createAudioRecordSource",
            "createAudioRecordForMix",
        )
        val methods = LinkedHashSet<Method>()
        var cls: Class<*>? = policy.javaClass
        while (cls != null && cls != Any::class.java) {
            for (m in cls.declaredMethods) {
                if (m.name in preferred || m.name.contains("createAudioRecord", ignoreCase = true)) {
                    methods += m
                }
            }
            for (m in cls.methods) {
                if (m.name in preferred || m.name.contains("createAudioRecord", ignoreCase = true)) {
                    methods += m
                }
            }
            cls = cls.superclass
        }
        val ordered = methods.sortedBy { m ->
            val idx = preferred.indexOf(m.name)
            if (idx >= 0) idx else preferred.size + m.parameterTypes.size
        }
        for (m in ordered) {
            val rec = runCatching {
                m.isAccessible = true
                val args = Array(m.parameterTypes.size) { i ->
                    val t = m.parameterTypes[i]
                    when {
                        t.isInstance(mix) || t.isAssignableFrom(mix.javaClass) -> mix
                        t == Integer.TYPE -> 0
                        t == java.lang.Boolean.TYPE -> false
                        else -> null
                    }
                }
                m.invoke(policy, *args)
            }.onFailure {
                logDebug(TAG, "createAudioRecord ${m.name} failed: ${formatThrowable(it)}")
            }.getOrNull() as? AudioRecord
            if (rec != null) {
                log(TAG, "AudioRecord via ${m.name} state=${rec.state}")
                return rec
            }
        }
        log(
            TAG,
            "createAudioRecordSink missing on ${policy.javaClass.name} " +
                "(found=${ordered.joinToString { it.name }}) — manual REMOTE_SUBMIX fallback",
        )
        return createAudioRecordForMixManual(mix)
    }

    /**
     * Mirrors AOSP [AudioPolicy.createAudioRecordSink]: REMOTE_SUBMIX capture with
     * `addr=<mix.getRegistration()>` tag and OUT→IN channel mask conversion.
     */
    private fun createAudioRecordForMixManual(mix: Any): AudioRecord? {
        return try {
            val mixClass = mix.javaClass
            val registration = runCatching {
                mixClass.methods.first { it.name == "getRegistration" && it.parameterTypes.isEmpty() }
                    .invoke(mix) as? String
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: run {
                log(TAG, "manual AudioRecord: mix getRegistration empty")
                return null
            }
            val mixFormat = runCatching {
                mixClass.methods.first { it.name == "getFormat" && it.parameterTypes.isEmpty() }
                    .invoke(mix) as? AudioFormat
            }.getOrNull() ?: run {
                log(TAG, "manual AudioRecord: mix getFormat null")
                return null
            }
            val inMask = runCatching {
                AudioFormat::class.java.getMethod(
                    "inChannelMaskFromOutChannelMask",
                    Int::class.javaPrimitiveType,
                ).invoke(null, mixFormat.channelMask) as Int
            }.getOrElse {
                // Stereo OUT → stereo IN; mono OUT → mono IN.
                if (mixFormat.channelCount >= 2) {
                    AudioFormat.CHANNEL_IN_STEREO
                } else {
                    AudioFormat.CHANNEL_IN_MONO
                }
            }
            val recordFormat = AudioFormat.Builder(mixFormat)
                .setChannelMask(inMask)
                .build()
            val attrsBuilder = AudioAttributes.Builder()
            runCatching {
                AudioAttributes.Builder::class.java.getMethod(
                    "setInternalCapturePreset",
                    Int::class.javaPrimitiveType,
                ).invoke(attrsBuilder, MediaRecorder.AudioSource.REMOTE_SUBMIX)
            }.getOrElse {
                log(TAG, "manual AudioRecord: setInternalCapturePreset failed: ${formatThrowable(it)}")
                return null
            }
            runCatching {
                AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
                    .invoke(attrsBuilder, "addr=$registration")
            }.onFailure {
                log(TAG, "manual AudioRecord: addTag(addr=) failed: ${formatThrowable(it)}")
                return null
            }
            runCatching {
                AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
                    .invoke(attrsBuilder, "fixedVolume")
            }
            val attrs = attrsBuilder.build()
            val minBuf = AudioRecord.getMinBufferSize(
                recordFormat.sampleRate,
                AudioFormat.CHANNEL_IN_STEREO,
                recordFormat.encoding,
            ).coerceAtLeast(
                if (recordFormat.sampleRate >= 40_000) MEDIA_READ_BYTES else SPEECH_READ_BYTES,
            )
            // AudioRecord(AudioAttributes, AudioFormat, int, int) is @SystemApi.
            val ctor = AudioRecord::class.java.getConstructor(
                AudioAttributes::class.java,
                AudioFormat::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).also { it.isAccessible = true }
            val rec = ctor.newInstance(
                attrs,
                recordFormat,
                minBuf,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            ) as AudioRecord
            log(
                TAG,
                "manual AudioRecord for mix addr=$registration state=${rec.state} " +
                    "rate=${recordFormat.sampleRate} inMask=$inMask",
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { rec.release() }
                return null
            }
            rec
        } catch (e: Throwable) {
            log(TAG, "manual AudioRecord for mix failed: ${formatThrowable(e)}")
            null
        }
    }

    private fun unregisterAudioPolicy(am: AudioManager?, policy: Any?) {
        if (am == null || policy == null) return
        runCatching {
            val m = AudioManager::class.java.methods.first { method ->
                method.name == "unregisterAudioPolicy" || method.name == "unregisterAudioPolicyAsync"
            }
            m.invoke(am, policy)
        }.onFailure { logDebug(TAG, "unregisterAudioPolicy: ${it.message}") }
    }

    // ------------------------------------------------------------------
    // MediaProjection acquisition (OEM / AOSP ladders)
    // ------------------------------------------------------------------

    /**
     * Inside system_server, local
     * [MediaProjectionManagerService.BinderService.createProjection] uses
     * [Context.checkCallingPermission], which **always denies** when
     * `callingPid == myPid` — even after [Binder.clearCallingIdentity]. Some OEMs
     * also lack `createProjectionInternal`. Falling through to REMOTE_SUBMIX skips
     * phone mute → sound plays on the handset speaker while the HU still gets PCM.
     *
     * Fix: construct the service's inner `MediaProjection` directly (same path the
     * Binder method takes after the permission gate), then try OEM/AOSP helpers.
     */
    private fun obtainMediaProjection(ctx: Context): MediaProjection? {
        runCatching {
            val proj = createProjectionDirect(ctx)
            if (proj != null) {
                log(TAG, "MediaProjection via direct service construct")
                return proj
            }
        }.onFailure { log(TAG, "direct MediaProjection construct failed", it) }

        // AOSP builds that expose createProjectionInternal.
        runCatching {
            val proj = createProjectionInternal(ctx)
            if (proj != null) {
                log(TAG, "MediaProjection via createProjectionInternal")
                return proj
            }
        }.onFailure { log(TAG, "createProjectionInternal failed", it) }

        // Cross-process only: same-process BinderService hits checkCallingPermission deny.
        runCatching {
            val proj = createProjectionViaBinder(ctx)
            if (proj != null) {
                log(TAG, "MediaProjection via IMediaProjectionManager.createProjection")
                return proj
            }
        }.onFailure { log(TAG, "createProjection binder failed", it) }

        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        if (mpm != null) {
            for (m in mpm.javaClass.methods.filter {
                it.name.contains("MediaProjection", ignoreCase = true) &&
                    it.name.startsWith("sem")
            }) {
                runCatching {
                    val args = Array(m.parameterTypes.size) { idx ->
                        defaultArgFor(m.parameterTypes[idx], idx)
                    }
                    val proj = m.invoke(mpm, *args) as? MediaProjection
                    if (proj != null) {
                        log(TAG, "MediaProjection via ${m.name}(${m.parameterTypes.size})")
                        return proj
                    }
                }.onFailure {
                    log(TAG, "${m.name} failed: ${it.message}")
                }
            }
        }

        log(TAG, "no MediaProjection — will try REMOTE_SUBMIX (phone mute skipped)")
        return null
    }

    /**
     * One UI: `(service, type, uid, packageName, targetSdk, isPrivileged)`.
     * AOSP: `(service, uid, packageName, type, isPermanentGrant)` or similar.
     */
    private fun createProjectionDirect(ctx: Context): MediaProjection? {
        val service = resolveMediaProjectionManagerService() ?: run {
            log(TAG, "MediaProjectionManagerService not found for direct construct")
            return null
        }
        val cl = service.javaClass.classLoader
        val inner = Class.forName(
            "com.android.server.media.projection.MediaProjectionManagerService\$MediaProjection",
            false,
            cl,
        )
        val pkg = "android"
        val uid = Process.SYSTEM_UID
        val typeMirroring = 1 // TYPE_MIRRORING — canProjectAudio on Samsung for 0/1/2
        val appInfo = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
        val targetSdk = appInfo?.targetSdkVersion ?: 33
        val privileged = true

        for (ctor in inner.declaredConstructors.sortedByDescending { it.parameterTypes.size }) {
            ctor.isAccessible = true
            val pts = ctor.parameterTypes
            if (pts.isEmpty() || !pts[0].isInstance(service)) continue
            val candidates: List<Array<Any?>> = when {
                // (Service, type, uid, pkg, int, boolean) — One UI: targetSdk+privileged;
                // AOSP variant: userId+permanentGrant.
                pts.size == 6 &&
                    pts[1] == Int::class.javaPrimitiveType &&
                    pts[2] == Int::class.javaPrimitiveType &&
                    pts[3] == String::class.java &&
                    pts[4] == Int::class.javaPrimitiveType &&
                    pts[5] == Boolean::class.javaPrimitiveType -> listOf(
                    arrayOf(service, typeMirroring, uid, pkg, targetSdk, privileged),
                    arrayOf(service, typeMirroring, uid, pkg, 0, true),
                )
                // AOSP-like: (Service, uid, pkg, type, permanentGrant)
                pts.size == 5 &&
                    pts[1] == Int::class.javaPrimitiveType &&
                    pts[2] == String::class.java &&
                    pts[3] == Int::class.javaPrimitiveType &&
                    pts[4] == Boolean::class.javaPrimitiveType -> listOf(
                    arrayOf(service, uid, pkg, typeMirroring, true),
                )
                else -> continue
            }
            for (args in candidates) {
                val iProj = runCatching { ctor.newInstance(*args) }.onFailure {
                    logDebug(TAG, "MediaProjection ctor ${pts.size} failed: ${it.message}")
                }.getOrNull() ?: continue
                val wrapped = wrapIMediaProjection(ctx, iProj)
                if (wrapped != null) {
                    log(TAG, "direct MediaProjection ctor args=${pts.size} type=$typeMirroring uid=$uid")
                    return wrapped
                }
            }
        }
        log(
            TAG,
            "no matching MediaProjection ctor among ${inner.declaredConstructors.size} " +
                "(service=${service.javaClass.name})",
        )
        return null
    }

    private fun createProjectionInternal(ctx: Context): MediaProjection? {
        val service = resolveMediaProjectionManagerService() ?: return null
        val method = service.javaClass.declaredMethods
            .filter { it.name == "createProjectionInternal" }
            .maxByOrNull { it.parameterTypes.size }
            ?.also { it.isAccessible = true }
            ?: return null
        val args = Array(method.parameterTypes.size) { idx ->
            defaultArgFor(method.parameterTypes[idx], idx, packageName = "android")
        }
        val iProj = method.invoke(service, *args) ?: return null
        return wrapIMediaProjection(ctx, iProj)
    }

    private fun createProjectionViaBinder(ctx: Context): MediaProjection? {
        val stub = mediaProjectionManagerStub() ?: return null
        // Same-process BinderService: checkCallingPermission always denies (callingPid==myPid).
        if (stub.javaClass.name.contains("BinderService")) {
            logDebug(TAG, "skip createProjection binder — local BinderService (checkCallingPermission deny)")
            return null
        }
        val create = stub.javaClass.methods
            .filter { it.name == "createProjection" }
            .maxByOrNull { it.parameterTypes.size }
            ?: return null
        val args = Array(create.parameterTypes.size) { idx ->
            defaultArgFor(create.parameterTypes[idx], idx, packageName = "android")
        }
        val iProj = create.invoke(stub, *args) ?: return null
        return wrapIMediaProjection(ctx, iProj)
    }

    private fun resolveMediaProjectionManagerService(): Any? {
        runCatching {
            val localServices = Class.forName("com.android.server.LocalServices")
            val svcClass = Class.forName(
                "com.android.server.media.projection.MediaProjectionManagerService",
            )
            val getService = localServices.methods.firstOrNull { m ->
                m.name == "getService" &&
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == Class::class.java
            }
            val got = getService?.invoke(null, svcClass)
            if (got != null) return got
        }
        val stub = mediaProjectionManagerStub() ?: return null
        return unwrapMediaProjectionManagerService(stub)
    }

    private fun mediaProjectionManagerStub(): Any? {
        val smClass = Class.forName("android.os.ServiceManager")
        val binder = smClass.getMethod("getService", String::class.java)
            .invoke(null, Context.MEDIA_PROJECTION_SERVICE) as? IBinder
            ?: return null
        return Class.forName("android.media.projection.IMediaProjectionManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    }

    private fun unwrapMediaProjectionManagerService(stub: Any): Any? {
        var c: Class<*>? = stub.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (f.name != "this$0" && !f.name.startsWith("this$")) continue
                runCatching {
                    f.isAccessible = true
                    val outer = f.get(stub) ?: return@runCatching
                    if (outer.javaClass.name.contains("MediaProjectionManagerService")) {
                        return outer
                    }
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun wrapIMediaProjection(ctx: Context, iProj: Any): MediaProjection? {
        val iProjClass = Class.forName("android.media.projection.IMediaProjection")
        val impl = when {
            iProjClass.isInstance(iProj) -> iProj
            iProj is IBinder -> {
                Class.forName("android.media.projection.IMediaProjection\$Stub")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, iProj)
            }
            else -> {
                // Service inner MediaProjection extends Stub — use as Binder.
                val asBinder = iProj.javaClass.methods.firstOrNull {
                    it.name == "asBinder" && it.parameterTypes.isEmpty()
                }
                val token = asBinder?.invoke(iProj) as? IBinder
                if (token != null) {
                    Class.forName("android.media.projection.IMediaProjection\$Stub")
                        .getMethod("asInterface", IBinder::class.java)
                        .invoke(null, token)
                } else {
                    iProj
                }
            }
        } ?: return null
        val ctor = MediaProjection::class.java.declaredConstructors.firstOrNull { c ->
            c.parameterTypes.size >= 2 &&
                c.parameterTypes[0] == Context::class.java &&
                iProjClass.isAssignableFrom(c.parameterTypes[1])
        }?.also { it.isAccessible = true }
            ?: return null
        return ctor.newInstance(ctx, impl) as? MediaProjection
    }

    private fun defaultArgFor(
        type: Class<*>,
        index: Int,
        packageName: String = "android",
    ): Any? = when {
        type == Int::class.javaPrimitiveType -> when (index) {
            0 -> Process.myUid() // processUid
            2 -> 0 // TYPE_SCREEN_CAPTURE / displayId depending on overload
            else -> 0
        }
        type == Boolean::class.javaPrimitiveType -> true // permanentGrant — keep capture alive
        type == String::class.java -> packageName
        type == Long::class.javaPrimitiveType -> 0L
        type.name == "android.os.UserHandle" -> {
            runCatching {
                UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType)
                    .invoke(null, 0)
            }.getOrElse {
                UserHandle::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                    .also { it.isAccessible = true }
                    .newInstance(0)
            }
        }
        else -> null
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

    private fun scheduleWatchdog(handler: Handler) {
        handler.postDelayed(
            {
                if (!desired.get()) {
                    // Session released. Also restore when the tap is already down but
                    // setStreamMute / device index was left held across a rearm gap.
                    if (armed.get() || phoneMuted || speakerDeviceMuted) setEnabled(false)
                    return@postDelayed
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
                    return@postDelayed
                }
                if (!armed.get()) {
                    scheduleRearm("watchdog-unarmed")
                    if (desired.get() && captureHandler === handler) {
                        scheduleWatchdog(handler)
                    }
                    return@postDelayed
                }
                // Revive sticky-mute if One UI killed / deadlocked the enforce thread.
                if (mediaMode == "playback-capture") {
                    synchronized(opLock) {
                        if (armed.get()) ensureMuteEnforceLocked()
                    }
                }
                // Lifetime max stays for "ever heard audio"; window peak shows if tap is live now.
                val peak = mediaPcmPeak.getAndSet(0)
                val dropNow = mediaRing.dropped
                val dropPrev = lastWatchdogDropMedia.getAndSet(dropNow)
                val dropDelta = (dropNow - dropPrev).coerceAtLeast(0L)
                // RENDER path must not fall back to device mute (Samsung zeros remotesubmix).
                if (mediaMode == "audio-policy-render" && peak == 0 && totalMediaPulled > 0L) {
                    log(
                        TAG,
                        "watchdog: audio-policy-render pcmPeak=0 with pulls — tap silent " +
                            "(not muting; check OEM RENDER)",
                    )
                }
                if (peak == 0 && totalMediaPulled > 0L &&
                    (mediaMode == "remote-submix" || mediaMode == "playback-capture")
                ) {
                    log(
                        TAG,
                        "watchdog: $mediaMode windowPeak=0 — capture silence " +
                            "(HU may still get empty AAC; check app volume / route)",
                    )
                }
                // Stuck on remotesubmix with ongoing ring overflow → in-place RENDER
                // upgrade (keeps AudioDeviceInfo). Never tear via force-rearm.
                if (mediaMode == "remote-submix" &&
                    dropDelta >= REMOTE_SUBMIX_DROP_REARM_BYTES &&
                    totalMediaPulled > 0L
                ) {
                    if (audioPolicyRenderUnavailable) {
                        log(
                            TAG,
                            "watchdog: remote-submix dropDelta=${dropDelta / 1024}KB — " +
                                "skip RENDER upgrade (audio-policy-render unavailable)",
                        )
                    } else {
                        log(
                            TAG,
                            "watchdog: remote-submix dropDelta=${dropDelta / 1024}KB — " +
                                "in-place upgrade to audio-policy-render",
                        )
                        synchronized(opLock) {
                            if (mediaMode == "remote-submix" &&
                                armed.get() &&
                                !audioPolicyRenderUnavailable
                            ) {
                                if (!tryUpgradeToAudioPolicyRenderLocked()) {
                                    audioPolicyRenderUnavailable = true
                                    log(
                                        TAG,
                                        "watchdog in-place RENDER upgrade failed — " +
                                            "further dropDelta upgrades suppressed",
                                    )
                                }
                            }
                        }
                    }
                }
                val muteAlive = muteMonitorThread?.isAlive == true
                log(
                    TAG,
                    "watchdog media=$mediaMode speech=$speechMode desired=true " +
                        "pulledMedia=${totalMediaPulled / 1024}KB pulledSpeech=${totalSpeechPulled / 1024}KB " +
                        "pcmPeak=$peak dropMedia=${dropNow / 1024}KB " +
                        "dropDelta=${dropDelta / 1024}KB muteThread=$muteAlive",
                )
                scheduleWatchdog(handler)
            },
            WATCHDOG_PERIOD_MS,
        )
    }

    /**
     * Simple SPSC ring for one producer / one Binder consumer.
     * When backlog exceeds [highWatermarkBytes], drop oldest down to [lowWatermarkBytes]
     * on both write and pull so stale PCM is discarded even while Binder is late.
     */
    private class PcmRing(
        private val capacity: Int,
        private val highWatermarkBytes: Int,
        private val lowWatermarkBytes: Int,
    ) {
        private val buf = ByteArray(capacity)
        private val lock = Any()
        private var head = 0
        private var tail = 0
        private var available = 0
        var dropped = 0L
            private set

        /** Bytes currently buffered (for watchdog / pull-timeout diagnostics). */
        val availableBytes: Int
            get() = synchronized(lock) { available }

        fun clear() {
            synchronized(lock) {
                head = 0
                tail = 0
                available = 0
                dropped = 0L
            }
        }

        fun write(data: ByteArray) {
            if (data.isEmpty()) return
            synchronized(lock) {
                // Keep only what fits; prefer the newest samples when the payload is huge.
                var srcOff = 0
                var srcLen = data.size
                if (srcLen > capacity) {
                    srcOff = srcLen - capacity
                    srcLen = capacity
                }
                val free = capacity - available
                if (srcLen > free) {
                    // Round drop UP to a 4-byte PCM frame so available + srcLen <= capacity.
                    // Rounding down left available > capacity and broke pull wrap math.
                    var drop = srcLen - free
                    val rem = drop % 4
                    if (rem != 0) drop += 4 - rem
                    if (drop > available) drop = available - (available % 4)
                    if (drop > 0) {
                        tail = (tail + drop) % capacity
                        available -= drop
                        dropped += drop.toLong()
                    }
                }
                var off = 0
                while (off < srcLen) {
                    val chunk = minOf(srcLen - off, capacity - head)
                    if (chunk <= 0) break
                    System.arraycopy(data, srcOff + off, buf, head, chunk)
                    head = (head + chunk) % capacity
                    available += chunk
                    off += chunk
                }
                if (available > capacity) available = capacity
                // Drop-to-live on the producer side: do not wait for a late Binder pull
                // to discover a full second of backlog already queued for TX.
                trimToWatermarkLocked()
            }
        }

        fun pull(maxBytes: Int): ByteArray {
            synchronized(lock) {
                trimToWatermarkLocked()
                val take = minOf(available, maxBytes.coerceAtLeast(0))
                if (take <= 0) return ByteArray(0)
                val arr = ByteArray(take)
                var off = 0
                while (off < take) {
                    // Remaining in the ring is [available], remaining to fill is [take - off].
                    // Never use (available - off): after a wrap chunk that double-subtracts
                    // and can yield chunk==0 → spin forever while holding the lock, which
                    // wedges every Binder pullCarPlayAudio + the capture writer (no car
                    // audio, phone stays muted).
                    val chunk = minOf(take - off, available, capacity - tail)
                    if (chunk <= 0) break
                    System.arraycopy(buf, tail, arr, off, chunk)
                    tail = (tail + chunk) % capacity
                    available -= chunk
                    off += chunk
                }
                return if (off == take) arr else arr.copyOf(off)
            }
        }

        private fun trimToWatermarkLocked() {
            val high = highWatermarkBytes.coerceIn(0, capacity)
            val low = lowWatermarkBytes.coerceIn(0, high)
            if (available <= high) return
            var drop = available - low
            drop -= drop % 4
            if (drop <= 0) return
            tail = (tail + drop) % capacity
            available -= drop
            dropped += drop.toLong()
        }
    }
}
