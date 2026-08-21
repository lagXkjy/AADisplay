package io.github.nitsuya.aa.display.util

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local LRU for app icons / recent-task bitmaps / activity labels.
 * Capacity is bounded; invalidate on package broadcasts via [invalidateAll].
 */
object PmIconCache {
    private const val DRAWABLE_CAPACITY = 96
    private const val BITMAP_CAPACITY = 48
    private const val LABEL_CAPACITY = 96

    private val drawableLock = Any()
    private val bitmapLock = Any()

    private val drawables = object : LruCache<String, Drawable>(DRAWABLE_CAPACITY) {}
    private val bitmaps = object : LruCache<String, Bitmap>(BITMAP_CAPACITY) {}
    private val labels = ConcurrentHashMap<String, String>()

    fun getDrawable(packageName: String): Drawable? {
        synchronized(drawableLock) {
            return drawables.get(packageName)
        }
    }

    fun putDrawable(packageName: String, icon: Drawable) {
        synchronized(drawableLock) {
            drawables.put(packageName, icon)
        }
    }

    fun getOrLoadDrawable(pm: PackageManager, packageName: String): Drawable? {
        getDrawable(packageName)?.let { return it }
        val icon = try {
            pm.getApplicationIcon(packageName)
        } catch (_: Throwable) {
            null
        } ?: return null
        putDrawable(packageName, icon)
        return icon
    }

    fun getBitmap(key: String): Bitmap? {
        synchronized(bitmapLock) {
            return bitmaps.get(key)
        }
    }

    fun putBitmap(key: String, bitmap: Bitmap) {
        synchronized(bitmapLock) {
            bitmaps.put(key, bitmap)
        }
    }

    fun getLabel(key: String): String? = labels[key]

    fun putLabel(key: String, label: String) {
        if (labels.size >= LABEL_CAPACITY) {
            labels.clear()
        }
        labels[key] = label
    }

    fun invalidate(packageName: String? = null) {
        if (packageName.isNullOrBlank()) {
            invalidateAll()
            return
        }
        val pkg = packageName.trim()
        synchronized(drawableLock) {
            drawables.remove(pkg)
        }
        synchronized(bitmapLock) {
            val keys = bitmaps.snapshot().keys.filter {
                it == pkg || it.startsWith("$pkg/") || it.startsWith("$pkg|")
            }
            keys.forEach { bitmaps.remove(it) }
        }
        labels.keys.filter { it == pkg || it.startsWith("$pkg/") || it.startsWith("$pkg|") }
            .forEach { labels.remove(it) }
    }

    fun invalidateAll() {
        synchronized(drawableLock) {
            drawables.evictAll()
        }
        synchronized(bitmapLock) {
            bitmaps.evictAll()
        }
        labels.clear()
    }
}
