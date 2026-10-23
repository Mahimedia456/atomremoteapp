package com.atomosremote.nativeapp.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atomosremote.nativeapp.R
import com.atomosremote.nativeapp.advanced.AdvancedControlCatalog
import com.atomosremote.nativeapp.airglu.AirGluRole
import com.atomosremote.nativeapp.atomos.AtomosCapability
import com.atomosremote.nativeapp.atomos.AtomosProfiles
import com.atomosremote.nativeapp.atomos.CapabilityStatus
import com.atomosremote.nativeapp.atomos.TelemetryParser
import com.atomosremote.nativeapp.ble.BleAdvertisement
import com.atomosremote.nativeapp.ble.BlePermission
import com.atomosremote.nativeapp.ble.ZoomBoxState
import com.atomosremote.nativeapp.output.OutputPageWireDecoder
import com.atomosremote.nativeapp.monitor.MonitorLutState
import com.atomosremote.nativeapp.monitor.MonitorDisplayState
import com.atomosremote.nativeapp.network.LanDeviceCandidate
import com.atomosremote.nativeapp.network.NetworkServiceCandidate
import com.atomosremote.nativeapp.ndi.NdiPreviewState
import com.atomosremote.nativeapp.ndi.NdiSourceRegistry
import com.atomosremote.nativeapp.ndi06.NdiDiscovery
import com.atomosremote.nativeapp.ndi06.NdiSource as Ndi06DiscoveredSource
import com.atomosremote.nativeapp.ndi07.Ndi07Native
import com.atomosremote.nativeapp.ndi07.Ndi07InlinePreview
import com.atomosremote.nativeapp.viewmodel.AtomosViewModel
import com.atomosremote.nativeapp.viewmodel.RemoteTransportUiController
import com.atomosremote.nativeapp.input.InputSourceCatalog
import com.atomosremote.nativeapp.input.InputSignalPresenter
import com.atomosremote.nativeapp.input.InputHdmiStandardCatalog
import com.atomosremote.nativeapp.input.InputDeviceIdentityPresenter
import com.atomosremote.nativeapp.input.InputHdrStatePresenter
import com.atomosremote.nativeapp.input.InputHdrCameraPresenter
import com.atomosremote.nativeapp.input.InputHdrGammaPresenter
import com.atomosremote.nativeapp.input.InputHdrGamutPresenter
import com.atomosremote.nativeapp.input.InputHdrCapabilitiesPresenter
import com.atomosremote.nativeapp.input.InputExtendedPresenter
import com.atomosremote.nativeapp.input.InputFinalPresenter
import com.atomosremote.nativeapp.monitor.MonitorCoreSnapshot
import com.atomosremote.nativeapp.monitor.MonitorZoomCoreState
import com.atomosremote.nativeapp.monitor.MonitorZoomViewportState
import com.atomosremote.nativeapp.monitor.MonitorFocusAssistState
import com.atomosremote.nativeapp.monitor.MonitorZebraState
import com.atomosremote.nativeapp.monitor.MonitorAssistEffectsState
import com.atomosremote.nativeapp.monitor.MonitorScopeState
import com.atomosremote.nativeapp.monitor.MonitorGuideState
import com.atomosremote.nativeapp.monitor.MonitorAnamorphicState
import com.atomosremote.nativeapp.input.InputEditCatalog
import com.atomosremote.nativeapp.audio.AudioR26Catalog
import com.atomosremote.nativeapp.audio.AudioR26Setting
import com.atomosremote.nativeapp.audio.AudioR26WireInspector
import com.atomosremote.nativeapp.audio.AudioR26Part2Plan

// OUTPUT01-COLOR-RESOLVE-V2: UI-local color aliases preserve the established palette.
// No changes to global theme or recorder control transport.
private val AtomosCyan = androidx.compose.ui.graphics.Color(0xFF19E7D3)
private val AtomosRed = androidx.compose.ui.graphics.Color(0xFFFF3B3B)
private val AtomosSurface2 = androidx.compose.ui.graphics.Color(0xFF182125)

// V16.1: BLE addresses cannot be derived from NDI DNS-SD advertisements.
// Use only a UNIQUE, strong recorder-name match for automatic selection.
// Otherwise the user explicitly binds the desired NDI sender to this BLE device.
private fun ndi161NameToken(value: String): String = value.uppercase(java.util.Locale.ROOT)
    .filter { it in 'A'..'Z' || it in '0'..'9' }

private fun ndi161LikelySameRecorder(deviceName: String, ndiName: String): Boolean {
    val device = ndi161NameToken(deviceName)
    val sender = ndi161NameToken(ndiName)
    if (device.length < 6 || sender.length < 6) return false
    if (device in setOf("ATOMOSRECORDER", "SHOGUNULTRA", "UNKNOWNDEVICE", "BLUETOOTHREMOTE")) return false
    return sender.startsWith(device) || (sender.length >= 8 && device.startsWith(sender))
}

// V16: Strict PRIVATE IPv4 validation. Never send NDI traffic to mobile-data
// public addresses. Hotspot host/client can have any RFC1918 subnet.
private fun ndi16LocalIpv4(value: String): Boolean {
    if (value.isBlank() || value.length > 15) return false
    val parts = value.split('.')
    if (parts.size != 4) return false
    val a = IntArray(4)
    for (i in 0..3) {
        val part = parts[i]
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return false
        if (part.length > 1 && part[0] == '0') return false
        val octet = part.toIntOrNull() ?: return false
        if (octet !in 0..255) return false
        a[i] = octet
    }
    return (a[0] == 10) || (a[0] == 172 && a[1] in 16..31) ||
        (a[0] == 192 && a[1] == 168) || (a[0] == 169 && a[1] == 254)
}

@Composable
fun AtomosApp(vm: AtomosViewModel = viewModel()) {
    val screen by vm.screen.collectAsState()
    val selectedRecorder by vm.selected.collectAsState()
    val connectedRecorderName by vm.connectedRecorderName.collectAsState()
    val tcsPairState by vm.tcsPairingState.collectAsState()
    // R26.50 UI-only post-pair interstitial. Background RemoteScreen composition
    // remains active so existing GATT, NDI and collector work is never delayed.
    var prepareArmed by remember { mutableStateOf(false) }
    var prepareShowing by remember { mutableStateOf(false) }
    var prepareTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(screen, selectedRecorder?.address, tcsPairState.acceptWritten) {
        if (screen == AtomosViewModel.Screen.PAIR) {
            prepareArmed = true
            prepareShowing = false
            prepareTick = 0
        } else if (screen == AtomosViewModel.Screen.REMOTE &&
            prepareArmed && tcsPairState.acceptWritten) {
            prepareArmed = false
            prepareShowing = true
            prepareTick = 0
            val deviceKey = selectedRecorder?.address.orEmpty()
            Log.i("AtomRemotePrepare", "PREPARE_START device=$deviceKey durationMs=10000 transportUntouched=true")
            // This is a presentation hold, NOT a hardware-ready acknowledgement.
            for (i in 1..100) {
                delay(100L)
                prepareTick = i
            }
            prepareShowing = false
            Log.i("AtomRemotePrepare", "PREPARE_END device=$deviceKey durationMs=10000")
        } else if (screen != AtomosViewModel.Screen.REMOTE) {
            prepareShowing = false
            prepareTick = 0
        }
    }
    if (!AtomRemoteStartupGate()) return
    Surface(Modifier.fillMaxSize()) {
        when (screen) {
            AtomosViewModel.Screen.HOME -> HomeScreen(vm)
            AtomosViewModel.Screen.CONNECT -> ConnectScreen(vm)
            AtomosViewModel.Screen.PAIR -> PairScreen(vm)
            AtomosViewModel.Screen.REMOTE -> RemoteScreen(vm)
            AtomosViewModel.Screen.INPUT -> InputModuleScreen(vm)
            AtomosViewModel.Screen.AUDIO -> AudioModuleScreen(vm)
            AtomosViewModel.Screen.MEDIA -> MediaModuleScreen(vm)
            AtomosViewModel.Screen.OUTPUT -> OutputModuleScreen(vm)
            AtomosViewModel.Screen.SETTINGS -> SettingsModuleScreen(vm)
            AtomosViewModel.Screen.GATT -> GattScreen(vm)
            AtomosViewModel.Screen.PROTOCOL -> ProtocolScreen(vm)
            AtomosViewModel.Screen.COMPATIBILITY -> CompatibilityScreen(vm)
            AtomosViewModel.Screen.NETWORK -> NetworkScreen(vm)
            AtomosViewModel.Screen.MULTI -> MultiDeviceScreen(vm)
            AtomosViewModel.Screen.AIRGLU -> AirGluScreen(vm)
            AtomosViewModel.Screen.NDI -> NdiPreviewScreen(vm)
            AtomosViewModel.Screen.ADVANCED -> AdvancedControlsScreen(vm)
            AtomosViewModel.Screen.PRESETS -> PresetsScreen(vm)
            AtomosViewModel.Screen.CAPTURE -> CaptureScreen(vm)
            AtomosViewModel.Screen.QA -> FinalQaScreen(vm)
        }
        if (prepareShowing && screen == AtomosViewModel.Screen.REMOTE) {
            val preparedDeviceName = connectedRecorderName?.trim()?.takeIf { it.isNotBlank() }
                ?: selectedRecorder?.let { vm.displayNameFor(it) }?.trim()?.takeIf { it.isNotBlank() }
                ?: "ATOMOS Recorder"
            Dialog(
                onDismissRequest = { /* 10-second pairing presentation, not user-dismissible */ },
                properties = androidx.compose.ui.window.DialogProperties(
                    dismissOnBackPress = false, dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false)
            ) {
                Column(
                    Modifier.fillMaxWidth(0.90f)
                        .background(Color(0xFF091418), RoundedCornerShape(22.dp))
                        .border(1.dp, Color(0xFF305056), RoundedCornerShape(22.dp))
                        .padding(horizontal = 26.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(Modifier.size(64.dp)
                        .background(Color(0xFF113C3C), CircleShape),
                        contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(38.dp),
                            strokeWidth = 3.dp, color = AtomosCyan)
                    }
                    Text("PREPARING YOUR REMOTE", color = Color.White, fontSize = 17.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                    Text(preparedDeviceName, color = AtomosCyan, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 2,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text("It may take about 10 seconds. Hold tight while we prepare your recorder.",
                        color = Color(0xFFADBBC0), fontSize = 12.sp, lineHeight = 18.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    LinearProgressIndicator(progress = { prepareTick.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth().height(9.dp)
                            .clip(RoundedCornerShape(9.dp)),
                        color = AtomosCyan, trackColor = Color(0xFF243E42))
                    Text("${10 - (prepareTick / 10)}s remaining",
                        color = Color(0xFF9BB4B7), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun AppHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth().statusBarsPadding(),
        color = Color(0xFF030B0E),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                Surface(
                    modifier = Modifier.size(40.dp).clickable(onClick = onBack),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF071619),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1B393D))
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("<", fontSize = 27.sp, color = RemoteMint, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(9.dp))
            }

            Image(
                painter = painterResource(R.drawable.atom_remote_brand_mark),
                contentDescription = "Atom Remote",
                modifier = Modifier.size(39.dp)
            )
            Spacer(Modifier.width(9.dp))

            Text(
                "REMOTE",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
                letterSpacing = 0.8.sp,
                maxLines = 1
            )

            Spacer(Modifier.weight(1f))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    title,
                    color = RemoteMint,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = Color(0xFF839397),
                        fontSize = 8.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(vm: AtomosViewModel) {
    val saved by vm.saved.collectAsState()
    val savedAll by vm.savedAll.collectAsState()
    val networkStatus by vm.networkStatus.collectAsState()
    val lanDevices by vm.lanDevices.collectAsState()

    DisposableEffect(Unit) {
        vm.startNetworkDiscovery()
        onDispose { vm.stopNetworkDiscovery() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF02090B))
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.atom_remote_brand_mark),
                contentDescription = "Atom Remote",
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("REMOTE", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Text("Wireless recorder control", color = Color(0xFF7A8C90), fontSize = 9.5.sp)
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF071A18),
                border = androidx.compose.foundation.BorderStroke(1.dp, RemoteMint.copy(alpha = 0.35f))
            ) {
                Text(
                    if (networkStatus.connected) "ONLINE" else "READY",
                    color = RemoteMint,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(Modifier.height(22.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF061316),
            border = androidx.compose.foundation.BorderStroke(1.dp, RemoteMint.copy(alpha = 0.45f))
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.atom_remote_brand_mark),
                    contentDescription = null,
                    modifier = Modifier.size(112.dp)
                )
                Text("CONTROL YOUR RECORDER", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "Pair once, then control monitoring, recording and recorder settings from one workspace.",
                    color = Color(0xFF84969A),
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
                Button(
                    onClick = { vm.go(AtomosViewModel.Screen.CONNECT) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RemoteMint, contentColor = Color(0xFF00110D))
                ) {
                    Text("SCAN & CONNECT", fontWeight = FontWeight.Black, fontSize = 10.sp)
                }
                if (saved != null) {
                    OutlinedButton(
                        onClick = { vm.reconnectSaved() },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF28544D))
                    ) {
                        Text("RECONNECT ${saved!!.name.uppercase()}", color = RemoteMint, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF071216),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1C363B))
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(if (networkStatus.connected) RemoteMint else Color(0xFF66767A), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("SAME NETWORK", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Text(
                            "${networkStatus.transport} | ${lanDevices.size} discovered",
                            color = Color(0xFF71858A),
                            fontSize = 8.5.sp
                        )
                    }
                    TextButton(onClick = { vm.stopNetworkDiscovery(); vm.startNetworkDiscovery() }) {
                        Text("RESCAN", color = RemoteMint, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                lanDevices.take(3).forEach { device ->
                    Row(Modifier.fillMaxWidth().padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                device.displayName,
                                color = if (device.likelyAtomos) RemoteMint else Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(device.host, color = Color(0xFF5F7378), fontSize = 8.sp)
                        }
                        if (device.likelyAtomos) {
                            TextButton(onClick = { vm.startWifiAssistedRemote(device) }) {
                                Text("PAIR", color = RemoteMint, fontSize = 8.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        val utilityRows = listOf(
            Triple("NETWORK", "MULTI ${savedAll.size}", Pair(AtomosViewModel.Screen.NETWORK, AtomosViewModel.Screen.MULTI)),
            Triple("DEVICES", "AIRGLU", Pair(AtomosViewModel.Screen.COMPATIBILITY, AtomosViewModel.Screen.AIRGLU)),
            Triple("NDI", "PRESETS", Pair(AtomosViewModel.Screen.NDI, AtomosViewModel.Screen.PRESETS))
        )
        utilityRows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 7.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(
                    onClick = { vm.go(row.third.first) },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF25423F))
                ) { Text(row.first, color = Color.White, fontSize = 9.sp) }
                OutlinedButton(
                    onClick = { vm.go(row.third.second) },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF25423F))
                ) { Text(row.second, color = Color.White, fontSize = 9.sp) }
            }
        }
    }
}

private fun permissionsGranted(context: android.content.Context): Boolean = BlePermission.requiredScanPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun ConnectScreen(vm: AtomosViewModel) {
    val context = LocalContext.current
    val devices by vm.devices.collectAsState()
    val scanState by vm.scanState.collectAsState()
    val scanDiagnostics by vm.scanDiagnostics.collectAsState()
    val wifiAssistTarget by vm.wifiAssistTarget.collectAsState()
    var permissionDenied by remember { mutableStateOf(false) }
    var optionalLocationPrompted by remember { mutableStateOf(context.getSharedPreferences("atomremote_startup_permissions", android.content.Context.MODE_PRIVATE).getBoolean("optional_location_offered", false)) }

    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (vm.bluetoothEnabled()) vm.startScan()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // Fine Location is optional on Android 12+; refusing it does NOT block a scan.
        val ok = BlePermission.requiredScanPermissions().all {
            result[it] == true || ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        permissionDenied = !ok
        if (ok) {
            if (vm.bluetoothEnabled()) vm.startScan() else enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    fun begin() {
        permissionDenied = false
        val missingRequired = BlePermission.requiredScanPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        val missingOptional = if (!optionalLocationPrompted) BlePermission.optionalDiscoveryPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        } else emptyList()
        if (missingRequired.isNotEmpty() || missingOptional.isNotEmpty()) {
            if (missingOptional.isNotEmpty()) optionalLocationPrompted = true
            permissionLauncher.launch((missingRequired + missingOptional).distinct().toTypedArray())
        } else if (!vm.bluetoothEnabled()) enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        else vm.startScan()
    }

    LaunchedEffect(Unit) { begin() }
    DisposableEffect(Unit) { onDispose { vm.stopScan() } }

    // Discovery and pairing eligibility are decided in one authoritative place:
    // ViewModel's strict ATOMOS/AirGlu filter, not an extra UI-side filter.
    val visibleDevices = devices
    val scanning = scanState.startsWith("scanning")

    Column(Modifier.fillMaxSize().background(Color(0xFF02090C))) {
        AppHeader(
            "Connect recorder",
            "Bluetooth / AirGlu discovery",
            onBack = { vm.stopScan(); vm.stopPairingPresence(); vm.clearWifiAssist(); vm.go(AtomosViewModel.Screen.HOME) }
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF051519),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AtomosCyan.copy(alpha = 0.55f))
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("DISCOVERY", color = AtomosCyan, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                        Text("Find your ATOMOS", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                        Text(
                            "Open Bluetooth / AirGlu Pair on the recorder and keep that screen visible.",
                            color = Color(0xFF7E9297),
                            fontSize = 10.sp,
                            lineHeight = 15.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Box(Modifier.size(128.dp).padding(top = 12.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(122.dp).border(1.dp, Color(0xFF173D44), CircleShape))
                            Box(Modifier.size(88.dp).border(1.dp, Color(0xFF18525B), CircleShape))
                            Box(
                                Modifier
                                    .size(54.dp)
                                    .background(Color(0xFF08282D), CircleShape)
                                    .border(1.dp, AtomosCyan.copy(alpha = 0.60f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("A", color = AtomosCyan, fontSize = 22.sp, fontWeight = FontWeight.Black)
                            }
                            if (scanning) {
                                CircularProgressIndicator(Modifier.size(122.dp), strokeWidth = 2.dp, color = AtomosCyan)
                            }
                        }

                        Text(
                            if (scanning) "Scanning for compatible recorders..." else "${visibleDevices.size} compatible device(s)",
                            color = if (scanning) AtomosCyan else Color(0xFF91A3A7),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            scanDiagnostics,
                            color = Color(0xFF8FA7AC), fontSize = 9.sp,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                        if (scanState.startsWith("error:")) {
                            Text(
                                scanState.removePrefix("error:"), color = Color(0xFFFFA3A8), fontSize = 10.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        Button(
                            onClick = { begin() },
                            enabled = !scanning,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(47.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AtomosCyan, contentColor = Color(0xFF001013))
                        ) {
                            Text(if (scanning) "SCANNING..." else "SCAN FOR DEVICES", fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }

            wifiAssistTarget?.let { target ->
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(15.dp),
                        color = Color(0xFF07171B),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF16434A))
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("SAME WI-FI RECORDER", color = AtomosCyan, fontWeight = FontWeight.Black, fontSize = 9.sp)
                            Text(target.displayName, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
                            Text(target.host, color = Color(0xFF768B90), fontSize = 9.sp)
                        }
                    }
                }
            }

            if (permissionDenied) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp),
                        color = Color(0xFF241315),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6E3036))
                    ) {
                        Text(
                            "Bluetooth Nearby Devices is required. On Android 8 to 11 allow Location, and keep phone Location enabled while scanning.",
                            color = Color(0xFFFFA3A8),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(13.dp)
                        )
                    }
                }
            }

            item {
                Column {
                    Text("If 0 ATOMOS but Unique BLE > 0, the phone is scanning and recorder identity is not visible. Keep recorder Pair mode open.",
                        color = Color(0xFF768B90), fontSize = 9.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 7.dp)) {
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                            modifier = Modifier.weight(1f)
                        ) { Text("LOCATION", fontSize = 9.sp) }
                        OutlinedButton(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:${context.packageName}")))
                            }, modifier = Modifier.weight(1f)
                        ) { Text("APP PERMISSIONS", fontSize = 9.sp) }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("NEARBY ATOMOS DEVICES", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Tap a recorder to use the existing secure pairing flow.", color = Color(0xFF6E8287), fontSize = 9.sp)
                    }
                    Text("${visibleDevices.size}", color = AtomosCyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            }

            if (visibleDevices.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(15.dp),
                        color = Color(0xFF071216),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1C343A))
                    ) {
                        Text(
                            if (scanning) "Searching..." else "No compatible recorder found. Keep the recorder Pair screen open and scan again.",
                            color = Color(0xFF809398),
                            fontSize = 10.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(visibleDevices, key = { it.address }) { ad ->
                    DeviceCard(ad, vm.displayNameFor(ad), wifiAssistTarget != null) { vm.selectAndConnect(ad) }
                }
            }
        }
    }
}

private fun isAirGluSyncAdvertisement(ad: BleAdvertisement): Boolean {
    val n = ad.name?.trim()?.lowercase().orEmpty()
    val hasFcc1 = ad.serviceUuids.any { it.lowercase().contains("fcc1") }
    val hasCapturedShogunService = ad.serviceUuids.any { it.equals(AtomosViewModel.AIRGLU_SHOGUN_SERVICE_UUID, ignoreCase = true) }
    return hasFcc1 || hasCapturedShogunService || n == "ag2" || n == "ag" || n.startsWith("ag2-") || n.startsWith("ag-")
}

private fun likelyAtomosAdvertisement(ad: BleAdvertisement): Boolean {
    val n = ad.name?.trim()?.lowercase().orEmpty()
    return ad.advertisedPairingId != null || isAirGluSyncAdvertisement(ad) || AtomosProfiles.match(ad.name) != null ||
        n.contains("atomos") || n.contains("shogun") || n.contains("ninja") || n.contains("neon") || n.contains("sumo") || n.contains("shinobi")
}

private fun friendlyRecorderName(rawName: String?): String {
    val raw = rawName?.trim().orEmpty()
    val lower = raw.lowercase()
    return when {
        lower == "ag2" || lower.startsWith("ag2-") || lower == "ag" || lower.startsWith("ag-") ->
            "ATOMOS Recorder (${raw.ifBlank { "AirGlu" }})"
        lower.contains("shogun") || lower.contains("ninja") || lower.contains("neon") ||
            lower.contains("sumo") || lower.contains("shinobi") || lower.contains("atomos") -> raw
        raw.isNotBlank() -> raw
        else -> "ATOMOS Recorder"
    }
}

private fun recorderModelCode(rawName: String?): String? {
    val raw = rawName?.trim().orEmpty()
    val lower = raw.lowercase()
    return if (lower == "ag2" || lower.startsWith("ag2-") || lower == "ag" || lower.startsWith("ag-")) raw else null
}

@Composable
private fun DeviceCard(ad: BleAdvertisement, displayName: String, wifiAssist: Boolean = false, onClick: () -> Unit) {
    val service9383 = ad.serviceUuids.any { it.equals(AtomosViewModel.AIRGLU_SHOGUN_SERVICE_UUID, ignoreCase = true) }
    val signal = when {
        ad.rssi >= -55 -> "EXCELLENT"
        ad.rssi >= -67 -> "GOOD"
        ad.rssi >= -78 -> "FAIR"
        else -> "WEAK"
    }

    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF071317),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF234149))
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(47.dp)
                    .background(Color(0xFF08272C), RoundedCornerShape(13.dp))
                    .border(1.dp, Color(0xFF15515A), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(recorderModelCode(ad.name)?.take(2)?.uppercase() ?: "AR", color = AtomosCyan, fontSize = 12.sp, fontWeight = FontWeight.Black)
            }

            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                recorderModelCode(ad.name)?.let {
                    Text("AirGlu $it", color = AtomosCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "${if (service9383) "AirGlu Remote" else "Compatible ATOMOS"} | ${ad.rssi} dBm | $signal",
                    color = Color(0xFF819398),
                    fontSize = 8.7.sp
                )
                Text(ad.address, color = Color(0xFF5F7479), fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ad.advertisedPairingId?.let {
                    Text("Pairing ID $it", color = Color(0xFFFFC46C), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            Surface(
                shape = RoundedCornerShape(9.dp),
                color = Color(0xFF082B30),
                border = androidx.compose.foundation.BorderStroke(1.dp, AtomosCyan.copy(alpha = 0.55f))
            ) {
                Text(
                    if (wifiAssist) "PAIR" else "CONNECT",
                    color = AtomosCyan,
                    fontWeight = FontWeight.Black,
                    fontSize = 8.5.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun PairScreen(vm: AtomosViewModel) {
    val selected by vm.selected.collectAsState()
    val state by vm.gattState.collectAsState()
    val bondState by vm.bondState.collectAsState()
    val snapshot by vm.gattSnapshot.collectAsState()
    val tcsPairing by vm.tcsPairingState.collectAsState()
    val tcsRemote by vm.tcsRemoteState.collectAsState()
    val disconnectReason by vm.gattDisconnectReason.collectAsState()
    val s1c by vm.s1cStatus.collectAsState()
    val protocol by vm.protocol.collectAsState()
    val pairingAccepted by vm.pairingCodeAccepted.collectAsState()
    val codeCandidates by vm.pairingCodeCandidates.collectAsState()
    val scanState by vm.scanState.collectAsState()
    val gattEvents by vm.gattEvents.collectAsState()
    val smpPasskey by vm.peripheralSmpPasskey.collectAsState()
    val peripheralState by vm.peripheralState.collectAsState()
    val inboundAddress by vm.peripheralCentralAddress.collectAsState()
    val inboundPairingId by vm.peripheralPairingId.collectAsState()
    val context = LocalContext.current
    val connectedRecorderName by vm.connectedRecorderName.collectAsState()

    val pairingId = selected?.advertisedPairingId ?: snapshot?.pairingId ?: inboundPairingId
    val remoteReady = tcsRemote.available || tcsRemote.linked || s1c.ready || protocol?.canTransmit == true
    val centralLinked = state in setOf("connected", "services-ready", "tcs-pairing", "passive-pair-capture", "pair-discovery") || s1c.ready
    val busy = state == "connecting" || (state == "tcs-pairing" && tcsPairing.phase !in setOf("can-accept", "waiting-for-bcid", "accepted"))
    val staleBond = selected?.device?.bondState == BluetoothDevice.BOND_BONDED && !pairingAccepted && pairingId == null

    Column(Modifier.fillMaxSize().background(Color(0xFF02090C))) {
        AppHeader(
            connectedRecorderName?.takeIf { it.isNotBlank() } ?: selected?.let { vm.displayNameFor(it) } ?: "Connect recorder",
            selected?.let { ad -> recorderModelCode(ad.name)?.let { "AirGlu $it | ${ad.address}" } ?: ad.address } ?: "ATOMOS recorder",
            onBack = { vm.stopScan(); vm.stopPairingPresence(); vm.gatt.disconnect(); vm.go(AtomosViewModel.Screen.CONNECT) }
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PairStageChip("DEVICE", selected != null, Modifier.weight(1f))
                    PairStageChip("LINK", centralLinked, Modifier.weight(1f))
                    PairStageChip("PAIR", pairingId != null || pairingAccepted, Modifier.weight(1f))
                    PairStageChip("REMOTE", remoteReady, Modifier.weight(1f))
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF071317)), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PAIRING ID", color = Color(0xFF868D90), fontSize = 10.sp, letterSpacing = 1.2.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            pairingId ?: if (pairingAccepted) "Accepted" else "Waiting...",
                            fontSize = if (pairingId != null) 34.sp else 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = if (pairingId != null) 1.2.sp else 0.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when {
                                remoteReady -> "Recorder control channel confirmed."
                                pairingAccepted -> "Pairing accepted. Preparing Remote control."
                                pairingId != null -> "Confirm that this 9-digit code matches the recorder, then accept it on the recorder."
                                centralLinked -> "Recorder connected. Preparing the pairing code."
                                else -> "Keep the recorder Bluetooth pairing screen open."
                            },
                            color = Color(0xFF868D90),
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                        if (busy) {
                            Spacer(Modifier.height(14.dp))
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp, color = AtomosCyan)
                        }
                    }
                }
            }


            if (staleBond) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2115)), shape = RoundedCornerShape(15.dp)) {
                        Column(Modifier.fillMaxWidth().padding(15.dp)) {
                            Text("STALE ANDROID BOND DETECTED", color = Color(0xFFE8A23B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "Android already has an old Bluetooth bond for this recorder. Reset the bond, then use Unpair All on the recorder if a fresh pairing code does not appear.",
                                color = Color(0xFFD7C4A5), fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 5.dp)
                            )
                            Button(onClick = { vm.clearAndroidBondAndRetry() }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(50.dp)) {
                                Text("RESET STALE BOND + RESTART", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(46.dp)
                            ) { Text("OPEN ANDROID BLUETOOTH SETTINGS") }
                        }
                    }
                }
            }

            if (!pairingAccepted && codeCandidates.isNotEmpty()) {
                item {
                    Text("PAIRING CODES", color = Color(0xFF868D90), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("Select only the code shown on the recorder.", color = Color(0xFF868D90), fontSize = 11.sp)
                }
                items(codeCandidates, key = { "scan-${it.pairingId}-${it.sourceAddress}" }) { candidate ->
                    Card(
                        onClick = { vm.selectPairingCodeCandidate(candidate.pairingId) },
                        colors = CardDefaults.cardColors(containerColor = if (pairingId == candidate.pairingId) Color(0xFF173331) else Color(0xFF1A1F21)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(candidate.pairingId, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    if (candidate.exactAg2Source) "Recorder advertisement" else "Pairing signal | ${candidate.rssi} dBm",
                                    color = Color(0xFF868D90), fontSize = 10.sp
                                )
                            }
                            Text(if (pairingId == candidate.pairingId) "SELECTED" else "USE IF MATCH", color = AtomosCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (!pairingAccepted && pairingId != null) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { vm.retryPairingIdScan() }, modifier = Modifier.weight(1f).height(50.dp)) { Text("RESCAN") }
                        Button(
                            onClick = { vm.confirmPairingCodeAndContinue() },
                            enabled = tcsPairing.canAccept,
                            modifier = Modifier.weight(1.5f).height(50.dp)
                        ) {
                            Text(if (tcsPairing.canAccept) "ACCEPT MATCHING CODE" else "WAITING FOR RECORDER", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (!pairingAccepted) {
                item {
                    OutlinedButton(onClick = { vm.retryPairingIdScan() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Text("RECONNECT RECORDER")
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF071317)), shape = RoundedCornerShape(15.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(if (remoteReady) AtomosCyan else Color(0xFF687174), CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (remoteReady) "Remote ready" else "Connecting", color = Color(0xFFE5E9EA), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(if (remoteReady) "Pairing complete" else "Keep Pair mode open on the recorder", color = Color(0xFF818A8D), fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }

            if (pairingAccepted && !remoteReady) {
                item {
                    Button(onClick = { vm.startPairingDiscovery() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("OPEN REMOTE", fontWeight = FontWeight.Bold)
                    }
                }
            }

            disconnectReason?.let { reason -> item { Text("Last disconnect: $reason", color = AtomosRed, fontSize = 11.sp) } }

        }
    }
}

@Composable
private fun PairStageChip(label: String, active: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (active) Color(0xFF173331) else Color(0xFF171B1D),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (active) AtomosCyan else Color(0xFF2B3032))
    ) {
        Text(label, color = if (active) AtomosCyan else Color(0xFF575D5F), fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 7.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

private val RemoteMint = AtomosCyan // UI 07.3.5: same accent as connected top tabs everywhere
private val RemoteMintDim = Color(0xFF2F8C7C)

private enum class RemotePanel(val title: String, val subtitle: String, val badge: String) {
    MONITOR_ASSIST("Monitor Assist", "Focus, exposure and zoom tools", "MA"),
    SCOPES("Scopes", "Waveform and vectorscope controls", "SC"),
    GUIDES("Grid & Guides", "Grid, frame guides and safe areas", "GD"),
    ANAMORPHIC("Anamorphic", "De-squeeze monitoring ratios", "AN"),
    MONITORING("Monitoring", "Native, Rec.709, HLG, PQ and LUT", "MN"),
    DISPLAY("Display", "Backlight, picture, flip and tally", "DS"),
    CODEC("Record Codec", "Codec profile and record format", "RC"),
    INPUT_FORMAT("Input / Format", "Recorder input and output details", "IF")
}

private enum class RemoteHomeTool(
    val label: String,
    val panel: RemotePanel,
    val iconRes: Int
) {
    MONITOR("MONITOR", RemotePanel.MONITORING, R.drawable.ui_remote_tool_monitor),
    WAVEFORM("WAVEFORM", RemotePanel.SCOPES, R.drawable.ui_remote_tool_waveform),
    FOCUS("FOCUS", RemotePanel.MONITOR_ASSIST, R.drawable.ui_remote_tool_focus),
    ZEBRA("ZEBRA", RemotePanel.MONITOR_ASSIST, R.drawable.ui_remote_tool_zebra),

    FALSE_COLOR("FALSE COLOR", RemotePanel.MONITOR_ASSIST, R.drawable.ui_remote_tool_false_color),
    BLUE_ONLY("BLUE ONLY", RemotePanel.MONITOR_ASSIST, R.drawable.ui_remote_tool_false_color),
    LUT("LUT", RemotePanel.MONITORING, R.drawable.ui_remote_tool_lut),
    FRAME_GUIDE("FRAME GUIDE", RemotePanel.GUIDES, R.drawable.ui_remote_tool_frame_guide),
    GRID("GRID", RemotePanel.GUIDES, R.drawable.ui_remote_tool_grid),

    ZOOM("ZOOM", RemotePanel.MONITOR_ASSIST, R.drawable.ui_remote_tool_zoom),
    SAFE_AREA("SAFE AREA", RemotePanel.GUIDES, R.drawable.ui_remote_tool_safe_area),
    DISPLAY("DISPLAY", RemotePanel.DISPLAY, R.drawable.ui_remote_tool_display),
    CODEC("CODEC", RemotePanel.CODEC, R.drawable.ui_remote_tool_codec),
    ANAMORPHIC("ANAMORPHIC", RemotePanel.ANAMORPHIC, R.drawable.ui_remote_tool_anamorphic)
}

private fun RemoteHomeTool.sheetTitle(): String = when (this) {
    RemoteHomeTool.MONITOR -> "Monitor"
    RemoteHomeTool.WAVEFORM -> "Waveform"
    RemoteHomeTool.FOCUS -> "Focus Assist"
    RemoteHomeTool.ZEBRA -> "Zebra"
    RemoteHomeTool.FALSE_COLOR -> "False Color"
    RemoteHomeTool.BLUE_ONLY -> "Blue Only"
    RemoteHomeTool.LUT -> "LUT"
    RemoteHomeTool.FRAME_GUIDE -> "Frame Guide"
    RemoteHomeTool.GRID -> "Grid"
    RemoteHomeTool.ZOOM -> "Zoom"
    RemoteHomeTool.SAFE_AREA -> "Safe Area"
    RemoteHomeTool.DISPLAY -> "Display"
    RemoteHomeTool.CODEC -> "Codec"
    RemoteHomeTool.ANAMORPHIC -> "Anamorphic"
}

// AGENT7 20.03.4.11 PERSISTENT CONNECTED SCREEN
private enum class ConnectedTopTab(val label: String) {
    REMOTE("Remote"),
    INPUT("Input"),
    MONITOR("Monitor"),
    AUDIO("Audio"),
    OUTPUT("Output"),
    MEDIA("Media"),
    SETTINGS("Settings")
}

private val LocalConnectedModuleEmbedded = compositionLocalOf { false }

@Composable
private fun ConnectedTopTabs(
    selected: ConnectedTopTab,
    onSelected: (ConnectedTopTab) -> Unit
) {
    val firstRow = listOf(
        ConnectedTopTab.REMOTE,
        ConnectedTopTab.INPUT,
        ConnectedTopTab.MONITOR,
        ConnectedTopTab.AUDIO
    )
    val secondRow = listOf(
        ConnectedTopTab.MEDIA,
        ConnectedTopTab.OUTPUT,
        ConnectedTopTab.SETTINGS
    )

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF030B0E))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(firstRow, secondRow).forEach { rowTabs ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                rowTabs.forEach { tab ->
                    val active = selected == tab
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clickable { onSelected(tab) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (active) Color(0xFF0A2B30) else Color(0xFF081115),
                        border = androidx.compose.foundation.BorderStroke(
                            if (active) 1.5.dp else 1.dp,
                            if (active) AtomosCyan else Color(0xFF1F343A)
                        )
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                tab.label.uppercase(),
                                color = if (active) Color.White else Color(0xFF839499),
                                fontSize = 8.2.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                repeat(4 - rowTabs.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteScreen(vm: AtomosViewModel) {
    val selected by vm.selected.collectAsState()
    // WL-1: use the existing safe LAN discovery stack while Remote is open.
    val networkStatus by vm.networkStatus.collectAsState()
    val lanDevices by vm.lanDevices.collectAsState()
    val networkServices by vm.networkServices.collectAsState()
    val networkProbeResults by vm.networkProbeResults.collectAsState()
    val gattState by vm.gattState.collectAsState()
    val tcsPairing by vm.tcsPairingState.collectAsState()
    val remote by vm.tcsRemoteState.collectAsState()
    val stateSnapshot by vm.remoteStateSnapshot.collectAsState() // AppPolling diagnostic only
    val inputCodecCollector by vm.inputCodecCollector.collectAsState()

    val monitorZoom by vm.monitorZoom.collectAsState()
    val monitorZoomLevel by vm.monitorZoomLevel.collectAsState()
    val monitorFocusAssist by vm.monitorFocusAssist.collectAsState()
    val monitorZebra by vm.monitorZebra.collectAsState()
    val monitorFalseColor by vm.monitorFalseColor.collectAsState()
    val monitorBlueOnly by vm.monitorBlueOnly.collectAsState()
    val zoomSupportedLevels by vm.zoomSupportedLevels.collectAsState()
    val zoomCapabilitiesKnown by vm.zoomCapabilitiesKnown.collectAsState()
    val monitorZoomCore by vm.monitorZoomCore.collectAsState()
    val monitorZoomViewport by vm.monitorZoomViewport.collectAsState()
    val monitorFocusAssistState by vm.monitorFocusAssistState.collectAsState()
    val monitorZebraState by vm.monitorZebraState.collectAsState()
    val monitorAssistEffects by vm.monitorAssistEffects.collectAsState()
    val monitorScopeState by vm.monitorScopeState.collectAsState()
    val monitorGuideState by vm.monitorGuideState.collectAsState()
    val monitorAnamorphicState by vm.monitorAnamorphicState.collectAsState()
    val monitorLutState by vm.monitorLutState.collectAsState()
    val monitorDisplayState by vm.monitorDisplayState.collectAsState()
    val zoomBoxState by vm.zoomBoxState.collectAsState()

    val scopeEnabled by vm.scopeEnabled.collectAsState()
    val scopeMode by vm.scopeMode.collectAsState()
    val scopeSize by vm.scopeSize.collectAsState()

    val frameGuideEnabled by vm.frameGuideEnabled.collectAsState()
    val frameGridEnabled by vm.frameGridEnabled.collectAsState()
    val safeAreaMode by vm.safeAreaMode.collectAsState()
    val anamorphicMode by vm.anamorphicMode.collectAsState()
    val monitorDisplayMode by vm.monitorDisplayMode.collectAsState()
    val monitorCore by vm.monitorCoreSnapshot.collectAsState()
    val codecProfile by vm.codecProfile.collectAsState()
    val codecBitDepth by vm.codecBitDepth.collectAsState()
    val codecHardwareProfile by vm.codecHardwareProfile.collectAsState()
    val inputFormatInfo by vm.inputFormatInfo.collectAsState()
    val inputCodecId by vm.inputCodecId.collectAsState()
    val inputCameraId by vm.inputCameraId.collectAsState()
    val inputGammaId by vm.inputGammaId.collectAsState()
    val inputGamutId by vm.inputGamutId.collectAsState()
    val outputInfo by vm.outputInfo.collectAsState()
    val outputHdrTypeId by vm.outputHdrTypeId.collectAsState()
    val outputGamutId by vm.outputGamutId.collectAsState()
    val inputOutputRawHex by vm.inputOutputRawHex.collectAsState()
    val connectedRecorderName by vm.connectedRecorderName.collectAsState()
    val commandStatus by vm.commandStatus.collectAsState()
    val transportUi by vm.remoteTransportUiState.collectAsState()

    // NDI-06C: Remote-screen-only discovery. This NEVER transmits recorder TX controls
    // or opens a media port. It reuses the proven 06A _ndi._tcp NSD implementation.
    val ndi06Context = LocalContext.current
    var ndi06Sources by remember(ndi06Context) { mutableStateOf<List<Ndi06DiscoveredSource>>(emptyList()) }
    var ndi06Note by remember(ndi06Context) { mutableStateOf("Waiting for NDI discovery") }
    val ndi06Discovery = remember(ndi06Context) {
        NdiDiscovery(
            ndi06Context,
            onLog = { message -> ndi06Note = message },
            onUpdate = { found -> ndi06Sources = found }
        )
    }
    DisposableEffect(ndi06Discovery) {
        ndi06Discovery.start()
        onDispose { ndi06Discovery.stop() }
    }
    // V16.1: NDI discovery is multi-source. Do NOT auto-attach a SHOGUN source
    // when the Bluetooth-selected device is a NINJA or another recorder.
    // Stable explicit source binding is saved under the selected Bluetooth MAC.
    val ndi16Prefs = remember(ndi06Context) {
        ndi06Context.getSharedPreferences("ndi16_local_endpoint", android.content.Context.MODE_PRIVATE)
    }
    val ndi161BluetoothKey = selected?.address ?: "not_paired"
    val ndi161BindingKey = "ndi161_source_for_" + ndi161BluetoothKey
    var ndi161UserBoundName by remember(ndi161BluetoothKey) {
        mutableStateOf(ndi16Prefs.getString(ndi161BindingKey, null))
    }
    // Never let a stale GATT display name from the previous recorder auto-bind
    // the newly selected Bluetooth device to that previous sender.
    val ndi161DeviceName = selected?.name?.trim()?.takeIf { it.isNotBlank() }
        ?: connectedRecorderName.orEmpty()
    val ndi161AutoMatches = if (selected == null) emptyList() else ndi06Sources.filter {
        ndi161LikelySameRecorder(ndi161DeviceName, it.name)
    }
    val ndi161AutoName = ndi161AutoMatches.singleOrNull()?.name
    // A uniquely identified auto-source is retained only within THIS BLE
    // recorder session, to survive transient mDNS service-loss events.
    var ndi161SessionAutoName by remember(ndi161BluetoothKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(ndi161AutoName, ndi161BluetoothKey) {
        if (ndi161AutoName != null && ndi161SessionAutoName == null) {
            ndi161SessionAutoName = ndi161AutoName
            Log.i("AtomNdi161", "NDI161_AUTO_PIN ble=$ndi161BluetoothKey source=$ndi161AutoName")
        }
    }
    // Explicit manual mapping always wins. It persists through brief mDNS gaps.
    // Auto-matching is permitted only if ONE NDI sender matches recorder identity.
    val ndi161ChosenName = ndi161UserBoundName ?: ndi161SessionAutoName ?: ndi161AutoName
    val ndi06Source = ndi06Sources.singleOrNull { it.name == ndi161ChosenName }
    var ndi161BindingNote by remember(ndi161BluetoothKey) { mutableStateOf("") }
    LaunchedEffect(ndi161ChosenName, ndi161BluetoothKey) {
        Log.i("AtomNdi161", "NDI161_BINDING ble=$ndi161BluetoothKey name=${ndi161ChosenName ?: "none"} auto=${ndi161UserBoundName == null && ndi161AutoName != null} candidates=${ndi06Sources.size}")
    }
    // Keep existing V16 hotspot preference identity keys so user's prior
    // manual local-IP configuration is preserved, but NEVER trust V16's
    // automatically cached SHOGUN source name as a recorder identity.
    val ndi16IdentityKey = "verified_shogun_" + ndi161BluetoothKey
    // Old V16 auto-cached IP/source may belong to Shogun from a previous
    // session. Preserve the address but require an explicit V16.1 confirmation
    // before restoring its DIRECT mode as a recorder identity.
    val ndi161DirectAuthorizedKey = "ndi161_direct_authorized_" + ndi161BluetoothKey
    var ndi16DirectMode by remember(ndi161BluetoothKey) {
        mutableStateOf(ndi16Prefs.getBoolean("direct_" + ndi16IdentityKey, false) &&
            ndi16Prefs.getBoolean(ndi161DirectAuthorizedKey, false))
    }
    var ndi16SavedHost by remember(ndi161BluetoothKey) {
        mutableStateOf(ndi16Prefs.getString("host_" + ndi16IdentityKey, "") ?: "")
    }
    var ndi16SavedPort by remember(ndi161BluetoothKey) {
        mutableIntStateOf(ndi16Prefs.getInt("port_" + ndi16IdentityKey, 5961))
    }
    var ndi16DraftHost by remember(ndi161BluetoothKey) { mutableStateOf(ndi16SavedHost) }
    var ndi16DraftPort by remember(ndi161BluetoothKey) { mutableStateOf(ndi16SavedPort.toString()) }
    var ndi16InputMessage by remember(ndi161BluetoothKey) { mutableStateOf("") }
    val ndi16DirectValid = ndi16LocalIpv4(ndi16SavedHost) && ndi16SavedPort in 1..65535
    val ndi16SelectedSourceName = if (ndi16DirectMode && ndi16DirectValid) {
        val label = ndi161ChosenName ?: "Recorder NDI (direct local IP)"
        "$label##NDI16_IP=$ndi16SavedHost:$ndi16SavedPort"
    } else if (ndi16DirectMode) null else ndi161ChosenName
    LaunchedEffect(ndi16DirectMode, ndi16SavedHost, ndi16SavedPort) {
        Log.i("AtomNdi16", "NDI16_MODE direct=$ndi16DirectMode host=${if (ndi16DirectMode) ndi16SavedHost else "auto"} port=$ndi16SavedPort valid=$ndi16DirectValid")
    }
    // V14: local receiver ON/OFF only. Does not change Shogun NDI TX or license.
    var ndi14PreviewActive by remember(selected?.address) { mutableStateOf(true) }
    // NDI15 is Android receive/post-decode resolution only; never alters recorder TX.
    val ndi15Prefs = remember(ndi06Context) {
        ndi06Context.getSharedPreferences("ndi15_android_output", android.content.Context.MODE_PRIVATE)
    }
    var ndi15Height by remember(ndi15Prefs) {
        val stored = ndi15Prefs.getInt("output_height", 720)
        mutableStateOf(if (stored in setOf(230, 480, 720, 1080)) stored else 720)
    }
    var ndi15SettingsOpen by remember { mutableStateOf(false) }
    // NDI16.2 UI-only: expandable automatically refreshed source picker and
    // advanced local-IP controls. The underlying discovery starts on Remote entry.
    var ndi162SourcePickerOpen by remember(ndi161BluetoothKey) { mutableStateOf(false) }
    var ndi162AdvancedOpen by remember { mutableStateOf(false) }
    var ndi163ResolutionMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(ndi162SourcePickerOpen, ndi161BluetoothKey) {
        if (ndi162SourcePickerOpen && ndi06Sources.isEmpty()) {
            // An empty picker refreshes existing NSD discovery automatically.
            // Do not restart discovery while a sender is available/streaming.
            ndi06Discovery.stop()
            ndi06Discovery.start()
            Log.i("AtomNdi162", "NDI162_PICKER_AUTO_SCAN ble=$ndi161BluetoothKey")
        }
    }
    LaunchedEffect(ndi15Height) {
        val applied = Ndi07Native.setAndroidOutputHeight(ndi15Height)
        Log.i("AtomNdi15", "NDI15_ANDROID_PROCESS_HEIGHT height=$ndi15Height applied=$applied sender_unchanged=true")
    }


    DisposableEffect(Unit) {
        vm.startNetworkDiscovery()
        onDispose { vm.stopNetworkDiscovery() }
    }

    val transientPacketLinkPhase = remote.phase in setOf(
        "sending", "awaiting-response", "awaiting-credit", "transport-ready", "bootstrapping"
    )
    val rawTransportReady = tcsPairing.acceptWritten && remote.available && (remote.linked || transientPacketLinkPhase)
    val hardDisconnected = gattState == "idle" || gattState == "disconnected" ||
        gattState.startsWith("error:") || gattState.startsWith("service-error:")
    // 19.11.10 session stability: a rejected/unsupported feature command is not a BLE
    // disconnect. Once the recorder Remote transport has been proven ready, keep the
    // UI session alive through command-level RemoteApi errors and state refreshes.
    // Only pairing loss or an actual GATT disconnect/error clears the ready latch.
    var sessionReadyLatched by remember(selected?.address) { mutableStateOf(false) }
    LaunchedEffect(rawTransportReady, tcsPairing.acceptWritten, hardDisconnected) {
        if (rawTransportReady) sessionReadyLatched = true
        if (!tcsPairing.acceptWritten || hardDisconnected) sessionReadyLatched = false
    }
    val transportReady = rawTransportReady || (sessionReadyLatched && tcsPairing.acceptWritten && !hardDisconnected)
    val commandInFlight = remote.phase in setOf(
        "queued", "sending", "requesting-credit", "awaiting-response", "awaiting-credit", "bootstrapping"
    )
    val transportUiLocked = transportUi.locked

    // AGENT7 20.03.6.2: after Playback -> Record mode prep is confirmed,
    // keep the UI quiet briefly before allowing Record.Start.
    val remoteModeSettleGuard = remember { RemoteModeSettleGuard() }
    var recordModeSettling by remember { mutableStateOf(false) }

    LaunchedEffect(transportUi.pendingAction, transportUi.mode) {
        if (
            remoteModeSettleGuard.observe(
                pendingAction = transportUi.pendingAction,
                mode = transportUi.mode
            )
        ) {
            recordModeSettling = true
            Log.d(
                "AtomRemoteUI",
                "TCS20.03.6.2 RECORD MODE SETTLE start ms=1800"
            )
            kotlinx.coroutines.delay(1800)
            recordModeSettling = false
            Log.d(
                "AtomRemoteUI",
                "TCS20.03.6.2 RECORD MODE SETTLE complete"
            )
        }
    }

    val transportButtonsEnabled =
        transportReady && !commandInFlight && !transportUiLocked && !recordModeSettling
    val hasSessionError = hardDisconnected

    LaunchedEffect(
        remote.phase,
        remote.lastCommand,
        remote.lastError,
        gattState
    ) {
        vm.observeRemoteTransportUiState()
    }

    LaunchedEffect(transportUi.generation, transportUi.pendingAction) {
        val generation = transportUi.generation
        if (transportUi.pendingAction != null) {
            kotlinx.coroutines.delay(6000)
            vm.timeoutRemoteTransportUiAction(generation)
        }
    }

    val recorderName = connectedRecorderName?.trim()?.takeIf {
        it.length > 3 && !it.equals("Unknown", true) &&
            !it.equals("PC", true) && !it.equals("AG2", true)
    } ?: selected?.let { vm.displayNameFor(it) }
        ?.takeIf { it.isNotBlank() && !it.equals("Unknown", true) }
        ?: "ATOMOS Recorder"
    val recorderAddress = selected?.let { ad -> recorderModelCode(ad.name)?.let { "Model code: $it | ${ad.address}" } ?: ad.address } ?: "Bluetooth Remote"
    val sameNetworkRecorder = remember(lanDevices) {
        lanDevices.firstOrNull { it.likelyAtomos }
    }
    val localIpv4 = networkStatus.localAddresses.firstOrNull { it.count { ch -> ch == '.' } == 3 }
    val streamProbeTarget = remember(lanDevices, sameNetworkRecorder, localIpv4) {
        sameNetworkRecorder ?: lanDevices.firstOrNull { device ->
            device.host != localIpv4 &&
                !device.host.endsWith(".1") &&
                device.host.count { ch -> ch == '.' } == 3
        }
    }
    val streamProbeTargetConfirmed = streamProbeTarget?.likelyAtomos == true
    val sameNetworkReady = networkStatus.connected && sameNetworkRecorder != null
    val wirelessFoundationStatus = when {
        sameNetworkReady -> "SAME NETWORK RECORDER FOUND"
        streamProbeTarget != null -> "LAN CANDIDATE FOUND"
        networkStatus.connected -> "SAME NETWORK DISCOVERY"
        else -> "WIFI NOT READY"
    }
    val wirelessFoundationDetail = when {
        sameNetworkRecorder != null -> sameNetworkRecorder.host
        streamProbeTarget != null -> streamProbeTarget.host
        networkStatus.connected -> localIpv4 ?: networkStatus.transport
        else -> "Connect phone and recorder to the same Wi-Fi"
    }

    val streamProbePorts = remember { "80,443,554,5960,8000,8080,8081,8554" }
    LaunchedEffect(streamProbeTarget?.host) {
        streamProbeTarget?.host?.let { host ->
            val confidence = if (streamProbeTargetConfirmed) "atomos" else "candidate"
            Log.d("AtomRemoteWL2", "WL22 TARGET host=$host confidence=$confidence evidence=${streamProbeTarget?.evidence}")
            Log.d("AtomRemoteWL2", "WL22 AUTO PROBE host=$host ports=$streamProbePorts")
            vm.probeNetwork(host, streamProbePorts)
        }
    }

    LaunchedEffect(networkProbeResults, streamProbeTarget?.host, networkServices) {
        val host = streamProbeTarget?.host ?: return@LaunchedEffect
        if (networkProbeResults.isNotEmpty()) {
            val open = networkProbeResults.filter { it.reachable }.joinToString(",") { it.port.toString() }
            Log.d("AtomRemoteWL2", "WL2 PROBE RESULT host=$host openPorts=${if (open.isBlank()) "none" else open}")
        }
        val hostServices = networkServices.filter { it.host == host }
        if (hostServices.isNotEmpty()) {
            val svc = hostServices.joinToString("|") { "${it.serviceType}:${it.port ?: 0}" }
            Log.d("AtomRemoteWL2", "WL2 SERVICES host=$host services=$svc")
        }
    }

    val streamCandidateLabel = remember(networkProbeResults, networkServices, sameNetworkRecorder?.host) {
        val host = streamProbeTarget?.host
        val hostServices = networkServices.filter { it.host == host }
        when {
            hostServices.any { it.serviceType.contains("_ndi._tcp.", ignoreCase = true) } -> "NDI CANDIDATE"
            hostServices.any { it.serviceType.contains("_rtsp._tcp.", ignoreCase = true) } -> "RTSP CANDIDATE"
            networkProbeResults.any { it.port == 554 && it.reachable } -> "RTSP PORT OPEN"
            networkProbeResults.any { it.port == 8554 && it.reachable } -> "RTSP ALT PORT OPEN"
            networkProbeResults.any { it.reachable } -> "NETWORK SERVICE FOUND"
            sameNetworkReady && networkProbeResults.isNotEmpty() -> "NO STREAM PORT FOUND"
            sameNetworkReady -> "PROBING STREAM PORTS"
            else -> "WAITING FOR SAME NETWORK"
        }
    }

    val streamProbeDetail = remember(networkProbeResults, networkServices, sameNetworkRecorder?.host) {
        val host = streamProbeTarget?.host
        val openPorts = networkProbeResults.filter { it.reachable }.map { it.port }.sorted()
        val hostServices = networkServices.filter { it.host == host }.map { it.serviceType }.distinct()
        when {
            hostServices.isNotEmpty() -> hostServices.joinToString(" Â· ")
            openPorts.isNotEmpty() -> "Open: " + openPorts.joinToString(", ")
            networkProbeResults.isNotEmpty() -> "No tested stream/service ports open"
            host != null -> "Testing $streamProbePorts"
            else -> "Same-network recorder not identified yet"
        }
    }
    val pairingCode = tcsPairing.pairingCode ?: selected?.advertisedPairingId
    val connectionTitle = when {
        hasSessionError -> "DISCONNECTED"
        transportReady -> "CONNECTED"
        else -> "CONNECTING"
    }
    val connectionSubtitle = when {
        hasSessionError -> "Recorder connection ended"
        transportReady -> "Remote control is ready"
        else -> "Preparing recorder controls"
    }

    var showDisconnectConfirmation by remember { mutableStateOf(false) }
    BackHandler(enabled = true) { showDisconnectConfirmation = true }

    var activePanel by remember { mutableStateOf<RemotePanel?>(null) }
    var activeTool by remember { mutableStateOf<RemoteHomeTool?>(null) }
    // 20.03.4.11: Connected modules are panels inside one persistent screen.
    // Changing tabs must not call vm.go(), restart GATT, or start a hardware refresh.
    var connectedTab by remember(selected?.address) { mutableStateOf(ConnectedTopTab.REMOTE) }

    // AGENT7 20.03.6.2: frozen-Gatt post-pair recovery.
    // If a freshly re-paired session reaches the known cxtic2/cxtic4 readability
    // preflight error, retry the already-saved trusted session once.
    val pairingLinkRecoveryGuard = remember(selected?.address) {
        PairingLinkRecoveryGuard()
    }
    LaunchedEffect(remote.lastError, selected?.address) {
        if (
            selected != null &&
            pairingLinkRecoveryGuard.shouldRecover(remote.lastError)
        ) {
            Log.d(
                "AtomRemoteUI",
                "TCS20.03.6.2 PAIR LINK RECOVERY known-preflight-error retry=once"
            )
            kotlinx.coroutines.delay(500)
            vm.recoverRemoteSessionFromUi()
        }
    }

    val remoteListState = rememberLazyListState()
    val density = LocalDensity.current
    val miniThresholdPx = with(density) { 120.dp.roundToPx() }
    val ndiMiniPreview by remember(remoteListState, miniThresholdPx) {
        derivedStateOf {
            remoteListState.firstVisibleItemIndex > 0 ||
                remoteListState.firstVisibleItemScrollOffset > miniThresholdPx
        }
    }
    LaunchedEffect(transportReady, selected?.address) {
        if (!transportReady) {
            vm.invalidateMonitorSessionCache("transport-not-ready")
        }
    }

    LaunchedEffect(activePanel, connectedTab, transportReady, selected?.address) {
        if (activePanel != null) {
            // Wait for the expanded tool panel item to enter the LazyColumn, then
            // bring it into view automatically so the user never has to hunt for it.
            kotlinx.coroutines.delay(120)
            remoteListState.animateScrollToItem(4)
        }

        val monitorVisible =
            connectedTab == ConnectedTopTab.MONITOR ||
                activePanel == RemotePanel.MONITOR_ASSIST ||
                activePanel == RemotePanel.MONITORING ||
                activePanel == RemotePanel.GUIDES ||
                activePanel == RemotePanel.SCOPES ||
                activePanel == RemotePanel.DISPLAY

        if (transportReady && monitorVisible) {
            vm.ensureMonitorSessionSnapshot(
                reason = when {
                    connectedTab == ConnectedTopTab.MONITOR -> "monitor-tab"
                    activePanel == RemotePanel.MONITOR_ASSIST -> "monitor-assist-panel"
                    activePanel == RemotePanel.GUIDES -> "monitor-guides-panel"
                    activePanel == RemotePanel.SCOPES -> "monitor-scopes-panel"
                    activePanel == RemotePanel.DISPLAY -> "monitor-display-panel"
                    else -> "monitor-mode-panel"
                }
            )
            if (connectedTab == ConnectedTopTab.MONITOR && monitorLutState.currentSlot == null && monitorLutState.pendingKey == null) {
                kotlinx.coroutines.delay(650L)
                vm.refreshLutState()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07090A))
            .navigationBarsPadding()
    ) {
        AppHeader(
            title = recorderName,
            subtitle = if (transportReady) "Connected" else "Connecting",
            onBack = { showDisconnectConfirmation = true }
        )

        ConnectedTopTabs(
            selected = connectedTab,
            onSelected = { target ->
                if (target != connectedTab) {
                    Log.d("AtomRemoteUI", "TCS20.03.5.3 TAB ${connectedTab.name} -> ${target.name}; persistentSession=true hardwareAction=none")
                    connectedTab = target
                    activePanel = null
                }
            }
        )

        if (connectedTab == ConnectedTopTab.REMOTE) {
        LaunchedEffect(monitorDisplayMode, transportReady) {
            if (transportReady && monitorDisplayMode == 5) {
                kotlinx.coroutines.delay(350L)
                vm.refreshLutState()
            }
        }

        LaunchedEffect(activePanel, transportReady) {
        if (!transportReady) return@LaunchedEffect

        when (activePanel) {
            RemotePanel.MONITOR_ASSIST -> {
                // Auto-hydrate recorder-backed Monitor Assist values on entry.
                kotlinx.coroutines.delay(350L)
                vm.refreshFocusAssistState()
                kotlinx.coroutines.delay(650L)
                vm.refreshMonitorAssistEffects()
            }

            RemotePanel.SCOPES -> {
                // Auto-hydrate the complete Scope snapshot on entry.
                kotlinx.coroutines.delay(250L)
                vm.refreshScopeCore()
            }

            RemotePanel.GUIDES -> {
                kotlinx.coroutines.delay(250L)
                vm.refreshGuidesState()
                kotlinx.coroutines.delay(500L)
                vm.refreshSafeAreaState()
            }

            RemotePanel.ANAMORPHIC -> {
                kotlinx.coroutines.delay(250L)
                vm.refreshAnamorphicMode()
            }

            else -> Unit
        }
    }

    Box(Modifier.weight(1f).fillMaxWidth()) {
    LazyColumn(
            state = remoteListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // NDI-06C: exact same Remote tab and preview container. TX control
            // remains locked until verified readback and official setter payload.
            item {
                // Same LazyColumn item as the original hero, so existing scroll targets
                // and tool-sheet indices do not change.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteWirelessHomeHero(
                    recorderName = recorderName,
                    recorderAddress = recorderAddress,
                    transportReady = transportReady,
                    inputLabel = inputFormatInfo ?: "HDMI",
                    codecLabel = codecSummary(codecProfile),
                    monitorLabel = monitorModeSummary(monitorDisplayMode),
                    pairingCode = pairingCode,
                    wirelessStatus = wirelessFoundationStatus,
                    wirelessDetail = wirelessFoundationDetail,
                    sameNetworkReady = sameNetworkReady,
                    networkTransport = networkStatus.transport,
                    networkHost = streamProbeTarget?.host,
                    networkHostConfirmed = streamProbeTargetConfirmed,
                    bleLinked = transportReady,
                    streamCandidate = streamCandidateLabel,
                    ndi06Source = ndi06Source
                    )
                }
            }

            val visibleRemoteMessage = when {
                recordModeSettling ->
                    "Record mode confirmed. Waiting briefly for PacketLink to settle before Record Start."
                else -> transportUi.error
                    ?: transportUi.message
                    ?: commandStatus
            }

            visibleRemoteMessage?.takeIf { it.isNotBlank() }?.let { message ->
                item {
                    RemoteTransportStatusBanner(
                        message = message,
                        isError = transportUi.error != null ||
                            message.contains("error", ignoreCase = true) ||
                            message.contains("failed", ignoreCase = true) ||
                            message.contains("could not", ignoreCase = true) ||
                            message.contains("not confirmed", ignoreCase = true) ||
                            message.contains("ended", ignoreCase = true),
                        showRecover = transportUi.recoveryRequired,
                        onRecover = vm::recoverRemoteSessionFromUi
                    )
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("TOOLS", color = Color(0xFF8B9497), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (transportReady) {
                            "READY" + if (inputCodecCollector.codecVerified) " | CODEC OK" else ""
                        } else "CONNECTING",
                        color = if (transportReady) RemoteMint else Color(0xFF6F787B),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "AUTO",
                        color = Color(0xFF6F787B),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            item {
                RemoteHomeToolsGrid(
                    activeTool = activeTool,
                    enabled = transportReady,
                    ndi15Height = ndi15Height,
                    onPreviewSettings = {
                        activeTool = null
                        activePanel = null
                        ndi15SettingsOpen = true
                    },
                    onTool = { tool ->
                        ndi15SettingsOpen = false
                        activeTool = tool
                        activePanel = tool.panel
                    }
                )
            }




            if (hasSessionError) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF241416))
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Recorder disconnected", color = AtomosRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("The Bluetooth Remote session has ended.", color = Color(0xFFC8B9BB), fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { vm.go(AtomosViewModel.Screen.HOME) }, modifier = Modifier.fillMaxWidth()) { Text("GO HOME") }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(4.dp)) }
        }

        // NDI-07 V9: one persistent video view, outside LazyColumn.
        // Scroll only changes its bounds. It does NOT dispose/start/stop native NDI.
        // Leaving the REMOTE tab disposes the view and releases the receiver.
        if (ndi16SelectedSourceName != null && Ndi07Native.available) {
            val previewBounds = if (ndiMiniPreview) {
                Modifier.align(Alignment.TopEnd)
                    .padding(top = 50.dp, end = 14.dp)
                    .width(188.dp)
                    .height(108.dp)
            } else {
                Modifier.align(Alignment.TopCenter)
                    .padding(top = 20.dp, start = 26.dp, end = 26.dp)
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            }
            Box(
                modifier = previewBounds
                    .zIndex(5f)
                    .background(Color.Black, RoundedCornerShape(12.dp))
                    .border(1.dp, AtomosCyan.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
            ) {
                Ndi07InlinePreview(
                    sourceName = ndi16SelectedSourceName,
                    enabled = ndi14PreviewActive,
                    modifier = Modifier.fillMaxSize()
                )
                if (ndiMiniPreview) {
                    Text(
                        "RECORDER NDI",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomStart)
                            .padding(6.dp)
                            .background(Color(0xCC031513), RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }

    }

            // UI 07.3.4: transport stays visible, remains above the gesture bar,
            // and is rendered as a single horizontal icon dock.
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF02090B))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF06110F)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, RemoteMint.copy(alpha = 0.70f))
                ) {
                    val startUiAction: (
                        action: String,
                        label: String,
                        expectedLastCommand: String,
                        dispatch: () -> Boolean
                    ) -> Unit = { action, label, expectedLastCommand, dispatch ->
                        val generation = vm.beginRemoteTransportUiAction(
                            action = action,
                            label = label,
                            expectedLastCommand = expectedLastCommand
                        )
                        if (generation != null) {
                            val accepted = dispatch()
                            if (!accepted) {
                                vm.rejectRemoteTransportUiAction("$label could not be queued.")
                            }
                        }
                    }

                    val recordPending = transportUi.pendingAction in setOf(
                        "record-start",
                        "return-record-mode"
                    )
                    val recordLabel = when (transportUi.pendingAction) {
                        "return-record-mode" -> "RETURNING..."
                        "record-start" -> "RECORDING..."
                        else -> "RECORD"
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RemoteTransportDockButton(
                            label = recordLabel,
                            kind = "record",
                            enabled = transportButtonsEnabled,
                            accent = AtomosRed,
                            loading = recordPending,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (transportUi.mode == RemoteTransportUiController.Mode.PLAYBACK) {
                                startUiAction(
                                    "return-record-mode",
                                    "Return to Record mode",
                                    "Prepare Record Mode"
                                ) {
                                    vm.returnToRecordModeOnlyFromUi()
                                }
                            } else {
                                startUiAction(
                                    "record-start",
                                    "Record Start",
                                    "Record Start"
                                ) {
                                    vm.sendRecordStartConfirmedModeFromUi()
                                }
                            }
                        }

                        RemoteTransportDivider()

                        RemoteTransportDockButton(
                            label = if (transportUi.pendingAction == "record-stop") "STOPPING..." else "STOP",
                            kind = "stop",
                            enabled = transportButtonsEnabled,
                            accent = Color(0xFFE7EAEB),
                            loading = transportUi.pendingAction == "record-stop",
                            modifier = Modifier.weight(1f)
                        ) {
                            startUiAction("record-stop", "Record Stop", "Record Stop") {
                                vm.sendRemoteTransportFromUi("record-stop")
                            }
                        }

                        RemoteTransportDivider()

                        RemoteTransportDockButton(
                            label = if (transportUi.pendingAction == "play") "PLAYING..." else "PLAY",
                            kind = "play",
                            enabled = transportButtonsEnabled,
                            accent = Color.White,
                            loading = transportUi.pendingAction == "play",
                            modifier = Modifier.weight(1f)
                        ) {
                            startUiAction("play", "Play", "Play") {
                                vm.sendRemoteTransportFromUi("play")
                            }
                        }

                        RemoteTransportDivider()

                        RemoteTransportDockButton(
                            label = if (transportUi.pendingAction == "pause") "PAUSING..." else "PAUSE",
                            kind = "pause",
                            enabled = transportButtonsEnabled,
                            accent = Color.White,
                            loading = transportUi.pendingAction == "pause",
                            modifier = Modifier.weight(1f)
                        ) {
                            startUiAction("pause", "Pause", "Pause") {
                                vm.sendRemoteTransportFromUi("pause")
                            }
                        }
                    }
                }
            }

            // NDI16.2: all NDI receiver settings live in a single organized sheet.
            // The BLE-recorded sender binding is applied automatically on reconnect;
            // the picker is for choosing/repairing an ambiguous mapping once.
            if (ndi15SettingsOpen) {
                ModalBottomSheet(
                    onDismissRequest = { ndi15SettingsOpen = false },
                    containerColor = Color(0xFF071311),
                    contentColor = Color.White,
                    dragHandle = {
                        Box(Modifier.padding(top = 10.dp, bottom = 8.dp)
                            .size(width = 54.dp, height = 4.dp)
                            .background(Color(0xFF607C74), RoundedCornerShape(99.dp)))
                    }
                ) {
                    Column(
                        Modifier.fillMaxWidth().fillMaxHeight(0.88f)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 18.dp, end = 18.dp, bottom = 34.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF11312B)) {
                                Icon(painterResource(R.drawable.ui_remote_tool_live_preview),
                                    contentDescription = null, tint = AtomosCyan,
                                    modifier = Modifier.padding(9.dp).size(25.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Live Preview", color = Color.White,
                                    fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text("Android NDI receiver", color = Color(0xFF91AAA2), fontSize = 11.sp)
                            }
                            TextButton(onClick = { ndi15SettingsOpen = false }) {
                                Text("DONE", color = AtomosCyan, fontWeight = FontWeight.Bold)
                            }
                        }

                        Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF0D201D),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF284940))) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("NDI Preview", fontSize = 14.sp, color = Color.White,
                                        fontWeight = FontWeight.Bold)
                                    Text(if (ndi14PreviewActive) "Receiver on" else "Receiver off",
                                        color = if (ndi14PreviewActive) AtomosCyan else Color(0xFF91AAA2),
                                        fontSize = 11.sp)
                                }
                                Switch(checked = ndi14PreviewActive, onCheckedChange = { enabled ->
                                    ndi14PreviewActive = enabled
                                    Log.i("AtomNdi162", "NDI162_PREVIEW_TOGGLE enabled=$enabled receiver_only=true")
                                })
                            }
                        }

                        // V16.3: compact dropdown anchored to the current recorder.
                        // Discovery and per-BLE binding are unchanged from V16.2.
                        Text("NDI SENDERS", color = AtomosCyan,
                            fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val menuWidth = maxWidth
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    ndi162SourcePickerOpen = !ndi162SourcePickerOpen
                                    ndi163ResolutionMenuOpen = false
                                    Log.i("AtomNdi162", "NDI162_PICKER_OPEN=${ndi162SourcePickerOpen} source=${ndi161ChosenName ?: "none"}")
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF0D2422),
                                border = androidx.compose.foundation.BorderStroke(1.dp,
                                    if (ndi161ChosenName != null) AtomosCyan.copy(alpha = 0.60f) else Color(0xFF345B53))
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(if (ndi16DirectMode && ndi16DirectValid) "Hotspot Direct IP" else
                                            ndi06Source?.displayName ?: ndi161ChosenName ?: "Select NDI sender",
                                            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(when {
                                            ndi16DirectMode && ndi16DirectValid -> "${ndi16SavedHost}:${ndi16SavedPort} • Manual endpoint"
                                            ndi161UserBoundName != null -> "Saved for this Bluetooth recorder"
                                            ndi161AutoName != null -> "Automatically matched to connected recorder"
                                            ndi06Sources.isEmpty() -> "Discovering senders automatically..."
                                            else -> "${ndi06Sources.size} available • Choose a sender"
                                        }, color = Color(0xFF9DBDB3), fontSize = 10.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(if (ndi162SourcePickerOpen) "▴" else "▾",
                                        color = AtomosCyan, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            DropdownMenu(
                                expanded = ndi162SourcePickerOpen,
                                onDismissRequest = { ndi162SourcePickerOpen = false },
                                modifier = Modifier.width(menuWidth).heightIn(max = 320.dp)
                                    .background(Color(0xFF0B1D19))
                            ) {
                                if (ndi06Sources.isEmpty()) {
                                    DropdownMenuItem(
                                        text = {
                                            Text("Searching for NDI senders on this network...",
                                                color = Color(0xFFB6CAC2), fontSize = 12.sp)
                                        },
                                        onClick = {}, enabled = false
                                    )
                                }
                                ndi06Sources.sortedWith(compareBy({ it.displayName.lowercase() }, { it.host })).forEach { source ->
                                    // Keep V16.2 duplicate-name guard and exact saved BLE identity.
                                    val nameUnique = ndi06Sources.count { it.name == source.name } == 1
                                    val isCurrent = ndi161ChosenName == source.name && !ndi16DirectMode
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(source.displayName, color = Color.White,
                                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("${source.host}:${source.advertisedPort}" +
                                                    if (!nameUnique) " • Duplicate sender name" else "",
                                                    color = if (nameUnique) Color(0xFF91B2A7) else Color(0xFFE9C17D),
                                                    fontSize = 10.sp)
                                            }
                                        },
                                        trailingIcon = {
                                            if (isCurrent) Text("✓", color = AtomosCyan,
                                                fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        },
                                        enabled = nameUnique && selected != null,
                                        onClick = {
                                            ndi161UserBoundName = source.name
                                            ndi161SessionAutoName = source.name
                                            ndi16Prefs.edit().putString(ndi161BindingKey, source.name).apply()
                                            ndi16DirectMode = false
                                            ndi16Prefs.edit().putBoolean("direct_" + ndi16IdentityKey, false).apply()
                                            ndi161BindingNote = "NDI sender saved for this Bluetooth recorder."
                                            ndi162SourcePickerOpen = false
                                            Log.i("AtomNdi162", "NDI162_SOURCE_BOUND ble=$ndi161BluetoothKey source=${source.name} host=${source.host}")
                                        }
                                    )
                                }
                            }
                        }
                        if (ndi161UserBoundName != null) {
                            TextButton(onClick = {
                                ndi161UserBoundName = null
                                ndi161SessionAutoName = null
                                ndi16Prefs.edit().remove(ndi161BindingKey).apply()
                                ndi161BindingNote = "Saved association cleared; matching will retry automatically."
                                Log.i("AtomNdi162", "NDI162_SOURCE_UNBOUND ble=$ndi161BluetoothKey")
                            }) { Text("Forget saved sender", color = Color(0xFFB5C9BE), fontSize = 11.sp) }
                        }
                        if (selected == null) {
                            Text("Connect a Bluetooth recorder to save its NDI sender.",
                                color = Color(0xFFE9C17D), fontSize = 11.sp)
                        }
                        if (ndi161ChosenName == null && !ndi16DirectMode) {
                            Text("No matching NDI sender yet. Choose one from the dropdown.",
                                color = Color(0xFFE9C17D), fontSize = 11.sp)
                        }
                        if (ndi161BindingNote.isNotBlank()) {
                            Text(ndi161BindingNote, color = Color(0xFF9DDBD4), fontSize = 11.sp)
                        }

                        // V16.3: resolution selection is a compact dropdown, not a radio list.
                        Text("VIDEO RESOLUTION", color = AtomosCyan,
                            fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val menuWidth = maxWidth
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    ndi163ResolutionMenuOpen = !ndi163ResolutionMenuOpen
                                    ndi162SourcePickerOpen = false
                                },
                                shape = RoundedCornerShape(12.dp), color = Color(0xFF0D2422),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF345B53))
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${ndi15Height}p" + if (ndi15Height == 720) "  •  Balanced" else "",
                                            color = Color.White, fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold)
                                        Text("Android decoded-frame processing", color = Color(0xFF9DBDB3), fontSize = 10.sp)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(if (ndi163ResolutionMenuOpen) "▴" else "▾",
                                        color = AtomosCyan, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            DropdownMenu(
                                expanded = ndi163ResolutionMenuOpen,
                                onDismissRequest = { ndi163ResolutionMenuOpen = false },
                                modifier = Modifier.width(menuWidth).background(Color(0xFF0B1D19))
                            ) {
                                listOf(230, 480, 720, 1080).forEach { height ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text("${height}p" + if (height == 720) "  •  Recommended" else "",
                                                    color = Color.White, fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold)
                                                Text(when (height) {
                                                    230 -> "Minimum processing load"
                                                    480 -> "Low resource mode"
                                                    720 -> "Balanced performance and detail"
                                                    else -> "Higher preview detail"
                                                }, color = Color(0xFF9DBDB3), fontSize = 10.sp)
                                            }
                                        },
                                        trailingIcon = {
                                            if (ndi15Height == height) Text("✓", color = AtomosCyan,
                                                fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        },
                                        onClick = {
                                            ndi15Height = height
                                            ndi15Prefs.edit().putInt("output_height", height).apply()
                                            ndi163ResolutionMenuOpen = false
                                            Log.i("AtomNdi162", "NDI162_OUTPUT_HEIGHT=$height android_only=true")
                                        }
                                    )
                                }
                            }
                        }
                        Text("Android processing only. Recorder output remains unchanged.",
                            color = Color(0xFF91AAA2), fontSize = 10.sp)

                        HorizontalDivider(color = Color(0xFF24453F))
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { ndi162AdvancedOpen = !ndi162AdvancedOpen },
                            shape = RoundedCornerShape(11.dp), color = Color(0xFF0E1E1B)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Hotspot / manual IP (advanced)", color = Color(0xFFD9EAE4),
                                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                                    modifier = Modifier.weight(1f))
                                Text(if (ndi162AdvancedOpen) "HIDE" else "OPEN", color = AtomosCyan, fontSize = 11.sp)
                            }
                        }
                        if (ndi162AdvancedOpen) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Hotspot direct local IP", color = Color.White,
                                    fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Switch(checked = ndi16DirectMode, onCheckedChange = { enable ->
                                    if (!enable || ndi16DirectValid) {
                                        ndi16DirectMode = enable
                                        ndi16Prefs.edit().putBoolean("direct_" + ndi16IdentityKey, enable).apply()
                                        ndi16InputMessage = if (enable) "Using saved local IP" else "Automatic NDI discovery"
                                    } else {
                                        ndi16InputMessage = "Enter and apply a valid private/local IPv4 first"
                                    }
                                })
                            }
                            Text("Auto discovery works on normal Wi-Fi and supported hotspots. Manual IP is a fallback when mDNS cannot discover the recorder.",
                                color = Color(0xFF9BB6B1), fontSize = 10.sp)
                            OutlinedTextField(
                                value = ndi16DraftHost, onValueChange = { input ->
                                    if (input.length <= 15) ndi16DraftHost = input.filter { it.isDigit() || it == '.' }
                                }, label = { Text("Recorder private/local IPv4") },
                                placeholder = { Text("10.69.210.243") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = ndi16DraftPort, onValueChange = { input ->
                                    if (input.length <= 5) ndi16DraftPort = input.filter { it.isDigit() }
                                }, label = { Text("NDI service port") },
                                placeholder = { Text("5961") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    val port = ndi16DraftPort.toIntOrNull()
                                    if (!ndi16LocalIpv4(ndi16DraftHost) || port == null || port !in 1..65535) {
                                        ndi16InputMessage = "Invalid private/local IPv4 or port"
                                    } else {
                                        ndi16SavedHost = ndi16DraftHost
                                        ndi16SavedPort = port
                                        ndi16DirectMode = true
                                        ndi16Prefs.edit()
                                            .putString("host_" + ndi16IdentityKey, ndi16SavedHost)
                                            .putInt("port_" + ndi16IdentityKey, ndi16SavedPort)
                                            .putBoolean("direct_" + ndi16IdentityKey, true)
                                            .putBoolean(ndi161DirectAuthorizedKey, true)
                                            .apply()
                                        ndi16InputMessage = "Direct local IP applied"
                                        Log.i("AtomNdi162", "NDI162_DIRECT_IP_APPLIED host=$ndi16SavedHost port=$ndi16SavedPort")
                                    }
                                }) { Text("APPLY IP", color = AtomosCyan) }
                                TextButton(onClick = {
                                    ndi16DirectMode = false
                                    ndi16Prefs.edit().putBoolean("direct_" + ndi16IdentityKey, false).apply()
                                    ndi16InputMessage = "Auto discovery enabled"
                                }) { Text("AUTO", color = AtomosCyan) }
                            }
                            if (ndi16InputMessage.isNotEmpty()) {
                                Text(ndi16InputMessage, color = Color(0xFFE8C17D), fontSize = 11.sp)
                            }
                            Text("Local receiver routing only. Never changes recorder NDI TX or paid activation.",
                                color = Color(0xFF91AAA2), fontSize = 10.sp)
                        }
                    }
                }
            }

            if (activeTool != null && activePanel != null) {
                ModalBottomSheet(
                    onDismissRequest = {
                        activeTool = null
                        activePanel = null
                    },
                    containerColor = Color(0xFF071311),
                    contentColor = Color.White,
                    dragHandle = {
                        Box(
                            Modifier
                                .padding(top = 8.dp, bottom = 7.dp)
                                .size(width = 58.dp, height = 5.dp)
                                .background(Color(0xFF63777C), RoundedCornerShape(99.dp))
                        )
                    }
                ) {
                    val tool = activeTool!!
                    val panel = activePanel!!
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 690.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, bottom = 22.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(tool.iconRes),
                                contentDescription = null,
                                tint = RemoteMint,
                                modifier = Modifier.size(34.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                tool.sheetTitle(),
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                activeTool = null
                                activePanel = null
                            }) {
                                Text("CLOSE", color = RemoteMint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(10.dp))

                        when (tool) {
                            RemoteHomeTool.MONITOR -> RemoteMonitoringCard(
                                enabled = transportReady,
                                mode = monitorDisplayMode,
                                monitorCore = monitorCore,
                                lutState = monitorLutState,
                                onModeChange = vm::setMonitorDisplayMode,
                                onLutRefresh = vm::refreshLutState,
                                onLutSlot = vm::setLutSlot,
                                onLutPreview = vm::setLutPreview,
                                onLutRecord = vm::setLutDuringRecord,
                                onLutOutput = vm::setLutDuringOutput
                            )

                            RemoteHomeTool.WAVEFORM -> RemoteScopesCard(
                                enabled = transportReady,
                                scopeState = monitorScopeState,
                                onRefresh = vm::refreshScopeCore,
                                onEnabledChange = vm::setScopeEnabled,
                                onModeChange = vm::setScopeMode,
                                onSizeChange = vm::setScopeSize,
                                onOpacityChange = vm::setScopeOpacity,
                                onBrightnessChange = vm::setScopeBrightness
                            )

                            RemoteHomeTool.FOCUS -> RemoteFocusOnlyCard(
                                enabled = transportReady,
                                state = monitorFocusAssistState,
                                onRefresh = vm::refreshFocusAssistState,
                                onToggle = { checked -> vm.setMonitorAssistToggle("focus-assist", checked) },
                                onType = vm::setFocusAssistType,
                                onThreshold = vm::setFocusAssistThreshold,
                                onColour = vm::setFocusAssistColour
                            )

                            RemoteHomeTool.ZEBRA -> RemoteZebraOnlyCard(
                                enabled = transportReady,
                                state = monitorZebraState,
                                onRefresh = vm::refreshMonitorAssistEffects,
                                onToggle = { checked -> vm.setMonitorAssistToggle("zebra", checked) },
                                onThreshold = vm::setZebraThreshold
                            )

                            RemoteHomeTool.FALSE_COLOR -> RemoteFalseColorOnlyCard(
                                enabled = transportReady,
                                state = monitorAssistEffects,
                                onRefresh = vm::refreshMonitorAssistEffects,
                                onToggle = { checked -> vm.setMonitorAssistToggle("false-color", checked) }
                            )

                            RemoteHomeTool.BLUE_ONLY -> RemoteBlueOnlyCard(
                                enabled = transportReady,
                                state = monitorAssistEffects,
                                onRefresh = vm::refreshMonitorAssistEffects,
                                onToggle = { checked -> vm.setMonitorAssistToggle("blue-only", checked) }
                            )

                            RemoteHomeTool.LUT -> RemoteLutInnerCard(
                                enabled = transportReady,
                                mode = monitorDisplayMode,
                                lutState = monitorLutState,
                                onEnableLutMode = { vm.setMonitorDisplayMode(5) },
                                onRefresh = vm::refreshLutState,
                                onSlot = vm::setLutSlot,
                                onPreview = vm::setLutPreview,
                                onRecord = vm::setLutDuringRecord,
                                onOutput = vm::setLutDuringOutput
                            )

                            RemoteHomeTool.FRAME_GUIDE -> RemoteFrameGuideOnlyCard(
                                enabled = transportReady,
                                guideState = monitorGuideState,
                                onRefresh = vm::refreshGuidesState,
                                onToggle = vm::setFrameGuideEnabled,
                                onFormat = vm::setFrameGuideFormat
                            )

                            RemoteHomeTool.GRID -> RemoteGridOnlyCard(
                                enabled = transportReady,
                                guideState = monitorGuideState,
                                onRefresh = vm::refreshGuidesState,
                                onToggle = vm::setFrameGridEnabled
                            )

                            RemoteHomeTool.ZOOM -> RemoteZoomOnlyCard(
                                enabled = transportReady,
                                zoom = monitorZoom,
                                zoomLevel = monitorZoomLevel,
                                zoomSupportedLevels = zoomSupportedLevels,
                                zoomCapabilitiesKnown = zoomCapabilitiesKnown,
                                zoomCore = monitorZoomCore,
                                zoomViewport = monitorZoomViewport,
                                zoomBoxState = zoomBoxState,
                                onZoomLevelChange = vm::setMonitorZoomLevel,
                                onZoomRefresh = vm::refreshZoomState,
                                onZoomViewportRefresh = vm::refreshZoomViewport,
                                onZoomAnalogSessionChange = vm::setZoomAnalogSession,
                                onPan = vm::panZoomNormalized
                            )

                            RemoteHomeTool.SAFE_AREA -> RemoteSafeAreaOnlyCard(
                                enabled = transportReady,
                                safeAreaMode = safeAreaMode,
                                onRefresh = vm::refreshSafeAreaState,
                                onMode = vm::setSafeAreaMode
                            )

                            RemoteHomeTool.DISPLAY -> RemoteDisplayCard(
                                enabled = transportReady,
                                state = monitorDisplayState,
                                onRefresh = vm::refreshDisplayState,
                                onBacklight = vm::setDisplayBacklight,
                                onFlip = vm::setDisplayFlip,
                                onTally = vm::setDisplayTally,
                                onLift = vm::setDisplayYLift,
                                onGamma = vm::setDisplayYGamma,
                                onGain = vm::setDisplayYGain,
                                onReset = vm::resetDisplayPicture
                            )

                            RemoteHomeTool.CODEC -> RemoteCodecCard(
                                enabled = transportReady,
                                profile = codecProfile,
                                bitDepth = codecBitDepth,
                                confirmedProfile = codecHardwareProfile,
                                onProfileChange = vm::selectCodecProfile,
                                onBitDepthChange = vm::selectCodecBitDepth,
                                onVerify = vm::verifyCodecProfile,
                                onApply = vm::applyCodecProfile,
                                onRestartApply = vm::activateCodecProfile
                            )

                            RemoteHomeTool.ANAMORPHIC -> RemoteAnamorphicCard(
                                transportReady,
                                monitorAnamorphicState,
                                vm::refreshAnamorphicMode,
                                vm::setAnamorphicMode
                            )
                        }
                    }
                }
            }

        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(LocalConnectedModuleEmbedded provides true) {
                    when (connectedTab) {
                        ConnectedTopTab.INPUT -> InputModuleScreen(vm)
                        ConnectedTopTab.MONITOR -> {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                ModuleSessionStrip(
                                    linked = transportReady,
                                    statePages = stateSnapshot.receivedPages,
                                    expectedStatePages = stateSnapshot.expectedPages,
                                    stateComplete = stateSnapshot.cycleComplete
                                )
                                RemoteMonitorAssistCard(
                                    enabled = transportReady, zoom = monitorZoom, zoomLevel = monitorZoomLevel,
                                    focusAssist = monitorFocusAssist, zebra = monitorZebra, falseColor = monitorFalseColor, blueOnly = monitorBlueOnly,
                                    zoomSupportedLevels = zoomSupportedLevels, zoomCapabilitiesKnown = zoomCapabilitiesKnown,
                                zoomCore = monitorZoomCore,
                                    zoomViewport = monitorZoomViewport,
                                    focusState = monitorFocusAssistState,
                                    zebraState = monitorZebraState,
                                    effectsState = monitorAssistEffects,
                                    zoomBoxState = zoomBoxState,
                                    onZoomLevelChange = vm::setMonitorZoomLevel,
                                    onZoomRefresh = vm::refreshZoomState,
                                onZoomViewportRefresh = vm::refreshZoomViewport,
                                    onZoomAnalogSessionChange = vm::setZoomAnalogSession,
                                    onFocusRefresh = vm::refreshFocusAssistState,
                                    onFocusType = vm::setFocusAssistType,
                                    onFocusThreshold = vm::setFocusAssistThreshold,
                                    onFocusColour = vm::setFocusAssistColour,
                                    onZebraThreshold = vm::setZebraThreshold,
                                    onEffectsRefresh = vm::refreshMonitorAssistEffects,
                                    onPan = vm::panZoomNormalized,
                                    onToggle = { control, checked -> vm.setMonitorAssistToggle(control, checked) }
                                )
                                RemoteScopesCard(
                                    enabled = transportReady,
                                    scopeState = monitorScopeState,
                                    onRefresh = vm::refreshScopeCore,
                                    onEnabledChange = vm::setScopeEnabled,
                                    onModeChange = vm::setScopeMode,
                                    onSizeChange = vm::setScopeSize,
                                    onOpacityChange = vm::setScopeOpacity,
                                    onBrightnessChange = vm::setScopeBrightness
                                )
                                RemoteGuidesCard(
                                    enabled = transportReady,
                                    guideState = monitorGuideState,
                                    safeAreaMode = safeAreaMode,
                                    onRefresh = vm::refreshGuidesState,
                                    onFrameGuideChange = vm::setFrameGuideEnabled,
                                    onFrameGuideFormat = vm::setFrameGuideFormat,
                                    onFrameGridChange = vm::setFrameGridEnabled,
                                    onSafeAreaRefresh = vm::refreshSafeAreaState,
                                    onSafeAreaMode = vm::setSafeAreaMode
                                )
                                RemoteAnamorphicCard(transportReady, monitorAnamorphicState, vm::refreshAnamorphicMode, vm::setAnamorphicMode)
                                RemoteMonitoringCard(
                                    enabled = transportReady,
                                    mode = monitorDisplayMode,
                                    monitorCore = monitorCore,
                                    lutState = monitorLutState,
                                    onModeChange = vm::setMonitorDisplayMode,
                                    onLutRefresh = vm::refreshLutState,
                                    onLutSlot = vm::setLutSlot,
                                    onLutPreview = vm::setLutPreview,
                                    onLutRecord = vm::setLutDuringRecord,
                                    onLutOutput = vm::setLutDuringOutput
                                )
                                RemoteDisplayCard(
                                    enabled = transportReady,
                                    state = monitorDisplayState,
                                    onRefresh = vm::refreshDisplayState,
                                    onBacklight = vm::setDisplayBacklight,
                                    onFlip = vm::setDisplayFlip,
                                    onTally = vm::setDisplayTally,
                                    onLift = vm::setDisplayYLift,
                                    onGamma = vm::setDisplayYGamma,
                                    onGain = vm::setDisplayYGain,
                                    onReset = vm::resetDisplayPicture
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        ConnectedTopTab.AUDIO -> AudioModuleScreen(vm)
                        ConnectedTopTab.OUTPUT -> OutputModuleScreen(vm)
                        ConnectedTopTab.MEDIA -> MediaModuleScreen(vm)
                        ConnectedTopTab.SETTINGS -> SettingsModuleScreen(vm)
                        ConnectedTopTab.REMOTE -> Unit
                    }
                }
            }
        }
    }

    if (showDisconnectConfirmation) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirmation = false },
            title = { Text("Disconnect Remote?") },
            text = {
                Text(
                    "Are you sure you want to disconnect Remote? The app will disconnect and forget the current recorder pairing."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDisconnectConfirmation = false
                        vm.disconnectRemoteAndUnpair()
                    }
                ) {
                    Text("DISCONNECT")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirmation = false }) {
                    Text("CANCEL")
                }
            }
        )
    }
}



@Composable
private fun RemoteFrameGuideOnlyCard(
    enabled: Boolean,
    guideState: MonitorGuideState,
    onRefresh: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onFormat: (Int) -> Unit
) {
    LaunchedEffect(Unit) { if (enabled) onRefresh() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1517)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF244B45))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Frame Guide", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Independent frame guide control", color = Color(0xFF80938F), fontSize = 9.sp)
                }
                TextButton(onClick = onRefresh, enabled = enabled) {
                    Text("SYNC", color = RemoteMint, fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
            }
            HorizontalDivider(color = Color(0xFF213432), modifier = Modifier.padding(vertical = 10.dp))

            // Important recovery: do not permanently disable the switch from a stale UI pendingKey.
            // The existing ViewModel queue remains the only command path.
            RemoteMonitorToggle(
                "Frame Guide",
                guideState.frameGuideEnabled ?: false,
                enabled,
                onToggle
            )

            Text(
                guideState.status,
                color = if (guideState.pendingKey == "frame-guide") RemoteMint else Color(0xFF71817E),
                fontSize = 8.5.sp,
                lineHeight = 12.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            val formats = listOf(
                1 to "16:9",
                5 to "1.85:1",
                4 to "1.9:1",
                2 to "1.91:1",
                7 to "2.35:1",
                3 to "2.41:1",
                6 to "4:3",
                8 to "1:1",
                9 to "4:5",
                10 to "9:16"
            )
            Text("FRAME RATIO", color = Color(0xFF6F817D), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            formats.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    row.forEach { (value, label) ->
                        RemoteScopeChoiceButton(
                            label,
                            guideState.format == value,
                            enabled && guideState.frameGuideEnabled == true && guideState.pendingKey != "format",
                            Modifier.weight(1f)
                        ) { onFormat(value) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(7.dp))
            }
        }
    }
}

@Composable
private fun RemoteGridOnlyCard(
    enabled: Boolean,
    guideState: MonitorGuideState,
    onRefresh: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    LaunchedEffect(Unit) { if (enabled) onRefresh() }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1517)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF244B45))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("9-Grid", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Independent composition grid control", color = Color(0xFF80938F), fontSize = 9.sp)
                }
                TextButton(onClick = onRefresh, enabled = enabled) {
                    Text("SYNC", color = RemoteMint, fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
            }
            HorizontalDivider(color = Color(0xFF213432), modifier = Modifier.padding(vertical = 10.dp))
            RemoteMonitorToggle(
                "9-Grid",
                guideState.gridEnabled ?: false,
                enabled && guideState.pendingKey != "grid",
                onToggle
            )
            Text(guideState.status, color = Color(0xFF71817E), fontSize = 8.5.sp, lineHeight = 12.sp)
        }
    }
}

@Composable
private fun RemoteSafeAreaOnlyCard(
    enabled: Boolean,
    safeAreaMode: Int,
    onRefresh: () -> Unit,
    onMode: (Int) -> Unit
) {
    LaunchedEffect(Unit) { if (enabled) onRefresh() }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1517)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF244B45))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Safe Area", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Independent title/action safe-area control", color = Color(0xFF80938F), fontSize = 9.sp)
                }
                TextButton(onClick = onRefresh, enabled = enabled) {
                    Text("SYNC", color = RemoteMint, fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
            }
            HorizontalDivider(color = Color(0xFF213432), modifier = Modifier.padding(vertical = 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                RemoteScopeChoiceButton("OFF", safeAreaMode == 0, enabled, Modifier.weight(1f)) { onMode(0) }
                RemoteScopeChoiceButton("ACTION", safeAreaMode == 1, enabled, Modifier.weight(1f)) { onMode(1) }
                RemoteScopeChoiceButton("TITLE", safeAreaMode == 2, enabled, Modifier.weight(1f)) { onMode(2) }
            }
        }
    }
}

// REMOTE-HOME-NORMAL-FIX-20261008
@Composable
private fun RemoteFocusOnlyCard(
    enabled: Boolean,
    state: MonitorFocusAssistState,
    onRefresh: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onType: (Int) -> Unit,
    onThreshold: (Int) -> Unit,
    onColour: (Int) -> Unit
) {
    var uiEnabled by remember { mutableStateOf(state.enabled ?: false) }
    var uiType by remember { mutableStateOf(state.type) }
    var uiColour by remember { mutableStateOf(state.colour) }
    var thresholdDraft by remember {
        mutableFloatStateOf((state.threshold ?: 50).toFloat().coerceIn(0f, 100f))
    }
    var enabledTouched by remember { mutableStateOf(false) }
    var typeTouched by remember { mutableStateOf(false) }
    var colourTouched by remember { mutableStateOf(false) }
    var thresholdTouched by remember { mutableStateOf(false) }

    fun requestFreshState() {
        enabledTouched = false
        typeTouched = false
        colourTouched = false
        thresholdTouched = false
        onRefresh()
    }

    LaunchedEffect(Unit) {
        if (enabled) requestFreshState()
    }
    LaunchedEffect(state.enabled, state.pendingKey) {
        if (!enabledTouched && state.pendingKey == null && state.enabled != null) uiEnabled = state.enabled
    }
    LaunchedEffect(state.type, state.pendingKey) {
        if (!typeTouched && state.pendingKey == null && state.type != null) uiType = state.type
    }
    LaunchedEffect(state.colour, state.pendingKey) {
        if (!colourTouched && state.pendingKey == null && state.colour != null) uiColour = state.colour
    }
    LaunchedEffect(state.threshold, state.pendingKey) {
        if (!thresholdTouched && state.pendingKey == null && state.threshold != null) {
            thresholdDraft = state.threshold.toFloat().coerceIn(0f, 100f)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1517)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF244B45))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Focus Peaking", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { requestFreshState() }, enabled = enabled) {
                    Text("REFRESH", color = AtomosCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            Text(
                state.status,
                color = Color(0xFF71817E),
                fontSize = 8.5.sp,
                lineHeight = 11.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            RemoteMonitorToggle("Focus Peaking", uiEnabled, enabled) { checked ->
                enabledTouched = true
                uiEnabled = checked
                onToggle(checked)
            }

            Spacer(Modifier.height(8.dp))
            Text("TYPE", color = Color(0xFF6F817D), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                listOf(1 to "THIN", 2 to "MEDIUM", 3 to "THICK").forEach { (value, label) ->
                    RemoteScopeChoiceButton(
                        label,
                        uiType == value,
                        enabled && uiEnabled,
                        Modifier.weight(1f)
                    ) {
                        typeTouched = true
                        uiType = value
                        onType(value)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("COLOUR", color = Color(0xFF6F817D), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            val colours = listOf(
                1 to "WHITE", 2 to "RED", 3 to "GREEN", 4 to "BLUE",
                5 to "YELLOW", 6 to "CYAN", 7 to "MAGENTA"
            )
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                colours.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        row.forEach { (value, label) ->
                            RemoteScopeChoiceButton(
                                label,
                                uiColour == value,
                                enabled && uiEnabled,
                                Modifier.weight(1f)
                            ) {
                                colourTouched = true
                                uiColour = value
                                onColour(value)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("THRESHOLD", color = Color(0xFF6F817D), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = thresholdDraft,
                    onValueChange = {
                        thresholdTouched = true
                        thresholdDraft = it
                    },
                    onValueChangeFinished = { onThreshold(thresholdDraft.toInt()) },
                    valueRange = 0f..100f,
                    enabled = enabled && uiEnabled,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${thresholdDraft.toInt()}%",
                    color = AtomosCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.width(58.dp)
                )
            }
        }
    }
}

@Composable
private fun RemoteZebraOnlyCard(
    enabled: Boolean,
    state: MonitorZebraState,
    onRefresh: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onThreshold: (Int) -> Unit
) {
    var uiEnabled by remember { mutableStateOf(state.enabled ?: false) }
    var thresholdDraft by remember {
        mutableFloatStateOf((state.threshold ?: 90).toFloat().coerceIn(0f, 100f))
    }
    var enabledTouched by remember { mutableStateOf(false) }
    var thresholdTouched by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (enabled) onRefresh() }
    LaunchedEffect(state.enabled, state.pendingKey) {
        if (!enabledTouched && state.pendingKey == null && state.enabled != null) uiEnabled = state.enabled
    }
    LaunchedEffect(state.threshold, state.pendingKey) {
        if (!thresholdTouched && state.pendingKey == null && state.threshold != null) {
            thresholdDraft = state.threshold.toFloat().coerceIn(0f, 100f)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1517)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF244B45))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Zebra", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            RemoteMonitorToggle("Zebra", uiEnabled, enabled) { checked ->
                enabledTouched = true
                uiEnabled = checked
                onToggle(checked)
            }
            Text("THRESHOLD ${thresholdDraft.toInt()}%", color = Color(0xFF8FA19D), fontSize = 9.sp)
            Slider(
                value = thresholdDraft,
                onValueChange = {
                    thresholdTouched = true
                    thresholdDraft = it
                },
                onValueChangeFinished = { onThreshold(thresholdDraft.toInt()) },
                valueRange = 0f..100f,
                enabled = enabled && uiEnabled
            )
            Text(state.status, color = Color(0xFF71817E), fontSize = 8.5.sp)
        }
    }
}

@Composable
private fun RemoteBlueOnlyCard(
    enabled: Boolean,
    state: MonitorAssistEffectsState,
    onRefresh: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    var uiEnabled by remember { mutableStateOf(state.blueOnlyEnabled ?: false) }
    var touched by remember { mutableStateOf(false) }

    fun requestFreshState() {
        touched = false
        onRefresh()
    }

    LaunchedEffect(Unit) { if (enabled) requestFreshState() }
    LaunchedEffect(state.blueOnlyEnabled, state.pendingKey) {
        if (!touched && state.pendingKey == null && state.blueOnlyEnabled != null) {
            uiEnabled = state.blueOnlyEnabled
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1517)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF244B45))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Blue Only", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { requestFreshState() }, enabled = enabled) {
                    Text("REFRESH", color = AtomosCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            Text(
                state.status,
                color = Color(0xFF71817E),
                fontSize = 8.5.sp,
                lineHeight = 11.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            RemoteMonitorToggle(
                "Blue Only",
                uiEnabled,
                enabled && state.settingsChangeable != false
            ) { checked ->
                touched = true
                uiEnabled = checked
                onToggle(checked)
            }
        }
    }
}

@Composable
private fun RemoteFalseColorOnlyCard(
    enabled: Boolean,
    state: MonitorAssistEffectsState,
    onRefresh: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    var uiEnabled by remember { mutableStateOf(state.falseColourEnabled ?: false) }
    var touched by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (enabled) onRefresh() }
    LaunchedEffect(state.falseColourEnabled, state.pendingKey) {
        if (!touched && state.pendingKey == null && state.falseColourEnabled != null) {
            uiEnabled = state.falseColourEnabled
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1517)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF244B45))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("False Color", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            RemoteMonitorToggle("False Color", uiEnabled, enabled) { checked ->
                touched = true
                uiEnabled = checked
                onToggle(checked)
            }
            Text(state.status, color = Color(0xFF71817E), fontSize = 8.5.sp, lineHeight = 12.sp)
        }
    }
}

@Composable
private fun RemoteZoomOnlyCard(
    enabled: Boolean,
    zoom: Boolean,
    zoomLevel: Int,
    zoomSupportedLevels: List<Int>,
    zoomCapabilitiesKnown: Boolean,
    zoomCore: MonitorZoomCoreState,
    zoomViewport: MonitorZoomViewportState,
    zoomBoxState: ZoomBoxState?,
    onZoomLevelChange: (Int) -> Unit,
    onZoomRefresh: () -> Unit,
    onZoomViewportRefresh: () -> Unit,
    onZoomAnalogSessionChange: (Boolean) -> Unit,
    onPan: (Float, Float) -> Unit
) {
    LaunchedEffect(Unit) {
        if (enabled) {
            onZoomRefresh()
            onZoomViewportRefresh()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1517)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF244B45))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Analog Zoom", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "Recorder monitor zoom",
                        color = Color(0xFF80938F),
                        fontSize = 9.sp
                    )
                }
                TextButton(onClick = onZoomRefresh, enabled = enabled) {
                    Text("SYNC", color = RemoteMint, fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
            }

            HorizontalDivider(color = Color(0xFF213432), modifier = Modifier.padding(vertical = 10.dp))

            RemoteMonitorToggle(
                "Analog Zoom",
                zoom,
                enabled,
                onZoomAnalogSessionChange
            )

            Spacer(Modifier.height(8.dp))
            Text("ZOOM LEVEL", color = Color(0xFF6F817D), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))

            var zoomAnalogOpen by remember { mutableStateOf(false) }

            val supported = if (zoomSupportedLevels.isNotEmpty()) {
                zoomSupportedLevels.distinct().sorted()
            } else {
                listOf(1, 2)
            }

            val currentLevel = zoomCore.currentLevel ?: zoomLevel
            val analogEligible = enabled && zoom && currentLevel >= 2

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RemoteScopeChoiceButton(
                    label = "OFF",
                    selected = !zoom || currentLevel <= 0,
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) {
                    zoomAnalogOpen = false
                    onZoomAnalogSessionChange(false)
                    onZoomLevelChange(0)
                }
                RemoteScopeChoiceButton(
                    label = "1:1",
                    selected = currentLevel == 1,
                    enabled = enabled && 1 in supported,
                    modifier = Modifier.weight(1f)
                ) {
                    zoomAnalogOpen = false
                    onZoomAnalogSessionChange(false)
                    onZoomLevelChange(1)
                }
                RemoteScopeChoiceButton(
                    label = "2:1",
                    selected = currentLevel == 2,
                    enabled = enabled && 2 in supported,
                    modifier = Modifier.weight(1f)
                ) {
                    onZoomLevelChange(2)
                }
            }

            Spacer(Modifier.height(8.dp))

            if (currentLevel >= 2 && zoom) {
                RemoteScopeChoiceButton(
                    label = "ANALOG",
                    selected = zoomAnalogOpen,
                    enabled = analogEligible,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    zoomAnalogOpen = !zoomAnalogOpen
                    onZoomAnalogSessionChange(zoomAnalogOpen)
                }
                Spacer(Modifier.height(10.dp))
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF071310),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF203B37))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (zoom) "Zoom active" else "Zoom off",
                            color = if (zoom) RemoteMint else Color(0xFFB4C0BD),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (zoomCapabilitiesKnown) {
                                "Current: " + if (currentLevel <= 0) "OFF" else "${currentLevel}x"
                            } else {
                                "Waiting for recorder zoom capability readback"
                            },
                            color = Color(0xFF72837F),
                            fontSize = 8.5.sp
                        )
                    }
                    Text(
                        if (zoom && currentLevel > 0) "${currentLevel}x" else "OFF",
                        color = RemoteMint,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            if (zoomAnalogOpen && currentLevel >= 2 && zoom) {
                Spacer(Modifier.height(10.dp))
                RemoteZoomAnalogDialog(
                    enabled = analogEligible,
                    zoomLevel = currentLevel,
                    zoomBox = zoomBoxState,
                    viewportState = zoomViewport,
                    onRefresh = onZoomViewportRefresh,
                    onPan = onPan,
                    onDismiss = {
                        zoomAnalogOpen = false
                        onZoomAnalogSessionChange(false)
                    }
                )
            }

            @Suppress("UNUSED_VARIABLE")
            val preserveExistingPanCallback = onPan

            @Suppress("UNUSED_VARIABLE")
            val preserveZoomBoxReadback = zoomBoxState

            @Suppress("UNUSED_VARIABLE")
            val preserveViewportReadback = zoomViewport
        }
    }
}



/**
 * NDI-06C. Remote tab header immediately ABOVE the existing Live Preview hero.
 * A discovered sender is NOT a verified recorder TX ON/OFF state.
 * The displayed toggle is deliberately disabled, not an OFF indication.
 * Never send LiveModeSet, GATT writes, NDI control commands or media connects here.
 */
@Composable
private fun RemoteNdiTxStatusHeader(
    source: Ndi06DiscoveredSource?,
    discoveredCount: Int,
    lastEvent: String,
    onRescan: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF071A18),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1C4740))
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("NDI TX", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "TX STATE UNKNOWN | CONTROL LOCKED",
                        color = Color(0xFFEDC982),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                // OFF would be a false claim when the recorder is already yellow/red.
                // Status UNKNOWN is shown regardless of this disabled switch graphic.
                Switch(
                    checked = false,
                    onCheckedChange = null,
                    enabled = false
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    source != null -> "NDI SOURCE: ${source.name} (${source.host})"
                    discoveredCount > 1 -> "${discoveredCount} sources detected; recorder not auto-selected"
                    else -> "Searching for recorder NDI sender on Wi-Fi"
                },
                color = if (source != null) AtomosCyan else Color(0xFF9DAEAB),
                fontSize = 8.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                lastEvent.take(90),
                color = Color(0xFF748984),
                fontSize = 7.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Live video will appear in the SAME preview box when an NDI decoder is integrated.",
                    color = Color(0xFF879C95),
                    fontSize = 7.5.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRescan) {
                    Text("RESCAN", color = AtomosCyan, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun RemoteWirelessHomeHero(
    recorderName: String,
    recorderAddress: String?,
    transportReady: Boolean,
    inputLabel: String,
    codecLabel: String,
    monitorLabel: String,
    pairingCode: String?,
    wirelessStatus: String,
    wirelessDetail: String,
    sameNetworkReady: Boolean,
    networkTransport: String,
    networkHost: String?,
    networkHostConfirmed: Boolean,
    bleLinked: Boolean,
    streamCandidate: String,
    ndi06Source: Ndi06DiscoveredSource?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF061311),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            AtomosCyan.copy(alpha = 0.62f)
        )
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color(0xFF020807), RoundedCornerShape(14.dp))
            ) {
                Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                    drawRect(
                        AtomosCyan.copy(alpha = 0.16f),
                        style = Stroke(width = 1.4f)
                    )
                    drawLine(
                        AtomosCyan.copy(alpha = 0.07f),
                        Offset(size.width / 3f, 0f),
                        Offset(size.width / 3f, size.height),
                        1f
                    )
                    drawLine(
                        AtomosCyan.copy(alpha = 0.07f),
                        Offset(size.width * 2f / 3f, 0f),
                        Offset(size.width * 2f / 3f, size.height),
                        1f
                    )
                    drawLine(
                        AtomosCyan.copy(alpha = 0.07f),
                        Offset(0f, size.height / 2f),
                        Offset(size.width, size.height / 2f),
                        1f
                    )
                }


                if (!Ndi07Native.available || ndi06Source == null) {
                    Column(
                        Modifier.align(Alignment.Center).padding(horizontal = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            painter = painterResource(R.drawable.atom_remote_brand_mark),
                            contentDescription = null,
                            modifier = Modifier.size(42.dp)
                        )
                        Text(
                            if (ndi06Source == null) "Waiting for Recorder NDI" else "NDI decoder unavailable",
                            color = AtomosCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        if (ndi06Source != null) Text(
                            Ndi07Native.loadDiagnostic.take(120),
                            color = Color(0xFFB1BDBA),
                            fontSize = 9.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun RemoteWirelessHeroMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    active: Boolean
) {
    Column(
        modifier = modifier.padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            label,
            color = if (active) AtomosCyan else Color(0xFF61716D),
            fontSize = 6.3.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.45.sp
        )
        Text(
            value,
            color = if (active) Color.White else Color(0xFF87938F),
            fontSize = 7.4.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}


@Composable
private fun RemoteHomeOverlayChip(text: String) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = Color(0xD8071110),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Color(0xFF213B37)
        )
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = 8.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun RemoteWirelessStreamProbeCard(
    enabled: Boolean,
    host: String?,
    candidate: String,
    detail: String,
    onProbe: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF06110F),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (enabled) AtomosCyan.copy(alpha = 0.55f) else Color(0xFF243A40)
        )
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "WIRELESS FEED CHECK",
                        color = AtomosCyan,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.7.sp
                    )
                    Text(
                        candidate,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                    Text(
                        host ?: "Waiting for recorder LAN IP",
                        color = Color(0xFF83938F),
                        fontSize = 8.5.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                OutlinedButton(
                    onClick = onProbe,
                    enabled = enabled,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (enabled) AtomosCyan else Color(0xFF344144)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Text(
                        "PROBE",
                        color = if (enabled) AtomosCyan else Color(0xFF687578),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                detail,
                color = Color(0xFF70817D),
                fontSize = 8.5.sp,
                lineHeight = 11.sp
            )
            Text(
                "Live video starts only after a real recorder stream transport is confirmed.",
                color = Color(0xFF52645F),
                fontSize = 7.5.sp,
                lineHeight = 10.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun RemoteWirelessSummaryStrip(
    codec: String,
    monitor: String,
    linked: Boolean,
    networkStatus: String,
    networkHost: String?
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        RemoteWirelessSummaryItem(
            "NETWORK",
            networkHost ?: networkStatus,
            Modifier.weight(1f)
        )
        RemoteWirelessSummaryItem("CODEC", codec, Modifier.weight(1f))
        RemoteWirelessSummaryItem("MONITOR", monitor, Modifier.weight(1f))
        RemoteWirelessSummaryItem(
            "BLE LINK",
            if (linked) "CONNECTED" else "WAIT",
            Modifier.weight(1f)
        )
    }
}

@Composable
private fun RemoteWirelessSummaryItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(11.dp),
        color = Color(0xFF071310),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Color(0xFF203B37)
        )
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 9.dp)
        ) {
            Text(
                label,
                color = RemoteMint,
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                value,
                color = Color.White,
                fontSize = 8.7.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

// WL-3.2.6: Remote Home Monitor parity - Focus Peaking + Blue Only restored
@Composable
private fun RemoteHomeToolsGrid(
    activeTool: RemoteHomeTool?,
    enabled: Boolean,
    ndi15Height: Int,
    onPreviewSettings: () -> Unit,
    onTool: (RemoteHomeTool) -> Unit
) {
    // V15.1: Live Preview is FIRST, not appended inside the final row.
    // All established monitor tools follow in their exact original order.
    // This receiver-only tile is accessible even without Bluetooth transport.
    val tools: List<RemoteHomeTool?> = listOf(null) + RemoteHomeTool.entries
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tools.chunked(4).forEach { rowTools ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowTools.forEach { tool ->
                    if (tool == null) {
                        Surface(
                            modifier = Modifier.weight(1f).height(98.dp)
                                .clickable(onClick = onPreviewSettings),
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF0B211F),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AtomosCyan)
                        ) {
                            Column(Modifier.fillMaxSize().padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                Icon(painterResource(R.drawable.ui_remote_tool_live_preview),
                                    contentDescription = "Live Preview settings", tint = AtomosCyan,
                                    modifier = Modifier.size(34.dp))
                                Text("LIVE PREVIEW", color = Color.White, fontSize = 8.2.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                Text("${ndi15Height}p", color = AtomosCyan, fontSize = 9.sp)
                            }
                        }
                    } else {
                        val active = activeTool == tool
                        Surface(
                            modifier = Modifier.weight(1f).height(98.dp)
                                .clickable(enabled = enabled) { onTool(tool) },
                            shape = RoundedCornerShape(14.dp),
                            color = if (active) Color(0xFF0A2E32) else Color(0xFF071115),
                            border = androidx.compose.foundation.BorderStroke(
                                if (active) 1.5.dp else 1.dp,
                                if (active) RemoteMint else Color(0xFF243A40)
                            )
                        ) {
                            Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 9.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                Icon(painter = painterResource(tool.iconRes),
                                    contentDescription = tool.label,
                                    tint = if (enabled) AtomosCyan else Color(0xFF52615E),
                                    modifier = Modifier.size(39.dp))
                                Text(tool.label,
                                    color = if (enabled) Color.White else Color(0xFF5A686C),
                                    fontSize = 8.2.sp,
                                    lineHeight = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    maxLines = 2,
                                    modifier = Modifier.padding(top = 7.dp))
                            }
                        }
                    }
                }
                repeat(4 - rowTools.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RemoteLutInnerCard(
    enabled: Boolean,
    mode: Int,
    lutState: MonitorLutState,
    onEnableLutMode: () -> Unit,
    onRefresh: () -> Unit,
    onSlot: (Int) -> Unit,
    onPreview: (Boolean) -> Unit,
    onRecord: (Boolean) -> Unit,
    onOutput: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1315))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LUT", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        if (mode == 5) "LUT monitoring active" else "LUT controls available. Enable LUT monitor mode to preview.",
                        color = if (mode == 5) AtomosCyan else Color(0xFF8B989C),
                        fontSize = 9.5.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                TextButton(onClick = onRefresh, enabled = enabled && lutState.pendingKey != "refresh") {
                    Text("SYNC", color = AtomosCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (mode != 5) {
                Button(
                    onClick = onEnableLutMode,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text("ENABLE LUT MONITOR MODE")
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("LUT SLOT", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text(
                lutState.currentLabel(),
                color = AtomosCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )

            (1..8).toList().chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    row.forEach { slot ->
                        RemoteScopeChoiceButton(
                            label = slot.toString(),
                            selected = lutState.shownSlot == slot,
                            enabled = enabled && lutState.pendingKey != "slot",
                            modifier = Modifier.weight(1f)
                        ) { onSlot(slot) }
                    }
                }
                Spacer(Modifier.height(7.dp))
            }

            HorizontalDivider(color = Color(0xFF202628))
            Spacer(Modifier.height(8.dp))

            RemoteLutSwitchRow(
                title = "Preview / Compare",
                checked = lutState.shownPreviewEnabled == true,
                available = lutState.previewAvailable,
                enabled = enabled && lutState.pendingKey != "preview",
                onCheckedChange = onPreview
            )
            RemoteLutSwitchRow(
                title = "During Record",
                checked = lutState.shownRecordBurnInEnabled == true,
                available = lutState.recordBurnInAvailable,
                enabled = enabled && lutState.pendingKey != "record",
                onCheckedChange = onRecord
            )
            RemoteLutSwitchRow(
                title = "During Output",
                checked = lutState.shownOutputLutEnabled == true,
                available = lutState.outputLutAvailable,
                enabled = enabled && lutState.pendingKey != "output",
                onCheckedChange = onOutput
            )

            Text(
                lutState.status,
                color = Color(0xFF747E81),
                fontSize = 8.5.sp,
                lineHeight = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun RemoteLutSwitchRow(
    title: String,
    checked: Boolean,
    available: Boolean?,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(
                when (available) {
                    true -> if (checked) "On" else "Off"
                    false -> "Not available on current recorder state"
                    null -> "State not reported yet"
                },
                color = Color(0xFF7F898C),
                fontSize = 8.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled && available != false
        )
    }
}


private fun monitorAssistSummary(zoomLevel: Int, focus: Boolean, zebra: Boolean, falseColor: Boolean, blueOnly: Boolean): String {
    val active = listOf(focus, zebra, falseColor, blueOnly).count { it }
    return when {
        zoomLevel > 0 -> "Zoom ${if (zoomLevel == 2) "2:1" else "1:1"}${if (active > 0) " Â· $active active" else ""}"
        active > 0 -> "$active active"
        else -> "Ready"
    }
}

private fun guidesSummary(grid: Boolean, guide: Boolean, safe: Int): String {
    val active = mutableListOf<String>()
    if (grid) active += "Grid"
    if (guide) active += "Guide"
    if (safe == 1) active += "Action"
    if (safe == 2) active += "Title"
    return active.joinToString(" | ").ifBlank { "Ready" }
}

private fun anamorphicSummary(mode: Int): String = when (mode) {
    1 -> "2x"; 2 -> "2.43x"; 3 -> "1.5x"; 4 -> "1.33x"; 5 -> "1.8x"
    6 -> "1.25x"; 7 -> "1.79x"; 8 -> "1.65x"; 9 -> "1.6x"; else -> "Off"
}

private fun monitorModeSummary(mode: Int): String = when (mode) {
    1 -> "Native"
    2 -> "Rec.709"
    3 -> "HLG"
    4 -> "PQ"
    5 -> "LUT"
    else -> "Ready"
}

private fun codecSummary(profile: Int): String = when (profile) {
    1 -> "ProRes Proxy"; 2 -> "ProRes LT"; 3 -> "ProRes 422"; 4 -> "ProRes HQ"; 5 -> "ProRes 4444"
    6 -> "DNx LB"; 7 -> "DNx SQ"; 8 -> "DNx HQ"; 9 -> "DNx HQX"; 10 -> "CinemaDNG"
    11 -> "ProRes RAW"; 12 -> "ProRes RAW HQ"; 13 -> "H.264 LQ"; 14 -> "H.264 MQ"; 15 -> "H.264 HQ"
    16 -> "H.265 LQ"; 17 -> "H.265 MQ"; 18 -> "H.265 HQ"; 19 -> "H.265 XQ"; else -> "Select profile"
}

@Composable
private fun RemoteFeatureTile(
    panel: RemotePanel,
    status: String,
    connected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .heightIn(min = 98.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101416)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2527))
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF102624)) {
                    Text(panel.badge, color = AtomosCyan, fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp))
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(7.dp).background(if (connected) AtomosCyan else Color(0xFF4E575A), CircleShape))
            }
            Spacer(Modifier.height(10.dp))
            Text(panel.title, color = Color(0xFFE7EBEC), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, color = Color(0xFF7D878A), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun RemoteConnectionCard(
    recorderName: String,
    recorderAddress: String,
    pairingCode: String?,
    title: String,
    subtitle: String,
    phase: String,
    transportReady: Boolean,
    commandInFlight: Boolean,
    hasError: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101416))
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(42.dp)
                        .background(Color(0xFF191F21), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .background(
                                when {
                                    hasError -> AtomosRed
                                    transportReady -> AtomosCyan
                                    else -> Color(0xFF657074)
                                },
                                CircleShape
                            )
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        recorderName,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        recorderAddress,
                        color = Color(0xFF717A7D),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = when {
                        hasError -> Color(0xFF2A1517)
                        transportReady -> Color(0xFF0E2725)
                        else -> Color(0xFF1A1F21)
                    }
                ) {
                    Text(
                        when {
                            hasError -> "CHECK"
                            commandInFlight -> "ACTIVE"
                            transportReady -> "READY"
                            else -> "SYNCING"
                        },
                        color = when {
                            hasError -> AtomosRed
                            transportReady -> AtomosCyan
                            else -> Color(0xFFABB2B4)
                        },
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Divider(color = Color(0xFF202628))
            Spacer(Modifier.height(14.dp))

            Text(
                title,
                color = when {
                    hasError -> AtomosRed
                    transportReady -> AtomosCyan
                    else -> Color(0xFFB1B8BA)
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.1.sp
            )
            Text(
                subtitle,
                color = Color(0xFF858D90),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 5.dp)
            )

            Spacer(Modifier.height(13.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RemoteMetaPill("CONTROL", if (transportReady) "READY" else "CONNECTING", Modifier.weight(1f))
                RemoteMetaPill("PAIRING", if (pairingCode.isNullOrBlank()) "ACCEPTED" else "VERIFIED", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RemoteMetaPill(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0B0E10),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF22282A))
    ) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 8.dp)) {
            Text(label, color = Color(0xFF596164), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            Text(
                value,
                color = Color(0xFFB9C1C3),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun RemoteSectionLabel(text: String) {
    Text(
        text,
        color = Color(0xFF70787B),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.1.sp
    )
}

@Composable
private fun RemoteInfoTile(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1315))
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = Color(0xFF626A6D), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
            Text(
                value,
                color = valueColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

@Composable
private fun RemoteMonitorAssistCard(
    enabled: Boolean,
    zoom: Boolean,
    zoomLevel: Int,
    focusAssist: Boolean,
    zebra: Boolean,
    falseColor: Boolean,
    blueOnly: Boolean,
    zoomSupportedLevels: List<Int>,
    zoomCapabilitiesKnown: Boolean,
    zoomCore: MonitorZoomCoreState,
    zoomViewport: MonitorZoomViewportState,
    focusState: MonitorFocusAssistState,
    zebraState: MonitorZebraState,
    effectsState: MonitorAssistEffectsState,
    zoomBoxState: ZoomBoxState?,
    onZoomLevelChange: (Int) -> Unit,
    onZoomRefresh: () -> Unit,
    onZoomViewportRefresh: () -> Unit,
    onZoomAnalogSessionChange: (Boolean) -> Unit,
    onFocusRefresh: () -> Unit,
    onFocusType: (Int) -> Unit,
    onFocusThreshold: (Int) -> Unit,
    onFocusColour: (Int) -> Unit,
    onZebraThreshold: (Int) -> Unit,
    onEffectsRefresh: () -> Unit,
    onPan: (Float, Float) -> Unit,
    onToggle: (String, Boolean) -> Unit
) {
    var zoomAnalogOpen by remember { mutableStateOf(false) }


    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1315))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(Color(0xFF102624), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(8.dp).background(AtomosCyan, CircleShape))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Monitor Assist", color = Color(0xFFE9EDEE), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Focus, exposure and zoom tools", color = Color(0xFF959DA0), fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }

            Spacer(Modifier.height(14.dp))
            Divider(color = Color(0xFF202628))
            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("ZOOM", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onZoomRefresh, enabled = enabled) {
                    Text("SYNC", color = AtomosCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            // 20.04.9 macOS parity: ZoomSettingsController enables the
            // buttons from availZoomLevels. Unknown capability state is not treated
            // as support, which prevents blind 2:1 sends/rejects.
            val level1Available = zoomCapabilitiesKnown && 1 in zoomSupportedLevels
            val level2Available = zoomCapabilitiesKnown && 2 in zoomSupportedLevels
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                RemoteScopeChoiceButton(
                    "OFF",
                    zoomCore.currentLevel == 0,
                    enabled,
                    Modifier.weight(0.8f)
                ) { onZoomLevelChange(0) }
                RemoteScopeChoiceButton(
                    "1:1",
                    zoomCore.currentLevel == 1,
                    enabled && level1Available,
                    Modifier.weight(1f)
                ) { onZoomLevelChange(1) }
                RemoteScopeChoiceButton(
                    "2:1",
                    zoomCore.currentLevel == 2,
                    enabled && level2Available,
                    Modifier.weight(1f)
                ) { onZoomLevelChange(2) }
            }

            RemoteScopeChoiceButton(
                "ANALOG",
                zoomAnalogOpen,
                enabled && (zoomCore.currentLevel ?: 0) > 0,
                Modifier.fillMaxWidth().padding(top = 7.dp)
            ) {
                zoomAnalogOpen = !zoomAnalogOpen
                onZoomAnalogSessionChange(zoomAnalogOpen)
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Current: ${zoomCore.label(zoomCore.currentLevel)}",
                        color = if (zoomCore.currentLevel != null) AtomosCyan else Color(0xFF8C9699),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (zoomCore.capabilitiesKnown && zoomCore.supportedLevels.isNotEmpty()) {
                        Text(
                            "Available: " + zoomCore.supportedLevels.joinToString(" Â· ") { zoomCore.label(it) },
                            color = Color(0xFF8C9699),
                            fontSize = 9.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                if (zoomCore.pendingLevel != null) {
                    Text(
                        "VERIFYING",
                        color = Color(0xFF9AA5A8),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Text(
                zoomCore.status,
                color = Color(0xFF747E81),
                fontSize = 9.sp,
                lineHeight = 12.sp,
                modifier = Modifier.padding(bottom = 5.dp)
            )

            if (zoomAnalogOpen) {
                RemoteZoomAnalogDialog(
                    enabled = enabled && (zoomCore.currentLevel ?: 0) > 0,
                    zoomLevel = zoomCore.currentLevel ?: 0,
                    zoomBox = zoomBoxState,
                    viewportState = zoomViewport,
                    onRefresh = onZoomViewportRefresh,
                    onPan = onPan,
                    onDismiss = {
                        zoomAnalogOpen = false
                        onZoomAnalogSessionChange(false)
                    }
                )
            }

            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = Color(0xFF202628))
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("FOCUS ASSIST", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onFocusRefresh, enabled = enabled && focusState.pendingKey != "enabled") {
                    Text("REFRESH", color = AtomosCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            RemoteMonitorToggle(
                "Focus Peaking",
                focusState.enabled ?: focusAssist,
                enabled
            ) { onToggle("focus-assist", it) }

            Text(
                focusState.status,
                color = Color(0xFF747E81),
                fontSize = 9.sp,
                lineHeight = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )

            Text("TYPE", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                listOf(1 to "Thin", 2 to "Medium", 3 to "Thick").forEach { (value, label) ->
                    RemoteScopeChoiceButton(
                        label.uppercase(),
                        focusState.type == value,
                        enabled && (focusState.enabled ?: focusAssist),
                        Modifier.weight(1f)
                    ) { onFocusType(value) }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("COLOUR", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            val focusColours = listOf(
                1 to "White",
                2 to "Red",
                3 to "Green",
                4 to "Blue",
                5 to "Yellow",
                6 to "Cyan",
                7 to "Magenta"
            )
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                focusColours.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        row.forEach { (value, label) ->
                            RemoteScopeChoiceButton(
                                label.uppercase(),
                                focusState.colour == value,
                                enabled && (focusState.enabled ?: focusAssist),
                                Modifier.weight(1f)
                            ) { onFocusColour(value) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("THRESHOLD", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            val focusReportedThreshold = focusState.threshold
            var focusThresholdDraft by remember(focusReportedThreshold) {
                mutableFloatStateOf((focusReportedThreshold ?: 50).toFloat().coerceIn(0f, 100f))
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Slider(
                    value = focusThresholdDraft,
                    onValueChange = { focusThresholdDraft = it },
                    onValueChangeFinished = {
                        onFocusThreshold(focusThresholdDraft.toInt())
                    },
                    valueRange = 0f..100f,
                    enabled = enabled && (focusState.enabled ?: focusAssist),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${focusThresholdDraft.toInt()}%",
                    color = if (focusReportedThreshold != null) AtomosCyan else Color(0xFF6F797C),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.width(58.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFF202628))
            Spacer(Modifier.height(8.dp))
            Text("ZEBRA", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)

            RemoteMonitorToggle(
                "Zebra",
                zebraState.enabled ?: zebra,
                enabled
            ) { onToggle("zebra", it) }

            Text(
                zebraState.status,
                color = Color(0xFF747E81),
                fontSize = 9.sp,
                lineHeight = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 7.dp)
            )

            Text("THRESHOLD", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            val zebraReportedThreshold = zebraState.threshold
            var zebraThresholdDraft by remember(zebraReportedThreshold) {
                mutableFloatStateOf((zebraReportedThreshold ?: 70).toFloat().coerceIn(0f, 100f))
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Slider(
                    value = zebraThresholdDraft,
                    onValueChange = { zebraThresholdDraft = it },
                    onValueChangeFinished = {
                        onZebraThreshold(zebraThresholdDraft.toInt())
                    },
                    valueRange = 0f..100f,
                    enabled = enabled && (zebraState.enabled ?: zebra),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${zebraThresholdDraft.toInt()}%",
                    color = if (zebraReportedThreshold != null) AtomosCyan else Color(0xFF6F797C),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.width(58.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFF202628))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("EXPOSURE TOOLS", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEffectsRefresh, enabled = enabled && effectsState.pendingKey == null) {
                    Text("REFRESH", color = AtomosCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            RemoteMonitorToggle(
                "False Colour",
                effectsState.falseColourEnabled ?: falseColor,
                enabled && effectsState.settingsChangeable != false
            ) { onToggle("false-color", it) }

            RemoteMonitorToggle(
                "Blue Only",
                effectsState.blueOnlyEnabled ?: blueOnly,
                enabled && effectsState.settingsChangeable != false
            ) { onToggle("blue-only", it) }

            Text(
                effectsState.status,
                color = Color(0xFF747E81),
                fontSize = 9.sp,
                lineHeight = 12.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

@Composable
private fun MonitorValueDropdown(
    title: String,
    values: List<Int>,
    current: Int?,
    enabled: Boolean,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { expanded = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(11.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF314044))
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(title.uppercase(), color = Color(0xFF8C989B), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text(
                current?.toString() ?: "Choose recorder value",
                color = if (current == null) Color(0xFFB8C0C2) else AtomosCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black
            )
        }
    }

    if (expanded) {
        Dialog(onDismissRequest = { expanded = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF11171A),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF314044))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title.uppercase(), color = Color(0xFF8C989B), fontSize = 9.sp, fontWeight = FontWeight.Black)
                            if (current != null) {
                                Text("Current: $current", color = AtomosCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        TextButton(onClick = { expanded = false }) {
                            Text("CLOSE", fontSize = 9.sp, fontWeight = FontWeight.Black)
                        }
                    }
                    HorizontalDivider(color = Color(0xFF253034))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(values.distinct()) { value ->
                            OutlinedButton(
                                onClick = {
                                    expanded = false
                                    onSelect(value)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (value == current) AtomosCyan else Color(0xFF314044)
                                )
                            ) {
                                Text(
                                    value.toString(),
                                    color = if (value == current) AtomosCyan else Color(0xFFD7DDDE),
                                    fontSize = 10.sp,
                                    fontWeight = if (value == current) FontWeight.Black else FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteZoomAnalogDialog(
    enabled: Boolean,
    zoomLevel: Int,
    zoomBox: ZoomBoxState?,
    viewportState: MonitorZoomViewportState,
    onRefresh: () -> Unit,
    onPan: (Float, Float) -> Unit,
    onDismiss: () -> Unit
) {
    val frameW = zoomBox?.frameWidth?.takeIf { it > 0 } ?: 1920
    val frameH = zoomBox?.frameHeight?.takeIf { it > 0 } ?: 1080
    val effectiveViewW = when {
        zoomLevel == 2 && (zoomBox == null || !zoomBox.pannable) -> frameW / 2
        zoomBox?.viewportWidth?.let { it > 0 } == true -> zoomBox.viewportWidth
        else -> frameW
    }.coerceAtLeast(1)
    val effectiveViewH = when {
        zoomLevel == 2 && (zoomBox == null || !zoomBox.pannable) -> frameH / 2
        zoomBox?.viewportHeight?.let { it > 0 } == true -> zoomBox.viewportHeight
        else -> frameH
    }.coerceAtLeast(1)
    val maxLeft = (frameW - effectiveViewW).coerceAtLeast(0)
    val maxTop = (frameH - effectiveViewH).coerceAtLeast(0)
    val recorderX = if (maxLeft > 0) ((zoomBox?.left ?: 0).toFloat() / maxLeft).coerceIn(0f, 1f) else 0.5f
    val recorderY = if (maxTop > 0) ((zoomBox?.top ?: 0).toFloat() / maxTop).coerceIn(0f, 1f) else 0.5f
    var xFraction by remember(zoomLevel, frameW, frameH, zoomBox?.left, zoomBox?.top) { mutableFloatStateOf(recorderX) }
    var yFraction by remember(zoomLevel, frameW, frameH, zoomBox?.left, zoomBox?.top) { mutableFloatStateOf(recorderY) }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    var stickOffset by remember { mutableStateOf(Offset.Zero) }
    var lastLiveSendAt by remember { mutableLongStateOf(0L) }
    val canPan = enabled && zoomLevel == 2 && maxLeft > 0 && maxTop > 0

    fun sendLive(force: Boolean = false) {
        if (!canPan) return
        val now = SystemClock.uptimeMillis()
        if (force || now - lastLiveSendAt >= 34L) {
            lastLiveSendAt = now
            onPan(xFraction, yFraction)
        }
    }

    fun commit(nx: Float = xFraction, ny: Float = yFraction) {
        xFraction = nx.coerceIn(0f, 1f)
        yFraction = ny.coerceIn(0f, 1f)
        sendLive(force = true)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF0D1113),
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 8.dp
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ZOOM ANALOG", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
                        Text(
                            if (zoomLevel == 2) "2:1 viewport control" else "Select 2:1 for full pan range",
                            color = Color(0xFF8F999C), fontSize = 10.sp
                        )
                    }
                    TextButton(onClick = onRefresh) { Text("SYNC", color = AtomosCyan, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = onDismiss) { Text("CLOSE", color = Color(0xFFB8C0C2), fontWeight = FontWeight.Bold) }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        viewportState.pendingPan -> "Live pan streaming to recorder..."
                        canPan -> "Drag the cyan box, use the joystick, or tap the arrow controls."
                        zoomLevel != 2 -> "2:1 Zoom is required for analog movement."
                        else -> "Waiting for a usable viewport."
                    },
                    color = if (canPan) AtomosCyan else Color(0xFF8F999C),
                    fontSize = 10.sp
                )

                Spacer(Modifier.height(10.dp))
                val aspect = (frameW.toFloat() / frameH.toFloat()).coerceIn(1f, 2.6f)
                val viewportWFrac = (effectiveViewW.toFloat() / frameW.toFloat()).coerceIn(0.08f, 1f)
                val viewportHFrac = (effectiveViewH.toFloat() / frameH.toFloat()).coerceIn(0.08f, 1f)
                val previewModifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .background(Color(0xFF070A0B), RoundedCornerShape(14.dp))
                    .border(1.dp, Color(0xFF30383B), RoundedCornerShape(14.dp))
                    .onSizeChanged { padSize = it }
                    .pointerInput(canPan, padSize, viewportWFrac, viewportHFrac) {
                        if (!canPan || padSize.width <= 0 || padSize.height <= 0) return@pointerInput
                        detectDragGestures(
                            onDragEnd = { sendLive(force = true) },
                            onDrag = { change, dragAmount ->
                                val travelX = (padSize.width.toFloat() * (1f - viewportWFrac)).coerceAtLeast(1f)
                                val travelY = (padSize.height.toFloat() * (1f - viewportHFrac)).coerceAtLeast(1f)
                                xFraction = (xFraction + dragAmount.x / travelX).coerceIn(0f, 1f)
                                yFraction = (yFraction + dragAmount.y / travelY).coerceIn(0f, 1f)
                                sendLive()
                                change.consume()
                            }
                        )
                    }

                Canvas(previewModifier) {
                    val boxW = size.width * viewportWFrac
                    val boxH = size.height * viewportHFrac
                    val left = xFraction * (size.width - boxW).coerceAtLeast(0f)
                    val top = yFraction * (size.height - boxH).coerceAtLeast(0f)
                    drawRect(Color(0xFF090D0F))
                    drawRect(AtomosCyan.copy(alpha = 0.18f), Offset(left, top), Size(boxW, boxH))
                    drawRect(AtomosCyan, Offset(left, top), Size(boxW, boxH), style = Stroke(width = 5f))
                    val center = Offset(left + boxW / 2f, top + boxH / 2f)
                    drawCircle(AtomosCyan, radius = 16f, center = center)
                    drawCircle(Color.Black, radius = 5f, center = center)
                }

                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    // PS4-style analog stick with recorder-live feedback.
                    // GattClient coalesces high-rate samples so the newest position is sent
                    // as soon as PacketLink becomes available without flooding BLE.
                    Box(
                        modifier = Modifier
                            .size(128.dp)
                            .background(Color(0xFF111719), CircleShape)
                            .border(2.dp, Color(0xFF394347), CircleShape)
                            .pointerInput(canPan) {
                                if (!canPan) return@pointerInput
                                detectDragGestures(
                                    onDragStart = { stickOffset = Offset.Zero },
                                    onDragEnd = {
                                        stickOffset = Offset.Zero
                                        sendLive(force = true)
                                    },
                                    onDragCancel = { stickOffset = Offset.Zero },
                                    onDrag = { change, amount ->
                                        val next = stickOffset + amount
                                        val radius = 38f
                                        val len = kotlin.math.sqrt(next.x * next.x + next.y * next.y)
                                        stickOffset = if (len > radius && len > 0f) next * (radius / len) else next

                                        // PS4-style live movement: the image on the recorder follows
                                        // the stick continuously instead of waiting for finger release.
                                        xFraction = (xFraction + amount.x / 360f).coerceIn(0f, 1f)
                                        yFraction = (yFraction + amount.y / 360f).coerceIn(0f, 1f)
                                        sendLive()
                                        change.consume()
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            val c = center
                            drawCircle(Color(0xFF1D2528), radius = size.minDimension * 0.43f, center = c)
                            drawCircle(Color(0xFF2C373B), radius = size.minDimension * 0.24f, center = c + stickOffset)
                            drawCircle(AtomosCyan, radius = size.minDimension * 0.07f, center = c + stickOffset)
                        }
                    }

                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("FINE MOVE", color = Color(0xFF8F999C), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        RemoteAnalogArrow("â–²", canPan) { commit(ny = (yFraction - 0.08f).coerceAtLeast(0f)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RemoteAnalogArrow("â—€", canPan) { commit(nx = (xFraction - 0.08f).coerceAtLeast(0f)) }
                            RemoteAnalogArrow("â—", canPan) { commit(0.5f, 0.5f) }
                            RemoteAnalogArrow("â–¶", canPan) { commit(nx = (xFraction + 0.08f).coerceAtMost(1f)) }
                        }
                        RemoteAnalogArrow("â–¼", canPan) { commit(ny = (yFraction + 0.08f).coerceAtMost(1f)) }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "X ${(xFraction * 100).toInt()}%  Â·  Y ${(yFraction * 100).toInt()}%",
                    color = Color(0xFF778286), fontSize = 9.sp, modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
private fun RemoteAnalogArrow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(46.dp).clickable(enabled = enabled, onClick = onClick),
        color = if (enabled) Color(0xFF1A2225) else Color(0xFF111517),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) Color(0xFF3B474B) else Color(0xFF252B2D))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = if (enabled) AtomosCyan else Color(0xFF50585A), fontSize = 18.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun RemoteZoomViewportPad(
    enabled: Boolean,
    zoomLevel: Int,
    zoomBox: ZoomBoxState?,
    viewportState: MonitorZoomViewportState,
    onPan: (Float, Float) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 10.dp),
        color = Color(0xFF151A1C),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("ANALOG PAN", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)

            if (zoomLevel <= 0) {
                Text("Select 1:1 or 2:1 Zoom", color = Color(0xFF959DA0), fontSize = 9.sp, modifier = Modifier.padding(top = 5.dp))
                return@Column
            }

            // The AG2 can report ZoomBox view==frame even after confirmed 2:1.
            // Mirror the macOS wholeFrameView + dragIcon interaction: keep the full
            // frame as the pad and expose a half-size movable viewport at 2:1.
            val frameW = zoomBox?.frameWidth?.takeIf { it > 0 } ?: 1920
            val frameH = zoomBox?.frameHeight?.takeIf { it > 0 } ?: 1080
            val effectiveViewW = when {
                zoomLevel == 2 && (zoomBox == null || !zoomBox.pannable) -> frameW / 2
                zoomBox?.viewportWidth?.let { it > 0 } == true -> zoomBox.viewportWidth
                else -> frameW
            }.coerceAtLeast(1)
            val effectiveViewH = when {
                zoomLevel == 2 && (zoomBox == null || !zoomBox.pannable) -> frameH / 2
                zoomBox?.viewportHeight?.let { it > 0 } == true -> zoomBox.viewportHeight
                else -> frameH
            }.coerceAtLeast(1)
            val maxLeft = (frameW - effectiveViewW).coerceAtLeast(0)
            val maxTop = (frameH - effectiveViewH).coerceAtLeast(0)
            val initialX = if (maxLeft > 0) ((zoomBox?.left ?: 0).toFloat() / maxLeft).coerceIn(0f, 1f) else 0f
            val initialY = if (maxTop > 0) ((zoomBox?.top ?: 0).toFloat() / maxTop).coerceIn(0f, 1f) else 0f
            var xFraction by remember(zoomLevel, frameW, frameH, zoomBox?.left, zoomBox?.top) { mutableFloatStateOf(initialX) }
            var yFraction by remember(zoomLevel, frameW, frameH, zoomBox?.left, zoomBox?.top) { mutableFloatStateOf(initialY) }
            var padSize by remember { mutableStateOf(IntSize.Zero) }
            val canPan = enabled && zoomLevel == 2 && maxLeft > 0 && maxTop > 0 && !viewportState.pendingPan

            Text(
                when {
                    viewportState.pendingPan -> "Verifying viewport position..."
                    canPan -> "Grab the cyan box and drag it to move the recorder zoom view"
                    zoomLevel == 1 -> "Analog movement is available in 2:1 Zoom"
                    else -> "Waiting for Zoom Analog viewport..."
                },
                color = Color(0xFF959DA0),
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )

            val aspect = (frameW.toFloat() / frameH.toFloat()).coerceIn(1f, 2.6f)
            val viewportWFrac = (effectiveViewW.toFloat() / frameW.toFloat()).coerceIn(0.08f, 1f)
            val viewportHFrac = (effectiveViewH.toFloat() / frameH.toFloat()).coerceIn(0.08f, 1f)
            val touchModifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .background(Color(0xFF090C0D), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF30383B), RoundedCornerShape(10.dp))
                .onSizeChanged { padSize = it }
                .pointerInput(canPan, padSize, viewportWFrac, viewportHFrac) {
                    if (!canPan || padSize.width <= 0 || padSize.height <= 0) return@pointerInput
                    detectDragGestures(
                        onDragEnd = { onPan(xFraction, yFraction) },
                        onDrag = { change, dragAmount ->
                            val travelX = (padSize.width.toFloat() * (1f - viewportWFrac)).coerceAtLeast(1f)
                            val travelY = (padSize.height.toFloat() * (1f - viewportHFrac)).coerceAtLeast(1f)
                            xFraction = (xFraction + dragAmount.x / travelX).coerceIn(0f, 1f)
                            yFraction = (yFraction + dragAmount.y / travelY).coerceIn(0f, 1f)
                            change.consume()
                        }
                    )
                }

            Canvas(touchModifier) {
                val boxW = size.width * viewportWFrac
                val boxH = size.height * viewportHFrac
                val left = xFraction * (size.width - boxW).coerceAtLeast(0f)
                val top = yFraction * (size.height - boxH).coerceAtLeast(0f)
                drawRect(Color(0xFF101719))
                drawRect(
                    color = AtomosCyan.copy(alpha = 0.22f),
                    topLeft = Offset(left, top),
                    size = Size(boxW, boxH)
                )
                drawRect(
                    color = AtomosCyan,
                    topLeft = Offset(left, top),
                    size = Size(boxW, boxH),
                    style = Stroke(width = 4f)
                )
                // Obvious draggable handle, analogous to the macOS dragIcon.
                drawCircle(
                    color = AtomosCyan,
                    radius = 11f,
                    center = Offset(left + boxW / 2f, top + boxH / 2f)
                )
                drawLine(
                    color = Color.Black,
                    start = Offset(left + boxW / 2f - 6f, top + boxH / 2f),
                    end = Offset(left + boxW / 2f + 6f, top + boxH / 2f),
                    strokeWidth = 2f
                )
                drawLine(
                    color = Color.Black,
                    start = Offset(left + boxW / 2f, top + boxH / 2f - 6f),
                    end = Offset(left + boxW / 2f, top + boxH / 2f + 6f),
                    strokeWidth = 2f
                )
            }
        }
    }
}

@Composable
private fun RemoteMonitorToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (enabled) Color(0xFFD8DDDE) else Color(0xFF626A6D),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun RemoteScopesCard(
    enabled: Boolean,
    scopeState: MonitorScopeState,
    onRefresh: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onModeChange: (String) -> Unit,
    onSizeChange: (String) -> Unit,
    onOpacityChange: (Int) -> Unit,
    onBrightnessChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1315))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(Color(0xFF102624), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(8.dp).background(AtomosCyan, CircleShape))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Scopes", color = Color(0xFFE9EDEE), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Waveform and vectorscope", color = Color(0xFF959DA0), fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                }
                TextButton(
                    onClick = onRefresh,
                    enabled = enabled && scopeState.pendingKey == null
                ) {
                    Text("REFRESH", color = AtomosCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFF202628))

            RemoteMonitorToggle(
                "Waveform / Scopes",
                scopeState.enabled ?: false,
                enabled && scopeState.pendingKey != "enabled",
                onEnabledChange
            )

            Text(
                scopeState.status,
                color = Color(0xFF747E81),
                fontSize = 9.sp,
                lineHeight = 12.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            Text("MODE", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(7.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                RemoteScopeChoiceButton(
                    "LUMA",
                    scopeState.mode == 1,
                    enabled && scopeState.pendingKey != "mode",
                    Modifier.weight(1f)
                ) { onModeChange("luma-parade") }
                RemoteScopeChoiceButton(
                    "RGB",
                    scopeState.mode == 2,
                    enabled && scopeState.pendingKey != "mode",
                    Modifier.weight(1f)
                ) { onModeChange("rgb-parade") }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                RemoteScopeChoiceButton(
                    "VECTOR",
                    scopeState.mode == 3,
                    enabled && scopeState.pendingKey != "mode",
                    Modifier.weight(1f)
                ) { onModeChange("vectorscope") }
                RemoteScopeChoiceButton(
                    "VECTOR ZOOM",
                    scopeState.mode == 4,
                    enabled && scopeState.pendingKey != "mode",
                    Modifier.weight(1f)
                ) { onModeChange("vectorscope-zoom") }
            }

            Spacer(Modifier.height(14.dp))
            Text("SIZE", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                RemoteScopeChoiceButton(
                    "1/4",
                    scopeState.size == 1,
                    enabled && scopeState.pendingKey != "size",
                    Modifier.weight(1f)
                ) { onSizeChange("quarter") }
                RemoteScopeChoiceButton(
                    "1/3",
                    scopeState.size == 2,
                    enabled && scopeState.pendingKey != "size",
                    Modifier.weight(1f)
                ) { onSizeChange("third") }
                RemoteScopeChoiceButton(
                    "FULL",
                    scopeState.size == 3,
                    enabled && scopeState.pendingKey != "size",
                    Modifier.weight(1f)
                ) { onSizeChange("full") }
            }


            Spacer(Modifier.height(14.dp))
            Text("OPACITY", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            val scopeOpacityReported = scopeState.opacity
            var scopeOpacityDraft by remember(scopeOpacityReported) {
                mutableFloatStateOf((scopeOpacityReported ?: 50).toFloat().coerceIn(0f, 100f))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = scopeOpacityDraft,
                    onValueChange = { scopeOpacityDraft = it },
                    onValueChangeFinished = {
                        if (scopeOpacityReported != null) onOpacityChange(scopeOpacityDraft.toInt())
                    },
                    valueRange = 0f..100f,
                    enabled = enabled && scopeOpacityReported != null && scopeState.pendingKey != "opacity",
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (scopeOpacityReported != null) "${scopeOpacityDraft.toInt()}%" else "READING",
                    color = if (scopeOpacityReported != null) AtomosCyan else Color(0xFF6F797C),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.width(58.dp)
                )
            }

            Spacer(Modifier.height(10.dp))
            Text("BRIGHTNESS", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            val scopeBrightnessReported = scopeState.brightness
            var scopeBrightnessDraft by remember(scopeBrightnessReported) {
                mutableFloatStateOf((scopeBrightnessReported ?: 50).toFloat().coerceIn(0f, 100f))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = scopeBrightnessDraft,
                    onValueChange = { scopeBrightnessDraft = it },
                    onValueChangeFinished = {
                        if (scopeBrightnessReported != null) onBrightnessChange(scopeBrightnessDraft.toInt())
                    },
                    valueRange = 0f..100f,
                    enabled = enabled && scopeBrightnessReported != null && scopeState.pendingKey != "brightness",
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (scopeBrightnessReported != null) "${scopeBrightnessDraft.toInt()}%" else "READING",
                    color = if (scopeBrightnessReported != null) AtomosCyan else Color(0xFF6F797C),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.width(58.dp)
                )
            }
        }
    }
}

@Composable
private fun RemoteScopeChoiceButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(42.dp),
            shape = RoundedCornerShape(11.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF153B38),
                contentColor = AtomosCyan,
                disabledContainerColor = Color(0xFF17201F),
                disabledContentColor = Color(0xFF5D6969)
            ),
            contentPadding = PaddingValues(horizontal = 5.dp)
        ) {
            Text(label, fontSize = 9.sp, fontWeight = FontWeight.Black)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(42.dp),
            shape = RoundedCornerShape(11.dp),
            contentPadding = PaddingValues(horizontal = 5.dp)
        ) {
            Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RemoteGuidesCard(
    enabled: Boolean,
    guideState: MonitorGuideState,
    safeAreaMode: Int,
    onRefresh: () -> Unit,
    onFrameGuideChange: (Boolean) -> Unit,
    onFrameGuideFormat: (Int) -> Unit,
    onFrameGridChange: (Boolean) -> Unit,
    onSafeAreaRefresh: () -> Unit,
    onSafeAreaMode: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1315))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(Color(0xFF102624), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) { Box(Modifier.size(8.dp).background(AtomosCyan, CircleShape)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Grid & Guides", color = Color(0xFFE9EDEE), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Frame guides and 9-grid", color = Color(0xFF959DA0), fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                }
                TextButton(
                    onClick = onRefresh,
                    enabled = enabled && guideState.pendingKey == null
                ) {
                    Text("REFRESH", color = AtomosCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFF202628))

            RemoteMonitorToggle(
                "Frame Guide",
                guideState.frameGuideEnabled ?: false,
                enabled && guideState.pendingKey != "frame-guide",
                onFrameGuideChange
            )

            Text(
                guideState.status,
                color = Color(0xFF747E81),
                fontSize = 9.sp,
                lineHeight = 12.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            val showFrameFormats = guideState.frameGuideEnabled == true ||
                (guideState.pendingKey == "frame-guide" && guideState.frameGuideEnabled == true)
            if (showFrameFormats) {
                Text("FRAME GUIDE FORMAT", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Text(
                    guideState.formatLabel(guideState.format),
                    color = AtomosCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(top = 4.dp, bottom = 7.dp)
                )

                val guideFormats = listOf(
                    1 to "16:9",
                    5 to "1.85:1",
                    4 to "1.9:1",
                    2 to "1.91:1",
                    7 to "2.35:1",
                    3 to "2.41:1",
                    6 to "4:3",
                    8 to "1:1",
                    9 to "4:5",
                    10 to "9:16"
                )
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    guideFormats.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            row.forEach { (value, label) ->
                                RemoteScopeChoiceButton(
                                    label,
                                    guideState.format == value,
                                    enabled && guideState.pendingKey != "format",
                                    Modifier.weight(1f)
                                ) { onFrameGuideFormat(value) }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }

            } else {
                Text(
                    if (guideState.pendingKey == "frame-guide") "Activating Frame Guide..." else "Turn Frame Guide on to choose a frame ratio",
                    color = Color(0xFF8C9699),
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            RemoteMonitorToggle(
                "9-Grid",
                guideState.gridEnabled ?: false,
                enabled && guideState.pendingKey != "grid",
                onFrameGridChange
            )

            if (guideState.format == 12) {
                Text(
                    "Custom guide detected Â· ratio ${guideState.customRatio ?: 13}. Custom ratio editing will be added only after the exact recorder scale is verified.",
                    color = Color(0xFF8C9699),
                    fontSize = 8.sp,
                    lineHeight = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "SAFE AREA",
                    color = Color(0xFF687174),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onSafeAreaRefresh, enabled = enabled) {
                    Text("SYNC", color = AtomosCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(7.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                RemoteScopeChoiceButton(
                    "OFF",
                    safeAreaMode == 0,
                    enabled,
                    Modifier.weight(1f)
                ) { onSafeAreaMode(0) }
                RemoteScopeChoiceButton(
                    "ACTION",
                    safeAreaMode == 1,
                    enabled,
                    Modifier.weight(1f)
                ) { onSafeAreaMode(1) }
                RemoteScopeChoiceButton(
                    "TITLE",
                    safeAreaMode == 2,
                    enabled,
                    Modifier.weight(1f)
                ) { onSafeAreaMode(2) }
            }

            Text(
                when (safeAreaMode) {
                    1 -> "Action Safe Area selected"
                    2 -> "Title Safe Area selected Â· recorder should show the Title/Action double-frame state when supported by the active format"
                    else -> "Safe Area Off"
                },
                color = if (safeAreaMode in 1..2) AtomosCyan else Color(0xFF747E81),
                fontSize = 9.sp,
                lineHeight = 12.sp,
                modifier = Modifier.padding(top = 7.dp)
            )
        }
    }
}

@Composable
private fun RemoteAnamorphicCard(
    enabled: Boolean,
    state: MonitorAnamorphicState,
    onRefresh: () -> Unit,
    onModeChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1315))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(Color(0xFF102624), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) { Box(Modifier.size(8.dp).background(AtomosCyan, CircleShape)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Anamorphic", color = Color(0xFFE9EDEE), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    Text("De-squeeze monitoring ratios", color = Color(0xFF959DA0), fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                }
                TextButton(onClick = onRefresh, enabled = enabled && state.pendingMode == null) {
                    Text("SYNC", color = AtomosCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFF202628))

            Text(
                "CURRENT Â· ${MonitorAnamorphicState.label(state.currentMode)}",
                color = AtomosCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(top = 10.dp, bottom = 5.dp)
            )
            Text(state.status, color = Color(0xFF747E81), fontSize = 8.sp, lineHeight = 11.sp)

            Spacer(Modifier.height(10.dp))
            val options = MonitorAnamorphicState.macOsModeFamily
            options.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    row.forEach { (value, label) ->
                        RemoteScopeChoiceButton(
                            label,
                            state.currentMode == value,
                            enabled && state.pendingMode == null,
                            Modifier.weight(1f)
                        ) { onModeChange(value) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(7.dp))
            }

            Text(
                "Mode family is recovered from the supplied macOS app. Selection is confirmed only by recorder readback.",
                color = Color(0xFF697275),
                fontSize = 8.sp,
                lineHeight = 11.sp
            )
        }
    }
}

@Composable
private fun RemoteMonitoringCard(
    enabled: Boolean,
    mode: Int,
    monitorCore: MonitorCoreSnapshot,
    lutState: MonitorLutState,
    onModeChange: (Int) -> Unit,
    onLutRefresh: () -> Unit,
    onLutSlot: (Int) -> Unit,
    onLutPreview: (Boolean) -> Unit,
    onLutRecord: (Boolean) -> Unit,
    onLutOutput: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1315))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(Color(0xFF102624), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(8.dp).background(AtomosCyan, CircleShape))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Monitoring", color = Color(0xFFE9EDEE), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Recorder-backed mode with confirmed readback", color = Color(0xFF959DA0), fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                }

            }
            Spacer(Modifier.height(12.dp))
            Divider(color = Color(0xFF202628))
            Spacer(Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(11.dp),
                color = Color(0xFF151A1C)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "CURRENT MODE",
                            color = Color(0xFF687174),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (monitorCore.ready) monitorModeSummary(mode) else "Waiting for recorder",
                            color = if (monitorCore.ready) AtomosCyan else Color(0xFF959DA0),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                    if (monitorCore.pendingMode != null) {
                        Text(
                            "VERIFYING",
                            color = Color(0xFF9AA5A8),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                monitorCore.status,
                color = Color(0xFF7E898C),
                fontSize = 9.sp,
                lineHeight = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RemoteScopeChoiceButton("NATIVE", mode == 1, enabled, Modifier.weight(1f)) { onModeChange(1) }
                RemoteScopeChoiceButton("REC.709", mode == 2, enabled, Modifier.weight(1f)) { onModeChange(2) }
                RemoteScopeChoiceButton("HLG", mode == 3, enabled, Modifier.weight(1f)) { onModeChange(3) }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RemoteScopeChoiceButton("PQ", mode == 4, enabled, Modifier.weight(1f)) { onModeChange(4) }
                RemoteScopeChoiceButton("LUT", mode == 5, enabled, Modifier.weight(1f)) { onModeChange(5) }
                Spacer(Modifier.weight(1f))
            }

            if (mode == 5) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF202628))
                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("LUT CONTROLS", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                        Text(
                            if (lutState.currentSlot == null) {
                                "Select LUT slot"
                            } else {
                                lutState.currentLabel()
                            },
                            color = AtomosCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                    TextButton(
                        onClick = onLutRefresh,
                        enabled = enabled && lutState.pendingKey != "refresh"
                    ) {
                        Text("SYNC", color = AtomosCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(8.dp))
                (1..8).toList().chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        row.forEach { slot ->
                            RemoteScopeChoiceButton(
                                label = slot.toString(),
                                selected = lutState.shownSlot == slot,
                                enabled = enabled && lutState.pendingKey != "slot",
                                modifier = Modifier.weight(1f)
                            ) { onLutSlot(slot) }
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Preview / Compare", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            when (lutState.previewAvailable) {
                                true -> if (lutState.shownPreviewEnabled == true) "On" else "Off"
                                false -> "Not available"
                                null -> "State not reported"
                            },
                            color = Color(0xFF7F898C), fontSize = 8.sp
                        )
                    }
                    Switch(
                        checked = lutState.shownPreviewEnabled == true,
                        onCheckedChange = onLutPreview,
                        enabled = enabled && lutState.pendingKey != "preview" && lutState.previewAvailable != false
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("During Record", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            when (lutState.recordBurnInAvailable) {
                                true -> if (lutState.shownRecordBurnInEnabled == true) "On" else "Off"
                                false -> "Not available"
                                null -> "State not reported"
                            },
                            color = Color(0xFF7F898C), fontSize = 8.sp
                        )
                    }
                    Switch(
                        checked = lutState.shownRecordBurnInEnabled == true,
                        onCheckedChange = onLutRecord,
                        enabled = enabled && lutState.pendingKey != "record" && lutState.recordBurnInAvailable != false
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("During Output", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            when (lutState.outputLutAvailable) {
                                true -> if (lutState.shownOutputLutEnabled == true) "On" else "Off"
                                false -> "Not available"
                                null -> "State not reported"
                            },
                            color = Color(0xFF7F898C), fontSize = 8.sp
                        )
                    }
                    Switch(
                        checked = lutState.shownOutputLutEnabled == true,
                        onCheckedChange = onLutOutput,
                        enabled = enabled && lutState.pendingKey != "output" && lutState.outputLutAvailable != false
                    )
                }

                Text(
                    lutState.status,
                    color = Color(0xFF747E81),
                    fontSize = 8.sp,
                    lineHeight = 11.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = Color(0xFF202628))
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(Modifier.weight(1f), shape = RoundedCornerShape(10.dp), color = Color(0xFF151A1C)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("SOURCE GAMMA", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        Text("From Input", color = Color(0xFFC4CBCE), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Surface(Modifier.weight(1f), shape = RoundedCornerShape(10.dp), color = Color(0xFF151A1C)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("SOURCE GAMUT", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        Text("From Input", color = Color(0xFFC4CBCE), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteCodecCard(
    enabled: Boolean,
    profile: Int,
    bitDepth: Int,
    confirmedProfile: Pair<Int, Int>?,
    onProfileChange: (Int) -> Unit,
    onBitDepthChange: (Int) -> Unit,
    onVerify: () -> Unit,
    onApply: () -> Unit,
    onRestartApply: () -> Unit = {}
) {
    var showRestartConfirm by remember { mutableStateOf(false) }
    val family = when (profile) {
        in 1..5 -> "ProRes"
        in 6..9 -> "DNxHD"
        10 -> "CinemaDNG"
        in 13..15 -> "H.264"
        in 16..19 -> "H.265"
        12 -> "ProRes RAW"
        else -> "ProRes"
    }

    fun selectProfile(id: Int) {
        onProfileChange(id)
    }

    fun selectFamily(name: String) {
        when (name) {
            "ProRes" -> selectProfile(if (profile in 1..5) profile else 3)
            "DNxHD" -> selectProfile(if (profile in 6..9) profile else 8)
            "CinemaDNG" -> selectProfile(10)
            "H.264" -> selectProfile(if (profile in 13..15) profile else 15)
            "H.265" -> selectProfile(if (profile in 16..19) profile else 18)
            "ProRes RAW" -> selectProfile(12)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1315))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(Color(0xFF102624), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(8.dp).background(AtomosCyan, CircleShape))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Record Codec", color = Color(0xFFE9EDEE), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    Text("macOS RemoteApi parity: non-reboot apply + recorder readback", color = Color(0xFF959DA0), fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            Divider(color = Color(0xFF202628))
            Spacer(Modifier.height(10.dp))

            Text("CODEC", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RemoteScopeChoiceButton("ProRes", family == "ProRes", enabled, Modifier.weight(1f)) { selectFamily("ProRes") }
                RemoteScopeChoiceButton("DNxHD", family == "DNxHD", enabled, Modifier.weight(1f)) { selectFamily("DNxHD") }
                RemoteScopeChoiceButton("CinemaDNG", family == "CinemaDNG", enabled, Modifier.weight(1f)) { selectFamily("CinemaDNG") }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RemoteScopeChoiceButton("H.264", family == "H.264", enabled, Modifier.weight(1f)) { selectFamily("H.264") }
                RemoteScopeChoiceButton("H.265", family == "H.265", enabled, Modifier.weight(1f)) { selectFamily("H.265") }
                RemoteScopeChoiceButton("ProRes RAW", family == "ProRes RAW", enabled, Modifier.weight(1f)) { selectFamily("ProRes RAW") }
            }

            Spacer(Modifier.height(13.dp))
            Text("COMPRESSION", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(7.dp))
            when (family) {
                "ProRes" -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RemoteScopeChoiceButton("Proxy", profile == 1, enabled, Modifier.weight(1f)) { selectProfile(1) }
                        RemoteScopeChoiceButton("LT", profile == 2, enabled, Modifier.weight(1f)) { selectProfile(2) }
                        RemoteScopeChoiceButton("422", profile == 3, enabled, Modifier.weight(1f)) { selectProfile(3) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RemoteScopeChoiceButton("HQ", profile == 4, enabled, Modifier.weight(1f)) { selectProfile(4) }
                        RemoteScopeChoiceButton("4444", profile == 5, enabled, Modifier.weight(1f)) { selectProfile(5) }
                        Spacer(Modifier.weight(1f))
                    }
                }
                "DNxHD" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RemoteScopeChoiceButton("LB", profile == 6, enabled, Modifier.weight(1f)) { selectProfile(6) }
                    RemoteScopeChoiceButton("SQ", profile == 7, enabled, Modifier.weight(1f)) { selectProfile(7) }
                    RemoteScopeChoiceButton("HQ", profile == 8, enabled, Modifier.weight(1f)) { selectProfile(8) }
                    RemoteScopeChoiceButton("HQX", profile == 9, enabled, Modifier.weight(1f)) { selectProfile(9) }
                }
                "CinemaDNG" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RemoteScopeChoiceButton("CinemaDNG", profile == 10, enabled, Modifier.weight(1f)) { selectProfile(10) }
                    Spacer(Modifier.weight(3f))
                }
                "H.264" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RemoteScopeChoiceButton("LQ", profile == 13, enabled, Modifier.weight(1f)) { selectProfile(13) }
                    RemoteScopeChoiceButton("MQ", profile == 14, enabled, Modifier.weight(1f)) { selectProfile(14) }
                    RemoteScopeChoiceButton("HQ", profile == 15, enabled, Modifier.weight(1f)) { selectProfile(15) }
                    Spacer(Modifier.weight(1f))
                }
                "H.265" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RemoteScopeChoiceButton("LQ", profile == 16, enabled, Modifier.weight(1f)) { selectProfile(16) }
                    RemoteScopeChoiceButton("MQ", profile == 17, enabled, Modifier.weight(1f)) { selectProfile(17) }
                    RemoteScopeChoiceButton("HQ", profile == 18, enabled, Modifier.weight(1f)) { selectProfile(18) }
                    RemoteScopeChoiceButton("XQ", profile == 19, enabled, Modifier.weight(1f)) { selectProfile(19) }
                }
                else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RemoteScopeChoiceButton("RAW HQ", profile == 12, enabled, Modifier.weight(1f)) { selectProfile(12) }
                    Spacer(Modifier.weight(3f))
                }
            }

            Spacer(Modifier.height(13.dp))
            Text("BIT DEPTH / CHROMA", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RemoteScopeChoiceButton("10b 4:2:2", bitDepth == 0, enabled, Modifier.weight(1f)) { onBitDepthChange(0) }
                RemoteScopeChoiceButton("8b 4:2:2", bitDepth == 1, enabled, Modifier.weight(1f)) { onBitDepthChange(1) }
                RemoteScopeChoiceButton("8b 4:2:0", bitDepth == 2, enabled, Modifier.weight(1f)) { onBitDepthChange(2) }
            }

            Spacer(Modifier.height(13.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF151A1C)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("RECORDER READBACK", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        Text(
                            confirmedProfile?.let { codecSummary(it.first) } ?: "Waiting for recorder state",
                            color = if (confirmedProfile != null) Color(0xFFC4CBCE) else Color(0xFF7A8386),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                    if (confirmedProfile?.first == profile && confirmedProfile?.second == bitDepth) {
                        Text("CONFIRMED", color = AtomosCyan, fontSize = 8.sp, fontWeight = FontWeight.Black)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onVerify,
                enabled = enabled && profile in 1..19,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("VERIFY CURRENT SELECTION", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Codec enum/raw mapping is recovered from the latest macOS SwiftProtobuf metadata. VERIFY uses the exact mapped codec + bit-depth tuple.",
                color = Color(0xFF687174), fontSize = 9.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onApply,
                enabled = enabled && profile in 1..19,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF153B38),
                    contentColor = AtomosCyan,
                    disabledContainerColor = Color(0xFF17201F),
                    disabledContentColor = Color(0xFF5D6969)
                )
            ) {
                Text("APPLY CODEC", fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.7.sp)
            }
            Text(
                "Normal Apply uses macOS NonRebootCodecProfileSet 0x013F with the recovered raw codec enum. A separate 0x013A tuple readback confirms the result.",
                color = Color(0xFF687174), fontSize = 9.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showRestartConfirm = true },
                enabled = enabled && profile in 1..19,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("APPLY + RESTART", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showRestartConfirm) {
        AlertDialog(
            onDismissRequest = { showRestartConfirm = false },
            title = { Text("Apply codec with recorder restart?") },
            text = {
                Text("This uses the restart-capable codec path recovered from AtomRemote. Recording must be stopped before continuing.")
            },
            confirmButton = {
                Button(onClick = {
                    showRestartConfirm = false
                    onRestartApply()
                }) { Text("APPLY + RESTART") }
            },
            dismissButton = {
                TextButton(onClick = { showRestartConfirm = false }) { Text("CANCEL") }
            }
        )
    }
}

@Composable
private fun RemoteDisplayCard(
    enabled: Boolean,
    state: MonitorDisplayState,
    onRefresh: () -> Unit,
    onBacklight: (Int) -> Unit,
    onFlip: (Boolean) -> Unit,
    onTally: (Boolean) -> Unit,
    onLift: (Int) -> Unit,
    onGamma: (Int) -> Unit,
    onGain: (Int) -> Unit,
    onReset: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1315))) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Display", color = Color(0xFFE9EDEE), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Recorder-backed display controls", color = Color(0xFF959DA0), fontSize = 10.sp)
                }
                TextButton(onClick = onRefresh, enabled = enabled && state.pendingKey == null) { Text("SYNC", color = AtomosCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold) }
            }
            HorizontalDivider(color = Color(0xFF202628))
            Spacer(Modifier.height(10.dp))

            fun pending(key: String) = state.pendingKey == key
            var backlightUi by remember(state.shownBacklightPercent, state.requestedBacklightPercent) {
                mutableFloatStateOf((state.shownBacklightPercent ?: state.requestedBacklightPercent ?: 50).toFloat())
            }
            Text(
                "Backlight / Brightness  ${state.shownBacklightPercent?.let { "${backlightUi.toInt()}%" } ?: "Not reported - drag to set"}",
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold
            )
            Text(state.backlightCapabilityText, color = Color(0xFF747E81), fontSize = 8.sp)
            Slider(
                value = backlightUi,
                onValueChange = { backlightUi = it.coerceIn(0f, 100f) },
                onValueChangeFinished = { onBacklight(backlightUi.toInt()) },
                valueRange = 0f..100f,
                steps = 99,
                enabled = enabled && state.backlightChangeable != false && !pending("backlight")
            )

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Flip / Orientation", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(state.flipCapabilityText, color = Color(0xFF747E81), fontSize = 8.sp)
                }
                Switch(checked = state.shownFlipEnabled == true, onCheckedChange = onFlip, enabled = enabled && !pending("flip") && state.flipSupported != false)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tally", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(state.tallyCapabilityText, color = Color(0xFF747E81), fontSize = 8.sp)
                }
                Switch(checked = state.shownTallyEnabled == true, onCheckedChange = onTally, enabled = enabled && !pending("tally") && state.tallySupported != false)
            }

            Spacer(Modifier.height(8.dp))
            var liftUi by remember(state.shownYLift) { mutableFloatStateOf((state.shownYLift ?: 0).toFloat()) }
            Text("Y Lift  ${liftUi.toInt()}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Slider(
                value = liftUi,
                onValueChange = { liftUi = (it.toInt() / 2 * 2).coerceIn(-64, 64).toFloat() },
                onValueChangeFinished = { onLift(liftUi.toInt()) },
                valueRange = -64f..64f,
                steps = 63,
                enabled = enabled && !pending("lift")
            )

            var gammaUi by remember(state.shownYGammaHundredths) { mutableFloatStateOf((state.shownYGammaHundredths ?: 240).toFloat()) }
            Text("Y Gamma  ${String.format("%.2f", gammaUi / 100f)}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Slider(
                value = gammaUi,
                onValueChange = { gammaUi = (it.toInt() / 2 * 2).coerceIn(176, 304).toFloat() },
                onValueChangeFinished = { onGamma(gammaUi.toInt()) },
                valueRange = 176f..304f,
                steps = 63,
                enabled = enabled && !pending("gamma")
            )

            var gainUi by remember(state.shownYGainHundredths) { mutableFloatStateOf((state.shownYGainHundredths ?: 100).toFloat()) }
            Text("Y Gain  ${String.format("%.2fx", gainUi / 100f)}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Slider(
                value = gainUi,
                onValueChange = { gainUi = it.toInt().coerceIn(50, 200).toFloat() },
                onValueChangeFinished = { onGain(gainUi.toInt()) },
                valueRange = 50f..200f,
                steps = 149,
                enabled = enabled && !pending("gain")
            )

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onReset, enabled = enabled && state.pendingKey == null, modifier = Modifier.fillMaxWidth()) { Text("RESET PICTURE") }
            if (state.pendingKey != null || state.status.contains("unavailable", ignoreCase = true) || state.status.contains("waiting", ignoreCase = true)) {
                Text(state.status, color = Color(0xFF747E81), fontSize = 8.sp, lineHeight = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
            if (state.pendingKey != null) Text("Requested state is shown while waiting; it is not hardware-confirmed until recorder readback arrives.", color = Color(0xFF7F898C), fontSize = 8.sp)
        }
    }
}

@Composable
private fun RemoteInputFormatCard(
    enabled: Boolean,
    inputFormatInfo: String?,
    codecId: Int?,
    cameraId: Int?,
    gammaId: Int?,
    gamutId: Int?,
    outputInfo: String?,
    outputHdrTypeId: Int?,
    outputGamutId: Int?,
    rawHex: String?,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1315))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(Color(0xFF102624), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(8.dp).background(AtomosCyan, CircleShape))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Input / Format", color = Color(0xFFE9EDEE), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Input, gamma, gamut and output information", color = Color(0xFF959DA0), fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                }

            }

            Spacer(Modifier.height(12.dp))
            Divider(color = Color(0xFF202628))
            Spacer(Modifier.height(10.dp))

            RemoteInfoRow("Input Format", inputFormatInfo ?: "Not reported by recorder")
            RemoteInfoRow("Output", outputInfo ?: "Not reported")

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onRefresh,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF153B38), contentColor = AtomosCyan,
                    disabledContainerColor = Color(0xFF17201F), disabledContentColor = Color(0xFF5D6969)
                )
            ) {
                Text("REFRESH", fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 0.7.sp)
            }



        }
    }
}

@Composable
private fun RemoteInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = Color(0xFF7F898C), fontSize = 9.sp, modifier = Modifier.weight(0.42f))
        Text(value, color = Color(0xFFC8CFD1), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.58f))
    }
}


// 19.12.6 pre-integration UI polish: keep hardware transport behavior unchanged.
// Normal module screens now use recorder-facing language only; protocol/debug evidence
// stays in package notes and logcat rather than the production control surface.

private data class ModuleTab(
    val screen: AtomosViewModel.Screen,
    val label: String
)

// AGENT7 20.03.4.11 TWO-ROW TOP TABS
private val remoteModuleTabs = listOf(
    ModuleTab(AtomosViewModel.Screen.REMOTE, "Remote"),
    ModuleTab(AtomosViewModel.Screen.INPUT, "Input"),
    ModuleTab(AtomosViewModel.Screen.AUDIO, "Audio"),
    ModuleTab(AtomosViewModel.Screen.MEDIA, "Media"),
    ModuleTab(AtomosViewModel.Screen.OUTPUT, "Output"),
    ModuleTab(AtomosViewModel.Screen.SETTINGS, "Settings")
)

@Composable
private fun RemoteModuleIcon(screen: AtomosViewModel.Screen, selected: Boolean) {
    val color = if (selected) AtomosCyan else Color(0xFF6F797C)
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.8.dp.toPx())

        when (screen) {
            AtomosViewModel.Screen.REMOTE -> {
                drawRoundRect(color, Offset(w * 0.12f, h * 0.26f), Size(w * 0.56f, h * 0.48f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = stroke)
                drawLine(color, Offset(w * 0.70f, h * 0.38f), Offset(w * 0.88f, h * 0.28f), stroke.width)
                drawLine(color, Offset(w * 0.70f, h * 0.62f), Offset(w * 0.88f, h * 0.72f), stroke.width)
                drawLine(color, Offset(w * 0.88f, h * 0.28f), Offset(w * 0.88f, h * 0.72f), stroke.width)
            }
            AtomosViewModel.Screen.INPUT -> {
                drawRoundRect(color, Offset(w * 0.12f, h * 0.24f), Size(w * 0.76f, h * 0.52f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = stroke)
                drawLine(color, Offset(w * 0.25f, h * 0.50f), Offset(w * 0.52f, h * 0.50f), stroke.width)
                drawLine(color, Offset(w * 0.44f, h * 0.40f), Offset(w * 0.54f, h * 0.50f), stroke.width)
                drawLine(color, Offset(w * 0.44f, h * 0.60f), Offset(w * 0.54f, h * 0.50f), stroke.width)
            }
            AtomosViewModel.Screen.AUDIO -> {
                drawLine(color, Offset(w * 0.20f, h * 0.62f), Offset(w * 0.20f, h * 0.38f), stroke.width)
                drawLine(color, Offset(w * 0.40f, h * 0.76f), Offset(w * 0.40f, h * 0.24f), stroke.width)
                drawLine(color, Offset(w * 0.60f, h * 0.68f), Offset(w * 0.60f, h * 0.32f), stroke.width)
                drawLine(color, Offset(w * 0.80f, h * 0.58f), Offset(w * 0.80f, h * 0.42f), stroke.width)
            }
            AtomosViewModel.Screen.MEDIA -> {
                drawRoundRect(color, Offset(w * 0.14f, h * 0.18f), Size(w * 0.72f, h * 0.64f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = stroke)
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.42f, h * 0.34f)
                    lineTo(w * 0.68f, h * 0.50f)
                    lineTo(w * 0.42f, h * 0.66f)
                    close()
                }
                drawPath(path, color)
            }
            AtomosViewModel.Screen.OUTPUT -> {
                drawRoundRect(color, Offset(w * 0.12f, h * 0.24f), Size(w * 0.76f, h * 0.52f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = stroke)
                drawLine(color, Offset(w * 0.48f, h * 0.50f), Offset(w * 0.75f, h * 0.50f), stroke.width)
                drawLine(color, Offset(w * 0.66f, h * 0.40f), Offset(w * 0.76f, h * 0.50f), stroke.width)
                drawLine(color, Offset(w * 0.66f, h * 0.60f), Offset(w * 0.76f, h * 0.50f), stroke.width)
            }
            AtomosViewModel.Screen.SETTINGS -> {
                drawCircle(color, radius = w * 0.29f, center = Offset(w * 0.50f, h * 0.50f), style = stroke)
                drawCircle(color, radius = w * 0.09f, center = Offset(w * 0.50f, h * 0.50f), style = stroke)
                repeat(4) { index ->
                    val horizontal = index % 2 == 0
                    if(horizontal){
                        drawLine(color, Offset(w * 0.06f, h * 0.50f), Offset(w * 0.20f, h * 0.50f), stroke.width)
                        drawLine(color, Offset(w * 0.80f, h * 0.50f), Offset(w * 0.94f, h * 0.50f), stroke.width)
                    } else {
                        drawLine(color, Offset(w * 0.50f, h * 0.06f), Offset(w * 0.50f, h * 0.20f), stroke.width)
                        drawLine(color, Offset(w * 0.50f, h * 0.80f), Offset(w * 0.50f, h * 0.94f), stroke.width)
                    }
                }
            }
            else -> {
                drawCircle(color, radius = w * 0.24f, center = Offset(w * 0.50f, h * 0.50f), style = stroke)
            }
        }
    }
}

@Composable
private fun RemoteModuleNavBar(vm: AtomosViewModel, current: AtomosViewModel.Screen) {
    NavigationBar(
        containerColor = Color(0xFF0A0D0F),
        tonalElevation = 0.dp
    ) {
        remoteModuleTabs.forEach { tab ->
            val selected = current == tab.screen
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) vm.go(tab.screen)
                },
                icon = {
                    Box(
                        Modifier
                            .size(width = 32.dp, height = 24.dp)
                            .background(
                                if (selected) Color(0xFF173331) else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        RemoteModuleIcon(tab.screen, selected)
                    }
                },
                label = {
                    Text(
                        tab.label,
                        fontSize = 8.sp,
                        maxLines = 1,
                        color = if (selected) AtomosCyan else Color(0xFF778184)
                    )
                },
                alwaysShowLabel = true
            )
        }
    }
}

// TCS19.12.17 NAV ICONS: UI-only custom icons, no transport behavior.
@Composable
private fun ModuleShell(
    vm: AtomosViewModel,
    current: AtomosViewModel.Screen,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val pairing by vm.tcsPairingState.collectAsState()
    val remote by vm.tcsRemoteState.collectAsState()
    val stateSnapshot by vm.remoteStateSnapshot.collectAsState()
    val gattState by vm.gattState.collectAsState()
    val hardDisconnected = gattState == "idle" || gattState == "disconnected" ||
        gattState.startsWith("error:") || gattState.startsWith("service-error:")
    val linked = pairing.acceptWritten && remote.available && !hardDisconnected

    val pageBody: @Composable ColumnScope.() -> Unit = {
        ModulePageHero(title = title, recorder = subtitle, current = current, linked = linked)
        ModuleSessionStrip(
            linked = linked,
            statePages = stateSnapshot.receivedPages,
            expectedStatePages = stateSnapshot.expectedPages,
            stateComplete = stateSnapshot.cycleComplete
        )
        content()
        Text(
            "Only recorder-backed state and existing controls are shown. Unsupported values remain unavailable.",
            color = Color(0xFF586C71),
            fontSize = 8.5.sp,
            lineHeight = 12.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )
    }

    if (LocalConnectedModuleEmbedded.current) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF02090C))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            pageBody()
        }
        return
    }

    BackHandler(enabled = true) { vm.go(AtomosViewModel.Screen.REMOTE) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF02090C))
            .navigationBarsPadding()
    ) {
        AppHeader(title = title, subtitle = subtitle, onBack = { vm.go(AtomosViewModel.Screen.REMOTE) })
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            pageBody()
        }
        RemoteModuleNavBar(vm = vm, current = current)
    }
}

@Composable
private fun ModulePageHero(
    title: String,
    recorder: String,
    current: AtomosViewModel.Screen,
    linked: Boolean
) {
    val code = when (current) {
        AtomosViewModel.Screen.INPUT -> "IN"
        AtomosViewModel.Screen.AUDIO -> "AU"
        AtomosViewModel.Screen.MEDIA -> "MD"
        AtomosViewModel.Screen.OUTPUT -> "OUT"
        AtomosViewModel.Screen.SETTINGS -> "SET"
        else -> "AR"
    }
    val description = when (current) {
        AtomosViewModel.Screen.INPUT -> "Source, signal, HDR and recorder input state"
        AtomosViewModel.Screen.AUDIO -> "Recorder-backed audio routing and channel state"
        AtomosViewModel.Screen.MEDIA -> "Recording media and recorder storage state"
        AtomosViewModel.Screen.OUTPUT -> "Recorder output signal and supported output state"
        AtomosViewModel.Screen.SETTINGS -> "Recorder identity, synchronization and device settings"
        else -> "Recorder module"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF051519),
        border = androidx.compose.foundation.BorderStroke(1.dp, AtomosCyan.copy(alpha = 0.48f))
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(Color(0xFF08272C), RoundedCornerShape(12.dp))
                    .border(1.dp, AtomosCyan.copy(alpha = 0.55f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(code, color = AtomosCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title.uppercase(), color = AtomosCyan, fontSize = 8.5.sp, fontWeight = FontWeight.Black, letterSpacing = 0.9.sp)
                Text(description, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(recorder, color = Color(0xFF667C81), fontSize = 8.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                if (linked) "LIVE" else "WAIT",
                color = if (linked) Color(0xFF57E8BE) else Color(0xFF89979A),
                fontSize = 8.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun ModuleSessionStrip(
    linked: Boolean,
    statePages: Int,
    expectedStatePages: Int,
    stateComplete: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF071317),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF19383F))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(8.dp).background(
                    if (linked) AtomosCyan else Color(0xFF596266),
                    CircleShape
                )
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (linked) "RECORDER LINKED" else "WAITING FOR RECORDER",
                    color = if (linked) AtomosCyan else Color(0xFF8A9497),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp
                )
                Text(
                    if (linked) {
                        "Recorder linked. Live module data is loaded from supported RemoteApi getters on demand."
                    } else "Controls stay read-only until the Remote session is ready.",
                    color = Color(0xFF747E81),
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun ModuleSection(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF071216)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1A353C))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(22.dp).background(AtomosCyan, RoundedCornerShape(99.dp)))
                Spacer(Modifier.width(9.dp))
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (subtitle != null) {
                Text(subtitle, color = Color(0xFF758B90), fontSize = 9.sp, lineHeight = 13.sp)
            }
            Divider(color = Color(0xFF173238))
            content()
        }
    }
}

@Composable
private fun ModuleValueRow(label: String, value: String, accent: Boolean = false) {
    val pending = value.startsWith("Not reported", ignoreCase = true) ||
        value.startsWith("Waiting", ignoreCase = true) ||
        value.startsWith("Awaiting", ignoreCase = true) ||
        value == "Not active" || value == "None"
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF09171B), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xFF7D9297), fontSize = 9.5.sp, modifier = Modifier.weight(0.44f))
        Text(
            value,
            color = when {
                accent -> AtomosCyan
                pending -> Color(0xFF6F8085)
                else -> Color(0xFFDCE5E6)
            },
            fontSize = 9.5.sp,
            fontWeight = if (accent) FontWeight.ExtraBold else FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.56f)
        )
    }
}

@Composable
private fun ModuleFeatureChips(values: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.chunked(3).forEach { rowValues ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowValues.forEach { value ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF151A1C),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF252C2E))
                    ) {
                        Text(
                            value,
                            color = Color(0xFF9CA5A8),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 9.dp)
                        )
                    }
                }
                repeat(3 - rowValues.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun semanticInputSource(raw:String?):String =
    InputSourceCatalog.labelFor(raw)

private fun semanticHdrState(raw:String?):String {
    if (raw.isNullOrBlank()) return "Not reported"
    val id = Regex("""\bID\s+(\d+)\b""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: raw.trim().toIntOrNull()
    return when(id){
        0 -> "Off"
        1 -> "On"
        else -> raw.substringBefore(" Â· raw ").substringBefore(" Ãƒâ€š| raw ").ifBlank { "Not reported" }
    }
}

private fun semanticDeviceName(raw:String?, fallback:String):String {
    if (raw.isNullOrBlank()) return fallback
    val parts = raw.split(" Â· ", " Ãƒâ€š| ")
    val printable = parts.firstOrNull { part ->
        part.isNotBlank() &&
        !part.startsWith("type 0x", ignoreCase=true) &&
        !part.startsWith("ID ", ignoreCase=true) &&
        !part.startsWith("raw ", ignoreCase=true)
    }
    return printable?.trim()?.takeIf { it.isNotBlank() } ?: fallback
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputModuleScreen(vm: AtomosViewModel) {
    DisposableEffect(Unit) {
        vm.setInputTabVisible(true)
        onDispose { vm.setInputTabVisible(false) }
    }
    val selected by vm.selected.collectAsState()
    val connectedRecorderName by vm.connectedRecorderName.collectAsState()
    val remote by vm.tcsRemoteState.collectAsState()
    val pairing by vm.tcsPairingState.collectAsState()
    val collector by vm.inputCodecCollector.collectAsState()
    val extendedInput by vm.inputExtendedSnapshot.collectAsState()
    val inputSetter by vm.inputSetterState.collectAsState()
    val inputSession by vm.inputSessionSnapshot.collectAsState()
    val inputFormatInfo by vm.inputFormatInfo.collectAsState()
    val inputCodecId by vm.inputCodecId.collectAsState()
    val inputOutputRawHex by vm.inputOutputRawHex.collectAsState()

    val signalView = remember(inputFormatInfo, inputCodecId, inputOutputRawHex) {
        InputSignalPresenter.build(
            inputFormatInfo = inputFormatInfo,
            inputCodecId = inputCodecId,
            rawHex = inputOutputRawHex
        )
    }

    val recorderName = connectedRecorderName?.takeIf { it.isNotBlank() }
        ?: selected?.let { vm.displayNameFor(it) }
        ?: "ATOMOS Recorder"

    val ready = pairing.acceptWritten && remote.available

    // 20.12.5: Input performs one recorder refresh on tab entry; previous confirmed values remain visible.
    LaunchedEffect(ready, selected?.address) {
        if (ready) {
            // Fallback only if the connect-time prefetch was delayed. This call
            // is session-idempotent and does nothing when the snapshot is ready.
            vm.ensureInputSessionSnapshot("input-tab-fallback")
        }
    }

    // INPUT18: temporary UI-only transition loader; never fabricates GATT data.
    var input18EntryLoader by remember(selected?.address) { mutableStateOf(true) }
    LaunchedEffect(ready, selected?.address) {
        input18EntryLoader = true
        if (ready) {
            delay(2400L)
            input18EntryLoader = false
        }
    }

    val inputPreparing =
        ready &&
            !inputSession.ready &&
            (
                inputSession.phase.name.startsWith("PREFETCHING") ||
                    collector.running ||
                    extendedInput.running
            )

    val values = collector.inputValues
    // 20.03.5.2: semantic presentation lives in UI/backend only.
    // GattClient is the exact frozen 20.03.4.13 hardware-proven core.
    val source = semanticInputSource(values["source"])
    val hdrState = semanticHdrState(values["hdrState"])
    val deviceName = semanticDeviceName(values["deviceName"], recorderName)

    // INPUT BATCH 20.03.6.4-6.6. Presentation-only, frozen transport.
    val identityView = remember(values["deviceName"], recorderName, collector.inputResponses) {
        InputDeviceIdentityPresenter.build(
            raw = values["deviceName"],
            recorderName = recorderName,
            responses = collector.inputResponses
        )
    }
    val hdrView = remember(
        values["hdrState"],
        values["hdrAuto"],
        collector.inputResponses
    ) {
        InputHdrStatePresenter.build(
            rawState = values["hdrState"],
            rawAuto = values["hdrAuto"],
            responses = collector.inputResponses
        )
    }
    val hdrCamera = remember(values["hdrCamera"]) {
        InputHdrCameraPresenter.display(values["hdrCamera"])
    }
    val hdrGamma = remember(values["hdrGamma"]) {
        InputHdrGammaPresenter.display(values["hdrGamma"])
    }
    val hdrGamut = remember(values["hdrGamut"]) {
        InputHdrGamutPresenter.display(values["hdrGamut"])
    }
    val hdrCapabilities = remember(hdrCamera, hdrGamma, hdrGamut) {
        InputHdrCapabilitiesPresenter.build(
            camera = hdrCamera,
            gamma = hdrGamma,
            gamut = hdrGamut
        )
    }
    val extendedView = remember(extendedInput) {
        InputExtendedPresenter.build(extendedInput)
    }
    val hdrAutoValue =
        if (extendedView.hdrAuto != "Not reported by recorder") {
            extendedView.hdrAuto
        } else {
            hdrView.hdrAuto
        }
    val finalHdmiStandard = InputFinalPresenter.current(extendedView.hdmiStandard)
    val finalHdmiOptions = InputFinalPresenter.capability(
        extendedView.hdmiStandardOptions,
        extendedView.rawPayloads["hdmiStandardList"]
    )
    val finalTriggerSource = InputFinalPresenter.current(extendedView.triggerSource)
    val finalTriggerEnabled = InputFinalPresenter.current(extendedView.triggerEnabled)
    val finalCamera = InputFinalPresenter.current(extendedView.camera)
    val finalGamma = InputFinalPresenter.current(extendedView.gamma)
    val finalGamut = InputFinalPresenter.current(extendedView.gamut)
    val finalCameraOptions = InputFinalPresenter.capability(
        extendedView.cameraOptions,
        extendedView.rawPayloads["hdrCameraList"]
    )
    val finalGammaOptions = InputFinalPresenter.capability(
        extendedView.gammaOptions,
        extendedView.rawPayloads["hdrGammaList"]
    )
    val finalGamutOptions = InputFinalPresenter.capability(
        extendedView.gamutOptions,
        extendedView.rawPayloads["hdrGamutList"]
    )
    val finalSdiLink = InputFinalPresenter.capability(
        extendedView.sdiLink,
        extendedView.rawPayloads["sdiLink"]
    )
    val finalActiveSdi = InputFinalPresenter.capability(
        extendedView.activeSdiSource,
        extendedView.rawPayloads["activeSdiSource"]
    )
    val finalInputRange = InputFinalPresenter.capability(
        extendedView.inputRange,
        extendedView.rawPayloads["inputRange"]
    )
    val finalRawInput = InputFinalPresenter.current(extendedView.rawInput)
    val finalCameraMetadata = InputFinalPresenter.current(extendedView.cameraMetadata)

    // INPUT UI REDESIGN ONLY: all device state and setters are supplied by the unchanged VM.
    // Do not optimistically overwrite recorder readback when an option is selected.
    var inputSheet by remember { mutableStateOf<String?>(null) }
    // INPUT05C.1: Background GET collectors do not disable Input option sheets.
    // A real pending SET still blocks conflicting writes until confirmed/failed.
    val canEdit = ready && !inputSetter.busy
    val unknown = "Not reported by recorder"
    fun clean(v: String): String = if (v.isBlank() || v == unknown || v == "Not reported") "Not available" else v
    val rows = listOf(
        Triple("Input Source", clean(source), "source"),
        Triple("Source Name", clean(signalView.format), "signal"),
        Triple("Input Range", clean(finalInputRange.text), "range"),
        Triple("HDR", clean(hdrView.hdr), "hdr"),
        Triple("Record Trigger", clean(finalTriggerEnabled.text), "trigger"),
        Triple("Device Name", clean(deviceName), "device"),
        Triple("HDMI Standard", clean(finalHdmiStandard.text), "hdmi"),
        Triple("Image / Camera", clean(finalCamera.text), "camera"),
        Triple("Gamma", clean(finalGamma.text), "gamma"),
        Triple("Gamut", clean(finalGamut.text), "gamut"),
        Triple("SDI Input", clean(finalActiveSdi.text), "sdi"),
        Triple("RAW Input", clean(finalRawInput.text), "raw"),
        Triple("Camera Metadata", clean(finalCameraMetadata.text), "metadata")
    )
    ModuleShell(
        vm = vm,
        current = AtomosViewModel.Screen.INPUT,
        title = "Input",
        subtitle = recorderName
    ) {
        // INPUT18: one Input-only status for core and details.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("INPUT STATUS", color = Color(0xFF8B9497), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                if (!ready) "CONNECTING" else
                    "${collector.inputResponses}/${collector.inputExpected}  ·  ${extendedInput.completed}/${extendedInput.expected} details" +
                        if (collector.running || extendedInput.running || inputPreparing) "  ·  SYNCING" else "  ·  READY",
                color = if (ready) AtomosCyan else Color(0xFF93A6AA),
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold
            )
        }
        if (ready && input18EntryLoader) {
            RecorderSettingsLoader("Loading Input settings", "Syncing Source, HDR and Details")
        }
        // No additional reads on row taps. Prefetch/cached states remain unchanged.
        if (!ready) {
            Text("Recorder not connected", color = Color(0xFF9CAFB4), fontSize = 12.sp)
        } else if (inputPreparing) {
            Text("Syncing recorder values · showing available readings", color = Color(0xFF93A6AA), fontSize = 11.sp)
        }
        if (!input18EntryLoader) rows.forEachIndexed { index, entry ->
            val (label, value, key) = entry
            Row(
                Modifier.fillMaxWidth()
                    .padding(bottom = 7.dp)
                    .border(
                        width = if (index == 0) 1.5.dp else 1.dp,
                        color = if (index == 0) AtomosCyan else Color(0xFF30474E),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .background(Color(0xFF06161B), RoundedCornerShape(12.dp))
                    .clickable { inputSheet = key }
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("◇", color = AtomosCyan, fontSize = 21.sp)
                Spacer(Modifier.width(15.dp))
                Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Text(value, color = if (index == 0) AtomosCyan else Color(0xFFCBD8DC),
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp))
                Spacer(Modifier.width(10.dp))
                Text("›", color = Color.White, fontSize = 24.sp)
            }
        }
        // Manual user refresh is explicit: no duplicated auto-polling from this UI.
        TextButton(
            onClick = { vm.refreshInputSessionSnapshotFromUi() },
            enabled = canEdit,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Refresh input readings", color = AtomosCyan, fontSize = 12.sp) }
    }

    if (inputSheet != null) {
        val key = inputSheet!!
        val row = rows.firstOrNull { it.third == key }
        val options: List<InputEditCatalog.Option> = when (key) {
            "source" -> InputEditCatalog.sourceOptions
            "range" -> InputEditCatalog.inputRangeOptions
            "hdr" -> listOf(InputEditCatalog.Option(0, "Off"), InputEditCatalog.Option(1, "On"))
            "trigger" -> listOf(InputEditCatalog.Option(0, "Off"), InputEditCatalog.Option(1, "On"))
            "hdmi" -> InputEditCatalog.hdmiStandardOptions
            "camera" -> InputEditCatalog.hdrCameraOptions
            "gamma" -> InputEditCatalog.hdrGammaOptions
            "gamut" -> InputEditCatalog.hdrGamutOptions
            "sdi" -> InputEditCatalog.sdiSourceOptions
            else -> emptyList()
        }
        val permitted = when (key) {
            "hdmi" -> canEdit && source.contains("HDMI", ignoreCase = true)
            "hdr" -> canEdit && hdrView.control == "Available"
            else -> canEdit
        }
        ModalBottomSheet(
            onDismissRequest = { inputSheet = null },
            containerColor = Color(0xFF06161B),
            contentColor = Color.White
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                Text(row?.first ?: "Input", color = Color.White,
                    fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Current: ${row?.second ?: "Not available"}", color = AtomosCyan, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                if (options.isEmpty()) {
                    Text("Recorder information (read-only)", color = Color(0xFFB0C2C6), fontSize = 13.sp)
                } else {
                    options.forEach { option ->
                        val active = when (key) {
                            "hdr" -> readOnlyBooleanMatch(hdrView.hdr) == option.label
                            "trigger" -> readOnlyBooleanMatch(finalTriggerEnabled.text) == option.label
                            else -> readOnlyMatch(row?.second.orEmpty(), listOf(option.label)) != null
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                .border(1.dp, if (active) AtomosCyan else Color(0xFF30474E), RoundedCornerShape(10.dp))
                                .clickable(enabled = permitted && !active) {
                                    when (key) {
                                        "source", "sdi" -> vm.setInputSource(option.value, option.label)
                                        "range" -> vm.setInputRange(option.value, option.label)
                                        "hdr" -> vm.setInputHdrEnabled(option.value == 1)
                                        "trigger" -> vm.setInputTriggerEnabled(option.value == 1)
                                        "hdmi" -> vm.setInputHdmiStandard(option.value, option.label)
                                        "camera" -> vm.setInputHdrCamera(option.value, option.label)
                                        "gamma" -> vm.setInputHdrGamma(option.value, option.label)
                                        "gamut" -> vm.setInputHdrGamut(option.value, option.label)
                                    }
                                    inputSheet = null
                                }
                                .padding(horizontal = 17.dp, vertical = 17.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (active) "◉" else "○", color = if (active) AtomosCyan else Color.LightGray, fontSize = 22.sp)
                            Spacer(Modifier.width(15.dp))
                            Text(option.label, color = if (permitted) Color.White else Color.Gray,
                                fontSize = 15.sp, modifier = Modifier.weight(1f))
                        }
                    }
                    if (!permitted) Text("Selection unavailable until recorder is ready / current operation finishes.",
                        color = Color(0xFF93A6AA), fontSize = 11.sp)
                }
            }
        }
    }
}

private fun readOnlyMatch(current: String, options: List<String>): String? {
    val normalized = current.trim().lowercase()
    return options.firstOrNull { option ->
        normalized == option.lowercase() || normalized.contains(option.lowercase())
    }
}

private fun readOnlyBooleanMatch(current: String): String? {
    val normalized = current.trim().lowercase()
    return when {
        normalized == "1" || normalized == "true" || normalized == "on" ||
            normalized.contains("enabled") -> "On"
        normalized == "0" || normalized == "false" || normalized == "off" ||
            normalized.contains("disabled") -> "Off"
        else -> null
    }
}

@Composable
private fun EditableInputOptions(
    options: List<InputEditCatalog.Option>,
    active: String?,
    enabled: Boolean,
    onSelect: (InputEditCatalog.Option) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        options.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                row.forEach { option ->
                    val selected = active?.equals(option.label, ignoreCase = true) == true
                    OutlinedButton(
                        onClick = { onSelect(option) },
                        enabled = enabled && !selected,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(11.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (selected) AtomosCyan else Color(0xFF314044)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (selected) AtomosCyan else Color(0xFFB8C0C2),
                            disabledContentColor = if (selected) AtomosCyan else Color(0xFF657174)
                        )
                    ) {
                        Text(
                            option.label,
                            fontSize = 9.sp,
                            fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EditableInputDropdown(
    title: String,
    options: List<InputEditCatalog.Option>,
    active: String?,
    enabled: Boolean,
    onSelect: (InputEditCatalog.Option) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { expanded = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(11.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF314044))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                title,
                color = Color(0xFF8C989B),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                active ?: "Choose option",
                color = if (active.isNullOrBlank()) Color(0xFFB8C0C2) else AtomosCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    if (expanded) {
        Dialog(onDismissRequest = { expanded = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF11171A),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF314044))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                title.uppercase(),
                                color = Color(0xFF8C989B),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.7.sp
                            )
                            if (!active.isNullOrBlank()) {
                                Text(
                                    "Current: $active",
                                    color = AtomosCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        TextButton(onClick = { expanded = false }) {
                            Text("CLOSE", fontSize = 9.sp, fontWeight = FontWeight.Black)
                        }
                    }

                    HorizontalDivider(color = Color(0xFF253034))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 410.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(options, key = { option -> "${title}-${option.value}" }) { option ->
                            val selected = active?.equals(option.label, ignoreCase = true) == true
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = enabled && !selected) {
                                        expanded = false
                                        onSelect(option)
                                    },
                                shape = RoundedCornerShape(11.dp),
                                color = if (selected) Color(0xFF102523) else Color(0xFF0C1214),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (selected) AtomosCyan else Color(0xFF263236)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        option.label,
                                        modifier = Modifier.weight(1f),
                                        color = if (selected) AtomosCyan else Color(0xFFD0D6D8),
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold
                                    )
                                    if (selected) {
                                        Text(
                                            "CURRENT",
                                            color = AtomosCyan,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InputSetterStatusRow(
    status: String,
    busy: Boolean,
    confirmed: Boolean
) {
    if (status == "Ready") return
    Text(
        status,
        color = when {
            busy -> AtomosCyan
            confirmed -> Color(0xFF8DD9A5)
            else -> Color(0xFFB8C0C2)
        },
        fontSize = 9.sp,
        lineHeight = 13.sp
    )
}

@Composable
private fun ReadOnlyInputOptions(
    options: List<String>,
    active: String?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        options.forEach { option ->
            val selected = active == option
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(11.dp),
                color = if (selected) Color(0xFF15312F) else Color(0xFF13181A),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (selected) AtomosCyan else Color(0xFF283033)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .border(
                                1.dp,
                                if (selected) AtomosCyan else Color(0xFF596367),
                                CircleShape
                            )
                            .background(
                                if (selected) AtomosCyan else Color.Transparent,
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        option,
                        color = if (selected) AtomosCyan else Color(0xFF7A8588),
                        fontSize = 9.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// TCS19.12.17 INPUT READ-ONLY WIRING:
// Existing granular recorder getters feed the existing Input tab and its inner UI.
// No setter, command ID, PacketLink rule or pairing behavior is added here.


// AUDIO R26: macOS and recorder screenshot parity, guarded read-only baseline.
// Neither paired transport nor Output/Input/Monitor code is changed.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioModuleScreen(vm: AtomosViewModel) {
    val selected by vm.selected.collectAsState()
    val recorderName = selected?.let { vm.displayNameFor(it) } ?: "ATOMOS Recorder"
    val pairing by vm.tcsPairingState.collectAsState()
    val remote by vm.tcsRemoteState.collectAsState()
    val gattState by vm.gattState.collectAsState()
    val snapshot by vm.remoteStateSnapshot.collectAsState()
    val audioReadback by vm.audioR26Live.collectAsState()
    val micLineEvidence by vm.gatt.r2697MicLineEvidence.collectAsState()
    val linked = pairing.acceptWritten && remote.available &&
        gattState != "idle" && gattState != "disconnected" &&
        !gattState.startsWith("error:") && !gattState.startsWith("service-error:")
    // R26.11.3: only a linked recorder's typed 0x8647 state page can claim readback.
    // The UI does NOT map protobuf field numbers to audio options or dB values.
    val meters = if (linked) snapshot.pages[0x0647] else null
    // R26.21: typed collector RX is an independent Flow. In some sessions it
    // arrives while the broader remote snapshot is still updating. Use the
    // RX already observed by this same paired Audio collector as a fallback.
    // Never accept a non-0x8647 reply or a disconnected session's cache.
    val collectorPage = audioReadback.values["AudioMetersPageGet"]
    // R26.27: a successful older state-page cannot silently substitute for a
    // failed new collector generation. Require a TYPED_RX from THIS run.
    val pageFromCollector = if (linked && collectorPage?.status == "TYPED_RX" &&
        collectorPage.responseType == 0x8647) collectorPage.rawHex else ""
    // R26.30: keep last genuine typed RX readable during a subsequent collector
    // refresh. Clear when disconnected or another recorder is selected. A cached
    // page is NEVER labelled a fresh GET. The general snapshot is from GATT's
    // current connection (GattClient resets it on disconnect).
    var r2630LastTypedInView by remember(selected?.address) { mutableStateOf("") }
    LaunchedEffect(linked, pageFromCollector) {
        if (!linked) r2630LastTypedInView = ""
        else if (com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(pageFromCollector) != null)
            r2630LastTypedInView = pageFromCollector
    }
    val r2630Source = com.atomosremote.nativeapp.audio.AudioR2630Readable.select(
        linked, pageFromCollector, r2630LastTypedInView,
        if (linked && meters?.responseType == 0x8647) meters?.rawHex.orEmpty() else "")
    val audioPageRawHex = r2630Source.hex
    val r2627Diagnosis = com.atomosremote.nativeapp.audio.AudioR2627_29Evidence
        .diagnose(linked, audioReadback.running, collectorPage?.status,
            audioReadback.status, pageFromCollector.isNotEmpty())
    val wireInspection = remember(audioPageRawHex) { AudioR26WireInspector.inspect(audioPageRawHex) }
    val haveMeterPayload = linked && audioPageRawHex.isNotEmpty() &&
        wireInspection.status == "RAW_PARSED_UNMAPPED" && wireInspection.fields.isNotEmpty()
    var pane by remember(selected?.address) { mutableStateOf("AUDIO") }
    var sheetKey by remember(selected?.address) { mutableStateOf<String?>(null) }
    var refreshGeneration by remember(selected?.address) { mutableIntStateOf(0) }
    var lastRequestQueued by remember(selected?.address) { mutableStateOf<Boolean?>(null) }
    // R26.31-33: A/B comparison state is per recorder, not shared with NDI.
    var r2631BaselineHex by remember(selected?.address) { mutableStateOf("") }
    var r2631BaselineGeneration by remember(selected?.address) { mutableIntStateOf(-1) }
    LaunchedEffect(linked) {
        if (!linked) { r2631BaselineHex = ""; r2631BaselineGeneration = -1 }
    }
    // R26.51: local-only one-field A/B SET-readiness evidence per paired recorder.
    // A/B observes physical AG2 changes; it must never be called an Android SET.
    var r2651BaseKey by remember(selected?.address) { mutableStateOf("") }
    var r2651BaseHex by remember(selected?.address) { mutableStateOf("") }
    var r2651BaseGen by remember(selected?.address) { mutableIntStateOf(-1) }
    LaunchedEffect(linked) {
        if (!linked) { r2651BaseKey = ""; r2651BaseHex = ""; r2651BaseGen = -1 }
    }
    val r2631Comparison = com.atomosremote.nativeapp.audio.AudioR2631_33Proof.compare(
        r2631BaselineHex, audioPageRawHex, r2631BaselineGeneration,
        audioReadback.generation, r2630Source.isCurrentRx, audioReadback.running)

    // R26.34-36: evidence-only calibration tied to this BLE recorder, never NDI.
    // Correlations are operator-labelled observations, not a verified GET/SET mapping.
    var r2634Target by remember(selected?.address) { mutableStateOf("Other Audio setting") }
    var r2634Observations by remember(selected?.address) {
        mutableStateOf<List<com.atomosremote.nativeapp.audio.AudioR2634_36Calibration.Observation>>(emptyList())
    }
    LaunchedEffect(linked) { if (!linked) r2634Observations = emptyList() }
    val r2634Assessment = com.atomosremote.nativeapp.audio.AudioR2634_36Calibration
        .assess(r2634Target, r2634Observations)

    var priorAudioPageRaw by remember(selected?.address) { mutableStateOf("") }
    var lastChangedWireFields by remember(selected?.address) { mutableStateOf<List<Int>>(emptyList()) }
    var r2625LastRawDeltas by remember(selected?.address) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(linked, audioPageRawHex) {
        if (!linked) {
            priorAudioPageRaw = ""
            lastChangedWireFields = emptyList()
            r2625LastRawDeltas = emptyList()
        } else if (haveMeterPayload && r2630Source.isCurrentRx && audioPageRawHex != priorAudioPageRaw) {
            if (priorAudioPageRaw.isNotEmpty()) {
                lastChangedWireFields = com.atomosremote.nativeapp.audio.AudioR2620_22Evidence
                    .changedRawFields(priorAudioPageRaw, audioPageRawHex)
                r2625LastRawDeltas = com.atomosremote.nativeapp.audio.AudioR2624_26Protocol
                    .diff(priorAudioPageRaw, audioPageRawHex)
                if (lastChangedWireFields.isNotEmpty()) {
                    Log.i("AtomRemoteAudioR2621", "AUDIO_PAGE_RAW_FIELD_CHANGE " +
                        "fields=${lastChangedWireFields.joinToString(",")} " +
                        "notCalibrated=true")
                }
            }
            priorAudioPageRaw = audioPageRawHex
        }
    }
    // R26.12: no Save A/B, no non-local diagnostic buttons in the normal Audio UI.

    // Raw evidence logging is keyed to a changed, strictly parsed readback.
    LaunchedEffect(linked, audioPageRawHex, r2630Source.origin) {
        Log.i("AtomRemoteAudioR2630", "UI_READBACK origin=${r2630Source.origin} " +
            "available=$haveMeterPayload linked=$linked running=${audioReadback.running} " +
            "bytes=${audioPageRawHex.length / 2}")
        if (haveMeterPayload && r2630Source.isCurrentRx) {
            Log.i("AtomRemoteAudioR26Map",
                "PAGE_8647_VERIFIED size=${audioPageRawHex.length / 2} " +
                    "fields=${wireInspection.fields.size} " +
                    wireInspection.fields.joinToString(";") { "f${it.number}/w${it.wireType}=${it.detail}" })
        }
    }

    // R26.13: actual recovered GET opcodes, one sequential collector per screen/refresh.
    // Collector itself waits for MTU and other feature owners; no Save A/B.
    // R26.28: Audio-only settle window after opening a session. Existing
    // Input/Output prefetch is not modified. Explicit Refresh skips the wait.
    // Never start a second GET while the original Audio collector is running.
    LaunchedEffect(selected?.address, linked, refreshGeneration) {
        if (linked) {
            if (refreshGeneration == 0) delay(350L)
            val accepted = vm.refreshRecoveredAudioR26()
            Log.i("AtomRemoteAudioR2628",
                "AUDIO_REFRESH_REQUEST accepted=$accepted manual=${refreshGeneration > 0} " +
                    "selected=${selected?.address.orEmpty()} linked=$linked " +
                    "collectorRunning=${audioReadback.running} noExtraGet=true")
        }
    }

    DisposableEffect(vm, selected?.address) {
        onDispose { vm.stopRecoveredAudioR26() }
    }

    // R26.42: ALL settings visible on both panes, regardless of RX status.
    // Only real device-reported data can populate a value; missing telemetry
    // never deletes the row or activates an unverified Audio SET command.
    val r2641Display = remember(audioPageRawHex) {
        com.atomosremote.nativeapp.audio.AudioR2641Presentation.decode(audioPageRawHex)
    }
    var r2641Advanced by remember(selected?.address) { mutableStateOf(false) }
    // REC channel calibration stays on this BLE recorder only. Every mapping
    // requires an observed single-ID toggle on the physical recorder.
    val recCalibrationContext = LocalContext.current
    val recCalibrationPrefs = remember(recCalibrationContext) {
        recCalibrationContext.getSharedPreferences("atomremote_rec_routing_v1", android.content.Context.MODE_PRIVATE)
    }
    val recDeviceAddress = selected?.address.orEmpty()
    val recMappingPrefKey = "rec_mapping_" + recDeviceAddress
    var recLearnedPairs by remember(recDeviceAddress) {
        mutableStateOf(com.atomosremote.nativeapp.audio.AudioRecCalibratedChannels.decodeSaved(
            recCalibrationPrefs.getString(recMappingPrefKey, null)))
    }
    var recPendingPair by remember(recDeviceAddress) { mutableStateOf<Pair<String, String>?>(null) }
    var recPendingGeneration by remember(recDeviceAddress) { mutableIntStateOf(-1) }
    var recCandidate by remember(recDeviceAddress) {
        mutableStateOf<com.atomosremote.nativeapp.audio.AudioR2647RecProof.Candidate?>(null)
    }
    var recCalibrationNotice by remember(recDeviceAddress) { mutableStateOf("") }
    LaunchedEffect(linked, recDeviceAddress) {
        if (!linked) {
            recPendingPair = null
            recCandidate = null
            recPendingGeneration = -1
        }
    }
    val recLiveStates = remember(audioPageRawHex) {
        com.atomosremote.nativeapp.audio.AudioRecCalibratedChannels.idStates(audioPageRawHex)
    }
    // R26.47: observe only a NEW completed typed GET. Do not auto-save an ID.
    LaunchedEffect(recDeviceAddress, audioPageRawHex, recPendingPair, recPendingGeneration,
        linked, r2630Source.isCurrentRx, audioReadback.running, audioReadback.generation) {
        val pending = recPendingPair
        if (recCandidate == null && pending != null && linked && recDeviceAddress.isNotEmpty() &&
            r2630Source.isCurrentRx && !audioReadback.running &&
            audioReadback.generation > recPendingGeneration) {
            val result = com.atomosremote.nativeapp.audio.AudioR2647RecProof.evaluate(
                pending.first, pending.second, audioPageRawHex, recPendingGeneration,
                audioReadback.generation, true, false)
            recCalibrationNotice = result.message
            recCandidate = result.candidate
            if (result.candidate != null) {
                Log.i("AtomRemoteAudioR2647", "REC_CANDIDATE target=${pending.first} " +
                    "id=${result.candidate.rawId} generation=${audioReadback.generation} " +
                    "operatorConfirmationRequired=true audioSetTx=false")
            }
        }
    }
    // R26.57: user can inspect available options and stage a UI-only choice.
    // This is intentionally NOT a control transaction or a recorder SET.
    var r2657PreviewKey by remember(selected?.address) { mutableStateOf("") }
    var r2657PreviewValue by remember(selected?.address) { mutableStateOf<Long?>(null) }
    // R26.65: explicit trial selection. Never auto-sends after GET or connect.
    var r2665TrialArmed by remember(selected?.address) { mutableStateOf(false) }
    var r2665PhonesDraft by remember(selected?.address) { mutableIntStateOf(-1) }
    var r2665LastTrialStatus by remember(selected?.address) { mutableStateOf("") }

    val r2657NameContext = LocalContext.current
    val r2657NamePrefs = remember(r2657NameContext) {
        r2657NameContext.getSharedPreferences("atomremote_audio_r2657_modes", android.content.Context.MODE_PRIVATE)
    }
    val r2657Device = selected?.address.orEmpty()
    val r2657NameOptions = listOf(
        "AUDIO_ORIENTATION:Vertical", "AUDIO_ORIENTATION:Horizontal",
        "AUDIO_EMBED:Off", "AUDIO_EMBED:1/2", "AUDIO_EMBED:3/4")
    var r2657NamedRaw by remember(r2657Device) {
        mutableStateOf(r2657NameOptions.mapNotNull { k ->
            val n = r2657NamePrefs.getLong("$r2657Device/$k", Long.MIN_VALUE)
            if (n == Long.MIN_VALUE) null else k to n
        }.toMap())
    }
    var r2653ProofKey by remember(selected?.address) { mutableStateOf("analogMonitor") }
    // R26.54: optional, low-rate GET-only level tracking while Audio is visible.
    // Uses the established shared collector. NEVER a PhonesLevelSet / Audio SET.
    var r2654LiveGet by remember(selected?.address) { mutableStateOf(false) }
    LaunchedEffect(selected?.address, linked, pane, r2654LiveGet, audioReadback.running) {
        if (linked && pane == "AUDIO" && r2654LiveGet && !audioReadback.running) {
            delay(6_000L)
            if (linked && pane == "AUDIO" && r2654LiveGet) {
                refreshGeneration += 1
                Log.i("AtomRemoteAudioR2654", "LIVE_GET_TICK linked=true " +
                    "sharedCollector=true audioSetTx=false")
            }
        }
    }
    ModuleShell(vm, AtomosViewModel.Screen.AUDIO, "Audio", recorderName) {
        // R27.19: Audio + Mic/Line Input/Output-style options-list presentation.
        // Legacy audio UI (including experimental SET controls) is NOT rendered.
        // All former GET flows and hardware paths remain intact. No SET here.
        if (true) {
            AudioR2719FinalOptionLists(
                deviceKey = selected?.address.orEmpty(),
                linked = linked,
                pane = pane,
                onPane = { pane = it; sheetKey = null },
                audioBusy = audioReadback.running,
                audioPageHex = audioPageRawHex,
                freshTypedAudio = linked && !audioReadback.running &&
                    collectorPage?.status == "TYPED_RX" &&
                    collectorPage.responseType == 0x8647 &&
                    r2630Source.isCurrentRx &&
                    com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(pageFromCollector) != null,
                evidenceFlow = vm.gatt.r2697MicLineEvidence,
                onAudioRefresh = { refreshGeneration += 1 },
                onGainRefresh = { vm.r2672TryMicLineGet("gainLeft") },
                onGainPilot = { selector, restore -> vm.r2722GainPilot(selector, restore) },
                onGainValueSet = { selector, raw -> vm.a137GainValueSet(selector, raw) },
                onInputPilot = { desired -> vm.a05InputLevelSetPilot(desired) },
                onInputRefresh = { vm.r2672TryMicLineGet("inputLevel") },
                onDelayRefresh = { vm.r2672TryMicLineGet("delay") },
                onPhonesTrial = { target -> vm.r2665TryAudioSet("phones", target) },
                onA12Pilot = { key, desired -> vm.a12ExperimentalOneShotSet(key, desired) },
                onRecordOneShot = { recordId, enable -> vm.b0119TryRecordOneShot(recordId, enable) }
            )
        } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("RECORDER AUDIO", color = Color.White, fontSize = 16.sp,
                    fontWeight = FontWeight.Bold)
                Text(when {
                    !linked -> "Disconnected - values unavailable"
                    r2630Source.isCurrentRx && r2641Display.verified -> "Verified Audio page. Individual settings may be pending"
                    r2641Display.verified -> "Last verified value - refresh pending"
                    audioReadback.running -> "GET in progress - settings remain visible"
                    else -> "Readback unavailable - settings remain visible"
                }, fontSize = 11.sp, color = if (r2641Display.verified) AtomosCyan else Color(0xFF99AFB2))
            }
            TextButton(onClick = { refreshGeneration += 1 },
                enabled = linked && !audioReadback.running) {
                Text("REFRESH", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = if (linked && !audioReadback.running) AtomosCyan else Color.Gray)
            }
        }
        if (audioReadback.running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp),
                color = AtomosCyan, trackColor = Color(0xFF244044))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            listOf("AUDIO" to "Audio", "MIC_LINE" to "Mic / Line").forEach { (key, title) ->
                Surface(modifier = Modifier.weight(1f)
                    .clickable { pane = key; sheetKey = null },
                    shape = RoundedCornerShape(11.dp),
                    color = if (pane == key) Color(0xFF0E3639) else Color(0xFF0A181B),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, if (pane == key) AtomosCyan else Color(0xFF2D454A))) {
                    Box(Modifier.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                        Text(title, color = if (pane == key) AtomosCyan else Color(0xFFA2B5B8),
                            fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        // R2695_UNIFIED_SOURCE_BATCH: replace only Audio/MicLine presentation.
        val r2695UnifiedUiActive = true
        AudioR2695UnifiedPanel(
            pane = pane, linked = linked, running = audioReadback.running,
            audioPageHex = audioPageRawHex,
            micEvidence = if (linked) micLineEvidence else com.atomosremote.nativeapp.audio.AudioR2697MicLineSnapshot(),
            onRefresh = { refreshGeneration += 1 },
            onMicLineReadAttempt = { key -> vm.r2672TryMicLineGet(key) }
        )
        // MICLINE_VISIBLE_PHASE02: explicit Mic/Line readback screen; no unverified GET/SET.
        if (pane == "MIC_LINE" && !r2695UnifiedUiActive) {
            MicLineGetPhase01Panel(
                linked = linked,
                running = audioReadback.running,
                onRefresh = { refreshGeneration += 1 },
                onCandidateGet = { key -> vm.r2672TryMicLineGet(key) },
                onCandidateSet = { key, value ->
                    val queued = vm.r2672TryMicLineSet(key, value)
                    if (queued) refreshGeneration += 1
                }
            )
        }
        // R27.14: read-only, existing-session gain evidence. Never change 0x06B6 TX,
        // PacketLink framing, any SET or other Audio/Settings screen.
        if (pane == "MIC_LINE") {
            AudioR2714GainReadOnlyPanel(
                linked = linked,
                evidenceFlow = vm.gatt.r2697MicLineEvidence,
                onReadGain = { vm.r2672TryMicLineGet("gainLeft") }
            )
        }
        // R27.16: input mode is scoped to its own verified 0x06B3 / 0x86AE RX.
        // No transport or other Audio/Settings UI modifications.
        if (pane == "MIC_LINE") {
            AudioR2716InputLevelReadOnlyPanel(
                linked = linked,
                evidenceFlow = vm.gatt.r2697MicLineEvidence,
                onReadInputLevel = { vm.r2672TryMicLineGet("inputLevel") }
            )
        }
        // R27.18: headphone monitoring shared Audio page readback. Candidate f1 only;
        // original R27.17 B0 contradicted the physical icon, so this is not SET-ready.
        // Existing 0x0647 GET collector and settings remain untouched.
        if (pane == "MIC_LINE") {
            AudioR2718MonitorReadOnlyRow(
                linked = linked,
                typedPageHex = pageFromCollector,
                currentTypedPage = linked && !audioReadback.running &&
                    collectorPage?.status == "TYPED_RX" &&
                    collectorPage.responseType == 0x8647 &&
                    r2630Source.isCurrentRx &&
                    com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(pageFromCollector) != null,
                refreshBusy = audioReadback.running,
                onRefreshPage = { refreshGeneration += 1 }
            )
        }
        // R26.53: recorder-focused compact UI. One REC sheet only. The verified
        // common page supplies status, never a speculative per-control SET.
        val r2653Page = com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(audioPageRawHex)
        val r2653Enabled = r2653Page?.recordEnabledList.orEmpty().toSet()
        val r2653Valid = r2653Page?.recordValidList.orEmpty().toSet()
        val r2653Count = if (r2653Page == null) "-- / --" else
            "${r2653Enabled.size} / ${r2653Valid.size}"
        val r2653Fresh = linked && r2630Source.isCurrentRx && !audioReadback.running
        val visibleRows = com.atomosremote.nativeapp.audio.AudioR2650ReadbackUi.enrich(
            if (pane == "AUDIO") r2641Display.audio else r2641Display.micLine,
            recLearnedPairs, audioPageRawHex)
        if (pane == "AUDIO" && !r2695UnifiedUiActive) {
            Text(com.atomosremote.nativeapp.audio.AudioR2673BatchAudit.synopsis(), color = Color(0xFF9FB7BB), fontSize = 10.sp)
            // Headphones is the one level supported by a real value and min/max.
            Surface(modifier = Modifier.fillMaxWidth().clickable { sheetKey = "AUDIO_PHONES" },
                shape = RoundedCornerShape(14.dp), color = Color(0xFF0A2529),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF31585E))) {
                Column(Modifier.fillMaxWidth().padding(15.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("HEADPHONES", color = Color.White, fontSize = 14.sp,
                                fontWeight = FontWeight.Bold)
                            Text("Recorder level", color = Color(0xFF9DB7BA), fontSize = 11.sp)
                        }
                        Text(if (r2653Page == null) "--" else "${r2653Page.phonesLevel}",
                            color = AtomosCyan, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                    }
                    val span = ((r2653Page?.phonesLevelMax ?: 1L) -
                        (r2653Page?.phonesLevelMin ?: 0L)).coerceAtLeast(1L)
                    val frac = if (r2653Page != null)
                        ((r2653Page.phonesLevel - r2653Page.phonesLevelMin).toFloat() /
                            span.toFloat()).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { frac },
                        modifier = Modifier.fillMaxWidth().height(13.dp)
                            .clip(RoundedCornerShape(7.dp)),
                        color = AtomosCyan, trackColor = Color(0xFF29434A))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${r2653Page?.phonesLevelMin ?: "--"}",
                            color = Color(0xFF99B0B4), fontSize = 11.sp)
                        Text("${r2653Page?.phonesLevelMax ?: "--"}",
                            color = Color(0xFF99B0B4), fontSize = 11.sp)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (r2653Fresh) "Level synced from recorder" else "Last verified level",
                            color = Color(0xFFAFC5C5), fontSize = 11.sp)
                        TextButton(onClick = { refreshGeneration += 1 },
                            enabled = linked && !audioReadback.running) {
                            Text("READ NOW", color = if (linked) AtomosCyan else Color.Gray,
                                fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    TextButton(onClick = { r2654LiveGet = !r2654LiveGet },
                        enabled = linked, modifier = Modifier.fillMaxWidth()) {
                        Text(if (r2654LiveGet) "LIVE LEVEL GET  /  ON  (6s)" else
                            "LIVE LEVEL GET  /  OFF",
                            color = if (r2654LiveGet) AtomosCyan else Color(0xFFBAC9CA),
                            fontSize = 11.sp)
                    }
                    Text("The bar follows real AG2 headphone-level GET values (f2). " +
                        "Touch-to-change volume is locked until PhonesLevelSet has " +
                        "verified wire bytes, acknowledgement and GET-after-SET.",
                        color = Color(0xFFAFC5C5), fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
            // One REC card replaces the old 6 tiles, duplicated raw list and individual sheets.
            Surface(modifier = Modifier.fillMaxWidth().clickable { sheetKey = "REC_GROUP" },
                shape = RoundedCornerShape(14.dp), color = Color(0xFF08272B),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF39666B))) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("CHANNEL REC", color = Color.White, fontSize = 14.sp,
                            fontWeight = FontWeight.Bold)
                        Text("$r2653Count enabled / valid", color = AtomosCyan,
                            fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("REC states + headphones monitor options",
                            color = Color(0xFF9DB8BA), fontSize = 11.sp)
                    }
                    Text("VIEW  ›", color = AtomosCyan, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold)
                }
            }
            // These are physically A/B-correlated field readbacks, NOT Android SETs.
            Text("AUDIO ROUTING", color = Color(0xFF8EA9AD), fontSize = 11.sp,
                fontWeight = FontWeight.Bold)
            // Names are stable UI vocabulary. Do not invent physical mode meanings.
            // The single-field A/B proved f9/f11 and f2. The f1 multi-field A/B
            // was skipped; it remains a recorder-reported selector, not SET proof.
            val monitorName = r2653Page?.let { pg -> "Source ${pg.monitor}" } ?: "Readback pending"
            val orientationName = r2653Page?.let { pg ->
                r2657NamedRaw.entries.firstOrNull { it.key.startsWith("AUDIO_ORIENTATION:") &&
                    it.value == pg.orientation }?.key?.substringAfter(":") ?: "Position ${pg.orientation}"
            } ?: "Readback pending"
            val embedName = r2653Page?.let { pg ->
                r2657NamedRaw.entries.firstOrNull { it.key.startsWith("AUDIO_EMBED:") &&
                    it.value == pg.analogEmbed }?.key?.substringAfter(":") ?: "Option ${pg.analogEmbed}"
            } ?: "Readback pending"
            listOf(
                Triple("AUDIO_MONITOR", "Analog monitor", monitorName),
                Triple("AUDIO_ORIENTATION", "Meters orientation", orientationName),
                Triple("AUDIO_EMBED", "Audio embedded", embedName),
                Triple("AUDIO_DROPOUT", "Ignore Audio Dropouts", "GET pending")
            ).forEach { (key, label, displayValue) ->
                Surface(modifier = Modifier.fillMaxWidth().clickable {
                        sheetKey = key; r2657PreviewKey = ""; r2657PreviewValue = null
                    },
                    shape = RoundedCornerShape(10.dp), color = Color(0xFF0A1F23),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF29464C))) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.weight(1f), color = Color.White,
                            fontSize = 12.sp)
                        Text(displayValue, color = if (r2653Page == null)
                            Color(0xFF8D9D9F) else AtomosCyan,
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("  ›", color = AtomosCyan, fontSize = 12.sp)
                    }
                }
            }
            Text("Tap a setting to view the recorder-supported values. " +
                "Recorder changes are reflected by GET; Android SET is still unverified.",
                color = Color(0xFFAFC4C4), fontSize = 10.sp, lineHeight = 15.sp)
        } else if (pane == "MIC_LINE" && !r2695UnifiedUiActive) {
            Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF102328),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF47575A))) {
                Column(Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("MIC / LINE", color = Color.White, fontSize = 16.sp,
                        fontWeight = FontWeight.Bold)
                    Text("Independent recorder GET not yet verified", color = Color(0xFFD8BC81),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Use the Mic/Line cards above to open bottom-sheet trials for " +
                        "Left gain, Right gain, Audio delay and Audio-in level. " +
                        "These are experimental candidate GET/SET actions based on " +
                        "official macOS method names; refresh the shared Audio GET " +
                        "after every trial to verify any recorder-side change.",
                        color = Color(0xFFB9CBCB), fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
        // R26.57: Additive bottom sheets. Existing three routing cards, one REC
        // sheet and headphones bar stay in exactly their R26.54 locations.
        // NEVER present a local radio choice as sent to AG2. The only GET remains 0x0647.
        if (pane == "AUDIO" && sheetKey in setOf("AUDIO_MONITOR", "AUDIO_ORIENTATION",
                "AUDIO_EMBED", "AUDIO_DROPOUT", "AUDIO_PHONES")) {
            val openedKey = sheetKey.orEmpty()
            val title = when (openedKey) {
                "AUDIO_MONITOR" -> "ANALOG MONITOR"
                "AUDIO_ORIENTATION" -> "METERS ORIENTATION"
                "AUDIO_EMBED" -> "AUDIO EMBEDDED"
                "AUDIO_DROPOUT" -> "IGNORE AUDIO DROPOUTS"
                else -> "HEADPHONES LEVEL"
            }
            val getValue: Long? = r2653Page?.let { pg ->
                when (openedKey) {
                    "AUDIO_MONITOR" -> pg.monitor
                    "AUDIO_ORIENTATION" -> pg.orientation
                    "AUDIO_EMBED" -> pg.analogEmbed
                    "AUDIO_PHONES" -> pg.phonesLevel
                    else -> null
                }
            }
            val optionIds: List<Long> = r2653Page?.let { pg ->
                when (openedKey) {
                    "AUDIO_MONITOR" -> pg.monitorList.map { it.toLong() }.distinct()
                    "AUDIO_ORIENTATION" -> if (pg.orientationChangeable != 0L) listOf(0L, 1L) else listOf(pg.orientation)
                    "AUDIO_EMBED" -> pg.analogEmbedList.map { it.toLong() }.distinct()
                    else -> emptyList()
                }
            } ?: emptyList()
            val isPreviewing = r2657PreviewKey == openedKey && r2657PreviewValue != null
            ModalBottomSheet(onDismissRequest = { sheetKey = null; r2665TrialArmed = false },
                containerColor = Color(0xFF071B20), contentColor = Color.White) {
                Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f)
                    .verticalScroll(rememberScrollState()).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(if (r2653Fresh) "Connected AG2 | fresh Audio GET" else "Readback pending / last verified",
                        color = if (r2653Fresh) AtomosCyan else Color(0xFFB4A17E), fontSize = 12.sp)
                    Text(if (getValue == null) "Recorder status: Not reported by current Audio GET"
                        else "Recorder status: $getValue (wire value)",
                        color = Color(0xFFB9D3D3), fontSize = 12.sp)
                    if (openedKey == "AUDIO_PHONES") {
                        val minimum = r2653Page?.phonesLevelMin ?: 0L
                        val maximum = r2653Page?.phonesLevelMax ?: 1L
                        val level = r2653Page?.phonesLevel ?: minimum
                        val fraction = ((level - minimum).toFloat() /
                            (maximum - minimum).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(9.dp), color = AtomosCyan,
                            trackColor = Color(0xFF2F464C))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Min $minimum", color = Color(0xFFADC3C5), fontSize = 11.sp)
                            Text("$level / $maximum", color = AtomosCyan, fontSize = 13.sp)
                        }
                        Text("Experimental Audio SET test: value uses recorder GET range. " +
                            "Opcode/payload candidate is NOT official-ACK verified. " +
                            "Only press TEST when recorder is idle; confirm physical result.",
                            color = Color(0xFFE4C391), fontSize = 11.sp)
                        val minInt = minimum.toInt().coerceIn(0, 127)
                        val maxInt = maximum.toInt().coerceIn(minInt, 127)
                        if (maxInt > minInt) {
                            val draft = if (r2665PhonesDraft in minInt..maxInt) r2665PhonesDraft
                                else level.toInt().coerceIn(minInt, maxInt)
                            Slider(value = draft.toFloat(),
                                onValueChange = { r2665PhonesDraft = it.toInt().coerceIn(minInt, maxInt) },
                                valueRange = minInt.toFloat()..maxInt.toFloat(),
                                steps = (maxInt - minInt - 1).coerceAtLeast(0),
                                enabled = linked && r2653Fresh && !audioReadback.running)
                            Text("Trial target $draft | Recorder GET $level", color = AtomosCyan,
                                fontSize = 12.sp)
                            Button(onClick = {
                                val sent = vm.r2665TryAudioSet("phones", draft)
                                r2665LastTrialStatus = if (sent)
                                    "TRIAL QUEUED: inspect recorder, then READ NOW to verify"
                                    else "BLOCKED: refresh GET, choose different value, or wait for idle"
                                r2665TrialArmed = false
                            }, enabled = r2665TrialArmed && linked && r2653Fresh &&
                                !audioReadback.running && draft.toLong() != level,
                                modifier = Modifier.fillMaxWidth()) {
                                Text("TEST PHONES SET (ONE COMMAND)")
                            }
                        }
                    } else if (openedKey == "AUDIO_DROPOUT") {
                        Text("OFF / ON", color = Color.White, fontSize = 15.sp)
                        Text("The exact separate recorder GET/SET for Ignore Audio Dropouts " +
                            "has not been isolated. No guessed switch is enabled.",
                            color = Color(0xFFE4C391), fontSize = 11.sp)
                    } else {
                        Text("RECORDER-REPORTED OPTIONS", color = AtomosCyan,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        val visibleChoices = (optionIds + listOfNotNull(getValue)).distinct()
                        if (visibleChoices.isEmpty()) {
                            Text("No independently verified choices in current readback.",
                                color = Color(0xFFE4C391), fontSize = 12.sp)
                        } else {
                            visibleChoices.forEach { code ->
                                val selectedByRecorder = code == getValue
                                val label = when (openedKey) {
                                    "AUDIO_ORIENTATION" -> when (code) {
                                        0L -> "Orientation option 0"
                                        1L -> "Orientation option 1"
                                        else -> "Orientation option $code"
                                    }
                                    "AUDIO_EMBED" -> "Embed option $code (Off / 1/2 / 3/4 need physical label mapping)"
                                    else -> "Monitor source $code"
                                }
                                Surface(modifier = Modifier.fillMaxWidth().clickable {
                                        r2657PreviewKey = openedKey
                                        r2657PreviewValue = code
                                        Log.i("AtomRemoteAudioR2657", "LOCAL_OPTION_PREVIEW key=$openedKey " +
                                            "choice=$code recorderValue=$getValue setTx=false")
                                    }, color = if (selectedByRecorder) Color(0xFF143C40)
                                        else Color(0xFF0A2227), shape = RoundedCornerShape(9.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp,
                                        if (selectedByRecorder) AtomosCyan else Color(0xFF28474B))) {
                                    Row(Modifier.fillMaxWidth().padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = selectedByRecorder,
                                            onClick = null, enabled = false)
                                        Text(label, color = Color.White, fontSize = 12.sp,
                                            modifier = Modifier.weight(1f))
                                        if (selectedByRecorder) Text("RECORDER", color = AtomosCyan,
                                            fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                        val physicalNames = when (openedKey) {
                            "AUDIO_ORIENTATION" -> listOf("Vertical", "Horizontal")
                            "AUDIO_EMBED" -> listOf("Off", "1/2", "3/4")
                            else -> emptyList()
                        }
                        if (physicalNames.isNotEmpty()) {
                            HorizontalDivider(color = Color(0xFF315358))
                            Text("CALIBRATE PHYSICAL RECORDER LABEL", color = AtomosCyan,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("First select the named mode on the physical recorder, " +
                                "tap READ NOW, then confirm the matching name below. " +
                                "This only saves a display label for this BLE recorder.",
                                color = Color(0xFFAFC4C4), fontSize = 11.sp)
                            physicalNames.forEach { name ->
                                val compound = "$openedKey:$name"
                                val stored = r2657NamedRaw[compound]
                                TextButton(onClick = {
                                    if (r2653Fresh && getValue != null &&
                                        getValue in visibleChoices && r2657Device.isNotEmpty()) {
                                        val conflicting = r2657NamedRaw.keys.filter { k ->
                                            k.startsWith("$openedKey:") &&
                                                (k == compound || r2657NamedRaw[k] == getValue)
                                        }
                                        val editor = r2657NamePrefs.edit()
                                        conflicting.forEach { k -> editor.remove("$r2657Device/$k") }
                                        editor.putLong("$r2657Device/$compound", getValue).apply()
                                        r2657NamedRaw = (r2657NamedRaw - conflicting.toSet()) + (compound to getValue)
                                        Log.i("AtomRemoteAudioR2657", "PHYSICAL_LABEL_LOCAL " +
                                            "device=$r2657Device key=$openedKey name=$name " +
                                            "raw=$getValue generation=${audioReadback.generation} setTx=false")
                                    }
                                }, enabled = r2653Fresh && getValue != null &&
                                    getValue in visibleChoices && r2657Device.isNotEmpty()) {
                                    Text("${if (stored == getValue) "✓ " else ""}Confirm $name = current recorder value " +
                                        "${getValue ?: "--"}", color = AtomosCyan, fontSize = 12.sp)
                                }
                            }
                        }
                        if (isPreviewing) {
                            Text("Selected option ${r2657PreviewValue} is staged for an explicit trial. " +
                                "Recorder has NOT changed yet.",
                                color = Color(0xFFE4C391), fontSize = 12.sp)
                            val trialKey = when (openedKey) {
                                "AUDIO_MONITOR" -> "monitor"
                                "AUDIO_ORIENTATION" -> "orientation"
                                "AUDIO_EMBED" -> "embed"
                                else -> ""
                            }
                            val selectedTrial = r2657PreviewValue?.toInt()
                            if (trialKey.isNotEmpty() && selectedTrial != null) {
                                Button(onClick = {
                                    val queued = vm.r2665TryAudioSet(trialKey, selectedTrial)
                                    r2665LastTrialStatus = if (queued)
                                        "TRIAL QUEUED: check recorder, then READ NOW"
                                        else "BLOCKED: refresh GET, different value or busy transport"
                                    r2665TrialArmed = false
                                }, enabled = r2665TrialArmed && linked && r2653Fresh &&
                                    !audioReadback.running && r2657PreviewValue != getValue,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text("TEST DEVICE SET (ONE COMMAND)")
                                }
                            }
                        }
                        Text("For physical change, select on Shogun Ultra and tap READ NOW. " +
                            "Android SET will unlock only after TX + ACK + GET-after-SET proof.",
                            color = Color(0xFFA9C3C4), fontSize = 11.sp)
                    }
                    HorizontalDivider(color = Color(0xFF315358))
                    Text("R26.65 EXPERIMENTAL SET PILOT (NOT YET DEVICE-VERIFIED)",
                        color = Color(0xFFE4C391), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Stops at one candidate command. Never automatic. " +
                        "Use only while recorder is NOT recording. Physical result and fresh GET " +
                        "must both be checked. No NDI commands are involved.",
                        color = Color(0xFFA9C3C4), fontSize = 11.sp)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = r2665TrialArmed,
                            onCheckedChange = { r2665TrialArmed = it })
                        Text("Arm ONE Audio SET test", color = Color.White, fontSize = 12.sp)
                    }
                    if (r2665LastTrialStatus.isNotEmpty())
                        Text(r2665LastTrialStatus, color = AtomosCyan, fontSize = 11.sp)
                    Button(onClick = { if (linked && !audioReadback.running) refreshGeneration += 1 },
                        enabled = linked && !audioReadback.running, modifier = Modifier.fillMaxWidth()) {
                        Text("READ NOW (RECORDER GET)")
                    }
                    Text("Experimental candidate SET, no verified AG2 ACK yet. " +
                        "REC and Dropouts SET are NOT enabled.",
                        color = Color(0xFFE4C391), fontSize = 11.sp)
                    TextButton(onClick = { sheetKey = null }) { Text("CLOSE", color = AtomosCyan) }
                }
            }
        }
        // All channel statuses and headphone-source choices live in ONE REC sheet.
        if (pane == "AUDIO" && sheetKey == "REC_GROUP") {
            ModalBottomSheet(onDismissRequest = { sheetKey = null },
                containerColor = Color(0xFF071B20), contentColor = Color.White) {
                Column(Modifier.fillMaxWidth().fillMaxHeight(0.84f)
                    .verticalScroll(rememberScrollState()).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("CHANNEL REC & HEADPHONES", fontSize = 19.sp,
                        fontWeight = FontWeight.Bold, color = Color.White)
                    Text("$r2653Count recorder-enabled / valid IDs",
                        color = AtomosCyan, fontSize = 14.sp)
                    Text(if (r2653Fresh) "Fresh 0x8647 readback" else "Last verified readback - refresh Audio",
                        color = Color(0xFF91AAAE), fontSize = 10.sp)
                    Text("REC statuses are read from f3 and f4. ID names require " +
                        "one-switch physical calibration. No hardware SET is sent by these controls.",
                        color = Color(0xFFAFC3C5), fontSize = 11.sp, lineHeight = 16.sp)
                    if (recLiveStates.isEmpty()) {
                        Text("No valid recorder REC list in current GET.",
                            color = Color(0xFFD2B47B), fontSize = 12.sp)
                    } else {
                        recLiveStates.forEach { item ->
                            val match = recLearnedPairs.entries.firstOrNull { it.value == item.id }
                            val name = when (match?.key) {
                                "ch12" -> "CH 1/2"; "ch34" -> "CH 3/4"
                                "ch56" -> "CH 5/6"; "ch78" -> "CH 7/8"
                                "ch910" -> "CH 9/10"; "ch1112" -> "CH 11/12"
                                else -> "Recorder ID ${item.id}"
                            }
                            Surface(shape = RoundedCornerShape(9.dp),
                                color = Color(0xFF112C30)) {
                                Row(Modifier.fillMaxWidth().padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(name, color = Color.White, fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold)
                                        Text("ID ${item.id} | ${if (item.enabled) "REC ON" else "REC OFF"}",
                                            color = if (item.enabled) AtomosCyan else Color(0xFF9EAEB0),
                                            fontSize = 11.sp)
                                    }
                                    Switch(checked = item.enabled, onCheckedChange = null,
                                        enabled = false)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0xFF315358))
                    Text("HEADPHONES MONITOR SOURCE", color = AtomosCyan,
                        fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("This is the recorder's reported monitor-source selector. " +
                        "Its choices are not confirmed REC channel labels. " +
                        "Selection remains read-only until exact MonitorSet is verified.",
                        color = Color(0xFFADBDC0), fontSize = 11.sp, lineHeight = 16.sp)
                    r2653Page?.monitorList?.distinct()?.forEach { choice ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = r2653Page?.monitor == choice,
                                onClick = null, enabled = false)
                            Text("Source option $choice", color = Color(0xFFD7E4E5),
                                fontSize = 12.sp)
                        }
                    }
                    HorizontalDivider(color = Color(0xFF315358))
                    Text("REC CHANNEL LABEL CALIBRATION", color = AtomosCyan,
                        fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Optional: choose a channel below, toggle exactly that " +
                        "physical REC option on AG2, tap Audio REFRESH, then confirm its ID.",
                        color = Color(0xFFA5BDC0), fontSize = 11.sp, lineHeight = 15.sp)
                    val channelLabels = listOf("ch12" to "CH 1/2", "ch34" to "CH 3/4",
                        "ch56" to "CH 5/6", "ch78" to "CH 7/8",
                        "ch910" to "CH 9/10", "ch1112" to "CH 11/12")
                    channelLabels.chunked(2).forEach { group ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            group.forEach { (key, label) ->
                                val mappedId = recLearnedPairs[key]
                                OutlinedButton(onClick = {
                                    if (r2653Fresh && recLiveStates.isNotEmpty()) {
                                        recPendingPair = key to audioPageRawHex
                                        recPendingGeneration = audioReadback.generation
                                        recCandidate = null
                                        recCalibrationNotice = "Change physical $label, then REFRESH"
                                    } else recCalibrationNotice = "Wait for fresh Audio GET"
                                }, modifier = Modifier.weight(1f), enabled = mappedId == null && r2653Fresh) {
                                    Text(if (mappedId == null) "LEARN $label" else "$label = ID $mappedId",
                                        fontSize = 10.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                    if (recPendingPair != null) {
                        Text("Learning ${recPendingPair?.first}: ${recCalibrationNotice}",
                            color = AtomosCyan, fontSize = 11.sp)
                        val candidate = recCandidate
                        if (candidate != null) {
                            val canConfirm = com.atomosremote.nativeapp.audio.AudioR2647RecProof.canConfirm(
                                candidate, recPendingPair!!.first, recLearnedPairs, linked,
                                audioReadback.generation, audioPageRawHex) && r2653Fresh
                            Text("Physical change: ID ${candidate.rawId} → ${if (candidate.switchedOn) "ON" else "OFF"}",
                                color = Color(0xFFDDC78C), fontSize = 11.sp)
                            TextButton(onClick = {
                                if (canConfirm) {
                                    val updated = recLearnedPairs + (candidate.targetKey to candidate.rawId)
                                    recCalibrationPrefs.edit().putString(recMappingPrefKey,
                                        com.atomosremote.nativeapp.audio.AudioRecCalibratedChannels
                                            .encodeSaved(updated)).apply()
                                    recLearnedPairs = updated
                                    recPendingPair = null; recCandidate = null
                                    recPendingGeneration = -1
                                    recCalibrationNotice = "Mapped ID ${candidate.rawId} locally, no SET"
                                    Log.i("AtomRemoteAudioR2647", "REC_MAPPING_CONFIRMED " +
                                        "label=${candidate.targetKey} rawId=${candidate.rawId} " +
                                        "generation=${candidate.observedGeneration} audioSetTx=false")
                                }
                            }, enabled = canConfirm) {
                                Text("CONFIRM REC ID", color = if (canConfirm) AtomosCyan else Color.Gray)
                            }
                        }
                        TextButton(onClick = {
                            recPendingPair = null; recCandidate = null
                            recPendingGeneration = -1
                        }) { Text("CANCEL LEARN", color = Color(0xFFBAC8C9)) }
                    }
                    TextButton(onClick = {
                        if (linked && !audioReadback.running) refreshGeneration += 1
                    }, enabled = linked && !audioReadback.running) {
                        Text("REFRESH RECORDER GET", color = AtomosCyan)
                    }
                    Text("REC switch and headphone source selection are shown " +
                        "as read-only because full AG2 SET bytes and typed replies are unproven.",
                        color = Color(0xFFCCAE7A), fontSize = 11.sp, lineHeight = 16.sp)
                    TextButton(onClick = { sheetKey = null }) { Text("CLOSE", color = AtomosCyan) }
                }
            }
        }
        Text(if (r2641Advanced) "Hide diagnostics" else "Advanced Audio diagnostics",
            color = Color(0xFF8FA9AB), fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().clickable { r2641Advanced = !r2641Advanced }
                .padding(vertical = 9.dp))
        if (r2641Advanced) {
            // Original discovery utilities remain under Advanced, not in the main UI.
            AudioR2627_29ReadbackStatusPanel(r2627Diagnosis.title, r2627Diagnosis.detail,
                audioReadback.generation, audioReadback.running)
            AudioR2630PrimaryPanel(r2630Source)
            AudioR2631_33ComparePanel(
                currentPageHex = audioPageRawHex,
                freshTypedRx = r2630Source.isCurrentRx && haveMeterPayload,
                running = audioReadback.running, linked = linked,
                hasBaseline = r2631BaselineGeneration >= 0,
                generation = audioReadback.generation, comparison = r2631Comparison,
                saveBaseline = {
                    if (r2630Source.isCurrentRx && haveMeterPayload && !audioReadback.running) {
                        r2631BaselineHex = audioPageRawHex
                        r2631BaselineGeneration = audioReadback.generation
                    }
                },
                refreshAndCompare = { if (linked && !audioReadback.running) refreshGeneration += 1 },
                clearBaseline = { r2631BaselineHex = ""; r2631BaselineGeneration = -1 }
            )
            AudioR2634_36CalibrationPanel(
                selectedTarget = r2634Target, onTargetSelected = { r2634Target = it },
                linked = linked, comparison = r2631Comparison,
                evidence = r2634Observations, assessment = r2634Assessment,
                onRecord = {
                    val observation = com.atomosremote.nativeapp.audio.AudioR2634_36Calibration.observe(
                        r2634Target, r2631BaselineHex, audioPageRawHex,
                        r2631BaselineGeneration, audioReadback.generation,
                        r2630Source.isCurrentRx, audioReadback.running)
                    if (observation != null && r2634Observations.none {
                            it.currentGeneration == observation.currentGeneration
                        }) r2634Observations = r2634Observations + observation
                }, onClear = { r2634Observations = emptyList() }
            )
            // Retain R26.51 A/B testing without restoring the removed per-control sheets.
            Text("PHYSICAL A/B PROOF (NO SET)", color = AtomosCyan,
                fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("analogMonitor", "phones", "orientation", "embed").forEach { key ->
                    TextButton(onClick = { r2653ProofKey = key },
                        modifier = Modifier.weight(1f)) {
                        Text(when (key) {
                            "analogMonitor" -> "Monitor"; "phones" -> "Phones"
                            "orientation" -> "Meters"; else -> "Embed"
                        }, color = if (r2653ProofKey == key) AtomosCyan else Color.Gray,
                            fontSize = 10.sp)
                    }
                }
            }
            val r2653Verifier = com.atomosremote.nativeapp.audio.AudioR2651SetVerification
            if (r2651BaseKey == r2653ProofKey) {
                val proof = r2653Verifier.comparePhysical(r2653ProofKey,
                    r2651BaseHex, audioPageRawHex, r2651BaseGen,
                    audioReadback.generation, linked, r2630Source.isCurrentRx,
                    audioReadback.running, recLearnedPairs)
                Text("A/B ${proof.status} | ${proof.detail}",
                    color = if (proof.physicalEvidence) AtomosCyan else Color(0xFFD4BB86),
                    fontSize = 11.sp, lineHeight = 15.sp)
                LaunchedEffect(r2653ProofKey, proof.status, proof.before, proof.after) {
                    if (proof.physicalEvidence) Log.i("AtomRemoteAudioR2651",
                        "PHYSICAL_AB_CONFIRMED device=${selected?.address.orEmpty()} " +
                            "key=$r2653ProofKey field=${proof.changedField} " +
                            "before=${proof.before} after=${proof.after} setTx=false pageRx=0x8647")
                }
            }
            Row(Modifier.fillMaxWidth()) {
                val canSave = r2653Fresh && audioPageRawHex.isNotEmpty()
                TextButton(onClick = {
                    r2651BaseKey = r2653ProofKey
                    r2651BaseHex = audioPageRawHex
                    r2651BaseGen = audioReadback.generation
                    Log.i("AtomRemoteAudioR2651", "PHYSICAL_AB_BASELINE " +
                        "device=${selected?.address.orEmpty()} key=$r2653ProofKey " +
                        "generation=$r2651BaseGen payload=$r2651BaseHex setTx=false")
                }, enabled = canSave) { Text("SAVE BASELINE", color = AtomosCyan, fontSize = 11.sp) }
                TextButton(onClick = {
                    val accepted = vm.refreshRecoveredAudioR26()
                    Log.i("AtomRemoteAudioR2651", "PHYSICAL_AB_REFRESH " +
                        "device=${selected?.address.orEmpty()} key=$r2653ProofKey " +
                        "accepted=$accepted setTx=false")
                }, enabled = linked && !audioReadback.running) {
                    Text("REFRESH GET", color = AtomosCyan, fontSize = 11.sp)
                }
            }
            Text("A/B only proves a physical recorder change. Android SET is not enabled.",
                color = Color(0xFFBBA983), fontSize = 10.sp)
            AudioR26VerifiedPagePanel(haveMeterPayload, audioPageRawHex, wireInspection)
        }
        LaunchedEffect(pane, r2641Display.verified, r2630Source.origin) {
            Log.i("AtomRemoteAudioR2642", "SCREEN pane=$pane verified=${if (pane == "AUDIO") r2641Display.verified else false} " +
                "origin=${if (pane == "AUDIO") r2630Source.origin else "NO_INDEPENDENT_MICLINE_RX"} rows=${visibleRows.size} " +
                "compactUi=true audioSetTx=false")
        }
        } // R27.19: dormant legacy Audio/MicLine UI
    }

}

// R26.46: compact Monitor-style channel pairs inside the REC routing module.
// A raw enabled ID list must not be presented as verified per-pair REC state.
@Composable
private fun AudioR2646ChannelPairTile(
    row: com.atomosremote.nativeapp.audio.AudioR2641Presentation.Row,
    onClick: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(11.dp), color = Color(0xFF071B20),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2F4A51))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, modifier = Modifier.weight(1f), color = Color.White,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("›", color = Color(0xFFBBD0D3), fontSize = 20.sp)
            }
            Text("REC / MONITOR", color = Color(0xFF8CA5A9), fontSize = 9.sp)
            Text(if (row.evidence == com.atomosremote.nativeapp.audio.AudioR2641Presentation.Evidence.NOT_REPORTED)
                    "Readback pending" else row.value,
                color = Color(0xFF9EB6BA), fontSize = 11.sp)
        }
    }
}

// R26.42: all options visible, read-only status is explicit.
@Composable
private fun AudioR2641ValueCard(
    row: com.atomosremote.nativeapp.audio.AudioR2641Presentation.Row,
    onClick: () -> Unit
) {
    val confirmed = row.evidence == com.atomosremote.nativeapp.audio.AudioR2641Presentation.Evidence.AG2_CALIBRATED
    val pending = row.evidence == com.atomosremote.nativeapp.audio.AudioR2641Presentation.Evidence.NOT_REPORTED
    Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp), color = Color(0xFF071B20),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2F4A51))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("◇", color = AtomosCyan, fontSize = 18.sp)
                Spacer(Modifier.width(11.dp))
                Text(row.label, modifier = Modifier.weight(1f), color = Color.White,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Column(horizontalAlignment = Alignment.End) {
                    Text(row.value, color = when {
                        confirmed -> AtomosCyan
                        pending -> Color(0xFF839DA0)
                        else -> Color(0xFFC6DBDE)
                    }, fontSize = if (pending) 11.sp else 13.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(when {
                        confirmed -> "AG2 calibrated"
                        pending -> "Readback pending"
                        else -> "Raw recorder value"
                    }, color = Color(0xFF7F999E), fontSize = 9.sp)
                }
                Spacer(Modifier.width(7.dp))
                Text("›", color = Color(0xFFBCCDD0), fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun AudioR2641NoData(pane: String, linked: Boolean, running: Boolean) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp),
        color = Color(0xFF081C20),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF29474D))) {
        Column(Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (pane == "Mic / Line") "Mic / Line" else "Audio readback",
                color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(when {
                !linked -> "Connect to your AG2 to read its current settings."
                pane == "Mic / Line" -> "AG2 has not supplied independent Mic/Line gain, input source or delay readback. Unsupported controls are hidden."
                running -> "Waiting for the AG2 configuration response."
                else -> "No valid configuration reply yet. Use Refresh to retry."
            }, color = Color(0xFF9CB2B6), fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

// R26.31-33: real recorder before/after configuration evidence, not a fake SET slider.
@Composable
private fun AudioR2631_33ComparePanel(
    currentPageHex: String,
    freshTypedRx: Boolean,
    running: Boolean,
    linked: Boolean,
    hasBaseline: Boolean,
    generation: Int,
    comparison: com.atomosremote.nativeapp.audio.AudioR2631_33Proof.Comparison,
    saveBaseline: () -> Unit,
    refreshAndCompare: () -> Unit,
    clearBaseline: () -> Unit
) {
    val proof = com.atomosremote.nativeapp.audio.AudioR2631_33Proof
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A2425)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF316368))
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("RECORDER AUDIO CHECK | R26.31-33", color = AtomosCyan,
                fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Real device page: 0x0647 → 0x8647  |  refresh generation $generation",
                color = Color(0xFFB5D1D3), fontSize = 10.sp)
            var showR2631RawRows by remember { mutableStateOf(false) }
            if (freshTypedRx) {
                Text(
                    if (showR2631RawRows) "Hide full recorder configuration" else "Show full recorder configuration",
                    color = AtomosCyan, fontSize = 10.sp,
                    modifier = Modifier.clickable { showR2631RawRows = !showR2631RawRows }
                )
                if (showR2631RawRows) proof.liveConfiguration(currentPageHex).forEach { row ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.name, color = Color(0xFFB9CACD), fontSize = 10.sp,
                            modifier = Modifier.weight(0.55f))
                        Text(row.raw, color = Color(0xFFB1FFFF), fontSize = 10.sp,
                            modifier = Modifier.weight(0.45f))
                    }
                }
            } else {
                Text("No fresh Audio GET yet; cached readings are NOT treated as new data.",
                    color = Color(0xFFD8BB78), fontSize = 10.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = saveBaseline, enabled = linked && freshTypedRx && !running,
                    modifier = Modifier.weight(1f)) {
                    Text("SAVE BASELINE", color = if (freshTypedRx && !running) AtomosCyan else Color.Gray,
                        fontSize = 10.sp)
                }
                TextButton(onClick = refreshAndCompare, enabled = linked && hasBaseline && !running,
                    modifier = Modifier.weight(1f)) {
                    Text("REFRESH + COMPARE", color = if (linked && hasBaseline && !running) AtomosCyan else Color.Gray,
                        fontSize = 10.sp)
                }
            }
            if (hasBaseline) {
                Text("A/B: ${comparison.code}  |  ${comparison.detail}",
                    color = if (comparison.newSampleConfirmed) AtomosCyan else Color(0xFFD8BB78),
                    fontSize = 11.sp, lineHeight = 16.sp)
                comparison.changes.forEach { change ->
                    Text(change, color = Color(0xFFE1F0EF), fontSize = 10.sp)
                }
                TextButton(onClick = clearBaseline) {
                    Text("Clear baseline", color = Color(0xFF9FB6BB), fontSize = 10.sp)
                }
            }
            Text("macOS individual Audio GET/SET opcodes are documented but NOT wire-proven. " +
                "Their protobuf request bodies/AG2 response types still require verification; " +
                "no individual SET or unverified GET can be transmitted.",
                color = Color(0xFFD8BB78), fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

// R26.34-36: human-labelled calibration is read-only. It never sends SET commands.
@Composable
private fun AudioR2634_36CalibrationPanel(
    selectedTarget: String,
    onTargetSelected: (String) -> Unit,
    linked: Boolean,
    comparison: com.atomosremote.nativeapp.audio.AudioR2631_33Proof.Comparison,
    evidence: List<com.atomosremote.nativeapp.audio.AudioR2634_36Calibration.Observation>,
    assessment: com.atomosremote.nativeapp.audio.AudioR2634_36Calibration.Assessment,
    onRecord: () -> Unit,
    onClear: () -> Unit
) {
    val targets = com.atomosremote.nativeapp.audio.AudioR2634_36Calibration.targetLabels
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1C1F)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF315B61))
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("CALIBRATE AUDIO FIELD | R26.34-36", color = AtomosCyan,
                fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("1. Select ONE setting you changed directly on the AG2.  " +
                "2. Save baseline / Refresh + Compare above.  " +
                "3. Record only after a NEW typed RX changes exactly one field.",
                color = Color(0xFFB8C9CB), fontSize = 10.sp, lineHeight = 15.sp)
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Text("Setting: $selectedTarget  ▾", color = AtomosCyan, fontSize = 11.sp)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    targets.forEach { target ->
                        DropdownMenuItem(text = { Text(target) }, onClick = {
                            onTargetSelected(target)
                            menuOpen = false
                        })
                    }
                }
            }
            TextButton(onClick = onRecord, enabled = linked &&
                comparison.newSampleConfirmed && comparison.changes.size == 1) {
                Text("RECORD OBSERVATION", color = if (comparison.newSampleConfirmed &&
                    comparison.changes.size == 1) AtomosCyan else Color.Gray, fontSize = 11.sp)
            }
            Text("Mapping: ${assessment.status} | ${assessment.detail}",
                color = if (assessment.status == "CONFLICT") Color(0xFFFFB1A3) else Color(0xFFD9CD99),
                fontSize = 10.sp, lineHeight = 15.sp)
            if (evidence.isNotEmpty()) {
                Text("This session: ${evidence.size} operator-labelled observation(s)",
                    color = Color(0xFFB0C8C8), fontSize = 10.sp)
                evidence.takeLast(4).forEach { item ->
                    Text("${item.target}: f${item.field} ${item.before} → ${item.after}",
                        color = Color(0xFFE1F0EF), fontSize = 10.sp)
                }
                TextButton(onClick = onClear) {
                    Text("Clear calibration evidence", color = Color(0xFF9FB6BB), fontSize = 10.sp)
                }
            }
            Text("Evidence is session-only. No GET/SET payload or dB value is inferred; " +
                "unverified controls remain disabled.", color = Color(0xFFD7BB7F),
                fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}

// R26.30: show true recorder 0x8647 config values BEFORE diagnostic panels.
// Mapping labels are explicitly inferred; never imply working dB/mic controls.
@Composable
private fun AudioR2630PrimaryPanel(
    selection: com.atomosremote.nativeapp.audio.AudioR2630Readable.Selection
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A2425)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF316368))
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("RECORDER AUDIO SETTINGS | READ ONLY", color = AtomosCyan,
                fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(selection.label,
                color = if (selection.isCurrentRx) Color(0xFFB8F0DF) else Color(0xFFF0D49B),
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            if (selection.available) {
                com.atomosremote.nativeapp.audio.AudioR2630Readable.primaryRows(selection.hex)
                    .forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, color = Color(0xFFB5C7C9), fontSize = 11.sp,
                                modifier = Modifier.weight(0.57f))
                            Text(value, color = Color.White, fontSize = 11.sp,
                                modifier = Modifier.weight(0.43f))
                        }
                    }
            } else {
                Text("Audio readback pending. Connect AG2 and tap Refresh audio readback.",
                    color = Color(0xFFAEC1C4), fontSize = 11.sp)
            }
            Text("* Field labels are inferred from macOS metadata, not hardware-validated meanings. " +
                "Values are raw recorder configuration, not dB or proven writable controls.",
                color = Color(0xFFE3C790), fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

// R26.27: explicit freshness/timeout diagnostics, Audio UI only.
@Composable
private fun AudioR2627_29ReadbackStatusPanel(
    title: String, detail: String, generation: Int, reading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF102126)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334C52))
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("AUDIO RX HEALTH | R26.27-29", color = AtomosCyan,
                fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(title, color = Color(0xFFE1E9EB), fontSize = 12.sp,
                fontWeight = FontWeight.Bold)
            Text(detail, color = Color(0xFFB8C7C9), fontSize = 10.sp,
                lineHeight = 15.sp)
            Text("Collector generation $generation | running=$reading. " +
                "Only the current collector's typed 0x8647 reply can show live readings.",
                color = Color(0xFF81979C), fontSize = 10.sp)
        }
    }
}

// R26.24-26: strictly decoded current Audio page, and observed raw field changes.
// No NDI or transport dependency, and no SET control is exposed here.
@Composable
private fun AudioR2624_26LivePagePanel(
    verified: Boolean, pageHex: String, deltas: List<String>
) {
    val proto = com.atomosremote.nativeapp.audio.AudioR2624_26Protocol
    val page = remember(pageHex) { proto.decodeObservedPage(pageHex) }
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0C2226)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF31545A))
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("OFFICIAL AUDIO PROTOCOL | R26.24-26", color = AtomosCyan,
                fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(
                if (verified && page != null) "Recorder GET 0x0647 / RX 0x8647: ${page.fieldCount}/11 typed raw fields"
                else "Waiting for typed Audio page; no current recorder values claimed",
                fontSize = 11.sp, color = Color(0xFFD5E8E8)
            )
            if (verified && page != null) {
                page.fields.chunked(2).forEach { fields ->
                    Text(fields.joinToString("    ") { "f${it.number}=${it.render()}" },
                        color = Color(0xFFC6D6D9), fontSize = 10.sp)
                }
                if (deltas.isNotEmpty()) {
                    Text("CHANGED ON RECORDER (raw)", color = AtomosCyan,
                        fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    deltas.forEach { Text(it, color = Color(0xFFE2CF92), fontSize = 10.sp) }
                }
            }
            Text("macOS: ${proto.getNamedConstructors.size} constructor opcodes verified; " +
                "individual request bodies/replies not verified. Audio SET blocked.",
                color = Color(0xFFD5B47C), fontSize = 10.sp, lineHeight = 14.sp)
            Text("Raw field numbers are real. Names/ranges are inferred from Swift metadata, not calibrated dB or writable control states.",
                color = Color(0xFF96AAAF), fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

// R26.11.3: Live wire evidence only. Do not silently rename fields to Phone/REC/Gain.
@Composable
private fun AudioR2620_22EvidencePanel(
    verified: Boolean,
    pageHex: String,
    changedFieldNumbers: List<Int>
) {
    val evidence = com.atomosremote.nativeapp.audio.AudioR2620_22Evidence
    var showProtocolEvidence by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A2023)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF285159))
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("AUDIO HARDWARE CAPABILITY", color = AtomosCyan,
                fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(
                if (verified) evidence.confirmedReadbackSummary(pageHex)
                else "No paired-recorder 0x8647 readback in current Audio session",
                color = Color(0xFFE3EFED), fontSize = 11.sp, lineHeight = 15.sp
            )
            Text("Hardware proven: Audio page GET only. Individual Gain, Mic/Line, Phones and Speaker GET/SET: unverified. Live dB meter: unverified.",
                color = Color(0xFFD5B47C), fontSize = 10.sp, lineHeight = 15.sp)
            if (changedFieldNumbers.isNotEmpty()) {
                Text("Recorder change detected in raw field(s): ${changedFieldNumbers.joinToString(", ")}. " +
                    "This identifies a wire change, not its calibrated control name.",
                    color = AtomosCyan, fontSize = 10.sp, lineHeight = 15.sp)
            }
            Text(
                if (showProtocolEvidence) "macOS GET constructors: show less" else "macOS GET constructors: ${evidence.getterConstructors.size} verified opcode/argument shapes (details)",
                color = AtomosCyan, fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth().clickable { showProtocolEvidence = !showProtocolEvidence }
                    .padding(vertical = 5.dp)
            )
            if (showProtocolEvidence) {
                evidence.getterConstructors.forEach { getter ->
                    Text("${getter.method}: 0x${getter.opcode.toString(16).uppercase()} " +
                        "(${getter.argumentShape}) | ${getter.macOSVA}",
                        color = Color(0xFFAFC5C7), fontSize = 10.sp)
                }
                Text("Constructor args are observed, but serialized protobuf request fields and AG2 replies are NOT verified; these getters are not sent.",
                    color = Color(0xFFD5B47C), fontSize = 10.sp)
            }
        }
    }
}


@Composable
private fun AudioR26VerifiedPagePanel(
    verified: Boolean,
    rawHex: String,
    inspection: com.atomosremote.nativeapp.audio.AudioR26WireInspector.Inspection
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF081A1E)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF24454B))
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("RECORDER AUDIO PAGE", color = AtomosCyan,
                fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(
                if (verified) "GET 0x0647  •  RX 0x8647  •  ${rawHex.length / 2} bytes  •  ${inspection.fields.size} raw fields"
                else "No complete typed readback for current connection (${inspection.status})",
                color = if (verified) Color(0xFFDAEBE9) else Color(0xFF9DACB1),
                fontSize = 11.sp, lineHeight = 16.sp
            )
            val macSnapshot = if (verified) com.atomosremote.nativeapp.audio.AudioR26PageSnapshot.decode(rawHex) else null
            if (macSnapshot != null) {
                Text("macOS AUDIO PAGE STATE | live recorder raw values",
                    color = AtomosCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                macSnapshot.rows.forEach { (name, value) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(name, color = Color(0xFFBDD2D4), fontSize = 10.sp,
                            modifier = Modifier.weight(0.52f))
                        Text(value, color = Color(0xFFE1F3EE), fontSize = 10.sp,
                            modifier = Modifier.weight(0.48f))
                    }
                }
                Text("Property names from official macOS Swift metadata. Wire-tag association inferred from scalar/list types; channel, dB and SET semantics not hardware-calibrated.",
                    color = Color(0xFFD5B47C), fontSize = 10.sp, lineHeight = 14.sp)
            }
            if (verified) {
                inspection.fields.forEach { field ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text("FIELD ${field.number}", color = AtomosCyan, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.width(72.dp))
                        Text("wire=${field.wireType}  ${field.detail}",
                            color = Color(0xFFB5C9CB), fontSize = 10.sp,
                            lineHeight = 15.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
            Text("Numeric Audio page values are genuine recorder readbacks; property-tag names are inferred, not calibrated. No Audio SET is enabled.",
                color = Color(0xFFD5B47C), fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun AudioR26ReadbackLabel(
    spec: AudioR26Setting,
    values: Map<String, com.atomosremote.nativeapp.audio.AudioR26LiveValue>,
    pageHex: String
): String {
    // R26.19 current recorder 0x8647 snapshot; do not mistake inferred tags for SET-ready controls.
    val macPageLabel = com.atomosremote.nativeapp.audio.AudioR2630Readable.compactLabel(spec.key, pageHex)
    if (macPageLabel != null) return macPageLabel
    val pageHint = com.atomosremote.nativeapp.audio.AudioR26ProtoCandidates.pageHint(spec.key, pageHex)
    if (pageHint != null && values[spec.macMethods.firstOrNull()]?.status != "TYPED_RX") return pageHint
    if (spec.key.startsWith("ch") || spec.key == "analogRecord")
        return "Per-channel/REC GET mapping pending"
    val getter = spec.macMethods.mapNotNull { com.atomosremote.nativeapp.audio.AudioR26RecoveredMap.byMethod(it) }.firstOrNull()
        ?: return "Getter opcode awaiting extraction"
    if (getter.requiresArguments) return "GET 0x${getter.command.toString(16).uppercase()}: args pending"
    val readback = values[getter.method]
    if (readback == null) return "GET 0x${getter.command.toString(16).uppercase()}: not read yet"
    if (readback.status == "TYPED_RX") {
        val raw = readback.field1Raw?.let { "raw=$it" }
            ?: "hex=${readback.rawHex.take(20)}"
        return "${getter.method}: $raw (RX 0x${readback.responseType?.toString(16)})"
    }
    return "GET 0x${getter.command.toString(16).uppercase()}: ${readback.status}"
}

@Composable
private fun AudioR26SettingRow(
    spec: AudioR26Setting,
    liveValues: Map<String, com.atomosremote.nativeapp.audio.AudioR26LiveValue>,
    pageHex: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp)
            .border(1.dp, Color(0xFF30474E), RoundedCornerShape(12.dp))
            .background(Color(0xFF06161B), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("◇", color = AtomosCyan, fontSize = 21.sp)
        Spacer(Modifier.width(15.dp))
        Text(spec.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        Text(AudioR26ReadbackLabel(spec, liveValues, pageHex),
            color = if (com.atomosremote.nativeapp.audio.AudioR2630Readable.compactLabel(spec.key, pageHex) != null)
                AtomosCyan else Color(0xFF82979B), fontSize = 10.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 142.dp))
        Spacer(Modifier.width(8.dp))
        Text("›", color = Color.White, fontSize = 24.sp)
    }
}

@Composable
private fun AudioR26MetersPlaceholder(havePayload: Boolean, rawHex: String, inspection: com.atomosremote.nativeapp.audio.AudioR26WireInspector.Inspection) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF081A1E)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF24454B))
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            AudioR26WireInspector.meterPairs.forEach { channel ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(channel, color = Color(0xFFB8C8CB), fontSize = 10.sp,
                        modifier = Modifier.width(92.dp))
                    Box(Modifier.weight(1f).height(8.dp)
                        .background(Color(0xFF243236), RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text("-- dB", color = Color(0xFF71878C), fontSize = 10.sp)
                }
            }
            Text(
                if (havePayload) "RAW 0x0647 (${rawHex.length / 2} bytes), inspect=${inspection.status}. No channel dB decoder is enabled until AG2 hardware proof."
                else "No verified audio levels. Bars are intentionally inactive.",
                color = Color(0xFF869DA1), fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun MediaModuleScreen(vm: AtomosViewModel) {
    val selected by vm.selected.collectAsState()
    val recorderName = selected?.let { vm.displayNameFor(it) } ?: "ATOMOS Recorder"

    LaunchedEffect(recorderName) {
        Log.d(
            "AtomRemoteUI",
            "MEDIA-M02-M04 recorder=$recorderName ui=ISOLATED playback=BROWSER_VIEW_ONLY verifiedGet=NONE writes=LOCKED"
        )
    }

    ModuleShell(vm, AtomosViewModel.Screen.MEDIA, "Media", recorderName) {
        com.atomosremote.nativeapp.media.MediaPhaseM02Screen()
    }
}


// OUTPUT06-14 UI integration: one reusable loader presentation for Input and Output.
// Readback/collector ownership stays in the existing GATT and ViewModel implementations.
@Composable
private fun RecorderSettingsLoader(label: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)
            .border(1.dp, Color(0xFF30474E), RoundedCornerShape(12.dp))
            .background(Color(0xFF06161B), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = AtomosCyan, strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Color(0xFF9CB1B6), fontSize = 11.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutputModuleScreen(vm: AtomosViewModel) {
    val selected by vm.selected.collectAsState()
    val pairing by vm.tcsPairingState.collectAsState()
    val remote by vm.tcsRemoteState.collectAsState()
    val outputSnapshot by vm.outputSessionSnapshot.collectAsState()
    val outputReadbackStatus by vm.outputReadbackStatus.collectAsState()
    val lutReadback by vm.gatt.monitorLutState.collectAsState()
    val hdrReadback by vm.gatt.outputHdrEnabled.collectAsState()
    val downscaleRawId by vm.outputDownscaleRawId.collectAsState()
    val cropRawId by vm.outputDciCropRawId.collectAsState()
    val hdmiTcEnabled by vm.outputHdmiTimecodeEnabled.collectAsState()
    // R23: only exact-length, typed 0x8644 pages from GATT reach this flow.
    val outputPageRaw by vm.gatt.outputPageRawR19.collectAsState()
    val pageEvidence = remember(outputPageRaw) { OutputPageWireDecoder.decode(outputPageRaw) }
    var outputPageBaseline by remember(selected?.address) { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { Log.i("AtomRemoteOutput45", "OUTPUT45 SCREEN MOUNTED") }
    val recorderName = selected?.let { vm.displayNameFor(it) } ?: "ATOMOS Recorder"
    val ready = pairing.acceptWritten && remote.available
    // OUTPUT R14: the AG2 returned protobuf enum ID 0 for both scalar GETs.
    // macOS app enum symbols identify the Rec.709 entry for HDR gamma / gamut.
    // This is a display-only mapping; no option list or SET is inferred.
    val hdr = when (val id = outputSnapshot.hdrTypeId) {
        null -> "Not available"
        0 -> "Rec.709"
        else -> "ID $id (unmapped)"
    }
    val gamut = when (val id = outputSnapshot.gamutId) {
        null -> "Not available"
        0 -> "Rec.709"
        else -> "ID $id (unmapped)"
    }
    val signal = outputSnapshot.outputInfo?.takeIf { it.isNotBlank() } ?: "Not available"
    val rows = listOf(
        Triple("HDR Out", hdrReadback?.let { if (it) "On" else "Off" } ?: "Not reported", "hdrEnable"),
        Triple("HDR Type", hdr, "hdrType"),
        Triple("Gamut", gamut, "gamut"),
        Triple("LUT Out", lutReadback.outputLutEnabled?.let { if (it) "On" else "Off" } ?: "Not reported", "lut"),
        Triple("4K to HD Output", if (ready) downscaleRawId?.let { "ID $it (unmapped)" } ?: "Not available" else "Not available", "downscale"),
        Triple("DCI Crop", if (ready) cropRawId?.let { "ID $it (unmapped)" } ?: "Not available" else "Not available", "crop"),
        Triple("HDMI Timecode", if (ready) hdmiTcEnabled?.let { if (it) "On" else "Off" } ?: "Not available" else "Not available", "timecode"),
        Triple("SDI Link Type", "Not available", "sdiLink"),
        Triple("Output Signal", signal, "signal"),
        Triple("HDMI Output", "Not available", "hdmiStatus"),
        Triple("SDI Output", "Not available", "sdiStatus"),
        Triple("OutputPage proof", if (ready && pageEvidence.valid) "${pageEvidence.bytes}B / ${pageEvidence.fields.size} fields" else "Not received", "pageProof")
    )
    var outputSheet by remember { mutableStateOf<String?>(null) }
    // One readback-driven loader: no fake 2.4-second success timer.
    // A bounded timeout prevents indefinite spinning if no correlated summary is returned.
    val outputHasReadback = outputSnapshot.connected && (
        outputSnapshot.rawSummaryAvailable || outputSnapshot.hdrTypeId != null ||
            outputSnapshot.gamutId != null || !outputSnapshot.outputInfo.isNullOrBlank() ||
            hdrReadback != null || lutReadback.outputLutEnabled != null
    )
    var outputRefreshGeneration by remember(selected?.address) { mutableIntStateOf(0) }
    var outputReadbackTimedOut by remember(selected?.address, outputRefreshGeneration) { mutableStateOf(false) }
    var outputMinEntryElapsed by remember(selected?.address, outputRefreshGeneration) { mutableStateOf(false) }
    // OUTPUT R8: a confirmed no-readback timeout is not a success. Preserve the
    // warning while Refresh starts a new loading attempt; do not invent values.
    var outputReadbackEverTimedOut by remember(selected?.address) { mutableStateOf(false) }
    LaunchedEffect(outputReadbackTimedOut, outputHasReadback) {
        if (outputHasReadback) outputReadbackEverTimedOut = false
        else if (outputReadbackTimedOut) outputReadbackEverTimedOut = true
    }
    val currentHasOutputReadback by rememberUpdatedState(outputHasReadback)
    LaunchedEffect(selected?.address, ready, outputRefreshGeneration) {
        outputReadbackTimedOut = false
        outputMinEntryElapsed = false
        Log.i("AtomRemoteOutput45", "OUTPUT LOADER START ready=$ready refresh=$outputRefreshGeneration")
        if (ready) {
            vm.refreshOutputHardwareReadback()
            delay(900L)
            outputMinEntryElapsed = true
            if (!currentHasOutputReadback) {
                delay(8100L)
                if (!currentHasOutputReadback) {
                    outputReadbackTimedOut = true
                    Log.i("AtomRemoteOutput45", "OUTPUT READBACK TIMEOUT readbackStatus=$outputReadbackStatus")
                }
            }
        } else {
            outputMinEntryElapsed = true
        }
    }
    val outputLoading = ready && (!outputMinEntryElapsed || (!outputHasReadback && !outputReadbackTimedOut))
    // OUTPUT02/03: same visual/edit-sheet pattern as Input; no unsupported writes.
    ModuleShell(vm, AtomosViewModel.Screen.OUTPUT, "Output", recorderName) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("OUTPUT STATUS", color = Color(0xFF8B9497), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(if (ready) "REMOTE READY" else "CONNECTING", color = if (ready) AtomosCyan else Color(0xFF93A6AA), fontSize = 11.sp)
        }
        if (outputLoading) {
            RecorderSettingsLoader("Loading Output settings", "Waiting for recorder readback")
            if (outputReadbackEverTimedOut) {
                Text("Previous Output GET attempts returned no verified values. Retrying...",
                    color = Color(0xFFD8B787), fontSize = 12.sp,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            }
        } else if (ready && !outputHasReadback) {
            Text(
                "Recorder has not confirmed Output values. $outputReadbackStatus. Use Refresh to retry.",
                color = Color(0xFFD8B787), fontSize = 12.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
            )
        } else if (!ready) {
            Text("Connect the recorder to load Output settings", color = Color(0xFF9CB1B6),
                fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        }
        if (!outputLoading) {
        listOf(
            "HDR OUTPUT" to rows.subList(0, 4),
            "OUTPUT OPTIONS" to rows.subList(4, 8),
            "OUTPUT SIGNAL" to rows.subList(8, 12)
        ).forEach { (heading, sectionRows) ->
            Text(heading, modifier = Modifier.padding(top = 16.dp, bottom = 10.dp), color = Color(0xFF91A4A8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            sectionRows.forEachIndexed { index, entry ->
                val (label, value, key) = entry
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 7.dp)
                        .border(if (index == 0) 1.5.dp else 1.dp,
                            if (index == 0) AtomosCyan else Color(0xFF30474E), RoundedCornerShape(12.dp))
                        .background(Color(0xFF06161B), RoundedCornerShape(12.dp))
                        .clickable {
                            Log.i("AtomRemoteOutputR10", "SHEET_OPEN key=$key current=$value ready=$ready")
                            outputSheet = key
                        }
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("◇", color = AtomosCyan, fontSize = 21.sp)
                    Spacer(Modifier.width(15.dp))
                    Text(label, modifier = Modifier.weight(1f), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(value, modifier = Modifier.widthIn(max = 140.dp), color = if (index == 0) AtomosCyan else Color(0xFFCBD8DC), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(10.dp))
                    Text("›", color = Color.White, fontSize = 24.sp)
                }
            }
        }
        } // End Output fields: never show setting cards while the shared loader is visible.
        if (ready && !outputLoading) {
            Text("Readback: $outputReadbackStatus", color = Color(0xFF93A6AA), fontSize = 11.sp,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
        }
        TextButton(onClick = { outputRefreshGeneration += 1 }, enabled = ready && !outputLoading, modifier = Modifier.fillMaxWidth()) {
            Text("Refresh output readback", color = AtomosCyan, fontSize = 12.sp)
        }
    }
    // OUTPUT R10: every Output row has an Input-style bottom sheet showing the
    // recorder's current value and *only* the options established by evidence.
    // This phase is an inspection UI. No Output SET is emitted from these sheets:
    // the previous LUT Output ON SET received 0xFFFF from the AG2.
    if (!outputLoading && outputSheet != null) {
        val sheetKey = outputSheet ?: ""
        val selectedRow = rows.firstOrNull { it.third == sheetKey }
        val isBooleanSetting = sheetKey == "hdrEnable" || sheetKey == "lut" || sheetKey == "timecode"
        val confirmedBoolean: Boolean? = when (sheetKey) {
            "hdrEnable" -> if (ready) hdrReadback else null
            "lut" -> if (ready) lutReadback.outputLutEnabled else null
            "timecode" -> if (ready) hdmiTcEnabled else null
            else -> null
        }
        val valueReported = when (sheetKey) {
            "hdrEnable" -> ready && hdrReadback != null
            "lut" -> ready && lutReadback.outputLutEnabled != null
            "hdrType" -> ready && outputSnapshot.hdrTypeId != null
            "gamut" -> ready && outputSnapshot.gamutId != null
            "downscale" -> ready && downscaleRawId != null
            "crop" -> ready && cropRawId != null
            "timecode" -> ready && hdmiTcEnabled != null
            "signal" -> ready && !outputSnapshot.outputInfo.isNullOrBlank()
            "pageProof" -> ready && pageEvidence.valid
            else -> false
        }
        val sheetStatus = when {
            !ready -> "Recorder disconnected"
            valueReported -> "Current value reported by recorder"
            else -> "Device value not reported"
        }
        ModalBottomSheet(
            onDismissRequest = { outputSheet = null },
            containerColor = Color(0xFF06161B),
            contentColor = Color.White
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Text(selectedRow?.first ?: "Output", color = Color.White,
                    fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("CURRENT VALUE", modifier = Modifier.weight(1f),
                        color = Color(0xFF93A6AA), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(if (valueReported) "READ BACK" else "UNAVAILABLE",
                        color = if (valueReported) AtomosCyan else Color(0xFFD8B787),
                        fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text(if (ready) (selectedRow?.second ?: "Not available") else "Connect recorder",
                    color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(sheetStatus, color = Color(0xFF9CB1B6), fontSize = 11.sp)
                if (sheetKey == "pageProof") {
                    Text("Recorder-confirmed raw AppPolling.OutputPageGet (0x0644 / 0x8644). Protobuf field numbers are NOT proven setting labels.",
                        color = Color(0xFF9CB1B6), fontSize = 11.sp)
                    if (pageEvidence.valid) {
                        Text("${pageEvidence.bytes} bytes | ${pageEvidence.fields.size} top-level fields",
                            color = AtomosCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        pageEvidence.fields.forEach { field ->
                            Text("Field ${field.number} (wire ${field.wireType}): ${field.value}",
                                color = Color(0xFFCBD8DC), fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = {
                            outputPageBaseline = outputPageRaw
                            Log.i("AtomRemoteOutputR23", "AB_BASELINE_CAPTURE bytes=${pageEvidence.bytes} raw=$outputPageRaw")
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Save current page as A/B baseline")
                        }
                        if (outputPageBaseline != null) {
                            val diffs = OutputPageWireDecoder.differences(outputPageBaseline, outputPageRaw)
                            Text("Changed wire fields vs saved baseline: ${diffs.size}",
                                color = AtomosCyan, fontSize = 12.sp)
                            diffs.forEach { diff ->
                                Text(diff, color = Color(0xFFCBD8DC), fontSize = 11.sp)
                            }
                            Text("After changing ONE setting on the recorder itself, tap Refresh and reopen this sheet. Any changed field is an A/B candidate, not proof until reproduced.",
                                color = Color(0xFF9CB1B6), fontSize = 11.sp)
                        }
                    } else {
                        Text("A complete typed OutputPage response has not arrived.",
                            color = Color(0xFFD8B787), fontSize = 12.sp)
                    }
                }
                if (sheetKey == "lut") {
                    val capability = when (lutReadback.outputLutAvailable) {
                        true -> "LUT Output capability: reported available"
                        false -> "LUT Output capability: reported unavailable"
                        null -> "LUT Output capability: not reported"
                    }
                    Text(capability, color = Color(0xFF9CB1B6), fontSize = 11.sp)
                }
                HorizontalDivider(color = Color(0xFF30474E))
                Text("OPTIONS", color = Color(0xFF93A6AA),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (sheetKey == "pageProof") {
                    Text("This screen is evidence only. No recorder SET is transmitted.",
                        color = Color(0xFF9CB1B6), fontSize = 11.sp)
                } else if (isBooleanSetting) {
                    // Boolean choices are known from the official Output UI.
                    // Their availability as *writable* options has NOT been verified.
                    listOf(false, true).forEach { option ->
                        val checked = confirmedBoolean == option
                        Row(
                            Modifier.fillMaxWidth()
                                .border(1.dp, if (checked) AtomosCyan else Color(0xFF30474E),
                                    RoundedCornerShape(11.dp))
                                .background(Color(0xFF102126), RoundedCornerShape(11.dp))
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (option) "On" else "Off", modifier = Modifier.weight(1f),
                                color = Color.White, fontSize = 14.sp)
                            if (checked) {
                                Text("CURRENT", color = AtomosCyan,
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Text("READ ONLY", color = Color(0xFF819196), fontSize = 10.sp)
                            }
                        }
                    }
                    Text("These are the control's On/Off choices, not a confirmed writable option list. Changes are disabled until AG2 SET and post-SET GET pass.",
                        color = Color(0xFF9CB1B6), fontSize = 11.sp)
                    if (sheetKey == "lut") {
                        Text("Previous LUT Out ON request was rejected (0xFFFF); no automatic retry.",
                            color = Color(0xFFD8B787), fontSize = 11.sp)
                    }
                } else {
                    // Show only the *current* recorder-returned scalar ID. The
                    // supported-values list GET is still unmapped for this device.
                    val enumId = when (sheetKey) {
                        "hdrType" -> outputSnapshot.hdrTypeId
                        "gamut" -> outputSnapshot.gamutId
                        else -> null
                    }
                    if (enumId == 0) {
                        Row(
                            Modifier.fillMaxWidth()
                                .border(1.dp, AtomosCyan, RoundedCornerShape(11.dp))
                                .background(Color(0xFF102126), RoundedCornerShape(11.dp))
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Rec.709", modifier = Modifier.weight(1f),
                                color = Color.White, fontSize = 14.sp)
                            Text("CURRENT / ID 0", color = AtomosCyan,
                                fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("Recorder GET returned ID 0. Rec.709 is the matching official macOS enum label. Other supported options have not been reported.",
                            color = Color(0xFF9CB1B6), fontSize = 11.sp)
                    }
                    Row(
                        Modifier.fillMaxWidth().border(1.dp, Color(0xFF30474E),
                            RoundedCornerShape(11.dp)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Recorder option list not received yet",
                            color = Color(0xFFB6C7CB), fontSize = 13.sp)
                    }
                    Text(
                        when (sheetKey) {
                            "hdrType", "gamut", "downscale", "crop", "sdiLink" ->
                                "Supported choices will be shown when a verified AG2 GET/list response is available. No guessed options are added."
                            "signal", "hdmiStatus", "sdiStatus" ->
                                "This item reports output status; it is not a writable setting."
                            else -> "This item is read-only until its device protocol is verified."
                        }, color = Color(0xFF9CB1B6), fontSize = 11.sp
                    )
                }
                if (sheetKey in listOf("downscale", "crop", "timecode", "sdiLink", "signal", "hdmiStatus", "sdiStatus") && pageEvidence.valid) {
                    Text("OutputPage response available (${pageEvidence.bytes} bytes, ${pageEvidence.fields.size} fields). This setting is not yet linked to a verified field number. Open OutputPage proof for raw evidence and A/B comparison.",
                        color = Color(0xFFD8B787), fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = {
                        Log.i("AtomRemoteOutputR10", "SHEET_REFRESH key=$sheetKey")
                        outputSheet = null
                        outputRefreshGeneration += 1
                    }, enabled = ready, modifier = Modifier.fillMaxWidth()
                ) { Text("Refresh recorder values") }
                TextButton(onClick = { outputSheet = null }, modifier = Modifier.fillMaxWidth()) {
                    Text("Close", color = AtomosCyan)
                }
            }
        }
    }
}
@Composable
private fun SettingsModuleScreen(vm: AtomosViewModel) {
    val selected by vm.selected.collectAsState()
    val connectedRecorderName by vm.connectedRecorderName.collectAsState()
    val pairing by vm.tcsPairingState.collectAsState()
    val remote by vm.tcsRemoteState.collectAsState()
    val gattState by vm.gattState.collectAsState()
    val recorderName = connectedRecorderName?.takeIf { it.isNotBlank() }
        ?: selected?.let { vm.displayNameFor(it) }
        ?: "ATOMOS Recorder"
    val modelCode = selected?.name?.let { recorderModelCode(it) } ?: "Not reported"
    val remoteReady = pairing.acceptWritten && remote.available

    ModuleShell(vm, AtomosViewModel.Screen.SETTINGS, "Settings", recorderName) {
        com.atomosremote.nativeapp.ui.settings.SettingsS02Panel(
            recorderName = recorderName,
            modelCode = modelCode,
            bleAddress = selected?.address ?: "Not connected",
            bleState = gattState.replace('-', ' ').uppercase(),
            linked = remoteReady,
            remotePhase = remote.phase
        )
    }
}

@Composable
private fun RemoteModuleCard(
    title: String,
    subtitle: String,
    detail: String,
    phase: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1315))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(Color(0xFF171D1F), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(8.dp).background(Color(0xFF4E595C), CircleShape))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color(0xFFE9EDEE), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                Text(subtitle, color = Color(0xFF959DA0), fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                Text(detail, color = Color(0xFF5F686B), fontSize = 9.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.width(10.dp))
            Surface(shape = RoundedCornerShape(999.dp), color = Color(0xFF171D1F)) {
                Text(
                    phase,
                    color = Color(0xFF778184),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun RemoteTransportDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(64.dp)
            .background(Color(0xFF1E3538))
    )
}

@Composable
private fun RemoteTransportDockButton(
    label: String,
    kind: String,
    enabled: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(92.dp)
            .background(Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    strokeWidth = 2.dp,
                    color = accent
                )
            } else {
                RemoteTransportGlyph(kind = kind, tint = accent, modifier = Modifier.size(54.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = label,
                color = if (enabled || loading) Color.White else Color(0xFF738185),
                fontSize = 7.5.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.45.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RemoteTransportGlyph(
    kind: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2f, h / 2f)
        val ring = minOf(w, h) * 0.38f
        val stroke = ring * 0.10f
        val ringColor = if (kind == "record") AtomosRed else Color(0xFF5D6870)
        drawCircle(
            color = ringColor,
            radius = ring,
            center = center,
            style = Stroke(width = stroke)
        )
        when (kind) {
            "record" -> {
                drawCircle(color = AtomosRed, radius = ring * 0.42f, center = center)
            }
            "stop" -> {
                val side = ring * 0.82f
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(center.x - side / 2f, center.y - side / 2f),
                    size = Size(side, side),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(side * 0.12f, side * 0.12f)
                )
            }
            "play" -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(center.x - ring * 0.28f, center.y - ring * 0.40f)
                    lineTo(center.x + ring * 0.42f, center.y)
                    lineTo(center.x - ring * 0.28f, center.y + ring * 0.40f)
                    close()
                }
                drawPath(path = path, color = Color.White)
            }
            "pause" -> {
                val barW = ring * 0.22f
                val barH = ring * 0.85f
                val gap = ring * 0.18f
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(center.x - gap / 2f - barW, center.y - barH / 2f),
                    size = Size(barW, barH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * 0.25f, barW * 0.25f)
                )
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(center.x + gap / 2f, center.y - barH / 2f),
                    size = Size(barW, barH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * 0.25f, barW * 0.25f)
                )
            }
        }
    }
}

@Composable
private fun RemoteTransportButton(
    label: String,
    enabled: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(62.dp),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (enabled || loading) accent.copy(alpha = 0.72f) else Color(0xFF252A2C)
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (enabled) accent else Color(0xFF596063),
            disabledContentColor = if (loading) accent else Color(0xFF596063)
        ),
        contentPadding = PaddingValues(horizontal = 6.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 2.dp,
                color = accent
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            label,
            fontWeight = FontWeight.ExtraBold,
            fontSize = if (loading) 8.sp else 11.sp,
            letterSpacing = 0.35.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun RemoteTransportStatusBanner(
    message: String,
    isError: Boolean,
    showRecover: Boolean,
    onRecover: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (isError) Color(0xFF35191C) else Color(0xFF102421),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isError) Color(0xFF7D343A) else Color(0xFF24534C)
        )
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isError) "ERROR" else "STATUS",
                    color = if (isError) Color(0xFFFF8E97) else AtomosCyan,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.7.sp
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    message,
                    color = Color(0xFFE7EAEB),
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.weight(1f)
                )
            }

            if (showRecover) {
                Spacer(Modifier.height(9.dp))
                Button(
                    onClick = onRecover,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AtomosCyan,
                        contentColor = Color(0xFF07110F)
                    )
                ) {
                    Text("RECOVER REMOTE", fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun RemoteToolChip(name: String, active: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    OutlinedButton(
        onClick = { onChange(!active) },
        enabled = enabled,
        modifier = modifier.height(42.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (active) AtomosCyan else Color(0xFFB7BFC1)),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (active) AtomosCyan else Color(0xFF2B3032)),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        Text(name, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = Color.Gray, fontSize = 10.sp)
            Text(value, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ToolToggle(name: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun GattScreen(vm: AtomosViewModel) {
    val snapshot by vm.gattSnapshot.collectAsState()
    val events by vm.gattEvents.collectAsState()
    Column(Modifier.fillMaxSize()) {
        AppHeader("Outgoing GATT Inspector", snapshot?.deviceAddress, onBack = { vm.go(AtomosViewModel.Screen.PAIR) })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Pairing ID: ${snapshot?.pairingId ?: "not detected"}", color = AtomosCyan)
                        Text("${snapshot?.characteristics?.size ?: 0} characteristics discovered", color = Color.Gray)
                        Text("Recorder connection details", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            items(snapshot?.characteristics.orEmpty()) { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(c.characteristicUuid, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Service ${c.serviceUuid}", fontSize = 10.sp, color = Color.Gray)
                        Text("R:${c.readable}  W:${c.writable}  WNR:${c.writableNoResponse}  N:${c.notifiable}  I:${c.indicatable}", fontSize = 10.sp)
                        c.lastValueHex?.let { Text("HEX $it", fontSize = 10.sp, color = Color.Gray) }
                        c.lastValueAscii?.takeIf { it.isNotBlank() }?.let { Text("ASCII $it", fontSize = 10.sp, color = AtomosCyan) }
                    }
                }
            }
            item { Text("Recent notifications / reads", color = Color.Gray, modifier = Modifier.padding(top = 10.dp)) }
            items(events.reversed().take(40)) { event -> Text(event, fontSize = 10.sp, color = Color.LightGray) }
        }
    }
}

@Composable
private fun ProtocolScreen(vm: AtomosViewModel) {
    val current by vm.protocol.collectAsState()
    var json by remember { mutableStateOf(vm.protocolTemplate()) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        AppHeader("Protocol Profile", current?.name ?: "No validated profile", onBack = { vm.go(AtomosViewModel.Screen.REMOTE) })
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Load only UUIDs and command payloads validated from Atomos SDK/documentation or your own hardware capture. Random writes are blocked.", color = Color.LightGray, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = json, onValueChange = { json = it }, modifier = Modifier.fillMaxWidth().weight(1f), label = { Text("Protocol JSON") })
            Spacer(Modifier.height(10.dp))
            Button(onClick = {
                val r = vm.loadProtocolJson(json)
                message = r.fold({ "Validated profile saved" }, { it.message ?: "Invalid profile" })
            }, modifier = Modifier.fillMaxWidth()) { Text("VALIDATE + SAVE") }
            message?.let { Text(it, color = if (it.startsWith("Validated")) AtomosCyan else MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
private fun CompatibilityScreen(vm: AtomosViewModel) {
    Column(Modifier.fillMaxSize()) {
        AppHeader("Device Compatibility", "Supported recorder families", onBack = { vm.go(AtomosViewModel.Screen.HOME) })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vm.profiles, key = { it.id }) { profile ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(profile.displayName, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(profile.family.name, color = AtomosCyan, fontSize = 11.sp)
                        profile.accessoryNote?.let { Text(it, color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
                        profile.maxRaw?.let { Text("Maximum RAW tier: $it", color = Color.Gray, fontSize = 11.sp) }
                        Spacer(Modifier.height(8.dp))
                        profile.capabilities.forEach { (cap, status) -> CapabilityRow(cap, status) }
                        Text(profile.notes, color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilityRow(capability: AtomosCapability, status: CapabilityStatus) {
    val color = when (status) {
        CapabilityStatus.BUILT_IN -> AtomosCyan
        CapabilityStatus.REQUIRES_ACCESSORY -> Color(0xFFFFCC66)
        CapabilityStatus.OPTIONAL_ACTIVATION -> Color(0xFF9DB7FF)
        CapabilityStatus.VALIDATE -> Color.Gray
        CapabilityStatus.NOT_AVAILABLE -> Color.DarkGray
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(capability.name.replace('_', ' '), Modifier.weight(1f), fontSize = 11.sp)
        Text(status.name.replace('_', ' '), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NetworkScreen(vm: AtomosViewModel) {
    val status by vm.networkStatus.collectAsState()
    val services by vm.networkServices.collectAsState()
    val lanDevices by vm.lanDevices.collectAsState()
    val probes by vm.networkProbeResults.collectAsState()
    var host by remember { mutableStateOf("") }
    var ports by remember { mutableStateOf("80,443,5960") }

    DisposableEffect(Unit) {
        vm.startNetworkDiscovery()
        onDispose { vm.stopNetworkDiscovery() }
    }

    Column(Modifier.fillMaxSize()) {
        AppHeader("Network Discovery", "Wi-Fi / Ethernet / NDI services", onBack = { vm.go(AtomosViewModel.Screen.HOME) })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AtomosSurface2)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${status.transport} Ã¢â‚¬Â¢ ${if (status.connected) "CONNECTED" else "OFFLINE"}", color = AtomosCyan, fontWeight = FontWeight.Bold)
                        Text(status.localAddresses.joinToString().ifBlank { "No local address" }, color = Color.LightGray, fontSize = 11.sp)
                        Text(status.message, color = Color.Gray, fontSize = 10.sp)
                        Text("Discovery combines mDNS/DNS-SD, SSDP, neighbour-cache hints and a bounded same-subnet scan. Same Wi-Fi now identifies the Shogun and launches BLE pairing from the device card. ATOMOS documents AtomRemote control over Bluetooth LE; no public LAN remote-control API was found, so SSH/port 22 is never treated as a control interface.", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            item {
                OutlinedTextField(value = host, onValueChange = { host = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Recorder / device IP or hostname") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = ports, onValueChange = { ports = it }, modifier = Modifier.fillMaxWidth(), label = { Text("TCP ports") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.probeNetwork(host, ports) }, modifier = Modifier.weight(1f)) { Text("PROBE HOST") }
                    OutlinedButton(onClick = { vm.stopNetworkDiscovery(); vm.startNetworkDiscovery() }, modifier = Modifier.weight(1f)) { Text("RESCAN") }
                }
            }
            item { Text("SAME-LAN DEVICES (${lanDevices.size})", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            if (lanDevices.isEmpty()) {
                item { Text("Scanning the current Wi-Fi/Ethernet LAN...", color = Color.Gray) }
            } else {
                items(lanDevices, key = { it.host }) { device -> LanDeviceCard(device, onProbe = { vm.probeNetwork(device.host, "80,443,554,5960,22,8000,8080") }, onPairRemote = { vm.startWifiAssistedRemote(device) }) }
            }

            if (probes.isNotEmpty()) {
                item { Text("PORT PROBE", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                items(probes) { p ->
                    Text("${p.host}:${p.port}  ${if (p.reachable) "OPEN" else "NO RESPONSE"}  ${p.latencyMs}ms", color = if (p.reachable) AtomosCyan else Color.Gray, fontSize = 11.sp)
                }
            }
            item { Text("DISCOVERED SERVICES (${services.size})", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            if (services.isEmpty()) {
                item { Text("No mDNS/DNS-SD services found yet", color = Color.Gray) }
            } else {
                items(services, key = { it.key }) { service -> NetworkServiceCard(service) }
            }
        }
    }
}

@Composable
private fun LanDeviceCard(device: LanDeviceCandidate, onProbe: () -> Unit, onPairRemote: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (device.likelyAtomos) AtomosSurface2 else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (device.likelyAtomos) "ATOMOS CANDIDATE Ã¢â‚¬Â¢ ${device.displayName}" else device.displayName,
                        color = if (device.likelyAtomos) AtomosCyan else Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(device.host, color = Color.LightGray, fontSize = 11.sp)
                    Text("Seen via ${device.sources.joinToString().ifBlank { "LAN" }}", color = Color.Gray, fontSize = 10.sp)
                    if (device.serviceTypes.isNotEmpty()) Text(device.serviceTypes.joinToString(), color = Color.Gray, fontSize = 9.sp)
                    if (device.openPorts.isNotEmpty()) Text("Open ports: ${device.openPorts.sorted().joinToString()}", color = Color.Gray, fontSize = 9.sp)
                    if (device.evidence.isNotBlank()) Text(device.evidence, color = Color.Gray, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (device.likelyAtomos) TextButton(onClick = onPairRemote) { Text("PAIR REMOTE") }
                    TextButton(onClick = onProbe) { Text("PROBE") }
                }
            }
        }
    }
}

@Composable
private fun NetworkServiceCard(service: NetworkServiceCandidate) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(service.serviceName, fontWeight = FontWeight.Bold)
            Text(service.serviceType, color = if (service.serviceType.contains("ndi", true)) AtomosCyan else Color.Gray, fontSize = 11.sp)
            Text("${service.host ?: "unresolved"}${service.port?.let { ":$it" } ?: ""}", color = Color.LightGray, fontSize = 11.sp)
            if (service.attributes.isNotEmpty()) Text(service.attributes.entries.joinToString { "${it.key}=${it.value}" }, color = Color.Gray, fontSize = 9.sp)
        }
    }
}

@Composable
private fun MultiDeviceScreen(vm: AtomosViewModel) {
    val saved by vm.savedAll.collectAsState()
    val sessions by vm.multiSessions.collectAsState()
    val protocol by vm.protocol.collectAsState()
    val controlReady = protocol?.canTransmit == true

    Column(Modifier.fillMaxSize()) {
        AppHeader("Multi Device", "Connect All / Gang command workspace", onBack = { vm.go(AtomosViewModel.Screen.HOME) })
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.connectAllSaved() }, enabled = saved.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("CONNECT ALL") }
                OutlinedButton(onClick = { vm.disconnectAllSaved() }, modifier = Modifier.weight(1f)) { Text("DISCONNECT") }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.multiSend("record-start") }, enabled = controlReady && sessions.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = AtomosRed), modifier = Modifier.weight(1f)) { Text("RECORD ALL") }
                OutlinedButton(onClick = { vm.multiSend("record-stop") }, enabled = controlReady && sessions.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("STOP ALL") }
            }
            if (!controlReady) Text("Record All / Stop All stays locked until a validated Atomos BLE command profile is loaded.", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            Text("SAVED RECORDERS (${saved.size})", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 8.dp)) {
                items(saved, key = { it.address }) { d ->
                    val session = sessions.firstOrNull { it.address == d.address }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.name, fontWeight = FontWeight.Bold)
                                    Text(d.address, color = Color.Gray, fontSize = 10.sp)
                                }
                                Text(session?.state?.uppercase() ?: "SAVED", color = if (session?.state?.contains("ready") == true || session?.state == "connected") AtomosCyan else Color.Gray, fontSize = 10.sp)
                            }
                            session?.lastAction?.let { Text("Last action: $it", color = Color.Gray, fontSize = 10.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AirGluScreen(vm: AtomosViewModel) {
    val saved by vm.airGluConfig.collectAsState()
    var role by remember(saved) { mutableStateOf(saved.role) }
    var channelText by remember(saved) { mutableStateOf(saved.rfChannel.toString()) }
    var group by remember(saved) { mutableStateOf(saved.groupName) }
    var gang by remember(saved) { mutableStateOf(saved.gangRecord) }
    var timecode by remember(saved) { mutableStateOf(saved.timecodeSource) }
    var version by remember(saved) { mutableStateOf(saved.deviceVersion ?: "AGu2s 2.31;TFP2s 1.6;8") }

    Column(Modifier.fillMaxSize()) {
        AppHeader("AirGlu Sync", "Wireless sync and control", onBack = { vm.go(AtomosViewModel.Screen.HOME) })
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AtomosSurface2)) {
                Column(Modifier.padding(14.dp)) {
                    Text(if (vm.airGluNativeAvailable) "NATIVE AIRGLU READY" else "AIRGLU RF BRIDGE LOCKED", color = AtomosCyan, fontWeight = FontWeight.Bold)
                    Text(vm.airGluImplementationName, color = Color.LightGray, fontSize = 11.sp)
                    Text("AirGlu RF is a separate wireless control/sync transport. This screen stores workflow settings but does not transmit guessed RF packets.", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("ROLE", color = Color.Gray, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = role == AirGluRole.SERVER, onClick = { role = AirGluRole.SERVER }, label = { Text("SERVER") })
                FilterChip(selected = role == AirGluRole.CLIENT, onClick = { role = AirGluRole.CLIENT }, label = { Text("CLIENT") })
            }
            OutlinedTextField(value = channelText, onValueChange = { channelText = it.filter { ch -> ch.isDigit() }.take(2) }, label = { Text("RF Channel (1-12)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = group, onValueChange = { group = it }, label = { Text("Group name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = timecode, onValueChange = { timecode = it }, label = { Text("Timecode source") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = version, onValueChange = { version = it }, label = { Text("Recorder AirGlu version") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Gang Record", Modifier.weight(1f))
                Switch(checked = gang, onCheckedChange = { gang = it })
            }
            Button(onClick = {
                vm.saveAirGlu(role, channelText.toIntOrNull() ?: 1, group, gang, timecode, version)
            }, modifier = Modifier.fillMaxWidth()) { Text("SAVE WORKFLOW") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.tryApplyAirGlu() }, modifier = Modifier.fillMaxWidth()) { Text("TEST NATIVE BRIDGE") }
        }
    }
}

@Composable
private fun NdiPreviewScreen(vm: AtomosViewModel) {
    val services by vm.networkServices.collectAsState()
    val previewState by vm.ndiPreviewState.collectAsState()
    val sources = remember(services) { NdiSourceRegistry.fromNetwork(services) }

    LaunchedEffect(Unit) {
        vm.refreshNetworkStatus()
        vm.startNetworkDiscovery()
    }
    DisposableEffect(Unit) {
        onDispose {
            vm.stopNdi()
            vm.stopNetworkDiscovery()
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppHeader("NDI / Network Preview", "Network video sources", onBack = { vm.go(AtomosViewModel.Screen.HOME) })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AtomosSurface2)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            if (vm.ndiNativeAvailable) "NATIVE NDI BRIDGE READY" else "NDI SOURCE DISCOVERY READY",
                            color = AtomosCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(vm.ndiImplementationName, color = Color.LightGray, fontSize = 11.sp)
                        Text(
                            "mDNS can discover NDI-capable sources. Actual video decoding remains locked until the native NDI SDK is linked and licensed.",
                            color = Color.Gray,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            item {
                when (val state = previewState) {
                    NdiPreviewState.Idle -> Text("No preview source selected", color = Color.Gray, fontSize = 11.sp)
                    is NdiPreviewState.Ready -> Text("Ready: ${state.source.name}", color = AtomosCyan, fontSize = 11.sp)
                    is NdiPreviewState.Unavailable -> Text(state.message, color = Color.Gray, fontSize = 11.sp)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.stopNetworkDiscovery(); vm.startNetworkDiscovery() }, modifier = Modifier.weight(1f)) { Text("RESCAN") }
                    OutlinedButton(onClick = { vm.stopNdi() }, modifier = Modifier.weight(1f)) { Text("STOP PREVIEW") }
                }
            }
            item { Text("NDI SOURCES (${sources.size})", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            if (sources.isEmpty()) {
                item { Text("No _ndi._tcp. services discovered on the current network.", color = Color.Gray) }
            } else {
                items(sources, key = { it.key }) { source ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(source.name, fontWeight = FontWeight.Bold)
                            Text("${source.host ?: "unresolved"}${source.port?.let { ":$it" } ?: ""}", color = Color.LightGray, fontSize = 11.sp)
                            if (source.attributes.isNotEmpty()) {
                                Text(source.attributes.entries.joinToString { "${it.key}=${it.value}" }, color = Color.Gray, fontSize = 9.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { vm.prepareNdi(source) }, enabled = source.resolved, modifier = Modifier.fillMaxWidth()) {
                                Text(if (vm.ndiNativeAvailable) "OPEN PREVIEW" else "PREPARE SOURCE")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetsScreen(vm: AtomosViewModel) {
    val presets by vm.presets.collectAsState()
    val protocol by vm.protocol.collectAsState()
    var name by remember { mutableStateOf("") }
    var commands by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val availableKeys = protocol?.commands?.availableKeys().orEmpty().sorted()

    Column(Modifier.fillMaxSize()) {
        AppHeader("Recorder Presets", "Saved recorder configurations", onBack = { vm.go(AtomosViewModel.Screen.HOME) })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AtomosSurface2)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("VALIDATED COMMAND PRESETS", color = AtomosCyan, fontWeight = FontWeight.Bold)
                        Text(
                            "A preset is only a sequence of command keys. The app transmits a step only when that key has a validated payload in the active protocol profile.",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                        Text(
                            "Available now: ${availableKeys.joinToString().ifBlank { "none" }}",
                            color = Color.LightGray,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            item {
                OutlinedTextField(name, { name = it }, label = { Text("Preset name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    commands,
                    { commands = it },
                    label = { Text("Command keys, comma separated") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Example: waveform-on,peaking-on or validated custom keys from extraCommands") }
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    vm.createPreset(name, commands, note)
                    name = ""; commands = ""; note = ""
                }, modifier = Modifier.fillMaxWidth()) { Text("SAVE PRESET") }
            }
            item { Text("PRESETS (${presets.size})", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            items(presets, key = { it.id }) { preset ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(preset.name, fontWeight = FontWeight.Bold)
                        Text(preset.commandKeys.joinToString().ifBlank { "No commands" }, color = Color.LightGray, fontSize = 10.sp)
                        if (preset.note.isNotBlank()) Text(preset.note, color = Color.Gray, fontSize = 10.sp)
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.applyPreset(preset) }, enabled = protocol?.canTransmit == true, modifier = Modifier.weight(1f)) { Text("APPLY") }
                            OutlinedButton(onClick = { vm.removePreset(preset.id) }, modifier = Modifier.weight(1f)) { Text("REMOVE") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureScreen(vm: AtomosViewModel) {
    val context = LocalContext.current
    val snapshot by vm.gattSnapshot.collectAsState()
    val events by vm.gattEvents.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        AppHeader("Protocol Capture", "GATT capture + export", onBack = { vm.go(AtomosViewModel.Screen.HOME) })
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AtomosSurface2)) {
                Column(Modifier.padding(14.dp)) {
                    Text("SAFE CAPTURE MODE", color = AtomosCyan, fontWeight = FontWeight.Bold)
                    Text("Exports discovered services/characteristics and recent read/notify events. It does not generate or transmit guessed Atomos command packets.", color = Color.Gray, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            StatusCard("DEVICE", snapshot?.deviceAddress ?: "Not connected", Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusCard("LINK", "${snapshot?.characteristics?.size ?: 0} chars", Modifier.weight(1f))
                StatusCard("EVENTS", events.size.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    vm.exportProtocolCapture().fold(
                        onSuccess = { result ->
                            message = "Exported: ${result.summary}"
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_STREAM, result.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Share ATOMOS GATT capture"))
                        },
                        onFailure = { message = it.message ?: "Capture export failed" }
                    )
                },
                enabled = snapshot != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("EXPORT + SHARE JSON") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.go(AtomosViewModel.Screen.GATT) }, modifier = Modifier.fillMaxWidth()) { Text("OPEN GATT INSPECTOR") }
            message?.let { Text(it, color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp)) }
        }
    }
}

@Composable
private fun FinalQaScreen(vm: AtomosViewModel) {
    val report by vm.qaReport.collectAsState()
    LaunchedEffect(Unit) { vm.runFinalQa() }

    Column(Modifier.fillMaxSize()) {
        AppHeader("Final QA", "System verification", onBack = { vm.go(AtomosViewModel.Screen.HOME) })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AtomosSurface2)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("RELEASE READINESS", color = AtomosCyan, fontWeight = FontWeight.Bold)
                        Text(
                            report?.let { "${it.passed}/${it.total} checks passing Ã¢â‚¬Â¢ ${it.blockers} blocker(s)" } ?: "Running checks...",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            item { OutlinedButton(onClick = { vm.runFinalQa() }, modifier = Modifier.fillMaxWidth()) { Text("RUN QA AGAIN") } }
            items(report?.checks.orEmpty(), key = { it.id }) { check ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
                        Text(if (check.passed) "PASS" else if (check.blocking) "BLOCK" else "INFO", color = if (check.passed) AtomosCyan else if (check.blocking) AtomosRed else Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
                        Column(Modifier.weight(1f)) {
                            Text(check.label, fontWeight = FontWeight.Bold)
                            Text(check.detail, color = Color.Gray, fontSize = 10.sp)
                        }
                    }
                }
            }
            item {
                Text(
                    "A passing application QA does not unlock proprietary recorder commands. Record/monitor writes still require an explicitly validated Atomos protocol profile.",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun AdvancedControlsScreen(vm: AtomosViewModel) {
    val protocol by vm.protocol.collectAsState()
    val available = protocol?.commands?.availableKeys().orEmpty()
    val sections = AdvancedControlCatalog.actions.groupBy { it.section }

    Column(Modifier.fillMaxSize()) {
        AppHeader("Advanced Recorder", "Validated AtomRemote-style controls", onBack = { vm.go(AtomosViewModel.Screen.REMOTE) })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AtomosSurface2)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("PROFILE-GATED CONTROL", color = AtomosCyan, fontWeight = FontWeight.Bold)
                        Text(
                            "Buttons are enabled only when the active validated protocol profile contains the matching command key in extraCommands.",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                }
            }
            sections.forEach { (section, actions) ->
                item { Text(section, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                items(actions, key = { it.commandKey }) { action ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(action.label, fontWeight = FontWeight.Bold)
                                Text(action.description, color = Color.Gray, fontSize = 10.sp)
                                Text(action.commandKey, color = Color.DarkGray, fontSize = 9.sp)
                            }
                            Button(
                                onClick = { vm.send(action.commandKey) },
                                enabled = protocol?.canTransmit == true && available.contains(action.commandKey)
                            ) { Text("SET") }
                        }
                    }
                }
            }
        }
    }
}




