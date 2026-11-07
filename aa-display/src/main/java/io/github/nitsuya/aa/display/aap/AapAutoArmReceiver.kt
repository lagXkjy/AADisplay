package io.github.nitsuya.aa.display.aap

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.nitsuya.aa.display.aap.cert.PhoneCertStore
import io.github.nitsuya.aa.display.carplay.CarPlayChannel

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
                PhoneCertStore.ensureExtracted(app, force = false)
                AapProjectionService.start(app)
                Log.i(AapConsts.TAG, "AapAutoArm action=$action → FGS")
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
