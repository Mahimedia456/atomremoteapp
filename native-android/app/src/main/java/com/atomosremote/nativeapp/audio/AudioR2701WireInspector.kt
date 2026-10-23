package com.atomosremote.nativeapp.audio

/**
 * R27.01 read-only protobuf wire inspection. Field numbers are printed only;
 * NO gain/delay/enum semantics are inferred, and NO GET/SET commands are sent.
 * This is intentionally independent of Android so it can be fixture-tested.
 */
object AudioR2701WireInspector {
    fun describe(data: ByteArray): String {
        if (data.isEmpty()) return "EMPTY"
        if (data.size > 4096) return "OVERSIZE"
        val parts = mutableListOf<String>()
        var p = 0
        fun readVarint(): Long? {
            var v = 0L
            for (i in 0 until 10) {
                if (p >= data.size) return null
                val x = data[p++].toInt() and 0xff
                if (i == 9 && x > 1) return null
                v = v or ((x and 0x7f).toLong() shl (i * 7))
                if ((x and 0x80) == 0) return v
            }
            return null
        }
        while (p < data.size) {
            if (parts.size >= 64) return "TOO_MANY_FIELDS"
            val start = p
            val key = readVarint() ?: return "MALFORMED_TAG@$start"
            val field = key ushr 3
            val kind = (key and 7).toInt()
            if (field == 0L || field > 2047L) return "INVALID_TAG@$start"
            val representation = when(kind) {
                0 -> {
                    val value = readVarint() ?: return "MALFORMED_VARINT@$start"
                    "v=" + java.lang.Long.toUnsignedString(value)
                }
                1 -> { if (data.size - p < 8) return "TRUNCATED_64@$start"; p += 8; "fixed64" }
                2 -> {
                    val n = readVarint() ?: return "BAD_LENGTH@$start"
                    if (n < 0 || n > data.size.toLong() - p) return "TRUNCATED_BYTES@$start"
                    p += n.toInt()
                    "bytes=" + n
                }
                5 -> { if (data.size - p < 4) return "TRUNCATED_32@$start"; p += 4; "fixed32" }
                else -> return "UNSUPPORTED_WIRE_KIND_$kind@$start"
            }
            parts.add("f$field/w$kind:$representation")
        }
        return parts.joinToString(";")
    }
}
