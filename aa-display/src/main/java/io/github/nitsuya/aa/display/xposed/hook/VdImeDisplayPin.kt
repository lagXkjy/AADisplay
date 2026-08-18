package io.github.nitsuya.aa.display.xposed.hook

import android.view.Display
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.XC_MethodHook
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.lang.reflect.Field

/**
 * Keeps the soft-keyboard window on the AA VirtualDisplay that requested it.
 *
 * Rule: client on AA VD → IME window/token on that same VD. OEM paths that rewrite
 * the target to [Display.DEFAULT_DISPLAY] (e.g. Samsung Flip `isFolded` → force 0) are
 * corrected only when the intended display is one of ours.
 */
object VdImeDisplayPin {
    private const val TAG = "AAD_VdImeDisplayPin"
    private const val IMMS = "com.android.server.inputmethod.InputMethodManagerService"

    private var windowToBeAddedHook: XC_MethodHook.Unhook? = null
    private var displayIdToShowHook: XC_MethodHook.Unhook? = null
    private var hooked = false

    @Volatile private var clientDisplayIdField: Field? = null
    @Volatile private var curClientField: Field? = null
    @Volatile private var displayIdToShowImeField: Field? = null
    @Volatile private var clientFieldLookupDone = false

    fun ensureHooked() {
        if (!AndroidHook.isReadyForSystemHooks()) return
        if (hooked) return
        installHooks()
    }

    private fun installHooks() {
        hookGetDisplayIdOfInputMethodWindowToBeAdded()
        hookGetDisplayIdToShowImeLocked()
        hooked = windowToBeAddedHook != null || displayIdToShowHook != null
        if (hooked) {
            log(
                TAG,
                "hooked windowToBeAdded=${windowToBeAddedHook != null} " +
                    "displayIdToShow=${displayIdToShowHook != null}"
            )
        } else {
            log(TAG, "no IMMS IME-display hooks found")
        }
    }

    /**
     * Samsung OneUI: after [computeImeDisplayIdForTarget], this rewrites the IME display.
     * Folded Flip always returns 0 ("folded flip displayId=0") — undo that for AA VDs.
     */
    private fun hookGetDisplayIdOfInputMethodWindowToBeAdded() {
        val method = AndroidHook.findSystemMethod(IMMS) {
            name == "getDisplayIdOfInputMethodWindowToBeAdded" &&
                parameterTypes.size == 1 &&
                parameterTypes[0] == Int::class.javaPrimitiveType
        }
        if (method == null) {
            logDebug(TAG, "getDisplayIdOfInputMethodWindowToBeAdded not present")
            return
        }
        windowToBeAddedHook = method.hookAfter { param ->
            try {
                val intended = param.args[0] as? Int ?: return@hookAfter
                if (!CoreManagerService.isAaVirtualDisplay(intended)) return@hookAfter
                val actual = param.result as? Int ?: return@hookAfter
                if (actual == intended) return@hookAfter
                log(TAG, "IME window display $actual → $intended (AA VD)")
                pinShowDisplay(param.thisObject, intended)
                param.result = intended
            } catch (e: Throwable) {
                log(TAG, "windowToBeAdded hook failed", e)
            }
        }
    }

    /**
     * Universal safety net: [getDisplayIdToShowImeLocked] feeds token creation
     * ([InputMethodBindingController.addFreshWindowToken]). If the current client is on
     * an AA VD but the stored show-display is not, pin it back.
     */
    private fun hookGetDisplayIdToShowImeLocked() {
        val method = AndroidHook.findSystemMethod(IMMS) {
            name == "getDisplayIdToShowImeLocked" && parameterTypes.isEmpty()
        }
        if (method == null) {
            logDebug(TAG, "getDisplayIdToShowImeLocked not present")
            return
        }
        displayIdToShowHook = method.hookAfter { param ->
            try {
                val clientDisplay = clientDisplayId(param.thisObject) ?: return@hookAfter
                if (!CoreManagerService.isAaVirtualDisplay(clientDisplay)) return@hookAfter
                val actual = param.result as? Int ?: return@hookAfter
                if (actual == clientDisplay) return@hookAfter
                logDebug(TAG, "IME show display $actual → $clientDisplay (client AA VD)")
                pinShowDisplay(param.thisObject, clientDisplay)
                param.result = clientDisplay
            } catch (e: Throwable) {
                log(TAG, "displayIdToShow hook failed", e)
            }
        }
    }

    private fun pinShowDisplay(imms: Any, displayId: Int) {
        ensureClientFields(imms)
        try {
            displayIdToShowImeField?.setInt(imms, displayId)
        } catch (_: Throwable) {
        }
    }

    private fun clientDisplayId(imms: Any): Int? {
        ensureClientFields(imms)
        val client = try {
            curClientField?.get(imms)
        } catch (_: Throwable) {
            null
        } ?: return null
        return try {
            val id = clientDisplayIdField?.getInt(client) ?: return null
            id.takeIf { it != Display.INVALID_DISPLAY }
        } catch (_: Throwable) {
            null
        }
    }

    private fun ensureClientFields(imms: Any) {
        if (clientFieldLookupDone) return
        clientFieldLookupDone = true
        curClientField = findField(imms.javaClass, "mCurClient")
        displayIdToShowImeField = findField(imms.javaClass, "mDisplayIdToShowIme")
        val client = try {
            curClientField?.get(imms)
        } catch (_: Throwable) {
            null
        } ?: return
        clientDisplayIdField = findField(client.javaClass, "selfReportedDisplayId")
            ?: findField(client.javaClass, "displayId")
            ?: findField(client.javaClass, "mDisplayId")
    }

    private fun findField(cls: Class<*>, name: String): Field? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            try {
                return c.getDeclaredField(name).also { it.isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }
}
