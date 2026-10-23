package com.atomosremote.nativeapp.audio

/** R27.07: pure, read-only inspection of fully reassembled PacketLink payloads.
 * Called BEFORE GattClient's strict AtomMessage demux. This class never sends
 * commands, marks a gain numeric value as proven, or accepts an invalid frame.
 */
object AudioR2707MicWireAudit {
    data class Frame(
        val kind: String, val packetId: Int?, val messageType: Int?,
        val bodyLength: Int, val declaredLength: Int?, val exactLength: Boolean
    )

    fun inspect(payload: ByteArray): Frame {
        if (payload.size < 5) return Frame("SHORT", null, null, 0, null, false)
        val id = payload[0].toInt() and 0xFF
        val length = (payload[1].toInt() and 0xFF) or ((payload[2].toInt() and 0xFF) shl 8)
        val type = (payload[3].toInt() and 0xFF) or ((payload[4].toInt() and 0xFF) shl 8)
        val exact = payload.size == 5 + length
        val kind = if (!exact) "LENGTH_MISMATCH" else when (type) {
            0x86B3 -> "INPUT_SOURCE_CANDIDATE"
            0x86B6 -> "GAIN_VALUES_CANDIDATE"
            0x86B9 -> "FRAME_DELAY_CANDIDATE"
            0x8000 -> "GENERIC_ACK"
            0xFFFF, 0xFFFE -> "GENERIC_REJECTION"
            else -> "OTHER_TYPED"
        }
        return Frame(kind, id, type, (payload.size - 5).coerceAtLeast(0), length, exact)
    }

    /** Exact reply type can be a *candidate* without trusting packetId zero;
     * generic statuses require live request ownership AND matching packet ID.
     * Other live PacketLink owners always win. Never certifies numeric values.
     */
    fun correlation(command: Int, reply: Int, packetId: Int, requestPacketId: Int,
                    activeGATTWritten: Boolean, sameOwner: Boolean, otherOwner: Boolean): String {
        if (!activeGATTWritten) return "NO_CONFIRMED_TX"
        if (otherOwner) return "OTHER_PACKET_OWNER"
        if (reply == (command or 0x8000)) {
            return if (packetId == requestPacketId) "TYPED_CANDIDATE_ID_MATCH" else "TYPED_CANDIDATE_ID_VARIANT"
        }
        if (reply == 0xFFFF || reply == 0xFFFE || reply == 0x8000) {
            return if (sameOwner && packetId == requestPacketId) "OWNED_GENERIC_STATUS" else "AMBIGUOUS_GENERIC_STATUS"
        }
        return "UNRELATED_TYPE"
    }
}
