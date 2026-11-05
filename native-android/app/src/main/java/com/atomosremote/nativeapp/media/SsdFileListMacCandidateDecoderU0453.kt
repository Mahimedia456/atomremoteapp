package com.atomosremote.nativeapp.media

/**
 * U04.53: Isolated OFFLINE candidate decoder, not part of GATT/PacketLink or Media UI.
 *
 * Original Mac executable evidence:
 *  - 0x01FB is constructed with (start,count,forward,infoType)
 *  - Swift FileBrowserFileListMsg reflection order is files, _numFiles,
 *    _filesStartIndex. Reflection ORDER DOES NOT PROVE PROTOBUF TAG NUMBERS.
 *  - FileBrowserFileInfoMsg contains _fileName; its tag number is NOT proven.
 *
 * This parser deliberately treats tags (files=1, numFiles=2, start=3;
 * nested fileName=2) as a HYPOTHESIS for interpreting future CAPTURED evidence.
 * It NEVER transmits, never changes recorder state and never emits a verified
 * recorder file list. A syntactically valid fixture is NOT a real recorder RX.
 */
object SsdFileListMacCandidateDecoderU0453 {
    const val LIST_QUERY_MAC_STATIC = 0x01FB
    const val INDEX_OPERATION_NOT_FILELIST = 0x01FC

    enum class Verdict {
        CREDIT_ONLY, INVALID_FRAME, DIFFERENT_PACKET_ID, DIFFERENT_OPCODE,
        EMPTY_OR_ERROR_PAYLOAD, MALFORMED_PROTOBUF, UNMATCHED_FIELD_SHAPE,
        HYPOTHETICAL_LIST_STRUCTURE
    }

    data class Entry(val index: Int, val name: String)
    data class Audit(
        val verdict: Verdict,
        val requestOpcode: Int,
        val responseOpcode: Int?,
        val packetId: Int?,
        val entries: List<Entry> = emptyList(),
        val totalFiles: Int? = null,
        val startIndex: Int? = null,
        val responseBodySize: Int = 0,
        // Never set this without *actual* correlated AG2 recorder evidence.
        val hardwareValidated: Boolean = false,
        val protobufFieldNumbersVerified: Boolean = false
    )
    private data class ProtoField(val id: Int, val wireType: Int, val number: Long?, val bytes: ByteArray?)

    private fun varint(data: ByteArray, cursor: IntArray): Long {
        var out = 0L
        for (shift in 0..63 step 7) {
            if (cursor[0] >= data.size) throw IllegalArgumentException("truncated varint")
            val b = data[cursor[0]++].toInt() and 255
            if (shift == 63 && (b and 127) > 1) throw IllegalArgumentException("varint overflow")
            out = out or ((b and 127).toLong() shl shift)
            if ((b and 128) == 0) return out
        }
        throw IllegalArgumentException("long varint")
    }

    private fun fields(data: ByteArray, maxBytes: Int): List<ProtoField> {
        if (data.size > maxBytes) throw IllegalArgumentException("oversized")
        val c = intArrayOf(0)
        val result = ArrayList<ProtoField>()
        while (c[0] < data.size) {
            if (result.size >= 512) throw IllegalArgumentException("too many fields")
            val tag = varint(data, c)
            if (tag == 0L || tag > (0x1fffffffL shl 3) + 7) throw IllegalArgumentException("invalid field")
            val id = (tag ushr 3).toInt()
            val wt = (tag and 7).toInt()
            when (wt) {
                0 -> result.add(ProtoField(id, wt, varint(data, c), null))
                2 -> {
                    val len = varint(data, c)
                    if (len < 0 || len > data.size - c[0]) throw IllegalArgumentException("bad length")
                    val start = c[0]
                    c[0] += len.toInt()
                    result.add(ProtoField(id, wt, null, data.copyOfRange(start, c[0])))
                }
                1 -> { if (data.size - c[0] < 8) throw IllegalArgumentException("fixed64"); c[0] += 8 }
                5 -> { if (data.size - c[0] < 4) throw IllegalArgumentException("fixed32"); c[0] += 4 }
                else -> throw IllegalArgumentException("unsupported wire type")
            }
        }
        return result
    }

    private fun candidateFiles(body: ByteArray): Triple<List<Entry>, Int?, Int?>? {
        val fs = fields(body, 65535)
        // Hypothesized standard protobuf tags based on the original Mac
        // Swift reflection property ORDER. These field numbers remain unverified.
        val rawEntries = fs.filter { it.id == 1 && it.wireType == 2 }
        val counts = fs.filter { it.id == 2 && it.wireType == 0 }
        val starts = fs.filter { it.id == 3 && it.wireType == 0 }
        if (rawEntries.size > 256 || counts.size > 1 || starts.size > 1) return null
        val total = counts.firstOrNull()?.number
        val start = starts.firstOrNull()?.number ?: 0L
        if (start !in 0L..1000000L || (total != null && total !in 0L..1000000L)) return null
        if (total == null && rawEntries.isEmpty()) return null
        if (total != null && total < rawEntries.size) return null
        val entries = ArrayList<Entry>()
        for ((i, item) in rawEntries.withIndex()) {
            val inner = fields(item.bytes ?: return null, 4096)
            // Candidate FileBrowserFileInfoMsg _fileName field2; NOT established.
            val names = inner.filter { it.id == 2 && it.wireType == 2 }
            if (names.size != 1) return null
            val raw = names[0].bytes ?: return null
            if (raw.isEmpty() || raw.size > 255) return null
            val text = raw.toString(Charsets.UTF_8)
            if (text.isBlank() || '\uFFFD' in text || text.any { it.code < 32 || it.code == 127 }) return null
            entries.add(Entry(start.toInt() + i, text))
        }
        return Triple(entries, total?.toInt(), start.toInt())
    }

    /**
     * Offline E0/E8 complete-frame audit. Require EXPLICIT response opcode
     * and packet ID from an independently verified request (never guess one).
     * Does not imply that a real recorder originated the supplied bytes.
     */
    fun inspectCapturedFrame(
        frame: ByteArray,
        expectedPacketId: Int,
        expectedResponseOpcode: Int,
        expectedRouteLocal: Boolean
    ): Audit {
        require(expectedPacketId in 0..255)
        require(expectedResponseOpcode in 0..65535)
        val base = Audit(Verdict.INVALID_FRAME, LIST_QUERY_MAC_STATIC, null, null)
        if (frame.size == 1 && ((frame[0].toInt() and 255) == 0xE0 || (frame[0].toInt() and 255) == 0xE8))
            return base.copy(verdict = Verdict.CREDIT_ONLY)
        if (frame.size < 6) return base
        val route = frame[0].toInt() and 255
        if (route != (if (expectedRouteLocal) 0xE8 else 0xE0)) return base
        val id = frame[1].toInt() and 255
        val len = (frame[2].toInt() and 255) or ((frame[3].toInt() and 255) shl 8)
        val opcode = (frame[4].toInt() and 255) or ((frame[5].toInt() and 255) shl 8)
        val basic = base.copy(responseOpcode = opcode, packetId = id, responseBodySize = len)
        if (len != frame.size - 6) return basic
        if (id != expectedPacketId) return basic.copy(verdict = Verdict.DIFFERENT_PACKET_ID)
        if (opcode != expectedResponseOpcode) return basic.copy(verdict = Verdict.DIFFERENT_OPCODE)
        if (len == 0 || opcode == 0xFFFE || opcode == 0xFFFF)
            return basic.copy(verdict = Verdict.EMPTY_OR_ERROR_PAYLOAD)
        val parsed = try { candidateFiles(frame.copyOfRange(6, frame.size)) }
                     catch (_: IllegalArgumentException) { return basic.copy(verdict = Verdict.MALFORMED_PROTOBUF) }
        if (parsed == null) return basic.copy(verdict = Verdict.UNMATCHED_FIELD_SHAPE)
        return basic.copy(
            verdict = Verdict.HYPOTHETICAL_LIST_STRUCTURE,
            entries = parsed.first,
            totalFiles = parsed.second,
            startIndex = parsed.third
        )
    }
}
