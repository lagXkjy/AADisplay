package io.github.nitsuya.aa.display.ui.aa.fragment

import android.content.Intent
import io.github.duzhaokun123.template.bases.BaseFragment
import io.github.duzhaokun123.template.utils.runIO
import io.github.duzhaokun123.template.utils.runMain
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.databinding.FragmentAaRecentTaskBinding
import io.github.nitsuya.aa.display.ui.aa.AaDisplayActivityKt
import io.github.nitsuya.aa.display.ui.aa.recent.RecentTaskAdapters
import io.github.nitsuya.aa.display.ui.aa.recent.RecentTaskColumns
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.util.AABroadcastConst

class AaRecentTaskFragment: BaseFragment<FragmentAaRecentTaskBinding>(FragmentAaRecentTaskBinding::class.java){

    private var adapters: RecentTaskAdapters? = null

    override fun initViews() {
        val hide = { AaDisplayActivityKt.hideRecentTask(parentFragmentManager) }
        adapters = RecentTaskColumns.wireThreeColumnRecents(
            left = baseBinding.rvRecentTaskLeft,
            center = baseBinding.rvRecentTaskCenter,
            right = baseBinding.rvRecentTaskRight,
            onExit = hide,
            focusedPaneProvider = { CoreApi.focusedPane },
            setFocusedPane = { CoreApi.setFocusedPane(it) },
        )
        baseBinding.btnAddAppLeft.setOnClickListener {
            hide()
            openPicker(SplitPane.PRIMARY)
        }
        baseBinding.btnAddAppCenter.setOnClickListener {
            hide()
            openPicker(SplitPane.SECONDARY)
        }
    }

    private fun openPicker(pane: Int) {
        try {
            requireContext().sendBroadcast(
                Intent(AABroadcastConst.ACTION_OPEN_SPLIT_PICKER).apply {
                    putExtra(AABroadcastConst.EXTRA_PANE, pane)
                    putExtra(AABroadcastConst.EXTRA_KEEP_OCCUPANCY, true)
                }
            )
        } catch (_: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
        runIO {
            CoreApi.recentTask?.also { recentTask ->
                runMain {
                    val cols = adapters ?: return@runMain
                    cols.primary.setItems(recentTask.primaryDisplay)
                    cols.secondary.setItems(recentTask.secondaryDisplay)
                    cols.phone.setItems(recentTask.mainDisplay)
                }
            }
        }
    }
}
