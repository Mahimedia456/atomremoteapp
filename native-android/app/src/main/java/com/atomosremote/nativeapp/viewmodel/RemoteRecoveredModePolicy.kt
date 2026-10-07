package com.atomosremote.nativeapp.viewmodel

object RemoteRecoveredModePolicy {
    fun afterTrustedRecovery(
        previous: RemoteTransportUiController.Mode
    ): RemoteTransportUiController.Mode =
        when (previous) {
            RemoteTransportUiController.Mode.RECORD -> RemoteTransportUiController.Mode.RECORD
            RemoteTransportUiController.Mode.PLAYBACK -> RemoteTransportUiController.Mode.PLAYBACK
            RemoteTransportUiController.Mode.UNKNOWN,
            RemoteTransportUiController.Mode.RETURNING_TO_RECORD ->
                RemoteTransportUiController.Mode.UNKNOWN
        }

    fun label(mode: RemoteTransportUiController.Mode): String =
        when (mode) {
            RemoteTransportUiController.Mode.RECORD -> "Record"
            RemoteTransportUiController.Mode.PLAYBACK -> "Playback"
            RemoteTransportUiController.Mode.RETURNING_TO_RECORD -> "Returning to Record"
            RemoteTransportUiController.Mode.UNKNOWN -> "Unknown"
        }
}
