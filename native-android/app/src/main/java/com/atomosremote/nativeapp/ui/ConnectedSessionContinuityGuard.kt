package com.atomosremote.nativeapp.ui

class ConnectedSessionContinuityGuard {
    private var recoveryIssuedForCurrentDisconnect = false

    fun shouldRecover(hardDisconnected: Boolean, pairingAccepted: Boolean): Boolean {
        if (!hardDisconnected) {
            recoveryIssuedForCurrentDisconnect = false
            return false
        }
        if (!pairingAccepted || recoveryIssuedForCurrentDisconnect) return false
        recoveryIssuedForCurrentDisconnect = true
        return true
    }
}
