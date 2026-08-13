package io.github.duzhaokun123.template.utils

import android.content.res.Resources
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
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

fun Resources.Theme.getAttr(@AttrRes id: Int) =
    TypedValue().apply { resolveAttribute(id, this, true) }
