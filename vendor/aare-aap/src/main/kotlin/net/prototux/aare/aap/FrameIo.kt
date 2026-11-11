package net.prototux.aare.aap

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.System

/**
 * Reads frames and reassembles them into [AapMessage]s.
 *
 * Fragments of different channels may interleave on the wire, so partial messages are kept per
 * channel. Encrypted payloads are decrypted frame by frame, in arrival order, as TLS requires.
 *
 * [drainStale] (USB re-attach recovery): a port the head unit still believes is alive keeps
 * flushing the previous session's tail into the pipe after the phone reopens it — encrypted
 * frames this reader cannot decrypt, plus continuation fragments whose first frame was lost to
 * the reopen. Until the fresh (plaintext) version request arrives they are discarded, not fatal;
 * after it, orphan encrypted continuations are still tolerated. Without [drainStale] every one
 * of those is a session error, as before.
 */
class FrameReader(input: InputStream, private val cryptor: () -> Cryptor?, private val drainStale: Boolean = false) {
    private val input = DataInputStream(input.buffered(64 * 1024))
    private class Partial(val declaredTotal: Int, val buf: ByteArrayOutputStream)

    private val partial = HashMap<Int, Partial>()
    // Channels whose partial message has a plaintext fragment: the whole message then counts as plaintext.
    private val partialPlain = HashSet<Int>()

    /** Bytes received so far across all open partials; bounds memory across channels. */
    private var buffered = 0L
    private var header = ByteArray(Frame.EXTENDED_SIZE)
    private var payload = ByteArray(Frame.MAX_PAYLOAD + 1024)

    // Whether the session has seen the version request yet (the end of the stale-drain window).
    // Bound from [AapSession]; unbound = window already closed.
    private var reachedRef: (() -> Boolean)? = null

    fun bindReachedProbe(probe: () -> Boolean) {
        reachedRef = probe
    }

    // Stale-tail discard accounting (drainStale): throttled progress line + a summary when the window closes.
    @Volatile private var staleDroppedFrames = 0L
    @Volatile private var staleDroppedBytes = 0L
    @Volatile private var lastStaleLogMs = 0L

    /** Blocks until a complete message is available. Throws on end of stream or a framing error. */
    fun read(): AapMessage {
        while (true) {
            val channel = input.readUnsignedByte()
            val flags = input.readUnsignedByte()
            val size = input.readUnsignedShort()
            val declaredTotal = if (Frame.hasTotalSize(flags)) input.readInt() else 0
            if (payload.size < size) payload = ByteArray(size)
            input.readFully(payload, 0, size)

            val first = Frame.isFirst(flags)
            val last = Frame.isLast(flags)
            val encrypted = Frame.isEncrypted(flags)

            if (drainStale) {
                val draining = !reached()
                val orphan = !first && partial[channel] == null
                if ((draining && encrypted) || (orphan && encrypted)) {
                    staleDrop(channel, size, first, last)
                    continue
                }
                if (draining && orphan) {
                    // A stale plaintext continuation cannot happen (the phone side never sent
                    // plaintext mid-session), but a lost boundary must not kill the handshake either.
                    staleDrop(channel, size, first, last)
                    continue
                }
            }

            val plain: ByteArray
            val plainLen: Int
            if (encrypted) {
                val c = cryptor() ?: throw AapException("encrypted frame before TLS is up (ch=$channel)")
                plain = c.decrypt(payload, 0, size)
                plainLen = plain.size
            } else {
                plain = payload
                plainLen = size
            }

            if (first && last) {
                staleWindowClosed()
                return toMessage(channel, flags, encrypted, plain.copyOf(plainLen), plainLen)
            }
            if (first) {
                partial.remove(channel)?.let { buffered -= it.buf.size() }
                partialPlain.remove(channel)
                // Early reject on the announced size; the real guard is the received-bytes check below.
                if (declaredTotal < 0 || declaredTotal > MAX_MESSAGE_SIZE) {
                    throw AapException("declared message size $declaredTotal out of range (ch=$channel)")
                }
                val buf = ByteArrayOutputStream(minOf(plainLen * 4, declaredTotal))
                val state = Partial(declaredTotal, buf)
                appendFragment(channel, state, plain, plainLen)
                partial[channel] = state
                if (encrypted) partialPlain.remove(channel) else partialPlain.add(channel)
                continue
            }
            val state = partial[channel] ?: throw AapException("continuation frame without a first frame (ch=$channel)")
            appendFragment(channel, state, plain, plainLen)
            if (!encrypted) partialPlain.add(channel)
            if (last) {
                partial.remove(channel)
                buffered -= state.buf.size()
                val wasEncrypted = !partialPlain.remove(channel)
                val all = state.buf.toByteArray()
                staleWindowClosed()
                return toMessage(channel, flags, wasEncrypted, all, all.size)
            }
        }
    }

    /** Fresh data arrived: the stale-drain window is over — one summary line if anything was dropped. */
    private fun staleWindowClosed() {
        if (!drainStale) return
        val n = staleDroppedFrames
        if (n == 0L) return
        staleDroppedFrames = 0L
        println("I/aap: stale drain window closed — discarded $n frames / $staleDroppedBytes bytes of the previous session")
    }

    private fun staleDrop(channel: Int, size: Int, first: Boolean, last: Boolean) {
        staleDroppedFrames += 1
        staleDroppedBytes += size
        val now = System.currentTimeMillis()
        if (now - lastStaleLogMs > 2_000L) {
            lastStaleLogMs = now
            println(
                "W/aap: discarding stale frame from the previous session " +
                    "(ch=$channel size=$size first=$first last=$last; waiting for the fresh version request)",
            )
        }
    }

    /**
     * Appends one fragment after checking the bytes actually received, so a head unit announcing a
     * wrong size cannot make us buffer more than the caps, nor more than it declared.
     */
    private fun appendFragment(channel: Int, state: Partial, data: ByteArray, len: Int) {
        val received = state.buf.size().toLong() + len
        val error = when {
            received > MAX_MESSAGE_SIZE -> "reassembled message exceeds $MAX_MESSAGE_SIZE bytes"
            received > state.declaredTotal -> "reassembled message exceeds declared size ${state.declaredTotal}"
            buffered + len > MAX_BUFFERED -> "too much partial data buffered"
            else -> null
        }
        if (error != null) {
            partial.clear()
            partialPlain.clear()
            buffered = 0
            throw AapException("$error (ch=$channel)")
        }
        state.buf.write(data, 0, len)
        buffered += len
    }

    private fun toMessage(channel: Int, flags: Int, encrypted: Boolean, data: ByteArray, len: Int): AapMessage {
        if (len < 2) throw AapException("message too short on ch=$channel")
        val id = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
        return AapMessage(channel, Frame.isControl(flags), encrypted, id, data, 2, len - 2)
    }

    private fun reached(): Boolean = reachedRef?.invoke() ?: true

    private companion object {
        /** Upper bound of a reassembled message; a larger declared total is a protocol violation. */
        const val MAX_MESSAGE_SIZE = 8 * 1024 * 1024

        /** Upper bound of the bytes received for all partial messages open at once, across channels. */
        const val MAX_BUFFERED = 2L * MAX_MESSAGE_SIZE
    }
}

/**
 * Splits messages into frames, encrypting each chunk when needed, and writes them.
 * Fragments of one message are written back to back: some head units can't reassemble
 * interleaved fragments, so we never interleave on output.
 */
class FrameWriter(output: OutputStream, private val cryptor: () -> Cryptor?) {
    private val output = output
    private val frame = ByteArray(Frame.EXTENDED_SIZE + Frame.MAX_PAYLOAD + 1024)

    /**
     * Writes one message. [plain] holds id + body (see [AapMessage.plaintext]).
     * Must be called from a single thread (the session's writer), because TLS records must
     * leave in the order they were sealed.
     */
    fun write(channel: Int, control: Boolean, encrypted: Boolean, plain: ByteArray, length: Int = plain.size) {
        val c = if (encrypted) cryptor() ?: throw AapException("TLS not ready") else null
        var offset = 0
        val baseFlags = (if (control) Frame.FLAG_CONTROL else 0) or (if (encrypted) Frame.FLAG_ENCRYPTED else 0)
        do {
            val chunk = minOf(Frame.MAX_PAYLOAD, length - offset)
            val first = offset == 0
            val last = offset + chunk == length
            var flags = baseFlags
            if (first) flags = flags or Frame.FLAG_FIRST
            if (last) flags = flags or Frame.FLAG_LAST
            val extended = first && !last

            val body: ByteArray
            val bodyOff: Int
            val bodyLen: Int
            if (c != null) {
                body = c.encrypt(plain, offset, chunk); bodyOff = 0; bodyLen = body.size
            } else {
                body = plain; bodyOff = offset; bodyLen = chunk
            }
            if (bodyLen > 0xffff) throw AapException("frame too large: $bodyLen")

            var p = 0
            frame[p++] = channel.toByte()
            frame[p++] = flags.toByte()
            frame[p++] = (bodyLen shr 8).toByte()
            frame[p++] = bodyLen.toByte()
            if (extended) {
                frame[p++] = (length ushr 24).toByte()
                frame[p++] = (length ushr 16).toByte()
                frame[p++] = (length ushr 8).toByte()
                frame[p++] = length.toByte()
            }
            if (p + bodyLen <= frame.size) {
                System.arraycopy(body, bodyOff, frame, p, bodyLen)
                output.write(frame, 0, p + bodyLen)
            } else {
                output.write(frame, 0, p)
                output.write(body, bodyOff, bodyLen)
            }
            offset += chunk
        } while (offset < length)
        output.flush()
    }
}
