package io.github.nitsuya.aa.display.util

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.RailPhase
import kotlin.math.abs

/**
 * Single display-profile settle rule for AA reconnect sizing.
 *
 * Coolwalk keeps two competing widths:
 * - full HU ([DrawingSpec] / expanded `content_bounds`) e.g. 800
 * - content slot (full − vertical rail) e.g. 720 when [GhFacetBar] still owns a compositor strip
 *
 * Rule: while a live FacetBar/rail VD still has a real strip width (>1), settle to
 * `fullHu − rail`; otherwise use full HU / reported size. No separate grow-vs-shrink
 * confirm ladders — both directions converge on this value.
 */
internal object DisplayProfileSettle {
    data class Size(val width: Int, val height: Int, val densityDpi: Int)

    fun isRailDisplayName(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        return name.contains("FacetBar", ignoreCase = true) ||
            name.contains("GhFacet", ignoreCase = true) ||
            name.contains("VerticalRail", ignoreCase = true) ||
            name.contains("EdgeColumn", ignoreCase = true)
    }

    fun railPxRange(fullW: Int): IntRange {
        if (fullW <= 0) return 32..160
        val min = (fullW * 0.05f).toInt().coerceIn(32, 96)
        val max = (fullW * 0.25f).toInt().coerceAtLeast(min).coerceAtMost(fullW / 2).coerceAtLeast(120)
        return min..max
    }

    private fun isPlausibleRailStrip(width: Int, height: Int): Boolean {
        if (width <= 1 || height <= 0) return false
        // Vertical rail: tall and narrow (e.g. 80×480).
        if (height < width * 2) return false
        return width in 32..160 || width in railPxRange(height)
    }

    /**
     * Live FacetBar strip width (px), or 0 when absent / starved to ≤1.
     * system_server can see private gearhead VDs.
     */
    fun observeLiveRailWidthPx(context: Context): Int {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return 0
        var best = 0
        for (display in dm.displays) {
            if (display.displayId == Display.DEFAULT_DISPLAY) continue
            val name = runCatching { display.name }.getOrNull() ?: continue
            if (!isRailDisplayName(name)) continue
            val w = runCatching { display.mode.physicalWidth }.getOrNull() ?: continue
            val h = runCatching { display.mode.physicalHeight }.getOrNull() ?: continue
            if (isPlausibleRailStrip(w, h) && w > best) best = w
        }
        return best
    }

    /**
     * Best-effort full HU width from live Displays / reported.
     * Skip our own split VDs and the CarActivity presentation — the latter is often
     * still HU−rail while LayoutInfo / content_bounds already know the true full HU.
     */
    fun observeFullHuWidthPx(context: Context, reportedWidth: Int, reportedHeight: Int): Int {
        var best = reportedWidth.coerceAtLeast(1)
        val dm = context.getSystemService(DisplayManager::class.java) ?: return best
        for (display in dm.displays) {
            if (display.displayId == Display.DEFAULT_DISPLAY) continue
            val name = runCatching { display.name }.getOrNull() ?: continue
            if (isRailDisplayName(name)) continue
            if (name.contains("Dashboard", ignoreCase = true)) continue
            // Split VDs are derived from settle — including them loops inflated sizes.
            if (name.startsWith("AADisplay-")) continue
            // Presentation often stays at content slot; do not treat it as full HU.
            if (name.contains("AaDisplayActivity", ignoreCase = true)) continue
            val w = runCatching { display.mode.physicalWidth }.getOrNull() ?: continue
            val h = runCatching { display.mode.physicalHeight }.getOrNull() ?: continue
            if (w <= 1 || h <= 1) continue
            // Same landscape band as the reported HU (tolerate small jitter).
            if (reportedHeight > 0 && abs(h - reportedHeight) > 8) continue
            if (w < reportedWidth / 2) continue
            if (w > best) best = w
        }
        return best
    }

    /**
     * True when [reportedW] looks like this-connection full HU minus one Coolwalk rail
     * (e.g. 1173 vs 1280, 720 vs 800). Used so we do not inflate split VDs while the
     * CarActivity presentation is still the content slot.
     */
    fun isContentSlotVsFull(reportedW: Int, fullW: Int): Boolean {
        if (reportedW <= 0 || fullW <= 0) return false
        val gap = fullW - reportedW
        if (gap <= 1) return false
        if (gap !in railPxRange(fullW)) return false
        val maxSingle = (fullW * 0.15f).toInt().coerceAtLeast(railPxRange(fullW).first)
        return gap <= maxSingle
    }

    /**
     * @param reported client / presentation / DrawingSpec size (may be full HU or content slot)
     * @param railWidthPx live FacetBar strip; ≤1 means reclaimed or absent
     * @param fullHuWidthPx observed full HU width (≥ reported when presentation is full-bleed)
     */
    fun settle(reported: Size, railWidthPx: Int, fullHuWidthPx: Int = reported.width): Size {
        val h = reported.height.coerceAtLeast(1)
        val dpi = reported.densityDpi.coerceAtLeast(1)
        val full = fullHuWidthPx.coerceAtLeast(reported.width).coerceAtLeast(1)
        if (railWidthPx <= 1) {
            // FacetBar starved, but CarActivity presentation may still be HU−rail.
            // After content_bounds reclaim is visible in the server snapshot, settle to
            // full HU so split VDs are not stuck at 693 while the compositor gutter is gone.
            if (isContentSlotVsFull(reported.width, full) && !coolwalkReclaimProven(full)) {
                return Size(reported.width.coerceAtLeast(1), h, dpi)
            }
            return Size(full, h, dpi)
        }
        if (!isPlausibleRailStrip(railWidthPx, h) && railWidthPx !in 32..160) {
            return Size(full, h, dpi)
        }
        val contentW = full - railWidthPx
        if (contentW < full / 2 || contentW < 200) {
            return Size(full, h, dpi)
        }
        return Size(contentW, h, dpi)
    }

    private fun coolwalkReclaimProven(fullHuWidthPx: Int): Boolean {
        val snap = CoolwalkRailStore.serverSnapshot
        if (snap.phase == RailPhase.FullBleed || snap.phase == RailPhase.Reclaiming) {
            return snap.fullHuWidthPx >= fullHuWidthPx - 2
        }
        val sessionFull = CoolwalkRailStore.resolvedSession()?.fullHuWidthPx ?: 0
        return sessionFull >= fullHuWidthPx - 2 && snap.fullHuWidthPx >= fullHuWidthPx - 2
    }
}
