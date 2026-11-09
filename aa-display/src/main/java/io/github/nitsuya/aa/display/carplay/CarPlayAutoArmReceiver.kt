package io.github.nitsuya.aa.display.carplay

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.nitsuya.aa.display.egress.ChannelOrchestrator
import java.util.concurrent.atomic.AtomicLong

/**
 * Re-arms [CarPlayProjectionService] after reboot / package replace / BT on / unlock when
 * the user last chose the CarPlay channel. DiPlay dials phone UUID `…cafe` on reconnect;
 * without a live RFCOMM listen the SDP lookup fails (`scn=0`) and the accessory never
 * reaches iAP2 — classic BT audio may already work, so the user hears sound long before
 * the CarPlay display comes up.
 *
 * Some OEMs enable Bluetooth and let the HU take A2DP **before**
 * [Intent.ACTION_BOOT_COMPLETED]. Manifest [BluetoothAdapter.ACTION_STATE_CHANGED]
 * is blocked while the app is stopped (`Background execution not allowed`); early
 * arm is done in system_server ([io.github.nitsuya.aa.display.xposed.carplay.CarPlayBootArm]).
 * This receiver remains the BOOT_COMPLETED / package-replace / unlock fallback ladder.
 *
 * Boot order inside FGS: [CarPlayBluetoothLink] still waits for STATE_ON if the adapter
 * flickers.
 */
class CarPlayAutoArmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!isArmAction(action)) return
        if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
            val state = intent.getIntExtra(
                BluetoothAdapter.EXTRA_STATE,
                BluetoothAdapter.ERROR,
            )
            if (state != BluetoothAdapter.STATE_ON) return
        }
        if (!CarPlayChannel.useCarPlayEgress(context)) {
            Log.i(CarPlayConsts.TAG, "CarPlayAutoArm skip action=$action channel=aa")
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        val delays = retryDelaysMs(action)
        fun finishIfLast(index: Int) {
            if (index >= delays.lastIndex) pending.finish()
        }
        for ((i, delayMs) in delays.withIndex()) {
            val reason = if (delayMs == 0L) "immediate" else "delayed-${delayMs}ms"
            // Ladder steps must not share BT debounce — each kick is intentional.
            val debounce = action == BluetoothAdapter.ACTION_STATE_CHANGED
            if (delayMs == 0L) {
                arm(app, action, reason, debounce)
                finishIfLast(i)
            } else {
                mainHandler.postDelayed({
                    try {
                        if (CarPlayChannel.useCarPlayEgress(app)) {
                            arm(app, action, reason, debounce = false)
                        }
                    } finally {
                        finishIfLast(i)
                    }
                }, delayMs)
            }
        }
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val lastArmElapsed = AtomicLong(0L)

        /** Collapse BT flicker / multi-broadcast storms into one FGS start. */
        private const val ARM_DEBOUNCE_MS = 2_000L

        private fun isArmAction(action: String): Boolean =
            action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                action == Intent.ACTION_USER_UNLOCKED ||
                action == BluetoothAdapter.ACTION_STATE_CHANGED

        /**
         * BT STATE_ON is the critical moment DiPlay can SDP — arm once.
         * Boot / unlock / replace get a short ladder so late system services still get cafe.
         */
        private fun retryDelaysMs(action: String): LongArray =
            when (action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> longArrayOf(0L)
                else -> longArrayOf(0L, 3_000L, 12_000L, 30_000L)
            }

        private fun arm(app: Context, action: String, reason: String, debounce: Boolean) {
            // Boot ladder is 0/3/12/30s — once FGS has a session (cafe listening),
            // further kicks only recycle the encoder and look like connect failure.
            if (CarPlayProjectionService.isSessionArmed()) {
                Log.d(
                    CarPlayConsts.TAG,
                    "CarPlayAutoArm skip action=$action reason=$reason — session armed",
                )
                return
            }
            if (ChannelOrchestrator.isSwitchInProgress()) {
                Log.i(
                    CarPlayConsts.TAG,
                    "CarPlayAutoArm skip action=$action reason=$reason — channel switch",
                )
                return
            }
            if (debounce) {
                val now = SystemClock.elapsedRealtime()
                val prev = lastArmElapsed.get()
                if (now - prev < ARM_DEBOUNCE_MS && prev != 0L) {
                    Log.d(CarPlayConsts.TAG, "CarPlayAutoArm debounce action=$action reason=$reason")
                    return
                }
                lastArmElapsed.set(now)
            }
            Log.i(CarPlayConsts.TAG, "CarPlayAutoArm action=$action reason=$reason → ensureArmed")
            try {
                // Serializes with channel switch; also stops a stray AAP FGS if any.
                ChannelOrchestrator.ensureArmedCurrent(app)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "CarPlayAutoArm start FGS failed ($reason)", e)
                if (debounce) lastArmElapsed.set(0L)
            }
        }
    }
}
