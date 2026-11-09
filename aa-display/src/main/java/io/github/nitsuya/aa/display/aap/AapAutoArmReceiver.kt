package io.github.nitsuya.aa.display.aap

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.nitsuya.aa.display.carplay.CarPlayChannel
import io.github.nitsuya.aa.display.egress.ChannelOrchestrator

/** Re-arm [AapProjectionService] after boot / unlock / BT ON when channel is AAP. */
class AapAutoArmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state != BluetoothAdapter.STATE_ON) return
        }
        if (!CarPlayChannel.useAapEgress(context)) return
        val app = context.applicationContext
        val pending = goAsync()
        mainHandler.post {
            try {
                if (ChannelOrchestrator.isSwitchInProgress()) {
                    Log.i(AapConsts.TAG, "AapAutoArm skip action=$action — channel switch")
                    return@post
                }
                ChannelOrchestrator.ensureArmedCurrent(app)
                Log.i(AapConsts.TAG, "AapAutoArm action=$action → ensureArmed")
            } catch (e: Throwable) {
                Log.e(AapConsts.TAG, "AapAutoArm failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
    }
}
