package io.github.nitsuya.aa.display.xposed.carplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Process
import android.provider.Settings
import io.github.nitsuya.aa.display.carplay.CarPlayChannel
import io.github.nitsuya.aa.display.carplay.CarPlayConsts
import io.github.nitsuya.aa.display.xposed.util.log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * system_server early arm for wireless CarPlay Source.
 *
 * App-side [io.github.nitsuya.aa.display.carplay.CarPlayAutoArmReceiver] cannot receive
 * [BluetoothAdapter.ACTION_STATE_CHANGED] while stopped
 * (`Background execution not allowed`). Some OEMs enable Bluetooth (and classic HU
 * reconnect) ~15–20s before [Intent.ACTION_BOOT_COMPLETED] starts the app — the HU
 * SDP for `…cafe` fails (`scn=0`) and backs off; the second connect works after cafe
 * is finally listening.
 *
 * This watch runs in system_server (no background-broadcast ban) and starts the app
 * FGS as soon as BT is ON and CE storage is unlocked, when the user last chose CarPlay.
 */
object CarPlayBootArm {
    private const val TAG = "AAD_CpBootArm"
    private const val TARGET_PACKAGE = "io.github.nitsuya.aa.display"
    private const val SERVICE_CLASS =
        "io.github.nitsuya.aa.display.carplay.CarPlayProjectionService"
    private val SETTINGS_CHANNEL = CarPlayChannel.SETTINGS_CHANNEL
    private const val DEBOUNCE_MS = 1_500L
    private const val RETRY_MS = 2_000L

    private val installed = AtomicBoolean(false)
    private val lastArmElapsed = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val app = context.applicationContext
        val rx = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                when (action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR,
                        )
                        if (state != BluetoothAdapter.STATE_ON) return
                        tryArm(app, "bt-on")
                    }
                    Intent.ACTION_USER_UNLOCKED -> tryArm(app, "user-unlocked")
                    Intent.ACTION_BOOT_COMPLETED -> tryArm(app, "boot-completed")
                }
            }
        }
        receiver = rx
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_USER_UNLOCKED)
            addAction(Intent.ACTION_BOOT_COMPLETED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                app.registerReceiver(rx, filter)
            }
            log(TAG, "installed")
        } catch (e: Throwable) {
            receiver = null
            installed.set(false)
            log(TAG, "install failed", e)
            return
        }
        // system_server may come up after BT is already ON / user unlocked.
        mainHandler.post { tryArm(app, "install") }
        mainHandler.postDelayed({ tryArm(app, "install-retry") }, RETRY_MS)
    }

    private fun tryArm(app: Context, reason: String) {
        if (!isCarPlayChannelSelected(app)) {
            return
        }
        if (!isBluetoothOn(app)) {
            log(TAG, "skip arm reason=$reason — bluetooth off")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val prev = lastArmElapsed.get()
        if (prev != 0L && now - prev < DEBOUNCE_MS) {
            return
        }
        lastArmElapsed.set(now)
        val token = android.os.Binder.clearCallingIdentity()
        try {
            val intent = Intent().setClassName(TARGET_PACKAGE, SERVICE_CLASS)
                .setAction(CarPlayConsts.ACTION_CONNECT)
                // Boot: HostRuntime is started inside FGS; trampoline Activity often hits BAL.
                .putExtra("launchHostTrampoline", false)
            if (Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                app.startService(intent)
            }
            log(TAG, "start FGS reason=$reason")
        } catch (e: Throwable) {
            lastArmElapsed.set(0L)
            log(TAG, "start FGS failed reason=$reason", e)
            mainHandler.postDelayed({ tryArm(app, "$reason-retry") }, RETRY_MS)
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    private fun isBluetoothOn(app: Context): Boolean {
        return try {
            app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Prefer Settings.Global mirror (CE-safe once unlocked); fall back to app filesDir.
     */
    private fun isCarPlayChannelSelected(app: Context): Boolean {
        try {
            val v = Settings.Global.getInt(app.contentResolver, SETTINGS_CHANNEL, -1)
            when (v) {
                CarPlayChannel.CHANNEL_CARPLAY -> return true
                CarPlayChannel.CHANNEL_CARPLAY_USB -> {
                    if (CarPlayChannel.USB_CARPLAY_ENABLED) return true
                    // Leftover wired selection while USB is disabled.
                    val fallback = CarPlayChannel.defaultChannel(app)
                    mirrorSettings(app, fallback)
                    return fallback == CarPlayChannel.CHANNEL_CARPLAY
                }
                CarPlayChannel.CHANNEL_AA -> return false
            }
        } catch (_: Throwable) {
        }
        val userId = Process.myUid() / 100_000
        val paths = listOf(
            "/data/user/$userId/$TARGET_PACKAGE/files/aadisplay_carplay_channel",
            "/data/data/$TARGET_PACKAGE/files/aadisplay_carplay_channel",
        )
        for (path in paths) {
            val f = File(path)
            if (!f.isFile) continue
            val text = try {
                f.readText().trim()
            } catch (_: Throwable) {
                continue
            }
            when (text) {
                CarPlayChannel.CHANNEL_CARPLAY.toString(), "cp", "carplay" -> {
                    mirrorSettings(app, CarPlayChannel.CHANNEL_CARPLAY)
                    return true
                }
                CarPlayChannel.CHANNEL_CARPLAY_USB.toString(), "cp-usb", "carplay-usb", "usb" -> {
                    if (CarPlayChannel.USB_CARPLAY_ENABLED) {
                        mirrorSettings(app, CarPlayChannel.CHANNEL_CARPLAY_USB)
                        return true
                    }
                    val fallback = CarPlayChannel.defaultChannel(app)
                    mirrorSettings(app, fallback)
                    return fallback == CarPlayChannel.CHANNEL_CARPLAY ||
                        fallback == CarPlayChannel.CHANNEL_AAP
                }
                CarPlayChannel.CHANNEL_AAP.toString(), "aap", "native-aa" -> {
                    // Native AAP is armed from the app process (AapProjectionService),
                    // not system_server cafe RFCOMM — BootArm only mirrors settings.
                    mirrorSettings(app, CarPlayChannel.CHANNEL_AAP)
                    return false
                }
                CarPlayChannel.CHANNEL_AA.toString(), "aa" -> {
                    mirrorSettings(app, CarPlayChannel.CHANNEL_AA)
                    return false
                }
            }
        }
        // No persisted choice yet: AA installed → AAP; else CarPlay.
        val def = CarPlayChannel.defaultChannel(app)
        if (def == CarPlayChannel.CHANNEL_CARPLAY) {
            mirrorSettings(app, CarPlayChannel.CHANNEL_CARPLAY)
            return true
        }
        if (def == CarPlayChannel.CHANNEL_AAP) {
            mirrorSettings(app, CarPlayChannel.CHANNEL_AAP)
        }
        return false
    }

    private fun mirrorSettings(app: Context, channel: Int) {
        try {
            Settings.Global.putInt(app.contentResolver, SETTINGS_CHANNEL, channel)
        } catch (_: Throwable) {
        }
    }
}
