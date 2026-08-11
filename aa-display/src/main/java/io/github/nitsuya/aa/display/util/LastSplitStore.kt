package io.github.nitsuya.aa.display.util

import android.content.ContentResolver
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/**
 * Durable OneUI split snapshot written from system_server ([AaVirtualDisplayAdapter]).
 * App prefs / XSharedPreferences are not writable from system_server; this file lives under
 * `/data/system` like the hook mirror. Also mirrored into [Settings.Global] for easy verification.
 *
 * Settings.Global is the source of truth for restore: a root-owned / unwritable properties file
 * (e.g. after `adb shell su` touch) would otherwise shadow forever and quick-restore keeps the
 * stale pair.
 */
object LastSplitStore {
    private const val TAG = "AADisplay_LastSplitStore"
    const val PATH = "/data/system/aadisplay_last_split.properties"

    const val SETTINGS_LEFT = "aadisplay_last_split_left"
    const val SETTINGS_RIGHT = "aadisplay_last_split_right"
    const val SETTINGS_RATIO = "aadisplay_last_split_ratio"
    const val SETTINGS_LANDSCAPE = "aadisplay_last_split_landscape"

    data class Snapshot(
        val leftPackage: String,
        val rightPackage: String,
        val primaryRatio: Float,
        val landscape: Boolean,
    )

    fun load(contentResolver: ContentResolver? = null): Snapshot? {
        // Prefer Settings — system_server can always update it; the file may be root-owned 0644.
        loadFromSettings(contentResolver)?.let { return it }
        return loadFromFile()
    }

    /**
     * Always mirrors [Settings.Global]. File write is best-effort (may fail if the path is
     * root-owned); restore reads Settings first.
     */
    fun save(
        snapshot: Snapshot,
        contentResolver: ContentResolver? = null,
        mirrorSettings: Boolean = false,
    ): Boolean {
        val settingsOk = saveToSettings(snapshot, contentResolver)
        val fileOk = saveToFile(snapshot)
        // mirrorSettings kept for call-site compatibility; Settings is always written now.
        if (!settingsOk && mirrorSettings) {
            Log.w(TAG, "settings save failed (mirror requested)")
        }
        return settingsOk || fileOk
    }

    private fun loadFromFile(): Snapshot? {
        return try {
            val file = File(PATH)
            if (!file.isFile || file.length() == 0L) return null
            val props = Properties()
            FileInputStream(file).use { props.load(it) }
            parseProps(
                left = props.getProperty(AADisplayConfig.LastSplitLeftPackage.key),
                right = props.getProperty(AADisplayConfig.LastSplitRightPackage.key),
                ratio = props.getProperty(AADisplayConfig.LastSplitPrimaryRatio.key),
                landscape = props.getProperty(AADisplayConfig.LastSplitDisplayLandscape.key)
            )
        } catch (e: Throwable) {
            Log.w(TAG, "load file failed", e)
            null
        }
    }

    private fun loadFromSettings(cr: ContentResolver?): Snapshot? {
        if (cr == null) return null
        return try {
            parseProps(
                left = Settings.Global.getString(cr, SETTINGS_LEFT),
                right = Settings.Global.getString(cr, SETTINGS_RIGHT),
                ratio = Settings.Global.getString(cr, SETTINGS_RATIO),
                landscape = Settings.Global.getString(cr, SETTINGS_LANDSCAPE)
            )
        } catch (e: Throwable) {
            Log.w(TAG, "load settings failed", e)
            null
        }
    }

    private fun parseProps(
        left: String?,
        right: String?,
        ratio: String?,
        landscape: String?
    ): Snapshot? {
        val l = left?.trim().orEmpty()
        val r = right?.trim().orEmpty()
        if (l.isEmpty() || r.isEmpty() || l == r) return null
        val ratioVal = ratio?.toFloatOrNull()?.takeIf { it in 0.15f..0.85f } ?: 0.5f
        val landscapeVal = landscape?.toBooleanStrictOrNull() ?: true
        return Snapshot(l, r, ratioVal, landscapeVal)
    }

    private fun saveToFile(snapshot: Snapshot): Boolean {
        val props = Properties()
        props.setProperty(AADisplayConfig.LastSplitLeftPackage.key, snapshot.leftPackage)
        props.setProperty(AADisplayConfig.LastSplitRightPackage.key, snapshot.rightPackage)
        props.setProperty(
            AADisplayConfig.LastSplitPrimaryRatio.key,
            snapshot.primaryRatio.toString()
        )
        props.setProperty(
            AADisplayConfig.LastSplitDisplayLandscape.key,
            snapshot.landscape.toString()
        )
        val file = File(PATH)
        fun writeOnce(): Boolean {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                props.store(out, "AADisplay last OneUI split snapshot")
            }
            // Owner (system) read/write; others read — never leave a root-only file if we created it.
            file.setReadable(true, false)
            file.setWritable(true, true)
            return true
        }
        return try {
            writeOnce()
            Log.i(
                TAG,
                "file saved left=${snapshot.leftPackage} right=${snapshot.rightPackage} " +
                    "ratio=${snapshot.primaryRatio} landscape=${snapshot.landscape}"
            )
            true
        } catch (e: Throwable) {
            // Common failure: prior adb/root created root:root 0644 — system_server cannot overwrite.
            Log.w(TAG, "file save failed, retry after delete", e)
            try {
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "could not delete unwritable $PATH")
                    return false
                }
                writeOnce()
                Log.i(
                    TAG,
                    "file saved after delete left=${snapshot.leftPackage} " +
                        "right=${snapshot.rightPackage}"
                )
                true
            } catch (e2: Throwable) {
                Log.w(TAG, "file save failed", e2)
                false
            }
        }
    }

    private fun saveToSettings(snapshot: Snapshot, cr: ContentResolver?): Boolean {
        if (cr == null) return false
        return try {
            Settings.Global.putString(cr, SETTINGS_LEFT, snapshot.leftPackage)
            Settings.Global.putString(cr, SETTINGS_RIGHT, snapshot.rightPackage)
            Settings.Global.putString(cr, SETTINGS_RATIO, snapshot.primaryRatio.toString())
            Settings.Global.putString(cr, SETTINGS_LANDSCAPE, snapshot.landscape.toString())
            Log.i(
                TAG,
                "settings saved left=${snapshot.leftPackage} right=${snapshot.rightPackage}"
            )
            true
        } catch (e: Throwable) {
            Log.w(TAG, "settings save failed", e)
            false
        }
    }
}
