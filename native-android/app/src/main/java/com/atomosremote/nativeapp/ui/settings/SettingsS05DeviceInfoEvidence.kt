package com.atomosremote.nativeapp.ui.settings

/**
 * SETTINGS S05 — evidence-only observer of the EXISTING RemoteApi bootstrap.
 * No BLE, GATT, PacketLink, callbacks, or TX code live here.
 * The official startup requests DeviceInfoGet 0x04B0 and can be refreshed
 * through GattClient.refreshOfficialRecorderState() without any new opcode.
 * No firmware/device ID schema is assumed from an arbitrary protobuf body.
 */
internal object SettingsS05DeviceInfoEvidence {
    internal data class Response(val packetId: Int, val rawHex: String)

    // Only parse a fully demultiplexed AtomMessage log with exact type 0x04B0.
    // Messages logged merely "while=DeviceInfoGet" are NOT enough for proof.
    private val responsePattern = Regex(
        """\bresponse=AtomMessage packetId=(\d+) payloadLen=(\d+) messageType=(\d+) data=([0-9A-Fa-f]*)"""
    )

    fun parseDeviceResponse(event: String): Response? {
        if (!event.startsWith("TCS18.5 ")) return null
        val match = responsePattern.find(event) ?: return null
        val packetId = match.groupValues[1].toIntOrNull() ?: return null
        val bytes = match.groupValues[2].toIntOrNull() ?: return null
        val type = match.groupValues[3].toIntOrNull() ?: return null
        val hex = match.groupValues[4]
        if (type != 0x04B0 || packetId !in 0..255 || bytes !in 1..2048) return null
        if (hex.length != bytes * 2) return null
        return Response(packetId, hex.uppercase())
    }

    /**
     * Fail closed if the previously seen event has rolled out of the bounded
     * GATT event list; never attribute an old session's history to this device.
     */
    fun freshAfter(events: List<String>, lastSeen: String): List<String> {
        val previousIndex = events.indexOfLast { it == lastSeen }
        return if (previousIndex >= 0) events.drop(previousIndex + 1) else emptyList()
    }
}
