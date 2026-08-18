package io.github.duzhaokun123.template.bases

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import io.github.duzhaokun123.template.utils.inflateBinding

abstract class BaseFragment<BaseBinding : ViewBinding>(private val baseBindingClass: Class<BaseBinding>) : Fragment() {
    lateinit var baseBinding: BaseBinding
        private set

    /**
     * Subclasses must use this instead of `::baseBinding.isInitialized`.
     * The property-reference form generates a cross-class synthetic accessor that
     * R8 can turn into IllegalAccessError after renaming this base (private set).
     */
    protected fun isBaseBindingInitialized(): Boolean = ::baseBinding.isInitialized

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        baseBinding = inflateBinding(layoutInflater, baseBindingClass)
        initViews()
        return baseBinding.root
    }

    open fun initViews() {}
}
