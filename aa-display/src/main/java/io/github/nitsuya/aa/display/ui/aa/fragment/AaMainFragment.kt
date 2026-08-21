package io.github.nitsuya.aa.display.ui.aa.fragment

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.InputDeviceCompat
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
import io.github.nitsuya.aa.display.util.ReconnectSizingTrace
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
    /** Track picker visibility so we can auto-hide it after system_server restart. */
    private var isPickerVisible: Boolean = false
    private val paneHasApp = booleanArrayOf(false, false)
    /** Live front packages for picker "最近" (empty string = vacant). */
    private val panePackages = arrayOf("", "")
    private var imeChipVisible = false
    private var imeChipPane = SplitPane.PRIMARY

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                AABroadcastConst.ACTION_OPEN_SPLIT_PICKER -> {
                    val pane = intent.getIntExtra(
                        AABroadcastConst.EXTRA_PANE,
                        SplitPane.PRIMARY
                    )
                    val keepOccupancy = intent.getBooleanExtra(
                        AABroadcastConst.EXTRA_KEEP_OCCUPANCY,
                        false,
                    )
                    // system_server vacant/restore miss clears occupancy; Recents "添加应用" keeps it.
                    if (SplitPane.isValid(pane)) {
                        if (!keepOccupancy) {
                            paneHasApp[pane] = false
                            panePackages[pane] = ""
                            updateEmptyOverlays()
                        }
                        appPicker.show(pane)
                    }
                }
                AABroadcastConst.ACTION_SPLIT_STATE_CHANGED -> {
                    // Occupancy (+ optional fullscreen / swap ratio).
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
                    // Swap inverts controller ratio; apply here so TextureViews match VD sizes
                    // without waiting solely on afterSwapSettle (which can race Binder/IO).
                    if (intent.hasExtra(AABroadcastConst.EXTRA_RATIO) &&
                        !dividerDragging &&
                        !SplitPane.isFullscreenPane(fullscreenPane)
                    ) {
                        val remote = intent.getFloatExtra(AABroadcastConst.EXTRA_RATIO, Float.NaN)
                        if (!remote.isNaN() && remote > 0f) {
                            val clamped = SplitPane.clampRatio(remote)
                            if (appliedRatio.isNaN() || abs(appliedRatio - clamped) >= 0.01f) {
                                splitRatio = clamped
                                applySplitLayoutWeights(clamped, force = true)
                                baseBinding.splitDivider.setRatio(clamped)
                            }
                        }
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
                            when (action) {
                                // MIB3 long-next is FAST_FORWARD (90); long-prev is REWIND (89).
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                                KeyEvent.KEYCODE_MEDIA_REWIND -> openRecentsFromSteering()
                                KeyEvent.KEYCODE_MEDIA_NEXT,
                                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> performSwapClick()
                            }
                        }
                    }
                }
                AABroadcastConst.ACTION_REQUEST_AA_UI_DISPLAY_ID -> {
                    reportAaUiDisplayId()
                }
                AABroadcastConst.ACTION_SHOW_RECENT_TASK -> {
                    // Locked-phone peel: system_server cannot inject into occluded presentation.
                    openRecentsFromSteering()
                }
                AABroadcastConst.ACTION_IME_VISIBILITY -> {
                    val pane = intent.getIntExtra(
                        AABroadcastConst.EXTRA_PANE,
                        SplitPane.FULLSCREEN_NONE,
                    )
                    applyImeChip(SplitPane.isValid(pane), pane)
                }
            }
        }
    }

    override fun initViews() {
        Log.d(TAG, "initViews")
        appPicker = SplitAppPickerController(baseBinding) {
            listOfNotNull(
                panePackages[SplitPane.PRIMARY].takeIf { it.isNotEmpty() },
                panePackages[SplitPane.SECONDARY].takeIf { it.isNotEmpty() },
            )
        }.also {
            it.onAppPicked = { pane, packageName ->
                if (SplitPane.isValid(pane)) {
                    paneHasApp[pane] = true
                    panePackages[pane] = packageName.trim()
                }
                updateEmptyOverlays()
                // Confirm with system_server after launch settles.
                scheduleOccupancySync(400L)
            }
            it.onVisibilityChanged = { showing ->
                isPickerVisible = showing
                if (showing) {
                    baseBinding.btnHideIme.isVisible = false
                } else {
                    refreshImeChip()
                }
            }
            it.prefetch()
        }

        LastSplitStore.load(requireContext().contentResolver)?.let { snap ->
            splitRatio = SplitPane.clampRatio(snap.primaryRatio)
            ratioBeforeFullscreen = splitRatio
            fullscreenPane = snap.fullscreenPane
            // Optimistic: hide "tap to choose" while system_server restores the pair.
            paneHasApp[SplitPane.PRIMARY] = true
            paneHasApp[SplitPane.SECONDARY] = true
            panePackages[SplitPane.PRIMARY] = snap.primaryPackage.trim()
            panePackages[SplitPane.SECONDARY] = snap.secondaryPackage.trim()
        }
        if (SplitPane.isFullscreenPane(fullscreenPane)) {
            applyFullscreenLayout(fullscreenPane)
        } else {
            applySplitLayoutWeights(splitRatio)
        }
        setupDivider()
        setupPaneSurfaces()
        setupEmptyPaneClicks()
        baseBinding.btnHideIme.setOnClickListener {
            applyImeChip(false, imeChipPane)
            CoreApi.hideIme()
        }

        // Single layout observer: size-change only (avoids doOnLayout + layout-change storms).
        baseBinding.splitContainer.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            val oldWidth = oldRight - oldLeft
            val oldHeight = oldBottom - oldTop
            if (width <= 0 || height <= 0) return@addOnLayoutChangeListener
            if (width == oldWidth && height == oldHeight) return@addOnLayoutChangeListener
            reportAaUiDisplayId()
            requestDisplay("layout-change")
        }
        // First layout may already have non-zero size before the listener is attached.
        baseBinding.splitContainer.post {
            if (baseBinding.splitContainer.width > 0 && baseBinding.splitContainer.height > 0) {
                reportAaUiDisplayId()
                requestDisplay("layout")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        baseBinding.splitContainer.post {
            reportAaUiDisplayId()
            if (displayId == Display.INVALID_DISPLAY) {
                isDisplayCreateRequested = false
            }
            // Soft-reconnect sizing is owned by CoreManagerService DisplayProfileSettle.
            requestDisplay("resume")
        }
    }

    override fun onDestroy() {
        try {
            baseBinding.root.removeCallbacks(settleImmediate)
            baseBinding.root.removeCallbacks(settleMid)
            baseBinding.root.removeCallbacks(settleLate)
            baseBinding.root.removeCallbacks(afterOccupancySync)
            baseBinding.root.removeCallbacks(afterSwapSettle)
        } catch (_: Throwable) {
        }
        try {
            clearDragPreview()
        } catch (_: Throwable) {
        }
        try {
            if (::appPicker.isInitialized) appPicker.destroy()
        } catch (_: Throwable) {
        }
        // Drop coalesced MOVE before tearing down VDs — never inject after destroy.
        cancelPendingPaneTouches()
        tryOrNull { CoreApi.reportAaUiDisplayId(Display.INVALID_DISPLAY) }
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
    /** GPU split/peel preview — pane layout sizes stay put until settle. */
    private var dragPreviewActive = false
    private var dragPeelPreview = false
    private var dragBasePrimaryMain = 1
    private var dragBaseSecondaryMain = 1
    private var dragBaseDividerMargin = 0
    /** Soft-reconnect settle: cancel prior pipeline before arming a new one. */
    private var settleExpectCreate = false
    private val settleImmediate = Runnable { runConnectSettleStep(0) }
    private val settleMid = Runnable { runConnectSettleStep(1) }
    private val settleLate = Runnable { runConnectSettleStep(2) }
    private val afterOccupancySync = Runnable { syncPaneOccupancyFromService() }
    private val afterSwapSettle = Runnable {
        if (!isAdded || view == null || dividerDragging) return@Runnable
        val fs = tryOrNull { CoreApi.splitFullscreenPane } ?: SplitPane.FULLSCREEN_NONE
        applyFullscreenFromRemote(fs)
        if (!SplitPane.isFullscreenPane(fs)) {
            val ratio = tryOrNull { CoreApi.splitRatio }?.takeIf { it > 0f } ?: return@Runnable
            val clamped = SplitPane.clampRatio(ratio)
            splitRatio = clamped
            applySplitLayoutWeights(clamped, force = true)
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
                // Drag: GPU scale/clip only. Live CoreApi.setSplitRatio →
                // VirtualDisplay.resize + freezeDisplayRotation costs 600–900ms/call on
                // system_server main and visibly jitters both panes.
                if (!dividerDragging) {
                    ratioAtDragStart = SplitPane.clampRatio(
                        if (appliedRatio.isNaN()) splitRatio else appliedRatio
                    )
                }
                dividerDragging = true
                splitRatio = ratio
                applyDragPreview(ratio)
                baseBinding.btnHideIme.isVisible = false
            }
            onRatioSettled = { ratio ->
                splitRatio = ratio
                clearDragPreview()
                applySplitLayoutWeights(ratio, force = true)
                CoreApi.setSplitRatio(ratio)
                dividerDragging = false
                baseBinding.root.removeCallbacks(afterOccupancySync)
                baseBinding.root.postDelayed(afterOccupancySync, 300L)
                refreshImeChip()
            }
            onFullscreenEnter = { pane ->
                dividerDragging = false
                ratioBeforeFullscreen = ratioAtDragStart
                enterFullscreen(pane)
                refreshImeChip()
            }
            onFullscreenExit = { ratio ->
                dividerDragging = false
                exitFullscreen(ratio)
                refreshImeChip()
            }
            onPeelCancelled = {
                dividerDragging = false
                clearDragPreview()
                if (SplitPane.isFullscreenPane(fullscreenPane)) {
                    applyFullscreenLayout(fullscreenPane)
                }
                refreshImeChip()
            }
            onStackClick = { openRecentsFromSteering() }
            onSwapClick = { performSwapClick() }
        }
    }

    /**
     * Same as divider / peel tap: split swaps both task stacks; fullscreen only flips
     * the visible pane (no moveRootTask — stacks stay on PRIMARY / SECONDARY).
     */
    private fun performSwapClick() {
        if (!isBaseBindingInitialized() || !isAdded) return
        if (SplitPane.isFullscreenPane(fullscreenPane)) {
            val other = if (fullscreenPane == SplitPane.PRIMARY) {
                SplitPane.SECONDARY
            } else {
                SplitPane.PRIMARY
            }
            enterFullscreen(other)
        } else {
            // Mirror controller's 1-ratio invert immediately so TextureViews track VD
            // resize; broadcast / afterSwapSettle correct if the Binder path lags.
            val next = SplitPane.clampRatio(1f - splitRatio)
            splitRatio = next
            applySplitLayoutWeights(next, force = true)
            baseBinding.splitDivider.setRatio(next)
            CoreApi.swapSplitPanes()
            baseBinding.root.removeCallbacks(afterSwapSettle)
            baseBinding.root.postDelayed(afterSwapSettle, 280L)
        }
    }

    /**
     * Recents is added on top without hiding Main — reset local gesture /
     * drag-preview state so peel inject cannot stay stuck mid-gesture.
     */
    private fun openRecentsFromSteering() {
        if (!isBaseBindingInitialized() || !isAdded) return
        baseBinding.splitDivider.resetGesture()
        dividerDragging = false
        clearDragPreview()
        runMain {
            AaDisplayActivityKt.showRecentTask(parentFragmentManager)
        }
    }

    private fun enterFullscreen(pane: Int) {
        if (!SplitPane.isFullscreenPane(pane)) return
        fullscreenPane = pane
        clearDragPreview()
        CoreApi.setSplitFullscreen(pane)
        applyFullscreenLayout(pane)
        updateEmptyOverlays()
        baseBinding.root.removeCallbacks(afterOccupancySync)
        baseBinding.root.postDelayed(afterOccupancySync, 300L)
    }

    /**
     * @param releaseRatio peel finger position when exiting (authoritative for local peel).
     * Null falls back to [ratioBeforeFullscreen] for remote / non-gesture exits.
     *
     * Publish [splitRatio] via [CoreApi.setSplitRatio] *before* exit while the
     * controller is still fullscreen — it stashes into `mRatioBeforeFullscreen`.
     * Then [CoreApi.setSplitFullscreen] restores that ratio in **one** VD resize.
     * Calling exit then ratio caused 800→ratioBefore→releaseRatio and left Window
     * Requested stuck (ADB: messaging Requested 343 on a 518×480 VD).
     */
    private fun exitFullscreen(releaseRatio: Float? = null) {
        fullscreenPane = SplitPane.FULLSCREEN_NONE
        clearDragPreview()
        splitRatio = SplitPane.clampRatio(releaseRatio ?: ratioBeforeFullscreen)
        CoreApi.setSplitRatio(splitRatio)
        CoreApi.setSplitFullscreen(SplitPane.FULLSCREEN_NONE)
        applySplitLayoutWeights(splitRatio, force = true)
        baseBinding.splitDivider.setFullscreenPane(SplitPane.FULLSCREEN_NONE)
        baseBinding.splitDivider.setRatio(splitRatio)
        updateEmptyOverlays()
        baseBinding.root.removeCallbacks(afterOccupancySync)
        baseBinding.root.postDelayed(afterOccupancySync, 300L)
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
        clearDragPreview()
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
        // Above pane elevation (2); divider itself uses empty outline (no shadow).
        baseBinding.splitDivider.elevation = 8f
        baseBinding.splitContainer.bringChildToFront(baseBinding.splitDivider)
        baseBinding.splitDivider.invalidate()
        syncPaneTouchEnabled(pane)
        positionImeChip()
    }

    private data class SplitVisual(
        val sideBySide: Boolean,
        val parentW: Int,
        val parentH: Int,
        val gap: Int,
        val expand: Int,
        val primaryMain: Int,
        val secondaryMain: Int,
    ) {
        val touchSpan: Int get() = gap + 2 * expand
        val dividerMargin: Int get() = (primaryMain - expand).coerceAtLeast(0)
    }

    private fun splitVisual(ratio: Float): SplitVisual? {
        val parentW = baseBinding.splitContainer.width
        val parentH = baseBinding.splitContainer.height
        if (parentW <= 0 || parentH <= 0) return null
        val sideBySide = parentW >= parentH
        val density = resources.displayMetrics.density
        val gap = (SplitPane.DIVIDER_DP * density).toInt().coerceAtLeast(1)
        val expand = (SplitPane.DIVIDER_TOUCH_EXPAND_DP * density).toInt().coerceAtLeast(0)
        val visual = ratio.coerceIn(0.01f, 0.99f)
        return if (sideBySide) {
            val usable = (parentW - gap).coerceAtLeast(2)
            val pw = (usable * visual).toInt().coerceAtLeast(1)
            SplitVisual(true, parentW, parentH, gap, expand, pw, (usable - pw).coerceAtLeast(1))
        } else {
            val usable = (parentH - gap).coerceAtLeast(2)
            val ph = (usable * visual).toInt().coerceAtLeast(1)
            SplitVisual(false, parentW, parentH, gap, expand, ph, (usable - ph).coerceAtLeast(1))
        }
    }

    private fun applySplitLayoutWeights(
        ratio: Float,
        force: Boolean = false,
    ) {
        val sideBySide = baseBinding.splitContainer.width >= baseBinding.splitContainer.height
            || baseBinding.splitContainer.width == 0
        if (!force &&
            appliedFullscreenPane == SplitPane.FULLSCREEN_NONE &&
            appliedSideBySide == sideBySide &&
            !appliedRatio.isNaN() &&
            abs(appliedRatio - ratio) < 0.001f
        ) {
            return
        }
        clearDragPreview()
        appliedRatio = ratio
        appliedSideBySide = sideBySide
        appliedFullscreenPane = SplitPane.FULLSCREEN_NONE
        fullscreenPane = SplitPane.FULLSCREEN_NONE
        baseBinding.splitDivider.setFullscreenPane(SplitPane.FULLSCREEN_NONE)
        baseBinding.splitContainer.clipChildren = false
        baseBinding.splitDivider.sideBySide = sideBySide

        val vis = splitVisual(ratio)
        val unmeasured = vis == null
        val gap = vis?.gap ?: 1
        val expand = vis?.expand ?: 0
        val touchSpan = vis?.touchSpan ?: (gap + 2 * expand)
        val primaryMain = vis?.primaryMain ?: 0
        val secondaryMain = vis?.secondaryMain ?: 0

        val primaryLp = baseBinding.panePrimary.layoutParams as FrameLayout.LayoutParams
        val secondaryLp = baseBinding.paneSecondary.layoutParams as FrameLayout.LayoutParams
        if (sideBySide) {
            primaryLp.width = if (unmeasured) FrameLayout.LayoutParams.MATCH_PARENT else primaryMain
            primaryLp.height = FrameLayout.LayoutParams.MATCH_PARENT
            primaryLp.marginStart = 0
            primaryLp.topMargin = 0
            primaryLp.gravity = Gravity.START or Gravity.TOP
            secondaryLp.width = if (unmeasured) FrameLayout.LayoutParams.MATCH_PARENT else secondaryMain
            secondaryLp.height = FrameLayout.LayoutParams.MATCH_PARENT
            secondaryLp.marginStart = if (unmeasured) 0 else primaryMain + gap
            secondaryLp.topMargin = 0
            secondaryLp.gravity = Gravity.START or Gravity.TOP

            val dividerLp = baseBinding.splitDivider.layoutParams as FrameLayout.LayoutParams
            dividerLp.width = touchSpan
            dividerLp.height = FrameLayout.LayoutParams.MATCH_PARENT
            dividerLp.marginStart = if (unmeasured) 0 else (primaryMain - expand).coerceAtLeast(0)
            dividerLp.topMargin = 0
            dividerLp.gravity = Gravity.START or Gravity.TOP
            baseBinding.splitDivider.layoutParams = dividerLp
        } else {
            primaryLp.width = FrameLayout.LayoutParams.MATCH_PARENT
            primaryLp.height = if (unmeasured) FrameLayout.LayoutParams.MATCH_PARENT else primaryMain
            primaryLp.marginStart = 0
            primaryLp.topMargin = 0
            primaryLp.gravity = Gravity.START or Gravity.TOP
            secondaryLp.width = FrameLayout.LayoutParams.MATCH_PARENT
            secondaryLp.height = if (unmeasured) FrameLayout.LayoutParams.MATCH_PARENT else secondaryMain
            secondaryLp.marginStart = 0
            secondaryLp.topMargin = if (unmeasured) 0 else primaryMain + gap
            secondaryLp.gravity = Gravity.START or Gravity.TOP

            val dividerLp = baseBinding.splitDivider.layoutParams as FrameLayout.LayoutParams
            dividerLp.width = FrameLayout.LayoutParams.MATCH_PARENT
            dividerLp.height = touchSpan
            dividerLp.marginStart = 0
            dividerLp.topMargin = if (unmeasured) 0 else (primaryMain - expand).coerceAtLeast(0)
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
        syncPaneTouchEnabled(SplitPane.FULLSCREEN_NONE)
        positionImeChip()
    }

    /**
     * Drag preview without resizing TextureViews. Split: GPU scale from settled pane
     * sizes. Peel: clipBounds on full-buffer panes so the other app shows through.
     */
    private fun applyDragPreview(ratio: Float) {
        val vis = splitVisual(ratio) ?: return
        beginDragPreviewIfNeeded(vis)
        if (dragPeelPreview) {
            applyPeelClipPreview(vis)
        } else {
            applySplitScalePreview(vis)
        }
        baseBinding.splitDivider.invalidate()
    }

    private fun beginDragPreviewIfNeeded(vis: SplitVisual) {
        if (dragPreviewActive) return
        dragPreviewActive = true
        dragPeelPreview = SplitPane.isFullscreenPane(fullscreenPane)
        val sideBySide = vis.sideBySide
        val primary = baseBinding.panePrimary
        val secondary = baseBinding.paneSecondary
        dragBasePrimaryMain = if (sideBySide) {
            primary.width.coerceAtLeast(1)
        } else {
            primary.height.coerceAtLeast(1)
        }
        dragBaseSecondaryMain = if (sideBySide) {
            secondary.width.coerceAtLeast(1)
        } else {
            secondary.height.coerceAtLeast(1)
        }
        val divider = baseBinding.splitDivider
        val dividerLp = divider.layoutParams as FrameLayout.LayoutParams
        if (dragPeelPreview) {
            // Peel tab is only the entry; morph to the normal split divider chrome.
            baseBinding.tvDisplayPrimary.visibility = View.VISIBLE
            baseBinding.tvDisplaySecondary.visibility = View.VISIBLE
            divider.setPeelDragSplitVisual(true)
            if (sideBySide) {
                dividerLp.width = vis.touchSpan
                dividerLp.height = FrameLayout.LayoutParams.MATCH_PARENT
                dividerLp.marginStart = vis.dividerMargin
                dividerLp.topMargin = 0
                dividerLp.gravity = Gravity.START or Gravity.TOP
            } else {
                dividerLp.width = FrameLayout.LayoutParams.MATCH_PARENT
                dividerLp.height = vis.touchSpan
                dividerLp.marginStart = 0
                dividerLp.topMargin = vis.dividerMargin
                dividerLp.gravity = Gravity.START or Gravity.TOP
            }
            divider.layoutParams = dividerLp
            dragBaseDividerMargin = vis.dividerMargin
            divider.translationX = 0f
            divider.translationY = 0f
        } else {
            dragBaseDividerMargin = if (sideBySide) dividerLp.marginStart else dividerLp.topMargin
        }
    }

    private fun applySplitScalePreview(vis: SplitVisual) {
        val primary = baseBinding.panePrimary
        val secondary = baseBinding.paneSecondary
        val divider = baseBinding.splitDivider
        val primaryScale = vis.primaryMain.toFloat() / dragBasePrimaryMain
        val secondaryScale = vis.secondaryMain.toFloat() / dragBaseSecondaryMain
        val shift = (vis.primaryMain - dragBasePrimaryMain).toFloat()
        val dividerShift = (vis.dividerMargin - dragBaseDividerMargin).toFloat()
        primary.pivotX = 0f
        primary.pivotY = 0f
        secondary.pivotX = 0f
        secondary.pivotY = 0f
        if (vis.sideBySide) {
            primary.scaleX = primaryScale
            primary.scaleY = 1f
            primary.translationX = 0f
            primary.translationY = 0f
            secondary.scaleX = secondaryScale
            secondary.scaleY = 1f
            secondary.translationX = shift
            secondary.translationY = 0f
            divider.translationX = dividerShift
            divider.translationY = 0f
        } else {
            primary.scaleX = 1f
            primary.scaleY = primaryScale
            primary.translationX = 0f
            primary.translationY = 0f
            secondary.scaleX = 1f
            secondary.scaleY = secondaryScale
            secondary.translationX = 0f
            secondary.translationY = shift
            divider.translationX = 0f
            divider.translationY = dividerShift
        }
    }

    private fun applyPeelClipPreview(vis: SplitVisual) {
        val primary = baseBinding.panePrimary
        val secondary = baseBinding.paneSecondary
        val divider = baseBinding.splitDivider
        primary.scaleX = 1f
        primary.scaleY = 1f
        primary.translationX = 0f
        primary.translationY = 0f
        secondary.scaleX = 1f
        secondary.scaleY = 1f
        secondary.translationX = 0f
        secondary.translationY = 0f
        // Same seam geometry as split drag: DIVIDER_DP gap + centered full-length bar.
        val dividerShift = (vis.dividerMargin - dragBaseDividerMargin).toFloat()
        if (vis.sideBySide) {
            primary.clipBounds = Rect(0, 0, vis.primaryMain, vis.parentH)
            val secLeft = (vis.primaryMain + vis.gap).coerceAtMost(vis.parentW)
            secondary.clipBounds = Rect(secLeft, 0, vis.parentW, vis.parentH)
            divider.translationX = dividerShift
            divider.translationY = 0f
        } else {
            primary.clipBounds = Rect(0, 0, vis.parentW, vis.primaryMain)
            val secTop = (vis.primaryMain + vis.gap).coerceAtMost(vis.parentH)
            secondary.clipBounds = Rect(0, secTop, vis.parentW, vis.parentH)
            divider.translationX = 0f
            divider.translationY = dividerShift
        }
    }

    private fun clearDragPreview() {
        if (!dragPreviewActive) return
        dragPreviewActive = false
        dragPeelPreview = false
        val primary = baseBinding.panePrimary
        val secondary = baseBinding.paneSecondary
        val divider = baseBinding.splitDivider
        for (v in arrayOf(primary, secondary)) {
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationX = 0f
            v.translationY = 0f
            v.pivotX = 0f
            v.pivotY = 0f
            v.clipBounds = null
        }
        divider.setPeelDragSplitVisual(false)
        divider.translationX = 0f
        divider.translationY = 0f
    }

    /**
     * Behind fullscreen pane must not eat touches or be composited; both panes
     * active in split. INVISIBLE (not GONE) keeps the SurfaceTexture alive.
     *
     * While either TextureView still lacks a Surface, keep both VISIBLE: applying
     * LastSplit fullscreen in [initViews] used to hide the back pane before
     * [onSurfaceTextureAvailable], so [requestDisplay] waited forever, VDs never
     * created, and restore fell back to "tap to choose".
     */
    private fun syncPaneTouchEnabled(visibleFullscreenPane: Int) {
        val primaryTv = baseBinding.tvDisplayPrimary
        val secondaryTv = baseBinding.tvDisplaySecondary
        val bootstrapping = primarySurface == null || secondarySurface == null
        when {
            bootstrapping || visibleFullscreenPane == SplitPane.FULLSCREEN_NONE -> {
                primaryTv.isEnabled = true
                primaryTv.visibility = View.VISIBLE
                secondaryTv.isEnabled = true
                secondaryTv.visibility = View.VISIBLE
            }
            visibleFullscreenPane == SplitPane.PRIMARY -> {
                primaryTv.isEnabled = true
                primaryTv.visibility = View.VISIBLE
                secondaryTv.isEnabled = false
                secondaryTv.visibility = View.INVISIBLE
            }
            else -> {
                primaryTv.isEnabled = false
                primaryTv.visibility = View.INVISIBLE
                secondaryTv.isEnabled = true
                secondaryTv.visibility = View.VISIBLE
            }
        }
    }

    private fun setupPaneSurfaces() {
        baseBinding.tvDisplayPrimary.isOpaque = true
        baseBinding.tvDisplaySecondary.isOpaque = true
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
                // Presentation displayId can bind after first layout on some OEMs — re-report
                // so Coolwalk peel inject does not race an empty mAaUiDisplayId.
                reportAaUiDisplayId()
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
            panePackages[pane] = pkg
        }
        updateEmptyOverlays()
        maybeAutoHidePickerWhenOccupied()
    }

    private fun applyOccupancyFromPackages(primaryPkg: String, secondaryPkg: String) {
        if (!isAdded || view == null || dividerDragging) return
        val primary = primaryPkg.trim()
        val secondary = secondaryPkg.trim()
        paneHasApp[SplitPane.PRIMARY] = primary.isNotEmpty()
        paneHasApp[SplitPane.SECONDARY] = secondary.isNotEmpty()
        panePackages[SplitPane.PRIMARY] = primary
        panePackages[SplitPane.SECONDARY] = secondary
        updateEmptyOverlays()
        maybeAutoHidePickerWhenOccupied()
    }

    /**
     * After a soft reconnect / system_server restart, fragment is often not recreated.
     * If the picker was left open, it can remain visible even after both panes are
     * already occupied (so the user doesn't need "tap to choose" anymore).
     */
    private fun maybeAutoHidePickerWhenOccupied() {
        if (!::appPicker.isInitialized) return
        if (!isPickerVisible) return
        if (paneHasApp[SplitPane.PRIMARY] && paneHasApp[SplitPane.SECONDARY]) {
            appPicker.hide()
        }
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
                reportAaUiDisplayId()
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

    /**
     * Publish this presentation's displayId to system_server for Coolwalk peel inject.
     * Private CarActivity VDs are not visible via system [DisplayManager.getDisplays].
     * Called from layout, resume, surface-available, and VD-created so late display
     * binding on some OEMs still reaches [SplitDisplayController.setAaUiDisplayId].
     */
    private fun reportAaUiDisplayId() {
        val host = baseBinding.splitContainer.display
            ?: view?.display
            ?: context?.display
            ?: return
        val id = host.displayId
        if (id == Display.INVALID_DISPLAY || id == Display.DEFAULT_DISPLAY) return
        tryOrNull { CoreApi.reportAaUiDisplayId(id) }
    }

    private fun requestDisplay(reason: String) {
        val displayWidth = baseBinding.splitContainer.width
        val displayHeight = baseBinding.splitContainer.height
        val displayDpi = resolveHostDensityDpi()
        val trace = ReconnectSizingTrace.ENABLED
        val hostDisplay = if (trace) {
            baseBinding.splitContainer.display ?: view?.display ?: context?.display
        } else null
        val hostMode = hostDisplay?.mode
        Log.d(
            TAG,
            "requestDisplay[$reason]: ${displayWidth}x$displayHeight,$displayDpi " +
                "requested=$isDisplayCreateRequested display=$displayId " +
                if (trace) {
                    "hostDisplay=${hostDisplay?.displayId ?: Display.INVALID_DISPLAY} " +
                        "hostMode=${hostMode?.physicalWidth ?: 0}x${hostMode?.physicalHeight ?: 0}"
                } else {
                    ""
                }
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
        ctx.registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(AABroadcastConst.ACTION_STEERING_WHEEL_CONTROL)
            addAction(AABroadcastConst.ACTION_OPEN_SPLIT_PICKER)
            addAction(AABroadcastConst.ACTION_SPLIT_STATE_CHANGED)
            addAction(AABroadcastConst.ACTION_REQUEST_AA_UI_DISPLAY_ID)
            addAction(AABroadcastConst.ACTION_SHOW_RECENT_TASK)
            addAction(AABroadcastConst.ACTION_IME_VISIBILITY)
        }, Context.RECEIVER_EXPORTED)
        isControlReceiverRegistered = true
        syncImeChipFromService()
    }

    private fun syncImeChipFromService() {
        val pane = tryOrNull { CoreApi.imePane } ?: SplitPane.FULLSCREEN_NONE
        applyImeChip(SplitPane.isValid(pane), pane)
    }

    private fun refreshImeChip() {
        applyImeChip(imeChipVisible, imeChipPane)
    }

    private fun applyImeChip(visible: Boolean, pane: Int) {
        if (!isBaseBindingInitialized() || !isAdded) return
        imeChipVisible = visible
        if (SplitPane.isValid(pane)) imeChipPane = pane
        val pickerUp = try {
            baseBinding.appPickerHost.isVisible
        } catch (_: Throwable) {
            false
        }
        val show = visible && !dividerDragging && !pickerUp
        baseBinding.btnHideIme.isVisible = show
        if (show) {
            baseBinding.btnHideIme.bringToFront()
            positionImeChip()
        }
    }

    /** Sit on the AA shell (above TextureView IME pixels) at the bottom of the IME pane. */
    private fun positionImeChip() {
        if (!isBaseBindingInitialized() || !imeChipVisible) return
        val chip = baseBinding.btnHideIme
        if (!chip.isVisible) return
        val host = baseBinding.root
        val target = when {
            SplitPane.isFullscreenPane(fullscreenPane) -> baseBinding.splitContainer
            imeChipPane == SplitPane.PRIMARY -> baseBinding.panePrimary
            else -> baseBinding.paneSecondary
        }
        if (host.width <= 0 || target.width <= 0) {
            if (!host.isLaidOut) host.post { positionImeChip() }
            return
        }
        val locHost = IntArray(2)
        val locTarget = IntArray(2)
        host.getLocationOnScreen(locHost)
        target.getLocationOnScreen(locTarget)
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        chip.measure(spec, spec)
        val cw = chip.measuredWidth.coerceAtLeast(1)
        val ch = chip.measuredHeight.coerceAtLeast(1)
        val density = resources.displayMetrics.density
        val margin = (16f * density).toInt()
        val left = locTarget[0] - locHost[0]
        val top = locTarget[1] - locHost[1]
        val lp = chip.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.TOP or Gravity.START
        lp.marginStart = left + ((target.width - cw) / 2).coerceAtLeast(0)
        lp.topMargin = (top + target.height - ch - margin).coerceAtLeast(margin)
        lp.bottomMargin = 0
        chip.layoutParams = lp
    }
}
