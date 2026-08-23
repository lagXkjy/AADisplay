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
                next = next.copy(
                    layoutWidthPx = event.widthPx,
                    layoutHeightPx = event.heightPx,
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
                if (event.widthPx > next.fullHuWidthPx) {
                    next = next.copy(
                        fullHuWidthPx = event.widthPx,
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
                next = next.copy(
                    effectiveRailWidthPx = 0,
                    fullHuWidthPx = event.fullWidthPx.coerceAtLeast(next.fullHuWidthPx),
                    layoutWidthPx = event.fullWidthPx.coerceAtLeast(next.layoutWidthPx),
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
                next = next.copy(
                    phase = RailPhase.ReconnectSettling,
                    fullBleedStableCount = 0,
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
        mergedFull = maxOf(mergedFull, server.fullHuWidthPx)
        if (server.touchRailWidthPx > mergedTouch) mergedTouch = server.touchRailWidthPx
        if (server.layoutWidthPx > mergedLayoutW) mergedLayoutW = server.layoutWidthPx
        if (server.layoutHeightPx > mergedLayoutH) mergedLayoutH = server.layoutHeightPx
        if (server.updatedUptimeMs > mergedUpdated) {
            mergedPhase = server.phase
            mergedUpdated = server.updatedUptimeMs
        }
        resolver?.let { r ->
            val stored = CoolwalkRailStore.read(r)
            mergedFull = maxOf(mergedFull, stored.fullHuWidthPx)
            if (stored.touchRailWidthPx > mergedTouch) mergedTouch = stored.touchRailWidthPx
            if (stored.updatedUptimeMs > mergedUpdated) {
                mergedPhase = stored.phase
                mergedUpdated = stored.updatedUptimeMs
            }
        }
        val ipc = CoreManager.tryGetCoolwalkRailSnapshot()
        if (ipc != null && ipc.size >= 4) {
            mergedFull = maxOf(mergedFull, ipc[2])
            if (ipc[1] > mergedTouch) mergedTouch = ipc[1]
        }
        if (mergedFull > snapshot.fullHuWidthPx ||
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
        if (width < 640 || height < 320 || width <= height) return
        if (width > snapshot.fullHuWidthPx || height > snapshot.layoutHeightPx) {
            onEvent(RailEvent.FullHuObserved(width, height, "vd-size"))
        }
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
        if (server.updatedUptimeMs <= snapshot.updatedUptimeMs &&
            server.fullHuWidthPx <= snapshot.fullHuWidthPx
        ) {
            return
        }
        snapshot = snapshot.copy(
            phase = server.phase,
            touchRailWidthPx = server.touchRailWidthPx.takeIf { it > 0 } ?: snapshot.touchRailWidthPx,
            fullHuWidthPx = server.fullHuWidthPx.takeIf { it > 0 } ?: snapshot.fullHuWidthPx,
            layoutWidthPx = server.layoutWidthPx.takeIf { it > 0 } ?: snapshot.layoutWidthPx,
            layoutHeightPx = server.layoutHeightPx.takeIf { it > 0 } ?: snapshot.layoutHeightPx,
            facetDisplayId = server.facetDisplayId.takeIf { it != Display.INVALID_DISPLAY }
                ?: snapshot.facetDisplayId,
            updatedUptimeMs = server.updatedUptimeMs.coerceAtLeast(snapshot.updatedUptimeMs),
        )
    }

    fun serverSnapshotForSettle(): RailSnapshot = CoolwalkRailStore.serverSnapshot
}
