package io.github.nitsuya.aa.display.xposed.hook

import android.content.Context
import android.os.Binder
import android.provider.Settings
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
 *
 * Soft-keyboard height: with a BT hard keyboard attached, LatinIME's
 * [android.inputmethodservice.InputMethodService.onEvaluateInputViewShown] returns
 * false unless `show_ime_with_hard_keyboard` is on — yielding a visible IME token /
 * shell “收起键盘” chip with `mGivenContentInsets` height 0. While AA VDs are live,
 * temporarily enable that Secure setting (restore previous value on teardown).
 */
object VdImeDisplayPin {
    private const val TAG = "AAD_VdImeDisplayPin"
    private const val IMMS = "com.android.server.inputmethod.InputMethodManagerService"
    private const val IME_VIS = "com.android.server.inputmethod.ImeVisibilityStateComputer"
    private const val SHOW_IME_WITH_HARD_KEYBOARD = "show_ime_with_hard_keyboard"

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

    @Volatile private var softImeSettingOwned = false
    @Volatile private var softImeSettingPrevious: Int? = null

    fun ensureHooked() {
        if (!AndroidHook.isReadyForSystemHooks()) return
        if (installAttempted) return
        installAttempted = true
        installHooks()
    }

    /**
     * AA panes are live — allow soft IME alongside a connected BT hard keyboard.
     * No-op if the user already enabled [SHOW_IME_WITH_HARD_KEYBOARD].
     */
    fun onAaDisplaysActive(context: Context) {
        ensureHooked()
        if (softImeSettingOwned) return
        val cr = context.contentResolver ?: return
        val identity = Binder.clearCallingIdentity()
        try {
            val current = Settings.Secure.getInt(cr, SHOW_IME_WITH_HARD_KEYBOARD, 0)
            if (current != 0) return
            softImeSettingPrevious = 0
            Settings.Secure.putInt(cr, SHOW_IME_WITH_HARD_KEYBOARD, 1)
            softImeSettingOwned = true
            log(TAG, "enabled show_ime_with_hard_keyboard for AA session (BT hard kb)")
        } catch (e: Throwable) {
            log(TAG, "enable show_ime_with_hard_keyboard failed", e)
            softImeSettingPrevious = null
            softImeSettingOwned = false
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    /** Restore Secure setting after AA VDs are fully torn down. */
    fun onAaDisplaysInactive(context: Context) {
        if (!softImeSettingOwned) return
        val previous = softImeSettingPrevious
        softImeSettingOwned = false
        softImeSettingPrevious = null
        if (previous == null) return
        val cr = context.contentResolver ?: return
        val identity = Binder.clearCallingIdentity()
        try {
            Settings.Secure.putInt(cr, SHOW_IME_WITH_HARD_KEYBOARD, previous)
            log(TAG, "restored show_ime_with_hard_keyboard=$previous")
        } catch (e: Throwable) {
            log(TAG, "restore show_ime_with_hard_keyboard failed", e)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
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
                // FALLBACK → DEFAULT_DISPLAY(0): pin back to the pane.
                // Do NOT rewrite INVALID_DISPLAY(-1) from HIDE — that yields mInputShown
                // without an IME window (shell shows “hide keyboard” with no keyboard).
                if (actual != Display.DEFAULT_DISPLAY) return@hookAfter
                logDebug(TAG, "computeImeDisplay $actual → $target (AA VD)")
                param.result = target
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Without system decorations, [DisplayContent.getImePolicy] often returns FALLBACK/HIDE
     * *without* calling [WindowManagerService.getDisplayImePolicy]. Hooking only WMS is not
     * enough — must pin both so computeImeDisplayId keeps the soft keyboard on the pane.
     */
    private fun hookDisplayImePolicy() {
        var hookedAny = false
        AndroidHook.findSystemMethod(
            "com.android.server.wm.DisplayContent",
            findSuper = true,
        ) {
            name == "getImePolicy" && parameterTypes.isEmpty()
        }?.let { method ->
            displayImePolicyHook = method.hookAfter { param ->
                rewriteImePolicyResult(
                    displayIdOfDisplayContent(param.thisObject) ?: return@hookAfter,
                    param,
                )
            }
            hookedAny = true
        }
        AndroidHook.findSystemMethod(
            "com.android.server.wm.WindowManagerService",
            findSuper = true,
        ) {
            name == "getDisplayImePolicy" &&
                parameterTypes.size == 1 &&
                parameterTypes[0] == Int::class.javaPrimitiveType
        }?.let { method ->
            val unhook = method.hookAfter { param ->
                rewriteImePolicyResult(param.args[0] as? Int ?: return@hookAfter, param)
            }
            if (displayImePolicyHook == null) displayImePolicyHook = unhook
            hookedAny = true
        }
        if (!hookedAny) {
            logDebug(TAG, "getDisplayImePolicy/getImePolicy not present")
        }
    }

    private fun rewriteImePolicyResult(displayId: Int, param: XC_MethodHook.MethodHookParam) {
        try {
            if (!CoreManagerService.hasAaVirtualDisplays()) return
            if (!CoreManagerService.isAaVirtualDisplay(displayId)) return
            val actual = param.result as? Int ?: return
            // 0 LOCAL already ok. 1 FALLBACK (common when decorations off) → LOCAL.
            // Leave 2 HIDE alone — forcing LOCAL caused show requests with no IME window.
            if (actual != 1) return
            logDebug(TAG, "imePolicy display=$displayId FALLBACK → LOCAL(0)")
            param.result = 0 // DISPLAY_IME_POLICY_LOCAL
        } catch (_: Throwable) {
        }
    }

    private fun displayIdOfDisplayContent(dc: Any): Int? {
        findField(dc.javaClass, "mDisplayId")?.let { f ->
            return runCatching { f.getInt(dc) }.getOrNull()
        }
        return runCatching {
            dc.javaClass.methods.firstOrNull { m ->
                m.name == "getDisplayId" && m.parameterTypes.isEmpty()
            }?.invoke(dc) as? Int
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
        // Retry when the first call happened before mCurClient existed — otherwise
        // clientDisplayIdField stays null forever and getDisplayIdToShowIme never pins.
        if (clientFieldLookupDone && curClientField != null && clientDisplayIdField != null) return
        if (!clientFieldLookupDone) {
            clientFieldLookupDone = true
            curClientField = findField(imms.javaClass, "mCurClient")
            displayIdToShowImeField = findField(imms.javaClass, "mDisplayIdToShowIme")
                ?: findField(imms.javaClass, "mDisplayIdToShowImeLocked")
        }
        if (clientDisplayIdField != null) return
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
