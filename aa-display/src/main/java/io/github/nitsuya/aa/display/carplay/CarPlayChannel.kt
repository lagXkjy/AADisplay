package io.github.nitsuya.aa.display.carplay

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * User-selected video egress channel, persisted app-side.
 *
 * Channels are **mutually exclusive** — one paints at a time:
 *  - [CHANNEL_CARPLAY]: wireless CarPlay Source + [CarPlayHostActivity] GL panes.
 *  - [CHANNEL_CARPLAY_USB]: wired fake-iPhone path (WIP; gated by [USB_CARPLAY_ENABLED]).
 *  - [CHANNEL_AAP]: Android Auto over AAP (custom protocol) — replaces stock gearhead.
 *  - [CHANNEL_AA]: retired Google gearhead path; read only as a migrate sentinel → [defaultChannel].
 *
 * Persistence uses [Context.getFilesDir] (not SharedPreferences).
 *
 * Default when nothing is persisted: [CHANNEL_AAP] if Android Auto (gearhead) is installed
 * (cert can be auto-extracted); otherwise [CHANNEL_CARPLAY].
 */
object CarPlayChannel {
    /** Retired stock Google AA (gearhead). Never active; migrates to [defaultChannel]. */
    const val CHANNEL_AA = 0
    const val CHANNEL_CARPLAY = 1
    const val CHANNEL_CARPLAY_USB = 2
    /** AAP egress — phone-as-client protocol stack. */
    const val CHANNEL_AAP = 3

    /** Package used only for CarService cert extraction (not runtime hooks). */
    const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"

    /**
     * Wired USB CarPlay egress — WIP. Keep false for public builds so leftover `cp-usb`
     * cannot arm FGS or enter [CarPlaySourceSession] USB transport.
     */
    const val USB_CARPLAY_ENABLED = false

    private const val FILE_NAME = "aadisplay_carplay_channel"
    /** Mirrored for system_server [io.github.nitsuya.aa.display.xposed.carplay.CarPlayBootArm]. */
    const val SETTINGS_CHANNEL = "aadisplay_carplay_channel"
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
        Log.i(CarPlayConsts.TAG, "CarPlayChannel default applied channel=$def aaInstalled=${isAndroidAutoInstalled(app)}")
        return def
    }

    fun set(context: Context, channel: Int) {
        val value = normalizeForWrite(context.applicationContext, channel)
        val app = context.applicationContext
        if (!writeFile(app, value)) {
            Log.e(CarPlayConsts.TAG, "CarPlayChannel set failed channel=$value")
        }
        try {
            app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(LEGACY_KEY, value)
                .commit()
        } catch (_: Throwable) {
        }
    }

    /** Active CarPlay Source egress (not AAP). USB counts only when [USB_CARPLAY_ENABLED]. */
    fun useCarPlayEgress(context: Context): Boolean {
        val ch = get(context)
        return ch == CHANNEL_CARPLAY || (USB_CARPLAY_ENABLED && ch == CHANNEL_CARPLAY_USB)
    }

    fun useUsbCarPlay(context: Context): Boolean =
        USB_CARPLAY_ENABLED && get(context) == CHANNEL_CARPLAY_USB

    fun useWirelessCarPlay(context: Context): Boolean = get(context) == CHANNEL_CARPLAY

    fun useAapEgress(context: Context): Boolean = get(context) == CHANNEL_AAP

    private fun resolveActive(app: Context, channel: Int, clearDirty: Boolean): Int {
        if (channel == CHANNEL_CARPLAY_USB && !USB_CARPLAY_ENABLED) {
            val fallback = defaultChannel(app)
            if (clearDirty) {
                writeFile(app, fallback)
                Log.i(CarPlayConsts.TAG, "CarPlayChannel USB disabled — cleared leftover cp-usb → $fallback")
            }
            return fallback
        }
        if (channel == CHANNEL_AA) {
            val fallback = defaultChannel(app)
            if (clearDirty) {
                writeFile(app, fallback)
                Log.i(
                    CarPlayConsts.TAG,
                    "CarPlayChannel retired gearhead AA — cleared leftover aa → $fallback",
                )
            }
            return fallback
        }
        return when (channel) {
            CHANNEL_CARPLAY -> CHANNEL_CARPLAY
            CHANNEL_CARPLAY_USB -> CHANNEL_CARPLAY_USB
            CHANNEL_AAP -> CHANNEL_AAP
            else -> defaultChannel(app)
        }
    }

    private fun normalizeForWrite(app: Context, channel: Int): Int = when (channel) {
        CHANNEL_CARPLAY -> CHANNEL_CARPLAY
        CHANNEL_CARPLAY_USB -> if (USB_CARPLAY_ENABLED) CHANNEL_CARPLAY_USB else defaultChannel(app)
        CHANNEL_AAP -> CHANNEL_AAP
        else -> defaultChannel(app) // includes retired CHANNEL_AA
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
            Log.w(CarPlayConsts.TAG, "CarPlayChannel read failed", e)
            null
        }
    }

    private fun writeFile(app: Context, channel: Int): Boolean {
        return try {
            val dir = app.filesDir
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(CarPlayConsts.TAG, "CarPlayChannel filesDir missing")
                return false
            }
            val f = channelFile(app)
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(channel.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(channel.toString())
                tmp.delete()
            }
            Log.i(CarPlayConsts.TAG, "CarPlayChannel persisted channel=$channel path=${f.absolutePath}")
            mirrorSettings(app, channel)
            true
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "CarPlayChannel write failed", e)
            false
        }
    }

    private fun mirrorSettings(app: Context, channel: Int) {
        try {
            Settings.Global.putInt(app.contentResolver, SETTINGS_CHANNEL, channel)
        } catch (e: Throwable) {
            Log.w(
                CarPlayConsts.TAG,
                "CarPlayChannel Settings.Global mirror skipped: ${e.message}",
            )
        }
    }
}
