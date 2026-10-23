package com.atomosremote.nativeapp.audio

/** Ledger of independent hardware evidence. Offline class/opcode discovery is not
 * permission to send recorder control bytes. This class has NO transport side effects.
 */
object AudioR2695Proof {
    data class WireContract(
        val key: String,
        val getMethod: String,
        val setMethod: String,
        val getterConstructorId: Int?,
        val setterCandidateId: Int?,
        val getTypedResponseProven: Boolean,
        val setSerializedPayloadProven: Boolean,
        val setAckAndReadbackProven: Boolean
    ) {
        val maySet: Boolean get() = getTypedResponseProven &&
            setSerializedPayloadProven && setAckAndReadbackProven
    }
    val controls = listOf(
        WireContract("phones", "PhonesLevelGet", "PhonesLevelSet", 0x06AB, 0x06AC, false, false, false),
        WireContract("monitor", "MonitorGet", "MonitorSet", 0x06A5, 0x06A6, false, false, false),
        WireContract("orientation", "OrientationGet", "OrientationSet", 0x06AF, 0x06B0, false, false, false),
        WireContract("embed", "AnalogEmbedGet", "AnalogEmbedSet", 0x06B1, 0x06B2, false, false, false),
        WireContract("rec", "RecordEnabledListGet", "RecordSet", 0x06A9, 0x06A8, false, false, false),
        WireContract("dropouts", "AudioFillGet", "AudioFillSet", 0x06C5, 0x06C6, false, false, false),
        WireContract("gainLeft", "GainGet", "GainSet", 0x06B7, 0x06B8, false, false, false),
        WireContract("gainRight", "GainGet", "GainSet", 0x06B7, 0x06B8, false, false, false),
        WireContract("delay", "FrameDelayGet", "FrameDelaySet", 0x06BA, 0x06BB, false, false, false),
        WireContract("inputLevel", "InputSourceGet", "InputSourceSet", 0x06B4, 0x06B5, false, false, false)
    )
    /** A verified aggregate page is NOT an independent typed per-setting GET. */
    fun hasProvenAudioPage(rawHex: String): Boolean = AudioR26PageSnapshot.decode(rawHex) != null
    fun canHardwareSet(key: String): Boolean = controls.firstOrNull { it.key == key }?.maySet == true
}
