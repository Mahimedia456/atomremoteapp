package com.atomosremote.nativeapp.media.ssdreadonly

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * U04.37. Isolated pure Kotlin RX inspector. No Android/BLE/PacketLink references and NO TX API.
 *
 * Schema below is a CANDIDATE layout from the earlier macOS metadata investigation,
 * NOT a confirmed Shogun Ultra AG2 FileList response schema. A syntactically valid
 * candidate must never be treated as proof of recorder support or a verified filename.
 */
object U0437SsdRxEvidence {
    const val FILELIST_REQUEST_OPCODE = 0x01FC
    const val FILELIST_CANDIDATE_RESPONSE_OPCODE = 0x81FC
    const val MAX_BODY = 65535
    const val MAX_FILES = 256
    const val MAX_NAME_BYTES = 255

    enum class Classification {
        CREDIT_ONLY, DIFFERENT_APPLICATION_MESSAGE, FILELIST_EMPTY_BODY,
        FILELIST_MALFORMED, FILELIST_CANDIDATE, TRANSPORT_ERROR_MARKER
    }

    data class CandidateFile(val provisionalIndex: Int, val filename: String)

    data class Inspection(
        val classification: Classification,
        val local: Boolean?,
        val packetId: Int?,
        val opcode: Int?,
        val files: List<CandidateFile> = emptyList(),
        val candidateTotal: Int? = null,
        val candidateStart: Int? = null,
        val diagnostic: String,
        val schemaConfirmedOnRecorder: Boolean = false,
        val requestPacketCorrelated: Boolean = false
    ) {
        val mayDisplayAsVerifiedFilenames: Boolean get() = false
    }

    /** This is for a *reassembled* application packet after the existing transport receives it. */
    fun inspectApplicationPacket(local: Boolean, packetId: Int, opcode: Int, body: ByteArray): Inspection {
        require(packetId in 0..255) { "Packet id outside byte range" }
        require(opcode in 0..65535) { "Opcode outside ushort range" }
        if (opcode == 0xFFFF) return Inspection(
            Classification.TRANSPORT_ERROR_MARKER, local, packetId, opcode,
            diagnostic = "0xFFFF application marker seen; meaning and association not proven")
        if (opcode != FILELIST_REQUEST_OPCODE && opcode != FILELIST_CANDIDATE_RESPONSE_OPCODE) {
            return Inspection(Classification.DIFFERENT_APPLICATION_MESSAGE, local, packetId, opcode,
                diagnostic = "Application packet is not a FileList opcode candidate")
        }
        if (body.isEmpty()) return Inspection(Classification.FILELIST_EMPTY_BODY, local, packetId, opcode,
            diagnostic = "FileList opcode candidate with empty body; no filename proof")
        return try {
            val parsed = decodeCandidate(body)
            Inspection(
                Classification.FILELIST_CANDIDATE, local, packetId, opcode,
                files = parsed.files, candidateTotal = parsed.total, candidateStart = parsed.start,
                diagnostic = "Provisional schema parse only. Compare exact filenames on recorder before enabling UI.")
        } catch (error: IllegalArgumentException) {
            Inspection(Classification.FILELIST_MALFORMED, local, packetId, opcode,
                diagnostic = error.message ?: "Unparseable candidate")
        }
    }

    /** Credit-only E0/E8 frames never contain an application packet or filenames. */
    fun inspectCreditOnlyFrame(hex: String): Inspection? {
        val normalized = hex.trim().uppercase()
        if (normalized != "E0" && normalized != "E8") return null
        return Inspection(Classification.CREDIT_ONLY, normalized == "E8", null, null,
            diagnostic = "PacketLink credit-only frame; no application payload")
    }

    private data class Field(val number: Int, val wire: Int, val integer: Long?, val bytes: ByteArray?)
    private data class Parsed(val files: List<CandidateFile>, val total: Int?, val start: Int?)

    private fun readVarint(data: ByteArray, cursor: IntArray): Long {
        var value = 0L
        for (i in 0..9) {
            if (cursor[0] == data.size) throw IllegalArgumentException("Truncated protobuf varint")
            val b = data[cursor[0]++].toInt() and 0xff
            if (i == 9 && (b and 0xfe) != 0) throw IllegalArgumentException("Protobuf varint overflow")
            value = value or ((b and 0x7f).toLong() shl (7 * i))
            if ((b and 0x80) == 0) return value
        }
        throw IllegalArgumentException("Oversized protobuf varint")
    }

    private fun parseFields(bytes: ByteArray, limit: Int): List<Field> {
        if (bytes.size > MAX_BODY) throw IllegalArgumentException("Oversized body")
        val result = ArrayList<Field>()
        val pos = intArrayOf(0)
        while (pos[0] < bytes.size) {
            if (result.size >= limit) throw IllegalArgumentException("Too many protobuf fields")
            val rawTag = readVarint(bytes, pos)
            if (rawTag !in 1L..0xffffffffL) throw IllegalArgumentException("Invalid tag")
            val fieldNumber = rawTag ushr 3
            val wire = (rawTag and 7L).toInt()
            if (fieldNumber !in 1L..536870911L) throw IllegalArgumentException("Invalid field number")
            when (wire) {
                0 -> result.add(Field(fieldNumber.toInt(), wire, readVarint(bytes, pos), null))
                1 -> {
                    if (bytes.size - pos[0] < 8) throw IllegalArgumentException("Short fixed64")
                    pos[0] += 8
                    result.add(Field(fieldNumber.toInt(), wire, null, null))
                }
                2 -> {
                    val length = readVarint(bytes, pos)
                    if (length < 0 || length > bytes.size - pos[0])
                        throw IllegalArgumentException("Length-delimited field exceeds body")
                    val end = pos[0] + length.toInt()
                    result.add(Field(fieldNumber.toInt(), wire, null, bytes.copyOfRange(pos[0], end)))
                    pos[0] = end
                }
                5 -> {
                    if (bytes.size - pos[0] < 4) throw IllegalArgumentException("Short fixed32")
                    pos[0] += 4
                    result.add(Field(fieldNumber.toInt(), wire, null, null))
                }
                else -> throw IllegalArgumentException("Unsupported protobuf wire type")
            }
        }
        return result
    }

    private fun boundedInt(value: Long?, label: String): Int? {
        if (value == null) return null
        if (value !in 0L..1000000L) throw IllegalArgumentException("Candidate $label outside safe bounds")
        return value.toInt()
    }

    private fun strictUtf8(bytes: ByteArray): String {
        if (bytes.isEmpty() || bytes.size > MAX_NAME_BYTES) throw IllegalArgumentException("Invalid filename byte length")
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val string = try { decoder.decode(ByteBuffer.wrap(bytes)).toString().trimEnd('\u0000') }
        catch (error: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Malformed UTF-8 filename", error)
        }
        if (string.isEmpty() || string.length > 255 || string.any {
            it.code < 32 || it.code == 127 || it == '\ufffd'
        }) throw IllegalArgumentException("Unsafe or invalid filename")
        return string
    }

    private fun decodeCandidate(body: ByteArray): Parsed {
        val root = parseFields(body, 512)
        val totals = root.filter { it.number == 1 && it.wire == 0 }
        val starts = root.filter { it.number == 3 && it.wire == 0 }
        if (totals.size > 1 || starts.size > 1) throw IllegalArgumentException("Duplicate candidate count/index")
        val total = boundedInt(totals.firstOrNull()?.integer, "total")
        val start = boundedInt(starts.firstOrNull()?.integer, "start")
        val entries = root.filter { it.number == 2 && it.wire == 2 }
        if (entries.size > MAX_FILES) throw IllegalArgumentException("Too many candidate files")
        if (entries.isEmpty() && total == null) throw IllegalArgumentException("No candidate file entries or count")
        if (entries.isEmpty() && total != 0) throw IllegalArgumentException("Count without files is not proof of empty SSD")
        val files = entries.mapIndexed { index, field ->
            val nested = parseFields(field.bytes ?: throw IllegalArgumentException("Missing nested payload"), 128)
            val filenameFields = nested.filter { it.number == 2 && it.wire == 2 }
            if (filenameFields.size != 1) throw IllegalArgumentException("Missing or duplicate candidate filename")
            val filename = strictUtf8(filenameFields.single().bytes!!)
            val idx = (start ?: 0).toLong() + index
            if (idx > 1000000) throw IllegalArgumentException("Candidate index overflow")
            CandidateFile(idx.toInt(), filename)
        }
        if (total != null && start != null && start.toLong() + files.size > total.toLong())
            throw IllegalArgumentException("Candidate count/index inconsistent")
        return Parsed(files, total, start)
    }
}
