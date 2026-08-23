package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Pure rail geometry — JVM-testable, no Android framework deps beyond Rect. */
object CoolwalkRailMath {

    const val FULL_BLEED_STABLE_THRESHOLD = 3

    fun railPxRange(fullW: Int): IntRange {
        if (fullW <= 0) return 32..160
        val min = (fullW * 0.05f).roundToInt().coerceIn(32, 96)
        val max = (fullW * 0.25f).roundToInt().coerceAtLeast(min).coerceAtMost(fullW / 2).coerceAtLeast(120)
        return min..max
    }

    fun isAbsoluteNarrowRailSize(width: Int, height: Int): Boolean =
        width in 8..120 && height >= width * 3

    fun isThinRailSize(width: Int, height: Int, layoutWidthPx: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (layoutWidthPx <= 0) return isAbsoluteNarrowRailSize(width, height)
        val maxRail = railPxRange(layoutWidthPx).last
        return width <= maxRail && height >= width * 2
    }

    fun isRailVirtualDisplayName(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        return name.contains("FacetBar", ignoreCase = true) ||
            name.contains("GhFacet", ignoreCase = true) ||
            name.contains("VerticalRail", ignoreCase = true) ||
            name.contains("EdgeColumn", ignoreCase = true)
    }

    fun layoutWidthPx(snapshot: RailSnapshot): Int =
        max(snapshot.layoutWidthPx, snapshot.fullHuWidthPx).takeIf { it > 0 } ?: 0

    fun looksLikeHuContentBounds(
        rect: Rect,
        layoutWidthPx: Int,
        layoutHeightPx: Int,
        observedFullHuWidthPx: Int = 0,
    ): Boolean {
        if (rect.width() <= 0 || rect.height() <= 0 || rect.top > 0) return false
        val fullH = layoutHeightPx.takeIf { it > 0 } ?: rect.bottom
        val observedFull = max(observedFullHuWidthPx, layoutWidthPx).takeIf { it > 0 } ?: 0
        val fullW = layoutWidthPx.takeIf { it > 0 } ?: observedFull.takeIf { it > 0 } ?: rect.right
        if (fullH > 0 && fullW > 0) {
            val heightOk = abs(rect.bottom - fullH) <= 2
            val range = railPxRange(maxOf(fullW, rect.right, observedFull))
            val contentSlotInset = rect.left <= 0 &&
                observedFull > rect.right + 8 &&
                (observedFull - rect.right) in range
            val widthOk = abs(rect.right - fullW) <= 2 ||
                abs(rect.right + rect.left - fullW) <= 2 ||
                (rect.left > 0 && abs(rect.right - (fullW + rect.left)) <= 2) ||
                contentSlotInset
            if (!heightOk || !widthOk) return false
            val leftInset = rect.left in range
            val rightInset = rect.left <= 0 && (fullW - rect.right) in range
            return leftInset || rightInset || contentSlotInset
        }
        if (rect.right < 320 || rect.bottom < 180) return false
        val approxFullW = max(rect.right, observedFull).takeIf { it > 0 } ?: rect.right
        if (rect.left in railPxRange(approxFullW)) return true
        if (rect.left <= 0 && observedFull > rect.right + 8) {
            return (observedFull - rect.right) in railPxRange(observedFull)
        }
        return false
    }

    data class ExpandedBounds(
        val targetWidthPx: Int,
        val targetHeightPx: Int,
        val railWidthPx: Int,
    )

    /**
     * Expand rail-inset content_bounds to full HU. Returns null when unchanged.
     */
    fun computeExpandedContentBounds(
        rect: Rect,
        layoutWidthPx: Int,
        layoutHeightPx: Int,
        observedFullHuWidthPx: Int,
        touchRailWidthPx: Int = 0,
    ): ExpandedBounds? = computeExpandedContentBounds(
        left = rect.left,
        top = rect.top,
        right = rect.right,
        bottom = rect.bottom,
        layoutWidthPx = layoutWidthPx,
        layoutHeightPx = layoutHeightPx,
        observedFullHuWidthPx = observedFullHuWidthPx,
        touchRailWidthPx = touchRailWidthPx,
    )

    fun computeExpandedContentBounds(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        layoutWidthPx: Int,
        layoutHeightPx: Int,
        observedFullHuWidthPx: Int,
        touchRailWidthPx: Int = 0,
    ): ExpandedBounds? {
        val fullW = layoutWidthPx.takeIf { it > 0 } ?: right
        val fullH = layoutHeightPx.takeIf { it > 0 } ?: bottom
        val range = railPxRange(fullW)
        val observedFull = max(observedFullHuWidthPx, fullW)
        // Content slot Rect(0,0,HU−rail,H) in :car when full HU not yet in snapshot.
        if (left <= 0 && top <= 0 && touchRailWidthPx > 1 && right > 0) {
            val alreadyFull = observedFullHuWidthPx > 0 && right >= observedFullHuWidthPx - 2
            if (!alreadyFull) {
                val inferredFull = right + touchRailWidthPx
                if (touchRailWidthPx in railPxRange(inferredFull) && inferredFull > right) {
                    return ExpandedBounds(inferredFull, fullH.coerceAtLeast(bottom), touchRailWidthPx)
                }
            }
        }
        if (left <= 0 && observedFull > fullW + 8) {
            val gap = observedFull - right
            if (gap in railPxRange(observedFull) && top <= 0) {
                return ExpandedBounds(
                    observedFull,
                    fullH.coerceAtLeast(bottom),
                    gap,
                )
            }
        }
        if (left in range && top <= 0 &&
            (right >= fullW - 2 || abs(right - (fullW + left)) <= 2)
        ) {
            val targetW = maxOf(fullW, right)
            return ExpandedBounds(targetW, fullH.coerceAtLeast(bottom), left)
        }
        val rightGap = fullW - right
        if (left <= 0 && rightGap in range && top <= 0) {
            return ExpandedBounds(fullW, fullH.coerceAtLeast(bottom), rightGap)
        }
        if (left <= 0 && top <= 0 && observedFull > right + 8) {
            val gap = observedFull - right
            if (gap in railPxRange(observedFull)) {
                return ExpandedBounds(
                    observedFull,
                    fullH.coerceAtLeast(bottom),
                    gap,
                )
            }
        }
        return null
    }

    fun applyExpandedContentBounds(
        rect: Rect,
        layoutWidthPx: Int,
        layoutHeightPx: Int,
        observedFullHuWidthPx: Int,
        touchRailWidthPx: Int = 0,
    ): ExpandedBounds? {
        val expanded = computeExpandedContentBounds(
            rect,
            layoutWidthPx,
            layoutHeightPx,
            observedFullHuWidthPx,
            touchRailWidthPx,
        ) ?: return null
        rect.set(0, 0, expanded.targetWidthPx, expanded.targetHeightPx)
        return expanded
    }

    fun computeZeroedContentInsets(
        rect: Rect,
        layoutWidthPx: Int,
    ): Int? {
        val range = railPxRange(layoutWidthPx.takeIf { it > 0 } ?: rect.left.coerceAtLeast(rect.right) * 10)
        if (rect.left in range) return rect.left
        if (rect.right in range) return rect.right
        return null
    }

    fun railHitWidthPx(snapshot: RailSnapshot): Int {
        val fullW = layoutWidthPx(snapshot)
        val observed = snapshot.touchRailWidthPx
        if (fullW > 0) {
            val range = railPxRange(fullW)
            if (observed in range) return observed
            if (observed in 8..(fullW / 2)) return observed
            return (fullW * 0.08f).roundToInt().coerceIn(range)
        }
        if (observed in 8..240) return observed
        return 48
    }

    fun settleProfileWidth(
        reportedWidth: Int,
        reportedHeight: Int,
        railWidthPx: Int,
        fullHuWidthPx: Int,
    ): Int {
        val full = fullHuWidthPx.coerceAtLeast(reportedWidth).coerceAtLeast(1)
        val h = reportedHeight.coerceAtLeast(1)
        if (railWidthPx <= 1) return full
        if (h < railWidthPx * 2 && railWidthPx !in 32..160) return full
        val contentW = full - railWidthPx
        if (contentW < full / 2 || contentW < 200) return full
        return contentW
    }
}
