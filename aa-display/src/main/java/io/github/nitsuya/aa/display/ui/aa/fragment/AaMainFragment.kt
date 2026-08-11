package io.github.nitsuya.aa.display.ui.aa.fragment

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.support.car.Car
import android.support.car.CarConnectionCallback
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.InputDeviceCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import com.google.android.gms.car.CarFirstPartyManager
import com.topjohnwu.superuser.Shell
import io.github.duzhaokun123.template.bases.BaseFragment
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.databinding.FragmentAaMainBinding
import io.github.nitsuya.aa.display.ui.aa.AaDisplayActivityKt
import io.github.nitsuya.aa.display.ui.aa.split.SplitAppPickerController
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.LastSplitStore
import io.github.nitsuya.aa.display.util.SharedPreferencesAccess
import io.github.nitsuya.aa.display.util.getGmsCarFirstPartyManager
import io.github.nitsuya.aa.display.util.rewriteMotionEvent
import io.github.nitsuya.aa.display.util.startCarAaDisplay
import io.github.nitsuya.aa.display.util.startCarTelecom
import io.github.nitsuya.aa.display.xposed.IVirtualDisplayCreatedListener
import io.github.nitsuya.template.bases.runMain

class AaMainFragment : BaseFragment<FragmentAaMainBinding>(FragmentAaMainBinding::class.java) {
    companion object {
        private const val TAG = "AADisplay_AaMainFragment"
    }

    private var displayId: Int = Display.INVALID_DISPLAY
    private var repairDownTimePrimary = Long.MIN_VALUE
    private var repairDownTimeSecondary = Long.MIN_VALUE
    private var isForeground = false
    private var isDisplayCreateRequested = false
    private var isControlReceiverRegistered = false
    private var primarySurface: Surface? = null
    private var secondarySurface: Surface? = null
    private var splitRatio: Float = SplitPane.DEFAULT_RATIO
    private lateinit var config: SharedPreferences
    private lateinit var appPicker: SplitAppPickerController
    private val paneHasApp = booleanArrayOf(false, false)

    private var car: Car? = null
    private var carManager: CarFirstPartyManager? = null
    private val carConnectionCallback = object : CarConnectionCallback() {
        override fun onConnected(car: Car) {
            if (carManager == null) {
                carManager = car.getGmsCarFirstPartyManager()
            }
        }

        override fun onDisconnected(car: Car) {
            carManager = null
        }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        val voiceAssistShell by lazy { AADisplayConfig.VoiceAssistShell.get(config) }
        fun startVoiceAssist() {
            voiceAssistShell?.let {
                CoreApi.displayPower(true)
                tryOrNull {
                    Shell.cmd(
                        it.replace(
                            "\${DisplayId}",
                            (if (displayId == Display.INVALID_DISPLAY) Display.DEFAULT_DISPLAY else displayId).toString()
                        )
                    ).exec()
                }
            }
        }

        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                AABroadcastConst.ACTION_OPEN_SPLIT_PICKER -> {
                    val pane = intent.getIntExtra(
                        AABroadcastConst.EXTRA_PANE,
                        SplitPane.PRIMARY
                    )
                    appPicker.show(pane)
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
                AABroadcastConst.ACTION_SCREEN_CONTROL -> {
                    when (val action = intent.getIntExtra(AABroadcastConst.EXTRA_ACTION, 0)) {
                        KeyEvent.KEYCODE_FEATURED_APP_1 -> carManager.startCarTelecom()
                        KeyEvent.KEYCODE_SEARCH -> startVoiceAssist()
                        KeyEvent.KEYCODE_POWER -> CoreApi.toggleDisplayPower()
                        else -> {
                            if (!isForeground) {
                                carManager.startCarAaDisplay()
                                return
                            }
                            when (action) {
                                KeyEvent.KEYCODE_DEMO_APP_1 -> CoreApi.moveSecondTaskToFront()
                                KeyEvent.KEYCODE_BACK -> CoreApi.pressKey(action)
                                KeyEvent.KEYCODE_APP_SWITCH -> runMain {
                                    AaDisplayActivityKt.showRecentTask(this@AaMainFragment.parentFragmentManager)
                                }
                            }
                        }
                    }
                }
                AABroadcastConst.ACTION_STEERING_WHEEL_CONTROL -> {
                    val action = intent.getIntExtra(AABroadcastConst.EXTRA_ACTION, 0)
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
                                else -> CoreApi.toast("方控[$action]未设置")
                            }
                        }
                        1 -> {
                            when (action) {
                                KeyEvent.KEYCODE_SEARCH -> startVoiceAssist()
                                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> CoreApi.moveSecondTaskToFront()
                                else -> CoreApi.toast("方控长按[$action]未设置")
                            }
                        }
                        2 -> CoreApi.toast("方控双击[$action]未设置")
                    }
                }
            }
        }
    }

    override fun initViews() {
        config = SharedPreferencesAccess.openForHooks(this.requireContext(), AADisplayConfig.ConfigName)
        Log.i(TAG, "initViews")
        // Do NOT call makeReadableForHooks here: Shell.getShell()+cp blocks AA main thread
        // for hundreds of ms–seconds. Mirror is published when settings are saved.
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

        car?.disconnect()
        car = Car.createCar(this.requireContext(), carConnectionCallback).apply {
            connect()
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        if (this::config.isInitialized) {
            baseBinding.splitContainer.post {
                if (displayId == Display.INVALID_DISPLAY) {
                    isDisplayCreateRequested = false
                }
                requestDisplay("resume")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: displayId=$displayId")
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
        car?.disconnect()
        car = null
    }

    private var appliedRatio: Float = Float.NaN
    private var appliedSideBySide: Boolean? = null
    private var lastCreateWidth = 0
    private var lastCreateHeight = 0
    private var lastCreateDpi = 0
    private var dividerDragging = false

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
                baseBinding.root.postDelayed({ syncPaneOccupancyFromService() }, 300L)
            }
            onStackClick = {
                runMain {
                    AaDisplayActivityKt.showRecentTask(this@AaMainFragment.parentFragmentManager)
                }
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
        baseBinding.splitDivider.sideBySide = sideBySide
        val dividerLp = baseBinding.splitDivider.layoutParams as LinearLayout.LayoutParams
        if (sideBySide) {
            dividerLp.width = (SplitPane.DIVIDER_DP * resources.displayMetrics.density).toInt()
            dividerLp.height = LinearLayout.LayoutParams.MATCH_PARENT
        } else {
            dividerLp.width = LinearLayout.LayoutParams.MATCH_PARENT
            dividerLp.height = (SplitPane.DIVIDER_DP * resources.displayMetrics.density).toInt()
        }
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
                Log.i(TAG, "pane=$pane surface available ${width}x$height")
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
                Log.i(TAG, "pane=$pane surface destroyed")
                CoreApi.setPaneSurface(pane, null)
                onSurface(null)
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun setupEmptyPaneClicks() {
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
                Log.i(TAG, "onAvailableDisplay: displayId=$displayId create=$create")
                primarySurface?.let { CoreApi.setPaneSurface(SplitPane.PRIMARY, it) }
                secondarySurface?.let { CoreApi.setPaneSurface(SplitPane.SECONDARY, it) }
                registerControlReceivers()
                // Occupancy settles after restore / pane launches; light Binder retries only.
                scheduleOccupancySync(0L)
                scheduleOccupancySync(900L)
                if (create) {
                    applyRemoteRatioOnce()
                    baseBinding.root.postDelayed({ applyRemoteRatioOnce() }, 700L)
                }
            }
        }
    }

    private fun scheduleOccupancySync(delayMs: Long) {
        baseBinding.root.postDelayed({
            syncPaneOccupancyFromService()
        }, delayMs)
    }

    private fun requestDisplay(reason: String) {
        if (!this::config.isInitialized) return
        val displayWidth = baseBinding.splitContainer.width
        val displayHeight = baseBinding.splitContainer.height
        val displayDpi = AADisplayConfig.VirtualDisplayDpi.get(config).let {
            if (it <= 50) resources.displayMetrics.densityDpi else it
        }
        Log.i(
            TAG,
            "requestDisplay[$reason]: ${displayWidth}x$displayHeight,$displayDpi " +
                "requested=$isDisplayCreateRequested display=$displayId"
        )
        if (displayWidth <= 0 || displayHeight <= 0) return
        if (primarySurface == null || secondarySurface == null) {
            Log.i(TAG, "requestDisplay[$reason] waiting for both surfaces")
            return
        }
        if (isDisplayCreateRequested && displayId == Display.INVALID_DISPLAY) {
            Log.i(TAG, "requestDisplay[$reason] skipped: create already pending")
            return
        }
        // Soft-reconnect only when profile actually changed; skip identical repeats.
        if (displayId != Display.INVALID_DISPLAY &&
            displayWidth == lastCreateWidth &&
            displayHeight == lastCreateHeight &&
            displayDpi == lastCreateDpi
        ) {
            Log.i(TAG, "requestDisplay[$reason] skipped: profile unchanged")
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
        Log.i(TAG, "clearDisplaySurfaces[$reason]")
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
        val touchSlop = android.view.ViewConfiguration.get(textureView.context).scaledTouchSlop
        val longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var longPressFired = false
        val longPressRunnable = Runnable {
            longPressFired = true
            // Cancel the in-progress gesture on the VD before opening the picker.
            val down = if (pane == SplitPane.PRIMARY) repairDownTimePrimary else repairDownTimeSecondary
            val cancel = MotionEvent.obtain(
                down,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_CANCEL,
                downX,
                downY,
                0,
            )
            cancel.source = InputDeviceCompat.SOURCE_TOUCHSCREEN
            CoreApi.touchPane(pane, cancel)
            cancel.recycle()
            appPicker.show(pane)
        }
        textureView.setOnTouchListener { v, e ->
            val uptimeMillis = SystemClock.uptimeMillis()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressFired = false
                    downX = e.x
                    downY = e.y
                    setDownTime(uptimeMillis)
                    CoreApi.setFocusedPane(pane)
                    v.removeCallbacks(longPressRunnable)
                    v.postDelayed(longPressRunnable, longPressTimeout)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!longPressFired) {
                        val dx = e.x - downX
                        val dy = e.y - downY
                        if (dx * dx + dy * dy > touchSlop * touchSlop) {
                            v.removeCallbacks(longPressRunnable)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPressRunnable)
                }
            }
            if (longPressFired) {
                return@setOnTouchListener true
            }
            val down = if (pane == SplitPane.PRIMARY) repairDownTimePrimary else repairDownTimeSecondary
            val newEvent = rewriteMotionEvent(
                source = e,
                downTime = down,
                eventTime = uptimeMillis,
                preserveMeta = false,
                sourceOverride = InputDeviceCompat.SOURCE_TOUCHSCREEN,
            )
            CoreApi.touchPane(pane, newEvent)
            newEvent.recycle()
            true
        }
    }

    private fun registerControlReceivers() {
        if (isControlReceiverRegistered) return
        val ctx = context
        if (!isAdded || ctx == null) return
        ContextCompat.registerReceiver(ctx, broadcastReceiver, IntentFilter().apply {
            addAction(AABroadcastConst.ACTION_SCREEN_CONTROL)
            addAction(AABroadcastConst.ACTION_STEERING_WHEEL_CONTROL)
            addAction(AABroadcastConst.ACTION_OPEN_SPLIT_PICKER)
            addAction(AABroadcastConst.ACTION_SPLIT_STATE_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)
        isControlReceiverRegistered = true
    }
}
