package io.github.nitsuya.aa.display.xposed.util

import android.os.Build
import android.os.IBinder
import android.view.DisplayControl
import android.view.SurfaceControlHidden
import io.github.nitsuya.aa.display.xposed.log

/**
 * Resolves the default physical display token across API levels.
 * [SurfaceControlHidden.getInternalDisplayToken] was removed in Android 14 (API 34).
 */
object DisplayPowerCompat {
    private const val TAG = "AADisplay_DisplayPowerCompat"

    @Volatile
    private var cachedToken: IBinder? = null

    fun resolveDefaultDisplayToken(): IBinder? {
        cachedToken?.let { return it }
        val token = try {
            if (Build.VERSION.SDK_INT >= 34) {
                resolveViaDisplayControl()
            } else {
                @Suppress("DEPRECATION")
                SurfaceControlHidden.getInternalDisplayToken()
            }
        } catch (e: Throwable) {
            log(TAG, "resolveDefaultDisplayToken primary path failed:", e)
            resolveViaDisplayControl()
                ?: resolveViaReflectionFallback()
        }
        if (token != null) cachedToken = token
        return token
    }

    fun setPhoneDisplayPowerMode(mode: Int) {
        val token = resolveDefaultDisplayToken()
        if (token == null) {
            log(TAG, "setPhoneDisplayPowerMode($mode): no display token")
            return
        }
        SurfaceControlHidden.setDisplayPowerMode(token, mode)
    }

    private fun resolveViaDisplayControl(): IBinder? {
        return try {
            val ids = DisplayControl.getPhysicalDisplayIds() ?: return null
            if (ids.isEmpty()) return null
            DisplayControl.getPhysicalDisplayToken(ids[0])
        } catch (e: Throwable) {
            log(TAG, "DisplayControl token failed:", e)
            null
        }
    }

    /** Last resort: reflect by class/method name if stub linkage differs on OEM builds. */
    private fun resolveViaReflectionFallback(): IBinder? {
        return try {
            val clazz = Class.forName("android.view.DisplayControl")
            val idsMethod = clazz.getDeclaredMethod("getPhysicalDisplayIds").apply { isAccessible = true }
            val ids = idsMethod.invoke(null) as? LongArray ?: return null
            if (ids.isEmpty()) return null
            val tokenMethod = clazz.getDeclaredMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
            tokenMethod.invoke(null, ids[0]) as? IBinder
        } catch (e: Throwable) {
            log(TAG, "reflection DisplayControl fallback failed:", e)
            null
        }
    }
}
