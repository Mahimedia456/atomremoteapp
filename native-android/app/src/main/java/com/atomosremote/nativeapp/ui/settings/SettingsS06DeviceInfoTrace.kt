package com.atomosremote.nativeapp.ui.settings

/** Read-only DeviceInfo reply observer: never sends a BLE/PacketLink request.
 *  B04 accepts the CURRENT Android REMOTE PAYLOAD event format in addition
 *  to the legacy reconstructed AtomMessage format. Wrong packet IDs and
 *  hypothetical 0x84B0 replies are displayed as CANDIDATES, not verified GET.
 */
internal object SettingsS06DeviceInfoTrace {
    private const val GET_ID = 0x04B0
    internal data class Verified(val packetId: Int, val messageType: Int, val rawHex: String)
    internal data class Candidate(val packetId: Int, val messageType: Int, val rawHex: String, val reason: String)
    internal data class Trace(
        val pendingPacket: Int? = null,
        val queryCount: Int = 0,
        val flowOnlyCount: Int = 0,
        val unrelatedPayloadCount: Int = 0,
        val rejectionCount: Int = 0,
        val lastReturnedType: Int? = null,
        val verified: Verified? = null,
        val status: String = "No DeviceInfo query observed since Settings opened",
        val candidate: Candidate? = null
    )
    private val query = Regex("""\bBOOTSTRAP QUERY label=DeviceInfoGet cmd=(\d+) expected=(\d+) packetId=(\d+)\b""")
    private val mac13Query = Regex("""\bMAC13 PACKET label=DeviceInfoGet cmd=0x4B0 packetId=(\d+)\b""")
    private val legacyResponse = Regex("""\bresponse=AtomMessage packetId=(\d+) payloadLen=(\d+) messageType=(\d+) data=([0-9a-fA-F]*)""")
    private val plinkResponse = Regex("""\bREMOTE PAYLOAD local=(true|false) packetId=(\d+) payloadLen=(\d+) messageType=(\d+) data=([0-9a-fA-F]*)""")
    private data class Reply(val packet: Int, val length: Int, val type: Int, val raw: String)
    private fun parseReply(event: String): Reply? {
        val real = plinkResponse.find(event)
        val legacy = if (real == null) legacyResponse.find(event) else null
        val groups = real?.groupValues ?: legacy?.groupValues ?: return null
        val shift = if (real == null) 0 else 1
        val packet = groups[shift + 1].toIntOrNull() ?: return null
        val len = groups[shift + 2].toIntOrNull() ?: return null
        val type = groups[shift + 3].toIntOrNull() ?: return null
        val bytes = groups[shift + 4]
        if (packet !in 0..255 || len !in 0..2048 || bytes.length != len * 2 || bytes.length % 2 != 0) return null
        return Reply(packet, len, type, bytes.uppercase())
    }
    fun consume(previous: Trace, newEvents: List<String>): Trace {
        var trace = previous
        for (event in newEvents) {
            val q = query.find(event)
            if (q != null) {
                val id = q.groupValues[3].toIntOrNull()
                if (id != null && id in 1..255 && q.groupValues[1].toIntOrNull() == GET_ID && q.groupValues[2].toIntOrNull() == GET_ID) {
                    if (trace.pendingPacket != id) trace = trace.copy(queryCount = trace.queryCount + 1)
                    trace = trace.copy(pendingPacket = id, verified = null, candidate = null, lastReturnedType = null,
                        status = "0x04B0 GET queued (packet $id); waiting for recorder data")
                }
            }
            val m = mac13Query.find(event)
            if (m != null) {
                val id = m.groupValues[1].toIntOrNull()
                if (id != null && id in 1..255 && trace.pendingPacket != id) {
                    trace = trace.copy(pendingPacket = id, queryCount = trace.queryCount + 1,
                        verified = null, candidate = null, lastReturnedType = null,
                        status = "0x04B0 MAC13 dispatch packet $id; typed reply pending")
                }
            }
            if (trace.pendingPacket != null && event.contains("FLOW-ONLY RX E0"))
                trace = trace.copy(flowOnlyCount = trace.flowOnlyCount + 1)
            val rx = parseReply(event) ?: continue
            // Current firmware uses full 0x80xx response types for other GETs.
            // Until a genuine DeviceInfo payload is evidenced, 0x84B0 is only a candidate.
            val possibleType = rx.type == GET_ID || rx.type == 0x84B0
            val matched = trace.pendingPacket != null && rx.packet == trace.pendingPacket
            if (!matched) {
                if (possibleType && rx.length > 0) trace = trace.copy(
                    candidate = Candidate(rx.packet, rx.type, rx.raw, "type candidate; packet ID NOT correlated"),
                    status = "Possible 0x${rx.type.toString(16).uppercase()} reply with unrelated packet ${rx.packet}; UNVERIFIED"
                )
                trace = trace.copy(unrelatedPayloadCount = trace.unrelatedPayloadCount + 1)
                continue
            }
            trace = when {
                rx.type == 0xFFFF || rx.type == 0xFFFE -> trace.copy(
                    pendingPacket = null, rejectionCount = trace.rejectionCount + 1,
                    lastReturnedType = rx.type, verified = null,
                    status = "DeviceInfo request packet ${rx.packet}: reject 0x${rx.type.toString(16).uppercase()}")
                rx.type == GET_ID && rx.length > 0 -> trace.copy(
                    pendingPacket = null, lastReturnedType = rx.type,
                    verified = Verified(rx.packet, rx.type, rx.raw),
                    status = "Packet-correlated 0x04B0 data received; field names not validated")
                rx.type == 0x84B0 && rx.length > 0 -> trace.copy(
                    lastReturnedType = rx.type,
                    candidate = Candidate(rx.packet, rx.type, rx.raw, "alternative response opcode needs AG2 validation"),
                    status = "0x84B0 reply candidate on packet ${rx.packet}; schema NOT verified")
                else -> trace.copy(
                    lastReturnedType = rx.type, unrelatedPayloadCount = trace.unrelatedPayloadCount + 1,
                    status = "Packet ${rx.packet} returned 0x${rx.type.toString(16).uppercase()}; DeviceInfo NOT verified")
            }
        }
        return trace
    }
}
