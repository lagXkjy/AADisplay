package io.github.nitsuya.aa.display.ui.aa.recent

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.model.RecentTaskInfo
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import kotlin.math.abs

data class RecentTaskAdapters(
    val primary: RecentTaskColumnAdapter,
    val secondary: RecentTaskColumnAdapter,
    val phone: RecentTaskColumnAdapter,
)

object RecentTaskColumns {

    @SuppressLint("ClickableViewAccessibility")
    fun wireThreeColumnRecents(
        left: RecyclerView,
        center: RecyclerView,
        right: RecyclerView,
        coordinator: RecentTasksCoordinator,
        onExit: () -> Unit,
        focusedPaneProvider: () -> Int,
        setFocusedPane: (Int) -> Unit,
    ): RecentTaskAdapters {
        var cachedFocus = runCatching { focusedPaneProvider() }.getOrDefault(SplitPane.PRIMARY)
            .let { if (SplitPane.isValid(it)) it else SplitPane.PRIMARY }
        val focusHandler = Handler(Looper.getMainLooper())
        fun focusPaneAsync(pane: Int) {
            if (cachedFocus == pane) return
            cachedFocus = pane
            focusHandler.post {
                runCatching { setFocusedPane(pane) }
            }
        }

        val host = object : RecentTaskColumnAdapter.Host {
            override fun onOpenTask(stackPane: Int?, item: RecentTaskInfo) =
                coordinator.openTask(stackPane, item)

            override fun onCloseTask(item: RecentTaskInfo) = coordinator.closeTask(item)

            override fun onPhoneSwipeLeft(item: RecentTaskInfo) =
                coordinator.onPhoneSwipeLeft(item, focusedPane())

            override fun onPhoneSwipeRight(item: RecentTaskInfo) =
                coordinator.onPhoneSwipeRight(item)

            override fun onPrimarySwipeLeft(item: RecentTaskInfo) =
                coordinator.onPrimarySwipeLeft(item)

            override fun onPrimarySwipeRight(item: RecentTaskInfo) =
                coordinator.onPrimarySwipeRight(item)

            override fun onSecondarySwipeLeft(item: RecentTaskInfo) =
                coordinator.onSecondarySwipeLeft(item)

            override fun onSecondarySwipeRight(item: RecentTaskInfo) =
                coordinator.onSecondarySwipeRight(item)

            override fun onReorderPaneStack(pane: Int, packagesTopToBottom: List<String>) =
                coordinator.reorderPaneStack(pane, packagesTopToBottom)

            override fun onDismissOverlay() {
                focusHandler.post { onExit() }
            }

            override fun focusedPane(): Int = cachedFocus
        }

        val phoneAdapter = RecentTaskColumnAdapter(right, host = host)
        val primaryAdapter = RecentTaskColumnAdapter(left, SplitPane.PRIMARY, host = host)
        val secondaryAdapter = RecentTaskColumnAdapter(center, SplitPane.SECONDARY, host = host)

        left.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = primaryAdapter
            itemAnimator = null
        }
        center.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = secondaryAdapter
            itemAnimator = null
        }
        right.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = phoneAdapter
            itemAnimator = null
        }

        arrayOf(left, center, right).forEach { rv ->
            rv.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.setTag(R.id.drag_last_x, event.x)
                        v.setTag(R.id.drag_last_y, event.y)
                        when (v.id) {
                            R.id.rv_recent_task_left -> focusPaneAsync(SplitPane.PRIMARY)
                            R.id.rv_recent_task_center -> focusPaneAsync(SplitPane.SECONDARY)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val isTap = abs((v.getTag(R.id.drag_last_x) as? Float ?: 0f) - event.x) <= 5
                            && abs((v.getTag(R.id.drag_last_y) as? Float ?: 0f) - event.y) <= 5
                        if (!isTap) return@setOnTouchListener false
                        focusHandler.post { onExit() }
                    }
                }
                false
            }
        }

        return RecentTaskAdapters(primaryAdapter, secondaryAdapter, phoneAdapter)
    }
}
