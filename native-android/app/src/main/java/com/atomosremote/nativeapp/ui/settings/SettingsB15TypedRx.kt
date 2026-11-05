package com.atomosremote.nativeapp.ui.settings

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** B15: pure, read-only decoding of proven AG2 General replies. No BLE or SET APIs. */
internal object SettingsB15TypedRx {
    private data class Field(val tag: Int, val kind: Int, val number: Long?, val raw: ByteArray?)
    private val datePattern = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
    private val dateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT)

    private fun fields(hex: String): List<Field>? {
        if (hex.isEmpty() || hex.length % 2 != 0 || hex.length > 4096 ||
            !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val src = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        var pos = 0
        fun varint(): Long? {
            var out = 0L
            for (shift in 0..63 step 7) {
                if (pos >= src.size) return null
                val b = src[pos++].toInt() and 0xFF
                if (shift == 63 && b > 1) return null
                out = out or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) return out
            }
            return null
        }
        val found = mutableListOf<Field>()
        while (pos < src.size) {
            if (found.size >= 32) return null
            val key = varint() ?: return null
            if (key <= 0 || key > Int.MAX_VALUE) return null
            val fieldNum = (key ushr 3).toInt()
            val kind = (key and 7).toInt()
            if (fieldNum <= 0 || found.any { it.tag == fieldNum }) return null
            when (kind) {
                0 -> found += Field(fieldNum, kind, varint() ?: return null, null)
                2 -> {
                    val size = varint() ?: return null
                    if (size < 0 || size > src.size - pos) return null
                    val len = size.toInt()
                    val value = src.copyOfRange(pos, pos + len)
                    pos += len
                    found += Field(fieldNum, kind, null, value)
                }
                else -> return null
            }
        }
        return found
    }
    private fun text(f: Field?): String? {
        if (f == null || f.kind != 2) return null
        val bytes = f.raw ?: return null
        if (bytes.isEmpty() || bytes.any { (it.toInt() and 0xff) !in 32..126 }) return null
        return String(bytes, Charsets.US_ASCII)
    }

    /** Keys are recorder-originated fields; numeric DateFormat enums intentionally remain unlabeled. */
    fun decode(requestCommand: Int, rawHex: String): Map<String, String>? {
        val all = fields(rawHex) ?: return null
        val map = all.associateBy { it.tag }
        return when (requestCommand) {
            0x04B9 -> {
                if (all.size != 1) return null
                val timestamp = text(map[1]) ?: return null
                if (!datePattern.matches(timestamp)) return null
                try { LocalDateTime.parse(timestamp, dateFormat) } catch (_: Exception) { return null }
                mapOf("dateTime" to timestamp)
            }
            0x04BB -> {
                if (all.size != 2 || map[1]?.kind != 0 || map[2]?.kind != 0) return null
                val a = map[1]?.number ?: return null
                val b = map[2]?.number ?: return null
                if (a !in 0..255 || b !in 0..255) return null
                mapOf("dateFormatRaw" to "f1=$a / f2=$b")
            }
            0x04B0 -> {
                if (map.keys != setOf(1, 2, 3, 5, 6)) return null
                val did = text(map[1]) ?: return null
                val f2 = text(map[2]) ?: return null
                val model = text(map[3]) ?: return null
                val f5 = text(map[5]) ?: return null
                val f6 = text(map[6]) ?: return null
                if (did.length !in 4..40 || model.length !in 3..80) return null
                mapOf("deviceId" to did, "deviceField2" to f2, "model" to model,
                    "deviceField5" to f5, "deviceField6" to f6)
            }
            else -> null
        }
    }
}
