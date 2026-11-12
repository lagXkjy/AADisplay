package io.github.nitsuya.aa.display.xposed.carplay

import android.annotation.SuppressLint
import android.content.Context
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.Process
import android.os.UserHandle
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug

/**
 * MediaProjection acquisition ladder for the CarPlay system_server tap
 * (see [CarPlayAudioCapture]). All attempts are stateless reflection; each
 * identity drop is local to the call, so this object is shared safely.
 *
 * **Inside system_server, local**
 * [MediaProjectionManagerService.BinderService.createProjection] uses
 * [Context.checkCallingPermission], which **always denies** when
 * `callingPid == myPid` — even after [android.os.Binder.clearCallingIdentity].
 * Some OEMs also lack `createProjectionInternal`. Falling through to REMOTE_SUBMIX
 * skips phone mute → sound plays on the handset speaker while the HU still gets PCM.
 *
 * Fix: construct the service's inner `MediaProjection` directly (same path the
 * Binder method takes after the permission gate), then try OEM/AOSP helpers.
 */
internal object CarPlayMediaProjectionAcquire {
    private const val TAG = "AAD_CarPlayAudioCap"

    fun acquire(ctx: Context): MediaProjection? {
        runCatching {
            val proj = createProjectionDirect(ctx)
            if (proj != null) {
                log(TAG, "MediaProjection via direct service construct")
                return proj
            }
        }.onFailure { log(TAG, "direct MediaProjection construct failed", it) }

        // AOSP builds that expose createProjectionInternal.
        runCatching {
            val proj = createProjectionInternal(ctx)
            if (proj != null) {
                log(TAG, "MediaProjection via createProjectionInternal")
                return proj
            }
        }.onFailure { log(TAG, "createProjectionInternal failed", it) }

        // Cross-process only: same-process BinderService hits checkCallingPermission deny.
        runCatching {
            val proj = createProjectionViaBinder(ctx)
            if (proj != null) {
                log(TAG, "MediaProjection via IMediaProjectionManager.createProjection")
                return proj
            }
        }.onFailure { log(TAG, "createProjection binder failed", it) }

        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        if (mpm != null) {
            for (m in mpm.javaClass.methods.filter {
                it.name.contains("MediaProjection", ignoreCase = true) &&
                    it.name.startsWith("sem")
            }) {
                runCatching {
                    val args = Array(m.parameterTypes.size) { idx ->
                        defaultArgFor(m.parameterTypes[idx], idx)
                    }
                    val proj = m.invoke(mpm, *args) as? MediaProjection
                    if (proj != null) {
                        log(TAG, "MediaProjection via ${m.name}(${m.parameterTypes.size})")
                        return proj
                    }
                }.onFailure {
                    log(TAG, "${m.name} failed: ${it.message}")
                }
            }
        }

        log(TAG, "no MediaProjection — will try REMOTE_SUBMIX (phone mute skipped)")
        return null
    }

    /**
     * One UI: `(service, type, uid, packageName, targetSdk, isPrivileged)`.
     * AOSP: `(service, uid, packageName, type, isPermanentGrant)` or similar.
     */
    @SuppressLint("PrivateApi")
    private fun createProjectionDirect(ctx: Context): MediaProjection? {
        val service = resolveMediaProjectionManagerService() ?: run {
            log(TAG, "MediaProjectionManagerService not found for direct construct")
            return null
        }
        val cl = service.javaClass.classLoader
        val inner = Class.forName(
            "com.android.server.media.projection.MediaProjectionManagerService\$MediaProjection",
            false,
            cl,
        )
        val pkg = "android"
        val uid = Process.SYSTEM_UID
        val typeMirroring = 1 // TYPE_MIRRORING — canProjectAudio on Samsung for 0/1/2
        val appInfo = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
        val targetSdk = appInfo?.targetSdkVersion ?: 33
        val privileged = true

        for (ctor in inner.declaredConstructors.sortedByDescending { it.parameterTypes.size }) {
            ctor.isAccessible = true
            val pts = ctor.parameterTypes
            if (pts.isEmpty() || !pts[0].isInstance(service)) continue
            val candidates: List<Array<Any?>> = when {
                // (Service, type, uid, pkg, int, boolean) — One UI: targetSdk+privileged;
                // AOSP variant: userId+permanentGrant.
                pts.size == 6 &&
                    pts[1] == Int::class.javaPrimitiveType &&
                    pts[2] == Int::class.javaPrimitiveType &&
                    pts[3] == String::class.java &&
                    pts[4] == Int::class.javaPrimitiveType &&
                    pts[5] == Boolean::class.javaPrimitiveType -> listOf(
                    arrayOf(service, typeMirroring, uid, pkg, targetSdk, privileged),
                    arrayOf(service, typeMirroring, uid, pkg, 0, true),
                )
                // AOSP-like: (Service, uid, pkg, type, permanentGrant)
                pts.size == 5 &&
                    pts[1] == Int::class.javaPrimitiveType &&
                    pts[2] == String::class.java &&
                    pts[3] == Int::class.javaPrimitiveType &&
                    pts[4] == Boolean::class.javaPrimitiveType -> listOf(
                    arrayOf(service, uid, pkg, typeMirroring, true),
                )
                else -> continue
            }
            for (args in candidates) {
                val iProj = runCatching { ctor.newInstance(*args) }.onFailure {
                    logDebug(TAG, "MediaProjection ctor ${pts.size} failed: ${it.message}")
                }.getOrNull() ?: continue
                val wrapped = wrapIMediaProjection(ctx, iProj)
                if (wrapped != null) {
                    log(TAG, "direct MediaProjection ctor args=${pts.size} type=$typeMirroring uid=$uid")
                    return wrapped
                }
            }
        }
        log(
            TAG,
            "no matching MediaProjection ctor among ${inner.declaredConstructors.size} " +
                "(service=${service.javaClass.name})",
        )
        return null
    }

    private fun createProjectionInternal(ctx: Context): MediaProjection? {
        val service = resolveMediaProjectionManagerService() ?: return null
        val method = service.javaClass.declaredMethods
            .filter { it.name == "createProjectionInternal" }
            .maxByOrNull { it.parameterTypes.size }
            ?.also { it.isAccessible = true }
            ?: return null
        val args = Array(method.parameterTypes.size) { idx ->
            defaultArgFor(method.parameterTypes[idx], idx, packageName = "android")
        }
        val iProj = method.invoke(service, *args) ?: return null
        return wrapIMediaProjection(ctx, iProj)
    }

    private fun createProjectionViaBinder(ctx: Context): MediaProjection? {
        val stub = mediaProjectionManagerStub() ?: return null
        // Same-process BinderService: checkCallingPermission always denies (callingPid==myPid).
        if (stub.javaClass.name.contains("BinderService")) {
            logDebug(TAG, "skip createProjection binder — local BinderService (checkCallingPermission deny)")
            return null
        }
        val create = stub.javaClass.methods
            .filter { it.name == "createProjection" }
            .maxByOrNull { it.parameterTypes.size }
            ?: return null
        val args = Array(create.parameterTypes.size) { idx ->
            defaultArgFor(create.parameterTypes[idx], idx, packageName = "android")
        }
        val iProj = create.invoke(stub, *args) ?: return null
        return wrapIMediaProjection(ctx, iProj)
    }

    @SuppressLint("PrivateApi")
    private fun resolveMediaProjectionManagerService(): Any? {
        runCatching {
            val localServices = Class.forName("com.android.server.LocalServices")
            val svcClass = Class.forName(
                "com.android.server.media.projection.MediaProjectionManagerService",
            )
            val getService = localServices.methods.firstOrNull { m ->
                m.name == "getService" &&
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == Class::class.java
            }
            val got = getService?.invoke(null, svcClass)
            if (got != null) return got
        }
        val stub = mediaProjectionManagerStub() ?: return null
        return unwrapMediaProjectionManagerService(stub)
    }

    private fun mediaProjectionManagerStub(): Any? {
        val smClass = Class.forName("android.os.ServiceManager")
        val binder = smClass.getMethod("getService", String::class.java)
            .invoke(null, Context.MEDIA_PROJECTION_SERVICE) as? IBinder
            ?: return null
        return Class.forName("android.media.projection.IMediaProjectionManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    }

    private fun unwrapMediaProjectionManagerService(stub: Any): Any? {
        var c: Class<*>? = stub.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (f.name != "this$0" && !f.name.startsWith("this$")) continue
                runCatching {
                    f.isAccessible = true
                    val outer = f.get(stub) ?: return@runCatching
                    if (outer.javaClass.name.contains("MediaProjectionManagerService")) {
                        return outer
                    }
                }
            }
            c = c.superclass
        }
        return null
    }

    @SuppressLint("PrivateApi")
    private fun wrapIMediaProjection(ctx: Context, iProj: Any): MediaProjection? {
        val iProjClass = Class.forName("android.media.projection.IMediaProjection")
        val impl = when {
            iProjClass.isInstance(iProj) -> iProj
            iProj is IBinder -> {
                Class.forName("android.media.projection.IMediaProjection\$Stub")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, iProj)
            }
            else -> {
                // Service inner MediaProjection extends Stub — use as Binder.
                val asBinder = iProj.javaClass.methods.firstOrNull {
                    it.name == "asBinder" && it.parameterTypes.isEmpty()
                }
                val token = asBinder?.invoke(iProj) as? IBinder
                if (token != null) {
                    Class.forName("android.media.projection.IMediaProjection\$Stub")
                        .getMethod("asInterface", IBinder::class.java)
                        .invoke(null, token)
                } else {
                    iProj
                }
            }
        } ?: return null
        val ctor = MediaProjection::class.java.declaredConstructors.firstOrNull { c ->
            c.parameterTypes.size >= 2 &&
                c.parameterTypes[0] == Context::class.java &&
                iProjClass.isAssignableFrom(c.parameterTypes[1])
        }?.also { it.isAccessible = true }
            ?: return null
        return ctor.newInstance(ctx, impl) as? MediaProjection
    }

    private fun defaultArgFor(
        type: Class<*>,
        index: Int,
        packageName: String = "android",
    ): Any? = when {
        type == Int::class.javaPrimitiveType -> when (index) {
            0 -> Process.myUid() // processUid
            2 -> 0 // TYPE_SCREEN_CAPTURE / displayId depending on overload
            else -> 0
        }
        type == Boolean::class.javaPrimitiveType -> true // permanentGrant — keep capture alive
        type == String::class.java -> packageName
        type == Long::class.javaPrimitiveType -> 0L
        type.name == "android.os.UserHandle" -> {
            runCatching {
                UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType)
                    .invoke(null, 0)
            }.getOrElse {
                UserHandle::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                    .also { it.isAccessible = true }
                    .newInstance(0)
            }
        }
        else -> null
    }
}
