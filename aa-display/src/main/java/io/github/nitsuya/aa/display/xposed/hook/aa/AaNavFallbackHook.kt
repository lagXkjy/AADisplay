package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.github.kyuubiran.ezxhelper.init.InitFields
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.util.log

/**
 * Without Maps, AA would show [NAV_FALLBACK_CLASS] and Coolwalk can crash.
 * Disable the component once; no Intent/bind insurance.
 */
object AaNavFallbackHook : AaHook() {
    override val tagName: String = "AAD_AaNavFallbackHook"

    private const val NAV_FALLBACK_CLASS =
        "com.google.android.apps.auto.components.system.navigation.fallback.NavigationFallbackCarActivityService"

    override fun isSupportProcess(processName: String): Boolean {
        return processProjection == processName || processCar == processName
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val ctx: Context = InitFields.appContext
            val cn = ComponentName(ctx.packageName, NAV_FALLBACK_CLASS)
            val pm = ctx.packageManager
            val state = pm.getComponentEnabledSetting(cn)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
            ) {
                return
            }
            pm.setComponentEnabledSetting(
                cn,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            log(tagName, "disabled $NAV_FALLBACK_CLASS")
        }.onFailure { e ->
            log(tagName, "disable $NAV_FALLBACK_CLASS failed", e)
        }
    }
}
