package io.github.duzhaokun123.template.bases

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import net.matsudamper.viewbindingutil.ViewBindingUtil

abstract class BaseFragment<BaseBinding : ViewBinding>(private val baseBindingClass: Class<BaseBinding>) : Fragment() {
    lateinit var baseBinding: BaseBinding
        private set

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        baseBinding = ViewBindingUtil.inflate(layoutInflater, baseBindingClass)
        initViews()
        return baseBinding.root
    }

    open fun initViews() {}
}
