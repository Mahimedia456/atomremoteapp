package com.atomosremote.nativeapp.input

/**
 * 20.03.6.10-6.13 R2
 *
 * Distinguishes a real command-owner conflict from the normal PacketLink E0
 * heartbeat. operationInFlight/pendingOps are expected to be non-empty during
 * heartbeat turns and must not permanently block a manual Input-details read.
 */
object InputExtendedStartPolicy {
    fun hasRealOwner(
        coreInputRunning: Boolean,
        bootstrapRunning: Boolean,
        pendingRemoteLabel: String?
    ): Boolean =
        coreInputRunning ||
            bootstrapRunning ||
            !pendingRemoteLabel.isNullOrBlank()

    const val RETRY_WHILE_PACKETLINK_BUSY_MS: Long = 37L
}
