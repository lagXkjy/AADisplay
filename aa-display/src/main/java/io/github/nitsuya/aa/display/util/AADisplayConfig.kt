package io.github.nitsuya.aa.display.util

import android.content.SharedPreferences
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import java.io.StringReader
import java.util.*

sealed class AADisplayConfig<T>(val key: String) {
    companion object {
        const val ConfigName = "aadisplay_config"
    }

    abstract fun get(config: SharedPreferences?): T

    object AutoOpen: BooleanConfig("AutoOpen", true)
    /** On new dual-VD session, restore the last custom split pair and ratio. */
    object RestoreLastSplit: BooleanConfig("RestoreLastSplit", true)
    /** Snapshot keys (also mirrored under /data/system/aadisplay_last_split.properties). */
    object LastSplitLeftPackage: StringConfig("LastSplitLeftPackage", null)
    object LastSplitRightPackage: StringConfig("LastSplitRightPackage", null)
    object LastSplitPrimaryRatio: StringConfig("LastSplitPrimaryRatio", null)
    object LastSplitDisplayLandscape: BooleanConfig("LastSplitDisplayLandscape", true)
    object DisableGoogleMapsOnAa: BooleanConfig("DisableGoogleMapsOnAa", true)
    object VirtualDisplayDpi: IntConfig("VirtualDisplayDpi", 0)
    object AndroidAutoDpi: IntConfig("AndroidAutoDpi", 0)
    object DelayDestroyTime: IntConfig("DelayDestroyTime", 180)
    object ScreenOffReplaceLockScreen: BooleanConfig("ScreenOffReplaceLockScreen", false)
    object ForceRightAngle: BooleanConfig("ForceRightAngle", true)
    object DisplayImePolicy: IntConfig("DisplayImePolicy", 0) //WindowManager.DISPLAY_IME_POLICY_LOCAL:0, WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY:1
    object VoiceAssistShell: StringConfig("VoiceAssistShell", null)
    object CreateVirtualDisplayBefore: ArrayStringConfig("CreateVirtualDisplayBefore")
    object DestroyVirtualDisplayAfter: ArrayStringConfig("DestroyVirtualDisplayAfter")
    object ComGoogleAndroidGmsCarProps: PropertiesConfig("ComGoogleAndroidGmsCarProps")
    object ComGoogleAndroidProjectionGearheadProps: PropertiesConfig("ComGoogleAndroidProjectionGearheadProps")


    abstract class StringConfig(key: String, private val defValue: String? = null): AADisplayConfig<String?>(key){
        override fun get(config: SharedPreferences?): String? = config?.getString(key, defValue)?.trim()?.let {
            it.ifBlank { defValue }
        } ?: defValue
    }
    abstract class BooleanConfig(key: String, private val defValue: Boolean = false): AADisplayConfig<Boolean>(key){
        override fun get(config: SharedPreferences?): Boolean {
            if (config == null) return defValue
            if (config.contains(key)) return config.getBoolean(key, defValue)
            // Device prefs may still carry the older AutoRestoreLastSplit key.
            if (key == "RestoreLastSplit" && config.contains("AutoRestoreLastSplit")) {
                return config.getBoolean("AutoRestoreLastSplit", defValue)
            }
            return defValue
        }
    }
    abstract class IntConfig(key: String, private val defValue: Int = 0): AADisplayConfig<Int>(key){
        private val defValueStr = defValue.toString()
        override fun get(config: SharedPreferences?): Int = tryOrNull { config?.getString(key, defValueStr)?.toInt() } ?: defValue
    }
    abstract class ArrayStringConfig(key: String): AADisplayConfig<Array<String>>(key){
        override fun get(config: SharedPreferences?): Array<String>{
            return config?.getString(key, null)?.let {
                it.split("\n")
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toTypedArray()
            } ?: emptyArray()
        }
    }
    abstract class PropertiesConfig(key: String): AADisplayConfig<Properties?>(key){
        override fun get(config: SharedPreferences?): Properties?{
            return config?.getString(key, null)?.let {
                Properties().apply {
                    load(StringReader(it))
                }
            } ?: null
        }
    }

}
