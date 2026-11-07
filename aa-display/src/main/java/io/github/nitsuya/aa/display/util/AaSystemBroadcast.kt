package io.github.nitsuya.aa.display.util

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.UserHandle
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.xposed.util.logDebug

/**
 * system_server → app broadcasts.
 *
 * Android 14+ dumps a stack for every non-protected action from uid 1000
 * ([BroadcastController.checkBroadcastFromSystem]). Pair with the AndroidHook
 * whitelist, and always send as a user + package target so ContextImpl
 * does not warn about unqualified system sends.
 */
object AaSystemBroadcast {
    const val ACTION_PREFIX = "aa.display.action."

    /** Module process only (e.g. [AABroadcastConst.ACTION_IME_VISIBILITY]). */
    fun toAaDisplay(context: Context, intent: Intent) {
        send(context, intent, BuildConfig.APPLICATION_ID)
    }

    fun send(context: Context, intent: Intent, vararg packages: String) {
        val identity = Binder.clearCallingIdentity()
        try {
            val targets = packages.filter { it.isNotEmpty() }
            if (targets.isEmpty()) {
                sendAsUser(context, intent)
            } else {
                for (pkg in targets) {
                    sendAsUser(context, Intent(intent).setPackage(pkg))
                }
            }
        } catch (e: Throwable) {
            logDebug("AaSystemBroadcast", "send failed action=${intent.action}: ${e.message}")
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun sendAsUser(context: Context, intent: Intent) {
        try {
            val user = android.os.Process.myUserHandle()
            Context::class.java
                .getMethod("sendBroadcastAsUser", Intent::class.java, UserHandle::class.java)
                .invoke(context, intent, user)
        } catch (_: Throwable) {
            context.sendBroadcast(intent)
        }
    }
}
