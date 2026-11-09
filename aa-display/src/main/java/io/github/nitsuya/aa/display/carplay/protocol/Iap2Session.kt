package io.github.nitsuya.aa.display.carplay.protocol

import android.util.Log
import io.github.nitsuya.aa.display.carplay.CarPlayConsts
import io.github.nitsuya.aa.display.carplay.profile.CarPlayHuProfile
import io.github.nitsuya.aa.display.carplay.profile.CarPlayProfileRegistry
import io.github.nitsuya.aa.display.carplay.profile.CarPlayProfileResolver
import io.github.nitsuya.aa.display.carplay.profile.CarPlaySourceIdentity
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Framing.CONTROL_ACK
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Framing.CONTROL_SYN
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Framing.LinkSyncPayload
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Source-role (device / spoofed handset) iAP2 state machine over RFCOMM.
 *
 * Against a HU / diplay receiver:
 * 1. Detect (`FF 55 02 00 EE 10`)
 * 2. Link Synchronization (SYN / SYN+ACK / ACK)
 * 3. Identification (`0x1D00` → `0x1D01` → `0x1D02`)
 * 4. Accessory authentication (`0xAA00`…`0xAA05`)
 * 5. Handoff: transport id / `0x4E0D` / `0x5702` / `0x4300` →
 *    `0x5703` Wi‑Fi creds and/or `0x4301` CarPlayStartSession
 *
 * Phone strings come from [sourceIdentity]; HU wireless path from [huProfile]
 * (resolved via [CarPlayProfileResolver], not vendor name checks in this class).
 */
class Iap2Session(
    private val sendRaw: (ByteArray) -> Unit,
    private val bluetoothTransportId: () -> String = { "00:00:00:00:00:00" },
    private val onLinkReady: (() -> Unit)? = null,
    private val onIdentificationComplete: (() -> Unit)? = null,
    private val onAuthComplete: (() -> Unit)? = null,
    private val onWifiConfig: ((Iap2Csm.WifiConfig) -> Unit)? = null,
    private val onCarPlayStart: ((Iap2Csm.CarPlayStart) -> Unit)? = null,
    private val onNowPlayingUpdates: (() -> Unit)? = null,
    private val onFileTransferDatagram: ((ByteArray) -> Unit)? = null,
    private val sourceIdentity: CarPlaySourceIdentity = CarPlayProfileRegistry.defaultSource(),
    private val onHuProfileChanged: ((CarPlayHuProfile) -> Unit)? = null,
    /**
     * Detect/SYN retried out with no peer SYN/ACK. Close the RFCOMM so an inbound
     * `…cafe` accept is not blocked by a zombie outbound `…caff` socket.
     */
    private val onHandshakeStuck: (() -> Unit)? = null,
) : NowPlayingEgress {
    private enum class State {
        Idle,
        Detect,
        Negotiate,
        LinkReady,
        Identifying,
        Identified,
        Authenticating,
        Authenticated,
        Dead,
    }

    private val lock = Any()
    private val pending = ByteArrayOutputStream()
    private var state = State.Idle

    private var ourSeq: Int = 0
    private var peerSeq: Int = 0
    private var controlSessionId: Int = 0x01
    private var fileTransferSessionId: Int = 0x0C
    private var ourLsp: LinkSyncPayload = LinkSyncPayload.defaultForSource()
    private var peerLsp: LinkSyncPayload? = null
    private var nowPlayingUpdatesRequested = false

    // Sender-side window: datagrams queued until the peer's cumulative ack frees space.
    // The DiPlay link engine drops packets beyond peer maxOutgoing (+slack), so unbounded
    // back-to-back sends (artwork bursts) would tear the link.
    private val fileTransferQueue = ArrayDeque<ByteArray>()
    private var peerMaxOutgoing = 5
    private var lastPeerAck: Int? = null
    private var unackedCount = 0

    private val detectSeen = AtomicBoolean(false)
    private val synSent = AtomicBoolean(false)
    /** Bumped on each transport connect/close so a stale detect-probe cannot drop a new peer. */
    private val handshakeEpoch = AtomicInteger(0)
    private val startIdentSent = AtomicBoolean(false)
    private val authStarted = AtomicBoolean(false)
    private val wirelessStarted = AtomicBoolean(false)
    private var pendingChallenge: ByteArray? = null
    private var transportUsers = 0
    /** MIB3-style: cert request and 20-byte challenge go out together. */
    private var huProfile: CarPlayHuProfile = CarPlayProfileRegistry.defaultHu()
    private var mib3ChallengeSent = false
    private val startSessionSeen = AtomicBoolean(false)
    private val wifiRetryArmed = AtomicBoolean(false)
    private var mib3TransportAnnounced = false
    /** True after [onCarPlayStart] has fired for this RFCOMM session. */
    fun hasSeenStartSession(): Boolean = startSessionSeen.get()

    @Volatile
    var linkReady: Boolean = false
        private set

    /** LSP accepted on Bluetooth. Wi-Fi iAP2 echoes this on the event channel. */
    fun acceptedLinkSync(): ByteArray? = synchronized(lock) {
        if (!linkReady) null else ourLsp.pack()
    }

    /** Current HU profile (updated as peer / iAP ident signals arrive). */
    fun currentHuProfile(): CarPlayHuProfile = synchronized(lock) { huProfile }

    private val mib3: Boolean
        get() = huProfile.usesMib3WirelessPath

    /**
     * True from first detect through a live auth/session — RFCOMM must not replace
     * this peer as "idle" while we wait for the HU SYN after ours.
     */
    fun isNegotiatingOrLive(): Boolean = synchronized(lock) {
        state != State.Idle && state != State.Dead
    }

    @Volatile
    var identificationComplete: Boolean = false
        private set

    @Volatile
    var authenticationComplete: Boolean = false
        private set

    /**
     * BT peer label from RFCOMM. Resolves [huProfile] via [CarPlayProfileResolver].
     */
    fun notePeer(name: String?, address: String?) {
        val resolved = CarPlayProfileResolver.resolve(
            CarPlayProfileResolver.Signals(btName = name),
        )
        applyHuProfile(resolved, reason = "bt-name")
        Log.i(
            CarPlayConsts.TAG,
            "iAP peer name=$name addr=$address hu=${resolved.id} mib3=${resolved.usesMib3WirelessPath}",
        )
    }

    private fun applyHuProfile(resolved: CarPlayHuProfile, reason: String) {
        val changed: Boolean
        synchronized(lock) {
            changed = huProfile.id != resolved.id
            if (changed) {
                huProfile = resolved
                Log.i(CarPlayConsts.TAG, "iAP huProfile → ${resolved.id} ($reason)")
            }
        }
        if (changed) onHuProfileChanged?.invoke(resolved)
    }

    fun onTransportConnected() {
        val gen: Int
        synchronized(lock) {
            transportUsers++
            resetLocked()
            state = State.Detect
            // Race-torn Auth used to blacklist 20/32 for the whole process, so the next
            // clean cafe/dial session only had 16B left and never finished Auth.
            rejectedChallengeSizes.clear()
            gen = handshakeEpoch.incrementAndGet()
        }
        Log.i(CarPlayConsts.TAG, "iAP transport up → detect")
        // DiPlay may pad RFCOMM reads to 512B of repeated detect; we must speak first.
        sendRaw(Iap2Framing.DETECT_MARKER)
        Thread({
            // Detect probe (~1.2s) then SYN retransmit. Audi sometimes drops the first
            // after-detect SYN; without a retry the RFCOMM pump sits forever and the
            // HU never reaches Wi-Fi / video (seen: detect+SYN, then silence).
            repeat(8) { i ->
                try {
                    Thread.sleep(150L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                synchronized(lock) {
                    if (gen != handshakeEpoch.get()) return@Thread
                    if (state != State.Detect && state != State.Negotiate) return@Thread
                    if (peerLsp != null || linkReady) return@Thread
                    if (!detectSeen.get()) {
                        sendRaw(Iap2Framing.DETECT_MARKER)
                        Log.i(CarPlayConsts.TAG, "iAP detect retransmit #${i + 1}")
                    } else {
                        maybeSendSynLocked(if (synSent.get()) "syn-retry-$i" else "probe-$i")
                    }
                }
            }
            repeat(10) { i ->
                try {
                    Thread.sleep(400L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                synchronized(lock) {
                    if (gen != handshakeEpoch.get()) return@Thread
                    if (state != State.Detect && state != State.Negotiate) return@Thread
                    if (peerLsp != null || linkReady) return@Thread
                    if (!detectSeen.get()) return@Thread
                    maybeSendSynLocked("syn-retry-late-$i")
                }
            }
            val stuck = synchronized(lock) {
                gen == handshakeEpoch.get() &&
                    (state == State.Detect || state == State.Negotiate) &&
                    peerLsp == null &&
                    !linkReady
            }
            if (stuck) {
                Log.w(
                    CarPlayConsts.TAG,
                    "iAP handshake stuck (no SYN/ACK) — drop RFCOMM",
                )
                onHandshakeStuck?.invoke()
            }
        }, "iap2-detect-probe").apply { isDaemon = true }.start()
    }

    /** @return false when this close belongs to a socket already replaced by a newer peer. */
    fun onTransportClosed(): Boolean {
        val current = synchronized(lock) {
            // CarDeck: Audi may tear the link ~0.4s after a rejected challenge length.
            // Remember that size for the rest of the process so the next session skips it.
            val challenge = pendingChallenge
            if (challenge != null &&
                (state == State.Authenticating || state == State.Identified)
            ) {
                rejectedChallengeSizes.add(challenge.size)
                Log.w(
                    CarPlayConsts.TAG,
                    "iAP challenge ${challenge.size}B rejected (link drop mid-auth) — " +
                        "skip next session rejected=$rejectedChallengeSizes",
                )
            }
            handshakeEpoch.incrementAndGet()
            transportUsers = (transportUsers - 1).coerceAtLeast(0)
            if (transportUsers > 0) {
                false
            } else {
                // Full reset so the next RFCOMM accept starts Detect cleanly (not sticky Dead
                // flags / half-finished Auth that make DiPlay drop mid-challenge).
                resetLocked()
                state = State.Dead
                true
            }
        }
        if (current) {
            Log.i(CarPlayConsts.TAG, "iAP transport closed")
        } else {
            Log.i(CarPlayConsts.TAG, "iAP stale transport close ignored")
        }
        return current
    }

    fun onBytes(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        synchronized(lock) {
            if (state == State.Idle || state == State.Dead) return
            pending.write(chunk)
            drainLocked()
        }
    }

    private fun resetLocked() {
        pending.reset()
        state = State.Idle
        ourSeq = 0
        peerSeq = 0
        controlSessionId = 0x01
        fileTransferSessionId = 0x0C
        ourLsp = LinkSyncPayload.defaultForSource()
        peerLsp = null
        fileTransferQueue.clear()
        peerMaxOutgoing = 5
        lastPeerAck = null
        unackedCount = 0
        nowPlayingUpdatesRequested = false
        detectSeen.set(false)
        synSent.set(false)
        startIdentSent.set(false)
        authStarted.set(false)
        wirelessStarted.set(false)
        pendingChallenge = null
        mib3ChallengeSent = false
        startSessionSeen.set(false)
        wifiRetryArmed.set(false)
        mib3TransportAnnounced = false
        huProfile = CarPlayProfileRegistry.defaultHu()
        linkReady = false
        identificationComplete = false
        authenticationComplete = false
    }

    private fun drainLocked() {
        val data = pending.toByteArray()
        var offset = 0
        while (offset < data.size) {
            val result = Iap2Framing.tryConsume(data, offset, data.size - offset) ?: break
            when (result) {
                is Iap2Framing.ConsumeResult.Detect -> {
                    offset += result.consumed
                    handleDetectLocked()
                }
                is Iap2Framing.ConsumeResult.Packet -> {
                    offset += result.consumed
                    handlePacketLocked(result.packet)
                }
                is Iap2Framing.ConsumeResult.Skip -> {
                    offset += result.consumed
                }
            }
        }
        if (offset > 0) {
            pending.reset()
            if (offset < data.size) {
                pending.write(data, offset, data.size - offset)
            }
        }
    }

    private fun handleDetectLocked() {
        val first = detectSeen.compareAndSet(false, true)
        if (!first) return
        Log.i(CarPlayConsts.TAG, "iAP detect exchanged")
        sendRaw(Iap2Framing.DETECT_MARKER)
        if (state == State.Detect) {
            state = State.Negotiate
        }
        maybeSendSynLocked("after-detect")
    }

    private fun handlePacketLocked(packet: Iap2Framing.LinkPacket) {
        val head = packet.payload.take(8).joinToString(" ") { "%02x".format(it) }
        Log.i(
            CarPlayConsts.TAG,
            "iAP link ctrl=0x${"%02x".format(packet.control)} seq=${packet.seq} " +
                "ack=${packet.ack} sess=${packet.sessionId} pay=${packet.payload.size}B head=$head",
        )
        if (packet.hasRst) {
            Log.w(CarPlayConsts.TAG, "iAP peer RST")
            state = State.Dead
            linkReady = false
            return
        }
        if (packet.hasSyn) {
            handleSynLocked(packet)
            return
        }
        if (packet.hasAck && packet.payload.isEmpty() && state == State.Negotiate) {
            peerSeq = packet.seq
            markLinkReadyLocked("ack-to-syn")
            return
        }
        if (packet.payload.isNotEmpty() && !packet.hasSyn) {
            peerSeq = packet.seq
            sendLinkLocked(CONTROL_ACK, sessionId = 0, payload = ByteArray(0))
            if (packet.hasAck) {
                trackPeerAckLocked(packet.ack)
            }
            if (packet.sessionId == controlSessionId ||
                peerLsp?.sessions?.any { it.first == packet.sessionId && it.second == 0 } == true
            ) {
                handleControlPayloadLocked(packet.payload)
            } else if (packet.sessionId == fileTransferSessionId) {
                onFileTransferDatagram?.invoke(packet.payload)
                flushFileTransferQueueLocked()
            }
        }
    }

    /**
     * Peer cumulative-ack bookkeeping: bounds the unacked window so queued file-transfer
     * datagrams respect the peer's [LinkSyncPayload.maxOutgoing] (the DiPlay engine drops
     * anything beyond it).
     */
    private fun trackPeerAckLocked(ack: Int) {
        val last = lastPeerAck
        if (last == null || ack == last) {
            if (last == null) {
                lastPeerAck = ack
                unackedCount = 0
            }
            return
        }
        val delta = (ack - last + 256) % 256
        if (delta in 1 until 128) {
            unackedCount = (unackedCount - delta).coerceAtLeast(0)
            lastPeerAck = ack
        }
    }

    private fun handleSynLocked(packet: Iap2Framing.LinkPacket) {
        val lsp = LinkSyncPayload.parse(packet.payload)
        if (lsp == null) {
            Log.w(CarPlayConsts.TAG, "iAP SYN payload unparseable (${packet.payload.size}B)")
            return
        }
        peerLsp = lsp
        peerSeq = packet.seq
        peerMaxOutgoing = lsp.maxOutgoing.coerceIn(1, 255)
        val negotiated = negotiateLinkLocked(lsp)
        ourLsp = negotiated
        controlSessionId = negotiated.controlSessionId()
        fileTransferSessionId =
            negotiated.sessions.firstOrNull { it.second == Iap2Framing.SESSION_TYPE_FILE_TRANSFER }?.first
                ?: lsp.sessions.firstOrNull { it.second == Iap2Framing.SESSION_TYPE_FILE_TRANSFER }?.first
                ?: 0x0C
        Log.i(
            CarPlayConsts.TAG,
            "iAP SYN peer sessions=${lsp.sessions} → accept ${negotiated.sessions} " +
                "controlSession=$controlSessionId",
        )
        if (state == State.Detect) state = State.Negotiate
        ourSeq = (ourSeq + 1) and 0xFF
        sendLinkLocked(
            CONTROL_SYN or CONTROL_ACK,
            sessionId = 0,
            payload = negotiated.pack(),
            seqOverride = ourSeq,
        )
        synSent.set(true)
        if (packet.hasAck) {
            markLinkReadyLocked("syn-ack")
        } else if (negotiated === lsp) {
            // Audi sends SYN with no ACK and then waits. An empty ACK may never come
            // once the SYN|ACK echoes its sessions; start identification anyway.
            schedulePeerSynReadyLocked()
        }
    }

    /**
     * DiPlay's SYN sessions are a subset of ours: keep that intersection and our link
     * parameters. Audi adds an EA session we did not advertise (`id=2`, type 2, ver 1)
     * and rejects a SYN|ACK that drops it or upgrades control to version 2 — echo its LSP.
     */
    private fun negotiateLinkLocked(lsp: LinkSyncPayload): LinkSyncPayload {
        val ours = ourLsp.sessions.map { (id, type, _) -> id to type }.toSet()
        val peerCovered = lsp.sessions.all { (id, type, _) -> (id to type) in ours }
        if (!peerCovered || lsp.sessions.isEmpty()) return lsp
        val peerIds = lsp.sessions.map { (id, type, _) -> id to type }.toSet()
        val intersect = ourLsp.sessions.filter { (id, type, _) -> (id to type) in peerIds }
        return if (intersect.isEmpty()) lsp else ourLsp.copy(sessions = intersect)
    }

    private fun schedulePeerSynReadyLocked() {
        Thread({
            try {
                Thread.sleep(400)
            } catch (_: InterruptedException) {
                return@Thread
            }
            synchronized(lock) {
                if (linkReady || state == State.Dead || state == State.Idle) return@synchronized
                if (state != State.Negotiate && state != State.Detect) return@synchronized
                markLinkReadyLocked("peer-syn-no-ack")
            }
        }, "iap2-syn-ready").apply { isDaemon = true }.start()
    }

    private fun maybeSendSynLocked(reason: String) {
        if (state != State.Detect && state != State.Negotiate) return
        if (peerLsp != null) return
        if (synSent.get() && !detectSeen.get()) return
        // Retransmits keep the same seq so a late peer SYN|ACK still matches.
        if (!synSent.get()) {
            ourSeq = (ourSeq + 1) and 0xFF
        }
        sendLinkLocked(
            CONTROL_SYN,
            sessionId = 0,
            payload = ourLsp.pack(),
            seqOverride = ourSeq,
        )
        synSent.set(true)
        if (state == State.Detect) state = State.Negotiate
        Log.i(CarPlayConsts.TAG, "iAP SYN sent ($reason) seq=$ourSeq")
    }

    private fun markLinkReadyLocked(reason: String) {
        if (linkReady) return
        linkReady = true
        state = State.LinkReady
        Log.i(CarPlayConsts.TAG, "iAP link ready ($reason) controlSession=$controlSessionId")
        onLinkReady?.invoke()
        beginIdentificationLocked()
    }

    private fun beginIdentificationLocked() {
        if (!startIdentSent.compareAndSet(false, true)) return
        state = State.Identifying
        sendControlLocked(Iap2Csm.empty(Iap2Csm.START_IDENTIFICATION))
        Log.i(CarPlayConsts.TAG, "iAP StartIdentification (0x1D00) sent sess=$controlSessionId")
    }

    private fun handleControlPayloadLocked(payload: ByteArray) {
        val msg = Iap2Framing.tryParseCsm(payload) ?: run {
            Log.w(CarPlayConsts.TAG, "iAP control payload not CSM (${payload.size}B)")
            return
        }
        Log.i(
            CarPlayConsts.TAG,
            "iAP CSM id=0x${"%04x".format(msg.messageId)} params=${msg.parameters.size}",
        )
        when (msg.messageId) {
            Iap2Csm.START_IDENTIFICATION -> sendIdentificationInformationLocked()
            Iap2Csm.IDENTIFICATION_INFORMATION -> {
                logIdentificationSummary(msg)
                if (!mib3) {
                    val fromIdent = CarPlayProfileResolver.matchIap(
                        Iap2Csm.utf8(msg.parameters[0]),
                        Iap2Csm.utf8(msg.parameters[1]),
                        Iap2Csm.utf8(msg.parameters[2]),
                    )
                    if (fromIdent != null) {
                        applyHuProfile(fromIdent, reason = "iap-ident")
                    }
                }
                sendIdentificationAcceptedLocked()
            }
            Iap2Csm.IDENTIFICATION_ACCEPTED -> markIdentifiedLocked("peer-accepted")
            Iap2Csm.IDENTIFICATION_REJECTED ->
                Log.w(
                    CarPlayConsts.TAG,
                    "iAP IdentificationRejected params=${msg.parameters.keys} " +
                        msg.parameters.entries.joinToString { (id, v) ->
                            "0x${"%04x".format(id)}=${v.size}B"
                        },
                )

            Iap2Csm.AUTH_CERTIFICATE -> handleAuthCertificateLocked(msg)
            Iap2Csm.AUTH_RESPONSE -> handleAuthResponseLocked(msg)
            Iap2Csm.AUTH_FAILED -> Log.w(CarPlayConsts.TAG, "iAP AuthenticationFailed from peer")
            Iap2Csm.AUTH_SUCCEEDED -> {
                // Unusual for accessory to send; treat as done if we were waiting.
                markAuthenticatedLocked("peer-AA05")
            }

            Iap2Csm.ACCESSORY_WIFI_INFO -> {
                if (mib3 && wifiRetryArmed.compareAndSet(false, true)) {
                    Thread({
                        try {
                            Thread.sleep(2_000)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                        synchronized(lock) {
                            if (state == State.Dead || state == State.Idle || !mib3) return@synchronized
                            sendControlLocked(Iap2Csm.empty(Iap2Csm.REQUEST_ACCESSORY_WIFI))
                            Log.i(CarPlayConsts.TAG, "iAP MIB3 re-sent 0x5702 after Wi-Fi creds")
                        }
                    }, "iap2-mib3-wifi-retry").apply { isDaemon = true }.start()
                }
                val wifi = Iap2Csm.parseWifiConfig(msg)
                Log.i(
                    CarPlayConsts.TAG,
                    "iAP AccessoryWiFi ssid=${wifi.ssid} ch=${wifi.channel} " +
                        "sec=${wifi.securityType} passLen=${wifi.passphrase?.length ?: 0}",
                )
                if (mib3) announceMib3TransportLocked()
                onWifiConfig?.invoke(wifi)
            }
            Iap2Csm.CARPLAY_START_SESSION -> {
                startSessionSeen.set(true)
                val start = Iap2Csm.parseCarPlayStart(msg)
                Log.i(
                    CarPlayConsts.TAG,
                    "iAP CarPlayStartSession port=${start.port} deviceId=${start.deviceId} " +
                        "ssid=${start.ssid} ips=${start.ipAddresses} ver=${start.sourceVersion}",
                )
                onCarPlayStart?.invoke(start)
            }

            Iap2Csm.START_NOW_PLAYING_UPDATES -> {
                if (!nowPlayingUpdatesRequested) {
                    nowPlayingUpdatesRequested = true
                    Log.i(CarPlayConsts.TAG, "iAP StartNowPlayingUpdates (0x5000) — now playing stream armed")
                }
                onNowPlayingUpdates?.invoke()
            }

            Iap2Csm.REQUEST_AUTH_CERTIFICATE -> {
                if (mib3) {
                    val serial = bluetoothTransportId().replace(":", "")
                    sendControlLocked(Iap2Csm.authenticationSerial(serial))
                    Log.i(CarPlayConsts.TAG, "iAP MIB3 0xAA00 → 0xAA06 serial=$serial")
                } else {
                    Log.i(CarPlayConsts.TAG, "iAP CSM device-origin echo/ignore 0xAA00")
                }
            }
            Iap2Csm.REQUEST_AUTH_CHALLENGE,
            Iap2Csm.REQUEST_ACCESSORY_WIFI,
            Iap2Csm.CARPLAY_AVAILABILITY,
            -> Log.i(CarPlayConsts.TAG, "iAP CSM device-origin echo/ignore 0x${"%04x".format(msg.messageId)}")

            else ->
                Log.i(CarPlayConsts.TAG, "iAP CSM unhandled 0x${"%04x".format(msg.messageId)}")
        }
    }

    private fun logIdentificationSummary(msg: Iap2Framing.ControlMessage) {
        fun str(id: Int): String? = Iap2Csm.utf8(msg.parameters[id])
        Log.i(
            CarPlayConsts.TAG,
            "iAP IdentificationInformation name=${str(0)} model=${str(1)} " +
                "mfr=${str(2)} wirelessCarPlayParam=${msg.parameters.containsKey(0x0018)} " +
                "btTransport=${msg.parameters.containsKey(0x0011)}",
        )
    }

    private fun sendIdentificationAcceptedLocked() {
        sendControlLocked(Iap2Csm.empty(Iap2Csm.IDENTIFICATION_ACCEPTED))
        Log.i(CarPlayConsts.TAG, "iAP IdentificationAccepted (0x1D02) sent")
        markIdentifiedLocked("accepted-accessory")
    }

    private fun sendIdentificationInformationLocked() {
        // Phone-shaped 0x1D01. Sent only when the HU asks (0x1D00). DiPlay identifies
        // itself and does not take this path. MIB3 rejects an accessory-shaped ident.
        val mac = bluetoothTransportId()
        val serial = mac.replace(":", "").ifBlank { "000000000000" }
        val id = sourceIdentity
        val params = linkedMapOf(
            0x0000 to Iap2Framing.utf8Param(id.iapName),
            0x0001 to Iap2Framing.utf8Param(id.iapModel),
            0x0002 to Iap2Framing.utf8Param(id.iapManufacturer),
            0x0003 to Iap2Framing.utf8Param(serial),
            0x0004 to Iap2Framing.utf8Param(id.iapFirmwareVersion),
            0x0005 to Iap2Framing.utf8Param(id.iapHardwareVersion),
            0x000C to Iap2Framing.utf8Param(id.iapLanguage),
            0x000D to Iap2Framing.utf8Param(id.iapRegion),
        )
        macBytes(mac)?.let { raw ->
            params[0x0011] = Iap2Csm.encodeGroup(
                linkedMapOf(
                    0x0000 to byteArrayOf(0x00, 0x01),
                    0x0001 to Iap2Framing.utf8Param("Bluetooth"),
                    0x0002 to byteArrayOf(0x01),
                    0x0003 to raw,
                ),
            )
        }
        sendControlLocked(Iap2Framing.encodeCsm(Iap2Csm.IDENTIFICATION_INFORMATION, params))
        Log.i(CarPlayConsts.TAG, "iAP IdentificationInformation (0x1D01) ${id.iapName} bt=$mac")
    }

    private fun markIdentifiedLocked(reason: String) {
        if (identificationComplete) return
        identificationComplete = true
        state = State.Identified
        Log.i(CarPlayConsts.TAG, "iAP identification complete ($reason)")
        onIdentificationComplete?.invoke()
        beginAuthenticationLocked()
    }

    private fun beginAuthenticationLocked() {
        if (!authStarted.compareAndSet(false, true)) return
        state = State.Authenticating
        sendControlLocked(Iap2Csm.empty(Iap2Csm.REQUEST_AUTH_CERTIFICATE))
        Log.i(CarPlayConsts.TAG, "iAP RequestAuthenticationCertificate (0xAA00) sent")
        if (!mib3) return
        // MIB3 / real iPhone: 20B with the cert request. Prefer 20 even if 32 was
        // rejected earlier; never send a size already known-bad for this process.
        val size = firstUsableChallengeSize(prefer20First = true) ?: 20
        val challenge = ByteArray(size).also { SecureRandom().nextBytes(it) }
        pendingChallenge = challenge
        mib3ChallengeSent = true
        sendControlLocked(Iap2Csm.requestAuthChallenge(challenge))
        Log.i(CarPlayConsts.TAG, "iAP MIB3 RequestAuthenticationChallenge (0xAA02) sent ${size}B")
    }

    private fun handleAuthCertificateLocked(msg: Iap2Framing.ControlMessage) {
        Log.i(CarPlayConsts.TAG, "iAP AuthenticationCertificate ${Iap2Csm.authCertificateSummary(msg)}")
        if (mib3ChallengeSent) {
            Log.i(CarPlayConsts.TAG, "iAP MIB3 certificate ignored; challenge already sent")
            return
        }
        // DiPlay wants 32B first; skip sizes this process already saw tear the link.
        val size = firstUsableChallengeSize(prefer20First = false) ?: 32
        val challenge = ByteArray(size).also { SecureRandom().nextBytes(it) }
        pendingChallenge = challenge
        sendControlLocked(Iap2Csm.requestAuthChallenge(challenge))
        Log.i(CarPlayConsts.TAG, "iAP RequestAuthenticationChallenge (0xAA02) sent ${challenge.size}B")
    }

    private fun firstUsableChallengeSize(prefer20First: Boolean): Int? {
        val order = if (prefer20First) {
            listOf(20, 32, 16)
        } else {
            listOf(32, 20, 16)
        }
        return order.firstOrNull { it !in rejectedChallengeSizes }
    }

    private fun handleAuthResponseLocked(msg: Iap2Framing.ControlMessage) {
        val sig = msg.parameters[0x0000] ?: msg.parameters.values.firstOrNull()
        Log.i(CarPlayConsts.TAG, "iAP AuthenticationResponse sig=${sig?.size ?: 0}B")
        // Source accepts accessory signature without local MFi verify (lab / diplay path).
        sendControlLocked(Iap2Csm.empty(Iap2Csm.AUTH_SUCCEEDED))
        Log.i(CarPlayConsts.TAG, "iAP AuthenticationSucceeded (0xAA05) sent")
        markAuthenticatedLocked("challenge-ok")
    }

    private fun markAuthenticatedLocked(reason: String) {
        if (authenticationComplete) return
        authenticationComplete = true
        state = State.Authenticated
        pendingChallenge = null
        Log.i(CarPlayConsts.TAG, "iAP authentication complete ($reason)")
        onAuthComplete?.invoke()
        beginWirelessHandoffLocked()
    }

    private fun beginWirelessHandoffLocked() {
        if (!wirelessStarted.compareAndSet(false, true)) return
        if (mib3) {
            // Ask for the AP first. 0x4E0D/0x4E09/0x4E0E/0x4300 go out only after 0x5703.
            val sessionId = UUID.randomUUID().toString().uppercase()
            sendControlLocked(Iap2Csm.wirelessSessionIdentifier(sessionId))
            Log.i(CarPlayConsts.TAG, "iAP MIB3 wireless session id (0x4E0C) $sessionId")
            sendControlLocked(Iap2Csm.empty(Iap2Csm.REQUEST_ACCESSORY_WIFI))
            Log.i(CarPlayConsts.TAG, "iAP RequestAccessoryWiFiConfiguration (0x5702)")
            return
        }
        val btId = bluetoothTransportId().ifBlank { "00:00:00:00:00:00" }
        sendControlLocked(Iap2Csm.wirelessCarPlayUpdate(available = true))
        Log.i(CarPlayConsts.TAG, "iAP WirelessCarPlayUpdate AVAILABLE (0x4E0D)")
        sendControlLocked(Iap2Csm.deviceTransportIdentifier(btId))
        Log.i(CarPlayConsts.TAG, "iAP DeviceTransportIdentifier (0x4E0E) bt=$btId")
        sendControlLocked(Iap2Csm.empty(Iap2Csm.REQUEST_ACCESSORY_WIFI))
        Log.i(CarPlayConsts.TAG, "iAP RequestAccessoryWiFiConfiguration (0x5702)")
        sendControlLocked(Iap2Csm.carPlayAvailability(btId))
        Log.i(CarPlayConsts.TAG, "iAP CarPlayAvailability (0x4300) wireless=true")
    }

    /** After `0x5703`: declare the Bluetooth transport, then ask for `0x4301`. */
    private fun announceMib3TransportLocked() {
        if (mib3TransportAnnounced) return
        mib3TransportAnnounced = true
        val btId = bluetoothTransportId().ifBlank { "00:00:00:00:00:00" }
        val serial = btId.replace(":", "")
        sendControlLocked(Iap2Csm.wirelessCarPlayUpdate(available = true))
        Log.i(CarPlayConsts.TAG, "iAP WirelessCarPlayUpdate AVAILABLE (0x4E0D)")
        sendControlLocked(Iap2Csm.deviceInformationUpdate(sourceIdentity.iapName))
        Log.i(
            CarPlayConsts.TAG,
            "iAP MIB3 DeviceInformationUpdate (0x4E09) name=${sourceIdentity.iapName}",
        )
        sendControlLocked(Iap2Csm.deviceTransportIdentifier(btId, serial))
        Log.i(CarPlayConsts.TAG, "iAP MIB3 DeviceTransportIdentifier (0x4E0E) bt=$btId serial=$serial")
        sendMib3AvailabilityLocked(btId, serial)
        Thread({
            try {
                Thread.sleep(2_500)
            } catch (_: InterruptedException) {
                return@Thread
            }
            synchronized(lock) {
                if (startSessionSeen.get() || state == State.Dead || state == State.Idle) return@synchronized
                val id = bluetoothTransportId().ifBlank { btId }
                sendMib3AvailabilityLocked(id, id.replace(":", ""))
                Log.i(CarPlayConsts.TAG, "iAP MIB3 re-sent 0x4300; 0x4301 not yet seen")
            }
        }, "iap2-mib3-start-retry").apply { isDaemon = true }.start()
    }

    private fun sendMib3AvailabilityLocked(bluetoothMac: String, serial: String) {
        sendControlLocked(Iap2Csm.mib3CarPlayAvailability(bluetoothMac, serial))
        Log.i(CarPlayConsts.TAG, "iAP CarPlayAvailability (0x4300) bt=$bluetoothMac")
    }

    /** Accessory AP is bound. `0x4300` already carries the Bluetooth id, not the STA MAC. */
    fun noteAccessoryWifiBound(wifiMac: String?) {
        synchronized(lock) {
            if (!mib3 || !mib3TransportAnnounced) return
            if (startSessionSeen.get() || state == State.Dead || state == State.Idle) return
            val id = bluetoothTransportId().ifBlank { "00:00:00:00:00:00" }
            sendMib3AvailabilityLocked(id, id.replace(":", ""))
            Log.i(
                CarPlayConsts.TAG,
                "iAP MIB3 re-sent 0x4300 after Wi-Fi bound bt=$id sta=${wifiMac ?: "hidden"}",
            )
        }
    }

    private fun macBytes(mac: String): ByteArray? {
        val parts = mac.split(":")
        if (parts.size != 6) return null
        val out = ByteArray(6)
        for (i in 0..5) {
            val v = parts[i].toIntOrNull(16) ?: return null
            out[i] = v.toByte()
        }
        return out
    }

    private fun sendControlLocked(csm: ByteArray) {
        ourSeq = (ourSeq + 1) and 0xFF
        sendLinkLocked(
            CONTROL_ACK,
            sessionId = controlSessionId,
            payload = csm,
            seqOverride = ourSeq,
        )
    }

    private fun sendLinkLocked(
        control: Int,
        sessionId: Int,
        payload: ByteArray,
        seqOverride: Int? = null,
    ) {
        val seq = seqOverride ?: ourSeq
        val frame = Iap2Framing.encodeLinkPacket(
            control = control,
            seq = seq,
            ack = peerSeq,
            sessionId = sessionId,
            payload = payload,
        )
        sendRaw(frame)
    }

    // ------------------------------------------------------------------
    // CarPlay NowPlaying (0x5001) + iAP2 v2 file transfer (session 12)
    // ------------------------------------------------------------------

    /**
     * Sends one NowPlayingUpdate. Group bodies are nested TLV8 (DiPlay
     * [Iap2BodyReader.optionalGroup] shape); empty groups are omitted, an omitted field
     * keeps its previous value, and a present empty string clears it. Safe from any thread.
     */
    override fun sendNowPlaying(mediaItem: Map<Int, ByteArray>, playback: Map<Int, ByteArray>) {
        val params = linkedMapOf<Int, ByteArray>()
        if (mediaItem.isNotEmpty()) params[0] = Iap2Csm.encodeGroup(mediaItem)
        if (playback.isNotEmpty()) params[1] = Iap2Csm.encodeGroup(playback)
        if (params.isEmpty()) return
        synchronized(lock) {
            if (state == State.Dead || state == State.Idle) return
            sendControlLocked(Iap2Framing.encodeCsm(Iap2Csm.NOW_PLAYING_UPDATE, params))
        }
    }

    /**
     * Queues one file-transfer datagram (session 12) and sends as many as the peer window
     * allows; the remainder flushes on the next peer ack. The caller owns the 2-byte
     * [id, flags|opcode] header + payload layout. Safe from any thread.
     */
    override fun sendFileTransfer(datagram: ByteArray) {
        synchronized(lock) {
            if (state == State.Dead || state == State.Idle || !linkReady) return
            if (fileTransferQueue.isNotEmpty()) {
                if (fileTransferQueue.size < FILE_TRANSFER_QUEUE_CAP) {
                    fileTransferQueue += datagram
                } else {
                    Log.w(
                        CarPlayConsts.TAG,
                        "iAP file-transfer queue full (${fileTransferQueue.size}) — dropping ${datagram.size}B",
                    )
                }
                return
            }
            if (unackedCount >= peerMaxOutgoing) {
                fileTransferQueue += datagram
                return
            }
            sendFileTransferLocked(datagram)
        }
    }

    private fun sendFileTransferLocked(datagram: ByteArray) {
        ourSeq = (ourSeq + 1) and 0xFF
        unackedCount++
        sendLinkLocked(CONTROL_ACK, sessionId = fileTransferSessionId, payload = datagram)
    }

    private fun flushFileTransferQueueLocked() {
        if (fileTransferQueue.isEmpty() || !linkReady) return
        while (unackedCount < peerMaxOutgoing && fileTransferQueue.isNotEmpty()) {
            sendFileTransferLocked(fileTransferQueue.removeFirst())
        }
    }

    private companion object {
        const val FILE_TRANSFER_QUEUE_CAP = 4_096
        /** Process-wide: challenge lengths that tore the RFCOMM link mid-auth. */
        private val rejectedChallengeSizes = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
    }
}
