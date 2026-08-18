package io.github.nitsuya.aa.display.xposed.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.IPackageManager
import com.github.kyuubiran.ezxhelper.utils.*
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.BridgeService
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.qauxv.util.Initiator
import java.io.File
import java.lang.reflect.Method

object AndroidHook : BaseHook() {
    override val tagName: String = "AAD_AndroidHook"

    @Volatile
    var isSystemServerHooked: Boolean = false
        private set

    @Volatile
    private var isSystemServerProcessCached: Boolean = false

    @Volatile
    private var systemServerClassLoader: ClassLoader? = null

    /** True only in the real system_server process after AndroidHook.init. */
    fun isReadyForSystemHooks(): Boolean =
        isSystemServerHooked && isSystemServerProcessCached

    internal fun findSystemMethod(
        className: String,
        findSuper: Boolean = false,
        condition: Method.() -> Boolean
    ): Method? {
        if (!isReadyForSystemHooks()) return null
        val cl = systemServerClassLoader ?: return null
        return findMethod(className, cl, findSuper, condition)
    }

    private fun readIsSystemServerProcess(): Boolean {
        return try {
            val name = File("/proc/self/cmdline").readBytes()
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)
            name == "system_server"
        } catch (_: Throwable) {
            false
        }
    }

    /** Prefer themable UI Context for system-side UI / Instances. */
    private fun fieldContext(ams: Any, name: String): Context? =
        runCatching { ams.getObjectAs(name, Context::class.java) as? Context }.getOrNull()

    @SuppressLint("SoonBlockedPrivateApi", "DiscouragedPrivateApi")
    private fun activityThreadUiContext(): Context? =
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
                ?: return@runCatching null
            runCatching {
                atClass.getDeclaredMethod("getSystemUiContext").invoke(thread) as? Context
            }.getOrNull()
                ?: runCatching {
                    atClass.getDeclaredMethod("getSystemContext").invoke(thread) as? Context
                }.getOrNull()
        }.getOrNull()

    /**
     * Capture AMS system/UI Context across OEM signature churn (e.g. One UI 8.5
     * no longer always has Context as constructor parameterTypes[0]).
     */
    private fun captureSystemContext(ams: Any, args: Array<Any?>?): Boolean {
        if (CoreManagerService.hasSystemContext) return true
        val ctx =
            fieldContext(ams, "mUiContext")
                ?: args?.filterIsInstance<Context>()?.firstOrNull()
                ?: fieldContext(ams, "mContext")
                ?: activityThreadUiContext()
        if (ctx == null) {
            log(tagName, "captureSystemContext failed")
            return false
        }
        CoreManagerService.systemContext = ctx
        log(tagName, "get systemUiContext")
        return true
    }

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        isSystemServerProcessCached = readIsSystemServerProcess()
        systemServerClassLoader = lpparam.classLoader
        isSystemServerHooked = true
        Initiator.init(lpparam.classLoader)
        log(tagName, "xposed init")
        var serviceManagerHook: XC_MethodHook.Unhook? = null
        serviceManagerHook = findMethod("android.os.ServiceManager") {
            name == "addService"
        }.hookBefore { param ->
            if (param.args[0] == "package") {
                serviceManagerHook?.unhook()
                val pms = param.args[1] as IPackageManager
                log(tagName, "Got pms: $pms")
                runCatching {
                    BridgeService.register(pms)
                    log(tagName, "Bridge service injected")
                }.onFailure {
                    log(tagName, "System service crashed", it)
                }
            }
        }

        // One UI 8.5+ may reorder/wrap AMS ctor params; do not require Context at [0].
        var activityManagerServiceConstructorHook: List<XC_MethodHook.Unhook> = emptyList()
        activityManagerServiceConstructorHook =
            findAllConstructors("com.android.server.am.ActivityManagerService") {
                true
            }.hookAfter { param ->
                if (captureSystemContext(param.thisObject, param.args)) {
                    activityManagerServiceConstructorHook.forEach { hook -> hook.unhook() }
                }
            }.also {
                if (it.isEmpty()) {
                    log(tagName, "no ActivityManagerService constructor found")
                } else {
                    log(tagName, "hooked ${it.size} ActivityManagerService constructor(s)")
                }
            }

        var activityManagerServiceSystemReadyHook: XC_MethodHook.Unhook? = null
        activityManagerServiceSystemReadyHook =
            findMethod("com.android.server.am.ActivityManagerService") {
                name == "systemReady"
            }.hookAfter { param ->
                activityManagerServiceSystemReadyHook?.unhook()
                runCatching {
                    if (!CoreManagerService.hasSystemContext) {
                        captureSystemContext(param.thisObject, param.args)
                    }
                    CoreManagerService.systemReady()
                    log(tagName, "system ready")
                }.onFailure {
                    log(tagName, "systemReady failed", it)
                }
            }
    }
}
