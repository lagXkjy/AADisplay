package net.prototux.aare.aap

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** A byte pipe to a head unit: USB accessory, TCP (wireless or the developer head-unit server). */
interface Transport {
    val name: String
    val input: InputStream
    val output: OutputStream

    /** Closes both directions; must unblock a reader blocked in [input]. Idempotent. */
    fun close()
}

class SocketTransport(private val socket: Socket, override val name: String = "tcp ${socket.remoteSocketAddress}") : Transport {
    init {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        // big buffers: video frames are tens of kB and we don't want the writer to stall per frame
        runCatching { socket.sendBufferSize = 512 * 1024 }
        runCatching { socket.receiveBufferSize = 256 * 1024 }
    }

    override val input: InputStream = socket.getInputStream()
    override val output: OutputStream = socket.getOutputStream()

    override fun close() {
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }
}
