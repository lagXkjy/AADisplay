package io.github.nitsuya.aa.display.ui.aa.split

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.github.kyuubiran.ezxhelper.utils.newInstance

/** Shared [Context.startActivityAsUser] reflection for cold launch vs task reorder. */
internal object AaLaunchHelper {

    enum class Mode {
        /** New root on target VD ([Intent.FLAG_ACTIVITY_MULTIPLE_TASK]). */
        COLD,
        /** Reorder existing root ([REORDER_TO_FRONT] | [SINGLE_TOP]). */
        REORDER,
    }

    fun startActivityOnDisplay(
        context: Context,
        component: ComponentName,
        userId: Int,
        displayId: Int,
        mode: Mode,
        addLauncherCategory: Boolean = false,
    ): Boolean {
        return try {
            context.invokeMethod(
                "startActivityAsUser",
                args(
                    Intent().apply {
                        this.component = component
                        `package` = component.packageName
                        action = Intent.ACTION_MAIN
                        if (addLauncherCategory) {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                        }
                        flags = when (mode) {
                            Mode.COLD -> Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                            Mode.REORDER -> Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                    },
                    android.app.ActivityOptions.makeBasic().apply {
                        launchDisplayId = displayId
                        try {
                            invokeMethod(
                                "setCallerDisplayId",
                                args(displayId),
                                argTypes(Integer.TYPE),
                            )
                        } catch (_: Throwable) {
                        }
                    }.toBundle(),
                    UserHandle::class.java.newInstance(
                        args(userId),
                        argTypes(Integer.TYPE),
                    ),
                ),
                argTypes(Intent::class.java, Bundle::class.java, UserHandle::class.java),
            )
            true
        } catch (_: Throwable) {
            false
        }
    }
}
