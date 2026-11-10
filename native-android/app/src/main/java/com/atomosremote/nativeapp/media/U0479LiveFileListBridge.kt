package com.atomosremote.nativeapp.media

/**
 * U04.79: verified response-only SSD FileList decoder from current live GATT state.
 * The original GATT callback increments fileListOpcodeRx only during the active
 * 15-second FileList request window; this is the RX freshness proof. The rolling
 * 500-event debug history is NOT required to retain a TX marker, because the
 * 10ms PacketLink tick may evict it before delayed response processing.
 *
 * No BLE sends, no FILEPLAY/SET. Successful decode is NOT authorization to
 * replay a FilePlay command. Never display the captured Mac sample as live.
 */
object U0479LiveFileListBridge {
    data class Hit(val generation: Long, val responsePacketId: Int,
                   val page: U0459FileBrowserWire.FileListPage)
    private val rx = Regex("""^TCS18\.8 REMOTE PAYLOAD local=(true|false) packetId=(\d+) payloadLen=(\d+) messageType=(\d+) data=([0-9a-fA-F]+)$""")

    fun fromEvents(events: List<String>, state: SsdBrowserState): Hit? {
        if (!state.pending || state.fileListOpcodeRx <= 0 ||
            state.requestedStartIndex !in 0..1_000_000) return null
        // A genuinely current opcode was counted by the frozen GATT receiver.
        // Scan newest first, avoiding old packet IDs and TX-marker eviction.
        for (line in events.asReversed()) {
            val match = rx.matchEntire(line) ?: continue
            val local = match.groupValues[1] == "true"
            val pkt = match.groupValues[2].toIntOrNull() ?: continue
            val count = match.groupValues[3].toIntOrNull() ?: continue
            val opcode = match.groupValues[4].toIntOrNull() ?: continue
            if (opcode != 0x81FB || count !in 4..65_535) continue
            if (local && state.localApplicationRx <= 0) continue
            if (!local && state.nonLocalApplicationRx <= 0) continue
            val hex = match.groupValues[5]
            if (hex.length != count * 2) continue
            val bytes = try { ByteArray(count) {
                i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            } } catch (_: NumberFormatException) { continue }
            // Mac hardware-confirmed 0x81FB envelope + protobuf schema.
            // This is a LIVE RX body only, never a bundled Mac sample.
            val page = U0489MacRxEvidenceGate.decodeComplete(
                opcode, bytes, state.requestedStartIndex) ?: continue
            if (page.start != state.requestedStartIndex ||
                page.entries.isEmpty() || page.entries.size > 64 ||
                page.entries.any { it.index !in 0..1_000_000 } ||
                (page.total != null && page.total < page.start + page.entries.size)) continue
            return Hit(state.generation, pkt, page)
        }
        return null
    }
}
