package com.atomosremote.nativeapp.audio

/** R26.37-39: AG2-specific, read-only calibration of Audio page protobuf f11.
 * The only safe transport request remains EMPTY BODY 0x0647, expecting 0x8647.
 * This helper never writes to BLE, never invokes PacketLink and cannot SET a value.
 */
object AudioR2637_39AnalogEmbed {
    const val PAGE_GET = 0x0647
    const val PAGE_RX = 0x8647
    // macOS constructor IDs, *not* evidence of the request body's serialized bytes.
    const val MACOS_ANALOG_EMBED_GET = 0x06B1
    const val MACOS_ANALOG_EMBED_SET = 0x06B2
    const val INDIVIDUAL_GET_WIRE_PROVEN = false
    const val INDIVIDUAL_SET_WIRE_PROVEN = false

    enum class Confidence { HARDWARE_CALIBRATED, UNKNOWN_OPTION }
    data class Readback(val raw: Long, val display: String, val confidence: Confidence)

    /** f11 is inferred from macOS field metadata and corroborated by AG2 one-setting
     * A/B tests on 2026-10-14: Audio Embedded 1/2 -> Off produced f11 1 -> 0.
     * f11=2 was observed, but its exact AG2 display option has not been verified.
     */
    fun fromPage(pageHex: String): Readback? {
        val page = AudioR26PageSnapshot.decode(pageHex) ?: return null
        return when (page.analogEmbed) {
            0L -> Readback(0L, "Off", Confidence.HARDWARE_CALIBRATED)
            1L -> Readback(1L, "1/2", Confidence.HARDWARE_CALIBRATED)
            else -> Readback(page.analogEmbed, "Option ${page.analogEmbed} (unmapped)", Confidence.UNKNOWN_OPTION)
        }
    }

    fun mainPageRow(pageHex: String): Pair<String, String>? = fromPage(pageHex)?.let {
        "Audio Embedded (AG2-calibrated f11)" to "${it.display} [f11=${it.raw}]"
    }
    fun compactLabel(pageHex: String): String? = fromPage(pageHex)?.let {
        if (it.confidence == Confidence.HARDWARE_CALIBRATED) it.display
        else "${it.raw} raw (unmapped)"
    }
    /** No individual Audio GET or SET is permitted before its *full* bytes and
     * matched AG2 responses are independently verified. */
    fun isSafeAudioRequest(opcode: Int, payload: ByteArray): Boolean =
        opcode == PAGE_GET && payload.isEmpty()
    fun isAnalogEmbedSetAllowed(@Suppress("UNUSED_PARAMETER") payload: ByteArray): Boolean = false
}
