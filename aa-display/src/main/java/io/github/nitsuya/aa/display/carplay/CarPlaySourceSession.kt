package io.github.nitsuya.aa.display.carplay

import android.content.Context
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.carplay.profile.CarPlayProfileRegistry
import io.github.nitsuya.aa.display.carplay.profile.CarPlayProfileResolver
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Csm
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Session
import io.github.nitsuya.aa.display.egress.ProjectionSessionGate
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Orchestrates CarPlay Source session: Bonjour + BT iAP2 + Wi‑Fi handoff + RTSP.
 */
class CarPlaySourceSession(
    private val appContext: Context,
) {
    private val running = AtomicBoolean(false)
    val tee = FrameEgressTee()
    private var glCompositor: CarPlayGlCompositor? = null
    private val keyframesLogged = AtomicLong(0)

    /**
     * Current supply frame rate. Starts at [CarPlayConsts.DEFAULT_VIDEO_FPS]; sustained
     * under-delivery drops to [CarPlayConsts.DEGRADED_VIDEO_FPS], and a sustained healthy
     * 30 fps supply climbs back. The DiPlay host decodes at its own user fps setting, so
     * this only throttles our production + bandwidth, never the wire format.
     */
    @Volatile
    private var supplyFps = CarPlayConsts.DEFAULT_VIDEO_FPS
    @Volatile
    private var encodeW = CarPlayConsts.ENCODE_WIDTH
    @Volatile
    private var encodeH = CarPlayConsts.ENCODE_HEIGHT
    private var badRateWindows = 0
    private var goodRateWindows = 0
    private var lastDropCount = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val encoder = CarPlayVideoEncoder { bytes, ptsUs, key, config ->
        val written = rtsp?.sendVideoAccessUnit(bytes, ptsUs, key, config) ?: 0
        if (key || config) {
            val n = keyframesLogged.incrementAndGet()
            if (n <= 5 || n % 30L == 0L) {
                Log.d(
                    CarPlayConsts.TAG,
                    "encoded ${if (config) "config" else "keyframe"} bytes=${bytes.size} " +
                        "ptsUs=$ptsUs packets=$written streaming=${rtsp?.isStreaming() == true}",
                )
            }
        }
    }
    private var bonjour: CarPlayBonjour? = null
    private var bluetooth: CarPlayBluetoothLink? = null
    private var rtsp: CarPlayRtspSession? = null
    private var iap2: Iap2Session? = null
    private var wifiHandoff: CarPlayWifiHandoff? = null
    /** True once we dialed AirPlay (0x4301 or `_airplay._tcp` / gateway fallback). */
    private val airplayDialed = AtomicBoolean(false)
    /** Host / reportLive / audio-capture armed — only after HU dial or screen SETUP. */
    private val mediaPrepared = AtomicBoolean(false)
    @Volatile private var lastWifiSsid: String? = null
    @Volatile private var pendingAudioReleaseReason = ""
    /** Bumped on cancel / reschedule so a dequeued release runnable aborts. */
    private val audioReleaseEpoch = AtomicInteger(0)

    /**
     * HU media gone, but DiPlay often TEARDOWNs and SETUPs again within a couple
     * of seconds. Wait before dropping session-live so phone volume returns on a
     * real disconnect without flashing the speaker on a soft reconnect.
     */
    private val releasePhoneAudio = Runnable {
        val epoch = audioReleaseEpoch.get()
        if (!running.get()) return@Runnable
        if (rtsp?.isStreaming() == true) {
            Log.i(CarPlayConsts.TAG, "skip phone audio release — streaming again")
            return@Runnable
        }
        if (epoch != audioReleaseEpoch.get()) return@Runnable
        val reason = pendingAudioReleaseReason
        Log.i(CarPlayConsts.TAG, "CarPlay HU gone ($reason) — restore phone volume")
        reportOwnerLive(false)
        if (epoch != audioReleaseEpoch.get()) return@Runnable
        releaseHostPipeline(reason)
    }

    /**
     * Stop GL / dual VD / MediaCodec after a real HU drop. FGS + cafe/RTSP listen stay.
     * Next [prepareMediaPipeline] boots Host again.
     */
    private fun releaseHostPipeline(reason: String) {
        if (!mediaPrepared.get() && !CarPlayHostRuntime.isRunning()) return
        Log.i(CarPlayConsts.TAG, "release host pipeline ($reason)")
        cancelGlRebindRetry()
        glCompositor?.onRateReport = null
        try {
            glCompositor?.detachEncoder()
        } catch (_: Throwable) {
        }
        tee.setGlExclusive(false)
        encoder.stop()
        glCompositor = null
        CarPlayHostRuntime.stop(reason)
        mediaPrepared.set(false)
        airplayDialed.set(false)
        supplyFps = CarPlayConsts.DEFAULT_VIDEO_FPS
        badRateWindows = 0
        goodRateWindows = 0
        lastDropCount = 0L
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Log.i(CarPlayConsts.TAG, "CarPlaySourceSession start (listen-only)")
        // Do not set ProjectionSessionGate / reportLive / encoder here — idle AutoArm
        // used to burn ~40% CPU on 720p60 encode + dual VDs with streaming=false.
        airplayDialed.set(false)
        mediaPrepared.set(false)

        supplyFps = CarPlayConsts.DEFAULT_VIDEO_FPS
        badRateWindows = 0
        goodRateWindows = 0
        lastDropCount = encoder.dropCount()
        val rateCb: (Int, Float) -> Unit = { target, actual -> onRateReport(target, actual) }
        tee.onRateReport = rateCb
        glCompositor?.onRateReport = rateCb

        val rtspSession = CarPlayRtspSession(
            appContext = appContext,
            // Screen SETUP (incl. DiPlay re-SETUP after TCP reset): always mint a fresh
            // MediaCodec input Surface. Keeping a wedged GL→encoder Surface leaves the
            // codec at in=0 while GlCompositor still reports ~60fps — DiPlay freezes.
            onVideoSetup = { w, h ->
                ensureEncoder(w, h, forceSurfaceRefresh = true)
                // Audi MIB3 needs a real IDR immediately after dataPort open; intra-refresh
                // sync frames (~464B) were not enough and the HU timed out the session.
                encoder.requestKeyFrame()
            },
            onSessionReady = {
                // Do not ensureEncoder here — a second bindGl/forceFreshSurface mid-stream
                // re-sends CONFIG and Audi drops control within ~3s.
                encoder.requestKeyFrame()
                // Store observer + egress: MIB3 Wi‑Fi iAP2 (Audi cluster), else BT (DiPlay).
                // [rtsp] is assigned before SETUP invokes this callback.
                CarPlayNowPlaying.start(appContext)
                val wifiNp = rtsp?.mib3NowPlayingEgress()
                if (wifiNp != null) {
                    CarPlayNowPlaying.attachEgress(wifiNp)
                } else {
                    iap2?.let { CarPlayNowPlaying.attachEgress(it) }
                }
            },
            onTouchEvent = { x, y, action -> CarPlayInputBridge.injectNormalized(x, y, action) },
            onForceKeyFrame = { encoder.requestKeyFrame() },
            linkSyncBytes = { iap2?.acceptedLinkSync() },
            onHuMediaEnded = { reason -> schedulePhoneAudioRelease(reason) },
        )
        rtsp = rtspSession
        rtspSession.startServer()
        startWirelessTransport(rtspSession)
    }

    /**
     * Boots Host GL + dual VDs and reports session live (audio capture / process boost).
     * Idempotent. Call only when the HU is actually connecting — not on AutoArm listen.
     */
    private fun prepareMediaPipeline(reason: String) {
        if (!running.get()) return
        cancelPhoneAudioRelease()
        val first = mediaPrepared.compareAndSet(false, true)
        // Before Host onCreateSplitDisplay so Coolwalk does not shrink VD panes.
        reportOwnerLive(true)
        if (!first && CarPlayHostRuntime.isRunning()) {
            CarPlayHostRuntime.compositorOrNull()?.let { gl ->
                if (glCompositor !== gl) {
                    glCompositor = gl
                    gl.onRateReport = { target, actual -> onRateReport(target, actual) }
                }
            }
            return
        }
        Log.i(CarPlayConsts.TAG, "prepareMediaPipeline reason=$reason")
        CarPlayHostRuntime.ensureStartedSync(appContext, timeoutMs = 5_000L)
        CarPlayHostRuntime.compositorOrNull()?.let { gl ->
            glCompositor = gl
            gl.onRateReport = { target, actual -> onRateReport(target, actual) }
        }
    }

    private fun startWirelessTransport(rtspSession: CarPlayRtspSession) {
        wifiHandoff = CarPlayWifiHandoff(appContext).also { handoff ->
            handoff.onBound = { mac ->
                iap2?.noteAccessoryWifiBound(mac)
                onAccessoryWifiBound(mac)
            }
        }

        val bj = CarPlayBonjour(
            appContext,
            onCtrlResolved = { info -> onBonjourResolved(info) },
            onAirPlayResolved = { info -> onAirPlayEndpoint(info) },
        )
        bonjour = bj

        val sourceIdentity = CarPlayProfileRegistry.defaultSource()
        val link = CarPlayBluetoothLink(
            appContext,
            onConnected = { iap2?.onTransportConnected() },
            onDisconnected = disconnect@{
                if (iap2?.onTransportClosed() == false) return@disconnect
                // MIB3 NowPlaying rides Wi‑Fi iAP2 after handoff — RFCOMM drop must not
                // tear media or the cluster card. DiPlay still needs a full reset to re-Auth.
                if (rtsp?.mib3NowPlayingEgress() != null) {
                    Log.i(CarPlayConsts.TAG, "iAP RFCOMM closed — keep media/NowPlaying (Wi-Fi iAP)")
                    return@disconnect
                }
                rtsp?.releaseMediaStreams("iap-rfcomm-closed")
                CarPlayNowPlaying.stop()
                airplayDialed.set(false)
            },
            onBytes = { bytes -> iap2?.onBytes(bytes) },
            // Detect/SYN/auth all count as live — mid-negotiate must not be replaced.
            sessionLive = { iap2?.isNegotiatingOrLive() == true },
            onPeer = { name, address -> iap2?.notePeer(name, address) },
        )
        bluetooth = link
        iap2 = Iap2Session(
            sendRaw = { bytes -> link.sendRaw(bytes) },
            bluetoothTransportId = { link.localAddress() },
            onLinkReady = {
                Log.i(CarPlayConsts.TAG, "iAP link ready → Identification")
            },
            onIdentificationComplete = {
                Log.i(CarPlayConsts.TAG, "iAP identification done → Auth")
            },
            onAuthComplete = {
                Log.i(CarPlayConsts.TAG, "iAP auth done → WiFi / CarPlayStartSession")
            },
            onWifiConfig = { wifi ->
                lastWifiSsid = wifi.ssid
                // Before join: disable saved-STA autojoin so accessory AP cannot
                // become ACCEPT_UNVALIDATED default and kill cellular.
                try {
                    CoreApi.preferCellularOverUnvalidatedWifi()
                } catch (_: Throwable) {
                }
                // Join only; dial waits for CarPlayStartSession + network ready,
                // or `_airplay._tcp` fallback when MIB3 never sends 0x4301.
                wifiHandoff?.ensureAccessoryWifi(wifi.ssid, wifi.passphrase, wifi.channel) {}
            },
            onCarPlayStart = { start ->
                val handoff = wifiHandoff
                if (handoff == null || start.ssid.isNullOrBlank()) {
                    dialCarPlayControl(start)
                } else {
                    handoff.ensureAccessoryWifi(start.ssid, start.passphrase, start.channel) {
                        dialCarPlayControl(start)
                    }
                }
            },
            onNowPlayingUpdates = { CarPlayNowPlaying.onUpdatesRequested() },
            onFileTransferDatagram = { dg -> CarPlayNowPlaying.onFileTransferDatagram(dg) },
            sourceIdentity = sourceIdentity,
            onHandshakeStuck = {
                link.dropCurrentPeer(
                    "iap-handshake-stuck",
                    allowInbound = true,
                    stillNegotiating = { iap2?.linkReady != true },
                )
            },
        )
        // Register Bonjour only after [link] is assigned — RTSP often binds before this
        // method returns, so an earlier thread raced `bluetooth == null` and advertised
        // ctrl TXT without `id` (HU then never invites).
        Thread {
            var waits = 0
            while (running.get() && rtspSession.localPort <= 0 && waits < 50) {
                Thread.sleep(20)
                waits++
            }
            val port = rtspSession.localPort
            if (port > 0) {
                val mac = link.localAddress()
                bj.registerControl(port, mac)
            }
            bj.startCtrlDiscovery()
        }.start()
        link.start()
    }

    /**
     * After accessory AP bind: harden ctrl advertisement (real BT MAC on the
     * bound network) and warm `_airplay._tcp` cache. Primary dial stays
     * [dialCarPlayControl] from `0x4301` — mDNS/gateway only if StartSession never arrives.
     */
    private fun onAccessoryWifiBound(staMac: String?) {
        val bj = bonjour ?: return
        val port = rtsp?.localPort ?: 0
        val btMac = bluetooth?.localAddress().orEmpty()
        if (port > 0) {
            bj.registerControl(port, btMac.ifBlank { staMac.orEmpty() }, force = true)
        }
        val localIp = wifiHandoff?.localIpv4OnAccessory(lastWifiSsid)
        bj.airplaySubnetPrefix24 = CarPlayBonjour.prefix24(localIp)
        bj.resetAirPlayCache()
        bj.startAirPlayDiscovery()
        Log.i(
            CarPlayConsts.TAG,
            "WiFi bound → ctrl re-register mac=$btMac airplay /24=${bj.airplaySubnetPrefix24}",
        )
        // Late fallback only: never race StartSession (our proven primary dial).
        Thread({
            try {
                Thread.sleep(8_000)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!running.get() || airplayDialed.get()) return@Thread
            if (iap2?.hasSeenStartSession() == true) return@Thread
            dialAirPlayFallback("no-0x4301-after-wifi")
        }, "carplay-airplay-fallback").apply { isDaemon = true }.start()
    }

    /** Cache only — do not dial here (would steal StartSession primary path). */
    private fun onAirPlayEndpoint(info: NsdServiceInfo) {
        Log.i(
            CarPlayConsts.TAG,
            "AirPlay mDNS cached ${info.serviceName} ${info.host}:${info.port} (fallback only)",
        )
    }

    private fun dialAirPlayFallback(reason: String) {
        if (!running.get() || airplayDialed.get()) return
        if (iap2?.hasSeenStartSession() == true) return
        val cached = bonjour?.cachedAirPlayEndpoint()
            ?: bonjour?.awaitAirPlayEndpoint(2_000L)
        if (cached?.host != null && cached.port > 0) {
            dialAirPlayHost(cached.host, cached.port, reason)
            return
        }
        val gw = wifiHandoff?.accessoryIpv4Hint(lastWifiSsid)
        if (gw.isNullOrBlank()) {
            Log.w(CarPlayConsts.TAG, "AirPlay fallback: no mDNS endpoint and no gateway ($reason)")
            return
        }
        try {
            dialAirPlayHost(InetAddress.getByName(gw), 7000, "$reason-gw7000")
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "AirPlay fallback resolve failed $gw", e)
        }
    }

    private fun dialAirPlayHost(host: InetAddress?, port: Int, reason: String) {
        if (host == null || port <= 0 || port > 65535) return
        if (!airplayDialed.compareAndSet(false, true)) return
        Log.i(CarPlayConsts.TAG, "AirPlay dial $host:$port ($reason) [fallback]")
        prepareMediaPipeline("airplay-fallback")
        val hu = iap2?.currentHuProfile() ?: CarPlayProfileRegistry.defaultHu()
        rtsp?.connectAsAirPlaySource(
            host = host,
            port = port,
            deviceId = null,
            huProfile = hu,
            sourceIdentity = CarPlayProfileRegistry.defaultSource(),
            phoneMac = bluetooth?.localAddress(),
            wifiMac = wifiHandoff?.staMacForAnnounce(),
        )
    }

    private fun dialCarPlayControl(start: Iap2Csm.CarPlayStart) {
        val port = start.port ?: return
        if (port <= 0 || port > 65535) return
        // Prefer explicit IPv4/global from StartSession; DiPlay sometimes only puts fe80 in
        // 0x4301 — fall back to the accessory AP DHCP/gateway on the joined Wi‑Fi.
        val host = CarPlayWifiHandoff.pickDialHost(start.ipAddresses)
            ?: wifiHandoff?.accessoryIpv4Hint(start.ssid)
            ?: start.ipAddresses.firstOrNull()
        if (host.isNullOrBlank()) {
            Log.i(
                CarPlayConsts.TAG,
                "CarPlayStartSession port=$port without IPv4 — rely on Bonjour / inbound TCP",
            )
            return
        }
        // StartSession is the primary dial. If a late mDNS fallback already
        // reached streaming, keep it; otherwise always prefer 0x4301.
        if (airplayDialed.get() && rtsp?.isStreaming() == true) {
            Log.i(CarPlayConsts.TAG, "StartSession after fallback already streaming — keep")
            return
        }
        airplayDialed.set(true)
        if (host.startsWith("fe80:", ignoreCase = true)) {
            Log.w(
                CarPlayConsts.TAG,
                "CarPlayStartSession dial host is link-local $host — may fail without scope",
            )
        }
        Thread({
            val addr = try {
                InetAddress.getByName(host)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "StartSession resolve failed $host", e)
                return@Thread
            }
            // Handoff / DHCP may still be settling after accessory Wi‑Fi bind.
            var lastErr: Throwable? = null
            for (attempt in 1..12) {
                if (!running.get()) return@Thread
                try {
                    val ssidForBind = start.ssid ?: lastWifiSsid
                    if (!ssidForBind.isNullOrBlank()) {
                        wifiHandoff?.rebindForDial(ssidForBind)
                    }
                    if (!CarPlayAccessoryNet.hasNetwork()) {
                        throw IllegalStateException("accessory Wi-Fi Network not bound")
                    }
                    Log.i(
                        CarPlayConsts.TAG,
                        "AirPlay source dial from StartSession $host:$port " +
                            "deviceId=${start.deviceId} attempt=$attempt mode=wifi",
                    )
                    // Probe reachability before committing the AirPlay handshake thread.
                    CarPlayAccessoryNet.openTcp().use { probe ->
                        probe.connect(java.net.InetSocketAddress(addr, port), 2_500)
                    }
                    // HU reachable — warm Host/VD/audio before SETUP so first frame is ready.
                    prepareMediaPipeline("airplay-dial")
                    val bySsid = CarPlayProfileResolver.matchSsid(start.ssid)
                    val hu = when {
                        bySsid != null &&
                            bySsid.id != CarPlayProfileRegistry.generic.id -> bySsid
                        else -> iap2?.currentHuProfile() ?: CarPlayProfileRegistry.defaultHu()
                    }
                    rtsp?.connectAsAirPlaySource(
                        host = addr,
                        port = port,
                        deviceId = start.deviceId,
                        huProfile = hu,
                        sourceIdentity = CarPlayProfileRegistry.defaultSource(),
                        phoneMac = bluetooth?.localAddress(),
                        wifiMac = wifiHandoff?.staMacForAnnounce(),
                    )
                    return@Thread
                } catch (e: Throwable) {
                    lastErr = e
                    Log.w(
                        CarPlayConsts.TAG,
                        "AirPlay dial attempt=$attempt failed: ${e.message}",
                    )
                    val ssid = start.ssid
                    if (!ssid.isNullOrBlank() &&
                        (e.message?.contains("accessory Wi-Fi") == true ||
                            !CarPlayAccessoryNet.hasNetwork())
                    ) {
                        try {
                            wifiHandoff?.ensureAccessoryWifi(
                                ssid,
                                start.passphrase,
                                start.channel,
                            ) {}
                        } catch (_: Throwable) {
                        }
                    }
                    try {
                        Thread.sleep(750L)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }
            Log.e(CarPlayConsts.TAG, "StartSession dial failed $host:$port after retries", lastErr)
        }, "carplay-start-dial").apply { isDaemon = true }.start()
    }

    /**
     * Host GPU path: pane Surfaces feed [CarPlayGlCompositor], which owns the encoder
     * input Surface. Canvas [FrameEgressTee] is stopped while GL is attached so the two
     * producers never contend.
     */
    fun attachGlCompositor(compositor: CarPlayGlCompositor?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            attachGlCompositorMain(compositor)
        } else {
            mainHandler.post { attachGlCompositorMain(compositor) }
        }
    }

    private fun attachGlCompositorMain(compositor: CarPlayGlCompositor?) {
        glCompositor?.let { prev ->
            if (prev !== compositor) {
                prev.onRateReport = null
                prev.detachEncoder()
            }
        }
        glCompositor = compositor
        compositor?.onRateReport = { target, actual -> onRateReport(target, actual) }
        if (!running.get()) return
        // Host may attach after tee-armed SETUP — rebind with a fresh Surface
        // (Canvas lockCanvas contaminates the prior MediaCodec input on Samsung).
        // Never do that while the HU screen stream is live.
        if (rtsp?.isStreaming() == true) {
            Log.w(CarPlayConsts.TAG, "attachGl skip rebind — screen streaming")
            return
        }
        if (compositor != null) {
            cancelGlRebindRetry()
            if (encoder.currentFps() > 0) {
                bindGlOrTee(supplyFps, forceFreshSurface = true)
            }
        } else if (encoder.currentFps() > 0) {
            bindGlOrTee(supplyFps, forceFreshSurface = false)
        }
    }

    private fun onBonjourResolved(info: NsdServiceInfo) {
        val host = info.host ?: return
        val port = info.port
        // Ignore our own advertisement.
        if (port == rtsp?.localPort) return
        Log.i(CarPlayConsts.TAG, "dialing resolved CarPlay control $host:$port")
        prepareMediaPipeline("bonjour")
        rtsp?.connect(host, port)
    }

    /**
     * Arms encoder + tee at [CarPlayConsts.ENCODE_WIDTH]x[ENCODE_HEIGHT] regardless of the
     * size in the SETUP record: the HU scales the decoded frame to its own display, while the
     * RGB→NV12 conversion cost scales with pixels, so a fixed lower resolution wins.
     */
    private fun ensureEncoder(width: Int, height: Int, forceSurfaceRefresh: Boolean = false) {
        // Serialized on the main looper: DiPlay re-SETUP drives this from worker threads and
        // a concurrent codec start/stop crashed the app (see EXECUTION.md §14).
        // Must *wait* when called from the RTSP worker — otherwise audio SETUP starts while
        // GL/encoder is still posting, and DiPlay plays PCM seconds before the first AVC.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ensureEncoderMain(width, height, forceSurfaceRefresh)
            return
        }
        val done = CountDownLatch(1)
        mainHandler.post {
            try {
                ensureEncoderMain(width, height, forceSurfaceRefresh)
            } finally {
                done.countDown()
            }
        }
        if (!done.await(8, TimeUnit.SECONDS)) {
            Log.w(CarPlayConsts.TAG, "ensureEncoder sync wait timed out 8s — audio may lead video")
        }
    }

    private fun ensureEncoderMain(width: Int, height: Int, forceSurfaceRefresh: Boolean) {
        if (!running.get()) return
        // Screen SETUP can arrive before dial warm-up finishes — arm Host/live first.
        prepareMediaPipeline("video-setup")
        // MIB3 /info pixels as-is (Audi 1412×654). 16-align made SPS 1424×656 while
        // SETUP/CONFIG stayed 1412×654 — HU black + ~12s airplay-control kill.
        val (encW, encH) = if (
            width > 0 && height > 0 &&
            (width != CarPlayConsts.DEFAULT_VIDEO_WIDTH || height != CarPlayConsts.DEFAULT_VIDEO_HEIGHT)
        ) {
            width.coerceAtLeast(16) to height.coerceAtLeast(16)
        } else {
            CarPlayConsts.ENCODE_WIDTH to CarPlayConsts.ENCODE_HEIGHT
        }
        encodeW = encW
        encodeH = encH
        val fps = supplyFps
        // Block until Host GL is up — async ensure previously raced SETUP into test-pattern.
        resolveGlCompositor(waitMs = 5_000L)
        val wantGl = glCompositor != null
        // Keep split VDs / touch shell == encode canvas (Audi /info 1412×654 vs boot 1280×720).
        // Safe while streaming: only resizes VD buffers, does not recycle MediaCodec.
        if (wantGl) {
            CarPlayHostRuntime.resizeShell(encW, encH)
        }
        // Live screen stream: never recycle MediaCodec (mid-stream CONFIG → Audi kill).
        if (rtsp?.isStreaming() == true) {
            if (forceSurfaceRefresh || (wantGl && glCompositor?.isEncoderAttached() != true)) {
                Log.w(
                    CarPlayConsts.TAG,
                    "ensureEncoder skip recycle while streaming " +
                        "force=$forceSurfaceRefresh glAttached=${glCompositor?.isEncoderAttached()}",
                )
            }
            return
        }
        // Skip a second codec recycle when already armed, unless screen SETUP asked for
        // a fresh Surface (onVideoSetup runs before streaming is armed).
        if (!forceSurfaceRefresh && encoder.isRunningAt(encW, encH, fps)) {
            if (wantGl && glCompositor!!.isEncoderAttached()) {
                return
            }
            if (wantGl) {
                bindGlOrTee(fps, forceFreshSurface = true)
            }
            return
        }
        Log.i(
            CarPlayConsts.TAG,
            "ensureEncoder setup=${width}x$height → encode=${encW}x$encH@${fps}fps" +
                " host=${wantGl}" +
                if (forceSurfaceRefresh) " forceSurfaceRefresh" else "",
        )
        bindGlOrTee(fps, forceFreshSurface = forceSurfaceRefresh)
    }

    /** Pull [CarPlayHostRuntime] compositor into this session (sync boot when needed). */
    private fun resolveGlCompositor(waitMs: Long): CarPlayGlCompositor? {
        glCompositor?.let { return it }
        CarPlayHostRuntime.ensureStartedSync(appContext, timeoutMs = waitMs)
        val gl = CarPlayHostRuntime.compositorOrNull() ?: return null
        // Assign locally — do not call ProjectionService.attachGlCompositor (re-enters bind).
        glCompositor = gl
        gl.onRateReport = { target, actual -> onRateReport(target, actual) }
        return gl
    }

    /**
     * Host GL owns the encoder Surface. Canvas [FrameEgressTee] is only a temporary
     * fallback (test pattern) until Host attaches — AA shell is never teed.
     *
     * Important: a MediaCodec input [android.view.Surface] that was ever fed via
     * [android.view.Surface.lockCanvas] (tee path) stays connected as
     * `NATIVE_WINDOW_API_CPU` on several OEMs (Samsung). Rebinding that same Surface as
     * an EGL window then fails with `eglCreateWindowSurface` / EGL_BAD_ALLOC
     * ("already connected"). Always mint a fresh input Surface when (re)attaching GL.
     */
    private fun bindGlOrTee(fps: Int, forceFreshSurface: Boolean = false) {
        val encW = encodeW
        val encH = encodeH
        val gl = resolveGlCompositor(waitMs = 5_000L)
        if (gl != null) {
            tee.setGlExclusive(true)
            if (!forceFreshSurface &&
                gl.isEncoderAttached() &&
                encoder.hasSurfaceInput() &&
                encoder.isRunningAt(encW, encH, fps)
            ) {
                Log.i(CarPlayConsts.TAG, "CarPlaySourceSession feed=gl-compositor (kept)")
                cancelGlRebindRetry()
                return
            }
            for (attempt in 1..3) {
                gl.detachEncoder()
                encoder.start(encW, encH, fps)
                val surface = encoder.inputSurfaceOrNull()
                if (surface == null) {
                    Log.w(CarPlayConsts.TAG, "GL bind: no surface input attempt=$attempt")
                    continue
                }
                if (gl.attachEncoder(surface, encW, encH, fps)) {
                    Log.i(CarPlayConsts.TAG, "CarPlaySourceSession feed=gl-compositor attempt=$attempt")
                    cancelGlRebindRetry()
                    return
                }
                Log.w(CarPlayConsts.TAG, "GL attachEncoder failed attempt=$attempt")
            }
            Log.w(CarPlayConsts.TAG, "GL attachEncoder exhausted — tee-canvas + retry")
        } else {
            Log.w(CarPlayConsts.TAG, "HostRuntime not ready — tee-canvas + retry")
        }
        tee.setGlExclusive(false)
        if (!encoder.isRunningAt(encW, encH, fps) || !encoder.hasSurfaceInput()) {
            encoder.start(encW, encH, fps)
        }
        tee.start(encoder, encW, encH, fps)
        Log.i(CarPlayConsts.TAG, "CarPlaySourceSession feed=tee-canvas")
        scheduleGlRebindRetry()
    }

    private var glRebindRetries = 0
    private val glRebindRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            if (rtsp?.isStreaming() == true) {
                Log.w(CarPlayConsts.TAG, "GL rebind skip — screen streaming")
                return
            }
            if (glCompositor?.isEncoderAttached() == true) {
                glRebindRetries = 0
                return
            }
            glRebindRetries++
            if (glRebindRetries > GL_REBIND_MAX) {
                Log.w(CarPlayConsts.TAG, "GL rebind gave up after $GL_REBIND_MAX tries — still tee-canvas")
                return
            }
            Log.i(CarPlayConsts.TAG, "GL rebind retry $glRebindRetries/$GL_REBIND_MAX")
            bindGlOrTee(supplyFps, forceFreshSurface = true)
        }
    }

    private fun scheduleGlRebindRetry() {
        mainHandler.removeCallbacks(glRebindRunnable)
        if (!running.get()) return
        mainHandler.postDelayed(glRebindRunnable, GL_REBIND_DELAY_MS)
    }

    private fun cancelGlRebindRetry() {
        glRebindRetries = 0
        mainHandler.removeCallbacks(glRebindRunnable)
    }

    /**
     * Pump-thread rate report (10 s window): under-delivery or encoder back-pressure drops
     * the supply 60→30 after [DEGRADE_WINDOWS] bad windows; a healthy 30 fps supply recovers
     * to 60 after [RECOVER_WINDOWS] good ones. The DiPlay host decodes at its own user fps,
     * so this only throttles our production + bandwidth.
     *
     * When [CarPlayGlCompositor] content-caps the tick (slow VD apps like Douyin), the
     * report's target is already 30 — do not treat that as link failure or fight it with
     * encoder recycle. Branch on session [supplyFps], not the compositor effective target.
     */
    private fun onRateReport(targetFps: Int, actualFps: Float) {
        if (!running.get() || encoder.currentFps() == 0) return
        if (glCompositor?.isContentLimited() == true) {
            // Content is the bottleneck; reset degrade hysteresis so a later quiet
            // UI scene can recover cleanly without a stale bad-window streak.
            badRateWindows = 0
            goodRateWindows = 0
            lastDropCount = encoder.dropCount()
            return
        }
        val drops = encoder.dropCount()
        val dropRatePerSec = (drops - lastDropCount) * 1000f / RATE_REPORT_WINDOW_MS
        lastDropCount = drops
        // Prefer session supply over compositor effective (may already be pressure-capped).
        val sessionTarget = supplyFps
        val poor = actualFps < sessionTarget * CarPlayConsts.DEGRADE_RATE_FRACTION ||
            dropRatePerSec > sessionTarget * 0.15f
        val good = !poor && actualFps >= sessionTarget * 0.8f
        if (sessionTarget >= CarPlayConsts.DEFAULT_VIDEO_FPS) {
            badRateWindows = if (poor) badRateWindows + 1 else 0
            goodRateWindows = 0
            if (badRateWindows >= DEGRADE_WINDOWS) {
                badRateWindows = 0
                Log.w(
                    CarPlayConsts.TAG,
                    "CarPlay supply degraded 60→30 (rate=${"%.1f".format(actualFps)}fps " +
                        "dropRate=${"%.1f".format(dropRatePerSec)}fps effTarget=$targetFps)",
                )
                applySupplyFps(CarPlayConsts.DEGRADED_VIDEO_FPS)
            }
        } else {
            goodRateWindows = if (good) goodRateWindows + 1 else 0
            badRateWindows = 0
            if (goodRateWindows >= RECOVER_WINDOWS) {
                goodRateWindows = 0
                Log.i(
                    CarPlayConsts.TAG,
                    "CarPlay supply recovered 30→60 (rate=${"%.1f".format(actualFps)}fps)",
                )
                applySupplyFps(CarPlayConsts.DEFAULT_VIDEO_FPS)
            }
        }
    }

    private fun applySupplyFps(fps: Int) {
        supplyFps = fps
        // Soft cap the GL pump immediately (no codec recycle). Encoder rebind below may
        // be deferred while screen streaming — pressure still cuts GPU/encode work now.
        glCompositor?.setPressureFps(
            if (fps < CarPlayConsts.DEFAULT_VIDEO_FPS) fps else 0,
        )
        mainHandler.post {
            if (!running.get()) return@post
            if (rtsp?.isStreaming() == true) {
                Log.w(CarPlayConsts.TAG, "applySupplyFps=$fps deferred — screen streaming")
                return@post
            }
            if (encoder.isRunningAt(encodeW, encodeH, fps) &&
                (glCompositor == null || glCompositor!!.isEncoderAttached())
            ) {
                return@post
            }
            lastDropCount = encoder.dropCount()
            glCompositor?.detachEncoder()
            bindGlOrTee(fps)
        }
    }

    /** Prompt the 300s Bluetooth discoverable system dialog (pairing / re-pair). */
    fun requestDiscoverableDialog() {
        bluetooth?.requestDiscoverableDialog()
            ?: CarPlayBluetoothLink.launchDiscoverableDialog(appContext)
    }

    private fun schedulePhoneAudioRelease(reason: String) {
        if (!running.get()) return
        audioReleaseEpoch.incrementAndGet()
        pendingAudioReleaseReason = reason
        Log.i(CarPlayConsts.TAG, "schedule phone audio release ($reason)")
        mainHandler.removeCallbacks(releasePhoneAudio)
        mainHandler.postDelayed(releasePhoneAudio, PHONE_AUDIO_RELEASE_DELAY_MS)
    }

    private fun cancelPhoneAudioRelease() {
        audioReleaseEpoch.incrementAndGet()
        mainHandler.removeCallbacks(releasePhoneAudio)
    }

    private fun reportOwnerLive(live: Boolean) {
        val aggregate = ProjectionSessionGate.setOwnerLive(
            ProjectionSessionGate.OWNER_CARPLAY,
            live,
        )
        try {
            CoreApi.reportCarPlaySessionLive(aggregate)
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "reportCarPlaySessionLive($aggregate) failed", e)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        cancelPhoneAudioRelease()
        val (sent, bytes) = rtsp?.rtpStats() ?: (0L to 0L)
        Log.i(CarPlayConsts.TAG, "CarPlaySourceSession stop rtpSent=$sent rtpBytes=$bytes")
        cancelGlRebindRetry()
        tee.onRateReport = null
        glCompositor?.onRateReport = null
        glCompositor?.detachEncoder()
        tee.setGlExclusive(false)
        tee.stop()
        encoder.stop()
        supplyFps = CarPlayConsts.DEFAULT_VIDEO_FPS
        badRateWindows = 0
        goodRateWindows = 0
        lastDropCount = 0L
        CarPlayNowPlaying.stop()
        wifiHandoff?.release()
        wifiHandoff = null
        bluetooth?.stop()
        bluetooth = null
        iap2 = null
        bonjour?.stop()
        bonjour = null
        rtsp?.stop()
        rtsp = null
        mediaPrepared.set(false)
        // Release CP owner only; aggregate report happens in ProjectionService.tearDown.
        ProjectionSessionGate.setOwnerLive(ProjectionSessionGate.OWNER_CARPLAY, false)
    }

    companion object {
        /** Matches [FrameEgressTee] evaluation window. */
        private const val RATE_REPORT_WINDOW_MS = 10_000L

        /** 8 x 10 s of poor supply before the 60 -> 30 downgrade (GL path validation). */
        private const val DEGRADE_WINDOWS = 8

        /** 6 x 10 s of a healthy 30 fps supply before climbing back to 60. */
        private const val RECOVER_WINDOWS = 6

        private const val GL_REBIND_DELAY_MS = 1_000L
        private const val GL_REBIND_MAX = 15

        /** Real HU drop vs DiPlay TEARDOWN→SETUP. Volume stays held inside this window. */
        private const val PHONE_AUDIO_RELEASE_DELAY_MS = 4_000L
    }
}