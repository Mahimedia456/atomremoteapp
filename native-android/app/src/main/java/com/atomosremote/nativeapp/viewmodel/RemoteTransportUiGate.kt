package com.atomosremote.nativeapp.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Agent7 20.03.5.4.5
 *
 * UI/ViewModel-only transport interaction gate.
 *
 * IMPORTANT:
 * - Does not own BluetoothGatt.
 * - Does not build AtomMessage frames.
 * - Does not alter PacketLink.
 * - Does not alter TCS pairing.
 * - Does not know Input or Monitor command IDs.
 *
 * Its only job is to stop a second transport tap while the first UI action is
 * pending, and to keep the operator-facing Record/Playback mode intent.
 */
class RemoteTransportUiGate {
    enum class OperatorMode {
        UNKNOWN,
        RECORD,
        PLAYBACK,
        RETURNING_TO_RECORD
    }

    data class State(
        val mode: OperatorMode = OperatorMode.UNKNOWN,
        val pendingAction: String? = null,
        val pendingLabel: String? = null,
        val sawBusyPhase: Boolean = false,
        val generation: Long = 0L,
        val lastUiError: String? = null
    ) {
        val busy: Boolean
            get() = pendingAction != null
    }

    sealed class Decision {
        data class Dispatch(val command: String, val label: String) : Decision()
        data class ReturnToRecordMode(val label: String = "Return to Record mode") : Decision()
        data class Blocked(val message: String) : Decision()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Synchronized
    fun decide(command: String): Decision {
        val current = _state.value

        if (current.busy) {
            return Decision.Blocked(
                "${current.pendingLabel ?: "Remote command"} is still pending. Wait for it to finish."
            )
        }

        if (
            command == "record-start" &&
            current.mode == OperatorMode.PLAYBACK
        ) {
            return Decision.ReturnToRecordMode()
        }

        if (
            command == "record-start" &&
            current.mode == OperatorMode.RETURNING_TO_RECORD
        ) {
            return Decision.Blocked("Recorder is still returning to Record mode.")
        }

        val label = when (command) {
            "record-start" -> "Record Start"
            "record-stop" -> "Record Stop"
            "play" -> "Play"
            "pause" -> "Pause"
            else -> command
        }
        return Decision.Dispatch(command, label)
    }

    @Synchronized
    fun queued(action: String, label: String): Long {
        val nextGeneration = _state.value.generation + 1L
        val nextMode = when (action) {
            "return-record-mode" -> OperatorMode.RETURNING_TO_RECORD
            "record-start", "record-stop" -> OperatorMode.RECORD
            "play", "pause" -> OperatorMode.PLAYBACK
            else -> _state.value.mode
        }

        _state.value = _state.value.copy(
            mode = nextMode,
            pendingAction = action,
            pendingLabel = label,
            sawBusyPhase = false,
            generation = nextGeneration,
            lastUiError = null
        )
        return nextGeneration
    }

    @Synchronized
    fun queueRejected(message: String) {
        val current = _state.value
        val restoredMode = if (
            current.pendingAction == "return-record-mode" ||
            current.mode == OperatorMode.RETURNING_TO_RECORD
        ) {
            OperatorMode.PLAYBACK
        } else {
            current.mode
        }

        _state.value = current.copy(
            mode = restoredMode,
            pendingAction = null,
            pendingLabel = null,
            sawBusyPhase = false,
            lastUiError = message
        )
    }

    /**
     * Returns a user-facing completion/error message only when the gate itself
     * transitions from pending -> idle.
     */
    @Synchronized
    fun onRemotePhase(phase: String, remoteError: String?): String? {
        val current = _state.value
        if (!current.busy) return null

        if (phase in setOf("sending", "awaiting-response", "awaiting-credit")) {
            if (!current.sawBusyPhase) {
                _state.value = current.copy(sawBusyPhase = true)
            }
            return null
        }

        if (phase == "error") {
            val restoredMode = if (
                current.pendingAction == "return-record-mode" ||
                current.mode == OperatorMode.RETURNING_TO_RECORD
            ) {
                OperatorMode.PLAYBACK
            } else {
                current.mode
            }
            val message = remoteError ?: "${current.pendingLabel ?: "Remote command"} failed."
            _state.value = current.copy(
                mode = restoredMode,
                pendingAction = null,
                pendingLabel = null,
                sawBusyPhase = false,
                lastUiError = message
            )
            return message
        }

        val idle = phase in setOf("linked", "transport-ready", "packet-ready")
        if (idle && current.sawBusyPhase) {
            val completedAction = current.pendingAction
            val completedLabel = current.pendingLabel ?: "Remote command"
            val nextMode = when (completedAction) {
                "return-record-mode" -> OperatorMode.RECORD
                "record-start", "record-stop" -> OperatorMode.RECORD
                "play", "pause" -> OperatorMode.PLAYBACK
                else -> current.mode
            }

            _state.value = current.copy(
                mode = nextMode,
                pendingAction = null,
                pendingLabel = null,
                sawBusyPhase = false,
                lastUiError = null
            )

            return when (completedAction) {
                "return-record-mode" -> "Recorder returned to Record mode. Press RECORD again to start recording."
                else -> "$completedLabel completed."
            }
        }

        return null
    }

    @Synchronized
    fun timeoutIfPending(generation: Long): String? {
        val current = _state.value
        if (!current.busy || current.generation != generation) return null

        val action = current.pendingAction
        val label = current.pendingLabel ?: "Remote command"
        val restoredMode = if (
            action == "return-record-mode" ||
            current.mode == OperatorMode.RETURNING_TO_RECORD
        ) {
            OperatorMode.PLAYBACK
        } else {
            current.mode
        }

        val message = "$label was not confirmed in time. The UI lock was released."
        _state.value = current.copy(
            mode = restoredMode,
            pendingAction = null,
            pendingLabel = null,
            sawBusyPhase = false,
            lastUiError = message
        )
        return message
    }

    @Synchronized
    fun resetForDisconnect() {
        _state.value = State(
            generation = _state.value.generation + 1L,
            lastUiError = "Recorder connection ended."
        )
    }
}
