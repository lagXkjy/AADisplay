package io.github.nitsuya.aa.display.aap.usb

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import io.github.nitsuya.aa.display.aap.AapConsts
import io.github.nitsuya.aa.display.aap.AapProjectionService
import io.github.nitsuya.aa.display.aap.cert.PhoneCertStore
import io.github.nitsuya.aa.display.carplay.CarPlayChannel
import kotlin.concurrent.thread

/**
 * AOA USB entry for wired Android Auto head units
 * (manufacturer "Android", model "Android Auto").
 *
 * Flow: force CHANNEL_AAP → start FGS → ensure CarService leaf → open accessory →
 * hand transport once to [AapSourceSession] (sendPings=false).
 */
class AapUsbAccessoryActivity : Activity() {
    @Volatile private var permissionReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val accessory = intent?.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
        if (accessory == null) {
            Log.w(AapConsts.TAG, "USB accessory intent missing EXTRA_ACCESSORY")
            finish()
            return
        }
        CarPlayChannel.set(this, CarPlayChannel.CHANNEL_AAP)
        AapProjectionService.start(this)
        val app = applicationContext
        thread(name = "aap-usb-open", isDaemon = true) {
            var transport: UsbAccessoryTransport? = null
            try {
                // Warm cert before openAccessory — HU may time out if TLS waits on DEX extract.
                PhoneCertStore.ensureExtracted(app, force = false)
                val armedDeadline = SystemClock.elapsedRealtime() + SESSION_WAIT_MS
                while (!AapProjectionService.isSessionArmed() &&
                    SystemClock.elapsedRealtime() < armedDeadline
                ) {
                    Thread.sleep(100)
                }
                if (!AapProjectionService.isSessionArmed()) {
                    Log.w(AapConsts.TAG, "USB AAP: FGS session not armed in time")
                }

                val usb = app.getSystemService(UsbManager::class.java)
                    ?: throw IllegalStateException("UsbManager missing")
                if (!usb.hasPermission(accessory)) {
                    Log.i(AapConsts.TAG, "USB AAP requesting accessory permission")
                    if (!requestAccessoryPermission(app, usb, accessory)) {
                        Log.w(AapConsts.TAG, "USB AAP permission denied")
                        return@thread
                    }
                }
                transport = UsbAccessoryTransport(usb, accessory)
                val peer = "usb:${accessory.manufacturer}:${accessory.model}:${accessory.version}"
                val accepted = AapProjectionService.attachTransport(
                    transport,
                    peerKey = peer,
                    trustOnFirstUse = true,
                )
                Log.i(
                    AapConsts.TAG,
                    "USB AAP attach accepted=$accepted name=${transport.name}",
                )
                val liveDeadline = SystemClock.elapsedRealtime() + BIND_WAIT_MS
                while (!AapProjectionService.isProtocolLive() &&
                    SystemClock.elapsedRealtime() < liveDeadline
                ) {
                    // If FGS was late, pending queue is drained on ensureSession.
                    if (!accepted) {
                        AapProjectionService.attachTransport(transport, peer, trustOnFirstUse = true)
                    }
                    Thread.sleep(200)
                }
                if (!AapProjectionService.isProtocolLive()) {
                    Log.w(AapConsts.TAG, "USB AAP protocol not live; closing accessory")
                    transport.close()
                } else {
                    Log.i(AapConsts.TAG, "USB AAP protocol live")
                }
            } catch (e: Throwable) {
                Log.e(AapConsts.TAG, "USB accessory open failed", e)
                runCatching { transport?.close() }
            } finally {
                unregisterPermissionReceiver(app)
                runOnUiThread { finish() }
            }
        }
    }

    override fun onDestroy() {
        unregisterPermissionReceiver(applicationContext)
        super.onDestroy()
    }

    private fun requestAccessoryPermission(
        app: Context,
        usb: UsbManager,
        accessory: UsbAccessory,
    ): Boolean {
        val lock = Object()
        var granted: Boolean? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_PERMISSION) return
                val ok = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                synchronized(lock) {
                    granted = ok
                    lock.notifyAll()
                }
            }
        }
        permissionReceiver = receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        val pi = PendingIntent.getBroadcast(
            app,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(app.packageName),
            PendingIntent.FLAG_MUTABLE,
        )
        usb.requestPermission(accessory, pi)
        val waitUntil = SystemClock.elapsedRealtime() + PERMISSION_TIMEOUT_MS
        synchronized(lock) {
            while (granted == null && SystemClock.elapsedRealtime() < waitUntil) {
                val left = waitUntil - SystemClock.elapsedRealtime()
                if (left <= 0) break
                lock.wait(left.coerceAtMost(500))
            }
        }
        return granted == true || usb.hasPermission(accessory)
    }

    private fun unregisterPermissionReceiver(app: Context) {
        val r = permissionReceiver ?: return
        permissionReceiver = null
        runCatching { app.unregisterReceiver(r) }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "io.github.nitsuya.aa.display.aap.USB_ACCESSORY_PERM"
        private const val SESSION_WAIT_MS = 15_000L
        private const val BIND_WAIT_MS = 25_000L
        private const val PERMISSION_TIMEOUT_MS = 30_000L
    }
}
