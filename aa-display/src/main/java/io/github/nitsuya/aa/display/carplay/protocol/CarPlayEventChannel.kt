package io.github.nitsuya.aa.display.carplay.protocol

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import io.github.nitsuya.aa.display.carplay.CarPlayConsts
import io.github.nitsuya.aa.display.carplay.CarPlayWheelController
import io.github.nitsuya.aa.display.carplay.TouchGestureClock
import io.github.nitsuya.aa.display.carplay.buildMultiPointerEvent
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * AirPlay event channel (TCP [eventPort] from SETUP session).
 *
 * Event keys are the complement of the accessory: we decrypt `Events-Write`
 * and encrypt `Events-Read`. Reversing that fails `ChaCha20-Poly1305 open`
 * on the first HU frame and the socket closes.
 * Accessory → Source: `POST /command` (forceKeyFrame, hidSendReport, …);
 * Source replies encrypted `RTSP/1.0 200 OK`.
 */
class CarPlayEventChannel private constructor(
    private val socket: Socket,
    private val crypto: ControlChannelCrypto,
    private val onForceKeyFrame: () -> Unit,
    private val onTouch: (MotionEvent) -> Unit,
    private val touchMaxX: Int,
    private val touchMaxY: Int,
    private val onHidKey: (device: Int, code: Int, pressed: Boolean, extra: Int) -> Unit,
    private val onIapMessage: (ByteArray) -> Unit,
    /**
     * After HU UNBORROW/UNTAKE restores MainScreen/MainAudio to the phone, fire so the
     * control channel can POST `modesChanged` (speakers/HID come back). Never call this
     * on BORROW/TAKE — reclaiming mid-borrow made Audi silent with RTP still flowing.
     */
    private val onModesRestoredToPhone: () -> Unit,
    /**
     * MainAudio specifically left the HU and came back to the phone (FM / call →
     * CarPlay source). The HU's CarPlay audio engine was reset while the other source
     * was live, so the existing audio stream (old dataPort + key + RTP sequence) is
     * silently ignored again — the session re-SETUPs the audio streams from here.
     */
    private val onMainAudioRestored: () -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    private val commandLock = Object()
    private var commandsReplied = 0
    private var lastCommandAt = 0L
    private val out = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
    @Volatile private var fingerDown = false
    @Volatile private var knobBtns = 0

    // Resource manager state (WWDC16): entity=current owner, permanentEntity=take owner.
    // 0 = accessory (HU), 1 = controller (phone). resourceID 1=MainScreen, 2=MainAudio.
    private val modesLock = Any()
    @Volatile private var screenEntity = ENTITY_PHONE
    @Volatile private var screenPermanent = ENTITY_PHONE
    @Volatile private var audioEntity = ENTITY_PHONE
    @Volatile private var audioPermanent = ENTITY_PHONE

    /** True while HU BORROW/TAKE holds MainAudio — skip phone-owns modesChanged reclaim. */
    fun isPhoneOwningMainAudio(): Boolean = audioEntity == ENTITY_PHONE

    /** One-shot flag for the next [handle] after [applyChangeModes] restored MainAudio. */
    @Volatile
    private var mainAudioRestoredPending = false

    /**
     * iPhone answers accessory `POST /command` (modesChanged, iAPSendMessage)
     * and lets that settle before the screen SETUP. Returns how many commands
     * were replied to.
     */
    fun awaitBeforeScreen(budgetMs: Long, quietMs: Long = 150L): Int {
        val deadline = System.currentTimeMillis() + budgetMs
        synchronized(commandLock) {
            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (commandsReplied > 0 && now - lastCommandAt >= quietMs) {
                    return commandsReplied
                }
                val remain = deadline - now
                if (remain <= 0) break
                try {
                    commandLock.wait(minOf(quietMs, remain))
                } catch (_: InterruptedException) {
                    break
                }
            }
            return commandsReplied
        }
    }

    private val outboundCseq = AtomicInteger(1)

    fun start() {
        thread(name = "carplay-event", isDaemon = true) {
            val input = BufferedInputStream(socket.getInputStream())
            Log.i(
                CarPlayConsts.TAG,
                "event channel pump touchMax=${touchMaxX}x$touchMaxY",
            )
            while (!closed.get() && !socket.isClosed) {
                val raw = try {
                    crypto.readMessage(input)
                } catch (e: Throwable) {
                    if (!closed.get()) Log.w(CarPlayConsts.TAG, "event read ended", e)
                    break
                } ?: break
                val msg = RtspMessage.parse(raw) ?: continue
                handle(msg)
            }
            close()
        }
    }

    /**
     * Fire-and-forget `POST /command` on the **event** socket (Events keys, own CSeq).
     * CarDeck/DiPlay: HID arming (`hidSetInputMode`) must not go on the RTSP control
     * connection — the HU ignores it there.
     */
    fun postCommand(plist: Map<String, Any?>): Boolean {
        if (closed.get() || socket.isClosed) return false
        return try {
            val body = BinaryPlist.encode(plist)
            val seq = outboundCseq.getAndIncrement()
            val req = RtspMessage(
                "POST /command RTSP/1.0",
                linkedMapOf(
                    "Content-Type" to "application/x-apple-binary-plist",
                    "Content-Length" to body.size.toString(),
                    "CSeq" to seq.toString(),
                ),
                body,
            )
            synchronized(writeLock) {
                crypto.writeMessage(out, req.toBytes())
                out.flush()
            }
            true
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "event postCommand failed", e)
            false
        }
    }

    /** Arm each `/info` hidDevices uuid so the HU starts sending touch reports. */
    fun armHidInputModes(uuids: Collection<String>): Int {
        var ok = 0
        for (uuid in uuids) {
            if (uuid.isBlank()) continue
            val sent = postCommand(
                linkedMapOf(
                    "type" to "hidSetInputMode",
                    "params" to linkedMapOf(
                        "hidInputMode" to 0L,
                        "uuid" to uuid,
                    ),
                ),
            )
            Log.i(CarPlayConsts.TAG, "event hidSetInputMode uuid=$uuid ok=$sent")
            if (sent) ok++
        }
        return ok
    }

    private fun handle(msg: RtspMessage) {
        val path = msg.firstLine.substringAfter(' ', "").substringBefore(' ')
        Log.i(
            CarPlayConsts.TAG,
            "event ← ${msg.firstLine} body=${msg.body.size}B",
        )
        // iPhone: changeModes gets a plist 200 (status+params ~220B), not empty.
        // Empty ACK left Audi black then tear-down ~5s after BORROW_CHANGE.
        var replyBody = ByteArray(0)
        if (msg.method == "POST" && path.contains("command", ignoreCase = true)) {
            replyBody = dispatchCommand(msg.body) ?: ByteArray(0)
        }
        reply200(msg, replyBody)
        synchronized(commandLock) {
            commandsReplied++
            lastCommandAt = System.currentTimeMillis()
            commandLock.notifyAll()
        }
    }

    /** @return optional RTSP 200 body (changeModes status plist); null → empty. */
    private fun dispatchCommand(body: ByteArray): ByteArray? {
        if (body.isEmpty()) return null
        val dict = BinaryPlist.asDict(BinaryPlist.decode(body)) ?: return null
        when (dict["type"]?.toString()) {
            "forceKeyFrame" -> {
                Log.i(CarPlayConsts.TAG, "event forceKeyFrame")
                onForceKeyFrame()
            }
            "hidSendReport" -> {
                val report = dict["hidReport"] as? ByteArray ?: return null
                val uuidRaw = dict["uuid"]
                val uuid = when (uuidRaw) {
                    is String -> uuidRaw.lowercase()
                    is Number -> uuidRaw.toInt().toString()
                    is ByteArray -> uuidRaw.joinToString("") { "%02x".format(it) }
                    else -> uuidRaw?.toString()?.lowercase()
                }
                // DiPlay: hex uuid tails. Audi MMI: numeric uuid + CSM TouchCodec
                // layouts (often 5B LE_FLAG_XY16), not DiPlay's 6B×2 slot format.
                when {
                    uuid == KNOB_HID_UUID || uuid == "2" -> parseKnobReport(report)
                    uuid == MEDIA_HID_UUID || uuid == "3" ->
                        parseOneByteReport(CarPlayWheelController.DEV_MEDIA, report)
                    uuid == TELEPHONY_HID_UUID || uuid == "4" ->
                        parseOneByteReport(CarPlayWheelController.DEV_TELEPHONY, report)
                    uuid == TOUCH_HID_UUID || uuid == TOUCH_HID_UUID_ALT ||
                        uuid == "1" || uuid == "0" || uuid == null ||
                        report.size >= 5 -> parseTouchReport(report)
                    report.size == 1 ->
                        parseOneByteReport(CarPlayWheelController.DEV_MEDIA, report)
                    else -> Log.i(
                        CarPlayConsts.TAG,
                        "event hidSendReport uuid=$uuid len=${report.size} " +
                            "raw=${report.joinToString("") { "%02x".format(it) }} (ignored)",
                    )
                }
            }
            "iAPSendMessage" -> {
                val params = BinaryPlist.asDict(dict["params"])
                val data = params?.get("data") as? ByteArray
                if (data == null || data.isEmpty()) {
                    Log.w(CarPlayConsts.TAG, "event iAPSendMessage missing params.data")
                } else {
                    val head = data.take(12).joinToString(" ") { "%02x".format(it) }
                    Log.i(CarPlayConsts.TAG, "event iAP2 ${data.size}B head=$head")
                    onIapMessage(data)
                }
            }
            "modesChanged" -> {
                Log.i(CarPlayConsts.TAG, "event modesChanged keys=${dict.keys}")
            }
            "changeModes" -> {
                val params = BinaryPlist.asDict(dict["params"])
                val reason = params?.get("reasonStr")?.toString() ?: params?.get("reason")?.toString()
                Log.i(
                    CarPlayConsts.TAG,
                    "event changeModes reason=$reason resources=${params?.get("resources")}",
                )
                // Grant/release per WWDC resource manager. Refusing BORROW (always
                // phone-owns) left Audi with RTP alive but speakers+wheel dead after
                // backupCamera MainScreen borrow. Empty 200 → black ~5s then tear-down.
                val restored = applyChangeModes(params)
                val modes = currentModesParams()
                val encoded = BinaryPlist.encode(
                    mapOf(
                        "status" to 0,
                        "params" to modes,
                    ),
                    groupKeys = true,
                )
                Log.i(
                    CarPlayConsts.TAG,
                    "event changeModes → 200 body=${encoded.size}B " +
                        "screen=$screenEntity/$screenPermanent audio=$audioEntity/$audioPermanent " +
                        "restored=$restored",
                )
                if (restored) {
                    try {
                        onModesRestoredToPhone()
                    } catch (e: Throwable) {
                        Log.w(CarPlayConsts.TAG, "onModesRestoredToPhone failed", e)
                    }
                }
                if (mainAudioRestoredPending) {
                    mainAudioRestoredPending = false
                    try {
                        onMainAudioRestored()
                    } catch (e: Throwable) {
                        Log.w(CarPlayConsts.TAG, "onMainAudioRestored failed", e)
                    }
                }
                return encoded
            }
            "requestUI" -> {
                Log.i(CarPlayConsts.TAG, "event requestUI keys=${dict.keys}")
                onForceKeyFrame()
            }
            else -> Log.i(CarPlayConsts.TAG, "event command type=${dict["type"]}")
        }
        return null
    }

    /**
     * Apply HU take/borrow/untake/unborrow. @return true when at least one resource
     * returned to the phone (controller) so control can re-announce modesChanged.
     */
    private fun applyChangeModes(params: Map<String, Any?>?): Boolean {
        val resources = params?.get("resources") as? List<*> ?: return false
        var restored = false
        synchronized(modesLock) {
            val screenBefore = screenEntity
            val audioBefore = audioEntity
            for (item in resources) {
                val r = BinaryPlist.asDict(item) ?: continue
                val id = BinaryPlist.intOf(r["resourceID"])?.toInt() ?: continue
                val transfer = BinaryPlist.intOf(r["transferType"])?.toInt() ?: continue
                when (id) {
                    RESOURCE_MAIN_SCREEN -> {
                        when (transfer) {
                            TRANSFER_TAKE -> {
                                screenEntity = ENTITY_ACCESSORY
                                screenPermanent = ENTITY_ACCESSORY
                            }
                            TRANSFER_UNTAKE -> {
                                screenEntity = ENTITY_PHONE
                                screenPermanent = ENTITY_PHONE
                            }
                            TRANSFER_BORROW -> screenEntity = ENTITY_ACCESSORY
                            TRANSFER_UNBORROW -> screenEntity = screenPermanent
                            else -> Log.w(
                                CarPlayConsts.TAG,
                                "changeModes unknown transferType=$transfer resourceID=$id",
                            )
                        }
                    }
                    RESOURCE_MAIN_AUDIO -> {
                        when (transfer) {
                            TRANSFER_TAKE -> {
                                audioEntity = ENTITY_ACCESSORY
                                audioPermanent = ENTITY_ACCESSORY
                            }
                            TRANSFER_UNTAKE -> {
                                audioEntity = ENTITY_PHONE
                                audioPermanent = ENTITY_PHONE
                            }
                            TRANSFER_BORROW -> audioEntity = ENTITY_ACCESSORY
                            TRANSFER_UNBORROW -> audioEntity = audioPermanent
                            else -> Log.w(
                                CarPlayConsts.TAG,
                                "changeModes unknown transferType=$transfer resourceID=$id",
                            )
                        }
                    }
                    else -> Log.i(
                        CarPlayConsts.TAG,
                        "changeModes ignore resourceID=$id transferType=$transfer",
                    )
                }
            }
            if (screenBefore != ENTITY_PHONE && screenEntity == ENTITY_PHONE) restored = true
            if (audioBefore != ENTITY_PHONE && audioEntity == ENTITY_PHONE) {
                restored = true
                mainAudioRestoredPending = true
            }
        }
        return restored
    }

    /** Current MainScreen+MainAudio ownership snapshot (Audi volume / mixer). */
    private fun currentModesParams(): Map<String, Any?> = synchronized(modesLock) {
        linkedMapOf(
            "resources" to listOf(
                linkedMapOf(
                    "resourceID" to RESOURCE_MAIN_SCREEN,
                    "entity" to screenEntity,
                    "permanentEntity" to screenPermanent,
                ),
                linkedMapOf(
                    "resourceID" to RESOURCE_MAIN_AUDIO,
                    "entity" to audioEntity,
                    "permanentEntity" to audioPermanent,
                ),
            ),
            "appStates" to listOf(
                linkedMapOf("appStateID" to 2, "entity" to 0),
                linkedMapOf("appStateID" to 3, "entity" to 0),
                linkedMapOf("appStateID" to 1, "entity" to 0, "speechMode" to -1),
            ),
        )
    }

    private fun parseTouchReport(report: ByteArray) {
        if (report.size < 4) return
        if (chosenTouchLayout == TouchLayout.DIPLAY_SLOT6) {
            if (report.size >= 6) {
                parseDiPlaySlots(report)
            } else if (diplayFingers.isNotEmpty()) {
                // Short frame while fingers were down — force all-up so chrome/pane
                // do not stay pressed after the digitizer stops sending full slots.
                flushDiPlayFingers()
            }
            return
        }
        val decoded = decodeTouchPoint(report) ?: return
        val maxX = touchMaxX.coerceAtLeast(1).toFloat()
        val maxY = touchMaxY.coerceAtLeast(1).toFloat()
        val xNorm = (decoded.x.toFloat() / maxX).coerceIn(0f, 1f)
        val yNorm = (decoded.y.toFloat() / maxY).coerceIn(0f, 1f)
        val down = decoded.tip
        val action = when {
            down && !fingerDown -> MotionEvent.ACTION_DOWN
            down && fingerDown -> MotionEvent.ACTION_MOVE
            !down && fingerDown -> MotionEvent.ACTION_UP
            else -> return
        }
        fingerDown = down
        val arrivedAt = SystemClock.uptimeMillis()
        val (downTime, eventTime) = TouchGestureClock.stamp(action, arrivedAt)
        val event = MotionEvent.obtain(downTime, eventTime, action, xNorm, yNorm, 0)
        onTouch(event)
        if (action != MotionEvent.ACTION_MOVE || touchLogCount < 8) {
            touchLogCount++
            Log.i(
                CarPlayConsts.TAG,
                "event touch action=$action x=$xNorm y=$yNorm " +
                    "px=${decoded.x},${decoded.y} layout=${decoded.layout} " +
                    "rawMax=${touchMaxX}x$touchMaxY",
            )
        }
    }

    /**
     * DiPlay [TouchCodec] 6B×N slots `[id|down|x16le|y16le]` — one slot per finger,
     * so a report can carry several pointers. Table keyed by slot id (slot index
     * fallback when id==0); tip-up or absent slots release the pointer.
     */
    private val diplayFingers = LinkedHashMap<Int, IntArray>()

    private fun parseDiPlaySlots(report: ByteArray) {
        val slots = report.size / 6
        if (slots == 0) {
            if (diplayFingers.isNotEmpty()) flushDiPlayFingers()
            return
        }
        fun u16le(o: Int) =
            (report[o].toInt() and 0xff) or ((report[o + 1].toInt() and 0xff) shl 8)
        val slotPts = LinkedHashMap<Int, IntArray>() // down slots only
        for (k in 0 until slots) {
            val id = report[k * 6].toInt() and 0xff
            if (report[k * 6 + 1].toInt() and 0xff == 0) continue
            val key = if (id != 0) id else k
            slotPts[key] = intArrayOf(u16le(k * 6 + 2), u16le(k * 6 + 4))
        }
        val before = diplayFingers.keys.toList()
        val added = slotPts.keys.filter { it !in before }
        val removed = before.filter { it !in slotPts }
        if (before.isEmpty() && added.isEmpty()) return
        val action = when {
            added.isNotEmpty() ->
                if (before.isEmpty()) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN
            removed.isNotEmpty() ->
                if (before.size - removed.size == 0) MotionEvent.ACTION_UP
                else MotionEvent.ACTION_POINTER_UP

            else -> MotionEvent.ACTION_MOVE
        }
        val table = LinkedHashMap(diplayFingers)
        table.putAll(slotPts) // adds new pointers, refreshes positions
        val ids = table.keys.toList()
        val arrivedAt = SystemClock.uptimeMillis()
        val maxX = touchMaxX.coerceAtLeast(1).toFloat()
        val maxY = touchMaxY.coerceAtLeast(1).toFloat()
        // Event keeps released pointers (Android semantics); the table does not.
        val pts = ids.map { id ->
            val xy = table[id]!!
            id to floatArrayOf(
                (xy[0].toFloat() / maxX).coerceIn(0f, 1f),
                (xy[1].toFloat() / maxY).coerceIn(0f, 1f),
            )
        }
        for (id in removed) table.remove(id)
        diplayFingers.clear()
        diplayFingers.putAll(table)
        fingerDown = diplayFingers.isNotEmpty()
        val actionIndex = when (action) {
            MotionEvent.ACTION_POINTER_DOWN -> ids.indexOf(added.first()).coerceAtLeast(0)
            MotionEvent.ACTION_POINTER_UP -> ids.indexOfFirst { it in removed }.coerceAtLeast(0)
            else -> 0
        }
        val (downTime, eventTime) = TouchGestureClock.stamp(action, arrivedAt)
        val event = buildMultiPointerEvent(downTime, eventTime, action, actionIndex, pts)
        if (action != MotionEvent.ACTION_MOVE) {
            Log.i(
                CarPlayConsts.TAG,
                "event touch multi action=$action n=${pts.size} " +
                    "rawMax=${touchMaxX}x$touchMaxY",
            )
        }
        onTouch(event)
    }

    /** Emit a synthetic ACTION_UP for every tracked DiPlay finger, then clear. */
    private fun flushDiPlayFingers() {
        if (diplayFingers.isEmpty()) return
        val arrivedAt = SystemClock.uptimeMillis()
        val maxX = touchMaxX.coerceAtLeast(1).toFloat()
        val maxY = touchMaxY.coerceAtLeast(1).toFloat()
        val pts = diplayFingers.map { (id, xy) ->
            id to floatArrayOf(
                (xy[0].toFloat() / maxX).coerceIn(0f, 1f),
                (xy[1].toFloat() / maxY).coerceIn(0f, 1f),
            )
        }
        diplayFingers.clear()
        fingerDown = false
        val (downTime, eventTime) = TouchGestureClock.stamp(MotionEvent.ACTION_UP, arrivedAt)
        val event = buildMultiPointerEvent(
            downTime, eventTime, MotionEvent.ACTION_UP, 0, pts,
        )
        Log.i(CarPlayConsts.TAG, "event touch multi flush-up n=${pts.size}")
        onTouch(event)
    }

    private var touchLogCount = 0
    private var chosenTouchLayout: TouchLayout? = null
    private var touchProbeCount = 0
    private val touchFit = IntArray(TouchLayout.entries.size)
    private val touchTotal = IntArray(TouchLayout.entries.size)

    private data class TouchPoint(val x: Int, val y: Int, val tip: Boolean, val layout: TouchLayout)

    /**
     * CSM [TouchCodec] layouts. Audi MMI_8280 uses 5B [flag|x16le|y16le]; DiPlay uses
     * 6B×N [id|down|x16le|y16le]. Probe fits against the /info panel until one wins.
     */
    private enum class TouchLayout {
        /** Audi: `[flag, x_lo, x_hi, y_lo, y_hi]` tip=(flag&1). */
        LE_FLAG_XY16,
        /** DiPlay primary slot: `[id, down, x_lo, x_hi, y_lo, y_hi]`. */
        DIPLAY_SLOT6,
        BE_X16_Y16,
        LE_X16_Y16,
    }

    private fun decodeTouchPoint(report: ByteArray): TouchPoint? {
        val chosen = chosenTouchLayout
        if (chosen != null) {
            return decodeLayout(report, chosen)
        }
        for (layout in TouchLayout.entries) {
            val pt = decodeLayout(report, layout) ?: continue
            val idx = layout.ordinal
            touchTotal[idx]++
            if (pt.x in 0 until touchMaxX && pt.y in 0 until touchMaxY) {
                touchFit[idx]++
            }
        }
        touchProbeCount++
        if (touchProbeCount >= 8) {
            var best: TouchLayout? = null
            var bestFit = 0
            for (layout in TouchLayout.entries) {
                val fit = touchFit[layout.ordinal]
                if (fit > bestFit) {
                    bestFit = fit
                    best = layout
                }
            }
            chosenTouchLayout = best ?: TouchLayout.LE_FLAG_XY16
            // Probe ends mid-press: seed the multi-pointer table so the first slot
            // report after the switch does not fire a phantom DOWN.
            if (chosenTouchLayout == TouchLayout.DIPLAY_SLOT6 && fingerDown) {
                decodeLayout(report, TouchLayout.DIPLAY_SLOT6)?.let {
                    diplayFingers[0] = intArrayOf(it.x, it.y)
                }
            }
            Log.i(
                CarPlayConsts.TAG,
                "event touch layout=${chosenTouchLayout} fit=" +
                    TouchLayout.entries.joinToString(",") {
                        "${it.name}:${touchFit[it.ordinal]}/${touchTotal[it.ordinal]}"
                    },
            )
        }
        // Prefer LE_FLAG_XY16 while probing (Audi 5B reports).
        return decodeLayout(report, TouchLayout.LE_FLAG_XY16)
            ?: decodeLayout(report, TouchLayout.DIPLAY_SLOT6)
    }

    private fun decodeLayout(report: ByteArray, layout: TouchLayout): TouchPoint? {
        fun u16le(o: Int) =
            (report[o].toInt() and 0xff) or ((report[o + 1].toInt() and 0xff) shl 8)
        fun u16be(o: Int) =
            ((report[o].toInt() and 0xff) shl 8) or (report[o + 1].toInt() and 0xff)
        return when (layout) {
            TouchLayout.LE_FLAG_XY16 -> {
                if (report.size < 5) return null
                val tip = (report[0].toInt() and 1) != 0
                TouchPoint(u16le(1), u16le(3), tip, layout)
            }
            TouchLayout.DIPLAY_SLOT6 -> {
                if (report.size < 6) return null
                val tip = report[1].toInt() and 0xff != 0
                TouchPoint(u16le(2), u16le(4), tip, layout)
            }
            TouchLayout.BE_X16_Y16 -> {
                if (report.size < 6) return null
                val b = report[1].toInt()
                val tip = (b and 1) != 0 || (b and 0x80) != 0
                TouchPoint(u16be(2), u16be(4), tip, layout)
            }
            TouchLayout.LE_X16_Y16 -> {
                if (report.size < 6) return null
                val b = report[1].toInt()
                val tip = (b and 1) != 0 || (b and 0x80) != 0
                TouchPoint(u16le(2), u16le(4), tip, layout)
            }
        }
    }

    /**
     * DiPlay [AirPlayHid.knobReport]: 4 bytes — [0] buttons (0x01 select / 0x02 home /
     * 0x04 back), [1..2] absolute x/y, [3] relative wheel (signed 8 bit).
     */
    private fun parseKnobReport(report: ByteArray) {
        if (report.size < 4) return
        val btns = report[0].toInt() and 0xff
        val wheel = report[3].toInt() // signed
        val prev = knobBtns
        knobBtns = btns
        if (btns != 0) {
            if (btns and 0x01 != 0) onHidKey(CarPlayWheelController.DEV_KNOB, CarPlayWheelController.KNOB_SELECT, true, 0)
            if (btns and 0x02 != 0) onHidKey(CarPlayWheelController.DEV_KNOB, CarPlayWheelController.KNOB_HOME, true, 0)
            if (btns and 0x04 != 0) onHidKey(CarPlayWheelController.DEV_KNOB, CarPlayWheelController.KNOB_BACK, true, 0)
        } else if (prev != 0) {
            onHidKey(CarPlayWheelController.DEV_KNOB, 0, false, 0)
        }
        if (wheel != 0) {
            onHidKey(CarPlayWheelController.DEV_KNOB, CarPlayWheelController.KNOB_WHEEL, true, wheel)
        }
    }

    /** Media / telephony: 1-byte usage index (0 = release). */
    private fun parseOneByteReport(device: Int, report: ByteArray) {
        if (report.isEmpty()) return
        val v = report[0].toInt() and 0xff
        if (device == CarPlayWheelController.DEV_MEDIA) {
            Log.i(
                CarPlayConsts.TAG,
                "hid media report=${report.joinToString("") { "%02x".format(it) }} " +
                    "usage=$v pressed=${v != 0}",
            )
        }
        onHidKey(device, if (v == 0) 0 else v, v != 0, v)
    }

    private fun gmtHttpDate(): String {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        return fmt.format(Date())
    }

    private fun reply200(request: RtspMessage, body: ByteArray = ByteArray(0)) {
        val cseq = request.headers.entries
            .firstOrNull { it.key.equals("CSeq", ignoreCase = true) }
            ?.value?.trim() ?: "0"
        Log.i(CarPlayConsts.TAG, "event → 200 cseq=$cseq body=${body.size}B")
        // iPhone event 200 on this MMI: Date + Content-Length, no CSeq.
        // A CSeq-only 200 leaves the HU retransmitting the same detect POST.
        val headers = linkedMapOf(
            "Date" to gmtHttpDate(),
            "Content-Length" to body.size.toString(),
        )
        if (body.isNotEmpty()) {
            headers["Content-Type"] = "application/x-apple-binary-plist"
        }
        val resp = RtspMessage("RTSP/1.0 200 OK", headers, body)
        try {
            synchronized(writeLock) {
                crypto.writeMessage(out, resp.toBytes())
                out.flush()
            }
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "event reply failed", e)
            close()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            out.close()
        } catch (_: Throwable) {
        }
        try {
            socket.close()
        } catch (_: Throwable) {
        }
        Log.i(CarPlayConsts.TAG, "event channel closed")
    }

    companion object {
        /** DiPlay AirPlayHid device uuids (lowercase hex, no 0x prefix on the wire). */
        private const val TOUCH_HID_UUID = "2a2a2a2a"
        private const val TOUCH_HID_UUID_ALT = "2A2A2A2A"
        private const val KNOB_HID_UUID = "2a2a2a2b"
        private const val MEDIA_HID_UUID = "2a2a2a2c"
        private const val TELEPHONY_HID_UUID = "2a2a2a2d"

        private const val RESOURCE_MAIN_SCREEN = 1
        private const val RESOURCE_MAIN_AUDIO = 2
        private const val ENTITY_ACCESSORY = 0
        private const val ENTITY_PHONE = 1
        /** CSM / Audi numeric transferType (matches live BORROW_CHANGE logs). */
        private const val TRANSFER_TAKE = 1
        private const val TRANSFER_UNTAKE = 2
        private const val TRANSFER_BORROW = 3
        private const val TRANSFER_UNBORROW = 4

        fun open(
            host: InetAddress,
            eventPort: Int,
            sharedSecret: ByteArray,
            touchMaxX: Int,
            touchMaxY: Int,
            onForceKeyFrame: () -> Unit,
            onTouch: (MotionEvent) -> Unit,
            onHidKey: (Int, Int, Boolean, Int) -> Unit,
            onIapMessage: (ByteArray) -> Unit = {},
            onModesRestoredToPhone: () -> Unit = {},
            onMainAudioRestored: () -> Unit = {},
        ): CarPlayEventChannel? {
            if (eventPort <= 0 || eventPort > 65535) {
                Log.w(CarPlayConsts.TAG, "eventPort invalid=$eventPort")
                return null
            }
            return try {
                val readKey = HkdfSha512.derive(
                    sharedSecret,
                    "Events-Salt",
                    "Events-Write-Encryption-Key",
                    32,
                )
                val writeKey = HkdfSha512.derive(
                    sharedSecret,
                    "Events-Salt",
                    "Events-Read-Encryption-Key",
                    32,
                )
                val socket = io.github.nitsuya.aa.display.carplay.CarPlayAccessoryNet.openTcp()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, eventPort), 10_000)
                socket.soTimeout = 0
                val channel = CarPlayEventChannel(
                    socket = socket,
                    crypto = ControlChannelCrypto(writeKey, readKey),
                    onForceKeyFrame = onForceKeyFrame,
                    onTouch = onTouch,
                    touchMaxX = touchMaxX,
                    touchMaxY = touchMaxY,
                    onHidKey = onHidKey,
                    onIapMessage = onIapMessage,
                    onModesRestoredToPhone = onModesRestoredToPhone,
                    onMainAudioRestored = onMainAudioRestored,
                )
                Log.i(
                    CarPlayConsts.TAG,
                    "event channel connected $host:$eventPort touchMax=${touchMaxX}x$touchMaxY",
                )
                channel
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "event channel connect failed $host:$eventPort", e)
                null
            }
        }
    }
}
