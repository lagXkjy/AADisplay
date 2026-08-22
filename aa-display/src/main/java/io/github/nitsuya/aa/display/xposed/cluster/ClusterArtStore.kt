package io.github.nitsuya.aa.display.xposed.cluster

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadata
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Arrays

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
    private const val TAG = "AAD_ClusterArtStore"
    private const val MAX_EDGE_PX = 512
    private const val JPEG_QUALITY = 85
    /** Luna / QQ may publish title before cover — avoid flashing placeholder during the gap. */
    private const val NULL_ART_CLEAR_DELAY_MS = 2_000L

    private const val METADATA_KEY_ALBUM_ART = "android.media.metadata.ALBUM_ART"
    private const val METADATA_KEY_ART = "android.media.metadata.ART"
    private const val METADATA_KEY_ALBUM_ART_URI = "android.media.metadata.ALBUM_ART_URI"
    private const val METADATA_KEY_DISPLAY_ICON_URI = "android.media.metadata.DISPLAY_ICON_URI"

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var pendingClearMediaId: String? = null

    private var pendingClearRunnable: Runnable? = null

    /** gearhead-side decode cache; invalidated when [SETTINGS_ART_REVISION] changes. */
    @Volatile
    private var cachedBitmap: Bitmap? = null

    @Volatile
    private var cachedRevision: Long = 0L

    fun artFile(): File = File(ART_PATH)

    fun readRevision(resolver: ContentResolver): Long =
        Settings.Global.getString(resolver, SETTINGS_ART_REVISION)?.toLongOrNull() ?: 0L

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
    ) {
        if (mediaId.isEmpty()) return
        cancelPendingClear()
        val bitmap = extractArt(resolver, metadata)
        if (bitmap == null) {
            logDebug(TAG, "no art mediaId=$mediaId (deferred clear)")
            scheduleDeferredClear(resolver, mediaId)
            return
        }
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
            return
        }
        scaled.recycle()
        if (jpegBytes.isEmpty()) return

        val file = artFile()
        if (file.exists() && file.length() == jpegBytes.size.toLong()) {
            val existing = runCatching { file.readBytes() }.getOrNull()
            if (existing != null && Arrays.equals(existing, jpegBytes)) {
                val cachedId = Settings.Global.getString(resolver, SETTINGS_ART_MEDIA_ID)?.trim().orEmpty()
                if (cachedId == mediaId) {
                    logDebug(TAG, "art unchanged mediaId=$mediaId")
                    return
                }
            }
        }

        runCatching {
            FileOutputStream(file).use { out ->
                out.write(jpegBytes)
            }
            file.setReadable(true, false)
            Settings.Global.putString(resolver, SETTINGS_ART_MEDIA_ID, mediaId)
            val revision = bumpRevision(resolver)
            logDebug(TAG, "art saved mediaId=$mediaId bytes=${file.length()} rev=$revision")
        }.onFailure { e ->
            log(TAG, "publish art failed mediaId=$mediaId", e)
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
        evictBitmapCache()
        runCatching {
            Settings.Global.putString(resolver, SETTINGS_ART_MEDIA_ID, "")
            artFile().delete()
            bumpRevision(resolver)
        }.onFailure { e ->
            log(TAG, "clear art failed", e)
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
        cachedBitmap?.takeIf { !it.isRecycled }?.recycle()
        cachedBitmap = null
        cachedRevision = 0L
    }

    private fun extractArt(resolver: ContentResolver, metadata: MediaMetadata?): Bitmap? {
        if (metadata == null) return null
        // Never return/recycle bitmaps still owned by the active MediaSession — SystemUI
        // parcels the same metadata and crashes with "Can't parcel a recycled bitmap".
        // Luna (com.luna.music) uses ART; QQ uses ALBUM_ART — check both.
        metadata.getBitmap(METADATA_KEY_ART)?.let { ownedCopy(it) }?.let { return it }
        metadata.getBitmap(METADATA_KEY_ALBUM_ART)?.let { ownedCopy(it) }?.let { return it }
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
        metadata.description?.let { desc ->
            desc.iconBitmap?.let { ownedCopy(it) }?.let { return it }
            desc.iconUri?.toString()
                ?.takeIf { it.isNotEmpty() }
                ?.let { loadUri(resolver, it) }
                ?.let { return it }
        }
        return null
    }

    /** Detached copy safe to scale/recycle without breaking live session metadata. */
    private fun ownedCopy(source: Bitmap): Bitmap? {
        if (source.isRecycled) return null
        return source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
    }

    private fun loadUri(resolver: ContentResolver, uriString: String): Bitmap? {
        val uri = Uri.parse(uriString)
        return runCatching {
            resolver.openInputStream(uri).use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
            ?: runCatching {
                resolver.openFileDescriptor(uri, "r").use { pfd ->
                    decodeFd(pfd)
                }
            }.getOrNull()
    }

    private fun decodeFd(pfd: ParcelFileDescriptor?): Bitmap? {
        if (pfd == null) return null
        return runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
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
