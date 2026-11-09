package io.github.nitsuya.aa.display.aap.cert

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Offline CarService RSA private-key extractor for Android Auto (gearhead) APK / APKM / DEX.
 *
 * Port of AARE [extract_key.py](https://github.com/prototux/AARE/blob/main/extract_key.py):
 * scan DEX for CarService + GAL CA PEMs and adjacent byte[] array-payloads (salt + AES blob),
 * re-implement the app KDF, decrypt PKCS#8, verify modulus against the leaf certificate.
 *
 * Used only so this module can present a Google-issued phone certificate during AAP TLS —
 * Android Auto **compatibility** with factory head units. The release APK never ships Google's
 * private key; material is derived from the user's installed (or supplied) gearhead package.
 */
object AaApkKeyExtractor {
    private const val PEM_BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val PEM_END = "-----END CERTIFICATE-----"

    /** Built-in GAL root PEM when the APK string table does not carry it. */
    private val GAL_CA_PEM = """
        -----BEGIN CERTIFICATE-----
        MIIDiTCCAnGgAwIBAgIJAMFO56WkVE1CMA0GCSqGSIb3DQEBBQUAMFsxCzAJBgNV
        BAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRYwFAYDVQQHDA1Nb3VudGFpbiBW
        aWV3MR8wHQYDVQQKDBZHb29nbGUgQXV0b21vdGl2ZSBMaW5rMB4XDTE0MDYwNjE4
        MjgxOVoXDTQ0MDYwNTE4MjgxOVowWzELMAkGA1UEBhMCVVMxEzARBgNVBAgMCkNh
        bGlmb3JuaWExFjAUBgNVBAcMDU1vdW50YWluIFZpZXcxHzAdBgNVBAoMFkdvb2ds
        ZSBBdXRvbW90aXZlIExpbmswggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIB
        AQDUH+iIbwwVb74NdI5eBv/ACFmh4ml/NOW7gUVWdYX50n8uQQsHHLCNIhk5VV2H
        hanvAZ/XXHPuVAPadE2HpnNqePKF/RDo4eJo/+rOief8gBYq/Z+OQTZeLdNm+GoI
        HBrEjU4Ms8IdLuFW0jF8LlIRgekjLHpc7duUl3QpwBlmAWQK40T/SZjprlmhyqfJ
        g1rxFdnGbrSibmCsTmb3m6WZyZUyrcwmd7t6q3pHbMABO+o02asPG/YPj/SJo4+i
        fb5/Nk56f3hH9pBiPKQXJnVUdVLKMXSRgydDBsGSBol4C0JL77MNDrMR5jdafJ4j
        mWmsa2+mnzoAv9AxEL9T0LiNAgMBAAGjUDBOMB0GA1UdDgQWBBS5dqvv8DPQiwrM
        fgn8xKR91k7wgjAfBgNVHSMEGDAWgBS5dqvv8DPQiwrMfgn8xKR91k7wgjAMBgNV
        HRMEBTADAQH/MA0GCSqGSIb3DQEBBQUAA4IBAQDKcnBsrbB0Jbz2VGJKP2lwYB6P
        dCTCCpQu7dVp61UQOX+zWfd2hnNMnLs/r1xPO+eyN0vmw7sD05phaIhbXVauKWZi
        9WqWHTaR+9s6CTyBOc1Mye0DMj+4vHt+WLmf0lYjkYUVYvR1EImX8ktXzkVmOqn+
        e30siqlZ8pQpsOgegIKfJ+pNQM8c3eXVv3KFMUgjZW33SziZL8IMsLvSO+1LtH37
        KqbTEMP6XUwVuZopgGvaHU74eT/WSRGlL7vX4OL5/UXXP4qsGH2Zp7uQlErv4H9j
        kMs37UL1vGb4M8RM7Eyu9/RulepSmqZUF+3i+3eby8iGq/3OWk9wgJf7AXnx
        -----END CERTIFICATE-----
        
        """.trimIndent().replace("\n", "\n") + "\n"

    data class Result(
        val privateKeyPem: String,
        val certificatePem: String,
        val galCaPem: String,
        val certificate: X509Certificate,
        val modulusFingerprint: String,
        val sourceLabel: String,
    )

    sealed class ExtractError(message: String) : Exception(message) {
        class NoDex(path: String) : ExtractError("no classes*.dex in $path")
        class NoCarServiceCert : ExtractError("no CarService certificate in DEX")
        class NoKeyBlob : ExtractError("no valid key blob (layout changed?)")
        class ModulusMismatch : ExtractError("recovered key modulus does not match certificate")
    }

    fun extractFromApkPaths(paths: List<String>): Result {
        require(paths.isNotEmpty()) { "no APK paths" }
        val blobs = mutableListOf<Pair<String, ByteArray>>()
        for (path in paths) {
            blobs += loadDexBlobs(path)
        }
        if (blobs.isEmpty()) {
            throw ExtractError.NoDex(paths.first())
        }
        return extractFromDexBlobs(blobs, sourceLabel = paths.joinToString(",") { File(it).name })
    }

    fun extractFromDexBlobs(
        blobs: List<Pair<String, ByteArray>>,
        sourceLabel: String,
    ): Result {
        val certs = mutableListOf<FoundCert>()
        for ((label, data) in blobs) {
            for ((off, pem) in findCertStrings(data)) {
                val der = pemToDer(pem) ?: continue
                val subject = try {
                    val c = CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
                    c.subjectX500Principal.name
                } catch (_: Throwable) {
                    ""
                }
                certs += FoundCert(label, off, pem, der, subject)
            }
        }
        val gal = certs.filter {
            it.subject.contains("Google Automotive Link", ignoreCase = false) &&
                !it.subject.contains("CarService", ignoreCase = false)
        }
        val cars = certs.filter {
            !it.subject.contains("Google Automotive Link", ignoreCase = false) ||
                it.subject.contains("CarService", ignoreCase = false)
        }.ifEmpty {
            certs.filter { !it.subject.contains("Google Automotive Link") }
        }
        if (cars.isEmpty()) throw ExtractError.NoCarServiceCert()

        val galPemBytes = (gal.firstOrNull()?.pem ?: GAL_CA_PEM).toByteArray(Charsets.UTF_8)

        val pairs = mutableListOf<Candidate>()
        val salts = mutableListOf<Triple<String, Int, ByteArray>>()
        val ciphers = mutableListOf<Triple<String, Int, ByteArray>>()
        for ((label, data) in blobs) {
            val arrays = findByteArrays(data)
            val (adj, s, c) = findCandidates(label, arrays)
            pairs += adj
            for ((off, _, bytes) in s) salts += Triple(label, off, bytes)
            for ((off, _, bytes) in c) ciphers += Triple(label, off, bytes)
        }

        var recovered: Triple<ByteArray, FoundCert, Candidate>? = null
        for (p in pairs) {
            for (car in cars) {
                val der = tryExtract(p, car.pem.toByteArray(Charsets.UTF_8), galPemBytes) ?: continue
                recovered = Triple(der, car, p)
                break
            }
            if (recovered != null) break
        }
        if (recovered == null) {
            val tried = HashSet<Pair<ByteArray, Int>>()
            outer@ for ((lbl, _, sdata) in salts) {
                for ((_, _, cdata) in ciphers) {
                    if (cdata.size < 512 || cdata.size % 16 != 0) continue
                    if (!tried.add(sdata to cdata.size)) continue
                    val guess = Candidate(lbl, 0, 0, sdata, cdata)
                    for (car in cars) {
                        val der = tryExtract(guess, car.pem.toByteArray(Charsets.UTF_8), galPemBytes)
                            ?: continue
                        recovered = Triple(der, car, guess)
                        break@outer
                    }
                }
            }
        }
        val (pkcs8, car, _) = recovered ?: throw ExtractError.NoKeyBlob()

        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(car.der)) as X509Certificate
        val key = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as RSAPrivateCrtKey
        val certMod = (cert.publicKey as java.security.interfaces.RSAPublicKey).modulus
        if (key.modulus != certMod) throw ExtractError.ModulusMismatch()

        return Result(
            privateKeyPem = pemEncode(pkcs8, "PRIVATE KEY"),
            certificatePem = pemEncode(car.der, "CERTIFICATE"),
            galCaPem = String(galPemBytes, Charsets.UTF_8),
            certificate = cert,
            modulusFingerprint = sha256Hex(key.modulus.toByteArray()).take(16),
            sourceLabel = sourceLabel,
        )
    }

    // --- DEX / ZIP ----------------------------------------------------------

    private fun loadDexBlobs(path: String, maxDepth: Int = 3): List<Pair<String, ByteArray>> {
        val f = File(path)
        if (!f.exists()) return emptyList()
        if (f.isDirectory) {
            return f.listFiles()?.sortedBy { it.name }?.flatMap { loadDexBlobs(it.absolutePath, maxDepth) }
                ?: emptyList()
        }
        if (path.endsWith(".dex", ignoreCase = true)) {
            return listOf(f.name to f.readBytes())
        }
        return fromZip(f.inputStream(), f.name, maxDepth)
    }

    private fun fromZip(input: InputStream, prefix: String, depth: Int): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(input.buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                val label = if (prefix.isEmpty()) name else "$prefix/$name"
                if (isDexName(name)) {
                    val data = zis.readBytes()
                    if (data.size >= 4 &&
                        (data[0] == 'd'.code.toByte() && data[1] == 'e'.code.toByte() &&
                            data[2] == 'x'.code.toByte())
                    ) {
                        out += label to data
                    }
                } else if (depth > 0 &&
                    name.matches(Regex(""".*\.(apk|apks|apkm|xapk|zip|jar)$""", RegexOption.IGNORE_CASE))
                ) {
                    val nested = zis.readBytes()
                    out += fromZip(ByteArrayInputStream(nested), label, depth - 1)
                } else {
                    // drain
                    val buf = ByteArray(8 * 1024)
                    while (zis.read(buf) >= 0) { /* discard */ }
                }
                zis.closeEntry()
            }
        }
        return out
    }

    private fun isDexName(name: String): Boolean {
        val base = name.substringAfterLast('/')
        return base.matches(Regex("""classes(\d+)?\.dex"""))
    }

    private data class FoundCert(
        val dex: String,
        val off: Int,
        val pem: String,
        val der: ByteArray,
        val subject: String,
    )

    private fun findCertStrings(dex: ByteArray): List<Pair<Int, String>> {
        val begin = PEM_BEGIN.toByteArray(Charsets.US_ASCII)
        val end = PEM_END.toByteArray(Charsets.US_ASCII)
        val out = ArrayList<Pair<Int, String>>()
        val seen = HashSet<String>()
        var i = 0
        while (true) {
            val start = indexOf(dex, begin, i)
            if (start < 0) break
            val endAt = indexOf(dex, end, start)
            if (endAt < 0) {
                i = start + 1
                continue
            }
            var nul = endAt + end.size
            while (nul < dex.size && dex[nul] != 0.toByte()) nul++
            if (nul >= dex.size) {
                i = start + 1
                continue
            }
            val s = String(dex, start, nul - start, Charsets.US_ASCII)
            if (seen.add(s)) out += start to s
            i = start + 1
        }
        return out
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..(hay.size - needle.size)) {
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private data class Arr(val off: Int, val size: Int, val data: ByteArray)

    private fun findByteArrays(dex: ByteArray, minSize: Int = 64, maxSize: Int = 1 shl 20): List<Arr> {
        val marker = byteArrayOf(0x00, 0x03, 0x01, 0x00)
        val out = ArrayList<Arr>()
        var i = 0
        while (true) {
            val off = indexOf(dex, marker, i)
            if (off < 0) break
            if ((off and 1) != 0) {
                i = off + 1
                continue
            }
            if (off + 8 > dex.size) break
            val size = (dex[off + 4].toInt() and 0xff) or
                ((dex[off + 5].toInt() and 0xff) shl 8) or
                ((dex[off + 6].toInt() and 0xff) shl 16) or
                ((dex[off + 7].toInt() and 0xff) shl 24)
            if (size in minSize..maxSize && off + 8 + size <= dex.size) {
                out += Arr(off, size, dex.copyOfRange(off + 8, off + 8 + size))
            }
            i = off + 1
        }
        return out
    }

    private data class Candidate(
        val dex: String,
        val saltOff: Int,
        val certOff: Int,
        val salt: ByteArray,
        val enc: ByteArray,
    )

    private fun findCandidates(
        dexLabel: String,
        arrays: List<Arr>,
    ): Triple<List<Candidate>, List<Arr>, List<Arr>> {
        val salts = arrays.filter { it.size == 256 }
        val ciphers = arrays.filter { it.size >= 512 && it.size % 16 == 0 }
        val byOff = arrays.associateBy { it.off }
        val pairs = ArrayList<Candidate>()
        for (a in arrays) {
            for (delta in listOf(8 + a.size, 8 + a.size + 2)) {
                val nxt = byOff[a.off + delta] ?: continue
                if (nxt.size >= 512 && nxt.size % 16 == 0) {
                    pairs += Candidate(dexLabel, a.off, nxt.off, a.data, nxt.data)
                }
            }
        }
        return Triple(pairs, salts, ciphers)
    }

    // --- KDF / AES ----------------------------------------------------------

    private fun makeTables(salt: ByteArray): Array<IntArray> {
        val n = salt.size
        return Array(48) { j ->
            IntArray(256) { v ->
                (((((v ushr 7) or (v + v)) + 33) xor (salt[j % n].toInt() and 0xff)) and 0xff)
            }
        }
    }

    private fun tweak(src: ByteArray, buf: ByteArray, tables: Array<IntArray>) {
        if (src === buf) {
            val n = src.size
            for (i in 0 until n) {
                for (j in 0 until 48) {
                    buf[j] = (tables[j][buf[j].toInt() and 0xff] xor (src[i].toInt() and 0xff)).toByte()
                }
            }
            return
        }
        for (b in src) {
            for (j in 0 until 48) {
                buf[j] = (tables[j][buf[j].toInt() and 0xff] xor (b.toInt() and 0xff)).toByte()
            }
        }
    }

    private fun deriveKeyMaterial(salt: ByteArray, cert1: ByteArray, cert2: ByteArray): Pair<ByteArray, ByteArray> {
        val buf = ByteArray(48)
        val tables = makeTables(salt)
        tweak(cert1, buf, tables)
        tweak(cert2, buf, tables)
        repeat(7) { tweak(buf, buf, tables) }
        return buf.copyOfRange(0, 32) to buf.copyOfRange(32, 48)
    }

    private fun tryExtract(cand: Candidate, certA: ByteArray, certB: ByteArray): ByteArray? {
        for ((first, second) in listOf(certA to certB, certB to certA)) {
            val (key, iv) = deriveKeyMaterial(cand.salt, first, second)
            val enc = cand.enc
            if (enc.size < 32 || enc.size % 16 != 0) continue
            val aes = Cipher.getInstance("AES/ECB/NoPadding")
            aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            val last = aes.doFinal(enc.copyOfRange(enc.size - 16, enc.size))
            val prev = enc.copyOfRange(enc.size - 32, enc.size - 16)
            val pLast = ByteArray(16) { (last[it].toInt() xor prev[it].toInt()).toByte() }
            val pad = pLast[15].toInt() and 0xff
            if (pad !in 1..16) continue
            val cbc = Cipher.getInstance("AES/CBC/NoPadding")
            cbc.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            var plain = cbc.doFinal(enc)
            if (plain.size < pad) continue
            val padOk = (plain.size - pad until plain.size).all { plain[it].toInt() and 0xff == pad }
            if (!padOk) continue
            plain = plain.copyOf(plain.size - pad)
            if (plain.size < 55) continue
            val mid = plain.copyOfRange(28, plain.size - 26)
            val der = aospBase64Decode(mid) ?: continue
            if (!looksLikePkcs8Rsa(der)) continue
            return der
        }
        return null
    }

    private fun looksLikePkcs8Rsa(der: ByteArray): Boolean {
        return try {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateCrtKey
            key.modulus == key.primeP.multiply(key.primeQ) &&
                key.privateExponent > BigInteger.ZERO &&
                key.publicExponent > BigInteger.ONE
        } catch (_: Throwable) {
            false
        }
    }

    /** Android util.Base64 skip-filtering decoder (AOSP Decoder). */
    private fun aospBase64Decode(data: ByteArray): ByteArray? {
        val decode = IntArray(128) { -1 }.also { t ->
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            for (i in alphabet.indices) t[alphabet[i].code] = i
            t['='.code] = -2
        }
        val out = ByteArray(data.size * 3 / 4 + 10)
        var op = 0
        var state = 0
        var value = 0
        for (c in data) {
            val d = if (c.toInt() and 0xff < 0x80) decode[c.toInt()] else -1
            when (state) {
                0 -> when {
                    d >= 0 -> { value = d; state = 1 }
                    d != -1 -> return null
                }
                1 -> when {
                    d >= 0 -> { value = (value shl 6) or d; state = 2 }
                    d != -1 -> return null
                }
                2 -> when {
                    d >= 0 -> { value = (value shl 6) or d; state = 3 }
                    d == -2 -> {
                        out[op++] = (value shr 4).toByte()
                        state = 4
                    }
                    d != -1 -> return null
                }
                3 -> when {
                    d >= 0 -> {
                        value = (value shl 6) or d
                        out[op++] = (value shr 16).toByte()
                        out[op++] = (value shr 8).toByte()
                        out[op++] = value.toByte()
                        state = 0
                    }
                    d == -2 -> {
                        out[op++] = (value shr 10).toByte()
                        out[op++] = (value shr 2).toByte()
                        state = 5
                    }
                    d != -1 -> return null
                }
                4 -> when {
                    d == -2 -> state = 5
                    d != -1 -> return null
                }
                5 -> if (d != -1) return null
            }
        }
        when (state) {
            1, 4 -> return null
            2 -> out[op++] = (value shr 4).toByte()
            3 -> {
                out[op++] = (value shr 10).toByte()
                out[op++] = (value shr 2).toByte()
            }
        }
        return out.copyOf(op)
    }

    private fun pemToDer(pem: String): ByteArray? {
        val b64 = pem.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("-----") }
            .joinToString("")
        return try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (_: Throwable) {
            null
        }
    }

    private fun pemEncode(der: ByteArray, label: String): String {
        val body = Base64.encodeToString(der, Base64.DEFAULT).trimEnd()
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n"
    }

    private fun sha256Hex(data: ByteArray): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(data)
        return dig.joinToString("") { "%02x".format(it) }
    }
}
