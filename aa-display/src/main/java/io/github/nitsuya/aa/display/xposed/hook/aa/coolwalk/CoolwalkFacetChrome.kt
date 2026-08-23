package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug

object CoolwalkFacetChrome {

    private lateinit var env: CoolwalkHookEnv

    fun install(hookEnv: CoolwalkHookEnv) {
        env = hookEnv
        hookFacetBar(env)
        hookFacetWindowAttach(env)
    }

    private fun hookFacetBar(env: CoolwalkHookEnv) {
        findMethod(LayoutInflater::class.java) {
            name == "inflate"
            && parameterCount == 3
            && parameterTypes[0] == Int::class.javaPrimitiveType
            && parameterTypes[1] == ViewGroup::class.java
            && parameterTypes[2] == Boolean::class.javaPrimitiveType
        }.hookAfter { param ->
            if (env.mInjectingFacetBar) return@hookAfter
            val layoutResId = param.args[0] as Int
            val resultViewGroup = param.result as? ViewGroup ?: return@hookAfter
            if (resultViewGroup.tag === env.facetBarInjectedTag) {
                return@hookAfter
            }
            val matchedById = env.facetBarLayoutIds.contains(layoutResId)
            val matchedByContent = !matchedById && isCoolwalkFacetBarContent(env, resultViewGroup)
            if (matchedById || matchedByContent) {
                try {
                    env.mInjectingFacetBar = true
                    injectAaFacetBarReplaceResult(
                        env,
                        resultViewGroup,
                        if (matchedById) "layoutId=$layoutResId" else "content",
                    )
                } catch (e: Throwable) {
                    log(CoolwalkHookEnv.TAG, "AaUiHook: inject facet bar failed [$layoutResId]", e)
                } finally {
                    env.mInjectingFacetBar = false
                }
                return@hookAfter
            }
            if (env.railHostLayoutIds.contains(layoutResId)) {
                if (!tryInjectIntoFacetColumn(env, resultViewGroup, "rail:$layoutResId")) {
                    scheduleEnsureFacetBar(env, "rail:$layoutResId")
                }
                scheduleReclaimLeftGutter(env, resultViewGroup)
            }
        }
    }

    private fun hookFacetWindowAttach(env: CoolwalkHookEnv) {
        try {
            findMethod(Class.forName("android.view.WindowManagerGlobal")) {
                name == "addView" && parameterCount >= 1
            }.hookAfter { param ->
                if (!env.canHookFacetBar || env.mInjectingFacetBar) return@hookAfter
                val root = param.args[0] as? ViewGroup ?: return@hookAfter
                root.post {
                    if (!env.canHookFacetBar || env.mInjectingFacetBar) return@post
                    reclaimLeftGutter(env, root)
                    dispatchGutterReclaim("windowAttach")
                    if (!hasInjectedFacet(env, root) && containsFacetChrome(env, root)) {
                        scheduleEnsureFacetBar(env, "windowAttach")
                    }
                }
            }
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: hooked WindowManagerGlobal.addView for facet ensure")
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: hook WindowManagerGlobal.addView failed", e)
        }
    }

    fun scheduleEnsureFacetBar(env: CoolwalkHookEnv, reason: String, rearm: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (rearm || now >= env.mFacetEnsureDeadlineMs) {
            env.mFacetEnsureDeadlineMs = now + CoolwalkHookEnv.FACET_ENSURE_WINDOW_MS
        }
        env.mFacetEnsureHandler.removeCallbacksAndMessages(CoolwalkHookEnv.FACET_ENSURE_TOKEN)
        env.mFacetEnsureHandler.postAtTime(
            { ensureFacetBarInjected(env, reason) },
            CoolwalkHookEnv.FACET_ENSURE_TOKEN,
            now,
        )
    }

    private fun ensureFacetBarInjected(env: CoolwalkHookEnv, reason: String) {
        if (!env.canHookFacetBar || env.mInjectingFacetBar) return
        try {
            val roots = collectWindowRootViews()
            var attempted = 0
            for (root in roots) {
                if (!containsFacetChrome(env, root)) continue
                if (hasInjectedFacet(env, root)) continue
                if (tryInjectIntoFacetColumn(env, root, reason)) attempted++
            }
            if (attempted > 0) {
                logDebug(CoolwalkHookEnv.TAG, "AaUiHook: ensure facet injected [$reason] count=$attempted")
            }
            dispatchGutterReclaim("ensure:$reason")
            if (roots.any { hasInjectedFacet(env, it) }) return
            val now = SystemClock.uptimeMillis()
            if (now < env.mFacetEnsureDeadlineMs) {
                env.mFacetEnsureHandler.postAtTime(
                    { ensureFacetBarInjected(env, "$reason-poll") },
                    CoolwalkHookEnv.FACET_ENSURE_TOKEN,
                    now + CoolwalkHookEnv.FACET_ENSURE_POLL_MS,
                )
            } else if (reason.endsWith("-poll") || reason.indexOf('-') < 0) {
                log(
                    CoolwalkHookEnv.TAG,
                    "AaUiHook: ensure facet still missing [$reason] roots=${roots.size} " +
                        "chrome=${roots.count { containsFacetChrome(env, it) }}",
                )
            }
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: ensure facet failed [$reason]", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun collectWindowRootViews(): List<ViewGroup> {
        return try {
            val wmGlobalClass = Class.forName("android.view.WindowManagerGlobal")
            val instance = wmGlobalClass.getMethod("getInstance").invoke(null) ?: return emptyList()
            val views = try {
                wmGlobalClass.getMethod("getWindowViews").invoke(instance) as? List<*>
            } catch (_: Throwable) {
                val field = wmGlobalClass.getDeclaredField("mViews").apply { isAccessible = true }
                field.get(instance) as? List<*>
            } ?: return emptyList()
            views.mapNotNull { it as? ViewGroup }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun containsFacetChrome(env: CoolwalkHookEnv, root: ViewGroup): Boolean {
        if (root.findViewById<View>(env.resIdStatusBarId) == null) return false
        if (root.findViewById<View>(env.resIdLauncherAndDashboardIconContainerId) == null) return false
        if (root.findViewById<View>(env.resIdLauncherAndDashboardIconId) == null) return false
        return true
    }

    private fun hasInjectedFacet(env: CoolwalkHookEnv, root: ViewGroup): Boolean {
        if (root.tag === env.facetBarInjectedTag) return true
        val status = root.findViewById<View>(env.resIdStatusBarId) ?: return false
        var node: View? = status
        while (node != null) {
            if (node.tag === env.facetBarInjectedTag) return true
            node = node.parent as? View
        }
        return false
    }

    private fun findFacetColumn(env: CoolwalkHookEnv, root: ViewGroup): ViewGroup? {
        val status = root.findViewById<View>(env.resIdStatusBarId) ?: return null
        val launcherContainer = root.findViewById<View>(env.resIdLauncherAndDashboardIconContainerId) ?: return null
        var node = status.parent as? ViewGroup ?: return null
        while (true) {
            if (launcherContainer === node || isDescendantOf(node, launcherContainer)) {
                return node
            }
            if (node === root) break
            node = node.parent as? ViewGroup ?: break
        }
        return null
    }

    private fun isDescendantOf(ancestor: ViewGroup, child: View): Boolean {
        var node: View? = child
        while (node != null) {
            if (node === ancestor) return true
            node = node.parent as? View
        }
        return false
    }

    private fun tryInjectIntoFacetColumn(env: CoolwalkHookEnv, root: ViewGroup, reason: String): Boolean {
        if (env.mInjectingFacetBar) return false
        if (hasInjectedFacet(env, root)) return false
        val column = findFacetColumn(env, root) ?: return false
        if (column.tag === env.facetBarInjectedTag) return false
        return try {
            env.mInjectingFacetBar = true
            injectAaFacetBarInPlace(env, column, reason)
            true
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: in-place facet inject failed [$reason]", e)
            false
        } finally {
            env.mInjectingFacetBar = false
        }
    }

    private fun isCoolwalkFacetBarContent(env: CoolwalkHookEnv, root: ViewGroup): Boolean {
        if (!containsFacetChrome(env, root)) return false
        val w = root.layoutParams?.width ?: root.measuredWidth
        val h = root.layoutParams?.height ?: root.measuredHeight
        if (w > 0 && h > 0) {
            val min = minOf(w, h)
            val max = maxOf(w, h)
            if (min > 0 && max / min < 3) return false
        }
        return true
    }

    private fun injectAaFacetBarReplaceResult(env: CoolwalkHookEnv, resultViewGroup: ViewGroup, matchReason: String) {
        collapseFacetChromeInPlace(env, resultViewGroup, matchReason)
    }

    private fun injectAaFacetBarInPlace(env: CoolwalkHookEnv, facetHost: ViewGroup, reason: String) {
        collapseFacetChromeInPlace(env, facetHost, "inplace:$reason")
    }

    private fun collapseFacetChromeInPlace(env: CoolwalkHookEnv, facetHost: ViewGroup, reason: String) {
        facetHost.tag = env.facetBarInjectedTag
        AaCoolwalkAutoOpenHook.scheduleAutoOpenIfNeeded(env, "facet:$reason")
        logDebug(CoolwalkHookEnv.TAG, "AaUiHook: collapse facet rail ($reason)")
        reclaimRailSpace(env, facetHost)
        dispatchGutterReclaim("collapse:$reason")
    }

    private fun dispatchGutterReclaim(reason: String) {
        val actions = CoolwalkRailCoordinator.onEvent(RailEvent.GutterReclaim(reason)).second
        CoolwalkRailCoordinator.dispatchActions(actions)
    }

    private fun reclaimRailSpace(env: CoolwalkHookEnv, rail: View) {
        applyZeroWidthGone(env, rail)
        collapseThinRailChainAndExpandContent(env, rail)
        env.mReclaimFollowUps.remove(rail)?.let { rail.removeCallbacks(it) }
        val settle = Runnable {
            env.mReclaimFollowUps.remove(rail)
            if (!rail.isAttachedToWindow) return@Runnable
            collapseThinRailChainAndExpandContent(env, rail)
        }
        env.mReclaimFollowUps[rail] = settle
        rail.post(settle)
    }

    private fun scheduleReclaimLeftGutter(env: CoolwalkHookEnv, root: ViewGroup) {
        env.mReclaimFollowUps.remove(root)?.let { root.removeCallbacks(it) }
        val settle = Runnable {
            env.mReclaimFollowUps.remove(root)
            if (root.isAttachedToWindow) reclaimLeftGutter(env, root)
        }
        env.mReclaimFollowUps[root] = settle
        root.post(settle)
    }

    private fun maxSideRailPx(env: CoolwalkHookEnv, view: View): Int {
        val fullW = env.layoutWidthPx().takeIf { it > 0 }
            ?: view.rootView?.width?.takeIf { it > 0 }
            ?: view.width.takeIf { it > 0 }
            ?: view.resources.displayMetrics.widthPixels
        return CoolwalkRailMath.railPxRange(fullW.coerceAtLeast(1)).last
    }

    private fun applyZeroWidthGone(env: CoolwalkHookEnv, view: View, sourceLp: ViewGroup.LayoutParams? = null) {
        if (isWindowDecorOrRoot(view)) {
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: skip zero-width on ${view.javaClass.simpleName}")
            return
        }
        view.visibility = View.GONE
        view.isClickable = false
        view.isFocusable = false
        view.isEnabled = false
        view.isFocusableInTouchMode = false
        if (view is ViewGroup) {
            view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val lp = sourceLp ?: view.layoutParams
        if (lp != null) {
            lp.width = 0
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.marginStart = 0
                lp.leftMargin = 0
                lp.marginEnd = 0
                lp.rightMargin = 0
            }
            if (lp is LinearLayout.LayoutParams) {
                lp.weight = 0f
            }
            view.layoutParams = lp
        } else {
            view.layoutParams = ViewGroup.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    private fun isWindowDecorOrRoot(view: View): Boolean {
        val name = view.javaClass.name
        if (name.contains("DecorView") || name.endsWith("ViewRootImpl")) return true
        return view.isAttachedToWindow && view.parent == null && view === view.rootView
    }

    private fun measuredOrLpWidth(view: View): Int {
        view.width.takeIf { it > 0 }?.let { return it }
        view.measuredWidth.takeIf { it > 0 }?.let { return it }
        return view.layoutParams?.width?.takeIf { it > 0 } ?: 0
    }

    private fun measuredOrLpHeight(view: View): Int {
        view.height.takeIf { it > 0 }?.let { return it }
        view.measuredHeight.takeIf { it > 0 }?.let { return it }
        return view.layoutParams?.height?.takeIf { it > 0 } ?: 0
    }

    private fun isThinSideRail(view: View, maxSidePx: Int): Boolean {
        val w = measuredOrLpWidth(view)
        val h = measuredOrLpHeight(view)
        if (w <= 0) return view.visibility == View.GONE || (view.layoutParams?.width == 0)
        if (w > maxSidePx) return false
        return h <= 0 || h >= w * 2
    }

    private fun expandContentSibling(content: View) {
        val lp = content.layoutParams ?: return
        when (lp) {
            is LinearLayout.LayoutParams -> {
                lp.width = 0
                lp.weight = 1f
                lp.marginStart = 0
                lp.leftMargin = 0
            }
            else -> {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                if (lp is ViewGroup.MarginLayoutParams) {
                    lp.marginStart = 0
                    lp.leftMargin = 0
                }
            }
        }
        content.layoutParams = lp
        content.translationX = 0f
        clearStartPadding(content)
        (content.parent as? View)?.let { clearStartPadding(it) }
    }

    private fun clearStartPadding(view: View) {
        if (view.paddingStart == 0 && view.paddingLeft == 0) return
        view.setPaddingRelative(0, view.paddingTop, view.paddingEnd, view.paddingBottom)
    }

    private fun collapseThinRailChainAndExpandContent(env: CoolwalkHookEnv, from: View) {
        val maxSidePx = maxSideRailPx(env, from)
        var node: View = from
        applyZeroWidthGone(env, node)
        var depth = 0
        while (depth < 8) {
            val parent = node.parent as? ViewGroup ?: break
            when {
                parent is LinearLayout && parent.orientation == LinearLayout.HORIZONTAL -> {
                    applyZeroWidthGone(env, node)
                    for (i in 0 until parent.childCount) {
                        val c = parent.getChildAt(i) ?: continue
                        if (c === node || isThinSideRail(c, maxSidePx)) {
                            applyZeroWidthGone(env, c)
                        } else {
                            expandContentSibling(c)
                        }
                    }
                    parent.requestLayout()
                    logDebug(CoolwalkHookEnv.TAG, "AaUiHook: reclaimed left gutter via horizontal parent")
                    return
                }
                parent is ConstraintLayout -> {
                    applyZeroWidthGone(env, node)
                    expandConstraintContent(parent, node)
                    parent.requestLayout()
                    logDebug(CoolwalkHookEnv.TAG, "AaUiHook: reclaimed left gutter via constraint parent")
                    return
                }
                isThinSideRail(parent, maxSidePx) || parent.childCount <= 1 -> {
                    if (isWindowDecorOrRoot(parent)) break
                    applyZeroWidthGone(env, parent)
                    node = parent
                    depth++
                }
                else -> {
                    applyZeroWidthGone(env, node)
                    for (i in 0 until parent.childCount) {
                        val c = parent.getChildAt(i) ?: continue
                        if (c === node || isThinSideRail(c, maxSidePx)) {
                            applyZeroWidthGone(env, c)
                        } else {
                            expandContentSibling(c)
                        }
                    }
                    parent.requestLayout()
                    return
                }
            }
        }
    }

    private fun expandConstraintContent(parent: ConstraintLayout, rail: View) {
        if (rail.id == View.NO_ID) {
            rail.id = View.generateViewId()
        }
        val set = ConstraintSet()
        set.clone(parent)
        set.setVisibility(rail.id, View.GONE)
        set.constrainWidth(rail.id, 0)
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i) ?: continue
            if (c === rail || c.visibility == View.GONE) continue
            if (c.id == View.NO_ID) c.id = View.generateViewId()
            set.connect(c.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
            set.connect(c.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
            set.constrainWidth(c.id, ConstraintSet.MATCH_CONSTRAINT)
            expandContentSibling(c)
        }
        set.applyTo(parent)
    }

    fun reclaimAllWindowGutters(reason: String) {
        if (!::env.isInitialized) return
        if (!CoolwalkRailCoordinator.shouldReclaimAllGutters(reason)) {
            logDebug(
                CoolwalkHookEnv.TAG,
                "AaUiHook: skip reclaim all gutters [$reason] phase=${CoolwalkRailCoordinator.current().phase}",
            )
            return
        }
        val roots = collectWindowRootViews()
        var n = 0
        for (root in roots) {
            if (!root.isAttachedToWindow) continue
            reclaimLeftGutter(env, root)
            n++
        }
        if (n > 0) {
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: reclaim all gutters [$reason] roots=$n")
        }
    }

    private fun reclaimLeftGutter(env: CoolwalkHookEnv, root: ViewGroup) {
        try {
            val injected = root.findViewWithTag<View>(env.facetBarInjectedTag)
            if (injected != null) {
                reclaimRailSpace(env, injected)
            }
            val column = findFacetColumn(env, root)
            if (column != null) {
                reclaimRailSpace(env, column)
            }
            val maxSidePx = maxSideRailPx(env, root)
            val queue = ArrayDeque<ViewGroup>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val vg = queue.removeFirst()
                if (vg is LinearLayout && vg.orientation == LinearLayout.HORIZONTAL && vg.childCount >= 2) {
                    val first = vg.getChildAt(0) ?: continue
                    if (isThinSideRail(first, maxSidePx) || first.visibility == View.GONE) {
                        reclaimRailSpace(env, first)
                        return
                    }
                }
                if (vg is ConstraintLayout && vg.childCount >= 2) {
                    for (i in 0 until vg.childCount) {
                        val c = vg.getChildAt(i) ?: continue
                        if (!isThinSideRail(c, maxSidePx)) continue
                        if (c.left > 16 && c.x > 16f) continue
                        reclaimRailSpace(env, c)
                        return
                    }
                }
                for (i in 0 until vg.childCount) {
                    val child = vg.getChildAt(i) as? ViewGroup ?: continue
                    queue.add(child)
                }
            }
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: reclaimLeftGutter failed", e)
        }
    }
}
