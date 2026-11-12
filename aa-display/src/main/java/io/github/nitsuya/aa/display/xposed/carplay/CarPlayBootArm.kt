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
import io.github.nitsuya.aa.display.egress.EgressChannel
import io.github.nitsuya.aa.display.carplay.CarPlayConsts
import io.github.nitsuya.aa.display.util.EgressChannelMirror
import io.github.nitsuya.aa.display.xposed.util.log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * system_server early arm for wireless CarPlay Source.
 *
 * All background FGS arming lives here (system context, no background
 * restrictions): BT ON / user-unlocked / boot / package-replace. The app-side
 * manifest receivers were removed — arming a `connectedDevice` FGS from app
 * background (adapter off, no device connected) is force-killed on Android 13,
 * the 5s FGS deadline then expires with the process dead and the pending
 * `ForegroundServiceDidNotStartInTimeException` poisons every process incarnation
 * the next FGS start spawns (crash loop until consumed).
 *
 * Some OEMs enable Bluetooth (and classic HU reconnect) ~15–20s before
 * [Intent.ACTION_BOOT_COMPLETED] starts the app — the HU
 * SDP for `…cafe` fails (`scn=0`) and backs off; the second connect works after cafe
 * is finally listening. This watch runs in system_server (no background-broadcast
 * ban) and starts the app FGS as soon as BT is ON and CE storage is unlocked,
 * when the user last chose CarPlay.
 *
 * Channel signal: [EgressChannelMirror] (`/data/system/…`, written via Binder).
 * No Settings.Global.
 *
 * Foreground re-arm (status page open) stays app-side: CarPlayStatusUi /
 * ChannelOrchestrator.ensureArmedCurrent.
 */
object CarPlayBootArm {
    private const val TAG = "AAD_CpBootArm"
    private const val TARGET_PACKAGE = "io.github.nitsuya.aa.display"
    private const val SERVICE_CLASS =
        "io.github.nitsuya.aa.display.carplay.CarPlayProjectionService"
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
                    Intent.ACTION_PACKAGE_REPLACED -> {
                        if (intent.data?.schemeSpecificPart != TARGET_PACKAGE) return
                        tryArm(app, "pkg-replaced")
                    }
                }
            }
        }
        receiver = rx
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_USER_UNLOCKED)
            addAction(Intent.ACTION_BOOT_COMPLETED)
            // Package-replace re-arm moved here with the app-side receivers:
            // `adb install -r` kills the app FGS. MY_PACKAGE_REPLACED is scoped to the
            // replaced package (never delivered to system_server) — use the system-wide
            // PACKAGE_REPLACED and guard by package name in onReceive.
            addAction(Intent.ACTION_PACKAGE_REPLACED)
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
            app.startForegroundService(intent)
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
     * Best-effort read of the app's channel file (often unreadable from
     * system_server — 0700 CE). Null when missing or denied.
     */
    @SuppressLint("SdCardPath")
    private fun readChannelFile(): Int? {
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
            val parsed = when (text) {
                EgressChannel.CHANNEL_CARPLAY.toString(), "cp", "carplay" -> EgressChannel.CHANNEL_CARPLAY
                EgressChannel.CHANNEL_CARPLAY_USB.toString(), "cp-usb", "carplay-usb", "usb" -> EgressChannel.CHANNEL_CARPLAY_USB
                EgressChannel.CHANNEL_AAP.toString(), "aap", "native-aa" -> EgressChannel.CHANNEL_AAP
                EgressChannel.CHANNEL_AA.toString(), "aa" -> EgressChannel.CHANNEL_AA
                else -> null
            }
            if (parsed != null) return parsed
        }
        return null
    }

    private fun isCarPlayChannelSelected(app: Context): Boolean {
        // Primary: Binder-synced /data/system mirror (survives reboot).
        when (EgressChannelMirror.read()) {
            EgressChannel.CHANNEL_CARPLAY -> return true
            EgressChannel.CHANNEL_AAP -> return false
        }
        // First boot / pre-mirror: best-effort app file (often unreadable from uid 1000).
        when (readChannelFile()) {
            EgressChannel.CHANNEL_CARPLAY -> {
                EgressChannelMirror.write(EgressChannel.CHANNEL_CARPLAY)
                return true
            }
            EgressChannel.CHANNEL_AAP -> {
                EgressChannelMirror.write(EgressChannel.CHANNEL_AAP)
                return false
            }
            EgressChannel.CHANNEL_CARPLAY_USB, EgressChannel.CHANNEL_AA -> {
                val fallback = EgressChannel.defaultChannel(app)
                EgressChannelMirror.write(fallback)
                return fallback == EgressChannel.CHANNEL_CARPLAY
            }
        }
        // No persisted choice yet: AA installed → AAP; else CarPlay.
        val def = EgressChannel.defaultChannel(app)
        EgressChannelMirror.write(def)
        return def == EgressChannel.CHANNEL_CARPLAY
    }
}
