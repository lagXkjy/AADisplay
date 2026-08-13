package io.github.nitsuya.aa.display.ui.aa.fragment

import io.github.duzhaokun123.template.bases.BaseFragment
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.databinding.FragmentAaRecentTaskBinding
import io.github.nitsuya.aa.display.ui.aa.AaDisplayActivityKt
import io.github.nitsuya.aa.display.ui.aa.recent.RecentTaskAdapters
import io.github.nitsuya.aa.display.ui.aa.recent.RecentTaskColumns
import io.github.duzhaokun123.template.utils.runIO
import io.github.duzhaokun123.template.utils.runMain

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
