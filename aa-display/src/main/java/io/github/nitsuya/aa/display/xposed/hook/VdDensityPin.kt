package io.github.nitsuya.aa.display.xposed.hook

import android.content.res.Configuration
import android.view.Display
import com.github.kyuubiran.ezxhelper.utils.getObject
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import de.robv.android.xposed.XC_MethodHook
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.util.log
import java.util.concurrent.ConcurrentHashMap

/**
 * Pins virtual-display densityDpi for processes whose tasks live on the AA VD.
 * Must stay in sync when tasks move between the phone stack and the virtual-display stack.
 */
object VdDensityPin {
    private const val TAG = "AAD_VdDensityPin"

    /** Packages that should receive AA VD densityDpi (displayId values were never read). */
    private val pinnedPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val activityTaskManagerService_startProcessAsync by lazy {
        try {
            AndroidHook.findSystemMethod("com.android.server.wm.ActivityTaskManagerService") {
                name == "startProcessAsync"
            }
        } catch (e: Throwable) {
            log(TAG, "ActivityTaskManagerService.startProcessAsync method", e)
            null
        }
    }
    private val applicationThread_bindApplication by lazy {
        try {
            AndroidHook.findSystemMethod("android.app.IApplicationThread\$Stub\$Proxy") {
                name == "bindApplication"
            }
        } catch (e: Throwable) {
            log(TAG, "IApplicationThread.bindApplication method", e)
            null
        }
    }
    private var activityTaskManagerService_startProcessAsync_hook: XC_MethodHook.Unhook? = null
    private var applicationThread_bindApplication_hook: XC_MethodHook.Unhook? = null
    private var activityRecord_ensureConfiguration_hook: XC_MethodHook.Unhook? = null
    private var hooked = false

    fun markPackageOnVirtualDisplay(packageName: String?, displayId: Int) {
        val pkg = normalizePackage(packageName) ?: return
        if (displayId == Display.DEFAULT_DISPLAY || displayId == Display.INVALID_DISPLAY) return
        pinnedPackages.add(pkg)
    }

    fun clearPackageVirtualDisplay(packageName: String?) {
        val pkg = normalizePackage(packageName) ?: return
        pinnedPackages.remove(pkg)
    }

    /** Keep package DPI pinning in sync when a task moves VD ↔ phone. */
    fun onTaskDisplayChanged(packageName: String?, newDisplayId: Int) {
        val pkg = normalizePackage(packageName) ?: return
        if (CoreManagerService.isAaVirtualDisplay(newDisplayId)) {
            markPackageOnVirtualDisplay(pkg, newDisplayId)
        } else if (newDisplayId == Display.DEFAULT_DISPLAY) {
            clearPackageVirtualDisplay(pkg)
        }
    }

    /**
     * Install density hooks once per DisplaySessionPolicy session.
     * AA reconnect / onResume must NOT clear [pinnedPackages] — that drops VD DPI pinning
     * mid-session and dual-VD density pinning would break.
     */
    fun ensureHooked() {
        if (!AndroidHook.isReadyForSystemHooks()) return
        if (hooked) return
        installHooks()
    }

    fun unHook() {
        uninstallHooks()
        pinnedPackages.clear()
        hooked = false
    }

    private fun installHooks() {
        if (!AndroidHook.isReadyForSystemHooks()) return
        uninstallHooks()
        activityTaskManagerService_startProcessAsync_hook =
            activityTaskManagerService_startProcessAsync?.hookBefore { param ->
                try {
                    val activityRecord = param.args[0]
                    val displayId = activityRecord.invokeMethod("getDisplayId") as Int
                    val packageName = activityRecord.getObject("packageName") as String
                    val pkg = normalizePackage(packageName) ?: return@hookBefore
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        // Task is on the phone stack — never keep forcing VD DPI.
                        clearPackageVirtualDisplay(pkg)
                        return@hookBefore
                    }
                    // Non-default (AA VD or other): same as before — pin package for VD DPI.
                    markPackageOnVirtualDisplay(pkg, displayId)
                } catch (e: Exception) {
                    log(TAG, "activityTaskManagerService_startProcessAsync Hook Exception", e)
                }
            }
        applicationThread_bindApplication_hook =
            applicationThread_bindApplication?.hookBefore { param ->
                try {
                    val configuration = param.args.firstOrNull { it is Configuration } as? Configuration
                        ?: return@hookBefore
                    val packageName = normalizePackage(param.args[0] as? String) ?: return@hookBefore
                    pinDensityIfMapped(packageName, configuration)
                } catch (e: Exception) {
                    log(TAG, "applicationThread_bindApplication Hook Exception", e)
                }
            }
        // Re-pin VD density when WM recomputes activity configuration after cross-display moves.
        activityRecord_ensureConfiguration_hook = hookActivityRecordConfigurationPin()
        hooked = true
    }

    private fun uninstallHooks() {
        activityTaskManagerService_startProcessAsync_hook?.apply { unhook() }
        activityTaskManagerService_startProcessAsync_hook = null

        applicationThread_bindApplication_hook?.apply { unhook() }
        applicationThread_bindApplication_hook = null

        activityRecord_ensureConfiguration_hook?.apply { unhook() }
        activityRecord_ensureConfiguration_hook = null
        hooked = false
    }

    private fun hookActivityRecordConfigurationPin(): XC_MethodHook.Unhook? {
        return try {
            val method = AndroidHook.findSystemMethod(
                "com.android.server.wm.ActivityRecord",
                findSuper = true
            ) {
                name == "ensureActivityConfiguration"
            } ?: AndroidHook.findSystemMethod(
                "com.android.server.wm.ActivityRecord",
                findSuper = true
            ) {
                name == "ensureConfiguration"
            } ?: return null
            method.hookBefore { param ->
                try {
                    val record = param.thisObject
                    val displayId = record.invokeMethod("getDisplayId") as? Int ?: return@hookBefore
                    val vdDpi = CoreManagerService.getDensityDpi()
                    if (!CoreManagerService.isAaVirtualDisplay(displayId) || vdDpi == 0) {
                        if (displayId == Display.DEFAULT_DISPLAY) {
                            val packageName =
                                normalizePackage(record.getObject("packageName") as? String)
                            clearPackageVirtualDisplay(packageName)
                        }
                        return@hookBefore
                    }
                    val packageName = normalizePackage(record.getObject("packageName") as? String)
                        ?: return@hookBefore
                    if (!pinnedPackages.contains(packageName)) {
                        markPackageOnVirtualDisplay(packageName, displayId)
                    }
                    // Only rewrite when density is wrong — avoid fighting live layout.
                    val config = runCatching {
                        record.getObject("mMergedOverrideConfiguration") as? Configuration
                    }.getOrNull() ?: runCatching {
                        record.invokeMethod("getConfiguration") as? Configuration
                    }.getOrNull()
                    if (config != null && config.densityDpi != vdDpi) {
                        config.densityDpi = vdDpi
                    }
                } catch (e: Exception) {
                    log(TAG, "ActivityRecord configuration pin Exception", e)
                }
            }
        } catch (e: Throwable) {
            log(TAG, "ActivityRecord configuration pin hook failed", e)
            null
        }
    }

    private fun pinDensityIfMapped(packageName: String, configuration: Configuration) {
        if (!pinnedPackages.contains(packageName)) return
        val densityDpi = CoreManagerService.getDensityDpi()
        if (densityDpi != 0 && configuration.densityDpi != densityDpi) {
            configuration.densityDpi = densityDpi
        }
    }

    private fun normalizePackage(packageName: String?): String? {
        val pkg = packageName?.substringBeforeLast(":")?.trim().orEmpty()
        return pkg.takeIf { it.isNotEmpty() }
    }
}
