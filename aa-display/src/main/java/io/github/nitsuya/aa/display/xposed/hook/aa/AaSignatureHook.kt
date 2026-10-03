package io.github.nitsuya.aa.display.xposed.hook.aa

import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object AaSignatureHook: AaHook() {
    override val tagName: String = "AAD_AaSignatureHook"

    private lateinit var method: Method

    override fun isSupportProcess(processName: String): Boolean {
        return processCar == processName
    }

    override fun loadDexClass(bridge: DexKitBridge, lpparam: XC_LoadPackage.LoadPackageParam) {
        val methodMatcher = MethodMatcher().apply{
            modifiers = Modifier.PUBLIC or Modifier.FINAL
            returnType = "boolean"
            paramTypes("java.lang.String")
        }
        val classes = bridge.findClass {
            matcher {
                usingStrings {
                    add(
                        "Package has more than one signature.",
                        StringMatchType.Equals,
                        false
                    )
                }
                methods {
                    add(methodMatcher)
                }
            }
        }
        if (classes.isEmpty() || classes.size > 1) {
            throw NoSuchMethodException("AaSignatureHook: not found SignatureVerifierUtil class：${classes.size}")
        }

        val methodDatas = classes.findMethod {
            matcher(methodMatcher)
        }
        if (methodDatas.isEmpty() || methodDatas.size > 1) {
            throw NoSuchMethodException("AaSignatureHook: not found Check method：${methodDatas.size}")
        }
        method = methodDatas.single().getMethodInstance(lpparam.classLoader)
    }
    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        method.hookAfter { param ->
            if((param.args[0] as String) == BuildConfig.APPLICATION_ID){
                param.result = true
            }
        }
    }
}
