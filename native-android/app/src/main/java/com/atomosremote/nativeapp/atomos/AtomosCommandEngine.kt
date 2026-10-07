package com.atomosremote.nativeapp.atomos

import com.atomosremote.nativeapp.ble.GattClient

class AtomosCommandEngine(private val gatt: GattClient) {
    fun send(profile: ProtocolProfile?, command: String): Result<Unit> {
        if (profile == null || !profile.canTransmit) {
            return Result.failure(IllegalStateException("No validated Atomos protocol profile is loaded"))
        }
        val payload = profile.commands.payloadFor(command)
            ?: return Result.failure(IllegalStateException("Validated payload missing for $command"))

        val ok = gatt.write(
            serviceUuid = profile.serviceUuid,
            characteristicUuid = profile.commandCharacteristicUuid,
            value = payload,
            withResponse = profile.writeWithResponse
        )
        return if (ok) Result.success(Unit)
        else Result.failure(IllegalStateException("Android GATT write request failed"))
    }
}
