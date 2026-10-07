package com.atomosremote.nativeapp.multi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import com.atomosremote.nativeapp.atomos.AtomosCommandEngine
import com.atomosremote.nativeapp.atomos.ProtocolProfile
import com.atomosremote.nativeapp.ble.GattClient
import com.atomosremote.nativeapp.data.SavedAtomosDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class MultiDeviceSession(
    val address: String,
    val name: String,
    val state: String = "idle",
    val lastAction: String? = null
)

class MultiDeviceController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clients = linkedMapOf<String, GattClient>()
    private val _sessions = MutableStateFlow<List<MultiDeviceSession>>(emptyList())
    val sessions: StateFlow<List<MultiDeviceSession>> = _sessions

    @SuppressLint("MissingPermission")
    fun connectAll(devices: List<SavedAtomosDevice>) {
        devices.forEach { saved ->
            if (clients.containsKey(saved.address)) return@forEach
            val remote = runCatching {
                context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(saved.address)
            }.getOrNull() ?: run {
                update(saved.address, saved.name, "invalid-address", "connect")
                return@forEach
            }
            val client = GattClient(context)
            clients[saved.address] = client
            update(saved.address, saved.name, "connecting", "connect")
            scope.launch {
                client.state.collect { state -> update(saved.address, saved.name, state, null) }
            }
            runCatching { client.connect(remote) }.onFailure {
                clients.remove(saved.address)
                update(saved.address, saved.name, "connect-error", "${it.message}")
            }
        }
    }

    fun disconnectAll() {
        clients.values.forEach { it.disconnect() }
        clients.clear()
        _sessions.value = emptyList()
    }

    fun sendAll(profile: ProtocolProfile?, command: String): Map<String, Result<Unit>> {
        val results = linkedMapOf<String, Result<Unit>>()
        clients.forEach { (address, client) ->
            val result = AtomosCommandEngine(client).send(profile, command)
            results[address] = result
            val existing = _sessions.value.firstOrNull { it.address == address }
            update(address, existing?.name ?: address, existing?.state ?: "unknown", command)
        }
        return results
    }

    fun close() {
        disconnectAll()
        scope.cancel()
    }

    private fun update(address: String, name: String, state: String, action: String?) {
        val map = _sessions.value.associateBy { it.address }.toMutableMap()
        val old = map[address]
        map[address] = MultiDeviceSession(
            address = address,
            name = name,
            state = state,
            lastAction = action ?: old?.lastAction
        )
        _sessions.value = map.values.sortedBy { it.name.lowercase() }
    }
}
