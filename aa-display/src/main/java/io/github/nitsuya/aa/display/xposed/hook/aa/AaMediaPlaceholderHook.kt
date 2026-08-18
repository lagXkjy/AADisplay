package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.util.log
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Coolwalk empty media card is the **Dashboard** presentation
 * (≈304×460 on 800×480 HUs) showing "无法获享媒体内容".
 *
 * VD starve for name=Dashboard lives in [AaUiHook.rewriteVirtualDisplayArgs]
 * (create / Builder / resize). Here: disable [MEDIA_CAR_APP], hide leftover
 * Presentation windows, and swallow Coolwalk's Dashboard cover assert if it
 * throws after `content_bounds` expand.
 */
object AaMediaPlaceholderHook : AaHook() {
    override val tagName: String = "AAD_AaMediaPlaceholderHook"

    private const val MEDIA_CAR_APP =
        "com.google.android.apps.auto.components.media.app.MediaCarAppService"

    private var insetAssertMethods: List<Method> = emptyList()

    override fun isSupportProcess(processName: String): Boolean {
        return processProjection == processName || processCar == processName
    }

    override fun loadDexClass(bridge: DexKitBridge, lpparam: XC_LoadPackage.LoadPackageParam) {
        insetAssertMethods = listOf(
            "does not mostly cover or provide a hint for one side of",
            "does not fully cover",
        ).flatMap { needle ->
            runCatching {
                bridge.findMethod {
                    matcher { usingStrings(needle) }
                }.mapNotNull { md ->
                    runCatching { md.getMethodInstance(lpparam.classLoader) }.getOrNull()
                }
            }.getOrDefault(emptyList())
        }.distinctBy {
            "${it.declaringClass.name}#${it.name}#${it.parameterTypes.joinToString { p -> p.name }}"
        }
        log(tagName, "inset assert methods=${insetAssertMethods.size}")
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        disableMediaCarApp()
        hideDashboardOnAddView()
        suppressDashboardCoverAssert()
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

    /** Original inset logic runs; only Dashboard cover [IllegalStateException] is swallowed. */
    private fun suppressDashboardCoverAssert() {
        if (insetAssertMethods.isEmpty()) {
            log(tagName, "no Coolwalk inset-assert methods; cover crash not guarded")
            return
        }
        var hooked = 0
        for (method in insetAssertMethods) {
            try {
                XposedBridge.hookMethod(method, object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any? {
                        return try {
                            XposedBridge.invokeOriginalMethod(
                                param.method,
                                param.thisObject,
                                param.args,
                            )
                        } catch (e: InvocationTargetException) {
                            val cause = e.cause ?: e
                            if (!isDashboardCoverAssert(cause)) throw cause
                            log(
                                tagName,
                                "suppress ${method.declaringClass.simpleName}#${method.name} " +
                                    "Dashboard cover assert"
                            )
                            dummyInsetResult(method)
                        }
                    }
                })
                hooked++
            } catch (e: Throwable) {
                log(tagName, "hook inset ${method.declaringClass.name}#${method.name} failed", e)
            }
        }
        log(tagName, "hooked inset assert methods=$hooked/${insetAssertMethods.size}")
    }

    private fun isDashboardCoverAssert(t: Throwable): Boolean {
        if (t !is IllegalStateException) return false
        val msg = t.message ?: return false
        if (!msg.contains("Dashboard")) return false
        return msg.contains("does not mostly cover") || msg.contains("does not fully cover")
    }

    private fun dummyInsetResult(method: Method): Any? {
        val rt = method.returnType
        if (rt == Void.TYPE || rt == Void::class.java) return null
        if (rt == Rect::class.java) return Rect()
        return null
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
