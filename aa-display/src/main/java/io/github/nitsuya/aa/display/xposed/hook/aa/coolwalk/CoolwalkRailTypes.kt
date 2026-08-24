package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.view.Display

/** Coolwalk vertical-rail lifecycle for full-bleed AADisplay. */
enum class RailPhase(val code: Int) {
    Bootstrapping(0),
    RailPresent(1),
    Reclaiming(2),
    FullBleed(3),
    ReconnectSettling(4),
    ;

    companion object {
        fun fromCode(code: Int): RailPhase =
            entries.firstOrNull { it.code == code } ?: Bootstrapping
    }
}

/** Cross-process rail truth published by system_server. */
data class RailSnapshot(
    val phase: RailPhase = RailPhase.Bootstrapping,
    /** Content / profile / content_bounds — target always 0 after reclaim. */
    val effectiveRailWidthPx: Int = 0,
    /** Hit-test band for :car HU touch steal only. */
    val touchRailWidthPx: Int = 0,
    val fullHuWidthPx: Int = 0,
    /**
     * Observed content-area width (LayoutInfo / DrawingSpec slot), kept after reclaim even
     * when [layoutWidthPx] is promoted to [fullHuWidthPx]. Used for compositor inset / touch
     * remap — resolution-agnostic, not a per-HU constant.
     */
    val contentSlotWidthPx: Int = 0,
    val facetDisplayId: Int = Display.INVALID_DISPLAY,
    val layoutWidthPx: Int = 0,
    val layoutHeightPx: Int = 0,
    val fullBleedStableCount: Int = 0,
    val lastEvent: String = "",
    val updatedUptimeMs: Long = 0L,
)

sealed class RailEvent {
    abstract val reason: String

    data class LayoutInfo(val widthPx: Int, val heightPx: Int, override val reason: String) : RailEvent()
    data class RailWidthObserved(val widthPx: Int, override val reason: String) : RailEvent()
    data class FullHuObserved(val widthPx: Int, val heightPx: Int, override val reason: String) : RailEvent()
    data class FacetDisplayId(val displayId: Int, override val reason: String) : RailEvent()
    data class ContentBoundsExpanded(
        val fullWidthPx: Int,
        val fullHeightPx: Int,
        val railWidthPx: Int,
        override val reason: String,
    ) : RailEvent()
    data class FacetBarVdCreate(
        val name: String?,
        val requestedWidthPx: Int,
        val requestedHeightPx: Int,
        override val reason: String,
    ) : RailEvent()
    data class ReconnectStarted(override val reason: String) : RailEvent()
    data class GutterReclaim(override val reason: String) : RailEvent()
}

/** Side effects hooks should run after [CoolwalkRailCoordinator.onEvent]. */
sealed class RailAction {
    data class NotifyServer(val snapshot: RailSnapshot) : RailAction()
    data class ReclaimAllGutters(val reason: String) : RailAction()
}
