package com.atomosremote.nativeapp.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atomosremote.nativeapp.viewmodel.RemoteTransportUiController

/**
 * U04.86 evidence-correct Media UI. FileList errors are NOT inferred from uncorrelated RX.
 * All mode/Play/Pause callbacks are the existing recorder implementations.
 * FileList requests are manually initiated, read-only, and never retried automatically.
 * No unverified FilePlay, ModeStart, FilenameSize or overlay commands are exposed.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun MediaModePlaybackScreen(
    ui: RemoteTransportUiController.State,
    mediaFileList: SsdBrowserState,
    fileListEventLines: List<String> = emptyList(),
    browserProbe: U0465ProbeState,
    remoteAvailable: Boolean,
    remotePhase: String,
    commandStatus: String?,
    onPlaybackMode: () -> Unit,
    onRecordMode: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSsdListEvidence: (Int) -> Unit,
    onDiagnosticTest: (String) -> Boolean,
    onSsdListTimeout: (Long) -> Unit,
    onSsdListReset: () -> Unit,
    onModeGetEvidence: () -> Boolean,
    onModeStart: () -> Boolean,
    onFilenameSize: () -> Boolean,
    onFilePlay: (Int) -> Boolean,
    onPageRequest: (Int) -> Boolean,
    onMediaStateGetEvidence: () -> Boolean,
    onBrowserProbeGet: (String) -> Boolean,
    onBrowserProbeTimeout: (Long) -> Unit,
    onRecover: () -> Unit
) {
    val accent = Color(0xFF19E7D3)
    val surface = Color(0xFF0C181B)
    val subdued = Color(0xFF9BB0B4)
    val warning = Color(0xFFFFCB91)
    val transportBusy = remotePhase in setOf(
        "queued", "sending", "requesting-credit", "awaiting-credit",
        "awaiting-response", "bootstrapping"
    )
    val playback = ui.mode == RemoteTransportUiController.Mode.PLAYBACK
    val ready = remoteAvailable && !ui.locked && !transportBusy &&
        !ui.recoveryRequired && ui.pendingAction == null

    // Never show filenames from a sample Mac capture or a previous recorder session.
    val liveFiles = remember { mutableStateListOf<SsdBrowserEntry>() }
    var fileCount by remember { mutableStateOf<Int?>(null) }
    var processed by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var requestFeedback by remember { mutableStateOf("") }
    var selectedClipIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(remoteAvailable, playback, mediaFileList.generation) {
        if (!remoteAvailable || !playback ||
            (mediaFileList.pending && mediaFileList.requestedStartIndex == 0)) {
            liveFiles.clear()
            selectedClipIndex = null
            fileCount = null
            processed = null
            requestFeedback = ""
        }
    }
    LaunchedEffect(fileListEventLines, mediaFileList.fileListOpcodeRx,
        mediaFileList.generation, mediaFileList.pending, remoteAvailable, playback) {
        if (remoteAvailable && playback && mediaFileList.pending) {
            val hit = U0479LiveFileListBridge.fromEvents(fileListEventLines, mediaFileList)
            if (hit != null && processed != (hit.generation to hit.responsePacketId)) {
                if (hit.page.start == 0) liveFiles.clear()
                val knownIndices = liveFiles.map { it.index }.toSet()
                liveFiles.addAll(hit.page.entries.filter { it.index !in knownIndices })
                fileCount = hit.page.total ?: fileCount
                processed = hit.generation to hit.responsePacketId
                requestFeedback = ""
            }
        }
    }
    LaunchedEffect(mediaFileList.pending, mediaFileList.generation) {
        if (mediaFileList.pending) {
            kotlinx.coroutines.delay(15_000L)
            onSsdListTimeout(mediaFileList.generation)
        }
    }
    LaunchedEffect(remoteAvailable, playback) {
        if (!remoteAvailable || !playback) onSsdListReset()
    }

    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFF284B4B)),
        colors = CardDefaults.cardColors(containerColor = surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("MEDIA", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRecordMode,
                    enabled = ready && ui.mode != RemoteTransportUiController.Mode.RECORD,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, if (!playback) accent else subdued),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) { Text("RECORD", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = onPlaybackMode,
                    enabled = ready && !playback,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, if (playback) accent else subdued),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) { Text("PLAYBACK", fontSize = 12.sp) }
            }
            Text(
                when {
                    !remoteAvailable -> "Not connected"
                    ui.recoveryRequired -> "Connection recovery required"
                    playback -> "Playback selected"
                    ui.mode == RemoteTransportUiController.Mode.RECORD -> "Record selected"
                    else -> "Recorder mode not confirmed"
                },
                color = if (remoteAvailable && !ui.recoveryRequired) accent else warning,
                fontSize = 12.sp
            )
            if (ui.pendingAction != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = accent)
                Text(ui.pendingLabel ?: "Command in progress", color = subdued, fontSize = 11.sp)
            }
            if (ui.recoveryRequired) {
                Text(ui.error ?: "Reconnect to continue", color = warning, fontSize = 11.sp)
                OutlinedButton(onClick = onRecover, modifier = Modifier.fillMaxWidth()) {
                    Text("RECOVER CONNECTION", fontSize = 12.sp)
                }
            }
            if (!commandStatus.isNullOrBlank()) {
                Text(commandStatus, color = subdued, fontSize = 11.sp)
            }
        }
    }

    // Passive observation only. These events can be historical and must never
    // authorize new recorder SET commands or substitute for a fresh FileList RX.
    val macInitRx = U0482MacSequenceGate.observe(fileListEventLines)

    if (playback) {
        Card(
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = surface)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("PLAYBACK CONTROLS", color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                // Five-position transport dock. Four experimental operations are
                // intentionally DISABLED until genuine recorder opcode/payload/ACK
                // evidence exists. Do not infer an FF opcode from a nearby constant.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = {}, enabled = false,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 1.dp, vertical = 8.dp)
                    ) { Text("PREV", fontSize = 10.sp) }
                    OutlinedButton(
                        onClick = {}, enabled = false,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 1.dp, vertical = 8.dp)
                    ) { Text("REV", fontSize = 10.sp) }
                    Column(modifier = Modifier.weight(1.45f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = onPlay, enabled = ready && !mediaFileList.pending,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)
                        ) { Text("PLAY", fontSize = 11.sp) }
                        OutlinedButton(
                            onClick = onPause, enabled = ready && !mediaFileList.pending,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)
                        ) { Text("PAUSE", fontSize = 11.sp) }
                    }
                    OutlinedButton(
                        onClick = {}, enabled = false,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 1.dp, vertical = 8.dp)
                    ) { Text("FWD", fontSize = 10.sp) }
                    OutlinedButton(
                        onClick = {}, enabled = false,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 1.dp, vertical = 8.dp)
                    ) { Text("NEXT", fontSize = 10.sp) }
                }
                Text(
                    "PREV / REV / FWD / NEXT locked: recorder command not verified. " +
                        "PLAY / PAUSE control the current recorder clip.",
                    color = subdued, fontSize = 11.sp
                )
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = surface)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SSD FILES", color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    if (macInitRx.modeStartResponseObserved)
                        "SSD browsing is awaiting live recorder verification"
                    else "SSD browsing is awaiting live recorder verification",
                    color = subdued, fontSize = 11.sp
                )
                // A generic 0xFFFF was seen after Android 0x01FB in the
                // user capture, but its packetId differs from the outbound
                // packetId. It is NOT a correlated FileList rejection and
                // must not suppress the manual retry or imply an empty SSD.
                val genericUnmatched = mediaFileList.localApplicationRx > 0 &&
                    mediaFileList.lastApplicationOpcode == 0xFFFF &&
                    mediaFileList.fileListOpcodeRx == 0
                val fileListOpcodeSeen = mediaFileList.fileListOpcodeRx > 0
                val sorted = liveFiles.distinctBy { it.index }.sortedBy { it.index }
                if (sorted.isEmpty()) {
                    Text(
                        when {
                            mediaFileList.pending && fileListOpcodeSeen ->
                                "FileList opcode observed. Validating live names..."
                            mediaFileList.pending && genericUnmatched ->
                                "Generic 0xFFFF observed, not matched to FileList request; waiting for 0x81FB."
                            mediaFileList.pending -> "Reading SSD files..."
                            fileListOpcodeSeen ->
                                "0x81FB observed but no valid filename page decoded. Capture RX for parser review."
                            genericUnmatched ->
                                "Generic 0xFFFF observed (unmatched request ID). No confirmed FileList reply."
                            mediaFileList.generation > 0L ->
                                "No verified FileList reply in this request window. SSD is NOT confirmed empty."
                            else -> "No verified SSD file list received."
                        }, color = if (genericUnmatched || fileListOpcodeSeen) warning else subdued,
                        fontSize = 12.sp
                    )
                } else {
                    Text("${sorted.size} files" + (fileCount?.let { " / $it" } ?: ""),
                        color = accent, fontSize = 12.sp)
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(sorted, key = { it.index }) { file ->
                            OutlinedButton(
                                onClick = { selectedClipIndex = file.index },
                                enabled = ready && !mediaFileList.pending,
                                modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(1.dp,
                                    if (selectedClipIndex == file.index) accent else Color(0xFF284B4B)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(file.filename, color = Color.White, fontSize = 12.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    val selectedFile = sorted.firstOrNull { it.index == selectedClipIndex }
                    if (selectedFile != null) {
                        Text("SELECTED: ${selectedFile.filename}",
                            color = accent, fontSize = 12.sp)
                        // Only the confirmed existing PLAY callback may transmit.
                        // FilePlay (0x01FC) is NOT recorder-verified, so selection
                        // alone must never call onFilePlay or start playback.
                        Text("Selected-clip playback awaits recorder verification. " +
                            "PLAY controls the current recorder clip.",
                            color = warning, fontSize = 11.sp)
                    }
                    val nextStart = sorted.last().index + 1
                    val hasMore = fileCount == null || nextStart < fileCount!!
                    if (hasMore && !mediaFileList.pending) {
                        OutlinedButton(
                            onClick = {
                                val queued = onPageRequest(nextStart)
                                requestFeedback = if (queued) "Loading more..." else "Request not queued"
                            }, enabled = ready, modifier = Modifier.fillMaxWidth()
                        ) { Text("LOAD MORE", fontSize = 12.sp) }
                    }
                }
                // No auto retry. Unlike U04.85, an UNCORRELATED 0xFFFF does not
                // permanently hide the button. Every retry is user initiated.
                if (sorted.isEmpty() && !mediaFileList.pending) {
                    OutlinedButton(
                        onClick = {
                            val queued = onDiagnosticTest("filelist-local")
                            requestFeedback = if (queued) "Reading SSD files..." else "Request not queued"
                        }, enabled = ready, modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (mediaFileList.generation > 0L) "RETRY SSD FILES" else "LOAD SSD FILES",
                            fontSize = 12.sp)
                    }
                }
                if (requestFeedback.isNotBlank()) {
                    Text(requestFeedback, color = subdued, fontSize = 11.sp)
                }
                if (mediaFileList.generation > 0L) {
                    Text(
                        "FileList GET: " + mediaFileList.status +
                            " | 0x81FB RX=" + mediaFileList.fileListOpcodeRx +
                            " | local RX=" + mediaFileList.localApplicationRx,
                        color = subdued, fontSize = 10.sp
                    )
                }
                // Clip selection is read-only. No FilePlay, Fast Forward, Rewind,
                // Next, Previous or other unverified SET may transmit from here.
            }
        }
    }
}
