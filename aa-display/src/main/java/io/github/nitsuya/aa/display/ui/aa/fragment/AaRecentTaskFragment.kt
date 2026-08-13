package io.github.nitsuya.aa.display.ui.aa.fragment

import io.github.duzhaokun123.template.bases.BaseFragment
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.databinding.FragmentAaRecentTaskBinding
import io.github.nitsuya.aa.display.ui.aa.AaDisplayActivityKt
import io.github.nitsuya.aa.display.ui.window.DisplayRecyclerViewAdapter
import io.github.nitsuya.aa.display.ui.window.RecentTaskUiHelper
import io.github.duzhaokun123.template.utils.runIO
import io.github.duzhaokun123.template.utils.runMain

class AaRecentTaskFragment: BaseFragment<FragmentAaRecentTaskBinding>(FragmentAaRecentTaskBinding::class.java){

    override fun initViews() {
        val hide = { AaDisplayActivityKt.hideRecentTask(parentFragmentManager) }
        RecentTaskUiHelper.wireThreeColumnRecents(
            left = baseBinding.rvRecentTaskLeft,
            center = baseBinding.rvRecentTaskCenter,
            right = baseBinding.rvRecentTaskRight,
            onExit = hide,
            focusedPaneProvider = { CoreApi.focusedPane },
            setFocusedPane = { CoreApi.setFocusedPane(it) },
        )
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
