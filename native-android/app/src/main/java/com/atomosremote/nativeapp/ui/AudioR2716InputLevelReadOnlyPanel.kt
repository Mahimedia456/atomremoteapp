package com.atomosremote.nativeapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atomosremote.nativeapp.audio.AudioR2697MicLineSnapshot
import com.atomosremote.nativeapp.audio.AudioR2716InputLevelReadback
import kotlinx.coroutines.flow.StateFlow

/** Read-only recorder Audio In Level. No GET on recomposition and no SET ever. */
@Composable
internal fun AudioR2716InputLevelReadOnlyPanel(
    linked: Boolean,
    evidenceFlow: StateFlow<AudioR2697MicLineSnapshot>,
    onReadInputLevel: () -> Unit
) {
    val evidence by evidenceFlow.collectAsState()
    val record = evidence.controls["inputLevel"]
    val valid = linked && record != null && record.key == "inputLevel" &&
        record.command == 0x06B3 && record.responseType == 0x86AE &&
        record.state == "CANDIDATE_RX_UNMAPPED"
    val decoded = if (valid) AudioR2716InputLevelReadback.decodeHex(record!!.rawHex) else null
    val busy = record?.state in setOf("PENDING_TRANSPORT", "BUFFER_ACCEPTED",
        "ATT_WRITE_CONFIRMED", "TRANSPORT_DELIVERED")
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        color = Color(0xFF0B292D), border = BorderStroke(1.dp, Color(0xFF396269))) {
        Column(Modifier.fillMaxWidth().padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("AUDIO IN LEVEL  |  RECORDER", color = Color.White,
                fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(if (linked) decoded?.display() ?: "--" else "--",
                color = Color(0xFF51DBE7), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(when {
                !linked -> "Disconnected. No recorder value."
                decoded?.option == AudioR2716InputLevelReadback.Option.LINE_LEVEL ||
                    decoded?.option == AudioR2716InputLevelReadback.Option.MIC_LEVEL ->
                    "AG2 verified physical mode | 0x06B3 / 0x86AE | raw ${record?.rawHex}"
                decoded != null -> "Unknown enum. Raw value only; no guessed label."
                busy -> "GET queued. Awaiting typed recorder response."
                valid -> "Typed reply received but protobuf structure not supported."
                else -> "Tap READ to fetch selected Line/Mic mode from the recorder."
            }, color = Color(0xFFADC6C9), fontSize = 11.sp)
            OutlinedButton(onClick = onReadInputLevel,
                enabled = linked && !busy, modifier = Modifier.fillMaxWidth()) {
                Text("READ AUDIO IN LEVEL (GET ONLY)", fontSize = 12.sp)
            }
            Text("Line=1, Mic=2 physically verified. Mode SET remains disabled.",
                color = Color(0xFFE0C89B), fontSize = 10.sp)
        }
    }
}
