package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.view.Choreographer
import android.view.Display
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
import io.github.nitsuya.aa.display.service.AaActivityService
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.util.ReconnectSizingTrace
import io.github.nitsuya.aa.display.util.rewriteMotionEvent
import io.github.nitsuya.aa.display.xposed.CoreManager
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.hook.DexKitMethodCache
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

object AaUiHook: AaHook() {
    override val tagName: String = "AAD_AaUiHook"
    override val usesDexKit: Boolean = true

    private const val CACHE_CONTENT_BOUNDS = "hook.AaUiHook.content_bounds"
    private const val CACHE_HU_TOUCH = "hook.AaUiHook.hu_touch"
    private const val CACHE_LAYOUT_INFO = "hook.AaUiHook.layout_info_class"

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
    /**
     * Fullscreen peel handle hit in the rail band — route to AaDisplay UI instead of
     * the pane VD so a flush-left peel stays tappable under Coolwalk steal.
     */
    @Volatile private var mHuPeelGesture = false
    /**
     * App picker (or other AaDisplay overlay) is open — left-rail steal must go to
     * [ICoreManager.touchAaDisplay], not the primary pane under the dimmer.
     */
    @Volatile private var mAaUiRailConsume = false
    /**
     * Cached split fullscreen pane for rail steal. DOWN/MOVE must not Binder-query
     * [CoreManager.splitFullscreenPane]; [AABroadcastConst.ACTION_SPLIT_STATE_CHANGED] is the
     * source of truth, plus one async warmup after the receiver is registered.
     */
    @Volatile private var mCachedFullscreenPane: Int = SplitPane.FULLSCREEN_NONE
    /** True after at least one SPLIT_STATE_CHANGED extra (skip binder warmup). */
    @Volatile private var mSplitStateSeen = false
    private val mRailMoveLock = Any()
    private var mPendingRailMove: MotionEvent? = null
    /** Bumped on UP/CANCEL so an in-flight frame flush cannot inject MOVE after UP. */
    @Volatile private var mRailMoveGeneration = 0
    private val mRailMoveFrameCallback = Choreographer.FrameCallback { flushPendingRailMove() }
    private val mRailMovePostToFrame = Runnable {
        val choreographer = Choreographer.getInstance()
        choreographer.removeFrameCallback(mRailMoveFrameCallback)
        choreographer.postFrameCallback(mRailMoveFrameCallback)
    }
    private var mSplitStateReceiver: android.content.BroadcastReceiver? = null
    private var mRailConsumeReceiver: android.content.BroadcastReceiver? = null


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
    private var canHookFacetBar: Boolean = false
    private var mInjectingFacetBar: Boolean = false
    private val facetBarInjectedTag = Any()
    private val mFacetEnsureHandler = Handler(Looper.getMainLooper())
    /**
     * Soft reconnect (no USB replug) often rebuilds LayoutInfo before GhFacetBar chrome
     * is attached; keep a single poll chain across the connect window (no N fixed kicks).
     */
    private val FACET_ENSURE_WINDOW_MS = 8_000L
    private val FACET_ENSURE_POLL_MS = 400L
    private val FACET_ENSURE_TOKEN = Any()
    private var mFacetEnsureDeadlineMs = 0L
    private val PROJECTION_CONFIG_KEYS = setOf(
        "content_bounds", "contentBounds", "content_insets", "contentInsets"
    )
    /**
     * CarSystemUiControllerService.a(Intent) readiness (AA 17.4):
     *  - `wmu.be(khh.r())` throws IllegalStateException if Car API client is not connected
     *  - CAMS null in :car → silent no-op (no exception; "CAMS is null")
     * Coolwalk's binder path queues the Intent until SysUi onCarConnected; we mirror that
     * with [mAutoOpenArmed] + a kick on the SysUi car-connected listener, plus sparse
     * CAMS retries. Stop only on [ACTION_AA_DISPLAY_SHOWN].
     */
    private val AUTO_OPEN_DELAYS_MS = longArrayOf(
        0L, 1500L, 5000L, 12_000L, 24_000L,
    )
    private val AUTO_OPEN_TOKEN = Any()
    /** Uptime of the last armed Auto Open session; used to debounce LayoutInfo storms. */
    private var mAutoOpenSessionAtMs = 0L
    /** Must be ≥ last [AUTO_OPEN_DELAYS_MS] entry so rearm cannot cancel in-flight retries. */
    private val AUTO_OPEN_REARM_GAP_MS = 24_000L
    @Volatile private var mAaDisplayShownThisSession = false
    /** True while retries are live; car-connected kick may fire an immediate start. */
    @Volatile private var mAutoOpenArmed = false
    private var mAutoOpenShownReceiver: android.content.BroadcastReceiver? = null
    private var mCarConnectedKickHooked = false
    private var mCarConnectedListenerHooked = false
    /** Coalesce reclaim follow-ups per view across soft-reconnect storms. */
    private val mReclaimFollowUps = java.util.WeakHashMap<View, Runnable>()

    /** Latest main-display LayoutInfo size in dp (from constructor args). */
    @Volatile private var mLayoutWidthDp: Int = 0
    @Volatile private var mLayoutHeightDp: Int = 0
    /** Last observed GhFacetBar / thin-rail VD width in px (for content expand fallback). */
    @Volatile private var mObservedRailWidthPx: Int = 0
    /**
     * Largest landscape HU-sized VD width seen in this process (excludes rail / Dashboard /
     * phone portrait). Used when LayoutInfo still reports the content slot (HU−rail).
     */
    @Volatile private var mObservedFullHuWidthPx: Int = 0
    /**
     * Android displayId of the live FacetBar / thin-rail VD (per-process).
     * Used so :car can steal every touch routed to that display even when
     * LayoutInfo width is unavailable and x-band heuristics would under-steal.
     * Never DEFAULT_DISPLAY — phone panels must not be treated as FacetBar.
     */
    @Volatile private var mObservedRailDisplayId: Int = Display.INVALID_DISPLAY
    @Volatile private var mLoggedDashboardStarve = false
    @Volatile private var mLastProjectionConfigRewrite: String? = null

    override fun isSupportProcess(processName: String): Boolean {
        // Facet/VD UI lives in :projection; Coolwalk writes content_bounds in :car
        // (GhLifecycleService.onProjectionStart) — both need hooks.
        return processProjection == processName || processCar == processName
    }

    override fun applyCache(
        cache: DexKitMethodCache.Session,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ): Boolean {
        val contentRefs = cache.getRefs(CACHE_CONTENT_BOUNDS) ?: return false
        contentBoundsMethods = cache.resolveAll(lpparam.classLoader, contentRefs) ?: return false
        if (!cache.hasKey(CACHE_HU_TOUCH)) return false
        huTouchDispatchMethod = cache.getRef(CACHE_HU_TOUCH)?.let { ref ->
            cache.resolve(lpparam.classLoader, ref) ?: return false
        }
        log(
            tagName,
            "AaUiHook: content_bounds methods=${contentBoundsMethods.size} (cache) " +
                contentBoundsMethods.map { "${it.declaringClass.name}#${it.name}" },
        )
        log(
            tagName,
            "AaUiHook: HU touch dispatch method=" +
                (huTouchDispatchMethod?.let { "${it.declaringClass.name}#${it.name}" } ?: "null") +
                " (cache)",
        )
        startMethod = resolveCarStartActivityMethod()
        if (lpparam.processName == processCar) {
            return true
        }
        val layoutInfoClassName = cache.getString(CACHE_LAYOUT_INFO) ?: return false
        layoutInfoConstructors = runCatching {
            resolveLayoutInfoConstructors(layoutInfoClassName)
        }.onFailure { e ->
            log(tagName, "AaUiHook: LayoutInfo cache resolve failed", e)
        }.getOrNull() ?: return false
        log(
            tagName,
            "AaUiHook: LayoutInfo ctors=${layoutInfoConstructors.size} (cache) " +
                layoutInfoConstructors.joinToString { "p${it.parameterCount}" },
        )
        loadProjectionResources()
        return true
    }

    override fun saveCache(
        cache: DexKitMethodCache.Session,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ) {
        cache.putRefs(CACHE_CONTENT_BOUNDS, contentBoundsMethods)
        cache.putRef(CACHE_HU_TOUCH, huTouchDispatchMethod)
        if (lpparam.processName != processCar && layoutInfoConstructors.isNotEmpty()) {
            cache.putString(CACHE_LAYOUT_INFO, layoutInfoConstructors[0].declaringClass.name)
        }
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

        // AutoOpen retries are armed by [scheduleAutoOpenIfNeeded], but [hookContentBounds]
        // runs in both :car and :projection. Make startMethod available in :car too so
        // system_server soft reconnect can still auto-open.
        startMethod = resolveCarStartActivityMethod()

        // :car only needs the projection-config Bundle rewrite; skip LayoutInfo/facet setup.
        if (lpparam.processName == processCar) {
            return
        }

        val classes = bridge.findClass {
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

        loadProjectionResources()
    }

    private fun loadProjectionResources() {
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

        if (resLayoutLeftResourceId == 0) {
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
            logDebug(tagName, "AaUiHook: facet layout ids=$facetBarLayoutIds railHosts=$railHostLayoutIds")
        }
        logDebug(tagName, "AaUiHook: rail width dimens=$railWidthDimenIds")
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Coolwalk: GhLifecycleService in :car puts content_bounds=Rect(rail,0,fullW,fullH).
        // Must rewrite here — :projection never sees that putParcelable.
        hookContentBounds()
        if (lpparam.processName == processCar) {
            // HU touch dispatch (imt.H/G/I) only runs in :car.
            hookHuTouchDispatchRedirect()
            // Discover this HU's FacetBar / thin-rail display early (no LayoutInfo in :car).
            ensureRailObservationFromDisplays()
            registerSplitStateReceiver()
            registerAaUiRailConsumeReceiver()
            return
        }
        logDebug(tagName, "AaUiHook: AutoOpen always-on startMethod=${startMethod?.name}")
        // Zero rail-column dimens first so LayoutInfo / VD allocation sees full HU width.
        hookRailWidthDimens()
        hookVirtualDisplaySizing()
        // Always force vertical rail on LayoutInfo.
        // Missing canonical layout resources still need hasVerticalRail=true or AA
        // falls back to the bottom facet bar (GhFacetBar 800×80 on 800×480 HUs).
        hookLayoutInfo()
        registerAutoOpenShownReceiver()
        hookCarSystemUiConnectedKick()
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
                "${layoutInfoConstructors.size} lhd=$resLayoutLeftResourceId"
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

    private fun logProjectionConfigRewriteOnce(message: String) {
        if (!ReconnectSizingTrace.ENABLED) return
        if (mLastProjectionConfigRewrite == message) return
        mLastProjectionConfigRewrite = message
        logDebug(tagName, message)
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
        logDebug(tagName, "AaUiHook: hooked rail width dimens → 0 (${railWidthDimenIds.size} ids)")
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
                        rewriteProjectionConfigParcelable(param.args[0] as? String, param.args[1])?.let { it ->
                            param.args[1] = it
                            // content_bounds updates are a reliable "layout refresh" signal; arm
                            // AutoOpen even if :projection hooks didn't re-run yet.
                            scheduleAutoOpenIfNeeded("content_bounds")
                        }
                    }
                    logDebug(tagName, "AaUiHook: hooked BaseBundle.putParcelable(content_bounds)")
                }
                if (method.name == "putInt" && method.parameterCount == 2 &&
                    method.parameterTypes[0] == String::class.java &&
                    (method.parameterTypes[1] == Int::class.javaPrimitiveType ||
                        method.parameterTypes[1] == Integer::class.java)
                ) {
                    method.isAccessible = true
                    method.hookBefore { param ->
                        val key = param.args[0] as? String ?: return@hookBefore
                        if (key != "pillar_width") return@hookBefore
                        val value = (param.args[1] as? Number)?.toInt() ?: return@hookBefore
                        if (value == 0) return@hookBefore
                        val range = railPxRange(layoutWidthPx().takeIf { it > 0 } ?: (value * 10))
                        if (value in range) {
                            mObservedRailWidthPx = value
                            param.args[1] = 0
                        }
                    }
                    logDebug(tagName, "AaUiHook: hooked BaseBundle.putInt(pillar_width)")
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
                rewriteProjectionConfigParcelable(param.args[0] as? String, param.args[1])?.let { it ->
                    param.args[1] = it
                    scheduleAutoOpenIfNeeded("content_bounds")
                }
            }
            logDebug(tagName, "AaUiHook: hooked Bundle.putParcelable(content_bounds)")
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: hook Bundle.putParcelable failed", e)
        }
        try {
            findMethod(Bundle::class.java) {
                name == "putInt" && parameterCount == 2 &&
                    parameterTypes[0] == String::class.java &&
                    (parameterTypes[1] == Int::class.javaPrimitiveType ||
                        parameterTypes[1] == Integer::class.java)
            }.hookBefore { param ->
                val key = param.args[0] as? String ?: return@hookBefore
                if (key != "pillar_width") return@hookBefore
                val value = (param.args[1] as? Number)?.toInt() ?: return@hookBefore
                if (value == 0) return@hookBefore
                val range = railPxRange(layoutWidthPx().takeIf { it > 0 } ?: (value * 10))
                if (value in range) {
                    mObservedRailWidthPx = value
                    param.args[1] = 0
                }
            }
            logDebug(tagName, "AaUiHook: hooked Bundle.putInt(pillar_width)")
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
                    if (key !in PROJECTION_CONFIG_KEYS) return@hookAfter
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
                val key = param.args[0] as? String ?: return@hookBefore
                if (key !in PROJECTION_CONFIG_KEYS) return@hookBefore
                rewriteProjectionConfigParcelable(key, param.args[1])?.let {
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
                val key = param.args[0] as? String ?: return@hookBefore
                if (key !in PROJECTION_CONFIG_KEYS) return@hookBefore
                rewriteProjectionConfigParcelable(key, param.args[1])?.let {
                    param.args[1] = it
                }
            }
            logDebug(tagName, "AaUiHook: hooked ArrayMap.put for content_bounds")
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
            logDebug(tagName, "AaUiHook: hooked Rect(int,int,int,int) for content_bounds")
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
            logDebug(tagName, "AaUiHook: hooked Rect.set for content_bounds mutate")
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
                logDebug(tagName, "AaUiHook: hooked ${method.declaringClass.name}#${method.name} for config Bundle")
            } catch (e: Throwable) {
                log(tagName, "AaUiHook: hook ${method.declaringClass.name}#${method.name} failed", e)
            }
        }
    }

    private fun fixProjectionConfigBundle(bundle: Bundle) {
        try {
            val bounds = bundleParcelableRect(bundle, "content_bounds")
            val boundsBefore = bounds?.let { Rect(it) }
            val boundsAfter = rewriteProjectionConfigParcelable("content_bounds", bounds)
            val insets = bundleParcelableRect(bundle, "content_insets")
            val insetsBefore = insets?.let { Rect(it) }
            val insetsAfter = rewriteProjectionConfigParcelable("content_insets", insets)
            var pillarRewrite: String? = null
            if (bundle.containsKey("pillar_width")) {
                val value = bundle.getInt("pillar_width", 0)
                if (value != 0) {
                    val range = railPxRange(layoutWidthPx().takeIf { it > 0 } ?: (value * 10))
                    if (value in range) {
                        mObservedRailWidthPx = value
                        bundle.putInt("pillar_width", 0)
                        pillarRewrite = "$value→0"
                    }
                }
            }
            if (boundsAfter != null || insetsAfter != null || pillarRewrite != null) {
                logProjectionConfigRewriteOnce(
                    "AaUiHook: projection config rewrite " +
                        "layout=${layoutWidthPx()}x${layoutHeightPx()} " +
                        "bounds=${boundsBefore ?: "null"}→${bounds ?: "null"} " +
                        "insets=${insetsBefore ?: "null"}→${insets ?: "null"} " +
                        "pillar=${pillarRewrite ?: "unchanged"}"
                )
            }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: fixProjectionConfigBundle failed", e)
        }
    }

    private fun rewriteProjectionConfigParcelable(key: String?, value: Any?): Rect? {
        if (key.isNullOrEmpty() || key !in PROJECTION_CONFIG_KEYS) return null
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
            val heightOk = abs(rect.bottom - fullH) <= 2
            val widthOk = abs(rect.right - fullW) <= 2 || abs(rect.right + rect.left - fullW) <= 2
            if (!heightOk || !widthOk) return false
            // Require an actual rail inset so full-bleed / unrelated HU rects stay untouched.
            val range = railPxRange(fullW)
            val leftInset = rect.left in range
            val rightInset = rect.left <= 0 && (fullW - rect.right) in range
            return leftInset || rightInset
        }
        // Without LayoutInfo: landscape HU-sized rect with a proportional left gutter.
        if (rect.right < 320 || rect.bottom < 180 || rect.width() < rect.left * 3) return false
        val approxFullW = rect.right
        return rect.left in railPxRange(approxFullW)
    }

    /** Expand a rail-inset content rect to full HU origin; mutates [rect] in place when possible. */
    private fun applyExpandedContentBounds(rect: Rect): Rect? {
        val fullW = layoutWidthPx().takeIf { it > 0 } ?: rect.right
        val fullH = layoutHeightPx().takeIf { it > 0 } ?: rect.bottom
        val range = railPxRange(fullW)
        // Left-aligned content slot Rect(0,0,HU−rail,H) — common when LayoutInfo is missing
        // and [fullW] was taken from rect.right. Re-evaluate against observed full HU.
        val observedFull = mObservedFullHuWidthPx
        if (rect.left <= 0 && observedFull > fullW + 8) {
            val gap = observedFull - rect.right
            if (gap in railPxRange(observedFull) && rect.top <= 0) {
                val before = Rect(rect)
                mObservedRailWidthPx = gap
                rect.set(0, 0, observedFull, fullH.coerceAtLeast(rect.bottom))
                logProjectionConfigRewriteOnce(
                    "AaUiHook: content_bounds expanded $before→$rect layout=${observedFull}x${fullH}"
                )
                return rect
            }
        }
        // LHD: left gutter reserved for vertical rail.
        if (rect.left in range && rect.right >= fullW - 2 && rect.top <= 0) {
            val before = Rect(rect)
            mObservedRailWidthPx = rect.left
            rect.set(0, 0, fullW.coerceAtLeast(rect.right), fullH.coerceAtLeast(rect.bottom))
            logProjectionConfigRewriteOnce(
                "AaUiHook: content_bounds expanded $before→$rect layout=${fullW}x${fullH}"
            )
            return rect
        }
        // RHD: right edge pulled in by rail width.
        val rightGap = fullW - rect.right
        if (rect.left <= 0 && rightGap in range && rect.top <= 0) {
            val before = Rect(rect)
            mObservedRailWidthPx = rightGap
            rect.set(0, 0, fullW, fullH.coerceAtLeast(rect.bottom))
            logProjectionConfigRewriteOnce(
                "AaUiHook: content_bounds expanded $before→$rect layout=${fullW}x${fullH}"
            )
            return rect
        }
        return null
    }

    /** Zero left/right rail insets published alongside content_bounds. */
    private fun applyZeroedContentInsets(rect: Rect): Rect? {
        val range = railPxRange(layoutWidthPx().takeIf { it > 0 } ?: rect.left.coerceAtLeast(rect.right) * 10)
        val before = Rect(rect)
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
        logProjectionConfigRewriteOnce(
            "AaUiHook: content_insets zeroed $before→$rect layout=${layoutWidthPx()}x${layoutHeightPx()}"
        )
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
            logDebug(tagName, "AaUiHook: hooked DisplayManager.createVirtualDisplay overloads=$hooked")
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
            logDebug(tagName, "AaUiHook: hooked VirtualDisplayConfig.Builder paths=$hookedBuilder")
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
        if (!isTrustedRailDisplayId(display.displayId)) return
        val width = display.mode.physicalWidth
        if (width > 1) {
            mObservedRailWidthPx = width
        }
        mObservedRailDisplayId = display.displayId
        logDebug(
            tagName,
            "AaUiHook: observe rail VD id=${display.displayId} name=$name w=$width"
        )
    }

    /**
     * Steal left-rail HU touches before Coolwalk hit-tests windows.
     * AA 17.4: imt.H(CarDisplayId, ProjectionTouchEvent), imt.G/I(CarDisplayId, MotionEvent).
     * Must run in :car — that is where CarActivityManagerService lives.
     *
     * FacetBar VD often stays full rail width with a GONE window (no touchable target);
     * InputDispatcher then drops the event. Full-display steal only for a **trusted**
     * FacetBar / absolute-narrow private VD; otherwise fall back to the observed-width
     * x-band (never treat the phone DEFAULT_DISPLAY as rail).
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
            "AaUiHook: hooked HU touch dispatch → rail steal " +
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
            ensureRailObservationFromDisplays()
            val railTarget = isRailTargetCarDisplay(param.args[0])
            val rail = railHitWidthPx()
            val action = motion.actionMasked
            // Trusted FacetBar display → steal the whole gesture (Samsung GONE chrome).
            // Otherwise only the left x-band (r5-compatible; avoids phone-main-display mis-steal).
            val downInRailBand = motion.getX(0) < rail
            val downInRail = if (railTarget) true else downInRailBand
            if (action == MotionEvent.ACTION_DOWN) {
                mHuRailGesture = downInRail
                mHuPeelGesture = downInRail &&
                    SplitPane.isFullscreenPane(mCachedFullscreenPane) &&
                    isPeelHandleHitBand(motion)
            }
            // Follow through only when the gesture *started* in the rail. Do not steal a
            // content gesture that merely slides into the left strip (common map/list mis-touch).
            val steal = when (action) {
                MotionEvent.ACTION_DOWN -> downInRail
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val was = mHuRailGesture
                    mHuRailGesture = false
                    // Keep peel routing for this UP/CANCEL, then clear.
                    val result = was
                    if (!was) mHuPeelGesture = false
                    result
                }
                else -> mHuRailGesture
            }
            if (!steal) {
                if (action == MotionEvent.ACTION_DOWN ||
                    action == MotionEvent.ACTION_UP ||
                    action == MotionEvent.ACTION_CANCEL
                ) {
                    cancelRailMoveFlush()
                    mRailMoveGeneration++
                    takePendingRailMove()?.recycle()
                }
                return
            }
            // Coolwalk MotionEvent clocks are often not InputDispatcher uptime — rewrite
            // before Binder inject or the event is dropped (same as PTE decode path).
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) {
                mRailHostDownTime = now
            }
            val down = mRailHostDownTime.takeIf { it > 0L } ?: now
            val toInject = rewriteMotionEvent(
                source = motion,
                downTime = down,
                eventTime = now,
                sourceOverride = InputDevice.SOURCE_TOUCHSCREEN,
            )
            var retainInject = false
            try {
                val fs = mCachedFullscreenPane
                val peel = mHuPeelGesture
                val railToAaUi = mAaUiRailConsume
                if (action == MotionEvent.ACTION_MOVE) {
                    // Same as TextureView: keep last MOVE, flush once per frame.
                    queuePendingRailMove(toInject)
                    retainInject = true
                    param.result = null
                    return
                }
                cancelRailMoveFlush()
                mRailMoveGeneration++
                takePendingRailMove()?.let { pending ->
                    try {
                        injectStolenRailEvent(pending, fs, peel, railToAaUi)
                    } finally {
                        pending.recycle()
                    }
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    mHuPeelGesture = false
                }
                val injected = injectStolenRailEvent(toInject, fs, peel, railToAaUi)
                // DOWN: only swallow when inject reached system_server (else Coolwalk keeps it).
                // UP/CANCEL: already stole DOWN — swallow even if this parcel fails so Coolwalk
                // does not see a lone UP.
                if (!injected && action == MotionEvent.ACTION_DOWN) return
                param.result = null
                if (action == MotionEvent.ACTION_DOWN) {
                    logDebug(
                        tagName,
                        "AaUiHook: HU rail → " +
                            (when {
                                peel || railToAaUi -> "touchAaDisplay"
                                SplitPane.isFullscreenPane(fs) -> "touchPane($fs)"
                                else -> "touchPrimaryPane"
                            }) +
                            " x=${motion.x} y=${motion.y} rail=$rail " +
                            "facetTarget=$railTarget picker=$railToAaUi"
                    )
                }
            } finally {
                if (!retainInject) toInject.recycle()
            }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: HU rail steal failed", e)
        } finally {
            if (owned) motion.recycle()
        }
    }

    private fun injectStolenRailEvent(
        event: MotionEvent,
        fs: Int,
        peel: Boolean,
        railToAaUi: Boolean,
    ): Boolean {
        return when {
            peel || railToAaUi -> CoreManager.tryTouchAaDisplay(event)
            SplitPane.isFullscreenPane(fs) -> CoreManager.tryTouchPane(fs, event)
            else -> CoreManager.tryTouchPrimaryPane(event)
        }
    }

    private fun queuePendingRailMove(event: MotionEvent) {
        synchronized(mRailMoveLock) {
            mPendingRailMove?.recycle()
            mPendingRailMove = event
        }
        scheduleRailMoveFlush()
    }

    private fun takePendingRailMove(): MotionEvent? {
        synchronized(mRailMoveLock) {
            val pending = mPendingRailMove
            mPendingRailMove = null
            return pending
        }
    }

    private fun scheduleRailMoveFlush() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val choreographer = Choreographer.getInstance()
            choreographer.removeFrameCallback(mRailMoveFrameCallback)
            choreographer.postFrameCallback(mRailMoveFrameCallback)
        } else {
            mFacetEnsureHandler.removeCallbacks(mRailMovePostToFrame)
            mFacetEnsureHandler.post(mRailMovePostToFrame)
        }
    }

    private fun cancelRailMoveFlush() {
        mFacetEnsureHandler.removeCallbacks(mRailMovePostToFrame)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Choreographer.getInstance().removeFrameCallback(mRailMoveFrameCallback)
        }
    }

    private fun flushPendingRailMove() {
        val generation = mRailMoveGeneration
        val pending = takePendingRailMove() ?: return
        if (generation != mRailMoveGeneration) {
            pending.recycle()
            return
        }
        try {
            val fs = mCachedFullscreenPane
            injectStolenRailEvent(
                pending,
                fs,
                mHuPeelGesture,
                mAaUiRailConsume,
            )
        } finally {
            pending.recycle()
        }
    }

    /**
     * Vertical (side-by-side) or horizontal (stacked) band around the peel tab center.
     * Delegates to [SplitPane.peelHitContains] so flush-edge geometry stays one source.
     */
    private fun isPeelHandleHitBand(motion: MotionEvent): Boolean {
        return SplitPane.peelHitContains(
            motion.getX(0),
            motion.getY(0),
            layoutWidthPx(),
            layoutHeightPx(),
        )
    }

    /** Never treat the phone main display as Coolwalk FacetBar. */
    private fun isTrustedRailDisplayId(displayId: Int): Boolean {
        return displayId != Display.INVALID_DISPLAY && displayId != Display.DEFAULT_DISPLAY
    }

    private fun clearUntrustedRailObservation() {
        if (!isTrustedRailDisplayId(mObservedRailDisplayId)) {
            mObservedRailDisplayId = Display.INVALID_DISPLAY
        }
        // Portrait phone panels mis-locked as "rail" leave a huge width; drop it when
        // we have no trusted FacetBar id (x-band then uses the narrow fallback).
        if (mObservedRailDisplayId == Display.INVALID_DISPLAY && mObservedRailWidthPx > 240) {
            mObservedRailWidthPx = 0
        }
    }

    /**
     * Absolute-narrow private strip when LayoutInfo is unavailable (:car).
     * Must not match phone portrait panels (e.g. 1080×2340).
     */
    private fun isAbsoluteNarrowRailSize(width: Int, height: Int): Boolean {
        return width in 8..120 && height >= width * 3
    }

    /**
     * :car does not run LayoutInfo / createVirtualDisplay hooks; discover the live
     * FacetBar display via DisplayManager. Prefer named FacetBar; never first-hit the
     * phone DEFAULT_DISPLAY via loose thin-geometry heuristics.
     */
    private fun ensureRailObservationFromDisplays() {
        clearUntrustedRailObservation()
        // Starved FacetBar is 1px so width stays 0 — still keep the display id or every
        // HU touch rediscovers and spams "discovered rail … w=1".
        if (isTrustedRailDisplayId(mObservedRailDisplayId)) return
        val dm = runCatching {
            InitFields.appContext.getSystemService(DisplayManager::class.java)
        }.getOrNull() ?: return
        // Pass 1: named FacetBar / VerticalRail / EdgeColumn only.
        for (display in dm.displays) {
            if (!isTrustedRailDisplayId(display.displayId)) continue
            if (!isRailVirtualDisplayName(display.name)) continue
            val w = runCatching { display.mode.physicalWidth }.getOrDefault(0)
            if (w > 1) mObservedRailWidthPx = w
            mObservedRailDisplayId = display.displayId
            logDebug(
                tagName,
                "AaUiHook: discovered rail display id=${display.displayId} name=${display.name} w=$w"
            )
            return
        }
        // Pass 2: absolute-narrow private strip only (no LayoutInfo width*10 heuristic).
        for (display in dm.displays) {
            if (!isTrustedRailDisplayId(display.displayId)) continue
            if (isRailVirtualDisplayName(display.name)) continue
            val w = runCatching { display.mode.physicalWidth }.getOrDefault(0)
            val h = runCatching { display.mode.physicalHeight }.getOrDefault(0)
            if (!isAbsoluteNarrowRailSize(w, h)) continue
            mObservedRailWidthPx = w
            mObservedRailDisplayId = display.displayId
            logDebug(
                tagName,
                "AaUiHook: discovered narrow rail display id=${display.displayId} name=${display.name} w=$w"
            )
            return
        }
    }

    /**
     * True when Coolwalk is routing this HU touch to a trusted FacetBar / narrow-rail display.
     */
    private fun isRailTargetCarDisplay(carDisplayId: Any?): Boolean {
        if (carDisplayId == null) return false
        clearUntrustedRailObservation()
        val androidId = androidDisplayIdFromCarDisplayId(carDisplayId)
            ?: return false
        if (!isTrustedRailDisplayId(androidId)) return false
        if (androidId == mObservedRailDisplayId && isTrustedRailDisplayId(mObservedRailDisplayId)) {
            return true
        }
        val display = runCatching {
            InitFields.appContext.getSystemService(DisplayManager::class.java)
                ?.getDisplay(androidId)
        }.getOrNull() ?: return false
        if (isRailVirtualDisplayName(display.name)) {
            mObservedRailDisplayId = androidId
            runCatching {
                val w = display.mode.physicalWidth
                if (w > 1) mObservedRailWidthPx = w
            }
            return true
        }
        val w = runCatching { display.mode.physicalWidth }.getOrDefault(0)
        val h = runCatching { display.mode.physicalHeight }.getOrDefault(0)
        // With LayoutInfo: proportional thin rail. Without: absolute narrow only.
        val thin = if (layoutWidthPx() > 0) {
            isThinRailSize(w, h)
        } else {
            isAbsoluteNarrowRailSize(w, h)
        }
        if (w > 1 && h > 0 && thin) {
            mObservedRailDisplayId = androidId
            mObservedRailWidthPx = w
            return true
        }
        return false
    }

    private val carDisplayIdAccessorNames = setOf(
        "getDisplayId", "displayId", "getId", "id", "getAndroidDisplayId", "androidDisplayId",
    )
    private val carDisplayIdFieldNames = setOf("mDisplayId", "displayId", "id")

    /** Per-class whitelist Method/Field accessors for CarDisplayId → Android displayId. */
    private data class CarDisplayIdAccessors(
        val methods: List<Method>,
        val fields: List<Field>,
    )

    private val carDisplayIdAccessorsByClass =
        ConcurrentHashMap<Class<*>, CarDisplayIdAccessors>()

    private fun resolveCarDisplayIdAccessors(clazz: Class<*>): CarDisplayIdAccessors {
        carDisplayIdAccessorsByClass[clazz]?.let { return it }
        val methods = clazz.methods.filter { m ->
            m.parameterCount == 0 &&
                m.name in carDisplayIdAccessorNames &&
                (m.returnType == Int::class.javaPrimitiveType || m.returnType == Integer::class.java)
        }.onEach { it.isAccessible = true }
        val fields = clazz.declaredFields.filter { f ->
            !Modifier.isStatic(f.modifiers) &&
                (f.name in carDisplayIdAccessorNames || f.name in carDisplayIdFieldNames) &&
                (f.type == Int::class.javaPrimitiveType || f.type == Integer::class.java)
        }.onEach { it.isAccessible = true }
        val resolved = CarDisplayIdAccessors(methods, fields)
        // Only cache when at least one accessor exists; empty → keep discovering each call.
        if (methods.isNotEmpty() || fields.isNotEmpty()) {
            carDisplayIdAccessorsByClass[clazz] = resolved
        }
        return resolved
    }

    /** Best-effort CarDisplayId / wrapper → Android displayId (whitelist accessors only). */
    private fun androidDisplayIdFromCarDisplayId(carDisplayId: Any): Int? {
        when (carDisplayId) {
            is Int -> return carDisplayId.takeIf { it != Display.INVALID_DISPLAY }
            is Number -> return carDisplayId.toInt().takeIf { it != Display.INVALID_DISPLAY }
        }
        val accessors = resolveCarDisplayIdAccessors(carDisplayId.javaClass)
        val candidates = linkedSetOf<Int>()
        for (m in accessors.methods) {
            runCatching {
                val v = (m.invoke(carDisplayId) as? Number)?.toInt() ?: return@runCatching
                if (v >= 0) candidates += v
            }
        }
        for (f in accessors.fields) {
            runCatching {
                val v = (f.get(carDisplayId) as? Number)?.toInt() ?: return@runCatching
                if (v >= 0) candidates += v
            }
        }
        if (candidates.isEmpty()) return null
        if (mObservedRailDisplayId in candidates && isTrustedRailDisplayId(mObservedRailDisplayId)) {
            return mObservedRailDisplayId
        }
        val dm = runCatching {
            InitFields.appContext.getSystemService(DisplayManager::class.java)
        }.getOrNull()
        if (dm != null) {
            for (id in candidates) {
                if (!isTrustedRailDisplayId(id)) continue
                val d = dm.getDisplay(id) ?: continue
                if (isRailVirtualDisplayName(d.name)) return id
            }
            for (id in candidates) {
                if (!isTrustedRailDisplayId(id)) continue
                val d = dm.getDisplay(id) ?: continue
                val w = runCatching { d.mode.physicalWidth }.getOrDefault(0)
                val h = runCatching { d.mode.physicalHeight }.getOrDefault(0)
                val thin = if (layoutWidthPx() > 0) isThinRailSize(w, h) else isAbsoluteNarrowRailSize(w, h)
                if (w > 1 && h > 0 && thin) return id
            }
        }
        // Prefer a single non-default candidate; never fall back to DEFAULT_DISPLAY alone.
        return candidates.singleOrNull { isTrustedRailDisplayId(it) }
    }

    /**
     * LHD left-rail hit band in HU px. Prefer live FacetBar / content_bounds width.
     * Use the full observed width (no 0.9 shrink) so the Coolwalk routing band and our
     * steal band do not leave a dead seam. Never hardcode a single HU's pixel size.
     */
    private fun railHitWidthPx(): Int {
        clearUntrustedRailObservation()
        val observed = mObservedRailWidthPx
        val fullW = layoutWidthPx()
        if (fullW > 0) {
            val range = railPxRange(fullW)
            if (observed in range) return observed
            // Observed from FacetBar but slightly outside the proportional range — still trust it.
            if (observed in 8..(fullW / 2)) return observed
            return (fullW * 0.08f).roundToInt().coerceIn(range)
        }
        // :car often has no LayoutInfo — trust FacetBar / content_bounds observation only.
        if (observed in 8..240) return observed
        return 48
    }

    /**
     * Cached field layout for obfuscated Coolwalk ProjectionTouchEvent.
     * Layout (AA 17.4): action:int, actionIndex:int, time:long, pointers:[{x,y,id}:int].
     */
    private data class PteLayout(
        val actionField: Field,
        val pointersField: Field,
        val pointerX: Field,
        val pointerY: Field,
        val pointerId: Field,
    )

    private val pteLayoutByClass = ConcurrentHashMap<Class<*>, PteLayout>()

    private class PtePointerBuffers {
        var props = Array(8) { MotionEvent.PointerProperties() }
        var coords = Array(8) { MotionEvent.PointerCoords() }

        fun ensure(count: Int) {
            if (props.size >= count) return
            val n = count.coerceAtLeast(props.size * 2)
            props = Array(n) { i -> if (i < props.size) props[i] else MotionEvent.PointerProperties() }
            coords = Array(n) { i -> if (i < coords.size) coords[i] else MotionEvent.PointerCoords() }
        }
    }

    private val ptePointerBuffers = ThreadLocal.withInitial { PtePointerBuffers() }

    private fun resolvePteLayout(pte: Any): PteLayout? {
        val clazz = pte.javaClass
        pteLayoutByClass[clazz]?.let { return it }
        val intFields = mutableListOf<Field>()
        var pointersField: Field? = null
        for (f in clazz.declaredFields) {
            if (Modifier.isStatic(f.modifiers)) continue
            f.isAccessible = true
            when (f.type) {
                Int::class.javaPrimitiveType -> intFields += f
                else -> {
                    if (f.type.isArray) {
                        pointersField = f
                    } else {
                        val v = runCatching { f.get(pte) }.getOrNull()
                        if (v != null && v.javaClass.isArray) pointersField = f
                    }
                }
            }
        }
        val actionField = intFields.getOrNull(0) ?: return null
        val pf = pointersField ?: return null
        val arr = runCatching { pf.get(pte) as? Array<*> }.getOrNull()
        val pointerClass = arr?.firstOrNull()?.javaClass
            ?: arr?.javaClass?.componentType
            ?: pf.type.componentType
            ?: return null
        val pInts = pointerClass.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .onEach { it.isAccessible = true }
        if (pInts.size < 3) return null
        val layout = PteLayout(
            actionField = actionField,
            pointersField = pf,
            pointerX = pInts[0],
            pointerY = pInts[1],
            pointerId = pInts[2],
        )
        pteLayoutByClass[clazz] = layout
        return layout
    }

    /**
     * Best-effort decode of obfuscated Coolwalk ProjectionTouchEvent → [MotionEvent].
     * Layout (AA 17.4): action:int, actionIndex:int, time:long, pointers:[{x,y,id}:int].
     */
    private fun projectionTouchEventToMotionEvent(pte: Any?): MotionEvent? {
        if (pte == null) return null
        return try {
            val layout = resolvePteLayout(pte) ?: return null
            val action = layout.actionField.getInt(pte)
            val pointers = layout.pointersField.get(pte) as? Array<*> ?: return null
            if (pointers.isEmpty()) return null
            val buffers = ptePointerBuffers.get()!!
            buffers.ensure(pointers.size)
            val props = buffers.props
            val coords = buffers.coords
            for (i in pointers.indices) {
                val p = pointers[i] ?: return null
                // AA 17.4 VirtualTouchEvent path: e=x, f=y, g=pointerId
                val x = layout.pointerX.getInt(p)
                val y = layout.pointerY.getInt(p)
                val id = layout.pointerId.getInt(p)
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
            // Layout drift / bad cache — drop and rediscover next event.
            pte?.javaClass?.let { pteLayoutByClass.remove(it) }
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
        // Coolwalk empty media card Presentation — starve before compositor maps it to HU.
        if (name?.equals("Dashboard", ignoreCase = true) == true) {
            if (width == 1 && height == 1) return null
            if (!mLoggedDashboardStarve) {
                mLoggedDashboardStarve = true
                logDebug(tagName, "AaUiHook: starve Dashboard VD ${width}x$height → 1x1")
            }
            return 1 to 1
        }
        val railName = name?.contains("FacetBar", ignoreCase = true) == true ||
            name?.contains("GhFacet", ignoreCase = true) == true ||
            name?.contains("VerticalRail", ignoreCase = true) == true ||
            name?.contains("EdgeColumn", ignoreCase = true) == true
        // Named FacetBar: remember real strip width for touch hit-tests, then starve the
        // compositor slot to 1px so content_bounds expand + displayProfile share one truth
        // (full HU). Leaving it at 80px was the 800-vs-720 reconnect oscillation source.
        if (railName) {
            if (width > 1) {
                mObservedRailWidthPx = width
                logDebug(
                    tagName,
                    "AaUiHook: starve FacetBar VD name=$name ${width}x$height → 1x$height"
                )
                // Host (GhostActivity) often rebuilds the leftover gutter after this starve.
                mFacetEnsureHandler.post { reclaimAllWindowGutters("starve-facet") }
                scheduleEnsureFacetBar("starve-facet")
                return 1 to height
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
        rememberFullHuSize(width, height)
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

    private fun layoutWidthPx(): Int =
        maxOf(mLayoutWidthDp, mObservedFullHuWidthPx).takeIf { it > 0 } ?: 0

    private fun rememberFullHuSize(width: Int, height: Int) {
        if (width < 640 || height < 320 || width <= height) return
        if (width > mObservedFullHuWidthPx) mObservedFullHuWidthPx = width
    }

    private fun layoutHeightPx(): Int = mLayoutHeightDp.takeIf { it > 0 } ?: 0

    /** Rail strip width in car pixels (~5–25% of HU width, clamped). */
    private fun railPxRange(fullW: Int): IntRange {
        val min = (fullW * 0.05f).roundToInt().coerceIn(32, 96)
        val max = (fullW * 0.25f).roundToInt().coerceAtLeast(min).coerceAtMost(fullW / 2).coerceAtLeast(120)
        return min..max
    }

    private fun isThinRailSize(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val fullW = layoutWidthPx()
        if (fullW <= 0) {
            // No LayoutInfo (:car / early connect) — absolute narrow only; never width*10
            // which matches portrait phone panels (1080×2340).
            return isAbsoluteNarrowRailSize(width, height)
        }
        val maxRail = railPxRange(fullW).last
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
                logDebug(tagName, "AaUiHook: startMethod=a(Intent)")
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
            logDebug(tagName, "AaUiHook: startMethod fallback=${picked.name}(Intent) candidates=${candidates.map { it.name }}")
        } else {
            log(tagName, "AaUiHook: no static Intent start method on CarSystemUiControllerService")
        }
        return picked
    }

    private fun aaDisplayLaunchIntent(): Intent = Intent().apply {
        component = ComponentName(BuildConfig.APPLICATION_ID, AaActivityService::class.java.name)
        putExtra("android.intent.extra.PACKAGE_NAME", BuildConfig.APPLICATION_ID)
    }

    private fun registerSplitStateReceiver() {
        if (mSplitStateReceiver != null) return
        val ctx = InitFields.appContext
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != AABroadcastConst.ACTION_SPLIT_STATE_CHANGED) return
                if (!intent.hasExtra(AABroadcastConst.EXTRA_FULLSCREEN_PANE)) return
                mSplitStateSeen = true
                mCachedFullscreenPane = intent.getIntExtra(
                    AABroadcastConst.EXTRA_FULLSCREEN_PANE,
                    SplitPane.FULLSCREEN_NONE
                )
            }
        }
        try {
            ctx.registerReceiver(
                receiver,
                IntentFilter(AABroadcastConst.ACTION_SPLIT_STATE_CHANGED),
                Context.RECEIVER_EXPORTED
            )
            mSplitStateReceiver = receiver
            logDebug(tagName, "AaUiHook: registered SPLIT_STATE_CHANGED for rail fullscreen cache")
            mFacetEnsureHandler.post {
                if (mSplitStateSeen) return@post
                mCachedFullscreenPane = try {
                    CoreManager.splitFullscreenPane
                } catch (_: Throwable) {
                    mCachedFullscreenPane
                }
            }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: register SPLIT_STATE_CHANGED failed", e)
        }
    }

    private fun registerAaUiRailConsumeReceiver() {
        if (mRailConsumeReceiver != null) return
        val ctx = InitFields.appContext
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != AABroadcastConst.ACTION_AA_UI_RAIL_CONSUME) return
                mAaUiRailConsume = intent.getBooleanExtra(
                    AABroadcastConst.EXTRA_AA_UI_RAIL_CONSUME,
                    false,
                )
                logDebug(tagName, "AaUiHook: aaUiRailConsume=$mAaUiRailConsume")
            }
        }
        try {
            ctx.registerReceiver(
                receiver,
                IntentFilter(AABroadcastConst.ACTION_AA_UI_RAIL_CONSUME),
                Context.RECEIVER_EXPORTED
            )
            mRailConsumeReceiver = receiver
            logDebug(tagName, "AaUiHook: registered AA_UI_RAIL_CONSUME for picker rail routing")
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: register AA_UI_RAIL_CONSUME failed", e)
        }
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
            ctx.registerReceiver(
                receiver,
                IntentFilter(AABroadcastConst.ACTION_AA_DISPLAY_SHOWN),
                Context.RECEIVER_EXPORTED
            )
            mAutoOpenShownReceiver = receiver
            logDebug(tagName, "AaUiHook: registered AA_DISPLAY_SHOWN receiver for AutoOpen cancel")
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: register AA_DISPLAY_SHOWN receiver failed", e)
        }
    }

    /**
     * Mirror Coolwalk: when CarSystemUiControllerService's car-connected listener fires
     * (`xcs.a(qpm)` / "Car connected."), immediately try AutoOpen if a session is armed.
     * Listener class names are obfuscated; discover via the service field that references
     * the service instance.
     */
    private fun hookCarSystemUiConnectedKick() {
        if (mCarConnectedKickHooked) return
        val svcClass = try {
            loadClass("com.google.android.projection.gearhead.service.CarSystemUiControllerService")
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: AutoOpen connected-kick: SysUi service missing", e)
            return
        }
        try {
            val onCreate = svcClass.declaredMethods.firstOrNull {
                it.name == "onCreate" && it.parameterCount == 0
            } ?: run {
                log(tagName, "AaUiHook: AutoOpen connected-kick: onCreate missing")
                return
            }
            onCreate.isAccessible = true
            onCreate.hookAfter { param ->
                val service = param.thisObject ?: return@hookAfter
                installCarConnectedKickOnService(service, svcClass)
            }
            mCarConnectedKickHooked = true
            logDebug(tagName, "AaUiHook: AutoOpen connected-kick hooked SysUi onCreate")
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: AutoOpen connected-kick hook failed", e)
        }
    }

    private fun installCarConnectedKickOnService(service: Any, svcClass: Class<*>) {
        if (mCarConnectedListenerHooked) return
        for (field in svcClass.declaredFields) {
            if (Modifier.isStatic(field.modifiers)) continue
            field.isAccessible = true
            val listener = try {
                field.get(service)
            } catch (_: Throwable) {
                null
            } ?: continue
            if (listener === service) continue
            if (listener is Intent || listener is List<*> || listener is android.os.IBinder) continue
            val lClass = listener.javaClass
            val refsService = lClass.declaredFields.any { f ->
                f.isAccessible = true
                try {
                    f.get(listener) === service
                } catch (_: Throwable) {
                    false
                }
            }
            if (!refsService) continue
            var hooked = 0
            for (m in lClass.declaredMethods) {
                if (Modifier.isStatic(m.modifiers)) continue
                if (m.parameterCount != 1) continue
                if (m.returnType != Void.TYPE && m.returnType != Void::class.java) continue
                val p0 = m.parameterTypes[0]
                if (p0.isPrimitive || p0 == Intent::class.java) continue
                try {
                    m.isAccessible = true
                    m.hookAfter {
                        onCarClientConnectedSignal("sysui.${m.name}")
                    }
                    hooked++
                } catch (_: Throwable) {
                }
            }
            if (hooked > 0) {
                mCarConnectedListenerHooked = true
                logDebug(
                    tagName,
                    "AaUiHook: AutoOpen connected-kick hooked $hooked method(s) on ${lClass.name}",
                )
                return
            }
        }
        logDebug(tagName, "AaUiHook: AutoOpen connected-kick: no SysUi listener field matched")
    }

    private fun onCarClientConnectedSignal(reason: String) {
        if (!mAutoOpenArmed || mAaDisplayShownThisSession) return
        logDebug(tagName, "AaUiHook: AutoOpen connected kick ($reason)")
        mFacetEnsureHandler.post {
            tryAutoOpenAaDisplay(-1L)
        }
    }

    private fun markAaDisplayShown(reason: String) {
        if (mAaDisplayShownThisSession) return
        mAaDisplayShownThisSession = true
        mAutoOpenArmed = false
        mFacetEnsureHandler.removeCallbacksAndMessages(AUTO_OPEN_TOKEN)
        logDebug(tagName, "AaUiHook: AutoOpen stop retries ($reason)")
    }

    private fun scheduleAutoOpenIfNeeded(reason: String = "unknown") {
        if (startMethod == null) {
            log(tagName, "AaUiHook: AutoOpen skip ($reason): startMethod null")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (mAutoOpenSessionAtMs != 0L && now - mAutoOpenSessionAtMs < AUTO_OPEN_REARM_GAP_MS) {
            return
        }
        mAutoOpenSessionAtMs = now
        mAaDisplayShownThisSession = false
        mAutoOpenArmed = true
        mFacetEnsureHandler.removeCallbacksAndMessages(AUTO_OPEN_TOKEN)
        logDebug(
            tagName,
            "AaUiHook: arm AutoOpen retries ($reason) delays=${AUTO_OPEN_DELAYS_MS.contentToString()}",
        )
        for (delayMs in AUTO_OPEN_DELAYS_MS) {
            mFacetEnsureHandler.postAtTime(
                { tryAutoOpenAaDisplay(delayMs) },
                AUTO_OPEN_TOKEN,
                now + delayMs,
            )
        }
    }

    private fun tryAutoOpenAaDisplay(delayMs: Long) {
        // Only stop on real resume (AA_DISPLAY_SHOWN). CarSystemUiControllerService
        // may "succeed" without opening when CAMS is still null, and throws when the
        // Car API client is not connected yet — keep retries / connected kick armed.
        if (mAaDisplayShownThisSession) {
            markAaDisplayShown("flag")
            return
        }
        if (!mAutoOpenArmed) return
        val method = startMethod ?: return
        val label = if (delayMs < 0L) "connected" else "${delayMs}ms"
        try {
            method.invoke(null, aaDisplayLaunchIntent())
            logDebug(tagName, "AaUiHook: AutoOpen invoke at $label")
        } catch (e: InvocationTargetException) {
            val cause = e.cause ?: e
            if (cause is IllegalStateException) {
                // Typical: Car client not connected (wmu.be(khh.r())).
                logDebug(tagName, "AaUiHook: AutoOpen not-ready at $label: ${cause.message}")
            } else {
                log(tagName, "AaUiHook: AutoOpen invoke failed at $label", cause)
            }
        } catch (e: IllegalStateException) {
            logDebug(tagName, "AaUiHook: AutoOpen not-ready at $label: ${e.message}")
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: AutoOpen invoke failed at $label", e)
        }
    }

    private fun bundleParcelableRect(bundle: Bundle, key: String): Rect? {
        return bundle.getParcelable(key, Rect::class.java)
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
                scheduleReclaimLeftGutter(resultViewGroup)
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
                    // Dual-VD: GhostActivity / rail-host has the leftover ~107px column but
                    // no facet chrome ids (those live on GhFacetBar). Reclaim every attach.
                    reclaimLeftGutter(root)
                    reclaimAllWindowGutters("windowAttach")
                    if (!hasInjectedFacet(root) && containsFacetChrome(root)) {
                        scheduleEnsureFacetBar("windowAttach")
                    }
                }
            }
            logDebug(tagName, "AaUiHook: hooked WindowManagerGlobal.addView for facet ensure")
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: hook WindowManagerGlobal.addView failed", e)
        }
    }

    private fun scheduleEnsureFacetBar(reason: String) {
        mFacetEnsureHandler.removeCallbacksAndMessages(FACET_ENSURE_TOKEN)
        val now = SystemClock.uptimeMillis()
        mFacetEnsureDeadlineMs = now + FACET_ENSURE_WINDOW_MS
        // One immediate kick; continue via a single poll chain until success or deadline.
        mFacetEnsureHandler.postAtTime(
            { ensureFacetBarInjected(reason) },
            FACET_ENSURE_TOKEN,
            now
        )
    }

    private fun ensureFacetBarInjected(reason: String) {
        if (!canHookFacetBar || mInjectingFacetBar) return
        try {
            val roots = collectWindowRootViews()
            var attempted = 0
            for (root in roots) {
                if (!containsFacetChrome(root)) continue
                if (hasInjectedFacet(root)) continue
                if (tryInjectIntoFacetColumn(root, reason)) attempted++
            }
            if (attempted > 0) {
                logDebug(tagName, "AaUiHook: ensure facet injected [$reason] count=$attempted")
            }
            // Always sweep every window. Stopping at "FacetBar already tagged" was why the
            // left nav gutter kept coming back: chrome lives on GhFacetBar, the ~107px
            // column lives on GhostActivity and is rebuilt after inject.
            reclaimAllWindowGutters(reason)
            val now = SystemClock.uptimeMillis()
            if (now < mFacetEnsureDeadlineMs) {
                mFacetEnsureHandler.postAtTime(
                    { ensureFacetBarInjected("$reason-poll") },
                    FACET_ENSURE_TOKEN,
                    now + FACET_ENSURE_POLL_MS
                )
            } else if (!roots.any { hasInjectedFacet(it) } &&
                (reason.endsWith("-poll") || reason.indexOf('-') < 0)
            ) {
                log(
                    tagName,
                    "AaUiHook: ensure facet still missing [$reason] roots=${roots.size} " +
                        "chrome=${roots.count { containsFacetChrome(it) }}"
                )
            }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: ensure facet failed [$reason]", e)
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
        // Never removeView / reparent the stock facet tree. Coolwalk hosts
        // RailStatusBarFragment in R.id.status_bar; detaching that subtree makes
        // FragmentManager crash with "No view found for id …/status_bar".
        collapseFacetChromeInPlace(resultViewGroup, matchReason)
    }

    private fun injectAaFacetBarInPlace(facetHost: ViewGroup, reason: String) {
        collapseFacetChromeInPlace(facetHost, "inplace:$reason")
    }

    /**
     * Hide/zero the rail column in place and arm AutoOpen. Keeps [resIdStatusBarId]
     * attached so AA fragment transactions stay valid.
     *
     * Reclaim immediately: [applyZeroWidthGone] refuses DecorView / window roots, so
     * AutoOpen is no longer gated on a multi-second deferral.
     */
    private fun collapseFacetChromeInPlace(facetHost: ViewGroup, reason: String) {
        facetHost.tag = facetBarInjectedTag
        scheduleAutoOpenIfNeeded("facet:$reason")
        logDebug(tagName, "AaUiHook: collapse facet rail ($reason)")
        reclaimRailSpace(facetHost)
        reclaimAllWindowGutters("collapse:$reason")
        scheduleEnsureFacetBar("collapse:$reason")
    }

    /**
     * Collapse the rail/facet column AND its thin wrappers, then expand content siblings
     * so the black gutter does not remain after chrome is hidden.
     * One immediate pass + one layout-settle follow-up (no multi-second timer chain).
     */
    private fun reclaimRailSpace(rail: View) {
        applyZeroWidthGone(rail)
        collapseThinRailChainAndExpandContent(rail)
        mReclaimFollowUps.remove(rail)?.let { rail.removeCallbacks(it) }
        val settle = Runnable {
            mReclaimFollowUps.remove(rail)
            if (!rail.isAttachedToWindow) return@Runnable
            collapseThinRailChainAndExpandContent(rail)
        }
        mReclaimFollowUps[rail] = settle
        rail.post(settle)
    }

    /** One layout-settle left-gutter reclaim; cancels prior posts for the same root. */
    private fun scheduleReclaimLeftGutter(root: ViewGroup) {
        mReclaimFollowUps.remove(root)?.let { root.removeCallbacks(it) }
        val settle = Runnable {
            mReclaimFollowUps.remove(root)
            if (root.isAttachedToWindow) reclaimLeftGutter(root)
        }
        mReclaimFollowUps[root] = settle
        root.post(settle)
    }

    /** Max thin-rail width from live LayoutInfo / root size (no fixed phone/HU pixels). */
    private fun maxSideRailPx(view: View): Int {
        val fullW = layoutWidthPx().takeIf { it > 0 }
            ?: view.rootView?.width?.takeIf { it > 0 }
            ?: view.width.takeIf { it > 0 }
            ?: view.resources.displayMetrics.widthPixels
        return railPxRange(fullW.coerceAtLeast(1)).last
    }
    private fun applyZeroWidthGone(view: View, sourceLp: ViewGroup.LayoutParams? = null) {
        // Never GONE DecorView / window roots — that kills FacetBar presentation and
        // CarSystemUiControllerService can no longer AutoOpen OEM apps.
        if (isWindowDecorOrRoot(view)) {
            logDebug(tagName, "AaUiHook: skip zero-width on ${view.javaClass.simpleName}")
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
        // Inflate-time facet hosts are often unattached FrameLayouts (parent == null) that
        // share rootView with themselves — must not treat them as window roots or reconnect
        // leaves the ~107px left gutter ("导航栏黑条").
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
                    // Stop before DecorView / window root (breaks CarSystemUi AutoOpen).
                    if (isWindowDecorOrRoot(parent)) break
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

    /** Collapse leftover left gutters on every live window in this process. */
    private fun reclaimAllWindowGutters(reason: String) {
        val roots = collectWindowRootViews()
        var n = 0
        for (root in roots) {
            if (!root.isAttachedToWindow) continue
            reclaimLeftGutter(root)
            n++
        }
        if (n > 0) {
            logDebug(tagName, "AaUiHook: reclaim all gutters [$reason] roots=$n")
        }
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
            // Fallback: leftmost thin child of any horizontal LinearLayout / ConstraintLayout.
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
                if (vg is ConstraintLayout && vg.childCount >= 2) {
                    for (i in 0 until vg.childCount) {
                        val c = vg.getChildAt(i) ?: continue
                        if (!isThinSideRail(c, maxSidePx)) continue
                        if (c.left > 16 && c.x > 16f) continue
                        reclaimRailSpace(c)
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
