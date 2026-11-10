package io.github.nitsuya.aa.display.xposed.carplay

/**
 * Simple SPSC ring for one producer / one Binder consumer
 * (media: capture reader → pull; speech: capture reader → pullSpeech).
 * When backlog exceeds [highWatermarkBytes], drop oldest down to [lowWatermarkBytes]
 * on both write and pull so stale PCM is discarded even while Binder is late.
 */
internal class CarPlayPcmRing(
    private val capacity: Int,
    private val highWatermarkBytes: Int,
    private val lowWatermarkBytes: Int,
) {
    private val buf = ByteArray(capacity)
    private val lock = Any()
    private var head = 0
    private var tail = 0
    private var available = 0
    var dropped = 0L
        private set

    /** Bytes currently buffered (for watchdog / pull-timeout diagnostics). */
    val availableBytes: Int
        get() = synchronized(lock) { available }

    fun clear() {
        synchronized(lock) {
            head = 0
            tail = 0
            available = 0
            dropped = 0L
        }
    }

    fun write(data: ByteArray) {
        if (data.isEmpty()) return
        synchronized(lock) {
            // Keep only what fits; prefer the newest samples when the payload is huge.
            var srcOff = 0
            var srcLen = data.size
            if (srcLen > capacity) {
                srcOff = srcLen - capacity
                srcLen = capacity
            }
            val free = capacity - available
            if (srcLen > free) {
                // Round drop UP to a 4-byte PCM frame so available + srcLen <= capacity.
                // Rounding down left available > capacity and broke pull wrap math.
                var drop = srcLen - free
                val rem = drop % 4
                if (rem != 0) drop += 4 - rem
                if (drop > available) drop = available - (available % 4)
                if (drop > 0) {
                    tail = (tail + drop) % capacity
                    available -= drop
                    dropped += drop.toLong()
                }
            }
            var off = 0
            while (off < srcLen) {
                val chunk = minOf(srcLen - off, capacity - head)
                if (chunk <= 0) break
                System.arraycopy(data, srcOff + off, buf, head, chunk)
                head = (head + chunk) % capacity
                available += chunk
                off += chunk
            }
            if (available > capacity) available = capacity
            // Drop-to-live on the producer side: do not wait for a late Binder pull
            // to discover a full second of backlog already queued for TX.
            trimToWatermarkLocked()
        }
    }

    fun pull(maxBytes: Int): ByteArray {
        synchronized(lock) {
            trimToWatermarkLocked()
            val take = minOf(available, maxBytes.coerceAtLeast(0))
            if (take <= 0) return ByteArray(0)
            val arr = ByteArray(take)
            var off = 0
            while (off < take) {
                // Remaining in the ring is [available], remaining to fill is [take - off].
                // Never use (available - off): after a wrap chunk that double-subtracts
                // and can yield chunk==0 → spin forever while holding the lock, which
                // wedges every Binder pullCarPlayAudio + the capture writer (no car
                // audio, phone stays muted).
                val chunk = minOf(take - off, available, capacity - tail)
                if (chunk <= 0) break
                System.arraycopy(buf, tail, arr, off, chunk)
                tail = (tail + chunk) % capacity
                available -= chunk
                off += chunk
            }
            return if (off == take) arr else arr.copyOf(off)
        }
    }

    private fun trimToWatermarkLocked() {
        val high = highWatermarkBytes.coerceIn(0, capacity)
        val low = lowWatermarkBytes.coerceIn(0, high)
        if (available <= high) return
        var drop = available - low
        drop -= drop % 4
        if (drop <= 0) return
        tail = (tail + drop) % capacity
        available -= drop
        dropped += drop.toLong()
    }
}
