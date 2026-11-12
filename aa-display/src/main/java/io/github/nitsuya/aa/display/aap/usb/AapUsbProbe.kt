package io.github.nitsuya.aa.display.aap.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.nitsuya.aa.display.aap.AapConsts
import io.github.nitsuya.aa.display.aap.AapProjectionService
import io.github.nitsuya.aa.display.aap.cert.PhoneCertStore
import io.github.nitsuya.aa.display.egress.EgressChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Attach an already-present AOA accessory (DHU / factory HU) when the
 * [USB_ACCESSORY_ATTACHED] intent was missed (wrong filter, UriActivity, etc.).
 *
 * Requests [UsbManager] permission when needed — without it [UsbManager.openAccessory]
 * always fails and the session stays on wireless listen.
 */
object AapUsbProbe {
    private const val ACTION_PERM = "io.github.nitsuya.aa.display.aap.USB_PROBE_PERM"
    private val busy = AtomicBoolean(false)

    fun tryAttachExisting(context: Context) {
        if (!EgressChannel.useAapEgress(context)) return
        if (AapProjectionService.isProtocolLive()) {
            Log.i(AapConsts.TAG, "USB probe: AAP already live — skip")
            return
        }
        // Handshake in progress (TLS / service discovery) — do not openAccessory again.
        if (AapProjectionService.hasActiveTransport()) {
            Log.i(AapConsts.TAG, "USB probe: transport already bound — skip")
            return
        }
        if (AapProjectionService.isUsbReplugRequired()) {
            Log.i(AapConsts.TAG, "USB probe: blocked until unplug/replug (TLS fault)")
            return
        }
        // A stall/teardown kill just closed the previous transport: its drain thread is still
        // joining and the endpoint re-binding — a second openAccessory on the same port would
        // read the pipe in parallel and tear the stream apart. The 8 s soft re-attach owns the
        // recovery; this window only keeps the probe from racing it.
        if (AapProjectionService.isUsbKillCooling()) {
            Log.i(AapConsts.TAG, "USB probe: cooling down after kill — skip")
            return
        }
        if (!busy.compareAndSet(false, true)) {
            Log.i(AapConsts.TAG, "USB probe: already in progress")
            return
        }
        val app = context.applicationContext
        thread(name = "aap-usb-probe", isDaemon = true) {
            try {
                probeOnce(app)
            } catch (e: Throwable) {
                Log.e(AapConsts.TAG, "USB probe failed", e)
            } finally {
                busy.set(false)
            }
        }
    }

    private fun probeOnce(app: Context) {
        val usb = app.getSystemService(UsbManager::class.java) ?: return
        val list = usb.accessoryList
        if (list.isNullOrEmpty()) {
            Log.i(AapConsts.TAG, "USB probe: no accessories")
            return
        }
        val target = list.firstOrNull { UsbAccessoryTransport.isAndroidAuto(it) } ?: run {
            Log.i(
                AapConsts.TAG,
                "USB probe: accessories present but none match AA/DHU " +
                    list.joinToString { "${it.manufacturer}/${it.model}" },
            )
            return
        }
        Log.i(
            AapConsts.TAG,
            "USB probe: found ${target.manufacturer}/${target.model}/${target.version} " +
                "perm=${usb.hasPermission(target)}",
        )
        if (!usb.hasPermission(target)) {
            if (!requestPermissionBlocking(app, usb, target)) {
                Log.w(AapConsts.TAG, "USB probe: permission denied — unlock phone and allow USB accessory")
                return
            }
        }
        if (AapProjectionService.isProtocolLive() || AapProjectionService.hasActiveTransport()) return
        // Hold the AOA fd before cert extract — system exits accessory mode ~10s without open.
        val transport = UsbAccessoryTransport(usb, target)
        val peer = "usb:${target.manufacturer}:${target.model}:${target.version}"
        Log.i(AapConsts.TAG, "USB probe: accessory opened name=${transport.name}")
        runCatching { PhoneCertStore.ensureExtracted(app, force = false) }
            .onFailure { Log.e(AapConsts.TAG, "USB probe: cert extract failed", it) }
        val ok = AapProjectionService.attachTransport(transport, peer, trustOnFirstUse = true)
        Log.i(AapConsts.TAG, "USB probe: attachTransport accepted=$ok")
        if (!ok) {
            AapProjectionService.start(app)
            val deadline = SystemClock.elapsedRealtime() + 15_000L
            while (!AapProjectionService.isProtocolLive() &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                AapProjectionService.attachTransport(transport, peer, trustOnFirstUse = true)
                Thread.sleep(200)
            }
        }
        // [A5] if nobody ended up holding the opened AOA fd (FGS never armed / channel
        // switched away), release it — the drain thread must not outlive the attempt.
        if (!AapProjectionService.hasActiveTransport()) {
            runCatching { transport.close() }
            Log.i(AapConsts.TAG, "USB probe: closing unbound transport")
        }
        Log.i(
            AapConsts.TAG,
            "USB probe: done live=${AapProjectionService.isProtocolLive()} " +
                "status=${AapProjectionService.lastStatus()}",
        )
    }

    private fun requestPermissionBlocking(
        app: Context,
        usb: UsbManager,
        accessory: UsbAccessory,
    ): Boolean {
        val lock = Object()
        var granted: Boolean? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_PERM) return
                val ok = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                synchronized(lock) {
                    granted = ok
                    lock.notifyAll()
                }
            }
        }
        val filter = IntentFilter(ACTION_PERM)
        app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        try {
            val pi = PendingIntent.getBroadcast(
                app,
                1,
                Intent(ACTION_PERM).setPackage(app.packageName),
                PendingIntent.FLAG_MUTABLE,
            )
            Log.i(AapConsts.TAG, "USB probe: requesting accessory permission")
            usb.requestPermission(accessory, pi)
            val waitUntil = SystemClock.elapsedRealtime() + 45_000L
            synchronized(lock) {
                while (granted == null && SystemClock.elapsedRealtime() < waitUntil) {
                    val left = waitUntil - SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    lock.wait(left.coerceAtMost(500))
                }
            }
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
        }
        return granted == true || usb.hasPermission(accessory)
    }
}
