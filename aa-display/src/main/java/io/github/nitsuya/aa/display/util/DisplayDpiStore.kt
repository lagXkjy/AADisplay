package io.github.nitsuya.aa.display.util

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/**
 * Manual virtual-display DPI override. Settings.Global is primary;
 * `/data/system/aadisplay_vd_density.properties` is best-effort fallback.
 *
 * `0` = auto (follow HU-reported density). Valid override: [MIN_DPI]..[MAX_DPI].
 */
object DisplayDpiStore {
    private const val TAG = "AADisplay_DisplayDpiStore"
    const val PATH = "/data/system/aadisplay_vd_density.properties"
    const val SETTINGS_DPI = "aadisplay_vd_density_dpi"
    private const val FILE_DPI = "VdDensityDpi"

    const val MIN_DPI = 120
    const val MAX_DPI = 640

    private val cacheLock = Any()

    /** In-memory authoritative copy when OEM rejects unknown Settings.Global keys. */
    @Volatile
    private var cachedDpi: Int? = null

    /** Returns configured override; `0` when auto/unset. */
    fun loadConfigured(contentResolver: ContentResolver? = null): Int {
        cachedDpi?.let { return it }
        synchronized(cacheLock) {
            cachedDpi?.let { return it }
            val loaded = loadFromSettings(contentResolver) ?: loadFromFile() ?: 0
            cachedDpi = loaded
            return loaded
        }
    }

    /**
     * Persist [dpi] (`0` = auto). Both backends are written when possible.
     * Returns false only when both backends fail.
     */
    fun save(dpi: Int, contentResolver: ContentResolver? = null): Boolean {
        val normalized = normalize(dpi)
        synchronized(cacheLock) {
            if (cachedDpi == normalized) return true
        }
        val settingsOk = if (normalized == 0) {
            clearSettings(contentResolver)
        } else {
            saveToSettings(normalized, contentResolver)
        }
        val fileOk = if (normalized == 0) {
            clearFile()
        } else {
            saveToFile(normalized)
        }
        if (!settingsOk) {
            Log.w(TAG, "settings save failed dpi=$normalized")
        }
        val ok = settingsOk || fileOk
        if (ok) {
            synchronized(cacheLock) {
                cachedDpi = normalized
            }
            Log.d(TAG, "saved dpi=$normalized settings=$settingsOk file=$fileOk")
        }
        return ok
    }

    /** Wipe durable override (Settings + file + cache). */
    fun clear(contentResolver: ContentResolver? = null): Boolean {
        synchronized(cacheLock) {
            cachedDpi = 0
        }
        val settingsOk = clearSettings(contentResolver)
        val fileOk = clearFile()
        if (settingsOk || fileOk) {
            Log.d(TAG, "cleared")
        }
        return settingsOk || fileOk
    }

    /** `0` = auto; otherwise clamp to [MIN_DPI]..[MAX_DPI]. */
    fun normalize(dpi: Int): Int {
        if (dpi <= 0) return 0
        return dpi.coerceIn(MIN_DPI, MAX_DPI)
    }

    fun isValidOverride(dpi: Int): Boolean = dpi in MIN_DPI..MAX_DPI

    private fun parseDpi(raw: String?): Int {
        val value = raw?.trim()?.toIntOrNull() ?: return 0
        return if (value <= 0) 0 else value.coerceIn(MIN_DPI, MAX_DPI)
    }

    private fun loadFromSettings(cr: ContentResolver?): Int? {
        if (cr == null) return null
        return try {
            val raw = Settings.Global.getString(cr, SETTINGS_DPI) ?: return 0
            parseDpi(raw)
        } catch (e: Throwable) {
            Log.w(TAG, "load settings failed", e)
            null
        }
    }

    private fun loadFromFile(): Int? {
        return try {
            parseDpi(readProps().getProperty(FILE_DPI))
        } catch (e: Throwable) {
            Log.w(TAG, "load file failed", e)
            null
        }
    }

    private fun readProps(): Properties {
        val props = Properties()
        val file = File(PATH)
        if (file.isFile && file.length() > 0L) {
            FileInputStream(file).use { props.load(it) }
        }
        return props
    }

    private fun updateFile(mutate: (Properties) -> Unit): Boolean {
        val file = File(PATH)
        @SuppressLint("SetWorldReadable")
        fun writeOnce(): Boolean {
            val props = readProps()
            mutate(props)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                props.store(out, "AADisplay virtual display DPI")
            }
            file.setReadable(true, false)
            file.setWritable(true, true)
            return true
        }
        return try {
            writeOnce()
        } catch (e: Throwable) {
            Log.w(TAG, "file update failed, retry after delete", e)
            try {
                if (file.exists() && !file.delete()) return false
                writeOnce()
            } catch (e2: Throwable) {
                Log.w(TAG, "file update failed", e2)
                false
            }
        }
    }

    private fun saveToSettings(dpi: Int, cr: ContentResolver?): Boolean {
        if (cr == null) return false
        return try {
            Settings.Global.putString(cr, SETTINGS_DPI, dpi.toString())
            true
        } catch (e: Throwable) {
            Log.w(TAG, "settings save failed", e)
            false
        }
    }

    private fun clearSettings(cr: ContentResolver?): Boolean {
        if (cr == null) return false
        return try {
            Settings.Global.putString(cr, SETTINGS_DPI, null)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "settings clear failed", e)
            false
        }
    }

    private fun saveToFile(dpi: Int): Boolean {
        return updateFile { props ->
            props.setProperty(FILE_DPI, dpi.toString())
        }
    }

    @SuppressLint("SetWorldReadable")
    private fun clearFile(): Boolean {
        return try {
            val props = readProps()
            props.remove(FILE_DPI)
            if (props.isEmpty) {
                val file = File(PATH)
                if (!file.exists()) return true
                return file.delete()
            }
            val file = File(PATH)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                props.store(out, "AADisplay virtual display DPI")
            }
            file.setReadable(true, false)
            file.setWritable(true, true)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "file clear failed", e)
            false
        }
    }
}
