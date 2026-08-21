package io.github.nitsuya.aa.display.util

import android.content.ComponentName
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local cache of launcher [ComponentName] per package.
 * Invalidate on PACKAGE_ADDED/REMOVED/CHANGED (see [SplitAppPickerController]).
 */
object PmResolveCache {
    private val byPackage = ConcurrentHashMap<String, ComponentName>()

    fun get(packageName: String): ComponentName? = byPackage[packageName]

    fun put(packageName: String, component: ComponentName) {
        byPackage[packageName] = component
    }

    fun invalidate(packageName: String? = null) {
        if (packageName.isNullOrBlank()) {
            byPackage.clear()
        } else {
            byPackage.remove(packageName.trim())
        }
    }

    fun invalidateAll() = byPackage.clear()
}
