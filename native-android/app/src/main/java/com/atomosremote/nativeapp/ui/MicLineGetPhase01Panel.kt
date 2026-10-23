package com.atomosremote.nativeapp.ui

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

/** R26.72 Mic/Line UI aligned to the user-provided official layout.
 * Bottom sheets are available for Left gain, Right gain, Audio delay and Audio-in level.
 * Candidate GET/SET actions are explicit operator trials only and must be verified by logcat
 * plus a follow-up shared Audio GET refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MicLineGetPhase01Panel(
    linked: Boolean,
    running: Boolean,
    onRefresh: () -> Unit,
    onCandidateGet: (String) -> Unit,
    onCandidateSet: (String, Int) -> Unit
) {
    val bg = Color(0xFF191919)
    val text = Color(0xFFF2F2F2)
    val secondary = Color(0xFFAAAAAA)
    val muted = Color(0xFF55555B)
    var sheetKey by remember { mutableStateOf<String?>(null) }
    var leftGain by remember { mutableFloatStateOf(50f) }
    var rightGain by remember { mutableFloatStateOf(50f) }
    var delayFrames by remember { mutableIntStateOf(0) }
    var inputLevel by remember { mutableStateOf("Line") }

    fun open(key: String) { if (linked) sheetKey = key }

    Surface(color = bg, shape = RoundedCornerShape(12.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("MIC / LINE", color = text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                if (!linked) "Disconnected | Mic/Line candidate trials unavailable"
                else if (running) "Shared Audio GET running | Mic/Line cards stay visible"
                else "Bottom-sheet trials for candidate GET/SET | verify on recorder after every action",
                color = secondary, fontSize = 11.sp
            )
            if (running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            MicLineCard(
                title = "Left gain (dB)",
                value = leftGain.toString(),
                subtitle = "Bottom sheet slider",
                onClick = { open("LEFT_GAIN") }
            )
            MicLineCard(
                title = "Right gain (dB)",
                value = rightGain.toString(),
                subtitle = "Bottom sheet slider",
                onClick = { open("RIGHT_GAIN") }
            )
            MicLineCard(
                title = "Audio delay (frames)",
                value = delayFrames.toString(),
                subtitle = "Bottom sheet frame stepper",
                onClick = { open("DELAY") }
            )
            MicLineCard(
                title = "Audio-in level",
                value = inputLevel,
                subtitle = "Bottom sheet Line / Mic selector",
                onClick = { open("INPUT_LEVEL") }
            )

            OutlinedButton(onClick = onRefresh, enabled = linked && !running,
                modifier = Modifier.fillMaxWidth()) {
                Text("REFRESH SHARED AUDIO GET (0x0647)", color = if (linked) text else secondary,
                    fontSize = 11.sp)
            }
            Text(
                "Note: candidate Mic/Line GET/SET payloads are experimental and based on macOS constructor recovery. " +
                    "Recorder confirmation requires visible device change, logcat evidence and post-action refresh.",
                color = Color(0xFFD0B785), fontSize = 10.sp
            )
        }
    }

    if (sheetKey == "LEFT_GAIN") {
        ModalBottomSheet(onDismissRequest = { sheetKey = null }, containerColor = bg) {
            MicLineSliderSheet(
                title = "Left gain (dB)", value = leftGain,
                onValueChange = { leftGain = it },
                onGet = { onCandidateGet("gainLeft") },
                onSend = { onCandidateSet("gainLeft", leftGain.toInt()) },
                linked = linked
            )
        }
    }
    if (sheetKey == "RIGHT_GAIN") {
        ModalBottomSheet(onDismissRequest = { sheetKey = null }, containerColor = bg) {
            MicLineSliderSheet(
                title = "Right gain (dB)", value = rightGain,
                onValueChange = { rightGain = it },
                onGet = { onCandidateGet("gainRight") },
                onSend = { onCandidateSet("gainRight", rightGain.toInt()) },
                linked = linked
            )
        }
    }
    if (sheetKey == "DELAY") {
        ModalBottomSheet(onDismissRequest = { sheetKey = null }, containerColor = bg) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Audio delay (frames)", color = text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(delayFrames.toString(), color = text, fontSize = 28.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { if (delayFrames > 0) delayFrames -= 1 }, enabled = linked) { Text("−") }
                    OutlinedButton(onClick = { if (delayFrames < 12) delayFrames += 1 }, enabled = linked) { Text("+") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { onCandidateGet("delay") }, enabled = linked, modifier = Modifier.weight(1f)) {
                        Text("READ CANDIDATE", color = text, fontSize = 11.sp)
                    }
                    Button(onClick = { onCandidateSet("delay", delayFrames) }, enabled = linked, modifier = Modifier.weight(1f)) {
                        Text("SEND TRIAL", fontSize = 11.sp)
                    }
                }
                Text("Candidate method: FrameDelayGet / FrameDelaySet", color = secondary, fontSize = 11.sp)
            }
        }
    }
    if (sheetKey == "INPUT_LEVEL") {
        ModalBottomSheet(onDismissRequest = { sheetKey = null }, containerColor = bg) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Audio-in level", color = text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Line", "Mic").forEach { option ->
                        val selected = option == inputLevel
                        Button(
                            onClick = { inputLevel = option },
                            enabled = linked,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) Color(0xFF47444F) else Color(0xFF2C2A33)
                            )
                        ) { Text(option, fontSize = 16.sp) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { onCandidateGet("inputLevel") }, enabled = linked, modifier = Modifier.weight(1f)) {
                        Text("READ CANDIDATE", color = text, fontSize = 11.sp)
                    }
                    Button(onClick = { onCandidateSet("inputLevel", if (inputLevel == "Mic") 1 else 0) }, enabled = linked, modifier = Modifier.weight(1f)) {
                        Text("SEND TRIAL", fontSize = 11.sp)
                    }
                }
                Text("Candidate method: InputSourceGet / InputSourceSet", color = secondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun MicLineCard(title: String, value: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp), color = Color(0xFF0A2529),
        border = BorderStroke(1.dp, Color(0xFF31585E))
    ) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color(0xFF9DB7BA), fontSize = 11.sp)
            }
            Text(value, color = Color(0xFF60D9E3), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("  ›", color = Color(0xFF60D9E3), fontSize = 12.sp)
        }
    }
}

@Composable
private fun MicLineSliderSheet(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onGet: () -> Unit,
    onSend: () -> Unit,
    linked: Boolean
) {
    val text = Color(0xFFF2F2F2)
    val secondary = Color(0xFFAAAAAA)
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, color = text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(String.format(java.util.Locale.US, "%.1f", value), color = text,
                fontSize = 26.sp, modifier = Modifier.weight(1f))
            Text("dB", color = secondary, fontSize = 15.sp)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = 0f..100f, enabled = linked)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onGet, enabled = linked, modifier = Modifier.weight(1f)) {
                Text("READ CANDIDATE", color = text, fontSize = 11.sp)
            }
            Button(onClick = onSend, enabled = linked, modifier = Modifier.weight(1f)) {
                Text("SEND TRIAL", fontSize = 11.sp)
            }
        }
        Text("Candidate method: GainGet / GainSet", color = secondary, fontSize = 11.sp)
    }
}
