package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.findAllMethods
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import io.github.nitsuya.aa.display.xposed.hook.abortMethod
import io.github.nitsuya.aa.display.xposed.util.log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

/**
 * Shared helpers for disabling / blocking AA Car App Service launches
 * (NavigationFallback, MediaCarApp, etc.).
 */
internal object AaCarAppLaunchGuard {

    @Volatile
    private var cachedProjectionStarts: List<Method>? = null

    @Volatile
    private var cachedClassLoader: ClassLoader? = null

    fun findProjectionStartMethods(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
    ): List<Method> {
        cachedProjectionStarts?.takeIf { cachedClassLoader === classLoader }?.let { return it }

        val methods = bridge.findClass {
            matcher {
                usingStrings {
                    add("GH.ProjectionContext", StringMatchType.Equals, false)
                }
            }
        }.flatMap { classData ->
            classData.getMethods().filter { md ->
                md.returnTypeName == "void" &&
                    md.paramTypeNames.isNotEmpty() &&
                    md.paramTypeNames[0] == "android.content.Intent"
            }
        }.mapNotNull { md ->
            runCatching { md.getMethodInstance(classLoader) }.getOrNull()
        }.distinctBy {
            "${it.declaringClass.name}#${it.name}#${it.parameterTypes.joinToString { p -> p.name }}"
        }

        cachedProjectionStarts = methods
        cachedClassLoader = classLoader
        return methods
    }

    fun disableComponent(tagName: String, className: String) {
        runCatching {
            val ctx: Context = InitFields.appContext
            val cn = ComponentName(ctx.packageName, className)
            val pm = ctx.packageManager
            val state = pm.getComponentEnabledSetting(cn)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
            ) {
                return
            }
            pm.setComponentEnabledSetting(
                cn,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            log(tagName, "disabled $className")
        }.onFailure { e ->
            log(tagName, "disable $className failed", e)
        }
    }

    /**
     * Insurance if [disableComponent] fails or the component is re-enabled:
     * ProjectionContext start, Intent.setComponent, Context bind/start.
     */
    fun installLaunchBlocks(
        className: String,
        projectionStartMethods: List<Method>,
    ) {
        fun matches(cn: ComponentName?) = cn?.className == className

        projectionStartMethods.forEach { method ->
            method.hookBefore { param ->
                val intent = param.args.getOrNull(0) as? Intent ?: return@hookBefore
                if (!matches(intent.component)) return@hookBefore
                intent.component = null
                param.abortMethod()
            }
        }

        findMethod(Intent::class.java) {
            name == "setComponent" &&
                parameterCount == 1 &&
                parameterTypes[0] == ComponentName::class.java
        }.hookBefore { param ->
            val cn = param.args[0] as? ComponentName ?: return@hookBefore
            if (!matches(cn)) return@hookBefore
            param.args[0] = null
        }

        findAllMethods(ContextWrapper::class.java) {
            (name == "bindService" ||
                name == "bindServiceAsUser" ||
                name == "startService" ||
                name == "startForegroundService") &&
                parameterTypes.isNotEmpty() &&
                parameterTypes[0] == Intent::class.java
        }.forEach { method ->
            method.hookBefore { param ->
                val intent = param.args[0] as? Intent ?: return@hookBefore
                if (!matches(intent.component)) return@hookBefore
                param.result = when (method.returnType) {
                    Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> false
                    else -> null
                }
            }
        }
    }
}
