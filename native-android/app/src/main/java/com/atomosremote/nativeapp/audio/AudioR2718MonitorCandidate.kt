package com.atomosremote.nativeapp.audio

/** Recorder Audio 0x8647 page field 1, observed as headphone source ID.
 * A12.73 UI rule: 0=REC/Mic-Line => ON, nonzero source (1..7) => OFF.
 * This decoder remains GET-ONLY; no SET is implied by source equality.
 */
internal object AudioR2718MonitorCandidate {
    enum class Icon { ON_CANDIDATE, OFF_CANDIDATE, UNKNOWN }
    data class Readback(val icon: Icon, val rawField1: Long?, val reason: String)

    fun decode(hex: String): Readback {
        val cleaned = hex.trim()
        if (cleaned.isEmpty() || cleaned.length % 2 != 0 || cleaned.length > 2048 ||
            !cleaned.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' })
            return Readback(Icon.UNKNOWN, null, "Invalid or missing page hex")
        val bytes = try { ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        } } catch (_: Exception) { return Readback(Icon.UNKNOWN, null, "Hex decode failed") }
        var offset = 0
        var field1: Long? = null
        try {
            while (offset < bytes.size) {
                val tag = varint(bytes, offset) ?: return Readback(Icon.UNKNOWN, null, "Bad field tag")
                offset = tag.second
                val field = (tag.first ushr 3).toInt()
                val wire = (tag.first and 7L).toInt()
                if (field < 1 || field > 2047) return Readback(Icon.UNKNOWN, null, "Invalid field index")
                when (wire) {
                    0 -> {
                        val v = varint(bytes, offset) ?: return Readback(Icon.UNKNOWN, null, "Bad varint")
                        offset = v.second
                        if (field == 1) {
                            if (field1 != null) return Readback(Icon.UNKNOWN, null, "Duplicate monitor field")
                            field1 = v.first
                        }
                    }
                    1 -> { if (offset + 8 > bytes.size) return Readback(Icon.UNKNOWN, null, "Truncated fixed64"); offset += 8 }
                    2 -> {
                        val len = varint(bytes, offset) ?: return Readback(Icon.UNKNOWN, null, "Bad length")
                        offset = len.second
                        if (len.first < 0 || len.first > (bytes.size - offset).toLong())
                            return Readback(Icon.UNKNOWN, null, "Truncated nested field")
                        offset += len.first.toInt()
                    }
                    5 -> { if (offset + 4 > bytes.size) return Readback(Icon.UNKNOWN, null, "Truncated fixed32"); offset += 4 }
                    else -> return Readback(Icon.UNKNOWN, null, "Unsupported wire type")
                }
                if (field == 1 && wire != 0) return Readback(Icon.UNKNOWN, null, "Wrong field1 wire type")
            }
        } catch (_: Exception) { return Readback(Icon.UNKNOWN, null, "Unexpected decode failure") }
        // R27.17 observed H0/H3 ON=0; H1/H2 OFF=1. Initial B0 contradicts this.
        return when (field1) {
            0L -> Readback(Icon.ON_CANDIDATE, 0, "R27.17 candidate, B0 contradiction")
            in 1L..7L -> Readback(Icon.OFF_CANDIDATE, field1, "Non-REC headphone source selected")
            else -> Readback(Icon.UNKNOWN, field1, "Unmapped raw monitor value")
        }
    }

    private fun varint(b: ByteArray, start: Int): Pair<Long, Int>? {
        var out = 0L
        var index = start
        for (i in 0 until 10) {
            if (index >= b.size) return null
            val octet = b[index++].toInt() and 0xFF
            if (i == 9 && (octet and 0xFE) != 0) return null
            out = out or ((octet and 0x7F).toLong() shl (i * 7))
            if (octet and 0x80 == 0) return out to index
        }
        return null
    }
}
