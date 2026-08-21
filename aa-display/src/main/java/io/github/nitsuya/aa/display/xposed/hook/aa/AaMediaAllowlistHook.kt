package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.SharedPreferences
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.hook.DexKitMethodCache
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

/**
 * Allow AADisplay's invisible [io.github.nitsuya.aa.display.service.ClusterLyricMediaService]
 * to be treated as a valid AA media source without enabling developer "Unknown sources".
 *
 * Scope: [BuildConfig.APPLICATION_ID] only — never force-allow arbitrary sideload apps.
 */
object AaMediaAllowlistHook : AaHook() {
    override val tagName: String = "AAD_AaMediaAllowlistHook"
    override val usesDexKit: Boolean = true

    private const val CACHE_PKG_BOOL = "hook.AaMediaAllowlistHook.pkg_bool"

    private val selfPkg = BuildConfig.APPLICATION_ID

    /** Keys Gearhead uses for the developer Unknown-sources toggle (best-effort). */
    private val unknownSourcePrefKeys = setOf(
        "unknown_sources",
        "enable_unknown_sources",
        "unknown_sources_enabled",
        "pref_unknown_sources",
        "car_unknown_sources",
    )

    private var packageBoolMethods: List<Method> = emptyList()

    override fun isSupportProcess(processName: String): Boolean {
        return processProjection == processName || processCar == processName
    }

    override fun applyCache(
        cache: DexKitMethodCache.Session,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ): Boolean {
        val refs = cache.getRefs(CACHE_PKG_BOOL) ?: return false
        // Empty list is a valid "scan found nothing" cache — still apply pref hook.
        packageBoolMethods = if (refs.isEmpty()) {
            emptyList()
        } else {
            cache.resolveAll(lpparam.classLoader, refs) ?: return false
        }
        logDebug(tagName, "pkg-bool methods=${packageBoolMethods.size} (cache)")
        return true
    }

    override fun saveCache(
        cache: DexKitMethodCache.Session,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ) {
        cache.putRefs(CACHE_PKG_BOOL, packageBoolMethods)
    }

    override fun loadDexClass(bridge: DexKitBridge, lpparam: XC_LoadPackage.LoadPackageParam) {
        val needles = listOf(
            "unknown_sources",
            "Unknown sources",
            "unknown sources",
            "ENABLE_UNKNOWN_SOURCES",
            "not installed from the Play Store",
            "sideload",
            "third-party media",
        )
        val found = linkedMapOf<String, Method>()
        for (needle in needles) {
            runCatching {
                bridge.findMethod {
                    matcher {
                        returnType = "boolean"
                        usingStrings {
                            add(needle, StringMatchType.Contains, false)
                        }
                    }
                }.forEach { md ->
                    val method = runCatching { md.getMethodInstance(lpparam.classLoader) }.getOrNull()
                        ?: return@forEach
                    if (!isPackageNameBoolean(method)) return@forEach
                    val key =
                        "${method.declaringClass.name}#${method.name}#" +
                            method.parameterTypes.joinToString { it.name }
                    found.putIfAbsent(key, method)
                }
            }
        }
        packageBoolMethods = found.values.toList()
        logDebug(tagName, "pkg-bool methods=${packageBoolMethods.size} (live)")
        // Do not throw when empty — SharedPreferences fallback still helps discovery.
    }

    private fun isPackageNameBoolean(method: Method): Boolean {
        if (method.returnType != Boolean::class.javaPrimitiveType &&
            method.returnType != java.lang.Boolean::class.java
        ) {
            return false
        }
        val params = method.parameterTypes
        if (params.isEmpty()) return false
        // (String) or (Context, String) / (String, …) with a package-name String slot.
        return params.any { it == String::class.java }
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookPackageBoolMethods()
        // Pref force is global in gearhead — only use when pkg-bool DexKit found nothing.
        if (packageBoolMethods.isEmpty()) {
            hookUnknownSourcesPreference()
            log(tagName, "armed pkg=$selfPkg pkgBoolHooks=0 + unknown-sources pref fallback")
        } else {
            log(tagName, "armed pkg=$selfPkg pkgBoolHooks=${packageBoolMethods.size}")
        }
    }

    private fun hookPackageBoolMethods() {
        var hooked = 0
        for (method in packageBoolMethods) {
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!resultIsBlocking(param.result)) return
                        if (!argsContainSelfPkg(param.args)) return
                        param.result = true
                        logDebug(
                            tagName,
                            "allow ${method.declaringClass.simpleName}#${method.name} for $selfPkg",
                        )
                    }
                })
                hooked++
            } catch (e: Throwable) {
                log(tagName, "hook ${method.declaringClass.name}#${method.name} failed", e)
            }
        }
        logDebug(tagName, "hooked pkg-bool methods=$hooked/${packageBoolMethods.size}")
    }

    /**
     * Fallback only: force Unknown-sources pref true when pkg-bool methods were not found.
     * Prefer [hookPackageBoolMethods] which scopes allow to [selfPkg] only.
     */
    private fun hookUnknownSourcesPreference() {
        try {
            findMethod(SharedPreferences::class.java) {
                name == "getBoolean" &&
                    parameterCount == 2 &&
                    parameterTypes[0] == String::class.java &&
                    parameterTypes[1] == Boolean::class.javaPrimitiveType
            }.hookAfter { param ->
                val key = param.args[0] as? String ?: return@hookAfter
                if (!isUnknownSourcesKey(key)) return@hookAfter
                if (param.result == true) return@hookAfter
                param.result = true
                logDebug(tagName, "force pref $key=true (cluster media shell)")
            }
            logDebug(tagName, "hooked SharedPreferences.getBoolean for unknown-sources keys")
        } catch (e: Throwable) {
            log(tagName, "hook SharedPreferences.getBoolean failed", e)
        }
    }

    private fun isUnknownSourcesKey(key: String): Boolean {
        val normalized = key.lowercase().replace('-', '_')
        if (normalized in unknownSourcePrefKeys) return true
        return normalized.contains("unknown_source")
    }

    private fun argsContainSelfPkg(args: Array<Any?>): Boolean {
        for (arg in args) {
            if (arg is String && arg == selfPkg) return true
        }
        return false
    }

    /** False / null means the app would be rejected — we override to allow. */
    private fun resultIsBlocking(result: Any?): Boolean {
        return result == false || result == java.lang.Boolean.FALSE
    }
}
