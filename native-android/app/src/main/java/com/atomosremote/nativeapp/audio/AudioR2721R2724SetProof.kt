package com.atomosremote.nativeapp.audio

/**
 * R27.21-24 unified Mic/Line SET proof gate, version Stage A.
 *
 * READBACK/VALIDATION ONLY. This file MUST NOT import the BLE client or send
 * packets. Sender implementation remains absent, by design: macOS setter
 * constructors are visible but serialized protobuf payload, gain unit scaling,
 * selector polarity and target AG2 response/rollback have not been verified.
 * All four control names exist here so that *one* eventual proven transaction
 * controller can be prepared without destabilizing working GET paths.
 */
object AudioR2721R2724SetProof {
    enum class Key { LEFT_GAIN, RIGHT_GAIN, AUDIO_IN_LEVEL, HEADPHONE_MONITOR }
    data class Plan(
        val key: Key,
        val targetName: String,
        val observedBefore: String,
        val sourceType: Int,
        val candidateMacSetter: Int?,
        val mayTransmit: Boolean,
        val blockers: List<String>
    )
    private fun supportedGain(db: Int): Boolean = db >= -10 && db <= 20 && db % 5 == 0

    /** For a CURRENT, typed and physically correlated GET only. */
    fun preview(
        key: Key,
        choice: String,
        typedResponse: Int,
        typedBodyHex: String,
        correctAg2Linked: Boolean,
        freshResponse: Boolean,
        recorderIsRecording: Boolean
    ): Plan {
        val response = when (key) {
            Key.LEFT_GAIN, Key.RIGHT_GAIN -> 0x86B1
            Key.AUDIO_IN_LEVEL -> 0x86AE
            Key.HEADPHONE_MONITOR -> 0x8647
        }
        val setterCandidate = when (key) {
            Key.LEFT_GAIN, Key.RIGHT_GAIN -> 0x06B7
            Key.AUDIO_IN_LEVEL -> 0x06B4
            Key.HEADPHONE_MONITOR -> null // no independently verified icon-specific SET
        }
        val current = if (typedResponse != response || !correctAg2Linked || !freshResponse) null else when (key) {
            Key.LEFT_GAIN -> AudioR2714GainReadback.decodeHex(typedBodyHex)?.leftLabel()
            Key.RIGHT_GAIN -> AudioR2714GainReadback.decodeHex(typedBodyHex)?.rightLabel()
            Key.AUDIO_IN_LEVEL -> AudioR2716InputLevelReadback.decodeHex(typedBodyHex)?.display()
            Key.HEADPHONE_MONITOR -> {
                val rb = AudioR2718MonitorCandidate.decode(typedBodyHex)
                when (rb.icon) {
                    AudioR2718MonitorCandidate.Icon.ON_CANDIDATE -> "On (candidate)"
                    AudioR2718MonitorCandidate.Icon.OFF_CANDIDATE -> "Off (candidate)"
                    else -> null
                }
            }
        }
        val requestedAllowed = when (key) {
            Key.LEFT_GAIN, Key.RIGHT_GAIN -> choice.removeSuffix(".0 dB").removePrefix("+")
                .toIntOrNull()?.let(::supportedGain) ?: false
            Key.AUDIO_IN_LEVEL -> choice == "Line Level" || choice == "Mic Level"
            Key.HEADPHONE_MONITOR -> choice == "On" || choice == "Off"
        }
        val blockers = mutableListOf<String>()
        if (!correctAg2Linked) blockers += "Not linked to the verified Shogun Ultra AG2"
        if (!freshResponse) blockers += "No fresh current-session typed GET"
        if (typedResponse != response || current == null) blockers += "Typed readback missing/malformed or wrong opcode"
        if (recorderIsRecording) blockers += "Recorder recording: setting changes prohibited"
        if (!requestedAllowed) blockers += "Choice outside the physical AG2 option list"
        blockers += "No independently verified SET protobuf encoding and selector semantics"
        blockers += "No proven bounded physical GET-after-SET plus reversal on AG2"
        if (key == Key.HEADPHONE_MONITOR)
            blockers += "Audio page f1 is only an ON/OFF candidate; no icon-specific SET identified"
        return Plan(key, choice, current ?: "Unknown", response, setterCandidate, false, blockers)
    }

    /** Explicit closed gate even if all readback preliminaries pass. */
    fun requireVerifiedSetWire(plan: Plan): Nothing = throw IllegalStateException(
        "R27.21-24: LIVE SET LOCKED for ${plan.key}; no verified wire payload or reversal. " +
        "Candidate ${plan.candidateMacSetter?.toString(16) ?: "none"} is NOT permission to TX."
    )
}
