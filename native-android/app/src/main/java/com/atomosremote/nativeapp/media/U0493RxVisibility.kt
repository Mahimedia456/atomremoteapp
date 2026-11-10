package com.atomosremote.nativeapp.media

/** U04.93 RX-only visibility. Never sends a recorder command or fabricates a response. */
object U0493RxVisibility {
    private val line = Regex("""^TCS18\.8 REMOTE PAYLOAD local=(true|false) packetId=(\d+) payloadLen=(\d+) messageType=(\d+) data=([0-9A-Fa-f]*)$""")
    private val targets = setOf(0x81FD, 0x81F7, 0x81F6, 0x81FB, 0x8202)
    /** Capture counters only. No filename or user data is logged. */
    fun summarize(events: List<String>, baseline: List<String>): String {
        val fresh = U0490MacInitEvidence.freshSuffix(events, baseline)
        val counts = mutableMapOf<Int, Int>()
        var invalid = 0
        var ackMode1 = 0
        var fileListFrames = 0
        var localCount = 0
        for (event in fresh) {
            if (!event.startsWith("TCS18.8 REMOTE PAYLOAD")) continue
            val match = line.matchEntire(event)
            if (match == null) { invalid++; continue }
            val len = match.groupValues[3].toIntOrNull()
            val opcode = match.groupValues[4].toIntOrNull()
            val hex = match.groupValues[5]
            if (len == null || opcode == null || len !in 1..65535 || hex.length != len * 2) {
                invalid++; continue
            }
            if (opcode !in targets) continue
            counts[opcode] = (counts[opcode] ?: 0) + 1
            if (match.groupValues[1] == "true") localCount++
            if (opcode == 0x81FB) fileListFrames++
            if (opcode == 0x81FD) {
                val body = try { ByteArray(len) { i ->
                    hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                } } catch (_: NumberFormatException) { invalid++; continue }
                if (U0459FileBrowserWire.parseMode(body) == 1) ackMode1++
            }
        }
        val names = listOf(0x81FD, 0x81F7, 0x81F6, 0x81FB, 0x8202)
        val byOpcode = names.joinToString(",") { "0x" + it.toString(16).uppercase() + "=" + (counts[it] ?: 0) }
        return "freshEvents=${fresh.size} baseline=${baseline.size} current=${events.size} " +
            "continuity=${baseline.isEmpty() || fresh.isNotEmpty() || baseline == events} " +
            "targetCounts=[$byOpcode] mode1Ack=$ackMode1 fileListFrames=$fileListFrames " +
            "localTargetEvents=$localCount invalidPayload=$invalid"
    }
}
