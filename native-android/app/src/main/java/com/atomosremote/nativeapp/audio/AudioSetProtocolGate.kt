package com.atomosremote.nativeapp.audio

/**
 * B01.18 offline protocol evidence gate. Deliberately NOT connected to BLE dispatch.
 * Do not convert any candidate into a wire request without official serialization evidence
 * and physical recorder GET-before/GET-after verification.
 */
internal object AudioSetProtocolGate {
    enum class Control { HEADPHONE_LEVEL, METER_ORIENTATION, AUDIO_EMBEDDED, CHANNEL_REC, ANALOG_MONITOR }
    enum class Confidence { ADJACENCY_ONLY, EXECUTABLE_COMMAND_STORE, VERIFIED_WIRE_AND_HARDWARE }
    data class Evidence(
        val control: Control,
        val candidateOpcode: Int?,
        val confidence: Confidence,
        val verifiedPayload: ByteArray?,
        val verifiedAck: Boolean,
        val verifiedGetAfter: Boolean
    ) {
        fun mayTransmit(): Boolean =
            confidence == Confidence.VERIFIED_WIRE_AND_HARDWARE &&
                verifiedPayload != null && verifiedAck && verifiedGetAfter
    }

    private val entries = mapOf(
        Control.HEADPHONE_LEVEL to Evidence(Control.HEADPHONE_LEVEL, 0x06AC, Confidence.ADJACENCY_ONLY, null, false, false),
        Control.METER_ORIENTATION to Evidence(Control.METER_ORIENTATION, 0x06B0, Confidence.ADJACENCY_ONLY, null, false, false),
        Control.AUDIO_EMBEDDED to Evidence(Control.AUDIO_EMBEDDED, 0x06B2, Confidence.ADJACENCY_ONLY, null, false, false),
        Control.CHANNEL_REC to Evidence(Control.CHANNEL_REC, 0x06A7, Confidence.EXECUTABLE_COMMAND_STORE, null, false, false),
        Control.ANALOG_MONITOR to Evidence(Control.ANALOG_MONITOR, 0x06A6, Confidence.ADJACENCY_ONLY, null, false, false)
    )
    fun snapshot(): List<Evidence> = Control.entries.map { entries.getValue(it) }
    fun isAnySetReleaseReady(): Boolean = snapshot().any { it.mayTransmit() }
}
