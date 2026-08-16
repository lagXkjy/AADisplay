package io.github.nitsuya.aa.display.ui.window

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import androidx.core.content.ContextCompat
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.ui.aa.split.SplitDisplayController
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.aa.display.xposed.util.RomUtil
import io.github.nitsuya.aa.display.xposed.util.log
import java.lang.reflect.Method
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * AA display session policy: Delay Destroy (180s) and keep-awake for OWN_DISPLAY_GROUP VDs.
 * Phone overlay UI was removed; this class no longer hosts any WindowManager views.
 */
class DisplaySessionPolicy(
    private val mContext: Context,
    private val displayAdapter: SplitDisplayController,
) {
    companion object {
        private const val TAG = "AADisplay_DisplaySessionPolicy"
        /** Keep OWN_DISPLAY_GROUP user-activity from timing out / dozing on Samsung. */
        private const val KEEP_AWAKE_INTERVAL_MS = 15_000L
        private const val TOUCH_KEEP_AWAKE_MIN_INTERVAL_MS = 1_000L
        /** PowerManager.USER_ACTIVITY_EVENT_TOUCH */
        private const val USER_ACTIVITY_EVENT_TOUCH = 2
        /** PowerManager.USER_ACTIVITY_EVENT_OTHER */
        private const val USER_ACTIVITY_EVENT_OTHER = 0
        /**
         * Display-scoped VD bright locks still need these legacy levels;
         * [PowerManager.SCREEN_BRIGHT_WAKE_LOCK] / [PowerManager.ACQUIRE_CAUSES_WAKEUP] are deprecated.
         */
        private const val SCREEN_BRIGHT_WAKE_LOCK = 0x0000000a
        private const val ACQUIRE_CAUSES_WAKEUP = 0x10000000
        /** Seconds to keep dual VD after AA disconnect before destroy. */
        private const val DELAY_DESTROY_SEC = 180
    }

    private var mDestroyJob: Job? = null

    private val isSupportInteractive = RomUtil.isMiui()
    private var mKeepAwakeJob: Job? = null
    private var mLastTouchKeepAwakeAt = 0L
    private var mMiuiReceiverRegistered = false
    private var iPowerManagerService: Any? = null
    private var iPowerManagerUserActivity: Method? = null

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
            // Phone screen policy must not blank the AA virtual display group.
            keepVirtualDisplayAwake("phone-$action", forceWake = true)
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
                    lock.acquire(2_000L)
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
            if (!mMiuiReceiverRegistered) {
                try {
                    ContextCompat.registerReceiver(
                        mContext,
                        this,
                        addAction(IntentFilter()),
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                    mMiuiReceiverRegistered = true
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
                AndroidHook.VdDensityPin.ensureHooked()
            }
        }

        fun release() {
            stopKeepAwakeLoop()
            if (mMiuiReceiverRegistered) {
                try {
                    mContext.unregisterReceiver(this)
                } catch (_: Throwable) {
                }
                mMiuiReceiverRegistered = false
            }
            if (isSupportInteractive) {
                try {
                    Settings.Secure.putInt(mContext.contentResolver, "synergy_mode", 0)
                } catch (_: Throwable) {
                }
            }
            releaseMonitor()
            AndroidHook.VdDensityPin.unHook()
        }
    }

    private fun startKeepAwakeLoop() {
        if (mKeepAwakeJob?.isActive == true) return
        mKeepAwakeJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(KEEP_AWAKE_INTERVAL_MS)
                keepVirtualDisplayAwake("heartbeat", forceWake = false)
            }
        }
    }

    private fun stopKeepAwakeLoop() {
        mKeepAwakeJob?.cancel()
        mKeepAwakeJob = null
    }

    /**
     * Keep / restore power for both AA virtual display groups so the car UI does not
     * stay black after phone sleep, doze, or OWN_DISPLAY_GROUP user-activity timeout.
     */
    fun keepVirtualDisplayAwake(reason: String, forceWake: Boolean = false) {
        val displayIds = aaVirtualDisplayIds()
        if (displayIds.isEmpty()) return
        try {
            interactiveMonitor.acquireMonitor()
            val event = if (forceWake) USER_ACTIVITY_EVENT_TOUCH else USER_ACTIVITY_EVENT_OTHER
            for (displayId in displayIds) {
                userActivityOnDisplay(displayId, event)
                if (forceWake) {
                    interactiveMonitor.pulseWake(displayId)
                }
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
        keepVirtualDisplayAwake("interaction", forceWake = true)
    }

    /**
     * IPowerManager.userActivity(displayId, …) — PowerManager only forwards the
     * context display id, which is useless for OWN_DISPLAY_GROUP virtual displays.
     * Resolves overload by name/arity so A14–A16 signature churn does not hard-fail.
     */
    private fun userActivityOnDisplay(displayId: Int, event: Int) {
        try {
            val service = iPowerManagerService
                ?: PowerManager::class.java.getDeclaredField("mService").apply {
                    isAccessible = true
                }.get(Instances.powerManager)?.also { iPowerManagerService = it }
                ?: return
            val method = iPowerManagerUserActivity ?: resolveUserActivityMethod(service.javaClass)
                ?.also { iPowerManagerUserActivity = it }
                ?: return
            invokeUserActivity(method, service, displayId, event)
        } catch (e: Throwable) {
            log(TAG, "IPowerManager.userActivity(display=$displayId) failed:", e)
        }
    }

    private fun resolveUserActivityMethod(serviceClass: Class<*>): Method? {
        val candidates = serviceClass.methods.filter { it.name == "userActivity" }
        // Prefer (int displayId, long time, int event, int flags)
        candidates.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 4 &&
                p[0] == Int::class.javaPrimitiveType &&
                p[1] == Long::class.javaPrimitiveType &&
                p[2] == Int::class.javaPrimitiveType &&
                p[3] == Int::class.javaPrimitiveType
        }?.let { return it }
        // (long time, int event, int flags) — no displayId
        candidates.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 3 &&
                p[0] == Long::class.javaPrimitiveType &&
                p[1] == Int::class.javaPrimitiveType &&
                p[2] == Int::class.javaPrimitiveType
        }?.let { return it }
        // (long time, boolean noChangeLights) legacy
        candidates.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 2 &&
                p[0] == Long::class.javaPrimitiveType &&
                p[1] == Boolean::class.javaPrimitiveType
        }?.let { return it }
        return candidates.firstOrNull()
    }

    private fun invokeUserActivity(method: Method, service: Any, displayId: Int, event: Int) {
        val now = SystemClock.uptimeMillis()
        when (method.parameterTypes.size) {
            4 -> method.invoke(service, displayId, now, event, 0)
            3 -> method.invoke(service, now, event, 0)
            2 -> method.invoke(service, now, false)
            else -> method.invoke(service, *Array<Any?>(method.parameterCount) { i ->
                val t = method.parameterTypes[i]
                when {
                    t == Int::class.javaPrimitiveType && i == 0 -> displayId
                    t == Long::class.javaPrimitiveType -> now
                    t == Int::class.javaPrimitiveType -> event
                    t == Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            })
        }
    }

    /** Wake the phone panel without deprecated ACQUIRE_CAUSES_WAKEUP wake locks. */
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
        interactiveMonitor.init()
        mDestroyJob?.cancelAndJoin()
    }

    suspend fun onDestroyPromptly() {
        restorePhoneDisplayPower()
        interactiveMonitor.release()
        mDestroyJob?.cancelAndJoin()
    }

    suspend fun onDestroy(onDestroySucceed: () -> Unit) {
        restorePhoneDisplayPower()
        interactiveMonitor.release()
        mDestroyJob?.cancelAndJoin()
        startDelayDestroy(onDestroySucceed)
    }

    /** Headless Delay Destroy so [onDestroySucceed] fires and VDs are released. */
    private fun startDelayDestroy(onDestroySucceed: () -> Unit) {
        mDestroyJob = flow {
            for (i in DELAY_DESTROY_SEC downTo 0) {
                emit(i)
                delay(1000)
            }
        }.onCompletion {
            if (it == null) {
                onDestroySucceed()
            }
        }.launchIn(CoroutineScope(Dispatchers.Main))
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
