package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import io.github.nitsuya.aa.display.util.CoolwalkRailStore
import io.github.nitsuya.aa.display.util.DisplayProfileSettle
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Widen gearhead [DrawingSpec] from content slot (e.g. 1173) to full HU (1280) once
 * content_bounds reclaim has started on this connection. Must run in gearhead where
 * the spec is constructed; AADisplay unmarshals via [Parcelable.Creator] — hook both.
 */
object CoolwalkDrawingSpecWiden {

    private const val DRAWING_SPEC = "com.google.android.gms.car.DrawingSpec"
    private const val TAG = "AAD_AaDisplayVD"

    private val gearheadInstalled = AtomicBoolean(false)
    private val aaDisplayInstalled = AtomicBoolean(false)
    private val creatorHookedClasses = ConcurrentHashMap.newKeySet<Class<*>>()

    fun installGearhead(classLoader: ClassLoader) {
        if (!gearheadInstalled.compareAndSet(false, true)) return
        installOnClass(classLoader, ::resolveTargetWidthGearhead, "gearhead")
    }

    /** AADisplay loads DrawingSpec from gearhead dex after Application.onCreate. */
    fun installAaDisplayLazy() {
        if (!aaDisplayInstalled.compareAndSet(false, true)) return
        val loader = runCatching { InitFields.appContext.classLoader }.getOrNull() ?: return
        if (installOnClass(loader, ::resolveTargetWidthAaDisplay, "aadisplay-immediate")) {
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
                installOnClass(clazz.classLoader ?: loader, ::resolveTargetWidthAaDisplay, "aadisplay-lazy")
            }
        }.onFailure { e ->
            log(TAG, "DrawingSpec loadClass hook failed", e)
        }
    }

    private fun installOnClass(
        classLoader: ClassLoader,
        resolveWidth: (Int, Int) -> Int,
        source: String,
    ): Boolean {
        val specClass = runCatching {
            Class.forName(DRAWING_SPEC, false, classLoader)
        }.getOrNull() ?: return false
        return installConstructorsOnClass(specClass, resolveWidth, source) ||
            installCreatorOnClass(specClass, resolveWidth, source)
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
            ctor.hookAfter { param ->
                widenInstance(param.thisObject, resolveWidth, "$source-ctor")
            }
            hooked++
        }
        if (hooked > 0) {
            logDebug(TAG, "hooked DrawingSpec constructors=$hooked source=$source")
        }
        return hooked > 0
    }

    private fun installCreatorOnClass(
        specClass: Class<*>,
        resolveWidth: (Int, Int) -> Int,
        source: String,
    ): Boolean {
        if (creatorHookedClasses.contains(specClass)) return false
        val creator = runCatching {
            val field = specClass.getField("CREATOR")
            field.get(null)
        }.getOrNull() ?: return false
        return runCatching {
            findMethod(creator.javaClass) {
                name == "createFromParcel" && parameterCount == 1 &&
                    parameterTypes[0] == android.os.Parcel::class.java
            }.hookAfter { param ->
                widenInstance(param.result, resolveWidth, "$source-parcel")
            }
            creatorHookedClasses.add(specClass)
            logDebug(TAG, "hooked DrawingSpec CREATOR source=$source")
            true
        }.getOrDefault(false)
    }

    private fun widenInstance(
        spec: Any?,
        resolveWidth: (Int, Int) -> Int,
        source: String,
    ) {
        if (spec == null) return
        val fields = resolveWidthHeightFields(spec.javaClass) ?: return
        val (widthField, heightField) = fields
        val w = widthField.getInt(spec)
        val h = heightField.getInt(spec)
        val target = resolveWidth(w, h)
        if (target <= w) return
        widthField.setInt(spec, target)
        logDebug(TAG, "H13|DrawingSpec instance widen [$source] ${w}x$h → ${target}x$h")
    }

    private fun resolveWidthHeightFields(clazz: Class<*>): Pair<java.lang.reflect.Field, java.lang.reflect.Field>? {
        val ints = clazz.declaredFields.filter { field ->
            !Modifier.isStatic(field.modifiers) && field.type == Integer.TYPE
        }
        if (ints.size < 2) return null
        ints[0].isAccessible = true
        ints[1].isAccessible = true
        return ints[0] to ints[1]
    }

    private fun resolveTargetWidthGearhead(width: Int, height: Int): Int {
        CoolwalkRailCoordinator.syncExternalTruth()
        return resolveTargetWidth(width, CoolwalkRailCoordinator.effectiveSnapshot())
    }

    private fun resolveTargetWidthAaDisplay(width: Int, height: Int): Int {
        val cr = runCatching { InitFields.appContext.contentResolver }.getOrNull() ?: return width
        val snap = CoolwalkRailStore.snapshotWithSession(CoolwalkRailStore.read(cr), cr)
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
            // content_bounds may have fired while IPC still says ReconnectSettling.
            RailPhase.ReconnectSettling -> if (snap.fullBleedStableCount >= 1) full else width
            else -> width
        }
    }
}
