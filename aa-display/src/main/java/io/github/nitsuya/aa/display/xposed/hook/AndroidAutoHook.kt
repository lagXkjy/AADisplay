package io.github.nitsuya.aa.display.xposed.hook

import android.app.Application
import android.app.Instrumentation
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.hook.aa.AaBtnEventHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaClusterLyricEgressHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaFrxRequiredAppsHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaMediaPlaceholderHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaNavFallbackHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaSignatureHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaUiHook
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import org.luckypray.dexkit.DexKitBridge
import kotlin.system.measureTimeMillis


abstract class AaHook {
    companion object {
        const val processProjection = "com.google.android.projection.gearhead:projection"
        const val processCar =        "com.google.android.projection.gearhead:car"
    }
    abstract val tagName: String
    /** True when [loadDexClass] runs DexKit queries (eligible for [DexKitMethodCache]). */
    open val usesDexKit: Boolean = false
    abstract fun isSupportProcess(processName: String) : Boolean
    open fun loadDexClass(bridge: DexKitBridge, lpparam: XC_LoadPackage.LoadPackageParam) {}
    /**
     * Apply previously cached DexKit coordinates. Return true only when all required
     * targets resolved; false triggers a live DexKit scan for this hook.
     */
    open fun applyCache(
        cache: DexKitMethodCache.Session,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ): Boolean = false
    open fun saveCache(
        cache: DexKitMethodCache.Session,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ) {}
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
            AaClusterLyricEgressHook,
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
            val cache = DexKitMethodCache.open(lpparam, InitFields.appContext)
            val ready = mutableListOf<AaHook>()
            val needScan = mutableListOf<AaHook>()

            for (h in hooks) {
                if (!h.usesDexKit) {
                    ready += h
                    continue
                }
                val fromCache = cache.isValid && runCatching {
                    h.applyCache(cache, lpparam)
                }.onFailure { e ->
                    log(tagName, "${h.tagName} applyCache failed", e)
                }.getOrDefault(false)
                if (fromCache) {
                    logDebug(tagName, "${h.tagName} dexkit cache hit")
                    ready += h
                } else {
                    needScan += h
                }
            }

            if (needScan.isNotEmpty()) {
                System.loadLibrary("dexkit")
                val measureTimeMillis = measureTimeMillis {
                    DexKitBridge.create(lpparam.appInfo.sourceDir).use { bridge ->
                        needScan.forEach { h ->
                            runCatching { h.loadDexClass(bridge, lpparam) }
                                .onSuccess {
                                    ready += h
                                    runCatching { h.saveCache(cache, lpparam) }
                                        .onFailure { e ->
                                            log(tagName, "${h.tagName} saveCache failed", e)
                                        }
                                }
                                .onFailure { e ->
                                    log(tagName, "${h.tagName} loadDexClass failed", e)
                                }
                        }
                    }
                }
                cache.commit()
                logDebug(
                    tagName,
                    "${lpparam.processName} load class measure ${measureTimeMillis}ms " +
                        "(scanned=${needScan.size} cached=${hooks.count { it.usesDexKit } - needScan.size})",
                )
            } else {
                logDebug(
                    tagName,
                    "${lpparam.processName} dexkit cache hit all " +
                        "(${hooks.count { it.usesDexKit }} hooks, skipped scan)",
                )
            }

            ready.forEach { h ->
                runCatching { h.hook(lpparam) }
                    .onFailure { e -> log(tagName, "${h.tagName} hook failed", e) }
            }
        }
    }
}
