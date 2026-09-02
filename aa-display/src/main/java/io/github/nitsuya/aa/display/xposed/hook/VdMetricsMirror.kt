package io.github.nitsuya.aa.display.xposed.hook

import android.os.Binder
import android.view.Display
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.XC_MethodHook
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.util.log
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * When a package pinned to an AA VD queries [Display.DEFAULT_DISPLAY] DisplayInfo
 * (e.g. getDefaultDisplay().getMetrics()), rewrite logicalDensityDpi to the live VD
 * density so apps that bypass Configuration still see VD DPI.
 *
 * Fail-closed: ClassNotFound / missing methods / NPE / copy failure → leave original
 * result; trip [enabled] after repeated failures. Never mutate shared DisplayInfo
 * in place. [VdDensityPin] stays unchanged as the Configuration path.
 */
object VdMetricsMirror {
    private const val TAG = "AAD_VdMetricsMirror"
    private const val FAIL_TRIP = 8
    private const val LOG_EVERY = 16

    private const val DMS = "com.android.server.display.DisplayManagerService"
    private const val DMS_BINDER = "com.android.server.display.DisplayManagerService\$BinderService"
    private const val DISPLAY_INFO = "android.view.DisplayInfo"

    @Volatile
    private var enabled = true

    private var installAttempted = false
    private var hooked = false
    private var internalHook: XC_MethodHook.Unhook? = null
    private var binderHook: XC_MethodHook.Unhook? = null

    private val failCount = AtomicInteger(0)
    private val failLogCount = AtomicInteger(0)

    @Volatile private var copyStrategyClass: Class<*>? = null
    @Volatile private var copyCtor: Constructor<*>? = null
    @Volatile private var copyFromMethod: Method? = null
    @Volatile private var setToMethod: Method? = null
    @Volatile private var emptyCtor: Constructor<*>? = null

    @Volatile private var fieldsClass: Class<*>? = null
    @Volatile private var logicalDensityDpiField: Field? = null
    @Volatile private var densityDpiField: Field? = null
    @Volatile private var physicalXDpiField: Field? = null
    @Volatile private var physicalYDpiField: Field? = null

    fun ensureHooked() {
        if (!AndroidHook.isReadyForSystemHooks()) return
        if (installAttempted) return
        installAttempted = true
        try {
            installHooks()
        } catch (t: Throwable) {
            enabled = false
            log(TAG, "ensureHooked failed; degraded to VdDensityPin only", t)
        }
    }

    private fun installHooks() {
        if (!AndroidHook.isReadyForSystemHooks()) return
        if (!resolveDisplayInfoAccess()) {
            enabled = false
            log(TAG, "DisplayInfo density fields unavailable; mirror disabled")
            return
        }

        val internal = AndroidHook.findSystemMethod(DMS) {
            name == "getDisplayInfoInternal" &&
                parameterTypes.size == 2 &&
                parameterTypes[0] == Int::class.javaPrimitiveType &&
                parameterTypes[1] == Int::class.javaPrimitiveType
        }
        if (internal != null) {
            internalHook = internal.hookAfter { param ->
                onDisplayInfoResult(
                    displayId = param.args.getOrNull(0) as? Int,
                    callingUid = param.args.getOrNull(1) as? Int,
                    param = param,
                )
            }
            hooked = true
            log(TAG, "hooked DisplayManagerService.getDisplayInfoInternal")
            return
        }

        val binder = AndroidHook.findSystemMethod(DMS_BINDER) {
            name == "getDisplayInfo" &&
                parameterTypes.size == 1 &&
                parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: AndroidHook.findSystemMethod(DMS, findSuper = true) {
            name == "getDisplayInfo" &&
                parameterTypes.size == 1 &&
                parameterTypes[0] == Int::class.javaPrimitiveType &&
                declaringClass.name.contains("BinderService")
        }
        if (binder != null) {
            binderHook = binder.hookAfter { param ->
                val uid = try {
                    Binder.getCallingUid()
                } catch (_: Throwable) {
                    return@hookAfter
                }
                onDisplayInfoResult(
                    displayId = param.args.getOrNull(0) as? Int,
                    callingUid = uid,
                    param = param,
                )
            }
            hooked = true
            log(TAG, "hooked DisplayManagerService BinderService.getDisplayInfo")
            return
        }

        enabled = false
        log(TAG, "no DisplayManagerService getDisplayInfo* method; mirror disabled")
    }

    private fun onDisplayInfoResult(
        displayId: Int?,
        callingUid: Int?,
        param: XC_MethodHook.MethodHookParam,
    ) {
        if (!enabled) return
        try {
            if (displayId == null || callingUid == null) return
            if (displayId != Display.DEFAULT_DISPLAY) return
            if (!CoreManagerService.hasAaVirtualDisplays()) return
            if (!VdDensityPin.hasPinnedPackages()) return
            val vdDpi = CoreManagerService.getDensityDpi()
            if (vdDpi <= 0) return
            if (!VdDensityPin.isUidPinned(callingUid)) return

            val original = param.result ?: return
            if (!isDisplayInfo(original)) return
            resolveFields(original.javaClass)
            val logical = logicalDensityDpiField
            if (logical == null) {
                noteFailure("logicalDensityDpi field missing")
                return
            }
            val current = try {
                logical.getInt(original)
            } catch (_: Throwable) {
                noteFailure("read logicalDensityDpi failed")
                return
            }
            if (current == vdDpi) return

            val copy = copyDisplayInfo(original) ?: run {
                noteFailure("copy DisplayInfo failed")
                return
            }
            if (!applyDensity(copy, vdDpi)) {
                noteFailure("write density fields failed")
                return
            }
            param.result = copy
        } catch (t: Throwable) {
            noteFailure("hot-path", t)
        }
    }

    private fun isDisplayInfo(obj: Any): Boolean {
        return try {
            obj.javaClass.name == DISPLAY_INFO ||
                obj.javaClass.name.endsWith(".DisplayInfo")
        } catch (_: Throwable) {
            false
        }
    }

    private fun resolveDisplayInfoAccess(): Boolean {
        return try {
            val cls = AndroidHook.loadSystemClass(DISPLAY_INFO) ?: Class.forName(DISPLAY_INFO)
            resolveFields(cls)
            resolveCopyStrategy(cls)
            logicalDensityDpiField != null &&
                (copyCtor != null || copyFromMethod != null || setToMethod != null)
        } catch (t: Throwable) {
            log(TAG, "resolveDisplayInfoAccess failed", t)
            false
        }
    }

    private fun resolveFields(cls: Class<*>) {
        if (fieldsClass == cls && logicalDensityDpiField != null) return
        synchronized(this) {
            if (fieldsClass == cls && logicalDensityDpiField != null) return
            logicalDensityDpiField = findField(cls, "logicalDensityDpi", Int::class.javaPrimitiveType!!)
            densityDpiField = findField(cls, "densityDpi", Int::class.javaPrimitiveType!!)
            physicalXDpiField = findField(cls, "physicalXDpi", Float::class.javaPrimitiveType!!)
            physicalYDpiField = findField(cls, "physicalYDpi", Float::class.javaPrimitiveType!!)
            fieldsClass = cls
        }
    }

    private fun resolveCopyStrategy(cls: Class<*>) {
        if (copyStrategyClass == cls &&
            (copyCtor != null || copyFromMethod != null || setToMethod != null)
        ) {
            return
        }
        synchronized(this) {
            if (copyStrategyClass == cls &&
                (copyCtor != null || copyFromMethod != null || setToMethod != null)
            ) {
                return
            }
            copyCtor = runCatching {
                cls.getDeclaredConstructor(cls).also { it.isAccessible = true }
            }.getOrNull()
            emptyCtor = runCatching {
                cls.getDeclaredConstructor().also { it.isAccessible = true }
            }.getOrNull()
            copyFromMethod = runCatching {
                cls.getDeclaredMethod("copyFrom", cls).also { it.isAccessible = true }
            }.getOrNull()
            setToMethod = runCatching {
                cls.getDeclaredMethod("setTo", cls).also { it.isAccessible = true }
            }.getOrNull()
            copyStrategyClass = cls
        }
    }

    private fun copyDisplayInfo(original: Any): Any? {
        return try {
            val cls = original.javaClass
            resolveCopyStrategy(cls)
            copyCtor?.takeIf { it.declaringClass == cls }?.newInstance(original)?.let { return it }
            val empty = emptyCtor?.takeIf { it.declaringClass == cls }
            val from = copyFromMethod?.takeIf { it.declaringClass == cls }
            if (empty != null && from != null) {
                val copy = empty.newInstance()
                from.invoke(copy, original)
                return copy
            }
            val setTo = setToMethod?.takeIf { it.declaringClass == cls }
            if (empty != null && setTo != null) {
                val copy = empty.newInstance()
                setTo.invoke(copy, original)
                return copy
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun applyDensity(info: Any, vdDpi: Int): Boolean {
        return try {
            resolveFields(info.javaClass)
            val logical = logicalDensityDpiField ?: return false
            logical.setInt(info, vdDpi)
            densityDpiField?.let { field ->
                runCatching { field.setInt(info, vdDpi) }
            }
            val dpiF = vdDpi.toFloat()
            physicalXDpiField?.let { field ->
                runCatching { field.setFloat(info, dpiF) }
            }
            physicalYDpiField?.let { field ->
                runCatching { field.setFloat(info, dpiF) }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun findField(cls: Class<*>, name: String, type: Class<*>): Field? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            try {
                val f = c.getDeclaredField(name)
                if (type.isAssignableFrom(f.type) || f.type == type) {
                    f.isAccessible = true
                    return f
                }
            } catch (_: NoSuchFieldException) {
            } catch (_: Throwable) {
                return null
            }
            c = c.superclass
        }
        return null
    }

    private fun noteFailure(reason: String, t: Throwable? = null) {
        val n = failCount.incrementAndGet()
        val logN = failLogCount.incrementAndGet()
        if (logN == 1 || logN % LOG_EVERY == 0) {
            if (t != null) {
                log(TAG, "rewrite skipped ($reason) count=$n", t)
            } else {
                log(TAG, "rewrite skipped ($reason) count=$n")
            }
        }
        if (n >= FAIL_TRIP) {
            enabled = false
            log(TAG, "circuit open after $n failures; mirror disabled for this process")
        }
    }
}
