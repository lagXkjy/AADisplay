package io.github.nitsuya.aa.display.ui.aa.fragment

import android.view.MotionEvent
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.duzhaokun123.template.bases.BaseFragment
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.FragmentAaRecentTaskBinding
import io.github.nitsuya.aa.display.ui.aa.AaDisplayActivityKt
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.ui.window.DisplayRecyclerViewAdapter
import io.github.nitsuya.template.bases.runIO
import io.github.nitsuya.template.bases.runMain
import kotlin.math.abs

class AaRecentTaskFragment: BaseFragment<FragmentAaRecentTaskBinding>(FragmentAaRecentTaskBinding::class.java){
    companion object {
        const val TAG = "AADisplay_AaRecentTaskFragment"
    }

    override fun initViews() {
        val hide = { AaDisplayActivityKt.hideRecentTask(parentFragmentManager) }
        val phoneAdapter = DisplayRecyclerViewAdapter(baseBinding.rvRecentTaskRight, onExit = hide)
        val primaryAdapter = DisplayRecyclerViewAdapter(baseBinding.rvRecentTaskLeft, SplitPane.PRIMARY, hide)
        val secondaryAdapter = DisplayRecyclerViewAdapter(baseBinding.rvRecentTaskCenter, SplitPane.SECONDARY, hide)
        phoneAdapter.primaryAdapter = primaryAdapter
        phoneAdapter.secondaryAdapter = secondaryAdapter
        primaryAdapter.phoneAdapter = phoneAdapter
        primaryAdapter.secondaryAdapter = secondaryAdapter
        secondaryAdapter.phoneAdapter = phoneAdapter
        secondaryAdapter.primaryAdapter = primaryAdapter

        baseBinding.rvRecentTaskLeft.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = primaryAdapter
        }
        baseBinding.rvRecentTaskCenter.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = secondaryAdapter
        }
        baseBinding.rvRecentTaskRight.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = phoneAdapter
        }

        arrayOf(
            baseBinding.rvRecentTaskLeft,
            baseBinding.rvRecentTaskCenter,
            baseBinding.rvRecentTaskRight,
        ).forEach {
            it.setOnTouchListener { v, event ->
                when(event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.setTag(R.id.drag_last_x, event.x)
                        v.setTag(R.id.drag_last_y, event.y)
                        when (v.id) {
                            R.id.rv_recent_task_left -> CoreApi.setFocusedPane(SplitPane.PRIMARY)
                            R.id.rv_recent_task_center -> CoreApi.setFocusedPane(SplitPane.SECONDARY)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        // VD columns: tap selects move target; phone empty-tap dismisses.
                        if (v.id == R.id.rv_recent_task_right
                            && abs((v.getTag(R.id.drag_last_x) as? Float ?: 0f) - event.x) <= 5
                            && abs((v.getTag(R.id.drag_last_y) as? Float ?: 0f) - event.y) <= 5) {
                            AaDisplayActivityKt.hideRecentTask(parentFragmentManager)
                        }
                    }
                }
                return@setOnTouchListener false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        runIO {
            CoreApi.recentTask?.also { recentTask ->
                runMain {
                    (baseBinding.rvRecentTaskLeft.adapter as DisplayRecyclerViewAdapter)
                        .setItems(recentTask.primaryDisplay)
                    (baseBinding.rvRecentTaskCenter.adapter as DisplayRecyclerViewAdapter)
                        .setItems(recentTask.secondaryDisplay)
                    (baseBinding.rvRecentTaskRight.adapter as DisplayRecyclerViewAdapter)
                        .setItems(recentTask.mainDisplay)
                }
            }
        }
    }
}
