package io.github.nitsuya.aa.display.util

import android.content.ContentResolver
import android.os.SystemClock
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
    /** Survives soft reconnect; gearhead reads via [readPersistedSession]. */
    const val SETTINGS_SESSION_FULL_HU_W = "aadisplay_cw_session_full_w"
    const val SETTINGS_SESSION_TOUCH_W = "aadisplay_cw_session_touch_w"
    /** Shared across :projection / :car for idempotent content_bounds skip. */
    const val SETTINGS_RECONNECT_UPTIME_MS = "aadisplay_cw_reconnect_uptime_ms"

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
     * [updatedUptimeMs] is [SystemClock.uptimeMillis] and resets on reboot while
     * Settings.Global survives — a larger stored value is always from a prior boot.
     * Keep HU geometry hints but drop reclaim phase so LayoutInfo is not widened
     * before this session's FacetBar starve / content_bounds run.
     */
    fun isCrossBootStale(updatedUptimeMs: Long, nowUptimeMs: Long = SystemClock.uptimeMillis()): Boolean {
        return updatedUptimeMs > nowUptimeMs
    }

    fun sanitizeCrossBoot(
        snapshot: RailSnapshot,
        nowUptimeMs: Long = SystemClock.uptimeMillis(),
    ): RailSnapshot {
        if (!isCrossBootStale(snapshot.updatedUptimeMs, nowUptimeMs)) return snapshot
        return snapshot.copy(
            phase = RailPhase.Bootstrapping,
            fullBleedStableCount = 0,
            updatedUptimeMs = 0L,
        )
    }

    /**
     * Remember cold-start / full-bleed truth once gearhead reports it.
     * Soft reconnect clears [serverSnapshot] live fields but keeps this for presentation create.
     */
    fun rememberFromReport(snapshot: RailSnapshot, cr: ContentResolver? = null) {
        if (snapshot.fullHuWidthPx <= 0) return
        if (snapshot.phase == RailPhase.ReconnectSettling) return
        val existing = sessionSettled
        val full = snapshot.fullHuWidthPx
        val touch = snapshot.touchRailWidthPx
        val railForCompare = touch.coerceAtLeast(existing?.touchRailWidthPx ?: 0)
        val next = if (existing != null &&
            !CoolwalkRailMath.isSameHuGeometry(
                existing.fullHuWidthPx,
                0,
                full,
                0,
                railForCompare,
            )
        ) {
            SessionSettled(full, touch)
        } else {
            SessionSettled(
                fullHuWidthPx = maxOf(full, existing?.fullHuWidthPx ?: 0),
                touchRailWidthPx = maxOf(touch, existing?.touchRailWidthPx ?: 0),
            )
        }
        sessionSettled = next
        persistSession(cr, next)
    }

    fun resolvedSession(cr: ContentResolver? = null): SessionSettled? =
        sessionSettled ?: readPersistedSession(cr)

    fun markReconnectEpoch(cr: ContentResolver?) {
        if (cr == null) return
        try {
            Settings.Global.putLong(cr, SETTINGS_RECONNECT_UPTIME_MS, SystemClock.uptimeMillis())
        } catch (_: Throwable) {
        }
    }

    fun readReconnectEpochMs(cr: ContentResolver?): Long {
        if (cr == null) return 0L
        return try {
            Settings.Global.getLong(cr, SETTINGS_RECONNECT_UPTIME_MS, 0L)
        } catch (_: Throwable) {
            0L
        }
    }

    fun isRecentReconnectEpoch(cr: ContentResolver?, windowMs: Long = 30_000L): Boolean {
        val t = readReconnectEpochMs(cr)
        if (t <= 0L) return false
        return SystemClock.uptimeMillis() - t < windowMs
    }

    private fun persistSession(cr: ContentResolver?, session: SessionSettled) {
        if (cr == null) return
        try {
            Settings.Global.putInt(cr, SETTINGS_SESSION_FULL_HU_W, session.fullHuWidthPx)
            Settings.Global.putInt(cr, SETTINGS_SESSION_TOUCH_W, session.touchRailWidthPx)
        } catch (_: Throwable) {
        }
    }

    fun readPersistedSession(cr: ContentResolver?): SessionSettled? {
        if (cr == null) return null
        return try {
            val full = Settings.Global.getInt(cr, SETTINGS_SESSION_FULL_HU_W, 0)
            if (full <= 0) return null
            val touch = Settings.Global.getInt(cr, SETTINGS_SESSION_TOUCH_W, 0)
            SessionSettled(full, touch)
        } catch (_: Throwable) {
            null
        }
    }

    /** Live server snapshot merged with [sessionSettled] when reconnect cleared live HU fields. */
    fun effectiveSnapshot(cr: ContentResolver? = null): RailSnapshot = snapshotWithSession(serverSnapshot, cr)

    /**
     * Merge session cache when live snapshot was cleared for reconnect.
     * During [RailPhase.ReconnectSettling] with no live full HU and no stable
     * content_bounds on this connection, do not inject session — widen / profile must
     * wait for this session's reclaim (see layout_widen_waits / §9.5).
     */
    fun snapshotWithSession(live: RailSnapshot, cr: ContentResolver? = null): RailSnapshot {
        val session = resolvedSession(cr) ?: return live
        if (live.fullHuWidthPx <= 0 || live.phase == RailPhase.ReconnectSettling) {
            if (live.fullHuWidthPx <= 0 &&
                live.phase == RailPhase.ReconnectSettling &&
                live.fullBleedStableCount == 0
            ) {
                val touchOnly = maxOf(live.touchRailWidthPx, session.touchRailWidthPx)
                return if (touchOnly == live.touchRailWidthPx) {
                    live
                } else {
                    live.copy(touchRailWidthPx = touchOnly)
                }
            }
            val full = maxOf(live.fullHuWidthPx, session.fullHuWidthPx)
            val touch = maxOf(live.touchRailWidthPx, session.touchRailWidthPx)
            return if (full == live.fullHuWidthPx && touch == live.touchRailWidthPx) {
                live
            } else {
                live.copy(fullHuWidthPx = full, touchRailWidthPx = touch)
            }
        }
        val touch = maxOf(live.touchRailWidthPx, session.touchRailWidthPx)
        val h = live.layoutHeightPx
        val pickedFull = CoolwalkRailMath.pickConnectionFullHuWidth(
            session.fullHuWidthPx,
            h,
            live.fullHuWidthPx,
            h,
            touch,
        )
        val shouldPreferSession = live.phase in TRANSIENT_RECONNECT_PHASES &&
            pickedFull > live.fullHuWidthPx
        if (!shouldPreferSession) return live
        val merged = live.copy(
            fullHuWidthPx = session.fullHuWidthPx,
            touchRailWidthPx = maxOf(live.touchRailWidthPx, session.touchRailWidthPx),
        )
        return merged
    }

    private val TRANSIENT_RECONNECT_PHASES = setOf(
        RailPhase.Bootstrapping,
        RailPhase.ReconnectSettling,
        RailPhase.Reclaiming,
    )

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
            val sessionFull = resolvedSession(cr)?.fullHuWidthPx ?: 0
            val fullToWrite = maxOf(snapshot.fullHuWidthPx, sessionFull)
            Settings.Global.putInt(cr, SETTINGS_PHASE, snapshot.phase.code)
            Settings.Global.putInt(cr, SETTINGS_TOUCH_RAIL_W, snapshot.touchRailWidthPx)
            Settings.Global.putInt(cr, SETTINGS_FULL_HU_W, fullToWrite)
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
        return sanitizeCrossBoot(
            RailSnapshot(
                phase = RailPhase.fromCode(
                    Settings.Global.getInt(cr, SETTINGS_PHASE, RailPhase.Bootstrapping.code),
                ),
                effectiveRailWidthPx = 0,
                touchRailWidthPx = Settings.Global.getInt(cr, SETTINGS_TOUCH_RAIL_W, 0),
                fullHuWidthPx = Settings.Global.getInt(cr, SETTINGS_FULL_HU_W, 0),
                facetDisplayId = Settings.Global.getInt(
                    cr,
                    SETTINGS_FACET_DISPLAY_ID,
                    Display.INVALID_DISPLAY,
                ),
                updatedUptimeMs = Settings.Global.getLong(cr, SETTINGS_UPDATED_MS, 0L),
            ),
        )
    }
}
