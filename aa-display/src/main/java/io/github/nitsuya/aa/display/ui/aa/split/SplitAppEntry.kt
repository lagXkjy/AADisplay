package io.github.nitsuya.aa.display.ui.aa.split

import android.graphics.drawable.Drawable

/** Launchable app row for Host / picker overlays. */
data class SplitAppEntry(
    val packageName: String,
    val label: String,
    @Volatile var icon: Drawable? = null,
)
