package com.atomosremote.nativeapp.ui.settings

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * S08: passive wire-format inspector of packet-correlated DeviceInfo GET data.
 * Field NUMBERS and wire types are shown, never interpreted as confirmed
 * firmware, device ID, model, date/time or recorder capabilities.
 * This object performs no Bluetooth/GATT operations or writes.
 */
internal object SettingsS08DeviceInfoFields {
    internal data class Field(val number: Int, val kind: String, val display: String)
    internal data class Inspection(
        val decoded: Boolean,
        val fields: List<Field> = emptyList(),
        val note: String = "",
        val byteCount: Int = 0
    )

    fun inspect(rawHex: String): Inspection {
        if (rawHex.isEmpty()) return Inspection(false, note = "Empty typed reply; no fields to inspect")
        if (rawHex.length % 2 != 0 || rawHex.length > 8192 || !rawHex.all {
                it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
            }) return Inspection(false, note = "Invalid or oversized wire bytes")
        val bytes = ByteArray(rawHex.length / 2) { i ->
            rawHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        if (bytes.size > 4096) return Inspection(false, note = "Wire data exceeds inspection limit", byteCount = bytes.size)
        var index = 0
        val fields = ArrayList<Field>()
        fun getVarint(): Long? {
            var value = 0L
            for (shiftIndex in 0..9) {
                if (index >= bytes.size) return null
                val b = bytes[index++].toInt() and 0xFF
                if (shiftIndex == 9 && b > 1) return null
                value = value or ((b and 0x7F).toLong() shl (shiftIndex * 7))
                if ((b and 0x80) == 0) return value
            }
            return null
        }
        fun failed(): Inspection = Inspection(
            decoded = false, fields = emptyList(), byteCount = bytes.size,
            note = "Payload does not form a complete supported protobuf wire message; retaining raw bytes only"
        )
        while (index < bytes.size) {
            if (fields.size >= 128) return failed()
            val key = getVarint() ?: return failed()
            val fieldNumber = key ushr 3
            val wireType = (key and 7L).toInt()
            if (fieldNumber !in 1..536870911L) return failed()
            val number = fieldNumber.toInt()
            when (wireType) {
                0 -> {
                    val numberValue = getVarint() ?: return failed()
                    fields.add(Field(number, "varint", "Unsigned raw: ${numberValue.toULong()} (schema not assigned)"))
                }
                1 -> {
                    if (bytes.size - index < 8) return failed()
                    val raw = bytes.copyOfRange(index, index + 8)
                    index += 8
                    fields.add(Field(number, "fixed64", "LE raw: ${raw.toHex()}"))
                }
                2 -> {
                    val lengthLong = getVarint() ?: return failed()
                    if (lengthLong < 0 || lengthLong > bytes.size - index) return failed()
                    val length = lengthLong.toInt()
                    val raw = bytes.copyOfRange(index, index + length)
                    index += length
                    val printable = printableText(raw)
                    val display = buildString {
                        append("$length bytes, hex=")
                        append(raw.toHex().take(96))
                        if (raw.size > 48) append("…")
                        if (printable != null) append(" | possible UTF-8 text: \"$printable\"")
                    }
                    fields.add(Field(number, "length-delimited", display))
                }
                5 -> {
                    if (bytes.size - index < 4) return failed()
                    val raw = bytes.copyOfRange(index, index + 4)
                    index += 4
                    fields.add(Field(number, "fixed32", "LE raw: ${raw.toHex()}"))
                }
                else -> return failed() // groups and unknown wire types deliberately unsupported
            }
        }
        return Inspection(
            decoded = true, fields = fields, byteCount = bytes.size,
            note = "Wire layout only. No field is mapped to a recorder Settings property."
        )
    }

    private fun printableText(bytes: ByteArray): String? {
        if (bytes.size !in 3..64) return null
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val result = try { decoder.decode(ByteBuffer.wrap(bytes)).toString() } catch (_: Exception) { return null }
        return if (result.all { !it.isISOControl() } && result.any { it.isLetterOrDigit() }) result else null
    }

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}
