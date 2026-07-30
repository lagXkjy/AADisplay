package io.github.nitsuya.aa.display.xposed

import android.annotation.SuppressLint
import android.content.*
import android.os.*
import android.view.*
import de.robv.android.xposed.XSharedPreferences
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.model.RecentTask
import io.github.nitsuya.aa.display.ui.aa.AaVirtualDisplayAdapter
import io.github.nitsuya.aa.display.ui.window.DisplayWindow
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.SharedPreferencesAccess
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.template.bases.runIO
import io.github.nitsuya.template.bases.runMain
import io.github.qauxv.ui.CommonContextWrapper
import kotlinx.coroutines.*
import java.io.File

class CoreManagerService private constructor(): ICoreManager.Stub() {
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

        /**
         * Same keys as the app settings page. Prefer package XSharedPreferences; if SELinux blocks
         * app_data_file, fall back to the XML copy published by [SharedPreferencesAccess.publishHookMirror].
         * Retries while null so a mirror published later in the same boot is picked up.
         */
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
            // 1) Durable system mirror (survives reboot; system_server can read).
            loadMirrorPreferences()?.let { return it }

            // 2) Package path / LSPosed path (may work in some processes).
            try {
                val pkgPrefs = XSharedPreferences(BuildConfig.APPLICATION_ID, AADisplayConfig.ConfigName)
                runCatching { pkgPrefs.reload() }
                // Do not require file.canRead(): SELinux often lies for app_data_file, while reload
                // may still succeed via LSPosed. Prefer non-empty content.
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
        private var mAaVirtualDisplayAdapter: AaVirtualDisplayAdapter? = null
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

        /**
         * Lock profile per active AA display session.
         * - New display session: (re)learn from current size.
         * - Reconnect within same session: keep locked size unless orientation flips.
         */
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

        fun getDisplayId(): Int{
            return mAaVirtualDisplayAdapter?.mDisplayId ?: Display.INVALID_DISPLAY
        }

        fun getDensityDpi(): Int{
            return mAaVirtualDisplayAdapter?.mDensityDpi ?: 0
        }
    }

    override fun getVersionName(): String {
        return BuildConfig.VERSION_NAME
    }

    override fun getVersionCode(): Int {
        return BuildConfig.VERSION_CODE
    }

    override fun getUid(): Int {
        return Process.myUid()
    }

    override fun getBuildTime(): Long {
        return BuildConfig.BUILD_TIME
    }

    override fun onCreateDisplay(width: Int, height: Int, densityDpi: Int, surface: Surface?, listener: IVirtualDisplayCreatedListener){
        runMain {
            log(
                TAG,
                "onCreateDisplay request: ${width}x$height,$densityDpi surface=${surface != null} existing=${mAaVirtualDisplayAdapter != null}"
            )
            val profile = resolveDisplayProfile(
                width = width,
                height = height,
                densityDpi = densityDpi,
                newSession = mAaVirtualDisplayAdapter == null
            )
            mAaVirtualDisplayAdapter?.apply {
                onReconnected(profile.width, profile.height, profile.densityDpi)
                setSurface(surface)
                mDisplayWindow?.onResume(profile.width, profile.height)
                listener.onAvailableDisplay(this.mDisplayId, false)
                return@runMain
            }
            if (mDisplayCreateInProgress) {
                log(
                    TAG,
                    "onCreateDisplay ignored: display create already in progress for ${profile.width}x${profile.height},${profile.densityDpi}"
                )
                return@runMain
            }
            config?.apply {
                reload()
                log(TAG, "config: ${this.all.map { "${it.key}=${it.value}[${it.value?.javaClass?.name}]" }.joinToString() }")
            }
            mDisplayCreateInProgress = true
            AaVirtualDisplayAdapter(systemContext, config){
                try {
                    mAaVirtualDisplayAdapter = this
                    onConnected(profile.width, profile.height, profile.densityDpi, surface){ displayId ->
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

    override fun setDisplaySurface(surface: Surface?){
        runMain {
            log(TAG, "setDisplaySurface: surface=${surface != null}, display=${mAaVirtualDisplayAdapter?.mDisplayId ?: Display.INVALID_DISPLAY}")
            mAaVirtualDisplayAdapter?.setSurface(surface)
        }
    }

    override fun onDestroyDisplay(){
        runMain {
            mDisplayWindow?.onDestroy {
                mAaVirtualDisplayAdapter?.onDestroy()
                mDisplayWindow = null
                mAaVirtualDisplayAdapter = null
                mDisplayCreateInProgress = false
                clearDisplayProfileLock()
            }
        }
    }

    override fun startLauncher() {
        runIO {
            mAaVirtualDisplayAdapter?.run {
                startLauncher()
            }
        }
    }

    override fun startActivity(packageName: String, userId: Int) {
        runIO {
            mAaVirtualDisplayAdapter?.run {
                startActivity(packageName, userId)
            }
        }
    }

    override fun startTaskId(taskId: Int, packageName: String, userId: Int) {
        runIO {
            mAaVirtualDisplayAdapter?.startTaskId(taskId, packageName, userId)
        }
    }

    override fun moveTaskId(taskId: Int, isVirtualDisplay: Boolean) {
        runIO {
            mAaVirtualDisplayAdapter?.moveTaskId(taskId, isVirtualDisplay)
        }
    }

    override fun moveTaskToFront(taskId: Int) {
        runIO {
            mAaVirtualDisplayAdapter?.moveTaskToFront(taskId)
        }
    }

    override fun moveSecondTaskToFront() {
        runIO {
            mAaVirtualDisplayAdapter?.moveSecondTaskToFront()
        }
    }

    @SuppressLint("MissingPermission")
    override fun removeTask(taskId: Int){
        runIO {
            mAaVirtualDisplayAdapter?.removeTask(taskId)
        }
    }

    override fun pressKey(action: Int) {
        runIO {
            mAaVirtualDisplayAdapter?.onPressKey(action)
        }
    }

    override fun touch(event: MotionEvent) {
        runBlocking(Dispatchers.IO) {
            mAaVirtualDisplayAdapter?.onTouch(event)
        }
    }


    override fun toggleDisplayPower() {
        runIO {
            mDisplayWindow?.toggleDisplayPower()
        }
    }

    override fun displayPower(displayPower: Boolean) {
        runIO {
            mDisplayWindow?.toggleDisplayPower(displayPower)
        }
    }

    override fun addMirror(surfaceControl: SurfaceControl) {
        runIO {
            mAaVirtualDisplayAdapter?.addMirror(surfaceControl)
        }
    }

    override fun removeMirror(surfaceControl: SurfaceControl){
        runIO {
            mAaVirtualDisplayAdapter?.removeMirror(surfaceControl)
        }
    }

    override fun getRecentTask(): RecentTask {
        return runBlocking(Dispatchers.IO){
            mAaVirtualDisplayAdapter?.getRecentTask() ?: RecentTask(emptyList(), emptyList())
        }
    }

    @SuppressLint("RestrictedApi")
    override fun testCode(action: String){

    }

    override fun toast(msg: String){
        runMain {
            TipUtil.showToast(msg)
        }
    }

    override fun printLog(tag: String, msg: String){
        runIO {
            log(tag, msg)
        }
    }
}
