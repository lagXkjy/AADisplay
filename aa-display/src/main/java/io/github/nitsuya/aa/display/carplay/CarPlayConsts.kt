package io.github.nitsuya.aa.display.carplay

/**
 * Wireless CarPlay Source constants for the AADisplay egress path.
 * Protocol shapes follow publicly documented wireless CarPlay / AirPlay control
 * conventions; no third-party CarPlay firmware is vendored here.
 * External read-only refs (iAP2 / auth / Bonjour): docs/CARPLAY_REFS.md
 * (45clouds/WirelessCarPlay, MIT — do not copy binaries/PDFs into this tree).
 */
object CarPlayConsts {
    const val TAG = "AADisplay_CarPlay"

    const val ACTION_CONNECT = "io.github.nitsuya.aa.display.carplay.CONNECT"
    const val ACTION_STOP = "io.github.nitsuya.aa.display.carplay.STOP"

    /** Bonjour / NSD type used by wireless CarPlay control (phone advertises). */
    const val BONJOUR_TYPE = "_carplay-ctrl._tcp."

    /**
     * Accessory AirPlay endpoint (HU advertises). Audi MIB3 often never sends
     * `0x4301`; dial host:port comes from this discovery instead.
     */
    const val AIRPLAY_BONJOUR_TYPE = "_airplay._tcp."

    const val NOTIFICATION_CHANNEL_ID = "carplay_projection"
    const val NOTIFICATION_ID = 0x0C01

    /** Default test / fallback encode size before HU negotiates. */
    const val DEFAULT_VIDEO_WIDTH = 1920
    const val DEFAULT_VIDEO_HEIGHT = 720

    /**
     * Negotiated / supplied frame rate. The DiPlay host ignores the SETUP `fps` field (its
     * decoder runs at its own 30..60 user setting), but the supply side must still produce
     * 60 frames/s so a 60-configured host is never starved; other hosts (CarPlay Simulator)
     * pace off the SETUP value.
     */
    const val DEFAULT_VIDEO_FPS = 60

    /** Degraded supply rate when the link / encoder cannot hold [DEFAULT_VIDEO_FPS]. */
    const val DEGRADED_VIDEO_FPS = 30

    /**
     * Periodic IDR interval (seconds). CSM Audi.11 uses 1s with High@IDR (not
     * intra-refresh). Intra-refresh-only (I_FRAME_INTERVAL=-1) left Audi black and
     * killed control ~8s later. DiPlay can also request an IDR via `forceKeyFrame`.
     */
    const val I_FRAME_INTERVAL_SEC = 1

    /** Sustained actual-fps below this share of target triggers the 60→30 downgrade. */
    const val DEGRADE_RATE_FRACTION = 0.5f

    /**
     * Content-driven supply cap (GlCompositor). When OES arrivals stay below
     * [CONTENT_SLOW_FPS] (e.g. Douyin ~25 Hz HEVC), tick 60→30 so we stop
     * re-encoding duplicate frames. Recover when content climbs above
     * [CONTENT_FAST_FPS]. Does not recycle MediaCodec — only the GL pump interval.
     */
    const val CONTENT_SLOW_FPS = 36f
    const val CONTENT_FAST_FPS = 50f
    /** 2 s windows × 2 → ~4 s to engage the content cap. */
    const val CONTENT_CADENCE_WINDOW_MS = 2_000L
    const val CONTENT_SLOW_WINDOWS = 2
    const val CONTENT_FAST_WINDOWS = 3

    /**
     * AVC encode resolution. The HU decodes whatever the source sends and scales to its
     * display; the buffer-input RGB→NV12 conversion cost scales with pixel count, so encode
     * at 1280x720 instead of the 1920x720 composite (panes stay 960x720, scaled into the
     * 1280x720 canvas). 16-aligned so the encoder buffer size equals the tee canvas
     * (no per-frame rescale in the encoder).
     */
    const val ENCODE_WIDTH = 1280
    const val ENCODE_HEIGHT = 720

    /**
     * Density for Host overlay Views measured in encode pixels.
     * 160 → 1dp=1px on 1280×720 so AA picker dp/sp match the car canvas.
     */
    const val ENCODE_UI_DENSITY_DPI = 160

    /**
     * iPhone / Source advertises this SDP UUID so the accessory can dial back (reconnect path).
     * @see IAP2_ACCESSORY_RFCOMM_UUID
     */
    const val IAP2_PHONE_RFCOMM_UUID = "00000000-deca-fade-deca-deafdecacafe"

    /**
     * Accessory / HU (diplay) advertises this UUID; Source dials it on first connect.
     * Audi MMI also dials this record inbound, so the phone listens on it as well as …cafe.
     */
    const val IAP2_ACCESSORY_RFCOMM_UUID = "00000000-deca-fade-deca-deafdecacaff"

    /**
     * Wireless CarPlay capability UUID (EIR AD type 0x07 / SDP). VAG btstack sets the
     * wireless-CarPlay bit only when this UUID is present; iAP2 RFCOMM UUIDs alone are not enough.
     */
    const val WIRELESS_CARPLAY_CAPABILITY_UUID = "ec884348-cd41-40a2-9727-575d50bf1fd3"
}
