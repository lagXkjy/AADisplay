package io.github.nitsuya.aa.display.ui.window

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.*
import androidx.core.view.ViewCompat
import androidx.core.view.allViews
import androidx.core.view.updateLayoutParams
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.WindowControllerBinding
import io.github.nitsuya.aa.display.databinding.WindowMirrorBinding
import io.github.nitsuya.aa.display.ui.aa.split.SplitDisplayController
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.util.rewriteMotionEvent
import io.github.nitsuya.aa.display.xposed.TipUtil
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.aa.display.xposed.util.RomUtil
import io.github.nitsuya.template.bases.runIO
import io.github.nitsuya.template.bases.runMain
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt


class DisplayWindow(
      private val mContext: Context
    , private val displayAdapter: SplitDisplayController
    , private var mDisplayWidth: Int
    , private var mDisplayHeight: Int
    , private var mDensityDpi: Int
): View.OnTouchListener {
    companion object {
        private const val TAG = "AADisplay_DisplayWindow"
        /** Keep OWN_DISPLAY_GROUP user-activity from timing out / dozing on Samsung. */
        private const val KEEP_AWAKE_INTERVAL_MS = 15_000L
        private const val TOUCH_KEEP_AWAKE_MIN_INTERVAL_MS = 1_000L
        /** PowerManager.USER_ACTIVITY_EVENT_TOUCH */
        private const val USER_ACTIVITY_EVENT_TOUCH = 2
        /** PowerManager.USER_ACTIVITY_EVENT_OTHER */
        private const val USER_ACTIVITY_EVENT_OTHER = 0
    }

    private var mControllerBinding: WindowControllerBinding? = null
    private lateinit var mControllerLayoutParams: WindowManager.LayoutParams

    private var mMirrorBinding: WindowMirrorBinding? = null
    private lateinit var mMirrorLayoutParams: WindowManager.LayoutParams

    private var mControllerStatus = false
    private var mMirrorStatus = false
    private var mControllerCollapsed = true
    private var mControllerDockRight = true
    private val mControllerPeekPx by lazy { (mContext.resources.displayMetrics.density * 14f).toInt() }

    private var mDisplayRatio = 1f
    private var mDisplayPower = true

    private var mDestroyJob: Job? = null
    private var mChangeAlphaCountDownTimer = object : CountDownTimer(5000,5000){
        override fun onFinish() {
            mControllerBinding?.apply {
                ViewCompat.animate(root).setDuration(500).alpha(0.5F).start()
            }
        }
        override fun onTick(millisUntilFinished: Long) {}
    }

    private var mScreenOffReplaceLockScreen = false
    /** Seconds to keep dual VD after AA disconnect before destroy. */
    private val mDelayDestroyTime = 180

    private val isSupportInteractive = RomUtil.isMiui()
    private var mKeepAwakeJob: Job? = null
    private var mLastTouchKeepAwakeAt = 0L
    private var mMiuiReceiverRegistered = false
    private var iPowerManagerService: Any? = null
    private var iPowerManagerUserActivity: java.lang.reflect.Method? = null

    /**
     * Both split VDs use OWN_DISPLAY_GROUP, so each has an independent power group.
     * Keeping only the primary awake leaves the secondary black while audio continues.
     */
    private fun aaVirtualDisplayIds(): IntArray {
        val primary = displayAdapter.primaryDisplayId
        val secondary = displayAdapter.secondaryDisplayId
        return when {
            primary == Display.INVALID_DISPLAY && secondary == Display.INVALID_DISPLAY -> intArrayOf()
            secondary == Display.INVALID_DISPLAY -> intArrayOf(primary)
            primary == Display.INVALID_DISPLAY -> intArrayOf(secondary)
            else -> intArrayOf(primary, secondary)
        }
    }

    private var interactiveMonitor = object: BroadcastReceiver(){
        private val monitorLocks = mutableMapOf<Int, PowerManager.WakeLock>()
        private val wakePulseLocks = mutableMapOf<Int, PowerManager.WakeLock>()

        fun addAction(intentFilter: IntentFilter): IntentFilter {
            return intentFilter.apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        }
        override fun onReceive(context: Context, intent: Intent) {
            intent.action?.let { action ->
                onReceive(context, action)
            }
        }
        fun onReceive(context: Context, action: String){
            if(isSupportInteractive) {
                try {
                    when(action){
                        Intent.ACTION_SCREEN_ON -> Settings.Secure.putInt(context.contentResolver, "synergy_mode", 0)
                        Intent.ACTION_SCREEN_OFF -> Settings.Secure.putInt(context.contentResolver, "synergy_mode", 1)
                    }
                } catch (_: Throwable) {}
            }
            // Phone screen policy must not blank the AA virtual display group.
            keepVirtualDisplayAwake("phone-$action", forceWake = true)
        }

        private fun newDisplayWakeLock(levelAndFlags: Int, tagSuffix: String, displayId: Int): PowerManager.WakeLock {
            return Instances.powerManagerHidden.newWakeLock(
                levelAndFlags,
                "${BuildConfig.APPLICATION_ID}:$tagSuffix:$displayId",
                displayId
            ).apply { setReferenceCounted(false) }
        }

        private fun releaseLockMap(locks: MutableMap<Int, PowerManager.WakeLock>, label: String) {
            locks.keys.toList().forEach { displayId ->
                try {
                    locks.remove(displayId)?.let { lock ->
                        if (lock.isHeld) lock.release()
                    }
                } catch (e: Throwable) {
                    log(TAG, "VD $label release failed display=$displayId:", e)
                }
            }
        }

        private fun pruneStaleLocks(activeIds: Set<Int>) {
            (monitorLocks.keys - activeIds).forEach { displayId ->
                try {
                    monitorLocks.remove(displayId)?.let { if (it.isHeld) it.release() }
                } catch (_: Throwable) {}
            }
            (wakePulseLocks.keys - activeIds).forEach { displayId ->
                try {
                    wakePulseLocks.remove(displayId)?.let { if (it.isHeld) it.release() }
                } catch (_: Throwable) {}
            }
        }

        fun acquireMonitor() {
            val ids = aaVirtualDisplayIds()
            if (ids.isEmpty()) return
            val active = ids.toSet()
            pruneStaleLocks(active)
            for (displayId in ids) {
                try {
                    val lock = monitorLocks.getOrPut(displayId) {
                        newDisplayWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                            "Monitor",
                            displayId
                        )
                    }
                    if (!lock.isHeld) {
                        lock.acquire()
                        log(TAG, "VD Monitor wake lock acquired display=$displayId")
                    }
                } catch (e: Throwable) {
                    log(TAG, "VD Monitor acquire failed display=$displayId:", e)
                }
            }
        }

        fun pulseWake(displayId: Int) {
            try {
                val lock = wakePulseLocks.getOrPut(displayId) {
                    newDisplayWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "VdWake",
                        displayId
                    )
                }
                if (!lock.isHeld) {
                    lock.acquire(2_000L)
                }
            } catch (e: Throwable) {
                log(TAG, "VD wakePulse failed display=$displayId:", e)
            }
        }

        fun releaseMonitor() {
            releaseLockMap(monitorLocks, "Monitor")
            releaseLockMap(wakePulseLocks, "VdWake")
        }
        fun init(){
            if(mScreenOffReplaceLockScreen){
                AndroidHook.Power.hook()
            }
            // Always watch phone screen transitions so OWN_DISPLAY_GROUP is re-asserted
            // when Samsung DreamManager tries to DOZE the AA virtual display with the phone.
            if (!mMiuiReceiverRegistered) {
                try {
                    mContext.registerReceiver(this, addAction(IntentFilter()))
                    mMiuiReceiverRegistered = true
                } catch (e: Throwable) {
                    log(TAG, "register SCREEN_ON/OFF failed:", e)
                }
            }
            if (isSupportInteractive) {
                onReceive(mContext, if(Instances.powerManager.isInteractive) Intent.ACTION_SCREEN_ON else Intent.ACTION_SCREEN_OFF)
            }
            // Always hold a display-scoped SCREEN_BRIGHT lock for each AA VD group.
            // MIUI synergy / ScreenOffReplace only affect the phone panel; without this,
            // Samsung OWN_DISPLAY_GROUP still DOZEs the car virtual displays.
            acquireMonitor()
            startKeepAwakeLoop()
            keepVirtualDisplayAwake("init", forceWake = true)
            // ensureHooked: do not reinstall/clear map on AA reconnect (onResume → init).
            if (AndroidHook.isReadyForSystemHooks()) {
                AndroidHook.FuckAppUseApplicationContext.ensureHooked()
            }
        }
        fun release(){
            stopKeepAwakeLoop()
            if(mScreenOffReplaceLockScreen){
                AndroidHook.Power.unHook()
            }
            if (mMiuiReceiverRegistered) {
                try {
                    mContext.unregisterReceiver(this)
                } catch (_: Throwable) {}
                mMiuiReceiverRegistered = false
            }
            if (isSupportInteractive) {
                try {
                    Settings.Secure.putInt(mContext.contentResolver, "synergy_mode", 0)
                } catch (_: Throwable) {}
            }
            releaseMonitor()
            AndroidHook.FuckAppUseApplicationContext.unHook()
        }
    }

    private fun startKeepAwakeLoop() {
        if (mKeepAwakeJob?.isActive == true) return
        mKeepAwakeJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(KEEP_AWAKE_INTERVAL_MS)
                keepVirtualDisplayAwake("heartbeat", forceWake = false)
            }
        }
    }

    private fun stopKeepAwakeLoop() {
        mKeepAwakeJob?.cancel()
        mKeepAwakeJob = null
    }

    /**
     * Keep / restore power for both AA virtual display groups so the car UI does not
     * stay black after phone sleep, doze, or OWN_DISPLAY_GROUP user-activity timeout.
     */
    fun keepVirtualDisplayAwake(reason: String, forceWake: Boolean = false) {
        val displayIds = aaVirtualDisplayIds()
        if (displayIds.isEmpty()) return
        try {
            interactiveMonitor.acquireMonitor()
            val event = if (forceWake) USER_ACTIVITY_EVENT_TOUCH else USER_ACTIVITY_EVENT_OTHER
            for (displayId in displayIds) {
                userActivityOnDisplay(displayId, event)
                if (forceWake) {
                    interactiveMonitor.pulseWake(displayId)
                }
            }
        } catch (e: Throwable) {
            log(TAG, "keepVirtualDisplayAwake[$reason] failed:", e)
        }
    }

    /** Throttled keep-awake for AA touch / key forwarding. */
    fun onVirtualDisplayUserInteraction() {
        val now = SystemClock.uptimeMillis()
        if (now - mLastTouchKeepAwakeAt < TOUCH_KEEP_AWAKE_MIN_INTERVAL_MS) return
        mLastTouchKeepAwakeAt = now
        keepVirtualDisplayAwake("interaction", forceWake = true)
    }

    /**
     * IPowerManager.userActivity(displayId, …) — PowerManager only forwards the
     * context display id, which is useless for OWN_DISPLAY_GROUP virtual displays.
     */
    private fun userActivityOnDisplay(displayId: Int, event: Int) {
        try {
            val service = iPowerManagerService
                ?: PowerManager::class.java.getDeclaredField("mService").apply {
                    isAccessible = true
                }.get(Instances.powerManager)?.also { iPowerManagerService = it }
                ?: return
            val method = iPowerManagerUserActivity
                ?: service.javaClass.getMethod(
                    "userActivity",
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ).also { iPowerManagerUserActivity = it }
            method.invoke(service, displayId, SystemClock.uptimeMillis(), event, 0)
        } catch (e: Throwable) {
            log(TAG, "IPowerManager.userActivity(display=$displayId) failed:", e)
        }
    }



    init {
        runCatching {
            with(ContextThemeWrapper(mContext, R.style.Theme_AADisplay_Window)){
                mControllerBinding = WindowControllerBinding.inflate(LayoutInflater.from(this))
                mMirrorBinding = WindowMirrorBinding.inflate(LayoutInflater.from(this)).apply {
                    llRecentTask.setPadding(0, getStatusBarHeight(), 0, 0)
                }
            }
        }.onFailure {
            log(TAG, "init: new window failed may you forget reboot", it)
            TipUtil.showToast("new window failed\nmay you forget reboot")
        }.onSuccess {
            doInit()
        }
        interactiveMonitor.init()
    }

    fun doInit() {
        initLayoutParams()
        mControllerBinding?.apply {
            root.allViews.forEach {
                it.setOnTouchListener(this@DisplayWindow)
            }
            ibHandle.setOnClickListener {
                expandController()
            }
            ibHideController.setOnClickListener {
                collapseController()
            }
            if(mScreenOffReplaceLockScreen){
                ibExtinguish.visibility = View.VISIBLE
                ibExtinguish.setOnClickListener {
                    toggleDisplayPower(false)
                }
            }
            ibMirrorDisplay.setOnClickListener {
                hideController()
                showMirror()
            }
            ibMirrorDisplay.setOnLongClickListener {
                collapseController()
                true
            }
            ibExpand.setOnClickListener {
                expandController()
            }
        }
        mMirrorBinding?.apply {
            arrayOf(vHeightUmbrella1, vHeightUmbrella2).forEach {
                it.setOnClickListener {
                    hideMirror()
                    showController()
                }
            }
            ibBack.setOnClickListener {
                displayAdapter.onPressKey(KeyEvent.KEYCODE_BACK)
            }
            ibRecentTask.setOnClickListener { v ->
                val tapCount = v.getTag(R.id.tap_count) as? Int ?: 0
                v.setTag(R.id.tap_count,   tapCount + 1)
                if(tapCount > 0) return@setOnClickListener
                runIO {
                    delay(300)
                    runMain {
                        when(v.getTag(R.id.tap_count) as? Int ?: 0){
                            1 -> toggleRecentTask()
                            2 -> displayAdapter.moveSecondTaskToFront()
                        }
                        v.setTag(R.id.tap_count,  0)
                    }
                }
            }
            tvVirtualDisplayInfo.text = "$mDisplayWidth*$mDisplayHeight,$mDensityDpi"
            RecentTaskUiHelper.wireThreeColumnRecents(
                left = rvRecentTaskLeft,
                center = rvRecentTaskCenter,
                right = rvRecentTaskRight,
                onExit = ::hideRecentTask,
                focusedPaneProvider = { displayAdapter.mFocusedPane },
                setFocusedPane = { displayAdapter.setFocusedPane(it) },
            )
        }
        updateDisplaySize()
        mMirrorBinding?.apply {
            bindMirrorSurface(svMirrorPrimary, SplitPane.PRIMARY)
            bindMirrorSurface(svMirrorSecondary, SplitPane.SECONDARY)
        }
        showController()
    }

    private fun bindMirrorSurface(surfaceView: SurfaceView, pane: Int) {
        surfaceView.setOnTouchListener { _, event ->
            val newEvent = rewriteMotionEvent(
                source = event,
                divideCoordsBy = mDisplayRatio,
            )
            displayAdapter.setFocusedPane(pane)
            displayAdapter.onTouchPane(pane, newEvent)
            newEvent.recycle()
            true
        }
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                displayAdapter.addMirrorPane(pane, surfaceView.surfaceControl)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                displayAdapter.removeMirrorPane(pane, surfaceView.surfaceControl)
            }
        })
    }

    private fun initLayoutParams() {
        mControllerLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
             WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                or (if(mScreenOffReplaceLockScreen) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        mMirrorLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
             WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                or (if(mScreenOffReplaceLockScreen) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0)
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            ,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    fun onSplitRatioChanged() {
        updateDisplaySize()
    }

    private fun updateDisplaySize(){
        mControllerBinding?.apply {
            ibMirrorDisplay.visibility = View.VISIBLE
        }
        mDisplayRatio = Instances.windowManager.currentWindowMetrics.bounds.let {
            (it.width().toFloat() / mDisplayWidth).coerceAtMost(it.height().toFloat() / mDisplayHeight)
        }
        val ratio = SplitPane.clampRatio(displayAdapter.mRatio)
        val gap = (6 * mContext.resources.displayMetrics.density).toInt()
        val sideBySide = displayAdapter.isSideBySide
        mMirrorBinding?.apply {
            llMirrorSplit.orientation =
                if (sideBySide) android.widget.LinearLayout.HORIZONTAL
                else android.widget.LinearLayout.VERTICAL
            vMirrorDivider.updateLayoutParams {
                if (sideBySide) {
                    width = gap
                    height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                } else {
                    width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    height = gap
                }
            }
            if (sideBySide) {
                val primaryW = ((mDisplayWidth - gap) * ratio).toInt().coerceAtLeast(1)
                val secondaryW = (mDisplayWidth - gap - primaryW).coerceAtLeast(1)
                svMirrorPrimary.holder.setFixedSize(primaryW, mDisplayHeight)
                svMirrorSecondary.holder.setFixedSize(secondaryW, mDisplayHeight)
                svMirrorPrimary.updateLayoutParams {
                    width = (primaryW * mDisplayRatio).toInt()
                    height = (mDisplayHeight * mDisplayRatio).toInt()
                }
                svMirrorSecondary.updateLayoutParams {
                    width = (secondaryW * mDisplayRatio).toInt()
                    height = (mDisplayHeight * mDisplayRatio).toInt()
                }
            } else {
                val primaryH = ((mDisplayHeight - gap) * ratio).toInt().coerceAtLeast(1)
                val secondaryH = (mDisplayHeight - gap - primaryH).coerceAtLeast(1)
                svMirrorPrimary.holder.setFixedSize(mDisplayWidth, primaryH)
                svMirrorSecondary.holder.setFixedSize(mDisplayWidth, secondaryH)
                svMirrorPrimary.updateLayoutParams {
                    width = (mDisplayWidth * mDisplayRatio).toInt()
                    height = (primaryH * mDisplayRatio).toInt()
                }
                svMirrorSecondary.updateLayoutParams {
                    width = (mDisplayWidth * mDisplayRatio).toInt()
                    height = (secondaryH * mDisplayRatio).toInt()
                }
            }
        }
    }

    suspend fun onResume(width: Int, height: Int){
        mDisplayWidth = width
        mDisplayHeight = height
        mMirrorBinding?.tvVirtualDisplayInfo?.text = "$mDisplayWidth*$mDisplayHeight,$mDensityDpi"
        interactiveMonitor.init()
        mDestroyJob?.cancelAndJoin()
        updateDisplaySize()
        mControllerBinding?.apply {
            tvDestroyTime.visibility = View.GONE
            ibMirrorDisplay.visibility = View.VISIBLE
        }
        showController()
    }

    suspend fun onDestroyPromptly() {
        restorePhoneDisplayPower()
        interactiveMonitor.release()
        mDestroyJob?.cancelAndJoin()
        close()
    }
    suspend fun onDestroy(onDestroySucceed: () -> Unit) {
        restorePhoneDisplayPower()
        interactiveMonitor.release()
        mDestroyJob?.cancelAndJoin()

        if(mDelayDestroyTime == 0){
            close()
            onDestroySucceed()
            return
        }
        mControllerBinding?.apply {
            ibMirrorDisplay.visibility = View.GONE
            tvDestroyTime.setOnClickListener {
                mDestroyJob?.cancel()
                close()
                onDestroySucceed()
            }
            mDestroyJob = flow {
                for (i in mDelayDestroyTime downTo 0) {
                    emit(i)
                    delay(1000)
                }
            }.onStart {
                tvDestroyTime.visibility = View.VISIBLE
            }.onEach {
                if(mControllerStatus){
                    tvDestroyTime.text = "${it}S"
                }
            }.onCompletion {
                if(it == null){
                    close()
                    onDestroySucceed()
                }
            }.launchIn(CoroutineScope(Dispatchers.Main))
        }
    }

    /** Restore phone panel power only (ScreenOffReplace / legacy wakeup). */
    private fun restorePhoneDisplayPower() {
        try {
            if (mScreenOffReplaceLockScreen) {
                mDisplayPower = true
                SurfaceControlHidden.setDisplayPowerMode(
                    SurfaceControlHidden.getInternalDisplayToken(),
                    SurfaceControlHidden.POWER_MODE_NORMAL
                )
            } else {
                Instances.powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "${BuildConfig.APPLICATION_ID}:wakeup"
                ).apply {
                    acquire()
                    release()
                }
            }
        } catch (e: Throwable) {
            log(TAG, "restorePhoneDisplayPower failed:", e)
        }
    }

    fun toggleDisplayPower(displayPower: Boolean = !mDisplayPower){
        try {
            if(mScreenOffReplaceLockScreen){
                mDisplayPower = displayPower
                if(mDisplayPower){
                    SurfaceControlHidden.setDisplayPowerMode(SurfaceControlHidden.getInternalDisplayToken(), SurfaceControlHidden.POWER_MODE_NORMAL)
                } else {
                    SurfaceControlHidden.setDisplayPowerMode(SurfaceControlHidden.getInternalDisplayToken(), SurfaceControlHidden.POWER_MODE_OFF)
                }
            } else if (displayPower) {
                // Wake phone panel only when explicitly turning power on (legacy path).
                Instances.powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "${BuildConfig.APPLICATION_ID}:wakeup").apply {
                    acquire()
                    release()
                }
            }
            // Always restore the AA virtual display group — this is what the car sees.
            keepVirtualDisplayAwake("toggleDisplayPower:$displayPower", forceWake = displayPower)
        } catch (e : Throwable){
            log(TAG, "", e)
        }
    }

    private fun showController(){
        if(mMirrorStatus){
            hideMirror()
        }
        if(mControllerStatus) return
        mControllerBinding?.apply {
            tryOrNull { Instances.windowManager.addView(root, mControllerLayoutParams) }
            applyControllerCollapsedState()
            root.post {
                applyControllerDockPosition()
            }
            mChangeAlphaCountDownTimer.start()
            mControllerStatus = true
        }
    }
    private fun hideController(){
        if(!mControllerStatus) return
        mControllerBinding?.apply {
            tryOrNull { Instances.windowManager.removeView(root) }
            mControllerStatus = false
        }
    }

    private fun showMirror(){
        if(mMirrorStatus) return
        hideRecentTask()
        mMirrorBinding?.apply {
            tryOrNull { Instances.windowManager.addView(root, mMirrorLayoutParams) }
            mMirrorStatus = true
        }
    }
    private fun hideMirror(){
        if(!mMirrorStatus) return
        mMirrorBinding?.apply {
            tryOrNull { Instances.windowManager.removeView(root) }
            mMirrorStatus = false
        }
    }

    private fun toggleRecentTask(){
        mMirrorBinding?.apply {
            if(llRecentTask.visibility == View.VISIBLE){
                hideRecentTask()
                return
            }
            llRecentTask.visibility = View.VISIBLE
            vHeightUmbrella1.visibility = View.GONE
            runIO {
                displayAdapter.getRecentTask().also {recentTask ->
                    runMain {
                        (rvRecentTaskLeft.adapter as DisplayRecyclerViewAdapter)?.setItems(recentTask.primaryDisplay)
                        (rvRecentTaskCenter.adapter as DisplayRecyclerViewAdapter)?.setItems(recentTask.secondaryDisplay)
                        (rvRecentTaskRight.adapter as DisplayRecyclerViewAdapter)?.setItems(recentTask.mainDisplay)
                    }
                }
            }
        }
    }
    private fun hideRecentTask(){
        mMirrorBinding?.apply {
            llRecentTask.visibility = View.GONE
            vHeightUmbrella1.visibility = View.VISIBLE
            (rvRecentTaskLeft.adapter as DisplayRecyclerViewAdapter)?.clearItem()
            (rvRecentTaskCenter.adapter as DisplayRecyclerViewAdapter)?.clearItem()
            (rvRecentTaskRight.adapter as DisplayRecyclerViewAdapter)?.clearItem()
        }
    }

    private fun expandController(){
        setControllerCollapsed(false)
    }
    private fun collapseController(){
        setControllerCollapsed(true)
    }

    private fun setControllerCollapsed(collapsed: Boolean) {
        mControllerCollapsed = collapsed
        applyControllerCollapsedState()
        mControllerBinding?.root?.post {
            applyControllerDockPosition()
        }
    }

    private fun applyControllerCollapsedState() {
        mControllerBinding?.apply {
            cvPanel.visibility = if (mControllerCollapsed) View.GONE else View.VISIBLE
            cvHandle.visibility = if (mControllerCollapsed) View.VISIBLE else View.GONE
            llPanel.visibility = if (mControllerCollapsed) View.GONE else View.VISIBLE
            ibExpand.visibility = View.GONE
        }
    }

    private fun applyControllerDockPosition() {
        val binding = mControllerBinding ?: return
        val displayMetrics = mContext.resources.displayMetrics
        val visibleWidth = getControllerVisibleWidth(binding)
        val visibleHeight = getControllerVisibleHeight(binding)
        if (visibleWidth <= 0 || visibleHeight <= 0) return

        mControllerLayoutParams.x = if (mControllerCollapsed) {
            if (mControllerDockRight) {
                displayMetrics.widthPixels - mControllerPeekPx
            } else {
                -(visibleWidth - mControllerPeekPx)
            }
        } else {
            if (mControllerDockRight) {
                displayMetrics.widthPixels - visibleWidth
            } else {
                0
            }
        }
        mControllerLayoutParams.y = mControllerLayoutParams.y.coerceIn(
            0,
            (displayMetrics.heightPixels - visibleHeight).coerceAtLeast(0)
        )
        tryOrNull { Instances.windowManager.updateViewLayout(binding.root, mControllerLayoutParams) }
    }

    private fun getControllerVisibleWidth(binding: WindowControllerBinding): Int {
        return if (mControllerCollapsed) {
            binding.cvHandle.width.takeIf { it > 0 } ?: binding.cvHandle.measuredWidth
        } else {
            binding.cvPanel.width.takeIf { it > 0 } ?: binding.cvPanel.measuredWidth
        }
    }

    private fun getControllerVisibleHeight(binding: WindowControllerBinding): Int {
        return if (mControllerCollapsed) {
            binding.cvHandle.height.takeIf { it > 0 } ?: binding.cvHandle.measuredHeight
        } else {
            binding.cvPanel.height.takeIf { it > 0 } ?: binding.cvPanel.measuredHeight
        }
    }

    private fun close() {
        mChangeAlphaCountDownTimer.cancel()
        hideMirror()
        hideController()
    }

    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId: Int = mContext.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = mContext.resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        return mControllerBinding?.run {
            var isDrag = false
            when(event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.apply {
                        setTag(R.id.is_drag, false)
                        setTag(R.id.drag_last_x, event.rawX)
                        setTag(R.id.drag_last_y, event.rawY)
                        setTag(R.id.drag_distance, 0f)
                    }
                    mChangeAlphaCountDownTimer.cancel()
                    ViewCompat.animate(root).setDuration(200).alpha(0.9f).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    isDrag = v.getTag(R.id.is_drag) as Boolean
                    val dragMoveX = event.rawX
                    val dragMoveY = event.rawY
                    val dragLastX = (v.getTag(R.id.drag_last_x) as? Float ?: 0f)
                    val dragLastY = (v.getTag(R.id.drag_last_y) as? Float ?: 0f)
                    if(!isDrag){
                        val dragSumDistance = abs(sqrt((dragMoveX - dragLastX).pow(2) + (dragMoveY - dragLastY).pow(2))) + (v.getTag(R.id.drag_distance) as? Float ?: 0f)
                        if(dragSumDistance > 5f){
                            isDrag = true
                            v.apply {
                                setTag(R.id.is_drag, true)
                                onTouchEvent(MotionEvent.obtain(event).apply {
                                    action = MotionEvent.ACTION_CANCEL
                                })
                            }
                        } else {
                            v.setTag(R.id.drag_distance, dragSumDistance)
                        }
                    }
                    Instances.windowManager.updateViewLayout(root, mControllerLayoutParams.apply {
                        x = (dragMoveX - dragLastX + x).toInt()
                        y = (dragMoveY - dragLastY + y).toInt()
                    })
                    v.apply {
                        setTag(R.id.drag_last_x, dragMoveX)
                        setTag(R.id.drag_last_y, dragMoveY)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    isDrag = v.getTag(R.id.is_drag) as Boolean
                    val displayMetrics = mContext.resources.displayMetrics
                    mControllerDockRight = event.rawX > (displayMetrics.widthPixels / 2f)
                    applyControllerDockPosition()
                    mChangeAlphaCountDownTimer.start()
                }
            }
            return if(isDrag) true else v.onTouchEvent(event)
        } ?: false
    }



}
