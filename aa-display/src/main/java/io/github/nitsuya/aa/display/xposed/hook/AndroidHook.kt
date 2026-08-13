package io.github.nitsuya.aa.display.xposed.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManager
import android.content.res.Configuration
import android.view.Display
import android.view.WindowManager
import com.github.kyuubiran.ezxhelper.utils.*
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.BridgeService
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.logDebug
import io.github.nitsuya.aa.display.ui.aa.split.SplitPresentationGuard
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

    /** Prefer themable UI Context for system-side UI / Instances. */
    private fun fieldContext(ams: Any, name: String): Context? =
        runCatching { ams.getObjectAs(name, Context::class.java) as? Context }.getOrNull()

    @SuppressLint("SoonBlockedPrivateApi", "DiscouragedPrivateApi")
    private fun activityThreadUiContext(): Context? =
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
                ?: return@runCatching null
            runCatching {
                atClass.getDeclaredMethod("getSystemUiContext").invoke(thread) as? Context
            }.getOrNull()
                ?: runCatching {
                    atClass.getDeclaredMethod("getSystemContext").invoke(thread) as? Context
                }.getOrNull()
        }.getOrNull()

    /**
     * Capture AMS system/UI Context across OEM signature churn (e.g. One UI 8.5
     * no longer always has Context as constructor parameterTypes[0]).
     */
    private fun captureSystemContext(ams: Any, args: Array<Any?>?): Boolean {
        if (CoreManagerService.hasSystemContext) return true
        val ctx =
            fieldContext(ams, "mUiContext")
                ?: args?.filterIsInstance<Context>()?.firstOrNull()
                ?: fieldContext(ams, "mContext")
                ?: activityThreadUiContext()
        if (ctx == null) {
            log(tagName, "captureSystemContext failed")
            return false
        }
        CoreManagerService.systemContext = ctx
        log(tagName, "get systemUiContext")
        return true
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

        // One UI 8.5+ may reorder/wrap AMS ctor params; do not require Context at [0].
        var activityManagerServiceConstructorHook: List<XC_MethodHook.Unhook> = emptyList()
        activityManagerServiceConstructorHook =
            findAllConstructors("com.android.server.am.ActivityManagerService") {
                true
            }.hookAfter { param ->
                if (captureSystemContext(param.thisObject, param.args)) {
                    activityManagerServiceConstructorHook.forEach { hook -> hook.unhook() }
                }
            }.also {
                if (it.isEmpty()) {
                    log(tagName, "no ActivityManagerService constructor found")
                } else {
                    log(tagName, "hooked ${it.size} ActivityManagerService constructor(s)")
                }
            }

        var activityManagerServiceSystemReadyHook: XC_MethodHook.Unhook? = null
        activityManagerServiceSystemReadyHook =
            findMethod("com.android.server.am.ActivityManagerService") {
                name == "systemReady"
            }.hookAfter { param ->
                activityManagerServiceSystemReadyHook?.unhook()
                runCatching {
                    if (!CoreManagerService.hasSystemContext) {
                        captureSystemContext(param.thisObject, param.args)
                    }
                    CoreManagerService.systemReady()
                    log(tagName, "system ready")
                }.onFailure {
                    log(tagName, "systemReady failed", it)
                }
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

    /**
     * Pins virtual-display densityDpi for processes whose tasks live on the AA VD.
     * Must stay in sync when tasks move between the phone stack and the virtual-display stack.
     */
    object VdDensityPin {
        private val appInitUseDisplay: ConcurrentHashMap<String, Int> = ConcurrentHashMap()
        private val activityTaskManagerService_startProcessAsync by lazy {
            try {
                findSystemMethod("com.android.server.wm.ActivityTaskManagerService") {
                    name == "startProcessAsync"
                }
            } catch (e: Throwable) {
                log(
                    tagName,
                    "VdDensityPin ActivityTaskManagerService.startProcessAsync method",
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
                log(tagName, "VdDensityPin IApplicationThread.bindApplication method", e)
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
            logDebug(tagName, "VD density map mark: $pkg -> display=$displayId")
        }

        fun clearPackageVirtualDisplay(packageName: String?) {
            val pkg = normalizePackage(packageName) ?: return
            if (appInitUseDisplay.remove(pkg) != null) {
                logDebug(tagName, "VD density map clear: $pkg")
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
                        } else {
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

    /**
     * Reject [Presentation] windows / WindowContexts that target an AA pane owned by another
     * package. Douyin LivePlay uses MediaRouter → createWindowContext(TYPE_PRESENTATION)
     * → [attachWindowContextToDisplayArea]; `addWindow` often sees displayId=-1 afterward.
     */
    object PanePresentationGuard {
        /** WindowManagerGlobal.ADD_INVALID_DISPLAY */
        private const val ADD_INVALID_DISPLAY = -9

        private var addWindowHook: XC_MethodHook.Unhook? = null
        private var attachContextHook: XC_MethodHook.Unhook? = null
        private var hooked = false

        fun ensureHooked() {
            if (!isReadyForSystemHooks()) return
            if (hooked) return
            hook()
        }

        private fun hook() {
            hookAddWindow()
            hookAttachWindowContext()
            hooked = addWindowHook != null || attachContextHook != null
            if (hooked) {
                log(
                    tagName,
                    "PanePresentationGuard: hooked addWindow=${addWindowHook != null} " +
                        "attachContext=${attachContextHook != null}"
                )
            }
        }

        private fun hookAddWindow() {
            val method = findSystemMethod("com.android.server.wm.WindowManagerService") {
                name == "addWindow" &&
                    parameterTypes.any { it.name.endsWith("LayoutParams") }
            }
            if (method == null) {
                log(tagName, "PanePresentationGuard: WindowManagerService.addWindow not found")
                return
            }
            addWindowHook = method.hookBefore { param ->
                try {
                    val attrsIdx = param.args.indexOfFirst { it is WindowManager.LayoutParams }
                    if (attrsIdx < 0) return@hookBefore
                    val attrs = param.args[attrsIdx] as WindowManager.LayoutParams
                    if (!SplitPresentationGuard.isPresentationType(attrs.type)) return@hookBefore
                    val displayId = resolveAaPresentationDisplayId(param.args, attrsIdx)
                        ?: return@hookBefore
                    if (shouldBlockPresentation(displayId, param.args)) {
                        param.result = ADD_INVALID_DISPLAY
                    }
                } catch (e: Throwable) {
                    log(tagName, "PanePresentationGuard addWindow hook failed", e)
                }
            }
        }

        /**
         * AOSP/OneUI: `attachWindowContextToDisplayArea(IBinder, type, displayId, Bundle)`.
         * This is where Douyin binds TYPE_PRESENTATION to the wrong AA VD before addWindow.
         */
        private fun hookAttachWindowContext() {
            val method = findSystemMethod("com.android.server.wm.WindowManagerService") {
                name == "attachWindowContextToDisplayArea" &&
                    parameterTypes.size >= 3 &&
                    parameterTypes[1] == Int::class.javaPrimitiveType &&
                    parameterTypes[2] == Int::class.javaPrimitiveType
            }
            if (method == null) {
                log(tagName, "PanePresentationGuard: attachWindowContextToDisplayArea not found")
                return
            }
            attachContextHook = method.hookBefore { param ->
                try {
                    val type = param.args[1] as? Int ?: return@hookBefore
                    if (!SplitPresentationGuard.isPresentationType(type)) return@hookBefore
                    val displayId = param.args[2] as? Int ?: return@hookBefore
                    if (!CoreManagerService.isAaVirtualDisplay(displayId)) return@hookBefore
                    val ownerPkg = CoreManagerService.panePackageForDisplay(displayId) ?: return@hookBefore
                    val callerPkg = SplitPresentationGuard.packageForUid(
                        CoreManagerService.systemContext,
                        android.os.Binder.getCallingUid()
                    ) ?: return@hookBefore
                    if (!SplitPresentationGuard.isForeignPresentationCaller(ownerPkg, callerPkg)) {
                        return@hookBefore
                    }
                    log(
                        tagName,
                        "PanePresentationGuard: block attachContext $callerPkg type=$type " +
                            "display=$displayId (owner=$ownerPkg)"
                    )
                    // IllegalArgumentException is what clients expect for a bad display/type.
                    param.setThrowable(
                        IllegalArgumentException(
                            "AADisplay: presentation display $displayId owned by $ownerPkg"
                        )
                    )
                } catch (e: Throwable) {
                    log(tagName, "PanePresentationGuard attachContext hook failed", e)
                }
            }
        }

        private fun shouldBlockPresentation(displayId: Int, args: Array<Any?>): Boolean {
            val ownerPkg = CoreManagerService.panePackageForDisplay(displayId) ?: return false
            val callerPkg = resolvePresentationCallerPackage(args) ?: return false
            if (!SplitPresentationGuard.isForeignPresentationCaller(ownerPkg, callerPkg)) return false
            log(
                tagName,
                "PanePresentationGuard: block addWindow $callerPkg presentation on " +
                    "display=$displayId (owner=$ownerPkg)"
            )
            return true
        }

        private fun resolvePresentationCallerPackage(args: Array<Any?>): String? {
            val session = args.firstOrNull { arg ->
                arg != null && arg.javaClass.name.endsWith("Session")
            }
            val uid = if (session != null) {
                session.javaClass.methods.firstOrNull { m ->
                    m.name == "getUid" && m.parameterTypes.isEmpty()
                }?.invoke(session) as? Int
            } else {
                android.os.Binder.getCallingUid()
            } ?: return null
            return SplitPresentationGuard.packageForUid(CoreManagerService.systemContext, uid)
        }

        /**
         * OneUI WMS.addWindow: `(Session, IWindow, LayoutParams, III, …)` — do not assume
         * `attrsIdx+2` is always displayId (often userId=0). Prefer any AA VD id after attrs.
         */
        private fun resolveAaPresentationDisplayId(args: Array<Any?>, attrsIdx: Int): Int? {
            for (i in (attrsIdx + 1) until args.size) {
                val v = args[i] as? Int ?: continue
                if (CoreManagerService.isAaVirtualDisplay(v)) return v
            }
            val fallback = args.getOrNull(attrsIdx + 2) as? Int ?: return null
            return fallback.takeIf { CoreManagerService.isAaVirtualDisplay(it) }
        }
    }
}
