package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CoolwalkCompositorPolicyTest {

    @Test
    fun skip_aa_display_presentation_vd_expansion() {
        CoolwalkRailCoordinator.onEvent(
            RailEvent.FullHuObserved(1280, 720, "test"),
        )
        val result = CoolwalkCompositorPolicy.rewriteVirtualDisplayArgs(
            name = "io.github.nitsuya.aa.display/ui.aa.AaDisplayActivity",
            width = 1173,
            height = 720,
            layoutWidthPx = 1280,
            layoutHeightPx = 540,
            observedRailWidthPx = 107,
        )
        assertNull(result.rewrite)
    }

    @Test
    fun expand_car_app_vd_when_layout_height_shorter_than_vd_height() {
        CoolwalkRailCoordinator.onEvent(
            RailEvent.FullHuObserved(1280, 720, "test"),
        )
        val result = CoolwalkCompositorPolicy.rewriteVirtualDisplayArgs(
            name = "com.example.app/TemplateCarFragment",
            width = 1173,
            height = 720,
            layoutWidthPx = 1280,
            layoutHeightPx = 540,
            observedRailWidthPx = 107,
        )
        assertNotNull(result.rewrite)
        assertEquals(1280, result.rewrite!!.width)
        assertEquals(720, result.rewrite!!.height)
    }
}
