package io.github.nitsuya.aa.display.xposed.hook

import android.content.Context
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.xposed.util.log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.util.Properties

/**
 * Persist DexKit method coordinates so gearhead cold start can skip rescanning the APK.
 *
 * Cache stores descriptors (`class#method#(paramTypes)`), never [Method] instances.
 * Invalidated when gearhead version / lastUpdateTime, this module versionCode, process
 * name, or [SCHEMA] changes. Resolve failure falls back to live DexKit.
 */
object DexKitMethodCache {
    private const val TAG = "AAD_DexKitCache"
    /** Bump when descriptor encoding or required keys change. */
    const val SCHEMA = 1

    private const val FILE_PREFIX = "aadisplay_dexkit_"
    private const val K_SCHEMA = "fp.schema"
    private const val K_PROCESS = "fp.process"
    private const val K_GEARHEAD_VC = "fp.gearhead_vc"
    private const val K_GEARHEAD_LU = "fp.gearhead_lu"
    private const val K_MODULE_VC = "fp.module_vc"

    data class MethodRef(
        val className: String,
        val methodName: String,
        val paramTypeNames: List<String>,
    ) {
        fun encode(): String =
            "$className#$methodName#(${paramTypeNames.joinToString(",")})"

        companion object {
            fun encode(method: Method): String =
                from(method).encode()

            fun from(method: Method): MethodRef =
                MethodRef(
                    className = method.declaringClass.name,
                    methodName = method.name,
                    paramTypeNames = method.parameterTypes.map { it.name },
                )

            fun parse(raw: String): MethodRef? {
                val hash1 = raw.indexOf('#')
                val hash2 = raw.indexOf('#', hash1 + 1)
                if (hash1 <= 0 || hash2 <= hash1) return null
                val className = raw.substring(0, hash1)
                val methodName = raw.substring(hash1 + 1, hash2)
                val paramsPart = raw.substring(hash2 + 1)
                if (!paramsPart.startsWith("(") || !paramsPart.endsWith(")")) return null
                val inner = paramsPart.substring(1, paramsPart.length - 1)
                val params = if (inner.isEmpty()) {
                    emptyList()
                } else {
                    inner.split(',')
                }
                if (className.isEmpty() || methodName.isEmpty()) return null
                return MethodRef(className, methodName, params)
            }
        }
    }

    class Session private constructor(
        private val file: File,
        private val processName: String,
        private val gearheadVersionCode: Long,
        private val gearheadLastUpdate: Long,
        private val moduleVersionCode: Int,
        private val props: Properties,
        val isValid: Boolean,
    ) {
        companion object {
            fun create(
                file: File,
                processName: String,
                gearheadVersionCode: Long,
                gearheadLastUpdate: Long,
                moduleVersionCode: Int,
                props: Properties,
                isValid: Boolean,
            ): Session = Session(
                file,
                processName,
                gearheadVersionCode,
                gearheadLastUpdate,
                moduleVersionCode,
                props,
                isValid,
            )
        }

        private var dirty = false

        fun getString(key: String): String? =
            props.getProperty(key)?.takeIf { it.isNotEmpty() }

        fun putString(key: String, value: String?) {
            if (value.isNullOrEmpty()) {
                if (props.remove(key) != null) dirty = true
            } else if (props.getProperty(key) != value) {
                props.setProperty(key, value)
                dirty = true
            }
        }

        fun getRefs(key: String): List<MethodRef>? {
            val raw = getString(key) ?: return null
            if (raw == "-") return emptyList()
            val parts = raw.split('|')
            val refs = parts.mapNotNull { MethodRef.parse(it) }
            return refs.takeIf { it.size == parts.size }
        }

        fun putRefs(key: String, methods: List<Method>) {
            val encoded = if (methods.isEmpty()) {
                "-"
            } else {
                methods.joinToString("|") { MethodRef.encode(it) }
            }
            putString(key, encoded)
        }

        fun getRef(key: String): MethodRef? {
            val raw = getString(key) ?: return null
            if (raw == "-") return null
            return MethodRef.parse(raw)
        }

        /** Present key with "-" means resolved-null (not a cache miss). */
        fun hasKey(key: String): Boolean = props.getProperty(key) != null

        fun putRef(key: String, method: Method?) {
            putString(key, method?.let { MethodRef.encode(it) } ?: "-")
        }

        fun resolve(classLoader: ClassLoader, ref: MethodRef): Method? {
            return runCatching {
                val clazz = Class.forName(ref.className, false, classLoader)
                val params = Array(ref.paramTypeNames.size) { i ->
                    resolveType(ref.paramTypeNames[i], classLoader)
                }
                clazz.getDeclaredMethod(ref.methodName, *params).also { it.isAccessible = true }
            }.onFailure { e ->
                log(TAG, "resolve failed ${ref.encode()}", e)
            }.getOrNull()
        }

        fun resolveAll(classLoader: ClassLoader, refs: List<MethodRef>): List<Method>? {
            if (refs.isEmpty()) return emptyList()
            val out = ArrayList<Method>(refs.size)
            for (ref in refs) {
                out += resolve(classLoader, ref) ?: return null
            }
            return out
        }

        fun commit() {
            if (!dirty) return
            props.setProperty(K_SCHEMA, SCHEMA.toString())
            props.setProperty(K_PROCESS, processName)
            props.setProperty(K_GEARHEAD_VC, gearheadVersionCode.toString())
            props.setProperty(K_GEARHEAD_LU, gearheadLastUpdate.toString())
            props.setProperty(K_MODULE_VC, moduleVersionCode.toString())
            runCatching {
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { props.store(it, "AADisplay DexKit method cache") }
                log(
                    TAG,
                    "saved ${file.name} hooks=${props.keys.count { it.toString().startsWith("hook.") }}",
                )
            }.onFailure { e ->
                log(TAG, "save failed ${file.absolutePath}", e)
            }
            dirty = false
        }
    }

    private data class Fingerprint(
        val processName: String,
        val gearheadVersionCode: Long,
        val gearheadLastUpdate: Long,
        val moduleVersionCode: Int,
    )

    fun open(lpparam: XC_LoadPackage.LoadPackageParam, context: Context): Session {
        val fingerprint = currentFingerprint(lpparam, context)
        val file = cacheFile(context, fingerprint.processName)
        val props = Properties()
        var valid = false
        if (file.isFile) {
            runCatching {
                FileInputStream(file).use { props.load(it) }
            }.onFailure { e ->
                log(TAG, "load failed ${file.absolutePath}", e)
                props.clear()
            }
            valid = matches(props, fingerprint)
            if (!valid) {
                log(
                    TAG,
                    "invalidate ${file.name} " +
                        "cached=${props.getProperty(K_GEARHEAD_VC)}/${props.getProperty(K_MODULE_VC)} " +
                        "now=${fingerprint.gearheadVersionCode}/${fingerprint.moduleVersionCode}",
                )
                props.clear()
            } else {
                log(TAG, "hit ${file.name} keys=${props.size}")
            }
        } else {
            log(TAG, "miss ${file.name}")
        }
        return Session.create(
            file = file,
            processName = fingerprint.processName,
            gearheadVersionCode = fingerprint.gearheadVersionCode,
            gearheadLastUpdate = fingerprint.gearheadLastUpdate,
            moduleVersionCode = fingerprint.moduleVersionCode,
            props = props,
            isValid = valid,
        )
    }

    private fun currentFingerprint(
        lpparam: XC_LoadPackage.LoadPackageParam,
        context: Context,
    ): Fingerprint {
        val pm = context.packageManager
        val pi = runCatching {
            pm.getPackageInfo(lpparam.packageName, 0)
        }.getOrNull()
        return Fingerprint(
            processName = lpparam.processName,
            gearheadVersionCode = pi?.longVersionCode ?: 0L,
            gearheadLastUpdate = pi?.lastUpdateTime ?: 0L,
            moduleVersionCode = BuildConfig.VERSION_CODE,
        )
    }

    private fun matches(props: Properties, fp: Fingerprint): Boolean {
        return props.getProperty(K_SCHEMA) == SCHEMA.toString() &&
            props.getProperty(K_PROCESS) == fp.processName &&
            props.getProperty(K_GEARHEAD_VC) == fp.gearheadVersionCode.toString() &&
            props.getProperty(K_GEARHEAD_LU) == fp.gearheadLastUpdate.toString() &&
            props.getProperty(K_MODULE_VC) == fp.moduleVersionCode.toString()
    }

    private fun cacheFile(context: Context, processName: String): File {
        val suffix = processName.substringAfterLast(':', "main")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(context.cacheDir, "$FILE_PREFIX$suffix.properties")
    }

    private fun resolveType(name: String, classLoader: ClassLoader): Class<*> {
        return when (name) {
            "boolean" -> Boolean::class.javaPrimitiveType!!
            "byte" -> Byte::class.javaPrimitiveType!!
            "char" -> Char::class.javaPrimitiveType!!
            "short" -> Short::class.javaPrimitiveType!!
            "int" -> Int::class.javaPrimitiveType!!
            "long" -> Long::class.javaPrimitiveType!!
            "float" -> Float::class.javaPrimitiveType!!
            "double" -> Double::class.javaPrimitiveType!!
            "void" -> Void.TYPE
            else -> Class.forName(name, false, classLoader)
        }
    }
}
