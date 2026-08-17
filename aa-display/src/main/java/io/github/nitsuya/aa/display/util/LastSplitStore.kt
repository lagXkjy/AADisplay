package io.github.nitsuya.aa.display.util

import android.content.ContentResolver
import android.provider.Settings
import android.util.Log
import io.github.nitsuya.aa.display.ui.aa.split.PaneAppStack
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/**
 * Durable custom-split snapshot written from system_server ([io.github.nitsuya.aa.display.ui.aa.split.SplitDisplayController]).
 * Settings.Global is the source of truth; file under `/data/system` is best-effort.
 *
 * Persisted keys keep historical `left`/`right` names (= PRIMARY/SECONDARY panes).
 * Kotlin API uses primary/secondary to match [io.github.nitsuya.aa.display.ui.aa.split.SplitPane].
 *
 * Stack keys store comma-separated packages in **bottom → top** order (last = front).
 * Front package keys remain for backward compatibility with older builds.
 */
object LastSplitStore {
    private const val TAG = "AADisplay_LastSplitStore"
    const val PATH = "/data/system/aadisplay_last_split.properties"

    /** Settings.Global key for PRIMARY pane front package (historical name). */
    const val SETTINGS_LEFT = "aadisplay_last_split_left"
    /** Settings.Global key for SECONDARY pane front package (historical name). */
    const val SETTINGS_RIGHT = "aadisplay_last_split_right"
    const val SETTINGS_RATIO = "aadisplay_last_split_ratio"
    const val SETTINGS_FULLSCREEN = "aadisplay_last_split_fullscreen"
    /** Bottom→top CSV for PRIMARY stack (optional; absent on old snapshots). */
    const val SETTINGS_LEFT_STACK = "aadisplay_last_split_left_stack"
    /** Bottom→top CSV for SECONDARY stack. */
    const val SETTINGS_RIGHT_STACK = "aadisplay_last_split_right_stack"

    /** Properties-file keys (historical names; do not rename — existing snapshots). */
    private const val FILE_LEFT = "LastSplitLeftPackage"
    private const val FILE_RIGHT = "LastSplitRightPackage"
    private const val FILE_RATIO = "LastSplitPrimaryRatio"
    private const val FILE_FULLSCREEN = "LastSplitFullscreenPane"
    private const val FILE_LEFT_STACK = "LastSplitLeftStack"
    private const val FILE_RIGHT_STACK = "LastSplitRightStack"

    data class Snapshot(
        val primaryPackage: String,
        val secondaryPackage: String,
        val primaryRatio: Float,
        /** [SplitPane.FULLSCREEN_NONE] or PRIMARY/SECONDARY. */
        val fullscreenPane: Int = SplitPane.FULLSCREEN_NONE,
        /**
         * PRIMARY stack bottom → top. Empty means legacy single-app snapshot
         * (use [primaryPackage] alone).
         */
        val primaryStack: List<String> = emptyList(),
        /** SECONDARY stack bottom → top. */
        val secondaryStack: List<String> = emptyList(),
    ) {
        fun primaryPackagesBottomToTop(): List<String> =
            normalizeStack(primaryStack, primaryPackage)

        fun secondaryPackagesBottomToTop(): List<String> =
            normalizeStack(secondaryStack, secondaryPackage)
    }

    fun load(contentResolver: ContentResolver? = null): Snapshot? {
        loadFromSettings(contentResolver)?.let { return it }
        return loadFromFile()
    }

    /**
     * Persist [snapshot] to Settings.Global and the system properties file.
     *
     * Both backends are always written when possible. [mirrorSettings] only controls
     * whether a Settings failure is logged as a warning (true) or left quiet (false);
     * it does not switch to a file-only path.
     */
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

    fun encodeStack(packagesBottomToTop: List<String>): String =
        packagesBottomToTop
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .takeLast(PaneAppStack.MAX_PER_PANE)
            .joinToString(",")

    fun decodeStack(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .takeLast(PaneAppStack.MAX_PER_PANE)
    }

    private fun normalizeStack(stack: List<String>, front: String): List<String> {
        val frontPkg = front.trim()
        if (stack.isEmpty()) {
            return if (frontPkg.isNotEmpty()) listOf(frontPkg) else emptyList()
        }
        val cleaned = stack.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .takeLast(PaneAppStack.MAX_PER_PANE)
            .toMutableList()
        if (frontPkg.isNotEmpty()) {
            cleaned.remove(frontPkg)
            cleaned.add(frontPkg)
        }
        return cleaned.takeLast(PaneAppStack.MAX_PER_PANE)
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
                fullscreen = props.getProperty(FILE_FULLSCREEN),
                primaryStack = props.getProperty(FILE_LEFT_STACK),
                secondaryStack = props.getProperty(FILE_RIGHT_STACK),
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
                fullscreen = Settings.Global.getString(cr, SETTINGS_FULLSCREEN),
                primaryStack = Settings.Global.getString(cr, SETTINGS_LEFT_STACK),
                secondaryStack = Settings.Global.getString(cr, SETTINGS_RIGHT_STACK),
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
        fullscreen: String?,
        primaryStack: String? = null,
        secondaryStack: String? = null,
    ): Snapshot? {
        val p = primary?.trim().orEmpty()
        val s = secondary?.trim().orEmpty()
        if (p.isEmpty() || s.isEmpty() || p == s) return null
        val ratioVal = ratio?.toFloatOrNull()?.takeIf { it in 0.15f..0.85f } ?: 0.5f
        val fsVal = fullscreen?.toIntOrNull()?.takeIf { SplitPane.isFullscreenPane(it) }
            ?: SplitPane.FULLSCREEN_NONE
        return Snapshot(
            primaryPackage = p,
            secondaryPackage = s,
            primaryRatio = ratioVal,
            fullscreenPane = fsVal,
            primaryStack = decodeStack(primaryStack),
            secondaryStack = decodeStack(secondaryStack),
        )
    }

    private fun saveToFile(snapshot: Snapshot): Boolean {
        val props = Properties()
        props.setProperty(FILE_LEFT, snapshot.primaryPackage)
        props.setProperty(FILE_RIGHT, snapshot.secondaryPackage)
        props.setProperty(FILE_RATIO, snapshot.primaryRatio.toString())
        props.setProperty(FILE_FULLSCREEN, snapshot.fullscreenPane.toString())
        val pStack = encodeStack(snapshot.primaryPackagesBottomToTop())
        val sStack = encodeStack(snapshot.secondaryPackagesBottomToTop())
        props.setProperty(FILE_LEFT_STACK, pStack)
        props.setProperty(FILE_RIGHT_STACK, sStack)
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
            Log.d(
                TAG,
                "file saved primary=${snapshot.primaryPackage} secondary=${snapshot.secondaryPackage} " +
                    "ratio=${snapshot.primaryRatio} fullscreen=${snapshot.fullscreenPane} " +
                    "pStack=$pStack sStack=$sStack"
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
            Settings.Global.putString(cr, SETTINGS_FULLSCREEN, snapshot.fullscreenPane.toString())
            Settings.Global.putString(
                cr,
                SETTINGS_LEFT_STACK,
                encodeStack(snapshot.primaryPackagesBottomToTop()),
            )
            Settings.Global.putString(
                cr,
                SETTINGS_RIGHT_STACK,
                encodeStack(snapshot.secondaryPackagesBottomToTop()),
            )
            Log.d(
                TAG,
                "settings saved primary=${snapshot.primaryPackage} secondary=${snapshot.secondaryPackage} " +
                    "fullscreen=${snapshot.fullscreenPane}"
            )
            true
        } catch (e: Throwable) {
            Log.w(TAG, "settings save failed", e)
            false
        }
    }
}
