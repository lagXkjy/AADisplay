package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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

    /**
     * True when [gap] is a single Coolwalk rail, not 2×rail (the 720→880 inflation).
     * When [observedRailWidthPx] is known, require a match; otherwise use the rail
     * pixel range capped at ~15% of HU so a double-rail gap cannot pass.
     */
    fun isPlausibleRailGap(gap: Int, fullW: Int, observedRailWidthPx: Int = 0): Boolean {
        if (gap <= 1 || fullW <= 0) return false
        if (observedRailWidthPx > 1) return abs(gap - observedRailWidthPx) <= 2
        if (gap !in railPxRange(fullW)) return false
        val maxSingle = (fullW * 0.15f).roundToInt().coerceAtLeast(railPxRange(fullW).first)
        return gap <= maxSingle
    }

    /**
     * Prefer a smaller HU width when the larger one is the smaller plus 1–3 rail strips
     * (runaway +rail inflation). Otherwise keep the max (real HU grew **in this session**).
     * Do not use this across connections — different cars are not +rail siblings.
     */
    fun mergeFullHuWidth(a: Int, b: Int, railWidthPx: Int): Int {
        val x = a.coerceAtLeast(0)
        val y = b.coerceAtLeast(0)
        if (x <= 0) return y
        if (y <= 0) return x
        val hi = max(x, y)
        val lo = min(x, y)
        if (hi == lo) return hi
        val rail = railWidthPx
        if (rail > 1) {
            val extra = hi - lo
            if (extra <= rail * 3 + 2 && extra % rail <= 2) return lo
        }
        return hi
    }

    /**
     * True when [measuredW]×[measuredH] is the same head-unit as [sessionW]×[sessionH]
     * (identical, or one is the other minus 1–3 Coolwalk rails). Different cars fail this.
     */
    fun isSameHuGeometry(
        sessionW: Int,
        sessionH: Int,
        measuredW: Int,
        measuredH: Int,
        railWidthPx: Int,
    ): Boolean {
        if (sessionW <= 0 || measuredW <= 0) return false
        if (sessionH > 0 && measuredH > 0 && abs(sessionH - measuredH) > 8) return false
        val extra = abs(sessionW - measuredW)
        if (extra <= 2) return true
        if (railWidthPx > 1 && extra <= railWidthPx * 3 + 2 && extra % railWidthPx <= 2) return true
        return isPlausibleRailGap(extra, max(sessionW, measuredW), railWidthPx)
    }

    /**
     * This-connection HU width. [measuredW]/[measuredH] is live LayoutInfo / Display /
     * reported size — never a hardcoded resolution. A new head-unit (different height,
     * or width not a rail sibling) replaces the previous session; same HU keeps the
     * full width rather than the content slot (HU − rail).
     */
    fun pickConnectionFullHuWidth(
        sessionFull: Int,
        sessionHeight: Int,
        measuredW: Int,
        measuredH: Int,
        railWidthPx: Int,
    ): Int {
        val measured = measuredW.coerceAtLeast(0)
        val session = sessionFull.coerceAtLeast(0)
        if (measured <= 0) return session
        if (session <= 0) return measured
        if (!isSameHuGeometry(session, sessionHeight, measured, measuredH, railWidthPx)) {
            return measured
        }
        if (session > measured && isPlausibleRailGap(session - measured, session, railWidthPx)) {
            return session
        }
        if (measured > session && isPlausibleRailGap(measured - session, measured, railWidthPx)) {
            return measured
        }
        return max(session, measured)
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
                isPlausibleRailGap(observedFull - rect.right, observedFull)
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
            return isPlausibleRailGap(observedFull - rect.right, observedFull)
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
        if (top > 0 || right <= left || bottom <= 0) return null
        val contentW = right - left
        val fullH = max(layoutHeightPx.takeIf { it > 0 } ?: 0, bottom)
        val layoutW = layoutWidthPx.takeIf { it > 0 } ?: 0
        val observedFull = observedFullHuWidthPx.takeIf { it > 0 } ?: 0
        val rangeHint = maxOf(layoutW, observedFull, right, right + left.coerceAtLeast(0))
        val range = railPxRange(rangeHint)

        // Form A: Rect(rail, 0, fullW, H) — Coolwalk right edge is the HU, never right+left.
        // Form B: Rect(rail, 0, contentRight, H) when LayoutInfo/observed is independently larger
        //         and equals right+left (e.g. 107/1173 vs 1280).
        if (left in range) {
            val formA = right
            val formB = right + left
            val formAMatches =
                (layoutW > 0 && abs(formA - layoutW) <= 2) ||
                    (observedFull > 0 && abs(formA - observedFull) <= 2)
            val formBMatches =
                (layoutW > right + 8 && abs(formB - layoutW) <= 2) ||
                    (observedFull > right + 8 && abs(formB - observedFull) <= 2)
            val target = when {
                formAMatches -> formA
                formBMatches -> maxOf(formB, layoutW, observedFull)
                else -> formA
            }
            if (left <= 0 && abs(right - target) <= 2) return null
            if (target > contentW + 2) {
                return ExpandedBounds(target, fullH, left)
            }
            return null
        }

        // Form C: Rect(0, 0, contentW, H) — expand only to a known full HU, never by
        // blindly adding touchRail onto a width that is already the HU.
        if (left <= 0) {
            val knownFull = max(observedFull, layoutW)
            if (knownFull > right + 8 && isPlausibleRailGap(knownFull - right, knownFull, touchRailWidthPx)) {
                return ExpandedBounds(knownFull, fullH, knownFull - right)
            }
            if (layoutW <= 0 && observedFull <= 0 && touchRailWidthPx > 1) {
                val inferred = right + touchRailWidthPx
                if (isPlausibleRailGap(touchRailWidthPx, inferred, touchRailWidthPx) && inferred > right) {
                    return ExpandedBounds(inferred, fullH, touchRailWidthPx)
                }
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

    /**
     * Target CarActivity / presentation width for this connection.
     * Keeps [hasVerticalRail]=true for facet chrome; only widens the canvas when
     * content_bounds reclaim already zeroed the compositor rail slot.
     */
    fun targetPresentationWidthPx(snapshot: RailSnapshot, contentWidth: Int): Int {
        val w = contentWidth.coerceAtLeast(0)
        val full = snapshot.fullHuWidthPx
        if (w <= 0 || full <= w + 2) return w.coerceAtLeast(1)
        if (snapshot.effectiveRailWidthPx > 1) return w
        val reclaimed = snapshot.fullBleedStableCount > 0 ||
            snapshot.phase == RailPhase.FullBleed ||
            (snapshot.phase == RailPhase.Reclaiming && full > w + 8)
        if (!reclaimed) return w
        if (!isPlausibleRailGap(full - w, full, snapshot.touchRailWidthPx)) return w
        return full
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
}
