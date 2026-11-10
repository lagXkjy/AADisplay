package io.github.nitsuya.aa.display.xposed.carplay

import android.media.AudioAttributes
import android.media.AudioFormat

/**
 * CarPlay stream formats shared by the capture stack: [CarPlayAudioCapture] (rings,
 * readers), [CarPlayAudioPolicy] (mix build) and the manual AudioRecord fallback.
 *
 * DiPlay wire formats:
 * - **media**  — PCM_FORMAT[0x8000] = 48 kHz stereo (SETUP `audioType=media`)
 * - **speech** — PCM_FORMAT[0x10]   = 16 kHz mono   (SETUP `audioType=speech`)
 */
internal object CarPlayAudioFormats {
    /** DiPlay entertainment media: PCM_FORMAT[0x8000] = 48 kHz stereo. */
    const val MEDIA_SAMPLE_RATE = 48_000
    /** AudioRecord / playback-capture use IN masks. */
    const val MEDIA_CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO
    /** AudioMix RENDER/LOOP_BACK mixes use OUT masks (CTS / AOSP). */
    const val MEDIA_MIX_CHANNEL_MASK = AudioFormat.CHANNEL_OUT_STEREO
    /** DiPlay voice bit PCM_FORMAT[0x10] = 16 kHz mono. */
    const val SPEECH_SAMPLE_RATE = 16_000
    const val SPEECH_MIX_CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO

    val MEDIA_BYTES_PER_MS: Int = MEDIA_SAMPLE_RATE * 2 /* ch */ * 2 /* s16 */ / 1000
    val SPEECH_BYTES_PER_MS: Int = SPEECH_SAMPLE_RATE * 1 /* ch */ * 2 /* s16 */ / 1000

    /**
     * Hard cap ~200ms @ 48 kHz stereo s16. Prefer a short skip over lip-lag; a taller
     * ring only let Binder stalls ship stale PCM while video always takes the latest frame.
     */
    val MEDIA_RING_BYTES: Int = MEDIA_BYTES_PER_MS * 200
    /** Hard cap ~400ms @ 16 kHz mono s16. */
    val SPEECH_RING_BYTES: Int = SPEECH_BYTES_PER_MS * 400

    /**
     * Drop-to-live hysteresis: trim when backlog exceeds [LIVE_HIGH_MS], down to
     * [LIVE_LOW_MS] (under DiPlay audioLatencyMs). Keep the floor near one–two packets
     * so a matched producer/consumer cannot sit forever at ~200ms of sticky latency.
     * Applied on both write and pull so capture drops stale PCM before TX recovers.
     */
    const val LIVE_HIGH_MS = 60
    const val LIVE_LOW_MS = 20
    val MEDIA_HIGH_WATERMARK_BYTES: Int = MEDIA_BYTES_PER_MS * LIVE_HIGH_MS
    val MEDIA_LOW_WATERMARK_BYTES: Int = MEDIA_BYTES_PER_MS * LIVE_LOW_MS
    val SPEECH_HIGH_WATERMARK_BYTES: Int = SPEECH_BYTES_PER_MS * LIVE_HIGH_MS
    val SPEECH_LOW_WATERMARK_BYTES: Int = SPEECH_BYTES_PER_MS * LIVE_LOW_MS

    const val MEDIA_READ_BYTES = 3_840 // 20 ms @ 48 kHz stereo s16
    const val SPEECH_READ_BYTES = 640 // 20 ms @ 16 kHz mono s16

    /**
     * RENDER media usages that never trip CAPTURE_VOICE_COMMUNICATION_OUTPUT.
     * Voice/call usages are appended only when that permission is granted
     * (see [CarPlayAudioPolicy.mediaCaptureUsagesForRender]).
     */
    val MEDIA_CAPTURE_USAGES_BASE = intArrayOf(
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_GAME,
        AudioAttributes.USAGE_UNKNOWN,
        AudioAttributes.USAGE_NOTIFICATION,
        AudioAttributes.USAGE_NOTIFICATION_EVENT,
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        AudioAttributes.USAGE_ALARM,
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
    )

    val MEDIA_CAPTURE_USAGES_VOICE = intArrayOf(
        AudioAttributes.USAGE_VOICE_COMMUNICATION,
        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
    )

    /** Full list for logging / mute commentary; RENDER may use a subset. */
    val MEDIA_CAPTURE_USAGES = MEDIA_CAPTURE_USAGES_BASE + MEDIA_CAPTURE_USAGES_VOICE

    /** Nav / assistant on the 16 kHz speech CarPlay stream (DiPlay; skipped on MIB3). */
    val SPEECH_CAPTURE_USAGES = intArrayOf(
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
        AudioAttributes.USAGE_ASSISTANT,
    )
}
