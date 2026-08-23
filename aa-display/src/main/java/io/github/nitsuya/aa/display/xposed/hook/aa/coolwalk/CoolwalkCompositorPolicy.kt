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
        // Never rewrite AaDisplayActivity presentation buffer size. The Car SDK
        // allocates the encoder Surface at the original slot; changing the VD
        // to full HU without a matching Surface produces a black screen.
        if (isAaDisplayPresentationVd(name)) {
            return VdRewriteResult(null)
        }
        val fullW = resolveTargetFullWidth(width, layoutWidthPx, observedRailWidthPx)
        if (fullW <= 0 || width >= fullW) return VdRewriteResult(null)
        val missing = fullW - width
        if (!CoolwalkRailMath.isPlausibleRailGap(missing, fullW, observedRailWidthPx)) {
            return VdRewriteResult(null)
        }
        return VdRewriteResult(VdRewrite(fullW, height))
    }

    private fun isAaDisplayPresentationVd(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        return name.contains("AaDisplayActivity", ignoreCase = true)
    }

    /**
     * Prefer current DrawingSpec width; only grow to observed full HU when the
     * incoming width is clearly a rail-trimmed content slot.
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
            if (CoolwalkRailMath.isPlausibleRailGap(layoutMissing, layoutWidthPx, observedRailWidthPx)) {
                return if (observedFull > layoutWidthPx &&
                    CoolwalkRailMath.isPlausibleRailGap(
                        observedFull - width,
                        observedFull,
                        observedRailWidthPx,
                    )
                ) {
                    observedFull
                } else {
                    layoutWidthPx
                }
            }
            return layoutWidthPx
        }
        if (observedFull > width) return observedFull
        return width
    }
}
