package io.github.nitsuya.aa.display.xposed.hook

import android.view.WindowManager
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import io.github.nitsuya.aa.display.ui.aa.split.SplitPresentationGuard
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.util.log

/**
 * Reject [Presentation] windows / WindowContexts that target an AA pane owned by another
 * package. Douyin LivePlay uses MediaRouter → createWindowContext(TYPE_PRESENTATION)
 * → [attachWindowContextToDisplayArea]; `addWindow` often sees displayId=-1 afterward.
 */
object PanePresentationGuard {
    private const val TAG = "AAD_PanePresentationGuard"

    /** WindowManagerGlobal.ADD_INVALID_DISPLAY */
    private const val ADD_INVALID_DISPLAY = -9

    private var addWindowHook: XC_MethodHook.Unhook? = null
    private var attachContextHook: XC_MethodHook.Unhook? = null
    private var hooked = false

    fun ensureHooked() {
        if (!AndroidHook.isReadyForSystemHooks()) return
        if (hooked) return
        installHooks()
    }

    private fun installHooks() {
        hookAddWindow()
        hookAttachWindowContext()
        hooked = addWindowHook != null || attachContextHook != null
        if (hooked) {
            log(
                TAG,
                "hooked addWindow=${addWindowHook != null} " +
                    "attachContext=${attachContextHook != null}"
            )
        }
    }

    private fun hookAddWindow() {
        val method = AndroidHook.findSystemMethod("com.android.server.wm.WindowManagerService") {
            name == "addWindow" &&
                parameterTypes.any { it.name.endsWith("LayoutParams") }
        }
        if (method == null) {
            log(TAG, "WindowManagerService.addWindow not found")
            return
        }
        addWindowHook = method.hookBefore { param ->
            try {
                val attrsIdx = param.args.indexOfFirst { it is WindowManager.LayoutParams }
                if (attrsIdx < 0) return@hookBefore
                val attrs = param.args[attrsIdx] as WindowManager.LayoutParams
                if (!SplitPresentationGuard.isPresentationType(attrs.type)) return@hookBefore
                val displayId = resolveAaPresentationDisplayId(param.args, attrsIdx)
                    ?: return@hookBefore
                if (shouldBlockPresentation(displayId, param.args)) {
                    param.result = ADD_INVALID_DISPLAY
                }
            } catch (e: Throwable) {
                log(TAG, "addWindow hook failed", e)
            }
        }
    }

    /**
     * AOSP/OneUI: `attachWindowContextToDisplayArea(IBinder, type, displayId, Bundle)`.
     * This is where Douyin binds TYPE_PRESENTATION to the wrong AA VD before addWindow.
     */
    private fun hookAttachWindowContext() {
        val method = AndroidHook.findSystemMethod("com.android.server.wm.WindowManagerService") {
            name == "attachWindowContextToDisplayArea" &&
                parameterTypes.size >= 3 &&
                parameterTypes[1] == Int::class.javaPrimitiveType &&
                parameterTypes[2] == Int::class.javaPrimitiveType
        }
        if (method == null) {
            log(TAG, "attachWindowContextToDisplayArea not found")
            return
        }
        attachContextHook = method.hookBefore { param ->
            try {
                val type = param.args[1] as? Int ?: return@hookBefore
                if (!SplitPresentationGuard.isPresentationType(type)) return@hookBefore
                val displayId = param.args[2] as? Int ?: return@hookBefore
                if (!CoreManagerService.isAaVirtualDisplay(displayId)) return@hookBefore
                val ownerPkg = CoreManagerService.panePackageForDisplay(displayId) ?: return@hookBefore
                val callerPkg = SplitPresentationGuard.packageForUid(
                    CoreManagerService.systemContext,
                    android.os.Binder.getCallingUid()
                ) ?: return@hookBefore
                if (!SplitPresentationGuard.isForeignPresentationCaller(ownerPkg, callerPkg)) {
                    return@hookBefore
                }
                log(
                    TAG,
                    "block attachContext $callerPkg type=$type " +
                        "display=$displayId (owner=$ownerPkg)"
                )
                // IllegalArgumentException is what clients expect for a bad display/type.
                param.throwable = IllegalArgumentException(
                    "AADisplay: presentation display $displayId owned by $ownerPkg"
                )
            } catch (e: Throwable) {
                log(TAG, "attachContext hook failed", e)
            }
        }
    }

    private fun shouldBlockPresentation(displayId: Int, args: Array<Any?>): Boolean {
        val ownerPkg = CoreManagerService.panePackageForDisplay(displayId) ?: return false
        val callerPkg = resolvePresentationCallerPackage(args) ?: return false
        if (!SplitPresentationGuard.isForeignPresentationCaller(ownerPkg, callerPkg)) return false
        log(
            TAG,
            "block addWindow $callerPkg presentation on " +
                "display=$displayId (owner=$ownerPkg)"
        )
        return true
    }

    private fun resolvePresentationCallerPackage(args: Array<Any?>): String? {
        val session = args.firstOrNull { arg ->
            arg != null && arg.javaClass.name.endsWith("Session")
        }
        val uid = if (session != null) {
            session.javaClass.methods.firstOrNull { m ->
                m.name == "getUid" && m.parameterTypes.isEmpty()
            }?.invoke(session) as? Int
        } else {
            android.os.Binder.getCallingUid()
        } ?: return null
        return SplitPresentationGuard.packageForUid(CoreManagerService.systemContext, uid)
    }

    /**
     * OneUI WMS.addWindow: `(Session, IWindow, LayoutParams, III, …)` — do not assume
     * `attrsIdx+2` is always displayId (often userId=0). Prefer any AA VD id after attrs.
     */
    private fun resolveAaPresentationDisplayId(args: Array<Any?>, attrsIdx: Int): Int? {
        for (i in (attrsIdx + 1) until args.size) {
            val v = args[i] as? Int ?: continue
            if (CoreManagerService.isAaVirtualDisplay(v)) return v
        }
        val fallback = args.getOrNull(attrsIdx + 2) as? Int ?: return null
        return fallback.takeIf { CoreManagerService.isAaVirtualDisplay(it) }
    }
}
