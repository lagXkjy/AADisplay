package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.loadClass
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.service.AaActivityService
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

object AaCoolwalkAutoOpenHook {

    /** Shared debounce for starve + content_bounds + slot relaunch paths. */
    private const val FULL_BLEED_RELAUNCH_DEBOUNCE_MS = 2_000L

    @Volatile
    private var lastFullBleedRelaunchScheduleUptimeMs = -1L

    fun install(env: CoolwalkHookEnv, lpparam: XC_LoadPackage.LoadPackageParam) {
        logDebug(CoolwalkHookEnv.TAG, "AaUiHook: AutoOpen always-on startMethod=${env.startMethod?.name}")
        registerAutoOpenShownReceiver(env)
        hookCarSystemUiConnectedKick(env)
    }

    fun scheduleAutoOpenIfNeeded(
        env: CoolwalkHookEnv,
        reason: String = "unknown",
        bypassRearmGap: Boolean = false,
    ) {
        if (env.startMethod == null) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: AutoOpen skip ($reason): startMethod null")
            return
        }
        CoolwalkRailCoordinator.syncExternalTruth(InitFields.appContext.contentResolver)
        val snap = CoolwalkRailCoordinator.current()
        if (snap.phase == RailPhase.ReconnectSettling && snap.fullHuWidthPx <= 0 &&
            !reason.contains("content_bounds", ignoreCase = true)
        ) {
            logDebug(
                CoolwalkHookEnv.TAG,
                "AaUiHook: defer AutoOpen ($reason) until content_bounds sets full HU",
            )
            return
        }
        val now = SystemClock.uptimeMillis()
        if (!bypassRearmGap &&
            env.mAutoOpenSessionAtMs != 0L &&
            now - env.mAutoOpenSessionAtMs < CoolwalkHookEnv.AUTO_OPEN_REARM_GAP_MS
        ) {
            logDebug(
                CoolwalkHookEnv.TAG,
                "AaUiHook: AutoOpen skip rearm gap ($reason) elapsed=${now - env.mAutoOpenSessionAtMs}ms",
            )
            return
        }
        if (bypassRearmGap) {
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: arm AutoOpen bypassRearm ($reason)")
        }
        env.mAutoOpenSessionAtMs = now
        env.mAaDisplayShownThisSession = false
        env.mAutoOpenArmed = true
        env.mFacetEnsureHandler.removeCallbacksAndMessages(CoolwalkHookEnv.AUTO_OPEN_TOKEN)
        logDebug(
            CoolwalkHookEnv.TAG,
            "AaUiHook: arm AutoOpen retries ($reason) delays=${CoolwalkHookEnv.AUTO_OPEN_DELAYS_MS.contentToString()}",
        )
        for (delayMs in CoolwalkHookEnv.AUTO_OPEN_DELAYS_MS) {
            env.mFacetEnsureHandler.postAtTime(
                { tryAutoOpenAaDisplay(env, delayMs) },
                CoolwalkHookEnv.AUTO_OPEN_TOKEN,
                now + delayMs,
            )
        }
    }

    /**
     * Presentation may have been created at HU−rail before content_bounds reclaim finished.
     * Force a CarActivity relaunch so the next [DrawingSpec] is built at full HU width.
     *
     * @param force when true, relaunch even if [CoolwalkHookEnv.mAaDisplayShownThisSession] is
     *   still false — the CarActivity presentation VD may already exist at content-slot width.
     */
    fun scheduleFullBleedRelaunch(env: CoolwalkHookEnv, reason: String, force: Boolean = false) {
        if (env.startMethod == null) return
        if (!force && !env.mAaDisplayShownThisSession) {
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: full-bleed relaunch ($reason) → AutoOpen (not shown)")
            scheduleAutoOpenIfNeeded(env, reason, bypassRearmGap = true)
            return
        }
        val now = SystemClock.uptimeMillis()
        // Starve + content_bounds often fire together — keep the first delay chain.
        if (lastFullBleedRelaunchScheduleUptimeMs >= 0L &&
            now - lastFullBleedRelaunchScheduleUptimeMs < FULL_BLEED_RELAUNCH_DEBOUNCE_MS
        ) {
            logDebug(
                CoolwalkHookEnv.TAG,
                "AaUiHook: full-bleed relaunch debounced ($reason) " +
                    "elapsed=${now - lastFullBleedRelaunchScheduleUptimeMs}ms",
            )
            return
        }
        lastFullBleedRelaunchScheduleUptimeMs = now
        env.mAaDisplayShownThisSession = false
        env.mAutoOpenArmed = true
        env.mFacetEnsureHandler.removeCallbacksAndMessages(CoolwalkHookEnv.FULL_BLEED_RELAUNCH_TOKEN)
        logDebug(CoolwalkHookEnv.TAG, "AaUiHook: full-bleed relaunch ($reason)")
        // Widen / starve paths need time for compositor blX expand before Surface alloc.
        val delays = if (reason.contains("widen", ignoreCase = true) ||
            reason.contains("starve", ignoreCase = true) ||
            reason.contains("slot", ignoreCase = true)
        ) {
            longArrayOf(400L, 1200L, 2500L)
        } else {
            longArrayOf(0L, 400L, 1500L)
        }
        for (delayMs in delays) {
            env.mFacetEnsureHandler.postAtTime(
                { tryAutoOpenAaDisplay(env, -2L) },
                CoolwalkHookEnv.FULL_BLEED_RELAUNCH_TOKEN,
                now + delayMs,
            )
        }
        val fallbackAt = now + delays.max() + 50L
        env.mFacetEnsureHandler.postAtTime(
            { armFullBleedFallbackIfNeeded(env, reason) },
            CoolwalkHookEnv.FULL_BLEED_RELAUNCH_TOKEN,
            fallbackAt,
        )
    }

    /**
     * After the short full-bleed chain finishes, arm the long AutoOpen retry window only if
     * AADisplay still has not shown (failure-driven; success path keeps 0ms first hit).
     */
    private fun armFullBleedFallbackIfNeeded(env: CoolwalkHookEnv, reason: String) {
        if (env.mAaDisplayShownThisSession || !env.mAutoOpenArmed) return
        logDebug(
            CoolwalkHookEnv.TAG,
            "AaUiHook: full-bleed short chain exhausted ($reason) → long AutoOpen fallback",
        )
        scheduleAutoOpenIfNeeded(
            env,
            "full-bleed-fallback:content_bounds:$reason",
            bypassRearmGap = true,
        )
    }

    private fun isAutoOpenNotReady(t: Throwable): Boolean =
        t is IllegalStateException || t is NullPointerException

    private fun tryAutoOpenAaDisplay(env: CoolwalkHookEnv, delayMs: Long) {
        if (env.mAaDisplayShownThisSession) {
            markAaDisplayShown(env, "flag")
            return
        }
        if (!env.mAutoOpenArmed) return
        val method = env.startMethod ?: return
        val label = when {
            delayMs == -2L -> "full-bleed-relaunch"
            delayMs < 0L -> "connected"
            else -> "${delayMs}ms"
        }
        try {
            method.invoke(null, aaDisplayLaunchIntent())
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: AutoOpen invoke at $label")
        } catch (e: InvocationTargetException) {
            val cause = e.cause ?: e
            if (isAutoOpenNotReady(cause)) {
                logDebug(CoolwalkHookEnv.TAG, "AaUiHook: AutoOpen not-ready at $label: ${cause.message}")
            } else {
                log(CoolwalkHookEnv.TAG, "AaUiHook: AutoOpen invoke failed at $label", cause)
            }
        } catch (e: IllegalStateException) {
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: AutoOpen not-ready at $label: ${e.message}")
        } catch (e: NullPointerException) {
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: AutoOpen not-ready at $label: ${e.message}")
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: AutoOpen invoke failed at $label", e)
        }
    }

    private fun aaDisplayLaunchIntent(): Intent = Intent().apply {
        component = ComponentName(BuildConfig.APPLICATION_ID, AaActivityService::class.java.name)
        putExtra("android.intent.extra.PACKAGE_NAME", BuildConfig.APPLICATION_ID)
    }

    private fun registerAutoOpenShownReceiver(env: CoolwalkHookEnv) {
        if (env.mAutoOpenShownReceiver != null) return
        val ctx = InitFields.appContext
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != AABroadcastConst.ACTION_AA_DISPLAY_SHOWN) return
                markAaDisplayShown(env, "broadcast")
            }
        }
        try {
            ctx.registerReceiver(
                receiver,
                IntentFilter(AABroadcastConst.ACTION_AA_DISPLAY_SHOWN),
                Context.RECEIVER_EXPORTED,
            )
            env.mAutoOpenShownReceiver = receiver
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: registered AA_DISPLAY_SHOWN receiver for AutoOpen cancel")
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: register AA_DISPLAY_SHOWN receiver failed", e)
        }
    }

    private fun hookCarSystemUiConnectedKick(env: CoolwalkHookEnv) {
        if (env.mCarConnectedKickHooked) return
        val svcClass = try {
            loadClass("com.google.android.projection.gearhead.service.CarSystemUiControllerService")
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: AutoOpen connected-kick: SysUi service missing", e)
            return
        }
        try {
            val onCreate = svcClass.declaredMethods.firstOrNull {
                it.name == "onCreate" && it.parameterCount == 0
            } ?: run {
                log(CoolwalkHookEnv.TAG, "AaUiHook: AutoOpen connected-kick: onCreate missing")
                return
            }
            onCreate.isAccessible = true
            onCreate.hookAfter { param ->
                val service = param.thisObject ?: return@hookAfter
                installCarConnectedKickOnService(env, service, svcClass)
            }
            env.mCarConnectedKickHooked = true
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: AutoOpen connected-kick hooked SysUi onCreate")
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: AutoOpen connected-kick hook failed", e)
        }
    }

    private fun installCarConnectedKickOnService(env: CoolwalkHookEnv, service: Any, svcClass: Class<*>) {
        if (env.mCarConnectedListenerHooked) return
        for (field in svcClass.declaredFields) {
            if (Modifier.isStatic(field.modifiers)) continue
            field.isAccessible = true
            val listener = try {
                field.get(service)
            } catch (_: Throwable) {
                null
            } ?: continue
            if (listener === service) continue
            if (listener is Intent || listener is List<*> || listener is android.os.IBinder) continue
            val lClass = listener.javaClass
            val refsService = lClass.declaredFields.any { f ->
                f.isAccessible = true
                try {
                    f.get(listener) === service
                } catch (_: Throwable) {
                    false
                }
            }
            if (!refsService) continue
            var hooked = 0
            for (m in lClass.declaredMethods) {
                if (Modifier.isStatic(m.modifiers)) continue
                if (m.parameterCount != 1) continue
                if (m.returnType != Void.TYPE && m.returnType != Void::class.java) continue
                val p0 = m.parameterTypes[0]
                if (p0.isPrimitive || p0 == Intent::class.java) continue
                try {
                    m.isAccessible = true
                    m.hookAfter {
                        onCarClientConnectedSignal(env, "sysui.${m.name}")
                    }
                    hooked++
                } catch (_: Throwable) {
                }
            }
            if (hooked > 0) {
                env.mCarConnectedListenerHooked = true
                logDebug(
                    CoolwalkHookEnv.TAG,
                    "AaUiHook: AutoOpen connected-kick hooked $hooked method(s) on ${lClass.name}",
                )
                return
            }
        }
        logDebug(CoolwalkHookEnv.TAG, "AaUiHook: AutoOpen connected-kick: no SysUi listener field matched")
    }

    private fun onCarClientConnectedSignal(env: CoolwalkHookEnv, reason: String) {
        if (!env.mAutoOpenArmed || env.mAaDisplayShownThisSession) return
        logDebug(CoolwalkHookEnv.TAG, "AaUiHook: AutoOpen connected kick ($reason)")
        env.mFacetEnsureHandler.post {
            tryAutoOpenAaDisplay(env, -1L)
        }
    }

    fun markAaDisplayShown(env: CoolwalkHookEnv, reason: String) {
        if (env.mAaDisplayShownThisSession) return
        env.mAaDisplayShownThisSession = true
        env.mAutoOpenArmed = false
        env.mFacetEnsureHandler.removeCallbacksAndMessages(CoolwalkHookEnv.AUTO_OPEN_TOKEN)
        logDebug(CoolwalkHookEnv.TAG, "AaUiHook: AutoOpen stop retries ($reason)")
    }
}
