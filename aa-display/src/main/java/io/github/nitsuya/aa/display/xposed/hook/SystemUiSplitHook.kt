package io.github.nitsuya.aa.display.xposed.hook

import android.provider.Settings
import android.view.Display
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.findAllMethods
import com.github.kyuubiran.ezxhelper.utils.findMethodOrNull
import com.github.kyuubiran.ezxhelper.utils.getObjectOrNull
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.github.kyuubiran.ezxhelper.utils.loadClass
import com.github.kyuubiran.ezxhelper.utils.putObject
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.SharedPreferencesAccess
import io.github.nitsuya.aa.display.xposed.log
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * OneUI [StageCoordinator] is created for [Display.DEFAULT_DISPLAY] only.
 * After ATM allows freeform→split on the AA VD, rebind coordinator display id +
 * DisplayLayout so Shell applies stages on the car virtual display.
 */
object SystemUiSplitHook : BaseHook() {
    override val tagName: String = "AAD_SystemUiSplit"

    const val SETTINGS_VD_DISPLAY_ID = "aadisplay_vd_display_id"

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        if (!isOneUiSplitEnabled()) {
            log(tagName, "EnableOneUiSplit=false — skip")
            return
        }

        try {
            val stageClass = loadClass("com.android.wm.shell.splitscreen.StageCoordinator")
            findAllMethods(stageClass) {
                name == "onFreeformToSplitRequested"
            }.hookBefore { param ->
                val info = param.args.getOrNull(0)
                val taskId = info?.let {
                    readIntProp(it, "getFreeformToSplitTaskId", "mFreeformToSplitTaskId")
                        ?: readIntProp(it, "getTaskId", "mTaskId")
                } ?: -1
                val targetDisplay = resolveTargetDisplayId()
                if (targetDisplay == Display.INVALID_DISPLAY) {
                    log(tagName, "onFreeformToSplitRequested: no AA VD (task=$taskId)")
                    return@hookBefore
                }
                rebindStageCoordinator(param.thisObject, targetDisplay, taskId)
            }
            log(tagName, "hooked StageCoordinator.onFreeformToSplitRequested")

            // OneUI often delivers freeform→split via onSplitLayoutChangeRequested first
            // (TaskOrganizerInfo with mFreeformToSplitTaskId). Rebind here so companion
            // auto-pair searches the AA VD, not phone display 0.
            findAllMethods(stageClass) {
                name == "onSplitLayoutChangeRequested"
            }.hookBefore { param ->
                val info = param.args.getOrNull(0) ?: return@hookBefore
                val freeformTaskId = readIntProp(
                    info,
                    "getFreeformToSplitTaskId",
                    "mFreeformToSplitTaskId"
                ) ?: return@hookBefore
                if (freeformTaskId <= 0) return@hookBefore
                val targetDisplay = resolveTargetDisplayId()
                if (targetDisplay == Display.INVALID_DISPLAY) {
                    log(tagName, "onSplitLayoutChangeRequested(freeform→split): no AA VD")
                    return@hookBefore
                }
                rebindStageCoordinator(param.thisObject, targetDisplay, freeformTaskId)
            }
            log(tagName, "hooked StageCoordinator.onSplitLayoutChangeRequested for freeform→split")
        } catch (e: Throwable) {
            log(tagName, "hook StageCoordinator freeform→split failed:", e)
        }

        try {
            val captionClass = loadClass(
                "com.android.wm.shell.windowdecor.SecCaptionWindowDecorViewModel\$CaptionTouchEventListener"
            )
            findMethodOrNull(captionClass) {
                name == "moveToSplit" && parameterCount == 1
            }?.hookBefore { param ->
                val targetDisplay = resolveTargetDisplayId()
                if (targetDisplay == Display.INVALID_DISPLAY) return@hookBefore
                val coordinator = findStageCoordinatorNear(param.thisObject) ?: return@hookBefore
                rebindStageCoordinator(coordinator, targetDisplay, taskId = -1)
            } ?: log(tagName, "CaptionTouchEventListener.moveToSplit not found")
            log(tagName, "hooked CaptionTouchEventListener.moveToSplit")
        } catch (e: Throwable) {
            log(tagName, "hook moveToSplit failed:", e)
        }
    }

    private fun isOneUiSplitEnabled(): Boolean {
        val prefs = loadConfigPrefs() ?: return true
        return AADisplayConfig.EnableOneUiSplit.get(prefs)
    }

    private fun loadConfigPrefs(): XSharedPreferences? {
        return try {
            val mirror = File(SharedPreferencesAccess.HOOK_MIRROR_PATH)
            if (mirror.isFile) {
                XSharedPreferences(mirror).also { runCatching { it.reload() } }
            } else {
                XSharedPreferences(BuildConfig.APPLICATION_ID, AADisplayConfig.ConfigName)
                    .also { runCatching { it.reload() } }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveTargetDisplayId(): Int = readPublishedVdDisplayId()

    private fun readPublishedVdDisplayId(): Int {
        return try {
            EzXHelperInit.initAppContext()
            val cr = InitFields.appContext.contentResolver
            Settings.Global.getInt(cr, SETTINGS_VD_DISPLAY_ID, Display.INVALID_DISPLAY)
        } catch (_: Throwable) {
            Display.INVALID_DISPLAY
        }
    }

    private fun rebindStageCoordinator(coordinator: Any, displayId: Int, taskId: Int) {
        val before = readIntProp(coordinator, "getDisplayId", "mDisplayId")
        val idSet = setIntField(coordinator, "mDisplayId", displayId)

        val displayController = coordinator.getObjectOrNull("mDisplayController")
        val layout = try {
            displayController?.invokeMethod(
                "getDisplayLayout",
                args(displayId),
                argTypes(Integer.TYPE)
            )
        } catch (_: Throwable) {
            null
        }
        if (layout != null) {
            try {
                coordinator.putObject("mDisplayLayout", layout)
            } catch (_: Throwable) {
            }
            refreshSplitLayout(coordinator.getObjectOrNull("mSplitLayout"), layout)
        } else {
            // DisplayController often has no layout for virtual displays — patch fields.
            patchDisplayLayoutSize(coordinator.getObjectOrNull("mDisplayLayout"), displayId)
        }

        val rootId = readRootTaskId(coordinator) ?: 3
        moveStackToDisplay(rootId, displayId)

        val after = readIntProp(coordinator, "getDisplayId", "mDisplayId")
        log(
            tagName,
            "rebind StageCoordinator display $before -> $after " +
                "(target=$displayId setOk=$idSet task=$taskId root=$rootId layout=${layout != null})"
        )
    }

    private fun patchDisplayLayoutSize(displayLayout: Any?, displayId: Int) {
        if (displayLayout == null) return
        try {
            EzXHelperInit.initAppContext()
            val dm = InitFields.appContext.getSystemService(android.hardware.display.DisplayManager::class.java)
            val display = dm?.getDisplay(displayId) ?: return
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            setIntField(displayLayout, "mWidth", metrics.widthPixels)
            setIntField(displayLayout, "mHeight", metrics.heightPixels)
            log(
                tagName,
                "patched DisplayLayout to ${metrics.widthPixels}x${metrics.heightPixels} for display=$displayId"
            )
        } catch (e: Throwable) {
            log(tagName, "patch DisplayLayout failed:", e)
        }
    }

    private fun refreshSplitLayout(splitLayout: Any?, layout: Any) {
        if (splitLayout == null) return
        for (name in listOf("update", "updateConfiguration", "init", "reset")) {
            try {
                val methods = splitLayout.javaClass.declaredMethods.filter { it.name == name }
                for (m in methods) {
                    m.isAccessible = true
                    when (m.parameterCount) {
                        0 -> {
                            m.invoke(splitLayout)
                            return
                        }
                        1 -> {
                            if (m.parameterTypes[0].isInstance(layout)) {
                                m.invoke(splitLayout, layout)
                                return
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun readRootTaskId(coordinator: Any): Int? {
        for (fieldName in listOf("mRootTaskInfo", "mRootTask")) {
            try {
                val info = coordinator.getObjectOrNull(fieldName) ?: continue
                readIntProp(info, "getTaskId", "taskId")?.takeIf { it > 0 }?.let { return it }
                readIntProp(info, "getTaskId", "mTaskId")?.takeIf { it > 0 }?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun moveStackToDisplay(rootId: Int, displayId: Int) {
        if (rootId <= 0 || displayId == Display.INVALID_DISPLAY) return
        try {
            val proc = Runtime.getRuntime().exec(
                arrayOf(
                    "cmd",
                    "activity",
                    "display",
                    "move-stack",
                    rootId.toString(),
                    displayId.toString()
                )
            )
            val code = proc.waitFor()
            log(tagName, "move-stack $rootId -> $displayId exit=$code")
        } catch (e: Throwable) {
            log(tagName, "move-stack $rootId -> $displayId failed:", e)
        }
    }

    private fun findStageCoordinatorNear(from: Any): Any? {
        var cur: Any? = from
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.javaClass.name.contains("StageCoordinator")) return cur
            val next = listOf(
                "this\$0",
                "mStageCoordinator",
                "mSplitScreenController",
                "mController",
                "mDecorViewModel"
            ).firstNotNullOfOrNull { name ->
                readFieldWalking(cur!!, name)
            } ?: break

            if (next.javaClass.name.contains("StageCoordinator")) return next
            if (next.javaClass.name.contains("SplitScreenController")) {
                readFieldWalking(next, "mStageCoordinator")?.let { return it }
            }
            cur = next
            depth++
        }
        return null
    }

    private fun readFieldWalking(obj: Any, name: String): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                return cls.getDeclaredField(name).apply { isAccessible = true }.get(obj)
            } catch (_: Throwable) {
                cls = cls.superclass
            }
        }
        return null
    }

    private fun readIntProp(obj: Any, getter: String, field: String): Int? {
        try {
            val v = obj.invokeMethod(getter, args(), argTypes()) as? Int
            if (v != null) return v
        } catch (_: Throwable) {
        }
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                return cls.getDeclaredField(field).apply { isAccessible = true }.getInt(obj)
            } catch (_: Throwable) {
                cls = cls.superclass
            }
        }
        return null
    }

    private fun setIntField(obj: Any, name: String, value: Int): Boolean {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val field = cls.getDeclaredField(name)
                field.isAccessible = true
                clearFinal(field)
                field.setInt(obj, value)
                return field.getInt(obj) == value
            } catch (_: Throwable) {
                cls = cls.superclass
            }
        }
        return false
    }

    private fun clearFinal(field: Field) {
        if (!Modifier.isFinal(field.modifiers)) return
        try {
            val modifiersField = Field::class.java.getDeclaredField("modifiers")
            modifiersField.isAccessible = true
            modifiersField.setInt(field, field.modifiers and Modifier.FINAL.inv())
        } catch (_: Throwable) {
            try {
                val getDeclaredFields0 = Class::class.java.getDeclaredMethod(
                    "getDeclaredFields0",
                    Boolean::class.javaPrimitiveType
                )
                getDeclaredFields0.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val fields = getDeclaredFields0.invoke(Field::class.java, false) as Array<Field>
                val modifiersField = fields.first { it.name == "modifiers" }
                modifiersField.isAccessible = true
                modifiersField.setInt(field, field.modifiers and Modifier.FINAL.inv())
            } catch (_: Throwable) {
            }
        }
    }
}
