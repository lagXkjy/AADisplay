package io.github.duzhaokun123.template.bases

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.updatePadding
import androidx.viewbinding.ViewBinding
import io.github.duzhaokun123.template.utils.inflateBinding
import io.github.duzhaokun123.template.utils.maxSystemBarsDisplayCutout

abstract class BaseActivity<BaseBinding : ViewBinding>(
    private val baseBindingClass: Class<BaseBinding>
) : AppCompatActivity() {

    lateinit var baseBinding: BaseBinding
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

        baseBinding = inflateBinding(layoutInflater, baseBindingClass)
        setContentView(baseBinding.root)
        ViewCompat.setOnApplyWindowInsetsListener(baseBinding.root) { v, insets ->
            v.updatePadding(top = insets.maxSystemBarsDisplayCutout.top)
            insets
        }

        initData()
    }

    open fun initData() {}
}
