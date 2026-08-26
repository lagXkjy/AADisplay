package io.github.nitsuya.aa.display.ui.aa

import android.content.Intent
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.add
import androidx.fragment.app.commit
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.ui.aa.fragment.AaMainFragment
import io.github.nitsuya.aa.display.ui.aa.fragment.AaRecentTaskFragment
import io.github.nitsuya.aa.display.util.AABroadcastConst

object AaDisplayActivityKt {

    fun pressKey(action: Int){
        CoreApi.pressKey(action)
    }

    fun showMain(fragmentManager: FragmentManager){
        fragmentManager.commit {
            setReorderingAllowed(true)
            add<AaMainFragment>(R.id.fragment_container_view)
        }
    }

    fun showRecentTask(fragmentManager: FragmentManager){
        val fragment = fragmentManager.findFragmentByTag("RecentTask")
        if(fragment == null){
            fragmentManager.commit {
                setReorderingAllowed(true)
                add<AaRecentTaskFragment>(R.id.fragment_container_view, "RecentTask")
            }
            // BT mouse + Coolwalk rail must hit the Recents overlay on the shell.
            setAaUiShellCapture(fragmentManager, true)
        } else {
            hideRecentTask(fragmentManager)
        }
    }

    fun hideRecentTask(fragmentManager: FragmentManager){
        val fragment = fragmentManager.findFragmentByTag("RecentTask") ?: return
        fragmentManager.commit {
            setReorderingAllowed(true)
            remove(fragment)
        }
        setAaUiShellCapture(fragmentManager, false)
    }

    /** Same flag as app picker: rail / HID → AaDisplay presentation. */
    private fun setAaUiShellCapture(fragmentManager: FragmentManager, capture: Boolean) {
        val ctx = fragmentManager.fragments.firstOrNull()?.context ?: return
        try {
            ctx.sendBroadcast(
                Intent(AABroadcastConst.ACTION_AA_UI_RAIL_CONSUME).putExtra(
                    AABroadcastConst.EXTRA_AA_UI_RAIL_CONSUME,
                    capture,
                )
            )
        } catch (_: Throwable) {
        }
    }

}