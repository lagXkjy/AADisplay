package io.github.nitsuya.aa.display.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import io.github.nitsuya.aa.display.xposed.IShellManager

/**
 * Optional shell hooks around VD create/destroy. Prefs-driven command lists were removed;
 * both ops are no-ops (always succeed).
 */
class ShellManagerService: Service() {
    companion object {
        private const val TAG = "AADisplay_ShellManagerService"
    }

    private val stub: IShellManager.Stub = object: IShellManager.Stub(){
        override fun createVirtualDisplayBefore(): Boolean = true
        override fun destroyVirtualDisplayAfter(): Boolean = true
    }
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
    }
    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind: $intent")
        return stub
    }
}
