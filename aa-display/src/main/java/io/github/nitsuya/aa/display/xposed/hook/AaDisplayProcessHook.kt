package io.github.nitsuya.aa.display.xposed.hook

import android.app.Application
import android.app.Instrumentation
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.AaDisplayPresentationResize

/**
 * AADisplay process: widen CarActivity presentation VD at create time only.
 * gearhead :projection owns LayoutInfo / content_bounds; this mirrors that truth
 * when the Car SDK allocates the presentation buffer in-app.
 */
object AaDisplayProcessHook : BaseHook() {
    override val tagName = "AAD_AaDisplayProcessHook"

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        var onCreateApplication: de.robv.android.xposed.XC_MethodHook.Unhook? = null
        onCreateApplication = findMethod(Instrumentation::class.java) {
            name == "callApplicationOnCreate" && parameterCount == 1 &&
                parameterTypes[0] == Application::class.java
        }.hookBefore {
            onCreateApplication?.unhook()
            EzXHelperInit.initAppContext()
            AaDisplayPresentationResize.hookDisplayManager()
        }
    }
}
