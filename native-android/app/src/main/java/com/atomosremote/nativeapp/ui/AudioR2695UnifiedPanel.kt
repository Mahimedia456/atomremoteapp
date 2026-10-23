package com.atomosremote.nativeapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atomosremote.nativeapp.audio.AudioR26PageSnapshot

/** R26.95 unified operator UI. Hardware values are obtained ONLY from parsed 0x8647.
 * No fabricated gain/delay limits, no zero-value fallback, no unverified SET TX.
 * All bottom sheets support local inspection and optional staging; a staged value is
 * NEVER represented as sent or recorder-acknowledged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioR2695UnifiedPanel(
    pane: String,
    linked: Boolean,
    running: Boolean,
    audioPageHex: String,
    micEvidence: com.atomosremote.nativeapp.audio.AudioR2697MicLineSnapshot,
    onRefresh: () -> Unit,
    onMicLineReadAttempt: (String) -> Unit
) {
    val cyan = Color(0xFF51DBE7)
    val secondary = Color(0xFF9DB8BA)
    val bg = Color(0xFF071B20)
    val page = remember(pane, linked, audioPageHex) { if (linked && pane == "AUDIO") AudioR26PageSnapshot.decode(audioPageHex) else null }
    var sheet by remember(pane) { mutableStateOf<String?>(null) }
    var stagedRaw by remember(sheet, audioPageHex) { mutableStateOf<Long?>(null) }
    var stagedMode by remember(sheet) { mutableStateOf<String?>(null) }
    val audio = pane == "AUDIO"
    val titles = if (audio) listOf(
        "phones" to "Headphones level",
        "monitor" to "Analog monitor",
        "orientation" to "Meters orientation",
        "embed" to "Audio embedded",
        "rec" to "Channel REC",
        "dropouts" to "Ignore Audio Dropouts"
    ) else listOf(
        "gainLeft" to "Left gain (dB)",
        "gainRight" to "Right gain (dB)",
        "delay" to "Audio delay (frames)",
        "inputLevel" to "Audio-in level"
    )
    fun currentValue(key: String): String {
        val p = page
        return if (audio) when (key) {
            "phones" -> p?.phonesLevel?.toString() ?: "Not read"
            "monitor" -> p?.monitor?.toString()?.plus(" (raw)") ?: "Not read"
            "orientation" -> p?.orientation?.toString()?.plus(" (raw)") ?: "Not read"
            "embed" -> p?.analogEmbed?.toString()?.plus(" (raw)") ?: "Not read"
            "rec" -> p?.let { "${it.recordEnabledList.size}/${it.recordValidList.size}" } ?: "Not read"
            else -> "Not read"
        } else {
            val evidence = micEvidence.controls[key]
            when (evidence?.state) {
                "AWAITING_CANDIDATE_RX" -> "Reading candidate..."
                "CANDIDATE_RX_UNMAPPED" -> "Raw reply (unmapped)"
                "GENERIC_ACK_NO_VALUE" -> "ACK only; no Mic/Line value"
                "WIRE_SCHEMA_UNVERIFIED" -> "GET schema unverified"
                "CANDIDATE_GET_TIMEOUT" -> "No GET response"
                "RECORDER_REJECT" -> "Recorder rejected"
                "DEFERRED_TRANSPORT_BUSY", "DEFERRED_QUEUE_REFUSED" -> "Busy; reopen"
                "UNEXPECTED_RX", "WRONG_ROUTE", "EMPTY_RX" -> "Unverified response"
                else -> "Not read"
            }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Text(if (audio) "AUDIO CONTROLS" else "MIC / LINE CONTROLS",
            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(if (!linked) "Disconnected. Readback unavailable." else if (running)
            "GET queued or running. Input/Output may own PacketLink; no preemption. Last readback remains visible." else if (audio && page != null)
            "Audio GET: 0x0647 / RX: 0x8647. Settings are raw recorder IDs."
            else if (audio) "Awaiting complete recorder Audio page." else
            "Independent Mic/Line replies not verified. Do not treat Audio page as Mic/Line gain.",
            fontSize = 11.sp, color = secondary)
        titles.forEach { (key, label) ->
            Surface(modifier = Modifier.fillMaxWidth().clickable {
                sheet = key
                // R27.00: update per-control evidence only, with zero BLE TX.
                // Legacy ABI candidates returned 0xFFFF and are blocked in GattClient.
                if (!audio && linked && !running) onMicLineReadAttempt(key)
            },
                color = Color(0xFF0A262A), shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF30545A))) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.weight(1f), color = Color.White,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(currentValue(key), color = cyan, fontSize = 12.sp)
                    Text("   ›", color = cyan, fontSize = 17.sp)
                }
            }
        }
        OutlinedButton(onClick = onRefresh, enabled = linked && !running,
            modifier = Modifier.fillMaxWidth()) { Text("REFRESH AUDIO PAGE", fontSize = 12.sp) }
        Text("Selection/slider draft is local only until exact AG2 GET + SET wire bytes and " +
            "readback correlation are verified. No guessed command is transmitted.",
            fontSize = 10.sp, color = Color(0xFFD7BE84))
    }
    val opened = sheet
    if (opened != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null },
            containerColor = bg, contentColor = Color.White) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.7f)
                .verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(titles.firstOrNull { it.first == opened }?.second ?: "Audio",
                    fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("Recorder: ${currentValue(opened)}", color = cyan, fontSize = 13.sp)
                if (audio && page != null && opened == "phones") {
                    val min = page.phonesLevelMin
                    val max = page.phonesLevelMax
                    val draft = (stagedRaw ?: page.phonesLevel).coerceIn(min, max)
                    if (max > min) {
                        Slider(value = draft.toFloat(),
                            valueRange = min.toFloat()..max.toFloat(),
                            steps = ((max - min - 1).coerceIn(0, 99)).toInt(),
                            onValueChange = { stagedRaw = it.toLong().coerceIn(min,max) })
                    }
                    Text("Recorder range $min to $max  |  Draft $draft (not sent)",
                        fontSize = 12.sp, color = secondary)
                } else if (audio && page != null && opened in setOf("monitor", "embed", "orientation")) {
                    val ids = when (opened) {
                        "monitor" -> page.monitorList
                        "embed" -> page.analogEmbedList
                        else -> if (page.orientationChangeable != 0L) listOf(0L, 1L)
                                else listOf(page.orientation)
                    }.distinct()
                    val current = when (opened) {
                        "monitor" -> page.monitor
                        "embed" -> page.analogEmbed
                        else -> page.orientation
                    }
                    ids.forEach { id ->
                        val selected = (stagedRaw ?: current) == id
                        Surface(modifier = Modifier.fillMaxWidth().clickable { stagedRaw = id },
                            color = if (selected) Color(0xFF145159) else Color(0xFF132A2E),
                            shape = RoundedCornerShape(9.dp)) {
                            Text("Option ID $id ${if (selected) "  ✓" else ""}",
                                modifier = Modifier.padding(14.dp), color = Color.White)
                        }
                    }
                    Text("IDs are read from recorder page, not hardcoded option names.",
                        color = secondary, fontSize = 11.sp)
                } else if (audio && page != null && opened == "rec") {
                    page.recordValidList.distinct().forEach { id ->
                        val isEnabled = id in page.recordEnabledList
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Recorder channel ID $id", color = Color.White,
                                modifier = Modifier.weight(1f), fontSize = 12.sp)
                            Text(if (isEnabled) "REC ON" else "REC OFF",
                                color = if (isEnabled) cyan else secondary, fontSize = 12.sp)
                        }
                    }
                    Text("IDs cannot be assigned to named channel pairs without calibration.",
                        color = secondary, fontSize = 11.sp)
                } else if (!audio && opened == "inputLevel") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf("Line", "Mic").forEach { mode ->
                            OutlinedButton(onClick = { stagedMode = mode }, modifier = Modifier.weight(1f)) {
                                Text("${if (stagedMode == mode) "✓ " else ""}$mode", fontSize = 13.sp)
                            }
                        }
                    }
                    Text("Draft: ${stagedMode ?: "None"}. Exact enum IDs have not been confirmed.",
                        color = secondary, fontSize = 11.sp)
                } else {
                    Text("Individual recorder GET, channel arguments or dynamic limits are not yet proven. " +
                        "A numeric slider/stepper cannot safely send a setting without those values.",
                        fontSize = 12.sp, color = secondary)
                }
                if (!audio) {
                    val evidence = micEvidence.controls[opened]
                    Text("Independent GET: ${evidence?.state ?: "Not requested"}",
                        color = secondary, fontSize = 11.sp)
                    if (evidence?.state == "CANDIDATE_RX_UNMAPPED") {
                        Text("Raw reply: ${evidence.rawHex}", color = secondary, fontSize = 10.sp)
                    }
                    Text("AG2 rejected the previous local-ABI GET candidates (0xFFFF). " +
                        "This card now shows evidence without transmitting guessed bytes. " +
                        "Gain/delay/mode and SET need exact SwiftProtobuf wire mapping.",
                        color = secondary, fontSize = 10.sp)
                }
                Divider(color = Color(0xFF33545A))
                Text("SET unavailable: current logs show queued candidate writes without a " +
                    "verified recorder change. Avoid arbitrary 0/1 or fabricated numeric ranges.",
                    color = Color(0xFFE4C48B), fontSize = 11.sp)
                TextButton(onClick = { sheet = null }) { Text("CLOSE", color = cyan) }
            }
        }
    }
}
