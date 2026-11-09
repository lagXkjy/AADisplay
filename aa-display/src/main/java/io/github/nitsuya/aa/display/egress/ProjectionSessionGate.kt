package io.github.nitsuya.aa.display.egress

import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-process flag for whether a **secondary egress** session is live
 * (wireless CarPlay Source **or** native AAP).
 *
 * Owners are tracked by key ([OWNER_CARPLAY] / [OWNER_AAP]) so a late
 * teardown `false` from the channel being switched away cannot clobber the
 * channel that already went live. Aggregate live = any owner present.
 *
 * Binder / method names still say "CarPlay" for AIDL stability
 * ([io.github.nitsuya.aa.display.xposed.ICoreManager.reportCarPlaySessionLive]);
 * system_server maps that to [io.github.nitsuya.aa.display.ui.window.DisplaySessionPolicy.onSecondaryEgressLiveChanged].
 *
 * Video is exclusive: Host-style GL (CarPlay Host or
 * [io.github.nitsuya.aa.display.aap.AapDisplayBridge]) owns the encoder while
 * this bit is true — callers must not destroy shared split VDs when
 * [isOwnedByOther] is true for their own key.
 */
object ProjectionSessionGate {
    const val OWNER_CARPLAY = "cp"
    const val OWNER_AAP = "aap"

    private val lock = Any()
    private val owners = linkedSetOf<String>()
    private val secondaryEgressLive = AtomicBoolean(false)

    /**
     * Acquire or release [owner]'s claim on the secondary-egress live bit.
     * @return aggregate live after the update (report this to system_server).
     */
    fun setOwnerLive(owner: String, live: Boolean): Boolean {
        synchronized(lock) {
            if (live) owners.add(owner) else owners.remove(owner)
            val now = owners.isNotEmpty()
            secondaryEgressLive.set(now)
            return now
        }
    }

    /** True when any secondary egress owner is live. */
    fun isCarPlayLive(): Boolean = secondaryEgressLive.get()

    /** True when some owner other than [self] currently holds the live bit. */
    fun isOwnedByOther(self: String): Boolean {
        synchronized(lock) {
            return owners.any { it != self }
        }
    }

    /** True when [owner] currently holds the live bit. */
    fun isOwnedBy(owner: String): Boolean {
        synchronized(lock) {
            return owner in owners
        }
    }

    /**
     * Legacy boolean write used only where the caller is the sole egress.
     * Prefer [setOwnerLive]. Kept so a mistaken single-bit clear cannot be
     * reintroduced without an owner key at new call sites.
     */
    @Deprecated("Use setOwnerLive(owner, live)", ReplaceWith("setOwnerLive(owner, live)"))
    fun setCarPlayLive(live: Boolean) {
        synchronized(lock) {
            if (live) {
                // Ambiguous owner — treat as CarPlay for backward safety.
                owners.add(OWNER_CARPLAY)
            } else {
                owners.clear()
            }
            secondaryEgressLive.set(owners.isNotEmpty())
        }
    }

    /** True when secondary egress is live — Host / AAP GL owns touch + encoder path. */
    fun shouldUseCarPlayHost(): Boolean = secondaryEgressLive.get()
}
