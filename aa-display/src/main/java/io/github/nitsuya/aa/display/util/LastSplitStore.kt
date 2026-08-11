package io.github.nitsuya.aa.display.util

import android.content.ContentResolver
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/**
 * Durable custom-split snapshot written from system_server ([SplitDisplayController]).
 * Settings.Global is the source of truth; file under `/data/system` is best-effort.
 */
object LastSplitStore {
    private const val TAG = "AADisplay_LastSplitStore"
    const val PATH = "/data/system/aadisplay_last_split.properties"

    const val SETTINGS_LEFT = "aadisplay_last_split_left"
    const val SETTINGS_RIGHT = "aadisplay_last_split_right"
    const val SETTINGS_RATIO = "aadisplay_last_split_ratio"
    const val SETTINGS_LANDSCAPE = "aadisplay_last_split_landscape"
    const val SETTINGS_SIDE_BY_SIDE = "aadisplay_last_split_side_by_side"

    data class Snapshot(
        val leftPackage: String,
        val rightPackage: String,
        val primaryRatio: Float,
        val landscape: Boolean,
        val sideBySide: Boolean = landscape,
    )

    fun load(contentResolver: ContentResolver? = null): Snapshot? {
        loadFromSettings(contentResolver)?.let { return it }
        return loadFromFile()
    }

    fun save(
        snapshot: Snapshot,
        contentResolver: ContentResolver? = null,
        mirrorSettings: Boolean = false,
    ): Boolean {
        val settingsOk = saveToSettings(snapshot, contentResolver)
        val fileOk = saveToFile(snapshot)
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
                landscape = props.getProperty(AADisplayConfig.LastSplitDisplayLandscape.key),
                sideBySide = props.getProperty("LastSplitSideBySide"),
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
                landscape = Settings.Global.getString(cr, SETTINGS_LANDSCAPE),
                sideBySide = Settings.Global.getString(cr, SETTINGS_SIDE_BY_SIDE),
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
        landscape: String?,
        sideBySide: String?,
    ): Snapshot? {
        val l = left?.trim().orEmpty()
        val r = right?.trim().orEmpty()
        if (l.isEmpty() || r.isEmpty() || l == r) return null
        val ratioVal = ratio?.toFloatOrNull()?.takeIf { it in 0.15f..0.85f } ?: 0.5f
        val landscapeVal = landscape?.toBooleanStrictOrNull() ?: true
        val sideBySideVal = sideBySide?.toBooleanStrictOrNull() ?: landscapeVal
        return Snapshot(l, r, ratioVal, landscapeVal, sideBySideVal)
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
        props.setProperty("LastSplitSideBySide", snapshot.sideBySide.toString())
        val file = File(PATH)
        fun writeOnce(): Boolean {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                props.store(out, "AADisplay last custom split snapshot")
            }
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
            Log.w(TAG, "file save failed, retry after delete", e)
            try {
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "could not delete unwritable $PATH")
                    return false
                }
                writeOnce()
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
            Settings.Global.putString(cr, SETTINGS_SIDE_BY_SIDE, snapshot.sideBySide.toString())
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
