package io.github.nitsuya.aa.display.xposed.hook

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManager
import android.content.res.Configuration
import android.view.Display
import com.github.kyuubiran.ezxhelper.utils.*
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.xposed.BridgeService
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.log
import io.github.qauxv.util.Initiator
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object AndroidHook : BaseHook() {
    override val tagName: String = "AAD_AndroidHook"

    @Volatile
    var isSystemServerHooked: Boolean = false
        private set

    @Volatile
    private var systemServerClassLoader: ClassLoader? = null

    /** True only in the real system_server process after AndroidHook.init. */
    fun isReadyForSystemHooks(): Boolean =
        isSystemServerHooked && isSystemServerProcess()

    private fun isSystemServerProcess(): Boolean {
        return try {
            val name = File("/proc/self/cmdline").readBytes()
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)
            name == "system_server"
        } catch (_: Throwable) {
            false
        }
    }

    private fun findSystemMethod(
        className: String,
        findSuper: Boolean = false,
        condition: Method.() -> Boolean
    ): Method? {
        if (!isReadyForSystemHooks()) return null
        val cl = systemServerClassLoader ?: return null
        return findMethod(className, cl, findSuper, condition)
    }

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        systemServerClassLoader = lpparam.classLoader
        isSystemServerHooked = true
        Initiator.init(lpparam.classLoader)
        log(tagName, "xposed init")
        var serviceManagerHook: XC_MethodHook.Unhook? = null
        serviceManagerHook = findMethod("android.os.ServiceManager") {
            name == "addService"
        }.hookBefore { param ->
            if (param.args[0] == "package") {
                serviceManagerHook?.unhook()
                val pms = param.args[1] as IPackageManager
                log(tagName, "Got pms: $pms")
                runCatching {
                    BridgeService.register(pms)
                    log(tagName, "Bridge service injected")
                }.onFailure {
                    log(tagName, "System service crashed", it)
                }
            }
        }

        var activityManagerServiceConstructorHook: List<XC_MethodHook.Unhook> = emptyList()
        activityManagerServiceConstructorHook =
            findAllConstructors("com.android.server.am.ActivityManagerService") {
                parameterTypes[0] == Context::class.java
            }.hookAfter {
                activityManagerServiceConstructorHook.forEach { hook -> hook.unhook() }
                CoreManagerService.systemContext = it.thisObject.getObjectAs("mUiContext")
                log(tagName, "get systemUiContext")
            }.also {
                if (it.isEmpty())
                    log(tagName, "no constructor with parameterTypes[0] == Context found")
            }

        var activityManagerServiceSystemReadyHook: XC_MethodHook.Unhook? = null
        activityManagerServiceSystemReadyHook =
            findMethod("com.android.server.am.ActivityManagerService") {
                name == "systemReady"
            }.hookAfter {
                activityManagerServiceSystemReadyHook?.unhook()
                CoreManagerService.systemReady()
                log(tagName, "system ready")
            }


        findMethod("com.android.server.wm.ActivityTaskSupervisor") {
            name == "isCallerAllowedToLaunchOnDisplay"
                    && parameterCount == 4
                    && parameterTypes[0] == Int::class.javaPrimitiveType //callingPid
                    && parameterTypes[1] == Int::class.javaPrimitiveType //callingUid
                    && parameterTypes[2] == Int::class.javaPrimitiveType //launchDisplayId
                    && parameterTypes[3] == ActivityInfo::class.java
        }.hookAfter { param ->
            if (param.result as Boolean) {
                param.result = true
                log(tagName, "hook isCallerAllowedToLaunchOnDisplay success")
            }
        }
    }

    object Power {
        private val powerPress by lazy {
            try {
                findSystemMethod("com.android.server.policy.PhoneWindowManager") {
                    name == "powerPress"
                            && parameterCount == 3
                            && parameterTypes[0] == Long::class.javaPrimitiveType //eventTime
                            && parameterTypes[1] == Int::class.javaPrimitiveType //count
                            && parameterTypes[2] == Boolean::class.javaPrimitiveType //beganFromNonInteractive
                }
            } catch (e: Throwable) {
                log(tagName, "Power PhoneWindowManager.powerPress", e)
                null
            }
        }
        private var hookPower: XC_MethodHook.Unhook? = null
        fun hook() {
            if (!isReadyForSystemHooks()) return
            unHook()
            hookPower = powerPress?.hookBefore {
                if (!(it.args[2] as Boolean)) {
                    CoreApi.toggleDisplayPower()
                    it.abortMethod()
                } else {
                    CoreApi.displayPower(true)
                }
            }
        }

        fun unHook() {
            hookPower?.unhook()
            hookPower = null
        }
    }

    /**
     * Pins virtual-display densityDpi for processes whose tasks live on the AA VD.
     * Must stay in sync when tasks move between the phone stack and the virtual-display stack.
     */
    object FuckAppUseApplicationContext {
        private val appInitUseDisplay: ConcurrentHashMap<String, Int> = ConcurrentHashMap()
        private val activityTaskManagerService_startProcessAsync by lazy {
            try {
                findSystemMethod("com.android.server.wm.ActivityTaskManagerService") {
                    name == "startProcessAsync"
                }
            } catch (e: Throwable) {
                log(
                    tagName,
                    "FuckAppUseAppContext ActivityTaskManagerService.startProcessAsync method",
                    e
                )
                null
            }
        }
        private val applicationThread_bindApplication by lazy {
            try {
                findSystemMethod("android.app.IApplicationThread\$Stub\$Proxy") {
                    name == "bindApplication"
                }
            } catch (e: Throwable) {
                log(tagName, "FuckAppUseAppContext IApplicationThread.bindApplication method", e)
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
            appInitUseDisplay[pkg] = displayId
            log(tagName, "VD density map mark: $pkg -> display=$displayId")
        }

        fun clearPackageVirtualDisplay(packageName: String?) {
            val pkg = normalizePackage(packageName) ?: return
            if (appInitUseDisplay.remove(pkg) != null) {
                log(tagName, "VD density map clear: $pkg")
            }
        }

        /** Keep package→display DPI mapping in sync when a task moves VD ↔ phone. */
        fun onTaskDisplayChanged(packageName: String?, newDisplayId: Int) {
            val pkg = normalizePackage(packageName) ?: return
            if (CoreManagerService.isAaVirtualDisplay(newDisplayId)) {
                markPackageOnVirtualDisplay(pkg, newDisplayId)
            } else if (newDisplayId == Display.DEFAULT_DISPLAY) {
                clearPackageVirtualDisplay(pkg)
            }
        }

        /**
         * Install density hooks once per DisplayWindow session.
         * AA reconnect / onResume must NOT clear [appInitUseDisplay] — that drops VD DPI pinning
         * mid-session and dual-VD density pinning would break.
         */
        fun ensureHooked() {
            if (!isReadyForSystemHooks()) return
            if (hooked) return
            hook()
        }

        fun hook() {
            if (!isReadyForSystemHooks()) return
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
                        if (CoreManagerService.isAaVirtualDisplay(displayId)) {
                            markPackageOnVirtualDisplay(pkg, displayId)
                        } else if (displayId != Display.DEFAULT_DISPLAY) {
                            appInitUseDisplay[pkg] = displayId
                        }
                    } catch (e: Exception) {
                        log(
                            tagName,
                            "activityTaskManagerService_startProcessAsync Hook Exception",
                            e
                        )
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
                        log(tagName, "applicationThread_bindApplication Hook Exception", e)
                    }
                }
            // Re-pin VD density when WM recomputes activity configuration after cross-display moves.
            activityRecord_ensureConfiguration_hook = hookActivityRecordConfigurationPin()
            hooked = true
        }

        fun unHook() {
            uninstallHooks()
            appInitUseDisplay.clear()
            hooked = false
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
                val method = findSystemMethod("com.android.server.wm.ActivityRecord", findSuper = true) {
                    name == "ensureActivityConfiguration"
                } ?: findSystemMethod("com.android.server.wm.ActivityRecord", findSuper = true) {
                    name == "ensureConfiguration"
                } ?: return null
                method.hookBefore { param ->
                    try {
                        val record = param.thisObject
                        val displayId = record.invokeMethod("getDisplayId") as? Int ?: return@hookBefore
                        val vdDpi = CoreManagerService.getDensityDpi()
                        if (!CoreManagerService.isAaVirtualDisplay(displayId) || vdDpi == 0) {
                            if (displayId == Display.DEFAULT_DISPLAY) {
                                val packageName = normalizePackage(record.getObject("packageName") as? String)
                                clearPackageVirtualDisplay(packageName)
                            }
                            return@hookBefore
                        }
                        val packageName = normalizePackage(record.getObject("packageName") as? String)
                            ?: return@hookBefore
                        if (!appInitUseDisplay.containsKey(packageName)) {
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
                        log(tagName, "ActivityRecord configuration pin Exception", e)
                    }
                }
            } catch (e: Throwable) {
                log(tagName, "ActivityRecord configuration pin hook failed", e)
                null
            }
        }

        private fun pinDensityIfMapped(packageName: String, configuration: Configuration) {
            if (!appInitUseDisplay.containsKey(packageName)) return
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
}
