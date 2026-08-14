package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.view.InputDevice
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.getIdByName
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.loadClass
import com.github.kyuubiran.ezxhelper.utils.staticMethod
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.service.AaActivityService
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.xposed.CoreManager
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import io.github.qauxv.ui.CommonContextWrapper
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.math.roundToInt

object AaUiHook: AaHook() {
    override val tagName: String = "AAD_AaUiHook"

    /**
     * Gearhead dimens that reserve the vertical rail / edge column.
     * Zeroing these (via Resources hooks) makes AA allocate content at full HU width
     * for any car resolution — do not hardcode 80/720/800.
     */
    private val RAIL_WIDTH_DIMEN_NAMES = arrayOf(
        "cielo_vertical_rail_width",
        "coolwalk_vertical_rail_width",
        "coolwalk_edge_column_width_padded",
        "rail_coolwalk_edge_column_width_padded",
        "floating_vertical_rail_space_width",
        "gearhead_edge_column_width",
        "gearhead_edge_column_width_padded",
        "rail_max_width",
        "legacy_facet_bar_touch_target_width",
    )

    /** All LayoutInfo ctors with the stable (layoutId,w,h,type,rhd,hasVerticalRail,…) shape. */
    private var layoutInfoConstructors: List<Constructor<*>> = emptyList()
    private var startMethod: Method? = null
    /** LayoutInfo.hasVerticalRail (2nd instance boolean); resolved once after first construct. */
    @Volatile private var hasVerticalRailField: java.lang.reflect.Field? = null
    /** LayoutInfo.layoutResourceId (first instance int); resolved once after first construct. */
    @Volatile private var layoutResourceIdField: java.lang.reflect.Field? = null
    /** Methods that touch projection `content_bounds` (DexKit via string). */
    private var contentBoundsMethods: List<Method> = emptyList()
    /** CarActivityManagerService HU touch router (imt.H/G/I in :car). */
    private var huTouchDispatchMethod: Method? = null
    /** First pointer downTime for a stolen left-rail gesture. */
    @Volatile private var mRailHostDownTime = 0L
    /** HU-space gesture started in the left rail strip (follow through MOVE/UP). */
    @Volatile private var mHuRailGesture = false


    private var resLayoutLeftResourceId: Int = 0
    private var resLayoutRightResourceId: Int = 0

    private var resIdStatusBarId: Int = 0
    private var resIdLauncherAndDashboardIconContainerId: Int = 0
    private var resIdLauncherAndDashboardIconId: Int = 0
    /** Layout resource IDs that host the AA facet / rail chrome we inject into. */
    private val facetBarLayoutIds = mutableSetOf<Int>()
    /** Full-screen rail hosts that embed facet chrome (not a separate coolwalk bar inflate). */
    private val railHostLayoutIds = mutableSetOf<Int>()
    /** Dimens that size the reserved left/right rail strip (resolved per AA version). */
    private val railWidthDimenIds = mutableSetOf<Int>()
    private var canHookLayout: Boolean = false
    private var canHookFacetBar: Boolean = false
    private var mInjectingFacetBar: Boolean = false
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
    /**
     * CarSystemUiControllerService.a(Intent) swallows IllegalStateException when the
     * controller is not ready yet ("Unable to start activity"). A single early attempt
     * then permanently blocks Auto Open — retry across the connect window instead.
     * Keep attempts few and spaced so a successful start can cancel before the next retry
     * (repeated start recreates/refocuses CarActivity and feels janky).
     */
    private val AUTO_OPEN_DELAYS_MS = longArrayOf(1200L, 4000L, 8000L)
    private val AUTO_OPEN_TOKEN = Any()
    /** Uptime of the last armed Auto Open session; used to debounce LayoutInfo storms. */
    private var mAutoOpenSessionAtMs = 0L
    private val AUTO_OPEN_REARM_GAP_MS = 12_000L
    @Volatile private var mAaDisplayShownThisSession = false
    private var mAutoOpenShownReceiver: android.content.BroadcastReceiver? = null

    /** Latest main-display LayoutInfo size in dp (from constructor args). */
    @Volatile private var mLayoutWidthDp: Int = 0
    @Volatile private var mLayoutHeightDp: Int = 0
    /** Last observed GhFacetBar / thin-rail VD width in px (for content expand fallback). */
    @Volatile private var mObservedRailWidthPx: Int = 0

    override fun isSupportProcess(processName: String): Boolean {
        // Facet/VD UI lives in :projection; Coolwalk writes content_bounds in :car
        // (GhLifecycleService.onProjectionStart) — both need hooks.
        return processProjection == processName || processCar == processName
    }

    override fun loadDexClass(bridge: DexKitBridge, lpparam: XC_LoadPackage.LoadPackageParam) {
        contentBoundsMethods = try {
            val byKey = bridge.findMethod {
                matcher { usingStrings("content_bounds") }
            }
            val byLog = bridge.findMethod {
                matcher {
                    usingStrings {
                        add("onProjectionStart updated config", StringMatchType.StartsWith, false)
                    }
                }
            }
            (byKey + byLog).mapNotNull { md ->
                runCatching { md.getMethodInstance(lpparam.classLoader) }.getOrNull()
            }.distinctBy { "${it.declaringClass.name}#${it.name}#${it.parameterTypes.joinToString { p -> p.name }}" }
                .also { list ->
                    log(
                        tagName,
                        "AaUiHook: content_bounds methods=${list.size} " +
                            list.map { "${it.declaringClass.name}#${it.name}" }
                    )
                }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: DexKit content_bounds methods failed", e)
            emptyList()
        }

        huTouchDispatchMethod = try {
            bridge.findMethod {
                matcher {
                    usingStrings("UpDown touch event (%s,%s) does not correspond to a window for %s")
                }
            }.mapNotNull { md ->
                runCatching { md.getMethodInstance(lpparam.classLoader) }.getOrNull()
            }.firstOrNull { m ->
                m.parameterTypes.size == 2 && m.returnType == Void.TYPE
            }.also { m ->
                log(
                    tagName,
                    "AaUiHook: HU touch dispatch method=" +
                        (m?.let { "${it.declaringClass.name}#${it.name}" } ?: "null")
                )
            }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: DexKit HU touch dispatch failed", e)
            null
        }

        // :car only needs the projection-config Bundle rewrite; skip LayoutInfo/facet setup.
        if (lpparam.processName == processCar) {
            return
        }

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
        val layoutInfoClassName = classes[0].name
        layoutInfoConstructors = resolveLayoutInfoConstructors(layoutInfoClassName)
        log(
            tagName,
            "AaUiHook: LayoutInfo ctors=${layoutInfoConstructors.size} " +
                layoutInfoConstructors.joinToString { "p${it.parameterCount}" }
        )

        startMethod = resolveCarStartActivityMethod()

        val pkg = InitFields.appContext.packageName
        val res = InitFields.appContext.resources
        fun layoutId(name: String): Int = res.getIdentifier(name, "layout", pkg).also { id ->
            if (id != 0) facetBarLayoutIds.add(id)
        }

        // AA 16.x used vertical coolwalk facet; 17.x with canonical rail may still inflate
        // the non-vertical / RHD variants — match all known facet hosts.
        layoutId("gh_coolwalk_vertical_facet_bar")
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

        for (name in RAIL_WIDTH_DIMEN_NAMES) {
            val id = res.getIdentifier(name, "dimen", pkg)
            if (id != 0) railWidthDimenIds.add(id)
        }

        canHookLayout = resLayoutLeftResourceId != 0
        if (!canHookLayout) {
            log(
                tagName,
                "AaUiHook: LHD canonical layout missing; still forcing hasVerticalRail=true"
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
        log(tagName, "AaUiHook: rail width dimens=$railWidthDimenIds")
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Coolwalk: GhLifecycleService in :car puts content_bounds=Rect(rail,0,fullW,fullH).
        // Must rewrite here — :projection never sees that putParcelable.
        hookContentBounds()
        if (lpparam.processName == processCar) {
            // HU touch dispatch (imt.H/G/I) only runs in :car.
            hookHuTouchDispatchRedirect()
            return
        }
        log(tagName, "AaUiHook: AutoOpen always-on startMethod=${startMethod?.name}")
        // Zero rail-column dimens first so LayoutInfo / VD allocation sees full HU width.
        hookRailWidthDimens()
        hookVirtualDisplaySizing()
        // Always force vertical rail on LayoutInfo — do not gate on canHookLayout.
        // Missing canonical layout resources still need hasVerticalRail=true or AA
        // falls back to the bottom facet bar (GhFacetBar 800×80 on 800×480 HUs).
        hookLayoutInfo()
        registerAutoOpenShownReceiver()
        if (canHookFacetBar) {
            hookFacetBar()
            hookFacetWindowAttach()
        }
        hookRadius()
    }

    /**
     * Force main-display LayoutInfo onto the LHD vertical-rail family.
     * AA 17.4 on some HUs (e.g. 800×480) defaults hasVerticalRail=false → bottom bar.
     */
    private fun hookLayoutInfo() {
        var hooked = 0
        for (ctor in layoutInfoConstructors) {
            try {
                ctor.hookBefore { param -> forceVerticalRailOnLayoutInfoArgs(param.args) }
                ctor.hookAfter { param ->
                    val instance = param.thisObject ?: return@hookAfter
                    forceVerticalRailOnLayoutInfoInstance(instance)
                    scheduleAutoOpenIfNeeded("layoutInfo")
                    if (canHookFacetBar) {
                        scheduleEnsureFacetBar("layoutInfo")
                    }
                }
                hooked++
            } catch (e: Throwable) {
                log(tagName, "AaUiHook: hook LayoutInfo ctor p${ctor.parameterCount} failed", e)
            }
        }
        log(
            tagName,
            "AaUiHook: LayoutInfo vertical-rail force hooked=$hooked/" +
                "${layoutInfoConstructors.size} canHookLayout=$canHookLayout " +
                "lhd=$resLayoutLeftResourceId"
        )
    }

    private fun isAuxiliaryLayoutType(args: Array<Any?>): Boolean {
        val code = layoutTypeCode(args.getOrNull(3) ?: return false) ?: return false
        // Skip cluster/auxiliary layouts to avoid overriding non-main surfaces.
        return code in setOf(7, 8, 9)
    }

    private fun forceVerticalRailOnLayoutInfoArgs(args: Array<Any?>) {
        if (args.size < 6) return
        if (isAuxiliaryLayoutType(args)) return
        (args[1] as? Int)?.takeIf { it > 0 }?.let { mLayoutWidthDp = it }
        (args[2] as? Int)?.takeIf { it > 0 }?.let { mLayoutHeightDp = it }

        val beforeLayoutId = args[0] as? Int
        val beforeType = args[3]
        val beforeRail = args[5] as? Boolean

        // LHD only: pin the left vertical-rail layout when the resource exists.
        // RHD is out of scope. hasVerticalRail must be forced even without that layout.
        if (resLayoutLeftResourceId != 0) {
            args[0] = resLayoutLeftResourceId
            setLayoutTypeArg(args, 2)
        }
        if (args[5] is Boolean) {
            args[5] = true
        }

        if (beforeRail != true ||
            (resLayoutLeftResourceId != 0 && beforeLayoutId != resLayoutLeftResourceId)
        ) {
            logDebug(
                tagName,
                "AaUiHook: force vertical rail args " +
                    "layoutId=$beforeLayoutId→${args[0]} type=$beforeType→${args[3]} " +
                    "hasVerticalRail=$beforeRail→${args[5]} size=${mLayoutWidthDp}x${mLayoutHeightDp}"
            )
        }
    }

    private fun forceVerticalRailOnLayoutInfoInstance(instance: Any) {
        try {
            resolveLayoutInfoFields(instance.javaClass)
            val railField = hasVerticalRailField
            if (railField != null && !railField.getBoolean(instance)) {
                railField.setBoolean(instance, true)
                logDebug(tagName, "AaUiHook: force vertical rail field ${railField.name}=true")
            }
            val layoutField = layoutResourceIdField
            if (resLayoutLeftResourceId != 0 && layoutField != null) {
                val cur = layoutField.getInt(instance)
                if (cur != resLayoutLeftResourceId) {
                    layoutField.setInt(instance, resLayoutLeftResourceId)
                    logDebug(
                        tagName,
                        "AaUiHook: force vertical rail layoutId field ${layoutField.name} $cur→$resLayoutLeftResourceId"
                    )
                }
            }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: force vertical rail on instance failed", e)
        }
    }

    private fun resolveLayoutInfoFields(clazz: Class<*>) {
        if (hasVerticalRailField != null && layoutResourceIdField != null) return
        val namedRail = clazz.declaredFields.firstOrNull { f ->
            !java.lang.reflect.Modifier.isStatic(f.modifiers) &&
                f.type == Boolean::class.javaPrimitiveType &&
                f.name.contains("vertical", ignoreCase = true)
        }
        val bools = clazz.declaredFields.filter { f ->
            !java.lang.reflect.Modifier.isStatic(f.modifiers) &&
                f.type == Boolean::class.javaPrimitiveType
        }
        // Ctor booleans: [isRhd, hasVerticalRail, isDriverAlignedDashboard, isHero, isIrregular]
        val rail = namedRail ?: bools.getOrNull(1)
        if (rail != null) {
            rail.isAccessible = true
            hasVerticalRailField = rail
        }
        val namedLayout = clazz.declaredFields.firstOrNull { f ->
            !java.lang.reflect.Modifier.isStatic(f.modifiers) &&
                f.type == Int::class.javaPrimitiveType &&
                (f.name.contains("layoutResource", ignoreCase = true) ||
                    f.name.contains("layoutRes", ignoreCase = true))
        }
        val ints = clazz.declaredFields.filter { f ->
            !java.lang.reflect.Modifier.isStatic(f.modifiers) &&
                f.type == Int::class.javaPrimitiveType
        }
        // Ctor ints: [layoutResourceId, displayWidthDp, displayHeightDp, layoutType?]
        val layoutId = namedLayout ?: ints.getOrNull(0)
        if (layoutId != null) {
            layoutId.isAccessible = true
            layoutResourceIdField = layoutId
        }
    }

    /**
     * Force gearhead rail-column dimens to 0px so AA sizes content to the full car display
     * for any HU resolution (72/80/86/96dp variants all become 0).
     */
    private fun hookRailWidthDimens() {
        if (railWidthDimenIds.isEmpty()) {
            log(tagName, "AaUiHook: no rail width dimens resolved; VD expand fallback only")
            return
        }
        fun hookDimenMethod(clazz: Class<*>, methodName: String, zero: Any) {
            try {
                findMethod(clazz) {
                    name == methodName && parameterCount == 1 &&
                        parameterTypes[0] == Int::class.javaPrimitiveType
                }.hookAfter { param ->
                    val id = param.args[0] as? Int ?: return@hookAfter
                    if (railWidthDimenIds.contains(id)) {
                        param.result = zero
                    }
                }
            } catch (e: Throwable) {
                log(tagName, "AaUiHook: hook $clazz.$methodName failed", e)
            }
        }
        hookDimenMethod(Resources::class.java, "getDimensionPixelSize", 0)
        hookDimenMethod(Resources::class.java, "getDimensionPixelOffset", 0)
        hookDimenMethod(Resources::class.java, "getDimension", 0f)
        try {
            val impl = loadClass("android.content.res.ResourcesImpl")
            hookDimenMethod(impl, "getDimensionPixelSize", 0)
            hookDimenMethod(impl, "getDimensionPixelOffset", 0)
            hookDimenMethod(impl, "getDimension", 0f)
        } catch (_: Throwable) {
        }
        log(tagName, "AaUiHook: hooked rail width dimens → 0 (${railWidthDimenIds.size} ids)")
    }

    /**
     * Coolwalk projection config uses Bundle key `content_bounds` as the car compositor
     * content region. With a vertical rail it becomes Rect(rail, 0, fullW, fullH) even after
     * View/VD shrink — that left strip is the leftover menu gutter. Expand to origin for any HU.
     *
     * Written from [GhLifecycleService] in the `:car` process via Bundle.putParcelable — not from
     * `:projection`. Also zero `pillar_width` / left `content_insets` when present.
     */
    private fun hookContentBounds() {
        try {
            // API 36: putParcelable / putInt live on BaseBundle; Bundle may not redeclare them.
            val baseBundle = loadClass("android.os.BaseBundle")
            for (method in baseBundle.declaredMethods) {
                if (method.name == "putParcelable" && method.parameterCount == 2 &&
                    method.parameterTypes[0] == String::class.java
                ) {
                    method.isAccessible = true
                    method.hookBefore { param ->
                        rewriteProjectionConfigParcelable(param.args[0] as? String, param.args[1])?.let {
                            param.args[1] = it
                        }
                    }
                    log(tagName, "AaUiHook: hooked BaseBundle.putParcelable(content_bounds)")
                }
                if (method.name == "putInt" && method.parameterCount == 2 &&
                    method.parameterTypes[0] == String::class.java &&
                    method.parameterTypes[1] == Int::class.javaPrimitiveType
                ) {
                    method.isAccessible = true
                    method.hookBefore { param ->
                        val key = param.args[0] as? String ?: return@hookBefore
                        if (key != "pillar_width") return@hookBefore
                        val value = param.args[1] as? Int ?: return@hookBefore
                        if (value == 0) return@hookBefore
                        val range = railPxRange(layoutWidthPx().takeIf { it > 0 } ?: (value * 10))
                        if (value in range) {
                            mObservedRailWidthPx = value
                            param.args[1] = 0
                        }
                    }
                    log(tagName, "AaUiHook: hooked BaseBundle.putInt(pillar_width)")
                }
            }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: hook BaseBundle put failed", e)
        }
        try {
            findMethod(Bundle::class.java) {
                name == "putParcelable" && parameterCount == 2 &&
                    parameterTypes[0] == String::class.java
            }.hookBefore { param ->
                rewriteProjectionConfigParcelable(param.args[0] as? String, param.args[1])?.let {
                    param.args[1] = it
                }
            }
            log(tagName, "AaUiHook: hooked Bundle.putParcelable(content_bounds)")
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: hook Bundle.putParcelable failed", e)
        }
        try {
            findMethod(Bundle::class.java) {
                name == "putInt" && parameterCount == 2 &&
                    parameterTypes[0] == String::class.java &&
                    parameterTypes[1] == Int::class.javaPrimitiveType
            }.hookBefore { param ->
                val key = param.args[0] as? String ?: return@hookBefore
                if (key != "pillar_width") return@hookBefore
                val value = param.args[1] as? Int ?: return@hookBefore
                if (value == 0) return@hookBefore
                val range = railPxRange(layoutWidthPx().takeIf { it > 0 } ?: (value * 10))
                if (value in range) {
                    mObservedRailWidthPx = value
                    param.args[1] = 0
                }
            }
            log(tagName, "AaUiHook: hooked Bundle.putInt(pillar_width)")
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: hook Bundle.putInt failed", e)
        }
        // API 33+ typed getter; older 1-arg getter.
        for (method in Bundle::class.java.declaredMethods) {
            if (method.name != "getParcelable") continue
            if (method.parameterCount !in 1..2) continue
            if (method.parameterTypes[0] != String::class.java) continue
            try {
                method.isAccessible = true
                method.hookAfter { param ->
                    val key = param.args[0] as? String ?: return@hookAfter
                    val rect = param.result as? Rect ?: return@hookAfter
                    rewriteProjectionConfigParcelable(key, rect)?.let { param.result = it }
                }
            } catch (e: Throwable) {
                log(tagName, "AaUiHook: hook Bundle.getParcelable failed", e)
            }
        }
        try {
            findMethod(Intent::class.java) {
                name == "putExtra" && parameterCount == 2 &&
                    parameterTypes[0] == String::class.java &&
                    parameterTypes[1] == Parcelable::class.java
            }.hookBefore { param ->
                rewriteProjectionConfigParcelable(param.args[0] as? String, param.args[1])?.let {
                    param.args[1] = it
                }
            }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: hook Intent.putExtra(Parcelable) failed", e)
        }
        try {
            val arrayMapClass = loadClass("android.util.ArrayMap")
            findMethod(arrayMapClass) {
                name == "put" && parameterCount == 2
            }.hookBefore { param ->
                rewriteProjectionConfigParcelable(param.args[0] as? String, param.args[1])?.let {
                    param.args[1] = it
                }
            }
            log(tagName, "AaUiHook: hooked ArrayMap.put for content_bounds")
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: hook ArrayMap.put failed", e)
        }
        try {
            // Only rewrite HU-sized rail-inset rects (avoid random clip Rect(rail,*,*,*)).
            Rect::class.java.getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).hookAfter { param ->
                val rect = param.thisObject as? Rect ?: return@hookAfter
                if (!looksLikeHuContentBounds(rect)) return@hookAfter
                applyExpandedContentBounds(rect)
            }
            log(tagName, "AaUiHook: hooked Rect(int,int,int,int) for content_bounds")
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: hook Rect ctor failed", e)
        }
        try {
            Rect::class.java.getDeclaredMethod(
                "set",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).hookAfter { param ->
                val rect = param.thisObject as? Rect ?: return@hookAfter
                if (!looksLikeHuContentBounds(rect)) return@hookAfter
                applyExpandedContentBounds(rect)
            }
            log(tagName, "AaUiHook: hooked Rect.set for content_bounds mutate")
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: hook Rect.set failed", e)
        }
        // Backup: after GhLifecycleService builds the config Bundle, expand any leftover inset.
        for (method in contentBoundsMethods) {
            try {
                method.hookBefore { param ->
                    param.args.forEach { arg ->
                        if (arg is Bundle) fixProjectionConfigBundle(arg)
                    }
                }
                method.hookAfter { param ->
                    param.args.forEach { arg ->
                        if (arg is Bundle) fixProjectionConfigBundle(arg)
                    }
                }
                log(tagName, "AaUiHook: hooked ${method.declaringClass.name}#${method.name} for config Bundle")
            } catch (e: Throwable) {
                log(tagName, "AaUiHook: hook ${method.declaringClass.name}#${method.name} failed", e)
            }
        }
    }

    private fun fixProjectionConfigBundle(bundle: Bundle) {
        try {
            val bounds = bundleParcelableRect(bundle, "content_bounds")
            rewriteProjectionConfigParcelable("content_bounds", bounds)
            val insets = bundleParcelableRect(bundle, "content_insets")
            rewriteProjectionConfigParcelable("content_insets", insets)
            if (bundle.containsKey("pillar_width")) {
                val value = bundle.getInt("pillar_width", 0)
                if (value != 0) {
                    val range = railPxRange(layoutWidthPx().takeIf { it > 0 } ?: (value * 10))
                    if (value in range) {
                        mObservedRailWidthPx = value
                        bundle.putInt("pillar_width", 0)
                    }
                }
            }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: fixProjectionConfigBundle failed", e)
        }
    }

    private fun rewriteProjectionConfigParcelable(key: String?, value: Any?): Rect? {
        if (key.isNullOrEmpty()) return null
        val rect = value as? Rect ?: return null
        return when (key) {
            "content_bounds", "contentBounds" -> applyExpandedContentBounds(rect)
            "content_insets", "contentInsets" -> applyZeroedContentInsets(rect)
            else -> null
        }
    }

    /** True when [rect] looks like a full-HU content region with a rail inset (not a small clip). */
    private fun looksLikeHuContentBounds(rect: Rect): Boolean {
        if (rect.width() <= 0 || rect.height() <= 0 || rect.top > 0) return false
        val fullW = layoutWidthPx()
        val fullH = layoutHeightPx()
        if (fullW > 0 && fullH > 0) {
            return abs(rect.bottom - fullH) <= 2 &&
                (abs(rect.right - fullW) <= 2 || abs(rect.right + rect.left - fullW) <= 2)
        }
        // Without LayoutInfo: require a landscape HU-sized rect (car px, not phone density).
        return rect.right >= 320 && rect.bottom >= 180 && rect.width() >= rect.left * 3
    }

    /** Expand a rail-inset content rect to full HU origin; mutates [rect] in place when possible. */
    private fun applyExpandedContentBounds(rect: Rect): Rect? {
        val fullW = layoutWidthPx().takeIf { it > 0 } ?: rect.right
        val fullH = layoutHeightPx().takeIf { it > 0 } ?: rect.bottom
        val range = railPxRange(fullW)
        // LHD: left gutter reserved for vertical rail.
        if (rect.left in range && rect.right >= fullW - 2 && rect.top <= 0) {
            mObservedRailWidthPx = rect.left
            rect.set(0, 0, fullW.coerceAtLeast(rect.right), fullH.coerceAtLeast(rect.bottom))
            return rect
        }
        // RHD: right edge pulled in by rail width.
        val rightGap = fullW - rect.right
        if (rect.left <= 0 && rightGap in range && rect.top <= 0) {
            mObservedRailWidthPx = rightGap
            rect.set(0, 0, fullW, fullH.coerceAtLeast(rect.bottom))
            return rect
        }
        return null
    }

    /** Zero left/right rail insets published alongside content_bounds. */
    private fun applyZeroedContentInsets(rect: Rect): Rect? {
        val range = railPxRange(layoutWidthPx().takeIf { it > 0 } ?: rect.left.coerceAtLeast(rect.right) * 10)
        var changed = false
        if (rect.left in range) {
            mObservedRailWidthPx = rect.left
            rect.left = 0
            changed = true
        }
        if (rect.right in range) {
            mObservedRailWidthPx = rect.right
            rect.right = 0
            changed = true
        }
        if (!changed) return null
        return rect
    }

    /**
     * Dual-VD fallback for AA 17.x: even with View collapse, GhFacetBar keeps a compositor
     * slot (content = fullWidth − railWidth). Shrink facet VDs and expand content VDs to
     * [LayoutInfo] full size — sizes come from the live layout, not hardcoded pixels.
     */
    private fun hookVirtualDisplaySizing() {
        try {
            var hooked = 0
            for (method in DisplayManager::class.java.declaredMethods) {
                if (method.name != "createVirtualDisplay") continue
                val params = method.parameterTypes
                if (params.size < 5) continue
                if (params[0] != String::class.java) continue
                if (params[1] != Int::class.javaPrimitiveType) continue
                if (params[2] != Int::class.javaPrimitiveType) continue
                method.isAccessible = true
                method.hookBefore { param ->
                    rewriteVirtualDisplayArgs(
                        name = param.args[0] as? String,
                        width = param.args[1] as? Int ?: return@hookBefore,
                        height = param.args[2] as? Int ?: return@hookBefore,
                    )?.let { (newW, newH) ->
                        if (newW != param.args[1]) param.args[1] = newW
                        if (newH != param.args[2]) param.args[2] = newH
                    }
                }
                method.hookAfter { param ->
                    rememberRailVirtualDisplay(
                        name = param.args[0] as? String,
                        vd = param.result as? VirtualDisplay,
                    )
                }
                hooked++
            }
            log(tagName, "AaUiHook: hooked DisplayManager.createVirtualDisplay overloads=$hooked")
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: hook DisplayManager.createVirtualDisplay failed", e)
        }
        // API 31–35 may expose setSize; API 36+ sizes are constructor args only.
        try {
            val builderClass = loadClass("android.hardware.display.VirtualDisplayConfig\$Builder")
            var hookedBuilder = 0
            for (ctor in builderClass.declaredConstructors) {
                val p = ctor.parameterTypes
                if (p.size < 4) continue
                if (p[0] != String::class.java) continue
                if (p[1] != Int::class.javaPrimitiveType) continue
                if (p[2] != Int::class.javaPrimitiveType) continue
                ctor.isAccessible = true
                ctor.hookBefore { param ->
                    rewriteVirtualDisplayArgs(
                        name = param.args[0] as? String,
                        width = param.args[1] as? Int ?: return@hookBefore,
                        height = param.args[2] as? Int ?: return@hookBefore,
                    )?.let { (newW, newH) ->
                        param.args[1] = newW
                        param.args[2] = newH
                    }
                }
                hookedBuilder++
            }
            try {
                findMethod(builderClass) {
                    name == "setSize" && parameterCount == 2 &&
                        parameterTypes[0] == Int::class.javaPrimitiveType &&
                        parameterTypes[1] == Int::class.javaPrimitiveType
                }.hookBefore { param ->
                    val name = readVirtualDisplayBuilderName(param.thisObject)
                    rewriteVirtualDisplayArgs(
                        name = name,
                        width = param.args[0] as? Int ?: return@hookBefore,
                        height = param.args[1] as? Int ?: return@hookBefore,
                    )?.let { (newW, newH) ->
                        param.args[0] = newW
                        param.args[1] = newH
                    }
                }
                hookedBuilder++
            } catch (_: Throwable) {
            }
            log(tagName, "AaUiHook: hooked VirtualDisplayConfig.Builder paths=$hookedBuilder")
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: hook VirtualDisplayConfig.Builder failed", e)
        }
        try {
            findMethod(VirtualDisplay::class.java) {
                name == "resize" && parameterCount == 3 &&
                    parameterTypes[0] == Int::class.javaPrimitiveType &&
                    parameterTypes[1] == Int::class.javaPrimitiveType
            }.hookBefore { param ->
                val vd = param.thisObject as VirtualDisplay
                val name = runCatching { vd.display?.name }.getOrNull()
                rewriteVirtualDisplayArgs(
                    name = name,
                    width = param.args[0] as? Int ?: return@hookBefore,
                    height = param.args[1] as? Int ?: return@hookBefore,
                )?.let { (newW, newH) ->
                    param.args[0] = newW
                    param.args[1] = newH
                }
                rememberRailVirtualDisplay(name, vd)
            }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: hook VirtualDisplay.resize failed", e)
        }
    }

    private fun isRailVirtualDisplayName(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        return name.contains("FacetBar", ignoreCase = true) ||
            name.contains("GhFacet", ignoreCase = true) ||
            name.contains("VerticalRail", ignoreCase = true) ||
            name.contains("EdgeColumn", ignoreCase = true)
    }

    private fun rememberRailVirtualDisplay(name: String?, vd: VirtualDisplay?) {
        if (vd == null || !isRailVirtualDisplayName(name)) return
        val display = vd.display ?: return
        val width = display.mode.physicalWidth
        if (width > 1) {
            mObservedRailWidthPx = width
        }
        logDebug(tagName, "AaUiHook: observe rail VD id=${display.displayId} name=$name w=$width")
    }

    /**
     * Steal left-rail HU touches before Coolwalk hit-tests windows.
     * AA 17.4: imt.H(CarDisplayId, ProjectionTouchEvent), imt.G/I(CarDisplayId, MotionEvent).
     * Must run in :car — that is where CarActivityManagerService lives.
     */
    private fun hookHuTouchDispatchRedirect() {
        val anchor = huTouchDispatchMethod
        if (anchor == null) {
            log(tagName, "AaUiHook: skip HU touch dispatch redirect (method not found)")
            return
        }
        val targets = linkedSetOf<Method>()
        targets += anchor
        runCatching {
            for (m in anchor.declaringClass.declaredMethods) {
                if (m.parameterTypes.size != 2) continue
                if (m.parameterTypes[0] != anchor.parameterTypes[0]) continue
                val p1 = m.parameterTypes[1]
                if (p1 == MotionEvent::class.java || p1 == anchor.parameterTypes[1]) {
                    m.isAccessible = true
                    targets += m
                }
            }
        }
        var hooked = 0
        for (method in targets) {
            try {
                method.hookBefore { param -> stealHuTouchIfRail(param) }
                hooked++
            } catch (e: Throwable) {
                log(
                    tagName,
                    "AaUiHook: hook HU dispatch ${method.declaringClass.name}#${method.name} failed",
                    e
                )
            }
        }
        log(
            tagName,
            "AaUiHook: hooked HU touch dispatch → touchPrimaryPane " +
                "methods=${targets.joinToString { it.name }} hooked=$hooked"
        )
    }

    private fun stealHuTouchIfRail(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
        if (param.args.size < 2) return
        val raw = param.args[1] ?: return
        val owned = raw !is MotionEvent
        val motion = when (raw) {
            is MotionEvent -> raw
            else -> projectionTouchEventToMotionEvent(raw)
        } ?: return
        try {
            val rail = railHitWidthPx()
            val action = motion.actionMasked
            val inRail = (0 until motion.pointerCount).any { i -> motion.getX(i) < rail }
            if (action == MotionEvent.ACTION_DOWN) {
                mHuRailGesture = inRail
            }
            val steal = mHuRailGesture || inRail
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mHuRailGesture = false
            }
            if (!steal) return
            CoreManager.touchPrimaryPane(motion)
            param.result = null
            if (action == MotionEvent.ACTION_DOWN) {
                logDebug(
                    tagName,
                    "AaUiHook: HU rail → touchPrimaryPane x=${motion.x} y=${motion.y} rail=$rail"
                )
            }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: HU rail → touchPrimaryPane failed", e)
        } finally {
            if (owned) motion.recycle()
        }
    }

    /** LHD left-rail hit band in HU px. Observed FacetBar / content_bounds, else ~10% of HU. */
    private fun railHitWidthPx(): Int {
        val observed = mObservedRailWidthPx
        val fullW = layoutWidthPx()
        if (fullW > 0) {
            val range = railPxRange(fullW)
            if (observed in range) return observed
            return (fullW * 0.10f).roundToInt().coerceIn(range)
        }
        if (observed in 16..200) return observed
        return 80
    }

    /**
     * Best-effort decode of obfuscated Coolwalk ProjectionTouchEvent → [MotionEvent].
     * Layout (AA 17.4): action:int, actionIndex:int, time:long, pointers:[{x,y,id}:int].
     */
    private fun projectionTouchEventToMotionEvent(pte: Any?): MotionEvent? {
        if (pte == null) return null
        return try {
            val ints = mutableListOf<Pair<java.lang.reflect.Field, Int>>()
            var pointersField: java.lang.reflect.Field? = null
            for (f in pte.javaClass.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                when (f.type) {
                    Int::class.javaPrimitiveType -> ints += f to f.getInt(pte)
                    else -> {
                        val v = f.get(pte)
                        if (v != null && v.javaClass.isArray) pointersField = f
                    }
                }
            }
            if (ints.size < 1 || pointersField == null) return null
            // Declaration order matches AA 17.4 toString: action, actionIndex.
            val action = ints[0].second
            val pointers = pointersField.get(pte) as? Array<*> ?: return null
            if (pointers.isEmpty()) return null
            val props = Array(pointers.size) { MotionEvent.PointerProperties() }
            val coords = Array(pointers.size) { MotionEvent.PointerCoords() }
            for (i in pointers.indices) {
                val p = pointers[i] ?: return null
                val pInts = p.javaClass.declaredFields
                    .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
                    .onEach { it.isAccessible = true }
                // AA 17.4 VirtualTouchEvent path: e=x, f=y, g=pointerId
                val x = pInts.getOrNull(0)?.getInt(p) ?: 0
                val y = pInts.getOrNull(1)?.getInt(p) ?: 0
                val id = pInts.getOrNull(2)?.getInt(p) ?: i
                props[i].id = id
                props[i].toolType = MotionEvent.TOOL_TYPE_FINGER
                coords[i].x = x.toFloat()
                coords[i].y = y.toFloat()
                coords[i].pressure = 1f
                coords[i].size = 1f
            }
            val now = SystemClock.uptimeMillis()
            // Always use uptime: Coolwalk PTE timestamps are not InputDispatcher uptime
            // and injected events with a foreign clock are dropped.
            when (action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> mRailHostDownTime = now
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { /* keep until next down */ }
            }
            val down = mRailHostDownTime.takeIf { it > 0L } ?: now
            val eventTime = now
            MotionEvent.obtain(
                down,
                eventTime,
                action,
                pointers.size,
                props,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: ProjectionTouchEvent decode failed", e)
            null
        }
    }

    private fun readVirtualDisplayBuilderName(builder: Any): String? {
        return runCatching {
            builder.javaClass.methods.firstOrNull { m ->
                m.name == "getName" && m.parameterCount == 0
            }?.invoke(builder) as? String
        }.getOrNull() ?: runCatching {
            builder.javaClass.declaredFields.firstOrNull { f ->
                f.type == String::class.java
            }?.apply { isAccessible = true }?.get(builder) as? String
        }.getOrNull()
    }

    /**
     * @return rewritten (width, height) or null if unchanged.
     */
    private fun rewriteVirtualDisplayArgs(name: String?, width: Int, height: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val railName = name?.contains("FacetBar", ignoreCase = true) == true ||
            name?.contains("GhFacet", ignoreCase = true) == true ||
            name?.contains("VerticalRail", ignoreCase = true) == true ||
            name?.contains("EdgeColumn", ignoreCase = true) == true
        // Named FacetBar VD: observe width for railHitWidthPx / content expand only.
        if (railName) {
            if (width > 1) {
                mObservedRailWidthPx = width
            }
            return null
        }
        if (isThinRailSize(width, height)) {
            if (width > 1) {
                mObservedRailWidthPx = width
                log(
                    tagName,
                    "AaUiHook: shrink rail VD name=$name ${width}x$height → 1x$height (thin-geometry)"
                )
                return 1 to height
            }
            return null
        }
        val fullW = layoutWidthPx()
        val fullH = layoutHeightPx()
        if (fullW <= 0 || fullH <= 0) return null
        if (abs(height - fullH) > 2) return null
        if (width >= fullW) return null
        val missing = fullW - width
        val range = railPxRange(fullW)
        val observed = mObservedRailWidthPx
        val looksLikeContentMinusRail =
            (observed > 0 && abs(missing - observed) <= 2) ||
                missing in range
        if (!looksLikeContentMinusRail) return null
        log(
            tagName,
            "AaUiHook: expand content VD name=$name ${width}x$height → ${fullW}x$height " +
                "(layout=${mLayoutWidthDp}x${mLayoutHeightDp}dp missing=$missing)"
        )
        return fullW to height
    }

    private fun layoutWidthPx(): Int = mLayoutWidthDp.takeIf { it > 0 } ?: 0

    private fun layoutHeightPx(): Int = mLayoutHeightDp.takeIf { it > 0 } ?: 0

    /** Rail strip width in car pixels (~5–25% of HU width, clamped). */
    private fun railPxRange(fullW: Int): IntRange {
        val min = (fullW * 0.05f).roundToInt().coerceIn(32, 96)
        val max = (fullW * 0.25f).roundToInt().coerceAtLeast(min).coerceAtMost(fullW / 2).coerceAtLeast(120)
        return min..max
    }

    private fun isThinRailSize(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val maxRail = railPxRange(layoutWidthPx().takeIf { it > 0 } ?: (width * 10)).last
        return width <= maxRail && height >= width * 2
    }

    private fun resolveCarStartActivityMethod(): Method? {
        val clazz = try {
            loadClass("com.google.android.projection.gearhead.service.CarSystemUiControllerService")
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: CarSystemUiControllerService missing", e)
            return null
        }
        try {
            return clazz.staticMethod("a", null, argTypes(Intent::class.java)).also {
                log(tagName, "AaUiHook: startMethod=a(Intent)")
            }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: CarSystemUiControllerService.a missing, scanning static Intent methods", e)
        }
        val candidates = clazz.declaredMethods.filter { m ->
            java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == Intent::class.java &&
                (m.returnType == Void.TYPE || m.returnType == Void::class.java)
        }
        val picked = candidates.firstOrNull()
        if (picked != null) {
            picked.isAccessible = true
            log(tagName, "AaUiHook: startMethod fallback=${picked.name}(Intent) candidates=${candidates.map { it.name }}")
        } else {
            log(tagName, "AaUiHook: no static Intent start method on CarSystemUiControllerService")
        }
        return picked
    }

    private fun aaDisplayLaunchIntent(): Intent = Intent().apply {
        component = ComponentName(BuildConfig.APPLICATION_ID, AaActivityService::class.java.name)
        putExtra("android.intent.extra.PACKAGE_NAME", BuildConfig.APPLICATION_ID)
    }

    private fun registerAutoOpenShownReceiver() {
        if (mAutoOpenShownReceiver != null) return
        val ctx = InitFields.appContext
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != AABroadcastConst.ACTION_AA_DISPLAY_SHOWN) return
                markAaDisplayShown("broadcast")
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(
                    receiver,
                    IntentFilter(AABroadcastConst.ACTION_AA_DISPLAY_SHOWN),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, IntentFilter(AABroadcastConst.ACTION_AA_DISPLAY_SHOWN))
            }
            mAutoOpenShownReceiver = receiver
            log(tagName, "AaUiHook: registered AA_DISPLAY_SHOWN receiver for AutoOpen cancel")
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: register AA_DISPLAY_SHOWN receiver failed", e)
        }
    }

    private fun markAaDisplayShown(reason: String) {
        if (mAaDisplayShownThisSession) return
        mAaDisplayShownThisSession = true
        mFacetEnsureHandler.removeCallbacksAndMessages(AUTO_OPEN_TOKEN)
        logDebug(tagName, "AaUiHook: AutoOpen stop retries ($reason)")
    }

    private fun scheduleAutoOpenIfNeeded(reason: String = "unknown") {
        if (startMethod == null) {
            log(tagName, "AaUiHook: AutoOpen skip ($reason): startMethod null")
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (mAutoOpenSessionAtMs != 0L && now - mAutoOpenSessionAtMs < AUTO_OPEN_REARM_GAP_MS) {
            return
        }
        mAutoOpenSessionAtMs = now
        mAaDisplayShownThisSession = false
        mFacetEnsureHandler.removeCallbacksAndMessages(AUTO_OPEN_TOKEN)
        logDebug(tagName, "AaUiHook: arm AutoOpen retries ($reason) delays=${AUTO_OPEN_DELAYS_MS.contentToString()}")
        for (delayMs in AUTO_OPEN_DELAYS_MS) {
            mFacetEnsureHandler.postAtTime(
                { tryAutoOpenAaDisplay(delayMs) },
                AUTO_OPEN_TOKEN,
                now + delayMs
            )
        }
    }

    private fun tryAutoOpenAaDisplay(delayMs: Long) {
        // Only stop on real resume (AA_DISPLAY_SHOWN). CarSystemUiControllerService
        // swallows "Unable to start activity" — invoke can "succeed" without opening,
        // so do not cancel remaining 4s/8s retries on a bare invoke.
        if (mAaDisplayShownThisSession) {
            markAaDisplayShown("flag")
            return
        }
        val method = startMethod ?: return
        try {
            method.invoke(null, aaDisplayLaunchIntent())
            logDebug(tagName, "AaUiHook: AutoOpen invoke at ${delayMs}ms")
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: AutoOpen invoke failed at ${delayMs}ms", e)
        }
    }

    private fun bundleParcelableRect(bundle: Bundle, key: String): Rect? {
        return if (Build.VERSION.SDK_INT >= 33) {
            bundle.getParcelable(key, Rect::class.java)
        } else {
            @Suppress("DEPRECATION")
            bundle.getParcelable(key) as? Rect
        }
    }

    private fun resolveLayoutInfoConstructors(className: String): List<Constructor<*>> {
        // Keep only stable shape checks so this works across AA 16.4 and 16.6+.
        val clazz = loadClass(className)
        val matched = clazz.declaredConstructors.filter { ctor ->
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
        }
        if (matched.isEmpty()) {
            throw NoSuchMethodException("AaUiHook: not found compatible LayoutInfo constructor for $className")
        }
        for (ctor in matched) {
            ctor.isAccessible = true
        }
        val primary = matched.first()
        log(
            tagName,
            "AaUiHook: LayoutInfo constructor selected, paramCount=${primary.parameterCount}, " +
                "layoutTypeArg=${primary.parameterTypes[3].name}, matches=${matched.size}"
        )
        return matched
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
                // Always try to reclaim the left gutter on the rail host, even if inject missed.
                resultViewGroup.post { reclaimLeftGutter(resultViewGroup) }
                resultViewGroup.postDelayed({ reclaimLeftGutter(resultViewGroup) }, 400L)
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
                    if (hasInjectedFacet(root)) {
                        reclaimLeftGutter(root)
                        return@post
                    }
                    if (!containsFacetChrome(root)) return@post
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
                logDebug(tagName, "AaUiHook: ensure facet injected [$label] count=$attempted")
                for (root in roots) {
                    reclaimLeftGutter(root)
                }
                mFacetEnsureHandler.removeCallbacksAndMessages(FACET_ENSURE_TOKEN)
                return
            }
            // Even without a successful inject, try reclaiming any leftover left gutter.
            for (root in roots) {
                if (containsFacetChrome(root) || hasInjectedFacet(root)) {
                    reclaimLeftGutter(root)
                }
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
        reclaimRailSpace(aaFacetBar)
        param.result = aaFacetBar
    }

    private fun injectAaFacetBarInPlace(facetHost: ViewGroup, reason: String) {
        val parent = facetHost.parent as? ViewGroup
            ?: throw IllegalStateException("facet host has no parent")
        val index = parent.indexOfChild(facetHost)
        val lp = facetHost.layoutParams
        parent.removeView(facetHost)
        val aaFacetBar = buildAaFacetBar(facetHost, parent, "inplace:$reason")
        applyZeroWidthGone(aaFacetBar, lp)
        if (index >= 0) {
            parent.addView(aaFacetBar, index, aaFacetBar.layoutParams ?: lp)
        } else {
            parent.addView(aaFacetBar, aaFacetBar.layoutParams ?: lp)
        }
        reclaimRailSpace(aaFacetBar)
    }

    /**
     * Collapse the rail/facet column AND its thin wrappers, then expand content siblings
     * so the black gutter does not remain after chrome is hidden.
     */
    private fun reclaimRailSpace(rail: View) {
        applyZeroWidthGone(rail)
        collapseThinRailChainAndExpandContent(rail)
        rail.post { collapseThinRailChainAndExpandContent(rail) }
        rail.postDelayed({ collapseThinRailChainAndExpandContent(rail) }, 300L)
        rail.postDelayed({ collapseThinRailChainAndExpandContent(rail) }, 1000L)
    }

    private fun maxSideRailPx(view: View): Int =
        (140f * view.resources.displayMetrics.density).toInt().coerceIn(160, 480)

    private fun applyZeroWidthGone(view: View, sourceLp: ViewGroup.LayoutParams? = null) {
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
        // Parent paddingStart also leaves a dead touch strip after the rail is gone.
        (content.parent as? View)?.let { clearStartPadding(it) }
    }

    private fun clearStartPadding(view: View) {
        if (view.paddingStart == 0 && view.paddingLeft == 0) return
        view.setPaddingRelative(0, view.paddingTop, view.paddingEnd, view.paddingBottom)
    }

    private fun collapseThinRailChainAndExpandContent(from: View) {
        val maxSidePx = maxSideRailPx(from)
        var node: View = from
        applyZeroWidthGone(node)
        var depth = 0
        while (depth < 8) {
            val parent = node.parent as? ViewGroup ?: break
            when {
                parent is LinearLayout && parent.orientation == LinearLayout.HORIZONTAL -> {
                    applyZeroWidthGone(node)
                    for (i in 0 until parent.childCount) {
                        val c = parent.getChildAt(i) ?: continue
                        if (c === node || isThinSideRail(c, maxSidePx)) {
                            applyZeroWidthGone(c)
                        } else {
                            expandContentSibling(c)
                        }
                    }
                    parent.requestLayout()
                    logDebug(tagName, "AaUiHook: reclaimed left gutter via horizontal parent")
                    return
                }
                parent is ConstraintLayout -> {
                    applyZeroWidthGone(node)
                    expandConstraintContent(parent, node)
                    parent.requestLayout()
                    logDebug(tagName, "AaUiHook: reclaimed left gutter via constraint parent")
                    return
                }
                isThinSideRail(parent, maxSidePx) || parent.childCount <= 1 -> {
                    // Thin wrapper around the rail — keep collapsing upward.
                    applyZeroWidthGone(parent)
                    node = parent
                    depth++
                }
                else -> {
                    // Wide parent that is not a simple LL/Constraint — still try to expand
                    // non-rail children, then stop.
                    applyZeroWidthGone(node)
                    for (i in 0 until parent.childCount) {
                        val c = parent.getChildAt(i) ?: continue
                        if (c === node || isThinSideRail(c, maxSidePx)) {
                            applyZeroWidthGone(c)
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

    /** Scan a window/rail root for a leftover left gutter and reclaim it. */
    private fun reclaimLeftGutter(root: ViewGroup) {
        try {
            val injected = root.findViewWithTag<View>(facetBarInjectedTag)
            if (injected != null) {
                reclaimRailSpace(injected)
            }
            val column = findFacetColumn(root)
            if (column != null) {
                reclaimRailSpace(column)
            }
            // Fallback: leftmost thin child of any horizontal LinearLayout under root.
            val maxSidePx = maxSideRailPx(root)
            val queue = ArrayDeque<ViewGroup>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val vg = queue.removeFirst()
                if (vg is LinearLayout && vg.orientation == LinearLayout.HORIZONTAL && vg.childCount >= 2) {
                    val first = vg.getChildAt(0) ?: continue
                    if (isThinSideRail(first, maxSidePx) || first.visibility == View.GONE) {
                        reclaimRailSpace(first)
                        return
                    }
                }
                for (i in 0 until vg.childCount) {
                    val child = vg.getChildAt(i) as? ViewGroup ?: continue
                    queue.add(child)
                }
            }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: reclaimLeftGutter failed", e)
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
        val aaFacetBar = layoutInflater.inflate(R.layout.aa_facet_bar, resultViewGroupParent, false) as ConstraintLayout
        aaFacetBar.tag = facetBarInjectedTag
        resultViewGroup.tag = facetBarInjectedTag
        logDebug(tagName, "AaUiHook: collapse facet rail ($matchReason)")
        scheduleAutoOpenIfNeeded("facet:$matchReason")
        // Keep original chrome in hierarchy but hidden so AA lifecycle stays intact.
        resultViewGroup.visibility = View.GONE
        aaFacetBar.addView(resultViewGroup)
        applyZeroWidthGone(aaFacetBar)
        return aaFacetBar
    }

    private fun hookRadius() {
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
}
