package io.github.nitsuya.aa.display.egress

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot hints across [ChannelOrchestrator] → protocol FGS arm without widening
 * [ProjectionEgress] (orchestrator must not import carplay/aap).
 *
 * [markPostAapCarPlayArm] is set when a switch settles a live AAP session and arms
 * CarPlay; [CarPlayProjectionService] consumes it on CONNECT so BT can force outbound
 * dial (Audi will not dial …cafe after wired AA).
 */
object EgressMigrationHints {
    private val postAapCarPlayArm = AtomicBoolean(false)
    private val postAapCarPlayToast = AtomicBoolean(false)

    fun markPostAapCarPlayArm() {
        postAapCarPlayArm.set(true)
        postAapCarPlayToast.set(true)
    }

    /** BT force-outbound path — returns true once and clears. */
    fun consumePostAapCarPlayArm(): Boolean = postAapCarPlayArm.getAndSet(false)

    /** Status-page toast after switch — returns true once and clears. */
    fun consumePostAapCarPlayToast(): Boolean = postAapCarPlayToast.getAndSet(false)
}
