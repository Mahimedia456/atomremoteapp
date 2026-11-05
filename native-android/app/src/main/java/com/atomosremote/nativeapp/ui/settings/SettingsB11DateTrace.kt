package com.atomosremote.nativeapp.ui.settings

/** B11: correlates only the fresh one-shot DateGet packet; never labels E0 as data. */
internal object SettingsB11DateTrace {
    internal data class Trace(
        val packetId: Int? = null,
        val dispatchSeen: Boolean = false,
        val txSeen: Boolean = false,
        val transportDelivered: Boolean = false,
        val responseType: Int? = null,
        val rawHex: String? = null,
        val rejected: Boolean = false,
        val status: String = "Date GET not requested"
    )
    private val tx = Regex("""DATE_GET_DISPATCH packetId=(\d+) cmd=0x04B9\b""")
    private val response = Regex("""(?:REMOTE PAYLOAD .*|response=AtomMessage )packetId=(\d+) payloadLen=(\d+) messageType=(\d+) data=([0-9a-fA-F]*)""")
    private val completed = Regex("""TRANSPORT DELIVERED label=B11\.DateGet\b""")

    fun consume(start: Trace, fresh: List<String>): Trace {
        var state = start
        for (event in fresh) {
            val request = tx.find(event)
            if (request != null) {
                val id = request.groupValues[1].toIntOrNull()
                if (id != null && id in 1..255) {
                    state = Trace(packetId = id, dispatchSeen = true, status = "DateGet 0x04B9 dispatched; raw RX pending")
                }
            }
            if (state.packetId == null) continue
            val id = state.packetId
            if (event.contains("DATE_GET_QUEUE_RESULT packetId=$id accepted=true")) {
                state = state.copy(txSeen = true, status = "DateGet queued; recorder reply pending")
            }
            if (completed.containsMatchIn(event)) {
                state = state.copy(transportDelivered = true, status = "PacketLink transport delivered; typed Date RX unverified")
            }
            // Actual AtomMessage payload only, with packetId and byte-length checking.
            if (!event.contains("REMOTE PAYLOAD ") && !event.contains("response=AtomMessage")) continue
            val match = response.find(event) ?: continue
            val p = match.groupValues[1].toIntOrNull() ?: continue
            val len = match.groupValues[2].toIntOrNull() ?: continue
            val kind = match.groupValues[3].toIntOrNull() ?: continue
            val raw = match.groupValues[4].uppercase()
            if (p != id || len <= 0 || len > 2048 || raw.length != len * 2) continue
            state = if (kind == 65535 || kind == 65534) {
                state.copy(responseType = kind, rejected = true, status = "Recorder rejected DateGet; type 0x${kind.toString(16).uppercase()}")
            } else {
                val category = when (kind) {
                    0x04B5 -> "binary candidate 0x04B5"
                    0x84B5 -> "alternate 0x84B5"
                    else -> "unexpected type 0x${kind.toString(16).uppercase()}"
                }
                state.copy(responseType = kind, rawHex = raw, status = "Packet-correlated raw Date RX ($category); date fields unverified")
            }
        }
        return state
    }
}
