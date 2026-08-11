package io.github.nitsuya.aa.display.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextParams
import android.os.Process
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import de.robv.android.xposed.XSharedPreferences
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.model.RecentTask
import io.github.nitsuya.aa.display.ui.aa.split.SplitDisplayController
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.ui.window.DisplayWindow
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.SharedPreferencesAccess
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.template.bases.runIO
import io.github.nitsuya.template.bases.runMain
import io.github.qauxv.ui.CommonContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

class CoreManagerService private constructor() : ICoreManager.Stub() {
    companion object {
        const val TAG = "CoreManagerService"

        val instance: CoreManagerService by lazy {
            CoreManagerService().apply {
                log(TAG, "AADisplay service initialized")
            }
        }

        @SuppressLint("StaticFieldLeak")
        private lateinit var systemContextHost: Context
        var systemContext: Context
            get() = systemContextHost
            set(value) {
                log(TAG, "SystemContext.params is null: ${value.params}")
                systemContextHost = value.createContext(value.params ?: ContextParams.Builder().build())
            }

        @Volatile
        private var configHolder: XSharedPreferences? = null

        val config: XSharedPreferences?
            get() {
                configHolder?.let {
                    runCatching { it.reload() }
                    return it
                }
                return loadConfigPreferences().also { configHolder = it }
            }

        private fun loadConfigPreferences(): XSharedPreferences? {
            loadMirrorPreferences()?.let { return it }
            try {
                val pkgPrefs = XSharedPreferences(BuildConfig.APPLICATION_ID, AADisplayConfig.ConfigName)
                runCatching { pkgPrefs.reload() }
                if (pkgPrefs.all.isNotEmpty()) {
                    return pkgPrefs
                }
                log(TAG, "package config empty/unreadable: ${pkgPrefs.file}")
            } catch (e: Throwable) {
                log(TAG, "config load failed:", e)
            }
            return null
        }

        private fun loadMirrorPreferences(): XSharedPreferences? {
            return try {
                val mirror = File(SharedPreferencesAccess.HOOK_MIRROR_PATH)
                if (!mirror.exists() || !mirror.canRead()) {
                    log(TAG, "hook mirror missing/unreadable: $mirror")
                    return null
                }
                XSharedPreferences(mirror).also {
                    runCatching { it.reload() }
                    log(TAG, "hook mirror loaded: keys=${it.all.size}")
                }
            } catch (e: Throwable) {
                log(TAG, "hook mirror load failed:", e)
                null
            }
        }

        private var mDisplayWindow: DisplayWindow? = null
        private var mSplitController: SplitDisplayController? = null
        private var mDisplayCreateInProgress = false

        private data class DisplayProfile(
            val width: Int,
            val height: Int,
            val densityDpi: Int
        ) {
            val isLandscape: Boolean
                get() = width >= height
        }

        private var mLockedDisplayProfile: DisplayProfile? = null

        private fun sanitizeDisplayProfile(width: Int, height: Int, densityDpi: Int): DisplayProfile {
            return DisplayProfile(
                width = width.coerceAtLeast(1),
                height = height.coerceAtLeast(1),
                densityDpi = densityDpi.coerceAtLeast(1)
            )
        }

        private fun resolveDisplayProfile(
            width: Int,
            height: Int,
            densityDpi: Int,
            newSession: Boolean
        ): DisplayProfile {
            val candidate = sanitizeDisplayProfile(width, height, densityDpi)
            val current = mLockedDisplayProfile
            if (current == null) {
                mLockedDisplayProfile = candidate
                log(TAG, "displayProfile locked: ${candidate.width}*${candidate.height},${candidate.densityDpi}")
                return candidate
            }
            if (newSession) {
                if (current != candidate) {
                    mLockedDisplayProfile = candidate
                    log(
                        TAG,
                        "displayProfile relocked(new-session): ${current.width}*${current.height},${current.densityDpi} -> ${candidate.width}*${candidate.height},${candidate.densityDpi}"
                    )
                }
                return mLockedDisplayProfile!!
            }
            if (current.isLandscape != candidate.isLandscape) {
                mLockedDisplayProfile = candidate
                log(
                    TAG,
                    "displayProfile relocked(orientation): ${current.width}*${current.height},${current.densityDpi} -> ${candidate.width}*${candidate.height},${candidate.densityDpi}"
                )
                return candidate
            }
            if (current != candidate) {
                // Soft reconnect often briefly reports the pre-rail-reclaim size (e.g. 720)
                // after we already grew to full HU (800). Allow monotonic grow so split panes
                // fill the reclaimed gutter; keep lock on shrink/jitter to avoid flicker.
                val grew =
                    current.isLandscape == candidate.isLandscape &&
                        candidate.width >= current.width &&
                        candidate.height >= current.height &&
                        (candidate.width > current.width || candidate.height > current.height)
                if (grew) {
                    mLockedDisplayProfile = candidate
                    log(
                        TAG,
                        "displayProfile relocked(grow): ${current.width}*${current.height},${current.densityDpi} -> ${candidate.width}*${candidate.height},${candidate.densityDpi}"
                    )
                    return candidate
                }
                log(
                    TAG,
                    "displayProfile keep-locked(reconnect): locked=${current.width}*${current.height},${current.densityDpi}, incoming=${candidate.width}*${candidate.height},${candidate.densityDpi}"
                )
            }
            return current
        }

        private fun clearDisplayProfileLock() {
            mLockedDisplayProfile?.also {
                log(TAG, "displayProfile cleared: ${it.width}*${it.height},${it.densityDpi}")
            }
            mLockedDisplayProfile = null
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        fun systemReady() {
            TipUtil.init(systemContext, "[AADisplay] ")
            Instances.init(systemContext)
        }

        fun isAaVirtualDisplay(displayId: Int): Boolean {
            return mSplitController?.isAaVirtualDisplay(displayId) == true
        }

        fun getDensityDpi(): Int {
            return mSplitController?.mDensityDpi ?: 0
        }
    }

    override fun getVersionName(): String = BuildConfig.VERSION_NAME

    override fun getVersionCode(): Int = BuildConfig.VERSION_CODE

    override fun getUid(): Int = Process.myUid()

    override fun getBuildTime(): Long = BuildConfig.BUILD_TIME

    override fun onCreateSplitDisplay(
        width: Int,
        height: Int,
        densityDpi: Int,
        ratio: Float,
        primarySurface: Surface?,
        secondarySurface: Surface?,
        listener: IVirtualDisplayCreatedListener
    ) {
        runMain {
            log(
                TAG,
                "onCreateSplitDisplay: ${width}x$height,$densityDpi ratio=$ratio " +
                    "existing=${mSplitController != null}"
            )
            val profile = resolveDisplayProfile(
                width = width,
                height = height,
                densityDpi = densityDpi,
                newSession = mSplitController == null
            )
            mSplitController?.apply {
                val sizeChanged =
                    profile.width != mWidth ||
                        profile.height != mHeight ||
                        profile.densityDpi != mDensityDpi
                if (sizeChanged) {
                    onReconnected(profile.width, profile.height, profile.densityDpi)
                }
                setPaneSurface(SplitPane.PRIMARY, primarySurface)
                setPaneSurface(SplitPane.SECONDARY, secondarySurface)
                // Ratio is owned by divider drag / restore — do not push AA's echo back
                // unless it meaningfully differs (avoids resize thrash).
                val clamped = SplitPane.clampRatio(ratio)
                if (kotlin.math.abs(clamped - mRatio) >= 0.01f) {
                    setSplitRatio(clamped)
                }
                if (sizeChanged) {
                    mDisplayWindow?.onResume(profile.width, profile.height)
                }
                listener.onAvailableDisplay(primaryDisplayId, false)
                return@runMain
            }
            if (mDisplayCreateInProgress) {
                logDebug(TAG, "onCreateSplitDisplay ignored: create already in progress")
                return@runMain
            }
            config?.apply {
                reload()
                logDebug(TAG, "config loaded: keys=${this.all.size}")
            }
            mDisplayCreateInProgress = true
            SplitDisplayController(systemContext, config) {
                try {
                    mSplitController = this
                    onSplitLayoutChanged = { mDisplayWindow?.onSplitRatioChanged() }
                    onConnected(
                        profile.width,
                        profile.height,
                        profile.densityDpi,
                        SplitPane.clampRatio(ratio),
                        primarySurface,
                        secondarySurface,
                    ) { displayId ->
                        listener.onAvailableDisplay(displayId, true)
                    }
                    mDisplayWindow?.onDestroyPromptly()
                    mDisplayWindow = DisplayWindow(
                        CommonContextWrapper.createAppCompatContext(systemContext),
                        this,
                        profile.width,
                        profile.height,
                        profile.densityDpi
                    )
                } finally {
                    mDisplayCreateInProgress = false
                }
            }
        }
    }

    override fun setPaneSurface(pane: Int, surface: Surface?) {
        runMain {
            log(TAG, "setPaneSurface pane=$pane surface=${surface != null}")
            mSplitController?.setPaneSurface(pane, surface)
        }
    }

    override fun setSplitRatio(ratio: Float) {
        runMain {
            mSplitController?.setSplitRatio(ratio)
            // Mirror refresh is driven by controller.onSplitLayoutChanged after a real resize.
        }
    }

    override fun getSplitRatio(): Float {
        return mSplitController?.mRatio ?: SplitPane.DEFAULT_RATIO
    }

    override fun getPanePackage(pane: Int): String? {
        return mSplitController?.getPanePackage(pane)
    }

    override fun setFocusedPane(pane: Int) {
        mSplitController?.setFocusedPane(pane)
    }

    override fun onDestroyDisplay() {
        runMain {
            mDisplayWindow?.onDestroy {
                mSplitController?.onDestroy()
                mDisplayWindow = null
                mSplitController = null
                mDisplayCreateInProgress = false
                clearDisplayProfileLock()
            }
        }
    }

    override fun startActivity(packageName: String, userId: Int) {
        runIO { mSplitController?.startActivity(packageName, userId) }
    }

    override fun startActivityOnPane(packageName: String, userId: Int, pane: Int) {
        runIO { mSplitController?.startActivityOnPane(packageName, userId, pane) }
    }

    override fun startTaskId(taskId: Int, packageName: String, userId: Int) {
        runIO { mSplitController?.startTaskId(taskId, packageName, userId) }
    }

    override fun moveTaskId(taskId: Int, isVirtualDisplay: Boolean) {
        // Controller marshals onto its handler; keep off the Binder thread.
        runIO { mSplitController?.moveTaskId(taskId, isVirtualDisplay) }
    }

    override fun moveTaskToFront(taskId: Int) {
        runIO { mSplitController?.moveTaskToFront(taskId) }
    }

    override fun moveSecondTaskToFront() {
        runIO { mSplitController?.moveSecondTaskToFront() }
    }

    @SuppressLint("MissingPermission")
    override fun removeTask(taskId: Int) {
        runIO { mSplitController?.removeTask(taskId) }
    }

    override fun restoreLastSplit() {
        runIO {
            val controller = mSplitController
            if (controller == null) {
                runMain { TipUtil.showToast("快捷分屏：无显示会话") }
                return@runIO
            }
            val result = controller.requestRestoreLastSplitManual()
            runMain {
                TipUtil.showToast(
                    when (result) {
                        SplitDisplayController.ManualRestoreResult.Started ->
                            "正在恢复上次分屏…"
                        SplitDisplayController.ManualRestoreResult.NoDisplay ->
                            "快捷分屏：无显示会话"
                        SplitDisplayController.ManualRestoreResult.NoSnapshot ->
                            "快捷分屏：没有可恢复的分屏记录"
                        SplitDisplayController.ManualRestoreResult.PackageUnavailable ->
                            "快捷分屏：左右应用不可用"
                    }
                )
            }
        }
    }

    override fun pressKey(action: Int) {
        runIO {
            mDisplayWindow?.onVirtualDisplayUserInteraction()
            mSplitController?.onPressKey(action)
        }
    }

    override fun touchPane(pane: Int, event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            runIO { mDisplayWindow?.onVirtualDisplayUserInteraction() }
            mSplitController?.setFocusedPane(pane)
        }
        mSplitController?.onTouchPane(pane, event)
    }

    override fun toggleDisplayPower() {
        runIO { mDisplayWindow?.toggleDisplayPower() }
    }

    override fun displayPower(displayPower: Boolean) {
        runIO { mDisplayWindow?.toggleDisplayPower(displayPower) }
    }

    override fun addMirrorPane(pane: Int, surfaceControl: SurfaceControl) {
        runIO { mSplitController?.addMirrorPane(pane, surfaceControl) }
    }

    override fun removeMirrorPane(pane: Int, surfaceControl: SurfaceControl) {
        runIO { mSplitController?.removeMirrorPane(pane, surfaceControl) }
    }

    override fun getRecentTask(): RecentTask {
        return runBlocking(Dispatchers.IO) {
            mSplitController?.getRecentTask() ?: RecentTask(emptyList(), emptyList(), emptyList())
        }
    }

    @SuppressLint("RestrictedApi")
    override fun testCode(action: String) {
    }

    override fun toast(msg: String) {
        runMain { TipUtil.showToast(msg) }
    }

    override fun printLog(tag: String, msg: String) {
        runIO { log(tag, msg) }
    }
}
