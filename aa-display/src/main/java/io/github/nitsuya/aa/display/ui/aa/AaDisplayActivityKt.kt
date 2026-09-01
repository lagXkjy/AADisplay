package io.github.nitsuya.aa.display.ui.aa

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.add
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
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
        if (fragment == null) {
            fragmentManager.commit {
                setReorderingAllowed(true)
                add<AaRecentTaskFragment>(R.id.fragment_container_view, "RecentTask")
            }
            setAaUiShellCapture(fragmentManager, true)
            setMainChromeBlocked(fragmentManager, true)
        } else {
            (fragment as? AaRecentTaskFragment)?.requestReload()
            setAaUiShellCapture(fragmentManager, true)
            setMainChromeBlocked(fragmentManager, true)
        }
    }

    fun hideRecentTask(fragmentManager: FragmentManager) {
        val fragment = fragmentManager.findFragmentByTag("RecentTask") ?: return
        val removeNow = Runnable {
            if (fragmentManager.findFragmentByTag("RecentTask") == null) return@Runnable
            if (!fragmentManager.isStateSaved) {
                fragmentManager.commitNow {
                    setReorderingAllowed(true)
                    remove(fragment)
                }
            } else {
                fragmentManager.commit {
                    setReorderingAllowed(true)
                    remove(fragment)
                }
            }
            setAaUiShellCapture(fragmentManager, false)
            setMainChromeBlocked(fragmentManager, false)
        }
        // commitNow during the touch/click that opened hide stalls the UI and can break picker taps.
        val posted = fragment.view?.post(removeNow) == true
        if (!posted) {
            Handler(Looper.getMainLooper()).post(removeNow)
        }
    }

    /** Back / Esc while Recents overlay is up should dismiss it, not inject into pane VDs. */
    fun consumeShellBackKey(fragmentManager: FragmentManager): Boolean {
        val recent = fragmentManager.findFragmentByTag("RecentTask") ?: return false
        if (!recent.isVisible) return false
        hideRecentTask(fragmentManager)
        return true
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

    private fun setMainChromeBlocked(fragmentManager: FragmentManager, blocked: Boolean) {
        fragmentManager.fragments
            .filterIsInstance<AaMainFragment>()
            .firstOrNull()
            ?.setOverlayBlocksChrome(blocked)
    }

    /** Locked peel preview from [CoreApi.getHidCursorOverlay] indices 4–5. */
    fun applyLockedPeelPreview(fragmentManager: FragmentManager, active: Boolean, ratio: Float) {
        fragmentManager.fragments
            .filterIsInstance<AaMainFragment>()
            .firstOrNull()
            ?.applyLockedPeelPreview(active, ratio)
    }

}
