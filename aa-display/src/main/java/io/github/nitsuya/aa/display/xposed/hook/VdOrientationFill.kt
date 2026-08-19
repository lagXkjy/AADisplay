package io.github.nitsuya.aa.display.xposed.hook

import android.content.pm.ActivityInfo
import android.view.Display
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.util.log
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Pane VDs freeze [android.view.Surface.ROTATION_0] so TextureView aspect matches the buffer.
 * Portrait-only apps then get WM `FIXED_ORIENTATION` letterbox on landscape panes
 * (fullscreen 800×480 → centered 288×480).
 *
 * Safety rules:
 * - Hook **config-time** resolvers only (not `getOrientation` / `applyAspectRatio`).
 * - Callbacks never throw into WM (try/catch + cheap [hasAaVirtualDisplays] gate).
 * - Missing OEM methods → skip install, leave stock behavior.
 */
object VdOrientationFill {
    private const val TAG = "AAD_VdOrientationFill"
    private const val ACTIVITY_RECORD = "com.android.server.wm.ActivityRecord"

    private val unhooks = mutableListOf<XC_MethodHook.Unhook>()
    private var hooked = false
    /** True after one install attempt — do not retry (avoids boot loops on OEM churn). */
    private var installAttempted = false

    @Volatile private var displayIdMethod: Method? = null
    @Volatile private var orientationField: Field? = null
    @Volatile private var orientationFieldLookupDone = false

    fun ensureHooked() {
        if (!AndroidHook.isReadyForSystemHooks()) return
        if (installAttempted) return
        installAttempted = true
        installHooks()
    }

    private fun installHooks() {
        val cls = AndroidHook.loadSystemClass(ACTIVITY_RECORD)
        if (cls == null) {
            log(TAG, "$ACTIVITY_RECORD not found")
            return
        }
        runCatching { hookSkipNamed(cls, "resolveFixedOrientationConfiguration") }
            .onFailure { log(TAG, "resolveFixedOrientationConfiguration failed", it) }
        runCatching { hookSkipNamed(cls, "resolveAspectRatioRestriction") }
            .onFailure { log(TAG, "resolveAspectRatioRestriction failed", it) }
        runCatching { hookSetRequestedOrientation(cls) }
            .onFailure { log(TAG, "setRequestedOrientation failed", it) }

        hooked = unhooks.isNotEmpty()
        log(TAG, if (hooked) "hooked count=${unhooks.size}" else "no hooks installed (stock letterbox)")
    }

    private fun hookSkipNamed(cls: Class<*>, name: String) {
        val methods = cls.declaredMethods.filter { m -> m.name == name }
        if (methods.isEmpty()) {
            log(TAG, "$ACTIVITY_RECORD.$name not found")
            return
        }
        for (method in methods) {
            method.isAccessible = true
            unhooks += method.hookBefore { param ->
                try {
                    if (!isAaActivity(param.thisObject)) return@hookBefore
                    neutralizeOrientationField(param.thisObject)
                    param.result = when (method.returnType) {
                        Void.TYPE -> null
                        java.lang.Boolean.TYPE -> false
                        else -> null
                    }
                } catch (_: Throwable) {
                    // Never propagate into WM config.
                }
            }
        }
        log(TAG, "$name hooks=${methods.size}")
    }

    private fun hookSetRequestedOrientation(cls: Class<*>) {
        val methods = cls.declaredMethods.filter { m ->
            m.name == "setRequestedOrientation" &&
                m.parameterTypes.isNotEmpty() &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        if (methods.isEmpty()) {
            log(TAG, "$ACTIVITY_RECORD.setRequestedOrientation not found")
            return
        }
        for (method in methods) {
            method.isAccessible = true
            unhooks += method.hookBefore { param ->
                try {
                    if (!isAaActivity(param.thisObject)) return@hookBefore
                    param.args[0] = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    neutralizeOrientationField(param.thisObject)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun neutralizeOrientationField(record: Any) {
        val field = orientationField ?: if (orientationFieldLookupDone) {
            null
        } else {
            orientationFieldLookupDone = true
            findIntField(record, "mOrientation")?.also { orientationField = it }
        } ?: return
        try {
            if (field.getInt(record) != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                field.setInt(record, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            }
        } catch (_: Throwable) {
        }
    }

    private fun isAaActivity(record: Any?): Boolean {
        if (record == null) return false
        if (!CoreManagerService.hasAaVirtualDisplays()) return false
        val displayId = displayIdOf(record)
        if (displayId == Display.DEFAULT_DISPLAY || displayId == Display.INVALID_DISPLAY) {
            return false
        }
        return CoreManagerService.isAaVirtualDisplay(displayId)
    }

    private fun displayIdOf(record: Any): Int {
        val method = displayIdMethod ?: findNoArgIntMethod(record, "getDisplayId")?.also {
            displayIdMethod = it
        } ?: return Display.INVALID_DISPLAY
        return try {
            (method.invoke(record) as? Int) ?: Display.INVALID_DISPLAY
        } catch (_: Throwable) {
            Display.INVALID_DISPLAY
        }
    }

    private fun findNoArgIntMethod(record: Any, name: String): Method? {
        var cls: Class<*>? = record.javaClass
        while (cls != null && cls != Any::class.java) {
            val method = cls.declaredMethods.firstOrNull { m ->
                m.name == name &&
                    m.parameterTypes.isEmpty() &&
                    m.returnType == Int::class.javaPrimitiveType
            }
            if (method != null) {
                method.isAccessible = true
                return method
            }
            cls = cls.superclass
        }
        return null
    }

    private fun findIntField(record: Any, name: String): Field? {
        var cls: Class<*>? = record.javaClass
        while (cls != null && cls != Any::class.java) {
            val field = cls.declaredFields.firstOrNull {
                it.name == name && it.type == Int::class.javaPrimitiveType
            }
            if (field != null) {
                field.isAccessible = true
                return field
            }
            cls = cls.superclass
        }
        return null
    }
}
