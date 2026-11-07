package io.github.nitsuya.aa.display.aap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.aap.cert.PhoneCertStore
import io.github.nitsuya.aa.display.aap.usb.AapUsbProbe
import io.github.nitsuya.aa.display.carplay.CarPlayChannel
import io.github.nitsuya.aa.display.ui.main.MainActivity
import net.prototux.aare.aap.Transport
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service owning [AapSourceSession] for native AAP egress (no gearhead hooks).
 */
class AapProjectionService : Service() {
    companion object {
        private val sessionRef = AtomicReference<AapSourceSession?>(null)
        private val pendingTransport = AtomicReference<PendingTransport?>(null)
        private val attachLock = Any()
        @Volatile private var lastStatus: String = "未启动"

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

        fun lastStatus(): String = lastStatus

        /**
         * Hand an already-open transport (USB AOA / lab TCP) to the live session.
         *
         * @return true if the session accepted / already owns [transport]; false if queued
         *         until [ensureSession] drains (caller may wait on [isProtocolLive]).
         */
        fun attachTransport(
            transport: Transport,
            peerKey: String,
            trustOnFirstUse: Boolean = true,
        ): Boolean {
            synchronized(attachLock) {
                val s = sessionRef.get()
                if (s != null) {
                    if (s.isBoundTo(transport)) return true
                    pendingTransport.getAndSet(null)
                    // Run outside lock? extract can be slow — release before bind.
                } else {
                    pendingTransport.set(PendingTransport(transport, peerKey, trustOnFirstUse))
                    return false
                }
            }
            val s = sessionRef.get() ?: return false
            if (s.isBoundTo(transport)) return true
            s.startOnTransport(transport, peerKey, trustOnFirstUse)
            return true
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
                if (!CarPlayChannel.useAapEgress(this)) {
                    CarPlayChannel.set(this, CarPlayChannel.CHANNEL_AAP)
                }
                ensureForeground()
                ensureSession()
            }
            else -> {
                if (CarPlayChannel.useAapEgress(this)) {
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
        if (sessionRef.get() != null) {
            drainPendingTransport()
            // Session may have been armed before DHU entered accessory mode — keep probing.
            AapUsbProbe.tryAttachExisting(this)
            return
        }
        val s = AapSourceSession(this) { status ->
            lastStatus = status
            updateNotification(status)
            Log.i(AapConsts.TAG, "status: $status")
        }
        session = s
        sessionRef.set(s)
        s.start()
        lastStatus = "已启动"
        updateNotification(lastStatus)
        drainPendingTransport()
        // DHU may already be in accessory mode with a missed ATTACHED (wrong filter / UriActivity).
        AapUsbProbe.tryAttachExisting(this)
    }

    private fun tearDown(reason: String) {
        Log.i(AapConsts.TAG, "AapProjectionService tearDown $reason")
        pendingTransport.getAndSet(null)?.let { runCatching { it.transport.close() } }
        sessionRef.getAndSet(null)?.stop()
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
