package com.atomosremote.nativeapp.ui

/**
 * UI-only one-shot guard for the known post-pair CONNECT preflight error.
 * It does not touch pairing packets or GATT characteristics.
 */
class PairingLinkRecoveryGuard {
    private var attemptedForCurrentError = false

    fun shouldRecover(lastError: String?): Boolean {
        val error = lastError.orEmpty()
        val isKnownPreflightError =
            error.contains("cxtic2 readable=false", ignoreCase = true) ||
            error.contains("cxtic4 readable=false", ignoreCase = true) ||
            error.contains("CONNECT preflight endpoints", ignoreCase = true)

        if (!isKnownPreflightError) {
            attemptedForCurrentError = false
            return false
        }

        if (attemptedForCurrentError) return false
        attemptedForCurrentError = true
        return true
    }
}
