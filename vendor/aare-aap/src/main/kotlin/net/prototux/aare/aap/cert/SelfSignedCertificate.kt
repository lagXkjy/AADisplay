package net.prototux.aare.aap.cert

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Generates a self-signed RSA certificate for the phone's TLS server role.
 *
 * Neither the JDK nor Android exposes a public certificate builder, and pulling in BouncyCastle
 * for this one job is heavy, so this writes the few DER structures by hand (RFC 5280).
 */
object SelfSignedCertificate {

    class Generated(val keyPair: KeyPair, val certificate: X509Certificate)

    fun generate(commonName: String = "AARE phone", validYears: Int = 30, random: SecureRandom = SecureRandom()): Generated {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
        val serial = BigInteger(64, random).abs().add(BigInteger.ONE)
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 3600 * 1000)
        val notAfter = Date(now + validYears * 365L * 24 * 3600 * 1000)
        val name = name(commonName)

        val sha256WithRsa = seq(oid("1.2.840.113549.1.1.11"), NULL)
        val tbs = seq(
            explicit(0, integer(BigInteger.valueOf(2))), // v3
            integer(serial),
            sha256WithRsa,
            name, // issuer = subject
            seq(time(notBefore), time(notAfter)),
            name,
            keyPair.public.encoded, // SubjectPublicKeyInfo is already DER
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val der = seq(tbs, sha256WithRsa, bitString(signature))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        return Generated(keyPair, cert)
    }

    // --- minimal DER encoding ---

    private val NULL = byteArrayOf(0x05, 0x00)

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        when {
            len < 0x80 -> out.write(len)
            len < 0x100 -> { out.write(0x81); out.write(len) }
            len < 0x10000 -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xff) }
            else -> { out.write(0x83); out.write(len shr 16); out.write((len shr 8) and 0xff); out.write(len and 0xff) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun explicit(n: Int, content: ByteArray) = tlv(0xA0 or n, content)
    private fun integer(v: BigInteger) = tlv(0x02, v.toByteArray())
    private fun bitString(bytes: ByteArray) = tlv(0x03, byteArrayOf(0) + bytes)
    private fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))

    private fun time(d: Date): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = java.util.Calendar.getInstance(utc).apply { time = d }.get(java.util.Calendar.YEAR)
        // RFC 5280: UTCTime through 2049, GeneralizedTime from 2050.
        return if (year < 2050) {
            tlv(0x17, SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = utc }.format(d).toByteArray())
        } else {
            tlv(0x18, SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = utc }.format(d).toByteArray())
        }
    }

    private fun name(cn: String) = seq(set(seq(oid("2.5.4.3"), utf8(cn))))

    private fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (p in parts.drop(2)) {
            val stack = ArrayList<Int>()
            var v = p
            stack.add((v and 0x7f).toInt())
            v = v shr 7
            while (v > 0) { stack.add(((v and 0x7f) or 0x80).toInt()); v = v shr 7 }
            for (b in stack.asReversed()) out.write(b)
        }
        return tlv(0x06, out.toByteArray())
    }
}
