package io.github.nitsuya.aa.display.xposed.hook.aa

import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method

/**
 * Without Maps, AA would show [NAV_FALLBACK_CLASS] and Coolwalk can crash.
 * Root fix: disable the component. Intent / bind / ProjectionContext guards
 * if disable fails or is re-enabled.
 */
object AaNavFallbackHook : AaHook() {
    override val tagName: String = "AAD_AaNavFallbackHook"

    private const val NAV_FALLBACK_CLASS =
        "com.google.android.apps.auto.components.system.navigation.fallback.NavigationFallbackCarActivityService"

    private lateinit var projectionStartMethods: List<Method>

    override fun isSupportProcess(processName: String): Boolean {
        return processProjection == processName || processCar == processName
    }

    override fun loadDexClass(bridge: DexKitBridge, lpparam: XC_LoadPackage.LoadPackageParam) {
        projectionStartMethods = AaCarAppLaunchGuard.findProjectionStartMethods(
            bridge,
            lpparam.classLoader,
        )
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        AaCarAppLaunchGuard.disableComponent(tagName, NAV_FALLBACK_CLASS)
        AaCarAppLaunchGuard.installLaunchBlocks(NAV_FALLBACK_CLASS, projectionStartMethods)
    }
}
