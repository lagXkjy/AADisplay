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
 *
 * Persisted keys keep historical `left`/`right` names (= PRIMARY/SECONDARY panes).
 * Kotlin API uses primary/secondary to match [io.github.nitsuya.aa.display.ui.aa.split.SplitPane].
 */
object LastSplitStore {
    private const val TAG = "AADisplay_LastSplitStore"
    const val PATH = "/data/system/aadisplay_last_split.properties"

    /** Settings.Global key for PRIMARY pane package (historical name). */
    const val SETTINGS_LEFT = "aadisplay_last_split_left"
    /** Settings.Global key for SECONDARY pane package (historical name). */
    const val SETTINGS_RIGHT = "aadisplay_last_split_right"
    const val SETTINGS_RATIO = "aadisplay_last_split_ratio"
    const val SETTINGS_LANDSCAPE = "aadisplay_last_split_landscape"
    const val SETTINGS_SIDE_BY_SIDE = "aadisplay_last_split_side_by_side"

    /** Properties-file keys (historical names; do not rename — existing snapshots). */
    private const val FILE_LEFT = "LastSplitLeftPackage"
    private const val FILE_RIGHT = "LastSplitRightPackage"
    private const val FILE_RATIO = "LastSplitPrimaryRatio"
    private const val FILE_LANDSCAPE = "LastSplitDisplayLandscape"
    private const val FILE_SIDE_BY_SIDE = "LastSplitSideBySide"

    data class Snapshot(
        val primaryPackage: String,
        val secondaryPackage: String,
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
                primary = props.getProperty(FILE_LEFT),
                secondary = props.getProperty(FILE_RIGHT),
                ratio = props.getProperty(FILE_RATIO),
                landscape = props.getProperty(FILE_LANDSCAPE),
                sideBySide = props.getProperty(FILE_SIDE_BY_SIDE),
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
                primary = Settings.Global.getString(cr, SETTINGS_LEFT),
                secondary = Settings.Global.getString(cr, SETTINGS_RIGHT),
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
        primary: String?,
        secondary: String?,
        ratio: String?,
        landscape: String?,
        sideBySide: String?,
    ): Snapshot? {
        val p = primary?.trim().orEmpty()
        val s = secondary?.trim().orEmpty()
        if (p.isEmpty() || s.isEmpty() || p == s) return null
        val ratioVal = ratio?.toFloatOrNull()?.takeIf { it in 0.15f..0.85f } ?: 0.5f
        val landscapeVal = landscape?.toBooleanStrictOrNull() ?: true
        val sideBySideVal = sideBySide?.toBooleanStrictOrNull() ?: landscapeVal
        return Snapshot(p, s, ratioVal, landscapeVal, sideBySideVal)
    }

    private fun saveToFile(snapshot: Snapshot): Boolean {
        val props = Properties()
        props.setProperty(FILE_LEFT, snapshot.primaryPackage)
        props.setProperty(FILE_RIGHT, snapshot.secondaryPackage)
        props.setProperty(FILE_RATIO, snapshot.primaryRatio.toString())
        props.setProperty(FILE_LANDSCAPE, snapshot.landscape.toString())
        props.setProperty(FILE_SIDE_BY_SIDE, snapshot.sideBySide.toString())
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
                "file saved primary=${snapshot.primaryPackage} secondary=${snapshot.secondaryPackage} " +
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
            Settings.Global.putString(cr, SETTINGS_LEFT, snapshot.primaryPackage)
            Settings.Global.putString(cr, SETTINGS_RIGHT, snapshot.secondaryPackage)
            Settings.Global.putString(cr, SETTINGS_RATIO, snapshot.primaryRatio.toString())
            Settings.Global.putString(cr, SETTINGS_LANDSCAPE, snapshot.landscape.toString())
            Settings.Global.putString(cr, SETTINGS_SIDE_BY_SIDE, snapshot.sideBySide.toString())
            Log.i(
                TAG,
                "settings saved primary=${snapshot.primaryPackage} secondary=${snapshot.secondaryPackage}"
            )
            true
        } catch (e: Throwable) {
            Log.w(TAG, "settings save failed", e)
            false
        }
    }
}
