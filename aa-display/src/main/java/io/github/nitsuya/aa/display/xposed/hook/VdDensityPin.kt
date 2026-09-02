package io.github.nitsuya.aa.display.xposed.hook

import android.content.res.Configuration
import android.graphics.Rect
import android.view.Display
import com.github.kyuubiran.ezxhelper.utils.getObject
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.aa.display.xposed.util.log
import java.lang.reflect.Field
import java.lang.reflect.Method
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

    /** Cached once; WM configuration is a hot path — never look up methods by name there. */
    @Volatile private var activityRecordDisplayIdMethod: Method? = null
    @Volatile private var activityRecordPackageNameField: Field? = null
    @Volatile private var activityRecordMergedConfigField: Field? = null
    @Volatile private var activityRecordGetConfigurationMethod: Method? = null
    @Volatile private var packageNameLookupDone = false
    @Volatile private var mergedConfigLookupDone = false

    fun markPackageOnVirtualDisplay(packageName: String?, displayId: Int) {
        val pkg = normalizePackage(packageName) ?: return
        if (displayId == Display.DEFAULT_DISPLAY || displayId == Display.INVALID_DISPLAY) return
        pinnedPackages.add(pkg)
    }

    fun clearPackageVirtualDisplay(packageName: String?) {
        val pkg = normalizePackage(packageName) ?: return
        pinnedPackages.remove(pkg)
    }

    /** True when [packageName] currently has a task pinned to an AA VD. */
    fun isPackagePinned(packageName: String?): Boolean {
        val pkg = normalizePackage(packageName) ?: return false
        return pinnedPackages.contains(pkg)
    }

    /** Hot-path: any package currently pinned to an AA VD. */
    fun hasPinnedPackages(): Boolean = pinnedPackages.isNotEmpty()

    /**
     * True when any package for [uid] is on an AA VD stack.
     * Fail-closed: lookup errors / system uids → false (do not rewrite metrics).
     */
    fun isUidPinned(uid: Int): Boolean {
        if (uid < 10000) return false
        if (pinnedPackages.isEmpty()) return false
        return try {
            val pkgs = Instances.packageManager.getPackagesForUid(uid) ?: return false
            pkgs.any { isPackagePinned(it) }
        } catch (_: Throwable) {
            false
        }
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
                    if (!CoreManagerService.hasAaVirtualDisplays() && pinnedPackages.isEmpty()) {
                        return@hookBefore
                    }
                    val activityRecord = param.args[0]
                    val displayId = displayIdOf(activityRecord)
                    val pkg = packageNameOf(activityRecord) ?: return@hookBefore
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        // Task is on the phone stack — never keep forcing VD DPI.
                        clearPackageVirtualDisplay(pkg)
                        return@hookBefore
                    }
                    // Non-default (AA VD or other): same as before — pin package for VD DPI.
                    markPackageOnVirtualDisplay(pkg, displayId)
                } catch (_: Throwable) {
                }
            }
        applicationThread_bindApplication_hook =
            applicationThread_bindApplication?.hookBefore { param ->
                try {
                    if (pinnedPackages.isEmpty()) return@hookBefore
                    val configuration = param.args.firstOrNull { it is Configuration } as? Configuration
                        ?: return@hookBefore
                    val packageName = normalizePackage(param.args[0] as? String) ?: return@hookBefore
                    pinDensityIfMapped(packageName, configuration)
                } catch (_: Throwable) {
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
                    // Hot path: bail before any reflection when AA session is idle and nothing pinned.
                    if (!CoreManagerService.hasAaVirtualDisplays() && pinnedPackages.isEmpty()) {
                        return@hookBefore
                    }
                    val record = param.thisObject
                    val displayId = displayIdOf(record)
                    if (displayId == Display.INVALID_DISPLAY) return@hookBefore
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        // Phone stack: only touch the pin set when something is actually pinned.
                        if (pinnedPackages.isNotEmpty()) {
                            clearPackageVirtualDisplay(packageNameOf(record))
                        }
                        return@hookBefore
                    }
                    if (!CoreManagerService.isAaVirtualDisplay(displayId)) return@hookBefore
                    val vdDpi = CoreManagerService.getDensityDpi()
                    if (vdDpi == 0) return@hookBefore
                    val packageName = packageNameOf(record) ?: return@hookBefore
                    if (!pinnedPackages.contains(packageName)) {
                        markPackageOnVirtualDisplay(packageName, displayId)
                    }
                    val config = mergedOverrideConfig(record) ?: return@hookBefore
                    val paneSize = CoreManagerService.aaPaneSizePx(displayId)
                    if (paneSize != null) {
                        val (paneW, paneH) = paneSize
                        if (paneBoundsStale(config, paneW, paneH, vdDpi)) {
                            applyAaPaneBounds(config, paneW, paneH, vdDpi)
                        } else if (config.densityDpi != vdDpi) {
                            config.densityDpi = vdDpi
                        }
                    } else if (config.densityDpi != vdDpi) {
                        config.densityDpi = vdDpi
                    }
                } catch (_: Throwable) {
                    // Never propagate into WM ensureActivityConfiguration.
                }
            }
        } catch (e: Throwable) {
            log(TAG, "ActivityRecord configuration pin hook failed", e)
            null
        }
    }

    private fun paneBoundsStale(
        config: Configuration,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
    ): Boolean {
        val bounds = configWindowBounds(config)
        if (bounds != null) {
            return bounds.width() != widthPx || bounds.height() != heightPx
        }
        val density = densityDpi / 160f
        val expectedW = (widthPx / density).toInt()
        val expectedH = (heightPx / density).toInt()
        return config.screenWidthDp != expectedW || config.screenHeightDp != expectedH
    }

    private fun configWindowBounds(config: Configuration): Rect? {
        return runCatching {
            val wc = config.javaClass.getField("windowConfiguration").get(config) ?: return null
            wc.javaClass.getMethod("getBounds").invoke(wc) as? Rect
        }.getOrNull()
    }

    private fun applyAaPaneBounds(
        config: Configuration,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
    ) {
        val density = densityDpi / 160f
        config.densityDpi = densityDpi
        config.screenWidthDp = (widthPx / density).toInt().coerceAtLeast(1)
        config.screenHeightDp = (heightPx / density).toInt().coerceAtLeast(1)
        val rect = Rect(0, 0, widthPx, heightPx)
        runCatching {
            val wc = config.javaClass.getField("windowConfiguration").get(config) ?: return@runCatching
            wc.javaClass.getMethod("setBounds", Rect::class.java).invoke(wc, rect)
            wc.javaClass.getMethod("setAppBounds", Rect::class.java).invoke(wc, rect)
            wc.javaClass.getMethod("setMaxBounds", Rect::class.java).invoke(wc, rect)
        }
    }

    private fun pinDensityIfMapped(packageName: String, configuration: Configuration) {
        if (!pinnedPackages.contains(packageName)) return
        val densityDpi = CoreManagerService.getDensityDpi()
        if (densityDpi != 0 && configuration.densityDpi != densityDpi) {
            configuration.densityDpi = densityDpi
        }
    }

    private fun displayIdOf(record: Any): Int {
        val method = activityRecordDisplayIdMethod ?: findNoArgMethod(
            record,
            "getDisplayId",
            Int::class.javaPrimitiveType!!,
        )?.also { activityRecordDisplayIdMethod = it } ?: return Display.INVALID_DISPLAY
        return try {
            (method.invoke(record) as? Int) ?: Display.INVALID_DISPLAY
        } catch (_: Throwable) {
            Display.INVALID_DISPLAY
        }
    }

    private fun packageNameOf(record: Any): String? {
        if (!packageNameLookupDone) {
            activityRecordPackageNameField = findField(record, "packageName", String::class.java)
            packageNameLookupDone = true
        }
        val raw = activityRecordPackageNameField?.get(record) as? String
            ?: runCatching { record.getObject("packageName") as? String }.getOrNull()
        return normalizePackage(raw)
    }

    private fun mergedOverrideConfig(record: Any): Configuration? {
        if (!mergedConfigLookupDone) {
            activityRecordMergedConfigField =
                findField(record, "mMergedOverrideConfiguration", Configuration::class.java)
            activityRecordGetConfigurationMethod = findNoArgMethod(
                record,
                "getConfiguration",
                Configuration::class.java,
            )
            mergedConfigLookupDone = true
        }
        (activityRecordMergedConfigField?.get(record) as? Configuration)?.let { return it }
        return activityRecordGetConfigurationMethod?.invoke(record) as? Configuration
    }

    private fun findNoArgMethod(record: Any, name: String, returnType: Class<*>): Method? {
        var cls: Class<*>? = record.javaClass
        while (cls != null && cls != Any::class.java) {
            val method = cls.declaredMethods.firstOrNull { m ->
                m.name == name && m.parameterTypes.isEmpty() && m.returnType == returnType
            }
            if (method != null) {
                method.isAccessible = true
                return method
            }
            cls = cls.superclass
        }
        return null
    }

    private fun findField(record: Any, name: String, type: Class<*>): Field? {
        var cls: Class<*>? = record.javaClass
        while (cls != null && cls != Any::class.java) {
            val field = cls.declaredFields.firstOrNull { it.name == name && type.isAssignableFrom(it.type) }
            if (field != null) {
                field.isAccessible = true
                return field
            }
            cls = cls.superclass
        }
        return null
    }

    private fun normalizePackage(packageName: String?): String? {
        val pkg = packageName?.substringBeforeLast(":")?.trim().orEmpty()
        return pkg.takeIf { it.isNotEmpty() }
    }
}
