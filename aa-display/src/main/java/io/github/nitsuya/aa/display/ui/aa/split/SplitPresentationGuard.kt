package io.github.nitsuya.aa.display.ui.aa.split

import android.view.Display
import android.view.WindowManager
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.util.function.Consumer

/**
 * Blocks / removes [Presentation] windows that leak onto the wrong AA VirtualDisplay pane.
 * Seen with Douyin LivePlay: task on SECONDARY while a fullscreen ty=PRESENTATION window
 * covers PRIMARY (MediaRouter / WindowContext picks the other VD).
 *
 * OneUI: [WindowContainer.forAllWindows] is `(Consumer, boolean)`. Prefer
 * [WindowState.removeImmediately] + [WindowState.getOwningPackage] over Session reflection.
 */
internal object SplitPresentationGuard {

    private const val TYPE_PRESENTATION = 2037
    /** OEM / hidden alias seen on Samsung alongside [TYPE_PRESENTATION]. */
    private const val TYPE_PRIVATE_PRESENTATION = 2038

    /** Debounce stack-change storms before arming the 0/600/1800ms eviction wave. */
    private const val STACK_EVICT_DEBOUNCE_MS = 180L

    internal val EVICT_TOKEN = Any()
    private val STACK_EVICT_TOKEN = Any()

    fun scheduleEvictForeignPresentations(c: SplitDisplayController, reason: String) {
        c.mHandler.removeCallbacksAndMessages(EVICT_TOKEN)
        val delays = longArrayOf(0L, 350L, 900L, 2000L)
        for (delay in delays) {
            c.mHandler.postDelayed({
                if (c.mIsDestroying) return@postDelayed
                evictForeignPresentations(c, "$reason@${delay}ms")
            }, EVICT_TOKEN, delay)
        }
    }

    /** Task-stack / LivePlay safety net — debounced so focus storms do not cancel mid-wave. */
    fun scheduleEvictOnStackChanged(c: SplitDisplayController) {
        c.mHandler.removeCallbacksAndMessages(STACK_EVICT_TOKEN)
        c.mHandler.postDelayed({
            if (c.mIsDestroying) return@postDelayed
            scheduleEvictForeignPresentations(c, "stack")
        }, STACK_EVICT_TOKEN, STACK_EVICT_DEBOUNCE_MS)
    }

    fun evictForeignPresentations(c: SplitDisplayController, reason: String) {
        for (pane in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
            val displayId = c.input.displayIdFor(pane) ?: continue
            val ownerPkg = c.mPanePackages[pane]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            evictOnDisplay(c, displayId, ownerPkg, reason)
        }
    }

    private fun evictOnDisplay(
        c: SplitDisplayController,
        displayId: Int,
        ownerPkg: String,
        reason: String,
    ) {
        if (displayId == Display.INVALID_DISPLAY) return
        tryOrNull {
            val wms = Class.forName("android.view.WindowManagerGlobal")
                .getDeclaredMethod("getWindowManagerService")
                .apply { isAccessible = true }
                .invoke(null) ?: run {
                    logDebug(SplitDisplayController.TAG, "evictPresentation[$reason]: no WMS")
                    return@tryOrNull 0
                }
            val root = runCatching { wms.javaClass.getField("mRoot").get(wms) }.getOrNull()
                ?: run {
                    logDebug(SplitDisplayController.TAG, "evictPresentation[$reason]: no mRoot")
                    return@tryOrNull 0
                }
            val displayContent = root.javaClass.methods.firstOrNull { m ->
                m.name == "getDisplayContent" &&
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType
            }?.invoke(root, displayId) ?: run {
                logDebug(SplitDisplayController.TAG, "evictPresentation[$reason]: no DisplayContent id=$displayId")
                return@tryOrNull 0
            }
            val forAllWindows = resolveForAllWindows(displayContent.javaClass) ?: run {
                logDebug(SplitDisplayController.TAG, "evictPresentation[$reason]: forAllWindows missing")
                return@tryOrNull 0
            }
            val victims = mutableListOf<Any>()
            val consumer = Consumer<Any> { windowState ->
                val attrs = runCatching {
                    windowState.javaClass.getField("mAttrs").get(windowState) as WindowManager.LayoutParams
                }.getOrNull() ?: return@Consumer
                if (!isPresentationType(attrs.type)) return@Consumer
                val pkg = packageForWindowState(c, windowState) ?: return@Consumer
                if (!isForeignPresentationCaller(ownerPkg, pkg)) return@Consumer
                victims += windowState
            }
            try {
                invokeForAllWindows(forAllWindows, displayContent, consumer)
            } catch (e: Throwable) {
                log(SplitDisplayController.TAG, "evictPresentation[$reason]: forAllWindows failed", e)
                return@tryOrNull 0
            }
            if (victims.isEmpty()) return@tryOrNull 0
            var count = 0
            for (windowState in victims) {
                val pkg = packageForWindowState(c, windowState) ?: "?"
                if (removeWindowState(wms, windowState)) {
                    count++
                    log(
                        SplitDisplayController.TAG,
                        "evictPresentation[$reason]: removed $pkg from display=$displayId (owner=$ownerPkg)"
                    )
                } else {
                    log(
                        SplitDisplayController.TAG,
                        "evictPresentation[$reason]: failed to remove $pkg from display=$displayId"
                    )
                }
            }
            count
        }
    }

    private fun removeWindowState(wms: Any, windowState: Any): Boolean {
        // Already gone / mid-teardown — calling removeImmediately then SIGSEGVs in
        // SurfaceControl.Transaction.reparent (native NPE; Java catch cannot save us).
        if (isWindowStateAlreadyGone(windowState)) return false
        // Prefer the Session path: stays in Java and fails safely if the token is stale.
        if (removeViaWmsSession(wms, windowState)) return true
        if (!hasLiveSurfaceControl(windowState)) {
            logDebug(
                SplitDisplayController.TAG,
                "evictPresentation: skip removeImmediately (no live SurfaceControl)"
            )
            return false
        }
        return runCatching {
            val m = windowState.javaClass.methods.firstOrNull {
                it.name == "removeImmediately" && it.parameterTypes.isEmpty()
            } ?: return@runCatching false
            m.invoke(windowState)
            true
        }.onFailure { e ->
            log(SplitDisplayController.TAG, "evictPresentation: removeImmediately failed", e)
        }.getOrDefault(false)
    }

    private fun removeViaWmsSession(wms: Any, windowState: Any): Boolean {
        return runCatching {
            val removeWindow = wms.javaClass.methods.firstOrNull { m ->
                m.name == "removeWindow" &&
                    m.parameterTypes.size == 2 &&
                    m.parameterTypes[0].name.contains("Session")
            } ?: return@runCatching false
            val session = windowState.javaClass.getField("mSession").get(windowState)
                ?: return@runCatching false
            val client = windowState.javaClass.getField("mClient").get(windowState)
                ?: return@runCatching false
            removeWindow.invoke(wms, session, client)
            true
        }.getOrDefault(false)
    }

    /** True when removeImmediately would touch a dead / missing layer. */
    private fun isWindowStateAlreadyGone(windowState: Any): Boolean {
        runCatching {
            val f = windowState.javaClass.getField("mRemoved")
            if (f.getBoolean(windowState)) return true
        }
        runCatching {
            val m = windowState.javaClass.methods.firstOrNull {
                it.name == "isRemoved" && it.parameterTypes.isEmpty()
            }
            if (m?.invoke(windowState) == true) return true
        }
        runCatching {
            val f = windowState.javaClass.getField("mHasSurface")
            // No surface yet or already torn down — Session remove is enough / safer.
            if (!f.getBoolean(windowState)) return true
        }
        return false
    }

    private fun hasLiveSurfaceControl(windowState: Any): Boolean {
        val sc = runCatching {
            windowState.javaClass.methods.firstOrNull {
                it.name == "getSurfaceControl" && it.parameterTypes.isEmpty()
            }?.invoke(windowState)
        }.getOrNull() ?: return false
        return runCatching {
            sc.javaClass.methods.firstOrNull {
                it.name == "isValid" && it.parameterTypes.isEmpty()
            }?.invoke(sc) == true
        }.getOrDefault(true)
    }

    /** Prefer OneUI `(Consumer, boolean)`; fall back to AOSP single-arg if present. */
    private fun resolveForAllWindows(displayContentClass: Class<*>): java.lang.reflect.Method? {
        val methods = displayContentClass.methods.filter { it.name == "forAllWindows" }
        methods.firstOrNull { m ->
            m.parameterTypes.size == 2 &&
                m.parameterTypes[0].name.contains("Consumer") &&
                m.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }?.let { return it }
        return methods.firstOrNull { m ->
            m.parameterTypes.size == 1 && m.parameterTypes[0].name.contains("Consumer")
        }
    }

    private fun invokeForAllWindows(
        method: java.lang.reflect.Method,
        displayContent: Any,
        consumer: Consumer<Any>,
    ) {
        if (method.parameterTypes.size == 2) {
            // traverseTopToBottom = true — Presentation usually sits above the pane owner.
            method.invoke(displayContent, consumer, true)
        } else {
            method.invoke(displayContent, consumer)
        }
    }

    fun isPresentationType(type: Int): Boolean {
        return type == TYPE_PRESENTATION || type == TYPE_PRIVATE_PRESENTATION
    }

    /** True when [callerPkg] is neither the pane owner nor this module. */
    fun isForeignPresentationCaller(ownerPkg: String, callerPkg: String): Boolean {
        return callerPkg != ownerPkg && callerPkg != BuildConfig.APPLICATION_ID
    }

    fun packageForUid(context: android.content.Context, uid: Int): String? {
        return tryOrNull {
            context.packageManager.getPackagesForUid(uid)?.firstOrNull { it.isNotBlank() }
        }
    }

    private fun packageForWindowState(c: SplitDisplayController, windowState: Any): String? {
        runCatching {
            windowState.javaClass.methods.firstOrNull {
                it.name == "getOwningPackage" && it.parameterTypes.isEmpty()
            }?.invoke(windowState) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        val session = runCatching {
            windowState.javaClass.getField("mSession").get(windowState)
        }.getOrNull() ?: return null
        val uid = runCatching {
            session.javaClass.methods.firstOrNull { it.name == "getUid" && it.parameterTypes.isEmpty() }
                ?.invoke(session) as? Int
        }.getOrNull() ?: return null
        return packageForUid(c.context, uid)
    }
}
