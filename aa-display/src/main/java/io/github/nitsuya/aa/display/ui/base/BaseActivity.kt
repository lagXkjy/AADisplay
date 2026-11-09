package io.github.nitsuya.aa.display.ui.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentActivity
import androidx.viewbinding.ViewBinding

abstract class BaseActivity<BaseBinding : ViewBinding> : FragmentActivity() {

    lateinit var baseBinding: BaseBinding
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

        baseBinding = bind(layoutInflater)
        setContentView(baseBinding.root)
        // Edge-to-edge: pad ALL sides (not only top). ScrollView root otherwise measures
        // under the nav bar — content that "fits" the full window cannot scroll, while
        // longer CarPlay status text suddenly can (felt intermittent).
        ViewCompat.setOnApplyWindowInsetsListener(baseBinding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            v.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            insets
        }

        initData()
    }

    protected abstract fun bind(inflater: LayoutInflater): BaseBinding

    open fun initData() {}
}
