package io.github.nitsuya.aa.display.xposed.cluster

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadata
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.Settings
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.io.File
import java.io.FileOutputStream

/**
 * Album-art bridge: system_server writes a world-readable JPEG under `/data/system`;
 * gearhead / `:cluster` load the same path (app cache is SELinux-blocked from system_server).
 */
object ClusterArtStore {
    const val SETTINGS_ART_MEDIA_ID = "aadisplay_cluster_np_art_media_id"
    /** Shared with [io.github.nitsuya.aa.display.service.ClusterLyricMediaService] / egress hook. */
    const val ART_PATH = "/data/system/aadisplay_cluster_art.jpg"
    private const val TAG = "AAD_ClusterArtStore"
    private const val MAX_EDGE_PX = 512
    private const val JPEG_QUALITY = 85

    private const val METADATA_KEY_ALBUM_ART = "android.media.metadata.ALBUM_ART"
    private const val METADATA_KEY_ART = "android.media.metadata.ART"
    private const val METADATA_KEY_ALBUM_ART_URI = "android.media.metadata.ALBUM_ART_URI"
    private const val METADATA_KEY_DISPLAY_ICON_URI = "android.media.metadata.DISPLAY_ICON_URI"

    /** gearhead-side decode cache; invalidated when [ART_PATH] mtime changes. */
    @Volatile
    private var cachedBitmap: Bitmap? = null

    @Volatile
    private var cachedMtime: Long = 0L

    fun artFile(): File = File(ART_PATH)

    fun artUriString(): String? {
        val f = artFile()
        if (!f.exists() || f.length() <= 0L) return null
        return Uri.fromFile(f).toString()
    }

    fun publishFromMetadata(
        resolver: ContentResolver,
        metadata: MediaMetadata?,
        mediaId: String,
    ) {
        if (mediaId.isEmpty()) return
        val cachedId = Settings.Global.getString(resolver, SETTINGS_ART_MEDIA_ID)?.trim().orEmpty()
        if (mediaId == cachedId && artFile().exists() && artFile().length() > 0L) {
            return
        }
        val bitmap = extractArt(resolver, metadata)
        if (bitmap == null) {
            logDebug(TAG, "no art mediaId=$mediaId")
            if (cachedId.isNotEmpty() || artFile().exists()) {
                clear(resolver)
            }
            return
        }
        val scaled = scaleDown(bitmap)
        if (scaled !== bitmap) {
            bitmap.recycle()
        }
        val file = artFile()
        runCatching {
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            file.setReadable(true, false)
            Settings.Global.putString(resolver, SETTINGS_ART_MEDIA_ID, mediaId)
            // ART may arrive after title (Luna); wake :cluster even when mediaId unchanged.
            runCatching {
                resolver.notifyChange(Settings.Global.getUriFor(SETTINGS_ART_MEDIA_ID), null)
            }
            logDebug(TAG, "art saved mediaId=$mediaId bytes=${file.length()}")
        }.onFailure { e ->
            log(TAG, "publish art failed mediaId=$mediaId", e)
        }.also {
            scaled.recycle()
        }
    }

    fun loadBitmap(): Bitmap? {
        val file = artFile()
        if (!file.exists() || file.length() <= 0L) {
            evictBitmapCache()
            return null
        }
        val mtime = file.lastModified()
        cachedBitmap?.takeIf { !it.isRecycled && cachedMtime == mtime }?.let { return it }
        evictBitmapCache()
        val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath) }
            .getOrNull()
            ?.takeIf { !it.isRecycled }
            ?: return null
        cachedBitmap = decoded
        cachedMtime = mtime
        return decoded
    }

    fun loadBitmap(context: Context): Bitmap? = loadBitmap()

    fun clear(resolver: ContentResolver) {
        evictBitmapCache()
        runCatching {
            Settings.Global.putString(resolver, SETTINGS_ART_MEDIA_ID, "")
            artFile().delete()
        }.onFailure { e ->
            log(TAG, "clear art failed", e)
        }
    }

    private fun evictBitmapCache() {
        cachedBitmap?.takeIf { !it.isRecycled }?.recycle()
        cachedBitmap = null
        cachedMtime = 0L
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
