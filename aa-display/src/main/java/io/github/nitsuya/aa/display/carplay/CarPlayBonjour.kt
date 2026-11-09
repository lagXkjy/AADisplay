package io.github.nitsuya.aa.display.carplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import android.util.Log
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Discovers wireless CarPlay / AirPlay services and registers local `_carplay-ctrl`.
 *
 * CarDeck Audi lessons borrowed here (ours previously lacked these):
 * - TXT `id` must be the real Bluetooth MAC (placeholder → HU never invites).
 * - Fresh [NsdManager.RegistrationListener] each register (reuse → already in use).
 * - Force re-register after accessory Wi‑Fi bind (multicast follows process network).
 * - Discover `_airplay._tcp` when MIB3 never sends `0x4301`; filter by our /24.
 */
class CarPlayBonjour(
    context: Context,
    private val onCtrlResolved: (NsdServiceInfo) -> Unit,
    private val onAirPlayResolved: (NsdServiceInfo) -> Unit = {},
) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val ctrlDiscovering = AtomicBoolean(false)
    private val airplayDiscovering = AtomicBoolean(false)
    private val registered = AtomicBoolean(false)
    private var localPort: Int = 0
    private var registeredMac: String = ""
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var registeredAtMs = 0L

    private val airplayEndpoint = AtomicReference<NsdServiceInfo?>(null)
    /** Optional /24 filter: only accept AirPlay hosts whose IPv4 shares this prefix. */
    @Volatile
    var airplaySubnetPrefix24: String? = null

    private val ctrlDiscoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
            Log.e(CarPlayConsts.TAG, "Bonjour ctrl discovery start failed code=$errorCode")
            ctrlDiscovering.set(false)
        }

        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
            Log.e(CarPlayConsts.TAG, "Bonjour ctrl discovery stop failed code=$errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String?) {
            Log.i(CarPlayConsts.TAG, "Bonjour discovery start type=$serviceType")
        }

        override fun onDiscoveryStopped(serviceType: String?) {
            Log.i(CarPlayConsts.TAG, "Bonjour discovery stopped")
            ctrlDiscovering.set(false)
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
            val info = serviceInfo ?: return
            Log.i(CarPlayConsts.TAG, "Bonjour found ${info.serviceName} type=${info.serviceType}")
            if (info.serviceName == SERVICE_NAME) return
            resolveFound(info) { onCtrlResolved(it) }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
            Log.i(CarPlayConsts.TAG, "Bonjour lost ${serviceInfo?.serviceName}")
        }
    }

    private val airplayDiscoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
            Log.e(CarPlayConsts.TAG, "Bonjour airplay discovery start failed code=$errorCode")
            airplayDiscovering.set(false)
        }

        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
            Log.e(CarPlayConsts.TAG, "Bonjour airplay discovery stop failed code=$errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String?) {
            Log.i(CarPlayConsts.TAG, "Bonjour airplay discovery start type=$serviceType")
        }

        override fun onDiscoveryStopped(serviceType: String?) {
            airplayDiscovering.set(false)
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
            val info = serviceInfo ?: return
            Log.i(
                CarPlayConsts.TAG,
                "Bonjour airplay found ${info.serviceName} type=${info.serviceType}",
            )
            resolveFound(info) { resolved ->
                if (!acceptAirPlay(resolved)) {
                    Log.i(
                        CarPlayConsts.TAG,
                        "Bonjour airplay ignored ${resolved.host}:${resolved.port} (subnet filter)",
                    )
                    return@resolveFound
                }
                airplayEndpoint.set(resolved)
                Log.i(
                    CarPlayConsts.TAG,
                    "Bonjour airplay ok ${resolved.serviceName} → ${resolved.host}:${resolved.port}",
                )
                onAirPlayResolved(resolved)
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
            val lost = serviceInfo ?: return
            val cur = airplayEndpoint.get()
            if (cur != null && cur.serviceName == lost.serviceName) {
                airplayEndpoint.compareAndSet(cur, null)
            }
        }
    }

    private fun resolveFound(info: NsdServiceInfo, onResolved: (NsdServiceInfo) -> Unit) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    Thread({
                        try {
                            Thread.sleep(200)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                        resolveFound(info, onResolved)
                    }, "bonjour-resolve-retry").apply { isDaemon = true }.start()
                    return
                }
                Log.e(CarPlayConsts.TAG, "Bonjour resolve failed code=$errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                val resolved = serviceInfo ?: return
                Log.i(
                    CarPlayConsts.TAG,
                    "Bonjour resolved ${resolved.serviceName} host=${resolved.host} port=${resolved.port}",
                )
                onResolved(resolved)
            }
        }
        try {
            nsd.resolveService(info, listener)
        } catch (e: IllegalArgumentException) {
            Log.w(CarPlayConsts.TAG, "Bonjour resolve skipped: ${e.message}")
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "Bonjour resolveService failed", e)
        }
    }

    private fun acceptAirPlay(info: NsdServiceInfo): Boolean {
        val prefix = airplaySubnetPrefix24 ?: return true
        val host = info.host ?: return false
        val v4 = when (host) {
            is Inet4Address -> host.hostAddress
            else -> host.hostAddress?.takeIf { !it.contains(':') }
        } ?: return false
        val parts = v4.split('.')
        if (parts.size != 4) return false
        val hostPrefix = "${parts[0]}.${parts[1]}.${parts[2]}"
        return hostPrefix == prefix
    }

    fun startCtrlDiscovery() {
        if (!ctrlDiscovering.compareAndSet(false, true)) return
        try {
            nsd.discoverServices(
                CarPlayConsts.BONJOUR_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                ctrlDiscoveryListener,
            )
        } catch (e: Throwable) {
            ctrlDiscovering.set(false)
            Log.e(CarPlayConsts.TAG, "Bonjour ctrl discoverServices failed", e)
        }
    }

    fun startAirPlayDiscovery() {
        if (!airplayDiscovering.compareAndSet(false, true)) return
        try {
            nsd.discoverServices(
                CarPlayConsts.AIRPLAY_BONJOUR_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                airplayDiscoveryListener,
            )
        } catch (e: Throwable) {
            airplayDiscovering.set(false)
            Log.e(CarPlayConsts.TAG, "Bonjour airplay discoverServices failed", e)
        }
    }

    fun resetAirPlayCache() {
        airplayEndpoint.set(null)
    }

    fun cachedAirPlayEndpoint(): NsdServiceInfo? = airplayEndpoint.get()

    /**
     * Wait up to [timeoutMs] for a resolved `_airplay._tcp` on the accessory subnet.
     */
    fun awaitAirPlayEndpoint(timeoutMs: Long): NsdServiceInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            airplayEndpoint.get()?.let { return it }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return airplayEndpoint.get()
            }
        }
        return airplayEndpoint.get()
    }

    /**
     * Register `_carplay-ctrl._tcp` with TXT `id` = Bluetooth MAC.
     * [force] re-registers even when already up (after Wi‑Fi bind / MAC repair).
     */
    fun registerControl(port: Int, bluetoothMac: String, force: Boolean = false) {
        localPort = port
        val mac = bluetoothMac.trim().uppercase()
        if (!force && registered.get() && registeredMac == mac && localPort == port) {
            return
        }
        if (registered.get()) {
            unregisterQuietly()
            // NsdManager unregister is async — brief pause before reuse.
            try {
                Thread.sleep(if (force) 400 else 200)
            } catch (_: InterruptedException) {
            }
        }
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = CarPlayConsts.BONJOUR_TYPE
            setPort(port)
            if (mac.isNotEmpty() && mac != "02:00:00:00:00:00") {
                setAttribute("id", mac)
            } else {
                Log.w(CarPlayConsts.TAG, "Bonjour ctrl TXT id missing real BT MAC — HU may not invite")
            }
            setAttribute("srcvers", "220.68")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(CarPlayConsts.TAG, "bonjour-registration failed code=$errorCode")
                registered.set(false)
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(CarPlayConsts.TAG, "bonjour unregistration failed code=$errorCode")
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                Log.i(
                    CarPlayConsts.TAG,
                    "bonjour-registration ok ${serviceInfo?.serviceName} id=$mac port=$port",
                )
                registered.set(true)
                registeredAtMs = SystemClock.uptimeMillis()
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                registered.set(false)
            }
        }
        registrationListener = listener
        registeredMac = mac
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registered.set(true)
            registeredAtMs = SystemClock.uptimeMillis()
        } catch (e: Throwable) {
            registered.set(false)
            Log.e(CarPlayConsts.TAG, "Bonjour registerService failed", e)
            // One retry after async unregister settles.
            Thread({
                try {
                    Thread.sleep(1_200)
                    nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
                    registered.set(true)
                    registeredAtMs = SystemClock.uptimeMillis()
                    Log.i(CarPlayConsts.TAG, "bonjour-registration retry ok id=$mac")
                } catch (e2: Throwable) {
                    Log.e(CarPlayConsts.TAG, "Bonjour registerService retry failed", e2)
                }
            }, "bonjour-register-retry").apply { isDaemon = true }.start()
        }
    }

    private fun unregisterQuietly() {
        val listener = registrationListener
        registrationListener = null
        try {
            if (listener != null) nsd.unregisterService(listener)
        } catch (_: Throwable) {
        }
        registered.set(false)
    }

    fun stop() {
        try {
            if (ctrlDiscovering.get()) nsd.stopServiceDiscovery(ctrlDiscoveryListener)
        } catch (_: Throwable) {
        }
        ctrlDiscovering.set(false)
        try {
            if (airplayDiscovering.get()) nsd.stopServiceDiscovery(airplayDiscoveryListener)
        } catch (_: Throwable) {
        }
        airplayDiscovering.set(false)
        unregisterQuietly()
        airplayEndpoint.set(null)
    }

    companion object {
        private const val SERVICE_NAME = "AADisplay-CarPlay"

        /** `/24` prefix of an IPv4 dotted string, or null. */
        fun prefix24(ipv4: String?): String? {
            val parts = ipv4?.trim()?.split('.').orEmpty()
            if (parts.size != 4) return null
            return "${parts[0]}.${parts[1]}.${parts[2]}"
        }
    }
}
