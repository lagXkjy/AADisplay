package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Method

class CoolwalkFacetBarSurfaceHookTest {

    @Test
    fun clampSurfaceArgs_rewritesRailWidthToOne() {
        val env = CoolwalkHookEnv()
        env.mLayoutWidthDp = 800
        env.mLayoutHeightDp = 480
        CoolwalkRailCoordinator.onEvent(RailEvent.LayoutInfo(800, 480, "test"))
        val args = arrayOf<Any?>("GhFacetBar", 107, 480)
        val method = String::class.java.getMethod("length")
        CoolwalkFacetBarSurfaceHook.clampSurfaceArgs(env, method, args)
        assertEquals(1, args[1])
    }

    @Test
    fun clampSurfaceArgs_leavesFullWidthUntouched() {
        val env = CoolwalkHookEnv()
        env.mLayoutWidthDp = 800
        val args = arrayOf<Any?>("Dashboard", 800, 480)
        val method = Method::class.java.methods.first()
        CoolwalkFacetBarSurfaceHook.clampSurfaceArgs(env, method, args)
        assertEquals(800, args[1])
    }
}
