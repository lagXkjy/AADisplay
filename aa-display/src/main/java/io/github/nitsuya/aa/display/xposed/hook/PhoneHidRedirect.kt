package io.github.nitsuya.aa.display.xposed.hook

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.os.ServiceManager
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.InputFilter
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import io.github.nitsuya.aa.display.ui.aa.split.HidSplitLayout
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Redirects phone-paired Bluetooth keyboard / mouse onto the focused AA VirtualDisplay
 * while an AA session is live (not during Delay Destroy).
 *
 * OEM-safe: every install / session toggle / hot-path is isolated with try/catch.
 * Missing hooks or InputFilter → feature degrades (keys-only or off); never crashes
 * system_server. Steering-wheel media path is independent.
 *
 * - Keys: [PhoneWindowManager.interceptKeyBeforeQueueing] consume + inject (fallback).
 * - Pointer: [InputManagerService.setInputFilter] steal mouse/touchpad; primary-button
 *   gestures become [SOURCE_TOUCHSCREEN] inject. Best-effort pointer-display API.
 */
object PhoneHidRedirect {
    private const val TAG = "AAD_PhoneHid"
    /** [lastPointerBindPane] sentinel while the cursor is on the AaDisplay shell VD. */
    private const val POINTER_BIND_SHELL = -2
    /** Relative overshoot at top/bottom (cross gate) before hopping to the sibling VD. */
    private const val CROSS_HOLD_PX = 28f

    private var installAttempted = false
    private var keyHook: XC_MethodHook.Unhook? = null

    @Volatile private var sessionLive = false
    @Volatile private var filterInstalled = false
    /** After a hard failure constructing/installing the filter, do not retry this boot. */
    @Volatile private var filterPermanentlyFailed = false

    private var imsInstance: Any? = null
    private var setInputFilterMethod: Method? = null
    private var inputFilterField: Field? = null
    private var setPointerDisplayIdMethod: Method? = null
    private var setPointerIconVisibleMethod: Method? = null
    private var forceHideCursorMethod: Method? = null
    private var pointerApiTarget: Any? = null
    private var pointerIconTarget: Any? = null
    private var forceHideCursorTarget: Any? = null
    private var viewportHook: XC_MethodHook.Unhook? = null
    private var setDisplayViewportsMethod: Method? = null

    private var savedFilter: Any? = null
    private var hidFilter: InputFilter? = null

    private var cursorX = 0f
    private var cursorY = 0f
    /** Always pane-local (within the bound VD), never full-HU canvas. */
    private var cursorPane: Int = SplitPane.PRIMARY
    private var cursorInitialized = false
    private var pointerDown = false
    private var pointerDownTime = 0L
    private var lastTargetDisplayId = -1
    private var lastPointerBindPane: Int = -1
    /**
     * Extra relative travel past a pane edge required before hopping VD.
     * While holding the edge, LMB drives the shell divider instead of jumping.
     */
    private var seamHoldPx = 0f
    /** After a pane hop, ignore reverse edge-cross briefly (stops 16↔17 oscillation). */
    private var seamCooldownUntil = 0L
    /** Mouse gesture owned by AaDisplay divider (ratio / Recents / swap). */
    private var dividerGestureActive = false
    private var dividerDownTime = 0L
    /** Divider drag position in VD-profile canvas space ([HidSplitLayout.totalW/H]). */
    private var dividerX = 0f
    private var dividerY = 0f
    /** Gesture owned by shell overlay (Recents / picker). */
    private var shellGestureActive = false
    private var shellDownTime = 0L
    private var shellX = 0f
    private var shellY = 0f

    fun ensureHooked() {
        if (!AndroidHook.isReadyForSystemHooks()) return
        if (installAttempted) return
        installAttempted = true
        runCatching {
            resolveInputManager()
            hookKeyIntercept()
            hookDisplayViewports()
        }.onFailure { log(TAG, "ensureHooked failed (HID redirect disabled)", it) }
        log(
            TAG,
            "ready keyHook=${keyHook != null} setInputFilter=${setInputFilterMethod != null} " +
                "pointerApi=${setPointerDisplayIdMethod != null} " +
                "hideCursor=${setPointerIconVisibleMethod != null} " +
                "forceHide=${forceHideCursorMethod != null} " +
                "viewportHook=${viewportHook != null}"
        )
    }

    fun onShellCaptureChanged(capture: Boolean) {
        if (!sessionLive) return
        runCatching {
            if (capture) {
                cancelPaneFinger()
                dividerGestureActive = false
                seamHoldPx = 0f
                val layout = runCatching { CoreManagerService.hidSplitLayout() }.getOrNull()
                if (layout != null && SplitPane.isValid(cursorPane)) {
                    val (cx, cy) = layout.toCanvas(cursorPane, cursorX, cursorY)
                    shellX = cx
                    shellY = cy
                }
                applyPointerDisplayToShell()
            } else {
                shellGestureActive = false
                applyPointerDisplay()
            }
        }.onFailure { logDebug(TAG, "onShellCaptureChanged($capture): ${it.message}") }
    }

    /** Called from DisplaySessionPolicy / create when AA UI is attached vs Delay Destroy. */
    fun onSessionLiveChanged(live: Boolean) {
        runCatching {
            if (!installAttempted) ensureHooked()
            if (sessionLive == live) {
                if (live) applyPointerDisplay()
                return@runCatching
            }
            sessionLive = live
            if (live) {
                cursorInitialized = false
                pointerDown = false
                seamHoldPx = 0f
                lastPointerBindPane = -1
                dividerGestureActive = false
                shellGestureActive = false
                installInputFilter()
                applyPointerDisplay()
                invokeForceHideCursor(true)
                requestViewportRefresh()
                log(
                    TAG,
                    "session live — HID on (filter=$filterInstalled keyHook=${keyHook != null})"
                )
            } else {
                pointerDown = false
                seamHoldPx = 0f
                dividerGestureActive = false
                shellGestureActive = false
                runCatching { CoreManagerService.clearAaUiShellCapture() }
                uninstallInputFilter()
                invokeForceHideCursor(false)
                restorePointerDisplay()
                log(TAG, "session not live — HID redirect off")
            }
        }.onFailure { log(TAG, "onSessionLiveChanged($live) failed", it) }
    }

    fun isRedirectActive(): Boolean =
        try {
            sessionLive && CoreManagerService.isAaSessionLive()
        } catch (_: Throwable) {
            false
        }

    private fun resolveInputManager() {
        val identity = android.os.Binder.clearCallingIdentity()
        try {
            val binder = ServiceManager.getService(Context.INPUT_SERVICE) ?: return
            imsInstance = binder
            val imsClass = binder.javaClass
            setInputFilterMethod = findMethodRecursive(imsClass) { m ->
                m.name == "setInputFilter" && m.parameterTypes.size == 1
            }
            inputFilterField = runCatching {
                findFieldRecursive(imsClass, "mInputFilter")?.also { it.isAccessible = true }
            }.getOrNull()
            resolvePointerApi(binder)
        } catch (e: Throwable) {
            log(TAG, "resolveInputManager failed", e)
        } finally {
            android.os.Binder.restoreCallingIdentity(identity)
        }
    }

    /**
     * Samsung W7023 ADB: Cursor mapper paints the sprite on the active physical panel
     * (cover displayId=1 folded / main displayId=0 open) *before* InputFilter. Must bind
     * pointer display to the AA VD; otherwise the cursor stays on the phone even when
     * clicks are stolen. services.jar has setVirtualMousePointerDisplayId but it may live
     * on IMS / LocalService with OEM signatures — search broadly.
     */
    private fun resolvePointerApi(ims: Any) {
        runCatching {
            val candidates = mutableListOf<Any>(ims)
            val localServices = AndroidHook.loadSystemClass("com.android.server.LocalServices")
            val getService = localServices?.methods?.firstOrNull { m ->
                m.name == "getService" &&
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == Class::class.java
            }
            val internalClass =
                AndroidHook.loadSystemClass("com.android.server.input.InputManagerInternal")
            if (getService != null && internalClass != null) {
                runCatching { getService.invoke(null, internalClass) }.getOrNull()?.let {
                    candidates += it
                }
            }
            // Also try nested LocalService field on IMS.
            runCatching {
                findFieldRecursive(ims.javaClass, "mLocalService")?.also { it.isAccessible = true }
                    ?.get(ims)
            }.getOrNull()?.let { candidates += it }

            for (target in candidates) {
                if (setPointerDisplayIdMethod == null) {
                    setPointerDisplayIdMethod = findMethodRecursive(target.javaClass) { m ->
                        m.name == "setVirtualMousePointerDisplayId" ||
                            m.name == "setVirtualMousePointerDisplayIdBlocking" ||
                            m.name == "setPointerDisplayId" ||
                            m.name == "setDefaultMouseDisplayId"
                    }?.also {
                        it.isAccessible = true
                        pointerApiTarget = target
                    }
                }
                if (setPointerIconVisibleMethod == null) {
                    setPointerIconVisibleMethod = findMethodRecursive(target.javaClass) { m ->
                        m.name == "setPointerIconVisible" ||
                            m.name == "setPointerIconVisibility"
                    }?.also {
                        it.isAccessible = true
                        pointerIconTarget = target
                    }
                }
                if (forceHideCursorMethod == null) {
                    forceHideCursorMethod = findMethodRecursive(target.javaClass) { m ->
                        m.name == "forceHideCursor" ||
                            m.name.equals("setForceHideCursor", ignoreCase = true)
                    }?.also {
                        it.isAccessible = true
                        forceHideCursorTarget = target
                    }
                }
                if (setDisplayViewportsMethod == null) {
                    setDisplayViewportsMethod = findMethodRecursive(target.javaClass) { m ->
                        m.name == "setDisplayViewports" ||
                            m.name == "setDisplayViewportsInternal"
                    }?.also { it.isAccessible = true }
                }
            }
            log(
                TAG,
                "pointer resolve: displayMethod=${setPointerDisplayIdMethod?.name} " +
                    "on=${pointerApiTarget?.javaClass?.simpleName} " +
                    "iconMethod=${setPointerIconVisibleMethod?.name} " +
                    "forceHide=${forceHideCursorMethod?.name}"
            )
        }.onFailure { log(TAG, "resolvePointerApi failed", it) }
    }

    /**
     * W7023 ADB: AA pane VDs were touch NONE and absent from Input viewports, so
     * mOverriddenPointerDisplayId=16 still painted the sprite on display 0. Inject our
     * pane viewports into every WM→IMS viewport update while the session is live.
     */
    private fun hookDisplayViewports() {
        val ims = imsInstance ?: return
        val method = setDisplayViewportsMethod
            ?: findMethodRecursive(ims.javaClass) { m ->
                m.name == "setDisplayViewports" || m.name == "setDisplayViewportsInternal"
            }?.also {
                it.isAccessible = true
                setDisplayViewportsMethod = it
            }
            ?: return
        if (viewportHook != null) return
        viewportHook = method.hookBefore { param ->
            try {
                if (!sessionLive) return@hookBefore
                val arg = param.args.getOrNull(0) ?: return@hookBefore
                val list = when (arg) {
                    is MutableList<*> -> @Suppress("UNCHECKED_CAST") (arg as MutableList<Any?>)
                    is List<*> -> {
                        val copy = ArrayList<Any?>(arg)
                        param.args[0] = copy
                        copy
                    }
                    else -> return@hookBefore
                }
                injectAaViewportsInto(list)
            } catch (_: Throwable) {
            }
        }
        log(TAG, "hooked ${method.name} for AA pointer viewports")
    }

    private fun injectAaViewportsInto(list: MutableList<Any?>) {
        val primary = CoreManagerService.hidPrimaryDisplayId()
        val secondary = CoreManagerService.hidSecondaryDisplayId()
        val ids = intArrayOf(primary, secondary).filter { it >= 0 }.distinct()
        if (ids.isEmpty()) return
        val existing = list.mapNotNull { vp ->
            runCatching {
                vp?.javaClass?.getField("displayId")?.getInt(vp)
            }.getOrNull()
        }.toHashSet()
        for (id in ids) {
            if (id in existing) continue
            val vp = buildVirtualViewport(id) ?: continue
            list.add(vp)
            logDebug(TAG, "injected Input viewport for displayId=$id")
        }
    }

    private fun buildVirtualViewport(displayId: Int): Any? {
        return try {
            val size = CoreManagerService.displaySizeFor(displayId) ?: return null
            val display = Instances.displayManager.getDisplay(displayId) ?: return null
            val vpClass = Class.forName("android.hardware.display.DisplayViewport")
            val vp = vpClass.getDeclaredConstructor().newInstance()
            fun set(name: String, value: Any?) {
                val f = vpClass.getField(name)
                f.isAccessible = true
                f.set(vp, value)
            }
            set("displayId", displayId)
            runCatching {
                val uidMethod = display.javaClass.methods.firstOrNull { m ->
                    m.name == "getUniqueId" && m.parameterTypes.isEmpty()
                }
                val uid = uidMethod?.invoke(display) as? String
                if (!uid.isNullOrEmpty()) {
                    set("uniqueId", uid)
                }
            }.onFailure { /* optional field */ }
            // DisplayViewport.VIEWPORT_VIRTUAL == 3 on AOSP
            runCatching { set("type", 3) }
            runCatching { set("orientation", 0) }
            runCatching { set("isActive", true) }
            runCatching { set("deviceWidth", size.x) }
            runCatching { set("deviceHeight", size.y) }
            val rectClass = Class.forName("android.graphics.Rect")
            val frame = rectClass.getConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).newInstance(0, 0, size.x, size.y)
            runCatching { set("logicalFrame", frame) }
            runCatching { set("physicalFrame", frame) }
            vp
        } catch (e: Throwable) {
            logDebug(TAG, "buildVirtualViewport($displayId): ${e.message}")
            null
        }
    }

    /** Nudge WM to push viewports again so our hook can inject AA panes. */
    private fun requestViewportRefresh() {
        runCatching {
            val wms = ServiceManager.getService(Context.WINDOW_SERVICE) ?: return@runCatching
            // Best-effort: any no-arg display/layout poke. Failure is fine — next
            // rotation / config change will also hit setDisplayViewports.
            findMethodRecursive(wms.javaClass) { m ->
                m.name == "onDisplayChanged" && m.parameterTypes.size == 1
            }?.also { it.isAccessible = true }?.let { m ->
                val id = CoreManagerService.hidTargetDisplayId()
                if (id >= 0) m.invoke(wms, id)
            }
        }
        // Re-apply pointer after a short delay once viewports may exist.
        android.os.Handler(Looper.getMainLooper()).postDelayed({
            if (sessionLive) {
                applyPointerDisplay()
                invokeForceHideCursor(true)
            }
        }, 500L)
    }

    private fun invokeForceHideCursor(hide: Boolean) {
        val method = forceHideCursorMethod ?: return
        val target = forceHideCursorTarget ?: return
        runCatching {
            when (method.parameterTypes.size) {
                1 -> {
                    if (method.parameterTypes[0] == Boolean::class.javaPrimitiveType) {
                        method.invoke(target, hide)
                    }
                }
                0 -> if (hide) method.invoke(target)
                else -> method.invoke(target, hide)
            }
            log(TAG, "forceHideCursor($hide)")
        }.onFailure {
            logDebug(TAG, "forceHideCursor failed: ${it.message}")
        }
    }

    private fun findMethodRecursive(
        start: Class<*>,
        predicate: (Method) -> Boolean,
    ): Method? {
        var c: Class<*>? = start
        while (c != null && c != Any::class.java) {
            c.declaredMethods.firstOrNull(predicate)?.let { return it }
            c.methods.firstOrNull(predicate)?.let { return it }
            c = c.superclass
        }
        return null
    }

    private fun findFieldRecursive(start: Class<*>, name: String): Field? {
        var c: Class<*>? = start
        while (c != null && c != Any::class.java) {
            runCatching { c!!.getDeclaredField(name) }.getOrNull()?.let { return it }
            c = c.superclass
        }
        return null
    }

    private fun hookKeyIntercept() {
        val method = AndroidHook.findSystemMethod(
            "com.android.server.policy.PhoneWindowManager",
            findSuper = true,
        ) {
            name == "interceptKeyBeforeQueueing" &&
                parameterTypes.isNotEmpty() &&
                parameterTypes[0] == KeyEvent::class.java
        } ?: AndroidHook.findSystemMethod(
            "com.android.server.wm.DisplayPolicy",
            findSuper = true,
        ) {
            name == "interceptKeyBeforeQueueing" &&
                parameterTypes.isNotEmpty() &&
                parameterTypes[0] == KeyEvent::class.java
        }
        if (method == null) {
            log(TAG, "interceptKeyBeforeQueueing not found — key fallback unavailable")
            return
        }
        keyHook = method.hookBefore { param ->
            try {
                if (!isRedirectActive()) return@hookBefore
                // When InputFilter is installed it already steals keys; avoid double-inject.
                if (filterInstalled) return@hookBefore
                val event = param.args[0] as? KeyEvent ?: return@hookBefore
                if (!shouldStealKey(event)) return@hookBefore
                if (dispatchKey(event)) {
                    // 0 = ACTION_PASS_TO_USER cleared → drop (AOSP: return 0 consumes).
                    param.result = 0
                }
            } catch (_: Throwable) {
                // Never break PWM key dispatch on OEM quirks.
            }
        }
    }

    @SuppressLint("NewApi")
    private fun installInputFilter() {
        if (filterPermanentlyFailed || filterInstalled) return
        val ims = imsInstance ?: return
        val setFilter = setInputFilterMethod ?: return
        val identity = android.os.Binder.clearCallingIdentity()
        try {
            val filter = obtainHidFilter() ?: return
            savedFilter = runCatching { inputFilterField?.get(ims) }.getOrNull()
            setFilter.invoke(ims, filter)
            filterInstalled = true
            log(TAG, "InputFilter installed (prev=${savedFilter != null})")
        } catch (e: Throwable) {
            log(TAG, "InputFilter install failed — mouse steal off, key hook may still work", e)
            filterInstalled = false
            filterPermanentlyFailed = true
            hidFilter = null
            savedFilter = null
        } finally {
            android.os.Binder.restoreCallingIdentity(identity)
        }
    }

    /** Construct once; VerifyError / missing InputFilter → permanent mouse-filter disable. */
    private fun obtainHidFilter(): InputFilter? {
        hidFilter?.let { return it }
        if (filterPermanentlyFailed) return null
        val looper = Looper.getMainLooper()
        if (looper == null) {
            log(TAG, "no main looper — InputFilter skipped")
            return null
        }
        return try {
            HidInputFilter(looper).also { hidFilter = it }
        } catch (e: Throwable) {
            filterPermanentlyFailed = true
            log(TAG, "HidInputFilter construct failed — mouse steal disabled this boot", e)
            null
        }
    }

    private fun uninstallInputFilter() {
        if (!filterInstalled) return
        val ims = imsInstance
        val setFilter = setInputFilterMethod
        if (ims == null || setFilter == null) {
            filterInstalled = false
            savedFilter = null
            return
        }
        val identity = android.os.Binder.clearCallingIdentity()
        try {
            runCatching { setFilter.invoke(ims, savedFilter) }
                .onFailure {
                    log(TAG, "InputFilter restore prev failed, clearing", it)
                    runCatching { setFilter.invoke(ims, null) }
                }
        } finally {
            filterInstalled = false
            savedFilter = null
            android.os.Binder.restoreCallingIdentity(identity)
        }
    }

    private fun applyPointerDisplay() {
        if (CoreManagerService.aaUiShellCapture) {
            applyPointerDisplayToShell()
            return
        }
        val layout = runCatching { CoreManagerService.hidSplitLayout() }.getOrNull()
        val pane = when {
            layout == null -> -1
            cursorInitialized && SplitPane.isValid(cursorPane) -> cursorPane
            else -> layout.focusedPane
        }
        val displayId = when {
            pane >= 0 && layout != null -> layout.displayIdOf(pane)
            else -> runCatching { CoreManagerService.hidTargetDisplayId() }.getOrNull()
                ?: return
        }
        if (displayId < 0) return
        if (displayId == lastTargetDisplayId && pane == lastPointerBindPane) return
        invokePointerDisplay(displayId)
        lastTargetDisplayId = displayId
        lastPointerBindPane = pane
        // Hide sprite on physical panels so fold cover/main don't keep a ghost cursor.
        invokePointerIconVisible(false)
    }

    private fun applyPointerDisplayToShell() {
        val shellId = CoreManagerService.hidAaUiDisplayId()
        if (shellId < 0) return
        if (shellId == lastTargetDisplayId && lastPointerBindPane == POINTER_BIND_SHELL) return
        invokePointerDisplay(shellId)
        lastTargetDisplayId = shellId
        lastPointerBindPane = POINTER_BIND_SHELL
        invokePointerIconVisible(false)
    }

    /**
     * Split hover: rebind only when [cursorPane] actually changed (pane-local model).
     */
    private fun shouldRebindPointerDisplay(layout: HidSplitLayout, pane: Int): Boolean {
        return pane != lastPointerBindPane
    }

    private fun restorePointerDisplay() {
        invokePointerDisplay(Display.DEFAULT_DISPLAY)
        invokePointerIconVisible(true)
    }

    private fun invokePointerDisplay(displayId: Int) {
        val method = setPointerDisplayIdMethod ?: return
        val target = pointerApiTarget ?: return
        runCatching {
            when (method.parameterTypes.size) {
                1 -> method.invoke(target, displayId)
                2 -> {
                    // Some OEM overloads: (displayId, boolean) or (deviceId, displayId)
                    val p0 = method.parameterTypes[0]
                    val p1 = method.parameterTypes[1]
                    if (p0 == Int::class.javaPrimitiveType && p1 == Boolean::class.javaPrimitiveType) {
                        method.invoke(target, displayId, true)
                    } else if (p0 == Int::class.javaPrimitiveType && p1 == Int::class.javaPrimitiveType) {
                        method.invoke(target, -1, displayId)
                    } else {
                        method.invoke(target, displayId)
                    }
                }
                else -> method.invoke(target, displayId)
            }
            log(TAG, "setPointerDisplayId → $displayId via ${method.name}")
        }.onFailure {
            log(TAG, "setPointerDisplayId($displayId) failed: ${it.message}")
        }
    }

    private fun invokePointerIconVisible(visible: Boolean) {
        val method = setPointerIconVisibleMethod ?: return
        val target = pointerIconTarget ?: return
        runCatching {
            when (method.parameterTypes.size) {
                1 -> {
                    if (method.parameterTypes[0] == Boolean::class.javaPrimitiveType) {
                        method.invoke(target, visible)
                    } else {
                        // (displayId) show — skip
                    }
                }
                2 -> {
                    val p0 = method.parameterTypes[0]
                    val p1 = method.parameterTypes[1]
                    if (p0 == Int::class.javaPrimitiveType && p1 == Boolean::class.javaPrimitiveType) {
                        // Hide on both fold panels; show restores default display.
                        method.invoke(target, Display.DEFAULT_DISPLAY, visible)
                        method.invoke(target, 1, visible)
                    } else if (p0 == Boolean::class.javaPrimitiveType) {
                        method.invoke(target, visible, Display.DEFAULT_DISPLAY)
                    }
                }
            }
            logDebug(TAG, "setPointerIconVisible($visible)")
        }.onFailure {
            logDebug(TAG, "setPointerIconVisible failed: ${it.message}")
        }
    }

    /** @return true if the event was consumed and must not reach DEFAULT_DISPLAY. */
    internal fun tryConsume(event: InputEvent): Boolean {
        if (!isRedirectActive()) return false
        return try {
            when (event) {
                is KeyEvent -> {
                    if (!shouldStealKey(event)) return false
                    dispatchKey(event)
                    true // always drop from phone once classified as our HID key
                }
                is MotionEvent -> {
                    if (!shouldStealPointer(event)) return false
                    // Re-assert pointer display occasionally — fold open/close resets it
                    // to the active physical panel (cover vs main).
                    if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
                        event.actionMasked == MotionEvent.ACTION_DOWN ||
                        event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
                    ) {
                        applyPointerDisplay()
                    }
                    runCatching { dispatchPointer(event) }
                    true
                }
                else -> false
            }
        } catch (e: Throwable) {
            logDebug(TAG, "tryConsume: ${e.message}")
            false
        }
    }

    private fun shouldStealKey(event: KeyEvent): Boolean {
        return try {
            if (event.deviceId <= 0) return false
            val kcm = runCatching { event.keyCharacterMap }.getOrNull()
            if (kcm?.keyboardType == KeyCharacterMap.VIRTUAL_KEYBOARD) return false
            val source = event.source
            if (source and InputDevice.SOURCE_KEYBOARD == 0 &&
                source and InputDevice.SOURCE_DPAD == 0 &&
                source and InputDevice.SOURCE_GAMEPAD == 0
            ) {
                return false
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_POWER,
                KeyEvent.KEYCODE_WAKEUP,
                KeyEvent.KEYCODE_SLEEP,
                KeyEvent.KEYCODE_SOFT_SLEEP,
                KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE,
                KeyEvent.KEYCODE_MUTE,
                KeyEvent.KEYCODE_STEM_PRIMARY,
                KeyEvent.KEYCODE_RECENT_APPS,
                KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_SYSRQ,
                KeyEvent.KEYCODE_BREAK -> false
                else -> true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun shouldStealPointer(event: MotionEvent): Boolean {
        return try {
            if (event.deviceId <= 0) return false
            val source = event.source
            // Never steal pure finger touchscreen (phone / cover panel).
            if (source and InputDevice.SOURCE_TOUCHSCREEN != 0 &&
                source and InputDevice.SOURCE_MOUSE == 0 &&
                source and InputDevice.SOURCE_MOUSE_RELATIVE == 0
            ) {
                return false
            }
            val device = runCatching { InputDevice.getDevice(event.deviceId) }.getOrNull()
            if (device != null) {
                if (device.isVirtual) return false
                val ds = device.sources
                // External cursor / mouse / touchpad (BT Keyboard Mouse on W7023).
                if (ds and InputDevice.SOURCE_MOUSE != 0 ||
                    ds and InputDevice.SOURCE_MOUSE_RELATIVE != 0 ||
                    ds and InputDevice.SOURCE_TOUCHPAD != 0 ||
                    ds and InputDevice.SOURCE_TRACKBALL != 0
                ) {
                    return true
                }
                // CURSOR class devices may only expose CLASS_POINTER in event.source.
                if (device.isExternal && source and InputDevice.SOURCE_CLASS_POINTER != 0) {
                    return true
                }
            }
            source and InputDevice.SOURCE_CLASS_POINTER != 0 &&
                (
                    source and InputDevice.SOURCE_MOUSE != 0 ||
                        source and InputDevice.SOURCE_TOUCHPAD != 0 ||
                        source and InputDevice.SOURCE_TRACKBALL != 0 ||
                        source and InputDevice.SOURCE_MOUSE_RELATIVE != 0
                    )
        } catch (_: Throwable) {
            false
        }
    }

    private fun dispatchKey(event: KeyEvent): Boolean {
        return try {
            // Ctrl+… chrome shortcuts (ratio / Recents / swap) — do not inject into apps.
            if (event.action == KeyEvent.ACTION_DOWN && handleChromeShortcut(event)) {
                return true
            }
            if (event.action == KeyEvent.ACTION_UP &&
                event.metaState and KeyEvent.META_CTRL_ON != 0 &&
                event.keyCode in setOf(
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_W,
                    KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_TAB,
                    KeyEvent.KEYCODE_APP_SWITCH,
                )
            ) {
                return true
            }
            CoreManagerService.injectHidKeyEvent(event)
        } catch (e: Throwable) {
            logDebug(TAG, "dispatchKey: ${e.message}")
            false
        }
    }

    /**
     * - Ctrl+←/→/↑/↓ or WASD: nudge split ratio ±0.05 (shell-first)
     * - Ctrl+R / Ctrl+Tab / App-switch: open Recents
     * - Ctrl+S: swap panes
     */
    private fun handleChromeShortcut(event: KeyEvent): Boolean {
        if (event.metaState and KeyEvent.META_CTRL_ON == 0) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> {
                CoreManagerService.nudgeHidSplitRatio(-0.05f)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                CoreManagerService.nudgeHidSplitRatio(0.05f)
                true
            }
            KeyEvent.KEYCODE_S -> {
                CoreManagerService.swapHidPanes()
                true
            }
            KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_TAB -> {
                CoreManagerService.showHidRecentTask()
                true
            }
            else -> false
        }
    }

    private fun dispatchPointer(event: MotionEvent): Boolean {
        return try {
            dispatchPointerInner(event)
        } catch (e: Throwable) {
            logDebug(TAG, "dispatchPointer: ${e.message}")
            false
        }
    }

    private fun dispatchPointerInner(event: MotionEvent): Boolean {
        val layout = CoreManagerService.hidSplitLayout() ?: return false
        ensureCursorPane(layout)

        // Middle click: jump focus + cursor to the other pane (split only).
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
            event.actionButton == MotionEvent.BUTTON_TERTIARY
        ) {
            switchToOtherPane(layout, center = true)
            return true
        }
        if (event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 &&
            event.actionMasked == MotionEvent.ACTION_DOWN
        ) {
            switchToOtherPane(layout, center = true)
            return true
        }

        val relX = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
        val relY = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
        val hasRel = relX != 0f || relY != 0f

        if (dividerGestureActive && hasRel) {
            val tw = layout.totalW.toFloat().coerceAtLeast(1f)
            val th = layout.totalH.toFloat().coerceAtLeast(1f)
            dividerX = (dividerX + relX).coerceIn(0f, tw - 1f)
            dividerY = (dividerY + relY).coerceIn(0f, th - 1f)
        } else if ((CoreManagerService.aaUiShellCapture || shellGestureActive) && hasRel) {
            val tw = layout.totalW.toFloat().coerceAtLeast(1f)
            val th = layout.totalH.toFloat().coerceAtLeast(1f)
            shellX = (shellX + relX).coerceIn(0f, tw - 1f)
            shellY = (shellY + relY).coerceIn(0f, th - 1f)
        } else if (hasRel) {
            // Cap per-event deltas — W7023 pointer acceleration=3 yields hundreds of px/event,
            // which blasted past the seam and flip-flopped setPointerDisplayId 16↔17.
            val step = 32f
            applyRelativeMove(
                layout,
                relX.coerceIn(-step, step),
                relY.coerceIn(-step, step),
            )
        }
        // No absolute hover updates while an AA pane owns the pointer.
        // ADB: abs from cover/main fought relative; only after LMB (abs skipped) could
        // the user approach the divider. Physical abs also warped coords after VD hops.

        if (CoreManagerService.aaUiShellCapture || shellGestureActive) {
            return routeShellOverlay(layout, event)
        }

        if (routeDividerIfNeeded(layout, event)) return true

        // Re-bind system cursor when pane changed via relative edge cross.
        if (cursorPane != lastPointerBindPane && shouldRebindPointerDisplay(layout, cursorPane)) {
            CoreManagerService.focusHidPane(cursorPane)
            applyPointerDisplay()
        }

        val pane = cursorPane
        val px = cursorX
        val py = cursorY

        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (vScroll == 0f && hScroll == 0f) return true
                return CoreManagerService.injectHidScrollOnPane(pane, px, py, vScroll, hScroll)
            }
            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_DOWN -> {
                if (event.actionButton == MotionEvent.BUTTON_TERTIARY) return true
                if (pointerDown) return true
                if (atSeamEdge(layout)) return startDividerGesture(layout)
                pointerDown = true
                pointerDownTime = SystemClock.uptimeMillis()
                CoreManagerService.focusHidPane(pane)
                applyPointerDisplay()
                return CoreManagerService.injectHidTouchOnPane(
                    pane, MotionEvent.ACTION_DOWN, px, py, pointerDownTime, pointerDownTime,
                )
            }
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (!pointerDown) return true
                return CoreManagerService.injectHidTouchOnPane(
                    pane, MotionEvent.ACTION_MOVE, px, py, pointerDownTime, SystemClock.uptimeMillis(),
                )
            }
            MotionEvent.ACTION_BUTTON_RELEASE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (!pointerDown) return true
                pointerDown = false
                val action =
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) MotionEvent.ACTION_CANCEL
                    else MotionEvent.ACTION_UP
                return CoreManagerService.injectHidTouchOnPane(
                    pane, action, px, py, pointerDownTime, SystemClock.uptimeMillis(),
                )
            }
            else -> {
                val pressed = event.buttonState and MotionEvent.BUTTON_PRIMARY != 0
                if (pressed && !pointerDown) {
                    if (atSeamEdge(layout)) return startDividerGesture(layout)
                    pointerDown = true
                    pointerDownTime = SystemClock.uptimeMillis()
                    CoreManagerService.focusHidPane(pane)
                    applyPointerDisplay()
                    return CoreManagerService.injectHidTouchOnPane(
                        pane, MotionEvent.ACTION_DOWN, px, py, pointerDownTime, pointerDownTime,
                    )
                }
                if (!pressed && pointerDown) {
                    pointerDown = false
                    return CoreManagerService.injectHidTouchOnPane(
                        pane, MotionEvent.ACTION_UP, px, py, pointerDownTime, SystemClock.uptimeMillis(),
                    )
                }
                if (pointerDown) {
                    return CoreManagerService.injectHidTouchOnPane(
                        pane, MotionEvent.ACTION_MOVE, px, py, pointerDownTime, SystemClock.uptimeMillis(),
                    )
                }
                return true
            }
        }
    }

    private fun ensureCursorPane(layout: HidSplitLayout) {
        val active = layout.activePanes()
        if (!cursorInitialized || cursorPane !in active.toList()) {
            cursorPane = when {
                layout.focusedPane in active.toList() -> layout.focusedPane
                else -> active.first()
            }
            val size = layout.sizeOf(cursorPane)
            cursorX = (size?.first ?: 2) * 0.5f
            cursorY = (size?.second ?: 2) * 0.5f
            cursorInitialized = true
            pointerDown = false
            seamHoldPx = 0f
            lastTargetDisplayId = layout.displayIdOf(cursorPane)
            return
        }
        val size = layout.sizeOf(cursorPane) ?: return
        cursorX = cursorX.coerceIn(0f, (size.first - 1).toFloat())
        cursorY = cursorY.coerceIn(0f, (size.second - 1).toFloat())
    }

    /**
     * Place the logical cursor on the edge of [pane] that faces the sibling
     * (or center). Never keep the previous pane's local X/Y — after a hop that
     * left cursorX≈primaryW on SECONDARY, the sprite sat on the far right of the
     * new VD ("坐标拉到很远").
     */
    private fun resetCursorOnPane(
        layout: HidSplitLayout,
        pane: Int,
        enterFromSeam: Boolean,
    ) {
        val size = layout.sizeOf(pane) ?: return
        val w = size.first.toFloat().coerceAtLeast(1f)
        val h = size.second.toFloat().coerceAtLeast(1f)
        val yKeep = cursorY.coerceIn(0f, h - 1f)
        val xKeep = cursorX.coerceIn(0f, w - 1f)
        if (!enterFromSeam || !layout.isSplit()) {
            cursorX = w * 0.5f
            cursorY = h * 0.5f
        } else if (layout.sideBySide) {
            cursorX = if (pane == SplitPane.SECONDARY) 0f else w - 1f
            cursorY = yKeep
        } else {
            cursorX = xKeep
            cursorY = if (pane == SplitPane.SECONDARY) 0f else h - 1f
        }
        cursorPane = pane
        seamHoldPx = 0f
    }

    /**
     * Relative mouse in pane-local space.
     *
     * Side-by-side (matches finger divider ends / user sketch):
     * - **Middle** of the shared edge: hard stop → LMB drags the split bar.
     * - **Top / bottom** end insets: push across like a multi-monitor seam.
     * Stacked: same idea on left / right ends of the horizontal seam.
     */
    private fun applyRelativeMove(layout: HidSplitLayout, dx: Float, dy: Float) {
        val size = layout.sizeOf(cursorPane) ?: return
        val w = size.first.toFloat().coerceAtLeast(1f)
        val h = size.second.toFloat().coerceAtLeast(1f)
        if (!layout.isSplit()) {
            cursorX = (cursorX + dx).coerceIn(0f, w - 1f)
            cursorY = (cursorY + dy).coerceIn(0f, h - 1f)
            seamHoldPx = 0f
            return
        }

        val x = cursorX + dx
        val y = cursorY + dy
        val now = SystemClock.uptimeMillis()

        if (layout.sideBySide) {
            val exitPrimary = cursorPane == SplitPane.PRIMARY && x >= w
            val exitSecondary = cursorPane == SplitPane.SECONDARY && x < 0f
            if (exitPrimary || exitSecondary) {
                val yGate = cursorY.coerceIn(0f, h - 1f)
                if (!pointerDown && isInCrossGate(layout, along = yGate, longSpan = h) &&
                    now >= seamCooldownUntil
                ) {
                    val overshoot = if (exitPrimary) x - (w - 1f) else -x
                    seamHoldPx += overshoot.coerceIn(0f, 32f)
                    if (seamHoldPx >= CROSS_HOLD_PX) {
                        val next =
                            if (exitPrimary) SplitPane.SECONDARY else SplitPane.PRIMARY
                        val yRatio = yGate / h
                        resetCursorOnPane(layout, next, enterFromSeam = true)
                        val ns = layout.sizeOf(next) ?: return
                        cursorY = (yRatio * ns.second).coerceIn(0f, ns.second - 1f)
                        seamCooldownUntil = now + 250L
                        logDebug(TAG, "cross gate → pane $next y=${cursorY.toInt()}")
                        return
                    }
                    cursorX = if (exitPrimary) w - 1f else 0f
                    cursorY = y.coerceIn(0f, h - 1f)
                    return
                }
                // Middle band: block for divider chrome.
                seamHoldPx = 0f
                cursorX = if (exitPrimary) w - 1f else 0f
                cursorY = y.coerceIn(0f, h - 1f)
                return
            }
        } else {
            val exitPrimary = cursorPane == SplitPane.PRIMARY && y >= h
            val exitSecondary = cursorPane == SplitPane.SECONDARY && y < 0f
            if (exitPrimary || exitSecondary) {
                val xGate = cursorX.coerceIn(0f, w - 1f)
                if (!pointerDown && isInCrossGate(layout, along = xGate, longSpan = w) &&
                    now >= seamCooldownUntil
                ) {
                    val overshoot = if (exitPrimary) y - (h - 1f) else -y
                    seamHoldPx += overshoot.coerceIn(0f, 32f)
                    if (seamHoldPx >= CROSS_HOLD_PX) {
                        val next =
                            if (exitPrimary) SplitPane.SECONDARY else SplitPane.PRIMARY
                        val xRatio = xGate / w
                        resetCursorOnPane(layout, next, enterFromSeam = true)
                        val ns = layout.sizeOf(next) ?: return
                        cursorX = (xRatio * ns.first).coerceIn(0f, ns.first - 1f)
                        seamCooldownUntil = now + 250L
                        logDebug(TAG, "cross gate → pane $next x=${cursorX.toInt()}")
                        return
                    }
                    cursorX = x.coerceIn(0f, w - 1f)
                    cursorY = if (exitPrimary) h - 1f else 0f
                    return
                }
                seamHoldPx = 0f
                cursorX = x.coerceIn(0f, w - 1f)
                cursorY = if (exitPrimary) h - 1f else 0f
                return
            }
        }

        seamHoldPx = 0f
        cursorX = x.coerceIn(0f, w - 1f)
        cursorY = y.coerceIn(0f, h - 1f)
    }

    /** Same dp as [SplitPane.DIVIDER_TOUCH_END_INSET_DP] — finger fall-through / mouse cross. */
    private fun endInsetPx(layout: HidSplitLayout): Float {
        val dpi = layout.densityDpi.coerceAtLeast(160)
        return SplitPane.DIVIDER_TOUCH_END_INSET_DP * dpi / 160f
    }

    /**
     * Top/bottom (side-by-side) or left/right (stacked) of the seam — cursor may
     * cross VDs like a PC extended display. Middle of the seam stays blocked.
     */
    private fun isInCrossGate(
        layout: HidSplitLayout,
        along: Float,
        longSpan: Float,
    ): Boolean {
        val inset = endInsetPx(layout)
        if (longSpan <= 2f * inset + 1f) return true
        return along < inset || along > longSpan - inset
    }

    /** Near the shared seam **middle** (or fullscreen peel) — LMB hits shell chrome. */
    private fun atSeamEdge(layout: HidSplitLayout): Boolean {
        if (!layout.isSplit()) {
            val (cx, cy) = layout.toCanvas(cursorPane, cursorX, cursorY)
            return layout.isPeelHit(cx, cy)
        }
        val size = layout.sizeOf(cursorPane) ?: return false
        val band = (layout.dividerHitPx / 2f).coerceAtLeast(16f)
        val onEdge = if (layout.sideBySide) {
            when (cursorPane) {
                SplitPane.PRIMARY -> cursorX >= size.first - band
                else -> cursorX <= band
            }
        } else {
            when (cursorPane) {
                SplitPane.PRIMARY -> cursorY >= size.second - band
                else -> cursorY <= band
            }
        }
        if (!onEdge) return false
        // Ends are cross gates — not divider chrome (same as SplitDividerView end inset).
        val along = if (layout.sideBySide) cursorY else cursorX
        val longSpan =
            if (layout.sideBySide) size.second.toFloat() else size.first.toFloat()
        return !isInCrossGate(layout, along, longSpan)
    }

    private fun shellTouchPoint(layout: HidSplitLayout, x: Float, y: Float): Pair<Float, Float> =
        layout.toShellTouch(x, y)

    private fun wantsDividerChrome(layout: HidSplitLayout): Boolean = atSeamEdge(layout)

    /** Begin divider / peel drag on AaDisplay presentation; always consumes the press. */
    private fun startDividerGesture(layout: HidSplitLayout): Boolean {
        if (!wantsDividerChrome(layout)) return false
        cancelPaneFinger()
        seamHoldPx = 0f
        dividerGestureActive = true
        dividerDownTime = SystemClock.uptimeMillis()
        val (cx, cy) = layout.toCanvas(cursorPane, cursorX, cursorY)
        dividerX = cx
        dividerY = cy
        val (sx, sy) = shellTouchPoint(layout, dividerX, dividerY)
        CoreManagerService.injectHidAaUiTouch(
            MotionEvent.ACTION_DOWN, sx, sy, dividerDownTime, dividerDownTime,
        )
        return true
    }

    /** Recents / picker: inject onto AaDisplay presentation so overlay stays operable. */
    private fun routeShellOverlay(layout: HidSplitLayout, event: MotionEvent): Boolean {
        applyPointerDisplayToShell()
        val pressed = event.buttonState and MotionEvent.BUTTON_PRIMARY != 0 ||
            event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
        fun inject(action: Int, x: Float, y: Float, down: Long, now: Long): Boolean {
            val (sx, sy) = shellTouchPoint(layout, x, y)
            return CoreManagerService.injectHidAaUiTouch(action, sx, sy, down, now)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                if (event.actionButton == MotionEvent.BUTTON_TERTIARY) return true
                cancelPaneFinger()
                dividerGestureActive = false
                shellGestureActive = true
                shellDownTime = SystemClock.uptimeMillis()
                return inject(MotionEvent.ACTION_DOWN, shellX, shellY, shellDownTime, shellDownTime)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                if (!shellGestureActive) return true
                if (!pressed && event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) return true
                return inject(
                    MotionEvent.ACTION_MOVE, shellX, shellY, shellDownTime, SystemClock.uptimeMillis(),
                )
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_CANCEL -> {
                if (!shellGestureActive) return true
                shellGestureActive = false
                val action =
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) MotionEvent.ACTION_CANCEL
                    else MotionEvent.ACTION_UP
                return inject(action, shellX, shellY, shellDownTime, SystemClock.uptimeMillis())
            }
            MotionEvent.ACTION_SCROLL -> return true
            else -> {
                if (!shellGestureActive) return true
                if (pressed) {
                    return inject(
                        MotionEvent.ACTION_MOVE, shellX, shellY, shellDownTime, SystemClock.uptimeMillis(),
                    )
                }
                return true
            }
        }
    }

    /**
     * Seam / peel (or in-progress chrome drag) → [touchAaDisplay] so
     * [SplitDividerView] can drag shell ratio / long-press Recents / tap-swap.
     */
    private fun routeDividerIfNeeded(layout: HidSplitLayout, event: MotionEvent): Boolean {
        val pressed = event.buttonState and MotionEvent.BUTTON_PRIMARY != 0 ||
            event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS

        fun inject(action: Int, x: Float, y: Float, down: Long, now: Long) {
            val (sx, sy) = shellTouchPoint(layout, x, y)
            CoreManagerService.injectHidAaUiTouch(action, sx, sy, down, now)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                if (event.actionButton == MotionEvent.BUTTON_TERTIARY) return false
                if (dividerGestureActive) return true
                if (!wantsDividerChrome(layout)) {
                    dividerGestureActive = false
                    return false
                }
                return startDividerGesture(layout)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                if (!dividerGestureActive) return false
                if (!pressed && event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) return true
                inject(
                    MotionEvent.ACTION_MOVE, dividerX, dividerY, dividerDownTime, SystemClock.uptimeMillis(),
                )
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_CANCEL -> {
                if (!dividerGestureActive) return false
                dividerGestureActive = false
                val action =
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) MotionEvent.ACTION_CANCEL
                    else MotionEvent.ACTION_UP
                inject(action, dividerX, dividerY, dividerDownTime, SystemClock.uptimeMillis())
                return true
            }
            else -> {
                if (!dividerGestureActive) return false
                if (pressed) {
                    inject(
                        MotionEvent.ACTION_MOVE, dividerX, dividerY, dividerDownTime, SystemClock.uptimeMillis(),
                    )
                }
                return true
            }
        }
    }

    private fun cancelPaneFinger() {
        if (!pointerDown) return
        CoreManagerService.injectHidTouchOnPane(
            cursorPane, MotionEvent.ACTION_CANCEL, cursorX, cursorY,
            pointerDownTime, SystemClock.uptimeMillis(),
        )
        pointerDown = false
    }

    private fun switchToOtherPane(layout: HidSplitLayout, center: Boolean) {
        val active = layout.activePanes()
        if (active.size < 2) return
        cancelPaneFinger()
        dividerGestureActive = false
        seamCooldownUntil = SystemClock.uptimeMillis() + 400L
        val next = layout.otherPane(cursorPane)
        resetCursorOnPane(layout, next, enterFromSeam = !center)
        CoreManagerService.focusHidPane(cursorPane)
        applyPointerDisplay()
        log(TAG, "mouse pane → $cursorPane @ ${cursorX.toInt()},${cursorY.toInt()}")
    }

    private class HidInputFilter(looper: Looper) : InputFilter(looper) {
        override fun onInputEvent(event: InputEvent, policyFlags: Int) {
            try {
                if (tryConsume(event)) return
            } catch (e: Throwable) {
                log(TAG, "tryConsume failed", e)
            }
            try {
                sendInputEvent(event, policyFlags)
            } catch (e: Throwable) {
                log(TAG, "sendInputEvent failed", e)
            }
        }
    }
}
