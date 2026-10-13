package com.atomosremote.nativeapp.audio

/** macOS x86_64 constructor immediate evidence, NOT yet recorder-confirmed individually.
 *  Constructor immediates recovered from Contents(20261011-114525).zip.
 *  No inferred/unverified command is ever transmitted. All SETs prohibited.
 */
data class AudioR26GetDefinition(val method: String, val command: Int, val requiresArguments: Boolean, val evidence: String = "DIRECT_X86_OPCODE")
data class AudioR26LiveValue(
    val method: String, val opcode: Int, val status: String,
    val responseType: Int? = null, val rawHex: String = "",
    val field1Raw: Int? = null
)
data class AudioR26LiveSnapshot(
    val generation: Int = 0, val running: Boolean = false,
    val status: String = "Not started", val activeMethod: String? = null,
    val values: Map<String, AudioR26LiveValue> = emptyMap()
)
/** Full official macOS Audio class registry. Only directly evidenced, argument-free GETs may transmit. */
data class AudioR26AuditKey(
    val method: String, val opcode: Int, val kind: String, val confidence: String,
    val requiresArguments: Boolean
)
object AudioR26RecoveredMap {
    val allKeys = listOf(
        AudioR26AuditKey("MonitorGet", 0x06A5, "GET", "DIRECT_BINARY", false),
        AudioR26AuditKey("MonitorSet", 0x06A6, "SET", "BRACKETED_INFERENCE", false),
        AudioR26AuditKey("MonitorListGet", 0x06A7, "GET", "DIRECT_BINARY", true),
        AudioR26AuditKey("RecordSet", 0x06A8, "SET", "BRACKETED_INFERENCE", false),
        AudioR26AuditKey("RecordEnabledListGet", 0x06A9, "GET", "BRACKETED_INFERENCE", false),
        AudioR26AuditKey("RecordValidListGet", 0x06AA, "GET", "BRACKETED_INFERENCE", false),
        AudioR26AuditKey("PhonesLevelGet", 0x06AB, "GET", "DIRECT_BINARY", false),
        AudioR26AuditKey("PhonesLevelSet", -1, "SET", "UNVERIFIED", false),
        AudioR26AuditKey("PhonesLevelMinMaxGet", -1, "GET", "UNVERIFIED", false),
        AudioR26AuditKey("OrientationGet", 0x06AF, "GET", "DIRECT_BINARY", false),
        AudioR26AuditKey("OrientationSet", 0x06B0, "SET", "BRACKETED_INFERENCE", false),
        AudioR26AuditKey("AnalogEmbedGet", 0x06B1, "GET", "DIRECT_BINARY", false),
        AudioR26AuditKey("AnalogEmbedSet", 0x06B2, "SET", "BRACKETED_INFERENCE", false),
        AudioR26AuditKey("AnalogEmbedListGet", 0x06B3, "GET", "BRACKETED_INFERENCE", false),
        AudioR26AuditKey("InputSourceGet", 0x06B4, "GET", "DIRECT_BINARY", true),
        AudioR26AuditKey("InputSourceSet", 0x06B5, "SET", "BRACKETED_INFERENCE", false),
        AudioR26AuditKey("InputSourceListGet", 0x06B6, "GET", "BRACKETED_INFERENCE", false),
        AudioR26AuditKey("GainGet", 0x06B7, "GET", "DIRECT_BINARY", true),
        AudioR26AuditKey("GainSet", 0x06B8, "SET", "BRACKETED_INFERENCE", false),
        AudioR26AuditKey("GainMinMaxGet", 0x06B9, "GET", "BRACKETED_INFERENCE", false),
        AudioR26AuditKey("FrameDelayGet", 0x06BA, "GET", "DIRECT_BINARY", false),
        AudioR26AuditKey("FrameDelaySet", 0x06BB, "SET", "BRACKETED_INFERENCE", false),
        AudioR26AuditKey("FrameDelayMinMaxGet", 0x06BC, "GET", "BRACKETED_INFERENCE", false),
        AudioR26AuditKey("OrientationChangeableGet", 0x06BD, "GET", "BRACKETED_INFERENCE", false),
        AudioR26AuditKey("IsSpeakerLevelSupported", 0x06BE, "OTHER", "BRACKETED_INFERENCE", false),
        AudioR26AuditKey("SpeakerLevelGet", 0x06BF, "GET", "DIRECT_BINARY", false),
        AudioR26AuditKey("SpeakerLevelSet", 0x06C0, "SET", "BRACKETED_INFERENCE", false),
        AudioR26AuditKey("SpeakerLevelMinMaxGet", 0x06C1, "GET", "BRACKETED_INFERENCE", false),
        AudioR26AuditKey("IsSpeakerMuteSupported", 0x06C2, "OTHER", "BRACKETED_INFERENCE", false),
        AudioR26AuditKey("SpeakerMuteGet", 0x06C3, "GET", "DIRECT_BINARY", false),
        AudioR26AuditKey("SpeakerMuteSet", 0x06C4, "SET", "UNVERIFIED_INFERENCE", false),
        AudioR26AuditKey("AudioFillGet", 0x06C5, "GET", "UNVERIFIED_INFERENCE", false),
        AudioR26AuditKey("AudioFillSet", 0x06C6, "SET", "UNVERIFIED_INFERENCE", false),
    )
    val directGetters: List<AudioR26GetDefinition> = allKeys.filter {
        it.kind == "GET" && it.opcode > 0 && it.confidence == "DIRECT_BINARY"
    }.map { AudioR26GetDefinition(it.method, it.opcode, it.requiresArguments, it.confidence) }
    val safeEmptyGets = directGetters.filterNot { it.requiresArguments }
    val auditCandidates: List<AudioR26GetDefinition> = allKeys.filter {
        it.kind != "SET" // support helper classes get explicit SKIPPED status; never dispatched
    }.map { AudioR26GetDefinition(it.method, it.opcode, it.requiresArguments,
        if (it.kind == "GET") it.confidence else "NOT_A_GET") }
    // R26.13 compatibility: panel can match currently known official getters.
    fun byMethod(method: String): AudioR26GetDefinition? = auditCandidates.firstOrNull { it.method == method && it.command > 0 }
    fun isWireEligible(entry: AudioR26GetDefinition): Boolean =
        entry.evidence == "DIRECT_BINARY" || entry.evidence == "DIRECT_X86_OPCODE"
}
