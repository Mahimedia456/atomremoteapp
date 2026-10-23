package com.atomosremote.nativeapp.media

/**
 * M05-M07 Media-only typed-readback foundation.
 * All values start unknown. Never reinterpret missing readbacks as zero/empty/off.
 * Source evidence (request/reply/payload, AG2 model, capture ID) must pass M04 before
 * any payload parser is permitted to mark data as recorder-confirmed.
 *
 * M05-M07 intentionally makes ZERO BLE/GATT calls and ZERO recorder writes.
 * No AG2 Media request/reply ID is proven by the supplied macOS symbols workbook.
 */
internal data class MediaM05DriveDetails(
    val slot: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val mediaName: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val inserted: MediaVerifiedValue<Boolean> = MediaVerifiedValue(),
    val totalBytes: MediaVerifiedValue<Long> = MediaVerifiedValue(),
    val freeBytes: MediaVerifiedValue<Long> = MediaVerifiedValue(),
    val estimatedSeconds: MediaVerifiedValue<Long> = MediaVerifiedValue(),
)

internal data class MediaM06ClipDetails(
    val filename: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val folder: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val sizeBytes: MediaVerifiedValue<Long> = MediaVerifiedValue(),
    val durationSeconds: MediaVerifiedValue<Long> = MediaVerifiedValue(),
    val codec: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val resolution: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val frameRate: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val timecode: MediaVerifiedValue<String> = MediaVerifiedValue(),
)

internal data class MediaM06BrowserDetails(
    val currentDirectory: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val directoryInfo: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val fileCount: MediaVerifiedValue<Int> = MediaVerifiedValue(),
    val currentIndex: MediaVerifiedValue<Int> = MediaVerifiedValue(),
    val folders: List<MediaVerifiedValue<String>> = emptyList(),
    val clips: List<MediaM06ClipDetails> = emptyList(),
    val listVerified: Boolean = false,
)

internal data class MediaM07NamingRecoveryDetails(
    val filename: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val filenameExample: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val filepathSource: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val filepathSourceOptions: List<MediaVerifiedValue<String>> = emptyList(),
    val optionsVerified: Boolean = false,
    val recoveryState: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val lastRecordingFilename: MediaVerifiedValue<String> = MediaVerifiedValue(),
)

internal data class MediaM05M07Snapshot(
    val mediaState: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val capacityBytes: MediaVerifiedValue<Long> = MediaVerifiedValue(),
    val availableBytes: MediaVerifiedValue<Long> = MediaVerifiedValue(),
    val remainingSeconds: MediaVerifiedValue<Long> = MediaVerifiedValue(),
    val changeId: MediaVerifiedValue<String> = MediaVerifiedValue(),
    val drives: List<MediaM05DriveDetails> = emptyList(),
    val drivesVerified: Boolean = false,
    val browser: MediaM06BrowserDetails = MediaM06BrowserDetails(),
    val naming: MediaM07NamingRecoveryDetails = MediaM07NamingRecoveryDetails(),
)

/** Proof object cannot be constructed from a UI guess or from an E0 transport ACK. */
internal class MediaM05M07Evidence private constructor(
    val operation: MediaM04ProofGate.Operation,
    val recorderAddress: String,
    val evidenceCaptureId: String,
    val typedResponseId: Int,
) {
    companion object {
        fun validate(
            operation: MediaM04ProofGate.Operation,
            requestId: Int,
            typedResponseId: Int?,
            rawPayload: ByteArray,
            recorderModel: String,
            recorderAddress: String,
            evidenceCaptureId: String,
        ): MediaM05M07Evidence? {
            if (recorderAddress.isBlank() || typedResponseId == null) return null
            if (!MediaM04ReceiveContract.accepts(
                    operation, requestId, typedResponseId, rawPayload,
                    recorderModel, evidenceCaptureId)) return null
            return MediaM05M07Evidence(operation, recorderAddress, evidenceCaptureId, typedResponseId)
        }
    }
}

internal object MediaM05M07Format {
    fun bytes(value: MediaVerifiedValue<Long>): String {
        val n = verifiedLong(value) ?: return "Not reported"
        val unit = when {
            n >= 1_099_511_627_776L -> "TiB" to 1_099_511_627_776.0
            n >= 1_073_741_824L -> "GiB" to 1_073_741_824.0
            n >= 1_048_576L -> "MiB" to 1_048_576.0
            else -> "bytes" to 1.0
        }
        return if (unit.first == "bytes") "$n bytes" else
            "%.1f %s".format(java.util.Locale.US, n / unit.second, unit.first)
    }

    fun time(value: MediaVerifiedValue<Long>): String {
        val s = verifiedLong(value) ?: return "Not reported"
        return "%02d:%02d:%02d".format(java.util.Locale.US, s / 3600, (s % 3600) / 60, s % 60)
    }

    fun count(value: MediaVerifiedValue<Int>): String =
        if (value.verified && !value.sourceCaptureId.isNullOrBlank() && value.value != null && value.value >= 0)
            value.value.toString() else "Not reported"

    fun inserted(value: MediaVerifiedValue<Boolean>): String =
        if (!value.verified || value.sourceCaptureId.isNullOrBlank() || value.value == null) "Not reported"
        else if (value.value) "Inserted" else "Not inserted"

    private fun verifiedLong(value: MediaVerifiedValue<Long>): Long? =
        value.value?.takeIf { it >= 0 && value.verified && !value.sourceCaptureId.isNullOrBlank() }
}
