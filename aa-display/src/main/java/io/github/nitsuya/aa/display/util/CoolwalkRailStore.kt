package io.github.nitsuya.aa.display.util

import android.content.ContentResolver
import android.provider.Settings
import android.view.Display
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.CoolwalkRailMath
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.RailPhase
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.RailSnapshot

/** Cold-start settled HU geometry kept in system_server across soft reconnect. */
data class SessionSettled(
    val fullHuWidthPx: Int,
    val touchRailWidthPx: Int,
)

/**
 * Settings.Global mirror of Coolwalk rail state. system_server is authoritative;
 * gearhead :projection / :car report via [ICoreManager.reportCoolwalkRailSnapshot].
 */
object CoolwalkRailStore {
    const val SETTINGS_PHASE = "aadisplay_cw_rail_phase"
    const val SETTINGS_TOUCH_RAIL_W = "aadisplay_cw_rail_touch_w"
    const val SETTINGS_FULL_HU_W = "aadisplay_cw_rail_full_w"
    const val SETTINGS_FACET_DISPLAY_ID = "aadisplay_cw_rail_facet_id"
    const val SETTINGS_UPDATED_MS = "aadisplay_cw_rail_updated_ms"

    @Volatile
    var serverSnapshot: RailSnapshot = RailSnapshot()
        private set

    /** Last successful full-bleed geometry for this HU; survives [RailPhase.ReconnectSettling]. */
    @Volatile
    var sessionSettled: SessionSettled? = null
        private set

    fun publishServer(snapshot: RailSnapshot) {
        serverSnapshot = snapshot
    }

    /**
     * Remember cold-start / full-bleed truth once gearhead reports it.
     * Soft reconnect clears [serverSnapshot] live fields but keeps this for presentation create.
     */
    fun rememberFromReport(snapshot: RailSnapshot) {
        if (snapshot.fullHuWidthPx <= 0) return
        if (snapshot.phase == RailPhase.ReconnectSettling) return
        val existing = sessionSettled
        val full = snapshot.fullHuWidthPx
        val touch = snapshot.touchRailWidthPx
        val railForCompare = touch.coerceAtLeast(existing?.touchRailWidthPx ?: 0)
        if (existing != null &&
            !CoolwalkRailMath.isSameHuGeometry(
                existing.fullHuWidthPx,
                0,
                full,
                0,
                railForCompare,
            )
        ) {
            sessionSettled = SessionSettled(full, touch)
            return
        }
        sessionSettled = SessionSettled(
            fullHuWidthPx = maxOf(full, existing?.fullHuWidthPx ?: 0),
            touchRailWidthPx = maxOf(touch, existing?.touchRailWidthPx ?: 0),
        )
    }

    /** Live server snapshot merged with [sessionSettled] when reconnect cleared live HU fields. */
    fun effectiveSnapshot(): RailSnapshot = snapshotWithSession(serverSnapshot)

    /** Merge session cache when live snapshot was cleared for reconnect. */
    fun snapshotWithSession(live: RailSnapshot): RailSnapshot {
        val session = sessionSettled ?: return live
        if (live.fullHuWidthPx > 0 && live.phase != RailPhase.ReconnectSettling) return live
        val full = maxOf(live.fullHuWidthPx, session.fullHuWidthPx)
        val touch = maxOf(live.touchRailWidthPx, session.touchRailWidthPx)
        return live.copy(
            fullHuWidthPx = full,
            touchRailWidthPx = touch,
            fullBleedStableCount = live.fullBleedStableCount.coerceAtLeast(1),
            phase = when (live.phase) {
                RailPhase.ReconnectSettling, RailPhase.Bootstrapping -> RailPhase.Reclaiming
                else -> live.phase
            },
        )
    }

    fun clearSession() {
        sessionSettled = null
    }

    fun resetForTests() {
        serverSnapshot = RailSnapshot()
        sessionSettled = null
    }

    fun clear(cr: ContentResolver? = null) {
        serverSnapshot = RailSnapshot()
        sessionSettled = null
        if (cr != null) write(cr, serverSnapshot)
    }

    fun write(cr: ContentResolver, snapshot: RailSnapshot) {
        try {
            Settings.Global.putInt(cr, SETTINGS_PHASE, snapshot.phase.code)
            Settings.Global.putInt(cr, SETTINGS_TOUCH_RAIL_W, snapshot.touchRailWidthPx)
            Settings.Global.putInt(cr, SETTINGS_FULL_HU_W, snapshot.fullHuWidthPx)
            Settings.Global.putInt(cr, SETTINGS_FACET_DISPLAY_ID, snapshot.facetDisplayId)
            Settings.Global.putLong(cr, SETTINGS_UPDATED_MS, snapshot.updatedUptimeMs)
        } catch (_: Throwable) {
            // OEM SettingsProvider may reject unknown keys; serverSnapshot + IPC remain authoritative.
        }
    }

    fun snapshotFromWire(wire: IntArray): RailSnapshot? {
        if (wire.size < 4) return null
        return RailSnapshot(
            phase = RailPhase.fromCode(wire[0]),
            touchRailWidthPx = wire[1],
            fullHuWidthPx = wire[2],
            facetDisplayId = wire[3],
        )
    }

    fun read(cr: ContentResolver): RailSnapshot {
        return RailSnapshot(
            phase = RailPhase.fromCode(Settings.Global.getInt(cr, SETTINGS_PHASE, RailPhase.Bootstrapping.code)),
            effectiveRailWidthPx = 0,
            touchRailWidthPx = Settings.Global.getInt(cr, SETTINGS_TOUCH_RAIL_W, 0),
            fullHuWidthPx = Settings.Global.getInt(cr, SETTINGS_FULL_HU_W, 0),
            facetDisplayId = Settings.Global.getInt(cr, SETTINGS_FACET_DISPLAY_ID, Display.INVALID_DISPLAY),
            updatedUptimeMs = Settings.Global.getLong(cr, SETTINGS_UPDATED_MS, 0L),
        )
    }
}
