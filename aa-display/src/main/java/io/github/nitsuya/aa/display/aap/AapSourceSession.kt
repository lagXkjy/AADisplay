package io.github.nitsuya.aa.display.aap

import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.control.message.AudioFocusNotificationOuterClass.AudioFocusNotification
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.inputsource.message.PointerActionOuterClass.PointerAction
import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.sink.message.VideoCodecResolutionTypeOuterClass.VideoCodecResolutionType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.sink.message.VideoFrameRateTypeOuterClass.VideoFrameRateType
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.aap.cert.PhoneCertStore
import io.github.nitsuya.aa.display.aap.usb.UsbAccessoryTransport
import io.github.nitsuya.aa.display.aap.wireless.WirelessAutoStarter
import io.github.nitsuya.aa.display.aap.wireless.WirelessConnector
import io.github.nitsuya.aa.display.aap.wireless.WirelessException
import io.github.nitsuya.aa.display.aap.wireless.WirelessPermissionException
import io.github.nitsuya.aa.display.carplay.CarPlayAppPickerOverlay
import io.github.nitsuya.aa.display.carplay.CarPlayConsts
import io.github.nitsuya.aa.display.carplay.CarPlayInputBridge
import io.github.nitsuya.aa.display.carplay.CarPlayRecentsOverlay
import io.github.nitsuya.aa.display.egress.ProjectionSessionGate
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import aap_protobuf.service.inputsource.message.TouchEventOuterClass.TouchEvent
import net.prototux.aare.aap.AapLog
import net.prototux.aare.aap.AapSession
import net.prototux.aare.aap.PhoneDescription
import net.prototux.aare.aap.ServiceChannel
import net.prototux.aare.aap.SessionOptions
import net.prototux.aare.aap.Transport
import net.prototux.aare.aap.channels.AudioChannel
import net.prototux.aare.aap.channels.InputChannel
import net.prototux.aare.aap.channels.MediaPlaybackChannel
import net.prototux.aare.aap.channels.SensorChannel
import net.prototux.aare.aap.channels.VideoChannel
import net.prototux.aare.aap.wireless.WirelessBootstrapException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Native AAP phone session: wireless bootstrap → TLS → video/audio/input/playback → VD encode.
 */
class AapSourceSession(
    context: Context,
    private val onStatus: (String) -> Unit,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    /** Touch/scroll Binder must not run on aap-reader (stalls USB drain → TLS NONCE-GAP). */
    private val inputThread = HandlerThread("aap-input").apply { start() }
    private val inputHandler = Handler(inputThread.looper)
    private val log = object : AapLog {
        override fun i(msg: String) {
            Log.i(AapConsts.TAG, msg)
        }
        override fun w(msg: String, t: Throwable?) {
            if (t != null) Log.w(AapConsts.TAG, msg, t) else Log.w(AapConsts.TAG, msg)
        }
        override fun e(msg: String, t: Throwable?) {
            if (t != null) Log.e(AapConsts.TAG, msg, t) else Log.e(AapConsts.TAG, msg)
        }
    }
    private val running = AtomicBoolean(false)
    private val certAttempt = AtomicInteger(0)
    private var wireless: WirelessConnector? = null
    private var autoStarter: WirelessAutoStarter? = null
    private var wifiStateReceiver: BroadcastReceiver? = null
    private var usbStateReceiver: BroadcastReceiver? = null
    /** uptime anchor for session age in diagnostics; SystemClock.uptimeMillis. */
    @Volatile private var sessionStartMs = 0L
    /** Health telemetry worker; started in onRunning, stopped on close/teardown. */
    private var healthWorker: Thread? = null
    /** rxBytes at the last health tick, for the throughput delta. */
    private var lastHealthRxBits: Pair<Long, Long> = 0L to 0L
    /**
     * CPU wake lock held while the session is live.
     *
     * Root cause of the "head unit cuts the session" issue: when the phone panel times out
     * (息屏) the SoC drops to deep low-power (all cores 787 MHz — see SDHMS:LOAD at 17:07),
     * the USB writer starves for >3 s, the head unit's keep-alive (interval=1s, timeout=3s)
     * expires and it kills the session with a TLS close. The display-scoped keep-awake in
     * DisplaySessionPolicy keeps the VDs lit but does not hold the CPU awake; a partial wake
     * lock is the direct lever. Cost is neutral: the encode pipeline already keeps the SoC
     * busy while the session is live, and the phone is plugged in.
     */
    @Volatile private var cpuWakeLock: PowerManager.WakeLock? = null

    private fun acquireCpuWakeLock() {
        if (cpuWakeLock?.isHeld == true) return
        try {
            val pm = app.getSystemService(PowerManager::class.java) ?: return
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${BuildConfig.APPLICATION_ID}:AapLive")
            lock.acquire()
            cpuWakeLock = lock
            Log.i(AapConsts.TAG, "cpu wake lock acquired (session live)")
        } catch (e: Throwable) {
            Log.w(AapConsts.TAG, "cpu wake lock acquire failed", e)
        }
    }

    private fun releaseCpuWakeLock() {
        val lock = cpuWakeLock ?: return
        cpuWakeLock = null
        runCatching { if (lock.isHeld) lock.release() }
    }
    private var transport: Transport? = null
    private var aap: AapSession? = null
    private var display: AapDisplayBridge? = null
    private val audioEgress = AapAudioEgress()
    private val nowPlaying = AapNowPlaying()
    private val inputKeys = AapInputKeys(app)
    private val batteryReporter = AapBatteryReporter(app)
    /** All HU audio sinks opened this session; only the first of each streamType is fed. */
    private val audioChannels = ArrayList<AudioChannel>()
    private var touchW = AapConsts.DEFAULT_VIDEO_WIDTH
    private var touchH = AapConsts.DEFAULT_VIDEO_HEIGHT
    private var videoW = AapConsts.DEFAULT_VIDEO_WIDTH
    private var videoH = AapConsts.DEFAULT_VIDEO_HEIGHT
    /** HUIG: visible content = codec − margins (cropped by the HU). */
    private var widthMargin = 0
    private var heightMargin = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        onStatus("正在准备…")
        armUsbStateMonitor()
        thread(name = "aap-boot", isDaemon = true) {
            try {
                PhoneCertStore.ensureExtracted(app, force = false)
                main.post {
                    // USB attach may have won during extract — do not clobber its status with
                    // wireless listen text, but still register wireless listen for later.
                    val usbBusy = transport != null || aap != null
                    armWireless(updateStatus = !usbBusy)
                }
            } catch (e: Throwable) {
                Log.e(AapConsts.TAG, "AapSourceSession boot failed", e)
                onStatus("启动失败，请重试")
                // Do not clear [running] if a wired session already bound during extract.
                if (aap == null && transport == null) running.set(false)
            }
        }
    }

    fun stop() {
        running.set(false)
        releaseCpuWakeLock()
        stopHealthTicker()
        removeWifiStateMonitor()
        removeUsbStateMonitor()
        tearDownSession("stop")
        try {
            autoStarter?.unregister()
        } catch (_: Throwable) {
        }
        autoStarter = null
        wireless = null
        reportLive(false)
        inputHandler.removeCallbacksAndMessages(null)
        onStatus("已停止")
    }

    fun reconnect() {
        stop()
        start()
    }

    private fun armWireless(updateStatus: Boolean = true) {
        if (!running.get()) return
        if (autoStarter != null) return
        if (updateStatus) onStatus("等待车机用无线 Android Auto 连接…")
        val connector = WirelessConnector(app, log)
        wireless = connector
        val starter = WirelessAutoStarter(app, log) { device ->
            if (!running.get()) return@WirelessAutoStarter
            thread(name = "aap-wireless-connect", isDaemon = true) {
                connectDevice(device)
            }
        }
        autoStarter = starter
        try {
            starter.register()
        } catch (e: WirelessPermissionException) {
            onStatus("需要蓝牙权限，请到系统设置里允许")
            Log.w(AapConsts.TAG, "wireless perms missing: ${e.missing}")
        } catch (e: Throwable) {
            Log.e(AapConsts.TAG, "WirelessAutoStarter register failed", e)
            onStatus("无法等待车机连接，请重试")
        }
        thread(name = "aap-scan-connected", isDaemon = true) {
            try {
                for (d in connector.findConnectedHeadUnits()) {
                    if (!running.get()) return@thread
                    connectDevice(d)
                    return@thread
                }
            } catch (e: Throwable) {
                Log.w(AapConsts.TAG, "findConnectedHeadUnits: ${e.message}")
            }
        }
    }

    private fun connectDevice(device: BluetoothDevice) {
        if (!running.get()) return
        if (aap != null) {
            Log.i(AapConsts.TAG, "session already live; ignore ${device.address}")
            return
        }
        val connector = wireless ?: return
        val peerName = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "车机"
        onStatus("正在连接 $peerName…")
        // Mach-E (#74): right after BT comes up the car accepts RFCOMM but is not ready yet.
        // Retry twice inside this job (10s then 20s) when bootstrap fails whileNegotiating.
        armWifiStateMonitor()
        // Do not hold @Synchronized across sleep — USB attach must still be able to bind.
        var attempt = 0
        while (true) {
            if (!running.get() || aap != null) return
            val t = try {
                connector.connectBlocking(device)
            } catch (e: WirelessPermissionException) {
                onStatus("需要蓝牙权限，请到系统设置里允许")
                return
            } catch (e: Throwable) {
                val carNotReady = generateSequence<Throwable>(e) { it.cause }
                    .any { it is WirelessBootstrapException && it.whileNegotiating }
                if (carNotReady && attempt < 2 && running.get() && aap == null) {
                    attempt++
                    val delayMs = attempt * WIRELESS_RETRY_DELAY_MS
                    Log.w(
                        AapConsts.TAG,
                        "wireless: car not ready yet; retry $attempt/2 in ${delayMs / 1000}s (${e.message})",
                    )
                    onStatus("车机尚未就绪，${delayMs / 1000}s 后重试…")
                    try {
                        Thread.sleep(delayMs)
                    } catch (_: InterruptedException) {
                        return
                    }
                    continue
                }
                if (e is WirelessException) {
                    onStatus("连接失败，请在车机上重试")
                    Log.w(AapConsts.TAG, "wireless connect failed", e)
                } else {
                    onStatus("连接失败，请重试")
                    Log.e(AapConsts.TAG, "connectDevice", e)
                }
                return
            }
            // USB may have bound while we blocked in RFCOMM/Wi‑Fi.
            if (aap != null) {
                Log.i(AapConsts.TAG, "drop wireless transport; session already live")
                runCatching { t.close() }
                return
            }
            startAap(t, peerKey = device.address, trustOnFirstUse = true, sendPings = true)
            return
        }
    }

    /** True when [t] is the live transport with an AAP session already up. */
    @Synchronized
    fun isBoundTo(t: Transport): Boolean = transport === t && aap != null

    /** True while a transport fd is held (incl. mid-TLS) — soft USB probe must not reopen. */
    @Synchronized
    fun hasActiveTransport(): Boolean = transport != null

    /** True only after service discovery completed — not during TLS / failed handshake. */
    fun isProtocolLive(): Boolean = aap?.state == AapSession.State.RUNNING

    /**
     * Soft [AapUsbProbe] must not reopen the same AOA accessory after TLS desync /
     * mid-session crypto failure — HU still speaks encrypted frames. Cleared on
     * USB DETACH or a fresh ATTACHED entry ([clearUsbReplugRequired]).
     */
    private val usbReplugRequired = AtomicBoolean(false)

    fun isUsbReplugRequired(): Boolean = usbReplugRequired.get()

    /** Call from USB_ACCESSORY_ATTACHED / DETACHED before a new soft or hard attach. */
    fun clearUsbReplugRequired() {
        if (usbReplugRequired.compareAndSet(true, false)) {
            Log.i(AapConsts.TAG, "USB replug gate cleared")
        }
    }

    /**
     * USB AOA / lab entry: AAP on an already-open [Transport].
     *
     * Must finish cert extract before TLS — factory HUs reject the generated self-signed leaf.
     * Wired links use [SessionOptions.sendPings]=false (stock phone only answers HU pings on USB).
     */
    fun startOnTransport(t: Transport, peerKey: String, trustOnFirstUse: Boolean = true) {
        synchronized(this) {
            if (transport === t && aap != null) return
        }
        if (peerKey.startsWith("usb:") && usbReplugRequired.get()) {
            Log.w(AapConsts.TAG, "USB attach blocked — unplug/replug required")
            onStatus("请拔掉数据线再插上")
            runCatching { t.close() }
            return
        }
        if (!running.get()) running.set(true)
        onStatus("数据线已连接，正在准备…")
        try {
            PhoneCertStore.ensureExtracted(app, force = false)
        } catch (e: Throwable) {
            Log.e(AapConsts.TAG, "USB ensureExtracted failed", e)
        }
        val st = PhoneCertStore.status(app)
        if (!st.hasCarService) {
            Log.w(AapConsts.TAG, "USB AAP: no CarService leaf — factory HU likely to reject TLS")
            onStatus("还没准备好，请先在状态页完成「连车准备」")
        }
        synchronized(this) {
            if (transport === t && aap != null) return
            startAap(t, peerKey, trustOnFirstUse, sendPings = false)
        }
    }

    @Synchronized
    private fun startAap(
        t: Transport,
        peerKey: String,
        trustOnFirstUse: Boolean,
        sendPings: Boolean,
    ) {
        tearDownSession("rebind")
        transport = t
        val identity = PhoneCertStore.pick(app, certAttempt.get())
        onStatus("正在和车机握手…")
        val session = AapSession(
            t,
            identity,
            PhoneDescription(
                deviceName = android.os.Build.MODEL,
                label = "AADisplay",
                instanceId = instanceId(),
            ),
            callbacks,
            log,
            SessionOptions(
                sendPings = sendPings,
                pins = null,
                peerKey = peerKey,
                trustOnFirstUse = trustOnFirstUse,
            ),
        )
        aap = session
        sessionStartMs = SystemClock.uptimeMillis()
        session.start()
    }

    /**
     * Wi-Fi state changes are the strongest precursor of a wireless AAP disconnect (single-radio
     * phones drop the car's AP for scans; a dead AP ends the network request → transport close).
     * Log every transition so the timing lines up with the session-close reason in logcat.
     */
    private fun armWifiStateMonitor() {
        armUsbStateMonitor()
        if (wifiStateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)
                Log.w(AapConsts.TAG, "wifi state changed: ${wifiStateName(state)} (session=${aap?.state ?: "none"})")
            }
        }
        runCatching {
            // minSdk 33: the flag exists; the state broadcast is protected, not for other apps.
            app.registerReceiver(receiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION),
                android.content.Context.RECEIVER_NOT_EXPORTED)
        }.onFailure {
            Log.w(AapConsts.TAG, "cannot monitor wifi state", it)
            return
        }
        wifiStateReceiver = receiver
    }

    /**
     * Wired disconnect forensics: the USB port state (CONFIGURED → DISCONNECTED when the car
     * drops the port or switches it to charge-only) plus AOA accessory attach/detach, so a port
     * event lines up in time with the session-close reason.
     */
    private fun armUsbStateMonitor() {
        if (usbStateReceiver != null) return
        val usb = app.getSystemService(UsbManager::class.java) ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                // ACTION_USB_STATE / EXTRA_USB_STATE constants are gone from the API 36 SDK;
                // the strings are stable framework values.
                val state = intent.getStringExtra("android.hardware.usb.extra.USB_STATE") ?: "?"
                val accessories = runCatching { usb.accessoryList?.size ?: 0 }.getOrDefault(0)
                Log.w(
                    AapConsts.TAG,
                    "usb state changed: $state accessories=$accessories (session=${aap?.state ?: "none"})",
                )
            }
        }
        val filter = IntentFilter().apply {
            addAction("android.hardware.usb.action.USB_STATE")
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        runCatching {
            // minSdk 33: protected system broadcasts, not for other apps.
            app.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        }.onFailure {
            Log.w(AapConsts.TAG, "cannot monitor usb state", it)
            return
        }
        usbStateReceiver = receiver
    }

    private fun removeWifiStateMonitor() {
        val r = wifiStateReceiver ?: return
        wifiStateReceiver = null
        runCatching { app.unregisterReceiver(r) }
    }

    private fun removeUsbStateMonitor() {
        val r = usbStateReceiver ?: return
        usbStateReceiver = null
        runCatching { app.unregisterReceiver(r) }
    }

    private fun wifiStateName(state: Int): String = when (state) {
        // WifiManager.WIFI_STATE_* constants are gone from the API 36 SDK; values are stable.
        0 -> "DISABLING"
        1 -> "DISABLED"
        2 -> "ENABLING"
        3 -> "ENABLED"
        4 -> "CONNECTING"
        5 -> "CONNECTED"
        6 -> "FAILURE"
        7 -> "DISCONNECTING"
        8 -> "DISCONNECTED"
        else -> "UNKNOWN($state)"
    }

    /**
     * Disconnection forensics: while the session is live, one line every 15 s with session age,
     * AAP protocol state, keep-alive RTT and the video link's latency/drop metrics. A session
     * that dies after a trend of rising ack/send latency or falling RSSI (HeadUnitNetwork) points
     * at the Wi-Fi link; a sudden death with healthy metrics points at the head unit side.
     */
    private fun startHealthTicker() {
        if (healthWorker?.isAlive == true) return
        healthWorker = thread(name = "aap-health", isDaemon = true) {
            while (running.get()) {
                try {
                    Thread.sleep(HEALTH_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running.get()) break
                val s = aap ?: continue
                val upS = if (sessionStartMs > 0) (SystemClock.uptimeMillis() - sessionStartMs) / 1000 else -1
                val video = activeVideo?.takeIf { it.started }?.let {
                    " video[ack=${it.ackLatencyMs}ms send=${it.sendLatencyMs}ms pend=${it.pendingFrames} drops=${it.congestionDrops} focus=${it.focused}]"
                } ?: ""
                val usb = transport as? UsbAccessoryTransport
                val usbInfo = if (usb != null) {
                    val now = SystemClock.uptimeMillis()
                    val dx = usb.rxBytes - lastHealthRxBits.first
                    val dt = ((now - lastHealthRxBits.second) / 1000).coerceAtLeast(1)
                    lastHealthRxBits = usb.rxBytes to now
                    " usb[rx=${dx / 1024}KB/s ring=${usb.ringUsed()} fault=${usb.readFault?.message ?: "none"}]"
                } else { lastHealthRxBits = 0L to SystemClock.uptimeMillis(); "" }
                Log.i(
                    AapConsts.TAG,
                    "AAP health: up=${upS}s state=${s.state} rtt=${s.lastRttMs}ms " +
                        "transport=${transport?.name ?: "none"}$video$usbInfo",
                )
            }
        }
    }

    private fun stopHealthTicker() {
        healthWorker?.interrupt()
        healthWorker = null
    }

    /** USB write-stall watchdog worker; same lifecycle as [healthWorker]. */
    private var stallWorker: Thread? = null
    /** elapsedRealtime the write path was first seen stalled; 0 = healthy. */
    @Volatile private var stallSinceMs = 0L
    /** elapsedRealtime of the previous stall-kill (0 = none this session). */
    @Volatile private var lastStallKillMs = 0L
    /** True while the most recent close was our own stall-kill (status + re-probe). */
    @Volatile private var closedByStall = false

    /**
     * USB write-stall watchdog.
     *
     * The AOA output endpoint can stall two ways (11-09: audio + video froze on the car while
     * the link stayed "up" for 10+ minutes, reader still receiving): one write blocked in the
     * kernel for minutes, or a sustained slow endpoint (~10 ms per write) where the session's
     * write queue settles at a constant ~8 s delay — inbound traffic keeps flowing, so no I/O
     * error fires and the session never fails or recovers. A blocked USB write cannot be
     * interrupted; the only escape is closing the fd, so once the stall is confirmed this
     * closes the transport: the session fails, and the USB probe re-attaches a fresh one.
     */
    private fun startStallMonitor() {
        if (stallWorker?.isAlive == true) return
        stallWorker = thread(name = "aap-usb-stall", isDaemon = true) {
            while (running.get()) {
                try {
                    Thread.sleep(USB_STALL_CHECK_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running.get()) break
                val s = aap ?: continue
                if (s.state != AapSession.State.RUNNING) continue
                val usb = transport as? UsbAccessoryTransport ?: continue
                val inflightMs = usb.inflightWriteMs()
                val queueWaitMs = s.oldestQueueWaitMs
                val bad = inflightMs > USB_WRITE_BLOCK_KILL_MS ||
                    queueWaitMs > USB_QUEUE_WAIT_BAD_MS
                if (!bad) {
                    stallSinceMs = 0L
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                if (stallSinceMs == 0L) stallSinceMs = now
                // A write blocked in the kernel kills immediately (nothing drains behind it);
                // a slow endpoint must stay slow first, so a single USB blip doesn't cycle.
                if (inflightMs <= USB_WRITE_BLOCK_KILL_MS && now - stallSinceMs < USB_STALL_SUSTAIN_MS) continue
                stallSinceMs = 0L
                val prevKillMs = lastStallKillMs
                lastStallKillMs = now
                closedByStall = true
                // Same endpoint stalling again soon: soft reopen can't fix it, require replug.
                if (prevKillMs > 0 && now - prevKillMs < USB_STALL_REKILL_WINDOW_MS) {
                    usbReplugRequired.set(true)
                }
                Log.w(
                    AapConsts.TAG,
                    "USB write stall — killing session (inflight=${inflightMs}ms " +
                        "queueWait=${queueWaitMs}ms replugGate=${usbReplugRequired.get()})",
                )
                runCatching { usb.close() }
            }
        }
    }

    private fun stopStallMonitor() {
        stallWorker?.interrupt()
        stallWorker = null
    }

    private fun sessionAgeMs(): Long =
        if (sessionStartMs > 0) SystemClock.uptimeMillis() - sessionStartMs else -1

    private val videoListener = object : VideoChannel.Listener {
        override fun chooseConfiguration(
            configs: List<VideoConfiguration>,
            preferred: List<Int>,
        ): Int {
            for ((i, c) in configs.withIndex()) {
                val (w, h) = resolutionOf(c.codecResolution)
                Log.i(
                    AapConsts.TAG,
                    "AAP video cfg[$i]=${w}x$h fps=${c.frameRate} dens=${c.density} " +
                        "realDens=${c.realDensity} margin=${c.widthMargin}x${c.heightMargin} " +
                        "preferred=${i in preferred}",
                )
            }
            // Honour HU preference order (AARE PhoneSim / configIndices). Do not require 30fps —
            // Audi MIB3 only advertises 60fps. Do not bias toward 1280×720: that overrode
            // preferred=[1920×1080] and left height_margin=128 unused → magnified crop + bad touch.
            for (idx in preferred) {
                val c = configs.getOrNull(idx) ?: continue
                val (w, h) = resolutionOf(c.codecResolution)
                if (w in 800..1920 && h in 480..1080) {
                    Log.i(
                        AapConsts.TAG,
                        "AAP video choose preferred index=$idx ${w}x$h preferred=$preferred",
                    )
                    return idx
                }
            }
            val fallback = preferred.firstOrNull()?.takeIf { it in configs.indices } ?: 0
            Log.i(AapConsts.TAG, "AAP video choose fallback index=$fallback preferred=$preferred")
            return fallback
        }

        override fun onVideoStart(index: Int, config: VideoConfiguration) {
            val (w, h) = resolutionOf(config.codecResolution)
            videoW = w.coerceAtLeast(16)
            videoH = h.coerceAtLeast(16)
            widthMargin = config.widthMargin.toInt().coerceIn(0, videoW - 16)
            heightMargin = config.heightMargin.toInt().coerceIn(0, videoH - 16)
            // Margins are total (L+R / T+B); keep them even so content centers cleanly.
            if (widthMargin and 1 != 0) widthMargin -= 1
            if (heightMargin and 1 != 0) heightMargin -= 1
            val fps = when (config.frameRate) {
                VideoFrameRateType.VIDEO_FPS_60 -> 60
                else -> 30
            }
            val contentW = (videoW - widthMargin).coerceAtLeast(16)
            val contentH = (videoH - heightMargin).coerceAtLeast(16)
            Log.i(
                AapConsts.TAG,
                "AAP video start idx=$index ${videoW}x$videoH@$fps dens=${config.density} " +
                    "realDens=${config.realDensity} margin=${widthMargin}x$heightMargin " +
                    "content=${contentW}x$contentH touch=${touchW}x$touchH ui=${config.hasUiConfig()}",
            )
            val sessionDpi = resolveSessionDpi(config)
            syncTouchBridgeLayout(sessionDpi)
            val videoCh = activeVideo ?: return
            val bridge = display ?: AapDisplayBridge(app, onStatus).also { display = it }
            bridge.startFor(
                videoCh,
                videoW,
                videoH,
                fps,
                sessionDpi,
                widthMargin,
                heightMargin,
            )
            main.postDelayed({
                if (!running.get() || videoCh !== activeVideo || bridge !== display) return@postDelayed
                val base = runCatching { bridge.baseVideoBitrate() }.getOrDefault(0)
                videoBitrateCtrl.reset(base)
                startVideoBitrateCtrl()
            }, 1_000)
            onStatus("正在打开车机画面…")
        }

        override fun onVideoFocus(projected: Boolean) {
            Log.i(AapConsts.TAG, "AAP video focus projected=$projected")
            if (projected) display?.requestKeyFrame()
        }

        override fun onVideoFocusMode(
            mode: aap_protobuf.service.media.video.message.VideoFocusModeOuterClass.VideoFocusMode,
            unsolicited: Boolean,
        ) {
            Log.i(AapConsts.TAG, "AAP VIDEO_FOCUS mode=$mode unsolicited=$unsolicited")
        }

        override fun onKeyFrameNeeded() {
            display?.requestKeyFrame()
        }

        override fun onUiConfig(config: aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig) {
            Log.i(
                AapConsts.TAG,
                "AAP uiConfig margins=${config.margins} content=${config.contentInsets} " +
                    "stable=${config.stableContentInsets}",
            )
        }
    }

    @Volatile
    private var activeVideo: VideoChannel? = null

    /**
     * Link-aware video bitrate. aare's VideoChannel already measures the HU ack
     * window (`ackLatencyMs` send→ack, `sendLatencyMs` queue→socket, cumulative
     * `congestionDrops`) — the fields are exposed "for the phone's adaptive bitrate"
     * but nothing consumed them. This controller maps those to a live
     * `setParameters(KEY_BIT_RATE)` step change, never a codec recycle:
     * two congested windows step the CSM-derived base down (×2/3), three clean
     * windows step back up, clamped to [base/2, base].
     */
    private val videoBitrateCtrl = object : Runnable {
        @Volatile var base = 0
        @Volatile var current = 0
        private var badWindows = 0
        private var goodWindows = 0
        private var lastCongestionDrops = 0L
        private var lastChangeAt = 0L

        fun reset(baseBps: Int) {
            base = baseBps
            current = baseBps
            badWindows = 0
            goodWindows = 0
            lastCongestionDrops = 0L
            lastCongestionLogAt = 0L
        }

        @Volatile private var lastCongestionLogAt = 0L

        override fun run() {
            while (running.get()) {
                try {
                    Thread.sleep(3_000)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running.get()) break
                evaluate()
            }
        }

        private fun evaluate() {
            val v = activeVideo ?: return
            if (base <= 0 || !v.started) return
            if (!v.focused) return
            val dropsDelta = v.congestionDrops - lastCongestionDrops
            lastCongestionDrops = v.congestionDrops
            val congested = dropsDelta > 0 || v.ackLatencyMs > ACK_LATENCY_BAD_MS ||
                v.sendLatencyMs > SEND_LATENCY_BAD_MS
            // Disconnection forensics: surface rising head-unit ack latency / drops as soon as
            // they appear (not only when a bitrate step happens) — a head unit that stops
            // acking is usually the one that cuts the session a moment later.
            if (congested) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastCongestionLogAt > 5_000L) {
                    lastCongestionLogAt = now
                    Log.i(
                        AapConsts.TAG,
                        "AAP video congestion: ack=${v.ackLatencyMs}ms send=${v.sendLatencyMs}ms " +
                            "drops+=$dropsDelta pend=${v.pendingFrames} bitrate=${current / 1_000_000}M",
                    )
                }
            }
            val clean = !congested && v.ackLatencyMs < ACK_LATENCY_GOOD_MS &&
                v.sendLatencyMs < SEND_LATENCY_GOOD_MS
            badWindows = if (congested) badWindows + 1 else 0
            goodWindows = if (clean) goodWindows + 1 else 0
            if (badWindows < 2 && goodWindows < 3) return
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastChangeAt < 6_000L) return
            val floor = (base / 2).coerceAtLeast(2_000_000)
            val next = when {
                badWindows >= 2 -> {
                    badWindows = 0
                    (current * 2 / 3).coerceAtLeast(floor)
                }
                else -> {
                    goodWindows = 0
                    (current * 3 / 2).coerceAtMost(base)
                }
            }
            if (next == current) return
            Log.i(
                AapConsts.TAG,
                "AAP video bitrate ${current / 1_000_000}M → ${next / 1_000_000}M " +
                    "(ack=${v.ackLatencyMs}ms send=${v.sendLatencyMs}ms drops+=$dropsDelta " +
                    "pending=${v.pendingFrames})",
            )
            current = next
            lastChangeAt = now
            display?.setVideoBitrate(next)
        }
    }

    @Volatile
    private var videoBitrateWorker: Thread? = null

    private fun startVideoBitrateCtrl() {
        if (videoBitrateWorker?.isAlive == true) return
        videoBitrateCtrl.base = 0
        videoBitrateWorker = thread(name = "aap-video-bitrate", isDaemon = true) {
            videoBitrateCtrl.run()
        }
    }

    private fun stopVideoBitrateCtrl() {
        videoBitrateWorker?.interrupt()
        videoBitrateWorker = null
        videoBitrateCtrl.base = 0
    }

    private val audioListener = object : AudioChannel.Listener {
        override fun onAudioReady(channel: AudioChannel, config: AudioConfiguration) {
            if (!isPrimaryAudio(channel)) return
            audioEgress.onChannelReady(channel, config)
            audioEgress.attach(channel)
        }
    }

    /**
     * Some cars offer two media / guidance sinks (Ford Mustang Mach-E, AARE #74). Feeding both
     * made the second replace the first mid-capture; keep only the first of each kind.
     */
    private fun isPrimaryAudio(channel: AudioChannel): Boolean =
        audioChannels.firstOrNull { it.streamType == channel.streamType } === channel

    private val inputListener = object : InputChannel.Listener {
        override fun onInput(report: InputReport) {
            // Copy refs only — protobuf messages are immutable; Binder inject off reader thread.
            val touch = if (report.hasTouchEvent()) report.touchEvent else null
            val key = if (report.hasKeyEvent()) report.keyEvent else null
            val relative = if (report.hasRelativeEvent()) report.relativeEvent else null
            val pad = if (report.hasTouchpadEvent()) report.touchpadEvent else null
            inputHandler.post {
                if (touch != null) {
                    // Full-canvas → split pane local coords (same as CarPlay Host). The old path
                    // injected video-sized coords into focusedPane only — right pane / off-focus taps felt dead.
                    dispatchTouch(touch, source = "touch")
                }
                if (key != null) {
                    inputKeys.onKeyEvent(key)
                }
                if (relative != null) {
                    handleRelative(relative)
                }
                if (pad != null) {
                    dispatchTouch(pad, source = "pad")
                }
            }
        }

        private fun dispatchTouch(te: TouchEvent, source: String) {
            if (te.pointerDataCount <= 0) {
                Log.w(AapConsts.TAG, "AAP $source empty pointers action=${te.action}")
                return
            }
            val p = te.getPointerData(0)
            // HUIG content rect the stream is cropped to. Audi MIB3 reports a wider
            // touchscreen (e.g. 1540×720) than content (1412×654); raw coords still
            // span the content pixels (samples peak ≪ touchW). Dividing by touchW
            // shifted the divider ~6% left of the painted seam → chronic misses.
            val contentW = (videoW - widthMargin).coerceAtLeast(1)
            val contentH = (videoH - heightMargin).coerceAtLeast(1)
            val normW = when {
                touchW > contentW + 8 -> contentW
                else -> touchW.coerceAtLeast(1)
            }
            val normH = when {
                touchH > contentH + 8 -> contentH
                else -> touchH.coerceAtLeast(1)
            }
            val xNorm = (p.x.toFloat() / normW).coerceIn(0f, 1f)
            val yNorm = (p.y.toFloat() / normH).coerceIn(0f, 1f)
            val action = when (te.action) {
                PointerAction.ACTION_DOWN -> MotionEvent.ACTION_DOWN
                PointerAction.ACTION_UP -> MotionEvent.ACTION_UP
                PointerAction.ACTION_MOVED -> MotionEvent.ACTION_MOVE
                PointerAction.ACTION_POINTER_DOWN, PointerAction.ACTION_POINTER_UP ->
                    MotionEvent.ACTION_MOVE
                else -> {
                    Log.w(
                        AapConsts.TAG,
                        "AAP $source unknown action=${te.action} raw=${p.x},${p.y} " +
                            "normBase=${normW}x$normH → $xNorm,$yNorm",
                    )
                    return
                }
            }
            // Throttle MOVE spam; always log DOWN/UP so we can see if HU sends touch at all.
            if (action != MotionEvent.ACTION_MOVE || shouldLogTouchMove()) {
                Log.i(
                    AapConsts.TAG,
                    "AAP $source action=$action raw=${p.x},${p.y} touch=${touchW}x$touchH " +
                        "normBase=${normW}x$normH " +
                        "norm=${"%.3f".format(xNorm)},${"%.3f".format(yNorm)} " +
                        "video=${videoW}x$videoH content=${contentW}x$contentH",
                )
            }
            // Do not pull CoreApi split ratio while divider/peel owns the pointer —
            // that snapped primaryRatio back every MOVE and killed drag.
            if (!CarPlayInputBridge.isChromeGestureActive()) {
                syncTouchBridgeLayout()
            }
            try {
                CarPlayInputBridge.injectNormalized(xNorm, yNorm, action)
            } catch (e: Throwable) {
                Log.w(AapConsts.TAG, "AAP touch inject failed", e)
            }
        }

        private fun handleRelative(rel: aap_protobuf.service.inputsource.message.RelativeEventOuterClass.RelativeEvent) {
            var vScroll = 0
            var hScroll = 0
            for (d in rel.dataList) {
                // HU rotary / pad relative deltas; treat first axis as vertical, second as horizontal.
                when (d.keycode) {
                    0, 1 -> vScroll += d.delta
                    else -> hScroll += d.delta
                }
            }
            if (vScroll == 0 && hScroll == 0) return
            val pane = runCatching { CoreApi.focusedPane }.getOrDefault(SplitPane.PRIMARY)
                .coerceIn(SplitPane.PRIMARY, SplitPane.SECONDARY)
            val x = (videoW / 2).coerceAtLeast(0)
            val y = (videoH / 2).coerceAtLeast(0)
            runCatching {
                CoreApi.scrollPane(pane, x, y, vScroll, hScroll)
            }.onFailure { Log.w(AapConsts.TAG, "scrollPane failed", it) }
        }
    }

    private val callbacks = object : AapSession.Callbacks {
        override fun onServiceDiscovery(
            session: AapSession,
            response: ServiceDiscoveryResponse,
        ): List<ServiceChannel> {
            val channels = ArrayList<ServiceChannel>()
            audioChannels.clear()
            for (s in response.channelsList) {
                when {
                    s.hasMediaSinkService() && s.mediaSinkService.videoConfigsCount > 0 &&
                        s.mediaSinkService.displayType.number == 0 -> {
                        val v = VideoChannel(s, videoListener)
                        activeVideo = v
                        channels += v
                    }
                    s.hasMediaSinkService() && s.mediaSinkService.videoConfigsCount == 0 -> {
                        val audio = AudioChannel(s, audioListener)
                        channels += audio
                        audioChannels += audio
                        // May attach later on onAudioReady when config lands.
                        if (isPrimaryAudio(audio)) {
                            audio.config?.let { audioEgress.attach(audio) }
                        }
                    }
                    s.hasInputSourceService() -> {
                        val input = InputChannel(s, inputListener)
                        input.touchScreen?.let { ts ->
                            // Hyundai GEN5 Wide advertises 0×0; treat nonpositive as unspecified
                            // and keep the video-sized default (using 1×1 blows up every touch).
                            if (ts.width > 0) touchW = ts.width
                            if (ts.height > 0) touchH = ts.height
                            Log.i(
                                AapConsts.TAG,
                                "AAP touchscreen advertised=${ts.width}x${ts.height} " +
                                    "using=${touchW}x$touchH type=${ts.type} secondary=${ts.isSecondary}",
                            )
                            syncTouchBridgeLayout()
                        } ?: Log.w(AapConsts.TAG, "AAP input has no touchscreen; keep default ${touchW}x$touchH")
                        input.touchPad?.let { tp ->
                            Log.i(AapConsts.TAG, "AAP touchpad ${tp.width}x${tp.height} abs=${tp.uiAbsolute}")
                        }
                        Log.i(AapConsts.TAG, "AAP keycodes=${input.keycodes.size}")
                        channels += input
                    }
                    s.hasSensorSourceService() -> {
                        channels += SensorChannel(s, object : SensorChannel.Listener {
                            override fun onSensors(batch: SensorBatch) = Unit
                        })
                    }
                    s.hasMediaPlaybackService() -> {
                        val playback = MediaPlaybackChannel(s)
                        channels += playback
                        nowPlaying.attach(playback)
                    }
                }
            }
            Log.i(
                AapConsts.TAG,
                "AAP audio: " + audioChannels.joinToString { ch ->
                    "${ch.streamType.name.removePrefix("AUDIO_STREAM_")}#${ch.id}" +
                        if (isPrimaryAudio(ch)) "" else " (unused: second of this kind)"
                },
            )
            onStatus("车机已认出本机，正在接通…")
            return channels
        }

        override fun onRunning(session: AapSession) {
            reportLive(true)
            acquireCpuWakeLock()
            nowPlaying.start(app)
            batteryReporter.start(session)
            audioEgress.start()
            startHealthTicker()
            startStallMonitor()
            // HUIG: request focus before MEDIA; wait for AudioFocusNotification to start pump.
            runCatching {
                session.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_GAIN)
            }.onFailure { Log.w(AapConsts.TAG, "requestAudioFocus failed", it) }
            onStatus("已连上车机")
        }

        override fun onAudioFocus(session: AapSession, notification: AudioFocusNotification) {
            audioEgress.onAudioFocus(notification.focusState)
        }

        override fun onClosed(session: AapSession, reason: String, error: Throwable?) {
            stopHealthTicker()
            stopStallMonitor()
            releaseCpuWakeLock()
            // One summary of where the session stood when it died (state/age/rtt/transport);
            // the reason above says who ended it. For a wired link, the drain loop's first
            // kernel I/O error is the ground truth behind a bare "read failed".
            val usbFault = (transport as? UsbAccessoryTransport)?.readFault
            if (usbFault != null) Log.w(AapConsts.TAG, "session closed: $reason — usb rx fault: ${usbFault.message}", usbFault)
            Log.i(
                AapConsts.TAG,
                "session closed: $reason (age=${sessionAgeMs()}ms state=${session.state} " +
                    "rtt=${session.lastRttMs}ms transport=${transport?.name ?: "none"})",
                error,
            )
            val errMsg = error?.message.orEmpty()
            val usbCryptoFault = errMsg.contains("encrypted frame before TLS") ||
                errMsg.contains("NONCE-GAP") ||
                errMsg.contains("TLS record failed") ||
                errMsg.contains("TLS closed")
            val wasUsb = transport?.name?.startsWith("usb ") == true
            if (session.certificateRejected) {
                certAttempt.incrementAndGet()
                onStatus("车机未接受连车信息，将自动换一种方式重试")
            } else if (wasUsb && usbCryptoFault) {
                // Soft reopen of the same AOA accessory cannot reset HU TLS — require unplug.
                usbReplugRequired.set(true)
                onStatus("请拔掉数据线再插上")
                Log.w(AapConsts.TAG, "USB TLS fault — soft attach blocked until unplug/replug")
            } else if (closedByStall && wasUsb) {
                onStatus(
                    if (usbReplugRequired.get()) "请拔掉数据线再插上" else "链路拥塞，正在自动重连…",
                )
            } else {
                onStatus("已断开，可重新连接")
            }
            main.post {
                runCatching {
                    session.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_RELEASE)
                }
                batteryReporter.stop()
                audioEgress.stop()
                audioChannels.clear()
                nowPlaying.stop()
                inputKeys.reset()
                inputHandler.removeCallbacksAndMessages(null)
                CarPlayRecentsOverlay.close()
                CarPlayAppPickerOverlay.close()
                stopVideoBitrateCtrl()
                display?.stop()
                display = null
                aap = null
                // Closing the accessory fd; HU often keeps AOA until physical unplug.
                runCatching { transport?.close() }
                transport = null
                activeVideo = null
                reportLive(false)
                if (closedByStall && wasUsb && !usbReplugRequired.get()) {
                    // Give the head unit time to time out its own keep-alive and reset, then
                    // re-arm the probe window to re-attach the (still plugged) accessory.
                    main.postDelayed({
                        if (running.get() && transport == null && aap == null) {
                            AapProjectionService.start(app)
                        }
                    }, USB_STALL_REATTACH_DELAY_MS)
                }
                closedByStall = false
            }
        }
    }

    private fun tearDownSession(reason: String) {
        stopStallMonitor()
        Log.i(AapConsts.TAG, "tearDownSession $reason (age=${sessionAgeMs()}ms state=${aap?.state ?: "none"})")
        runCatching {
            aap?.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_RELEASE)
        }
        batteryReporter.stop()
        audioEgress.stop()
        nowPlaying.stop()
        inputKeys.reset()
        CarPlayRecentsOverlay.close()
        CarPlayAppPickerOverlay.close()
        stopVideoBitrateCtrl()
        runCatching { aap?.stop() }
        aap = null
        runCatching { transport?.close() }
        transport = null
        display?.stop()
        display = null
        activeVideo = null
    }

    private fun reportLive(live: Boolean) {
        val aggregate = ProjectionSessionGate.setOwnerLive(
            ProjectionSessionGate.OWNER_AAP,
            live,
        )
        try {
            CoreApi.reportCarPlaySessionLive(aggregate)
        } catch (e: Throwable) {
            Log.w(AapConsts.TAG, "reportCarPlaySessionLive failed", e)
        }
    }

    private fun instanceId(): String {
        val f = java.io.File(app.filesDir, "aap_instance_id")
        if (f.isFile) return f.readText().trim()
        val id = UUID.randomUUID().toString()
        f.writeText(id)
        return id
    }

    companion object {
        private fun resolutionOf(type: VideoCodecResolutionType): Pair<Int, Int> = when (type) {
            VideoCodecResolutionType.VIDEO_800x480 -> 800 to 480
            VideoCodecResolutionType.VIDEO_1280x720 -> 1280 to 720
            VideoCodecResolutionType.VIDEO_1920x1080 -> 1920 to 1080
            VideoCodecResolutionType.VIDEO_2560x1440 -> 2560 to 1440
            VideoCodecResolutionType.VIDEO_3840x2160 -> 3840 to 2160
            VideoCodecResolutionType.VIDEO_720x1280 -> 720 to 1280
            VideoCodecResolutionType.VIDEO_1080x1920 -> 1080 to 1920
            VideoCodecResolutionType.VIDEO_1440x2560 -> 1440 to 2560
            VideoCodecResolutionType.VIDEO_2160x3840 -> 2160 to 3840
        }

        /** Delay base for wireless "car not ready yet" retries (× attempt number). */
        private const val WIRELESS_RETRY_DELAY_MS = 10_000L

        /** Health telemetry cadence (disconnection forensics). */
        private const val HEALTH_INTERVAL_MS = 15_000L

        /** [startStallMonitor] tick. */
        private const val USB_STALL_CHECK_MS = 2_000L
        /** One write blocked in the kernel longer than this kills the session. */
        private const val USB_WRITE_BLOCK_KILL_MS = 5_000L
        /** Write-queue age of a polled message that counts as a stalled link. */
        private const val USB_QUEUE_WAIT_BAD_MS = 3_000L
        /** How long the link must stay bad before the slow-endpoint kill (blip immunity). */
        private const val USB_STALL_SUSTAIN_MS = 10_000L
        /** Two stall-kills within this window → soft reopen is hopeless, require replug. */
        private const val USB_STALL_REKILL_WINDOW_MS = 10 * 60_000L
        /** Delay before re-arming the USB probe after a stall-kill (HU keep-alive reset). */
        private const val USB_STALL_REATTACH_DELAY_MS = 8_000L
        /** Sustained ack latency (send→HU ack) beyond this counts as congestion. */
        private const val ACK_LATENCY_BAD_MS = 300L
        private const val SEND_LATENCY_BAD_MS = 1_000L
        private const val ACK_LATENCY_GOOD_MS = 150L
        private const val SEND_LATENCY_GOOD_MS = 300L
    }

    /** Keep CarPlay-shared pane router in sync with HUIG content size + live split layout. */
    private fun syncTouchBridgeLayout(sessionDpi: Int = 0) {
        // Touchscreen 0..1 maps onto the visible content rect (codec − margins), not the
        // cropped margin bands the HU covers with its own chrome.
        val contentW = (videoW - widthMargin).coerceAtLeast(1)
        val contentH = (videoH - heightMargin).coerceAtLeast(1)
        CarPlayInputBridge.shellWidthPx = contentW
        CarPlayInputBridge.shellHeightPx = contentH
        CarPlayInputBridge.densityDpi = when {
            sessionDpi > 0 -> sessionDpi
            else -> runCatching { CoreApi.vdDensityDpi }.getOrNull()?.takeIf { it > 0 }
                ?: CarPlayConsts.ENCODE_UI_DENSITY_DPI
        }
        runCatching {
            CarPlayInputBridge.primaryRatio = CoreApi.getSplitRatio()
                .coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
        }
        runCatching {
            CarPlayInputBridge.fullscreenPane = CoreApi.getSplitFullscreenPane()
        }
    }

    /**
     * AAP density — same model as the old gearhead path:
     * 1) status-page manual override (dynamic),
     * 2) else HU [VideoConfiguration.density] / real_density (e.g. Audi 213),
     * 3) else encode default 160.
     * Do **not** force 160: CarPlay uses 160 only because it has no HU density negotiate.
     */
    private fun resolveSessionDpi(config: VideoConfiguration): Int {
        val huDpi = when {
            config.density > 0 -> config.density.toInt()
            config.realDensity > 0 -> config.realDensity.toInt()
            else -> 0
        }
        val override = runCatching { CoreApi.vdDensityDpi }.getOrDefault(0)
        val dpi = when {
            override > 0 -> override
            huDpi > 0 -> huDpi
            else -> CarPlayConsts.ENCODE_UI_DENSITY_DPI
        }
        Log.i(AapConsts.TAG, "AAP density session=$dpi override=$override huCfg=$huDpi")
        return dpi
    }

    private var lastTouchMoveLogMs = 0L
    private fun shouldLogTouchMove(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTouchMoveLogMs < 250L) return false
        lastTouchMoveLogMs = now
        return true
    }

}
