package com.atomosremote.nativeapp.media

/**
 * U04.59 FileBrowser protocol reconstructed from the user's macOS binary analysis.
 * This is a wire implementation, NOT a claim of AG2 hardware verification.
 * All commands are explicit user actions; no scanning loops or silent FilePlay.
 */
object U0459FileBrowserWire {
    const val FILE_LIST_GET = 0x01FB
    const val FILE_PLAY = 0x01FC
    const val MODE_START = 0x01FD
    const val MODE_EXIT = 0x01FE
    const val MODE_GET = 0x01FF
    const val LUT_SELECT = 0x0200
    const val FILENAME_SIZE_SET = 0x0202
    const val CURRENT_INDEX_GET = 0x01F4 // Sequence-derived, not independently proven
    const val CURRENT_INDEX_SET = 0x01F5 // Sequence-derived, disabled
    const val DIR_INFO_GET = 0x01F8

    const val INFO_FILE_NAME_ONLY = 0
    const val INFO_MOVIE_ONLY = 1
    const val INFO_FILE_NAME_AND_MOVIE = 2
    const val MODE_MOVIE_PICKER = 1
    // U04.98: Mac successful RX = 97 entries in 13 pages (fits 8/page);
    // observed Mac 0x8202 protobuf limits=12,16,8, with 8 a combined-type candidate.
    // Original controller starts with 4 before negotiation; using 8 here is an
    // operator-confirmed GET experiment, NOT proof of a recorder-required count.
    const val DEFAULT_UNVERIFIED_COUNT = 8
    const val MAX_PAGE_LIMIT = 64

    private fun putVarint(out: MutableList<Byte>, input: Int) {
        require(input >= 0)
        var v = input
        do {
            val one = v and 0x7F
            v = v ushr 7
            out.add((one or if (v > 0) 0x80 else 0).toByte())
        } while (v != 0)
    }

    private fun putField(out: MutableList<Byte>, field: Int, value: Int) {
        putVarint(out, field shl 3)
        putVarint(out, value)
    }

    /** Proto2-style explicit field presence observed in the macOS name-map findings.
     *  Wire fields 1, 2, 3 and 4 are always encoded, INCLUDING zero-valued fields.
     *  This is still a binary-derived candidate until verified on AG2.
     */
    fun fileListQuery(start: Int, count: Int, infoType: Int = INFO_FILE_NAME_AND_MOVIE): ByteArray {
        require(start in 0..1_000_000 && count in 1..MAX_PAGE_LIMIT)
        require(infoType in 0..2)
        return buildList<Byte> {
            putField(this, 1, start)
            putField(this, 2, count)
            putField(this, 3, 1)
            putField(this, 4, infoType)
        }.toByteArray()
    }

    /**
     * U05.03: Separate Mac x86_64 constructor call 0x10002335C..0x10002336A
     * passes 0,0,1,3 to FileList builder 0x10007F140. This is a GET
     * candidate, NOT a captured Mac BLE write, nor confirmed AG2 behavior.
     * Keep this isolated from normal page request (count >= 1, infoType <= 2).
     */
    fun macStaticRootCandidate(): ByteArray = buildList<Byte> {
        putField(this, 1, 0)  // start
        putField(this, 2, 0)  // count: special Mac static call, not a page
        putField(this, 3, 1)  // forward
        putField(this, 4, 3)  // infoType: special Mac static call
    }.toByteArray()

    /** Mac ModeStart constructor takes mode=1, resumePrevMovie=false.
     *  U04.67 explicitly serializes field 2=false; AG2 response unverified.
     */
    fun startMoviePicker(): ByteArray = byteArrayOf(0x08, MODE_MOVIE_PICKER.toByte(), 0x10, 0x00)
    /** FileBrowserIndexMsg field 1: nonnegative index. */
    fun playFileIndex(index: Int): ByteArray {
        require(index in 0..1_000_000)
        return buildList<Byte> { putField(this, 1, index) }.toByteArray()
    }
    /** FilelistFilenameSizeMsg field 1: negotiated maximum filename bytes. */
    fun filenameSize(size: Int): ByteArray {
        require(size in 1..255)
        return buildList<Byte> { putField(this, 1, size) }.toByteArray()
    }

    private data class Field(val id: Int, val wire: Int, val number: Long?, val bytes: ByteArray?)
    private fun varint(data: ByteArray, pos: IntArray): Long {
        var shift = 0
        var value = 0L
        repeat(10) {
            if (pos[0] >= data.size) throw IllegalArgumentException("truncated varint")
            val b = data[pos[0]++].toInt() and 255
            if (shift == 63 && (b and 127) > 1) throw IllegalArgumentException("overflow")
            value = value or ((b and 127).toLong() shl shift)
            if ((b and 128) == 0) return value
            shift += 7
        }
        throw IllegalArgumentException("varint too long")
    }
    private fun fields(data: ByteArray, maxLen: Int = 65_535): List<Field> {
        if (data.size > maxLen) throw IllegalArgumentException("oversized response")
        val pos = intArrayOf(0)
        val result = ArrayList<Field>()
        while (pos[0] < data.size) {
            if (result.size >= 1024) throw IllegalArgumentException("too many fields")
            val tag = varint(data, pos)
            if (tag == 0L || tag > (536870911L shl 3) + 7) throw IllegalArgumentException("bad tag")
            val id = (tag ushr 3).toInt()
            val type = (tag and 7L).toInt()
            when (type) {
                0 -> result.add(Field(id, type, varint(data, pos), null))
                2 -> {
                    val len = varint(data, pos)
                    if (len < 0L || len > data.size - pos[0]) throw IllegalArgumentException("length")
                    val end = pos[0] + len.toInt()
                    result.add(Field(id, type, null, data.copyOfRange(pos[0], end)))
                    pos[0] = end
                }
                1 -> { if (data.size - pos[0] < 8) throw IllegalArgumentException("fixed64"); pos[0] += 8 }
                5 -> { if (data.size - pos[0] < 4) throw IllegalArgumentException("fixed32"); pos[0] += 4 }
                else -> throw IllegalArgumentException("bad wire type")
            }
        }
        return result
    }

    data class FileListPage(val entries: List<SsdBrowserEntry>, val total: Int?, val start: Int)

    /** Only call with a live, packet-correlated recorder application response. */
    fun parseFileList(body: ByteArray, requestedStart: Int): FileListPage? = try {
        val fields = fields(body)
        // U05.11: Live AG2 RX at 05:52:48.978 establishes this ACTUAL shape:
        //   08 61 = field 1 varint total=97; 12 29 = field 2 FileInfo bytes.
        // The earlier U05.09 static-inventory interpretation was inverted.
        // Strict validation remains: never render truncated/synthetic rows.
        val totals = fields.filter { it.id == 1 && it.wire == 0 }
        val entries = fields.filter { it.id == 2 && it.wire == 2 }
        val starts = fields.filter { it.id == 3 && it.wire == 0 }
        if (totals.size > 1 || starts.size > 1 || entries.size > MAX_PAGE_LIMIT) null else {
            val totalRaw = totals.firstOrNull()?.number
            val startRaw = starts.firstOrNull()?.number ?: requestedStart.toLong()
            if (startRaw !in 0L..1_000_000L || totalRaw != null && totalRaw !in 0L..1_000_000L) null else {
                val total = totalRaw?.toInt()
                val start = startRaw.toInt()
                val decoded = entries.mapIndexed { offset, entry ->
                    val inner = fields(entry.bytes ?: throw IllegalArgumentException("missing FileInfo"), 4096)
                    val names = inner.filter { it.id == 2 && it.wire == 2 }
                    if (names.size != 1) throw IllegalArgumentException("filename missing/ambiguous")
                    val bytes = names.single().bytes ?: throw IllegalArgumentException("no filename bytes")
                    if (bytes.isEmpty() || bytes.size > 1024) throw IllegalArgumentException("name size")
                    val name = bytes.toString(Charsets.UTF_8).trimEnd('\u0000')
                    if (name.isBlank() || name.length > 255 || name.any { it.code < 32 || it.code == 127 || it == '\uFFFD' })
                        throw IllegalArgumentException("invalid filename")
                    SsdBrowserEntry(start + offset, name)
                }
                if (total == null && decoded.isEmpty()) null else if (total != null && total < decoded.size) null else
                    FileListPage(decoded, total, start)
            }
        }
    } catch (_: IllegalArgumentException) { null }

    /** FileResultMsg field 1: response status (0 presumed OK, 1 presumed Loading). */
    fun parseFileResult(body: ByteArray): Int? = try {
        fields(body, 256).firstOrNull { it.id == 1 && it.wire == 0 }?.number?.toInt()
    } catch (_: IllegalArgumentException) { null }

    /** ModeMsg field 1. Null means unsupported/unrecognized. */
    fun parseMode(body: ByteArray): Int? = try {
        fields(body, 256).firstOrNull { it.id == 1 && it.wire == 0 }?.number?.toInt()?.takeIf { it in 0..2 }
    } catch (_: IllegalArgumentException) { null }

    /** QueryCountLimitMsg: 1/2/3 per filename / movie / combined info type. */
    fun parseCountLimit(body: ByteArray, infoType: Int = 0): Int? = try {
        fields(body, 256).firstOrNull { it.id == infoType + 1 && it.wire == 0 }
            ?.number?.toInt()?.takeIf { it in 1..MAX_PAGE_LIMIT }
    } catch (_: IllegalArgumentException) { null }
}
