package io.github.duzhaokun123.template.utils

import android.view.LayoutInflater
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

val WindowInsetsCompat.maxSystemBarsDisplayCutout: Insets
    get() = getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())

fun runMain(block: suspend CoroutineScope.() -> Unit) =
    GlobalScope.launch(Dispatchers.Main, block = block)

fun runIO(block: suspend CoroutineScope.() -> Unit) =
    GlobalScope.launch(Dispatchers.IO, block = block)

@Suppress("UNCHECKED_CAST")
fun <T : ViewBinding> inflateBinding(inflater: LayoutInflater, clazz: Class<T>): T {
    val method = clazz.getMethod("inflate", LayoutInflater::class.java)
    return method.invoke(null, inflater) as T
}
