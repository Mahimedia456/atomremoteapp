package com.atomosremote.nativeapp.ui

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atomosremote.nativeapp.audio.AudioR2718MonitorCandidate

/** Read-only Audio/MicLine SHARED headphone icon. Does not send a SET or schedule automatic GET. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioR2718MonitorReadOnlyRow(
    linked: Boolean,
    typedPageHex: String,
    currentTypedPage: Boolean,
    refreshBusy: Boolean,
    onRefreshPage: () -> Unit
) {
    val fresh = linked && currentTypedPage && !refreshBusy
    val candidate = remember(fresh, typedPageHex) {
        if (fresh) AudioR2718MonitorCandidate.decode(typedPageHex)
        else AudioR2718MonitorCandidate.Readback(
            AudioR2718MonitorCandidate.Icon.UNKNOWN, null, "No fresh typed Audio page")
    }
    val text = when (candidate.icon) {
        AudioR2718MonitorCandidate.Icon.ON_CANDIDATE -> "On (candidate)"
        AudioR2718MonitorCandidate.Icon.OFF_CANDIDATE -> "Off (candidate)"
        AudioR2718MonitorCandidate.Icon.UNKNOWN -> "Unknown"
    }
    val teal = Color(0xFF51DBE7)
    var details by remember { mutableStateOf(false) }
    LaunchedEffect(linked, fresh, candidate.icon, candidate.rawField1) {
        Log.i("AtomRemoteAudioR2718", "MIC_MONITOR_UI state=$text rawF1=${candidate.rawField1} " +
            "currentTyped=$fresh linked=$linked setTx=false evidence=PROVISIONAL_B0_CONFLICT")
    }
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        color = Color(0xFF0B292D), border = BorderStroke(1.dp, Color(0xFF396269))) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(modifier = Modifier.fillMaxWidth().clickable { details = true },
                verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("HEADPHONE MONITOR", color = Color.White, fontWeight = FontWeight.Bold,
                        fontSize = 13.sp)
                    Text("Mic / Line shared Audio selection", color = Color(0xFFADC6C9), fontSize = 11.sp)
                }
                Text(text, color = if (fresh && candidate.rawField1 != null) teal else Color.LightGray,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Text("Recorder-derived candidate only. Select row for details; no remote On/Off SET.",
                color = Color(0xFFDFCA9F), fontSize = 10.sp)
            OutlinedButton(onClick = onRefreshPage, modifier = Modifier.fillMaxWidth(),
                enabled = linked && !refreshBusy) {
                Text("REFRESH HEADPHONE STATE (GET ONLY)", fontSize = 11.sp)
            }
        }
    }
    if (details) {
        ModalBottomSheet(onDismissRequest = { details = false },
            containerColor = Color(0xFF10272B)) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Headphone Monitor", color = Color.White, fontWeight = FontWeight.Bold,
                    fontSize = 19.sp)
                Text(text, color = teal, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Physical Shogun MIC/LINE headphone icon shares the Audio page monitoring " +
                    "selection. Candidate is based on response 0x8647 protobuf field 1. " +
                    "R27.17 initial baseline contradicted the observed ON state; it is not " +
                    "yet a confirmed writable mapping.", color = Color(0xFFB4CCD0), fontSize = 12.sp)
                Text("Field 1: ${candidate.rawField1 ?: "--"} | Fresh typed page: $fresh | " +
                    "Source: 0x0647 / 0x8647", color = Color(0xFF9DB6B9), fontSize = 11.sp)
                OutlinedButton(onClick = { details = false; onRefreshPage() },
                    enabled = linked && !refreshBusy, modifier = Modifier.fillMaxWidth()) {
                    Text("REFRESH (GET ONLY)")
                }
                Text("On/Off SET unavailable until official payload and GET-after-SET are proven.",
                    color = Color(0xFFE0C89B), fontSize = 11.sp)
            }
        }
    }
}
