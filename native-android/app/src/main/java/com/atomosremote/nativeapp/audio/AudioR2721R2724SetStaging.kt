package com.atomosremote.nativeapp.audio

/** R27.21-R27.24: four-control SET staging, deliberately without any transport.
 * This is an independently testable domain layer: it MUST NOT be treated as
 * recorder SET success or a substitute for a physical write/readback roundtrip.
 * Protocol references: macOS Audio.GainSet 0x06B7; f1 gain(Int32), f2 channel(Bool).
 */
object AudioR2721R2724SetStaging {
    const val LIVE_TRANSMIT_ENABLED: Boolean = false
    enum class Control { LEFT_GAIN, RIGHT_GAIN, INPUT_LEVEL, HEADPHONE_MONITOR }
    enum class Status { STAGED_NO_TX, BLOCKED_NO_FRESH_GET, BLOCKED_INVALID_TARGET,
        BLOCKED_UNVERIFIED_SET_WIRE, BLOCKED_UNKNOWN_CONTROL }
    data class Plan(val status: Status, val control: String, val target: String,
                    val baseline: String, val detail: String, val willTransmit: Boolean = false)
    val gainDbChoices = (-20..40).map { it / 2.0 }
    val inputChoices = listOf("Line Level", "Mic Level")
    val monitorChoices = listOf("On", "Off")
    
    fun stage(key: String, target: String, linked: Boolean, freshTypedGet: Boolean,
              baseline: String): Plan {
        val control = when (key) {
            "gainL" -> Control.LEFT_GAIN
            "gainR" -> Control.RIGHT_GAIN
            "inLevel" -> Control.INPUT_LEVEL
            "micMonitor" -> Control.HEADPHONE_MONITOR
            else -> return Plan(Status.BLOCKED_UNKNOWN_CONTROL, key, target, baseline,
                "Unrecognized SET target")
        }
        if (!linked || !freshTypedGet || baseline.isBlank() || baseline == "--" ||
            baseline == "Unknown") {
            return Plan(Status.BLOCKED_NO_FRESH_GET, key, target, baseline,
                "Take a fresh typed GET on the connected AG2 before preparing this control")
        }
        val valid = when (control) {
            Control.LEFT_GAIN, Control.RIGHT_GAIN -> {
                val number = target.removeSuffix(" dB").toDoubleOrNull()
                number != null && number.isFinite() &&
                    number >= -10.0 && number <= 20.0 &&
                    (number * 2.0) % 1.0 == 0.0
            }
            Control.INPUT_LEVEL -> target in inputChoices
            Control.HEADPHONE_MONITOR -> target in monitorChoices
        }
        if (!valid) return Plan(Status.BLOCKED_INVALID_TARGET, key, target, baseline,
            "Target is outside the recorder UI option list")
        val reason = when (control) {
            Control.LEFT_GAIN, Control.RIGHT_GAIN ->
                "GainSet 0x06B7 f1 Int32/f2 Bool: channel polarity, dB scale and " +
                "reverse-frame proof are not verified on AG2"
            Control.INPUT_LEVEL ->
                "Input Level GET is verified; exact SET opcode/enum payload and reversal missing"
            Control.HEADPHONE_MONITOR ->
                "Monitor GET candidate mapped; SET opcode/payload and monitor-vs-enable semantics missing"
        }
        return Plan(Status.STAGED_NO_TX, key, target, baseline,
            "Target staged locally only. $reason", willTransmit = false)
    }
    /** Only pure protobuf candidate vectors: not scaled, channel-mapped, framed or eligible for TX. */
    fun offlineGainProtobuf(gainInt32: Int, channelBool: Boolean): ByteArray {
        fun varint(v: Long): List<Byte> {
            var n = v
            val bytes = ArrayList<Byte>()
            do {
                val part = (n and 0x7fL).toInt()
                n = n ushr 7
                bytes.add((part or if (n != 0L) 0x80 else 0).toByte())
            } while(n != 0L)
            return bytes
        }
        val result = ArrayList<Byte>()
        if (gainInt32 != 0) {
            result.add(0x08)
            result.addAll(varint(gainInt32.toLong()))
        }
        if (channelBool) {
            result.add(0x10)
            result.add(0x01)
        }
        return result.toByteArray()
    }
    fun canTransmit(@Suppress("UNUSED_PARAMETER") plan: Plan): Boolean = false
}
