package io.github.nitsuya.aa.display.xposed.carplay

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phone-side sink mute for the CarPlay capture tap (see [CarPlayAudioCapture]).
 * Owns the saved-volume / mute-flag state so a rearm keeps mute across tap
 * rebuild, and runs the sticky 250ms CSM-style enforce thread + volume
 * receiver.
 *
 * Gates come from the capture owner as lambdas:
 * - [liveGate] — capture armed in a mode that mutes (mediaMode == "playback-capture")
 * - [holdGate] — session still [CarPlayAudioCapture.desired] (keep mute across rearm)
 *
 * **Must NOT be driven while holding [CarPlayAudioCapture]'s opLock for the
 * volume calls**: [AudioManager.setStreamVolume] can dispatch
 * VOLUME_CHANGED_ACTION synchronously; the receiver and enforce thread therefore
 * only snapshot gates and never take the capture opLock (deadlock history:
 * receiver re-entered opLock → mute thread died, phone speaker came back).
 */
internal class CarPlayPhoneMute(
    private val context: Context,
    private val audioManager: AudioManager,
    private val liveGate: () -> Boolean,
    private val holdGate: () -> Boolean,
) {
    private val TAG = "AAD_CarPlayAudioCap"

    // STREAM_VOICE_ASSISTANCE = 13 (removed from the API 36 AudioManager surface).
    private val STREAM_VOICE_ASSISTANCE = 13
    /** CSM re-enforces local mute every 250ms — some OEMs restore MUSIC when apps start. */
    private val MUTE_ENFORCE_MS = 250L
    private val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    private val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

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

    /** True once [armMute] muted phone streams (read by the capture owner). */
    var phoneMuted = false
        private set
    /** Remotesubmix path: speaker-device volume zeroed (stream mute would silence the mix). */
    var speakerDeviceMuted = false
        private set

    /** Parallel to [mutedStreamTypes]. */
    var savedVolumes: IntArray? = null
        private set
    /** Parallel to [savedVolumes] — only streams that accepted get/set on this OEM. */
    private var mutedStreamTypes: IntArray? = null
    /** Parallel to [mutedStreamTypes]. -1 = this OEM has no fine-volume API. */
    private var savedFineVolumes: IntArray? = null
    private var savedSpeakerVolumes: IntArray? = null
    private var savedSpeakerStreamTypes: IntArray? = null

    /** API 33 3-arg vs API 36 4-arg / setStreamVolumeIndexAS. */
    @Volatile private var volumeIndexFns: VolumeIndexFns? = null

    private val muteEnforceActive = AtomicBoolean(false)
    @Volatile private var muteMonitorThread: Thread? = null
    private var volumeReceiver: BroadcastReceiver? = null

    /** Watchdog: is the enforce thread alive? */
    val muteThreadAlive: Boolean
        get() = muteMonitorThread?.isAlive == true

    /** Enforce gate: live playback-capture, or mute held for a still-desired session. */
    fun shouldEnforce(): Boolean = liveGate() || (phoneMuted && holdGate())

    /** "playback-capture" arm section: mute streams + local devices, start enforce thread. */
    fun armMute() {
        phoneMuted = mutePhoneStreams(audioManager)
        speakerDeviceMuted = muteLocalPlaybackDevices()
        ensureMuteEnforceLocked()
    }

    /** Drop stream/device mute left over from a keep-mute rearm into a non-mute mode. */
    fun clearHeldMute(reason: String) {
        if (!phoneMuted && !speakerDeviceMuted) return
        log(TAG, "clear held mute for $reason")
        stopMuteEnforceLocked()
        if (phoneMuted) restorePhoneStreams(audioManager)
        if (speakerDeviceMuted) restoreLocalPlaybackDevices()
        clearSavedState()
    }

    /**
     * Full disarm: always stop the enforce thread (even when no mute held — the
     * thread may still be running), restore what was muted, drop saved state.
     */
    fun teardown() {
        stopMuteEnforceLocked()
        if (phoneMuted) {
            restorePhoneStreams(audioManager)
        }
        if (speakerDeviceMuted) {
            restoreLocalPlaybackDevices()
        }
        clearSavedState()
    }

    private fun clearSavedState() {
        savedVolumes = null
        savedFineVolumes = null
        mutedStreamTypes = null
        phoneMuted = false
        speakerDeviceMuted = false
        savedSpeakerVolumes = null
        savedSpeakerStreamTypes = null
    }

    /**
     * Rearm keep-mute: preserve saved volumes / phoneMuted across tap rebuild;
     * drop saved state for mute tiers that were never held.
     */
    fun pruneUnheldState() {
        if (!phoneMuted) {
            mutedStreamTypes = null
            savedVolumes = null
            savedFineVolumes = null
        }
        if (!speakerDeviceMuted) {
            savedSpeakerVolumes = null
            savedSpeakerStreamTypes = null
        }
    }

    /**
     * CSM-style sticky mute: some OEMs restore STREAM_MUSIC when media apps start,
     * so a one-shot mute leaves the phone speaker live while AAC still flows.
     */
    fun enforceNow() {
        // Hold mute across rearm windows (desired + phoneMuted) even while briefly unarmed.
        if (!shouldEnforce()) return
        val streams = mutedStreamTypes?.toList() ?: MUTED_STREAMS
        var touched = false
        var restoredVol: Int? = null
        for (stream in streams) {
            val muted = runCatching { audioManager.isStreamMute(stream) }.getOrNull()
            if (muted == false) {
                runCatching { audioManager.setStreamMute(stream, true) }
                touched = true
            }
            val vol = runCatching { audioManager.getStreamVolume(stream) }.getOrNull()
            if (vol != null && vol != 0) {
                if (stream == AudioManager.STREAM_MUSIC) restoredVol = vol
                runCatching { audioManager.setStreamVolume(stream, 0, 0) }
                // OEM fine-volume APIs can leave speaker audible after stream index 0.
                zeroOemFineVolume(audioManager, stream)
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

    private fun restorePhoneStreams(am: AudioManager) {
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
    fun ensureMuteEnforceLocked() {
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
                            if (shouldEnforce()) {
                                enforceNow()
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
        if (volumeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!muteEnforceActive.get() || intent == null) return
                val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, Int.MIN_VALUE)
                if (stream != Int.MIN_VALUE && stream != AudioManager.STREAM_MUSIC) return
                // No capture opLock here — see enforceNow. Snapshot gates only.
                if (!shouldEnforce()) return
                val vol = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
                val muted = runCatching { audioManager.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrNull()
                if (vol == 0 && muted == true) return
                enforceNow()
            }
        }
        val filter = IntentFilter(VOLUME_CHANGED_ACTION)
        runCatching {
            this.context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            volumeReceiver = receiver
        }.onFailure {
            logDebug(TAG, "volume receiver register failed: ${it.message}")
        }
    }

    private fun unregisterVolumeReceiverLocked() {
        val receiver = volumeReceiver ?: return
        volumeReceiver = null
        runCatching { context.unregisterReceiver(receiver) }
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
    @SuppressLint("PrivateApi")
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

    @SuppressLint("PrivateApi")
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
}
