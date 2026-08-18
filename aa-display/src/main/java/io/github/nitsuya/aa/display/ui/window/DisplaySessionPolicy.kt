package io.github.nitsuya.aa.display.ui.window

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.ui.aa.split.SplitDisplayController
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook
import io.github.nitsuya.aa.display.xposed.hook.VdDensityPin
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.aa.display.xposed.util.RomUtil
import io.github.nitsuya.aa.display.xposed.util.log
import java.lang.reflect.Method
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * AA display session policy: Delay Destroy (180s) and keep-awake for OWN_DISPLAY_GROUP VDs.
 * Phone overlay UI was removed; this class no longer hosts any WindowManager views.
 *
 * **Hard rule: AA virtual panes must not stay black (ColorFade / OFF).** Prefer a rare
 * phone-panel wake over blank car screens.
 *
 * Keep-awake strategy (Samsung-first):
 * - Hold display-scoped [SCREEN_BRIGHT_WAKE_LOCK] on each AA VD for the whole session,
 *   including Delay Destroy (release only when VDs are actually torn down).
 * - Drive [IPowerManager.userActivity] only with the displayId overload.
 * - While the phone is off, or a pane is OFF/DOZE, or on resume / soft-reconnect /
 *   SCREEN_OFF: short display-scoped [ACQUIRE_CAUSES_WAKEUP] pulse (Monitor alone
 *   cannot exit ColorFade).
 */
class DisplaySessionPolicy(
    private val mContext: Context,
    private val displayAdapter: SplitDisplayController,
) {
    companion object {
        private const val TAG = "AADisplay_DisplaySessionPolicy"
        /** Keep OWN_DISPLAY_GROUP user-activity from timing out / dozing on Samsung. */
        private const val KEEP_AWAKE_INTERVAL_MS = 15_000L
        /** While phone is off — beat ColorFade before DreamManager settles. */
        private const val KEEP_AWAKE_INTERVAL_SCREEN_OFF_MS = 3_000L
        private const val TOUCH_KEEP_AWAKE_MIN_INTERVAL_MS = 1_000L
        /** After phone SCREEN_OFF, reassert before the next heartbeat. */
        private const val SCREEN_OFF_REASSERT_COUNT = 10
        private const val SCREEN_OFF_REASSERT_INTERVAL_MS = 400L
        /** PowerManager.USER_ACTIVITY_EVENT_TOUCH */
        private const val USER_ACTIVITY_EVENT_TOUCH = 2
        /** PowerManager.USER_ACTIVITY_EVENT_OTHER */
        private const val USER_ACTIVITY_EVENT_OTHER = 0
        /**
         * Display-scoped VD bright locks still need this legacy level;
         * [PowerManager.SCREEN_BRIGHT_WAKE_LOCK] is deprecated but required for hidden API.
         */
        private const val SCREEN_BRIGHT_WAKE_LOCK = 0x0000000a
        /**
         * Short pulse: exits Samsung ColorFade on OWN_DISPLAY_GROUP after OFF.
         * Display-scoped newWakeLock(displayId); do not hold long-term.
         */
        private const val ACQUIRE_CAUSES_WAKEUP = 0x10000000
        private const val WAKE_PULSE_MS = 3_000L
        /** Seconds to keep dual VD after AA disconnect before destroy. */
        private const val DELAY_DESTROY_SEC = 180
    }

    private var mDestroyJob: Job? = null

    private val isSupportInteractive = RomUtil.isMiui()
    private var mKeepAwakeJob: Job? = null
    private var mScreenOffReassertJob: Job? = null
    private var mLastTouchKeepAwakeAt = 0L
    private var mScreenReceiverRegistered = false
    private var iPowerManagerService: Any? = null
    private var iPowerManagerUserActivity: Method? = null
    private var mLoggedMissingDisplayUserActivity = false

    /**
     * Both split VDs use OWN_DISPLAY_GROUP, so each has an independent power group.
     * Keeping only the primary awake leaves the secondary black while audio continues.
     */
    private fun aaVirtualDisplayIds(): IntArray {
        val primary = displayAdapter.primaryDisplayId
        val secondary = displayAdapter.secondaryDisplayId
        return when {
            primary == Display.INVALID_DISPLAY && secondary == Display.INVALID_DISPLAY -> intArrayOf()
            secondary == Display.INVALID_DISPLAY -> intArrayOf(primary)
            primary == Display.INVALID_DISPLAY -> intArrayOf(secondary)
            else -> intArrayOf(primary, secondary)
        }
    }

    private fun isVirtualDisplayPoweredOff(displayId: Int): Boolean {
        return try {
            val state = Instances.displayManager.getDisplay(displayId)?.state ?: return false
            state == Display.STATE_OFF || state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND
        } catch (_: Throwable) {
            false
        }
    }

    private var interactiveMonitor = object : BroadcastReceiver() {
        private val monitorLocks = mutableMapOf<Int, PowerManager.WakeLock>()
        private val wakePulseLocks = mutableMapOf<Int, PowerManager.WakeLock>()

        fun addAction(intentFilter: IntentFilter): IntentFilter {
            return intentFilter.apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        }

        override fun onReceive(context: Context, intent: Intent) {
            intent.action?.let { action ->
                onReceive(context, action)
            }
        }

        fun onReceive(context: Context, action: String) {
            if (isSupportInteractive) {
                try {
                    when (action) {
                        Intent.ACTION_SCREEN_ON -> Settings.Secure.putInt(context.contentResolver, "synergy_mode", 0)
                        Intent.ACTION_SCREEN_OFF -> Settings.Secure.putInt(context.contentResolver, "synergy_mode", 1)
                    }
                } catch (_: Throwable) {
                }
            }
            when (action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Critical Samsung path: DreamManager may doze OWN_DISPLAY_GROUP with the phone.
                    keepVirtualDisplayAwake("phone-SCREEN_OFF", forceWake = true)
                    startScreenOffReassertBurst()
                }
                Intent.ACTION_SCREEN_ON -> {
                    cancelScreenOffReassertBurst()
                    keepVirtualDisplayAwake("phone-SCREEN_ON", forceWake = false)
                }
                else -> keepVirtualDisplayAwake("phone-$action", forceWake = true)
            }
        }

        private fun newDisplayWakeLock(levelAndFlags: Int, tagSuffix: String, displayId: Int): PowerManager.WakeLock {
            return Instances.powerManagerHidden.newWakeLock(
                levelAndFlags,
                "${BuildConfig.APPLICATION_ID}:$tagSuffix:$displayId",
                displayId
            ).apply { setReferenceCounted(false) }
        }

        private fun releaseLockMap(locks: MutableMap<Int, PowerManager.WakeLock>, label: String) {
            locks.keys.toList().forEach { displayId ->
                try {
                    locks.remove(displayId)?.let { lock ->
                        if (lock.isHeld) lock.release()
                    }
                } catch (e: Throwable) {
                    log(TAG, "VD $label release failed display=$displayId:", e)
                }
            }
        }

        private fun pruneStaleLocks(activeIds: Set<Int>) {
            (monitorLocks.keys - activeIds).forEach { displayId ->
                try {
                    monitorLocks.remove(displayId)?.let { if (it.isHeld) it.release() }
                } catch (_: Throwable) {
                }
            }
            (wakePulseLocks.keys - activeIds).forEach { displayId ->
                try {
                    wakePulseLocks.remove(displayId)?.let { if (it.isHeld) it.release() }
                } catch (_: Throwable) {
                }
            }
        }

        fun acquireMonitor() {
            val ids = aaVirtualDisplayIds()
            if (ids.isEmpty()) return
            val active = ids.toSet()
            pruneStaleLocks(active)
            for (displayId in ids) {
                try {
                    val lock = monitorLocks.getOrPut(displayId) {
                        newDisplayWakeLock(
                            SCREEN_BRIGHT_WAKE_LOCK,
                            "Monitor",
                            displayId
                        )
                    }
                    if (!lock.isHeld) {
                        lock.acquire()
                    }
                } catch (e: Throwable) {
                    log(TAG, "VD Monitor acquire failed display=$displayId:", e)
                }
            }
        }

        /**
         * Exit ColorFade / OFF on a single OWN_DISPLAY_GROUP VD.
         * Timed acquire only — long-held WAKEUP can leak to the phone panel on some OEMs.
         */
        fun pulseWake(displayId: Int) {
            try {
                val lock = wakePulseLocks.getOrPut(displayId) {
                    newDisplayWakeLock(
                        SCREEN_BRIGHT_WAKE_LOCK or ACQUIRE_CAUSES_WAKEUP,
                        "VdWake",
                        displayId
                    )
                }
                if (!lock.isHeld) {
                    lock.acquire(WAKE_PULSE_MS)
                }
            } catch (e: Throwable) {
                log(TAG, "VD wakePulse failed display=$displayId:", e)
            }
        }

        fun releaseMonitor() {
            releaseLockMap(monitorLocks, "Monitor")
            releaseLockMap(wakePulseLocks, "VdWake")
        }

        fun init() {
            // Always watch phone screen transitions so OWN_DISPLAY_GROUP is re-asserted
            // when Samsung DreamManager tries to DOZE the AA virtual display with the phone.
            if (!mScreenReceiverRegistered) {
                try {
                    mContext.registerReceiver(
                        this,
                        addAction(IntentFilter()),
                        Context.RECEIVER_NOT_EXPORTED
                    )
                    mScreenReceiverRegistered = true
                } catch (e: Throwable) {
                    log(TAG, "register SCREEN_ON/OFF failed:", e)
                }
            }
            if (isSupportInteractive) {
                onReceive(mContext, if (Instances.powerManager.isInteractive) Intent.ACTION_SCREEN_ON else Intent.ACTION_SCREEN_OFF)
            }
            // Hold a display-scoped SCREEN_BRIGHT lock for each AA VD group so
            // Samsung OWN_DISPLAY_GROUP does not DOZE the car virtual displays.
            acquireMonitor()
            startKeepAwakeLoop()
            keepVirtualDisplayAwake("init", forceWake = true)
            // ensureHooked: do not reinstall/clear map on AA reconnect (onResume → init).
            if (AndroidHook.isReadyForSystemHooks()) {
                VdDensityPin.ensureHooked()
            }
        }

        fun release() {
            stopKeepAwakeLoop()
            cancelScreenOffReassertBurst()
            if (mScreenReceiverRegistered) {
                try {
                    mContext.unregisterReceiver(this)
                } catch (_: Throwable) {
                }
                mScreenReceiverRegistered = false
            }
            if (isSupportInteractive) {
                try {
                    Settings.Secure.putInt(mContext.contentResolver, "synergy_mode", 0)
                } catch (_: Throwable) {
                }
            }
            releaseMonitor()
            VdDensityPin.unHook()
        }
    }

    private fun keepAwakeIntervalMs(): Long {
        return if (isPhoneInteractive()) {
            KEEP_AWAKE_INTERVAL_MS
        } else {
            KEEP_AWAKE_INTERVAL_SCREEN_OFF_MS
        }
    }

    private fun startKeepAwakeLoop() {
        if (mKeepAwakeJob?.isActive == true) return
        mKeepAwakeJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(keepAwakeIntervalMs())
                val phoneOff = !isPhoneInteractive()
                val anyVdOff = aaVirtualDisplayIds().any { isVirtualDisplayPoweredOff(it) }
                // phoneOff → pulse inside keepVirtualDisplayAwake (never-black hard rule).
                keepVirtualDisplayAwake("heartbeat", forceWake = phoneOff || anyVdOff)
            }
        }
    }

    private fun stopKeepAwakeLoop() {
        mKeepAwakeJob?.cancel()
        mKeepAwakeJob = null
    }

    private fun startScreenOffReassertBurst() {
        cancelScreenOffReassertBurst()
        mScreenOffReassertJob = CoroutineScope(Dispatchers.Default).launch {
            // First assert already ran from onReceive; reinforce while DreamManager settles.
            repeat(SCREEN_OFF_REASSERT_COUNT) {
                delay(SCREEN_OFF_REASSERT_INTERVAL_MS)
                if (!isActive) return@launch
                keepVirtualDisplayAwake("phone-SCREEN_OFF-burst", forceWake = true)
            }
        }
    }

    private fun cancelScreenOffReassertBurst() {
        mScreenOffReassertJob?.cancel()
        mScreenOffReassertJob = null
    }

    private fun isPhoneInteractive(): Boolean {
        return try {
            Instances.powerManager.isInteractive
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Keep / restore power for both AA virtual display groups.
     * Hard rule: panes must not stay ColorFade/OFF — pulse whenever the phone is off,
     * a pane is OFF/DOZE, or on resume / soft-reconnect / SCREEN_OFF / init.
     */
    fun keepVirtualDisplayAwake(reason: String, forceWake: Boolean = false) {
        val displayIds = aaVirtualDisplayIds()
        if (displayIds.isEmpty()) return
        try {
            interactiveMonitor.acquireMonitor()
            val phoneOff = !isPhoneInteractive()
            val event = if (forceWake || phoneOff) {
                USER_ACTIVITY_EVENT_TOUCH
            } else {
                USER_ACTIVITY_EVENT_OTHER
            }
            val sessionPulse = reason == "resume" ||
                reason == "soft-reconnect" ||
                reason == "init" ||
                reason.startsWith("phone-SCREEN_OFF")
            for (displayId in displayIds) {
                // VD never-black: pulse while phone is off even if getState() still says ON
                // (Samsung can ColorFade before state flips).
                if (sessionPulse || phoneOff || isVirtualDisplayPoweredOff(displayId)) {
                    interactiveMonitor.pulseWake(displayId)
                }
                userActivityOnDisplay(displayId, event)
            }
        } catch (e: Throwable) {
            log(TAG, "keepVirtualDisplayAwake[$reason] failed:", e)
        }
    }

    /** Throttled keep-awake for AA touch / key forwarding. */
    fun onVirtualDisplayUserInteraction() {
        val now = SystemClock.uptimeMillis()
        if (now - mLastTouchKeepAwakeAt < TOUCH_KEEP_AWAKE_MIN_INTERVAL_MS) return
        mLastTouchKeepAwakeAt = now
        // If phone is already off, pulse so a dozing pane recovers on first touch.
        keepVirtualDisplayAwake("interaction", forceWake = true)
    }

    /**
     * IPowerManager.userActivity(displayId, …) — PowerManager only forwards the
     * context display id, which is useless for OWN_DISPLAY_GROUP virtual displays.
     * Only the displayId overload is used; global overloads would wake the phone panel.
     */
    private fun userActivityOnDisplay(displayId: Int, event: Int) {
        try {
            val service = iPowerManagerService
                ?: PowerManager::class.java.getDeclaredField("mService").apply {
                    isAccessible = true
                }.get(Instances.powerManager)?.also { iPowerManagerService = it }
                ?: return
            val method = iPowerManagerUserActivity ?: resolveDisplayUserActivityMethod(service.javaClass)
                ?.also { iPowerManagerUserActivity = it }
            if (method == null) {
                if (!mLoggedMissingDisplayUserActivity) {
                    mLoggedMissingDisplayUserActivity = true
                    log(TAG, "IPowerManager.userActivity(displayId,…) unavailable; skip to avoid waking phone")
                }
                return
            }
            method.invoke(service, displayId, SystemClock.uptimeMillis(), event, 0)
        } catch (e: Throwable) {
            log(TAG, "IPowerManager.userActivity(display=$displayId) failed:", e)
        }
    }

    private fun resolveDisplayUserActivityMethod(serviceClass: Class<*>): Method? {
        return serviceClass.methods.firstOrNull { m ->
            if (m.name != "userActivity") return@firstOrNull false
            val p = m.parameterTypes
            p.size == 4 &&
                p[0] == Int::class.javaPrimitiveType &&
                p[1] == Long::class.javaPrimitiveType &&
                p[2] == Int::class.javaPrimitiveType &&
                p[3] == Int::class.javaPrimitiveType
        }
    }

    /** Wake the phone panel via display-scoped userActivity on DEFAULT_DISPLAY. */
    private fun pulsePhoneWake() {
        try {
            userActivityOnDisplay(Display.DEFAULT_DISPLAY, USER_ACTIVITY_EVENT_TOUCH)
        } catch (e: Throwable) {
            log(TAG, "pulsePhoneWake failed:", e)
        }
    }

    init {
        interactiveMonitor.init()
    }

    suspend fun onResume() {
        // Cancel Delay Destroy first so completion cannot race-release keep-awake.
        mDestroyJob?.cancelAndJoin()
        interactiveMonitor.init()
        // Soft reconnect often inherits panes already ColorFade'd while AA was gone.
        keepVirtualDisplayAwake("resume", forceWake = true)
    }

    suspend fun onDestroyPromptly() {
        restorePhoneDisplayPower()
        interactiveMonitor.release()
        mDestroyJob?.cancelAndJoin()
    }

    suspend fun onDestroy(onDestroySucceed: () -> Unit) {
        // Keep Monitor + heartbeat through Delay Destroy so soft reconnect does not
        // inherit Samsung ColorFade-black panes. Release only when VDs are torn down.
        mDestroyJob?.cancelAndJoin()
        startDelayDestroy(onDestroySucceed)
    }

    /** Headless Delay Destroy so [onDestroySucceed] fires and VDs are released. */
    private fun startDelayDestroy(onDestroySucceed: () -> Unit) {
        mDestroyJob = CoroutineScope(Dispatchers.Main).launch {
            delay(DELAY_DESTROY_SEC * 1000L)
            restorePhoneDisplayPower()
            interactiveMonitor.release()
            onDestroySucceed()
        }
    }

    /** Best-effort wake of the phone panel before tearing down session power. */
    private fun restorePhoneDisplayPower() {
        try {
            pulsePhoneWake()
        } catch (e: Throwable) {
            log(TAG, "restorePhoneDisplayPower failed:", e)
        }
    }
}
