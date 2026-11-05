package com.atomosremote.nativeapp.ui.settings

/**
 * B47.56: locally encoded SwiftProtobuf message BODY examples, based on
 * exact compiled metadata from the official ATOMOS Remote Contents.zip.
 *
 * This class has NO BLE/GATT/PacketLink dependency and NO command sender.
 * SET requests must NOT be transmitted merely because their message body can
 * be serialized. Command, request semantics, acknowledgement and physical
 * post-GET confirmation have not all been proven.
 */
internal object SettingsB4756ProtoCodec {
    internal enum class Proof { COMPILED_PROTO_FIELD_MAP, GET_PHYSICAL_AB, SET_UNVERIFIED }

    internal data class Candidate(
        val messageType: String,
        val body: ByteArray,
        val proof: Proof = Proof.COMPILED_PROTO_FIELD_MAP,
        val setTransmissionAuthorized: Boolean = false
    ) {
        fun hex(): String = body.joinToString(separator = "") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }.uppercase()
    }

    // The exact compiled DateTimeMsg name map has field #1 "datetime".
    internal fun dateTimeMessage(recorderFormatTimestamp: String): Candidate {
        require(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}").matches(recorderFormatTimestamp)) {
            "Use exact yyyy-MM-dd HH:mm:ss; timezone conversion is NOT authorized"
        }
        val text = recorderFormatTimestamp.toByteArray(Charsets.UTF_8)
        return Candidate("DateTimeMsg", byteArrayOf(0x0A) + varint(text.size) + text)
    }

    // Exact compiled DateTimeFormatMsg map: #1 date_format, #2 time_format.
    // Numeric enum ranges here reflect only the known A/B observations and
    // the published Swift enum case count; this does NOT establish a SET.
    internal fun dateTimeFormatMessage(dateFormatId: Int, timeFormatId: Int): Candidate {
        require(dateFormatId == 0) { "Only dateFormatId=0 observed in available recorder evidence" }
        require(timeFormatId in 0..1) { "Unknown TimeFormatState enum" }
        return Candidate("DateTimeFormatMsg", byteArrayOf(0x08) + varint(dateFormatId) + byteArrayOf(0x10) + varint(timeFormatId))
    }

    internal fun fanTurboCoolMessage(on: Boolean): Candidate =
        Candidate("FanTurboCoolMsg", byteArrayOf(0x08, (if (on) 1 else 0).toByte()))

    internal fun fanDimDisplayMessage(on: Boolean): Candidate =
        Candidate("FanDimDisplayMsg", byteArrayOf(0x08, (if (on) 1 else 0).toByte()))

    private fun varint(nonnegative: Int): ByteArray {
        require(nonnegative >= 0)
        var remaining = nonnegative
        val out = ArrayList<Byte>(5)
        do {
            var part = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) part = part or 0x80
            out.add(part.toByte())
        } while (remaining != 0)
        return out.toByteArray()
    }
}
