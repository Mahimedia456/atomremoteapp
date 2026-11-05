package com.atomosremote.nativeapp.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * U04.55: combined Mac-style browser, evidence state, and locked remote controls.
 * Play/Pause uses existing verified UI callbacks only.
 * Previous/Next/clip selection and FileList query are NOT wired to any BLE TX.
 * No screenshots' example filenames are inserted into the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun U0455MacStylePlaybackDeck(
    mediaFileList: SsdBrowserState,
    controlsEnabled: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit
) {
    val cyan = Color(0xFF19E7D3)
    val muted = Color(0xFF91A6A8)
    val amber = Color(0xFFFFCB91)
    val gateway = remember(mediaFileList) { SsdBrowserTransactionU0455.fromExistingRecorderState(mediaFileList) }
    val verifiedNames = gateway.receivedFileList && gateway.candidateNames.isNotEmpty()
    var browserSheetOpen by rememberSaveable { mutableStateOf(false) }
    var localHighlightedIndex by remember { mutableStateOf<Int?>(null) }
    val visibleRows = remember(gateway.candidateNames) { gateway.candidateNames.sortedBy { it.index }.take(256) }
    val localPreviewName = visibleRows.firstOrNull { it.index == localHighlightedIndex }?.filename
    LaunchedEffect(mediaFileList.generation, mediaFileList.recorderValidated) {
        localHighlightedIndex = null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFF24504D)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1719))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("PLAYBACK", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text("RECORDER CLIP", color = cyan, fontSize = 10.sp)
            }
            // No connected API presently returns the active recorder-selected clip name.
            // Even a decoder candidate in mediaFileList.entries is not active-selection proof.
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { browserSheetOpen = true },
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF273134),
                border = BorderStroke(1.dp, Color(0xFF496064))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(localPreviewName ?: "Clip name not synced", color = Color.White,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (localPreviewName != null) "LOCAL PREVIEW ONLY | recorder selection unchanged"
                             else "Tap for SSD browser | recorder active clip unknown", color = muted,
                            fontSize = 10.sp)
                    }
                    Text("BROWSE  >", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // No AG2-verified previous-clip BLE control ID exists in the frozen API.
                OutlinedButton(
                    onClick = {}, enabled = false, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp)
                ) { Text("|<", fontSize = 17.sp) }
                Button(
                    onClick = onPlay, enabled = controlsEnabled, modifier = Modifier.weight(1.3f),
                    contentPadding = PaddingValues(horizontal = 3.dp, vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF126158))
                ) { Text("PLAY", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = onPause, enabled = controlsEnabled, modifier = Modifier.weight(1.3f),
                    contentPadding = PaddingValues(horizontal = 3.dp, vertical = 10.dp)
                ) { Text("PAUSE", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = {}, enabled = false, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp)
                ) { Text(">|", fontSize = 17.sp) }
            }
            Text("Play 0x0191 / Pause 0x0192: existing verified callbacks. Remote Previous / Next locked: AG2 command IDs unverified.",
                color = muted, fontSize = 10.sp)
            Text("RX local=${gateway.localRx} nonlocal=${gateway.nonLocalRx} | gen=${gateway.generation} | " +
                 "FileList validated=${gateway.receivedFileList}", color = muted, fontSize = 10.sp)
        }
    }

    if (browserSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { browserSheetOpen = false },
            containerColor = Color(0xFF101719),
            contentColor = Color.White,
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF8BA7A9)) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Video files", color = Color.White, fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold)
                Text("SSD /", color = cyan, fontSize = 12.sp)
                Text("Directory query 0x01FB: Mac static constructor. " +
                     "Exact wire serialization/response unverified; TX blocked. " +
                     "0x01FC is an index operation of uncertain purpose.", color = muted, fontSize = 10.sp)
                Text("Android RX: local ${gateway.localRx}, nonlocal ${gateway.nonLocalRx}, " +
                     "generation ${gateway.generation}; last opcode=" +
                     (gateway.lastOpcode?.let { "0x" + it.toString(16) } ?: "none"),
                     color = muted, fontSize = 11.sp)
                if (mediaFileList.entries.isEmpty()) {
                    Surface(color = Color(0xFF253133), shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("No recorder file names received yet", color = amber,
                                fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("The SSD may contain clips. The current FileBrowser request has not produced a verified filename response.",
                                color = Color.White, fontSize = 12.sp)
                            Text("Last state: ${mediaFileList.status}", color = muted, fontSize = 11.sp)
                        }
                    }
                } else {
                    Text(
                        if (verifiedNames) "Recorder-validated listing: ${mediaFileList.entries.size} entries"
                        else "Decoded candidate entries: ${mediaFileList.entries.size} (not validated on recorder)",
                        color = if (verifiedNames) cyan else amber, fontSize = 12.sp
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        items(visibleRows, key = { it.index }) { entry ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    // Local highlight only, NEVER FilePlay or recorder selection.
                                    localHighlightedIndex = entry.index
                                },
                                color = if (localHighlightedIndex == entry.index)
                                    Color(0xFF254E4B) else Color(0xFF222A2C),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("FILE", color = muted, fontSize = 10.sp,
                                        modifier = Modifier.width(42.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.filename, color = Color.White, fontSize = 13.sp,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text("Index ${entry.index}  |  ${if (verifiedNames) "Listing verified" else "Candidate only"}",
                                            color = muted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                    if (localHighlightedIndex != null) {
                        Text("Highlighted locally: index $localHighlightedIndex. Recorder has NOT switched clips.",
                            color = amber, fontSize = 11.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = visibleRows.size > 1,
                            onClick = {
                                val pos = visibleRows.indexOfFirst { it.index == localHighlightedIndex }
                                val nextPos = if (pos <= 0) 0 else pos - 1
                                localHighlightedIndex = visibleRows[nextPos].index
                            }, modifier = Modifier.weight(1f)
                        ) { Text("PREV HIGHLIGHT", fontSize = 10.sp) }
                        OutlinedButton(
                            enabled = visibleRows.size > 1,
                            onClick = {
                                val pos = visibleRows.indexOfFirst { it.index == localHighlightedIndex }
                                val nextPos = if (pos < 0) 0 else (pos + 1).coerceAtMost(visibleRows.lastIndex)
                                localHighlightedIndex = visibleRows[nextPos].index
                            }, modifier = Modifier.weight(1f)
                        ) { Text("NEXT HIGHLIGHT", fontSize = 10.sp) }
                    }
                    Text("Highlight navigation is Android-only, NOT recorder Previous/Next.",
                         color = amber, fontSize = 10.sp)
                }
                OutlinedButton(
                    onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()
                ) { Text("REQUEST DIRECTORY 0x01FB | WIRE NOT VERIFIED", fontSize = 11.sp) }
                OutlinedButton(
                    onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()
                ) { Text("PLAY SELECTED FILE | RECORDER COMMAND UNVERIFIED", fontSize = 11.sp) }
                Text("Mac browser and playback are observed; original on-wire clip-selection contract is still unverified on this AG2.",
                    color = muted, fontSize = 11.sp)
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
