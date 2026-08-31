package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.util.rewriteMotionEvent
import io.github.nitsuya.aa.display.xposed.CoreManager
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

object AaCoolwalkHuTouchHook {

    private const val ENSURE_RAIL_MIN_INTERVAL_MS = 750L

    @Volatile
    private var lastEnsureRailUptimeMs = 0L

    private val carDisplayIdAccessorNames = setOf(
        "getDisplayId", "displayId", "getId", "id", "getAndroidDisplayId", "androidDisplayId",
    )
    private val carDisplayIdFieldNames = setOf("mDisplayId", "displayId", "id")

    private data class CarDisplayIdAccessors(
        val methods: List<Method>,
        val fields: List<Field>,
    )

    private val carDisplayIdAccessorsByClass = ConcurrentHashMap<Class<*>, CarDisplayIdAccessors>()

    private data class PteLayout(
        val actionField: Field,
        val pointersField: Field,
        val pointerX: Field,
        val pointerY: Field,
        val pointerId: Field,
    )

    private val pteLayoutByClass = ConcurrentHashMap<Class<*>, PteLayout>()

    private class PtePointerBuffers {
        var props = Array(8) { MotionEvent.PointerProperties() }
        var coords = Array(8) { MotionEvent.PointerCoords() }

        fun ensure(count: Int) {
            if (props.size >= count) return
            val n = count.coerceAtLeast(props.size * 2)
            props = Array(n) { i -> if (i < props.size) props[i] else MotionEvent.PointerProperties() }
            coords = Array(n) { i -> if (i < coords.size) coords[i] else MotionEvent.PointerCoords() }
        }
    }

    private val ptePointerBuffers = ThreadLocal.withInitial { PtePointerBuffers() }

    fun install(env: CoolwalkHookEnv, lpparam: XC_LoadPackage.LoadPackageParam) {
        syncServerRailSnapshot()
        hookHuTouchDispatchRedirect(env)
        ensureRailObservationFromDisplays(env)
        registerSplitStateReceiver(env)
        registerAaUiRailConsumeReceiver(env)
    }

    private fun syncServerRailSnapshot() {
        val data = CoreManager.tryGetCoolwalkRailSnapshot() ?: return
        if (data.size < 4) return
        CoolwalkRailCoordinator.applyServerSnapshot(
            RailSnapshot(
                phase = RailPhase.fromCode(data[0]),
                touchRailWidthPx = data[1],
                fullHuWidthPx = data[2],
                facetDisplayId = data[3],
                updatedUptimeMs = SystemClock.uptimeMillis(),
                lastEvent = "server-sync",
            ),
        )
    }

    fun isTrustedRailDisplayId(displayId: Int): Boolean {
        return displayId != Display.INVALID_DISPLAY && displayId != Display.DEFAULT_DISPLAY
    }

    fun flushPendingRailMove(env: CoolwalkHookEnv) {
        val generation = env.mRailMoveGeneration
        val pending = takePendingRailMove(env) ?: return
        if (generation != env.mRailMoveGeneration) {
            pending.recycle()
            return
        }
        try {
            val fs = env.mCachedFullscreenPane
            injectStolenRailEvent(env, pending, fs, env.mHuPeelGesture, env.mAaUiRailConsume)
        } finally {
            pending.recycle()
        }
    }

    private fun hookHuTouchDispatchRedirect(env: CoolwalkHookEnv) {
        val anchor = env.huTouchDispatchMethod
        if (anchor == null) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: skip HU touch dispatch redirect (method not found)")
            return
        }
        val targets = linkedSetOf<Method>()
        targets += anchor
        runCatching {
            for (m in anchor.declaringClass.declaredMethods) {
                if (m.parameterTypes.size != 2) continue
                if (m.parameterTypes[0] != anchor.parameterTypes[0]) continue
                val p1 = m.parameterTypes[1]
                if (p1 == MotionEvent::class.java || p1 == anchor.parameterTypes[1]) {
                    m.isAccessible = true
                    targets += m
                }
            }
        }
        var hooked = 0
        for (method in targets) {
            try {
                method.hookBefore { param -> stealHuTouchIfRail(env, param) }
                hooked++
            } catch (e: Throwable) {
                log(
                    CoolwalkHookEnv.TAG,
                    "AaUiHook: hook HU dispatch ${method.declaringClass.name}#${method.name} failed",
                    e,
                )
            }
        }
        log(
            CoolwalkHookEnv.TAG,
            "AaUiHook: hooked HU touch dispatch → rail steal " +
                "methods=${targets.joinToString { it.name }} hooked=$hooked",
        )
    }

    private fun stealHuTouchIfRail(
        env: CoolwalkHookEnv,
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam,
    ) {
        if (param.args.size < 2) return
        val raw = param.args[1] ?: return
        val owned = raw !is MotionEvent
        val motion = when (raw) {
            is MotionEvent -> raw
            else -> projectionTouchEventToMotionEvent(env, raw)
        } ?: return
        try {
            // Heavy display/IPC sync is not needed on every MOVE — only DOWN / interval.
            val nowEnsure = SystemClock.uptimeMillis()
            val shouldEnsure = motion.actionMasked == MotionEvent.ACTION_DOWN ||
                nowEnsure - lastEnsureRailUptimeMs >= ENSURE_RAIL_MIN_INTERVAL_MS
            if (shouldEnsure) {
                ensureRailObservationFromDisplays(env)
            }
            val railTarget = isRailTargetCarDisplay(env, param.args[0])
            val railBase = CoolwalkRailCoordinator.railHitWidthPx()
            // Picker/Recents: cover full absolute facet band even if touchRail not reported yet.
            val rail = if (env.mAaUiRailConsume) {
                maxOf(railBase, CoolwalkRailMath.absoluteFacetRailBand().last)
            } else {
                railBase
            }
            val action = motion.actionMasked
            val downInRailBand = motion.getX(0) < rail
            val downInRail = if (railTarget) true else downInRailBand
            if (action == MotionEvent.ACTION_DOWN) {
                env.mHuRailGesture = downInRail
                // Recents / picker owns the shell — never treat rail as peel (would swap
                // fullscreen panes via SplitDividerView under a low-elevation overlay).
                env.mHuPeelGesture = !env.mAaUiRailConsume &&
                    downInRail &&
                    SplitPane.isFullscreenPane(env.mCachedFullscreenPane) &&
                    isPeelHandleHitBand(env, motion)
            }
            val steal = when (action) {
                MotionEvent.ACTION_DOWN -> downInRail
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val was = env.mHuRailGesture
                    env.mHuRailGesture = false
                    val result = was
                    if (!was) env.mHuPeelGesture = false
                    result
                }
                else -> env.mHuRailGesture
            }
            if (!steal) {
                if (action == MotionEvent.ACTION_DOWN ||
                    action == MotionEvent.ACTION_UP ||
                    action == MotionEvent.ACTION_CANCEL
                ) {
                    cancelRailMoveFlush(env)
                    env.mRailMoveGeneration++
                    takePendingRailMove(env)?.recycle()
                }
                return
            }
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) {
                env.mRailHostDownTime = now
            }
            val down = env.mRailHostDownTime.takeIf { it > 0L } ?: now
            val fs = env.mCachedFullscreenPane
            val peel = env.mHuPeelGesture
            val railToAaUi = env.mAaUiRailConsume
            // Picker / Recents: AaDisplay presentation is already full-HU (1920 etc.).
            // Subtracting compositor blX shifts left-rail taps off the overlay → dead strip.
            // Peel still needs inset when content-slot compositor is active (doc §10.11).
            val xInset = if (railToAaUi) {
                0f
            } else {
                CoolwalkRailMath.compositorLeftInsetPx(CoolwalkRailCoordinator.current()).toFloat()
            }
            val toInject = rewriteMotionEvent(
                source = motion,
                downTime = down,
                eventTime = now,
                sourceOverride = InputDevice.SOURCE_TOUCHSCREEN,
                xOffset = xInset,
            )
            var retainInject = false
            try {
                if (action == MotionEvent.ACTION_MOVE) {
                    queuePendingRailMove(env, toInject)
                    retainInject = true
                    param.result = null
                    return
                }
                cancelRailMoveFlush(env)
                env.mRailMoveGeneration++
                takePendingRailMove(env)?.let { pending ->
                    try {
                        injectStolenRailEvent(env, pending, fs, peel, railToAaUi)
                    } finally {
                        pending.recycle()
                    }
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    env.mHuPeelGesture = false
                }
                val injected = injectStolenRailEvent(env, toInject, fs, peel, railToAaUi)
                if (!injected && action == MotionEvent.ACTION_DOWN) return
                param.result = null
                if (action == MotionEvent.ACTION_DOWN) {
                    logDebug(
                        CoolwalkHookEnv.TAG,
                        "AaUiHook: HU rail → " +
                            (when {
                                peel || railToAaUi -> "touchAaDisplay"
                                SplitPane.isFullscreenPane(fs) -> "touchPane($fs)"
                                else -> "touchPrimaryPane"
                            }) +
                            " x=${motion.x} y=${motion.y} injectX=${motion.x - xInset} " +
                            "inset=$xInset rail=$rail peel=$peel " +
                            "facetTarget=$railTarget picker=$railToAaUi",
                    )
                }
            } finally {
                if (!retainInject) toInject.recycle()
            }
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: HU rail steal failed", e)
        } finally {
            if (owned) motion.recycle()
        }
    }

    private fun injectStolenRailEvent(
        env: CoolwalkHookEnv,
        event: MotionEvent,
        fs: Int,
        peel: Boolean,
        railToAaUi: Boolean,
    ): Boolean {
        return when {
            // Picker / Recents first — never pane while shell capture is on.
            railToAaUi || peel -> CoreManager.tryTouchAaDisplay(event)
            SplitPane.isFullscreenPane(fs) -> CoreManager.tryTouchPane(fs, event)
            else -> CoreManager.tryTouchPrimaryPane(event)
        }
    }

    private fun queuePendingRailMove(env: CoolwalkHookEnv, event: MotionEvent) {
        synchronized(env.mRailMoveLock) {
            env.mPendingRailMove?.recycle()
            env.mPendingRailMove = event
        }
        scheduleRailMoveFlush(env)
    }

    private fun takePendingRailMove(env: CoolwalkHookEnv): MotionEvent? {
        synchronized(env.mRailMoveLock) {
            val pending = env.mPendingRailMove
            env.mPendingRailMove = null
            return pending
        }
    }

    private fun scheduleRailMoveFlush(env: CoolwalkHookEnv) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val choreographer = Choreographer.getInstance()
            choreographer.removeFrameCallback(env.mRailMoveFrameCallback)
            choreographer.postFrameCallback(env.mRailMoveFrameCallback)
        } else {
            env.mFacetEnsureHandler.removeCallbacks(env.mRailMovePostToFrame)
            env.mFacetEnsureHandler.post(env.mRailMovePostToFrame)
        }
    }

    private fun cancelRailMoveFlush(env: CoolwalkHookEnv) {
        env.mFacetEnsureHandler.removeCallbacks(env.mRailMovePostToFrame)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Choreographer.getInstance().removeFrameCallback(env.mRailMoveFrameCallback)
        }
    }

    private fun isPeelHandleHitBand(env: CoolwalkHookEnv, motion: MotionEvent): Boolean {
        val x = motion.getX(0)
        val y = motion.getY(0)
        val layoutW = env.layoutWidthPx()
        val layoutH = env.layoutHeightPx()
        if (SplitPane.peelHitContains(x, y, layoutW, layoutH)) return true
        // Soft reconnect: this process may still hold previous HU layout (e.g. 720H)
        // while touches are on a shorter car (480H) — peel center then misses.
        val live = CoolwalkRailCoordinator.observeLiveVirtualDeviceHuSize() ?: return false
        if (kotlin.math.abs(live.first - layoutW) <= 8 &&
            kotlin.math.abs(live.second - layoutH) <= 8
        ) {
            return false
        }
        return SplitPane.peelHitContains(x, y, live.first, live.second)
    }

    private fun clearUntrustedRailObservation(env: CoolwalkHookEnv) {
        val facetId = CoolwalkRailCoordinator.observedRailDisplayId()
        if (!isTrustedRailDisplayId(facetId)) {
            CoolwalkRailCoordinator.dispatchActions(
                CoolwalkRailCoordinator.onEvent(
                    RailEvent.FacetDisplayId(Display.INVALID_DISPLAY, "clear-untrusted"),
                ).second,
            )
        }
        if (facetId == Display.INVALID_DISPLAY && CoolwalkRailCoordinator.observedRailWidthPx() > 240) {
            CoolwalkRailCoordinator.dispatchActions(
                CoolwalkRailCoordinator.onEvent(
                    RailEvent.RailWidthObserved(0, "clear-untrusted-width"),
                ).second,
            )
        }
    }

    fun ensureRailObservationFromDisplays(env: CoolwalkHookEnv) {
        lastEnsureRailUptimeMs = SystemClock.uptimeMillis()
        CoolwalkRailCoordinator.syncExternalTruth()
        clearUntrustedRailObservation(env)
        if (isTrustedRailDisplayId(CoolwalkRailCoordinator.observedRailDisplayId())) return
        val dm = runCatching {
            InitFields.appContext.getSystemService(DisplayManager::class.java)
        }.getOrNull() ?: return
        for (display in dm.displays) {
            if (!isTrustedRailDisplayId(display.displayId)) continue
            if (!CoolwalkRailMath.isRailVirtualDisplayName(display.name)) continue
            val w = runCatching { display.mode.physicalWidth }.getOrDefault(0)
            val actions = mutableListOf<RailAction>()
            if (w > 1) {
                actions += CoolwalkRailCoordinator.onEvent(
                    RailEvent.RailWidthObserved(w, "discover-named"),
                ).second
            }
            actions += CoolwalkRailCoordinator.onEvent(
                RailEvent.FacetDisplayId(display.displayId, "discover-named"),
            ).second
            CoolwalkRailCoordinator.dispatchActions(actions)
            logDebug(
                CoolwalkHookEnv.TAG,
                "AaUiHook: discovered rail display id=${display.displayId} name=${display.name} w=$w",
            )
            return
        }
        for (display in dm.displays) {
            if (!isTrustedRailDisplayId(display.displayId)) continue
            if (CoolwalkRailMath.isRailVirtualDisplayName(display.name)) continue
            val w = runCatching { display.mode.physicalWidth }.getOrDefault(0)
            val h = runCatching { display.mode.physicalHeight }.getOrDefault(0)
            if (!CoolwalkRailMath.isAbsoluteNarrowRailSize(w, h)) continue
            val actions = mutableListOf<RailAction>()
            actions += CoolwalkRailCoordinator.onEvent(
                RailEvent.RailWidthObserved(w, "discover-narrow"),
            ).second
            actions += CoolwalkRailCoordinator.onEvent(
                RailEvent.FacetDisplayId(display.displayId, "discover-narrow"),
            ).second
            CoolwalkRailCoordinator.dispatchActions(actions)
            logDebug(
                CoolwalkHookEnv.TAG,
                "AaUiHook: discovered narrow rail display id=${display.displayId} name=${display.name} w=$w",
            )
            return
        }
    }

    private fun isRailTargetCarDisplay(env: CoolwalkHookEnv, carDisplayId: Any?): Boolean {
        if (carDisplayId == null) return false
        clearUntrustedRailObservation(env)
        val androidId = androidDisplayIdFromCarDisplayId(env, carDisplayId) ?: return false
        if (!isTrustedRailDisplayId(androidId)) return false
        val observedId = CoolwalkRailCoordinator.observedRailDisplayId()
        if (androidId == observedId && isTrustedRailDisplayId(observedId)) {
            return true
        }
        val display = runCatching {
            InitFields.appContext.getSystemService(DisplayManager::class.java)?.getDisplay(androidId)
        }.getOrNull() ?: return false
        if (CoolwalkRailMath.isRailVirtualDisplayName(display.name)) {
            val w = runCatching { display.mode.physicalWidth }.getOrDefault(0)
            val actions = mutableListOf<RailAction>()
            if (w > 1) {
                actions += CoolwalkRailCoordinator.onEvent(
                    RailEvent.RailWidthObserved(w, "car-display-named"),
                ).second
            }
            actions += CoolwalkRailCoordinator.onEvent(
                RailEvent.FacetDisplayId(androidId, "car-display-named"),
            ).second
            CoolwalkRailCoordinator.dispatchActions(actions)
            return true
        }
        val w = runCatching { display.mode.physicalWidth }.getOrDefault(0)
        val h = runCatching { display.mode.physicalHeight }.getOrDefault(0)
        val thin = if (env.layoutWidthPx() > 0) {
            CoolwalkRailMath.isThinRailSize(w, h, env.layoutWidthPx())
        } else {
            CoolwalkRailMath.isAbsoluteNarrowRailSize(w, h)
        }
        if (w > 1 && h > 0 && thin) {
            val actions = mutableListOf<RailAction>()
            actions += CoolwalkRailCoordinator.onEvent(
                RailEvent.RailWidthObserved(w, "car-display-thin"),
            ).second
            actions += CoolwalkRailCoordinator.onEvent(
                RailEvent.FacetDisplayId(androidId, "car-display-thin"),
            ).second
            CoolwalkRailCoordinator.dispatchActions(actions)
            return true
        }
        return false
    }

    private fun resolveCarDisplayIdAccessors(clazz: Class<*>): CarDisplayIdAccessors {
        carDisplayIdAccessorsByClass[clazz]?.let { return it }
        val methods = clazz.methods.filter { m ->
            m.parameterCount == 0 &&
                m.name in carDisplayIdAccessorNames &&
                (m.returnType == Int::class.javaPrimitiveType || m.returnType == Integer::class.java)
        }.onEach { it.isAccessible = true }
        val fields = clazz.declaredFields.filter { f ->
            !Modifier.isStatic(f.modifiers) &&
                (f.name in carDisplayIdAccessorNames || f.name in carDisplayIdFieldNames) &&
                (f.type == Int::class.javaPrimitiveType || f.type == Integer::class.java)
        }.onEach { it.isAccessible = true }
        val resolved = CarDisplayIdAccessors(methods, fields)
        if (methods.isNotEmpty() || fields.isNotEmpty()) {
            carDisplayIdAccessorsByClass[clazz] = resolved
        }
        return resolved
    }

    private fun androidDisplayIdFromCarDisplayId(env: CoolwalkHookEnv, carDisplayId: Any): Int? {
        when (carDisplayId) {
            is Int -> return carDisplayId.takeIf { it != Display.INVALID_DISPLAY }
            is Number -> return carDisplayId.toInt().takeIf { it != Display.INVALID_DISPLAY }
        }
        val accessors = resolveCarDisplayIdAccessors(carDisplayId.javaClass)
        val candidates = linkedSetOf<Int>()
        for (m in accessors.methods) {
            runCatching {
                val v = (m.invoke(carDisplayId) as? Number)?.toInt() ?: return@runCatching
                if (v >= 0) candidates += v
            }
        }
        for (f in accessors.fields) {
            runCatching {
                val v = (f.get(carDisplayId) as? Number)?.toInt() ?: return@runCatching
                if (v >= 0) candidates += v
            }
        }
        if (candidates.isEmpty()) return null
        val observedId = CoolwalkRailCoordinator.observedRailDisplayId()
        if (observedId in candidates && isTrustedRailDisplayId(observedId)) {
            return observedId
        }
        val dm = runCatching {
            InitFields.appContext.getSystemService(DisplayManager::class.java)
        }.getOrNull()
        if (dm != null) {
            for (id in candidates) {
                if (!isTrustedRailDisplayId(id)) continue
                val d = dm.getDisplay(id) ?: continue
                if (CoolwalkRailMath.isRailVirtualDisplayName(d.name)) return id
            }
            for (id in candidates) {
                if (!isTrustedRailDisplayId(id)) continue
                val d = dm.getDisplay(id) ?: continue
                val w = runCatching { d.mode.physicalWidth }.getOrDefault(0)
                val h = runCatching { d.mode.physicalHeight }.getOrDefault(0)
                val thin = if (env.layoutWidthPx() > 0) {
                    CoolwalkRailMath.isThinRailSize(w, h, env.layoutWidthPx())
                } else {
                    CoolwalkRailMath.isAbsoluteNarrowRailSize(w, h)
                }
                if (w > 1 && h > 0 && thin) return id
            }
        }
        return candidates.singleOrNull { isTrustedRailDisplayId(it) }
    }

    private fun resolvePteLayout(pte: Any): PteLayout? {
        val clazz = pte.javaClass
        pteLayoutByClass[clazz]?.let { return it }
        val intFields = mutableListOf<Field>()
        var pointersField: Field? = null
        for (f in clazz.declaredFields) {
            if (Modifier.isStatic(f.modifiers)) continue
            f.isAccessible = true
            when (f.type) {
                Int::class.javaPrimitiveType -> intFields += f
                else -> {
                    if (f.type.isArray) {
                        pointersField = f
                    } else {
                        val v = runCatching { f.get(pte) }.getOrNull()
                        if (v != null && v.javaClass.isArray) pointersField = f
                    }
                }
            }
        }
        val actionField = intFields.getOrNull(0) ?: return null
        val pf = pointersField ?: return null
        val arr = runCatching { pf.get(pte) as? Array<*> }.getOrNull()
        val pointerClass = arr?.firstOrNull()?.javaClass
            ?: arr?.javaClass?.componentType
            ?: pf.type.componentType
            ?: return null
        val pInts = pointerClass.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .onEach { it.isAccessible = true }
        if (pInts.size < 3) return null
        val layout = PteLayout(
            actionField = actionField,
            pointersField = pf,
            pointerX = pInts[0],
            pointerY = pInts[1],
            pointerId = pInts[2],
        )
        pteLayoutByClass[clazz] = layout
        return layout
    }

    private fun projectionTouchEventToMotionEvent(env: CoolwalkHookEnv, pte: Any?): MotionEvent? {
        if (pte == null) return null
        return try {
            val layout = resolvePteLayout(pte) ?: return null
            val action = layout.actionField.getInt(pte)
            val pointers = layout.pointersField.get(pte) as? Array<*> ?: return null
            if (pointers.isEmpty()) return null
            val buffers = ptePointerBuffers.get()!!
            buffers.ensure(pointers.size)
            val props = buffers.props
            val coords = buffers.coords
            for (i in pointers.indices) {
                val p = pointers[i] ?: return null
                val x = layout.pointerX.getInt(p)
                val y = layout.pointerY.getInt(p)
                val id = layout.pointerId.getInt(p)
                props[i].id = id
                props[i].toolType = MotionEvent.TOOL_TYPE_FINGER
                coords[i].x = x.toFloat()
                coords[i].y = y.toFloat()
                coords[i].pressure = 1f
                coords[i].size = 1f
            }
            val now = SystemClock.uptimeMillis()
            when (action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> env.mRailHostDownTime = now
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { }
            }
            val down = env.mRailHostDownTime.takeIf { it > 0L } ?: now
            MotionEvent.obtain(
                down,
                now,
                action,
                pointers.size,
                props,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        } catch (e: Throwable) {
            pte.javaClass.let { pteLayoutByClass.remove(it) }
            log(CoolwalkHookEnv.TAG, "AaUiHook: ProjectionTouchEvent decode failed", e)
            null
        }
    }

    private fun registerSplitStateReceiver(env: CoolwalkHookEnv) {
        if (env.mSplitStateReceiver != null) return
        val ctx = InitFields.appContext
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != AABroadcastConst.ACTION_SPLIT_STATE_CHANGED) return
                if (!intent.hasExtra(AABroadcastConst.EXTRA_FULLSCREEN_PANE)) return
                env.mSplitStateSeen = true
                env.mCachedFullscreenPane = intent.getIntExtra(
                    AABroadcastConst.EXTRA_FULLSCREEN_PANE,
                    SplitPane.FULLSCREEN_NONE,
                )
            }
        }
        try {
            ctx.registerReceiver(
                receiver,
                IntentFilter(AABroadcastConst.ACTION_SPLIT_STATE_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
            env.mSplitStateReceiver = receiver
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: registered SPLIT_STATE_CHANGED for rail fullscreen cache")
            env.mFacetEnsureHandler.post {
                if (env.mSplitStateSeen) return@post
                env.mCachedFullscreenPane = try {
                    CoreManager.splitFullscreenPane
                } catch (_: Throwable) {
                    env.mCachedFullscreenPane
                }
            }
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: register SPLIT_STATE_CHANGED failed", e)
        }
    }

    private fun registerAaUiRailConsumeReceiver(env: CoolwalkHookEnv) {
        if (env.mRailConsumeReceiver != null) return
        val ctx = InitFields.appContext
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != AABroadcastConst.ACTION_AA_UI_RAIL_CONSUME) return
                env.mAaUiRailConsume = intent.getBooleanExtra(
                    AABroadcastConst.EXTRA_AA_UI_RAIL_CONSUME,
                    false,
                )
                logDebug(CoolwalkHookEnv.TAG, "AaUiHook: aaUiRailConsume=${env.mAaUiRailConsume}")
            }
        }
        try {
            ctx.registerReceiver(
                receiver,
                IntentFilter(AABroadcastConst.ACTION_AA_UI_RAIL_CONSUME),
                Context.RECEIVER_EXPORTED,
            )
            env.mRailConsumeReceiver = receiver
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: registered AA_UI_RAIL_CONSUME for picker rail routing")
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: register AA_UI_RAIL_CONSUME failed", e)
        }
    }
}
