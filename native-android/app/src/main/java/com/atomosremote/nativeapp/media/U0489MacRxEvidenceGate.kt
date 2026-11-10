package com.atomosremote.nativeapp.media

/**
 * U04.89: receive-only FileBrowser validation from 2026-11-06 AG2 Mac captures.
 * This object contains no BLE writer, no command generator, and NO example names.
 * 26 real 0x81FB pages (13 per Mac session) form the regression fixtures.
 * These pages verify the existing protobuf decoder; they do not prove Android TX.
 */
object U0489MacRxEvidenceGate {
    const val FILE_LIST_RESPONSE = 0x81FB

    /** Accept only real, complete application responses already delivered by GATT. */
    fun decodeComplete(opcode: Int, bytes: ByteArray, expectedStart: Int):
            U0459FileBrowserWire.FileListPage? {
        if (opcode != FILE_LIST_RESPONSE || expectedStart !in 0..1_000_000 ||
            bytes.isEmpty() || bytes.size > 65_535) return null
        val page = U0459FileBrowserWire.parseFileList(bytes, expectedStart) ?: return null
        val total = page.total ?: return null
        if (page.start != expectedStart || page.entries.isEmpty() ||
            page.entries.size > U0459FileBrowserWire.MAX_PAGE_LIMIT ||
            total !in 1..1_000_000 || total < page.start + page.entries.size ||
            page.entries.map { it.index } !=
                (page.start until page.start + page.entries.size).toList() ||
            page.entries.map { it.filename }.distinct().size != page.entries.size) return null
        return page
    }

    /**
     * Passive evidence-only reassembly for Mac-style characteristic values:
     * E8 + packetId + remainingLenLE16 + 0x81FB LE16 + current protobuf slice.
     * Each successive remainingLen drops by the previous slice length.
     * This does not mutate the production GATT PacketLink engine or send bytes.
     */
    class FragmentVerifier {
        private data class Pending(val opcode: Int, val total: Int,
                                   var remaining: Int, val chunks: MutableList<ByteArray>)
        private val pending = mutableMapOf<Int, Pending>()
        fun reset() = pending.clear()
        fun offer(raw: ByteArray): ByteArray? {
            if (raw.size < 7 || (raw[0].toInt() and 255) != 0xE8) return null
            val id = raw[1].toInt() and 255
            val remaining = (raw[2].toInt() and 255) or ((raw[3].toInt() and 255) shl 8)
            val opcode = (raw[4].toInt() and 255) or ((raw[5].toInt() and 255) shl 8)
            val chunk = raw.copyOfRange(6, raw.size)
            if (opcode != FILE_LIST_RESPONSE || chunk.isEmpty() ||
                remaining == 0 || remaining > 65_535 || chunk.size > remaining) {
                pending.remove(id)
                return null
            }
            var state = pending[id]
            if (state == null) {
                state = Pending(opcode, remaining, remaining, mutableListOf())
                pending[id] = state
            }
            if (state.opcode != opcode || state.remaining != remaining) {
                pending.remove(id)
                return null
            }
            state.chunks.add(chunk)
            state.remaining -= chunk.size
            if (state.remaining > 0) return null
            pending.remove(id)
            val result = ByteArray(state.total)
            var offset = 0
            for (part in state.chunks) {
                if (part.size > result.size - offset) return null
                part.copyInto(result, offset)
                offset += part.size
            }
            return if (offset == state.total) result else null
        }
    }
}
