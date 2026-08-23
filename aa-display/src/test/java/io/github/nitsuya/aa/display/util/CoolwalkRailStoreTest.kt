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
        assertEquals(RailPhase.Reclaiming, merged.phase)
        assertEquals(1, merged.fullBleedStableCount)
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
    fun clear_session_wipes_cache() {
        CoolwalkRailStore.rememberFromReport(
            RailSnapshot(phase = RailPhase.FullBleed, touchRailWidthPx = 107, fullHuWidthPx = 1280),
        )
        CoolwalkRailStore.clearSession()
        assertNull(CoolwalkRailStore.sessionSettled)
    }
}
