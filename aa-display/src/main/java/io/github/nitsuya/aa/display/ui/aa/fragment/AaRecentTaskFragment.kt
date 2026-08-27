package io.github.nitsuya.aa.display.ui.aa.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import io.github.duzhaokun123.template.bases.BaseFragment
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.databinding.FragmentAaRecentTaskBinding
import io.github.nitsuya.aa.display.ui.aa.AaDisplayActivityKt
import io.github.nitsuya.aa.display.ui.aa.recent.RecentTaskColumns
import io.github.nitsuya.aa.display.ui.aa.recent.RecentTasksCoordinator
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.util.AABroadcastConst

class AaRecentTaskFragment :
    BaseFragment<FragmentAaRecentTaskBinding>(FragmentAaRecentTaskBinding::class.java) {

    private var coordinator: RecentTasksCoordinator? = null
    private var dirtyReceiverRegistered = false
    /** Skip onResume reload when [requestReload] already ran this visibility cycle. */
    private var skipResumeReloadOnce = false

    private val dirtyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            coordinator?.reload()
        }
    }

    override fun initViews() {
        val hide = { AaDisplayActivityKt.hideRecentTask(parentFragmentManager) }
        val coord = RecentTasksCoordinator(onExit = hide)
        val cols = RecentTaskColumns.wireThreeColumnRecents(
            left = baseBinding.rvRecentTaskLeft,
            center = baseBinding.rvRecentTaskCenter,
            right = baseBinding.rvRecentTaskRight,
            coordinator = coord,
            onExit = hide,
            focusedPaneProvider = { CoreApi.focusedPane },
            setFocusedPane = { CoreApi.setFocusedPane(it) },
        )
        coord.bind(cols)
        coordinator = coord
        baseBinding.btnAddAppLeft.setOnClickListener {
            openAppPickerAndClose(SplitPane.PRIMARY)
        }
        baseBinding.btnAddAppCenter.setOnClickListener {
            openAppPickerAndClose(SplitPane.SECONDARY)
        }
    }

    /**
     * Must hide Recents before showing picker (picker lives under Main). Do not post on this
     * fragment's view after [hide] — commitNow removes it and the broadcast/post never runs.
     */
    private fun openAppPickerAndClose(pane: Int) {
        val fm = parentFragmentManager
        val main = fm.fragments.filterIsInstance<AaMainFragment>().firstOrNull()
        // Hide Recents off the button callback, then show picker on Main's next layout pass.
        (view ?: baseBinding.root).post {
            AaDisplayActivityKt.hideRecentTask(fm)
            val host = main?.takeIf { it.isAdded }
            if (host != null) {
                host.view?.post { host.showAppPickerFromRecent(pane) }
                    ?: host.showAppPickerFromRecent(pane)
            } else {
                sendPickerBroadcastFallback(pane)
            }
        }
    }

    private fun sendPickerBroadcastFallback(pane: Int) {
        try {
            val ctx = activity?.applicationContext ?: return
            ctx.sendBroadcast(
                Intent(AABroadcastConst.ACTION_OPEN_SPLIT_PICKER).apply {
                    setPackage(BuildConfig.APPLICATION_ID)
                    putExtra(AABroadcastConst.EXTRA_PANE, pane)
                    putExtra(AABroadcastConst.EXTRA_KEEP_OCCUPANCY, true)
                },
            )
        } catch (_: Throwable) {
        }
    }

    fun requestReload() {
        skipResumeReloadOnce = true
        coordinator?.reloadImmediate()
    }

    override fun onResume() {
        super.onResume()
        registerDirtyReceiver()
        if (skipResumeReloadOnce) {
            skipResumeReloadOnce = false
        } else {
            coordinator?.reloadImmediate()
        }
        focusStackColumn()
    }

    /** BT keyboard opens Recents without a hover target — claim shell focus on the VD stack column. */
    private fun focusStackColumn() {
        if (!isBaseBindingInitialized()) return
        val rv = when (CoreApi.focusedPane) {
            SplitPane.SECONDARY -> baseBinding.rvRecentTaskCenter
            else -> baseBinding.rvRecentTaskLeft
        }
        baseBinding.root.isFocusableInTouchMode = true
        rv.isFocusable = true
        rv.isFocusableInTouchMode = true
        baseBinding.root.post { rv.requestFocus() }
    }

    override fun onPause() {
        unregisterDirtyReceiver()
        super.onPause()
    }

    private fun registerDirtyReceiver() {
        if (dirtyReceiverRegistered) return
        val filter = IntentFilter(AABroadcastConst.ACTION_RECENT_TASK_DIRTY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(dirtyReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            requireContext().registerReceiver(dirtyReceiver, filter)
        }
        dirtyReceiverRegistered = true
    }

    private fun unregisterDirtyReceiver() {
        if (!dirtyReceiverRegistered) return
        try {
            requireContext().unregisterReceiver(dirtyReceiver)
        } catch (_: Throwable) {
        }
        dirtyReceiverRegistered = false
    }
}
