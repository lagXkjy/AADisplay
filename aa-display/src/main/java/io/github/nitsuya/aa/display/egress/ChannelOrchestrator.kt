package io.github.nitsuya.aa.display.egress

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serializes egress channel switches in the app process, backend-agnostic:
 * persist → settle all other [ProjectionEgress] backends (await) → arm target.
 *
 * UI / AutoArm / status-page re-arm must go through here so the FGSs never
 * tear down and create shared split VDs / live-bit / audio capture
 * concurrently.
 *
 * Backends register via [ProjectionEgressRegistry]; this class imports no
 * protocol package (carplay / aap / future protocols).
 */
object ChannelOrchestrator {
    private const val TAG = "AADisplay_ChannelOrch"
    private const val STOP_AWAIT_MS = 4_000L
    private const val POLL_MS = 40L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aad-channel-orch").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)

    @Volatile
    private var switchInProgress = false

    /**
     * Persist [channel] immediately, then on the orchestrator thread:
     * settle the other backends (await), arm the selected one.
     * [onComplete] runs on the main looper with the channel that was requested.
     */
    fun switchTo(
        context: Context,
        channel: Int,
        onComplete: (() -> Unit)? = null,
    ) {
        val app = context.applicationContext
        val target = normalize(app, channel)
        EgressChannel.set(app, target)
        val gen = generation.incrementAndGet()
        switchInProgress = true
        Log.i(TAG, "switchTo channel=$target gen=$gen")
        executor.execute {
            try {
                if (gen != generation.get()) {
                    Log.i(TAG, "switchTo aborted superseded gen=$gen")
                    return@execute
                }
                performSwitch(app, target, gen)
            } catch (e: Throwable) {
                Log.e(TAG, "switchTo failed channel=$target", e)
            } finally {
                if (gen == generation.get()) {
                    switchInProgress = false
                }
                mainHandler.post { onComplete?.invoke() }
            }
        }
    }

    /**
     * Soft re-arm for AutoArm / status-page open: ensure the persisted channel's
     * FGS is up and every other backend is fully down. No-ops while a [switchTo]
     * is running.
     */
    fun ensureArmedCurrent(context: Context) {
        if (switchInProgress) {
            Log.i(TAG, "ensureArmedCurrent skip — switch in progress")
            return
        }
        val app = context.applicationContext
        val gen = generation.get()
        executor.execute {
            if (switchInProgress || gen != generation.get()) {
                Log.i(TAG, "ensureArmedCurrent skip — superseded")
                return@execute
            }
            try {
                val channel = EgressChannel.get(app)
                // Re-sync /data/system mirror after reboot / upgrade.
                EgressChannel.mirrorToSystem(channel)
                val current = ProjectionEgressRegistry.byChannel(channel)
                    ?: ProjectionEgressRegistry.byChannel(EgressChannel.defaultChannel(app))
                    ?: return@execute
                settleOthers(app, current)
                if (!current.isArmed()) {
                    current.arm(app)
                    Log.i(TAG, "ensureArmedCurrent → channel=${current.channelId}")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "ensureArmedCurrent failed", e)
            }
        }
    }

    private fun performSwitch(app: Context, channel: Int, gen: Int) {
        val target = ProjectionEgressRegistry.byChannel(channel)
        if (target == null) {
            // Unknown / retired sentinel — migrate to default and retry once.
            val fallback = EgressChannel.defaultChannel(app)
            EgressChannel.set(app, fallback)
            if (gen == generation.get()) {
                performSwitch(app, fallback, gen)
            }
            return
        }
        settleOthers(app, target)
        if (gen != generation.get()) return
        target.arm(app)
        Log.i(TAG, "armed channel=${target.channelId}")
    }

    /** Settle every backend except [target] (single orchestrator thread). */
    private fun settleOthers(app: Context, target: ProjectionEgress) {
        for (other in ProjectionEgressRegistry.all) {
            if (other === target) continue
            other.stopAndAwait(app, STOP_AWAIT_MS, POLL_MS)
        }
    }

    private fun normalize(app: Context, channel: Int): Int =
        if (ProjectionEgressRegistry.byChannel(channel) != null) channel
        else EgressChannel.defaultChannel(app) // includes retired AA / USB sentinels
}
