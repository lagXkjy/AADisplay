package io.github.nitsuya.aa.display.xposed.util

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityManagerHidden
import android.app.IActivityTaskManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.input.IInputManager
import android.os.PowerManager
import android.os.PowerManagerHidden
import android.os.ServiceManager
import android.view.IWindowManager
import dev.rikka.tools.refine.Refine

@SuppressLint("StaticFieldLeak")
object Instances {
    val iInputManager:         IInputManager          by lazy { IInputManager.Stub.asInterface(ServiceManager.getService(Context.INPUT_SERVICE)) }
    val packageManager:        PackageManager         by lazy { mContext.packageManager }
    val displayManager:        DisplayManager         by lazy { mContext.getSystemService(DisplayManager::class.java) }

    val iWindowManager:        IWindowManager         by lazy { IWindowManager.Stub.asInterface(ServiceManager.getService(Context.WINDOW_SERVICE)) }

    val powerManager:          PowerManager           by lazy { mContext.getSystemService(PowerManager::class.java) }
    val powerManagerHidden:    PowerManagerHidden     by lazy { (Refine.unsafeCast(powerManager) as PowerManagerHidden) }

    val activityManager:       ActivityManager        by lazy { mContext.getSystemService(ActivityManager::class.java) }
    val activityManagerHidden: ActivityManagerHidden  by lazy { Refine.unsafeCast(activityManager) as ActivityManagerHidden }

    val iActivityTaskManager:  IActivityTaskManager   by lazy { IActivityTaskManager.Stub.asInterface(ServiceManager.getService(/* Context.ACTIVITY_TASK_SERVICE */"activity_task")) }

    private lateinit var mContext: Context

    fun init(context: Context) {
        mContext = context
    }
}
