package io.github.nitsuya.aa.display.xposed.util

import android.util.Log
import de.robv.android.xposed.XposedBridge

fun log(tag: String, message: String) {
    Log.i(tag, message)
    XposedBridge.log("[$tag] $message")
}

fun log(tag: String, message: String, t: Throwable?) {
    Log.e(tag, message, t)
    XposedBridge.log("[$tag] $message")
    if(t != null){
        XposedBridge.log(t)
    }
}

/**
 * Hot-path / no-op diagnostics. Logcat only — never [XposedBridge.log]
 * (LSPosed module log IO was hitching system_server during connect/stack storms).
 */
fun logDebug(tag: String, message: String) {
    Log.d(tag, message)
}
