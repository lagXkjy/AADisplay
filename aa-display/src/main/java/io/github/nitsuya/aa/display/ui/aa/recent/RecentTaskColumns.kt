package io.github.nitsuya.aa.display.ui.aa.recent

import android.view.MotionEvent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import kotlin.math.abs

data class RecentTaskAdapters(
    val primary: RecentTaskColumnAdapter,
    val secondary: RecentTaskColumnAdapter,
    val phone: RecentTaskColumnAdapter,
)

object RecentTaskColumns {

    fun wireThreeColumnRecents(
        left: RecyclerView,
        center: RecyclerView,
        right: RecyclerView,
        onExit: () -> Unit,
        focusedPaneProvider: () -> Int,
        setFocusedPane: (Int) -> Unit,
    ): RecentTaskAdapters {
        val phoneAdapter = RecentTaskColumnAdapter(right, onExit = onExit)
        val primaryAdapter = RecentTaskColumnAdapter(left, SplitPane.PRIMARY, onExit)
        val secondaryAdapter = RecentTaskColumnAdapter(center, SplitPane.SECONDARY, onExit)
        phoneAdapter.primaryAdapter = primaryAdapter
        phoneAdapter.secondaryAdapter = secondaryAdapter
        primaryAdapter.phoneAdapter = phoneAdapter
        primaryAdapter.secondaryAdapter = secondaryAdapter
        secondaryAdapter.phoneAdapter = phoneAdapter
        secondaryAdapter.primaryAdapter = primaryAdapter

        left.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = primaryAdapter
        }
        center.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = secondaryAdapter
        }
        right.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = phoneAdapter
        }

        arrayOf(left, center, right).forEach { rv ->
            rv.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.setTag(R.id.drag_last_x, event.x)
                        v.setTag(R.id.drag_last_y, event.y)
                        when (v.id) {
                            R.id.rv_recent_task_left -> {
                                v.setTag(
                                    R.id.pane_was_focused,
                                    focusedPaneProvider() == SplitPane.PRIMARY,
                                )
                                setFocusedPane(SplitPane.PRIMARY)
                            }
                            R.id.rv_recent_task_center -> {
                                v.setTag(
                                    R.id.pane_was_focused,
                                    focusedPaneProvider() == SplitPane.SECONDARY,
                                )
                                setFocusedPane(SplitPane.SECONDARY)
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        // Empty tap: phone always dismisses. VD first tap selects move
                        // target; second tap on the already-focused column dismisses.
                        val isTap = abs((v.getTag(R.id.drag_last_x) as? Float ?: 0f) - event.x) <= 5
                            && abs((v.getTag(R.id.drag_last_y) as? Float ?: 0f) - event.y) <= 5
                        if (!isTap) return@setOnTouchListener false
                        val dismiss = when (v.id) {
                            R.id.rv_recent_task_right -> true
                            R.id.rv_recent_task_left,
                            R.id.rv_recent_task_center ->
                                v.getTag(R.id.pane_was_focused) as? Boolean == true
                            else -> false
                        }
                        if (dismiss) onExit()
                    }
                }
                false
            }
        }

        return RecentTaskAdapters(primaryAdapter, secondaryAdapter, phoneAdapter)
    }
}
