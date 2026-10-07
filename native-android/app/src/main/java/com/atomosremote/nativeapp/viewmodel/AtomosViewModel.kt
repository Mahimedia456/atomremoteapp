package com.atomosremote.nativeapp.viewmodel

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atomosremote.nativeapp.advanced.RecorderPreset
import com.atomosremote.nativeapp.advanced.RecorderPresetStore
import com.atomosremote.nativeapp.airglu.AirGluConfig
import com.atomosremote.nativeapp.airglu.AirGluConfigStore
import com.atomosremote.nativeapp.airglu.AirGluRole
import com.atomosremote.nativeapp.airglu.SafeAirGluBoundary
import com.atomosremote.nativeapp.atomos.AtomosCommandEngine
import com.atomosremote.nativeapp.atomos.AtomosDeviceProfile
import com.atomosremote.nativeapp.atomos.AtomosProfiles
import com.atomosremote.nativeapp.atomos.ProtocolProfile
import com.atomosremote.nativeapp.atomos.S1cChannel
import com.atomosremote.nativeapp.atomos.S1cCommands
import com.atomosremote.nativeapp.atomos.S1cStatus
import com.atomosremote.nativeapp.atomos.extractS1cAscii
import com.atomosremote.nativeapp.atomos.isS1cReply
import com.atomosremote.nativeapp.atomos.reduceS1cStatus
import com.atomosremote.nativeapp.ble.BleAdvertisement
import com.atomosremote.nativeapp.ble.BleScanner
import com.atomosremote.nativeapp.ble.GattClient
import com.atomosremote.nativeapp.ble.AtomRemotePeripheral
import com.atomosremote.nativeapp.ble.PairingIdRescue
import com.atomosremote.nativeapp.ble.PairingRuntimeGate
import com.atomosremote.nativeapp.backend.BackendAgentCoordinator
import com.atomosremote.nativeapp.backend.BackendInputRepository
import com.atomosremote.nativeapp.capture.CaptureExport
import com.atomosremote.nativeapp.capture.PairingCandidateCapture
import com.atomosremote.nativeapp.capture.ProtocolCaptureExporter
import com.atomosremote.nativeapp.data.DeviceIdentityStore
import com.atomosremote.nativeapp.data.KnownPrimaryDevice
import com.atomosremote.nativeapp.data.ProtocolProfileStore
import com.atomosremote.nativeapp.data.SavedAtomosDevice
import com.atomosremote.nativeapp.input.InputSessionSnapshotCache
import com.atomosremote.nativeapp.monitor.MonitorCoreCatalog
import com.atomosremote.nativeapp.monitor.MonitorZoomCoreState
import com.atomosremote.nativeapp.monitor.MonitorZoomViewportState
import com.atomosremote.nativeapp.monitor.MonitorFocusAssistState
import com.atomosremote.nativeapp.monitor.MonitorZebraState
import com.atomosremote.nativeapp.monitor.MonitorAssistEffectsState
import com.atomosremote.nativeapp.monitor.MonitorScopeState
import com.atomosremote.nativeapp.monitor.MonitorGuideState
import com.atomosremote.nativeapp.monitor.MonitorAnamorphicState
import com.atomosremote.nativeapp.monitor.MonitorLutState
import com.atomosremote.nativeapp.monitor.MonitorDisplayState
import com.atomosremote.nativeapp.multi.MultiDeviceController
import com.atomosremote.nativeapp.ndi.NdiPreviewState
import com.atomosremote.nativeapp.ndi.NdiSource
import com.atomosremote.nativeapp.ndi.SafeNdiPreviewBoundary
import com.atomosremote.nativeapp.network.NetworkDiscovery
import com.atomosremote.nativeapp.network.LanDeviceCandidate
import com.atomosremote.nativeapp.network.PortProbeResult
import com.atomosremote.nativeapp.qa.QaReport
import com.atomosremote.nativeapp.qa.ReleaseReadinessRunner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class PairingCodeCandidate(
    val pairingId: String,
    val sourceAddress: String,
    val sourceName: String?,
    val rssi: Int,
    val hits: Int,
    val exactAg2Source: Boolean,
    val lastSeenMs: Long
)

class AtomosViewModel(app: Application) : AndroidViewModel(app) {
    enum class Screen {
        HOME, CONNECT, PAIR, REMOTE,
        INPUT, AUDIO, MEDIA, OUTPUT, SETTINGS,
        GATT, PROTOCOL, COMPATIBILITY, NETWORK, MULTI, AIRGLU,
        NDI, ADVANCED, PRESETS, CAPTURE, QA
    }

    private val scanner = BleScanner(app)
    private val peripheral = AtomRemotePeripheral(app)
    val gatt = GattClient(app)
    private val backendAgent = BackendAgentCoordinator(
        gattClient = gatt,
        scope = viewModelScope
    )
    private val backendInputRepository = BackendInputRepository()
    private val identityStore = DeviceIdentityStore(app)
    private val trustedPrefs = app.getSharedPreferences("atomosremote_trusted_tcs", 0)
    private val profileStore = ProtocolProfileStore(app)
    private val commandEngine = AtomosCommandEngine(gatt)
    private val networkDiscovery = NetworkDiscovery(app)
    private val multiController = MultiDeviceController(app)
    private val airGluStore = AirGluConfigStore(app)
    private val airGluBridge = SafeAirGluBoundary()
    private val ndiBridge = SafeNdiPreviewBoundary()
    private val presetStore = RecorderPresetStore(app)
    private val captureExporter = ProtocolCaptureExporter(app)
    private val qaRunner = ReleaseReadinessRunner(app)

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen

    private val _devices = MutableStateFlow<List<BleAdvertisement>>(emptyList())
    val devices: StateFlow<List<BleAdvertisement>> = _devices

    private val _scanState = MutableStateFlow("idle")
    val scanState: StateFlow<String> = _scanState

    private val _selected = MutableStateFlow<BleAdvertisement?>(null)
    val selected: StateFlow<BleAdvertisement?> = _selected

    private val _saved = MutableStateFlow(identityStore.load())
    val saved: StateFlow<SavedAtomosDevice?> = _saved

    private val _savedAll = MutableStateFlow(identityStore.loadAll())
    val savedAll: StateFlow<List<SavedAtomosDevice>> = _savedAll

    private val _protocol = MutableStateFlow(profileStore.load())
    val protocol: StateFlow<ProtocolProfile?> = _protocol

    private val _commandStatus = MutableStateFlow<String?>(null)
    val commandStatus: StateFlow<String?> = _commandStatus

    // Agent7 20.03.5.4.6. Pure UI/ViewModel gate. GattClient remains frozen.
    private val remoteTransportUiController = RemoteTransportUiController()
    val remoteTransportUiState: StateFlow<RemoteTransportUiController.State> =
        remoteTransportUiController.state

    private val packetLinkTimeoutRecoveryGuard = PacketLinkTimeoutRecoveryGuard()

    // Phase 03 Monitor Assist UI state. Full recorder state reconciliation is
    // intentionally deferred to Phase 10; these values update only after a
    // verified macOS-derived setter command is successfully queued.
    private val _monitorZoom = MutableStateFlow(false)
    val monitorZoom: StateFlow<Boolean> = _monitorZoom
    private val _monitorZoomLevel = MutableStateFlow(0)
    val monitorZoomLevel: StateFlow<Int> = _monitorZoomLevel
    private val _monitorFocusAssist = MutableStateFlow(false)
    val monitorFocusAssist: StateFlow<Boolean> = _monitorFocusAssist
    private val _monitorZebra = MutableStateFlow(false)
    val monitorZebra: StateFlow<Boolean> = _monitorZebra
    private val _monitorFalseColor = MutableStateFlow(false)
    val monitorFalseColor: StateFlow<Boolean> = _monitorFalseColor
    private val _monitorBlueOnly = MutableStateFlow(false)
    val monitorBlueOnly: StateFlow<Boolean> = _monitorBlueOnly
    val zoomSupportedLevels: StateFlow<List<Int>> = gatt.zoomSupportedLevels
    val zoomCapabilitiesKnown: StateFlow<Boolean> = gatt.zoomCapabilitiesKnown
    val monitorZoomCore: StateFlow<MonitorZoomCoreState> = gatt.monitorZoomCore
    val monitorZoomViewport: StateFlow<MonitorZoomViewportState> = gatt.monitorZoomViewport
    val monitorFocusAssistState: StateFlow<MonitorFocusAssistState> = gatt.monitorFocusAssist
    val monitorZebraState: StateFlow<MonitorZebraState> = gatt.monitorZebraState
    val monitorAssistEffects: StateFlow<MonitorAssistEffectsState> = gatt.monitorAssistEffects
    val monitorScopeState: StateFlow<MonitorScopeState> = gatt.monitorScopeState
    val monitorGuideState: StateFlow<MonitorGuideState> = gatt.monitorGuideState
    val monitorAnamorphicState: StateFlow<MonitorAnamorphicState> = gatt.monitorAnamorphicState
    val anamorphicModeFamily = MonitorAnamorphicState.macOsModeFamily
    val monitorLutState: StateFlow<MonitorLutState> = gatt.monitorLutState
    val monitorDisplayState: StateFlow<MonitorDisplayState> = gatt.monitorDisplayState
    val zoomBoxState = gatt.zoomBoxState

    // Phase 04 Scopes UI state. Like Phase 03, this is intentionally local
    // until Phase 10 adds full recorder state reconciliation. The values are
    // changed only after a macOS-evidenced RemoteApi setter is queued.
    private val _scopeEnabled = MutableStateFlow(false)
    val scopeEnabled: StateFlow<Boolean> = _scopeEnabled
    private val _scopeMode = MutableStateFlow("luma-parade")
    val scopeMode: StateFlow<String> = _scopeMode
    private val _scopeSize = MutableStateFlow("quarter")
    val scopeSize: StateFlow<String> = _scopeSize

    // Phase 05 Guides local UI state. Full read-back reconciliation remains a
    // Phase 10 responsibility; setter acceptance/errors are still surfaced by
    // GattClient and the recorder response.
    private val _frameGuideEnabled = MutableStateFlow(false)
    val frameGuideEnabled: StateFlow<Boolean> = _frameGuideEnabled
    private val _frameGridEnabled = MutableStateFlow(false)
    val frameGridEnabled: StateFlow<Boolean> = _frameGridEnabled
    private val _safeAreaMode = MutableStateFlow(0)
    val safeAreaMode: StateFlow<Int> = _safeAreaMode

    // Phase 06 Anamorphic De-squeeze. Values are the exact macOS controller
    // enum values recovered from Contents(2).zip. Unsupported values remain
    // recorder-authoritative and are reported through RemoteApi errors.
    private val _anamorphicMode = MutableStateFlow(0)
    val anamorphicMode: StateFlow<Int> = _anamorphicMode

    // Phase 07 macOS MonitorModeSet state: 0 Native/SDR, 3 Log/HDR, 4 LUT.
    private val _monitorDisplayMode = MutableStateFlow(0)
    val monitorDisplayMode: StateFlow<Int> = _monitorDisplayMode
    val monitorCoreSnapshot = gatt.monitorCoreSnapshot
    val monitorModeOptions = MonitorCoreCatalog.officialModes
    private var monitorSnapshotAddress: String? = null
    private var monitorSnapshotJob: kotlinx.coroutines.Job? = null
    private var monitorUiActionRetryJob: Job? = null
    private val monitorSnapshotStepLastQueuedAt = mutableMapOf<String, Long>()
    private var monitorSnapshotGeneration: Int = 0

    // Phase 08 Record codec selection. Profile 0 means no explicit selection has
    // been made in Android yet, so we never pretend to know the recorder's
    // current codec before Phase 10 read-back/state synchronization.
    private val _codecProfile = MutableStateFlow(0)
    val codecProfile: StateFlow<Int> = _codecProfile
    // R16.2: -1 means recorder bit depth has not been read yet. Do not paint
    // 10-bit as selected merely because protobuf enum zero is a valid value.
    private val _codecBitDepth = MutableStateFlow(-1)
    val codecBitDepth: StateFlow<Int> = _codecBitDepth
    // R16.1 keeps the user's draft choice separate from recorder-authoritative
    // confirmation so periodic RecordPage polling cannot overwrite LT/HQ/etc.
    // between selection and APPLY.
    private var codecSelectionDirty: Boolean = false
    // 19.11.9 exposes the last recorder-readback tuple separately from the
    // currently selected UI tuple. A selection is never called confirmed until
    // CodecProfileGet reports it.
    val codecHardwareProfile: StateFlow<Pair<Int, Int>?> = gatt.codecHardwareProfile
    val codecSupportedProfiles: StateFlow<List<Int>> = gatt.codecSupportedProfiles
    val codecSupportedBitDepths: StateFlow<List<Int>> = gatt.codecSupportedBitDepths

    // Phase 09 read-only recorder-reported Input / Format summary.
    val inputFormatInfo: StateFlow<String?> = gatt.inputFormatInfo
    val inputCodecId: StateFlow<Int?> = gatt.inputCodecId
    val inputCameraId: StateFlow<Int?> = gatt.inputCameraId
    val inputGammaId: StateFlow<Int?> = gatt.inputGammaId
    val inputGamutId: StateFlow<Int?> = gatt.inputGamutId
    val outputInfo: StateFlow<String?> = gatt.outputInfo
    val outputHdrTypeId: StateFlow<Int?> = gatt.outputHdrTypeId
    val outputGamutId: StateFlow<Int?> = gatt.outputGamutId
    val inputOutputRawHex: StateFlow<String?> = gatt.inputOutputRawHex
    val inputCodecCollector = gatt.inputCodecCollector

    val inputExtendedSnapshot = gatt.inputExtendedSnapshot
    val inputSetterState = gatt.inputSetterState

    // 20.03.7.9: Input data is prefetched once as soon as the recorder session
    // reaches usable PacketLink/Remote state. Opening the Input tab consumes the
    // already-populated session snapshot instead of starting a tab-owned sync.
    private val inputSessionSnapshotCache = InputSessionSnapshotCache()
    val inputSessionSnapshot = inputSessionSnapshotCache.state

    private var inputSessionBootstrapJob: Job? = null
    private var inputAutoRefreshJob: Job? = null
    private var inputSessionExpectedCoreGeneration: Int = -1
    private var inputSessionExpectedExtendedGeneration: Int = -1
    private var inputSessionCoreGeneration: Int = -1

    fun shouldAutoSyncInput(): Boolean =
        !inputSessionSnapshotCache.isReadyFor(_selected.value?.address)

    fun markInputAutoSyncCached() {
        val address = _selected.value?.address ?: return
        inputSessionSnapshotCache.ready(
            address = address,
            coreGeneration = gatt.inputCodecCollector.value.generation,
            extendedGeneration = gatt.inputExtendedSnapshot.value.generation,
            partial = false
        )
        Log.d("AtomRemoteSession", "TCS20.03.7.9 INPUT SESSION CACHE READY address=$address")
    }

    fun invalidateInputAutoSyncCache() {
        inputSessionSnapshotCache.invalidate()
        inputSessionExpectedCoreGeneration = -1
        inputSessionExpectedExtendedGeneration = -1
        inputSessionCoreGeneration = -1
        Log.d("AtomRemoteSession", "TCS20.03.7.9 INPUT SESSION CACHE INVALIDATED")
    }

    fun ensureInputSessionSnapshot(reason: String = "ui-fallback") {
        startInputSessionBootstrapIfNeeded(reason)
    }

    fun refreshInputSessionSnapshotFromUi() {
        invalidateInputAutoSyncCache()
        startInputSessionBootstrapIfNeeded("user-refresh")
    }

    fun setInputTabVisible(visible: Boolean) {
        if (!visible) {
            inputAutoRefreshJob?.cancel()
            inputAutoRefreshJob = null
            Log.d("AtomRemoteSession", "TCS20.12.5 INPUT TAB ONE-SHOT STOP")
            return
        }
        if (inputAutoRefreshJob?.isActive == true) return
        inputAutoRefreshJob = viewModelScope.launch {
            // One recorder refresh per Input-tab entry. No repeating timer.
            if (_selected.value != null && gatt.tcsRemoteState.value.available &&
                !gatt.inputCodecCollector.value.running && !gatt.inputExtendedSnapshot.value.running &&
                !gatt.inputSetterState.value.busy) {
                invalidateInputAutoSyncCache()
                startInputSessionBootstrapIfNeeded("input-tab-one-shot")
                Log.d("AtomRemoteSession", "TCS20.12.5 INPUT TAB ONE-SHOT REFRESH")
            } else {
                Log.d("AtomRemoteSession", "TCS20.12.5 INPUT TAB ONE-SHOT DEFER busy-or-not-ready")
            }
        }
    }

    private fun startInputSessionBootstrapIfNeeded(reason: String) {
        val address = _selected.value?.address ?: return
        if (!_pairingCodeAccepted.value || !gatt.tcsPairingState.value.acceptWritten) return
        if (!gatt.tcsRemoteState.value.available) return

        if (inputSessionSnapshotCache.isReadyFor(address)) {
            Log.d("AtomRemoteSession", "TCS20.03.7.9 INPUT SESSION CACHE HIT reason=$reason address=$address")
            return
        }
        if (inputSessionSnapshotCache.isRunningFor(address) || inputSessionBootstrapJob?.isActive == true) return

        inputSessionBootstrapJob = viewModelScope.launch {
            // PacketLink may still be completing its last connect/bootstrap turn.
            // Retry only until the existing collector accepts ownership; no tab
            // navigation or Compose recomposition participates in this loop.
            repeat(40) { attempt ->
                if (_selected.value?.address != address) return@launch
                if (!gatt.tcsRemoteState.value.available) return@launch

                val queued = gatt.refreshGranularInputState()
                if (queued) {
                    inputSessionExpectedCoreGeneration =
                        gatt.inputCodecCollector.value.generation + 1
                    inputSessionSnapshotCache.beginCore(
                        address = address,
                        expectedGeneration = inputSessionExpectedCoreGeneration
                    )
                    Log.d(
                        "AtomRemoteSession",
                        "TCS20.03.7.9 INPUT SESSION PREFETCH START reason=$reason address=$address attempt=$attempt expectedCore=$inputSessionExpectedCoreGeneration"
                    )
                    return@launch
                }
                delay(100L)
            }
            Log.d(
                "AtomRemoteSession",
                "TCS20.03.7.9 INPUT SESSION PREFETCH DEFERRED reason=$reason address=$address"
            )
        }
    }

    private fun startInputExtendedSessionBootstrap(address: String, coreGeneration: Int) {
        if (_selected.value?.address != address) return
        viewModelScope.launch {
            repeat(40) { attempt ->
                if (_selected.value?.address != address) return@launch
                val queued = gatt.refreshExtendedInputState()
                if (queued) {
                    inputSessionCoreGeneration = coreGeneration
                    inputSessionExpectedExtendedGeneration =
                        gatt.inputExtendedSnapshot.value.generation + 1
                    inputSessionSnapshotCache.beginExtended(
                        address = address,
                        coreGeneration = coreGeneration,
                        expectedGeneration = inputSessionExpectedExtendedGeneration
                    )
                    Log.d(
                        "AtomRemoteSession",
                        "TCS20.03.7.9 INPUT SESSION EXTENDED PREFETCH START address=$address attempt=$attempt expectedExtended=$inputSessionExpectedExtendedGeneration"
                    )
                    return@launch
                }
                delay(100L)
            }

            // Core 3/3 is still valid useful recorder state even when every
            // optional extended getter cannot start.
            inputSessionSnapshotCache.ready(
                address = address,
                coreGeneration = coreGeneration,
                extendedGeneration = gatt.inputExtendedSnapshot.value.generation,
                partial = true
            )
            Log.d(
                "AtomRemoteSession",
                "TCS20.03.7.9 INPUT SESSION READY partial=true reason=extended-not-started address=$address"
            )
        }
    }
    val remoteStateSnapshot = gatt.remoteStateSnapshot // AppPolling diagnostic only in 19.12.10
    val safeAreaSummaryRaw = gatt.safeAreaSummaryRaw
    val frameGuideFormatSummaryRaw = gatt.frameGuideFormatSummaryRaw

    private val _pairingCodeAccepted = MutableStateFlow(false)
    val pairingCodeAccepted: StateFlow<Boolean> = _pairingCodeAccepted

    private val _pairingCodeCandidates = MutableStateFlow<List<PairingCodeCandidate>>(emptyList())
    val pairingCodeCandidates: StateFlow<List<PairingCodeCandidate>> = _pairingCodeCandidates

    private val _s1cStatus = MutableStateFlow(S1cStatus())
    val s1cStatus: StateFlow<S1cStatus> = _s1cStatus

    private val _networkProbeResults = MutableStateFlow<List<PortProbeResult>>(emptyList())
    val networkProbeResults: StateFlow<List<PortProbeResult>> = _networkProbeResults

    private val _wifiAssistTarget = MutableStateFlow<LanDeviceCandidate?>(null)
    val wifiAssistTarget: StateFlow<LanDeviceCandidate?> = _wifiAssistTarget

    private val _airGluConfig = MutableStateFlow(airGluStore.load())
    val airGluConfig: StateFlow<AirGluConfig> = _airGluConfig

    private val _presets = MutableStateFlow(presetStore.loadAll())
    val presets: StateFlow<List<RecorderPreset>> = _presets

    private val _ndiPreviewState = MutableStateFlow<NdiPreviewState>(NdiPreviewState.Idle)
    val ndiPreviewState: StateFlow<NdiPreviewState> = _ndiPreviewState

    private val _qaReport = MutableStateFlow<QaReport?>(null)
    val qaReport: StateFlow<QaReport?> = _qaReport

    val networkStatus = networkDiscovery.status
    val networkServices = networkDiscovery.services
    val lanDevices = networkDiscovery.lanDevices
    val multiSessions = multiController.sessions
    val gattState = gatt.state
    val bondState = gatt.bondState
    val gattDisconnectReason = gatt.disconnectReason
    val gattSnapshot = gatt.snapshot
    val tcsPairingState = gatt.tcsPairingState
    val tcsRemoteState = gatt.tcsRemoteState
    val connectedRecorderName = gatt.connectedRecorderName
    val gattEvents = gatt.events
    val peripheralState = peripheral.state
    val peripheralEvents = peripheral.events
    val peripheralCentralAddress = peripheral.centralAddress
    val peripheralPairingId = peripheral.observedPairingId
    val peripheralProfileSummary = peripheral.profileSummary
    val peripheralSmpPasskey = peripheral.smpPasskey
    val peripheralSmpPairingVariant = peripheral.smpPairingVariant
    val profiles: List<AtomosDeviceProfile> = AtomosProfiles.profiles
    val airGluNativeAvailable: Boolean get() = airGluBridge.available
    val airGluImplementationName: String get() = airGluBridge.implementationName
    val ndiNativeAvailable: Boolean get() = ndiBridge.available
    val ndiImplementationName: String get() = ndiBridge.implementationName

    private var scanJob: Job? = null
    private var s1cProbeJob: Job? = null
    private var pairingFlowJob: Job? = null
    private var pairingFlowAddress: String? = null
    private var trustedReconnectAddress: String? = null
    private var postBondControlAttemptAddress: String? = null
    private var pairingSessionStartedAtMs: Long = 0L
    private var selectedSessionPairingId: String? = null

    private data class TrustedTcs(val code: String?, val ccid: Long, val bcid: Long)

    private fun trustedKey(address: String, field: String) = "${address.uppercase()}.$field"

    private fun loadTrustedTcs(address: String): TrustedTcs? {
        val ccid = trustedPrefs.getLong(trustedKey(address, "ccid"), 0L)
        val bcid = trustedPrefs.getLong(trustedKey(address, "bcid"), 0L)
        if (ccid == 0L || bcid == 0L) return null
        return TrustedTcs(trustedPrefs.getString(trustedKey(address, "code"), null), ccid, bcid)
    }

    private fun saveTrustedTcs(address: String, code: String?, ccid: Long, bcid: Long) {
        if (ccid == 0L || bcid == 0L) return
        trustedPrefs.edit()
            .putLong(trustedKey(address, "ccid"), ccid)
            .putLong(trustedKey(address, "bcid"), bcid)
            .putString(trustedKey(address, "code"), code)
            .apply()
    }

    private fun clearTrustedTcs(address: String) {
        trustedPrefs.edit()
            .remove(trustedKey(address, "ccid"))
            .remove(trustedKey(address, "bcid"))
            .remove(trustedKey(address, "code"))
            .apply()
    }

    init {
        backendAgent.start()
        Log.d("AtomRemoteBackend", "Backend agent coordinator started deviceId=ag2-main mode=LOCAL")
        viewModelScope.launch {
            gatt.state.collect { state ->
                if (state == "services-ready") {
                    if (!_s1cStatus.value.ready && _s1cStatus.value.state != "pairing-api") {
                        _s1cStatus.value = _s1cStatus.value.copy(state = "link-stable", error = null)
                    }
                    val ad = _selected.value
                    if (ad != null && isLikelyAtomRemoteCandidate(ad) && pairingFlowAddress != ad.address) {
                        pairingFlowAddress = ad.address
                        launchAutomaticPairingFlow(ad.address)
                    }
                } else if (state == "disconnected" || state.startsWith("error:") || state.startsWith("service-error:")) {
                    pairingFlowJob?.cancel()
                    _s1cStatus.value = _s1cStatus.value.copy(state = "disconnected", error = null)
                }
            }
        }
        viewModelScope.launch {
            gatt.snapshot.collect { snap ->
                val ad = _selected.value
                if (snap != null && ad != null && snap.deviceAddress.equals(ad.address, ignoreCase = true)) {
                    Log.d("AtomRemotePair", "18.3 official TCS AG2 GATT snapshot ready for ${ad.address}: services=${snap.serviceUuids.size} chars=${snap.characteristics.size}")
                    // 18.0 stays on the direct outbound AG2 GATT link and ports the
                    // official AtomRemote TCSiOSC application pairing state machine.
                }

                if (snap?.pairingId != null && !_pairingCodeAccepted.value) {
                    if (ad != null && snap.deviceAddress.equals(ad.address, ignoreCase = true)) {
                        selectedSessionPairingId = snap.pairingId
                        PairingRuntimeGate.seedCapturedId(ad.address, snap.pairingId)
                        _selected.value = ad.copy(advertisedPairingId = snap.pairingId)
                        _pairingCodeCandidates.value = emptyList()
                    }
                    Log.d("AtomRemotePair", "TCS18.3 official app-generated Pairing ID=${snap.pairingId}")
                    _commandStatus.value =
                        "Official TCS Pairing ID ${snap.pairingId} generated and written as SHOGUN Connect Name. Wait for SHOGUN to display the same code, then accept."
                }
            }
        }
        viewModelScope.launch {
            gatt.tcsPairingState.collect { tcs ->
                val ad = _selected.value ?: return@collect
                tcs.pairingCode?.let { code ->
                    selectedSessionPairingId = code
                    PairingRuntimeGate.seedCapturedId(ad.address, code)
                    if (_selected.value?.advertisedPairingId != code) {
                        _selected.value = ad.copy(advertisedPairingId = code)
                    }
                }
                if (tcs.acceptWritten && tcs.ccid != null && tcs.bcid != null) {
                    saveTrustedTcs(ad.address, tcs.pairingCode, tcs.ccid, tcs.bcid)
                }
                _commandStatus.value = when (tcs.phase) {
                    "reading-id-slots" -> "Official TCS pairing: reading SHOGUN pairing slots..."
                    "writing-connect-name" -> "Official TCS pairing: writing the 9-digit Connect Name to SHOGUN..."
                    "writing-ccid" -> "Official TCS pairing: Connect Name accepted, writing CCID..."
                    "waiting-for-bcid" -> "SHOGUN has the code. Waiting for BCID. Compare the 9-digit code on both screens."
                    "can-accept" -> "SHOGUN pairing handshake is ready. If the recorder shows ${tcs.pairingCode}, tap ACCEPT MATCHING CODE."
                    "accepted-waiting-for-bcid" -> "Code accepted. Waiting for SHOGUN BCID before the official ~CCID acceptance write."
                    "writing-accept" -> "Sending official TCS acceptance (~CCID) to SHOGUN..."
                    "accepted" -> "Official TCS pairing accepted. Synchronizing the recorder frame clock and timecode before Remote opens."
                    "error" -> "Official TCS pairing error: ${tcs.lastError ?: "unknown"}"
                    else -> _commandStatus.value
                }
            }
        }
        viewModelScope.launch {
            gatt.connectedRecorderName.collect { name ->
                val ad = _selected.value ?: return@collect
                if (!name.isNullOrBlank()) {
                    trustedPrefs.edit().putString(trustedKey(ad.address, "name"), name).apply()
                }
            }
        }
        viewModelScope.launch {
            gatt.zoomHardwareLevel.collect { level ->
                if (level == null) return@collect
                _monitorZoomLevel.value = level
                _monitorZoom.value = level != 0
                val label = when (level) {
                    0 -> "Off"
                    1 -> "1:1"
                    else -> "2:1"
                }
                _commandStatus.value = "Zoom $label confirmed by recorder"
            }
        }
        viewModelScope.launch {
            gatt.zoomSupportedLevels.collect { levels ->
                if (levels.isNotEmpty()) {
                    _commandStatus.value = "Recorder zoom levels: ${levels.joinToString(", ")}"
                }
            }
        }
        viewModelScope.launch {
            gatt.safeAreaHardwareMode.collect { mode ->
                if (mode == null) return@collect
                _safeAreaMode.value = mode
                val label = when (mode) { 0 -> "Off"; 1 -> "Action"; else -> "Title" }
                _commandStatus.value = "Safe Area $label synchronized with recorder"
            }
        }
        viewModelScope.launch {
            gatt.frameGridHardwareEnabled.collect { enabled ->
                if (enabled == null) return@collect
                _frameGridEnabled.value = enabled
                _commandStatus.value = "9-Grid ${if (enabled) "On" else "Off"} confirmed by recorder"
            }
        }
        viewModelScope.launch {
            gatt.anamorphicHardwareMode.collect { mode ->
                if (mode == null) return@collect
                _anamorphicMode.value = mode
                val label = when (mode) {
                    0 -> "Off"; 1 -> "2x 4:3"; 2 -> "2x"; 3 -> "1.5x"; 4 -> "1.33x"
                    5 -> "1.8x"; 6 -> "1.25x"; 7 -> "1.79x"; 8 -> "1.65x"; else -> "1.6x"
                }
                _commandStatus.value = "Anamorphic $label confirmed by recorder"
            }
        }
        viewModelScope.launch {
            gatt.monitorModeHardware.collect { mode ->
                if (mode == null) return@collect
                _monitorDisplayMode.value = mode
                val label = MonitorCoreCatalog.label(mode)
                _commandStatus.value = "Monitor $label confirmed by recorder readback"
            }
        }
        viewModelScope.launch {
            gatt.codecHardwareProfile.collect { profile ->
                if (profile == null) return@collect
                val draftMatchesRecorder =
                    _codecProfile.value == profile.first && _codecBitDepth.value == profile.second
                if (!codecSelectionDirty || draftMatchesRecorder) {
                    _codecProfile.value = profile.first
                    _codecBitDepth.value = profile.second
                    codecSelectionDirty = false
                }
                _commandStatus.value = if (codecSelectionDirty && !draftMatchesRecorder) {
                    "Recorder is ${codecProfileLabel(profile.first)}; selected ${codecProfileLabel(_codecProfile.value)} is not confirmed yet"
                } else {
                    "Codec ${codecProfileLabel(profile.first)} confirmed by recorder readback"
                }
            }
        }
        viewModelScope.launch {
            gatt.remoteStateSnapshot.collect { snapshot ->
                if (snapshot.generation <= 0 || !snapshot.cycleComplete) return@collect
                // 19.12.10: AppPolling is diagnostic only on AG2. Never replace
                // the visible recorder status with misleading STATE 0/10 text.
                Log.d(
                    "AtomRemoteSession",
                    "TCS19.12.10 APPPOLLING DIAGNOSTIC generation=${snapshot.generation} pages=${snapshot.receivedPages}/${snapshot.expectedPages} json=${snapshot.json}"
                )
            }
        }
        viewModelScope.launch {
            gatt.inputCodecCollector.collect { snapshot ->
                if (snapshot.generation <= 0) return@collect
                Log.d(
                    "AtomRemoteSession",
                    "TCS19.12.10 COLLECTOR UI generation=${snapshot.generation} running=${snapshot.running} input=${snapshot.inputResponses}/${snapshot.inputExpected} codecVerified=${snapshot.codecVerified} json=${snapshot.json}"
                )
                if (!snapshot.running) {
                    _commandStatus.value = "Input recorder data ${snapshot.inputResponses}/${snapshot.inputExpected} getters returned"

                    val address = _selected.value?.address
                    if (
                        address != null &&
                        inputSessionExpectedCoreGeneration > 0 &&
                        snapshot.generation >= inputSessionExpectedCoreGeneration &&
                        inputSessionSnapshotCache.isRunningFor(address)
                    ) {
                        inputSessionExpectedCoreGeneration = -1
                        Log.d(
                            "AtomRemoteSession",
                            "TCS20.03.7.9 INPUT SESSION CORE READY generation=${snapshot.generation} responses=${snapshot.inputResponses}/${snapshot.inputExpected}"
                        )
                        startInputExtendedSessionBootstrap(address, snapshot.generation)
                    }
                }
            }
        }
        viewModelScope.launch {
            gatt.inputExtendedSnapshot.collect { snapshot ->
                val address = _selected.value?.address ?: return@collect
                if (
                    inputSessionExpectedExtendedGeneration > 0 &&
                    snapshot.generation >= inputSessionExpectedExtendedGeneration &&
                    !snapshot.running &&
                    inputSessionSnapshotCache.isRunningFor(address)
                ) {
                    val partial = snapshot.completed < snapshot.expected
                    inputSessionSnapshotCache.ready(
                        address = address,
                        coreGeneration = inputSessionCoreGeneration.coerceAtLeast(
                            gatt.inputCodecCollector.value.generation
                        ),
                        extendedGeneration = snapshot.generation,
                        partial = partial
                    )
                    inputSessionExpectedExtendedGeneration = -1
                    Log.d(
                        "AtomRemoteSession",
                        "TCS20.03.7.9 INPUT SESSION READY partial=$partial completed=${snapshot.completed}/${snapshot.expected} address=$address"
                    )
                }
            }
        }
        viewModelScope.launch {
            gatt.tcsRemoteState.collect { remote ->
                if (!_pairingCodeAccepted.value) return@collect

                if (
                    packetLinkTimeoutRecoveryGuard.shouldRecover(
                        phase = remote.phase,
                        error = remote.lastError
                    )
                ) {
                    Log.d(
                        "AtomRemoteSession",
                        "TCS20.03.6.3 AUTO TRUSTED RECOVERY reason=${remote.lastError}"
                    )
                    recoverRemoteSessionFromUi()
                    return@collect
                }

                when (remote.phase) {
                    "connected-sync", "waiting-frame-clock", "reading-timecode", "connected-synced" -> {
                        _s1cStatus.value = _s1cStatus.value.copy(state = "tcs-connected-sync", error = null)
                        _commandStatus.value = remote.lastStatus ?: "Synchronizing official TCS connected session..."
                        if (_screen.value == Screen.PAIR) _screen.value = Screen.REMOTE
                    }
                    "link-priming", "packet-ready" -> {
                        _s1cStatus.value = _s1cStatus.value.copy(state = "tcs-packet-ready", error = null)
                        _commandStatus.value = "18.6 recorder session synchronized. Opening PacketLink."
                        if (_screen.value == Screen.PAIR) _screen.value = Screen.REMOTE
                    }
                    "bootstrapping" -> {
                        _commandStatus.value = remote.lastStatus ?: "Synchronizing recorder state..."
                        if (_screen.value == Screen.PAIR) _screen.value = Screen.REMOTE
                    }
                    "transport-ready" -> {
                        _s1cStatus.value = _s1cStatus.value.copy(state = "tcs-linked", error = null)
                        _commandStatus.value = remote.lastStatus
                            ?: "PacketLink connected. RemoteApi state is unverified; controls remain enabled for direct hardware testing."
                        if (_screen.value == Screen.PAIR) _screen.value = Screen.REMOTE
                        Log.d("AtomRemoteSession", "TCS20.11 INPUT HOME PREFETCH SUPPRESSED phase=transport-ready")
                    }
                    "sending" -> _commandStatus.value = "Sending ${remote.lastCommand ?: "remote command"}..."
                    "awaiting-response" -> _commandStatus.value = remote.lastStatus ?: "Waiting for recorder response..."
                    "linked" -> {
                        _s1cStatus.value = _s1cStatus.value.copy(state = "tcs-linked", error = null)
                        _commandStatus.value = "Recorder remote transport is ready."
                        if (_screen.value == Screen.PAIR) _screen.value = Screen.REMOTE
                        Log.d("AtomRemoteSession", "TCS20.11 INPUT HOME PREFETCH SUPPRESSED phase=linked")
                    }
                    "error" -> {
                        val selected = _selected.value
                        val identityMismatch = remote.lastError?.contains("BCID", ignoreCase = true) == true ||
                            remote.lastStatus?.contains("pairing slot", ignoreCase = true) == true
                        if (identityMismatch && selected != null && trustedReconnectAddress == selected.address) {
                            clearTrustedTcs(selected.address)
                            trustedReconnectAddress = null
                            _pairingCodeAccepted.value = false
                            _commandStatus.value = "Saved pairing no longer matches this recorder. Fresh pairing is required once; stored trust was cleared safely."
                            _screen.value = Screen.PAIR
                        } else {
                            _commandStatus.value = "Remote link error: ${remote.lastError ?: "unknown"}"
                            if (_screen.value == Screen.PAIR) _screen.value = Screen.REMOTE
                        }
                    }
                }
            }
        }

    }

    fun go(screen: Screen) {
        Log.d("AtomRemoteUI", "TCS19.12.8 NAV ${_screen.value} -> $screen")
        _screen.value = screen
    }

    /**
     * 19.11.10 explicit Remote exit. This is deliberately app-level unpairing only:
     * disconnect the active BLE/GATT session, forget the saved TCS trust tuple and
     * saved recorder entry, clear the in-memory pairing gate, and return Home.
     *
     * It does NOT call Android createBond/removeBond and does NOT invent a recorder-side
     * Unpair command. The proven official TCS pairing sequence itself remains untouched.
     */
    fun disconnectRemoteAndUnpair() {
        val ad = _selected.value
        Log.d("AtomRemoteSession", "TCS19.11.10 USER DISCONNECT requested address=${ad?.address ?: "none"}")

        scanJob?.cancel()
        scanJob = null
        s1cProbeJob?.cancel()
        s1cProbeJob = null
        pairingFlowJob?.cancel()
        pairingFlowJob = null
        pairingFlowAddress = null
        trustedReconnectAddress = null
        postBondControlAttemptAddress = null

        peripheral.stopPairingPresence()
        peripheral.setExpectedCentralAddress(null)
        _wifiAssistTarget.value = null

        if (ad != null) {
            clearTrustedTcs(ad.address)
            trustedPrefs.edit().remove(trustedKey(ad.address, "name")).apply()
            identityStore.remove(ad.address)
            PairingRuntimeGate.beginFreshPairing(ad.address, null)
            Log.d("AtomRemoteSession", "TCS19.11.10 APP UNPAIR cleared trusted tuple and saved identity for ${ad.address}")
        }

        PairingIdRescue.clear()
        _pairingCodeAccepted.value = false
        _pairingCodeCandidates.value = emptyList()
        selectedSessionPairingId = null
        _s1cStatus.value = S1cStatus()
        _scanState.value = "idle"

        // Leave the Remote screen before closing GATT so a disconnect callback cannot
        // produce a visible red/flickering Remote UI during an intentional exit.
        _screen.value = Screen.HOME
        gatt.disconnect()
        _selected.value = null
        refreshSaved()
        _commandStatus.value = "Remote disconnected and recorder pairing removed from this app."
    }

    /**
     * Wi-Fi can reliably identify the recorder on the local LAN, but ATOMOS documents
     * AtomRemote control over Bluetooth LE. Use the LAN identity as a pairing assistant:
     * keep the discovered Shogun selected while the user enters Pair mode and perform an
     * unfiltered BLE scan for the matching ATOMOS/AirGlu control endpoint.
     */
    fun startWifiAssistedRemote(device: LanDeviceCandidate) {
        _wifiAssistTarget.value = device
        _commandStatus.value = "ATOMOS recorder found on the same network at ${device.host}. Open Bluetooth/AirGlu Pair on the recorder. Starting Bluetooth discovery now."
        _screen.value = Screen.CONNECT
        startScan()
    }

    fun clearWifiAssist() {
        _wifiAssistTarget.value = null
    }

    fun bluetoothEnabled(): Boolean = scanner.isEnabled()
    fun bluetoothAvailable(): Boolean = scanner.isAvailable()

    fun startScan() {
        // Discovery list remains scanner-only. 18.0 never requires the phone to
        // advertise a fake recorder profile during official TCS application pairing.
        peripheral.stopPairingPresence()
        peripheral.setExpectedCentralAddress(null)
        scanJob?.cancel()
        _devices.value = emptyList()
        _pairingCodeCandidates.value = emptyList()
        selectedSessionPairingId = null
        _scanState.value = "scanning"
        scanJob = viewModelScope.launch {
            // Preserve first-seen order. RSSI changes several times per second and must never
            // reorder LazyColumn rows while the user is trying to tap a recorder.
            val map = linkedMapOf<String, BleAdvertisement>()
            val failure = runCatching {
                withTimeoutOrNull(12_000L) {
                    scanner.scan().collect { ad ->
                        val merged = mergeAdvertisement(map[ad.address], ad)
                        map[ad.address] = merged
                        _devices.value = map.values.filter(::isStrictDiscoveryCandidate)
                        if (_selected.value?.address == merged.address) {
                            _selected.value = mergeAdvertisement(_selected.value, merged)
                        }
                    }
                }
            }.exceptionOrNull()

            if (failure != null) {
                _scanState.value = "error:${failure.message}"
            } else {
                _scanState.value = "complete:${map.values.count(::isStrictDiscoveryCandidate)}"
            }
            scanJob = null
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (_scanState.value == "scanning" || _scanState.value == "pairing-id-scan") {
            _scanState.value = "stopped"
        }
    }

    fun stopPairingPresence() {
        peripheral.stopPairingPresence()
    }

    private fun mergeAdvertisement(previous: BleAdvertisement?, latest: BleAdvertisement): BleAdvertisement {
        if (previous == null) return latest
        return latest.copy(
            name = latest.name ?: previous.name,
            serviceUuids = (previous.serviceUuids + latest.serviceUuids).distinct(),
            manufacturerDataHex = latest.manufacturerDataHex ?: previous.manufacturerDataHex,
            connectable = latest.connectable ?: previous.connectable,
            rawRecordHex = latest.rawRecordHex ?: previous.rawRecordHex,
            advertisedPairingId = latest.advertisedPairingId ?: previous.advertisedPairingId,
            manufacturerCompanyIds = (previous.manufacturerCompanyIds + latest.manufacturerCompanyIds).distinct(),
            serviceDataUuids = (previous.serviceDataUuids + latest.serviceDataUuids).distinct(),
            legacyAdvertisement = latest.legacyAdvertisement ?: previous.legacyAdvertisement,
            advertisingSid = latest.advertisingSid ?: previous.advertisingSid
        )
    }

    private fun isAppleOrGenericPhoneAdvertisement(ad: BleAdvertisement): Boolean {
        val n = ad.name?.trim()?.lowercase().orEmpty()
        if (ad.manufacturerCompanyIds.any { it == 76 }) return true // Apple company identifier 0x004C
        if (n == "iphone" || n == "ipad" || n.contains("airpods") || n.contains("apple watch")) return true
        val uuids = (ad.serviceUuids + ad.serviceDataUuids).map { it.lowercase() }
        return uuids.any { it == "d0611e78-bbb4-4591-a5f8-487910ae4366" || it == "9fa480e0-4967-4542-9390-d343dc5d04ae" }
    }

    private fun isStrictDiscoveryCandidate(ad: BleAdvertisement): Boolean {
        if (ad.connectable == false) return false
        if (isAppleOrGenericPhoneAdvertisement(ad)) return false
        // Phase 10 broadens discovery for future ATOMOS models while keeping
        // the actual TCS/GATT service preflight authoritative after connection.
        // This allows a named Ninja/Shogun/ATOMOS device to be shown even when
        // Android has not included the proprietary service UUID in advertising.
        val n = ad.name?.trim()?.lowercase().orEmpty()
        val knownAtomosName = n.contains("atomos") || n.contains("shogun") ||
            n.contains("ninja") || n.contains("neon") || n.contains("sumo") ||
            n.contains("shinobi") || n == "ag" || n == "ag2" ||
            n.startsWith("ag-") || n.startsWith("ag2-")
        return isAirGluSyncEndpoint(ad) || knownAtomosName
    }

    private fun normalizePairingCode(raw: String?): String? {
        val digits = raw?.filter(Char::isDigit).orEmpty()
        if (digits.length != 9 || digits.all { it == '0' }) return null
        return "${digits.substring(0, 3)}-${digits.substring(3, 6)}-${digits.substring(6, 9)}"
    }

    private fun startFocusedPairingIdScan(ad: BleAdvertisement) {
        peripheral.setExpectedCentralAddress(ad.address)
        scanJob?.cancel()
        _pairingCodeCandidates.value = emptyList()
        selectedSessionPairingId = ad.advertisedPairingId
        pairingSessionStartedAtMs = System.currentTimeMillis()
        _scanState.value = "pairing-session-code-scan"

        scanJob = viewModelScope.launch {
            data class Evidence(
                val id: String,
                val address: String,
                val name: String?,
                var rssi: Int,
                var hits: Int,
                var lastSeenMs: Long,
                val exact: Boolean
            )

            val evidence = linkedMapOf<String, Evidence>()
            ad.advertisedPairingId?.let { known ->
                val normalized = normalizePairingCode(known)
                if (normalized != null) {
                    evidence[normalized] = Evidence(normalized, ad.address, ad.name, ad.rssi, 10, System.currentTimeMillis(), true)
                }
            }

            fun publishCandidates(current: BleAdvertisement) {
                // Publish even a one-hit high-confidence code so the user can compare it
                // with the physical SHOGUN display. Auto-selection remains conservative:
                // direct AG2 evidence or a repeated session beacon only.
                val visible = evidence.values
                    .filter { it.hits >= 1 }
                    .sortedWith(
                        compareByDescending<Evidence> { it.exact }
                            .thenByDescending { it.hits }
                            .thenByDescending { it.rssi }
                    )
                    .map { PairingCodeCandidate(it.id, it.address, it.name, it.rssi, it.hits, it.exact, it.lastSeenMs) }
                _pairingCodeCandidates.value = visible

                val exact = visible.firstOrNull { it.exactAg2Source }
                val repeated = visible.filter { it.hits >= 2 }
                val uniqueRepeatedSession = repeated
                    .takeIf { list -> list.map { it.pairingId }.distinct().size == 1 }
                    ?.firstOrNull()
                val chosen = exact ?: uniqueRepeatedSession
                if (!_pairingCodeAccepted.value && chosen != null && selectedSessionPairingId != chosen.pairingId) {
                    selectedSessionPairingId = chosen.pairingId
                    PairingRuntimeGate.seedCapturedId(current.address, chosen.pairingId)
                    _selected.value = current.copy(advertisedPairingId = chosen.pairingId)
                    _commandStatus.value = if (chosen.exactAg2Source) {
                        "Pairing ID ${chosen.pairingId} captured directly from AG2. Compare it with SHOGUN and accept only if it matches."
                    } else {
                        "Pairing ID ${chosen.pairingId} correlated to the active AG2 Pair session from a repeated non-connectable pairing beacon. Compare it with SHOGUN before accepting."
                    }
                } else if (!_pairingCodeAccepted.value && visible.size > 1) {
                    _commandStatus.value =
                        "Multiple 9-digit Pairing IDs are visible in the active scan. Select the code that exactly matches the SHOGUN screen; none is auto-accepted."
                }
            }

            val failure = runCatching {
                withTimeoutOrNull(45_000L) {
                    scanner.scan().collect { fresh ->
                        val current = _selected.value ?: return@collect
                        val now = System.currentTimeMillis()
                        if (now - pairingSessionStartedAtMs > 48_000L) return@collect

                        if (fresh.address.equals(current.address, ignoreCase = true)) {
                            val merged = mergeAdvertisement(current, fresh)
                            _selected.value = merged
                            normalizePairingCode(merged.advertisedPairingId)?.let { id ->
                                val e = evidence[id]
                                if (e == null) {
                                    evidence[id] = Evidence(id, current.address, merged.name, merged.rssi, 2, now, true)
                                } else {
                                    e.hits += 1
                                    e.rssi = maxOf(e.rssi, merged.rssi)
                                    e.lastSeenMs = now
                                }
                            }
                            publishCandidates(merged)
                            return@collect
                        }

                        val id = normalizePairingCode(fresh.advertisedPairingId) ?: return@collect
                        if (isAppleOrGenericPhoneAdvertisement(fresh)) return@collect
                        // Shogun/AtomRemote pairing may expose the visible code on a separate
                        // non-connectable advertising set with a private address. Correlate it
                        // to the active selected AG2 session, but require repeated sightings and
                        // explicit user comparison before acceptance.
                        if (fresh.connectable == true) return@collect
                        if (fresh.rssi < -95) return@collect

                        val e = evidence[id]
                        if (e == null) {
                            evidence[id] = Evidence(id, fresh.address, fresh.name, fresh.rssi, 1, now, false)
                        } else {
                            e.hits += 1
                            e.rssi = maxOf(e.rssi, fresh.rssi)
                            e.lastSeenMs = now
                        }
                        publishCandidates(current)
                    }
                }
            }.exceptionOrNull()

            if (failure != null) {
                _scanState.value = "error:${failure.message}"
            } else if (_scanState.value == "pairing-session-code-scan") {
                _scanState.value = "pairing-session-code-scan-complete"
            }
            scanJob = null
        }
    }

    private fun scheduleLiveProfileWaitNotice(ad: BleAdvertisement, delayMs: Long) {
        viewModelScope.launch {
            delay(delayMs)
            val current = _selected.value ?: return@launch
            if (!current.address.equals(ad.address, ignoreCase = true)) return@launch
            if (gattSnapshot.value?.characteristics?.any {
                    it.serviceUuid.equals("9383b854-8bee-11e7-bb31-be2e44b06b34", ignoreCase = true)
                } == true) return@launch
            _commandStatus.value =
                "Still waiting for the recorder control service. Keep the recorder Pair screen open and retry only if the direct Bluetooth link does not become ready."
        }
    }

    fun selectAndConnect(ad: BleAdvertisement) {
        if (!isStrictDiscoveryCandidate(ad)) {
            _commandStatus.value =
                "Device filter rejected ${ad.name ?: ad.address}. Select a connectable ATOMOS recorder/AirGlu endpoint, not a phone or standalone pairing beacon."
            return
        }

        stopScan()
        s1cProbeJob?.cancel()
        pairingFlowJob?.cancel()
        pairingFlowAddress = null
        _pairingCodeAccepted.value = false
        _pairingCodeCandidates.value = emptyList()
        selectedSessionPairingId = null
        pairingSessionStartedAtMs = System.currentTimeMillis()
        trustedReconnectAddress = null
        postBondControlAttemptAddress = null
        _s1cStatus.value = S1cStatus()
        gatt.disconnect()

        // If this recorder was previously accepted by the application-level TCS
        // handshake, validate the saved CCID/BCID identity through the official
        // LINKED cxtic2+cxtic4 preflight. Never force a fresh pairing merely
        // because Android reports BOND_BONDED. A mismatch safely falls back.
        val trusted = loadTrustedTcs(ad.address)
        if (trusted != null) {
            trustedReconnectAddress = ad.address
            _selected.value = ad
            _pairingCodeAccepted.value = true
            selectedSessionPairingId = trusted.code
            peripheral.stopPairingPresence()
            peripheral.setExpectedCentralAddress(null)
            if (gatt.connectTrusted(ad.device, trusted.code, trusted.ccid, trusted.bcid)) {
                _screen.value = Screen.PAIR
                _commandStatus.value = "Recognized paired ATOMOS recorder. Verifying device identity and opening Remote automatically..."
                return
            }
        }

        PairingRuntimeGate.beginFreshPairing(ad.address, ad.advertisedPairingId)
        peripheral.stopPairingPresence()
        peripheral.setExpectedCentralAddress(null)
        _selected.value = ad
        // 18.0 ports the official macOS AtomRemote TCSiOSC pairing state machine.
        // Android is the central/GATT client. No phone GATT server or inbound link is required.
        Log.d("AtomRemotePair", "TCS19.10 select ATOMOS ${ad.name ?: ad.address}: official central pairing state machine")
        gatt.connect(ad.device)
        scheduleLiveProfileWaitNotice(ad, 4_000L)
        _screen.value = Screen.PAIR
        _commandStatus.value = when {
            ad.device.bondState == BluetoothDevice.BOND_BONDED && ad.advertisedPairingId == null ->
                "STALE ANDROID BOND DETECTED for ${ad.address}. Android already considers AG2 bonded before a fresh Pairing ID exists, so a new system pairing prompt may not appear. Use RESET STALE BOND + RESTART below, and use Unpair All once on SHOGUN if its Pair screen still reuses the old session."
            ad.advertisedPairingId != null ->
                "Selected ${ad.name ?: "ATOMOS recorder"} ${ad.address}. Pairing ID ${ad.advertisedPairingId} was seen on this endpoint. Compare it with the recorder before accepting."
            else ->
                "Selected ${ad.name ?: "ATOMOS recorder"} ${ad.address}. Keep the recorder Pair screen open while the secure pairing code is prepared."
        }
    }

    fun retryPairingIdScan() {
        val ad = _selected.value ?: return
        if (!isStrictDiscoveryCandidate(ad)) {
            _commandStatus.value = "Selected device is not a connectable ATOMOS/AirGlu candidate. Return to discovery and select the recorder by its advertised name."
            return
        }
        pairingFlowJob?.cancel()
        pairingFlowAddress = null
        postBondControlAttemptAddress = null
        _pairingCodeAccepted.value = false
        _pairingCodeCandidates.value = emptyList()
        selectedSessionPairingId = ad.advertisedPairingId
        pairingSessionStartedAtMs = System.currentTimeMillis()
        PairingRuntimeGate.beginFreshPairing(ad.address, ad.advertisedPairingId)
        peripheral.stopPairingPresence()
        peripheral.setExpectedCentralAddress(null)
        gatt.disconnect()
        Log.d("AtomRemotePair", "TCS18 retry AG2 ${ad.address}: official central pairing state machine")
        gatt.connect(ad.device)
        scheduleLiveProfileWaitNotice(ad, 4_000L)
        _commandStatus.value =
            "Retrying ${ad.name ?: "ATOMOS recorder"} ${ad.address}. Pairing runs directly over the recorder Bluetooth control service."
    }

    fun selectPairingCodeCandidate(pairingId: String) {
        val ad = _selected.value ?: return
        val normalized = normalizePairingCode(pairingId) ?: return
        val candidate = _pairingCodeCandidates.value.firstOrNull { it.pairingId == normalized } ?: return
        selectedSessionPairingId = normalized
        PairingRuntimeGate.seedCapturedId(ad.address, normalized)
        _selected.value = ad.copy(advertisedPairingId = normalized)
        _commandStatus.value = if (candidate.exactAg2Source) {
            "Using Pairing ID $normalized captured directly from AG2. Accept only if SHOGUN shows the same code."
        } else {
            "Using Pairing ID $normalized from pairing beacon ${candidate.sourceAddress}. It is bound to the CURRENT AG2 Pair session only after your manual code match."
        }
    }

    fun selectDiagnosticPairingCode(pairingId: String) {
        val ad = _selected.value ?: return
        val normalized = normalizePairingCode(pairingId) ?: return
        val candidates = gattSnapshot.value?.binaryPairingCandidates.orEmpty()
        if (normalized !in candidates) return
        selectedSessionPairingId = normalized
        PairingRuntimeGate.seedCapturedId(ad.address, normalized)
        _selected.value = ad.copy(advertisedPairingId = normalized)
        Log.d("AtomRemotePair", "User selected GATT-derived diagnostic Pairing-ID candidate=$normalized for ${ad.address}")
        _commandStatus.value =
            "Diagnostic candidate $normalized selected. ACCEPT only if the Shogun Ultra screen shows exactly the same 9-digit code. It is derived from AG2 session bytes and is never auto-trusted."
    }

    fun confirmPairingCodeAndContinue() {
        val ad = _selected.value ?: return
        if (!isStrictDiscoveryCandidate(ad)) {
            _commandStatus.value = "Pairing acceptance blocked: selected device is not the active connectable ATOMOS/AirGlu endpoint."
            return
        }
        val pairingId = selectedSessionPairingId
            ?: ad.advertisedPairingId
            ?: PairingRuntimeGate.currentPairingId(ad.address)
        if (pairingId == null) {
            _commandStatus.value =
                "No Pairing ID is selected for this recorder session. Keep Pair mode open and use only the code shown on the recorder."
            return
        }

        PairingRuntimeGate.seedCapturedId(ad.address, pairingId)
        if (!PairingRuntimeGate.confirmUserAccept(pairingId, ad.address)) {
            _commandStatus.value = "Pairing code acceptance failed because the selected code was not bound to the active AG2 session. Rescan and select the code shown on SHOGUN."
            return
        }

        val acceptStarted = gatt.acceptOfficialTcsPairing()
        if (!acceptStarted) {
            _commandStatus.value =
                "Official TCS accept is not ready yet. Wait until CCID is written and SHOGUN shows the same 9-digit code, then tap ACCEPT again."
            return
        }

        _pairingCodeAccepted.value = true
        _selected.value = ad.copy(advertisedPairingId = pairingId)
        stopScan()
        pairingFlowJob?.cancel()
        pairingFlowAddress = null
        postBondControlAttemptAddress = null
        peripheral.stopPairingPresence()
        peripheral.setExpectedCentralAddress(null)
        _scanState.value = "tcs18-code-accepted"
        Log.d("AtomRemotePair", "TCS18.3 user accepted matching Pairing ID $pairingId for ${ad.address}; writing official ~CCID gate")
        _commandStatus.value =
            "Code $pairingId accepted. The proven ~CCID gate will complete first, then 18.6 synchronizes the official TCS frame clock/timecode before PacketLink and Remote controls open."
    }

    private fun scheduleSameAg2ControlLink(ad: BleAdvertisement, reason: String, delayMs: Long) {
        postBondControlAttemptAddress = ad.address
        viewModelScope.launch {
            delay(delayMs)
            val current = _selected.value ?: return@launch
            val acceptedAddress = PairingRuntimeGate.acceptedSessionAddress()
            if (!_pairingCodeAccepted.value || !current.address.equals(ad.address, true) ||
                acceptedAddress?.equals(ad.address, true) != true) return@launch

            if (gattState.value == "services-ready") {
                pairingFlowAddress = null
                _commandStatus.value =
                    "Reusing the already-active AG2 ${ad.address} direct GATT link ($reason) for the official TCS pairing/control flow."
                launchAutomaticPairingFlow(ad.address, force = true)
                return@launch
            }
            if (gattState.value in setOf("connecting", "connected", "pre-pair-capture", "pair-discovery")) return@launch

            pairingFlowAddress = null
            _commandStatus.value =
                "Re-opening the same AG2 ${ad.address} as the outbound GATT endpoint ($reason). 18.3 official TCS central pairing state remains active; phone advertising stays OFF."
            gatt.connect(ad.device)
        }
    }

    fun retrySelectedConnection() {
        val ad = _selected.value ?: return
        if (!isStrictDiscoveryCandidate(ad)) {
            _pairingCodeAccepted.value = false
            _commandStatus.value = "Retry blocked: return to discovery and select the connectable AG2/AirGlu endpoint."
            return
        }
        gatt.disconnect()
        pairingFlowJob?.cancel()
        pairingFlowAddress = null
        peripheral.stopPairingPresence()
        peripheral.setExpectedCentralAddress(null)
        gatt.connect(ad.device)
        scheduleLiveProfileWaitNotice(ad, 4_000L)
        val pairingId = PairingRuntimeGate.acceptedSessionPairingId()
        if (_pairingCodeAccepted.value && pairingId != null && PairingRuntimeGate.acceptedSessionAddress()?.equals(ad.address, true) == true) {
            postBondControlAttemptAddress = null
            _scanState.value = "tcs18-code-accepted"
            _commandStatus.value =
                "Restarted the AG2 GATT link after code acceptance. 18.3 will reuse the official TCS pairing tuple on this session."
        } else {
            _pairingCodeAccepted.value = false
            _pairingCodeCandidates.value = emptyList()
            selectedSessionPairingId = null
            PairingRuntimeGate.beginFreshPairing(ad.address, null)
            _scanState.value = "tcs18-retrying-official-pairing"
            _commandStatus.value =
                "Restarted recorder Bluetooth link. 18.3 will rerun the official TCS ID-slots -> Connect Name -> CCID -> BCID sequence when services are ready."
        }
    }

    @Suppress("MissingPermission")
    fun clearAndroidBondAndRetry() {
        val ad = _selected.value ?: return
        pairingFlowJob?.cancel()
        scanJob?.cancel()
        scanJob = null
        gatt.disconnect()
        peripheral.stopPairingPresence()
        PairingIdRescue.clear()
        PairingRuntimeGate.beginFreshPairing(ad.address, null)
        _pairingCodeAccepted.value = false
        _pairingCodeCandidates.value = emptyList()
        selectedSessionPairingId = null

        val device = ad.device
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            _commandStatus.value = "Android bond is already clear. Restarting a clean SHOGUN Pair session now."
            restartPairingAfterBondReset(ad)
            return
        }

        val removed = runCatching {
            // BluetoothDevice.removeBond() is not part of the public Android SDK.
            // Some OEM builds still expose it. Use it only as a recovery attempt and
            // provide a settings fallback if Android blocks hidden API access.
            val method = BluetoothDevice::class.java.getMethod("removeBond")
            (method.invoke(device) as? Boolean) == true
        }.getOrDefault(false)

        if (!removed) {
            _commandStatus.value =
                "Android blocked automatic stale-bond removal. Open Android Bluetooth settings and Forget AG2/ATOMOS, then on SHOGUN use Sync Config > Bluetooth/AirGlu BT > Unpair All once. Return here and tap RETRY PAIRING ID CAPTURE."
            return
        }

        _scanState.value = "clearing-stale-android-bond"
        _commandStatus.value =
            "Removing stale Android bond for ${ad.address}. Waiting for BOND_NONE, then 18.3 will restart the direct official TCS GATT pairing flow."

        viewModelScope.launch {
            var cleared = false
            repeat(16) {
                delay(250L)
                if (device.bondState == BluetoothDevice.BOND_NONE) {
                    cleared = true
                    return@repeat
                }
            }
            if (device.bondState == BluetoothDevice.BOND_NONE) cleared = true
            if (cleared) {
                restartPairingAfterBondReset(ad.copy(advertisedPairingId = null))
            } else {
                _scanState.value = "stale-bond-removal-timeout"
                _commandStatus.value =
                    "Android did not clear the old bond. Forget AG2/ATOMOS manually in Android Bluetooth settings, use Unpair All once on SHOGUN, then retry."
            }
        }
    }

    private fun restartPairingAfterBondReset(ad: BleAdvertisement) {
        PairingIdRescue.clear()
        PairingRuntimeGate.beginFreshPairing(ad.address, null)
        peripheral.stopPairingPresence()
        peripheral.setExpectedCentralAddress(null)
        _selected.value = ad.copy(advertisedPairingId = null)
        pairingSessionStartedAtMs = System.currentTimeMillis()
        _scanState.value = "fresh-pairing-after-bond-reset"
        gatt.disconnect()
        gatt.connect(ad.device)
        scheduleLiveProfileWaitNotice(ad, 4_000L)
        _commandStatus.value =
            "Fresh Android bond state ready. Restarting the 18.3 official TCS pairing state machine on the direct AG2 GATT link."
    }

    fun startPairingDiscovery() {
        val ad = _selected.value ?: return
        if (!_pairingCodeAccepted.value || PairingRuntimeGate.acceptedSessionAddress()?.equals(ad.address, true) != true) {
            retryPairingIdScan()
            return
        }
        peripheral.stopPairingPresence()
        peripheral.setExpectedCentralAddress(null)
        _scanState.value = "tcs18.3-remote-open"
        Log.d("AtomRemotePair", "TCS18.6 starting official CONNECTED lifecycle before PacketLink for ${ad.address}")
        _commandStatus.value = "Synchronizing official TCS frame clock and timecode before PacketLink opens."
        if (gattState.value !in setOf("services-ready", "tcs-pairing", "connected", "connecting")) {
            gatt.connect(ad.device)
            return
        }
        if (!gatt.openOfficialTcsRemoteLink(autoProbe = true)) {
            _commandStatus.value = "TCS connected-session sync is not ready yet. Keep AG2 connected and retry Remote."
        }
    }

    private fun launchAutomaticPairingFlow(address: String, force: Boolean = false) {
        pairingFlowJob?.cancel()
        pairingFlowJob = viewModelScope.launch {
            delay(if (force) 40L else 120L)
            val ad = _selected.value ?: return@launch
            if (ad.address != address || gattState.value != "services-ready") return@launch

            if (trustedReconnectAddress == ad.address && !PairingRuntimeGate.isUserConfirmed(ad.address)) {
                _commandStatus.value =
                    "Trusted saved-device reconnect. Keeping the existing direct AG2 GATT link; no fresh pairing mutation started."
                probeS1cControlChannel()
                return@launch
            }

            if (!PairingRuntimeGate.isUserConfirmed(ad.address)) {
                _commandStatus.value =
                    "18.3 OFFICIAL TCS pairing: generating the 9-digit Connect Name, reading ID slots, then writing Connect Name + CCID and waiting for BCID."
                val started = gatt.beginOfficialTcsPairing()
                if (!started) {
                    _commandStatus.value =
                        "Official TCS pairing could not start. Keep SHOGUN on Sync Config > Bluetooth/AirGlu BT > Pair, then retry the AG2 link."
                }
                return@launch
            }

            _commandStatus.value =
                "Matching 9-digit code is accepted. Waiting for the official ~CCID write and SHOGUN remote/control transition."
        }
    }

    private fun isStandardGattServiceUuid(uuid: String): Boolean {
        val value = uuid.lowercase()
        return value.startsWith("00001800-") || value.startsWith("00001801-") ||
            value.startsWith("0000180a-") || value.startsWith("0000180f-")
    }

    private suspend fun probeAirGluPairingApiInternal(
        fast: Boolean = false,
        preCodeBootstrap: Boolean = false
    ) {
        val snapshot = gattSnapshot.value ?: return
        if (gattState.value != "services-ready") return

        val candidates = snapshot.characteristics
            .groupBy { it.serviceUuid }
            .filterKeys {
                !isStandardGattServiceUuid(it) &&
                    !it.equals(AIRGLU_SHOGUN_SERVICE_UUID, ignoreCase = true)
            }
            .toList()
            .sortedByDescending { (serviceUuid, _) ->
                val value = serviceUuid.lowercase()
                when {
                    value == AIRGLU_SHOGUN_SERVICE_UUID -> 2
                    value.startsWith("0000fcc1-") -> 1
                    else -> 0
                }
            }
            .flatMap { (serviceUuid, chars) ->
                val canReply = chars.any { it.notifiable || it.indicatable || it.readable }
                if (!canReply) emptyList() else chars
                    .filter { it.writable || it.writableNoResponse }
                    .flatMap { c ->
                        buildList {
                            if (c.writable) add(S1cChannel(serviceUuid, c.characteristicUuid, true))
                            if (c.writableNoResponse) add(S1cChannel(serviceUuid, c.characteristicUuid, false))
                        }
                    }
            }
            .distinctBy { Triple(it.serviceUuid, it.characteristicUuid, it.writeWithResponse) }
            .take(if (fast) 2 else 8)

        if (candidates.isEmpty()) {
            _s1cStatus.value = _s1cStatus.value.copy(
                state = "pairing-api-not-found",
                error = "No proven S1C UART-like service was discovered; proprietary 9383 blind writes remain blocked in 18.3"
            )
            _commandStatus.value = "No proven S1C transport is exposed outside the proprietary 9383 service. 18.3 uses the recovered TCS Packet Link for Remote commands instead."
            return
        }

        suspend fun sendVariant(channel: S1cChannel, command: String, prefixes: List<String>, timeout: Long): String? {
            val trimmed = command.trimEnd('\n', '\r')
            val frames = listOf("$trimmed\n", "$trimmed\r\n")
            for (frame in frames) {
                val baseline = gattEvents.value.size
                val ok = gatt.write(
                    serviceUuid = channel.serviceUuid,
                    characteristicUuid = channel.characteristicUuid,
                    value = frame.toByteArray(Charsets.US_ASCII),
                    withResponse = channel.writeWithResponse
                )
                if (!ok) continue
                val reply = waitForS1cReply(baseline, prefixes, timeout)
                if (reply != null) return reply
            }
            return null
        }

        for (channel in candidates) {
            if (gattState.value != "services-ready") return

            // First ask for pairing state, exactly as documented by AirGlu S1C.
            val pairing = sendVariant(
                channel,
                S1cCommands.PAIRING,
                listOf("BTPR="),
                if (fast) 1200L else 1800L
            )
            if (pairing != null) {
                var pairingState = reduceS1cStatus(
                    S1cStatus(state = "pairing-api", channel = channel),
                    pairing
                )
                _s1cStatus.value = pairingState
                val selectedAddress = _selected.value?.address
                val finalConnectAllowed = PairingRuntimeGate.isPairingMutationAllowed(selectedAddress)
                if (!finalConnectAllowed && !preCodeBootstrap) {
                    _commandStatus.value = "AirGlu pairing API detected: ${pairing.removePrefix("#")}. Final BTPR=2 remains blocked until the visible 9-digit Pairing ID is accepted."
                    return
                }

                suspend fun pollUntilWaiting(): String? {
                    var info = pairingState.pairingInfo.orEmpty()
                    if (info.startsWith("0")) {
                        _commandStatus.value = "AirGlu BTPR is idle. Starting the documented same-AG2 search (BTPR=1)..."
                        val started = gatt.write(
                            serviceUuid = channel.serviceUuid,
                            characteristicUuid = channel.characteristicUuid,
                            value = S1cCommands.PAIR_START.toByteArray(Charsets.US_ASCII),
                            withResponse = channel.writeWithResponse
                        )
                        if (!started) return null
                        delay(220L)
                    }
                    repeat(if (fast) 12 else 16) {
                        val reply = sendVariant(channel, S1cCommands.PAIRING, listOf("BTPR="), if (fast) 700L else 1000L)
                        if (reply != null) {
                            pairingState = reduceS1cStatus(pairingState, reply)
                            _s1cStatus.value = pairingState
                            info = pairingState.pairingInfo.orEmpty()
                            _commandStatus.value = "Shogun/AirGlu application pairing: BTPR=$info"
                            if (info.startsWith("2")) return reply
                        }
                        delay(if (fast) 260L else 400L)
                    }
                    return null
                }

                val waiting = if (pairingState.pairingInfo.orEmpty().startsWith("2")) pairing else pollUntilWaiting()
                if (waiting != null) {
                    if (!finalConnectAllowed) {
                        _s1cStatus.value = pairingState.copy(state = "pairing-waiting-for-code")
                        _commandStatus.value =
                            "AirGlu pairing search reached BTPR=${pairingState.pairingInfo}. Waiting for the ATOMOS 9-digit Pairing ID from the active notify/read path. BTPR=2 is intentionally NOT sent yet."
                        return
                    }
                    _commandStatus.value = "AirGlu reports the locked phone waiting to pair. Sending documented BTPR=2 to complete the accepted application pairing..."
                    val connectSent = gatt.write(
                        serviceUuid = channel.serviceUuid,
                        characteristicUuid = channel.characteristicUuid,
                        value = S1cCommands.PAIR_CONNECT.toByteArray(Charsets.US_ASCII),
                        withResponse = channel.writeWithResponse
                    )
                    if (connectSent) {
                        _s1cStatus.value = pairingState.copy(state = "pair-connect-sent")
                        delay(650L)
                        val afterPair = sendVariant(channel, S1cCommands.PAIRING, listOf("BTPR="), 1800L)
                        if (afterPair != null) {
                            pairingState = reduceS1cStatus(pairingState, afterPair)
                            _s1cStatus.value = pairingState
                        }
                        val model = sendVariant(channel, S1cCommands.PROBE, listOf("GCMD="), 2000L)
                        val transport = if (model == null) sendVariant(channel, S1cCommands.TRANSPORT, listOf("GCCT="), 1800L) else null
                        if (model != null || transport != null) {
                            var ready = S1cStatus(state = "ready", channel = channel)
                            if (model != null) ready = reduceS1cStatus(ready, model)
                            if (transport != null) ready = reduceS1cStatus(ready, transport)
                            _s1cStatus.value = ready.copy(state = "ready")
                            _commandStatus.value = "SHOGUN/AirGlu application pairing and S1C control are confirmed. On SHOGUN, tap the white Remote icon if it is visible; the app Remote controls are now enabled."
                        } else {
                            _s1cStatus.value = pairingState.copy(state = "pair-connect-sent")
                            _commandStatus.value = "BTPR=2 was sent on the documented AirGlu channel. Keep SHOGUN on the pairing screen for a few seconds; if the white Remote icon appears, tap it, then retry control discovery."
                        }
                    } else {
                        _commandStatus.value = "AirGlu waiting state was found, but BTPR=2 could not be written on the confirmed channel."
                    }
                    return
                }

                _commandStatus.value = "AirGlu pairing API is live, but BTPR did not reach the waiting-device state on this Pair window. Keep SHOGUN Sync Config > Bluetooth/AirGlu BT > Pair open and retry the same-AG2 handshake."
                return
            }

            // The public S1C API also permits LF or CRLF framing. Some integrations expose
            // transport/status even when BTPR is not available to this client. Confirm the
            // channel with harmless queries and unlock REC/STOP/PLAY/PAUSE only if a real
            // recorder-control reply is received.
            val model = sendVariant(
                channel,
                S1cCommands.PROBE,
                listOf("GCMD="),
                if (fast) 850L else 1400L
            )
            if (model != null) {
                var next = reduceS1cStatus(S1cStatus(state = "ready", channel = channel), model)
                val transport = sendVariant(channel, S1cCommands.TRANSPORT, listOf("GCCT="), if (fast) 850L else 1300L)
                if (transport != null) next = reduceS1cStatus(next, transport)
                _s1cStatus.value = next.copy(state = "ready")
                _commandStatus.value = "Documented AirGlu S1C recorder-control channel confirmed. Transport controls are enabled."
                return
            }

            val transport = sendVariant(
                channel,
                S1cCommands.TRANSPORT,
                listOf("GCCT="),
                if (fast) 850L else 1400L
            )
            if (transport != null) {
                _s1cStatus.value = reduceS1cStatus(
                    S1cStatus(state = "ready", channel = channel),
                    transport
                ).copy(state = "ready")
                _commandStatus.value = "Documented AirGlu transport control confirmed. REC/STOP/PLAY/PAUSE are enabled."
                return
            }

            // Finally identify a generic S1C transport without assuming recorder control.
            val revision = sendVariant(
                channel,
                "#STVS?\n",
                listOf("STVS="),
                if (fast) 650L else 1200L
            )
            if (revision != null) {
                val selectedAddress = _selected.value?.address
                val finalConnectAllowed = PairingRuntimeGate.isPairingMutationAllowed(selectedAddress)
                if (!finalConnectAllowed && !preCodeBootstrap) {
                    _s1cStatus.value = S1cStatus(
                        state = "s1c-awaiting-code-accept",
                        channel = channel,
                        lastReply = revision
                    )
                    _commandStatus.value =
                        "S1C channel confirmed. Pairing-ID bootstrap has not been armed for this session, so final pairing remains locked."
                    return
                }

                // The public AirGlu2 S1C API documents the actual pairing sequence:
                // BTPR=1 starts searching, BTPR? reports state, and BTPR=2 connects
                // the waiting device. Earlier builds only queried BTPR? and therefore
                // never initiated the sequence on an otherwise valid S1C channel.
                _s1cStatus.value = S1cStatus(
                    state = "s1c-pairing-start",
                    channel = channel,
                    lastReply = revision
                )
                _commandStatus.value = "S1C channel confirmed. Starting documented AirGlu pairing search (BTPR=1)..."

                val startOk = gatt.write(
                    serviceUuid = channel.serviceUuid,
                    characteristicUuid = channel.characteristicUuid,
                    value = S1cCommands.PAIR_START.toByteArray(Charsets.US_ASCII),
                    withResponse = channel.writeWithResponse
                )
                if (!startOk) {
                    _s1cStatus.value = _s1cStatus.value.copy(
                        state = "s1c-pairing-wait",
                        error = "S1C channel replied, but BTPR=1 could not be written"
                    )
                    _commandStatus.value = "S1C channel found, but pairing start write failed."
                    return
                }

                delay(250L)
                var lastPairReply: String? = null
                repeat(if (fast) 8 else 12) {
                    if (gattState.value != "services-ready") return@repeat
                    val poll = sendVariant(
                        channel,
                        S1cCommands.PAIRING,
                        listOf("BTPR="),
                        if (fast) 650L else 900L
                    )
                    if (poll != null) {
                        lastPairReply = poll
                        val next = reduceS1cStatus(
                            S1cStatus(state = "pairing-api", channel = channel),
                            poll
                        )
                        _s1cStatus.value = next
                        val info = next.pairingInfo.orEmpty()
                        if (info.startsWith("2")) {
                            _commandStatus.value = if (finalConnectAllowed) {
                                "AirGlu reports a device waiting to pair: BTPR=$info. Final pairing is authorized for the accepted 9-digit code."
                            } else {
                                "AirGlu reports a device waiting to pair: BTPR=$info. Keep both pairing screens open while the 9-digit Pairing ID is captured; BTPR=2 is still locked."
                            }
                            return
                        }
                        _commandStatus.value = "AirGlu pairing search active: BTPR=$info"
                    }
                    delay(if (fast) 300L else 450L)
                }

                _s1cStatus.value = _s1cStatus.value.copy(
                    state = "s1c-pairing-wait",
                    lastReply = lastPairReply ?: revision,
                    error = "S1C channel is valid, but no device reached BTPR=2 during this Pair window"
                )
                _commandStatus.value = "S1C pairing search started, but no device reached waiting-to-pair state. Keep recorder Pair mode open and retry pairing discovery."
                return
            }
        }

        _s1cStatus.value = _s1cStatus.value.copy(
            state = "pairing-api-not-found",
            error = "No BTPR reply from the discovered vendor write/reply services"
        )
        _commandStatus.value = "BLE/GATT is working, but the proprietary AtomRemote Pairing-ID channel was not exposed and no AirGlu BTPR reply was detected."
    }

    /**
     * Agent7 20.03.5.4.6.
     * Playback -> Record first press must ONLY return the recorder to Record mode.
     * This calls the already-existing frozen GattClient command. No GattClient code
     * or packet construction is changed.
     */
    fun returnToRecordModeOnlyFromUi(): Boolean {
        val remote = gatt.tcsRemoteState.value
        if (!_pairingCodeAccepted.value || !gatt.tcsPairingState.value.acceptWritten) {
            _commandStatus.value = "Official TCS pairing must be accepted before remote control."
            return false
        }
        if (!remote.available) {
            _commandStatus.value = "Recorder transport is not ready."
            return false
        }

        val ok = gatt.sendOfficialRemoteCommand("prepare-record-mode")
        _commandStatus.value = if (ok) {
            "Returning recorder to Record mode..."
        } else {
            "Return to Record mode could not be queued."
        }
        return ok
    }

    /**
     * UI recovery path for a stuck/failed Remote transaction.
     *
     * Uses only the existing public disconnect/connectTrusted API from the frozen
     * GattClient. Trust/pairing data is preserved. No unpair is performed.
     */
    fun beginRemoteTransportUiAction(
        action: String,
        label: String,
        expectedLastCommand: String
    ): Long? {
        return remoteTransportUiController.begin(
            action = action,
            label = label,
            expectedLastCommand = expectedLastCommand,
            currentRemotePhase = gatt.tcsRemoteState.value.phase
        )
    }

    fun rejectRemoteTransportUiAction(message: String) {
        remoteTransportUiController.reject(message)
    }

    fun observeRemoteTransportUiState() {
        val remote = gatt.tcsRemoteState.value
        remoteTransportUiController.observe(
            remotePhase = remote.phase,
            remoteLastCommand = remote.lastCommand,
            remoteError = remote.lastError,
            gattState = gatt.state.value
        )
    }

    fun timeoutRemoteTransportUiAction(generation: Long) {
        if (remoteTransportUiController.timeout(generation)) {
            _commandStatus.value = remoteTransportUiController.state.value.error
        }
    }

    fun recoverRemoteSessionFromUi() {
        val ad = _selected.value
        if (ad == null) {
            _commandStatus.value = "No recorder selected for recovery."
            return
        }

        val trusted = loadTrustedTcs(ad.address)
        if (trusted == null) {
            _commandStatus.value = "Saved trusted pairing is unavailable. Pair once again."
            return
        }

        remoteTransportUiController.startRecovery()
        _commandStatus.value = "Recovering Remote connection..."

        viewModelScope.launch {
            runCatching {
                gatt.disconnect()
                delay(450)
                val started = gatt.connectTrusted(
                    ad.device,
                    trusted.code,
                    trusted.ccid,
                    trusted.bcid
                )
                if (!started) {
                    throw IllegalStateException("trusted reconnect could not be started")
                }

                val ready = withTimeoutOrNull(10_000L) {
                    while (true) {
                        val state = gatt.tcsRemoteState.value
                        val gattState = gatt.state.value
                        if (
                            state.available &&
                            state.phase in setOf("linked", "packet-ready", "transport-ready") &&
                            !gattState.startsWith("error:") &&
                            gattState != "disconnected"
                        ) {
                            break
                        }
                        delay(120)
                    }
                    true
                } == true

                if (!ready) {
                    throw IllegalStateException("Remote recovery timed out")
                }

                remoteTransportUiController.recovered()
                _commandStatus.value = "Remote recovered. Controls are ready."
            }.onFailure {
                _commandStatus.value = "Remote recovery failed: ${it.message}"
            }
        }
    }

    fun reconnectSaved() {
        val s = _saved.value ?: return
        runCatching {
            val manager = getApplication<Application>().getSystemService(BluetoothManager::class.java)
            val device = manager.adapter.getRemoteDevice(s.address)
            val ad = BleAdvertisement(device, s.name, s.address, 0, emptyList(), null, null, System.currentTimeMillis())
            trustedReconnectAddress = s.address
            _selected.value = ad
            val trusted = loadTrustedTcs(s.address)
            if (trusted != null) {
                _pairingCodeAccepted.value = true
                selectedSessionPairingId = trusted.code
                gatt.connectTrusted(device, trusted.code, trusted.ccid, trusted.bcid)
                _commandStatus.value = "Verifying saved recorder identity and reconnecting Remote..."
            } else {
                _pairingCodeAccepted.value = false
                gatt.connect(device)
                _commandStatus.value = "Saved recorder found, but no validated TCS pairing slot is stored. Pair once to enable automatic reconnect."
            }
            _screen.value = Screen.PAIR
        }.onFailure {
            _commandStatus.value = "Reconnect failed: ${it.message}"
        }
    }

    fun trustCurrentDevice() {
        val ad = _selected.value ?: return
        val remoteReady = gatt.tcsRemoteState.value.available || _s1cStatus.value.ready || (_protocol.value?.canTransmit == true)
        if (!remoteReady) {
            _commandStatus.value = "BLE is linked, but ATOMOS control is not ready. Wait for the AirGlu/S1C probe or open GATT Inspector."
            return
        }
        val matched = AtomosProfiles.match(ad.name)
        val isPrimaryShogun = ad.name?.contains("shogun", true) == true || ad.name?.startsWith("AG", true) == true
        val savedDevice = SavedAtomosDevice(
            address = ad.address,
            name = ad.name ?: matched?.displayName ?: "ATOMOS Recorder",
            profileId = matched?.id ?: "generic",
            did = if (isPrimaryShogun) KnownPrimaryDevice.DID else null,
            aid = if (isPrimaryShogun) KnownPrimaryDevice.AID else null,
            bid = if (isPrimaryShogun) KnownPrimaryDevice.BID else null,
            atomOs = if (isPrimaryShogun) KnownPrimaryDevice.ATOMOS_VERSION else null,
            airGlu = if (isPrimaryShogun) KnownPrimaryDevice.AIRGLU else null,
            preferred = true
        )
        identityStore.save(savedDevice)
        refreshSaved()
        _screen.value = Screen.REMOTE
    }

    fun removeSaved(address: String) {
        identityStore.remove(address)
        refreshSaved()
    }

    fun clearSaved() {
        identityStore.clear()
        refreshSaved()
    }

    private fun refreshSaved() {
        _saved.value = identityStore.load()
        _savedAll.value = identityStore.loadAll()
    }

    fun loadProtocolJson(json: String): Result<Unit> = runCatching {
        profileStore.save(json)
        _protocol.value = profileStore.load()
    }

    fun protocolTemplate(): String = profileStore.template()

    fun sendRecordStartConfirmedModeFromUi(): Boolean {
        val remote = gatt.tcsRemoteState.value
        if (!_pairingCodeAccepted.value || !gatt.tcsPairingState.value.acceptWritten) {
            _commandStatus.value = "Official TCS pairing must be accepted before remote control."
            return false
        }
        if (!remote.available) {
            _commandStatus.value = "Recorder transport is not ready."
            return false
        }

        val ok = gatt.sendOfficialRemoteCommand("record-start")
        _commandStatus.value = if (ok) {
            "Record Start command queued"
        } else {
            "Record Start command could not be queued"
        }
        Log.d(
            "AtomRemoteSession",
            "TCS20.03.6.3 DIRECT RECORD START confirmedMode=RECORD queued=$ok"
        )
        return ok
    }

    /**
     * 20.03.5.4.6 transport wrapper for UI acknowledgement only.
     * Command semantics are identical to the existing send() transport branch.
     */
    fun sendRemoteTransportFromUi(command: String): Boolean {
        if (command !in setOf("record-start", "record-stop", "play", "pause")) return false

        val remote = gatt.tcsRemoteState.value
        if (!_pairingCodeAccepted.value || !gatt.tcsPairingState.value.acceptWritten) {
            _commandStatus.value = "Official TCS pairing must be accepted before remote control."
            return false
        }
        if (!remote.available) {
            _commandStatus.value = "Recorder transport is still opening. Wait for PacketLink before using Remote controls."
            return false
        }

        val label = when (command) {
            "record-start" -> "Record Start"
            "record-stop" -> "Record Stop"
            "play" -> "Play"
            "pause" -> "Pause"
            else -> command
        }

        val ok = gatt.sendOfficialRemoteCommandMacParity(command)
        _commandStatus.value = if (ok) {
            "$label command queued"
        } else {
            "$label command could not be queued"
        }
        return ok
    }

    fun send(command: String) {
        // 18.6 transport lock: Record / Stop / Play / Pause are official TCS RemoteApi only.
        // Never fall back to the legacy guessed S1C transport for these controls.
        if (command in setOf("record-start", "record-stop", "play", "pause")) {
            val remote = gatt.tcsRemoteState.value
            if (!_pairingCodeAccepted.value || !gatt.tcsPairingState.value.acceptWritten) {
                _commandStatus.value = "Official TCS pairing must be accepted before remote control."
                return
            }
            if (!remote.available) {
                _commandStatus.value = "Recorder transport is still opening. Wait for PacketLink before using Remote controls."
                return
            }
            val label = when (command) {
                "record-start" -> "Record Start"
                "record-stop" -> "Record Stop"
                "play" -> "Play"
                "pause" -> "Pause"
                else -> command
            }
            val ok = gatt.sendOfficialRemoteCommandMacParity(command)
            _commandStatus.value = if (ok) {
                "$label command queued"
            } else {
                "$label command could not be queued"
            }
            return
        }

        val result = commandEngine.send(_protocol.value, command)
        _commandStatus.value = result.fold(
            onSuccess = { "Sent: $command" },
            onFailure = { it.message ?: "Command failed" }
        )
    }

    fun setMonitorAssistToggle(control: String, enabled: Boolean) {
        if (!_pairingCodeAccepted.value || !gatt.tcsPairingState.value.acceptWritten) {
            _commandStatus.value = "Official TCS pairing must be accepted before Monitor Assist control."
            return
        }
        if (!gatt.tcsRemoteState.value.available) {
            _commandStatus.value = "Recorder PacketLink is not ready for Monitor Assist control."
            return
        }

        val label = when (control) {
            "focus-assist" -> "Focus Assist"
            "zebra" -> "Zebra"
            "false-color" -> "False Color"
            "blue-only" -> "Blue Only"
            else -> {
                _commandStatus.value = "Unsupported Monitor Assist toggle: $control"
                return
            }
        }

        val queued = when (control) {
            "zebra" -> gatt.sendOfficialZebraEnabled(enabled)
            "false-color" -> gatt.sendOfficialFalseColourEnabled(enabled)
            "blue-only" -> gatt.sendOfficialBlueOnlyEnabled(enabled)
            else -> gatt.sendOfficialMonitorAssistToggle(control, enabled)
        }
        if (queued) {
            when (control) {
                // 20.05.0 Focus is recorder-confirmed only.
                // Zebra 20.05.4 is recorder-confirmed only.
                // False Colour / Blue Only 20.05.6-7 are recorder-confirmed only.
            }
            _commandStatus.value = if (control == "focus-assist") {
                "$label change sent; waiting for recorder readback"
            } else {
                "$label ${if (enabled) "On" else "Off"} command queued"
            }
        } else {
            _commandStatus.value = "$label command could not be queued. Wait for the current PacketLink transaction and retry."
        }
    }

    private fun queueLatestMonitorUiAction(
        label: String,
        action: () -> Boolean,
        acceptedText: String
    ) {
        monitorUiActionRetryJob?.cancel()
        monitorUiActionRetryJob = viewModelScope.launch {
            repeat(16) { attempt ->
                if (action()) {
                    _commandStatus.value = acceptedText
                    return@launch
                }
                if (attempt == 0) {
                    _commandStatus.value = "$label waiting for the current recorder transaction..."
                }
                kotlinx.coroutines.delay(140L)
            }
            _commandStatus.value = "$label could not be queued after waiting for PacketLink."
        }
    }

    fun refreshFocusAssistState() {
        if (!remoteFeatureReady("Focus Assist")) return
        val queued = gatt.refreshOfficialFocusAssistState()
        _commandStatus.value = if (queued) {
            "Reading Focus Assist from recorder..."
        } else {
            "Focus Assist readback is waiting for the current recorder command."
        }
    }

    fun setFocusAssistType(type: Int) {
        if (!remoteFeatureReady("Focus Type")) return
        queueLatestMonitorUiAction(
            label = "Focus Type",
            action = { gatt.sendOfficialFocusPeakType(type) },
            acceptedText = "Applying Focus Type; waiting for recorder readback"
        )
    }

    fun setFocusAssistThreshold(threshold: Int) {
        if (!remoteFeatureReady("Focus Threshold")) return
        queueLatestMonitorUiAction(
            label = "Focus Threshold",
            action = { gatt.sendOfficialFocusPeakThreshold(threshold) },
            acceptedText = "Applying Focus threshold; waiting for recorder readback"
        )
    }

    fun setFocusAssistColour(colour: Int) {
        if (!remoteFeatureReady("Focus Colour")) return
        queueLatestMonitorUiAction(
            label = "Focus Colour",
            action = { gatt.sendOfficialFocusPeakColour(colour) },
            acceptedText = "Applying Focus colour; waiting for recorder readback"
        )
    }

    fun refreshMonitorAssistEffects() {
        if (!remoteFeatureReady("Monitor Assist")) return
        val queued = gatt.refreshOfficialMonitorAssistEffects()
        _commandStatus.value = if (queued) "Reading exposure tools..." else "Monitor Assist readback is busy."
    }

    fun setZebraThreshold(threshold: Int) {
        if (!remoteFeatureReady("Zebra Threshold")) return
        val queued = gatt.sendOfficialZebraThreshold(threshold)
        _commandStatus.value = if (queued) {
            "Applying Zebra threshold; waiting for recorder readback"
        } else {
            "Zebra threshold command could not be queued."
        }
    }

    fun refreshRecorderState() {
        // Compatibility entry point for the Remote toolbar. 19.12.10 no longer
        // uses AppPolling 10/10 as the AG2 state gate.
        refreshInputCodecState()
    }

    fun refreshInputCodecState() {
        if (!remoteFeatureReady("Input / Codec data")) return
        val queued = gatt.refreshGranularInputState()
        _commandStatus.value = if (queued) {
            "Reading Input state from recorder..."
        } else {
            "Input data refresh is waiting for PacketLink READY."
        }
        Log.d("AtomRemoteSession", "TCS19.12.10 UI COLLECTOR REFRESH queued=$queued")
    }

    fun refreshExtendedInputState() {
        if (!remoteFeatureReady("Input details")) return
        val queued = gatt.refreshExtendedInputState()
        _commandStatus.value = if (queued) {
            "Reading extended Input state from recorder..."
        } else {
            "Input details refresh is waiting for PacketLink READY and an idle transport."
        }
        Log.d("AtomRemoteSession", "TCS20.03.6.10 UI EXT INPUT REFRESH queued=$queued")
    }

    fun setInputSource(value: Int, label: String) {
        if (!remoteFeatureReady("Input Source")) return
        val queued = gatt.setOfficialInputSource(value)
        _commandStatus.value = if (queued) {
            "Applying $label. Waiting for recorder readback..."
        } else {
            "Input Source is busy. Wait for synchronization or the current command to finish."
        }
        Log.d("AtomRemoteSession", "TCS20.03.7 UI SOURCE SET queued=$queued label=$label")
    }

    fun setInputHdmiStandard(value: Int, label: String) {
        if (!remoteFeatureReady("HDMI Standard")) return
        val queued = gatt.setOfficialInputHdmiStandard(value)
        _commandStatus.value = if (queued) {
            "Applying $label. Waiting for recorder readback..."
        } else {
            "HDMI Standard is busy. Wait for synchronization or the current command to finish."
        }
        Log.d("AtomRemoteSession", "TCS20.03.7 UI HDMI SET queued=$queued label=$label")
    }

    fun setInputTriggerSource(value: Int, label: String) {
        if (!remoteFeatureReady("Trigger Source")) return
        val queued = gatt.setOfficialInputTriggerSource(value)
        _commandStatus.value = if (queued) {
            "Applying $label. Waiting for recorder readback..."
        } else {
            "Trigger Source is busy. Wait for synchronization or the current command to finish."
        }
        Log.d("AtomRemoteSession", "TCS20.03.7 UI TRIGGER SOURCE SET queued=$queued label=$label")
    }

    fun setInputTriggerEnabled(enabled: Boolean) {
        if (!remoteFeatureReady("Record Trigger")) return
        val queued = gatt.setOfficialInputTriggerEnabled(enabled)
        _commandStatus.value = if (queued) {
            "Applying Record Trigger ${if (enabled) "On" else "Off"}. Waiting for recorder readback..."
        } else {
            "Record Trigger is busy. Wait for synchronization or the current command to finish."
        }
        Log.d("AtomRemoteSession", "TCS20.03.7 UI TRIGGER ENABLE SET queued=$queued enabled=$enabled")
    }

    fun setInputHdrEnabled(enabled: Boolean) {
        if (!remoteFeatureReady("Input HDR")) return
        val queued = gatt.setOfficialInputHdrEnabled(enabled)
        _commandStatus.value = if (queued) {
            "Applying HDR ${if (enabled) "On" else "Off"}. Waiting for recorder readback..."
        } else {
            "HDR is busy. Wait for the current Input command to finish."
        }
        Log.d("AtomRemoteSession", "TCS20.03.7.3 UI HDR ENABLE SET queued=$queued enabled=$enabled")
    }

    fun setInputHdrAuto(enabled: Boolean) {
        if (!remoteFeatureReady("HDR Auto")) return
        val queued = gatt.setOfficialInputHdrAuto(enabled)
        _commandStatus.value = if (queued) {
            "Applying HDR Auto ${if (enabled) "On" else "Off"}. Waiting for recorder readback..."
        } else {
            "HDR Auto is busy. Wait for the current Input command to finish."
        }
        Log.d("AtomRemoteSession", "TCS20.03.7.3 UI HDR AUTO SET queued=$queued enabled=$enabled")
    }

    fun setInputHdrCamera(value: Int, label: String) {
        if (!remoteFeatureReady("HDR Camera")) return
        val queued = gatt.setOfficialInputHdrCamera(value)
        _commandStatus.value = if (queued) {
            "Applying Camera $label. Waiting for recorder readback..."
        } else {
            "Camera setting is busy. Wait for the current Input command to finish."
        }
        Log.d("AtomRemoteSession", "TCS20.03.7.4 UI HDR CAMERA SET queued=$queued label=$label")
    }

    fun setInputHdrGamma(value: Int, label: String) {
        if (!remoteFeatureReady("HDR Gamma")) return
        val queued = gatt.setOfficialInputHdrGamma(value)
        _commandStatus.value = if (queued) {
            "Applying Gamma $label. Waiting for recorder readback..."
        } else {
            "Gamma setting is busy. Wait for the current Input command to finish."
        }
        Log.d("AtomRemoteSession", "TCS20.03.7.5 UI HDR GAMMA SET queued=$queued label=$label")
    }

    fun setInputHdrGamut(value: Int, label: String) {
        if (!remoteFeatureReady("HDR Gamut")) return
        val queued = gatt.setOfficialInputHdrGamut(value)
        _commandStatus.value = if (queued) {
            "Applying Gamut $label. Waiting for recorder readback..."
        } else {
            "Gamut setting is busy. Wait for the current Input command to finish."
        }
        Log.d("AtomRemoteSession", "TCS20.03.7.6 UI HDR GAMUT SET queued=$queued label=$label")
    }

    fun setInputRange(value: Int, label: String) {
        if (!remoteFeatureReady("Input Range")) return
        val queued = gatt.setOfficialInputRange(value)
        _commandStatus.value = if (queued) {
            "Applying Input Range $label. Waiting for recorder readback..."
        } else {
            "Input Range is busy. Wait for the current Input command to finish."
        }
        Log.d("AtomRemoteSession", "TCS20.03.7.8 UI INPUT RANGE SET queued=$queued label=$label")
    }

    fun refreshZoomState() {
        if (!remoteFeatureReady("Zoom")) return
        val queued = gatt.refreshOfficialZoomState()
        _commandStatus.value = if (queued) {
            "Reading Zoom state from recorder..."
        } else {
            "Zoom readback is waiting for the current recorder command."
        }
    }

    fun refreshZoomViewport() {
        if (!remoteFeatureReady("Zoom Analog")) return
        val queued = gatt.refreshOfficialZoomViewport()
        _commandStatus.value = if (queued) {
            "Reading Zoom Analog viewport from recorder..."
        } else {
            "Zoom Analog viewport is waiting for the current recorder command."
        }
        Log.d("AtomRemoteSession", "TCS20.13.4R10 ANALOG DIRECT VIEWPORT REFRESH queued=$queued")
    }

    private suspend fun queueMonitorSnapshotStep(
        label: String,
        attempts: Int = 12,
        action: () -> Boolean
    ): Boolean {
        // 20.11.4: Compose/manual refreshes can request the same GET while a monitor
        // entry snapshot is already progressing. Suppress only a very recent
        // duplicate of the same logical step; other sections still serialize normally.
        val now = System.currentTimeMillis()
        val lastQueuedAt = monitorSnapshotStepLastQueuedAt[label]
        if (lastQueuedAt != null && now - lastQueuedAt < 900L) {
            Log.d("AtomRemoteSession", "TCS20.11.4 DUPLICATE GET SUPPRESSED label=$label ageMs=${now - lastQueuedAt}")
            return true
        }
        repeat(attempts) { attempt ->
            if (action()) {
                monitorSnapshotStepLastQueuedAt[label] = System.currentTimeMillis()
                Log.d("AtomRemoteSession", "TCS20.12.5 MONITOR SECTION ACCEPT label=$label attempt=${attempt + 1}")
                var stableIdle = 0
                repeat(100) { waitTurn ->
                    if (gatt.monitorSnapshotTransportIdle()) {
                        stableIdle += 1
                        if (stableIdle >= 2) {
                            Log.d("AtomRemoteSession", "TCS20.12.5 MONITOR SECTION COMPLETE label=$label waitTurn=$waitTurn")
                            return true
                        }
                    } else {
                        stableIdle = 0
                    }
                    delay(75L)
                }
                Log.w("AtomRemoteSession", "TCS20.12.5 MONITOR SECTION TIMEOUT label=$label")
                return true
            }
            delay(120L + (attempt * 20L))
        }
        Log.w("AtomRemoteSession", "TCS20.11.3 MONITOR QUEUE SKIP label=$label reason=busy-after-retries")
        return false
    }


    fun setZoomAnalogSession(active: Boolean) {
        val queued = gatt.setZoomAnalogSessionActive(active)
        _commandStatus.value = if (active) {
            if (queued) "Zoom Analog opened; synchronizing viewport once"
            else "Zoom Analog opened; viewport sync is waiting for PacketLink"
        } else {
            "Zoom Analog closed"
        }
    }

    fun invalidateMonitorSessionCache(reason: String) {
        monitorSnapshotJob?.cancel()
        monitorSnapshotJob = null
        monitorSnapshotAddress = null
        monitorSnapshotStepLastQueuedAt.clear()
        monitorSnapshotGeneration += 1
        Log.d("AtomRemoteSession", "TCS20.11.5 MONITOR CACHE INVALIDATED reason=$reason generation=$monitorSnapshotGeneration")
    }

    fun ensureMonitorSessionSnapshot(reason: String = "monitor-open") {
        val ad = _selected.value ?: return
        if (!gatt.tcsRemoteState.value.available) return

        // 20.11.0: entering the Monitor tab must refresh the complete recorder
        // view, not only Monitor Mode + Zoom. A same-device cache can satisfy
        // background/panel calls, but an explicit monitor-tab entry forces a
        // recorder-backed snapshot so stale values do not survive navigation.
        val forceMonitorEntry = reason == "monitor-tab" || reason.startsWith("monitor-tab-")
        val sameAddress = monitorSnapshotAddress?.equals(ad.address, ignoreCase = true) == true
        if (monitorSnapshotAddress != null && !sameAddress) {
            monitorSnapshotJob?.cancel()
            monitorSnapshotJob = null
            monitorSnapshotStepLastQueuedAt.clear()
            monitorSnapshotGeneration += 1
            Log.d("AtomRemoteSession", "TCS20.11.5 MONITOR CACHE DEVICE CHANGE old=${monitorSnapshotAddress} new=${ad.address} generation=$monitorSnapshotGeneration")
        }
        val modeReady = gatt.monitorCoreSnapshot.value.ready
        val zoomReady = gatt.monitorZoomCore.value.currentLevel != null || gatt.zoomCapabilitiesKnown.value

        if (!forceMonitorEntry && sameAddress && modeReady && zoomReady) {
            Log.d("AtomRemoteSession", "TCS20.11.0 MONITOR SESSION CACHE HIT address=${ad.address} reason=$reason")
            return
        }

        if (monitorSnapshotJob?.isActive == true) {
            Log.d("AtomRemoteSession", "TCS20.11.3 MONITOR SESSION already-running address=${ad.address} reason=$reason")
            return
        }

        monitorSnapshotAddress = ad.address
        monitorSnapshotGeneration += 1
        val snapshotGeneration = monitorSnapshotGeneration
        monitorSnapshotJob = viewModelScope.launch {
            Log.d("AtomRemoteSession", "TCS20.11.0 MONITOR SNAPSHOT START address=${ad.address} reason=$reason generation=$snapshotGeneration")

            val results = linkedMapOf<String, Boolean>()
            results["mode"] = queueMonitorSnapshotStep("MonitorMode") {
                gatt.refreshOfficialMonitorMode(source = "monitor-session-$reason", expectedMode = null)
            }
            results["monitorPage"] = queueMonitorSnapshotStep("MonitorPage") {
                gatt.refreshOfficialMonitorPageSnapshot()
            }
            results["zoom"] = queueMonitorSnapshotStep("Zoom") { gatt.refreshOfficialZoomState() }
            results["focus"] = queueMonitorSnapshotStep("Focus") { gatt.refreshOfficialFocusAssistState() }
            results["effects"] = queueMonitorSnapshotStep("MonitorAssist") { gatt.refreshOfficialMonitorAssistEffects() }
            results["scopes"] = queueMonitorSnapshotStep("Scopes") { gatt.refreshOfficialScopeCore() }
            results["guides"] = queueMonitorSnapshotStep("Guides") { gatt.refreshOfficialGuidesState() }
            results["safeArea"] = queueMonitorSnapshotStep("SafeArea") { gatt.refreshOfficialSafeAreaState() }
            results["anamorphic"] = queueMonitorSnapshotStep("Anamorphic") { gatt.refreshOfficialAnamorphicMode() }
            results["lut"] = queueMonitorSnapshotStep("LUT") { gatt.refreshOfficialLutState() }
            results["display"] = queueMonitorSnapshotStep("Display") { gatt.refreshOfficialDisplayState() }

            Log.d(
                "AtomRemoteSession",
                "TCS20.11.0 MONITOR SNAPSHOT COMPLETE address=${ad.address} generation=$snapshotGeneration " +
                    results.entries.joinToString(" ") { "${it.key}=${it.value}" }
            )
        }
    }

    fun setMonitorZoomLevel(level: Int) {
        if (level !in 0..2) {
            _commandStatus.value = "Choose Off, Zoom 1:1 or Zoom 2:1."
            return
        }
        if (!remoteFeatureReady("Zoom")) return
        val queued = gatt.sendOfficialMonitorZoomLevel(level)
        val label = when (level) { 0 -> "Off"; 1 -> "1:1"; else -> "2:1" }
        _commandStatus.value = if (queued) {
            "Applying Zoom $label; waiting for recorder readback"
        } else {
            gatt.tcsRemoteState.value.lastError
                ?: "Zoom command could not be queued. Wait for the current recorder command and retry."
        }
    }

    fun panZoomNormalized(xFraction: Float, yFraction: Float) {
        if (!remoteFeatureReady("Zoom Pan")) return
        val rawBox = gatt.zoomBoxState.value
        val logicalZoom = gatt.monitorZoomCore.value.currentLevel ?: 0
        val box = if (rawBox != null && rawBox.valid && !rawBox.pannable && logicalZoom == 2) {
            rawBox.copy(
                viewportWidth = (rawBox.frameWidth / 2).coerceAtLeast(1),
                viewportHeight = (rawBox.frameHeight / 2).coerceAtLeast(1)
            )
        } else rawBox
        if (box == null || !box.valid || !box.pannable) {
            _commandStatus.value = "Zoom Analog viewport is not available yet."
            gatt.refreshOfficialZoomViewport()
            return
        }
        val x = xFraction.coerceIn(0f, 1f)
        val y = yFraction.coerceIn(0f, 1f)
        val left = (x * box.maxLeft.toFloat() + 0.5f).toInt().coerceIn(0, box.maxLeft)
        val top = (y * box.maxTop.toFloat() + 0.5f).toInt().coerceIn(0, box.maxTop)
        val queued = gatt.sendOfficialZoomPan(left, top)
        _commandStatus.value = if (queued) {
            "Moving Zoom viewport..."
        } else {
            "Zoom viewport move is waiting for the current recorder command."
        }
    }

    fun setScopeEnabled(enabled: Boolean) {
        if (!remoteFeatureReady("Scopes")) return
        val queued = gatt.sendOfficialScopeEnabled(enabled)
        _commandStatus.value = if (queued) {
            "Applying Waveform; waiting for recorder readback"
        } else {
            "Waveform command could not be queued."
        }
    }

    fun refreshScopeCore() {
        if (!remoteFeatureReady("Scopes")) return
        val queued = gatt.refreshOfficialScopeCore()
        if (queued) {
            _commandStatus.value = "Reading scope state from recorder..."
        }
    }

    fun setScopeMode(mode: String) {
        if (!remoteFeatureReady("Scopes")) return
        val label = when (mode) {
            "luma-parade" -> "Luma Parade"
            "rgb-parade" -> "RGB Parade"
            "vectorscope" -> "Vectorscope"
            "vectorscope-zoom" -> "Vectorscope Zoom"
            else -> {
                _commandStatus.value = "Unsupported scope mode: $mode"
                return
            }
        }
        queueLatestMonitorUiAction(
            label = label,
            action = { gatt.sendOfficialScopeMode(mode) },
            acceptedText = "Applying $label; waiting for recorder readback"
        )
    }

    fun setScopeSize(size: String) {
        if (!remoteFeatureReady("Scopes")) return
        val label = when (size) {
            "quarter" -> "1/4"
            "third" -> "1/3"
            "full" -> "Full"
            else -> {
                _commandStatus.value = "Unsupported scope size: $size"
                return
            }
        }
        queueLatestMonitorUiAction(
            label = "Scope Size $label",
            action = { gatt.sendOfficialScopeSize(size) },
            acceptedText = "Applying scope size $label; waiting for recorder readback"
        )
    }

    fun setScopeOpacity(value: Int) {
        if (!remoteFeatureReady("Scope Opacity")) return
        queueLatestMonitorUiAction(
            label = "Scope Opacity",
            action = { gatt.sendOfficialScopeOpacity(value) },
            acceptedText = "Applying Waveform opacity; waiting for recorder readback"
        )
    }

    fun setScopeBrightness(value: Int) {
        if (!remoteFeatureReady("Scope Brightness")) return
        queueLatestMonitorUiAction(
            label = "Scope Brightness",
            action = { gatt.sendOfficialScopeBrightness(value) },
            acceptedText = "Applying Waveform brightness; waiting for recorder readback"
        )
    }

    fun refreshGuidesState() {
        if (!remoteFeatureReady("Grid & Guides")) return
        val queued = gatt.refreshOfficialGuidesState()
        _commandStatus.value = if (queued) {
            "Reading Grid & Guides from recorder..."
        } else {
            "Guide readback is waiting for the current recorder transaction."
        }
    }

    fun setFrameGuideEnabled(enabled: Boolean) {
        if (!remoteFeatureReady("Frame Guide")) return
        queueLatestMonitorUiAction(
            label = "Frame Guide",
            action = { gatt.sendOfficialFrameGuideEnabled(enabled) },
            acceptedText = "Applying Frame Guide; waiting for recorder state"
        )
    }

    fun setFrameGuideFormat(format: Int) {
        if (!remoteFeatureReady("Frame Guide Format")) return
        queueLatestMonitorUiAction(
            label = "Frame Guide Format",
            action = { gatt.sendOfficialFrameGuideFormat(format) },
            acceptedText = "Applying Frame Guide format; waiting for recorder readback"
        )
    }

    fun setFrameGridEnabled(enabled: Boolean) {
        if (!remoteFeatureReady("9-Grid")) return
        queueLatestMonitorUiAction(
            label = "9-Grid",
            action = { gatt.sendOfficialFrameGridEnabled(enabled) },
            acceptedText = "Applying 9-Grid; waiting for recorder readback"
        )
    }

    fun refreshSafeAreaState() {
        if (!remoteFeatureReady("Safe Area")) return
        val queued = gatt.refreshOfficialSafeAreaState()
        if (queued) {
            _commandStatus.value = "Reading Safe Area state from recorder..."
        }
    }

    fun cycleSafeAreaMode() {
        if (!remoteFeatureReady("Safe Area")) return
        val current = _safeAreaMode.value
        val target = when (current) { 0 -> 1; 1 -> 2; else -> 0 }
        setSafeAreaMode(target)
    }

    fun setSafeAreaMode(mode: Int) {
        if (mode !in 0..2) {
            _commandStatus.value = "Safe Area supports Off, Action and Title."
            return
        }
        if (!remoteFeatureReady("Safe Area")) return
        val label = when (mode) { 0 -> "Off"; 1 -> "Action"; else -> "Title" }
        queueLatestMonitorUiAction(
            label = "Safe Area $label",
            action = { gatt.sendOfficialSafeAreaMode(mode) },
            acceptedText = "Applying Safe Area $label; waiting for recorder readback"
        )
    }

    fun refreshInputFormatSummary() {
        refreshInputCodecState()
    }

    fun refreshAnamorphicMode() {
        if (!remoteFeatureReady("Anamorphic De-squeeze")) return
        val queued = gatt.refreshOfficialAnamorphicMode()
        if (queued) _commandStatus.value = "Reading Anamorphic mode from recorder..."
    }

    fun setAnamorphicMode(mode: Int) {
        if (mode !in 0..9) return
        if (!remoteFeatureReady("Anamorphic De-squeeze")) return
        queueLatestMonitorUiAction(
            label = "Anamorphic ${MonitorAnamorphicState.label(mode)}",
            action = { gatt.sendOfficialAnamorphicMode(mode) },
            acceptedText = "Applying Anamorphic ${MonitorAnamorphicState.label(mode)}; waiting for recorder readback"
        )
    }

    fun refreshDisplayState() {
        if (!remoteFeatureReady("Display")) return
        val queued = gatt.refreshOfficialDisplayState()
        _commandStatus.value = if (queued) "Reading Display state from recorder..." else "Display readback is busy."
    }

    fun setDisplayBacklight(percent: Int) = queueLatestMonitorUiAction("Display Backlight", { gatt.setOfficialDisplayBacklight(percent) }, "Backlight requested; waiting for recorder readback")
    fun setDisplayFlip(enabled: Boolean) = queueLatestMonitorUiAction("Display Flip", { gatt.setOfficialDisplayFlip(enabled) }, "Flip requested; waiting for recorder readback")
    fun setDisplayTally(enabled: Boolean) = queueLatestMonitorUiAction("Display Tally", { gatt.setOfficialDisplayTally(enabled) }, "Tally requested; waiting for recorder readback")
    fun setDisplayYLift(value: Int) = queueLatestMonitorUiAction("Display Y Lift", { gatt.setOfficialDisplayYLift(value) }, "Y Lift requested; waiting for recorder readback")
    fun setDisplayYGamma(hundredths: Int) = queueLatestMonitorUiAction("Display Y Gamma", { gatt.setOfficialDisplayYGamma(hundredths) }, "Y Gamma requested; waiting for recorder readback")
    fun setDisplayYGain(hundredths: Int) = queueLatestMonitorUiAction("Display Y Gain", { gatt.setOfficialDisplayYGain(hundredths) }, "Y Gain requested; waiting for recorder readback")
    fun resetDisplayPicture() = queueLatestMonitorUiAction("Display Reset", { gatt.resetOfficialDisplayPicture() }, "Picture reset requested; waiting for recorder readback")

    fun setLutModeEnabled(enabled: Boolean) {
        val target = if (enabled) 5 else 1
        setMonitorDisplayMode(target)
        if (enabled) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(700L)
                refreshLutState()
                kotlinx.coroutines.delay(900L)
                if (monitorLutState.value.currentSlot == null && !monitorLutState.value.finalReady) {
                    refreshLutState()
                }
            }
        }
    }

    fun refreshLutState() {
        val queued = gatt.refreshOfficialLutState()
        if (queued) {
            _commandStatus.value = "Reading LUT state from recorder..."
        } else {
            _commandStatus.value = gatt.tcsRemoteState.value.lastError
                ?: "LUT state read is waiting for the current recorder transaction."
        }
    }

    fun setLutSlot(slot: Int) {
        if (slot !in 1..8) return
        if (!remoteFeatureReady("LUT Slot")) return
        queueLatestMonitorUiAction(
            label = "LUT Slot $slot",
            action = { gatt.sendOfficialLutSlot(slot) },
            acceptedText = "Applying LUT Slot $slot; waiting for recorder readback"
        )
    }

    fun setLutPreview(enabled: Boolean) {
        if (!remoteFeatureReady("LUT Preview")) return
        queueLatestMonitorUiAction(
            label = "LUT Preview ${if (enabled) "On" else "Off"}",
            action = { gatt.sendOfficialLutPreview(enabled) },
            acceptedText = "Applying LUT Preview ${if (enabled) "On" else "Off"}; waiting for recorder readback"
        )
    }

    fun setLutDuringRecord(enabled: Boolean) {
        if (!remoteFeatureReady("LUT During Record")) return
        queueLatestMonitorUiAction(
            label = "LUT During Record ${if (enabled) "On" else "Off"}",
            action = { gatt.sendOfficialLutDuringRecord(enabled) },
            acceptedText = "Applying LUT During Record; waiting for recorder readback"
        )
    }

    fun setLutDuringOutput(enabled: Boolean) {
        if (!remoteFeatureReady("LUT During Output")) return
        queueLatestMonitorUiAction(
            label = "LUT During Output ${if (enabled) "On" else "Off"}",
            action = { gatt.sendOfficialLutDuringOutput(enabled) },
            acceptedText = "Applying LUT During Output; waiting for recorder readback"
        )
    }

    fun setMonitorDisplayMode(mode: Int) {
        if (mode !in 1..5) {
            _commandStatus.value = "Unsupported Monitor Mode: $mode"
            return
        }
        if (!remoteFeatureReady("Monitor Mode")) return
        val queued = gatt.sendOfficialMonitorMode(mode)
        if (queued) {
            val label = MonitorCoreCatalog.label(mode)
            _commandStatus.value = "Applying Monitor $label; waiting for recorder readback"
        } else {
            _commandStatus.value = gatt.tcsRemoteState.value.lastError
                ?: "Monitor Mode command could not be queued. Wait for PacketLink and retry."
        }
    }

    private fun enterCodecExclusiveTransport(reason: String) {
        // R16.6: The MONITOR entry snapshot serializes many recorder reads. On AG2
        // those optional reads can hold/reacquire the single PacketLink command slot
        // for tens of seconds. A deliberate Codec interaction must take priority.
        if (monitorSnapshotJob?.isActive == true) {
            monitorSnapshotJob?.cancel()
            monitorSnapshotJob = null
            monitorSnapshotGeneration += 1
            Log.d("AtomRemoteSession", "TCS20.15R16.6 CODEC EXCLUSIVE cancel-monitor-snapshot reason=$reason generation=$monitorSnapshotGeneration")
        } else {
            Log.d("AtomRemoteSession", "TCS20.15R16.6 CODEC EXCLUSIVE already-idle reason=$reason")
        }
    }

    private fun queueCodecUiAction(
        label: String,
        action: () -> Boolean,
        acceptedText: String
    ) {
        enterCodecExclusiveTransport(label)
        monitorUiActionRetryJob?.cancel()
        monitorUiActionRetryJob = viewModelScope.launch {
            repeat(30) { attempt ->
                val queued = action()
                Log.d("AtomRemoteSession", "TCS20.15R16.6 CODEC UI QUEUE label=$label attempt=${attempt + 1} queued=$queued")
                if (queued) {
                    _commandStatus.value = acceptedText
                    return@launch
                }
                if (attempt == 0) {
                    _commandStatus.value = "$label waiting for the current recorder transaction..."
                }
                kotlinx.coroutines.delay(140L)
            }
            _commandStatus.value = "$label could not obtain the PacketLink command slot."
            Log.w("AtomRemoteSession", "TCS20.15R16.6 CODEC UI GIVEUP label=$label")
        }
    }

    fun selectCodecProfile(profile: Int) {
        Log.d("AtomRemoteSession", "TCS20.15R16.8 CODEC UI PROFILE TAP profile=$profile current=${codecHardwareProfile.value}")
        enterCodecExclusiveTransport("profile-$profile")
        if (codecHardwareProfile.value == null) {
            _commandStatus.value = "Sync current codec from recorder before choosing a new codec."
            return
        }
        if (profile !in 1..19 || profile == 11) {
            _commandStatus.value = "Unsupported codec profile: $profile"
            return
        }
        _codecProfile.value = profile
        if (profile in 1..5) {
            _codecBitDepth.value = 0
        }
        codecSelectionDirty = true
        _commandStatus.value = if (profile in 1..5) {
            "Codec ${codecProfileLabel(profile)} selected. Official macOS recorder-managed tuple will be used on APPLY."
        } else {
            "Codec ${codecProfileLabel(profile)} selected. Tap APPLY to send it to the recorder."
        }
    }

    fun selectCodecBitDepth(bitDepth: Int) {
        Log.d("AtomRemoteSession", "TCS20.15R16.8 CODEC UI DEPTH TAP bitDepth=$bitDepth profile=${_codecProfile.value} current=${codecHardwareProfile.value}")
        enterCodecExclusiveTransport("depth-$bitDepth")
        if (codecHardwareProfile.value == null) {
            _commandStatus.value = "Sync current codec from recorder before changing bit depth."
            return
        }
        if (bitDepth !in 0..2) {
            _commandStatus.value = "Unsupported codec bit-depth/chroma mode: $bitDepth"
            return
        }
        if (_codecProfile.value in 1..5 && bitDepth != 0) {
            _codecBitDepth.value = 0
            _commandStatus.value = "ProRes depth is recorder-managed by the official macOS codec command path."
            return
        }
        _codecBitDepth.value = bitDepth
        codecSelectionDirty = true
        _commandStatus.value = "Record bit depth ${codecBitDepthLabel(bitDepth)} selected. Tap APPLY to send it with the codec profile."
    }

    fun verifyCodecProfile() {
        if (!remoteFeatureReady("Codec state sync")) return
        // R16.8 CURRENT-FIRST: the codec editor is recorder-authoritative.
        // Clear any stale draft and require the official macOS state pair
        // RecordPage(0x0643) + InputOutputSummary(0x0648) before editing.
        enterCodecExclusiveTransport("Codec Current Sync")
        codecSelectionDirty = false
        _codecProfile.value = 0
        _codecBitDepth.value = -1
        Log.d("AtomRemoteSession", "TCS20.15R16.8 CODEC UI SYNC TAP current-first=1")
        queueCodecUiAction(
            label = "Codec Current Sync",
            action = { gatt.syncOfficialCodecState(-1) },
            acceptedText = "Reading current codec, compression and bit depth from recorder..."
        )
    }

    fun applyCodecProfile() {
        val profile = _codecProfile.value
        val bitDepth = _codecBitDepth.value
        val recorderCurrent = codecHardwareProfile.value
        if (recorderCurrent == null) {
            _commandStatus.value = "Sync current codec from recorder first. Apply stays locked until recorder state is known."
            return
        }
        if (profile !in 1..19 || profile == 11) {
            _commandStatus.value = "Choose a supported codec/compression profile before applying."
            return
        }
        if (bitDepth !in 0..2) {
            _commandStatus.value = "Sync recorder codec first or choose a Bit Depth / Chroma mode before applying."
            return
        }
        if (!remoteFeatureReady("Codec")) return
        // Normal macOS Record picker uses NonRebootCodecProfileSet 0x013F.
        // 19.11.9 then verifies the recorder through CodecProfileGet 0x013A.
        // Transport delivery or a setter ACK alone does not change hardware truth.
        Log.d("AtomRemoteSession", "TCS20.15R16.8 CODEC UI APPLY TAP current=$recorderCurrent draft=$profile/$bitDepth")
        queueCodecUiAction(
            label = "Codec Apply ${codecProfileLabel(profile)}",
            action = { gatt.sendOfficialCodecProfile(profile, bitDepth) },
            acceptedText = "Applying ${codecProfileLabel(profile)}. Recorder state check will run automatically."
        )
    }

    fun activateCodecProfile() {
        val profile = _codecProfile.value
        val bitDepth = _codecBitDepth.value
        if (profile !in 1..19 || profile == 11) {
            _commandStatus.value = "Choose a supported codec/compression profile before activation."
            return
        }
        if (bitDepth !in 0..2) {
            _commandStatus.value = "Sync recorder codec first or choose a Bit Depth / Chroma mode before restart apply."
            return
        }
        if (!remoteFeatureReady("Codec activation")) return
        Log.d("AtomRemoteSession", "TCS20.15R16.6 CODEC UI RESTART APPLY TAP profile=$profile bitDepth=$bitDepth")
        queueCodecUiAction(
            label = "Codec Restart Apply ${codecProfileLabel(profile)}",
            action = { gatt.activateOfficialCodecProfile(profile, bitDepth) },
            acceptedText = "Restart-capable codec change ${codecProfileLabel(profile)} queued (0x013B); recorder readback will verify the result."
        )
    }

    private fun codecProfileLabel(profile: Int): String = when (profile) {
        1 -> "ProRes Proxy"
        2 -> "ProRes LT"
        3 -> "ProRes 422"
        4 -> "ProRes HQ"
        5 -> "ProRes 4444"
        6 -> "DNx 36/LB"
        7 -> "DNx 145/SQ"
        8 -> "DNx 220/HQ"
        9 -> "DNx 220x/HQX"
        10 -> "CinemaDNG"
        11 -> "ProRes RAW"
        12 -> "ProRes RAW HQ"
        13 -> "H.264 LQ"
        14 -> "H.264 MQ"
        15 -> "H.264 HQ"
        16 -> "H.265 LQ"
        17 -> "H.265 MQ"
        18 -> "H.265 HQ"
        19 -> "H.265 XQ"
        else -> "Not selected"
    }

    private fun codecBitDepthLabel(bitDepth: Int): String = when (bitDepth) {
        0 -> "10-bit 4:2:2"
        1 -> "8-bit 4:2:2"
        2 -> "8-bit 4:2:0"
        else -> "Unknown"
    }

    private fun remoteFeatureReady(feature: String): Boolean {
        if (!_pairingCodeAccepted.value || !gatt.tcsPairingState.value.acceptWritten) {
            _commandStatus.value = "Official TCS pairing must be accepted before $feature control."
            return false
        }
        if (!gatt.tcsRemoteState.value.available) {
            _commandStatus.value = "Recorder PacketLink is not ready for $feature control."
            return false
        }
        return true
    }

    fun retryOfficialRemoteLink() {
        if (!_pairingCodeAccepted.value || !gatt.tcsPairingState.value.acceptWritten) {
            _commandStatus.value = "Pairing has not been accepted yet."
            return
        }
        val ok = gatt.openOfficialTcsRemoteLink(autoProbe = true)
        _commandStatus.value = if (ok) {
            "18.6 resynchronizing official TCS CONNECTED lifecycle, PacketLink and RemoteApi."
        } else {
            "18.6 could not resynchronize the official TCS connected session."
        }
    }

    fun isAirGluSyncEndpoint(ad: BleAdvertisement): Boolean {
        val n = ad.name?.trim()?.lowercase().orEmpty()
        val hasFcc1 = ad.serviceUuids.any { it.lowercase().contains("fcc1") }
        val hasShogunAirGluService = ad.serviceUuids.any { it.equals(AIRGLU_SHOGUN_SERVICE_UUID, ignoreCase = true) }
        val shortAirGluName = n == "ag2" || n == "ag" || n.startsWith("ag2-") || n.startsWith("ag-")
        return hasFcc1 || hasShogunAirGluService || shortAirGluName
    }


    fun displayNameFor(ad: BleAdvertisement): String {
        val advertised = ad.name?.trim().orEmpty()
        val lower = advertised.lowercase()
        val matched = AtomosProfiles.match(advertised)

        // Verified hardware identity wins over a short/internal DeviceName such
        // as the AG2 Input payload "PC". This prevents Input from presenting the
        // connected Shogun Ultra as Unknown/PC.
        if (lower == "ag2" || lower.startsWith("ag2-")) return "Shogun Ultra"
        if (matched != null && matched.id != "generic" && !advertised.equals("AG", true)) {
            return matched.displayName
        }

        val storedName = trustedPrefs.getString(trustedKey(ad.address, "name"), null)?.trim().orEmpty()
        if (
            storedName.isNotBlank() &&
            storedName.length > 3 &&
            !storedName.equals("unknown", true)
        ) {
            return storedName
        }

        return when {
            advertised.isNotBlank() && !advertised.equals("AG", true) -> advertised
            else -> "ATOMOS Recorder"
        }
    }

    fun isLikelyAtomRemoteCandidate(ad: BleAdvertisement): Boolean {
        if (isAppleOrGenericPhoneAdvertisement(ad)) return false
        val n = ad.name?.trim()?.lowercase().orEmpty()
        return isAirGluSyncEndpoint(ad) || n.contains("atomos") || n.contains("shogun") || n.contains("ninja") ||
            n.contains("neon") || AtomosProfiles.match(ad.name) != null
    }

    fun probeS1cControlChannel() {
        if (s1cProbeJob?.isActive == true) return
        val snapshot = gattSnapshot.value ?: run {
            _commandStatus.value = "GATT services are not ready yet"
            return
        }
        if (gattState.value != "services-ready") {
            _commandStatus.value = "BLE GATT inspection is still running"
            return
        }

        val characteristics = snapshot.characteristics
        val servicesWithReplies = characteristics
            .filter { it.readable || it.notifiable || it.indicatable }
            .map { it.serviceUuid }
            .toSet()
        val candidates = characteristics
            .filter { (it.writable || it.writableNoResponse) &&
                !it.serviceUuid.equals(AIRGLU_SHOGUN_SERVICE_UUID, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<com.atomosremote.nativeapp.ble.GattCharacteristicInfo> { it.serviceUuid in servicesWithReplies }
                    .thenByDescending { it.writable }
                    .thenByDescending { it.writableNoResponse }
            )
            .take(8)

        if (candidates.isEmpty()) {
            _s1cStatus.value = S1cStatus(state = "not-found", error = "No proven S1C transport outside proprietary 9383 was discovered")
            _commandStatus.value = "18.6 safety: blind S1C probing is disabled; use the recovered TCS CONNECTED + PacketLink RemoteApi transport."
            return
        }

        s1cProbeJob = viewModelScope.launch {
            _s1cStatus.value = S1cStatus(state = "probing")
            for (candidate in candidates) {
                if (gattState.value != "services-ready") {
                    _s1cStatus.value = _s1cStatus.value.copy(state = "waiting-for-gatt")
                    return@launch
                }
                val channel = S1cChannel(
                    serviceUuid = candidate.serviceUuid,
                    characteristicUuid = candidate.characteristicUuid,
                    writeWithResponse = candidate.writable
                )
                val baseline = gattEvents.value.size
                val ok = gatt.write(
                    serviceUuid = channel.serviceUuid,
                    characteristicUuid = channel.characteristicUuid,
                    value = S1cCommands.PROBE.toByteArray(Charsets.US_ASCII),
                    withResponse = channel.writeWithResponse
                )
                if (!ok) continue
                val reply = waitForS1cReply(baseline, listOf("GCMD="), 1100)
                if (reply != null) {
                    _s1cStatus.value = reduceS1cStatus(
                        S1cStatus(state = "ready", channel = channel),
                        reply
                    )
                    _commandStatus.value = "AirGlu S1C control channel detected"
                    refreshS1cStatus()
                    return@launch
                }
                delay(120)
            }
            _s1cStatus.value = S1cStatus(
                state = "not-found",
                error = "BLE link is live, but no documented AirGlu S1C reply was detected"
            )
            _commandStatus.value = "AirGlu S1C control channel not detected"
        }
    }

    private suspend fun waitForS1cReply(startCount: Int, prefixes: List<String>, timeoutMs: Long): String? {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < timeoutMs) {
            val events = gattEvents.value
            val from = startCount.coerceAtMost(events.size)
            val hit = events.drop(from).asReversed().asSequence()
                .mapNotNull { extractS1cAscii(it) }
                .firstOrNull { text -> prefixes.any { prefix -> text.removePrefix("#").startsWith(prefix) } || isS1cReply(text) && prefixes.isEmpty() }
            if (hit != null) return hit
            delay(80)
        }
        return null
    }

    private fun writeS1c(command: String): Boolean {
        val channel = _s1cStatus.value.channel ?: return false
        return gatt.write(
            serviceUuid = channel.serviceUuid,
            characteristicUuid = channel.characteristicUuid,
            value = command.toByteArray(Charsets.US_ASCII),
            withResponse = channel.writeWithResponse
        )
    }

    private suspend fun queryS1c(command: String, prefixes: List<String>, timeoutMs: Long = 900): String? {
        if (_s1cStatus.value.channel == null) return null
        val baseline = gattEvents.value.size
        if (!writeS1c(command)) return null
        val reply = waitForS1cReply(baseline, prefixes, timeoutMs)
        if (reply != null) {
            var next = reduceS1cStatus(_s1cStatus.value, reply)
            if (reply.removePrefix("#").startsWith("GCMD=")) next = next.copy(state = "ready")
            _s1cStatus.value = next
        }
        return reply
    }

    fun refreshS1cStatus() {
        if (!_s1cStatus.value.ready) return
        viewModelScope.launch {
            queryS1c(S1cCommands.FIRMWARE, listOf("GCVR="))
            queryS1c(S1cCommands.BATTERY, listOf("GCBT="))
            queryS1c(S1cCommands.TRANSPORT, listOf("GCCT="))
            queryS1c(S1cCommands.MEDIA, listOf("GCDV="))
            queryS1c(S1cCommands.TIMECODE, listOf("TCTM="))
            queryS1c(S1cCommands.DEVICE_POLL, listOf("GCXP="))
            queryS1c(S1cCommands.PAIRING, listOf("BTPR="))
            queryS1c(S1cCommands.PAIR_STATUS, listOf("BTST="))
        }
    }

    fun completeS1cPairing() {
        val address = _selected.value?.address
        if (!PairingRuntimeGate.isPairingMutationAllowed(address)) {
            _commandStatus.value = "BTPR=2 blocked: accept the captured Pairing ID first."
            return
        }
        if (_s1cStatus.value.channel == null) {
            _commandStatus.value = "AirGlu pairing API has not been detected yet"
            return
        }
        viewModelScope.launch {
            val ok = writeS1c(S1cCommands.PAIR_CONNECT)
            _commandStatus.value = if (ok) "AirGlu BTPR=2 connect request sent" else "AirGlu pairing request failed"
            if (!ok) return@launch
            delay(700L)
            queryS1c(S1cCommands.PAIRING, listOf("BTPR="), 2200L)
            queryS1c(S1cCommands.PAIR_STATUS, listOf("BTST="), 2200L)
            val model = queryS1c(S1cCommands.PROBE, listOf("GCMD="), 2300L)
            _commandStatus.value = if (model != null) {
                "ATOMOS/AirGlu control channel confirmed. On the recorder, enable Remote mode if the white Remote icon is shown."
            } else {
                "Pair/connect command was sent, but recorder control has not been confirmed yet."
            }
        }
    }

    fun clearCommandStatus() { _commandStatus.value = null }

    fun createPreset(name: String, commandKeysText: String, note: String = "") {
        val keys = commandKeysText.split(',', ';', '\n').map { it.trim() }.filter { it.isNotBlank() }
        presetStore.create(name, keys, note)
        _presets.value = presetStore.loadAll()
        _commandStatus.value = "Preset saved"
    }

    fun removePreset(id: String) {
        presetStore.remove(id)
        _presets.value = presetStore.loadAll()
    }

    fun applyPreset(preset: RecorderPreset) {
        if (preset.commandKeys.isEmpty()) {
            _commandStatus.value = "Preset has no command keys"
            return
        }
        val results = preset.commandKeys.map { key -> key to commandEngine.send(_protocol.value, key) }
        val sent = results.count { it.second.isSuccess }
        val missing = results.filter { it.second.isFailure }.map { it.first }
        _commandStatus.value = if (missing.isEmpty()) {
            "Preset '${preset.name}' applied: $sent command(s) accepted"
        } else {
            "Preset '${preset.name}': $sent sent, ${missing.size} locked/missing (${missing.joinToString()})"
        }
    }

    fun startNetworkDiscovery() = networkDiscovery.start()
    fun stopNetworkDiscovery() = networkDiscovery.stop()
    fun refreshNetworkStatus() = networkDiscovery.refreshStatus()

    fun probeNetwork(host: String, portsText: String) {
        val cleanHost = host.trim()
        if (cleanHost.isBlank()) {
            _networkProbeResults.value = emptyList()
            _commandStatus.value = "Enter a recorder or network-device IP/hostname"
            return
        }
        val ports = portsText.split(',', ' ', ';')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..65535 }
            .ifEmpty { NetworkDiscovery.DEFAULT_PROBE_PORTS }
        viewModelScope.launch {
            _networkProbeResults.value = networkDiscovery.probeHost(cleanHost, ports)
        }
    }

    fun prepareNdi(source: NdiSource) {
        _ndiPreviewState.value = ndiBridge.prepare(source).getOrElse {
            NdiPreviewState.Unavailable(it.message ?: "NDI preview bridge failed")
        }
    }

    fun stopNdi() {
        ndiBridge.stop()
        _ndiPreviewState.value = NdiPreviewState.Idle
    }

    fun connectAllSaved() {
        multiController.connectAll(_savedAll.value)
    }

    fun disconnectAllSaved() = multiController.disconnectAll()

    fun multiSend(command: String) {
        val results = multiController.sendAll(_protocol.value, command)
        val ok = results.count { it.value.isSuccess }
        val failed = results.size - ok
        _commandStatus.value = "$command: $ok accepted, $failed failed/locked"
    }

    fun saveAirGlu(
        role: AirGluRole,
        channel: Int,
        groupName: String,
        gangRecord: Boolean,
        timecodeSource: String,
        deviceVersion: String?
    ) {
        val config = AirGluConfig(
            role = role,
            rfChannel = channel.coerceIn(1, 12),
            groupName = groupName.trim().ifBlank { "ATOMOS" },
            gangRecord = gangRecord,
            timecodeSource = timecodeSource.trim().ifBlank { "AirGlu" },
            deviceVersion = deviceVersion?.trim()?.ifBlank { null }
        )
        airGluStore.save(config)
        _airGluConfig.value = config
        _commandStatus.value = "AirGlu workflow profile saved locally"
    }

    fun tryApplyAirGlu() {
        val result = airGluBridge.apply(_airGluConfig.value)
        _commandStatus.value = result.fold(
            onSuccess = { "AirGlu configuration applied" },
            onFailure = { it.message ?: "AirGlu bridge unavailable" }
        )
    }

    fun exportPairingTrace(): Result<CaptureExport> = runCatching {
        val ad = _selected.value
        captureExporter.exportPairingTrace(
            recorderName = ad?.name ?: _saved.value?.name,
            selectedAddress = ad?.address,
            pairingId = ad?.advertisedPairingId ?: PairingRuntimeGate.acceptedSessionPairingId(),
            peripheralState = peripheralState.value,
            centralAddress = peripheralCentralAddress.value,
            events = peripheralEvents.value,
            advertisement = ad,
            pairingCandidates = _pairingCodeCandidates.value.map { candidate ->
                PairingCandidateCapture(
                    pairingId = candidate.pairingId,
                    sourceAddress = candidate.sourceAddress,
                    sourceName = candidate.sourceName,
                    rssi = candidate.rssi,
                    hits = candidate.hits,
                    exactAg2Source = candidate.exactAg2Source
                )
            },
            gattSnapshot = gattSnapshot.value,
            gattState = gattState.value,
            gattEvents = gattEvents.value,
            smpPasskey = peripheralSmpPasskey.value,
            smpPairingVariant = peripheralSmpPairingVariant.value
        )
    }

    fun exportProtocolCapture(): Result<CaptureExport> = runCatching {
        captureExporter.export(
            recorderName = _selected.value?.name ?: _saved.value?.name,
            snapshot = gattSnapshot.value,
            events = gattEvents.value + peripheralEvents.value.map { "PERIPHERAL $it" },
            protocolName = _protocol.value?.name,
            advertisement = _selected.value
        )
    }

    fun runFinalQa() {
        _qaReport.value = qaRunner.run(
            protocolValidated = _protocol.value?.canTransmit == true,
            savedDeviceCount = _savedAll.value.size,
            gattReady = gattState.value == "services-ready" || gattSnapshot.value != null,
            ndiNativeAvailable = ndiNativeAvailable,
            airGluNativeAvailable = airGluNativeAvailable
        )
    }

    companion object {
        const val AIRGLU_SHOGUN_SERVICE_UUID = "9383b854-8bee-11e7-bb31-be2e44b06b34"
    }

    override fun onCleared() {
        backendAgent.stop()
        Log.d("AtomRemoteBackend", "Backend agent coordinator stopped")
        stopScan()
        networkDiscovery.stop()
        ndiBridge.stop()
        multiController.close()
        peripheral.close()
        gatt.close()
        super.onCleared()
    }
}
