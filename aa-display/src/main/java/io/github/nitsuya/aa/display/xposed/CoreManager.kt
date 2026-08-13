package io.github.nitsuya.aa.display.xposed

import android.os.IBinder.DeathRecipient
import android.os.Parcel
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
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

    override fun moveSecondTaskToFront() {
        getService()?.moveSecondTaskToFront()
    }

    override fun removeTask(taskId: Int) {
        getService()?.removeTask(taskId)
    }

    override fun pressKey(action: Int) {
        getService()?.pressKey(action)
    }

    override fun touchPane(pane: Int, motionEvent: MotionEvent) {
        getService()?.touchPane(pane, motionEvent)
    }

    override fun touchPrimaryPane(motionEvent: MotionEvent) {
        val svc = getService()
        if (svc == null) {
            Log.e(TAG, "touchPrimaryPane skipped; binder unavailable")
            return
        }
        svc.touchPrimaryPane(motionEvent)
    }

    override fun addMirrorPane(pane: Int, surfaceControl: SurfaceControl) {
        getService()?.addMirrorPane(pane, surfaceControl)
    }

    override fun removeMirrorPane(pane: Int, surfaceControl: SurfaceControl) {
        getService()?.removeMirrorPane(pane, surfaceControl)
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
