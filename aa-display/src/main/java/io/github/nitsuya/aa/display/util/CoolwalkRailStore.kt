package io.github.nitsuya.aa.display.util

import android.content.ContentResolver
import android.provider.Settings
import android.view.Display
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.RailPhase
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.RailSnapshot

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

    fun publishServer(snapshot: RailSnapshot) {
        serverSnapshot = snapshot
    }

    fun clear(cr: ContentResolver? = null) {
        serverSnapshot = RailSnapshot()
        if (cr != null) write(cr, serverSnapshot)
    }

    fun write(cr: ContentResolver, snapshot: RailSnapshot) {
        Settings.Global.putInt(cr, SETTINGS_PHASE, snapshot.phase.code)
        Settings.Global.putInt(cr, SETTINGS_TOUCH_RAIL_W, snapshot.touchRailWidthPx)
        Settings.Global.putInt(cr, SETTINGS_FULL_HU_W, snapshot.fullHuWidthPx)
        Settings.Global.putInt(cr, SETTINGS_FACET_DISPLAY_ID, snapshot.facetDisplayId)
        Settings.Global.putLong(cr, SETTINGS_UPDATED_MS, snapshot.updatedUptimeMs)
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
