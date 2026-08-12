package io.github.nitsuya.aa.display.xposed.util

import android.os.SystemProperties

object RomUtil {
    private const val KEY_VERSION_MIUI = "ro.miui.ui.version.name"

    fun isMiui(): Boolean = SystemProperties.get(KEY_VERSION_MIUI).isNotBlank()
}
