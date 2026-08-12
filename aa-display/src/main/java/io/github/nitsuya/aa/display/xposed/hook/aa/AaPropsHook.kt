package io.github.nitsuya.aa.display.xposed.hook.aa

import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.hook.AaHook

/**
 * Optional phenotype/props overrides. Prefs-driven props lists were removed; hook is a no-op.
 */
object AaPropsHook: AaHook() {
    override val tagName: String = "AAD_AaPropsHook"

    override fun isSupportProcess(processName: String): Boolean {
        return true
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        // No ComGoogleAndroid*Props overrides.
    }
}
