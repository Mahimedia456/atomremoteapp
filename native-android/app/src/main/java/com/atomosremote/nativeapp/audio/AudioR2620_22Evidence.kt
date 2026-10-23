package com.atomosremote.nativeapp.audio

/** R26.20-22: read-only evidence built from post-NDI sources and macOS binary.
 * Independent from NDI, networking, BLE pairing, and the PacketLink serializer.
 */
object AudioR2620_22Evidence {
    const val VERIFIED_AUDIO_PAGE_REQUEST = 0x0647
    const val VERIFIED_AUDIO_PAGE_RESPONSE = 0x8647
    const val INDIVIDUAL_AUDIO_GETS_VERIFIED = 0
    const val AUDIO_SET_COMMANDS_VERIFIED = 0

    /** Constructor virtual addresses recovered from the macOS x86_64 Mach-O.
     * Opcode immediate seen in constructor. Argument count derived from call
     * sites; it does NOT establish protobuf field tags or returned values.
     */
    data class GetterConstructor(val method: String, val opcode: Int,
                                 val macOSVA: String, val argumentShape: String)
    val getterConstructors = listOf(
        GetterConstructor("MonitorGet", 0x06A5, "0x10007C0A0", "int"),
        GetterConstructor("MonitorListGet", 0x06A7, "0x10007C150", "int, int"),
        GetterConstructor("PhonesLevelGet", 0x06AB, "0x10007C5B0", "int"),
        GetterConstructor("OrientationGet", 0x06AF, "0x10007C6C0", "int"),
        GetterConstructor("AnalogEmbedGet", 0x06B1, "0x10007C770", "int"),
        GetterConstructor("InputSourceGet", 0x06B4, "0x10007C890", "int"),
        GetterConstructor("GainGet", 0x06B7, "0x10007CA00", "int, int"),
        GetterConstructor("FrameDelayGet", 0x06BA, "0x10007CC40", "int"),
        GetterConstructor("SpeakerLevelGet", 0x06BF, "0x10007CE20", "int"),
        GetterConstructor("SpeakerMuteGet", 0x06C3, "0x10007CFA0", "bool")
    )

    /** Comparing two wire payloads produces observed *field numbers*, never
     * guessed volume/channel labels. No retained snapshots or file writes.
     */
    fun changedRawFields(previousHex: String, currentHex: String): List<Int> {
        val previous = AudioR26WireInspector.inspect(previousHex)
        val current = AudioR26WireInspector.inspect(currentHex)
        if (previous.status != "RAW_PARSED_UNMAPPED" ||
            current.status != "RAW_PARSED_UNMAPPED") return emptyList()
        val a = previous.fields.groupBy { it.number }
        val b = current.fields.groupBy { it.number }
        return (a.keys + b.keys).distinct().sorted().filter { a[it] != b[it] }
    }

    fun confirmedReadbackSummary(pageHex: String): String {
        val inspection = AudioR26WireInspector.inspect(pageHex)
        if (inspection.status != "RAW_PARSED_UNMAPPED") return "No verified Audio page"
        val macFields = AudioR26PageSnapshot.decode(pageHex)
        return if (macFields != null)
            "RX 0x8647: ${inspection.fields.size} raw fields; 11 metadata-based inferred properties"
        else "RX 0x8647: ${inspection.fields.size} raw fields; named mapping unavailable"
    }
}
