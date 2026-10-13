package com.atomosremote.nativeapp.output

/**
 * Output R23: lossless, READ-ONLY diagnostic decoding of an official
 * AppPolling.OutputPageGet 0x0644 / 0x8644 payload.
 *
 * IMPORTANT: Protobuf wire field numbers are NOT semantic setting IDs.
 * Do not turn a varint 0 into "disabled" or a list into available options
 * until the actual macOS protobuf field-number schema or a controlled
 * recorder-side A/B test proves the mapping.
 */
data class OutputWireField(
    val number: Int,
    val wireType: Int,
    val value: String
)

data class OutputPageWireEvidence(
    val valid: Boolean,
    val bytes: Int,
    val fields: List<OutputWireField>,
    val error: String? = null
) {
    fun formatted(): String = if (!valid) "Invalid page: ${error ?: "unknown"}" else
        fields.joinToString("\n") { "#${it.number} wire=${it.wireType} ${it.value}" }
}

object OutputPageWireDecoder {
    fun decode(raw: String?): OutputPageWireEvidence {
        if (raw.isNullOrBlank()) return OutputPageWireEvidence(false, 0, emptyList(), "No typed page received")
        if (raw.length % 2 != 0 || !raw.all { it in "0123456789abcdefABCDEF" })
            return OutputPageWireEvidence(false, 0, emptyList(), "Malformed hexadecimal data")
        val data = try {
            ByteArray(raw.length / 2) { i -> raw.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (_: Exception) {
            return OutputPageWireEvidence(false, 0, emptyList(), "Hexadecimal conversion failed")
        }
        if (data.size > 4096) return OutputPageWireEvidence(false, data.size, emptyList(), "Page over safe size limit")
        val results = mutableListOf<OutputWireField>()
        var p = 0
        fun readVarint(): Long? {
            var shift = 0
            var value = 0L
            repeat(10) {
                if (p >= data.size) return null
                val b = data[p++].toInt() and 0xff
                // Protobuf varint is at most 64 bits and cannot overflow on tenth byte.
                if (it == 9 && b > 1) return null
                value = value or ((b.toLong() and 0x7f) shl shift)
                if ((b and 0x80) == 0) return value
                shift += 7
            }
            return null
        }
        fun invalid(message: String) = OutputPageWireEvidence(false, data.size, results, "$message at byte $p")
        while (p < data.size) {
            if (results.size >= 128) return invalid("Too many fields")
            val tag = readVarint() ?: return invalid("Invalid tag")
            val fieldNumber = tag ushr 3
            val wire = (tag and 7).toInt()
            if (fieldNumber < 1L || fieldNumber > 536870911L) return invalid("Invalid field number")
            val number = fieldNumber.toInt()
            val value = when (wire) {
                0 -> "uint=${readVarint() ?: return invalid("Invalid varint")}" // raw unsigned; NOT an enum label
                1 -> {
                    if (data.size - p < 8) return invalid("Short 64-bit fixed value")
                    val hex = data.copyOfRange(p, p + 8).joinToString("") { "%02X".format(it.toInt() and 255) }
                    p += 8
                    "fixed64=$hex"
                }
                2 -> {
                    val len = readVarint() ?: return invalid("Invalid length")
                    if (len < 0 || len > data.size - p) return invalid("Truncated length-delimited value")
                    val count = len.toInt()
                    val hex = data.copyOfRange(p, p + count).joinToString("") { "%02X".format(it.toInt() and 255) }
                    p += count
                    "len=$count hex=$hex"
                }
                5 -> {
                    if (data.size - p < 4) return invalid("Short 32-bit fixed value")
                    val hex = data.copyOfRange(p, p + 4).joinToString("") { "%02X".format(it.toInt() and 255) }
                    p += 4
                    "fixed32=$hex"
                }
                else -> return invalid("Unsupported wire type $wire")
            }
            results.add(OutputWireField(number, wire, value))
        }
        return OutputPageWireEvidence(true, data.size, results)
    }

    fun differences(before: String?, after: String?): List<String> {
        val a = decode(before)
        val b = decode(after)
        if (!a.valid || !b.valid) return emptyList()
        val left = a.fields.groupBy { it.number }.mapValues { e -> e.value.map { "${it.wireType}/${it.value}" } }
        val right = b.fields.groupBy { it.number }.mapValues { e -> e.value.map { "${it.wireType}/${it.value}" } }
        return (left.keys + right.keys).distinct().sorted().filter { left[it] != right[it] }.map { n ->
            "#$n  ${left[n]?.joinToString() ?: "<absent>"}  ->  ${right[n]?.joinToString() ?: "<absent>"}"
        }
    }
}
