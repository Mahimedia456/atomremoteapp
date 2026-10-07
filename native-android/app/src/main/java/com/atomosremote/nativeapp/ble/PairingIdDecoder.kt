package com.atomosremote.nativeapp.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Conservative Pairing-ID decoder used during ATOMOS discovery.
 *
 * ATOMOS' public documentation shows a 9-digit Pairing ID in the UI but does not
 * publish its BLE encoding. We therefore treat only human-readable 9-digit text
 * (ASCII/UTF-8/UTF-16) as high confidence. Binary interpretations are exposed as
 * candidates for comparison with the recorder and are never accepted automatically.
 */
object PairingIdDecoder {
    data class Result(
        val highConfidence: String? = null,
        val candidates: List<String> = emptyList(),
        val evidence: List<String> = emptyList()
    )

    private val pairingRegex = Regex("(?<!\\d)(\\d{3})[- ]?(\\d{3})[- ]?(\\d{3})(?!\\d)")

    fun decode(vararg payloads: ByteArray?): Result = decode(payloads.filterNotNull())

    fun decode(payloads: Iterable<ByteArray>): Result {
        val high = linkedSetOf<String>()
        val candidates = linkedSetOf<String>()
        val evidence = mutableListOf<String>()

        payloads.forEachIndexed { index, bytes ->
            if (bytes.isEmpty()) return@forEachIndexed
            val tag = "payload#$index"

            val textViews = listOf(
                "UTF-8" to runCatching { bytes.toString(Charsets.UTF_8) }.getOrDefault(""),
                "ASCII" to runCatching { bytes.toString(Charsets.US_ASCII) }.getOrDefault(""),
                "UTF-16LE" to decodeUtf16(bytes, Charsets.UTF_16LE),
                "UTF-16BE" to decodeUtf16(bytes, Charsets.UTF_16BE)
            )

            textViews.forEach { (encoding, text) ->
                pairingRegex.findAll(text).forEach { match ->
                    val id = format(match.groupValues[1] + match.groupValues[2] + match.groupValues[3])
                    high += id
                    evidence += "$tag:$encoding:$id"
                }
            }

            // Binary candidates are deliberately conservative and never become authoritative.
            decodeWholeUInt32(bytes, ByteOrder.LITTLE_ENDIAN)?.let {
                candidates += it
                evidence += "$tag:uint32-le:$it"
            }
            decodeWholeUInt32(bytes, ByteOrder.BIG_ENDIAN)?.let {
                candidates += it
                evidence += "$tag:uint32-be:$it"
            }
            decodeUInt32Modulo(bytes, ByteOrder.LITTLE_ENDIAN)?.let {
                candidates += it
                evidence += "$tag:uint32-le-mod1e9:$it"
            }
            decodeUInt32Modulo(bytes, ByteOrder.BIG_ENDIAN)?.let {
                candidates += it
                evidence += "$tag:uint32-be-mod1e9:$it"
            }
            decodePackedBcd(bytes)?.let {
                candidates += it
                evidence += "$tag:bcd:$it"
            }
        }

        candidates.removeAll(high)
        return Result(
            highConfidence = high.firstOrNull(),
            candidates = (high + candidates).take(12),
            evidence = evidence.take(24)
        )
    }

    private fun decodeUtf16(bytes: ByteArray, charset: Charset): String {
        if (bytes.size < 4 || bytes.size % 2 != 0) return ""
        return runCatching { bytes.toString(charset) }.getOrDefault("")
    }

    private fun decodeWholeUInt32(bytes: ByteArray, order: ByteOrder): String? {
        if (bytes.size != 4) return null
        val unsigned = ByteBuffer.wrap(bytes).order(order).int.toLong() and 0xFFFF_FFFFL
        if (unsigned !in 1..999_999_999L) return null
        return format(unsigned.toString().padStart(9, '0'))
    }

    private fun decodeUInt32Modulo(bytes: ByteArray, order: ByteOrder): String? {
        if (bytes.size != 4) return null
        val unsigned = ByteBuffer.wrap(bytes).order(order).int.toLong() and 0xFFFF_FFFFL
        if (unsigned <= 999_999_999L) return null
        val reduced = unsigned % 1_000_000_000L
        if (reduced == 0L) return null
        return format(reduced.toString().padStart(9, '0'))
    }

    private fun decodePackedBcd(bytes: ByteArray): String? {
        if (bytes.size != 5) return null
        val nibbles = mutableListOf<Int>()
        bytes.forEach { b ->
            nibbles += (b.toInt() ushr 4) and 0x0F
            nibbles += b.toInt() and 0x0F
        }
        // Allow one leading/trailing F filler nibble around exactly nine decimal digits.
        val digits = when {
            nibbles.firstOrNull() == 0x0F && nibbles.drop(1).all { it in 0..9 } -> nibbles.drop(1)
            nibbles.lastOrNull() == 0x0F && nibbles.dropLast(1).all { it in 0..9 } -> nibbles.dropLast(1)
            else -> return null
        }
        if (digits.size != 9) return null
        return format(digits.joinToString(""))
    }

    private fun format(digits: String): String {
        val clean = digits.filter(Char::isDigit).padStart(9, '0').takeLast(9)
        return "${clean.substring(0, 3)}-${clean.substring(3, 6)}-${clean.substring(6, 9)}"
    }
}
