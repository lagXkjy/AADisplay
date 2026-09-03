package io.github.nitsuya.aa.display.ui.window

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.util.AaSystemBroadcast
import android.os.PowerManager
import android.os.SystemClock
import android.os.SystemProperties
import android.provider.Settings
import android.view.Display
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.ui.aa.split.SplitDisplayController
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook
import io.github.nitsuya.aa.display.xposed.hook.PhoneHidRedirect
import io.github.nitsuya.aa.display.xposed.hook.VdDensityPin
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.lang.reflect.Method
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * AA display session policy: Delay Destroy (180s) and keep-awake for split pane VDs plus the
 * CarActivity presentation display id reported by the AA UI process.
 * Phone overlay UI was removed; this class no longer hosts any WindowManager views.
 *
 * **Hard rule: AA virtual panes must not stay black (ColorFade / OFF).**
 *
 * Keep-awake strategy (with phone **pseudo screen-off**):
 * - Hold display-scoped [SCREEN_BRIGHT_WAKE_LOCK] Monitor + heartbeat [userActivity] on pane
 *   VDs for the whole AA session (Samsung OWN_DISPLAY_GROUP must not ColorFade).
 * - **Phone panel:** when DEFAULT_DISPLAY idle since last wake / real phone
 *   [userActivity](TOUCH|BUTTON|ACCESSIBILITY) exceeds [Settings.System.SCREEN_OFF_TIMEOUT],
 *   force [IPowerManager.goToSleep] on display 0 — **unless** the phone front task is a
 *   real user app (e.g. Maps). Do not rely on the system idle timer (AA / apps on VDs hold
 *   global bright locks that block it). Car / VD interaction must not reset that clock
 *   ([onVirtualDisplayUserInteraction] only keeps panes awake).
 * - Never use [ACQUIRE_CAUSES_WAKEUP] — leaks to the phone panel on Samsung. Pane OFF/DOZE uses
 *   a short display-scoped bright pulse without WAKEUP; phone SCREEN_OFF path is userActivity only.
 * - Presentation VD: no wake lock / userActivity / WAKEUP pulse (shares displayGroup 0).
 * - Pane overlays intentionally omit FLAG_KEEP_SCREEN_ON (WM can promote that to displayId=-1).
 */
class DisplaySessionPolicy(
    private val mContext: Context,
    private val displayAdapter: SplitDisplayController,
) {
    companion object {
        private const val TAG = "AADisplay_DisplaySessionPolicy"
        /** Poll pane VD power while phone is on (cheap — no idle side effects). */
        private const val KEEP_AWAKE_INTERVAL_MS = 15_000L
        /** While phone is off — beat ColorFade before DreamManager settles. */
        private const val KEEP_AWAKE_INTERVAL_SCREEN_OFF_MS = 3_000L
        private const val TOUCH_KEEP_AWAKE_MIN_INTERVAL_MS = 1_000L
        /** After phone SCREEN_OFF, reassert before the next heartbeat. */
        private const val SCREEN_OFF_REASSERT_COUNT = 10
        private const val SCREEN_OFF_REASSERT_INTERVAL_MS = 400L
        /** PowerManager.USER_ACTIVITY_EVENT_OTHER */
        private const val USER_ACTIVITY_EVENT_OTHER = 0
        /** PowerManager.USER_ACTIVITY_EVENT_BUTTON */
        private const val USER_ACTIVITY_EVENT_BUTTON = 1
        /** PowerManager.USER_ACTIVITY_EVENT_TOUCH */
        private const val USER_ACTIVITY_EVENT_TOUCH = 2
        /** PowerManager.USER_ACTIVITY_EVENT_ACCESSIBILITY */
        private const val USER_ACTIVITY_EVENT_ACCESSIBILITY = 3
        /**
         * Display-scoped VD bright locks still need this legacy level;
         * [PowerManager.SCREEN_BRIGHT_WAKE_LOCK] is deprecated but required for hidden API.
         */
        @Suppress("DEPRECATION")
        private const val SCREEN_BRIGHT_WAKE_LOCK = PowerManager.SCREEN_BRIGHT_WAKE_LOCK
        /** Timed display-scoped bright pulse when a pane VD reports OFF/DOZE (no WAKEUP flag). */
        private const val VD_BRIGHT_PULSE_MS = 3_000L
        /** Seconds to keep dual VD after AA disconnect before destroy. */
        private const val DELAY_DESTROY_SEC = 180
        /** Min gap between UI presentation recovery nudges (battery). */
        private const val PRESENTATION_RECOVERY_MIN_INTERVAL_MS = 45_000L
        /** MIUI/HyperOS: toggle Secure synergy_mode on phone screen on/off. */
        private val isMiui = SystemProperties.get("ro.miui.ui.version.name").isNotBlank()
        /** Poll main-panel idle while AA session is live. */
        private const val PSEUDO_OFF_POLL_MS = 2_000L
        /** Back off after a forced sleep so SCREEN_OFF / VD keep-awake can settle. */
        private const val PSEUDO_OFF_FORCE_MIN_INTERVAL_MS = 8_000L
        /** PowerManager.GO_TO_SLEEP_REASON_TIMEOUT */
        private const val GO_TO_SLEEP_REASON_TIMEOUT = 2
        /**
         * After createVirtualDisplay, DisplayManager returns an id before PMS
         * [PowerGroup] is added for OWN_DISPLAY_GROUP — userActivity NPEs until then.
         */
        private const val POWER_GROUP_RETRY_DELAY_MS = 250L
        private const val POWER_GROUP_RETRY_MAX = 8
    }

    private var mDestroyJob: Job? = null

    /** True while AA UI is attached; false during Delay Destroy / after teardown. */
    val isAaSessionLive: Boolean
        get() = mDestroyJob?.isActive != true

    private var mKeepAwakeJob: Job? = null
    private var mPseudoOffJob: Job? = null
    private var mScreenOffReassertJob: Job? = null
    private var mLastTouchKeepAwakeAt = 0L
    /** Uptime when the phone panel last became active (wake / session start). */
    private var mPseudoOffAnchorUptime = SystemClock.uptimeMillis()
    private var mPhoneWasInteractive = false
    private var mLastDefaultDisplayState = Display.STATE_UNKNOWN
    private var mLastForcePhoneOffAt = 0L
    private var mScreenReceiverRegistered = false
    private var iPowerManagerService: Any? = null
    private var iPowerManagerUserActivity: Method? = null
    private var iPowerManagerGoToSleep: Method? = null
    private var mLoggedMissingDisplayUserActivity = false
    private var mLoggedMissingGoToSleep = false
    /** Soft-fail once: VD exists in DM before PMS registers OWN_DISPLAY_GROUP PowerGroup. */
    private var mLoggedMissingPowerGroup = false
    /** After [POWER_GROUP_RETRY_MAX] misses, stop scheduling until a userActivity succeeds. */
    private var mPowerGroupRetriesExhausted = false
    private var mPowerGroupRetryJob: Job? = null
    private var mLastPresentationRecoveryAt = 0L
    private val mPhoneUserActivityHooks = mutableListOf<XC_MethodHook.Unhook>()
    @Volatile private var mPhoneUserActivityHookInstalled = false

    /** Split pane VDs (OWN_DISPLAY_GROUP). */
    private fun aaPaneDisplayIds(): IntArray {
        val primary = displayAdapter.primaryDisplayId
        val secondary = displayAdapter.secondaryDisplayId
        return when {
            primary == Display.INVALID_DISPLAY && secondary == Display.INVALID_DISPLAY -> intArrayOf()
            secondary == Display.INVALID_DISPLAY -> intArrayOf(primary)
            primary == Display.INVALID_DISPLAY -> intArrayOf(secondary)
            else -> intArrayOf(primary, secondary)
        }
    }

    /** CarActivity presentation id from AA UI; INVALID when AA disconnected. */
    private fun aaPresentationDisplayId(): Int {
        val id = displayAdapter.mAaUiDisplayId
        return if (id != Display.INVALID_DISPLAY && id != Display.DEFAULT_DISPLAY) id else Display.INVALID_DISPLAY
    }

    private fun isVirtualDisplayPoweredOff(displayId: Int): Boolean {
        return try {
            val state = Instances.displayManager.getDisplay(displayId)?.state ?: return false
            state == Display.STATE_OFF || state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND
        } catch (_: Throwable) {
            false
        }
    }

    private fun anyPanePoweredOff(): Boolean {
        return aaPaneDisplayIds().any { isVirtualDisplayPoweredOff(it) }
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
            if (isMiui) {
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
                    mLastDefaultDisplayState = Display.STATE_OFF
                    // Critical Samsung path: DreamManager may doze OWN_DISPLAY_GROUP with the phone.
                    keepVirtualDisplayAwake("phone-SCREEN_OFF", forceWake = true)
                    maybeRecoverPresentation("phone-SCREEN_OFF", phoneOff = true)
                    startScreenOffReassertBurst()
                }
                Intent.ACTION_SCREEN_ON -> {
                    cancelScreenOffReassertBurst()
                    mLastDefaultDisplayState = Display.STATE_ON
                    resetPseudoOffAnchor("SCREEN_ON")
                    // Keep pane Monitors — phone idle is handled by pseudo screen-off, not by
                    // dropping VD keep-awake.
                    pruneStaleDisplayLocks()
                    acquireMonitor()
                }
                else -> keepVirtualDisplayAwake("phone-$action", forceWake = false)
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

        fun pruneStaleDisplayLocks() {
            pruneStaleLocks(aaPaneDisplayIds().toSet())
        }

        fun releaseMonitorLocks() {
            releaseLockMap(monitorLocks, "Monitor")
        }

        /** Long-held display-scoped Monitor for each pane VD for the whole AA session. */
        fun acquireMonitor() {
            val ids = aaPaneDisplayIds()
            val active = ids.toSet()
            pruneStaleLocks(active)
            if (ids.isEmpty()) {
                releaseMonitorLocks()
                return
            }
            val identity = Binder.clearCallingIdentity()
            try {
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
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        /**
         * Timed display-scoped bright lock when [Display.getState] is OFF/DOZE.
         * Must not use [PowerManager.ACQUIRE_CAUSES_WAKEUP] — leaks to the phone panel on Samsung.
         */
        fun pulseVdBright(displayId: Int) {
            val identity = Binder.clearCallingIdentity()
            try {
                val lock = wakePulseLocks.getOrPut(displayId) {
                    newDisplayWakeLock(
                        SCREEN_BRIGHT_WAKE_LOCK,
                        "VdBright",
                        displayId
                    )
                }
                if (!lock.isHeld) {
                    lock.acquire(VD_BRIGHT_PULSE_MS)
                }
            } catch (e: Throwable) {
                log(TAG, "VD pulseVdBright failed display=$displayId:", e)
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        fun releaseMonitor() {
            releaseLockMap(monitorLocks, "Monitor")
            releaseLockMap(wakePulseLocks, "VdBright")
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
            if (isMiui) {
                onReceive(mContext, if (Instances.powerManager.isInteractive) Intent.ACTION_SCREEN_ON else Intent.ACTION_SCREEN_OFF)
            }
            acquireMonitor()
            startKeepAwakeLoop()
            startPseudoOffLoop()
            ensurePhoneUserActivityHook()
            keepVirtualDisplayAwake("init")
            // ensureHooked: do not reinstall/clear map on AA reconnect (onResume → init).
            if (AndroidHook.isReadyForSystemHooks()) {
                VdDensityPin.ensureHooked()
            }
        }

        fun release() {
            stopKeepAwakeLoop()
            stopPseudoOffLoop()
            cancelScreenOffReassertBurst()
            cancelPowerGroupRetry()
            releasePhoneUserActivityHook()
            if (mScreenReceiverRegistered) {
                try {
                    mContext.unregisterReceiver(this)
                } catch (_: Throwable) {
                }
                mScreenReceiverRegistered = false
            }
            if (isMiui) {
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
        // Prefer DEFAULT_DISPLAY state: Samsung goToSleep can leave isInteractive=true in DOZE.
        return if (isDefaultDisplayOn()) {
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
                val phonePanelOff = !isDefaultDisplayOn()
                val anyPaneOff = anyPanePoweredOff()
                keepVirtualDisplayAwake("heartbeat", forceWake = phonePanelOff || anyPaneOff)
                maybeRecoverPresentation("heartbeat", phonePanelOff)
            }
        }
    }

    private fun stopKeepAwakeLoop() {
        mKeepAwakeJob?.cancel()
        mKeepAwakeJob = null
    }

    private fun startPseudoOffLoop() {
        resetPseudoOffAnchor("session-start")
        mPhoneWasInteractive = isPhoneInteractive()
        mLastDefaultDisplayState = defaultDisplayState()
        if (mPseudoOffJob?.isActive == true) return
        mPseudoOffJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(PSEUDO_OFF_POLL_MS)
                maybeForcePhonePanelOff()
            }
        }
    }

    private fun resetPseudoOffAnchor(@Suppress("UNUSED_PARAMETER") reason: String) {
        mPseudoOffAnchorUptime = SystemClock.uptimeMillis()
    }

    /**
     * Real phone-panel interaction (not car / VD). Resets the pseudo-off idle clock so
     * operating the handset does not get force-slept mid-gesture.
     */
    private fun notePhoneUserActivity(event: Int) {
        when (event) {
            USER_ACTIVITY_EVENT_TOUCH,
            USER_ACTIVITY_EVENT_BUTTON,
            USER_ACTIVITY_EVENT_ACCESSIBILITY -> Unit
            else -> return
        }
        if (!isDefaultDisplayOn()) return
        resetPseudoOffAnchor("phone-ua-$event")
    }

    /**
     * Observe [PowerManagerService] display-scoped userActivity for DEFAULT_DISPLAY only.
     * Pane keep-awake calls the same API with VD displayIds and must not extend phone idle.
     */
    private fun ensurePhoneUserActivityHook() {
        if (mPhoneUserActivityHookInstalled) return
        if (!AndroidHook.isReadyForSystemHooks()) return
        val pmsClass = AndroidHook.loadSystemClass("com.android.server.power.PowerManagerService")
        if (pmsClass == null) {
            log(TAG, "phone userActivity hook: PowerManagerService missing")
            return
        }
        try {
            var installed = 0
            for (method in pmsClass.declaredMethods) {
                if (method.name != "userActivity" && method.name != "userActivityInternal") continue
                val pts = method.parameterTypes
                // (displayId, eventTime, event, flags[, uid…])
                if (pts.size < 3) continue
                if (pts[0] != Int::class.javaPrimitiveType) continue
                if (pts[1] != Long::class.javaPrimitiveType) continue
                if (pts[2] != Int::class.javaPrimitiveType) continue
                method.isAccessible = true
                val unhook = method.hookBefore { param ->
                    try {
                        val displayId = param.args[0] as? Int ?: return@hookBefore
                        if (displayId != Display.DEFAULT_DISPLAY) return@hookBefore
                        val event = param.args[2] as? Int ?: return@hookBefore
                        notePhoneUserActivity(event)
                    } catch (_: Throwable) {
                        // Never let observer failures abort PowerManagerService.userActivity.
                    }
                }
                mPhoneUserActivityHooks.add(unhook)
                installed++
            }
            if (installed == 0) {
                log(TAG, "phone userActivity hook: no matching methods")
                return
            }
            mPhoneUserActivityHookInstalled = true
            log(TAG, "phone userActivity hook installed n=$installed")
        } catch (e: Throwable) {
            releasePhoneUserActivityHook()
            log(TAG, "phone userActivity hook failed:", e)
        }
    }

    private fun releasePhoneUserActivityHook() {
        mPhoneUserActivityHooks.toList().forEach { unhook ->
            try {
                unhook.unhook()
            } catch (_: Throwable) {
            }
        }
        mPhoneUserActivityHooks.clear()
        mPhoneUserActivityHookInstalled = false
    }

    private fun defaultDisplayState(): Int {
        return try {
            Instances.displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_ON
        } catch (_: Throwable) {
            Display.STATE_ON
        }
    }

    private fun isDefaultDisplayOn(): Boolean {
        return when (defaultDisplayState()) {
            Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> false
            else -> true
        }
    }

    /** Samsung goToSleep often leaves isInteractive=true in DOZE; use display OFF→ON as wake. */
    private fun noteDefaultDisplayWakeTransition() {
        val state = defaultDisplayState()
        if (state == Display.STATE_ON && mLastDefaultDisplayState != Display.STATE_ON) {
            resetPseudoOffAnchor("display-ON-from-$mLastDefaultDisplayState")
        }
        mLastDefaultDisplayState = state
    }

    private fun stopPseudoOffLoop() {
        mPseudoOffJob?.cancel()
        mPseudoOffJob = null
    }

    private fun screenOffTimeoutMs(): Long {
        return try {
            Settings.System.getInt(
                mContext.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                30_000,
            ).toLong().coerceAtLeast(5_000L)
        } catch (_: Throwable) {
            30_000L
        }
    }

    /**
     * Idle since the last phone wake / phone-panel userActivity / AA session anchor.
     * Do not read PowerManagerService lastUserActivity — Samsung values stay stale across
     * goToSleep/wake and caused pseudo-off ~3s after a manual wake.
     * Live phone TOUCH/BUTTON/A11Y is mirrored via [ensurePhoneUserActivityHook] instead.
     */
    private fun phoneDisplayGroupIdleMs(): Long {
        return (SystemClock.uptimeMillis() - mPseudoOffAnchorUptime).coerceAtLeast(0L)
    }

    private fun maybeForcePhonePanelOff() {
        if (aaPaneDisplayIds().isEmpty()) return
        val interactive = isPhoneInteractive()
        if (interactive && !mPhoneWasInteractive) {
            resetPseudoOffAnchor("interactive-rise")
        }
        mPhoneWasInteractive = interactive
        noteDefaultDisplayWakeTransition()
        // Only count down while the phone panel is actually on (not DOZE/OFF).
        if (!isDefaultDisplayOn()) return
        // Handset showing Maps / Settings / etc. — never force-sleep over a user FG app.
        if (phoneHasForegroundUserApp()) {
            resetPseudoOffAnchor("phone-fg-app")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - mLastForcePhoneOffAt < PSEUDO_OFF_FORCE_MIN_INTERVAL_MS) return
        val idleMs = phoneDisplayGroupIdleMs()
        val timeoutMs = screenOffTimeoutMs()
        if (idleMs < timeoutMs) return
        if (forcePhonePanelOff("pseudo-idle-${idleMs}ms/${timeoutMs}ms")) {
            mLastForcePhoneOffAt = now
            mLastDefaultDisplayState = defaultDisplayState()
        }
    }

    /**
     * Phone DEFAULT_DISPLAY front is a real user app (Maps, browser, …), not Home / SystemUI.
     * Pseudo-off must not lock the panel while the user is using the handset.
     */
    private fun phoneHasForegroundUserApp(): Boolean {
        return try {
            displayAdapter.ownership.phoneHasForegroundUserApp()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Force the phone panel off while AA pane VDs stay powered.
     * Uses [IPowerManager.goToSleep] (displayId overload when available).
     */
    private fun forcePhonePanelOff(reason: String): Boolean {
        if (!isPhoneInteractive()) return false
        val identity = Binder.clearCallingIdentity()
        try {
            val service = iPowerManagerService
                ?: PowerManager::class.java.getDeclaredField("mService").apply {
                    isAccessible = true
                }.get(Instances.powerManager)?.also { iPowerManagerService = it }
                ?: return false
            val method = iPowerManagerGoToSleep ?: resolveGoToSleepMethod(service.javaClass)
                ?.also { iPowerManagerGoToSleep = it }
            if (method == null) {
                if (!mLoggedMissingGoToSleep) {
                    mLoggedMissingGoToSleep = true
                    log(TAG, "IPowerManager.goToSleep unavailable; pseudo screen-off disabled")
                }
                return false
            }
            val now = SystemClock.uptimeMillis()
            when (method.parameterTypes.size) {
                4 -> method.invoke(
                    service,
                    Display.DEFAULT_DISPLAY,
                    now,
                    GO_TO_SLEEP_REASON_TIMEOUT,
                    0,
                )
                3 -> method.invoke(service, now, GO_TO_SLEEP_REASON_TIMEOUT, 0)
                else -> return false
            }
            log(TAG, "forcePhonePanelOff[$reason] timeout=${screenOffTimeoutMs()}ms")
            return true
        } catch (e: Throwable) {
            log(TAG, "forcePhonePanelOff[$reason] failed:", e)
            return false
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun resolveGoToSleepMethod(serviceClass: Class<*>): Method? {
        serviceClass.methods.firstOrNull { m ->
            if (m.name != "goToSleep") return@firstOrNull false
            val p = m.parameterTypes
            p.size == 4 &&
                p[0] == Int::class.javaPrimitiveType &&
                p[1] == Long::class.javaPrimitiveType &&
                p[2] == Int::class.javaPrimitiveType &&
                p[3] == Int::class.javaPrimitiveType
        }?.let { return it }
        return serviceClass.methods.firstOrNull { m ->
            if (m.name != "goToSleep") return@firstOrNull false
            val p = m.parameterTypes
            p.size == 3 &&
                p[0] == Long::class.javaPrimitiveType &&
                p[1] == Int::class.javaPrimitiveType &&
                p[2] == Int::class.javaPrimitiveType
        }
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
     * Keep pane VDs powered for the whole AA session (Monitor + display-scoped userActivity).
     * Phone panel sleep is owned by pseudo screen-off — never wake the phone from here.
     */
    fun keepVirtualDisplayAwake(reason: String, forceWake: Boolean = false) {
        val paneIds = aaPaneDisplayIds()
        if (paneIds.isEmpty() && aaPresentationDisplayId() == Display.INVALID_DISPLAY) return
        try {
            interactiveMonitor.acquireMonitor()
            if (paneIds.isEmpty()) return
            val phonePanelOff = !isDefaultDisplayOn()
            val screenOffPath = reason.startsWith("phone-SCREEN_OFF")
            val event = if (forceWake || phonePanelOff || screenOffPath) {
                USER_ACTIVITY_EVENT_TOUCH
            } else {
                USER_ACTIVITY_EVENT_OTHER
            }
            for (displayId in paneIds) {
                val paneOff = isVirtualDisplayPoweredOff(displayId)
                userActivityOnDisplay(displayId, event)
                // Only pulse bright when a pane is already OFF/DOZE — not on every SCREEN_OFF
                // (WAKEUP-free pulse still risks OEM leakage if overused while phone is sleeping).
                if (paneOff && !screenOffPath) {
                    interactiveMonitor.pulseVdBright(displayId)
                }
            }
        } catch (e: Throwable) {
            log(TAG, "keepVirtualDisplayAwake[$reason] failed:", e)
        }
    }

    /** Drop stale presentation Monitor locks when AA UI reports or clears its presentation id. */
    fun onAaUiDisplayIdChanged(displayId: Int) {
        if (displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY) {
            mLastPresentationRecoveryAt = 0L
        }
        try {
            interactiveMonitor.acquireMonitor()
        } catch (e: Throwable) {
            log(TAG, "onAaUiDisplayIdChanged prune failed:", e)
        }
    }

    /**
     * Ask the AA UI to rebind TextureView surfaces when presentation may be black while
     * pane VDs are still live (phone off / presentation DOZE / ColorFade).
     */
    private fun maybeRecoverPresentation(reason: String, phoneOff: Boolean) {
        val presentationId = aaPresentationDisplayId()
        if (presentationId == Display.INVALID_DISPLAY) return
        if (aaPaneDisplayIds().isEmpty()) return
        val presentationOff = isVirtualDisplayPoweredOff(presentationId)
        if (!phoneOff && !presentationOff) return
        val now = SystemClock.uptimeMillis()
        if (now - mLastPresentationRecoveryAt < PRESENTATION_RECOVERY_MIN_INTERVAL_MS) return
        mLastPresentationRecoveryAt = now
        logDebug(TAG, "maybeRecoverPresentation[$reason] id=$presentationId phoneOff=$phoneOff off=$presentationOff")
        sendPresentationRecoveryBroadcast()
    }

    private fun sendPresentationRecoveryBroadcast() {
        sendAaDisplayBroadcast(AABroadcastConst.ACTION_REQUEST_DISPLAY_RECOVERY, "recovery")
    }

    fun sendCoolwalkFullBleedBroadcast(includeGearhead: Boolean = false) {
        try {
            val intent = Intent(AABroadcastConst.ACTION_COOLWALK_FULL_BLEED)
            if (includeGearhead) {
                intent.putExtra(AABroadcastConst.EXTRA_COOLWALK_RELAUNCH_PRESENTATION, true)
                AaSystemBroadcast.toAaDisplayAndGearhead(mContext, intent)
            } else {
                AaSystemBroadcast.toAaDisplay(mContext, intent)
            }
        } catch (e: Throwable) {
            log(TAG, "sendAaDisplayBroadcast[full-bleed] failed:", e)
        }
    }

    private fun sendAaDisplayBroadcast(action: String, label: String) {
        try {
            AaSystemBroadcast.toAaDisplay(mContext, Intent(action))
        } catch (e: Throwable) {
            log(TAG, "sendAaDisplayBroadcast[$label] failed:", e)
        }
    }

    private fun resetPresentationRecoveryState() {
        mLastPresentationRecoveryAt = 0L
    }

    fun onVirtualDisplayUserInteraction() {
        // Car / VD only — must not reset phone pseudo-off idle (see ensurePhoneUserActivityHook).
        val now = SystemClock.uptimeMillis()
        if (now - mLastTouchKeepAwakeAt < TOUCH_KEEP_AWAKE_MIN_INTERVAL_MS) return
        mLastTouchKeepAwakeAt = now
        keepVirtualDisplayAwake("interaction", forceWake = !isDefaultDisplayOn() || anyPanePoweredOff())
    }

    /**
     * IPowerManager.userActivity(displayId, …) — PowerManager only forwards the
     * context display id, which is useless for OWN_DISPLAY_GROUP virtual displays.
     * Only the displayId overload is used; global overloads would wake the phone panel.
     *
     * AOSP/OEM [userActivityNoUpdateLocked] NPEs when [PowerGroup] is not yet mapped for
     * a fresh OWN_DISPLAY_GROUP VD (or after teardown). That is a soft race — already
     * caught here; never let it spam or abort keep-awake. Retry briefly after create.
     */
    private fun userActivityOnDisplay(displayId: Int, event: Int): Boolean {
        if (displayId == Display.INVALID_DISPLAY) return false
        try {
            // Stale id after release: DM gone → skip before poking PMS.
            if (Instances.displayManager.getDisplay(displayId) == null) return false
            val service = iPowerManagerService
                ?: PowerManager::class.java.getDeclaredField("mService").apply {
                    isAccessible = true
                }.get(Instances.powerManager)?.also { iPowerManagerService = it }
                ?: return false
            val method = iPowerManagerUserActivity ?: resolveDisplayUserActivityMethod(service.javaClass)
                ?.also { iPowerManagerUserActivity = it }
            if (method == null) {
                if (!mLoggedMissingDisplayUserActivity) {
                    mLoggedMissingDisplayUserActivity = true
                    log(TAG, "IPowerManager.userActivity(displayId,…) unavailable; skip to avoid waking phone")
                }
                return false
            }
            method.invoke(service, displayId, SystemClock.uptimeMillis(), event, 0)
            mPowerGroupRetriesExhausted = false
            return true
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
            if (isMissingPowerGroup(cause)) {
                if (!mLoggedMissingPowerGroup) {
                    mLoggedMissingPowerGroup = true
                    log(
                        TAG,
                        "IPowerManager.userActivity(display=$displayId) soft-fail: " +
                            "PowerGroup not ready (OWN_DISPLAY_GROUP race); will retry",
                    )
                }
                if (!mPowerGroupRetriesExhausted) {
                    schedulePowerGroupRetry()
                }
                return false
            }
            log(TAG, "IPowerManager.userActivity(display=$displayId) failed:", e)
            return false
        }
    }

    private fun isMissingPowerGroup(t: Throwable): Boolean {
        if (t !is NullPointerException) return false
        val msg = t.message ?: return false
        return msg.contains("PowerGroup") || msg.contains("getGroupId")
    }

    /** Re-assert pane userActivity until PMS has PowerGroups for new OWN_DISPLAY_GROUP VDs. */
    private fun schedulePowerGroupRetry() {
        if (mPowerGroupRetryJob?.isActive == true) return
        mPowerGroupRetryJob = CoroutineScope(Dispatchers.Default).launch {
            repeat(POWER_GROUP_RETRY_MAX) {
                delay(POWER_GROUP_RETRY_DELAY_MS)
                if (!isActive) return@launch
                val ids = aaPaneDisplayIds()
                if (ids.isEmpty()) return@launch
                var anyFail = false
                for (displayId in ids) {
                    if (!userActivityOnDisplay(displayId, USER_ACTIVITY_EVENT_TOUCH)) {
                        anyFail = true
                    }
                }
                if (!anyFail) return@launch
            }
            mPowerGroupRetriesExhausted = true
        }
    }

    private fun cancelPowerGroupRetry() {
        mPowerGroupRetryJob?.cancel()
        mPowerGroupRetryJob = null
        mPowerGroupRetriesExhausted = false
        mLoggedMissingPowerGroup = false
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
        // Soft reconnect: only recover panes that are already OFF/DOZE while phone is off.
        keepVirtualDisplayAwake("resume")
        runCatching { PhoneHidRedirect.onSessionLiveChanged(true) }
            .onFailure { log(TAG, "PhoneHidRedirect onResume failed", it) }
    }

    suspend fun onDestroyPromptly() {
        runCatching { PhoneHidRedirect.onSessionLiveChanged(false) }
            .onFailure { log(TAG, "PhoneHidRedirect onDestroyPromptly failed", it) }
        resetPresentationRecoveryState()
        restorePhoneDisplayPower()
        interactiveMonitor.release()
        mDestroyJob?.cancelAndJoin()
    }

    suspend fun onDestroy(onDestroySucceed: () -> Unit) {
        // Keep heartbeat through Delay Destroy so soft reconnect can recover OFF panes.
        // Monitor locks are acquired only when phone is off or a pane is OFF/DOZE.
        runCatching { PhoneHidRedirect.onSessionLiveChanged(false) }
            .onFailure { log(TAG, "PhoneHidRedirect onDestroy failed", it) }
        resetPresentationRecoveryState()
        mDestroyJob?.cancelAndJoin()
        startDelayDestroy(onDestroySucceed)
    }

    /** Headless Delay Destroy so [onDestroySucceed] fires and VDs are released. */
    private fun startDelayDestroy(onDestroySucceed: () -> Unit) {
        mDestroyJob = CoroutineScope(Dispatchers.Main).launch {
            delay(DELAY_DESTROY_SEC * 1000L)
            resetPresentationRecoveryState()
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
