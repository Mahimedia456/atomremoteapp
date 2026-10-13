package com.atomosremote.nativeapp.audio

/** R26.16. Strict read-only reconstruction from the two proven 0x8647 AG2
 * responses (39 and 40 bytes). The macOS x86_64 Swift field descriptors show
 * audio-related property names; tag->meaning remains a TEST HYPOTHESIS.
 * The individual Audio GET request classes each have one scalar field, and
 * their first-field wire payload is a protobuf varint CANDIDATE, not a
 * hardware-verified request format. Do not put any SET in this object.
 */
object AudioR26ProtoCandidates {
    private fun readVarint(bytes: ByteArray, pos: Int): Pair<Long, Int>? {
        var i = pos; var v = 0L; var shift = 0
        while (i < bytes.size && shift <= 63) {
            val c = bytes[i++].toInt() and 0xFF
            v = v or ((c.toLong() and 0x7F) shl shift)
            if (c and 0x80 == 0) return v to i
            shift += 7
        }
        return null
    }
    fun parsePage(rawHex: String): Map<Int, Long>? {
        if (rawHex.length !in 50..512 || rawHex.length % 2 != 0 ||
            !rawHex.all { it in "0123456789abcdefABCDEF" }) return null
        val bytes = ByteArray(rawHex.length / 2) { i ->
            rawHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val fields = linkedMapOf<Int, Long>()
        var pos = 0; var count = 0
        while (pos < bytes.size) {
            if (++count > 30) return null
            val tag = readVarint(bytes, pos) ?: return null
            pos = tag.second
            val number = (tag.first ushr 3).toInt()
            val wire = (tag.first and 7).toInt()
            if (number !in 1..11) return null
            when (wire) {
                0 -> {
                    val value = readVarint(bytes, pos) ?: return null
                    pos = value.second
                    if (number in fields) return null
                    fields[number] = value.first
                }
                2 -> {
                    val length = readVarint(bytes, pos) ?: return null
                    pos = length.second
                    if (length.first > (bytes.size - pos).toLong()) return null
                    pos += length.first.toInt()
                }
                else -> return null
            }
        }
        // Both recorder-received page versions use this same required varint subset.
        return if (fields.keys.containsAll(setOf(1, 2, 7, 8, 9, 10, 11)) && count == 11)
            fields else null
    }
    // Tentative field association cross-checked against Swift Audio page members.
    // NEVER treat these as calibrated audio dB or a verified hardware UI mapping.
    private val methodToPageTag = mapOf(
        "MonitorGet" to 1, "PhonesLevelGet" to 2,
        "OrientationGet" to 7, "AnalogEmbedGet" to 10
    )
    private val settingToPageTag = mapOf(
        "analogMonitor" to 1, "phones" to 2,
        "orientation" to 7, "embed" to 10
    )
    fun candidatePayload(method: String, pageHex: String): ByteArray? {
        val field = methodToPageTag[method] ?: return null
        val value = parsePage(pageHex)?.get(field) ?: return null
        if (value < 0 || value > 255L) return null
        val data = ArrayList<Byte>(); data.add(0x08.toByte())
        var n = value
        do {
            var c = (n and 0x7FL).toInt(); n = n ushr 7
            if (n != 0L) c = c or 0x80
            data.add(c.toByte())
        } while (n != 0L)
        return data.toByteArray()
    }
    fun pageHint(settingKey: String, pageHex: String): String? {
        val field = settingToPageTag[settingKey] ?: return null
        val raw = parsePage(pageHex)?.get(field) ?: return null
        return "page f$field raw=$raw (tentative)"
    }
}
