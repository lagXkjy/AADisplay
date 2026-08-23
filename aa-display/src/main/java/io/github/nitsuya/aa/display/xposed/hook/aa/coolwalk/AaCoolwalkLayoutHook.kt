package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.content.res.Resources
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.loadClass
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.lang.reflect.Modifier

object AaCoolwalkLayoutHook {

    fun install(env: CoolwalkHookEnv) {
        installRailWidthDimens(env)
        hookLayoutInfo(env)
        hookRadius()
    }

    fun installRailWidthDimens(env: CoolwalkHookEnv) {
        hookRailWidthDimens(env)
    }

    private fun hookLayoutInfo(env: CoolwalkHookEnv) {
        var hooked = 0
        for (ctor in env.layoutInfoConstructors) {
            try {
                ctor.hookBefore { param -> forceVerticalRailOnLayoutInfoArgs(env, param.args) }
                ctor.hookAfter { param ->
                    val instance = param.thisObject ?: return@hookAfter
                    forceVerticalRailOnLayoutInfoInstance(env, instance)
                    val w = env.mLayoutWidthDp
                    val h = env.mLayoutHeightDp
                    if (w > 0 && h > 0) {
                        val actions = CoolwalkRailCoordinator.onEvent(
                            RailEvent.LayoutInfo(w, h, "layoutInfo"),
                        ).second
                        CoolwalkRailCoordinator.dispatchActions(actions)
                    }
                    AaCoolwalkAutoOpenHook.scheduleAutoOpenIfNeeded(env, "layoutInfo")
                    if (env.canHookFacetBar) {
                        CoolwalkFacetChrome.scheduleEnsureFacetBar(env, "layoutInfo")
                    }
                }
                hooked++
            } catch (e: Throwable) {
                log(CoolwalkHookEnv.TAG, "AaUiHook: hook LayoutInfo ctor p${ctor.parameterCount} failed", e)
            }
        }
        log(
            CoolwalkHookEnv.TAG,
            "AaUiHook: LayoutInfo vertical-rail force hooked=$hooked/${env.layoutInfoConstructors.size} " +
                "lhd=${env.resLayoutLeftResourceId}",
        )
    }

    private fun isAuxiliaryLayoutType(args: Array<Any?>): Boolean {
        val code = layoutTypeCode(args.getOrNull(3) ?: return false) ?: return false
        return code in setOf(7, 8, 9)
    }

    private fun forceVerticalRailOnLayoutInfoArgs(env: CoolwalkHookEnv, args: Array<Any?>) {
        if (args.size < 6) return
        if (isAuxiliaryLayoutType(args)) return
        (args[1] as? Int)?.takeIf { it > 0 }?.let { env.mLayoutWidthDp = it }
        (args[2] as? Int)?.takeIf { it > 0 }?.let { env.mLayoutHeightDp = it }

        val beforeLayoutId = args[0] as? Int
        val beforeType = args[3]
        val beforeRail = args[5] as? Boolean

        if (env.resLayoutLeftResourceId != 0) {
            args[0] = env.resLayoutLeftResourceId
            setLayoutTypeArg(args, 2)
        }
        if (args[5] is Boolean) {
            args[5] = true
        }
        widenLayoutInfoToFullHu(env, args)

        if (beforeRail != true ||
            (env.resLayoutLeftResourceId != 0 && beforeLayoutId != env.resLayoutLeftResourceId)
        ) {
            logDebug(
                CoolwalkHookEnv.TAG,
                "AaUiHook: force vertical rail args " +
                    "layoutId=$beforeLayoutId→${args[0]} type=$beforeType→${args[3]} " +
                    "hasVerticalRail=$beforeRail→${args[5]} size=${env.mLayoutWidthDp}x${env.mLayoutHeightDp}",
            )
        }
    }

    private fun forceVerticalRailOnLayoutInfoInstance(env: CoolwalkHookEnv, instance: Any) {
        try {
            resolveLayoutInfoFields(env, instance.javaClass)
            val railField = env.hasVerticalRailField
            if (railField != null && !railField.getBoolean(instance)) {
                railField.setBoolean(instance, true)
                logDebug(CoolwalkHookEnv.TAG, "AaUiHook: force vertical rail field ${railField.name}=true")
            }
            val layoutField = env.layoutResourceIdField
            if (env.resLayoutLeftResourceId != 0 && layoutField != null) {
                val cur = layoutField.getInt(instance)
                if (cur != env.resLayoutLeftResourceId) {
                    layoutField.setInt(instance, env.resLayoutLeftResourceId)
                    logDebug(
                        CoolwalkHookEnv.TAG,
                        "AaUiHook: force vertical rail layoutId field ${layoutField.name} $cur→${env.resLayoutLeftResourceId}",
                    )
                }
            }
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: force vertical rail on instance failed", e)
        }
    }

    /**
     * After forcing vertical rail, widen the canvas to this-connection full HU once
     * content_bounds reclaim zeroed the compositor slot. hasVerticalRail stays true.
     */
    private fun widenLayoutInfoToFullHu(env: CoolwalkHookEnv, args: Array<Any?>) {
        val w = args[1] as? Int ?: return
        if (w <= 0) return
        CoolwalkRailCoordinator.maybeBeginReconnectIfNeeded("layoutInfo:pre-widen")
        val target = CoolwalkRailMath.targetPresentationWidthPx(CoolwalkRailCoordinator.current(), w)
        if (target <= w) return
        args[1] = target
        env.mLayoutWidthDp = target
        logDebug(
            CoolwalkHookEnv.TAG,
            "AaUiHook: widen LayoutInfo canvas ${w}→$target (hasVerticalRail kept)",
        )
    }

    private fun resolveLayoutInfoFields(env: CoolwalkHookEnv, clazz: Class<*>) {
        if (env.hasVerticalRailField != null && env.layoutResourceIdField != null) return
        val namedRail = clazz.declaredFields.firstOrNull { f ->
            !Modifier.isStatic(f.modifiers) &&
                f.type == Boolean::class.javaPrimitiveType &&
                f.name.contains("vertical", ignoreCase = true)
        }
        val bools = clazz.declaredFields.filter { f ->
            !Modifier.isStatic(f.modifiers) &&
                f.type == Boolean::class.javaPrimitiveType
        }
        val rail = namedRail ?: bools.getOrNull(1)
        if (rail != null) {
            rail.isAccessible = true
            env.hasVerticalRailField = rail
        }
        val namedLayout = clazz.declaredFields.firstOrNull { f ->
            !Modifier.isStatic(f.modifiers) &&
                f.type == Int::class.javaPrimitiveType &&
                (f.name.contains("layoutResource", ignoreCase = true) ||
                    f.name.contains("layoutRes", ignoreCase = true))
        }
        val ints = clazz.declaredFields.filter { f ->
            !Modifier.isStatic(f.modifiers) &&
                f.type == Int::class.javaPrimitiveType
        }
        val layoutId = namedLayout ?: ints.getOrNull(0)
        if (layoutId != null) {
            layoutId.isAccessible = true
            env.layoutResourceIdField = layoutId
        }
    }

    private fun hookRailWidthDimens(env: CoolwalkHookEnv) {
        if (env.railWidthDimenIds.isEmpty()) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: no rail width dimens resolved; VD expand fallback only")
            return
        }
        fun hookDimenMethod(clazz: Class<*>, methodName: String, zero: Any) {
            try {
                findMethod(clazz) {
                    name == methodName && parameterCount == 1 &&
                        parameterTypes[0] == Int::class.javaPrimitiveType
                }.hookAfter { param ->
                    val id = param.args[0] as? Int ?: return@hookAfter
                    if (env.railWidthDimenIds.contains(id)) {
                        param.result = zero
                        val actions = CoolwalkRailCoordinator.onEvent(
                            RailEvent.RailWidthObserved(0, "dimen-zero:$id"),
                        ).second
                        CoolwalkRailCoordinator.dispatchActions(actions)
                    }
                }
            } catch (e: Throwable) {
                log(CoolwalkHookEnv.TAG, "AaUiHook: hook $clazz.$methodName failed", e)
            }
        }
        hookDimenMethod(Resources::class.java, "getDimensionPixelSize", 0)
        hookDimenMethod(Resources::class.java, "getDimensionPixelOffset", 0)
        hookDimenMethod(Resources::class.java, "getDimension", 0f)
        try {
            val impl = loadClass("android.content.res.ResourcesImpl")
            hookDimenMethod(impl, "getDimensionPixelSize", 0)
            hookDimenMethod(impl, "getDimensionPixelOffset", 0)
            hookDimenMethod(impl, "getDimension", 0f)
        } catch (_: Throwable) {
        }
        logDebug(CoolwalkHookEnv.TAG, "AaUiHook: hooked rail width dimens → 0 (${env.railWidthDimenIds.size} ids)")
    }

    private fun layoutTypeCode(raw: Any): Int? {
        return when (raw) {
            is Int -> raw - 1
            is Enum<*> -> raw.toString().toIntOrNull()
            else -> raw.toString().toIntOrNull()
        }
    }

    private fun setLayoutTypeArg(args: Array<Any?>, targetCode: Int) {
        val raw = args.getOrNull(3) ?: return
        when (raw) {
            is Int -> args[3] = targetCode + 1
            is Enum<*> -> {
                val enumClass = raw.javaClass
                val enumValue = enumClass.enumConstants?.firstOrNull { c ->
                    c?.toString()?.toIntOrNull() == targetCode
                }
                if (enumValue != null) {
                    args[3] = enumValue
                }
            }
        }
    }

    private fun hookRadius() {
        try {
            val targetClass = loadClass("com.google.android.gms.car.ProjectionWindowDecorationParams")
            val ctor = targetClass.declaredConstructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size >= 9 &&
                    p[0] == Int::class.javaPrimitiveType &&
                    p[1] == Int::class.javaPrimitiveType &&
                    p[2] == Int::class.javaPrimitiveType &&
                    p[3] == Int::class.javaPrimitiveType &&
                    p[4] == Int::class.javaPrimitiveType &&
                    p[5] == Int::class.javaPrimitiveType &&
                    p[6] == Int::class.javaPrimitiveType &&
                    p[7] == Boolean::class.javaPrimitiveType &&
                    p[8] == Boolean::class.javaPrimitiveType
            } ?: throw NoSuchMethodException(
                "AaUiHook: ProjectionWindowDecorationParams compatible constructor not found",
            )
            ctor.isAccessible = true
            ctor.hookBefore { param ->
                // Zero horizontal decoration insets (Coolwalk pillar gutters on both sides).
                if (param.args.size > 0 && param.args[0] is Int) param.args[0] = 0
                if (param.args.size > 2 && param.args[2] is Int) param.args[2] = 0
                if (param.args.size > 5 && param.args[5] is Int) param.args[5] = 0
            }
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "ProjectionWindowDecorationParams", e)
        }
    }
}
