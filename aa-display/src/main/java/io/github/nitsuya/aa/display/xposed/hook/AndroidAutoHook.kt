package io.github.nitsuya.aa.display.xposed.hook

import android.app.Application
import android.app.Instrumentation
import android.content.pm.PackageManager
import com.github.kyuubiran.ezxhelper.init.InitFields
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
            try {
                EzXHelperInit.initAppContext()
            } catch (e: Throwable) {
                log(tagName, "$processName context initialization failed", e)
                return@hookBefore
            }
            runCatching {
                val info = InitFields.appContext.packageManager.getPackageInfo(
                    lpparam.packageName,
                    PackageManager.PackageInfoFlags.of(0L),
                )
                log(tagName, "$processName AA=${info.versionName} (${info.longVersionCode})")
            }.onFailure { log(tagName, "$processName version lookup failed", it) }

            val dexHooks = setOf(AaSignatureHook, AaUiHook, AaFrxRequiredAppsHook)
            val resolved = hooks.filter { it !in dexHooks }.toMutableSet()
            val elapsed = measureTimeMillis {
                try {
                    System.loadLibrary("dexkit")
                    DexKitBridge.create(lpparam.appInfo.sourceDir).use { bridge ->
                        checkNotNull(bridge) { "DexKitBridge.create() failed" }
                        hooks.filter { it in dexHooks }.forEach { hook ->
                            try {
                                hook.loadDexClass(bridge, lpparam)
                                resolved.add(hook)
                                log(tagName, "$processName ${hook.tagName}: resolved")
                            } catch (e: Throwable) {
                                log(tagName, "$processName ${hook.tagName}: resolution failed; skipped", e)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    log(tagName, "$processName DexKit initialization/close failed", e)
                }
            }
            log(tagName, "$processName resolution completed in ${elapsed}ms")
            val installed = mutableSetOf<AaHook>()
            hooks.forEach { hook ->
                if (hook !in resolved) return@forEach
                if (hook == AaMediaPlaceholderHook && AaUiHook !in installed) {
                    log(tagName, "$processName ${hook.tagName}: skipped; UI hook unavailable")
                    return@forEach
                }
                try {
                    hook.hook(lpparam)
                    installed.add(hook)
                    log(tagName, "$processName ${hook.tagName}: installation returned")
                } catch (e: Throwable) {
                    log(tagName, "$processName ${hook.tagName}: installation failed", e)
                }
            }
            if (AaSignatureHook in hooks && AaSignatureHook !in installed) {
                log(tagName, "$processName projection entry unavailable: signature hook not installed")
            }
            log(tagName, "$processName installation completed ${installed.size}/${hooks.size}; check individual hook logs")
        }
    }
}
