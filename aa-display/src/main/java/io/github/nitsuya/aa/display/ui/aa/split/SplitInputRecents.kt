package io.github.nitsuya.aa.display.ui.aa.split

import android.app.ActivityTaskManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Point
import android.os.Binder
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.core.graphics.drawable.toBitmap
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import io.github.nitsuya.aa.display.model.RecentTaskInfo
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.Instances

internal class SplitInputRecents(private val c: SplitDisplayController) {

    fun displayIdFor(pane: Int): Int? = when (pane) {
        SplitPane.PRIMARY -> c.primaryDisplayId.takeIf { it != Display.INVALID_DISPLAY }
        SplitPane.SECONDARY -> c.secondaryDisplayId.takeIf { it != Display.INVALID_DISPLAY }
        else -> null
    }

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

    fun isMediaKeyCode(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_RECORD,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MUTE -> true
            else -> false
        }
    }

    /** True when the display's top activity looks like an in-app live/room player. */
    fun isLiveStyleTopActivity(displayId: Int): Boolean {
        if (displayId == Display.INVALID_DISPLAY) return false
        val tasks = tryOrNull {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        }.orEmpty()
        val name = tasks.firstOrNull()?.topActivity?.className ?: return false
        // "LivePlay" is covered by case-insensitive "live".
        return name.contains("live", ignoreCase = true) ||
            name.contains("webcast", ignoreCase = true)
    }

    /**
     * Best-effort when the focused VD activity ignores injected MEDIA_* (e.g. Douyin
     * LivePlay after FeedPlayerSession is removed). Runs as system_server.
     * Do not use for NEXT/PREV on live rooms — that should [injectLiveRoomSwipe].
     */
    fun dispatchMediaKeyFallback(keyCode: Int) {
        if (!isMediaKeyCode(keyCode)) return
        try {
            val am = c.context.getSystemService(android.media.AudioManager::class.java) ?: return
            am.dispatchMediaKeyEvent(createKeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(createKeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (e: Throwable) {
            log(SplitDisplayController.TAG, "dispatchMediaKeyFallback failed key=$keyCode:", e)
        }
    }

    /**
     * Vertical fling on the current AA VirtualDisplay size (ratio / resize safe).
     * [next]=true → swipe up (next live room); false → swipe down (previous).
     * Keeps the gesture in the upper-mid video band so comment RecyclerViews rarely steal it.
     */
    fun injectLiveRoomSwipe(displayId: Int, next: Boolean): Boolean {
        val size = displaySizePx(displayId) ?: return false
        val w = size.x
        val h = size.y
        if (w < 2 || h < 2) return false
        // Slightly right of center: fewer overlays than the left host/avatar cluster.
        val x = w * 0.62f
        val yFrom = h * (if (next) 0.42f else 0.18f)
        val yTo = h * (if (next) 0.12f else 0.42f)
        val durationMs = 140L
        val steps = 6
        val downTime = SystemClock.uptimeMillis()
        var ok = injectMotion(displayId, MotionEvent.ACTION_DOWN, x, yFrom, downTime, downTime)
        for (i in 1..steps) {
            val t = downTime + durationMs * i / steps
            val y = yFrom + (yTo - yFrom) * i / steps
            ok = injectMotion(displayId, MotionEvent.ACTION_MOVE, x, y, downTime, t) && ok
        }
        ok = injectMotion(
            displayId,
            MotionEvent.ACTION_UP,
            x,
            yTo,
            downTime,
            downTime + durationMs
        ) && ok
        log(
            SplitDisplayController.TAG,
            "injectLiveRoomSwipe: display=$displayId ${w}x$h next=$next ok=$ok"
        )
        return ok
    }

    /** Live display metrics — always read at inject time (split ratio / VD resize). */
    fun displaySizePx(displayId: Int): Point? {
        if (displayId == Display.INVALID_DISPLAY) return null
        return tryOrNull {
            val display = Instances.displayManager.getDisplay(displayId) ?: return@tryOrNull null
            val point = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(point)
            if (point.x <= 0 || point.y <= 0) {
                @Suppress("DEPRECATION")
                display.getSize(point)
            }
            point.takeIf { it.x > 0 && it.y > 0 }
        }
    }

    private fun injectMotion(
        displayId: Int,
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
    ): Boolean {
        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            x,
            y,
            0
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        return try {
            injectInputEvent(displayId, event)
        } finally {
            event.recycle()
        }
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
        // IPC from the app process keeps the caller uid; ATMS requires MANAGE_ACTIVITY_TASKS.
        val identity = Binder.clearCallingIdentity()
        return try {
            val all = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
            all.asSequence().mapNotNull { taskInfo ->
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
                icon = downsampleForIpc(icon, MAX_RECENT_ICON_EDGE_PX)
                var label = taskDescription.label
                if (label == null) {
                    val activityInfo = if (Build.VERSION.SDK_INT >= 33) {
                        Instances.packageManager.getActivityInfo(
                            topActivity,
                            PackageManager.ComponentInfoFlags.of(0)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        Instances.packageManager.getActivityInfo(topActivity, 0)
                    }
                    label = activityInfo.loadLabel(Instances.packageManager).toString()
                }
                RecentTaskInfo(icon, taskInfo.taskId, label, topActivity.packageName)
            }.take(MAX_RECENT_PER_DISPLAY).toList()
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    companion object {
        /** Cap tasks per display to keep Binder payload bounded. */
        private const val MAX_RECENT_PER_DISPLAY = 12
        private const val MAX_RECENT_ICON_EDGE_PX = 96

        /** Soft-copy / scale icons so Recent IPC stays light. */
        private fun downsampleForIpc(src: Bitmap, maxEdgePx: Int): Bitmap {
            val maxDim = maxOf(src.width, src.height).coerceAtLeast(1)
            val needsScale = maxDim > maxEdgePx
            val needsCopy = src.config == Bitmap.Config.HARDWARE || needsScale
            if (!needsCopy) return src
            val soft = if (src.config == Bitmap.Config.HARDWARE) {
                src.copy(Bitmap.Config.ARGB_8888, false) ?: return src
            } else {
                src
            }
            if (!needsScale) {
                return if (soft !== src) soft else src
            }
            val scale = maxEdgePx.toFloat() / maxDim
            val w = (src.width * scale).toInt().coerceAtLeast(1)
            val h = (src.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(soft, w, h, true)
            if (soft !== src && soft !== scaled) soft.recycle()
            return scaled
        }
    }
}
