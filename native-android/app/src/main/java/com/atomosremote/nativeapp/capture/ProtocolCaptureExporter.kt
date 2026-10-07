package com.atomosremote.nativeapp.capture

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.atomosremote.nativeapp.ble.BleAdvertisement
import com.atomosremote.nativeapp.ble.GattSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class CaptureExport(
    val file: File,
    val uri: Uri,
    val summary: String
)

data class PairingCandidateCapture(
    val pairingId: String,
    val sourceAddress: String,
    val sourceName: String?,
    val rssi: Int,
    val hits: Int,
    val exactAg2Source: Boolean
)

/**
 * Protocol capture exporter intentionally depends only on the stable GATT model.
 *
 * Pairing Fix 16.8 expanded the BLE advertisement model while older capture code
 * still referenced removed/renamed advertisement fields.  That made debug builds
 * fail even though the pairing source verification passed.  Keep advertisement
 * optional and opaque here so protocol capture cannot break when scan metadata
 * evolves again.
 */
class ProtocolCaptureExporter(private val context: Context) {
    fun exportPairingTrace(
        recorderName: String?,
        selectedAddress: String?,
        pairingId: String?,
        peripheralState: String,
        centralAddress: String?,
        events: List<String>,
        advertisement: BleAdvertisement? = null,
        pairingCandidates: List<PairingCandidateCapture> = emptyList(),
        gattSnapshot: GattSnapshot? = null,
        gattState: String? = null,
        gattEvents: List<String> = emptyList(),
        smpPasskey: String? = null,
        smpPairingVariant: Int? = null
    ): CaptureExport {
        val root = JSONObject()
            .put("schema", "atomos-native-pairing-trace-v2")
            .put("build", "0.18.8.1-native")
            .put("pairingArchitecture", "official-tcs-pairing-plus-packet-link")
            .put("phoneGattServerEnabled", false)
            .put("phoneGattServerMode", "disabled")
            .put("phoneAdvertisingEnabledDuringPairing", false)
            .put(
                "capturedAtUtc",
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())
            )
            .put("recorderName", recorderName ?: "ATOMOS Recorder")
            .put("selectedAddress", selectedAddress)
            .put("pairingId", pairingId)
            .put("legacyPeripheralObserverState", peripheralState)
            .put("legacyInboundCentralAddress", centralAddress)
            .put("advertisementDebug", advertisement?.toString())
            .put("gattState", gattState)
            .put("smpPasskey", smpPasskey)
            .put("smpPairingVariant", smpPairingVariant)
            .put("pairingCandidates", JSONArray().apply {
                pairingCandidates.forEach { candidate ->
                    put(
                        JSONObject()
                            .put("pairingId", candidate.pairingId)
                            .put("sourceAddress", candidate.sourceAddress)
                            .put("sourceName", candidate.sourceName)
                            .put("rssi", candidate.rssi)
                            .put("hits", candidate.hits)
                            .put("exactAg2Source", candidate.exactAg2Source)
                    )
                }
            })
            .put("gattSnapshot", gattSnapshot?.let { snap ->
                JSONObject()
                    .put("deviceAddress", snap.deviceAddress)
                    .put("pairingId", snap.pairingId)
                    .put("binaryPairingCandidates", JSONArray(snap.binaryPairingCandidates))
                    .put("pairingDecodeEvidence", JSONArray(snap.pairingDecodeEvidence))
                    .put("serviceUuids", JSONArray(snap.serviceUuids))
                    .put("characteristics", JSONArray().apply {
                        snap.characteristics.forEach { c ->
                            put(
                                JSONObject()
                                    .put("serviceUuid", c.serviceUuid)
                                    .put("characteristicUuid", c.characteristicUuid)
                                    .put("readable", c.readable)
                                    .put("writable", c.writable)
                                    .put("writableNoResponse", c.writableNoResponse)
                                    .put("notifiable", c.notifiable)
                                    .put("indicatable", c.indicatable)
                                    .put("properties", c.properties)
                                    .put("permissions", c.permissions)
                                    .put("descriptorUuids", JSONArray(c.descriptorUuids))
                                    .put("lastValueHex", c.lastValueHex)
                                    .put("lastValueAscii", c.lastValueAscii)
                            )
                        }
                    })
            })
            .put("peripheralEvents", JSONArray(events.takeLast(500)))
            .put("gattEvents", JSONArray(gattEvents.takeLast(500)))

        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "atomos-pairing-trace-$stamp.json")
        file.writeText(root.toString(2))
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            file
        )

        return CaptureExport(
            file = file,
            uri = uri,
            summary = "18.3 official TCS pairing + Packet Link remote: ${gattEvents.size.coerceAtMost(500)} GATT events, target=${selectedAddress ?: "none"}"
        )
    }

    fun export(
        recorderName: String?,
        snapshot: GattSnapshot?,
        events: List<String>,
        protocolName: String?,
        advertisement: BleAdvertisement? = null
    ): CaptureExport {
        require(snapshot != null) {
            "No GATT snapshot is available. Connect to a recorder and discover services first."
        }

        val characteristics = JSONArray()
        snapshot.characteristics.forEach { c ->
            characteristics.put(
                JSONObject()
                    .put("serviceUuid", c.serviceUuid)
                    .put("characteristicUuid", c.characteristicUuid)
                    .put("readable", c.readable)
                    .put("writable", c.writable)
                    .put("writableNoResponse", c.writableNoResponse)
                    .put("notifiable", c.notifiable)
                    .put("indicatable", c.indicatable)
                    .put("properties", c.properties)
                    .put("permissions", c.permissions)
                    .put("descriptorUuids", JSONArray(c.descriptorUuids))
                    .put("lastValueHex", c.lastValueHex)
                    .put("lastValueAscii", c.lastValueAscii)
            )
        }

        val root = JSONObject()
            .put("schema", "atomos-native-gatt-capture-v2")
            .put(
                "capturedAtUtc",
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())
            )
            .put("recorderName", recorderName ?: "ATOMOS Recorder")
            .put("deviceAddress", snapshot.deviceAddress)
            .put("pairingId", snapshot.pairingId)
            .put("binaryPairingCandidates", JSONArray(snapshot.binaryPairingCandidates))
            .put("pairingDecodeEvidence", JSONArray(snapshot.pairingDecodeEvidence))
            .put("activeProtocolProfile", protocolName)
            .put("advertisementAvailable", advertisement != null)
            .put("advertisementDebug", advertisement?.toString())
            .put("characteristics", characteristics)
            .put("events", JSONArray(events.takeLast(250)))

        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "atomos-gatt-$stamp.json")
        file.writeText(root.toString(2))
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            file
        )

        return CaptureExport(
            file = file,
            uri = uri,
            summary = "${snapshot.characteristics.size} characteristics, ${events.size.coerceAtMost(250)} recent events"
        )
    }
}
