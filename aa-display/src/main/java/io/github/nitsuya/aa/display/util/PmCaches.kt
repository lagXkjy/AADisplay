package io.github.nitsuya.aa.display.util

/**
 * Clears process-local PM resolve/icon caches after package installs/updates/removals.
 */
object PmCaches {
    fun invalidateAll() {
        PmResolveCache.invalidateAll()
        PmIconCache.invalidateAll()
        MusicAppClassifier.invalidate()
    }

    fun invalidatePackage(packageName: String?) {
        PmResolveCache.invalidate(packageName)
        PmIconCache.invalidate(packageName)
        MusicAppClassifier.invalidate(packageName)
    }
}
