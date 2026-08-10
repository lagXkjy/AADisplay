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
        loadFromFile()?.let { return it }
        return loadFromSettings(contentResolver)
    }

    /**
     * @param mirrorSettings Settings.Global is relatively expensive (sync + notify).
     * Prefer file-only on the hot path; mirror on destroy / rare updates.
     */
    fun save(
        snapshot: Snapshot,
        contentResolver: ContentResolver? = null,
        mirrorSettings: Boolean = false,
    ): Boolean {
        val fileOk = saveToFile(snapshot)
        val settingsOk = if (mirrorSettings) {
            saveToSettings(snapshot, contentResolver)
        } else {
            false
        }
        return fileOk || settingsOk
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
        return try {
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
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                props.store(out, "AADisplay last OneUI split snapshot")
            }
            file.setReadable(true, false)
            Log.i(
                TAG,
                "file saved left=${snapshot.leftPackage} right=${snapshot.rightPackage} " +
                    "ratio=${snapshot.primaryRatio} landscape=${snapshot.landscape}"
            )
            true
        } catch (e: Throwable) {
            Log.w(TAG, "file save failed", e)
            false
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
