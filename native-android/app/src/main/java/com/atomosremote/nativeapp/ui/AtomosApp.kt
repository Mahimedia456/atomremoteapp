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
import com.atomosremote.nativeapp.monitor.MonitorLutState
import com.atomosremote.nativeapp.monitor.MonitorDisplayState
import com.atomosremote.nativeapp.network.LanDeviceCandidate
import com.atomosremote.nativeapp.network.NetworkServiceCandidate
import com.atomosremote.nativeapp.ndi.NdiPreviewState
import com.atomosremote.nativeapp.ndi.NdiSourceRegistry
import com.atomosremote.nativeapp.ui.theme.AtomosCyan
import com.atomosremote.nativeapp.ui.theme.AtomosRed
import com.atomosremote.nativeapp.ui.theme.AtomosSurface2
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

@Composable
fun AtomosApp(vm: AtomosViewModel = viewModel()) {
    val screen by vm.screen.collectAsState()
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
    }
}

@Composable
private fun AppHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0D0F))
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            TextButton(/* AGENT7 20.03.3.1 ROBUST BACK BUTTON REPAIR */
            modifier = Modifier
                .heightIn(min = 44.dp)
                .widthIn(min = 48.dp),
onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                // AGENT7 20.03.4 COMPACT BACK ICON
            Text("<", fontSize = 32.sp, color = AtomosCyan)
            }
            Spacer(Modifier.width(4.dp))
        }
        Box(Modifier.size(8.dp).background(AtomosCyan, CircleShape))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = Color(0xFF868D90), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("ATOMOS", color = AtomosCyan, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp, fontSize = 14.sp)
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

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(painterResource(R.drawable.atomos_logo), contentDescription = "ATOMOS", modifier = Modifier.size(112.dp))
            Spacer(Modifier.height(14.dp))
            Text("ATOMOS REMOTE", fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text("Recorder Control", color = Color.Gray)
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { vm.go(AtomosViewModel.Screen.CONNECT) },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("CONNECT ATOMOS", fontWeight = FontWeight.Bold) }

            if (saved != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { vm.reconnectSaved() },
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) { Text("RECONNECT ${saved!!.name.uppercase()}") }
            }

            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AtomosSurface2)) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("SAME WI-FI / LAN", color = AtomosCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                "${networkStatus.transport} â€¢ ${if (networkStatus.connected) "CONNECTED" else "OFFLINE"} â€¢ ${lanDevices.size} device(s)",
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )
                            networkStatus.localAddresses.firstOrNull { it.contains('.') }?.let {
                                Text("Phone: $it", color = Color.Gray, fontSize = 10.sp)
                            }
                        }
                        TextButton(onClick = { vm.stopNetworkDiscovery(); vm.startNetworkDiscovery() }) { Text("SCAN") }
                    }
                    if (lanDevices.isEmpty()) {
                        Text("Searching mDNS, SSDP and the current local subnet...", color = Color.Gray, fontSize = 10.sp)
                    } else {
                        lanDevices.take(4).forEach { device ->
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (device.likelyAtomos) "ATOMOS CANDIDATE â€¢ ${device.displayName}" else device.displayName,
                                        color = if (device.likelyAtomos) AtomosCyan else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = if (device.likelyAtomos) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${device.host} â€¢ ${device.sources.joinToString()}${if (device.openPorts.isNotEmpty()) " â€¢ ports ${device.openPorts.sorted().joinToString()}" else ""}",
                                        color = Color.Gray,
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (device.likelyAtomos) {
                                    TextButton(onClick = { vm.startWifiAssistedRemote(device) }) { Text("PAIR REMOTE") }
                                }
                            }
                        }
                        if (lanDevices.size > 4) {
                            TextButton(onClick = { vm.go(AtomosViewModel.Screen.NETWORK) }) { Text("VIEW ALL ${lanDevices.size} DEVICES") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.go(AtomosViewModel.Screen.NETWORK) }, modifier = Modifier.weight(1f)) { Text("NETWORK (${lanDevices.size})") }
                OutlinedButton(onClick = { vm.go(AtomosViewModel.Screen.MULTI) }, modifier = Modifier.weight(1f)) { Text("MULTI (${savedAll.size})") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.go(AtomosViewModel.Screen.COMPATIBILITY) }, modifier = Modifier.weight(1f)) { Text("DEVICES") }
                OutlinedButton(onClick = { vm.go(AtomosViewModel.Screen.AIRGLU) }, modifier = Modifier.weight(1f)) { Text("AIRGLU") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.go(AtomosViewModel.Screen.NDI) }, modifier = Modifier.weight(1f)) { Text("NDI") }
                OutlinedButton(onClick = { vm.go(AtomosViewModel.Screen.PRESETS) }, modifier = Modifier.weight(1f)) { Text("PRESETS") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.go(AtomosViewModel.Screen.CAPTURE) }, modifier = Modifier.weight(1f)) { Text("CAPTURE") }
                OutlinedButton(onClick = { vm.go(AtomosViewModel.Screen.QA) }, modifier = Modifier.weight(1f)) { Text("FINAL QA") }
            }
        }
    }
}

private fun permissionsGranted(context: android.content.Context): Boolean = BlePermission.runtimePermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun ConnectScreen(vm: AtomosViewModel) {
    val context = LocalContext.current
    val devices by vm.devices.collectAsState()
    val scanState by vm.scanState.collectAsState()
    val wifiAssistTarget by vm.wifiAssistTarget.collectAsState()
    var permissionDenied by remember { mutableStateOf(false) }

    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (vm.bluetoothEnabled()) vm.startScan()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val ok = BlePermission.runtimePermissions().all { result[it] == true || ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        permissionDenied = !ok
        if (ok) {
            if (vm.bluetoothEnabled()) vm.startScan() else enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    fun begin() {
        permissionDenied = false
        if (!permissionsGranted(context)) permissionLauncher.launch(BlePermission.runtimePermissions())
        else if (!vm.bluetoothEnabled()) enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        else vm.startScan()
    }

    LaunchedEffect(Unit) { begin() }
    DisposableEffect(Unit) { onDispose { vm.stopScan() } }

    Column(Modifier.fillMaxSize().background(Color(0xFF0A0C0D))) {
        AppHeader(
            "Find your device",
            "Compatible ATOMOS recorders",
            onBack = { vm.stopScan(); vm.stopPairingPresence(); vm.clearWifiAssist(); vm.go(AtomosViewModel.Screen.HOME) }
        )

    
    LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column {
                    Text("Connect an ATOMOS recorder", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "Open Bluetooth / AirGlu Pair on the recorder, then scan.",
                        color = Color(0xFF868D90),
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            wifiAssistTarget?.let { target ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151A1C)), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("SAME WI-FI RECORDER", color = AtomosCyan, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Text(target.displayName, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
                            Text(target.host, color = Color(0xFF868D90), fontSize = 11.sp)
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151A1C)), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(AtomosCyan, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("BLUETOOTH REMOTE", color = Color(0xFF868D90), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("ATOMOS recorder pairing", fontWeight = FontWeight.Bold, color = AtomosCyan)
                        }
                    }
                }
            }

            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(118.dp).border(1.dp, Color(0xFF2B3032), CircleShape), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(80.dp).border(1.dp, Color(0xFF2B3032), CircleShape))
                        Box(Modifier.size(42.dp).border(1.dp, Color(0xFF2B3032), CircleShape))
                        if (scanState == "scanning") CircularProgressIndicator(Modifier.size(112.dp), strokeWidth = 2.dp, color = AtomosCyan)
                        Box(Modifier.size(10.dp).background(AtomosCyan, CircleShape))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (scanState == "scanning") "Scanning for AirGlu BT devicesâ€¦" else "Scan state: $scanState",
                        color = Color(0xFF868D90),
                        fontSize = 12.sp
                    )
                    TextButton(onClick = { begin() }) { Text("SCAN AGAIN") }
                }
            }

            if (permissionDenied) {
                item { Text("Nearby Bluetooth permission is required for BLE discovery.", color = AtomosRed, fontSize = 12.sp) }
            }

            val visibleDevices = devices.filter { likelyAtomosAdvertisement(it) && it.connectable != false }
            if (visibleDevices.isEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151A1C)), shape = RoundedCornerShape(14.dp)) {
                        Text(
                            "No compatible ATOMOS recorder found yet. Open the recorder Bluetooth/AirGlu Pair screen and scan again.",
                            color = Color(0xFF868D90),
                            fontSize = 12.sp,
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
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1F21)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(Color(0xFF222729), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(11.dp).background(AtomosCyan, CircleShape))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(displayName, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                recorderModelCode(ad.name)?.let { code ->
                    Text("Model code: $code", color = AtomosCyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    if (service9383) "ATOMOS Remote" else "Compatible ATOMOS device",
                    color = Color(0xFF9AA2A5),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
                Text("${ad.address} Â· ${ad.rssi} dBm", color = Color(0xFF868D90), fontSize = 10.sp)
                ad.advertisedPairingId?.let { Text("Pairing ID $it", color = Color(0xFFE8A23B), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
            Text(if (wifiAssist) "PAIR" else "CONNECT", color = AtomosCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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

    Column(Modifier.fillMaxSize().background(Color(0xFF0A0C0D))) {
        AppHeader(
            connectedRecorderName?.takeIf { it.isNotBlank() } ?: selected?.let { vm.displayNameFor(it) } ?: "Connect recorder",
            selected?.let { ad -> recorderModelCode(ad.name)?.let { "AirGlu $it Â· ${ad.address}" } ?: ad.address } ?: "ATOMOS recorder",
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
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151A1C)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PAIRING ID", color = Color(0xFF868D90), fontSize = 10.sp, letterSpacing = 1.2.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            pairingId ?: if (pairingAccepted) "Accepted" else "Waitingâ€¦",
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
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2115)), shape = RoundedCornerShape(14.dp)) {
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
                                    if (candidate.exactAg2Source) "Recorder advertisement" else "Pairing signal Â· ${candidate.rssi} dBm",
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
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151A1C)), shape = RoundedCornerShape(14.dp)) {
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

private enum class RemotePanel(val title: String, val subtitle: String, val badge: String) {
    MONITOR_ASSIST("Monitor Assist", "Focus, exposure and zoom tools", "MA"),
    SCOPES("Scopes", "Waveform and vectorscope controls", "SC"),
    GUIDES("Grid & Guides", "Grid, frame guides and safe areas", "GD"),
    ANAMORPHIC("Anamorphic", "De-squeeze monitoring ratios", "AN"),
    MONITORING("Monitoring", "Native, Rec.709, HLG, PQ and LUT", "MN"),
    CODEC("Record Codec", "Codec profile and record format", "RC"),
    INPUT_FORMAT("Input / Format", "Recorder input and output details", "IF")
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
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0D0F))
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        val compact = maxWidth < 380.dp
        val labelSize = if (compact) 8.5.sp else 9.5.sp
        val tabHeight = if (compact) 36.dp else 40.dp
        val rowGap = 6.dp
        val firstRow = listOf(ConnectedTopTab.REMOTE, ConnectedTopTab.INPUT, ConnectedTopTab.MONITOR, ConnectedTopTab.AUDIO)
        val secondRow = listOf(ConnectedTopTab.OUTPUT, ConnectedTopTab.MEDIA, ConnectedTopTab.SETTINGS)

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(rowGap)) {
            listOf(firstRow, secondRow).forEach { rowTabs ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowTabs.forEach { tab ->
                        val active = selected == tab
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(tabHeight)
                                .clickable { onSelected(tab) },
                            shape = RoundedCornerShape(11.dp),
                            color = if (active) Color(0xFF173331) else Color(0xFF101416),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (active) AtomosCyan.copy(alpha = 0.72f) else Color(0xFF20272A)
                            )
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    tab.label,
                                    color = if (active) AtomosCyan else Color(0xFF9AA3A6),
                                    fontSize = labelSize,
                                    fontWeight = if (active) FontWeight.Black else FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
private fun RemoteScreen(vm: AtomosViewModel) {
    val selected by vm.selected.collectAsState()
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
    val codecSupportedProfiles by vm.codecSupportedProfiles.collectAsState()
    val codecSupportedBitDepths by vm.codecSupportedBitDepths.collectAsState()
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

    val recorderName = selected?.let { vm.displayNameFor(it) }
        ?.takeIf { it.isNotBlank() && !it.equals("Unknown", true) }
        ?: connectedRecorderName?.takeIf {
            it.isNotBlank() && !it.equals("Unknown", true) && it.length > 3
        }
        ?: "ATOMOS Recorder"
    val recorderAddress = selected?.let { ad -> recorderModelCode(ad.name)?.let { "Model code: $it Â· ${ad.address}" } ?: ad.address } ?: "Bluetooth Remote"
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
                activePanel == RemotePanel.MONITORING

        if (transportReady && monitorVisible) {
            vm.ensureMonitorSessionSnapshot(
                reason = when {
                    connectedTab == ConnectedTopTab.MONITOR -> "monitor-tab"
                    activePanel == RemotePanel.MONITOR_ASSIST -> "monitor-assist-panel"
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
            title = "ATOMOS Remote",
            subtitle = recorderName,
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

    LazyColumn(
            state = remoteListState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                RemoteConnectionCard(
                    recorderName = recorderName,
                    recorderAddress = recorderAddress,
                    pairingCode = pairingCode,
                    title = connectionTitle,
                    subtitle = connectionSubtitle,
                    phase = remote.phase,
                    transportReady = transportReady,
                    commandInFlight = commandInFlight,
                    hasError = hasSessionError
                )
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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1315))
                ) {
                    Column(Modifier.padding(14.dp)) {
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

                        Button(
                            onClick = {
                                if (transportUi.mode != RemoteTransportUiController.Mode.RECORD) {
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
                            },
                            enabled = transportButtonsEnabled,
                            modifier = Modifier.fillMaxWidth().height(72.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AtomosRed,
                                contentColor = Color.White,
                                disabledContainerColor = Color(0xFF291719),
                                disabledContentColor = Color(0xFF72585B)
                            )
                        ) {
                            if (recordPending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Box(Modifier.size(13.dp).background(Color.White, CircleShape))
                            }
                            Spacer(Modifier.width(11.dp))
                            Text(
                                recordLabel,
                                fontSize = if (recordPending) 13.sp else 18.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.1.sp
                            )
                        }
                        Spacer(Modifier.height(9.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RemoteTransportButton(
                                label = if (transportUi.pendingAction == "record-stop") "STOPPING..." else "STOP",
                                enabled = transportButtonsEnabled,
                                accent = Color(0xFFE7EAEB),
                                modifier = Modifier.weight(1f),
                                loading = transportUi.pendingAction == "record-stop"
                            ) {
                                startUiAction("record-stop", "Record Stop", "Record Stop") {
                                    vm.sendRemoteTransportFromUi("record-stop")
                                }
                            }

                            RemoteTransportButton(
                                label = if (transportUi.pendingAction == "play") "PLAYING..." else "PLAY",
                                enabled = transportButtonsEnabled,
                                accent = AtomosCyan,
                                modifier = Modifier.weight(1f),
                                loading = transportUi.pendingAction == "play"
                            ) {
                                startUiAction("play", "Play", "Play") {
                                    vm.sendRemoteTransportFromUi("play")
                                }
                            }

                            RemoteTransportButton(
                                label = if (transportUi.pendingAction == "pause") "PAUSING..." else "PAUSE",
                                enabled = transportButtonsEnabled,
                                accent = Color(0xFFE7EAEB),
                                modifier = Modifier.weight(1f),
                                loading = transportUi.pendingAction == "pause"
                            ) {
                                startUiAction("pause", "Pause", "Pause") {
                                    vm.sendRemoteTransportFromUi("pause")
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("TOOLS", color = Color(0xFF8B9497), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (transportReady) {
                            "READY Â· INPUT ${inputCodecCollector.inputResponses}/${inputCodecCollector.inputExpected}" +
                                if (inputCodecCollector.codecVerified) " Â· CODEC OK" else ""
                        } else "CONNECTING",
                        color = if (transportReady) AtomosCyan else Color(0xFF6F787B),
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
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        RemoteFeatureTile(RemotePanel.MONITOR_ASSIST, monitorAssistSummary(monitorZoomLevel, monitorFocusAssist, monitorZebra, monitorFalseColor, monitorBlueOnly), transportReady, Modifier.weight(1f)) { activePanel = RemotePanel.MONITOR_ASSIST }
                        RemoteFeatureTile(RemotePanel.SCOPES, if (scopeEnabled) "On" else "Off", transportReady, Modifier.weight(1f)) { activePanel = RemotePanel.SCOPES }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        RemoteFeatureTile(RemotePanel.GUIDES, guidesSummary(frameGridEnabled, frameGuideEnabled, safeAreaMode), transportReady, Modifier.weight(1f)) { activePanel = RemotePanel.GUIDES }
                        RemoteFeatureTile(RemotePanel.ANAMORPHIC, anamorphicSummary(anamorphicMode), transportReady, Modifier.weight(1f)) { activePanel = RemotePanel.ANAMORPHIC }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        RemoteFeatureTile(RemotePanel.MONITORING, monitorModeSummary(monitorDisplayMode), transportReady, Modifier.weight(1f)) { activePanel = RemotePanel.MONITORING }
                        RemoteFeatureTile(RemotePanel.CODEC, codecSummary(codecProfile), transportReady, Modifier.weight(1f)) { activePanel = RemotePanel.CODEC }
                    }
                    RemoteFeatureTile(RemotePanel.INPUT_FORMAT, inputFormatInfo ?: "Open full Input module", transportReady, Modifier.fillMaxWidth()) {
                        connectedTab = ConnectedTopTab.INPUT
                        activePanel = null
                    }
                }
            }


            if (activePanel != null) {
                item {
                    val panel = activePanel!!
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(panel.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { activePanel = null }) { Text("CLOSE", color = AtomosCyan, fontSize = 10.sp) }
                        }
                        when (panel) {
                            RemotePanel.MONITOR_ASSIST -> RemoteMonitorAssistCard(
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
                            RemotePanel.SCOPES -> RemoteScopesCard(
                                enabled = transportReady,
                                scopeState = monitorScopeState,
                                onRefresh = vm::refreshScopeCore,
                                onEnabledChange = vm::setScopeEnabled,
                                onModeChange = vm::setScopeMode,
                                onSizeChange = vm::setScopeSize,
                                onOpacityChange = vm::setScopeOpacity,
                                onBrightnessChange = vm::setScopeBrightness
                            )
                            RemotePanel.GUIDES -> RemoteGuidesCard(
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
                            RemotePanel.ANAMORPHIC -> RemoteAnamorphicCard(transportReady, monitorAnamorphicState, vm::refreshAnamorphicMode, vm::setAnamorphicMode)
                            RemotePanel.MONITORING -> RemoteMonitoringCard(
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
                            RemotePanel.CODEC -> RemoteCodecCard(
                                enabled = transportReady,
                                profile = codecProfile,
                                bitDepth = codecBitDepth,
                                confirmedProfile = codecHardwareProfile,
                                supportedProfiles = codecSupportedProfiles,
                                supportedBitDepths = codecSupportedBitDepths,
                                onProfileChange = vm::selectCodecProfile,
                                onBitDepthChange = vm::selectCodecBitDepth,
                                onVerify = vm::verifyCodecProfile,
                                onApply = vm::applyCodecProfile,
                                onRestartApply = vm::activateCodecProfile
                            )
                            RemotePanel.INPUT_FORMAT -> RemoteInputFormatCard(
                                enabled = transportReady, inputFormatInfo = inputFormatInfo, codecId = inputCodecId, cameraId = inputCameraId,
                                gammaId = inputGammaId, gamutId = inputGamutId, outputInfo = outputInfo, outputHdrTypeId = outputHdrTypeId, outputGamutId = outputGamutId,
                                rawHex = inputOutputRawHex, onRefresh = vm::refreshInputFormatSummary
                            )
                        }
                    }
                }
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
                                // R16.1: same shared recorder-authoritative codec
                                // controller/card used by Remote. No duplicate
                                // Monitor-only codec state is introduced.
                                RemoteCodecCard(
                                    enabled = transportReady,
                                    profile = codecProfile,
                                    bitDepth = codecBitDepth,
                                    confirmedProfile = codecHardwareProfile,
                                    supportedProfiles = codecSupportedProfiles,
                                    supportedBitDepths = codecSupportedBitDepths,
                                    onProfileChange = vm::selectCodecProfile,
                                    onBitDepthChange = vm::selectCodecBitDepth,
                                    onVerify = vm::verifyCodecProfile,
                                    onApply = vm::applyCodecProfile,
                                    onRestartApply = vm::activateCodecProfile
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

private fun monitorAssistSummary(zoomLevel: Int, focus: Boolean, zebra: Boolean, falseColor: Boolean, blueOnly: Boolean): String {
    val active = listOf(focus, zebra, falseColor, blueOnly).count { it }
    return when {
        zoomLevel > 0 -> "Zoom ${if (zoomLevel == 2) "2:1" else "1:1"}${if (active > 0) " · $active active" else ""}"
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
    return active.joinToString(" Â· ").ifBlank { "Ready" }
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

private fun codecBitDepthSummary(bitDepth: Int): String = when (bitDepth) {
    0 -> "10-bit 4:2:2"
    1 -> "8-bit 4:2:2"
    2 -> "8-bit 4:2:0"
    else -> "Unknown bit depth"
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
                            "Available: " + zoomCore.supportedLevels.joinToString(" · ") { zoomCore.label(it) },
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
                enabled && focusState.pendingKey != "enabled"
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
                        enabled && focusState.pendingKey != "type",
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
                                enabled && focusState.pendingKey != "colour",
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
                        if (focusReportedThreshold != null) onFocusThreshold(focusThresholdDraft.toInt())
                    },
                    valueRange = 0f..100f,
                    enabled = enabled && focusReportedThreshold != null && focusState.pendingKey != "threshold",
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (focusReportedThreshold != null) "${focusThresholdDraft.toInt()}%" else "READING",
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
                enabled && zebraState.pendingKey != "enabled"
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
                        if (zebraReportedThreshold != null) onZebraThreshold(zebraThresholdDraft.toInt())
                    },
                    valueRange = 0f..100f,
                    enabled = enabled && zebraReportedThreshold != null && zebraState.pendingKey != "threshold",
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (zebraReportedThreshold != null) "${zebraThresholdDraft.toInt()}%" else "READING",
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
                enabled && effectsState.settingsChangeable != false && effectsState.pendingKey != "false-colour"
            ) { onToggle("false-color", it) }

            RemoteMonitorToggle(
                "Blue Only",
                effectsState.blueOnlyEnabled ?: blueOnly,
                enabled && effectsState.settingsChangeable != false && effectsState.pendingKey != "blue-only"
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
                        RemoteAnalogArrow("▲", canPan) { commit(ny = (yFraction - 0.08f).coerceAtLeast(0f)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RemoteAnalogArrow("◀", canPan) { commit(nx = (xFraction - 0.08f).coerceAtLeast(0f)) }
                            RemoteAnalogArrow("●", canPan) { commit(0.5f, 0.5f) }
                            RemoteAnalogArrow("▶", canPan) { commit(nx = (xFraction + 0.08f).coerceAtMost(1f)) }
                        }
                        RemoteAnalogArrow("▼", canPan) { commit(ny = (yFraction + 0.08f).coerceAtMost(1f)) }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "X ${(xFraction * 100).toInt()}%  ·  Y ${(yFraction * 100).toInt()}%",
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
                enabled,
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
                                    enabled,
                                    Modifier.weight(1f)
                                ) { onFrameGuideFormat(value) }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }

            } else {
                Text(
                    if (guideState.pendingKey == "frame-guide") "Turning Frame Guide on; syncing recorder..." else "Turn Frame Guide on, then choose a frame ratio below",
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
                    "Custom guide detected · ratio ${guideState.customRatio ?: 13}. Custom ratio editing will be added only after the exact recorder scale is verified.",
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
                    2 -> "Title Safe Area selected · recorder should show the Title/Action double-frame state when supported by the active format"
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
                "CURRENT · ${MonitorAnamorphicState.label(state.currentMode)}",
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
    supportedProfiles: List<Int>,
    supportedBitDepths: List<Int>,
    onProfileChange: (Int) -> Unit,
    onBitDepthChange: (Int) -> Unit,
    onVerify: () -> Unit,
    onApply: () -> Unit,
    onRestartApply: () -> Unit = {}
) {
    var showRestartConfirm by remember { mutableStateOf(false) }
    fun profileAvailable(id: Int): Boolean = supportedProfiles.isEmpty() || id in supportedProfiles
    fun bitDepthAvailable(id: Int): Boolean = supportedBitDepths.isEmpty() || id in supportedBitDepths
    val recorderStateReady = confirmedProfile != null
    // R16.8 CURRENT-FIRST: do not let the Android draft invent recorder
    // state. The user can edit/apply only after the official macOS state pair
    // RecordPage(0x0643) + InputOutputSummary(0x0648) has produced a current
    // codec + bit-depth tuple.
    val editEnabled = enabled && recorderStateReady
    val family = when (profile) {
        in 1..5 -> "ProRes"
        in 6..9 -> "DNxHD"
        10 -> "CinemaDNG"
        in 13..15 -> "H.264"
        in 16..19 -> "H.265"
        12 -> "ProRes RAW"
        else -> "ProRes"
    }
    val proResDepthLocked = family == "ProRes"

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

            if (!recorderStateReady) {
                Text(
                    "SYNC CURRENT FROM RECORDER first. Codec, compression and bit depth stay locked until recorder state is read successfully.",
                    color = Color(0xFF9AA3A6), fontSize = 9.sp, lineHeight = 13.sp, modifier = Modifier.padding(bottom = 10.dp)
                )
            }
            Text("CODEC", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RemoteScopeChoiceButton("ProRes", family == "ProRes", editEnabled, Modifier.weight(1f)) { selectFamily("ProRes") }
                RemoteScopeChoiceButton("DNxHD", family == "DNxHD", editEnabled, Modifier.weight(1f)) { selectFamily("DNxHD") }
                RemoteScopeChoiceButton("CinemaDNG", family == "CinemaDNG", editEnabled, Modifier.weight(1f)) { selectFamily("CinemaDNG") }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RemoteScopeChoiceButton("H.264", family == "H.264", editEnabled, Modifier.weight(1f)) { selectFamily("H.264") }
                RemoteScopeChoiceButton("H.265", family == "H.265", editEnabled, Modifier.weight(1f)) { selectFamily("H.265") }
                RemoteScopeChoiceButton("ProRes RAW", family == "ProRes RAW", editEnabled, Modifier.weight(1f)) { selectFamily("ProRes RAW") }
            }

            Spacer(Modifier.height(13.dp))
            Text("COMPRESSION", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(7.dp))
            when (family) {
                "ProRes" -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RemoteScopeChoiceButton("Proxy", profile == 1, editEnabled && profileAvailable(1), Modifier.weight(1f)) { selectProfile(1) }
                        RemoteScopeChoiceButton("LT", profile == 2, editEnabled && profileAvailable(2), Modifier.weight(1f)) { selectProfile(2) }
                        RemoteScopeChoiceButton("422", profile == 3, editEnabled && profileAvailable(3), Modifier.weight(1f)) { selectProfile(3) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RemoteScopeChoiceButton("HQ", profile == 4, editEnabled && profileAvailable(4), Modifier.weight(1f)) { selectProfile(4) }
                        RemoteScopeChoiceButton("4444", profile == 5, editEnabled && profileAvailable(5), Modifier.weight(1f)) { selectProfile(5) }
                        Spacer(Modifier.weight(1f))
                    }
                }
                "DNxHD" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RemoteScopeChoiceButton("LB", profile == 6, editEnabled && profileAvailable(6), Modifier.weight(1f)) { selectProfile(6) }
                    RemoteScopeChoiceButton("SQ", profile == 7, editEnabled && profileAvailable(7), Modifier.weight(1f)) { selectProfile(7) }
                    RemoteScopeChoiceButton("HQ", profile == 8, editEnabled && profileAvailable(8), Modifier.weight(1f)) { selectProfile(8) }
                    RemoteScopeChoiceButton("HQX", profile == 9, editEnabled && profileAvailable(9), Modifier.weight(1f)) { selectProfile(9) }
                }
                "CinemaDNG" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RemoteScopeChoiceButton("CinemaDNG", profile == 10, editEnabled && profileAvailable(10), Modifier.weight(1f)) { selectProfile(10) }
                    Spacer(Modifier.weight(3f))
                }
                "H.264" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RemoteScopeChoiceButton("LQ", profile == 13, editEnabled && profileAvailable(13), Modifier.weight(1f)) { selectProfile(13) }
                    RemoteScopeChoiceButton("MQ", profile == 14, editEnabled && profileAvailable(14), Modifier.weight(1f)) { selectProfile(14) }
                    RemoteScopeChoiceButton("HQ", profile == 15, editEnabled && profileAvailable(15), Modifier.weight(1f)) { selectProfile(15) }
                    Spacer(Modifier.weight(1f))
                }
                "H.265" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RemoteScopeChoiceButton("LQ", profile == 16, editEnabled && profileAvailable(16), Modifier.weight(1f)) { selectProfile(16) }
                    RemoteScopeChoiceButton("MQ", profile == 17, editEnabled && profileAvailable(17), Modifier.weight(1f)) { selectProfile(17) }
                    RemoteScopeChoiceButton("HQ", profile == 18, editEnabled && profileAvailable(18), Modifier.weight(1f)) { selectProfile(18) }
                    RemoteScopeChoiceButton("XQ", profile == 19, editEnabled && profileAvailable(19), Modifier.weight(1f)) { selectProfile(19) }
                }
                else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RemoteScopeChoiceButton("RAW HQ", profile == 12, editEnabled && profileAvailable(12), Modifier.weight(1f)) { selectProfile(12) }
                    Spacer(Modifier.weight(3f))
                }
            }

            Spacer(Modifier.height(13.dp))
            Text("BIT DEPTH / CHROMA", color = Color(0xFF687174), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RemoteScopeChoiceButton("10b 4:2:2", bitDepth == 0, editEnabled && bitDepthAvailable(0), Modifier.weight(1f)) { onBitDepthChange(0) }
                RemoteScopeChoiceButton("8b 4:2:2", bitDepth == 1, editEnabled && !proResDepthLocked && bitDepthAvailable(1), Modifier.weight(1f)) { onBitDepthChange(1) }
                RemoteScopeChoiceButton("8b 4:2:0", bitDepth == 2, editEnabled && !proResDepthLocked && bitDepthAvailable(2), Modifier.weight(1f)) { onBitDepthChange(2) }
            }
            if (proResDepthLocked) {
                Text("ProRes profile changes use the official macOS recorder-managed codec tuple; the wire setter carries its codec-depth sentinel automatically.", color = Color(0xFF687174), fontSize = 8.sp, modifier = Modifier.padding(top = 6.dp))
            }

            Spacer(Modifier.height(10.dp))
            if (supportedProfiles.isNotEmpty() || supportedBitDepths.isNotEmpty()) {
                val supportedCodecText = if (supportedProfiles.isEmpty()) "waiting" else supportedProfiles.joinToString { codecSummary(it) }
                val supportedDepthText = if (supportedBitDepths.isEmpty()) "waiting" else supportedBitDepths.joinToString { codecBitDepthSummary(it) }
                Text(
                    "Recorder supported: $supportedCodecText · $supportedDepthText",
                    color = Color(0xFF687174), fontSize = 8.sp, lineHeight = 11.sp
                )
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
                            confirmedProfile?.let { "${codecSummary(it.first)} · ${codecBitDepthSummary(it.second)}" } ?: "Waiting for recorder state",
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
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("SYNC CURRENT FROM RECORDER", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Reads the official macOS current-state pair: RecordPage 0x0643 + InputOutputSummary 0x0648. Apply remains locked until both pages produce recorder state.",
                color = Color(0xFF687174), fontSize = 9.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onApply,
                enabled = editEnabled && recorderStateReady && profile in 1..19 && profile != 11 && bitDepth in 0..2,
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
                "Normal Apply uses macOS NonRebootCodecProfileSet 0x013F. Recorder state, not the draft button, decides when the change is confirmed.",
                color = Color(0xFF687174), fontSize = 9.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showRestartConfirm = true },
                enabled = editEnabled && profile in 1..19 && profile != 11 && bitDepth in 0..2,
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

    if (LocalConnectedModuleEmbedded.current) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF07090A))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ModuleSessionStrip(
                linked = linked,
                statePages = stateSnapshot.receivedPages,
                expectedStatePages = stateSnapshot.expectedPages,
                stateComplete = stateSnapshot.cycleComplete
            )
            content()
            Text(
                "Available controls and values depend on the connected recorder and firmware.",
                color = Color(0xFF596266),
                fontSize = 9.sp,
                lineHeight = 13.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
        return
    }

    BackHandler(enabled = true) { vm.go(AtomosViewModel.Screen.REMOTE) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07090A))
            .navigationBarsPadding()
    ) {
        AppHeader(
            title = title,
            subtitle = subtitle,
            onBack = { vm.go(AtomosViewModel.Screen.REMOTE) }
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ModuleSessionStrip(
                linked = linked,
                statePages = stateSnapshot.receivedPages,
                expectedStatePages = stateSnapshot.expectedPages,
                stateComplete = stateSnapshot.cycleComplete
            )
            content()
            Text(
                "Available controls and values depend on the connected recorder and firmware.",
                color = Color(0xFF596266),
                fontSize = 9.sp,
                lineHeight = 13.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
        RemoteModuleNavBar(vm = vm, current = current)
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
        color = Color(0xFF0D1113),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF20272A))
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
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1315))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color(0xFFE9EDEE), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            if (subtitle != null) {
                Text(subtitle, color = Color(0xFF7D878A), fontSize = 9.sp, lineHeight = 13.sp)
            }
            Divider(color = Color(0xFF202628))
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
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xFF7F898C), fontSize = 10.sp, modifier = Modifier.weight(0.44f))
        Text(
            value,
            color = when {
                accent -> AtomosCyan
                pending -> Color(0xFF747E81)
                else -> Color(0xFFC8CFD1)
            },
            fontSize = 10.sp,
            fontWeight = if (accent) FontWeight.Bold else FontWeight.SemiBold,
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
        else -> raw.substringBefore(" · raw ").substringBefore(" Ã‚Â· raw ").ifBlank { "Not reported" }
    }
}

private fun semanticDeviceName(raw:String?, fallback:String):String {
    if (raw.isNullOrBlank()) return fallback
    val parts = raw.split(" · ", " Ã‚Â· ")
    val printable = parts.firstOrNull { part ->
        part.isNotBlank() &&
        !part.startsWith("type 0x", ignoreCase=true) &&
        !part.startsWith("ID ", ignoreCase=true) &&
        !part.startsWith("raw ", ignoreCase=true)
    }
    return printable?.trim()?.takeIf { it.isNotBlank() } ?: fallback
}

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

    // AGENT7 20.03.5.3 CONSOLIDATED FROZEN INPUT\n    // 20.03.5.0 semantic display + 20.03.5.1 menu/cache + 20.03.5.2 frozen-core correction.
    // Keep the official Input menu families permanently visible at the top.
    // Switching these inner tabs is Compose-local only and never touches BLE.
    var inputPage by remember { mutableStateOf("Source") }
    val pages = listOf("Source", "Image", "Record Trigger", "SDI")

    ModuleShell(
        vm = vm,
        current = AtomosViewModel.Screen.INPUT,
        title = "Input",
        subtitle = recorderName
    ) {
        if (inputPreparing) {
            ModuleSection("Input Status", null) {
            ModuleValueRow(
                "Status",
                when {
                    inputSession.ready && !collector.running && !extendedInput.running -> "Ready"
                    inputPreparing -> "Refreshing recorder settings... existing values remain visible"
                    ready -> "Refreshing recorder settings..."
                    else -> "Recorder not connected"
                },
                accent = inputSession.ready
            )
            OutlinedButton(
                onClick = { vm.refreshInputSessionSnapshotFromUi() },
                enabled = ready && !collector.running && !extendedInput.running && !inputSetter.busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(11.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF314044))
            ) {
                Text(
                    "REFRESH",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        ModuleSection(
            "Input Status",
            "Live recorder values from the official RemoteApi read path."
        ) {
            ModuleValueRow("Connection", if (ready) "Connected" else "Waiting for recorder", accent = ready)
            ModuleValueRow("Source", source, accent = source != "Not reported")
            ModuleValueRow("HDR", hdrState, accent = hdrState != "Not reported")
            ModuleValueRow("Device", deviceName, accent = deviceName != "Not reported")
            ModuleValueRow(
                "Readback",
                if (collector.running) {
                    "Reading ${collector.inputResponses}/${collector.inputExpected}"
                } else if (collector.inputExpected > 0) {
                    "${collector.inputResponses}/${collector.inputExpected} confirmed"
                } else {
                    "Not read yet"
                },
                accent = collector.inputResponses >= 3
            )

                    }

        ModuleSection("Recorder", null) {
            ModuleValueRow(
                "Model",
                recorderName,
                accent = recorderName != "ATOMOS Recorder"
            )
            if (
                identityView.deviceName.isNotBlank() &&
                identityView.deviceName != recorderName &&
                identityView.deviceName.length > 3 &&
                !identityView.deviceName.equals("Unknown", true)
            ) {
                ModuleValueRow("Device Name", identityView.deviceName)
            }
        }

        ModuleSection(
            "HDR",
            "HDR state is read-only in this batch. No HDR setter is sent."
        ) {
            ModuleValueRow("HDR", hdrView.hdr, accent = hdrView.confirmed)
            ModuleValueRow(
                "HDR Control",
                hdrView.control,
                accent = hdrView.control == "Available"
            )
            ModuleValueRow(
                "HDR Auto",
                hdrAutoValue,
                accent = hdrAutoValue != "Not reported by recorder"
            )
            ModuleValueRow(
                "Camera",
                hdrCamera,
                accent = hdrCamera != "Not reported by recorder"
            )
        }

        ModuleSection(
            "Current Signal + Input Format",
            "Read-only existing recorder snapshot. Opening Input or changing an Input panel sends no new PacketLink command."
        ) {
            ModuleValueRow(
                "Signal / Format",
                signalView.format,
                accent = signalView.available
            )
        }

        ModuleSection(
            "Synchronization",
            "Input state loads once per recorder session and remains cached while you navigate the app. Sync Again is optional."
        ) {
            Button(
                onClick = {
                    vm.invalidateInputAutoSyncCache()
                    vm.refreshInputSessionSnapshotFromUi()
                },
                enabled = ready && !collector.running && !extendedInput.running && !inputSetter.busy,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF223438),
                    contentColor = AtomosCyan,
                    disabledContainerColor = Color(0xFF1A2426),
                    disabledContentColor = Color(0xFF657174)
                )
            ) {
                Text("REFRESH", fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
        }

        ModuleSection("Input Range", "20.03.6.11. Read-only recorder state.") {
            ModuleValueRow("Range", finalInputRange.text, accent = finalInputRange.text != "Not reported by recorder")
        }

        ModuleSection("RAW + Camera Metadata", "20.03.6.12. Read-only recorder state.") {
            ModuleValueRow("RAW Input", finalRawInput.text, accent = finalRawInput.text != "Not reported by recorder")
            ModuleValueRow("Camera Metadata", finalCameraMetadata.text, accent = finalCameraMetadata.text != "Not reported by recorder")
        }

        ModuleSection("Input State", "20.03.6.13. Change-aware safe refresh. Internal Change ID is never shown.") {
            ModuleValueRow("State", extendedView.stateStatus, accent = extendedView.stateStatus == "Current" || extendedView.stateStatus == "Updated")
            ModuleValueRow(
                "Readback",
                if (extendedInput.running) "Reading..." else if (extendedInput.completed > 0) "${extendedInput.completed}/${extendedInput.expected} recorder responses" else "Not refreshed",
                accent = extendedInput.completed > 0
            )
        }

        ModuleSection(
            "Input Menu",
            "Source, Image, Record Trigger and SDI are local UI panels. Opening or switching them never sends a PacketLink command."
        ) {
            pages.chunked(2).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { page ->
                        val active = inputPage == page
                        OutlinedButton(
                            onClick = { inputPage = page },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (active) AtomosCyan else Color(0xFF314044)
                            )
                        ) {
                            Text(
                                page,
                                color = if (active) AtomosCyan else Color(0xFFB8C0C2),
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        when (inputPage) {
            "Source" -> {
                val rawSource = values["source"]
                val sourceOptions = InputSourceCatalog.protocolFamily.map { it.label }
                val cachedHdmiStandard = InputHdmiStandardCatalog.normalize(values["hdmiStandard"])
                val hdmiStandard =
                    if (finalHdmiStandard.verified) {
                        finalHdmiStandard.text
                    } else {
                        cachedHdmiStandard
                    }

                ModuleSection(
                    "Input Source",
                    "Choose an official Input source. Current Source always remains recorder readback, never the last tapped option."
                ) {
                    ModuleValueRow("Current Source", source, accent = source != "Not reported")
                    ModuleValueRow(
                        "Readback",
                        if (collector.inputResponses >= 3) "Recorder confirmed" else "Waiting for stable Input snapshot",
                        accent = collector.inputResponses >= 3
                    )
                    EditableInputOptions(
                        options = InputEditCatalog.sourceOptions,
                        active = source.takeIf { it in sourceOptions },
                        enabled = ready && !inputSetter.busy && !collector.running && !extendedInput.running,
                        onSelect = { option -> vm.setInputSource(option.value, option.label) }
                    )
                    InputSetterStatusRow(inputSetter.status, inputSetter.busy, inputSetter.confirmed)
                }

                ModuleSection(
                    "HDMI Standard",
                    "HDMI Standard is editable when the current source is HDMI. Confirmation requires recorder readback."
                ) {
                    ModuleValueRow(
                        "Current Standard",
                        hdmiStandard,
                        accent = hdmiStandard != "Not reported by recorder"
                    )
                    EditableInputOptions(
                        options = InputEditCatalog.hdmiStandardOptions,
                        active = readOnlyMatch(
                            hdmiStandard,
                            InputEditCatalog.hdmiStandardOptions.map { it.label }
                        ),
                        enabled = ready &&
                            source.contains("HDMI", ignoreCase = true) &&
                            !inputSetter.busy &&
                            !collector.running &&
                            !extendedInput.running,
                        onSelect = { option -> vm.setInputHdmiStandard(option.value, option.label) }
                    )
                    ModuleValueRow(
                        "Supported Standards",
                        finalHdmiOptions.text,
                        accent = finalHdmiOptions.text != "Not reported by recorder"
                    )
                    Text(
                        "Choose a standard to send the official setter. The selected value becomes current only after recorder GET readback confirms it.",
                        color = Color(0xFF747E81),
                        fontSize = 9.sp,
                        lineHeight = 13.sp
                    )
                }

                ModuleSection(
                    "HDR + Range",
                    "HDR and HDR Auto are editable in 20.03.7.3. Current values change only after recorder readback confirms them."
                ) {
                    ModuleValueRow("HDR", hdrView.hdr, accent = hdrView.confirmed)
                    EditableInputOptions(
                        options = listOf(
                            InputEditCatalog.Option(0, "Off"),
                            InputEditCatalog.Option(1, "On")
                        ),
                        active = readOnlyBooleanMatch(hdrView.hdr),
                        enabled = ready &&
                            hdrView.control == "Available" &&
                            !inputSetter.busy &&
                            !collector.running &&
                            !extendedInput.running,
                        onSelect = { option -> vm.setInputHdrEnabled(option.value == 1) }
                    )
                    Spacer(Modifier.height(6.dp))
                    ModuleValueRow("HDR Auto", hdrAutoValue, accent = hdrAutoValue != "Not reported by recorder")
                    EditableInputOptions(
                        options = listOf(
                            InputEditCatalog.Option(0, "Off"),
                            InputEditCatalog.Option(1, "On")
                        ),
                        active = readOnlyBooleanMatch(hdrAutoValue),
                        enabled = ready &&
                            !inputSetter.busy &&
                            !collector.running &&
                            !extendedInput.running,
                        onSelect = { option -> vm.setInputHdrAuto(option.value == 1) }
                    )
                    ModuleValueRow("Input Range", finalInputRange.text, accent = finalInputRange.text != "Not reported by recorder")
                    EditableInputDropdown(
                        title = "Change Input Range",
                        options = InputEditCatalog.inputRangeOptions,
                        active = readOnlyMatch(
                            finalInputRange.text,
                            InputEditCatalog.inputRangeOptions.map { it.label }
                        ),
                        enabled = ready && !inputSetter.busy && !collector.running && !extendedInput.running,
                        onSelect = { option -> vm.setInputRange(option.value, option.label) }
                    )
                    InputSetterStatusRow(inputSetter.status, inputSetter.busy, inputSetter.confirmed)
                }
            }

            "Image" -> {
                val currentCamera =
                    if (extendedView.camera != "Not reported by recorder") extendedView.camera else hdrCapabilities.camera
                val currentGamma =
                    if (extendedView.gamma != "Not reported by recorder") extendedView.gamma else hdrCapabilities.gamma
                val currentGamut =
                    if (extendedView.gamut != "Not reported by recorder") extendedView.gamut else hdrCapabilities.gamut

                ModuleSection(
                    "HDR Image Conversion",
                    "Camera, Gamma and Gamut update only after recorder readback confirms the selection."
                ) {
                    ModuleValueRow(
                        "Camera",
                        currentCamera,
                        accent = currentCamera != "Not reported by recorder"
                    )
                    EditableInputDropdown(
                        title = "Change Camera",
                        options = InputEditCatalog.hdrCameraOptions,
                        active = readOnlyMatch(
                            currentCamera,
                            InputEditCatalog.hdrCameraOptions.map { it.label }
                        ),
                        enabled = ready && !inputSetter.busy && !collector.running && !extendedInput.running,
                        onSelect = { option -> vm.setInputHdrCamera(option.value, option.label) }
                    )
                    Spacer(Modifier.height(6.dp))
                    ModuleValueRow(
                        "Gamma",
                        currentGamma,
                        accent = currentGamma != "Not reported by recorder"
                    )
                    EditableInputDropdown(
                        title = "Change Gamma",
                        options = InputEditCatalog.hdrGammaOptions,
                        active = readOnlyMatch(
                            currentGamma,
                            InputEditCatalog.hdrGammaOptions.map { it.label }
                        ),
                        enabled = ready && !inputSetter.busy && !collector.running && !extendedInput.running,
                        onSelect = { option -> vm.setInputHdrGamma(option.value, option.label) }
                    )
                    Spacer(Modifier.height(6.dp))
                    ModuleValueRow(
                        "Gamut",
                        currentGamut,
                        accent = currentGamut != "Not reported by recorder"
                    )
                    EditableInputDropdown(
                        title = "Change Gamut",
                        options = InputEditCatalog.hdrGamutOptions,
                        active = readOnlyMatch(
                            currentGamut,
                            InputEditCatalog.hdrGamutOptions.map { it.label }
                        ),
                        enabled = ready && !inputSetter.busy && !collector.running && !extendedInput.running,
                        onSelect = { option -> vm.setInputHdrGamut(option.value, option.label) }
                    )
                    InputSetterStatusRow(inputSetter.status, inputSetter.busy, inputSetter.confirmed)
                }

                val cameraCapability =
                    if (finalCameraOptions.text != "Not reported by recorder") finalCameraOptions.text else hdrCapabilities.cameraOptions
                val gammaCapability =
                    if (finalGammaOptions.text != "Not reported by recorder") finalGammaOptions.text else hdrCapabilities.gammaOptions
                val gamutCapability =
                    if (finalGamutOptions.text != "Not reported by recorder") finalGamutOptions.text else hdrCapabilities.gamutOptions

                if (
                    cameraCapability != "Not reported by recorder" ||
                    gammaCapability != "Not reported by recorder" ||
                    gamutCapability != "Not reported by recorder"
                ) {
                    ModuleSection("Recorder Capabilities", null) {
                        if (cameraCapability != "Not reported by recorder") {
                            ModuleValueRow("Camera", cameraCapability)
                        }
                        if (gammaCapability != "Not reported by recorder") {
                            ModuleValueRow("Gamma", gammaCapability)
                        }
                        if (gamutCapability != "Not reported by recorder") {
                            ModuleValueRow("Gamut", gamutCapability)
                        }
                    }
                }
            }

            "Record Trigger" -> {
                ModuleSection(
                    "Record Trigger",
                    "Trigger controls are editable. Current values change only after recorder readback confirms the requested setting."
                ) {
                    ModuleValueRow("Trigger Source", finalTriggerSource.text, accent = finalTriggerSource.text != "Not reported by recorder")
                    EditableInputOptions(
                        options = InputEditCatalog.triggerSourceOptions,
                        active = readOnlyMatch(
                            finalTriggerSource.text,
                            InputEditCatalog.triggerSourceOptions.map { it.label }
                        ),
                        enabled = ready && !inputSetter.busy && !collector.running && !extendedInput.running,
                        onSelect = { option -> vm.setInputTriggerSource(option.value, option.label) }
                    )
                    Spacer(Modifier.height(6.dp))
                    ModuleValueRow("Trigger Enabled", finalTriggerEnabled.text, accent = finalTriggerEnabled.text != "Not reported by recorder")
                    EditableInputOptions(
                        options = listOf(
                            InputEditCatalog.Option(0, "Off"),
                            InputEditCatalog.Option(1, "On")
                        ),
                        active = readOnlyBooleanMatch(finalTriggerEnabled.text),
                        enabled = ready && !inputSetter.busy && !collector.running && !extendedInput.running,
                        onSelect = { option -> vm.setInputTriggerEnabled(option.value == 1) }
                    )
                    InputSetterStatusRow(inputSetter.status, inputSetter.busy, inputSetter.confirmed)
                }

                Text(
                    "Protocol details remain internal. A transmitted command alone is never treated as confirmation.",
                    color = Color(0xFF747E81),
                    fontSize = 9.sp,
                    lineHeight = 13.sp
                )
            }

            "SDI" -> {
                ModuleSection("SDI Input", null) {
                    ModuleValueRow("Current Input", source, accent = source != "Not reported")
                    EditableInputDropdown(
                        title = "Change SDI Input",
                        options = InputEditCatalog.sdiSourceOptions,
                        active = readOnlyMatch(
                            source,
                            InputEditCatalog.sdiSourceOptions.map { it.label }
                        ),
                        enabled = ready &&
                            !inputSetter.busy &&
                            !collector.running &&
                            !extendedInput.running,
                        onSelect = { option -> vm.setInputSource(option.value, option.label) }
                    )

                    if (finalSdiLink.text != "Not reported by recorder") {
                        ModuleValueRow("Link Capability", finalSdiLink.text)
                    }
                    if (finalActiveSdi.text != "Not reported by recorder") {
                        ModuleValueRow("Source Capability", finalActiveSdi.text)
                    }

                    Text(
                        "SDI A/B/Dual/Quad selection uses Input Source. Link/active-source data is capability readback only.",
                        color = Color(0xFF747E81),
                        fontSize = 9.sp,
                        lineHeight = 13.sp
                    )
                    InputSetterStatusRow(inputSetter.status, inputSetter.busy, inputSetter.confirmed)
                }
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


@Composable
private fun AudioModuleScreen(vm: AtomosViewModel) {
    val selected by vm.selected.collectAsState()
    val recorderName = selected?.let { vm.displayNameFor(it) } ?: "ATOMOS Recorder"
    var selectedChannel by remember { mutableStateOf(1) }

    LaunchedEffect(recorderName, selectedChannel) {
        Log.d(
            "AtomRemoteUI",
            "TCS19.12.8 TAB AUDIO recorder=$recorderName selectedChannel=$selectedChannel liveData=NOT_WIRED uiState=PLACEHOLDER"
        )
    }

    ModuleShell(vm, AtomosViewModel.Screen.AUDIO, "Audio", recorderName) {
        ModuleSection(
            "Audio Meters",
            "Select a channel to view its recorder-reported audio state."
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (1..4).forEach { channel ->
                    FilterChip(
                        selected = selectedChannel == channel,
                        onClick = { selectedChannel = channel },
                        label = { Text("CH $channel") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (5..8).forEach { channel ->
                    FilterChip(
                        selected = selectedChannel == channel,
                        onClick = { selectedChannel = channel },
                        label = { Text("CH $channel") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            AudioMeterPlaceholder(channel = selectedChannel)
        }

        ModuleSection("Channel Routing") {
            ModuleValueRow("Selected channel", "CH $selectedChannel", accent = true)
            ModuleValueRow("Monitor", "Waiting for recorder state")
            ModuleValueRow("Record", "Waiting for recorder state")
            ModuleFeatureChips(listOf("Monitor", "Record", "Record Enabled", "Record Valid"))
        }

        ModuleSection("Analog Input") {
            ModuleValueRow("Input source", "Not reported")
            ModuleValueRow("Embed", "Not reported")
            ModuleValueRow("Input level", "Not reported")
            ModuleValueRow("Gain L", "Not reported")
            ModuleValueRow("Gain R", "Not reported")
            ModuleValueRow("Frame delay", "Not reported")
            ModuleFeatureChips(listOf("Mic", "Line", "Gain L/R", "Frame Delay", "Analog Embed"))
        }

        ModuleSection("Headphones & Speaker") {
            ModuleValueRow("Headphones", "Not reported")
            ModuleValueRow("Speaker", "Not reported")
            ModuleValueRow("Speaker mute", "Not reported")
            ModuleFeatureChips(listOf("Phones Level", "Speaker Level", "Mute"))
        }

        ModuleSection("Layout & Fill") {
            ModuleValueRow("Meter orientation", "Not reported")
            ModuleValueRow("Audio fill", "Not reported")
            ModuleFeatureChips(listOf("Horizontal", "Vertical", "Audio Fill"))
        }

        ModuleSection("Recorder") {
            ModuleValueRow("Device", recorderName, accent = true)
            ModuleValueRow("Audio sync", "Awaiting recorder state")
        }
    }
}

@Composable
private fun AudioMeterPlaceholder(channel: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111619))
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("CH $channel", fontWeight = FontWeight.ExtraBold, color = Color(0xFFE9EDEE))
                Spacer(Modifier.weight(1f))
                Text("NO DATA", color = Color(0xFF7C8589), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .background(Color(0xFF242B2F), RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("-60", color = Color(0xFF6F787B), fontSize = 9.sp)
                Spacer(Modifier.weight(1f))
                Text("-12", color = Color(0xFF6F787B), fontSize = 9.sp)
                Spacer(Modifier.weight(1f))
                Text("0 dB", color = Color(0xFF6F787B), fontSize = 9.sp)
            }
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
            "TCS19.12.8 TAB MEDIA recorder=$recorderName liveData=NOT_WIRED uiState=PLACEHOLDER destructiveActions=LOCKED"
        )
    }

    ModuleShell(vm, AtomosViewModel.Screen.MEDIA, "Media", recorderName) {
        ModuleSection(
            "Recording Media",
            "Media status, capacity and remaining record time come directly from the recorder."
        ) {
            ModuleValueRow("Media state", "Waiting for recorder data", accent = true)
            ModuleValueRow("Device", "Not reported")
            ModuleValueRow("Capacity", "Not reported")
            ModuleValueRow("Available", "Not reported")
            ModuleValueRow("Record time remaining", "Not reported")
            ModuleValueRow("Media change ID", "Not reported")
        }

        ModuleSection(
            "Drives",
            "Only media drives reported by the connected recorder are shown as available."
        ) {
            MediaDriveCard("Drive 1")
            Spacer(Modifier.height(7.dp))
            MediaDriveCard("Drive 2")
        }

        ModuleSection(
            "File Browser",
            "Browse, preview and playback become available when the recorder exposes file-browser data."
        ) {
            ModuleValueRow("Browser mode", "Not active")
            ModuleValueRow("Directory", "Not reported")
            ModuleValueRow("Selected file", "None")
            ModuleValueRow("Video info", "Not reported")
            ModuleFeatureChips(listOf("Browse Files", "Preview Clip", "Play Clip"))
        }

        ModuleSection(
            "Recovery",
            "File recovery status is shown only when the recorder reports a recoverable file state."
        ) {
            ModuleValueRow("Recovery state", "Not reported")
            ModuleValueRow("Last recording", "Not reported")
        }

        ModuleSection(
            "Media Management",
            "Formatting is destructive. These controls remain locked until recorder-side confirmation is fully validated."
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("FORMAT MEDIA", fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("SECURE FORMAT", fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
            }
            Text(
                "Controls unlock only when the connected recorder reports a supported media state.",
                color = Color(0xFF7F898C),
                fontSize = 9.sp,
                lineHeight = 13.sp
            )
        }
    }
}

@Composable
private fun MediaDriveCard(label: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF121719),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF242B2E))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, color = Color(0xFFD7DDDF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("NOT REPORTED", color = Color(0xFF707A7D), fontSize = 8.sp, fontWeight = FontWeight.Black)
            }
            Divider(color = Color(0xFF202628))
            ModuleValueRow("Model / media", "Waiting for recorder data")
            ModuleValueRow("Capacity", "Not reported")
            ModuleValueRow("Free space", "Not reported")
        }
    }
}

@Composable
private fun OutputModuleScreen(vm: AtomosViewModel) {
    val selected by vm.selected.collectAsState()
    val pairing by vm.tcsPairingState.collectAsState()
    val remote by vm.tcsRemoteState.collectAsState()
    val outputInfo by vm.outputInfo.collectAsState()
    val hdrType by vm.outputHdrTypeId.collectAsState()
    val gamut by vm.outputGamutId.collectAsState()
    val rawHex by vm.inputOutputRawHex.collectAsState()
    val recorderName = selected?.let { vm.displayNameFor(it) } ?: "ATOMOS Recorder"
    val ready = pairing.acceptWritten && remote.available

    LaunchedEffect(ready, outputInfo, hdrType, gamut, rawHex) {
        Log.d(
            "AtomRemoteUI",
            "TCS19.12.8 TAB OUTPUT ready=$ready output=${outputInfo ?: "<null>"} hdrType=${hdrType ?: -1} gamut=${gamut ?: -1} rawHexChars=${rawHex?.length ?: 0}"
        )
    }

    // AGENT7 20.03.4.11: selecting Output is navigation only.
    // Hardware refresh remains explicit through the REFRESH OUTPUT button.
    LaunchedEffect(ready) { Unit }

    ModuleShell(vm, AtomosViewModel.Screen.OUTPUT, "Video Output", recorderName) {
        ModuleSection(
            "Current Output",
            "Current output information is read-only until the recorder reports dedicated output state."
        ) {
            ModuleValueRow("Session", if (ready) "Remote ready" else "Waiting for recorder", accent = ready)
            ModuleValueRow("Signal / format", outputInfo ?: "Waiting for recorder data")
            ModuleValueRow("HDR type ID", hdrType?.toString() ?: "Not reported")
            ModuleValueRow("Gamut ID", gamut?.toString() ?: "Not reported")
            ModuleValueRow("Summary received", if (rawHex.isNullOrBlank()) "No" else "Yes")
            Button(
                onClick = vm::refreshInputFormatSummary,
                enabled = ready,
                modifier = Modifier.fillMaxWidth()
            ) { Text("REFRESH OUTPUT") }
        }

        ModuleSection(
            "Output Connections",
            "HDMI, SDI and link options appear only when supported by the connected recorder."
        ) {
            ModuleValueRow("HDMI output", "Waiting for recorder data")
            ModuleValueRow("SDI output", "Waiting for recorder data")
            ModuleValueRow("SDI link type", "Not reported")
            ModuleFeatureChips(listOf("HDMI", "SDI", "SDI Link Type"))
        }

        ModuleSection(
            "Downscale",
            "Downscale choices are recorder-driven and may vary by model."
        ) {
            ModuleValueRow("Current state", "Not reported")
            ModuleValueRow("Available states", "Waiting for recorder data")
            ModuleFeatureChips(listOf("Disabled", "Enabled", "HDMI Auto"))
        }

        ModuleSection(
            "DCI Crop",
            "DCI crop choices are loaded from the connected recorder."
        ) {
            ModuleValueRow("Current state", "Not reported")
            ModuleValueRow("Available options", "Waiting for recorder data")
            ModuleFeatureChips(listOf("4K / 2K DCI Crop", "Recorder Options"))
        }

        ModuleSection("HDMI Timecode") {
            ModuleValueRow("Embedded timecode", "Not reported")
            ModuleFeatureChips(listOf("HDMI Timecode", "Enable", "Disable"))
        }

        ModuleSection(
            "HDR Output",
            "HDR type and gamut choices depend on recorder capabilities."
        ) {
            ModuleValueRow("HDR available", "Not reported")
            ModuleValueRow("HDR enabled", "Not reported")
            ModuleValueRow("Current type", hdrType?.toString() ?: "Not reported")
            ModuleValueRow("Current gamut", gamut?.toString() ?: "Not reported")
            ModuleFeatureChips(listOf("Rec.709", "PQ / ST 2084", "HLG"))
            ModuleFeatureChips(listOf("BT.2020", "DCI-P3", "P3-D65"))
        }

        ModuleSection(
            "LUT Output",
            "LUT output becomes available only when the recorder reports support."
        ) {
            ModuleValueRow("Available", "Not reported")
            ModuleValueRow("Enabled", "Not reported")
            ModuleFeatureChips(listOf("LUT Output", "Recorder Support"))
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

    LaunchedEffect(recorderName, modelCode, remoteReady, gattState, selected?.address) {
        Log.d(
            "AtomRemoteUI",
            "TCS19.12.8 TAB SETTINGS recorder=$recorderName model=$modelCode address=${selected?.address ?: "<none>"} gatt=$gattState remoteReady=$remoteReady dateTimeData=NOT_WIRED timecodeData=PARTIAL_SYNC firmwareData=NOT_WIRED"
        )
    }

    ModuleShell(vm, AtomosViewModel.Screen.SETTINGS, "Settings", recorderName) {
        ModuleSection(
            "Recorder Identity",
            "Recorder identity uses the recorder-reported device name whenever available."
        ) {
            ModuleValueRow("Recorder", recorderName, accent = true)
            ModuleValueRow("Model code", modelCode)
            ModuleValueRow("BLE address", selected?.address ?: "Not connected")
            ModuleValueRow("BLE state", gattState.replace('-', ' ').uppercase())
            ModuleValueRow("Remote session", if (remoteReady) "Ready" else remote.phase.replace('-', ' ').ifBlank { "Not ready" }, accent = remoteReady)
        }

        ModuleSection(
            "Date & Time",
            "Recorder date, time and time zone stay read-only until live values are available."
        ) {
            ModuleValueRow("Recorder date", "Not reported")
            ModuleValueRow("Recorder time", "Not reported")
            ModuleValueRow("Time zone", "Not reported")
            ModuleFeatureChips(listOf("Recorder Date", "Recorder Time", "Time Zone"))
        }

        ModuleSection(
            "Timecode",
            "Timecode controls remain read-only until the connected recorder reports supported sources and values."
        ) {
            ModuleValueRow("Current timecode", "Sync active - value pending")
            ModuleValueRow("Source", "Not reported")
            ModuleValueRow("Frame offset", "Not reported")
            ModuleValueRow("Drop frame", "Not reported")
            ModuleFeatureChips(listOf("Source List", "Current TC", "Offset"))
        }

        ModuleSection(
            "AirGlu & Sync",
            "AirGlu provides the current recorder control and synchronization link."
        ) {
            ModuleValueRow("Control transport", "AirGlu / TCS")
            ModuleValueRow("Device name sync", if (connectedRecorderName.isNullOrBlank()) "Waiting" else "Received", accent = !connectedRecorderName.isNullOrBlank())
            ModuleValueRow("Remote link", if (remoteReady) "Linked" else "Not ready", accent = remoteReady)
            ModuleFeatureChips(listOf("AirGlu", "Frame Sync", "Device Data"))
        }

        ModuleSection(
            "Firmware & Device Info",
            "Firmware and device information are shown only when reported by the recorder."
        ) {
            ModuleValueRow("Recorder firmware", "Not reported")
            ModuleValueRow("Monitor firmware", "Not reported")
            ModuleValueRow("Device info", "Waiting for recorder data")
            ModuleFeatureChips(listOf("Firmware", "Monitor FW", "Device Info"))
        }

        ModuleSection(
            "System Actions",
            "System-changing actions stay locked until recorder confirmation is fully validated."
        ) {
            ModuleFeatureChips(listOf("Set Date / Time", "Set TC Source", "Set TC Offset"))
            ModuleFeatureChips(listOf("Firmware Update", "Recorder Restart"))
            Text(
                "Destructive and restart actions are currently locked.",
                color = Color(0xFF7F898C),
                fontSize = 9.sp,
                lineHeight = 13.sp
            )
        }
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
                        Text("${status.transport} â€¢ ${if (status.connected) "CONNECTED" else "OFFLINE"}", color = AtomosCyan, fontWeight = FontWeight.Bold)
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
                        if (device.likelyAtomos) "ATOMOS CANDIDATE â€¢ ${device.displayName}" else device.displayName,
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
                            report?.let { "${it.passed}/${it.total} checks passing â€¢ ${it.blockers} blocker(s)" } ?: "Running checks...",
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




