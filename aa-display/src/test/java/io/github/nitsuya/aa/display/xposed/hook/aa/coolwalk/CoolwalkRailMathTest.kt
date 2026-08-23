package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import io.github.nitsuya.aa.display.util.DisplayProfileSettle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CoolwalkRailMathTest {

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
    fun expand_content_slot_with_touch_rail_720_plus_80() {
        val expanded = CoolwalkRailMath.computeExpandedContentBounds(
            left = 0, top = 0, right = 720, bottom = 480,
            layoutWidthPx = 720, layoutHeightPx = 480,
            observedFullHuWidthPx = 0,
            touchRailWidthPx = 80,
        )
        assertNotNull(expanded)
        assertEquals(800, expanded!!.targetWidthPx)
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
    fun settle_profile_full_bleed_when_rail_starved() {
        val settled = DisplayProfileSettle.settle(
            DisplayProfileSettle.Size(720, 480, 160),
            railWidthPx = 1,
            fullHuWidthPx = 800,
        )
        assertEquals(800, settled.width)
    }

    @Test
    fun settle_profile_1412_full_after_rail_gone() {
        val settled = DisplayProfileSettle.settle(
            DisplayProfileSettle.Size(1305, 480, 160),
            railWidthPx = 0,
            fullHuWidthPx = 1412,
        )
        assertEquals(1412, settled.width)
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
}
