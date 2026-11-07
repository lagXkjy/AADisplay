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
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.aap.cert.PhoneCertStore
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Native AAP phone session: wireless bootstrap → TLS → video/audio/input/playback → VD encode.
 */
class AapSourceSession(
    context: Context,
    private val onStatus: (String) -> Unit,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
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
    private var transport: Transport? = null
    private var aap: AapSession? = null
    private var display: AapDisplayBridge? = null
    private var touchMapper: AapTouchMapper? = null
    private val audioEgress = AapAudioEgress()
    private val nowPlaying = AapNowPlaying()
    private val inputKeys = AapInputKeys(app)
    private val batteryReporter = AapBatteryReporter(app)
    private var touchW = AapConsts.DEFAULT_VIDEO_WIDTH
    private var touchH = AapConsts.DEFAULT_VIDEO_HEIGHT
    private var videoW = AapConsts.DEFAULT_VIDEO_WIDTH
    private var videoH = AapConsts.DEFAULT_VIDEO_HEIGHT

    fun start() {
        if (!running.compareAndSet(false, true)) return
        onStatus("准备证书…")
        thread(name = "aap-boot", isDaemon = true) {
            try {
                PhoneCertStore.ensureExtracted(app, force = false)
                main.post {
                    // USB attach may have won during extract — do not clobber its status with
                    // "监听无线", but still register wireless listen for later.
                    val usbBusy = transport != null || aap != null
                    armWireless(updateStatus = !usbBusy)
                }
            } catch (e: Throwable) {
                Log.e(AapConsts.TAG, "AapSourceSession boot failed", e)
                onStatus("启动失败：${e.message}")
                // Do not clear [running] if a wired session already bound during extract.
                if (aap == null && transport == null) running.set(false)
            }
        }
    }

    fun stop() {
        running.set(false)
        tearDownSession("stop")
        try {
            autoStarter?.unregister()
        } catch (_: Throwable) {
        }
        autoStarter = null
        wireless = null
        reportLive(false)
        onStatus("已停止")
    }

    fun reconnect() {
        stop()
        start()
    }

    private fun armWireless(updateStatus: Boolean = true) {
        if (!running.get()) return
        if (autoStarter != null) return
        if (updateStatus) onStatus("监听无线 Android Auto…")
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
            onStatus("需要蓝牙/附近设备权限")
            Log.w(AapConsts.TAG, "wireless perms missing: ${e.missing}")
        } catch (e: Throwable) {
            Log.e(AapConsts.TAG, "WirelessAutoStarter register failed", e)
            onStatus("无线监听失败：${e.message}")
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

    @Synchronized
    private fun connectDevice(device: BluetoothDevice) {
        if (!running.get()) return
        if (aap != null) {
            Log.i(AapConsts.TAG, "session already live; ignore ${device.address}")
            return
        }
        val connector = wireless ?: return
        onStatus("正在连接 ${runCatching { device.name }.getOrNull() ?: device.address}…")
        val t = try {
            connector.connectBlocking(device)
        } catch (e: WirelessPermissionException) {
            onStatus("缺少权限：${e.missing.joinToString { it.substringAfterLast('.') }}")
            return
        } catch (e: WirelessException) {
            onStatus("无线连接失败：${e.message}")
            Log.w(AapConsts.TAG, "wireless connect failed", e)
            return
        } catch (e: Throwable) {
            onStatus("连接异常：${e.message}")
            Log.e(AapConsts.TAG, "connectDevice", e)
            return
        }
        // USB may have bound while we blocked in RFCOMM/Wi‑Fi.
        if (aap != null) {
            Log.i(AapConsts.TAG, "drop wireless transport; session already live")
            runCatching { t.close() }
            return
        }
        startAap(t, peerKey = device.address, trustOnFirstUse = true, sendPings = true)
    }

    /** True when [t] is the live transport with an AAP session already up. */
    @Synchronized
    fun isBoundTo(t: Transport): Boolean = transport === t && aap != null

    /** True only after service discovery completed — not during TLS / failed handshake. */
    fun isProtocolLive(): Boolean = aap?.state == AapSession.State.RUNNING

    private val usbRetry = AtomicInteger(0)

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
        if (!running.get()) running.set(true)
        onStatus("有线 USB：准备证书…")
        try {
            PhoneCertStore.ensureExtracted(app, force = false)
        } catch (e: Throwable) {
            Log.e(AapConsts.TAG, "USB ensureExtracted failed", e)
        }
        val st = PhoneCertStore.status(app)
        if (!st.hasCarService) {
            Log.w(AapConsts.TAG, "USB AAP: no CarService leaf — factory HU likely to reject TLS")
            onStatus("有线：缺少 CarService 证书（请先提取）")
        }
        synchronized(this) {
            if (transport === t && aap != null) return
            if (peerKey.startsWith("usb:")) {
                // Fresh USB bind — allow desync retries again.
                usbRetry.set(0)
            }
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
        onStatus("TLS 握手（证书 #${certAttempt.get()}）…")
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
        session.start()
    }

    private val videoListener = object : VideoChannel.Listener {
        override fun chooseConfiguration(
            configs: List<VideoConfiguration>,
            preferred: List<Int>,
        ): Int {
            var best = 0
            var bestScore = Int.MIN_VALUE
            for ((i, c) in configs.withIndex()) {
                val (w, h) = resolutionOf(c.codecResolution)
                var score = 0
                if (w in 800..1920) score += 10
                if (h in 480..1080) score += 10
                if (c.frameRate == VideoFrameRateType.VIDEO_FPS_30) score += 5
                if (abs(w - 1280) + abs(h - 720) < 200) score += 20
                if (score > bestScore) {
                    bestScore = score
                    best = i
                }
            }
            return best
        }

        override fun onVideoStart(index: Int, config: VideoConfiguration) {
            val (w, h) = resolutionOf(config.codecResolution)
            videoW = w.coerceAtLeast(16)
            videoH = h.coerceAtLeast(16)
            val fps = when (config.frameRate) {
                VideoFrameRateType.VIDEO_FPS_60 -> 60
                else -> 30
            }
            touchMapper = AapTouchMapper(touchW, touchH, videoW, videoH)
            syncTouchBridgeLayout()
            val videoCh = activeVideo ?: return
            val bridge = display ?: AapDisplayBridge(app, onStatus).also { display = it }
            bridge.startFor(videoCh, videoW, videoH, fps)
            onStatus("出画 ${videoW}x$videoH@$fps")
        }

        override fun onVideoFocus(projected: Boolean) {
            if (projected) display?.requestKeyFrame()
        }

        override fun onKeyFrameNeeded() {
            display?.requestKeyFrame()
        }
    }

    @Volatile
    private var activeVideo: VideoChannel? = null

    private val audioListener = object : AudioChannel.Listener {
        override fun onAudioReady(channel: AudioChannel, config: AudioConfiguration) {
            audioEgress.onChannelReady(channel, config)
            audioEgress.attach(channel)
        }
    }

    private val inputListener = object : InputChannel.Listener {
        override fun onInput(report: InputReport) {
            if (report.hasTouchEvent()) {
                // Full-canvas → split pane local coords (same as CarPlay Host). The old path
                // injected video-sized coords into focusedPane only — right pane / off-focus taps felt dead.
                dispatchTouch(report.touchEvent)
            }
            if (report.hasKeyEvent()) {
                inputKeys.onKeyEvent(report.keyEvent)
            }
            if (report.hasRelativeEvent()) {
                handleRelative(report.relativeEvent)
            }
            if (report.hasTouchpadEvent()) {
                dispatchTouch(report.touchpadEvent)
            }
        }

        private fun dispatchTouch(te: TouchEvent) {
            if (te.pointerDataCount <= 0) return
            val p = te.getPointerData(0)
            val xNorm = (p.x.toFloat() / touchW.coerceAtLeast(1)).coerceIn(0f, 1f)
            val yNorm = (p.y.toFloat() / touchH.coerceAtLeast(1)).coerceIn(0f, 1f)
            val action = when (te.action) {
                PointerAction.ACTION_DOWN -> MotionEvent.ACTION_DOWN
                PointerAction.ACTION_UP -> MotionEvent.ACTION_UP
                PointerAction.ACTION_MOVED -> MotionEvent.ACTION_MOVE
                PointerAction.ACTION_POINTER_DOWN, PointerAction.ACTION_POINTER_UP ->
                    MotionEvent.ACTION_MOVE
                else -> return
            }
            syncTouchBridgeLayout()
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
                        // May attach later on onAudioReady when config lands.
                        audio.config?.let { audioEgress.attach(audio) }
                    }
                    s.hasInputSourceService() -> {
                        val input = InputChannel(s, inputListener)
                        input.touchScreen?.let { ts ->
                            touchW = ts.width.coerceAtLeast(1)
                            touchH = ts.height.coerceAtLeast(1)
                            touchMapper = AapTouchMapper(touchW, touchH, videoW, videoH)
                            syncTouchBridgeLayout()
                        }
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
            onStatus("服务发现：${AapSession.describe(response)}")
            return channels
        }

        override fun onRunning(session: AapSession) {
            reportLive(true)
            nowPlaying.start(app)
            batteryReporter.start(session)
            audioEgress.start()
            // HUIG: request focus before MEDIA; wait for AudioFocusNotification to start pump.
            runCatching {
                session.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_GAIN)
            }.onFailure { Log.w(AapConsts.TAG, "requestAudioFocus failed", it) }
            onStatus("已连接（AAP）")
        }

        override fun onAudioFocus(session: AapSession, notification: AudioFocusNotification) {
            audioEgress.onAudioFocus(notification.focusState)
        }

        override fun onClosed(session: AapSession, reason: String, error: Throwable?) {
            Log.i(AapConsts.TAG, "session closed: $reason", error)
            val usbDesync = error?.message?.contains("encrypted frame before TLS") == true
            if (session.certificateRejected) {
                certAttempt.incrementAndGet()
                onStatus("车机拒绝证书，下次将换下一张")
            } else if (usbDesync) {
                onStatus("有线协议不同步（车机仍在旧会话），正在重试…")
            } else {
                onStatus("已断开：$reason")
            }
            main.post {
                runCatching {
                    session.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_RELEASE)
                }
                batteryReporter.stop()
                audioEgress.stop()
                nowPlaying.stop()
                inputKeys.reset()
                CarPlayRecentsOverlay.close()
                CarPlayAppPickerOverlay.close()
                display?.stop()
                display = null
                aap = null
                // Closing the accessory fd forces DHU / HU to reset AOA before we reopen.
                runCatching { transport?.close() }
                transport = null
                activeVideo = null
                reportLive(false)
                if (usbDesync && running.get() && usbRetry.incrementAndGet() <= 3) {
                    val attempt = usbRetry.get()
                    Log.i(AapConsts.TAG, "USB TLS desync — retry attach attempt=$attempt")
                    main.postDelayed(
                        {
                            io.github.nitsuya.aa.display.aap.usb.AapUsbProbe.tryAttachExisting(app)
                        },
                        1_500L * attempt,
                    )
                }
            }
        }
    }

    private fun tearDownSession(reason: String) {
        Log.i(AapConsts.TAG, "tearDownSession $reason")
        runCatching {
            aap?.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_RELEASE)
        }
        batteryReporter.stop()
        audioEgress.stop()
        nowPlaying.stop()
        inputKeys.reset()
        CarPlayRecentsOverlay.close()
        CarPlayAppPickerOverlay.close()
        runCatching { aap?.stop() }
        aap = null
        runCatching { transport?.close() }
        transport = null
        display?.stop()
        display = null
        activeVideo = null
    }

    private fun reportLive(live: Boolean) {
        ProjectionSessionGate.setCarPlayLive(live)
        try {
            CoreApi.reportCarPlaySessionLive(live)
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
    }

    /** Keep CarPlay-shared pane router in sync with AAP encode size + live split layout. */
    private fun syncTouchBridgeLayout() {
        CarPlayInputBridge.shellWidthPx = videoW.coerceAtLeast(1)
        CarPlayInputBridge.shellHeightPx = videoH.coerceAtLeast(1)
        CarPlayInputBridge.densityDpi = runCatching { CoreApi.vdDensityDpi }
            .getOrNull()
            ?.takeIf { it > 0 }
            ?: CarPlayConsts.ENCODE_UI_DENSITY_DPI
        runCatching {
            CarPlayInputBridge.primaryRatio = CoreApi.getSplitRatio()
                .coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
        }
        runCatching {
            CarPlayInputBridge.fullscreenPane = CoreApi.getSplitFullscreenPane()
        }
    }
}
