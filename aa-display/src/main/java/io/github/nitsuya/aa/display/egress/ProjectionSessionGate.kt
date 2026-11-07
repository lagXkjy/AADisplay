package io.github.nitsuya.aa.display.egress

import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-process flag for whether a **secondary egress** session is live
 * (wireless CarPlay Source **or** native AAP — both set this bit).
 *
 * Binder / method names still say "CarPlay" for AIDL stability
 * ([io.github.nitsuya.aa.display.xposed.ICoreManager.reportCarPlaySessionLive]);
 * system_server maps that to [DisplaySessionPolicy.onSecondaryEgressLiveChanged].
 *
 * Video is exclusive: Host-style GL (CarPlay Host or [io.github.nitsuya.aa.display.aap.AapDisplayBridge])
 * owns the encoder while this bit is true.
 */
object ProjectionSessionGate {
    private val secondaryEgressLive = AtomicBoolean(false)

    /** Secondary egress (CarPlay ∪ AAP) live in the app process. */
    fun setCarPlayLive(live: Boolean) {
        secondaryEgressLive.set(live)
    }

    fun isCarPlayLive(): Boolean = secondaryEgressLive.get()

    /** True when secondary egress is live — Host / AAP GL owns touch + encoder path. */
    fun shouldUseCarPlayHost(): Boolean = secondaryEgressLive.get()
}
