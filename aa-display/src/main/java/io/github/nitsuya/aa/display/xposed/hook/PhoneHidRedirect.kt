package io.github.nitsuya.aa.display.xposed.hook

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
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
import io.github.nitsuya.aa.display.ui.aa.split.HidCursorHit
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
 * Android 16: InputFilter + AA VIRTUAL viewports are installed only while an
 * external mouse/touchpad is present. Always-on filter (older behavior) let phone
 * finger events traverse the filter path and confused multi-viewport routing so
 * taps landed on pane VDs. Source checks must use full-constant match
 * (sources and SOURCE) == SOURCE — nonzero AND falsely treats touchscreen as mouse
 * because both share CLASS_POINTER.
 *
 * Keys: PhoneWindowManager.interceptKeyBeforeQueueing consume + inject (fallback).
 * Pointer: InputManagerService.setInputFilter steal mouse/touchpad; primary-button
 * gestures become SOURCE_TOUCHSCREEN inject. Best-effort pointer-display API.
 */
object PhoneHidRedirect {
    private const val TAG = "AAD_PhoneHid"
    /** Hide the shell-drawn cursor after pointer idle (re-show on next move). */
    private const val CURSOR_IDLE_HIDE_MS = 3000L
    /** Re-assert OS pointer hide — not every relative MOVE. */
    private const val SUPPRESS_REASSERT_MS = 500L

    private var installAttempted = false
    private var keyHook: XC_MethodHook.Unhook? = null

    @Volatile private var sessionLive = false
    @Volatile private var filterInstalled = false
    /** After a hard failure constructing/installing the filter, do not retry this boot. */
    @Volatile private var filterPermanentlyFailed = false
    @Volatile private var deviceListenerRegistered = false

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
    /** Continuous HU canvas position — relative deltas accumulate here, not pane-local. */
    private var cursorCanvasX = 0f
    private var cursorCanvasY = 0f
    /** Pane-local inject target derived from [cursorCanvasX]/[cursorCanvasY]. */
    private var cursorPane: Int = SplitPane.PRIMARY
    private var cursorInitialized = false
    private var pointerDown = false
    private var pointerDownTime = 0L
    private var lastSuppressUptime = 0L
    private var lastPointerActivityUptime = 0L
    private var lastTargetDisplayId = -1
    private val cursorIdleHandler = Handler(Looper.getMainLooper())
    private val cursorIdleHideRunnable = Runnable { maybeHideCursorOnIdle() }
    /** Absolute pointer sample (some BT stacks omit AXIS_RELATIVE_*). */
    private var lastAbsSampleValid = false
    private var lastAbsX = 0f
    private var lastAbsY = 0f
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
    /** Last shell overlay position — stable across divider drag / ratio settle. */
    private var overlayShellX = 0f
    private var overlayShellY = 0f

    fun ensureHooked() {
        if (!AndroidHook.isReadyForSystemHooks()) return
        if (installAttempted) return
        installAttempted = true
        runCatching {
            resolveInputManager()
            hookKeyIntercept()
            hookDisplayViewports()
            registerExternalHidDeviceListener()
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
        if (!sessionLive || !filterInstalled) return
        runCatching {
            if (capture) {
                cancelPaneFinger()
                dividerGestureActive = false
                val layout = runCatching { CoreManagerService.hidSplitLayout() }.getOrNull()
                if (layout != null && SplitPane.isValid(cursorPane)) {
                    shellX = cursorCanvasX
                    shellY = cursorCanvasY
                }
                // Finger / steering Recents only need shell capture for touch routing.
                // Do NOT force-show the HID arrow here — that was for Ctrl+R and made
                // long-press Recents flash a mouse cursor. Keyboard path calls
                // [revealCursorForKeyboardRecent] after snap.
                applyPointerDisplayToShell()
            } else {
                shellGestureActive = false
                suppressOsPointerSprite(force = true)
            }
        }.onFailure { logDebug(TAG, "onShellCaptureChanged($capture): ${it.message}") }
    }

    /**
     * Ctrl+R / HID keyboard Recents: park the shell cursor on the focused stack column
     * and show the overlay so the user sees where keyboard focus lands.
     */
    fun revealCursorForKeyboardRecent() {
        if (!sessionLive || !filterInstalled) return
        runCatching {
            snapShellToRecentColumn()
            val layout = CoreManagerService.hidSplitLayout()
            if (!cursorInitialized && layout != null) {
                initCursorPane(layout)
            }
            publishHidCursorOverlay(layout, force = true)
        }.onFailure { logDebug(TAG, "revealCursorForKeyboardRecent: ${it.message}") }
    }

    /** Ctrl+R Recent: park shell cursor on the focused VD stack column (keyboard has no hover). */
    fun snapShellToRecentColumn() {
        runCatching {
            val layout = CoreManagerService.hidSplitLayout() ?: return@runCatching
            val active = layout.activePanes().toList()
            val pane = when {
                layout.focusedPane in active -> layout.focusedPane
                SplitPane.isValid(cursorPane) && cursorPane in active -> cursorPane
                else -> active.firstOrNull() ?: SplitPane.PRIMARY
            }
            val (cx, cy) = recentColumnCenter(layout, pane)
            shellX = cx
            shellY = cy
        }.onFailure { logDebug(TAG, "snapShellToRecentColumn: ${it.message}") }
    }

    private fun recentColumnCenter(layout: HidSplitLayout, pane: Int): Pair<Float, Float> {
        val tw = layout.totalW.toFloat().coerceAtLeast(1f)
        val th = layout.totalH.toFloat().coerceAtLeast(1f)
        val x = when (pane) {
            SplitPane.SECONDARY -> tw * 0.5f
            else -> tw / 6f
        }
        return x to (th * 0.5f)
    }

    /** Called from DisplaySessionPolicy / create when AA UI is attached vs Delay Destroy. */
    fun onSessionLiveChanged(live: Boolean) {
        runCatching {
            if (!installAttempted) ensureHooked()
            if (sessionLive == live) {
                if (live) maybeEnableHidForExternalPointer("session-same")
                return@runCatching
            }
            sessionLive = live
            if (live) {
                cursorInitialized = false
                pointerDown = false
                dividerGestureActive = false
                shellGestureActive = false
                // A16: do NOT install InputFilter / inject AA viewports until a real
                // external mouse/keyboard is present — otherwise phone touchscreen is
                // routed through the filter+VIRTUAL viewports and lands on pane VDs.
                maybeEnableHidForExternalPointer("session-live")
                log(
                    TAG,
                    "session live — HID armed (filter=$filterInstalled " +
                        "extPointer=${hasExternalPointerDevice()} keyHook=${keyHook != null})",
                )
            } else {
                pointerDown = false
                dividerGestureActive = false
                shellGestureActive = false
                lastAbsSampleValid = false
                runCatching { CoreManagerService.clearAaUiShellCapture() }
                cancelCursorIdleHide()
                publishHidCursorOverlay(null, visible = false)
                disableHidRedirect("session-end")
                log(TAG, "session not live — HID redirect off")
            }
        }.onFailure { log(TAG, "onSessionLiveChanged($live) failed", it) }
    }

    /**
     * Enable InputFilter + AA viewports only while an external **pointer** exists.
     * Keyboard-only uses [hookKeyIntercept] without installing the global filter —
     * A16 phone touch must never go through InputFilter+VIRTUAL viewports.
     */
    private fun maybeEnableHidForExternalPointer(reason: String) {
        if (!sessionLive) return
        if (!hasExternalPointerDevice()) {
            // Drop filter/viewports; key hook still works if an external keyboard is present.
            if (filterInstalled) {
                disableHidRedirect("no-ext-pointer/$reason")
            }
            return
        }
        installInputFilter()
        suppressOsPointerSprite(force = true)
        invokeForceHideCursor(true)
        requestViewportRefresh()
        logDebug(TAG, "HID enabled ($reason) filter=$filterInstalled")
    }

    private fun disableHidRedirect(reason: String) {
        uninstallInputFilter()
        invokeForceHideCursor(false)
        restorePointerDisplay()
        logDebug(TAG, "HID disabled ($reason)")
    }

    /** External mouse / touchpad / trackball (not the phone touchpanel). */
    private fun hasExternalPointerDevice(): Boolean {
        return try {
            InputDevice.getDeviceIds().any { id ->
                if (id <= 0) return@any false
                val d = InputDevice.getDevice(id) ?: return@any false
                if (d.isVirtual) return@any false
                // Built-in touchscreen shares SOURCE_CLASS_POINTER with MOUSE; require
                // full source match + isExternal so finger panels never arm the filter.
                if (!d.isExternal) return@any false
                hasFullSource(d.sources, InputDevice.SOURCE_MOUSE) ||
                    hasFullSource(d.sources, InputDevice.SOURCE_MOUSE_RELATIVE) ||
                    hasFullSource(d.sources, InputDevice.SOURCE_TOUCHPAD) ||
                    hasFullSource(d.sources, InputDevice.SOURCE_TRACKBALL)
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasExternalKeyboardDevice(): Boolean {
        return try {
            InputDevice.getDeviceIds().any { id ->
                if (id <= 0) return@any false
                val d = InputDevice.getDevice(id) ?: return@any false
                if (d.isVirtual) return@any false
                d.isExternal && hasFullSource(d.sources, InputDevice.SOURCE_KEYBOARD)
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Android source constants include a class nibble (e.g. MOUSE and TOUCHSCREEN both
     * have CLASS_POINTER). `(sources and MOUSE) != 0` is true for the touchscreen —
     * always compare the full constant.
     */
    private fun hasFullSource(sources: Int, source: Int): Boolean =
        (sources and source) == source

    private fun isFingerTouchscreenSource(sources: Int): Boolean =
        hasFullSource(sources, InputDevice.SOURCE_TOUCHSCREEN) &&
            !hasFullSource(sources, InputDevice.SOURCE_MOUSE) &&
            !hasFullSource(sources, InputDevice.SOURCE_MOUSE_RELATIVE)

    /** Hot-plug BT mouse/keyboard → enable/disable InputFilter without AA reconnect. */
    private fun registerExternalHidDeviceListener() {
        if (deviceListenerRegistered) return
        runCatching {
            val im = resolveInputManagerApi() ?: return@runCatching
            val listener = object : android.hardware.input.InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) {
                    maybeEnableHidForExternalPointer("device-add:$deviceId")
                }
                override fun onInputDeviceRemoved(deviceId: Int) {
                    maybeEnableHidForExternalPointer("device-rm:$deviceId")
                }
                override fun onInputDeviceChanged(deviceId: Int) {
                    maybeEnableHidForExternalPointer("device-chg:$deviceId")
                }
            }
            im.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
            deviceListenerRegistered = true
            log(TAG, "InputDeviceListener registered for lazy HID")
        }.onFailure { log(TAG, "InputDeviceListener register failed", it) }
    }

    private fun resolveInputManagerApi(): android.hardware.input.InputManager? {
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null) ?: return@runCatching null
            val ctx = at.getMethod("getSystemContext").invoke(thread) as? Context
            ctx?.getSystemService(android.hardware.input.InputManager::class.java)?.let { return it }
        }
        return runCatching {
            val m = android.hardware.input.InputManager::class.java.getDeclaredMethod("getInstance")
            m.isAccessible = true
            m.invoke(null) as? android.hardware.input.InputManager
        }.getOrNull()
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
                // Only inject AA viewports when HID filter is actually stealing the mouse.
                // Always-on VIRTUAL viewports on A16 confuse the phone touchscreen mapper.
                if (!sessionLive || !filterInstalled) return@hookBefore
                if (!hasExternalPointerDevice()) return@hookBefore
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
        val shell = CoreManagerService.hidAaUiDisplayId()
        val ids = intArrayOf(primary, secondary, shell).filter { it >= 0 }.distinct()
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
                suppressOsPointerSprite(force = true)
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
                // Without an external keyboard/mouse, never steal phone hardware keys.
                if (!hasExternalKeyboardDevice() && !hasExternalPointerDevice()) return@hookBefore
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

    /**
     * Hide the OS mouse sprite — visual cursor is [HidCursorOverlayView] on the
     * AaDisplay presentation (Activity overlay).
     */
    private fun suppressOsPointerSprite(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastSuppressUptime < SUPPRESS_REASSERT_MS) return
        lastSuppressUptime = now
        invokeForceHideCursor(true)
        invokePointerIconVisible(false)
        hidePointerIconOnDisplay(Display.DEFAULT_DISPLAY)
        hidePointerIconOnDisplay(1)
        hidePointerIconOnDisplay(CoreManagerService.hidAaUiDisplayId())
        hidePointerIconOnDisplay(CoreManagerService.hidPrimaryDisplayId())
        hidePointerIconOnDisplay(CoreManagerService.hidSecondaryDisplayId())
        val offDisplay = Display.INVALID_DISPLAY
        if (lastTargetDisplayId != offDisplay) {
            invokePointerDisplay(offDisplay)
            lastTargetDisplayId = offDisplay
        }
    }

    private fun hidePointerIconOnDisplay(displayId: Int) {
        if (displayId < 0) return
        val method = setPointerIconVisibleMethod ?: return
        val target = pointerIconTarget ?: return
        runCatching {
            when (method.parameterTypes.size) {
                2 -> {
                    val p0 = method.parameterTypes[0]
                    val p1 = method.parameterTypes[1]
                    if (p0 == Int::class.javaPrimitiveType && p1 == Boolean::class.javaPrimitiveType) {
                        method.invoke(target, displayId, false)
                    }
                }
                else -> { /* global hide handled by invokePointerIconVisible(false) */ }
            }
        }.onFailure {
            logDebug(TAG, "hidePointerIconOnDisplay($displayId): ${it.message}")
        }
    }

    private fun applyPointerDisplayToShell() {
        suppressOsPointerSprite()
    }

    private fun overlayCanvasPosition(): Pair<Float, Float> = when {
        dividerGestureActive -> dividerX to dividerY
        CoreManagerService.aaUiShellCapture || shellGestureActive -> shellX to shellY
        else -> cursorCanvasX to cursorCanvasY
    }

    private fun refreshOverlayShell(layout: HidSplitLayout) {
        val (cx, cy) = overlayCanvasPosition()
        val (sx, sy) = layout.toShellTouch(cx, cy)
        overlayShellX = sx
        overlayShellY = sy
    }

    private fun publishOverlayShell(sx: Float, sy: Float, force: Boolean) {
        overlayShellX = sx
        overlayShellY = sy
        lastPointerActivityUptime = SystemClock.uptimeMillis()
        // In-process state only — AaDisplayActivity polls via getHidCursorOverlay each
        // frame. Broadcasts were AMS-batched and looked like segment jumps.
        CoreManagerService.publishHidCursorState(true, sx, sy)
        scheduleCursorIdleHide()
    }

    private fun scheduleCursorIdleHide() {
        lastPointerActivityUptime = SystemClock.uptimeMillis()
        cursorIdleHandler.removeCallbacks(cursorIdleHideRunnable)
        cursorIdleHandler.postDelayed(cursorIdleHideRunnable, CURSOR_IDLE_HIDE_MS)
    }

    private fun cancelCursorIdleHide() {
        cursorIdleHandler.removeCallbacks(cursorIdleHideRunnable)
    }

    private fun maybeHideCursorOnIdle() {
        if (!sessionLive || !cursorInitialized) return
        if (dividerGestureActive || pointerDown || shellGestureActive ||
            CoreManagerService.aaUiShellCapture
        ) {
            scheduleCursorIdleHide()
            return
        }
        val idle = SystemClock.uptimeMillis() - lastPointerActivityUptime
        if (idle < CURSOR_IDLE_HIDE_MS) {
            cursorIdleHandler.postDelayed(
                cursorIdleHideRunnable,
                CURSOR_IDLE_HIDE_MS - idle,
            )
            return
        }
        publishHidCursorOverlay(null, visible = false, idleHide = true)
    }

    /**
     * Broadcast shell cursor position to [AaDisplayActivity] ([HidCursorOverlayView]).
     * Presentation coords via [HidSplitLayout.toShellTouch].
     */
    private fun publishHidCursorOverlay(
        layout: HidSplitLayout?,
        visible: Boolean = true,
        force: Boolean = false,
        idleHide: Boolean = false,
    ) {
        if (!visible) {
            if (!idleHide) cancelCursorIdleHide()
            CoreManagerService.publishHidCursorState(false)
            return
        }
        if (!sessionLive || !cursorInitialized) return
        val lay = layout ?: runCatching { CoreManagerService.hidSplitLayout() }.getOrNull() ?: return
        val (sx, sy) = if (dividerGestureActive) {
            overlayShellX to overlayShellY
        } else {
            refreshOverlayShell(lay)
            overlayShellX to overlayShellY
        }
        publishOverlayShell(sx, sy, force)
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
                        suppressOsPointerSprite(force = true)
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
            if (isFingerTouchscreenSource(source)) return false
            val device = runCatching { InputDevice.getDevice(event.deviceId) }.getOrNull()
            if (device != null) {
                if (device.isVirtual) return false
                if (!device.isExternal) return false
                val ds = device.sources
                if (isFingerTouchscreenSource(ds)) return false
                // External cursor / mouse / touchpad (BT Keyboard Mouse on W7023).
                if (hasFullSource(ds, InputDevice.SOURCE_MOUSE) ||
                    hasFullSource(ds, InputDevice.SOURCE_MOUSE_RELATIVE) ||
                    hasFullSource(ds, InputDevice.SOURCE_TOUCHPAD) ||
                    hasFullSource(ds, InputDevice.SOURCE_TRACKBALL)
                ) {
                    return true
                }
            }
            // Event-level sources only when we already know device is external (above).
            false
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
            if (CoreManagerService.aaUiShellCapture) {
                return CoreManagerService.injectHidAaUiKeyEvent(event)
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
        val shellOverlay = CoreManagerService.aaUiShellCapture
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> {
                if (shellOverlay) return false
                CoreManagerService.nudgeHidSplitRatio(-0.05f)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (shellOverlay) return false
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
        if (!cursorInitialized) {
            initCursorPane(layout)
        }

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
        var dx = relX
        var dy = relY
        var hasDelta = relX != 0f || relY != 0f
        // Many BT combo devices report absolute pointer samples without RELATIVE axes;
        // ignoring them makes the shell cursor advance only on sparse relative ticks → jump.
        val actionMasked = event.actionMasked
        if (!hasDelta &&
            (actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                actionMasked == MotionEvent.ACTION_MOVE ||
                actionMasked == MotionEvent.ACTION_HOVER_ENTER)
        ) {
            val ax = event.x
            val ay = event.y
            if (lastAbsSampleValid) {
                dx = ax - lastAbsX
                dy = ay - lastAbsY
                hasDelta = dx != 0f || dy != 0f
            }
            lastAbsX = ax
            lastAbsY = ay
            lastAbsSampleValid = true
        } else if (hasDelta) {
            lastAbsX = event.x
            lastAbsY = event.y
            lastAbsSampleValid = true
        }

        val chromeCapture = CoreManagerService.aaUiShellCapture || shellGestureActive

        if (dividerGestureActive && hasDelta) {
            val tw = layout.totalW.toFloat().coerceAtLeast(1f)
            val th = layout.totalH.toFloat().coerceAtLeast(1f)
            dividerX = (dividerX + dx).coerceIn(0f, tw - 1f)
            dividerY = (dividerY + dy).coerceIn(0f, th - 1f)
            cursorCanvasX = dividerX
            cursorCanvasY = dividerY
            refreshOverlayShell(layout)
        } else if (chromeCapture && hasDelta) {
            val tw = layout.totalW.toFloat().coerceAtLeast(1f)
            val th = layout.totalH.toFloat().coerceAtLeast(1f)
            shellX = (shellX + dx).coerceIn(0f, tw - 1f)
            shellY = (shellY + dy).coerceIn(0f, th - 1f)
        } else if (hasDelta) {
            applyRelativeMove(layout, dx, dy)
        }
        if (!dividerGestureActive && !chromeCapture) {
            syncCursorToActivePane(layout)
        }
        if (hasDelta && !dividerGestureActive) {
            suppressOsPointerSprite()
        }

        if (chromeCapture) {
            if (hasDelta) publishHidCursorOverlay(layout, force = false)
            return routeShellOverlay(layout, event)
        }

        if (routeDividerIfNeeded(layout, event)) return true

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
                if (wantsDividerChrome(layout)) return startDividerGesture(layout)
                pointerDown = true
                pointerDownTime = SystemClock.uptimeMillis()
                CoreManagerService.focusHidPane(pane)
                publishHidCursorOverlay(layout, force = false)
                return CoreManagerService.injectHidTouchOnPane(
                    pane, MotionEvent.ACTION_DOWN, px, py, pointerDownTime, pointerDownTime,
                )
            }
            MotionEvent.ACTION_HOVER_ENTER -> {
                suppressOsPointerSprite(force = true)
                publishHidCursorOverlay(layout, force = true)
                return true
            }
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_HOVER_MOVE -> {
                publishHidCursorOverlay(layout, force = false)
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
                publishHidCursorOverlay(layout, force = false)
                return CoreManagerService.injectHidTouchOnPane(
                    pane, action, px, py, pointerDownTime, SystemClock.uptimeMillis(),
                )
            }
            else -> {
                val pressed = event.buttonState and MotionEvent.BUTTON_PRIMARY != 0
                if (pressed && !pointerDown) {
                    if (wantsDividerChrome(layout)) return startDividerGesture(layout)
                    pointerDown = true
                    pointerDownTime = SystemClock.uptimeMillis()
                    CoreManagerService.focusHidPane(pane)
                    publishHidCursorOverlay(layout, force = false)
                    return CoreManagerService.injectHidTouchOnPane(
                        pane, MotionEvent.ACTION_DOWN, px, py, pointerDownTime, pointerDownTime,
                    )
                }
                if (!pressed && pointerDown) {
                    pointerDown = false
                    publishHidCursorOverlay(layout, force = false)
                    return CoreManagerService.injectHidTouchOnPane(
                        pane, MotionEvent.ACTION_UP, px, py, pointerDownTime, SystemClock.uptimeMillis(),
                    )
                }
                if (pointerDown) {
                    publishHidCursorOverlay(layout, force = false)
                    return CoreManagerService.injectHidTouchOnPane(
                        pane, MotionEvent.ACTION_MOVE, px, py, pointerDownTime, SystemClock.uptimeMillis(),
                    )
                }
                if (hasDelta) publishHidCursorOverlay(layout, force = false)
                return true
            }
        }
    }

    /** One-time placement when the HID session goes live — only path that may use pane center. */
    private fun initCursorPane(layout: HidSplitLayout) {
        val active = layout.activePanes()
        cursorPane = when {
            layout.focusedPane in active.toList() -> layout.focusedPane
            else -> active.first()
        }
        val size = layout.sizeOf(cursorPane)
        cursorX = (size?.first ?: 2) * 0.5f
        cursorY = (size?.second ?: 2) * 0.5f
        val (cx, cy) = layout.toCanvas(cursorPane, cursorX, cursorY)
        cursorCanvasX = cx
        cursorCanvasY = cy
        cursorInitialized = true
        pointerDown = false
        suppressOsPointerSprite(force = true)
        publishHidCursorOverlay(layout, force = true)
    }

    /**
     * After relative move / layout change: clamp to the current pane, or remap across
     * fullscreen↔split without teleporting to center.
     */
    private fun syncCursorToActivePane(layout: HidSplitLayout) {
        val active = layout.activePanes()
        if (cursorPane !in active.toList()) {
            remapCursorToActivePane(layout, active)
        }
        projectCanvasToPane(layout)
    }

    private fun remapCursorToActivePane(layout: HidSplitLayout, active: IntArray) {
        val (cx, cy) = layout.clampCanvas(cursorCanvasX, cursorCanvasY)
        val target = layout.focusedPane.takeIf { it in active.toList() } ?: active.first()
        val (lx, ly) = layout.paneLocalOf(target, cx, cy)
        cursorPane = target
        cursorX = lx
        cursorY = ly
        cursorCanvasX = cx
        cursorCanvasY = cy
        pointerDown = false
    }

    /** Map canvas position → pane-local inject coords; focus pane on cross. */
    private fun projectCanvasToPane(layout: HidSplitLayout) {
        val (cx, cy) = layout.clampCanvas(cursorCanvasX, cursorCanvasY)
        cursorCanvasX = cx
        cursorCanvasY = cy
        when (val hit = layout.hit(cx, cy, chromeInteract = false)) {
            is HidCursorHit.Pane -> {
                val paneChanged = hit.pane != cursorPane
                cursorPane = hit.pane
                cursorX = hit.x
                cursorY = hit.y
                if (paneChanged) {
                    if (pointerDown) {
                        cancelPaneFinger()
                    }
                    logDebug(
                        TAG,
                        "canvas → pane $cursorPane @ ${cursorX.toInt()},${cursorY.toInt()} " +
                            "canvas=${cx.toInt()},${cy.toInt()}",
                    )
                    CoreManagerService.focusHidPane(cursorPane)
                    if (!dividerGestureActive) {
                        publishHidCursorOverlay(layout, force = true)
                    }
                }
            }
            else -> {
                val (lx, ly) = layout.paneLocalOf(cursorPane, cx, cy)
                cursorX = lx
                cursorY = ly
            }
        }
    }

    /**
     * Place the logical cursor on the edge of [pane] that faces the sibling
     * (or center). Used for middle-click pane switch — not seam crosses.
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
        val (cx, cy) = layout.toCanvas(cursorPane, cursorX, cursorY)
        cursorCanvasX = cx
        cursorCanvasY = cy
    }

    /** Relative mouse in canvas space — free cross-pane movement (extended-desktop model). */
    private fun applyRelativeMove(layout: HidSplitLayout, dx: Float, dy: Float) {
        val tw = layout.totalW.toFloat().coerceAtLeast(1f)
        val th = layout.totalH.toFloat().coerceAtLeast(1f)
        cursorCanvasX = (cursorCanvasX + dx).coerceIn(0f, tw - 1f)
        cursorCanvasY = (cursorCanvasY + dy).coerceIn(0f, th - 1f)
        projectCanvasToPane(layout)
    }

    /** Near the three-dot grip or fullscreen peel — LMB → shell chrome. */
    private fun wantsDividerChrome(layout: HidSplitLayout): Boolean {
        return layout.hit(cursorCanvasX, cursorCanvasY, chromeInteract = true) is HidCursorHit.Divider
    }

    /** Begin divider / peel drag on AaDisplay presentation; always consumes the press. */
    private fun startDividerGesture(layout: HidSplitLayout): Boolean {
        if (!wantsDividerChrome(layout)) return false
        cancelPaneFinger()
        dividerGestureActive = true
        dividerDownTime = SystemClock.uptimeMillis()
        dividerX = cursorCanvasX
        dividerY = cursorCanvasY
        refreshOverlayShell(layout)
        val (sx, sy) = shellTouchPoint(layout, dividerX, dividerY)
        CoreManagerService.injectHidAaUiTouch(
            MotionEvent.ACTION_DOWN, sx, sy, dividerDownTime, dividerDownTime,
        )
        publishOverlayShell(sx, sy, force = true)
        return true
    }

    private fun shellTouchPoint(layout: HidSplitLayout, x: Float, y: Float): Pair<Float, Float> =
        layout.toShellTouch(x, y)

    /** Recents / picker: inject onto AaDisplay presentation so overlay stays operable. */
    private fun routeShellOverlay(layout: HidSplitLayout, event: MotionEvent): Boolean {
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
                // Cursor overlay already coalesced on relative move; force only on click.
                publishHidCursorOverlay(layout, force = true)
                return inject(MotionEvent.ACTION_DOWN, shellX, shellY, shellDownTime, shellDownTime)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                if (!shellGestureActive) {
                    // Hover while picker open: coalesced overlay only (no force flood).
                    if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                        publishHidCursorOverlay(layout, force = false)
                    }
                    return true
                }
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

    /** End divider chrome drag — keep overlay at release point for subsequent hover. */
    private fun commitDividerCursor(layout: HidSplitLayout) {
        cursorCanvasX = dividerX
        cursorCanvasY = dividerY
        syncPaneLocalFromCanvas(layout)
    }

    private fun syncPaneLocalFromCanvas(layout: HidSplitLayout) {
        val (cx, cy) = layout.clampCanvas(cursorCanvasX, cursorCanvasY)
        cursorCanvasX = cx
        cursorCanvasY = cy
        when (val hit = layout.hit(cx, cy, chromeInteract = false)) {
            is HidCursorHit.Pane -> {
                val paneChanged = hit.pane != cursorPane
                cursorPane = hit.pane
                cursorX = hit.x
                cursorY = hit.y
                if (paneChanged) {
                    if (pointerDown) cancelPaneFinger()
                    CoreManagerService.focusHidPane(cursorPane)
                }
            }
            else -> {
                val (lx, ly) = layout.paneLocalOf(cursorPane, cx, cy)
                cursorX = lx
                cursorY = ly
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
                if (!pressed && event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                    publishOverlayShell(overlayShellX, overlayShellY, force = true)
                    return true
                }
                val (sx, sy) = shellTouchPoint(layout, dividerX, dividerY)
                inject(
                    MotionEvent.ACTION_MOVE, dividerX, dividerY, dividerDownTime, SystemClock.uptimeMillis(),
                )
                publishOverlayShell(sx, sy, force = true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_CANCEL -> {
                if (!dividerGestureActive) return false
                val action =
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) MotionEvent.ACTION_CANCEL
                    else MotionEvent.ACTION_UP
                val (sx, sy) = shellTouchPoint(layout, dividerX, dividerY)
                inject(action, dividerX, dividerY, dividerDownTime, SystemClock.uptimeMillis())
                commitDividerCursor(layout)
                dividerGestureActive = false
                publishOverlayShell(sx, sy, force = true)
                return true
            }
            else -> {
                if (!dividerGestureActive) return false
                if (pressed) {
                    val (sx, sy) = shellTouchPoint(layout, dividerX, dividerY)
                    inject(
                        MotionEvent.ACTION_MOVE, dividerX, dividerY, dividerDownTime, SystemClock.uptimeMillis(),
                    )
                    publishOverlayShell(sx, sy, force = true)
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
        val next = layout.otherPane(cursorPane)
        resetCursorOnPane(layout, next, enterFromSeam = !center)
        CoreManagerService.focusHidPane(cursorPane)
        publishHidCursorOverlay(layout, force = true)
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
