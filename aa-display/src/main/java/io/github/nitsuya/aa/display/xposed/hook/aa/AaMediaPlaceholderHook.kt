package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.util.log
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method

/**
 * Coolwalk empty media card is a Dashboard presentation window
 * (≈304×460 on 800×480 HUs) showing "无法获享媒体内容".
 * Hide it on addView; disable / block [MEDIA_CAR_APP] as insurance.
 */
object AaMediaPlaceholderHook : AaHook() {
    override val tagName: String = "AAD_AaMediaPlaceholderHook"

    private const val MEDIA_CAR_APP =
        "com.google.android.apps.auto.components.media.app.MediaCarAppService"

    private lateinit var projectionStartMethods: List<Method>

    override fun isSupportProcess(processName: String): Boolean {
        return processProjection == processName || processCar == processName
    }

    override fun loadDexClass(bridge: DexKitBridge, lpparam: XC_LoadPackage.LoadPackageParam) {
        projectionStartMethods = AaCarAppLaunchGuard.findProjectionStartMethods(
            bridge,
            lpparam.classLoader,
        )
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        AaCarAppLaunchGuard.disableComponent(tagName, MEDIA_CAR_APP)
        AaCarAppLaunchGuard.installLaunchBlocks(MEDIA_CAR_APP, projectionStartMethods)
        hookDashboardWindowHide()
    }

    private fun hookDashboardWindowHide() {
        try {
            findMethod(Class.forName("android.view.WindowManagerGlobal")) {
                name == "addView" && parameterCount >= 2
            }.hookAfter { param ->
                val root = param.args[0] as? View ?: return@hookAfter
                val lp = param.args.getOrNull(1) as? WindowManager.LayoutParams
                if (!isDashboardWindow(lp, root)) return@hookAfter
                hideDashboard(root, lp)
            }
        } catch (e: Throwable) {
            log(tagName, "hook WindowManagerGlobal.addView failed", e)
        }

        // Title may be assigned after addView; keep suppressing visibility flips.
        try {
            findMethod(View::class.java) {
                name == "setVisibility" &&
                    parameterCount == 1 &&
                    parameterTypes[0] == Int::class.javaPrimitiveType
            }.hookBefore { param ->
                val view = param.thisObject as? View ?: return@hookBefore
                if (!isDashboardRoot(view)) return@hookBefore
                if (param.args[0] as Int != View.GONE) {
                    param.args[0] = View.GONE
                }
            }
        } catch (e: Throwable) {
            log(tagName, "hook setVisibility for Dashboard failed", e)
        }
    }

    private fun isDashboardWindow(lp: WindowManager.LayoutParams?, root: View): Boolean {
        val title = lp?.title?.toString().orEmpty()
        if (title.contains("Dashboard", ignoreCase = true)) return true
        // Fallback when title is late: tall PRIVATE_PRESENTATION media card on HU.
        if (lp != null && lp.type == 2030 /* TYPE_PRIVATE_PRESENTATION */) {
            val w = lp.width
            val h = lp.height
            if (w in 200..400 && h in 360..520 && h > w) return true
        }
        return root.javaClass.name.contains("Dashboard", ignoreCase = true)
    }

    private fun isDashboardRoot(view: View): Boolean {
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return false
        return isDashboardWindow(lp, view)
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
            runCatching {
                val wm = root.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.updateViewLayout(root, lp)
            }
        }
        log(tagName, "hide Dashboard title=${lp?.title}")
    }
}
