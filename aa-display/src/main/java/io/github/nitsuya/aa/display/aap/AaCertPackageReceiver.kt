package io.github.nitsuya.aa.display.aap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.nitsuya.aa.display.aap.cert.PhoneCertStore

/**
 * When Android Auto is installed or updated, refresh the on-disk CarService identity.
 *
 * Does not start the AAP FGS — only [PhoneCertStore.ensureExtracted]. Channel arm stays with
 * the system-side [io.github.nitsuya.aa.display.xposed.carplay.CarPlayBootArm] /
 * status UI (app-side arm receivers were removed — see docs/CARPLAY_CONNECT.md). Uninstall is ignored so a previously extracted leaf remains usable.
 */
class AaCertPackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) {
            return
        }
        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg != PhoneCertStore.GEARHEAD_PACKAGE) return

        val app = context.applicationContext
        val pending = goAsync()
        Thread(
            {
                try {
                    val st = PhoneCertStore.ensureExtracted(app, force = false)
                    Log.i(
                        AapConsts.TAG,
                        "AaCertPackage action=$action → extract " +
                            "hasCarService=${st.hasCarService} ver=${st.gearheadVersionCode} " +
                            "err=${st.lastError ?: ""}",
                    )
                } catch (e: Throwable) {
                    Log.e(AapConsts.TAG, "AaCertPackage extract failed", e)
                } finally {
                    pending.finish()
                }
            },
            "aap-cert-pkg",
        ).apply {
            isDaemon = true
            start()
        }
    }
}
