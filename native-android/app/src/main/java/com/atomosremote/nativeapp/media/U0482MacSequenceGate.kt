package com.atomosremote.nativeapp.media

/**
 * Passive Mac-sequence RX markers. These observations are deliberately NOT
 * sufficient authorization for ModeStart, FilePlay or other SET operations.
 * The debug ring buffer may contain earlier sessions or unsolicited messages.
 */
object U0482MacSequenceGate {
    data class Observation(
        val modeGetResponseObserved: Boolean,
        val sizeLimitsResponseObserved: Boolean,
        val modeStartResponseObserved: Boolean,
        val directoryResponseObserved: Boolean,
        val fileListResponseObserved: Boolean,
        val genericFFFFObserved: Boolean
    )

    private val rx = Regex(
        """^TCS18\.8 REMOTE PAYLOAD local=(true|false) packetId=(\d+) payloadLen=(\d+) messageType=(\d+) data=([0-9a-fA-F]*)$"""
    )

    fun observe(eventLines: List<String>): Observation {
        var mode = false
        var limits = false
        var start = false
        var directory = false
        var list = false
        var generic = false
        for (line in eventLines.takeLast(500)) {
            val match = rx.matchEntire(line) ?: continue
            val count = match.groupValues[3].toIntOrNull() ?: continue
            val opcode = match.groupValues[4].toIntOrNull() ?: continue
            val hex = match.groupValues[5]
            if (count < 0 || count > 65535 || hex.length != count * 2) continue
            when (opcode) {
                0x81FF -> if (hex.startsWith("0800", true)) mode = true
                0x8202 -> if (hex.equals("080C10101808", true)) limits = true
                0x81FD -> if (hex.equals("0801", true)) start = true
                0x81F7 -> if (hex.isNotEmpty()) directory = true
                0x81FB -> if (hex.isNotEmpty()) list = true
                0xFFFF -> generic = true
            }
        }
        return Observation(mode, limits, start, directory, list, generic)
    }
}
