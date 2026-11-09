package io.github.nitsuya.aa.display.carplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.ui.main.MainActivity
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service owning [CarPlaySourceSession] for wireless CarPlay Source egress.
 * CarPlay paints only via Host GL.
 */
class CarPlayProjectionService : Service() {
    companion object {
        private val sessionRef = AtomicReference<CarPlaySourceSession?>(null)
        private val glCompositorRef = AtomicReference<CarPlayGlCompositor?>(null)

        /**
         * CarPlay-only Host GPU compositor (SurfaceTexture → GLES → encoder).
         * Null detaches; session will fall back to [FrameEgressTee] Canvas pump.
         */
        fun attachGlCompositor(compositor: CarPlayGlCompositor?) {
            glCompositorRef.set(compositor)
            sessionRef.get()?.attachGlCompositor(compositor)
        }

        fun start(context: Context, launchHostTrampoline: Boolean = true) {
            val i = Intent(context, CarPlayProjectionService::class.java)
                .setAction(CarPlayConsts.ACTION_CONNECT)
                .putExtra(EXTRA_LAUNCH_HOST_TRAMPOLINE, launchHostTrampoline)
            context.startForegroundService(i)
        }

        private const val EXTRA_LAUNCH_HOST_TRAMPOLINE = "launchHostTrampoline"

        /** True once [ensureSession] created a live [CarPlaySourceSession] (RFCOMM listening). */
        fun isSessionArmed(): Boolean = sessionRef.get() != null

        fun stop(context: Context) {
            val i = Intent(context, CarPlayProjectionService::class.java).setAction(CarPlayConsts.ACTION_STOP)
            context.startService(i)
        }

        /**
         * Status-page: show the 300s discoverable dialog so the HU can find the phone
         * without opening Bluetooth Settings.
         */
        fun requestDiscoverable(context: Context) {
            val session = sessionRef.get()
            if (session != null) {
                session.requestDiscoverableDialog()
            } else {
                CarPlayBluetoothLink.launchDiscoverableDialog(context)
            }
        }
    }

    private var session: CarPlaySourceSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CarPlayConsts.ACTION_STOP -> {
                // XOR egress: user stopped while still on CarPlay → fall back to default
                // (AAP if AA installed). Skip rewrite when ChannelOrchestrator already
                // persisted AAP / another channel — otherwise STOP flaps the file mid-switch.
                if (CarPlayChannel.useCarPlayEgress(this) &&
                    CarPlayChannel.isAndroidAutoInstalled(this)
                ) {
                    CarPlayChannel.set(this, CarPlayChannel.defaultChannel(this))
                }
                tearDown("stop-intent")
                stopSelf()
                return START_NOT_STICKY
            }
            CarPlayConsts.ACTION_CONNECT -> {
                // Honor the persisted selection: a stray CONNECT (system_server BootArm on a
                // stale Settings mirror, trampoline recreate, sticky re-delivery) must NOT
                // rewrite the user's choice back to CarPlay — that re-arms CP over a live
                // AAP session and rebinds the shared split VDs (audio up / video black).
                if (!CarPlayChannel.useCarPlayEgress(this)) {
                    tearDown("channel-not-carplay")
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureForeground()
                val launchHost = intent.getBooleanExtra(EXTRA_LAUNCH_HOST_TRAMPOLINE, true)
                safeEnsureSession(launchHostTrampoline = launchHost)
            }
            // System restart / sticky re-delivery: only re-arm when the user chose CarPlay.
            else -> {
                if (CarPlayChannel.useCarPlayEgress(this)) {
                    ensureForeground()
                    safeEnsureSession(launchHostTrampoline = true)
                } else {
                    tearDown("channel-aa")
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    /**
     * Missing BT runtime perms used to throw out of [onStartCommand] → process death →
     * sticky restart crash loop. Keep the FGS up; RFCOMM soft-fails until perms are granted.
     */
    private fun safeEnsureSession(launchHostTrampoline: Boolean) {
        try {
            ensureSession(launchHostTrampoline = launchHostTrampoline)
        } catch (e: SecurityException) {
            Log.e(CarPlayConsts.TAG, "ensureSession SecurityException — grant BT perms", e)
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "ensureSession failed", e)
        }
    }

    private fun ensureForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CarPlayConsts.NOTIFICATION_CHANNEL_ID,
                "CarPlay 投屏",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CarPlayConsts.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AADisplay CarPlay")
            .setContentText("已就绪，等待车机连接")
            .setSmallIcon(R.drawable.ic_round_check_circle_24)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                CarPlayConsts.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(CarPlayConsts.NOTIFICATION_ID, notification)
        }
    }

    private fun ensureSession(launchHostTrampoline: Boolean = true) {
        if (session != null) {
            // Soft re-arm (AutoArm ladder / sticky restart): keep the existing
            // SourceSession + RFCOMM / RTSP listen. Do not re-report live or boot Host —
            // idle AutoArm used to spin MediaCodec + dual VDs with no HU attached.
            Log.d(CarPlayConsts.TAG, "CarPlayProjectionService already armed — keep listen")
            return
        }
        Log.i(CarPlayConsts.TAG, "CarPlayProjectionService CONNECT (listen-only)")
        // Lightweight arm: BT cafe + RTSP listen only. Host GL / dual VD / MediaCodec /
        // audio capture arm later in CarPlaySourceSession.prepareMediaPipeline when the
        // HU actually dials (or screen SETUP arrives).
        // Drop orphan VDs left by a prior force-stop / crash (apps kept rendering at 60fps).
        CarPlayHostRuntime.releaseOrphansIfIdle("listen-arm")
        val s = CarPlaySourceSession(applicationContext)
        session = s
        sessionRef.set(s)
        // If Host was already warm (manual prior session), reuse the compositor.
        (glCompositorRef.get() ?: CarPlayHostRuntime.compositorOrNull())?.let {
            glCompositorRef.set(it)
            s.attachGlCompositor(it)
        }
        s.start()
        // Optional trampoline only when an explicit UI path asked for it — AutoArm /
        // BootArm pass false so we never bring up HostActivity on reboot.
        if (launchHostTrampoline) {
            CarPlayHostActivity.launch(this)
        }
    }

    private fun tearDown(reason: String) {
        Log.i(CarPlayConsts.TAG, "CarPlayProjectionService tearDown reason=$reason")
        session?.stop()
        session = null
        sessionRef.set(null)
        CarPlayHostRuntime.stop(reason)
        // Report aggregate live — do not force false if AAP already acquired the bit.
        reportAggregateLive()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun reportAggregateLive() {
        try {
            CoreApi.reportCarPlaySessionLive(
                io.github.nitsuya.aa.display.egress.ProjectionSessionGate.isCarPlayLive(),
            )
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "reportCarPlaySessionLive failed", e)
        }
    }

    override fun onDestroy() {
        tearDown("service-destroy")
        super.onDestroy()
    }
}
