package com.atomosremote.nativeapp.audio

/**
 * R26.04 / R26.05: non-destructive wire *inspection* and seven-pair UI contract.
 * This is NOT an AG2-proven AudioMetersPageState decoder. Nothing here turns an
 * arbitrary protobuf field into a dB value. Recording and SET are not touched.
 */
object AudioR26WireInspector {
    data class Field(val number: Int, val wireType: Int, val detail: String)
    data class Inspection(val status: String, val fields: List<Field>)

    fun inspect(rawHex: String): Inspection {
        if (rawHex.isEmpty()) return Inspection("NO_RESPONSE", emptyList())
        if (rawHex.length > 32768 || rawHex.length % 2 != 0) return Inspection("INVALID_HEX_LENGTH", emptyList())
        val bytes = try {
            ByteArray(rawHex.length / 2) { index ->
                rawHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            return Inspection("INVALID_HEX", emptyList())
        }
        val fields = ArrayList<Field>()
        var pos = 0
        fun readVarint(): Long? {
            var v = 0L
            var shift = 0
            for (i in 0 until 10) {
                if (pos >= bytes.size) return null
                val b = bytes[pos++].toInt() and 0xFF
                if (i == 9 && b > 1) return null
                v = v or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return v
                shift += 7
            }
            return null
        }
        while (pos < bytes.size) {
            if (fields.size >= 64) return Inspection("FIELD_LIMIT", fields)
            val tag = readVarint() ?: return Inspection("INVALID_TAG", fields)
            val number = tag ushr 3
            val wire = (tag and 7).toInt()
            if (number < 1 || number > 536870911) return Inspection("INVALID_FIELD_NUMBER", fields)
            val detail = when (wire) {
                0 -> {
                    val v = readVarint() ?: return Inspection("TRUNCATED_VARINT", fields)
                    "uintRaw=$v (not calibrated)"
                }
                1 -> {
                    if (bytes.size - pos < 8) return Inspection("TRUNCATED_FIXED64", fields)
                    val raw = bytes.copyOfRange(pos, pos + 8).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
                    pos += 8
                    "fixed64 hex=$raw (not interpreted)"
                }
                2 -> {
                    val n = readVarint() ?: return Inspection("TRUNCATED_LENGTH", fields)
                    if (n < 0 || n > bytes.size.toLong() - pos) return Inspection("TRUNCATED_BYTES", fields)
                    val len = n.toInt()
                    // Report exact wire bytes as evidence; never interpret as levels or channel IDs.
                    val hex = bytes.copyOfRange(pos, pos + len).joinToString("") {
                        "%02X".format(it.toInt() and 0xFF)
                    }
                    pos += len
                    "bytes=$n hex=$hex (semantics unverified)"
                }
                5 -> {
                    if (bytes.size - pos < 4) return Inspection("TRUNCATED_FIXED32", fields)
                    val raw = bytes.copyOfRange(pos, pos + 4).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
                    pos += 4
                    "fixed32 hex=$raw (not interpreted)"
                }
                else -> return Inspection("UNSUPPORTED_WIRE_TYPE_$wire", fields)
            }
            fields.add(Field(number.toInt(), wire, detail))
        }
        return Inspection("RAW_PARSED_UNMAPPED", fields)
    }

    // The 14-channel display contract exists; no acoustic values can be
    // instantiated until a tested official field map identifies each channel.
    val meterPairs: List<String> = listOf("Analog L / R", "1-2", "3-4", "5-6", "7-8", "9-10", "11-12")
    const val METER_VALUES_VERIFIED = false
}
