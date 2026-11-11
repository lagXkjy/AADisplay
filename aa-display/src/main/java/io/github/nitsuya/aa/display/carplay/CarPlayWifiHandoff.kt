package io.github.nitsuya.aa.display.carplay

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.nitsuya.aa.display.CoreApi
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Best-effort join of the accessory hotspot after iAP2 `0x5703` / `0x4301`.
 * Callers wait for [ensureAccessoryWifi] before dialing AirPlay TCP.
 *
 * OEM / ephemeral [WifiNetworkSpecifier] quirks this class must survive:
 * - `WifiInfo.ssid` often stays `<unknown ssid>` after a specifier join, so
 *   "already on SSID" must use our own [boundSsid] or [ConnectivityManager]
 *   transport SSID — never re-request / unregister a live join.
 * - Unregistering the [NetworkCallback] tears the association down immediately.
 * - Framework marks no-internet saved APs `CMD_UNWANTED_NETWORK` (score=0) and
 *   drops them within ~2–3s — that is the DiPlay connect→flash→reconnect loop.
 *   A live specifier callback (local-only, no INTERNET capability) holds the link;
 *   naked "adopt saved network" is only a temporary dial path, then we re-arm hold.
 * - A saved STA that already has INTERNET sets mMatchLocalOnlySpecifiers=false.
 *   A local-only specifier never attaches to it and can sit pending forever.
 *   Adopt that STA and hold it with a listen callback instead.
 * - Specifier [onUnavailable] must not leave a zombie [callbackRef] (dial forever
 *   "already pending"), nor abandon the session without a holding re-request.
 * - Specifier [onAvailable] Network handles often die within ~0.5s on some OEMs
 *   (`activeRequest: null` while wlan0 stays up). Never treat that as "leave the
 *   AP"; re-adopt from [ConnectivityManager.getAllNetworks] and keep dialing.
 * - A live specifier callback with **no** WIFI [Network] (agent already gone)
 *   never reaches onAvailable again. Re-request; do not sit on "already pending".
 */
class CarPlayWifiHandoff(context: Context) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbackRef = AtomicReference<ConnectivityManager.NetworkCallback?>(null)
    private val cellularCallbackRef = AtomicReference<ConnectivityManager.NetworkCallback?>(null)
    private val pendingReady = CopyOnWriteArrayList<() -> Unit>()
    /**
     * Serializes [pendingReady] add/clear against [fireReady]'s toList/clear drain.
     * The CopyOnWriteArrayList makes each op atomic but not the drain compound: a
     * concurrent add landing between toList() and clear() would be dropped without
     * ever firing (a lost 0x4301 dial callback costs the 8s mDNS fallback — or the
     * whole session on a HU that never advertises `_airplay`).
     */
    private val readyLock = Any()
    private val pendingSsid = AtomicReference<String?>(null)
    private val boundSsid = AtomicReference<String?>(null)
    private val boundNetwork = AtomicReference<Network?>(null)
    private val cachedPassphrase = AtomicReference<String?>(null)
    private val cachedChannel = AtomicReference<Int?>(null)
    private val readyFired = AtomicBoolean(false)
    private val wantJoin = AtomicBoolean(false)
    private val rejoinGeneration = AtomicInteger(0)
    private val holding = AtomicBoolean(false)
    private val callbackIsSpecifier = AtomicBoolean(false)
    private val radioEnableTried = AtomicBoolean(false)
    private val specifierRequestedAtMs = AtomicLong(0L)

    /**
     * Fired once the process is bound to the accessory AP (not on a failed specifier).
     * Argument is the STA MAC actually on the AP, when the platform exposes it.
     */
    var onBound: ((String?) -> Unit)? = null

    /**
     * When [ssid] is empty or already bound/connected, invokes [onReady] immediately.
     * Otherwise requests a Wi‑Fi specifier and invokes [onReady] after bind.
     * Duplicate ensure for the same SSID coalesces — never tears a live join.
     */
    @SuppressLint("MissingPermission")
    fun ensureAccessoryWifi(
        ssidRaw: String?,
        passphrase: String?,
        channel: Int?,
        onReady: () -> Unit,
    ) {
        val ssid = ssidRaw?.trim().orEmpty()
        if (ssid.isEmpty()) {
            Log.i(CarPlayConsts.TAG, "WiFi handoff skipped: empty SSID — dial on current network")
            onReady()
            return
        }
        if (!passphrase.isNullOrEmpty()) {
            cachedPassphrase.set(passphrase)
        }
        if (channel != null) {
            cachedChannel.set(channel)
        }
        wantJoin.set(true)

        // CarPlay handoff is the top priority: persist the hotspot as a saved,
        // autojoin-able network (system_server) before joining. OEM factories tear
        // down specifier-only joins of never-saved no-internet APs within seconds
        // — without this the session dies unless the user once joined manually.
        // Same call re-arms the settings "auto-reconnect" switch if the user
        // flipped it off mid-session (framework then kills the STA on toggle-off).
        armAccessoryWifiConfig(ssid)

        if (isBoundTo(ssid) || adoptIfAssociated(ssid, reason = "ensure")) {
            onReady()
            // Saved-network adopt alone is killed by OEM UNWANTED within seconds.
            ensureHoldingSpecifier(ssid)
            return
        }

        val existing = pendingSsid.get()
        val liveCb = callbackRef.get()
        if (liveCb != null && existing?.equals(ssid, ignoreCase = true) == true) {
            Log.i(CarPlayConsts.TAG, "WiFi handoff already pending ssid=$ssid — queue dial")
            synchronized(readyLock) { pendingReady.add(onReady) }
            // Specifier callback can outlive a usable Network handle (OEM).
            // Re-adopt from allNetworks before giving up to the watch loop.
            if (rebindForDial(ssid) || adoptIfAssociated(ssid, reason = "pending-flush")) {
                fireReady()
                ensureHoldingSpecifier(ssid)
            } else if (shouldRerequestSpecifier(ssid)) {
                Log.w(
                    CarPlayConsts.TAG,
                    "WiFi handoff zombie pending ssid=$ssid — re-request Specifier",
                )
                startSpecifierRequest(
                    ssid = ssid,
                    passphrase = passphrase ?: cachedPassphrase.get(),
                    channel = channel ?: cachedChannel.get(),
                    onReady = onReady,
                    preserveBind = false,
                )
            } else {
                scheduleAssociationWatch(ssid)
            }
            return
        }

        if (liveCb != null && boundSsid.get()?.equals(ssid, ignoreCase = true) == true) {
            Log.i(CarPlayConsts.TAG, "WiFi handoff coalesce ssid=$ssid — keep callback")
            synchronized(readyLock) { pendingReady.add(onReady) }
            if (readyFired.get()) {
                fireReady()
            }
            return
        }

        startSpecifierRequest(
            ssid = ssid,
            passphrase = passphrase ?: cachedPassphrase.get(),
            channel = channel,
            onReady = onReady,
            preserveBind = false,
        )
    }

    /**
     * Keep a local-only specifier [NetworkCallback] registered for the whole CP
     * session so the framework cannot mark the AP UNWANTED and tear TCP mid-stream.
     *
     * Never [switchToListenHold] after a Specifier join: unregistering the
     * Specifier callback drops L2 immediately on some OEMs (dial then sees
     * "accessory Wi-Fi Network not bound" forever).
     */
    private fun ensureHoldingSpecifier(ssid: String) {
        if (!wantJoin.get()) return
        if (callbackRef.get() != null && callbackIsSpecifier.get() &&
            (pendingSsid.get()?.equals(ssid, ignoreCase = true) == true ||
                boundSsid.get()?.equals(ssid, ignoreCase = true) == true)
        ) {
            holding.set(true)
            return
        }
        if (adoptIfAssociated(ssid, reason = "holding")) {
            fireReady()
            keepSpecifierHold(ssid)
            return
        }
        Log.i(CarPlayConsts.TAG, "WiFi handoff arm holding specifier ssid=$ssid")
        startSpecifierRequest(
            ssid = ssid,
            passphrase = cachedPassphrase.get(),
            channel = cachedChannel.get(),
            onReady = null,
            preserveBind = true,
        )
    }

    /** Prefer Specifier ownership; never tear Specifier down for a listen-only hold. */
    private fun keepSpecifierHold(ssid: String) {
        if (callbackRef.get() != null && callbackIsSpecifier.get()) {
            holding.set(true)
            return
        }
        // Listen-only does not own ephemeral AP — safe to drop and re-request Specifier.
        if (callbackRef.get() != null && !callbackIsSpecifier.get()) {
            releaseCallbacksOnly()
        }
        startSpecifierRequest(
            ssid = ssid,
            passphrase = cachedPassphrase.get(),
            channel = cachedChannel.get(),
            onReady = null,
            preserveBind = true,
        )
    }

    @SuppressLint("MissingPermission")
    private fun startSpecifierRequest(
        ssid: String,
        passphrase: String?,
        channel: Int?,
        onReady: (() -> Unit)?,
        preserveBind: Boolean,
    ) {
        // Only tear the previous callback when replacing; preserveBind keeps the
        // current L2 association while we try to obtain a holding request.
        if (!preserveBind) {
            releaseCallbacksOnly()
            boundSsid.set(null)
            boundNetwork.set(null)
            CarPlayAccessoryNet.clear()
            readyFired.set(false)
            synchronized(readyLock) { pendingReady.clear() }
        } else if (callbackRef.get() != null) {
            // Already have a live callback — do not unregister (that drops Wi‑Fi).
            if (onReady != null) {
                synchronized(readyLock) { pendingReady.add(onReady) }
                if (readyFired.get()) fireReady()
            }
            return
        }

        pendingSsid.set(ssid)
        if (onReady != null) {
            synchronized(readyLock) { pendingReady.add(onReady) }
        }
        if (!preserveBind) {
            holding.set(false)
        }

        if (wifi?.isWifiEnabled != true) {
            ensureWifiRadio()
            if (wifi?.isWifiEnabled != true) {
                Log.w(CarPlayConsts.TAG, "WiFi radio still off — defer join ssid=$ssid")
                callbackRef.set(null)
                scheduleHoldingRetry(ssid, rejoinGeneration.get())
                return
            }
        }
        if (adoptIfAssociated(ssid, reason = "pre-request")) {
            fireReady()
            // Fall through and still register Specifier so we own the hold.
            // switchToListenHold here used to unregister Specifier → OEM drops L2.
        }

        try {
            val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (!passphrase.isNullOrEmpty()) {
                specifierBuilder.setWpa2Passphrase(passphrase)
            }
            // Channel is logged only. WifiNetworkSpecifier.setBand() makes some OEMs'
            // WifiNetworkFactory reject the request ("frequency bands not yet supported"),
            // so a 5 GHz Audi AP (ch 149) never gets scanned. Match by SSID.
            if (channel != null) {
                Log.i(CarPlayConsts.TAG, "WiFi handoff ssid=$ssid ch=$channel (no band constraint)")
            }
            // Local-only: do not require INTERNET/VALIDATED — otherwise some OEMs
            // scores the AP 0 and emits CMD_UNWANTED_NETWORK within a few seconds.
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifierBuilder.build())
                .build()
            val generation = rejoinGeneration.incrementAndGet()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(CarPlayConsts.TAG, "WiFi handoff available ssid=$ssid hold=true")
                    holding.set(true)
                    bindNetwork(ssid, network)
                    fireReady()
                }

                override fun onUnavailable() {
                    Log.w(CarPlayConsts.TAG, "WiFi handoff unavailable ssid=$ssid")
                    callbackRef.compareAndSet(this, null)
                    callbackIsSpecifier.set(false)
                    holding.set(false)
                    if (pendingSsid.get()?.equals(ssid, ignoreCase = true) == true) {
                        pendingSsid.compareAndSet(ssid, null)
                    }
                    if (adoptIfAssociated(ssid, reason = "unavailable")) {
                        fireReady()
                        keepSpecifierHold(ssid)
                        return
                    }
                    // Do not fireReady unbound — dial would hit LTE and time out.
                    Log.w(CarPlayConsts.TAG, "WiFi handoff unavailable — retry Specifier")
                    scheduleHoldingRetry(ssid, generation)
                }

                override fun onLost(network: Network) {
                    val current = boundNetwork.get()
                    if (current != null && current !== network) return
                    Log.w(CarPlayConsts.TAG, "WiFi handoff lost ssid=$ssid — schedule rejoin")
                    holding.set(false)
                    if (boundNetwork.compareAndSet(network, null)) {
                        CarPlayAccessoryNet.clear()
                    }
                    boundSsid.compareAndSet(ssid, null)
                    scheduleRejoin(ssid, generation)
                }
            }
            callbackRef.set(cb)
            callbackIsSpecifier.set(true)
            specifierRequestedAtMs.set(SystemClock.elapsedRealtime())
            cm.requestNetwork(request, cb)
            Log.i(
                CarPlayConsts.TAG,
                "WiFi handoff requested ssid=$ssid ch=${channel ?: cachedChannel.get()} " +
                    "preserveBind=$preserveBind — dial waits for available",
            )
            scheduleAssociationWatch(ssid)
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "WiFi handoff request failed", e)
            callbackRef.set(null)
            callbackIsSpecifier.set(false)
            holding.set(false)
            pendingSsid.set(null)
            fireReady()
        }
    }

    /**
     * Saved STA (INTERNET, mMatchLocalOnlySpecifiers=false) cannot satisfy a
     * local-only specifier. A listen callback observes onLost without owning
     * the association, so unregister does not tear the link down.
     */
    private fun switchToListenHold(ssid: String) {
        if (callbackRef.get() != null && !callbackIsSpecifier.get()) {
            holding.set(true)
            return
        }
        if (callbackIsSpecifier.get()) {
            releaseCallbacksOnly()
        }
        armAssociationListen(ssid)
    }

    @SuppressLint("MissingPermission")
    private fun armAssociationListen(ssid: String) {
        if (callbackRef.get() != null && !callbackIsSpecifier.get()) {
            holding.set(true)
            return
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!networkMatches(ssid, network)) return
                Log.i(CarPlayConsts.TAG, "WiFi handoff listen ssid=$ssid")
                holding.set(true)
                bindNetwork(ssid, network)
                fireReady()
            }

            override fun onLost(network: Network) {
                val current = boundNetwork.get()
                if (current != null && current !== network) return
                Log.w(CarPlayConsts.TAG, "WiFi handoff lost ssid=$ssid — schedule rejoin")
                holding.set(false)
                if (boundNetwork.compareAndSet(network, null)) {
                    CarPlayAccessoryNet.clear()
                }
                boundSsid.compareAndSet(ssid, null)
                scheduleRejoin(ssid, rejoinGeneration.get())
            }
        }
        callbackRef.set(cb)
        callbackIsSpecifier.set(false)
        holding.set(true)
        try {
            cm.registerNetworkCallback(request, cb)
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "WiFi listen callback failed", e)
            callbackRef.compareAndSet(cb, null)
            holding.set(false)
        }
    }

    @Suppress("DEPRECATION")
    private fun ensureWifiRadio() {
        if (wifi?.isWifiEnabled == true) return
        val viaApp = runCatching { wifi?.setWifiEnabled(true) == true }.getOrDefault(false)
        Log.i(
            CarPlayConsts.TAG,
            "WiFi radio off → setWifiEnabled=$viaApp enabled=${wifi?.isWifiEnabled}",
        )
        if (wifi?.isWifiEnabled == true) return
        if (!radioEnableTried.compareAndSet(false, true)) return
        val viaSystem = runCatching {
            CoreApi.setWifiEnabled(true)
            true
        }.getOrElse {
            Log.w(CarPlayConsts.TAG, "WiFi enable via system failed: ${it.message}")
            false
        }
        Log.i(CarPlayConsts.TAG, "WiFi enable via system requested=$viaSystem")
    }

    private fun armAccessoryWifiConfig(ssid: String) {
        runCatching {
            CoreApi.armCarAccessoryWifi(ssid, cachedPassphrase.get())
        }.onFailure {
            Log.w(CarPlayConsts.TAG, "armCarAccessoryWifi failed: ${it.message}")
        }
    }

    private fun scheduleRejoin(ssid: String, generation: Int) {
        if (!wantJoin.get()) return
        mainHandler.postDelayed({
            if (!wantJoin.get()) return@postDelayed
            if (generation != rejoinGeneration.get()) return@postDelayed
            if (adoptIfAssociated(ssid, reason = "rejoin-check")) {
                fireReady()
                ensureHoldingSpecifier(ssid)
                return@postDelayed
            }
            Log.i(CarPlayConsts.TAG, "WiFi handoff rejoin ssid=$ssid after onLost")
            armAccessoryWifiConfig(ssid)
            startSpecifierRequest(
                ssid = ssid,
                passphrase = cachedPassphrase.get(),
                channel = cachedChannel.get(),
                onReady = null,
                preserveBind = false,
            )
        }, REJOIN_DELAY_MS)
    }

    private fun scheduleHoldingRetry(ssid: String, generation: Int) {
        if (!wantJoin.get()) return
        mainHandler.postDelayed({
            if (!wantJoin.get()) return@postDelayed
            if (generation != rejoinGeneration.get()) return@postDelayed
            if (holding.get() && callbackRef.get() != null) return@postDelayed
            ensureHoldingSpecifier(ssid)
        }, HOLDING_RETRY_MS)
    }

    private fun scheduleAssociationWatch(ssid: String) {
        val gen = rejoinGeneration.get()
        mainHandler.postDelayed({
            if (!wantJoin.get()) return@postDelayed
            if (gen != rejoinGeneration.get()) return@postDelayed
            // readyFired alone is not enough: Specifier Network can go stale after
            // the first fireReady, leaving dial with "accessory Wi-Fi Network not bound".
            val boundLive = CarPlayAccessoryNet.hasNetwork() && isBoundTo(ssid)
            if (readyFired.get() && pendingReady.isEmpty() && holding.get() && boundLive) {
                return@postDelayed
            }
            if (isBoundTo(ssid) && holding.get() && !callbackIsSpecifier.get() && boundLive) {
                return@postDelayed
            }
            if (rebindForDial(ssid) || adoptIfAssociated(ssid, reason = "watch")) {
                fireReady()
                keepSpecifierHold(ssid)
                return@postDelayed
            }
            if (shouldRerequestSpecifier(ssid)) {
                Log.w(
                    CarPlayConsts.TAG,
                    "WiFi handoff specifier without Network ssid=$ssid — re-request",
                )
                startSpecifierRequest(
                    ssid = ssid,
                    passphrase = cachedPassphrase.get(),
                    channel = cachedChannel.get(),
                    onReady = null,
                    preserveBind = false,
                )
                return@postDelayed
            }
            scheduleAssociationWatch(ssid)
        }, ASSOCIATION_WATCH_MS)
    }

    /**
     * Specifier callback still registered, but ConnectivityService has no WIFI
     * [Network] to bind. Happens after the agent is dropped while wlan0 stays
     * associated: onAvailable will not fire again on that callback.
     */
    private fun shouldRerequestSpecifier(ssid: String): Boolean {
        if (!callbackIsSpecifier.get() || callbackRef.get() == null) return false
        if (CarPlayAccessoryNet.hasNetwork() && isBoundTo(ssid)) return false
        if (findWifiNetwork(ssid) != null) return false
        val age = SystemClock.elapsedRealtime() - specifierRequestedAtMs.get()
        if (age < SPECIFIER_REREQUEST_MS) return false
        val staUp = isCurrentSsid(ssid) || isCurrentFrequency()
        return staUp || age >= SPECIFIER_GIVE_UP_MS
    }

    /**
     * Refresh [CarPlayAccessoryNet] from a live WIFI [Network] for [ssid].
     * Call before each AirPlay TCP probe — Specifier handles go stale while STA stays up.
     */
    @SuppressLint("MissingPermission")
    fun rebindForDial(ssidRaw: String?): Boolean {
        val ssid = ssidRaw?.trim().orEmpty()
        if (ssid.isEmpty()) return CarPlayAccessoryNet.hasNetwork()
        if (isBoundTo(ssid)) {
            boundNetwork.get()?.let { CarPlayAccessoryNet.set(it) }
            return CarPlayAccessoryNet.hasNetwork()
        }
        val network = findWifiNetwork(ssid) ?: return false
        if (!networkStillUsable(network) && !isCurrentSsid(ssid) && !isCurrentFrequency()) {
            return false
        }
        bindNetwork(ssid, network)
        Log.i(CarPlayConsts.TAG, "WiFi handoff rebind-for-dial ssid=$ssid")
        return CarPlayAccessoryNet.hasNetwork()
    }

    private fun fireReady() {
        // Drain under [readyLock] so a concurrent add cannot fall into the
        // toList/clear window (see [readyLock]). readyFired is set inside the lock:
        // an add that lands after the drain then observes readyFired==true from its
        // own follow-up check and re-fires, so no waiter is lost. Both the
        // first-fire and re-fire paths drained identically — one block.
        val waiters = synchronized(readyLock) {
            readyFired.set(true)
            val w = pendingReady.toList()
            pendingReady.clear()
            w
        }
        // Invoke outside the lock: waiters run the AirPlay handshake (seconds) and
        // must not hold it while CM callback threads queue.
        for (w in waiters) {
            try {
                w()
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "WiFi ready callback failed", e)
            }
        }
    }

    private fun bindNetwork(ssid: String, network: Network) {
        val first = boundNetwork.get() == null ||
            !ssid.equals(boundSsid.get(), ignoreCase = true)
        boundSsid.set(ssid)
        boundNetwork.set(network)
        // Socket-level bind only — process-wide bind pins DNS/default to the
        // no-internet AP and blocks cellular for this process (and confuses OEM
        // "stay on Wi‑Fi" paths). Clear any leftover process bind.
        CarPlayAccessoryNet.set(network)
        try {
            cm.bindProcessToNetwork(null)
        } catch (_: Throwable) {
        }
        holdCellularForInternet()
        // system_server: drop ACCEPT_UNVALIDATED so LTE is default (needs reboot once
        // for new Stub). Retry — OEM may re-stamp accept on NetworkAgent recreate.
        preferCellularNow()
        mainHandler.postDelayed({ preferCellularNow() }, 1_500L)
        mainHandler.postDelayed({ preferCellularNow() }, 4_000L)
        if (first) {
            val mac = staMacForAnnounce()
            Log.i(CarPlayConsts.TAG, "WiFi handoff bound ssid=$ssid staMac=${mac ?: "hidden"}")
            try {
                onBound?.invoke(mac)
            } catch (e: Throwable) {
                Log.w(CarPlayConsts.TAG, "WiFi onBound failed", e)
            }
        }
    }

    private fun preferCellularNow() {
        try {
            CoreApi.preferCellularOverUnvalidatedWifi()
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "preferCellularOverUnvalidatedWifi failed", e)
        }
    }

    /**
     * Keep the cellular NetworkAgent up while we hold a local-only car AP, so
     * system default routing can use mobile data for INTERNET (maps, WeChat, …).
     */
    @SuppressLint("MissingPermission")
    private fun holdCellularForInternet() {
        if (cellularCallbackRef.get() != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(CarPlayConsts.TAG, "cellular hold available for internet while on car Wi‑Fi")
            }

            override fun onLost(network: Network) {
                Log.w(CarPlayConsts.TAG, "cellular hold lost")
            }
        }
        try {
            cm.requestNetwork(request, cb)
            cellularCallbackRef.set(cb)
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "cellular hold request failed", e)
        }
    }

    private fun releaseCellularHold() {
        val cb = cellularCallbackRef.getAndSet(null) ?: return
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Throwable) {
        }
    }

    /** STA MAC for iAP identity. System_server sees the address the AP associated. */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun staMacForAnnounce(): String? {
        val fromSystem = runCatching { CoreApi.localWifiMac() }.getOrNull()
        normalizeMac(fromSystem)?.let { return it }
        return currentStaMac()
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun currentStaMac(): String? {
        val fromInfo = try {
            normalizeMac(wifi?.connectionInfo?.macAddress)
        } catch (_: Throwable) {
            null
        }
        if (fromInfo != null) return fromInfo
        val network = boundNetwork.get() ?: return null
        val caps = try {
            cm.getNetworkCapabilities(network)
        } catch (_: Throwable) {
            null
        } ?: return null
        val transport = caps.transportInfo
        val raw = when (transport) {
            is WifiInfo -> transport.macAddress
            null -> null
            else -> try {
                transport.javaClass.methods.firstOrNull { it.name == "getMacAddress" }
                    ?.invoke(transport) as? String
            } catch (_: Throwable) {
                null
            }
        }
        return normalizeMac(raw)
    }

    private fun normalizeMac(raw: String?): String? {
        val mac = raw?.trim()?.uppercase().orEmpty()
        if (mac.length < 11 || mac == "02:00:00:00:00:00") return null
        return mac
    }

    @SuppressLint("MissingPermission")
    private fun adoptIfAssociated(ssid: String, reason: String): Boolean {
        if (isBoundTo(ssid)) {
            boundNetwork.get()?.let { net ->
                CarPlayAccessoryNet.set(net)
                holdCellularForInternet()
            }
            Log.i(CarPlayConsts.TAG, "WiFi handoff skipped: already bound ssid=$ssid ($reason)")
            return true
        }
        val network = findWifiNetwork(ssid)?.takeIf { networkStillUsable(it) } ?: return false
        bindNetwork(ssid, network)
        Log.i(CarPlayConsts.TAG, "WiFi handoff adopted ssid=$ssid via CM ($reason)")
        return true
    }

    @SuppressLint("MissingPermission")
    private fun networkMatches(ssid: String, network: Network): Boolean {
        val caps = try {
            cm.getNetworkCapabilities(network)
        } catch (_: Throwable) {
            null
        } ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        val seen = readSsid(caps)
        if (seen != null) return seen.equals(ssid, ignoreCase = true)
        val target = channelToMhz(cachedChannel.get())
        val freq = readFrequency(caps)
        if (target != null && freq > 0) return freq == target
        return soleWifiNetwork() === network
    }

    private fun isBoundTo(ssid: String): Boolean {
        val bound = boundSsid.get() ?: return false
        if (!bound.equals(ssid, ignoreCase = true)) return false
        val network = boundNetwork.get() ?: return false
        // Specifier RELEASE / NetworkAgent recycle leaves a non-null handle that
        // bindSocket rejects with EPERM. If STA is still on this SSID, swap in a
        // fresh allNetworks handle instead of clearing — clearing races dial and
        // leaves "already pending" with no AccessoryNet forever.
        if (!networkStillUsable(network)) {
            val fresh = findWifiNetwork(ssid)
            if (fresh != null && fresh !== network) {
                Log.w(
                    CarPlayConsts.TAG,
                    "WiFi handoff stale Network for ssid=$ssid — refresh handle",
                )
                bindNetwork(ssid, fresh)
                return true
            }
            if (isCurrentSsid(ssid) || isCurrentFrequency()) {
                // L2 still up; keep ssid marker, drop only the corpse handle.
                if (boundNetwork.compareAndSet(network, null)) {
                    if (CarPlayAccessoryNet.get() === network) {
                        CarPlayAccessoryNet.clear()
                    }
                }
                Log.w(
                    CarPlayConsts.TAG,
                    "WiFi handoff stale Network for ssid=$ssid — STA still up, await rebind",
                )
                return false
            }
            if (boundNetwork.compareAndSet(network, null)) {
                CarPlayAccessoryNet.clear()
            }
            boundSsid.compareAndSet(ssid, null)
            Log.w(CarPlayConsts.TAG, "WiFi handoff stale Network for ssid=$ssid — cleared")
            return false
        }
        return true
    }

    private fun networkStillUsable(network: Network): Boolean {
        return try {
            if (cm.getNetworkCapabilities(network) != null) return true
            // OEM: Specifier Network can lose capabilities while LinkProperties
            // still names wlan0 — treat that as usable for socket bind refresh.
            cm.getLinkProperties(network)?.interfaceName?.isNotBlank() == true
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun findWifiNetwork(ssid: String): Network? {
        val networks = try {
            cm.allNetworks
        } catch (_: Throwable) {
            return null
        }
        for (network in networks) {
            val caps = try {
                cm.getNetworkCapabilities(network)
            } catch (_: Throwable) {
                null
            } ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val infoSsid = wifiSsidFrom(caps) ?: continue
            if (infoSsid.equals(ssid, ignoreCase = true)) return network
        }
        if (isCurrentSsid(ssid) || isCurrentFrequency()) {
            return activeWifiNetwork()
        }
        val target = channelToMhz(cachedChannel.get())
        if (target != null) {
            for (network in networks) {
                val caps = try {
                    cm.getNetworkCapabilities(network)
                } catch (_: Throwable) {
                    null
                } ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                if (readFrequency(caps) == target) return network
            }
        }
        val current = currentSsid()
        if (current != null && !current.equals(ssid, ignoreCase = true)) return null
        val infoFreq = try {
            wifi?.connectionInfo?.frequency ?: 0
        } catch (_: Throwable) {
            0
        }
        if (target != null && infoFreq > 0 && infoFreq != target) return null
        // SSID (and maybe frequency) redacted for this background FGS. The only
        // STA during handoff is the accessory AP the radio just joined.
        val sole = soleWifiNetwork() ?: return null
        if (infoFreq <= 0 || target == null || infoFreq == target) {
            Log.i(
                CarPlayConsts.TAG,
                "WiFi handoff SSID redacted; using sole STA ssid=$ssid freq=$infoFreq",
            )
            return sole
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun soleWifiNetwork(): Network? {
        val wifiNets = try {
            cm.allNetworks
        } catch (_: Throwable) {
            return null
        }.filter { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        return wifiNets.singleOrNull()
    }

    private fun activeWifiNetwork(): Network? {
        return cm.activeNetwork?.takeIf { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: soleWifiNetwork()
    }

    private fun wifiSsidFrom(caps: NetworkCapabilities): String? = readSsid(caps)

    private fun readSsid(caps: NetworkCapabilities): String? {
        val transport = caps.transportInfo
        if (transport is WifiInfo) {
            return normalizeSsid(transport.ssid)
        }
        if (transport == null) return null
        val raw = try {
            transport.javaClass.methods
                .firstOrNull { it.name == "getSSID" || it.name == "getSsid" || it.name == "getWifiSsid" }
                ?.invoke(transport)
                ?.toString()
        } catch (_: Throwable) {
            null
        }
        return normalizeSsid(raw)
    }

    private fun readFrequency(caps: NetworkCapabilities): Int {
        val transport = caps.transportInfo
        if (transport is WifiInfo && transport.frequency > 0) return transport.frequency
        if (transport == null) return 0
        return try {
            (transport.javaClass.methods.firstOrNull { it.name == "getFrequency" }
                ?.invoke(transport) as? Int) ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    @SuppressLint("MissingPermission")
    private fun isCurrentFrequency(): Boolean {
        val target = channelToMhz(cachedChannel.get()) ?: return false
        val freq = try {
            wifi?.connectionInfo?.frequency ?: 0
        } catch (_: Throwable) {
            0
        }
        return freq == target
    }

    private fun channelToMhz(channel: Int?): Int? {
        if (channel == null || channel <= 0) return null
        return when (channel) {
            in 1..14 -> 2407 + channel * 5
            in 32..196 -> 5000 + channel * 5
            else -> null
        }
    }

    @SuppressLint("MissingPermission")
    private fun isCurrentSsid(ssid: String): Boolean {
        val current = currentSsid() ?: return false
        return current.equals(ssid, ignoreCase = true)
    }

    @SuppressLint("MissingPermission")
    private fun currentSsid(): String? = try {
        normalizeSsid(wifi?.connectionInfo?.ssid)
    } catch (_: Throwable) {
        null
    }

    private fun normalizeSsid(raw: String?): String? {
        val s = raw?.trim()?.trim('"').orEmpty()
        if (s.isEmpty() || s == "<unknown ssid>") return null
        return s
    }

    private fun releaseCallbacksOnly() {
        val cb = callbackRef.getAndSet(null) ?: return
        holding.set(false)
        callbackIsSpecifier.set(false)
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Throwable) {
        }
    }

    /**
     * IPv4 of the accessory AP (DHCP server / default gateway on the joined Wi‑Fi).
     * Used when StartSession only publishes a link-local `fe80::` address.
     */
    @SuppressLint("MissingPermission")
    fun accessoryIpv4Hint(ssid: String?): String? {
        val lp = linkPropertiesFor(ssid) ?: return null
        lp.dhcpServerAddress?.hostAddress?.let { return it }
        for (route in lp.routes) {
            if (!route.isDefaultRoute) continue
            val gw = route.gateway?.hostAddress ?: continue
            if (gw.contains(':')) continue
            return gw
        }
        return null
    }

    /** Our STA IPv4 on the accessory AP — used to /24-filter `_airplay._tcp` results. */
    @SuppressLint("MissingPermission")
    fun localIpv4OnAccessory(ssid: String?): String? {
        val lp = linkPropertiesFor(ssid) ?: return null
        for (link in lp.linkAddresses) {
            val addr = link.address?.hostAddress ?: continue
            if (addr.contains(':')) continue
            return addr
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun linkPropertiesFor(ssid: String?): android.net.LinkProperties? {
        val target = ssid?.trim().orEmpty()
        val network = when {
            target.isNotEmpty() && isBoundTo(target) -> boundNetwork.get()
            target.isNotEmpty() -> findWifiNetwork(target)
            else -> boundNetwork.get()
        } ?: return null
        return try {
            cm.getLinkProperties(network)
        } catch (_: Throwable) {
            null
        }
    }

    fun release() {
        wantJoin.set(false)
        rejoinGeneration.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        releaseCallbacksOnly()
        releaseCellularHold()
        synchronized(readyLock) { pendingReady.clear() }
        pendingSsid.set(null)
        boundSsid.set(null)
        boundNetwork.set(null)
        CarPlayAccessoryNet.clear()
        readyFired.set(false)
        holding.set(false)
        try {
            cm.bindProcessToNetwork(null)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val REJOIN_DELAY_MS = 400L

        /**
         * Pick a dialable address from the `0x4301` list, preferring non-link-local
         * (global IPv6 / IPv4) over `fe80:`.
         */
        fun pickDialHost(addresses: List<String>): String? {
            val nonLl = addresses.firstOrNull {
                it.isNotBlank() && !it.startsWith("fe80:", ignoreCase = true)
            }
            if (nonLl != null) return nonLl
            return addresses.firstOrNull { it.isNotBlank() }
        }
        private const val ASSOCIATION_WATCH_MS = 1_200L
        private const val HOLDING_RETRY_MS = 1_500L
        /** Min gap between specifier re-requests once the agent is already gone. */
        /** Min gap between specifier re-requests once the agent is already gone. */
        private const val SPECIFIER_REREQUEST_MS = 2_500L
        /** No STA and no Network: let a scan finish before tearing the request. */
        private const val SPECIFIER_GIVE_UP_MS = 8_000L
    }
}
