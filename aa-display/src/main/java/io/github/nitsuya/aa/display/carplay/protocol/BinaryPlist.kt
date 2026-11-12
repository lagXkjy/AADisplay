package io.github.nitsuya.aa.display.carplay.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal Apple binary plist (bplist00) encoder/decoder for AirPlay SETUP bodies.
 * Supports dict / array / string / bool / int / data — enough for control SETUP/RECORD.
 */
object BinaryPlist {
    /**
     * @param groupKeys Apple's writer interns every dict key before any value.
     *   Event-channel `iAPSendMessage` on this Audi is byte-identical only in that order.
     *   SETUP bodies stay on the previous walk; those lengths are already accepted.
     */
    fun encode(root: Any?, minObjectRefSize: Int = 1, groupKeys: Boolean = false): ByteArray {
        val objects = ArrayList<Any?>()
        val indexOf = HashMap<IdentityKey, Int>()
        fun internNormalized(n: Any?): Int {
            val key = IdentityKey(n)
            indexOf[key]?.let { return it }
            val idx = objects.size
            objects.add(n)
            indexOf[key] = idx
            when (n) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = n as Map<String, Any?>
                    if (groupKeys) {
                        for (k in map.keys) internNormalized(k)
                        for (value in map.values) internNormalized(value)
                    } else {
                        for ((k, value) in map) {
                            internNormalized(k)
                            internNormalized(value)
                        }
                    }
                }
                is List<*> -> for (item in n) internNormalized(item)
            }
            return idx
        }
        internNormalized(normalize(root))
        // Same three-key screen dict is 158B with 1-byte object refs and 167B with
        // 2-byte refs. iPhone's Delphi SETUP body is 167B.
        val objectRefSize = maxOf(refSizeFor(objects.size), minObjectRefSize.coerceIn(1, 4))
        fun refBytes(ref: Int): ByteArray = intBytes(ref, objectRefSize)

        val objectBytes = Array(objects.size) { i ->
            val obj = objects[i]
            val out = ByteArrayOutputStream()
            when (obj) {
                null -> out.write(0x00)
                is Boolean -> out.write(if (obj) 0x09 else 0x08)
                is Number -> {
                    val v = obj.toLong()
                    when {
                        v in 0L..0xffL -> {
                            out.write(0x10)
                            out.write(v.toInt() and 0xff)
                        }
                        v in 0L..0xffffL -> {
                            out.write(0x11)
                            out.write(intBytes(v.toInt() and 0xffff, 2))
                        }
                        v in 0L..0xffff_ffffL -> {
                            out.write(0x12)
                            out.write(intBytes(v.toInt(), 4))
                        }
                        else -> {
                            out.write(0x13)
                            out.write(longBytes(v))
                        }
                    }
                }
                is ByteArray -> {
                    writeMarkerLength(out, 0x40, obj.size)
                    out.write(obj)
                }
                is String -> {
                    val ascii = obj.toByteArray(Charsets.US_ASCII)
                    if (ascii.size == obj.length) {
                        writeMarkerLength(out, 0x50, ascii.size)
                        out.write(ascii)
                    } else {
                        writeMarkerLength(out, 0x60, obj.length)
                        out.write(obj.toByteArray(Charsets.UTF_16BE))
                    }
                }
                is List<*> -> {
                    writeMarkerLength(out, 0xA0, obj.size)
                    for (item in obj) {
                        out.write(refBytes(indexOf.getValue(IdentityKey(item))))
                    }
                }
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = obj as Map<String, Any?>
                    writeMarkerLength(out, 0xD0, map.size)
                    for (k in map.keys) {
                        out.write(refBytes(indexOf.getValue(IdentityKey(k))))
                    }
                    for (v in map.values) {
                        out.write(refBytes(indexOf.getValue(IdentityKey(v))))
                    }
                }
                else -> out.write(0x00)
            }
            out.toByteArray()
        }

        val body = ByteArrayOutputStream()
        body.write("bplist00".toByteArray(Charsets.US_ASCII))
        val offsets = IntArray(objects.size)
        for (i in objects.indices) {
            offsets[i] = body.size()
            body.write(objectBytes[i])
        }
        val offsetTableOffset = body.size()
        val offsetSize = refSizeFor(offsetTableOffset)
        for (off in offsets) body.write(intBytes(off, offsetSize))
        body.write(ByteArray(6))
        body.write(offsetSize)
        body.write(objectRefSize)
        body.write(longBytes(objects.size.toLong()))
        body.write(longBytes(0))
        body.write(longBytes(offsetTableOffset.toLong()))
        return body.toByteArray()
    }

    fun decode(data: ByteArray): Any? {
        if (data.size < 32) return null
        val magic = data.copyOfRange(0, 8).toString(Charsets.US_ASCII)
        if (magic != "bplist00") return null
        val trailer = data.copyOfRange(data.size - 32, data.size)
        val offsetSize = trailer[6].toInt() and 0xff
        val objectRefSize = trailer[7].toInt() and 0xff
        val numObjects = readBeLong(trailer, 8)
        val topObject = readBeLong(trailer, 16)
        val offsetTableOffset = readBeLong(trailer, 24)
        if (numObjects <= 0L || numObjects > 100_000L) return null
        val count = numObjects.toInt()
        val offsets = LongArray(count)
        for (i in 0 until count) {
            val at = offsetTableOffset.toInt() + i * offsetSize
            offsets[i] = readBeInt(data, at, offsetSize).toLong()
        }
        val cache = arrayOfNulls<Any?>(count)
        fun parse(idx: Int): Any? {
            if (idx < 0 || idx >= cache.size) return null
            cache[idx]?.let { return it }
            val pos = offsets[idx].toInt()
            if (pos < 0 || pos >= data.size) return null
            val marker = data[pos].toInt() and 0xff
            val type = marker ushr 4
            val info = marker and 0x0f
            when (type) {
                0x0 -> {
                    val v = when (info) {
                        0x0 -> null
                        0x8 -> false
                        0x9 -> true
                        else -> null
                    }
                    cache[idx] = v
                    return v
                }
                0x1 -> {
                    val size = 1 shl info
                    val v = readBeLongSized(data, pos + 1, size)
                    cache[idx] = v
                    return v
                }
                0x4 -> {
                    val (len, next) = readLength(data, pos, info)
                    val v = data.copyOfRange(next, next + len)
                    cache[idx] = v
                    return v
                }
                0x5 -> {
                    val (len, next) = readLength(data, pos, info)
                    val v = String(data, next, len, Charsets.US_ASCII)
                    cache[idx] = v
                    return v
                }
                0x6 -> {
                    val (len, next) = readLength(data, pos, info)
                    val v = String(data, next, len * 2, Charsets.UTF_16BE)
                    cache[idx] = v
                    return v
                }
                0xA -> {
                    val (len, next) = readLength(data, pos, info)
                    val list = ArrayList<Any?>(len)
                    cache[idx] = list
                    var p = next
                    repeat(len) {
                        val ref = readBeInt(data, p, objectRefSize)
                        p += objectRefSize
                        list.add(parse(ref))
                    }
                    return list
                }
                0xD -> {
                    val (len, next) = readLength(data, pos, info)
                    val map = LinkedHashMap<String, Any?>()
                    cache[idx] = map
                    var p = next
                    val keyRefs = IntArray(len)
                    for (i in 0 until len) {
                        keyRefs[i] = readBeInt(data, p, objectRefSize)
                        p += objectRefSize
                    }
                    val valRefs = IntArray(len)
                    for (i in 0 until len) {
                        valRefs[i] = readBeInt(data, p, objectRefSize)
                        p += objectRefSize
                    }
                    for (i in 0 until len) {
                        val k = parse(keyRefs[i])?.toString() ?: continue
                        map[k] = parse(valRefs[i])
                    }
                    return map
                }
                else -> return null
            }
        }
        return parse(topObject.toInt())
    }

    @Suppress("UNCHECKED_CAST")
    fun asDict(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    fun intOf(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        else -> null
    }

    private fun normalize(v: Any?): Any? = when (v) {
        null -> null
        is Boolean, is String, is ByteArray -> v
        is Int, is Long, is Short, is Byte -> (v as Number).toLong()
        is Float -> v.toDouble()
        is Double -> v
        is Map<*, *> -> {
            val out = LinkedHashMap<String, Any?>()
            for ((k, value) in v) {
                if (k != null) out[k.toString()] = normalize(value)
            }
            out
        }
        is List<*> -> v.map { normalize(it) }
        is Array<*> -> v.map { normalize(it) }
        else -> v.toString()
    }

    private class IdentityKey(private val value: Any?) {
        override fun equals(other: Any?): Boolean {
            if (other !is IdentityKey) return false
            val a = value
            val b = other.value
            return when {
                a === b -> true
                a is ByteArray && b is ByteArray -> a.contentEquals(b)
                a is Map<*, *> && b is Map<*, *> -> false
                a is List<*> && b is List<*> -> false
                else -> a == b
            }
        }

        override fun hashCode(): Int = when (value) {
            is ByteArray -> value.contentHashCode()
            is Map<*, *>, is List<*> -> System.identityHashCode(value)
            else -> value.hashCode()
        }
    }

    private fun writeMarkerLength(out: ByteArrayOutputStream, markerBase: Int, length: Int) {
        if (length < 15) {
            out.write(markerBase or length)
        } else {
            out.write(markerBase or 0x0f)
            when {
                length <= 0xff -> {
                    out.write(0x10)
                    out.write(length and 0xff)
                }
                length <= 0xffff -> {
                    out.write(0x11)
                    out.write(intBytes(length, 2))
                }
                else -> {
                    out.write(0x12)
                    out.write(intBytes(length, 4))
                }
            }
        }
    }

    private fun readLength(data: ByteArray, pos: Int, info: Int): Pair<Int, Int> {
        if (info < 15) return info to (pos + 1)
        val lenMarker = data[pos + 1].toInt() and 0xff
        val size = 1 shl (lenMarker and 0x0f)
        val len = readBeInt(data, pos + 2, size)
        return len to (pos + 2 + size)
    }

    private fun refSizeFor(n: Int): Int = when {
        n < 256 -> 1
        n < 65536 -> 2
        else -> 4
    }

    private fun intBytes(value: Int, size: Int): ByteArray {
        val out = ByteArray(size)
        for (i in 0 until size) {
            out[size - 1 - i] = ((value ushr (8 * i)) and 0xff).toByte()
        }
        return out
    }

    private fun longBytes(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()

    private fun readBeInt(data: ByteArray, at: Int, size: Int): Int {
        var v = 0
        for (i in 0 until size) {
            v = (v shl 8) or (data[at + i].toInt() and 0xff)
        }
        return v
    }

    private fun readBeLong(data: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (data[at + i].toLong() and 0xff)
        }
        return v
    }

    private fun readBeLongSized(data: ByteArray, at: Int, size: Int): Long {
        // Apple plist ints are notionally signed, but AirPlay/DiPlay encode ports and
        // similar fields as unsigned bit patterns in 1/2/4-byte ints (e.g. dataPort
        // 0xa085). Sign-extending those turns valid ports into negatives and breaks SETUP.
        var v = 0L
        for (i in 0 until size) {
            v = (v shl 8) or (data[at + i].toLong() and 0xff)
        }
        return v
    }
}
