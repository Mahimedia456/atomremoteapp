package com.atomosremote.nativeapp.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.atomosremote.nativeapp.input.InputExtendedReadPlan
import com.atomosremote.nativeapp.input.InputExtendedSnapshot
import com.atomosremote.nativeapp.input.InputExtendedStartPolicy
import com.atomosremote.nativeapp.input.InputEditCatalog
import com.atomosremote.nativeapp.monitor.MonitorCoreCatalog
import com.atomosremote.nativeapp.monitor.MonitorCoreSnapshot
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
import com.atomosremote.nativeapp.input.InputSetterState
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID
import java.util.ConcurrentModificationException

data class ZoomBoxState(
    val frameWidth: Int,
    val frameHeight: Int,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val left: Int,
    val top: Int
) {
    val maxLeft: Int get() = (frameWidth - viewportWidth).coerceAtLeast(0)
    val maxTop: Int get() = (frameHeight - viewportHeight).coerceAtLeast(0)
    val valid: Boolean get() = frameWidth > 0 && frameHeight > 0 && viewportWidth > 0 && viewportHeight > 0
    val pannable: Boolean get() = valid && (maxLeft > 0 || maxTop > 0)
}

/** 19.12.9 recorder-authoritative AppPolling page snapshot. */
data class RemotePageSnapshot(
    val commandId: Int,
    val label: String,
    val responseType: Int,
    val local: Boolean,
    val rawHex: String,
    val fieldsJson: String
)

data class RemoteStateSnapshot(
    val generation: Int = 0,
    val cycleComplete: Boolean = false,
    val expectedPages: Int = 10,
    val pages: Map<Int, RemotePageSnapshot> = emptyMap(),
    val json: String = "{}"
) {
    val receivedPages: Int get() = pages.size
}

/** 19.12.10 recorder-backed granular Input + Codec collector.
 * AppPolling is not a release gate on AG2 because 0x0640..0x0649 currently
 * complete only the PacketLink E0 turn on the tested recorder.
 */
data class InputCodecCollectorSnapshot(
    val generation: Int = 0,
    val running: Boolean = false,
    val inputExpected: Int = 8,
    val inputResponses: Int = 0,
    val inputValues: Map<String, String> = emptyMap(),
    val codecProfile: Int? = null,
    val codecBitDepth: Int? = null,
    val codecVerified: Boolean = false,
    val json: String = "{}"
)

/**
 * Native BLE/GATT client for ATOMOS Shogun Ultra.
 *
 * 18.8 preserves the proven official TCS pairing state machine byte-for-byte and
 * ports the supplied macOS TCSiOSC TCSPacketLink transport as a real
 * READY -> WRITING -> READING state machine. Post-accept frame-count/timecode sync
 * remains intact; pairing writes and acceptance math are not modified.
 * 18.0 ports the application pairing state machine recovered from the official
 * macOS AtomRemote 1.2.5 TCSiOSC framework. The Android app stays BLE central:
 * read ID slots, write the 9-digit Connect Name, write CCID, receive BCID, then
 * on explicit user confirmation write bitwise-NOT CCID. No Android createBond()
 * call and no guessed S1C/BTPR transport is used for this pairing gate.
 */
class GattClient(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var retryCount = 0
    private var receiverRegistered = false
    private var linkStartedAtMs = 0L
    private var prePairCaptureStartedForConnection = false
    private var passivePairCaptureStartedForConnection = false
    private val secureRandom = SecureRandom()

    private val _state = MutableStateFlow("idle")
    val state: StateFlow<String> = _state

    private val _bondState = MutableStateFlow("none")
    val bondState: StateFlow<String> = _bondState

    private val _disconnectReason = MutableStateFlow<String?>(null)
    val disconnectReason: StateFlow<String?> = _disconnectReason

    private val _snapshot = MutableStateFlow<GattSnapshot?>(null)
    val snapshot: StateFlow<GattSnapshot?> = _snapshot

    private val _tcsPairingState = MutableStateFlow(TcsPairingState())
    val tcsPairingState: StateFlow<TcsPairingState> = _tcsPairingState

    private val _tcsRemoteState = MutableStateFlow(TcsRemoteState())
    val tcsRemoteState: StateFlow<TcsRemoteState> = _tcsRemoteState

    // 19.11.6 universal identity: BLE advertisements such as "AG2" identify the
    // AirGlu endpoint, not the recorder model. cxtic2 is the recorder-authoritative
    // device name read during CONNECT/CONNECTED and is exposed to the UI.
    private val _connectedRecorderName = MutableStateFlow<String?>(null)
    val connectedRecorderName: StateFlow<String?> = _connectedRecorderName

    // 19.11.8 Zoom feature state, synchronized from the same three GETs
    // used by macOS ZoomSettingsController.
    private val _zoomSupportedLevels = MutableStateFlow<List<Int>>(emptyList())
    val zoomSupportedLevels: StateFlow<List<Int>> = _zoomSupportedLevels
    private val _zoomCapabilitiesKnown = MutableStateFlow(false)
    val zoomCapabilitiesKnown: StateFlow<Boolean> = _zoomCapabilitiesKnown
    private val _zoomHardwareLevel = MutableStateFlow<Int?>(null)
    val zoomHardwareLevel: StateFlow<Int?> = _zoomHardwareLevel
    private val _monitorZoomCore = MutableStateFlow(MonitorZoomCoreState())
    val monitorZoomCore: StateFlow<MonitorZoomCoreState> = _monitorZoomCore
    private var zoomExpectedLevelAfterSet: Int? = null
    private var lastRequestedZoomPanX: Int? = null
    private var lastRequestedZoomPanY: Int? = null
    // 20.13.4 R14 live analog pump. UI samples never wait on the previous
    // PanSet. The newest target wins; an independent pump retries whenever the
    // shared PacketLink slot is busy. One authoritative ZoomBox readback runs
    // only after the live stream has been quiet for a short settle interval.
    private var queuedLiveZoomPanX: Int? = null
    private var queuedLiveZoomPanY: Int? = null
    private var zoomPanSettleGeneration: Int = 0
    private var zoomPanPumpScheduled: Boolean = false
    private var zoomPanLastTargetAtMs: Long = 0L
    private var zoomPanLastSendAtMs: Long = 0L
    private var zoomPanWatchdogGeneration: Int = 0
    private var zoomAnalogSessionActive: Boolean = false
    private val _zoomBoxState = MutableStateFlow<ZoomBoxState?>(null)
    val zoomBoxState: StateFlow<ZoomBoxState?> = _zoomBoxState
    private val _monitorZoomViewport = MutableStateFlow(MonitorZoomViewportState())
    val monitorZoomViewport: StateFlow<MonitorZoomViewportState> = _monitorZoomViewport
    private val _monitorFocusAssist = MutableStateFlow(MonitorFocusAssistState())
    val monitorFocusAssist: StateFlow<MonitorFocusAssistState> = _monitorFocusAssist
    private val _monitorZebraState = MutableStateFlow(MonitorZebraState())
    val monitorZebraState: StateFlow<MonitorZebraState> = _monitorZebraState
    private val _monitorAssistEffects = MutableStateFlow(MonitorAssistEffectsState())
    val monitorAssistEffects: StateFlow<MonitorAssistEffectsState> = _monitorAssistEffects
    private val _monitorScopeState = MutableStateFlow(MonitorScopeState())
    val monitorScopeState: StateFlow<MonitorScopeState> = _monitorScopeState
    private val _monitorGuideState = MutableStateFlow(MonitorGuideState())
    val monitorGuideState: StateFlow<MonitorGuideState> = _monitorGuideState
    private val _monitorAnamorphicState = MutableStateFlow(MonitorAnamorphicState())
    val monitorAnamorphicState: StateFlow<MonitorAnamorphicState> = _monitorAnamorphicState
    private val _monitorLutState = MutableStateFlow(MonitorLutState())
    val monitorLutState: StateFlow<MonitorLutState> = _monitorLutState
    private val _monitorDisplayState = MutableStateFlow(MonitorDisplayState())
    val monitorDisplayState: StateFlow<MonitorDisplayState> = _monitorDisplayState
    private var lutRefreshStage = 0
    private var lutExpectedSlot: Int? = null
    private var lutExpectedPreview: Boolean? = null
    private var lutExpectedRecordBurnIn: Boolean? = null
    private var lutExpectedOutput: Boolean? = null
    private var lutInitialRetryCount = 0
    private var scopeRefreshStage = 0
    private var scopeExpectedEnabled: Boolean? = null
    private var scopeExpectedMode: Int? = null
    private var scopeExpectedSize: Int? = null
    private var scopeExpectedOpacity: Int? = null
    private var scopeExpectedBrightness: Int? = null
    private var focusRefreshStage = 0
    private var focusRefreshRetry = 0
    private var focusExpectedEnabled: Boolean? = null
    private var focusExpectedType: Int? = null
    private var focusExpectedThreshold: Int? = null
    private var focusExpectedColour: Int? = null
    private var zebraExpectedEnabled: Boolean? = null
    private var zebraExpectedThreshold: Int? = null
    private var zoomSummaryExpectedLevel: Int? = null
    private var pendingZoomLevelAfterCapability: Int? = null
    private var deferredZoomLevelAfterCapability: Int? = null
    private var zoomRefreshActive = false
    private var zoomRefreshStage = 0 // 0 idle, 1 list, 2 level, 3 box
    private var zoomRefreshAfterSet = false

    // 19.6 guide/anamorphic state mirrors. Safe Area is synchronized on first
    // cycle because the recorder may already be on Action or Title when the
    // Android UI opens. This avoids a local OFF state hiding the real device state.
    private val _safeAreaHardwareMode = MutableStateFlow<Int?>(null)
    val safeAreaHardwareMode: StateFlow<Int?> = _safeAreaHardwareMode
    private val _anamorphicHardwareMode = MutableStateFlow<Int?>(null)
    val anamorphicHardwareMode: StateFlow<Int?> = _anamorphicHardwareMode
    // 19.7 keeps the UI responsive while respecting the single in-flight
    // PacketLink transaction. Rapid Anamorphic taps are coalesced to the latest
    // requested mode and dispatched immediately after the active command ends.
    private var deferredAnamorphicMode: Int? = null

    // Phase 07 Monitor Mode state. macOS MonitorTabController calls
    // RemoteApi.Monitor.MonitorModeSet (0x02BC). macOS AtomRemote static
    // MonitorMode table: 1=Native, 2=Rec709, 3=HLG, 4=PQ, 5=LUT.
    // Gamma/Gamut shown on the Monitor page are source
    // metadata; official ATOMOS documentation says they are set in Input.
    private val _monitorModeHardware = MutableStateFlow<Int?>(null)
    val monitorModeHardware: StateFlow<Int?> = _monitorModeHardware

    // 20.04.0-20.04.2 Monitor Core.
    // The official MAC13 startup already asks MonitorModeGet (0x02BD). This
    // snapshot makes that connect-time readback explicit and keeps the UI
    // recorder-authoritative. Setter acceptance never changes `mode`.
    private val _monitorCoreSnapshot = MutableStateFlow(MonitorCoreSnapshot())
    val monitorCoreSnapshot: StateFlow<MonitorCoreSnapshot> = _monitorCoreSnapshot
    private var monitorModeReadbackExpected: Int? = null
    private var monitorModeReadbackSource: String? = null

    // Phase 19.11.9 Record codec state. macOS evidence separates three paths:
    //   CodecProfileGet          = 0x013A
    //   CodecProfileSet          = 0x013B (restart-capable)
    //   NonRebootCodecProfileSet = 0x013F (normal Record picker)
    // PacketLink delivery or a generic setter ACK is never treated as hardware
    // confirmation. This mirror changes only from CodecProfileGet readback.
    private val _codecHardwareProfile = MutableStateFlow<Pair<Int, Int>?>(null)
    val codecHardwareProfile: StateFlow<Pair<Int, Int>?> = _codecHardwareProfile


    // Phase 09 Input / Format is deliberately read-only. The macOS app polls
    // AppPolling.ApplicationStateGet (0x0640) and InputOutputSummaryGet (0x0648).
    // We mirror those proven GETs and expose recorder-reported fields without
    // inventing Input setters. Full writable/capability synchronization remains
    // Phase 10 work.
    private val _inputFormatInfo = MutableStateFlow<String?>(null)
    val inputFormatInfo: StateFlow<String?> = _inputFormatInfo
    private val _inputCodecId = MutableStateFlow<Int?>(null)
    val inputCodecId: StateFlow<Int?> = _inputCodecId
    private val _inputCameraId = MutableStateFlow<Int?>(null)
    val inputCameraId: StateFlow<Int?> = _inputCameraId
    private val _inputGammaId = MutableStateFlow<Int?>(null)
    val inputGammaId: StateFlow<Int?> = _inputGammaId
    private val _inputGamutId = MutableStateFlow<Int?>(null)
    val inputGamutId: StateFlow<Int?> = _inputGamutId
    private val _outputInfo = MutableStateFlow<String?>(null)
    val outputInfo: StateFlow<String?> = _outputInfo
    private val _outputHdrTypeId = MutableStateFlow<Int?>(null)
    val outputHdrTypeId: StateFlow<Int?> = _outputHdrTypeId
    private val _outputGamutId = MutableStateFlow<Int?>(null)
    val outputGamutId: StateFlow<Int?> = _outputGamutId

    // OUTPUT R17: these values are NULL until independently correlated,
    // local-route, typed recorder GET responses are observed.
    private val _outputDownscaleRawId = MutableStateFlow<Int?>(null)
    val outputDownscaleRawId: StateFlow<Int?> = _outputDownscaleRawId
    // OUTPUT R19: strictly typed raw page, NOT interpreted as Output settings.
    // Null until recorder actually returns AppPolling.OutputPageGet.
    private val _outputPageRawR19 = MutableStateFlow<String?>(null)
    val outputPageRawR19: StateFlow<String?> = _outputPageRawR19
    // R23 increments on EACH fully typed OutputPage response, even if
    // the same 54-byte payload repeats after refresh.
    private val _outputPageRxGenerationR23 = MutableStateFlow(0)
    val outputPageRxGenerationR23: StateFlow<Int> = _outputPageRxGenerationR23
    private val _outputDciCropRawId = MutableStateFlow<Int?>(null)
    val outputDciCropRawId: StateFlow<Int?> = _outputDciCropRawId
    private val _outputHdmiTimecodeEnabled = MutableStateFlow<Boolean?>(null)
    val outputHdmiTimecodeEnabled: StateFlow<Boolean?> = _outputHdmiTimecodeEnabled

    // OUTPUT06: Hardware-only HDR Out state. Null means unverified/unsupported.
    // Never substitute the macOS UI's default Rec.709/Off for an AG2 readback.
    private val _outputHdrEnabled = MutableStateFlow<Boolean?>(null)
    val outputHdrEnabled: StateFlow<Boolean?> = _outputHdrEnabled
    private val _inputOutputRawHex = MutableStateFlow<String?>(null)
    val inputOutputRawHex: StateFlow<String?> = _inputOutputRawHex

    // 19.12.10 AG2 state collector. These values are populated only by actual
    // RemoteApi getter payloads/rejects observed from the recorder.
    private val _inputCodecCollector = MutableStateFlow(InputCodecCollectorSnapshot())
    val inputCodecCollector: StateFlow<InputCodecCollectorSnapshot> = _inputCodecCollector
    private val _inputExtendedSnapshot = MutableStateFlow(InputExtendedSnapshot())
    val inputExtendedSnapshot: StateFlow<InputExtendedSnapshot> = _inputExtendedSnapshot
    private val _inputSetterState = MutableStateFlow(InputSetterState())
    val inputSetterState: StateFlow<InputSetterState> = _inputSetterState
    private val inputExtendedValues = linkedMapOf<String, String>()
    private val inputExtendedResponseTypes = linkedMapOf<String, Int>()
    private val inputExtendedRawPayloads = linkedMapOf<String, String>()
    private data class PendingInputSetter(
        val key: String,
        val desiredValue: Int,
        val requestedLabel: String,
        val mismatchCount: Int = 0
    )

    private var pendingInputSetter: PendingInputSetter? = null
    private var inputSetterReadbackRunnable: Runnable? = null
    private val inputSetterMaxMismatchRetries = 3

    private var inputExtendedGeneration = 0
    private var inputExtendedRunning = false
    private var inputExtendedIndex = 0
    private var inputExtendedVariant = 0
    private var inputExtendedTimeout: Runnable? = null
    private var inputExtendedActive: InputExtendedReadPlan.Probe? = null
    private var inputExtendedLastChangeToken: String? = null
    private val inputCollectorValues = linkedMapOf<String, String>()
    private var inputCollectorGeneration = 0
    private var inputCollectorRunning = false
    private var inputCollectorRefreshPending = false
    // INPUT05C.2: guarded Source GET-only readback, reuses existing collector parser.
    private var inputSourceOnlyReadback = false
    // AGENT7 20.03.4.2 POST-READY AUTO INPUT
    // One automatic Input 3/3 collector is allowed after each successful
    // macOS-parity Remote bootstrap reaches its real LINKED settle point.
    // AGENT7 20.03.4.5.2 REMOTE STATE STABILITY
    // AGENT7 20.03.4.8 POST-LINK TIMECODE ARBITRATION
    private var postReadyInputAutoTriggered = false
    private var postReadyInputAutoTask: Runnable? = null

    // AGENT7 20.03.4.3 POST-ACCEPT PROGRESS WATCHDOG
    //
    // Fresh-pair evidence proved user acceptance can be recorded while the existing
    // CONNECTED -> PacketLink progression never starts until an app reopen.
    // This watchdog does NOT invent a new pairing step. It only re-enters the existing
    // guarded functions for the already-proven official sequence.
    private var postAcceptProgressTask: Runnable? = null
    private var postAcceptProgressEpoch = 0

    private var inputProbeIndex = 0
    private var inputProbeVariant = 0
    // TCS19.12.17.1 read-only getter route matrix packet sequence.
    private var inputGetterRoutePacketSequence = 0
    private var inputProbeTimeout: Runnable? = null
    private var activeInputProbe: InputGranularProbe? = null
    private var pendingCodecReadbackExpected: Pair<Int, Int>? = null
    private var pendingCodecReadbackSource: String? = null
    private var codecReadbackTimeout: Runnable? = null


    // 19.12.9 full connected-state backbone. Official AtomRemote exposes the
    // AppPolling page family 0x0640..0x0649. Every connection requests all pages.
    private val _remoteStateSnapshot = MutableStateFlow(RemoteStateSnapshot())
    val remoteStateSnapshot: StateFlow<RemoteStateSnapshot> = _remoteStateSnapshot
    private val stateSyncPages = linkedMapOf<Int, RemotePageSnapshot>()
    // 19.12.9.1: AppPolling responses may arrive asynchronously after the
    // PacketLink E0 transport turn has already cleared pendingRemotePacket.
    // Keep request packetId -> page command correlation alive until the real
    // AtomMessage payload arrives, otherwise valid page data is seen but the
    // UI remains STATE 0/10.
    private val stateSyncPacketToPage = mutableMapOf<Int, Int>()
    // INPUT06A BOOTSTRAP RX: exact request packet correlation, cleared on disconnect.
    private val input06aBootstrapPending = mutableMapOf<Int, Pair<Int, String>>()
    // 19.12.10.2: binary-confirmed macOS AppPolling parity.
    // AppPolling AtomMessage objects finish with m_isLocal=false. CommandManager packet IDs
    // are retained as a sequence so this test covers the exact combination not yet tested.
    private var appPollingPacketSequence: Int = 0
    // 19.12.9.2 recovery: state sync must not depend on one PacketLink-prime edge.
    // XOS / trusted reconnect timing can leave the Remote screen linked while no
    // AppPolling page has been retained. A bounded watchdog and an explicit UI
    // refresh can restart the official bootstrap after PacketLink is already ready.
    private var stateSyncRecoveryTask: Runnable? = null
    private var stateSyncRecoveryAttempts = 0
    private var stateSyncRefreshPending = false
    private var stateSyncGeneration = 0
    private var remoteBootstrapTotal = 0
    private val _safeAreaSummaryRaw = MutableStateFlow<Int?>(null)
    val safeAreaSummaryRaw: StateFlow<Int?> = _safeAreaSummaryRaw
    private val _frameGuideFormatSummaryRaw = MutableStateFlow<Int?>(null)
    val frameGuideFormatSummaryRaw: StateFlow<Int?> = _frameGuideFormatSummaryRaw

    private var pendingSafeAreaCycle = false
    private var deferredSafeAreaMode: Int? = null

    // 19.11.7 Guides feature: macOS cmdFrameGrid does not send a standalone
    // boolean. It reads/preserves the current FrameGuideFormat tuple, changes
    // the 3x3 member, then sends FrameGuideFormatSet (0x0275). Keep recorder
    // state authoritative so toggling 9-Grid cannot silently overwrite the
    // selected frame-guide ratio.
    private val _frameGridHardwareEnabled = MutableStateFlow<Boolean?>(null)
    val frameGridHardwareEnabled: StateFlow<Boolean?> = _frameGridHardwareEnabled
    private data class PendingFrameGridSet(
        val enabled: Boolean,
        val frameGuideValue: Int,
        val frameGuideEnabled: Boolean,
        val mode: Int
    )
    private var pendingFrameGridSet: PendingFrameGridSet? = null
    // R16.2 Frame Guide readback target. Restored from the previously proven implementation.
    private var frameGuideExpectedFormat: Int? = null
    private var frameGuideReadbackGeneration: Int = 0

    // Legacy mirrors retained for UI/debug compatibility. 18.8 transport truth is
    // TcsPacketLinkTransport below. Official DidConnect starts first=1/credit=0,
    // produces the F0 prime turn; official macOS RemoteApi AtomMessage subclasses use m_isLocal=1.
    private var tcsCommandSequence: Int = 0
    private var tcsPacketFirstSubpacket: Boolean = true
    private var tcsPacketRemoteCredit: Boolean = false
    // TCSBleMonitor.packetLinkTimer in the supplied macOS AtomRemote executable
    // runs every 0.01 s and calls TCSBleManager_PacketLinkProcessTick. 18.4 was
    // missing this process loop, so it treated the first flow-control-only E0 as
    // a completed RemoteApi transaction and never pulled the later payload.
    private var tcsPacketLocalFlag: Boolean = false
    private var packetLinkProcessEnabled: Boolean = false
    private var remoteEmptyPolls: Int = 0
    private var remoteResponseStartedAtMs: Long = 0L
    private var remoteResponseTimeout: Runnable? = null
    private var remoteBootstrapSettle: Runnable? = null
    private val remoteRxBuffer = ByteArrayOutputStream()
    private val packetLinkProcessTick = object : Runnable {
        override fun run() {
            if (!packetLinkProcessEnabled) return
            drivePacketLink18_8()
            mainHandler.postDelayed(this, PACKET_LINK_PROCESS_TICK_MS)
        }
    }

    private data class PendingRemotePacket(
        val body: ByteArray,
        val purpose: String,
        val label: String,
        val commandId: Int,
        val packetId: Int,
        val expectedResponseId: Int? = null,
        val bootstrap: Boolean = false
    )

    private data class RemoteBootstrapQuery(
        val commandId: Int,
        val expectedResponseId: Int,
        val label: String,
        val local: Boolean = true
    )

    // TCS19.12.17.1 INPUT GETTER ROUTE MATRIX: empty-payload GETs only; no Input writes.
    // TCS19.12.17.2 BINARY-CONFIRMED INPUT CORE
    //
    // Exact Input-related GET mappings recovered from the supplied official
    // macOS ATOMOS Remote startup sequence:
    //
    // 0x0648 -> 0x0648 AppPolling.InputOutputSummaryGet
    // 0x0064 -> 0x0064 Input.SourceGet
    // 0x0071 -> 0x006D Input HDR state getter
    // 0x0070 -> 0x006C Input.DeviceNameGet
    //
    // Query envelope: sequential packet IDs, empty payload, m_isLocal=false.
    private data class InputGranularProbe(
        val commandId: Int,
        val expectedResponseId: Int,
        val key: String,
        val label: String
    )

    // TCS19.12.19 AG2 RELEASE INPUT COLLECTOR
    // AppPolling.InputOutputSummaryGet (0x0648) is intentionally excluded from
    // the release collector on the tested Shogun Ultra AG2. Hardware validation
    // showed all four read-only envelopes complete PacketLink transport with E0
    // but return no typed AtomMessage payload. The three direct Input getters
    // below are recorder-backed and returned typed payloads on AG2.
    private val inputGranularProbePlan = listOf(
        InputGranularProbe(0x0064, 0x0064, "source", "Input Source"),
        InputGranularProbe(0x0071, 0x006D, "hdrState", "Input HDR State"),
        InputGranularProbe(0x0070, 0x006C, "deviceName", "Input Device Name")
    )
    private var pendingRemotePacket: PendingRemotePacket? = null
    private var pendingRemoteFrameWritten: Boolean = false
    private val remoteBootstrapQueue = ArrayDeque<RemoteBootstrapQuery>()
    private var remoteBootstrapActive = false
    private var remoteBootstrapCompleted = false
    private var remoteBootstrapResponses = 0

    // 18.9.1: PacketLink transport health and RemoteApi verification are different.
    // The UI remains usable for diagnostics when PacketLink is ready even if the
    // recorder has not returned a RemoteApi AtomMessage yet.
    private var remoteApiVerified = false
    private var pendingDiagnosticFollowUpCommand: String? = null
    private var pendingDiagnosticFollowUpTask: Runnable? = null
    // AGENT7 20.03.4.13 TRUSTED TRANSPORT RECOVERY
    private val agent7TransportRecoveryVersion = "20.03.4.13-trusted-transport-recovery"
    private val remoteParityVersion = "18.9.1-remoteapi-diagnostic-mode-parity"

    // Official TCSBleManager CONNECTED lifecycle. The macOS framework does not jump
    // directly from ~CCID acceptance to LINKED. It first subscribes cxtic0 frame-count,
    // waits for a frame update, reads cxtic1 timecode, and only then exposes PacketLink.
    private var connectedLifecycleArmed = false
    private var connectedFrameCountCccdReady = false
    private var connectedLifecycleReady = false
    // 18.8.2: the official TCS LINKED path opens PacketLink on a fresh BLE
    // connection after the accepted CONNECTED session. This flag survives
    // closeGattOnly() so the new services-ready callback can finish that handoff.
    private var officialLinkedReconnectPending = false
    private var connectedTimecodeReadPending = false
    private var connectedDeviceNameCountdown = 3
    private var connectedLastFrameCount = -1
    private var connectedLastTimecodeReadAtMs = 0L
    private var connectedSyncTimeout: Runnable? = null

    // 18.8.6: exact CONNECTED Data Transfer State Machine stage recovered from
    // the supplied macOS TCSiOSC.framework. PacketLink/LINKED is gated until
    // the initial primary/secondary model negotiation and cxtic8/cxtic9 model
    // synchronization complete.
    private val connectedDtsm = TcsConnectedDataTransfer()
    private val macosPostPairParityVersion = "18.9.0-timecode-gated-connected-packetlink"

    // 18.8: direct port of the official macOS TCSPacketLink READY/WRITING/READING
    // state machine. Pairing remains completely separate from this transport.
    private val packetLink18 = TcsPacketLinkTransport()
    // OUTPUT R21: BLE MTU is a connection-scoped ATT capability, not an
    // AtomRemote pairing/PacketLink command. The negotiated value is used
    // only to assess whether a full 0x8644 page can physically fit in one read.
    private var outputR21MtuRequested = false
    private var outputR21MtuBytes = 23  // default ATT MTU until callback proves otherwise
    private var outputR21MtuResult = "not-requested"
    // TCS19.12.17.10 LOW LEVEL PLINK EPOCH GUARD
    // One low-level PacketLink DidConnect is allowed per actual GATT connection.
    // Reset only when Android reports a new GATT connected epoch.
    private var packetLinkDidConnectEnteredThisGattEpoch: Boolean = false
    private var packetLink18Primed = false

    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events

    private val values = linkedMapOf<String, ByteArray>()
    private val asciiLineBuffer = StringBuilder()
    private var address: String = ""

    private sealed interface PendingOp {
        // R21: serialized with existing ATT ops. Never request MTU while a
        // PacketLink read/write or TCS pairing transaction is in flight.
        data class OutputR21Mtu(val mtu: Int = 185) : PendingOp
        data class Read(
            val characteristic: BluetoothGattCharacteristic,
            val purpose: String? = null
        ) : PendingOp
        data class WriteDescriptor(
            val descriptor: BluetoothGattDescriptor,
            val value: ByteArray,
            val purpose: String? = null
        ) : PendingOp
        data class WriteCharacteristic(
            val characteristic: BluetoothGattCharacteristic,
            val value: ByteArray,
            val writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            val purpose: String
        ) : PendingOp
    }

    private val pendingOps = ArrayDeque<PendingOp>()
    private var activeOp: PendingOp? = null
    private var operationInFlight = false
    private var operationTimeout: Runnable? = null
    private var connectionTimeout: Runnable? = null

    init {
        registerBondReceiver()
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        r2706MicReset("new-connect")
        outputR21MtuRequested = false
        outputR21MtuBytes = 23
        outputR21MtuResult = "not-requested"
        audioR26RunId += 1
        audioR26Active = null
        _audioR26Live.value = com.atomosremote.nativeapp.audio.AudioR26LiveSnapshot()
        _r2697MicLineEvidence.value = com.atomosremote.nativeapp.audio.AudioR2697MicLineSnapshot()
        targetDevice = device
        address = device.address
        retryCount = 0
        values.clear()
        asciiLineBuffer.clear()
        _snapshot.value = null
        _tcsPairingState.value = TcsPairingState()
        _tcsRemoteState.value = TcsRemoteState()
        _connectedRecorderName.value = null
        inputExtendedTimeout?.let { mainHandler.removeCallbacks(it) }
        inputExtendedTimeout = null
        inputExtendedRunning = false
        inputExtendedActive = null
        inputExtendedValues.clear()
        inputExtendedResponseTypes.clear()
        inputExtendedRawPayloads.clear()
        _inputExtendedSnapshot.value = InputExtendedSnapshot()
        inputSetterReadbackRunnable?.let { mainHandler.removeCallbacks(it) }
        inputSetterReadbackRunnable = null
        pendingInputSetter = null
        _inputSetterState.value = InputSetterState()
        resetRemoteStateSnapshot()
        _zoomSupportedLevels.value = emptyList()
        _zoomCapabilitiesKnown.value = false
        _zoomHardwareLevel.value = null
        _monitorZoomCore.value = MonitorZoomCoreState()
        zoomExpectedLevelAfterSet = null
        lastRequestedZoomPanX = null
        lastRequestedZoomPanY = null
        queuedLiveZoomPanX = null
        queuedLiveZoomPanY = null
        zoomPanSettleGeneration = 0
        zoomAnalogSessionActive = false
        _zoomBoxState.value = null
        _monitorZoomViewport.value = MonitorZoomViewportState()
        _monitorFocusAssist.value = MonitorFocusAssistState()
        _monitorZebraState.value = MonitorZebraState()
        _monitorAssistEffects.value = MonitorAssistEffectsState()
        _monitorScopeState.value = MonitorScopeState()
        _monitorGuideState.value = MonitorGuideState()
        _monitorAnamorphicState.value = MonitorAnamorphicState()
        _monitorLutState.value = MonitorLutState()
        _outputHdrEnabled.value = null
        _outputHdrTypeId.value = null // R13: never show a previous recorder's cached Output value
        _outputGamutId.value = null
        _outputDownscaleRawId.value = null
        _outputDciCropRawId.value = null
        _outputHdmiTimecodeEnabled.value = null
        outputR17ProbeKind = null
        outputR17ProbeExpiresAt = 0L
        outputR17ProbeExpectedResponse = -1
        outputR13ProbeKind = null
        outputR13ProbeExpiresAt = 0L
        outputR13ProbeExpectedResponse = -1
        _monitorDisplayState.value = MonitorDisplayState()
        lutRefreshStage = 0
        lutExpectedSlot = null
        lutExpectedPreview = null
        lutExpectedRecordBurnIn = null
        lutExpectedOutput = null
        lutInitialRetryCount = 0
        scopeRefreshStage = 0
        scopeExpectedEnabled = null
        scopeExpectedMode = null
        scopeExpectedSize = null
        scopeExpectedOpacity = null
        scopeExpectedBrightness = null
        focusRefreshStage = 0
        focusRefreshRetry = 0
        focusExpectedEnabled = null
        focusExpectedType = null
        focusExpectedThreshold = null
        focusExpectedColour = null
        zebraExpectedEnabled = null
        zebraExpectedThreshold = null
        zoomSummaryExpectedLevel = null
        pendingZoomLevelAfterCapability = null
        deferredZoomLevelAfterCapability = null
        zoomRefreshActive = false
        zoomRefreshStage = 0
        zoomRefreshAfterSet = false
        _safeAreaHardwareMode.value = null
        _frameGridHardwareEnabled.value = null
        _anamorphicHardwareMode.value = null
        pendingSafeAreaCycle = false
        deferredSafeAreaMode = null
        pendingFrameGridSet = null
        cancelDiagnosticFollowUp()
        remoteApiVerified = false
        officialLinkedReconnectPending = false
        tcsCommandSequence = 0
        tcsPacketFirstSubpacket = true
        tcsPacketRemoteCredit = false
        tcsPacketLocalFlag = false
        pendingRemotePacket = null
        pendingRemoteFrameWritten = false
        remoteBootstrapQueue.clear()
        remoteBootstrapActive = false
        remoteBootstrapCompleted = false
        remoteBootstrapResponses = 0
        postReadyInputAutoTriggered = false
        postReadyInputAutoTask?.let { mainHandler.removeCallbacks(it) }
        postReadyInputAutoTask = null
        postAcceptProgressEpoch += 1
        postAcceptProgressTask?.let { mainHandler.removeCallbacks(it) }
        postAcceptProgressTask = null
        remoteBootstrapTotal = 0
        connectedLifecycleArmed = false
        connectedFrameCountCccdReady = false
        connectedLifecycleReady = false
        connectedTimecodeReadPending = false
        connectedDeviceNameCountdown = 3
        connectedLastFrameCount = -1
        connectedLastTimecodeReadAtMs = 0L
        connectedDtsm.reset()
        cancelConnectedSyncTimeout()
        remoteEmptyPolls = 0
        remoteResponseStartedAtMs = 0L
        cancelRemoteResponseTimeout()
        cancelRemoteBootstrapSettle()
        remoteRxBuffer.reset()
        stopPacketLinkProcessLoop()
        packetLink18.didDisconnect()
        packetLink18Primed = false
        _disconnectReason.value = null
        updateBondState(device.bondState)

        // ATOMOS documents an application-level Pairing ID flow. Do not block the BLE
        // transport waiting for Android's OS bond state and do not create a bond here.
        openGatt(device)
    }

    /**
     * Restore a previously accepted application-level TCS identity and use the
     * official LINKED CONNECT preflight (cxtic2 name + cxtic4 BCID) to verify it.
     * No createBond(), no pairing write, and no Pairing-ID mutation occur here.
     * Any BCID mismatch is rejected by the existing LINKED preflight and the UI
     * can fall back to a normal fresh Pair flow.
     */
    @SuppressLint("MissingPermission")
    fun connectTrusted(device: BluetoothDevice, pairingCode: String?, ccid: Long, bcid: Long): Boolean {
        if (ccid == 0L || bcid == 0L) return false
        connect(device)
        _tcsPairingState.value = TcsPairingState(
            phase = "accepted",
            pairingCode = pairingCode,
            ccid = ccid,
            bcid = bcid,
            canAccept = false,
            userAccepted = true,
            connectNameWritten = true,
            ccidWritten = true,
            acceptWritten = true,
            lastError = null
        )
        officialLinkedReconnectPending = true
        appendEvent("TCS19.11.6 TRUSTED CONNECT armed for ${device.address}; LINKED preflight must validate BCID before PacketLink")
        return true
    }

    @SuppressLint("MissingPermission")
    fun retry() {
        val device = targetDevice ?: return
        closeGattOnly()
        retryCount = 0
        _disconnectReason.value = null
        updateBondState(device.bondState)
        openGatt(device)
    }

    @SuppressLint("MissingPermission")
    private fun openGatt(device: BluetoothDevice) {
        closeGattOnly()
        pendingOps.clear()
        activeOp = null
        operationInFlight = false
        prePairCaptureStartedForConnection = false
        passivePairCaptureStartedForConnection = false
        _state.value = "connecting"
        appendEvent("GATT connect -> ${device.address}, osBond=${bondLabel(device.bondState)}")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, callback)
        }
        armConnectionTimeout(device)
    }

    @SuppressLint("MissingPermission")
    fun clearAndroidBondAndRetry(): Boolean {
        val device = targetDevice ?: return false
        closeGattOnly()
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            appendEvent("Android OS bond already clear; reconnecting")
            mainHandler.postDelayed({ openGatt(device) }, 500L)
            return true
        }
        val removed = runCatching {
            val method = device.javaClass.getMethod("removeBond")
            method.invoke(device) as? Boolean ?: false
        }.getOrDefault(false)
        appendEvent(if (removed) "Requested removal of stale Android OS bond" else "Could not remove Android OS bond programmatically; forget the device in Android Bluetooth settings")
        if (removed) {
            mainHandler.postDelayed({ openGatt(device) }, 1400L)
        }
        return removed
    }

    private fun armConnectionTimeout(device: BluetoothDevice) {
        cancelConnectionTimeout()
        val task = Runnable {
            if (_state.value != "connecting" && _state.value != "retrying") return@Runnable
            appendEvent("BLE connect timeout after 8 seconds -> ${device.address}")
            _disconnectReason.value = "BLE connect timeout after 8 seconds"
            _state.value = "error:connect-timeout"
            closeGattOnly()
        }
        connectionTimeout = task
        mainHandler.postDelayed(task, 8_000L)
    }

    private fun cancelConnectionTimeout() {
        connectionTimeout?.let { mainHandler.removeCallbacks(it) }
        connectionTimeout = null
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        r2706MicReset("disconnect")
        pendingOps.clear()
        activeOp = null
        operationInFlight = false
        closeGattOnly()
        _disconnectReason.value = null
        _state.value = "idle"
    }

    fun close() {
        disconnect()
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(bondReceiver) }
            receiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGattOnly() {
        cancelOperationTimeout()
        cancelConnectionTimeout()
        cancelRemoteResponseTimeout()
        cancelRemoteBootstrapSettle()
        cancelWl32NetworkReadback()
        cancelConnectedSyncTimeout()
        connectedLifecycleArmed = false
        connectedFrameCountCccdReady = false
        connectedLifecycleReady = false
        connectedTimecodeReadPending = false
        connectedDeviceNameCountdown = 3
        connectedLastFrameCount = -1
        connectedLastTimecodeReadAtMs = 0L
        connectedDtsm.reset()
        remoteRxBuffer.reset()
        stopPacketLinkProcessLoop()
        pendingRemotePacket = null
        pendingRemoteFrameWritten = false
        audioR26RunId += 1
        audioR26Active = null
        _audioR26Live.value = com.atomosremote.nativeapp.audio.AudioR26LiveSnapshot()
        _r2697MicLineEvidence.value = com.atomosremote.nativeapp.audio.AudioR2697MicLineSnapshot()
        activeOp = null
        operationInFlight = false
        val current = gatt
        gatt = null
        if (current != null) {
            runCatching { current.disconnect() }
            runCatching { current.close() }
        }
    }

    @SuppressLint("MissingPermission")
    fun write(serviceUuid: String, characteristicUuid: String, value: ByteArray, withResponse: Boolean): Boolean {
        val current = gatt ?: return false
        if (_state.value != "services-ready") return false
        if (operationInFlight || pendingOps.isNotEmpty()) return false
        if (serviceUuid.equals(AIRGLU_SHOGUN_SERVICE_UUID, ignoreCase = true) &&
            value.firstOrNull()?.toInt()?.and(0xFF) == '#'.code
        ) {
            appendEvent("18.0 SAFETY BLOCK: refused blind ASCII command on proprietary 9383 AirGlu service (${value.size} byte(s)); passive capture stays connected")
            return false
        }
        val service = runCatching { current.getService(UUID.fromString(serviceUuid)) }.getOrNull() ?: return false
        val characteristic = runCatching { service.getCharacteristic(UUID.fromString(characteristicUuid)) }.getOrNull() ?: return false
        val writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        characteristic.writeType = writeType
        appendEvent("Explicit write -> ${characteristic.uuid}, ${value.size} byte(s)")
        return if (Build.VERSION.SDK_INT >= 33) {
            current.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.value = value
                current.writeCharacteristic(characteristic)
            }
        }
    }

    /**
     * Passive service enumeration. No reads, no notification subscriptions and no writes.
     * This is intentionally the default immediately after connection.
     */
    private fun inspectServicesPassive(g: BluetoothGatt) {
        pendingOps.clear()
        activeOp = null
        operationInFlight = false
        val infos = mutableListOf<GattCharacteristicInfo>()

        g.services.forEach { service ->
            service.characteristics.forEach { c ->
                val p = c.properties
                val descriptorUuids = c.descriptors.map { it.uuid.toString() }
                infos += GattCharacteristicInfo(
                    serviceUuid = service.uuid.toString(),
                    characteristicUuid = c.uuid.toString(),
                    readable = p and BluetoothGattCharacteristic.PROPERTY_READ != 0,
                    writable = p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0,
                    writableNoResponse = p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0,
                    notifiable = p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0,
                    indicatable = p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
                    properties = p,
                    permissions = c.permissions,
                    descriptorUuids = descriptorUuids
                )
                appendEvent(
                    "GATT MAP service=${service.uuid} char=${c.uuid} props=0x${p.toString(16)} perms=0x${c.permissions.toString(16)} desc=${descriptorUuids.joinToString(",").ifBlank { "none" }}"
                )
            }
        }

        _snapshot.value = GattSnapshot(
            deviceAddress = address,
            characteristics = infos,
            serviceUuids = g.services.map { it.uuid.toString() }
        )
        _state.value = "services-ready"
        val serviceSummary = g.services.joinToString(" | ") { service ->
            "${service.uuid}[${service.characteristics.size}]"
        }
        appendEvent("ACTIVE AG2 GATT ready: ${g.services.size} services / ${infos.size} characteristics mapped; vendor characteristic writes are locked; passive capture may arm CCCD only")
        appendEvent("GATT SERVICES: ${serviceSummary.ifBlank { "none" }}")
    }


    /**
     * Official AtomRemote / TCSiOSC pairing flow recovered from the user supplied
     * macOS AtomRemote 1.2.5 bundle:
     *
     *  1. Read ID slots (cxtic 6)
     *  2. Write 9-digit Connect Name, NUL padded to 12 bytes (cxtic 3)
     *  3. Generate non-zero/non-0xffffffff CCID and write U32 little-endian (cxtic 5)
     *  4. Wait for/read BCID U32 little-endian (cxtic 4)
     *  5. User compares the same visible 9-digit code on app + recorder
     *  6. Accept by writing bitwise-NOT CCID to cxtic 5, little-endian
     *
     * This is application-level AirGlu pairing. Android createBond() is not part of
     * the official gate and is never forced here.
     */
    @SuppressLint("MissingPermission")
    fun beginOfficialTcsPairing(): Boolean {
        val current = gatt ?: return false
        if (_state.value != "services-ready") {
            appendEvent("TCS18 START blocked: GATT state=${_state.value}, expected services-ready")
            return false
        }

        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
        if (service == null) {
            failTcsPairing("Official 9383 AirGlu service not found")
            return false
        }

        val slots = service.getCharacteristic(UUID.fromString(TCS_ID_SLOTS_UUID))
        val connectName = service.getCharacteristic(UUID.fromString(TCS_CONNECT_NAME_UUID))
        val ccid = service.getCharacteristic(UUID.fromString(TCS_CCID_UUID))
        val bcid = service.getCharacteristic(UUID.fromString(TCS_BCID_UUID))
        if (slots == null || connectName == null || ccid == null || bcid == null) {
            failTcsPairing(
                "Official TCS pairing characteristic missing: " +
                    "slots=${slots != null} connectName=${connectName != null} ccid=${ccid != null} bcid=${bcid != null}"
            )
            return false
        }

        pendingOps.clear()
        activeOp = null
        operationInFlight = false
        cancelOperationTimeout()

        val rawCode = generateNineDigitConnectName()
        val displayCode = formatNineDigitCode(rawCode)
        _tcsPairingState.value = TcsPairingState(
            phase = "arming-bcid",
            pairingCode = displayCode
        )
        _snapshot.value = _snapshot.value?.copy(
            pairingId = displayCode,
            binaryPairingCandidates = emptyList(),
            pairingDecodeEvidence = listOf(
                "official-tcs: app-generated 9-digit Connect Name",
                "official-tcs: ID_SLOTS -> CONNECT_NAME -> CCID -> BCID -> ~CCID"
            )
        )
        PairingRuntimeGate.seedCapturedId(address, displayCode)
        appendEvent("TCS18 START code=$displayCode (official app-generated Connect Name; 9 independent decimal digits)")

        // Official TCS waits for BCID changes. Arm its CCCD first when available so the
        // transition cannot be missed while the initial pairing writes are running.
        val localNotify = runCatching { current.setCharacteristicNotification(bcid, true) }.getOrDefault(false)
        val cccd = bcid.getDescriptor(CCCD_UUID)
        if (localNotify && cccd != null) {
            val enable = if (bcid.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            pendingOps.addLast(PendingOp.WriteDescriptor(cccd, enable, TCS_PURPOSE_BCID_CCCD))
        } else {
            appendEvent("TCS18 BCID CCCD not available; pairing will poll/read BCID after CCID write")
        }

        pendingOps.addLast(PendingOp.Read(slots, TCS_PURPOSE_ID_SLOTS))
        _tcsPairingState.value = _tcsPairingState.value.copy(phase = "reading-id-slots")
        _state.value = "tcs-pairing"
        processNext(current)
        return true
    }

    @SuppressLint("MissingPermission")
    fun acceptOfficialTcsPairing(): Boolean {
        val current = gatt ?: return false
        val state = _tcsPairingState.value

        // AGENT7 20.03.4.4 QUEUED ACCEPT INTENT
        //
        // The UI already validates the displayed 9-digit code against the active
        // recorder session before entering this function. On a fresh pairing the
        // user can confirm that code a few milliseconds before the CCID write callback
        // flips canAccept=true. The old implementation rejected that click and lost
        // the user's acceptance, so ~CCID, CONNECTED, PacketLink, Record/Play and
        // Input never opened until an app reconnect.
        //
        // Persist the validated user intent immediately. The existing official
        // CCID -> BCID -> ~CCID ordering remains unchanged: enqueueOfficialAccept()
        // is still the only function allowed to write ~CCID and it still requires CCID.
        val ccidReady = state.ccid != null
        val bcidReady = state.bcid != null && state.bcid != 0L
        val canWriteNow = state.canAccept && ccidReady && bcidReady

        _tcsPairingState.value = state.copy(
            phase = when {
                canWriteNow -> "writing-accept"
                !ccidReady -> "accepted-waiting-for-ccid"
                !bcidReady -> "accepted-waiting-for-bcid"
                else -> state.phase
            },
            canAccept = if (canWriteNow) false else state.canAccept,
            userAccepted = true,
            lastError = null
        )
        appendEvent(
            "TCS20.03.4.4 ACCEPT INTENT queued " +
                "phase=${state.phase} canAccept=${state.canAccept} " +
                "ccid=$ccidReady bcid=$bcidReady"
        )
        appendEvent("TCS18 USER ACCEPT recorded for code=${state.pairingCode}; BCID=${state.bcid?.let { hexU32(it) } ?: "waiting"}")
        armPostAcceptProgressWatchdog("user-accept")

        // The official TCS manager records the UI accept flag first. If the
        // handshake is already ready, write ~CCID now. Otherwise the existing
        // CCID/BCID callbacks below will consume this queued acceptance intent.
        val accepted = _tcsPairingState.value
        if (accepted.ccid == null || accepted.bcid == null || accepted.bcid == 0L || !accepted.canAccept) {
            return true
        }
        return enqueueOfficialAccept(current)
    }

    private fun enqueueOfficialAccept(current: BluetoothGatt? = gatt): Boolean {
        val link = current ?: return false
        val state = _tcsPairingState.value
        val ccid = state.ccid ?: return false
        if (!state.userAccepted || state.acceptWritten) return false
        if (pendingOps.any { it is PendingOp.WriteCharacteristic && it.purpose == TCS_PURPOSE_ACCEPT }) return true

        val service = link.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return false
        val ccidChar = service.getCharacteristic(UUID.fromString(TCS_CCID_UUID)) ?: return false
        val accept = ccid xor 0xFFFF_FFFFL
        val payload = u32Le(accept)
        _tcsPairingState.value = state.copy(phase = "writing-accept", canAccept = false, lastError = null)
        _state.value = "tcs-pairing"
        pendingOps.addLast(
            PendingOp.WriteCharacteristic(
                ccidChar,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                TCS_PURPOSE_ACCEPT
            )
        )
        appendEvent("TCS18 ACCEPT -> write ~CCID=${hexU32(accept)} LE=${payload.toHex()} to $TCS_CCID_UUID")
        if (!operationInFlight) processNext(link)
        return true
    }

    private fun generateNineDigitConnectName(): String = buildString(9) {
        repeat(9) { append(('0'.code + secureRandom.nextInt(10)).toChar()) }
    }

    private fun formatNineDigitCode(raw: String): String =
        raw.filter(Char::isDigit).take(9).chunked(3).joinToString("-")

    private fun connectNamePayload(displayCode: String): ByteArray {
        val digits = displayCode.filter(Char::isDigit).take(9)
        val payload = ByteArray(12)
        digits.toByteArray(Charsets.US_ASCII).copyInto(payload, endIndex = digits.length)
        return payload
    }

    private fun generateOfficialCcid(): Long {
        while (true) {
            val candidate = secureRandom.nextInt().toLong() and 0xFFFF_FFFFL
            if (candidate != 0L && candidate != 0xFFFF_FFFFL) return candidate
        }
    }

    private fun u16Le(value: ByteArray): Int {
        if (value.size < 2) return 0
        return (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
    }

    private fun u32Le(value: ByteArray): Long {
        if (value.size < 4) return 0L
        return (value[0].toLong() and 0xFF) or
            ((value[1].toLong() and 0xFF) shl 8) or
            ((value[2].toLong() and 0xFF) shl 16) or
            ((value[3].toLong() and 0xFF) shl 24)
    }

    private fun u32Le(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte()
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
    private fun hexU32(value: Long): String = "0x%08X".format(value and 0xFFFF_FFFFL)

    private fun failTcsPairing(message: String) {
        pendingOps.clear()
        activeOp = null
        operationInFlight = false
        cancelOperationTimeout()
        _tcsPairingState.value = _tcsPairingState.value.copy(
            phase = "error",
            canAccept = false,
            lastError = message
        )
        _state.value = "services-ready"
        appendEvent("TCS18 ERROR: $message")
    }

    private fun handleTcsReadPurpose(g: BluetoothGatt, op: PendingOp.Read, value: ByteArray, status: Int): Boolean {
        val purpose = op.purpose ?: return false
        if (!purpose.startsWith("tcs-")) return false
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failTcsPairing("$purpose read failed status=$status")
            return true
        }

        when (purpose) {
            TCS_PURPOSE_ID_SLOTS -> {
                val slots = u16Le(value)
                appendEvent("TCS18 READ ID_SLOTS -> $slots (raw=${value.toHex()})")
                if (slots <= 0) {
                    failTcsPairing("Recorder reports no free pairing ID slots")
                    return true
                }
                val state = _tcsPairingState.value.copy(
                    phase = "writing-connect-name",
                    slotsAvailable = slots,
                    lastError = null
                )
                _tcsPairingState.value = state
                val service = g.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
                val connectName = service?.getCharacteristic(UUID.fromString(TCS_CONNECT_NAME_UUID))
                if (connectName == null) {
                    failTcsPairing("Connect Name characteristic disappeared")
                    return true
                }
                val payload = connectNamePayload(state.pairingCode.orEmpty())
                appendEvent(
                    "TCS18 WRITE CONNECT_NAME code=${state.pairingCode} bytes=${payload.toHex()} uuid=$TCS_CONNECT_NAME_UUID"
                )
                pendingOps.addFirst(
                    PendingOp.WriteCharacteristic(
                        connectName,
                        payload,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                        TCS_PURPOSE_CONNECT_NAME
                    )
                )
            }

            TCS_PURPOSE_BCID_READ -> {
                val bcid = u32Le(value)
                if (bcid != 0L) {
                    onOfficialBcid(bcid, "read")
                } else {
                    appendEvent("TCS18 BCID read returned 0; waiting for notification")
                }
            }
        }
        return true
    }

    private fun handleTcsWritePurpose(g: BluetoothGatt, op: PendingOp.WriteCharacteristic, status: Int): Boolean {
        if (!op.purpose.startsWith("tcs-")) return false
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failTcsPairing("${op.purpose} write failed status=$status")
            return true
        }

        when (op.purpose) {
            TCS_PURPOSE_CONNECT_NAME -> {
                val ccid = generateOfficialCcid()
                val state = _tcsPairingState.value.copy(
                    phase = "writing-ccid",
                    connectNameWritten = true,
                    ccid = ccid,
                    lastError = null
                )
                _tcsPairingState.value = state
                val service = g.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
                val ccidChar = service?.getCharacteristic(UUID.fromString(TCS_CCID_UUID))
                if (ccidChar == null) {
                    failTcsPairing("CCID characteristic disappeared")
                    return true
                }
                val payload = u32Le(ccid)
                appendEvent("TCS18 WRITE CCID=${hexU32(ccid)} LE=${payload.toHex()} uuid=$TCS_CCID_UUID")
                pendingOps.addFirst(
                    PendingOp.WriteCharacteristic(
                        ccidChar,
                        payload,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                        TCS_PURPOSE_CCID
                    )
                )
            }

            TCS_PURPOSE_CCID -> {
                val state = _tcsPairingState.value.copy(
                    phase = "waiting-for-bcid",
                    ccidWritten = true,
                    canAccept = true,
                    lastError = null
                )
                _tcsPairingState.value = state
                appendEvent("TCS18 CAN_ACCEPT: CCID write complete; waiting for BCID and user code confirmation")

                // AGENT7 20.03.4.4 QUEUED ACCEPT INTENT
                // If the user already confirmed the matching code and BCID arrived
                // before this callback, do not require a second tap.
                val now = _tcsPairingState.value
                if (now.userAccepted && now.bcid != null && now.bcid != 0L && !now.acceptWritten) {
                    appendEvent("TCS20.03.4.4 ACCEPT INTENT consume-at-ccid")
                    enqueueOfficialAccept(g)
                } else {
                    val service = g.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
                    val bcid = service?.getCharacteristic(UUID.fromString(TCS_BCID_UUID))
                    if (bcid != null && bcid.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                        pendingOps.addFirst(PendingOp.Read(bcid, TCS_PURPOSE_BCID_READ))
                    }
                }
            }

            TCS_PURPOSE_ACCEPT -> {
                _tcsPairingState.value = _tcsPairingState.value.copy(
                    phase = "accepted",
                    canAccept = false,
                    acceptWritten = true,
                    lastError = null
                )

                // AGENT7 20.03.4.13 FRESH PAIR -> TRUSTED RECONNECT
                //
                // User-proven invariant:
                // closing/reopening the app with this SAME accepted TCS identity
                // produces the stable session in which Record/Stop/Play/Pause work.
                //
                // Do exactly that internally after ~CCID succeeds. Do NOT run the
                // separate CONNECTED lifecycle on the pairing GATT.
                val accepted = _tcsPairingState.value
                val acceptedDevice = targetDevice ?: g.device
                val acceptedCcid = accepted.ccid ?: 0L
                val acceptedBcid = accepted.bcid ?: 0L

                appendEvent(
                    "TCS20.03.4.13 ACCEPT COMPLETE -> TRUSTED RECONNECT " +
                        "ccid=${hexU32(acceptedCcid)} bcid=${hexU32(acceptedBcid)}"
                )

                // Kill watchdogs/tasks tied to the pairing GATT before connectTrusted().
                postAcceptProgressEpoch += 1
                postAcceptProgressTask?.let { mainHandler.removeCallbacks(it) }
                postAcceptProgressTask = null
                connectedSyncTimeout?.let { mainHandler.removeCallbacks(it) }
                connectedSyncTimeout = null

                if (acceptedCcid == 0L || acceptedBcid == 0L) {
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        phase = "error",
                        available = false,
                        linked = false,
                        lastStatus = "Pairing accepted but trusted identity is incomplete",
                        lastError = "CCID/BCID unavailable after accept"
                    )
                    appendEvent("TCS20.03.4.13 TRUSTED RECONNECT blocked: retained CCID/BCID missing")
                } else {
                    mainHandler.postDelayed({
                        val started = connectTrusted(
                            device = acceptedDevice,
                            pairingCode = accepted.pairingCode,
                            ccid = acceptedCcid,
                            bcid = acceptedBcid
                        )
                        appendEvent("TCS20.03.4.13 TRUSTED RECONNECT STARTED=$started source=fresh-accept")
                    }, 220L)
                }
            }
        }
        return true
    }

    private fun onOfficialBcid(bcid: Long, source: String) {
        if (bcid == 0L) return
        val current = _tcsPairingState.value
        _tcsPairingState.value = current.copy(
            phase = when {
                current.userAccepted -> "writing-accept"
                current.canAccept -> "can-accept"
                else -> current.phase
            },
            bcid = bcid,
            lastError = null
        )
        appendEvent("TCS18 BCID <- ${hexU32(bcid)} source=$source")
        if (_tcsPairingState.value.userAccepted && !_tcsPairingState.value.acceptWritten) {
            appendEvent("TCS20.03.4.4 ACCEPT INTENT consume-at-bcid source=$source")
            enqueueOfficialAccept()
        }
    }


    /**
     * Official post-pair CONNECTED lifecycle recovered from TCSiOSC.framework.
     *
     * macOS order after successful ~CCID:
     *   CONNECTED_Enter
     *   -> subscribe cxtic0 frame-count updates
     *   -> CONNECTED_WaitingForFrameCount
     *   -> read cxtic1 timecode
     *   -> require 16 bytes and byte[0] != 0
     *   -> then PacketLink becomes eligible for the Remote screen
     *
     * 18.5 skipped these states and declared the recorder linked too early.
     */
    // AGENT7 20.03.4.3 POST-ACCEPT PROGRESS WATCHDOG
    private fun armPostAcceptProgressWatchdog(reason: String) {
        postAcceptProgressTask?.let { mainHandler.removeCallbacks(it) }
        postAcceptProgressEpoch += 1
        val epoch = postAcceptProgressEpoch
        var checks = 0

        lateinit var task: Runnable
        task = Runnable {
            if (epoch != postAcceptProgressEpoch) return@Runnable

            val pairing = _tcsPairingState.value
            checks += 1

            if (!pairing.userAccepted) {
                postAcceptProgressTask = null
                appendEvent("TCS20.03.4.3 POST-ACCEPT stop: userAccepted=0 reason=$reason")
                return@Runnable
            }

            when {
                !pairing.acceptWritten -> {
                    val hasBcid = pairing.bcid != null && pairing.bcid != 0L
                    if (hasBcid) {
                        val queued = enqueueOfficialAccept()
                        if (checks == 1 || checks % 5 == 0) {
                            appendEvent(
                                "TCS20.03.4.3 POST-ACCEPT ensure-accept checks=$checks queued=$queued " +
                                    "phase=${pairing.phase}"
                            )
                        }
                    } else if (checks == 1 || checks % 5 == 0) {
                        appendEvent(
                            "TCS20.03.4.3 POST-ACCEPT waiting-bcid checks=$checks phase=${pairing.phase}"
                        )
                    }
                }

                !connectedLifecycleReady -> {
                    val started = beginOfficialConnectedLifecycle()
                    if (checks == 1 || checks % 5 == 0) {
                        appendEvent(
                            "TCS20.03.4.3 POST-ACCEPT ensure-connected checks=$checks started=$started " +
                                "phase=${_tcsRemoteState.value.phase}"
                        )
                    }
                }

                !packetLink18Primed -> {
                    val opened = openOfficialTcsRemoteLink(autoProbe = true)
                    if (checks == 1 || checks % 5 == 0) {
                        appendEvent(
                            "TCS20.03.4.3 POST-ACCEPT ensure-packetlink checks=$checks opened=$opened " +
                                "phase=${_tcsRemoteState.value.phase}"
                        )
                    }
                }

                else -> {
                    postAcceptProgressTask = null
                    appendEvent(
                        "TCS20.03.4.3 POST-ACCEPT COMPLETE checks=$checks " +
                            "primed=$packetLink18Primed phase=${_tcsRemoteState.value.phase}"
                    )
                    // 20.03.4.2's auto-input hook may still be waiting on bootstrap settle.
                    // Once PacketLink is genuinely primed, arm one independent idle-wait
                    // fallback so Input can populate without an app reopen.
                    schedulePrimedInputFallback()
                    return@Runnable
                }
            }

            if (checks < 100) {
                mainHandler.postDelayed(task, 300L)
            } else {
                postAcceptProgressTask = null
                appendEvent(
                    "TCS20.03.4.3 POST-ACCEPT TIMEOUT accept=${pairing.acceptWritten} " +
                        "connected=$connectedLifecycleReady primed=$packetLink18Primed " +
                        "remotePhase=${_tcsRemoteState.value.phase}"
                )
            }
        }

        postAcceptProgressTask = task
        appendEvent("TCS20.03.4.3 POST-ACCEPT ARMED reason=$reason epoch=$epoch")
        mainHandler.postDelayed(task, 120L)
    }

    // PacketLink-prime is the earliest proven point at which the three existing
    // granular Input GETs are legal. Wait until bootstrap and ATT traffic are idle,
    // then launch exactly one existing collector. No new RemoteApi command is added.
    private fun schedulePrimedInputFallback() {
        if (postReadyInputAutoTriggered || inputCollectorRunning) return
        var checks = 0
        lateinit var task: Runnable
        task = Runnable {
            if (postReadyInputAutoTriggered || inputCollectorRunning) return@Runnable
            checks += 1

            val idle =
                packetLink18Primed &&
                _tcsPairingState.value.acceptWritten &&
                _tcsRemoteState.value.available &&
                !remoteBootstrapActive &&
                pendingRemotePacket == null &&
                pendingOps.isEmpty() &&
                !operationInFlight

            if (idle) {
                // INPUT06C: MAC13 typed values are the source of truth. Do not duplicate
                // a fully populated bootstrap snapshot with a second collector.
                val confirmedKeys = listOf("source", "hdrState", "deviceName")
                if (confirmedKeys.all { key ->
                        inputCollectorValues[key]?.let { it.isNotBlank() &&
                            !it.startsWith("NO_RESPONSE") && !it.startsWith("REJECT") } == true
                    }) {
                    postReadyInputAutoTriggered = true
                    appendEvent("INPUT06C PRIMED REUSE confirmed=3/3; no duplicate GET")
                    publishInputCodecCollector(running = false, source = "input06c-bootstrap-ready")
                    return@Runnable
                }
                postReadyInputAutoTriggered = true
                appendEvent("TCS20.03.4.3 PRIMED INPUT FALLBACK fire checks=$checks")
                val started = refreshGranularInputState()
                appendEvent("TCS20.03.4.3 PRIMED INPUT FALLBACK started=$started")
                if (!started) {
                    postReadyInputAutoTriggered = false
                    mainHandler.postDelayed({ schedulePrimedInputFallback() }, 400L)
                }
            } else if (checks < 80) {
                if (checks == 1 || checks % 10 == 0) {
                    appendEvent(
                        "TCS20.03.4.3 PRIMED INPUT FALLBACK wait checks=$checks " +
                            "primed=$packetLink18Primed bootstrap=$remoteBootstrapActive " +
                            "pending=${pendingRemotePacket?.label} attBusy=$operationInFlight queue=${pendingOps.size}"
                    )
                }
                mainHandler.postDelayed(task, 300L)
            } else {
                appendEvent(
                    "TCS20.03.4.3 PRIMED INPUT FALLBACK timeout " +
                        "primed=$packetLink18Primed bootstrap=$remoteBootstrapActive " +
                        "pending=${pendingRemotePacket?.label} attBusy=$operationInFlight queue=${pendingOps.size}"
                )
            }
        }
        mainHandler.postDelayed(task, 160L)
    }

    @SuppressLint("MissingPermission")
    private fun beginOfficialConnectedLifecycle(): Boolean {
        val current = gatt ?: return false
        if (!_tcsPairingState.value.acceptWritten) {
            appendEvent("TCS18.6 CONNECTED blocked: official ~CCID accept is not complete")
            return false
        }
        if (connectedLifecycleReady) {
            return openOfficialTcsRemoteLink(autoProbe = true)
        }

        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
        val frameCount = service?.getCharacteristic(UUID.fromString(TCS_FRAME_COUNT_UUID))
        val timecode = service?.getCharacteristic(UUID.fromString(TCS_TIMECODE_UUID))
        if (frameCount == null || timecode == null) {
            connectedLifecycleArmed = false
            connectedFrameCountCccdReady = false
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                phase = "error",
                available = false,
                linked = false,
                lastStatus = "Recorder connected-session endpoints are unavailable",
                lastError = "Official TCS CONNECTED characteristics missing"
            )
            appendEvent("TCS18.6 CONNECTED failed: cxtic0=${frameCount != null} cxtic1=${timecode != null}")
            return false
        }

        val canNotify = frameCount.properties and
            (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        val canReadTimecode = timecode.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
        if (!canNotify || !canReadTimecode) {
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                phase = "error",
                available = false,
                linked = false,
                lastStatus = "Recorder connected-session properties are unavailable",
                lastError = "Official TCS CONNECTED properties invalid (frameNotify=$canNotify timecodeRead=$canReadTimecode)"
            )
            appendEvent("TCS18.6 CONNECTED failed properties: cxtic0Notify=$canNotify cxtic1Read=$canReadTimecode")
            return false
        }

        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = "connected-sync",
                    available = _tcsRemoteState.value.available,
                    linked = _tcsRemoteState.value.linked,
            lastStatus = "Synchronizing official TCS connected session",
            lastError = null
        )
        stopPacketLinkProcessLoop()
        cancelRemoteResponseTimeout()
        cancelRemoteBootstrapSettle()
        pendingRemotePacket = null
        pendingRemoteFrameWritten = false

        if (connectedFrameCountCccdReady) {
            armConnectedSyncTimeout()
            appendEvent("TCS18.6 CONNECTED waiting for recorder frame clock on cxtic0")
            return true
        }
        if (connectedLifecycleArmed) return true

        val localNotify = runCatching { current.setCharacteristicNotification(frameCount, true) }.getOrDefault(false)
        val cccd = frameCount.getDescriptor(CCCD_UUID)
        if (!localNotify || cccd == null) {
            connectedLifecycleArmed = false
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                phase = "error",
                available = false,
                linked = false,
                lastStatus = "Could not arm recorder frame clock",
                lastError = "cxtic0 notification subscription unavailable"
            )
            appendEvent("TCS18.6 CONNECTED cxtic0 subscription unavailable: local=$localNotify cccd=${cccd != null}")
            return false
        }

        // CoreBluetooth's setNotifyValue maps to a notification subscription when
        // PROPERTY_NOTIFY exists. cxtic0 advertises both notify and indicate; prefer
        // notify to mirror the continuous frame-count update path.
        val enable = if (frameCount.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        connectedLifecycleArmed = true
        connectedFrameCountCccdReady = false
        pendingOps.addFirst(PendingOp.WriteDescriptor(cccd, enable, TCS_PURPOSE_CONNECTED_FRAMECOUNT_CCCD))
        appendEvent("TCS18.6 CONNECTED ENTER: subscribing cxtic0=$TCS_FRAME_COUNT_UUID frame-count updates before PacketLink")
        if (!operationInFlight) processNext(current)
        return true
    }

    private fun armConnectedSyncTimeout() {
        cancelConnectedSyncTimeout()
        val task = Runnable {
            connectedSyncTimeout = null
            if (connectedLifecycleReady || !_tcsPairingState.value.acceptWritten) return@Runnable
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                phase = "waiting-frame-clock",
                available = false,
                linked = false,
                lastStatus = "Waiting for recorder frame clock",
                lastError = null
            )
            appendEvent("TCS18.6 CONNECTED still waiting for cxtic0 frame-count notification; PacketLink remains gated")
        }
        connectedSyncTimeout = task
        mainHandler.postDelayed(task, CONNECTED_SYNC_WAIT_MS)
    }

    private fun cancelConnectedSyncTimeout() {
        connectedSyncTimeout?.let { mainHandler.removeCallbacks(it) }
        connectedSyncTimeout = null
    }

    @SuppressLint("MissingPermission")
    private fun onOfficialConnectedFrameCount(g: BluetoothGatt, value: ByteArray) {
        if (!_tcsPairingState.value.acceptWritten || value.size < 2) return
        val frameCount = u16Le(value)
        connectedLastFrameCount = frameCount
        appendEvent("TCS18.6 CONNECTED FRAMECOUNT <- $frameCount raw=${value.toHex()}")

        if (connectedTimecodeReadPending) return
        if (connectedDtsm.isCycleBusy()) return
        // Once Remote is active, keep the official CONNECTED heartbeat alive without
        // flooding Android's single ATT queue. Initial synchronization is immediate;
        // subsequent timecode reads are rate-limited while PacketLink owns the queue.
        val now = System.currentTimeMillis()
        if (connectedLifecycleReady && now - connectedLastTimecodeReadAtMs < CONNECTED_TIMECODE_REFRESH_MS) return

        val timecode = g.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
            ?.getCharacteristic(UUID.fromString(TCS_TIMECODE_UUID)) ?: return
        connectedTimecodeReadPending = true
        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = if (connectedLifecycleReady) _tcsRemoteState.value.phase else "reading-timecode",
            available = connectedLifecycleReady && _tcsRemoteState.value.available,
            linked = connectedLifecycleReady && _tcsRemoteState.value.linked,
            lastStatus = if (connectedLifecycleReady) {
                _tcsRemoteState.value.lastStatus
            } else {
                "Frame clock received; reading official TCS timecode"
            },
            lastError = null
        )
        pendingOps.addFirst(PendingOp.Read(timecode, TCS_PURPOSE_CONNECTED_TIMECODE))
        if (!operationInFlight) processNext(g)
    }

    @SuppressLint("MissingPermission")
    private fun beginOfficialConnectedDataTransfer(g: BluetoothGatt) {
        if (connectedLifecycleReady || connectedDtsm.isComplete()) return
        if (!connectedDtsm.isEnabled()) {
            appendEvent("TCS18.9 DTSM cycle skipped: disabled by recorder timecode flag")
            return
        }
        if (!connectedDtsm.beginCycle()) return

        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = "connected-dtsm",
            available = false,
            linked = false,
            lastStatus = "Synchronizing official TCS device data",
            lastError = null
        )

        if (connectedDtsm.shouldReadDeviceNameThisCycle()) {
            val nameChar = g.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
                ?.getCharacteristic(UUID.fromString(TCS_DEVICE_NAME_UUID))
            if (nameChar != null &&
                nameChar.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
            ) {
                pendingOps.addFirst(
                    PendingOp.Read(nameChar, TCS_PURPOSE_CONNECTED_DTSM_DEVICE_NAME)
                )
                appendEvent("TCS18.8.6 DTSM ReadingDeviceName -> cxtic2")
                if (!operationInFlight) processNext(g)
                return
            }
        }

        queueOfficialConnectedDataTransferWrite(g)
    }

    @SuppressLint("MissingPermission")
    private fun queueOfficialConnectedDataTransferWrite(g: BluetoothGatt) {
        val request = connectedDtsm.nextWriteRequest()
        if (request == null) {
            connectedDtsm.failCycle("No DTSM write request for stage=${connectedDtsm.stage()}")
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                phase = "error",
                available = false,
                linked = false,
                lastStatus = "Official TCS device-data synchronization failed",
                lastError = "No write request for DTSM stage ${connectedDtsm.stage()}"
            )
            return
        }

        val service = g.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
        val (uuid, purpose) = when (request.kind) {
            TcsConnectedDataTransfer.WriteKind.OFFER_PRIMARY ->
                TCS_DATA_LINK_WRITE_UUID to TCS_PURPOSE_CONNECTED_DTSM_OFFER_WRITE
            TcsConnectedDataTransfer.WriteKind.ACCEPT_SECONDARY ->
                TCS_DATA_LINK_WRITE_UUID to TCS_PURPOSE_CONNECTED_DTSM_ACCEPT_WRITE
            TcsConnectedDataTransfer.WriteKind.MODEL_DATA ->
                TCS_DATA_WRITE_UUID to TCS_PURPOSE_CONNECTED_DTSM_DATA_WRITE
        }

        val c = service?.getCharacteristic(UUID.fromString(uuid))
        if (c == null || c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
            connectedDtsm.failCycle("Required DTSM write cxtic=${request.cxtic} unavailable")
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                phase = "error",
                available = false,
                linked = false,
                lastStatus = "Official TCS device-data synchronization failed",
                lastError = "Required cxtic${request.cxtic} write endpoint unavailable"
            )
            appendEvent("TCS18.8.6 DTSM FAIL missing writable cxtic${request.cxtic} uuid=$uuid")
            return
        }

        pendingOps.addFirst(
            PendingOp.WriteCharacteristic(
                characteristic = c,
                value = request.value,
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                purpose = purpose
            )
        )
        appendEvent(
            "TCS18.8.6 DTSM WRITE cxtic${request.cxtic} kind=${request.kind} " +
                "value=${request.value.toHex()} stage=${connectedDtsm.stage()}"
        )
        if (!operationInFlight) processNext(g)
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectedDataTransferWritePurpose(
        g: BluetoothGatt,
        op: PendingOp.WriteCharacteristic,
        status: Int
    ): Boolean {
        val purpose = op.purpose
        if (!purpose.startsWith("connected-dtsm-")) return false

        if (status != BluetoothGatt.GATT_SUCCESS) {
            connectedDtsm.failCycle("$purpose write failed status=$status")
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                phase = "error",
                available = false,
                linked = false,
                lastStatus = "Official TCS device-data synchronization failed",
                lastError = "$purpose write failed status=$status"
            )
            appendEvent("TCS18.8.6 DTSM WRITE FAIL purpose=$purpose status=$status")
            return true
        }

        val service = g.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))

        when (purpose) {
            TCS_PURPOSE_CONNECTED_DTSM_OFFER_WRITE -> {
                val read = service?.getCharacteristic(UUID.fromString(TCS_DATA_LINK_READ_UUID))
                if (read == null ||
                    read.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0
                ) {
                    connectedDtsm.failCycle("cxtic11 unavailable")
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        phase = "error",
                        available = false,
                        linked = false,
                        lastStatus = "Official TCS model negotiation failed",
                        lastError = "cxtic11 read endpoint unavailable"
                    )
                    return true
                }
                pendingOps.addFirst(
                    PendingOp.Read(read, TCS_PURPOSE_CONNECTED_DTSM_LINK_READ)
                )
                appendEvent("TCS18.8.6 DTSM OFFER PRIMARY written; reading cxtic11")
            }

            TCS_PURPOSE_CONNECTED_DTSM_ACCEPT_WRITE -> {
                connectedDtsm.onSecondaryAccepted()
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = "connected-dtsm",
                    available = false,
                    linked = false,
                    lastStatus = "Official TCS model negotiation accepted; waiting for next recorder frame",
                    lastError = null
                )
                appendEvent("TCS18.8.6 DTSM ACCEPT SECONDARY complete -> CONNECTED; waiting next frame")
                armConnectedSyncTimeout()
            }

            TCS_PURPOSE_CONNECTED_DTSM_DATA_WRITE -> {
                connectedDtsm.onModelWriteComplete()
                val read = service?.getCharacteristic(UUID.fromString(TCS_DATA_READ_UUID))
                if (read == null ||
                    read.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0
                ) {
                    connectedDtsm.failCycle("cxtic9 unavailable")
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        phase = "error",
                        available = false,
                        linked = false,
                        lastStatus = "Official TCS device-data read failed",
                        lastError = "cxtic9 read endpoint unavailable"
                    )
                    return true
                }
                pendingOps.addFirst(
                    PendingOp.Read(read, TCS_PURPOSE_CONNECTED_DTSM_DATA_READ)
                )
                appendEvent("TCS18.8.6 DTSM model TX complete; reading cxtic9")
            }
        }

        return true
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectedDtsmRead(
        g: BluetoothGatt,
        purpose: String,
        value: ByteArray,
        status: Int
    ): Boolean {
        if (!purpose.startsWith("connected-dtsm-")) return false

        if (status != BluetoothGatt.GATT_SUCCESS) {
            connectedDtsm.failCycle("$purpose read failed status=$status")
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                phase = "error",
                available = false,
                linked = false,
                lastStatus = "Official TCS device-data synchronization failed",
                lastError = "$purpose read failed status=$status"
            )
            appendEvent("TCS18.8.6 DTSM READ FAIL purpose=$purpose status=$status")
            return true
        }

        when (purpose) {
            TCS_PURPOSE_CONNECTED_DTSM_DEVICE_NAME -> {
                val name = value.toString(Charsets.UTF_8).trim('\u0000', ' ', '\r', '\n')
                appendEvent(
                    "TCS18.8.6 DTSM DEVICE NAME <- ${name.ifBlank { value.toHex() }} raw=${value.toHex()}"
                )
                if (name.isNotBlank()) _connectedRecorderName.value = name
                queueOfficialConnectedDataTransferWrite(g)
            }

            TCS_PURPOSE_CONNECTED_DTSM_LINK_READ -> {
                val result = connectedDtsm.onLinkRead(value)
                appendEvent(
                    "TCS18.8.6 DTSM LINK READ cxtic11=${value.toHex()} peerModel=${result.peerModelId} " +
                        "stage=${connectedDtsm.stage()} result=${result.message}"
                )
                if (!result.ok) {
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        phase = "error",
                        available = false,
                        linked = false,
                        lastStatus = "Official TCS model negotiation failed",
                        lastError = result.message
                    )
                } else {
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        phase = "connected-dtsm",
                        available = false,
                        linked = false,
                        lastStatus = "Official TCS model negotiation advanced; waiting for next recorder frame",
                        lastError = null
                    )
                    armConnectedSyncTimeout()
                }
            }

            TCS_PURPOSE_CONNECTED_DTSM_DATA_READ -> {
                val result = connectedDtsm.onInboundData(value)
                appendEvent(
                    "TCS18.8.6 DTSM DATA RX cxtic9=${value.toHex()} changed=${result.changedBytes} " +
                        "final=${result.final} result=${result.message}"
                )

                if (!result.ok) {
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        phase = "error",
                        available = false,
                        linked = false,
                        lastStatus = "Official TCS device-data decode failed",
                        lastError = result.message
                    )
                    return true
                }

                if (!result.final) {
                    val read = g.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
                        ?.getCharacteristic(UUID.fromString(TCS_DATA_READ_UUID))
                    if (read == null) {
                        connectedDtsm.failCycle("cxtic9 disappeared during continuation")
                        return true
                    }
                    pendingOps.addFirst(
                        PendingOp.Read(read, TCS_PURPOSE_CONNECTED_DTSM_DATA_READ)
                    )
                    appendEvent("TCS18.8.6 DTSM DATA continuation -> read cxtic9 again")
                    return true
                }

                connectedLifecycleReady = true
                cancelConnectedSyncTimeout()
                val model = connectedDtsm.modelSnapshot()
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = "connected-model-ready",
                    available = false,
                    linked = false,
                    lastStatus = "Official TCS device-data model synchronized",
                    lastError = null
                )
                appendEvent(
                    "TCS18.9 DTSM COMPLETE modelBytes=${model.size} snapshot=${model.toHex()}; " +
                        "official CONNECTED gate complete; opening PacketLink on current BLE session"
                )

                mainHandler.postDelayed({
                    openOfficialTcsRemoteLink(autoProbe = true)
                }, 20L)
            }
        }

        return true
    }
    @SuppressLint("MissingPermission")
    private fun handleConnectedReadPurpose(g: BluetoothGatt, op: PendingOp.Read, value: ByteArray, status: Int): Boolean {
        val purpose = op.purpose ?: return false
        if (!purpose.startsWith("connected-")) return false
        if (purpose.startsWith("connected-dtsm-")) {
            return handleConnectedDtsmRead(g, purpose, value, status)
        }
        when (purpose) {
            TCS_PURPOSE_CONNECTED_TIMECODE -> {
                connectedTimecodeReadPending = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    connectedLifecycleReady = false
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        phase = "error",
                        available = false,
                        linked = false,
                        lastStatus = "Recorder timecode synchronization failed",
                        lastError = "cxtic1 timecode read failed status=$status"
                    )
                    appendEvent("TCS18.6 CONNECTED TIMECODE read failed status=$status")
                    return true
                }
                if (value.size != 16 || value[0].toInt() == 0) {
                    connectedLifecycleReady = false
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        phase = "waiting-frame-clock",
                        available = false,
                        linked = false,
                        lastStatus = "Waiting for a valid recorder timecode frame",
                        lastError = null
                    )
                    appendEvent("TCS18.6 CONNECTED TIMECODE not ready len=${value.size} first=${value.firstOrNull()?.toInt()?.and(0xFF) ?: -1} hex=${value.toHex()}")
                    armConnectedSyncTimeout()
                    return true
                }

                connectedLastTimecodeReadAtMs = System.currentTimeMillis()
                if (!connectedLifecycleReady && !connectedDtsm.isCycleBusy()) {
                    cancelConnectedSyncTimeout()

                    // Exact macOS TCSiOSC behavior:
                    // tcsTimecodeUpdate stores (timecode[12] >> 7) as the
                    // Data Transfer State Machine enable flag, then
                    // CONNECTED_ReadingTimecode calls dtsmSetEnabled(flag).
                    val dataTransferEnabled =
                        value.size > 12 && (value[12].toInt() and 0x80) != 0

                    appendEvent(
                        "TCS18.9 TIMECODE FLAGS byte12=0x%02X dtsm=%s".format(
                            value[12].toInt() and 0xFF,
                            dataTransferEnabled
                        )
                    )

                    if (dataTransferEnabled) {
                        if (!connectedDtsm.isEnabled()) {
                            connectedDtsm.enable()
                            connectedDeviceNameCountdown = 3
                            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                                phase = "connected-dtsm",
                                available = false,
                                linked = false,
                                lastStatus = "Official TCS device-data synchronization enabled by recorder timecode",
                                lastError = null
                            )
                            appendEvent(
                                "TCS18.9 DTSM ENABLED BY TIMECODE primary=${TcsConnectedDataTransfer.PRIMARY_MODEL_ID} " +
                                    "secondary=${TcsConnectedDataTransfer.SECONDARY_MODEL_ID} " +
                                    "blocks=${TcsConnectedDataTransfer.MODEL_BLOCK_COUNT}"
                            )
                        }

                        appendEvent(
                            "TCS18.9 CONNECTED TIMECODE OK len=16 frameCount=$connectedLastFrameCount " +
                                "hex=${value.toHex()}; continuing official DTSM cycle"
                        )
                        beginOfficialConnectedDataTransfer(g)
                    } else {
                        // This is the path observed on Shogun Ultra AG2:
                        // byte[12] high bit is clear, so official TCSDataTransfer
                        // is disabled. cxtic8-11 must not gate Remote availability.
                        connectedDtsm.reset()
                        connectedLifecycleReady = true
                        connectedDeviceNameCountdown = 3
                        cancelConnectedSyncTimeout()

                        _tcsRemoteState.value = _tcsRemoteState.value.copy(
                            phase = "connected-packet-ready",
                            available = false,
                            linked = false,
                            lastStatus = "Official CONNECTED session ready; opening PacketLink on current BLE session",
                            lastError = null
                        )

                        appendEvent(
                            "TCS18.9 DTSM DISABLED BY TIMECODE; CONNECTED major-mode PacketLink is available " +
                                "on the current GATT session; no cxtic10/cxtic11 negotiation required"
                        )

                        mainHandler.postDelayed({
                            openOfficialTcsRemoteLink(autoProbe = true)
                        }, 20L)
                    }
                }
                return true
            }
            TCS_PURPOSE_CONNECTED_DEVICE_NAME -> {
    
            if (status == BluetoothGatt.GATT_SUCCESS) {
                    val name = value.toString(Charsets.UTF_8).trim('\u0000', ' ', '\r', '\n')
                    appendEvent("TCS18.6 CONNECTED DEVICE NAME <- ${name.ifBlank { value.toHex() }}")
                    if (name.isNotBlank()) _connectedRecorderName.value = name
                }
                return true
            }
        }
        return false
    }

    /**
     * 18.5 official TCS Packet Link + macOS RemoteApi process-loop transport.
     *
     * Exact behavior recovered from TCSiOSC TCSPacketLink.c:
     *   cxtic 12 = 232be115-457c-4e46-a24c-e9267071b567 (read)
     *   cxtic 13 = 8a8cc04b-7c4b-4c98-999e-b4ea39164c3a (write)
     *
     * AtomMessage local wire body:
     *   packetId U8 + payloadLen LE16 + messageType LE16 + payload
     *
     * Important 18.3 correction from the macOS executable/framework:
     * TCSPacketLink DidConnect starts first=1, peerCredit=0 and local=0. A normal
     * PacketLink process tick with no buffered command therefore serializes F0,
     * not F8. F0 is the session prime: first/final/initial + RX-space, non-local.
     * After cxtic12 returns E0 (peer TX credit bit 0x20), RemoteApi one-shot local
     * commands serialize as E8 + AtomMessage. This keeps link setup separate from
     * the Record/Stop/Play command packet, matching the framework state machine.
     */
    /**
     * 18.8.2 official LINKED transition.
     *
     * TCSiOSC does not reuse the TRYPAIR/CONNECTED GATT instance for LINKED.
     * OpenPacketLink drives LINKED_Enter -> CONNECT_Enter, producing a fresh
     * BLE connection before PacketLink processing begins.
     *
     * Pairing is already accepted here and is deliberately not modified.
     */
    @SuppressLint("MissingPermission")
    private fun beginOfficialLinkedReconnect(): Boolean {
        // AGENT7 20.03.4.7 SAME GATT LINKED TRANSITION
        //
        // Pairing has already completed on THIS GATT. Do not tear it down here.
        // The previous 18.8.2 flow closed the accepted GATT and opened a second
        // BLE connection 500 ms later. Runtime evidence shows intermittent loss
        // of RemoteApi readiness around this transition. Continue the official
        // CONNECT preflight on the already-accepted, already-discovered GATT.
        if (!_tcsPairingState.value.acceptWritten) {
            appendEvent("TCS20.03.4.7 SAME-GATT LINK blocked: accepted pairing gate incomplete")
            return false
        }
        if (officialLinkedReconnectPending) {
            appendEvent("TCS20.03.4.7 SAME-GATT LINK already pending")
            return true
        }

        val current = gatt ?: run {
            appendEvent("TCS20.03.4.7 SAME-GATT LINK failed: current GATT missing")
            return false
        }

        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
        val deviceName = service?.getCharacteristic(UUID.fromString(TCS_DEVICE_NAME_UUID))
        val bcid = service?.getCharacteristic(UUID.fromString(TCS_BCID_UUID))
        val deviceNameReadable =
            deviceName != null &&
                deviceName.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
        val bcidReadable =
            bcid != null &&
                bcid.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0

        if (!deviceNameReadable || !bcidReadable) {
            officialLinkedReconnectPending = false
            connectedLifecycleReady = false
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                phase = "error",
                available = false,
                linked = false,
                lastStatus = "Accepted GATT missing official CONNECT preflight endpoints",
                lastError = "cxtic2 readable=$deviceNameReadable cxtic4 readable=$bcidReadable"
            )
            appendEvent(
                "TCS20.03.4.7 SAME-GATT PREFLIGHT FAIL " +
                    "cxtic2=$deviceNameReadable cxtic4=$bcidReadable"
            )
            return false
        }

        officialLinkedReconnectPending = true
        connectedLifecycleArmed = false
        connectedFrameCountCccdReady = false
        connectedLifecycleReady = false
        connectedTimecodeReadPending = false

        stopPacketLinkProcessLoop()
        pendingRemotePacket = null
        pendingRemoteFrameWritten = false
        remoteBootstrapQueue.clear()
        remoteBootstrapActive = false
        remoteBootstrapCompleted = false
        remoteBootstrapResponses = 0
        postReadyInputAutoTriggered = false
        postReadyInputAutoTask?.let { mainHandler.removeCallbacks(it) }
        postReadyInputAutoTask = null
        postAcceptProgressEpoch += 1
        postAcceptProgressTask?.let { mainHandler.removeCallbacks(it) }
        postAcceptProgressTask = null
        remoteBootstrapTotal = 0

        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = "linked-connect-preflight",
            available = _tcsRemoteState.value.available,
            linked = _tcsRemoteState.value.linked,
            lastStatus = "Pairing accepted; validating LINKED state on current BLE session",
            lastError = null
        )

        pendingOps.addFirst(
            PendingOp.Read(
                deviceName!!,
                TCS_PURPOSE_LINKED_DEVICE_NAME
            )
        )
        appendEvent(
            "TCS20.03.4.7 SAME-GATT PREFLIGHT START address=${current.device.address} " +
                "cxtic2=$TCS_DEVICE_NAME_UUID then cxtic4=$TCS_BCID_UUID"
        )
        processNext(current)
        return true
    }

    /**
     * 18.8.3 exact CONNECT preflight recovered from the supplied official
     * TCSiOSC.framework x86_64 implementation:
     *
     * substate_CONNECT_ReadingDeviceName_OnEntry -> TCSBlePlex_Read(cxtic 2)
     * substate_CONNECT_ReadingBcid_OnEntry       -> TCSBlePlex_Read(cxtic 4)
     *
     * Only after those reads does the state machine transition to LINKED.
     */
    @SuppressLint("MissingPermission")
    private fun handleOfficialLinkedConnectReadPurpose(
        g: BluetoothGatt,
        op: PendingOp.Read,
        value: ByteArray,
        status: Int
    ): Boolean {
        val purpose = op.purpose ?: return false
        if (purpose != TCS_PURPOSE_LINKED_DEVICE_NAME && purpose != TCS_PURPOSE_LINKED_BCID) {
            return false
        }

        if (status != BluetoothGatt.GATT_SUCCESS) {
            officialLinkedReconnectPending = false
            connectedLifecycleReady = false
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                phase = "error",
                available = false,
                linked = false,
                lastStatus = "Official LINKED CONNECT preflight read failed",
                lastError = "$purpose failed status=$status"
            )
            appendEvent("TCS18.8.3 CONNECT PREFLIGHT READ FAIL purpose=$purpose status=$status")
            return true
        }

        when (purpose) {
            TCS_PURPOSE_LINKED_DEVICE_NAME -> {
                val name = value.toString(Charsets.UTF_8).trim('\u0000', ' ', '\r', '\n')
                appendEvent(
                    "TCS18.8.3 CONNECT DEVICE NAME <- ${name.ifBlank { value.toHex() }} raw=${value.toHex()}"
                )
                if (name.isNotBlank()) _connectedRecorderName.value = name

                val bcid = g.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
                    ?.getCharacteristic(UUID.fromString(TCS_BCID_UUID))
                if (bcid == null || bcid.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
                    officialLinkedReconnectPending = false
                    connectedLifecycleReady = false
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        phase = "error",
                        available = false,
                        linked = false,
                        lastStatus = "Official LINKED BCID endpoint unavailable",
                        lastError = "cxtic4 BCID characteristic missing/not readable"
                    )
                    appendEvent("TCS18.8.3 CONNECT PREFLIGHT FAIL: cxtic4 BCID unavailable after device-name read")
                    return true
                }

                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = "linked-connect-preflight",
                            available = _tcsRemoteState.value.available,
                            linked = _tcsRemoteState.value.linked,
                    lastStatus = "Device name read; reading official CONNECT BCID",
                    lastError = null
                )
                pendingOps.addFirst(PendingOp.Read(bcid, TCS_PURPOSE_LINKED_BCID))
                appendEvent("TCS18.8.3 CONNECT BCID READ -> cxtic4=$TCS_BCID_UUID")
                return true
            }

            TCS_PURPOSE_LINKED_BCID -> {
                if (value.size < 4) {
                    officialLinkedReconnectPending = false
                    connectedLifecycleReady = false
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        phase = "error",
                        available = false,
                        linked = false,
                        lastStatus = "Official LINKED BCID read was invalid",
                        lastError = "Expected 4-byte BCID, got ${value.size}"
                    )
                    appendEvent("TCS18.8.3 CONNECT BCID INVALID len=${value.size} raw=${value.toHex()}")
                    return true
                }

                val linkedBcid = u32Le(value)
                val pairedBcid = _tcsPairingState.value.bcid ?: 0L

                appendEvent(
                    "TCS18.8.3 CONNECT BCID <- ${hexU32(linkedBcid)} paired=${hexU32(pairedBcid)} raw=${value.toHex()}"
                )

                // The official framework maps this freshly-read BCID back to its
                // stored CCID before entering LINKED. We already retain the accepted
                // application pairing state, so a mismatching non-zero BCID means
                // this fresh connection is not the paired recorder identity.
                if (linkedBcid == 0L || (pairedBcid != 0L && linkedBcid != pairedBcid)) {
                    officialLinkedReconnectPending = false
                    connectedLifecycleReady = false
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        phase = "error",
                        available = false,
                        linked = false,
                        lastStatus = "Fresh LINKED connection did not resolve the accepted pairing slot",
                        lastError = "BCID linked=${hexU32(linkedBcid)} paired=${hexU32(pairedBcid)}"
                    )
                    appendEvent("TCS18.8.3 CONNECT IDENTITY FAIL: refusing PacketLink on unresolved/mismatched BCID")
                    return true
                }

                officialLinkedReconnectPending = false
                connectedLifecycleReady = true
                connectedTimecodeReadPending = false

                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = "linked-reconnect-ready",
                    available = false,
                    linked = false,
                    lastStatus = "Official CONNECT preflight complete; opening PacketLink",
                    lastError = null
                )
                appendEvent("TCS18.8.3 CONNECT PREFLIGHT PASS: cxtic2 + cxtic4 complete; entering LINKED PacketLink")
                mainHandler.postDelayed({ openOfficialTcsRemoteLink(autoProbe = true) }, 60L)
                return true
            }
        }

        return false
    }
    @SuppressLint("MissingPermission")
    fun openOfficialTcsRemoteLink(autoProbe: Boolean = true): Boolean {
        // TCS19.12.17.10 LOW LEVEL PLINK EPOCH GUARD
        if (packetLinkDidConnectEnteredThisGattEpoch) {
            appendEvent(
                "TCS19.12.17.10 PLINK LOW-LEVEL REENTRY BLOCKED " +
                    "phase=${_tcsRemoteState.value.phase} primed=$packetLink18Primed " +
                    "peerCredit=${packetLink18.peerCredit()} bootstrap=$remoteBootstrapActive " +
                    "packetId=$tcsCommandSequence"
            )
            return true
        }
        packetLinkDidConnectEnteredThisGattEpoch = true
        appendEvent("TCS19.12.17.10 PLINK LOW-LEVEL FIRST ENTRY ALLOWED")
        val current = gatt ?: return false
        if (!_tcsPairingState.value.acceptWritten) {
            appendEvent("TCS18.8 REMOTE OPEN blocked: pairing accept gate is not complete")
            return false
        }
        if (!connectedLifecycleReady) {
            appendEvent("TCS18.8 REMOTE OPEN deferred: official CONNECTED frame-count/timecode gate is not ready")
            return beginOfficialConnectedLifecycle()
        }
        // TCS19.12.17.9 PACKETLINK SINGLE ENTRY
        // A valid PacketLink session must not run DidConnect again on the same BLE
        // connection. DidConnect resets packet sequence/bootstrap state and sends F0.
        // Runtime 19.12.17.8-R1 proved a second entry restarted MAC13 mid-session.
        val packetLinkSessionAlreadyActive =
            _tcsRemoteState.value.available &&
                (
                    _tcsRemoteState.value.phase == "link-priming" ||
                    _tcsRemoteState.value.phase == "bootstrapping" ||
                    _tcsRemoteState.value.phase == "transport-ready" ||
                    packetLink18Primed ||
                    packetLink18.peerCredit() ||
                    remoteBootstrapActive ||
                    pendingRemotePacket != null
                )

        if (packetLinkSessionAlreadyActive) {
            appendEvent(
                "TCS19.12.17.9 PLINK REENTRY BLOCKED phase=${_tcsRemoteState.value.phase} " +
                    "primed=$packetLink18Primed peerCredit=${packetLink18.peerCredit()} " +
                    "bootstrap=$remoteBootstrapActive packetId=$tcsCommandSequence"
            )
            return true
        }

        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
        val readChar = service?.getCharacteristic(UUID.fromString(TCS_PACKET_READ_UUID))
        val writeChar = service?.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID))
        if (readChar == null || writeChar == null) {
            _tcsRemoteState.value = TcsRemoteState(phase = "error", lastError = "Official TCS Packet Link characteristic missing")
            appendEvent("TCS18.8 REMOTE OPEN failed: cxtic12=${readChar != null} cxtic13=${writeChar != null}")
            return false
        }
        val readOk = readChar.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
        val writeOk = writeChar.properties and
            (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        if (!readOk || !writeOk) {
            _tcsRemoteState.value = TcsRemoteState(
                phase = "error",
                lastError = "TCS Packet Link properties are not usable (read=$readOk write=$writeOk)"
            )
            return false
        }

        // TCSPacketLink DidConnect. No pairing state is modified here.
        packetLink18.didConnect()
        cancelDiagnosticFollowUp()
        remoteApiVerified = false
        packetLink18Primed = false
        tcsPacketFirstSubpacket = true
        tcsPacketRemoteCredit = false
        tcsPacketLocalFlag = false
        pendingRemotePacket = null
        pendingRemoteFrameWritten = false
        tcsCommandSequence = 0
        remoteBootstrapQueue.clear()
        remoteBootstrapActive = false
        remoteBootstrapCompleted = false
        remoteBootstrapResponses = 0
        postReadyInputAutoTriggered = false
        postReadyInputAutoTask?.let { mainHandler.removeCallbacks(it) }
        postReadyInputAutoTask = null
        postAcceptProgressEpoch += 1
        postAcceptProgressTask?.let { mainHandler.removeCallbacks(it) }
        postAcceptProgressTask = null
        remoteBootstrapTotal = 0
        stateSyncRecoveryAttempts = 0
        stateSyncRefreshPending = false
        cancelStateSyncRecoveryWatchdog()
        cancelRemoteResponseTimeout()
        cancelRemoteBootstrapSettle()
        remoteRxBuffer.reset()
        stopPacketLinkProcessLoop()

        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = "link-priming",
            available = true,
            linked = false,
            lastStatus = "Opening macOS-parity TCSPacketLink",
            lastError = null
        )
        appendEvent("TCS18.8 PLINK DIDCONNECT state=READY first=1 peerCredit=0; cxtic12=$TCS_PACKET_READ_UUID cxtic13=$TCS_PACKET_WRITE_UUID")

        if (autoProbe) {
            startPacketLinkProcessLoop()
            drivePacketLink18_8() // First READY process turn produces official F0.
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun enqueueOfficialLinkPrime(
        current: BluetoothGatt,
        writeChar: BluetoothGattCharacteristic
    ): Boolean {
        if (operationInFlight || pendingOps.any { op ->
                (op as? PendingOp.Read)?.purpose.orEmpty().startsWith("remote-") ||
                    (op as? PendingOp.WriteCharacteristic)?.purpose.orEmpty().startsWith("remote-")
            }) {
            appendEvent("TCS18.5 LINK PRIME deferred: GATT queue busy")
            mainHandler.postDelayed({
                val live = gatt ?: return@postDelayed
                val wc = live.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
                    ?.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID))
                    ?: return@postDelayed
                enqueueOfficialLinkPrime(live, wc)
            }, 40L)
            return true
        }

        val header = buildOfficialPacketLinkHeader(local = false)
        val frame = byteArrayOf(header.toByte())
        pendingOps.addLast(
            PendingOp.WriteCharacteristic(
                writeChar,
                frame,
                packetLinkWriteType(writeChar),
                "remote-link-prime"
            )
        )
        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = "link-priming",
            available = true,
            linked = false,
            lastTxHex = frame.toHex(),
            lastStatus = "Priming PacketLink session",
            lastError = null
        )
        appendEvent("TCS18.5 LINK PRIME TX frame=${frame.toHex()} expected=F0 source=TCSiOSC-DidConnect/Process local=0")
        if (!operationInFlight) processNext(current)
        return true
    }

    /**
     * 18.9.1 macOS button parity with diagnostic fallback.
     *
     * We intentionally do NOT disable controls merely because RemoteApi has not
     * produced a payload. PacketLink transport-ready is sufficient for testing.
     *
     * macOS recordButtonPressed:
     *   application state != 3 -> Play.RebootToRecord (0x0197)
     *   then Record.Start/Stop
     *
     * macOS playButtonPressed:
     *   playback app unavailable -> Record.RebootToPlayback (0x0132)
     *   then Play.Start/Pause
     *
     * Until an actual RemoteApi state payload is available, Record Start and Play
     * use the safe diagnostic mode-prep path rather than pretending the state is known.
     */
    @SuppressLint("MissingPermission")
    fun sendOfficialRemoteCommandMacParity(command: String): Boolean {
        if (!_tcsPairingState.value.acceptWritten) {
            appendEvent("TCS18.9.1 MAC BUTTON blocked before accepted pairing command=$command")
            return false
        }

        if (!_tcsRemoteState.value.available) {
            val pairing = _tcsPairingState.value
            val device = targetDevice ?: gatt?.device
            val ccid = pairing.ccid ?: 0L
            val bcid = pairing.bcid ?: 0L

            if (
                pairing.acceptWritten &&
                device != null &&
                ccid != 0L &&
                bcid != 0L
            ) {
                appendEvent(
                    "TCS20.03.4.13 BUTTON SELF-HEAL command=$command; " +
                        "re-entering proven trusted reconnect path"
                )
                val started = connectTrusted(
                    device = device,
                    pairingCode = pairing.pairingCode,
                    ccid = ccid,
                    bcid = bcid
                )
                if (started) {
                    scheduleDiagnosticFollowUp(command)
                    appendEvent("TCS20.03.4.13 BUTTON SELF-HEAL armed command=$command")
                }
                return started
            }

            appendEvent(
                "TCS20.03.4.13 MAC BUTTON blocked: transport unavailable and " +
                    "trusted identity incomplete command=$command"
            )
            return false
        }

        return when {
            command == "record-start" && !remoteApiVerified -> {
                appendEvent(
                    "TCS18.9.1 MAC RECORD STATE UNKNOWN: RemoteApi unverified; " +
                        "sending official Play.RebootToRecord 0x0197 before Record.Start 0x012C"
                )
                val prepared = sendOfficialRemoteCommand("prepare-record-mode")
                if (prepared) scheduleDiagnosticFollowUp("record-start")
                prepared
            }

            command == "play" && !remoteApiVerified -> {
                appendEvent(
                    "TCS18.9.1 MAC PLAY STATE UNKNOWN: RemoteApi unverified; " +
                        "sending official Record.RebootToPlayback 0x0132 before Play.Start 0x0191"
                )
                val prepared = sendOfficialRemoteCommand("prepare-playback-mode")
                if (prepared) scheduleDiagnosticFollowUp("play")
                prepared
            }

            else -> {
                appendEvent(
                    "TCS18.9.1 MAC BUTTON direct command=$command apiVerified=$remoteApiVerified"
                )
                sendOfficialRemoteCommand(command)
            }
        }
    }

    /**
     * Phase 03 Monitor Assist commands recovered from the supplied macOS
     * ATOMOS Remote executable. This path is additive and does not alter the
     * proven Record / Stop / Play / Pause command implementation.
     *
     * macOS evidence:
     *  FocusPeakEnableSet   = 0x0262
     *  ZebraEnableSet       = 0x026A
     *  FalseColourEnableSet = 0x026E
     *  BlueOnlyEnableSet    = 0x0270
     *  ZoomLevelSet         = 0x0272
     *
     * Important 19.4.2 correction:
     * macOS does not append the setting value as a raw U8 AtomMessage payload.
     * It creates the typed AtomProtobuf message and serializes that message via
     * SwiftProtobuf. For these recovered scalar messages, field #1 is varint,
     * so the payload is 08 <value> and AtomMessage payloadLen is 0x0002.
     *
     * Zoom is also recovered from macOS instead of guessed:
     *  0 = Off (main controller explicitly sends ZoomLevelSet(0))
     *  1 = 1:1 (ZoomSettingsController first button)
     *  2 = 2x  (ZoomSettingsController second button)
     */
    @SuppressLint("MissingPermission")
    fun sendOfficialMonitorAssistToggle(control: String, enabled: Boolean): Boolean {
        if (!canQueueOfficialMonitorCommand(control)) return false

        val spec = when (control) {
            "focus-assist" -> Triple(REMOTE_MONITOR_FOCUS_PEAK_ENABLE_SET, "remote-monitor-focus", "Focus Assist")
            "zebra" -> Triple(REMOTE_MONITOR_ZEBRA_ENABLE_SET, "remote-monitor-zebra", "Zebra")
            "false-color" -> Triple(REMOTE_MONITOR_FALSE_COLOUR_ENABLE_SET, "remote-monitor-false-colour", "False Color")
            "blue-only" -> Triple(REMOTE_MONITOR_BLUE_ONLY_ENABLE_SET, "remote-monitor-blue-only", "Blue Only")
            "zoom" -> {
                appendEvent("TCS19.4.2 MONITOR use sendOfficialMonitorZoomLevel() for level-based Zoom")
                return false
            }
            else -> {
                appendEvent("TCS19.4.2 MONITOR unsupported control=$control")
                return false
            }
        }

        if (control == "focus-assist") {
            focusExpectedEnabled = enabled
            _monitorFocusAssist.value = _monitorFocusAssist.value.copy(
                pendingKey = "enabled",
                status = "Applying Focus Assist ${if (enabled) "On" else "Off"}; waiting for recorder readback"
            )
        }

        return enqueueOfficialRemoteCommandProtoBool1(
            commandId = spec.first,
            enabled = enabled,
            purpose = spec.second,
            label = "${spec.third} ${if (enabled) "On" else "Off"}"
        )
    }

    /**
     * 20.05.0-20.05.2 Focus Assist core, recovered from the uploaded macOS binary.
     *
     * Exact local=true getter pairs recovered from FocusAssistSettingsController:
     *   FocusPeakEnableGet              0x0263 -> 0x025D
     *   FocusPeakTypeGet                0x0265 -> 0x025E
     *   FocusPeakThresholdGet           0x0267 -> 0x025F
     *   FocusPeakSupportedThresholdsGet 0x027D -> 0x026B
     *
     * Exact setters:
     *   FocusPeakEnableSet    0x0262
     *   FocusPeakTypeSet      0x0264
     *   FocusPeakThresholdSet 0x0266
     *
     * macOS constructs these GETs through AtomMessage(baseLocal=true).
     */
    private fun scheduleFocusRefreshStep(stage: Int, delayMs: Long = 120L) {
        focusRefreshStage = stage
        focusRefreshRetry = 0
        mainHandler.postDelayed({ driveFocusRefreshStep(stage) }, delayMs)
    }

    private fun driveFocusRefreshStep(stage: Int) {
        if (focusRefreshStage != stage) return
        if (pendingRemotePacket != null) {
            focusRefreshRetry += 1
            if (focusRefreshRetry <= 18) {
                mainHandler.postDelayed({ driveFocusRefreshStep(stage) }, 120L)
            } else {
                focusRefreshStage = 0
                focusRefreshRetry = 0
                _monitorFocusAssist.value = _monitorFocusAssist.value.copy(
                    pendingKey = null,
                    status = "Focus settings partially synchronized"
                )
                appendEvent("TCS20.05.3 FOCUS CHAIN GIVEUP stage=$stage")
            }
            return
        }

        val queued = when (stage) {
            1 -> queueOfficialFocusGetter(
                REMOTE_MONITOR_FOCUS_PEAK_ENABLE_GET,
                REMOTE_MONITOR_FOCUS_PEAK_ENABLE_RESPONSE,
                "remote-monitor-focus-enable-get",
                "Focus Assist"
            )
            2 -> queueOfficialFocusGetter(
                REMOTE_MONITOR_FOCUS_PEAK_TYPE_GET,
                REMOTE_MONITOR_FOCUS_PEAK_TYPE_RESPONSE,
                "remote-monitor-focus-type-get",
                "Focus Type"
            )
            3 -> queueOfficialFocusGetter(
                REMOTE_MONITOR_FOCUS_PEAK_SUPPORTED_THRESHOLDS_GET,
                REMOTE_MONITOR_FOCUS_PEAK_SUPPORTED_THRESHOLDS_RESPONSE,
                "remote-monitor-focus-thresholds-get",
                "Focus Threshold Options"
            )
            4 -> queueOfficialFocusGetter(
                REMOTE_MONITOR_FOCUS_PEAK_THRESHOLD_GET,
                REMOTE_MONITOR_FOCUS_PEAK_THRESHOLD_RESPONSE,
                "remote-monitor-focus-threshold-get",
                "Focus Threshold"
            )
            5 -> queueOfficialFocusGetter(
                REMOTE_MONITOR_FOCUS_PEAK_COLOUR_GET,
                REMOTE_MONITOR_FOCUS_PEAK_COLOUR_RESPONSE,
                "remote-monitor-focus-colour-get",
                "Focus Colour"
            )
            6 -> queueOfficialFocusGetter(
                REMOTE_MONITOR_ZEBRA_ENABLE_GET,
                REMOTE_MONITOR_ZEBRA_ENABLE_RESPONSE,
                "remote-monitor-zebra-enable-get",
                "Zebra"
            )
            7 -> queueOfficialFocusGetter(
                REMOTE_MONITOR_ZEBRA_THRESHOLD_GET,
                REMOTE_MONITOR_ZEBRA_THRESHOLD_RESPONSE,
                "remote-monitor-zebra-threshold-get",
                "Zebra Threshold"
            )
            else -> false
        }

        if (!queued) {
            focusRefreshRetry += 1
            if (focusRefreshRetry <= 18) {
                mainHandler.postDelayed({ driveFocusRefreshStep(stage) }, 120L)
            } else {
                focusRefreshStage = 0
                focusRefreshRetry = 0
                appendEvent("TCS20.05.3 FOCUS CHAIN QUEUE GIVEUP stage=$stage")
            }
        } else {
            focusRefreshRetry = 0
            appendEvent("TCS20.05.3 FOCUS CHAIN QUEUED stage=$stage")
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshOfficialFocusAssistState(): Boolean {
        if (!canQueueOfficialMonitorCommand("focus-refresh")) return false
        _monitorFocusAssist.value = _monitorFocusAssist.value.copy(
            status = "Reading Focus Assist from recorder"
        )
        _monitorZebraState.value = _monitorZebraState.value.copy(
            status = "Reading Zebra from recorder"
        )
        scheduleFocusRefreshStep(1, 20L)
        return true
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialFocusPeakType(type: Int): Boolean {
        if (type !in 1..3) return false
        if (!canQueueOfficialMonitorCommand("focus-type:$type")) return false
        focusExpectedType = type
        _monitorFocusAssist.value = _monitorFocusAssist.value.copy(
            pendingKey = "type",
            status = "Applying ${focusTypeLabel(type)}; waiting for recorder readback"
        )
        return enqueueOfficialRemoteCommandProtoExplicitVarint1(
            commandId = REMOTE_MONITOR_FOCUS_PEAK_TYPE_SET,
            value = type,
            purpose = "remote-monitor-focus-type:$type",
            label = "Focus Type ${focusTypeLabel(type)}"
        )
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialFocusPeakThreshold(threshold: Int): Boolean {
        val advertised = _monitorFocusAssist.value.supportedThresholds
        if (advertised.isNotEmpty() && threshold !in advertised) {
            appendEvent("TCS20.05.2 FOCUS THRESHOLD BLOCKED value=$threshold advertised=$advertised")
            return false
        }
        if (!canQueueOfficialMonitorCommand("focus-threshold:$threshold")) return false
        focusExpectedThreshold = threshold
        _monitorFocusAssist.value = _monitorFocusAssist.value.copy(
            pendingKey = "threshold",
            status = "Applying threshold $threshold; waiting for recorder readback"
        )
        return enqueueOfficialRemoteCommandProtoExplicitVarint1(
            commandId = REMOTE_MONITOR_FOCUS_PEAK_THRESHOLD_SET,
            value = threshold,
            purpose = "remote-monitor-focus-threshold:$threshold",
            label = "Focus Threshold $threshold"
        )
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialFocusPeakColour(colour: Int): Boolean {
        if (colour !in 1..7) return false
        if (!canQueueOfficialMonitorCommand("focus-colour:$colour")) return false
        focusExpectedColour = colour
        _monitorFocusAssist.value = _monitorFocusAssist.value.copy(
            pendingKey = "colour",
            status = "Applying ${_monitorFocusAssist.value.colourLabel(colour)}; waiting for recorder readback"
        )
        return enqueueOfficialRemoteCommandProtoExplicitVarint1(
            commandId = REMOTE_MONITOR_FOCUS_PEAK_COLOUR_SET,
            value = colour,
            purpose = "remote-monitor-focus-colour:$colour",
            label = "Focus Colour ${_monitorFocusAssist.value.colourLabel(colour)}"
        )
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialZebraEnabled(enabled: Boolean): Boolean {
        if (!canQueueOfficialMonitorCommand("zebra:$enabled")) return false
        zebraExpectedEnabled = enabled
        _monitorZebraState.value = _monitorZebraState.value.copy(
            pendingKey = "enabled",
            status = "Applying Zebra ${if (enabled) "On" else "Off"}; waiting for recorder readback"
        )
        return enqueueOfficialRemoteCommandProtoBool1(
            commandId = REMOTE_MONITOR_ZEBRA_ENABLE_SET,
            enabled = enabled,
            purpose = "remote-monitor-zebra:$enabled",
            label = "Zebra ${if (enabled) "On" else "Off"}"
        )
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialZebraThreshold(threshold: Int): Boolean {
        if (threshold !in 0..100) return false
        if (!canQueueOfficialMonitorCommand("zebra-threshold:$threshold")) return false
        zebraExpectedThreshold = threshold
        _monitorZebraState.value = _monitorZebraState.value.copy(
            pendingKey = "threshold",
            status = "Applying Zebra threshold $threshold; waiting for recorder readback"
        )
        return enqueueOfficialRemoteCommandProtoExplicitVarint1(
            commandId = REMOTE_MONITOR_ZEBRA_THRESHOLD_SET,
            value = threshold,
            purpose = "remote-monitor-zebra-threshold:$threshold",
            label = "Zebra Threshold $threshold"
        )
    }

    @SuppressLint("MissingPermission")
    fun refreshOfficialMonitorAssistEffects(): Boolean {
        if (!canQueueOfficialMonitorCommand("monitor-effects-refresh")) return false
        _monitorAssistEffects.value = _monitorAssistEffects.value.copy(status = "Reading exposure tools")
        return queueOfficialFocusGetter(
            REMOTE_MONITOR_FALSE_COLOUR_ENABLE_GET,
            REMOTE_MONITOR_FALSE_COLOUR_ENABLE_RESPONSE,
            "remote-monitor-false-colour-get",
            "False Colour"
        )
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialFalseColourEnabled(enabled: Boolean): Boolean {
        if (!canQueueOfficialMonitorCommand("false-colour:$enabled")) return false
        _monitorAssistEffects.value = _monitorAssistEffects.value.copy(
            pendingKey = "false-colour",
            status = "Applying False Colour; waiting for recorder readback"
        )
        return enqueueOfficialRemoteCommandProtoBool1(
            commandId = REMOTE_MONITOR_FALSE_COLOUR_ENABLE_SET,
            enabled = enabled,
            purpose = "remote-monitor-false-colour:$enabled",
            label = "False Colour ${if (enabled) "On" else "Off"}"
        )
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialBlueOnlyEnabled(enabled: Boolean): Boolean {
        if (!canQueueOfficialMonitorCommand("blue-only:$enabled")) return false
        _monitorAssistEffects.value = _monitorAssistEffects.value.copy(
            pendingKey = "blue-only",
            status = "Applying Blue Only; waiting for recorder readback"
        )
        return enqueueOfficialRemoteCommandProtoBool1(
            commandId = REMOTE_MONITOR_BLUE_ONLY_ENABLE_SET,
            enabled = enabled,
            purpose = "remote-monitor-blue-only:$enabled",
            label = "Blue Only ${if (enabled) "On" else "Off"}"
        )
    }

    @SuppressLint("MissingPermission")
    private fun queueOfficialFocusGetter(
        commandId: Int,
        expectedResponse: Int,
        purpose: String,
        label: String
    ): Boolean {
        val current = gatt ?: return false
        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return false
        service.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false
        if (pendingRemotePacket != null) return false

        val packetId = 0
        val body = buildOfficialAtomMessageBody(packetId, commandId)
        val packet = PendingRemotePacket(body, purpose, label, commandId, packetId)
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        appendEvent(
            "TCS20.05 FOCUS GET label=$label cmd=0x${commandId.toString(16)} " +
                "expected=0x${expectedResponse.toString(16)} packetId=0 m_isLocal=1"
        )
        return queueRemotePacket18_8(packet, local = false)
    }

    private fun focusTypeLabel(type: Int): String = when (type) {
        1 -> "Thin"
        2 -> "Medium"
        3 -> "Thick"
        else -> "Type $type"
    }

    /**
     * Phase 19.11.8 full Zoom controller recovered from Contents(3).zip.
     * macOS settings refresh order:
     * 0x0274 ZoomLevelListGet -> 0x0266
     * 0x0273 ZoomLevelGet     -> 0x0265
     * 0x027B ZoomBoxGet       -> 0x026A
     * Set: 0x0272, local=true. Pan: 0x027C, local=true.
     */
    @SuppressLint("MissingPermission")
    fun refreshOfficialZoomState(): Boolean {
        if (!canQueueOfficialMonitorCommand("zoom-refresh")) return false
        zoomRefreshActive = true
        zoomRefreshStage = 1
        val ok = queueOfficialZoomLevelListGet()
        if (!ok) {
            zoomRefreshActive = false
            zoomRefreshStage = 0
        }
        return ok
    }

    @SuppressLint("MissingPermission")
    fun refreshOfficialZoomCurrentLevel(): Boolean {
        if (!canQueueOfficialMonitorCommand("zoom-current")) return false
        _monitorZoomCore.value = _monitorZoomCore.value.copy(
            status = "Reading current Zoom level",
            readbackTimedOut = false
        )
        return queueOfficialZoomLevelGet()
    }

    @SuppressLint("MissingPermission")
    fun refreshOfficialZoomSupportedLevels(): Boolean {
        if (!canQueueOfficialMonitorCommand("zoom-supported")) return false
        _monitorZoomCore.value = _monitorZoomCore.value.copy(
            status = "Reading supported Zoom levels",
            readbackTimedOut = false
        )
        return queueOfficialZoomLevelListGet()
    }

    @SuppressLint("MissingPermission")
    fun refreshOfficialZoomCore(): Boolean {
        if (!canQueueOfficialMonitorCommand("zoom-core")) return false
        zoomRefreshActive = true
        zoomRefreshStage = 1
        _monitorZoomCore.value = _monitorZoomCore.value.copy(
            status = "Reading Zoom capabilities",
            readbackTimedOut = false
        )
        val queued = queueOfficialZoomLevelListGet()
        if (!queued) {
            zoomRefreshActive = false
            zoomRefreshStage = 0
        }
        return queued
    }

    @SuppressLint("MissingPermission")
    fun refreshOfficialZoomViewport(): Boolean {
        if (!canQueueOfficialMonitorCommand("zoom-viewport")) return false
        _monitorZoomViewport.value = _monitorZoomViewport.value.copy(status = "Reading Zoom viewport")
        return queueOfficialZoomBoxGet()
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialMonitorZoomLevel(level: Int): Boolean {
        if (level !in listOf(0, 1, 2)) return false
        if (!canQueueOfficialMonitorCommand("zoom:$level")) return false

        val advertised = _zoomSupportedLevels.value
        if (_zoomCapabilitiesKnown.value && level != 0 && level !in advertised) {
            _monitorZoomCore.value = _monitorZoomCore.value.copy(
                pendingLevel = null,
                status = "${zoomLevelLabel(level)} is not supported by this recorder"
            )
            appendEvent("TCS20.04.5 ZOOM SET BLOCKED level=$level advertised=$advertised")
            return false
        }

        zoomExpectedLevelAfterSet = level
        zoomSummaryExpectedLevel = level
        zoomRefreshAfterSet = false
        _monitorZoomCore.value = _monitorZoomCore.value.copy(
            pendingLevel = level,
            status = "Applying ${zoomLevelLabel(level)}; waiting for recorder readback",
            readbackTimedOut = false
        )
        val wireLevel = when (level) {
            0 -> 0
            1 -> 1
            2 -> 3
            else -> return false
        }
        appendEvent(
            "TCS20.13.4 ZOOM SET logical=$level wire=$wireLevel cmd=0x272 m_isLocal=1 " +
                "capabilitiesKnown=${if (_zoomCapabilitiesKnown.value) 1 else 0}"
        )
        val queued = queueOfficialZoomLevelSet(wireLevel, level)
        if (!queued) {
            zoomExpectedLevelAfterSet = null
            _monitorZoomCore.value = _monitorZoomCore.value.copy(
                pendingLevel = null,
                status = "Zoom command is busy; try again"
            )
        }
        return queued
    }

    @SuppressLint("MissingPermission")
    private fun queueOfficialZoomLevelSet(wireLevel: Int, logicalLevel: Int): Boolean =
        // Recorder advertises raw levels [1,3]. UI remains logical 0/1/2:
        // Off=0, 1:1=1, 2:1 logical=2 -> wire=3. Explicit zero is retained.
        enqueueOfficialRemoteCommandProtoExplicitVarint1(
            commandId = REMOTE_MONITOR_ZOOM_LEVEL_SET,
            value = wireLevel,
            purpose = "remote-monitor-zoom-level-set:$logicalLevel",
            label = when (logicalLevel) { 0 -> "Zoom Off"; 1 -> "Zoom 1:1"; else -> "Zoom 2:1" }
        )

    @SuppressLint("MissingPermission")
    private fun queueOfficialZoomLevelListGet(): Boolean =
        queueOfficialZoomGetter(
            commandId = REMOTE_MONITOR_ZOOM_LEVEL_LIST_GET,
            expectedResponseId = REMOTE_MONITOR_ZOOM_LEVEL_LIST_RESPONSE,
            purpose = "remote-monitor-zoom-level-list",
            label = "Zoom Available Levels",
            status = "Synchronizing Zoom availability"
        )

    @SuppressLint("MissingPermission")
    private fun queueOfficialZoomLevelGet(): Boolean =
        queueOfficialZoomGetter(
            commandId = REMOTE_MONITOR_ZOOM_LEVEL_GET,
            expectedResponseId = REMOTE_MONITOR_ZOOM_LEVEL_RESPONSE,
            purpose = "remote-monitor-zoom-level-get",
            label = "Zoom Current Level",
            status = "Reading current Zoom state"
        )

    @SuppressLint("MissingPermission")
    private fun queueOfficialZoomBoxGet(): Boolean =
        queueOfficialZoomGetter(
            commandId = REMOTE_MONITOR_ZOOM_BOX_GET,
            expectedResponseId = REMOTE_MONITOR_ZOOM_BOX_RESPONSE,
            purpose = "remote-monitor-zoom-box-get",
            label = "Zoom Viewport",
            status = "Reading Zoom viewport"
        )

    @SuppressLint("MissingPermission")
    private fun queueOfficialZoomGetter(
        commandId: Int,
        expectedResponseId: Int,
        purpose: String,
        label: String,
        status: String
    ): Boolean {
        val current = gatt ?: return false
        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return false
        service.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false
        // 20.04.9 exact macOS parity:
        // ZoomLevelListGet, ZoomLevelGet and ZoomBoxGet constructors in the
        // uploaded ATOMOS Remote binary all call AtomMessage(baseLocal = true).
        // Match existing local RemoteApi one-shot framing with packetId=0.
        val packetId = 0
        val body = buildOfficialAtomMessageBody(packetId, commandId)
        val packet = PendingRemotePacket(
            body = body, purpose = purpose, label = label, commandId = commandId,
            packetId = packetId, expectedResponseId = expectedResponseId
        )
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = if (tcsPacketRemoteCredit) "sending" else "requesting-credit",
            available = true,
            lastCommand = label,
            lastCommandId = commandId,
            lastStatus = status,
            lastError = null
        )
        appendEvent(
            "TCS20.04.9 ZOOM GET MAC-PARITY label=$label cmd=0x${commandId.toString(16)} " +
                "expected=0x${expectedResponseId.toString(16)} packetId=$packetId m_isLocal=0"
        )
        return queueRemotePacket18_8(packet, local = true)
    }

    private fun scheduleZoomBoxAfterConfirmedLevel(logicalLevel: Int, attempt: Int = 0) {
        val maxAttempts = 24
        mainHandler.postDelayed({
            if (logicalLevel == 0) {
                _zoomBoxState.value = null
                _monitorZoomViewport.value = MonitorZoomViewportState(
                    ready = false,
                    pannable = false,
                    pendingPan = false,
                    status = "Select 1:1 or 2:1 Zoom"
                )
                appendEvent("TCS20.13.4A ZOOM VIEWPORT CLEARED level=0")
                return@postDelayed
            }
            if (pendingRemotePacket != null || operationInFlight || pendingOps.isNotEmpty() || remoteBootstrapActive) {
                if (attempt + 1 < maxAttempts) {
                    scheduleZoomBoxAfterConfirmedLevel(logicalLevel, attempt + 1)
                } else {
                    appendEvent("TCS20.13.4A ZOOM BOX REFRESH TIMEOUT level=$logicalLevel attempts=$maxAttempts")
                }
                return@postDelayed
            }
            val queued = queueOfficialZoomBoxGet()
            appendEvent(
                "TCS20.13.4A ZOOM BOX REFRESH AFTER LEVEL level=$logicalLevel " +
                    "attempt=${attempt + 1} queued=$queued"
            )
            if (!queued && attempt + 1 < maxAttempts) {
                scheduleZoomBoxAfterConfirmedLevel(logicalLevel, attempt + 1)
            }
        }, if (attempt == 0) 220L else 120L)
    }

    private fun beginZoomLevelAndBoxReadback() {
        if (pendingRemotePacket != null) return
        zoomRefreshActive = true
        zoomRefreshStage = 2
        if (!queueOfficialZoomLevelGet()) {
            zoomRefreshActive = false
            zoomRefreshStage = 0
        }
    }

    /**
     * 19.12.19 AG2 granular Input route-matrix collector.
     *
     * Binary-confirmed getter IDs from the supplied macOS executable are queried
     * one at a time. Their generated SwiftProtobuf constructors carry a boolean
     * argument. SwiftProtobuf omits false on the wire, so each safe read-only GET
     * is first tried with an empty proto and then with field1=true (08 01) only if
     * the first variant yields no typed payload. No Input setter is called here.
     */
    private fun canQueueOfficialInputSetter(label: String): Boolean {
        if (!_tcsPairingState.value.acceptWritten || !_tcsRemoteState.value.available) {
            appendEvent("TCS20.03.7 INPUT SET blocked session-not-ready label=$label")
            return false
        }
        if (inputCollectorRunning || inputExtendedRunning || remoteBootstrapActive) {
            appendEvent(
                "TCS20.03.7 INPUT SET blocked sync-running label=$label " +
                    "core=$inputCollectorRunning extended=$inputExtendedRunning bootstrap=$remoteBootstrapActive"
            )
            return false
        }
        if (pendingRemotePacket != null || pendingInputSetter != null) {
            appendEvent(
                "TCS20.03.7 INPUT SET blocked busy label=$label " +
                    "remote=${pendingRemotePacket?.label} setter=${pendingInputSetter?.requestedLabel}"
            )
            return false
        }
        return true
    }

    private fun beginInputSetter(
        key: String,
        desiredValue: Int,
        requestedLabel: String,
        commandId: Int,
        enabled: Boolean? = null
    ): Boolean {
        if (!canQueueOfficialInputSetter(requestedLabel)) return false

        val queued = if (enabled != null) {
            enqueueOfficialRemoteCommandProtoBool1(
                commandId = commandId,
                enabled = enabled,
                purpose = "input-set:$key:$desiredValue",
                label = requestedLabel
            )
        } else {
            enqueueOfficialRemoteCommandProtoExplicitVarint1(
                commandId = commandId,
                value = desiredValue,
                purpose = "input-set:$key:$desiredValue",
                label = requestedLabel
            )
        }

        if (!queued) {
            appendEvent("TCS20.03.7 INPUT SET queue-failed key=$key label=$requestedLabel")
            return false
        }

        pendingInputSetter = PendingInputSetter(key, desiredValue, requestedLabel)
        _inputSetterState.value = InputSetterState(
            busy = true,
            key = key,
            requestedLabel = requestedLabel,
            requestedValue = desiredValue,
            status = "Applying $requestedLabel and waiting for recorder readback",
            confirmed = false
        )
        appendEvent(
            "TCS20.03.7 INPUT SET queued key=$key value=$desiredValue " +
                "label=$requestedLabel cmd=0x${commandId.toString(16).uppercase()} local=true"
        )
        scheduleInputSetterReadback(key, 0)
        return true
    }

    @SuppressLint("MissingPermission")
    fun setOfficialInputSource(value: Int): Boolean {
        val label = InputEditCatalog.sourceOptions.firstOrNull { it.value == value }?.label ?: return false
        return beginInputSetter(
            key = "source",
            desiredValue = value,
            requestedLabel = "Input Source: $label",
            commandId = InputEditCatalog.SOURCE_SET
        )
    }

    @SuppressLint("MissingPermission")
    fun setOfficialInputHdmiStandard(value: Int): Boolean {
        val label = InputEditCatalog.hdmiStandardOptions.firstOrNull { it.value == value }?.label ?: return false
        return beginInputSetter(
            key = "hdmiStandard",
            desiredValue = value,
            requestedLabel = "HDMI Standard: $label",
            commandId = InputEditCatalog.HDMI_STANDARD_SET
        )
    }

    @SuppressLint("MissingPermission")
    fun setOfficialInputTriggerSource(value: Int): Boolean {
        val label = InputEditCatalog.triggerSourceOptions.firstOrNull { it.value == value }?.label ?: return false
        return beginInputSetter(
            key = "triggerSource",
            desiredValue = value,
            requestedLabel = "Trigger Source: $label",
            commandId = InputEditCatalog.TRIGGER_SOURCE_SET
        )
    }

    @SuppressLint("MissingPermission")
    fun setOfficialInputTriggerEnabled(enabled: Boolean): Boolean =
        beginInputSetter(
            key = "triggerEnabled",
            desiredValue = if (enabled) 1 else 0,
            requestedLabel = "Record Trigger: ${if (enabled) "On" else "Off"}",
            commandId = InputEditCatalog.TRIGGER_ENABLED_SET,
            enabled = enabled
        )

    @SuppressLint("MissingPermission")
    fun setOfficialInputHdrEnabled(enabled: Boolean): Boolean =
        beginInputSetter(
            key = "hdrState",
            desiredValue = if (enabled) 1 else 0,
            requestedLabel = "HDR: ${if (enabled) "On" else "Off"}",
            commandId = InputEditCatalog.HDR_ENABLE_STATE_SET,
            enabled = enabled
        )

    @SuppressLint("MissingPermission")
    fun setOfficialInputHdrAuto(enabled: Boolean): Boolean =
        beginInputSetter(
            key = "hdrAuto",
            desiredValue = if (enabled) 1 else 0,
            requestedLabel = "HDR Auto: ${if (enabled) "On" else "Off"}",
            commandId = InputEditCatalog.HDR_AUTO_ENABLE_SET,
            enabled = enabled
        )

    @SuppressLint("MissingPermission")
    fun setOfficialInputHdrCamera(value: Int): Boolean {
        val label = InputEditCatalog.hdrCameraOptions.firstOrNull { it.value == value }?.label ?: return false
        return beginInputSetter(
            key = "hdrCamera",
            desiredValue = value,
            requestedLabel = "Camera: $label",
            commandId = InputEditCatalog.HDR_CAMERA_SET
        )
    }

    @SuppressLint("MissingPermission")
    fun setOfficialInputHdrGamma(value: Int): Boolean {
        val label = InputEditCatalog.hdrGammaOptions.firstOrNull { it.value == value }?.label ?: return false
        return beginInputSetter(
            key = "hdrGamma",
            desiredValue = value,
            requestedLabel = "Gamma: $label",
            commandId = InputEditCatalog.HDR_GAMMA_SET
        )
    }

    @SuppressLint("MissingPermission")
    fun setOfficialInputHdrGamut(value: Int): Boolean {
        val label = InputEditCatalog.hdrGamutOptions.firstOrNull { it.value == value }?.label ?: return false
        return beginInputSetter(
            key = "hdrGamut",
            desiredValue = value,
            requestedLabel = "Gamut: $label",
            commandId = InputEditCatalog.HDR_GAMUT_SET
        )
    }

    @SuppressLint("MissingPermission")
    fun setOfficialInputRange(value: Int): Boolean {
        val label = InputEditCatalog.inputRangeOptions.firstOrNull { it.value == value }?.label ?: return false
        return beginInputSetter(
            key = "inputRange",
            desiredValue = value,
            requestedLabel = "Input Range: $label",
            commandId = InputEditCatalog.INPUT_RANGE_SET
        )
    }

    private fun scheduleInputSetterReadback(key: String, attempt: Int) {
        inputSetterReadbackRunnable?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            val pending = pendingInputSetter ?: return@Runnable
            if (pending.key != key) return@Runnable

            if (pendingRemotePacket != null || inputCollectorRunning || inputExtendedRunning || remoteBootstrapActive) {
                if (attempt < 30) {
                    scheduleInputSetterReadback(key, attempt + 1)
                } else {
                    failPendingInputSetter("Recorder readback could not start")
                }
                return@Runnable
            }

            val queued = if (key == "source" || key == "hdrState") {
                refreshGranularInputState(sourceOnly = key == "source")
            } else {
                refreshExtendedInputState()
            }

            appendEvent(
                "TCS20.03.7 INPUT SET READBACK key=$key attempt=$attempt queued=$queued"
            )

            if (!queued) {
                if (attempt < 30) scheduleInputSetterReadback(key, attempt + 1)
                else failPendingInputSetter("Recorder readback could not be queued")
            }
        }
        inputSetterReadbackRunnable = task
        mainHandler.postDelayed(task, if (attempt == 0) 650L else 180L)
    }

    private fun confirmPendingInputSetter(key: String, actualValue: Int?) {
        val pending = pendingInputSetter ?: return
        if (pending.key != key || actualValue == null) return

        val matched = actualValue == pending.desiredValue
        if (matched) {
            appendEvent(
                "TCS20.03.7 INPUT SET CONFIRMED key=$key desired=${pending.desiredValue} " +
                    "actual=$actualValue mismatches=${pending.mismatchCount} label=${pending.requestedLabel}"
            )
            _inputSetterState.value = InputSetterState(
                busy = false,
                key = key,
                requestedLabel = pending.requestedLabel,
                requestedValue = pending.desiredValue,
                status = "${pending.requestedLabel} confirmed by recorder",
                confirmed = true
            )
            pendingInputSetter = null
            inputSetterReadbackRunnable = null
            return
        }

        val nextMismatch = pending.mismatchCount + 1
        appendEvent(
            "TCS20.03.7 INPUT SET SETTLING key=$key desired=${pending.desiredValue} " +
                "actual=$actualValue mismatch=$nextMismatch/$inputSetterMaxMismatchRetries " +
                "label=${pending.requestedLabel}"
        )

        if (nextMismatch >= inputSetterMaxMismatchRetries) {
            appendEvent(
                "TCS20.03.7 INPUT SET MISMATCH key=$key desired=${pending.desiredValue} " +
                    "actual=$actualValue label=${pending.requestedLabel}"
            )
            val status = when (key) {
                "source" ->
                    "Recorder kept the current input. The requested connector may not have a usable signal."
                "hdmiStandard" ->
                    "Recorder kept the negotiated HDMI standard for the connected source."
                "triggerSource", "triggerEnabled" ->
                    "Recorder kept the current trigger setting. HDMI trigger needs a compatible camera/trigger signal; a normal laptop HDMI feed may not support it."
                "hdrState", "hdrAuto", "hdrCamera", "hdrGamma", "hdrGamut" ->
                    "Recorder kept the current HDR conversion value for this signal/profile."
                "inputRange" ->
                    "Recorder kept the current input range for this signal."
                else ->
                    "Recorder kept the current value."
            }
            _inputSetterState.value = InputSetterState(
                busy = false,
                key = key,
                requestedLabel = pending.requestedLabel,
                requestedValue = pending.desiredValue,
                status = status,
                confirmed = false
            )
            pendingInputSetter = null
            inputSetterReadbackRunnable = null
            return
        }

        pendingInputSetter = pending.copy(mismatchCount = nextMismatch)
        _inputSetterState.value = InputSetterState(
            busy = true,
            key = key,
            requestedLabel = pending.requestedLabel,
            requestedValue = pending.desiredValue,
            status = "Recorder is settling ${pending.requestedLabel}. Verifying again...",
            confirmed = false
        )
    }

    private fun failPendingInputSetter(reason: String) {
        val pending = pendingInputSetter ?: return
        appendEvent(
            "TCS20.03.7 INPUT SET NOT_CONFIRMED key=${pending.key} " +
                "desired=${pending.desiredValue} reason=$reason"
        )
        _inputSetterState.value = InputSetterState(
            busy = false,
            key = pending.key,
            requestedLabel = pending.requestedLabel,
            requestedValue = pending.desiredValue,
            status = "$reason. Current UI remains recorder readback.",
            confirmed = false
        )
        pendingInputSetter = null
        inputSetterReadbackRunnable = null
    }

    @SuppressLint("MissingPermission")

    fun refreshExtendedInputState(): Boolean {
        if (!_tcsRemoteState.value.available || !_tcsPairingState.value.acceptWritten) {
            appendEvent("TCS20.03.6.10 EXT INPUT blocked session-not-ready")
            return false
        }
        if (inputExtendedRunning) return true
        if (
            InputExtendedStartPolicy.hasRealOwner(
                coreInputRunning = inputCollectorRunning,
                bootstrapRunning = remoteBootstrapActive,
                pendingRemoteLabel = pendingRemotePacket?.label
            )
        ) {
            appendEvent(
                "INPUT22 EXTENDED WAIT REAL OWNER " +
                    "coreInput=$inputCollectorRunning bootstrap=$remoteBootstrapActive " +
                    "pending=${pendingRemotePacket?.label}"
            )
            return false
        }

        // Normal E0 heartbeat activity intentionally does NOT block starting.
        // The first getter waits until PacketLink accepts its data packet.
        appendEvent(
            "TCS20.03.6.10-R2 EXT INPUT start accepted " +
                "heartbeatAtt=$operationInFlight queue=${pendingOps.size}"
        )

        inputExtendedGeneration += 1
        appendEvent("TCS20.12.3 EXT INPUT REFRESH RETAIN previousValues=${inputExtendedValues.size}")
        inputExtendedResponseTypes.clear()
        inputExtendedRawPayloads.clear()
        inputExtendedIndex = 0
        // R10 hardware evidence: HDMI Standard, Trigger Source, Trigger Enabled,
        // Camera, Gamma, Gamut and HDR Auto all returned typed payloads on local=true.
        // Probe local=true first; keep non-local as fallback only.
        inputExtendedVariant = 1
        inputExtendedRunning = true
        inputExtendedActive = null
        publishExtendedInput(true, false, "Reading Input details...")
        appendEvent("TCS20.03.6.10 EXT INPUT START generation=$inputExtendedGeneration getters=${InputExtendedReadPlan.probes.size}")
        return queueExtendedInputProbe()
    }

    private fun publishExtendedInput(running: Boolean, unchanged: Boolean, status: String) {
        val completed = inputExtendedValues.values.count {
            !it.startsWith("NO_RESPONSE") && !it.startsWith("REJECT")
        }
        _inputExtendedSnapshot.value = InputExtendedSnapshot(
            generation = inputExtendedGeneration,
            running = running,
            unchanged = unchanged,
            completed = completed,
            expected = InputExtendedReadPlan.probes.size,
            values = inputExtendedValues.toMap(),
            responseTypes = inputExtendedResponseTypes.toMap(),
            rawPayloads = inputExtendedRawPayloads.toMap(),
            status = status
        )
    }

    private fun finishExtendedInput(unchanged: Boolean = false, status: String? = null) {
        inputExtendedTimeout?.let { mainHandler.removeCallbacks(it) }
        inputExtendedTimeout = null
        inputExtendedActive = null
        inputExtendedRunning = false
        val completed = inputExtendedValues.values.count {
            !it.startsWith("NO_RESPONSE") && !it.startsWith("REJECT")
        }
        publishExtendedInput(false, unchanged, status ?: "Input details complete: $completed/${InputExtendedReadPlan.probes.size}")
        pendingInputSetter?.let { pending ->
            if (pending.key != "source" && pending.key != "hdrState") {
                if (pending.mismatchCount < inputSetterMaxMismatchRetries) {
                    appendEvent(
                        "TCS20.03.7 INPUT SET SETTLE RETRY key=${pending.key} " +
                            "mismatches=${pending.mismatchCount}"
                    )
                    scheduleInputSetterReadback(pending.key, 0)
                } else {
                    failPendingInputSetter("Input setting was not confirmed by recorder readback")
                }
            }
        }
        appendEvent("TCS20.03.6.13 EXT INPUT COMPLETE generation=$inputExtendedGeneration completed=$completed/${InputExtendedReadPlan.probes.size} unchanged=$unchanged")
    }

    private fun queueExtendedInputProbe(): Boolean {
        if (!inputExtendedRunning) return false
        if (inputExtendedIndex >= InputExtendedReadPlan.probes.size) {
            finishExtendedInput()
            return true
        }
        if (pendingRemotePacket != null) {
            appendEvent(
                "TCS20.03.6.10-R2 EXT INPUT defer real-owner=${pendingRemotePacket?.label}"
            )
            mainHandler.postDelayed(
                { queueExtendedInputProbe() },
                InputExtendedStartPolicy.RETRY_WHILE_PACKETLINK_BUSY_MS
            )
            return true
        }

        // R27.01: give a pending, MTU-ready verified Audio GET one bounded
        // turn only BETWEEN Extended Input probes, never during an active packet.
        // This retains the original input probe index, variant and response contract.
        if (audioR2701YieldAllowed()) {
            mainHandler.postDelayed({ queueExtendedInputProbe() }, 180L)
            return true
        }
        // R27.08 Mic/Line: one non-local polling GET in this genuinely idle gap.
        // Preserve Input index/variant and never change the active Input payload.
        if (r2708MicYieldToInputGap()) {
            mainHandler.postDelayed({ queueExtendedInputProbe() }, 180L)
            return true
        }
        val probe = InputExtendedReadPlan.probes[inputExtendedIndex]
        val localRoute = inputExtendedVariant == 1
        val packetId = if (localRoute) 0 else {
            tcsCommandSequence = if (tcsCommandSequence >= 255) 1 else tcsCommandSequence + 1
            tcsCommandSequence
        }
        val body = buildOfficialAtomMessageBodyWithProtoPayload(packetId, probe.commandId, byteArrayOf())
        val packet = PendingRemotePacket(
            body = body,
            purpose = "remote-input-extended:${probe.key}:v$inputExtendedVariant",
            label = probe.label,
            commandId = probe.commandId,
            packetId = packetId,
            expectedResponseId = null
        )

        inputExtendedActive = probe
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        appendEvent("TCS20.03.6.10 EXT INPUT TX index=$inputExtendedIndex label=${probe.label} cmd=0x${probe.commandId.toString(16).uppercase()} variant=$inputExtendedVariant local=$localRoute packetId=$packetId")
        val queued = queueRemotePacket18_8(packet, local = localRoute)
        if (!queued) {
            pendingRemotePacket = null
            inputExtendedActive = null
            appendEvent(
                "TCS20.03.6.10-R2 EXT INPUT waiting-for-packetlink-ready " +
                    "index=$inputExtendedIndex state=${packetLink18.state()}"
            )
            mainHandler.postDelayed(
                { queueExtendedInputProbe() },
                InputExtendedStartPolicy.RETRY_WHILE_PACKETLINK_BUSY_MS
            )
            return true
        }
        armExtendedInputTimeout(probe)
        return true
    }

    private fun armExtendedInputTimeout(probe: InputExtendedReadPlan.Probe) {
        inputExtendedTimeout?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            if (!inputExtendedRunning || inputExtendedActive != probe) return@Runnable
            appendEvent("TCS20.03.6.10 EXT INPUT TIMEOUT label=${probe.label} cmd=0x${probe.commandId.toString(16).uppercase()} variant=$inputExtendedVariant")
            if (!_tcsRemoteState.value.available) {
                finishExtendedInput(status = "Recorder transport ended during Input refresh")
                return@Runnable
            }
            if (inputExtendedVariant == 1) {
                appendEvent(
                    "TCS20.03.6.10-R10 EXT INPUT local-first timeout; trying non-local fallback label=${probe.label}"
                )
                inputExtendedVariant = 0
                inputExtendedActive = null
                mainHandler.postDelayed({ queueExtendedInputProbe() }, 220L)
                return@Runnable
            }
            inputExtendedValues[probe.key] = "NO_RESPONSE"
            inputExtendedIndex += 1
            inputExtendedVariant = 1
            inputExtendedActive = null
            publishExtendedInput(true, false, "Reading Input details...")
            mainHandler.postDelayed({ queueExtendedInputProbe() }, 25L)
        }
        inputExtendedTimeout = task
        mainHandler.postDelayed(task, 1600L)
    }

    private fun handleExtendedInputPayload(messageType: Int, body: ByteArray, local: Boolean): Boolean {
        val probe = inputExtendedActive ?: return false
        if (!inputExtendedRunning) return false
        if (messageType == REMOTE_GENERIC_SUCCESS || body.isEmpty()) return false

        inputExtendedTimeout?.let { mainHandler.removeCallbacks(it) }
        inputExtendedTimeout = null

        if (messageType == 0xFFFF || messageType == 0xFFFE) {
            if (inputExtendedVariant == 1) {
                appendEvent(
                    "TCS20.03.6.10-R10 EXT INPUT local-first reject; trying non-local fallback label=${probe.label}"
                )
                inputExtendedVariant = 0
                inputExtendedActive = null
                mainHandler.postDelayed({ queueExtendedInputProbe() }, 220L)
                return true
            }
            inputExtendedValues[probe.key] = "REJECT"
            inputExtendedIndex += 1
            inputExtendedVariant = 1
            inputExtendedActive = null
            publishExtendedInput(true, false, "Reading Input details...")
            mainHandler.postDelayed({ queueExtendedInputProbe() }, 25L)
            return true
        }

        val field1 = protoVarintField(body, 1)
        val printable = printableProtoText(body)
        val raw = body.toHex()
        inputExtendedValues[probe.key] = InputExtendedReadPlan.displayValue(probe.key, body, printable)
        inputExtendedResponseTypes[probe.key] = messageType
        inputExtendedRawPayloads[probe.key] = raw
        appendEvent("TCS20.03.6.10 EXT INPUT RX label=${probe.label} cmd=0x${probe.commandId.toString(16).uppercase()} response=0x${messageType.toString(16).uppercase()} local=$local field1=${field1 ?: -1} raw=$raw")
        confirmPendingInputSetter(probe.key, field1)

        if (probe.key == "changeId") {
            val token = "${messageType.toString(16)}:$raw"
            val previous = inputExtendedLastChangeToken
            inputExtendedLastChangeToken = token
            if (previous != null && previous == token && _inputExtendedSnapshot.value.values.isNotEmpty()) {
                finishExtendedInput(true, "Input state unchanged")
                return true
            }
        }

        inputExtendedIndex += 1
        inputExtendedVariant = 1
        inputExtendedActive = null
        publishExtendedInput(true, false, "Reading Input details...")
        mainHandler.postDelayed({ queueExtendedInputProbe() }, 25L)
        return true
    }

    fun refreshGranularInputState(sourceOnly: Boolean = false): Boolean {
        if (!_tcsPairingState.value.acceptWritten || !_tcsRemoteState.value.available || !packetLink18Primed) {
            appendEvent("TCS19.12.10 COLLECTOR BLOCKED accept=${_tcsPairingState.value.acceptWritten} available=${_tcsRemoteState.value.available} primed=$packetLink18Primed")
            return false
        }
        if (inputCollectorRunning) {
            appendEvent("TCS19.12.10 COLLECTOR already-running generation=$inputCollectorGeneration index=$inputProbeIndex")
            return true
        }
        if (remoteBootstrapActive || pendingRemotePacket != null || pendingOps.isNotEmpty()) {
            // AGENT7 20.03.4.1 READY-GATED FIRST-CONNECT
            //
            // Do not retry Input on a fixed timer while Remote bootstrap still owns
            // PacketLink. Instead keep exactly one pending Input refresh and poll the
            // existing transport state until bootstrap/pending packet/pending op queue
            // have actually drained. Pairing and PacketLink sequencing are unchanged.
            if (!inputCollectorRefreshPending) {
                inputCollectorRefreshPending = true
                appendEvent(
                    "TCS20.03.4.1 INPUT READY-GATE armed " +
                        "bootstrap=$remoteBootstrapActive pending=${pendingRemotePacket?.label} " +
                        "attBusy=$operationInFlight queue=${pendingOps.size}"
                )

                var readyGateChecks = 0
                lateinit var readyGateRunnable: Runnable
                readyGateRunnable = Runnable {
                    if (!inputCollectorRefreshPending || inputCollectorRunning) {
                        return@Runnable
                    }

                    readyGateChecks += 1
                    // INPUT23: E0 credit/read heartbeat may keep ATT activity non-idle.
                    // Arm core ownership after the REAL RemoteApi/bootstrap owner has
                    // finished; queueCurrentInputProbe() still serializes every GATT
                    // operation and waits for an actual free transmission slot.
                    val packetLinkReadyForInput =
                        packetLink18Primed &&
                        !remoteBootstrapActive &&
                        pendingRemotePacket == null

                    if (packetLinkReadyForInput) {
                        inputCollectorRefreshPending = false
                        appendEvent(
                            "INPUT23 CORE ARMED after bootstrap checks=$readyGateChecks attBusy=$operationInFlight attQueue=${pendingOps.size}"
                        )
                        refreshGranularInputState(sourceOnly = sourceOnly)
                    } else if (readyGateChecks < 400) {
                        appendEvent(
                            "INPUT22 CORE READY-GATE wait checks=$readyGateChecks " +
                                "bootstrap=$remoteBootstrapActive pending=${pendingRemotePacket?.label} " +
                                "attBusy=$operationInFlight queue=${pendingOps.size}"
                        )
                        mainHandler.postDelayed(readyGateRunnable, 50L)
                    } else {
                        inputCollectorRefreshPending = false
                        appendEvent(
                            "TCS20.03.4.1 INPUT READY-GATE timeout " +
                                "bootstrap=$remoteBootstrapActive pending=${pendingRemotePacket?.label} " +
                                "attBusy=$operationInFlight queue=${pendingOps.size}"
                        )
                    }
                }
                mainHandler.postDelayed(readyGateRunnable, 50L)
            } else {
                appendEvent(
                    "TCS20.03.4.1 INPUT READY-GATE already-armed " +
                        "bootstrap=$remoteBootstrapActive pending=${pendingRemotePacket?.label}"
                )
            }
            return true
        }

        if (operationInFlight) {
            appendEvent(
                "TCS19.12.17.18 INPUT ATT BUSY accepted-for-serialized-start " +
                    "activeOp=${activeOp?.javaClass?.simpleName ?: "null"} queue=${pendingOps.size}"
            )
        }
        inputCollectorRefreshPending = false
        inputSourceOnlyReadback = sourceOnly
        if (sourceOnly) appendEvent("INPUT05C.2 SOURCE-ONLY GET starting with verified existing route/parser")
        inputCollectorGeneration += 1
        appendEvent("TCS20.12.3 INPUT REFRESH RETAIN previousValues=${inputCollectorValues.size}")
        inputProbeIndex = 0
        inputProbeVariant = 0
        inputCollectorRunning = true
        activeInputProbe = null
        publishInputCodecCollector(running = true, source = "input-start")
        appendEvent(
            "TCS19.12.17.18 INPUT MANUAL COLLECTOR START " +
                "generation=$inputCollectorGeneration inputGetters=${inputGranularProbePlan.size} " +
                "attBusy=$operationInFlight appPollingGate=0"
        )
        return queueCurrentInputProbe()
    }

    @SuppressLint("MissingPermission")
    private fun queueCurrentInputProbe(): Boolean {
        if (!inputCollectorRunning) return false
        if (inputProbeIndex >= (if (inputSourceOnlyReadback) 1 else inputGranularProbePlan.size)) {
            finishInputCollector()
            return true
        }
        if (pendingRemotePacket != null || operationInFlight || pendingOps.isNotEmpty()) {
            // INPUT23: do not add 120ms per probe just to revisit a busy ATT slot.
            // No concurrent write is issued: the same owner checks remain mandatory.
            mainHandler.postDelayed({ queueCurrentInputProbe() }, 25L)
            return true
        }

        val probe = if (inputSourceOnlyReadback) {
            inputGranularProbePlan.first { it.key == "source" }
        } else {
            inputGranularProbePlan[inputProbeIndex]
        }
        // INPUT16: Latest AG2 hardware trace confirmed local+empty route 2
        // for Source, HDR and Device Name. inputProbeVariant remains the
        // ATTEMPT index (0..3) so all timeout/reject fallbacks stay intact.
        // On those three keys try actual route 2, then 0, 1, 3.
        // Other Input probes retain the original routing sequence.
        val input16Core = probe.key == "source" || probe.key == "hdrState" || probe.key == "deviceName"
        val requestVariant = if (input16Core) intArrayOf(2, 0, 1, 3)[inputProbeVariant] else inputProbeVariant
        if (input16Core) {
            appendEvent("INPUT16 CORE ROUTE attempt=$inputProbeVariant wireVariant=$requestVariant key=${probe.key}")
        }
        val localRoute = requestVariant >= 2
        val protoTrue = requestVariant == 1 || requestVariant == 3

        // Diagnostic read-only route matrix:
        //   0 = non-local + EMPTY
        //   1 = non-local + 08 01
        //   2 = local + EMPTY, packetId 0
        //   3 = local + 08 01, packetId 0
        // No setter is invoked by any variant.
        val packetId = if (localRoute) {
            0
        } else {
            tcsCommandSequence = if (tcsCommandSequence >= 255) 1 else tcsCommandSequence + 1
            tcsCommandSequence
        }

        val requestProto = if (protoTrue) byteArrayOf(0x08, 0x01) else byteArrayOf()
        val body = buildOfficialAtomMessageBodyWithProtoPayload(packetId, probe.commandId, requestProto)
        val packet = PendingRemotePacket(
            body = body,
            purpose = "remote-input-route-matrix:${probe.commandId}:v$requestVariant",
            label = probe.label,
            commandId = probe.commandId,
            packetId = packetId,
            expectedResponseId = probe.expectedResponseId
        )

        activeInputProbe = probe
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false

        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = if (tcsPacketRemoteCredit) "sending" else "requesting-credit",
            available = true,
            lastCommand = probe.label,
            lastCommandId = probe.commandId,
            lastStatus = "Reading ${probe.label} route variant $requestVariant",
            lastError = null
        )

        appendEvent(
            "TCS19.12.19 INPUT ROUTE TX index=$inputProbeIndex label=${probe.label} " +
                "cmd=0x${probe.commandId.toString(16).uppercase()} " +
                "expected=0x${probe.expectedResponseId.toString(16).uppercase()} " +
                "packetId=$packetId m_isLocal=${if (localRoute) 1 else 0} variant=$requestVariant " +
                "payload=${if (protoTrue) "0801" else "EMPTY"}"
        )

        val queued = queueRemotePacket18_8(packet, local = localRoute)
        if (!queued) {
            pendingRemotePacket = null
            activeInputProbe = null
            mainHandler.postDelayed({ queueCurrentInputProbe() }, 180L)
            return true
        }

        // INPUT06F: start semantic timeout only after real PacketLink delivery,
        // not when this read-only GET is merely queued behind other requests.
        appendEvent("INPUT06F GET QUEUED key=${probe.key} route=$requestVariant awaiting-transport")
        return true
    }

    private fun armInputProbeSemanticTimeout(probe: InputGranularProbe, variant: Int) {
        inputProbeTimeout?.let { mainHandler.removeCallbacks(it) }

        val task = Runnable {
            if (!inputCollectorRunning || activeInputProbe != probe) return@Runnable

            appendEvent(
                "TCS19.12.19 INPUT ROUTE TIMEOUT label=${probe.label} " +
                    "cmd=0x${probe.commandId.toString(16).uppercase()} " +
                    "expected=0x${probe.expectedResponseId.toString(16).uppercase()} route=$variant"
            )

            if (variant < 3) {
                inputProbeVariant = variant + 1
                activeInputProbe = null
                publishInputCodecCollector(
                    running = true,
                    source = "input-route-retry-${probe.key}-v${variant + 1}"
                )
                appendEvent(
                    "TCS19.12.19 INPUT ROUTE NEXT label=${probe.label} " +
                        "from=$variant to=${variant + 1}"
                )
                mainHandler.postDelayed({ queueCurrentInputProbe() }, 140L)
                return@Runnable
            }

            inputCollectorValues[probe.key] = "NO_RESPONSE_ALL_ROUTES"
            inputProbeIndex += 1
            inputProbeVariant = 0
            activeInputProbe = null
            publishInputCodecCollector(
                running = true,
                source = "input-route-exhausted-${probe.key}"
            )
            mainHandler.postDelayed({ queueCurrentInputProbe() }, 120L)
        }

        inputProbeTimeout = task
        // Allow the serialized ATT queue time to drain before treating a
        // missing typed RemoteApi reply as a failed route. E0 is only transport ACK.
        mainHandler.postDelayed(task, 1800L)
    }

    // INPUT06A BOOTSTRAP RX: passive state reuse; no extra commands and no guessed state.
    // Accept only a typed reply matching the command, packet ID, and expected response type.
    private fun input06aCaptureBootstrapResponse(packetId: Int, messageType: Int, body: ByteArray) {
        val expected = input06aBootstrapPending[packetId] ?: return
        if ((messageType and 0x7FFF) != expected.first || body.isEmpty()) return
        input06aBootstrapPending.remove(packetId)
        val field = protoVarintField(body, 1)
        val printable = printableProtoText(body)
        val key = when (expected.second) {
            "Input.SourceGet" -> "source"
            "Input.HdrStateGet" -> "hdrState"
            "Input.DeviceNameGet" -> "deviceName"
            else -> return
        }
        // Only actual recorder responses. Never count requests, E0 transport turns or cache guesses.
        val description = buildString {
            append("bootstrap type 0x")
            append((messageType and 0x7FFF).toString(16).uppercase())
            if (field != null) append(" ID $field")
            if (!printable.isNullOrBlank()) append(" ").append(printable.take(72))
            append(" raw ").append(body.toHex())
        }
        inputCollectorValues[key] = description
        when (key) {
            "source" -> if (field != null) _inputFormatInfo.value = "Input Source ID $field"
            "hdrState" -> if (field != null) _inputGammaId.value = field
            "deviceName" -> if (!printable.isNullOrBlank()) _connectedRecorderName.value = printable.take(64)
        }
        appendEvent("INPUT06A BOOTSTRAP RX key=$key packetId=$packetId response=0x${messageType.toString(16).uppercase()} bytes=${body.size}")
        publishInputCodecCollector(running = inputCollectorRunning, source = "bootstrap-typed-$key")
    }

    private fun handleInputGranularPayload(messageType: Int, body: ByteArray, local: Boolean): Boolean {
        val probe = activeInputProbe ?: return false
        if (!inputCollectorRunning) return false

        val normalized = messageType and 0x7FFF
        val reject = messageType == 0xFFFF || messageType == 0xFFFE
        val expected = normalized == probe.expectedResponseId

        if (!expected && !reject) return false

        inputProbeTimeout?.let { mainHandler.removeCallbacks(it) }
        inputProbeTimeout = null

        if (reject) {
            appendEvent(
                "TCS19.12.18 INPUT CORE REJECT label=${probe.label} " +
                    "cmd=0x${probe.commandId.toString(16).uppercase()} " +
                    "expected=0x${probe.expectedResponseId.toString(16).uppercase()} " +
                    "response=0x${messageType.toString(16).uppercase()} local=$local variant=$inputProbeVariant"
            )

            if (inputProbeVariant < 3) {
                val nextVariant = inputProbeVariant + 1
                appendEvent(
                    "TCS19.12.19 INPUT ROUTE REJECT_NEXT label=${probe.label} " +
                        "from=$inputProbeVariant to=$nextVariant response=0x${messageType.toString(16).uppercase()}"
                )
                inputProbeVariant = nextVariant
                activeInputProbe = null
                publishInputCodecCollector(
                    running = true,
                    source = "input-route-retry-reject-${probe.key}-v$nextVariant"
                )
                mainHandler.postDelayed({ queueCurrentInputProbe() }, 140L)
                return true
            }

            inputCollectorValues[probe.key] =
                "REJECT_ALL_ROUTES_0x${messageType.toString(16).uppercase()}"
            inputProbeIndex += 1
            inputProbeVariant = 0
            activeInputProbe = null
            publishInputCodecCollector(
                running = true,
                source = "input-binary-core-reject-${probe.key}"
            )
            mainHandler.postDelayed({ queueCurrentInputProbe() }, 120L)
            return true
        }

        val value = protoVarintField(body, 1)
        val printable = printableProtoText(body)

        val display = buildString {
            append("type 0x")
            append(messageType.toString(16).uppercase())

            if (value != null) {
                append(" Ãƒâ€šÃ‚Â· ID ")
                append(value)
            }

            if (!printable.isNullOrBlank()) {
                append(" Ãƒâ€šÃ‚Â· ")
                append(printable.take(72))
            }

            if (body.isNotEmpty()) {
                append(" Ãƒâ€šÃ‚Â· raw ")
                append(body.toHex())
            }
        }

        inputCollectorValues[probe.key] = display

        when (probe.key) {
            "source" -> {
                _inputFormatInfo.value =
                    if (value != null) "Input Source ID $value"
                    else (printable ?: "Input source reported")
            }
            "deviceName" -> {
                if (!printable.isNullOrBlank()) {
                    _connectedRecorderName.value = printable.take(64)
                }
            }
            "hdrState" -> {
                if (value != null) _inputGammaId.value = value
            }
            "inputOutputSummary" -> {
                _inputOutputRawHex.value = body.toHex()
            }
        }

        confirmPendingInputSetter(probe.key, value)
        _inputOutputRawHex.value = body.toHex()

        appendEvent(
            "TCS19.12.17.2 INPUT CORE RX label=${probe.label} " +
                "cmd=0x${probe.commandId.toString(16).uppercase()} " +
                "expected=0x${probe.expectedResponseId.toString(16).uppercase()} " +
                "response=0x${messageType.toString(16).uppercase()} local=$local variant=$inputProbeVariant " +
                "field1=${value ?: -1} raw=${body.toHex()}"
        )

        inputProbeIndex += 1
        inputProbeVariant = 0
        activeInputProbe = null
        publishInputCodecCollector(
            running = true,
            source = "input-binary-core-rx-${probe.key}"
        )
        mainHandler.postDelayed({ queueCurrentInputProbe() }, 120L)
        return true
    }

    private fun finishInputCollector() {
        inputProbeTimeout?.let { mainHandler.removeCallbacks(it) }
        inputProbeTimeout = null
        activeInputProbe = null
        inputCollectorRunning = false
        val wasSourceOnly = inputSourceOnlyReadback
        inputSourceOnlyReadback = false
        if (wasSourceOnly) appendEvent("INPUT05C.2 SOURCE-ONLY GET completed")
        val responses = inputCollectorValues.values.count { !it.startsWith("NO_RESPONSE") && !it.startsWith("REJECT") }
        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = if (remoteApiVerified) "linked" else "transport-ready",
            available = true,
            linked = true,
            lastStatus = "Input data collector complete: $responses/${inputGranularProbePlan.size} getter payloads",
            lastError = null
        )
        publishInputCodecCollector(running = false, source = "input-complete")
        pendingInputSetter?.let { pending ->
            if (pending.key == "source" || pending.key == "hdrState") {
                if (pending.mismatchCount < inputSetterMaxMismatchRetries) {
                    appendEvent(
                        "TCS20.03.7 INPUT SET SETTLE RETRY key=${pending.key} " +
                            "mismatches=${pending.mismatchCount}"
                    )
                    scheduleInputSetterReadback(pending.key, 0)
                } else {
                    failPendingInputSetter("Input setting was not confirmed by recorder readback")
                }
            }
        }
        appendEvent(
            "TCS19.12.17.18 INPUT MANUAL COLLECTOR COMPLETE " +
                "generation=$inputCollectorGeneration responses=$responses/${inputGranularProbePlan.size}"
        )
    }

    private fun publishInputCodecCollector(running: Boolean = inputCollectorRunning, source: String) {
        val responses = inputCollectorValues.values.count { !it.startsWith("NO_RESPONSE") && !it.startsWith("REJECT") }
        val codec = _codecHardwareProfile.value
        val q = 34.toChar()
        val valuesJson = inputCollectorValues.entries.joinToString(",") { (key, value) ->
            "$q${jsonEscape(key)}$q:$q${jsonEscape(value)}$q"
        }
        val json = buildString {
            append('{')
            append(q); append("generation"); append(q); append(':'); append(inputCollectorGeneration); append(',')
            append(q); append("source"); append(q); append(':'); append(q); append(jsonEscape(source)); append(q); append(',')
            append(q); append("running"); append(q); append(':'); append(running); append(',')
            append(q); append("inputExpected"); append(q); append(':'); append(inputGranularProbePlan.size); append(',')
            append(q); append("inputResponses"); append(q); append(':'); append(responses); append(',')
            append(q); append("codecProfile"); append(q); append(':'); append(codec?.first ?: -1); append(',')
            append(q); append("codecBitDepth"); append(q); append(':'); append(codec?.second ?: -1); append(',')
            append(q); append("input"); append(q); append(':'); append('{'); append(valuesJson); append('}'); append('}')
        }
        _inputCodecCollector.value = InputCodecCollectorSnapshot(
            generation = inputCollectorGeneration,
            running = running,
            inputExpected = inputGranularProbePlan.size,
            inputResponses = responses,
            inputValues = LinkedHashMap(inputCollectorValues),
            codecProfile = codec?.first,
            codecBitDepth = codec?.second,
            codecVerified = codec != null,
            json = json
        )
        appendEvent("TCS19.12.10 SNAPSHOT $json")
    }

    /** Public non-destructive verifier for the currently selected codec tuple. */
    @SuppressLint("MissingPermission")
    fun verifyOfficialCodecProfile(codec: Int, bitDepth: Int): Boolean {
        if (codec !in 1..19 || bitDepth !in 0..2) return false
        return queueOfficialCodecProfileGet(codec, bitDepth, "manual-verify")
    }

    /** Phase 09 read-only Input / Format refresh. The macOS Remote screen
     * already issues AppPolling.InputOutputSummaryGet (0x0648) during startup.
     * This explicit refresh uses the same proven GET and never changes recorder
     * Input, Gamma, Gamut, Trigger or Output settings. */
    @SuppressLint("MissingPermission")
    fun refreshOfficialOutputHdrReadback(): Boolean {
        // R9 uses the existing 0x00DD -> 0x00DC contract, now as a guarded
        // local=true OUTPUT-only read probe. No SET commands are emitted.
        appendEvent("OUTPUT0614 HDR GET REQUEST id=0x00DD expected=0x00DC")
        return queueOfficialLutGetter(
            commandId = 0x00DD,
            expectedResponseId = 0x00DC,
            proto = byteArrayOf(),
            purpose = "remote-output-hdr-enable-get",
            label = "HDR Out Readback"
        )
    }

    // R26.11.2: Audio-only deferred GET after the EXISTING post-pair MTU transaction.
    // Do not change global PacketLink framing, ACK handling, or any other tab.
    private var r26AudioGetWaitingForMtu: Boolean = false

    private fun dispatchR26AudioGetAfterMtu(attemptsLeft: Int) {
        if (!r26AudioGetWaitingForMtu) return
        if (_audioR26Live.value.running) {
            r26AudioGetWaitingForMtu = false
            android.util.Log.i("AtomRemoteAudioR2615", "AUTO_METERS_YIELD_TO_COLLECTOR avoids-duplicate-get")
            return
        }
        if (gatt == null || !_tcsPairingState.value.acceptWritten || !_tcsRemoteState.value.available) {
            r26AudioGetWaitingForMtu = false
            android.util.Log.i("AtomRemoteAudioR26Fix", "AUTO_GET_CANCELLED session-not-ready")
            return
        }
        if (outputR21MtuBytes < 61) {
            r26AudioGetWaitingForMtu = false
            android.util.Log.i("AtomRemoteAudioR26Fix", "AUTO_GET_CANCELLED mtu=$outputR21MtuBytes below61")
            return
        }
        // Respect the existing exclusive ATT/PacketLink owner and Input collector.
        // Never cancel, replace, or preempt a frozen-feature request.
        if (pendingRemotePacket != null || packetLink18.bufferedPacketSize() != 0 ||
            packetLink18.state().toString() != "READY" ||
            operationInFlight || pendingOps.isNotEmpty() || remoteBootstrapActive ||
            inputCollectorRunning || inputExtendedRunning
        ) {
            if (attemptsLeft > 0) {
                mainHandler.postDelayed({ dispatchR26AudioGetAfterMtu(attemptsLeft - 1) }, 700L)
            } else {
                r26AudioGetWaitingForMtu = false
                android.util.Log.i("AtomRemoteAudioR26Fix",
                    "AUTO_GET_DEFER_EXPIRED mtu=$outputR21MtuBytes reason=owner-busy; use manual Refresh")
            }
            return
        }
        r26AudioGetWaitingForMtu = false
        android.util.Log.i("AtomRemoteAudioR26Fix",
            "AUTO_GET_DISPATCH mtu=$outputR21MtuBytes transport=READY bodyExpected>=39")
        val accepted = probeR26AudioMetersPage()
        android.util.Log.i("AtomRemoteAudioR26Fix", "AUTO_GET_RESULT accepted=$accepted")
    }


    // R26.13: isolated Audio GET collector. No SETs, no guessed message IDs,
    // no changes to official TCS pairing, Input owner, or PacketLink serializer.
    private val _audioR26Live = MutableStateFlow(com.atomosremote.nativeapp.audio.AudioR26LiveSnapshot())
    val audioR26Live: StateFlow<com.atomosremote.nativeapp.audio.AudioR26LiveSnapshot> = _audioR26Live
    // R26.97 READ-ONLY candidate evidence, scoped to current TCS session.
    // Does not alter pairing, PacketLink state, or Audio-page GET.
    private val _r2697MicLineEvidence = MutableStateFlow(com.atomosremote.nativeapp.audio.AudioR2697MicLineSnapshot())
    val r2697MicLineEvidence: StateFlow<com.atomosremote.nativeapp.audio.AudioR2697MicLineSnapshot> = _r2697MicLineEvidence
    private var r2697MicLineToken = 0L
    private fun r2697MicLineState(key: String, cmd: Int, state: String, detail: String = "") {
        r2697MicLineToken += 1L
        val report = com.atomosremote.nativeapp.audio.AudioR2697MicLineRecord(
            key, cmd, r2697MicLineToken, state, detail = detail)
        _r2697MicLineEvidence.value = _r2697MicLineEvidence.value.copy(
            controls = _r2697MicLineEvidence.value.controls + (key to report))
        android.util.Log.i("AtomRemoteAudioR2697", "MIC_GET_STATE key=$key state=$state cmd=0x${cmd.toString(16)} detail=$detail")
    }

    // R27.06 MIC/LINE: bounded, operator-origin read intents. Main-thread pump only.
    // 'accepted into intents' != PacketLink buffer != ATT callback != device RX.
    private data class R2706MicRead(
        val key: String, val command: Int, val token: Long, val createdMs: Long,
        var stage: String = "PENDING_TRANSPORT", var dispatchedMs: Long = 0L,
        var gattWrittenMs: Long = 0L, var deliveredMs: Long = 0L,
        var requestPacketId: Int = 0, var assembledSeen: Int = 0,
        var typedCandidatesSeen: Int = 0, var invalidFramesSeen: Int = 0,
        var localTrial: Boolean = false // R27.11 limited operator-triggered Gain Left trial
    )
    private val r2706MicReads = ArrayDeque<R2706MicRead>()
    private var r2706MicActive: R2706MicRead? = null
    private var r2706MicPumpTask: Runnable? = null
    private val r2706MicWaitMs = 85_000L
    private val r2706MicAttMs = 18_000L
    private val r2706MicRxMs = 12_000L

    private fun r2706MicMark(read: R2706MicRead, stage: String, detail: String) {
        val old = _r2697MicLineEvidence.value.controls[read.key] ?: return
        if (old.requestToken != read.token) return
        read.stage = stage
        _r2697MicLineEvidence.value = _r2697MicLineEvidence.value.copy(
            controls = _r2697MicLineEvidence.value.controls +
                (read.key to old.copy(state = stage, detail = detail)))
        android.util.Log.i("AtomRemoteAudioR2706", "MIC_STAGE key=${read.key} " +
            "cmd=0x${read.command.toString(16)} token=${read.token} stage=$stage detail=$detail")
    }

    private var r2722LastGainRxMs = 0L
    // A05: Explicit one-shot Input Level pilot evidence; no other SET unlocked.
    private var a05LastInputRxMs = 0L
    private var a05InputPendingTarget: Int? = null
    private var a05InputPreviousRaw: Long? = null
    private var a05InputSetAtMs = 0L
    private var r2722PilotRestoreSelector: Boolean? = null
    private var r2722PilotSinceMs = 0L
    // A12.35: Capture one explicit Gain pilot at a time, and prove which
    // channel changed using only a fresh recorder GET. Never infer SET success
    // from local PacketLink queue acceptance.
    private var a135GainPilotSelector: Boolean? = null
    private var a135GainPilotRestore = false
    private var a135GainPilotBeforeLeft = 0L
    private var a135GainPilotBeforeRight = 0L
    private var a135GainPilotAt = 0L


    private fun r2706MicReset(reason: String) {
        a051CancelSetIntent("session-reset:$reason")
        r2722LastGainRxMs = 0L
        a05LastInputRxMs = 0L
        a05InputPendingTarget = null
        a05InputPreviousRaw = null
        a05InputSetAtMs = 0L
        r2722PilotRestoreSelector = null
        r2722CancelIdleWindow("session-reset")
        r2722PilotSinceMs = 0L
        r2706MicPumpTask?.let { mainHandler.removeCallbacks(it) }
        r2706MicPumpTask = null
        r2706MicReads.clear()
        r2706MicActive = null
        r2708MicTurnOpen = false
        r2708MicTurnGeneration = -1
        r2708MicConsumedGeneration = -1
        android.util.Log.i("AtomRemoteAudioR2706", "MIC_SESSION_RESET reason=$reason")
    }

    private fun r2706MicPumpSoon(delayMs: Long = 250L) {
        if (r2706MicPumpTask != null) return
        val task = Runnable {
            r2706MicPumpTask = null
            r2706MicPump()
        }
        r2706MicPumpTask = task
        mainHandler.postDelayed(task, delayMs)
    }

    @SuppressLint("MissingPermission")
    private fun r2706RequestMicLineRead(key: String, cmd: Int): Boolean {
        if (gatt == null || !_tcsPairingState.value.acceptWritten ||
            !_tcsRemoteState.value.available) {
            r2697MicLineState(key, cmd, "RECORDER_NOT_READY", "Pairing and PacketLink required")
            return false
        }
        if (r2706MicActive?.key == key || r2706MicReads.any { it.key == key }) {
            android.util.Log.i("AtomRemoteAudioR2706", "MIC_DUPLICATE_COLLAPSED key=$key")
            return true
        }
        if (r2706MicReads.size >= 4) {
            r2697MicLineState(key, cmd, "READ_QUEUE_FULL", "Maximum four distinct pending controls")
            return false
        }
        r2697MicLineState(key, cmd, "PENDING_TRANSPORT", "Queued locally, no BLE TX yet")
        val token = _r2697MicLineEvidence.value.controls[key]?.requestToken ?: return false
        val intent = R2706MicRead(key, cmd, token, android.os.SystemClock.elapsedRealtime())
        // A12.32: a delay confirmation requested after a SET must not sit behind
        // queued Gain/Input GETs. Never interrupt an active ATT or PacketLink op.
        if (key == "delay" && a124DelayPendingGet) {
            r2706MicReads.addFirst(intent)
            android.util.Log.i("AtomRemoteAudioA132", "DELAY_GET_PRIORITY queuedFirst=true active=${r2706MicActive?.key ?: "none"} preempt=false")
        } else {
            r2706MicReads.addLast(intent)
        }
        android.util.Log.i("AtomRemoteAudioR2706", "MIC_INTENT_ACCEPTED key=$key " +
            "cmd=0x${cmd.toString(16)} token=$token local=false body=EMPTY " +
            "bleTransmitted=false depth=${r2706MicReads.size}")
        r2706MicPumpSoon(0L)
        return true
    }

    // R27.15: strictly scoped Audio In Level read (InputSourceGet 0x06B3).
    // Official macOS expected reply base: 0x06AE. Recorder reply is not yet verified.
    // This does not change Gain, TCS, PacketLink, or any remote SET.
    @SuppressLint("MissingPermission")
    private fun r2715EnqueueMicInputLevelTrial(read: R2706MicRead): Boolean {
        if (!com.atomosremote.nativeapp.audio.AudioR2715InputLevelTrial.eligible(read.key, read.command)) return false
        val current = gatt ?: return false
        val characteristic = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
            ?.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false
        if (pendingRemotePacket != null || packetLink18.bufferedPacketSize() != 0) return false
        val purpose = "r2672-micline-get:${read.key}"
        val body = buildOfficialAtomMessageBodyWithProtoPayload(0, read.command, byteArrayOf())
        val packet = PendingRemotePacket(body = body, purpose = purpose,
            label = "R2715 Audio In Level GET only", commandId = read.command, packetId = 0)
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        val buffered = queueRemotePacket18_8(packet, local = true)
        if (!buffered) {
            if (pendingRemotePacket === packet) {
                pendingRemotePacket = null
                pendingRemoteFrameWritten = false
            }
            android.util.Log.i("AtomRemoteAudioR2715", "INPUT_LEVEL_LOCAL_DEFER noTx=true")
            return false
        }
        android.util.Log.i("AtomRemoteAudioR2715", "INPUT_LEVEL_LOCAL_BUFFER " +
            "key=${read.key} cmd=0x${read.command.toString(16)} packetId=0 " +
            "local=true proto=EMPTY body=${body.toHex()} expectedATT=E8000000B306 " +
            "physicalTxPending=true optionMappingVerified=false setTx=false")
        return true
    }

    // R27.11: verified local GET wire shape is E8 + packetID(0) + len(0)
    // + opcode(0x06B6). This is an EXPERIMENTAL Gain Left read only.
    // No nonlocal fallback, SET, retries or changes to the shared serializer.
    @SuppressLint("MissingPermission")
    private fun r2711EnqueueMicLocalTrial(read: R2706MicRead): Boolean {
        if (!com.atomosremote.nativeapp.audio.AudioR2711LocalGainTrial.eligible(read.key, read.command)) return false
        val current = gatt ?: return false
        val characteristic = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
            ?.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false
        if (pendingRemotePacket != null || packetLink18.bufferedPacketSize() != 0) return false
        val purpose = "r2672-micline-get:${read.key}"
        val body = buildOfficialAtomMessageBodyWithProtoPayload(0, read.command, byteArrayOf())
        val packet = PendingRemotePacket(body = body, purpose = purpose,
            label = "R2711 local Gain Left trial", commandId = read.command, packetId = 0)
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        val buffered = queueRemotePacket18_8(packet, local = true)
        if (!buffered) {
            if (pendingRemotePacket === packet) {
                pendingRemotePacket = null
                pendingRemoteFrameWritten = false
            }
            android.util.Log.w("AtomRemoteAudioR2711", "MIC_LOCAL_TRIAL_DEFER noTx=true")
            return false
        }
        android.util.Log.i("AtomRemoteAudioR2711", "MIC_LOCAL_TRIAL_BUFFER " +
            "key=${read.key} cmd=0x${read.command.toString(16)} " +
            "packetId=0 local=true proto=EMPTY body=${body.toHex()} " +
            "expectedATT=E8000000B606 physicalTxPending=true numericVerified=false")
        return true
    }

    // A10 one-shot operator-triggered FrameDelayGet pilot. No SET and no fallback.
    @SuppressLint("MissingPermission")
    private fun a10EnqueueDelayLocalGet(read: R2706MicRead): Boolean {
        if (read.key != "delay" || read.command != 0x06B9) return false
        val connected = gatt ?: return false
        val ch = connected.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
            ?.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false
        if (pendingRemotePacket != null || packetLink18.bufferedPacketSize() != 0) return false
        val payload = buildOfficialAtomMessageBodyWithProtoPayload(0, 0x06B9, byteArrayOf())
        val packet = PendingRemotePacket(payload, "r2672-micline-get:delay",
            "A10 Delay local GET pilot", 0x06B9, 0)
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        val queued = queueRemotePacket18_8(packet, local = true)
        if (!queued && pendingRemotePacket === packet) {
            pendingRemotePacket = null
            pendingRemoteFrameWritten = false
        }
        android.util.Log.i("AtomRemoteAudioA10", "DELAY_LOCAL_GET queued=$queued " +
            "cmd=0x06B9 local=true packetId=0 proto=EMPTY " +
            "expectedATT=E8000000B906 setTx=false numericVerified=false")
        return queued
    }

    // packet ID, like existing non-local Input and Display getters. The general
    // payload helper is intentionally unchanged because local SETs use ID zero.
    @SuppressLint("MissingPermission")
    private fun r2708EnqueueMicNonLocalRead(read: R2706MicRead): Boolean {
        val current = gatt ?: return false
        val characteristic = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
            ?.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false
        if (pendingRemotePacket != null || packetLink18.bufferedPacketSize() != 0) return false
        // same sequence rule as queueOfficialDisplayGetter / non-local Input probes
        tcsCommandSequence = if (tcsCommandSequence >= 255) 1 else tcsCommandSequence + 1
        val packetId = tcsCommandSequence
        val purpose = "r2672-micline-get:${read.key}"
        val body = buildOfficialAtomMessageBodyWithProtoPayload(packetId, read.command, byteArrayOf())
        val packet = PendingRemotePacket(
            body = body, purpose = purpose, label = "R2708 Mic GET ${read.key}",
            commandId = read.command, packetId = packetId
        )
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        val buffered = queueRemotePacket18_8(packet, local = false)
        if (!buffered) {
            if (pendingRemotePacket === packet) {
                pendingRemotePacket = null
                pendingRemoteFrameWritten = false
            }
            android.util.Log.w("AtomRemoteAudioR2708", "MIC_NONLOCAL_ENQUEUE_DEFER " +
                "key=${read.key} cmd=0x${read.command.toString(16)} id=$packetId")
            return false
        }
        android.util.Log.i("AtomRemoteAudioR2708", "MIC_NONLOCAL_PACKET_ID " +
            "key=${read.key} cmd=0x${read.command.toString(16)} " +
            "packetId=$packetId local=false emptyProto=true body=${body.toHex()} " +
            "bufferedOnly=true physicalTxPending=true numericVerified=false")
        return true
    }

    // R27.08: exactly one bounded Mic/Line polling opportunity between two
    // completed Extended Input probes. Never interrupt a current Input packet.
    private var r2708MicTurnOpen = false
    private var r2708MicTurnGeneration = -1
    private var r2708MicTurnIndex = -1
    private var r2708MicTurnStartedMs = 0L
    private var r2708MicConsumedGeneration = -1
    private var r2708MicConsumedIndex = -1
    private fun r2708MicYieldToInputGap(): Boolean {
        if (!inputExtendedRunning || r2706MicActive != null || r2706MicReads.isEmpty() ||
            _audioR26Live.value.running || inputExtendedActive != null ||
            inputCollectorRunning || remoteBootstrapActive || pendingRemotePacket != null ||
            pendingOps.isNotEmpty() || operationInFlight ||
            packetLink18.bufferedPacketSize() != 0 ||
            packetLink18.state().toString() != "READY") {
            r2708MicTurnOpen = false
            return false
        }
        if (r2708MicConsumedGeneration == inputExtendedGeneration &&
            r2708MicConsumedIndex == inputExtendedIndex) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (r2708MicTurnGeneration != inputExtendedGeneration ||
            r2708MicTurnIndex != inputExtendedIndex) {
            r2708MicTurnGeneration = inputExtendedGeneration
            r2708MicTurnIndex = inputExtendedIndex
            r2708MicTurnStartedMs = now
        }
        if (now - r2708MicTurnStartedMs >= 800L) {
            r2708MicTurnOpen = false
            android.util.Log.i("AtomRemoteAudioR2708", "MIC_FAIR_GAP_EXPIRED " +
                "inputIndex=$inputExtendedIndex queued=${r2706MicReads.size}")
            return false
        }
        r2708MicTurnOpen = true
        r2706MicPumpTask?.let { mainHandler.removeCallbacks(it) }
        r2706MicPumpTask = null
        r2706MicPumpSoon(0L)
        android.util.Log.i("AtomRemoteAudioR2708", "MIC_FAIR_GAP_GRANTED " +
            "inputIndex=$inputExtendedIndex total=${InputExtendedReadPlan.probes.size} " +
            "queued=${r2706MicReads.size} noActiveInputPacket=true")
        return true
    }

    @SuppressLint("MissingPermission")
    private fun r2706MicPump() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (gatt == null || !_tcsPairingState.value.acceptWritten ||
            !_tcsRemoteState.value.available) {
            r2706MicActive?.let { r2706MicMark(it, "SESSION_ENDED", "Recorder disconnected") }
            r2706MicReads.forEach { r2706MicMark(it, "SESSION_ENDED", "Recorder disconnected") }
            r2706MicReset("transport-not-ready")
            return
        }
        if (r2706MicReads.isNotEmpty()) {
            val head = r2706MicReads.first()
            val waitMs = now - head.createdMs
            if (waitMs >= 1500L && waitMs % 3000L < 300L) {
                android.util.Log.i("AtomRemoteAudioA125", "MIC_GET_WAIT " +
                    "key=${head.key} waitMs=$waitMs queueDepth=${r2706MicReads.size} " +
                    "active=${r2706MicActive?.key ?: "none"} inputExtended=$inputExtendedRunning " +
                    "audioRunning=${audioR26Live.value.running} attBusy=$operationInFlight " +
                    "pendingOps=${pendingOps.size} pendingPacket=${pendingRemotePacket?.purpose ?: "none"}")
            }
        }
        val active = r2706MicActive
        if (active != null) {
            val elapsed = now - active.dispatchedMs
            val timeout = when {
                active.deliveredMs > 0L -> now - active.deliveredMs >= r2706MicRxMs
                active.gattWrittenMs > 0L -> elapsed >= r2706MicAttMs + r2706MicRxMs
                else -> elapsed >= r2706MicAttMs
            }
            if (timeout) {
                val state = when {
                    active.deliveredMs > 0L -> "RX_TIMEOUT_AFTER_DELIVERY"
                    active.gattWrittenMs > 0L -> "DELIVERY_TIMEOUT_AFTER_ATT"
                    else -> "ATT_WRITE_UNCONFIRMED_TIMEOUT"
                }
                r2706MicMark(active, state,
                    "Bounded read ended; transport not reset; no SET; no automatic resend")
                android.util.Log.w("AtomRemoteAudioR2706", "MIC_GET_TIMEOUT " +
                    "key=${active.key} stage=$state elapsedMs=$elapsed " +
                    "assembledSeen=${active.assembledSeen} " +
                    "typedCandidatesSeen=${active.typedCandidatesSeen} " +
                    "invalidFramesSeen=${active.invalidFramesSeen}")
                android.util.Log.i("AtomRemoteAudioR2707", "MIC_RX_WINDOW_SUMMARY " +
                    "key=${active.key} cmd=0x${active.command.toString(16)} token=${active.token} " +
                    "expectedPacketId=${active.requestPacketId} assembled=${active.assembledSeen} " +
                    "typed=${active.typedCandidatesSeen} malformed=${active.invalidFramesSeen} " +
                    "result=NO_VERIFIED_NUMERIC_RESPONSE")
                r2706MicActive = null
            }
        }
        while (r2706MicReads.isNotEmpty() && now - r2706MicReads.first().createdMs > r2706MicWaitMs) {
            val expired = r2706MicReads.removeFirst()
            r2706MicMark(expired, "DEFER_EXPIRED", "Transport busy for 85s; no BLE TX")
        }
        if (r2706MicActive == null && r2706MicReads.isNotEmpty()) {
            val ready = !_audioR26Live.value.running && !remoteBootstrapActive &&
                !inputCollectorRunning && (!inputExtendedRunning || r2708MicTurnOpen) &&
                (inputExtendedActive == null || !inputExtendedRunning) &&
                pendingRemotePacket == null && pendingOps.isEmpty() &&
                !operationInFlight && packetLink18.bufferedPacketSize() == 0 &&
                packetLink18.state().toString() == "READY"
            if (ready) {
                val candidate = r2706MicReads.first()
                // R27.11: one operator-triggered Gain Left read uses the verified
                // E8 local wire ROUTE from the working Input GETs. All other GET
                // paths remain exactly R27.10 and do not auto-fallback.
                val inputLevelTrial = com.atomosremote.nativeapp.audio.AudioR2715InputLevelTrial.eligible(
                    candidate.key, candidate.command)
                // A10: Delay GET local-route pilot, explicitly read-only and manually requested.
                val a10DelayTrial = candidate.key == "delay" && candidate.command == 0x06B9
                val localTrial = inputLevelTrial || a10DelayTrial ||
                    com.atomosremote.nativeapp.audio.AudioR2711LocalGainTrial.eligible(
                        candidate.key, candidate.command)
                val accepted = when {
                    inputLevelTrial -> r2715EnqueueMicInputLevelTrial(candidate)
                    a10DelayTrial -> a10EnqueueDelayLocalGet(candidate)
                    localTrial -> r2711EnqueueMicLocalTrial(candidate)
                    else -> r2708EnqueueMicNonLocalRead(candidate)
                }
                if (accepted) {
                    android.util.Log.i("AtomRemoteAudioA126", "MIC_GET_DISPATCH " +
                        "key=${candidate.key} queueWaitMs=${now - candidate.createdMs} " +
                        "pendingOps=${pendingOps.size} attIdle=${!operationInFlight}")
                    r2706MicReads.removeFirst()
                    candidate.dispatchedMs = now
                    candidate.requestPacketId = pendingRemotePacket?.packetId ?: 0
                    candidate.localTrial = localTrial
                    r2706MicActive = candidate
                    if (r2708MicTurnOpen) {
                        r2708MicConsumedGeneration = inputExtendedGeneration
                        r2708MicConsumedIndex = inputExtendedIndex
                    }
                    r2708MicTurnOpen = false
                    r2706MicMark(candidate, "BUFFER_ACCEPTED", "PacketLink only; awaiting ATT write")
                    android.util.Log.i("AtomRemoteAudioR2706", "MIC_GET_BUFFER_ACCEPTED " +
                        "key=${candidate.key} cmd=0x${candidate.command.toString(16)} " +
                        "packetId=${candidate.requestPacketId} local=$localTrial " +
                        "proto=EMPTY physicalTxConfirmed=false")
                }
            }
        }
        if (r2706MicActive != null || r2706MicReads.isNotEmpty()) r2706MicPumpSoon(300L)
    }

    /** Invoke only after successful PLINK18 ATT write callback, never on enqueue. */
    private fun r2706MicOnAttWrite(bytes: ByteArray) {
        val read = r2706MicActive ?: return
        if (pendingRemotePacket?.purpose != "r2672-micline-get:${read.key}" || bytes.size <= 1) return
        if (read.gattWrittenMs == 0L) {
            read.gattWrittenMs = android.os.SystemClock.elapsedRealtime()
            r2706MicMark(read, "ATT_WRITE_CONFIRMED", "GATT callback success, recorder RX pending")
            android.util.Log.i("AtomRemoteAudioR2706", "MIC_GET_ATT_WRITE_OK " +
                "key=${read.key} cmd=0x${read.command.toString(16)} " +
                "attBytes=${bytes.toHex()} responseVerified=false")
        }
    }

    private fun r2706MicOnDelivered(sent: PendingRemotePacket?) {
        val read = r2706MicActive ?: return
        if (sent?.purpose != "r2672-micline-get:${read.key}" || sent.commandId != read.command) return
        read.deliveredMs = android.os.SystemClock.elapsedRealtime()
        r2706MicMark(read, "TRANSPORT_DELIVERED", "PacketLink completed; waiting typed RX")
        android.util.Log.i("AtomRemoteAudioR2706", "MIC_GET_PLINK_DELIVERED " +
            "key=${read.key} cmd=0x${read.command.toString(16)} " +
            "attWriteObserved=${read.gattWrittenMs > 0L} numericVerified=false")
    }

    /** R27.09: These are the expected reply bases embedded in OFFICIAL macOS
     * Audio poll command objects at offset +0x2C, not the request command ID.
     * Actual response wire opcode may carry high-nibble flags. The official
     * dispatcher normalizes it with 0x0FFF before matching. No TX behavior here.
     */
    private fun r2709ExpectedMicResponseBase(command: Int): Int? =
        com.atomosremote.nativeapp.audio.AudioR2709MacDispatch.expectedReplyBase(command)

    /** R27.07: observes assembled PacketLink payload before the normal demux.
     * A malformed/uncorrelated packet stays diagnostic only. The observer cannot
     * satisfy a GET, mutate numeric UI state or divert another feature's packet.
     */
    private fun r2707MicOnAssembled(payload: ByteArray, local: Boolean) {
        val read = r2706MicActive ?: return
        if (read.gattWrittenMs == 0L) return
        val frame = com.atomosremote.nativeapp.audio.AudioR2707MicWireAudit.inspect(payload)
        read.assembledSeen++
        if (!frame.exactLength) read.invalidFramesSeen++
        val expectedBase = r2709ExpectedMicResponseBase(read.command) ?: return
        val exactMacReply = frame.exactLength && frame.messageType != null &&
            com.atomosremote.nativeapp.audio.AudioR2709MacDispatch.normalize(frame.messageType) == expectedBase
        if (exactMacReply) read.typedCandidatesSeen++
        val interesting = exactMacReply ||
            frame.messageType == 0xFFFF || frame.messageType == 0xFFFE ||
            frame.messageType == 0x8000 || !frame.exactLength
        if (interesting || read.assembledSeen <= 12) {
            val owner = pendingRemotePacket?.purpose ?: "none"
            val raw = if (interesting) payload.take(96).toByteArray().toHex() else "OMITTED_OTHER_TYPE"
            android.util.Log.i("AtomRemoteAudioR2707", "MIC_PRE_FILTER_ASSEMBLED " +
                "key=${read.key} token=${read.token} cmd=0x${read.command.toString(16)} " +
                "expectedBase=0x${expectedBase.toString(16)} normalizedRx=0x${((frame.messageType ?: 0) and 0x0fff).toString(16)} " +
                "kind=${frame.kind} packetId=${frame.packetId ?: -1} " +
                "expectedPacketId=${read.requestPacketId} " +
                "messageType=0x${(frame.messageType ?: 0).toString(16)} local=$local " +
                "declared=${frame.declaredLength ?: -1} actualBody=${frame.bodyLength} " +
                "exact=${frame.exactLength} owner=$owner raw=$raw " +
                "readbackNumericVerified=false")
        }
    }

    /** R27.07: strict demux already validated AtomMessage length before here.
     * Typed GET bodies become candidates, never invented numeric gain/delay values.
     */
    private fun r2706MicOnRx(messageType: Int, body: ByteArray, local: Boolean, packetId: Int) {
        val read = r2706MicActive ?: return
        val expectedBase = r2709ExpectedMicResponseBase(read.command) ?: return
        val purpose = "r2672-micline-get:${read.key}"
        val currentOwner = pendingRemotePacket?.purpose
        // R27.09: Mac dispatch compares (wireOpcode & 0x0FFF) to the +0x2C
        // expected reply base; request opcode | 0x8000 is NOT the contract.
        // Correlate only the exact live nonzero packet ID after verified GATT TX.
        // Recorder can allocate its own response packet ID for local GETs
        // (Input.SourceGet: TX ID 0 -> RX ID 47). Match by exact official
        // response base, local E8 route and exclusive live Mic/Line window.
        val macMatch = if (read.localTrial) {
            val age = android.os.SystemClock.elapsedRealtime() - read.gattWrittenMs
            val ownerSafe = currentOwner == null || currentOwner == purpose
            if (read.key == "inputLevel") {
                com.atomosremote.nativeapp.audio.AudioR2715InputLevelTrial.matches(
                    key = read.key, command = read.command, reply = messageType,
                    local = local, attConfirmed = read.gattWrittenMs > 0L,
                    safeOwner = ownerSafe, sinceAttMs = age)
            } else if (read.key == "delay" && read.command == 0x06B9) {
                // A10: accept only exact official reply base in a live local ATT window.
                val expected = r2709ExpectedMicResponseBase(read.command)
                val matched = expected != null && (messageType and 0x0FFF) == expected &&
                    local && read.gattWrittenMs > 0L && ownerSafe && age in 0L..12000L
                android.util.Log.i("AtomRemoteAudioA10", "DELAY_LOCAL_RX_MATCH accepted=$matched " +
                    "reply=0x${messageType.toString(16)} expected=$expected local=$local " +
                    "attConfirmed=${read.gattWrittenMs > 0L} ageMs=$age numericVerified=false")
                matched
            } else {
                com.atomosremote.nativeapp.audio.AudioR2711LocalGainTrial.matches(
                    key = read.key, requestOpcode = read.command,
                    responseOpcode = messageType, responseLocal = local,
                    attConfirmed = read.gattWrittenMs > 0L,
                    safeOwner = ownerSafe, sinceAttMs = age)
            }
        } else {
            com.atomosremote.nativeapp.audio.AudioR2709MacDispatch.matches(
                read.command, messageType, packetId, read.requestPacketId,
                read.gattWrittenMs > 0L)
        }
        if (!macMatch) {
            if ((messageType and 0x0FFF) == expectedBase || messageType == 0x8000 ||
                messageType == 0xFFFF || messageType == 0xFFFE) {
                android.util.Log.w("AtomRemoteAudioR2709", "MIC_RX_QUARANTINED " +
                    "key=${read.key} token=${read.token} response=0x${messageType.toString(16)} " +
                    "normalized=0x${(messageType and 0x0FFF).toString(16)} " +
                    "expectedBase=0x${expectedBase.toString(16)} " +
                    "packetId=$packetId expectedPacketId=${read.requestPacketId} " +
                    "owner=${currentOwner ?: "none"} reason=UNMATCHED_OFFICIAL_DISPATCH numericVerified=false")
            }
            return
        }
        val status = if (read.localTrial) "R2711_LOCAL_EXACT_OPCODE_ACTIVE_WINDOW"
            else "R2709_MAC_EXPECTED_RESPONSE_AND_PACKET_ID"
        android.util.Log.i("AtomRemoteAudioR2709", "MIC_MAC_RX_MATCH " +
            "key=${read.key} cmd=0x${read.command.toString(16)} " +
            "response=0x${messageType.toString(16)} normalized=0x${(messageType and 0x0FFF).toString(16)} " +
            "expectedBase=0x${expectedBase.toString(16)} packetId=$packetId " +
            "bodyBytes=${body.size} numericVerified=false")
        val record = _r2697MicLineEvidence.value.controls[read.key] ?: return
        if (record.requestToken != read.token) return
        // Old R26.97 classifier requires reply == request, but macOS shows
        // 0x06B6 Gain GET expects a distinct 0x06B1 response base. Preserve
        // local trial raw response as UNMAPPED, never report numeric readback.
        val classified = if (read.localTrial) record.copy(
            state = if (body.isNotEmpty()) "CANDIDATE_RX_UNMAPPED" else "EMPTY_RX",
            responseType = messageType,
            rawHex = body.take(192).toByteArray().toHex(),
            detail = if (read.key == "inputLevel")
                "R27.15 Input Level typed read; enum Mic/Line mapping unverified; SET disabled"
            else if (read.key == "delay")
                "A10 local Delay GET candidate; frames mapping unverified; SET disabled"
            else "R27.11 local Gain read; channel and gain scale unverified; SET disabled")
        else com.atomosremote.nativeapp.audio.AudioR2697MicLineEvidence.classify(
            record, messageType, body.toHex(), local)
        _r2697MicLineEvidence.value = _r2697MicLineEvidence.value.copy(
            controls = _r2697MicLineEvidence.value.controls + (read.key to classified))
        android.util.Log.i("AtomRemoteAudioR2707", "MIC_GET_RX_CANDIDATE " +
            "key=${read.key} req=0x${read.command.toString(16)} " +
            "response=0x${messageType.toString(16)} packetId=$packetId " +
            "expectedPacketId=${read.requestPacketId} local=$local owner=${currentOwner ?: "none"} " +
            "correlation=$status state=${classified.state} bytes=${body.size} " +
            "raw=${body.take(192).toByteArray().toHex()} verifiedNumeric=false")
        // A11: strict FrameDelayGet numeric evidence, read-only. Exact two-byte
        // protobuf f1/wire0 for verified hardware test range 0..20 frames.
        if (read.key == "delay" && read.command == 0x06B9 && messageType == 0x86B3 && local) {
            val a11Frames = if (body.size == 2 && body[0] == 0x08.toByte() &&
                (body[1].toInt() and 0xFF) in 0..20) (body[1].toInt() and 0xFF) else null
            android.util.Log.i("AtomRemoteAudioA11", "DELAY_FRAMES_RX " +
                "value=${a11Frames?.toString() ?: "UNMAPPED"} " +
                "unit=fr raw=${body.toHex()} typed=true " +
                "range0to20=${a11Frames != null} setTx=false")
            // A12.24: PASSIVE observation only; retains A11's GET correlation and parser.
            // Only a fresh value=0 from this BLE session may authorize 0 -> 1 SET pilot.
            if (a11Frames != null) {
                val rxAt = android.os.SystemClock.elapsedRealtime()
                a124DelayLastFrames = a11Frames
                a124DelayLastRxAtMs = rxAt
                a124DelayLastRxGatt = gatt
                // A12.29: the typed GET is authoritative regardless of whether
                // an earlier SET verification window has timed out. Keep the
                // user-visible recorder value independent from transaction ACK.
                android.util.Log.i("AtomRemoteAudioA129Delay", "DELAY_CONFIRMED_RECORDER_GET " +
                    "frames=$a11Frames target=${a125DelayTargetFrames ?: "none"} " +
                    "setVerificationPending=$a124DelayPendingGet " +
                    "sameSession=${a124DelayPendingGatt === gatt} " +
                    "afterSetQueue=${rxAt > a124DelaySetQueuedAtMs} " +
                    "source=0x86B3 verifiedRecorderValue=true")
                if (a124DelayPendingGet && rxAt > a124DelaySetQueuedAtMs &&
                    a124DelayPendingGatt === gatt) {
                    val result = if (a11Frames == a125DelayTargetFrames) "READBACK_TARGET_MATCH" else "READBACK_NOT_TARGET"
                    android.util.Log.w("AtomRemoteAudioA124", "DELAY_POST_SET_$result " +
                        "value=$a11Frames target=$a125DelayTargetFrames cmd=0x06BA freshGet=0x86B3 " +
                        "setQueueAccepted=true physicalWriteUnproven=true " +
                        "recorderAppliedByThisApp=UNVERIFIED")
                    if (a11Frames == a125DelayTargetFrames) {
                        // A12.27: A fresh matching recorder GET completes this manually
                        // requested transaction. Unlock the next manual SET only now.
                        a124DelayPendingGet = false
                        a12PilotAttempted.remove("delay")
                        android.util.Log.i("AtomRemoteAudioA127", "DELAY_SET_VERIFIED " +
                            "target=$a11Frames nextManualSetEnabled=true noAutoSet=true")
                    } else {
                        // Do not treat an interim/unchanged GET as final failure.
                        // Later GET retries or the existing timeout will settle it.
                        android.util.Log.i("AtomRemoteAudioA127", "DELAY_SET_AWAIT_TARGET " +
                            "observed=$a11Frames target=$a125DelayTargetFrames " +
                            "pending=true nextManualSetEnabled=false")
                    }
                }
            }
        }
        // R27.22: readback timestamp only for typed, decodable GainValuesGet.
        // No changes to the GET serializer, PacketLink correlation or parser.
        if (read.command == 0x06B6 && messageType == 0x86B1 && local &&
            com.atomosremote.nativeapp.audio.AudioR2714GainReadback.decode(body) != null) {
            r2722LastGainRxMs = android.os.SystemClock.elapsedRealtime()
            // A12.29: Observe actual bulk GET for BOTH gain channels. Logging
            // only: neither channel selector polarity nor the full dB scale
            // has been hardware-calibrated. Do not unlock arbitrary GainSet.
            val a129GainPair = com.atomosremote.nativeapp.audio.AudioR2714GainReadback.decode(body)
            if (a129GainPair != null) {
                android.util.Log.i("AtomRemoteAudioA129Gain", "GAIN_CHANNEL_RX " +
                    "leftRaw=${a129GainPair.leftRaw} rightRaw=${a129GainPair.rightRaw} " +
                    "leftDisplay=${a129GainPair.leftLabel()} " +
                    "rightDisplay=${a129GainPair.rightLabel()} " +
                    "fullyCalibrated=${a129GainPair.fullyCalibrated()} " +
                    "request=0x06B6 response=0x86B1 tx=false")
            }
            // A12.37 recorder GET owns the verdict, never the queued SET.
            if (a137GainPending && a129GainPair != null && r2722LastGainRxMs > a137GainStartedMs) {
                val actual = if (a137GainSelector) a129GainPair.rightRaw else a129GainPair.leftRaw
                val other = if (a137GainSelector) a129GainPair.leftRaw else a129GainPair.rightRaw
                val originalOther = if (a137GainSelector) a137GainBeforeLeft else a137GainBeforeRight
                val status = when {
                    other != originalOther -> "OTHER_CHANNEL_CHANGED_STOP"
                    actual == a137GainTargetRaw -> "VERIFIED_TARGET_MATCH"
                    else -> "UNCHANGED_OR_DIFFERENT"
                }
                android.util.Log.i("AtomRemoteAudioA137Gain", "GAIN_RANGE_GET_VERDICT " +
                    "selector=${a137GainSelector} targetRaw=$a137GainTargetRaw " +
                    "afterLeft=${a129GainPair.leftRaw} afterRight=${a129GainPair.rightRaw} " +
                    "result=$status freshGet=0x86B1")
                if (status != "UNCHANGED_OR_DIFFERENT") a137GainPending = false
            }
            // Evidence-only: assess changed side independent from guessed Bool polarity.
            val a135Selector = a135GainPilotSelector
            if (a135Selector != null && a129GainPair != null) {
                val age = android.os.SystemClock.elapsedRealtime() - a135GainPilotAt
                if (age in 0L..30_000L) {
                    val dl = a129GainPair.leftRaw - a135GainPilotBeforeLeft
                    val dr = a129GainPair.rightRaw - a135GainPilotBeforeRight
                    val verdict = when {
                        dl == 0L && dr == 0L -> "UNCHANGED_GET_KEEP_WAITING"
                        dl != 0L && dr == 0L -> "LEFT_ONLY_CHANGED"
                        dl == 0L && dr != 0L -> "RIGHT_ONLY_CHANGED"
                        else -> "BOTH_CHANGED_UNSAFE"
                    }
                    android.util.Log.i("AtomRemoteAudioA135Gain",
                        "GAIN_SELECTOR_EVIDENCE selector=$a135Selector restore=$a135GainPilotRestore " +
                        "beforeLeft=$a135GainPilotBeforeLeft beforeRight=$a135GainPilotBeforeRight " +
                        "afterLeft=${a129GainPair.leftRaw} afterRight=${a129GainPair.rightRaw} " +
                        "deltaLeft=$dl deltaRight=$dr result=$verdict ageMs=$age " +
                        "freshGet=0x86B1 tx=false")
                    if (verdict != "UNCHANGED_GET_KEEP_WAITING") a135GainPilotSelector = null
                } else {
                    android.util.Log.w("AtomRemoteAudioA135Gain",
                        "GAIN_SELECTOR_EVIDENCE_TIMEOUT selector=$a135Selector ageMs=$age noProof=true")
                    a135GainPilotSelector = null
                }
            }
            // Clear an outstanding restore ONLY when the hardware GET confirms 0/0.
            // This is deliberately not conditioned on ACK or queue status.
            val verifiedGains = com.atomosremote.nativeapp.audio.AudioR2714GainReadback.decode(body)
            if (r2722PilotRestoreSelector != null && verifiedGains?.leftRaw == 0L &&
                verifiedGains.rightRaw == 0L) {
                val resolvedSelector = r2722PilotRestoreSelector
                r2722PilotRestoreSelector = null
                r2722PilotSinceMs = 0L
                android.util.Log.i("AtomRemoteAudioA053", "RESTORE_READBACK_CONFIRMED " +
                    "selector=$resolvedSelector left=0 right=0 source=0x86B1")
            } else if (r2722PilotRestoreSelector != null) {
                android.util.Log.w("AtomRemoteAudioA053", "RESTORE_STILL_PENDING " +
                    "selector=$r2722PilotRestoreSelector left=${verifiedGains?.leftRaw} " +
                    "right=${verifiedGains?.rightRaw} noSuccessClaim=true")
            }
        }
        if (read.command == 0x06B3 && messageType == 0x86AE && local) {
            val rawMode = com.atomosremote.nativeapp.audio.AudioR2716InputLevelReadback.decodeHex(body.toHex())
            if (rawMode != null && rawMode.rawEnum in 1L..2L) {
                a05LastInputRxMs = android.os.SystemClock.elapsedRealtime()
                val target = a05InputPendingTarget
                if (target != null) {
                    val age = a05LastInputRxMs - a05InputSetAtMs
                    val verdict = when {
                        age !in 0L..30_000L -> "STALE_OR_UNCORRELATED"
                        rawMode.rawEnum == target.toLong() && a05InputPreviousRaw != rawMode.rawEnum -> "TARGET_READBACK_MATCHED"
                        rawMode.rawEnum == a05InputPreviousRaw -> "NO_OBSERVED_CHANGE"
                        else -> "UNEXPECTED_VALUE"
                    }
                    android.util.Log.i("AtomRemoteAudioA05", "INPUT_SET_GET_VERDICT result=$verdict " +
                        "previous=${a05InputPreviousRaw} target=$target current=${rawMode.rawEnum} " +
                        "elapsedMs=$age recorderEffectRequiresPhysicalCheck=true")
                    // A08: after a hardware-confirmed Mic/Line mode flip, request a fresh
                    // left+right bulk GainValuesGet (0x06B6). GET ONLY, never Gain SET.
                    // The existing R2706 mic-read scheduler owns PacketLink arbitration.
                    if (verdict == "TARGET_READBACK_MATCHED") {
                        mainHandler.postDelayed({
                            if (gatt != null && _tcsPairingState.value.acceptWritten &&
                                _tcsRemoteState.value.available) {
                                val accepted = r2672TryMicLineCandidateGet("gainLeft")
                                android.util.Log.i("AtomRemoteAudioA08",
                                    "INPUT_MODE_GAIN_REFRESH requested=$accepted key=gainLeft " +
                                    "bulkGet=0x06B6 readOnly=true inputLevelFrozen=true")
                            } else {
                                android.util.Log.i("AtomRemoteAudioA08",
                                    "INPUT_MODE_GAIN_REFRESH_SKIPPED reason=session-not-ready")
                            }
                        }, 650L)
                    }
                    a05InputPendingTarget = null
                    a05InputPreviousRaw = null
                    a05InputSetAtMs = 0L
                }
            }
        }
        if (body.isNotEmpty()) {
            android.util.Log.i("AtomRemoteAudioR2707", "MIC_GET_PROTO_SHAPE " +
                "key=${read.key} " +
                com.atomosremote.nativeapp.audio.AudioR2701WireInspector.describe(body))
            if (read.command == 0x06B6) {
                // Same bulk gain-values poll opcode for Left and Right. Preserve
                // complete payload as candidate; channel map not yet proven.
                android.util.Log.i("AtomRemoteAudioR2707", "MIC_GAIN_BULK_CANDIDATE " +
                    "key=${read.key} leftRightMapping=UNVERIFIED " +
                    "payload=${body.take(192).toByteArray().toHex()} " +
                    "updateBothNumeric=false")
            }
        }
        r2706MicActive = null
        r2706MicPumpSoon(100L)
    }

    // R27.01: cooperative, bounded Audio priority at *between-probe* Input gap.
    // Input packets and PacketLink ownership are never preempted or cancelled.
    private var audioR2701InputYieldStartMs = 0L
    private var audioR2701InputYieldLastLogMs = 0L
    private val audioR2701InputYieldLimitMs = 5_000L

    private fun audioR2701YieldAllowed(): Boolean {
        if (!inputExtendedRunning || !_audioR26Live.value.running ||
            outputR21MtuBytes < 61 || inputCollectorRunning || remoteBootstrapActive ||
            inputExtendedActive != null || pendingRemotePacket != null ||
            packetLink18.bufferedPacketSize() != 0 ||
            audioR26Position >= audioR26Schedule.size) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (audioR2701InputYieldStartMs == 0L) {
            audioR2701InputYieldStartMs = now
            audioR2701InputYieldLastLogMs = 0L
        }
        val age = now - audioR2701InputYieldStartMs
        if (age >= audioR2701InputYieldLimitMs) {
            if (now - audioR2701InputYieldLastLogMs > 1_000L) {
                android.util.Log.i("AtomRemoteAudioR2701", "FAIR_YIELD_EXPIRED ageMs=$age inputExtendedContinues=true")
                audioR2701InputYieldLastLogMs = now
            }
            return false
        }
        if (now - audioR2701InputYieldLastLogMs > 1_000L) {
            android.util.Log.i("AtomRemoteAudioR2701", "FAIR_YIELD_GRANTED ageMs=$age mtU=$outputR21MtuBytes currentInput=${inputExtendedIndex}/${InputExtendedReadPlan.probes.size}")
            audioR2701InputYieldLastLogMs = now
        }
        return true
    }

    private var audioR26RunId = 0
    private var audioR26Position = 0
    private var audioR26BusyTicks = 0
    // R26.23: Audio-local, bounded recovery for an orphaned ATT MTU queue entry.
    private var audioR2623MtuOrphanRearms = 0
    private var audioR26Active: com.atomosremote.nativeapp.audio.AudioR26GetDefinition? = null
    private var audioR26ActiveStartedMs = 0L
    // R26.17 AUDIO-only passive lifecycle evidence. Buffer acceptance is NOT a BLE write.
    private var audioR26PhysicalWriteMs = 0L
    private var audioR26TransportCompleteMs = 0L
    private val audioR26BufferedMaxWaitMs = 20_000L
    private val audioR26PhysicalMaxWaitMs = 15_000L
    private val audioR26TypedRxMaxWaitMs = 10_000L
    // GET scheduler: only the known AG2 AudioMeters response is source+hardware
    // verified. Do NOT treat macOS getter names as argument-free requests.
    // R26.15: the ONLY getter proven by a typed AG2 hardware RX is 0x0647.
    // Put it first, after requesting the mandatory MTU at collector start.
    // R26.20: collect only hardware-proven readback. The full macOS method
    // catalog remains available for OFFLINE analysis, but must not be treated
    // as 22 supported device GETs or generate noisy no-op collector entries.
    private val audioR26Schedule = listOf(
        com.atomosremote.nativeapp.audio.AudioR26GetDefinition("AudioMetersPageGet", 0x0647, false, "AG2_TYPED_RX_VERIFIED")
    )
    private var audioR26QueueRefusals = 0
    // R26.14.1: the Input/Output owner may be busy for minutes; do not claim
    // recorder incompatibility or consume an Audio key solely because ATT is busy.
    private var audioR26AuditStartedMs = 0L
    private var audioR26BusySinceMs = 0L
    private var audioR26LastWaitLogMs = 0L
    private val audioR26AuditWindowMs = 240_000L
    // Session-only: a genuine recorder reject remains visible, and is not hammered on each refresh.
    private val audioR26SessionRejected = mutableSetOf<Int>()
    // RX from the verified aggregate AudioMeters page, scoped to THIS collector run.
    private var audioR26VerifiedPageHex = ""

    fun cancelAudioR26RecoveredCollector() {
        audioR2701InputYieldStartMs = 0L
        audioR26RunId += 1
        audioR26Active = null
        _audioR26Live.value = _audioR26Live.value.copy(running = false, activeMethod = null,
            status = "Collector stopped (Audio screen closed)")
    }

    fun startAudioR26RecoveredCollector(): Boolean {
        if (_audioR26Live.value.running) {
            android.util.Log.i("AtomRemoteAudioR2614", "ALREADY_RUNNING generation=$audioR26RunId pos=$audioR26Position total=${audioR26Schedule.size}; no reset")
            return true
        }
        if (!_tcsPairingState.value.acceptWritten || !_tcsRemoteState.value.available) {
            android.util.Log.i("AtomRemoteAudioR2613", "COLLECTOR_SKIP reason=not-paired")
            return false
        }
        audioR26RunId += 1
        audioR2701InputYieldStartMs = 0L
        audioR2701InputYieldLastLogMs = 0L
        val generation = audioR26RunId
        audioR26Position = 0
        audioR26BusyTicks = 0
        audioR2623MtuOrphanRearms = 0
        audioR26QueueRefusals = 0
        audioR26AuditStartedMs = android.os.SystemClock.elapsedRealtime()
        audioR26BusySinceMs = 0L
        audioR26LastWaitLogMs = 0L
        audioR26Active = null
        audioR26PhysicalWriteMs = 0L
        audioR26TransportCompleteMs = 0L
        audioR26VerifiedPageHex = ""
        // Request the standard Android ATT MTU *before* the Audio collector.
        // This only queues PendingOp.OutputR21Mtu via the original exclusive ATT
        // serializer and NEVER transmits an ATOMOS RemoteApi SET.
        val mtuReadyOrQueued = if (outputR21MtuBytes < 61) requestR21OutputPageMtu() else true
        android.util.Log.i("AtomRemoteAudioR2620", "CAPABILITY_AUDIT device=AG2 " +
            "verifiedGet=0x0647 expectedRx=0x8647 requestedMethods=1 " +
            "macosRegistry=${com.atomosremote.nativeapp.audio.AudioR26RecoveredMap.allKeys.size} " +
            "parameterizedGetTx=disabled audioSetTx=disabled")
        android.util.Log.i("AtomRemoteAudioR2615Fix",
            "START_MTU_PREPARE current=$outputR21MtuBytes queued=$mtuReadyOrQueued " +
            "state=$outputR21MtuResult attInFlight=$operationInFlight attQueue=${pendingOps.size} " +
            "remoteOwner=${pendingRemotePacket?.purpose ?: "none"}")
        _audioR26Live.value = com.atomosremote.nativeapp.audio.AudioR26LiveSnapshot(
            generation = generation, running = true,
            status = "Preparing live Audio readback (MTU=${outputR21MtuBytes})")
        mainHandler.post { audioR26Step(generation) }
        return true
    }

    private fun audioR26Step(generation: Int) {
        if (generation != audioR26RunId) return
        if (!_tcsPairingState.value.acceptWritten || !_tcsRemoteState.value.available || gatt == null) {
            audioR26RunId += 1
            _audioR26Live.value = com.atomosremote.nativeapp.audio.AudioR26LiveSnapshot(
                generation = generation, status = "Disconnected; current values invalid")
            return
        }
        if (audioR26Position >= audioR26Schedule.size) {
            val items = _audioR26Live.value.values.values
            val rx = items.count { it.status == "TYPED_RX" }
            val rejected = items.count { it.status.startsWith("RECORDER_REJECT") }
            val skipped = items.count { it.status.startsWith("SKIPPED_") }
            audioR2701InputYieldStartMs = 0L
            _audioR26Live.value = _audioR26Live.value.copy(running = false,
                activeMethod = null, status = "Audit complete: $rx RX, $rejected rejected, $skipped skipped, ${items.size}/${audioR26Schedule.size} checked")
            android.util.Log.i("AtomRemoteAudioR2614", "COLLECTOR_COMPLETE checked=${items.size} total=${audioR26Schedule.size} typed=$rx rejected=$rejected skipped=$skipped noSet=true")
            return
        }
        val upcoming = audioR26Schedule[audioR26Position]
        // Only four already-recognized GETs have single-varint CANDIDATES
        // derived from the same session's strictly typed 0x8647 page RX.
        // A candidate is NOT a confirmed hardware Audio mapping.
        // R26.19: no speculative per-setting payload is constructed or transmitted.
        val audioProtoCandidate: ByteArray? = null
        // Screen every official macOS method. Never send SETs, support predicates,
        // inferred/unverified IDs, or parameterized GETs with guessed empty bodies.
        val skipReason = when {
            upcoming.command in audioR26SessionRejected -> "PREVIOUS_RECORDER_REJECT_IN_SESSION"
            // R26.19 macOS metadata + AG2 capture: these four hand-seeded
            // first-field varints delivered on GATT but yielded no typed RX
            // across R26.16-R26.18. DO NOT auto-repeat speculative requests.
            upcoming.method in setOf("MonitorGet", "PhonesLevelGet", "OrientationGet", "AnalogEmbedGet") ->
                "UNVERIFIED_REQUEST_SCHEMA_DISABLED_USE_8647_PAGE"
            upcoming.evidence == "NOT_A_GET" -> "NOT_A_GET_OPERATION"
            upcoming.command < 0 -> "NO_PROVEN_OPCODE"
            upcoming.requiresArguments -> "ARGUMENT_SCHEMA_MISSING"
            // R26.16 only evaluates four read-only scalar GET hypotheses
            // after an actual paired AG2 page RX. The other three empty probes
            // from R26.14.1 remain BLOCKED (7/7 had 0xFFFF responses).
            upcoming.command != 0x0647 && audioProtoCandidate == null &&
                com.atomosremote.nativeapp.audio.AudioR26RecoveredMap.isWireEligible(upcoming) ->
                "MACOS_ARGUMENT_PAYLOAD_UNVERIFIED"
            upcoming.evidence != "AG2_TYPED_RX_VERIFIED" &&
                !com.atomosremote.nativeapp.audio.AudioR26RecoveredMap.isWireEligible(upcoming) -> "OPCODE_NOT_DIRECTLY_VERIFIED"
            else -> null
        }
        if (skipReason != null) {
            val message = "SKIPPED_$skipReason"
            _audioR26Live.value = _audioR26Live.value.copy(
                values = _audioR26Live.value.values + (upcoming.method to
                    com.atomosremote.nativeapp.audio.AudioR26LiveValue(upcoming.method, upcoming.command, message)),
                status = "Checked ${audioR26Position + 1}/${audioR26Schedule.size}: ${upcoming.method} $message")
            android.util.Log.i("AtomRemoteAudioR2614", "KEY_SKIPPED method=${upcoming.method} opcode=${upcoming.command} reason=$skipReason")
            audioR26Advance(generation)
            return
        }
        if (upcoming.command == 0x0647 && outputR21MtuBytes < 61) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - audioR26AuditStartedMs
            if (elapsed >= audioR26AuditWindowMs) {
                _audioR26Live.value = _audioR26Live.value.copy(
                    running = false, activeMethod = null,
                    values = _audioR26Live.value.values + (upcoming.method to
                        com.atomosremote.nativeapp.audio.AudioR26LiveValue(
                            upcoming.method, upcoming.command, "DEFERRED_MTU_NOT_NEGOTIATED")),
                    status = "Audio readback paused: ATT MTU not negotiated; ${outputR21MtuResult}. " +
                        "Audio values are unavailable, not zero. Reopen Audio to retry.")
                android.util.Log.i("AtomRemoteAudioR2615", "COLLECTOR_PAUSED_MTU method=${upcoming.method} mtu=$outputR21MtuBytes")
                return
            }
            // R26.23: The 2026-10-14 capture showed MTU state=queued for 44s
            // despite attBusy=false, activeOp=none, and attQueue=0. Repeating
            // requestR21OutputPageMtu() cannot work while its existing
            // connection-local outputR21MtuRequested flag remains true.
            // Re-arm ONLY after 8 seconds, ONLY when there is no ATT owner and
            // no actual pending MTU op, at most twice per Audio collector run.
            // No recorder RemoteApi bytes are emitted by this recovery.
            val orphanPending = pendingOps.any { it is PendingOp.OutputR21Mtu }
            if (com.atomosremote.nativeapp.audio.AudioR2623MtuOrphanPolicy.shouldRearm(
                    negotiatedMtu = outputR21MtuBytes,
                    wasRequested = outputR21MtuRequested,
                    requestState = outputR21MtuResult,
                    attBusy = operationInFlight,
                    hasActiveOperation = activeOp != null,
                    mtuStillQueued = orphanPending,
                    elapsedMs = elapsed,
                    rearmCount = audioR2623MtuOrphanRearms
                )) {
                audioR2623MtuOrphanRearms += 1
                outputR21MtuRequested = false
                outputR21MtuResult = "audio-orphan-rearm"
                val requeued = requestR21OutputPageMtu()
                android.util.Log.i("AtomRemoteAudioR2623",
                    "MTU_ORPHAN_REARM attempt=$audioR2623MtuOrphanRearms " +
                        "queued=$requeued mtu=$outputR21MtuBytes attInFlight=$operationInFlight " +
                        "attQueue=${pendingOps.size} remoteOwner=${pendingRemotePacket?.purpose ?: "none"}")
            }
            audioR26BusyTicks += 1
            if (audioR26BusyTicks == 1 || audioR26BusyTicks % 8 == 0) requestR21OutputPageMtu()
            if (audioR26BusyTicks == 1 || audioR26BusyTicks % 5 == 0)
                android.util.Log.i("AtomRemoteAudioR2615Fix", "KEY_WAIT_MTU method=${upcoming.method} " +
                    "mtu=$outputR21MtuBytes elapsedMs=$elapsed state=$outputR21MtuResult " +
                    "requested=$outputR21MtuRequested activeOp=${activeOp?.javaClass?.simpleName ?: "none"} " +
                    "attBusy=$operationInFlight attQueue=${pendingOps.size} " +
                    "pending=${pendingRemotePacket?.purpose ?: "none"}")
            _audioR26Live.value = _audioR26Live.value.copy(
                status = "Waiting for Audio meter BLE MTU; ${audioR26Position}/${audioR26Schedule.size} checked",
                activeMethod = null)
            mainHandler.postDelayed({ audioR26Step(generation) }, 1000L)
            return
        }
        // PacketLink.sendPacket buffers a message; its existing 10ms process
        // tick performs the actual serialized ATT transfer. An E0 heartbeat in
        // WRITING/READING or an unrelated in-flight ATT operation is not by
        // itself a reason to permanently skip an Audio GET. We must still
        // respect the real PacketLink buffer/pending owner and Input/bootstrap.
        // R27.01: the Ext-Input collector keeps its running flag set even at
        // a packet-free gap. Permit Audio only during our explicit bounded
        // cooperative yield, not while any Input probe owns the transport.
        val inCooperativeGap = inputExtendedRunning && audioR2701YieldAllowed()
        val realOwnerBusy = pendingRemotePacket != null ||
            packetLink18.bufferedPacketSize() != 0 || remoteBootstrapActive ||
            inputCollectorRunning || (inputExtendedRunning && !inCooperativeGap)
        if (realOwnerBusy) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (audioR26BusySinceMs == 0L) audioR26BusySinceMs = now
            val elapsed = now - audioR26AuditStartedMs
            if (elapsed >= audioR26AuditWindowMs) {
                _audioR26Live.value = _audioR26Live.value.copy(
                    running = false, activeMethod = null,
                    values = _audioR26Live.value.values + (upcoming.method to
                        com.atomosremote.nativeapp.audio.AudioR26LiveValue(
                            upcoming.method, upcoming.command, "DEFERRED_REAL_OWNER_BUSY")),
                    status = "Audit paused: Input/other owner busy for 4 min. " +
                        "Only actual typed RX proves support. Tap Audio Refresh to retry.")
                android.util.Log.i("AtomRemoteAudioR2615", "COLLECTOR_PAUSED_BUSY key=${upcoming.method} " +
                    "checked=${audioR26Position}/${audioR26Schedule.size} " +
                    "pending=${pendingRemotePacket?.purpose ?: "none"} input=$inputCollectorRunning extended=$inputExtendedRunning bootstrap=$remoteBootstrapActive")
                return
            }
            if (now - audioR26LastWaitLogMs >= 5000L) {
                audioR26LastWaitLogMs = now
                android.util.Log.i("AtomRemoteAudioR2615", "KEY_WAIT_OWNER method=${upcoming.method} " +
                    "elapsedMs=$elapsed pending=${pendingRemotePacket?.purpose ?: "none"} " +
                    "input=$inputCollectorRunning extended=$inputExtendedRunning bootstrap=$remoteBootstrapActive " +
                    "attBusy=$operationInFlight attQueue=${pendingOps.size} plink=${packetLink18.state()}")
            }
            _audioR26Live.value = _audioR26Live.value.copy(
                activeMethod = null,
                status = "Waiting for Input/Output transport owner (${elapsed / 1000}s); " +
                    "${audioR26Position}/${audioR26Schedule.size} checked; none falsely skipped")
            mainHandler.postDelayed({ audioR26Step(generation) }, 200L)
            return
        }
        val spec = audioR26Schedule[audioR26Position]
        audioR26BusySinceMs = 0L
        audioR26BusyTicks = 0
        audioR26Active = spec
        audioR26ActiveStartedMs = android.os.SystemClock.elapsedRealtime()
        audioR26PhysicalWriteMs = 0L
        audioR26TransportCompleteMs = 0L
        val purpose = "remote-audio-r26-recovered-get:${spec.method}"
        val payload = if (spec.command == 0x0647) byteArrayOf() else audioProtoCandidate
            ?: byteArrayOf() // This cannot dispatch except verified 0x0647 (guard above).
        if (spec.command != 0x0647) {
            android.util.Log.i("AtomRemoteAudioR2616", "GET_PROTO_CANDIDATE method=${spec.method} " +
                "source=page_8647 field1_varint payload=${payload.toHex()} " +
                "CAUTION=FIELD_NUMBER_AND_FIELD_MEANING_AWAIT_AG2_VALIDATION")
        }
        val packet = PendingRemotePacket(
            body = buildOfficialAtomMessageBodyWithProtoPayload(0, spec.command, payload),
            purpose = purpose, label = "Audio ${spec.method} read-only",
            commandId = spec.command, packetId = 0, expectedResponseId = spec.command)
        val queued = queueRemotePacket18_8(packet, local = true)
        if (!queued) {
            audioR26Active = null
            audioR26QueueRefusals += 1
            // Rejection by an already-buffered PacketLink turn is a temporary
            // transport state, NOT evidence that the Audio opcode failed.
            if (android.os.SystemClock.elapsedRealtime() - audioR26AuditStartedMs >= audioR26AuditWindowMs) {
                _audioR26Live.value = _audioR26Live.value.copy(
                    running = false, activeMethod = null,
                    values = _audioR26Live.value.values + (spec.method to
                        com.atomosremote.nativeapp.audio.AudioR26LiveValue(
                            spec.method, spec.command, "DEFERRED_QUEUE_NOT_ACCEPTED")),
                    status = "Audit paused: PacketLink buffer occupied; tap Refresh to retry")
                android.util.Log.i("AtomRemoteAudioR2615", "COLLECTOR_PAUSED_QUEUE method=${spec.method} retries=$audioR26QueueRefusals")
            } else {
                if (audioR26QueueRefusals == 1 || audioR26QueueRefusals % 10 == 0)
                    android.util.Log.i("AtomRemoteAudioR2615", "KEY_WAIT_BUFFER method=${spec.method} retries=$audioR26QueueRefusals")
                mainHandler.postDelayed({ audioR26Step(generation) }, 650L)
            }
            return
        }
        _audioR26Live.value = _audioR26Live.value.copy(
            activeMethod = spec.method, status = "Reading ${spec.method}",
            values = _audioR26Live.value.values +
                (spec.method to com.atomosremote.nativeapp.audio.AudioR26LiveValue(
                    spec.method, spec.command, "WAITING_TYPED_RX")))
        // R26.17: a successful queueRemotePacket18_8 only proves BUFFER ACCEPTED.
        // The PacketLink process tick and actual GATT write callback are separate.
        android.util.Log.i("AtomRemoteAudioR2617", "GET_BUFFERED method=${spec.method} cmd=0x${spec.command.toString(16)} local=true packetId=0 payloadLen=${payload.size} physicalWrite=false")
        android.util.Log.i("AtomRemoteAudioR2614", "KEY_TX method=${spec.method} command=0x${spec.command.toString(16)} checked=${audioR26Position + 1}/${audioR26Schedule.size} queuedOnly=true")
        mainHandler.postDelayed({ audioR26ObserveTimeout(generation, spec.method) }, 400L)
    }

    /** Read-only Audio scheduler watchdog; never drops another feature's pending owner. */
    private fun audioR26ObserveTimeout(generation: Int, method: String) {
        if (generation != audioR26RunId || audioR26Active?.method != method) return
        val active = audioR26Active ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val queuedMs = now - audioR26ActiveStartedMs
        val writeMs = audioR26PhysicalWriteMs
        val deliveredMs = audioR26TransportCompleteMs
        val reason = when {
            writeMs == 0L && queuedMs >= audioR26BufferedMaxWaitMs -> "DEFERRED_NOT_GATT_WRITTEN"
            writeMs != 0L && deliveredMs == 0L &&
                now - writeMs >= audioR26PhysicalMaxWaitMs -> "DEFERRED_TRANSPORT_INCOMPLETE"
            deliveredMs != 0L && now - deliveredMs >= audioR26TypedRxMaxWaitMs -> "NO_TYPED_RX_AFTER_DELIVERY"
            else -> null
        }
        if (reason != null) {
            _audioR26Live.value = _audioR26Live.value.copy(
                values = _audioR26Live.value.values +
                    (method to com.atomosremote.nativeapp.audio.AudioR26LiveValue(
                        method, active.command, reason)))
            android.util.Log.i("AtomRemoteAudioR2617", "GET_LIFECYCLE_TIMEOUT method=$method reason=$reason " +
                "bufferedMs=$queuedMs physicalWritten=${writeMs != 0L} transportDelivered=${deliveredMs != 0L} " +
                "pending=${pendingRemotePacket?.purpose ?: "none"} plink=${packetLink18.state()}")
            // Do not advance to a NEW command while the current queued/physical
            // packet still owns PacketLink. This avoids misattributed later RX.
            if (reason != "NO_TYPED_RX_AFTER_DELIVERY" &&
                pendingRemotePacket?.purpose == "remote-audio-r26-recovered-get:$method") {
                _audioR26Live.value = _audioR26Live.value.copy(
                    running = false, activeMethod = null,
                    status = "Audio GET deferred: $reason; leave transport owner untouched")
                android.util.Log.i("AtomRemoteAudioR2617", "COLLECTOR_PAUSED method=$method reason=$reason")
                audioR26RunId += 1
                audioR26Active = null
                return
            }
            audioR26Advance(generation)
            return
        }
        mainHandler.postDelayed({ audioR26ObserveTimeout(generation, method) }, 400L)
    }

    /** Passive callbacks from existing PacketLink without changing its queue/state machine. */
    private fun audioR26OnGATTWritten() {
        val active = audioR26Active ?: return
        if (pendingRemotePacket?.purpose != "remote-audio-r26-recovered-get:${active.method}" ||
            audioR26PhysicalWriteMs != 0L) return
        audioR26PhysicalWriteMs = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i("AtomRemoteAudioR2617", "GET_GATT_TX_OK method=${active.method} " +
            "cmd=0x${active.command.toString(16)} bufferedMs=${audioR26PhysicalWriteMs - audioR26ActiveStartedMs}")
    }

    private fun audioR26OnTransportComplete(sent: PendingRemotePacket?) {
        val active = audioR26Active ?: return
        if (sent?.purpose != "remote-audio-r26-recovered-get:${active.method}" ||
            audioR26TransportCompleteMs != 0L) return
        audioR26TransportCompleteMs = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i("AtomRemoteAudioR2617", "GET_TRANSPORT_COMPLETE method=${active.method} " +
            "written=${audioR26PhysicalWriteMs != 0L} packetOwnerCleared=true " +
            "ageMs=${audioR26TransportCompleteMs - audioR26ActiveStartedMs}")
    }

    private fun audioR26Advance(generation: Int) {
        if (generation != audioR26RunId) return
        audioR26Active = null
        audioR26PhysicalWriteMs = 0L
        audioR26TransportCompleteMs = 0L
        audioR26Position += 1
        audioR26BusyTicks = 0
        audioR26QueueRefusals = 0
        _audioR26Live.value = _audioR26Live.value.copy(
            activeMethod = null,
            status = "Audio page GET: ${_audioR26Live.value.values.size}/${audioR26Schedule.size} checked")
        mainHandler.postDelayed({ audioR26Step(generation) }, 320L)
    }

    /** RX observation only AFTER strict AtomMessage length/PacketLink demux. */
    private fun audioR26ObserveTypedRx(
        pending: PendingRemotePacket?, messageType: Int, body: ByteArray, local: Boolean
    ) {
        val active = audioR26Active ?: return
        // R26.18: pendingRemotePacket is a *transport* owner, not a reply token.
        // It is cleared at the successful PacketLink read turn, potentially
        // before the recorder's standalone typed RemoteApi reply is received.
        // In a narrow post-delivery window, accept ONLY the uniquely typed
        // 0x8647 response whose full AG2 Audio page parses. Other responses
        // remain uncorrelated to avoid stealing Input/Monitor messages.
        // OnePass RX correction: recorder can send the standalone 0x8647
        // reply AFTER the PacketLink transport owner is cleared. Only a
        // strictly parsed Audio page within this active GET's delivery window
        // may be attributed to this collector; never infer any SET success.
        val lateTypedAudioRx = com.atomosremote.nativeapp.audio.AudioR26LateTypedGate.accept(
            activeCommand = active.command,
            responseType = messageType,
            bodyHex = body.toHex(),
            local = local,
            pendingPurpose = pending?.purpose,
            physicalWriteMs = audioR26PhysicalWriteMs,
            transportCompleteMs = audioR26TransportCompleteMs,
            nowMs = android.os.SystemClock.elapsedRealtime(),
            windowMs = audioR26TypedRxMaxWaitMs
        )
        if ((pending?.purpose != "remote-audio-r26-recovered-get:${active.method}" && !lateTypedAudioRx) || !local) {
            val now = android.os.SystemClock.elapsedRealtime()
            val afterDelivery = audioR26PhysicalWriteMs != 0L &&
                audioR26TransportCompleteMs != 0L &&
                now >= audioR26TransportCompleteMs &&
                now - audioR26TransportCompleteMs <= audioR26TypedRxMaxWaitMs
            if (afterDelivery && local && pending == null) {
                val normalizedType = messageType and 0x7FFF
                val opcodeProximity = normalizedType in (active.command - 4)..(active.command + 1)
                android.util.Log.i("AtomRemoteAudioR2618", "POST_DELIVERY_RX_WINDOW method=${active.method} " +
                    "type=0x${messageType.toString(16)} normalized=0x${normalizedType.toString(16)} " +
                    "nearCommand=$opcodeProximity bytes=${body.size} " +
                    "ageAfterDeliveryMs=${now - audioR26TransportCompleteMs} " +
                    "pending=none notAcceptedAsTyped=true payload=${body.toHex().take(120)}")
            } else if (local && (messageType and 0x8000) != 0 &&
                (messageType and 0x7FFF) in (active.command - 4)..(active.command + 1)) {
                android.util.Log.i("AtomRemoteAudioR2618", "RX_UNCORRELATED_AUDIO_WINDOW method=${active.method} " +
                    "type=0x${messageType.toString(16)} pending=${pending?.purpose ?: "none"} " +
                    "delivered=$afterDelivery notAcceptedAsTyped=true")
            }
            return
        }
        if (lateTypedAudioRx) {
            android.util.Log.i("AtomRemoteAudioOnePassRxFix", "LATE_TYPED_RX_ACCEPT " +
                "method=${active.method} type=0x${messageType.toString(16)} bytes=${body.size} " +
                "ageAfterDeliveryMs=${android.os.SystemClock.elapsedRealtime() - audioR26TransportCompleteMs} " +
                "pending=none verifiedPage=true noSet=true")
        }
        val gen = audioR26RunId
        val normalized = messageType and 0x7FFF
        // The macOS binary establishes request IDs, but response-class IDs
        // can differ (Input GET 0x70 -> response 0x6c). Keep raw RX explicit.
        if (messageType == 0xFFFF || messageType == 0xFFFE) {
            audioR26SessionRejected += active.command
            _audioR26Live.value = _audioR26Live.value.copy(values = _audioR26Live.value.values +
                (active.method to com.atomosremote.nativeapp.audio.AudioR26LiveValue(
                    active.method, active.command, "RECORDER_REJECT_0x${messageType.toString(16)}", messageType)))
            android.util.Log.i("AtomRemoteAudioR2613", "GET_REJECT method=${active.method} response=0x${messageType.toString(16)}")
            audioR26Advance(gen)
        } else if ((messageType and 0x8000) != 0 &&
            (normalized in (active.command - 4)..(active.command + 1) ||
                (active.command == 0x0647 && normalized == 0x0647)) &&
            // R26.18: match the delivery-aware timeout, not the obsolete 4.2s
            // queue-time clock. Never accept after the collector watchdog ends.
            (audioR26PhysicalWriteMs == 0L ||
                android.os.SystemClock.elapsedRealtime() - audioR26PhysicalWriteMs <=
                audioR26PhysicalMaxWaitMs + audioR26TypedRxMaxWaitMs)) {
            val raw = body.toHex()
            if (active.command == 0x0647 && messageType == 0x8647 &&
                com.atomosremote.nativeapp.audio.AudioR26ProtoCandidates.parsePage(raw) != null) {
                audioR26VerifiedPageHex = raw
                android.util.Log.i("AtomRemoteAudioR2616", "AG2_PAGE_SEED_VERIFIED bytes=${body.size} " +
                    "candidateKeys=MonitorGet,PhonesLevelGet,OrientationGet,AnalogEmbedGet")
            }
            val rawField1 = protoVarintField(body, 1)
            val status = if (body.isNotEmpty()) "TYPED_RX" else "TYPED_EMPTY_RX"
            _audioR26Live.value = _audioR26Live.value.copy(values = _audioR26Live.value.values +
                (active.method to com.atomosremote.nativeapp.audio.AudioR26LiveValue(
                    active.method, active.command, status, messageType, raw, rawField1)))
            android.util.Log.i("AtomRemoteAudioR2613", "GET_RX method=${active.method} command=0x${active.command.toString(16)} response=0x${messageType.toString(16)} " +
                "local=$local bodyBytes=${body.size} field1Raw=$rawField1 raw=$raw currentRawOnly=true")
            audioR26Advance(gen)
        }
    }

    // B01.20: Channel REC is ON/OFF for each REC channel, NEVER a headphone source selector.
    // B01.19 missed idle windows because the click-time ATT/write guard ran once while
    // recurring E0 transport polls were in flight. B01.20 arms ONE explicit request,
    // then waits for a bounded quiet window. It is NOT a verified SET serializer.
    // B01.21: ID 3 OFF -> recorder physically disabled Channels 5/6,
    // and a new Audio 0x8647 GET removed ID 3 from f3 while f4 remained unchanged.
    // Only this particular ID/direction is hardware-verified so far.
    // Other valid REC IDs are operator trials, not a calibrated channel map.
    private data class B0121QueuedRecordTrial(
        val session: BluetoothGatt,
        val id: Int,
        val expectedOn: Boolean,
        val baselineGeneration: Int
    )
    private var b0121LastQueuedRecord: B0121QueuedRecordTrial? = null
    private var b0120RecordPending = false

    @SuppressLint("MissingPermission")
    fun b0119TryRecordOneShot(recordId: Int, enable: Boolean): Boolean {
        val live = _audioR26Live.value
        val proof = live.values["AudioMetersPageGet"]
        val page = proof?.takeIf { it.status == "TYPED_RX" && it.responseType == 0x8647 }
            ?.rawHex?.let { com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(it) }
        val valid = page?.recordValidList?.contains(recordId.toLong()) == true
        val enabledNow = page?.recordEnabledList?.contains(recordId.toLong()) == true
        val startGatt = gatt
        // No global one-shot latch. Re-arm only after a NEW, completed recorder
        // GET in this same BLE session confirms the previous requested REC ID.
        // Never release on enqueue success or GATT status=0 alone.
        val pendingEmbed = b0123EmbedQueued
        if (b0123EmbedArming) {
            android.util.Log.w("AtomRemoteAudioB0123", "REC_BLOCK_EMBED_ARMING tx=false")
            return false
        }
        if (pendingEmbed != null) {
            val embedFresh = startGatt === pendingEmbed.session &&
                live.generation > pendingEmbed.baselineGeneration && !live.running &&
                proof?.status == "TYPED_RX" && proof.responseType == 0x8647
            if (!embedFresh) {
                android.util.Log.w("AtomRemoteAudioB0124", "REC_WAIT_EMBED_FRESH_GET " +
                    "target=${pendingEmbed.target} tx=false")
                return false
            }
            // B01.24 hardware evidence: 0x06B2 / 0800 was ACKed by ATT but
            // fresh f11 remained 1. That is an Embedded SET failure, not a
            // reason to block the already-proven REC setter forever.
            b0123EmbedQueued = null
            if (page?.analogEmbed?.toInt() != pendingEmbed.target) {
                android.util.Log.w("AtomRemoteAudioB0124", "EMBED_FRESH_GET_MISMATCH_SETTLED " +
                    "target=${pendingEmbed.target} actual=${page?.analogEmbed} " +
                    "embeddedVerified=false recGateReleased=true noRetry=true tx=false")
            } else {
                android.util.Log.i("AtomRemoteAudioB0124", "EMBED_GET_MATCH_BEFORE_REC " +
                    "target=${pendingEmbed.target} recGateReleased=true")
            }
        }
        val prior = b0121LastQueuedRecord
        if (prior != null) {
            if (startGatt !== prior.session) {
                b0121LastQueuedRecord = null
                android.util.Log.i("AtomRemoteAudioB0121", "REC_SESSION_RESET previousId=${prior.id} tx=false")
            } else {
                val fresh = live.generation > prior.baselineGeneration && !live.running &&
                    proof?.status == "TYPED_RX" && proof?.responseType == 0x8647
                val confirmed = fresh && page?.recordValidList?.contains(prior.id.toLong()) == true &&
                    (page?.recordEnabledList?.contains(prior.id.toLong()) == prior.expectedOn)
                if (!confirmed) {
                    android.util.Log.w("AtomRemoteAudioB0121", "REC_WAIT_FRESH_GET " +
                        "previousId=${prior.id} target=${prior.expectedOn} " +
                        "generation=${live.generation} baseline=${prior.baselineGeneration} " +
                        "fresh=$fresh actualOn=${page?.recordEnabledList?.contains(prior.id.toLong())} " +
                        "tx=false noRepeat=true")
                    return false
                }
                b0121LastQueuedRecord = null
                android.util.Log.i("AtomRemoteAudioB0121", "REC_PREVIOUS_GET_CONFIRMED " +
                    "id=${prior.id} state=${prior.expectedOn} freshGeneration=${live.generation} " +
                    "nextManualToggleAllowed=true")
            }
        }
        val logicalReady = !b0120RecordPending && !live.running &&
            recordId in 0..7 && valid && (enabledNow != enable) &&
            startGatt != null && _tcsPairingState.value.acceptWritten &&
            _tcsRemoteState.value.available && !remoteBootstrapActive
        if (!logicalReady) {
            android.util.Log.w("AtomRemoteAudioB0120", "REC_ARM_BLOCKED id=$recordId target=$enable " +
                "valid=$valid enabledNow=$enabledNow " +
                "pending=$b0120RecordPending pageCurrent=${page != null} " +
                "collectorRunning=${live.running} " +
                "gatt=${startGatt != null} pairing=${_tcsPairingState.value.acceptWritten} " +
                "remoteAvailable=${_tcsRemoteState.value.available} " +
                "bootstrap=${remoteBootstrapActive} tx=false")
            return false
        }
        // Hypothetical field #1 = REC index, field #2 = enable bool.
        // Retained exactly to isolate the B01.19 transport-busy problem.
        // NO claim of official wire-tag verification or recorder acceptance.
        val protoCandidate = byteArrayOf(0x08, recordId.toByte(), 0x10,
            if (enable) 1.toByte() else 0.toByte())
        b0120RecordPending = true
        val armedAt = android.os.SystemClock.elapsedRealtime()
        android.util.Log.w("AtomRemoteAudioB0120", "REC_ARMED id=$recordId " +
            "from=$enabledNow target=$enable proto=${protoCandidate.toHex()} " +
            "cmd=0x06A7 maxWaitMs=12000 tx=false")
        val worker = object : Runnable {
            var lastDiagnostic = -1L
            override fun run() {
                if (!b0120RecordPending) return
                val age = android.os.SystemClock.elapsedRealtime() - armedAt
                val sameSession = gatt === startGatt && _tcsPairingState.value.acceptWritten
                if (!sameSession || age > 12_000L || remoteBootstrapActive) {
                    b0120RecordPending = false
                    android.util.Log.w("AtomRemoteAudioB0120", "REC_ARM_ABORT id=$recordId " +
                        "ageMs=$age sameSession=$sameSession bootstrap=$remoteBootstrapActive tx=false")
                    return
                }
                val nowLive = _audioR26Live.value
                val free = !_audioR26Live.value.running && !inputCollectorRunning &&
                    !inputExtendedRunning && r2706MicActive == null &&
                    r2706MicReads.isEmpty() && !operationInFlight &&
                    pendingRemotePacket == null && pendingOps.isEmpty() &&
                    packetLink18.bufferedPacketSize() == 0 &&
                    packetLink18.state().toString() == "READY"
                if (!free) {
                    if (lastDiagnostic < 0L || age - lastDiagnostic >= 1800L) {
                        lastDiagnostic = age
                        android.util.Log.i("AtomRemoteAudioB0120", "REC_IDLE_WAIT id=$recordId " +
                            "ageMs=$age audioRunning=${nowLive.running} " +
                            "inputRunning=$inputCollectorRunning extended=$inputExtendedRunning " +
                            "micActive=${r2706MicActive != null} micQueued=${r2706MicReads.size} " +
                            "attBusy=$operationInFlight ops=${pendingOps.size} " +
                            "packetPending=${pendingRemotePacket != null} " +
                            "buffered=${packetLink18.bufferedPacketSize()} " +
                            "plink=${packetLink18.state()} tx=false")
                    }
                    mainHandler.postDelayed(this, 50L)
                    return
                }
                // Exactly one TX per click. A second operator click is allowed
                // ONLY after a fresh, command-following 0x8647 GET matches.
                b0120RecordPending = false
                android.util.Log.w("AtomRemoteAudioB0120", "REC_CANDIDATE_QUEUE_ATTEMPT " +
                    "id=$recordId target=$enable cmd=0x06A7 proto=${protoCandidate.toHex()} " +
                    "ageMs=$age")
                val queued = enqueueOfficialRemoteCommandProtoPayload(
                    commandId = 0x06A7, proto = protoCandidate,
                    purpose = "b0120-record-one-shot:$recordId:$enable",
                    label = "B01.20 REC trial ID $recordId to $enable", local = true
                )
                if (queued && startGatt != null) {
                    b0121LastQueuedRecord = B0121QueuedRecordTrial(
                        startGatt, recordId, enable, nowLive.generation)
                }
                android.util.Log.w("AtomRemoteAudioB0120", "REC_QUEUE_RESULT " +
                    "id=$recordId target=$enable queued=$queued " +
                    "recorderConfirmed=false verifyPhysicalRecorderAndFreshGet=true")
                android.util.Log.i("AtomRemoteAudioB0121", "REC_POST_QUEUE_GATE " +
                    "id=$recordId queued=$queued requiresNewConfirmedGet=$queued " +
                    "baseGeneration=${nowLive.generation} sessionBound=true")
                // No optimistic local state change, no automatic repeat, no
                // new GET preempting PacketLink. Operator performs fresh GET.
            }
        }
        mainHandler.post(worker)
        return true // ARMED only, NOT ATT/TX confirmed.
    }


// B01.23: isolated Audio Embedded one-shot trial. The x86/arm64 class
// AnalogEmbedSet and SwiftProtobuf _analogEmbed field exist, but 0x06B2
// and field-1 wire tags are *not* established by binary analysis.
// This is NOT a production setter; only the operator's explicit consent
// with a current GET may schedule one bounded trial. No automatic retry.
private data class B0123EmbedQueued(val session: BluetoothGatt,
    val target: Int, val baselineGeneration: Int)
private var b0123EmbedQueued: B0123EmbedQueued? = null
private var b0123EmbedArming = false

@SuppressLint("MissingPermission")
private fun b0123TryEmbedOneShot(desired: Int): Boolean {
    // B01.28: RETIRED after two recorder-correlated failed 0x06B2 hardware trials.
    // This private stub is intentionally non-transmitting. No old candidate can
    // be revived by calling this method, after app data reset, or after reconnect.
    android.util.Log.w("AtomRemoteAudioB0128", "EMBED_SET_RETIRED target=$desired " +
        "reason=0x06B2_FAILED_BOTH_DIRECTIONS tx=false noRetry=true")
    return false
}

    // B01.34: macOS x86_64 Headphone Level slider ACTION handler
    // 0x10001A1C5 calls 0x10007C5B0 (value in EDI), which passes 0x06AB
    // to 0x100088970; 0x1000889A3 writes opcode to outgoing +0x10.
    // It uses scalar value encoding, but AG2 hardware validation is REQUIRED.
    // Old 0x06AC is an unverified adjacent-opcode guess and MUST NOT be sent.
    // Scope = PhonesLevel ONLY. Orientation/Embedded/REC logic above untouched.
    private data class B0134PhonesPending(
        val session: BluetoothGatt, val target: Int, val baseline: Int,
        val generationBeforeTx: Int
    )
    private var b0134PhonesSession: BluetoothGatt? = null
    private var b0134PhonesArming = false
    private var b0134PhonesPending: B0134PhonesPending? = null
    private var b0134PhonesFailedTarget: Int? = null
    private var b0134PhonesLastTxMs = 0L

    @SuppressLint("MissingPermission")
    private fun b0134TryHeadphoneLevelSet(desired: Int): Boolean {
        val session = gatt
        if (session == null) {
            android.util.Log.w("AtomRemoteAudioB0134", "PHONES_BLOCK reason=NO_GATT tx=false")
            return false
        }
        if (b0134PhonesSession !== session) {
            b0134PhonesSession = session
            b0134PhonesArming = false
            b0134PhonesPending = null
            b0134PhonesFailedTarget = null
            b0134PhonesLastTxMs = 0L
            android.util.Log.i("AtomRemoteAudioB0134", "PHONES_SESSION_RESET noAutomaticTX=true")
        }
        val live = _audioR26Live.value
        val proof = live.values["AudioMetersPageGet"]
        val fresh = !live.running && proof?.status == "TYPED_RX" &&
            proof.responseType == 0x8647
        val page = if (fresh) proof?.rawHex?.let {
            com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(it)
        } else null
        val current = page?.phonesLevel?.toInt()
        val low = page?.phonesLevelMin?.toInt()
        val high = page?.phonesLevelMax?.toInt()

        // A manually requested GET after a timed-out automatic GET can also
        // complete verification. Never treat ATT status=0 as recorder success.
        val previous = b0134PhonesPending
        if (previous != null && (previous.session !== session ||
                (fresh && live.generation > previous.generationBeforeTx))) {
            val matched = previous.session === session && current == previous.target
            b0134PhonesFailedTarget = if (matched) null else previous.target
            b0134PhonesPending = null
            android.util.Log.w("AtomRemoteAudioB0134", "PHONES_FRESH_GET_SETTLED " +
                "wanted=${previous.target} observed=$current matched=$matched " +
                "noAutoRetry=true physicalRecorderMustMatch=true")
        }
        val oldAudio = b0130QueuedAudio
        val waitingOtherAudio = oldAudio != null && oldAudio.session === session &&
            live.generation <= oldAudio.generationBeforeTx
        val boundsValid = current != null && low != null && high != null &&
            low >= 0 && high <= 127 && high > low && desired in low..high && desired != current
        val paired = _tcsPairingState.value.acceptWritten && _tcsRemoteState.value.available
        val cooldown = b0134PhonesLastTxMs > 0 &&
            android.os.SystemClock.elapsedRealtime() - b0134PhonesLastTxMs < 700L
        val ready = fresh && boundsValid && paired && !remoteBootstrapActive &&
            !inputCollectorRunning && !inputExtendedRunning &&
            !b0134PhonesArming && b0134PhonesPending == null &&
            !b0130AudioArming && !waitingOtherAudio &&
            !b0120RecordPending && b0121LastQueuedRecord == null &&
            !b0123EmbedArming && b0123EmbedQueued == null &&
            b0134PhonesFailedTarget != desired && !cooldown
        if (!ready) {
            android.util.Log.w("AtomRemoteAudioB0134", "PHONES_BLOCK current=$current " +
                "desired=$desired low=$low high=$high fresh=$fresh paired=$paired " +
                "arming=$b0134PhonesArming pending=${b0134PhonesPending != null} " +
                "otherAudioPending=$waitingOtherAudio blockedDirection=${b0134PhonesFailedTarget == desired} " +
                "cooldown=$cooldown tx=false")
            return false
        }
        b0134PhonesArming = true
        val generationAtArm = live.generation
        val baseline = current!!
        val armStarted = android.os.SystemClock.elapsedRealtime()
        android.util.Log.w("AtomRemoteAudioB0134", "PHONES_ARMED from=$baseline " +
            "target=$desired allowed=$low..$high opcode=0x06AB " +
            "protoCandidate=08${"%02x".format(desired)} tx=false binaryOpcodeProof=true " +
            "hardwareProofPending=true")
        val worker = object : Runnable {
            override fun run() {
                if (!b0134PhonesArming) return
                val age = android.os.SystemClock.elapsedRealtime() - armStarted
                val currentLive = _audioR26Live.value
                val sessionGood = gatt === session &&
                    _tcsPairingState.value.acceptWritten && _tcsRemoteState.value.available
                if (!sessionGood || age > 12000L || currentLive.generation != generationAtArm ||
                    remoteBootstrapActive) {
                    b0134PhonesArming = false
                    android.util.Log.w("AtomRemoteAudioB0134", "PHONES_ARM_ABORT ageMs=$age " +
                        "sessionGood=$sessionGood generationChanged=${currentLive.generation != generationAtArm} tx=false")
                    return
                }
                val idle = !currentLive.running && !inputCollectorRunning &&
                    !inputExtendedRunning && r2706MicActive == null &&
                    r2706MicReads.isEmpty() && !operationInFlight &&
                    pendingRemotePacket == null && pendingOps.isEmpty() &&
                    packetLink18.bufferedPacketSize() == 0 &&
                    packetLink18.state().toString() == "READY" &&
                    !b0120RecordPending && b0121LastQueuedRecord == null &&
                    !b0130AudioArming
                if (!idle) {
                    mainHandler.postDelayed(this, 90L)
                    return
                }
                b0134PhonesArming = false
                val payload = byteArrayOf(0x08, desired.toByte())
                val queued = enqueueOfficialRemoteCommandProtoPayload(
                    commandId = 0x06AB, proto = payload,
                    purpose = "b0134-phones-binary-verified-opcode:$desired",
                    label = "B01.34 PHONES LEVEL $baseline to $desired (VERIFY GET)",
                    local = true
                )
                android.util.Log.w("AtomRemoteAudioB0134", "PHONES_SET_QUEUED " +
                    "from=$baseline target=$desired cmd=0x06AB proto=${payload.toHex()} " +
                    "queued=$queued recorderConfirmed=false noAutoRetry=true")
                if (!queued) return
                b0134PhonesLastTxMs = android.os.SystemClock.elapsedRealtime()
                b0134PhonesPending = B0134PhonesPending(session, desired, baseline, currentLive.generation)
                val genAtTx = currentLive.generation
                val txAt = android.os.SystemClock.elapsedRealtime()
                val refresh = object : Runnable {
                    override fun run() {
                        if (gatt !== session) return
                        val elapsed = android.os.SystemClock.elapsedRealtime() - txAt
                        val v = _audioR26Live.value
                        val isIdle = !v.running && !remoteBootstrapActive &&
                            !inputCollectorRunning && !inputExtendedRunning &&
                            r2706MicActive == null && r2706MicReads.isEmpty() &&
                            !operationInFlight && pendingRemotePacket == null &&
                            pendingOps.isEmpty() && packetLink18.bufferedPacketSize() == 0 &&
                            packetLink18.state().toString() == "READY"
                        if (!isIdle) {
                            if (elapsed > 24000L) {
                                android.util.Log.w("AtomRemoteAudioB0134", "PHONES_AUTO_GET_DEFERRED " +
                                    "busy=true manualRefreshRequired=true noAutoSet=true")
                                return
                            }
                            mainHandler.postDelayed(this, 120L)
                            return
                        }
                        val accepted = startAudioR26RecoveredCollector()
                        android.util.Log.i("AtomRemoteAudioB0134", "PHONES_AUTO_GET_REQUEST " +
                            "accepted=$accepted afterSet=0x06AB target=$desired noAutoSet=true")
                        if (!accepted) return
                        val pollAt = android.os.SystemClock.elapsedRealtime()
                        val verifier = object : Runnable {
                            override fun run() {
                                if (gatt !== session) return
                                val newLive = _audioR26Live.value
                                val rx = newLive.values["AudioMetersPageGet"]
                                val newFresh = newLive.generation > genAtTx &&
                                    !newLive.running && rx?.status == "TYPED_RX" &&
                                    rx.responseType == 0x8647
                                if (newFresh) {
                                    val r = rx?.rawHex?.let {
                                        com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(it)
                                    }
                                    val observed = r?.phonesLevel?.toInt()
                                    val matched = observed == desired
                                    val pending = b0134PhonesPending
                                    if (pending != null && pending.session === session &&
                                        pending.generationBeforeTx == genAtTx) {
                                        b0134PhonesPending = null
                                        b0134PhonesFailedTarget = if (matched) null else desired
                                    }
                                    android.util.Log.w("AtomRemoteAudioB0134", "PHONES_POST_GET_RESULT " +
                                        "requested=$desired observed=$observed matched=$matched " +
                                        "baseline=$baseline physicalRecorderMustMatch=true noAutoRetry=true")
                                    return
                                }
                                if (android.os.SystemClock.elapsedRealtime() - pollAt > 22000L) {
                                    android.util.Log.w("AtomRemoteAudioB0134", "PHONES_POST_GET_TIMEOUT " +
                                        "requested=$desired manualFreshGETRequired=true " +
                                        "noAutoRetry=true")
                                    return
                                }
                                mainHandler.postDelayed(this, 200L)
                            }
                        }
                        mainHandler.postDelayed(verifier, 200L)
                    }
                }
                mainHandler.postDelayed(refresh, 180L)
            }
        }
        mainHandler.post(worker)
        return true // Armed only; UI cannot display the draft as recorder value.
    }

    // R26.65 EXPERIMENTAL AUDIO SET PILOT. Explicit UI operator action only.
    // Opcodes and field-1 scalar bodies are CANDIDATES (macOS class adjacency).
    // Do not call this from polling, reconnection, backend or automatic startup.
    @SuppressLint("MissingPermission")
    fun r2665TryAudioScalarSet(key: String, desired: Int): Boolean {
        if (key == "phones") return b0134TryHeadphoneLevelSet(desired)
        // R2695_UNIFIED_SOURCE_BATCH: candidate TX is NOT a confirmed SET contract.
        // B01: Only the explicitly operator-triggered phones pilot may pass the
        // historical proof gate. All other unverified SET keys remain blocked.
        // 0x06AC remains an unverified candidate, not production control.
        if (key != "phones" && !com.atomosremote.nativeapp.audio.AudioR2695Proof.canHardwareSet(key)) {
            android.util.Log.w("AtomRemoteAudioR2695", "SET_BLOCKED unverified-audio key=$key desired=$desired")
            return false
        }
        if (key == "phones" && "b01-phones" in a12PilotAttempted) {
            android.util.Log.w("AtomRemoteAudioB01", "PHONES_ONE_SHOT_ALREADY_ATTEMPTED tx=false")
            return false
        }
        val proof = _audioR26Live.value.values["AudioMetersPageGet"]
        val page = proof?.takeIf { it.status == "TYPED_RX" && it.responseType == 0x8647 }
            ?.rawHex?.let { com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(it) }
        val current = when (key) {
            "phones" -> page?.phonesLevel
            "monitor" -> page?.monitor
            "orientation" -> page?.orientation
            "embed" -> page?.analogEmbed
            else -> null
        }
        val allowed = when (key) {
            "phones" -> page != null && desired.toLong() in page.phonesLevelMin..page.phonesLevelMax
            "monitor" -> page != null && desired.toLong() in page.monitorList
            "orientation" -> page != null && page.orientationChangeable != 0L && desired in 0..1
            "embed" -> page != null && desired.toLong() in page.analogEmbedList
            else -> false
        }
        val ready = _tcsPairingState.value.acceptWritten && _tcsRemoteState.value.available &&
            page != null && allowed && current != null && current != desired.toLong() &&
            desired in 0..127 && !audioR26Live.value.running && !remoteBootstrapActive &&
            !inputCollectorRunning && !inputExtendedRunning && pendingRemotePacket == null &&
            pendingOps.isEmpty() && packetLink18.bufferedPacketSize() == 0
        if (!ready) {
            android.util.Log.i("AtomRemoteAudioR2665", "SET_TRIAL_BLOCKED key=$key desired=$desired " +
                "current=$current allowed=$allowed paired=${_tcsPairingState.value.acceptWritten} " +
                "collector=${audioR26Live.value.running} busy=${pendingRemotePacket != null}" +
                " remoteAvailable=${_tcsRemoteState.value.available} " +
                " bootstrap=$remoteBootstrapActive inputCore=$inputCollectorRunning " +
                " inputExtended=$inputExtendedRunning attBusy=$operationInFlight " +
                " attQueue=${pendingOps.size} plinkBuffered=${packetLink18.bufferedPacketSize()}")
            return false
        }
        // IMPORTANT: These four IDs are candidates, NOT confirmed AG2 setter opcodes.
        val opcode = when (key) {
            "phones" -> 0x06AB // B01.34 direct binary action path; early-dispatched above
            "monitor" -> 0x06A6 // between recovered MonitorGet and MonitorListGet
            "orientation" -> 0x06B0 // between recovered OrientationGet and AnalogEmbedGet
            "embed" -> 0x06B2 // between recovered AnalogEmbedGet and AnalogEmbedListGet
            else -> return false
        }
        android.util.Log.i("AtomRemoteAudioR2665", "SET_TRIAL_ATTEMPT key=$key opcode=0x${opcode.toString(16)} " +
            "baseline=$current desired=$desired protoCandidate=08${"%02x".format(desired)} " +
            "operatorAction=true ACK_NOT_YET_VERIFIED noAutomaticRetry=true")
        android.util.Log.i("AtomRemoteAudioB011", "SET_DISPATCH_ENTER key=$key command=0x${opcode.toString(16)} " +
            "target=$desired gattPresent=${gatt != null} plinkState=${packetLink18.state()} " +
            "peerCredit=${packetLink18.peerCredit()} pending=${pendingRemotePacket?.purpose ?: "none"} " +
            "attBusy=$operationInFlight attQueue=${pendingOps.size} candidateUnverified=true")
        val queued = enqueueOfficialRemoteCommandProtoExplicitVarint1(
            commandId = opcode, value = desired,
            purpose = "r2665-audio-set-trial:$key:$desired",
            label = "R2665 AUDIO SET TRIAL $key $desired"
        )
        android.util.Log.i("AtomRemoteAudioB011", "SET_DISPATCH_RETURN key=$key queued=$queued " +
            "plinkState=${packetLink18.state()} buffered=${packetLink18.bufferedPacketSize()} " +
            "pending=${pendingRemotePacket?.purpose ?: "none"} physicalWriteNotYetConfirmed=true")
        if (key == "phones" && queued) a12PilotAttempted.add("b01-phones")
        android.util.Log.i("AtomRemoteAudioR2665", "SET_TRIAL_QUEUE key=$key " +
            "desired=$desired queued=$queued gattTxNotYetProven=true")
        return queued
    }

    // R26.72 EXPERIMENTAL MIC/LINE CANDIDATE GET/SET.
    // These methods are explicit operator trials based on official macOS method names
    // and adjacent opcode recovery. They are NOT verified protocol implementations.
    @SuppressLint("MissingPermission")
    fun r2672TryMicLineCandidateGet(key: String): Boolean {
        // R2706: operator-tapped macOS empty-body getter. Defer across transport
        // ownership instead of dropping taps. The existing PacketLink writer,
        // Input packet order, TCS pairing and all SET paths are untouched.
        val macGet = com.atomosremote.nativeapp.audio.AudioR2705MacReadOnlyCommands.get(key)
        if (macGet != null) return r2706RequestMicLineRead(key, macGet)
        // R27.00: all four ABI-image candidates were rejected by AG2 (0xFFFF).
        // Never keep probing on a card tap or pretend a failed GET has a value.
        // Preserve official PacketLink and all working remote controls unchanged.
        if (!com.atomosremote.nativeapp.audio.AudioR2698MacGetAbi.canTransmitGet(key)) {
            val method = com.atomosremote.nativeapp.audio.AudioR2698MacGetAbi.forKey(key)
                ?: return false
            r2697MicLineState(key, method.opcode, "WIRE_SCHEMA_UNVERIFIED",
                "R26.99 candidate got 0xFFFF; official SwiftProtobuf wire encoding not established. No BLE command sent")
            android.util.Log.w("AtomRemoteAudioR2700",
                "MIC_GET_SAFE_BLOCK key=$key cmd=0x${method.opcode.toString(16)} " +
                    "source=macOS-local-ABI notWireFormat=true transmitted=false")
            return false
        }
        val ready = _tcsPairingState.value.acceptWritten && _tcsRemoteState.value.available &&
            !audioR26Live.value.running && !remoteBootstrapActive && !inputCollectorRunning &&
            !inputExtendedRunning && pendingRemotePacket == null && pendingOps.isEmpty() &&
            packetLink18.bufferedPacketSize() == 0
        if (!ready) {
            com.atomosremote.nativeapp.audio.AudioR2697MicLineEvidence.getOpcode(key)?.let { cmd ->
                r2697MicLineState(key, cmd, "DEFERRED_TRANSPORT_BUSY", "No GET bytes transmitted")
            }
            android.util.Log.i("AtomRemoteAudioR2672", "GET_TRIAL_BLOCKED key=$key " +
                "paired=${_tcsPairingState.value.acceptWritten} remoteAvailable=${_tcsRemoteState.value.available} " +
                "collector=${audioR26Live.value.running} pending=${pendingRemotePacket?.purpose ?: "none"} " +
                "attBusy=$operationInFlight attQueue=${pendingOps.size} plinkBuffered=${packetLink18.bufferedPacketSize()}")
            return false
        }
        // R26.98: macOS constructor ABI candidates, NOT proven serialized wire
        // schemas. One candidate GET per explicit operator card tap, no SET.
        val candidate = com.atomosremote.nativeapp.audio.AudioR2698MacGetAbi.forKey(key)
            ?: return false
        val commandId = candidate.opcode
        val proto = candidate.body
        val label = "${candidate.method} ${candidate.key} ABI Candidate"
        android.util.Log.i("AtomRemoteAudioR2699",
            "GET_ABI_CANDIDATE key=$key cmd=0x${commandId.toString(16).uppercase()} " +
                "body=${proto.toHex()} provenance=${candidate.provenance} " +
                "wireVerified=false operatorAction=true noAutoRetry=true")
        val queued = enqueueOfficialRemoteCommandProtoPayload(
            commandId = commandId,
            proto = proto,
            purpose = "r2672-micline-get:$key",
            label = "R2672 $label",
            local = true
        )
        android.util.Log.i("AtomRemoteAudioR2672", "GET_TRIAL_QUEUE key=$key queued=$queued")
        r2697MicLineState(key, commandId,
            if (queued) "AWAITING_CANDIDATE_RX" else "DEFERRED_QUEUE_REFUSED",
            "Local ABI candidate ${candidate.provenance}, not a verified wire schema or numeric GET")
        if (queued) {
            val token = r2697MicLineToken
            mainHandler.postDelayed({
                val record = _r2697MicLineEvidence.value.controls[key]
                if (record?.requestToken == token && record.state == "AWAITING_CANDIDATE_RX") {
                    _r2697MicLineEvidence.value = _r2697MicLineEvidence.value.copy(
                        controls = _r2697MicLineEvidence.value.controls +
                            (key to record.copy(state = "CANDIDATE_GET_TIMEOUT",
                                detail = "No purpose-correlated response observed in 12 seconds")))
                    android.util.Log.i("AtomRemoteAudioR2697", "MIC_GET_TIMEOUT key=$key token=$token")
                }
            }, 12_000L)
        }
        return queued
    }

    // A05.1: bounded, operator-only Mic/Line SET readiness scheduler.
    // Existing setter guards and PacketLink TX implementation remain authoritative.
    // It only waits for earlier GET owners, refreshes stale proven GETs, and offers
    // one narrow pilot at a normal READY boundary. No automatic SET retry.
    private data class A051SetIntent(
        val id: Long, val kind: String, val selector: Boolean, val restore: Boolean,
        val desired: Int, val startedAt: Long,
        var getAttempted: Boolean = false,
        @Volatile var inputReadyAtBoundary: Boolean = false
    )
    @Volatile private var a051SetIntent: A051SetIntent? = null
    private var a051NextIntentId = 0L
    private val a051IntentTtlMs = 75_000L

    private fun a051CancelSetIntent(reason: String) {
        val intent = a051SetIntent ?: return
        a051SetIntent = null
        android.util.Log.w("AtomRemoteAudioA051",
            "SET_PREP_ABORT kind=${intent.kind} id=${intent.id} reason=$reason tx=false")
    }

    private fun a051StartSetIntent(kind: String, selector: Boolean = false,
                                  restore: Boolean = false, desired: Int = 0): Boolean {
        val existing = a051SetIntent
        if (existing != null || gatt == null || !_tcsPairingState.value.acceptWritten ||
            !_tcsRemoteState.value.available || remoteBootstrapActive ||
            audioR26Live.value.running || inputCollectorRunning || inputExtendedRunning ||
            (kind == "INPUT" && (desired !in 1..2 || a05InputPendingTarget != null))) {
            android.util.Log.w("AtomRemoteAudioA051", "SET_PREP_BLOCKED kind=$kind " +
                "active=${existing?.kind ?: "none"} desired=$desired tx=false")
            return false
        }
        a051NextIntentId += 1L
        val intent = A051SetIntent(a051NextIntentId, kind, selector, restore, desired,
            android.os.SystemClock.elapsedRealtime())
        a051SetIntent = intent
        android.util.Log.i("AtomRemoteAudioA051", "SET_PREP_ACCEPTED kind=$kind " +
            "id=${intent.id} selector=$selector restore=$restore desired=$desired " +
            "deadlineMs=$a051IntentTtlMs transmitted=false")
        mainHandler.post { a051PollSetIntent(intent.id) }
        return true
    }

    fun a051RequestGainPilot(selector: Boolean, restore: Boolean): String =
        if (a051StartSetIntent("GAIN", selector, restore)) "PREPARING_GET_IDLE"
        else "BLOCKED_NO_TX"

    fun a051RequestInputPilot(desired: Int): Boolean =
        a051StartSetIntent("INPUT", desired = desired)

    private fun a051PollSetIntent(id: Long) {
        val intent = a051SetIntent ?: return
        if (intent.id != id) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - intent.startedAt > a051IntentTtlMs) {
            a051CancelSetIntent("bounded-timeout")
            return
        }
        if (gatt == null || !_tcsPairingState.value.acceptWritten ||
            !_tcsRemoteState.value.available || remoteBootstrapActive ||
            audioR26Live.value.running || inputCollectorRunning || inputExtendedRunning) {
            a051CancelSetIntent("session-or-owner-changed")
            return
        }
        val key = if (intent.kind == "GAIN") "gainLeft" else "inputLevel"
        val rec = _r2697MicLineEvidence.value.controls[key]
        val lastRx = if (intent.kind == "GAIN") r2722LastGainRxMs else a05LastInputRxMs
        val maxAge = if (intent.kind == "GAIN") 25_000L else 15_000L
        val typed = if (intent.kind == "GAIN")
            rec?.command == 0x06B6 && rec.responseType == 0x86B1 &&
                rec.state == "CANDIDATE_RX_UNMAPPED" &&
                com.atomosremote.nativeapp.audio.AudioR2714GainReadback.decodeHex(rec.rawHex) != null
        else
            rec?.command == 0x06B3 && rec.responseType == 0x86AE &&
                rec.state == "CANDIDATE_RX_UNMAPPED" &&
                com.atomosremote.nativeapp.audio.AudioR2716InputLevelReadback.decodeHex(rec.rawHex) != null
        val fresh = lastRx > 0L && now - lastRx in 0L..maxAge && typed
        // A05.2: A previous restore may already have physically succeeded.
        // Reconcile its pending marker ONLY from a fresh typed 0/0 recorder GET.
        // Never clear an unverified -1 dB state and never send a restore here.
        if (intent.kind == "GAIN" && intent.restore && fresh) {
            val confirmed = com.atomosremote.nativeapp.audio.AudioR2714GainReadback
                .decodeHex(rec!!.rawHex)
            if (confirmed?.leftRaw == 0L && confirmed.rightRaw == 0L &&
                r2722PilotRestoreSelector == intent.selector) {
                r2722PilotRestoreSelector = null
                r2722PilotSinceMs = 0L
                a051CancelSetIntent("verified-zero-zero-no-restore-tx")
                android.util.Log.i("AtomRemoteAudioA052", "GAIN_RESTORE_RECONCILED " +
                    "selector=${intent.selector} rawLeft=0 rawRight=0 tx=false source=fresh-typed-GET")
                return
            }
        }
        if (!fresh) {
            // Let the existing bounded Mic/Line GET queue drain naturally. In
            // particular, the nonresponding Delay GET must not be cancelled.
            if (!intent.getAttempted && r2706MicActive == null &&
                r2706MicReads.isEmpty() && pendingRemotePacket == null &&
                pendingOps.isEmpty() && !inputExtendedRunning) {
                intent.getAttempted = r2672TryMicLineCandidateGet(key)
                android.util.Log.i("AtomRemoteAudioA051", "SET_PREP_FRESH_GET " +
                    "kind=${intent.kind} accepted=${intent.getAttempted} key=$key txIsGetOnly=true")
            }
            if (intent.getAttempted && rec?.state in setOf("RX_TIMEOUT_AFTER_DELIVERY",
                    "DELIVERY_TIMEOUT_AFTER_ATT", "ATT_WRITE_UNCONFIRMED_TIMEOUT")) {
                a051CancelSetIntent("fresh-get-failed")
                return
            }
        } else if (r2706MicActive == null && r2706MicReads.isEmpty() &&
            pendingRemotePacket == null && pendingOps.isEmpty() &&
            packetLink18.bufferedPacketSize() == 0) {
            if (intent.kind == "GAIN") {
                val verdict = r2722RequestGainPilotIdleWindow(intent.selector, intent.restore)
                if (verdict == "WAITING_IDLE" || verdict == "QUEUED_UNVERIFIED") {
                    a051SetIntent = null
                    android.util.Log.i("AtomRemoteAudioA051", "GAIN_SET_PREP_RESULT " +
                        "result=$verdict selector=${intent.selector} restore=${intent.restore} " +
                        "recorderApplied=UNVERIFIED")
                    // A05.4: bounded GET-only readback scheduling. The old single
                    // 2.7s attempt could miss while another Mic owner was active.
                    // This block NEVER retries or transmits a SET.
                    val readbackStartedAt = android.os.SystemClock.elapsedRealtime()
                    val readback = object : Runnable {
                        override fun run() {
                            if (gatt == null || !_tcsPairingState.value.acceptWritten ||
                                !_tcsRemoteState.value.available) {
                                android.util.Log.w("AtomRemoteAudioA054",
                                    "GAIN_GET_AFTER_SET_CANCEL reason=session-changed setRetransmit=false")
                                return
                            }
                            if (r2722LastGainRxMs != 0L) {
                                android.util.Log.i("AtomRemoteAudioA054",
                                    "GAIN_GET_AFTER_SET_FINISHED source=typed-0x86B1 setRetransmit=false")
                                return
                            }
                            val ageMs = android.os.SystemClock.elapsedRealtime() - readbackStartedAt
                            if (ageMs > 15_000L) {
                                android.util.Log.w("AtomRemoteAudioA054",
                                    "GAIN_GET_AFTER_SET_TIMEOUT elapsedMs=$ageMs setRetransmit=false")
                                return
                            }
                            val free = r2706MicActive == null && r2706MicReads.isEmpty() &&
                                pendingRemotePacket == null && pendingOps.isEmpty() &&
                                packetLink18.bufferedPacketSize() == 0 &&
                                !audioR26Live.value.running && !inputCollectorRunning &&
                                !inputExtendedRunning
                            if (ageMs >= 1_500L && free) {
                                val requested = r2672TryMicLineCandidateGet("gainLeft")
                                android.util.Log.i("AtomRemoteAudioA054",
                                    "GAIN_GET_AFTER_SET_ATTEMPT accepted=$requested " +
                                        "elapsedMs=$ageMs requestType=GET setRetransmit=false")
                                if (requested) return
                            }
                            mainHandler.postDelayed(this, 450L)
                        }
                    }
                    mainHandler.postDelayed(readback, 1_500L)
                    return
                }
            } else {
                val raw = com.atomosremote.nativeapp.audio.AudioR2716InputLevelReadback
                    .decodeHex(rec!!.rawHex)?.rawEnum
                if (raw == intent.desired.toLong()) {
                    a051CancelSetIntent("target-already-current")
                    return
                }
                if (raw !in 1L..2L) {
                    a051CancelSetIntent("invalid-input-enum")
                    return
                }
                // The next completed ATT read owns the only safe READY boundary.
                // The existing A05 setter still performs all final guards.
                if (!intent.inputReadyAtBoundary) {
                    intent.inputReadyAtBoundary = true
                    android.util.Log.i("AtomRemoteAudioA051", "INPUT_BOUNDARY_ARMED " +
                        "desired=${intent.desired} tx=false")
                }
            }
        }
        mainHandler.postDelayed({ a051PollSetIntent(id) }, 120L)
    }

    @SuppressLint("MissingPermission")
    private fun a051OfferInputAtCompletedRead(g: BluetoothGatt) {
        val intent = a051SetIntent ?: return
        if (intent.kind != "INPUT" || !intent.inputReadyAtBoundary || g !== gatt ||
            packetLink18.state().toString() != "READY" || operationInFlight ||
            android.os.SystemClock.elapsedRealtime() - intent.startedAt > a051IntentTtlMs) return
        // No preemption, no special GATT write, and no retry after success.
        val sent = a05TryInputLevelSetPilot(intent.desired)
        if (sent) {
            a051SetIntent = null
            android.util.Log.i("AtomRemoteAudioA051", "INPUT_BOUNDARY_RESULT " +
                "desired=${intent.desired} queued=true effect=UNVERIFIED freshGetRequired=true")
        }
    }

    // R27.22.2: An operator-requested pilot may wait briefly for the normal
    // PLINK E0 WRITING/READING turn to finish. This does NOT relax any guard:
    // the original r2722TryGainPilot() is the sole physical-write gate.
    // A reconnect/session reset, stale GET, other queued work, or timeout cancels.
    // R27.22.3: the ATT read callback sees a READY boundary before the next
    // 10 ms PacketLink process tick. These fields publish the UI intent to
    // that callback; no transport timer or BLE state machine is modified.
    private val r2722IdleWindowLock = Any()
    @Volatile private var r2722IdleWindowToken = 0L
    @Volatile private var r2722IdleWindowPending = false
    @Volatile private var r2722IdleWindowStartedAtMs = 0L
    @Volatile private var r2722IdleWindowSelector = false
    @Volatile private var r2722IdleWindowRestore = false

    private fun r2722CancelIdleWindow(reason: String): Boolean {
        val cancelled = synchronized(r2722IdleWindowLock) {
            if (!r2722IdleWindowPending) false else {
                r2722IdleWindowPending = false
                r2722IdleWindowToken += 1L
                r2722IdleWindowStartedAtMs = 0L
                true
            }
        }
        if (cancelled) android.util.Log.w("AtomRemoteAudioR2722",
            "IDLE_WINDOW_CANCEL reason=$reason tx=false")
        return cancelled
    }

    // Claim is atomic against the 15ms fallback polling and ATT read callback.
    // This is essential: a single UI acknowledgment can never queue twice.
    private fun r2722ClaimIdleWindow(token: Long): Boolean = synchronized(r2722IdleWindowLock) {
        if (!r2722IdleWindowPending || token != r2722IdleWindowToken) false else {
            r2722IdleWindowPending = false
            r2722IdleWindowToken += 1L
            r2722IdleWindowStartedAtMs = 0L
            true
        }
    }

    private fun r2722IdleWindowPrerequisites(selector: Boolean, restore: Boolean): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val rec = _r2697MicLineEvidence.value.controls["gainLeft"]
        val gains = if (rec?.command == 0x06B6 && rec.responseType == 0x86B1 &&
            rec.state == "CANDIDATE_RX_UNMAPPED")
            com.atomosremote.nativeapp.audio.AudioR2714GainReadback.decodeHex(rec.rawHex)
        else null
        val fresh = r2722LastGainRxMs > 0L && (now - r2722LastGainRxMs) in 0L..25_000L
        val contextReady = gatt != null && _tcsPairingState.value.acceptWritten &&
            _tcsRemoteState.value.available && !remoteBootstrapActive &&
            !audioR26Live.value.running && !inputCollectorRunning && !inputExtendedRunning &&
            r2706MicActive == null && r2706MicReads.isEmpty() &&
            pendingRemotePacket == null && pendingOps.isEmpty() &&
            packetLink18.bufferedPacketSize() == 0
        val baselineSafe = gains?.leftRaw == 0L && gains.rightRaw == 0L
        val restoreSafe = gains != null &&
            ((gains.leftRaw == -2L && gains.rightRaw == 0L) ||
             (gains.leftRaw == 0L && gains.rightRaw == -2L)) &&
            r2722PilotRestoreSelector == selector &&
            (now - r2722PilotSinceMs) in 0L..300_000L
        val eligible = contextReady && fresh && (if (restore) restoreSafe else baselineSafe) &&
            (restore || r2722PilotRestoreSelector == null)
        if (!eligible) {
            android.util.Log.w("AtomRemoteAudioR2722",
                "A03_GAIN_GUARD tx=false restore=$restore selector=$selector " +
                "fresh=$fresh ageMs=${if (r2722LastGainRxMs > 0L) now - r2722LastGainRxMs else -1L} " +
                "baselineSafe=$baselineSafe restoreSafe=$restoreSafe " +
                "leftRaw=${gains?.leftRaw} rightRaw=${gains?.rightRaw} " +
                "paired=${_tcsPairingState.value.acceptWritten} remote=${_tcsRemoteState.value.available} " +
                "bootstrap=${remoteBootstrapActive} audioCollector=${audioR26Live.value.running} " +
                "inputCollector=$inputCollectorRunning inputExtended=$inputExtendedRunning " +
                "micActive=${r2706MicActive != null} micQueued=${r2706MicReads.size} " +
                "pendingPacket=${pendingRemotePacket != null} pendingOps=${pendingOps.size} " +
                "plinkBuffered=${packetLink18.bufferedPacketSize()} operationInFlight=$operationInFlight " +
                "plinkState=${packetLink18.state()} restorePending=${r2722PilotRestoreSelector != null}")
        }
        return eligible
    }

    /** Return WAITING_IDLE, QUEUED_UNVERIFIED or BLOCKED_NO_TX.
     * Only one operator request in flight; retry expires within 1,000 ms.
     * Do not confuse WAITING_IDLE with a transmitted or applied SET.
     */
    @SuppressLint("MissingPermission")
    fun r2722RequestGainPilotIdleWindow(selector: Boolean, restore: Boolean): String {
        if (r2722IdleWindowPending) {
            android.util.Log.w("AtomRemoteAudioR2722",
                "IDLE_WINDOW_REJECT duplicate=true selector=$selector restore=$restore tx=false")
            return "BLOCKED_NO_TX"
        }
        if (!r2722IdleWindowPrerequisites(selector, restore)) {
            android.util.Log.w("AtomRemoteAudioR2722",
                "IDLE_WINDOW_REJECT prerequisites=false selector=$selector restore=$restore tx=false")
            return "BLOCKED_NO_TX"
        }
        // Do not issue a SET inside an active GATT operation, even if PLINK
        // has zero application bytes buffered. Require actual READY too.
        if (!operationInFlight && packetLink18.state().toString() == "READY") {
            val queued = r2722TryGainPilot(selector, restore)
            android.util.Log.i("AtomRemoteAudioR2722",
                "IDLE_WINDOW_IMMEDIATE queued=$queued selector=$selector restore=$restore")
            return if (queued) "QUEUED_UNVERIFIED" else "BLOCKED_NO_TX"
        }
        val state = packetLink18.state().toString()
        if (!operationInFlight || state !in setOf("WRITING", "READING")) {
            android.util.Log.w("AtomRemoteAudioR2722",
                "IDLE_WINDOW_REJECT activeAtt=$operationInFlight state=$state reason=not-plink-turn tx=false")
            return "BLOCKED_NO_TX"
        }
        val started = android.os.SystemClock.elapsedRealtime()
        val token = synchronized(r2722IdleWindowLock) {
            r2722IdleWindowToken += 1L
            r2722IdleWindowStartedAtMs = started
            r2722IdleWindowSelector = selector
            r2722IdleWindowRestore = restore
            r2722IdleWindowPending = true
            r2722IdleWindowToken
        }
        var samples = 0
        android.util.Log.i("AtomRemoteAudioR2722",
            "IDLE_WINDOW_ARMED selector=$selector restore=$restore ttlMs=1000 tx=false")
        val task = object : Runnable {
            override fun run() {
                if (token != r2722IdleWindowToken || !r2722IdleWindowPending) return
                samples += 1
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                if (elapsed > 1_000L || !r2722IdleWindowPrerequisites(selector, restore)) {
                    if (r2722CancelIdleWindow(if (elapsed > 1_000L) "timeout" else "guard-changed")) {
                        android.util.Log.w("AtomRemoteAudioR2722",
                            "IDLE_WINDOW_ABORT selector=$selector restore=$restore elapsed=$elapsed samples=$samples noSet=true")
                    }
                    return
                }
                if (!operationInFlight && packetLink18.state().toString() == "READY") {
                    if (r2722ClaimIdleWindow(token)) {
                        // Re-check all original guards immediately at transmission.
                        val queued = r2722TryGainPilot(selector, restore)
                        android.util.Log.i("AtomRemoteAudioR2722",
                            "IDLE_WINDOW_RESULT queued=$queued selector=$selector restore=$restore " +
                                "elapsed=$elapsed samples=$samples recorderApplied=UNVERIFIED")
                    }
                    return
                }
                mainHandler.postDelayed(this, 15L)
            }
        }
        mainHandler.postDelayed(task, 15L)
        return "WAITING_IDLE"
    }

    /** R27.22.3: consume a single pending pilot *inside* a completed ATT read.
     * onCharacteristicRead already set operationInFlight=false before invoking
     * the PacketLink handler. onReadSubpacket(E0 / final) sets READY here, but
     * the 10 ms process loop can consume that state before UI polling sees it.
     * This only buffers an explicit operator request; it never performs a
     * direct GATT write. The original physical SET guard remains authoritative.
     */
    @SuppressLint("MissingPermission")
    private fun r2722OfferIdleWindowAtCompletedRead(g: BluetoothGatt) {
        if (!r2722IdleWindowPending || g !== gatt ||
            packetLink18.state().toString() != "READY") return
        val token = r2722IdleWindowToken
        val started = r2722IdleWindowStartedAtMs
        val selector = r2722IdleWindowSelector
        val restore = r2722IdleWindowRestore
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        if (started <= 0L || elapsed !in 0L..1_000L) {
            if (r2722CancelIdleWindow("boundary-expired")) {
                android.util.Log.w("AtomRemoteAudioR2722",
                    "IDLE_WINDOW_BOUNDARY_ABORT reason=expired elapsed=$elapsed tx=false")
            }
            return
        }
        // If another owner took the packet or ATT queue, keep waiting only
        // until the existing deadline; never preempt Settings, Input or Remote.
        if (!r2722IdleWindowPrerequisites(selector, restore) ||
            operationInFlight || pendingOps.isNotEmpty()) {
            if (r2722CancelIdleWindow("boundary-guard-changed")) {
                android.util.Log.w("AtomRemoteAudioR2722",
                    "IDLE_WINDOW_BOUNDARY_ABORT reason=guard-changed tx=false")
            }
            return
        }
        if (!r2722ClaimIdleWindow(token)) return
        // r2722TryGainPilot checks fresh typed GET, 0/-1 raw baseline,
        // pairing, bootstrap, pending owners and *actual* ATT availability.
        val queued = r2722TryGainPilot(selector, restore)
        android.util.Log.i("AtomRemoteAudioR2722",
            "IDLE_WINDOW_BOUNDARY_RESULT queued=$queued selector=$selector " +
                "restore=$restore elapsed=$elapsed path=ATT_READ_READY " +
                "recorderApplied=UNVERIFIED physicalGetRequired=true")
    }

    // A12.37: Proven selector=false LEFT / selector=true RIGHT.
    // Numeric 0.5 dB mapping comes from GET A/B and -1 dB SET pilot.
    // This is an operator-only single shot, with explicit field-2 selector.
    // No automatic SET retries; no success until independent typed GET.
    @Volatile private var a137GainPending = false
    private var a137GainSelector = false
    private var a137GainTargetRaw = 0L
    private var a137GainBeforeLeft = 0L
    private var a137GainBeforeRight = 0L
    private var a137GainStartedMs = 0L

    @SuppressLint("MissingPermission")
    fun a137RequestGainSet(selector: Boolean, rawTarget: Int): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val rec = _r2697MicLineEvidence.value.controls["gainLeft"]
        val gains = if (rec?.command == 0x06B6 && rec.responseType == 0x86B1 &&
            rec.state == "CANDIDATE_RX_UNMAPPED")
            com.atomosremote.nativeapp.audio.AudioR2714GainReadback.decodeHex(rec.rawHex)
        else null
        val current = if (selector) gains?.rightRaw else gains?.leftRaw
        if (a137GainPending || rawTarget !in -20..40 || gains == null ||
            r2722LastGainRxMs <= 0L || now - r2722LastGainRxMs !in 0L..15_000L ||
            current == rawTarget.toLong() || gatt == null ||
            !_tcsPairingState.value.acceptWritten || !_tcsRemoteState.value.available ||
            remoteBootstrapActive || audioR26Live.value.running ||
            inputCollectorRunning || inputExtendedRunning ||
            r2722PilotRestoreSelector != null || a051SetIntent != null) {
            android.util.Log.w("AtomRemoteAudioA137Gain", "GAIN_RANGE_BLOCKED selector=$selector target=$rawTarget " +
                "current=$current pending=$a137GainPending freshMs=${now-r2722LastGainRxMs} tx=false")
            return false
        }
        a137GainPending = true
        a137GainSelector = selector
        a137GainTargetRaw = rawTarget.toLong()
        a137GainBeforeLeft = gains.leftRaw
        a137GainBeforeRight = gains.rightRaw
        a137GainStartedMs = now
        android.util.Log.i("AtomRemoteAudioA137Gain", "GAIN_RANGE_ARMED selector=$selector targetRaw=$rawTarget " +
            "beforeLeft=${gains.leftRaw} beforeRight=${gains.rightRaw} setTx=false")
        val run = object : Runnable {
            override fun run() {
                if (!a137GainPending || a137GainStartedMs != now) return
                val elapsed = android.os.SystemClock.elapsedRealtime() - now
                if (elapsed > 5_000L || gatt == null || !_tcsPairingState.value.acceptWritten ||
                    !_tcsRemoteState.value.available || remoteBootstrapActive) {
                    a137GainPending = false
                    android.util.Log.w("AtomRemoteAudioA137Gain", "GAIN_RANGE_ABORT reason=idle-timeout-or-session " +
                        "elapsedMs=$elapsed tx=false")
                    return
                }
                val idle = !operationInFlight && pendingRemotePacket == null &&
                    pendingOps.isEmpty() && r2706MicActive == null && r2706MicReads.isEmpty() &&
                    !inputCollectorRunning && !inputExtendedRunning && !audioR26Live.value.running &&
                    packetLink18.bufferedPacketSize() == 0 && packetLink18.state().toString() == "READY"
                if (!idle) { mainHandler.postDelayed(this, 25L); return }
                // Explicit field-2 FALSE or TRUE is required, even when false.
                val raw = rawTarget.toLong()
                val wire = java.io.ByteArrayOutputStream()
                wire.write(0x08)
                var x = raw
                while (true) {
                    if ((x and -128L) == 0L) { wire.write(x.toInt()); break }
                    wire.write(((x and 127L) or 128L).toInt())
                    x = x ushr 7
                }
                wire.write(0x10)
                wire.write(if (selector) 1 else 0)
                val proto = wire.toByteArray()
                val packet = PendingRemotePacket(
                    buildOfficialAtomMessageBodyWithProtoPayload(0, 0x06B7, proto),
                    "a137-gain-range:$selector:$rawTarget", "A137 Gain SET", 0x06B7, 0)
                pendingRemotePacket = packet
                pendingRemoteFrameWritten = false
                val queued = queueRemotePacket18_8(packet, local = true)
                if (!queued && pendingRemotePacket === packet) {
                    pendingRemotePacket = null
                    pendingRemoteFrameWritten = false
                }
                if (!queued) {
                    a137GainPending = false
                    android.util.Log.w("AtomRemoteAudioA137Gain", "GAIN_RANGE_QUEUE_REFUSED selector=$selector " +
                        "raw=$rawTarget tx=false")
                    return
                }
                r2722LastGainRxMs = 0L
                android.util.Log.i("AtomRemoteAudioA137Gain", "GAIN_RANGE_SET_QUEUED selector=$selector " +
                    "targetRaw=$rawTarget proto=${proto.toHex()} elapsedMs=$elapsed " +
                    "transmissionUnverified=true applied=UNKNOWN noSetRetry=true")
                // GET ONLY. Busy transport may defer GET; do not retransmit SET.
                val requestAt = android.os.SystemClock.elapsedRealtime()
                val verify = object : Runnable {
                    override fun run() {
                        if (!a137GainPending || a137GainStartedMs != now) return
                        val age = android.os.SystemClock.elapsedRealtime() - requestAt
                        if (age > 15_000L) {
                            a137GainPending = false
                            android.util.Log.w("AtomRemoteAudioA137Gain", "GAIN_RANGE_UNVERIFIED_TIMEOUT " +
                                "targetRaw=$rawTarget setRetry=false")
                            return
                        }
                        val free = r2706MicActive == null && r2706MicReads.isEmpty() &&
                            pendingRemotePacket == null && pendingOps.isEmpty() &&
                            packetLink18.bufferedPacketSize() == 0 && !operationInFlight
                        if (free && r2722LastGainRxMs == 0L) {
                            val accepted = r2672TryMicLineCandidateGet("gainLeft")
                            android.util.Log.i("AtomRemoteAudioA137Gain", "GAIN_RANGE_VERIFY_GET " +
                                "accepted=$accepted ageMs=$age cmd=0x06B6 setRetry=false")
                        }
                        mainHandler.postDelayed(this, 450L)
                    }
                }
                mainHandler.postDelayed(verify, 400L)
            }
        }
        mainHandler.post(run)
        return true
    }

    /** R27.22 constrained hardware pilot, not production gain SET.
     * Unknown channel polarity is intentionally exposed as selector=false/true.
     * The ONLY allowed physical delta is 0 dB -> -1 dB on one channel.
     * Restore 0 dB is a separate operator action after independent fresh GET.
     * All other four-control SET paths remain locked.
     */
    @SuppressLint("MissingPermission")
    fun r2722TryGainPilot(selector: Boolean, restore: Boolean): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val rec = _r2697MicLineEvidence.value.controls["gainLeft"]
        val gains = if (rec?.command == 0x06B6 && rec.responseType == 0x86B1 &&
            rec.state == "CANDIDATE_RX_UNMAPPED")
            com.atomosremote.nativeapp.audio.AudioR2714GainReadback.decodeHex(rec.rawHex)
        else null
        val fresh = r2722LastGainRxMs > 0L && (now - r2722LastGainRxMs) in 0L..25_000L
        val ready = gatt != null && _tcsPairingState.value.acceptWritten &&
            _tcsRemoteState.value.available && !remoteBootstrapActive &&
            !audioR26Live.value.running && !inputCollectorRunning && !inputExtendedRunning &&
            r2706MicActive == null && r2706MicReads.isEmpty() &&
            pendingRemotePacket == null && pendingOps.isEmpty() &&
            packetLink18.bufferedPacketSize() == 0 && !operationInFlight &&
            packetLink18.state().toString() == "READY"
        val baselineSafe = gains?.leftRaw == 0L && gains.rightRaw == 0L
        val restoreSafe = gains != null &&
            ((gains.leftRaw == -2L && gains.rightRaw == 0L) ||
             (gains.leftRaw == 0L && gains.rightRaw == -2L)) &&
            r2722PilotRestoreSelector == selector &&
            (now - r2722PilotSinceMs) in 0L..300_000L
        if (!ready || !fresh || (if (restore) !restoreSafe else !baselineSafe) ||
            (!restore && r2722PilotRestoreSelector != null)) {
            android.util.Log.w("AtomRemoteAudioR2722",
                "GAIN_PILOT_BLOCKED restore=$restore selector=$selector ready=$ready " +
                "fresh=$fresh left=${gains?.leftRaw} right=${gains?.rightRaw} " +
                "restorePending=${r2722PilotRestoreSelector != null} tx=false")
            android.util.Log.w("AtomRemoteAudioR2722",
                "R2722_GUARD_DETAIL " +
                "gattPresent=${gatt != null} " +
                "pairAccepted=${_tcsPairingState.value.acceptWritten} " +
                "remoteAvailable=${_tcsRemoteState.value.available} " +
                "bootstrapInactive=${!remoteBootstrapActive} " +
                "audioInactive=${!audioR26Live.value.running} " +
                "inputCollectorInactive=${!inputCollectorRunning} " +
                "inputExtendedInactive=${!inputExtendedRunning} " +
                "micActiveIdle=${r2706MicActive == null} " +
                "micQueueEmpty=${r2706MicReads.isEmpty()} " +
                "packetPendingNone=${pendingRemotePacket == null} " +
                "opsEmpty=${pendingOps.isEmpty()} " +
                "plinkEmpty=${packetLink18.bufferedPacketSize() == 0} " +
                "plinkBytes=${packetLink18.bufferedPacketSize()} " +
                "plinkState=${packetLink18.state()} " +
                "operationIdle=${!operationInFlight} tx=false")
            return false
        }
        val rawGain = if (restore) 0 else -2
        val legacyProto = com.atomosremote.nativeapp.audio.AudioR2722GainPilotWire
            .gainMessage(rawGain, selector, explicitZero = restore)
        // A12.36: deliberately encode a present field-2 FALSE, instead of
        // omitting it. Only the existing operator-triggered 0/-1dB pilot.
        // This is a one-shot protocol hypothesis, NOT a proven recorder setting.
        val proto = if (!selector) legacyProto + byteArrayOf(0x10, 0x00)
                    else legacyProto
        android.util.Log.i("AtomRemoteAudioA136Gain",
            "GAIN_EXPLICIT_SELECTOR_PILOT selector=$selector restore=$restore " +
            "field2Present=true explicitFalse=${!selector} " +
            "legacy=${legacyProto.toHex()} candidate=${proto.toHex()} " +
            "hardwareEffect=UNVERIFIED")
        // Isolate the pilot packet from legacy R26.72 and clean up on queue refusal.
        val purpose = "r2722-gain-pilot:${if (restore) "restore" else "trial"}:$selector"
        val body = buildOfficialAtomMessageBodyWithProtoPayload(0, 0x06B7, proto)
        val packet = PendingRemotePacket(body, purpose,
            "R2722 Gain PILOT ${if (restore) "RESTORE" else "MINUS_1_DB"}", 0x06B7, 0)
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        val queued = queueRemotePacket18_8(packet, local = true)
        if (!queued && pendingRemotePacket === packet) {
            pendingRemotePacket = null
            pendingRemoteFrameWritten = false
        }
        if (queued) {
            // This is an evidence marker, not an acknowledgment of recorder SET.
            a135GainPilotSelector = selector
            a135GainPilotRestore = restore
            a135GainPilotBeforeLeft = gains!!.leftRaw
            a135GainPilotBeforeRight = gains.rightRaw
            a135GainPilotAt = now
            android.util.Log.i("AtomRemoteAudioA135Gain",
                "GAIN_SELECTOR_ARMED selector=$selector restore=$restore " +
                "beforeLeft=${gains.leftRaw} beforeRight=${gains.rightRaw} " +
                "cmd=0x06B7 rawGain=$rawGain queueAccepted=true physicalEffect=UNVERIFIED")
            if (restore) {
                // A05 combined safety fix: QUEUED is not APPLIED. Keep the
                // restore marker until typed 0/0 GET proves physical restore.
                // In particular 0xFFFF or unchanged -1dB MUST NOT unlock SET.
                android.util.Log.i("AtomRemoteAudioA053", "RESTORE_QUEUED_AWAIT_GET " +
                    "selector=$selector raw=0 applied=false")
            } else {
                r2722PilotRestoreSelector = selector
                r2722PilotSinceMs = now
            }
            r2722LastGainRxMs = 0L // operator must refresh the recorder after every write
        }
        android.util.Log.i("AtomRemoteAudioR2722",
            "GAIN_PILOT_QUEUE queued=$queued selector=$selector restore=$restore " +
            "cmd=0x06B7 rawGain=$rawGain proto=${proto.toHex()} " +
            "recorderApplied=UNVERIFIED physicalGetRequired=true")
        return queued
    }

    /** A05: one-shot macOS-derived 0x06B4 InputSourceSet PILOT, NOT production SET.
     * Operator must confirm recorder idle and monitoring muted. Only 1<->2 flip
     * from a fresh 0x86AE GET, with no BLE owner conflicts; one post-SET GET.
     * Payload is a candidate (protobuf f1 enum), never treated as ACK.
     */
    @SuppressLint("MissingPermission")
    fun a05TryInputLevelSetPilot(desired: Int): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val read = _r2697MicLineEvidence.value.controls["inputLevel"]
        val current = if (read?.command == 0x06B3 && read.responseType == 0x86AE &&
            read.state == "CANDIDATE_RX_UNMAPPED")
            com.atomosremote.nativeapp.audio.AudioR2716InputLevelReadback.decodeHex(read.rawHex)
        else null
        val fresh = a05LastInputRxMs > 0L && (now - a05LastInputRxMs) in 0L..15_000L
        val idle = gatt != null && _tcsPairingState.value.acceptWritten &&
            _tcsRemoteState.value.available && !remoteBootstrapActive &&
            !audioR26Live.value.running && !inputCollectorRunning && !inputExtendedRunning &&
            r2706MicActive == null && r2706MicReads.isEmpty() &&
            pendingRemotePacket == null && pendingOps.isEmpty() && !operationInFlight &&
            packetLink18.bufferedPacketSize() == 0 && packetLink18.state().toString() == "READY"
        val previous = current?.rawEnum
        if (desired !in 1..2 || !fresh || !idle || (previous == null || previous !in 1L..2L) ||
            previous == desired.toLong() || a05InputPendingTarget != null) {
            android.util.Log.w("AtomRemoteAudioA05", "INPUT_SET_BLOCKED tx=false " +
                "desired=$desired current=$previous fresh=$fresh idle=$idle " +
                "pending=${a05InputPendingTarget != null} " +
                "gattPresent=${gatt != null} pairing=${_tcsPairingState.value.acceptWritten} " +
                "available=${_tcsRemoteState.value.available} " +
                "micActive=${r2706MicActive != null} micQueued=${r2706MicReads.size} " +
                "pendingPacket=${pendingRemotePacket?.purpose ?: "none"} " +
                "attBusy=$operationInFlight plink=${packetLink18.state()}")
            return false
        }
        // Candidate f1 enum from real AG2 GETs; SET wire has not been proven.
        val proto = byteArrayOf(0x08, desired.toByte())
        val body = buildOfficialAtomMessageBodyWithProtoPayload(0, 0x06B4, proto)
        val packet = PendingRemotePacket(body, "a05-input-pilot:$desired",
            "A05 Input Level PILOT ${if (desired == 1) "LINE" else "MIC"}", 0x06B4, 0)
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        val queued = queueRemotePacket18_8(packet, local = true)
        if (!queued && pendingRemotePacket === packet) {
            pendingRemotePacket = null
            pendingRemoteFrameWritten = false
        }
        android.util.Log.i("AtomRemoteAudioA05", "INPUT_SET_PILOT queued=$queued " +
            "cmd=0x06B4 local=true packetId=0 proto=${proto.toHex()} " +
            "before=$previous requested=$desired effect=UNVERIFIED")
        if (queued) {
            a05InputPendingTarget = desired
            a05InputPreviousRaw = previous
            a05InputSetAtMs = now
            // Exactly one readback request. Never automatically retry a SET.
            mainHandler.postDelayed({
                if (a05InputPendingTarget == desired && gatt != null &&
                    _tcsPairingState.value.acceptWritten) {
                    val accepted = r2672TryMicLineCandidateGet("inputLevel")
                    android.util.Log.i("AtomRemoteAudioA05", "INPUT_POST_SET_GET_REQUEST " +
                        "accepted=$accepted target=$desired txIsGETOnly=true")
                }
            }, 1_800L)
            // A06: One bounded additional GET opportunity if the first GET
            // could not complete while ATT/PacketLink was busy. This never
            // retries the experimental SET and never assumes a write succeeded.
            mainHandler.postDelayed({
                if (a05InputPendingTarget == desired && a05InputSetAtMs == now &&
                    gatt != null && _tcsPairingState.value.acceptWritten) {
                    val accepted = r2672TryMicLineCandidateGet("inputLevel")
                    android.util.Log.i("AtomRemoteAudioA06", "INPUT_POST_SET_GET_RETRY " +
                        "accepted=$accepted target=$desired txIsGETOnly=true setRetried=false")
                }
            }, 7_000L)
            mainHandler.postDelayed({
                if (a05InputPendingTarget == desired && a05InputSetAtMs == now) {
                    android.util.Log.w("AtomRemoteAudioA05", "INPUT_SET_UNCONFIRMED " +
                        "target=$desired reason=no-bounded-typed-readback")
                    a05InputPendingTarget = null
                    a05InputPreviousRaw = null
                    a05InputSetAtMs = 0L
                }
            }, 35_000L)
        }
        return queued
    }

    @SuppressLint("MissingPermission")
    fun r2672TryMicLineCandidateSet(key: String, desired: Int): Boolean {
        // R2695_UNIFIED_SOURCE_BATCH: wrong gain scale/channel enum must never be transmitted.
        if (!com.atomosremote.nativeapp.audio.AudioR2695Proof.canHardwareSet(key)) {
            android.util.Log.w("AtomRemoteAudioR2695", "SET_BLOCKED unverified-micline key=$key desired=$desired")
            return false
        }
        val ready = _tcsPairingState.value.acceptWritten && _tcsRemoteState.value.available &&
            !audioR26Live.value.running && !remoteBootstrapActive && !inputCollectorRunning &&
            !inputExtendedRunning && pendingRemotePacket == null && pendingOps.isEmpty() &&
            packetLink18.bufferedPacketSize() == 0
        if (!ready) {
            android.util.Log.i("AtomRemoteAudioR2672", "SET_TRIAL_BLOCKED key=$key desired=$desired " +
                "paired=${_tcsPairingState.value.acceptWritten} remoteAvailable=${_tcsRemoteState.value.available} " +
                "collector=${audioR26Live.value.running} pending=${pendingRemotePacket?.purpose ?: "none"} " +
                "attBusy=$operationInFlight attQueue=${pendingOps.size} plinkBuffered=${packetLink18.bufferedPacketSize()}")
            return false
        }
        val tuple = when (key) {
            "gainLeft" -> Triple(0x06B8, buildProtoVarintFields(1 to 0, 2 to desired.coerceIn(0, 100)), "GainSet Left")
            "gainRight" -> Triple(0x06B8, buildProtoVarintFields(1 to 1, 2 to desired.coerceIn(0, 100)), "GainSet Right")
            "delay" -> Triple(0x06BB, buildProtoVarintFields(1 to desired.coerceIn(0, 12)), "FrameDelaySet")
            "inputLevel" -> Triple(0x06B5, buildProtoVarintFields(1 to desired.coerceIn(0, 1)), "InputSourceSet")
            else -> return false
        }
        val (commandId, proto, label) = tuple
        android.util.Log.i("AtomRemoteAudioR2672",
            "SET_TRIAL_ATTEMPT key=$key desired=$desired cmd=0x${commandId.toString(16).uppercase()} " +
                "proto=${proto.toHex()} verifiedBytes=false operatorAction=true")
        val queued = enqueueOfficialRemoteCommandProtoPayload(
            commandId = commandId,
            proto = proto,
            purpose = "r2672-micline-set:$key:$desired",
            label = "R2672 $label",
            local = true
        )
        android.util.Log.i("AtomRemoteAudioR2672", "SET_TRIAL_QUEUE key=$key desired=$desired queued=$queued")
        return queued
    }


    // A12.5: bounded, explicitly triggered delay SET opportunity.
    // SET is a candidate, and a queued packet MUST NOT be shown as confirmed.
    // No other control may use this gate. No SET retry after queue acceptance.
    private val a12PilotAttempted = mutableSetOf<String>()
    private var a125DelayWaiting = false
    private var a125DelayDeadlineMs = 0L

    // A12.7: explicitly operator-triggered MONITOR candidate, NOT protocol proven.
    // Previous 0x06A6 / 0801 trial left the microphone monitor GET unchanged.
    // Deliberately isolated from existing audio SET and global ATT scheduler.
    private var a127MonitorWaiting = false
    private var a127MonitorAttempted = false
    @SuppressLint("MissingPermission")
    private fun a127MonitorExperimentalOneShot(desired: Int): Boolean {
        if (desired !in 0..1 || a127MonitorWaiting || a127MonitorAttempted) {
            android.util.Log.w("AtomRemoteAudioA127", "MONITOR_REJECT desired=$desired reason=invalid-or-once tx=false")
            return false
        }
        if (gatt == null || !_tcsPairingState.value.acceptWritten || !_tcsRemoteState.value.available) {
            android.util.Log.w("AtomRemoteAudioA127", "MONITOR_REJECT reason=not-connected tx=false")
            return false
        }
        a127MonitorWaiting = true
        val initialGatt = gatt
        val deadline = android.os.SystemClock.elapsedRealtime() + 18_000L
        val tick = object : Runnable {
            override fun run() {
                if (!a127MonitorWaiting) return
                if (gatt == null || gatt !== initialGatt || !_tcsPairingState.value.acceptWritten ||
                    !_tcsRemoteState.value.available) {
                    a127MonitorWaiting = false
                    android.util.Log.w("AtomRemoteAudioA127", "MONITOR_ABORT session-changed tx=false")
                    return
                }
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    a127MonitorWaiting = false
                    android.util.Log.w("AtomRemoteAudioA127", "MONITOR_IDLE_TIMEOUT tx=false")
                    return
                }
                val idle = !audioR26Live.value.running && !remoteBootstrapActive &&
                    !inputCollectorRunning && !inputExtendedRunning &&
                    pendingRemotePacket == null && pendingOps.isEmpty() &&
                    !operationInFlight && packetLink18.bufferedPacketSize() == 0 &&
                    r2706MicActive == null && r2706MicReads.isEmpty() &&
                    !a125DelayWaiting
                val state = packetLink18.state().toString()
                if (!idle || (state != "READY" && state != "WRITING")) {
                    mainHandler.postDelayed(this, 300L)
                    return
                }
                a127MonitorWaiting = false
                a127MonitorAttempted = true // one attempt only, including queue rejection
                val proto = buildProtoVarintFields(1 to desired)
                android.util.Log.w("AtomRemoteAudioA127", "MONITOR_SET_CANDIDATE opcode=0x06A6 proto=${proto.toHex()} " +
                    "requested=$desired mappingUnverified=true mayChangeMonitorSource=true")
                val queued = enqueueOfficialRemoteCommandProtoPayload(
                    commandId = 0x06A6, proto = proto,
                    purpose = "a127-experimental-monitor-one-shot:$desired",
                    label = "A127 UNVERIFIED MonitorSet candidate", local = true)
                android.util.Log.w("AtomRemoteAudioA127", "MONITOR_SET_QUEUE queued=$queued " +
                    "recorderVerified=false noRetry=true")
                if (queued) mainHandler.postDelayed({
                    if (gatt === initialGatt && _tcsPairingState.value.acceptWritten) {
                        val getQueued = probeR26AudioMetersPage()
                        android.util.Log.i("AtomRemoteAudioA127", "MONITOR_POST_SET_AUDIO_GET " +
                            "requested=$getQueued desired=$desired compareField1Only=true noSetRetry=true")
                    }
                }, 2_500L)
            }
        }
        mainHandler.post(tick)
        android.util.Log.w("AtomRemoteAudioA127", "MONITOR_IDLE_WINDOW_ARMED desired=$desired maxMs=18000 " +
            "explicitOneShot=true unverifiedOpcode=true")
        return true // scheduled candidate; NOT confirmed recorder change
    }

    // A12.74 SHARED Audio-tab and Mic/Line headphone-source diagnostic.
    // Recorder GET 0x8647 field 1 is authoritative. This candidate is NOT a verified SET.
    // 0x06A7 / AudioRecordMsg fields 1(record) + 2(enable) may affect other settings.
    // Never auto-send, retry, sweep IDs, or call it from GET/connection lifecycle.
    private var a1274SourceWaiting = false
    private var a1274SourceAttempted = false

    @SuppressLint("MissingPermission")
    private fun a1274SharedSourcePilot(desired: Int): Boolean {
        if (desired !in 0..7 || a1274SourceWaiting || a1274SourceAttempted) {
            android.util.Log.w("AtomRemoteAudioA1274", "REJECT reason=bad-id-or-in-progress tx=false requested=$desired")
            return false
        }
        val startGatt = gatt
        if (startGatt == null || !_tcsPairingState.value.acceptWritten ||
            !_tcsRemoteState.value.available) {
            android.util.Log.w("AtomRemoteAudioA1274", "REJECT reason=not-linked tx=false")
            return false
        }
        val prefs = appContext.getSharedPreferences("a1274_headphone_source_pilot", Context.MODE_PRIVATE)
        val lock = "one_test_" + startGatt.device.address
        if (prefs.getBoolean(lock, false) && !prefs.getBoolean("confirmed_" + startGatt.device.address, false)) {
            android.util.Log.w("AtomRemoteAudioA1274", "REJECT reason=pilot-not-verified-one-shot-spent tx=false")
            return false
        }
        // Never repeat the failed A12.73 source-1 hypothesis for the same recorder.
        if (desired == 1 && !prefs.getBoolean("confirmed_" + startGatt.device.address, false) &&
            appContext.getSharedPreferences("a1273_source1_trial", Context.MODE_PRIVATE)
                .getBoolean("attempted_" + startGatt.device.address, false)) {
            android.util.Log.w("AtomRemoteAudioA1274", "REJECT reason=A1273-source1-already-tried tx=false")
            return false
        }
        val latest = _audioR26Live.value.values["AudioMetersPageGet"]
        val before = latest?.takeIf { it.status == "TYPED_RX" && it.responseType == 0x8647 }
            ?.rawHex?.let { com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(it) }
        val currentSource = before?.monitor?.toInt()
        if (currentSource == null || currentSource !in 0..7 || currentSource == desired) {
            android.util.Log.w("AtomRemoteAudioA1274", "REJECT reason=no-valid-changed-source tx=false current=$currentSource desired=$desired")
            return false
        }
        val startGeneration = _audioR26Live.value.generation
        val initialRaw = latest!!.rawHex
        val startRecIds = before!!.recordEnabledList.joinToString(",")
        a1274SourceWaiting = true
        val deadline = android.os.SystemClock.elapsedRealtime() + 25_000L
        val tick = object : Runnable {
            override fun run() {
                if (!a1274SourceWaiting) return
                if (gatt !== startGatt || !_tcsPairingState.value.acceptWritten ||
                    !_tcsRemoteState.value.available) {
                    a1274SourceWaiting = false
                    android.util.Log.w("AtomRemoteAudioA1274", "ABORT session-disconnected tx=false")
                    return
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (now >= deadline) {
                    a1274SourceWaiting = false
                    android.util.Log.w("AtomRemoteAudioA1274", "IDLE_TIMEOUT tx=false")
                    return
                }
                val live = _audioR26Live.value.values["AudioMetersPageGet"]
                val page = live?.takeIf { it.status == "TYPED_RX" && it.responseType == 0x8647 }
                    ?.rawHex?.let { com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(it) }
                if (page?.monitor?.toInt() != currentSource) {
                    a1274SourceWaiting = false
                    android.util.Log.w("AtomRemoteAudioA1274", "ABORT recorder-source-changed-before-tx tx=false")
                    return
                }
                val state = packetLink18.state().toString()
                val idle = !_audioR26Live.value.running && !remoteBootstrapActive &&
                    !inputCollectorRunning && !inputExtendedRunning &&
                    r2706MicActive == null && r2706MicReads.isEmpty() &&
                    pendingRemotePacket == null && pendingOps.isEmpty() &&
                    !operationInFlight && packetLink18.bufferedPacketSize() == 0 &&
                    (state == "READY" || state == "WRITING") &&
                    !a125DelayWaiting && a05InputPendingTarget == null
                if (!idle) {
                    mainHandler.postDelayed(this, 250L)
                    return
                }
                a1274SourceWaiting = false
                a1274SourceAttempted = true
                val testKey = "tested_" + startGatt.device.address + "_" + desired
                if (prefs.getBoolean(testKey, false)) {
                    android.util.Log.w("AtomRemoteAudioA1274", "REJECT reason=target-already-tested tx=false target=$desired")
                    return
                }
                if (!prefs.edit().putBoolean(lock, true).putBoolean(testKey, true).commit()) {
                    android.util.Log.e("AtomRemoteAudioA1274", "LOCK_FAILURE tx=false")
                    return
                }
                // Offline macOS two-field AudioRecordMsg hypothesis, NOT source-selection proof.
                val payload = byteArrayOf(0x08, desired.toByte(), 0x10, 0x01)
                android.util.Log.w("AtomRemoteAudioA1274", "TX_INTENT opcode=0x06A7 body=${payload.toHex()} " +
                    "sourceBefore=$currentSource target=$desired recEnabledBefore=$startRecIds " +
                    "unverifiedProtocol=true singleAttempt=true")
                val queued = enqueueOfficialRemoteCommandProtoPayload(
                    commandId = 0x06A7, proto = payload,
                    purpose = "a1274-shared-headphone-source:$desired",
                    label = "A1274 HEADPHONE SOURCE UNVERIFIED PILOT", local = true)
                android.util.Log.w("AtomRemoteAudioA1274", "QUEUE_RESULT queued=$queued " +
                    "recorderConfirmed=false txDeliveryNotProven=true")
                if (!queued) return
                // Collector is requested only after the previous packet can drain.
                val readTick = object : Runnable {
                    var tries = 0
                    override fun run() {
                        if (gatt !== startGatt || !_tcsPairingState.value.acceptWritten) return
                        if (pendingRemotePacket != null || !pendingOps.isEmpty() ||
                            packetLink18.bufferedPacketSize() > 0 || operationInFlight) {
                            if (++tries < 45) mainHandler.postDelayed(this, 500L)
                            else android.util.Log.w("AtomRemoteAudioA1274", "POST_GET_DEFERRED packet-not-idle recorderConfirmed=false")
                            return
                        }
                        val requested = startAudioR26RecoveredCollector()
                        android.util.Log.i("AtomRemoteAudioA1274", "POST_GET requested=$requested opcode=0x0647")
                    }
                }
                mainHandler.postDelayed(readTick, 1800L)
                mainHandler.postDelayed({
                    if (gatt === startGatt && _tcsPairingState.value.acceptWritten) {
                        val snap = _audioR26Live.value
                        val rx = snap.values["AudioMetersPageGet"]
                        val after = rx?.takeIf { it.status == "TYPED_RX" &&
                            it.responseType == 0x8647 && snap.generation > startGeneration &&
                            it.rawHex != initialRaw }?.rawHex?.let {
                            com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(it)
                        }
                        android.util.Log.w("AtomRemoteAudioA1274", "RESULT " +
                            "freshChangedPage=${after != null} before=$currentSource " +
                            "after=${after?.monitor ?: "unknown"} target=$desired " +
                            "matched=${after?.monitor?.toInt() == desired} " +
                            "recEnabledBefore=$startRecIds " +
                            "recEnabledAfter=${after?.recordEnabledList?.joinToString(",") ?: "unknown"} " +
                            "physicalCheckRequired=true causeNotAssumed=true")
                        if (after?.monitor?.toInt() == desired) {
                            // This unlocks manual testing of OTHER IDs only after real changed GET.
                            // It does not make the control production-verified or auto-apply changes.
                            prefs.edit().putBoolean("confirmed_" + startGatt.device.address, true).commit()
                            a1274SourceAttempted = false
                            android.util.Log.w("AtomRemoteAudioA1274", "GET_MATCH_UNLOCK oneId=$desired " +
                                "additionalIdsStillManual=true physicalCheckRequired=true")
                        }
                    }
                }, 28_000L)
            }
        }
        mainHandler.post(tick)
        android.util.Log.w("AtomRemoteAudioA1274", "ARMED tx=false target=$desired " +
            "before=$currentSource maxWaitMs=25000 oneShotPerRecorder=true")
        return true // Armed only, NOT transmitted/confirmed.
    }

    // A12.8 SAFE LOCK: real AG2 A12.5/A12.7 hardware observations contradict
    // success assumptions for these two prototype opcodes. Do NOT guess another
    // command ID or silently bypass official transport guards.
    // Preserve proven GET and A07 independently verified setters unchanged.
    // A12.24: Deliberate experimental 0 -> 1 frame SET PILOT only.
    // Static ARM64 and x86_64 constructors show FrameDelaySet = 0x06BA;
    // ARM64 AudioFrameDelayMsg SwiftProtobuf traverse uses field number 1.
    // The exact AG2 acceptance is still UNVERIFIED until physical + fresh GET.
    // No set on GET, tab entry, BLE reconnect, backend, or any automatic event.
    private var a124DelayLastRxAtMs = 0L
    private var a124DelayLastFrames: Int? = null
    private var a124DelayLastRxGatt: android.bluetooth.BluetoothGatt? = null
    private var a124DelayPendingGet = false
    private var a125DelayTargetFrames: Int? = null
    private var a124DelayPendingGatt: android.bluetooth.BluetoothGatt? = null
    private var a124DelaySetQueuedAtMs = 0L

    @SuppressLint("MissingPermission")
    // A12.75 HARDWARE-FIRST diagnostic only: alternative MONITOR hypothesis.
    // 0x06A6 field-1 source ID inferred from historical command catalog, NOT recorder-verified.
    // Prior A12.74 0x06A7 candidate did not change recorder headphone source.
    // ONE manual trial per recorder; no automatic retries or bulk source sweeps.
    private var a1275SourceWaiting = false
    private var a1275SourceAttempted = false

    @SuppressLint("MissingPermission")
    private fun a1275HardwareFirstPilot(desired: Int): Boolean {
        // A12.76: 1 is a VALID target. Never conflate validity with one-shot / busy safety locks.
        if (desired !in 0..7) {
            android.util.Log.w("AtomRemoteAudioA1276", "REJECT reason=INVALID_ID tx=false target=$desired validRange=0..7")
            return false
        }
        if (a1275SourceWaiting) {
            android.util.Log.w("AtomRemoteAudioA1276", "REJECT reason=WAITING_ALREADY_ARMED tx=false target=$desired validTarget=true")
            return false
        }
        if (a1275SourceAttempted) {
            android.util.Log.w("AtomRemoteAudioA1276", "REJECT reason=ONE_SHOT_SESSION_SPENT tx=false target=$desired validTarget=true")
            return false
        }
        android.util.Log.i("AtomRemoteAudioA1276", "VALID_TARGET id=$desired tx=false validationOnly=true")
        val startGatt = gatt
        if (startGatt == null || !_tcsPairingState.value.acceptWritten ||
            !_tcsRemoteState.value.available) {
            android.util.Log.w("AtomRemoteAudioA1276", "REJECT reason=NOT_CONNECTED tx=false target=$desired validTarget=true")
            return false
        }
        val prefs = appContext.getSharedPreferences("a1275_one_shot_source", Context.MODE_PRIVATE)
        val address = startGatt.device.address
        if (prefs.getBoolean("attempted_$address", false)) {
            android.util.Log.w("AtomRemoteAudioA1276", "REJECT reason=ONE_SHOT_DURABLE_SPENT tx=false target=$desired validTarget=true")
            return false
        }
        val last = _audioR26Live.value.values["AudioMetersPageGet"]
        val before = last?.takeIf { it.status == "TYPED_RX" && it.responseType == 0x8647 }
            ?.rawHex?.let { com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(it) }
        val current = before?.monitor?.toInt()
        if (current == null || current !in 0..7 || current == desired) {
            android.util.Log.w("AtomRemoteAudioA1276", "REJECT reason=NO_CHANGED_CONFIRMED_SOURCE tx=false current=$current target=$desired validTarget=true")
            return false
        }
        val startGeneration = _audioR26Live.value.generation
        val startEnabled = before!!.recordEnabledList.joinToString(",")
        a1275SourceWaiting = true
        val deadline = android.os.SystemClock.elapsedRealtime() + 25_000L
        android.util.Log.w("AtomRemoteAudioA1276", "ARMED tx=false sourceBefore=$current target=$desired validTarget=true " +
            "opcode=0x06A6 HYPOTHESIS_ONLY=true oneShot=true")
        var lastBusyLogAtMs = 0L
        val tick = object : Runnable {
            override fun run() {
                if (!a1275SourceWaiting) return
                if (gatt !== startGatt || !_tcsPairingState.value.acceptWritten ||
                    !_tcsRemoteState.value.available) {
                    a1275SourceWaiting = false
                    android.util.Log.w("AtomRemoteAudioA1275", "ABORT reason=disconnected tx=false")
                    return
                }
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    a1275SourceWaiting = false
                    android.util.Log.w("AtomRemoteAudioA1275", "TIMEOUT tx=false")
                    return
                }
                val latest = _audioR26Live.value.values["AudioMetersPageGet"]
                val currentPage = latest?.takeIf { it.status == "TYPED_RX" && it.responseType == 0x8647 }
                    ?.rawHex?.let { com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(it) }
                if (currentPage?.monitor?.toInt() != current) {
                    a1275SourceWaiting = false
                    android.util.Log.w("AtomRemoteAudioA1275", "ABORT reason=source-changed-before-send tx=false")
                    return
                }
                val plState = packetLink18.state().toString()
                val idle = !_audioR26Live.value.running && !remoteBootstrapActive &&
                    !inputCollectorRunning && !inputExtendedRunning &&
                    r2706MicActive == null && r2706MicReads.isEmpty() &&
                    pendingRemotePacket == null && pendingOps.isEmpty() &&
                    !operationInFlight && packetLink18.bufferedPacketSize() == 0 &&
                    (plState == "READY" || plState == "WRITING") &&
                    !a125DelayWaiting && a05InputPendingTarget == null
                if (!idle) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastBusyLogAtMs >= 2_000L) {
                        lastBusyLogAtMs = now
                        android.util.Log.i("AtomRemoteAudioA1276", "WAIT_BUSY tx=false target=$desired " +
                            "plink=$plState pendingPacket=${pendingRemotePacket != null} " +
                            "pendingOps=${pendingOps.size} attBusy=$operationInFlight " +
                            "audioRunning=${_audioR26Live.value.running} " +
                            "micActive=${r2706MicActive != null} " +
                            "collector=${inputCollectorRunning || inputExtendedRunning}")
                    }
                    mainHandler.postDelayed(this, 250L)
                    return
                }
                a1275SourceWaiting = false
                // Write the durable one-shot lock BEFORE any possible send. Never clear it
                // after a successful enqueue, timeout, disconnect or missing readback.
                if (!prefs.edit().putBoolean("attempted_$address", true).commit()) {
                    android.util.Log.e("AtomRemoteAudioA1276", "ABORT reason=LOCK_WRITE_FAILED tx=false target=$desired")
                    return
                }
                // Different hypothesis from failed 0x06A7 / field1+field2. Not a proven command.
                val proto = byteArrayOf(0x08, desired.toByte())
                android.util.Log.w("AtomRemoteAudioA1275", "TX_INTENT opcode=0x06A6 proto=${proto.toHex()} sourceBefore=$current sourceTarget=$desired RECbefore=$startEnabled hypothesis=true")
                val queued = enqueueOfficialRemoteCommandProtoPayload(
                    commandId = 0x06A6, proto = proto,
                    purpose = "a1275-headphone-hardware-first:$desired",
                    label = "A1275 UNVERIFIED HEADPHONE-SOURCE PILOT", local = true)
                android.util.Log.w("AtomRemoteAudioA1276", "QUEUE_RESULT queued=$queued target=$desired " +
                    "txDeliveryUnconfirmed=true hardwareUnconfirmed=true")
                if (!queued) {
                    // enqueue=false means there was NO accepted transport request.
                    // Restore only the guard for this rejected submission, never when queued=true.
                    val released = prefs.edit().remove("attempted_$address").commit()
                    a1275SourceAttempted = !released
                    android.util.Log.w("AtomRemoteAudioA1276", "NOT_QUEUED tx=false target=$desired " +
                        "oneShotReleased=$released explicitRetryRequired=true")
                    return
                }
                a1275SourceAttempted = true
                android.util.Log.w("AtomRemoteAudioA1276", "ONE_SHOT_SPENT queued=true " +
                    "target=$desired noAutoRetry=true GET_AND_PHYSICAL_VERIFY_REQUIRED=true")
                val poll = object : Runnable {
                    var tries = 0
                    override fun run() {
                        if (gatt !== startGatt || !_tcsPairingState.value.acceptWritten) return
                        if (pendingRemotePacket != null || !pendingOps.isEmpty() ||
                            packetLink18.bufferedPacketSize() > 0 || operationInFlight) {
                            if (++tries < 45) mainHandler.postDelayed(this, 500L)
                            else android.util.Log.w("AtomRemoteAudioA1275", "POST_GET_DEFERRED packet-busy")
                            return
                        }
                        val requested = startAudioR26RecoveredCollector()
                        android.util.Log.i("AtomRemoteAudioA1275", "POST_GET requested=$requested expectedOpcode=0x0647")
                    }
                }
                mainHandler.postDelayed(poll, 1800L)
                mainHandler.postDelayed({
                    if (gatt === startGatt && _tcsPairingState.value.acceptWritten) {
                        val snap = _audioR26Live.value
                        val rx = snap.values["AudioMetersPageGet"]
                        val after = rx?.takeIf { it.status == "TYPED_RX" &&
                            it.responseType == 0x8647 && snap.generation > startGeneration }
                            ?.rawHex?.let { com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(it) }
                        android.util.Log.w("AtomRemoteAudioA1275", "READBACK current=$current target=$desired after=${after?.monitor ?: "unknown"} " +
                            "matched=${after?.monitor?.toInt() == desired} " +
                            "RECbefore=$startEnabled RECafter=${after?.recordEnabledList?.joinToString(",") ?: "unknown"} " +
                            "physicalConfirmationRequired=true notProductionVerified=true")
                    }
                }, 19_000L)
            }
        }
        mainHandler.post(tick)
        return true
    }

    // A12.77 operator-only reset of the persisted experiment guard. NO RECORDER TX.
    // A successful reset only authorizes a NEW manual attempt after checking hardware.
    private fun a1277OperatorRearmHeadphonePilot(target: Int): Boolean {
        if (target !in 0..7 || a1275SourceWaiting) {
            android.util.Log.w("AtomRemoteAudioA1277", "REARM_REJECT badTargetOrWaiting target=$target tx=false")
            return false
        }
        val activeGatt = gatt
        if (activeGatt == null || !_tcsPairingState.value.acceptWritten ||
            !_tcsRemoteState.value.available) {
            android.util.Log.w("AtomRemoteAudioA1277", "REARM_REJECT disconnected tx=false")
            return false
        }
        val snapshot = _audioR26Live.value.values["AudioMetersPageGet"]
        val parsed = snapshot?.takeIf { it.status == "TYPED_RX" && it.responseType == 0x8647 }
            ?.rawHex?.let { com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(it) }
        val actual = parsed?.monitor?.toInt()
        if (actual == null || actual !in 0..7 || actual == target) {
            android.util.Log.w("AtomRemoteAudioA1277", "REARM_REJECT noDifferentTypedGET current=$actual target=$target tx=false")
            return false
        }
        val address = activeGatt.device.address
        val prefs = appContext.getSharedPreferences("a1275_one_shot_source", Context.MODE_PRIVATE)
        val wasSpent = prefs.getBoolean("attempted_$address", false)
        val cleared = prefs.edit().remove("attempted_$address").commit()
        if (cleared) a1275SourceAttempted = false
        android.util.Log.w("AtomRemoteAudioA1277", "REARM_RESULT cleared=$cleared oldSpent=$wasSpent target=$target " +
            "current=$actual tx=false noAutomaticSET=true unverifiedProtocol=true")
        return cleared
    }

    // B01.30: official macOS x86_64 outbound command reconstruction.
    // Audio UI selectedSegmentIndex -> 0x10001B20E -> 0x10007C6C0
    // -> 0x1000820F7 writes 0x06AF to outbound packet +0x10.
    // Embedded selector -> 0x10001C6E0 -> 0x10007C770
    // -> 0x100087FE3 writes 0x06B1 to outbound packet +0x10.
    // These are binary-grounded OPCODES, not recorder-proven SETs;
    // protobuf field-1 scalar tag 0x08 remains a hardware test hypothesis.
    // Distinct from FAILED 0x06B0 / 0x06B2. No automatic retries.
    private data class B0130AudioTrial(
        val session: BluetoothGatt, val key: String, val target: Int,
        val generationBeforeTx: Int
    )
    private var b0130QueuedAudio: B0130AudioTrial? = null
    private var b0130AudioArming = false
    // B01.33: Meter Orientation is GET-gated and repeatable after successful readback.
    // Do NOT change Embedded's durable one-shot reservation or REC transport.
    private var b0133OrientationSession: BluetoothGatt? = null
    private var b0133OrientationFailedTarget: Int? = null
    private var b0133OrientationLastTxMs: Long = 0L
    // B01.36: Embedded SET is also repeatable *only after recorder GET settles*.
    // Earlier durable one-shot preference is retired; it must not block a
    // physically verified Off/1/2 control in the production UI.
    private var b0136EmbedSession: BluetoothGatt? = null
    private var b0136EmbedFailedTarget: Int? = null
    private var b0136EmbedLastTxMs: Long = 0L

    @SuppressLint("MissingPermission")
    private fun b0130TryBinaryBackedOneShot(key: String, desired: Int): Boolean {
        if (key != "orientation" && key != "embed") return false
        val currentLive = _audioR26Live.value
        val rxProof = currentLive.values["AudioMetersPageGet"]
        val page = rxProof?.takeIf { it.status == "TYPED_RX" && it.responseType == 0x8647 }
            ?.rawHex?.let { com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(it) }
        val session = gatt
        if (key == "orientation" && b0133OrientationSession !== session) {
            b0133OrientationSession = session
            b0133OrientationFailedTarget = null
            b0133OrientationLastTxMs = 0L
            android.util.Log.i("AtomRemoteAudioB0133", "ORIENTATION_SESSION_RESET transientGates=true")
        }
        if (key == "embed" && b0136EmbedSession !== session) {
            b0136EmbedSession = session
            b0136EmbedFailedTarget = null
            b0136EmbedLastTxMs = 0L
            android.util.Log.i("AtomRemoteAudioB0136", "EMBED_SESSION_RESET transientGates=true")
        }
        val before = when (key) {
            "embed" -> page?.analogEmbed?.toInt()
            else -> page?.orientation?.toInt()
        }
        val currentFresh = page != null && rxProof != null &&
            rxProof.status == "TYPED_RX" && rxProof.responseType == 0x8647 &&
            !currentLive.running
        // Previous REC and old failed Embedded queue are settled with GET
        // before allowing one of the NEW audio controls to transmit.
        val olderRec = b0121LastQueuedRecord
        if (olderRec != null) {
            if (olderRec.session !== session) {
                b0121LastQueuedRecord = null
            } else if (currentFresh && currentLive.generation > olderRec.baselineGeneration &&
                page!!.recordValidList.contains(olderRec.id.toLong()) &&
                page.recordEnabledList.contains(olderRec.id.toLong()) == olderRec.expectedOn) {
                b0121LastQueuedRecord = null
                android.util.Log.i("AtomRemoteAudioB0130", "PRIOR_REC_GET_CONFIRMED id=${olderRec.id} tx=false")
            }
        }
        val oldEmbed = b0123EmbedQueued
        if (oldEmbed != null && (oldEmbed.session !== session ||
                (currentFresh && currentLive.generation > oldEmbed.baselineGeneration))) {
            b0123EmbedQueued = null
            android.util.Log.i("AtomRemoteAudioB0130", "OLD_EMBED_QUEUE_CLEARED tx=false")
        }
        val previous = b0130QueuedAudio
        if (previous != null && (previous.session !== session ||
                (currentFresh && currentLive.generation > previous.generationBeforeTx))) {
            val observed = if (previous.key == "embed") page?.analogEmbed?.toInt()
                else page?.orientation?.toInt()
            val matched = previous.session === session && observed == previous.target && currentFresh
            android.util.Log.w("AtomRemoteAudioB0130", "SET_FRESH_GET_CHECK " +
                "key=${previous.key} wanted=${previous.target} observed=$observed " +
                "matched=$matched recorderConfirmed=$matched " +
                "physicalRecorderMustAlsoMatch=true noRetry=true")
            if (previous.key == "orientation" && previous.session === session && currentFresh) {
                b0133OrientationFailedTarget = if (matched) null else previous.target
                android.util.Log.w("AtomRemoteAudioB0133", "ORIENTATION_SETTLED wanted=${previous.target} " +
                    "observed=$observed matched=$matched blockedDirection=$b0133OrientationFailedTarget " +
                    "recorderProofRequiresPhysicalMatch=true")
            }
            if (previous.key == "embed" && previous.session === session && currentFresh) {
                b0136EmbedFailedTarget = if (matched) null else previous.target
                android.util.Log.w("AtomRemoteAudioB0136", "EMBED_SETTLED wanted=${previous.target} " +
                    "observed=$observed matched=$matched blockedDirection=$b0136EmbedFailedTarget " +
                    "recorderProofRequiresPhysicalMatch=true")
            }
            b0130QueuedAudio = null
        }
        val opcode = if (key == "embed") 0x06B1 else 0x06AF
        // Physical A/B mapping: f11=0 Off, f11=1 1/2; f9=0 Vertical, f9=1 Horizontal.
        // A queued GATT write is not recorder confirmation.
        val allowed = before != null && before in 0..1 && desired in 0..1 &&
            before != desired && page != null &&
            if (key == "embed") page.analogEmbedList.contains(desired.toLong())
            else page.orientationChangeable != 0L
        val ready = currentFresh && allowed && session != null &&
            _tcsPairingState.value.acceptWritten && _tcsRemoteState.value.available &&
            !remoteBootstrapActive && !inputCollectorRunning && !inputExtendedRunning &&
            !b0120RecordPending && b0121LastQueuedRecord == null &&
            !b0123EmbedArming && b0123EmbedQueued == null &&
            !b0130AudioArming && b0130QueuedAudio == null
        // Both production controls use one queued SET followed by a fresh GET.
        // Never auto-retry; failed target stays blocked for this BLE session.
        val blockedDirection = if (key == "orientation")
            b0133OrientationFailedTarget == desired else b0136EmbedFailedTarget == desired
        val lastTxMs = if (key == "orientation") b0133OrientationLastTxMs else b0136EmbedLastTxMs
        val cooldown = lastTxMs > 0L &&
            android.os.SystemClock.elapsedRealtime() - lastTxMs < 700L
        if (!ready || blockedDirection || cooldown) {
            android.util.Log.w("AtomRemoteAudioB0136", "SET_BLOCK " +
                "key=$key current=$before desired=$desired ready=$ready " +
                "failedDirection=$blockedDirection cooldown=$cooldown " +
                "awaitingGet=${b0130QueuedAudio != null} " +
                "recPending=${b0121LastQueuedRecord != null} tx=false")
            return false
        }
        b0130AudioArming = true
        val generationAtArm = currentLive.generation
        val armedAt = android.os.SystemClock.elapsedRealtime()
        val payload = byteArrayOf(0x08, desired.toByte())
        android.util.Log.w("AtomRemoteAudioB0130", "BINARY_SET_ARMED " +
            "key=$key current=$before desired=$desired opcode=0x${opcode.toString(16)} " +
            "protoHypothesis=${payload.toHex()} binaryOpcodeProof=true " +
            "protoHardwareVerified=false tx=false")
        val worker = object : Runnable {
            override fun run() {
                if (!b0130AudioArming) return
                val age = android.os.SystemClock.elapsedRealtime() - armedAt
                val liveNow = _audioR26Live.value
                val sessionOkay = gatt === session &&
                    _tcsPairingState.value.acceptWritten && _tcsRemoteState.value.available
                if (!sessionOkay || age > 12000L || remoteBootstrapActive ||
                    liveNow.generation != generationAtArm) {
                    b0130AudioArming = false
                    android.util.Log.w("AtomRemoteAudioB0130", "BINARY_SET_ABORT " +
                        "key=$key ageMs=$age sessionOkay=$sessionOkay " +
                        "generationChanged=${liveNow.generation != generationAtArm} tx=false")
                    return
                }
                val idle = !liveNow.running && !inputCollectorRunning &&
                    !inputExtendedRunning && r2706MicActive == null &&
                    r2706MicReads.isEmpty() && !operationInFlight &&
                    pendingRemotePacket == null && pendingOps.isEmpty() &&
                    packetLink18.bufferedPacketSize() == 0 &&
                    packetLink18.state().toString() == "READY" &&
                    !b0120RecordPending && b0121LastQueuedRecord == null
                if (!idle) {
                    if (age > 11000L) android.util.Log.i("AtomRemoteAudioB0130",
                        "BINARY_SET_WAIT_IDLE key=$key ageMs=$age tx=false")
                    mainHandler.postDelayed(this, 70L)
                    return
                }
                b0130AudioArming = false
                // A queued SET is the only transmission. Neither control writes
                // a persistent attempted flag; recorder GET / per-session guards own safety.
                val queued = enqueueOfficialRemoteCommandProtoPayload(
                    commandId = opcode, proto = payload,
                    purpose = "audio-${key}-set:$desired",
                    label = "Audio $key $before to $desired",
                    local = true
                )
                if (queued && session != null) {
                    b0130QueuedAudio = B0130AudioTrial(session, key, desired, liveNow.generation)
                    if (key == "orientation") {
                        b0133OrientationLastTxMs = android.os.SystemClock.elapsedRealtime()
                    } else {
                        b0136EmbedLastTxMs = android.os.SystemClock.elapsedRealtime()
                    }
                    android.util.Log.w("AtomRemoteAudioB0136", "AUDIO_SET_QUEUED " +
                        "key=$key from=$before target=$desired command=0x${opcode.toString(16)} " +
                        "payload=${payload.toHex()} verified=false next=AUTO_GET noAutoRetry=true")
                    // One bounded automatic READBACK for both production controls;
                    // never a second SET and never an optimistic success label.
                    if (key == "orientation" || key == "embed") {
                        val sessionAtTx = session
                        val postTxGeneration = liveNow.generation
                        val postTxTarget = desired
                        val startPostTxMs = android.os.SystemClock.elapsedRealtime()
                        val autoRefresh = object : Runnable {
                            override fun run() {
                                if (gatt !== sessionAtTx || !_tcsPairingState.value.acceptWritten) {
                                    android.util.Log.w("AtomRemoteAudioB0131", "ORIENTATION_AUTO_GET_ABORT reason=SESSION_CHANGED tx=false")
                                    return
                                }
                                val elapsed = android.os.SystemClock.elapsedRealtime() - startPostTxMs
                                val snapshot = _audioR26Live.value
                                val idle = !snapshot.running && !remoteBootstrapActive &&
                                    !inputCollectorRunning && !inputExtendedRunning &&
                                    r2706MicActive == null && r2706MicReads.isEmpty() &&
                                    pendingRemotePacket == null && pendingOps.isEmpty() &&
                                    !operationInFlight && packetLink18.bufferedPacketSize() == 0 &&
                                    packetLink18.state().toString() == "READY"
                                if (!idle) {
                                    if (elapsed > 14000L) {
                                        android.util.Log.w("AtomRemoteAudioB0131", "ORIENTATION_AUTO_GET_DEFERRED busy=true noAutoSet=true")
                                        return
                                    }
                                    mainHandler.postDelayed(this, 120L)
                                    return
                                }
                                val accepted = startAudioR26RecoveredCollector()
                                android.util.Log.i("AtomRemoteAudioB0136", "AUDIO_AUTO_GET_REQUEST " +
                                    "key=$key accepted=$accepted target=$postTxTarget " +
                                    "afterSetCmd=0x${opcode.toString(16)} noAutoSet=true")
                                if (!accepted) return
                                val verifyStart = android.os.SystemClock.elapsedRealtime()
                                val verifier = object : Runnable {
                                    override fun run() {
                                        if (gatt !== sessionAtTx) return
                                        val now = _audioR26Live.value
                                        val proof = now.values["AudioMetersPageGet"]
                                        val fresh = now.generation > postTxGeneration && !now.running &&
                                            proof?.status == "TYPED_RX" && proof.responseType == 0x8647
                                        if (fresh) {
                                            val readback = proof?.rawHex?.let {
                                                com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(it)
                                            }
                                            val actual = if (key == "embed")
                                                readback?.analogEmbed?.toInt() else readback?.orientation?.toInt()
                                            val matched = actual == postTxTarget
                                            val pending = b0130QueuedAudio
                                            if (pending != null && pending.key == key &&
                                                pending.session === sessionAtTx &&
                                                pending.generationBeforeTx == postTxGeneration) {
                                                b0130QueuedAudio = null
                                                if (key == "embed") b0136EmbedFailedTarget = if (matched) null else postTxTarget
                                                else b0133OrientationFailedTarget = if (matched) null else postTxTarget
                                            }
                                            android.util.Log.w("AtomRemoteAudioB0136", "AUDIO_POST_GET_RESULT " +
                                                "key=$key requested=$postTxTarget observed=$actual matched=$matched " +
                                                "physicalRecorderMustAlsoMatch=true noAutoRetry=true tx=false")
                                            return
                                        }
                                        if (android.os.SystemClock.elapsedRealtime() - verifyStart > 12000L) {
                                            android.util.Log.w("AtomRemoteAudioB0131", "ORIENTATION_POST_GET_TIMEOUT " +
                                                "requested=$postTxTarget freshTypedGET=false noAutoRetry=true tx=false")
                                            return
                                        }
                                        mainHandler.postDelayed(this, 220L)
                                    }
                                }
                                mainHandler.postDelayed(verifier, 220L)
                            }
                        }
                        mainHandler.postDelayed(autoRefresh, 180L)
                    }
                } else if (!queued) {
                    android.util.Log.w("AtomRemoteAudioB0136", "AUDIO_SET_QUEUE_REFUSED " +
                        "key=$key transientReservationReleased=true tx=false")
                }
                android.util.Log.w("AtomRemoteAudioB0130", "BINARY_SET_QUEUE_RESULT " +
                    "key=$key command=0x${opcode.toString(16)} payload=${payload.toHex()} " +
                    "queued=$queued recorderConfirmed=false verifyF=${if (key == "embed") 11 else 9} " +
                    "manualFreshGETRequired=true noRetry=true")
            }
        }
        mainHandler.post(worker)
        return true // Armed only, do not display as confirmed.
    }


    // B01.35 Headphone Source: the macOS monitor-selection ACTION path
    // calls 0x10007C0A0 with chosen source in EDI (from 0x10001AB53).
    // That constructor passes outgoing opcode 0x06A5 to 0x100087FB0;
    // helper at 0x100087FE3 writes the opcode into the outbound packet.
    // Encoder field-1 scalar is a hardware TRIAL until AG2 GET f1 and
    // physical monitoring agree. This is NEVER Channel REC (f3/0x06A7).
    // Source f1 == 0: Mic/Line headphone monitor ON; nonzero: OFF.
    private data class B0135SourcePending(
        val session: BluetoothGatt, val target: Int, val generationBeforeTx: Int,
        val enabledRecIds: List<Long>
    )
    private var b0135SourceSession: BluetoothGatt? = null
    private var b0135SourceArming = false
    private var b0135SourcePending: B0135SourcePending? = null
    private var b0135SourceFailedTarget: Int? = null

    @SuppressLint("MissingPermission")
    private fun b0135TryHeadphoneSourceSet(desired: Int): Boolean {
        val session = gatt
        if (session == null || !_tcsPairingState.value.acceptWritten ||
            !_tcsRemoteState.value.available) {
            android.util.Log.w("AtomRemoteAudioB0135", "SOURCE_BLOCK reason=NOT_LINKED target=$desired tx=false")
            return false
        }
        if (b0135SourceSession !== session) {
            b0135SourceSession = session
            b0135SourceArming = false
            b0135SourcePending = null
            b0135SourceFailedTarget = null
            android.util.Log.i("AtomRemoteAudioB0135", "SOURCE_SESSION_RESET noTx=true")
        }
        val live = _audioR26Live.value
        val rx = live.values["AudioMetersPageGet"]
        val page = rx?.takeIf { it.status == "TYPED_RX" && it.responseType == 0x8647 }
            ?.rawHex?.let { com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(it) }
        val earlier = b0135SourcePending
        if (earlier != null) {
            if (live.generation <= earlier.generationBeforeTx || live.running || page == null) {
                android.util.Log.w("AtomRemoteAudioB0135", "SOURCE_WAIT_FRESH_GET target=${earlier.target} tx=false")
                return false
            }
            val recUnchanged = page.recordEnabledList == earlier.enabledRecIds
            val match = page.monitor.toInt() == earlier.target && recUnchanged
            b0135SourceFailedTarget = if (match) null else earlier.target
            b0135SourcePending = null
            android.util.Log.w("AtomRemoteAudioB0135", "SOURCE_PREVIOUS_GET_SETTLED target=${earlier.target} actual=${page.monitor} " +
                "recUnchanged=$recUnchanged matched=$match physicalCheckRequired=true")
        }
        val current = page?.monitor?.toInt()
        val valid = page?.monitorList?.contains(desired.toLong()) == true
        // A REC readback latch may remain allocated after its GET was already confirmed.
        // Read it without modifying the working REC subsystem.
        val earlierRec = b0121LastQueuedRecord
        val recSettled = earlierRec == null || earlierRec.session !== session ||
            (live.generation > earlierRec.baselineGeneration && !live.running &&
                page?.recordValidList?.contains(earlierRec.id.toLong()) == true &&
                page.recordEnabledList.contains(earlierRec.id.toLong()) == earlierRec.expectedOn)
        val earlierOrientation = b0130QueuedAudio
        val orientationSettled = earlierOrientation == null ||
            earlierOrientation.session !== session ||
            (live.generation > earlierOrientation.generationBeforeTx && !live.running &&
                page != null && ((earlierOrientation.key == "orientation" &&
                    page.orientation.toInt() == earlierOrientation.target) ||
                    (earlierOrientation.key == "embed" &&
                    page.analogEmbed.toInt() == earlierOrientation.target)))
        // B01.37: Older Audio/REC transactions may retain an allocated latch
        // even AFTER a newer typed Audio GET already proves their target.
        // Do not modify their state machines; only evaluate whether their
        // pending readback is settled before allowing an independent SOURCE SET.
        val phonesPending = b0134PhonesPending
        val phonesSettled = phonesPending == null || phonesPending.session !== session ||
            (live.generation > phonesPending.generationBeforeTx && !live.running &&
                page?.phonesLevel?.toInt() == phonesPending.target)
        val recGateClear = !b0120RecordPending || recSettled
        val ready = desired in 0..4 && valid && current != null && current != desired &&
            b0135SourceFailedTarget != desired && !b0135SourceArming &&
            !live.running && !remoteBootstrapActive && !inputCollectorRunning &&
            !inputExtendedRunning && recGateClear && recSettled &&
            orientationSettled && phonesSettled
        if (!ready) {
            android.util.Log.w("AtomRemoteAudioB0137", "SOURCE_GATE_BLOCK target=$desired current=$current valid=$valid " +
                "arming=$b0135SourceArming failedTarget=$b0135SourceFailedTarget " +
                "audioRunning=${live.running} recPending=$b0120RecordPending " +
                "recSettled=$recSettled orientationSettled=$orientationSettled " +
                "phonesSettled=$phonesSettled phonesLatch=${phonesPending != null} " +
                "bootstrap=$remoteBootstrapActive inputRunning=$inputCollectorRunning " +
                "inputExtended=$inputExtendedRunning tx=false")
            return false
        }
        b0135SourceArming = true
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000L
        val initialGeneration = live.generation
        android.util.Log.w("AtomRemoteAudioB0135", "SOURCE_ARMED current=$current target=$desired cmd=0x06A5 " +
            "protoCandidate=08${"%02x".format(desired)} binaryOpcodeProof=true " +
            "protoHardwareVerified=false tx=false")
        val worker = object : Runnable {
            override fun run() {
                if (!b0135SourceArming) return
                if (gatt !== session || !_tcsPairingState.value.acceptWritten ||
                    !_tcsRemoteState.value.available) {
                    b0135SourceArming = false
                    android.util.Log.w("AtomRemoteAudioB0135", "SOURCE_ABORT sessionChanged=true tx=false")
                    return
                }
                if (android.os.SystemClock.elapsedRealtime() > deadline) {
                    b0135SourceArming = false
                    android.util.Log.w("AtomRemoteAudioB0135", "SOURCE_IDLE_TIMEOUT tx=false noAutoRetry=true")
                    return
                }
                val latest = _audioR26Live.value
                val proof = latest.values["AudioMetersPageGet"]
                val latestPage = proof?.takeIf { it.status == "TYPED_RX" && it.responseType == 0x8647 }
                    ?.rawHex?.let { com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(it) }
                if (latestPage == null || latestPage.monitor.toInt() != current ||
                    latestPage.monitorList.contains(desired.toLong()) != true) {
                    b0135SourceArming = false
                    android.util.Log.w("AtomRemoteAudioB0135", "SOURCE_ABORT baselineOrChoicesChanged=true tx=false")
                    return
                }
                val idle = !latest.running && !remoteBootstrapActive &&
                    !inputCollectorRunning && !inputExtendedRunning &&
                    r2706MicActive == null && r2706MicReads.isEmpty() &&
                    !b0120RecordPending && pendingRemotePacket == null &&
                    pendingOps.isEmpty() && !operationInFlight &&
                    packetLink18.bufferedPacketSize() == 0 &&
                    packetLink18.state().toString() == "READY"
                if (!idle) {
                    mainHandler.postDelayed(this, 90L)
                    return
                }
                val payload = byteArrayOf(0x08, desired.toByte())
                b0135SourceArming = false
                val queued = enqueueOfficialRemoteCommandProtoPayload(
                    commandId = 0x06A5, proto = payload,
                    purpose = "b0135-headphone-source:$current->$desired",
                    label = "B01.35 HEADPHONE SOURCE SET TEST", local = true)
                android.util.Log.w("AtomRemoteAudioB0135", "SOURCE_SET_QUEUED from=$current target=$desired " +
                    "cmd=0x06A5 proto=${payload.toHex()} queued=$queued " +
                    "recorderConfirmed=false recIdsUntouched=true noAutoRetry=true")
                if (!queued) return
                val generationAtTx = latest.generation
                b0135SourcePending = B0135SourcePending(session, desired, generationAtTx,
                    latestPage.recordEnabledList.toList())
                val startMs = android.os.SystemClock.elapsedRealtime()
                val autoRead = object : Runnable {
                    override fun run() {
                        if (gatt !== session) return
                        val now = _audioR26Live.value
                        val idleRead = !now.running && !remoteBootstrapActive &&
                            !inputCollectorRunning && !inputExtendedRunning &&
                            r2706MicActive == null && r2706MicReads.isEmpty() &&
                            pendingRemotePacket == null && pendingOps.isEmpty() &&
                            !operationInFlight && packetLink18.bufferedPacketSize() == 0 &&
                            packetLink18.state().toString() == "READY"
                        if (!idleRead) {
                            if (android.os.SystemClock.elapsedRealtime() - startMs > 18_000L) {
                                android.util.Log.w("AtomRemoteAudioB0135", "SOURCE_AUTO_GET_DEFERRED busy=true manualRefresh=true")
                                return
                            }
                            mainHandler.postDelayed(this, 90L)
                            return
                        }
                        val accepted = startAudioR26RecoveredCollector()
                        android.util.Log.i("AtomRemoteAudioB0135", "SOURCE_AUTO_GET_REQUEST accepted=$accepted target=$desired tx=false")
                        if (!accepted) return
                        val verifyFromMs = android.os.SystemClock.elapsedRealtime()
                        val verifier = object : Runnable {
                            override fun run() {
                                if (gatt !== session) return
                                val freshLive = _audioR26Live.value
                                val f = freshLive.values["AudioMetersPageGet"]
                                val fresh = freshLive.generation > generationAtTx && !freshLive.running &&
                                    f?.status == "TYPED_RX" && f.responseType == 0x8647
                                if (fresh) {
                                    val readback = f?.rawHex?.let {
                                        com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(it)
                                    }
                                    val observed = readback?.monitor?.toInt()
                                    val last = b0135SourcePending
                                    val recUnchanged = readback?.recordEnabledList == last?.enabledRecIds
                                    val match = observed == desired && recUnchanged
                                    if (last != null && last.session === session &&
                                        last.generationBeforeTx == generationAtTx) {
                                        b0135SourcePending = null
                                        b0135SourceFailedTarget = if (match) null else desired
                                    }
                                    android.util.Log.w("AtomRemoteAudioB0135", "SOURCE_POST_GET_RESULT " +
                                        "requested=$desired observed=$observed matched=$match " +
                                        "micLineMonitor=${if (observed == 0) "On" else "Off"} " +
                                        "physicalRecorderMustMatch=true recUnchanged=$recUnchanged")
                                    return
                                }
                                if (android.os.SystemClock.elapsedRealtime() - verifyFromMs > 17_000L) {
                                    android.util.Log.w("AtomRemoteAudioB0135", "SOURCE_POST_GET_TIMEOUT " +
                                        "target=$desired manualRefreshRequired=true noAutoSetRetry=true")
                                    return
                                }
                                mainHandler.postDelayed(this, 200L)
                            }
                        }
                        mainHandler.postDelayed(verifier, 200L)
                    }
                }
                mainHandler.postDelayed(autoRead, 180L)
            }
        }
        mainHandler.post(worker)
        return true  // Armed, not proof of delivered SET.
    }

    fun a12ExperimentalOneShotSet(key: String, desired: Int): Boolean {
        if (key == "orientation" || key == "embed")
            return b0130TryBinaryBackedOneShot(key, desired)
        if (key == "headphonePilotRearm") return a1277OperatorRearmHeadphonePilot(desired)
        if (key == "headphoneSource" || key == "headphoneMonitor") return b0135TryHeadphoneSourceSet(desired) // B01.35 source f1; REC separate
        if (key != "delay") {
            android.util.Log.w("AtomRemoteAudioA124", "SET_HOLD key=$key desired=$desired " +
                "reason=NO_VERIFIED_SAFE_WIRE_FOR_THIS_CONTROL tx=false")
            return false
        }
        if (desired !in 0..20) {
            android.util.Log.w("AtomRemoteAudioA124", "DELAY_SET_BLOCK target=$desired " +
                "reason=OUTSIDE_0_TO_20 tx=false")
            return false
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val currentGatt = gatt
        val fresh = a124DelayLastFrames != null &&
            a124DelayLastRxAtMs > 0L &&
            (now - a124DelayLastRxAtMs) in 0L..30_000L &&
            a124DelayLastRxGatt === currentGatt
        // A12.26.1: An old GET is not a failed SET attempt. Request an existing
        // typed GET in the armed window and keep the operator-selected target.
        if (currentGatt == null || !_tcsPairingState.value.acceptWritten ||
            !_tcsRemoteState.value.available || "delay" in a12PilotAttempted ||
            a125DelayWaiting || a124DelayPendingGet) {
            android.util.Log.w("AtomRemoteAudioA124", "DELAY_SET_BLOCK " +
                "freshCurrentGet=$fresh paired=${_tcsPairingState.value.acceptWritten} " +
                "available=${_tcsRemoteState.value.available} " +
                "attempted=${"delay" in a12PilotAttempted} " +
                "waiting=$a125DelayWaiting pendingReadback=$a124DelayPendingGet tx=false")
            return false
        }
        a125DelayWaiting = true
        a125DelayDeadlineMs = now + 30_000L
        var lastRefreshRequestAtMs = 0L
        val initialGatt = currentGatt
        val tick = object : Runnable {
            override fun run() {
                if (!a125DelayWaiting) return
                val tickMs = android.os.SystemClock.elapsedRealtime()
                if (gatt !== initialGatt || !_tcsPairingState.value.acceptWritten ||
                    !_tcsRemoteState.value.available) {
                    a125DelayWaiting = false
                    android.util.Log.w("AtomRemoteAudioA124", "DELAY_SET_ABORT session_changed tx=false")
                    return
                }
                if (tickMs >= a125DelayDeadlineMs) {
                    a125DelayWaiting = false
                    android.util.Log.w("AtomRemoteAudioA1261", "DELAY_SET_ABORT reason=refresh_or_idle_timeout " +
                        "attemptConsumed=false setTx=false target=$desired")
                    return
                }
                val getIsFresh = a124DelayLastFrames != null &&
                    a124DelayLastRxGatt === initialGatt &&
                    (tickMs - a124DelayLastRxAtMs) in 0L..30_000L
                if (!getIsFresh) {
                    if (tickMs - lastRefreshRequestAtMs >= 3_000L || lastRefreshRequestAtMs == 0L) {
                        // Calls the EXISTING read-only GET dispatch; no new GET parser.
                        val requested = r2672TryMicLineCandidateGet("delay")
                        lastRefreshRequestAtMs = tickMs
                        android.util.Log.i("AtomRemoteAudioA1261", "DELAY_PRE_SET_GET " +
                            "queued=$requested target=$desired consumed=false tx=false")
                    }
                    mainHandler.postDelayed(this, 150L)
                    return
                }
                val idle = !audioR26Live.value.running && !remoteBootstrapActive &&
                    !inputCollectorRunning && !inputExtendedRunning &&
                    pendingRemotePacket == null && pendingOps.isEmpty() &&
                    !operationInFlight && packetLink18.bufferedPacketSize() == 0 &&
                    r2706MicActive == null && r2706MicReads.isEmpty() &&
                    packetLink18.state().toString() == "READY"
                if (!idle) {
                    val why = buildString {
                        if (audioR26Live.value.running) append("audioCollector,")
                        if (remoteBootstrapActive) append("bootstrap,")
                        if (inputCollectorRunning || inputExtendedRunning) append("inputCollector,")
                        if (pendingRemotePacket != null) append("pendingRemote,")
                        if (pendingOps.isNotEmpty() || operationInFlight) append("attBusy,")
                        if (packetLink18.bufferedPacketSize() != 0) append("packetBuffer,")
                        if (r2706MicActive != null || r2706MicReads.isNotEmpty()) append("micGet,")
                        if (packetLink18.state().toString() != "READY") append("plink=${packetLink18.state()},")
                    }
                    android.util.Log.i("AtomRemoteAudioA126", "DELAY_WAIT_IDLE target=$desired " +
                        "elapsedMs=${tickMs - now} blockedBy=$why tx=false")
                    // A12.34: quicker idle re-check, never preempt active ATT/PacketLink.
                    mainHandler.postDelayed(this, 100L)
                    return
                }
                android.util.Log.i("AtomRemoteAudioA126", "DELAY_IDLE_PASS target=$desired " +
                    "elapsedMs=${tickMs - now} ready=true")
                a125DelayWaiting = false
                // Attempt is consumed ONLY after PacketLink accepts the SET.
                val proto = com.atomosremote.nativeapp.audio.AudioA124DelayWire.framesPayload(desired)
                android.util.Log.w("AtomRemoteAudioA124", "DELAY_SET_ONE_SHOT " +
                    "cmd=0x06BA proto=${proto.toHex()} baseline=${a124DelayLastFrames} desired=$desired " +
                    "operatorAction=true staticProtoField1=true " +
                    "ag2SetSuccessUnverified=true")
                // Reuse the proven Gain pilot's isolated PacketLink queue path,
                // with cleanup if the queue refuses. No scheduler changes.
                val pilotCmd = com.atomosremote.nativeapp.audio.AudioA124DelayWire.COMMAND
                val body = buildOfficialAtomMessageBodyWithProtoPayload(0, pilotCmd, proto)
                val packet = PendingRemotePacket(body, "a125-one-shot-delay-$desired",
                    "A125 DELAY 0 TO $desired", pilotCmd, 0)
                pendingRemotePacket = packet
                pendingRemoteFrameWritten = false
                val queued = queueRemotePacket18_8(packet, local = true)
                if (!queued && pendingRemotePacket === packet) {
                    pendingRemotePacket = null
                    pendingRemoteFrameWritten = false
                }
                android.util.Log.w("AtomRemoteAudioA124", "DELAY_SET_QUEUE accepted=$queued " +
                    "physicalTxNotEstablished=true recorderApplied=UNKNOWN noSetRetry=true")
                android.util.Log.i("AtomRemoteAudioA126", "DELAY_PACKET_TRACE " +
                    "cmd=0x06BA target=$desired body=${body.toHex()} " +
                    "queueAccepted=$queued local=true packetId=0")
                if (queued) {
                    a12PilotAttempted.add("delay")
                    android.util.Log.w("AtomRemoteAudioA1261", "DELAY_ATTEMPT_COMMITTED " +
                        "target=$desired queueAccepted=true attemptConsumed=true")
                    a125DelayTargetFrames = desired
                    a124DelayPendingGet = true
                    a124DelayPendingGatt = initialGatt
                    a124DelaySetQueuedAtMs = tickMs
                    // A12.28: Extend the GET-only verification window to 65s.
                    // GET queue can be delayed by Gain and Input Level refreshes.
                    // NEVER resend SET automatically; a fresh matching 0x86B3
                    // remains the sole verified-state signal.
                    listOf(250L, 900L, 1_800L, 3_000L, 4_500L, 7_000L, 11_000L, 16_000L, 23_000L, 35_000L, 49_000L).forEachIndexed { index, delayMs ->
                        mainHandler.postDelayed({
                            if (gatt === initialGatt && _tcsPairingState.value.acceptWritten &&
                                a124DelayPendingGet) {
                                val requested = r2672TryMicLineCandidateGet("delay")
                                android.util.Log.i("AtomRemoteAudioA126", "DELAY_POST_SET_GET_REQUEST " +
                                    "attempt=${index + 1} queued=$requested expectedResponse=0x86B3 " +
                                    "noSetRetry=true")
                            }
                        }, delayMs)
                    }
                    mainHandler.postDelayed({
                        if (a124DelayPendingGet && a124DelayPendingGatt === initialGatt) {
                            android.util.Log.w("AtomRemoteAudioA124", "DELAY_SET_UNCONFIRMED " +
                                "reason=no_fresh_matching_get_after_65s queuedOnly=true " +
                                "recorderMayHaveApplied=true requestManualGet=true")
                            a124DelayPendingGet = false
                            // Permit a NEW operator action after timeout. Never resend
                            // the previous SET automatically, and always require a
                            // fresh typed GET before the next SET can dispatch.
                            a12PilotAttempted.remove("delay")
                            android.util.Log.w("AtomRemoteAudioA127", "DELAY_SET_TIMEOUT " +
                                "target=$desired nextManualSetEnabled=true " +
                                "recorderApplied=UNKNOWN noAutoRetry=true")
                        }
                    }, 65_000L)
                } else {
                    android.util.Log.w("AtomRemoteAudioA1261", "DELAY_QUEUE_REJECTED " +
                        "target=$desired attemptConsumed=false setTx=false")
                }
            }
        }
        mainHandler.post(tick)
        android.util.Log.w("AtomRemoteAudioA124", "DELAY_IDLE_WINDOW_ARMED " +
            "baselineFresh=$fresh target=$desired maxMs=30000 noAutoSet=true")
        android.util.Log.i("AtomRemoteAudioA126", "DELAY_SET_ARMED " +
            "target=$desired freshGet=$fresh baseline=${a124DelayLastFrames} " +
            "plink=${packetLink18.state()} pendingOps=${pendingOps.size} " +
            "audioRunning=${audioR26Live.value.running} tx=false")
        return true // armed only, not a verified hardware change
    }

    // AUDIO R26: read-only AppPolling.AudioMetersPageGet, source-mapped 0x0647.
    // This is NOT an AG2-confirmed field map. No Audio SET is enabled here.
    // Existing PacketLink serializer and raw state page capture remain unchanged.
    @SuppressLint("MissingPermission")
    fun probeR26AudioMetersPage(): Boolean {
        val ready = _tcsPairingState.value.acceptWritten && _tcsRemoteState.value.available
        if (!ready) {
            android.util.Log.i("AtomRemoteAudioR26", "METERS_GET_SKIPPED not-linked")
            return false
        }
        android.util.Log.i("AtomRemoteAudioR26", "QUEUE_BEFORE route=LOCAL pending=${pendingRemotePacket?.purpose ?: "none"} " +
            "frameWritten=$pendingRemoteFrameWritten plinkState=${packetLink18.state()} " +
            "bufferedBytes=${packetLink18.bufferedPacketSize()}")
        // AG2 declared 39 Audio protobuf bytes on 2026-10-11, but a default
        // MTU=23 yielded just 16. Existing OutputR21 safely negotiates MTU=185
        // AFTER accept, via the serialized ATT queue. Reuse it: no new GATT
        // primitive, no pairing changes, no Output setting action.
        if (outputR21MtuBytes < 61) {
            r26AudioGetWaitingForMtu = requestR21OutputPageMtu()
            android.util.Log.i("AtomRemoteAudioR26Fix",
                "AUDIO_MTU_PREPARE current=$outputR21MtuBytes desired=185 " +
                    "queued=$r26AudioGetWaitingForMtu GET_NOT_SENT")
            return false
        }
        val accepted = queueOfficialLutGetter(
            commandId = REMOTE_APP_POLLING_AUDIO_METERS_PAGE_GET,
            expectedResponseId = REMOTE_APP_POLLING_AUDIO_METERS_PAGE_GET,
            proto = byteArrayOf(),
            purpose = "remote-audio-r26-meters-page-get",
            label = "AudioMetersPageGet R26 read-only"
        )
        android.util.Log.i("AtomRemoteAudioR26", "METERS_GET_REQUEST id=0x0647 payload=EMPTY local=existing-GET-route queued=$accepted")
        return accepted
    }

    /**
     * AUDIO PART2 R26.06-11: explicit one-shot READ-ONLY AppPolling
     * non-local A/B probe. R26.01 used local=true/packetId=0 but the
     * user's 2026-10-11 capture proved ONLY queuing, NOT a typed reply.
     * The non-local route preserves existing 1..255 command sequencing;
     * it never changes the global serializer or dispatch logic. This is
     * manually triggered, not periodic and never sends an Audio SET.
     */
    @SuppressLint("MissingPermission")
    fun probeR26AudioMetersPageNonlocalOnce(): Boolean {
        val ready = _tcsPairingState.value.acceptWritten && _tcsRemoteState.value.available
        if (!ready) {
            android.util.Log.i("AtomRemoteAudioR26P2", "NONLOCAL_SKIP disconnected")
            return false
        }
        // Unlike remote-audio-r26-meters-page-get, this PURPOSE is NOT in
        // queueOfficialLutGetter's local=true allowlist; therefore local=false
        // is selected by existing exact code. No other route is changed.
        android.util.Log.i("AtomRemoteAudioR26P2", "QUEUE_BEFORE route=NONLOCAL pending=${pendingRemotePacket?.purpose ?: "none"} " +
            "frameWritten=$pendingRemoteFrameWritten plinkState=${packetLink18.state()} " +
            "bufferedBytes=${packetLink18.bufferedPacketSize()}")
        val accepted = queueOfficialLutGetter(
            commandId = REMOTE_APP_POLLING_AUDIO_METERS_PAGE_GET,
            expectedResponseId = REMOTE_APP_POLLING_AUDIO_METERS_PAGE_GET,
            proto = byteArrayOf(),
            purpose = "remote-audio-r26-part2-nonlocal-read-only",
            label = "AudioMetersPageGet R26 nonlocal READ ONLY"
        )
        android.util.Log.i("AtomRemoteAudioR26P2",
            "NONLOCAL_0647_REQUEST queued=$accepted local=false command=0x0647 " +
                "expected=0x0647 payloadBytes=0 write=false RESULT_NOT_YET_PROVEN")
        return accepted
    }

    // OUTPUT R19: one official AppPolling.OutputPageGet request (0x0644).
    // This is a documented GET class and Android-sourced opcode, but its AG2
    // response is NOT verified. Non-local 1..255 packetId follows existing
    // AppPolling convention; no state-changing message is sent here.
    @SuppressLint("MissingPermission")
    fun probeR19OutputPageGet(): Boolean {
        val accepted = queueOfficialLutGetter(
            commandId = REMOTE_APP_POLLING_OUTPUT_PAGE_GET,
            expectedResponseId = REMOTE_APP_POLLING_OUTPUT_PAGE_GET,
            proto = byteArrayOf(),
            purpose = "remote-output-r19-output-page-get",
            label = "OutputPageGet R19 read-only"
        )
        android.util.Log.i("AtomRemoteOutputR19",
            "PAGE_GET_REQUEST cmd=0x0644 expected=0x0644 local=false " +
                "payload=EMPTY queued=$accepted evidence=official-macos-class+Android-constant")
        return accepted
    }

    /**
     * OUTPUT R21: one guarded post-accept MTU negotiation per BLE connection.
     * R20 observed 0x8644 declaring a 54-byte payload while a PacketLink
     * final read exposed only 16 body bytes. Since final=1, reassembling
     * unrelated E0 turns would corrupt the protocol. MTU must be observed.
     * This method issues NO recorder RemoteApi command and NO pairing write.
     */
    @SuppressLint("MissingPermission")
    fun requestR21OutputPageMtu(): Boolean {
        val live = gatt ?: return false
        // R22: queue eligibility is connection-scoped. R21 incorrectly required
        // a momentary READY/empty-remote-packet state which background polling
        // practically never exposed. The existing ATT queue serializes MTU
        // exchange with physical reads/writes; pairing must already be complete.
        val pairingReady = _tcsPairingState.value.acceptWritten
        val linked = _tcsRemoteState.value.available && packetLink18Primed
        if (!pairingReady || !linked) {
            android.util.Log.i("AtomRemoteOutputR22",
                "MTU_DEFERRED paired=$pairingReady linked=$linked state=${packetLink18.state()}")
            return false
        }
        if (outputR21MtuBytes >= 61) {
            android.util.Log.i("AtomRemoteOutputR22",
                "MTU_ALREADY_SUFFICIENT negotiated=$outputR21MtuBytes")
            return true
        }
        if (outputR21MtuRequested) return true
        outputR21MtuRequested = true
        outputR21MtuResult = "queued"
        // Do not touch PacketLink framing. The single existing ATT owner will
        // run this after any in-flight operation callback, never concurrently.
        pendingOps.addFirst(PendingOp.OutputR21Mtu())
        android.util.Log.i("AtomRemoteOutputR22",
            "MTU_QUEUED requested=185 paired=$pairingReady linked=$linked " +
                "plinkState=${packetLink18.state()} pendingRemote=${pendingRemotePacket != null} " +
                "bootstrap=$remoteBootstrapActive inFlight=$operationInFlight " +
                "pendingAttOps=${pendingOps.size}")
        if (!operationInFlight) processNext(live)
        return true
    }

    // OUTPUT R20: read-only A/B probe for official OutputPageGet 0x0644.
    // Used only when the existing non-local R19 probe returned no typed page.
    // Exact GET opcode unchanged; no SET and no unrelated AppPolling changes.
    @SuppressLint("MissingPermission")
    fun probeR20OutputPageGetLocal(): Boolean {
        val accepted = queueOfficialLutGetter(
            commandId = REMOTE_APP_POLLING_OUTPUT_PAGE_GET,
            expectedResponseId = REMOTE_APP_POLLING_OUTPUT_PAGE_GET,
            proto = byteArrayOf(),
            purpose = "remote-output-r20-output-page-local-get",
            label = "OutputPageGet R20 local read-only"
        )
        android.util.Log.i("AtomRemoteOutputR20",
            "PAGE_LOCAL_GET cmd=0x0644 local=true empty=true accepted=$accepted")
        return accepted
    }

    // OUTPUT R17: macOS Output.Get constructor disassembly correlates its
    // immediates (D3/D5/D7) with the proven GET = immediate-1, RX = immediate-2
    // pattern (DE/DD/DC, E0/DF/DE, E2/E1/E0). These wire IDs are CANDIDATES
    // and are intentionally NOT trusted as hardware-proven until typed RX.
    // Only empty-payload, local=true GET calls are permitted. NO SET, NO lists.
    private var outputR17ProbeKind: String? = null
    private var outputR17ProbeExpiresAt: Long = 0L
    private var outputR17ProbeExpectedResponse: Int = -1

    @SuppressLint("MissingPermission")
    fun probeR17OutputOptionGet(kind: String): Boolean {
        val spec = when (kind) {
            "downscale" -> Pair(0x00D2, 0x00D1)
            "dci-crop" -> Pair(0x00D4, 0x00D3)
            "hdmi-timecode" -> Pair(0x00D6, 0x00D5)
            else -> return false
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (outputR17ProbeKind != null && now < outputR17ProbeExpiresAt) {
            android.util.Log.i("AtomRemoteOutputR17", "PROBE_BUSY kind=$kind active=$outputR17ProbeKind")
            return false
        }
        val purpose = "remote-output-r17-get-$kind"
        val queued = queueOfficialLutGetter(
            commandId = spec.first,
            expectedResponseId = spec.second,
            proto = byteArrayOf(),
            purpose = purpose,
            label = "R17 Output $kind read-only candidate"
        )
        if (queued) {
            outputR17ProbeKind = kind
            outputR17ProbeExpiresAt = android.os.SystemClock.elapsedRealtime() + 5000L
            outputR17ProbeExpectedResponse = spec.second
        }
        android.util.Log.i("AtomRemoteOutputR17",
            "PROBE_TX kind=$kind cmd=0x${spec.first.toString(16)} " +
                "expected=0x${spec.second.toString(16)} local=true payload=EMPTY " +
                "queued=$queued evidence=macOS-Get-class-immediate-pattern")
        return queued
    }

    // OUTPUT R13: Two *candidate*, read-only, local=true Output GET probes.
    // Command IDs are derived from the adjacent macOS RemoteApi.Output class
    // ordering anchored by hardware-verified 0x00DD and 0x00E3. These two IDs
    // ARE NOT hardware-proven until a correlated AG2 response is observed.
    // Absolutely no SET or speculative option-list requests originate here.
    private var outputR13ProbeKind: String? = null
    private var outputR13ProbeExpiresAt: Long = 0L
    private var outputR13ProbeExpectedResponse: Int = -1

    @SuppressLint("MissingPermission")
    fun probeR13OutputScalarGet(kind: String): Boolean {
        val spec = when (kind) {
            "hdr-type" -> Pair(0x00DF, 0x00DE)
            "gamut" -> Pair(0x00E1, 0x00E0)
            else -> return false
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (outputR13ProbeKind != null && now < outputR13ProbeExpiresAt) {
            android.util.Log.i("AtomRemoteOutputR13", "GET_BUSY kind=$kind active=$outputR13ProbeKind")
            return false
        }
        // Only one probe is outstanding, and only the exact expected type can
        // become a UI value. No opportunistic parser guesses or default enums.
        val purpose = "remote-output-r13-candidate-$kind"
        val queued = queueOfficialLutGetter(
            commandId = spec.first,
            expectedResponseId = spec.second,
            proto = byteArrayOf(),
            purpose = purpose,
            label = "R13 Output $kind GET candidate"
        )
        if (queued) {
            outputR13ProbeKind = kind
            outputR13ProbeExpiresAt = android.os.SystemClock.elapsedRealtime() + 4500L
            outputR13ProbeExpectedResponse = spec.second
        }
        android.util.Log.i(
            "AtomRemoteOutputR13",
            "GET_CANDIDATE kind=$kind cmd=0x${spec.first.toString(16)} " +
                "expected=0x${spec.second.toString(16)} local=true packetId=0 queued=$queued " +
                "status=${if (queued) "awaiting-hardware-proof" else "transport-busy"}"
        )
        return queued
    }

    @SuppressLint("MissingPermission")
    fun refreshOfficialInputOutputSummary(): Boolean {
        if (!canQueueOfficialMonitorCommand("input-output-summary")) return false
        val current = gatt ?: return false
        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return false
        service.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false

        // 19.12.10: AppPolling remains optional diagnostic only; macOS evidence uses non-local RemoteApi envelope. Granular GETs are the AG2 data path.
        val packetId = 0
        val body = buildOfficialAtomMessageBody(packetId, REMOTE_APP_POLLING_INPUT_OUTPUT_SUMMARY_GET)
        val packet = PendingRemotePacket(
            body = body,
            purpose = "remote-input-output-summary-get",
            label = "Input / Output Summary",
            commandId = REMOTE_APP_POLLING_INPUT_OUTPUT_SUMMARY_GET,
            packetId = packetId,
            expectedResponseId = REMOTE_APP_POLLING_INPUT_OUTPUT_SUMMARY_GET
        )
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = if (tcsPacketRemoteCredit) "sending" else "requesting-credit",
            available = true,
            lastCommand = packet.label,
            lastCommandId = packet.commandId,
            lastTxHex = null,
            lastStatus = "Refreshing recorder Input / Format summary",
            lastError = null
        )
        appendEvent("TCS19.12.10 APPPOLLING OPTIONAL INPUT SUMMARY cmd=0x0648 packetId=$packetId body=${body.toHex()} m_isLocal=0")
        return queueRemotePacket18_8(packet, local = false)
    }

    @SuppressLint("MissingPermission")
    private fun canQueueOfficialMonitorCommand(control: String): Boolean {
        if (!_tcsPairingState.value.acceptWritten) {
            appendEvent("TCS19.4.2 MONITOR blocked before accepted pairing control=$control")
            return false
        }
        if (!_tcsRemoteState.value.available && !openOfficialTcsRemoteLink(autoProbe = true)) {
            appendEvent("TCS19.4.2 MONITOR blocked: PacketLink unavailable control=$control")
            return false
        }
        if (!packetLink18Primed && !packetLink18.peerCredit()) {
            appendEvent("TCS19.4.2 MONITOR blocked: PacketLink not primed control=$control")
            return false
        }
        if (pendingRemotePacket != null) {
            releaseStaleMonitorPanelPendingIfReady("monitor:$control")
        }
        if (pendingRemotePacket != null) {
            appendEvent("TCS19.4.2 MONITOR busy: previous RemoteApi command is still active")
            return false
        }
        return true
    }

    /**
     * 20.06.0-20.06.2 recorder-backed Scopes core.
     *
     * Latest uploaded macOS Contents confirms:
     *   WaveformEnableSet/Get
     *   WaveformModeSet/Get
     *   WaveformSizeSet/Get
     *   WaveformOpacitySet/Get
     *   WaveformBrightnessSet/Get
     *
     * Contiguous RemoteApi matrix:
     *   Enable  Set 0x0258, Get 0x0259 -> response 0x0258
     *   Mode    Set 0x025A, Get 0x025B -> response 0x0259
     *   Size    Set 0x025C, Get 0x025D -> response 0x025A
     *   Opacity Set 0x025E, Get 0x025F -> response 0x025B
     *   Bright. Set 0x0260, Get 0x0261 -> response 0x025C
     *
     * This batch wires only Enable / Mode / Size. Opacity/Brightness remain
     * for the next scope batch.
     */
    @SuppressLint("MissingPermission")
    fun refreshOfficialScopeCore(): Boolean {
        if (!canQueueOfficialMonitorCommand("scope-refresh")) return false
        scopeRefreshStage = 1
        _monitorScopeState.value = _monitorScopeState.value.copy(
            status = "Reading scope state from recorder"
        )
        return queueOfficialScopeGetter(
            commandId = REMOTE_MONITOR_WAVEFORM_ENABLE_GET,
            expectedResponse = REMOTE_MONITOR_WAVEFORM_ENABLE_RESPONSE,
            purpose = "remote-scope-enable-get",
            label = "Waveform"
        )
    }

    @SuppressLint("MissingPermission")
    private fun queueOfficialScopeGetter(
        commandId: Int,
        expectedResponse: Int,
        purpose: String,
        label: String
    ): Boolean {
        val current = gatt ?: return false
        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return false
        service.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false
        if (pendingRemotePacket != null) return false

        val packetId = 0
        val body = buildOfficialAtomMessageBody(packetId, commandId)
        val packet = PendingRemotePacket(
            body = body,
            purpose = purpose,
            label = label,
            commandId = commandId,
            packetId = packetId,
            expectedResponseId = expectedResponse
        )
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        appendEvent(
            "TCS20.06 SCOPE GET label=$label cmd=0x${commandId.toString(16)} " +
                "expected=0x${expectedResponse.toString(16)} packetId=0 m_isLocal=1"
        )
        return queueRemotePacket18_8(packet, local = true)
    }

    /**
     * Phase 04 Scopes commands recovered from the supplied macOS ATOMOS
     * Remote executable. These setters use the same local PacketLink RemoteApi
     * envelope as Phase 03 and do not alter the proven transport commands.
     *
     * macOS evidence:
     *  WaveformEnableSet = 0x0258
     *  WaveformModeSet   = 0x025A
     *      0 = Luma Parade, 1 = RGB Parade, 2 = Vectorscope
     *  WaveformSizeSet   = 0x025C
     *      0 = Quarter, 1 = Third, 2 = Full
     *
     * Opacity and brightness setters also exist in macOS, but Phase 04 leaves
     * them unwired until their exact allowed numeric ranges are evidenced.
     */
    @SuppressLint("MissingPermission")
    fun sendOfficialScopeEnabled(enabled: Boolean): Boolean {
        if (!canQueueOfficialScopeCommand("enabled")) return false
        scopeExpectedEnabled = enabled
        _monitorScopeState.value = _monitorScopeState.value.copy(
            pendingKey = "enabled",
            status = "Applying Waveform ${if (enabled) "On" else "Off"}; waiting for recorder readback"
        )
        return enqueueOfficialRemoteCommandProtoBool1(
            commandId = REMOTE_MONITOR_WAVEFORM_ENABLE_SET,
            enabled = enabled,
            purpose = "remote-scope-enable",
            label = "Scopes ${if (enabled) "On" else "Off"}"
        )
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialScopeMode(mode: String): Boolean {
        if (!canQueueOfficialScopeCommand("mode:$mode")) return false
        val value = when (mode) {
            "luma-parade" -> 1
            "rgb-parade" -> 2
            "vectorscope" -> 3
            "vectorscope-zoom" -> 4
            else -> {
                appendEvent("TCS19.4 SCOPES unsupported mode=$mode")
                return false
            }
        }
        val label = when (mode) {
            "luma-parade" -> "Luma Parade"
            "rgb-parade" -> "RGB Parade"
            "vectorscope" -> "Vectorscope"
            else -> "Vectorscope Zoom"
        }
        scopeExpectedMode = value
        _monitorScopeState.value = _monitorScopeState.value.copy(
            pendingKey = "mode",
            status = "Applying $label; waiting for recorder readback"
        )
        return enqueueOfficialRemoteCommandProtoExplicitVarint1(
            commandId = REMOTE_MONITOR_WAVEFORM_MODE_SET,
            value = value,
            purpose = "remote-scope-mode",
            label = "Scopes Mode: $label"
        )
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialScopeSize(size: String): Boolean {
        if (!canQueueOfficialScopeCommand("size:$size")) return false
        val value = when (size) {
            "quarter" -> 1
            "third" -> 2
            "full" -> 3
            else -> {
                appendEvent("TCS19.4 SCOPES unsupported size=$size")
                return false
            }
        }
        val label = when (size) {
            "quarter" -> "Quarter"
            "third" -> "Third"
            else -> "Full"
        }
        scopeExpectedSize = value
        _monitorScopeState.value = _monitorScopeState.value.copy(
            pendingKey = "size",
            status = "Applying scope size $label; waiting for recorder readback"
        )
        return enqueueOfficialRemoteCommandProtoExplicitVarint1(
            commandId = REMOTE_MONITOR_WAVEFORM_SIZE_SET,
            value = value,
            purpose = "remote-scope-size",
            label = "Scopes Size: $label"
        )
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialScopeOpacity(value: Int): Boolean {
        if (value !in 0..100) return false
        if (!canQueueOfficialScopeCommand("opacity:$value")) return false
        scopeExpectedOpacity = value
        _monitorScopeState.value = _monitorScopeState.value.copy(
            pendingKey = "opacity",
            status = "Applying Waveform opacity $value%; waiting for recorder readback"
        )
        return enqueueOfficialRemoteCommandProtoExplicitVarint1(
            commandId = REMOTE_MONITOR_WAVEFORM_OPACITY_SET,
            value = value,
            purpose = "remote-scope-opacity",
            label = "Scope Opacity $value"
        )
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialScopeBrightness(value: Int): Boolean {
        if (value !in 0..100) return false
        if (!canQueueOfficialScopeCommand("brightness:$value")) return false
        scopeExpectedBrightness = value
        _monitorScopeState.value = _monitorScopeState.value.copy(
            pendingKey = "brightness",
            status = "Applying Waveform brightness $value%; waiting for recorder readback"
        )
        return enqueueOfficialRemoteCommandProtoExplicitVarint1(
            commandId = REMOTE_MONITOR_WAVEFORM_BRIGHTNESS_SET,
            value = value,
            purpose = "remote-scope-brightness",
            label = "Scope Brightness $value"
        )
    }

    /**
     * 20.07.0-20.07.2 recorder-backed Guides core.
     *
     * Latest macOS Contents exposes:
     * FrameGuideFormatGet/Set, FrameGuideGet/Set and the FrameGuideSettings
     * controller.  The controller has the preset buttons:
     * 16:9, 4:3, 1.85:1, 1.9:1, 2.35:1, 2.41:1, 1.91:1,
     * 1:1, 4:5, 9:16 and Custom.
     *
     * Reverse controller evidence maps the preset enum values used by
     * FrameGuideFormatSet:
     * 1=16:9, 2=1.91:1, 3=2.41:1, 4=1.9:1, 5=1.85:1,
     * 6=4:3, 7=2.35:1, 8=1:1, 9=4:5, 10=9:16, 12=Custom.
     */
    @SuppressLint("MissingPermission")
    fun refreshOfficialGuidesState(): Boolean {
        if (!canQueueOfficialMonitorCommand("guides-refresh")) return false
        val current = gatt ?: return false
        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return false
        service.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false
        if (pendingRemotePacket != null) return false

        val packetId = 0
        val body = buildOfficialAtomMessageBody(packetId, REMOTE_MONITOR_FRAME_GUIDE_FORMAT_GET)
        val packet = PendingRemotePacket(
            body = body,
            purpose = "remote-guide-format-get",
            label = "Frame Guide Format",
            commandId = REMOTE_MONITOR_FRAME_GUIDE_FORMAT_GET,
            packetId = packetId,
            expectedResponseId = REMOTE_MONITOR_FRAME_GUIDE_FORMAT_RESPONSE
        )
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        _monitorGuideState.value = _monitorGuideState.value.copy(
            pendingKey = "refresh",
            status = "Reading Frame Guide / 9-Grid state"
        )
        appendEvent("TCS20.13.2 GUIDE FORMAT GET cmd=0x0276 expected=0x0267 packetId=0 m_isLocal=1")
        val queued = queueRemotePacket18_8(packet, local = true)
        if (queued) scheduleMonitorAssistSummaryFallback("guides-refresh", 360L)
        return queued
    }

    /**
     * 20.13.2 R14 direct macOS main-UI Frame Guide parity.
     *
     * Two different RemoteApi families are used:
     *   FrameGuideSet 0x044E / FrameGuideGet 0x044F -> response 0x0451
     *       Controls only the main Frame Guide ON/OFF state.
     *   FrameGuideFormatSet 0x0275 / Get 0x0276 -> response 0x0267
     *       Controls the selected format and 3x3 grid tuple.
     *
     * The format GET hardware payload repeatedly observed on AG2 is:
     *   08 01 10 00 18 00 20 0D
     * which maps to:
     *   field #1 format
     *   field #2 3x3 grid
     *   field #3 guide mode
     *   field #4 custom ratio
     *
     * Direct main-UI disassembly proves Frame Guide activation is encoded by
     * the format argument itself (0 = off, non-zero = on) and the constructor is
     * called as FrameGuideFormatSet(format, grid, 2, 13). The separate 0x044E
     * class exists in the binary but is not the Shogun main MonitorAssist route.
     */
    private fun buildOfficialFrameGuideFormatProto(
        format: Int,
        grid: Boolean,
        mode: Int = 0,
        customRatio: Int = 13
    ): ByteArray = ByteArrayOutputStream().apply {
        write(0x08); writeUnsignedVarint(format.coerceAtLeast(0))
        write(0x10); writeUnsignedVarint(if (grid) 1 else 0)
        write(0x18); writeUnsignedVarint(mode.coerceAtLeast(0))
        write(0x20); writeUnsignedVarint(customRatio.coerceAtLeast(0))
    }.toByteArray()

    private fun buildMacMainUiFrameGuideProto(format: Int, grid: Boolean): ByteArray =
        ByteArrayOutputStream().apply {
            if (format != 0) {
                write(0x08)
                writeUnsignedVarint(format.coerceAtLeast(0))
            }
            if (grid) {
                write(0x10)
                writeUnsignedVarint(1)
            }
        }.toByteArray()

    /**
     * R16.2 bounded recorder readback pump for Frame Guide mutations. The 0x0275
     * setter is already hardware-proven on AG2. This pump only smooths UI state:
     * it retries 0x0276 while the target has not yet been observed and never
     * touches the frozen 9-Grid sender.
     */
    private fun scheduleFrameGuideFormatReadback(targetFormat: Int, generation: Int, attempt: Int = 0) {
        val delays = longArrayOf(110L, 260L, 520L, 900L)
        if (attempt >= delays.size) {
            if (frameGuideReadbackGeneration == generation && frameGuideExpectedFormat == targetFormat) {
                _monitorGuideState.value = _monitorGuideState.value.copy(
                    pendingKey = null,
                    status = "Frame Guide command applied; recorder readback is delayed. Tap SYNC to refresh."
                )
                appendEvent("TCS20.15R16.2 FRAME GUIDE READBACK GIVEUP target=$targetFormat gen=$generation")
            }
            return
        }
        mainHandler.postDelayed({
            if (frameGuideReadbackGeneration != generation || frameGuideExpectedFormat != targetFormat) return@postDelayed
            val actual = _monitorGuideState.value.format
            if (actual == targetFormat && _monitorGuideState.value.pendingKey == null) return@postDelayed
            if (pendingRemotePacket == null) {
                appendEvent("TCS20.15R16.2 FRAME GUIDE READBACK TRY attempt=${attempt + 1} target=$targetFormat gen=$generation")
                if (!refreshOfficialGuidesState()) {
                    scheduleFrameGuideFormatReadback(targetFormat, generation, attempt + 1)
                } else {
                    // Keep a bounded follow-up armed. A matching recorder reply
                    // clears frameGuideExpectedFormat, making it a no-op.
                    scheduleFrameGuideFormatReadback(targetFormat, generation, attempt + 1)
                }
            } else {
                appendEvent("TCS20.15R16.2 FRAME GUIDE READBACK BUSY attempt=${attempt + 1} target=$targetFormat")
                scheduleFrameGuideFormatReadback(targetFormat, generation, attempt + 1)
            }
        }, delays[attempt])
    }

    @SuppressLint("MissingPermission")
    private fun queueOfficialFrameGuideEnabledGet(reason: String): Boolean {
        if (!canQueueOfficialMonitorCommand("frame-guide-get:$reason")) return false
        val current = gatt ?: return false
        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return false
        service.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false
        val packetId = 0
        val body = buildOfficialAtomMessageBody(packetId, REMOTE_MONITOR_FRAME_GUIDE_GET)
        val packet = PendingRemotePacket(
            body = body,
            purpose = "remote-guide-frame-get:$reason",
            label = "Frame Guide State",
            commandId = REMOTE_MONITOR_FRAME_GUIDE_GET,
            packetId = packetId,
            expectedResponseId = REMOTE_MONITOR_FRAME_GUIDE_SET
        )
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        appendEvent(
            "TCS20.13.2R15 FRAME GUIDE GET cmd=0x44F expected=0x44E reason=$reason packetId=0 m_isLocal=0"
        )
        return queueRemotePacket18_8(packet, local = false)
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialFrameGuideFormat(format: Int): Boolean {
        if (format !in listOf(1,2,3,4,5,6,7,8,9,10,12)) return false
        if (!canQueueOfficialMonitorCommand("frame-guide-format")) return false
        val current = _monitorGuideState.value
        val grid = current.gridEnabled == true

        // R16.1: exact main-UI constructor parity. Do NOT write the Optional
        // sentinel values 2/13 as protobuf fields.
        val proto = buildMacMainUiFrameGuideProto(format, grid)
        frameGuideExpectedFormat = format
        frameGuideReadbackGeneration += 1
        _monitorGuideState.value = current.copy(
            format = format,
            pendingKey = "format",
            status = "Applying ${current.formatLabel(format)}; syncing recorder state"
        )
        appendEvent(
            "TCS20.15R16.1 GUIDE FORMAT SET cmd=0x275 format=$format grid=${if (grid) 1 else 0} " +
                "optionalEnable=nil optionalMode=nil proto=${proto.toHex()} m_isLocal=1"
        )
        return enqueueOfficialRemoteCommandProtoPayload(
            commandId = REMOTE_MONITOR_FRAME_GUIDE_FORMAT_SET,
            proto = proto,
            purpose = "remote-guide-format-set:$format",
            label = "Frame Guide ${current.formatLabel(format)}",
            local = true
        )
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialFrameGuideEnabled(enabled: Boolean): Boolean {
        if (!canQueueOfficialMonitorCommand("frame-guide")) return false
        val current = _monitorGuideState.value
        val grid = current.gridEnabled == true
        val activeFormat = current.format
            ?.takeIf { it in listOf(1,2,3,4,5,6,7,8,9,10,12) }
            ?: 1
        val targetFormat = if (enabled) activeFormat else 0

        // Fresh Contents.zip main Monitor UI does not use FrameGuideSet 0x044E
        // for this action. It dispatches FrameGuideFormatSet 0x0275. The
        // constructor's 2/13 arguments are Optional nil sentinels, so only
        // format and current grid state are serialized here.
        val proto = buildMacMainUiFrameGuideProto(targetFormat, grid)
        frameGuideExpectedFormat = targetFormat
        frameGuideReadbackGeneration += 1
        _monitorGuideState.value = current.copy(
            pendingKey = "frame-guide",
            status = if (enabled) {
                "Turning Frame Guide on with ${current.formatLabel(activeFormat)}; syncing recorder state"
            } else {
                "Turning Frame Guide off; syncing recorder state"
            }
        )
        appendEvent(
            "TCS20.15R16.1 FRAME GUIDE MAIN SET enabled=${if (enabled) 1 else 0} " +
                "cmd=0x275 targetFormat=$targetFormat grid=${if (grid) 1 else 0} " +
                "optionalEnable=nil optionalMode=nil proto=${proto.toHex()} m_isLocal=1"
        )
        return enqueueOfficialRemoteCommandProtoPayload(
            commandId = REMOTE_MONITOR_FRAME_GUIDE_FORMAT_SET,
            proto = proto,
            purpose = "remote-guide-frame-format-set:${if (enabled) 1 else 0}:$targetFormat",
            label = "Frame Guide ${if (enabled) "On" else "Off"}",
            local = true
        )
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialFrameGridEnabled(enabled: Boolean): Boolean {
        // GRID IS FROZEN FROM R12. Preserve its proven wire tuple:
        // field1=format, field2=grid target, field3=0, field4=13.
        if (!canQueueOfficialMonitorCommand("frame-grid")) return false
        val state = _monitorGuideState.value
        val selectedFormat = state.format
        if (selectedFormat == null) {
            _monitorGuideState.value = state.copy(
                pendingKey = "grid",
                status = "Reading current Frame Guide state before 9-Grid"
            )
            return queueOfficialFrameGuideFormatGetForGrid(enabled)
        }

        val preserveFormat = selectedFormat.takeIf { it in listOf(1,2,3,4,5,6,7,8,9,10,12) } ?: 1
        val proto = buildOfficialFrameGuideFormatProto(
            format = preserveFormat,
            grid = enabled,
            mode = 0,
            customRatio = 13
        )
        _monitorGuideState.value = state.copy(
            gridEnabled = enabled,
            pendingKey = "grid",
            status = "Applying 9-Grid ${if (enabled) "On" else "Off"}..."
        )
        appendEvent(
            "TCS20.13.3R13 GRID FROZEN SET target=${if (enabled) 1 else 0} format=$preserveFormat " +
                "mode=0 custom=13 proto=${proto.toHex()}"
        )
        return enqueueOfficialRemoteCommandProtoPayload(
            commandId = REMOTE_MONITOR_FRAME_GUIDE_FORMAT_SET,
            proto = proto,
            purpose = "remote-guide-grid-set:${if (enabled) 1 else 0}",
            label = "9-Grid ${if (enabled) "On" else "Off"}",
            local = true
        )
    }

    @SuppressLint("MissingPermission")
    private fun queueOfficialFrameGuideFormatGetForGrid(enabled: Boolean): Boolean {
        val current = gatt ?: return false
        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return false
        service.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false
        val packetId = 0
        val body = buildOfficialAtomMessageBody(packetId, REMOTE_MONITOR_FRAME_GUIDE_FORMAT_GET)
        val packet = PendingRemotePacket(
            body = body,
            purpose = "remote-guide-grid-format-get:${if (enabled) 1 else 0}",
            label = "9-Grid State",
            commandId = REMOTE_MONITOR_FRAME_GUIDE_FORMAT_GET,
            packetId = packetId,
            expectedResponseId = REMOTE_MONITOR_FRAME_GUIDE_FORMAT_RESPONSE
        )
        pendingFrameGridSet = null
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = if (tcsPacketRemoteCredit) "sending" else "requesting-credit",
            available = true,
            lastCommand = packet.label,
            lastCommandId = packet.commandId,
            lastStatus = "Reading current Frame Guide / 9-Grid state",
            lastError = null
        )
        appendEvent(
            "TCS20.07.2 GRID GET cmd=0x${REMOTE_MONITOR_FRAME_GUIDE_FORMAT_GET.toString(16)} " +
                "target=${if (enabled) 1 else 0} packetId=0 m_isLocal=1 body=${body.toHex()}"
        )
        return queueRemotePacket18_8(packet, local = true)
    }

    @SuppressLint("MissingPermission")
    private fun dispatchOfficialFrameGridSet(request: PendingFrameGridSet): Boolean {
        if (!canQueueOfficialMonitorCommand("frame-grid-set")) {
            pendingFrameGridSet = request
            return false
        }
        val preserveFormat = request.frameGuideValue.takeIf { it in listOf(1,2,3,4,5,6,7,8,9,10,12) } ?: 1
        val proto = buildOfficialFrameGuideFormatProto(
            format = preserveFormat,
            grid = request.enabled,
            mode = 0,
            customRatio = 13
        )
        _monitorGuideState.value = _monitorGuideState.value.copy(
            gridEnabled = request.enabled,
            pendingKey = "grid",
            status = "Applying 9-Grid ${if (request.enabled) "On" else "Off"}..."
        )
        appendEvent(
            "TCS20.13.3R13 GRID FROZEN SET target=${if (request.enabled) 1 else 0} format=$preserveFormat " +
                "mode=0 custom=13 proto=${proto.toHex()}"
        )
        return enqueueOfficialRemoteCommandProtoPayload(
            commandId = REMOTE_MONITOR_FRAME_GUIDE_FORMAT_SET,
            proto = proto,
            purpose = "remote-guide-grid-set:${if (request.enabled) 1 else 0}",
            label = "9-Grid ${if (request.enabled) "On" else "Off"}",
            local = true
        )
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialSafeAreaTitleDouble(): Boolean {
        if (!canQueueOfficialMonitorCommand("safe-area-title-prime")) return false
        deferredSafeAreaMode = 2
        appendEvent("TCS20.07.5R2 SAFE AREA TITLE sequence wire2(Action) -> wire3(Title)")
        return enqueueOfficialRemoteCommandProtoExplicitVarint1(
            commandId = REMOTE_MONITOR_SAFE_AREA_SIZE_SET,
            value = 2,
            purpose = "remote-guide-safe-area-title-prime",
            label = "Safe Area Action Prime"
        )
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialSafeAreaMode(mode: Int): Boolean {
        if (mode !in 0..2) return false
        if (mode == 2) return sendOfficialSafeAreaTitleDouble()

        val wireMode = mode + 1
        if (!canQueueOfficialMonitorCommand("safe-area:$mode")) return false
        appendEvent(
            "TCS20.07.${if (mode == 0) "3R2" else "4R2"} SAFE AREA SET logical=$mode wire=$wireMode " +
                "cmd=0x277 proto=08${"%02X".format(wireMode)}"
        )
        return enqueueOfficialRemoteCommandProtoExplicitVarint1(
            commandId = REMOTE_MONITOR_SAFE_AREA_SIZE_SET,
            value = wireMode,
            purpose = "remote-guide-safe-area-set:$wireMode",
            label = safeAreaLabel(mode)
        )
    }

    /**
     * Official ATOMOS behavior is a cycle: Off -> Action -> Title -> Off.
     * The first tap queries SafeAreaSizeGet when local state is unknown so the
     * Android UI cannot get one step out of sync with a recorder that was already
     * showing a Safe Area before connection.
     */
    @SuppressLint("MissingPermission")
    fun cycleOfficialSafeAreaMode(): Boolean {
        val current = _safeAreaHardwareMode.value ?: 0
        val target = when (current) { 0 -> 1; 1 -> 2; else -> 0 }
        appendEvent("TCS20.07.5 SAFE AREA CYCLE current=$current target=$target")
        return sendOfficialSafeAreaMode(target)
    }

    @SuppressLint("MissingPermission")
    fun refreshOfficialSafeAreaState(): Boolean {
        if (!canQueueOfficialMonitorCommand("safe-area-get")) return false
        val current = gatt ?: return false
        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return false
        service.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false

        val packetId = 0
        val body = buildOfficialAtomMessageBody(packetId, REMOTE_MONITOR_SAFE_AREA_SIZE_GET)
        val packet = PendingRemotePacket(
            body = body,
            purpose = "remote-guide-safe-area-get",
            label = "Safe Area State",
            commandId = REMOTE_MONITOR_SAFE_AREA_SIZE_GET,
            packetId = packetId,
            expectedResponseId = REMOTE_MONITOR_SAFE_AREA_SIZE_RESPONSE
        )
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        appendEvent(
            "TCS20.07.3 SAFE AREA GET cmd=0x278 expected=0x268 packetId=0 m_isLocal=1"
        )
        return queueRemotePacket18_8(packet, local = true)
    }

    @SuppressLint("MissingPermission")
    private fun queueOfficialLutGetter(
        commandId: Int,
        expectedResponseId: Int,
        proto: ByteArray,
        purpose: String,
        label: String
    ): Boolean {
        if (!canQueueOfficialMonitorCommand(label)) return false
        val current = gatt ?: return false
        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return false
        service.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false

        // OUTPUT R9 guarded route experiment: OUTPUT-only read-only getters.
        // R8 sent 0x00DD / 0x00E7 / 0x00E3 as local=false without typed output
        // confirmation. Other Input/Monitor/LUT GETs remain on their original
        // routes. This is a TEST, not proof of the macOS constructor locality.
        val outputR9LocalGetProbe = purpose == "remote-output-hdr-enable-get" ||
            purpose == "remote-lut-output-available-get" ||
            purpose == "remote-lut-output-get" ||
            purpose == "remote-lut-output-verify" ||
            purpose == "remote-output-r13-candidate-hdr-type" ||
            purpose == "remote-output-r13-candidate-gamut" ||
            // OUTPUT R18: R17's three read-only candidate GETs were accidentally
            // omitted here, silently turning requested local=true into local=false.
            purpose == "remote-output-r17-get-downscale" ||
            purpose == "remote-output-r17-get-dci-crop" ||
            purpose == "remote-output-r17-get-hdmi-timecode" ||
            // Isolated R20 OutputPageGet local=true comparison after nonlocal failure.
            purpose == "remote-output-r20-output-page-local-get" ||
            // AUDIO R26: a single isolated local=true audio-meter page GET;
            // Output R24 established this page-family route, not field mapping.
            purpose == "remote-audio-r26-meters-page-get"
        // Local RemoteApi one-shots use packetId=0. Non-local GETs keep their
        // existing shared 1..255 sequence; never change bootstrap/AppPolling.
        if (!outputR9LocalGetProbe) {
            tcsCommandSequence = if (tcsCommandSequence >= 255) 1 else tcsCommandSequence + 1
        }
        val packetId = if (outputR9LocalGetProbe) 0 else tcsCommandSequence
        val body = buildOfficialAtomMessageBodyWithProtoPayload(packetId, commandId, proto)
        val packet = PendingRemotePacket(
            body = body,
            purpose = purpose,
            label = label,
            commandId = commandId,
            packetId = packetId,
            expectedResponseId = expectedResponseId
        )
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        appendEvent(
            "TCS20.09 LUT GET label=$label cmd=0x${commandId.toString(16).uppercase()} " +
                "expected=0x${expectedResponseId.toString(16).uppercase()} packetId=$packetId " +
                "m_isLocal=${if (outputR9LocalGetProbe) 1 else 0} proto=${proto.toHex()}"
        )
        if (outputR9LocalGetProbe) {
            outputR7Trace(
                "R9_OUTPUT_LOCAL_TRUE_GET cmd=0x${commandId.toString(16).uppercase()} " +
                    "expected=0x${expectedResponseId.toString(16).uppercase()} packetId=$packetId " +
                    "purpose=$purpose payloadLen=${proto.size} write=false"
            )
        }
        // OUTPUT R18: report the REAL PacketLink locality, not the caller's
        // requested route.  A buffered GET is not a GATT write or typed RX.
        if (purpose.startsWith("remote-output-r17-get-")) {
            android.util.Log.i("AtomRemoteOutputR18",
                "GET_ROUTE purpose=$purpose cmd=0x${commandId.toString(16)} " +
                    "local=$outputR9LocalGetProbe packetId=$packetId proto=${proto.toHex()}")
        }
        return queueRemotePacket18_8(packet, local = outputR9LocalGetProbe)
    }

    private fun scheduleLutStageFallback(expectedStage: Int, delayMs: Long = 700L) {
        mainHandler.postDelayed({
            if (lutRefreshStage != expectedStage) return@postDelayed
            if (pendingRemotePacket != null) {
                scheduleLutStageFallback(expectedStage, 180L)
                return@postDelayed
            }

            when (expectedStage) {
                1 -> {
                    _monitorLutState.value = _monitorLutState.value.copy(
                        currentSlotQueryCompleted = true,
                        status = "Current LUT slot not reported; reading LUT capabilities"
                    )
                    appendEvent("TCS20.09.R1 LUT SLOT GET NO-TYPED-READBACK continue=true")
                    lutRefreshStage = 2
                    scheduleNextLutRefresh(0L)
                }
                2 -> {
                    appendEvent("TCS20.09.R1 LUT LIST GET NO-TYPED-READBACK continue=true")
                    lutRefreshStage = 3
                    scheduleNextLutRefresh(0L)
                }
                3 -> {
                    appendEvent("TCS20.09.R1 LUT PREVIEW AVAILABLE NO-TYPED-READBACK continue=true")
                    lutRefreshStage = 5
                    scheduleNextLutRefresh(0L)
                }
                4 -> {
                    appendEvent("TCS20.09.R1 LUT PREVIEW STATE NO-TYPED-READBACK continue=true")
                    lutRefreshStage = 5
                    scheduleNextLutRefresh(0L)
                }
                5 -> {
                    appendEvent("TCS20.09.R3 LUT RECORD AVAILABILITY SKIPPED no-guessed-id=true")
                    lutRefreshStage = 6
                    scheduleNextLutRefresh(0L)
                }
                6 -> {
                    appendEvent("TCS20.09.R1 LUT RECORD STATE NO-TYPED-READBACK continue=true")
                    lutRefreshStage = 7
                    scheduleNextLutRefresh(0L)
                }
                7 -> {
                    appendEvent("TCS20.09.R1 LUT OUTPUT AVAILABLE NO-TYPED-READBACK continue=true")
                    lutRefreshStage = 0
                    _monitorLutState.value = _monitorLutState.value.copy(
                        pendingKey = null,
                        finalReady = true,
                        status = "LUT mode ready; this recorder did not report every LUT detail"
                    )
                    appendEvent("TCS20.09.R1 LUT FINAL READY partial=true")
                }
                8 -> {
                    appendEvent("TCS20.09.R1 LUT OUTPUT STATE NO-TYPED-READBACK continue=true")
                    lutRefreshStage = 0
                    _monitorLutState.value = _monitorLutState.value.copy(
                        pendingKey = null,
                        finalReady = true,
                        status = "LUT mode ready; output state not reported"
                    )
                    appendEvent("TCS20.09.R1 LUT FINAL READY partial=true")
                }
            }
        }, delayMs)
    }

    private fun scheduleNextLutRefresh(delayMs: Long = 120L) {
        mainHandler.postDelayed({
            if (pendingRemotePacket != null) {
                scheduleNextLutRefresh(100L)
                return@postDelayed
            }
            when (lutRefreshStage) {
                2 -> {
                    queueOfficialLutGetter(
                        REMOTE_MONITOR_LUT_SLOT_INFO_LIST_GET,
                        REMOTE_MONITOR_LUT_SLOT_INFO_LIST_RESPONSE,
                        byteArrayOf(0x08, 0x01, 0x10, 0x08),
                        "remote-lut-list-get",
                        "LUT Slot List"
                    )
                    scheduleLutStageFallback(2)
                }
                3 -> {
                    queueOfficialLutGetter(
                        REMOTE_MONITOR_LUT_PREVIEW_AVAILABLE_GET,
                        REMOTE_MONITOR_LUT_PREVIEW_AVAILABLE_RESPONSE,
                        byteArrayOf(),
                        "remote-lut-preview-available-get",
                        "LUT Preview Availability"
                    )
                    scheduleLutStageFallback(3)
                }
                4 -> {
                    queueOfficialLutGetter(
                        REMOTE_MONITOR_LUT_PREVIEW_ENABLE_GET,
                        REMOTE_MONITOR_LUT_PREVIEW_ENABLE_RESPONSE,
                        byteArrayOf(),
                        "remote-lut-preview-get",
                        "LUT Preview State"
                    )
                    scheduleLutStageFallback(4)
                }
                5 -> {
                    // Latest binary ordering proves 0x0138 belongs to
                    // CodecProfileListGet, not LutBurnInAvailableGet.
                    // Do not send a guessed availability command. Probe the exact
                    // LutBurnInEnableGet (0x0130 -> 0x012D) instead.
                    lutRefreshStage = 6
                    scheduleNextLutRefresh(0L)
                }
                6 -> {
                    queueOfficialLutGetter(
                        REMOTE_RECORD_LUT_BURN_IN_ENABLE_GET,
                        REMOTE_RECORD_LUT_BURN_IN_ENABLE_RESPONSE,
                        byteArrayOf(),
                        "remote-lut-record-get",
                        "LUT During Record"
                    )
                    scheduleLutStageFallback(6)
                }
                7 -> {
                    queueOfficialLutGetter(
                        REMOTE_OUTPUT_LUT_OUTPUT_AVAILABLE_GET,
                        REMOTE_OUTPUT_LUT_OUTPUT_AVAILABLE_RESPONSE,
                        byteArrayOf(),
                        "remote-lut-output-available-get",
                        "LUT During Output Availability"
                    )
                    scheduleLutStageFallback(7)
                }
                8 -> {
                    queueOfficialLutGetter(
                        REMOTE_OUTPUT_LUT_OUTPUT_ENABLE_GET,
                        REMOTE_OUTPUT_LUT_OUTPUT_ENABLE_RESPONSE,
                        byteArrayOf(),
                        "remote-lut-output-get",
                        "LUT During Output"
                    )
                    scheduleLutStageFallback(8)
                }
                else -> Unit
            }
        }, delayMs)
    }

    private fun scheduleInitialLutReadRetry(delayMs: Long = 180L) {
        mainHandler.postDelayed({
            if (lutRefreshStage != 1) return@postDelayed

            if (pendingRemotePacket != null) {
                if (lutInitialRetryCount < 12) {
                    lutInitialRetryCount += 1
                    appendEvent(
                        "TCS20.09.R2 LUT INITIAL RETRY wait=$lutInitialRetryCount " +
                            "pending=${pendingRemotePacket?.purpose ?: "none"}"
                    )
                    scheduleInitialLutReadRetry(180L)
                } else {
                    appendEvent("TCS20.09.R2 LUT INITIAL RETRY exhausted; continuing capability chain")
                    lutRefreshStage = 2
                    _monitorLutState.value = _monitorLutState.value.copy(
                        currentSlotQueryCompleted = true,
                        status = "Current LUT slot not reported; reading LUT capabilities"
                    )
                    scheduleNextLutRefresh(0L)
                }
                return@postDelayed
            }

            val queued = queueOfficialLutGetter(
                REMOTE_MONITOR_LUT_SLOT_GET,
                REMOTE_MONITOR_LUT_SLOT_RESPONSE,
                byteArrayOf(),
                "remote-lut-slot-get",
                "LUT Current Slot"
            )

            if (queued) {
                appendEvent("TCS20.09.R2 LUT INITIAL RETRY queued attempt=$lutInitialRetryCount")
                scheduleLutStageFallback(1)
            } else if (lutInitialRetryCount < 12) {
                lutInitialRetryCount += 1
                appendEvent("TCS20.09.R2 LUT INITIAL RETRY admission-failed attempt=$lutInitialRetryCount")
                scheduleInitialLutReadRetry(180L)
            } else {
                appendEvent("TCS20.09.R2 LUT INITIAL RETRY admission-exhausted; continuing capability chain")
                lutRefreshStage = 2
                _monitorLutState.value = _monitorLutState.value.copy(
                    currentSlotQueryCompleted = true,
                    status = "Current LUT slot not reported; reading LUT capabilities"
                )
                scheduleNextLutRefresh(0L)
            }
        }, delayMs)
    }

    @SuppressLint("MissingPermission")
    fun refreshOfficialLutState(): Boolean {
        appendEvent("TCS20.09.R1 LUT REFRESH ATTEMPT pending=${pendingRemotePacket?.purpose ?: "none"}")
        lutRefreshStage = 1
        _monitorLutState.value = _monitorLutState.value.copy(
            pendingKey = "refresh",
            status = "Reading LUT state from recorder"
        )
        appendEvent("TCS20.09.0 LUT REFRESH START")
        lutInitialRetryCount = 0
        val queued = queueOfficialLutGetter(
            REMOTE_MONITOR_LUT_SLOT_GET,
            REMOTE_MONITOR_LUT_SLOT_RESPONSE,
            byteArrayOf(),
            "remote-lut-slot-get",
            "LUT Current Slot"
        )
        if (queued) {
            scheduleLutStageFallback(1)
            return true
        }

        // The LUT refresh commonly starts immediately after MonitorModeSet/Get.
        // If that transaction is still occupying the single PacketLink command
        // slot, defer instead of silently dropping the LUT refresh.
        appendEvent("TCS20.09.R2 LUT INITIAL DEFER queue-busy-or-not-ready=true")
        scheduleInitialLutReadRetry()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun enqueueOfficialLutSetter(
        commandId: Int,
        proto: ByteArray,
        purpose: String,
        label: String
    ): Boolean {
        if (!canQueueOfficialMonitorCommand(label)) return false
        val current = gatt ?: return false
        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return false
        service.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false

        // 20.09 R3 deep macOS disassembly parity:
        // LUT setters are AtomMessage local one-shots. The same AtomMessage
        // locality byte used by proven Record.Start=true is set for:
        // LutSlotSet, LutPreviewEnableSet, Record.LutBurnInEnableSet,
        // Output.LutOutputEnableSet. Local one-shots use packetId=0.
        val packetId = 0
        val body = buildOfficialAtomMessageBodyWithProtoPayload(packetId, commandId, proto)
        val packet = PendingRemotePacket(
            body = body,
            purpose = purpose,
            label = label,
            commandId = commandId,
            packetId = packetId
        )
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = if (tcsPacketRemoteCredit) "sending" else "requesting-credit",
            available = true,
            lastCommand = label,
            lastCommandId = commandId,
            lastTxHex = null,
            lastStatus = "Sending $label",
            lastError = null
        )
        appendEvent(
            "TCS20.09.R3 LUT SET QUEUE label=$label cmd=0x${commandId.toString(16).uppercase()} " +
                "packetId=$packetId m_isLocal=1 proto=${proto.toHex()}"
        )
        return queueRemotePacket18_8(packet, local = true)
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialLutSlot(slot: Int): Boolean {
        if (slot !in 1..8) return false
        if (!canQueueOfficialMonitorCommand("lut-slot:$slot")) return false
        lutExpectedSlot = slot
        _monitorLutState.value = _monitorLutState.value.copy(
            requestedSlot = slot,
            pendingKey = "slot",
            status = "LUT Slot $slot requested; waiting for recorder readback"
        )
        appendEvent("TCS20.09.1 LUT SLOT SET slot=$slot cmd=0x2BE m_isLocal=1")
        val queued = enqueueOfficialLutSetter(
            commandId = REMOTE_MONITOR_LUT_SLOT_SET,
            proto = byteArrayOf(0x08, slot.toByte()),
            purpose = "remote-lut-slot-set:$slot",
            label = "LUT Slot $slot"
        )
        if (queued) scheduleLutPendingRelease("slot", "LUT Slot $slot")
        return queued
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialLutPreview(enabled: Boolean): Boolean {
        if (_monitorLutState.value.previewAvailable == false) {
            appendEvent("TCS20.09.2 LUT PREVIEW blocked available=false")
            return false
        }
        if (!canQueueOfficialMonitorCommand("lut-preview:$enabled")) return false
        lutExpectedPreview = enabled
        _monitorLutState.value = _monitorLutState.value.copy(
            requestedPreviewEnabled = enabled,
            pendingKey = "preview",
            status = "LUT Preview ${if (enabled) "On" else "Off"} requested; waiting for recorder readback"
        )
        val proto = byteArrayOf(0x08, if (enabled) 0x01 else 0x00)
        appendEvent("TCS20.09.2 LUT PREVIEW SET enabled=$enabled cmd=0x2C0 m_isLocal=1")
        val queued = enqueueOfficialLutSetter(
            commandId = REMOTE_MONITOR_LUT_PREVIEW_ENABLE_SET,
            proto = proto,
            purpose = "remote-lut-preview-set:${if (enabled) 1 else 0}",
            label = "LUT Preview ${if (enabled) "On" else "Off"}"
        )
        if (queued) scheduleLutPendingRelease("preview", "LUT Preview")
        return queued
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialLutDuringRecord(enabled: Boolean): Boolean {
        if (_monitorLutState.value.recordBurnInAvailable == false) {
            appendEvent("TCS20.09.3 LUT RECORD blocked available=false")
            return false
        }
        if (!canQueueOfficialMonitorCommand("lut-record:$enabled")) return false
        lutExpectedRecordBurnIn = enabled
        _monitorLutState.value = _monitorLutState.value.copy(
            requestedRecordBurnInEnabled = enabled,
            pendingKey = "record",
            status = "LUT During Record ${if (enabled) "On" else "Off"} requested; waiting for recorder readback"
        )
        appendEvent("TCS20.09.3 LUT RECORD SET enabled=$enabled cmd=0x12F m_isLocal=1")
        val queued = enqueueOfficialLutSetter(
            commandId = REMOTE_RECORD_LUT_BURN_IN_ENABLE_SET,
            proto = byteArrayOf(0x08, if (enabled) 0x01 else 0x00),
            purpose = "remote-lut-record-set:${if (enabled) 1 else 0}",
            label = "LUT During Record ${if (enabled) "On" else "Off"}"
        )
        if (queued) scheduleLutPendingRelease("record", "LUT During Record")
        return queued
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialLutDuringOutput(enabled: Boolean): Boolean {
        if (_monitorLutState.value.outputLutAvailable == false) {
            appendEvent("TCS20.09.4 LUT OUTPUT blocked available=false")
            return false
        }
        if (!canQueueOfficialMonitorCommand("lut-output:$enabled")) return false
        lutExpectedOutput = enabled
        _monitorLutState.value = _monitorLutState.value.copy(
            requestedOutputLutEnabled = enabled,
            pendingKey = "output",
            status = "LUT During Output ${if (enabled) "On" else "Off"} requested; waiting for recorder readback"
        )
        appendEvent("TCS20.09.4 LUT OUTPUT SET enabled=$enabled cmd=0xE2 m_isLocal=1")
        val queued = enqueueOfficialLutSetter(
            commandId = REMOTE_OUTPUT_LUT_OUTPUT_ENABLE_SET,
            proto = byteArrayOf(0x08, if (enabled) 0x01 else 0x00),
            purpose = "remote-lut-output-set:${if (enabled) 1 else 0}",
            label = "LUT During Output ${if (enabled) "On" else "Off"}"
        )
        if (queued) scheduleLutPendingRelease("output", "LUT During Output")
        return queued
    }

    private fun scheduleLutRecordVerification() {
        mainHandler.postDelayed({
            if (pendingRemotePacket != null) {
                scheduleLutRecordVerification()
            } else {
                queueOfficialLutGetter(
                    REMOTE_RECORD_LUT_BURN_IN_ENABLE_GET,
                    REMOTE_RECORD_LUT_BURN_IN_ENABLE_RESPONSE,
                    byteArrayOf(),
                    "remote-lut-record-verify",
                    "LUT During Record Verify"
                )
            }
        }, 140L)
    }

    private fun scheduleLutOutputVerification() {
        mainHandler.postDelayed({
            if (pendingRemotePacket != null) {
                scheduleLutOutputVerification()
            } else {
                queueOfficialLutGetter(
                    REMOTE_OUTPUT_LUT_OUTPUT_ENABLE_GET,
                    REMOTE_OUTPUT_LUT_OUTPUT_ENABLE_RESPONSE,
                    byteArrayOf(),
                    "remote-lut-output-verify",
                    "LUT During Output Verify"
                )
            }
        }, 140L)
    }

    private fun scheduleLutPendingRelease(
        key: String,
        expectedLabel: String,
        delayMs: Long = 1200L
    ) {
        mainHandler.postDelayed({
            val current = _monitorLutState.value
            if (current.pendingKey != key) return@postDelayed

            _monitorLutState.value = current.copy(
                pendingKey = null,
                status = "$expectedLabel requested; no typed recorder confirmation (UI request retained)"
            )
            appendEvent("TCS20.09.R6 LUT PENDING RELEASE key=$key reason=no-typed-readback")
        }, delayMs)
    }

    private fun scheduleLutSlotVerification() {
        mainHandler.postDelayed({
            if (pendingRemotePacket != null) {
                scheduleLutSlotVerification()
            } else {
                queueOfficialLutGetter(
                    REMOTE_MONITOR_LUT_SLOT_GET,
                    REMOTE_MONITOR_LUT_SLOT_RESPONSE,
                    byteArrayOf(),
                    "remote-lut-slot-verify",
                    "LUT Slot Verify"
                )
            }
        }, 140L)
    }

    private fun scheduleLutPreviewVerification() {
        mainHandler.postDelayed({
            if (pendingRemotePacket != null) {
                scheduleLutPreviewVerification()
            } else {
                queueOfficialLutGetter(
                    REMOTE_MONITOR_LUT_PREVIEW_ENABLE_GET,
                    REMOTE_MONITOR_LUT_PREVIEW_ENABLE_RESPONSE,
                    byteArrayOf(),
                    "remote-lut-preview-verify",
                    "LUT Preview Verify"
                )
            }
        }, 140L)
    }

    fun monitorSnapshotTransportIdle(): Boolean =
        pendingRemotePacket == null &&
            !operationInFlight &&
            pendingOps.isEmpty() &&
            !remoteBootstrapActive &&
            lutRefreshStage == 0 &&
            scopeRefreshStage == 0 &&
            focusRefreshStage == 0 &&
            !zoomRefreshActive

    // 20.10.0-20.10.5 Display family recovered from the supplied macOS RemoteApi binary.
    // Setters use packetId=0/m_isLocal=true. GETs use the same non-local sequence style
    // as the macOS Display controller and only typed responses update confirmed values.
    @SuppressLint("MissingPermission")
    fun refreshOfficialDisplayState(): Boolean {
        if (!canQueueOfficialMonitorCommand("display-refresh")) return false
        _monitorDisplayState.value = _monitorDisplayState.value.copy(status = "Reading Display state from recorder")
        appendEvent("TCS20.11.0 DISPLAY SNAPSHOT START")

        // Do not chain the entire Display snapshot behind BacklightBrightnessGet.
        // AG2 can omit the direct Backlight typed response while still reporting
        // Backlight in AppPolling.MonitorPageState. Each getter therefore gets its
        // own serialized turn and scheduleDisplayGetter waits for PacketLink idle.
        val queued = queueOfficialDisplayGetter(REMOTE_DISPLAY_BACKLIGHT_GET, REMOTE_DISPLAY_BACKLIGHT_RESPONSE, "display-backlight-get", "Display Backlight")
        if (queued) {
            scheduleDisplayGetter(REMOTE_DISPLAY_Y_LIFT_GET, REMOTE_DISPLAY_Y_LIFT_RESPONSE, "display-lift-get", "Display Y Lift", 220L)
            scheduleDisplayGetter(REMOTE_DISPLAY_Y_GAMMA_GET, REMOTE_DISPLAY_Y_GAMMA_RESPONSE, "display-gamma-get", "Display Y Gamma", 440L)
            scheduleDisplayGetter(REMOTE_DISPLAY_Y_GAIN_GET, REMOTE_DISPLAY_Y_GAIN_RESPONSE, "display-gain-get", "Display Y Gain", 660L)
            scheduleDisplayGetter(REMOTE_DISPLAY_TALLY_GET, REMOTE_DISPLAY_TALLY_RESPONSE, "display-tally-get", "Display Tally", 880L)
            scheduleDisplayGetter(REMOTE_DISPLAY_FLIP_GET, REMOTE_DISPLAY_FLIP_RESPONSE, "display-flip-get", "Display Flip", 1100L)
            scheduleDisplayGetter(REMOTE_DISPLAY_SUPPORTS_GET, REMOTE_DISPLAY_SUPPORTS_RESPONSE, "display-supports-get", "Display Supports", 1320L)
            // AG2 can omit the direct Backlight typed response while still
            // exposing the real recorder value in AppPolling.MonitorPage field 8.
            // Always run the page fallback after the typed Display family.
            scheduleMonitorPageFallback("display-refresh", 1500L)
            appendEvent("TCS20.13.0 DISPLAY GETTERS SERIALIZED count=7 plusMonitorPageFallback=1")
        }
        return queued
    }

    fun refreshOfficialMonitorPageSnapshot(): Boolean {
        if (!canQueueOfficialMonitorCommand("monitor-page-snapshot")) return false
        appendEvent("TCS20.13.0 MONITOR PAGE SNAPSHOT GET cmd=0x${REMOTE_APP_POLLING_MONITOR_PAGE_GET.toString(16).uppercase()} m_isLocal=0")
        return queueOfficialDisplayGetter(
            REMOTE_APP_POLLING_MONITOR_PAGE_GET,
            REMOTE_APP_POLLING_MONITOR_PAGE_GET,
            "monitor-page-snapshot",
            "Monitor Page Snapshot"
        )
    }

    /**
     * Recorder-authoritative MonitorAssist summary. This page carries the
     * current 9-grid and Frame Guide enable state in fields 13/14 and Zoom in
     * field 8. It is used as a post-set readback fallback, never as an ACK.
     */
    fun refreshOfficialMonitorAssistSummarySnapshot(reason: String = "manual"): Boolean {
        if (!canQueueOfficialMonitorCommand("monitor-assist-summary:$reason")) return false
        appendEvent(
            "TCS20.13 GUIDE SUMMARY GET reason=$reason cmd=0x${REMOTE_APP_POLLING_MONITOR_ASSIST_SUMMARY_GET.toString(16).uppercase()} m_isLocal=0"
        )
        return queueOfficialDisplayGetter(
            REMOTE_APP_POLLING_MONITOR_ASSIST_SUMMARY_GET,
            REMOTE_APP_POLLING_MONITOR_ASSIST_SUMMARY_GET,
            "monitor-assist-summary-snapshot:$reason",
            "Monitor Assist Summary"
        )
    }

    private fun scheduleMonitorPageFallback(reason: String, delayMs: Long = 160L, attempt: Int = 0) {
        mainHandler.postDelayed({
            if (attempt >= 40) {
                appendEvent("TCS20.13.0 BACKLIGHT MONITOR PAGE FALLBACK TIMEOUT reason=$reason")
                return@postDelayed
            }
            if (monitorSnapshotTransportIdle()) {
                val queued = refreshOfficialMonitorPageSnapshot()
                appendEvent("TCS20.13.0 BACKLIGHT MONITOR PAGE FALLBACK reason=$reason queued=$queued attempt=$attempt")
                if (!queued) scheduleMonitorPageFallback(reason, 120L, attempt + 1)
            } else {
                scheduleMonitorPageFallback(reason, 120L, attempt + 1)
            }
        }, delayMs)
    }

    private fun scheduleMonitorAssistSummaryFallback(reason: String, delayMs: Long = 160L, attempt: Int = 0) {
        mainHandler.postDelayed({
            if (attempt >= 40) {
                appendEvent("TCS20.13 GUIDE SUMMARY TIMEOUT reason=$reason")
                return@postDelayed
            }
            if (monitorSnapshotTransportIdle()) {
                val queued = refreshOfficialMonitorAssistSummarySnapshot(reason)
                appendEvent("TCS20.13 GUIDE SUMMARY QUEUE reason=$reason queued=$queued attempt=$attempt")
                if (!queued) scheduleMonitorAssistSummaryFallback(reason, 120L, attempt + 1)
            } else {
                scheduleMonitorAssistSummaryFallback(reason, 120L, attempt + 1)
            }
        }, delayMs)
    }

    private fun scheduleDisplayGetter(commandId: Int, responseId: Int, purpose: String, label: String, delayMs: Long = 150L) {
        mainHandler.postDelayed({
            if (pendingRemotePacket != null) {
                scheduleDisplayGetter(commandId, responseId, purpose, label, 120L)
            } else {
                queueOfficialDisplayGetter(commandId, responseId, purpose, label)
            }
        }, delayMs)
    }

    @SuppressLint("MissingPermission")
    private fun queueOfficialDisplayGetter(commandId: Int, expectedResponseId: Int, purpose: String, label: String): Boolean {
        if (!canQueueOfficialMonitorCommand(label)) return false
        val current = gatt ?: return false
        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return false
        service.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false
        tcsCommandSequence = if (tcsCommandSequence >= 255) 1 else tcsCommandSequence + 1
        val packetId = tcsCommandSequence
        val body = buildOfficialAtomMessageBodyWithProtoPayload(packetId, commandId, byteArrayOf())
        val packet = PendingRemotePacket(body, purpose, label, commandId, packetId, expectedResponseId = expectedResponseId)
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        appendEvent("TCS20.10 DISPLAY GET label=$label cmd=0x${commandId.toString(16).uppercase()} expected=0x${expectedResponseId.toString(16).uppercase()} packetId=$packetId m_isLocal=0")
        return queueRemotePacket18_8(packet, local = false)
    }

    private fun encodeProtoInt32Field1(value: Int): ByteArray {
        if (value == 0) return byteArrayOf()
        val out = java.io.ByteArrayOutputStream()
        out.write(0x08)
        var v = value.toLong()
        // protobuf int32 uses sign-extended varint for negatives; non-negative display
        // values use normal multi-byte varint (required for 176..1000 ranges).
        do {
            val next = v ushr 7
            var b = (v and 0x7F).toInt()
            if (next != 0L) b = b or 0x80
            out.write(b)
            v = next
        } while (v != 0L)
        return out.toByteArray()
    }

    private fun encodeProtoVarintField(fieldNumber: Int, value: Int): ByteArray {
        require(fieldNumber in 1..15)
        require(value >= 0)
        val out = java.io.ByteArrayOutputStream()
        out.write((fieldNumber shl 3) or 0)
        var v = value.toLong()
        do {
            val next = v ushr 7
            var b = (v and 0x7F).toInt()
            if (next != 0L) b = b or 0x80
            out.write(b)
            v = next
        } while (v != 0L)
        return out.toByteArray()
    }

    private fun buildProtoVarintFields(vararg pairs: Pair<Int, Int>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        pairs.forEach { (field, value) -> out.writeBytes(encodeProtoVarintField(field, value)) }
        return out.toByteArray()
    }

    private fun displaySetInt32(commandId: Int, value: Int, key: String, label: String): Boolean {
        if (!canQueueOfficialMonitorCommand("display-$key")) return false
        val proto = encodeProtoInt32Field1(value)
        val queued = enqueueOfficialRemoteCommandProtoPayload(commandId, proto, "remote-display-$key-set:$value", label, local = true)
        if (queued) appendEvent("TCS20.10 DISPLAY SET key=$key value=$value cmd=0x${commandId.toString(16).uppercase()} m_isLocal=1 proto=${proto.toHex()}")
        return queued
    }


    private fun scheduleDisplayPendingRelease(key: String, delayMs: Long = 1500L) {
        mainHandler.postDelayed({
            val current = _monitorDisplayState.value
            if (current.pendingKey != key) return@postDelayed
            _monitorDisplayState.value = current.copy(
                pendingKey = null,
                status = "${key.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} requested; no typed recorder confirmation (UI request retained)"
            )
            appendEvent("TCS20.10 DISPLAY PENDING RELEASE key=$key reason=no-typed-readback")
        }, delayMs)
    }

    fun setOfficialDisplayBacklight(percent: Int): Boolean {
        val p = percent.coerceIn(0, 100)
        _monitorDisplayState.value = _monitorDisplayState.value.copy(
            requestedBacklightPercent = p,
            pendingKey = "backlight",
            status = "Backlight $p% requested; waiting for recorder readback"
        )
        // 20.13.0A R7: exact macOS BacklightSettingsController.backlightChanged:
        // rounds the UISlider value to an integer and passes that integer directly
        // into RemoteApi.Display.BacklightBrightnessSet. There is no UI-side x10
        // conversion. R6.3 sent 14% as wire=140 and AG2 did not change brightness.
        val queued = displaySetInt32(
            REMOTE_DISPLAY_BACKLIGHT_SET,
            p,
            "backlight",
            "Display Backlight $p%"
        )
        if (queued) {
            appendEvent("TCS20.13.0A BACKLIGHT DIRECT-PERCENT requested=$p wire=$p cmd=0x320")
            scheduleDisplayGetter(
                REMOTE_DISPLAY_BACKLIGHT_GET,
                REMOTE_DISPLAY_BACKLIGHT_RESPONSE,
                "display-backlight-verify",
                "Display Backlight Verify"
            )
            scheduleMonitorPageFallback("backlight-set-direct-percent", 520L)
            scheduleDisplayPendingRelease("backlight")
        }
        return queued
    }

    fun setOfficialDisplayYLift(value: Int): Boolean {
        val v = value.coerceIn(-64, 64)
        _monitorDisplayState.value = _monitorDisplayState.value.copy(requestedYLift = v, pendingKey = "lift", status = "Y Lift $v requested; waiting for recorder readback")
        val queued = displaySetInt32(REMOTE_DISPLAY_Y_LIFT_SET, v, "lift", "Display Y Lift $v")
        if (queued) { scheduleDisplayGetter(REMOTE_DISPLAY_Y_LIFT_GET, REMOTE_DISPLAY_Y_LIFT_RESPONSE, "display-lift-verify", "Display Y Lift Verify"); scheduleDisplayPendingRelease("lift") }
        return queued
    }

    fun setOfficialDisplayYGamma(hundredths: Int): Boolean {
        val v = hundredths.coerceIn(176, 304)
        _monitorDisplayState.value = _monitorDisplayState.value.copy(requestedYGammaHundredths = v, pendingKey = "gamma", status = "Y Gamma ${v / 100f} requested; waiting for recorder readback")
        val queued = displaySetInt32(REMOTE_DISPLAY_Y_GAMMA_SET, v, "gamma", "Display Y Gamma")
        if (queued) { scheduleDisplayGetter(REMOTE_DISPLAY_Y_GAMMA_GET, REMOTE_DISPLAY_Y_GAMMA_RESPONSE, "display-gamma-verify", "Display Y Gamma Verify"); scheduleDisplayPendingRelease("gamma") }
        return queued
    }

    fun setOfficialDisplayYGain(hundredths: Int): Boolean {
        val v = hundredths.coerceIn(50, 200)
        _monitorDisplayState.value = _monitorDisplayState.value.copy(requestedYGainHundredths = v, pendingKey = "gain", status = "Y Gain ${v / 100f}x requested; waiting for recorder readback")
        val queued = displaySetInt32(REMOTE_DISPLAY_Y_GAIN_SET, v, "gain", "Display Y Gain")
        if (queued) { scheduleDisplayGetter(REMOTE_DISPLAY_Y_GAIN_GET, REMOTE_DISPLAY_Y_GAIN_RESPONSE, "display-gain-verify", "Display Y Gain Verify"); scheduleDisplayPendingRelease("gain") }
        return queued
    }

    fun setOfficialDisplayTally(enabled: Boolean): Boolean {
        _monitorDisplayState.value = _monitorDisplayState.value.copy(requestedTallyEnabled = enabled, pendingKey = "tally", status = "Tally ${if (enabled) "On" else "Off"} requested; waiting for recorder readback")
        val queued = enqueueOfficialRemoteCommandProtoBool1(REMOTE_DISPLAY_TALLY_SET, enabled, "remote-display-tally-set", "Display Tally")
        if (queued) {
            appendEvent("TCS20.10 DISPLAY TALLY SET enabled=$enabled cmd=0x${REMOTE_DISPLAY_TALLY_SET.toString(16).uppercase()} m_isLocal=1")
            scheduleDisplayGetter(REMOTE_DISPLAY_TALLY_GET, REMOTE_DISPLAY_TALLY_RESPONSE, "display-tally-verify", "Display Tally Verify")
            scheduleDisplayPendingRelease("tally")
        }
        return queued
    }

    fun setOfficialDisplayFlip(enabled: Boolean): Boolean {
        _monitorDisplayState.value = _monitorDisplayState.value.copy(requestedFlipEnabled = enabled, pendingKey = "flip", status = "Flip ${if (enabled) "On" else "Off"} requested; waiting for recorder readback")
        val queued = enqueueOfficialRemoteCommandProtoBool1(REMOTE_DISPLAY_FLIP_SET, enabled, "remote-display-flip-set", "Display Flip")
        if (queued) {
            appendEvent("TCS20.10 DISPLAY FLIP SET enabled=$enabled cmd=0x${REMOTE_DISPLAY_FLIP_SET.toString(16).uppercase()} m_isLocal=1")
            scheduleDisplayGetter(REMOTE_DISPLAY_FLIP_GET, REMOTE_DISPLAY_FLIP_RESPONSE, "display-flip-verify", "Display Flip Verify")
            scheduleDisplayPendingRelease("flip")
        }
        return queued
    }

    fun resetOfficialDisplayPicture(): Boolean {
        if (!canQueueOfficialMonitorCommand("display-y-settings-reset")) return false
        val queued = enqueueOfficialRemoteCommand(REMOTE_DISPLAY_PICTURE_RESET, "remote-display-y-settings-reset", "Display Y Settings Reset")
        if (queued) {
            // The recorder is authoritative after reset. Drop both optimistic
            // requested values and the old confirmed Y values immediately so
            // the UI cannot keep displaying the pre-reset slider positions.
            _monitorDisplayState.value = _monitorDisplayState.value.copy(
                yLift = null,
                yGammaHundredths = null,
                yGainHundredths = null,
                requestedYLift = null,
                requestedYGammaHundredths = null,
                requestedYGainHundredths = null,
                pendingKey = "reset",
                status = "Picture reset requested; reading recorder values"
            )
            appendEvent("TCS20.13.1 DISPLAY Y SETTINGS RESET cmd=0x${REMOTE_DISPLAY_PICTURE_RESET.toString(16).uppercase()} m_isLocal=1 staleYStateCleared=1")
            mainHandler.postDelayed({
                fun retryFullDisplayReadback(attempt: Int) {
                    if (attempt >= 40) {
                        _monitorDisplayState.value = _monitorDisplayState.value.copy(
                            pendingKey = null,
                            status = "Picture reset accepted; recorder readback timed out"
                        )
                        appendEvent("TCS20.13.1 DISPLAY RESET VERIFY TIMEOUT")
                        return
                    }
                    if (monitorSnapshotTransportIdle()) {
                        val started = refreshOfficialDisplayState()
                        appendEvent("TCS20.13.1 DISPLAY RESET FULL READBACK started=$started attempt=$attempt")
                        if (started) {
                            // refreshOfficialDisplayState also schedules this,
                            // but the explicit post-reset page readback is kept
                            // as a second bounded hardware-authoritative path.
                            scheduleMonitorPageFallback("picture-reset", 420L)
                        } else {
                            mainHandler.postDelayed({ retryFullDisplayReadback(attempt + 1) }, 120L)
                        }
                    } else {
                        mainHandler.postDelayed({ retryFullDisplayReadback(attempt + 1) }, 120L)
                    }
                }
                retryFullDisplayReadback(0)
            }, 300L)
        }
        return queued
    }

    @SuppressLint("MissingPermission")
    fun refreshOfficialAnamorphicMode(): Boolean {
        if (!canQueueOfficialMonitorCommand("anamorphic-get")) return false
        val current = gatt ?: return false
        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return false
        service.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false

        val packetId = 0
        val body = buildOfficialAtomMessageBody(packetId, REMOTE_MONITOR_ANAMORPHIC_DESQUEEZE_MODE_GET)
        val packet = PendingRemotePacket(
            body = body,
            purpose = "remote-anamorphic-mode-get",
            label = "Anamorphic Current Mode",
            commandId = REMOTE_MONITOR_ANAMORPHIC_DESQUEEZE_MODE_GET,
            packetId = packetId,
            expectedResponseId = REMOTE_MONITOR_ANAMORPHIC_DESQUEEZE_MODE_RESPONSE
        )
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        _monitorAnamorphicState.value = _monitorAnamorphicState.value.copy(
            status = "Reading Anamorphic mode from recorder"
        )
        appendEvent("TCS20.08.0 ANAMORPHIC GET cmd=0x27A expected=0x269 packetId=0 m_isLocal=1")
        return queueRemotePacket18_8(packet, local = true)
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialAnamorphicMode(mode: Int): Boolean {
        if (mode !in 0..9) return false
        if (pendingRemotePacket != null) {
            deferredAnamorphicMode = mode
            _monitorAnamorphicState.value = _monitorAnamorphicState.value.copy(
                pendingMode = mode,
                status = "Waiting for current recorder transaction"
            )
            appendEvent("TCS20.08.2 ANAMORPHIC COALESCE latest=$mode")
            return true
        }
        if (!canQueueOfficialMonitorCommand("anamorphic:$mode")) return false
        _monitorAnamorphicState.value = _monitorAnamorphicState.value.copy(
            pendingMode = mode,
            status = "Applying ${anamorphicLabel(mode)}; waiting for recorder readback"
        )
        appendEvent("TCS20.08.2 ANAMORPHIC SET mode=$mode label=${anamorphicLabel(mode)} cmd=0x279")
        return enqueueOfficialRemoteCommandProtoExplicitVarint1(
            commandId = REMOTE_MONITOR_ANAMORPHIC_DESQUEEZE_MODE_SET,
            value = mode,
            purpose = "remote-anamorphic-mode:$mode",
            label = "Anamorphic ${anamorphicLabel(mode)}"
        )
    }

    @SuppressLint("MissingPermission")
    fun refreshOfficialMonitorMode(source: String = "manual", expectedMode: Int? = null): Boolean {
        if (!canQueueOfficialMonitorCommand("monitor-mode-get")) return false
        val g = gatt ?: return false
        val writeChar = g.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
            ?.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false

        // 20.04.9 exact macOS parity:
        // uploaded ATOMOS Remote binary constructs MonitorModeGet with
        // AtomMessage(baseLocal = true). Local RemoteApi one-shots use packetId=0.
        val packetId = 0
        val body = buildOfficialAtomMessageBody(packetId, REMOTE_MONITOR_MODE_GET)
        val packet = PendingRemotePacket(
            body = body,
            purpose = "remote-monitor-mode-get:$source:${expectedMode ?: -1}",
            label = "Monitor Mode Readback",
            commandId = REMOTE_MONITOR_MODE_GET,
            packetId = packetId,
            expectedResponseId = REMOTE_MONITOR_MODE_RESPONSE
        )
        monitorModeReadbackExpected = expectedMode
        monitorModeReadbackSource = source
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        _monitorCoreSnapshot.value = _monitorCoreSnapshot.value.copy(
            pendingMode = expectedMode,
            status = if (expectedMode != null) {
                "Verifying ${MonitorCoreCatalog.label(expectedMode)} with recorder"
            } else {
                "Reading Monitor mode from recorder"
            }
        )
        appendEvent(
            "TCS20.04.0 MONITOR MODE GET source=$source expected=${expectedMode ?: -1} " +
                "cmd=0x${REMOTE_MONITOR_MODE_GET.toString(16).uppercase()} packetId=$packetId m_isLocal=1"
        )
        return queueRemotePacket18_8(packet, local = true)
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialMonitorMode(mode: Int): Boolean {
        // Exact mode values recovered from the macOS AtomRemote executable's
        // static MonitorMode table: Native=1, Rec709=2, HLG=3, PQ=4, LUT=5.
        if (mode !in 1..5) {
            appendEvent("TCS19.11.5 MONITOR MODE unsupported mode=$mode")
            return false
        }
        if (!canQueueOfficialMonitorCommand("monitor-mode:$mode")) return false
        val label = when (mode) {
            1 -> "Monitor Native"
            2 -> "Monitor Rec.709"
            3 -> "Monitor HLG"
            4 -> "Monitor PQ"
            else -> "Monitor LUT"
        }
        _monitorCoreSnapshot.value = _monitorCoreSnapshot.value.copy(
            pendingMode = mode,
            status = "Applying ${MonitorCoreCatalog.label(mode)}"
        )
        appendEvent("TCS20.04.2 MONITOR MODE SET cmd=0x${REMOTE_MONITOR_MODE_SET.toString(16)} mode=$mode label=$label")
        return enqueueOfficialRemoteCommandProtoExplicitVarint1(
            commandId = REMOTE_MONITOR_MODE_SET,
            value = mode,
            purpose = "remote-monitor-mode:$mode",
            label = label
        )
    }

    @SuppressLint("MissingPermission")
    fun setZoomAnalogSessionActive(active: Boolean): Boolean {
        zoomAnalogSessionActive = active
        zoomPanSettleGeneration += 1
        zoomPanWatchdogGeneration = zoomPanSettleGeneration
        if (!active) {
            queuedLiveZoomPanX = null
            queuedLiveZoomPanY = null
            zoomPanPumpScheduled = false
            _monitorZoomViewport.value = _monitorZoomViewport.value.copy(
                pendingPan = false,
                status = "Zoom Analog closed"
            )
            appendEvent("TCS20.13.4R15 ZOOM ANALOG SESSION CLOSE; queue cleared; no viewport polling")
            return true
        }
        queuedLiveZoomPanX = null
        queuedLiveZoomPanY = null
        _monitorZoomViewport.value = _monitorZoomViewport.value.copy(
            pendingPan = false,
            status = "Opening Zoom Analog viewport"
        )
        appendEvent("TCS20.13.4R15 ZOOM ANALOG SESSION OPEN; one-shot viewport sync")
        return if (pendingRemotePacket == null) queueOfficialZoomBoxGet() else {
            mainHandler.postDelayed({
                if (zoomAnalogSessionActive && pendingRemotePacket == null) {
                    queueOfficialZoomBoxGet()
                }
            }, 120L)
            true
        }
    }

    /**
     * RemoteApi.MonitorAssist.PanSet = 0x027C recovered from macOS. Its typed
     * MonitorAssistPanMsg constructor takes two Int values (x,y), serialized as
     * protobuf fields 1 and 2. Values here are recorder viewport coordinates.
     */
    @SuppressLint("MissingPermission")
    fun sendOfficialZoomPan(x: Int, y: Int): Boolean {
        if (!zoomAnalogSessionActive) {
            appendEvent("TCS20.13.4R15 ZOOM PAN IGNORED session=closed")
            return false
        }
        val logicalZoom = _zoomHardwareLevel.value ?: (_monitorZoomCore.value.currentLevel ?: 0)
        if (logicalZoom != 2) {
            _monitorZoomViewport.value = _monitorZoomViewport.value.copy(
                pendingPan = false,
                status = "Zoom Analog requires 2:1"
            )
            return false
        }
        val recorderBox = _zoomBoxState.value
        val box = if (recorderBox != null && recorderBox.valid && !recorderBox.pannable) {
            recorderBox.copy(
                viewportWidth = (recorderBox.frameWidth / 2).coerceAtLeast(1),
                viewportHeight = (recorderBox.frameHeight / 2).coerceAtLeast(1)
            ).also { effective ->
                _zoomBoxState.value = effective
                _monitorZoomViewport.value = _monitorZoomViewport.value.copy(
                    ready = true,
                    pannable = effective.pannable,
                    status = "2:1 Analog viewport ready"
                )
                appendEvent(
                    "TCS20.13.4R14 ZOOM EFFECTIVE BOX frame=${effective.frameWidth}x${effective.frameHeight} " +
                        "view=${effective.viewportWidth}x${effective.viewportHeight}"
                )
            }
        } else recorderBox
        if (box == null || !box.valid || !box.pannable) {
            _monitorZoomViewport.value = _monitorZoomViewport.value.copy(
                pendingPan = false,
                status = "Zoom Analog is waiting for a valid 2:1 viewport"
            )
            appendEvent("TCS20.13.4R14 ZOOM PAN BLOCKED no-valid-pannable-box level=$logicalZoom")
            return false
        }

        val clampedX = x.coerceIn(0, box.maxLeft)
        val clampedY = y.coerceIn(0, box.maxTop)
        queuedLiveZoomPanX = clampedX
        queuedLiveZoomPanY = clampedY
        zoomPanSettleGeneration += 1
        zoomPanLastTargetAtMs = android.os.SystemClock.elapsedRealtime()
        val gen = zoomPanSettleGeneration
        _monitorZoomViewport.value = _monitorZoomViewport.value.copy(
            pendingPan = true,
            status = "Live Zoom pan streaming"
        )
        appendEvent("TCS20.13.4R14 ZOOM PAN TARGET left=$clampedX top=$clampedY gen=$gen")
        kickLiveZoomPanPump(0L)
        scheduleLiveZoomPanWatchdog(gen)
        return true
    }

    private fun kickLiveZoomPanPump(delayMs: Long = 18L) {
        if (!zoomAnalogSessionActive) return
        if (zoomPanPumpScheduled) return
        zoomPanPumpScheduled = true
        mainHandler.postDelayed({
            zoomPanPumpScheduled = false
            if (zoomAnalogSessionActive) pumpLiveZoomPan()
        }, delayMs.coerceAtLeast(0L))
    }

    private fun pumpLiveZoomPan() {
        if (!zoomAnalogSessionActive) {
            queuedLiveZoomPanX = null
            queuedLiveZoomPanY = null
            return
        }
        val x = queuedLiveZoomPanX
        val y = queuedLiveZoomPanY
        if (x == null || y == null) {
            _monitorZoomViewport.value = _monitorZoomViewport.value.copy(
                pendingPan = false,
                status = "Zoom Analog ready"
            )
            return
        }

        if (pendingRemotePacket != null || pendingOps.isNotEmpty()) {
            kickLiveZoomPanPump(24L)
            return
        }

        queuedLiveZoomPanX = null
        queuedLiveZoomPanY = null
        if (!sendOfficialZoomPanNow(x, y)) {
            queuedLiveZoomPanX = x
            queuedLiveZoomPanY = y
            appendEvent("TCS20.13.4R15 ZOOM PAN RETRY left=$x top=$y")
            kickLiveZoomPanPump(24L)
            return
        }
        zoomPanLastSendAtMs = android.os.SystemClock.elapsedRealtime()
        appendEvent("TCS20.13.4R15 ZOOM PAN SEND left=$x top=$y")
    }

    @SuppressLint("MissingPermission")
    private fun sendOfficialZoomPanNow(x: Int, y: Int): Boolean {
        if (!zoomAnalogSessionActive) return false
        if (!canQueueOfficialMonitorCommand("zoom-pan-live")) return false
        val proto = ByteArrayOutputStream().apply {
            write(0x08); writeUnsignedVarint(x)
            write(0x10); writeUnsignedVarint(y)
        }.toByteArray()
        lastRequestedZoomPanX = x
        lastRequestedZoomPanY = y
        appendEvent(
            "TCS20.13.4R15 ZOOM PAN LIVE cmd=0x27C left=$x top=$y proto=${proto.toHex()} m_isLocal=1"
        )
        return enqueueOfficialRemoteCommandProtoPayload(
            commandId = REMOTE_MONITOR_ZOOM_PAN_SET,
            proto = proto,
            purpose = "remote-monitor-zoom-pan-live:$x:$y",
            label = "Zoom Pan",
            local = true
        )
    }

    private fun scheduleLiveZoomPanWatchdog(generation: Int) {
        zoomPanWatchdogGeneration = generation
        mainHandler.postDelayed({
            if (!zoomAnalogSessionActive) return@postDelayed
            if (zoomPanWatchdogGeneration != generation) return@postDelayed
            if (generation != zoomPanSettleGeneration) return@postDelayed
            if (queuedLiveZoomPanX != null || queuedLiveZoomPanY != null) {
                appendEvent(
                    "TCS20.13.4R15 ZOOM PAN WATCHDOG queued=${queuedLiveZoomPanX}/${queuedLiveZoomPanY}"
                )
                kickLiveZoomPanPump(0L)
            }
        }, 450L)
    }

    private fun drainQueuedLiveZoomPanOrVerify(settleGeneration: Int) {
        if (!zoomAnalogSessionActive) return
        appendEvent("TCS20.13.4R15 ZOOM PAN DRAIN gen=$settleGeneration")
        kickLiveZoomPanPump(0L)
    }

    private fun ByteArrayOutputStream.writeUnsignedVarint(value: Int) {
        var v = value
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            write(b)
        } while (v != 0)
    }

    /**
     * 20.15 R14 Codec parity audited against Contents(20261006-192922).zip.
     *
     * Authoritative command split from the executable:
     *   CodecProfileGet          = 0x013A
     *   CodecProfileSet          = 0x013B
     *   NonRebootCodecProfileSet = 0x013F
     *
     * RecordTabController's normal picker uses NonRebootCodecProfileSet 0x013F.
     * The restart-capable CodecProfileSet path is 0x013B. Earlier Android phases
     * incorrectly used 0x013A as a setter; 0x013A is the CodecProfileGet/readback
     * path and therefore could transport successfully without switching codec.
     *
     * RecordCodecProfileMsg:
     *   field 1 = codec
     *   field 2 = codecBitdepth
     *
     * SwiftProtobuf omits scalar enum value zero, so bitDepth=0 is represented by
     * an absent field #2. Hardware state is updated only by 0x013A readback.
     */
    private fun codecRemoteWireValue(codec: Int): Int? = when (codec) {
        in 1..10 -> codec - 1
        12 -> 10
        in 13..19 -> codec - 2
        else -> null
    }

    private fun codecLogicalFromWire(wire: Int): Int? = when (wire) {
        in 0..9 -> wire + 1
        10 -> 12
        in 11..17 -> wire + 2
        else -> null
    }

    @SuppressLint("MissingPermission")
    fun sendOfficialCodecProfile(codec: Int, bitDepth: Int): Boolean {
        if (codecRemoteWireValue(codec) == null) {
            appendEvent("TCS20.15R15 CODEC unsupported logical profile=$codec")
            return false
        }
        if (bitDepth !in 0..2) {
            appendEvent("TCS19.11.9 CODEC unsupported bitDepth=$bitDepth")
            return false
        }
        if (!canQueueOfficialCodecCommand(codec, bitDepth)) return false
        val wireCodec = codecRemoteWireValue(codec) ?: return false
        val proto = buildRecordCodecProfileProto(wireCodec, bitDepth)
        val label = "Codec ${codecProfileLabel(codec)}"
        appendEvent(
            "TCS20.15R15 CODEC NONREBOOT SET cmd=0x${REMOTE_RECORD_NON_REBOOT_CODEC_PROFILE_SET.toString(16)} " +
                "packetId=0 m_isLocal=0 logical=$codec wire=$wireCodec bitDepth=$bitDepth proto=${proto.toHex()}"
        )
        return enqueueOfficialRemoteCommandProtoPayload(
            commandId = REMOTE_RECORD_NON_REBOOT_CODEC_PROFILE_SET,
            proto = proto,
            purpose = "remote-codec-profile-set:$codec:$bitDepth",
            label = label,
            local = false
        )
    }

    /**
     * Restart-capable setter recovered from the macOS CodecProfileSet class.
     * It is intentionally not exposed by the normal Android Record Codec card.
     * Normal user selection must go through 0x013F first.
     */
    @SuppressLint("MissingPermission")
    fun activateOfficialCodecProfile(codec: Int, bitDepth: Int): Boolean {
        if (codecRemoteWireValue(codec) == null || bitDepth !in 0..2) {
            appendEvent("TCS20.15R15 CODEC RESTART SET invalid profile=$codec bitDepth=$bitDepth")
            return false
        }
        if (!canQueueOfficialCodecCommand(codec, bitDepth)) return false
        val wireCodec = codecRemoteWireValue(codec) ?: return false
        val proto = buildRecordCodecProfileProto(wireCodec, bitDepth)
        val label = "Restart codec ${codecProfileLabel(codec)}"
        appendEvent(
            "TCS20.15R15 CODEC RESTART SET cmd=0x${REMOTE_RECORD_CODEC_PROFILE_SET.toString(16)} " +
                "packetId=0 m_isLocal=0 logical=$codec wire=$wireCodec bitDepth=$bitDepth proto=${proto.toHex()}"
        )
        return enqueueOfficialRemoteCommandProtoPayload(
            commandId = REMOTE_RECORD_CODEC_PROFILE_SET,
            proto = proto,
            purpose = "remote-codec-profile-activate:$codec:$bitDepth",
            label = label,
            local = false
        )
    }

    /**
     * macOS confirm/readback path. CodecProfileGet has its own typed initializer
     * and receives codec + bitDepth, then returns RecordCodecProfileMsg. We query
     * the tuple just requested and accept only the tuple actually reported by the
     * recorder as hardware truth.
     */
    @SuppressLint("MissingPermission")
    private fun queueOfficialCodecProfileGet(codec: Int, bitDepth: Int, source: String): Boolean {
        val wireCodec = codecRemoteWireValue(codec) ?: return false
        if (bitDepth !in 0..2) return false
        if (!canQueueOfficialCodecCommand(codec, bitDepth)) return false

        // binary-confirmed macOS constructor parity:
        // CodecProfileGet(0x013A) is NOT an empty GET. The official controller
        // passes codec + codecBitdepth into RecordCodecProfileMsg, uses packetId 0,
        // and sends it as a non-local RemoteApi AtomMessage.
        val proto = buildRecordCodecProfileProto(wireCodec, bitDepth)
        val packetId = 0
        val body = buildOfficialAtomMessageBodyWithProtoPayload(packetId, REMOTE_RECORD_CODEC_PROFILE_GET, proto)
        val packet = PendingRemotePacket(
            body = body,
            purpose = "remote-codec-profile-get:$codec:$bitDepth:$source",
            label = "Codec readback",
            commandId = REMOTE_RECORD_CODEC_PROFILE_GET,
            packetId = packetId,
            expectedResponseId = REMOTE_RECORD_CODEC_PROFILE_GET
        )
        pendingCodecReadbackExpected = codec to bitDepth
        pendingCodecReadbackSource = source
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = if (tcsPacketRemoteCredit) "sending" else "requesting-credit",
            available = true,
            lastCommand = packet.label,
            lastCommandId = packet.commandId,
            lastStatus = "Checking recorder codec state",
            lastError = null
        )
        appendEvent(
            "TCS20.15R15 CODEC READBACK TX typed=1 cmd=0x${REMOTE_RECORD_CODEC_PROFILE_GET.toString(16)} " +
                "source=$source expected=$codec/$bitDepth packetId=0 payloadLen=${proto.size} proto=${proto.toHex()} m_isLocal=0 body=${body.toHex()}"
        )
        val queued = queueRemotePacket18_8(packet, local = false)
        if (!queued) {
            pendingCodecReadbackExpected = null
            pendingCodecReadbackSource = null
            return false
        }
        codecReadbackTimeout?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            val expected = pendingCodecReadbackExpected ?: return@Runnable
            appendEvent("TCS19.12.10 CODEC READBACK TIMEOUT expected=${expected.first}/${expected.second} source=${pendingCodecReadbackSource ?: source}")
            pendingCodecReadbackExpected = null
            pendingCodecReadbackSource = null
            publishInputCodecCollector(source = "codec-timeout")
        }
        codecReadbackTimeout = task
        mainHandler.postDelayed(task, CODEC_GRANULAR_RESPONSE_TIMEOUT_MS)
        return true
    }

    private fun scheduleOfficialCodecReadback(
        codec: Int,
        bitDepth: Int,
        source: String,
        delayMs: Long,
        attempt: Int = 0
    ) {
        mainHandler.postDelayed({
            if (pendingRemotePacket == null) {
                if (!queueOfficialCodecProfileGet(codec, bitDepth, source) && attempt < 4) {
                    appendEvent("TCS19.11.9 CODEC VERIFY retry=${attempt + 1} source=$source expected=$codec/$bitDepth")
                    scheduleOfficialCodecReadback(codec, bitDepth, source, 140L, attempt + 1)
                }
            } else if (attempt < 4) {
                appendEvent("TCS19.11.9 CODEC VERIFY busy retry=${attempt + 1} source=$source")
                scheduleOfficialCodecReadback(codec, bitDepth, source, 140L, attempt + 1)
            } else {
                appendEvent("TCS19.11.9 CODEC VERIFY GIVEUP source=$source expected=$codec/$bitDepth")
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    lastStatus = "Codec command sent, but automatic readback could not obtain the PacketLink slot",
                    lastError = "Codec state remains unconfirmed; retry Apply when the recorder is idle."
                )
            }
        }, delayMs)
    }

    private fun buildRecordCodecProfileProto(codecWire: Int, bitDepth: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (codecWire != 0) {
            out.write(0x08)
            out.writeUnsignedVarint(codecWire)
        }
        if (bitDepth != 0) {
            out.write(0x10)
            out.writeUnsignedVarint(bitDepth)
        }
        return out.toByteArray()
    }

    @SuppressLint("MissingPermission")
    private fun canQueueOfficialCodecCommand(codec: Int, bitDepth: Int): Boolean {
        if (!_tcsPairingState.value.acceptWritten) {
            appendEvent("TCS19.11.9 CODEC blocked before accepted pairing profile=$codec bitDepth=$bitDepth")
            return false
        }
        if (!_tcsRemoteState.value.available && !openOfficialTcsRemoteLink(autoProbe = true)) {
            appendEvent("TCS19.11.9 CODEC blocked: PacketLink unavailable profile=$codec bitDepth=$bitDepth")
            return false
        }
        if (!packetLink18Primed && !packetLink18.peerCredit()) {
            appendEvent("TCS19.11.9 CODEC blocked: PacketLink not primed profile=$codec bitDepth=$bitDepth")
            return false
        }
        if (pendingRemotePacket != null) {
            appendEvent("TCS19.11.9 CODEC busy: previous RemoteApi command is still active")
            return false
        }
        return true
    }

    private fun codecProfileLabel(codec: Int): String = when (codec) {
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
        else -> "Profile $codec"
    }

    private fun codecBitDepthLabel(bitDepth: Int): String = when (bitDepth) {
        0 -> "10-bit 4:2:2"
        1 -> "8-bit 4:2:2"
        2 -> "8-bit 4:2:0"
        else -> "Format $bitDepth"
    }

    private fun safeAreaLabel(mode: Int): String = when (mode) {
        0 -> "Safe Area Off"
        1 -> "Safe Area Action"
        else -> "Safe Area Title"
    }

    private fun anamorphicLabel(mode: Int): String = when (mode) {
        0 -> "Off"
        1 -> "2x 4:3"
        2 -> "2x"
        3 -> "1.5x"
        4 -> "1.33x"
        5 -> "1.8x"
        6 -> "1.25x"
        7 -> "1.79x"
        8 -> "1.65x"
        9 -> "1.6x"
        else -> "Mode $mode"
    }

    @SuppressLint("MissingPermission")
    private fun releaseStaleMonitorPanelPendingIfReady(source: String): Boolean {
        val active = pendingRemotePacket ?: return false
        val isMonitorPanelPacket =
            active.purpose.startsWith("remote-scope-") ||
            active.purpose.startsWith("remote-monitor-focus-") ||
            active.purpose.startsWith("remote-monitor-zebra-") ||
            active.purpose.startsWith("remote-guide-")

        if (!isMonitorPanelPacket) return false
        if (packetLink18.state().toString() != "READY") return false
        if (operationInFlight) return false

        appendEvent(
            "TCS20.07.3 PANEL STALE RELEASE source=$source purpose=${active.purpose} " +
                "packetLink=READY transport-preserved=true"
        )
        pendingRemotePacket = null
        pendingRemoteFrameWritten = false

        if (active.purpose.startsWith("remote-scope-")) {
            scopeRefreshStage = 0
            scopeExpectedEnabled = null
            scopeExpectedMode = null
            scopeExpectedSize = null
            scopeExpectedOpacity = null
            scopeExpectedBrightness = null
            _monitorScopeState.value = _monitorScopeState.value.copy(
                pendingKey = null,
                status = "Scope controls ready"
            )
        }
        if (active.purpose.startsWith("remote-guide-")) {
            _monitorGuideState.value = _monitorGuideState.value.copy(
                pendingKey = null,
                status = "Guide controls ready"
            )
        }
        if (active.purpose.startsWith("remote-monitor-focus-")) {
            _monitorFocusAssist.value = _monitorFocusAssist.value.copy(
                pendingKey = null,
                status = "Focus controls ready"
            )
        }
        if (active.purpose.startsWith("remote-monitor-zebra-")) {
            _monitorZebraState.value = _monitorZebraState.value.copy(
                pendingKey = null,
                status = "Zebra controls ready"
            )
        }
        return true
    }

    private fun canQueueOfficialScopeCommand(control: String): Boolean {
        if (!_tcsPairingState.value.acceptWritten) {
            appendEvent("TCS19.4 SCOPES blocked before accepted pairing control=$control")
            return false
        }
        if (!_tcsRemoteState.value.available && !openOfficialTcsRemoteLink(autoProbe = true)) {
            appendEvent("TCS19.4 SCOPES blocked: PacketLink unavailable control=$control")
            return false
        }
        if (!packetLink18Primed && !packetLink18.peerCredit()) {
            appendEvent("TCS19.4 SCOPES blocked: PacketLink not primed control=$control")
            return false
        }
        if (pendingRemotePacket != null) {
            releaseStaleMonitorPanelPendingIfReady("scope:$control")
        }
        if (pendingRemotePacket != null) {
            appendEvent("TCS19.4 SCOPES busy: previous RemoteApi command is still active")
            return false
        }
        return true
    }

    private fun cancelDiagnosticFollowUp() {
        pendingDiagnosticFollowUpTask?.let { mainHandler.removeCallbacks(it) }
        pendingDiagnosticFollowUpTask = null
        pendingDiagnosticFollowUpCommand = null
    }

    private fun scheduleDiagnosticFollowUp(command: String, attempt: Int = 0) {
        pendingDiagnosticFollowUpTask?.let { mainHandler.removeCallbacks(it) }
        pendingDiagnosticFollowUpCommand = command

        val delayMs = if (attempt == 0) 1400L else 350L
        val task = Runnable {
            pendingDiagnosticFollowUpTask = null

            if (pendingDiagnosticFollowUpCommand != command) {
                return@Runnable
            }

            if (!_tcsPairingState.value.acceptWritten || !_tcsRemoteState.value.available) {
                if (attempt < 8) {
                    appendEvent(
                        "TCS18.9.1 MODE FOLLOW-UP waiting command=$command attempt=${attempt + 1} " +
                            "transportAvailable=${_tcsRemoteState.value.available}"
                    )
                    scheduleDiagnosticFollowUp(command, attempt + 1)
                } else {
                    pendingDiagnosticFollowUpCommand = null
                    appendEvent(
                        "TCS18.9.1 MODE FOLLOW-UP stopped command=$command: transport did not become ready"
                    )
                }
                return@Runnable
            }

            val queued = sendOfficialRemoteCommand(command)
            if (queued) {
                pendingDiagnosticFollowUpCommand = null
                appendEvent(
                    "TCS18.9.1 MODE FOLLOW-UP queued command=$command after official mode-prep " +
                        "attempt=$attempt apiVerified=$remoteApiVerified"
                )
            } else if (attempt < 8) {
                appendEvent(
                    "TCS18.9.1 MODE FOLLOW-UP PacketLink busy command=$command attempt=${attempt + 1}; retrying"
                )
                scheduleDiagnosticFollowUp(command, attempt + 1)
            } else {
                pendingDiagnosticFollowUpCommand = null
                appendEvent(
                    "TCS18.9.1 MODE FOLLOW-UP failed command=$command after retries"
                )
            }
        }

        pendingDiagnosticFollowUpTask = task
        mainHandler.postDelayed(task, delayMs)
    }
    @SuppressLint("MissingPermission")
    fun sendOfficialRemoteCommand(command: String): Boolean {
        if (!_tcsPairingState.value.acceptWritten) {
            appendEvent("TCS18.5 REMOTE command blocked before accepted pairing: $command")
            return false
        }
        if (!_tcsRemoteState.value.available && !openOfficialTcsRemoteLink(autoProbe = true)) return false
        // 18.9.1.2: remote.linked is no longer the PacketLink-prime test.
        // Use actual PacketLink transport evidence instead. The current Shogun
        // log can be phase=transport-ready with peerCredit=true while RemoteApi
        // remains unverified, and commands must still be allowed for diagnosis.
        if (!packetLink18Primed && !packetLink18.peerCredit()) {
            appendEvent(
                "TCS18.9.1.2 REMOTE blocked: PacketLink has neither prime completion nor peer credit: $command"
            )
            return false
        }
        appendEvent(
            "TCS18.9.1.2 TRANSPORT GATE PASS command=$command " +
                "primed=$packetLink18Primed peerCredit=${packetLink18.peerCredit()} apiVerified=$remoteApiVerified"
        )
        // The official 10 ms PacketLink process loop means a harmless empty
        // write/read tick can be in flight at almost any moment. Do NOT reject
        // a user button because of that background tick. A real RemoteApi
        // command is blocked only when another command payload is outstanding;
        // enqueueRemoteDataFrame places the user command ahead of queued ticks.
        if (pendingRemotePacket != null) {
            appendEvent("TCS18.5 REMOTE busy: previous RemoteApi command is still active")
            return false
        }
        return when (command) {
            "prepare-record-mode" -> enqueueOfficialRemoteCommand(
                REMOTE_PLAY_REBOOT_TO_RECORD,
                "remote-prepare-record-mode",
                "Prepare Record Mode"
            )
            "prepare-playback-mode" -> enqueueOfficialRemoteCommand(
                REMOTE_RECORD_REBOOT_TO_PLAYBACK,
                "remote-prepare-playback-mode",
                "Prepare Playback Mode"
            )
            "record-start" -> enqueueOfficialRemoteCommand(
                REMOTE_RECORD_START,
                "remote-record-start",
                "Record Start"
            )
            "record-stop" -> enqueueOfficialRemoteCommand(REMOTE_RECORD_STOP, "remote-record-stop", "Record Stop")
            "play" -> enqueueOfficialRemoteCommand(REMOTE_PLAY_START, "remote-play-start", "Play")
            "pause" -> enqueueOfficialRemoteCommand(REMOTE_PLAY_PAUSE, "remote-play-pause", "Pause")
            else -> {
                appendEvent("TCS18.5 REMOTE unsupported command=$command")
                false
            }
        }
    }

    /**
     * 19.12.9.2 explicit recorder-state refresh. This is intentionally safe to
     * call after the trusted Remote session is already linked. If ATT / PacketLink
     * is busy the request is deferred instead of being dropped.
     */
    @SuppressLint("MissingPermission")
    fun refreshOfficialRecorderState(): Boolean {
        if (!_tcsPairingState.value.acceptWritten || !_tcsRemoteState.value.available || !packetLink18Primed) {
            appendEvent("TCS19.12.9.2 STATE REFRESH blocked accept=${_tcsPairingState.value.acceptWritten} available=${_tcsRemoteState.value.available} primed=$packetLink18Primed")
            return false
        }
        if (remoteBootstrapActive) {
            appendEvent("TCS19.12.9.2 STATE REFRESH already-active pages=${stateSyncPages.size}/$APP_POLLING_STATE_PAGE_COUNT")
            return true
        }
        if (pendingRemotePacket != null || operationInFlight || pendingOps.isNotEmpty()) {
            if (!stateSyncRefreshPending) {
                stateSyncRefreshPending = true
                appendEvent("TCS19.12.9.2 STATE REFRESH deferred busy pending=${pendingRemotePacket?.label} op=$operationInFlight queue=${pendingOps.size}")
                mainHandler.postDelayed({
                    stateSyncRefreshPending = false
                    refreshOfficialRecorderState()
                }, 220L)
            }
            return true
        }
        appendEvent("TCS19.12.9.2 STATE MANUAL REFRESH start pages=${stateSyncPages.size}/$APP_POLLING_STATE_PAGE_COUNT")
        return startOfficialMacRemoteBootstrap()
    }

    private fun cancelStateSyncRecoveryWatchdog() {
        stateSyncRecoveryTask?.let { mainHandler.removeCallbacks(it) }
        stateSyncRecoveryTask = null
    }

    private fun scheduleStateSyncRecoveryWatchdog(reason: String, delayMs: Long = 2500L) {
        // 19.12.10: AG2 hardware proved that 0x0640..0x0649 can complete only
        // their PacketLink E0 turns without returning page payloads on this path.
        // Keep AppPolling diagnostic code available, but never retry it as a
        // state gate or let it starve granular Input/Codec reads.
        cancelStateSyncRecoveryWatchdog()
        appendEvent("TCS19.12.10 APPPOLLING OPTIONAL no-watchdog reason=$reason delay=$delayMs pages=${stateSyncPages.size}/$APP_POLLING_STATE_PAGE_COUNT")
    }

    /**
     * Remote screen bootstrap recovered directly from the official macOS
     * AtomRemote controller. The official view sends these GET/state messages
     * on connection before transport buttons are used. 18.3 skipped this
     * RemoteApi startup entirely and jumped straight to Record.Start.
     *
     * Message IDs / expected response IDs are copied from the macOS executable:
     *  0x04B0->0x04B0, ApplicationStateGet 0x0640->0x0640,
     *  Play.StateGet 0x012E->0x012C, Record.StateGet 0x0190->0x0190,
     *  then the remaining state probes used by the same Remote screen startup.
     */
    @SuppressLint("MissingPermission")
    private fun startOfficialMacRemoteBootstrap(): Boolean {
        if (remoteBootstrapActive) return true

        remoteBootstrapQueue.clear()

        // TCS19.12.17.3-R2 INPUT SESSION BOOTSTRAP PARITY
        // Exact official macOS Remote startup order.
        listOf(
            RemoteBootstrapQuery(0x04B0, 0x04B0, "DeviceInfoGet", local = false),
            RemoteBootstrapQuery(0x0640, 0x0640, "ApplicationStateGet", local = false),
            RemoteBootstrapQuery(0x012E, 0x012C, "Play.StateGet", local = false),
            RemoteBootstrapQuery(0x0190, 0x0190, "Record.StateGet", local = false),
            RemoteBootstrapQuery(0x0648, 0x0648, "InputOutputSummaryGet", local = false),
            RemoteBootstrapQuery(0x0064, 0x0064, "Input.SourceGet", local = false),
            RemoteBootstrapQuery(0x02BD, 0x02BC, "MonitorModeGet", local = true),
            RemoteBootstrapQuery(0x0135, 0x0135, "PrerollEnableGet", local = false),
            RemoteBootstrapQuery(0x0071, 0x006D, "Input.HdrStateGet", local = false),
            RemoteBootstrapQuery(0x00DD, 0x00DC, "Output.HdrStateGet", local = false),
            RemoteBootstrapQuery(0x013C, 0x013C, "Record.PulldownFormatGet", local = false),
            RemoteBootstrapQuery(0x0070, 0x006C, "Input.DeviceNameGet", local = false),
            RemoteBootstrapQuery(0x02BF, 0x02BD, "LutSlotGet", local = false),
            // INPUT14: skip unverified bulk page probes on normal startup.
            // Keep the proven MAC13 command sequence intact.
        ).forEach { remoteBootstrapQueue.addLast(it) }

        remoteBootstrapTotal = remoteBootstrapQueue.size

        stateSyncGeneration += 1
        stateSyncPages.clear()
        stateSyncPacketToPage.clear()

        publishRemoteStateSnapshot(
            cycleComplete = false,
            source = "macos-13-bootstrap-start"
        )

        remoteBootstrapActive = true
        remoteBootstrapCompleted = false
        remoteBootstrapResponses = 0

        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = "bootstrapping",
                    available = true,
                    linked = _tcsRemoteState.value.linked,
            lastStatus = "Syncing Remote state and Input pages",
            lastError = null
        )

        appendEvent(
            "INPUT12 MAC13+PAGES START count=$remoteBootstrapTotal " +
                "monitorModeLocal=1 inputCore=0648,0064,0071,0070"
        )

        return enqueueNextMacRemoteBootstrapQuery()
    }

    @SuppressLint("MissingPermission")
    private fun enqueueNextMacRemoteBootstrapQuery(): Boolean {
        if (!remoteBootstrapActive) return false
        if (pendingRemotePacket != null) return true
        val query = remoteBootstrapQueue.pollFirst()
        if (query == null) {
            // The official macOS CommandManager does not serialize UI startup on
            // one response per getter. It queues the startup requests while the
            // 10 ms PacketLink process loop keeps draining incoming state. 18.4
            // incorrectly treated E0 as the response itself. 18.5 separates the
            // two concepts: E0 completes the transport turn, while payloads are
            // collected independently during a short drain window.
            remoteBootstrapActive = false
            scheduleRemoteBootstrapSettle()
            appendEvent("TCS19.12.9 STATE SYNC FRAMES SENT count=$remoteBootstrapTotal; draining RemoteApi payloads for ${REMOTE_BOOTSTRAP_DRAIN_MS}ms")
            return true
        }
        val current = gatt ?: return false
        val writeChar = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
            ?.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false
        val appPollingStateQuery = query.commandId in REMOTE_APP_POLLING_APPLICATION_STATE_GET..REMOTE_APP_POLLING_MONITOR_ASSIST_SUMMARY_GET

        // TCS19.12.17.3-R3 SINGLE BOOTSTRAP PACKET SEQUENCE
        //
        // The official 13-command Remote startup uses one CommandManager query
        // sequence. Do not restart/split packet IDs for 0x0640 / 0x0648 merely
        // because those IDs also belong to the AppPolling namespace.
        tcsCommandSequence = if (tcsCommandSequence >= 255) 1 else tcsCommandSequence + 1
        val packetId = tcsCommandSequence

        if (query.label == "Input.SourceGet" || query.label == "Input.HdrStateGet" || query.label == "Input.DeviceNameGet") {
            input06aBootstrapPending[packetId] = Pair(query.expectedResponseId, query.label)
        }
        if (appPollingStateQuery) {
            stateSyncPacketToPage[packetId] = query.commandId
        }

        appendEvent(
            "TCS19.12.17.3-R3 MAC13 PACKET label=${query.label} " +
                "cmd=0x${query.commandId.toString(16).uppercase()} packetId=$packetId " +
                "m_isLocal=${if (query.local) 1 else 0}"
        )
        val body = buildOfficialAtomMessageBody(packetId, query.commandId)
        val purpose = "remote-bootstrap-${query.commandId.toString(16)}"
        val packet = PendingRemotePacket(
            body = body,
            purpose = purpose,
            label = query.label,
            commandId = query.commandId,
            packetId = packetId,
            expectedResponseId = query.expectedResponseId,
            bootstrap = true
        )
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = "bootstrapping",
                    available = true,
                    linked = _tcsRemoteState.value.linked,
            lastCommand = query.label,
            lastCommandId = query.commandId,
            lastStatus = "State sync ${remoteBootstrapTotal - remoteBootstrapQueue.size}/$remoteBootstrapTotal: ${query.label}"
        )
        appendEvent("TCS19.10 BOOTSTRAP QUERY label=${query.label} cmd=${query.commandId} expected=${query.expectedResponseId} packetId=$packetId credit=$tcsPacketRemoteCredit body=${body.toHex()} m_isLocal=${if (query.local) 1 else 0}")        // TCS19.12.17.9 INPUT READ TRANSPORT RESTORE
        // AtomMessage m_isLocal remains query.local (official MAC13 uses false).
        // PacketLink transport locality is restored to the pre-17.8 route.
        return queueRemotePacket18_8(packet, local = query.local)
    }

    private fun buildOfficialAtomMessageBody(packetId: Int, commandId: Int): ByteArray = byteArrayOf(
        (packetId and 0xFF).toByte(),
        0x00, 0x00,
        (commandId and 0xFF).toByte(),
        ((commandId ushr 8) and 0xFF).toByte()
    )

    /**
     * AtomMessage wire body:
     *   packetId U8 + payloadLen LE16 + messageType LE16 + protobuf payload
     *
     * macOS setter constructors instantiate typed AtomProtobuf messages and
     * serialize them with SwiftProtobuf before assigning AtomMessage.payload.
     * The recovered Monitor Assist / Scopes scalar messages use protobuf field
     * #1 with wire type varint:
     *
     *   protobuf payload = 08 <value>
     *   payloadLen       = 02 00
     *
     * The recorder log from Fix 1 returned messageType 0xFFFF for raw-U8
     * payloads such as "... 6E 02 01". This builder emits the macOS-equivalent
     * scalar protobuf representation instead.
     */
    private fun buildOfficialAtomMessageBodyWithProtoVarintField1(
        packetId: Int,
        commandId: Int,
        value: Int
    ): ByteArray {
        require(value in 0..0x7F) { "RemoteApi scalar protobuf value must fit one-byte varint" }

        // SwiftProtobuf omits a proto3 scalar/enum field when its value is the
        // default zero. The previous Android encoder forced "08 00", which the
        // AG2 rejected for SafeAreaSizeSet(0) and ZoomLevelSet(0).
        val proto = if (value == 0) {
            byteArrayOf()
        } else {
            byteArrayOf(0x08, (value and 0x7F).toByte())
        }
        return buildOfficialAtomMessageBodyWithProtoPayload(packetId, commandId, proto)
    }

    /**
     * Boolean RemoteApi messages on the recorder are presence-sensitive.
     * Hardware regression on 19.11.1 proved that OFF for Focus Assist is
     * rejected when the protobuf field is omitted. Keep the boolean field
     * explicitly present for both false and true: 08 00 / 08 01.
     */
    private fun buildOfficialAtomMessageBodyWithProtoBoolField1(
        packetId: Int,
        commandId: Int,
        enabled: Boolean
    ): ByteArray = buildOfficialAtomMessageBodyWithProtoPayload(
        packetId,
        commandId,
        byteArrayOf(0x08, if (enabled) 0x01 else 0x00)
    )

    private fun buildFrameGuideFormatProto(
        grid3x3: Boolean,
        frameGuideEnabled: Boolean,
        frameGuideMode: Int,
        customFrameGuideRatio: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // SwiftProtobuf omits default false/zero scalar values.
        if (grid3x3) { out.write(0x08); out.write(0x01) }          // field 1
        if (frameGuideEnabled) { out.write(0x10); out.write(0x01) } // field 2
        if (frameGuideMode != 0) { out.write(0x18); out.write(frameGuideMode and 0x7F) } // field 3
        if (customFrameGuideRatio != 0) { out.write(0x20); out.write(customFrameGuideRatio and 0x7F) } // field 4
        return out.toByteArray()
    }

    private fun buildOfficialAtomMessageBodyWithProtoPayload(
        packetId: Int,
        commandId: Int,
        proto: ByteArray
    ): ByteArray {
        require(proto.size <= 0xFFFF)
        return byteArrayOf(
            (packetId and 0xFF).toByte(),
            (proto.size and 0xFF).toByte(),
            ((proto.size ushr 8) and 0xFF).toByte(),
            (commandId and 0xFF).toByte(),
            ((commandId ushr 8) and 0xFF).toByte()
        ) + proto
    }

    @SuppressLint("MissingPermission")
    private fun enqueueOfficialRemoteCommandProtoPayload(
        commandId: Int,
        proto: ByteArray,
        purpose: String,
        label: String,
        local: Boolean = true
    ): Boolean {
        val current = gatt ?: return false
        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return false
        service.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false
        val packetId = 0
        val body = buildOfficialAtomMessageBodyWithProtoPayload(packetId, commandId, proto)
        val packet = PendingRemotePacket(body, purpose, label, commandId, packetId)
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = if (tcsPacketRemoteCredit) "sending" else "requesting-credit",
            available = true,
            lastCommand = label,
            lastCommandId = commandId,
            lastTxHex = null,
            lastStatus = if (tcsPacketRemoteCredit) "Peer credit already available" else "Requesting official PacketLink peer credit",
            lastError = null
        )
        appendEvent("TCS19.10 PROTOBUF PAYLOAD QUEUE label=$label cmd=0x${commandId.toString(16)} payloadLen=${proto.size} proto=${proto.toHex()} body=${body.toHex()} m_isLocal=${if (local) 1 else 0}")
        return queueRemotePacket18_8(packet, local = local)
    }

    @SuppressLint("MissingPermission")
        // TCS19.12.16.1-R1 KNOWN_GOOD_USER_COMMAND_ROUTE: one-shot/setter RemoteApi sends use m_isLocal=true
    private fun enqueueOfficialRemoteCommandProtoBool1(
        commandId: Int,
        enabled: Boolean,
        purpose: String,
        label: String
    ): Boolean {
        val current = gatt ?: return false
        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return false
        service.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false
        val packetId = 0
        val body = buildOfficialAtomMessageBodyWithProtoBoolField1(packetId, commandId, enabled)
        val packet = PendingRemotePacket(body, purpose, label, commandId, packetId)
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = if (tcsPacketRemoteCredit) "sending" else "requesting-credit",
            available = true,
            lastCommand = label,
            lastCommandId = commandId,
            lastTxHex = null,
            lastStatus = if (tcsPacketRemoteCredit) "Peer credit already available" else "Requesting official PacketLink peer credit",
            lastError = null
        )
        appendEvent(
            "TCS19.11.2 BOOL SETTER QUEUE label=$label cmd=0x${commandId.toString(16)} " +
                "proto=08${if (enabled) "01" else "00"} body=${body.toHex()}"
        )
        return queueRemotePacket18_8(packet, local = true)
    }

    @SuppressLint("MissingPermission")
    private fun enqueueOfficialRemoteCommandProtoExplicitVarint1(
        commandId: Int,
        value: Int,
        purpose: String,
        label: String
    ): Boolean {
        val current = gatt ?: return false
        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return false
        service.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false
        require(value in 0..0x7F)
        val packetId = 0
        val proto = byteArrayOf(0x08, (value and 0x7F).toByte())
        val body = buildOfficialAtomMessageBodyWithProtoPayload(packetId, commandId, proto)
        val packet = PendingRemotePacket(body, purpose, label, commandId, packetId)
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = if (tcsPacketRemoteCredit) "sending" else "requesting-credit",
            available = true,
            lastCommand = label,
            lastCommandId = commandId,
            lastTxHex = null,
            lastStatus = if (tcsPacketRemoteCredit) "Peer credit already available" else "Requesting official PacketLink peer credit",
            lastError = null
        )
        appendEvent(
            "TCS19.11.2 ENUM SETTER QUEUE label=$label cmd=0x${commandId.toString(16)} " +
                "proto=08${"%02X".format(value and 0x7F)} body=${body.toHex()}"
        )
        return queueRemotePacket18_8(packet, local = true)
    }

    @SuppressLint("MissingPermission")
    private fun enqueueOfficialRemoteCommandProtoVarint1(
        commandId: Int,
        value: Int,
        purpose: String,
        label: String
    ): Boolean {
        val current = gatt ?: return false
        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return false
        service.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false

        // macOS RemoteApi setters use packet id 0 + m_isLocal=true.
        val packetId = 0
        val body = buildOfficialAtomMessageBodyWithProtoVarintField1(packetId, commandId, value)
        val packet = PendingRemotePacket(body, purpose, label, commandId, packetId)
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = if (tcsPacketRemoteCredit) "sending" else "requesting-credit",
            available = true,
            lastCommand = label,
            lastCommandId = commandId,
            lastTxHex = null,
            lastStatus = if (tcsPacketRemoteCredit) "Peer credit already available" else "Requesting official PacketLink peer credit",
            lastError = null
        )
        val protoHex = if (value == 0) "" else "08${"%02X".format(value and 0x7F)}"
        appendEvent(
            "TCS19.11.1 PROTOBUF SETTER QUEUE label=$label cmd=0x${commandId.toString(16)} " +
                "payloadLen=${if (value == 0) 0 else 2} proto=$protoHex body=${body.toHex()}"
        )
        return queueRemotePacket18_8(packet, local = true)
    }

    @SuppressLint("MissingPermission")
    private fun enqueueOfficialRemoteCommand(commandId: Int, purpose: String, label: String): Boolean {
        val current = gatt ?: return false
        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return false
        val writeChar = service.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false
        // Official macOS AtomRemote constructor evidence: RemoteApi Record/Play
        // AtomMessage subclasses call AtomMessage(m_isLocal: true). The local
        // PacketLink bit (0x08) must therefore be present on these packets.
        // One-shot Record/Stop/Play/Pause still use packetId = 0.
        val packetId = 0
        val body = buildOfficialAtomMessageBody(packetId, commandId)
        val packet = PendingRemotePacket(body, purpose, label, commandId, packetId)
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = if (tcsPacketRemoteCredit) "sending" else "requesting-credit",
            available = true,
            lastCommand = label,
            lastCommandId = commandId,
            lastTxHex = null,
            lastStatus = if (tcsPacketRemoteCredit) "Peer credit already available" else "Requesting official PacketLink peer credit",
            lastError = null
        )

        return queueRemotePacket18_8(packet, local = true)
    }

    /** Buffer an AtomMessage exactly like TCSPacketLinkSendPacket. The 10 ms
     * process tick decides when it may actually enter the BLE write/read turn. */
    @SuppressLint("MissingPermission")
    private fun queueRemotePacket18_8(packet: PendingRemotePacket, local: Boolean): Boolean {
        if (!packetLink18.sendPacket(local, packet.body)) {
            if (packet.purpose.startsWith("r2665-audio-set-trial:phones:")) {
                android.util.Log.w("AtomRemoteAudioB011", "SET_BUFFER_REJECT command=0x${packet.commandId.toString(16)} " +
                    "state=${packetLink18.state()} buffered=${packetLink18.bufferedPacketSize()} physicalWrite=false")
            }
            appendEvent("TCS18.8 PLINK BUFFER BUSY label=${packet.label} state=${packetLink18.state()} buffered=${packetLink18.bufferedPacketSize()}")
            return false
        }
        if (packet.purpose.startsWith("r2665-audio-set-trial:phones:")) {
            android.util.Log.i("AtomRemoteAudioB011", "SET_BUFFER_ACCEPT command=0x${packet.commandId.toString(16)} " +
                "body=${packet.body.toHex()} state=${packetLink18.state()} physicalWriteNotYetConfirmed=true")
        }
        if (outputR7IsTrackedPurpose(packet.purpose)) {
            outputR7Trace("BUFFER_ACCEPTED purpose=${packet.purpose} cmd=0x${packet.commandId.toString(16)} packetId=${packet.packetId} local=$local")
        }
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = if (packet.bootstrap) "bootstrapping" else "queued",
            available = true,
            lastCommand = packet.label,
            lastCommandId = packet.commandId,
            lastTxHex = packet.body.toHex(),
            lastStatus = "${packet.label} buffered in official PacketLink",
            lastError = null
        )
        val packetIdRule = when {
            packet.bootstrap && packet.commandId in REMOTE_APP_POLLING_APPLICATION_STATE_GET..REMOTE_APP_POLLING_MONITOR_ASSIST_SUMMARY_GET -> "official-app-polling-sequence"
            packet.bootstrap -> "bootstrap-sequence"
            else -> "official-one-shot-zero"
        }
        appendEvent("TCS18.8.1 PLINK BUFFER label=${packet.label} cmd=${packet.commandId} packetId=${packet.packetId} m_isLocal=${if (local) 1 else 0} atom=${packet.body.toHex()} packetIdRule=$packetIdRule")
        // Match macOS: SendPacket only fills the PacketLink TX buffer. The 10 ms
        // TCSPacketLinkProcess timer owns the READY -> WRITING transition.
        return true
    }

    /**
     * Mirrors TCSPacketLinkProcess. Crucially, a timer tick does NOTHING while
     * state is WRITING or READING. The old Android implementation kept creating
     * manual E0 turns around in-flight commands, which was not framework parity.
     */
    @SuppressLint("MissingPermission")
    private fun drivePacketLink18_8() {
        if (!packetLinkProcessEnabled || !_tcsRemoteState.value.available) return

        // 20.03.6.10-6.13 R10:
        // Input-details refresh increased queue turnover enough to expose that
        // java.util.ArrayDeque can be modified by a GATT callback while this
        // 10ms process tick is iterating it. Treat a concurrent queue change as
        // "busy for this tick" and retry naturally on the next tick.
        val packetLinkOpQueued = try {
            pendingOps.any { op ->
                (op as? PendingOp.Read)?.purpose == PLINK18_READ_PURPOSE ||
                    (op as? PendingOp.WriteCharacteristic)?.purpose == PLINK18_WRITE_PURPOSE
            }
        } catch (_: ConcurrentModificationException) {
            appendEvent("TCS20.03.6.10-R10 PLINK QUEUE CHANGED during tick; defer one tick")
            true
        }

        if (operationInFlight || packetLinkOpQueued) return
        val current = gatt ?: return
        val writeChar = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
            ?.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return

        val subpacket = packetLink18.process() ?: return
        queuePacketLink18_8Write(current, writeChar, subpacket)
    }

    @SuppressLint("MissingPermission")
    private fun queuePacketLink18_8Write(
        current: BluetoothGatt,
        writeChar: BluetoothGattCharacteristic,
        subpacket: TcsPacketLinkTransport.OutboundSubpacket
    ) {
        pendingOps.addFirst(
            PendingOp.WriteCharacteristic(
                writeChar,
                subpacket.bytes,
                packetLinkWriteType(writeChar),
                PLINK18_WRITE_PURPOSE
            )
        )
        if (subpacket.includesPacketPayload && pendingRemotePacket?.purpose?.startsWith("r2665-audio-set-trial:phones:") == true) {
            android.util.Log.i("AtomRemoteAudioB011", "SET_DATA_ATT_QUEUED frame=${subpacket.bytes.toHex()} " +
                "command=0x${pendingRemotePacket?.commandId?.toString(16)} callbackNotYetConfirmed=true")
        }
        if (subpacket.includesPacketPayload && outputR7IsTrackedPurpose(pendingRemotePacket?.purpose)) {
            outputR7Trace("PLINK18_DATA_TX_ENQUEUED purpose=${pendingRemotePacket?.purpose} " +
                "packetId=${pendingRemotePacket?.packetId} bytes=${subpacket.bytes.toHex()}")
        }
        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            lastTxHex = subpacket.bytes.toHex(),
            lastStatus = when {
                subpacket.includesPacketPayload -> "Writing official PacketLink data subpacket"
                !packetLink18Primed -> "Priming official PacketLink"
                else -> _tcsRemoteState.value.lastStatus
            }
        )
        appendEvent(
            "TCS18.8 PLINK TX state=WRITING frame=${subpacket.bytes.toHex()} final=${subpacket.final} payloadBytes=${subpacket.payloadBytes} peerCredit=${packetLink18.peerCredit()}"
        )
        if (!operationInFlight) processNext(current)
    }

    @SuppressLint("MissingPermission")
    private fun queuePacketLink18_8Read(g: BluetoothGatt) {
        val readChar = g.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
            ?.getCharacteristic(UUID.fromString(TCS_PACKET_READ_UUID)) ?: return
        val hasPacketRead = try {
            pendingOps.any { (it as? PendingOp.Read)?.purpose == PLINK18_READ_PURPOSE }
        } catch (_: ConcurrentModificationException) {
            appendEvent("TCS20.05.8 PLINK READ QUEUE CHANGED; defer read scheduling")
            true
        }
        if (!hasPacketRead) {
            pendingOps.addFirst(PendingOp.Read(readChar, PLINK18_READ_PURPOSE))
        }
        if (!operationInFlight) processNext(g)
    }

    @SuppressLint("MissingPermission")
    private fun handlePacketLink18_8WritePurpose(
        g: BluetoothGatt,
        op: PendingOp.WriteCharacteristic,
        status: Int
    ): Boolean {
        if (op.purpose != PLINK18_WRITE_PURPOSE) return false
        if (status != BluetoothGatt.GATT_SUCCESS) {
            packetLink18.onWriteFailed()
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                phase = "packet-ready",
                lastStatus = "PacketLink write failed; transport retained",
                lastError = "cxtic13 write failed status=$status"
            )
            appendEvent("TCS18.8 PLINK WRITE FAIL status=$status; returning transport to READY")
            return true
        }
        if (pendingRemotePacket?.purpose?.startsWith("r2665-audio-set-trial:phones:") == true && op.value.size > 1) {
            android.util.Log.i("AtomRemoteAudioB011", "SET_ATT_WRITE_CALLBACK status=$status frame=${op.value.toHex()} " +
                "gattAccepted=true recorderAppliedUnverified=true")
        }
        // R27.06: Mic/Line evidence only; does not change PacketLink write flow.
        r2706MicOnAttWrite(op.value)
        // R26.17: Audio-only confirmation of a physical ATT packet write; passive.
        if (op.value.size > 1) audioR26OnGATTWritten()
        // OUTPUT R8: successful ATT callback for the *active* PLINK18 route.
        // The previous GATT_WRITE_OK marker was installed on the legacy remote-data path.
        if (op.value.size > 1 && outputR7IsTrackedPurpose(pendingRemotePacket?.purpose)) {
            outputR7Trace("PLINK18_GATT_DATA_WRITE_OK purpose=${pendingRemotePacket?.purpose} " +
                "packetId=${pendingRemotePacket?.packetId} bytes=${op.value.toHex()}")
        }

        when (packetLink18.onWriteComplete()) {
            TcsPacketLinkTransport.WriteCompleteAction.Read -> {
                appendEvent("TCS18.8 PLINK WRITE COMPLETE final=1 -> state=READING")
                queuePacketLink18_8Read(g)
            }
            TcsPacketLinkTransport.WriteCompleteAction.WriteNext -> {
                val writeChar = g.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
                    ?.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return true
                val next = packetLink18.continueWrite()
                if (next != null) queuePacketLink18_8Write(g, writeChar, next)
            }
            TcsPacketLinkTransport.WriteCompleteAction.Ignore -> Unit
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun handlePacketLink18_8ReadPurpose(
        g: BluetoothGatt,
        op: PendingOp.Read,
        value: ByteArray,
        status: Int
    ): Boolean {
        if (op.purpose != PLINK18_READ_PURPOSE) return false
        if (status != BluetoothGatt.GATT_SUCCESS) {
            packetLink18.onReadFailed()
            appendEvent("TCS18.8 PLINK READ FAIL status=$status; returning state=READY")
            return true
        }

        val result = packetLink18.onReadSubpacket(value)
        tcsPacketRemoteCredit = result.peerCredit
        if (outputR7IsTrackedPurpose(pendingRemotePacket?.purpose)) {
            val completedPayload = result.packet
            if (completedPayload != null) {
                outputR7Trace("PLINK18_PAYLOAD_RX purpose=${pendingRemotePacket?.purpose} " +
                    "bytes=${completedPayload.size} local=${result.packetLocal} " +
                    "frame=${value.toHex().take(160)}")
            } else if (value.size == 1 && (value[0].toInt() and 0xFF) == 0xE0) {
                outputR7Trace("PLINK18_E0_FLOW_ONLY purpose=${pendingRemotePacket?.purpose} " +
                    "packetId=${pendingRemotePacket?.packetId}")
            }
        }
        appendEvent(
            "TCS18.8 PLINK RX state=${packetLink18.state()} frame=${value.toHex()} final=${!result.needAnotherRead} peerCredit=${result.peerCredit} local=${result.packetLocal} payloadBytes=${result.packet?.size ?: 0}"
        )

        if (result.needAnotherRead) {
            queuePacketLink18_8Read(g)
            return true
        }

        if (!packetLink18Primed) {
            packetLink18Primed = true
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                phase = "bootstrapping",
                    available = true,
                    linked = _tcsRemoteState.value.linked,
                lastStatus = "PacketLink primed; syncing macOS RemoteApi state",
                lastError = null
            )
            appendEvent("TCS18.8 PLINK PRIME COMPLETE first F0/read turn -> READY peerCredit=${result.peerCredit}")
            val syncStarted = startOfficialMacRemoteBootstrap()
            appendEvent("TCS19.12.9.2 STATE PRIME TRIGGER started=$syncStarted")
            scheduleStateSyncRecoveryWatchdog("prime", 4500L)
        }

        // R27.07: read-only pre-demux observation of complete assembled packets.
        // Does NOT change existing demux acceptance, credits or packet ownership.
        result.packet?.let { r2707MicOnAssembled(it, result.packetLocal) }
        result.packet?.let { handlePacketLink18_8Payload(it, result.packetLocal) }

        if (result.txPacketCompletedThisTurn) {
            val sent = pendingRemotePacket
            r2706MicOnDelivered(sent)
            audioR26OnTransportComplete(sent)
            pendingRemotePacket = null
            pendingRemoteFrameWritten = false
            if (sent != null) {
                appendEvent("TCS18.9.1 TRANSPORT DELIVERED label=${sent.label} cmd=${sent.commandId}; PacketLink transport complete; recorder action unconfirmed")
                // INPUT06F: This is the transport-completion boundary for a
                // read-only Input core GET. Do not affect other commands.
                if (sent.purpose.startsWith("remote-input-route-matrix:") &&
                    inputCollectorRunning && activeInputProbe?.commandId == sent.commandId) {
                    appendEvent("INPUT16 GET DELIVERED label=${sent.label} attempt=$inputProbeVariant purpose=${sent.purpose}; waiting-typed-RX")
                    armInputProbeSemanticTimeout(activeInputProbe!!, inputProbeVariant)
                }
                if (sent.bootstrap) {
                    enqueueNextMacRemoteBootstrapQuery()
                } else {
                    val preserveRecorderResult = sent.purpose == "remote-monitor-zoom-level-list" ||
                        sent.purpose == "remote-monitor-zoom-level-get" ||
                        sent.purpose == "remote-monitor-zoom-box-get" ||
                        sent.purpose.startsWith("remote-monitor-zoom-pan:") ||
                        sent.purpose == "remote-input-output-summary-get" ||
                        sent.purpose.startsWith("remote-monitor-zoom-level-set:") ||
                        sent.purpose.startsWith("remote-codec-profile-set:") ||
                        sent.purpose.startsWith("remote-codec-profile-activate:") ||
                        sent.purpose.startsWith("remote-codec-profile-get:") ||
                        sent.purpose.startsWith("remote-guide-grid-format-get:") ||
                        sent.purpose.startsWith("remote-guide-grid-set:") ||
                        sent.purpose.startsWith("remote-guide-safe-area-set:") ||
                        sent.purpose.startsWith("remote-monitor-mode:") ||
                        sent.purpose.startsWith("remote-monitor-mode-get:")
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        phase = if (remoteApiVerified) "linked" else "transport-ready",
                        available = true,
                        linked = true,
                        lastStatus = when {
                            sent.purpose == "remote-input-output-summary-get" ->
                                "Input / Format refresh sent; waiting for recorder-reported state"
                            preserveRecorderResult ->
                                _tcsRemoteState.value.lastStatus ?: "${sent.label} command sent"
                            else -> "${sent.label} command sent"
                        },
                        lastError = if (preserveRecorderResult) _tcsRemoteState.value.lastError else null
                    )
                }

                if (sent.purpose == "remote-monitor-zoom-level-list") {
                    val deferred = deferredZoomLevelAfterCapability
                    deferredZoomLevelAfterCapability = null
                    if (deferred != null) {
                        zoomRefreshActive = false
                        zoomRefreshStage = 0
                        zoomRefreshAfterSet = true
                        mainHandler.postDelayed({
                            if (pendingRemotePacket == null) {
                                sendOfficialMonitorZoomLevel(deferred)
                            } else {
                                pendingZoomLevelAfterCapability = deferred
                            }
                        }, 70L)
                    } else if (zoomRefreshActive) {
                        zoomRefreshStage = 2
                        mainHandler.postDelayed({
                            if (pendingRemotePacket == null && zoomRefreshActive && zoomRefreshStage == 2) {
                                if (!queueOfficialZoomLevelGet()) {
                                    zoomRefreshActive = false
                                    zoomRefreshStage = 0
                                }
                            }
                        }, 55L)
                    }
                }

                if (sent.purpose == "remote-monitor-zoom-level-get" && zoomRefreshActive) {
                    val deferredAfterRead = deferredZoomLevelAfterCapability
                    deferredZoomLevelAfterCapability = null
                    if (deferredAfterRead != null) {
                        zoomRefreshActive = false
                        zoomRefreshStage = 0
                        zoomRefreshAfterSet = true
                        mainHandler.postDelayed({
                            if (pendingRemotePacket == null) {
                                appendEvent("TCS19.11.8 ZOOM LEVEL READ -> setter target=$deferredAfterRead")
                                sendOfficialMonitorZoomLevel(deferredAfterRead)
                            }
                        }, 60L)
                    } else {
                        zoomRefreshStage = 3
                        mainHandler.postDelayed({
                            if (pendingRemotePacket == null && zoomRefreshActive && zoomRefreshStage == 3) {
                                if (!queueOfficialZoomBoxGet()) {
                                    zoomRefreshActive = false
                                    zoomRefreshStage = 0
                                }
                            }
                        }, 55L)
                    }
                }

                if (sent.purpose == "remote-monitor-zoom-box-get") {
                    zoomRefreshActive = false
                    zoomRefreshStage = 0
                    if (queuedLiveZoomPanX != null || queuedLiveZoomPanY != null) {
                        appendEvent("TCS20.13.4R14 ZOOM PAN PUMP DRAIN after=ZoomBoxGet")
                        kickLiveZoomPanPump(0L)
                    }
                }

                // 9-Grid remains on its frozen readback path. Frame Guide uses the
                // physically-proven R16.2 bounded 0x0276 readback pump so competing
                // 95/140ms reads cannot fight for the one PacketLink slot.
                if (sent.purpose.startsWith("remote-guide-grid-set:")) {
                    appendEvent("TCS20.13.R10 GRID TRANSPORT COMPLETE purpose=${sent.purpose}; scheduling frozen format readback")
                    mainHandler.postDelayed({
                        if (pendingRemotePacket == null) refreshOfficialGuidesState()
                    }, 140L)
                }

                if (sent.purpose.startsWith("remote-guide-frame-format-set:") ||
                    sent.purpose.startsWith("remote-guide-format-set:")) {
                    val target = if (sent.purpose.startsWith("remote-guide-frame-format-set:")) {
                        sent.purpose.split(':').getOrNull(2)?.toIntOrNull()
                    } else {
                        sent.purpose.substringAfterLast(':').toIntOrNull()
                    }
                    val expected = target ?: frameGuideExpectedFormat
                    val gen = frameGuideReadbackGeneration
                    appendEvent("TCS20.15R16.2 FRAME GUIDE TRANSPORT COMPLETE purpose=${sent.purpose} target=${expected ?: -1} gen=$gen")
                    if (expected != null) scheduleFrameGuideFormatReadback(expected, gen)
                    scheduleMonitorAssistSummaryFallback("frame-guide-set", 420L)
                }

                if (sent.purpose.startsWith("remote-monitor-mode:")) {
                    val requested = sent.purpose.substringAfterLast(':').toIntOrNull()
                    if (requested != null) {
                        mainHandler.postDelayed({
                            if (pendingRemotePacket == null) {
                                appendEvent("TCS20.04.2 MONITOR MODE VERIFY SCHEDULED expected=$requested")
                                refreshOfficialMonitorMode(
                                    source = "setter-verify",
                                    expectedMode = requested
                                )
                            }
                        }, 120L)
                    }
                }

                if (sent.purpose.startsWith("remote-monitor-zoom-level-set:")) {
                    val expected = sent.purpose.substringAfterLast(':').toIntOrNull()
                    mainHandler.postDelayed({
                        if (pendingRemotePacket == null && expected != null) {
                            zoomExpectedLevelAfterSet = expected
                            appendEvent("TCS20.04.5 ZOOM VERIFY SCHEDULED expected=$expected")
                            if (!queueOfficialZoomLevelGet()) {
                                zoomExpectedLevelAfterSet = null
                                _monitorZoomCore.value = _monitorZoomCore.value.copy(
                                    pendingLevel = null,
                                    status = "Zoom readback could not start"
                                )
                            }
                        }
                    }, 140L)
                }

                if (sent.purpose.startsWith("remote-monitor-zoom-pan")) {
                    val settleGen = zoomPanSettleGeneration
                    appendEvent("TCS20.13.4R13 ZOOM PAN TRANSPORT COMPLETE purpose=${sent.purpose} gen=$settleGen")
                    drainQueuedLiveZoomPanOrVerify(settleGen)
                }

                if (sent.purpose.startsWith("remote-codec-profile-set:") ||
                    sent.purpose.startsWith("remote-codec-profile-activate:")) {
                    val parts = sent.purpose.split(':')
                    val requestedCodec = parts.getOrNull(1)?.toIntOrNull()
                    val requestedBitDepth = parts.getOrNull(2)?.toIntOrNull()
                    val source = if (sent.purpose.startsWith("remote-codec-profile-activate:")) "restart-set" else "non-reboot"
                    val delayMs = if (source == "restart-set") 1200L else 450L
                    if (requestedCodec != null && requestedBitDepth != null) {
                        appendEvent(
                            "TCS19.12.1 CODEC VERIFY SCHEDULE source=$source expected=$requestedCodec/$requestedBitDepth delayMs=$delayMs"
                        )
                        scheduleOfficialCodecReadback(requestedCodec, requestedBitDepth, source, delayMs)
                    }
                }

                if (sent.purpose.startsWith("remote-guide-grid-format-get:")) {
                    val gridRequest = pendingFrameGridSet
                    pendingFrameGridSet = null
                    if (gridRequest != null) {
                        mainHandler.postDelayed({
                            if (pendingRemotePacket == null) {
                                appendEvent(
                                    "TCS19.11.7 GRID GET PASS -> setter target=${if (gridRequest.enabled) 1 else 0} " +
                                        "preserveGuide=${gridRequest.frameGuideValue}"
                                )
                                dispatchOfficialFrameGridSet(gridRequest)
                            } else {
                                pendingFrameGridSet = gridRequest
                                appendEvent("TCS19.11.7 GRID setter deferred: PacketLink became busy")
                            }
                        }, 80L)
                    }
                }

                if (sent.purpose == "remote-guide-safe-area-get") {
                    val deferredSafe = deferredSafeAreaMode
                    deferredSafeAreaMode = null
                    if (deferredSafe != null) {
                        mainHandler.postDelayed({
                            if (pendingRemotePacket == null) {
                                appendEvent("TCS19.11.7 SAFE AREA CYCLE -> setter target=$deferredSafe")
                                sendOfficialSafeAreaMode(deferredSafe)
                            } else {
                                appendEvent("TCS19.6 SAFE AREA deferred setter skipped: PacketLink became busy")
                            }
                        }, 80L)
                    }
                }

                val deferredAnamorphic = deferredAnamorphicMode
                if (deferredAnamorphic != null) {
                    deferredAnamorphicMode = null
                    mainHandler.postDelayed({
                        if (pendingRemotePacket == null) {
                            appendEvent("TCS19.7 ANAMORPHIC DISPATCH latest=$deferredAnamorphic")
                            sendOfficialAnamorphicMode(deferredAnamorphic)
                        } else {
                            // A different command took the slot; keep the latest request
                            // so the next Anamorphic tap or completion can retry it.
                            deferredAnamorphicMode = deferredAnamorphic
                            appendEvent("TCS19.7 ANAMORPHIC remains deferred; PacketLink busy")
                        }
                    }, 25L)
                }
            }
        }

        // R27.22.3: offer the just-completed READY boundary only to an
        // explicitly armed, time-bounded Mic/Line gain pilot. Does not alter
        // PacketLink state/credit/timer or request any extra E0 turn.
        if (r2722IdleWindowPending) r2722OfferIdleWindowAtCompletedRead(g)
        if (a051SetIntent?.kind == "INPUT") a051OfferInputAtCompletedRead(g)

        // A12.6: a Mic/Line GET already queued by the user gets one chance at
        // this genuinely completed ATT read boundary, rather than waiting for
        // the 300 ms UI polling timer while the 10 ms PLINK tick may take READY.
        // The existing Mic pump owns all transport checks, Input/Audio exclusions,
        // and request serialization. Do not preempt any pending packet or SET.
        if (r2706MicActive == null && r2706MicReads.isNotEmpty() &&
            !operationInFlight && pendingOps.isEmpty() && pendingRemotePacket == null &&
            !audioR26Live.value.running && !inputCollectorRunning &&
            !inputExtendedRunning && !remoteBootstrapActive &&
            packetLink18.bufferedPacketSize() == 0 &&
            packetLink18.state().toString() == "READY") {
            android.util.Log.i("AtomRemoteAudioA126", "MIC_READY_BOUNDARY " +
                "queued=${r2706MicReads.size} attIdle=true preemption=false")
            r2706MicPump()
        }

        // Exact TCSPacketLinkProcess is timer-driven from READY. Do not immediately
        // force an extra E0 turn here; the 10 ms tick owns that transition.
        return true
    }

    private fun handlePacketLink18_8Payload(payload: ByteArray, local: Boolean) {
        val hex = payload.toHex()
        if (payload.size < 5) {
            appendEvent("TCS18.8 PLINK PAYLOAD short local=$local bytes=$hex")
            return
        }
        val packetId = payload[0].toInt() and 0xFF
        val payloadLen = (payload[1].toInt() and 0xFF) or ((payload[2].toInt() and 0xFF) shl 8)
        val messageType = (payload[3].toInt() and 0xFF) or ((payload[4].toInt() and 0xFF) shl 8)

        // TCS19.12.17.5 INPUT PACKETLINK STREAM DEMUX
        //
        // AtomMessage wire contract:
        //   packetId U8 + payloadLen LE16 + messageType LE16 + payload[payloadLen]
        //
        // PacketLink can also carry non-AtomMessage/TCS control bytes. Do not let
        // those bytes mutate RemoteApi state, satisfy Input correlation, or mark
        // remoteApiVerified. A valid assembled AtomMessage must have an exact
        // structural length match.
        val declaredTotal = 5 + payloadLen
        val normalizedHeaderMessageType = messageType and 0x7FFF

        // 20.05.0 ZoomBox compatibility:
        // AG2 returns ZoomBox response 0x826A with the LE16 length field equal
        // to the complete PacketLink frame length (including the E8 prefix),
        // while the assembled payload handed here excludes E8. Example:
        //   E8 D4 16 00 6A82 <16-byte protobuf>
        // Android previously rejected this recorder response as NON-ATOM.
        val zoomBoxFrameLengthCompat =
            local &&
                normalizedHeaderMessageType == REMOTE_MONITOR_ZOOM_BOX_RESPONSE &&
                (payloadLen == payload.size + 1 || payloadLen == payload.size + 2)

        if (declaredTotal != payload.size && !zoomBoxFrameLengthCompat) {
            // AUDIO R26.11.2: complete-body acceptance still uses the
            // existing strict demux. Never treat a partial Audio page as typed.
            if (local && normalizedHeaderMessageType == REMOTE_APP_POLLING_AUDIO_METERS_PAGE_GET) {
                android.util.Log.i("AtomRemoteAudioR26Fix",
                    "AUDIO_PAGE_TRUNCATED msg=0x${messageType.toString(16)} " +
                        "declared=$payloadLen actual=${(payload.size - 5).coerceAtLeast(0)} " +
                        "mtu=$outputR21MtuBytes action=DROP_INCOMPLETE")
            }
            if (local && normalizedHeaderMessageType == REMOTE_APP_POLLING_OUTPUT_PAGE_GET) {
                android.util.Log.i("AtomRemoteOutputR21",
                    "PAGE_TRUNCATED_RX type=0x${messageType.toString(16)} " +
                        "packetId=$packetId declaredPayload=$payloadLen actualPayload=${(payload.size - 5).coerceAtLeast(0)} " +
                        "totalNeeded=$declaredTotal totalReceived=${payload.size} " +
                        "negotiatedMtu=$outputR21MtuBytes mtuState=$outputR21MtuResult " +
                        "finalPacketLink=true action=DROP_INCOMPLETE")
            }
            val asciiLike = payload.all { byte ->
                val value = byte.toInt() and 0xFF
                value == 0x09 || value == 0x0A || value == 0x0D || value in 0x20..0x7E
            }
            val asciiPreview = if (asciiLike) {
                payload.toString(Charsets.US_ASCII)
                    .replace("\r", "\\r")
                    .replace("\n", "\\n")
                    .replace("\t", "\\t")
            } else {
                null
            }
            appendEvent(
                "TCS19.12.17.5 PLINK DEMUX NON-ATOM local=$local bytes=${payload.size} " +
                    "declaredPayloadLen=$payloadLen declaredTotal=$declaredTotal " +
                    "headerPacketId=$packetId headerMessageType=$messageType " +
                    "ascii=${asciiPreview ?: "no"} raw=$hex"
            )
            return
        }

        val body = when {
            zoomBoxFrameLengthCompat -> {
                appendEvent(
                    "TCS20.05.0 ZOOM BOX FRAME-LENGTH COMPAT accepted payloadBytes=${payload.size} " +
                        "wireLength=$payloadLen messageType=0x${normalizedHeaderMessageType.toString(16)}"
                )
                payload.copyOfRange(5, payload.size)
            }
            payloadLen > 0 -> payload.copyOfRange(5, 5 + payloadLen)
            else -> byteArrayOf()
        }
        if (!remoteBootstrapCompleted) remoteBootstrapResponses++
        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            lastRxHex = hex,
            lastStatus = if (remoteBootstrapCompleted) "Recorder RemoteApi state received" else "Recorder state received during startup",
            lastError = null
        )
        appendEvent("TCS18.8 REMOTE PAYLOAD local=$local packetId=$packetId payloadLen=$payloadLen messageType=$messageType data=${body.toHex()}")

        if (WlConnectPrototype.isPotentialConnectResponse(messageType)) {
            appendEvent(
                "WLP3 RAW CONNECT RX packetId=$packetId " +
                    "messageType=0x${messageType.toString(16).uppercase()} " +
                    "normalized=0x${(messageType and 0x7FFF).toString(16).uppercase()} " +
                    "local=${if (local) 1 else 0} payloadLen=$payloadLen bytes=${body.size} " +
                    "raw=${body.toHex()} ascii=${wl32AsciiPreview(body)}"
            )

            val pendingLiveMode = wl32PendingByPacketId[packetId]
            if (pendingLiveMode != null &&
                (pendingLiveMode.getter.commandId == 0x02FF || pendingLiveMode.getter.commandId == 0x0300)
            ) {
                appendEvent(
                    "WLP5 LIVEMODE RESPONSE CANDIDATE request=${pendingLiveMode.getter.label} " +
                        "requestCmd=0x${pendingLiveMode.getter.commandId.toString(16).uppercase()} " +
                        "packetId=$packetId responseType=0x${messageType.toString(16).uppercase()} " +
                        "payloadLen=$payloadLen raw=${body.toHex()}"
                )
            }
        }

        // R27.02: passive, request-agnostic evidence observer for independent
        // Mic/Line typed response opcodes. NO commands are initiated here.
        // Also guard against treating Audio 0x8647 or generic 0x8000 as Mic/Line.
        val r2702Pending = pendingRemotePacket
        val r2702Typed = com.atomosremote.nativeapp.audio.AudioR2702TypedRxEvidence.inspect(
            messageType, body, local, packetId,
            r2702Pending?.packetId == packetId,
            r2702Pending?.purpose ?: "none")
        if (r2702Typed != null) {
            android.util.Log.i("AtomRemoteAudioR2702", r2702Typed)
        }

        // R27.06 uses a unique operator intent token, ATT success and exact
        // typed response to avoid treating unrelated PacketLink packets as GET.
        r2706MicOnRx(messageType, body, local, packetId)
        val pending = pendingRemotePacket
        if (pending?.purpose?.startsWith("r2672-micline-") == true) {
            android.util.Log.i("AtomRemoteAudioR2672",
                "MICLINE_CANDIDATE_RX purpose=${pending.purpose} packetId=$packetId " +
                    "responseType=0x${messageType.toString(16).uppercase()} local=$local " +
                    "bytes=${body.size} raw=${body.toHex()}")
        }

        val wl32Correlated = wl32PendingByPacketId.remove(packetId)
        if (wl32Correlated != null) {
            val getter = wl32Correlated.getter
            appendEvent(
                "WL35 RX label=${getter.label} " +
                    "cmd=0x${getter.commandId.toString(16).uppercase()} " +
                    "variant=${wl32Correlated.variant} requestLocal=${if (wl32Correlated.localRoute) 1 else 0} " +
                    "requestPayload=${if (wl32Correlated.protoTrue) "0801" else "EMPTY"} " +
                    "packetId=$packetId response=0x${messageType.toString(16).uppercase()} " +
                    "responseLocal=${if (local) 1 else 0} bytes=${body.size} raw=${body.toHex()} " +
                    "ascii=${wl32AsciiPreview(body)}"
            )
        } else if (messageType in 0x02E0..0x0311 || messageType == 0xFFFE || messageType == 0xFFFF) {
            appendEvent(
                "WL35 RX UNCORRELATED packetId=$packetId " +
                    "response=0x${messageType.toString(16).uppercase()} " +
                    "local=${if (local) 1 else 0} bytes=${body.size} raw=${body.toHex()} " +
                    "ascii=${wl32AsciiPreview(body)}"
            )
        }

        // Phase 09 recorder-state decoding. These are GET responses already
        // present in the recovered macOS startup polling sequence. Field order
        // follows AtomProtobuf storage for AppPollingApplicationStateMsg and
        // AppPollingInputOutputSummaryMsg. We display recorder IDs/raw values
        // rather than inventing enum names that have not yet been recovered.
        val normalizedMessageType = messageType and 0x7FFF

        // AUDIO R26.13: RX only, preserving all existing PacketLink handlers.
        audioR26ObserveTypedRx(pending, messageType, body, local)

        // OUTPUT R20: passive receive evidence. A GATT ACK, E0 or unrelated
        // payload is NOT a verified OutputPage response.
        val r20PendingPage = pending?.purpose == "remote-output-r19-output-page-get" ||
            pending?.purpose == "remote-output-r20-output-page-local-get"
        if (r20PendingPage || normalizedMessageType == REMOTE_APP_POLLING_OUTPUT_PAGE_GET) {
            android.util.Log.i("AtomRemoteOutputR20",
                "PAGE_RX type=0x${messageType.toString(16)} " +
                    "normalized=0x${normalizedMessageType.toString(16)} " +
                    "local=$local packetId=$packetId pending=${pending?.purpose ?: "none"} " +
                    "typed=${normalizedMessageType == REMOTE_APP_POLLING_OUTPUT_PAGE_GET && body.isNotEmpty()} " +
                    "body=${body.toHex().take(256)}")
        }

        // OUTPUT R17: strictly-correlated scalar response proof. E0, write
        // callbacks, unrelated AtomMessages and malformed payloads NEVER set UI.
        val r17Kind = outputR17ProbeKind
        if (r17Kind != null) {
            val r17Active = android.os.SystemClock.elapsedRealtime() <= outputR17ProbeExpiresAt
            val r17Expected = outputR17ProbeExpectedResponse
            val r17Matching = local && r17Active && normalizedMessageType == r17Expected
            if (r17Matching || ((messageType == 0xFFFE || messageType == 0xFFFF) &&
                    pending?.purpose == "remote-output-r17-get-$r17Kind")) {
                android.util.Log.i("AtomRemoteOutputR17",
                    "PROBE_RX kind=$r17Kind type=0x${messageType.toString(16)} " +
                    "expected=0x${r17Expected.toString(16)} local=$local active=$r17Active " +
                    "packetId=$packetId raw=${body.toHex()}")
            }
            if (r17Matching) {
                val value = protoVarintField(body, 1)
                if (value != null && value in 0..255 &&
                    (r17Kind != "hdmi-timecode" || value in 0..1)) {
                    when (r17Kind) {
                        "downscale" -> _outputDownscaleRawId.value = value
                        "dci-crop" -> _outputDciCropRawId.value = value
                        "hdmi-timecode" -> _outputHdmiTimecodeEnabled.value = value == 1
                    }
                    android.util.Log.i("AtomRemoteOutputR17",
                        "PROBE_CONFIRMED kind=$r17Kind value=$value " +
                        "type=0x${messageType.toString(16)} raw=${body.toHex()}")
                    outputR17ProbeKind = null
                    outputR17ProbeExpectedResponse = -1
                    outputR17ProbeExpiresAt = 0L
                } else {
                    android.util.Log.i("AtomRemoteOutputR17",
                        "PROBE_INVALID kind=$r17Kind raw=${body.toHex()}")
                }
            } else if (r17Active && (messageType == 0xFFFF || messageType == 0xFFFE) &&
                pending?.purpose == "remote-output-r17-get-$r17Kind") {
                android.util.Log.i("AtomRemoteOutputR17",
                    "PROBE_REJECT kind=$r17Kind type=0x${messageType.toString(16)}")
                outputR17ProbeKind = null
                outputR17ProbeExpectedResponse = -1
                outputR17ProbeExpiresAt = 0L
            }
        }

        // R13: candidate readback is accepted only for an active, expiring,
        // local-route probe and the precise expected typed value response.
        // An E0 flow frame, an AtomMessage ACK, or an Input value cannot populate
        // the Output HDR Type / Gamut UI. Raw payloads are always logged.
        val r13Kind = outputR13ProbeKind
        if (r13Kind != null) {
            val active = android.os.SystemClock.elapsedRealtime() <= outputR13ProbeExpiresAt
            val expectedType = outputR13ProbeExpectedResponse
            if (normalizedMessageType == expectedType ||
                normalizedMessageType == expectedType + 1 ||
                messageType == 0xFFFF || messageType == 0xFFFE) {
                android.util.Log.i(
                    "AtomRemoteOutputR13",
                    "RX_CANDIDATE kind=$r13Kind response=0x${messageType.toString(16)} " +
                        "normalized=0x${normalizedMessageType.toString(16)} expected=0x${expectedType.toString(16)} " +
                        "local=$local active=$active packetId=$packetId raw=${body.toHex()}"
                )
            }
            if (active && local && normalizedMessageType == expectedType && body.isNotEmpty()) {
                val value = protoVarintField(body, 1)
                if (value != null && value in 0..255) {
                    if (r13Kind == "hdr-type") _outputHdrTypeId.value = value
                    if (r13Kind == "gamut") _outputGamutId.value = value
                    android.util.Log.i(
                        "AtomRemoteOutputR13",
                        "READBACK_CONFIRMED kind=$r13Kind value=$value " +
                            "type=0x${messageType.toString(16)} local=$local raw=${body.toHex()}"
                    )
                    outputR13ProbeKind = null
                    outputR13ProbeExpiresAt = 0L
                    outputR13ProbeExpectedResponse = -1
                } else {
                    android.util.Log.i("AtomRemoteOutputR13", "READBACK_INVALID kind=$r13Kind raw=${body.toHex()}")
                }
            } else if (active && (messageType == 0xFFFF || messageType == 0xFFFE) &&
                pending?.purpose == "remote-output-r13-candidate-$r13Kind") {
                android.util.Log.i("AtomRemoteOutputR13", "REJECT kind=$r13Kind response=0x${messageType.toString(16)}")
                outputR13ProbeKind = null
                outputR13ProbeExpiresAt = 0L
                outputR13ProbeExpectedResponse = -1
            }
        }

        // R9: observe only output GET result candidates, including explicit
        // rejects. Seeing an RX candidate is NOT a validated readback unless
        // existing HDR/LUT parser accepts its protobuf fields.
        if (normalizedMessageType == 0x00DC ||
            normalizedMessageType == REMOTE_OUTPUT_LUT_OUTPUT_AVAILABLE_RESPONSE ||
            normalizedMessageType == REMOTE_OUTPUT_LUT_OUTPUT_ENABLE_RESPONSE ||
            messageType == 0xFFFF || messageType == 0xFFFE) {
            outputR7Trace(
                "R9_RX_OUTPUT_CANDIDATE type=0x${messageType.toString(16).uppercase()} " +
                    "packetId=$packetId local=$local payloadLen=${body.size} " +
                    "pending=${pending?.purpose ?: "async"} raw=${body.toHex().take(120)}"
            )
        }

        handleExtendedInputPayload(messageType, body, local)

        // 19.12.10 semantic correlation survives the E0 transport turn. The
        // PacketLink transport may clear pendingRemotePacket before the recorder's
        // typed getter payload arrives, so the collector owns a separate probe token.
        input06aCaptureBootstrapResponse(packetId, messageType, body)
        handleInputGranularPayload(messageType, body, local)

        // 20.04.0/20.04.1 MonitorModeGet typed readback.
        // GET command 0x02BD returns typed MonitorMode response 0x02BC with
        // protobuf field #1 = mode. This path is intentionally independent of
        // pendingRemotePacket so the connect-time MAC13 async response is kept.
        if (normalizedMessageType == REMOTE_MONITOR_MODE_RESPONSE && body.isNotEmpty()) {
            val actualMode = protoVarintField(body, 1)
            if (actualMode != null && actualMode in 1..5) {
                val expected = monitorModeReadbackExpected
                val source = monitorModeReadbackSource ?: if (!remoteBootstrapCompleted) "mac13" else "async"
                _monitorModeHardware.value = actualMode
                val generation = _monitorCoreSnapshot.value.generation + 1
                val matched = expected == null || expected == actualMode
                _monitorCoreSnapshot.value = MonitorCoreSnapshot(
                    mode = actualMode,
                    ready = true,
                    generation = generation,
                    pendingMode = null,
                    status = when {
                        expected == null -> "${MonitorCoreCatalog.label(actualMode)} synchronized"
                        matched -> "${MonitorCoreCatalog.label(actualMode)} confirmed by recorder"
                        else -> "Recorder kept ${MonitorCoreCatalog.label(actualMode)}"
                    }
                )
                appendEvent(
                    "TCS20.04.1 MONITOR MODE READBACK source=$source actual=$actualMode " +
                        "expected=${expected ?: -1} matched=$matched generation=$generation raw=${body.toHex()}"
                )
                if (expected != null && !matched) {
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        lastStatus = "Monitor ${MonitorCoreCatalog.label(expected)} not confirmed; recorder reports ${MonitorCoreCatalog.label(actualMode)}",
                        lastError = null
                    )
                } else {
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        lastStatus = "Monitor ${MonitorCoreCatalog.label(actualMode)} confirmed by recorder readback",
                        lastError = null
                    )
                }
                monitorModeReadbackExpected = null
                monitorModeReadbackSource = null
            } else {
                appendEvent(
                    "TCS20.04.1 MONITOR MODE READBACK INVALID value=${actualMode ?: -1} raw=${body.toHex()}"
                )
            }
        }

        // 20.10.7 Display readback hardening. The latest supplied macOS binary exposes
        // paired GET/SET RemoteApi classes. AG2 firmware may return either the value-message
        // family ID or echo the GET family ID, so accept both observed typed forms without
        // changing any transmitted command ID/locality. Raw family RX is logged for hardware evidence.
        if (normalizedMessageType in REMOTE_DISPLAY_BACKLIGHT_SET..REMOTE_DISPLAY_SUPPORTS_GET) {
            appendEvent("TCS20.10.7 DISPLAY FAMILY RX type=0x${normalizedMessageType.toString(16).uppercase()} raw=${body.toHex()}")
        }
        when (normalizedMessageType) {
            REMOTE_DISPLAY_BACKLIGHT_GET -> {
                val wire = protoVarintField(body, 1)
                if (wire != null) {
                    // macOS UI passes integer slider units directly. Prefer direct
                    // 0..100 percent; retain a diagnostic legacy fallback for any
                    // firmware that reports 10x units.
                    val percent = when {
                        wire in 0..100 -> wire
                        wire in 101..1000 -> (wire / 10).coerceIn(0, 100)
                        else -> null
                    }
                    if (percent != null) {
                        _monitorDisplayState.value = _monitorDisplayState.value.copy(
                            backlightPercent = percent,
                            requestedBacklightPercent = null,
                            pendingKey = null,
                            status = "Backlight $percent% confirmed by recorder"
                        )
                        appendEvent(
                            "TCS20.13.0A BACKLIGHT RECORDER READBACK wire=$wire percent=$percent " +
                                "scale=${if (wire <= 100) "direct" else "legacy-x10"}"
                        )
                    } else {
                        appendEvent("TCS20.13.0A BACKLIGHT READBACK OUT-OF-RANGE wire=$wire raw=${body.toHex()}")
                    }
                }
            }
            REMOTE_DISPLAY_Y_LIFT_RESPONSE, REMOTE_DISPLAY_Y_LIFT_GET -> {
                val wire = protoVarintField(body, 1)
                if (wire != null) {
                    val value = wire.coerceIn(-64, 64)
                    _monitorDisplayState.value = _monitorDisplayState.value.copy(yLift = value, requestedYLift = null, pendingKey = null, status = "Y Lift $value confirmed")
                    appendEvent("TCS20.10 DISPLAY Y LIFT READBACK value=$value")
                }
            }
            REMOTE_DISPLAY_Y_GAMMA_RESPONSE, REMOTE_DISPLAY_Y_GAMMA_GET -> {
                val value = protoVarintField(body, 1)
                if (value != null) {
                    _monitorDisplayState.value = _monitorDisplayState.value.copy(yGammaHundredths = value, requestedYGammaHundredths = null, pendingKey = null, status = "Y Gamma ${value / 100f} confirmed")
                    appendEvent("TCS20.10 DISPLAY Y GAMMA READBACK value=$value")
                }
            }
            REMOTE_DISPLAY_Y_GAIN_RESPONSE, REMOTE_DISPLAY_Y_GAIN_GET -> {
                val value = protoVarintField(body, 1)
                if (value != null) {
                    _monitorDisplayState.value = _monitorDisplayState.value.copy(yGainHundredths = value, requestedYGainHundredths = null, pendingKey = null, status = "Y Gain ${value / 100f}x confirmed")
                    appendEvent("TCS20.10 DISPLAY Y GAIN READBACK value=$value")
                }
            }
            REMOTE_DISPLAY_TALLY_RESPONSE, REMOTE_DISPLAY_TALLY_GET -> {
                val value = (protoVarintField(body, 1) ?: 0) != 0
                _monitorDisplayState.value = _monitorDisplayState.value.copy(tallyEnabled = value, requestedTallyEnabled = null, tallySupported = true, pendingKey = null, status = "Tally ${if (value) "On" else "Off"} confirmed")
                appendEvent("TCS20.10 DISPLAY TALLY READBACK enabled=$value")
            }
            REMOTE_DISPLAY_FLIP_RESPONSE, REMOTE_DISPLAY_FLIP_GET -> {
                val value = (protoVarintField(body, 1) ?: 0) != 0
                _monitorDisplayState.value = _monitorDisplayState.value.copy(flipEnabled = value, requestedFlipEnabled = null, flipSupported = true, pendingKey = null, status = "Flip ${if (value) "On" else "Off"} confirmed")
                appendEvent("TCS20.10 DISPLAY FLIP READBACK enabled=$value")
            }
            REMOTE_DISPLAY_SUPPORTS_RESPONSE, REMOTE_DISPLAY_SUPPORTS_GET -> {
                _monitorDisplayState.value = _monitorDisplayState.value.copy(supportsReported = true, pendingKey = null, status = "Display capabilities reported")
                appendEvent("TCS20.10 DISPLAY SUPPORTS READBACK raw=${body.toHex()}")
            }
        }

        // 20.09.0-20.09.2 recorder-backed LUT family recovered from
        // the latest supplied macOS ATOMOS Remote binary.
        when (normalizedMessageType) {
            0x00DC -> {
                // HdrEnableGet response; accept only canonical protobuf boolean.
                // Invalid/non-boolean payloads remain unknown, not guessed.
                val raw = protoVarintField(body, 1)
                if (raw == 0 || raw == 1) {
                    _outputHdrEnabled.value = raw == 1
                    appendEvent("OUTPUT0614 HDR READBACK value=${_outputHdrEnabled.value} raw=${body.toHex()}")
                } else {
                    appendEvent("OUTPUT0614 HDR RESPONSE UNPARSED raw=${body.toHex()}")
                }
            }
            REMOTE_MONITOR_LUT_SLOT_RESPONSE -> {
                val actual = protoVarintField(body, 1) ?: 0
                if (actual in 0..8) {
                    val expected = lutExpectedSlot
                    val matched = expected == null || expected == actual
                    _monitorLutState.value = _monitorLutState.value.copy(
                        currentSlot = actual,
                        requestedSlot = null,
                        currentSlotQueryCompleted = true,
                        pendingKey = if (expected != null) null else _monitorLutState.value.pendingKey,
                        status = when {
                            expected == null -> if (actual == 0) "No LUT selected" else "LUT Slot $actual synchronized"
                            matched -> "LUT Slot $actual confirmed"
                            else -> "Recorder kept ${if (actual == 0) "no LUT" else "LUT Slot $actual"}"
                        }
                    )
                    appendEvent(
                        "TCS20.09.0 LUT SLOT READBACK actual=$actual expected=${expected ?: -1} " +
                            "matched=$matched raw=${body.toHex()}"
                    )
                    lutExpectedSlot = null
                    if (lutRefreshStage == 1) {
                        lutRefreshStage = 2
                        scheduleNextLutRefresh()
                    }
                } else {
                    appendEvent("TCS20.09.0 LUT SLOT READBACK invalid=$actual raw=${body.toHex()}")
                }
            }

            REMOTE_MONITOR_LUT_SLOT_INFO_LIST_RESPONSE -> {
                _monitorLutState.value = _monitorLutState.value.copy(
                    slotListReported = true,
                    status = "LUT slots reported by recorder"
                )
                appendEvent(
                    "TCS20.09.1 LUT LIST READBACK bytes=${body.size} raw=${body.toHex()}"
                )
                if (lutRefreshStage == 2) {
                    lutRefreshStage = 3
                    scheduleNextLutRefresh()
                }
            }

            REMOTE_MONITOR_LUT_PREVIEW_AVAILABLE_RESPONSE -> {
                val available = (protoVarintField(body, 1) ?: 0) != 0
                _monitorLutState.value = _monitorLutState.value.copy(
                    previewAvailable = available,
                    status = if (available) "LUT Preview available" else "LUT Preview not available"
                )
                appendEvent(
                    "TCS20.09.2 LUT PREVIEW AVAILABLE READBACK available=$available raw=${body.toHex()}"
                )
                if (lutRefreshStage == 3) {
                    if (available) {
                        lutRefreshStage = 4
                        scheduleNextLutRefresh()
                    } else {
                        lutRefreshStage = 5
                        _monitorLutState.value = _monitorLutState.value.copy(
                            status = "Preview not available; reading record/output LUT state"
                        )
                        scheduleNextLutRefresh()
                    }
                }
            }

            REMOTE_MONITOR_LUT_PREVIEW_ENABLE_RESPONSE -> {
                val actual = (protoVarintField(body, 1) ?: 0) != 0
                val expected = lutExpectedPreview
                val matched = expected == null || expected == actual
                _monitorLutState.value = _monitorLutState.value.copy(
                    previewEnabled = actual,
                    requestedPreviewEnabled = null,
                    pendingKey = null,
                    status = when {
                        expected == null -> "LUT Preview ${if (actual) "On" else "Off"}"
                        matched -> "LUT Preview ${if (actual) "On" else "Off"} confirmed"
                        else -> "Recorder kept LUT Preview ${if (actual) "On" else "Off"}"
                    }
                )
                appendEvent(
                    "TCS20.09.2 LUT PREVIEW READBACK actual=$actual expected=${expected ?: "none"} " +
                        "matched=$matched raw=${body.toHex()}"
                )
                lutExpectedPreview = null
                if (lutRefreshStage == 4) {
                    lutRefreshStage = 5
                    scheduleNextLutRefresh()
                }
            }
        }

        when (normalizedMessageType) {
            REMOTE_RECORD_LUT_BURN_IN_ENABLE_RESPONSE -> {
                val actual = (protoVarintField(body, 1) ?: 0) != 0
                val expected = lutExpectedRecordBurnIn
                val matched = expected == null || expected == actual
                _monitorLutState.value = _monitorLutState.value.copy(
                    recordBurnInEnabled = actual,
                    requestedRecordBurnInEnabled = null,
                    pendingKey = if (expected != null) null else _monitorLutState.value.pendingKey,
                    status = when {
                        expected == null -> "LUT During Record ${if (actual) "On" else "Off"}"
                        matched -> "LUT During Record ${if (actual) "On" else "Off"} confirmed"
                        else -> "Recorder kept LUT During Record ${if (actual) "On" else "Off"}"
                    }
                )
                appendEvent(
                    "TCS20.09.3 LUT RECORD READBACK actual=$actual expected=${expected ?: "none"} matched=$matched raw=${body.toHex()}"
                )
                lutExpectedRecordBurnIn = null
                if (lutRefreshStage == 6) {
                    lutRefreshStage = 7
                    scheduleNextLutRefresh()
                }
            }

            REMOTE_OUTPUT_LUT_OUTPUT_AVAILABLE_RESPONSE -> {
                val available = (protoVarintField(body, 1) ?: 0) != 0
                _monitorLutState.value = _monitorLutState.value.copy(
                    outputLutAvailable = available,
                    status = if (available) "LUT During Output available" else "LUT During Output not available"
                )
                appendEvent("TCS20.09.4 LUT OUTPUT AVAILABLE READBACK available=$available raw=${body.toHex()}")
                if (lutRefreshStage == 7) {
                    if (available) {
                        lutRefreshStage = 8
                        scheduleNextLutRefresh()
                    } else {
                        lutRefreshStage = 0
                        _monitorLutState.value = _monitorLutState.value.copy(
                            pendingKey = null,
                            finalReady = true,
                            status = "LUT state ready"
                        )
                        appendEvent("TCS20.09.5 LUT FINAL READY outputAvailable=false")
                    }
                }
            }

            REMOTE_OUTPUT_LUT_OUTPUT_ENABLE_RESPONSE -> {
                val actual = (protoVarintField(body, 1) ?: 0) != 0
                val expected = lutExpectedOutput
                val matched = expected == null || expected == actual
                _monitorLutState.value = _monitorLutState.value.copy(
                    outputLutEnabled = actual,
                    requestedOutputLutEnabled = null,
                    pendingKey = null,
                    finalReady = lutRefreshStage == 8 || _monitorLutState.value.finalReady,
                    status = when {
                        expected == null -> "LUT During Output ${if (actual) "On" else "Off"}"
                        matched -> "LUT During Output ${if (actual) "On" else "Off"} confirmed"
                        else -> "Recorder kept LUT During Output ${if (actual) "On" else "Off"}"
                    }
                )
                appendEvent(
                    "TCS20.09.4 LUT OUTPUT READBACK actual=$actual expected=${expected ?: "none"} matched=$matched raw=${body.toHex()}"
                )
                lutExpectedOutput = null
                if (lutRefreshStage == 8) {
                    lutRefreshStage = 0
                    _monitorLutState.value = _monitorLutState.value.copy(
                        pendingKey = null,
                        finalReady = true,
                        status = "LUT state ready"
                    )
                    appendEvent("TCS20.09.5 LUT FINAL READY outputAvailable=true")
                }
            }
        }

        if (pending?.purpose?.startsWith("remote-lut-slot-set:") == true) {
            val requested = pending.purpose.substringAfterLast(':').toIntOrNull()
            if (messageType == REMOTE_GENERIC_SUCCESS && requested != null) {
                appendEvent("TCS20.09.1 LUT SLOT ACK slot=$requested unconfirmed=true")
                scheduleLutSlotVerification()
            } else if (messageType == 0xFFFF || messageType == 0xFFFE) {
                lutExpectedSlot = null
                _monitorLutState.value = _monitorLutState.value.copy(
                    pendingKey = null,
                    status = "LUT Slot ${requested ?: ""} rejected by recorder"
                )
                appendEvent("TCS20.09.1 LUT SLOT REJECT slot=${requested ?: -1} response=0x${messageType.toString(16).uppercase()}")
            }
        }

        if (pending?.purpose?.startsWith("remote-lut-preview-set:") == true) {
            val requested = pending.purpose.substringAfterLast(':') == "1"
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.09.2 LUT PREVIEW ACK enabled=$requested unconfirmed=true")
                scheduleLutPreviewVerification()
            } else if (messageType == 0xFFFF || messageType == 0xFFFE) {
                lutExpectedPreview = null
                _monitorLutState.value = _monitorLutState.value.copy(
                    pendingKey = null,
                    status = "LUT Preview rejected by recorder"
                )
                appendEvent("TCS20.09.2 LUT PREVIEW REJECT enabled=$requested response=0x${messageType.toString(16).uppercase()}")
            }
        }

        if (pending?.purpose?.startsWith("remote-lut-record-set:") == true) {
            val requested = pending.purpose.substringAfterLast(':') == "1"
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.09.3 LUT RECORD ACK enabled=$requested unconfirmed=true")
                scheduleLutRecordVerification()
            } else if (messageType == 0xFFFF || messageType == 0xFFFE) {
                lutExpectedRecordBurnIn = null
                _monitorLutState.value = _monitorLutState.value.copy(
                    pendingKey = null,
                    status = "LUT During Record rejected by recorder"
                )
                appendEvent("TCS20.09.3 LUT RECORD REJECT enabled=$requested response=0x${messageType.toString(16).uppercase()}")
            }
        }

        if (pending?.purpose?.startsWith("remote-lut-output-set:") == true) {
            val requested = pending.purpose.substringAfterLast(':') == "1"
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.09.4 LUT OUTPUT ACK enabled=$requested unconfirmed=true")
                scheduleLutOutputVerification()
            } else if (messageType == 0xFFFF || messageType == 0xFFFE) {
                lutExpectedOutput = null
                _monitorLutState.value = _monitorLutState.value.copy(
                    pendingKey = null,
                    status = "LUT During Output rejected by recorder"
                )
                appendEvent("TCS20.09.4 LUT OUTPUT REJECT enabled=$requested response=0x${messageType.toString(16).uppercase()}")
            }
        }

        val correlatedStatePage = stateSyncPacketToPage[packetId]
        val isAppPollingPageType =
            normalizedMessageType in REMOTE_APP_POLLING_APPLICATION_STATE_GET..REMOTE_APP_POLLING_MONITOR_ASSIST_SUMMARY_GET
        val isRemoteRejectOrAck =
            messageType == REMOTE_GENERIC_SUCCESS || messageType == 0xFFFF || messageType == 0xFFFE

        if (isAppPollingPageType) {
            stateSyncPacketToPage.remove(packetId)
            captureAppPollingStatePage(normalizedMessageType, messageType, body, local)
        } else if (correlatedStatePage != null && normalizedMessageType == correlatedStatePage && !isRemoteRejectOrAck) {
            stateSyncPacketToPage.remove(packetId)
            appendEvent("TCS19.12.10.2 STATE CORRELATED VALID packetId=$packetId responseType=0x${messageType.toString(16)} page=0x${correlatedStatePage.toString(16)} bodyBytes=${body.size}")
            captureAppPollingStatePage(correlatedStatePage, messageType, body, local)
        } else if (correlatedStatePage != null && isRemoteRejectOrAck) {
            stateSyncPacketToPage.remove(packetId)
            appendEvent("TCS19.12.10.2 STATE REJECT packetId=$packetId page=0x${correlatedStatePage.toString(16)} responseType=0x${messageType.toString(16)} NOT_COUNTED")
        } else if (!remoteBootstrapCompleted && body.isNotEmpty()) {
            appendEvent("TCS19.12.9.1 STATE UNMATCHED ASYNC packetId=$packetId responseType=0x${messageType.toString(16)} body=${body.toHex()}")
        }
        if (pending?.purpose == "remote-input-output-summary-get" && (messageType == 0xFFFF || messageType == 0xFFFE)) {
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                lastStatus = "Input / Format query rejected by recorder",
                lastError = "RemoteApi returned 0x${messageType.toString(16).uppercase()} for InputOutputSummaryGet"
            )
            appendEvent("TCS19.10 INPUT SUMMARY REJECT response=0x${messageType.toString(16).uppercase()} local=$local")
        }
        if (normalizedMessageType == REMOTE_APP_POLLING_APPLICATION_STATE_GET) {
            val inputInfoBytes = protoLengthDelimitedField(body, 10)
            val printable = inputInfoBytes?.let(::printableProtoText)
            if (!printable.isNullOrBlank()) _inputFormatInfo.value = printable
            appendEvent("TCS19.9 INPUT APPLICATION STATE inputInfo=${printable ?: inputInfoBytes?.toHex() ?: "not-present"} raw=${body.toHex()}")
        }
        if (normalizedMessageType == REMOTE_APP_POLLING_INPUT_OUTPUT_SUMMARY_GET) {
            // AtomProtobuf storage order recovered from Contents(3).zip:
            // 1 codec, 2 inputInfo, 3 inputCamera, 4 inputGamma, 5 inputGamut,
            // 6 outputInfo, 7 outputHdrType, 8 outputGamut.
            _inputCodecId.value = protoVarintField(body, 1)
            val inputInfoBytes = protoLengthDelimitedField(body, 2)
            _inputFormatInfo.value = inputInfoBytes?.let(::printableProtoText)
                ?: inputInfoBytes?.let { "0x${it.toHex()}" }
            _inputCameraId.value = protoVarintField(body, 3)
            _inputGammaId.value = protoVarintField(body, 4)
            _inputGamutId.value = protoVarintField(body, 5)
            val outputBytes = protoLengthDelimitedField(body, 6)
            _outputInfo.value = outputBytes?.let(::printableProtoText) ?: outputBytes?.let { "0x${it.toHex()}" }
            _outputHdrTypeId.value = protoVarintField(body, 7)
            _outputGamutId.value = protoVarintField(body, 8)
            _inputOutputRawHex.value = body.toHex()
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                lastStatus = "Input / Format summary refreshed",
                lastError = null
            )
            appendEvent(
                "TCS19.9 INPUT SUMMARY codec=${_inputCodecId.value} camera=${_inputCameraId.value} " +
                    "gamma=${_inputGammaId.value} gamut=${_inputGamutId.value} output=${_outputInfo.value} " +
                    "outputHdr=${_outputHdrTypeId.value} outputGamut=${_outputGamutId.value} raw=${body.toHex()}"
            )
        }

        val scopeResponseType = messageType and 0x7FFF
        when (scopeResponseType) {
            REMOTE_MONITOR_WAVEFORM_ENABLE_RESPONSE -> {
                protoVarintField(body, 1)?.let { raw ->
                    val actual = raw != 0
                    val expected = scopeExpectedEnabled
                    val matched = expected == null || expected == actual
                    _monitorScopeState.value = _monitorScopeState.value.copy(
                        enabled = actual,
                        pendingKey = null,
                        status = when {
                            expected == null -> "Waveform ${if (actual) "On" else "Off"}"
                            matched -> "Waveform ${if (actual) "On" else "Off"} confirmed"
                            else -> "Recorder kept Waveform ${if (actual) "On" else "Off"}"
                        }
                    )
                    appendEvent("TCS20.06.0 SCOPE ENABLE READBACK actual=$actual expected=${expected ?: "none"} matched=$matched raw=${body.toHex()}")
                    scopeExpectedEnabled = null
                }
                if (scopeRefreshStage == 1) {
                    scopeRefreshStage = 2
                    mainHandler.postDelayed({
                        if (pendingRemotePacket == null) {
                            queueOfficialScopeGetter(
                                REMOTE_MONITOR_WAVEFORM_MODE_GET,
                                REMOTE_MONITOR_WAVEFORM_MODE_RESPONSE,
                                "remote-scope-mode-get",
                                "Waveform Mode"
                            )
                        }
                    }, 120L)
                }
            }

            REMOTE_MONITOR_WAVEFORM_MODE_RESPONSE -> {
                protoVarintField(body, 1)?.let { actual ->
                    if (actual in 1..4) {
                        val expected = scopeExpectedMode
                        val matched = expected == null || expected == actual
                        _monitorScopeState.value = _monitorScopeState.value.copy(
                            mode = actual,
                            pendingKey = null,
                            status = when {
                                expected == null -> "Scope mode synchronized"
                                matched -> "Scope mode confirmed"
                                else -> "Recorder kept ${_monitorScopeState.value.modeLabel(actual)}"
                            }
                        )
                        appendEvent("TCS20.06.1 SCOPE MODE READBACK actual=$actual expected=${expected ?: -1} matched=$matched raw=${body.toHex()}")
                        scopeExpectedMode = null
                    }
                }
                if (scopeRefreshStage == 2) {
                    scopeRefreshStage = 3
                    mainHandler.postDelayed({
                        if (pendingRemotePacket == null) {
                            queueOfficialScopeGetter(
                                REMOTE_MONITOR_WAVEFORM_SIZE_GET,
                                REMOTE_MONITOR_WAVEFORM_SIZE_RESPONSE,
                                "remote-scope-size-get",
                                "Waveform Size"
                            )
                        }
                    }, 120L)
                }
            }

            REMOTE_MONITOR_WAVEFORM_SIZE_RESPONSE -> {
                protoVarintField(body, 1)?.let { actual ->
                    if (actual in 1..3) {
                        val expected = scopeExpectedSize
                        val matched = expected == null || expected == actual
                        _monitorScopeState.value = _monitorScopeState.value.copy(
                            size = actual,
                            pendingKey = null,
                            status = when {
                                expected == null -> "Scope size synchronized"
                                matched -> "Scope size confirmed"
                                else -> "Recorder kept ${_monitorScopeState.value.sizeLabel(actual)}"
                            }
                        )
                        appendEvent("TCS20.06.2 SCOPE SIZE READBACK actual=$actual expected=${expected ?: -1} matched=$matched raw=${body.toHex()}")
                        scopeExpectedSize = null
                    }
                }
                if (scopeRefreshStage == 3) {
                    scopeRefreshStage = 4
                    mainHandler.postDelayed({
                        if (pendingRemotePacket == null) {
                            queueOfficialScopeGetter(
                                REMOTE_MONITOR_WAVEFORM_OPACITY_GET,
                                REMOTE_MONITOR_WAVEFORM_OPACITY_RESPONSE,
                                "remote-scope-opacity-get",
                                "Waveform Opacity"
                            )
                        }
                    }, 120L)
                }
            }

            REMOTE_MONITOR_WAVEFORM_OPACITY_RESPONSE -> {
                protoVarintField(body, 1)?.let { actual ->
                    if (actual in 0..100) {
                        val expected = scopeExpectedOpacity
                        val matched = expected == null || expected == actual
                        _monitorScopeState.value = _monitorScopeState.value.copy(
                            opacity = actual,
                            pendingKey = null,
                            status = when {
                                expected == null -> "Waveform opacity synchronized"
                                matched -> "Waveform opacity $actual% confirmed"
                                else -> "Recorder kept Waveform opacity $actual%"
                            }
                        )
                        appendEvent("TCS20.06.3 SCOPE OPACITY READBACK actual=$actual expected=${expected ?: -1} matched=$matched raw=${body.toHex()}")
                        scopeExpectedOpacity = null
                    }
                }
                if (scopeRefreshStage == 4) {
                    scopeRefreshStage = 5
                    mainHandler.postDelayed({
                        if (pendingRemotePacket == null) {
                            queueOfficialScopeGetter(
                                REMOTE_MONITOR_WAVEFORM_BRIGHTNESS_GET,
                                REMOTE_MONITOR_WAVEFORM_BRIGHTNESS_RESPONSE,
                                "remote-scope-brightness-get",
                                "Waveform Brightness"
                            )
                        }
                    }, 120L)
                }
            }

            REMOTE_MONITOR_WAVEFORM_BRIGHTNESS_RESPONSE -> {
                protoVarintField(body, 1)?.let { actual ->
                    if (actual in 0..100) {
                        val expected = scopeExpectedBrightness
                        val matched = expected == null || expected == actual
                        _monitorScopeState.value = _monitorScopeState.value.copy(
                            brightness = actual,
                            pendingKey = null,
                            status = when {
                                expected == null -> "Scope state synchronized"
                                matched -> "Waveform brightness $actual% confirmed"
                                else -> "Recorder kept Waveform brightness $actual%"
                            }
                        )
                        appendEvent("TCS20.06.4 SCOPE BRIGHTNESS READBACK actual=$actual expected=${expected ?: -1} matched=$matched raw=${body.toHex()}")
                        scopeExpectedBrightness = null
                    }
                }
                if (scopeRefreshStage == 5) {
                    scopeRefreshStage = 0
                    appendEvent("TCS20.06.5 SCOPE FINAL READY")
                }
            }
        }

        val focusResponseType = messageType and 0x7FFF
        when (focusResponseType) {
            REMOTE_MONITOR_FOCUS_PEAK_ENABLE_RESPONSE -> {
                protoVarintField(body, 1)?.let { raw ->
                    val actual = raw != 0
                    val expected = focusExpectedEnabled
                    val matched = expected == null || expected == actual
                    _monitorFocusAssist.value = _monitorFocusAssist.value.copy(
                        enabled = actual,
                        pendingKey = null,
                        status = when {
                            expected == null -> "Focus Assist ${if (actual) "On" else "Off"}"
                            matched -> "Focus Assist ${if (actual) "On" else "Off"} confirmed"
                            else -> "Recorder kept Focus Assist ${if (actual) "On" else "Off"}"
                        }
                    )
                    appendEvent("TCS20.05.0 FOCUS ENABLE READBACK actual=$actual expected=${expected ?: "none"} matched=$matched raw=${body.toHex()}")
                    focusExpectedEnabled = null
                }
                if (focusRefreshStage == 1) scheduleFocusRefreshStep(2)
            }

            REMOTE_MONITOR_FOCUS_PEAK_TYPE_RESPONSE -> {
                protoVarintField(body, 1)?.let { actual ->
                    if (actual in 1..3) {
                        val expected = focusExpectedType
                        val matched = expected == null || expected == actual
                        _monitorFocusAssist.value = _monitorFocusAssist.value.copy(
                            type = actual,
                            pendingKey = null,
                            status = when {
                                expected == null -> "Focus Type ${focusTypeLabel(actual)}"
                                matched -> "Focus Type ${focusTypeLabel(actual)} confirmed"
                                else -> "Recorder kept Focus Type ${focusTypeLabel(actual)}"
                            }
                        )
                        appendEvent("TCS20.05.1 FOCUS TYPE READBACK actual=$actual expected=${expected ?: -1} matched=$matched raw=${body.toHex()}")
                        focusExpectedType = null
                    }
                }
                if (focusRefreshStage == 2) scheduleFocusRefreshStep(3)
            }

            REMOTE_MONITOR_FOCUS_PEAK_SUPPORTED_THRESHOLDS_RESPONSE -> {
                val values = parseProtoRepeatedVarintField1(body).distinct().sorted()
                _monitorFocusAssist.value = _monitorFocusAssist.value.copy(
                    supportedThresholds = values,
                    status = if (values.isEmpty()) {
                        "Recorder did not report Focus threshold options"
                    } else {
                        "Focus threshold options synchronized"
                    }
                )
                appendEvent("TCS20.05.2 FOCUS THRESHOLDS LIST values=$values raw=${body.toHex()}")
                if (focusRefreshStage == 3) scheduleFocusRefreshStep(4)
            }

            REMOTE_MONITOR_FOCUS_PEAK_THRESHOLD_RESPONSE -> {
                protoVarintField(body, 1)?.let { actual ->
                    val expected = focusExpectedThreshold
                    val matched = expected == null || expected == actual
                    _monitorFocusAssist.value = _monitorFocusAssist.value.copy(
                        threshold = actual,
                        pendingKey = null,
                        status = when {
                            expected == null -> "Focus threshold $actual"
                            matched -> "Focus threshold $actual confirmed"
                            else -> "Recorder kept Focus threshold $actual"
                        }
                    )
                    appendEvent("TCS20.05.2 FOCUS THRESHOLD READBACK actual=$actual expected=${expected ?: -1} matched=$matched raw=${body.toHex()}")
                    focusExpectedThreshold = null
                }
                if (focusRefreshStage == 4) scheduleFocusRefreshStep(5)
            }


            REMOTE_MONITOR_FOCUS_PEAK_COLOUR_RESPONSE -> {
                protoVarintField(body, 1)?.let { actual ->
                    if (actual in 1..7) {
                        val expected = focusExpectedColour
                        val matched = expected == null || expected == actual
                        _monitorFocusAssist.value = _monitorFocusAssist.value.copy(
                            colour = actual,
                            pendingKey = null,
                            status = when {
                                expected == null -> "Focus colour ${_monitorFocusAssist.value.colourLabel(actual)}"
                                matched -> "Focus colour ${_monitorFocusAssist.value.colourLabel(actual)} confirmed"
                                else -> "Recorder kept ${_monitorFocusAssist.value.colourLabel(actual)}"
                            }
                        )
                        appendEvent("TCS20.05.3 FOCUS COLOUR READBACK actual=$actual expected=${expected ?: -1} matched=$matched raw=${body.toHex()}")
                        focusExpectedColour = null
                    }
                }
                if (focusRefreshStage == 5) scheduleFocusRefreshStep(6)
            }

            REMOTE_MONITOR_ZEBRA_ENABLE_RESPONSE -> {
                protoVarintField(body, 1)?.let { raw ->
                    val actual = raw != 0
                    val expected = zebraExpectedEnabled
                    val matched = expected == null || expected == actual
                    _monitorZebraState.value = _monitorZebraState.value.copy(
                        enabled = actual,
                        pendingKey = null,
                        status = when {
                            expected == null -> "Zebra ${if (actual) "On" else "Off"}"
                            matched -> "Zebra ${if (actual) "On" else "Off"} confirmed"
                            else -> "Recorder kept Zebra ${if (actual) "On" else "Off"}"
                        }
                    )
                    appendEvent("TCS20.05.4 ZEBRA ENABLE READBACK actual=$actual expected=${expected ?: "none"} matched=$matched raw=${body.toHex()}")
                    zebraExpectedEnabled = null
                }
                if (focusRefreshStage == 6) scheduleFocusRefreshStep(7)
            }

            REMOTE_MONITOR_ZEBRA_THRESHOLD_RESPONSE -> {
                protoVarintField(body, 1)?.let { actual ->
                    val expected = zebraExpectedThreshold
                    val matched = expected == null || expected == actual
                    _monitorZebraState.value = _monitorZebraState.value.copy(
                        threshold = actual,
                        pendingKey = null,
                        status = when {
                            expected == null -> "Zebra threshold $actual"
                            matched -> "Zebra threshold $actual confirmed"
                            else -> "Recorder kept Zebra threshold $actual"
                        }
                    )
                    appendEvent("TCS20.05.5 ZEBRA THRESHOLD READBACK actual=$actual expected=${expected ?: -1} matched=$matched raw=${body.toHex()}")
                    zebraExpectedThreshold = null
                }
                if (focusRefreshStage == 7) {
                    focusRefreshStage = 0
                    focusRefreshRetry = 0
                    appendEvent("TCS20.05.5 MONITOR ASSIST FOCUS/ZEBRA READY")
                }
            }

            REMOTE_MONITOR_FALSE_COLOUR_ENABLE_RESPONSE -> {
                protoVarintField(body, 1)?.let { raw ->
                    val actual = raw != 0
                    _monitorAssistEffects.value = _monitorAssistEffects.value.copy(
                        falseColourEnabled = actual,
                        pendingKey = null,
                        status = "False Colour ${if (actual) "On" else "Off"}"
                    )
                    appendEvent("TCS20.05.6 FALSE COLOUR READBACK actual=$actual raw=${body.toHex()}")
                }
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) {
                        queueOfficialFocusGetter(
                            REMOTE_MONITOR_BLUE_ONLY_ENABLE_GET,
                            REMOTE_MONITOR_BLUE_ONLY_ENABLE_RESPONSE,
                            "remote-monitor-blue-only-get",
                            "Blue Only"
                        )
                    }
                }, 120L)
            }

            REMOTE_MONITOR_BLUE_ONLY_ENABLE_RESPONSE -> {
                protoVarintField(body, 1)?.let { raw ->
                    val actual = raw != 0
                    _monitorAssistEffects.value = _monitorAssistEffects.value.copy(
                        blueOnlyEnabled = actual,
                        pendingKey = null,
                        status = "Blue Only ${if (actual) "On" else "Off"}"
                    )
                    appendEvent("TCS20.05.7 BLUE ONLY READBACK actual=$actual raw=${body.toHex()}")
                }
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) {
                        queueOfficialFocusGetter(
                            REMOTE_MONITOR_SETTINGS_CHANGEABLE_GET,
                            REMOTE_MONITOR_SETTINGS_CHANGEABLE_RESPONSE,
                            "remote-monitor-settings-changeable-get",
                            "Monitor Assist Availability"
                        )
                    }
                }, 120L)
            }

            REMOTE_MONITOR_SETTINGS_CHANGEABLE_RESPONSE -> {
                protoVarintField(body, 1)?.let { raw ->
                    val actual = raw != 0
                    _monitorAssistEffects.value = _monitorAssistEffects.value.copy(
                        settingsChangeable = actual,
                        pendingKey = null,
                        status = if (actual) "Monitor Assist controls available" else "Monitor Assist controls locked by recorder"
                    )
                    appendEvent("TCS20.05.8 SETTINGS CHANGEABLE READBACK actual=$actual raw=${body.toHex()}")
                }
            }
        }

        if (pending?.purpose == "remote-monitor-zoom-level-list") {
            val normalizedType = messageType and 0x7FFF
            when {
                messageType == 0xFFFF || messageType == 0xFFFE -> {
                    pendingZoomLevelAfterCapability = null
                    deferredZoomLevelAfterCapability = null
                    _zoomCapabilitiesKnown.value = false
                    zoomRefreshActive = false
                    zoomRefreshStage = 0
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        lastStatus = "Zoom availability query rejected",
                        lastError = "Recorder returned RemoteApi 0x${messageType.toString(16).uppercase()} for ZoomLevelListGet"
                    )
                }
                normalizedType == REMOTE_MONITOR_ZOOM_LEVEL_LIST_RESPONSE -> {
                    val rawLevels = parseProtoRepeatedVarintField1(body)
                        .filter { it in listOf(1, 3) }.distinct().sorted()
                    val levels = rawLevels.mapNotNull { raw ->
                        when (raw) { 1 -> 1; 3 -> 2; else -> null }
                    }.distinct().sorted()
                    if (levels.isEmpty()) {
                        _zoomCapabilitiesKnown.value = false
                        _monitorZoomCore.value = _monitorZoomCore.value.copy(
                            supportedLevels = emptyList(),
                            capabilitiesKnown = false,
                            status = "Recorder did not report supported Zoom levels"
                        )
                        pendingZoomLevelAfterCapability = null
                        deferredZoomLevelAfterCapability = null
                        _tcsRemoteState.value = _tcsRemoteState.value.copy(
                            lastStatus = "Recorder did not advertise a Zoom level",
                            lastError = "ZoomLevelList response contained no supported field #1 values"
                        )
                    } else {
                        _zoomSupportedLevels.value = levels
                        _zoomCapabilitiesKnown.value = true
                        _monitorZoomCore.value = _monitorZoomCore.value.copy(
                            supportedLevels = levels,
                            capabilitiesKnown = true,
                            status = "Zoom levels: ${levels.joinToString(", ") { zoomLevelLabel(it) }}",
                            readbackTimedOut = false
                        )
                        val target = pendingZoomLevelAfterCapability
                        pendingZoomLevelAfterCapability = null
                        if (target != null && target in levels) {
                            deferredZoomLevelAfterCapability = target
                        } else if (target != null && target != 0) {
                            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                                lastStatus = "${zoomLevelLabel(target)} is not advertised",
                                lastError = "Recorder zoom levels: ${levels.joinToString(", ")}"
                            )
                        } else if (target == 0) {
                            // Decide OFF only after current-level readback.
                            pendingZoomLevelAfterCapability = 0
                        }
                        appendEvent("TCS20.13.4 ZOOM LIST raw=$rawLevels logical=$levels target=$target")
                    }
                }
            }
        }

        if (pending?.purpose == "remote-monitor-zoom-level-get") {
            val normalizedType = messageType and 0x7FFF
            if (normalizedType == REMOTE_MONITOR_ZOOM_LEVEL_RESPONSE) {
                val rawLevel = protoVarintField(body, 1) ?: 0
                val logicalLevel = when (rawLevel) { 0 -> 0; 1 -> 1; 3 -> 2; else -> null }
                if (logicalLevel != null) {
                    _zoomHardwareLevel.value = logicalLevel
                    val expectedAfterSet = zoomExpectedLevelAfterSet
                    val matched = expectedAfterSet == null || expectedAfterSet == logicalLevel
                    _monitorZoomCore.value = _monitorZoomCore.value.copy(
                        currentLevel = logicalLevel,
                        pendingLevel = null,
                        status = when {
                            expectedAfterSet == null -> "${zoomLevelLabel(logicalLevel)} synchronized"
                            matched -> "${zoomLevelLabel(logicalLevel)} confirmed by recorder"
                            else -> "Recorder kept ${zoomLevelLabel(logicalLevel)}"
                        },
                        readbackTimedOut = false
                    )
                    appendEvent(
                        "TCS20.13.4 ZOOM CURRENT READBACK rawLevel=$rawLevel logical=$logicalLevel expected=${expectedAfterSet ?: -1} matched=$matched raw=${body.toHex()}"
                    )
                    zoomExpectedLevelAfterSet = null
                    zoomSummaryExpectedLevel = null
                    if (pendingZoomLevelAfterCapability == 0) {
                        pendingZoomLevelAfterCapability = null
                        if (logicalLevel != 0) deferredZoomLevelAfterCapability = 0
                    }
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        lastStatus = "${zoomLevelLabel(logicalLevel)} synchronized",
                        lastError = null
                    )
                    appendEvent("TCS20.13.4 ZOOM LEVEL raw=$rawLevel logical=$logicalLevel data=${body.toHex()}")
                    // 20.13.4A R7: Zoom level itself is hardware-confirmed and
                    // frozen. Refresh ZoomBox only after the level readback so the
                    // analog viewport does not stay stuck on the pre-zoom full-frame box.
                    if (expectedAfterSet != null) {
                        scheduleZoomBoxAfterConfirmedLevel(logicalLevel)
                    }
                } else {
                    pendingZoomLevelAfterCapability = null
                    deferredZoomLevelAfterCapability = null
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        lastStatus = "Recorder returned an unknown Zoom level",
                        lastError = "ZoomLevelGet returned unsupported value $rawLevel; Android did not remap it."
                    )
                    appendEvent("TCS19.11.8 ZOOM LEVEL UNKNOWN state=$rawLevel raw=${body.toHex()}")
                }
            }
        }

        if (pending?.purpose == "remote-monitor-zoom-box-get") {
            val normalizedType = messageType and 0x7FFF
            if (normalizedType == REMOTE_MONITOR_ZOOM_BOX_RESPONSE) {
                val frame = protoLengthDelimitedField(body, 1)
                val displayed = protoLengthDelimitedField(body, 2)
                val position = protoLengthDelimitedField(body, 3)
                val previousBox = _zoomBoxState.value
                val positionReported = position != null
                val recorderState = ZoomBoxState(
                    frameWidth = frame?.let { protoVarintField(it, 1) } ?: previousBox?.frameWidth ?: 0,
                    frameHeight = frame?.let { protoVarintField(it, 2) } ?: previousBox?.frameHeight ?: 0,
                    viewportWidth = displayed?.let { protoVarintField(it, 1) } ?: previousBox?.viewportWidth ?: 0,
                    viewportHeight = displayed?.let { protoVarintField(it, 2) } ?: previousBox?.viewportHeight ?: 0,
                    left = position?.let { protoVarintField(it, 1) }
                        ?: lastRequestedZoomPanX
                        ?: previousBox?.left
                        ?: 0,
                    top = position?.let { protoVarintField(it, 2) }
                        ?: lastRequestedZoomPanY
                        ?: previousBox?.top
                        ?: 0
                )
                val logicalZoom = _monitorZoomCore.value.currentLevel ?: 0
                // AG2 hardware evidence: after confirmed 2:1 the ZoomBox getter can keep
                // reporting view == frame (1920x1080), which makes the Android pad
                // permanently non-pannable. The official PanSet still accepts recorder
                // pixel coordinates. For confirmed 2:1 only, derive the effective half-size
                // viewport used by a 2x zoom and reconcile again after each PanSet.
                val state = if (
                    recorderState.valid && logicalZoom == 2 &&
                    recorderState.viewportWidth >= recorderState.frameWidth &&
                    recorderState.viewportHeight >= recorderState.frameHeight
                ) {
                    recorderState.copy(
                        viewportWidth = (recorderState.frameWidth / 2).coerceAtLeast(1),
                        viewportHeight = (recorderState.frameHeight / 2).coerceAtLeast(1),
                        left = recorderState.left.coerceIn(0, (recorderState.frameWidth / 2).coerceAtLeast(0)),
                        top = recorderState.top.coerceIn(0, (recorderState.frameHeight / 2).coerceAtLeast(0))
                    ).also {
                        appendEvent(
                            "TCS20.13.4B ZOOM BOX EFFECTIVE 2X recorderView=${recorderState.viewportWidth}x${recorderState.viewportHeight} " +
                                "effectiveView=${it.viewportWidth}x${it.viewportHeight} frame=${it.frameWidth}x${it.frameHeight}"
                        )
                    }
                } else recorderState
                _zoomBoxState.value = state.takeIf { it.valid }
                if (!positionReported && (lastRequestedZoomPanX != null || lastRequestedZoomPanY != null)) {
                    appendEvent(
                        "TCS20.13.4R12 ZOOM BOX POSITION OMITTED; preserving requested left=${state.left} top=${state.top}"
                    )
                }
                lastRequestedZoomPanX = null
                lastRequestedZoomPanY = null
                _monitorZoomViewport.value = MonitorZoomViewportState(
                    ready = state.valid,
                    pannable = state.valid && state.pannable,
                    pendingPan = false,
                    status = when {
                        !state.valid -> "Recorder returned unusable Zoom viewport data"
                        state.pannable -> if (logicalZoom == 2 && !recorderState.pannable)
                            "2:1 viewport ready for pan" else "Zoom viewport synchronized"
                        else -> "Zoom viewport synchronized; current view is fixed"
                    }
                )
                appendEvent(
                    "TCS20.04.6 ZOOM BOX READBACK valid=${state.valid} pannable=${state.pannable} " +
                        "frame=${state.frameWidth}x${state.frameHeight} view=${state.viewportWidth}x${state.viewportHeight} " +
                        "left=${state.left} top=${state.top}"
                )
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    lastStatus = if (state.valid) "Zoom viewport synchronized" else "Zoom viewport response received",
                    lastError = null
                )
                appendEvent(
                    "TCS19.11.8 ZOOM BOX frame=${state.frameWidth}x${state.frameHeight} " +
                        "view=${state.viewportWidth}x${state.viewportHeight} left=${state.left} top=${state.top}"
                )
            }
        }

        if (pending?.purpose == "remote-guide-format-get") {
            if (messageType == REMOTE_MONITOR_FRAME_GUIDE_FORMAT_RESPONSE ||
                (messageType and 0x7FFF) == REMOTE_MONITOR_FRAME_GUIDE_FORMAT_RESPONSE) {
                val format = protoVarintField(body, 1) ?: 0
                val grid = (protoVarintField(body, 2) ?: 0) != 0
                val modeWire = protoVarintField(body, 3) ?: 0
                val customWire = protoVarintField(body, 4) ?: 13
                val currentGuide = _monitorGuideState.value
                val frameMutationPending = currentGuide.pendingKey == "frame-guide"
                val formatMutationPending = currentGuide.pendingKey == "format"
                val refreshPending = currentGuide.pendingKey == "refresh"
                val expectedFormat = frameGuideExpectedFormat
                val targetMatched = expectedFormat == null || expectedFormat == format
                if (targetMatched && expectedFormat != null) {
                    frameGuideExpectedFormat = null
                }
                _monitorGuideState.value = currentGuide.copy(
                    format = format,
                    gridEnabled = grid,
                    mode = modeWire,
                    customRatio = customWire,
                    frameGuideEnabled = format != 0,
                    pendingKey = when {
                        (frameMutationPending || formatMutationPending) && !targetMatched -> currentGuide.pendingKey
                        frameMutationPending || formatMutationPending || refreshPending -> null
                        else -> currentGuide.pendingKey
                    },
                    status = when {
                        !targetMatched ->
                            "Recorder still reports ${currentGuide.formatLabel(format)}; syncing requested ${currentGuide.formatLabel(expectedFormat ?: format)}"
                        frameMutationPending ->
                            "Frame Guide ${if (format != 0) "On" else "Off"} confirmed by recorder"
                        formatMutationPending ->
                            "Frame Guide ${currentGuide.formatLabel(format)} confirmed by recorder"
                        else -> "Guide format synchronized"
                    }
                )
                _frameGuideFormatSummaryRaw.value = format
                _frameGridHardwareEnabled.value = grid
                appendEvent(
                    "TCS20.13.2R13 GUIDE FORMAT READBACK format=$format grid=${if (grid) 1 else 0} " +
                        "mode=$modeWire custom=$customWire raw=${body.toHex()}"
                )
                appendEvent(
                    "TCS20.15R16.1 GUIDE FORMAT READBACK format=$format " +
                        "frameGuideEnabled=${_monitorGuideState.value.frameGuideEnabled} pending=${_monitorGuideState.value.pendingKey}"
                )
            }
        }

        if (pending?.purpose?.startsWith("remote-guide-frame-get:") == true) {
            if (messageType == REMOTE_MONITOR_FRAME_GUIDE_RESPONSE ||
                (messageType and 0x7FFF) == REMOTE_MONITOR_FRAME_GUIDE_RESPONSE) {
                val enabled = (protoVarintField(body, 1) ?: 0) != 0
                val currentGuide = _monitorGuideState.value
                _monitorGuideState.value = currentGuide.copy(
                    frameGuideEnabled = enabled,
                    pendingKey = null,
                    status = "Frame Guide ${if (enabled) "On" else "Off"} confirmed by recorder"
                )
                appendEvent(
                    "TCS20.13.2R13 FRAME GUIDE READBACK enabled=${if (enabled) 1 else 0} raw=${body.toHex()}"
                )
            } else if (messageType == 0xFFFF || messageType == 0xFFFE) {
                val currentGuide = _monitorGuideState.value
                _monitorGuideState.value = currentGuide.copy(
                    pendingKey = null,
                    status = "Frame Guide state readback unavailable"
                )
                appendEvent("TCS20.13.2R13 FRAME GUIDE GET REJECT response=0x${messageType.toString(16).uppercase()}")
            }
        }

        if (pending?.purpose?.startsWith("remote-guide-frame-format-set:") == true) {
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.13.2R14 FRAME GUIDE FORMAT ACK response=0x8000; scheduling recorder format readback")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) refreshOfficialGuidesState()
                }, 140L)
            } else if (messageType == 0xFFFF || messageType == 0xFFFE) {
                _monitorGuideState.value = _monitorGuideState.value.copy(
                    pendingKey = null,
                    status = "Frame Guide rejected by recorder"
                )
                appendEvent("TCS20.13.2 FRAME GUIDE FORMAT REJECT response=0x${messageType.toString(16).uppercase()}")
            }
        }

        if (pending?.purpose?.startsWith("remote-guide-format-set:") == true) {
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                val target = pending.purpose.substringAfterLast(':').toIntOrNull()
                appendEvent("TCS20.07.1 GUIDE FORMAT ACK target=${target ?: -1} unconfirmed=true")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) refreshOfficialGuidesState()
                }, 140L)
            } else if (messageType == 0xFFFF || messageType == 0xFFFE) {
                _monitorGuideState.value = _monitorGuideState.value.copy(
                    pendingKey = null,
                    status = "Frame Guide format rejected by recorder"
                )
            }
        }

        if (pending?.purpose?.startsWith("remote-guide-frame-enable:") == true) {
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.13.2R15 FRAME GUIDE BOOL ACK response=0x8000; scheduling dedicated 0x44F readback")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) queueOfficialFrameGuideEnabledGet("setter-ack")
                }, 70L)
            } else if (messageType == 0xFFFF || messageType == 0xFFFE) {
                _monitorGuideState.value = _monitorGuideState.value.copy(
                    pendingKey = null,
                    status = "Frame Guide command rejected by recorder"
                )
                appendEvent("TCS20.13.2R15 FRAME GUIDE BOOL REJECT response=0x${messageType.toString(16).uppercase()}")
            }
        }

        if (pending?.purpose?.startsWith("remote-guide-grid-format-get:") == true) {
            val normalizedType = messageType and 0x7FFF
            val desired = pending.purpose.substringAfterLast(':').toIntOrNull() == 1
            if (normalizedType == REMOTE_MONITOR_FRAME_GUIDE_FORMAT_RESPONSE) {
                // Constructor argument #1 is the current frame-guide value; #2 is
                // the 3x3 state. Missing scalar fields mean protobuf default zero.
                val frameGuideValue = protoVarintField(body, 1) ?: 0
                val currentGrid = (protoVarintField(body, 2) ?: 0) != 0
                val frameGuideMode = protoVarintField(body, 3) ?: 0
                val customRatio = protoVarintField(body, 4) ?: 13
                _frameGridHardwareEnabled.value = currentGrid
                _monitorGuideState.value = _monitorGuideState.value.copy(
                    format = frameGuideValue,
                    gridEnabled = currentGrid,
                    mode = frameGuideMode,
                    customRatio = customRatio,
                    pendingKey = null,
                    status = "Guide state synchronized"
                )
                val activeFrameGuideValue = frameGuideValue.takeIf { it != 0 } ?: 1
                pendingFrameGridSet = PendingFrameGridSet(
                    enabled = desired,
                    frameGuideValue = activeFrameGuideValue,
                    frameGuideEnabled = false,
                    mode = 0
                )
                appendEvent(
                    "TCS20.13.R13 GRID STATE guide=$frameGuideValue grid=${if (currentGrid) 1 else 0} " +
                        "mode=$frameGuideMode custom=$customRatio -> target=${if (desired) 1 else 0} raw=${body.toHex()}"
                )
            } else if (messageType == 0xFFFF || messageType == 0xFFFE) {
                pendingFrameGridSet = null
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    lastStatus = "9-Grid state query rejected",
                    lastError = "Recorder rejected FrameGuideFormatGet; no guessed grid setter was sent"
                )
                appendEvent("TCS19.11.7 GRID GET REJECT response=0x${messageType.toString(16).uppercase()}")
            }
        }

        if (pending?.purpose?.startsWith("remote-guide-grid-set:") == true) {
            val expected = pending.purpose.substringAfterLast(':') == "1"
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.13.3 GRID ACK expected=${if (expected) 1 else 0} transportOnly=true; scheduling recorder summary readback")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) {
                        refreshOfficialGuidesState()
                    } else {
                        scheduleMonitorAssistSummaryFallback("grid-set", 320L)
                    }
                }, 120L)
            } else if (messageType == 0xFFFF || messageType == 0xFFFE) {
                _monitorGuideState.value = _monitorGuideState.value.copy(
                    pendingKey = null,
                    status = "9-Grid rejected by recorder"
                )
                appendEvent("TCS20.08.R3 GRID REJECT expected=${if (expected) 1 else 0} response=0x${messageType.toString(16).uppercase()}")
            }
        }

        if (pending?.purpose == "remote-guide-safe-area-get") {
            val normalizedType = messageType and 0x7FFF
            if (normalizedType == REMOTE_MONITOR_SAFE_AREA_SIZE_RESPONSE) {
                // 20.08 R2: AG2 SafeAreaSizeResponse mirrors the wire enum.
                // Hardware evidence: wire 0 is rejected; valid values are
                // 1=Off, 2=Action, 3=Title. Only typed recorder readback updates UI.
                val wire = parseProtoRepeatedVarintField1(body).firstOrNull()
                val mode = when (wire) {
                    1 -> 0
                    2 -> 1
                    3 -> 2
                    else -> {
                        appendEvent(
                            "TCS20.08.R2 SAFE AREA READBACK unsupported wire=${wire ?: -1} raw=${body.toHex()}"
                        )
                        return
                    }
                }
                _safeAreaHardwareMode.value = mode
                appendEvent(
                    "TCS20.08.R2 SAFE AREA READBACK wire=$wire logical=$mode " +
                        "label=${safeAreaLabel(mode)} raw=${body.toHex()}"
                )
                if (pendingSafeAreaCycle) {
                    pendingSafeAreaCycle = false
                    deferredSafeAreaMode = when (mode) { 0 -> 1; 1 -> 2; else -> 0 }
                }
            } else if (messageType == 0xFFFF) {
                pendingSafeAreaCycle = false
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    lastStatus = "Safe Area state query rejected",
                    lastError = "Recorder returned RemoteApi 0xFFFF for SafeAreaSizeGet"
                )
            }
        }

        if (pending?.purpose == "remote-guide-safe-area-title-prime") {
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.07.5R2 SAFE AREA TITLE PRIME ACK; dispatching wire3")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) {
                        deferredSafeAreaMode = null
                        enqueueOfficialRemoteCommandProtoExplicitVarint1(
                            commandId = REMOTE_MONITOR_SAFE_AREA_SIZE_SET,
                            value = 3,
                            purpose = "remote-guide-safe-area-set:3",
                            label = "Safe Area Title"
                        )
                    }
                }, 160L)
            } else if (messageType == 0xFFFF || messageType == 0xFFFE) {
                deferredSafeAreaMode = null
                appendEvent("TCS20.07.5R2 SAFE AREA TITLE PRIME rejected response=0x${messageType.toString(16)}")
            }
        }

        if (pending?.purpose?.startsWith("remote-guide-safe-area-set:") == true) {
            val requestedWire = pending.purpose.substringAfterLast(':').toIntOrNull()
            val requested = requestedWire?.let { (it - 1).coerceIn(0, 2) }
            if (messageType == REMOTE_GENERIC_SUCCESS && requested != null) {
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    lastStatus = "${safeAreaLabel(requested)} accepted; verifying recorder state",
                    lastError = null
                )
                appendEvent("TCS20.07.5 SAFE AREA ACK mode=$requested response=0x8000 unconfirmed=true")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) {
                        refreshOfficialSafeAreaState()
                    }
                }, 140L)
            } else if (messageType == 0xFFFF || messageType == 0xFFFE) {
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    lastStatus = "${pending.label} rejected by recorder",
                    lastError = "Recorder returned RemoteApi 0x${messageType.toString(16).uppercase()} for ${pending.label}"
                )
            }
        }

        if (pending?.purpose == "remote-anamorphic-mode-get") {
            val normalizedType = messageType and 0x7FFF
            if (normalizedType == REMOTE_MONITOR_ANAMORPHIC_DESQUEEZE_MODE_RESPONSE) {
                val actual = protoVarintField(body, 1)
                if (actual != null && actual in 0..9) {
                    val expected = _monitorAnamorphicState.value.pendingMode
                    val matched = expected == null || expected == actual
                    _anamorphicHardwareMode.value = actual
                    _monitorAnamorphicState.value = _monitorAnamorphicState.value.copy(
                        currentMode = actual,
                        pendingMode = null,
                        status = if (expected == null) {
                            "Anamorphic ${anamorphicLabel(actual)} synchronized"
                        } else if (matched) {
                            "Anamorphic ${anamorphicLabel(actual)} confirmed"
                        } else {
                            "Recorder kept Anamorphic ${anamorphicLabel(actual)}"
                        }
                    )
                    appendEvent(
                        "TCS20.08.0 ANAMORPHIC READBACK actual=$actual expected=${expected ?: -1} " +
                            "matched=$matched raw=${body.toHex()}"
                    )
                }
            }
        }

        if (pending?.purpose?.startsWith("remote-anamorphic-mode:") == true) {
            val requested = pending.purpose.substringAfterLast(':').toIntOrNull()
            if (messageType == REMOTE_GENERIC_SUCCESS && requested != null) {
                appendEvent("TCS20.08.2 ANAMORPHIC ACK mode=$requested unconfirmed=true")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) refreshOfficialAnamorphicMode()
                }, 140L)
            } else if (messageType == 0xFFFF || messageType == 0xFFFE) {
                _monitorAnamorphicState.value = _monitorAnamorphicState.value.copy(
                    pendingMode = null,
                    status = "Anamorphic ${requested?.let(::anamorphicLabel) ?: ""} rejected by recorder"
                )
                appendEvent("TCS20.08.2 ANAMORPHIC REJECT mode=${requested ?: -1}")
            }
        }

        if (pending?.purpose?.startsWith("remote-monitor-mode:") == true) {
            val requested = pending.purpose.substringAfterLast(':').toIntOrNull()
            if (messageType == REMOTE_GENERIC_SUCCESS && requested != null) {
                _monitorCoreSnapshot.value = _monitorCoreSnapshot.value.copy(
                    pendingMode = requested,
                    status = "${MonitorCoreCatalog.label(requested)} accepted; waiting for recorder readback"
                )
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    lastStatus = "${pending.label} accepted; verifying recorder state",
                    lastError = null
                )
                appendEvent("TCS20.04.2 MONITOR MODE ACK mode=$requested response=0x8000 unconfirmed=true")
            } else if (messageType == 0xFFFF || messageType == 0xFFFE) {
                _monitorCoreSnapshot.value = _monitorCoreSnapshot.value.copy(
                    pendingMode = null,
                    status = "${MonitorCoreCatalog.label(requested)} rejected by recorder"
                )
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    lastStatus = "${pending.label} rejected by recorder",
                    lastError = "Recorder returned RemoteApi 0x${messageType.toString(16).uppercase()} for ${pending.label}"
                )
                appendEvent("TCS20.04.2 MONITOR MODE REJECT mode=$requested response=0x${messageType.toString(16).uppercase()}")
            }
        }

        if (pending?.purpose?.startsWith("remote-monitor-mode-get:") == true &&
            (messageType == 0xFFFF || messageType == 0xFFFE)) {
            val expected = monitorModeReadbackExpected
            _monitorCoreSnapshot.value = _monitorCoreSnapshot.value.copy(
                pendingMode = null,
                status = "Monitor mode readback rejected"
            )
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                lastStatus = "Monitor mode readback rejected",
                lastError = "Recorder returned RemoteApi 0x${messageType.toString(16).uppercase()} for MonitorModeGet"
            )
            appendEvent("TCS20.04.1 MONITOR MODE GET REJECT expected=${expected ?: -1}")
            monitorModeReadbackExpected = null
            monitorModeReadbackSource = null
        }

        if (pending?.purpose?.startsWith("remote-codec-profile-set:") == true ||
            pending?.purpose?.startsWith("remote-codec-profile-activate:") == true) {
            val parts = pending.purpose.split(':')
            val requestedCodec = parts.getOrNull(1)?.toIntOrNull()
            val requestedBitDepth = parts.getOrNull(2)?.toIntOrNull()
            val activating = pending.purpose.startsWith("remote-codec-profile-activate:")
            val expectedType = if (activating) REMOTE_RECORD_CODEC_PROFILE_SET else REMOTE_RECORD_NON_REBOOT_CODEC_PROFILE_SET
            val normalizedType = messageType and 0x7FFF
            val accepted = messageType == REMOTE_GENERIC_SUCCESS || normalizedType == expectedType
            when {
                accepted && requestedCodec != null && requestedBitDepth != null -> {
                    // Setter response proves acceptance only. Recorder truth comes
                    // from the 0x013A CodecProfileGet issued after transport clears.
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        lastStatus = if (activating) {
                            "${pending.label} accepted; waiting for recorder readback"
                        } else {
                            "${pending.label} accepted; confirming recorder codec"
                        },
                        lastError = null
                    )
                    appendEvent(
                        "TCS19.11.9 CODEC ${if (activating) "RESTART SET" else "APPLY"} ACK " +
                            "requested=$requestedCodec/$requestedBitDepth response=0x${messageType.toString(16).uppercase()} " +
                            "hardwareStateNotUpdated=1"
                    )
                }
                messageType == 0xFFFF || messageType == 0xFFFE -> {
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        lastStatus = "${pending.label} rejected by recorder",
                        lastError = "Recorder returned RemoteApi 0x${messageType.toString(16).uppercase()} for ${pending.label}. The requested profile tuple was not confirmed."
                    )
                    appendEvent(
                        "TCS19.11.9 CODEC ${if (activating) "RESTART SET" else "APPLY"} REJECT " +
                            "requested=$requestedCodec/$requestedBitDepth response=0x${messageType.toString(16).uppercase()}"
                    )
                }
                else -> appendEvent(
                    "TCS19.11.9 CODEC SET response type=0x${messageType.toString(16)} " +
                        "expected=0x${expectedType.toString(16)} requested=$requestedCodec/$requestedBitDepth body=${body.toHex()}"
                )
            }
        }

        if (pending?.purpose?.startsWith("remote-codec-profile-get:") == true || pendingCodecReadbackExpected != null) {
            val parts = pending?.purpose?.takeIf { it.startsWith("remote-codec-profile-get:") }?.split(':')
            val expectedPair = pendingCodecReadbackExpected
            val expectedCodec = parts?.getOrNull(1)?.toIntOrNull() ?: expectedPair?.first
            val expectedBitDepth = parts?.getOrNull(2)?.toIntOrNull() ?: expectedPair?.second
            val source = parts?.getOrNull(3) ?: pendingCodecReadbackSource ?: "verify"
            val normalizedType = messageType and 0x7FFF

            when {
                messageType == 0xFFFF || messageType == 0xFFFE -> {
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        lastStatus = "Codec readback rejected by recorder",
                        lastError = "CodecProfileGet returned RemoteApi 0x${messageType.toString(16).uppercase()}; requested codec was not marked confirmed."
                    )
                    appendEvent(
                        "TCS19.11.9 CODEC READBACK REJECT expected=$expectedCodec/$expectedBitDepth " +
                            "source=$source response=0x${messageType.toString(16).uppercase()}"
                    )
                }
                normalizedType == REMOTE_RECORD_CODEC_PROFILE_GET -> {
                    val actualWireCodec = protoVarintField(body, 1) ?: 0
                    val actualCodec = codecLogicalFromWire(actualWireCodec)
                    val actualBitDepth = protoVarintField(body, 2) ?: 0
                    if (actualCodec != null && actualBitDepth in 0..2) {
                        _codecHardwareProfile.value = actualCodec to actualBitDepth
                        val matches = expectedCodec == actualCodec && expectedBitDepth == actualBitDepth
                        _tcsRemoteState.value = _tcsRemoteState.value.copy(
                            lastStatus = if (matches) {
                                "${codecProfileLabel(actualCodec)} confirmed by recorder"
                            } else {
                                "Recorder reports ${codecProfileLabel(actualCodec)} after codec request"
                            },
                            lastError = null
                        )
                        appendEvent(
                            "TCS20.15R15 CODEC READBACK raw=$actualWireCodec logical=$actualCodec/$actualBitDepth " +
                                "expected=$expectedCodec/$expectedBitDepth match=${if (matches) 1 else 0} source=$source raw=${body.toHex()}"
                        )
                    } else {
                        _tcsRemoteState.value = _tcsRemoteState.value.copy(
                            lastStatus = "Codec readback received but could not be validated",
                            lastError = "CodecProfileGet did not contain a supported RecordCodecProfile tuple."
                        )
                        appendEvent(
                            "TCS19.11.9 CODEC READBACK INVALID actual=$actualCodec/$actualBitDepth " +
                                "expected=$expectedCodec/$expectedBitDepth source=$source raw=${body.toHex()}"
                        )
                    }
                }
                else -> appendEvent(
                    "TCS19.11.9 CODEC READBACK unexpectedType=0x${messageType.toString(16)} " +
                        "expectedType=0x${REMOTE_RECORD_CODEC_PROFILE_GET.toString(16)} source=$source body=${body.toHex()}"
                )
            }
            if (normalizedType == REMOTE_RECORD_CODEC_PROFILE_GET || messageType == 0xFFFF || messageType == 0xFFFE) {
                codecReadbackTimeout?.let { mainHandler.removeCallbacks(it) }
                codecReadbackTimeout = null
                pendingCodecReadbackExpected = null
                pendingCodecReadbackSource = null
                publishInputCodecCollector(source = "codec-readback")
                appendEvent("TCS19.12.10 CODEC READBACK RX response=0x${messageType.toString(16)} expected=$expectedCodec/$expectedBitDepth source=$source raw=${body.toHex()}")
            }
        }

        if (pending?.purpose == "remote-scope-enable" &&
            (messageType == REMOTE_GENERIC_SUCCESS || messageType == 0xFFFF || messageType == 0xFFFE)) {
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.06.0 SCOPE ENABLE ACK unconfirmed=true")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) {
                        queueOfficialScopeGetter(
                            REMOTE_MONITOR_WAVEFORM_ENABLE_GET,
                            REMOTE_MONITOR_WAVEFORM_ENABLE_RESPONSE,
                            "remote-scope-enable-get",
                            "Waveform"
                        )
                    }
                }, 140L)
            } else {
                scopeExpectedEnabled = null
                _monitorScopeState.value = _monitorScopeState.value.copy(
                    pendingKey = null,
                    status = "Waveform change rejected by recorder"
                )
            }
        }

        if (pending?.purpose == "remote-scope-mode" &&
            (messageType == REMOTE_GENERIC_SUCCESS || messageType == 0xFFFF || messageType == 0xFFFE)) {
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.06.1 SCOPE MODE ACK unconfirmed=true")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) {
                        queueOfficialScopeGetter(
                            REMOTE_MONITOR_WAVEFORM_MODE_GET,
                            REMOTE_MONITOR_WAVEFORM_MODE_RESPONSE,
                            "remote-scope-mode-get",
                            "Waveform Mode"
                        )
                    }
                }, 140L)
            } else {
                scopeExpectedMode = null
                _monitorScopeState.value = _monitorScopeState.value.copy(
                    pendingKey = null,
                    status = "Scope mode change rejected by recorder"
                )
            }
        }

        if (pending?.purpose == "remote-scope-size" &&
            (messageType == REMOTE_GENERIC_SUCCESS || messageType == 0xFFFF || messageType == 0xFFFE)) {
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.06.2 SCOPE SIZE ACK unconfirmed=true")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) {
                        queueOfficialScopeGetter(
                            REMOTE_MONITOR_WAVEFORM_SIZE_GET,
                            REMOTE_MONITOR_WAVEFORM_SIZE_RESPONSE,
                            "remote-scope-size-get",
                            "Waveform Size"
                        )
                    }
                }, 140L)
            } else {
                scopeExpectedSize = null
                _monitorScopeState.value = _monitorScopeState.value.copy(
                    pendingKey = null,
                    status = "Scope size change rejected by recorder"
                )
            }
        }

        if (pending?.purpose == "remote-scope-opacity" &&
            (messageType == REMOTE_GENERIC_SUCCESS || messageType == 0xFFFF || messageType == 0xFFFE)) {
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.06.3 SCOPE OPACITY ACK unconfirmed=true")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) {
                        queueOfficialScopeGetter(
                            REMOTE_MONITOR_WAVEFORM_OPACITY_GET,
                            REMOTE_MONITOR_WAVEFORM_OPACITY_RESPONSE,
                            "remote-scope-opacity-get",
                            "Waveform Opacity"
                        )
                    }
                }, 140L)
            } else {
                scopeExpectedOpacity = null
                _monitorScopeState.value = _monitorScopeState.value.copy(
                    pendingKey = null,
                    status = "Waveform opacity change rejected by recorder"
                )
            }
        }

        if (pending?.purpose == "remote-scope-brightness" &&
            (messageType == REMOTE_GENERIC_SUCCESS || messageType == 0xFFFF || messageType == 0xFFFE)) {
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.06.4 SCOPE BRIGHTNESS ACK unconfirmed=true")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) {
                        queueOfficialScopeGetter(
                            REMOTE_MONITOR_WAVEFORM_BRIGHTNESS_GET,
                            REMOTE_MONITOR_WAVEFORM_BRIGHTNESS_RESPONSE,
                            "remote-scope-brightness-get",
                            "Waveform Brightness"
                        )
                    }
                }, 140L)
            } else {
                scopeExpectedBrightness = null
                _monitorScopeState.value = _monitorScopeState.value.copy(
                    pendingKey = null,
                    status = "Waveform brightness change rejected by recorder"
                )
            }
        }

        if (pending?.purpose == "remote-monitor-focus" && (messageType == REMOTE_GENERIC_SUCCESS || messageType == 0xFFFF || messageType == 0xFFFE)) {
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.05.0 FOCUS ENABLE ACK unconfirmed=true")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) {
                        queueOfficialFocusGetter(
                            REMOTE_MONITOR_FOCUS_PEAK_ENABLE_GET,
                            REMOTE_MONITOR_FOCUS_PEAK_ENABLE_RESPONSE,
                            "remote-monitor-focus-enable-get",
                            "Focus Assist"
                        )
                    }
                }, 120L)
            } else {
                focusExpectedEnabled = null
                _monitorFocusAssist.value = _monitorFocusAssist.value.copy(
                    pendingKey = null,
                    status = "Focus Assist change rejected by recorder"
                )
                appendEvent("TCS20.05.0 FOCUS ENABLE REJECT response=0x${messageType.toString(16).uppercase()}")
            }
        }

        if (pending?.purpose?.startsWith("remote-monitor-focus-type:") == true &&
            (messageType == REMOTE_GENERIC_SUCCESS || messageType == 0xFFFF || messageType == 0xFFFE)) {
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.05.1 FOCUS TYPE ACK unconfirmed=true")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) {
                        queueOfficialFocusGetter(
                            REMOTE_MONITOR_FOCUS_PEAK_TYPE_GET,
                            REMOTE_MONITOR_FOCUS_PEAK_TYPE_RESPONSE,
                            "remote-monitor-focus-type-get",
                            "Focus Type"
                        )
                    }
                }, 120L)
            } else {
                focusExpectedType = null
                _monitorFocusAssist.value = _monitorFocusAssist.value.copy(
                    pendingKey = null,
                    status = "Focus Type change rejected by recorder"
                )
                appendEvent("TCS20.05.1 FOCUS TYPE REJECT response=0x${messageType.toString(16).uppercase()}")
            }
        }

        if (pending?.purpose?.startsWith("remote-monitor-focus-threshold:") == true &&
            (messageType == REMOTE_GENERIC_SUCCESS || messageType == 0xFFFF || messageType == 0xFFFE)) {
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.05.2 FOCUS THRESHOLD ACK unconfirmed=true")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) {
                        queueOfficialFocusGetter(
                            REMOTE_MONITOR_FOCUS_PEAK_THRESHOLD_GET,
                            REMOTE_MONITOR_FOCUS_PEAK_THRESHOLD_RESPONSE,
                            "remote-monitor-focus-threshold-get",
                            "Focus Threshold"
                        )
                    }
                }, 120L)
            } else {
                focusExpectedThreshold = null
                _monitorFocusAssist.value = _monitorFocusAssist.value.copy(
                    pendingKey = null,
                    status = "Focus threshold change rejected by recorder"
                )
                appendEvent("TCS20.05.2 FOCUS THRESHOLD REJECT response=0x${messageType.toString(16).uppercase()}")
            }
        }

        if (pending?.purpose?.startsWith("remote-monitor-focus-colour:") == true &&
            (messageType == REMOTE_GENERIC_SUCCESS || messageType == 0xFFFF || messageType == 0xFFFE)) {
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.05.3 FOCUS COLOUR ACK unconfirmed=true")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) {
                        queueOfficialFocusGetter(
                            REMOTE_MONITOR_FOCUS_PEAK_COLOUR_GET,
                            REMOTE_MONITOR_FOCUS_PEAK_COLOUR_RESPONSE,
                            "remote-monitor-focus-colour-get",
                            "Focus Colour"
                        )
                    }
                }, 140L)
            } else {
                focusExpectedColour = null
                _monitorFocusAssist.value = _monitorFocusAssist.value.copy(
                    pendingKey = null,
                    status = "Focus colour change rejected by recorder"
                )
                appendEvent("TCS20.05.3 FOCUS COLOUR REJECT response=0x${messageType.toString(16).uppercase()}")
            }
        }

        if (pending?.purpose?.startsWith("remote-monitor-zebra:") == true &&
            !pending.purpose.startsWith("remote-monitor-zebra-threshold:") &&
            (messageType == REMOTE_GENERIC_SUCCESS || messageType == 0xFFFF || messageType == 0xFFFE)) {
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.05.4 ZEBRA ENABLE ACK unconfirmed=true")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) {
                        queueOfficialFocusGetter(
                            REMOTE_MONITOR_ZEBRA_ENABLE_GET,
                            REMOTE_MONITOR_ZEBRA_ENABLE_RESPONSE,
                            "remote-monitor-zebra-enable-get",
                            "Zebra"
                        )
                    }
                }, 140L)
            } else {
                zebraExpectedEnabled = null
                _monitorZebraState.value = _monitorZebraState.value.copy(
                    pendingKey = null,
                    status = "Zebra change rejected by recorder"
                )
                appendEvent("TCS20.05.4 ZEBRA ENABLE REJECT response=0x${messageType.toString(16).uppercase()}")
            }
        }

        if (pending?.purpose?.startsWith("remote-monitor-zebra-threshold:") == true &&
            (messageType == REMOTE_GENERIC_SUCCESS || messageType == 0xFFFF || messageType == 0xFFFE)) {
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.05.5 ZEBRA THRESHOLD ACK unconfirmed=true")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) {
                        queueOfficialFocusGetter(
                            REMOTE_MONITOR_ZEBRA_THRESHOLD_GET,
                            REMOTE_MONITOR_ZEBRA_THRESHOLD_RESPONSE,
                            "remote-monitor-zebra-threshold-get",
                            "Zebra Threshold"
                        )
                    }
                }, 140L)
            } else {
                zebraExpectedThreshold = null
                _monitorZebraState.value = _monitorZebraState.value.copy(
                    pendingKey = null,
                    status = "Zebra threshold change rejected by recorder"
                )
                appendEvent("TCS20.05.5 ZEBRA THRESHOLD REJECT response=0x${messageType.toString(16).uppercase()}")
            }
        }

        if (pending?.purpose?.startsWith("remote-monitor-false-colour:") == true &&
            (messageType == REMOTE_GENERIC_SUCCESS || messageType == 0xFFFF || messageType == 0xFFFE)) {
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.05.6 FALSE COLOUR ACK unconfirmed=true")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) {
                        queueOfficialFocusGetter(
                            REMOTE_MONITOR_FALSE_COLOUR_ENABLE_GET,
                            REMOTE_MONITOR_FALSE_COLOUR_ENABLE_RESPONSE,
                            "remote-monitor-false-colour-get",
                            "False Colour"
                        )
                    }
                }, 140L)
            } else {
                _monitorAssistEffects.value = _monitorAssistEffects.value.copy(
                    pendingKey = null,
                    status = "False Colour change rejected by recorder"
                )
            }
        }

        if (pending?.purpose?.startsWith("remote-monitor-blue-only:") == true &&
            (messageType == REMOTE_GENERIC_SUCCESS || messageType == 0xFFFF || messageType == 0xFFFE)) {
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                appendEvent("TCS20.05.7 BLUE ONLY ACK unconfirmed=true")
                mainHandler.postDelayed({
                    if (pendingRemotePacket == null) {
                        queueOfficialFocusGetter(
                            REMOTE_MONITOR_BLUE_ONLY_ENABLE_GET,
                            REMOTE_MONITOR_BLUE_ONLY_ENABLE_RESPONSE,
                            "remote-monitor-blue-only-get",
                            "Blue Only"
                        )
                    }
                }, 140L)
            } else {
                _monitorAssistEffects.value = _monitorAssistEffects.value.copy(
                    pendingKey = null,
                    status = "Blue Only change rejected by recorder"
                )
            }
        }

        if (pending?.purpose?.startsWith("remote-monitor-zoom-level-set:") == true) {
            val requested = pending.purpose.substringAfterLast(':').toIntOrNull()
            if (messageType == REMOTE_GENERIC_SUCCESS && requested != null) {
                _monitorZoomCore.value = _monitorZoomCore.value.copy(
                    pendingLevel = requested,
                    status = "${zoomLevelLabel(requested)} accepted; verifying recorder state"
                )
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    lastStatus = "${zoomLevelLabel(requested)} accepted; confirming recorder state",
                    lastError = null
                )
                appendEvent("TCS20.04.5 ZOOM ACK level=$requested response=0x8000 unconfirmed=true")
            } else if (messageType == 0xFFFF || messageType == 0xFFFE) {
                zoomExpectedLevelAfterSet = null
                _monitorZoomCore.value = _monitorZoomCore.value.copy(
                    pendingLevel = null,
                    status = "${zoomLevelLabel(requested ?: -1)} rejected by recorder"
                )
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    lastStatus = "${pending.label} rejected in the current recorder state",
                    lastError = "Recorder rejected ${zoomLevelLabel(requested ?: -1)} (RemoteApi 0x${messageType.toString(16).uppercase()})"
                )
                appendEvent("TCS20.04.5 ZOOM REJECT level=$requested response=0x${messageType.toString(16).uppercase()}")
            }
        }

        if (pending?.purpose?.startsWith("remote-monitor-zoom-pan") == true) {
            if (messageType == REMOTE_GENERIC_SUCCESS) {
                _monitorZoomViewport.value = _monitorZoomViewport.value.copy(
                    pendingPan = true,
                    status = "Pan accepted; verifying recorder viewport"
                )
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    lastStatus = "Zoom viewport move accepted; confirming position",
                    lastError = null
                )
                appendEvent("TCS20.04.7 ZOOM PAN ACK response=0x8000 unconfirmed=true")
            } else if (messageType == 0xFFFF || messageType == 0xFFFE) {
                _monitorZoomViewport.value = _monitorZoomViewport.value.copy(
                    pendingPan = false,
                    status = "Zoom viewport move rejected by recorder"
                )
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    lastStatus = "Zoom viewport move rejected",
                    lastError = "Recorder returned RemoteApi 0x${messageType.toString(16).uppercase()} for PanSet"
                )
                appendEvent("TCS20.04.7 ZOOM PAN REJECT response=0x${messageType.toString(16).uppercase()}")
            }
        }
    }

    private fun resetRemoteStateSnapshot() {
        inputProbeTimeout?.let { mainHandler.removeCallbacks(it) }
        inputProbeTimeout = null
        codecReadbackTimeout?.let { mainHandler.removeCallbacks(it) }
        codecReadbackTimeout = null
        input06aBootstrapPending.clear()
        inputCollectorValues.clear()
        inputCollectorRunning = false
        inputCollectorRefreshPending = false
        activeInputProbe = null
        pendingCodecReadbackExpected = null
        pendingCodecReadbackSource = null
        _inputCodecCollector.value = InputCodecCollectorSnapshot()
        stateSyncPages.clear()
        stateSyncPacketToPage.clear()
        _outputPageRawR19.value = null
        _outputPageRxGenerationR23.value = 0
        appPollingPacketSequence = 0
        stateSyncGeneration = 0
        remoteBootstrapTotal = 0
        _safeAreaSummaryRaw.value = null
        _frameGuideFormatSummaryRaw.value = null
        _remoteStateSnapshot.value = RemoteStateSnapshot()
    }

    private fun appPollingPageLabel(commandId: Int): String = when (commandId) {
        REMOTE_APP_POLLING_APPLICATION_STATE_GET -> "ApplicationStateGet"
        REMOTE_APP_POLLING_MONITOR_PAGE_GET -> "MonitorPageGet"
        REMOTE_APP_POLLING_INPUT_PAGE_GET -> "InputPageGet"
        REMOTE_APP_POLLING_RECORD_PAGE_GET -> "RecordPageGet"
        REMOTE_APP_POLLING_OUTPUT_PAGE_GET -> "OutputPageGet"
        REMOTE_APP_POLLING_INPUT_HDR_PAGE_GET -> "InputPageHdrGet"
        REMOTE_APP_POLLING_PLAYBACK_FORMAT_PAGE_GET -> "PlaybackFormatPageGet"
        REMOTE_APP_POLLING_AUDIO_METERS_PAGE_GET -> "AudioMetersPageGet"
        REMOTE_APP_POLLING_INPUT_OUTPUT_SUMMARY_GET -> "InputOutputSummaryGet"
        REMOTE_APP_POLLING_MONITOR_ASSIST_SUMMARY_GET -> "MonitorAssistSummaryStateGet"
        else -> "AppPolling 0x${commandId.toString(16)}"
    }

    private fun captureAppPollingStatePage(
        commandId: Int,
        responseType: Int,
        body: ByteArray,
        local: Boolean
    ) {
        val label = appPollingPageLabel(commandId)
        val fieldsJson = protoDebugJson(body)
        // AUDIO R26.03: passive, non-mutating wire evidence on the existing
        // correlated AppPolling page route. No new request or transport rule.
        if (commandId == REMOTE_APP_POLLING_AUDIO_METERS_PAGE_GET) {
            val usable = body.isNotEmpty() && responseType != 0xFFFF && responseType != 0xFFFE
            android.util.Log.i("AtomRemoteAudioR26",
                "PAGE_CORRELATED_RX command=0x0647 response=0x${responseType.toString(16)} " +
                    "local=$local bytes=${body.size} usableRaw=$usable payload=${body.toHex()}")
            android.util.Log.i("AtomRemoteAudioR26P2",
                "AB_0647_RX route=${if (local) "LOCAL" else "NONLOCAL"} " +
                    "responseType=0x${responseType.toString(16)} bodyBytes=${body.size} " +
                    "typedNonempty=$usable raw=${body.toHex()}")
        }
        if (commandId == REMOTE_APP_POLLING_INPUT_PAGE_GET ||
            commandId == REMOTE_APP_POLLING_INPUT_HDR_PAGE_GET) {
            appendEvent("INPUT12 BULK PAGE VERIFIED label=$label response=0x${responseType.toString(16).uppercase()} bytes=${body.size} payload=${body.toHex()}")
        }
        if (commandId == REMOTE_APP_POLLING_OUTPUT_PAGE_GET) {
            if (body.isNotEmpty() && responseType != 0xFFFF && responseType != 0xFFFE) {
                _outputPageRawR19.value = body.toHex()
                _outputPageRxGenerationR23.value = _outputPageRxGenerationR23.value + 1
                android.util.Log.i("AtomRemoteOutputR23", "PAGE_VERIFIED_RX generation=${_outputPageRxGenerationR23.value} bytes=${body.size}")
                android.util.Log.i("AtomRemoteOutputR19",
                    "PAGE_TYPED_RX command=0x0644 response=0x${responseType.toString(16)} " +
                        "local=$local payload=${body.toHex()} fields=${protoDebugJson(body)}")
            } else {
                android.util.Log.i("AtomRemoteOutputR19",
                    "PAGE_EMPTY_OR_REJECT response=0x${responseType.toString(16)} " +
                        "bodySize=${body.size} NOT_A_SETTING")
            }
        }
        stateSyncPages[commandId] = RemotePageSnapshot(
            commandId = commandId,
            label = label,
            responseType = responseType,
            local = local,
            rawHex = body.toHex(),
            fieldsJson = fieldsJson
        )

        if (commandId == REMOTE_APP_POLLING_MONITOR_PAGE_GET) {
            appendEvent("TCS20.10.7 MONITOR PAGE RX responseType=0x${responseType.toString(16).uppercase()} local=$local raw=${body.toHex()}")
            // Exact AppPolling.MonitorPageState field order recovered from the
            // latest supplied macOS AtomProtobuf descriptors:
            // 1 monitor_color_space, 2 lut_slot, 3 lut_preview, 4 lut_output.
            val pageSlot = protoVarintField(body, 2)
            val pagePreview = protoVarintField(body, 3)
            val pageOutput = protoVarintField(body, 4)
            val pageTally = protoVarintField(body, 5)
            val pageFlip = protoVarintField(body, 7)
            val pageBacklightWire = protoVarintField(body, 8)
            val pageYLiftRaw = protoVarintField(body, 9)
            val pageYGamma = protoVarintField(body, 10)
            val pageYGain = protoVarintField(body, 11)
            val pageBacklightChangeable = protoVarintField(body, 12)
            val pageFlipChangeable = protoVarintField(body, 13)
            if (pageSlot != null || pagePreview != null || pageOutput != null) {
                _monitorLutState.value = _monitorLutState.value.copy(
                    currentSlot = pageSlot?.takeIf { it in 0..8 } ?: _monitorLutState.value.currentSlot,
                    currentSlotQueryCompleted = pageSlot != null || _monitorLutState.value.currentSlotQueryCompleted,
                    previewEnabled = pagePreview?.let { it != 0 } ?: _monitorLutState.value.previewEnabled,
                    outputLutEnabled = pageOutput?.let { it != 0 } ?: _monitorLutState.value.outputLutEnabled,
                    status = "LUT state synchronized from Monitor page"
                )
                appendEvent(
                    "TCS20.09.R3 LUT MONITOR PAGE slot=${pageSlot ?: -1} " +
                        "preview=${pagePreview ?: -1} output=${pageOutput ?: -1} raw=${body.toHex()}"
                )
            }

            if (
                pageTally != null || pageFlip != null || pageBacklightWire != null ||
                pageYLiftRaw != null || pageYGamma != null || pageYGain != null ||
                pageBacklightChangeable != null || pageFlipChangeable != null
            ) {
                val pageBacklightPercent = pageBacklightWire?.let { raw ->
                    when {
                        raw in 0..100 -> raw
                        raw in 101..1000 -> (raw / 10).coerceIn(0, 100)
                        else -> null
                    }
                }
                val currentDisplay = _monitorDisplayState.value
                _monitorDisplayState.value = currentDisplay.copy(
                    backlightPercent = pageBacklightPercent ?: currentDisplay.backlightPercent,
                    backlightChangeable = pageBacklightChangeable?.let { it != 0 } ?: currentDisplay.backlightChangeable,
                    monitorPageReported = true,
                    yLift = pageYLiftRaw?.let { raw -> when { raw in 0..64 -> raw; raw in 65..127 -> raw - 128; else -> currentDisplay.yLift } } ?: currentDisplay.yLift,
                    yGammaHundredths = pageYGamma?.takeIf { it in 176..304 } ?: currentDisplay.yGammaHundredths,
                    yGainHundredths = pageYGain?.takeIf { it in 50..200 } ?: currentDisplay.yGainHundredths,
                    flipEnabled = pageFlip?.let { it != 0 } ?: currentDisplay.flipEnabled,
                    tallyEnabled = pageTally?.let { it != 0 } ?: currentDisplay.tallyEnabled,
                    flipSupported = when {
                        pageFlipChangeable != null -> pageFlipChangeable != 0
                        pageFlip != null -> true
                        else -> currentDisplay.flipSupported
                    },
                    tallySupported = if (pageTally != null) true else currentDisplay.tallySupported,
                    requestedBacklightPercent = if (pageBacklightWire != null) null else currentDisplay.requestedBacklightPercent,
                    requestedYLift = if (pageYLiftRaw != null) null else currentDisplay.requestedYLift,
                    requestedYGammaHundredths = if (pageYGamma != null) null else currentDisplay.requestedYGammaHundredths,
                    requestedYGainHundredths = if (pageYGain != null) null else currentDisplay.requestedYGainHundredths,
                    requestedFlipEnabled = if (pageFlip != null) null else currentDisplay.requestedFlipEnabled,
                    requestedTallyEnabled = if (pageTally != null) null else currentDisplay.requestedTallyEnabled,
                    pendingKey = when (currentDisplay.pendingKey) {
                        "backlight" -> if (pageBacklightWire != null) null else currentDisplay.pendingKey
                        "lift" -> if (pageYLiftRaw != null) null else currentDisplay.pendingKey
                        "gamma" -> if (pageYGamma != null) null else currentDisplay.pendingKey
                        "gain" -> if (pageYGain != null) null else currentDisplay.pendingKey
                        "reset" -> if (pageYLiftRaw != null && pageYGamma != null && pageYGain != null) null else currentDisplay.pendingKey
                        "flip" -> if (pageFlip != null) null else currentDisplay.pendingKey
                        "tally" -> if (pageTally != null) null else currentDisplay.pendingKey
                        else -> currentDisplay.pendingKey
                    },
                    status = when {
                        currentDisplay.pendingKey == "reset" && pageYLiftRaw != null && pageYGamma != null && pageYGain != null -> "Picture reset values confirmed by recorder"
                        pageBacklightPercent != null -> "Display synchronized; Backlight ${pageBacklightPercent}% reported by recorder"
                        else -> "Display capability/state synchronized from recorder"
                    }
                )
                appendEvent(
                    "TCS20.12.1 DISPLAY MONITOR PAGE tally=${pageTally ?: -1} flip=${pageFlip ?: -1} " +
                        "backlightWire=${pageBacklightWire ?: -1} backlightPercent=${pageBacklightPercent ?: -1} " +
                        "yLiftRaw=${pageYLiftRaw ?: -1} yGamma=${pageYGamma ?: -1} yGain=${pageYGain ?: -1} " +
                        "backlightChangeable=${pageBacklightChangeable ?: -1} flipChangeable=${pageFlipChangeable ?: -1}"
                )
            }
        }

        if (commandId == REMOTE_APP_POLLING_RECORD_PAGE_GET) {
            // RecordPageState: field 3 codec, field 7 codecBitdepth.
            val codec = protoVarintField(body, 3)
            val bitDepth = protoVarintField(body, 7) ?: 0
            if (codec != null && codec > 0) {
                _codecHardwareProfile.value = codec to bitDepth
                appendEvent("TCS19.12.9 RECORD PAGE codec=$codec bitDepth=$bitDepth raw=${body.toHex()}")
            }
        }

        if (commandId == REMOTE_APP_POLLING_MONITOR_ASSIST_SUMMARY_GET) {
            // MonitorAssistSummaryState storage order recovered from AtomProtobuf.
            // 20.04.8: field 8 is recorder-backed current Zoom fallback when the
            // direct ZoomLevelGet path returns only transport completion.
            protoVarintField(body, 8)?.let { summaryZoom ->
                if (summaryZoom in 0..2) {
                    _zoomHardwareLevel.value = summaryZoom
                    val expected = zoomSummaryExpectedLevel
                    val matched = expected == null || expected == summaryZoom
                    _monitorZoomCore.value = _monitorZoomCore.value.copy(
                        currentLevel = summaryZoom,
                        pendingLevel = null,
                        status = when {
                            expected == null -> "${zoomLevelLabel(summaryZoom)} synchronized from recorder state"
                            matched -> "${zoomLevelLabel(summaryZoom)} confirmed by recorder state"
                            else -> "Recorder kept ${zoomLevelLabel(summaryZoom)}"
                        },
                        readbackTimedOut = false
                    )
                    appendEvent(
                        "TCS20.04.8 ZOOM SUMMARY READBACK actual=$summaryZoom expected=${expected ?: -1} matched=$matched source=MonitorAssistSummary"
                    )
                    if (expected != null) zoomSummaryExpectedLevel = null
                }
            }
            _frameGuideFormatSummaryRaw.value = protoVarintField(body, 9)
            _safeAreaSummaryRaw.value = protoVarintField(body, 10)
            protoVarintField(body, 11)?.let { if (it in 0..9) _anamorphicHardwareMode.value = it }
            protoVarintField(body, 13)?.let {
                val enabled = it != 0
                _frameGridHardwareEnabled.value = enabled
                val currentGuide = _monitorGuideState.value
                _monitorGuideState.value = currentGuide.copy(
                    gridEnabled = enabled,
                    pendingKey = if (currentGuide.pendingKey == "grid") null else currentGuide.pendingKey,
                    status = if (currentGuide.pendingKey == "grid") "9-Grid ${if (enabled) "On" else "Off"} confirmed by recorder" else currentGuide.status
                )
                appendEvent("TCS20.13.3 GRID SUMMARY READBACK enabled=${if (enabled) 1 else 0} source=MonitorAssistSummary")
            }
            protoVarintField(body, 14)?.let {
                val enabled = it != 0
                val currentGuide = _monitorGuideState.value
                _monitorGuideState.value = currentGuide.copy(
                    frameGuideEnabled = enabled,
                    pendingKey = if (currentGuide.pendingKey == "frame-guide") null else currentGuide.pendingKey,
                    status = if (currentGuide.pendingKey == "frame-guide") "Frame Guide ${if (enabled) "On" else "Off"} confirmed by recorder" else currentGuide.status
                )
                appendEvent("TCS20.13.2 FRAME GUIDE SUMMARY READBACK enabled=${if (enabled) 1 else 0} source=MonitorAssistSummary")
            }
            protoVarintField(body, 15)?.let {
                _monitorGuideState.value = _monitorGuideState.value.copy(mode = it)
            }
            protoVarintField(body, 16)?.let {
                _monitorGuideState.value = _monitorGuideState.value.copy(customRatio = it)
            }
            _frameGuideFormatSummaryRaw.value?.let {
                _monitorGuideState.value = _monitorGuideState.value.copy(format = it)
            }
            appendEvent(
                "TCS19.12.9 MONITOR ASSIST SUMMARY zoom=${protoVarintField(body, 8)} " +
                    "frameGuide=${_frameGuideFormatSummaryRaw.value} safeAreaRaw=${_safeAreaSummaryRaw.value} " +
                    "grid=${protoVarintField(body, 13)} frameGuideEnable=${protoVarintField(body, 14)} " +
                    "frameGuideMode=${protoVarintField(body, 15)} custom=${protoVarintField(body, 16)}"
            )
        }

        publishRemoteStateSnapshot(cycleComplete = false, source = "page-$label")
        appendEvent(
            "TCS19.12.9 STATE PAGE id=0x${commandId.toString(16)} label=$label " +
                "response=0x${responseType.toString(16)} local=$local " +
                "pages=${stateSyncPages.size}/$APP_POLLING_STATE_PAGE_COUNT fields=$fieldsJson raw=${body.toHex()}"
        )
    }

    private fun publishRemoteStateSnapshot(cycleComplete: Boolean, source: String) {
        val ordered = stateSyncPages.toSortedMap()
        val q = 34.toChar()
        val pageChunks = ordered.values.map { page ->
            val key = "0x${page.commandId.toString(16).padStart(4, '0')}"
            buildString {
                append(q); append(jsonEscape(key)); append(q); append(':'); append('{')
                append(q); append("label"); append(q); append(':'); append(q); append(jsonEscape(page.label)); append(q); append(',')
                append(q); append("responseType"); append(q); append(':'); append(page.responseType); append(',')
                append(q); append("local"); append(q); append(':'); append(page.local); append(',')
                append(q); append("rawHex"); append(q); append(':'); append(q); append(jsonEscape(page.rawHex)); append(q); append(',')
                append(q); append("fields"); append(q); append(':'); append(page.fieldsJson); append('}')
            }
        }
        val codec = _codecHardwareProfile.value
        val json = buildString {
            append('{')
            append(q); append("generation"); append(q); append(':'); append(stateSyncGeneration); append(',')
            append(q); append("source"); append(q); append(':'); append(q); append(jsonEscape(source)); append(q); append(',')
            append(q); append("cycleComplete"); append(q); append(':'); append(cycleComplete); append(',')
            append(q); append("expectedPages"); append(q); append(':'); append(APP_POLLING_STATE_PAGE_COUNT); append(',')
            append(q); append("receivedPages"); append(q); append(':'); append(ordered.size); append(',')
            append(q); append("derived"); append(q); append(':'); append('{')
            append(q); append("codec"); append(q); append(':'); append(codec?.first ?: -1); append(',')
            append(q); append("codecBitDepth"); append(q); append(':'); append(codec?.second ?: -1); append(',')
            append(q); append("zoomLevel"); append(q); append(':'); append(_zoomHardwareLevel.value ?: -1); append(',')
            append(q); append("safeAreaRaw"); append(q); append(':'); append(_safeAreaSummaryRaw.value ?: -1); append(',')
            append(q); append("frameGuideFormat"); append(q); append(':'); append(_frameGuideFormatSummaryRaw.value ?: -1); append(',')
            append(q); append("grid3x3"); append(q); append(':'); append(_frameGridHardwareEnabled.value ?: false); append(',')
            append(q); append("inputCodec"); append(q); append(':'); append(_inputCodecId.value ?: -1); append(',')
            append(q); append("inputCamera"); append(q); append(':'); append(_inputCameraId.value ?: -1); append(',')
            append(q); append("inputGamma"); append(q); append(':'); append(_inputGammaId.value ?: -1); append(',')
            append(q); append("inputGamut"); append(q); append(':'); append(_inputGamutId.value ?: -1); append(',')
            append(q); append("outputHdrType"); append(q); append(':'); append(_outputHdrTypeId.value ?: -1); append(',')
            append(q); append("outputGamut"); append(q); append(':'); append(_outputGamutId.value ?: -1)
            append('}'); append(',')
            append(q); append("pages"); append(q); append(':'); append('{')
            append(pageChunks.joinToString(","))
            append('}'); append('}')
        }
        _remoteStateSnapshot.value = RemoteStateSnapshot(
            generation = stateSyncGeneration,
            cycleComplete = cycleComplete,
            expectedPages = APP_POLLING_STATE_PAGE_COUNT,
            pages = LinkedHashMap(ordered),
            json = json
        )
        if (cycleComplete) appendEvent("TCS_STATE_SNAPSHOT $json")
    }

    private fun jsonEscape(value: String): String = buildString(value.length + 8) {
        val slash = 92.toChar()
        value.forEach { ch ->
            when (ch) {
                slash -> { append(slash); append(slash) }
                34.toChar() -> { append(slash); append(34.toChar()) }
                10.toChar() -> { append(slash); append('n') }
                13.toChar() -> { append(slash); append('r') }
                9.toChar() -> { append(slash); append('t') }
                else -> if (ch.code < 0x20) append(' ') else append(ch)
            }
        }
    }

    private fun protoDebugJson(data: ByteArray): String {
        val entries = mutableListOf<String>()
        val q = 34.toChar()
        var index = 0
        while (index < data.size) {
            val keyRead = readProtoVarint(data, index) ?: break
            val key = keyRead.first
            index = keyRead.second
            val field = (key ushr 3).toInt()
            val wire = (key and 0x07).toInt()
            when (wire) {
                0 -> {
                    val vr = readProtoVarint(data, index) ?: break
                    entries += buildString {
                        append('{'); append(q); append("field"); append(q); append(':'); append(field); append(',')
                        append(q); append("wire"); append(q); append(':'); append(0); append(',')
                        append(q); append("value"); append(q); append(':'); append(vr.first); append('}')
                    }
                    index = vr.second
                }
                1 -> {
                    if (index + 8 > data.size) break
                    val bytes = data.copyOfRange(index, index + 8)
                    entries += buildString {
                        append('{'); append(q); append("field"); append(q); append(':'); append(field); append(',')
                        append(q); append("wire"); append(q); append(':'); append(1); append(',')
                        append(q); append("hex"); append(q); append(':'); append(q); append(bytes.toHex()); append(q); append('}')
                    }
                    index += 8
                }
                2 -> {
                    val lr = readProtoVarint(data, index) ?: break
                    index = lr.second
                    val len = lr.first.toInt()
                    if (len < 0 || index + len > data.size) break
                    val bytes = data.copyOfRange(index, index + len)
                    val text = printableProtoText(bytes)
                    entries += buildString {
                        append('{'); append(q); append("field"); append(q); append(':'); append(field); append(',')
                        append(q); append("wire"); append(q); append(':'); append(2); append(',')
                        append(q); append("len"); append(q); append(':'); append(len); append(',')
                        append(q); append("hex"); append(q); append(':'); append(q); append(bytes.toHex()); append(q)
                        if (!text.isNullOrBlank()) {
                            append(','); append(q); append("text"); append(q); append(':'); append(q); append(jsonEscape(text)); append(q)
                        }
                        append('}')
                    }
                    index += len
                }
                5 -> {
                    if (index + 4 > data.size) break
                    val bytes = data.copyOfRange(index, index + 4)
                    entries += buildString {
                        append('{'); append(q); append("field"); append(q); append(':'); append(field); append(',')
                        append(q); append("wire"); append(q); append(':'); append(5); append(',')
                        append(q); append("hex"); append(q); append(':'); append(q); append(bytes.toHex()); append(q); append('}')
                    }
                    index += 4
                }
                else -> break
            }
        }
        return entries.joinToString(prefix = "[", postfix = "]")
    }

    private fun zoomLevelLabel(level: Int): String = when (level) {
        0 -> "Zoom Off"
        1 -> "Zoom 1:1"
        2 -> "Zoom 2:1"
        else -> "Zoom $level"
    }

    /**
     * Decode protobuf field #1 repeated varints. SwiftProtobuf may encode a
     * repeated scalar either unpacked (08 01 08 02) or packed (0A 02 01 02).
     * Unknown fields are skipped by protobuf wire type so this remains tolerant
     * of firmware adding fields to MonitorAssistZoomLevelListMsg.
     */
    private fun parseProtoRepeatedVarintField1(data: ByteArray): List<Int> {
        val out = mutableListOf<Int>()
        var index = 0
        while (index < data.size) {
            val keyRead = readProtoVarint(data, index) ?: break
            val key = keyRead.first
            index = keyRead.second
            val field = (key ushr 3).toInt()
            val wire = (key and 0x07).toInt()
            when (wire) {
                0 -> {
                    val valueRead = readProtoVarint(data, index) ?: break
                    if (field == 1 && valueRead.first <= Int.MAX_VALUE.toLong()) out += valueRead.first.toInt()
                    index = valueRead.second
                }
                1 -> {
                    if (index + 8 > data.size) break
                    index += 8
                }
                2 -> {
                    val lengthRead = readProtoVarint(data, index) ?: break
                    index = lengthRead.second
                    val length = lengthRead.first.toInt()
                    if (length < 0 || index + length > data.size) break
                    val end = index + length
                    if (field == 1) {
                        while (index < end) {
                            val packed = readProtoVarint(data, index) ?: break
                            if (packed.first <= Int.MAX_VALUE.toLong()) out += packed.first.toInt()
                            index = packed.second
                        }
                        index = end
                    } else {
                        index = end
                    }
                }
                5 -> {
                    if (index + 4 > data.size) break
                    index += 4
                }
                else -> break
            }
        }
        return out
    }

    /** Compatibility decoder for capability-list messages whose repeated enum
     * field number differs across firmware. It only returns small scalar values;
     * the recorder still has final authority and may reject an unsupported setter. */
    private fun parseProtoSmallVarintsAnyField(data: ByteArray): List<Int> {
        val out = mutableListOf<Int>()
        var index = 0
        while (index < data.size) {
            val keyRead = readProtoVarint(data, index) ?: break
            val wire = (keyRead.first and 0x07).toInt()
            index = keyRead.second
            when (wire) {
                0 -> {
                    val valueRead = readProtoVarint(data, index) ?: break
                    val value = valueRead.first
                    if (value in 0..4) out += value.toInt()
                    index = valueRead.second
                }
                1 -> if (index + 8 <= data.size) index += 8 else break
                2 -> {
                    val lenRead = readProtoVarint(data, index) ?: break
                    index = lenRead.second
                    val len = lenRead.first.toInt()
                    if (len < 0 || index + len > data.size) break
                    val end = index + len
                    var packed = index
                    while (packed < end) {
                        val vr = readProtoVarint(data, packed) ?: break
                        if (vr.first in 0..4) out += vr.first.toInt()
                        packed = vr.second
                    }
                    index = end
                }
                5 -> if (index + 4 <= data.size) index += 4 else break
                else -> break
            }
        }
        return out
    }

    private fun protoVarintField(data: ByteArray, targetField: Int): Int? {
        var index = 0
        while (index < data.size) {
            val keyRead = readProtoVarint(data, index) ?: return null
            val key = keyRead.first
            index = keyRead.second
            val field = (key ushr 3).toInt()
            when ((key and 0x07).toInt()) {
                0 -> {
                    val valueRead = readProtoVarint(data, index) ?: return null
                    if (field == targetField && valueRead.first <= Int.MAX_VALUE.toLong()) return valueRead.first.toInt()
                    index = valueRead.second
                }
                1 -> if (index + 8 <= data.size) index += 8 else return null
                2 -> {
                    val lenRead = readProtoVarint(data, index) ?: return null
                    index = lenRead.second
                    val len = lenRead.first.toInt()
                    if (len < 0 || index + len > data.size) return null
                    index += len
                }
                5 -> if (index + 4 <= data.size) index += 4 else return null
                else -> return null
            }
        }
        return null
    }

    private fun protoLengthDelimitedField(data: ByteArray, targetField: Int): ByteArray? {
        var index = 0
        while (index < data.size) {
            val keyRead = readProtoVarint(data, index) ?: return null
            val key = keyRead.first
            index = keyRead.second
            val field = (key ushr 3).toInt()
            when ((key and 0x07).toInt()) {
                0 -> {
                    val valueRead = readProtoVarint(data, index) ?: return null
                    index = valueRead.second
                }
                1 -> if (index + 8 <= data.size) index += 8 else return null
                2 -> {
                    val lenRead = readProtoVarint(data, index) ?: return null
                    index = lenRead.second
                    val len = lenRead.first.toInt()
                    if (len < 0 || index + len > data.size) return null
                    if (field == targetField) return data.copyOfRange(index, index + len)
                    index += len
                }
                5 -> if (index + 4 <= data.size) index += 4 else return null
                else -> return null
            }
        }
        return null
    }

    private fun printableProtoText(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val text = runCatching { data.toString(Charsets.UTF_8) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val printable = text.count { it == '\n' || it == '\r' || it == '\t' || it.code in 0x20..0x7E }
        return if (printable * 10 >= text.length * 8) text.replace('\u0000', ' ').trim() else null
    }

    private fun readProtoVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var index = start
        var shift = 0
        var value = 0L
        while (index < data.size && shift < 64) {
            val b = data[index].toInt() and 0xFF
            value = value or ((b and 0x7F).toLong() shl shift)
            index++
            if ((b and 0x80) == 0) return value to index
            shift += 7
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun enqueueRemoteCreditRequest(
        current: BluetoothGatt,
        writeChar: BluetoothGattCharacteristic,
        packet: PendingRemotePacket
    ): Boolean {
        // Exact _writeSubPacket fallback when peer credit is lost after the session
        // has already been primed. RemoteApi packets are NON-LOCAL in the official
        // macOS CommandManager (m_isLocal = 0). The local bit is reserved for the
        // app's TCSCommand family, not ATOMOS recorder RemoteApi messages.
        // Therefore the credit/request turn must keep local=false as well.
        val header = buildOfficialPacketLinkHeader(local = false)
        val frame = byteArrayOf(header.toByte())
        val writeType = packetLinkWriteType(writeChar)
        pendingOps.addLast(
            PendingOp.WriteCharacteristic(
                writeChar,
                frame,
                writeType,
                "remote-credit-request:${packet.purpose}"
            )
        )
        _tcsRemoteState.value = _tcsRemoteState.value.copy(lastTxHex = frame.toHex())
        appendEvent("TCS18.7 FLOW TX CREDIT m_isLocal=0 label=${packet.label} packetId=${packet.packetId} header=${frame.toHex()} payload=none firstNow=$tcsPacketFirstSubpacket")
        if (!operationInFlight) processNext(current)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun enqueueRemoteDataFrame(
        current: BluetoothGatt,
        writeChar: BluetoothGattCharacteristic,
        packet: PendingRemotePacket
    ): Boolean {
        // macOS CommandManager passes AtomMessage.m_isLocal into
        // TCSBleManager_SendPacketToDevice. Normal ATOMOS RemoteApi commands
        // (Record/Stop/Play/Pause and state queries) are m_isLocal = 0. Only the
        // separate TCSCommand class uses m_isLocal = 1. 18.1-18.6 incorrectly
        // routed recorder commands as local packets, producing E8 frames that the
        // Shogun acknowledged at PacketLink level but did not dispatch to RemoteApi.
        tcsPacketLocalFlag = false
        remoteEmptyPolls = 0
        remoteResponseStartedAtMs = System.currentTimeMillis()
        armRemoteResponseTimeout(packet)
        startPacketLinkProcessLoop()
        val header = buildOfficialPacketLinkHeader(local = false)
        val frame = ByteArray(packet.body.size + 1)
        frame[0] = header.toByte()
        packet.body.copyInto(frame, destinationOffset = 1)
        val writeType = packetLinkWriteType(writeChar)
        pendingOps.addFirst(
            PendingOp.WriteCharacteristic(
                writeChar,
                frame,
                writeType,
                "remote-data:${packet.purpose}"
            )
        )
        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = "sending",
            lastTxHex = frame.toHex(),
            lastStatus = "Official AtomMessage data frame queued"
        )
        val packetIdRule = when {
            packet.bootstrap && packet.commandId in REMOTE_APP_POLLING_APPLICATION_STATE_GET..REMOTE_APP_POLLING_MONITOR_ASSIST_SUMMARY_GET -> "official-app-polling-sequence"
            packet.bootstrap -> "bootstrap-sequence"
            else -> "official-one-shot-zero"
        }
        appendEvent("TCS18.7 REMOTE DATA TX m_isLocal=0 label=${packet.label} cmd=${packet.commandId} packetId=${packet.packetId} frame=${frame.toHex()} credit=$tcsPacketRemoteCredit packetIdRule=$packetIdRule")
        if (!operationInFlight) processNext(current)
        return true
    }


    private fun startPacketLinkProcessLoop() {
        if (packetLinkProcessEnabled) return
        packetLinkProcessEnabled = true
        mainHandler.removeCallbacks(packetLinkProcessTick)
        mainHandler.postDelayed(packetLinkProcessTick, PACKET_LINK_PROCESS_TICK_MS)
        appendEvent("TCS18.8 PACKET PROCESS LOOP START interval=${PACKET_LINK_PROCESS_TICK_MS}ms stateMachine=READY/WRITING/READING")
    }

    private fun stopPacketLinkProcessLoop() {
        packetLinkProcessEnabled = false
        mainHandler.removeCallbacks(packetLinkProcessTick)
    }

    private fun cancelRemoteResponseTimeout() {
        remoteResponseTimeout?.let { mainHandler.removeCallbacks(it) }
        remoteResponseTimeout = null
    }

    private fun cancelRemoteBootstrapSettle() {
        remoteBootstrapSettle?.let { mainHandler.removeCallbacks(it) }
        remoteBootstrapSettle = null
    }

    // AGENT7 20.03.4.2 POST-READY AUTO INPUT
    //
    // 20.03.4.1 proved that waiting inside refreshGranularInputState() is too late:
    // on a fresh accepted pairing no Input refresh request reaches that branch.
    // The exact authoritative transition is scheduleRemoteBootstrapSettle():
    // remoteBootstrapCompleted=true + Remote state LINKED.
    //
    // Hook exactly there, then wait for one real PacketLink ATT idle slot before
    // starting the existing proven three-getter collector. No TCS pairing,
    // PacketLink framing, command IDs, or getter payloads are changed.
    private fun schedulePostReadyInputCollector() {
        if (postReadyInputAutoTriggered || inputCollectorRunning) return
        if (!_tcsPairingState.value.acceptWritten || !_tcsRemoteState.value.available) return
        if (!remoteBootstrapCompleted) return

        postReadyInputAutoTask?.let { mainHandler.removeCallbacks(it) }

        var checks = 0
        lateinit var task: Runnable
        task = Runnable {
            if (postReadyInputAutoTriggered || inputCollectorRunning) {
                postReadyInputAutoTask = null
                return@Runnable
            }

            checks += 1
            val idle =
                remoteBootstrapCompleted &&
                !remoteBootstrapActive &&
                pendingRemotePacket == null &&
                pendingOps.isEmpty() &&
                !operationInFlight

            if (idle) {
                postReadyInputAutoTriggered = true
                postReadyInputAutoTask = null
                appendEvent(
                    "TCS20.03.4.2 POST-READY AUTO INPUT fire checks=$checks"
                )
                val started = refreshGranularInputState()
                appendEvent(
                    "TCS20.03.4.2 POST-READY AUTO INPUT started=$started generation=$inputCollectorGeneration"
                )
                if (!started) {
                    postReadyInputAutoTriggered = false
                    mainHandler.postDelayed({ schedulePostReadyInputCollector() }, 350L)
                }
            } else if (checks < 60) {
                if (checks == 1 || checks % 8 == 0) {
                    appendEvent(
                        "TCS20.03.4.2 POST-READY AUTO INPUT wait checks=$checks " +
                            "bootstrap=$remoteBootstrapActive pending=${pendingRemotePacket?.label} " +
                            "attBusy=$operationInFlight queue=${pendingOps.size}"
                    )
                }
                mainHandler.postDelayed(task, 250L)
            } else {
                postReadyInputAutoTask = null
                appendEvent(
                    "TCS20.03.4.2 POST-READY AUTO INPUT timeout " +
                        "pending=${pendingRemotePacket?.label} attBusy=$operationInFlight queue=${pendingOps.size}"
                )
            }
        }

        postReadyInputAutoTask = task
        appendEvent("TCS20.03.4.2 POST-READY AUTO INPUT armed")
        mainHandler.postDelayed(task, 120L)
    }


    // WL-3.5 READ-ONLY NETWORK REQUEST-SHAPE MATRIX
    // Rebased on the current user-confirmed Frame Guide working GattClient baseline.
    // GETTERS ONLY. No Wi-Fi / LiveMode / Network setter is transmitted.
    private data class Wl32NetworkGetter(val commandId: Int, val label: String)
    private data class Wl35PendingProbe(
        val getter: Wl32NetworkGetter,
        val variant: Int,
        val localRoute: Boolean,
        val protoTrue: Boolean
    )

    // WL-3.3 ID recovery proof:
    // Input metadata order is sequential and matches known hardware IDs:
    // SourceGet 0x0064 -> DeviceNameGet 0x0070 -> HdrEnableStateGet 0x0071.
    // In the official macOS binary, Connect has 22 commands and Debug has 14
    // commands immediately before Display.BacklightBrightnessSet, which is
    // hardware-proven as 0x0320. Therefore ConnectModeSet = 0x02FC and
    // ConnectModeGet..NetworkConfigGet map to 0x02FD..0x0311.
    // WL-3.5 isolates only the three recorder identity/network getters.
    // Hardware evidence now distinguishes routes:
    //   local=true + EMPTY + sequential packetId -> typed 0xFFFE reject
    //   local=false + EMPTY -> transport completes with no correlated payload
    // This phase keeps the same candidate IDs and tests the four read-only
    // request envelopes already used by the proven Input getter route matrix.
    // WL-P3 SOURCE PROTOTYPE:
    // Evidence-backed request shape only: remote route + normal packetId + EMPTY payload.
    // No local route, no 08 01 payload, no setter.
    //
    // NetworkConfig + LiveMode GETs are read-only diagnostic requests.
    private val wl32NetworkGetters = listOf(
        Wl32NetworkGetter(WlConnectPrototype.WIFI_SSID_GET, "WifiSsidGet"),
        Wl32NetworkGetter(WlConnectPrototype.IP_ADDRESS_GET, "IpAddressGet"),
        Wl32NetworkGetter(WlConnectPrototype.EUI48_GET, "Eui48Get"),
        Wl32NetworkGetter(WlConnectPrototype.NETWORK_CONFIG_GET, "NetworkConfigGet"),
        Wl32NetworkGetter(0x02FF, "LiveModeValidGet"),
        Wl32NetworkGetter(0x0300, "LiveModeEnabledGet")
    )

    private var wl32NetworkProbeTask: Runnable? = null
    private var wl32NetworkProbeIndex: Int = 0
    private var wl35NetworkProbeVariant: Int = 0

    // WL-3.5: keep the full route/request-shape tuple for asynchronous response
    // correlation. This remains read-only and is cleared on disconnect/re-arm.
    private val wl32PendingByPacketId = mutableMapOf<Int, Wl35PendingProbe>()

    private fun wl32AsciiPreview(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "<empty>"
        return buildString {
            bytes.forEach { b ->
                val v = b.toInt() and 0xFF
                append(if (v in 0x20..0x7E) v.toChar() else '.')
            }
        }.take(96)
    }

    @SuppressLint("MissingPermission")
    private fun queueWl32NetworkGetter(getter: Wl32NetworkGetter, variant: Int): Boolean {
        if (!remoteBootstrapCompleted || !_tcsRemoteState.value.linked) return false
        if (pendingRemotePacket != null) return false
        if (variant != 0) {
            appendEvent("WLP2 BLOCK non-evidence variant=$variant label=${getter.label}")
            return false
        }

        val current = gatt ?: return false
        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return false
        service.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return false

        // WL-P2 evidence-backed read-only envelope:
        //   m_isLocal = false
        //   packetId = normal non-zero sequence
        //   protobuf payload = EMPTY
        //
        // WL-5.7 proved this envelope reaches PacketLink. The previous local and
        // 08 01 variants are intentionally no longer transmitted.
        tcsCommandSequence = if (tcsCommandSequence >= 255) 1 else tcsCommandSequence + 1
        val packetId = tcsCommandSequence
        val body = buildOfficialAtomMessageBodyWithProtoPayload(
            packetId,
            getter.commandId,
            byteArrayOf()
        )
        val packet = PendingRemotePacket(
            body = body,
            purpose = "wlp2-connect-readonly:${getter.label}",
            label = "WL-P2 ${getter.label}",
            commandId = getter.commandId,
            packetId = packetId,
            expectedResponseId = null
        )
        pendingRemotePacket = packet
        pendingRemoteFrameWritten = false
        wl32PendingByPacketId[packetId] = Wl35PendingProbe(
            getter = getter,
            variant = 0,
            localRoute = false,
            protoTrue = false
        )

        appendEvent(
            "WLP3 TX READONLY label=${getter.label} cmd=0x${getter.commandId.toString(16).uppercase()} " +
                "variant=0 packetId=$packetId m_isLocal=0 payload=EMPTY expected=UNKNOWN"
        )
        return queueRemotePacket18_8(packet, local = false)
    }
    private fun cancelWl32NetworkReadback() {
        wl32NetworkProbeTask?.let(mainHandler::removeCallbacks)
        wl32NetworkProbeTask = null
        wl32NetworkProbeIndex = 0
        wl35NetworkProbeVariant = 0
        wl32PendingByPacketId.clear()
    }

    private fun scheduleWl32NetworkReadback(delayMs: Long = 650L) {
        cancelWl32NetworkReadback()
        wl32NetworkProbeIndex = 0
        wl35NetworkProbeVariant = 0

        val task = object : Runnable {
            override fun run() {
                if (!remoteBootstrapCompleted || !_tcsRemoteState.value.linked) {
                    appendEvent("WL35 STOP reason=remote-not-linked")
                    wl32NetworkProbeTask = null
                    return
                }

                if (wl32NetworkProbeIndex >= wl32NetworkGetters.size) {
                    appendEvent(
                        "WLP3 COMPLETE getters=${wl32NetworkGetters.size} variants=1 " +
                            "attempts=${wl32NetworkGetters.size} mutationCommands=0 " +
                            "shape=remote-empty pendingCorrelation=${wl32PendingByPacketId.size}"
                    )
                    wl32NetworkProbeTask = null
                    return
                }

                if (pendingRemotePacket != null || operationInFlight || pendingOps.isNotEmpty()) {
                    mainHandler.postDelayed(this, 160L)
                    return
                }

                val getter = wl32NetworkGetters[wl32NetworkProbeIndex]
                if (queueWl32NetworkGetter(getter, 0)) {
                    wl35NetworkProbeVariant = 0
                    wl32NetworkProbeIndex++
                    // Give any asynchronous typed response room to arrive.
                    // PacketLink transport itself remains unchanged.
                    mainHandler.postDelayed(this, 1000L)
                } else {
                    mainHandler.postDelayed(this, 180L)
                }
            }
        }

        wl32NetworkProbeTask = task
        appendEvent(
            "WLP3 ARMED getters=${wl32NetworkGetters.size} variants=1 readonly=1 " +
                "matrix=remote-empty-only mutationCommands=0 delayMs=$delayMs"
        )
        mainHandler.postDelayed(task, delayMs)
    }

    private fun scheduleRemoteBootstrapSettle() {
        cancelRemoteBootstrapSettle()
        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = "bootstrapping",
                    available = true,
                    linked = _tcsRemoteState.value.linked,
            lastStatus = "RemoteApi startup sent; draining PacketLink state",
            lastError = null
        )
        val task = Runnable {
            remoteBootstrapSettle = null
            remoteBootstrapCompleted = true
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                phase = "linked",
                available = true,
                linked = true,
                lastStatus = if (remoteBootstrapResponses > 0) {
                    "Remote ready Ã¢â‚¬Â¢ $remoteBootstrapResponses state response${if (remoteBootstrapResponses == 1) "" else "s"}"
                } else {
                    "Remote ready Ã¢â‚¬Â¢ macOS-parity PacketLink active"
                },
                lastError = null
            )
            publishRemoteStateSnapshot(cycleComplete = true, source = "connection-complete")
            appendEvent("TCS19.12.9 STATE SYNC COMPLETE sent=$remoteBootstrapTotal payloadResponses=$remoteBootstrapResponses statePages=${stateSyncPages.size}/$APP_POLLING_STATE_PAGE_COUNT; Remote controls enabled")
            appendEvent("WL35 LINKED HOOK scheduling read-only network request-shape matrix")
            scheduleWl32NetworkReadback()
            if (PacketLinkSessionPolicy.AUTO_POST_READY_INPUT_ENABLED) {
                appendEvent("TCS20.03.4.2 POST-READY AUTO INPUT linked-hook")
                schedulePostReadyInputCollector()
            } else {
                appendEvent(
                    "TCS20.03.6.3 POST-READY AUTO INPUT SUPPRESSED; manual Input refresh only"
                )
            }
            if (stateSyncPages.size < APP_POLLING_STATE_PAGE_COUNT) {
                scheduleStateSyncRecoveryWatchdog("bootstrap-complete", 1400L)
            } else {
                cancelStateSyncRecoveryWatchdog()
            }
        }
        remoteBootstrapSettle = task
        mainHandler.postDelayed(task, REMOTE_BOOTSTRAP_DRAIN_MS)
    }

    private fun armRemoteResponseTimeout(packet: PendingRemotePacket) {
        cancelRemoteResponseTimeout()
        val task = Runnable {
            if (pendingRemotePacket !== packet) return@Runnable
            if (outputR7IsTrackedPurpose(packet.purpose)) {
                outputR7Trace("RESPONSE_TIMEOUT purpose=${packet.purpose} cmd=0x${packet.commandId.toString(16)} packetId=${packet.packetId} frameWritten=$pendingRemoteFrameWritten E0polls=$remoteEmptyPolls")
            }
            pendingRemotePacket = null
            pendingRemoteFrameWritten = false
            remoteEmptyPolls = 0
            if (packet.purpose.startsWith("remote-scope-") && packet.purpose.endsWith("-get")) {
                val stage = scopeRefreshStage
                _monitorScopeState.value = _monitorScopeState.value.copy(
                    pendingKey = null,
                    status = "Some scope values were not reported; available controls remain active"
                )
                appendEvent("TCS20.06.5 SCOPE READBACK TIMEOUT purpose=${packet.purpose} stage=$stage controls-unlocked=true")
                val nextStage = when (stage) {
                    1 -> 2
                    2 -> 3
                    3 -> 4
                    4 -> 5
                    else -> 0
                }
                if (nextStage > 0) {
                    scopeRefreshStage = nextStage
                    mainHandler.postDelayed({
                        if (pendingRemotePacket == null) {
                            when (nextStage) {
                                2 -> queueOfficialScopeGetter(REMOTE_MONITOR_WAVEFORM_MODE_GET, REMOTE_MONITOR_WAVEFORM_MODE_RESPONSE, "remote-scope-mode-get", "Waveform Mode")
                                3 -> queueOfficialScopeGetter(REMOTE_MONITOR_WAVEFORM_SIZE_GET, REMOTE_MONITOR_WAVEFORM_SIZE_RESPONSE, "remote-scope-size-get", "Waveform Size")
                                4 -> queueOfficialScopeGetter(REMOTE_MONITOR_WAVEFORM_OPACITY_GET, REMOTE_MONITOR_WAVEFORM_OPACITY_RESPONSE, "remote-scope-opacity-get", "Waveform Opacity")
                                5 -> queueOfficialScopeGetter(REMOTE_MONITOR_WAVEFORM_BRIGHTNESS_GET, REMOTE_MONITOR_WAVEFORM_BRIGHTNESS_RESPONSE, "remote-scope-brightness-get", "Waveform Brightness")
                            }
                        }
                    }, 140L)
                } else {
                    scopeRefreshStage = 0
                }
                return@Runnable
            }

            if ((packet.purpose.startsWith("remote-monitor-focus-") ||
                    packet.purpose.startsWith("remote-monitor-zebra-")) &&
                packet.purpose.endsWith("-get")) {
                val timedOutStage = focusRefreshStage
                if (packet.purpose.startsWith("remote-monitor-focus-")) {
                    _monitorFocusAssist.value = _monitorFocusAssist.value.copy(
                        pendingKey = null,
                        status = "Some Focus settings were not reported by recorder"
                    )
                } else {
                    _monitorZebraState.value = _monitorZebraState.value.copy(
                        pendingKey = null,
                        status = "Some Zebra settings were not reported by recorder"
                    )
                }
                appendEvent("TCS20.06 MONITOR ASSIST READBACK TIMEOUT purpose=${packet.purpose} stage=$timedOutStage controls-unlocked=true")

                // Do not strand the rest of the Monitor Assist chain when one
                // optional getter is absent on this recorder.
                val nextStage = when (timedOutStage) {
                    1 -> 2
                    2 -> 3
                    3 -> 4
                    4 -> 5
                    5 -> 6
                    6 -> 7
                    else -> 0
                }
                if (nextStage > 0) {
                    scheduleFocusRefreshStep(nextStage, 120L)
                } else {
                    focusRefreshStage = 0
                    focusRefreshRetry = 0
                }
                return@Runnable
            }

            if (packet.purpose.startsWith("remote-monitor-mode-get:")) {
                val expected = monitorModeReadbackExpected
                monitorModeReadbackExpected = null
                monitorModeReadbackSource = null
                _monitorCoreSnapshot.value = _monitorCoreSnapshot.value.copy(
                    pendingMode = null,
                    status = if (expected != null) {
                        "Recorder changed mode, but current Monitor mode readback was not returned"
                    } else {
                        "Current Monitor mode was not reported by recorder"
                    }
                )
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = if (remoteApiVerified) "linked" else "transport-ready",
                    available = true,
                    linked = true,
                    lastStatus = "Monitor mode readback unavailable; controls remain active",
                    lastError = null
                )
                appendEvent("TCS20.04.3 MONITOR MODE READBACK TIMEOUT expected=${expected ?: -1}; controls-unlocked=true")
                return@Runnable
            }
            if (packet.purpose == "remote-monitor-zoom-level-list" ||
                packet.purpose == "remote-monitor-zoom-level-get" ||
                packet.purpose == "remote-monitor-zoom-box-get") {
                pendingZoomLevelAfterCapability = null
                deferredZoomLevelAfterCapability = null
                zoomExpectedLevelAfterSet = null
                _monitorZoomCore.value = _monitorZoomCore.value.copy(
                    pendingLevel = null,
                    status = if (zoomSummaryExpectedLevel != null) {
                        "Direct Zoom readback unavailable; waiting for recorder state"
                    } else {
                        "Direct Zoom readback unavailable; controls remain active"
                    },
                    readbackTimedOut = true
                )
                if (packet.purpose == "remote-monitor-zoom-box-get") {
                    _monitorZoomViewport.value = _monitorZoomViewport.value.copy(
                        pendingPan = false,
                        status = "Recorder did not return Zoom viewport data"
                    )
                    appendEvent("TCS20.04.6 ZOOM BOX READBACK UNAVAILABLE")
                }
                zoomRefreshActive = false
                zoomRefreshStage = 0
                if (packet.purpose == "remote-monitor-zoom-level-list") _zoomCapabilitiesKnown.value = false
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = if (remoteApiVerified) "linked" else "transport-ready",
                    available = true,
                    linked = true,
                    lastStatus = "Zoom direct readback unavailable",
                    lastError = null
                )
                appendEvent("TCS20.04.8 ZOOM DIRECT READBACK UNAVAILABLE purpose=${packet.purpose} summaryExpected=${zoomSummaryExpectedLevel ?: -1}")
                if (zoomSummaryExpectedLevel != null) {
                    mainHandler.postDelayed({
                        if (pendingRemotePacket == null && !remoteBootstrapActive) {
                            appendEvent("TCS20.04.8 ZOOM SUMMARY FALLBACK REFRESH")
                            refreshOfficialRecorderState()
                        }
                    }, 180L)
                }
                return@Runnable
            }
            if (packet.bootstrap) {
                // A getter may not yield a payload on every firmware revision.
                // Keep the official startup queue moving instead of deadlocking
                // the entire Remote screen on one optional state response.
                appendEvent("TCS18.5 REMOTE PAYLOAD TIMEOUT bootstrap=${packet.label}; continuing official startup queue")
                enqueueNextMacRemoteBootstrapQuery()
            } else {
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = if (remoteApiVerified) "linked" else "transport-ready",
                    available = true,
                    linked = true,
                    lastStatus = "${packet.label} sent; no RemoteApi payload returned",
                    lastError = if (remoteBootstrapCompleted) null else "RemoteApi session is not synchronized"
                )
                appendEvent("TCS18.5 REMOTE PAYLOAD TIMEOUT command=${packet.label}; PacketLink stays open")
            }
        }
        remoteResponseTimeout = task
        val timeoutMs = if (packet.purpose == "remote-monitor-zoom-level-list" ||
            packet.purpose == "remote-monitor-zoom-level-get" ||
            packet.purpose == "remote-monitor-zoom-box-get") {
            ZOOM_CAPABILITY_TIMEOUT_MS
        } else {
            REMOTE_RESPONSE_TIMEOUT_MS
        }
        mainHandler.postDelayed(task, timeoutMs)
    }

    /**
     * Mirrors TCSBleMonitor.packetTick -> TCSBleManager_PacketLinkProcessTick.
     * The official macOS app schedules packetTick every 0.01 seconds. A tick in
     * PacketLink idle state writes an empty final subpacket and then starts a
     * cxtic12 read cycle. Android BLE serializes those ATT operations, so ticks
     * that occur while GATT is busy are intentionally coalesced.
     */
    @SuppressLint("MissingPermission")
    private fun runOfficialPacketLinkProcessTick() {
        if (!_tcsPairingState.value.acceptWritten || !_tcsRemoteState.value.available) return
        if (operationInFlight || pendingOps.isNotEmpty()) return
        val current = gatt ?: return
        val service = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID)) ?: return
        val writeChar = service.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID)) ?: return
        val header = buildOfficialPacketLinkHeader(local = tcsPacketLocalFlag)
        val frame = byteArrayOf(header.toByte())
        pendingOps.addLast(
            PendingOp.WriteCharacteristic(
                writeChar,
                frame,
                packetLinkWriteType(writeChar),
                "remote-process-tick"
            )
        )
        if (pendingRemotePacket != null) {
            appendEvent("TCS18.5 PACKET PROCESS TICK tx=${frame.toHex()} local=$tcsPacketLocalFlag pending=${pendingRemotePacket?.label}")
        }
        processNext(current)
    }

    private fun packetLinkWriteType(writeChar: BluetoothGattCharacteristic): Int =
        if (writeChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

    /**
     * Mirrors TCSiOSC _writeSubPacket first/final header generation.
     * Our Android receiver drains each cxtic12 response immediately, so the local
     * receive buffer is empty and the framework's 0x20 flag is asserted.
     */
    private fun buildOfficialPacketLinkHeader(local: Boolean): Int {
        var header = if (tcsPacketFirstSubpacket) 0x90 else 0x80
        tcsPacketFirstSubpacket = false
        if (local) header = header or 0x08
        header = header or 0x40 // current frame is the final subpacket
        header = header or 0x20 // local receive buffer is empty / advertise RX space
        return header and 0xFF
    }


    private fun parsePacketLinkHeader(value: ByteArray): String {
        if (value.isEmpty()) return "empty"
        val h = value[0].toInt() and 0xFF
        return "0x${h.toString(16).uppercase().padStart(2, '0')} first=${h and 0x80 != 0} final=${h and 0x40 != 0} credit=${h and 0x20 != 0} initial=${h and 0x10 != 0} local=${h and 0x08 != 0}"
    }

    @SuppressLint("MissingPermission")
    private fun handleRemoteReadPurpose(op: PendingOp.Read, value: ByteArray, status: Int): Boolean {
        val purpose = op.purpose.orEmpty()
        if (!purpose.startsWith("remote-")) return false
        if (status != BluetoothGatt.GATT_SUCCESS) {
            pendingRemotePacket = null
            pendingRemoteFrameWritten = false
            cancelRemoteResponseTimeout()
            remoteRxBuffer.reset()
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                phase = "packet-ready",
                lastStatus = "RX read failed status=$status",
                lastError = "Packet Link read failed status=$status"
            )
            appendEvent("TCS18.5 REMOTE RX failed purpose=$purpose status=$status")
            return true
        }

        val hex = value.toHex()
        val header = value.firstOrNull()?.toInt()?.and(0xFF)
        val final = header != null && header and 0x40 != 0
        val peerCredit = header != null && header and 0x20 != 0
        tcsPacketRemoteCredit = peerCredit
        val payload = if (value.isNotEmpty() && header != null) value.copyOfRange(1, value.size) else byteArrayOf()
        val first = header != null && header and 0x80 != 0
        if (first) remoteRxBuffer.reset()
        if (payload.isNotEmpty()) remoteRxBuffer.write(payload)
        appendEvent("TCS18.5 FLOW RX purpose=$purpose frame=$hex header=${parsePacketLinkHeader(value)} payload=${payload.toHex()}")

        if (purpose == "remote-link-prime-read") {
            if (!final || !peerCredit) {
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = "packet-ready",
                    linked = false,
                    lastRxHex = hex,
                    lastStatus = "PacketLink prime did not receive final peer credit",
                    lastError = "Expected E0-compatible final/credit response after F0 link prime"
                )
                appendEvent("TCS18.5 LINK PRIME FAILED rx=$hex expectedFinal=true expectedCredit=true")
                return true
            }
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                phase = "bootstrapping",
                    available = true,
                    linked = _tcsRemoteState.value.linked,
                lastRxHex = hex,
                lastStatus = "PacketLink ready; starting macOS RemoteApi bootstrap",
                lastError = null
            )
            appendEvent("TCS18.5 LINK PRIME COMPLETE F0 -> $hex; peerCredit=$peerCredit; starting official 10 ms PacketLink process loop")
            startPacketLinkProcessLoop()
            startOfficialMacRemoteBootstrap()
            return true
        }

        if (purpose.startsWith("remote-credit-read:")) {
            val packet = pendingRemotePacket
            if (!peerCredit || packet == null) {
                pendingRemotePacket = null
                pendingRemoteFrameWritten = false
                cancelRemoteResponseTimeout()
                remoteRxBuffer.reset()
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = "packet-ready",
                    linked = false,
                    lastRxHex = hex,
                    lastStatus = "Peer did not grant PacketLink TX credit",
                    lastError = "Expected cxtic12 credit bit 0x20 after flow-control request"
                )
                appendEvent("TCS18.5 FLOW CREDIT FAILED expected bit0x20; value=$hex")
                return true
            }
            val current = gatt ?: return true
            val writeChar = current.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
                ?.getCharacteristic(UUID.fromString(TCS_PACKET_WRITE_UUID))
            if (writeChar == null) {
                pendingRemotePacket = null
                pendingRemoteFrameWritten = false
                cancelRemoteResponseTimeout()
                remoteRxBuffer.reset()
                _tcsRemoteState.value = _tcsRemoteState.value.copy(phase = "error", lastError = "PacketLink write characteristic missing after credit")
                return true
            }
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                phase = "credit-granted",
                linked = !packet.bootstrap,
                lastRxHex = hex,
                lastStatus = "Peer credit granted; sending AtomMessage payload",
                lastError = null
            )
            appendEvent("TCS18.5 FLOW CREDIT GRANTED: ${parsePacketLinkHeader(value)}; sending buffered ${packet.label}")
            enqueueRemoteDataFrame(current, writeChar, packet)
            return true
        }

        if (purpose.startsWith("remote-response:") || purpose == "remote-process-read") {
            val packet = pendingRemotePacket
            if (!final) {
                // Official _plexDidReadValue immediately reads the next subpacket
                // until bit 0x40 marks the final subpacket.
                val current = gatt
                val readChar = current?.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
                    ?.getCharacteristic(UUID.fromString(TCS_PACKET_READ_UUID))
                if (readChar != null) {
                    pendingOps.addFirst(PendingOp.Read(readChar, purpose))
                    appendEvent("TCS18.5 FLOW RX continuation required; reading next cxtic12 subpacket")
                }
                return true
            }

            val completePayload = remoteRxBuffer.toByteArray()

            // A user command can be queued while an official 10 ms background
            // process tick is already in flight. Do not mistake that older tick's
            // E0/state payload for the newly queued command before its data frame
            // has actually reached cxtic13.
            if (purpose == "remote-process-read" && packet != null && !pendingRemoteFrameWritten) {
                if (completePayload.isNotEmpty()) {
                    appendEvent("TCS18.5 PRE-COMMAND ASYNC RX payload=${completePayload.toHex()} while=${packet.label}; preserving queued command")
                }
                remoteRxBuffer.reset()
                return true
            }

            if (completePayload.isEmpty()) {
                // Critical 18.5 fix: E0 is a final PacketLink subpacket with TX
                // credit, not a RemoteApi response. TCSBleMonitor keeps calling
                // PacketLinkProcessTick every 10 ms, causing another empty write
                // + read cycle until the remote payload is available.
                if (packet != null) {
                    remoteEmptyPolls++
                    if (outputR7IsTrackedPurpose(packet.purpose) &&
                        (remoteEmptyPolls == 1 || remoteEmptyPolls % 25 == 0)) {
                        outputR7Trace("E0_FLOW_ONLY purpose=${packet.purpose} packetId=${packet.packetId} polls=$remoteEmptyPolls frameWritten=$pendingRemoteFrameWritten")
                    }
                    if (packet.bootstrap) {
                        // E0 is not the query response, but it does finish this
                        // PacketLink transport turn and grants credit. Queue the
                        // next official startup request while the 10 ms process
                        // loop independently drains later state payloads.
                        cancelRemoteResponseTimeout()
                        pendingRemotePacket = null
                        pendingRemoteFrameWritten = false
                        remoteRxBuffer.reset()
                        _tcsRemoteState.value = _tcsRemoteState.value.copy(
                            phase = "bootstrapping",
                    available = true,
                    linked = _tcsRemoteState.value.linked,
                            lastRxHex = hex,
                            lastStatus = "${packet.label} sent; continuing recorder state sync",
                            lastError = null
                        )
                        appendEvent("TCS18.5 FLOW-ONLY RX E0 is NOT RemoteApi payload; bootstrap transport turn complete for ${packet.label}; continuing queue + 10ms process loop")
                        enqueueNextMacRemoteBootstrapQuery()
                    } else {
                        _tcsRemoteState.value = _tcsRemoteState.value.copy(
                            phase = "awaiting-response",
                            available = true,
                            linked = true,
                            lastRxHex = hex,
                            lastStatus = "Command sent; waiting for recorder state (${remoteEmptyPolls} flow ticks)",
                            lastError = null
                        )
                        appendEvent("TCS18.5 FLOW-ONLY RX E0 is NOT command completion; pending=${packet.label} emptyPolls=$remoteEmptyPolls next=10ms-process-tick")
                    }
                }
                return true
            }

            if (completePayload.size < 5) {
                appendEvent("TCS18.5 REMOTE RX short assembled payload=${completePayload.toHex()}; waiting for a complete AtomMessage")
                return true
            }

            val packetId = completePayload[0].toInt() and 0xFF
            val payloadLen = (completePayload[1].toInt() and 0xFF) or ((completePayload[2].toInt() and 0xFF) shl 8)
            val messageType = (completePayload[3].toInt() and 0xFF) or ((completePayload[4].toInt() and 0xFF) shl 8)
            val declaredEnd = (5 + payloadLen).coerceAtMost(completePayload.size)
            val messagePayload = if (declaredEnd > 5) completePayload.copyOfRange(5, declaredEnd) else byteArrayOf()
            val decoded = "AtomMessage packetId=$packetId payloadLen=$payloadLen messageType=$messageType data=${messagePayload.toHex()}"
            if (outputR7IsTrackedPurpose(packet?.purpose) ||
                messageType in arrayOf(0x00DC, 0x0648, 0xFFFF, 0xFFFE, 0x00E2, 0x00E3, 0x00E6, 0x00E7)) {
                outputR7Trace("RX_ATOM purpose=${packet?.purpose ?: "async"} requestPacketId=${packet?.packetId ?: -1} responsePacketId=$packetId type=0x${messageType.toString(16)} payloadLen=$payloadLen availableBytes=${completePayload.size} local=${tcsPacketLocalFlag} raw=${messagePayload.toHex().take(120)}")
            }

            val phase34FeaturePacket = packet?.purpose?.startsWith("remote-monitor-") == true ||
                packet?.purpose?.startsWith("remote-scope-") == true ||
                packet?.purpose?.startsWith("remote-guide-") == true
            if (phase34FeaturePacket && messageType == 0xFFFF) {
                cancelRemoteResponseTimeout()
                pendingRemotePacket = null
                pendingRemoteFrameWritten = false
                remoteEmptyPolls = 0
                remoteRxBuffer.reset()
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = "linked",
                    available = true,
                    linked = true,
                    lastRxHex = hex,
                    lastStatus = "${packet?.label ?: "Phase 03/04 command"} rejected by recorder",
                    lastError = "Recorder returned RemoteApi 0xFFFF for ${packet?.label ?: "Phase 03/04 command"}"
                )
                appendEvent(
                    "TCS19.4.2 REMOTEAPI REJECT label=${packet?.label ?: "unknown"} " +
                        "cmd=${packet?.commandId} response=$decoded"
                )
                return true
            }

            val firstVerifiedPayload = !remoteApiVerified
            remoteApiVerified = true
            if (firstVerifiedPayload) {
                appendEvent(
                    "TCS18.9.1 REMOTEAPI VERIFIED first real AtomMessage payload: $decoded"
                )
            }

            when (messageType) {
                0x0640 -> appendEvent(
                    "TCS18.9.1 STATE ApplicationState raw=${messagePayload.toHex()}"
                )
                0x0190 -> appendEvent(
                    "TCS18.9.1 STATE Record.State raw=${messagePayload.toHex()}"
                )
                0x012C, 0x012D, 0x012E -> appendEvent(
                    "TCS18.9.1 STATE Play/Record transport family type=0x${messageType.toString(16)} raw=${messagePayload.toHex()}"
                )
                else -> Unit
            }

            if (packet?.bootstrap == true && packetId != packet.packetId) {
                // Query replies are correlated primarily by AtomMessage packetId.
                // State/event message types can differ across recorder firmware,
                // so do not hard-block on our recovered message-type labels.
                remoteBootstrapResponses++
                appendEvent("TCS18.5 BOOTSTRAP ASYNC PAYLOAD while=${packet.label} response=$decoded expectedPacketId=${packet.packetId}; keeping current transport turn pending")
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = "bootstrapping",
                    available = true,
                    linked = _tcsRemoteState.value.linked,
                    lastRxHex = hex,
                    lastStatus = "Recorder state received while startup continues",
                    lastError = null
                )
                return true
            }

            if (packet == null && !remoteBootstrapCompleted) {
                remoteBootstrapResponses++
                remoteRxBuffer.reset()
                appendEvent("TCS18.5 BOOTSTRAP DRAIN PAYLOAD response=$decoded")
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = "bootstrapping",
                    available = true,
                    linked = _tcsRemoteState.value.linked,
                    lastRxHex = hex,
                    lastStatus = "Recorder state received during startup drain",
                    lastError = null
                )
                return true
            }

            cancelRemoteResponseTimeout()
            pendingRemotePacket = null
            pendingRemoteFrameWritten = false
            remoteEmptyPolls = 0
            remoteRxBuffer.reset()
            if (packet?.bootstrap == true) {
                remoteBootstrapResponses++
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = "bootstrapping",
                    available = true,
                    linked = _tcsRemoteState.value.linked,
                    lastRxHex = hex,
                    lastStatus = "Recorder state received; startup continues",
                    lastError = null
                )
                appendEvent("TCS18.5 BOOTSTRAP PAYLOAD label=${packet.label} expectedType=${packet.expectedResponseId} response=$decoded nextCredit=$tcsPacketRemoteCredit")
                enqueueNextMacRemoteBootstrapQuery()
                return true
            }

            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                phase = if (remoteApiVerified) "linked" else "transport-ready",
                available = true,
                linked = true,
                lastRxHex = hex,
                lastStatus = "${packet?.label ?: "Remote command"} response received",
                lastError = null
            )
            appendEvent("TCS18.5 REMOTE PAYLOAD RX label=${packet?.label ?: "unsolicited"} response=$decoded nextCredit=$tcsPacketRemoteCredit")
            return true
        }

        _tcsRemoteState.value = _tcsRemoteState.value.copy(
            phase = "linked",
            available = true,
            linked = true,
            lastRxHex = hex,
            lastStatus = "PacketLink response received",
            lastError = null
        )
        return true
    }

    @SuppressLint("MissingPermission")
    private fun handleRemoteWritePurpose(g: BluetoothGatt, op: PendingOp.WriteCharacteristic, status: Int): Boolean {
        val purpose = op.purpose
        if (!purpose.startsWith("remote-")) return false
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (purpose == "remote-process-tick") {
                appendEvent("TCS18.5 PACKET PROCESS TICK write failed status=$status; timer will retry")
                return true
            }
            pendingRemotePacket = null
            pendingRemoteFrameWritten = false
            cancelRemoteResponseTimeout()
            _tcsRemoteState.value = _tcsRemoteState.value.copy(
                phase = "packet-ready",
                lastStatus = "TX failed status=$status",
                lastError = "Remote PacketLink write failed status=$status"
            )
            appendEvent("TCS18.5 REMOTE TX failed purpose=$purpose status=$status")
            return true
        }

        val readChar = g.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
            ?.getCharacteristic(UUID.fromString(TCS_PACKET_READ_UUID))
        if (readChar == null) {
            pendingRemotePacket = null
            pendingRemoteFrameWritten = false
            _tcsRemoteState.value = _tcsRemoteState.value.copy(phase = "error", lastError = "Packet Link read characteristic disappeared")
            return true
        }

        when {
            purpose == "remote-link-prime" -> {
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = "link-priming",
                    available = true,
                    linked = false,
                    lastStatus = "F0 written; reading PacketLink peer credit"
                )
                pendingOps.addFirst(PendingOp.Read(readChar, "remote-link-prime-read"))
                appendEvent("TCS18.5 LINK PRIME TX OK; reading cxtic12 for E0/bit0x20 grant")
            }
            purpose.startsWith("remote-credit-request:") -> {
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = "awaiting-credit",
                    available = true,
                    lastStatus = "PacketLink flow-control header written; reading peer credit"
                )
                val basePurpose = purpose.removePrefix("remote-credit-request:")
                pendingOps.addFirst(PendingOp.Read(readChar, "remote-credit-read:$basePurpose"))
                appendEvent("TCS18.5 FLOW CREDIT TX OK; reading cxtic12 for bit0x20 grant")
            }
            purpose.startsWith("remote-data:") -> {
                if (outputR7IsTrackedPurpose(pendingRemotePacket?.purpose)) {
                    outputR7Trace("GATT_WRITE_OK purpose=${pendingRemotePacket?.purpose} cmd=${pendingRemotePacket?.commandId} packetId=${pendingRemotePacket?.packetId} next=read-cxtic12")
                }
                pendingRemoteFrameWritten = true
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = "awaiting-response",
                    available = true,
                    lastStatus = "AtomMessage frame written; entering PacketLink read cycle"
                )
                val basePurpose = purpose.removePrefix("remote-data:")
                pendingOps.addFirst(PendingOp.Read(readChar, "remote-response:$basePurpose"))
                appendEvent("TCS18.5 REMOTE DATA TX OK purpose=$basePurpose; reading cxtic12 then continuing 10ms process ticks")
            }
            purpose == "remote-process-tick" -> {
                pendingOps.addFirst(PendingOp.Read(readChar, "remote-process-read"))
            }
            else -> return false
        }
        return true
    }

    /**
     * Pre-code Pairing-ID capture.
     *
     * This deliberately avoids every characteristic write and every CCCD descriptor write.
     * Reads are limited to non-standard vendor characteristics whose metadata does not declare
     * encrypted/MITM read permissions. Notify/indicate capable characteristics are mapped but
     * are not subscribed until the user explicitly accepts the visible Pairing ID.
     */
    @SuppressLint("MissingPermission")
    fun beginPrePairCapture(): Boolean {
        val current = gatt ?: return false
        if (_state.value != "services-ready") return false
        if (prePairCaptureStartedForConnection) return false
        prePairCaptureStartedForConnection = true

        pendingOps.clear()
        operationInFlight = false
        values.clear()
        asciiLineBuffer.clear()

        val vendor = current.services
            .filterNot { isStandardGattService(it.uuid) }
            .sortedByDescending { isTimecodeSystemsService(it.uuid) }
            .flatMap { service -> service.characteristics.map { service to it } }

        vendor.forEach { (service, c) ->
            if (c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                appendEvent("PRE-PAIR notify mapped service=${service.uuid} char=${c.uuid}; CCCD intentionally NOT written before code acceptance")
            }
            if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                if (isPrePairReadSafe(c)) {
                    pendingOps.addLast(PendingOp.Read(c))
                } else {
                    appendEvent("PRE-PAIR protected read skipped char=${c.uuid} perms=0x${c.permissions.toString(16)}")
                }
            }
        }

        _state.value = "pre-pair-capture"
        appendEvent("PRE-PAIR active AG2 capture started: ${pendingOps.size} safe vendor read(s); zero characteristic writes; zero CCCD writes")
        if (pendingOps.isEmpty()) {
            _state.value = "services-ready"
            appendEvent("PRE-PAIR active AG2 capture complete: map only, no safe readable vendor characteristics")
            return true
        }
        processNext(current)
        return true
    }

    private fun isPrePairReadSafe(c: BluetoothGattCharacteristic): Boolean {
        val encrypted = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM
        return c.permissions and encrypted == 0
    }

    /**
     * User-initiated pairing-data discovery.
     *
     * Reads and notification subscriptions are serialized. If Android reports that a
     * characteristic requires authentication/encryption, the operation is skipped rather
     * than forcing createBond(). This keeps ATOMOS' application-level pairing flow separate
     * from Android OS bonding.
     */
    /**
     * Fast pairing discovery for short-lived AG/AG2/AirGlu BT Pair windows.
     *
     * ATOMOS documents the same Sync Config Bluetooth Pair entry for AtomRemote pairing,
     * while AirGlu BT also supports third-party sync clients. Prior builds spent most of
     * the roughly ten-second Pair window reading too many characteristics before trying
     * the pairing API. This path subscribes to at most two high-priority vendor notify
     * characteristics and reads at most one characteristic, then returns control so the
     * ViewModel can immediately issue the non-mutating BTPR? query.
     */
    /**
     * 17.9 dual-role outbound Pairing-ID capture for the Shogun 9383 profile.
     *
     * The 17.6 trace proved that sending public AirGlu S1C ASCII frames to arbitrary
     * writable characteristics in this proprietary service is wrong: the recorder
     * accepts the ATT write and then closes the BLE link. This capture mode therefore
     * performs ZERO characteristic writes. It only enables genuine notify/indicate
     * characteristics through their CCCD and samples readable vendor values so the
     * phone-side mirrored GATT server can answer with real cached recorder values.
     */
    @SuppressLint("MissingPermission")
    fun beginPassivePairingCapture(): Boolean {
        val current = gatt ?: return false
        if (_state.value != "services-ready") return false
        if (passivePairCaptureStartedForConnection) {
            appendEvent("17.9 dual-role outbound Pairing-ID capture already armed for this connection")
            return true
        }
        passivePairCaptureStartedForConnection = true

        pendingOps.clear()
        operationInFlight = false
        values.clear()
        asciiLineBuffer.clear()

        val vendor = current.services
            .filterNot { isStandardGattService(it.uuid) }
            .sortedByDescending { isTimecodeSystemsService(it.uuid) }
            .flatMap { service -> service.characteristics.map { service to it } }

        // Subscribe first so an application pairing packet cannot be missed while reads run.
        vendor
            .filter { (_, c) -> c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
            .forEach { (_, c) ->
                val enabled = runCatching { current.setCharacteristicNotification(c, true) }.getOrDefault(false)
                if (enabled) {
                    val cccd = c.getDescriptor(CCCD_UUID)
                    if (cccd != null) {
                        val enable = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        else
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        pendingOps.addLast(PendingOp.WriteDescriptor(cccd, enable))
                    }
                }
            }

        vendor
            .filter { (_, c) -> c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
            .filter { (_, c) -> isPrePairReadSafe(c) }
            .forEach { (_, c) -> pendingOps.addLast(PendingOp.Read(c)) }

        _state.value = "passive-pair-capture"
        appendEvent("17.9 OUTBOUND PASSIVE CAPTURE armed: ${pendingOps.size} CCCD/read operation(s), ZERO characteristic writes, proprietary 9383 ASCII probing disabled")
        if (pendingOps.isEmpty()) {
            _state.value = "services-ready"
            appendEvent("17.9 OUTBOUND PASSIVE CAPTURE ready: no subscribable/readable vendor characteristic found")
            return true
        }
        processNext(current)
        return true
    }

    @SuppressLint("MissingPermission")
    fun beginFastPairingDiscovery(): Boolean {
        val current = gatt ?: return false
        if (_state.value != "services-ready") return false

        pendingOps.clear()
        operationInFlight = false
        values.clear()
        asciiLineBuffer.clear()

        val vendorServices = current.services
            .filterNot { isStandardGattService(it.uuid) }
            .sortedByDescending { isTimecodeSystemsService(it.uuid) }
        val custom = vendorServices.flatMap { service -> service.characteristics.map { service to it } }

        if (vendorServices.any { isTimecodeSystemsService(it.uuid) }) {
            appendEvent("FAST pairing: AirGlu priority service detected (FCC1 or 9383b854); treating AG2 as Shogun/AirGlu control candidate")
        } else {
            appendEvent("FAST pairing: no known AirGlu priority service; using first vendor service(s)")
        }

        custom
            .filter { (_, c) -> c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
            .take(2)
            .forEach { (_, c) ->
                val p = c.properties
                val localEnabled = runCatching { current.setCharacteristicNotification(c, true) }.getOrDefault(false)
                if (localEnabled) {
                    val cccd = c.getDescriptor(CCCD_UUID)
                    if (cccd != null) {
                        val enable = if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        } else {
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        }
                        pendingOps.addLast(PendingOp.WriteDescriptor(cccd, enable))
                    }
                }
            }

        custom
            .filter { (_, c) -> c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
            .take(1)
            .forEach { (_, c) -> pendingOps.addLast(PendingOp.Read(c)) }

        _state.value = "pair-discovery"
        appendEvent("FAST pairing discovery started: ${pendingOps.size} operation(s)")
        processNext(current)
        return true
    }

    @SuppressLint("MissingPermission")
    fun beginPairingDiscovery(): Boolean {
        val current = gatt ?: return false
        if (_state.value != "services-ready") return false

        pendingOps.clear()
        operationInFlight = false
        values.clear()
        asciiLineBuffer.clear()

        val vendorServices = current.services
            .filterNot { isStandardGattService(it.uuid) }
            .sortedByDescending { isTimecodeSystemsService(it.uuid) }
        val custom = vendorServices
            .flatMap { service -> service.characteristics.map { service to it } }
        if (vendorServices.any { isTimecodeSystemsService(it.uuid) }) {
            appendEvent("Known AirGlu service detected (FCC1 or 9383b854); prioritizing it for pairing discovery")
        } else {
            appendEvent("Known AirGlu service not discovered; using generic vendor-service pairing discovery")
        }
        val standard = current.services
            .filter { isStandardGattService(it.uuid) }
            .flatMap { service -> service.characteristics.map { service to it } }

        // Subscribe to vendor notifications FIRST so any short-lived pairing response is
        // captured before we start reading characteristics. Then perform a small,
        // serialized read set. Every ATT operation has its own timeout, so a peripheral
        // that never calls us back cannot freeze pairing discovery forever.
        custom
            .filter { (_, c) -> c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
            .take(4)
            .forEach { (_, c) ->
                val p = c.properties
                val localEnabled = runCatching { current.setCharacteristicNotification(c, true) }.getOrDefault(false)
                if (localEnabled) {
                    val cccd = c.getDescriptor(CCCD_UUID)
                    if (cccd != null) {
                        val enable = if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        } else {
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        }
                        pendingOps.addLast(PendingOp.WriteDescriptor(cccd, enable))
                    }
                }
            }

        custom
            .filter { (_, c) -> c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
            .take(4)
            .forEach { (_, c) -> pendingOps.addLast(PendingOp.Read(c)) }

        // Only a tiny standard-service tail is inspected. Device Information can be
        // useful, but generic battery/GAP reads should never dominate the Pair window.
        standard
            .filter { (_, c) -> c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
            .take(1)
            .forEach { (_, c) -> pendingOps.addLast(PendingOp.Read(c)) }

        _state.value = "pair-discovery"
        appendEvent("Pairing discovery started: ${pendingOps.size} serialized operation(s), vendor notifications first")
        processNext(current)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun processNext(g: BluetoothGatt) {
        if (operationInFlight) return
        // R27.10: GATT callbacks may mutate this deque between its empty check
        // and removeFirst(). A single non-throwing dequeue removes that TOCTOU
        // NoSuchElementException without changing operation order or retries.
        // This does NOT make java.util.ArrayDeque fully thread-safe.
        while (true) {
            val op = pendingOps.pollFirst() ?: break
            val label = when (op) {
                is PendingOp.OutputR21Mtu -> "output-r21-mtu"
                is PendingOp.Read -> "${op.purpose ?: "read"} ${op.characteristic.uuid}"
                is PendingOp.WriteDescriptor -> "${op.purpose ?: "CCCD"} ${op.descriptor.characteristic.uuid}"
                is PendingOp.WriteCharacteristic -> "${op.purpose} ${op.characteristic.uuid}"
            }
            val started = when (op) {
                is PendingOp.OutputR21Mtu -> g.requestMtu(op.mtu)
                is PendingOp.Read -> g.readCharacteristic(op.characteristic)
                is PendingOp.WriteDescriptor -> {
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(op.descriptor, op.value) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            op.descriptor.value = op.value
                            g.writeDescriptor(op.descriptor)
                        }
                    }
                }
                is PendingOp.WriteCharacteristic -> {
                    if (op.characteristic.uuid.toString().equals(TCS_PACKET_WRITE_UUID, true)) {
                        android.util.Log.i("AtomRemoteA1280", "ATT_WRITE_INTENT purpose=${op.purpose} bytes=${op.value.size} hex=${op.value.toHex()}")
                    }
                    op.characteristic.writeType = op.writeType
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeCharacteristic(op.characteristic, op.value, op.writeType) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            op.characteristic.value = op.value
                            g.writeCharacteristic(op.characteristic)
                        }
                    }
                }
            }
            if (started) {
                activeOp = op
                operationInFlight = true
                armOperationTimeout(g, label)
                return
            }
            if (op is PendingOp.OutputR21Mtu) {
                outputR21MtuResult = "request-start-rejected"
                outputR21MtuRequested = false // allow ONE retry on next user Output refresh
                android.util.Log.i("AtomRemoteOutputR22", "MTU_START_REJECTED requested=${op.mtu} retry=NEXT_REFRESH")
                // Not a recorder failure. Continue normal ATT traffic.
            }
            // Android's BluetoothGatt can briefly report BUSY immediately after a
            // completed ATT callback even though our serialized queue is logically
            // idle. The macOS 10 ms PacketLink timer simply tries again on the next
            // tick. A missed empty process tick is therefore NOT a link failure and
            // must never clear a pending RemoteApi command or poison UI state.
            if (op is PendingOp.WriteCharacteristic && op.purpose == PLINK18_WRITE_PURPOSE) {
                // Do not mutate PacketLink state when Android merely reports BUSY.
                // macOS is still logically WRITING; retry the exact same subpacket.
                pendingOps.addFirst(op)
                appendEvent("TCS18.8 PLINK write start coalesced: Android GATT busy; retrying identical subpacket")
                activeOp = null
                mainHandler.postDelayed({
                    val live = gatt ?: return@postDelayed
                    processNext(live)
                }, 12L)
                return
            }
            if (op is PendingOp.Read && op.purpose == PLINK18_READ_PURPOSE) {
                // Same rule for READ: remain in READING and retry the same cxtic12 read.
                pendingOps.addFirst(op)
                appendEvent("TCS18.8 PLINK read start coalesced: Android GATT busy; retrying identical read")
                activeOp = null
                mainHandler.postDelayed({
                    val live = gatt ?: return@postDelayed
                    processNext(live)
                }, 12L)
                return
            }
            if (op is PendingOp.WriteCharacteristic && op.purpose == "remote-process-tick") {
                appendEvent("TCS18.5.1 PACKET PROCESS TICK coalesced: Android GATT busy; next 10ms tick will retry")
                activeOp = null
                continue
            }

            appendEvent("ATT operation could not start $label; continuing")
            val connectedPurpose = when (op) {
                is PendingOp.OutputR21Mtu -> "output-r21-mtu"
                is PendingOp.Read -> op.purpose
                is PendingOp.WriteDescriptor -> op.purpose
                is PendingOp.WriteCharacteristic -> op.purpose
            }
            if (connectedPurpose?.startsWith("connected-dtsm-") == true) {
                pendingOps.addFirst(op)
                appendEvent("TCS18.8.6 DTSM ATT busy/coalesced: $connectedPurpose; retrying identical operation")
                activeOp = null
                mainHandler.postDelayed({
                    val live = gatt ?: return@postDelayed
                    processNext(live)
                }, 20L)
                return
            }
            if (connectedPurpose?.startsWith("connected-") == true) {
                if (connectedPurpose == TCS_PURPOSE_CONNECTED_FRAMECOUNT_CCCD) {
                    connectedLifecycleArmed = false
                    connectedFrameCountCccdReady = false
                }
                if (connectedPurpose == TCS_PURPOSE_CONNECTED_TIMECODE) {
                    connectedTimecodeReadPending = false
                }
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = "connected-sync",
                    available = _tcsRemoteState.value.available,
                    linked = _tcsRemoteState.value.linked,
                    lastStatus = "Synchronizing recorder connected session",
                    lastError = null
                )
                appendEvent("TCS18.6 CONNECTED operation busy/coalesced: $connectedPurpose; retrying")
                if (connectedPurpose == TCS_PURPOSE_CONNECTED_TIMECODE && packetLink18Primed) {
                    connectedTimecodeReadPending = false
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        phase = "linked",
                        available = true,
                        linked = true,
                        lastStatus = "PacketLink active; deferred non-critical timecode refresh",
                        lastError = null
                    )
                    appendEvent(
                        "TCS20.03.4.8 POST-LINK TIMECODE BUSY SUPPRESSED; " +
                            "PacketLink/Input/RemoteApi retain ATT ownership"
                    )
                } else {
                    mainHandler.postDelayed({ beginOfficialConnectedLifecycle() }, 80L)
                }
                activeOp = null
                continue
            }
            if (op is PendingOp.WriteCharacteristic && op.purpose.startsWith("tcs-")) {
                failTcsPairing("${op.purpose} could not start")
                activeOp = null
                return
            }
            if (op is PendingOp.WriteCharacteristic && op.purpose.startsWith("remote-")) {
                pendingRemotePacket = null
                pendingRemoteFrameWritten = false
                cancelRemoteResponseTimeout()
                remoteRxBuffer.reset()
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = "packet-ready",
                    lastError = "${op.purpose} could not start"
                )
                appendEvent("TCS18.5.1 REMOTE operation could not start: ${op.purpose}")
            }
        }
        activeOp = null
        cancelOperationTimeout()
        val completedMode = _state.value
        if (completedMode != "tcs-pairing" || _tcsPairingState.value.phase in setOf("accepted", "can-accept", "waiting-for-bcid", "error")) {
            _state.value = "services-ready"
        }
        appendEvent(
            when (completedMode) {
                "pre-pair-capture" -> "PRE-PAIR active AG2 capture complete; Pairing-ID gate remains closed for characteristic writes"
                "passive-pair-capture" -> "Legacy passive capture ready"
                "tcs-pairing" -> "TCS18 ATT queue idle: phase=${_tcsPairingState.value.phase}"
                else -> if (_tcsRemoteState.value.available) "TCS18.5.1 PacketLink ATT queue idle: phase=${_tcsRemoteState.value.phase}" else "Pairing discovery complete"
            }
        )
    }

    private fun armOperationTimeout(g: BluetoothGatt, label: String) {
        cancelOperationTimeout()
        val task = Runnable {
            if (!operationInFlight) return@Runnable
            operationInFlight = false
            val timedOut = activeOp
            activeOp = null
            appendEvent("ATT operation timeout: $label; continuing")
            val timedPurpose = when (timedOut) {
                is PendingOp.OutputR21Mtu -> "output-r21-mtu"
                is PendingOp.Read -> timedOut.purpose
                is PendingOp.WriteDescriptor -> timedOut.purpose
                is PendingOp.WriteCharacteristic -> timedOut.purpose
                null -> null
            }
            if (timedPurpose == "output-r21-mtu") {
                outputR21MtuResult = "callback-timeout"
                outputR21MtuRequested = false // retry only on another explicit Output refresh
                android.util.Log.i("AtomRemoteOutputR22", "MTU_TIMEOUT requested=185 current=$outputR21MtuBytes retry=NEXT_REFRESH")
                processNext(g)
                return@Runnable
            }
            if (timedPurpose == PLINK18_WRITE_PURPOSE || timedPurpose == PLINK18_READ_PURPOSE) {
                if (timedPurpose == PLINK18_WRITE_PURPOSE) {
                    packetLink18.onWriteFailed()
                } else {
                    packetLink18.onReadFailed()
                }

                if (PacketLinkSessionPolicy.quarantineAfterPacketLinkAttTimeout(timedPurpose)) {
                    stopPacketLinkProcessLoop()
                    pendingOps.clear()
                    pendingRemotePacket = null
                    pendingRemoteFrameWritten = false
                    cancelRemoteResponseTimeout()
                    remoteRxBuffer.reset()

                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        phase = "error",
                        available = false,
                        linked = false,
                        lastStatus = "PacketLink ATT timeout quarantined",
                        lastError = "PacketLink ATT ${if (timedPurpose == PLINK18_READ_PURPOSE) "read" else "write"} timeout; trusted recovery required"
                    )
                    appendEvent(
                        "TCS20.03.6.3 PLINK TIMEOUT QUARANTINE purpose=$timedPurpose; process-loop=stopped pendingOps=cleared"
                    )
                    return@Runnable
                }

                processNext(g)
                return@Runnable
            }
            if (timedPurpose == TCS_PURPOSE_BCID_CCCD) {
                appendEvent("TCS18 BCID CCCD timeout; continuing with ID slots/read fallback")
                processNext(g)
                return@Runnable
            }
            if (timedPurpose?.startsWith("connected-dtsm-") == true && timedOut != null) {
                pendingOps.addFirst(timedOut)
                appendEvent("TCS18.8.6 DTSM ATT timeout: $timedPurpose; retrying identical operation")
                mainHandler.postDelayed({
                    val live = gatt ?: return@postDelayed
                    processNext(live)
                }, 30L)
                return@Runnable
            }
            if (timedPurpose?.startsWith("connected-") == true) {
                if (timedPurpose == TCS_PURPOSE_CONNECTED_FRAMECOUNT_CCCD) {
                    connectedLifecycleArmed = false
                    connectedFrameCountCccdReady = false
                }
                if (timedPurpose == TCS_PURPOSE_CONNECTED_TIMECODE) {
                    connectedTimecodeReadPending = false
                }
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = "connected-sync",
                    available = _tcsRemoteState.value.available,
                    linked = _tcsRemoteState.value.linked,
                    lastStatus = "Recorder connected-session operation timed out; retrying",
                    lastError = null
                )
                appendEvent("TCS18.6 CONNECTED timeout coalesced: $timedPurpose")
                if (timedPurpose == TCS_PURPOSE_CONNECTED_TIMECODE && packetLink18Primed) {
                    connectedTimecodeReadPending = false
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        phase = "linked",
                        available = true,
                        linked = true,
                        lastStatus = "PacketLink active; timed-out timecode refresh deferred",
                        lastError = null
                    )
                    appendEvent(
                        "TCS20.03.4.8 POST-LINK TIMECODE TIMEOUT SUPPRESSED; " +
                            "PacketLink/Input/RemoteApi retain ATT ownership"
                    )
                } else {
                    mainHandler.postDelayed({ beginOfficialConnectedLifecycle() }, 100L)
                }
                processNext(g)
                return@Runnable
            }
            if (timedPurpose?.startsWith("tcs-") == true) {
                failTcsPairing("$timedPurpose timeout")
                return@Runnable
            }
            if (timedPurpose == "remote-process-tick") {
                // Process ticks are best-effort timer work. Losing one ATT callback
                // must not tear down or invalidate the RemoteApi session.
                appendEvent("TCS18.5.1 PACKET PROCESS TICK timeout coalesced; timer will retry")
                processNext(g)
                return@Runnable
            }
            if (timedPurpose?.startsWith("remote-") == true) {
                pendingRemotePacket = null
                pendingRemoteFrameWritten = false
                cancelRemoteResponseTimeout()
                remoteRxBuffer.reset()
                _tcsRemoteState.value = _tcsRemoteState.value.copy(
                    phase = "packet-ready",
                    lastStatus = "Operation timeout",
                    lastError = "$timedPurpose timeout"
                )
                appendEvent("TCS18.5.1 REMOTE timeout: $timedPurpose")
            }
            processNext(g)
        }
        operationTimeout = task
        mainHandler.postDelayed(task, if (label == "output-r21-mtu") 3500L else 900L)
    }

    private fun cancelOperationTimeout() {
        operationTimeout?.let { mainHandler.removeCallbacks(it) }
        operationTimeout = null
    }

    private fun updateValue(c: BluetoothGattCharacteristic, value: ByteArray, source: String) {
        val key = "${c.service.uuid}/${c.uuid}"
        values[key] = value
        val rawAscii = value.toString(Charset.forName("UTF-8"))
        val ascii = rawAscii.filter { it.code in 0x20..0x7E }
        val hex = value.joinToString("") { "%02X".format(it) }
        val numeric = numericSummary(value)
        appendEvent("RX[$source] svc=${c.service.uuid} char=${c.uuid} len=${value.size} hex=$hex ${if (ascii.isNotBlank()) "ascii=[$ascii] " else ""}$numeric")
        appendAsciiLines(rawAscii)
        val officialCode = _tcsPairingState.value.pairingCode
        _snapshot.value = _snapshot.value?.let { snap ->
            snap.copy(
                pairingId = officialCode ?: snap.pairingId,
                binaryPairingCandidates = emptyList(),
                pairingDecodeEvidence = if (officialCode != null) {
                    listOf(
                        "official-tcs: app-generated Connect Name",
                        "official-tcs: ID_SLOTS -> CONNECT_NAME -> CCID -> BCID -> ~CCID"
                    )
                } else {
                    snap.pairingDecodeEvidence
                },
                characteristics = snap.characteristics.map {
                    if (it.characteristicUuid.equals(c.uuid.toString(), true)) it.copy(lastValueHex = hex, lastValueAscii = ascii) else it
                }
            )
        }
    }

    private fun appendAsciiLines(raw: String) {
        raw.forEach { ch ->
            when (ch) {
                '\n' -> {
                    val line = asciiLineBuffer.toString().trim().trimEnd('\r')
                    asciiLineBuffer.clear()
                    if (line.isNotBlank() && (line.startsWith("#") || line.matches(Regex("[A-Z0-9]{4}=.*")))) {
                        appendEvent("S1C<$line")
                    }
                }
                '\r' -> Unit
                else -> if (ch.code in 0x20..0x7E) {
                    asciiLineBuffer.append(ch)
                    if (asciiLineBuffer.length > 512) asciiLineBuffer.clear()
                }
            }
        }
    }

    private fun numericSummary(value: ByteArray): String {
        if (value.size != 4) return ""
        val le = (value[0].toLong() and 0xFF) or
            ((value[1].toLong() and 0xFF) shl 8) or
            ((value[2].toLong() and 0xFF) shl 16) or
            ((value[3].toLong() and 0xFF) shl 24)
        val be = (value[3].toLong() and 0xFF) or
            ((value[2].toLong() and 0xFF) shl 8) or
            ((value[1].toLong() and 0xFF) shl 16) or
            ((value[0].toLong() and 0xFF) shl 24)
        return "u32le=$le u32be=$be mod1e9(le=${le % 1_000_000_000L},be=${be % 1_000_000_000L})"
    }

    // OUTPUT-R7: passive observability only; never enqueue/alter a recorder packet.
    private fun outputR7IsTrackedPurpose(purpose: String?): Boolean =
        purpose == "remote-input-output-summary-get" ||
        purpose == "remote-output-hdr-enable-get" ||
        (purpose?.startsWith("remote-lut-") == true) ||
        (purpose?.startsWith("remote-output-r17-get-") == true) ||
        purpose == "remote-output-r19-output-page-get" ||
        purpose == "remote-output-r20-output-page-local-get" ||
        // R26.11.1: opt Audio read-only probes into EXISTING passive TX/RX logs.
        // This only expands diagnostics; packet routing, pairing, and controls stay untouched.
        purpose == "remote-audio-r26-meters-page-get" ||
        purpose == "remote-audio-r26-part2-nonlocal-read-only"

    private fun outputR7Trace(message: String) {
        Log.i("AtomRemoteOutputR7", message)
    }

    private fun appendEvent(message: String) {
        // OUTPUT R6: INFO-level diagnostic mirror only. No PacketLink framing,
        // queue policy, parsing, TCS pairing or recorder controls are modified.
        // Some OEM loggers suppress all DEBUG records even with adb logcat -d.
        if (message.startsWith("OUTPUT0614") ||
            message.startsWith("TCS19.9 INPUT SUMMARY") ||
            message.startsWith("TCS19.10 INPUT SUMMARY REJECT") ||
            message.startsWith("TCS19.12.10 APPPOLLING OPTIONAL INPUT SUMMARY") ||
            message.startsWith("TCS20.09.4 LUT OUTPUT") ||
            message.startsWith("TCS20.09.R1 LUT OUTPUT") ||
            (message.startsWith("TCS19.4.2 MONITOR blocked") && message.contains("input-output-summary"))) {
            Log.i("AtomRemoteOutputR6", message)
        }
        Log.d("AtomRemoteGatt", message)
        _events.value = (_events.value + message).takeLast(500)
    }

    private fun isTimecodeSystemsService(uuid: UUID): Boolean {
        val value = uuid.toString().lowercase()
        // FCC1 is the historical Timecode Systems assigned service hint. Real Shogun Ultra
        // captures also advertise the vendor service 9383b854-8bee-11e7-bb31-be2e44b06b34.
        // Treat both as priority hints while still discovering all vendor services at runtime.
        return value.startsWith("0000fcc1-") || value == AIRGLU_SHOGUN_SERVICE_UUID
    }

    private fun isStandardGattService(uuid: UUID): Boolean {
        val value = uuid.toString().lowercase()
        return value.startsWith("00001800-") ||
            value.startsWith("00001801-") ||
            value.startsWith("0000180a-") ||
            value.startsWith("0000180f-")
    }

    private fun handleSecurityRequired(g: BluetoothGatt, device: BluetoothDevice, status: Int) {
        operationInFlight = false
        updateBondState(device.bondState)
        appendEvent("Protected GATT operation skipped (status=$status, osBond=${bondLabel(device.bondState)}). Android bonding was NOT forced.")
        processNext(g)
    }

    private fun updateBondState(value: Int) {
        _bondState.value = bondLabel(value)
    }

    private fun bondLabel(value: Int): String = when (value) {
        BluetoothDevice.BOND_BONDED -> "bonded"
        BluetoothDevice.BOND_BONDING -> "pairing"
        else -> "not-paired"
    }

    private fun registerBondReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(bondReceiver, filter)
        }
        receiverRegistered = true
    }

    private val bondReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            if (device.address != targetDevice?.address) return

            val current = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
            updateBondState(current)
            appendEvent("Android OS bond changed: ${bondLabel(previous)} -> ${bondLabel(current)} (informational only)")
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== this@GattClient.gatt) {
                appendEvent("TCS18.8.2 stale GATT state callback ignored status=$status state=$newState")
                runCatching { g.close() }
                return
            }
            updateBondState(g.device.bondState)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                cancelConnectionTimeout()
                appendEvent("GATT connection error: status=$status state=$newState osBond=${bondLabel(g.device.bondState)}")
                if (status == 133 && retryCount < 1) {
                    retryCount++
                    _state.value = "retrying"
                    runCatching { g.close() }
                    if (this@GattClient.gatt === g) this@GattClient.gatt = null
                    mainHandler.postDelayed({ targetDevice?.let { openGatt(it) } }, 900)
                    return
                }
                _disconnectReason.value = "GATT error $status"
                _state.value = "error:$status"
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                cancelConnectionTimeout()
                retryCount = 0
                linkStartedAtMs = System.currentTimeMillis()
                _disconnectReason.value = null
                _state.value = "connected"
                packetLinkDidConnectEnteredThisGattEpoch = false // TCS19.12.17.10 NEW GATT EPOCH
                appendEvent("GATT connected; starting passive service discovery")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cancelConnectionTimeout()
                cancelOperationTimeout()
                cancelRemoteResponseTimeout()
                cancelRemoteBootstrapSettle()
                remoteRxBuffer.reset()
                stopPacketLinkProcessLoop()
                packetLink18.didDisconnect()
                packetLink18Primed = false
                pendingOps.clear()
                operationInFlight = false
                val aliveMs = if (linkStartedAtMs > 0L) System.currentTimeMillis() - linkStartedAtMs else 0L
                val reason = if (aliveMs > 0L) {
                    "Remote/peripheral closed BLE link after ${aliveMs}ms (GATT status 0)"
                } else {
                    "BLE link closed (GATT status 0)"
                }
                _disconnectReason.value = reason
                _state.value = "disconnected"
                appendEvent(reason)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {

            if (status == BluetoothGatt.GATT_SUCCESS) {
                inspectServicesPassive(g)
                if (officialLinkedReconnectPending && _tcsPairingState.value.acceptWritten) {
                    // Exact TCSiOSC CONNECT sequence on the fresh BLE connection:
                    // CONNECT_Enter_DidConnect
                    // -> CONNECT_ReadingDeviceName (cxtic 2)
                    // -> CONNECT_ReadingBcid (cxtic 4)
                    // -> LINKED
                    //
                    // Do not open PacketLink until these reads complete.
                    val service = g.getService(UUID.fromString(AIRGLU_SHOGUN_SERVICE_UUID))
                    val deviceName = service?.getCharacteristic(UUID.fromString(TCS_DEVICE_NAME_UUID))
                    val bcid = service?.getCharacteristic(UUID.fromString(TCS_BCID_UUID))

                    val deviceNameReadable = deviceName != null &&
                        deviceName.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
                    val bcidReadable = bcid != null &&
                        bcid.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0

                    if (!deviceNameReadable || !bcidReadable) {
                        officialLinkedReconnectPending = false
                        _tcsRemoteState.value = _tcsRemoteState.value.copy(
                            phase = "error",
                            available = false,
                            linked = false,
                            lastStatus = "Official LINKED CONNECT preflight unavailable",
                            lastError = "cxtic2 readable=$deviceNameReadable cxtic4 readable=$bcidReadable"
                        )
                        appendEvent("TCS18.8.3 CONNECT PREFLIGHT FAIL cxtic2=$deviceNameReadable cxtic4=$bcidReadable")
                    } else {
                        connectedLifecycleArmed = false
                        connectedFrameCountCccdReady = false
                        connectedLifecycleReady = false
                        connectedTimecodeReadPending = false

                        _tcsRemoteState.value = _tcsRemoteState.value.copy(
                            phase = "linked-connect-preflight",
                            available = _tcsRemoteState.value.available,
                            linked = _tcsRemoteState.value.linked,
                            lastStatus = "Fresh BLE connected; reading official CONNECT device name",
                            lastError = null
                        )

                        pendingOps.addFirst(
                            PendingOp.Read(
                                deviceName!!,
                                TCS_PURPOSE_LINKED_DEVICE_NAME
                            )
                        )
                        appendEvent("TCS18.8.3 CONNECT PREFLIGHT START: fresh BLE ready; reading cxtic2 device name before BCID/PacketLink")
                        if (!operationInFlight) processNext(g)
                    }
                }
                // 17.7: the 9383 profile is proprietary and is not assumed to transport
                // public S1C ASCII. ViewModel will arm passive reads/notifications only.
                appendEvent("18.3 services ready: proprietary 9383 profile mapped; proven official TCS pairing can start")
            } else {
                _state.value = "service-error:$status"
                _disconnectReason.value = "Service discovery failed: $status"
                appendEvent("Service discovery failed: $status")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            appendEvent("MTU changed mtu=$mtu status=$status")
            // R21: only finish the MTU ATT op if IT owns activeOp.
            // Unsolicited/late callbacks must never cancel another GATT read.
            if (gatt !== g) return
            if (status == BluetoothGatt.GATT_SUCCESS) outputR21MtuBytes = mtu
            val ownsOp = activeOp is PendingOp.OutputR21Mtu
            if (ownsOp) {
                outputR21MtuResult = if (status == BluetoothGatt.GATT_SUCCESS)
                    "negotiated" else "rejected-status-$status"
                cancelOperationTimeout()
                activeOp = null
                operationInFlight = false
            }
            android.util.Log.i("AtomRemoteOutputR21",
                "MTU_RESULT requested=185 negotiated=$outputR21MtuBytes status=$status " +
                    "owned=$ownsOp result=$outputR21MtuResult")
            if (ownsOp) processNext(g)
            // Audio-only continuation of the user-requested read. An E0 or
            // successful MTU callback does not itself prove Audio readback.
            if (r26AudioGetWaitingForMtu && ownsOp) {
                android.util.Log.i("AtomRemoteAudioR26Fix",
                    "AUDIO_MTU_RESULT negotiated=$outputR21MtuBytes status=$status " +
                        "sufficient=${status == BluetoothGatt.GATT_SUCCESS && outputR21MtuBytes >= 61}")
                if (status == BluetoothGatt.GATT_SUCCESS && outputR21MtuBytes >= 61) {
                    mainHandler.postDelayed({ dispatchR26AudioGetAfterMtu(100) }, 350L)
                } else {
                    r26AudioGetWaitingForMtu = false
                    android.util.Log.i("AtomRemoteAudioR26Fix", "AUDIO_GET_BLOCKED_MTU retry-manual")
                }
            }
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            appendEvent("PHY update tx=$txPhy rx=$rxPhy status=$status")
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            cancelOperationTimeout()
            operationInFlight = false
            val op = activeOp as? PendingOp.Read
            activeOp = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                updateValue(c, value, "read")
                if (op != null && handleOfficialLinkedConnectReadPurpose(g, op, value, status)) {
                    processNext(g)
                } else if (op != null && handlePacketLink18_8ReadPurpose(g, op, value, status)) {
                    processNext(g)
                } else if (op != null && handleConnectedReadPurpose(g, op, value, status)) {
                    processNext(g)
                } else if (op != null && handleRemoteReadPurpose(op, value, status)) {
                    processNext(g)
                } else if (op != null && handleTcsReadPurpose(g, op, value, status)) {
                    processNext(g)
                } else {
                    processNext(g)
                }
            } else if (status == GATT_AUTH || status == GATT_ENCRYPTION) {
                if (op?.purpose?.startsWith("tcs-") == true) {
                    failTcsPairing("${op.purpose} requires GATT security status=$status")
                } else {
                    handleSecurityRequired(g, g.device, status)
                }
            } else {
                if (op != null && handleOfficialLinkedConnectReadPurpose(g, op, value, status)) {
                    processNext(g)
                } else if (op != null && handlePacketLink18_8ReadPurpose(g, op, value, status)) {
                    processNext(g)
                } else if (op != null && handleConnectedReadPurpose(g, op, value, status)) {
                    processNext(g)
                } else if (op != null && handleRemoteReadPurpose(op, value, status)) {
                    processNext(g)
                } else if (op != null && handleTcsReadPurpose(g, op, value, status)) {
                    // TCS handler recorded the failure.
                } else {
                    appendEvent("Read ${c.uuid} failed: $status")
                    processNext(g)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            cancelOperationTimeout()
            operationInFlight = false
            val op = activeOp as? PendingOp.Read
            activeOp = null
            val value = c.value ?: byteArrayOf()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                updateValue(c, value, "read")
                if (op != null && !handleOfficialLinkedConnectReadPurpose(g, op, value, status) &&
                    !handlePacketLink18_8ReadPurpose(g, op, value, status) &&
                    !handleConnectedReadPurpose(g, op, value, status) &&
                    !handleRemoteReadPurpose(op, value, status)
                ) {
                    handleTcsReadPurpose(g, op, value, status)
                }
                processNext(g)
            } else if (status == GATT_AUTH || status == GATT_ENCRYPTION) {
                if (op?.purpose?.startsWith("tcs-") == true) {
                    failTcsPairing("${op.purpose} requires GATT security status=$status")
                } else {
                    handleSecurityRequired(g, g.device, status)
                }
            } else {
                if (op != null && handleOfficialLinkedConnectReadPurpose(g, op, value, status)) {
                    processNext(g)
                } else if (op != null && handlePacketLink18_8ReadPurpose(g, op, value, status)) {
                    processNext(g)
                } else if (op != null && handleConnectedReadPurpose(g, op, value, status)) {
                    processNext(g)
                } else if (op != null && handleRemoteReadPurpose(op, value, status)) {
                    processNext(g)
                } else if (op != null && handleTcsReadPurpose(g, op, value, status)) {
                    // TCS handler recorded the failure.
                } else {
                    appendEvent("Read ${c.uuid} failed: $status")
                    processNext(g)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            cancelOperationTimeout()
            operationInFlight = false
            val op = activeOp as? PendingOp.WriteDescriptor
            activeOp = null
            if (op?.purpose == TCS_PURPOSE_CONNECTED_FRAMECOUNT_CCCD) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    connectedFrameCountCccdReady = true
                    connectedLifecycleArmed = true
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        phase = "connected-sync",
                    available = _tcsRemoteState.value.available,
                    linked = _tcsRemoteState.value.linked,
                        lastStatus = "Waiting for recorder frame clock",
                        lastError = null
                    )
                    appendEvent("TCS18.6 CONNECTED cxtic0 CCCD READY; waiting for frame-count notification")
                    armConnectedSyncTimeout()
                } else {
                    connectedLifecycleArmed = false
                    connectedFrameCountCccdReady = false
                    _tcsRemoteState.value = _tcsRemoteState.value.copy(
                        phase = "error",
                        available = false,
                        linked = false,
                        lastStatus = "Recorder frame-clock subscription failed",
                        lastError = "cxtic0 CCCD write failed status=$status"
                    )
                    appendEvent("TCS18.6 CONNECTED cxtic0 CCCD failed status=$status; Android bond is not forced")
                }
                processNext(g)
                return
            }
            if (status == GATT_AUTH || status == GATT_ENCRYPTION) {
                if (op?.purpose == TCS_PURPOSE_BCID_CCCD) {
                    appendEvent("TCS18 BCID CCCD requires security status=$status; continuing with direct BCID read fallback")
                    processNext(g)
                } else if (op?.purpose?.startsWith("tcs-") == true) {
                    failTcsPairing("${op.purpose} requires GATT security status=$status")
                } else {
                    handleSecurityRequired(g, g.device, status)
                }
            } else {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    appendEvent("CCCD write OK char=${descriptor.characteristic.uuid} descriptor=${descriptor.uuid} purpose=${op?.purpose ?: "generic"}")
                } else {
                    appendEvent("Descriptor ${descriptor.uuid} write failed: $status")
                    if (op?.purpose == TCS_PURPOSE_BCID_CCCD) {
                        appendEvent("TCS18 BCID CCCD failed; continuing with direct BCID read/poll")
                    }
                }
                processNext(g)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            cancelOperationTimeout()
            operationInFlight = false
            val op = activeOp as? PendingOp.WriteCharacteristic
            if (op != null && c.uuid.toString().equals(TCS_PACKET_WRITE_UUID, true)) {
                android.util.Log.i("AtomRemoteA1280", "ATT_WRITE_CALLBACK purpose=${op.purpose} status=$status bytes=${op.value.size} hex=${op.value.toHex()}")
            }
            activeOp = null
            if (op != null && handleConnectedDataTransferWritePurpose(g, op, status)) {
                processNext(g)
                return
            }
            if (op != null && handlePacketLink18_8WritePurpose(g, op, status)) {
                processNext(g)
                return
            }
            if (op != null && handleRemoteWritePurpose(g, op, status)) {
                processNext(g)
                return
            }
            if (op != null && handleTcsWritePurpose(g, op, status)) {
                processNext(g)
                return
            }
            if (status == GATT_AUTH || status == GATT_ENCRYPTION) {
                appendEvent("Write ${c.uuid} requires security: $status; OS bond not forced")
            } else if (status == BluetoothGatt.GATT_SUCCESS) {
                appendEvent("Write ${c.uuid} accepted by Android GATT")
            } else {
                appendEvent("Write ${c.uuid} failed: $status")
            }
            processNext(g)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            updateValue(c, value, "notify")
            if (c.uuid.toString().equals(TCS_BCID_UUID, true) && value.size >= 4) {
                onOfficialBcid(u32Le(value), "notify")
            }
            if (c.uuid.toString().equals(TCS_FRAME_COUNT_UUID, true) && value.size >= 2) {
                onOfficialConnectedFrameCount(g, value)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                val value = c.value ?: byteArrayOf()
                updateValue(c, value, "notify")
                if (c.uuid.toString().equals(TCS_BCID_UUID, true) && value.size >= 4) {
                    onOfficialBcid(u32Le(value), "notify")
                }
                if (c.uuid.toString().equals(TCS_FRAME_COUNT_UUID, true) && value.size >= 2) {
                    onOfficialConnectedFrameCount(g, value)
                }
            }
        }
    }

    companion object {
        const val AIRGLU_SHOGUN_SERVICE_UUID: String = "9383b854-8bee-11e7-bb31-be2e44b06b34"

        // Exact TCSiOSC characteristic map recovered from the official macOS AtomRemote bundle.
        const val TCS_CONNECT_NAME_UUID: String = "b41d8bd4-a042-4fb4-9509-9bd3036d1ea2"
        const val TCS_BCID_UUID: String = "ce8dadf8-b7e0-4d9e-94cd-43f84803b361"
        const val TCS_CCID_UUID: String = "385a8147-e59c-4657-ab1a-c20b4ebb7b95"
        const val TCS_ID_SLOTS_UUID: String = "e751da25-85b1-46d4-aec4-5a0ee396670d"

        // Official TCS CONNECTED lifecycle characteristic mapping recovered from
        // TCSBleManager_StringForCxticId / CONNECTED_* states in TCSiOSC.framework.
        const val TCS_FRAME_COUNT_UUID: String = "9383b110-8bee-11e7-bb31-be2e44b06b34" // cxtic 0
        const val TCS_TIMECODE_UUID: String = "e3366e3f-9115-417f-9081-6daeee0788f2"    // cxtic 1
        const val TCS_DEVICE_NAME_UUID: String = "9383b110-8bee-11e7-bb79-be2e44b06b34" // cxtic 2

        // Official CONNECTED Data Transfer State Machine endpoints recovered from
        // TCSBleManager_StringForCxticId and DTSM state handlers in TCSiOSC.
        const val TCS_DATA_WRITE_UUID: String = "7fbca028-2601-44fa-b9ca-3fb04305209d"      // cxtic 8
        const val TCS_DATA_READ_UUID: String = "0252db82-f8a8-4874-b5cd-e65b7b3309b9"       // cxtic 9
        const val TCS_DATA_LINK_WRITE_UUID: String = "e107c6d9-d610-44e1-9cba-17fad3f4b9d7" // cxtic 10
        const val TCS_DATA_LINK_READ_UUID: String = "7c712e15-6378-439f-a706-d8fd0d9c70de"  // cxtic 11
        // 18.8.3: exact client CONNECT preflight recovered from TCSiOSC:
        // CONNECT_ReadingDeviceName reads cxtic 2, then CONNECT_ReadingBcid reads cxtic 4.
        private const val TCS_PURPOSE_LINKED_DEVICE_NAME = "linked-connect-device-name"
        private const val TCS_PURPOSE_LINKED_BCID = "linked-connect-bcid"

        // Official TCSPacketLink characteristic mapping recovered from TCSiOSC.
        const val TCS_PACKET_READ_UUID: String = "232be115-457c-4e46-a24c-e9267071b567"   // cxtic 12
        const val TCS_PACKET_WRITE_UUID: String = "8a8cc04b-7c4b-4c98-999e-b4ea39164c3a" // cxtic 13

        // Official RemoteApi command IDs recovered from the macOS AtomRemote binary.
        private const val REMOTE_RECORD_START = 300     // 0x012C, official RemoteApi.Record.Start
        private const val REMOTE_RECORD_STOP = 301     // 0x012D, official RemoteApi.Record.Stop
        private const val REMOTE_PLAY_START = 401     // 0x0191, official RemoteApi.Play.Start
        private const val REMOTE_PLAY_PAUSE = 402     // 0x0192, official RemoteApi.Play.Pause

        // Phase 19.11.9 Record codec command split recovered from Contents(3).zip.
        // 0x013A is CodecProfileGet (not a setter).
        // 0x013B is restart-capable CodecProfileSet.
        // 0x013F is the normal non-reboot Record picker setter.
        private const val REMOTE_RECORD_CODEC_PROFILE_GET = 0x013A
        private const val REMOTE_RECORD_CODEC_PROFILE_SET = 0x013B
        private const val REMOTE_RECORD_NON_REBOOT_CODEC_PROFILE_SET = 0x013F

        // Phase 09 read-only polling IDs recovered from macOS Contents(2).zip.
        // These exact GETs already exist in the official Remote screen startup.
        private const val REMOTE_APP_POLLING_APPLICATION_STATE_GET = 0x0640
        private const val REMOTE_APP_POLLING_MONITOR_PAGE_GET = 0x0641
        private const val REMOTE_APP_POLLING_INPUT_PAGE_GET = 0x0642
        private const val REMOTE_APP_POLLING_RECORD_PAGE_GET = 0x0643
        private const val REMOTE_APP_POLLING_OUTPUT_PAGE_GET = 0x0644
        private const val REMOTE_APP_POLLING_INPUT_HDR_PAGE_GET = 0x0645
        private const val REMOTE_APP_POLLING_PLAYBACK_FORMAT_PAGE_GET = 0x0646
        private const val REMOTE_APP_POLLING_AUDIO_METERS_PAGE_GET = 0x0647
        private const val REMOTE_APP_POLLING_INPUT_OUTPUT_SUMMARY_GET = 0x0648
        private const val REMOTE_APP_POLLING_MONITOR_ASSIST_SUMMARY_GET = 0x0649
        private const val APP_POLLING_STATE_PAGE_COUNT = 10
        private const val INPUT_GRANULAR_RESPONSE_TIMEOUT_MS = 1100L
        private const val CODEC_GRANULAR_RESPONSE_TIMEOUT_MS = 1200L

        // 20.06 Scopes exact contiguous RemoteApi matrix.
        private const val REMOTE_MONITOR_WAVEFORM_ENABLE_RESPONSE = 0x0258
        private const val REMOTE_MONITOR_WAVEFORM_MODE_RESPONSE = 0x0259
        private const val REMOTE_MONITOR_WAVEFORM_SIZE_RESPONSE = 0x025A
        private const val REMOTE_MONITOR_WAVEFORM_OPACITY_RESPONSE = 0x025B
        private const val REMOTE_MONITOR_WAVEFORM_BRIGHTNESS_RESPONSE = 0x025C

        private const val REMOTE_MONITOR_WAVEFORM_ENABLE_SET = 0x0258
        private const val REMOTE_MONITOR_WAVEFORM_ENABLE_GET = 0x0259
        private const val REMOTE_MONITOR_WAVEFORM_MODE_SET = 0x025A
        private const val REMOTE_MONITOR_WAVEFORM_MODE_GET = 0x025B
        private const val REMOTE_MONITOR_WAVEFORM_SIZE_SET = 0x025C
        private const val REMOTE_MONITOR_WAVEFORM_SIZE_GET = 0x025D
        private const val REMOTE_MONITOR_WAVEFORM_OPACITY_SET = 0x025E
        private const val REMOTE_MONITOR_WAVEFORM_OPACITY_GET = 0x025F
        private const val REMOTE_MONITOR_WAVEFORM_BRIGHTNESS_SET = 0x0260
        private const val REMOTE_MONITOR_WAVEFORM_BRIGHTNESS_GET = 0x0261

        // 20.05 Focus Assist exact command matrix recovered from uploaded macOS binary.
        private const val REMOTE_MONITOR_FOCUS_PEAK_ENABLE_RESPONSE = 0x025D
        private const val REMOTE_MONITOR_FOCUS_PEAK_TYPE_RESPONSE = 0x025E
        private const val REMOTE_MONITOR_FOCUS_PEAK_THRESHOLD_RESPONSE = 0x025F
        private const val REMOTE_MONITOR_FOCUS_PEAK_COLOUR_RESPONSE = 0x0260
        private const val REMOTE_MONITOR_ZEBRA_ENABLE_RESPONSE = 0x0261
        private const val REMOTE_MONITOR_ZEBRA_THRESHOLD_RESPONSE = 0x0262
        private const val REMOTE_MONITOR_FALSE_COLOUR_ENABLE_RESPONSE = 0x0263
        private const val REMOTE_MONITOR_BLUE_ONLY_ENABLE_RESPONSE = 0x0264
        private const val REMOTE_MONITOR_FOCUS_PEAK_SUPPORTED_THRESHOLDS_RESPONSE = 0x026B

        private const val REMOTE_MONITOR_FOCUS_PEAK_ENABLE_SET = 0x0262
        private const val REMOTE_MONITOR_FOCUS_PEAK_ENABLE_GET = 0x0263
        private const val REMOTE_MONITOR_FOCUS_PEAK_TYPE_SET = 0x0264
        private const val REMOTE_MONITOR_FOCUS_PEAK_TYPE_GET = 0x0265
        private const val REMOTE_MONITOR_FOCUS_PEAK_THRESHOLD_SET = 0x0266
        private const val REMOTE_MONITOR_FOCUS_PEAK_THRESHOLD_GET = 0x0267
        private const val REMOTE_MONITOR_FOCUS_PEAK_COLOUR_SET = 0x0268
        private const val REMOTE_MONITOR_FOCUS_PEAK_COLOUR_GET = 0x0269
        private const val REMOTE_MONITOR_FOCUS_PEAK_SUPPORTED_THRESHOLDS_GET = 0x027D

        private const val REMOTE_MONITOR_ZEBRA_ENABLE_SET = 0x026A
        private const val REMOTE_MONITOR_ZEBRA_ENABLE_GET = 0x026B
        private const val REMOTE_MONITOR_ZEBRA_THRESHOLD_SET = 0x026C
        private const val REMOTE_MONITOR_ZEBRA_THRESHOLD_GET = 0x026D

        // Phase 03 Monitor Assist setters recovered from macOS Contents(2).zip.
        private const val REMOTE_MONITOR_FALSE_COLOUR_ENABLE_SET = 0x026E
        private const val REMOTE_MONITOR_FALSE_COLOUR_ENABLE_GET = 0x026F
        private const val REMOTE_MONITOR_BLUE_ONLY_ENABLE_SET = 0x0270
        private const val REMOTE_MONITOR_BLUE_ONLY_ENABLE_GET = 0x0271
        private const val REMOTE_MONITOR_SETTINGS_CHANGEABLE_GET = 0x027E
        private const val REMOTE_MONITOR_SETTINGS_CHANGEABLE_RESPONSE = 0x026C
        private const val REMOTE_MONITOR_ZOOM_LEVEL_SET = 0x0272
        private const val REMOTE_MONITOR_ZOOM_LEVEL_GET = 0x0273
        private const val REMOTE_MONITOR_ZOOM_LEVEL_RESPONSE = 0x0265
        private const val REMOTE_MONITOR_ZOOM_LEVEL_LIST_GET = 0x0274
        private const val REMOTE_MONITOR_ZOOM_LEVEL_LIST_RESPONSE = 0x0266
        private const val REMOTE_MONITOR_ZOOM_BOX_GET = 0x027B
        private const val REMOTE_MONITOR_ZOOM_BOX_RESPONSE = 0x026A
        private const val REMOTE_MONITOR_ZOOM_PAN_SET = 0x027C

        // Phase 07 MonitorModeSet. Directly recovered from the macOS
        // MonitorTabController call to RemoteApi.Monitor.MonitorModeSet.
        private const val REMOTE_MONITOR_MODE_SET = 0x02BC
        private const val REMOTE_MONITOR_MODE_GET = 0x02BD
        private const val REMOTE_MONITOR_MODE_RESPONSE = 0x02BC

        // Guides / Phase 06 monitor commands recovered from macOS Contents(2).zip.
        private const val REMOTE_MONITOR_FRAME_GUIDE_FORMAT_SET = 0x0275
        private const val REMOTE_MONITOR_FRAME_GUIDE_FORMAT_GET = 0x0276
        private const val REMOTE_MONITOR_FRAME_GUIDE_FORMAT_RESPONSE = 0x0267
        private const val REMOTE_MONITOR_SAFE_AREA_SIZE_SET = 0x0277
        private const val REMOTE_MONITOR_SAFE_AREA_SIZE_GET = 0x0278
        private const val REMOTE_MONITOR_SAFE_AREA_SIZE_RESPONSE = 0x0268
        // 20.09.0-20.09.2 exact LUT RemoteApi IDs recovered from latest macOS binary.
        private const val REMOTE_MONITOR_LUT_SLOT_SET = 0x02BE
        private const val REMOTE_MONITOR_LUT_SLOT_GET = 0x02BF
        private const val REMOTE_MONITOR_LUT_SLOT_RESPONSE = 0x02BD
        private const val REMOTE_MONITOR_LUT_PREVIEW_ENABLE_SET = 0x02C0
        private const val REMOTE_MONITOR_LUT_PREVIEW_ENABLE_GET = 0x02C1
        private const val REMOTE_MONITOR_LUT_PREVIEW_ENABLE_RESPONSE = 0x02BE
        private const val REMOTE_MONITOR_LUT_SLOT_INFO_GET = 0x02C4
        private const val REMOTE_MONITOR_LUT_SLOT_INFO_RESPONSE = 0x02C0
        private const val REMOTE_MONITOR_LUT_PREVIEW_AVAILABLE_GET = 0x02C7
        private const val REMOTE_MONITOR_LUT_PREVIEW_AVAILABLE_RESPONSE = 0x02C1
        private const val REMOTE_MONITOR_LUT_SLOT_INFO_LIST_GET = 0x02C8
        private const val REMOTE_MONITOR_LUT_SLOT_INFO_LIST_RESPONSE = 0x02C8

        // 20.09.3-20.09.5 LUT Record/Output families recovered from the latest
        // supplied macOS binary. Command ordering is anchored by the already-proven
        // Record.RebootToPlayback=0x0132 / PrerollEnableGet=0x0135 and
        // Output.HdrEnableGet=0x00DD sequence.
        private const val REMOTE_RECORD_LUT_BURN_IN_ENABLE_SET = 0x012F
        private const val REMOTE_RECORD_LUT_BURN_IN_ENABLE_GET = 0x0130
        private const val REMOTE_RECORD_LUT_BURN_IN_ENABLE_RESPONSE = 0x012D
        private const val REMOTE_OUTPUT_LUT_OUTPUT_ENABLE_SET = 0x00E2
        private const val REMOTE_OUTPUT_LUT_OUTPUT_ENABLE_GET = 0x00E3
        private const val REMOTE_OUTPUT_LUT_OUTPUT_ENABLE_RESPONSE = 0x00E2
        private const val REMOTE_OUTPUT_LUT_OUTPUT_AVAILABLE_GET = 0x00E7
        private const val REMOTE_OUTPUT_LUT_OUTPUT_AVAILABLE_RESPONSE = 0x00E4

        // 20.10 Display family recovered from latest macOS binary. Even IDs are setters/messages, odd IDs are getters.
        private const val REMOTE_DISPLAY_BACKLIGHT_SET = 0x0320
        private const val REMOTE_DISPLAY_BACKLIGHT_GET = 0x0321
        private const val REMOTE_DISPLAY_BACKLIGHT_RESPONSE = 0x0321
        private const val REMOTE_DISPLAY_Y_LIFT_SET = 0x0322
        private const val REMOTE_DISPLAY_Y_LIFT_GET = 0x0323
        private const val REMOTE_DISPLAY_Y_LIFT_RESPONSE = 0x0322
        private const val REMOTE_DISPLAY_Y_GAMMA_SET = 0x0324
        private const val REMOTE_DISPLAY_Y_GAMMA_GET = 0x0325
        private const val REMOTE_DISPLAY_Y_GAMMA_RESPONSE = 0x0324
        private const val REMOTE_DISPLAY_Y_GAIN_SET = 0x0326
        private const val REMOTE_DISPLAY_Y_GAIN_GET = 0x0327
        private const val REMOTE_DISPLAY_Y_GAIN_RESPONSE = 0x0326
        private const val REMOTE_DISPLAY_TALLY_SET = 0x0328
        private const val REMOTE_DISPLAY_TALLY_GET = 0x0329
        private const val REMOTE_DISPLAY_TALLY_RESPONSE = 0x0328
        private const val REMOTE_DISPLAY_SUPPORTS_RESPONSE = 0x032A
        private const val REMOTE_DISPLAY_FLIP_SET = 0x032C
        private const val REMOTE_DISPLAY_FLIP_GET = 0x032D
        private const val REMOTE_DISPLAY_FLIP_RESPONSE = 0x032C
        private const val REMOTE_DISPLAY_PICTURE_RESET = 0x032E
        private const val REMOTE_DISPLAY_SUPPORTS_GET = 0x032F

        private const val REMOTE_MONITOR_ANAMORPHIC_DESQUEEZE_MODE_SET = 0x0279
        private const val REMOTE_MONITOR_ANAMORPHIC_DESQUEEZE_MODE_GET = 0x027A
        private const val REMOTE_MONITOR_ANAMORPHIC_DESQUEEZE_MODE_RESPONSE = 0x0269
        private const val REMOTE_MONITOR_FRAME_GUIDE_SET = 0x044E
        private const val REMOTE_MONITOR_FRAME_GUIDE_GET = 0x044F
        private const val REMOTE_MONITOR_FRAME_GUIDE_RESPONSE = 0x044E
        private const val REMOTE_GENERIC_SUCCESS = 0x8000

        // Exact mode-transition commands recovered from macOS recordButtonPressed:
        // and playButtonPressed:. Constructor m_isLocal is true for both.
        private const val REMOTE_PLAY_REBOOT_TO_RECORD = 0x0197
        private const val REMOTE_RECORD_REBOOT_TO_PLAYBACK = 0x0132

        private const val TCS_PURPOSE_BCID_CCCD = "tcs-bcid-cccd"
        private const val TCS_PURPOSE_ID_SLOTS = "tcs-id-slots"
        private const val TCS_PURPOSE_CONNECT_NAME = "tcs-connect-name"
        private const val TCS_PURPOSE_CCID = "tcs-ccid"
        private const val TCS_PURPOSE_BCID_READ = "tcs-bcid-read"
        private const val TCS_PURPOSE_ACCEPT = "tcs-accept"
        private const val TCS_PURPOSE_CONNECTED_FRAMECOUNT_CCCD = "connected-framecount-cccd"
        private const val TCS_PURPOSE_CONNECTED_TIMECODE = "connected-timecode"

        private const val TCS_PURPOSE_CONNECTED_DTSM_DEVICE_NAME = "connected-dtsm-device-name"
        private const val TCS_PURPOSE_CONNECTED_DTSM_OFFER_WRITE = "connected-dtsm-offer-write"
        private const val TCS_PURPOSE_CONNECTED_DTSM_ACCEPT_WRITE = "connected-dtsm-accept-write"
        private const val TCS_PURPOSE_CONNECTED_DTSM_LINK_READ = "connected-dtsm-link-read"
        private const val TCS_PURPOSE_CONNECTED_DTSM_DATA_WRITE = "connected-dtsm-data-write"
        private const val TCS_PURPOSE_CONNECTED_DTSM_DATA_READ = "connected-dtsm-data-read"
        private const val TCS_PURPOSE_CONNECTED_DEVICE_NAME = "connected-device-name"
        private const val PLINK18_WRITE_PURPOSE = "plink18-write"
        private const val PLINK18_READ_PURPOSE = "plink18-read"

        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val GATT_AUTH = 5
        private const val GATT_ENCRYPTION = 15
        private const val PACKET_LINK_PROCESS_TICK_MS = 10L
        private const val REMOTE_BOOTSTRAP_DRAIN_MS = 750L
        private const val REMOTE_RESPONSE_TIMEOUT_MS = 2500L
        private const val ZOOM_CAPABILITY_TIMEOUT_MS = 900L
        private const val CONNECTED_SYNC_WAIT_MS = 8_000L
        private const val CONNECTED_TIMECODE_REFRESH_MS = 750L
    }
}












