package io.github.nitsuya.aa.display.xposed

import android.content.pm.IPackageManager
import android.os.Binder
import android.os.Parcel
import android.os.Process
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug

object BridgeService {

    private const val TAG = "AADisplay_Bridge"

    private var appUid = 0
    /** Android Auto shares this uid across :car / :projection; needed for rail touchPrimaryPane. */
    private var gearheadUid = 0

    fun register(pms: IPackageManager) {
        log(TAG, "Initialize AADisplayService - Version ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
        appUid = packageUid(pms, BuildConfig.APPLICATION_ID)
        gearheadUid = packageUid(pms, "com.google.android.projection.gearhead")

        log(TAG, "Client uid: app=$appUid gearhead=$gearheadUid")
        log(TAG, "Service uid: ${Process.myUid()}")
        log(TAG, "Initialize service proxy")
        pms.javaClass.findMethod(true) {
            name == "onTransact"
        }.hookBefore { param ->
            val code = param.args[0] as Int
            val data = param.args[1] as Parcel
            val reply = param.args[2] as Parcel?
            if (myTransact(code, data, reply)) param.result = true
        }
    }

    const val TRANSACTION = ('A'.code shl 24) or ('A'.code shl 16) or ('D'.code shl 8) or 'D'.code
    const val DESCRIPTOR = "android.content.pm.IPackageManager"
    const val ACTION_GET_BINDER = 1

    private fun myTransact(code: Int, data: Parcel, reply: Parcel?): Boolean {
        if (code == TRANSACTION) {
            if (isAllowedClient(Binder.getCallingUid())) {
                logDebug(TAG, "Transaction from client uid=${Binder.getCallingUid()}")
                runCatching {
                    data.enforceInterface(DESCRIPTOR)
                    when (data.readInt()) {
                        ACTION_GET_BINDER -> {
                            reply?.writeNoException()
                            reply?.writeStrongBinder(CoreManagerService.instance)
                            return true
                        }
                        else -> log(TAG, "Unknown action")
                    }
                }.onFailure {
                    log(TAG, "Transaction error", it)
                }
            } else {
                log(TAG, "Someone else trying to get my binder? uid=${Binder.getCallingUid()}")
            }
            data.setDataPosition(0)
            reply?.setDataPosition(0)
        }
        return false
    }

    private fun isAllowedClient(uid: Int): Boolean {
        if (uid == appUid) return true
        if (gearheadUid != 0 && uid == gearheadUid) return true
        return false
    }

    private fun packageUid(pms: IPackageManager, packageName: String): Int {
        return try {
            pms.getPackageUid(packageName, 0L, 0)
        } catch (e: Throwable) {
            log(TAG, "getPackageUid($packageName) failed", e)
            0
        }
    }
}