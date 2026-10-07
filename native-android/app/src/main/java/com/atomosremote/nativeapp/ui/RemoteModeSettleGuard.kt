package com.atomosremote.nativeapp.ui

import com.atomosremote.nativeapp.viewmodel.RemoteTransportUiController

/**
 * UI-only guard. It never sends a command.
 * It detects completion of Playback -> Record mode preparation and asks the
 * UI to keep transport buttons disabled for a short quiescence window.
 */
class RemoteModeSettleGuard {
    private var previousPendingAction: String? = null

    fun observe(
        pendingAction: String?,
        mode: RemoteTransportUiController.Mode
    ): Boolean {
        val completedReturn =
            previousPendingAction == "return-record-mode" &&
            pendingAction == null &&
            mode == RemoteTransportUiController.Mode.RECORD

        previousPendingAction = pendingAction
        return completedReturn
    }
}
