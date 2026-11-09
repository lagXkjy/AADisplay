package io.github.nitsuya.aa.display.xposed

import android.os.IBinder.DeathRecipient
import android.os.Parcel
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import io.github.nitsuya.aa.display.model.RecentTask
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane

object CoreManager : ICoreManager, DeathRecipient {
    private const val TAG = "CoreManager"

    @Volatile
    private var service: ICoreManager? = null

    override fun binderDied() {
        service = null
        Log.e(TAG, "Binder died")
    }

    override fun asBinder() = service?.asBinder()

    override fun getVersionName(): String? {
        return getService()?.versionName
    }

    override fun getBuildTime(): Long {
        return getService()?.buildTime ?: 0
    }

    override fun onCreateSplitDisplay(
        width: Int,
        height: Int,
        densityDpi: Int,
        ratio: Float,
        primarySurface: Surface?,
        secondarySurface: Surface?,
        listener: IVirtualDisplayCreatedListener
    ) {
        val remote = getService()
        if (remote == null) {
            Log.e(
                TAG,
                "onCreateSplitDisplay skipped; service unavailable: ${width}x$height,$densityDpi"
            )
            return
        }
        Log.i(TAG, "onCreateSplitDisplay: ${width}x$height,$densityDpi ratio=$ratio")
        remote.onCreateSplitDisplay(
            width, height, densityDpi, ratio, primarySurface, secondarySurface, listener
        )
    }

    override fun setPaneSurface(pane: Int, surface: Surface?) {
        val remote = getService()
        if (remote == null) {
            Log.e(TAG, "setPaneSurface skipped; service unavailable pane=$pane")
            return
        }
        remote.setPaneSurface(pane, surface)
    }

    override fun setSplitRatio(ratio: Float) {
        getService()?.setSplitRatio(ratio)
    }

    override fun getSplitRatio(): Float {
        return getService()?.splitRatio ?: SplitPane.DEFAULT_RATIO
    }

    override fun setSplitFullscreen(pane: Int) {
        getService()?.setSplitFullscreen(pane)
    }

    override fun getSplitFullscreenPane(): Int {
        return getService()?.splitFullscreenPane ?: SplitPane.FULLSCREEN_NONE
    }

    override fun getPanePackage(pane: Int): String? {
        return getService()?.getPanePackage(pane)
    }

    override fun getFocusedPane(): Int {
        return getService()?.focusedPane ?: SplitPane.PRIMARY
    }

    override fun swapSplitPanes() {
        getService()?.swapSplitPanes()
    }

    override fun onDestroyDisplay() {
        getService()?.onDestroyDisplay()
    }

    override fun startActivity(packageName: String, userId: Int) {
        getService()?.startActivity(packageName, userId)
    }

    override fun startActivityOnPane(packageName: String, userId: Int, pane: Int) {
        getService()?.startActivityOnPane(packageName, userId, pane)
    }

    override fun startActivityOnPaneForUser(packageName: String, userId: Int, pane: Int): Boolean {
        return getService()?.startActivityOnPaneForUser(packageName, userId, pane) ?: false
    }

    override fun getHidCursorOverlay(): FloatArray {
        return try {
            getService()?.hidCursorOverlay
                ?: floatArrayOf(0f, 0f, 0f, 0f, 0f, 0.5f, 0f, -1f, 0f, 0.5f, -1f)
        } catch (e: Throwable) {
            Log.e(TAG, "getHidCursorOverlay failed", e)
            floatArrayOf(0f, 0f, 0f, 0f, 0f, 0.5f, 0f, -1f, 0f, 0.5f, -1f)
        }
    }

    override fun getClusterArtJpeg(): ByteArray? {
        return try {
            getService()?.clusterArtJpeg
        } catch (e: Throwable) {
            Log.e(TAG, "getClusterArtJpeg failed", e)
            null
        }
    }

    override fun setVdDensityDpi(dpi: Int) {
        val remote = getService()
        if (remote == null) {
            Log.e(TAG, "setVdDensityDpi skipped; service unavailable dpi=$dpi")
            return
        }
        try {
            remote.setVdDensityDpi(dpi)
        } catch (e: Throwable) {
            Log.e(TAG, "setVdDensityDpi failed dpi=$dpi", e)
        }
    }

    override fun getVdDensityDpi(): Int {
        return try {
            getService()?.vdDensityDpi ?: 0
        } catch (e: Throwable) {
            Log.e(TAG, "getVdDensityDpi failed", e)
            0
        }
    }

    override fun getEffectiveVdDensityDpi(): Int {
        return try {
            getService()?.effectiveVdDensityDpi ?: 0
        } catch (e: Throwable) {
            Log.e(TAG, "getEffectiveVdDensityDpi failed", e)
            0
        }
    }

    override fun moveTaskToFront(taskId: Int) {
        getService()?.moveTaskToFront(taskId)
    }

    override fun removeTask(taskId: Int) {
        getService()?.removeTask(taskId)
    }

    override fun pressKey(action: Int) {
        getService()?.pressKey(action)
    }

    override fun touchPane(pane: Int, motionEvent: MotionEvent) {
        tryTouchPane(pane, motionEvent)
    }

    override fun hideIme() {
        try {
            getService()?.hideIme()
        } catch (e: Throwable) {
            Log.e(TAG, "hideIme failed", e)
        }
    }

    override fun getImePane(): Int {
        return getService()?.imePane ?: SplitPane.FULLSCREEN_NONE
    }

    /** @return false when binder missing or the remote call throws (caller must not swallow HU events).
     *  touchPane is oneway — success means the parcel was queued, not that inject finished. */
    fun tryTouchPane(pane: Int, motionEvent: MotionEvent): Boolean {
        val svc = getService()
        if (svc == null) {
            Log.e(TAG, "touchPane skipped; binder unavailable pane=$pane")
            return false
        }
        return try {
            svc.touchPane(pane, motionEvent)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "touchPane failed pane=$pane", e)
            false
        }
    }

    override fun getRecentTask(): RecentTask? {
        return getService()?.recentTask
    }

    override fun reportCarPlaySessionLive(live: Boolean) {
        try {
            getService()?.reportCarPlaySessionLive(live)
        } catch (e: Throwable) {
            Log.e(TAG, "reportCarPlaySessionLive failed", e)
        }
    }

    override fun isCarPlaySessionLive(): Boolean {
        return try {
            getService()?.isCarPlaySessionLive ?: false
        } catch (e: Throwable) {
            Log.e(TAG, "isCarPlaySessionLive failed", e)
            false
        }
    }

    override fun setCarPlayAudioEgressEnabled(enabled: Boolean) {
        try {
            getService()?.setCarPlayAudioEgressEnabled(enabled)
        } catch (e: Throwable) {
            Log.e(TAG, "setCarPlayAudioEgressEnabled failed", e)
        }
    }

    override fun pullCarPlayAudio(maxBytes: Int): ByteArray {
        return try {
            getService()?.pullCarPlayAudio(maxBytes) ?: ByteArray(0)
        } catch (e: Throwable) {
            ByteArray(0)
        }
    }

    override fun pullCarPlaySpeechAudio(maxBytes: Int): ByteArray {
        return try {
            getService()?.pullCarPlaySpeechAudio(maxBytes) ?: ByteArray(0)
        } catch (e: Throwable) {
            ByteArray(0)
        }
    }

    override fun isCarPlaySpeechCaptureReady(): Boolean {
        return try {
            getService()?.isCarPlaySpeechCaptureReady() == true
        } catch (e: Throwable) {
            false
        }
    }

    override fun setWifiEnabled(enabled: Boolean) {
        try {
            getService()?.setWifiEnabled(enabled)
        } catch (e: Throwable) {
            Log.e(TAG, "setWifiEnabled failed", e)
        }
    }

    override fun localWifiMac(): String {
        return try {
            getService()?.localWifiMac().orEmpty()
        } catch (e: Throwable) {
            Log.e(TAG, "localWifiMac failed", e)
            ""
        }
    }

    override fun localBluetoothMac(): String {
        return try {
            getService()?.localBluetoothMac().orEmpty()
        } catch (e: Throwable) {
            Log.e(TAG, "localBluetoothMac failed", e)
            ""
        }
    }

    override fun prepareCarPlayHeadUnit(address: String?) {
        try {
            getService()?.prepareCarPlayHeadUnit(address)
        } catch (e: Throwable) {
            Log.e(TAG, "prepareCarPlayHeadUnit failed", e)
        }
    }

    override fun preferCellularOverUnvalidatedWifi() {
        try {
            getService()?.preferCellularOverUnvalidatedWifi()
        } catch (e: Throwable) {
            Log.e(TAG, "preferCellularOverUnvalidatedWifi failed", e)
        }
    }

    override fun armBluetoothDiscoverable() {
        try {
            getService()?.armBluetoothDiscoverable()
        } catch (e: Throwable) {
            Log.e(TAG, "armBluetoothDiscoverable failed", e)
        }
    }

    override fun reportHidShellOverlay(open: Boolean) {
        try {
            getService()?.reportHidShellOverlay(open)
        } catch (e: Throwable) {
            Log.e(TAG, "reportHidShellOverlay failed open=$open", e)
        }
    }

    override fun drainHidShellTouches(): FloatArray {
        return try {
            getService()?.drainHidShellTouches() ?: FloatArray(0)
        } catch (e: Throwable) {
            Log.e(TAG, "drainHidShellTouches failed", e)
            FloatArray(0)
        }
    }

    override fun scrollPane(pane: Int, x: Int, y: Int, vScroll: Int, hScroll: Int) {
        try {
            getService()?.scrollPane(pane, x, y, vScroll, hScroll)
        } catch (e: Throwable) {
            // oneway best-effort (CarPlay knob wheel)
        }
    }

    private fun getService(): ICoreManager? {
        if (service != null) return service
        val pm = ServiceManager.getService("package")
        if (pm == null) {
            // Very early boot: package service not registered yet — transact on null would
            // NPE. Retry on the next call.
            Log.d(TAG, "package service not ready; cannot acquire binder yet")
            return null
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        val remote = try {
            data.writeInterfaceToken(BridgeService.DESCRIPTOR)
            data.writeInt(BridgeService.ACTION_GET_BINDER)
            pm.transact(BridgeService.TRANSACTION, data, reply, 0)
            reply.readException()
            val binder = reply.readStrongBinder()
            ICoreManager.Stub.asInterface(binder)
        } catch (e: RemoteException) {
            Log.d(TAG, "Failed to get binder")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
        if (remote != null) {
            Log.i(TAG, "Binder acquired")
            remote.asBinder().linkToDeath(this, 0)
            service = remote
        }
        return service
    }
}
