package com.atomosremote.nativeapp.media

/**
 * Media-only evidence gate. No PacketLink / BLE / GATT writes or reads are
 * started by this file.  GET command IDs from the supplied macOS workbook are
 * MACOS_CLASS_ONLY and MUST NOT be guessed from command order or offsets.
 *
 * Future Media hardware integration must put recorder-confirmed AG2 contracts
 * behind a separately reviewed change; leave this registry empty in M02-M04.
 */
internal object MediaM04ProofGate {
    internal enum class Operation {
        MEDIA_STATE_GET, MEDIA_INFO_GET, MEDIA_CHANGE_ID_GET,
        FILE_RECOVERY_STATE_GET, FILE_BROWSER_MODE_GET, FILE_LIST_GET,
        DIR_INFO_GET, FILENAME_GET, VIDEO_INFO_GET, CURRENT_INDEX_GET
    }

    internal data class VerifiedGetContract(
        val operation: Operation,
        val requestId: Int,
        val responseId: Int,
        val recorderModel: String,
        val captureEvidenceId: String
    ) {
        fun isValid(): Boolean = requestId in 1..0xFFFF &&
            responseId in 1..0xFFFF &&
            recorderModel == "Shogun Ultra AG2" &&
            captureEvidenceId.isNotBlank()
    }

    // Locked to EMPTY: no verified AG2 MEDIA command/reply pair in supplied evidence.
    private val approvedContracts: Map<Operation, VerifiedGetContract> = emptyMap()

    fun contractFor(operation: Operation): VerifiedGetContract? {
        val contract = approvedContracts[operation] ?: return null
        return contract.takeIf { it.isValid() }
    }

    fun mayTransmitGet(operation: Operation): Boolean = contractFor(operation) != null
    fun approvedCount(): Int = approvedContracts.size

    // Irreversible and playback/mode control actions are out of the M02-M04 scope.
    fun mayTransmitControl(): Boolean = false
}

/** Data containers retain uncertainty instead of inventing a drive, folder, or clip. */
internal data class MediaVerifiedValue<T>(
    val value: T? = null,
    val verified: Boolean = false,
    val sourceCaptureId: String? = null
) {
    fun display(): String = if (verified && value != null && !sourceCaptureId.isNullOrBlank())
        value.toString() else "Not reported"
}

internal data class MediaDriveReadback(
    val label: String,
    val inserted: MediaVerifiedValue<Boolean> = MediaVerifiedValue(),
    val mediaName: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val capacity: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val freeSpace: MediaVerifiedValue<String> = MediaVerifiedValue()
)

internal data class MediaBrowserReadback(
    val recorderMode: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val directory: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val fileCount: MediaVerifiedValue<Int> = MediaVerifiedValue(),
    val selectedClip: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val videoInfo: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val folders: List<String> = emptyList(),
    val clips: List<String> = emptyList(),
    val listVerified: Boolean = false
)

internal data class MediaRecorderReadback(
    val mediaState: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val deviceName: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val capacity: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val available: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val remainingTime: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val changeId: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val drives: List<MediaDriveReadback> = emptyList(),
    val browser: MediaBrowserReadback = MediaBrowserReadback(),
    val recoveryState: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val lastRecording: MediaVerifiedValue<String> = MediaVerifiedValue()
)

/**
 * Offline-safe response gate. An E0 ACK or response from another page is never
 * promoted to Media data. M04 has no approved contracts so always rejects.
 */
internal object MediaM04ReceiveContract {
    fun accepts(
        operation: MediaM04ProofGate.Operation,
        requestId: Int,
        typedResponseId: Int?,
        rawPayload: ByteArray,
        recorderModel: String,
        evidenceCaptureId: String
    ): Boolean {
        val contract = MediaM04ProofGate.contractFor(operation) ?: return false
        return contract.requestId == requestId &&
            typedResponseId == contract.responseId &&
            recorderModel == contract.recorderModel &&
            evidenceCaptureId == contract.captureEvidenceId &&
            rawPayload.isNotEmpty()
    }
}
