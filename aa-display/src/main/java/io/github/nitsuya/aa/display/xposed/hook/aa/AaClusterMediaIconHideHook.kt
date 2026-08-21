package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.Intent
import android.content.pm.ResolveInfo
import android.service.media.MediaBrowserService
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.service.ClusterLyricMediaService
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug

/**
 * Hide [ClusterLyricMediaService] from AA's media-app / launcher grid while leaving the
 * service exported for warmStart, MediaSession Title egress, and explicit binds.
 *
 * Filters only [MediaBrowserService.SERVICE_INTERFACE] discovery lists in gearhead;
 * projection ([AaActivityService] CATEGORY_PROJECTION*) is untouched.
 *
 * Safety / perf:
 * - Callbacks never throw into PackageManager (try/catch; leave stock result on failure).
 * - Non-media queries, foreign [Intent.getPackage], and explicit [Intent.getComponent]
 *   return immediately (PM list queries are hot).
 * - List copy only when our shell is actually present.
 */
object AaClusterMediaIconHideHook : AaHook() {
    override val tagName: String = "AAD_AaClusterMediaIconHideHook"

    private val selfPkg = BuildConfig.APPLICATION_ID
    private val shellClass = ClusterLyricMediaService::class.java.name
    private const val MEDIA_BROWSER_ACTION = MediaBrowserService.SERVICE_INTERFACE

    @Volatile private var loggedHide = false
    @Volatile private var loggedFilterCallbackError = false
    @Volatile private var loggedParceledRebuildError = false

    override fun isSupportProcess(processName: String): Boolean {
        return processProjection == processName || processCar == processName
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val apm = runCatching {
            XposedHelpers.findClass("android.app.ApplicationPackageManager", lpparam.classLoader)
        }.getOrNull()
        if (apm == null) {
            log(tagName, "ApplicationPackageManager missing — stock media icon list")
            return
        }
        var hooked = 0
        for (method in apm.declaredMethods) {
            if (method.name != "queryIntentServices") continue
            if (method.parameterCount < 1) continue
            if (method.parameterTypes[0] != Intent::class.java) continue
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            filterMediaBrowserList(param)
                        } catch (t: Throwable) {
                            // Never propagate into gearhead PM — keep original result.
                            if (!loggedFilterCallbackError) {
                                loggedFilterCallbackError = true
                                log(tagName, "filter callback failed (stock list kept)", t)
                            }
                        }
                    }
                })
                hooked++
            }.onFailure { e ->
                log(tagName, "hook ${method.name}/${method.parameterCount} failed", e)
            }
        }
        if (hooked == 0) {
            log(tagName, "no queryIntentServices hooks — stock media icon list")
        } else {
            log(tagName, "armed pkg=$selfPkg queryIntentServicesHooks=$hooked")
        }
    }

    private fun filterMediaBrowserList(param: XC_MethodHook.MethodHookParam) {
        val intent = param.args.getOrNull(0) as? Intent ?: return
        // Hot path: almost all PM queries are non-media — exit before touching result.
        if (intent.action != MEDIA_BROWSER_ACTION) return
        // Explicit component must stay visible for cached / direct binds.
        if (intent.component != null) return
        // Package-scoped query for someone else cannot contain our shell.
        val scopedPkg = intent.`package`
        if (scopedPkg != null && scopedPkg != selfPkg) return

        val raw = param.result ?: return
        val filtered = when (raw) {
            is List<*> -> filterResolveList(raw)
            else -> filterParceledListSlice(raw)
        } ?: return
        param.result = filtered
        if (!loggedHide) {
            loggedHide = true
            logDebug(tagName, "hid cluster MediaBrowserService from discovery list")
        }
    }

    /**
     * Copy-on-write: no allocation when our shell is absent (common after first filter
     * or when AADisplay is not installed as a media source on this pass).
     */
    private fun filterResolveList(list: List<*>): List<Any?>? {
        val size = list.size
        if (size == 0) return null
        var out: ArrayList<Any?>? = null
        for (i in 0 until size) {
            val item = list[i]
            if (item is ResolveInfo && isClusterShell(item)) {
                if (out == null) {
                    out = ArrayList(size - 1)
                    for (j in 0 until i) {
                        out.add(list[j])
                    }
                }
                continue
            }
            out?.add(item)
        }
        return out
    }

    /**
     * Some OEM / framework paths return [android.content.pm.ParceledListSlice] from PM.
     * Rebuild with the same concrete class when present; on failure keep stock slice.
     */
    private fun filterParceledListSlice(raw: Any): Any? {
        val list = runCatching {
            XposedHelpers.callMethod(raw, "getList") as? List<*>
        }.getOrNull() ?: return null
        val filtered = filterResolveList(list) ?: return null
        return runCatching {
            val ctor = raw.javaClass.getDeclaredConstructor(List::class.java)
            ctor.isAccessible = true
            ctor.newInstance(filtered)
        }.onFailure { e ->
            if (!loggedParceledRebuildError) {
                loggedParceledRebuildError = true
                logDebug(tagName, "ParceledListSlice rebuild failed (stock kept): ${e.message}")
            }
        }.getOrNull()
    }

    private fun isClusterShell(ri: ResolveInfo): Boolean {
        val si = ri.serviceInfo ?: return false
        if (si.packageName != selfPkg) return false
        val name = si.name ?: return false
        return name == shellClass
    }
}
