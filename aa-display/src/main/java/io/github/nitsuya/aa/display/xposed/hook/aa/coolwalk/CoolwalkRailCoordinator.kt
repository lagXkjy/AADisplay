package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.content.ContentResolver
import android.os.SystemClock
import android.view.Display
import com.github.kyuubiran.ezxhelper.init.InitFields
import io.github.nitsuya.aa.display.util.CoolwalkRailStore
import io.github.nitsuya.aa.display.util.DisplayProfileSettle
import io.github.nitsuya.aa.display.xposed.CoreManager
import io.github.nitsuya.aa.display.xposed.util.logDebug

/**
 * Single source of Coolwalk rail state inside gearhead hooks.
 * All facet/rail hooks read/write through here; reports to system_server for profile settle.
 */
object CoolwalkRailCoordinator {
    const val TAG = "AAD_CoolwalkRail"

    /** Debounce window-scans / VD starve on rapid projection config republication. */
    private const val RECONNECT_RECLAIM_DEBOUNCE_MS = 500L

    /** Gap since last projection config → treat as soft reconnect (gearhead survives). */
    private const val PROJECTION_SESSION_GAP_MS = 8_000L

    @Volatile
    private var snapshot = RailSnapshot()

    /** Unset sentinel — distinct from a real uptime of 0 right after first config. */
    private const val UNSET_PROJECTION_MS = -1L

    @Volatile
    private var lastProjectionConfigUptimeMs = UNSET_PROJECTION_MS

    @Volatile
    private var lastReconnectReclaimUptimeMs = -1L

    data class ProjectionSessionResult(
        val reconnectStarted: Boolean,
        val shouldReclaim: Boolean,
    )

    fun current(): RailSnapshot = snapshot

    /**
     * Projection config republication (soft reconnect). Separates state reset from reclaim:
     * reclaim must still run when [RailPhase.RailPresent] — the common case when FacetBar
     * never left and [ReconnectStarted] would otherwise be skipped.
     */
    fun onProjectionConfigSignal(reason: String, syncExternal: Boolean = true): ProjectionSessionResult {
        if (syncExternal) {
            runCatching { syncExternalTruth() }
        }
        val now = SystemClock.uptimeMillis()
        val sessionGap = when {
            lastProjectionConfigUptimeMs == UNSET_PROJECTION_MS -> Long.MAX_VALUE
            else -> (now - lastProjectionConfigUptimeMs).coerceAtLeast(0L)
        }
        lastProjectionConfigUptimeMs = now
        val reconnectStarted = maybeBeginReconnectIfNeeded(reason, sessionGap)
        val shouldReclaim = shouldRunReconnectReclaim(sessionGap, reconnectStarted)
        val reclaim = shouldReclaim && (
            lastReconnectReclaimUptimeMs < 0L ||
                now - lastReconnectReclaimUptimeMs >= RECONNECT_RECLAIM_DEBOUNCE_MS
            )
        if (reclaim) {
            lastReconnectReclaimUptimeMs = now
            logDebug(TAG, "reconnect reclaim armed [$reason] phase=${snapshot.phase} gap=$sessionGap")
        }
        return ProjectionSessionResult(reconnectStarted, reclaim)
    }

    /**
     * Gearhead survives AA disconnect; the next connection's LayoutInfo often runs before
     * content_bounds. Clear prior-session reclaim state once per reconnect.
     *
     * @return true when [RailEvent.ReconnectStarted] was dispatched
     */
    fun maybeBeginReconnectIfNeeded(
        reason: String,
        sessionGapMs: Long = Long.MAX_VALUE,
    ): Boolean {
        val snap = snapshot
        if (snap.fullHuWidthPx <= 0 && snap.phase == RailPhase.Bootstrapping) return false
        if (snap.phase == RailPhase.ReconnectSettling) return false
        // Mid-reclaim on this connection — do not wipe live content_bounds progress.
        if (snap.phase == RailPhase.Reclaiming && snap.fullBleedStableCount > 0 &&
            sessionGapMs <= PROJECTION_SESSION_GAP_MS
        ) {
            return false
        }
        // Same-session rail republication — not a reconnect reset.
        if (snap.phase == RailPhase.RailPresent && sessionGapMs <= PROJECTION_SESSION_GAP_MS) return false
        // Stable full-bleed + rapid config republication — reclaim only, do not wipe HU truth.
        if (snap.phase == RailPhase.FullBleed && sessionGapMs <= PROJECTION_SESSION_GAP_MS) return false
        val actions = onEvent(RailEvent.ReconnectStarted(reason)).second
        runCatching { dispatchActions(actions) }
        return true
    }

    private fun shouldRunReconnectReclaim(sessionGapMs: Long, reconnectStarted: Boolean): Boolean {
        if (reconnectStarted) return true
        val snap = snapshot
        if (snap.fullHuWidthPx <= 0 && snap.phase == RailPhase.Bootstrapping) return false
        return when (snap.phase) {
            RailPhase.RailPresent,
            RailPhase.ReconnectSettling,
            RailPhase.Reclaiming,
            -> true
            RailPhase.FullBleed -> sessionGapMs > PROJECTION_SESSION_GAP_MS
            RailPhase.Bootstrapping -> false
        }
    }

    fun onEvent(event: RailEvent): Pair<RailSnapshot, List<RailAction>> {
        val now = SystemClock.uptimeMillis()
        var next = snapshot
        val actions = mutableListOf<RailAction>()
        when (event) {
            is RailEvent.LayoutInfo -> {
                syncExternalTruth()
                val anchorFull = resolveAnchorFullHuWidth(
                    next.fullHuWidthPx,
                    next.layoutHeightPx.coerceAtLeast(event.heightPx),
                    next.touchRailWidthPx,
                )
                val picked = CoolwalkRailMath.pickConnectionFullHuWidth(
                    anchorFull,
                    next.layoutHeightPx.coerceAtLeast(event.heightPx),
                    event.widthPx,
                    event.heightPx,
                    next.touchRailWidthPx,
                )
                val resolvedFull = picked
                next = next.copy(
                    layoutWidthPx = event.widthPx,
                    layoutHeightPx = event.heightPx,
                    fullHuWidthPx = resolvedFull,
                    lastEvent = event.reason,
                    updatedUptimeMs = now,
                )
                if (next.phase == RailPhase.Bootstrapping) {
                    next = next.copy(phase = RailPhase.Reclaiming)
                }
            }
            is RailEvent.RailWidthObserved -> {
                if (event.widthPx > 1) {
                    // Align with FacetBarVdCreate: starve / thin-VD observe must not wipe
                    // FullBleed or Reclaiming (that demoted DrawingSpec to hold content slot).
                    // True soft reconnect only via ReconnectStarted / projection session gap.
                    val phase = when (next.phase) {
                        RailPhase.FullBleed,
                        RailPhase.Reclaiming,
                        -> next.phase
                        RailPhase.ReconnectSettling -> RailPhase.ReconnectSettling
                        else -> RailPhase.RailPresent
                    }
                    next = next.copy(
                        touchRailWidthPx = event.widthPx,
                        phase = phase,
                        lastEvent = event.reason,
                        updatedUptimeMs = now,
                    )
                }
            }
            is RailEvent.FullHuObserved -> {
                val picked = CoolwalkRailMath.pickConnectionFullHuWidth(
                    next.fullHuWidthPx,
                    next.layoutHeightPx,
                    event.widthPx,
                    event.heightPx,
                    next.touchRailWidthPx,
                )
                if (picked != next.fullHuWidthPx || event.heightPx > next.layoutHeightPx) {
                    next = next.copy(
                        fullHuWidthPx = picked,
                        layoutHeightPx = event.heightPx.takeIf { it > 0 } ?: next.layoutHeightPx,
                        lastEvent = event.reason,
                        updatedUptimeMs = now,
                    )
                }
            }
            is RailEvent.FacetDisplayId -> {
                if (event.displayId != Display.INVALID_DISPLAY && event.displayId != Display.DEFAULT_DISPLAY) {
                    next = next.copy(
                        facetDisplayId = event.displayId,
                        lastEvent = event.reason,
                        updatedUptimeMs = now,
                    )
                }
            }
            is RailEvent.ContentBoundsExpanded -> {
                val rail = if (event.railWidthPx > 1) event.railWidthPx else next.touchRailWidthPx
                val mergedFull = CoolwalkRailMath.pickConnectionFullHuWidth(
                    next.fullHuWidthPx,
                    next.layoutHeightPx,
                    event.fullWidthPx,
                    event.fullHeightPx,
                    rail,
                )
                next = next.copy(
                    effectiveRailWidthPx = 0,
                    fullHuWidthPx = mergedFull,
                    layoutWidthPx = mergedFull,
                    layoutHeightPx = event.fullHeightPx.coerceAtLeast(next.layoutHeightPx),
                    touchRailWidthPx = if (event.railWidthPx > 1) event.railWidthPx else next.touchRailWidthPx,
                    fullBleedStableCount = next.fullBleedStableCount + 1,
                    lastEvent = event.reason,
                    updatedUptimeMs = now,
                )
                if (next.fullBleedStableCount >= CoolwalkRailMath.FULL_BLEED_STABLE_THRESHOLD) {
                    next = next.copy(phase = RailPhase.FullBleed)
                } else {
                    next = next.copy(phase = RailPhase.Reclaiming)
                }
                actions += RailAction.ReclaimAllGutters(event.reason)
            }
            is RailEvent.FacetBarVdCreate -> {
                if (event.requestedWidthPx > 1) {
                    next = next.copy(
                        touchRailWidthPx = event.requestedWidthPx,
                        lastEvent = event.reason,
                        updatedUptimeMs = now,
                    )
                    // Starving to 1px is expected — do not wipe FullBleed / stable count.
                    if (next.phase == RailPhase.Bootstrapping) {
                        next = next.copy(phase = RailPhase.Reclaiming)
                    }
                }
            }
            is RailEvent.ReconnectStarted -> {
                // Drop previous connection's HU; the next LayoutInfo / content_bounds
                // is the only truth for this session (cars and resolutions vary).
                val cr = runCatching { InitFields.appContext.contentResolver }.getOrNull()
                CoolwalkRailStore.markReconnectEpoch(cr)
                next = RailSnapshot(
                    phase = RailPhase.ReconnectSettling,
                    lastEvent = event.reason,
                    updatedUptimeMs = now,
                )
            }
            is RailEvent.GutterReclaim -> {
                next = next.copy(
                    lastEvent = event.reason,
                    updatedUptimeMs = now,
                )
                if (shouldReclaimAllGutters(next, event.reason)) {
                    actions += RailAction.ReclaimAllGutters(event.reason)
                }
            }
        }
        snapshot = next
        actions += RailAction.NotifyServer(next)
        logDebug(TAG, "phase=${next.phase} full=${next.fullHuWidthPx} touchRail=${next.touchRailWidthPx} event=${event.reason}")
        return next to actions
    }

    fun shouldReclaimAllGutters(reason: String): Boolean {
        return shouldReclaimAllGutters(snapshot, reason)
    }

    private fun shouldReclaimAllGutters(s: RailSnapshot, reason: String): Boolean {
        if (reason.contains("starve", ignoreCase = true) ||
            reason.contains("collapse", ignoreCase = true) ||
            reason.contains("reconnect", ignoreCase = true) ||
            s.phase == RailPhase.ReconnectSettling
        ) {
            return true
        }
        // FullBleed only skips blind poll noise — late chrome on reconnect still reclaims.
        if (s.phase == RailPhase.FullBleed &&
            s.fullBleedStableCount >= CoolwalkRailMath.FULL_BLEED_STABLE_THRESHOLD &&
            reason.endsWith("-poll")
        ) {
            return false
        }
        return true
    }

    /** Local content_bounds reclaim must not be wiped by stale ReconnectSettling IPC. */
    private fun shouldPreserveLocalReclaimProgress(): Boolean =
        (snapshot.phase == RailPhase.Reclaiming || snapshot.phase == RailPhase.FullBleed) &&
            snapshot.fullHuWidthPx > 0

    private fun mergePhasePreferringReclaim(local: RailPhase, server: RailPhase): RailPhase {
        if (local == RailPhase.FullBleed) return RailPhase.FullBleed
        if (local == RailPhase.Reclaiming && snapshot.fullHuWidthPx > 0 &&
            server == RailPhase.ReconnectSettling
        ) {
            return RailPhase.Reclaiming
        }
        return server
    }

    /**
     * Pull IPC / Settings into this process only when empty or the same head-unit.
     * A previous car's stored width must not replace this connection's LayoutInfo.
     */
    private fun absorbExternalFullHu(localFull: Int, localH: Int, externalFull: Int, rail: Int): Int {
        if (externalFull <= 0) return localFull
        if (localFull <= 0) return externalFull
        if (!CoolwalkRailMath.isSameHuGeometry(localFull, localH, externalFull, localH, rail)) {
            return localFull
        }
        return CoolwalkRailMath.pickConnectionFullHuWidth(localFull, localH, externalFull, localH, rail)
    }

    /**
     * Merge session / IPC / Settings into a single anchor before LayoutInfo or content_bounds
     * can shrink this connection's HU width (e.g. transient 600 on 800×480 reconnect).
     */
    internal fun resolveAnchorFullHuWidth(localFull: Int, localH: Int, railPx: Int): Int {
        val cr = runCatching { InitFields.appContext.contentResolver }.getOrNull()
        var anchor = localFull.coerceAtLeast(0)
        val deferSession = snapshot.phase == RailPhase.ReconnectSettling &&
            snapshot.fullBleedStableCount == 0 &&
            snapshot.fullHuWidthPx <= 0
        if (!deferSession) {
            CoolwalkRailStore.resolvedSession(cr)?.fullHuWidthPx?.takeIf { it > 0 }?.let { sessionFull ->
                anchor = CoolwalkRailMath.pickConnectionFullHuWidth(
                    anchor,
                    localH,
                    sessionFull,
                    localH,
                    maxOf(railPx, CoolwalkRailStore.resolvedSession(cr)?.touchRailWidthPx ?: 0),
                )
            }
        }
        CoreManager.tryGetCoolwalkRailSnapshot()?.getOrNull(2)?.takeIf { it > 0 }?.let { ipcFull ->
            anchor = CoolwalkRailMath.pickConnectionFullHuWidth(anchor, localH, ipcFull, localH, railPx)
        }
        return anchor
    }

    /** Merge server IPC + Settings.Global so :car can expand before :projection LayoutInfo. */
    fun syncExternalTruth(cr: ContentResolver? = null) {
        val resolver = cr ?: runCatching { InitFields.appContext.contentResolver }.getOrNull()
        var mergedFull = snapshot.fullHuWidthPx
        var mergedTouch = snapshot.touchRailWidthPx
        var mergedLayoutW = snapshot.layoutWidthPx
        var mergedLayoutH = snapshot.layoutHeightPx
        val server = CoolwalkRailStore.sanitizeCrossBoot(CoolwalkRailStore.serverSnapshot)
        val railForMerge = maxOf(snapshot.touchRailWidthPx, server.touchRailWidthPx)
        mergedFull = absorbExternalFullHu(mergedFull, mergedLayoutH, server.fullHuWidthPx, railForMerge)
        if (server.touchRailWidthPx > mergedTouch) mergedTouch = server.touchRailWidthPx
        if (server.layoutWidthPx > mergedLayoutW) mergedLayoutW = server.layoutWidthPx
        if (server.layoutHeightPx > mergedLayoutH) mergedLayoutH = server.layoutHeightPx
        resolver?.let { r ->
            val stored = CoolwalkRailStore.read(r)
            mergedFull = absorbExternalFullHu(mergedFull, mergedLayoutH, stored.fullHuWidthPx, railForMerge)
            if (stored.touchRailWidthPx > mergedTouch) mergedTouch = stored.touchRailWidthPx
        }
        val ipc = CoreManager.tryGetCoolwalkRailSnapshot()
        if (ipc != null && ipc.size >= 4) {
            val ipcPhase = RailPhase.fromCode(ipc[0])
            if (ipcPhase == RailPhase.ReconnectSettling &&
                snapshot.phase != RailPhase.ReconnectSettling &&
                snapshot.phase != RailPhase.Bootstrapping &&
                !shouldPreserveLocalReclaimProgress()
            ) {
                snapshot = RailSnapshot(
                    phase = RailPhase.ReconnectSettling,
                    touchRailWidthPx = maxOf(snapshot.touchRailWidthPx, ipc[1]),
                    facetDisplayId = ipc[3].takeIf { it != Display.INVALID_DISPLAY } ?: snapshot.facetDisplayId,
                    lastEvent = "ipc-reconnect",
                    updatedUptimeMs = SystemClock.uptimeMillis(),
                )
                lastReconnectReclaimUptimeMs = SystemClock.uptimeMillis()
            }
            val ipcFull = ipc[2]
            val ipcTouch = ipc[1]
            // :car may already FullBleed on a new HU while this process still holds the
            // previous car's FullBleed. absorbExternalFullHu keeps local on mismatch —
            // when live VirtualDevice matches IPC, replace (incl. shrinking touchRail).
            val live = observeLiveVirtualDeviceHuSize()
            if (ipcFull > 0 && live != null &&
                kotlin.math.abs(live.first - ipcFull) <= 2 &&
                snapshot.fullHuWidthPx > 0 &&
                snapshot.layoutHeightPx > 0 &&
                !CoolwalkRailMath.isSameHuGeometry(
                    snapshot.fullHuWidthPx,
                    snapshot.layoutHeightPx,
                    live.first,
                    live.second,
                    maxOf(snapshot.touchRailWidthPx, ipcTouch),
                )
            ) {
                snapshot = snapshot.copy(
                    phase = when (ipcPhase) {
                        RailPhase.Bootstrapping -> RailPhase.Reclaiming
                        else -> ipcPhase
                    },
                    fullHuWidthPx = ipcFull,
                    touchRailWidthPx = ipcTouch.takeIf { it > 0 } ?: 0,
                    layoutWidthPx = live.first,
                    layoutHeightPx = live.second,
                    facetDisplayId = ipc[3].takeIf { it != Display.INVALID_DISPLAY }
                        ?: snapshot.facetDisplayId,
                    fullBleedStableCount = maxOf(snapshot.fullBleedStableCount, 1),
                    lastEvent = "ipc-live-hu-replace",
                    updatedUptimeMs = SystemClock.uptimeMillis(),
                )
                return
            }
            mergedFull = absorbExternalFullHu(mergedFull, mergedLayoutH, ipcFull, railForMerge)
            if (ipcTouch > mergedTouch) mergedTouch = ipcTouch
        }
        if (mergedFull != snapshot.fullHuWidthPx ||
            mergedTouch > snapshot.touchRailWidthPx ||
            mergedLayoutW > snapshot.layoutWidthPx ||
            mergedLayoutH > snapshot.layoutHeightPx
        ) {
            snapshot = snapshot.copy(
                fullHuWidthPx = mergedFull,
                touchRailWidthPx = mergedTouch,
                layoutWidthPx = mergedLayoutW,
                layoutHeightPx = mergedLayoutH,
            )
        }
    }

    /**
     * Primary landscape VirtualDevice size (HU), or null. Skips FacetBar / Dashboard /
     * AADisplay split VDs and the phone DEFAULT_DISPLAY.
     */
    internal fun observeLiveVirtualDeviceHuSize(): Pair<Int, Int>? {
        val ctx = runCatching { InitFields.appContext }.getOrNull() ?: return null
        val dm = ctx.getSystemService(android.hardware.display.DisplayManager::class.java) ?: return null
        var bestW = 0
        var bestH = 0
        for (display in dm.displays) {
            if (display.displayId == Display.DEFAULT_DISPLAY) continue
            val name = runCatching { display.name }.getOrNull() ?: continue
            if (DisplayProfileSettle.isRailDisplayName(name)) continue
            if (name.contains("Dashboard", ignoreCase = true)) continue
            if (name.startsWith("AADisplay-")) continue
            if (name.contains("AaDisplayActivity", ignoreCase = true)) continue
            // Coolwalk template / car-app VDs are content slots, not the HU.
            if (name.contains("TemplateCar", ignoreCase = true)) continue
            if (name.contains("CarAppService", ignoreCase = true)) continue
            val w = runCatching { display.mode.physicalWidth }.getOrNull() ?: continue
            val h = runCatching { display.mode.physicalHeight }.getOrNull() ?: continue
            if (w <= h || w <= 1 || h <= 1) continue
            if (w > bestW) {
                bestW = w
                bestH = h
            }
        }
        return if (bestW > 0 && bestH > 0) bestW to bestH else null
    }

    /** Local coordinator state merged with system_server session cache (IPC). */
    fun effectiveSnapshot(): RailSnapshot {
        syncExternalTruth()
        val cr = runCatching { InitFields.appContext.contentResolver }.getOrNull()
        return CoolwalkRailStore.snapshotWithSession(snapshot, cr)
    }

    /** True when a soft-reconnect reclaim ran recently — content_bounds must not idempotent-skip. */
    fun recentReconnectReclaim(windowMs: Long = 30_000L): Boolean {
        val now = SystemClock.uptimeMillis()
        val t = lastReconnectReclaimUptimeMs
        if (t >= 0L && now - t < windowMs) return true
        val cr = runCatching { InitFields.appContext.contentResolver }.getOrNull()
        if (CoolwalkRailStore.isRecentReconnectEpoch(cr, windowMs)) return true
        val ipcEpoch = CoreManager.tryGetCoolwalkReconnectEpochMs()
        return ipcEpoch > 0L && now - ipcEpoch < windowMs
    }

    fun bestObservedFullHuWidthPx(): Int = effectiveSnapshot().fullHuWidthPx

    fun railHitWidthPx(): Int = CoolwalkRailMath.railHitWidthPx(snapshot)

    fun layoutWidthPx(): Int = CoolwalkRailMath.layoutWidthPx(snapshot)

    fun layoutHeightPx(): Int = snapshot.layoutHeightPx.takeIf { it > 0 } ?: 0

    fun observedFullHuWidthPx(): Int = snapshot.fullHuWidthPx

    fun observedRailWidthPx(): Int = snapshot.touchRailWidthPx

    fun observedRailDisplayId(): Int = snapshot.facetDisplayId

    fun rememberFullHuSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || width <= height) return
        // Landscape HU band — no fixed pixel floor (head units vary).
        if (height < width * 0.25f) return
        val knownFull = maxOf(snapshot.fullHuWidthPx, snapshot.layoutWidthPx)
        val rail = snapshot.touchRailWidthPx
        // Content slot (HU − rail) is not a new full HU.
        if (knownFull > width + 2 &&
            CoolwalkRailMath.isPlausibleRailGap(knownFull - width, knownFull, rail)
        ) {
            return
        }
        // Grow-only. Cross-HU replacement belongs in syncExternalTruth (ipc-live-hu-replace)
        // / explicit ReconnectStarted — never thrash ReconnectStarted from Template vs HU VDs.
        if (width > snapshot.fullHuWidthPx || height > snapshot.layoutHeightPx) {
            onEvent(RailEvent.FullHuObserved(width, height, "vd-size"))
        }
    }

    fun dispatchActions(actions: List<RailAction>) {
        for (action in actions) {
            when (action) {
                is RailAction.NotifyServer -> CoreManager.tryReportCoolwalkRailSnapshot(action.snapshot)
                is RailAction.ReclaimAllGutters -> CoolwalkFacetChrome.reclaimAllWindowGutters(action.reason)
            }
        }
    }

    fun applyServerSnapshot(server: RailSnapshot) {
        val effective = resolveServerSnapshot(server)
        if (effective.phase == RailPhase.ReconnectSettling && effective.fullHuWidthPx <= 0) {
            snapshot = RailSnapshot(
                phase = RailPhase.ReconnectSettling,
                lastEvent = effective.lastEvent,
                updatedUptimeMs = effective.updatedUptimeMs.coerceAtLeast(snapshot.updatedUptimeMs),
            )
            return
        }
        val serverResolved = effective
        val rail = maxOf(snapshot.touchRailWidthPx, serverResolved.touchRailWidthPx)
        val measuredW = serverResolved.fullHuWidthPx.takeIf { it > 0 } ?: serverResolved.layoutWidthPx
        val measuredH = serverResolved.layoutHeightPx.takeIf { it > 0 } ?: snapshot.layoutHeightPx
        val picked = CoolwalkRailMath.pickConnectionFullHuWidth(
            snapshot.fullHuWidthPx,
            snapshot.layoutHeightPx,
            measuredW,
            measuredH,
            rail,
        )
        if (serverResolved.updatedUptimeMs <= snapshot.updatedUptimeMs &&
            picked == snapshot.fullHuWidthPx
        ) {
            return
        }
        snapshot = snapshot.copy(
            phase = mergePhasePreferringReclaim(snapshot.phase, serverResolved.phase),
            touchRailWidthPx = serverResolved.touchRailWidthPx.takeIf { it > 0 } ?: snapshot.touchRailWidthPx,
            fullHuWidthPx = picked,
            layoutWidthPx = serverResolved.layoutWidthPx.takeIf { it > 0 } ?: snapshot.layoutWidthPx,
            layoutHeightPx = serverResolved.layoutHeightPx.takeIf { it > 0 } ?: snapshot.layoutHeightPx,
            facetDisplayId = serverResolved.facetDisplayId.takeIf { it != Display.INVALID_DISPLAY }
                ?: snapshot.facetDisplayId,
            fullBleedStableCount = maxOf(
                snapshot.fullBleedStableCount,
                serverResolved.fullBleedStableCount,
            ),
            updatedUptimeMs = serverResolved.updatedUptimeMs.coerceAtLeast(snapshot.updatedUptimeMs),
            lastEvent = serverResolved.lastEvent.ifEmpty { snapshot.lastEvent },
        )
        if (serverResolved.phase == RailPhase.Reclaiming &&
            serverResolved.fullHuWidthPx > 0 &&
            server.phase == RailPhase.ReconnectSettling
        ) {
            dispatchActions(listOf(RailAction.ReclaimAllGutters("session-reconnect")))
        }
    }

    /** system_server may publish ReconnectSettling with cleared HU; IPC carries session merge. */
    private fun resolveServerSnapshot(server: RailSnapshot): RailSnapshot {
        if (server.phase != RailPhase.ReconnectSettling || server.fullHuWidthPx > 0) {
            return server
        }
        val ipc = CoreManager.tryGetCoolwalkRailSnapshot()
        if (ipc != null && ipc.size >= 4) {
            CoolwalkRailStore.snapshotFromWire(ipc)?.let { merged ->
                if (merged.fullHuWidthPx > 0) return merged
            }
        }
        return server
    }
}
