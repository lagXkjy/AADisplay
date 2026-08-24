package io.github.nitsuya.aa.display.xposed.hook.aa

import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.hook.DexKitMethodCache
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.AaCoolwalkAutoOpenHook
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.AaCoolwalkCompositorHook
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.AaCoolwalkHuTouchHook
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.AaCoolwalkLayoutHook
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.AaCoolwalkProjectionHook
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.CoolwalkDrawingSpecWiden
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.CoolwalkFacetBarSurfaceHook
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.CoolwalkFacetChrome
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.CoolwalkHookEnv
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.CoolwalkProjectionBoundsHook
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

object AaUiHook : AaHook() {
    override val tagName: String = CoolwalkHookEnv.TAG
    override val usesDexKit: Boolean = true

    private const val CACHE_CONTENT_BOUNDS = "hook.AaUiHook.content_bounds"
    private const val CACHE_FACET_SURFACE = "hook.AaUiHook.facet_surface"
    private const val CACHE_HU_TOUCH = "hook.AaUiHook.hu_touch"
    private const val CACHE_LAYOUT_INFO = "hook.AaUiHook.layout_info_class"
    private const val CACHE_PROJECTION_BOUNDS = "hook.AaUiHook.projection_bounds_class"

    private val env = CoolwalkHookEnv()

    override fun isSupportProcess(processName: String): Boolean {
        return processProjection == processName || processCar == processName
    }

    override fun applyCache(
        cache: DexKitMethodCache.Session,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ): Boolean {
        val contentRefs = cache.getRefs(CACHE_CONTENT_BOUNDS) ?: return false
        env.contentBoundsMethods = cache.resolveAll(lpparam.classLoader, contentRefs) ?: return false
        env.facetBarSurfaceMethods = cache.getRefs(CACHE_FACET_SURFACE)?.let { refs ->
            cache.resolveAll(lpparam.classLoader, refs)
        } ?: return false
        if (env.facetBarSurfaceMethods.isEmpty()) return false
        if (!cache.hasKey(CACHE_HU_TOUCH)) return false
        env.huTouchDispatchMethod = cache.getRef(CACHE_HU_TOUCH)?.let { ref ->
            cache.resolve(lpparam.classLoader, ref) ?: return false
        }
        log(
            tagName,
            "AaUiHook: content_bounds methods=${env.contentBoundsMethods.size} (cache) " +
                env.contentBoundsMethods.map { "${it.declaringClass.name}#${it.name}" },
        )
        log(
            tagName,
            "AaUiHook: HU touch dispatch method=" +
                (env.huTouchDispatchMethod?.let { "${it.declaringClass.name}#${it.name}" } ?: "null") +
                " (cache)",
        )
        env.startMethod = env.resolveCarStartActivityMethod()
        env.projectionBoundsClassName = cache.getString(CACHE_PROJECTION_BOUNDS)
        // New key: miss once so DexKit finds `{blX=` compositor bounds class.
        if (env.projectionBoundsClassName.isNullOrEmpty()) return false
        if (lpparam.processName == processCar) {
            env.loadProjectionResources()
            return true
        }
        val layoutInfoClassName = cache.getString(CACHE_LAYOUT_INFO) ?: return false
        env.layoutInfoConstructors = runCatching {
            env.resolveLayoutInfoConstructors(layoutInfoClassName)
        }.onFailure { e ->
            log(tagName, "AaUiHook: LayoutInfo cache resolve failed", e)
        }.getOrNull() ?: return false
        log(
            tagName,
            "AaUiHook: LayoutInfo ctors=${env.layoutInfoConstructors.size} (cache) " +
                env.layoutInfoConstructors.joinToString { "p${it.parameterCount}" },
        )
        env.loadProjectionResources()
        return true
    }

    override fun saveCache(
        cache: DexKitMethodCache.Session,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ) {
        cache.putRefs(CACHE_CONTENT_BOUNDS, env.contentBoundsMethods)
        cache.putRefs(CACHE_FACET_SURFACE, env.facetBarSurfaceMethods)
        cache.putRef(CACHE_HU_TOUCH, env.huTouchDispatchMethod)
        if (lpparam.processName != processCar && env.layoutInfoConstructors.isNotEmpty()) {
            cache.putString(CACHE_LAYOUT_INFO, env.layoutInfoConstructors[0].declaringClass.name)
        }
        env.projectionBoundsClassName?.let { cache.putString(CACHE_PROJECTION_BOUNDS, it) }
    }

    override fun loadDexClass(bridge: DexKitBridge, lpparam: XC_LoadPackage.LoadPackageParam) {
        env.contentBoundsMethods = try {
            val byKey = bridge.findMethod {
                matcher { usingStrings("content_bounds") }
            }
            val byLog = bridge.findMethod {
                matcher {
                    usingStrings {
                        add("onProjectionStart updated config", StringMatchType.StartsWith, false)
                    }
                }
            }
            (byKey + byLog).mapNotNull { md ->
                runCatching { md.getMethodInstance(lpparam.classLoader) }.getOrNull()
            }.distinctBy { "${it.declaringClass.name}#${it.name}#${it.parameterTypes.joinToString { p -> p.name }}" }
                .also { list ->
                    log(
                        tagName,
                        "AaUiHook: content_bounds methods=${list.size} " +
                            list.map { "${it.declaringClass.name}#${it.name}" },
                    )
                }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: DexKit content_bounds methods failed", e)
            emptyList()
        }

        env.huTouchDispatchMethod = try {
            bridge.findMethod {
                matcher {
                    usingStrings("UpDown touch event (%s,%s) does not correspond to a window for %s")
                }
            }.mapNotNull { md ->
                runCatching { md.getMethodInstance(lpparam.classLoader) }.getOrNull()
            }.firstOrNull { m ->
                m.parameterTypes.size == 2 && m.returnType == Void.TYPE
            }.also { m ->
                log(
                    tagName,
                    "AaUiHook: HU touch dispatch method=" +
                        (m?.let { "${it.declaringClass.name}#${it.name}" } ?: "null"),
                )
            }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: DexKit HU touch dispatch failed", e)
            null
        }

        env.facetBarSurfaceMethods = try {
            val byWidth = bridge.findMethod {
                matcher { usingStrings("onWindowSurfaceAvailable width:") }
            }
            val byDims = bridge.findMethod {
                matcher { usingStrings("onWindowSurfaceAvailable dimensions:") }
            }
            (byWidth + byDims).mapNotNull { md ->
                runCatching { md.getMethodInstance(lpparam.classLoader) }.getOrNull()
            }.distinctBy { "${it.declaringClass.name}#${it.name}#${it.parameterTypes.joinToString { p -> p.name }}" }
                .also { list ->
                    log(
                        tagName,
                        "AaUiHook: facet-bar surface methods=${list.size} " +
                            list.map { "${it.declaringClass.name}#${it.name}" },
                    )
                }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: DexKit facet-bar surface methods failed", e)
            emptyList()
        }

        env.projectionBoundsClassName = try {
            val classes = bridge.findClass {
                matcher { usingStrings("{blX=") }
            }
            classes.mapNotNull { runCatching { it.name }.getOrNull() }
                .firstOrNull()
                .also { name ->
                    log(tagName, "AaUiHook: projection bounds class=${name ?: "null"}")
                }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: DexKit projection bounds class failed", e)
            null
        }
        CoolwalkProjectionBoundsHook.rememberClassName(env.projectionBoundsClassName)

        env.startMethod = env.resolveCarStartActivityMethod()

        if (lpparam.processName == processCar) {
            env.loadProjectionResources()
            return
        }

        val classes = bridge.findClass {
            matcher {
                usingStrings {
                    add("LayoutInfo{layoutResourceId=", StringMatchType.StartsWith, false)
                }
            }
        }
        if (classes.isEmpty() || classes.size > 1) {
            throw NoSuchMethodException("AaUiHook: not found LayoutInfo class：${classes.size}")
        }
        env.layoutInfoConstructors = env.resolveLayoutInfoConstructors(classes[0].name)
        log(
            tagName,
            "AaUiHook: LayoutInfo ctors=${env.layoutInfoConstructors.size} " +
                env.layoutInfoConstructors.joinToString { "p${it.parameterCount}" },
        )
        env.loadProjectionResources()
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        env.loadProjectionResources()
        CoolwalkDrawingSpecWiden.installGearhead(lpparam.classLoader)
        CoolwalkProjectionBoundsHook.install(
            lpparam.classLoader,
            env.projectionBoundsClassName ?: CoolwalkProjectionBoundsHook.cachedClassName(),
        )
        CoolwalkFacetBarSurfaceHook.install(env)
        AaCoolwalkProjectionHook.install(env)
        if (lpparam.processName == processCar) {
            AaCoolwalkLayoutHook.installRailWidthDimens(env)
            AaCoolwalkCompositorHook.install(env)
            if (env.canHookFacetBar) {
                CoolwalkFacetChrome.install(env)
            }
            AaCoolwalkHuTouchHook.install(env, lpparam)
            logDebug(tagName, "AaUiHook: Coolwalk hooks installed (:car)")
            return
        }
        AaCoolwalkLayoutHook.install(env)
        AaCoolwalkCompositorHook.install(env)
        if (env.canHookFacetBar) {
            CoolwalkFacetChrome.install(env)
        }
        AaCoolwalkAutoOpenHook.install(env, lpparam)
        logDebug(tagName, "AaUiHook: Coolwalk hooks installed (:projection)")
    }
}
