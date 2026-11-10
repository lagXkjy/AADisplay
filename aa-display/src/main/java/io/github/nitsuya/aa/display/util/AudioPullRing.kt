package io.github.nitsuya.aa.display.util

import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * App-local drop-to-live ring in front of the system_server capture rings.
 *
 * The system side ([io.github.nitsuya.aa.display.xposed.carplay.CarPlayAudioCapture])
 * already trims to ≤100 ms of content, but every consumer paces on its own wall
 * clock and pulled via a synchronous Binder round trip on the pacing thread — a
 * busy system_server (1–50 ms) landed directly in the audio cadence.
 *
 * This ring keeps the pull off the pacing path: one puller thread makes coarse
 * [pull] calls (up to 64 KB each) into a local ring; pacers read locally with a
 * bounded wait. Overflow drops the oldest (4-byte PCM-frame aligned) so the
 * consumer always sees the newest ≤ [highWatermarkBytes] of audio, mirroring the
 * system ring's drop-to-live hysteresis.
 */
class AudioPullRing(
    val label: String,
    capacityBytes: Int,
    private val highWatermarkBytes: Int,
    private val lowWatermarkBytes: Int,
    private val pull: (Int) -> ByteArray,
) {
    private val cap = capacityBytes.coerceAtLeast(1024)
    private val high = highWatermarkBytes.coerceIn(0, cap)
    private val low = lowWatermarkBytes.coerceIn(0, high)
    private val lock = Any()
    private val buf = ByteArray(cap)
    private var head = 0
    private var tail = 0
    private var available = 0

    private val running = AtomicBoolean(false)
    @Volatile
    var worker: Thread? = null
        private set
    /** Worker-side [flushUpstream] request (the puller is the only ring writer). */
    private val flushRequested = AtomicBoolean(false)
    @Volatile
    private var flushSignal: CountDownLatch? = null
    @Volatile
    private var lastFlushedBytes = 0

    /** Bytes dropped to stay drop-to-live, since ring creation. */
    @Volatile
    var droppedBytes = 0L
        private set

    /** Bytes currently buffered (for pacing-thread diagnostics). */
    val availableBytes: Int
        get() = synchronized(lock) { available }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = thread(name = "audio-pull-$label", isDaemon = true) {
            // Realtime audio supply: a Binder round trip delayed by a busy app process
            // (GL compositor / C2 encoder) opens a silence hole in the paced stream.
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var failLogged = false
            while (running.get()) {
                if (flushRequested.get()) {
                    // Run the flush HERE: the worker is the only ring writer, so
                    // drain-then-clear cannot race a concurrent write (a caller-side
                    // clear used to miss / clobber the puller's just-written bytes).
                    var dropped = 0
                    var aborted = false
                    for (i in 0 until MAX_FLUSH_PULLS) {
                        val got = try {
                            pull(PULL_MAX_BYTES)
                        } catch (e: InterruptedException) {
                            aborted = true
                            ByteArray(0)
                        } catch (_: Throwable) {
                            ByteArray(0)
                        }
                        if (aborted || got.isEmpty()) break
                        dropped += got.size
                    }
                    clear()
                    lastFlushedBytes = dropped
                    flushRequested.set(false)
                    flushSignal?.countDown()
                    if (aborted) break
                    continue
                }
                val got = try {
                    pull(PULL_MAX_BYTES)
                } catch (e: InterruptedException) {
                    throw e
                } catch (_: Throwable) {
                    if (!failLogged) {
                        failLogged = true
                        logI("pull $label failed — ring will stay empty")
                    }
                    ByteArray(0)
                }
                if (got.isEmpty()) {
                    // System ring is drop-to-live (≤100 ms): nothing new for now.
                    try {
                        Thread.sleep(PULL_IDLE_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                write(got)
                // The 64 KB pull took whatever the system ring held; only re-poll
                // quickly when it came back full (capture burst).
                try {
                    Thread.sleep(if (got.size >= PULL_MAX_BYTES) 1L else PULL_IDLE_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        worker?.interrupt()
        worker = null
        // Unblock a caller waiting on a pending worker-side flush.
        flushRequested.set(false)
        flushSignal?.countDown()
        flushSignal = null
        clear()
    }

    /** Discard everything buffered — content this old is never sent. */
    fun clear() {
        synchronized(lock) {
            head = 0
            tail = 0
            available = 0
        }
    }

    /**
     * Drain the upstream system ring (up to [MAX_FLUSH_PULLS] coarse pulls, stop at
     * the first empty) and then [clear] the local ring, so the first paced packet is
     * live. Requested on the caller thread, executed on the puller worker — the only
     * ring writer — so drain and clear cannot interleave with a concurrent write;
     * the caller waits (bounded) for the worker to finish.
     *
     * @return bytes discarded from the system ring.
     */
    fun flushUpstream(): Int {
        if (!running.get()) {
            // Worker not running — nothing to race; drain directly.
            var dropped = 0
            for (i in 0 until MAX_FLUSH_PULLS) {
                val got = runCatching { pull(PULL_MAX_BYTES) }.getOrDefault(ByteArray(0))
                if (got.isEmpty()) break
                dropped += got.size
            }
            clear()
            return dropped
        }
        lastFlushedBytes = 0
        val latch = CountDownLatch(1)
        flushSignal = latch
        flushRequested.set(true)
        try {
            latch.await(FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        flushRequested.set(false)
        flushSignal = null
        return lastFlushedBytes
    }

    /** Append [data], dropping the oldest beyond the watermarks (4-byte aligned). */
    fun write(data: ByteArray) {
        if (data.isEmpty()) return
        synchronized(lock) {
            var srcOff = 0
            var srcLen = data.size
            if (srcLen > cap) {
                srcOff = srcLen - cap
                srcLen = cap
            }
            val free = cap - available
            if (srcLen > free) {
                var drop = srcLen - free
                val rem = drop % 4
                if (rem != 0) drop += 4 - rem
                if (drop > available) drop = available - (available % 4)
                if (drop > 0) {
                    tail = (tail + drop) % cap
                    available -= drop
                    droppedBytes += drop.toLong()
                }
            }
            var off = 0
            while (off < srcLen) {
                val chunk = minOf(srcLen - off, cap - head)
                if (chunk <= 0) break
                System.arraycopy(data, srcOff + off, buf, head, chunk)
                head = (head + chunk) % cap
                available += chunk
                off += chunk
            }
            trimToWatermarkLocked()
        }
    }

    private fun trimToWatermarkLocked() {
        if (available <= high) return
        var drop = available - low
        drop -= drop % 4
        if (drop <= 0) return
        tail = (tail + drop) % cap
        available -= drop
        droppedBytes += drop.toLong()
    }

    /**
     * Read up to [maxBytes] of the newest audio; block at most [timeoutMs] when the
     * ring is empty. Returns whatever is available (possibly short / empty) —
     * callers pad with silence exactly as they did for short Binder pulls.
     */
    fun read(maxBytes: Int, timeoutMs: Long = 16L): ByteArray {
        val want = maxBytes.coerceIn(0, cap)
        if (want <= 0) return ByteArray(0)
        val out = ByteArray(want)
        var n = 0
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (n < want) {
            var chunk = 0
            synchronized(lock) {
                val take = minOf(available, want - n)
                if (take > 0) {
                    var off = 0
                    while (off < take) {
                        val c = minOf(take - off, cap - tail)
                        if (c <= 0) break
                        System.arraycopy(buf, tail, out, n + off, c)
                        tail = (tail + c) % cap
                        off += c
                    }
                    available -= take
                    chunk = take
                    n += chunk
                }
            }
            if (chunk == 0) {
                if (System.nanoTime() >= deadline) break
                try {
                    Thread.sleep(0, 500_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return if (n == want) out else out.copyOf(n)
    }

    companion object {
        private const val PULL_MAX_BYTES = 64 * 1024
        private const val PULL_IDLE_MS = 8L
        private const val MAX_FLUSH_PULLS = 8
        /** Bounds the caller-side wait for the worker to finish a flush. */
        private const val FLUSH_TIMEOUT_MS = 300L

        private fun logI(msg: String) {
            android.util.Log.i("AADisplay_AudioPullRing", msg)
        }
    }
}
