package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import io.github.nitsuya.aa.display.util.CoolwalkRailStore
import io.github.nitsuya.aa.display.util.DisplayProfileSettle
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Widen gearhead [DrawingSpec] from content slot (e.g. 1173) to full HU (1280) once
 * content_bounds reclaim has started on this connection. Must run in gearhead where
 * the spec is constructed; AADisplay only sees the parcelled result.
 */
object CoolwalkDrawingSpecWiden {

    private const val DRAWING_SPEC = "com.google.android.gms.car.DrawingSpec"
    private const val TAG = "AAD_AaDisplayVD"

    private val gearheadInstalled = AtomicBoolean(false)
    private val aaDisplayInstalled = AtomicBoolean(false)

    fun installGearhead(classLoader: ClassLoader) {
        if (!gearheadInstalled.compareAndSet(false, true)) return
        installConstructors(classLoader, ::resolveTargetWidthGearhead, "gearhead")
    }

    /** AADisplay loads DrawingSpec from gearhead dex after Application.onCreate. */
    fun installAaDisplayLazy() {
        if (!aaDisplayInstalled.compareAndSet(false, true)) return
        val loader = runCatching { InitFields.appContext.classLoader }.getOrNull() ?: return
        if (installConstructors(loader, ::resolveTargetWidthAaDisplay, "aadisplay-immediate")) {
            return
        }
        hookClassLoaderForDrawingSpec(loader)
        logDebug(TAG, "DrawingSpec lazy loadClass hook armed (aadisplay)")
    }

    private fun hookClassLoaderForDrawingSpec(loader: ClassLoader) {
        runCatching {
            findMethod(ClassLoader::class.java) {
                name == "loadClass" && parameterCount == 2 &&
                    parameterTypes[0] == String::class.java &&
                    parameterTypes[1] == Boolean::class.javaPrimitiveType
            }.hookAfter { param ->
                if (param.args[0] != DRAWING_SPEC) return@hookAfter
                val clazz = param.result as? Class<*> ?: return@hookAfter
                installConstructors(clazz.classLoader ?: loader, ::resolveTargetWidthAaDisplay, "aadisplay-lazy")
            }
        }.onFailure { e ->
            log(TAG, "DrawingSpec loadClass hook failed", e)
        }
    }

    private fun installConstructors(
        classLoader: ClassLoader,
        resolveWidth: (Int, Int) -> Int,
        source: String,
    ): Boolean {
        val specClass = runCatching {
            Class.forName(DRAWING_SPEC, false, classLoader)
        }.getOrNull() ?: return false
        return installConstructorsOnClass(specClass, resolveWidth, source)
    }

    private fun installConstructorsOnClass(
        specClass: Class<*>,
        resolveWidth: (Int, Int) -> Int,
        source: String,
    ): Boolean {
        var hooked = 0
        for (ctor in specClass.declaredConstructors) {
            val p = ctor.parameterTypes
            if (p.size < 3) continue
            if (p[0] != Int::class.javaPrimitiveType || p[1] != Int::class.javaPrimitiveType) continue
            if (p[2] != Int::class.javaPrimitiveType) continue
            ctor.isAccessible = true
            ctor.hookBefore { param ->
                val width = param.args[0] as? Int ?: return@hookBefore
                val height = param.args[1] as? Int ?: return@hookBefore
                val target = resolveWidth(width, height)
                if (target <= width) return@hookBefore
                param.args[0] = target
                logDebug(TAG, "DrawingSpec ctor widen [$source] ${width}x$height → ${target}x$height")
            }
            hooked++
        }
        if (hooked > 0) {
            logDebug(TAG, "hooked DrawingSpec constructors=$hooked source=$source")
        }
        return hooked > 0
    }

    private fun resolveTargetWidthGearhead(width: Int, height: Int): Int {
        CoolwalkRailCoordinator.syncExternalTruth()
        return resolveTargetWidth(width, CoolwalkRailCoordinator.effectiveSnapshot())
    }

    private fun resolveTargetWidthAaDisplay(width: Int, height: Int): Int {
        val cr = runCatching { InitFields.appContext.contentResolver }.getOrNull() ?: return width
        val snap = CoolwalkRailStore.snapshotWithSession(CoolwalkRailStore.read(cr))
        return resolveTargetWidth(width, snap)
    }

    /**
     * Only after this session's content_bounds reclaim (Reclaiming / FullBleed).
     * Do not widen on ReconnectSettling — compositor rail may still be live.
     */
    internal fun resolveTargetWidth(width: Int, snap: RailSnapshot): Int {
        val full = snap.fullHuWidthPx
        if (full <= 0 || width <= 0) return width
        if (!DisplayProfileSettle.isContentSlotVsFull(width, full)) return width
        return when (snap.phase) {
            RailPhase.FullBleed,
            RailPhase.Reclaiming,
            -> full
            else -> width
        }
    }
}
