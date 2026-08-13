package io.github.nitsuya.aa.display.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextParams
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.model.RecentTask
import io.github.nitsuya.aa.display.ui.aa.split.SplitDisplayController
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.ui.window.DisplayWindow
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.template.bases.runIO
import io.github.nitsuya.template.bases.runMain
import io.github.qauxv.ui.CommonContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

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
        val hasSystemContext: Boolean
            get() = ::systemContextHost.isInitialized
        var systemContext: Context
            get() = systemContextHost
            set(value) {
                if (value.params == null) {
                    log(TAG, "SystemContext.params is null; using empty ContextParams")
                }
                systemContextHost = value.createContext(value.params ?: ContextParams.Builder().build())
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
                // Soft reconnect may briefly report pre-rail-reclaim size; allow monotonic grow
                // so panes fill the reclaimed gutter, keep lock on shrink/jitter.
                val grew =
                    current.isLandscape == candidate.isLandscape &&
                        candidate.width >= current.width &&
                        candidate.height >= current.height &&
                        (candidate.width > current.width || candidate.height > current.height)
                if (grew) {
                    mLockedDisplayProfile = candidate
                    logDebug(
                        TAG,
                        "displayProfile relocked(grow): ${current.width}*${current.height},${current.densityDpi} -> ${candidate.width}*${candidate.height},${candidate.densityDpi}"
                    )
                    return candidate
                }
                logDebug(
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

        fun systemReady() {
            if (!hasSystemContext) {
                log(TAG, "systemReady skipped: systemContext not initialized")
                return
            }
            Instances.init(systemContext)
            AndroidHook.PanePresentationGuard.ensureHooked()
        }

        fun isAaVirtualDisplay(displayId: Int): Boolean {
            return mSplitController?.isAaVirtualDisplay(displayId) == true
        }

        fun panePackageForDisplay(displayId: Int): String? {
            val controller = mSplitController ?: return null
            return when (displayId) {
                controller.primaryDisplayId -> controller.mPanePackages[SplitPane.PRIMARY]
                controller.secondaryDisplayId -> controller.mPanePackages[SplitPane.SECONDARY]
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }
        }

        fun getDensityDpi(): Int {
            return mSplitController?.mDensityDpi ?: 0
        }
    }

    override fun getVersionName(): String = BuildConfig.VERSION_NAME

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
            if (!hasSystemContext) {
                log(TAG, "onCreateSplitDisplay aborted: systemContext not initialized")
                return@runMain
            }
            val profile = resolveDisplayProfile(
                width = width,
                height = height,
                densityDpi = densityDpi,
                newSession = mSplitController == null
            )
            mSplitController?.apply {
                // Soft reconnect: always cancel Delay Destroy and rebind surfaces/policies.
                mDisplayWindow?.onResume(profile.width, profile.height)
                setPaneSurface(SplitPane.PRIMARY, primarySurface)
                setPaneSurface(SplitPane.SECONDARY, secondarySurface)
                // Always kick resize/policies/ensure after surface rebind (null→live).
                onReconnected(profile.width, profile.height, profile.densityDpi)
                // Ratio is owned by divider drag / restore — do not push AA's echo back
                // unless it meaningfully differs (avoids resize thrash).
                val clamped = SplitPane.clampRatio(ratio)
                if (kotlin.math.abs(clamped - mRatio) >= 0.01f) {
                    setSplitRatio(clamped)
                }
                listener.onAvailableDisplay(primaryDisplayId, false)
                return@runMain
            }
            if (mDisplayCreateInProgress) {
                logDebug(TAG, "onCreateSplitDisplay ignored: create already in progress")
                return@runMain
            }
            mDisplayCreateInProgress = true
            SplitDisplayController(systemContext) {
                try {
                    val controller = this
                    mSplitController = controller
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
                        // Phone overlay is not needed for AA first frame; inflate after callback.
                        runMain {
                            if (mSplitController !== controller) return@runMain
                            mDisplayWindow?.onDestroyPromptly()
                            mDisplayWindow = DisplayWindow(
                                CommonContextWrapper.createAppCompatContext(systemContext),
                                controller,
                                profile.width,
                                profile.height,
                                profile.densityDpi
                            )
                        }
                    }
                } finally {
                    mDisplayCreateInProgress = false
                }
            }
        }
    }

    override fun setPaneSurface(pane: Int, surface: Surface?) {
        runMain {
            logDebug(TAG, "setPaneSurface pane=$pane surface=${surface != null}")
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

    override fun getFocusedPane(): Int {
        return mSplitController?.mFocusedPane ?: SplitPane.PRIMARY
    }

    override fun swapSplitPanes() {
        // Controller marshals onto its handler; keep off the Binder thread.
        runIO { mSplitController?.swapPanes() }
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

    override fun moveTaskId(taskId: Int, isVirtualDisplay: Boolean) {
        // Controller marshals onto its handler; keep off the Binder thread.
        runIO { mSplitController?.moveTaskId(taskId, isVirtualDisplay) }
    }

    override fun moveTaskIdToPane(taskId: Int, pane: Int) {
        runIO { mSplitController?.moveTaskIdToPane(taskId, pane) }
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

    override fun touchPrimaryPane(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            runIO { mDisplayWindow?.onVirtualDisplayUserInteraction() }
        }
        mSplitController?.onTouchPrimaryPane(event)
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
}



















