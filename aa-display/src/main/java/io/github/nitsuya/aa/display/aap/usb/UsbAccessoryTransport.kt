package io.github.nitsuya.aa.display.aap.usb

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.aap.AapConsts
import net.prototux.aare.aap.AapException
import net.prototux.aare.aap.Transport
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wired head units switch the phone to USB accessory mode (AOA) with the strings
 * manufacturer "Android", model "Android Auto", then talk AAP over the bulk endpoints.
 *
 * Kernel AOA drops the tail of a bulk transfer if userspace reads with a buffer smaller
 * than one transfer (16 kB). More importantly, if nobody reads while [aap-reader] is stuck
 * in TLS/Binder, the kernel RX buffer overflows and whole frames vanish (TLS NONCE-GAP).
 * A dedicated high-priority drain thread keeps the kernel empty into a large userspace ring;
 * [FrameReader] then consumes the ring at its own pace.
 */
class UsbAccessoryTransport(usb: UsbManager, val accessory: UsbAccessory) : Transport {
    private val fd: ParcelFileDescriptor = usb.openAccessory(accessory)
        ?: throw AapException("cannot open USB accessory ${accessory.model} (permission?)")

    override val name = "usb ${accessory.manufacturer} ${accessory.model} ${accessory.version}"

    private val rawIn = FileInputStream(fd.fileDescriptor)
    private val ring = UsbRxRing(RING_CAPACITY)
    private val drain = Thread(::drainLoop, "aap-usb-drain").apply {
        isDaemon = true
        priority = Thread.MAX_PRIORITY
        start()
    }

    override val input: InputStream = ring

    /** RX ring occupancy for diagnostics (health ticker). */
    fun ringUsed(): Int = ring.availableBytes()

    // The accessory fd has no kernel send buffer (unlike a TCP socket), so an unbuffered write
    // blocks per fragment on the USB bulk endpoint. Buffer so FrameWriter's per-fragment writes
    // coalesce into one write per message at its flush() — fewer blocking syscalls on the pipe.
    // [fdOut] is wrapped in [timing] so a blocking stall (the HU's input endpoint not ready)
    // shows up with its size: ~200 ms stalls preceded the 11-09 HU-side TLS close_notify.
    private val fdOut = FileOutputStream(fd.fileDescriptor)
    // Nanosecond start of the write currently inside the kernel (0 = not blocked). A USB bulk
    // write cannot be interrupted; the only escape is closing the fd, so the phone's stall
    // watchdog reads this to decide when to do that.
    @Volatile private var inflightSinceNs = 0L
    private val timing = object : OutputStream() {
        @Volatile private var lastStallLogMs = 0L
        override fun write(b: Int) {
            val one = ByteArray(1); one[0] = b.toByte(); write(one, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val t0 = System.nanoTime()
            if (len > 0) inflightSinceNs = t0
            try {
                fdOut.write(b, off, len)
            } catch (t: Throwable) {
                inflightSinceNs = 0L
                throw t
            } finally {
                inflightSinceNs = 0L
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (ms > 50) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastStallLogMs > 2_000L) {
                        lastStallLogMs = now
                        Log.w(AapConsts.TAG, "USB write blocked ${ms}ms (len=${len}B) — HU input endpoint slow")
                    }
                }
            }
        }
    }

    override val output: OutputStream = BufferedOutputStream(timing, 64 * 1024)

    /** How long the current kernel USB write has been blocked, in ms (0 when not blocked). */
    fun inflightWriteMs(): Long {
        val t = inflightSinceNs
        if (t == 0L) return 0
        return (System.nanoTime() - t) / 1_000_000
    }

    private val closed = AtomicBoolean(false)

    /**
     * First kernel I/O error on the accessory fd (the real reason a wired session dies);
     * the reader only sees the ring close, which says nothing.
     */
    @Volatile var readFault: Throwable? = null
        private set
    /** Bytes pulled from the USB pipe since attach; the health ticker turns the delta into a rate. */
    @Volatile var rxBytes: Long = 0
        private set
    /** Linux tid of aap-usb-drain; reported to system_server for SCHED_FIFO. */
    @Volatile private var drainTid = 0

    private fun drainLoop() {
        // Java Thread.MAX_PRIORITY alone is weak on Android CFS; match AapAudioEgress so a
        // phone UI spike (Settings / SF) cannot starve the AOA kernel RX drain → TLS NONCE-GAP.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        // system_server reasserts −19 in case OEM demotes the drain under UI load.
        drainTid = Process.myTid()
        runCatching { CoreApi.setThreadRealtimeBoost(drainTid, true) }
            .onFailure { Log.w(AapConsts.TAG, "USB drain nice boost request failed", it) }
        val chunk = ByteArray(USB_BULK_READ_SIZE)
        var lastHighLogMs = 0L
        var lastFullLogMs = 0L
        try {
            while (!closed.get()) {
                val n = try {
                    rawIn.read(chunk)
                } catch (t: Throwable) {
                    if (readFault == null) {
                        readFault = t
                        Log.w(AapConsts.TAG, "USB RX drain loop exited on I/O error: ${t.message}", t)
                    }
                    break
                }
                if (n < 0) break
                if (n == 0) continue
                rxBytes += n
                var off = 0
                while (off < n && !closed.get()) {
                    val wrote = ring.writeBlocking(chunk, off, n - off)
                    if (wrote <= 0) break
                    off += wrote
                    val used = ring.availableBytes()
                    val now = SystemClock.elapsedRealtime()
                    if (used >= RING_HIGH_WATER && now - lastHighLogMs > 2_000L) {
                        lastHighLogMs = now
                        Log.w(
                            AapConsts.TAG,
                            "USB RX ring high used=$used/$RING_CAPACITY " +
                                "(aap-reader behind; drain still pulling kernel)",
                        )
                    }
                    if (used >= RING_CAPACITY - USB_BULK_READ_SIZE && now - lastFullLogMs > 2_000L) {
                        lastFullLogMs = now
                        Log.w(
                            AapConsts.TAG,
                            "USB RX ring near-full used=$used/$RING_CAPACITY — drain may stall",
                        )
                    }
                }
            }
        } finally {
            ring.closeWriter()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val tid = drainTid
        if (tid > 0) {
            drainTid = 0
            runCatching { CoreApi.setThreadRealtimeBoost(tid, false) }
        }
        // Closing the fd unblocks drain's rawIn.read().
        runCatching { fd.close() }
        runCatching { rawIn.close() }
        runCatching { fdOut.close() }
        runCatching { output.close() }
        ring.closeWriter()
        drain.interrupt()
        runCatching { drain.join(500) }
        runCatching { ring.close() }
    }

    companion object {
        /** AOA bulk transfers are up to 16 kB; shorter userspace reads drop the remainder. */
        private const val USB_BULK_READ_SIZE = 16 * 1024

        /** Userspace cushion so TLS/dispatch/touch can lag without starving the kernel RX. */
        private const val RING_CAPACITY = 2 * 1024 * 1024
        private const val RING_HIGH_WATER = RING_CAPACITY * 3 / 4

        /**
         * Factory HUs advertise model `Android Auto`; Google DHU (`--usb`) uses
         * `Android Open Automotive Protocol`.
         */
        fun isAndroidAuto(a: UsbAccessory): Boolean {
            if (!a.manufacturer.equals("Android", true)) return false
            val model = a.model ?: return false
            return model.startsWith("Android Auto", ignoreCase = true) ||
                model.equals("Android Open Automotive Protocol", ignoreCase = true) ||
                model.contains("Automotive Protocol", ignoreCase = true)
        }
    }
}

/**
 * Single-producer / single-consumer byte ring used as an [InputStream].
 * Writer blocks when full (backpressure) instead of dropping — drop would recreate TLS gaps.
 */
private class UsbRxRing(private val capacity: Int) : InputStream() {
    private val buf = ByteArray(capacity)
    private var head = 0
    private var tail = 0
    private var size = 0
    private var writerClosed = false
    private var readerClosed = false

    @Synchronized
    fun availableBytes(): Int = size

    /**
     * Writes up to [len] bytes; blocks until at least one byte fits or the ring is closed.
     * @return bytes written, or -1 if closed with nothing written.
     */
    @Synchronized
    fun writeBlocking(src: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        while (!writerClosed && !readerClosed && size == capacity) {
            try {
                (this as Object).wait(200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return if (size < capacity) writeSome(src, off, len) else -1
            }
        }
        if (writerClosed || readerClosed) return -1
        return writeSome(src, off, len)
    }

    private fun writeSome(src: ByteArray, off: Int, len: Int): Int {
        val space = capacity - size
        if (space <= 0) return 0
        val n = minOf(len, space)
        val first = minOf(n, capacity - tail)
        System.arraycopy(src, off, buf, tail, first)
        if (n > first) {
            System.arraycopy(src, off + first, buf, 0, n - first)
        }
        tail = (tail + n) % capacity
        size += n
        (this as Object).notifyAll()
        return n
    }

    @Synchronized
    fun closeWriter() {
        writerClosed = true
        (this as Object).notifyAll()
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n < 0) -1 else one[0].toInt() and 0xff
    }

    @Synchronized
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (readerClosed) return -1
        if (len <= 0) return 0
        while (size == 0 && !writerClosed && !readerClosed) {
            try {
                (this as Object).wait(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return if (size > 0) readSome(b, off, len) else -1
            }
        }
        if (size == 0) return -1
        return readSome(b, off, len)
    }

    private fun readSome(b: ByteArray, off: Int, len: Int): Int {
        val n = minOf(len, size)
        val first = minOf(n, capacity - head)
        System.arraycopy(buf, head, b, off, first)
        if (n > first) {
            System.arraycopy(buf, 0, b, off + first, n - first)
        }
        head = (head + n) % capacity
        size -= n
        (this as Object).notifyAll()
        return n
    }

    @Synchronized
    override fun available(): Int = size

    @Synchronized
    override fun close() {
        readerClosed = true
        writerClosed = true
        (this as Object).notifyAll()
    }
}
