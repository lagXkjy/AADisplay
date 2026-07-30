package io.github.nitsuya.aa.display.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.topjohnwu.superuser.Shell
import io.github.nitsuya.aa.display.BuildConfig
import java.io.File

/**
 * App settings use the same `aadisplay_config` keys as Auto Open.
 *
 * LSPosed may also keep a copy under `/data/misc/.../prefs/` (MODE_WORLD_READABLE), but
 * system_server still cannot read `app_data_file` / `magisk_file` after reboot.
 * `/data/local/tmp` is wiped on many devices, so we publish a durable copy to
 * [HOOK_MIRROR_PATH] for [de.robv.android.xposed.XSharedPreferences].
 */
object SharedPreferencesAccess {
    private const val TAG = "AADisplay_SharedPreferencesAccess"
    private const val HookPreferencesReadyVersionKey = "__AADisplayHookPreferencesReadyVersion"

    /** Survives reboot; readable by system_server (`system_data_file`). */
    const val HOOK_MIRROR_PATH = "/data/system/aadisplay_config.xml"

    fun openForHooks(context: Context, name: String): SharedPreferences {
        // Always applicationContext so Activity/Service/Application share one cache.
        val app = context.applicationContext
        return try {
            @Suppress("DEPRECATION")
            app.getSharedPreferences(name, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
    }

    fun makeReadableForHooks(context: Context, name: String): Boolean {
        val app = context.applicationContext
        val prefsOk = try {
            @Suppress("DEPRECATION")
            app.getSharedPreferences(name, Context.MODE_WORLD_READABLE)
                .edit()
                .putInt(HookPreferencesReadyVersionKey, 1)
                .commit()
        } catch (_: SecurityException) {
            makePrivatePreferencesReadable(app, name)
        }
        val mirrored = publishHookMirror(app, name)
        return prefsOk && mirrored
    }

    fun publishHookMirror(context: Context, name: String = AADisplayConfig.ConfigName): Boolean {
        val prefsFile = File(context.applicationContext.applicationInfo.dataDir, "shared_prefs/$name.xml")
        // LSPosed may redirect WORLD_READABLE writes to /data/misc/...; also try that file.
        val candidates = linkedSetOf(prefsFile)
        findLsposedPrefsFile(name)?.let { candidates.add(it) }

        val source = candidates.firstOrNull { it.exists() && it.length() > 0 }
        if (source == null) {
            Log.w(TAG, "publishHookMirror: no source prefs for $name")
            return false
        }

        return try {
            // Ensure root shell (blocks until ready).
            val shell = Shell.getShell()
            if (!shell.isRoot) {
                Log.w(TAG, "publishHookMirror: root unavailable")
                return false
            }
            // Run commands separately so chcon failure does not discard a successful cp.
            val cp = Shell.cmd("cp ${source.absolutePath} $HOOK_MIRROR_PATH").exec()
            val chmod = Shell.cmd("chmod 644 $HOOK_MIRROR_PATH").exec()
            Shell.cmd("chcon u:object_r:system_data_file:s0 $HOOK_MIRROR_PATH").exec()
            val mirror = File(HOOK_MIRROR_PATH)
            val ok = mirror.exists() && mirror.canRead() && mirror.length() > 0
            if (!ok) {
                Log.w(TAG, "publishHookMirror failed: cp=${cp.isSuccess} chmod=${chmod.isSuccess} exists=${mirror.exists()}")
                return false
            }
            Log.i(TAG, "publishHookMirror ok ${source.absolutePath} -> $HOOK_MIRROR_PATH (${mirror.length()} bytes)")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "publishHookMirror error", e)
            false
        }
    }

    private fun findLsposedPrefsFile(name: String): File? {
        val misc = File("/data/misc")
        if (!misc.isDirectory) return null
        return try {
            misc.listFiles()
                ?.asSequence()
                ?.map { File(it, "prefs/${BuildConfig.APPLICATION_ID}/$name.xml") }
                ?.firstOrNull { it.exists() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun makePrivatePreferencesReadable(context: Context, name: String): Boolean {
        val dataDir = File(context.applicationInfo.dataDir)
        val prefsDir = File(dataDir, "shared_prefs")
        val prefsFile = File(prefsDir, "$name.xml")

        // Touch prefs so the XML exists before chmod/copy.
        openForHooks(context, name).edit().putInt(HookPreferencesReadyVersionKey, 1).commit()

        var ok = ensureExecutable(dataDir)
        if (prefsDir.exists()) {
            ok = ensureExecutable(prefsDir) && ok
        }
        if (prefsFile.exists()) {
            ok = ensureReadable(prefsFile) && ok
        }
        return ok
    }

    private fun ensureExecutable(file: File): Boolean {
        return file.exists() && (file.setExecutable(true, false) || file.canExecute())
    }

    private fun ensureReadable(file: File): Boolean {
        return file.exists() && (file.setReadable(true, false) || file.canRead())
    }
}
