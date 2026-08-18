package io.github.nitsuya.aa.display.xposed.hook

import android.app.Application
import android.app.Instrumentation
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.hook.aa.AaBtnEventHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaFrxRequiredAppsHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaMediaPlaceholderHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaNavFallbackHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaSignatureHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaUiHook
import io.github.nitsuya.aa.display.xposed.util.log
import org.luckypray.dexkit.DexKitBridge
import kotlin.system.measureTimeMillis


abstract class AaHook {
    companion object {
        const val processProjection = "com.google.android.projection.gearhead:projection"
        const val processCar =        "com.google.android.projection.gearhead:car"
    }
    abstract val tagName: String
    abstract fun isSupportProcess(processName: String) : Boolean
    open fun loadDexClass(bridge: DexKitBridge, lpparam: XC_LoadPackage.LoadPackageParam) {}
    abstract fun hook(lpparam: XC_LoadPackage.LoadPackageParam)
}

object AndroidAutoHook : BaseHook() {
    override val tagName: String = "AAD_AndroidAutoHook"
    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val processName = lpparam.processName
        val hooks = listOf(
            AaSignatureHook,
            AaBtnEventHook,
            AaUiHook,
            AaFrxRequiredAppsHook,
            AaNavFallbackHook,
            AaMediaPlaceholderHook,
        ).filter { i -> i.isSupportProcess(processName) }
        if(hooks.isEmpty()) return

        var onCreateApplication: XC_MethodHook.Unhook? = null
        onCreateApplication = findMethod(Instrumentation::class.java) {
            name == "callApplicationOnCreate"
            && parameterCount == 1
            && parameterTypes[0] == Application::class.java
        }.hookBefore {
            onCreateApplication?.unhook()
            EzXHelperInit.initAppContext()
            System.loadLibrary("dexkit")
            val ready = mutableListOf<AaHook>()
            DexKitBridge.create(lpparam.appInfo.sourceDir).use { bridge ->
                val measureTimeMillis = measureTimeMillis {
                    hooks.forEach { h ->
                        runCatching { h.loadDexClass(bridge, lpparam) }
                            .onSuccess { ready += h }
                            .onFailure { e -> log(tagName, "${h.tagName} loadDexClass failed", e) }
                    }
                }
                log(tagName,"${lpparam.processName} load class measure ${measureTimeMillis}ms")
            }
            ready.forEach { h ->
                runCatching { h.hook(lpparam) }
                    .onFailure { e -> log(tagName, "${h.tagName} hook failed", e) }
            }
        }
    }
}
