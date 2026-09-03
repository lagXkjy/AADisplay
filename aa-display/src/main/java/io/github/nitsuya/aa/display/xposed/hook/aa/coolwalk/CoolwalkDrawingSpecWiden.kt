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
        logDebug(TAG, "DrawingSpec instance widen [$source] ${w}x$h → ${target}x$h")
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
        // Samsung / OEM Settings.Global may reject our keys (reads stay 0). Prefer IPC.
        val ipc = runCatching {
            io.github.nitsuya.aa.display.xposed.CoreManager.tryGetCoolwalkRailSnapshot()
        }.getOrNull()?.let { CoolwalkRailStore.snapshotFromWire(it) }
        val cr = runCatching { InitFields.appContext.contentResolver }.getOrNull()
        val fromSettings = cr?.let {
            CoolwalkRailStore.snapshotWithSession(CoolwalkRailStore.read(it), it)
        }
        val snap = mergeAaDisplayWidenSnapshot(ipc, fromSettings, width)
        return resolveTargetWidth(width, snap)
    }

    /**
     * When Settings.Global is empty but system_server has full HU (Samsung), still widen
     * once the gap looks like a content slot — treat as Reclaiming for [resolveTargetWidth].
     */
    internal fun mergeAaDisplayWidenSnapshot(
        ipc: RailSnapshot?,
        fromSettings: RailSnapshot?,
        contentWidth: Int,
    ): RailSnapshot {
        val base = when {
            ipc != null && ipc.fullHuWidthPx > 0 -> ipc
            fromSettings != null && fromSettings.fullHuWidthPx > 0 -> fromSettings
            ipc != null -> ipc
            fromSettings != null -> fromSettings
            else -> return RailSnapshot()
        }
        val other = if (base === ipc) fromSettings else ipc
        val reclaimPhase = sequenceOf(base.phase, other?.phase).firstOrNull {
            it == RailPhase.Reclaiming || it == RailPhase.FullBleed
        }
        val stable = maxOf(base.fullBleedStableCount, other?.fullBleedStableCount ?: 0)
        val full = maxOf(base.fullHuWidthPx, other?.fullHuWidthPx ?: 0)
        val needsWiden = full > 0 && DisplayProfileSettle.isContentSlotVsFull(contentWidth, full)
        val phase = when {
            reclaimPhase != null -> reclaimPhase
            needsWiden && (stable > 0 || base.touchRailWidthPx > 1 || (other?.touchRailWidthPx ?: 0) > 1) ->
                RailPhase.Reclaiming
            needsWiden && full > contentWidth -> RailPhase.Reclaiming
            else -> base.phase
        }
        return base.copy(
            phase = phase,
            fullHuWidthPx = full,
            touchRailWidthPx = maxOf(base.touchRailWidthPx, other?.touchRailWidthPx ?: 0),
            fullBleedStableCount = maxOf(stable, if (phase == RailPhase.Reclaiming || phase == RailPhase.FullBleed) 1 else 0),
        )
    }

    /**
     * Widen content-slot widths after reclaim. [RailPhase.ReconnectSettling] with
     * [RailSnapshot.fullHuWidthPx] cleared (true soft reconnect) does not widen;
     * settling with a known full HU still widens (aligned with LayoutInfo / blX).
     * Bootstrapping / RailPresent stay gated — widening before starve leaves a left bar.
     */
    internal fun resolveTargetWidth(width: Int, snap: RailSnapshot): Int {
        val full = snap.fullHuWidthPx
        if (full <= 0 || width <= 0) {
            return width
        }
        if (!DisplayProfileSettle.isContentSlotVsFull(width, full)) return width
        val target = when (snap.phase) {
            RailPhase.FullBleed,
            RailPhase.Reclaiming,
            -> full
            // True reconnect clears fullHu; if fullHu is known, treat as reclaim-in-progress
            // (false FullBleed demotion or bounds expanded while IPC still settling).
            RailPhase.ReconnectSettling -> full
            else -> width
        }
        return target
    }
}
