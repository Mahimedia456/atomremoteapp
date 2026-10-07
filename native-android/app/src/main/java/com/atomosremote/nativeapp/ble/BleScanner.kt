package com.atomosremote.nativeapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class BleScanner(context: Context) {
    private val pairingRegex = Regex("\\b(\\d{3})[- ]?(\\d{3})[- ]?(\\d{3})\\b")

    private fun extractAsciiPairingId(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val text = bytes.toString(Charsets.US_ASCII)
        val match = pairingRegex.find(text) ?: return null
        return "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}"
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    fun isAvailable(): Boolean = adapter != null
    fun isEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun scan(): Flow<BleAdvertisement> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth LE scanner unavailable"))
            return@callbackFlow
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0L)
            .build()

        fun advertisementFrom(result: ScanResult): BleAdvertisement {
            val record = result.scanRecord
            val manufacturerCompanyIds = buildList {
                val data = record?.manufacturerSpecificData
                if (data != null) {
                    for (i in 0 until data.size()) add(data.keyAt(i))
                }
            }
            val mfg = if (record != null && record.manufacturerSpecificData.size() > 0) {
                val key = record.manufacturerSpecificData.keyAt(0)
                record.manufacturerSpecificData.get(key)?.joinToString("") { "%02X".format(it) }
            } else null
            val serviceDataUuids = record?.serviceData?.keys?.map { it.uuid.toString() }.orEmpty()
            val rawBytes = record?.bytes
            val rawHex = rawBytes?.joinToString("") { "%02X".format(it) }
            val rescuedPairingId = PairingRuntimeGate.observeScanResult(result)
            val sid = result.advertisingSid.takeIf { it != ScanResult.SID_NOT_PRESENT }

            return BleAdvertisement(
                device = result.device,
                name = record?.deviceName ?: result.device.name,
                address = result.device.address,
                rssi = result.rssi,
                serviceUuids = record?.serviceUuids?.map { it.uuid.toString() }.orEmpty(),
                manufacturerDataHex = mfg,
                connectable = if (android.os.Build.VERSION.SDK_INT >= 26) result.isConnectable else null,
                timestampMs = System.currentTimeMillis(),
                rawRecordHex = rawHex,
                advertisedPairingId = rescuedPairingId ?: extractAsciiPairingId(rawBytes),
                manufacturerCompanyIds = manufacturerCompanyIds,
                serviceDataUuids = serviceDataUuids,
                legacyAdvertisement = result.isLegacy,
                advertisingSid = sid
            )
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(advertisementFrom(result))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result -> trySend(advertisementFrom(result)) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed: $errorCode"))
            }
        }

        scanner.startScan(null, settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }
}
