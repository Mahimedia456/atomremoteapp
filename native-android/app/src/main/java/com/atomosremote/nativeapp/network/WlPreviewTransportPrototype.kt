package com.atomosremote.nativeapp.network

/**
 * WL-P6 preview transport candidate model.
 *
 * Passive only:
 * - no port probing
 * - no socket connect
 * - no RTSP/NDI assumption
 * - no Live Feed UI wiring
 */
internal data class WlPreviewCandidate(
    val serviceType: String,
    val host: String,
    val port: Int,
    val source: String
)

internal object WlPreviewTransportPrototype {
    const val PHASE = "WL-P6"
    const val ACTIVE_RECEIVER_ENABLED = false

    private val mediaServiceHints = listOf(
        "_ndi._tcp",
        "_rtsp._tcp",
        "_http._tcp",
        "_https._tcp"
    )

    fun isMediaServiceCandidate(type: String): Boolean {
        val normalized = type.lowercase()
        return mediaServiceHints.any { normalized.contains(it) }
    }

    fun isReceiverAllowed(): Boolean = ACTIVE_RECEIVER_ENABLED
}
