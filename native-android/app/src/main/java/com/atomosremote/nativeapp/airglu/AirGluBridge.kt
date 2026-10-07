package com.atomosremote.nativeapp.airglu

/**
 * Boundary for a future Atomos-supported AirGlu SDK/protocol implementation.
 * AirGlu RF is not Bluetooth LE and must not be emulated by sending guessed packets.
 */
interface AirGluBridge {
    val available: Boolean
    val implementationName: String
    fun apply(config: AirGluConfig): Result<Unit>
    fun gangRecord(start: Boolean): Result<Unit>
}

class SafeAirGluBoundary : AirGluBridge {
    override val available: Boolean = false
    override val implementationName: String = "No validated native AirGlu RF bridge loaded"

    override fun apply(config: AirGluConfig): Result<Unit> = Result.failure(
        IllegalStateException("AirGlu RF configuration is stored locally only. Load an Atomos-approved SDK/protocol before transmitting RF control commands.")
    )

    override fun gangRecord(start: Boolean): Result<Unit> = Result.failure(
        IllegalStateException("AirGlu gang control is locked until a validated Atomos AirGlu integration is available.")
    )
}
