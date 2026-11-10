package io.github.nitsuya.aa.display.xposed.carplay

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * system_server CFS nice boost (−19 / [Process.THREAD_PRIORITY_URGENT_AUDIO]) for AAP
 * USB critical tids (drain / reader / writer).
 *
 * App threads already set URGENT_AUDIO on themselves; this reasserts from system_server
 * so OEM background demotions cannot quietly drop the drain below UI threads. No
 * SCHED_FIFO — too OEM-fragile / heavy for the gain so far.
 *
 * Best-effort: failures are ignored; the session keeps the app-local −19.
 *
 * Driven by [io.github.nitsuya.aa.display.xposed.ICoreManager.setThreadRealtimeBoost].
 * Cleared with the secondary-egress live bit ([CarPlayProcessBoost] peer).
 */
object ThreadRealtimeBoost {
    private const val TAG = "AAD_ThreadRtBoost"
    private const val WATCHDOG_PERIOD_MS = 3_000L

    private val tids = ConcurrentHashMap.newKeySet<Int>()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val installed = AtomicBoolean(false)
    @Volatile private var loggedOk = false
    @Volatile private var loggedFail = false

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        val t = HandlerThread("aad-thread-rt-boost").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        log(TAG, "installed (URGENT_AUDIO/−19)")
    }

    fun setBoost(tid: Int, enable: Boolean) {
        if (tid <= 0) return
        val h = handler
        if (h == null) {
            if (enable) {
                tids.add(tid)
                apply(tid, true)
            } else {
                tids.remove(tid)
                apply(tid, false)
            }
            return
        }
        h.post {
            if (enable) {
                tids.add(tid)
                apply(tid, true)
                scheduleTick(h)
            } else {
                tids.remove(tid)
                apply(tid, false)
            }
        }
    }

    fun clearAll() {
        val h = handler
        if (h == null) {
            for (tid in tids.toTypedArray()) apply(tid, false)
            tids.clear()
            return
        }
        h.post {
            h.removeCallbacksAndMessages(null)
            for (tid in tids.toTypedArray()) apply(tid, false)
            tids.clear()
        }
    }

    private fun scheduleTick(h: Handler) {
        h.removeCallbacksAndMessages(null)
        if (tids.isEmpty()) return
        h.postDelayed(
            {
                if (tids.isEmpty()) return@postDelayed
                for (tid in tids.toTypedArray()) {
                    if (!File("/proc/$tid").exists()) {
                        tids.remove(tid)
                        continue
                    }
                    apply(tid, true)
                }
                scheduleTick(h)
            },
            WATCHDOG_PERIOD_MS,
        )
    }

    private fun apply(tid: Int, enable: Boolean) {
        if (!File("/proc/$tid").exists()) {
            tids.remove(tid)
            return
        }
        val prio = if (enable) {
            Process.THREAD_PRIORITY_URGENT_AUDIO
        } else {
            Process.THREAD_PRIORITY_DEFAULT
        }
        try {
            Process.setThreadPriority(tid, prio)
            if (enable && !loggedOk) {
                loggedOk = true
                log(TAG, "nice URGENT_AUDIO (−19) ok tid=$tid")
            } else {
                logDebug(TAG, "nice tid=$tid prio=$prio")
            }
        } catch (e: Throwable) {
            if (!loggedFail) {
                loggedFail = true
                log(TAG, "nice failed (app-local −19 remains): ${e.message}")
            }
        }
    }
}
