package io.github.nitsuya.aa.display.carplay.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** iAP2 control-session message IDs and Source-role builders/parsers. */
object Iap2Csm {
    const val START_IDENTIFICATION = 0x1D00
    const val IDENTIFICATION_INFORMATION = 0x1D01
    const val IDENTIFICATION_ACCEPTED = 0x1D02
    const val IDENTIFICATION_REJECTED = 0x1D03

    const val REQUEST_AUTH_CERTIFICATE = 0xAA00
    const val AUTH_CERTIFICATE = 0xAA01
    const val REQUEST_AUTH_CHALLENGE = 0xAA02
    const val AUTH_RESPONSE = 0xAA03
    const val AUTH_FAILED = 0xAA04
    const val AUTH_SUCCEEDED = 0xAA05
    /** AccessoryAuthenticationSerialNumber — MIB3 asks the phone for this instead of a cert. */
    const val AUTH_SERIAL = 0xAA06

    const val REQUEST_ACCESSORY_WIFI = 0x5702
    const val ACCESSORY_WIFI_INFO = 0x5703

    const val DEVICE_INFORMATION_UPDATE = 0x4E09
    const val WIRELESS_SESSION_ID = 0x4E0C
    const val WIRELESS_CARPLAY_UPDATE = 0x4E0D
    const val DEVICE_TRANSPORT_ID = 0x4E0E

    const val CARPLAY_AVAILABILITY = 0x4300
    const val CARPLAY_START_SESSION = 0x4301

    /** Accessory asks the phone to start streaming NowPlaying updates. */
    const val START_NOW_PLAYING_UPDATES = 0x5000
    /** Phone → accessory incremental NowPlaying update (media + playback groups). */
    const val NOW_PLAYING_UPDATE = 0x5001

    const val WIRELESS_CP_AVAILABLE = 1

    data class WifiConfig(
        val ssid: String?,
        val passphrase: String?,
        val securityType: Int?,
        val channel: Int?,
    )

    data class CarPlayStart(
        val port: Int?,
        val deviceId: String?,
        val pairingId: String?,
        val sourceVersion: String?,
        val ssid: String?,
        val passphrase: String?,
        val channel: Int?,
        val ipAddresses: List<String>,
    )

    fun empty(messageId: Int): ByteArray = Iap2Framing.encodeCsm(messageId)

    fun requestAuthChallenge(challenge: ByteArray): ByteArray =
        Iap2Framing.encodeCsm(REQUEST_AUTH_CHALLENGE, mapOf(0x0000 to challenge))

    fun authenticationSerial(serial: String): ByteArray =
        Iap2Framing.encodeCsm(AUTH_SERIAL, mapOf(0x0000 to Iap2Framing.utf8Param(serial)))

    fun deviceTransportIdentifier(btId: String, usbId: String = ""): ByteArray =
        Iap2Framing.encodeCsm(
            DEVICE_TRANSPORT_ID,
            linkedMapOf(
                0x0000 to Iap2Framing.utf8Param(btId),
                0x0001 to Iap2Framing.utf8Param(usbId),
            ),
        )

    /** MIB3 `0x4E09`: param 0 is the phone name the HU shows ("iPhone"). */
    fun deviceInformationUpdate(name: String): ByteArray =
        Iap2Framing.encodeCsm(
            DEVICE_INFORMATION_UPDATE,
            mapOf(0x0000 to Iap2Framing.utf8Param(name)),
        )

    /** MIB3 `0x4E0C`: one uppercase UUID, stable for the link. */
    fun wirelessSessionIdentifier(id: String): ByteArray =
        Iap2Framing.encodeCsm(
            WIRELESS_SESSION_ID,
            mapOf(0x0000 to Iap2Framing.utf8Param(id)),
        )

    fun wirelessCarPlayUpdate(available: Boolean): ByteArray =
        Iap2Framing.encodeCsm(
            WIRELESS_CARPLAY_UPDATE,
            mapOf(0x0000 to byteArrayOf(if (available) WIRELESS_CP_AVAILABLE.toByte() else 0)),
        )

    /** New-protocol availability: wireless available + Bluetooth transport id. */
    fun carPlayAvailability(bluetoothTransportId: String): ByteArray {
        val wireless = encodeGroup(
            linkedMapOf(
                0x0000 to ByteArray(0), // available (NoneLike / present)
                0x0001 to Iap2Framing.utf8Param(bluetoothTransportId),
            ),
        )
        return Iap2Framing.encodeCsm(
            CARPLAY_AVAILABILITY,
            linkedMapOf(0x0001 to wireless),
        )
    }

    /**
     * MIB3 `0x4300`: wired group (param 0) and wireless group (param 1).
     * Available is the byte `1`. The id is the Bluetooth address, not the Wi-Fi STA.
     */
    fun mib3CarPlayAvailability(bluetoothMac: String, serial: String): ByteArray {
        val available = byteArrayOf(WIRELESS_CP_AVAILABLE.toByte())
        val wired = encodeGroup(
            linkedMapOf(
                0x0000 to available,
                0x0001 to Iap2Framing.utf8Param(serial),
            ),
        )
        val wireless = encodeGroup(
            linkedMapOf(
                0x0000 to available,
                0x0001 to Iap2Framing.utf8Param(bluetoothMac),
            ),
        )
        return Iap2Framing.encodeCsm(
            CARPLAY_AVAILABILITY,
            linkedMapOf(0x0000 to wired, 0x0001 to wireless),
        )
    }

    fun parseWifiConfig(msg: Iap2Framing.ControlMessage): WifiConfig {
        require(msg.messageId == ACCESSORY_WIFI_INFO)
        return WifiConfig(
            ssid = utf8(msg.parameters[0x0001]),
            passphrase = utf8(msg.parameters[0x0002]),
            securityType = msg.parameters[0x0003]?.firstOrNull()?.toInt()?.and(0xFF),
            channel = msg.parameters[0x0004]?.firstOrNull()?.toInt()?.and(0xFF),
        )
    }

    fun parseCarPlayStart(msg: Iap2Framing.ControlMessage): CarPlayStart {
        require(msg.messageId == CARPLAY_START_SESSION)
        // Wired (ncm_carplayd / ARMiPhoneIAP2): param0 = WiredAttributes { id0 IPAddress };
        // wireless (DiPlay / MIB3): param1 group may carry SSID/pass/channel/IPs.
        val wired = msg.parameters[0x0000]?.let { parseGroup(it) }.orEmpty()
        val wireless = msg.parameters[0x0001]?.let { parseGroup(it) }.orEmpty()
        val portBytes = msg.parameters[0x0002]
        val port = when {
            portBytes == null || portBytes.isEmpty() -> null
            portBytes.size >= 4 -> ByteBuffer.wrap(portBytes).order(ByteOrder.BIG_ENDIAN).int
            portBytes.size == 2 ->
                ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
            else -> portBytes[0].toInt() and 0xFF
        }
        val ips = mutableListOf<String>()
        utf8(wired[0x0000])?.let(ips::add)
        wireless[0x0003]?.let { utf8(it)?.let(ips::add) }
        // Some stacks put multiple NUL-separated IPs in one param.
        wireless[0x0003]?.let { raw ->
            val parts = String(raw, Charsets.UTF_8).split('\u0000').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size > 1) {
                ips.clear()
                utf8(wired[0x0000])?.let(ips::add)
                ips.addAll(parts)
            }
        }
        return CarPlayStart(
            port = port,
            deviceId = utf8(msg.parameters[0x0003]),
            // Wired may put raw Ed25519 LTPK in param4; keep string parse best-effort.
            pairingId = utf8(msg.parameters[0x0004]),
            sourceVersion = utf8(msg.parameters[0x0005]),
            ssid = utf8(wireless[0x0000]),
            passphrase = utf8(wireless[0x0001]),
            channel = wireless[0x0002]?.firstOrNull()?.toInt()?.and(0xFF),
            ipAddresses = ips.distinct(),
        )
    }

    fun authCertificateSummary(msg: Iap2Framing.ControlMessage): String {
        val leaf = msg.parameters[0x0000]?.size ?: 0
        val type = msg.parameters[0x0001]?.firstOrNull()?.toInt()?.and(0xFF)
        val intermediate = msg.parameters[0x0002]?.size ?: 0
        // wiomoc-style single blob also lands in param 0.
        return "leaf=${leaf}B type=$type intermediate=${intermediate}B params=${msg.parameters.keys}"
    }

    fun utf8(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
        if (end <= 0) return null
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    fun encodeGroup(params: Map<Int, ByteArray>): ByteArray {
        var size = 0
        for (v in params.values) size += 4 + v.size
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        for ((id, value) in params) {
            buf.putShort((4 + value.size).toShort())
            buf.putShort(id.toShort())
            buf.put(value)
        }
        return buf.array()
    }

    fun parseGroup(body: ByteArray): Map<Int, ByteArray> {
        val params = linkedMapOf<Int, ByteArray>()
        var pos = 0
        while (pos + 4 <= body.size) {
            val size =
                ((body[pos].toInt() and 0xFF) shl 8) or (body[pos + 1].toInt() and 0xFF)
            val id =
                ((body[pos + 2].toInt() and 0xFF) shl 8) or (body[pos + 3].toInt() and 0xFF)
            if (size < 4 || pos + size > body.size) break
            params[id] = body.copyOfRange(pos + 4, pos + size)
            pos += size
        }
        return params
    }
}
