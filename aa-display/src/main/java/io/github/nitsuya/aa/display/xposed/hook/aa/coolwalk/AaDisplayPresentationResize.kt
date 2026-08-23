package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

/**
 * AADisplay process entry: lazy-hook [DrawingSpec] when gearhead dex loads.
 * Primary hook runs in gearhead (:car / :projection) via [CoolwalkDrawingSpecWiden.installGearhead].
 */
object AaDisplayPresentationResize {

    fun hookDisplayManager() {
        CoolwalkDrawingSpecWiden.installAaDisplayLazy()
    }
}
