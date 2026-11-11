package io.github.nitsuya.aa.display.carplay

import android.content.Context
import android.util.Log
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.carplay.profile.CarPlayHuProfile
import io.github.nitsuya.aa.display.carplay.profile.CarPlayProfileRegistry
import io.github.nitsuya.aa.display.carplay.profile.CarPlaySourceIdentity
import io.github.nitsuya.aa.display.carplay.protocol.AirPlayIdentityStore
import io.github.nitsuya.aa.display.carplay.protocol.AirPlayPairVerify
import io.github.nitsuya.aa.display.carplay.protocol.AirPlayPairing
import io.github.nitsuya.aa.display.carplay.protocol.BinaryPlist
import io.github.nitsuya.aa.display.carplay.protocol.CarPlayEventChannel
import io.github.nitsuya.aa.display.carplay.protocol.CarPlayTiming
import io.github.nitsuya.aa.display.carplay.protocol.ControlChannelCrypto
import io.github.nitsuya.aa.display.carplay.protocol.RtpH264Packetizer
import io.github.nitsuya.aa.display.carplay.protocol.RtspMessage
import io.github.nitsuya.aa.display.carplay.protocol.ScreenStreamEgress
import io.github.nitsuya.aa.display.carplay.protocol.WifiIap2Link
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Accepts / dials the CarPlay control RTSP channel, answers SETUP/RECORD,
 * and can emit H.264 as RTP/AVP/TCP interleaved frames.
 */
class CarPlayRtspSession(
    private val appContext: Context,
    private val onVideoSetup: (width: Int, height: Int) -> Unit,
    private val onSessionReady: () -> Unit,
    private val onTouchEvent: (x: Float, y: Float, action: Int) -> Unit,
    private val onForceKeyFrame: () -> Unit = {},
    /** Bluetooth-negotiated LSP. Null until iAP link sync has finished. */
    private val linkSyncBytes: () -> ByteArray? = { null },
    /**
     * HU media producers dropped (not listen-socket stop).
     * Caller debounces before restoring phone volume.
     */
    private val onHuMediaEnded: (reason: String) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private val streaming = AtomicBoolean(false)
    /** True while [connectAsAirPlaySource] owns the live control/media session. */
    private val airplaySourceActive = AtomicBoolean(false)
    /**
     * Bumped per [connectAsAirPlaySource]. A newer dial supersedes an in-flight
     * one: the old thread self-aborts on the epoch bump, and its [finally] must
     * not release the shared media streams the new dial owns (the pre-fix
     * finally killed the *winner* — the connect→flash→reconnect loop).
     */
    private val dialEpoch = AtomicInteger(0)
    /**
     * Bumped on every [attachPeer] session attach. [handlePeer] teardown only
     * releases shared state while it is still the current peer — a superseded
     * (or lab loopback) socket closing later must not null the live output /
     * release the media (mirrors Iap2Session's transportUsers guard; the RFCOMM
     * layer already had it, the TCP path did not).
     */
    private val peerGeneration = AtomicInteger(0)
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var peer: Socket? = null
    @Volatile
    private var output: BufferedOutputStream? = null
    private val writeLock = Any()
    private val packetizer = RtpH264Packetizer()
    private val rtpChannel = AtomicInteger(0)
    private val rtpSent = AtomicLong(0)
    private val rtpBytes = AtomicLong(0)
    private val controlWriteKey = AtomicReference<ByteArray?>(null)
    private val controlReadKey = AtomicReference<ByteArray?>(null)
    private val screenEgress = AtomicReference<ScreenStreamEgress?>(null)
    private val mediaAudioEgress = AtomicReference<CarPlayAudioEgress?>(null)
    /** MIB3 / Audi: type-102 AAC-LC (PCM type-100 stays silent on this HU). */
    private val mediaAacEgress = AtomicReference<CarPlayAacAudioEgress?>(null)
    /** Negotiated from /info audioFormats; default 48 kHz AAC-LC stereo. */
    @Volatile private var accessoryAacSampleRate: Int = 48_000
    @Volatile private var accessoryAacFormatBit: Long =
        CarPlayAacAudioEgress.AUDIO_FORMAT_AAC_LC_48000_STEREO
    private val speechAudioEgress = AtomicReference<CarPlayAudioEgress?>(null)
    private val eventChannel = AtomicReference<CarPlayEventChannel?>(null)
    /**
     * Re-POST modesChanged (phone owns MainAudio). Set after announce helper exists;
     * used by BORROW restore and the post-arm burst only — not cadence-gap / periodic
     * heartbeat (those latch Audi mute while AAC still flows).
     */
    private val modesRestored = AtomicReference<(() -> Unit)?>(null)
    private val lastModesReclaimMs = AtomicLong(0L)
    private val wifiIap = AtomicReference<WifiIap2Link?>(null)

    private companion object {
        /** Min gap between non-forced reclaims (BORROW restore path). */
        private const val MODES_RECLAIM_MIN_GAP_MS = 8_000L
        /** After the control pump is up, do not hold AAC RTP longer than this for the claim. */
        private const val AAC_CLAIM_FALLBACK_MS = 2_000L
    }
    /** Phone iAP frames waiting to go out on the RTSP control socket. */
    private val iapTx = ArrayDeque<ByteArray>()
    private val iapTxLock = Any()
    private val controlIapFlush = AtomicReference<(() -> Unit)?>(null)
    /** Sole control-socket reader after screen arm; demux responses by CSeq. */
    private val controlPumpLive = AtomicBoolean(false)
    private val controlPending =
        ConcurrentHashMap<Int, ArrayBlockingQueue<RtspMessage>>()
    private val controlOnMessage = AtomicReference<((RtspMessage) -> Boolean)?>(null)
    /** CSeq for pump-thread POST /feedback (high band — avoids handshake cseq clash). */
    private val feedbackCseq = AtomicInteger(900_000)
    private val controlPumpThread = AtomicReference<Thread?>(null)
    private val timing = AtomicReference<CarPlayTiming?>(null)
    private val keepAlive = AtomicReference<DatagramSocket?>(null)
    private val lastCodecConfig = AtomicReference<ByteArray?>(null)
    private val pairSessionKey = AtomicReference<ByteArray?>(null)
    /** Accessory main display from GET /info — HID touch report pixel space (DiPlay). */
    @Volatile private var accessoryTouchMaxX = CarPlayConsts.DEFAULT_VIDEO_WIDTH
    @Volatile private var accessoryTouchMaxY = CarPlayConsts.DEFAULT_VIDEO_HEIGHT
    @Volatile private var accessoryDisplayUuid: String? = null
    /** `/info` hidDevices[].uuid as strings ("0"/"1" on Audi, hex on DiPlay). */
    private val accessoryHidUuids = CopyOnWriteArrayList<String>()
    private val identityStore by lazy { AirPlayIdentityStore(appContext.applicationContext) }

    val localPort: Int
        get() = serverSocket?.localPort ?: 0

    fun isStreaming(): Boolean = streaming.get()

    fun rtpStats(): Pair<Long, Long> {
        val screen = screenEgress.get()
        return if (screen != null) screen.stats() else rtpSent.get() to rtpBytes.get()
    }

    fun startServer() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "carplay-rtsp-server", isDaemon = true) {
            try {
                val server = ServerSocket(0)
                serverSocket = server
                Log.i(CarPlayConsts.TAG, "RTSP control listening port=${server.localPort}")
                while (running.get()) {
                    val socket = try {
                        server.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    // One thread per peer — loopback must not block HU / diplay probes.
                    thread(name = "carplay-rtsp-peer", isDaemon = true) {
                        attachPeer(socket)
                    }
                }
            } catch (e: Throwable) {
                if (running.get()) Log.e(CarPlayConsts.TAG, "RTSP server failed", e)
            }
        }
    }

    fun connect(host: InetAddress, port: Int) {
        thread(name = "carplay-rtsp-client", isDaemon = true) {
            if (!running.get()) running.set(true)
            try {
                val socket = CarPlayAccessoryNet.openTcp()
                socket.connect(InetSocketAddress(host, port), 15_000)
                Log.i(CarPlayConsts.TAG, "RTSP connected to $host:$port")
                attachPeer(socket)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "RTSP connect failed", e)
            }
        }
    }

    /**
     * Source→receiver: after iAP2 CarPlayStartSession, dial HU AirPlay port,
     * GET /info, then HomeKit Pair-Setup (transient preferred).
     */
    fun connectAsAirPlaySource(
        host: InetAddress,
        port: Int,
        deviceId: String?,
        huProfile: CarPlayHuProfile = CarPlayProfileRegistry.defaultHu(),
        sourceIdentity: CarPlaySourceIdentity = CarPlayProfileRegistry.defaultSource(),
        phoneMac: String? = null,
        wifiMac: String? = null,
    ) {
        if (streaming.get()) {
            // A live control session is serving the HU — a duplicate 0x4301 /
            // mDNS dial must not tear it.
            Log.i(CarPlayConsts.TAG, "AirPlay source dial skipped — session live")
            return
        }
        // Supersede any previous dial before it can race this one on
        // peer/output/pump: bump the epoch first, then close the stale socket
        // so the old thread unwinds fast. The old thread's finally detects the
        // epoch bump and skips the shared release.
        val dialGen = dialEpoch.incrementAndGet()
        // Bump the peer generation before closing the stale socket: its
        // handlePeer finally (ownsSession) only releases shared media while its
        // captured generation is still current. Without the bump, a stale
        // *inbound* peer (attached, not streaming) closing just after our dial
        // entry would pass the generation check and releaseMediaStreams —
        // arming the 4s phone-audio release that tears the Host mid-handshake.
        synchronized(writeLock) { peerGeneration.incrementAndGet() }
        val stale = peer
        if (stale != null) {
            runCatching { stale.close() }
        }
        airplaySourceActive.set(true)
        thread(name = "carplay-airplay-source", isDaemon = true) {
            if (!running.get()) running.set(true)
            var currentSocket: Socket? = null
            try {
                val mib3 = huProfile.usesMib3WirelessPath
                val loopback = host.isLoopbackAddress
                var socket = CarPlayAccessoryNet.openTcp(allowUnbound = loopback)
                currentSocket = socket
                socket.connect(InetSocketAddress(host, port), 15_000)
                socket.soTimeout = 15_000
                Log.i(
                    CarPlayConsts.TAG,
                    "AirPlay source dial $host:$port deviceId=$deviceId hu=${huProfile.id}" +
                        if (loopback) " loopback" else "",
                )
                // Superseded while our connect was in flight — the newer dial
                // owns the session; do not claim peer/output.
                if (dialGen != dialEpoch.get()) {
                    socket.closeQuietly()
                    return@thread
                }
                peer = socket
                var input = BufferedInputStream(socket.getInputStream())
                var out = BufferedOutputStream(socket.getOutputStream())
                if (dialGen == dialEpoch.get()) {
                    output = out
                }
                var cseq = 1
                fun exchange(req: RtspMessage): RtspMessage? {
                    synchronized(writeLock) {
                        out.write(req.toBytes())
                        out.flush()
                    }
                    Log.i(CarPlayConsts.TAG, "AirPlay → ${req.firstLine} body=${req.body.size}B")
                    val headerBuf = ByteArrayOutputLimited(64 * 1024)
                    while (true) {
                        // Superseded by a newer dial — abort the exchange so the
                        // body unwinds (its finally skips the shared release).
                        if (dialGen != dialEpoch.get()) {
                            socket.closeQuietly()
                            return null
                        }
                        val b = input.read()
                        if (b < 0) return null
                        headerBuf.write(b)
                        if (!headerBuf.endsWithCrlfCrlf()) continue
                        val provisional = RtspMessage.parse(headerBuf.toByteArray()) ?: return null
                        headerBuf.reset()
                        val contentLen = provisional.headers.entries
                            .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
                            ?.value?.trim()?.toIntOrNull() ?: 0
                        val body = if (contentLen > 0) {
                            val buf = ByteArray(contentLen)
                            var got = 0
                            while (got < contentLen) {
                                val n = input.read(buf, got, contentLen - got)
                                if (n < 0) break
                                got += n
                            }
                            buf.copyOf(got)
                        } else {
                            ByteArray(0)
                        }
                        return provisional.copy(body = body)
                    }
                }
                fun reconnectForPairVerify() {
                    try {
                        socket.close()
                    } catch (_: Throwable) {
                    }
                    socket = CarPlayAccessoryNet.openTcp(allowUnbound = loopback)
                    currentSocket = socket
                    socket.connect(InetSocketAddress(host, port), 15_000)
                    socket.soTimeout = 15_000
                    if (dialGen != dialEpoch.get()) {
                        socket.closeQuietly()
                        return
                    }
                    peer = socket
                    input = BufferedInputStream(socket.getInputStream())
                    out = BufferedOutputStream(socket.getOutputStream())
                    if (dialGen == dialEpoch.get()) {
                        output = out
                    }
                    cseq = 1
                    Log.i(CarPlayConsts.TAG, "AirPlay reconnected for pair-verify fast path")
                }
                val userAgent = huProfile.userAgent
                val hkp = huProfile.hkp
                fun post(
                    path: String,
                    body: ByteArray,
                    contentType: String,
                    extraHeaders: Map<String, String> = emptyMap(),
                ): ByteArray? {
                    val headers = linkedMapOf(
                        "CSeq" to (cseq++).toString(),
                        "User-Agent" to userAgent,
                        "X-Apple-HKP" to hkp,
                        "Content-Type" to contentType,
                        "Content-Length" to body.size.toString(),
                    )
                    headers.putAll(extraHeaders)
                    val req = RtspMessage("POST $path RTSP/1.0", headers, body)
                    val resp = exchange(req) ?: return null
                    logAirPlayResponse(path, resp)
                    if (!resp.firstLine.contains("200")) {
                        Log.w(CarPlayConsts.TAG, "AirPlay $path non-200: ${resp.firstLine}")
                        // Only 403 aborts into the pair-verify fast path; other codes
                        // let AirPlayPairing try the next M1 variant (DiPlay).
                        if (resp.firstLine.contains("403")) {
                            throw IOException("POST $path ${resp.firstLine}")
                        }
                        return null
                    }
                    return resp.body
                }

                fun postPairing(path: String, body: ByteArray, contentType: String): ByteArray? {
                    // CarDeck / catplay PairingHelperTx: pair-verify needs X-Apple-PD: 1
                    // or Audi returns HTTP 403 before TLV parsing.
                    val extra = if (path.contains("pair-verify", ignoreCase = true)) {
                        mapOf("X-Apple-PD" to "1")
                    } else {
                        emptyMap()
                    }
                    return post(path, body, contentType, extra)
                }

                fun fetchInfo() {
                    val info = exchange(
                        RtspMessage(
                            "GET /info RTSP/1.0",
                            linkedMapOf(
                                "CSeq" to (cseq++).toString(),
                                "User-Agent" to userAgent,
                                "DACP-ID" to (deviceId ?: "AADISPLAY"),
                                "Active-Remote" to "AADisplay",
                                "X-Apple-HKP" to hkp,
                            ),
                        ),
                    )
                    logAirPlayResponse("info", info)
                    info?.body?.let { parseAccessoryInfo(it) }
                }
                fun fetchOptions() {
                    val options = exchange(
                        RtspMessage(
                            "OPTIONS * RTSP/1.0",
                            linkedMapOf(
                                "CSeq" to (cseq++).toString(),
                                "User-Agent" to userAgent,
                                "X-Apple-HKP" to hkp,
                            ),
                        ),
                    )
                    logAirPlayResponse("options", options)
                }
                // Our proven order: MIB3 needs OPTIONS before /info (else 455);
                // DiPlay accepts /info first. Do not skip preheat — CarDeck's
                // "info poisons pair-setup" is a hardening concern only if setup
                // then returns 403 (caught below), not a reason to drop our path.
                if (mib3) {
                    fetchOptions()
                    fetchInfo()
                } else {
                    fetchInfo()
                    fetchOptions()
                }

                val preferredId = deviceId ?: phoneMac ?: "AADisplay"
                val controller = identityStore.loadOrCreateController(preferredId)
                val savedAccessory = identityStore.loadAccessory()
                // Primary: our pair-setup → pair-verify (unchanged).
                // Hardening only: HTTP 403 → reconnect + verify with persisted controller.
                var identity: AirPlayPairing.Identity? = null
                try {
                    val pairing = AirPlayPairing(
                        controllerId = controller.pairingId,
                        exchange = { path, body, ct -> postPairing(path, body, ct) },
                        persistentController = controller,
                        onAccessoryPaired = { id, ltpk -> identityStore.saveAccessory(id, ltpk) },
                    )
                    val setup = pairing.runPairSetup(preferTransient = false)
                    identity = setup?.identity
                    if (identity != null) {
                        Log.i(
                            CarPlayConsts.TAG,
                            "AirPlay pair-setup success transient=${setup?.transient} " +
                                "accessoryId=${identity.accessoryId} " +
                                "sessionKey=${setup?.sessionKey?.size ?: 0}B",
                        )
                    } else {
                        Log.e(CarPlayConsts.TAG, "AirPlay pair-setup failed")
                        return@thread
                    }
                } catch (e: IOException) {
                    val forbidden = e.message?.contains("403") == true
                    if (!forbidden) {
                        Log.e(CarPlayConsts.TAG, "AirPlay pair-setup failed", e)
                        return@thread
                    }
                    Log.w(
                        CarPlayConsts.TAG,
                        "AirPlay pair-setup 403 → hardening pair-verify fast path " +
                            "(persisted controller; does not replace primary setup)",
                    )
                    reconnectForPairVerify()
                    identity = identityStore.toPairingIdentity(controller, savedAccessory)
                }
                val pairedIdentity = identity
                if (pairedIdentity == null) {
                    Log.e(CarPlayConsts.TAG, "AirPlay pairing identity missing")
                    return@thread
                }
                val verify = AirPlayPairVerify { path, body, ct -> postPairing(path, body, ct) }
                    .run(pairedIdentity)
                if (verify == null) {
                    Log.e(CarPlayConsts.TAG, "AirPlay pair-verify failed")
                    return@thread
                }
                controlWriteKey.set(verify.writeKey)
                controlReadKey.set(verify.readKey)
                pairSessionKey.set(verify.sharedSecret)
                Log.i(
                    CarPlayConsts.TAG,
                    "AirPlay pair-verify success writeKey=${verify.writeKey.size}B " +
                        "readKey=${verify.readKey.size}B shared=${verify.sharedSecret.size}B",
                )
                socket.soTimeout = 30_000
                val crypto = ControlChannelCrypto(verify.writeKey, verify.readKey)
                val ok = runEncryptedSetupRecord(
                    peerHost = host,
                    localAddress = socket.localAddress,
                    deviceId = deviceId,
                    cseqStart = cseq,
                    sharedSecret = verify.sharedSecret,
                    crypto = crypto,
                    input = input,
                    out = out,
                    socket = socket,
                    huProfile = huProfile,
                    sourceIdentity = sourceIdentity,
                    phoneMac = phoneMac,
                    wifiMac = wifiMac,
                    dialGen = dialGen,
                )
                if (!ok) {
                    Log.e(CarPlayConsts.TAG, "encrypted SETUP/RECORD / screen open failed")
                    return@thread
                }
                // Keep encrypted control alive (feedback); video is on dataPort.
                // Short timeout so queued control-socket iAP can be written between reads.
                socket.soTimeout = 250
                controlPumpThread.set(Thread.currentThread())
                controlPumpLive.set(true)
                pumpEncryptedControl(crypto, input, out, dialGen)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "AirPlay source dial failed", e)
            } finally {
                if (dialGen != dialEpoch.get()) {
                    // Superseded by a newer dial — that one owns the control
                    // session / media now. The pre-fix finally cleared every
                    // shared reference and released the media, killing the
                    // *winner* (connect→flash→reconnect loop). Only make sure
                    // our socket is gone; leave the shared state alone.
                    runCatching { currentSocket?.close() }
                    Log.i(CarPlayConsts.TAG, "AirPlay source dial superseded — exiting without release")
                } else {
                    controlPumpLive.set(false)
                    controlPumpThread.set(null)
                    controlOnMessage.set(null)
                    controlPending.clear()
                    controlIapFlush.set(null)
                    synchronized(iapTxLock) { iapTx.clear() }
                    airplaySourceActive.set(false)
                    // DiPlay resets control → must stop UDP audio / screen TCP or reconnect
                    // Auth dies under zombie streams ("连接中断" loop).
                    releaseMediaStreams("airplay-control-ended")
                }
            }
        }
    }

    /** 4-byte UDP ping every 10s. Delphi advertises the port after session SETUP. */
    private fun startKeepAlive(peer: InetAddress, port: Int) {
        val socket = try {
            CarPlayAccessoryNet.openUdp()
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "keepAlive bind failed", e)
            return
        }
        keepAlive.getAndSet(socket)?.close()
        thread(name = "carplay-keepalive", isDaemon = true) {
            val buf = ByteArray(4)
            while (!socket.isClosed) {
                Random.nextBytes(buf)
                try {
                    socket.send(DatagramPacket(buf, buf.size, peer, port))
                } catch (_: Throwable) {
                    break
                }
                try {
                    Thread.sleep(10_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        Log.i(CarPlayConsts.TAG, "keepAlive → $peer:$port")
    }

    /**
     * Drop live media producers without tearing down the RTSP listen socket.
     * Safe to call repeatedly from control death, TEARDOWN, or iAP RFCOMM close.
     */
    fun releaseMediaStreams(reason: String) {
        streaming.set(false)
        mediaAudioEgress.getAndSet(null)?.stop()
        mediaAacEgress.getAndSet(null)?.stop()
        speechAudioEgress.getAndSet(null)?.stop()
        screenEgress.getAndSet(null)?.close()
        wifiIap.getAndSet(null)?.also { link ->
            CarPlayNowPlaying.detachEgress(link)
            link.close()
        }
        eventChannel.getAndSet(null)?.close()
        timing.getAndSet(null)?.close()
        keepAlive.getAndSet(null)?.close()
        modesRestored.set(null)
        CarPlayWheelController.setEnabled(false)
        CarPlayMediaClock.clear()
        Log.i(CarPlayConsts.TAG, "releaseMediaStreams reason=$reason")
        // rtsp-stop is FGS/session teardown, which restores volume itself.
        if (reason != "rtsp-stop") {
            onHuMediaEnded(reason)
        }
    }

    /** MIB3 Wi‑Fi iAP2 NowPlaying transport, or null when DiPlay / not yet armed. */
    fun mib3NowPlayingEgress(): WifiIap2Link? =
        wifiIap.get()?.takeIf { it.isMib3NowPlayingEgress }

    /**
     * Re-assert phone owns MainAudio on a **background** thread.
     * Must never run on the AAC RTP pacer — [exchangeEncrypted] waits for the control
     * pump and will freeze TX (pulledMedia stall + Audi mixer latch).
     */
    private fun scheduleModesReclaimAsync(reason: String, force: Boolean = false) {
        val ev = eventChannel.get()
        if (ev != null && !ev.isPhoneOwningMainAudio()) {
            Log.i(CarPlayConsts.TAG, "modesChanged skip ($reason) — HU owns MainAudio")
            return
        }
        val now = System.currentTimeMillis()
        if (!force) {
            val prev = lastModesReclaimMs.get()
            if (now - prev < MODES_RECLAIM_MIN_GAP_MS) return
            if (!lastModesReclaimMs.compareAndSet(prev, now)) return
        } else {
            lastModesReclaimMs.set(now)
        }
        thread(name = "carplay-modes-reclaim", isDaemon = true) {
            if (!running.get() || !streaming.get()) return@thread
            Log.i(CarPlayConsts.TAG, "modesChanged reclaim async ($reason)")
            runCatching { modesRestored.get()?.invoke() }
                .onFailure { e -> Log.w(CarPlayConsts.TAG, "modesChanged reclaim failed", e) }
        }
    }

    /**
     * After pair-verify: HAP control framing → SETUP(session) → RECORD →
     * SETUP(screen type=110) → TCP dataPort with DataStream ChaCha video.
     * [sharedSecret] is the pair-verify X25519 secret (DiPlay session.sharedSecret).
     */
    private fun runEncryptedSetupRecord(
        peerHost: InetAddress,
        localAddress: InetAddress?,
        deviceId: String?,
        cseqStart: Int,
        sharedSecret: ByteArray,
        crypto: ControlChannelCrypto,
        input: BufferedInputStream,
        out: BufferedOutputStream,
        socket: java.net.Socket,
        huProfile: CarPlayHuProfile = CarPlayProfileRegistry.defaultHu(),
        sourceIdentity: CarPlaySourceIdentity = CarPlayProfileRegistry.defaultSource(),
        phoneMac: String? = null,
        wifiMac: String? = null,
        /** [dialEpoch] at entry — the dial self-aborts once superseded. */
        dialGen: Int = dialEpoch.get(),
    ): Boolean {
        val mib3 = huProfile.usesMib3WirelessPath
        var cseq = cseqStart
        val host = peerHost.hostAddress ?: peerHost.hostName
        val sessionId = (Random.nextLong() and Long.MAX_VALUE).toString()
        val sessionUrl = "rtsp://$host/$sessionId"
        val dacp = (deviceId ?: "AADISPLAY").replace(":", "").uppercase()
        val userAgent = huProfile.userAgent
        val hkp = huProfile.hkp
        fun headers(extra: Map<String, String> = emptyMap()): LinkedHashMap<String, String> {
            val h = linkedMapOf(
                "CSeq" to (cseq++).toString(),
                "User-Agent" to userAgent,
                "DACP-ID" to dacp,
                "Active-Remote" to "AADisplay",
                "X-Apple-HKP" to hkp,
            )
            h.putAll(extra)
            return h
        }
        fun postControlIap(frame: ByteArray): Boolean {
            if (frame.isEmpty()) return false
            synchronized(iapTxLock) { iapTx.addLast(frame) }
            return true
        }
        fun controlReply200(request: RtspMessage) {
            // CSM / iPhone: echo CSeq, no Content-Length on empty body.
            val headers = linkedMapOf<String, String>()
            if (request.cSeq >= 0) headers["CSeq"] = request.cSeq.toString()
            headers["Server"] = userAgent
            val resp = RtspMessage("RTSP/1.0 200 OK", headers)
            synchronized(writeLock) {
                crypto.writeMessage(out, resp.toBytes())
            }
        }
        /** @return true when [msg] was a request (already 200'd). */
        fun handleControlRequest(msg: RtspMessage): Boolean {
            if (!msg.isRequest) return false
            if (msg.method == "POST") {
                val dict = BinaryPlist.asDict(BinaryPlist.decode(msg.body))
                val params = BinaryPlist.asDict(dict?.get("params"))
                val data = params?.get("data") as? ByteArray
                if (dict?.get("type")?.toString() == "iAPSendMessage" && data != null) {
                    val head = data.take(12).joinToString(" ") { "%02x".format(it) }
                    Log.i(CarPlayConsts.TAG, "control iAP ← frame ${data.size}B head=$head")
                    wifiIap.get()?.onInbound(data)
                } else {
                    Log.i(
                        CarPlayConsts.TAG,
                        "control ← ${msg.firstLine} body=${msg.body.size}B " +
                            "type=${dict?.get("type")}",
                    )
                }
            } else {
                Log.i(
                    CarPlayConsts.TAG,
                    "control ← ${msg.firstLine} body=${msg.body.size}B",
                )
            }
            controlReply200(msg)
            return true
        }
        fun deliverControlResponse(msg: RtspMessage): Boolean {
            val seq = msg.cSeq
            if (seq < 0) return false
            val q = controlPending[seq] ?: return false
            return q.offer(msg)
        }
        fun awaitControlResponse(seq: Int, timeoutMs: Long): RtspMessage? {
            val q = controlPending[seq] ?: return null
            return try {
                q.poll(timeoutMs, TimeUnit.MILLISECONDS)
            } finally {
                controlPending.remove(seq, q)
            }
        }
        fun registerControlPending(seq: Int): ArrayBlockingQueue<RtspMessage> {
            val q = ArrayBlockingQueue<RtspMessage>(1)
            controlPending[seq] = q
            return q
        }
        /**
         * Drain inbound until a response, handling interleaved HU POSTs.
         * Only safe when [controlPumpLive] is false (sole reader).
         */
        fun readControlUntilResponse(timeoutMs: Long): RtspMessage? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (running.get() && System.currentTimeMillis() < deadline) {
                if (dialGen != dialEpoch.get()) return null
                val raw = try {
                    crypto.readMessage(input)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } ?: return null
                val msg = RtspMessage.parse(raw) ?: continue
                if (handleControlRequest(msg)) {
                    if (msg.method.equals("TEARDOWN", ignoreCase = true)) return null
                    continue
                }
                return msg
            }
            return null
        }
        // Header and plist are two ChaCha frames, same as the phone's control POST.
        fun flushControlIap() {
            while (running.get()) {
                val frame = synchronized(iapTxLock) {
                    if (iapTx.isEmpty()) return
                    iapTx.removeFirst()
                }
                val body = BinaryPlist.encode(
                    mapOf(
                        "type" to "iAPSendMessage",
                        "params" to mapOf("data" to frame),
                    ),
                    groupKeys = true,
                )
                val seq = cseq++
                val head = RtspMessage(
                    "POST /command RTSP/1.0",
                    linkedMapOf(
                        "Content-Length" to body.size.toString(),
                        "Content-Type" to "application/x-apple-binary-plist",
                        "CSeq" to seq.toString(),
                        "User-Agent" to userAgent,
                    ),
                ).toBytes()
                // Pump thread must never await a reply (it is the sole reader).
                val awaitReply = !controlPumpLive.get() ||
                    Thread.currentThread() !== controlPumpThread.get()
                if (controlPumpLive.get() && awaitReply) {
                    registerControlPending(seq)
                }
                synchronized(writeLock) {
                    crypto.writeFrames(out, head, body)
                }
                Log.i(CarPlayConsts.TAG, "control iAP → POST ${frame.size}B")
                when {
                    !awaitReply -> Unit // 200 arrives as demux orphan on next read
                    controlPumpLive.get() -> {
                        val reply = awaitControlResponse(seq, 2_000L)
                        if (reply == null) {
                            Log.w(CarPlayConsts.TAG, "control iAP POST no 200 cseq=$seq")
                        } else {
                            Log.i(
                                CarPlayConsts.TAG,
                                "control iAP ← ${reply.firstLine} body=${reply.body.size}B",
                            )
                        }
                    }
                    else -> {
                        val reply = readControlUntilResponse(2_000L)
                        if (reply == null) {
                            Log.w(CarPlayConsts.TAG, "control iAP POST no 200")
                        } else {
                            Log.i(
                                CarPlayConsts.TAG,
                                "control iAP ← ${reply.firstLine} body=${reply.body.size}B",
                            )
                        }
                    }
                }
            }
        }
        /**
         * iPhone: screen SETUP → phone detect on control → HU SYN on control.
         * Poll control for the SYN while flushing our detect / SYN|ACK queue.
         */
        fun awaitWifiSyn(timeoutMs: Long) {
            val link = wifiIap.get() ?: return
            link.announce()
            val deadline = System.currentTimeMillis() + timeoutMs
            val prevTimeout = socket.soTimeout
            socket.soTimeout = 100
            try {
                while (running.get() && System.currentTimeMillis() < deadline) {
                    if (dialGen != dialEpoch.get()) return
                    flushControlIap()
                    if (link.hasSyn()) {
                        flushControlIap()
                        Log.i(CarPlayConsts.TAG, "Wi-Fi iAP2 SYN seen on control")
                        return
                    }
                    try {
                        val raw = crypto.readMessage(input) ?: continue
                        val msg = RtspMessage.parse(raw) ?: continue
                        if (handleControlRequest(msg)) {
                            flushControlIap()
                            continue
                        }
                        Log.i(
                            CarPlayConsts.TAG,
                            "control iAP ← ${msg.firstLine} body=${msg.body.size}B",
                        )
                    } catch (_: java.net.SocketTimeoutException) {
                        // keep polling
                    } catch (e: Throwable) {
                        Log.w(CarPlayConsts.TAG, "control poll during Wi-Fi iAP2 failed", e)
                        return
                    }
                }
                flushControlIap()
                if (!link.hasSyn()) {
                    Log.w(CarPlayConsts.TAG, "Wi-Fi iAP2 no SYN within ${timeoutMs}ms")
                }
            } finally {
                socket.soTimeout = prevTimeout
            }
        }
        controlIapFlush.set { flushControlIap() }
        fun exchangeEncrypted(req: RtspMessage): RtspMessage? {
            flushControlIap()
            val seq = req.cSeq
            val plain = req.toBytes()
            val awaitReply = seq >= 0 && (
                !controlPumpLive.get() ||
                    Thread.currentThread() !== controlPumpThread.get()
                )
            if (controlPumpLive.get() && awaitReply) {
                registerControlPending(seq)
            }
            synchronized(writeLock) {
                crypto.writeMessage(out, plain)
            }
            Log.i(
                CarPlayConsts.TAG,
                "AirPlay enc → ${req.firstLine} body=${req.body.size}B plain=${plain.size}B",
            )
            val msg = when {
                !awaitReply -> null // caller on pump thread: 200 is next demux hit
                controlPumpLive.get() -> awaitControlResponse(seq, 5_000L)
                else -> readControlUntilResponse(5_000L)
            } ?: return null
            logAirPlayResponse("enc", msg)
            return msg
        }
        controlOnMessage.set { msg ->
            if (handleControlRequest(msg)) {
                return@set !msg.method.equals("TEARDOWN", ignoreCase = true)
            }
            if (!deliverControlResponse(msg)) {
                Log.i(
                    CarPlayConsts.TAG,
                    "AirPlay enc ← ${msg.firstLine} body=${msg.body.size}B (orphan cseq=${msg.cSeq})",
                )
            }
            true
        }

        val sessionUuid = UUID.randomUUID().toString().uppercase()
        val btMac = phoneMac?.takeIf { it.isNotBlank() }
        val staMac = wifiMac?.takeIf { it.isNotBlank() } ?: btMac
        val setupDeviceId = (if (mib3) btMac ?: deviceId else deviceId ?: btMac)
            ?: "00:00:00:00:00:00"
        val setupMac = (if (mib3) staMac ?: deviceId else deviceId ?: staMac)
            ?: "00:00:00:00:00:00"
        val timingIpv4 = if (mib3) CarPlayTiming.ipv4Beside(localAddress, peerHost) else null
        if (mib3 && timingIpv4 == null) {
            Log.e(
                CarPlayConsts.TAG,
                "timing no IPv4 beside $peerHost local=${localAddress?.hostAddress}",
            )
        }
        val timingSocket = timingIpv4?.let { ip ->
            CarPlayTiming.bind(ip)?.also { timing.getAndSet(it)?.close() }
        }
        val localIp = timingSocket?.localAddress?.hostAddress
        val setupFields = linkedMapOf<String, Any?>(
            "deviceID" to setupDeviceId,
            "macAddress" to setupMac,
            "model" to sourceIdentity.airplayModel,
            "name" to sourceIdentity.airplayName,
            "osBuildVersion" to huProfile.osBuildVersion,
            "osName" to sourceIdentity.airplayOsName,
            "osVersion" to huProfile.osVersion,
            "sessionUUID" to sessionUuid,
            "sourceVersion" to huProfile.sourceVersion,
            "timingProtocol" to "NTP",
        )
        if (mib3) {
            // rvictl: iPhone UA is AirPlay/980.77.1, HKP 2, session body 747B.
            // Delphi answers with a keep-alive UDP port (pcap :5020, 4-byte pings).
            setupFields["sessionCorrelationUUID"] = UUID.randomUUID().toString().uppercase()
            setupFields["keepAliveLowPower"] = true
            setupFields["keepAliveSendStatsAsBody"] = true
            setupFields["internalBuild"] = false
            setupFields["diagnosticsAndUsage"] = false
            setupFields["statsCollectionEnabled"] = false
        }
        if (timingSocket != null && localIp != null) {
            // Audi sends NTP to this port (TCP source IP). timingPeerInfo made
            // Delphi aim at the wrong place; audi.11 only advertises timingPort.
            setupFields["timingPort"] = timingSocket.localPort
            Log.i(
                CarPlayConsts.TAG,
                "timing port $localIp:${timingSocket.localPort}",
            )
        }
        val setupSessionBody = BinaryPlist.encode(setupFields)
        val setup1 = exchangeEncrypted(
            RtspMessage(
                "SETUP $sessionUrl RTSP/1.0",
                headers(
                    mapOf(
                        "Content-Type" to "application/x-apple-binary-plist",
                        "Content-Length" to setupSessionBody.size.toString(),
                    ),
                ),
                setupSessionBody,
            ),
        ) ?: return false
        if (!setup1.firstLine.contains("200")) {
            Log.w(CarPlayConsts.TAG, "SETUP session non-200: ${setup1.firstLine}")
            return false
        }
        val setup1Dict = BinaryPlist.asDict(BinaryPlist.decode(setup1.body))
        val eventPort = BinaryPlist.intOf(setup1Dict?.get("eventPort"))?.toInt()?.and(0xffff)
        val timingPort = BinaryPlist.intOf(setup1Dict?.get("timingPort"))?.toInt()?.and(0xffff)
        val keepAlivePort = BinaryPlist.intOf(setup1Dict?.get("keepAlivePort"))?.toInt()?.and(0xffff)
        Log.i(
            CarPlayConsts.TAG,
            "SETUP session ok eventPort=$eventPort timingPort=$timingPort " +
                "keepAlivePort=$keepAlivePort body=${setupSessionBody.size}B keys=${setup1Dict?.keys}",
        )
        if (keepAlivePort != null && keepAlivePort > 0) {
            startKeepAlive(peerHost, keepAlivePort)
        }
        if (mib3) {
            if (timingPort != null && timingPort > 0) {
                timingSocket?.notePeer(peerHost, timingPort)
            }
            // Plain GET /info is 455 before pair-verify. The display uuid lives on the
            // encrypted channel, after the session exists. iPhone fetches it after the
            // timing peer is named and before RECORD.
            val info = exchangeEncrypted(
                RtspMessage("GET /info RTSP/1.0", headers()),
            )
            if (info != null && info.firstLine.contains("200")) {
                parseAccessoryInfo(info.body)
            } else {
                Log.w(CarPlayConsts.TAG, "encrypted /info ${info?.firstLine ?: "no response"}")
            }
        }
        // modesRestored filled once announcePhoneModesChanged exists (below).
        // Event BORROW/UNBORROW restores call it — never reclaim mid-borrow.
        modesRestored.set(null)
        if (eventPort != null && eventPort > 0) {
            // DiPlay acceptEvent arms Events-* cipher on connect — open before RECORD/screen.
            val lsp = linkSyncBytes()
            val evHolder = AtomicReference<CarPlayEventChannel?>(null)
            val wifi = if (mib3 || lsp != null) {
                WifiIap2Link(
                    linkSync = lsp ?: ByteArray(0),
                    mib3Style = mib3,
                    onNowPlayingUpdates = { CarPlayNowPlaying.onUpdatesRequested() },
                    onFileTransferDatagram = { dg -> CarPlayNowPlaying.onFileTransferDatagram(dg) },
                ) { frame ->
                    postControlIap(frame)
                }
            } else {
                Log.w(CarPlayConsts.TAG, "Wi-Fi iAP2 skipped — Bluetooth LSP not ready")
                null
            }
            val ev = CarPlayEventChannel.open(
                host = peerHost,
                eventPort = eventPort,
                sharedSecret = sharedSecret,
                touchMaxX = accessoryTouchMaxX,
                touchMaxY = accessoryTouchMaxY,
                onForceKeyFrame = onForceKeyFrame,
                onTouch = onTouchEvent,
                onHidKey = { dev, code, pressed, extra ->
                    CarPlayWheelController.onHidKey(dev, code, pressed, extra)
                },
                onIapMessage = { frame -> wifi?.onInbound(frame) },
                onModesRestoredToPhone = {
                    thread(name = "carplay-modes-restored", isDaemon = true) {
                        try {
                            Thread.sleep(80L)
                        } catch (_: InterruptedException) {
                            return@thread
                        }
                        if (!running.get() || !streaming.get()) return@thread
                        modesRestored.get()?.invoke()
                    }
                },
            )
            if (ev != null) {
                // MIB3 reports physical right as HID PREV (and left as NEXT); invert so
                // long-right=swap / long-left=recents matches AA.
                CarPlayWheelController.setEnabled(
                    true,
                    invertMediaLeftRight = mib3,
                    context = appContext,
                )
                evHolder.set(ev)
                eventChannel.getAndSet(ev)?.close()
                wifi?.let {
                    wifiIap.getAndSet(it)?.also { old ->
                        CarPlayNowPlaying.detachEgress(old)
                        old.close()
                    }
                    wifiIap.set(it)
                    it.start()
                    // Rebind on soft re-SETUP once the observer is already live.
                    if (it.isMib3NowPlayingEgress && CarPlayNowPlaying.isStarted()) {
                        CarPlayNowPlaying.attachEgress(it)
                    }
                }
                ev.start()
                // Hardening only: arm HID when /info listed devices. Our touch path
                // already works via inbound hidSendReport without this; do not invent
                // uuid "0"/"1" when the HU omitted hidDevices (would replace nothing
                // useful and can confuse some receivers).
                val hidUuids = accessoryHidUuids.toList()
                if (hidUuids.isNotEmpty()) {
                    val armed = ev.armHidInputModes(hidUuids)
                    Log.i(CarPlayConsts.TAG, "event hidSetInputMode armed=$armed/${hidUuids.size}")
                }
            } else {
                wifi?.close()
            }
        } else {
            Log.w(CarPlayConsts.TAG, "SETUP session missing eventPort — no touch/forceKeyFrame")
        }

        // DiPlay wants timing keys. CSM audi.10 openScreenStream (type 110):
        // streamConnectionID (sign-bit cleared), latencyMs=75, uuid, displayWidth/
        // displayHeight — then VideoStreamSender bare-avcC CONFIG with header[6]=0x04.
        // Delphi 450.14 used displayUUID-only; Audi CSM uses "uuid" + canvas size.
        val streamConnectionId = if (mib3) newStreamConnectionId64() else newStreamConnectionId()
        val timestampInfo = listOf(
            mapOf("name" to "SubSu"),
            mapOf("name" to "BePxT"),
            mapOf("name" to "AfPxT"),
            mapOf("name" to "BefEn"),
            mapOf("name" to "EmEnc"),
        )
        // CSM uses /info widthPixels×heightPixels as-is (no 16-align). Aligning
        // 1412×654 → 1424×656 made SETUP/CONFIG disagree with the panel touch space.
        val screenW = if (mib3) accessoryTouchMaxX.coerceAtLeast(16) else CarPlayConsts.ENCODE_WIDTH
        val screenH = if (mib3) accessoryTouchMaxY.coerceAtLeast(16) else CarPlayConsts.ENCODE_HEIGHT
        val screenStream: Map<String, Any?> = if (mib3) {
            val uuid = accessoryDisplayUuid
            if (uuid == null) {
                Log.e(CarPlayConsts.TAG, "SETUP screen audi missing uuid from /info")
                return false
            }
            linkedMapOf(
                "type" to 110,
                "streamConnectionID" to streamConnectionId,
                "latencyMs" to 75,
                "uuid" to uuid,
                "displayWidth" to screenW,
                "displayHeight" to screenH,
            )
        } else {
            linkedMapOf(
                "type" to 110L,
                "streamConnectionID" to streamConnectionId,
                // Slightly under audioLatencyMs so DiPlay holds PCM until AVC arrives.
                "latencyMs" to 60L,
                "fps" to CarPlayConsts.DEFAULT_VIDEO_FPS.toLong(),
                "usingScreen" to true,
                "supportsHighAccuracyTimestamps" to true,
                "timestampInfo" to timestampInfo,
            )
        }
        fun sendScreenSetup(requested: List<Map<String, Any?>>, label: String): RtspMessage? {
            val setupScreenBody = BinaryPlist.encode(
                mapOf("streams" to requested),
                minObjectRefSize = if (mib3) 2 else 1,
            )
            Log.i(
                CarPlayConsts.TAG,
                "SETUP screen mib3=$mib3 $label ${screenW}x$screenH " +
                    "uuid=${accessoryDisplayUuid ?: "none"} streams=${requested.size} " +
                    "id=$streamConnectionId body=${setupScreenBody.size}B",
            )
            return exchangeEncrypted(
                RtspMessage(
                    "SETUP $sessionUrl RTSP/1.0",
                    headers(
                        mapOf(
                            "Content-Type" to "application/x-apple-binary-plist",
                            "Content-Length" to setupScreenBody.size.toString(),
                        ),
                    ),
                    setupScreenBody,
                ),
            )
        }
        fun sendRecord(): Boolean {
            val record = exchangeEncrypted(
                RtspMessage("RECORD $sessionUrl RTSP/1.0", headers()),
            ) ?: return false
            if (!record.firstLine.contains("200")) {
                Log.w(CarPlayConsts.TAG, "RECORD non-200: ${record.firstLine}")
                return false
            }
            Log.i(CarPlayConsts.TAG, "RECORD ok")
            return true
        }
        fun postControlCommand(plist: Map<String, Any?>, label: String): Boolean {
            // Match CSM Bplist.encode defaults (no forced ref size / key grouping).
            val body = BinaryPlist.encode(plist)
            val resp = exchangeEncrypted(
                RtspMessage(
                    "POST /command RTSP/1.0",
                    headers(
                        mapOf(
                            "Content-Type" to "application/x-apple-binary-plist",
                            "Content-Length" to body.size.toString(),
                        ),
                    ),
                    body,
                ),
            )
            val ok = resp?.firstLine?.contains("200") == true
            Log.i(
                CarPlayConsts.TAG,
                "control $label → POST ${body.size}B ${if (ok) "ok" else "fail=${resp?.firstLine}"}",
            )
            return ok
        }
        /**
         * iPhone posts modesChanged (~232B) after screen is up; Audi accepts that.
         * CSM's InitialCarPlayConnection changeModes gets 422 on this HU — skip it.
         */
        fun announcePhoneModesChanged(): Boolean = postControlCommand(
            linkedMapOf(
                "type" to "modesChanged",
                "params" to linkedMapOf(
                    // Screen + MainAudio on phone (entity=1). Without resourceID=2 the
                    // Audi mixer stays gray / silent even though type-102 AAC is flowing.
                    "resources" to listOf(
                        linkedMapOf(
                            "resourceID" to 1,
                            "entity" to 1,
                            "permanentEntity" to 1,
                        ),
                        linkedMapOf(
                            "resourceID" to 2,
                            "entity" to 1,
                            "permanentEntity" to 1,
                        ),
                    ),
                    "appStates" to listOf(
                        linkedMapOf("appStateID" to 2, "entity" to 0),
                        linkedMapOf("appStateID" to 3, "entity" to 0),
                        linkedMapOf("appStateID" to 1, "entity" to 0, "speechMode" to -1),
                    ),
                ),
            ),
            "modesChanged phone-owns-screen+audio",
        )
        modesRestored.set { announcePhoneModesChanged() }
        val setup2 = if (mib3) {
            if (!sendRecord()) return false
            // iPhone: answer event commands, then screen SETUP, then control-socket detect.
            val commands = eventChannel.get()?.awaitBeforeScreen(900L) ?: 0
            Log.i(CarPlayConsts.TAG, "event commands before screen=$commands")
            val timingOk = timingSocket?.awaitPacket(1_200L) == true
            if (!timingOk) {
                Log.w(CarPlayConsts.TAG, "timing no packet before screen SETUP")
            }
            val screen = sendScreenSetup(listOf(screenStream), "screen") ?: return false
            if (!screen.firstLine.contains("200")) {
                Log.w(CarPlayConsts.TAG, "SETUP screen audi non-200: ${screen.firstLine}")
                return false
            }
            screen
        } else {
            if (!sendRecord()) return false
            val screen = sendScreenSetup(listOf(screenStream), "diplay") ?: return false
            if (!screen.firstLine.contains("200")) {
                Log.w(CarPlayConsts.TAG, "SETUP screen non-200: ${screen.firstLine}")
                return false
            }
            screen
        }
        val setup2Dict = BinaryPlist.asDict(BinaryPlist.decode(setup2.body))
        val streams = setup2Dict?.get("streams") as? List<*>
        val screenDict = streams?.firstNotNullOfOrNull { item ->
            val dict = BinaryPlist.asDict(item) ?: return@firstNotNullOfOrNull null
            val type = BinaryPlist.intOf(dict["type"])?.toInt()
            if (mib3 && type != 110) null else dict
        } ?: BinaryPlist.asDict(streams?.firstOrNull())
        val dataPortRaw = BinaryPlist.intOf(screenDict?.get("dataPort"))
        val dataPort = dataPortRaw?.toInt()?.and(0xffff)
        if (dataPort == null || dataPort <= 0) {
            Log.e(
                CarPlayConsts.TAG,
                "SETUP screen missing dataPort body=${setup2.body.size}B " +
                    "dictKeys=${setup2Dict?.keys} streamKeys=${screenDict?.keys} raw=$dataPortRaw",
            )
            return false
        }
        Log.i(
            CarPlayConsts.TAG,
            "SETUP screen ok dataPort=$dataPort streamConnectionID=$streamConnectionId",
        )
        // iPhone Frida: after screen dataPort 200, phone detect on control → HU SYN on control.
        if (mib3) awaitWifiSyn(4_000L)

        // MIB3: encode at /info panel size as-is; header content==display==coded.
        // DiPlay keeps the fixed 1280×720 canvas + fourcc-wrapped CONFIG.
        val egress = ScreenStreamEgress.open(
            host = peerHost,
            dataPort = dataPort,
            sessionKey = sharedSecret,
            streamConnectionId = streamConnectionId,
            width = screenW,
            height = screenH,
            displayWidth = screenW,
            displayHeight = screenH,
            wrapAvccFourcc = !mib3,
        ) ?: return false
        screenEgress.getAndSet(egress)?.close()
        // Shared A/V timeline — must start before encoder + audio pumps.
        CarPlayMediaClock.reset()
        // Drop stale SPS/PPS from the pre-SETUP encoder. Refresh the codec *before*
        // arming streaming so ensureEncoder's live-stream gate does not block this one
        // intentional restart; never wire an old High-profile avcC (Audi black + ~8s kill).
        lastCodecConfig.set(null)
        onVideoSetup(screenW, screenH)
        streaming.set(true)
        onForceKeyFrame()

        // Audio: Audi MIB3 needs type-102 AAC-LC (CSM path). DiPlay keeps type-100 PCM.
        // Speech only when system_server has an exclusive nav tap (else DiPlay 重音).
        if (mib3) {
            setupAacMediaStream(
                peerHost,
                sharedSecret,
                { extra -> headers(extra) },
                sessionUrl,
                { req -> exchangeEncrypted(req) },
            )
        } else {
            setupAudioStream(
                peerHost,
                sharedSecret,
                { extra -> headers(extra) },
                sessionUrl,
                { req -> exchangeEncrypted(req) },
                CarPlayAudioEgress.MEDIA,
                mediaAudioEgress,
            )
        }
        val speechReady = !mib3 && try {
            CoreApi.isCarPlaySpeechCaptureReady()
        } catch (_: Throwable) {
            false
        }
        if (speechReady) {
            setupAudioStream(
                peerHost,
                sharedSecret,
                { extra -> headers(extra) },
                sessionUrl,
                { req -> exchangeEncrypted(req) },
                CarPlayAudioEgress.SPEECH,
                speechAudioEgress,
            )
        } else if (!mib3) {
            Log.i(CarPlayConsts.TAG, "skip speech SETUP — no exclusive AudioPolicy nav tap")
        }

        onSessionReady()
        Log.i(CarPlayConsts.TAG, "screen egress armed — AVC frames → dataPort=$dataPort")
        if (mib3) {
            // After Wi-Fi iAP auth+burst (~1s). Claiming during AA00/AA05 races the
            // control socket with iAP POSTs and Audi still dies ~12s later.
            // AAC RTP stays off until this claim (or the fallback): the mixer is
            // unstable while packets arrive before MainAudio is owned.
            thread(name = "carplay-modes-announce", isDaemon = true) {
                try {
                    Thread.sleep(1_500L)
                    // Wait until the sole control reader is up so demux can
                    // deliver the modesChanged 200 (no dual-read race).
                    val waitPump = System.currentTimeMillis() + 3_000L
                    while (!controlPumpLive.get() &&
                        System.currentTimeMillis() < waitPump
                    ) {
                        Thread.sleep(50L)
                    }
                    if (!running.get() || !streaming.get()) return@thread
                    // If the POST hangs, arm RTP anyway so the HU does not drop the stream.
                    val watchdog = thread(name = "carplay-aac-arm", isDaemon = true) {
                        try {
                            Thread.sleep(AAC_CLAIM_FALLBACK_MS)
                        } catch (_: InterruptedException) {
                            return@thread
                        }
                        if (!running.get() || !streaming.get()) return@thread
                        startDeferredAac("claim-timeout")
                    }
                    var claimed = false
                    try {
                        claimed = announcePhoneModesChanged()
                    } catch (e: Throwable) {
                        Log.w(CarPlayConsts.TAG, "modesChanged before AAC failed", e)
                    } finally {
                        watchdog.interrupt()
                        if (running.get() && streaming.get()) {
                            startDeferredAac(if (claimed) "modesChanged" else "modesChanged-fail")
                        }
                    }
                    // Audi can drop MainAudio after the first claim / RTP underrun while AAC
                    // still flows. Re-claim on this dedicated thread only (never AAC TX).
                    // One-shot burst only — a forever 30s heartbeat POST modesChanged made the
                    // HU mixer muddy every half-minute; cadence-gap reclaim during track-change
                    // underrun latched mute while RTP kept flowing.
                    for (delayMs in longArrayOf(4_000L, 12_000L, 30_000L)) {
                        try {
                            Thread.sleep(delayMs)
                        } catch (_: InterruptedException) {
                            return@thread
                        }
                        if (!running.get() || !streaming.get()) return@thread
                        scheduleModesReclaimAsync("burst+$delayMs", force = true)
                    }
                } catch (_: InterruptedException) {
                    if (running.get() && streaming.get()) startDeferredAac("interrupted")
                }
            }
        }
        return true
    }

    /** First successful [CarPlayAacAudioEgress.start] wins; later calls are no-ops. */
    private fun startDeferredAac(reason: String) {
        val egress = mediaAacEgress.get() ?: return
        if (egress.start()) {
            Log.i(CarPlayConsts.TAG, "AAC RTP arm ($reason)")
        }
    }

    /**
     * MIB3 / Audi: SETUP type-102 AAC-LC media (CSM audi.11). Binds a local UDP
     * controlPort like the phone; HU may return its own controlPort in the 200.
     */
    private fun setupAacMediaStream(
        peerHost: InetAddress,
        sharedSecret: ByteArray,
        headers: (Map<String, String>) -> LinkedHashMap<String, String>,
        sessionUrl: String,
        exchangeEncrypted: (RtspMessage) -> RtspMessage?,
    ) {
        val localControl = try {
            CarPlayAccessoryNet.openUdp()
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "AAC local controlPort bind failed", e)
            return
        }
        try {
            val localControlPort = localControl.localPort
            val streamConnectionId = Random.nextLong() and Long.MAX_VALUE
            val rate = accessoryAacSampleRate
            val formatBit = accessoryAacFormatBit
            val audioStream = linkedMapOf<String, Any?>(
                "type" to CarPlayAacAudioEgress.STREAM_TYPE,
                "streamConnectionID" to streamConnectionId,
                "controlPort" to localControlPort,
                "encryptionType" to 0L,
                "audioFormat" to formatBit,
                "sampleRate" to rate,
                "channels" to 2L,
                "bytesPerFrame" to 4L,
                "framesPerPacket" to 1024L,
                "audioMode" to 0L,
                "audioType" to "media",
                "audioLatencyMs" to CarPlayAacAudioEgress.AUDIO_LATENCY_MS,
                "supportsHighAccuracyTimestamps" to true,
                "sr" to rate,
                "spf" to 1024L,
                "ct" to 4L,
            )
            val body = BinaryPlist.encode(mapOf("streams" to listOf(audioStream)))
            val resp = exchangeEncrypted(
                RtspMessage(
                    "SETUP $sessionUrl RTSP/1.0",
                    headers(
                        mapOf(
                            "Content-Type" to "application/x-apple-binary-plist",
                            "Content-Length" to body.size.toString(),
                        ),
                    ),
                    body,
                ),
            )
            if (resp == null || !resp.firstLine.contains("200")) {
                Log.w(
                    CarPlayConsts.TAG,
                    "CarPlay AAC media SETUP rejected: ${resp?.firstLine}",
                )
                localControl.close()
                return
            }
            val egress = CarPlayAacAudioEgress.open(
                peerHost,
                resp,
                sharedSecret,
                streamConnectionId,
                rate,
                formatBit,
            )
            // Keep local control socket open so HU RTCP has a peer (CSM does the same).
            thread(name = "carplay-audio-rtcp", isDaemon = true) {
                val buf = ByteArray(2048)
                try {
                    while (!localControl.isClosed) {
                        try {
                            localControl.receive(DatagramPacket(buf, buf.size))
                        } catch (_: Throwable) {
                            break
                        }
                    }
                } finally {
                    try {
                        localControl.close()
                    } catch (_: Throwable) {
                    }
                }
            }
            mediaAacEgress.getAndSet(egress)?.stop()
            // start() waits for the modesChanged claim on carplay-modes-announce.
        } catch (e: Throwable) {
            try {
                localControl.close()
            } catch (_: Throwable) {
            }
            Log.w(CarPlayConsts.TAG, "CarPlay AAC media SETUP failed (screen continues)", e)
        }
    }

    /**
     * SETUP one type-100 audio stream ([profile]) on the encrypted control channel, then
     * start the UDP pump. Non-fatal: a declined stream only costs that channel.
     */
    private fun setupAudioStream(
        peerHost: InetAddress,
        sharedSecret: ByteArray,
        headers: (Map<String, String>) -> LinkedHashMap<String, String>,
        sessionUrl: String,
        exchangeEncrypted: (RtspMessage) -> RtspMessage?,
        profile: CarPlayAudioEgress.Profile,
        slot: AtomicReference<CarPlayAudioEgress?>,
    ) {
        try {
            val streamConnectionId = Random.nextLong() and Long.MAX_VALUE
            val audioStream = linkedMapOf<String, Any?>(
                "type" to 100L,
                "streamConnectionID" to streamConnectionId,
                "audioType" to profile.audioType,
                "audioFormat" to profile.audioFormatBit,
                "audioLatencyMs" to CarPlayAudioEgress.AUDIO_LATENCY_MS,
            )
            val body = BinaryPlist.encode(mapOf("streams" to listOf(audioStream)))
            val resp = exchangeEncrypted(
                RtspMessage(
                    "SETUP $sessionUrl RTSP/1.0",
                    headers(
                        mapOf(
                            "Content-Type" to "application/x-apple-binary-plist",
                            "Content-Length" to body.size.toString(),
                        ),
                    ),
                    body,
                ),
            )
            if (resp == null || !resp.firstLine.contains("200")) {
                Log.w(
                    CarPlayConsts.TAG,
                    "CarPlay ${profile.label} audio SETUP rejected: ${resp?.firstLine}",
                )
                return
            }
            val egress = CarPlayAudioEgress.open(
                peerHost,
                resp,
                sharedSecret,
                streamConnectionId,
                profile,
            )
            slot.getAndSet(egress)?.stop()
            egress?.start()
        } catch (e: Throwable) {
            Log.w(
                CarPlayConsts.TAG,
                "CarPlay ${profile.label} audio SETUP failed (screen continues)",
                e,
            )
        }
    }

    private fun pumpEncryptedControl(
        crypto: ControlChannelCrypto,
        input: BufferedInputStream,
        out: BufferedOutputStream,
        /** [dialEpoch] at entry — exit without touching shared state when superseded. */
        dialGen: Int,
    ) {
        Log.i(CarPlayConsts.TAG, "encrypted control pump (video on dataPort)")
        var lastFeedbackAt = 0L
        while (running.get()) {
            if (dialGen != dialEpoch.get()) {
                Log.i(CarPlayConsts.TAG, "control pump superseded — exiting")
                break
            }
            controlIapFlush.get()?.invoke()
            val now = System.currentTimeMillis()
            // AirPlay source keep-alive: POST /feedback ~2s. Write-only on the
            // pump thread (never await — that would deadlock the sole reader).
            if (now - lastFeedbackAt >= 2_000L) {
                lastFeedbackAt = now
                try {
                    sendControlFeedback(crypto, out)
                } catch (e: Throwable) {
                    Log.w(CarPlayConsts.TAG, "control feedback failed", e)
                }
            }
            val raw = try {
                crypto.readMessage(input)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (e: Throwable) {
                Log.w(CarPlayConsts.TAG, "encrypted control read ended", e)
                break
            }
            if (raw == null) {
                Log.w(CarPlayConsts.TAG, "encrypted control EOF (peer closed)")
                break
            }
            val msg = RtspMessage.parse(raw) ?: continue
            val cont = controlOnMessage.get()?.invoke(msg) ?: true
            if (!cont) {
                Log.i(CarPlayConsts.TAG, "control TEARDOWN — ending pump")
                break
            }
        }
    }

    /** Lightweight control keep-alive (write-only; 200 lands as demux orphan). */
    private fun sendControlFeedback(
        crypto: ControlChannelCrypto,
        out: BufferedOutputStream,
    ) {
        val seq = feedbackCseq.getAndIncrement()
        val req = RtspMessage(
            "POST /feedback RTSP/1.0",
            linkedMapOf(
                "CSeq" to seq.toString(),
                "Content-Length" to "0",
                "User-Agent" to "AirPlay/980.77.1",
            ),
        )
        synchronized(writeLock) {
            crypto.writeMessage(out, req.toBytes())
        }
        Log.d(CarPlayConsts.TAG, "control feedback → cseq=$seq")
    }

    private fun logAirPlayResponse(label: String, msg: RtspMessage?) {
        if (msg == null) {
            Log.w(CarPlayConsts.TAG, "AirPlay ← $label: no response")
            return
        }
        val preview = msg.body.take(64).joinToString(" ") { "%02x".format(it) }
        Log.i(
            CarPlayConsts.TAG,
            "AirPlay ← $label ${msg.firstLine} body=${msg.body.size}B head=$preview " +
                "public=${msg.headers["Public"] ?: msg.headers["public"]}",
        )
    }

    private fun attachPeer(socket: Socket) {
        val addr = socket.inetAddress
        val isLoopback = addr?.isLoopbackAddress == true
        // DiPlay Bonjour /ctrl-int probes hit our listen port during an active
        // AirPlay-source session. They must never clobber peer/output/streaming
        // or releaseMediaStreams — that was causing connect→flash→reconnect loops.
        val probeOnly = airplaySourceActive.get()
        Log.i(
            CarPlayConsts.TAG,
            "RTSP peer ${addr?.hostAddress} loopback=$isLoopback probeOnly=$probeOnly",
        )
        if (probeOnly) {
            handlePeer(socket, ownsSession = false)
            return
        }
        if (!isLoopback) {
            // A live session is being served — a duplicate inbound control
            // (HU redial / double mDNS resolve) must not replace it. The RFCOMM
            // layer already ignores peers on a live session; the TCP path had
            // no guard, and the late socket's close used to null the live output
            // and release the media.
            var stale: Socket? = null
            val gen = synchronized(writeLock) {
                if (streaming.get()) {
                    null
                } else {
                    stale = peer
                    peer = socket
                    streaming.set(false)
                    peerGeneration.incrementAndGet()
                }
            }
            if (gen == null) {
                Log.i(
                    CarPlayConsts.TAG,
                    "RTSP attach ignored — session live on ${socket.inetAddress?.hostAddress}",
                )
                socket.closeQuietly()
                return
            }
            // Prefer the fresh peer: close any stale (zombie / loopback) one.
            if (stale != null && stale !== socket) stale.closeQuietly()
            handlePeer(socket, ownsSession = true, gen = gen)
            return
        }
        val gen = synchronized(writeLock) {
            if (peer != null) {
                // A real peer is already attached — a loopback probe cannot own it.
                null
            } else {
                peer = socket
                peerGeneration.incrementAndGet()
            }
        }
        if (gen == null) {
            socket.closeQuietly()
            return
        }
        handlePeer(socket, ownsSession = true, gen = gen)
    }

    private fun handlePeer(socket: Socket, ownsSession: Boolean, gen: Int = 0) {
        val input = BufferedInputStream(socket.getInputStream())
        val out = BufferedOutputStream(socket.getOutputStream())
        if (ownsSession) {
            // Only bind the shared output if still the current peer — a
            // superseded owner assigning its closing stream last would point
            // interleaved RTP at a dead socket.
            synchronized(writeLock) {
                if (peer === socket && peerGeneration.get() == gen) {
                    output = out
                }
            }
        }
        val headerBuf = ByteArrayOutputLimited(64 * 1024)
        while (running.get() && !socket.isClosed) {
            val b = try {
                input.read()
            } catch (_: Throwable) {
                -1
            }
            if (b < 0) break
            // RTSP interleaved binary: '$' + channel + len(2) + payload
            if (b == '$'.code && headerBuf.size() == 0) {
                skipInterleaved(input)
                continue
            }
            headerBuf.write(b)
            if (!headerBuf.endsWithCrlfCrlf()) continue
            val headBytes = headerBuf.toByteArray()
            headerBuf.reset()
            val provisional = RtspMessage.parse(headBytes) ?: continue
            val contentLen = provisional.headers.entries
                .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
                ?.value?.trim()?.toIntOrNull() ?: 0
            val body = if (contentLen > 0) {
                val buf = ByteArray(contentLen)
                var got = 0
                while (got < contentLen) {
                    val n = input.read(buf, got, contentLen - got)
                    if (n < 0) break
                    got += n
                }
                buf.copyOf(got)
            } else {
                provisional.body
            }
            val msg = provisional.copy(body = body)
            Log.i(CarPlayConsts.TAG, "RTSP ${msg.firstLine} cSeq=${msg.cSeq} body=${body.size}")
            if (msg.firstLine.startsWith("GET ", ignoreCase = true) ||
                msg.firstLine.startsWith("POST ", ignoreCase = true)
            ) {
                dispatchHttp(msg, out)
            } else {
                dispatch(msg, out)
            }
        }
        if (ownsSession) {
            if (peer === socket && peerGeneration.get() == gen) {
                synchronized(writeLock) { output = null }
                releaseMediaStreams("rtsp-peer-closed")
                Log.i(
                    CarPlayConsts.TAG,
                    "RTSP peer closed rtpSent=${rtpSent.get()} rtpBytes=${rtpBytes.get()}",
                )
            } else {
                // Superseded by a newer peer (or the session stopped) — the live
                // session keeps its output / media; do not release on a stale close.
                Log.i(
                    CarPlayConsts.TAG,
                    "RTSP peer ${socket.inetAddress?.hostAddress} superseded — session kept",
                )
            }
        } else {
            Log.i(CarPlayConsts.TAG, "RTSP probe peer closed (AirPlay source kept)")
        }
    }

    private fun skipInterleaved(input: BufferedInputStream) {
        val ch = input.read()
        val lenHi = input.read()
        val lenLo = input.read()
        if (ch < 0 || lenHi < 0 || lenLo < 0) return
        val len = (lenHi shl 8) or lenLo
        var left = len
        val tmp = ByteArray(minOf(len, 4096))
        while (left > 0) {
            val n = input.read(tmp, 0, minOf(tmp.size, left))
            if (n <= 0) break
            left -= n
        }
        Log.d(CarPlayConsts.TAG, "RTSP interleaved inbound ch=$ch len=$len")
    }

    /**
     * diplay / DiPlay probe `_carplay-ctrl` with AirPlay-style HTTP, e.g.
     * `GET /ctrl-int/1/connect` — answer HTTP 2xx. Keep-alive so follow-up
     * pair-setup / RTSP on the same socket is not forced to reconnect.
     */
    private fun dispatchHttp(msg: RtspMessage, out: BufferedOutputStream) {
        val path = msg.firstLine.substringAfter(' ').substringBefore(' ')
        Log.i(CarPlayConsts.TAG, "HTTP control path=$path")
        val isConnect = path.contains("/ctrl-int/", ignoreCase = true) &&
            path.contains("connect", ignoreCase = true)
        val isInfo = path.contains("info", ignoreCase = true)
        val isPair = path.contains("pair-", ignoreCase = true) ||
            path.contains("auth-setup", ignoreCase = true)
        if (isPair) {
            Log.w(
                CarPlayConsts.TAG,
                "HTTP $path — pair-setup/auth not implemented yet; returning empty 200",
            )
        }
        val body = when {
            isInfo -> minimalInfoPlist()
            else -> ByteArray(0)
        }
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Server: AirTunes/220.68\r\n")
            append("Content-Type: application/x-apple-binary-plist\r\n")
            append("Content-Length: ${body.size}\r\n")
            // DiPlay often sends Connection: close on connect; still prefer keep-alive
            // so a follow-up request on the same TCP is not rejected mid-handshake.
            append(if (isConnect) "Connection: close\r\n" else "Connection: keep-alive\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        try {
            synchronized(writeLock) {
                out.write(headers)
                if (body.isNotEmpty()) out.write(body)
                out.flush()
            }
            Log.i(CarPlayConsts.TAG, "HTTP 200 for $path body=${body.size}B")
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "HTTP reply failed", e)
        }
    }

    /** Tiny binary plist dict so /info probes are not empty MIME mismatches. */
    private fun minimalInfoPlist(): ByteArray {
        // bplist00 + empty dict (0xD0 0x00) is enough for "has a body" probes.
        return byteArrayOf(
            'b'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'i'.code.toByte(),
            's'.code.toByte(), 't'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(),
            0xD0.toByte(), 0x00,
            0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x09,
        )
    }

    private fun dispatch(msg: RtspMessage, out: BufferedOutputStream) {
        val method = msg.method ?: return
        val cSeq = msg.cSeq.coerceAtLeast(0)
        when (method) {
            "OPTIONS" -> {
                reply(
                    out,
                    RtspMessage.ok(
                        cSeq,
                        mapOf("Public" to "OPTIONS, ANNOUNCE, SETUP, RECORD, TEARDOWN, GET_PARAMETER, SET_PARAMETER"),
                    ),
                )
            }
            "SETUP" -> {
                val transport = msg.headers.entries
                    .firstOrNull { it.key.equals("Transport", ignoreCase = true) }
                    ?.value
                    .orEmpty()
                val channel = parseInterleavedChannel(transport)
                rtpChannel.set(channel)
                Log.i(
                    CarPlayConsts.TAG,
                    "CarPlay audio/video SETUP transport=$transport interleaved=$channel",
                )
                onVideoSetup(CarPlayConsts.DEFAULT_VIDEO_WIDTH, CarPlayConsts.DEFAULT_VIDEO_HEIGHT)
                val replyTransport = if (transport.contains("interleaved", ignoreCase = true)) {
                    transport
                } else {
                    "RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}"
                }
                reply(
                    out,
                    RtspMessage.ok(
                        cSeq,
                        mapOf(
                            "Session" to "AADisplay1",
                            "Transport" to replyTransport,
                        ),
                    ),
                )
            }
            "RECORD", "ANNOUNCE" -> {
                reply(out, RtspMessage.ok(cSeq))
                streaming.set(true)
                Log.i(CarPlayConsts.TAG, "RTSP streaming armed (RECORD/ANNOUNCE)")
                onSessionReady()
            }
            "TEARDOWN" -> {
                releaseMediaStreams("rtsp-teardown")
                reply(out, RtspMessage.ok(cSeq))
            }
            "SET_PARAMETER" -> {
                reply(out, RtspMessage.ok(cSeq))
                if (msg.body.isNotEmpty()) {
                    onTouchEvent(0.5f, 0.5f, android.view.MotionEvent.ACTION_DOWN)
                    onTouchEvent(0.5f, 0.5f, android.view.MotionEvent.ACTION_UP)
                }
            }
            else -> reply(out, RtspMessage.ok(cSeq))
        }
    }

    /**
     * Prefer screen dataPort (type 110) when armed; else RTSP interleaved RTP (loopback).
     * @return packets / frames written (0 if not streaming).
     */
    fun sendVideoAccessUnit(
        au: ByteArray,
        ptsUs: Long,
        isKey: Boolean,
        isCodecConfig: Boolean = false,
    ): Int {
        if (isCodecConfig) {
            lastCodecConfig.set(au)
            val screen = screenEgress.get()
            if (screen != null) {
                // Mid-stream encoder recycle used to re-send avcC; Audi closes control.
                if (screen.hasSentConfig()) {
                    Log.i(CarPlayConsts.TAG, "skip mid-stream CONFIG (${au.size}B)")
                    return 0
                }
                val ok = screen.sendCodecConfig(au, ptsUs * 1_000L)
                return if (ok) 1 else 0
            }
            return 0
        }
        if (!streaming.get()) return 0
        val screen = screenEgress.get()
        if (screen != null) {
            if (!screen.hasSentConfig()) {
                val cfg = lastCodecConfig.get() ?: return 0
                if (!screen.sendCodecConfig(cfg, ptsUs * 1_000L)) return 0
            }
            val ok = screen.sendVideo(au, ptsUs * 1_000L, isKey)
            return if (ok) 1 else 0
        }
        val packets = packetizer.packetize(au, ptsUs, markerOnLast = true)
        if (packets.isEmpty()) return 0
        val ch = rtpChannel.get()
        var written = 0
        synchronized(writeLock) {
            val out = output ?: return 0
            try {
                for (pkt in packets) {
                    out.write('$'.code)
                    out.write(ch and 0xFF)
                    out.write((pkt.size shr 8) and 0xFF)
                    out.write(pkt.size and 0xFF)
                    out.write(pkt)
                    written++
                    rtpSent.incrementAndGet()
                    rtpBytes.addAndGet(pkt.size.toLong())
                }
                out.flush()
                if (isKey) {
                    Log.d(
                        CarPlayConsts.TAG,
                        "RTP keyframe packets=$written totalSent=${rtpSent.get()}",
                    )
                }
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "RTP interleaved write failed", e)
                streaming.set(false)
                return 0
            }
        }
        return written
    }

    private fun parseInterleavedChannel(transport: String): Int {
        val m = Regex("interleaved=(\\d+)", RegexOption.IGNORE_CASE).find(transport)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun reply(out: BufferedOutputStream, msg: RtspMessage) {
        try {
            synchronized(writeLock) {
                out.write(msg.toBytes())
                out.flush()
            }
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "RTSP reply failed", e)
        }
    }

    private fun parseAccessoryInfo(body: ByteArray) {
        val root = BinaryPlist.asDict(BinaryPlist.decode(body)) ?: return
        val displays = root["displays"] as? List<*>
        val main = displays?.firstNotNullOfOrNull { item ->
            val dict = BinaryPlist.asDict(item) ?: return@firstNotNullOfOrNull null
            val w = BinaryPlist.intOf(dict["widthPixels"])
            if (w != null && w > 0) dict else null
        } ?: BinaryPlist.asDict(displays?.firstOrNull())
        if (main != null) {
            val w = BinaryPlist.intOf(main["widthPixels"])?.toInt()?.and(0xffff)
            val h = BinaryPlist.intOf(main["heightPixels"])?.toInt()?.and(0xffff)
            if (w != null && w > 0 && h != null && h > 0) {
                accessoryTouchMaxX = w
                accessoryTouchMaxY = h
                Log.i(CarPlayConsts.TAG, "accessory /info display ${w}x$h (HID touch space)")
            }
            val uuid = displayUuidOf(main)
            if (uuid != null) {
                accessoryDisplayUuid = uuid
                Log.i(CarPlayConsts.TAG, "accessory display uuid=$uuid")
            }
        }
        if (accessoryDisplayUuid == null) {
            findDisplayUuid(root)?.let { uuid ->
                accessoryDisplayUuid = uuid
                Log.i(CarPlayConsts.TAG, "accessory display uuid=$uuid (nested)")
            }
        }
        // Audi: uuid is String "0"/"1"; DiPlay: integer. Keep wire form for hidSetInputMode.
        val hidUuids = ArrayList<String>()
        for (hidKey in listOf("hidDevices", "hids", "hid")) {
            val list = root[hidKey] as? List<*> ?: continue
            for (item in list) {
                val dict = BinaryPlist.asDict(item) ?: continue
                val raw = dict["uuid"] ?: continue
                val uuid = when (raw) {
                    is String -> raw
                    is Number -> raw.toInt().toString()
                    is ByteArray -> raw.toString(Charsets.UTF_8).trim('\u0000')
                    else -> raw.toString()
                }
                if (uuid.isNotBlank()) hidUuids += uuid
            }
        }
        if (hidUuids.isNotEmpty()) {
            accessoryHidUuids.clear()
            accessoryHidUuids.addAll(hidUuids.distinct())
            Log.i(CarPlayConsts.TAG, "accessory /info hidDevices uuids=$accessoryHidUuids")
        }
        // MIB3: always SETUP 48 kHz AAC-LC stereo — matches CarPlayAudioCapture and
        // avoids 44.1 encode vs 48 kHz PCM (no resampler). Audi advertises 48k.
        val formats = root["audioFormats"] as? List<*>
        var saw48 = false
        var saw44 = false
        for (item in formats.orEmpty()) {
            val dict = BinaryPlist.asDict(item) ?: continue
            val type = BinaryPlist.intOf(dict["type"])?.toInt()
            if (type != 102) continue
            val audioType = dict["audioType"]?.toString()
            if (audioType != null && !audioType.equals("media", ignoreCase = true)) continue
            val bits = BinaryPlist.intOf(dict["audioOutputFormats"]) ?: continue
            if ((bits and CarPlayAacAudioEgress.AUDIO_FORMAT_AAC_LC_48000_STEREO) != 0L) {
                saw48 = true
            }
            if ((bits and CarPlayAacAudioEgress.AUDIO_FORMAT_AAC_LC_44100_STEREO) != 0L) {
                saw44 = true
            }
        }
        accessoryAacSampleRate = 48_000
        accessoryAacFormatBit = CarPlayAacAudioEgress.AUDIO_FORMAT_AAC_LC_48000_STEREO
        if (!saw48 && saw44) {
            Log.w(
                CarPlayConsts.TAG,
                "accessory /info AAC media only saw 44.1 — forcing 48 kHz to match capture",
            )
        }
        Log.i(
            CarPlayConsts.TAG,
            "accessory /info AAC media rate=$accessoryAacSampleRate " +
                "format=0x${accessoryAacFormatBit.toString(16)} saw48=$saw48 saw44=$saw44",
        )
    }

    private fun displayUuidOf(dict: Map<String, Any?>): String? {
        for (key in listOf("uuid", "displayUUID", "UUID")) {
            val value = dict[key] as? String
            if (!value.isNullOrBlank() && value.contains('-')) return value
        }
        return null
    }

    private fun findDisplayUuid(node: Any?): String? {
        when (node) {
            is Map<*, *> -> {
                val dict = BinaryPlist.asDict(node)
                if (dict != null) {
                    val uuid = displayUuidOf(dict)
                    val w = BinaryPlist.intOf(dict["widthPixels"])
                    if (uuid != null && w != null && w > 0) return uuid
                }
                for (value in node.values) {
                    findDisplayUuid(value)?.let { return it }
                }
            }
            is List<*> -> {
                for (item in node) {
                    findDisplayUuid(item)?.let { return it }
                }
            }
        }
        return null
    }

    /** 1..2^31-1 so binary plist writes a 4-byte int (DiPlay echoes this width). */
    private fun newStreamConnectionId(): Long {
        val id = Random.nextInt().toLong() and 0x7fffffffL
        return if (id == 0L) 1L else id
    }

    /** Full width iOS sends on Delphi 450.14 (connectionID past 2^32). */
    private fun newStreamConnectionId64(): Long {
        val id = Random.nextLong() and Long.MAX_VALUE
        return if (id == 0L) 1L else id
    }

    fun stop() {
        running.set(false)
        releaseMediaStreams("rtsp-stop")
        peer?.closeQuietly()
        peer = null
        output = null
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
    }

    private fun Socket.closeQuietly() {
        try {
            close()
        } catch (_: Throwable) {
        }
    }

    private class ByteArrayOutputLimited(private val max: Int) {
        private val data = ArrayList<Byte>(4096)
        fun write(b: Int) {
            if (data.size >= max) data.clear()
            data.add(b.toByte())
        }

        fun size(): Int = data.size

        fun reset() = data.clear()

        fun toByteArray(): ByteArray = ByteArray(data.size) { data[it] }

        fun endsWithCrlfCrlf(): Boolean {
            val n = data.size
            if (n < 4) return false
            return data[n - 4] == '\r'.code.toByte() &&
                data[n - 3] == '\n'.code.toByte() &&
                data[n - 2] == '\r'.code.toByte() &&
                data[n - 1] == '\n'.code.toByte()
        }
    }
}
