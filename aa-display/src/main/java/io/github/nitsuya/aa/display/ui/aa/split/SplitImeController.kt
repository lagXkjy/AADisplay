package io.github.nitsuya.aa.display.ui.aa.split

import android.content.Context
import android.content.Intent
import android.os.ServiceManager
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.util.AaSystemBroadcast
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.function.Consumer

/**
 * Detect IME on pane VirtualDisplays and hide it without [SplitDisplayController.onPressKey]
 * (which bringTaskToFront-s and can leave a stuck keyboard).
 *
 * AA shell shows a hide chip from [AABroadcastConst.ACTION_IME_VISIBILITY].
 */
internal class SplitImeController(private val c: SplitDisplayController) {
    companion object {
        private const val POLL_HIDDEN_MS = 400L
        private const val POLL_VISIBLE_MS = 200L
        /** SoftInputShowHideReason.HIDE_SOFT_INPUT */
        private const val HIDE_REASON_HIDE_SOFT_INPUT = 4
        private const val BACK_FALLBACK_DELAY_MS = 80L
    }

    @Volatile
    var visible: Boolean = false
        private set
    @Volatile
    var imePane: Int = SplitPane.PRIMARY
        private set

    private var resolved = false
    private var localGetService: Method? = null
    private var wms: Any? = null
    private var getDisplayContent: Method? = null
    private var forAllWindows: Method? = null
    private var windowAttrsField: Field? = null
    private var windowIsVisible: Method? = null
    private var wmi: Any? = null
    private var wmiHideIme: Method? = null
    private var immInternal: Any? = null
    private var immHide: Method? = null
    private var immsInputShown: Field? = null
    private var imms: Any? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (c.mIsDestroying) return
            refreshAndNotify()
            val delay = if (visible) POLL_VISIBLE_MS else POLL_HIDDEN_MS
            c.mHandler.postDelayed(this, delay)
        }
    }

    fun start() {
        c.mHandler.removeCallbacks(pollRunnable)
        c.mHandler.post(pollRunnable)
    }

    fun stop() {
        c.mHandler.removeCallbacks(pollRunnable)
        if (visible) {
            visible = false
            broadcast(SplitPane.FULLSCREEN_NONE)
        }
    }

    fun currentPane(): Int = if (visible) imePane else SplitPane.FULLSCREEN_NONE

    fun hide() {
        if (c.mIsDestroying) return
        ensureResolved()
        val pane = if (visible) imePane else c.mFocusedPane
        val displayId = c.input.displayIdFor(pane)
            ?: c.input.displayIdFor(c.mFocusedPane)
        if (displayId != null && displayId != Display.INVALID_DISPLAY) {
            val sawImeWindow = isImeWindowVisible(displayId)
            hideViaWindowManager(displayId)
            hideViaInputMethodManager()
            c.mHandler.postDelayed({
                if (c.mIsDestroying) return@postDelayed
                refreshAndNotify()
                if (visible && sawImeWindow) {
                    // IME consumes the first BACK. Do not bringTaskToFront first.
                    injectBack(displayId)
                    c.mHandler.postDelayed({
                        if (!c.mIsDestroying) refreshAndNotify()
                    }, BACK_FALLBACK_DELAY_MS)
                }
            }, BACK_FALLBACK_DELAY_MS)
        } else {
            hideViaInputMethodManager()
            c.mHandler.postDelayed({
                if (!c.mIsDestroying) refreshAndNotify()
            }, BACK_FALLBACK_DELAY_MS)
        }
    }

    private fun refreshAndNotify() {
        val (shown, pane) = try {
            detect()
        } catch (e: Throwable) {
            logDebug(SplitDisplayController.TAG, "ime detect: ${e.message}")
            return
        }
        if (shown == visible && (!shown || pane == imePane)) return
        visible = shown
        if (shown) imePane = pane
        logDebug(SplitDisplayController.TAG, "ime visible=$shown pane=$pane")
        broadcast(if (shown) pane else SplitPane.FULLSCREEN_NONE)
    }

    private fun detect(): Pair<Boolean, Int> {
        ensureResolved()
        val focused = c.mFocusedPane
        val order = intArrayOf(
            focused,
            if (focused == SplitPane.PRIMARY) SplitPane.SECONDARY else SplitPane.PRIMARY,
        )
        for (pane in order) {
            val id = c.input.displayIdFor(pane) ?: continue
            if (isImeWindowVisible(id)) return true to pane
        }
        if (isImmsInputShown()) {
            return true to focused
        }
        return false to focused
    }

    private fun isImeWindowVisible(displayId: Int): Boolean {
        if (displayId == Display.INVALID_DISPLAY) return false
        val dc = displayContent(displayId) ?: return false
        val walk = forAllWindows ?: return false
        var found = false
        val visitor = Consumer<Any> { w ->
            if (found) return@Consumer
            if (isInputMethodWindow(w) && isWindowVisible(w)) {
                found = true
            }
        }
        return try {
            walk.invoke(dc, visitor, java.lang.Boolean.TRUE)
            found
        } catch (e: Throwable) {
            logDebug(SplitDisplayController.TAG, "ime forAllWindows: ${e.message}")
            false
        }
    }

    private fun isInputMethodWindow(window: Any): Boolean {
        val attrs = windowAttrs(window) ?: return false
        val type = attrs.type
        return type == WindowManager.LayoutParams.TYPE_INPUT_METHOD ||
            type == WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG
    }

    private fun windowAttrs(window: Any): WindowManager.LayoutParams? {
        val cached = windowAttrsField
        if (cached != null) {
            return try {
                cached.get(window) as? WindowManager.LayoutParams
            } catch (_: Throwable) {
                null
            }
        }
        var cls: Class<*>? = window.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField("mAttrs")
                f.isAccessible = true
                windowAttrsField = f
                return f.get(window) as? WindowManager.LayoutParams
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return try {
            findInstanceMethod(window, "getAttrs", emptyArray())
                ?.invoke(window) as? WindowManager.LayoutParams
        } catch (_: Throwable) {
            null
        }
    }

    private fun isWindowVisible(window: Any): Boolean {
        val cached = windowIsVisible
        if (cached != null) {
            return try {
                cached.invoke(window) as? Boolean == true
            } catch (_: Throwable) {
                false
            }
        }
        for (name in arrayOf("isVisible", "isVisibleNow", "isVisibleRequested", "isOnScreen")) {
            val m = findInstanceMethod(window, name, emptyArray()) ?: continue
            windowIsVisible = m
            return try {
                m.invoke(window) as? Boolean == true
            } catch (_: Throwable) {
                false
            }
        }
        return false
    }

    private fun isImmsInputShown(): Boolean {
        val field = immsInputShown ?: return false
        val svc = imms ?: return false
        return try {
            field.getBoolean(svc)
        } catch (_: Throwable) {
            false
        }
    }

    private fun hideViaWindowManager(displayId: Int): Boolean {
        val target = wmi ?: return false
        val method = wmiHideIme ?: return false
        return try {
            invokeHideIme(method, target, displayId)
            true
        } catch (e: Throwable) {
            logDebug(SplitDisplayController.TAG, "wmi hideIme: ${e.message}")
            false
        }
    }

    private fun invokeHideIme(method: Method, target: Any, displayId: Int) {
        val params = method.parameterTypes
        val args = Array<Any?>(params.size) { i ->
            when {
                params[i] == Int::class.javaPrimitiveType || params[i] == Int::class.java -> {
                    if (i == 0) displayId else HIDE_REASON_HIDE_SOFT_INPUT
                }
                else -> null
            }
        }
        method.invoke(target, *args)
    }

    private fun hideViaInputMethodManager(): Boolean {
        val target = immInternal ?: return false
        val method = immHide ?: return false
        return try {
            val params = method.parameterTypes
            if (params.size == 1 &&
                (params[0] == Int::class.javaPrimitiveType || params[0] == Int::class.java)
            ) {
                method.invoke(target, HIDE_REASON_HIDE_SOFT_INPUT)
            } else {
                method.invoke(target, *Array(params.size) { null })
            }
            true
        } catch (e: Throwable) {
            logDebug(SplitDisplayController.TAG, "imm hideCurrentInputMethod: ${e.message}")
            false
        }
    }

    private fun injectBack(displayId: Int) {
        val down = c.input.createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val up = c.input.createKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
        c.input.injectInputEvent(displayId, down)
        c.input.injectInputEvent(displayId, up)
    }

    private fun displayContent(displayId: Int): Any? {
        val wms = wms ?: return null
        val getter = getDisplayContent ?: return null
        return try {
            getter.invoke(wms, displayId)
        } catch (_: Throwable) {
            null
        }
    }

    private fun broadcast(pane: Int) {
        try {
            AaSystemBroadcast.toAaDisplay(
                c.context,
                Intent(AABroadcastConst.ACTION_IME_VISIBILITY).putExtra(
                    AABroadcastConst.EXTRA_PANE,
                    pane,
                ),
            )
        } catch (e: Throwable) {
            logDebug(SplitDisplayController.TAG, "ime broadcast: ${e.message}")
        }
    }

    private fun ensureResolved() {
        if (resolved) return
        resolved = true
        try {
            localGetService = AndroidHook.findSystemMethod("com.android.server.LocalServices") {
                name == "getService" && parameterTypes.size == 1 && parameterTypes[0] == Class::class.java
            }
            resolveWindowManager()
            resolveInputMethodManager()
        } catch (e: Throwable) {
            log(SplitDisplayController.TAG, "ime resolve failed:", e)
        }
    }

    private fun resolveWindowManager() {
        val binder = try {
            ServiceManager.getService(Context.WINDOW_SERVICE)
        } catch (_: Throwable) {
            null
        }
        val fromBinder = binder?.takeIf { candidate ->
            val n = candidate.javaClass.name
            n.contains("WindowManagerService") && !n.contains("Proxy")
        }
        val fromWmi = localService("com.android.server.wm.WindowManagerInternal")?.also { svc ->
            wmi = svc
            wmiHideIme = findNamedMethod(svc, "hideIme")
        }?.let { outerInstance(it) }
        wms = fromBinder ?: fromWmi
        val wm = wms ?: return
        getDisplayContent = findIntDisplayContent(wm)
        if (getDisplayContent == null) {
            val root = fieldValue(wm, "mRoot")
            if (root != null) {
                wms = root
                getDisplayContent = findIntDisplayContent(root)
            }
        }
        val probeId = c.primaryDisplayId.takeIf { it != Display.INVALID_DISPLAY }
            ?: c.secondaryDisplayId
        val dc = if (probeId != Display.INVALID_DISPLAY) displayContent(probeId) else null
        if (dc != null) {
            forAllWindows = dc.javaClass.methods.firstOrNull { m ->
                m.name == "forAllWindows" &&
                    m.parameterTypes.size == 2 &&
                    m.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    Consumer::class.java.isAssignableFrom(m.parameterTypes[0])
            }
        }
    }

    private fun resolveInputMethodManager() {
        val svc = localService("com.android.server.inputmethod.InputMethodManagerInternal") ?: return
        immInternal = svc
        immHide = findNamedMethod(svc, "hideCurrentInputMethod")
            ?: findNamedMethod(svc, "hideSoftInput")
        val outer = outerInstance(svc) ?: return
        imms = outer
        var cls: Class<*>? = outer.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField("mInputShown")
                f.isAccessible = true
                immsInputShown = f
                break
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
    }

    private fun localService(className: String): Any? {
        val get = localGetService ?: return null
        val type = AndroidHook.loadSystemClass(className) ?: return null
        return try {
            get.invoke(null, type)
        } catch (e: Throwable) {
            logDebug(SplitDisplayController.TAG, "LocalServices $className: ${e.message}")
            null
        }
    }

    private fun outerInstance(inner: Any): Any? {
        var cls: Class<*>? = inner.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField("this\$0")
                f.isAccessible = true
                return f.get(inner)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun fieldValue(obj: Any, name: String): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun findIntDisplayContent(obj: Any): Method? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredMethods.firstOrNull { m ->
                m.name == "getDisplayContent" &&
                    m.parameterTypes.size == 1 &&
                    (m.parameterTypes[0] == Int::class.javaPrimitiveType ||
                        m.parameterTypes[0] == Int::class.java)
            }?.let {
                it.isAccessible = true
                return it
            }
            cls = cls.superclass
        }
        return obj.javaClass.methods.firstOrNull { m ->
            m.name == "getDisplayContent" &&
                m.parameterTypes.size == 1 &&
                (m.parameterTypes[0] == Int::class.javaPrimitiveType ||
                    m.parameterTypes[0] == Int::class.java)
        }?.apply { isAccessible = true }
    }

    private fun findNamedMethod(obj: Any, name: String): Method? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredMethods.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                return it
            }
            cls = cls.superclass
        }
        return obj.javaClass.methods.firstOrNull { it.name == name }?.apply {
            isAccessible = true
        }
    }

    private fun findInstanceMethod(obj: Any, name: String, params: Array<Class<*>>): Method? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val m = if (params.isEmpty()) cls.getDeclaredMethod(name) else cls.getDeclaredMethod(name, *params)
                m.isAccessible = true
                return m
            } catch (_: NoSuchMethodException) {
                cls = cls.superclass
            }
        }
        return try {
            if (params.isEmpty()) obj.javaClass.getMethod(name) else obj.javaClass.getMethod(name, *params)
        } catch (_: Throwable) {
            null
        }
    }
}
