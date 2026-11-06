package com.atomosremote.nativeapp.media

/**
 * K01.6: conservative FileBrowserFileListMsg probe.
 * Field layout reconstructed from 2024 macOS SwiftProtobuf metadata, not yet
 * validated against a Shogun Ultra AG2 FileListGet response.
 * This parser neither transmits nor infers entries from flow-control frames.
 */
data class SsdBrowserEntry(val index: Int, val filename: String)
data class SsdBrowserState(
    val status: String = "No recorder file list received",
    val entries: List<SsdBrowserEntry> = emptyList(),
    val totalFiles: Int? = null,
    val startIndex: Int = 0,
    val responseOpcode: Int? = null,
    val recorderValidated: Boolean = false,
    val activeIndex: Int? = null, // only from packet-correlated FilePlay reply
    val modeReadback: Int? = null, // only from packet-correlated ModeGet reply
    val pageCountLimit: Int? = null, // no trusted limit until live QueryCountLimitMsg
    val requestedStartIndex: Int = 0,
    val pending: Boolean = false,
    val generation: Long = 0L,
    // U03: packet-level evidence after a manually queued FileList GET.
    // Counters refer only to parsed application payload frames, never E0 credits.
    val localApplicationRx: Int = 0,
    val nonLocalApplicationRx: Int = 0,
    val fileListOpcodeRx: Int = 0,
    val lastApplicationOpcode: Int? = null
)

object SsdFileListDecoder {
    private data class Field(val number: Int, val kind: Int, val raw: ByteArray, val integer: Long)
    private fun readVarint(data: ByteArray, pos: IntArray): Long {
        var shift = 0
        var value = 0L
        repeat(10) {
            if (pos[0] >= data.size) throw IllegalArgumentException("Truncated varint")
            val ch = data[pos[0]++].toInt() and 255
            if (shift == 63 && ch > 1) throw IllegalArgumentException("Varint overflow")
            value = value or ((ch and 127).toLong() shl shift)
            if ((ch and 128) == 0) return value
            shift += 7
        }
        throw IllegalArgumentException("Varint too long")
    }
    private fun fields(data: ByteArray): List<Field> {
        if (data.size > 65535) throw IllegalArgumentException("Payload too large")
        val pos = intArrayOf(0)
        val result = ArrayList<Field>()
        while (pos[0] < data.size) {
            if (result.size >= 4096) throw IllegalArgumentException("Too many protobuf fields")
            val tag = readVarint(data, pos).toInt()
            val field = tag ushr 3
            val wire = tag and 7
            if (field == 0) throw IllegalArgumentException("Bad field zero")
            when (wire) {
                0 -> result.add(Field(field, wire, byteArrayOf(), readVarint(data, pos)))
                2 -> {
                    val size = readVarint(data, pos)
                    if (size < 0 || size > data.size - pos[0]) throw IllegalArgumentException("Bad length")
                    val end = pos[0] + size.toInt()
                    result.add(Field(field, wire, data.copyOfRange(pos[0], end), 0))
                    pos[0] = end
                }
                1 -> { if (pos[0] + 8 > data.size) throw IllegalArgumentException("Bad fixed64"); pos[0] += 8 }
                5 -> { if (pos[0] + 4 > data.size) throw IllegalArgumentException("Bad fixed32"); pos[0] += 4 }
                else -> throw IllegalArgumentException("Unknown wire kind")
            }
        }
        return result
    }
    /** Returns candidate entries only for a structurally valid, relevant protobuf. */
    fun decode(responseOpcode: Int, body: ByteArray, requestedStartIndex: Int = 0): SsdBrowserState? {
        if ((responseOpcode and 0x7fff) != 0x01fb || body.isEmpty()) return null
        return try {
            val all = fields(body)
            val list = all.filter { it.number == 2 && it.kind == 2 }
            val total = all.firstOrNull { it.number == 1 && it.kind == 0 }?.integer
            val start = all.firstOrNull { it.number == 3 && it.kind == 0 }?.integer ?: requestedStartIndex.toLong()
            if (start !in 0..1000000 || total != null && total !in 0..1000000) return null
            if (list.size > 256) return null
            val entries = list.mapIndexedNotNull { i, item ->
                // FileBrowserFileInfoMsg: field 2=fileName, per Swift metadata.
                val info = fields(item.raw)
                val filenameBytes = info.firstOrNull { it.number == 2 && it.kind == 2 }?.raw ?: return@mapIndexedNotNull null
                val filename = filenameBytes.toString(Charsets.UTF_8).trimEnd('\u0000')
                if (filename.isEmpty() || filename.length > 255 ||
                    filename.any { it.code < 32 || it.code == 127 || it == '\uFFFD' }) return@mapIndexedNotNull null
                SsdBrowserEntry(start.toInt() + i, filename)
            }
            // Do not claim that a syntactically plausible response is hardware-validated.
            if (list.isNotEmpty() && entries.size != list.size) return null
            if (list.isEmpty() && total == null) return null
            SsdBrowserState(
                status = "Recorder FileList candidate decoded; compare filenames on recorder",
                entries = entries,
                totalFiles = total?.toInt(),
                startIndex = start.toInt(),
                responseOpcode = responseOpcode,
                recorderValidated = false,
                requestedStartIndex = requestedStartIndex,
                pending = false
            )
        } catch (_: IllegalArgumentException) { null }
    }
}


/**
 * U04.15: explicit protobuf field 1 (index = 0) candidate for FileListGet 0x01FC.
 * Offline macOS evidence identifies the message type. AG2 has NOT accepted it yet.
 * Fail closed for page >0: this index-message is not a paginated FileListQuery.
 * Single operator button is located in MediaModePlaybackScreen. No auto TX here.
 */
object SsdFileListPageQuery {
    fun encode(start: Int): ByteArray {
        require(start == 0) { "U04.15 supports index-zero evidence GET only" }
        return byteArrayOf(0x08, 0x00)
    }
}
