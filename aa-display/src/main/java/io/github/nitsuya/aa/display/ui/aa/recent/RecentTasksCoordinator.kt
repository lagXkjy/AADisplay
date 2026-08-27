package io.github.nitsuya.aa.display.ui.aa.recent

import android.os.Handler
import android.os.Looper
import io.github.duzhaokun123.template.utils.runIO
import io.github.duzhaokun123.template.utils.runMain
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.model.RecentTask
import io.github.nitsuya.aa.display.model.RecentTaskInfo
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import java.util.concurrent.Executors

/**
 * UI-side coordinator for the three-column Recents overlay.
 * Loads snapshots from [CoreApi] and applies mutations without racing reloads.
 */
class RecentTasksCoordinator(
    private val onExit: () -> Unit,
) {
    @Volatile
    private var adapters: RecentTaskAdapters? = null
    @Volatile
    private var reloadGeneration = 0
    /** Skip one debounced dirty reload after optimistic close (UI already updated). */
    @Volatile
    private var skipDirtyReloads = 0

    /** UI-side hide until server snapshot catches up (explicit close). */
    private val sessionHiddenTaskIds = mutableSetOf<Int>()
    private val sessionHiddenPackages = mutableSetOf<String>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val debouncedReload = Runnable { reloadNow() }
    /** Fire-and-forget ATMS mutations — never block the main thread or queue behind reload IO. */
    private val mutationExecutor = Executors.newSingleThreadExecutor()

    fun bind(adapters: RecentTaskAdapters) {
        this.adapters = adapters
    }

    /** First paint / explicit refresh — no debounce. */
    fun reloadImmediate() {
        mainHandler.removeCallbacks(debouncedReload)
        reloadNow()
    }

    /** ATMS dirty / stack settle — coalesce Binder+bitmap IPC. */
    fun reload() {
        if (skipDirtyReloads > 0) {
            skipDirtyReloads--
            return
        }
        mainHandler.removeCallbacks(debouncedReload)
        mainHandler.postDelayed(debouncedReload, RELOAD_DEBOUNCE_MS)
    }

    private fun reloadNow() {
        val cols = adapters ?: return
        val gen = ++reloadGeneration
        runIO {
            val snap = CoreApi.getRecentTask() ?: return@runIO
            val filtered = filterSnapshot(snap)
            runMain {
                if (gen != reloadGeneration) return@runMain
                cols.primary.setItems(filtered.primaryDisplay)
                cols.secondary.setItems(filtered.secondaryDisplay)
                cols.phone.setItems(filtered.mainDisplay)
            }
        }
    }

    private fun filterSnapshot(snap: RecentTask): RecentTask {
        fun keep(info: RecentTaskInfo): Boolean {
            val pkg = info.packageName?.trim().orEmpty()
            if (info.taskId in sessionHiddenTaskIds) return false
            if (pkg.isNotEmpty() && pkg in sessionHiddenPackages) return false
            return true
        }
        return RecentTask(
            snap.mainDisplay.filter(::keep),
            snap.primaryDisplay.filter(::keep),
            snap.secondaryDisplay.filter(::keep),
        )
    }

    private fun rememberClosed(item: RecentTaskInfo) {
        sessionHiddenTaskIds.add(item.taskId)
        item.packageName?.trim()?.takeIf { it.isNotEmpty() }?.let { sessionHiddenPackages.add(it) }
    }

    private fun clearSessionHidden(item: RecentTaskInfo) {
        sessionHiddenTaskIds.remove(item.taskId)
        item.packageName?.trim()?.takeIf { it.isNotEmpty() }?.let { sessionHiddenPackages.remove(it) }
    }

    /** Debounce reload so move IPC can settle before ATMS snapshot. */
    private fun scheduleReload(delayMs: Long = RELOAD_DEBOUNCE_MS) {
        mainHandler.removeCallbacks(debouncedReload)
        mainHandler.postDelayed(debouncedReload, delayMs)
    }

    private fun runMutation(block: () -> Unit) {
        mutationExecutor.execute {
            try {
                block()
            } catch (_: Throwable) {
            }
        }
    }

    private fun removeFromAllColumns(item: RecentTaskInfo) {
        val cols = adapters ?: return
        cols.primary.removeItemIfPresent(item)
        cols.secondary.removeItemIfPresent(item)
        cols.phone.removeItemIfPresent(item)
    }

    fun openTask(stackPane: Int?, item: RecentTaskInfo) {
        clearSessionHidden(item)
        // Do not commitNow-remove Recents from inside the row click callback.
        mainHandler.post { onExit() }
        val pane = stackPane
        val pkg = item.packageName
        val taskId = item.taskId
        runMutation {
            if (pane != null) {
                if (!pkg.isNullOrBlank()) {
                    // Sync IPC + handler front — VD promote finishes before mutation returns.
                    CoreApi.startActivityOnPaneForUser(pkg, 0, pane)
                } else {
                    CoreApi.setFocusedPane(pane)
                    CoreApi.moveTaskToFront(taskId)
                }
            } else {
                CoreApi.moveTaskToFront(taskId)
            }
        }
    }

    /** Optimistic row removal + session hide; shared by × close and swipe-off. */
    private fun closeTaskOptimistic(item: RecentTaskInfo) {
        rememberClosed(item)
        removeFromAllColumns(item)
        ++reloadGeneration
        skipDirtyReloads++
        mainHandler.removeCallbacks(debouncedReload)
        runMutation { CoreApi.removeTask(item.taskId) }
    }

    fun closeTask(item: RecentTaskInfo) = closeTaskOptimistic(item)

    fun onPhoneSwipeLeft(item: RecentTaskInfo, focusedPane: Int) {
        val pane = if (SplitPane.isValid(focusedPane)) focusedPane else SplitPane.PRIMARY
        runMutation {
            CoreApi.moveTaskIdToPane(item.taskId, pane)
            mainHandler.post { scheduleReload() }
        }
    }

    fun onPhoneSwipeRight(item: RecentTaskInfo) = closeTaskOptimistic(item)

    fun onPrimarySwipeLeft(item: RecentTaskInfo) = closeTaskOptimistic(item)

    fun onPrimarySwipeRight(item: RecentTaskInfo) {
        runMutation {
            CoreApi.moveTaskId(item.taskId, false)
            mainHandler.post { scheduleReload() }
        }
    }

    fun onSecondarySwipeLeft(item: RecentTaskInfo) {
        runMutation {
            CoreApi.moveTaskIdToPane(item.taskId, SplitPane.PRIMARY)
            mainHandler.post { scheduleReload() }
        }
    }

    fun onSecondarySwipeRight(item: RecentTaskInfo) {
        runMutation {
            CoreApi.moveTaskId(item.taskId, false)
            mainHandler.post { scheduleReload() }
        }
    }

    fun reorderPaneStack(pane: Int, packagesTopToBottom: List<String>) {
        val pkgs = packagesTopToBottom.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
        if (pkgs.isEmpty()) return
        // Sync IPC persists stack + promote; adapter order already updated by drag — skip reload.
        runMutation { CoreApi.reorderPaneStack(pane, pkgs.toTypedArray()) }
    }

    companion object {
        private const val RELOAD_DEBOUNCE_MS = 280L
    }
}
