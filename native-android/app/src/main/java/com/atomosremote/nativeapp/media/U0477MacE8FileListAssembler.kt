package com.atomosremote.nativeapp.media

/**
 * U04.77: bounded, read-only macOS E8 characteristic-frame reassembly.
 * Input MUST be actual 0x0030 GATT RX bytes, never an E0 flow-control byte.
 * This decoder does not send BLE traffic. Not connected to frozen GattClient.kt.
 * Verified offline using an original Shogun Ultra (11.19.0) 3-chunk 0x81FB page.
 */
class U0477MacE8FileListAssembler(
    private val maxBytes: Int = 65_535,
    private val maxConcurrent: Int = 8
) {
    init { require(maxBytes in 64..65_535 && maxConcurrent in 1..16) }
    data class Reply(val packetId: Int, val opcode: Int, val protobuf: ByteArray)
    private data class Work(
        val packetId: Int, val opcode: Int, val buffer: java.io.ByteArrayOutputStream,
        var remaining: Int
    )
    private val inflight = LinkedHashMap<Int, Work>()
    fun reset() { inflight.clear() }
    /** Null for E0 / incomplete / invalid fragments. Never synthesize filenames. */
    fun accept(gattValue: ByteArray): Reply? {
        if (gattValue.size == 1 && (gattValue[0].toInt() and 255) == 0xE0) return null
        if (gattValue.size < 6 || (gattValue[0].toInt() and 255) != 0xE8) return null
        val packetId = gattValue[1].toInt() and 255
        val remaining = (gattValue[2].toInt() and 255) or ((gattValue[3].toInt() and 255) shl 8)
        val opcode = (gattValue[4].toInt() and 255) or ((gattValue[5].toInt() and 255) shl 8)
        // This phase understands only known FileList responses and does not infer unknown opcodes.
        if (opcode != 0x81FB || remaining > maxBytes || remaining <= 0) {
            inflight.remove(packetId); return null
        }
        val chunkLen = gattValue.size - 6
        if (chunkLen == 0 || chunkLen > remaining) { inflight.remove(packetId); return null }
        val work = inflight[packetId] ?: run {
            // A new FileList page starts with protobuf field 1 (numFiles) tag 0x08.
            // Never accept an orphaned last fragment as a complete response.
            if ((gattValue[6].toInt() and 255) != 0x08) return null
            if (inflight.size >= maxConcurrent) inflight.remove(inflight.keys.first())
            Work(packetId, opcode, java.io.ByteArrayOutputStream(), remaining).also { inflight[packetId] = it }
        }
        if (work.opcode != opcode || work.remaining != remaining) {
            inflight.remove(packetId); return null
        }
        work.buffer.write(gattValue, 6, chunkLen)
        work.remaining -= chunkLen
        if (work.buffer.size() > maxBytes) { inflight.remove(packetId); return null }
        if (work.remaining != 0) return null
        inflight.remove(packetId)
        return Reply(packetId, opcode, work.buffer.toByteArray())
    }
}
