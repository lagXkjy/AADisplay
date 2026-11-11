package io.github.nitsuya.aa.display.aap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.aap.cert.PhoneCertStore
import io.github.nitsuya.aa.display.aap.usb.AapUsbProbe
import io.github.nitsuya.aa.display.egress.EgressChannel
import io.github.nitsuya.aa.display.egress.ProjectionEgress
import io.github.nitsuya.aa.display.ui.main.MainActivity
import net.prototux.aare.aap.Transport
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service owning [AapSourceSession] for native AAP egress (no gearhead hooks).
 */
class AapProjectionService : Service() {
    companion object : ProjectionEgress {
        private val sessionRef = AtomicReference<AapSourceSession?>(null)
        private val pendingTransport = AtomicReference<PendingTransport?>(null)
        /** Live FGS instance for receiver-driven re-arms; nulled on destroy. */
        private val serviceRef = AtomicReference<AapProjectionService?>(null)
        private val attachLock = Any()
        @Volatile private var lastStatus: String = "未启动"

        private const val USB_PROBE_WINDOW_MS = 20_000L
        private const val USB_PROBE_INTERVAL_MS = 500L
        /** API 31+; string keeps older compile SDKs from needing a hard reference. */
        private const val ACTION_USB_ACCESSORY_HANDSHAKE =
            "android.hardware.usb.action.USB_ACCESSORY_HANDSHAKE"

        private data class PendingTransport(
            val transport: Transport,
            val peerKey: String,
            val trustOnFirstUse: Boolean,
        )

        fun start(context: Context) {
            val i = Intent(context, AapProjectionService::class.java)
                .setAction(AapConsts.ACTION_CONNECT)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, AapProjectionService::class.java)
                .setAction(AapConsts.ACTION_STOP)
            context.startService(i)
        }

        fun isSessionArmed(): Boolean = sessionRef.get() != null

        fun isProtocolLive(): Boolean = sessionRef.get()?.isProtocolLive() == true

        fun hasActiveTransport(): Boolean =
            sessionRef.get()?.hasActiveTransport() == true || pendingTransport.get() != null

        fun lastStatus(): String = lastStatus

        /** Soft USB probe must wait for unplug after TLS desync / crypto fault. */
        fun isUsbReplugRequired(): Boolean = sessionRef.get()?.isUsbReplugRequired() == true

        /** Soft USB probe must wait out the killed transport's teardown (drain join + endpoint re-settle). */
        fun isUsbKillCooling(): Boolean = sessionRef.get()?.isInUsbKillCooldown() == true

        /**
         * Clear the USB replug gate (DETACH or a fresh ATTACHED entry).
         * Soft [AapUsbProbe] stays blocked until this runs.
         */
        fun clearUsbReplugRequired() {
            sessionRef.get()?.clearUsbReplugRequired()
        }

        /**
         * [A10] Session-side USB ATTACHED hook: the FGS receiver is OEM-dependent, the
         * source session's app-context receiver is the second listener — re-arm the probe
         * window when the FGS is alive, the channel is AAP, and nothing is bound yet.
         */
        fun onUsbAccessoryAttached() {
            val s = serviceRef.get() ?: return
            s.onUsbAccessoryAttached()
        }

        /**
         * Hand an already-open transport (USB AOA / lab TCP) to the live session.
         *
         * @param fromAccessoryAttached true when coming from [USB_ACCESSORY_ATTACHED]
         *        (clears the replug gate before bind).
         * @return true if the session accepted / already owns [transport]; false if queued
         *         until [ensureSession] drains, or rejected (another live link owns the
         *         session / the session is stopped — the transport is then closed).
         */
        fun attachTransport(
            transport: Transport,
            peerKey: String,
            trustOnFirstUse: Boolean = true,
            fromAccessoryAttached: Boolean = false,
        ): Boolean {
            if (fromAccessoryAttached) {
                clearUsbReplugRequired()
            }
            synchronized(attachLock) {
                val s = sessionRef.get()
                if (s != null) {
                    if (s.isBoundTo(transport)) return true
                    val dropped = pendingTransport.getAndSet(null)
                    // [A11] a queued transport for another (dead) moment must not leak its
                    // fd + drain thread — unless it IS the transport we are about to bind.
                    if (dropped != null && dropped.transport !== transport) {
                        runCatching { dropped.transport.close() }
                    }
                    // The bind itself runs outside this lock (cert extract can be slow).
                } else {
                    pendingTransport.set(PendingTransport(transport, peerKey, trustOnFirstUse))
                    return false
                }
            }
            val s = sessionRef.get() ?: return false
            if (s.isBoundTo(transport)) return true
            return s.startOnTransport(transport, peerKey, trustOnFirstUse)
        }

        // --- ProjectionEgress backend (neutral egress layer; ChannelOrchestrator only sees this) ---

        override val channelId: Int get() = EgressChannel.CHANNEL_AAP

        override fun isArmed(): Boolean = isSessionArmed()

        /** Pre-arm: cert extract → FGS → USB probe (same steps as the pre-refactor orchestrator path). */
        override fun arm(context: Context) {
            PhoneCertStore.ensureExtracted(context, force = false)
            // Heal BootArm /data/system mirror (Settings.Global often stale/blocked).
            EgressChannel.mirrorToSystem(EgressChannel.CHANNEL_AAP)
            start(context)
            AapUsbProbe.tryAttachExisting(context)
        }

        override fun stopAndAwait(context: Context, timeoutMs: Long, pollMs: Long) {
            if (!isSessionArmed()) return
            Log.i(AapConsts.TAG, "AAP stop (await)")
            stop(context)
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (isSessionArmed()) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    Log.w(AapConsts.TAG, "AAP stopAndAwait timeout — proceeding")
                    return
                }
                try {
                    Thread.sleep(pollMs)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }

        private fun drainPendingTransport() {
            val pending = synchronized(attachLock) {
                pendingTransport.getAndSet(null)
            } ?: return
            val s = sessionRef.get()
            if (s == null) {
                runCatching { pending.transport.close() }
                Log.w(AapConsts.TAG, "drop pending USB transport — session missing")
                return
            }
            if (s.isBoundTo(pending.transport)) return
            Log.i(AapConsts.TAG, "drain pending USB transport peer=${pending.peerKey}")
            s.startOnTransport(pending.transport, pending.peerKey, pending.trustOnFirstUse)
        }
    }

    private var session: AapSourceSession? = null
    private var usbDetachRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var usbProbeUntilElapsed = 0L
    /**
     * [A10] Second listener for AOA ATTACHED: the FGS receiver below is OEM-dependent;
     * the source session's app-context receiver forwards here. Re-arms the probe window
     * so a replug is answered even when the FGS copy of the broadcast is swallowed.
     */
    fun onUsbAccessoryAttached() {
        if (!EgressChannel.useAapEgress(this)) return
        if (sessionRef.get() == null) return
        if (isProtocolLive() || hasActiveTransport()) return
        Log.i(AapConsts.TAG, "USB attached (session rx) — re-arm probe window")
        scheduleUsbProbeWindow("attached-session-rx")
    }
    private val usbProbeTick = object : Runnable {
        override fun run() {
            if (!EgressChannel.useAapEgress(this@AapProjectionService)) return
            if (isProtocolLive() || hasActiveTransport()) return
            AapUsbProbe.tryAttachExisting(this@AapProjectionService)
            if (SystemClock.elapsedRealtime() < usbProbeUntilElapsed) {
                mainHandler.postDelayed(this, USB_PROBE_INTERVAL_MS)
            }
        }
    }

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    Log.i(AapConsts.TAG, "USB accessory detached — clear replug gate")
                    clearUsbReplugRequired()
                }
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    // Prefer Activity path; this is a safety net when ATTACHED is also delivered
                    // as a broadcast to the armed FGS (OEM-dependent).
                    Log.i(AapConsts.TAG, "USB accessory attached broadcast — probe")
                    clearUsbReplugRequired()
                    scheduleUsbProbeWindow("attached-broadcast")
                }
                else -> {
                    // API 31+: USB_ACCESSORY_HANDSHAKE — AOA START / timeout telemetry.
                    if (intent?.action == ACTION_USB_ACCESSORY_HANDSHAKE) {
                        Log.i(AapConsts.TAG, "USB accessory handshake — probe")
                        scheduleUsbProbeWindow("handshake")
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AapConsts.ACTION_STOP -> {
                tearDown("stop-intent")
                stopSelf()
                return START_NOT_STICKY
            }
            AapConsts.ACTION_EXTRACT_CERT -> {
                ensureForeground()
                PhoneCertStore.ensureExtracted(this, force = true)
            }
            AapConsts.ACTION_CONNECT, null -> {
                // Symmetric with CarPlayProjectionService: a stray start while the user is
                // on CarPlay must self-stop, not rewrite the channel back to AAP and steal
                // the shared split VDs from a live CarPlay session.
                if (!EgressChannel.useAapEgress(this)) {
                    tearDown("channel-not-aap")
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureForeground()
                ensureSession()
            }
            else -> {
                if (EgressChannel.useAapEgress(this)) {
                    ensureForeground()
                    ensureSession()
                } else {
                    tearDown("channel-other")
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    private fun ensureSession() {
        registerUsbDetachReceiver()
        if (sessionRef.get() != null) {
            drainPendingTransport()
            // Session may have been armed before DHU entered accessory mode — keep probing.
            scheduleUsbProbeWindow("ensure-existing")
            return
        }
        val s = AapSourceSession(this) { status ->
            lastStatus = status
            updateNotification(status)
            Log.i(AapConsts.TAG, "status: $status")
        }
        session = s
        sessionRef.set(s)
        serviceRef.set(this)
        s.start()
        lastStatus = "已启动"
        updateNotification(lastStatus)
        drainPendingTransport()
        // DHU may already be in accessory mode with a missed ATTACHED (wrong filter / UriActivity).
        // Also cover the race where AOA START arrives a few seconds after FGS arm.
        scheduleUsbProbeWindow("ensure-new")
    }

    /**
     * Poll [UsbManager.getAccessoryList] for a short window. DHU/AOA often appears after the
     * first probe; without a retry the kernel hits ACCESSORY_MODE_ENTER_TIMEOUT (~10s) and
     * DHU reports Attached → immediate read/write fail.
     */
    private fun scheduleUsbProbeWindow(reason: String) {
        val until = SystemClock.elapsedRealtime() + USB_PROBE_WINDOW_MS
        if (until > usbProbeUntilElapsed) usbProbeUntilElapsed = until
        mainHandler.removeCallbacks(usbProbeTick)
        Log.i(AapConsts.TAG, "USB probe window start reason=$reason for ${USB_PROBE_WINDOW_MS}ms")
        mainHandler.post(usbProbeTick)
    }

    private fun registerUsbDetachReceiver() {
        if (usbDetachRegistered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(ACTION_USB_ACCESSORY_HANDSHAKE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbDetachReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbDetachReceiver, filter)
        }
        usbDetachRegistered = true
    }

    private fun unregisterUsbDetachReceiver() {
        if (!usbDetachRegistered) return
        runCatching { unregisterReceiver(usbDetachReceiver) }
        usbDetachRegistered = false
    }

    private fun tearDown(reason: String) {
        Log.i(AapConsts.TAG, "AapProjectionService tearDown $reason")
        mainHandler.removeCallbacks(usbProbeTick)
        usbProbeUntilElapsed = 0L
        unregisterUsbDetachReceiver()
        pendingTransport.getAndSet(null)?.let { runCatching { it.transport.close() } }
        // [A15] Detach the instance *before* stop: an in-flight attachTransport on a stale
        // reference must see the flag in its bind critical section and refuse to rebind.
        sessionRef.get()?.detached = true
        sessionRef.getAndSet(null)?.stop()
        serviceRef.set(null)
        session = null
        lastStatus = "已停止"
    }

    private fun ensureForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                AapConsts.NOTIFICATION_CHANNEL_ID,
                "原生 Android Auto",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, AapConsts.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("原生 Android Auto (AAP)")
            .setContentText(lastStatus)
            .setSmallIcon(R.drawable.ic_round_check_circle_24)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(
            0xAAD2,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val pi = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(this, AapConsts.NOTIFICATION_CHANNEL_ID)
                .setContentTitle("原生 Android Auto (AAP)")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_round_check_circle_24)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
            nm.notify(0xAAD2, notification)
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        tearDown("destroy")
        super.onDestroy()
    }
}
