package com.atomosremote.nativeapp.media

/** U04.90: fresh application-level evidence only. No recorder writes. */
object U0490MacInitEvidence {
    private val line = Regex("""^TCS18\.8 REMOTE PAYLOAD local=(true|false) packetId=(\d+) payloadLen=(\d+) messageType=(\d+) data=([0-9A-Fa-f]*)$""")
    private data class Message(val opcode: Int, val body: ByteArray)
    /** Compare ordered RX event occurrences, not unique string contents.
     * Repeated identical ACK strings are distinct occurrences. No recorder write.
     * If the 500-event ring buffer entirely changes between samples, fail closed.
     */
    internal fun freshSuffix(events: List<String>, baseline: List<String>): List<String> {
        if (baseline.isEmpty()) return events
        val maxOverlap = minOf(events.size, baseline.size)
        for (n in maxOverlap downTo 1) {
            if (baseline.takeLast(n) == events.take(n)) return events.drop(n)
        }
        return emptyList() // no provable continuity; never invent fresh RX
    }
    private fun freshEvents(events: List<String>, baseline: List<String>): Sequence<Message> =
        freshSuffix(events, baseline).asReversed().asSequence().mapNotNull { event ->
            val m = line.matchEntire(event) ?: return@mapNotNull null
            val length = m.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            val opcode = m.groupValues[4].toIntOrNull() ?: return@mapNotNull null
            val hex = m.groupValues[5]
            if (length !in 1..65_535 || hex.length != length * 2) return@mapNotNull null
            val bytes = try { ByteArray(length) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            } } catch (_: NumberFormatException) { return@mapNotNull null }
            Message(opcode, bytes)
        }

    /** Mac 2026-11-06 observed 0x81FD with protobuf field1 mode=MoviePicker(1). */
    fun hasFreshMoviePickerAck(events: List<String>, baseline: List<String>): Boolean =
        freshEvents(events, baseline).any { msg ->
            msg.opcode == 0x81FD && U0459FileBrowserWire.parseMode(msg.body) == 1
        }

    /** Never accept the bundled Mac fixture as live recorder data. */
    fun freshFileListPage(events: List<String>, baseline: List<String>, expectedStart: Int):
            U0459FileBrowserWire.FileListPage? =
        freshEvents(events, baseline).mapNotNull { msg ->
            U0489MacRxEvidenceGate.decodeComplete(msg.opcode, msg.body, expectedStart)
        }.firstOrNull()
}
