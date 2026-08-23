package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import com.github.kyuubiran.ezxhelper.utils.hookBefore
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.lang.reflect.Method

/**
 * Clamp Coolwalk facet-bar surface width before the compositor records a ~107px slot.
 * [GhFacetBar.onWindowSurfaceAvailable] in :projection can run before :car starves the VD.
 */
object CoolwalkFacetBarSurfaceHook {

    fun install(env: CoolwalkHookEnv) {
        var hooked = 0
        for (method in env.facetBarSurfaceMethods) {
            if (hookMethod(env, method)) hooked++
        }
        if (hooked > 0) {
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: hooked facet-bar surface methods=$hooked")
        } else {
            log(CoolwalkHookEnv.TAG, "AaUiHook: no facet-bar surface methods to hook")
        }
    }

    private fun hookMethod(env: CoolwalkHookEnv, method: Method): Boolean {
        return runCatching {
            method.hookBefore { param -> clampSurfaceArgs(env, method, param.args) }
            true
        }.getOrElse { e ->
            log(CoolwalkHookEnv.TAG, "AaUiHook: hook facet surface ${method.declaringClass.name}#${method.name} failed", e)
            false
        }
    }

    internal fun clampSurfaceArgs(env: CoolwalkHookEnv, method: Method, args: Array<Any?>) {
        val fullW = env.layoutWidthPx().takeIf { it > 0 }
            ?: CoolwalkRailCoordinator.current().fullHuWidthPx
        if (fullW <= 0) return
        val facetNamed = args.any { arg ->
            arg is String && CoolwalkRailMath.isRailVirtualDisplayName(arg)
        }
        val ints = args.mapIndexedNotNull { index, arg ->
            (arg as? Int)?.let { index to it }
        }
        if (ints.isEmpty()) return
        var height = ints.maxOfOrNull { it.second }?.takeIf { it > 0 }
            ?: env.layoutHeightPx().takeIf { it > 0 }
            ?: CoolwalkRailCoordinator.current().layoutHeightPx
        for ((index, value) in ints) {
            if (value <= 1) continue
            if (!CoolwalkRailMath.isThinRailSize(value, height.coerceAtLeast(value), fullW)) continue
            if (!facetNamed && ints.size >= 2) {
                val other = ints.firstOrNull { it.first != index }?.second ?: continue
                if (other > value && other < value * 2) continue
            }
            args[index] = 1
            logDebug(
                CoolwalkHookEnv.TAG,
                "AaUiHook: clamp facet surface ${method.declaringClass.simpleName}#${method.name} " +
                    "$value→1 full=$fullW",
            )
            env.mFacetEnsureHandler.post {
                val actions = CoolwalkRailCoordinator.onEvent(
                    RailEvent.GutterReclaim("surface-clamp"),
                ).second
                CoolwalkRailCoordinator.dispatchActions(actions)
                CoolwalkFacetChrome.reclaimAllWindowGutters("surface-clamp")
            }
            return
        }
    }
}
