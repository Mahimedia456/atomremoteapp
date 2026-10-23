package com.atomosremote.nativeapp.audio

/** R27.14: read-only interpretation of the proved AG2 0x06B6 -> 0x86B1 reply.
 * The confirmed physical observations are (0,0), (-2,0), and (0,-2) for
 * (0,0 dB), (-1,0 dB) and (0,-1 dB) respectively. Other gains are provisional.
 * This has NO transport dependencies and NEVER transmits SETs.
 */
object AudioR2714GainReadback {
    data class Pair(val leftRaw: Long, val rightRaw: Long) {
        fun leftLabel(): String = label(leftRaw)
        fun rightLabel(): String = label(rightRaw)
        fun fullyCalibrated(): Boolean = known(leftRaw) && known(rightRaw)
    }
    private fun known(raw: Long): Boolean = raw == 0L || raw == -2L
    private fun label(raw: Long): String {
        if (raw < -240L || raw > 240L) return "raw $raw (range unverified)"
        val db = java.lang.String.format(java.util.Locale.US, "%.1f dB", raw.toDouble() / 2.0)
        return if (known(raw)) db else "~$db"
    }
    private fun parseVarint(bytes: ByteArray, offset: Int): kotlin.Pair<Long, Int>? {
        var result = 0UL
        for (i in 0 until 10) {
            val j = offset + i
            if (j >= bytes.size) return null
            val b = bytes[j].toInt() and 0xff
            if (i == 9 && (b and 0xfe) != 0) return null // 64-bit overflow
            result = result or ((b and 0x7f).toULong() shl (7 * i))
            if ((b and 0x80) == 0) return result.toLong() to (j + 1)
        }
        return null // truncated/too long
    }
    /** Reject duplicate/missing/unknown fields and unsupported wire types.
     * More complex replies remain RAW, never mistakenly converted to dB.
     */
    fun decode(body: ByteArray): Pair? {
        if (body.isEmpty() || body.size > 48) return null
        var offset = 0
        var left: Long? = null
        var right: Long? = null
        while (offset < body.size) {
            val key = parseVarint(body, offset) ?: return null
            offset = key.second
            val value = parseVarint(body, offset) ?: return null
            offset = value.second
            when (key.first) {
                8L -> { if (left != null) return null; left = value.first }
                16L -> { if (right != null) return null; right = value.first }
                else -> return null
            }
        }
        return if (left != null && right != null) Pair(left, right) else null
    }
    fun decodeHex(raw: String): Pair? {
        val hex = raw.trim()
        if (hex.length % 2 != 0 || hex.length > 96 || hex.isEmpty() ||
            !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val bytes = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        return decode(bytes)
    }
}
