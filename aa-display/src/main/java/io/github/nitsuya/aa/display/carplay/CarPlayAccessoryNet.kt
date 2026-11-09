package io.github.nitsuya.aa.display.carplay

import android.net.Network
import android.util.Log
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * Accessory (car) Wi‑Fi [Network] for CarPlay LAN sockets only.
 *
 * Do **not** use [android.net.ConnectivityManager.bindProcessToNetwork] for this
 * network: that pins the whole process (and DNS) to a no-internet hotspot and
 * blocks cellular for anything else in-process. Other apps keep system routing;
 * CarPlay dials/streams call [bind] / [openTcp] so LAN traffic still hits the AP.
 *
 * Never fall back to an unbound [Socket] when the accessory [Network] is missing
 * or stale: with cellular as the default route, connect() sources from LTE
 * (`10.13.x.x`) and can never reach the car AP (`10.174.x.x`).
 */
object CarPlayAccessoryNet {
    private val networkRef = AtomicReference<Network?>(null)

    fun set(network: Network?) {
        networkRef.set(network)
    }

    fun get(): Network? = networkRef.get()

    fun clear() {
        networkRef.set(null)
    }

    /** True when a non-null accessory [Network] is held (may still be stale). */
    fun hasNetwork(): Boolean = networkRef.get() != null

    /**
     * Unconnected TCP socket routed to the accessory AP.
     * @param allowUnbound when true and no accessory [Network] is held, return a plain
     *   [Socket] (lab loopback / 127.0.0.1 only — never for real car AP dials).
     * @throws IllegalStateException if no live accessory Network (caller should retry
     * after Wi‑Fi handoff), or if bind fails after clearing a stale handle.
     */
    fun openTcp(allowUnbound: Boolean = false): Socket {
        val network = networkRef.get()
        if (network == null) {
            if (allowUnbound) return Socket()
            throw IllegalStateException("accessory Wi-Fi Network not bound")
        }
        return try {
            network.socketFactory.createSocket()
        } catch (e: Throwable) {
            // EPERM after Specifier RELEASE / NetworkAgent recycle — drop the corpse
            // so handoff can adopt a fresh Network on the next ensure/rejoin.
            if (networkRef.compareAndSet(network, null)) {
                Log.w(
                    CarPlayConsts.TAG,
                    "accessory SocketFactory failed — cleared stale Network",
                    e,
                )
            }
            throw IllegalStateException("accessory Wi-Fi Network bind failed", e)
        }
    }

    fun bind(socket: Socket) {
        val network = networkRef.get() ?: return
        try {
            network.bindSocket(socket)
        } catch (e: Throwable) {
            if (networkRef.compareAndSet(network, null)) {
                Log.w(CarPlayConsts.TAG, "accessory bindSocket(TCP) failed — cleared stale Network", e)
            }
        }
    }

    fun bind(socket: DatagramSocket) {
        val network = networkRef.get() ?: return
        try {
            network.bindSocket(socket)
        } catch (e: Throwable) {
            if (networkRef.compareAndSet(network, null)) {
                Log.w(CarPlayConsts.TAG, "accessory bindSocket(UDP) failed — cleared stale Network", e)
            }
        }
    }

    /**
     * Unconnected UDP socket: bind to accessory [Network] first, then wildcard port.
     * [DatagramSocket] constructors that auto-bind before [bind] can miss the AP route.
     */
    fun openUdp(): DatagramSocket {
        val network = networkRef.get()
            ?: throw IllegalStateException("accessory Wi-Fi Network not bound")
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        try {
            network.bindSocket(socket)
        } catch (e: Throwable) {
            socket.close()
            if (networkRef.compareAndSet(network, null)) {
                Log.w(
                    CarPlayConsts.TAG,
                    "accessory UDP bindSocket failed — cleared stale Network",
                    e,
                )
            }
            throw IllegalStateException("accessory Wi-Fi UDP bind failed", e)
        }
        socket.bind(InetSocketAddress(0))
        return socket
    }
}
