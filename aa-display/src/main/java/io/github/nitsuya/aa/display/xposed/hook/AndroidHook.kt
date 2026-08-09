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

        // Samsung OneUI: moveFreeformTaskToSplitLocked hard-rejects displayId != 0 with
        // "failed, no display". Caption freeform on the AA VD always hits that path when the
        // phone top fullscreen task is Home. Bypass for the live AA virtual display and notify
        // the task organizer directly; also resolve stage roots on the AA VD TDA.
        hookOneUiFreeformToSplitOnVirtualDisplay()
    }

    /**
     * Allow OneUI caption freeform→split when the freeform task (and StageCoordinator shells)
     * live on the AA virtual display instead of [Display.DEFAULT_DISPLAY].
     */
    private fun hookOneUiFreeformToSplitOnVirtualDisplay() {
        try {
            findMethod("com.android.server.wm.MultiTaskingController") {
                name == "moveFreeformTaskToSplitLocked" && parameterCount == 2
            }.hookBefore { param ->
                val task = param.args[0] ?: return@hookBefore
                val vdId = CoreManagerService.getDisplayId()
                if (vdId == Display.INVALID_DISPLAY) return@hookBefore
                val displayId = readTaskDisplayId(task) ?: return@hookBefore
                if (displayId != vdId) return@hookBefore

                val inFreeform = try {
                    task.invokeMethod("inFreeformWindowingMode", args(), argTypes()) as? Boolean
                } catch (_: Throwable) {
                    null
                } == true
                val supportsSplit = try {
                    task.invokeMethod("supportsSplitScreenWindowingMode", args(), argTypes()) as? Boolean
                } catch (_: Throwable) {
                    null
                } != false
                val dex = try {
                    task.invokeMethod("isDexMode", args(), argTypes()) as? Boolean
                } catch (_: Throwable) {
                    false
                } == true
                if (!inFreeform || !supportsSplit || dex) {
                    log(
                        tagName,
                        "AA VD freeform→split skip: freeform=$inFreeform support=$supportsSplit dex=$dex task=$task"
                    )
                    return@hookBefore
                }

                val options = param.args.getOrNull(1)
                var position = 0
                var reparentCell = false
                if (options != null) {
                    try {
                        position = options.invokeMethod("getSplitPosition", args(), argTypes()) as? Int ?: 0
                    } catch (_: Throwable) {
                    }
                    try {
                        reparentCell =
                            options.invokeMethod("needToReparentCell", args(), argTypes()) as? Boolean
                                ?: false
                    } catch (_: Throwable) {
                    }
                }

                val toc = resolveTaskOrganizerController(param.thisObject) ?: run {
                    log(tagName, "AA VD freeform→split: TaskOrganizerController missing")
                    return@hookBefore
                }
                // Stock moveFreeformTaskToSplitByTaskId only sets withRecentAllApps when the
                // *phone* top fullscreen is Home. Otherwise it falls into this Locked path with
                // false — StageCoordinator then startTask()s into a stage and auto-pairs any other
                // FREEFORM on the VD (Default Launch 嘟嘟mini → main/left, caption app → side/right).
                // Force AppsEdge (withRecentAllApps=true) and park companions so main/left stays
                // the freeform app and side opens the chooser.
                val splitTaskId = readTaskId(task)
                val parked = parkCompanionFreeformsForSplit(param.thisObject, task, displayId)
                if (splitTaskId > 0) {
                    CoreManagerService.onFreeformToSplitCompanionsParked(splitTaskId, parked)
                }
                try {
                    toc.invokeMethod(
                        "onFreeformToSplitRequested",
                        args(task, true, position, reparentCell),
                        argTypes(
                            task.javaClass,
                            Boolean::class.javaPrimitiveType!!,
                            Int::class.javaPrimitiveType!!,
                            Boolean::class.javaPrimitiveType!!
                        )
                    )
                    log(
                        tagName,
                        "AA VD freeform→split: organizer notified display=$displayId pos=$position " +
                            "withAllApps=true task=$task parked=${parked.size}"
                    )
                    param.abortMethod()
                } catch (e: Throwable) {
                    log(tagName, "AA VD freeform→split organizer call failed:", e)
                }
            }
            log(tagName, "hooked MultiTaskingController.moveFreeformTaskToSplitLocked for AA VD")
        } catch (e: Throwable) {
            log(tagName, "hook moveFreeformTaskToSplitLocked failed:", e)
        }

        try {
            findMethod("com.android.server.wm.TaskOrganizerController") {
                name == "onSplitLayoutChangeRequested" &&
                    parameterCount == 1 &&
                    parameterTypes[0].name == "android.os.Bundle"
            }.hookBefore { param ->
                val vdId = CoreManagerService.getDisplayId()
                if (vdId == Display.INVALID_DISPLAY) return@hookBefore
                val toc = param.thisObject
                val bundle = param.args[0] ?: return@hookBefore
                val atm = toc.getObjectOrNull("mService") ?: return@hookBefore
                val rwc = atm.getObjectOrNull("mRootWindowContainer") ?: return@hookBefore

                val defaultRoot = try {
                    val defaultTda = rwc.invokeMethod(
                        "getDefaultTaskDisplayArea",
                        args(),
                        argTypes()
                    )
                    defaultTda?.invokeMethod("getRootMainStageTask", args(), argTypes())
                } catch (_: Throwable) {
                    null
                }
                if (defaultRoot != null) return@hookBefore

                val vdRoot = findRootMainStageOnDisplay(rwc, vdId) ?: run {
                    log(tagName, "onSplitLayoutChangeRequested: no stage root on phone or VD=$vdId")
                    return@hookBefore
                }

                try {
                    val runningInfoClass = loadClass("android.app.ActivityManager\$RunningTaskInfo")
                    val info = runningInfoClass.getDeclaredConstructor().newInstance()
                    vdRoot.invokeMethod(
                        "fillTaskInfo",
                        args(info),
                        argTypes(loadClass("android.app.TaskInfo"))
                    )
                    toc.putObject("mTmpTaskInfo", info)
                    val organizer = vdRoot.getObjectOrNull("mTaskOrganizer")
                    if (organizer == null) {
                        log(tagName, "onSplitLayoutChangeRequested: stage root has no organizer")
                        return@hookBefore
                    }
                    organizer.invokeMethod(
                        "onSplitLayoutChangeRequested",
                        args(info, bundle),
                        argTypes(
                            loadClass("android.app.ActivityManager\$RunningTaskInfo"),
                            loadClass("android.os.Bundle")
                        )
                    )
                    toc.putObject("mTmpTaskInfo", null)
                    log(tagName, "onSplitLayoutChangeRequested: delivered via AA VD=$vdId root=$vdRoot")
                    param.abortMethod()
                } catch (e: Throwable) {
                    try {
                        toc.putObject("mTmpTaskInfo", null)
                    } catch (_: Throwable) {
                    }
                    log(tagName, "onSplitLayoutChangeRequested VD path failed:", e)
                }
            }
            log(tagName, "hooked TaskOrganizerController.onSplitLayoutChangeRequested for AA VD")
        } catch (e: Throwable) {
            log(tagName, "hook onSplitLayoutChangeRequested failed:", e)
        }
    }

    private fun readTaskDisplayId(task: Any): Int? {
        return try {
            val dc = task.invokeMethod("getDisplayContent", args(), argTypes()) ?: return null
            dc.invokeMethod("getDisplayId", args(), argTypes()) as? Int
        } catch (_: Throwable) {
            null
        }
    }

    private fun readTaskId(task: Any): Int {
        try {
            (task.invokeMethod("getTaskId", args(), argTypes()) as? Int)
                ?.takeIf { it > 0 }
                ?.let { return it }
        } catch (_: Throwable) {
        }
        try {
            return task.getObjectAs("mTaskId", Int::class.javaPrimitiveType) as? Int ?: -1
        } catch (_: Throwable) {
            return -1
        }
    }

    /**
     * Move other FREEFORM leaf tasks off the AA VD onto the phone before freeform→split.
     * Uses the local ATMS (lock already held) — not the binder stub — to avoid deadlock.
     *
     * @return parked (taskId to package) pairs for ownership/reclaim suppress.
     */
    private fun parkCompanionFreeformsForSplit(
        multiTaskingController: Any,
        keepTask: Any,
        vdDisplayId: Int
    ): List<Pair<Int, String>> {
        val keepId = readTaskId(keepTask)
        val atm = resolveActivityTaskManager(multiTaskingController) ?: return emptyList()
        val tda = try {
            val rwc = atm.getObjectOrNull("mRootWindowContainer") ?: return emptyList()
            val dc = rwc.invokeMethod(
                "getDisplayContent",
                args(vdDisplayId),
                argTypes(Int::class.javaPrimitiveType!!)
            ) ?: return emptyList()
            dc.invokeMethod("getDefaultTaskDisplayArea", args(), argTypes())
        } catch (_: Throwable) {
            null
        } ?: return emptyList()

        val candidates = mutableListOf<Pair<Int, String>>()
        val childCount = try {
            tda.invokeMethod("getChildCount", args(), argTypes()) as? Int ?: 0
        } catch (_: Throwable) {
            0
        }
        for (i in 0 until childCount) {
            val child = try {
                tda.invokeMethod("getChildAt", args(i), argTypes(Int::class.javaPrimitiveType!!))
            } catch (_: Throwable) {
                null
            } ?: continue
            collectParkableFreeformLeaves(child, keepId, candidates)
        }

        if (candidates.isEmpty()) return emptyList()

        val parked = mutableListOf<Pair<Int, String>>()
        for ((taskId, pkg) in candidates.distinctBy { it.first }) {
            if (moveRootTaskToDisplayLocal(atm, taskId, Display.DEFAULT_DISPLAY)) {
                parked.add(taskId to pkg)
                log(tagName, "AA VD freeform→split: parked companion task=$taskId pkg=$pkg -> phone")
            } else {
                log(tagName, "AA VD freeform→split: park failed task=$taskId pkg=$pkg")
            }
        }
        return parked
    }

    private fun collectParkableFreeformLeaves(
        node: Any,
        keepId: Int,
        out: MutableList<Pair<Int, String>>
    ) {
        val taskId = readTaskId(node)
        if (taskId <= 0 || taskId == keepId) return

        val createdByOrganizer = try {
            node.getObjectAs("mCreatedByOrganizer", Boolean::class.javaPrimitiveType) as? Boolean
        } catch (_: Throwable) {
            null
        } == true
        if (createdByOrganizer) {
            // Walk stage children — do not move StageCoordinator shells themselves.
            val n = try {
                node.invokeMethod("getChildCount", args(), argTypes()) as? Int ?: 0
            } catch (_: Throwable) {
                0
            }
            for (i in 0 until n) {
                val child = try {
                    node.invokeMethod(
                        "getChildAt",
                        args(i),
                        argTypes(Int::class.javaPrimitiveType!!)
                    )
                } catch (_: Throwable) {
                    null
                } ?: continue
                collectParkableFreeformLeaves(child, keepId, out)
            }
            return
        }

        val inFreeform = try {
            node.invokeMethod("inFreeformWindowingMode", args(), argTypes()) as? Boolean
        } catch (_: Throwable) {
            null
        } == true
        if (!inFreeform) return

        val activityType = try {
            node.invokeMethod("getActivityType", args(), argTypes()) as? Int
        } catch (_: Throwable) {
            null
        }
        // ACTIVITY_TYPE_HOME = 2 — keep secondary home under the split.
        if (activityType == 2) return

        val pkg = readTopPackage(node) ?: return
        if (pkg == "com.android.systemui" ||
            pkg == "com.samsung.android.app.appsedge" ||
            pkg == BuildConfig.APPLICATION_ID
        ) {
            return
        }
        out.add(taskId to pkg)
    }

    private fun readTopPackage(task: Any): String? {
        try {
            val top = task.invokeMethod("getTopNonFinishingActivity", args(), argTypes())
                ?: task.invokeMethod("getTopActivity", args(), argTypes())
            val name = top?.invokeMethod("getPackageName", args(), argTypes()) as? String
            if (!name.isNullOrBlank()) return name
        } catch (_: Throwable) {
        }
        try {
            val intent = task.getObjectOrNull("intent") ?: task.getObjectOrNull("mIntent")
            val cmp = intent?.invokeMethod("getComponent", args(), argTypes())
            val name = cmp?.invokeMethod("getPackageName", args(), argTypes()) as? String
            if (!name.isNullOrBlank()) return name
        } catch (_: Throwable) {
        }
        return null
    }

    private fun resolveActivityTaskManager(multiTaskingController: Any): Any? {
        try {
            multiTaskingController.getObjectOrNull("mAtm")?.let { return it }
        } catch (_: Throwable) {
        }
        try {
            val wm = multiTaskingController.getObjectOrNull("mWm") ?: return null
            return wm.getObjectOrNull("mAtmService")
        } catch (_: Throwable) {
            return null
        }
    }

    private fun moveRootTaskToDisplayLocal(atm: Any, taskId: Int, displayId: Int): Boolean {
        return try {
            atm.invokeMethod(
                "moveRootTaskToDisplay",
                args(taskId, displayId),
                argTypes(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            )
            true
        } catch (e: Throwable) {
            log(tagName, "moveRootTaskToDisplayLocal($taskId -> $displayId) failed:", e)
            false
        }
    }

    private fun resolveTaskOrganizerController(multiTaskingController: Any): Any? {
        try {
            multiTaskingController.getObjectOrNull("mAtm")
                ?.getObjectOrNull("mTaskOrganizerController")
                ?.let { return it }
        } catch (_: Throwable) {
        }
        try {
            val wm = multiTaskingController.getObjectOrNull("mWm") ?: return null
            val atm = wm.getObjectOrNull("mAtmService") ?: return null
            return atm.getObjectOrNull("mTaskOrganizerController")
        } catch (_: Throwable) {
            return null
        }
    }

    private fun findRootMainStageOnDisplay(rwc: Any, displayId: Int): Any? {
        return try {
            val dc = rwc.invokeMethod(
                "getDisplayContent",
                args(displayId),
                argTypes(Int::class.javaPrimitiveType!!)
            ) ?: return null
            val tda = try {
                dc.invokeMethod("getDefaultTaskDisplayArea", args(), argTypes())
            } catch (_: Throwable) {
                null
            } ?: return null
            tda.invokeMethod("getRootMainStageTask", args(), argTypes())
        } catch (_: Throwable) {
            null
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
            val vdId = CoreManagerService.getDisplayId()
            if (vdId != Display.INVALID_DISPLAY && newDisplayId == vdId) {
                markPackageOnVirtualDisplay(pkg, vdId)
            } else if (newDisplayId == Display.DEFAULT_DISPLAY) {
                clearPackageVirtualDisplay(pkg)
            }
        }

        /**
         * Install density hooks once per DisplayWindow session.
         * AA reconnect / onResume must NOT clear [appInitUseDisplay] — that drops VD DPI pinning
         * mid-session and OneUI split often needs a reboot to recover.
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
                        val vdId = CoreManagerService.getDisplayId()
                        if (displayId == Display.DEFAULT_DISPLAY) {
                            // Task is on the phone stack — never keep forcing VD DPI.
                            clearPackageVirtualDisplay(pkg)
                            return@hookBefore
                        }
                        if (vdId != Display.INVALID_DISPLAY && displayId == vdId) {
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
                        val configuration = param.args[15]
                        if (configuration !is Configuration) {
                            return@hookBefore
                        }
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
                        val vdId = CoreManagerService.getDisplayId()
                        val vdDpi = CoreManagerService.getDensityDpi()
                        if (vdId == Display.INVALID_DISPLAY || vdDpi == 0) return@hookBefore
                        val packageName = normalizePackage(record.getObject("packageName") as? String)
                            ?: return@hookBefore
                        if (displayId == vdId) {
                            if (!appInitUseDisplay.containsKey(packageName)) {
                                markPackageOnVirtualDisplay(packageName, vdId)
                            }
                            // Only rewrite when wrong — constant writes during OneUI MW layout fights
                            // split bounds and can leave MW unusable until reboot.
                            val config = runCatching {
                                record.getObject("mMergedOverrideConfiguration") as? Configuration
                            }.getOrNull() ?: runCatching {
                                record.invokeMethod("getConfiguration") as? Configuration
                            }.getOrNull()
                            if (config != null && config.densityDpi != vdDpi) {
                                config.densityDpi = vdDpi
                            }
                        } else if (displayId == Display.DEFAULT_DISPLAY) {
                            clearPackageVirtualDisplay(packageName)
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
