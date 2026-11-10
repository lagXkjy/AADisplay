package io.github.nitsuya.aa.display.egress

import android.content.Context
import io.github.nitsuya.aa.display.aap.AapProjectionService
import io.github.nitsuya.aa.display.carplay.CarPlayProjectionService

/**
 * App-side backend contract for one video-egress channel (protocol stack + its FGS).
 *
 * The neutral egress layer ([EgressChannel], [ChannelOrchestrator],
 * [ProjectionSessionGate]) talks only to this interface — never to protocol
 * packages. Adding a new egress protocol = new protocol package whose FGS
 * companion implements [ProjectionEgress] + one entry in
 * [ProjectionEgressRegistry] (+ an [EgressChannel] channel id and a
 * [ProjectionSessionGate] owner key).
 *
 * Video is exclusive: [ChannelOrchestrator] settles every other backend
 * ([stopAndAwait]) before arming the selected one, so the two FGS never tear
 * down / create the shared split VDs, live bit, or audio capture concurrently.
 */
interface ProjectionEgress {
    /** [EgressChannel] id this backend paints for. */
    val channelId: Int

    /** FGS is up and the protocol session is armed. */
    fun isArmed(): Boolean

    /**
     * Arm the channel FGS (idempotent). Include channel-specific pre-arm steps
     * (AAP: cert extract → FGS → USB probe; CarPlay: no host trampoline).
     */
    fun arm(context: Context)

    /**
     * Stop the channel and block until it is settled; no-op when already
     * settled. Must be called on a background thread (sleeps while polling).
     */
    fun stopAndAwait(context: Context, timeoutMs: Long, pollMs: Long)

    /**
     * Release channel-specific visible windows (e.g. the CarPlay host
     * trampoline activity, posted to the main looper). Default: no-op.
     */
    fun finishHost(context: Context) {}
}

/**
 * Registered egress backends (wireless CarPlay Source, native AAP).
 * [ChannelOrchestrator] iterates this list — it imports no protocol package.
 */
object ProjectionEgressRegistry {
    val all: List<ProjectionEgress> = listOf(
        CarPlayProjectionService,
        AapProjectionService,
    )

    fun byChannel(channel: Int): ProjectionEgress? = all.firstOrNull { it.channelId == channel }
}
