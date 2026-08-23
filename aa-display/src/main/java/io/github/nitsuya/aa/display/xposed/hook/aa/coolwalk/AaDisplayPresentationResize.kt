package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

/**
 * CarActivity presentation VirtualDisplay is created in the AADisplay process.
 *
 * Never widen the presentation VD at create time: the Car SDK encoder Surface is
 * sized to the content slot it requested (e.g. 1173 on 1280×720 HUs). A wider VD
 * without a matching Surface blacks the head unit; 800×400 often reports full width
 * already so it never hit this path. Full-bleed is handled by content_bounds /
 * gutter reclaim + optional full-bleed relaunch, not VD buffer inflation.
 */
object AaDisplayPresentationResize {

    fun hookDisplayManager() {
        // Intentionally no-op — see class comment.
    }
}
