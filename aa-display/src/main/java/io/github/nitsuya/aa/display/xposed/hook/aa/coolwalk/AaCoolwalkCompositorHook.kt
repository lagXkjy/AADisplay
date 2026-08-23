package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.loadClass
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object AaCoolwalkCompositorHook {

    private val railVdByDisplayId = ConcurrentHashMap<Int, WeakReference<VirtualDisplay>>()

    fun install(env: CoolwalkHookEnv) {
        hookVirtualDisplaySizing(env)
        env.mFacetEnsureHandler.post { starveSurvivingFacetBarVds(env, "install") }
    }

    /**
     * Soft reconnect may reuse an 80px GhFacetBar VD without create/resize — starve any
     * surviving compositor strip we already hold a [VirtualDisplay] for.
     */
    fun starveSurvivingFacetBarVds(env: CoolwalkHookEnv, reason: String) {
        val dm = runCatching {
            InitFields.appContext.getSystemService(DisplayManager::class.java)
        }.getOrNull() ?: return
        var starved = 0
        for (display in dm.displays) {
            if (!AaCoolwalkHuTouchHook.isTrustedRailDisplayId(display.displayId)) continue
            if (!CoolwalkRailMath.isRailVirtualDisplayName(display.name)) continue
            val width = runCatching { display.mode.physicalWidth }.getOrDefault(0)
            val height = runCatching { display.mode.physicalHeight }.getOrDefault(0)
            if (width <= 1 || height <= 0) continue
            val vd = railVdByDisplayId[display.displayId]?.get()
            if (vd == null) {
                logDebug(
                    CoolwalkHookEnv.TAG,
                    "AaUiHook: surviving FacetBar VD id=${display.displayId} name=${display.name} " +
                        "${width}x$height no VirtualDisplay ref [$reason]",
                )
                continue
            }
            val densityDpi = runCatching {
                val metrics = android.util.DisplayMetrics()
                display.getRealMetrics(metrics)
                metrics.densityDpi
            }.getOrDefault(160)
            runCatching { vd.resize(1, height, densityDpi) }
                .onSuccess {
                    starved++
                    logDebug(
                        CoolwalkHookEnv.TAG,
                        "AaUiHook: starve surviving FacetBar VD id=${display.displayId} " +
                            "name=${display.name} ${width}x$height → 1x$height [$reason]",
                    )
                    env.mFacetEnsureHandler.post {
                        val actions = CoolwalkRailCoordinator.onEvent(
                            RailEvent.GutterReclaim("starve-surviving"),
                        ).second
                        CoolwalkRailCoordinator.dispatchActions(actions)
                    }
                }
                .onFailure { e ->
                    log(CoolwalkHookEnv.TAG, "AaUiHook: starve surviving FacetBar VD failed [$reason]", e)
                }
        }
        if (starved == 0) return
    }

    private fun hookVirtualDisplaySizing(env: CoolwalkHookEnv) {
        try {
            var hooked = 0
            for (method in DisplayManager::class.java.declaredMethods) {
                if (method.name != "createVirtualDisplay") continue
                val params = method.parameterTypes
                if (params.size < 5) continue
                if (params[0] != String::class.java) continue
                if (params[1] != Int::class.javaPrimitiveType) continue
                if (params[2] != Int::class.javaPrimitiveType) continue
                method.isAccessible = true
                method.hookBefore { param ->
                    applyRewrite(env, param, nameIndex = 0, widthIndex = 1, heightIndex = 2)
                }
                method.                hookAfter { param ->
                    val name = param.args[0] as? String
                    rememberRailVirtualDisplay(name, param.result as? VirtualDisplay)
                }
                hooked++
            }
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: hooked DisplayManager.createVirtualDisplay overloads=$hooked")
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: hook DisplayManager.createVirtualDisplay failed", e)
        }

        try {
            val builderClass = loadClass("android.hardware.display.VirtualDisplayConfig\$Builder")
            var hookedBuilder = 0
            for (ctor in builderClass.declaredConstructors) {
                val p = ctor.parameterTypes
                if (p.size < 4) continue
                if (p[0] != String::class.java) continue
                if (p[1] != Int::class.javaPrimitiveType) continue
                if (p[2] != Int::class.javaPrimitiveType) continue
                ctor.isAccessible = true
                ctor.hookBefore { param ->
                    applyRewrite(env, param, nameIndex = 0, widthIndex = 1, heightIndex = 2)
                }
                hookedBuilder++
            }
            try {
                findMethod(builderClass) {
                    name == "setSize" && parameterCount == 2 &&
                        parameterTypes[0] == Int::class.javaPrimitiveType &&
                        parameterTypes[1] == Int::class.javaPrimitiveType
                }.hookBefore { param ->
                    val name = readVirtualDisplayBuilderName(param.thisObject)
                    applyRewrite(
                        env,
                        param,
                        name = name,
                        widthIndex = 0,
                        heightIndex = 1,
                    )
                }
                hookedBuilder++
            } catch (_: Throwable) {
            }
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: hooked VirtualDisplayConfig.Builder paths=$hookedBuilder")
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: hook VirtualDisplayConfig.Builder failed", e)
        }

        try {
            findMethod(VirtualDisplay::class.java) {
                name == "resize" && parameterCount == 3 &&
                    parameterTypes[0] == Int::class.javaPrimitiveType &&
                    parameterTypes[1] == Int::class.javaPrimitiveType
            }.hookBefore { param ->
                val vd = param.thisObject as VirtualDisplay
                val name = runCatching { vd.display?.name }.getOrNull()
                applyRewrite(env, param, name = name, widthIndex = 0, heightIndex = 1)
                rememberRailVirtualDisplay(name, vd)
            }
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: hook VirtualDisplay.resize failed", e)
        }
    }

    private fun applyRewrite(
        env: CoolwalkHookEnv,
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam,
        name: String? = param.args.getOrNull(0) as? String,
        nameIndex: Int = -1,
        widthIndex: Int,
        heightIndex: Int,
    ) {
        val resolvedName = name ?: if (nameIndex >= 0) param.args[nameIndex] as? String else null
        val width = param.args[widthIndex] as? Int ?: return
        val height = param.args[heightIndex] as? Int ?: return
        val result = CoolwalkCompositorPolicy.rewriteVirtualDisplayArgs(
            name = resolvedName,
            width = width,
            height = height,
            layoutWidthPx = env.layoutWidthPx(),
            layoutHeightPx = env.layoutHeightPx(),
            observedRailWidthPx = CoolwalkRailCoordinator.observedRailWidthPx(),
        )
        CoolwalkRailCoordinator.dispatchActions(result.actions)
        val rewrite = result.rewrite ?: return
        if (resolvedName?.equals("Dashboard", ignoreCase = true) == true && !env.mLoggedDashboardStarve) {
            env.mLoggedDashboardStarve = true
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: starve Dashboard VD ${width}x$height → 1x1")
        }
        if (CoolwalkRailMath.isRailVirtualDisplayName(resolvedName) && width > 1) {
            logDebug(
                CoolwalkHookEnv.TAG,
                "AaUiHook: starve FacetBar VD name=$resolvedName ${width}x$height → 1x$height",
            )
            env.mFacetEnsureHandler.post {
                val actions = CoolwalkRailCoordinator.onEvent(
                    RailEvent.GutterReclaim("starve-facet"),
                ).second
                CoolwalkRailCoordinator.dispatchActions(actions)
            }
        }
        if (!CoolwalkRailMath.isRailVirtualDisplayName(resolvedName) &&
            CoolwalkRailMath.isThinRailSize(width, height, env.layoutWidthPx()) && width > 1
        ) {
            log(
                CoolwalkHookEnv.TAG,
                "AaUiHook: shrink rail VD name=$resolvedName ${width}x$height → 1x$height (thin-geometry)",
            )
        }
        val fullW = env.layoutWidthPx()
        val fullH = env.layoutHeightPx()
        if (fullW > 0 && fullH > 0 && abs(height - fullH) <= 2 && width < fullW &&
            rewrite.width == fullW
        ) {
            log(
                CoolwalkHookEnv.TAG,
                "AaUiHook: expand content VD name=$resolvedName ${width}x$height → ${fullW}x$height " +
                    "(layout=${env.mLayoutWidthDp}x${env.mLayoutHeightDp}dp missing=${fullW - width})",
            )
        }
        CoolwalkRailCoordinator.rememberFullHuSize(width, height)
        if (rewrite.width != width) param.args[widthIndex] = rewrite.width
        if (rewrite.height != height) param.args[heightIndex] = rewrite.height
    }

    private fun rememberRailVirtualDisplay(name: String?, vd: VirtualDisplay?) {
        if (vd == null || !CoolwalkRailMath.isRailVirtualDisplayName(name)) return
        val display = vd.display ?: return
        if (!AaCoolwalkHuTouchHook.isTrustedRailDisplayId(display.displayId)) return
        railVdByDisplayId[display.displayId] = WeakReference(vd)
        val width = display.mode.physicalWidth
        val actions = mutableListOf<RailAction>()
        if (width > 1) {
            actions += CoolwalkRailCoordinator.onEvent(
                RailEvent.RailWidthObserved(width, "vd-observe:$name"),
            ).second
        }
        actions += CoolwalkRailCoordinator.onEvent(
            RailEvent.FacetDisplayId(display.displayId, "vd-observe:$name"),
        ).second
        CoolwalkRailCoordinator.dispatchActions(actions)
        logDebug(
            CoolwalkHookEnv.TAG,
            "AaUiHook: observe rail VD id=${display.displayId} name=$name w=$width",
        )
    }

    private fun readVirtualDisplayBuilderName(builder: Any): String? {
        return runCatching {
            builder.javaClass.methods.firstOrNull { m ->
                m.name == "getName" && m.parameterCount == 0
            }?.invoke(builder) as? String
        }.getOrNull() ?: runCatching {
            builder.javaClass.declaredFields.firstOrNull { f ->
                f.type == String::class.java
            }?.apply { isAccessible = true }?.get(builder) as? String
        }.getOrNull()
    }
}
