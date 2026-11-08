package net.prototux.aare.aap

import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS for AAP, driven over in-memory buffers.
 *
 * The TLS bytes never touch the socket directly: during the handshake they travel inside
 * SSL_HANDSHAKE control messages, and afterwards each encrypted frame payload is one TLS record.
 * So this wraps an [SSLEngine] and exposes byte-array in/out operations.
 *
 * The head unit is the TLS client and the phone the TLS server. Head units present a certificate
 * chained to Google's automotive CA, but open-source ones cannot, so no CA is required: instead the
 * head unit's certificate must be one trusted for it in [pins] (see [PinStore]). Without a pin
 * store every certificate is accepted.
 *
 * The phone runs it in server mode; [client] mode exists for the simulated head unit.
 *
 * Not thread safe: the session calls [encrypt] from its writer thread and [decrypt] from its
 * reader thread only after the handshake, which SSLEngine supports (wrap and unwrap are
 * independent once the handshake has finished).
 */
class Cryptor(
    certificate: X509Certificate,
    privateKey: PrivateKey,
    chain: List<X509Certificate> = emptyList(),
    /** true to play the head unit's side (TLS client), used by the simulated head unit in tests. */
    client: Boolean = false,
    /** The head unit certificates the user trusts (phone side); null accepts any. */
    pins: PinStore? = null,
    /** Identifies the head unit in [pins]; required with [pins]. */
    peerKey: String? = null,
    /** Trust the first certificate of a head unit with nothing trusted yet, instead of refusing it. */
    trustOnFirstUse: Boolean = false,
) {

    private val engine: SSLEngine
    private val pinning: PinningTrustManager? = if (!client && pins != null) {
        PinningTrustManager(requireNotNull(peerKey) { "peerKey is required with pins" }, pins, trustOnFirstUse)
    } else {
        null
    }

    /** Set when the handshake failed because the head unit's certificate is not trusted. */
    val pinRefusal: PinRefusal? get() = pinning?.refusal

    /** True when this side refused the head unit's certificate, for any reason. */
    val peerRefused get() = pinning?.refused == true

    /** True when the handshake failed because the head unit presented no certificate. */
    val peerCertificateMissing get() = pinning?.noCertificate == true

    init {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        ks.setKeyEntry("aap", privateKey, CharArray(0), (listOf(certificate) + chain).toTypedArray())
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, arrayOf<TrustManager>(pinning ?: AcceptAll), SecureRandom())
        engine = ctx.createSSLEngine().apply {
            useClientMode = client
            // Head units speak TLS 1.2; some ignore or choke on 1.3 extensions.
            val wanted = supportedProtocols.filter { it == "TLSv1.2" }
            if (wanted.isNotEmpty()) enabledProtocols = wanted.toTypedArray()
            // With pins the head unit must present a certificate (gearhead requires it too): it is what
            // gets pinned. Asked for here and required in finishHandshake, so a head unit without one
            // is told apart from one refusing ours (a required one would just fail the handshake).
            if (!client) wantClientAuth = true
            // Modern head units negotiate ECDHE+GCM; some legacy ones only offer RSA key exchange
            // with CBC. Offer everything the platform has except the anonymous/null/export suites.
            enabledCipherSuites = supportedCipherSuites.filter { cs ->
                cs.startsWith("TLS_") && listOf("_anon_", "_NULL_", "_EXPORT_", "_DES_", "_RC4_", "_SCSV").none { it in cs }
            }.sortedBy { if ("GCM" in it || "CHACHA" in it) 0 else 1 }.toTypedArray()
        }
    }

    private val session get() = engine.session
    private var netOut: ByteBuffer = ByteBuffer.allocate(session.packetBufferSize)
    private var appIn: ByteBuffer = ByteBuffer.allocate(session.applicationBufferSize)
    private val empty = ByteBuffer.allocate(0)

    /**
     * First 8 bytes (hex) of the nonce/IV of each accepted record, oldest first. A failed record
     * whose nonce matches one of these is a replay: the transport delivered the same bytes twice.
     */
    private val recentNonces = ArrayDeque<String>()

    @Volatile var handshakeDone = false
        private set(value) {
            if (value && !field) pinning?.commit()
            field = value
        }

    /** The head unit's certificate chain, once the handshake is done. */
    val peerCertificates: List<X509Certificate>
        get() = runCatching { engine.session.peerCertificates.filterIsInstance<X509Certificate>() }.getOrDefault(emptyList())

    val protocol: String get() = engine.session.protocol
    val cipherSuite: String get() = engine.session.cipherSuite

    fun beginHandshake() = engine.beginHandshake()

    /**
     * Feeds handshake bytes received from the head unit (may be empty to just pump the engine)
     * and returns the bytes to send back (possibly empty).
     */
    fun handshake(incoming: ByteArray): ByteArray {
        val input = ByteBuffer.wrap(incoming)
        val out = java.io.ByteArrayOutputStream()
        loop@ while (true) {
            when (engine.handshakeStatus) {
                HandshakeStatus.NEED_UNWRAP, HandshakeStatus.NEED_UNWRAP_AGAIN -> {
                    if (!input.hasRemaining() && engine.handshakeStatus == HandshakeStatus.NEED_UNWRAP) break@loop
                    appIn.clear()
                    val r = engine.unwrap(input, appIn)
                    when (r.status) {
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> break@loop // wait for more bytes
                        SSLEngineResult.Status.BUFFER_OVERFLOW -> appIn = ByteBuffer.allocate(appIn.capacity() * 2)
                        SSLEngineResult.Status.CLOSED -> throw AapException("TLS closed during handshake")
                        SSLEngineResult.Status.OK -> Unit
                    }
                }
                HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val r = engine.wrap(empty, netOut)
                    if (r.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        netOut = ByteBuffer.allocate(netOut.capacity() * 2); continue@loop
                    }
                    netOut.flip()
                    out.write(netOut.array(), 0, netOut.limit())
                    if (r.status == SSLEngineResult.Status.CLOSED) throw AapException("TLS closed during handshake")
                }
                HandshakeStatus.NEED_TASK -> {
                    while (true) engine.delegatedTask?.run() ?: break
                }
                HandshakeStatus.FINISHED, HandshakeStatus.NOT_HANDSHAKING -> {
                    finishHandshake()
                    break@loop
                }
                else -> break@loop
            }
        }
        if (engine.handshakeStatus == HandshakeStatus.NOT_HANDSHAKING) finishHandshake()
        return out.toByteArray()
    }

    /** With pins, a head unit that presented no certificate has nothing to trust: refuse it. */
    private fun finishHandshake() {
        if (handshakeDone) return
        if (pinning != null && peerCertificates.isEmpty()) {
            pinning.noCertificate = true
            pinning.refused = true
            throw AapException("head unit sent no certificate")
        }
        handshakeDone = true
    }

    /** Encrypts one plaintext chunk (≤ [Frame.MAX_PAYLOAD]) into TLS record bytes. */
    fun encrypt(plain: ByteArray, offset: Int, length: Int): ByteArray {
        val input = ByteBuffer.wrap(plain, offset, length)
        val out = java.io.ByteArrayOutputStream(length + 64)
        while (input.hasRemaining()) {
            netOut.clear()
            val r = engine.wrap(input, netOut)
            when (r.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> { netOut = ByteBuffer.allocate(netOut.capacity() * 2); continue }
                SSLEngineResult.Status.CLOSED -> throw AapException("TLS closed")
                else -> Unit
            }
            netOut.flip()
            out.write(netOut.array(), 0, netOut.limit())
        }
        return out.toByteArray()
    }

    /** Decrypts the TLS record(s) of one frame payload. */
    fun decrypt(record: ByteArray, offset: Int, length: Int): ByteArray {
        val input = ByteBuffer.wrap(record, offset, length)
        val out = java.io.ByteArrayOutputStream(length)
        while (input.hasRemaining()) {
            appIn.clear()
            val recordStart = input.position()
            val r: SSLEngineResult = try {
                engine.unwrap(input, appIn)
            } catch (e: SSLException) {
                // A record that fails authentication ends the session; say what it looked like so
                // the log line distinguishes replay vs desync vs peer-side crypto failure.
                // (wrap() positions are absolute into [record], so recordStart needs no offset.)
                throw AapException("TLS record failed authentication: " +
                    describeRecord(record, recordStart, offset + length - recordStart), e)
            }
            when (r.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> { appIn = ByteBuffer.allocate(appIn.capacity() * 2); continue }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> throw AapException("truncated TLS record in frame")
                SSLEngineResult.Status.CLOSED -> throw AapException("TLS closed")
                SSLEngineResult.Status.OK -> if (r.bytesConsumed() > 0) rememberNonce(record, recordStart)
            }
            appIn.flip()
            out.write(appIn.array(), 0, appIn.limit())
            if (r.bytesConsumed() == 0 && r.bytesProduced() == 0) break
        }
        return out.toByteArray()
    }

    private fun rememberNonce(record: ByteArray, pos: Int) {
        if (record.size - pos < 13) return
        recentNonces.addLast(hexOf(record, pos + 5, 8))
        while (recentNonces.size > 64) recentNonces.removeFirst()
    }

    /**
     * One failed record, described for the session-failure log:
     * - a nonce that matches a recent accepted record  -> transport replay (bytes delivered twice);
     * - a header that is not an application-data record -> the stream desynced earlier (lost/extra bytes);
     * - otherwise -> the peer sealed something we cannot authenticate (peer-side key/sequence issue).
     */
    private fun describeRecord(b: ByteArray, pos: Int, len: Int): String {
        if (len < 5) return "short record (${len} bytes)"
        val type = b[pos].toInt() and 0xff
        val kind = when (type) {
            0x16 -> "handshake"
            0x17 -> "app-data"
            0x15 -> "alert"
            0x14 -> "change-cipher-spec"
            else -> "unknown-type 0x${type.toString(16)}"
        }
        val sb = StringBuilder("kind=").append(kind).append(" len=").append(len)
        if (len >= 13) {
            val nonce = hexOf(b, pos + 5, 8)
            sb.append(" nonce=").append(nonce)
            val dup = recentNonces.indexOf(nonce)
            if (dup >= 0) {
                sb.append(" NONCE-DUPLICATE (a record from the last ").append(recentNonces.size)
                    .append(" was delivered again: transport replay)")
            } else {
                val last = recentNonces.lastOrNull()
                last?.let {
                    sb.append(" last-accepted=").append(it)
                    val gap = nonceToLong(nonce) - nonceToLong(it)
                    when {
                        gap == 1L -> sb.append(" (=expected next: peer sealed a record we cannot authenticate)")
                        gap > 1L -> sb.append(" (NONCE-GAP: ${gap - 1} record(s) lost in transport, stream still aligned)")
                        else -> sb.append(" (nonce not sequential: stream desynced)")
                    }
                }
            }
        }
        sb.append(" head=").append(hexOf(b, pos, minOf(16, len)))
        return sb.toString()
    }

    private fun hexOf(b: ByteArray, pos: Int, len: Int): String =
        (0 until len).joinToString("") { "%02x".format(b[pos + it].toInt() and 0xff) }

    private fun nonceToLong(hex: String): Long =
        (0 until hex.length / 2).fold(0L) { acc, i -> acc shl 8 or (hex.substring(2 * i, 2 * i + 2).toLong(16) and 0xff) }

    private object AcceptAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

class AapException(message: String, cause: Throwable? = null) : Exception(message, cause)
