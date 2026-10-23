package com.atomosremote.nativeapp.audio

/**
 * Audio protocol constructor evidence extracted from the official macOS ATOMOS Remote
 * executable (SHA256 c95f3c06d0d9c8bf0055ae589177894b4e2f77d7c66319ac3ea16d257b40e8e0).
 *
 * ABSOLUTE RULE: Constructor argument shapes are not verified PacketLink wire payloads.
 * These constructors MUST NOT be used to send GET or SET commands to the recorder.
 * Existing proven 0x0647 -> 0x8647 readback remains the sole permitted Audio GET.
 */
object AudioMacRecoveredConstructors {
    const val MACOS_BINARY_SHA256 = "c95f3c06d0d9c8bf0055ae589177894b4e2f77d7c66319ac3ea16d257b40e8e0"

    enum class ArgumentABI { ONE_UNSIGNED_BYTE, FOUR_BYTE_INDEX_ZERO_BYTE_BOOLEAN, COMPLEX_UNRECOVERED }

    data class Evidence(
        val name: String,
        val constructorOpcode: Int,
        val argumentAbi: ArgumentABI,
        val constructorFileOffset: Int,
        val staticConstructorVerified: Boolean,
        val serializedWireBytesVerified: Boolean = false,
        val ag2ResponseVerified: Boolean = false,
        val enableHardwareTx: Boolean = false
    )

    val recoveredGets: List<Evidence> = listOf(
        Evidence("MonitorGet", 0x06A5, ArgumentABI.ONE_UNSIGNED_BYTE, 0x800A0, true),
        Evidence("MonitorListGet", 0x06A7, ArgumentABI.COMPLEX_UNRECOVERED, 0x80150, true),
        Evidence("PhonesLevelGet", 0x06AB, ArgumentABI.ONE_UNSIGNED_BYTE, 0x805B0, true),
        Evidence("OrientationGet", 0x06AF, ArgumentABI.ONE_UNSIGNED_BYTE, 0x806C0, true),
        Evidence("AnalogEmbedGet", 0x06B1, ArgumentABI.ONE_UNSIGNED_BYTE, 0x80770, true),
        Evidence("InputSourceGet", 0x06B4, ArgumentABI.COMPLEX_UNRECOVERED, 0x80890, true),
        Evidence("GainGet", 0x06B7, ArgumentABI.FOUR_BYTE_INDEX_ZERO_BYTE_BOOLEAN, 0x80A00, true),
        Evidence("FrameDelayGet", 0x06BA, ArgumentABI.ONE_UNSIGNED_BYTE, 0x80C40, true),
        Evidence("SpeakerLevelGet", 0x06BF, ArgumentABI.ONE_UNSIGNED_BYTE, 0x80E20, true),
        Evidence("SpeakerMuteGet", 0x06C3, ArgumentABI.ONE_UNSIGNED_BYTE, 0x80FA0, true)
    )

    /** This is a LOCAL ABI argument image for offline comparison, NOT a protobuf payload. */
    fun offlineOneByteAbiImage(name: String, value: Int): ByteArray {
        val proof = recoveredGets.singleOrNull { it.name == name }
            ?: throw IllegalArgumentException("Missing constructor proof: $name")
        require(proof.argumentAbi == ArgumentABI.ONE_UNSIGNED_BYTE) { "Unknown argument ABI: $name" }
        require(value in 0..255) { "Unsigned byte argument outside range" }
        return byteArrayOf(value.toByte())
    }

    /**
     * GainGet x86 code copies a 32-bit index, then 0 byte, then boolean flag.
     * Actual encoded transport wire bytes are NOT established by this layout.
     */
    fun offlineGainAbiImage(index: Int, enabledFlag: Boolean): ByteArray = byteArrayOf(
        (index and 0xff).toByte(),
        ((index ushr 8) and 0xff).toByte(),
        ((index ushr 16) and 0xff).toByte(),
        ((index ushr 24) and 0xff).toByte(),
        0,
        if (enabledFlag) 1 else 0
    )

    /**
     * Explicit hard stop even if an opcode is known. No production code may
     * treat opcode-only or ABI-only evidence as permission to transmit.
     */
    fun requireHardwareTransmissionProof(method: String): Nothing {
        val evidence = recoveredGets.singleOrNull { it.name == method }
        if (evidence == null) throw IllegalArgumentException("Unknown Audio method: $method")
        throw IllegalStateException(
            "$method is macOS constructor evidence only; serialized message, " +
                "AG2 response and GET-after-SET proof are missing. Hardware TX forbidden."
        )
    }
}
