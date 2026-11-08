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
    private val audioEgress = AapAudioEgress()
    private val nowPlaying = AapNowPlaying()
    private val inputKeys = AapInputKeys(app)
    private val batteryReporter = AapBatteryReporter(app)
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

    @Synchronized
    private fun connectDevice(device: BluetoothDevice) {
        if (!running.get()) return
        if (aap != null) {
            Log.i(AapConsts.TAG, "session already live; ignore ${device.address}")
            return
        }
        val connector = wireless ?: return
        val peerName = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "车机"
        onStatus("正在连接 $peerName…")
        val t = try {
            connector.connectBlocking(device)
        } catch (e: WirelessPermissionException) {
            onStatus("需要蓝牙权限，请到系统设置里允许")
            return
        } catch (e: WirelessException) {
            onStatus("连接失败，请在车机上重试")
            Log.w(AapConsts.TAG, "wireless connect failed", e)
            return
        } catch (e: Throwable) {
            onStatus("连接失败，请重试")
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
        session.start()
    }

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
        }

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
            audioEgress.onChannelReady(channel, config)
            audioEgress.attach(channel)
        }
    }

    private val inputListener = object : InputChannel.Listener {
        override fun onInput(report: InputReport) {
            if (report.hasTouchEvent()) {
                // Full-canvas → split pane local coords (same as CarPlay Host). The old path
                // injected video-sized coords into focusedPane only — right pane / off-focus taps felt dead.
                dispatchTouch(report.touchEvent, source = "touch")
            }
            if (report.hasKeyEvent()) {
                inputKeys.onKeyEvent(report.keyEvent)
            }
            if (report.hasRelativeEvent()) {
                handleRelative(report.relativeEvent)
            }
            if (report.hasTouchpadEvent()) {
                dispatchTouch(report.touchpadEvent, source = "pad")
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
                            Log.i(
                                AapConsts.TAG,
                                "AAP touchscreen ${touchW}x$touchH type=${ts.type} secondary=${ts.isSecondary}",
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
            onStatus("车机已认出本机，正在接通…")
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
            onStatus("已连上车机")
        }

        override fun onAudioFocus(session: AapSession, notification: AudioFocusNotification) {
            audioEgress.onAudioFocus(notification.focusState)
        }

        override fun onClosed(session: AapSession, reason: String, error: Throwable?) {
            Log.i(AapConsts.TAG, "session closed: $reason", error)
            val usbDesync = error?.message?.contains("encrypted frame before TLS") == true
            if (session.certificateRejected) {
                certAttempt.incrementAndGet()
                onStatus("车机未接受连车信息，将自动换一种方式重试")
            } else if (usbDesync) {
                onStatus("线还在，车机状态有点乱，正在重试…")
            } else {
                onStatus("已断开，可重新连接")
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
                stopVideoBitrateCtrl()
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
