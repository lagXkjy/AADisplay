package io.github.nitsuya.aa.display.xposed.hook.aa

import android.graphics.Point
import android.graphics.Rect
import android.util.Size
import com.github.kyuubiran.ezxhelper.utils.loadClass
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Constructor

/**
 * Optional Android Auto DPI override. Prefs-driven DPI was removed; DexKit resolution is
 * retained for future use, but display params are not rewritten.
 */
object AaDpiHook : AaHook() {
    override val tagName: String = "AAD_AaDpiHook"

    private lateinit var displayParamsConstructor: Constructor<*>
    private lateinit var carDisplayConstructor: Constructor<*>

    override fun isSupportProcess(processName: String): Boolean {
        return processCar == processName
    }

    override fun loadDexClass(bridge: DexKitBridge, lpparam: XC_LoadPackage.LoadPackageParam) {
        val classes = bridge.findClass {
            searchPackages = listOf("")
            matcher {
                usingStrings {
                    add("DisplayParams(selectedIndex=", StringMatchType.StartsWith, false)
                }
            }
        }
        if (classes.isEmpty() || classes.size > 1) {
            throw NoSuchMethodException("AaDpiHook: not found DisplayParams class: ${classes.size}")
        }

        val displayParamsClass = loadClass(classes[0].name)
        displayParamsConstructor = displayParamsClass.declaredConstructors.firstOrNull { ctor ->
            val p = ctor.parameterTypes
            p.size >= 18 &&
                p[0] == Int::class.javaPrimitiveType &&
                p[1] == Int::class.javaPrimitiveType &&
                p[2] == Int::class.javaPrimitiveType &&
                p[3] == Int::class.javaPrimitiveType &&
                p[4] == Int::class.javaPrimitiveType &&
                p[5] == Int::class.javaPrimitiveType &&
                p[6] == Int::class.javaPrimitiveType &&
                p[7] == Int::class.javaPrimitiveType &&
                p[8] == Int::class.javaPrimitiveType &&
                p[9] == Float::class.javaPrimitiveType &&
                p[10] == Int::class.javaPrimitiveType &&
                p[11] == Float::class.javaPrimitiveType &&
                p[12] == Size::class.java &&
                p[13] == Rect::class.java &&
                p[14] == Rect::class.java &&
                List::class.java.isAssignableFrom(p[15]) &&
                p[16].name == "com.google.android.gms.car.display.CarDisplayUiFeatures" &&
                p.last() == Int::class.javaPrimitiveType
        } ?: throw NoSuchMethodException("AaDpiHook: not found compatible DisplayParams constructor")
        displayParamsConstructor.isAccessible = true

        val carDisplayClass = loadClass("com.google.android.gms.car.display.CarDisplay")
        carDisplayConstructor = carDisplayClass.declaredConstructors.firstOrNull { ctor ->
            val p = ctor.parameterTypes
            (p.size == 9 || p.size == 10) &&
                p[0].name == "com.google.android.gms.car.display.CarDisplayId" &&
                p[1] == Int::class.javaPrimitiveType &&
                p[2] == Int::class.javaPrimitiveType &&
                p[3] == Point::class.java &&
                p[4] == Rect::class.java &&
                p[5] == Rect::class.java &&
                List::class.java.isAssignableFrom(p[6]) &&
                p[7] == Int::class.javaPrimitiveType &&
                p[8] == String::class.java
        } ?: throw NoSuchMethodException("AaDpiHook: not found compatible CarDisplay constructor")
        carDisplayConstructor.isAccessible = true
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        // No AndroidAutoDpi override — use gearhead defaults.
    }
}
