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
 * whitelist, and always send as a user + optional package targets so ContextImpl
 * does not warn about unqualified system sends.
 */
object AaSystemBroadcast {
    const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"
    const val ACTION_PREFIX = "aa.display.action."

    /** AA UI process only (most system → shell signals). */
    fun toAaDisplay(context: Context, intent: Intent) {
        send(context, intent, BuildConfig.APPLICATION_ID)
    }

    /** AA UI + gearhead (e.g. [AABroadcastConst.ACTION_SPLIT_STATE_CHANGED] for Coolwalk). */
    fun toAaDisplayAndGearhead(context: Context, intent: Intent) {
        send(context, intent, BuildConfig.APPLICATION_ID, GEARHEAD_PACKAGE)
    }

    fun send(context: Context, intent: Intent, vararg packages: String) {
        val identity = Binder.clearCallingIdentity()
        try {
            // Same delivery as before (registered + ordered/manifest if any); only add a
            // user-qualified send + optional package targets. Do not use
            // FLAG_RECEIVER_REGISTERED_ONLY — that would drop late registrants.
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
            // Prefer sendBroadcastAsUser so ContextImpl does not warn about
            // unqualified sends from system_server. Resolve UserHandle via
            // Process (public) — UserHandle.of/ALL are @SystemApi / stub-stripped.
            val user = android.os.Process.myUserHandle()
            Context::class.java
                .getMethod("sendBroadcastAsUser", Intent::class.java, UserHandle::class.java)
                .invoke(context, intent, user)
        } catch (_: Throwable) {
            context.sendBroadcast(intent)
        }
    }
}
