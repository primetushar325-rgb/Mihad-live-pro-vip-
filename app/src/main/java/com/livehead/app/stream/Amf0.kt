package com.livehead.app.stream

import java.io.ByteArrayOutputStream

/**
 * Minimal AMF0 encoder/decoder — enough for the RTMP command set used by
 * YouTube ingest (connect / createStream / publish / onStatus / _result).
 *
 * Pure JVM: no Android imports, so it is unit-testable on a desktop.
 */
object Amf0 {

    private const val MARKER_NUMBER = 0x00
    private const val MARKER_BOOLEAN = 0x01
    private const val MARKER_STRING = 0x02
    private const val MARKER_OBJECT = 0x03
    private const val MARKER_NULL = 0x05
    private const val MARKER_UNDEFINED = 0x06
    private const val MARKER_ECMA_ARRAY = 0x08
    private const val MARKER_OBJECT_END = 0x09

    class Writer {
        private val out = ByteArrayOutputStream(256)

        fun number(v: Double): Writer {
            out.write(MARKER_NUMBER)
            val bits = java.lang.Double.doubleToLongBits(v)
            for (shift in 56 downTo 0 step 8) out.write(((bits ushr shift) and 0xFF).toInt())
            return this
        }

        fun boolean(v: Boolean): Writer {
            out.write(MARKER_BOOLEAN)
            out.write(if (v) 1 else 0)
            return this
        }

        fun string(v: String): Writer {
            out.write(MARKER_STRING)
            utf8(v)
            return this
        }

        fun nil(): Writer {
            out.write(MARKER_NULL)
            return this
        }

        fun obj(props: Map<String, Any?>): Writer {
            out.write(MARKER_OBJECT)
            for ((k, v) in props) {
                utf8(k)
                value(v)
            }
            // empty key + object-end marker
            out.write(0); out.write(0); out.write(0)
            out.write(MARKER_OBJECT_END)
            return this
        }

        fun value(v: Any?): Writer = when (v) {
            null -> nil()
            is Double -> number(v)
            is Float -> number(v.toDouble())
            is Int -> number(v.toDouble())
            is Long -> number(v.toDouble())
            is Boolean -> boolean(v)
            is String -> string(v)
            is Map<*, *> -> obj(v as Map<String, Any?>)
            is List<*> -> throw IllegalArgumentException("AMF0 strict array not supported")
            else -> throw IllegalArgumentException("Unsupported AMF0 value: ${v::class.java}")
        }

        private fun utf8(v: String) {
            val b = v.toByteArray(Charsets.UTF_8)
            out.write((b.size shr 8) and 0xFF)
            out.write(b.size and 0xFF)
            out.write(b, 0, b.size)
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    /** Encodes a complete RTMP command: "name", txnId, args... */
    fun command(name: String, txnId: Int, args: List<Any?>): ByteArray {
        val w = Writer()
        w.string(name)
        w.number(txnId.toDouble())
        for (a in args) w.value(a)
        return w.bytes()
    }

    class Reader(private val data: ByteArray, private var pos: Int) {

        fun hasMore(): Boolean = pos < data.size

        fun readValue(): Any? {
            if (pos >= data.size) throw IllegalArgumentException("AMF0: EOF")
            val marker = data[pos++].toInt() and 0xFF
            return when (marker) {
                MARKER_NUMBER -> {
                    var bits = 0L
                    repeat(8) { bits = (bits shl 8) or ((data[pos++].toLong() and 0xFF)) }
                    java.lang.Double.longBitsToDouble(bits)
                }
                MARKER_BOOLEAN -> (data[pos++].toInt() and 0xFF) != 0
                MARKER_STRING -> readStringBody()
                MARKER_NULL, MARKER_UNDEFINED -> null
                MARKER_OBJECT -> readObjectBody(Int.MAX_VALUE)
                MARKER_ECMA_ARRAY -> {
                    // 4-byte count, then ECMA object body
                    skip(4)
                    readObjectBody(Int.MAX_VALUE)
                }
                else -> throw IllegalArgumentException("AMF0: unsupported marker 0x${marker.toString(16)}")
            }
        }

        fun readObjectBody(maxEntries: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            while (true) {
                if (pos + 2 > data.size) return map
                val keyLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                pos += 2
                if (keyLen == 0) {
                    if (pos < data.size) pos++ // skip object-end marker
                    return map
                }
                if (pos + keyLen > data.size) return map
                val key = String(data, pos, keyLen, Charsets.UTF_8)
                pos += keyLen
                if (pos < data.size && (data[pos].toInt() and 0xFF) == MARKER_OBJECT_END) {
                    pos++
                    return map
                }
                if (map.size >= maxEntries) { skipValue(); continue }
                map[key] = try {
                    readValue()
                } catch (t: Throwable) {
                    null
                }
            }
        }

        private fun readStringBody(): String {
            val len = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2
            val s = String(data, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }

        private fun skipValue() {
            // Skip a value we do not want to store: parse and discard.
            readValue()
        }

        private fun skip(n: Int) {
            pos = minOf(pos + n, data.size)
        }
    }

    /** Decodes the value list of a command message. */
    fun decode(data: ByteArray, maxValues: Int = 16): List<Any?> {
        val r = Reader(data, 0)
        val out = ArrayList<Any?>(4)
        while (r.hasMore() && out.size < maxValues) {
            out.add(try { r.readValue() } catch (t: Throwable) { break })
        }
        return out
    }
}
