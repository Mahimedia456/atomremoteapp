package com.atomosremote.nativeapp.audio

/** Physical AG2 A/B: 0x06B3 local GET -> 0x86AE; 0801=Line Level, 0802=Mic Level.
 * Only the independent typed Mic/Line GET may enter this decoder. Zero sends.
 */
object AudioR2716InputLevelReadback {
    enum class Option { LINE_LEVEL, MIC_LEVEL, UNKNOWN }
    data class Readback(val rawEnum: Long, val option: Option) {
        fun display(): String = when (option) {
            Option.LINE_LEVEL -> "Line Level"
            Option.MIC_LEVEL -> "Mic Level"
            Option.UNKNOWN -> "Unknown recorder option ($rawEnum)"
        }
    }
    fun decodeHex(raw: String): Readback? {
        val s = raw.trim()
        if (s.isEmpty() || s.length % 2 != 0 || s.length > 22 ||
            !s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val bytes = ByteArray(s.length / 2) { i -> s.substring(2*i,2*i+2).toInt(16) .toByte() }
        if (bytes[0].toInt() and 0xff != 0x08) return null // only field 1 / varint
        var value = 0UL
        var complete = false
        for (i in 1 until bytes.size) {
            if (i > 10) return null
            val v = bytes[i].toInt() and 0xff
            if (i == 10 && v > 1) return null // unsigned 64-bit overflow
            value = value or ((v and 0x7f).toULong() shl (7 * (i - 1)))
            if ((v and 0x80) == 0) {
                if (i != bytes.lastIndex) return null // trailing / duplicate fields
                complete = true
                break
            }
        }
        if (!complete) return null
        val number = value.toLong()
        return Readback(number, when (value) {
            1UL -> Option.LINE_LEVEL
            2UL -> Option.MIC_LEVEL
            else -> Option.UNKNOWN
        })
    }
}
