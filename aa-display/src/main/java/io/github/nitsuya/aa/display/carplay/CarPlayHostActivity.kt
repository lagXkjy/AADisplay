package io.github.nitsuya.aa.display.carplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional visible trampoline for [CarPlayHostRuntime].
 *
 * GL/VD live in [CarPlayHostRuntime] (FGS-owned). This Activity only helps BAL and keeps a
 * brief visible window; finishing it must **not** tear down CarPlay video.
 *
 * Own [android:taskAffinity] so [moveTaskToBack] after the BAL window does not send the
 * status-page ([MainActivity][io.github.nitsuya.aa.display.ui.main.MainActivity]) task away.
 */
class CarPlayHostActivity : Activity() {
    companion object {
        @Volatile
        var current: CarPlayHostActivity? = null

        private val launchGate = AtomicBoolean(false)
        private val mainHandler = Handler(Looper.getMainLooper())

        /** Best-effort Activity launch; always also arms [CarPlayHostRuntime]. */
        fun launch(context: Context) {
            CarPlayHostRuntime.ensureStarted(context)
            if (current != null) return
            if (!launchGate.compareAndSet(false, true)) return
            mainHandler.postDelayed({
                if (current == null) launchGate.set(false)
            }, 3_000L)
            try {
                context.startActivity(
                    Intent(context, CarPlayHostActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Throwable) {
                launchGate.set(false)
                Log.w(CarPlayConsts.TAG, "CarPlayHost launch deferred: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (current != null) {
            Log.w(CarPlayConsts.TAG, "CarPlayHostActivity duplicate ignored (runtime owns GL)")
            launchGate.set(false)
            finish()
            return
        }
        // Channel gate: a system-recreated trampoline must not re-arm CarPlay (FGS + host GL)
        // while the user is on AAP — the re-arm rebinds the shared split VDs and leaves AAP
        // with audio up / video black.
        if (!CarPlayChannel.useCarPlayEgress(applicationContext)) {
            Log.i(CarPlayConsts.TAG, "CarPlayHostActivity on non-CarPlay channel — skip re-arm")
            launchGate.set(false)
            finish()
            return
        }
        current = this
        launchGate.set(false)
        // Sync boot so SETUP in the same tick sees a live compositor.
        CarPlayHostRuntime.ensureStartedSync(this, timeoutMs = 5_000L)
        CarPlayProjectionService.start(this)
        Log.i(
            CarPlayConsts.TAG,
            "CarPlayHostActivity trampoline up runtime=${CarPlayHostRuntime.isRunning()}",
        )
        // Stay in the task briefly for BAL, then hide — do not destroy Runtime.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && current === this) {
                moveTaskToBack(true)
            }
        }, 800L)
    }

    override fun onDestroy() {
        if (current === this) {
            current = null
            launchGate.set(false)
        }
        // Runtime teardown is owned by FGS / channel switch.
        super.onDestroy()
    }
}
