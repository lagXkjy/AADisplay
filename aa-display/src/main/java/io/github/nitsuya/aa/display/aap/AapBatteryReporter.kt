package io.github.nitsuya.aa.display.aap

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import net.prototux.aare.aap.AapSession
import net.prototux.aare.aap.channels.sendBatteryStatus
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Control-channel battery status (msg 23) for the HU status bar.
 * Independent of [PhoneStatusChannel] (calls) — lightweight tier-1 completeness.
 */
class AapBatteryReporter(
    context: Context,
) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    @Volatile private var session: AapSession? = null
    @Volatile private var lastLevel = -1
    @Volatile private var lastCritical = false

    private val tick = object : Runnable {
        override fun run() {
            if (!active.get()) return
            push(force = false)
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start(session: AapSession) {
        this.session = session
        if (!active.compareAndSet(false, true)) {
            push(force = true)
            return
        }
        push(force = true)
        handler.postDelayed(tick, INTERVAL_MS)
        Log.i(AapConsts.TAG, "AapBatteryReporter started")
    }

    fun stop() {
        if (!active.compareAndSet(true, false)) return
        handler.removeCallbacks(tick)
        session = null
        lastLevel = -1
        Log.i(AapConsts.TAG, "AapBatteryReporter stopped")
    }

    private fun push(force: Boolean) {
        val s = session ?: return
        val (level, critical) = readBattery()
        if (!force && level == lastLevel && critical == lastCritical) return
        lastLevel = level
        lastCritical = critical
        runCatching { s.sendBatteryStatus(level, critical) }
            .onFailure { Log.w(AapConsts.TAG, "sendBatteryStatus failed", it) }
    }

    private fun readBattery(): Pair<Int, Boolean> {
        val sticky = runCatching {
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val pct = if (level >= 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 50
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val critical = !charging && pct <= CRITICAL_PCT
        return pct to critical
    }

    companion object {
        private const val INTERVAL_MS = 60_000L
        private const val CRITICAL_PCT = 15
    }
}
