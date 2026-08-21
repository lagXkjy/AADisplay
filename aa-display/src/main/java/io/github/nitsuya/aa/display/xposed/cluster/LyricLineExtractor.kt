package io.github.nitsuya.aa.display.xposed.cluster

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.xposed.util.logDebug
import org.json.JSONObject

/**
 * Resolves the instrument-cluster ticker string from a real [MediaController].
 * Timed LRC is synced to [PlaybackState] position; otherwise falls back to song title.
 *
 * QQ Music car / HD pad — reads [METADATA_KEY_LYRIC] from MediaSession; no player-process hooks.
 */
object LyricLineExtractor {
    private const val TAG = "AAD_LyricLineExtractor"
    private const val MAX_CHARS = 80

    /** Same as [MediaMetadata.METADATA_KEY_LYRIC] (API 34+); string literal for compileSdk stubs. */
    private const val METADATA_KEY_LYRIC = "android.media.metadata.LYRIC"

    private const val QQ_CAR_PKG = "com.tencent.qqmusiccar"
    private const val QQ_PAD_PKG = "com.tencent.qqmusicpad"

    /** Preferred sources for cluster lyric mirror; earlier wins when multiple match. */
    val PREFERRED_PACKAGE_ORDER = listOf(
        QQ_CAR_PKG,
        QQ_PAD_PKG,
    )

    private val PREFERRED_PACKAGES = PREFERRED_PACKAGE_ORDER.toSet()

    private val LRC_LINE = Regex(
        """\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]\s*(.*)""",
    )

    /** Avoid re-regex / re-parse of the same LRC blob every 300ms position tick. */
    private var cachedLrcMediaId: String = ""
    private var cachedLrcRaw: String = ""
    private var cachedLrcIsTimed: Boolean = false
    private var cachedLrcEntries: List<LrcEntry> = emptyList()

    data class Extracted(
        val tickerTitle: String,
        val artist: String,
        val mediaId: String,
        val songTitle: String,
        val fromLyric: Boolean,
        val needsPositionTick: Boolean,
    )

    fun isPreferredPackage(packageName: String?): Boolean {
        val pkg = packageName ?: return false
        return pkg in PREFERRED_PACKAGES
    }

    fun extract(controller: MediaController): Extracted? {
        val metadata = controller.metadata ?: return null
        val songTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val displaySub = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)?.trim().orEmpty()
        val artist = (
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: displaySub.takeIf { it.isNotEmpty() && !looksLikeLrc(it) }
            )?.trim().orEmpty()
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty()
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        val baseId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.trim().orEmpty()
        val mediaId = when {
            baseId.isNotEmpty() -> baseId
            songTitle.isNotEmpty() || artist.isNotEmpty() ->
                "$artist|$songTitle|$album|$durationMs"
            else -> return null
        }
        if (songTitle.isEmpty() && artist.isEmpty()) return null

        val positionMs = estimatePositionMs(controller.playbackState)
        val rawLyricBlob = findRawLyricBlob(controller.playbackState?.extras, metadata)
        val unwrapped = rawLyricBlob?.let { unwrapLyricPayload(it) }
        val timedEntries = unwrapped?.let { cachedTimedLrc(mediaId, it) }
        val resolved = resolveLyricText(
            unwrapped = unwrapped,
            timedEntries = timedEntries,
            positionMs = positionMs,
            songTitle = songTitle,
            artist = artist,
        )
        val ticker = truncate(resolved?.text?.takeIf { it.isNotBlank() } ?: songTitle)
        if (ticker.isEmpty()) return null

        return Extracted(
            tickerTitle = ticker,
            artist = artist,
            mediaId = mediaId,
            songTitle = songTitle.ifEmpty { ticker },
            fromLyric = resolved != null,
            needsPositionTick = timedEntries != null,
        )
    }

    /** Debug-only extras dump for verifying LYRIC keys on-device. */
    fun dumpExtrasOnce(packageName: String, state: PlaybackState?, metadata: MediaMetadata?) {
        if (!BuildConfig.DEBUG) return
        val keys = linkedSetOf<String>()
        collectKeys(state?.extras, keys)
        collectKeys(metadata?.bundleCompat(), keys)
        logDebug(TAG, "extras dump pkg=$packageName keys=${keys.sorted().joinToString()}")
        val v = state?.extras?.nonBlankString(METADATA_KEY_LYRIC)
            ?: metadata?.bundleCompat()?.nonBlankString(METADATA_KEY_LYRIC)
            ?: metadata?.getString(METADATA_KEY_LYRIC)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return
        logDebug(TAG, "extras lyric-key=$METADATA_KEY_LYRIC len=${v.length} head=${v.take(96)}")
    }

    private data class ResolvedLyric(val text: String, val fromLrc: Boolean)

    /**
     * Returns parsed timed entries when [raw] is LRC for [mediaId]; null when not timed LRC.
     * Cache keyed by mediaId + raw blob so position ticks only binary-search.
     */
    private fun cachedTimedLrc(mediaId: String, raw: String): List<LrcEntry>? {
        if (mediaId == cachedLrcMediaId && raw == cachedLrcRaw) {
            return if (cachedLrcIsTimed) cachedLrcEntries else null
        }
        cachedLrcMediaId = mediaId
        cachedLrcRaw = raw
        cachedLrcIsTimed = looksLikeLrc(raw)
        cachedLrcEntries = if (cachedLrcIsTimed) parseLrc(raw) else emptyList()
        return if (cachedLrcIsTimed) cachedLrcEntries else null
    }

    private fun resolveLyricText(
        unwrapped: String?,
        timedEntries: List<LrcEntry>?,
        positionMs: Long,
        songTitle: String,
        artist: String,
    ): ResolvedLyric? {
        val raw = unwrapped ?: return null

        if (timedEntries != null) {
            val line = lineAtPosition(timedEntries, positionMs)
            if (!line.isNullOrBlank()) {
                return ResolvedLyric(line, fromLrc = true)
            }
            return null
        }

        if (raw.length <= MAX_CHARS * 2 && !raw.contains('\n')) {
            val plain = raw.trim()
            if (plain == songTitle || plain == artist) return null
            return ResolvedLyric(plain, fromLrc = false)
        }
        logDebug(TAG, "ignore oversized non-LRC lyric len=${raw.length}")
        return null
    }

    private fun estimatePositionMs(state: PlaybackState?): Long {
        if (state == null) return 0L
        val base = state.position.coerceAtLeast(0L)
        val updated = state.lastPositionUpdateTime
        if (updated <= 0L) return base
        val st = state.state
        if (st != PlaybackState.STATE_PLAYING &&
            st != PlaybackState.STATE_FAST_FORWARDING &&
            st != PlaybackState.STATE_REWINDING
        ) {
            return base
        }
        val speed = state.playbackSpeed.let { if (it == 0f) 1f else it }
        val elapsed = (SystemClock.elapsedRealtime() - updated).coerceAtLeast(0L)
        return (base + (elapsed * speed).toLong()).coerceAtLeast(0L)
    }

    /** Some players wrap timed LRC inside JSON metadata extras. */
    private fun unwrapLyricPayload(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") || !trimmed.contains("lyric")) return raw
        return runCatching {
            val obj = JSONObject(trimmed)
            sequenceOf("lyric", "txtlyric", "rawLyric", "lrc", "yrc")
                .mapNotNull { key ->
                    obj.optString(key)?.trim()?.takeIf { it.isNotEmpty() }
                }
                .firstOrNull()
                ?: raw
        }.getOrDefault(raw)
    }

    private fun findRawLyricBlob(playbackExtras: Bundle?, metadata: MediaMetadata?): String? {
        metadata?.getString(METADATA_KEY_LYRIC)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        playbackExtras?.nonBlankString(METADATA_KEY_LYRIC)?.let { return it }
        metadata?.bundleCompat()?.nonBlankString(METADATA_KEY_LYRIC)?.let { return it }
        return null
    }

    private fun looksLikeLrc(text: String): Boolean {
        if (!text.contains('[')) return false
        var hits = 0
        for (line in text.lineSequence()) {
            if (LRC_LINE.containsMatchIn(line)) {
                hits++
                if (hits >= 2) return true
            }
        }
        return LRC_LINE.containsMatchIn(text)
    }

    private data class LrcEntry(val timeMs: Long, val text: String)

    private fun parseLrc(raw: String): List<LrcEntry> {
        val out = ArrayList<LrcEntry>(64)
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            var rest = trimmed
            val times = ArrayList<Long>(2)
            while (true) {
                val m = LRC_LINE.matchEntire(rest) ?: break
                val min = m.groupValues[1].toLongOrNull() ?: break
                val sec = m.groupValues[2].toLongOrNull() ?: break
                val frac = m.groupValues[3]
                val fracMs = when {
                    frac.isEmpty() -> 0L
                    frac.length == 1 -> frac.toLong() * 100L
                    frac.length == 2 -> frac.toLong() * 10L
                    else -> frac.take(3).padEnd(3, '0').toLong()
                }
                times.add(min * 60_000L + sec * 1_000L + fracMs)
                rest = m.groupValues[4].trim()
                if (!rest.startsWith('[')) break
            }
            val text = rest.trim()
            if (text.isEmpty() || times.isEmpty()) continue
            for (t in times) {
                out.add(LrcEntry(t, text))
            }
        }
        if (out.isEmpty()) return emptyList()
        out.sortBy { it.timeMs }
        return out
    }

    private fun lineAtPosition(entries: List<LrcEntry>, positionMs: Long): String? {
        if (entries.isEmpty()) return null
        var lo = 0
        var hi = entries.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].timeMs <= positionMs) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (best < 0) return null
        return entries[best].text.trim().takeIf { it.isNotEmpty() }
    }

    private fun Bundle.nonBlankString(key: String): String? {
        if (!containsKey(key)) return null
        getString(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun collectKeys(bundle: Bundle?, out: MutableSet<String>) {
        if (bundle == null) return
        for (key in bundle.keySet()) {
            out.add(key)
        }
    }

    private fun MediaMetadata.bundleCompat(): Bundle? {
        return runCatching {
            val field = MediaMetadata::class.java.getDeclaredField("mBundle").apply {
                isAccessible = true
            }
            field.get(this) as? Bundle
        }.getOrNull()
    }

    private fun truncate(text: String): String {
        if (text.length <= MAX_CHARS) return text
        return text.take(MAX_CHARS)
    }
}
