package io.github.nitsuya.aa.display.aap.cert

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import net.prototux.aare.aap.PhoneIdentity
import net.prototux.aare.aap.cert.SelfSignedCertificate
import java.io.File
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phone TLS identities for native AAP egress.
 *
 * Prefer the CarService leaf extracted from the **installed** Android Auto
 * (`com.google.android.projection.gearhead`) APK — no manual import. Falls back to a
 * generated self-signed certificate for open-source head units that do not check the chain.
 *
 * Refresh policy:
 * - Triggered by status page / AAP arm / boot / [AaCertPackageReceiver] on gearhead install·update
 * - Re-extract when installed gearhead [versionCode] differs from the saved meta
 * - Near-expiry / expired only **warns** (same APK cannot renew the leaf); user must update AA
 * - Uninstalling AA does **not** delete a previously saved CarService identity
 */
object PhoneCertStore {
    private const val TAG = "AAD_PhoneCertStore"
    const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"

    /** UI + meta: warn when the leaf has fewer than this many days left. */
    const val REFRESH_WARN_DAYS = 30L

    private const val DIR = "aap_certs"
    private const val IMPORTED = "carservice"
    private const val GENERATED = "_generated"
    private const val META_VERSION = "gearhead_versionCode"
    private const val META_FP = "modulus_fp"
    private const val META_SOURCE = "source"
    private const val META_EXTRACTED_AT = "extracted_at"

    private val extracting = AtomicBoolean(false)

    data class Status(
        val hasCarService: Boolean,
        val hasSelfSigned: Boolean,
        val subject: String?,
        val notAfter: Date?,
        val daysRemaining: Long?,
        val modulusFingerprint: String?,
        val gearheadInstalled: Boolean,
        val gearheadVersionCode: Long?,
        val gearheadVersionName: String?,
        val lastError: String?,
        val sourceLabel: String?,
    ) {
        val expired: Boolean
            get() = notAfter != null && notAfter.before(Date())

        /** True when the leaf is still valid but within [REFRESH_WARN_DAYS]. */
        val nearExpiry: Boolean
            get() {
                val days = daysRemaining ?: return false
                return !expired && days < REFRESH_WARN_DAYS
            }

        val summary: String
            get() = when {
                hasCarService && expired -> {
                    if (gearheadInstalled) {
                        "CarService 证书已过期（${formatDate(notAfter)}）— 请更新 Android Auto 后自动/手动重新提取"
                    } else {
                        "CarService 证书已过期（${formatDate(notAfter)}）— 请安装较新 Android Auto 后提取"
                    }
                }
                hasCarService && nearExpiry -> {
                    val days = daysRemaining
                    val keep = if (gearheadInstalled) {
                        "请更新 Android Auto，更新后会自动重提"
                    } else {
                        "请安装较新 Android Auto 后提取（当前可继续用落盘证书）"
                    }
                    "证书即将过期（剩余 ${days} 天）— $keep"
                }
                hasCarService -> {
                    val days = daysRemaining
                    val dayHint = if (days != null) "，剩余 ${days} 天" else ""
                    if (gearheadInstalled) {
                        "已从已安装 Android Auto 自动提取$dayHint"
                    } else {
                        "已落盘 CarService 证书$dayHint（Android Auto 已卸载，连车仍可用）"
                    }
                }
                gearheadInstalled -> "已安装 Android Auto，但尚未提取成功${lastError?.let { "：$it" } ?: ""}"
                else -> "未安装 Android Auto — 仅自签证书（多数原厂车机需安装 AA 后自动提取）"
            }
    }

    fun status(context: Context): Status {
        val app = context.applicationContext
        val gh = gearheadInfo(app)
        val imported = loadIdentity(File(certRoot(app), IMPORTED))
        val gen = File(certRoot(app), GENERATED).isDirectory
        val notAfter = imported?.certificate?.notAfter
        val days = notAfter?.let {
            ((it.time - System.currentTimeMillis()) / (24L * 3600_000L))
        }
        val meta = readMeta(app)
        return Status(
            hasCarService = imported != null,
            hasSelfSigned = gen || true,
            subject = imported?.certificate?.subjectX500Principal?.name,
            notAfter = notAfter,
            daysRemaining = days,
            modulusFingerprint = meta[META_FP] ?: imported?.let { fingerprint(it) },
            gearheadInstalled = gh != null,
            gearheadVersionCode = gh?.versionCode,
            gearheadVersionName = gh?.versionName,
            lastError = meta["last_error"],
            sourceLabel = meta[META_SOURCE],
        )
    }

    /**
     * Ensure CarService material is present and matches the currently installed gearhead
     * version. Safe to call from UI / FGS / package receiver; serializes concurrent work.
     *
     * Same [versionCode] + existing leaf → no-op (expiry alone cannot renew without a newer AA).
     */
    fun ensureExtracted(context: Context, force: Boolean = false): Status {
        val app = context.applicationContext
        val gh = gearheadInfo(app)
        if (gh == null) {
            ensureSelfSigned(app)
            val have = loadIdentity(File(certRoot(app), IMPORTED)) != null
            // Keep prior CarService on disk; only annotate meta for the status page.
            writeMeta(
                app,
                mapOf(
                    "last_error" to if (have) {
                        ""
                    } else {
                        "Android Auto 未安装"
                    },
                ),
            )
            return status(app)
        }
        val meta = readMeta(app)
        val savedVer = meta[META_VERSION]?.toLongOrNull()
        val imported = loadIdentity(File(certRoot(app), IMPORTED))
        val have = imported != null
        if (!force && have && savedVer == gh.versionCode) {
            val st = status(app)
            if (st.expired || st.nearExpiry) {
                // Same APK cannot mint a newer leaf — only warn (summary / UI); do not churn last_error.
                Log.i(
                    TAG,
                    "CarService leaf ${if (st.expired) "expired" else "near-expiry"} " +
                        "days=${st.daysRemaining} but gearhead versionCode=$savedVer unchanged — " +
                        "need a newer AA APK to renew",
                )
            }
            return st
        }
        if (!extracting.compareAndSet(false, true)) {
            return status(app)
        }
        try {
            val paths = gearheadApkPaths(app) ?: emptyList()
            if (paths.isEmpty()) {
                writeMeta(
                    app,
                    mapOf(
                        "last_error" to "无法读取 Android Auto APK 路径",
                        META_VERSION to gh.versionCode.toString(),
                    ),
                )
                ensureSelfSigned(app)
                return status(app)
            }
            Log.i(TAG, "extracting CarService from gearhead paths=${paths.size} ver=${gh.versionCode}")
            val result = AaApkKeyExtractor.extractFromApkPaths(paths)
            saveCarService(app, result)
            writeMeta(
                app,
                mapOf(
                    META_VERSION to gh.versionCode.toString(),
                    META_FP to result.modulusFingerprint,
                    META_SOURCE to result.sourceLabel,
                    META_EXTRACTED_AT to System.currentTimeMillis().toString(),
                    "last_error" to "",
                    "gearhead_versionName" to (gh.versionName ?: ""),
                ),
            )
            Log.i(
                TAG,
                "CarService extracted fp=${result.modulusFingerprint} " +
                    "notAfter=${result.certificate.notAfter}",
            )
        } catch (e: Throwable) {
            Log.e(TAG, "CarService extract failed", e)
            writeMeta(
                app,
                mapOf(
                    "last_error" to (e.message ?: e.javaClass.simpleName),
                    META_VERSION to gh.versionCode.toString(),
                ),
            )
            ensureSelfSigned(app)
        } finally {
            extracting.set(false)
        }
        return status(app)
    }

    /** Identities offered to AAP in order: CarService first, then self-signed. */
    fun identities(context: Context): List<PhoneIdentity> {
        val app = context.applicationContext
        ensureSelfSigned(app)
        val out = ArrayList<PhoneIdentity>(2)
        loadIdentity(File(certRoot(app), IMPORTED))?.let { out += it }
        loadIdentity(File(certRoot(app), GENERATED))?.let { out += it }
        return out
    }

    fun pick(context: Context, attemptIndex: Int): PhoneIdentity {
        val all = identities(context)
        require(all.isNotEmpty()) { "no phone identities" }
        return all[Math.floorMod(attemptIndex, all.size)]
    }

    private data class GhInfo(val versionCode: Long, val versionName: String?)

    private fun gearheadInfo(context: Context): GhInfo? {
        return try {
            val pi = context.packageManager.getPackageInfo(GEARHEAD_PACKAGE, 0)
            @Suppress("DEPRECATION")
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pi.longVersionCode
            } else {
                pi.versionCode.toLong()
            }
            GhInfo(code, pi.versionName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (e: Throwable) {
            Log.w(TAG, "gearheadInfo failed", e)
            null
        }
    }

    fun gearheadApkPaths(context: Context): List<String>? {
        return try {
            val ai = context.packageManager.getApplicationInfo(GEARHEAD_PACKAGE, 0)
            val paths = ArrayList<String>()
            ai.sourceDir?.let { paths += it }
            ai.publicSourceDir?.let { if (it !in paths) paths += it }
            ai.splitSourceDirs?.forEach { if (it !in paths) paths += it }
            paths.filter { File(it).canRead() }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (e: Throwable) {
            Log.w(TAG, "gearheadApkPaths failed", e)
            null
        }
    }

    private fun certRoot(context: Context): File =
        File(context.filesDir, DIR).also { it.mkdirs() }

    private fun saveCarService(context: Context, result: AaApkKeyExtractor.Result) {
        val d = File(certRoot(context), IMPORTED).also {
            it.mkdirs()
            it.listFiles()?.forEach { f -> f.delete() }
        }
        File(d, "key.pem").writeText(result.privateKeyPem)
        File(d, "cert.pem").writeText(result.certificatePem)
        File(d, "gal_ca.pem").writeText(result.galCaPem)
    }

    private fun ensureSelfSigned(context: Context) {
        val d = File(certRoot(context), GENERATED)
        if (loadIdentity(d) != null) return
        val g = SelfSignedCertificate.generate("AADisplay ${android.os.Build.MODEL}")
        d.mkdirs()
        File(d, "cert.pem").writeText(toPem(g.certificate.encoded, "CERTIFICATE"))
        File(d, "key.pem").writeText(toPem(g.keyPair.private.encoded, "PRIVATE KEY"))
    }

    private fun loadIdentity(dir: File): PhoneIdentity? {
        if (!dir.isDirectory) return null
        val certFile = File(dir, "cert.pem")
        val keyFile = File(dir, "key.pem")
        if (!certFile.isFile || !keyFile.isFile) return null
        return try {
            val cf = CertificateFactory.getInstance("X.509")
            val chain = cf.generateCertificates(certFile.inputStream()).map { it as X509Certificate }
            val keyPem = keyFile.readText()
            val keyDer = pemBody(keyPem)
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyDer))
            val caFile = File(dir, "gal_ca.pem")
            val extras = if (caFile.isFile) {
                cf.generateCertificates(caFile.inputStream()).map { it as X509Certificate }
            } else {
                emptyList()
            }
            PhoneIdentity(chain.first(), key, chain.drop(1) + extras)
        } catch (e: Throwable) {
            Log.w(TAG, "loadIdentity ${dir.name} failed", e)
            null
        }
    }

    private fun fingerprint(id: PhoneIdentity): String {
        val mod = (id.certificate.publicKey as java.security.interfaces.RSAPublicKey).modulus
        val dig = java.security.MessageDigest.getInstance("SHA-256").digest(mod.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun readMeta(context: Context): Map<String, String> {
        val f = File(certRoot(context), "meta.properties")
        if (!f.isFile) return emptyMap()
        return try {
            f.readLines()
                .mapNotNull { line ->
                    val i = line.indexOf('=')
                    if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
                }
                .toMap()
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun writeMeta(context: Context, updates: Map<String, String>) {
        val cur = readMeta(context).toMutableMap()
        cur.putAll(updates)
        val f = File(certRoot(context), "meta.properties")
        f.writeText(cur.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
    }

    private fun pemBody(pem: String): ByteArray {
        val b64 = pem.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("-----") }
            .joinToString("")
        return android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    }

    private fun toPem(der: ByteArray, label: String): String {
        val body = android.util.Base64.encodeToString(der, android.util.Base64.DEFAULT).trimEnd()
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n"
    }

    private fun formatDate(d: Date?): String {
        if (d == null) return "?"
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(d)
    }
}
