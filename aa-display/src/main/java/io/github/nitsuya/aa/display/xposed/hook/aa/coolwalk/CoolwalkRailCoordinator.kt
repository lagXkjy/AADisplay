package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.content.ContentResolver
import android.os.SystemClock
import android.view.Display
import com.github.kyuubiran.ezxhelper.init.InitFields
import io.github.nitsuya.aa.display.util.CoolwalkRailStore
import io.github.nitsuya.aa.display.xposed.CoreManager
import io.github.nitsuya.aa.display.xposed.util.logDebug

/**
 * Single source of Coolwalk rail state inside gearhead hooks.
 * All facet/rail hooks read/write through here; reports to system_server for profile settle.
 */
object CoolwalkRailCoordinator {
    const val TAG = "AAD_CoolwalkRail"

    @Volatile
    private var snapshot = RailSnapshot()

    fun current(): RailSnapshot = snapshot

    fun onEvent(event: RailEvent): Pair<RailSnapshot, List<RailAction>> {
        val now = SystemClock.uptimeMillis()
        var next = snapshot
        val actions = mutableListOf<RailAction>()
        when (event) {
            is RailEvent.LayoutInfo -> {
                val picked = CoolwalkRailMath.pickConnectionFullHuWidth(
                    next.fullHuWidthPx,
                    next.layoutHeightPx,
                    event.widthPx,
                    event.heightPx,
                    next.touchRailWidthPx,
                )
                next = next.copy(
                    layoutWidthPx = event.widthPx,
                    layoutHeightPx = event.heightPx,
                    fullHuWidthPx = picked,
                    lastEvent = event.reason,
                    updatedUptimeMs = now,
                )
                if (next.phase == RailPhase.Bootstrapping) {
                    next = next.copy(phase = RailPhase.Reclaiming)
                }
            }
            is RailEvent.RailWidthObserved -> {
                if (event.widthPx > 1) {
                    next = next.copy(
                        touchRailWidthPx = event.widthPx,
                        phase = if (next.phase == RailPhase.FullBleed) RailPhase.ReconnectSettling else RailPhase.RailPresent,
                        fullBleedStableCount = 0,
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
                } else if (event.reason.contains("windowAttach", ignoreCase = true)) {
                    actions += RailAction.ReclaimLeftGutter(event.reason)
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

    /** Merge server IPC + Settings.Global so :car can expand before :projection LayoutInfo. */
    fun syncExternalTruth(cr: ContentResolver? = null) {
        val resolver = cr ?: runCatching { InitFields.appContext.contentResolver }.getOrNull()
        var mergedFull = snapshot.fullHuWidthPx
        var mergedTouch = snapshot.touchRailWidthPx
        var mergedLayoutW = snapshot.layoutWidthPx
        var mergedLayoutH = snapshot.layoutHeightPx
        var mergedPhase = snapshot.phase
        var mergedUpdated = snapshot.updatedUptimeMs
        val server = CoolwalkRailStore.serverSnapshot
        val railForMerge = maxOf(snapshot.touchRailWidthPx, server.touchRailWidthPx, mergedTouch)
        mergedFull = absorbExternalFullHu(mergedFull, mergedLayoutH, server.fullHuWidthPx, railForMerge)
        if (server.touchRailWidthPx > mergedTouch) mergedTouch = server.touchRailWidthPx
        if (server.layoutWidthPx > mergedLayoutW) mergedLayoutW = server.layoutWidthPx
        if (server.layoutHeightPx > mergedLayoutH) mergedLayoutH = server.layoutHeightPx
        if (server.updatedUptimeMs > mergedUpdated) {
            mergedPhase = server.phase
            mergedUpdated = server.updatedUptimeMs
        }
        resolver?.let { r ->
            val stored = CoolwalkRailStore.read(r)
            mergedFull = absorbExternalFullHu(mergedFull, mergedLayoutH, stored.fullHuWidthPx, railForMerge)
            if (stored.touchRailWidthPx > mergedTouch) mergedTouch = stored.touchRailWidthPx
            if (stored.updatedUptimeMs > mergedUpdated) {
                mergedPhase = stored.phase
                mergedUpdated = stored.updatedUptimeMs
            }
        }
        val ipc = CoreManager.tryGetCoolwalkRailSnapshot()
        if (ipc != null && ipc.size >= 4) {
            mergedFull = absorbExternalFullHu(mergedFull, mergedLayoutH, ipc[2], railForMerge)
            if (ipc[1] > mergedTouch) mergedTouch = ipc[1]
        }
        if (mergedFull != snapshot.fullHuWidthPx ||
            mergedTouch > snapshot.touchRailWidthPx ||
            mergedLayoutW > snapshot.layoutWidthPx ||
            mergedLayoutH > snapshot.layoutHeightPx ||
            mergedUpdated > snapshot.updatedUptimeMs
        ) {
            snapshot = snapshot.copy(
                fullHuWidthPx = mergedFull,
                touchRailWidthPx = mergedTouch,
                layoutWidthPx = mergedLayoutW,
                layoutHeightPx = mergedLayoutH,
                phase = mergedPhase,
                updatedUptimeMs = mergedUpdated,
            )
        }
    }

    fun bestObservedFullHuWidthPx(): Int = snapshot.fullHuWidthPx

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
        if (width > snapshot.fullHuWidthPx || height > snapshot.layoutHeightPx) {
            onEvent(RailEvent.FullHuObserved(width, height, "vd-size"))
        }
    }

    fun resetForTests() {
        snapshot = RailSnapshot()
    }

    fun dispatchActions(actions: List<RailAction>) {
        for (action in actions) {
            when (action) {
                is RailAction.NotifyServer -> CoreManager.tryReportCoolwalkRailSnapshot(action.snapshot)
                is RailAction.ReclaimAllGutters -> CoolwalkFacetChrome.reclaimAllWindowGutters(action.reason)
                is RailAction.ReclaimLeftGutter -> { /* per-root via window attach callback */ }
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
            phase = serverResolved.phase,
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

    fun serverSnapshotForSettle(): RailSnapshot = CoolwalkRailStore.effectiveSnapshot()

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
