package io.github.nitsuya.aa.display.xposed.util

import android.os.Build
import android.os.SystemProperties

object RomUtil {
    private const val KEY_VERSION_MIUI = "ro.miui.ui.version.name"

    fun isMiui(): Boolean = SystemProperties.get(KEY_VERSION_MIUI).isNotBlank()

    fun isSamsung(): Boolean {
        return Build.MANUFACTURER.equals("samsung", ignoreCase = true) ||
            SystemProperties.get("ro.product.manufacturer").equals("samsung", ignoreCase = true)
    }

    fun isSamsungAndroid16Plus(): Boolean {
        return isSamsung() && Build.VERSION.SDK_INT >= 36
    }
}
