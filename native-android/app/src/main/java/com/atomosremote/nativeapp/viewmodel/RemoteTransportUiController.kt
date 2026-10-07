package com.atomosremote.nativeapp.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Agent7 20.03.5.4.6
 *
 * Pure UI/ViewModel transport controller.
 *
 * It NEVER owns or mutates GATT/PacketLink. It only:
 *  - blocks a second transport tap while one action is pending,
 *  - tracks the operator-facing Record/Playback mode,
 *  - waits for recorder-facing Remote state evidence before clearing loaders,
 *  - marks recovery-required instead of blindly unlocking buttons on timeout.
 */
class RemoteTransportUiController {
    enum class Mode { UNKNOWN, RECORD, PLAYBACK, RETURNING_TO_RECORD }

    data class State(
        val mode: Mode = Mode.UNKNOWN,
        val pendingAction: String? = null,
        val pendingLabel: String? = null,
        val expectedLastCommand: String? = null,
        val sawBusy: Boolean = false,
        val generation: Long = 0L,
        val message: String? = null,
        val error: String? = null,
        val recoveryRequired: Boolean = false
    ) {
        val locked: Boolean
            get() = pendingAction != null || recoveryRequired
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Synchronized
    fun begin(
        action: String,
        label: String,
        expectedLastCommand: String,
        currentRemotePhase: String
    ): Long? {
        val current = _state.value
        if (current.locked) return null

        val nextGeneration = current.generation + 1L
        val startsBusy = isBusyPhase(currentRemotePhase)

        _state.value = current.copy(
            pendingAction = action,
            pendingLabel = label,
            expectedLastCommand = expectedLastCommand,
            sawBusy = startsBusy,
            generation = nextGeneration,
            message = "$label queued",
            error = null,
            recoveryRequired = false
        )
        return nextGeneration
    }

    @Synchronized
    fun reject(message: String) {
        _state.value = _state.value.copy(
            pendingAction = null,
            pendingLabel = null,
            expectedLastCommand = null,
            sawBusy = false,
            message = null,
            error = message,
            recoveryRequired = false
        )
    }

    @Synchronized
    fun observe(
        remotePhase: String,
        remoteLastCommand: String?,
        remoteError: String?,
        gattState: String
    ) {
        val current = _state.value

        if (
            gattState == "disconnected" ||
            gattState.startsWith("error:") ||
            gattState.startsWith("service-error:")
        ) {
            _state.value = current.copy(
                pendingAction = null,
                pendingLabel = null,
                expectedLastCommand = null,
                sawBusy = false,
                error = "Recorder connection ended. Recover Remote before sending another command.",
                recoveryRequired = true
            )
            return
        }

        if (current.pendingAction == null) return

        if (remoteError != null && remotePhase == "error") {
            _state.value = current.copy(
                pendingAction = null,
                pendingLabel = null,
                expectedLastCommand = null,
                sawBusy = false,
                error = remoteError,
                recoveryRequired = true
            )
            return
        }

        if (isBusyPhase(remotePhase)) {
            if (!current.sawBusy) {
                _state.value = current.copy(sawBusy = true)
            }
            return
        }

        val expected = current.expectedLastCommand
        val commandConfirmed =
            expected != null &&
            remoteLastCommand?.equals(expected, ignoreCase = true) == true

        if (isIdlePhase(remotePhase) && (current.sawBusy || commandConfirmed)) {
            val nextMode = when (current.pendingAction) {
                "record-start", "record-stop" -> Mode.RECORD
                "play", "pause" -> Mode.PLAYBACK
                "return-record-mode" -> Mode.RECORD
                else -> current.mode
            }
            val doneMessage = when (current.pendingAction) {
                "return-record-mode" ->
                    "Recorder is back in Record mode. Press RECORD again to start recording."
                else -> "${current.pendingLabel ?: "Remote command"} completed."
            }

            _state.value = current.copy(
                mode = nextMode,
                pendingAction = null,
                pendingLabel = null,
                expectedLastCommand = null,
                sawBusy = false,
                message = doneMessage,
                error = null,
                recoveryRequired = false
            )
        }
    }

    @Synchronized
    fun timeout(generation: Long): Boolean {
        val current = _state.value
        if (current.pendingAction == null || current.generation != generation) return false

        _state.value = current.copy(
            error = "${current.pendingLabel ?: "Remote command"} was not confirmed. Remote is locked to prevent another command from corrupting the PacketLink session.",
            recoveryRequired = true
        )
        return true
    }

    @Synchronized
    fun startRecovery() {
        _state.value = _state.value.copy(
            pendingAction = null,
            pendingLabel = null,
            expectedLastCommand = null,
            sawBusy = false,
            message = "Recovering Remote connection...",
            error = null,
            recoveryRequired = true,
            generation = _state.value.generation + 1L
        )
    }

    @Synchronized
    fun recovered() {
        val previousMode = _state.value.mode
        val recoveredMode = RemoteRecoveredModePolicy.afterTrustedRecovery(previousMode)
        _state.value = State(
            mode = recoveredMode,
            generation = _state.value.generation + 1L,
            message = "Remote recovered. Tracked mode: ${RemoteRecoveredModePolicy.label(recoveredMode)}."
        )
    }

    @Synchronized
    fun markPlayback() {
        if (!_state.value.locked) {
            _state.value = _state.value.copy(mode = Mode.PLAYBACK)
        }
    }

    private fun isBusyPhase(phase: String): Boolean =
        phase in setOf(
            "queued",
            "sending",
            "requesting-credit",
            "awaiting-credit",
            "awaiting-response",
            "bootstrapping"
        )

    private fun isIdlePhase(phase: String): Boolean =
        phase in setOf(
            "linked",
            "packet-ready",
            "transport-ready"
        )
}
