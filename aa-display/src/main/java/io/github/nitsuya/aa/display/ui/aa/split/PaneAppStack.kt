package io.github.nitsuya.aa.display.ui.aa.split

/**
 * Per-pane multi-app stack for PRIMARY / SECONDARY VirtualDisplays.
 *
 * Order is **bottom → top**; the last element is the visible front app
 * ([SplitDisplayController.mPanePackages]).
 *
 * When syncing from ATMS, always go through
 * [SplitOwnership.normalizeRootTasksBottomToTop] — Samsung may enumerate
 * top→bottom; never assume raw [getAllRootTaskInfosOnDisplay] ends with the front.
 *
 * Capacity is [MAX_PER_PANE]; pushing a new package when full evicts the bottom.
 */
internal class PaneAppStack(private val c: SplitDisplayController) {

    companion object {
        const val MAX_PER_PANE = 3
    }

    /** Bottom → top package lists for [SplitPane.PRIMARY] / [SplitPane.SECONDARY]. */
    private val stacks: Array<ArrayList<String>> = Array(2) { ArrayList(MAX_PER_PANE) }

    fun clearAll() {
        stacks[0].clear()
        stacks[1].clear()
    }

    fun clear(pane: Int) {
        if (!SplitPane.isValid(pane)) return
        stacks[pane].clear()
        c.mPanePackages[pane] = null
    }

    fun packagesBottomToTop(pane: Int): List<String> {
        if (!SplitPane.isValid(pane)) return emptyList()
        return stacks[pane].toList()
    }

    /** Top → bottom (UI / Recents order; index 0 = currently displayed). */
    fun packagesTopToBottom(pane: Int): List<String> {
        if (!SplitPane.isValid(pane)) return emptyList()
        return stacks[pane].asReversed()
    }

    fun front(pane: Int): String? {
        if (!SplitPane.isValid(pane)) return null
        return stacks[pane].lastOrNull()
    }

    fun contains(pane: Int, packageName: String): Boolean {
        if (!SplitPane.isValid(pane)) return false
        return stacks[pane].contains(packageName)
    }

    fun containsAnywhere(packageName: String): Boolean =
        stacks[0].contains(packageName) || stacks[1].contains(packageName)

    fun paneContaining(packageName: String): Int? {
        val pkg = packageName.trim().takeIf { it.isNotEmpty() } ?: return null
        return when {
            stacks[SplitPane.PRIMARY].contains(pkg) -> SplitPane.PRIMARY
            stacks[SplitPane.SECONDARY].contains(pkg) -> SplitPane.SECONDARY
            else -> null
        }
    }

    fun syncFrontToMPanePackages() {
        for (pane in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
            c.mPanePackages[pane] = stacks[pane].lastOrNull()
        }
    }

    /**
     * Push [packageName] to the top of [pane]. Removes it from the other pane's stack.
     * @return package that was evicted from the bottom when the stack was full, or null
     */
    fun pushToTop(pane: Int, packageName: String): String? {
        if (!SplitPane.isValid(pane)) return null
        val pkg = packageName.trim().takeIf { it.isNotEmpty() } ?: return null
        val other = if (pane == SplitPane.PRIMARY) SplitPane.SECONDARY else SplitPane.PRIMARY
        stacks[other].remove(pkg)

        val stack = stacks[pane]
        if (stack.lastOrNull() == pkg) {
            syncFrontToMPanePackages()
            return null
        }
        stack.remove(pkg)
        var evicted: String? = null
        if (stack.size >= MAX_PER_PANE) {
            evicted = stack.removeAt(0)
        }
        stack.add(pkg)
        syncFrontToMPanePackages()
        return evicted
    }

    fun moveToTop(pane: Int, packageName: String): Boolean {
        if (!SplitPane.isValid(pane)) return false
        val pkg = packageName.trim().takeIf { it.isNotEmpty() } ?: return false
        val stack = stacks[pane]
        if (!stack.contains(pkg)) return false
        if (stack.lastOrNull() == pkg) {
            syncFrontToMPanePackages()
            return true
        }
        stack.remove(pkg)
        stack.add(pkg)
        syncFrontToMPanePackages()
        return true
    }

    fun remove(pane: Int, packageName: String): Boolean {
        if (!SplitPane.isValid(pane)) return false
        val pkg = packageName.trim().takeIf { it.isNotEmpty() } ?: return false
        val removed = stacks[pane].remove(pkg)
        if (removed) syncFrontToMPanePackages()
        return removed
    }

    fun removeFromAll(packageName: String): List<Int> {
        val pkg = packageName.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val vacated = mutableListOf<Int>()
        for (pane in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
            if (stacks[pane].remove(pkg)) vacated += pane
        }
        if (vacated.isNotEmpty()) syncFrontToMPanePackages()
        return vacated
    }

    fun setStackBottomToTop(pane: Int, packages: List<String>) {
        if (!SplitPane.isValid(pane)) return
        val stack = stacks[pane]
        stack.clear()
        val seen = linkedSetOf<String>()
        for (raw in packages) {
            val pkg = raw.trim().takeIf { it.isNotEmpty() } ?: continue
            if (!seen.add(pkg)) continue
            stack.add(pkg)
        }
        while (stack.size > MAX_PER_PANE) {
            stack.removeAt(0)
        }
        // Cross-pane exclusivity: drop from the other stack.
        val other = if (pane == SplitPane.PRIMARY) SplitPane.SECONDARY else SplitPane.PRIMARY
        for (pkg in stack) {
            stacks[other].remove(pkg)
        }
        syncFrontToMPanePackages()
    }

    fun swapStacks() {
        val tmp = ArrayList(stacks[SplitPane.PRIMARY])
        stacks[SplitPane.PRIMARY].clear()
        stacks[SplitPane.PRIMARY].addAll(stacks[SplitPane.SECONDARY])
        stacks[SplitPane.SECONDARY].clear()
        stacks[SplitPane.SECONDARY].addAll(tmp)
        syncFrontToMPanePackages()
    }

    /**
     * Drop packages no longer alive on the pane display; keep relative order.
     * @return true if the front package changed
     */
    fun trimToAlive(pane: Int, alivePackages: Collection<String>): Boolean {
        if (!SplitPane.isValid(pane)) return false
        val before = stacks[pane].lastOrNull()
        val alive = alivePackages.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        stacks[pane].removeAll { it !in alive }
        syncFrontToMPanePackages()
        return stacks[pane].lastOrNull() != before
    }

    /**
     * Rebuild from ATMS user-root order (bottom → top), capped at [MAX_PER_PANE]
     * (keeps the topmost packages).
     */
    fun syncFromAtmsBottomToTop(pane: Int, packagesBottomToTop: List<String>) {
        if (!SplitPane.isValid(pane)) return
        val cleaned = ArrayList<String>(MAX_PER_PANE)
        val seen = linkedSetOf<String>()
        for (raw in packagesBottomToTop) {
            val pkg = raw.trim().takeIf { it.isNotEmpty() } ?: continue
            if (!seen.add(pkg)) continue
            cleaned.add(pkg)
        }
        val kept = if (cleaned.size > MAX_PER_PANE) {
            cleaned.takeLast(MAX_PER_PANE)
        } else {
            cleaned
        }
        setStackBottomToTop(pane, kept)
    }

    /**
     * Merge ATMS-visible packages into the intentional stack without wiping buried members
     * that demote may have dropped from the ATMS walk.
     *
     * - Drop bookkeeping packages no longer in [aliveBottomToTop]
     * - Keep relative order of remaining intentional entries
     * - Append ATMS-only packages at the bottom (capacity permitting; launch paths evict)
     */
    fun mergeAliveKeepingOrder(pane: Int, aliveBottomToTop: List<String>) {
        if (!SplitPane.isValid(pane)) return
        val alive = ArrayList<String>(MAX_PER_PANE)
        val seen = linkedSetOf<String>()
        for (raw in aliveBottomToTop) {
            val pkg = raw.trim().takeIf { it.isNotEmpty() } ?: continue
            if (!seen.add(pkg)) continue
            alive.add(pkg)
        }
        val aliveSet = alive.toSet()
        val merged = ArrayList<String>(MAX_PER_PANE)
        // Keep intentional buried order for packages still alive.
        for (pkg in stacks[pane]) {
            if (pkg in aliveSet) merged.add(pkg)
        }
        // ATMS-only packages: prepend at bottom preserving ATMS bottom→top order.
        val extras = alive.filter { it !in merged }
        val room = MAX_PER_PANE - merged.size
        if (room > 0 && extras.isNotEmpty()) {
            merged.addAll(0, extras.take(room))
        }
        while (merged.size > MAX_PER_PANE) {
            merged.removeAt(0)
        }
        setStackBottomToTop(pane, merged)
    }
}
