package io.github.nitsuya.aa.display.ui.aa.split

import android.view.Display
import io.github.nitsuya.aa.display.model.RecentTask
import io.github.nitsuya.aa.display.model.RecentTaskInfo

/**
 * Builds [RecentTask] snapshots for the AA Recents overlay.
 *
 * VD columns follow [PaneAppStack] order (top-first); ATMS supplies task metadata only.
 * The phone column uses live ATMS roots on [Display.DEFAULT_DISPLAY], excluding VD stacks.
 */
internal class RecentTaskProvider(private val c: SplitDisplayController) {

    fun buildSnapshot(): RecentTask {
        val vdPackages = vdPackageSet()
        val main = c.input.recentTaskInfo(Display.DEFAULT_DISPLAY, maxCount = PHONE_MAX_COUNT)
            .filter { info -> !isSessionClosed(info) }
            .filter { info ->
                info.packageName.isNullOrBlank() || info.packageName !in vdPackages
            }
        val primary = recentTaskForPane(SplitPane.PRIMARY)
        val secondary = recentTaskForPane(SplitPane.SECONDARY)
        return RecentTask(main, primary, secondary)
    }

    private fun isSessionClosed(info: RecentTaskInfo): Boolean {
        val pkg = info.packageName?.trim().orEmpty()
        return pkg.isNotEmpty() && pkg in c.mExplicitlyClosedPackages
    }

    private fun vdPackageSet(): Set<String> {
        val out = linkedSetOf<String>()
        for (pane in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
            for (pkg in c.stacks.packagesBottomToTop(pane)) {
                out.add(pkg)
            }
        }
        return out
    }

    private fun recentTaskForPane(pane: Int): List<RecentTaskInfo> {
        val displayId = c.input.displayIdFor(pane) ?: return emptyList()
        val stackOrder = c.stacks.packagesTopToBottom(pane)
        val atmsByPkg = c.input.recentTaskInfoByPackage(displayId)
        if (atmsByPkg.isEmpty() && stackOrder.isEmpty()) return emptyList()

        val result = ArrayList<RecentTaskInfo>(PaneAppStack.MAX_PER_PANE)
        val seen = linkedSetOf<String>()
        // Stack order first (intentional front→back).
        for (pkg in stackOrder) {
            if (pkg in c.mExplicitlyClosedPackages) continue
            atmsByPkg[pkg]?.let { info ->
                result.add(info)
                seen.add(pkg)
            }
        }
        // ATMS-only roots (launch in flight / stack not synced yet) — keeps VD column usable.
        for (info in atmsByPkg.values) {
            val pkg = info.packageName?.trim().orEmpty()
            if (pkg.isEmpty() || pkg in seen || pkg in c.mExplicitlyClosedPackages) continue
            result.add(info)
            seen.add(pkg)
            if (result.size >= PaneAppStack.MAX_PER_PANE) break
        }
        return result.take(PaneAppStack.MAX_PER_PANE)
    }

    companion object {
        private const val PHONE_MAX_COUNT = 8
    }
}
