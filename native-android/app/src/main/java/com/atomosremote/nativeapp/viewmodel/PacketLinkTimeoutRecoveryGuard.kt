package com.atomosremote.nativeapp.viewmodel

class PacketLinkTimeoutRecoveryGuard {
    private var recoveryIssued = false

    fun shouldRecover(phase: String, error: String?): Boolean {
        val packetLinkTimeout =
            phase == "error" &&
            error.orEmpty().contains("PacketLink ATT", ignoreCase = true) &&
            error.orEmpty().contains("recovery required", ignoreCase = true)

        if (!packetLinkTimeout) {
            recoveryIssued = false
            return false
        }
        if (recoveryIssued) return false
        recoveryIssued = true
        return true
    }
}
