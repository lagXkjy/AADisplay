package io.github.nitsuya.aa.display.xposed.hook.aa

import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.hook.DexKitMethodCache
import io.github.nitsuya.aa.display.xposed.util.log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * FRX / Car Setup treats Google App, Maps, and TTS as required installs.
 * Force those three packages to report ready so setup can proceed without installing them.
 */
object AaFrxRequiredAppsHook : AaHook() {
    override val tagName: String = "AAD_AaFrxRequiredAppsHook"
    override val usesDexKit: Boolean = true

    private const val CACHE_STATUS = "hook.AaFrxRequiredAppsHook.status"
    private const val STATUS_READY = 1

    private val bypassPackages = setOf(
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.maps",
        "com.google.android.tts",
    )

    private val loggedBypass = ConcurrentHashMap.newKeySet<String>()

    private lateinit var statusMethods: List<Method>

    override fun isSupportProcess(processName: String): Boolean {
        return processProjection == processName || processCar == processName
    }

    override fun applyCache(
        cache: DexKitMethodCache.Session,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ): Boolean {
        val refs = cache.getRefs(CACHE_STATUS) ?: return false
        if (refs.isEmpty()) return false
        statusMethods = cache.resolveAll(lpparam.classLoader, refs) ?: return false
        log(tagName, "status methods=${statusMethods.size} (cache)")
        return true
    }

    override fun saveCache(
        cache: DexKitMethodCache.Session,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ) {
        if (::statusMethods.isInitialized) {
            cache.putRefs(CACHE_STATUS, statusMethods)
        }
    }

    override fun loadDexClass(bridge: DexKitBridge, lpparam: XC_LoadPackage.LoadPackageParam) {
        val contextStatusMatcher = MethodMatcher().apply {
            returnType = "int"
            paramTypes("android.content.Context")
        }

        val lecStatus = bridge.findMethod {
            matcher {
                returnType = "int"
                paramTypes("android.content.Context")
                usingStrings {
                    add(
                        "Package %s: installed ver=%d minimum required ver=%d",
                        StringMatchType.Equals,
                        false,
                    )
                }
            }
        }.mapNotNull { md ->
            runCatching { md.getMethodInstance(lpparam.classLoader) }.getOrNull()
        }

        val scqStatus = bridge.findClass {
            matcher {
                usingStrings {
                    add("isRequired=", StringMatchType.Contains, false)
                }
            }
        }.flatMap { classData ->
            classData.findMethod(FindMethod().matcher(contextStatusMatcher))
        }.mapNotNull { md ->
            runCatching { md.getMethodInstance(lpparam.classLoader) }.getOrNull()
        }

        statusMethods = (lecStatus + scqStatus)
            .distinctBy { "${it.declaringClass.name}#${it.name}" }
            .also { list ->
                if (list.isEmpty()) {
                    throw NoSuchMethodException("AaFrxRequiredAppsHook: no FRX package-status methods")
                }
                log(tagName, "status methods=${list.size}")
            }
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        statusMethods.forEach { method ->
            method.hookAfter { param ->
                val pkg = packageNameOf(param.thisObject) ?: return@hookAfter
                if (pkg !in bypassPackages) return@hookAfter
                if (param.result as? Int == STATUS_READY) return@hookAfter
                param.result = STATUS_READY
                if (loggedBypass.add(pkg)) {
                    log(tagName, "bypass FRX required-app check for $pkg")
                }
            }
        }
    }

    private fun packageNameOf(instance: Any?): String? {
        if (instance == null) return null
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                if (field.type != String::class.java) continue
                if (Modifier.isStatic(field.modifiers)) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(instance) as? String
                }.getOrNull() ?: continue
                if (value in bypassPackages) return value
                // Phenotype may store "pkg:minVersion"
                val bare = value.substringBefore(':')
                if (bare in bypassPackages) return bare
            }
            clazz = clazz.superclass
        }
        return null
    }
}
