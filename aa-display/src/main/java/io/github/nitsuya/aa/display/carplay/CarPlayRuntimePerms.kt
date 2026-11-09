package io.github.nitsuya.aa.display.carplay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Runtime permissions required for wireless CarPlay (BT RFCOMM + NSD / Nearby Wi‑Fi).
 * Declared in the manifest but never requested — Samsung Android 13 then throws
 * [SecurityException] on [android.bluetooth.BluetoothAdapter.getScanMode] and sticky
 * [CarPlayProjectionService] restart-loops the process.
 */
object CarPlayRuntimePerms {
    const val REQUEST_CODE = 0x0C02

    fun wirelessRequired(context: Context): Array<String> {
        val list = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list
            .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            .toTypedArray()
    }

    /**
     * @return true if already granted (caller may start FGS immediately);
     * false if a system dialog was shown (resume on [onRequestResult]).
     */
    fun ensureWireless(activity: Activity): Boolean {
        val missing = wirelessRequired(activity)
        if (missing.isEmpty()) return true
        Log.i(CarPlayConsts.TAG, "requesting runtime perms: ${missing.joinToString()}")
        activity.requestPermissions(missing, REQUEST_CODE)
        return false
    }

    fun onRequestResult(
        requestCode: Int,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode != REQUEST_CODE) return false
        return grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
    }
}
