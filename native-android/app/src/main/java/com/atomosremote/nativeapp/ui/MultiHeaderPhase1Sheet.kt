package com.atomosremote.nativeapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atomosremote.nativeapp.multi.Mdp1Recorder
import com.atomosremote.nativeapp.multi.MultiHeaderPhase1Manager
import java.util.Locale

private val mdMint = Color(0xFF19E7D3)
private val mdBg = Color(0xFF091316)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MultiHeaderPhase1Sheet(manager: MultiHeaderPhase1Manager, onDismiss: () -> Unit) {
    val ui by manager.ui.collectAsState()
    // Consent to each 9-digit code must be per recorder, not shared between BLE sessions.
    var acceptedCodeFor by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = { manager.stopScan(); onDismiss() },
        containerColor = mdBg,
        contentColor = Color.White,
        dragHandle = { BottomSheetDefaults.DragHandle(color = mdMint) }
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("CONNECTED RECORDERS", color = mdMint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text("Up to 5 ATOMOS recorders • Official TCS pairing • Independent PacketLink",
                color = Color.LightGray, fontSize = 11.sp)
            val selectedCount = ui.selected.size
            val readyCount = ui.selected.count { id ->
                (ui.primaryAddress == id && ui.primaryReady) ||
                    ui.secondaries.any { it.address == id && it.ready }
            }
            Text("SELECTED $selectedCount  •  READY $readyCount  •  Shared REC/STOP/PLAY/PAUSE",
                color = mdMint, fontSize = 11.sp, modifier = Modifier.padding(top = 9.dp))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { manager.selectPrimaryOnly() }, modifier = Modifier.weight(1f)) { Text("PRIMARY") }
                OutlinedButton(onClick = { manager.selectAll() }, modifier = Modifier.weight(1f)) { Text("SELECT ALL") }
                Button(onClick = { manager.scan() }, enabled = !ui.scanning, modifier = Modifier.weight(1f)) {
                    Text(if (ui.scanning) "SCANNING" else "SCAN")
                }
            }
            Text(ui.message, fontSize = 10.sp, color = Color.LightGray,
                modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                contentPadding = PaddingValues(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ui.primaryAddress?.let { primary ->
                    item(key = "primary") {
                        Mdp1Card(
                            title = ui.primaryName, secondary = "PRIMARY • existing GATT (never reopened)",
                            status = if (ui.primaryReady) "REMOTE READY" else "CONNECTED / SYNCING",
                            selected = primary in ui.selected,
                            selectedEnabled = true,
                            ready = ui.primaryReady,
                            onToggle = { manager.toggle(primary) }
                        )
                    }
                }
                val secondarySaved = ui.saved.filterNot { saved ->
                    saved.address.equals(ui.primaryAddress, ignoreCase = true)
                }.distinctBy { it.address.uppercase(Locale.ROOT) }
                items(secondarySaved, key = { "saved-${it.address}" }) { saved ->
                    val id = saved.address.uppercase(Locale.ROOT)
                    val session = ui.secondaries.firstOrNull { it.address == id }
                    Mdp1Card(
                        title = saved.name,
                        secondary = "SAVED • ${saved.address}",
                        status = session?.status ?: "Saved / disconnected",
                        selected = id in ui.selected,
                        selectedEnabled = true,
                        ready = session?.ready == true,
                        onToggle = { manager.toggle(id) }
                    ) {
                        if (session == null) {
                            TextButton(onClick = { manager.connectSaved(saved) }) { Text("CONNECT TRUSTED") }
                        } else {
                            if (!session.ready && !session.canAccept &&
                                (session.status.contains("disconnected", ignoreCase = true) ||
                                 session.status.contains("error", ignoreCase = true) ||
                                 session.status.startsWith("BLE: idle"))) {
                                TextButton(onClick = { manager.reconnectSaved(id) }) {
                                    Text("RECONNECT TRUSTED", color = mdMint)
                                }
                            }
                            PairingButtons(manager, session, acceptedCodeFor,
                                onConfirmCode = { acceptedCodeFor = it }, onDisconnect = { manager.disconnectSecondary(id) })
                        }
                    }
                }
                val known = (ui.saved.map { it.address.uppercase(Locale.ROOT) } +
                    listOfNotNull(ui.primaryAddress)).toSet()
                val newDevices = ui.discovered.filterNot { it.address.uppercase(Locale.ROOT) in known }
                if (newDevices.isNotEmpty()) {
                    item(key = "found") {
                        Text("NEW BLE RECORDERS (${newDevices.size})", fontSize = 11.sp,
                            color = mdMint, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                    items(newDevices, key = { "scan-${it.address}" }) { device ->
                        val id = device.address.uppercase(Locale.ROOT)
                        val session = ui.secondaries.firstOrNull { it.address == id }
                        Mdp1Card(
                            title = device.name ?: "ATOMOS recorder",
                            secondary = "DISCOVERED • ${device.address}",
                            status = session?.status ?: "Not paired",
                            selected = false, selectedEnabled = false,
                            ready = session?.ready == true,
                            onToggle = {}
                        ) {
                            if (session == null) {
                                TextButton(onClick = { manager.pairDiscovered(device) }) { Text("PAIR (OFFICIAL TCS)") }
                            } else {
                                PairingButtons(manager, session, acceptedCodeFor,
                                    onConfirmCode = { acceptedCodeFor = it },
                                    onDisconnect = { manager.disconnectSecondary(id) })
                            }
                        }
                    }
                }
                if (ui.saved.isEmpty() && ui.discovered.isEmpty()) {
                    item { Text("Scan nearby recorders. Pair each one before selecting it.", color = Color.LightGray) }
                }
                item {
                    Text("Remote transport and supported Remote tool SET actions target selected READY recorders. Other tabs and NDI are not rerouted. Per-device GET proof is required before trusting mixed-device tool states.",
                        fontSize = 10.sp, color = Color(0xFF8AA6A8))
                }
            }
        }
    }
}

@Composable
private fun Mdp1Card(
    title: String,
    secondary: String,
    status: String,
    selected: Boolean,
    selectedEnabled: Boolean,
    ready: Boolean,
    onToggle: () -> Unit,
    actions: @Composable () -> Unit = {}
) {
    Surface(
        color = Color(0xFF102023),
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, if (ready) mdMint else Color(0xFF33464A))
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = selectedEnabled)
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(secondary, fontSize = 9.sp, color = Color.LightGray)
                    Text(status, fontSize = 10.sp, color = if (ready) mdMint else Color(0xFFFFC476))
                }
                if (ready) Text("READY", fontSize = 10.sp, color = mdMint)
            }
            actions()
        }
    }
}

@Composable
private fun PairingButtons(
    manager: MultiHeaderPhase1Manager,
    session: Mdp1Recorder,
    acceptedCodeFor: String?,
    onConfirmCode: (String?) -> Unit,
    onDisconnect: () -> Unit
) {
    val code = session.pairingCode
    if (!code.isNullOrBlank() && !session.ready) {
        Text("9-DIGIT PAIRING CODE: $code", fontSize = 14.sp,
            fontWeight = FontWeight.Bold, color = mdMint)
        Text("Compare with recorder screen. Accept only if both codes match.",
            fontSize = 10.sp, color = Color.LightGray)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = acceptedCodeFor == session.address,
                onCheckedChange = { onConfirmCode(if (it) session.address else null) })
            Text("Codes match", fontSize = 11.sp)
        }
        Button(
            onClick = { manager.acceptPair(session.address); onConfirmCode(null) },
            enabled = session.canAccept && acceptedCodeFor == session.address
        ) { Text("ACCEPT PAIRING") }
    }
    if (session.error != null) Text(session.error, color = Color(0xFFFF7777), fontSize = 10.sp)
    TextButton(onClick = onDisconnect) { Text("DISCONNECT SECONDARY") }
}
