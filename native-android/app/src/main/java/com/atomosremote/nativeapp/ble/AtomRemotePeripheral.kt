package com.atomosremote.nativeapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * AtomRemote/AirGlu phone-side BLE peripheral.
 *
 * 17.9 uses dual-role BLE discovery during the Pairing-ID phase.
 *
 * Android remains a CENTRAL/GATT client to the selected AG2 recorder, while a
 * separate connectable phone-side discovery presence is advertised in parallel.
 * The phone-side presence intentionally does NOT mirror the recorder's proprietary
 * 9383 GATT profile. Earlier traces proved that mirroring the recorder profile was
 * not the missing pairing transaction.
 *
 * 0xFCC1 is the Bluetooth SIG company identifier for Timecode Systems. Historical
 * live traces also showed AG2 initiating an inbound BLE connection when the phone
 * advertised the FCC1-form compatibility presence UUID. 17.9 preserves that
 * empirically useful discovery beacon only; it is not treated as a proven GATT
 * service specification.
 *
 * The 9-digit AtomRemote Pairing ID is application data. Android SMP passkeys from
 * ACTION_PAIRING_REQUEST are recorded separately and are never converted into the
 * 9-digit Pairing ID.
 */
class AtomRemotePeripheral(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private var gattServer: BluetoothGattServer? = null
    private var centralDevice: BluetoothDevice? = null
    private var acceptedPairingId: String? = null
    private var expectedCentralAddress: String? = null
    private var receiverRegistered = false
    private var advertising = false
    private var fallbackWithoutNameTried = false

    private var mirroredCharacteristics: List<GattCharacteristicInfo> = emptyList()
    private var mirroredFingerprint: String? = null
    private var usingFallbackProfile = true
    private val mirroredInitialValues = linkedMapOf<UUID, ByteArray>()
    private val inboundWriteBuffers = linkedMapOf<UUID, ByteArrayOutputStream>()
    private val inboundDescriptorValues = linkedMapOf<String, ByteArray>()

    private val _state = MutableStateFlow("idle")
    val state: StateFlow<String> = _state

    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events

    private val _centralAddress = MutableStateFlow<String?>(null)
    val centralAddress: StateFlow<String?> = _centralAddress

    private val _observedPairingId = MutableStateFlow<String?>(null)
    val observedPairingId: StateFlow<String?> = _observedPairingId

    private val _profileSummary = MutableStateFlow("AirGlu 9383 fallback profile")
    val profileSummary: StateFlow<String> = _profileSummary

    private val _smpPasskey = MutableStateFlow<String?>(null)
    val smpPasskey: StateFlow<String?> = _smpPasskey

    private val _smpPairingVariant = MutableStateFlow<Int?>(null)
    val smpPairingVariant: StateFlow<Int?> = _smpPairingVariant

    init {
        registerBondReceiver()
    }

    @SuppressLint("MissingPermission")
    fun setExpectedCentralAddress(address: String?) {
        val next = address?.uppercase()
        if (next != expectedCentralAddress) {
            acceptedPairingId = null
            _observedPairingId.value = null
            _smpPasskey.value = null
            _smpPairingVariant.value = null
            inboundWriteBuffers.clear()
            inboundDescriptorValues.clear()
        }
        expectedCentralAddress = next
        val current = centralDevice
        if (current != null && expectedCentralAddress != null) {
            val currentAddress = runCatching { current.address.uppercase() }.getOrNull()
            if (currentAddress != expectedCentralAddress) {
                appendEvent("LOCKDOWN rejecting connected central address=$currentAddress expected=$expectedCentralAddress")
                runCatching { gattServer?.cancelConnection(current) }
                centralDevice = null
                _centralAddress.value = null
            }
        }
        appendEvent("PAIRING SESSION TARGET=${expectedCentralAddress ?: "unlocked-discovery"}")
    }

    fun isSupported(): Boolean = adapter?.isMultipleAdvertisementSupported == true

    /** Mirror the proprietary 9383 profile discovered on the selected AG2. */
    @SuppressLint("MissingPermission")
    fun configureFromRecorderGatt(snapshot: GattSnapshot): Boolean {
        val airGlu = snapshot.characteristics.filter {
            it.serviceUuid.equals(AIRGLU_SERVICE_UUID.toString(), ignoreCase = true)
        }
        if (airGlu.isEmpty()) {
            appendEvent("AG2 snapshot has no 9383 AirGlu characteristic map; fallback remains armed")
            return false
        }

        val fingerprint = airGlu.sortedBy { it.characteristicUuid.lowercase() }.joinToString("|") {
            "${it.characteristicUuid.lowercase()}:${it.properties}:${it.permissions}:${it.descriptorUuids.sorted().joinToString(",")}"
        }

        mirroredInitialValues.clear()
        airGlu.forEach { info ->
            val uuid = runCatching { UUID.fromString(info.characteristicUuid) }.getOrNull() ?: return@forEach
            info.lastValueHex?.let(::hexToBytes)?.let { mirroredInitialValues[uuid] = it }
        }

        if (fingerprint == mirroredFingerprint && !usingFallbackProfile) return true

        val wasActive = advertising || gattServer != null || centralDevice != null
        if (wasActive) {
            appendEvent("Replacing fallback/stale peripheral profile with live AG2 9383 map")
            stopInternal(resetAcceptedCode = false, publishIdle = false)
        }

        mirroredCharacteristics = airGlu
        mirroredFingerprint = fingerprint
        usingFallbackProfile = false
        _profileSummary.value = "AG2 9383 mirror: ${airGlu.size} characteristic(s)"
        _state.value = "profile-ready:${airGlu.size}"
        appendEvent("LIVE AG2 PROFILE MIRRORED service=$AIRGLU_SERVICE_UUID chars=${airGlu.size} fingerprint=${fingerprint.hashCode()}")
        return true
    }

    /** Bootstrap only, replaced automatically as soon as the live AG2 map arrives. */
    fun configureFallbackAirGluProfile() {
        if (!usingFallbackProfile && mirroredCharacteristics.isNotEmpty()) return
        mirroredCharacteristics = emptyList()
        mirroredFingerprint = null
        mirroredInitialValues.clear()
        usingFallbackProfile = true
        _profileSummary.value = "AirGlu 9383 fallback capture profile"
        appendEvent("Using 9383 AirGlu bootstrap profile until live AG2 map arrives")
    }

    /**
     * 17.9 discovery-only pairing presence.
     *
     * AirGlu/UltraSync pairing documentation requires the recording/app side to be
     * discoverable while the Timecode/AirGlu device searches for devices wishing
     * to pair. Keep this presence separate from the recorder-side 9383 GATT client.
     *
     * No recorder profile is mirrored here and no vendor characteristic writes are
     * generated. The GATT server exists only so Android can accept/log an inbound
     * recorder connection and any SMP/application pairing traffic.
     */
    @SuppressLint("MissingPermission")
    fun startPairingDiscoveryPresence() {
        val current = _state.value
        if (current == "advertising" || current.startsWith("central-connected:") ||
            current.startsWith("bonding:") || current.startsWith("bonded:")) return

        val bt = adapter
        if (bt == null) {
            _state.value = "unavailable:no-bluetooth"
            appendEvent("17.9 discovery presence unavailable: no Bluetooth adapter")
            return
        }
        if (!bt.isEnabled) {
            _state.value = "unavailable:bluetooth-off"
            appendEvent("17.9 discovery presence unavailable: Bluetooth is off")
            return
        }
        if (!bt.isMultipleAdvertisementSupported || bt.bluetoothLeAdvertiser == null) {
            _state.value = "unavailable:no-advertiser"
            appendEvent("17.9 discovery presence unavailable: Android controller cannot advertise")
            return
        }

        stopInternal(resetAcceptedCode = false, publishIdle = false)
        mirroredCharacteristics = emptyList()
        mirroredFingerprint = null
        mirroredInitialValues.clear()
        usingFallbackProfile = true
        fallbackWithoutNameTried = false
        _profileSummary.value = "17.9 pair-discovery presence only; no mirrored 9383 server"

        val server = runCatching { manager.openGattServer(appContext, serverCallback) }.getOrNull()
        if (server == null) {
            _state.value = "error:gatt-server-open"
            appendEvent("17.9 failed to open discovery-only Android BluetoothGattServer")
            return
        }
        gattServer = server
        appendEvent("17.9 DUAL-ROLE: starting connectable phone pairing-discovery presence; outbound AG2 GATT stays independent")
        startAdvertising(includeDeviceName = true)
    }

    @SuppressLint("MissingPermission")
    fun startPairingPresence() {
        val current = _state.value
        if (current == "advertising" || current.startsWith("central-connected:") ||
            current.startsWith("bonding:") || current.startsWith("bonded:")) return

        val bt = adapter
        if (bt == null) {
            _state.value = "unavailable:no-bluetooth"
            appendEvent("BLE peripheral unavailable: no Bluetooth adapter")
            return
        }
        if (!bt.isEnabled) {
            _state.value = "unavailable:bluetooth-off"
            appendEvent("BLE peripheral unavailable: Bluetooth is off")
            return
        }
        if (!bt.isMultipleAdvertisementSupported || bt.bluetoothLeAdvertiser == null) {
            _state.value = "unavailable:no-advertiser"
            appendEvent("BLE peripheral unavailable: Android controller cannot advertise")
            return
        }

        stopInternal(resetAcceptedCode = false, publishIdle = false)
        fallbackWithoutNameTried = false
        _state.value = "starting-gatt-server"
        appendEvent(
            if (usingFallbackProfile)
                "Starting 9383 AirGlu bootstrap GATT server"
            else
                "Starting LIVE AG2 9383 mirrored GATT server (${mirroredCharacteristics.size} chars)"
        )

        val server = runCatching { manager.openGattServer(appContext, serverCallback) }.getOrNull()
        if (server == null) {
            _state.value = "error:gatt-server-open"
            appendEvent("Failed to open Android BluetoothGattServer")
            return
        }
        gattServer = server

        val service = buildAirGluService()
        val queued = runCatching { server.addService(service) }.getOrDefault(false)
        if (!queued) {
            appendEvent("9383 AirGlu service add could not be queued; advertising UUID for capture")
            startAdvertising(includeDeviceName = true)
        } else {
            mainHandler.postDelayed({
                if (!advertising && _state.value == "starting-gatt-server") {
                    appendEvent("GATT service callback delayed; starting 9383 advertising fallback")
                    startAdvertising(includeDeviceName = true)
                }
            }, 750L)
        }
    }

    private fun buildAirGluService(): BluetoothGattService {
        val service = BluetoothGattService(AIRGLU_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val source = mirroredCharacteristics
        if (source.isEmpty()) {
            val capture = BluetoothGattCharacteristic(
                FALLBACK_CAPTURE_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            capture.addDescriptor(
                BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
            service.addCharacteristic(capture)
            return service
        }

        source.forEach { info ->
            val charUuid = runCatching { UUID.fromString(info.characteristicUuid) }.getOrNull() ?: return@forEach
            val properties = info.properties.takeIf { it != 0 } ?: propertiesFromInfo(info)
            val c = BluetoothGattCharacteristic(charUuid, properties, serverPermissionsFromInfo(info))
            val descriptors = info.descriptorUuids
                .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
                .toMutableSet()
            if ((info.notifiable || info.indicatable) && CCCD_UUID !in descriptors) descriptors += CCCD_UUID
            descriptors.forEach { descriptorUuid ->
                runCatching {
                    c.addDescriptor(
                        BluetoothGattDescriptor(
                            descriptorUuid,
                            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                        )
                    )
                }
            }
            service.addCharacteristic(c)
        }
        return service
    }

    private fun propertiesFromInfo(info: GattCharacteristicInfo): Int {
        var value = 0
        if (info.readable) value = value or BluetoothGattCharacteristic.PROPERTY_READ
        if (info.writable) value = value or BluetoothGattCharacteristic.PROPERTY_WRITE
        if (info.writableNoResponse) value = value or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        if (info.notifiable) value = value or BluetoothGattCharacteristic.PROPERTY_NOTIFY
        if (info.indicatable) value = value or BluetoothGattCharacteristic.PROPERTY_INDICATE
        return value
    }

    private fun serverPermissionsFromInfo(info: GattCharacteristicInfo): Int {
        var value = 0
        if (info.readable) value = value or BluetoothGattCharacteristic.PERMISSION_READ
        if (info.writable || info.writableNoResponse) value = value or BluetoothGattCharacteristic.PERMISSION_WRITE
        return value
    }

    fun acceptPairingCode(pairingId: String) {
        val normalized = normalizePairingId(pairingId) ?: return
        acceptedPairingId = normalized
        appendEvent("User accepted application Pairing ID $normalized; SMP confirmation may now proceed for locked AG2; no createBond()")
        centralDevice?.let { device ->
            runCatching { device.address }.getOrNull()?.let { _state.value = "central-connected:$it" }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopPairingPresence() = stopInternal(resetAcceptedCode = true, publishIdle = true)

    @SuppressLint("MissingPermission")
    fun finishPairingPresenceKeepAcceptance() = stopInternal(resetAcceptedCode = false, publishIdle = true)

    @SuppressLint("MissingPermission")
    fun close() {
        stopInternal(resetAcceptedCode = true, publishIdle = true)
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(bondReceiver) }
            receiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(includeDeviceName: Boolean) {
        val bt = adapter ?: return
        val advertiser = bt.bluetoothLeAdvertiser ?: run {
            _state.value = "unavailable:no-advertiser"
            return
        }

        val settingsBuilder = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
        if (Build.VERSION.SDK_INT >= 34) settingsBuilder.setDiscoverable(true)
        val settings = settingsBuilder.build()

        val primary = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(PAIRING_PRESENCE_UUID))
            .build()

        runCatching { advertiser.stopAdvertising(advertiseCallback) }
        advertising = false
        _state.value = if (includeDeviceName) "starting-advertiser" else "starting-advertiser-no-name"

        if (includeDeviceName) {
            val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).setIncludeTxPowerLevel(false).build()
            advertiser.startAdvertising(settings, primary, scanResponse, advertiseCallback)
        } else {
            advertiser.startAdvertising(settings, primary, advertiseCallback)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            _state.value = "advertising"
            appendEvent("17.9 phone pair-discovery presence advertising; profile=${_profileSummary.value}; waiting for recorder discovery/inbound handshake")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            appendEvent("BLE advertising failed code=$errorCode (${advertiseErrorLabel(errorCode)})")
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE && !fallbackWithoutNameTried) {
                fallbackWithoutNameTried = true
                appendEvent("Retrying FCC1 compatibility advertisement without device name")
                mainHandler.post { startAdvertising(includeDeviceName = false) }
            } else {
                _state.value = "error:advertise-$errorCode"
            }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            appendEvent("GATT service added status=$status uuid=${service?.uuid} chars=${service?.characteristics?.size ?: 0}")
            if (status == BluetoothGatt.GATT_SUCCESS && !advertising) {
                startAdvertising(includeDeviceName = true)
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = "error:gatt-service-$status"
                if (!advertising) startAdvertising(includeDeviceName = true)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = runCatching { device.address }.getOrDefault("unknown")
            val name = runCatching { device.name }.getOrNull()
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val expected = expectedCentralAddress
                    if (expected != null && !address.equals(expected, ignoreCase = true)) {
                        appendEvent("LOCKDOWN REJECTED INBOUND CENTRAL name=${name ?: "?"} address=$address expected=$expected status=$status")
                        runCatching { gattServer?.cancelConnection(device) }
                        if (advertising) _state.value = "advertising"
                        return
                    }
                    centralDevice = device
                    _centralAddress.value = address
                    _state.value = "central-connected:$address"
                    appendEvent("INBOUND BLE CENTRAL CONNECTED name=${name ?: "?"} address=$address status=$status bond=${bondLabel(device.bondState)} acceptedCode=${acceptedPairingId ?: "none"} profile=${_profileSummary.value}")
                    appendEvent("SMP owner is remote central; Android app never calls createBond()")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    appendEvent("Inbound central disconnected name=${name ?: "?"} address=$address status=$status")
                    if (centralDevice?.address == address) centralDevice = null
                    _centralAddress.value = null
                    if (advertising) _state.value = "advertising" else if (!_state.value.startsWith("bonded:")) _state.value = "idle"
                }
                else -> appendEvent("Inbound central state=$newState address=$address status=$status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            appendEvent("Inbound MTU=$mtu address=${device.address}")
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            appendEvent("INBOUND READ service=${characteristic.service?.uuid ?: AIRGLU_SERVICE_UUID} char=${characteristic.uuid} offset=$offset from=${device.address}")
            val full = mirroredInitialValues[characteristic.uuid] ?: byteArrayOf()
            if (offset < 0 || offset > full.size) {
                gattServer?.sendResponse(device, requestId, GATT_INVALID_OFFSET, offset, null)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, full.copyOfRange(offset, full.size))
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val hex = value.joinToString("") { "%02X".format(it) }
            appendEvent("INBOUND WRITE service=${characteristic.service?.uuid} char=${characteristic.uuid} offset=$offset prepared=$preparedWrite hex=$hex ascii=${printableAscii(value)} from=${device.address}")
            capturePossiblePairingId(characteristic.uuid, value, offset, device.address)
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            appendEvent("INBOUND DESCRIPTOR READ char=${descriptor.characteristic.uuid} desc=${descriptor.uuid} offset=$offset from=${device.address}")
            val key = "${descriptor.characteristic.uuid}/${descriptor.uuid}"
            val full = inboundDescriptorValues[key]
                ?: if (descriptor.uuid == CCCD_UUID) BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE else byteArrayOf()
            if (offset < 0 || offset > full.size) {
                gattServer?.sendResponse(device, requestId, GATT_INVALID_OFFSET, offset, null)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, full.copyOfRange(offset, full.size))
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val hex = value.joinToString("") { "%02X".format(it) }
            appendEvent("INBOUND DESCRIPTOR WRITE char=${descriptor.characteristic.uuid} desc=${descriptor.uuid} offset=$offset prepared=$preparedWrite hex=$hex from=${device.address}")
            val key = "${descriptor.characteristic.uuid}/${descriptor.uuid}"
            inboundDescriptorValues[key] = value.copyOf()
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        @SuppressLint("MissingPermission")
        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            appendEvent("INBOUND EXECUTE WRITE execute=$execute from=${device.address}")
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        @SuppressLint("MissingPermission")
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            appendEvent("OUTBOUND NOTIFICATION RESULT to=${device.address} status=$status")
        }
    }

    private fun capturePossiblePairingId(characteristicUuid: UUID, value: ByteArray, offset: Int, address: String) {
        PairingIdDecoder.decode(value).highConfidence?.let {
            publishPairingId(it, "single inbound write $characteristicUuid", address)
        }
        val buffer = inboundWriteBuffers.getOrPut(characteristicUuid) { ByteArrayOutputStream() }
        if (offset == 0 && buffer.size() > 0) buffer.reset()
        if (buffer.size() + value.size > MAX_CAPTURE_BYTES) buffer.reset()
        buffer.write(value)
        PairingIdDecoder.decode(buffer.toByteArray()).highConfidence?.let {
            publishPairingId(it, "fragmented inbound write $characteristicUuid", address)
        }
    }

    private fun publishPairingId(raw: String, source: String, address: String) {
        val normalized = normalizePairingId(raw) ?: return
        if (normalized == _observedPairingId.value) return
        _observedPairingId.value = normalized
        appendEvent("APPLICATION PAIRING ID CAPTURED id=$normalized source=$source from=$address")
    }

    private val bondReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return

            val address = runCatching { device.address }.getOrDefault("unknown")
            val activeCentral = centralDevice?.address
            val expected = expectedCentralAddress
            if (expected != null && !address.equals(expected, ignoreCase = true)) {
                appendEvent("LOCKDOWN ignored bond event address=$address expected=$expected")
                return
            }
            if (activeCentral != null && !address.equals(activeCentral, ignoreCase = true)) return

            when (action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                    val key = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1)
                    val keyText = if (key >= 0) key.toString().padStart(6, '0') else "none"
                    _smpPairingVariant.value = variant
                    _smpPasskey.value = key.takeIf { it >= 0 }?.toString()?.padStart(6, '0')
                    _state.value = "pairing-request:$address"
                    if (acceptedPairingId == null) {
                        // 17.7 capture mode: do not abort the selected AG2's SMP request.
                        // Aborting the ordered pairing broadcast can make the recorder tear down
                        // the same BLE session we are trying to observe. We still never call
                        // createBond() and never auto-confirm before the 9-digit application ID.
                        appendEvent("EARLY SMP REQUEST OBSERVED from=$address variant=$variant smpKey=$keyText applicationId=waiting broadcast=NOT_ABORTED LINK_PRESERVED")
                        return
                    }
                    val confirmed = setPairingConfirmationBestEffort(device)
                    appendEvent("SMP REQUEST AFTER 9-DIGIT ACCEPT from=$address variant=$variant smpKey=$keyText confirmationAttempt=$confirmed")
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, device.bondState)
                    val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                    appendEvent("Bond state ${bondLabel(previous)} -> ${bondLabel(state)} address=$address applicationIdAccepted=${acceptedPairingId != null}")
                    if (state == BluetoothDevice.BOND_BONDING && acceptedPairingId == null) {
                        appendEvent("EARLY SMP BONDING before 9-digit Pairing ID: preserving GATT link; no cancelBondProcess; no createBond")
                        return
                    }
                    when (state) {
                        BluetoothDevice.BOND_BONDING -> _state.value = "bonding:$address"
                        BluetoothDevice.BOND_BONDED -> _state.value = "bonded:$address"
                        BluetoothDevice.BOND_NONE -> if (advertising) _state.value = "advertising" else _state.value = "idle"
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setPairingConfirmationBestEffort(device: BluetoothDevice): Boolean = runCatching {
        val primitiveBoolean = Boolean::class.javaPrimitiveType ?: return@runCatching false
        val method = BluetoothDevice::class.java.getMethod("setPairingConfirmation", primitiveBoolean)
        (method.invoke(device, true) as? Boolean) ?: true
    }.getOrDefault(false)

    private fun registerBondReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(bondReceiver, filter)
        }
        receiverRegistered = true
    }

    @SuppressLint("MissingPermission")
    private fun stopInternal(resetAcceptedCode: Boolean, publishIdle: Boolean) {
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
        val server = gattServer
        gattServer = null
        if (server != null) {
            centralDevice?.let { runCatching { server.cancelConnection(it) } }
            runCatching { server.clearServices() }
            runCatching { server.close() }
        }
        centralDevice = null
        _centralAddress.value = null
        inboundWriteBuffers.clear()
        inboundDescriptorValues.clear()
        if (resetAcceptedCode) {
            acceptedPairingId = null
            expectedCentralAddress = null
            _observedPairingId.value = null
            _smpPasskey.value = null
            _smpPairingVariant.value = null
        }
        if (publishIdle) _state.value = "idle"
    }

    private fun normalizePairingId(raw: String?): String? {
        val digits = raw?.filter(Char::isDigit).orEmpty()
        if (digits.length != 9 || digits.all { it == '0' }) return null
        return "${digits.substring(0, 3)}-${digits.substring(3, 6)}-${digits.substring(6, 9)}"
    }

    private fun printableAscii(value: ByteArray): String = value.map { byte ->
        val n = byte.toInt() and 0xFF
        if (n in 32..126) n.toChar() else '.'
    }.joinToString("")

    private fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.filterNot(Char::isWhitespace)
        if (clean.length % 2 != 0) return null
        return runCatching {
            ByteArray(clean.length / 2) { index -> clean.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }

    private fun appendEvent(message: String) {
        Log.d("AtomRemoteSmp", message)
        _events.value = (_events.value + "${System.currentTimeMillis()}: $message").takeLast(700)
    }

    private fun bondLabel(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "none"
        BluetoothDevice.BOND_BONDING -> "bonding"
        BluetoothDevice.BOND_BONDED -> "bonded"
        else -> "unknown($state)"
    }

    private fun advertiseErrorLabel(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data-too-large"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too-many-advertisers"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already-started"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal-error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature-unsupported"
        else -> "unknown"
    }

    companion object {
        val AIRGLU_SERVICE_UUID: UUID = UUID.fromString("9383b854-8bee-11e7-bb31-be2e44b06b34")
        // 0xFCC1 is assigned to Timecode Systems as a company identifier. Existing
        // AtomRemote test traces nevertheless prove that the FCC1-form presence UUID
        // is enough for AG2 to connect inbound, so retain it only as a compatibility
        // discovery beacon while the GATT server itself mirrors the live 9383 profile.
        val PAIRING_PRESENCE_UUID: UUID = UUID.fromString("0000fcc1-0000-1000-8000-00805f9b34fb")
        const val TIMECODE_SYSTEMS_COMPANY_ID: Int = 0xFCC1
        val FALLBACK_CAPTURE_CHARACTERISTIC_UUID: UUID = UUID.fromString("49a1c001-7e12-4c4a-9f2a-41544f4d4f53")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val GATT_INVALID_OFFSET = 7
        private const val MAX_CAPTURE_BYTES = 512
    }
}
