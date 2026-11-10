package io.github.nitsuya.aa.display.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook
import io.github.nitsuya.aa.display.xposed.hook.BaseHook
import io.github.nitsuya.aa.display.xposed.util.ModuleInit
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logexIfThrow

class XposedInit : IXposedHookZygoteInit, IXposedHookLoadPackage{
    companion object {
        const val TAG = "AADisplay_XposedInit"
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        ModuleInit.initZygote(startupParam)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        when {
            // system_server: VirtualDisplay, Binder bridge, VD pin / IME / HID.
            packageName == "android" && lpparam.appInfo == null -> arrayOf(AndroidHook)
            // Google gearhead DexKit path deleted — AAP / CarPlay own egress (no AndroidAutoHook).
            else -> null
        }?.also {
            initHooks(lpparam, *it)
        }
    }

    private fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam, vararg hook: BaseHook) {
        ModuleInit.initHandleLoadPackage(lpparam)
        hook.forEach {
            runCatching {
                if (it.isInit) return@forEach
                it.init(lpparam)
                it.isInit = true
                log(TAG, "Inited hook: ${it.tagName}")
            }.logexIfThrow("Failed init hook: ${it.tagName}")
        }
    }
}
