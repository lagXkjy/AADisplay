package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import com.github.kyuubiran.ezxhelper.utils.findAllMethods
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.hook.abortMethod
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.logDebug
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.concurrent.atomic.AtomicBoolean

object AaBtnEventHook: AaHook() {
    override val tagName: String = "AAD_AaBtnEventHook"

    private const val ACTION_MEDIA_BUTTON = "android.intent.action.MEDIA_BUTTON"
    private const val ACTION_PROJECTED_KEY_EVENT = "android.intent.action.projected.KEY_EVENT"
    private const val DEDUP_WINDOW_MS = 80L

    /** Cross-channel identity; omit eventTime — MEDIA_BUTTON vs KEY_EVENT often differ by a few ms. */
    private data class KeyEventSig(
        val keyCode: Int,
        val action: Int,
        val downTime: Long,
        val longPress: Boolean,
    )

    override fun isSupportProcess(processName: String): Boolean {
        return processProjection == processName
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val hookedReceiverClasses = Collections.synchronizedSet(HashSet<String>())
        val activeReceiverByAction = Collections.synchronizedMap(HashMap<String, Any>())
        val longPressByKeyCode = Collections.synchronizedMap(HashMap<Int, AtomicBoolean>())
        val dedupLock = Any()
        var lastForwardedSig: KeyEventSig? = null
        var lastForwardedAtMs = 0L

        fun isTargetAction(action: String?): Boolean {
            return action == ACTION_MEDIA_BUTTON || action == ACTION_PROJECTED_KEY_EVENT
        }

        fun targetActionsOf(filter: IntentFilter): List<String> {
            return buildList {
                if (filter.hasAction(ACTION_MEDIA_BUTTON)) add(ACTION_MEDIA_BUTTON)
                if (filter.hasAction(ACTION_PROJECTED_KEY_EVENT)) add(ACTION_PROJECTED_KEY_EVENT)
            }
        }

        fun claimKeyEvent(keyEvent: KeyEvent): Boolean {
            val sig = KeyEventSig(
                keyCode = keyEvent.keyCode,
                action = keyEvent.action,
                downTime = keyEvent.downTime,
                longPress = keyEvent.isLongPress,
            )
            val now = SystemClock.elapsedRealtime()
            synchronized(dedupLock) {
                if (sig == lastForwardedSig && now - lastForwardedAtMs < DEDUP_WINDOW_MS) {
                    return false
                }
                lastForwardedSig = sig
                lastForwardedAtMs = now
                return true
            }
        }

        findAllMethods(ContextWrapper::class.java) {
            name == "registerReceiver"
        }.hookBefore { param ->
            if (param.args[0] == null) return@hookBefore
            val intentFilter = param.args.getOrNull(1) as? IntentFilter ?: return@hookBefore
            val targetActions = targetActionsOf(intentFilter)
            if (targetActions.isEmpty()) return@hookBefore

            val receiver = param.args[0] as Any
            val clazz = receiver.javaClass
            val clazzName = clazz.name
            log(tagName, "registerReceiver->$clazzName:${targetActions.joinToString()}")
            targetActions.forEach { action ->
                activeReceiverByAction[action] = receiver
            }

            if (!hookedReceiverClasses.add(clazzName)) {
                return@hookBefore
            }
            try {
                findMethod(clazz, findSuper = true) {
                    name == "onReceive"
                    && parameterTypes[0] == Context::class.java
                    && parameterTypes[1] == Intent::class.java
                }.hookBefore { receiveParam ->
                    try {
                        val intent = receiveParam.args[1] as Intent
                        val eventAction = intent.action
                        if (!isTargetAction(eventAction)) return@hookBefore
                        if (!activeReceiverByAction.containsKey(eventAction)) return@hookBefore

                        val keyEvent = IntentCompat.getParcelableExtra(
                            intent,
                            "android.intent.extra.KEY_EVENT",
                            KeyEvent::class.java
                        ) ?: return@hookBefore
                        val keyCode = keyEvent.keyCode
                        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
                            return@hookBefore
                        }

                        // Steal from AA for any managed action; only the active receiver forwards.
                        receiveParam.abortMethod()
                        if (activeReceiverByAction[eventAction] !== receiveParam.thisObject) {
                            return@hookBefore
                        }
                        // MEDIA_BUTTON and KEY_EVENT may both deliver the same physical press.
                        if (!claimKeyEvent(keyEvent)) {
                            logDebug(tagName, "drop duplicate keyCode:$keyCode action:${keyEvent.action} from $eventAction")
                            return@hookBefore
                        }

                        logDebug(tagName, "BroadcastReceiver onReceive $clazzName, action:$eventAction, keyCode:$keyCode")
                        val longPress = longPressByKeyCode.computeIfAbsent(keyCode) { AtomicBoolean(false) }
                        if (keyEvent.action != KeyEvent.ACTION_DOWN) {
                            if (longPress.get()) {
                                longPress.set(false)
                                return@hookBefore
                            }
                            logDebug(tagName, "send click $keyCode")
                            (receiveParam.args[0] as Context).sendBroadcast(Intent().apply {
                                action = AABroadcastConst.ACTION_STEERING_WHEEL_CONTROL
                                putExtra(AABroadcastConst.EXTRA_ACTION, keyCode)
                            })
                        } else if (keyEvent.isLongPress) {
                            longPress.set(true)
                            logDebug(tagName, "send long click $keyCode")
                            (receiveParam.args[0] as Context).sendBroadcast(Intent().apply {
                                action = AABroadcastConst.ACTION_STEERING_WHEEL_CONTROL
                                putExtra(AABroadcastConst.EXTRA_ACTION, keyCode)
                                putExtra(AABroadcastConst.EXTRA_TYPE, 1)
                            })
                        } else {
                            if (longPress.get()) {
                                return@hookBefore
                            }
                            keyEvent.startTracking()
                        }
                    } catch (e: Throwable) {
                        log(tagName, "onReceive [$clazz] throwable", e)
                    }
                }
            } catch (e: Throwable) {
                log(tagName, "btnEvent registerReceiver onReceive $clazzName", e)
            }
        }
    }
}
