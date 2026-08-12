package io.github.nitsuya.aa.display.ui.aa.split

import android.app.ActivityTaskManager
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.SurfaceControl
import android.window.TaskSnapshot
import androidx.core.graphics.drawable.toBitmap
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import io.github.nitsuya.aa.display.model.RecentTaskInfo
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.util.Instances

internal class SplitInputRecents(private val c: SplitDisplayController) {

    fun displayIdFor(pane: Int): Int? = when (pane) {
        SplitPane.PRIMARY -> c.primaryDisplayId.takeIf { it != Display.INVALID_DISPLAY }
        SplitPane.SECONDARY -> c.secondaryDisplayId.takeIf { it != Display.INVALID_DISPLAY }
        else -> null
    }

    /** AADisplay CarActivity private presentation (receives Coolwalk content touches). */
    fun findAaHostDisplayId(): Int? {
        cachedAaHostDisplayId?.takeIf { id ->
            Instances.displayManager.getDisplay(id) != null
        }?.let { return it }
        // getDisplays() hides FLAG_PRIVATE VDs (AaDisplayActivity is private).
        val found = findDisplayIdByName("AaDisplayActivity")
        cachedAaHostDisplayId = found
        return found
    }

    private fun findDisplayIdByName(needle: String): Int? {
        Instances.displayManager.displays.firstOrNull { d ->
            d.name?.contains(needle, ignoreCase = true) == true
        }?.displayId?.let { return it }
        for (id in 1..64) {
            val d = runCatching { Instances.displayManager.getDisplay(id) }.getOrNull() ?: continue
            if (d.name?.contains(needle, ignoreCase = true) == true) return id
        }
        return null
    }

    private var cachedAaHostDisplayId: Int? = null

    fun injectInputEvent(displayId: Int, event: InputEvent): Boolean {
        val identity = Binder.clearCallingIdentity()
        return try {
            event.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            Instances.iInputManager.injectInputEvent(event, 0)
        } catch (e: Throwable) {
            log(SplitDisplayController.TAG, "injectInputEvent exception:", e)
            false
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    fun createKeyEvent(action: Int, keyCode: Int): KeyEvent {
        val whenMillis = SystemClock.uptimeMillis()
        return KeyEvent(
            whenMillis, whenMillis, action, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY,
            InputDevice.SOURCE_KEYBOARD
        )
    }

    fun isSystemHomeTask(taskInfo: ActivityTaskManager.RootTaskInfo): Boolean {
        return try {
            val conf = (taskInfo as Any).invokeMethod("getConfiguration", args(), argTypes()) ?: return false
            val winConf = conf.invokeMethod("getWindowConfiguration", args(), argTypes()) ?: return false
            val activityType = winConf.invokeMethod("getActivityType", args(), argTypes()) as? Int
            activityType == SplitDisplayController.ACTIVITY_TYPE_HOME
        } catch (_: Throwable) {
            false
        }
    }

    fun recentTaskInfo(displayId: Int): List<RecentTaskInfo> {
        val all = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        return all.mapNotNull { taskInfo ->
            if (isSystemHomeTask(taskInfo)) return@mapNotNull null
            val topActivity = taskInfo.topActivity ?: return@mapNotNull null
            if (SplitChromePackages.BOUNCE_EXCLUDED.contains(topActivity.packageName)) return@mapNotNull null
            var taskDescription = taskInfo.taskDescription
                ?: Instances.iActivityTaskManager.getTaskDescription(taskInfo.taskId)
                ?: return@mapNotNull null
            var icon = runCatching { taskDescription.icon }.getOrNull()
            if (icon == null) {
                icon = Instances.packageManager.getActivityIcon(topActivity).toBitmap()
            }
            var label = taskDescription.label
            if (label == null) {
                label = Instances.packageManager.getActivityInfo(topActivity, 0)
                    .loadLabel(Instances.packageManager).toString()
            }
            val snapshot: Bitmap? = runCatching {
                val snap: TaskSnapshot? = try {
                    if (Build.VERSION.SDK_INT >= 34) {
                        Instances.iActivityTaskManager.getTaskSnapshot(taskInfo.taskId, true, true)
                    } else {
                        Instances.iActivityTaskManager.getTaskSnapshot(taskInfo.taskId, true)
                    }
                } catch (_: Throwable) {
                    Instances.iActivityTaskManager.getTaskSnapshot(taskInfo.taskId, true)
                }
                snap?.hardwareBuffer?.let { buffer ->
                    Bitmap.wrapHardwareBuffer(buffer, snap.colorSpace)
                }
            }.getOrNull()
            RecentTaskInfo(icon, taskInfo.taskId, label, snapshot, topActivity.packageName)
        }
    }

    fun releaseMirrors(map: HashMap<SurfaceControl, SurfaceControl>) {
        map.values.forEach { sc ->
            try {
                c.mTransaction.apply {
                    invokeMethod("remove", args(sc), argTypes(SurfaceControl::class.java))
                }.apply()
            } catch (_: Throwable) {
            }
            sc.release()
        }
        map.clear()
    }
}
