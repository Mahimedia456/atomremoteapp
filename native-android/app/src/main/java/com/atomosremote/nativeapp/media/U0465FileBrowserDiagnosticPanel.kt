package com.atomosremote.nativeapp.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** U04.65 step-by-step operator console. Every request requires an explicit tap. */
@Composable
fun U0465FileBrowserDiagnosticPanel(
    probe: U0465ProbeState,
    mediaFileList: SsdBrowserState,
    enabled: Boolean,
    onModeStart: () -> Boolean,
    onModeGet: () -> Boolean,
    onMediaStateGet: () -> Boolean,
    onFileListGet: () -> Boolean,
    onLocalFileListGet: () -> Boolean,
    onProbeGet: (String) -> Boolean,
    onExpire: (Long) -> Unit
) {
    val fg = Color.White
    val muted = Color(0xFFB6C1C4)
    val accent = Color(0xFF8BE0CD)
    val warning = Color(0xFFFAC686)
    var expanded by rememberSaveable { mutableStateOf(true) }
    var playbackConfirmed by rememberSaveable { mutableStateOf(false) }
    var backupConfirmed by rememberSaveable { mutableStateOf(false) }
    var enableInferred by rememberSaveable { mutableStateOf(false) }
    var localE8Consent by rememberSaveable { mutableStateOf(false) }
    var localE8Sent by rememberSaveable { mutableStateOf(false) }
    var modeStartDialog by remember { mutableStateOf(false) }
    var feedback by rememberSaveable { mutableStateOf("No request sent from this panel") }
    val safe = enabled && playbackConfirmed && backupConfirmed &&
        !mediaFileList.pending && probe.phase != "waiting"

    LaunchedEffect(probe.generation, probe.phase) {
        if (probe.phase == "waiting") {
            kotlinx.coroutines.delay(15_000L)
            onExpire(probe.generation)
        }
    }
    fun report(label: String, queued: Boolean) {
        feedback = if (queued) "$label queued, recorder response NOT confirmed" else
            "$label not queued; check existing one-shot guard/transport and previous request"
    }
    Card(modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, Color(0xFF536B6A)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101D20))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("U04.65  |  SSD BROWSER DIAGNOSTIC STEPS", color = fg,
                fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("MoviePicker may have no visible on-recorder screen change. Only recorder RX confirms a protocol state.",
                color = muted, fontSize = 11.sp)
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "HIDE STEP TESTS" else "SHOW STEP TESTS", color = accent)
            }
            if (expanded) {
                Row {
                    Checkbox(checked = playbackConfirmed, onCheckedChange = { playbackConfirmed = it })
                    Text("Playback active, recording stopped", modifier = Modifier.weight(1f), color = fg, fontSize = 11.sp)
                }
                Row {
                    Checkbox(checked = backupConfirmed, onCheckedChange = { backupConfirmed = it })
                    Text("SSD inserted; important clips backed up", modifier = Modifier.weight(1f), color = fg, fontSize = 11.sp)
                }
                Text("A. Recorder / filesystem GET probes (no automatic sequence)", color = accent, fontWeight = FontWeight.SemiBold)
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = safe,
                    onClick = { report("MediaStateGet", onMediaStateGet()) }) {
                    Text("1. MEDIA STATE GET  0x04B5", fontSize = 11.sp)
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth(),
                    enabled = safe && !probe.consumedNames.contains("media-change"),
                    onClick = { report("MediaChangeIdGet", onProbeGet("media-change")) }) {
                    Text("2. MEDIA CHANGE ID GET  0x04C3", fontSize = 11.sp)
                }
                Text("B. FileBrowser mode and directory", color = accent, fontWeight = FontWeight.SemiBold)
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = safe,
                    onClick = { modeStartDialog = true }) {
                    Text("3. ENTER MOVIE PICKER  0x01FD (changes mode)", fontSize = 11.sp)
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = safe,
                    onClick = { report("ModeGet", onModeGet()) }) {
                    Text("4. FILEBROWSER MODE GET  0x01FF", fontSize = 11.sp)
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth(),
                    enabled = safe && !probe.consumedNames.contains("dir-info"),
                    onClick = { report("DirInfoGet", onProbeGet("dir-info")) }) {
                    Text("5. DIRECTORY INFO GET  0x01F8", fontSize = 11.sp)
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = safe,
                    onClick = { report("FileListGet E0", onFileListGet()) }) {
                    Text("6. LOAD SSD FILENAMES E0  0x01FB (count=4)", fontSize = 11.sp)
                }
                if (probe.consumedNames.contains("dir-info")) {
                    Text("DirInfo already tested; its button stays grey by one-shot guard. " +
                        "Status=${probe.phase}. This is not an endless GATT operation; " +
                        "you can continue to FileList after timeout.", color = warning, fontSize = 11.sp)
                }
                Text("C. AG2 route A/B: experimental READ-ONLY E8 pilot", color = accent, fontWeight = FontWeight.SemiBold)
                Text("The Mac constructor indicates nonlocal E0, but successful AG2 Input/Settings GETs " +
                    "have used local E8. This tests only FileListGet on E8 once. " +
                    "It does not change global GATT routing.", color = muted, fontSize = 11.sp)
                Row {
                    Checkbox(checked = localE8Consent, onCheckedChange = { localE8Consent = it })
                    Text("I authorize ONE local E8 FileList GET after the previous E0 test", 
                        modifier = Modifier.weight(1f), fontSize = 11.sp, color = warning)
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth(),
                    enabled = safe && localE8Consent && !localE8Sent,
                    onClick = {
                        val queued = onLocalFileListGet()
                        if (queued) localE8Sent = true
                        report("FileListGet LOCAL E8 0x01FB (one-shot)", queued)
                    }) {
                    Text("7. LOCAL E8 FILELIST GET 0x01FB (ONCE)", fontSize = 11.sp)
                }
                Text("D. Advanced, inferred GET only", color = accent, fontWeight = FontWeight.SemiBold)
                Row {
                    Checkbox(checked = enableInferred, onCheckedChange = { enableInferred = it })
                    Text("Enable unverified CurrentIndexGet opcode test (0x01F4)",
                        modifier = Modifier.weight(1f), fontSize = 11.sp, color = warning)
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth(),
                    enabled = safe && enableInferred && !probe.consumedNames.contains("current-index-inferred"),
                    onClick = { report("CurrentIndexGet candidate", onProbeGet("current-index-inferred")) }) {
                    Text("8. CURRENT INDEX GET  0x01F4 (inferred)", fontSize = 11.sp)
                }
                Text("Locked: FilePlay 0x01FC, DirGo 0x01F9, ParentGo 0x01FA, ModeExit 0x01FE, " +
                    "CurrentIndexSet 0x01F5 and other unverified state-changing calls. " +
                    "Filename-size SET is not part of this diagnostic sequence.",
                    color = warning, fontSize = 11.sp)
            }
            HorizontalDivider(color = Color(0xFF355052))
            Text(feedback, color = warning, fontSize = 11.sp)
            Text("Last GET: ${probe.name}, phase=${probe.phase}, " +
                "request=${probe.opcode?.let { "0x" + it.toString(16) } ?: "none"}",
                color = fg, fontSize = 11.sp)
            Text(probe.status, color = muted, fontSize = 11.sp)
            Text("Response opcode=${probe.responseOpcode?.let { "0x" + it.toString(16) } ?: "none"} " +
                "route=${probe.responseLocal?.let { if (it) "E8" else "E0" } ?: "none"}",
                color = muted, fontSize = 11.sp)
            if (probe.responseHex.isNotEmpty()) Text("Response raw: ${probe.responseHex}", color = accent, fontSize = 10.sp)
            probe.lastRawAtomMessages.takeLast(3).forEach { entry ->
                Text(entry, color = muted, fontSize = 10.sp)
            }
            Text("FileList pending=${mediaFileList.pending}; confirmed filenames=" +
                "${if (mediaFileList.recorderValidated) mediaFileList.entries.size else 0}. " +
                "ATT status 0 is not recorder acceptance.", color = muted, fontSize = 11.sp)
        }
    }
    if (modeStartDialog) {
        AlertDialog(onDismissRequest = { modeStartDialog = false },
            title = { Text("Enter recorder MoviePicker?") },
            text = { Text("One-shot ModeStart 0x01FD is state-changing. Recording must be stopped. " +
                "No automatic FilePlay/ModeExit and no retry. Proceed?") },
            confirmButton = { TextButton(onClick = {
                modeStartDialog = false
                report("ModeStart MoviePicker", onModeStart())
            }) { Text("SEND ONCE") } },
            dismissButton = { TextButton(onClick = { modeStartDialog = false }) { Text("CANCEL") } }
        )
    }
}
