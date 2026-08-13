package io.github.nitsuya.aa.display.ui.window

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.*
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.allViews
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.WindowControllerBinding
import io.github.nitsuya.aa.display.ui.aa.split.SplitDisplayController
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.aa.display.xposed.util.RomUtil
import java.lang.reflect.Method
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt


class DisplayWindow(
      private val mContext: Context
    , private val displayAdapter: SplitDisplayController
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

    private var mControllerStatus = false
    private var mControllerCollapsed = true
    private var mControllerDockRight = true
    private val mControllerPeekPx by lazy { (mContext.resources.displayMetrics.density * 14f).toInt() }

    private var mDestroyJob: Job? = null
    private var mChangeAlphaCountDownTimer = object : CountDownTimer(5000,5000){
        override fun onFinish() {
            mControllerBinding?.apply {
                ViewCompat.animate(root).setDuration(500).alpha(0.5F).start()
            }
        }
        override fun onTick(millisUntilFinished: Long) {}
    }

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
            // Always watch phone screen transitions so OWN_DISPLAY_GROUP is re-asserted
            // when Samsung DreamManager tries to DOZE the AA virtual display with the phone.
            if (!mMiuiReceiverRegistered) {
                try {
                    ContextCompat.registerReceiver(
                        mContext,
                        this,
                        addAction(IntentFilter()),
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                    mMiuiReceiverRegistered = true
                } catch (e: Throwable) {
                    log(TAG, "register SCREEN_ON/OFF failed:", e)
                }
            }
            if (isSupportInteractive) {
                onReceive(mContext, if(Instances.powerManager.isInteractive) Intent.ACTION_SCREEN_ON else Intent.ACTION_SCREEN_OFF)
            }
            // Hold a display-scoped SCREEN_BRIGHT lock for each AA VD group so
            // Samsung OWN_DISPLAY_GROUP does not DOZE the car virtual displays.
            acquireMonitor()
            startKeepAwakeLoop()
            keepVirtualDisplayAwake("init", forceWake = true)
            // ensureHooked: do not reinstall/clear map on AA reconnect (onResume → init).
            if (AndroidHook.isReadyForSystemHooks()) {
                AndroidHook.VdDensityPin.ensureHooked()
            }
        }
        fun release(){
            stopKeepAwakeLoop()
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
            AndroidHook.VdDensityPin.unHook()
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
     * Resolves overload by name/arity so A14–A16 signature churn does not hard-fail.
     */
    private fun userActivityOnDisplay(displayId: Int, event: Int) {
        try {
            val service = iPowerManagerService
                ?: PowerManager::class.java.getDeclaredField("mService").apply {
                    isAccessible = true
                }.get(Instances.powerManager)?.also { iPowerManagerService = it }
                ?: return
            val method = iPowerManagerUserActivity ?: resolveUserActivityMethod(service.javaClass)
                ?.also { iPowerManagerUserActivity = it }
                ?: return
            invokeUserActivity(method, service, displayId, event)
        } catch (e: Throwable) {
            log(TAG, "IPowerManager.userActivity(display=$displayId) failed:", e)
        }
    }

    private fun resolveUserActivityMethod(serviceClass: Class<*>): Method? {
        val candidates = serviceClass.methods.filter { it.name == "userActivity" }
        // Prefer (int displayId, long time, int event, int flags)
        candidates.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 4 &&
                p[0] == Int::class.javaPrimitiveType &&
                p[1] == Long::class.javaPrimitiveType &&
                p[2] == Int::class.javaPrimitiveType &&
                p[3] == Int::class.javaPrimitiveType
        }?.let { return it }
        // (long time, int event, int flags) — no displayId
        candidates.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 3 &&
                p[0] == Long::class.javaPrimitiveType &&
                p[1] == Int::class.javaPrimitiveType &&
                p[2] == Int::class.javaPrimitiveType
        }?.let { return it }
        // (long time, boolean noChangeLights) legacy
        candidates.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 2 &&
                p[0] == Long::class.javaPrimitiveType &&
                p[1] == Boolean::class.javaPrimitiveType
        }?.let { return it }
        return candidates.firstOrNull()
    }

    private fun invokeUserActivity(method: Method, service: Any, displayId: Int, event: Int) {
        val now = SystemClock.uptimeMillis()
        when (method.parameterTypes.size) {
            4 -> method.invoke(service, displayId, now, event, 0)
            3 -> method.invoke(service, now, event, 0)
            2 -> method.invoke(service, now, false)
            else -> method.invoke(service, *Array(method.parameterCount) { i ->
                val t = method.parameterTypes[i]
                when {
                    t == Int::class.javaPrimitiveType && i == 0 -> displayId
                    t == Long::class.javaPrimitiveType -> now
                    t == Int::class.javaPrimitiveType -> event
                    t == Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            })
        }
    }

    /** Wake the phone panel without deprecated ACQUIRE_CAUSES_WAKEUP wake locks. */
    private fun pulsePhoneWake() {
        try {
            userActivityOnDisplay(Display.DEFAULT_DISPLAY, USER_ACTIVITY_EVENT_TOUCH)
        } catch (e: Throwable) {
            log(TAG, "pulsePhoneWake failed:", e)
        }
    }

    init {
        runCatching {
            with(ContextThemeWrapper(mContext, R.style.Theme_AADisplay_Window)){
                mControllerBinding = WindowControllerBinding.inflate(LayoutInflater.from(this))
            }
        }.onFailure {
            log(TAG, "init: new window failed may you forget reboot", it)
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
        }
        showController()
    }

    private fun initLayoutParams() {
        mControllerLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
             WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    suspend fun onResume(){
        interactiveMonitor.init()
        mDestroyJob?.cancelAndJoin()
        mControllerBinding?.apply {
            tvDestroyTime.visibility = View.GONE
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

        mControllerBinding?.apply {
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

    /** Best-effort wake of the phone panel before destroying overlays. */
    private fun restorePhoneDisplayPower() {
        try {
            pulsePhoneWake()
        } catch (e: Throwable) {
            log(TAG, "restorePhoneDisplayPower failed:", e)
        }
    }

    private fun showController(){
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
        hideController()
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
