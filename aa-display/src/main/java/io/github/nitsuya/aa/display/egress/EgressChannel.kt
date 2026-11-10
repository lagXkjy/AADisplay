package io.github.nitsuya.aa.display.egress

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File

/**
 * User-selected video egress channel, persisted app-side.
 *
 * Neutral egress layer shared by all projection stacks (CarPlay / AAP /
 * future protocols) — lives in `egress/` on purpose: protocol packages
 * (carplay/, aap/) import this, never the other way around.
 *
 * Channels are **mutually exclusive** — one paints at a time:
 *  - [CHANNEL_CARPLAY]: wireless CarPlay Source + Host GL panes.
 *  - [CHANNEL_AAP]: Android Auto over AAP (custom protocol).
 *  - [CHANNEL_AA]: retired Google gearhead path; read only as a migrate sentinel → [defaultChannel].
 *  - [CHANNEL_CARPLAY_USB]: retired wired fake-iPhone path (WIP abandoned);
 *    read only as a migrate sentinel → [defaultChannel].
 *
 * **Frozen persistence contract** (devices already out in the wild): file
 * [FILE_NAME] in filesDir, legacy prefs name/key, numeric ids and text aliases.
 * Do not rename. BootArm mirror is `/data/system/aadisplay_egress_channel`
 * ([io.github.nitsuya.aa.display.util.EgressChannelMirror]), synced over Binder —
 * not Settings.Global (OEM often blocks unknown keys).
 *
 * Default when nothing is persisted: [CHANNEL_AAP] if Android Auto (gearhead) is installed
 * (cert can be auto-extracted); otherwise [CHANNEL_CARPLAY].
 */
object EgressChannel {
    private const val TAG = "AADisplay_Egress"

    /** Retired stock Google AA (gearhead). Never active; migrates to [defaultChannel]. */
    const val CHANNEL_AA = 0
    const val CHANNEL_CARPLAY = 1
    /** Retired wired fake-iPhone path. Never active; migrates to [defaultChannel]. */
    const val CHANNEL_CARPLAY_USB = 2
    /** AAP egress — phone-as-client protocol stack. */
    const val CHANNEL_AAP = 3

    /** Package used only for CarService cert extraction (not runtime hooks). */
    const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"

    private const val FILE_NAME = "aadisplay_carplay_channel"
    private const val LEGACY_PREFS = "aadisplay_carplay"
    private const val LEGACY_KEY = "channel"

    fun isAndroidAutoInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(GEARHEAD_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    /** Channel used when no file / legacy prefs exist yet. */
    fun defaultChannel(context: Context): Int =
        if (isAndroidAutoInstalled(context)) CHANNEL_AAP else CHANNEL_CARPLAY

    fun get(context: Context): Int {
        val app = context.applicationContext
        val raw = readFile(app) ?: migrateLegacy(app)
        if (raw != null) {
            return resolveActive(app, raw, clearDirty = true)
        }
        val def = defaultChannel(app)
        writeFile(app, def)
        Log.i(TAG, "EgressChannel default applied channel=$def aaInstalled=${isAndroidAutoInstalled(app)}")
        return def
    }

    fun set(context: Context, channel: Int) {
        val value = normalizeForWrite(context.applicationContext, channel)
        val app = context.applicationContext
        if (!writeFile(app, value)) {
            Log.e(TAG, "EgressChannel set failed channel=$value")
        }
        try {
            app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(LEGACY_KEY, value)
                .commit()
        } catch (_: Throwable) {
        }
    }

    /** Active CarPlay Source egress (not AAP). */
    fun useCarPlayEgress(context: Context): Boolean =
        get(context) == CHANNEL_CARPLAY

    fun useWirelessCarPlay(context: Context): Boolean = get(context) == CHANNEL_CARPLAY

    fun useAapEgress(context: Context): Boolean = get(context) == CHANNEL_AAP

    private fun resolveActive(app: Context, channel: Int, clearDirty: Boolean): Int {
        if (channel == CHANNEL_CARPLAY_USB || channel == CHANNEL_AA) {
            val fallback = defaultChannel(app)
            if (clearDirty) {
                writeFile(app, fallback)
                Log.i(
                    TAG,
                    "EgressChannel retired channel=$channel — cleared leftover → $fallback",
                )
            }
            return fallback
        }
        return when (channel) {
            CHANNEL_CARPLAY -> CHANNEL_CARPLAY
            CHANNEL_AAP -> CHANNEL_AAP
            else -> defaultChannel(app)
        }
    }

    private fun normalizeForWrite(app: Context, channel: Int): Int = when (channel) {
        CHANNEL_CARPLAY -> CHANNEL_CARPLAY
        CHANNEL_AAP -> CHANNEL_AAP
        else -> defaultChannel(app) // includes retired CHANNEL_AA / CHANNEL_CARPLAY_USB
    }

    private fun migrateLegacy(app: Context): Int? {
        val legacy = try {
            app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .getInt(LEGACY_KEY, -1)
        } catch (_: Throwable) {
            -1
        }
        if (legacy != CHANNEL_CARPLAY && legacy != CHANNEL_AA &&
            legacy != CHANNEL_CARPLAY_USB && legacy != CHANNEL_AAP
        ) {
            return null
        }
        val active = resolveActive(app, legacy, clearDirty = false)
        writeFile(app, active)
        return active
    }

    private fun channelFile(app: Context): File = File(app.filesDir, FILE_NAME)

    private fun readFile(app: Context): Int? {
        val f = channelFile(app)
        if (!f.isFile) return null
        return try {
            when (f.readText().trim()) {
                CHANNEL_CARPLAY.toString(), "cp", "carplay" -> CHANNEL_CARPLAY
                CHANNEL_CARPLAY_USB.toString(), "cp-usb", "carplay-usb", "usb" -> CHANNEL_CARPLAY_USB
                CHANNEL_AAP.toString(), "aap", "native-aa" -> CHANNEL_AAP
                CHANNEL_AA.toString(), "aa" -> CHANNEL_AA
                else -> null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "EgressChannel read failed", e)
            null
        }
    }

    private fun writeFile(app: Context, channel: Int): Boolean {
        return try {
            val dir = app.filesDir
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "EgressChannel filesDir missing")
                return false
            }
            val f = channelFile(app)
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(channel.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(channel.toString())
                tmp.delete()
            }
            Log.i(TAG, "EgressChannel persisted channel=$channel path=${f.absolutePath}")
            mirrorToSystem(channel)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "EgressChannel write failed", e)
            false
        }
    }

    /**
     * Push the persisted choice into system_server's `/data/system` mirror so
     * [io.github.nitsuya.aa.display.xposed.carplay.CarPlayBootArm] survives reboot.
     */
    fun mirrorToSystem(channel: Int) {
        try {
            io.github.nitsuya.aa.display.CoreApi.reportEgressChannel(channel)
        } catch (e: Throwable) {
            Log.w(TAG, "EgressChannel system mirror skipped: ${e.message}")
        }
    }
}
