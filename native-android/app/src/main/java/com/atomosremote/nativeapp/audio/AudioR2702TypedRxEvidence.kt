package com.atomosremote.nativeapp.audio

/**
 * R27.02: PASSIVE inspection of independently typed Mic/Line responses.
 * Not a command serializer, not proof of hardware GET, and no setters.
 * No numeric gain, frame delay, or input-source value is published here.
 */
object AudioR2702TypedRxEvidence {
    fun inspect(messageType: Int, body: ByteArray, local: Boolean, packetId: Int,
                packetMatched: Boolean, pendingPurpose: String): String? {
        // A valid AtomMessage has already been checked by GattClient's existing
        // strict demultiplexer BEFORE calling this observer.
        val typed = when (messageType) {
            0x86B3 -> "INPUT_SOURCE_READ_CANDIDATE"
            0x86B6 -> "GAIN_VALUES_READ_CANDIDATE"
            0x86B9 -> "FRAME_DELAY_READ_CANDIDATE"
            0x86B4 -> "INPUT_SOURCE_SET_RESPONSE_NOT_READBACK"
            0x86B7 -> "GAIN_SET_RESPONSE_NOT_READBACK"
            0x86BA -> "FRAME_DELAY_SET_RESPONSE_NOT_READBACK"
            else -> return null
        }
        val safePurpose = pendingPurpose.take(100).replace(Regex("[^A-Za-z0-9:_./-]"), "_")
        val safeBody = body.take(192).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        val fieldShape = AudioR2701WireInspector.describe(body)
        return "MIC_TYPED_PASSIVE type=$typed response=0x${messageType.toString(16).uppercase()} " +
            "packetId=$packetId local=$local packetMatched=$packetMatched " +
            "pending=$safePurpose size=${body.size} raw=${safeBody} " +
            "fields=$fieldShape verifiedNumeric=false " +
            "requiresFreshCommandCorrelatedReadback=true"
    }
}
