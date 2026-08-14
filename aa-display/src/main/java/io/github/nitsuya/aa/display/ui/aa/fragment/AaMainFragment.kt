package io.github.nitsuya.aa.display.ui.aa.fragment

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.InputDeviceCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import io.github.duzhaokun123.template.bases.BaseFragment
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.databinding.FragmentAaMainBinding
import io.github.nitsuya.aa.display.ui.aa.AaDisplayActivityKt
import io.github.nitsuya.aa.display.ui.aa.split.SplitAppPickerController
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.util.LastSplitStore
import io.github.nitsuya.aa.display.util.rewriteMotionEvent
import io.github.nitsuya.aa.display.xposed.IVirtualDisplayCreatedListener
import io.github.duzhaokun123.template.utils.runMain
import kotlin.math.abs
class AaMainFragment : BaseFragment<FragmentAaMainBinding>(FragmentAaMainBinding::class.java) {
    companion object {
        private const val TAG = "AADisplay_AaMainFragment"
        private const val SETTLE_MID_MS = 400L
        private const val SETTLE_LATE_MS = 900L
    }

    private var displayId: Int = Display.INVALID_DISPLAY
    private var repairDownTimePrimary = Long.MIN_VALUE
    private var repairDownTimeSecondary = Long.MIN_VALUE
    private var isDisplayCreateRequested = false
    private var isControlReceiverRegistered = false
    private var primarySurface: Surface? = null
    private var secondarySurface: Surface? = null
    private var splitRatio: Float = SplitPane.DEFAULT_RATIO
    private lateinit var appPicker: SplitAppPickerController
    private val paneHasApp = booleanArrayOf(false, false)

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                AABroadcastConst.ACTION_OPEN_SPLIT_PICKER -> {
                    val pane = intent.getIntExtra(
                        AABroadcastConst.EXTRA_PANE,
                        SplitPane.PRIMARY
                    )
                    // system_server already decided this pane needs a pick (restore miss / vacant).
                    // Do not re-check getPanePackage — stale mPanePackages used to block the picker.
                    if (SplitPane.isValid(pane)) {
                        paneHasApp[pane] = false
                        updateEmptyOverlays()
                        appPicker.show(pane)
                    }
                }
                AABroadcastConst.ACTION_SPLIT_STATE_CHANGED -> {
                    // Occupancy (+ optional fullscreen) — never re-apply ratio from broadcasts.
                    val primary = intent.getStringExtra(AABroadcastConst.EXTRA_PRIMARY_PACKAGE)
                    val secondary = intent.getStringExtra(AABroadcastConst.EXTRA_SECONDARY_PACKAGE)
                    if (primary != null || secondary != null) {
                        applyOccupancyFromPackages(primary.orEmpty(), secondary.orEmpty())
                    } else {
                        syncPaneOccupancyFromService()
                    }
                    if (intent.hasExtra(AABroadcastConst.EXTRA_FULLSCREEN_PANE) && !dividerDragging) {
                        val fs = intent.getIntExtra(
                            AABroadcastConst.EXTRA_FULLSCREEN_PANE,
                            SplitPane.FULLSCREEN_NONE
                        )
                        applyFullscreenFromRemote(fs)
                    }
                }
                AABroadcastConst.ACTION_STEERING_WHEEL_CONTROL -> {
                    val action = intent.getIntExtra(AABroadcastConst.EXTRA_ACTION, 0)
                    // EXTRA_TYPE: 0 = click (AaBtnEventHook default), 1 = long-press.
                    when (intent.getIntExtra(AABroadcastConst.EXTRA_TYPE, 0)) {
                        0 -> {
                            when (action) {
                                KeyEvent.KEYCODE_SEARCH,
                                KeyEvent.KEYCODE_MEDIA_NEXT,
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                                KeyEvent.KEYCODE_HEADSETHOOK,
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                KeyEvent.KEYCODE_MEDIA_STOP,
                                KeyEvent.KEYCODE_MEDIA_REWIND,
                                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                                KeyEvent.KEYCODE_MUTE,
                                KeyEvent.KEYCODE_MEDIA_PLAY,
                                KeyEvent.KEYCODE_MEDIA_PAUSE,
                                KeyEvent.KEYCODE_MEDIA_RECORD -> CoreApi.pressKey(action)
                            }
                        }
                        1 -> {
                            if (action == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                                CoreApi.moveSecondTaskToFront()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun initViews() {
        Log.d(TAG, "initViews")
        appPicker = SplitAppPickerController(baseBinding).also {
            it.onAppPicked = { pane, _ ->
                paneHasApp[pane] = true
                updateEmptyOverlays()
                // Confirm with system_server after launch settles.
                scheduleOccupancySync(400L)
            }
        }

        LastSplitStore.load(requireContext().contentResolver)?.let { snap ->
            splitRatio = SplitPane.clampRatio(snap.primaryRatio)
            ratioBeforeFullscreen = splitRatio
            fullscreenPane = snap.fullscreenPane
        }
        if (SplitPane.isFullscreenPane(fullscreenPane)) {
            applyFullscreenLayout(fullscreenPane)
        } else {
            applySplitLayoutWeights(splitRatio)
        }
        setupDivider()
        setupPaneSurfaces()
        setupEmptyPaneClicks()

        baseBinding.splitContainer.doOnLayout {
            requestDisplay("layout")
        }
    }

    override fun onResume() {
        super.onResume()
        baseBinding.splitContainer.post {
            if (displayId == Display.INVALID_DISPLAY) {
                isDisplayCreateRequested = false
            }
            requestDisplay("resume")
        }
    }

    override fun onDestroy() {
        try {
            baseBinding.root.removeCallbacks(settleImmediate)
            baseBinding.root.removeCallbacks(settleMid)
            baseBinding.root.removeCallbacks(settleLate)
            baseBinding.root.removeCallbacks(afterDividerSettle)
            baseBinding.root.removeCallbacks(afterSwapSettle)
            baseBinding.root.removeCallbacks(afterOccupancySync)
        } catch (_: Throwable) {
        }
        // Drop coalesced MOVE before tearing down VDs — never inject after destroy.
        cancelPendingPaneTouches()
        super.onDestroy()
        Log.d(TAG, "onDestroy: displayId=$displayId")
        clearDisplaySurfaces("destroy")
        CoreApi.onDestroyDisplay()
        displayId = Display.INVALID_DISPLAY
        isDisplayCreateRequested = false
        lastCreateWidth = 0
        lastCreateHeight = 0
        lastCreateDpi = 0
        appliedRatio = Float.NaN
        appliedSideBySide = null
        appliedFullscreenPane = Int.MIN_VALUE
        if (isControlReceiverRegistered) {
            tryOrNull { context?.unregisterReceiver(broadcastReceiver) }
            isControlReceiverRegistered = false
        }
    }

    private var appliedRatio: Float = Float.NaN
    private var appliedSideBySide: Boolean? = null
    private var appliedFullscreenPane: Int = Int.MIN_VALUE
    private var fullscreenPane: Int = SplitPane.FULLSCREEN_NONE
    /** Split ratio remembered locally when entering fullscreen (IPC exit may race). */
    private var ratioBeforeFullscreen: Float = SplitPane.DEFAULT_RATIO
    /** Settled ratio at drag start — used when enter-FS so preview extremes are not saved. */
    private var ratioAtDragStart: Float = SplitPane.DEFAULT_RATIO
    private var lastCreateWidth = 0
    private var lastCreateHeight = 0
    private var lastCreateDpi = 0
    private var dividerDragging = false
    /** Soft-reconnect settle: cancel prior pipeline before arming a new one. */
    private var settleExpectCreate = false
    private val settleImmediate = Runnable { runConnectSettleStep(0) }
    private val settleMid = Runnable { runConnectSettleStep(1) }
    private val settleLate = Runnable { runConnectSettleStep(2) }
    private val afterDividerSettle = Runnable { syncPaneOccupancyFromService() }
    private val afterOccupancySync = Runnable { syncPaneOccupancyFromService() }
    private val afterSwapSettle = Runnable {
        if (!isAdded || view == null || dividerDragging) return@Runnable
        val fs = tryOrNull { CoreApi.splitFullscreenPane } ?: SplitPane.FULLSCREEN_NONE
        applyFullscreenFromRemote(fs)
        if (!SplitPane.isFullscreenPane(fs)) {
            val ratio = tryOrNull { CoreApi.splitRatio }?.takeIf { it > 0f } ?: return@Runnable
            val clamped = SplitPane.clampRatio(ratio)
            splitRatio = clamped
            applySplitLayoutWeights(clamped)
            baseBinding.splitDivider.setRatio(clamped)
        }
        syncPaneOccupancyFromService()
    }

    /** Per-pane MOVE coalesce — instance fields so [onDestroy] can cancel them. */
    private var primaryPendingMove: MotionEvent? = null
    private var secondaryPendingMove: MotionEvent? = null
    private val primaryFlushMove = Runnable { flushPendingMove(SplitPane.PRIMARY) }
    private val secondaryFlushMove = Runnable { flushPendingMove(SplitPane.SECONDARY) }

    private fun setupDivider() {
        baseBinding.splitDivider.apply {
            sideBySide = resources.configuration.orientation !=
                android.content.res.Configuration.ORIENTATION_PORTRAIT
            setRatio(splitRatio)
            setFullscreenPane(fullscreenPane)
            onRatioChanged = { ratio ->
                // Drag: update layout only. Live CoreApi.setSplitRatio →
                // VirtualDisplay.resize + freezeDisplayRotation costs 600–900ms/call on
                // system_server main and visibly jitters both panes.
                if (!dividerDragging) {
                    ratioAtDragStart = SplitPane.clampRatio(
                        if (appliedRatio.isNaN()) splitRatio else appliedRatio
                    )
                }
                dividerDragging = true
                splitRatio = ratio
                applySplitLayoutWeights(
                    ratio,
                    force = true,
                    peelPreview = SplitPane.isFullscreenPane(fullscreenPane),
                )
            }
            onRatioSettled = { ratio ->
                splitRatio = ratio
                applySplitLayoutWeights(ratio, force = true)
                CoreApi.setSplitRatio(ratio)
                dividerDragging = false
                baseBinding.root.removeCallbacks(afterDividerSettle)
                baseBinding.root.postDelayed(afterDividerSettle, 300L)
            }
            onFullscreenEnter = { pane ->
                dividerDragging = false
                ratioBeforeFullscreen = ratioAtDragStart
                enterFullscreen(pane)
            }
            onFullscreenExit = {
                dividerDragging = false
                exitFullscreen()
            }
            onPeelCancelled = {
                dividerDragging = false
                if (SplitPane.isFullscreenPane(fullscreenPane)) {
                    applyFullscreenLayout(fullscreenPane)
                }
            }
            onStackClick = {
                runMain {
                    AaDisplayActivityKt.showRecentTask(this@AaMainFragment.parentFragmentManager)
                }
            }
            onSwapClick = {
                if (SplitPane.isFullscreenPane(fullscreenPane)) {
                    // Tap peel: flip which pane is visible (no task move).
                    val other = if (fullscreenPane == SplitPane.PRIMARY) {
                        SplitPane.SECONDARY
                    } else {
                        SplitPane.PRIMARY
                    }
                    enterFullscreen(other)
                } else {
                    CoreApi.swapSplitPanes()
                    baseBinding.root.removeCallbacks(afterSwapSettle)
                    baseBinding.root.postDelayed(afterSwapSettle, 280L)
                }
            }
        }
    }

    private fun enterFullscreen(pane: Int) {
        if (!SplitPane.isFullscreenPane(pane)) return
        fullscreenPane = pane
        CoreApi.setSplitFullscreen(pane)
        applyFullscreenLayout(pane)
        updateEmptyOverlays()
        baseBinding.root.removeCallbacks(afterDividerSettle)
        baseBinding.root.postDelayed(afterDividerSettle, 300L)
    }

    private fun exitFullscreen() {
        fullscreenPane = SplitPane.FULLSCREEN_NONE
        CoreApi.setSplitFullscreen(SplitPane.FULLSCREEN_NONE)
        splitRatio = SplitPane.clampRatio(ratioBeforeFullscreen)
        applySplitLayoutWeights(splitRatio, force = true)
        baseBinding.splitDivider.setFullscreenPane(SplitPane.FULLSCREEN_NONE)
        baseBinding.splitDivider.setRatio(splitRatio)
        updateEmptyOverlays()
        baseBinding.root.removeCallbacks(afterDividerSettle)
        baseBinding.root.postDelayed(afterDividerSettle, 300L)
    }

    private fun applyFullscreenFromRemote(pane: Int) {
        val next = if (SplitPane.isFullscreenPane(pane)) pane else SplitPane.FULLSCREEN_NONE
        if (next == fullscreenPane &&
            (next == SplitPane.FULLSCREEN_NONE || appliedFullscreenPane == next)
        ) {
            return
        }
        fullscreenPane = next
        if (SplitPane.isFullscreenPane(next)) {
            applyFullscreenLayout(next)
        } else {
            val ratio = tryOrNull { CoreApi.splitRatio }?.takeIf { it > 0f } ?: splitRatio
            splitRatio = SplitPane.clampRatio(ratio)
            applySplitLayoutWeights(splitRatio, force = true)
            baseBinding.splitDivider.setFullscreenPane(SplitPane.FULLSCREEN_NONE)
            baseBinding.splitDivider.setRatio(splitRatio)
        }
        updateEmptyOverlays()
    }

    private fun applyFullscreenLayout(pane: Int) {
        val sideBySide = baseBinding.splitContainer.width >= baseBinding.splitContainer.height
            || baseBinding.splitContainer.width == 0
        appliedFullscreenPane = pane
        appliedRatio = Float.NaN
        appliedSideBySide = sideBySide
        baseBinding.splitContainer.clipChildren = false
        baseBinding.splitDivider.sideBySide = sideBySide
        baseBinding.splitDivider.setFullscreenPane(pane)

        val primaryLp = (baseBinding.panePrimary.layoutParams as FrameLayout.LayoutParams).apply {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            height = FrameLayout.LayoutParams.MATCH_PARENT
            marginStart = 0
            topMargin = 0
            gravity = Gravity.TOP or Gravity.START
        }
        val secondaryLp = (baseBinding.paneSecondary.layoutParams as FrameLayout.LayoutParams).apply {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            height = FrameLayout.LayoutParams.MATCH_PARENT
            marginStart = 0
            topMargin = 0
            gravity = Gravity.TOP or Gravity.START
        }
        baseBinding.panePrimary.layoutParams = primaryLp
        baseBinding.paneSecondary.layoutParams = secondaryLp

        val front = if (pane == SplitPane.PRIMARY) baseBinding.panePrimary else baseBinding.paneSecondary
        val back = if (pane == SplitPane.PRIMARY) baseBinding.paneSecondary else baseBinding.panePrimary
        back.elevation = 0f
        front.elevation = 2f
        // Keep both Surfaces full-size; touch routing uses TextureView enable flags.
        baseBinding.splitContainer.bringChildToFront(back)
        baseBinding.splitContainer.bringChildToFront(front)

        val dividerLp = (baseBinding.splitDivider.layoutParams as FrameLayout.LayoutParams)
        baseBinding.splitDivider.applyPeelLayoutParams(dividerLp, sideBySide)
        baseBinding.splitDivider.layoutParams = dividerLp
        baseBinding.splitContainer.bringChildToFront(baseBinding.splitDivider)
        baseBinding.splitDivider.invalidate()
        syncPaneTouchEnabled(pane)
    }

    private fun applySplitLayoutWeights(
        ratio: Float,
        force: Boolean = false,
        peelPreview: Boolean = false,
    ) {
        val sideBySide = baseBinding.splitContainer.width >= baseBinding.splitContainer.height
            || baseBinding.splitContainer.width == 0
        if (!force &&
            !peelPreview &&
            appliedFullscreenPane == SplitPane.FULLSCREEN_NONE &&
            appliedSideBySide == sideBySide &&
            !appliedRatio.isNaN() &&
            abs(appliedRatio - ratio) < 0.001f
        ) {
            return
        }
        appliedRatio = ratio
        appliedSideBySide = sideBySide
        if (!peelPreview) {
            appliedFullscreenPane = SplitPane.FULLSCREEN_NONE
            fullscreenPane = SplitPane.FULLSCREEN_NONE
            baseBinding.splitDivider.setFullscreenPane(SplitPane.FULLSCREEN_NONE)
        }
        baseBinding.splitContainer.clipChildren = false
        baseBinding.splitDivider.sideBySide = sideBySide

        val parentW = baseBinding.splitContainer.width.coerceAtLeast(0)
        val parentH = baseBinding.splitContainer.height.coerceAtLeast(0)
        val density = resources.displayMetrics.density
        val gap = (SplitPane.DIVIDER_DP * density).toInt().coerceAtLeast(1)
        val expand = (SplitPane.DIVIDER_TOUCH_EXPAND_DP * density).toInt().coerceAtLeast(0)
        val touchSpan = gap + 2 * expand
        // Allow peel/enter preview past clamp; keep ≥1px so layout stays stable.
        val visual = ratio.coerceIn(0.01f, 0.99f)

        val primaryLp = baseBinding.panePrimary.layoutParams as FrameLayout.LayoutParams
        val secondaryLp = baseBinding.paneSecondary.layoutParams as FrameLayout.LayoutParams
        if (sideBySide) {
            val usable = (parentW - gap).coerceAtLeast(2)
            val pw = if (parentW <= 0) 0 else (usable * visual).toInt().coerceAtLeast(1)
            val sw = if (parentW <= 0) 0 else (usable - pw).coerceAtLeast(1)
            primaryLp.width = if (parentW <= 0) FrameLayout.LayoutParams.MATCH_PARENT else pw
            primaryLp.height = FrameLayout.LayoutParams.MATCH_PARENT
            primaryLp.marginStart = 0
            primaryLp.topMargin = 0
            primaryLp.gravity = Gravity.START or Gravity.TOP
            secondaryLp.width = if (parentW <= 0) FrameLayout.LayoutParams.MATCH_PARENT else sw
            secondaryLp.height = FrameLayout.LayoutParams.MATCH_PARENT
            secondaryLp.marginStart = if (parentW <= 0) 0 else pw + gap
            secondaryLp.topMargin = 0
            secondaryLp.gravity = Gravity.START or Gravity.TOP

            val dividerLp = baseBinding.splitDivider.layoutParams as FrameLayout.LayoutParams
            dividerLp.width = touchSpan
            dividerLp.height = FrameLayout.LayoutParams.MATCH_PARENT
            dividerLp.marginStart = if (parentW <= 0) 0 else (pw - expand).coerceAtLeast(0)
            dividerLp.topMargin = 0
            dividerLp.gravity = Gravity.START or Gravity.TOP
            baseBinding.splitDivider.layoutParams = dividerLp
        } else {
            val usable = (parentH - gap).coerceAtLeast(2)
            val ph = if (parentH <= 0) 0 else (usable * visual).toInt().coerceAtLeast(1)
            val sh = if (parentH <= 0) 0 else (usable - ph).coerceAtLeast(1)
            primaryLp.width = FrameLayout.LayoutParams.MATCH_PARENT
            primaryLp.height = if (parentH <= 0) FrameLayout.LayoutParams.MATCH_PARENT else ph
            primaryLp.marginStart = 0
            primaryLp.topMargin = 0
            primaryLp.gravity = Gravity.START or Gravity.TOP
            secondaryLp.width = FrameLayout.LayoutParams.MATCH_PARENT
            secondaryLp.height = if (parentH <= 0) FrameLayout.LayoutParams.MATCH_PARENT else sh
            secondaryLp.marginStart = 0
            secondaryLp.topMargin = if (parentH <= 0) 0 else ph + gap
            secondaryLp.gravity = Gravity.START or Gravity.TOP

            val dividerLp = baseBinding.splitDivider.layoutParams as FrameLayout.LayoutParams
            dividerLp.width = FrameLayout.LayoutParams.MATCH_PARENT
            dividerLp.height = touchSpan
            dividerLp.marginStart = 0
            dividerLp.topMargin = if (parentH <= 0) 0 else (ph - expand).coerceAtLeast(0)
            dividerLp.gravity = Gravity.START or Gravity.TOP
            baseBinding.splitDivider.layoutParams = dividerLp
        }
        baseBinding.panePrimary.layoutParams = primaryLp
        baseBinding.paneSecondary.layoutParams = secondaryLp
        baseBinding.panePrimary.elevation = 0f
        baseBinding.paneSecondary.elevation = 0f
        baseBinding.splitContainer.bringChildToFront(baseBinding.panePrimary)
        baseBinding.splitContainer.bringChildToFront(baseBinding.paneSecondary)
        baseBinding.splitContainer.bringChildToFront(baseBinding.splitDivider)
        // Do not setRatio while dragging — it clamps lastRawRatio and blocks fullscreen enter.
        if (!dividerDragging) {
            baseBinding.splitDivider.setRatio(SplitPane.clampRatio(ratio))
        }
        baseBinding.splitDivider.invalidate()
        if (!peelPreview) {
            syncPaneTouchEnabled(SplitPane.FULLSCREEN_NONE)
        }
    }

    /** Behind fullscreen pane must not eat touches; both panes active in split. */
    private fun syncPaneTouchEnabled(visibleFullscreenPane: Int) {
        val primaryTv = baseBinding.tvDisplayPrimary
        val secondaryTv = baseBinding.tvDisplaySecondary
        when (visibleFullscreenPane) {
            SplitPane.PRIMARY -> {
                primaryTv.isEnabled = true
                secondaryTv.isEnabled = false
            }
            SplitPane.SECONDARY -> {
                primaryTv.isEnabled = false
                secondaryTv.isEnabled = true
            }
            else -> {
                primaryTv.isEnabled = true
                secondaryTv.isEnabled = true
            }
        }
    }

    private fun setupPaneSurfaces() {
        bindTexture(baseBinding.tvDisplayPrimary, SplitPane.PRIMARY) { surface ->
            primarySurface = surface
        }
        bindTexture(baseBinding.tvDisplaySecondary, SplitPane.SECONDARY) { surface ->
            secondarySurface = surface
        }
        setupTouchForwarding(baseBinding.tvDisplayPrimary, SplitPane.PRIMARY) { repairDownTimePrimary = it }
        setupTouchForwarding(baseBinding.tvDisplaySecondary, SplitPane.SECONDARY) { repairDownTimeSecondary = it }
    }

    private fun bindTexture(
        textureView: TextureView,
        pane: Int,
        onSurface: (Surface?) -> Unit,
    ) {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.d(TAG, "pane=$pane surface available ${width}x$height")
                val s = Surface(surface)
                onSurface(s)
                if (displayId != Display.INVALID_DISPLAY) {
                    CoreApi.setPaneSurface(pane, s)
                }
                requestDisplay("surface-$pane")
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                // Do NOT requestDisplay here: weight/ratio changes resize TextureViews and would
                // reconnect/resize VDs in a feedback loop (visible as constant jitter).
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                Log.d(TAG, "pane=$pane surface destroyed")
                CoreApi.setPaneSurface(pane, null)
                onSurface(null)
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun setupEmptyPaneClicks() {
        // Overlay is only visible while local occupancy says vacant — no Binder gate.
        baseBinding.tvEmptyPrimary.setOnClickListener { appPicker.show(SplitPane.PRIMARY) }
        baseBinding.tvEmptySecondary.setOnClickListener { appPicker.show(SplitPane.SECONDARY) }
        updateEmptyOverlays()
    }

    private fun updateEmptyOverlays() {
        if (SplitPane.isFullscreenPane(fullscreenPane)) {
            // Only the visible pane can show the vacant overlay.
            baseBinding.tvEmptyPrimary.isVisible =
                fullscreenPane == SplitPane.PRIMARY && !paneHasApp[SplitPane.PRIMARY]
            baseBinding.tvEmptySecondary.isVisible =
                fullscreenPane == SplitPane.SECONDARY && !paneHasApp[SplitPane.SECONDARY]
        } else {
            baseBinding.tvEmptyPrimary.isVisible = !paneHasApp[SplitPane.PRIMARY]
            baseBinding.tvEmptySecondary.isVisible = !paneHasApp[SplitPane.SECONDARY]
        }
    }

    /** Sync empty overlays from system_server; never mutates local divider ratio. */
    private fun syncPaneOccupancyFromService() {
        if (!isAdded || view == null || dividerDragging) return
        for (pane in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
            val pkg = tryOrNull { CoreApi.getPanePackage(pane) }?.trim().orEmpty()
            paneHasApp[pane] = pkg.isNotEmpty()
        }
        updateEmptyOverlays()
    }

    private fun applyOccupancyFromPackages(primaryPkg: String, secondaryPkg: String) {
        if (!isAdded || view == null || dividerDragging) return
        paneHasApp[SplitPane.PRIMARY] = primaryPkg.trim().isNotEmpty()
        paneHasApp[SplitPane.SECONDARY] = secondaryPkg.trim().isNotEmpty()
        updateEmptyOverlays()
    }

    /** Apply remote ratio / fullscreen only once after create/restore (not on every broadcast). */
    private fun applyRemoteRatioOnce() {
        if (!isAdded || view == null || dividerDragging) return
        val fs = tryOrNull { CoreApi.splitFullscreenPane } ?: SplitPane.FULLSCREEN_NONE
        if (SplitPane.isFullscreenPane(fs)) {
            applyFullscreenFromRemote(fs)
            return
        }
        val ratio = tryOrNull { CoreApi.splitRatio }?.takeIf { it > 0f } ?: return
        val clamped = SplitPane.clampRatio(ratio)
        if (!appliedRatio.isNaN() &&
            appliedFullscreenPane == SplitPane.FULLSCREEN_NONE &&
            abs(appliedRatio - clamped) < 0.01f
        ) {
            return
        }
        splitRatio = clamped
        applySplitLayoutWeights(clamped, force = true)
    }

    private val displayCreatedListener = object : IVirtualDisplayCreatedListener.Stub() {
        override fun onAvailableDisplay(displayId: Int, create: Boolean) {
            this@AaMainFragment.displayId = displayId
            runMain {
                if (!isAdded || context == null || view == null) {
                    Log.w(TAG, "onAvailableDisplay skipped: fragment not attached")
                    return@runMain
                }
                Log.d(TAG, "onAvailableDisplay: displayId=$displayId create=$create")
                primarySurface?.let { CoreApi.setPaneSurface(SplitPane.PRIMARY, it) }
                secondarySurface?.let { CoreApi.setPaneSurface(SplitPane.SECONDARY, it) }
                registerControlReceivers()
                // One reconnect settle pipeline (occupancy + optional ratio), not N parallel delays.
                scheduleConnectSettle(create)
            }
        }
    }

    private fun scheduleOccupancySync(delayMs: Long) {
        baseBinding.root.removeCallbacks(afterOccupancySync)
        baseBinding.root.postDelayed(afterOccupancySync, delayMs)
    }

    /**
     * Soft reconnect / first frame: cancel prior timers, then occupancy at 0/mid/late
     * and (on create) remote ratio at immediate + mid. Avoids Binder poll storms.
     */
    private fun scheduleConnectSettle(create: Boolean) {
        if (!isAdded || view == null) return
        val root = baseBinding.root
        root.removeCallbacks(settleImmediate)
        root.removeCallbacks(settleMid)
        root.removeCallbacks(settleLate)
        settleExpectCreate = create
        root.post(settleImmediate)
        root.postDelayed(settleMid, SETTLE_MID_MS)
        root.postDelayed(settleLate, SETTLE_LATE_MS)
    }

    private fun runConnectSettleStep(step: Int) {
        if (!isAdded || view == null || dividerDragging) return
        when (step) {
            0 -> {
                syncPaneOccupancyFromService()
                if (settleExpectCreate) applyRemoteRatioOnce()
            }
            1 -> {
                if (settleExpectCreate) applyRemoteRatioOnce()
                syncPaneOccupancyFromService()
            }
            else -> syncPaneOccupancyFromService()
        }
    }

    /**
     * Prefer density of the Display hosting the AA shell (HU / projection), not the phone
     * default metrics — phones and HUs vary; never hardcode a single dpi.
     */
    private fun resolveHostDensityDpi(): Int {
        val display = baseBinding.splitContainer.display
            ?: view?.display
            ?: context?.display
        if (display != null && display.displayId != Display.DEFAULT_DISPLAY) {
            runCatching {
                val dpi = requireContext()
                    .createDisplayContext(display)
                    .resources
                    .displayMetrics
                    .densityDpi
                if (dpi > 0) return dpi
            }
            runCatching {
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                if (metrics.densityDpi > 0) return metrics.densityDpi
            }
        }
        return resources.displayMetrics.densityDpi.coerceAtLeast(1)
    }

    private fun requestDisplay(reason: String) {
        val displayWidth = baseBinding.splitContainer.width
        val displayHeight = baseBinding.splitContainer.height
        val displayDpi = resolveHostDensityDpi()
        Log.d(
            TAG,
            "requestDisplay[$reason]: ${displayWidth}x$displayHeight,$displayDpi " +
                "requested=$isDisplayCreateRequested display=$displayId"
        )
        if (displayWidth <= 0 || displayHeight <= 0) return
        if (primarySurface == null || secondarySurface == null) {
            Log.d(TAG, "requestDisplay[$reason] waiting for both surfaces")
            return
        }
        if (isDisplayCreateRequested && displayId == Display.INVALID_DISPLAY) {
            Log.d(TAG, "requestDisplay[$reason] skipped: create already pending")
            return
        }
        // Soft-reconnect only when profile actually changed; skip identical repeats.
        if (displayId != Display.INVALID_DISPLAY &&
            displayWidth == lastCreateWidth &&
            displayHeight == lastCreateHeight &&
            displayDpi == lastCreateDpi
        ) {
            Log.d(TAG, "requestDisplay[$reason] skipped: profile unchanged")
            return
        }
        if (displayId == Display.INVALID_DISPLAY) {
            if (SplitPane.isFullscreenPane(fullscreenPane)) {
                applyFullscreenLayout(fullscreenPane)
            } else {
                applySplitLayoutWeights(splitRatio, force = true)
            }
        }
        lastCreateWidth = displayWidth
        lastCreateHeight = displayHeight
        lastCreateDpi = displayDpi
        isDisplayCreateRequested = true
        CoreApi.onCreateSplitDisplay(
            displayWidth,
            displayHeight,
            displayDpi,
            splitRatio,
            primarySurface,
            secondarySurface,
            displayCreatedListener
        )
    }

    private fun clearDisplaySurfaces(reason: String) {
        Log.d(TAG, "clearDisplaySurfaces[$reason]")
        CoreApi.setPaneSurface(SplitPane.PRIMARY, null)
        CoreApi.setPaneSurface(SplitPane.SECONDARY, null)
        primarySurface?.release()
        secondarySurface?.release()
        primarySurface = null
        secondarySurface = null
    }

    private fun pendingMoveFor(pane: Int): MotionEvent? =
        if (pane == SplitPane.PRIMARY) primaryPendingMove else secondaryPendingMove

    private fun setPendingMove(pane: Int, event: MotionEvent?) {
        if (pane == SplitPane.PRIMARY) primaryPendingMove = event else secondaryPendingMove = event
    }

    private fun flushRunnableFor(pane: Int): Runnable =
        if (pane == SplitPane.PRIMARY) primaryFlushMove else secondaryFlushMove

    private fun flushPendingMove(pane: Int) {
        val move = pendingMoveFor(pane) ?: return
        setPendingMove(pane, null)
        CoreApi.touchPane(pane, move)
        move.recycle()
    }

    /** Cancel coalesced MOVE; do not inject — VD may already be tearing down. */
    private fun cancelPendingPaneTouches() {
        try {
            baseBinding.tvDisplayPrimary.removeCallbacks(primaryFlushMove)
            baseBinding.tvDisplaySecondary.removeCallbacks(secondaryFlushMove)
        } catch (_: Throwable) {
        }
        primaryPendingMove?.recycle()
        primaryPendingMove = null
        secondaryPendingMove?.recycle()
        secondaryPendingMove = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchForwarding(
        textureView: TextureView,
        pane: Int,
        setDownTime: (Long) -> Unit,
    ) {
        // Coalesce MOVE to ~1/frame so Binder inject is not flooded during fast drags.
        val flushMove = flushRunnableFor(pane)
        textureView.setOnTouchListener { _, e ->
            // Fullscreen: ignore touches on the hidden pane TextureView.
            if (SplitPane.isFullscreenPane(fullscreenPane) && pane != fullscreenPane) {
                return@setOnTouchListener false
            }
            val uptimeMillis = SystemClock.uptimeMillis()
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                setDownTime(uptimeMillis)
                CoreApi.setFocusedPane(pane)
            }
            val down = if (pane == SplitPane.PRIMARY) repairDownTimePrimary else repairDownTimeSecondary
            val newEvent = rewriteMotionEvent(
                source = e,
                downTime = down,
                eventTime = uptimeMillis,
                sourceOverride = InputDeviceCompat.SOURCE_TOUCHSCREEN,
            )
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    pendingMoveFor(pane)?.recycle()
                    setPendingMove(pane, newEvent)
                    textureView.removeCallbacks(flushMove)
                    textureView.postOnAnimation(flushMove)
                }
                else -> {
                    textureView.removeCallbacks(flushMove)
                    pendingMoveFor(pane)?.let { pending ->
                        setPendingMove(pane, null)
                        CoreApi.touchPane(pane, pending)
                        pending.recycle()
                    }
                    CoreApi.touchPane(pane, newEvent)
                    newEvent.recycle()
                }
            }
            true
        }
    }

    private fun registerControlReceivers() {
        if (isControlReceiverRegistered) return
        val ctx = context
        if (!isAdded || ctx == null) return
        ContextCompat.registerReceiver(ctx, broadcastReceiver, IntentFilter().apply {
            addAction(AABroadcastConst.ACTION_STEERING_WHEEL_CONTROL)
            addAction(AABroadcastConst.ACTION_OPEN_SPLIT_PICKER)
            addAction(AABroadcastConst.ACTION_SPLIT_STATE_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)
        isControlReceiverRegistered = true
    }
}
