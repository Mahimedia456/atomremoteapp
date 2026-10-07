package com.atomosremote.nativeapp.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 20.03.7.9 session-scoped Input snapshot lifecycle.
 *
 * This intentionally stores lifecycle/readiness only. Recorder values remain in
 * the existing GattClient StateFlows so there is one authoritative decoded state.
 *
 * The cache is bound to the connected recorder address and is invalidated on a
 * new recorder/session. It is not a disk cache, so stale live settings are never
 * presented after an app/process restart.
 */
data class InputSessionSnapshotState(
    val recorderAddress: String? = null,
    val phase: Phase = Phase.EMPTY,
    val coreGeneration: Int = 0,
    val extendedGeneration: Int = 0,
    val status: String = "Waiting for recorder"
) {
    enum class Phase {
        EMPTY,
        PREFETCHING_CORE,
        PREFETCHING_EXTENDED,
        READY,
        PARTIAL
    }

    val ready: Boolean
        get() = phase == Phase.READY || phase == Phase.PARTIAL
}

class InputSessionSnapshotCache {
    private val _state = MutableStateFlow(InputSessionSnapshotState())
    val state: StateFlow<InputSessionSnapshotState> = _state

    fun isReadyFor(address: String?): Boolean =
        !address.isNullOrBlank() &&
            _state.value.recorderAddress == address &&
            _state.value.ready

    fun isRunningFor(address: String?): Boolean =
        !address.isNullOrBlank() &&
            _state.value.recorderAddress == address &&
            (_state.value.phase == InputSessionSnapshotState.Phase.PREFETCHING_CORE ||
                _state.value.phase == InputSessionSnapshotState.Phase.PREFETCHING_EXTENDED)

    fun beginCore(address: String, expectedGeneration: Int) {
        _state.value = InputSessionSnapshotState(
            recorderAddress = address,
            phase = InputSessionSnapshotState.Phase.PREFETCHING_CORE,
            coreGeneration = expectedGeneration,
            status = "Loading recorder settings"
        )
    }

    fun beginExtended(address: String, coreGeneration: Int, expectedGeneration: Int) {
        _state.value = InputSessionSnapshotState(
            recorderAddress = address,
            phase = InputSessionSnapshotState.Phase.PREFETCHING_EXTENDED,
            coreGeneration = coreGeneration,
            extendedGeneration = expectedGeneration,
            status = "Loading recorder settings"
        )
    }

    fun ready(address: String, coreGeneration: Int, extendedGeneration: Int, partial: Boolean) {
        _state.value = InputSessionSnapshotState(
            recorderAddress = address,
            phase = if (partial) InputSessionSnapshotState.Phase.PARTIAL
                    else InputSessionSnapshotState.Phase.READY,
            coreGeneration = coreGeneration,
            extendedGeneration = extendedGeneration,
            status = if (partial) "Recorder settings ready" else "Recorder settings ready"
        )
    }

    fun invalidate() {
        _state.value = InputSessionSnapshotState()
    }
}
