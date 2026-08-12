package io.github.nitsuya.aa.display

import android.content.Context
import android.os.Process
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import com.google.android.material.color.DynamicColors
import com.topjohnwu.superuser.Shell
import io.github.nitsuya.aa.display.xposed.CoreManager
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook


val IsSystemEnv by lazy {
    Process.myUid() == 1000
}
val CoreApi by lazy {
    // uid 1000 is shared by many OEM system apps (e.g. Samsung SLocation);
    // only the real system_server process hosts CoreManagerService.
    if (AndroidHook.isReadyForSystemHooks()) CoreManagerService.instance!!
    else CoreManager
}
lateinit var App : Application
class Application: android.app.Application() {
    init {
        App = this
        tryOrNull {
            Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(30))
        }
    }

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
    }
}
