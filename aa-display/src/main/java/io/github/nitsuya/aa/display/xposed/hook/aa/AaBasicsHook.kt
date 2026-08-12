package io.github.nitsuya.aa.display.xposed.hook.aa


import android.content.pm.InstallSourceInfo

import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.log


object AaBasicsHook : AaHook() {
    override val tagName: String = "AAD_AaBasicsHook"

    override fun isSupportProcess(processName: String): Boolean {
        return true
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            findMethod(InstallSourceInfo::class.java) {
                name == "getInitiatingPackageName"
            }.hookAfter { param ->
                param.result = "com.android.vending"
            }
        } catch (e: Throwable) {
            log(tagName, "InstallSourceInfo.getInitiatingPackageName", e)
        }
    }
}
