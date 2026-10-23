package com.atomosremote.nativeapp.audio

/** R26.43-45 binary-backed Audio proof catalog, WITHOUT any new GATT/PacketLink writes.
 *  The only hardware verified GET is 0x0647 with an empty request payload.
 *  The macOS Mach-O constructor IDs do NOT by themselves prove a device command.
 */
object AudioR2643_45WireProof {
    const val VERIFIED_PAGE_GET: Int = 0x0647
    const val VERIFIED_PAGE_RX: Int = 0x8647
    const val OFFICIAL_AUDIO_BINARY_SHA256 = "c95f3c06d0d9c8bf0055ae589177894b4e2f77d7c66319ac3ea16d257b40e8e0"
    enum class State { AG2_RX_VERIFIED, MACOS_OPCODE_ONLY, NO_COMMAND_EVIDENCE }
    data class Proof(val key: String, val getter: String?, val setter: String?,
        val getOpcode: Int?, val setOpcode: Int?, val state: State,
        val hardwareWriteAllowed: Boolean, val hardwareReadAllowed: Boolean,
        val note: String)
    private val directGet = mapOf(
        "MonitorGet" to 0x06A5, "MonitorListGet" to 0x06A7,
        "PhonesLevelGet" to 0x06AB, "OrientationGet" to 0x06AF,
        "AnalogEmbedGet" to 0x06B1, "InputSourceGet" to 0x06B4,
        "GainGet" to 0x06B7, "FrameDelayGet" to 0x06BA,
        "SpeakerLevelGet" to 0x06BF, "SpeakerMuteGet" to 0x06C3)
    private val inferredSet = mapOf(
        "MonitorSet" to 0x06A6, "RecordSet" to 0x06A8,
        "OrientationSet" to 0x06B0, "AnalogEmbedSet" to 0x06B2,
        "InputSourceSet" to 0x06B5, "GainSet" to 0x06B8,
        "FrameDelaySet" to 0x06BB, "SpeakerLevelSet" to 0x06C0,
        "SpeakerMuteSet" to 0x06C4, "AudioFillSet" to 0x06C6)

    fun proofFor(key: String): Proof {
        val setting = AudioR26Catalog.find(key)
        if (setting == null) return Proof(key, null, null, null, null,
            State.NO_COMMAND_EVIDENCE, false, false, "Unknown setting")
        val getter = setting.macMethods.firstOrNull { it in directGet }
        val setter = setting.macMethods.firstOrNull { it.endsWith("Set") }
        val known = getter != null || (setter != null && setter in inferredSet)
        return Proof(key, getter, setter, getter?.let { directGet[it] },
            setter?.let { inferredSet[it] },
            if (known) State.MACOS_OPCODE_ONLY else State.NO_COMMAND_EVIDENCE,
            false, false,
            "Only page 0x0647 is AG2 wire-proven. Independent request body/response and SET+GET proof absent.")
    }
    /** Exact previously observed 5-byte logical message, packetId=0,
     *  payloadLen=0 LE16, messageType=0x0647 LE16. PacketLink framing is
     *  handled only by the frozen existing transport, not by this proof helper.
     */
    fun verifiedPageAtomMessage(packetId: Int = 0): ByteArray? {
        if (packetId !in 0..254) return null
        return byteArrayOf(packetId.toByte(), 0, 0, 0x47, 0x06)
    }
    fun isApprovedSend(opcode: Int, body: ByteArray): Boolean =
        opcode == VERIFIED_PAGE_GET && body.isEmpty()
    fun canSendSet(@Suppress("UNUSED_PARAMETER") key: String,
                   @Suppress("UNUSED_PARAMETER") payload: ByteArray): Boolean = false
    fun isVerifiedTypedRx(command: Int, response: Int, bodyHex: String): Boolean =
        command == VERIFIED_PAGE_GET && response == VERIFIED_PAGE_RX &&
            AudioR26PageSnapshot.decode(bodyHex) != null
    fun rawFieldCount(bodyHex: String): Int = if (AudioR26PageSnapshot.decode(bodyHex) != null) 11 else 0
}
