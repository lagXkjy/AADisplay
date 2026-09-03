package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.getIdByName
import com.github.kyuubiran.ezxhelper.utils.loadClass
import com.github.kyuubiran.ezxhelper.utils.staticMethod
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Shared mutable hook state for Coolwalk / facet chrome submodules in one gearhead process.
 */
class CoolwalkHookEnv {

    var layoutInfoConstructors: List<Constructor<*>> = emptyList()
    var startMethod: Method? = null
    @Volatile var hasVerticalRailField: Field? = null
    @Volatile var layoutResourceIdField: Field? = null
    var contentBoundsMethods: List<Method> = emptyList()
    var facetBarSurfaceMethods: List<Method> = emptyList()
    var huTouchDispatchMethod: Method? = null
    /** Coolwalk compositor window bounds class (`{blX=` toString); may be null if DexKit miss. */
    var projectionBoundsClassName: String? = null

    @Volatile var mRailHostDownTime = 0L
    @Volatile var mHuRailGesture = false
    @Volatile var mHuPeelGesture = false
    @Volatile var mAaUiRailConsume = false
    @Volatile var mCachedFullscreenPane: Int = SplitPane.FULLSCREEN_NONE
    @Volatile var mSplitStateSeen = false
    val mRailMoveLock = Any()
    var mPendingRailMove: android.view.MotionEvent? = null
    @Volatile var mRailMoveGeneration = 0

    var mSplitStateReceiver: android.content.BroadcastReceiver? = null
    var mRailConsumeReceiver: android.content.BroadcastReceiver? = null

    var resLayoutLeftResourceId: Int = 0
    var resLayoutRightResourceId: Int = 0
    var resIdStatusBarId: Int = 0
    var resIdLauncherAndDashboardIconContainerId: Int = 0
    var resIdLauncherAndDashboardIconId: Int = 0
    val facetBarLayoutIds = mutableSetOf<Int>()
    val railHostLayoutIds = mutableSetOf<Int>()
    val railWidthDimenIds = mutableSetOf<Int>()
    var canHookFacetBar: Boolean = false
    var mInjectingFacetBar: Boolean = false
    val facetBarInjectedTag = Any()
    val mFacetEnsureHandler = Handler(Looper.getMainLooper())
    var mFacetEnsureDeadlineMs = 0L
    val mReclaimFollowUps = java.util.WeakHashMap<android.view.View, Runnable>()

    var mLayoutWidthDp: Int = 0
    var mLayoutHeightDp: Int = 0
    @Volatile var mLoggedDashboardStarve = false
    @Volatile var mLastProjectionConfigRewrite: String? = null

    var mAutoOpenSessionAtMs = 0L
    @Volatile var mAaDisplayShownThisSession = false
    @Volatile var mAutoOpenArmed = false
    var mAutoOpenShownReceiver: android.content.BroadcastReceiver? = null
    var mFullBleedRelaunchReceiver: android.content.BroadcastReceiver? = null
    var mCarConnectedKickHooked = false
    var mCarConnectedListenerHooked = false

    val mRailMoveFrameCallback = android.view.Choreographer.FrameCallback {
        AaCoolwalkHuTouchHook.flushPendingRailMove(this)
    }
    val mRailMovePostToFrame = Runnable {
        val choreographer = android.view.Choreographer.getInstance()
        choreographer.removeFrameCallback(mRailMoveFrameCallback)
        choreographer.postFrameCallback(mRailMoveFrameCallback)
    }

    fun layoutWidthPx(): Int =
        kotlin.math.max(
            mLayoutWidthDp,
            kotlin.math.max(CoolwalkRailCoordinator.layoutWidthPx(), CoolwalkRailCoordinator.observedFullHuWidthPx()),
        ).takeIf { it > 0 } ?: 0

    fun layoutHeightPx(): Int =
        kotlin.math.max(
            mLayoutHeightDp,
            CoolwalkRailCoordinator.layoutHeightPx(),
        ).takeIf { it > 0 } ?: 0

    fun logProjectionConfigRewriteOnce(message: String) {
        if (mLastProjectionConfigRewrite == message) return
        mLastProjectionConfigRewrite = message
        logDebug(TAG, message)
    }

    fun loadProjectionResources() {
        val pkg = InitFields.appContext.packageName
        val res = InitFields.appContext.resources
        fun layoutId(name: String): Int = res.getIdentifier(name, "layout", pkg).also { id ->
            if (id != 0) facetBarLayoutIds.add(id)
        }

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
            log(TAG, "AaUiHook: LHD canonical layout missing; still forcing hasVerticalRail=true")
        }
        canHookFacetBar =
            facetBarLayoutIds.isNotEmpty() &&
            resIdStatusBarId != 0 &&
            resIdLauncherAndDashboardIconContainerId != 0 &&
            resIdLauncherAndDashboardIconId != 0
        if (!canHookFacetBar) {
            log(
                TAG,
                "AaUiHook: skip facet-bar override, missing resources: facetIds=$facetBarLayoutIds, " +
                    "status=$resIdStatusBarId, launcherContainer=$resIdLauncherAndDashboardIconContainerId, " +
                    "launcherIcon=$resIdLauncherAndDashboardIconId",
            )
        } else {
            logDebug(TAG, "AaUiHook: facet layout ids=$facetBarLayoutIds railHosts=$railHostLayoutIds")
        }
        logDebug(TAG, "AaUiHook: rail width dimens=$railWidthDimenIds")
    }

    fun resolveLayoutInfoConstructors(className: String): List<Constructor<*>> {
        val clazz = loadClass(className)
        val matched = clazz.declaredConstructors.filter { ctor ->
            val p = ctor.parameterTypes
            p.size >= 10 &&
                p[0] == Int::class.javaPrimitiveType &&
                p[1] == Int::class.javaPrimitiveType &&
                p[2] == Int::class.javaPrimitiveType &&
                p[4] == Boolean::class.javaPrimitiveType &&
                p[5] == Boolean::class.javaPrimitiveType &&
                p[7] == Boolean::class.javaPrimitiveType &&
                p[8] == Boolean::class.javaPrimitiveType &&
                p[9] == Boolean::class.javaPrimitiveType &&
                !p[6].isPrimitive &&
                (p[3] == Int::class.javaPrimitiveType || p[3].isEnum)
        }
        if (matched.isEmpty()) {
            throw NoSuchMethodException("AaUiHook: not found compatible LayoutInfo constructor for $className")
        }
        for (ctor in matched) {
            ctor.isAccessible = true
        }
        val primary = matched.first()
        log(
            TAG,
            "AaUiHook: LayoutInfo constructor selected, paramCount=${primary.parameterCount}, " +
                "layoutTypeArg=${primary.parameterTypes[3].name}, matches=${matched.size}",
        )
        return matched
    }

    fun resolveCarStartActivityMethod(): Method? {
        val clazz = try {
            loadClass("com.google.android.projection.gearhead.service.CarSystemUiControllerService")
        } catch (e: Throwable) {
            log(TAG, "AaUiHook: CarSystemUiControllerService missing", e)
            return null
        }
        try {
            return clazz.staticMethod("a", null, argTypes(Intent::class.java)).also {
                logDebug(TAG, "AaUiHook: startMethod=a(Intent)")
            }
        } catch (e: Throwable) {
            log(TAG, "AaUiHook: CarSystemUiControllerService.a missing, scanning static Intent methods", e)
        }
        val candidates = clazz.declaredMethods.filter { m ->
            Modifier.isStatic(m.modifiers) &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == Intent::class.java &&
                (m.returnType == Void.TYPE || m.returnType == Void::class.java)
        }
        val picked = candidates.firstOrNull()
        if (picked != null) {
            picked.isAccessible = true
            logDebug(
                TAG,
                "AaUiHook: startMethod fallback=${picked.name}(Intent) candidates=${candidates.map { it.name }}",
            )
        } else {
            log(TAG, "AaUiHook: no static Intent start method on CarSystemUiControllerService")
        }
        return picked
    }

    companion object {
        const val TAG = "AAD_AaUiHook"

        val RAIL_WIDTH_DIMEN_NAMES = arrayOf(
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

        const val FACET_ENSURE_WINDOW_MS = 2_000L
        const val FACET_ENSURE_POLL_MS = 250L
        val FACET_ENSURE_TOKEN = Any()
        val AUTO_OPEN_TOKEN = Any()
        val FULL_BLEED_RELAUNCH_TOKEN = Any()
        const val AUTO_OPEN_REARM_GAP_MS = 24_000L

        val PROJECTION_CONFIG_KEYS = setOf(
            "content_bounds", "contentBounds", "content_insets", "contentInsets",
        )

        val AUTO_OPEN_DELAYS_MS = longArrayOf(
            0L, 1500L, 5000L, 12_000L, 24_000L,
        )
    }
}
