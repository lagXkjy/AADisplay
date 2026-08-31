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
 * Rule: client on AA VD → IME window/token on that same VD. OEM / A16 paths that
 * rewrite the target to [Display.DEFAULT_DISPLAY] (fallback when system decorations
 * are off, or Samsung Flip `isFolded` → 0) are corrected only for our pane VDs.
 */
object VdImeDisplayPin {
    private const val TAG = "AAD_VdImeDisplayPin"
    private const val IMMS = "com.android.server.inputmethod.InputMethodManagerService"
    private const val IME_VIS = "com.android.server.inputmethod.ImeVisibilityStateComputer"

    private var windowToBeAddedHook: XC_MethodHook.Unhook? = null
    private var displayIdToShowHook: XC_MethodHook.Unhook? = null
    private var computeImeDisplayHook: XC_MethodHook.Unhook? = null
    private var displayImePolicyHook: XC_MethodHook.Unhook? = null
    private var hooked = false
    private var installAttempted = false

    @Volatile private var clientDisplayIdField: Field? = null
    @Volatile private var curClientField: Field? = null
    @Volatile private var displayIdToShowImeField: Field? = null
    @Volatile private var clientFieldLookupDone = false

    fun ensureHooked() {
        if (!AndroidHook.isReadyForSystemHooks()) return
        if (installAttempted) return
        installAttempted = true
        installHooks()
    }

    private fun installHooks() {
        runCatching { hookGetDisplayIdOfInputMethodWindowToBeAdded() }
            .onFailure { log(TAG, "windowToBeAdded install failed", it) }
        runCatching { hookGetDisplayIdToShowIme() }
            .onFailure { log(TAG, "displayIdToShow install failed", it) }
        runCatching { hookComputeImeDisplayIdForTarget() }
            .onFailure { log(TAG, "computeImeDisplayId install failed", it) }
        runCatching { hookDisplayImePolicy() }
            .onFailure { log(TAG, "displayImePolicy install failed", it) }
        hooked = windowToBeAddedHook != null ||
            displayIdToShowHook != null ||
            computeImeDisplayHook != null ||
            displayImePolicyHook != null
        if (hooked) {
            log(
                TAG,
                "hooked windowToBeAdded=${windowToBeAddedHook != null} " +
                    "displayIdToShow=${displayIdToShowHook != null} " +
                    "computeIme=${computeImeDisplayHook != null} " +
                    "imePolicy=${displayImePolicyHook != null}",
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
                if (!CoreManagerService.hasAaVirtualDisplays()) return@hookAfter
                val intended = param.args[0] as? Int ?: return@hookAfter
                if (!CoreManagerService.isAaVirtualDisplay(intended)) return@hookAfter
                val actual = param.result as? Int ?: return@hookAfter
                if (actual == intended) return@hookAfter
                logDebug(TAG, "IME window display $actual → $intended (AA VD)")
                pinShowDisplay(param.thisObject, intended)
                param.result = intended
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * A13: getDisplayIdToShowImeLocked. A16: getDisplayIdToShowIme (no Locked).
     * Feeds token creation — if client is on an AA VD, pin show-display back.
     */
    private fun hookGetDisplayIdToShowIme() {
        val method = AndroidHook.findSystemMethod(IMMS) {
            (name == "getDisplayIdToShowImeLocked" || name == "getDisplayIdToShowIme") &&
                parameterTypes.isEmpty()
        } ?: AndroidHook.findSystemMethod(
            "com.android.server.inputmethod.InputMethodBindingController",
        ) {
            (name == "getDisplayIdToShowIme" || name == "getDisplayIdToShowImeLocked") &&
                parameterTypes.isEmpty()
        }
        if (method == null) {
            logDebug(TAG, "getDisplayIdToShowIme* not present")
            return
        }
        displayIdToShowHook = method.hookAfter { param ->
            try {
                if (!CoreManagerService.hasAaVirtualDisplays()) return@hookAfter
                // BindingController may not hold mCurClient — try IMMS via field walk.
                val host = param.thisObject
                val clientDisplay = clientDisplayId(host)
                    ?: clientDisplayIdFromBindingController(host)
                    ?: return@hookAfter
                if (!CoreManagerService.isAaVirtualDisplay(clientDisplay)) return@hookAfter
                val actual = param.result as? Int ?: return@hookAfter
                if (actual == clientDisplay) return@hookAfter
                logDebug(TAG, "IME show display $actual → $clientDisplay (client AA VD)")
                pinShowDisplay(host, clientDisplay)
                param.result = clientDisplay
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * A16 primary path: static IMMS.computeImeDisplayIdForTarget(displayId, validator)
     * returns DEFAULT / INVALID when decorations are off or policy is FALLBACK/HIDE.
     * Force LOCAL for our pane VDs so the soft keyboard token lands on the car display.
     */
    private fun hookComputeImeDisplayIdForTarget() {
        val method = AndroidHook.findSystemMethod(IMMS) {
            name == "computeImeDisplayIdForTarget" &&
                parameterTypes.isNotEmpty() &&
                parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: AndroidHook.findSystemMethod(IME_VIS) {
            name == "computeImeDisplayId" &&
                parameterTypes.size >= 2 &&
                parameterTypes[1] == Int::class.javaPrimitiveType
        }
        if (method == null) {
            logDebug(TAG, "computeImeDisplayId* not present")
            return
        }
        val displayArgIndex = if (method.parameterTypes[0] == Int::class.javaPrimitiveType) 0 else 1
        computeImeDisplayHook = method.hookAfter { param ->
            try {
                if (!CoreManagerService.hasAaVirtualDisplays()) return@hookAfter
                val target = param.args.getOrNull(displayArgIndex) as? Int ?: return@hookAfter
                if (!CoreManagerService.isAaVirtualDisplay(target)) return@hookAfter
                val actual = param.result as? Int ?: return@hookAfter
                if (actual == target) return@hookAfter
                // INVALID_DISPLAY (-1) or fallback DEFAULT(0) → keep IME on the pane.
                logDebug(TAG, "computeImeDisplay $actual → $target (AA VD)")
                param.result = target
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * WMS getDisplayImePolicy: without system decorations A16 often returns FALLBACK/HIDE.
     * Force DISPLAY_IME_POLICY_LOCAL (0) for AA panes so computeImeDisplayId keeps them.
     */
    private fun hookDisplayImePolicy() {
        val method = AndroidHook.findSystemMethod(
            "com.android.server.wm.WindowManagerService",
            findSuper = true,
        ) {
            name == "getDisplayImePolicy" &&
                parameterTypes.size == 1 &&
                parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: AndroidHook.findSystemMethod(
            "com.android.server.wm.DisplayContent",
            findSuper = true,
        ) {
            name == "getImePolicy" && parameterTypes.isEmpty()
        }
        if (method == null) {
            logDebug(TAG, "getDisplayImePolicy/getImePolicy not present")
            return
        }
        displayImePolicyHook = method.hookAfter { param ->
            try {
                if (!CoreManagerService.hasAaVirtualDisplays()) return@hookAfter
                val displayId = when {
                    method.parameterTypes.isEmpty() -> {
                        // DisplayContent.getImePolicy — resolve display id from this.
                        displayIdOfDisplayContent(param.thisObject) ?: return@hookAfter
                    }
                    else -> param.args[0] as? Int ?: return@hookAfter
                }
                if (!CoreManagerService.isAaVirtualDisplay(displayId)) return@hookAfter
                val actual = param.result as? Int ?: return@hookAfter
                if (actual == 0) return@hookAfter // already LOCAL
                logDebug(TAG, "imePolicy display=$displayId $actual → LOCAL(0)")
                param.result = 0 // DISPLAY_IME_POLICY_LOCAL
            } catch (_: Throwable) {
            }
        }
    }

    private fun displayIdOfDisplayContent(dc: Any): Int? {
        return runCatching {
            findField(dc.javaClass, "mDisplayId")?.getInt(dc)
        }.getOrNull()
    }

    private fun pinShowDisplay(host: Any, displayId: Int) {
        ensureClientFields(host)
        try {
            displayIdToShowImeField?.setInt(host, displayId)
        } catch (_: Throwable) {
        }
        // BindingController may nest IMMS — try parent field.
        runCatching {
            val imms = findField(host.javaClass, "mService")?.get(host)
                ?: findField(host.javaClass, "mImms")?.get(host)
            if (imms != null) {
                findField(imms.javaClass, "mDisplayIdToShowIme")?.setInt(imms, displayId)
            }
        }
    }

    private fun clientDisplayIdFromBindingController(host: Any): Int? {
        return runCatching {
            val imms = findField(host.javaClass, "mService")?.get(host)
                ?: findField(host.javaClass, "mImms")?.get(host)
                ?: return null
            clientDisplayId(imms)
        }.getOrNull()
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
        if (clientFieldLookupDone && curClientField != null) return
        clientFieldLookupDone = true
        curClientField = findField(imms.javaClass, "mCurClient")
        displayIdToShowImeField = findField(imms.javaClass, "mDisplayIdToShowIme")
        val client = try {
            curClientField?.get(imms)
        } catch (_: Throwable) {
            null
        } ?: return
        clientDisplayIdField = findField(client.javaClass, "selfReportedDisplayId")
            ?: findField(client.javaClass, "mSelfReportedDisplayId")
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
