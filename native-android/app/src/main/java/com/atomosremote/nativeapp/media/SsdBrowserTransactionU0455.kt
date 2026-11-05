package com.atomosremote.nativeapp.media

/**
 * Combined evidence-first FileBrowser transaction model for the AG2.
 *
 * NO BLE writer, no auto-retry, no assumed FilePlay. 0x01FB is a static
 * constructor candidate, and protobuf tags / recorder reply are unverified.
 * Do not promote this module to a transmitter without an independently
 * established original-wire contract and a separately reviewed transport hook.
 */
object SsdBrowserTransactionU0455 {
    const val QUERY_OPCODE_STATIC_CANDIDATE = 0x01FB
    const val INDEX_OPERATION_UNVERIFIED = 0x01FC
    const val MODE_OPERATION_UNVERIFIED = 0x01FD
    const val MODE_GET_STATIC_CANDIDATE = 0x0200
    const val PLAY_ALREADY_WIRED = 0x0191
    const val PAUSE_ALREADY_WIRED = 0x0192

    enum class Capability { EXISTING_ANDROID_CONTROL, MAC_STATIC_CANDIDATE, UNVERIFIED_ACTION }
    enum class Verdict {
        NO_TRANSACTION, INVALID_FRAME, CREDIT_ONLY, WRONG_ROUTE, WRONG_PACKET_ID,
        UNCORRELATED_OPCODE, EMPTY_RESPONSE, SYNTACTIC_FILELIST_CANDIDATE,
        INVALID_PROTO, NO_RECORDER_CONFIRMATION
    }
    data class RequestPlan(val opcode: Int, val bodyCandidate: ByteArray,
                           val initialBrowse: Boolean, val fieldsVerified: Boolean = false,
                           val routeVerified: Boolean = false, val transmitAuthorized: Boolean = false)
    data class RxObservation(
        val verdict: Verdict,
        val requestPacketId: Int? = null,
        val actualPacketId: Int? = null,
        val responseOpcode: Int? = null,
        val files: List<SsdBrowserEntry> = emptyList(),
        val requestCorrelated: Boolean = false,
        val onWireSchemaVerified: Boolean = false,
        val recorderConfirmed: Boolean = false
    )
    data class UiProof(
        val activeClipName: String? = null, // No current active-file GET in frozen app.
        val candidateNames: List<SsdBrowserEntry>,
        val receivedFileList: Boolean,
        val status: String,
        val generation: Long,
        val localRx: Int,
        val nonLocalRx: Int,
        val totalFiles: Int?,
        val lastOpcode: Int?,
        val remotePreviousNextAllowed: Boolean = false,
        val remoteSelectClipAllowed: Boolean = false
    )
    data class ControlPolicy(val action: String, val capability: Capability, val canTransmit: Boolean)

    private fun appendVarint(dst: MutableList<Byte>, value: Int) {
        require(value >= 0)
        var v = value
        do {
            val b = v and 127
            v = v ushr 7
            dst.add((b or if (v != 0) 128 else 0).toByte())
        } while (v != 0)
    }
    /** HYPOTHESIS ONLY: Swift property names do not establish field tags. */
    fun previewOriginalMacInitialBrowse(): RequestPlan = candidateQuery(0, 0, true, 3)
    fun candidateQuery(start: Int, count: Int, forward: Boolean, infoType: Int): RequestPlan {
        require(start in 0..1000000 && count in 0..256 && infoType in 0..255)
        val data = mutableListOf<Byte>()
        // Candidate sequential tags: _start=1, _count=2, _forward=3, _infoType=4.
        // Default values OMITTED. Both omission and field tags remain UNVERIFIED.
        if (start > 0) { appendVarint(data, 8); appendVarint(data, start) }
        if (count > 0) { appendVarint(data, 16); appendVarint(data, count) }
        if (forward) { appendVarint(data, 24); appendVarint(data, 1) }
        if (infoType > 0) { appendVarint(data, 32); appendVarint(data, infoType) }
        return RequestPlan(QUERY_OPCODE_STATIC_CANDIDATE, data.toByteArray(),
            initialBrowse = start == 0 && count == 0 && forward && infoType == 3)
    }

    fun controls(): List<ControlPolicy> = listOf(
        ControlPolicy("play", Capability.EXISTING_ANDROID_CONTROL, true),
        ControlPolicy("pause", Capability.EXISTING_ANDROID_CONTROL, true),
        ControlPolicy("directory-list", Capability.MAC_STATIC_CANDIDATE, false),
        ControlPolicy("previous-clip", Capability.UNVERIFIED_ACTION, false),
        ControlPolicy("next-clip", Capability.UNVERIFIED_ACTION, false),
        ControlPolicy("select-and-play", Capability.UNVERIFIED_ACTION, false)
    )

    /**
     * Parse a COMPLETE E0/E8 transport frame; never count a credit byte as data.
     * Decoder's protobuf fields are only a hypothesis; an intact frame and
     * matching packet ID do NOT imply AG2 accepted the request or schema.
     * Expected response opcode must come from independent wire evidence.
     */
    fun inspectCompleteFrame(
        frame: ByteArray,
        expectedPacketId: Int?,
        expectedResponseOpcode: Int?,
        expectedLocal: Boolean?
    ): RxObservation {
        if (frame.size == 1 && ((frame[0].toInt() and 255) in listOf(0xE0, 0xE8)))
            return RxObservation(Verdict.CREDIT_ONLY)
        if (frame.size < 6 || frame[0].toInt().and(255) !in listOf(0xE0, 0xE8))
            return RxObservation(Verdict.INVALID_FRAME)
        val actualLocal = (frame[0].toInt() and 255) == 0xE8
        val packetId = frame[1].toInt() and 255
        val length = (frame[2].toInt() and 255) or ((frame[3].toInt() and 255) shl 8)
        val opcode = (frame[4].toInt() and 255) or ((frame[5].toInt() and 255) shl 8)
        val base = RxObservation(Verdict.NO_TRANSACTION, expectedPacketId, packetId, opcode)
        if (length != frame.size - 6) return base.copy(verdict = Verdict.INVALID_FRAME)
        if (expectedLocal != null && expectedLocal != actualLocal) return base.copy(verdict = Verdict.WRONG_ROUTE)
        if (expectedPacketId == null || expectedResponseOpcode == null)
            return base.copy(verdict = Verdict.NO_TRANSACTION)
        if (packetId != expectedPacketId) return base.copy(verdict = Verdict.WRONG_PACKET_ID)
        if (opcode != expectedResponseOpcode) return base.copy(verdict = Verdict.UNCORRELATED_OPCODE)
        if (length == 0 || opcode == 0xFFFE || opcode == 0xFFFF)
            return base.copy(verdict = Verdict.EMPTY_RESPONSE)
        val hypothesis = SsdFileListMacCandidateDecoderU0453.inspectCapturedFrame(
            frame, expectedPacketId, expectedResponseOpcode, actualLocal
        )
        return if (hypothesis.verdict == SsdFileListMacCandidateDecoderU0453.Verdict.HYPOTHETICAL_LIST_STRUCTURE)
            base.copy(verdict = Verdict.SYNTACTIC_FILELIST_CANDIDATE,
                files = hypothesis.entries.map { SsdBrowserEntry(it.index, it.name) },
                requestCorrelated = true, onWireSchemaVerified = false, recorderConfirmed = false)
        else base.copy(verdict = Verdict.INVALID_PROTO, requestCorrelated = true)
    }

    /** Only recorded, already-decoded state is displayed, never synthetic fixtures. */
    fun fromExistingRecorderState(s: SsdBrowserState): UiProof = UiProof(
        activeClipName = null,
        candidateNames = s.entries,
        receivedFileList = s.recorderValidated,
        status = s.status,
        generation = s.generation,
        localRx = s.localApplicationRx,
        nonLocalRx = s.nonLocalApplicationRx,
        totalFiles = s.totalFiles,
        lastOpcode = s.lastApplicationOpcode
    )
}
