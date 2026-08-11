package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.getIdByName
import com.github.kyuubiran.ezxhelper.utils.getObjectOrNull
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.loadClass
import com.github.kyuubiran.ezxhelper.utils.staticMethod
import de.robv.android.xposed.callbacks.XC_LoadPackage
import de.robv.android.xposed.callbacks.XCallback
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.service.AaActivityService
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.hook.abortMethod
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.template.bases.runMain
import io.github.qauxv.ui.CommonContextWrapper
import kotlinx.coroutines.delay
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Constructor
import java.lang.reflect.Method

object AaUiHook: AaHook() {
    override val tagName: String = "AAD_AaUiHook"

    private lateinit var layoutInfoConstructor: Constructor<*>
    private var startMethod: Method? = null

    private var resLayoutLeftResourceId: Int = 0
    private var resLayoutRightResourceId: Int = 0

    private var resLayoutGhFacetBarId: Int = 0
    private var resIdStatusBarId: Int = 0
    private var resIdLauncherAndDashboardIconContainerId: Int = 0
    private var resIdLauncherAndDashboardIconId: Int = 0
    /** Layout resource IDs that host the AA facet / rail chrome we inject into. */
    private val facetBarLayoutIds = mutableSetOf<Int>()
    /** Full-screen rail hosts that embed facet chrome (not a separate coolwalk bar inflate). */
    private val railHostLayoutIds = mutableSetOf<Int>()
    private var canHookLayout: Boolean = false
    private var canHookFacetBar: Boolean = false
    private var mInjectingFacetBar: Boolean = false
    private var mCloseLauncherDashboard: Boolean = false
    private var mAutoOpen: Boolean = false
    private var mEnableOneUiSplit: Boolean = false
    private val facetBarInjectedTag = Any()
    private val mFacetEnsureHandler = Handler(Looper.getMainLooper())
    /**
     * Soft reconnect (no USB replug) often rebuilds LayoutInfo before GhFacetBar chrome
     * is attached; keep probing past the first 1.5s window.
     */
    private val FACET_ENSURE_DELAYS_MS = longArrayOf(0L, 250L, 700L, 1500L, 3000L, 5000L, 8000L)
    private val FACET_ENSURE_POLL_MS = 400L
    private val FACET_ENSURE_TOKEN = Any()
    private var mFacetEnsureDeadlineMs = 0L

    override fun isSupportProcess(processName: String): Boolean {
        return processProjection == processName
    }

    override fun loadDexClass(bridge: DexKitBridge, lpparam: XC_LoadPackage.LoadPackageParam) {
        val classes = bridge.findClass {
            searchPackages = listOf("")
            matcher {
                usingStrings {
                    add(
                        "LayoutInfo{layoutResourceId=",
                        StringMatchType.StartsWith,
                        false
                    )
                }
            }
        }
        if (classes.isEmpty() || classes.size > 1) {
            throw NoSuchMethodException("AaUiHook: not found LayoutInfo class：${classes.size}")
        }
        layoutInfoConstructor = resolveLayoutInfoConstructor(classes[0].name)

        try{
            startMethod = loadClass("com.google.android.projection.gearhead.service.CarSystemUiControllerService").staticMethod("a", null, argTypes(Intent::class.java))
        } catch (e: Throwable){
            log(tagName,  "AaUiHook: not found CarSystemUiControllerService.a static method", e)
        }

        val pkg = InitFields.appContext.packageName
        val res = InitFields.appContext.resources
        fun layoutId(name: String): Int = res.getIdentifier(name, "layout", pkg).also { id ->
            if (id != 0) facetBarLayoutIds.add(id)
        }

        // AA 16.x used vertical coolwalk facet; 17.x with canonical rail may still inflate
        // the non-vertical / RHD variants — match all known facet hosts.
        resLayoutGhFacetBarId = layoutId("gh_coolwalk_vertical_facet_bar")
        layoutId("gh_coolwalk_facet_bar")
        layoutId("gh_coolwalk_facet_bar_rhd")
        layoutId("gh_coolwalk_vertical_facet_bar_rhd")

        resIdStatusBarId = getIdByName("status_bar")
        resIdLauncherAndDashboardIconContainerId = getIdByName("launcher_and_dashboard_icon_container")
        resIdLauncherAndDashboardIconId = getIdByName("launcher_and_dashboard_icon")

        resLayoutLeftResourceId = res.getIdentifier("sys_ui_layout_canonical_vertical_rail_lhd", "layout", pkg)
        resLayoutRightResourceId = res.getIdentifier("sys_ui_layout_canonical_vertical_rail_rhd", "layout", pkg)
        if (resLayoutLeftResourceId != 0) railHostLayoutIds.add(resLayoutLeftResourceId)
        if (resLayoutRightResourceId != 0) railHostLayoutIds.add(resLayoutRightResourceId)

        canHookLayout = resLayoutLeftResourceId != 0 && resLayoutRightResourceId != 0
        if (!canHookLayout) {
            log(
                tagName,
                "AaUiHook: skip layout override, missing canonical layout resources: lhd=$resLayoutLeftResourceId, rhd=$resLayoutRightResourceId"
            )
        }
        canHookFacetBar =
            facetBarLayoutIds.isNotEmpty() &&
            resIdStatusBarId != 0 &&
            resIdLauncherAndDashboardIconContainerId != 0 &&
            resIdLauncherAndDashboardIconId != 0
        if (!canHookFacetBar) {
            log(
                tagName,
                "AaUiHook: skip facet-bar override, missing resources: facetIds=$facetBarLayoutIds, status=$resIdStatusBarId, launcherContainer=$resIdLauncherAndDashboardIconContainerId, launcherIcon=$resIdLauncherAndDashboardIconId"
            )
        } else {
            log(tagName, "AaUiHook: facet layout ids=$facetBarLayoutIds railHosts=$railHostLayoutIds")
        }
    }

    override fun hook(config: SharedPreferences?, lpparam: XC_LoadPackage.LoadPackageParam) {
        log(tagName,  "AaUiHook: ~~~~~~~~~~~~~~~~~~~~~~~~~~~")
        mCloseLauncherDashboard = AADisplayConfig.CloseLauncherDashboard.get(config)
        mAutoOpen = AADisplayConfig.AutoOpen.get(config)
        mEnableOneUiSplit = AADisplayConfig.EnableOneUiSplit.get(config)
        hookBaseClick()
        if (canHookLayout) {
            hookLayout()
        }
        if (canHookFacetBar) {
            hookFacetBar()
            hookFacetWindowAttach()
        }
        hookRadius(config)
    }

    private fun hookLayout() {
        layoutInfoConstructor.hookAfter { param ->
            log(tagName, param.thisObject.toString())
            // AA reconnect often rebuilds LayoutInfo without re-inflating coolwalk facet bar;
            // ensure our side buttons are re-attached into the rail chrome.
            if (canHookFacetBar) {
                scheduleEnsureFacetBar("layoutInfo")
            }
        }
        layoutInfoConstructor.hookBefore { param ->
            if (param.args.size < 5) return@hookBefore
            val layoutTypeCode = layoutTypeCode(param.args[3] ?: return@hookBefore) ?: return@hookBefore
            // Skip cluster/auxiliary layouts to avoid overriding non-main surfaces.
            if (layoutTypeCode in setOf(7, 8, 9)) return@hookBefore
            val isRightHandDrive = (param.args[4] as? Boolean) == true
            param.args[0] = if (isRightHandDrive) resLayoutRightResourceId else resLayoutLeftResourceId
            // Force canonical vertical rail for LHD/RHD.
            setLayoutTypeArg(param.args, if (isRightHandDrive) 3 else 2)
            if (param.args.size > 5 && param.args[5] is Boolean) {
                param.args[5] = true //hasVerticalRail
            }
        }
    }

    private fun resolveLayoutInfoConstructor(className: String): Constructor<*> {
        // Keep only stable shape checks so this works across AA 16.4 and 16.6+.
        val clazz = loadClass(className)
        val fallback = clazz.declaredConstructors.firstOrNull { ctor ->
            val p = ctor.parameterTypes
            p.size >= 10
                && p[0] == Int::class.javaPrimitiveType
                && p[1] == Int::class.javaPrimitiveType
                && p[2] == Int::class.javaPrimitiveType
                && p[4] == Boolean::class.javaPrimitiveType
                && p[5] == Boolean::class.javaPrimitiveType
                && p[7] == Boolean::class.javaPrimitiveType
                && p[8] == Boolean::class.javaPrimitiveType
                && p[9] == Boolean::class.javaPrimitiveType
                && !p[6].isPrimitive
                && (p[3] == Int::class.javaPrimitiveType || p[3].isEnum)
        } ?: throw NoSuchMethodException("AaUiHook: not found compatible LayoutInfo constructor for $className")

        fallback.isAccessible = true
        log(tagName, "AaUiHook: fallback constructor selected, paramCount=${fallback.parameterCount}, layoutTypeArg=${fallback.parameterTypes[3].name}")
        return fallback
    }

    private fun layoutTypeCode(raw: Any): Int? {
        return when (raw) {
            is Int -> raw - 1
            is Enum<*> -> raw.toString().toIntOrNull()
            else -> raw.toString().toIntOrNull()
        }
    }

    private fun setLayoutTypeArg(args: Array<Any?>, targetCode: Int) {
        val raw = args.getOrNull(3) ?: return
        when (raw) {
            is Int -> args[3] = targetCode + 1
            is Enum<*> -> {
                val enumClass = raw.javaClass
                val enumValue = enumClass.enumConstants?.firstOrNull { c ->
                    c?.toString()?.toIntOrNull() == targetCode
                }
                if (enumValue != null) {
                    args[3] = enumValue
                }
            }
        }
    }

    private fun hookFacetBar() {
        findMethod(LayoutInflater::class.java) {
            name == "inflate"
            && parameterCount == 3
            && parameterTypes[0] == Int::class.javaPrimitiveType // resource
            && parameterTypes[1] == ViewGroup::class.java // root
            && parameterTypes[2] == Boolean::class.javaPrimitiveType // attachToRoot
        }.hookAfter { param ->
            if (mInjectingFacetBar) return@hookAfter
            val layoutResId = param.args[0] as Int
            val resultViewGroup = param.result as? ViewGroup ?: return@hookAfter
            if (resultViewGroup.tag === facetBarInjectedTag) {
                return@hookAfter
            }
            val matchedById = facetBarLayoutIds.contains(layoutResId)
            val matchedByContent = !matchedById && isCoolwalkFacetBarContent(resultViewGroup)
            if (matchedById || matchedByContent) {
                try {
                    mInjectingFacetBar = true
                    injectAaFacetBarReplaceResult(
                        param = param,
                        resultViewGroup = resultViewGroup,
                        matchReason = if (matchedById) "layoutId=$layoutResId" else "content"
                    )
                } catch (e: Throwable) {
                    log(tagName, "AaUiHook: inject facet bar failed [$layoutResId]", e)
                } finally {
                    mInjectingFacetBar = false
                }
                return@hookAfter
            }
            // Canonical vertical rail embeds facet chrome; reconnect often reinflates this
            // without a separate coolwalk facet-bar inflate.
            if (railHostLayoutIds.contains(layoutResId)) {
                if (!tryInjectIntoFacetColumn(resultViewGroup, "rail:$layoutResId")) {
                    scheduleEnsureFacetBar("rail:$layoutResId")
                }
            }
        }
    }

    /**
     * Soft reconnect can attach GhFacetBar after LayoutInfo ensure delays have already
     * scanned empty roots. Re-arm when a window with facet chrome appears.
     */
    private fun hookFacetWindowAttach() {
        try {
            findMethod(Class.forName("android.view.WindowManagerGlobal")) {
                name == "addView" && parameterCount >= 1
            }.hookAfter { param ->
                if (!canHookFacetBar || mInjectingFacetBar) return@hookAfter
                val root = param.args[0] as? ViewGroup ?: return@hookAfter
                root.post {
                    if (!canHookFacetBar || mInjectingFacetBar) return@post
                    if (!containsFacetChrome(root) || hasInjectedFacet(root)) return@post
                    scheduleEnsureFacetBar("windowAttach")
                }
            }
            log(tagName, "AaUiHook: hooked WindowManagerGlobal.addView for facet ensure")
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: hook WindowManagerGlobal.addView failed", e)
        }
    }

    private fun scheduleEnsureFacetBar(reason: String) {
        mFacetEnsureHandler.removeCallbacksAndMessages(FACET_ENSURE_TOKEN)
        val now = android.os.SystemClock.uptimeMillis()
        // Keep a short tail after the last fixed kick so a late GhFacetBar can still attach.
        mFacetEnsureDeadlineMs = now + FACET_ENSURE_DELAYS_MS.last() + 2_000L
        // Immediate + delayed kicks; unfinished work continues via a single poll chain.
        for (delayMs in FACET_ENSURE_DELAYS_MS) {
            mFacetEnsureHandler.postAtTime(
                { ensureFacetBarInjected(reason, delayMs) },
                FACET_ENSURE_TOKEN,
                now + delayMs
            )
        }
    }

    private fun ensureFacetBarInjected(reason: String, delayMs: Long = -1L) {
        if (!canHookFacetBar || mInjectingFacetBar) return
        val label = if (delayMs >= 0) "$reason-$delayMs" else reason
        try {
            val roots = collectWindowRootViews()
            if (roots.any { hasInjectedFacet(it) }) {
                mFacetEnsureHandler.removeCallbacksAndMessages(FACET_ENSURE_TOKEN)
                return
            }
            var attempted = 0
            for (root in roots) {
                if (!containsFacetChrome(root)) continue
                if (hasInjectedFacet(root)) continue
                if (tryInjectIntoFacetColumn(root, label)) attempted++
            }
            if (attempted > 0) {
                log(tagName, "AaUiHook: ensure facet injected [$label] count=$attempted")
                mFacetEnsureHandler.removeCallbacksAndMessages(FACET_ENSURE_TOKEN)
                return
            }
            val now = android.os.SystemClock.uptimeMillis()
            if (now < mFacetEnsureDeadlineMs) {
                // Only the poll leg schedules the next tick to avoid N parallel chains.
                if (delayMs < 0 || delayMs == FACET_ENSURE_DELAYS_MS.last()) {
                    mFacetEnsureHandler.postAtTime(
                        { ensureFacetBarInjected("$reason-poll") },
                        FACET_ENSURE_TOKEN,
                        now + FACET_ENSURE_POLL_MS
                    )
                }
            } else if (delayMs == FACET_ENSURE_DELAYS_MS.last() || reason.endsWith("-poll")) {
                log(
                    tagName,
                    "AaUiHook: ensure facet still missing [$label] roots=${roots.size} " +
                        "chrome=${roots.count { containsFacetChrome(it) }}"
                )
            }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: ensure facet failed [$label]", e)
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

    private fun containsFacetChrome(root: ViewGroup): Boolean {
        if (root.findViewById<View>(resIdStatusBarId) == null) return false
        if (root.findViewById<View>(resIdLauncherAndDashboardIconContainerId) == null) return false
        if (root.findViewById<View>(resIdLauncherAndDashboardIconId) == null) return false
        return true
    }

    private fun hasInjectedFacet(root: ViewGroup): Boolean {
        if (root.tag === facetBarInjectedTag) return true
        val status = root.findViewById<View>(resIdStatusBarId) ?: return false
        var node: View? = status
        while (node != null) {
            if (node.tag === facetBarInjectedTag) return true
            node = node.parent as? View
        }
        return false
    }

    private fun findFacetColumn(root: ViewGroup): ViewGroup? {
        val status = root.findViewById<View>(resIdStatusBarId) ?: return null
        val launcherContainer = root.findViewById<View>(resIdLauncherAndDashboardIconContainerId) ?: return null
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

    private fun tryInjectIntoFacetColumn(root: ViewGroup, reason: String): Boolean {
        if (mInjectingFacetBar) return false
        if (hasInjectedFacet(root)) return false
        val column = findFacetColumn(root) ?: return false
        if (column.tag === facetBarInjectedTag) return false
        return try {
            mInjectingFacetBar = true
            injectAaFacetBarInPlace(column, reason)
            true
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: in-place facet inject failed [$reason]", e)
            false
        } finally {
            mInjectingFacetBar = false
        }
    }

    /** Coolwalk facet bars are narrow; full rail hosts are handled via in-place inject. */
    private fun isCoolwalkFacetBarContent(root: ViewGroup): Boolean {
        if (!containsFacetChrome(root)) return false
        val w = root.layoutParams?.width ?: root.measuredWidth
        val h = root.layoutParams?.height ?: root.measuredHeight
        if (w > 0 && h > 0) {
            val min = minOf(w, h)
            val max = maxOf(w, h)
            // Reject square-ish / full content panes; keep thin bars.
            if (min > 0 && max / min < 3) return false
        }
        return true
    }

    private fun injectAaFacetBarReplaceResult(
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam,
        resultViewGroup: ViewGroup,
        matchReason: String
    ) {
        val parent = (resultViewGroup.parent as ViewGroup?)?.apply {
            removeView(resultViewGroup)
        }
        val aaFacetBar = buildAaFacetBar(resultViewGroup, parent, matchReason)
        param.result = aaFacetBar
    }

    private fun injectAaFacetBarInPlace(facetHost: ViewGroup, reason: String) {
        val parent = facetHost.parent as? ViewGroup
            ?: throw IllegalStateException("facet host has no parent")
        val index = parent.indexOfChild(facetHost)
        val lp = facetHost.layoutParams
        parent.removeView(facetHost)
        val aaFacetBar = buildAaFacetBar(facetHost, parent, "inplace:$reason")
        if (index >= 0) {
            parent.addView(aaFacetBar, index, lp)
        } else {
            parent.addView(aaFacetBar, lp)
        }
    }

    private fun buildAaFacetBar(
        resultViewGroup: ViewGroup,
        resultViewGroupParent: ViewGroup?,
        matchReason: String
    ): ConstraintLayout {
        val ctx = resultViewGroup.context
        val ctx2 = CommonContextWrapper.createAppCompatContext(ctx)
        val layoutInflater = LayoutInflater.from(ctx2)
        if (mCloseLauncherDashboard) {
            val launcherIcon = resultViewGroup.findViewById<View>(resIdLauncherAndDashboardIconId)
            if (launcherIcon != null) {
                launcherIcon.setOnClickFinallyListener(OnClickFinallyListener {
                    launcherIcon.performLongClick()
                })
            }
        }
        val aaFacetBar = layoutInflater.inflate(R.layout.aa_facet_bar, resultViewGroupParent, false) as ConstraintLayout
        aaFacetBar.tag = facetBarInjectedTag
        resultViewGroup.tag = facetBarInjectedTag
        log(tagName, "AaUiHook: inject facet bar ($matchReason)")
        if (mAutoOpen) {
            aaFacetBar.post {
                runMain {
                    delay(1000)
                    try {
                        startMethod?.invoke(null, Intent().apply {
                            component = ComponentName(BuildConfig.APPLICATION_ID, AaActivityService::class.java.name)
                            putExtra("android.intent.extra.PACKAGE_NAME", BuildConfig.APPLICATION_ID)
                        })
                    } catch (e: Throwable) {
                        log(tagName, "CarSystemUiControllerService.a start app error", e)
                    }
                }
            }
        }
        val createBtn: (resId: Int, block: View.() -> Unit) -> Int = { resId, block ->
            val btn = ImageView(ctx).apply {
                id = View.generateViewId()
                layoutParams = ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setImageResource(resId)
            }
            block(btn)
            aaFacetBar.addView(btn)
            btn.id
        }
        val topIds = arrayListOf(
            resIdStatusBarId,
            resIdLauncherAndDashboardIconContainerId
        )
        val bottomIds = arrayListOf(
            createBtn(R.drawable.ic_aa_home_44) {
                val intentClick = Intent().apply {
                    action = AABroadcastConst.ACTION_SCREEN_CONTROL
                    putExtra(AABroadcastConst.EXTRA_ACTION, KeyEvent.KEYCODE_HOME)
                }
                setOnClickListener {
                    ctx.sendBroadcast(intentClick)
                }
                setPadding(0, 5, 0, 5)
            },
            createBtn(R.drawable.ic_aa_fullscreen_44) {
                val intentClick = Intent().apply {
                    action = AABroadcastConst.ACTION_SCREEN_CONTROL
                    putExtra(AABroadcastConst.EXTRA_ACTION, KeyEvent.KEYCODE_APP_SWITCH)
                }
                setOnClickListener {
                    ctx.sendBroadcast(intentClick)
                }
                setPadding(0, 5, 0, 5)
            },
            createBtn(R.drawable.ic_aa_arrow_back_44) {
                val intentClick = Intent().apply {
                    action = AABroadcastConst.ACTION_SCREEN_CONTROL
                    putExtra(AABroadcastConst.EXTRA_ACTION, KeyEvent.KEYCODE_BACK)
                }
                setOnClickListener {
                    ctx.sendBroadcast(intentClick)
                }
                setPadding(0, 5, 0, 5)
            },
        )
        if (mEnableOneUiSplit) {
            bottomIds += createBtn(R.drawable.ic_aa_clean_44) {
                contentDescription = ctx2.getString(R.string.cleanup_split_shells)
                val intentClick = Intent().apply {
                    action = AABroadcastConst.ACTION_CLEANUP_SPLIT_SHELLS
                }
                setOnClickListener {
                    ctx.sendBroadcast(intentClick)
                }
                setPadding(0, 5, 0, 5)
            }
            // Stacked above clean (bottomIds grows upward from Home).
            bottomIds += createBtn(R.drawable.ic_aa_split_44) {
                contentDescription = ctx2.getString(R.string.quick_restore_split)
                val intentClick = Intent().apply {
                    action = AABroadcastConst.ACTION_RESTORE_LAST_SPLIT
                }
                setOnClickListener {
                    ctx.sendBroadcast(intentClick)
                }
                setPadding(0, 5, 0, 5)
            }
        }
        arrayListOf(resIdStatusBarId, resIdLauncherAndDashboardIconContainerId).forEach { vId ->
            val view = resultViewGroup.findViewById<View>(vId) ?: return@forEach
            (view.parent as ViewGroup?)?.removeView(view)
            aaFacetBar.addView(view)
        }
        val set = ConstraintSet()
        set.clone(aaFacetBar)
        bottomIds.forEachIndexed { index, vId ->
            set.connect(vId, ConstraintSet.BOTTOM, if (index == 0) ConstraintSet.PARENT_ID else bottomIds[index - 1], if (index == 0) ConstraintSet.BOTTOM else ConstraintSet.TOP, 0)
            set.connect(vId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
            set.connect(vId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
        }
        topIds.forEachIndexed { index, vId ->
            set.connect(vId, ConstraintSet.TOP, if (index == 0) ConstraintSet.PARENT_ID else topIds[index - 1], if (index == 0) ConstraintSet.TOP else ConstraintSet.BOTTOM, 0)
            set.connect(vId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
            set.connect(vId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
        }
        set.applyTo(aaFacetBar)
        resultViewGroup.visibility = View.GONE
        aaFacetBar.addView(resultViewGroup)
        return aaFacetBar
    }

    private fun hookBaseClick() {
        try {
            findMethod(View::class.java) {
                name == "setOnLongClickListener"
                && parameterCount == 1
                && parameterTypes[0] == View.OnLongClickListener::class.java
            }.hookBefore(XCallback.PRIORITY_LOWEST) {
                if (it.args[0] is FinallyListener) return@hookBefore
                val view = it.thisObject as View
                if (!view.hasOnLongClickListeners() || (view.getObjectOrNull("mListenerInfo")?.getObjectOrNull("mOnLongClickListener") is FinallyListener).not()) {
                    return@hookBefore
                }
                view.setOnOriLongClickListener(it.args[0] as View.OnLongClickListener)
                it.abortMethod()
            }
        } catch (e: Throwable) {
            log(tagName, "hook View.setOnLongClickListener", e)
        }
        try {
            findMethod(View::class.java) {
                name == "setOnClickListener"
                && parameterCount == 1
                && parameterTypes[0] == View.OnClickListener::class.java
            }.hookBefore(XCallback.PRIORITY_LOWEST) {
                if (it.args[0] is FinallyListener) return@hookBefore
                val view = it.thisObject as View
                if (!view.hasOnClickListeners() || (view.getObjectOrNull("mListenerInfo")?.getObjectOrNull("mOnClickListener") is FinallyListener).not()) {
                    return@hookBefore
                }
                view.setOnOriClickListener(it.args[0] as View.OnClickListener)
                it.abortMethod()
            }
        } catch (e: Throwable) {
            log(tagName, "hook View.setOnClickListener", e)
        }
    }

    private fun hookRadius(config: SharedPreferences?) {
        if(!AADisplayConfig.ForceRightAngle.get(config)){
            return
        }
        try{
            val targetClass = loadClass("com.google.android.gms.car.ProjectionWindowDecorationParams")
            val ctor = targetClass.declaredConstructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size >= 9 &&
                    p[0] == Int::class.javaPrimitiveType &&
                    p[1] == Int::class.javaPrimitiveType &&
                    p[2] == Int::class.javaPrimitiveType &&
                    p[3] == Int::class.javaPrimitiveType &&
                    p[4] == Int::class.javaPrimitiveType &&
                    p[5] == Int::class.javaPrimitiveType &&
                    p[6] == Int::class.javaPrimitiveType &&
                    p[7] == Boolean::class.javaPrimitiveType &&
                    p[8] == Boolean::class.javaPrimitiveType
            } ?: throw NoSuchMethodException("AaUiHook: ProjectionWindowDecorationParams compatible constructor not found")
            ctor.isAccessible = true
            ctor.hookBefore { param ->
                if (param.args.size > 5 && param.args[5] is Int) {
                    param.args[5] = 0
                }
            }
        } catch (e: Throwable) {
            log(tagName, "ProjectionWindowDecorationParams", e)
        }
    }

    interface FinallyListener
    private fun interface OnClickFinallyListener: View.OnClickListener, FinallyListener
    private fun interface OnLongClickFinallyListener: View.OnLongClickListener, FinallyListener
    private fun View.setOnClickFinallyListener(l: OnClickFinallyListener) = this.setOnClickListener(l)
    private fun View.setOnLongClickFinallyListener(l: OnLongClickFinallyListener) = this.setOnLongClickListener(l)
    private fun View.setOnOriClickListener(l: View.OnClickListener) = this.setTag(R.id.ori_click_listener, l)
    private fun View.setOnOriLongClickListener(l: View.OnLongClickListener) = this.setTag(R.id.ori_long_click_listener, l)
    private fun View.performOriClick() {
        val clickListener = this.getTag(R.id.ori_click_listener) as View.OnClickListener? ?: return
        clickListener.onClick(this)
    }
    private fun View.performOriLongClick(): Boolean {
        val clickListener = this.getTag(R.id.ori_long_click_listener) as View.OnLongClickListener? ?: return false
        return clickListener.onLongClick(this)
    }
}
