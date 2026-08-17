package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.util.log

/**
 * Coolwalk empty media card is the **Dashboard** presentation
 * (≈304×460 on 800×480 HUs) showing "无法获享媒体内容".
 *
 * VD starve for name=Dashboard lives in [AaUiHook.rewriteVirtualDisplayArgs]
 * (create / Builder / resize). Here: disable [MEDIA_CAR_APP] and hide any
 * leftover Presentation window before first layout.
 */
object AaMediaPlaceholderHook : AaHook() {
    override val tagName: String = "AAD_AaMediaPlaceholderHook"

    private const val MEDIA_CAR_APP =
        "com.google.android.apps.auto.components.media.app.MediaCarAppService"

    override fun isSupportProcess(processName: String): Boolean {
        return processProjection == processName || processCar == processName
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        disableMediaCarApp()
        hideDashboardOnAddView()
    }

    private fun disableMediaCarApp() {
        runCatching {
            val ctx: Context = InitFields.appContext
            val cn = ComponentName(ctx.packageName, MEDIA_CAR_APP)
            val pm = ctx.packageManager
            val state = pm.getComponentEnabledSetting(cn)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
            ) {
                return
            }
            pm.setComponentEnabledSetting(
                cn,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            log(tagName, "disabled $MEDIA_CAR_APP")
        }.onFailure { e ->
            log(tagName, "disable $MEDIA_CAR_APP failed", e)
        }
    }

    private fun hideDashboardOnAddView() {
        try {
            findMethod(Class.forName("android.view.WindowManagerGlobal")) {
                name == "addView" && parameterCount >= 2
            }.hookBefore { param ->
                val root = param.args[0] as? View ?: return@hookBefore
                val lp = param.args.getOrNull(1) as? WindowManager.LayoutParams
                if (!isDashboardWindow(lp, root)) return@hookBefore
                hideDashboard(root, lp)
            }
        } catch (e: Throwable) {
            log(tagName, "hook WindowManagerGlobal.addView failed", e)
        }
    }

    private fun isDashboardWindow(lp: WindowManager.LayoutParams?, root: View): Boolean {
        val title = lp?.title?.toString().orEmpty()
        if (title.contains("Dashboard", ignoreCase = true)) return true
        if (lp != null && lp.type == 2030 /* TYPE_PRIVATE_PRESENTATION */) {
            val w = lp.width
            val h = lp.height
            if (w in 200..400 && h in 360..520 && h > w) return true
        }
        return root.javaClass.name.contains("Dashboard", ignoreCase = true)
    }

    private fun hideDashboard(root: View, lp: WindowManager.LayoutParams?) {
        root.alpha = 0f
        root.visibility = View.GONE
        root.isClickable = false
        root.isFocusable = false
        if (root is ViewGroup) {
            root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        if (lp != null) {
            lp.width = 0
            lp.height = 0
            lp.alpha = 0f
        }
        log(tagName, "hide Dashboard title=${lp?.title}")
    }
}
