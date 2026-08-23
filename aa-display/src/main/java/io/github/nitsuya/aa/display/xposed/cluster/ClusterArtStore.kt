package io.github.nitsuya.aa.display.xposed.cluster

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadata
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Album-art bridge: system_server writes a world-readable JPEG under `/data/system`;
 * gearhead / `:cluster` load the same path (app cache is SELinux-blocked from system_server).
 */
object ClusterArtStore {
    const val SETTINGS_ART_MEDIA_ID = "aadisplay_cluster_np_art_media_id"
    /** Monotonic counter — bumps on every successful write or clear; wakes :cluster / egress. */
    const val SETTINGS_ART_REVISION = "aadisplay_cluster_np_art_revision"
    /** Shared with [io.github.nitsuya.aa.display.service.ClusterLyricMediaService] / egress hook. */
    const val ART_PATH = "/data/system/aadisplay_cluster_art.jpg"
    private const val ART_TMP_PATH = "/data/system/aadisplay_cluster_art.jpg.tmp"
    private const val TAG = "AAD_ClusterArtStore"
    private const val MAX_EDGE_PX = 512
    private const val JPEG_QUALITY = 85
    /** Luna / QQ may publish title before cover — avoid flashing placeholder during the gap. */
    private const val NULL_ART_CLEAR_DELAY_MS = 2_000L

    private const val METADATA_KEY_ALBUM_ART = "android.media.metadata.ALBUM_ART"
    private const val METADATA_KEY_ART = "android.media.metadata.ART"
    private const val METADATA_KEY_DISPLAY_ICON = "android.media.metadata.DISPLAY_ICON"
    private const val METADATA_KEY_ALBUM_ART_URI = "android.media.metadata.ALBUM_ART_URI"
    private const val METADATA_KEY_ART_URI = "android.media.metadata.ART_URI"
    private const val METADATA_KEY_DISPLAY_ICON_URI = "android.media.metadata.DISPLAY_ICON_URI"

    private val handler = Handler(Looper.getMainLooper())

    private val artThread: HandlerThread by lazy {
        HandlerThread("aad-cluster-art").apply { start() }
    }

    private val artHandler: Handler by lazy { Handler(artThread.looper) }

    @Volatile
    private var pendingClearMediaId: String? = null

    private var pendingClearRunnable: Runnable? = null

    /** gearhead-side decode cache; invalidated when [SETTINGS_ART_REVISION] changes. */
    @Volatile
    private var cachedBitmap: Bitmap? = null

    @Volatile
    private var cachedRevision: Long = 0L

    /** Last package that owned the on-disk JPEG; empty after [clear]. */
    @Volatile
    private var lastArtPackage: String = ""

    /**
     * mediaId of the last JPEG successfully written. Survives [clear] so a track change
     * that clears Settings before Luna drops the previous cover still rejects stale art.
     */
    @Volatile
    private var lastWrittenMediaId: String = ""

    /** Bumped to drop in-flight encode/write after a newer publish or [clear]. */
    @Volatile
    private var publishGen: Long = 0L

    fun artFile(): File = File(ART_PATH)

    fun readRevision(resolver: ContentResolver): Long =
        Settings.Global.getString(resolver, SETTINGS_ART_REVISION)?.toLongOrNull() ?: 0L

    /** True when [mediaId] has no on-disk JPEG yet (or cache key still points elsewhere). */
    fun needsArtForMediaId(resolver: ContentResolver, mediaId: String): Boolean {
        if (mediaId.isEmpty()) return false
        val cachedId = Settings.Global.getString(resolver, SETTINGS_ART_MEDIA_ID)?.trim().orEmpty()
        if (cachedId != mediaId) return true
        val file = artFile()
        return !file.exists() || file.length() <= 0L
    }

    fun artUriString(revision: Long = 0L): String? {
        val f = artFile()
        if (!f.exists() || f.length() <= 0L) return null
        val base = Uri.fromFile(f).toString()
        return if (revision > 0L) "$base?rev=$revision" else base
    }

    fun publishFromMetadata(
        resolver: ContentResolver,
        metadata: MediaMetadata?,
        mediaId: String,
        packageName: String = "",
        /** Tick burst after track change — re-encode even when JPEG size matches. */
        forceRescan: Boolean = false,
    ) {
        if (mediaId.isEmpty()) return
        val pkg = packageName.trim()
        val cachedId = Settings.Global.getString(resolver, SETTINGS_ART_MEDIA_ID)?.trim().orEmpty()
        val mediaChanged = mediaId.isNotEmpty() && (
            (cachedId.isNotEmpty() && cachedId != mediaId) ||
                (lastWrittenMediaId.isNotEmpty() && lastWrittenMediaId != mediaId)
            )
        // Session bitmaps must be copied on this thread — the live MediaMetadata
        // instance may recycle them as soon as we return to the session.
        val sessionBitmapRaw = extractSessionBitmaps(metadata)
        val qqHandoff =
            pkg.isNotEmpty() &&
                lastArtPackage.isNotEmpty() &&
                lastArtPackage != pkg &&
                LyricLineExtractor.sameCoverSource(lastArtPackage, pkg)
        val sourceChanged =
            pkg.isNotEmpty() &&
                lastArtPackage.isNotEmpty() &&
                !LyricLineExtractor.sameCoverSource(lastArtPackage, pkg)
        // Title often updates before art; on track change the session bitmap is usually
        // still the previous cover — writing it under the new mediaId blocks tick retries.
        val sessionBitmap =
            if (mediaChanged && sessionBitmapRaw != null && !qqHandoff) {
                logDebug(
                    TAG,
                    "ignore stale session bitmap written=$lastWrittenMediaId cached=$cachedId -> $mediaId",
                )
                sessionBitmapRaw.recycle()
                null
            } else {
                sessionBitmapRaw
            }
        // Same-app track change (e.g. Luna): drop stale JPEG immediately. QQ car↔HD
        // handoff keeps the 2s deferred window when the new session has no art yet.
        if (mediaChanged && sessionBitmap == null && !qqHandoff) {
            logDebug(
                TAG,
                "stale art written=$lastWrittenMediaId cached=$cachedId -> $mediaId (immediate clear)",
            )
            clear(resolver)
        }
        val gen = ++publishGen
        artHandler.post {
            if (gen != publishGen) {
                sessionBitmap?.recycle()
                return@post
            }
            val bitmap = sessionBitmap ?: extractUriArt(resolver, metadata)
            if (bitmap == null) {
                handler.post {
                    if (gen != publishGen) return@post
                    if (sourceChanged) {
                        logDebug(TAG, "source changed without art $lastArtPackage -> $pkg (immediate clear)")
                        clear(resolver)
                        lastArtPackage = pkg
                        return@post
                    }
                    logDebug(TAG, "no art mediaId=$mediaId (deferred clear)")
                    scheduleDeferredClear(resolver, mediaId)
                }
                return@post
            }
            val jpegBytes = encodeJpeg(bitmap, mediaId)
            if (jpegBytes == null || jpegBytes.isEmpty()) return@post
            if (gen != publishGen) return@post
            val file = artFile()
            val wrote = atomicWriteJpeg(jpegBytes)
            if (!wrote) return@post
            if (gen != publishGen) return@post
            handler.post {
                if (gen != publishGen) return@post
                cancelPendingClear()
                runCatching {
                    Settings.Global.putString(resolver, SETTINGS_ART_MEDIA_ID, mediaId)
                    lastWrittenMediaId = mediaId
                    if (pkg.isNotEmpty()) lastArtPackage = pkg
                    val revision = bumpRevision(resolver)
                    logDebug(TAG, "art saved mediaId=$mediaId bytes=${file.length()} rev=$revision")
                }.onFailure { e ->
                    log(TAG, "publish art failed mediaId=$mediaId", e)
                }
            }
        }
    }

    fun loadBitmap(resolver: ContentResolver? = null): Bitmap? {
        val revision = resolver?.let { readRevision(it) } ?: 0L
        val file = artFile()
        if (!file.exists() || file.length() <= 0L) {
            evictBitmapCache()
            return null
        }
        if (revision > 0L) {
            cachedBitmap?.takeIf { !it.isRecycled && cachedRevision == revision }?.let { return it }
        } else {
            val mtime = file.lastModified()
            cachedBitmap?.takeIf { !it.isRecycled && cachedRevision == mtime }?.let { return it }
        }
        evictBitmapCache()
        val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath) }
            .getOrNull()
            ?.takeIf { !it.isRecycled }
            ?: return null
        cachedBitmap = decoded
        cachedRevision = if (revision > 0L) revision else file.lastModified()
        return decoded
    }

    fun loadBitmap(context: Context): Bitmap? = loadBitmap(context.contentResolver)

    fun clear(resolver: ContentResolver) {
        cancelPendingClear()
        val gen = ++publishGen
        evictBitmapCache()
        lastArtPackage = ""
        runCatching {
            Settings.Global.putString(resolver, SETTINGS_ART_MEDIA_ID, "")
            bumpRevision(resolver)
        }.onFailure { e ->
            log(TAG, "clear art failed", e)
        }
        artHandler.post {
            if (gen != publishGen) return@post
            artFile().delete()
            File(ART_TMP_PATH).delete()
        }
    }

    private fun encodeJpeg(bitmap: Bitmap, mediaId: String): ByteArray? {
        val scaled = scaleDown(bitmap)
        if (scaled !== bitmap) {
            bitmap.recycle()
        }
        val jpegBytes = runCatching {
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        }.getOrElse { e ->
            log(TAG, "compress art failed mediaId=$mediaId", e)
            scaled.recycle()
            return null
        }
        scaled.recycle()
        return jpegBytes
    }

    private fun atomicWriteJpeg(jpegBytes: ByteArray): Boolean {
        val tmp = File(ART_TMP_PATH)
        val file = artFile()
        return runCatching {
            FileOutputStream(tmp).use { out ->
                out.write(jpegBytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    log(TAG, "rename art tmp failed")
                    tmp.delete()
                    return false
                }
            }
            file.setReadable(true, false)
            true
        }.getOrElse { e ->
            log(TAG, "write art failed", e)
            tmp.delete()
            false
        }
    }

    private fun bumpRevision(resolver: ContentResolver): Long {
        val next = readRevision(resolver) + 1L
        Settings.Global.putString(resolver, SETTINGS_ART_REVISION, next.toString())
        notifyArtObservers(resolver)
        return next
    }

    private fun notifyArtObservers(resolver: ContentResolver) {
        runCatching {
            resolver.notifyChange(Settings.Global.getUriFor(SETTINGS_ART_MEDIA_ID), null)
            resolver.notifyChange(Settings.Global.getUriFor(SETTINGS_ART_REVISION), null)
        }
    }

    private fun scheduleDeferredClear(resolver: ContentResolver, mediaId: String) {
        // Tick retries call publishFromMetadata every ~500ms; do not reset the 2s timer.
        if (pendingClearMediaId == mediaId && pendingClearRunnable != null) {
            return
        }
        cancelPendingClear()
        pendingClearMediaId = mediaId
        val runnable = Runnable {
            pendingClearRunnable = null
            val pendingId = pendingClearMediaId ?: return@Runnable
            pendingClearMediaId = null
            val cachedId = Settings.Global.getString(resolver, SETTINGS_ART_MEDIA_ID)?.trim().orEmpty()
            // Cover arrived while we waited — keep it.
            if (cachedId == pendingId && artFile().exists() && artFile().length() > 0L) {
                return@Runnable
            }
            if (!artFile().exists()) return@Runnable
            logDebug(TAG, "deferred clear mediaId=$pendingId cachedId=$cachedId")
            clear(resolver)
        }
        pendingClearRunnable = runnable
        handler.postDelayed(runnable, NULL_ART_CLEAR_DELAY_MS)
    }

    private fun cancelPendingClear() {
        pendingClearRunnable?.let { handler.removeCallbacks(it) }
        pendingClearRunnable = null
        pendingClearMediaId = null
    }

    private fun evictBitmapCache() {
        // Never recycle — gearhead / SystemUI may still parcel or draw the previous Bitmap.
        cachedBitmap = null
        cachedRevision = 0L
    }

    private fun extractSessionBitmaps(metadata: MediaMetadata?): Bitmap? {
        if (metadata == null) return null
        metadata.getBitmap(METADATA_KEY_ART)?.let { ownedCopy(it) }?.let { return it }
        metadata.getBitmap(METADATA_KEY_ALBUM_ART)?.let { ownedCopy(it) }?.let { return it }
        metadata.getBitmap(METADATA_KEY_DISPLAY_ICON)?.let { ownedCopy(it) }?.let { return it }
        metadata.description?.iconBitmap?.let { ownedCopy(it) }?.let { return it }
        return null
    }

    private fun extractUriArt(resolver: ContentResolver, metadata: MediaMetadata?): Bitmap? {
        if (metadata == null) return null
        metadata.getString(METADATA_KEY_ART_URI)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { loadUri(resolver, it) }
            ?.let { return it }
        metadata.getString(METADATA_KEY_ALBUM_ART_URI)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { loadUri(resolver, it) }
            ?.let { return it }
        metadata.getString(METADATA_KEY_DISPLAY_ICON_URI)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { loadUri(resolver, it) }
            ?.let { return it }
        metadata.description?.iconUri?.toString()
            ?.takeIf { it.isNotEmpty() }
            ?.let { loadUri(resolver, it) }
            ?.let { return it }
        return null
    }

    /** Detached ARGB copy safe to scale/recycle without breaking live session metadata. */
    private fun ownedCopy(source: Bitmap): Bitmap? {
        if (source.isRecycled) return null
        return runCatching { source.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
    }

    private fun loadUri(resolver: ContentResolver, uriString: String): Bitmap? {
        val uri = Uri.parse(uriString)
        return decodeSampled(resolver, uri)
            ?: runCatching {
                resolver.openFileDescriptor(uri, "r").use { pfd ->
                    decodeFdSampled(pfd)
                }
            }.getOrNull()
    }

    private fun decodeSampled(resolver: ContentResolver, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
        }.getOrNull()
    }

    private fun decodeFdSampled(pfd: ParcelFileDescriptor?): Bitmap? {
        if (pfd == null) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ParcelFileDescriptor.dup(pfd.fileDescriptor).use { dup ->
                ParcelFileDescriptor.AutoCloseInputStream(dup).use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
        }.getOrNull()
    }

    private fun sampleSize(width: Int, height: Int): Int {
        val max = maxOf(width, height)
        if (max <= MAX_EDGE_PX) return 1
        var sample = 1
        while (max / (sample * 2) >= MAX_EDGE_PX) {
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val max = maxOf(w, h)
        if (max <= MAX_EDGE_PX) return source
        val scale = MAX_EDGE_PX.toFloat() / max.toFloat()
        val matrix = Matrix().apply { setScale(scale, scale) }
        return Bitmap.createBitmap(source, 0, 0, w, h, matrix, true)
    }
}
