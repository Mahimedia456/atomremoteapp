package com.atomosremote.nativeapp.media

/**
 * U04.78: purely passive FileBrowser RX observer. NEVER writes to BLE or GATT.
 * Consumes the public GattClient.events log stream through the Media Compose UI.
 * Accepts only a genuine, typed FileList application reply following a matching
 * operator-triggered FileList TX marker in the same event history and GET window.
 *
 * Recorder-generated E8 response packet IDs may differ from request packet ID;
 * correlate to the single active request, response opcode, requested page start,
 * and a real decoder result, NOT request-packetId equality.
 *
 * UI evidence alone does not authorize FilePlay or state-changing commands.
 */
object U0478MacFileListEventBridge {
    data class Hit(
        val generation: Long,
        val responsePacketId: Int,
        val page: U0459FileBrowserWire.FileListPage
    )

    private val rxPattern = Regex("""^TCS18\.8 REMOTE PAYLOAD local=true packetId=(\d+) payloadLen=(\d+) messageType=(\d+) data=([0-9a-fA-F]+)$""")
    private val txFileListMarker = Regex("""^TCS18\.8\.1 PLINK BUFFER label=(U0459\.FileBrowser\.FileListGet|U0466\.FileBrowser\.FileListGet\.LOCAL_E8) cmd=507 packetId=\d+ m_isLocal=(0|1)\b.*$""")

    fun fromEvents(events: List<String>, state: SsdBrowserState): Hit? {
        if (!state.pending || state.requestedStartIndex !in 0..1_000_000 || events.isEmpty()) return null
        // Exclude prior sessions or previous GET requests still retained in event history.
        val startMarker = events.indexOfLast { txFileListMarker.matches(it) }
        if (startMarker < 0) return null
        var hit: Hit? = null
        for (line in events.drop(startMarker + 1)) {
            // If a new FileList GET is queued, restart at that newer marker (no stale reuse).
            val m = rxPattern.matchEntire(line) ?: continue
            val packetId = m.groupValues[1].toIntOrNull() ?: continue
            val size = m.groupValues[2].toIntOrNull() ?: continue
            val opcode = m.groupValues[3].toIntOrNull() ?: continue
            if (opcode != 0x81FB || size !in 2..65_535) continue
            val hex = m.groupValues[4]
            if (hex.length != size * 2 || hex.length % 2 != 0) continue
            val raw = try { ByteArray(size) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }
                      catch (_: NumberFormatException) { continue }
            val page = U0459FileBrowserWire.parseFileList(raw, state.requestedStartIndex) ?: continue
            if (page.start != state.requestedStartIndex || page.entries.isEmpty() || page.entries.size > 64) continue
            if (page.total != null && page.total < page.start + page.entries.size) continue
            // Do not accept two non-identical responses as the same request.
            if (hit != null) return null
            hit = Hit(state.generation, packetId, page)
        }
        return hit
    }
}
