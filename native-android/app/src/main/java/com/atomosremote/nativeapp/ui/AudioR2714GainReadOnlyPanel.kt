package com.atomosremote.nativeapp.ui

import androidx.compose.foundation.BorderStroke
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
import com.atomosremote.nativeapp.audio.AudioR2697MicLineSnapshot
import com.atomosremote.nativeapp.audio.AudioR2714GainReadback
import kotlinx.coroutines.flow.StateFlow

/** A separate Mic/Line-only presentation of the existing live scoped evidence flow.
 * UI never polls automatically; the button calls the previously verified 0x06B6
 * manual read callback. No mutation of any transport, Settings, Audio GET or NDI.
 */
@Composable
internal fun AudioR2714GainReadOnlyPanel(
    linked: Boolean,
    evidenceFlow: StateFlow<AudioR2697MicLineSnapshot>,
    onReadGain: () -> Unit
) {
    val evidence by evidenceFlow.collectAsState()
    val record = evidence.controls["gainLeft"]
    val trustedReply = linked && record != null &&
        record.key == "gainLeft" && record.command == 0x06B6 &&
        record.state == "CANDIDATE_RX_UNMAPPED" && record.responseType == 0x86B1
    val values = if (trustedReply) AudioR2714GainReadback.decodeHex(record!!.rawHex) else null
    val busy = record?.state in setOf(
        "PENDING_TRANSPORT", "BUFFER_ACCEPTED", "ATT_WRITE_CONFIRMED", "TRANSPORT_DELIVERED"
    )
    val cyan = Color(0xFF51DBE7)
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        color = Color(0xFF0B292D), border = BorderStroke(1.dp, Color(0xFF396269))) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("MIC / LINE  |  RECORDER GAIN", fontSize = 13.sp,
                fontWeight = FontWeight.Bold, color = Color.White)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("LEFT" to values?.leftLabel(), "RIGHT" to values?.rightLabel()).forEach { (name, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(name, color = Color(0xFFB2C9CB), fontSize = 11.sp)
                        Text(if (linked) value ?: "--" else "--", color = cyan,
                            fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(when {
                !linked -> "Recorder disconnected. No active gain readback."
                values != null && values.fullyCalibrated() -> "AG2 typed gain readback | 0x06B6 / 0x86B1 | 0 and -1 dB calibrated"
                values != null -> "AG2 typed readback | ~ indicates provisional half-unit dB scale"
                trustedReply -> "Typed response received, but protobuf shape is not verified. Raw only."
                busy -> "Gain GET in progress. Waiting for typed recorder reply."
                else -> "Tap READ GAIN to retrieve Left and Right from one recorder GET."
            }, color = Color(0xFFADC6C9), fontSize = 11.sp)
            if (values != null) {
                Text("Raw signed L=${values.leftRaw}, R=${values.rightRaw} | " +
                    "AG2 A/B verified at 0.0 and -1.0 dB per side",
                    color = Color(0xFFD0BC91), fontSize = 10.sp)
            }
            OutlinedButton(onClick = onReadGain, enabled = linked && !busy,
                modifier = Modifier.fillMaxWidth()) {
                Text("READ GAIN (GET ONLY)", fontSize = 12.sp)
            }
            Text("Gain SET remains locked. Input source, delay and the full dB range are not yet calibrated.",
                color = Color(0xFFE0C89B), fontSize = 10.sp)
        }
    }
}
