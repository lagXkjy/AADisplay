package io.github.nitsuya.aa.display.xposed.carplay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Binder
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.lang.reflect.Method

/**
 * AudioPolicy / AudioMix reflection tier for the CarPlay system_server tap
 * (see [CarPlayAudioCapture]). Stateless object: the caller passes the
 * [AudioManager] it wants bound, and [CarPlayMediaProjectionAcquire] is used
 * for projection-backed registration.
 *
 * Samsung/AOSP gates (logcat AS.AudioService):
 * - allowPrivilegedPlaybackCapture → CAPTURE_MEDIA_OUTPUT (we lack it) — never set
 * - VOICE_* + LOOP_BACK_RENDER → CAPTURE_VOICE_COMMUNICATION_OUTPUT — omitted
 * - LOOP_BACK_RENDER + MediaProjection → allowed via canProjectAudio (preferred)
 */
internal object CarPlayAudioPolicy {
    private const val TAG = "AAD_CarPlayAudioCap"

    internal data class AudioPolicyRenderArm(
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
    fun mediaCaptureUsagesForRender(): IntArray {
        log(
            TAG,
            "audio-policy-render: omit VOICE_* usages " +
                "(CAPTURE_VOICE_COMMUNICATION_OUTPUT unavailable) — calls stay on phone",
        )
        return CarPlayAudioFormats.MEDIA_CAPTURE_USAGES_BASE
    }

    /**
     * True-route media + speech: both RENDER|LOOP_BACK onto REMOTE_SUBMIX so matching
     * players leave the phone speaker; capture via [createAudioRecordSource].
     * Soft-fails → caller falls back to playback-capture / remotesubmix.
     */
    fun openRenderArm(ctx: Context, am: AudioManager?): AudioPolicyRenderArm? {
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
                am = am,
                usages = mediaUsages,
                sampleRate = CarPlayAudioFormats.MEDIA_SAMPLE_RATE,
                channelMask = CarPlayAudioFormats.MEDIA_MIX_CHANNEL_MASK,
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
                am = am,
                usages = CarPlayAudioFormats.SPEECH_CAPTURE_USAGES,
                sampleRate = CarPlayAudioFormats.SPEECH_SAMPLE_RATE,
                channelMask = CarPlayAudioFormats.SPEECH_MIX_CHANNEL_MASK,
                routeFlags = renderLoop,
                deviceSystemType = remoteSubmix,
                deviceAddress = "0",
            ) ?: buildAudioMix(
                am = am,
                usages = CarPlayAudioFormats.SPEECH_CAPTURE_USAGES,
                sampleRate = CarPlayAudioFormats.SPEECH_SAMPLE_RATE,
                channelMask = CarPlayAudioFormats.SPEECH_MIX_CHANNEL_MASK,
                routeFlags = routeLoopBack,
                deviceSystemType = null,
                deviceAddress = null,
            )

            // MediaProjection satisfies LOOP_BACK_RENDER without CAPTURE_* (AOSP).
            val proj = CarPlayMediaProjectionAcquire.acquire(ctx)
            if (proj != null) {
                log(TAG, "audio-policy-render: MediaProjection attached for register")
            }

            // Register media+speech together; if OEM rejects the combo, media-only still
            // gives true route off speaker (speech stays none).
            var usedProjection = false
            var policy = registerAudioPolicy(
                am,
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
                policy = registerAudioPolicy(am, ctx, listOf(mediaMix), proj)
                if (policy != null) usedProjection = proj != null
                speechMixRegistered = false
            }
            if (policy == null && proj != null) {
                // Some OEMs reject projection-backed RENDER; retry without.
                log(TAG, "audio-policy-render: projection register failed — retry without")
                policy = registerAudioPolicy(am, ctx, buildList {
                    add(mediaMix)
                    if (speechMix != null) add(speechMix)
                }, null)
                speechMixRegistered = speechMix != null && policy != null
                if (policy == null && speechMix != null) {
                    policy = registerAudioPolicy(am, ctx, listOf(mediaMix), null)
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
                unregisterAudioPolicy(am, policy)
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
                    "usagesSpeech=${CarPlayAudioFormats.SPEECH_CAPTURE_USAGES.toList()} speechMode=$sMode " +
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
    fun openSpeechMix(
        ctx: Context,
        am: AudioManager?,
        sharedProjection: MediaProjection?,
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
            // Prefer the shared media projection. Only create one when media has none
            // (remotesubmix path) — and keep it only if register needs it (don't stop a
            // freshly created proj that became current for a failed attempt while APC lives).
            val proj = sharedProjection
            val createdProj = if (proj == null) CarPlayMediaProjectionAcquire.acquire(ctx) else null
            val useProj = proj ?: createdProj
            // Prefer RENDER so nav leaves the speaker; fall back to LOOP_BACK-only.
            val candidates = ArrayList<Any>(2)
            if (remoteSubmix != null) {
                buildAudioMix(
                    am = am,
                    usages = CarPlayAudioFormats.SPEECH_CAPTURE_USAGES,
                    sampleRate = CarPlayAudioFormats.SPEECH_SAMPLE_RATE,
                    channelMask = CarPlayAudioFormats.SPEECH_MIX_CHANNEL_MASK,
                    routeFlags = routeRender or routeLoopBack,
                    deviceSystemType = remoteSubmix,
                    deviceAddress = "0",
                )?.let { candidates += it }
            }
            buildAudioMix(
                am = am,
                usages = CarPlayAudioFormats.SPEECH_CAPTURE_USAGES,
                sampleRate = CarPlayAudioFormats.SPEECH_SAMPLE_RATE,
                channelMask = CarPlayAudioFormats.SPEECH_MIX_CHANNEL_MASK,
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
                policy = registerAudioPolicy(am, ctx, listOf(candidate), useProj)
                    ?: if (useProj != null) {
                        registerAudioPolicy(am, ctx, listOf(candidate), null)
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
            if (createdProj != null && sharedProjection == null) {
                runCatching { createdProj.stop() }
            }
            val record = createAudioRecordSource(policy, mix)
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record?.release() }
                unregisterAudioPolicy(am, policy)
                return null
            }
            log(TAG, "speech AudioPolicy mix registered usages=${CarPlayAudioFormats.SPEECH_CAPTURE_USAGES.toList()}")
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
    fun buildAudioMix(
        am: AudioManager?,
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
                    am = am,
                    mixBuilderClass = mixBuilderClass,
                    mixBuilder = mixBuilder,
                    deviceSystemType = deviceSystemType,
                    deviceAddress = deviceAddress ?: "0",
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

    fun formatThrowable(e: Throwable): String {
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
        am: AudioManager?,
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
            val info = findRemoteSubmixDeviceInfo(am, deviceAddress)
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
    private fun findRemoteSubmixDeviceInfo(am: AudioManager?, preferredAddress: String): AudioDeviceInfo? {
        val am = am ?: return null
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

    fun registerAudioPolicy(
        am: AudioManager?,
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
            val am = am ?: return null
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
    fun createAudioRecordSource(policy: Any, mix: Any): AudioRecord? {
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
                if (recordFormat.sampleRate >= 40_000) {
                    CarPlayAudioFormats.MEDIA_READ_BYTES
                } else {
                    CarPlayAudioFormats.SPEECH_READ_BYTES
                },
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

    fun unregisterAudioPolicy(am: AudioManager?, policy: Any?) {
        if (am == null || policy == null) return
        runCatching {
            val m = AudioManager::class.java.methods.first { method ->
                method.name == "unregisterAudioPolicy" || method.name == "unregisterAudioPolicyAsync"
            }
            m.invoke(am, policy)
        }.onFailure { logDebug(TAG, "unregisterAudioPolicy: ${it.message}") }
    }
}
