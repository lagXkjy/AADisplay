package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import io.github.nitsuya.aa.display.util.CoolwalkRailStore
import io.github.nitsuya.aa.display.util.DisplayProfileSettle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CoolwalkRailMathTest {

    @Before
    fun resetCoordinator() {
        CoolwalkRailCoordinator.resetForTests()
    }

    @Test
    fun form_a_lhd_inset_uses_right_edge_not_right_plus_left() {
        val expanded = CoolwalkRailMath.computeExpandedContentBounds(
            left = 80, top = 0, right = 800, bottom = 480,
            layoutWidthPx = 800, layoutHeightPx = 480, observedFullHuWidthPx = 0,
        )
        assertNotNull(expanded)
        assertEquals(800, expanded!!.targetWidthPx)
        assertEquals(80, expanded.railWidthPx)
    }

    @Test
    fun form_b_content_right_when_layout_is_independently_full() {
        val expanded = CoolwalkRailMath.computeExpandedContentBounds(
            left = 107, top = 0, right = 1173, bottom = 720,
            layoutWidthPx = 1280, layoutHeightPx = 720, observedFullHuWidthPx = 1280,
        )
        assertNotNull(expanded)
        assertEquals(1280, expanded!!.targetWidthPx)
        assertEquals(107, expanded.railWidthPx)
    }

    @Test
    fun expand_lhd_rail_inset_800x480() {
        val expanded = CoolwalkRailMath.computeExpandedContentBounds(
            left = 80, top = 0, right = 800, bottom = 480,
            layoutWidthPx = 800, layoutHeightPx = 480, observedFullHuWidthPx = 0,
        )
        assertNotNull(expanded)
        assertEquals(800, expanded!!.targetWidthPx)
        assertEquals(80, expanded.railWidthPx)
    }

    @Test
    fun expand_content_slot_720_when_layout_still_full_800() {
        val expanded = CoolwalkRailMath.computeExpandedContentBounds(
            left = 0, top = 0, right = 720, bottom = 480,
            layoutWidthPx = 800, layoutHeightPx = 480,
            observedFullHuWidthPx = 800,
            touchRailWidthPx = 80,
        )
        assertNotNull(expanded)
        assertEquals(800, expanded!!.targetWidthPx)
    }

    @Test
    fun expand_content_slot_with_touch_rail_when_layout_unknown() {
        val expanded = CoolwalkRailMath.computeExpandedContentBounds(
            left = 0, top = 0, right = 720, bottom = 480,
            layoutWidthPx = 0, layoutHeightPx = 480,
            observedFullHuWidthPx = 0,
            touchRailWidthPx = 80,
        )
        assertNotNull(expanded)
        assertEquals(800, expanded!!.targetWidthPx)
    }

    @Test
    fun do_not_add_rail_to_already_full_bleed_form_c() {
        assertNull(
            CoolwalkRailMath.computeExpandedContentBounds(
                left = 0, top = 0, right = 800, bottom = 480,
                layoutWidthPx = 800, layoutHeightPx = 480,
                observedFullHuWidthPx = 0,
                touchRailWidthPx = 80,
            ),
        )
    }

    @Test
    fun expand_content_slot_1173_with_observed_full_1280() {
        val expanded = CoolwalkRailMath.computeExpandedContentBounds(
            left = 0, top = 0, right = 1173, bottom = 720,
            layoutWidthPx = 0,
            layoutHeightPx = 720,
            observedFullHuWidthPx = 1280,
            touchRailWidthPx = 0,
        )
        assertNotNull(expanded)
        assertEquals(1280, expanded!!.targetWidthPx)
        assertEquals(107, expanded.railWidthPx)
    }

    @Test
    fun expand_reconnect_content_slot_1305_vs_full_1412() {
        val expanded = CoolwalkRailMath.computeExpandedContentBounds(
            left = 0, top = 0, right = 1305, bottom = 480,
            layoutWidthPx = 1305, layoutHeightPx = 480,
            observedFullHuWidthPx = 1412,
            touchRailWidthPx = 107,
        )
        assertNotNull(expanded)
        assertEquals(1412, expanded!!.targetWidthPx)
        assertEquals(107, expanded.railWidthPx)
    }

    @Test
    fun expand_audi_stale_1280_slot_with_observed_1412() {
        val expanded = CoolwalkRailMath.computeExpandedContentBounds(
            left = 107, top = 0, right = 1280, bottom = 720,
            layoutWidthPx = 1280, layoutHeightPx = 720,
            observedFullHuWidthPx = 1412,
        )
        assertNotNull(expanded)
        assertEquals(1412, expanded!!.targetWidthPx)
        assertEquals(107, expanded.railWidthPx)
    }

    @Test
    fun pick_connection_rejects_stale_shrunk_width_over_session() {
        assertEquals(
            800,
            CoolwalkRailMath.pickConnectionFullHuWidth(
                sessionFull = 800, sessionHeight = 480,
                measuredW = 600, measuredH = 480, railWidthPx = 107,
            ),
        )
    }

    @Test
    fun pick_connection_rejects_stale_cross_geometry_measurement() {
        assertEquals(
            1412,
            CoolwalkRailMath.pickConnectionFullHuWidth(
                sessionFull = 1412, sessionHeight = 654,
                measuredW = 1280, measuredH = 720, railWidthPx = 107,
            ),
        )
    }

    @Test
    fun resolve_layout_canvas_widens_stale_shrink_not_rail_gap() {
        val snap = RailSnapshot(
            phase = RailPhase.ReconnectSettling,
            fullHuWidthPx = 800,
            layoutWidthPx = 600,
            layoutHeightPx = 480,
            touchRailWidthPx = 107,
        )
        assertEquals(800, CoolwalkRailMath.resolveLayoutCanvasTargetPx(snap, 600, 480))
    }

    @Test
    fun resolve_layout_canvas_widens_stale_shrink_even_with_live_rail() {
        val snap = RailSnapshot(
            phase = RailPhase.ReconnectSettling,
            fullHuWidthPx = 1280,
            layoutWidthPx = 961,
            layoutHeightPx = 720,
            touchRailWidthPx = 107,
            effectiveRailWidthPx = 107,
        )
        assertEquals(1280, CoolwalkRailMath.resolveLayoutCanvasTargetPx(snap, 961, 720))
    }

    @Test
    fun resolve_layout_canvas_keeps_width_when_hu_geometry_changed() {
        val snap = RailSnapshot(
            phase = RailPhase.ReconnectSettling,
            fullHuWidthPx = 1280,
            layoutHeightPx = 720,
            touchRailWidthPx = 107,
        )
        assertEquals(800, CoolwalkRailMath.resolveLayoutCanvasTargetPx(snap, 800, 480))
    }

    @Test
    fun pick_connection_keeps_measured_when_session_width_is_different_hu() {
        assertEquals(
            800,
            CoolwalkRailMath.pickConnectionFullHuWidth(
                sessionFull = 1280, sessionHeight = 480,
                measuredW = 800, measuredH = 480, railWidthPx = 107,
            ),
        )
    }

    @Test
    fun reconnect_starts_after_long_gap_even_when_reclaiming_stable() {
        repeat(3) {
            CoolwalkRailCoordinator.onEvent(
                RailEvent.ContentBoundsExpanded(800, 480, 107, "stable"),
            )
        }
        assertEquals(RailPhase.FullBleed, CoolwalkRailCoordinator.current().phase)
        val t = maxOf(android.os.SystemClock.uptimeMillis(), 10_000L)
        CoolwalkRailCoordinator.setLastProjectionConfigUptimeForTests(t - 10_000L)
        CoolwalkRailCoordinator.resetReconnectReclaimDebounceForTests()
        val signal = CoolwalkRailCoordinator.onProjectionConfigSignal("long-gap", syncExternal = false)
        assertEquals(true, signal.reconnectStarted)
        assertEquals(RailPhase.ReconnectSettling, CoolwalkRailCoordinator.current().phase)
    }

    @Test
    fun expand_unchanged_when_already_full_bleed() {
        assertNull(
            CoolwalkRailMath.computeExpandedContentBounds(
                left = 0, top = 0, right = 800, bottom = 480,
                layoutWidthPx = 800, layoutHeightPx = 480, observedFullHuWidthPx = 800,
            ),
        )
    }

    @Test
    fun railHitWidth_prefers_observed_touch_band() {
        val snap = RailSnapshot(touchRailWidthPx = 80, layoutWidthPx = 800, layoutHeightPx = 480)
        assertEquals(80, CoolwalkRailMath.railHitWidthPx(snap))
    }

    @Test
    fun railHitWidth_fallback_when_no_layout() {
        val snap = RailSnapshot(touchRailWidthPx = 107)
        assertEquals(107, CoolwalkRailMath.railHitWidthPx(snap))
    }

    @Test
    fun settle_profile_800_minus_80_rail() {
        val settled = DisplayProfileSettle.settle(
            DisplayProfileSettle.Size(720, 480, 160),
            railWidthPx = 80,
            fullHuWidthPx = 800,
        )
        assertEquals(720, settled.width)
    }

    @Test
    fun settle_keeps_content_slot_when_presentation_still_trimmed() {
        val settled = DisplayProfileSettle.settle(
            DisplayProfileSettle.Size(720, 480, 160),
            railWidthPx = 1,
            fullHuWidthPx = 800,
        )
        assertEquals(720, settled.width)
    }

    @Test
    fun settle_full_bleed_when_reported_already_full() {
        val settled = DisplayProfileSettle.settle(
            DisplayProfileSettle.Size(800, 480, 160),
            railWidthPx = 0,
            fullHuWidthPx = 800,
        )
        assertEquals(800, settled.width)
    }

    @Test
    fun settle_keeps_1173_when_full_1280_and_rail_starved() {
        val settled = DisplayProfileSettle.settle(
            DisplayProfileSettle.Size(1173, 720, 213),
            railWidthPx = 0,
            fullHuWidthPx = 1280,
        )
        assertEquals(1173, settled.width)
    }

    @Test
    fun settle_keeps_1305_when_full_1412_and_rail_gone() {
        val settled = DisplayProfileSettle.settle(
            DisplayProfileSettle.Size(1305, 480, 160),
            railWidthPx = 0,
            fullHuWidthPx = 1412,
        )
        assertEquals(1305, settled.width)
    }

    @Test
    fun compositor_facet_bar_create_at_1px() {
        val result = CoolwalkCompositorPolicy.rewriteVirtualDisplayArgs(
            name = "GhFacetBar",
            width = 80,
            height = 480,
            layoutWidthPx = 800,
            layoutHeightPx = 480,
            observedRailWidthPx = 0,
        )
        assertNotNull(result.rewrite)
        assertEquals(1, result.rewrite!!.width)
        assertEquals(480, result.rewrite.height)
        assertEquals(80, CoolwalkRailCoordinator.current().touchRailWidthPx)
    }

    @Test
    fun merge_full_hu_collapses_plus_rail_inflation() {
        assertEquals(800, CoolwalkRailMath.mergeFullHuWidth(800, 880, 80))
        assertEquals(800, CoolwalkRailMath.mergeFullHuWidth(1040, 800, 80))
        assertEquals(1280, CoolwalkRailMath.mergeFullHuWidth(800, 1280, 80))
    }

    @Test
    fun pick_connection_replaces_previous_car_resolution() {
        assertEquals(
            800,
            CoolwalkRailMath.pickConnectionFullHuWidth(
                sessionFull = 1280, sessionHeight = 720,
                measuredW = 800, measuredH = 480, railWidthPx = 107,
            ),
        )
        assertEquals(
            1280,
            CoolwalkRailMath.pickConnectionFullHuWidth(
                sessionFull = 800, sessionHeight = 480,
                measuredW = 1280, measuredH = 720, railWidthPx = 80,
            ),
        )
    }

    @Test
    fun pick_connection_keeps_full_hu_over_content_slot() {
        assertEquals(
            800,
            CoolwalkRailMath.pickConnectionFullHuWidth(
                sessionFull = 800, sessionHeight = 480,
                measuredW = 720, measuredH = 480, railWidthPx = 80,
            ),
        )
        assertEquals(
            1280,
            CoolwalkRailMath.pickConnectionFullHuWidth(
                sessionFull = 1280, sessionHeight = 720,
                measuredW = 1173, measuredH = 720, railWidthPx = 107,
            ),
        )
    }

    @Test
    fun layout_info_replaces_previous_connection_hu() {
        CoolwalkRailCoordinator.onEvent(RailEvent.FullHuObserved(1280, 720, "prev-car"))
        CoolwalkRailCoordinator.onEvent(RailEvent.LayoutInfo(800, 480, "layoutInfo"))
        assertEquals(800, CoolwalkRailCoordinator.current().fullHuWidthPx)
        assertEquals(480, CoolwalkRailCoordinator.current().layoutHeightPx)
    }

    @Test
    fun target_presentation_width_after_content_bounds_reclaim() {
        val snap = RailSnapshot(
            phase = RailPhase.Reclaiming,
            fullHuWidthPx = 1280,
            touchRailWidthPx = 107,
            fullBleedStableCount = 1,
        )
        assertEquals(1280, CoolwalkRailMath.targetPresentationWidthPx(snap, 1173))
    }

    @Test
    fun target_presentation_width_keeps_slot_before_reclaim() {
        val snap = RailSnapshot(
            phase = RailPhase.RailPresent,
            fullHuWidthPx = 1280,
            touchRailWidthPx = 107,
        )
        assertEquals(1173, CoolwalkRailMath.targetPresentationWidthPx(snap, 1173))
    }

    @Test
    fun resolve_layout_canvas_widens_after_content_bounds_on_reconnect() {
        val snap = RailSnapshot(
            phase = RailPhase.ReconnectSettling,
            fullHuWidthPx = 800,
            layoutWidthPx = 693,
            layoutHeightPx = 480,
            touchRailWidthPx = 107,
            fullBleedStableCount = 1,
        )
        assertEquals(800, CoolwalkRailMath.resolveLayoutCanvasTargetPx(snap, 693, 480))
    }

    @Test
    fun target_presentation_width_reconnect_with_session_full_hu() {
        val snap = RailSnapshot(
            phase = RailPhase.ReconnectSettling,
            fullHuWidthPx = 1280,
            touchRailWidthPx = 107,
        )
        assertEquals(1173, CoolwalkRailMath.targetPresentationWidthPx(snap, 1173))
    }

    @Test
    fun layout_widen_waits_until_this_connection_content_bounds() {
        repeat(3) {
            CoolwalkRailCoordinator.onEvent(
                RailEvent.ContentBoundsExpanded(1280, 720, 107, "prev-session"),
            )
        }
        assertEquals(1280, CoolwalkRailMath.targetPresentationWidthPx(CoolwalkRailCoordinator.current(), 1173))
        CoolwalkRailCoordinator.onEvent(RailEvent.ReconnectStarted("layoutInfo:test"))
        assertEquals(1173, CoolwalkRailMath.targetPresentationWidthPx(CoolwalkRailCoordinator.current(), 1173))
        CoolwalkRailCoordinator.onEvent(
            RailEvent.ContentBoundsExpanded(1280, 720, 107, "this-session"),
        )
        assertEquals(1280, CoolwalkRailMath.targetPresentationWidthPx(CoolwalkRailCoordinator.current(), 1173))
    }

    @Test
    fun target_presentation_width_ignores_cross_boot_stale_full_bleed() {
        val stale = CoolwalkRailStore.sanitizeCrossBoot(
            RailSnapshot(
                phase = RailPhase.FullBleed,
                fullHuWidthPx = 1280,
                touchRailWidthPx = 107,
                fullBleedStableCount = 3,
                updatedUptimeMs = 9_999_999L,
            ),
            nowUptimeMs = 60_000L,
        )
        assertEquals(1173, CoolwalkRailMath.targetPresentationWidthPx(stale, 1173))
    }

    @Test
    fun drawing_spec_widen_after_content_bounds_reclaim() {
        val reclaiming = RailSnapshot(
            phase = RailPhase.Reclaiming,
            fullHuWidthPx = 1280,
            touchRailWidthPx = 107,
        )
        assertEquals(1280, CoolwalkDrawingSpecWiden.resolveTargetWidth(1173, reclaiming))
        val settling = RailSnapshot(
            phase = RailPhase.ReconnectSettling,
            fullHuWidthPx = 1280,
            touchRailWidthPx = 107,
        )
        assertEquals(1173, CoolwalkDrawingSpecWiden.resolveTargetWidth(1173, settling))
    }

    @Test
    fun reconnect_clears_previous_connection_hu() {
        CoolwalkRailCoordinator.onEvent(RailEvent.FullHuObserved(1280, 720, "prev-car"))
        CoolwalkRailCoordinator.onEvent(RailEvent.ReconnectStarted("projection:test"))
        assertEquals(0, CoolwalkRailCoordinator.current().fullHuWidthPx)
        assertEquals(0, CoolwalkRailCoordinator.current().layoutHeightPx)
    }

    @Test
    fun projection_signal_reclaims_when_rail_present_without_reconnect_started() {
        CoolwalkRailCoordinator.onEvent(RailEvent.RailWidthObserved(80, "pillar_width"))
        val now = maxOf(android.os.SystemClock.uptimeMillis(), 10_000L)
        CoolwalkRailCoordinator.setLastProjectionConfigUptimeForTests(now - 500L)
        val signal = CoolwalkRailCoordinator.onProjectionConfigSignal("projection:test", syncExternal = false)
        assertEquals(false, signal.reconnectStarted)
        assertEquals(true, signal.shouldReclaim)
        assertEquals(RailPhase.RailPresent, CoolwalkRailCoordinator.current().phase)
        val rapid = CoolwalkRailCoordinator.onProjectionConfigSignal("projection:test", syncExternal = false)
        assertEquals(false, rapid.reconnectStarted)
        assertEquals(false, rapid.shouldReclaim)
    }

    @Test
    fun projection_signal_starts_reconnect_after_session_gap() {
        CoolwalkRailCoordinator.onEvent(RailEvent.RailWidthObserved(80, "pillar_width"))
        val t = maxOf(android.os.SystemClock.uptimeMillis(), 10_000L)
        CoolwalkRailCoordinator.setLastProjectionConfigUptimeForTests(t - 500L)
        CoolwalkRailCoordinator.onProjectionConfigSignal("projection:first", syncExternal = false)
        CoolwalkRailCoordinator.setLastProjectionConfigUptimeForTests(
            android.os.SystemClock.uptimeMillis() - 9_000L,
        )
        CoolwalkRailCoordinator.resetReconnectReclaimDebounceForTests()
        val afterGap = CoolwalkRailCoordinator.onProjectionConfigSignal("projection:gap", syncExternal = false)
        assertEquals(true, afterGap.reconnectStarted)
        assertEquals(true, afterGap.shouldReclaim)
        assertEquals(RailPhase.ReconnectSettling, CoolwalkRailCoordinator.current().phase)
    }
}
