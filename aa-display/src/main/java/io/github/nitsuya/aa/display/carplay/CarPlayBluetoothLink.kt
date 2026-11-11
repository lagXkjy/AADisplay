package io.github.nitsuya.aa.display.carplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.carplay.profile.CarPlayDialPolicy
import io.github.nitsuya.aa.display.carplay.profile.CarPlayProfileResolver
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * RFCOMM transport for Source-role iAP2 against a HU / diplay receiver.
 *
 * - Listen on **phone** UUID (`…cafe`, "CarPlay Phone") — DiPlay dials this on reconnect.
 * - Listen on **accessory** UUID (`…caff`, "CarPlay Accessory"). Some HUs dial `…cafe` and reject outbound.
 * - Listen on the wireless-CarPlay capability UUID so classic EIR/SDP advertises it
 *   (VAG btstack will not offer CarPlay without that UUID).
 * - Dial bonded peers on `…caff`. Inbound-preferred HUs get delayed fallback after bond/ACL
 *   (cafe first, SDP refresh + secure/insecure, limited retries); never dial during
 *   BOND_BONDING or while a peer is already up.
 *
 * HU dial policy comes from [CarPlayProfileResolver] — not hardcoded vendor substrings.
 * Adapter name is left alone; RFCOMM UUIDs / iAP identity matter, not EIR rename.
 *
 * After reboot, [CarPlayProjectionService] often starts before the adapter is ON. Previously
 * that path logged "deferred" and permanently gave up — DiPlay SDP then returns `scn=0`.
 * We now watch [BluetoothAdapter.ACTION_STATE_CHANGED] and arm listen when STATE_ON arrives.
 */
class CarPlayBluetoothLink(
    context: Context,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onBytes: (ByteArray) -> Unit,
    /** True once iAP2 link sync has completed on the current socket. */
    private val sessionLive: () -> Boolean = { false },
    private val onPeer: (name: String?, address: String?) -> Unit = { _, _ -> },
    /**
     * After wired AAP → CarPlay, the HU will not dial …cafe. Shorten the cafe wait
     * and allow HFP soft-bounce between …caff retries. Also engaged when the peer
     * ACL is already up at arm time.
     */
    private val forceOutbound: Boolean = false,
) {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val running = AtomicBoolean(false)
    private val rfcommArmed = AtomicBoolean(false)
    private val serverSockets = mutableListOf<BluetoothServerSocket>()
    @Volatile
    private var clientSocket: BluetoothSocket? = null
    /** True when the current [clientSocket] was accepted (…cafe), not dialed (…caff). */
    @Volatile
    private var peerInbound = false
    private val writeLock = Any()
    private val watchLock = Any()
    private val dialLock = Any()
    private val listenAlive = AtomicInteger(0)
    private var peerGeneration = 0
    private var btStateReceiver: BroadcastReceiver? = null
    private var peerReceiver: BroadcastReceiver? = null
    private var pairingReceiver: BroadcastReceiver? = null
    /** One accessory-uuid dial after the head unit links, if it does not dial us. */
    @Volatile private var headUnitDialPending = false
    /** Fresh bond. An ACL blip in this window is profile setup, not a deleted key. */
    @Volatile private var headUnitBondedAtMs = 0L
    private val headUnitDialGeneration = AtomicInteger(0)
    private val discoverableRequested = AtomicBoolean(false)

    private val phoneUuid: UUID = UUID.fromString(CarPlayConsts.IAP2_PHONE_RFCOMM_UUID)
    private val accessoryUuid: UUID = UUID.fromString(CarPlayConsts.IAP2_ACCESSORY_RFCOMM_UUID)

    /**
     * After the HU deletes the phone, inquiry only sees a discoverable adapter.
     * Connectable-only keeps the old bond reconnecting and the new pair times out.
     *
     * Prefer [CoreApi.armBluetoothDiscoverable] (system_server has
     * BLUETOOTH_PRIVILEGED). App-uid [BluetoothAdapter.setScanMode] fails; the
     * 300s [BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE] dialog is the last fallback.
     */
    /** Null when [BLUETOOTH_SCAN] is denied — never throw into the FGS start path. */
    @SuppressLint("MissingPermission")
    private fun readScanMode(bt: BluetoothAdapter): Int? {
        return try {
            bt.scanMode
        } catch (e: SecurityException) {
            Log.w(CarPlayConsts.TAG, "BT scanMode denied (need BLUETOOTH_SCAN): ${e.message}")
            null
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "BT scanMode failed: ${e.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun armDiscoverable(bt: BluetoothAdapter) {
        val enabled = try {
            bt.isEnabled
        } catch (e: SecurityException) {
            Log.w(CarPlayConsts.TAG, "BT isEnabled denied (need BLUETOOTH_CONNECT): ${e.message}")
            return
        }
        if (!enabled) return
        val mode = readScanMode(bt) ?: return
        if (mode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) return
        runCatching { CoreApi.armBluetoothDiscoverable() }
        val afterPrivileged = readScanMode(bt)
        if (afterPrivileged == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            Log.i(CarPlayConsts.TAG, "BT discoverable ok=true via=system_server scanMode=$afterPrivileged")
            return
        }
        val ok = try {
            val method = bt.javaClass.getMethod("setScanMode", Int::class.javaPrimitiveType)
            method.invoke(bt, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) as? Boolean ?: false
        } catch (e: Throwable) {
            if (!discoverableRequested.get()) {
                Log.w(CarPlayConsts.TAG, "BT setScanMode discoverable failed: ${e.cause?.message ?: e.message}")
            }
            false
        }
        val after = readScanMode(bt)
        if (ok || after == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            Log.i(CarPlayConsts.TAG, "BT discoverable ok=$ok scanMode=$after")
            return
        }
        // Dial loop calls this often — show the consent dialog at most once per arm cycle.
        if (!discoverableRequested.compareAndSet(false, true)) return
        Log.i(CarPlayConsts.TAG, "BT discoverable ok=false scanMode=$after")
        showDiscoverableDialog()
    }

    /**
     * Status-page / reconnect: always re-prompt the 300s discoverable system dialog
     * so the HU can find the phone without opening Bluetooth Settings.
     */
    fun requestDiscoverableDialog() {
        discoverableRequested.set(false)
        showDiscoverableDialog()
        discoverableRequested.set(true)
    }

    private fun showDiscoverableDialog() {
        try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            Log.i(CarPlayConsts.TAG, "BT discoverable request dialog shown (300s)")
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "BT discoverable request failed", e)
        }
    }

    /**
     * Public BT MAC the head unit already has on the ACL.
     * Read in system_server; the app process only sees 02:00:00:00:00:00.
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun localAddress(): String {
        usableMac(runCatching { CoreApi.localBluetoothMac() }.getOrNull())?.let { return it }
        val fromAdapter = try {
            adapter?.address
        } catch (_: Throwable) {
            null
        }
        usableMac(fromAdapter)?.let { return it }
        val fromSettings = try {
            Settings.Secure.getString(appContext.contentResolver, "bluetooth_address")
        } catch (_: Throwable) {
            null
        }
        usableMac(fromSettings)?.let { return it }
        Log.w(CarPlayConsts.TAG, "BT MAC unavailable")
        return ""
    }

    private fun usableMac(raw: String?): String? {
        val mac = raw?.trim()?.uppercase().orEmpty()
        if (mac.length < 11 || mac == "02:00:00:00:00:00" || mac == "00:00:00:00:00:00") return null
        return mac
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        rfcommArmed.set(false)
        val bt = adapter
        if (bt == null) {
            Log.w(CarPlayConsts.TAG, "Bluetooth adapter missing; iAP RFCOMM unavailable")
            running.set(false)
            return
        }
        val enabled = try {
            bt.isEnabled
        } catch (e: SecurityException) {
            Log.e(
                CarPlayConsts.TAG,
                "BT permission missing — RFCOMM deferred (grant BLUETOOTH_CONNECT/SCAN)",
                e,
            )
            running.set(false)
            return
        }
        if (!enabled) {
            Log.w(CarPlayConsts.TAG, "Bluetooth off; iAP RFCOMM waiting for STATE_ON")
            armBtStateWatch()
            return
        }
        try {
            beginRfcomm(bt)
        } catch (e: SecurityException) {
            Log.e(CarPlayConsts.TAG, "BT RFCOMM arm denied — grant wireless CarPlay permissions", e)
            rfcommArmed.set(false)
            running.set(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginRfcomm(bt: BluetoothAdapter) {
        if (!running.get()) return
        if (!rfcommArmed.compareAndSet(false, true)) return
        disarmBtStateWatch()
        armDiscoverable(bt)
        armPairingWatch()
        armPeerWatch()
        val records = listenRecords()
        listenAlive.set(records.size)
        for ((name, uuid) in records) {
            thread(name = "carplay-rfcomm-$name", isDaemon = true) {
                try {
                    listen(bt, name, uuid)
                } catch (e: SecurityException) {
                    Log.e(CarPlayConsts.TAG, "RFCOMM listen denied uuid=$name", e)
                } finally {
                    if (listenAlive.decrementAndGet() == 0 && running.get()) {
                        rfcommArmed.set(false)
                        Log.w(CarPlayConsts.TAG, "RFCOMM listen ended; waiting for Bluetooth again")
                        armBtStateWatch()
                    }
                }
            }
        }
        thread(name = "carplay-rfcomm-dial", isDaemon = true) {
            // Give listen a moment to register SDP before the first sweep.
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
                return@thread
            }
            // Keep HFP. Do not start A2DP: some HUs time that out and show
            // "cannot establish Bluetooth" instead of dialing iAP.
            // One-shot: the phone still held a key after the HU deleted the device.
            try {
                val cleared = clearLeftoverHeadUnitBond(bt)
                if (!cleared) {
                    // Pre-register bonded CarPlay peers so a later PAIRING_REQUEST is
                    // confirmed in system_server before Settings can pop.
                    prepareBondedCarPlayPeers(bt)
                    restoreHeadUnitAudio(bt)
                }
                // Inbound-preferred HUs (Audi): do NOT arm a continuous …caff dial here.
                // Cafe listen is the primary path; one-shot fallback after bond/ACL (and
                // once at start if already bonded — force-stop does not re-fire ACL).
                // Arming pending at start made dialBonded hit every 4s, racing cafe accept
                // and cancelling SSP pairing.
                bondedInboundPreferred(bt)?.let { scheduleHeadUnitDial(it) }
            } catch (e: SecurityException) {
                Log.e(CarPlayConsts.TAG, "BT dial prep denied", e)
            }
            while (running.get() && rfcommArmed.get()) {
                try {
                    armDiscoverable(bt)
                    // Only dial when there is no RFCOMM peer. A connected socket mid-negotiate
                    // (!sessionLive) must not be redialed — createRfcommSocket races the pump
                    // and leaves Audi silent after our first SYN.
                    if (clientSocket?.isConnected != true) {
                        dialBonded(bt)
                    }
                } catch (e: SecurityException) {
                    Log.e(CarPlayConsts.TAG, "BT dial loop denied — stopping RFCOMM", e)
                    rfcommArmed.set(false)
                    return@thread
                }
                try {
                    Thread.sleep(DIAL_RETRY_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    private fun listenRecords(): List<Pair<String, UUID>> = listOf(
        "cafe" to phoneUuid,
        "caff" to accessoryUuid,
        "wcp" to UUID.fromString(CarPlayConsts.WIRELESS_CARPLAY_CAPABILITY_UUID),
    )

    private fun armBtStateWatch() {
        synchronized(watchLock) {
            if (btStateReceiver != null || !running.get()) return
            val rx = object : BroadcastReceiver() {
                @SuppressLint("MissingPermission")
                override fun onReceive(context: Context, intent: Intent?) {
                    if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                    if (!running.get()) return
                    val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR,
                    )
                    if (state != BluetoothAdapter.STATE_ON) return
                    val bt = adapter ?: return
                    if (!bt.isEnabled) return
                    Log.i(CarPlayConsts.TAG, "Bluetooth STATE_ON → arm RFCOMM …cafe listen")
                    beginRfcomm(bt)
                }
            }
            btStateReceiver = rx
            try {
                appContext.registerReceiver(
                    rx,
                    IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED,
                )
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "BT state watch register failed", e)
                btStateReceiver = null
                running.set(false)
            }
        }
        // Race: adapter may have turned on between isEnabled check and registerReceiver.
        val bt = adapter
        if (bt != null && bt.isEnabled && running.get() && !rfcommArmed.get()) {
            Log.i(CarPlayConsts.TAG, "Bluetooth already ON after watch arm → RFCOMM now")
            beginRfcomm(bt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun armPeerWatch() {
        synchronized(watchLock) {
            if (peerReceiver != null || !running.get()) return
            val rx = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent?) {
                    if (!running.get()) return
                    val action = intent?.action ?: return
                    val device = deviceExtra(intent) ?: return
                    when (action) {
                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                            val bond = intent.getIntExtra(
                                BluetoothDevice.EXTRA_BOND_STATE,
                                BluetoothDevice.BOND_NONE,
                            )
                            if (isInboundPreferredPeer(device) &&
                                bond == BluetoothDevice.BOND_BONDING
                            ) {
                                // Cancel any in-flight …caff dial — dial during SSP
                                // drops the ACL and the car shows "pairing no reaction".
                                headUnitDialGeneration.incrementAndGet()
                                headUnitDialPending = false
                                prepareHeadUnit(device.address)
                            }
                            if (bond != BluetoothDevice.BOND_BONDED) {
                                if (bond == BluetoothDevice.BOND_NONE &&
                                    isInboundPreferredPeer(device)
                                ) {
                                    adapter?.let { armDiscoverable(it) }
                                }
                                return
                            }
                            if (isInboundPreferredPeer(device)) {
                                headUnitBondedAtMs = SystemClock.elapsedRealtime()
                                prepareHeadUnit(device.address)
                                // One delayed …caff fallback if the HU never dials …cafe.
                                scheduleHeadUnitDial(device)
                            }
                            Log.i(
                                CarPlayConsts.TAG,
                                "RFCOMM bond complete ${device.address} name=${device.name}",
                            )
                            return
                        }
                        BluetoothDevice.ACTION_ACL_CONNECTED -> {
                            if (isInboundPreferredPeer(device)) {
                                prepareHeadUnit(device.address)
                                // Fresh pairing ACL arrives before BOND_BONDED — bond
                                // handler arms the first cafe-wait dial. Re-arm only for
                                // a later ACL (HU CarPlay retry) once the bond is settled.
                                if (device.bondState == BluetoothDevice.BOND_BONDED &&
                                    SystemClock.elapsedRealtime() - headUnitBondedAtMs >
                                    INBOUND_DIAL_FALLBACK_MS
                                ) {
                                    scheduleHeadUnitDial(device)
                                }
                            }
                            Log.i(
                                CarPlayConsts.TAG,
                                "RFCOMM ACL connected ${device.address} name=${device.name}",
                            )
                            return
                        }
                        BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                            if (isInboundPreferredPeer(device)) scheduleStaleBondDrop(device)
                            return
                        }
                        else -> return
                    }
                    if (!shouldOutboundDial(device)) {
                        Log.i(
                            CarPlayConsts.TAG,
                            "RFCOMM $action ${device.address} name=${device.name} — wait for inbound",
                        )
                        return
                    }
                    val target = device
                    thread(name = "carplay-rfcomm-redial", isDaemon = true) {
                        dialDevice(target)
                    }
                }
            }
            peerReceiver = rx
            try {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                }
                appContext.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "BT peer watch register failed", e)
                peerReceiver = null
            }
        }
    }

    private fun deviceExtra(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun disarmPeerWatch() {
        synchronized(watchLock) {
            val rx = peerReceiver ?: return
            peerReceiver = null
            try {
                appContext.unregisterReceiver(rx)
            } catch (_: Throwable) {
            }
        }
    }

    private fun disarmBtStateWatch() {
        synchronized(watchLock) {
            val rx = btStateReceiver ?: return
            btStateReceiver = null
            try {
                appContext.unregisterReceiver(rx)
            } catch (_: Throwable) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun dialBonded(bt: BluetoothAdapter) {
        val bonded = try {
            bt.bondedDevices?.toList().orEmpty()
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "bondedDevices unavailable", e)
            emptyList()
        }
        if (bonded.isEmpty()) {
            Log.i(CarPlayConsts.TAG, "RFCOMM dial: no bonded peers; rely on listen (…cafe)")
            return
        }
        val ordered = bonded.filter { shouldOutboundDial(it) }.sortedByDescending { dialScore(it) }
        if (ordered.isEmpty()) return
        Log.i(
            CarPlayConsts.TAG,
            "RFCOMM dial: trying ${ordered.size} bonded peer(s) on accessory uuid " +
                CarPlayConsts.IAP2_ACCESSORY_RFCOMM_UUID +
                " order=${ordered.joinToString { "${it.address}/${it.name}" }}",
        )
        for (device in ordered) {
            if (!running.get()) return
            if (clientSocket?.isConnected == true && sessionLive()) return
            val inboundPreferred = isInboundPreferredPeer(device)
            val socket = synchronized(dialLock) { openDialSocket(device) }
            if (socket == null) {
                if (inboundPreferred) headUnitDialPending = false
                continue
            }
            if (inboundPreferred) headUnitDialPending = false
            attachPeer(socket, inbound = false, via = "dial")
            return
        }
    }

    @SuppressLint("MissingPermission")
    private fun isInboundPreferredPeer(device: BluetoothDevice): Boolean {
        return CarPlayProfileResolver.isInboundPreferredPeer(device.name)
    }

    /**
     * Generic receivers are dialed on `…caff`. Inbound-preferred HUs normally dial our
     * `…cafe` listener; one outbound attempt is armed after the bond completes.
     * Dialing during BOND_BONDING drops the ACL and cancels the system pairing dialog.
     *
     * [headUnitDialPending] is set only after the 2.5s cafe wait — the 4s [dialBonded]
     * loop must not see it during the window (Audi 4026: pending=true at bond made
     * `…caff` fire in ~1s, SYN unanswered, cafe blocked).
     */
    @SuppressLint("MissingPermission")
    private fun shouldOutboundDial(device: BluetoothDevice): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDING) return false
        val profile = CarPlayProfileResolver.matchName(device.name)
        if (profile?.dialPolicy == CarPlayDialPolicy.INBOUND_PREFERRED) {
            return headUnitDialPending && device.bondState == BluetoothDevice.BOND_BONDED
        }
        return dialScore(device) > 0
    }

    private fun prepareHeadUnit(address: String) {
        runCatching { CoreApi.prepareCarPlayHeadUnit(address) }
            .onFailure { Log.w(CarPlayConsts.TAG, "prepareHeadUnit failed", it) }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleHeadUnitDial(device: BluetoothDevice, attempt: Int = 0) {
        if (!isInboundPreferredPeer(device)) return
        if (device.bondState == BluetoothDevice.BOND_BONDING) return
        val aggressive = forceOutbound || isDeviceConnected(device)
        val gen = headUnitDialGeneration.incrementAndGet()
        thread(name = "carplay-hu-dial", isDaemon = true) {
            val waitMs = when {
                attempt == 0 && aggressive -> FORCE_OUTBOUND_FIRST_WAIT_MS
                attempt == 0 -> INBOUND_DIAL_FALLBACK_MS
                else -> INBOUND_DIAL_RETRY_MS
            }
            try {
                // Cafe-first for cold inbound; post-AAP / ACL-live uses a short wait.
                Thread.sleep(waitMs)
            } catch (_: InterruptedException) {
                return@thread
            }
            if (gen != headUnitDialGeneration.get() || !running.get()) return@thread
            if (clientSocket?.isConnected == true || sessionLive()) {
                Log.i(
                    CarPlayConsts.TAG,
                    "RFCOMM …caff fallback skipped; peer already up ${device.address}",
                )
                return@thread
            }
            if (device.bondState != BluetoothDevice.BOND_BONDED) return@thread
            if (attempt >= 1 && aggressive) {
                softBounceHeadset(device)
                if (gen != headUnitDialGeneration.get() || !running.get()) return@thread
                if (clientSocket?.isConnected == true || sessionLive()) return@thread
            }
            // Arm pending only now so the 4s dialBonded loop cannot fire during the wait.
            headUnitDialPending = true
            var dialed = false
            try {
                if (!shouldOutboundDial(device)) return@thread
                Log.i(
                    CarPlayConsts.TAG,
                    "RFCOMM …caff fallback dial ${device.address} name=${device.name} " +
                        "attempt=${attempt + 1}/${INBOUND_DIAL_MAX_ATTEMPTS} " +
                        "aggressive=$aggressive",
                )
                dialDevice(device)
                dialed = clientSocket?.isConnected == true
            } finally {
                // Do not leave pending for the 4s dialBonded loop.
                headUnitDialPending = false
            }
            if (dialed || !running.get()) return@thread
            if (clientSocket?.isConnected == true || sessionLive()) return@thread
            if (attempt + 1 >= INBOUND_DIAL_MAX_ATTEMPTS) {
                Log.w(
                    CarPlayConsts.TAG,
                    "RFCOMM …caff fallback exhausted for ${device.address} — wait for …cafe",
                )
                return@thread
            }
            // Stale SDP / wrong SCN (seen as uuid=0x1101) often clears on a later query.
            scheduleHeadUnitDial(device, attempt + 1)
        }
    }

    /**
     * Disconnect→reconnect HFP only (no bond delete). Nudges the HU to refresh
     * SDP / CarPlay discovery after wired AA without racing cafe accept on attempt 0.
     */
    @SuppressLint("MissingPermission")
    private fun softBounceHeadset(device: BluetoothDevice) {
        val bt = adapter ?: return
        if (device.bondState != BluetoothDevice.BOND_BONDED) return
        Log.i(CarPlayConsts.TAG, "HFP soft-bounce ${device.address} name=${device.name}")
        val done = CountDownLatch(1)
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                try {
                    val off = disconnectProfile(proxy, device)
                    Log.i(CarPlayConsts.TAG, "HFP soft-bounce disconnect ok=$off")
                    try {
                        Thread.sleep(HFP_BOUNCE_GAP_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    val on = connectProfile(proxy, device)
                    Log.i(CarPlayConsts.TAG, "HFP soft-bounce reconnect ok=$on")
                } finally {
                    bt.closeProfileProxy(profile, proxy)
                    done.countDown()
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }
        if (!bt.getProfileProxy(appContext, listener, BluetoothProfile.HEADSET)) {
            Log.w(CarPlayConsts.TAG, "HFP soft-bounce getProfileProxy failed")
            return
        }
        try {
            if (!done.await(HFP_BOUNCE_AWAIT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(CarPlayConsts.TAG, "HFP soft-bounce timed out")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Close a negotiating socket so the next `…cafe` accept is not treated as a
     * live session. Outbound `…caff` with no SYN/ACK is always closed. Inbound is
     * kept unless [allowInbound]: a stuck HU-dialed `…cafe` otherwise stays
     * "live" and the next connect is dropped. [stillNegotiating] is re-checked
     * under [writeLock] so a link that just became ready is not torn down.
     */
    fun dropCurrentPeer(
        reason: String,
        allowInbound: Boolean = false,
        stillNegotiating: () -> Boolean = { true },
    ) {
        var inbound = false
        val socket = synchronized(writeLock) {
            if (peerInbound && !allowInbound) {
                Log.i(CarPlayConsts.TAG, "RFCOMM drop skipped ($reason) — inbound peer")
                return
            }
            if (!stillNegotiating()) {
                Log.i(CarPlayConsts.TAG, "RFCOMM drop skipped ($reason) — link ready")
                return
            }
            inbound = peerInbound
            val current = clientSocket
            clientSocket = null
            current
        } ?: return
        Log.i(CarPlayConsts.TAG, "RFCOMM drop peer ($reason) inbound=$inbound")
        socket.closeQuietly()
    }

    /** Keep HFP so the HU still has a phone link. A2DP is not part of CarPlay. */
    @SuppressLint("MissingPermission")
    private fun restoreHeadUnitAudio(bt: BluetoothAdapter) {
        val targets = try {
            bt.bondedDevices?.filter { isInboundPreferredPeer(it) }.orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
        if (targets.isEmpty()) return
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                for (device in targets) {
                    val ok = connectProfile(proxy, device)
                    Log.i(
                        CarPlayConsts.TAG,
                        "BT profile=$profile connect ${device.address} ok=$ok",
                    )
                }
                bt.closeProfileProxy(profile, proxy)
            }

            override fun onServiceDisconnected(profile: Int) {}
        }
        bt.getProfileProxy(appContext, listener, BluetoothProfile.HEADSET)
    }

    @SuppressLint("MissingPermission")
    private fun bondedInboundPreferred(bt: BluetoothAdapter): BluetoothDevice? {
        return try {
            bt.bondedDevices?.firstOrNull { isInboundPreferredPeer(it) }
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun prepareBondedCarPlayPeers(bt: BluetoothAdapter) {
        val bonded = try {
            bt.bondedDevices?.toList().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
        for (device in bonded) {
            if (isInboundPreferredPeer(device) || dialScore(device) > 0) {
                prepareHeadUnit(device.address)
            }
        }
    }

    /**
     * HU already deleted this phone, but the adapter still has the old link key.
     * A new pair then times out. Clear that key once; later deletes are handled
     * when the ACL actually drops.
     */
    @SuppressLint("MissingPermission")
    private fun clearLeftoverHeadUnitBond(bt: BluetoothAdapter): Boolean {
        val marker = java.io.File(appContext.filesDir, "aadisplay_bt_repair_v1")
        if (marker.exists()) return false
        val hu = bondedInboundPreferred(bt) ?: return false
        Log.i(
            CarPlayConsts.TAG,
            "BT clear leftover HU bond ${hu.address} so a deleted peer can pair",
        )
        removeBond(hu)
        try {
            marker.writeText(hu.address)
        } catch (_: Throwable) {
        }
        return true
    }

    /**
     * After ACL loss: stay discoverable so the HU can come back.
     *
     * Do **not** [removeBond] here. Force-stop / APK install / Wi‑Fi handoff all drop
     * ACL briefly while the session is not yet live — deleting the HU bond left the
     * phone with no Audi entry and "连不上" until a full re-pair. One-shot leftover
     * repair stays in [clearLeftoverHeadUnitBond] only.
     */
    private fun scheduleStaleBondDrop(device: BluetoothDevice) {
        if (sessionLive()) return
        thread(name = "carplay-bt-stale-bond", isDaemon = true) {
            try {
                Thread.sleep(3_000)
            } catch (_: InterruptedException) {
                return@thread
            }
            if (!running.get() || sessionLive()) return@thread
            if (isDeviceConnected(device)) return@thread
            Log.i(
                CarPlayConsts.TAG,
                "BT ACL loss ${device.address} name=${device.name} " +
                    "bond=${device.bondState} — keep bond, arm discoverable",
            )
            adapter?.let { armDiscoverable(it) }
        }
    }

    /**
     * App uid cannot call [BluetoothDevice.setPairingConfirmation]. Forward known HU
     * pairing to system_server via [prepareHeadUnit] (confirms + rejects PBAP/MAP), then
     * abort so Settings never pops. Already-bonded peers must keep their link key —
     * never [removeBond] here (that forced a full re-pair on every reconnect).
     */
    private fun armPairingWatch() {
        synchronized(watchLock) {
            if (pairingReceiver != null || !running.get()) return
            val rx = object : BroadcastReceiver() {
                @SuppressLint("MissingPermission")
                override fun onReceive(context: Context, intent: Intent?) {
                    if (intent?.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return
                    val device = deviceExtra(intent) ?: return
                    val variant = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.ERROR,
                    )
                    val bonded = device.bondState == BluetoothDevice.BOND_BONDED
                    // First Audi/MMI pair: name hint while BONDING. Reconnect of a
                    // bonded CarPlay peer: keep the key and confirm quietly.
                    // Unrelated bonded devices (headsets) stay on the system dialog.
                    val knownHu = isInboundPreferredPeer(device) ||
                        (bonded && dialScore(device) > 0)
                    Log.i(
                        CarPlayConsts.TAG,
                        "BT pairing request ${device.address} name=${device.name} " +
                            "variant=$variant bond=${device.bondState} knownHu=$knownHu",
                    )
                    if (!knownHu) {
                        Log.i(
                            CarPlayConsts.TAG,
                            "BT pairing left to system dialog ${device.address}",
                        )
                        return
                    }
                    // system_server confirms (privileged) and remembers the MAC for
                    // later ACL / access broadcasts. Do not delete an existing bond.
                    prepareHeadUnit(device.address)
                    try {
                        abortBroadcast()
                    } catch (_: Throwable) {
                    }
                    Log.i(
                        CarPlayConsts.TAG,
                        "BT pairing handed to system_server ${device.address} " +
                            "(bonded=$bonded)",
                    )
                }
            }
            pairingReceiver = rx
            try {
                val filter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
                    priority = IntentFilter.SYSTEM_HIGH_PRIORITY
                }
                appContext.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "BT pairing watch register failed", e)
                pairingReceiver = null
            }
        }
    }

    private fun disarmPairingWatch() {
        synchronized(watchLock) {
            val rx = pairingReceiver ?: return
            pairingReceiver = null
            try {
                appContext.unregisterReceiver(rx)
            } catch (_: Throwable) {
            }
        }
    }

    private fun removeBond(device: BluetoothDevice) {
        try {
            val ok = device.javaClass.getMethod("removeBond").invoke(device) as? Boolean
            Log.i(CarPlayConsts.TAG, "BT removeBond ${device.address} ok=$ok")
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "BT removeBond failed", e)
        }
    }

    private fun isDeviceConnected(device: BluetoothDevice): Boolean {
        return try {
            device.javaClass.getMethod("isConnected").invoke(device) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun connectProfile(proxy: BluetoothProfile, device: BluetoothDevice): Boolean {
        return try {
            val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
            method.invoke(proxy, device) as? Boolean ?: false
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "BT profile connect failed", e)
            false
        }
    }

    private fun disconnectProfile(proxy: BluetoothProfile, device: BluetoothDevice): Boolean {
        return try {
            val method = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
            method.invoke(proxy, device) as? Boolean ?: false
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "BT profile disconnect failed", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun dialScore(device: BluetoothDevice): Int {
        var score = 0
        val wirelessUuid = UUID.fromString(CarPlayConsts.WIRELESS_CARPLAY_CAPABILITY_UUID)
        val uuids = try {
            device.uuids
        } catch (_: Throwable) {
            null
        }
        if (uuids?.any { it.uuid == accessoryUuid } == true) score += 2
        if (uuids?.any { it.uuid == wirelessUuid } == true) score += 1
        if (CarPlayProfileResolver.matchName(device.name) != null) score += 1
        return score
    }

    @SuppressLint("MissingPermission")
    private fun dialDevice(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDING) {
            Log.i(
                CarPlayConsts.TAG,
                "RFCOMM dial skipped; bonding in progress ${device.address}",
            )
            return
        }
        val socket = synchronized(dialLock) { openDialSocket(device) } ?: return
        attachPeer(socket, inbound = false, via = "dial")
    }

    /**
     * Connect a dial socket. Caller holds [dialLock]. Does not pump; the caller attaches.
     * An iAP-live session is left alone. An idle socket is closed so Audi can take the link.
     *
     * Audi MIB3 has been observed with a corrupt accessory SDP record
     * (`Bad Service Class ID list` → stack falls through to SPP `0x1101` / SCN4).
     * Refresh remote SDP, cancel inquiry, then try secure and insecure UUID dials.
     */
    @SuppressLint("MissingPermission")
    private fun openDialSocket(device: BluetoothDevice): BluetoothSocket? {
        if (!running.get()) return null
        if (device.bondState == BluetoothDevice.BOND_BONDING) return null
        val current = clientSocket
        if (current?.isConnected == true) {
            val currentAddr = try {
                current.remoteDevice?.address
            } catch (_: Throwable) {
                null
            }
            if (currentAddr.equals(device.address, ignoreCase = true)) return null
            if (sessionLive()) {
                Log.i(
                    CarPlayConsts.TAG,
                    "RFCOMM dial ${device.address} skipped; live session on $currentAddr",
                )
                return null
            }
            Log.i(
                CarPlayConsts.TAG,
                "RFCOMM drop idle peer $currentAddr for ${device.address} name=${device.name}",
            )
            synchronized(writeLock) {
                if (clientSocket === current) {
                    current.closeQuietly()
                    clientSocket = null
                }
            }
        }
        refreshRemoteSdp(device)
        try {
            adapter?.cancelDiscovery()
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "BT cancelDiscovery failed: ${e.message}")
        }
        val factories = listOf(
            "secure" to {
                device.createRfcommSocketToServiceRecord(accessoryUuid)
            },
            "insecure" to {
                device.createInsecureRfcommSocketToServiceRecord(accessoryUuid)
            },
        )
        for ((label, factory) in factories) {
            if (!running.get()) return null
            var socket: BluetoothSocket? = null
            try {
                Log.i(
                    CarPlayConsts.TAG,
                    "RFCOMM connecting to ${device.address} name=${device.name} " +
                        "uuid=${CarPlayConsts.IAP2_ACCESSORY_RFCOMM_UUID} via=$label",
                )
                socket = factory()
                socket.connect()
                if (!running.get()) {
                    socket.closeQuietly()
                    return null
                }
                if (clientSocket?.isConnected == true && sessionLive()) {
                    Log.i(
                        CarPlayConsts.TAG,
                        "RFCOMM dial ${device.address} ignored; live session already active",
                    )
                    socket.closeQuietly()
                    return null
                }
                return socket
            } catch (e: Throwable) {
                Log.i(
                    CarPlayConsts.TAG,
                    "RFCOMM dial ${device.address} failed via=$label: ${e.message}",
                )
                socket?.closeQuietly()
            }
        }
        return null
    }

    /**
     * Force a fresh remote SDP browse before …caff dial. Stale / corrupt cached
     * records on Samsung+Audi have been seen to resolve the accessory UUID to
     * SPP SCN4 (`0x1101`) instead of the CarPlay RFCOMM channel.
     */
    @SuppressLint("MissingPermission")
    private fun refreshRemoteSdp(device: BluetoothDevice) {
        val latch = CountDownLatch(1)
        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_UUID) return
                val extra = deviceExtra(intent) ?: return
                if (extra.address.equals(device.address, ignoreCase = true)) {
                    latch.countDown()
                }
            }
        }
        try {
            appContext.registerReceiver(
                rx,
                IntentFilter(BluetoothDevice.ACTION_UUID),
                Context.RECEIVER_EXPORTED,
            )
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "SDP UUID watch register failed: ${e.message}")
            return
        }
        try {
            val started = try {
                device.fetchUuidsWithSdp()
            } catch (e: Throwable) {
                Log.w(CarPlayConsts.TAG, "fetchUuidsWithSdp failed: ${e.message}")
                false
            }
            if (!started) {
                Log.w(CarPlayConsts.TAG, "fetchUuidsWithSdp returned false for ${device.address}")
                return
            }
            val ok = latch.await(SDP_REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val hasCaff = try {
                device.uuids?.any { it.uuid == accessoryUuid } == true
            } catch (_: Throwable) {
                false
            }
            Log.i(
                CarPlayConsts.TAG,
                "RFCOMM SDP refresh ${device.address} ok=$ok hasCaff=$hasCaff",
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "SDP refresh wait failed: ${e.message}")
        } finally {
            try {
                appContext.unregisterReceiver(rx)
            } catch (_: Throwable) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun listen(bt: BluetoothAdapter, recordName: String, uuid: UUID) {
        val sdpName = when (recordName) {
            "caff" -> "CarPlay Accessory"
            "wcp" -> "Wireless CarPlay"
            else -> "CarPlay Phone"
        }
        try {
            val server = bt.listenUsingRfcommWithServiceRecord(sdpName, uuid)
            synchronized(writeLock) { serverSockets += server }
            Log.i(
                CarPlayConsts.TAG,
                "RFCOMM listening $recordName sdp=\"$sdpName\" uuid=$uuid",
            )
            while (running.get()) {
                val socket = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                if (!running.get()) {
                    socket.closeQuietly()
                    break
                }
                if (clientSocket?.isConnected == true && sessionLive()) {
                    Log.i(
                        CarPlayConsts.TAG,
                        "RFCOMM accept ignored ($recordName); live session already active",
                    )
                    socket.closeQuietly()
                    continue
                }
                if (clientSocket?.isConnected == true) {
                    // sessionLive is false only for a truly idle socket (no iAP yet).
                    Log.i(CarPlayConsts.TAG, "RFCOMM accept replacing idle session via $recordName")
                }
                attachPeer(socket, inbound = true, via = recordName)
            }
            synchronized(writeLock) { serverSockets.remove(server) }
            try {
                server.close()
            } catch (_: Throwable) {
            }
        } catch (e: Throwable) {
            if (running.get()) {
                Log.e(CarPlayConsts.TAG, "RFCOMM listen/accept failed", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attachPeer(socket: BluetoothSocket, inbound: Boolean, via: String = "dial") {
        val generation = synchronized(writeLock) {
            if (clientSocket != null && clientSocket !== socket) {
                clientSocket?.closeQuietly()
            }
            clientSocket = socket
            peerInbound = inbound
            ++peerGeneration
        }
        val address = try {
            socket.remoteDevice?.address
        } catch (_: Throwable) {
            null
        }
        val name = try {
            socket.remoteDevice?.name
        } catch (_: Throwable) {
            null
        }
        Log.i(
            CarPlayConsts.TAG,
            "RFCOMM peer ${if (inbound) "accepted" else "connected"} via=$via " +
                "$address name=$name",
        )
        // Inbound …cafe won — cancel any pending …caff fallback so Auth is not torn.
        if (inbound) {
            headUnitDialGeneration.incrementAndGet()
            headUnitDialPending = false
        }
        onPeer(name, address)
        onConnected()
        pump(socket)
        synchronized(writeLock) {
            if (peerGeneration == generation && clientSocket === socket) {
                clientSocket = null
            }
        }
        // Always notify. A newer peer already incremented the session user count,
        // so this close is ignored there and does not tear down the replacement.
        onDisconnected()
        Log.i(
            CarPlayConsts.TAG,
            if (peerGeneration == generation) "RFCOMM peer session ended" else "RFCOMM peer $address replaced",
        )
    }

    private fun BluetoothSocket.closeQuietly() {
        try {
            close()
        } catch (_: Throwable) {
        }
    }

    private fun pump(socket: BluetoothSocket) {
        val input = socket.inputStream
        val buf = ByteArray(4096)
        while (running.get()) {
            val n = try {
                input.read(buf)
            } catch (_: Throwable) {
                -1
            }
            if (n <= 0) break
            onBytes(buf.copyOf(n))
        }
        Log.i(CarPlayConsts.TAG, "RFCOMM pump ended")
    }

    /** Write raw iAP2 bytes (detect marker or FF5A frame). */
    fun sendRaw(bytes: ByteArray) {
        val out = synchronized(writeLock) { clientSocket?.outputStream } ?: return
        try {
            synchronized(writeLock) {
                out.write(bytes)
                out.flush()
            }
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "RFCOMM write failed", e)
        }
    }

    fun stop() {
        running.set(false)
        rfcommArmed.set(false)
        discoverableRequested.set(false)
        disarmBtStateWatch()
        disarmPeerWatch()
        disarmPairingWatch()
        synchronized(writeLock) {
            clientSocket?.closeQuietly()
            clientSocket = null
            for (server in serverSockets) {
                try {
                    server.close()
                } catch (_: Throwable) {
                }
            }
            serverSockets.clear()
        }
    }

    companion object {
        private const val DIAL_RETRY_MS = 4_000L
        /** Wait for inbound …cafe before the first …caff attempt (inbound-preferred HUs). */
        private const val INBOUND_DIAL_FALLBACK_MS = 2_500L
        /** Post-AAP / ACL-live: HU almost never dials cafe — shorten the wait. */
        private const val FORCE_OUTBOUND_FIRST_WAIT_MS = 800L
        /** Spacing between …caff fallback retries after a failed SDP/connect. */
        private const val INBOUND_DIAL_RETRY_MS = 5_000L
        /** Cafe-wait + outbound attempts before giving up to listen-only. */
        private const val INBOUND_DIAL_MAX_ATTEMPTS = 3
        private const val SDP_REFRESH_TIMEOUT_MS = 2_000L
        private const val HFP_BOUNCE_GAP_MS = 400L
        private const val HFP_BOUNCE_AWAIT_MS = 2_500L

        /** Status page when FGS is not yet armed — same 300s system dialog. */
        fun launchDiscoverableDialog(context: Context) {
            try {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                    if (context !is android.app.Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                context.startActivity(intent)
                Log.i(CarPlayConsts.TAG, "BT discoverable request dialog shown (300s, static)")
            } catch (e: Throwable) {
                Log.w(CarPlayConsts.TAG, "BT discoverable request failed", e)
            }
        }
    }
}
