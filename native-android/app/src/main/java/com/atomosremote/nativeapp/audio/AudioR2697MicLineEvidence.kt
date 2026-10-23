package com.atomosremote.nativeapp.audio

/** R26.97 strictly request-scoped, experimental Mic/Line GET evidence.
 * No aggregate 0x8647 Audio page is accepted as an independent Mic/Line value.
 * Class labels and candidate opcodes are NOT a verified protobuf field map.
 */
data class AudioR2697MicLineRecord(
    val key: String,
    val command: Int,
    val requestToken: Long,
    val state: String,
    val responseType: Int? = null,
    val rawHex: String = "",
    val detail: String = ""
)
data class AudioR2697MicLineSnapshot(
    val controls: Map<String, AudioR2697MicLineRecord> = emptyMap()
)
object AudioR2697MicLineEvidence {
    fun getOpcode(key: String): Int? = when (key) {
        "gainLeft", "gainRight" -> 0x06B6 // macOS Audio poll read, NOT 0x06B7 setter
        "delay" -> 0x06B9 // macOS Audio poll read, NOT 0x06BA setter
        "inputLevel" -> 0x06B3 // macOS Audio poll read, NOT 0x06B4 setter
        else -> null
    }

    /** A purpose-correlated packet is still only CANDIDATE RX until schema is proven.
     * An error response, unrelated opcode, empty body or 0x8647 aggregate page
     * must never be presented as a device gain, delay or input-source scalar.
     */
    fun classify(rx: AudioR2697MicLineRecord, reply: Int, bodyHex: String, local: Boolean): AudioR2697MicLineRecord {
        val normalized = reply and 0x7FFF
        val status = when {
            reply == 0xFFFF || reply == 0xFFFE -> "RECORDER_REJECT"
            !local && rx.command !in setOf(0x06B3, 0x06B6, 0x06B9) -> "WRONG_ROUTE"
            reply == 0x8000 && bodyHex.isEmpty() -> "GENERIC_ACK_NO_VALUE"
            AudioR2709MacDispatch.expectedReplyBase(rx.command)?.let { expected ->
                (reply and 0x0FFF) != expected
            } != false -> "UNEXPECTED_RX"
            bodyHex.isEmpty() -> "EMPTY_RX"
            else -> "CANDIDATE_RX_UNMAPPED"
        }
        return rx.copy(state = status, responseType = reply,
            rawHex = if (status == "CANDIDATE_RX_UNMAPPED") bodyHex else "",
            detail = "Mic/Line GET candidate: no verified parameter/channel mapping or dB/frame scale")
    }
}
