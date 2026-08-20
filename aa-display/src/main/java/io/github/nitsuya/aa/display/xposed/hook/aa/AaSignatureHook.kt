package io.github.nitsuya.aa.display.xposed.hook.aa

import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.hook.DexKitMethodCache
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object AaSignatureHook: AaHook() {
    override val tagName: String = "AAD_AaSignatureHook"
    override val usesDexKit: Boolean = true

    private const val CACHE_METHOD = "hook.AaSignatureHook.method"

    private lateinit var method: Method

    override fun isSupportProcess(processName: String): Boolean {
        return processCar == processName
    }

    override fun applyCache(
        cache: DexKitMethodCache.Session,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ): Boolean {
        val ref = cache.getRef(CACHE_METHOD) ?: return false
        method = cache.resolve(lpparam.classLoader, ref) ?: return false
        return true
    }

    override fun saveCache(
        cache: DexKitMethodCache.Session,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ) {
        if (::method.isInitialized) {
            cache.putRef(CACHE_METHOD, method)
        }
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

        val methodDatas = classes[0].findMethod(FindMethod().matcher(methodMatcher))
        if (methodDatas.isEmpty() || methodDatas.size > 1) {
            throw NoSuchMethodException("AaSignatureHook: not found Check method：${classes.size}")
        }
        val methodData = methodDatas[0]
        method = findMethod(methodData.className) {
            name == methodData.methodName
            && parameterCount == 1
            && parameterTypes[0] == String::class.java
        }
    }
    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        method.hookAfter { param ->
            if((param.args[0] as String) == BuildConfig.APPLICATION_ID){
                param.result = true
            }
        }
    }
}
