package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.CoolwalkRailMath.isRailVirtualDisplayName
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.CoolwalkRailMath.isThinRailSize

/**
 * VirtualDisplay sizing for Coolwalk FacetBar / Dashboard / content expand.
 * FacetBar named VDs are created at 1×H (C2); touch band width is recorded separately.
 */
object CoolwalkCompositorPolicy {

    data class VdRewrite(val width: Int, val height: Int)

    data class VdRewriteResult(
        val rewrite: VdRewrite?,
        val actions: List<RailAction> = emptyList(),
    )

    fun rewriteVirtualDisplayArgs(
        name: String?,
        width: Int,
        height: Int,
        layoutWidthPx: Int,
        layoutHeightPx: Int,
        observedRailWidthPx: Int,
    ): VdRewriteResult {
        if (width <= 0 || height <= 0) return VdRewriteResult(null)
        if (name.equals("Dashboard", ignoreCase = true)) {
            if (width == 1 && height == 1) return VdRewriteResult(null)
            return VdRewriteResult(VdRewrite(1, 1))
        }
        val railName = isRailVirtualDisplayName(name)
        if (railName) {
            if (width > 1) {
                val actions = CoolwalkRailCoordinator.onEvent(
                    RailEvent.FacetBarVdCreate(name, width, height, "vd-create:$name"),
                ).second
                return VdRewriteResult(VdRewrite(1, height), actions)
            }
            return VdRewriteResult(null)
        }
        if (isThinRailSize(width, height, layoutWidthPx)) {
            if (width > 1) {
                val actions = CoolwalkRailCoordinator.onEvent(
                    RailEvent.RailWidthObserved(width, "thin-vd:$name"),
                ).second
                return VdRewriteResult(VdRewrite(1, height), actions)
            }
            return VdRewriteResult(null)
        }
        // CarActivity presentation must match host layout width — expanding VD alone
        // letterboxes the 1173px UI inside a 1280 surface (black bars both sides).
        if (isAaDisplayPresentationVd(name)) return VdRewriteResult(null)
        val fullW = resolveTargetFullWidth(width, layoutWidthPx, observedRailWidthPx)
        if (fullW <= 0 || width >= fullW) return VdRewriteResult(null)
        val missing = fullW - width
        val range = CoolwalkRailMath.railPxRange(fullW)
        val looksLikeContentMinusRail =
            (observedRailWidthPx > 0 && kotlin.math.abs(missing - observedRailWidthPx) <= 2) ||
                missing in range
        if (!looksLikeContentMinusRail) return VdRewriteResult(null)
        return VdRewriteResult(VdRewrite(fullW, height))
    }

    private fun isAaDisplayPresentationVd(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        return name.contains("AaDisplayActivity", ignoreCase = true)
    }

    /**
     * Prefer current DrawingSpec width; only grow to observed full HU when the
     * incoming width is clearly a rail-trimmed content slot (not stale 1280 on 720 reconnect).
     */
    private fun resolveTargetFullWidth(
        width: Int,
        layoutWidthPx: Int,
        observedRailWidthPx: Int,
    ): Int {
        val observedFull = CoolwalkRailCoordinator.bestObservedFullHuWidthPx()
        if (layoutWidthPx > 0) {
            if (width >= layoutWidthPx - 2) return layoutWidthPx
            val layoutMissing = layoutWidthPx - width
            val layoutRange = CoolwalkRailMath.railPxRange(layoutWidthPx)
            if (layoutMissing in layoutRange) {
                return if (observedFull > layoutWidthPx &&
                    layoutWidthPx + layoutMissing >= observedFull - 2
                ) {
                    observedFull
                } else {
                    layoutWidthPx
                }
            }
            if (observedRailWidthPx > 0 &&
                kotlin.math.abs(layoutMissing - observedRailWidthPx) <= 2 &&
                observedFull > layoutWidthPx
            ) {
                return observedFull
            }
            return layoutWidthPx
        }
        if (observedFull > width) return observedFull
        return width
    }
}
