package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.content.ContentResolver
import android.hardware.display.DisplayManager
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import io.github.nitsuya.aa.display.util.CoolwalkRailStore
import io.github.nitsuya.aa.display.xposed.CoreManager
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug

/**
 * CarActivity presentation VirtualDisplay is created in the AADisplay process.
 *
 * Resize after the Car SDK allocated the encoder Surface blacks the HU — only widen
 * at [DisplayManager.createVirtualDisplay] time when rail reclaim already published
 * this-connection full HU via system_server rail snapshot (Settings.Global mirror may
 * be blocked on some OEM builds).
 */
object AaDisplayPresentationResize {
    private const val TAG = "AAD_AaDisplayVD"

    @Volatile
    private var createHooked = false

    fun hookDisplayManager() {
        if (createHooked) return
        createHooked = true
        try {
            var hooked = 0
            for (method in DisplayManager::class.java.declaredMethods) {
                if (method.name != "createVirtualDisplay") continue
                val params = method.parameterTypes
                if (params.size < 5) continue
                if (params[0] != String::class.java) continue
                if (params[1] != Int::class.javaPrimitiveType) continue
                if (params[2] != Int::class.javaPrimitiveType) continue
                method.isAccessible = true
                method.hookBefore { param ->
                    val name = param.args[0] as? String ?: return@hookBefore
                    if (!isAaDisplayPresentationVd(name)) return@hookBefore
                    val width = param.args[1] as? Int ?: return@hookBefore
                    val target = resolveCreateWidth(width, InitFields.appContext.contentResolver)
                    if (target > width) {
                        param.args[1] = target
                        log(TAG, "presentation VD create ${width}→$target ($name)")
                    }
                }
                hooked++
            }
            logDebug(TAG, "hooked createVirtualDisplay overloads=$hooked (create-only, no resize)")
        } catch (e: Throwable) {
            log(TAG, "hook DisplayManager.createVirtualDisplay failed", e)
        }
    }

    private fun isAaDisplayPresentationVd(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        return name.contains("AaDisplayActivity", ignoreCase = true)
    }

    private fun resolveCreateWidth(contentWidth: Int, cr: ContentResolver?): Int {
        val snap = resolveRailSnapshot(cr)
        return CoolwalkRailMath.targetPresentationWidthPx(snap, contentWidth)
    }

    /** Prefer IPC snapshot; Settings.Global is best-effort (Samsung may block new keys). */
    private fun resolveRailSnapshot(cr: ContentResolver?): RailSnapshot {
        CoreManager.tryGetCoolwalkRailSnapshot()?.let { wire ->
            CoolwalkRailStore.snapshotFromWire(wire)?.let { snap ->
                if (snap.fullHuWidthPx > 0 || snap.touchRailWidthPx > 1) return snap
            }
        }
        return when {
            cr != null -> CoolwalkRailStore.read(cr)
            else -> CoolwalkRailStore.serverSnapshot
        }
    }
}
