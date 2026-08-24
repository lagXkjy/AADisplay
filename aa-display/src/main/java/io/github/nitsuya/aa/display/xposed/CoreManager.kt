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

    override fun setFocusedPane(pane: Int) {
        getService()?.setFocusedPane(pane)
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

    override fun moveTaskId(taskId: Int, isVirtualDisplay: Boolean) {
        getService()?.moveTaskId(taskId, isVirtualDisplay)
    }

    override fun moveTaskIdToPane(taskId: Int, pane: Int) {
        getService()?.moveTaskIdToPane(taskId, pane)
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

    override fun touchPrimaryPane(motionEvent: MotionEvent) {
        tryTouchPrimaryPane(motionEvent)
    }

    override fun touchAaDisplay(motionEvent: MotionEvent) {
        tryTouchAaDisplay(motionEvent)
    }

    override fun reportAaUiDisplayId(displayId: Int) {
        try {
            getService()?.reportAaUiDisplayId(displayId)
        } catch (e: Throwable) {
            Log.e(TAG, "reportAaUiDisplayId failed id=$displayId", e)
        }
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

    override fun reportCoolwalkRailSnapshot(
        phase: Int,
        touchRailWidthPx: Int,
        fullHuWidthPx: Int,
        facetDisplayId: Int,
    ) {
        try {
            getService()?.reportCoolwalkRailSnapshot(phase, touchRailWidthPx, fullHuWidthPx, facetDisplayId)
        } catch (e: Throwable) {
            Log.e(TAG, "reportCoolwalkRailSnapshot failed", e)
        }
    }

    override fun getCoolwalkRailSnapshot(): IntArray {
        return try {
            getService()?.coolwalkRailSnapshot ?: intArrayOf(0, 0, 0, -1)
        } catch (e: Throwable) {
            Log.e(TAG, "getCoolwalkRailSnapshot failed", e)
            intArrayOf(0, 0, 0, -1)
        }
    }

    override fun getCoolwalkReconnectEpochMs(): Long {
        return try {
            getService()?.coolwalkReconnectEpochMs ?: 0L
        } catch (e: Throwable) {
            Log.e(TAG, "getCoolwalkReconnectEpochMs failed", e)
            0L
        }
    }

    override fun notifyCoolwalkFullBleed() {
        try {
            getService()?.notifyCoolwalkFullBleed()
        } catch (e: Throwable) {
            Log.e(TAG, "notifyCoolwalkFullBleed failed", e)
        }
    }

    fun tryReportCoolwalkRailSnapshot(snapshot: io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.RailSnapshot): Boolean {
        val svc = getService() ?: return false
        return try {
            svc.reportCoolwalkRailSnapshot(
                snapshot.phase.code,
                snapshot.touchRailWidthPx,
                snapshot.fullHuWidthPx,
                snapshot.facetDisplayId,
            )
            true
        } catch (e: Throwable) {
            Log.e(TAG, "reportCoolwalkRailSnapshot failed", e)
            false
        }
    }

    fun tryGetCoolwalkRailSnapshot(): IntArray? {
        return try {
            getService()?.coolwalkRailSnapshot
        } catch (e: Throwable) {
            Log.e(TAG, "getCoolwalkRailSnapshot failed", e)
            null
        }
    }

    fun tryGetCoolwalkReconnectEpochMs(): Long {
        return try {
            getService()?.coolwalkReconnectEpochMs ?: 0L
        } catch (e: Throwable) {
            Log.e(TAG, "getCoolwalkReconnectEpochMs failed", e)
            0L
        }
    }

    fun tryNotifyCoolwalkFullBleed(): Boolean {
        val svc = getService() ?: return false
        return try {
            svc.notifyCoolwalkFullBleed()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "notifyCoolwalkFullBleed failed", e)
            false
        }
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

    /** @return false when binder missing or the remote call throws (caller must not swallow HU events).
     *  touchPrimaryPane is oneway — success means the parcel was queued, not that inject finished. */
    fun tryTouchPrimaryPane(motionEvent: MotionEvent): Boolean {
        val svc = getService()
        if (svc == null) {
            Log.e(TAG, "touchPrimaryPane skipped; binder unavailable")
            return false
        }
        return try {
            svc.touchPrimaryPane(motionEvent)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "touchPrimaryPane failed", e)
            false
        }
    }

    /** @return false when binder missing or the remote call throws (caller must not swallow HU events).
     *  touchAaDisplay is oneway — success means the parcel was queued, not that inject finished. */
    fun tryTouchAaDisplay(motionEvent: MotionEvent): Boolean {
        val svc = getService()
        if (svc == null) {
            Log.e(TAG, "touchAaDisplay skipped; binder unavailable")
            return false
        }
        return try {
            svc.touchAaDisplay(motionEvent)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "touchAaDisplay failed", e)
            false
        }
    }

    override fun getRecentTask(): RecentTask? {
        return getService()?.recentTask
    }

    private fun getService(): ICoreManager? {
        if (service != null) return service
        val pm = ServiceManager.getService("package")
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
