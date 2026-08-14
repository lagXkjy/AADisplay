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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.widget.LinearLayout
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

class AaMainFragment : BaseFragment<FragmentAaMainBinding>(FragmentAaMainBinding::class.java) {
    companion object {
        private const val TAG = "AADisplay_AaMainFragment"
        /**
         * HU Coolwalk VDs are density-160 (dp≈px). Using the phone AA process density
         * (often 420–480) makes pane apps overscale assets and layout work.
         */
        private const val HU_VD_DENSITY_DPI = 160
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
                    // Occupancy only — never re-apply ratio/weights from broadcasts.
                    // Prefer extras from system_server to avoid sync getPanePackage Binder hits.
                    val primary = intent.getStringExtra(AABroadcastConst.EXTRA_PRIMARY_PACKAGE)
                    val secondary = intent.getStringExtra(AABroadcastConst.EXTRA_SECONDARY_PACKAGE)
                    if (primary != null || secondary != null) {
                        applyOccupancyFromPackages(primary.orEmpty(), secondary.orEmpty())
                    } else {
                        syncPaneOccupancyFromService()
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

        LastSplitStore.load(requireContext().contentResolver)?.primaryRatio?.let {
            splitRatio = SplitPane.clampRatio(it)
        }
        applySplitLayoutWeights(splitRatio)
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
        } catch (_: Throwable) {
        }
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
        if (isControlReceiverRegistered) {
            tryOrNull { context?.unregisterReceiver(broadcastReceiver) }
            isControlReceiverRegistered = false
        }
    }

    private var appliedRatio: Float = Float.NaN
    private var appliedSideBySide: Boolean? = null
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
    private val afterSwapSettle = Runnable {
        if (!isAdded || view == null || dividerDragging) return@Runnable
        val ratio = tryOrNull { CoreApi.splitRatio }?.takeIf { it > 0f } ?: return@Runnable
        val clamped = SplitPane.clampRatio(ratio)
        splitRatio = clamped
        applySplitLayoutWeights(clamped)
        baseBinding.splitDivider.setRatio(clamped)
        syncPaneOccupancyFromService()
    }

    private fun setupDivider() {
        baseBinding.splitDivider.apply {
            sideBySide = resources.configuration.orientation !=
                android.content.res.Configuration.ORIENTATION_PORTRAIT
            setRatio(splitRatio)
            onRatioChanged = { ratio ->
                // Drag: update LinearLayout weights only. Live CoreApi.setSplitRatio →
                // VirtualDisplay.resize + freezeDisplayRotation costs 600–900ms/call on
                // system_server main and visibly jitters both panes.
                dividerDragging = true
                splitRatio = ratio
                applySplitLayoutWeights(ratio)
            }
            onRatioSettled = { ratio ->
                splitRatio = ratio
                applySplitLayoutWeights(ratio)
                CoreApi.setSplitRatio(ratio)
                dividerDragging = false
                // One occupancy sync after settle (restore / remote may have launched apps).
                baseBinding.root.removeCallbacks(afterDividerSettle)
                baseBinding.root.postDelayed(afterDividerSettle, 300L)
            }
            onStackClick = {
                runMain {
                    AaDisplayActivityKt.showRecentTask(this@AaMainFragment.parentFragmentManager)
                }
            }
            onSwapClick = {
                CoreApi.swapSplitPanes()
                // Broadcast carries occupancy only; sync inverted ratio after controller settles.
                baseBinding.root.removeCallbacks(afterSwapSettle)
                baseBinding.root.postDelayed(afterSwapSettle, 280L)
            }
        }
    }

    private fun applySplitLayoutWeights(ratio: Float) {
        val sideBySide = baseBinding.splitContainer.width >= baseBinding.splitContainer.height
            || baseBinding.splitContainer.width == 0
        if (appliedSideBySide == sideBySide &&
            !appliedRatio.isNaN() &&
            kotlin.math.abs(appliedRatio - ratio) < 0.001f
        ) {
            return
        }
        appliedRatio = ratio
        appliedSideBySide = sideBySide
        baseBinding.splitContainer.orientation =
            if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        // Divider overlaps panes for hit target; allow drawing/touch outside child box.
        baseBinding.splitContainer.clipChildren = false
        baseBinding.splitDivider.sideBySide = sideBySide
        val dividerLp = baseBinding.splitDivider.layoutParams as LinearLayout.LayoutParams
        baseBinding.splitDivider.applyLayoutParams(dividerLp, sideBySide)
        baseBinding.splitDivider.layoutParams = dividerLp

        val primaryLp = baseBinding.panePrimary.layoutParams as LinearLayout.LayoutParams
        val secondaryLp = baseBinding.paneSecondary.layoutParams as LinearLayout.LayoutParams
        if (sideBySide) {
            primaryLp.width = 0
            primaryLp.height = LinearLayout.LayoutParams.MATCH_PARENT
            secondaryLp.width = 0
            secondaryLp.height = LinearLayout.LayoutParams.MATCH_PARENT
        } else {
            primaryLp.width = LinearLayout.LayoutParams.MATCH_PARENT
            primaryLp.height = 0
            secondaryLp.width = LinearLayout.LayoutParams.MATCH_PARENT
            secondaryLp.height = 0
        }
        primaryLp.weight = ratio
        secondaryLp.weight = 1f - ratio
        baseBinding.panePrimary.layoutParams = primaryLp
        baseBinding.paneSecondary.layoutParams = secondaryLp
        baseBinding.splitDivider.setRatio(ratio)
        baseBinding.splitDivider.invalidate()
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
        baseBinding.tvEmptyPrimary.isVisible = !paneHasApp[SplitPane.PRIMARY]
        baseBinding.tvEmptySecondary.isVisible = !paneHasApp[SplitPane.SECONDARY]
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

    /** Apply remote ratio only once after create/restore (not on every broadcast). */
    private fun applyRemoteRatioOnce() {
        if (!isAdded || view == null || dividerDragging) return
        val ratio = tryOrNull { CoreApi.splitRatio }?.takeIf { it > 0f } ?: return
        val clamped = SplitPane.clampRatio(ratio)
        if (!appliedRatio.isNaN() && kotlin.math.abs(appliedRatio - clamped) < 0.01f) return
        splitRatio = clamped
        applySplitLayoutWeights(clamped)
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
        baseBinding.root.removeCallbacks(settleLate)
        baseBinding.root.postDelayed({
            syncPaneOccupancyFromService()
        }, delayMs)
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
        root.postDelayed(settleMid, 400L)
        root.postDelayed(settleLate, 900L)
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
            applySplitLayoutWeights(splitRatio)
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchForwarding(
        textureView: TextureView,
        pane: Int,
        setDownTime: (Long) -> Unit,
    ) {
        // Coalesce MOVE to ~1/frame so Binder inject is not flooded during fast drags.
        var pendingMove: MotionEvent? = null
        val flushMove = Runnable {
            val move = pendingMove ?: return@Runnable
            pendingMove = null
            CoreApi.touchPane(pane, move)
            move.recycle()
        }
        textureView.setOnTouchListener { _, e ->
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
                    pendingMove?.recycle()
                    pendingMove = newEvent
                    textureView.removeCallbacks(flushMove)
                    textureView.postOnAnimation(flushMove)
                }
                else -> {
                    textureView.removeCallbacks(flushMove)
                    pendingMove?.let { pending ->
                        pendingMove = null
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
