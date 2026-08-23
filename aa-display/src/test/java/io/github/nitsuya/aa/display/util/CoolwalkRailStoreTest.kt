package io.github.nitsuya.aa.display.util

import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.RailPhase
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.RailSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CoolwalkRailStoreTest {

    @Before
    fun reset() {
        CoolwalkRailStore.resetForTests()
    }

    @Test
    fun remember_from_full_bleed_report() {
        CoolwalkRailStore.rememberFromReport(
            RailSnapshot(
                phase = RailPhase.FullBleed,
                touchRailWidthPx = 107,
                fullHuWidthPx = 1280,
            ),
        )
        assertEquals(SessionSettled(1280, 107), CoolwalkRailStore.sessionSettled)
    }

    @Test
    fun reconnect_report_does_not_clear_session() {
        CoolwalkRailStore.rememberFromReport(
            RailSnapshot(phase = RailPhase.FullBleed, touchRailWidthPx = 107, fullHuWidthPx = 1280),
        )
        CoolwalkRailStore.rememberFromReport(
            RailSnapshot(phase = RailPhase.ReconnectSettling, touchRailWidthPx = 0, fullHuWidthPx = 0),
        )
        assertEquals(SessionSettled(1280, 107), CoolwalkRailStore.sessionSettled)
    }

    @Test
    fun snapshot_with_session_fills_reconnect_gap() {
        CoolwalkRailStore.rememberFromReport(
            RailSnapshot(phase = RailPhase.FullBleed, touchRailWidthPx = 107, fullHuWidthPx = 1280),
        )
        CoolwalkRailStore.publishServer(
            RailSnapshot(phase = RailPhase.ReconnectSettling, touchRailWidthPx = 0, fullHuWidthPx = 0),
        )
        val merged = CoolwalkRailStore.snapshotWithSession(CoolwalkRailStore.serverSnapshot)
        assertEquals(1280, merged.fullHuWidthPx)
        assertEquals(107, merged.touchRailWidthPx)
        assertEquals(RailPhase.ReconnectSettling, merged.phase)
        assertEquals(0, merged.fullBleedStableCount)
    }

    @Test
    fun different_hu_geometry_replaces_session() {
        CoolwalkRailStore.rememberFromReport(
            RailSnapshot(phase = RailPhase.FullBleed, touchRailWidthPx = 107, fullHuWidthPx = 1280),
        )
        CoolwalkRailStore.rememberFromReport(
            RailSnapshot(phase = RailPhase.FullBleed, touchRailWidthPx = 50, fullHuWidthPx = 800),
        )
        assertEquals(SessionSettled(800, 50), CoolwalkRailStore.sessionSettled)
    }

    @Test
    fun live_snapshot_wins_when_present() {
        CoolwalkRailStore.rememberFromReport(
            RailSnapshot(phase = RailPhase.FullBleed, touchRailWidthPx = 107, fullHuWidthPx = 1280),
        )
        val live = RailSnapshot(
            phase = RailPhase.Reclaiming,
            touchRailWidthPx = 107,
            fullHuWidthPx = 1280,
            fullBleedStableCount = 2,
        )
        val merged = CoolwalkRailStore.snapshotWithSession(live)
        assertEquals(live, merged)
    }

    @Test
    fun effective_snapshot_matches_session_merge() {
        CoolwalkRailStore.rememberFromReport(
            RailSnapshot(phase = RailPhase.FullBleed, touchRailWidthPx = 107, fullHuWidthPx = 1280),
        )
        CoolwalkRailStore.publishServer(
            RailSnapshot(phase = RailPhase.ReconnectSettling, touchRailWidthPx = 0, fullHuWidthPx = 0),
        )
        val merged = CoolwalkRailStore.effectiveSnapshot()
        assertEquals(1280, merged.fullHuWidthPx)
        assertEquals(RailPhase.ReconnectSettling, merged.phase)
    }

    @Test
    fun clear_session_wipes_cache() {
        CoolwalkRailStore.rememberFromReport(
            RailSnapshot(phase = RailPhase.FullBleed, touchRailWidthPx = 107, fullHuWidthPx = 1280),
        )
        CoolwalkRailStore.clearSession()
        assertNull(CoolwalkRailStore.sessionSettled)
    }

    @Test
    fun sanitize_cross_boot_drops_stale_full_bleed_phase() {
        val stale = RailSnapshot(
            phase = RailPhase.FullBleed,
            touchRailWidthPx = 107,
            fullHuWidthPx = 1280,
            fullBleedStableCount = 3,
            updatedUptimeMs = 9_999_999L,
        )
        val fresh = CoolwalkRailStore.sanitizeCrossBoot(stale, nowUptimeMs = 60_000L)
        assertEquals(RailPhase.Bootstrapping, fresh.phase)
        assertEquals(0, fresh.fullBleedStableCount)
        assertEquals(0L, fresh.updatedUptimeMs)
        assertEquals(1280, fresh.fullHuWidthPx)
        assertEquals(107, fresh.touchRailWidthPx)
    }

    @Test
    fun sanitize_cross_boot_keeps_same_boot_snapshot() {
        val live = RailSnapshot(
            phase = RailPhase.FullBleed,
            touchRailWidthPx = 107,
            fullHuWidthPx = 1280,
            fullBleedStableCount = 3,
            updatedUptimeMs = 50_000L,
        )
        assertEquals(live, CoolwalkRailStore.sanitizeCrossBoot(live, nowUptimeMs = 60_000L))
    }
}
