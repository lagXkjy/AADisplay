package io.github.nitsuya.aa.display.ui.aa.split

import android.view.Display
import android.view.WindowManager
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.logDebug
import java.util.function.Consumer

/**
 * Blocks / removes [Presentation] windows that leak onto the wrong AA VirtualDisplay pane.
 * Seen with Douyin after soft reconnect / LivePlay: task on SECONDARY while a fullscreen
 * ty=PRESENTATION window covers PRIMARY (MediaRouter picks the other VD).
 *
 * OneUI: [WindowContainer.forAllWindows] is `(Consumer, boolean)` — a size-1 lookup never
 * matches, so eviction was a no-op until the boolean overload was used.
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
        val delays = longArrayOf(0L, 600L, 1800L)
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
        val removed = tryOrNull {
            val wms = Class.forName("android.view.WindowManagerGlobal")
                .getDeclaredMethod("getWindowManagerService")
                .apply { isAccessible = true }
                .invoke(null) ?: return@tryOrNull 0
            val root = wms.javaClass.getField("mRoot").get(wms) ?: return@tryOrNull 0
            val displayContent = root.javaClass.methods.firstOrNull { m ->
                m.name == "getDisplayContent" &&
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType
            }?.invoke(root, displayId) ?: return@tryOrNull 0
            val forAllWindows = resolveForAllWindows(displayContent.javaClass) ?: return@tryOrNull 0
            val victims = mutableListOf<Pair<Any, String>>()
            val consumer = Consumer<Any> { windowState ->
                val attrs = runCatching {
                    windowState.javaClass.getField("mAttrs").get(windowState) as WindowManager.LayoutParams
                }.getOrNull() ?: return@Consumer
                if (!isPresentationType(attrs.type)) return@Consumer
                val pkg = packageForWindowState(c, windowState) ?: return@Consumer
                if (pkg == ownerPkg || pkg == BuildConfig.APPLICATION_ID) return@Consumer
                victims += windowState to pkg
            }
            invokeForAllWindows(forAllWindows, displayContent, consumer)
            if (victims.isEmpty()) return@tryOrNull 0
            val removeWindow = wms.javaClass.methods.firstOrNull { m ->
                m.name == "removeWindow" &&
                    m.parameterTypes.size == 2 &&
                    m.parameterTypes[0].name.contains("Session")
            } ?: return@tryOrNull 0
            var count = 0
            for ((windowState, pkg) in victims) {
                val session = runCatching {
                    windowState.javaClass.getField("mSession").get(windowState)
                }.getOrNull() ?: continue
                val client = runCatching {
                    windowState.javaClass.getField("mClient").get(windowState)
                }.getOrNull() ?: continue
                runCatching {
                    removeWindow.invoke(wms, session, client)
                    count++
                    log(
                        SplitDisplayController.TAG,
                        "evictPresentation[$reason]: removed $pkg from display=$displayId (owner=$ownerPkg)"
                    )
                }
            }
            count
        } ?: 0
        if (removed == 0) {
            logDebug(
                SplitDisplayController.TAG,
                "evictPresentation[$reason]: display=$displayId owner=$ownerPkg nothing to remove"
            )
        }
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

    fun packageForUid(context: android.content.Context, uid: Int): String? {
        return tryOrNull {
            context.packageManager.getPackagesForUid(uid)?.firstOrNull { it.isNotBlank() }
        }
    }

    private fun packageForWindowState(c: SplitDisplayController, windowState: Any): String? {
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
