package io.github.nitsuya.aa.display.egress

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.nitsuya.aa.display.aap.AapConsts
import io.github.nitsuya.aa.display.aap.AapProjectionService
import io.github.nitsuya.aa.display.aap.cert.PhoneCertStore
import io.github.nitsuya.aa.display.aap.usb.AapUsbProbe
import io.github.nitsuya.aa.display.carplay.CarPlayChannel
import io.github.nitsuya.aa.display.carplay.CarPlayConsts
import io.github.nitsuya.aa.display.carplay.CarPlayHostActivity
import io.github.nitsuya.aa.display.carplay.CarPlayHostRuntime
import io.github.nitsuya.aa.display.carplay.CarPlayProjectionService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serializes CarPlay ↔ AAP channel switches in the app process:
 * persist → stop other FGS → await teardown → start target.
 *
 * UI / AutoArm / status-page re-arm must go through here so the two FGSs never
 * tear down and create shared split VDs / live-bit / audio capture concurrently.
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

    fun isSwitchInProgress(): Boolean = switchInProgress

    /**
     * Persist [channel] immediately, then on the orchestrator thread:
     * stop the other egress (await), start the selected one.
     * [onComplete] runs on the main looper with the channel that was requested.
     */
    fun switchTo(
        context: Context,
        channel: Int,
        onComplete: (() -> Unit)? = null,
    ) {
        val app = context.applicationContext
        val target = normalize(app, channel)
        CarPlayChannel.set(app, target)
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
     * FGS is up and the other is fully down. No-ops while a [switchTo] is running.
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
                when {
                    CarPlayChannel.useAapEgress(app) -> {
                        stopCarPlayAwait(app)
                        if (!AapProjectionService.isSessionArmed()) {
                            PhoneCertStore.ensureExtracted(app, force = false)
                            AapProjectionService.start(app)
                            AapUsbProbe.tryAttachExisting(app)
                            Log.i(TAG, "ensureArmedCurrent → AAP FGS")
                        }
                    }
                    CarPlayChannel.useCarPlayEgress(app) -> {
                        stopAapAwait(app)
                        if (!CarPlayProjectionService.isSessionArmed()) {
                            CarPlayProjectionService.start(app, launchHostTrampoline = false)
                            Log.i(TAG, "ensureArmedCurrent → CarPlay FGS")
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "ensureArmedCurrent failed", e)
            }
        }
    }

    private fun performSwitch(app: Context, channel: Int, gen: Int) {
        when (channel) {
            CarPlayChannel.CHANNEL_AAP -> {
                stopCarPlayAwait(app)
                if (gen != generation.get()) return
                PhoneCertStore.ensureExtracted(app, force = false)
                if (gen != generation.get()) return
                AapProjectionService.start(app)
                AapUsbProbe.tryAttachExisting(app)
                Log.i(AapConsts.TAG, "ChannelOrchestrator armed AAP")
            }
            CarPlayChannel.CHANNEL_CARPLAY,
            CarPlayChannel.CHANNEL_CARPLAY_USB,
            -> {
                stopAapAwait(app)
                if (gen != generation.get()) return
                CarPlayProjectionService.start(app, launchHostTrampoline = false)
                Log.i(CarPlayConsts.TAG, "ChannelOrchestrator armed CarPlay")
            }
            else -> {
                val fallback = CarPlayChannel.defaultChannel(app)
                CarPlayChannel.set(app, fallback)
                if (gen == generation.get()) {
                    performSwitch(app, fallback, gen)
                }
            }
        }
    }

    private fun stopCarPlayAwait(app: Context) {
        if (!CarPlayProjectionService.isSessionArmed() && !CarPlayHostRuntime.isRunning()) {
            return
        }
        Log.i(TAG, "stop CarPlay (await)")
        mainHandler.post { CarPlayHostActivity.current?.finish() }
        CarPlayProjectionService.stop(app)
        val ok = awaitUntil(STOP_AWAIT_MS) {
            !CarPlayProjectionService.isSessionArmed() && !CarPlayHostRuntime.isRunning()
        }
        if (!ok) {
            Log.w(TAG, "CarPlay stop await timed out — proceeding")
        }
    }

    private fun stopAapAwait(app: Context) {
        if (!AapProjectionService.isSessionArmed()) return
        Log.i(TAG, "stop AAP (await)")
        AapProjectionService.stop(app)
        val ok = awaitUntil(STOP_AWAIT_MS) { !AapProjectionService.isSessionArmed() }
        if (!ok) {
            Log.w(TAG, "AAP stop await timed out — proceeding")
        }
    }

    private fun awaitUntil(timeoutMs: Long, pred: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!pred()) {
            if (SystemClock.elapsedRealtime() >= deadline) return pred()
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return pred()
            }
        }
        return true
    }

    private fun normalize(app: Context, channel: Int): Int {
        return when (channel) {
            CarPlayChannel.CHANNEL_AAP -> CarPlayChannel.CHANNEL_AAP
            CarPlayChannel.CHANNEL_CARPLAY -> CarPlayChannel.CHANNEL_CARPLAY
            CarPlayChannel.CHANNEL_CARPLAY_USB ->
                if (CarPlayChannel.USB_CARPLAY_ENABLED) CarPlayChannel.CHANNEL_CARPLAY_USB
                else CarPlayChannel.defaultChannel(app)
            else -> CarPlayChannel.defaultChannel(app)
        }
    }
}
