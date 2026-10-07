package com.atomosremote.nativeapp.ble

import android.bluetooth.BluetoothDevice

data class BleAdvertisement(
    val device: BluetoothDevice,
    val name: String?,
    val address: String,
    val rssi: Int,
    val serviceUuids: List<String>,
    val manufacturerDataHex: String?,
    val connectable: Boolean?,
    val timestampMs: Long,
    val rawRecordHex: String? = null,
    val advertisedPairingId: String? = null,
    val manufacturerCompanyIds: List<Int> = emptyList(),
    val serviceDataUuids: List<String> = emptyList(),
    val legacyAdvertisement: Boolean? = null,
    val advertisingSid: Int? = null
)

data class GattCharacteristicInfo(
    val serviceUuid: String,
    val characteristicUuid: String,
    val readable: Boolean,
    val writable: Boolean,
    val writableNoResponse: Boolean,
    val notifiable: Boolean,
    val indicatable: Boolean,
    val properties: Int = 0,
    val permissions: Int = 0,
    val descriptorUuids: List<String> = emptyList(),
    val lastValueHex: String? = null,
    val lastValueAscii: String? = null
)

data class GattSnapshot(
    val deviceAddress: String,
    val characteristics: List<GattCharacteristicInfo>,
    val pairingId: String? = null,
    val serviceUuids: List<String> = emptyList(),
    // Diagnostic only. Never auto-accepted or promoted to pairingId.
    val binaryPairingCandidates: List<String> = emptyList(),
    val pairingDecodeEvidence: List<String> = emptyList()
)


data class TcsPairingState(
    val phase: String = "idle",
    val pairingCode: String? = null,
    val slotsAvailable: Int? = null,
    val ccid: Long? = null,
    val bcid: Long? = null,
    val canAccept: Boolean = false,
    val userAccepted: Boolean = false,
    val connectNameWritten: Boolean = false,
    val ccidWritten: Boolean = false,
    val acceptWritten: Boolean = false,
    val lastError: String? = null
)


data class TcsRemoteState(
    val phase: String = "idle",
    val available: Boolean = false,
    val linked: Boolean = false,
    val lastCommand: String? = null,
    val lastCommandId: Int? = null,
    val lastTxHex: String? = null,
    val lastRxHex: String? = null,
    val lastStatus: String? = null,
    val lastError: String? = null
)
