package com.atomosremote.nativeapp.media

/**
 * U04.46 - Isolated, read-only gateway contract. NO BLE send method exists here.
 * The original Mac UI demonstrates browsing and selected playback, but those
 * screenshots do not establish the AG2 BLE wire protocol or firmware support.
 *
 * NEVER reinterpret the existing 0x01FC diagnostic as a verified list GET.
 * The Mac 0x01FB constructor has four inputs. Their PROTOBUF TAGS / serialized
 * wire representation and recorder reply are NOT yet established.
 */
object SsdFileBrowserGatewayU0446 {
    const val DIRECTORY_QUERY_CANDIDATE = 0x01FB
    const val INDEX_OPERATION_UNVERIFIED = 0x01FC
    const val MODE_ACTION_UNVERIFIED = 0x01FD
    const val MODE_READBACK_CANDIDATE = 0x0200

    enum class Meaning {
        DIRECTORY_QUERY_STATIC_CANDIDATE,
        INDEX_ACTION_NOT_DIRECTORY_QUERY,
        MODE_ACTION_UNVERIFIED,
        MODE_READBACK_STATIC_CANDIDATE,
        UNMAPPED
    }

    data class DirectoryArguments(
        val start: Int,
        val count: Int,
        val forward: Boolean,
        val infoType: Int
    ) {
        init {
            require(start >= 0 && count >= 0) { "Negative list index/count" }
            require(infoType in 0..255) { "Info type outside byte range" }
        }
    }
    // Machine-code-confirmed INITIAL call arguments only: not transmitted bytes.
    val macInitialBrowseArguments = DirectoryArguments(0, 0, true, 3)

    fun meaningForOpcode(opcode: Int): Meaning = when (opcode) {
        DIRECTORY_QUERY_CANDIDATE -> Meaning.DIRECTORY_QUERY_STATIC_CANDIDATE
        INDEX_OPERATION_UNVERIFIED -> Meaning.INDEX_ACTION_NOT_DIRECTORY_QUERY
        MODE_ACTION_UNVERIFIED -> Meaning.MODE_ACTION_UNVERIFIED
        MODE_READBACK_CANDIDATE -> Meaning.MODE_READBACK_STATIC_CANDIDATE
        else -> Meaning.UNMAPPED
    }

    data class WireFrameEvidence(
        val transportCreditOnly: Boolean,
        val packetId: Int?,
        val opcode: Int?,
        val body: ByteArray,
        val meaning: Meaning,
        val isStructurallyComplete: Boolean
    ) {
        // Even a structurally correct frame is NOT an AG2-validated response.
        val recorderAccepted: Boolean get() = false
    }

    /**
     * Parses ONLY captured, complete E0 frames for offline evidence inspection.
     * Byte 0 is transport header, byte 1 packet ID, LE16 payload length,
     * LE16 command ID, then the declared payload. No BLE/Android dependency.
     */
    fun inspectCapturedFrame(frame: ByteArray): WireFrameEvidence? {
        if (frame.size == 1 && (frame[0].toInt() and 255) == 0xE0) {
            return WireFrameEvidence(true, null, null, byteArrayOf(), Meaning.UNMAPPED, true)
        }
        if (frame.size < 6 || (frame[0].toInt() and 255) != 0xE0) return null
        val declared = (frame[2].toInt() and 255) or ((frame[3].toInt() and 255) shl 8)
        if (frame.size != 6 + declared) return null
        val opcode = (frame[4].toInt() and 255) or ((frame[5].toInt() and 255) shl 8)
        return WireFrameEvidence(
            false, frame[1].toInt() and 255, opcode, frame.copyOfRange(6, frame.size),
            meaningForOpcode(opcode), true
        )
    }

    data class EvidenceGates(
        val bleSessionReady: Boolean = false,
        val recorderPlaybackReadbackVerified: Boolean = false,
        val browserModeReadbackVerified: Boolean = false,
        val directoryQueryWireVerified: Boolean = false,
        val correlatedDirectoryReplyVerified: Boolean = false,
        val selectedClipPlaybackWireVerified: Boolean = false
    )

    enum class Stage {
        WAIT_FOR_BLE,
        WAIT_FOR_RECORDER_PLAYBACK_READBACK,
        WAIT_FOR_BROWSER_MODE_READBACK,
        WAIT_FOR_ORIGINAL_DIRECTORY_WIRE_CONTRACT,
        WAIT_FOR_CORRELATED_AG2_LIST_REPLY,
        DIRECTORY_UI_DATA_READY_PLAYBACK_LOCKED,
        ALL_CONTRACTS_EVIDENCED_OFFLINE
    }

    fun assess(e: EvidenceGates): Stage = when {
        !e.bleSessionReady -> Stage.WAIT_FOR_BLE
        !e.recorderPlaybackReadbackVerified -> Stage.WAIT_FOR_RECORDER_PLAYBACK_READBACK
        !e.browserModeReadbackVerified -> Stage.WAIT_FOR_BROWSER_MODE_READBACK
        !e.directoryQueryWireVerified -> Stage.WAIT_FOR_ORIGINAL_DIRECTORY_WIRE_CONTRACT
        !e.correlatedDirectoryReplyVerified -> Stage.WAIT_FOR_CORRELATED_AG2_LIST_REPLY
        !e.selectedClipPlaybackWireVerified -> Stage.DIRECTORY_UI_DATA_READY_PLAYBACK_LOCKED
        else -> Stage.ALL_CONTRACTS_EVIDENCED_OFFLINE
    }

    /** This module deliberately has NO transmit, queue, retry, SET, or play method. */
}
