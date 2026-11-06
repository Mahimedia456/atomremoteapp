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
 * U04.59 user-facing playback / FileBrowser console.
 * Play/Pause reuse the original recorder callbacks. FilePlay and navigation are
 * available only for indices obtained from a correlated live FileList reply.
 * No fake SSD rows or fabricated hardware acceptance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun U0455MacStylePlaybackDeck(
    mediaFileList: SsdBrowserState,
    controlsEnabled: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onExperimentalBrowse: () -> Boolean,
    onModeStart: () -> Boolean,
    onModeGet: () -> Boolean,
    onStopWaiting: () -> Unit,
    onFilenameSize: () -> Boolean,
    onFilePlay: (Int) -> Boolean,
    onPageRequest: (Int) -> Boolean
) {
    val cyan = Color(0xFF15D7C4)
    val muted = Color(0xFFA5B4B9)
    val amber = Color(0xFFFFC98D)
    val bodyColor = Color(0xFF0B171A)
    val buttonBorder = Color(0xFF4D686A)
    val rows = remember(mediaFileList.entries, mediaFileList.recorderValidated) {
        if (!mediaFileList.recorderValidated) emptyList()
        else mediaFileList.entries.distinctBy { it.index }.sortedBy { it.index }.take(1024)
    }
    var selectedIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var initConfirm by remember { mutableStateOf(false) }
    var scanConfirm by remember { mutableStateOf(false) }
    var playConfirm by remember { mutableStateOf<Int?>(null) }
    var operatorPlayback by rememberSaveable { mutableStateOf(false) }
    var operatorBackup by rememberSaveable { mutableStateOf(false) }
    var remoteClipControlArmed by rememberSaveable { mutableStateOf(false) }
    var noQuickRx by remember { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var actionFeedback by rememberSaveable { mutableStateOf("") }
    // U04.63: UI-side sequence flag only. Queued is NOT confirmed MoviePicker state.
    var primaryMoviePickerRequested by rememberSaveable { mutableStateOf(false) }
    val selected = rows.firstOrNull { it.index == selectedIndex }
    val active = rows.firstOrNull { it.index == mediaFileList.activeIndex }
    val selectedPos = rows.indexOfFirst { it.index == selectedIndex }
    val safeToSend = controlsEnabled && !mediaFileList.pending && operatorPlayback && operatorBackup
    val clipActionsAllowed = safeToSend && remoteClipControlArmed &&
        mediaFileList.recorderValidated && rows.isNotEmpty()

    LaunchedEffect(mediaFileList.pending, mediaFileList.generation) {
        noQuickRx = false
        if (mediaFileList.pending) {
            kotlinx.coroutines.delay(4_000L)
            noQuickRx = true
        }
    }
    LaunchedEffect(rows) {
        if (selectedIndex != null && rows.none { it.index == selectedIndex }) selectedIndex = null
        if (selectedIndex == null && rows.isNotEmpty()) selectedIndex = rows.first().index
    }
    fun actionForIndex(index: Int) {
        if (!clipActionsAllowed || rows.none { it.index == index }) return
        val queued = onFilePlay(index)
        if (queued) selectedIndex = index
        actionFeedback = if (queued) "Clip ${index} FilePlay queued; waiting for recorder reply." else
            "Clip command not queued; recorder may be busy."
    }
    fun stepIndex(delta: Int): Int? {
        if (rows.isEmpty()) return null
        val index = when {
            selectedPos == -1 -> if (delta > 0) 0 else rows.lastIndex
            else -> (selectedPos + delta).coerceIn(0, rows.lastIndex)
        }
        return rows[index].index
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFF28514F)),
        colors = CardDefaults.cardColors(containerColor = bodyColor)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PLAYBACK", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("ATOMOS / SSD", color = cyan, fontSize = 10.sp)
            }
            Surface(modifier = Modifier.fillMaxWidth().clickable { sheetOpen = true },
                shape = RoundedCornerShape(12.dp), color = Color(0xFF223134),
                border = BorderStroke(1.dp, buttonBorder)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(active?.filename ?: selected?.filename ?: "No verified SSD filename received",
                            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (active != null) "Recorder accepted FilePlay for this index"
                             else if (selected != null) "Selected from live recorder list, playback unconfirmed"
                             else "SSD browsing unverified on AG2; Play/Pause remains available",
                             color = muted, fontSize = 10.sp)
                    }
                    Text("BROWSE  ›", color = cyan, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { stepIndex(-1)?.let { playConfirm = it } },
                    enabled = clipActionsAllowed && (selectedPos > 0 || selectedPos == -1),
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                    Text("◀", fontSize = 16.sp)
                }
                Button(onClick = onPlay, enabled = controlsEnabled && !mediaFileList.pending,
                    modifier = Modifier.weight(1.4f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF176F61)),
                    contentPadding = PaddingValues(horizontal = 1.dp)) {
                    Text("PLAY", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onPause, enabled = controlsEnabled && !mediaFileList.pending,
                    modifier = Modifier.weight(1.4f), contentPadding = PaddingValues(horizontal = 1.dp)) {
                    Text("PAUSE", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = { stepIndex(1)?.let { playConfirm = it } },
                    enabled = clipActionsAllowed && (selectedPos < rows.lastIndex),
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                    Text("▶", fontSize = 16.sp)
                }
            }
            Text("Play/Pause: existing recorder control. Prev/Next: FilePlay index from a real SSD list only, with confirmation.",
                color = muted, fontSize = 10.sp)
            if (mediaFileList.pending) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = cyan)
                Text(if (noQuickRx) "No immediate reply. RX listening ends after 15 seconds, without auto retry."
                     else "Waiting for FileList application reply…", color = amber, fontSize = 11.sp)
            } else if (rows.isEmpty()) {
                Text("SSD contents unknown: no recorder filename response has been received.",
                    color = muted, fontSize = 11.sp)
            } else {
                Text("${rows.size} filenames from live recorder RX" +
                    (mediaFileList.totalFiles?.let { " / $it total" } ?: ""),
                    color = cyan, fontSize = 11.sp)
            }
            if (actionFeedback.isNotBlank()) Text(actionFeedback, color = amber, fontSize = 11.sp)
        }
    }


    // U04.61 PRIMARY FILE BROWSER: read-only list access is visible in Playback.
    // A manual FileList GET is not FilePlay, ModeStart or a destructive SET.
    // Keep existing secondary sheet and verified Play/Pause paths unchanged.
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFF28514F)),
        colors = CardDefaults.cardColors(containerColor = bodyColor)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SSD BROWSER (UNVERIFIED)", color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text("${rows.size} confirmed", color = cyan, fontSize = 11.sp)
            }
            Text("Experimental FileBrowser. This AG2 has not confirmed MoviePicker or a filename-list response.",
                color = muted, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = operatorPlayback, onCheckedChange = { operatorPlayback = it })
                Text("Recorder in Playback, recording stopped", color = Color.White,
                    fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = operatorBackup, onCheckedChange = { operatorBackup = it })
                Text("SSD inserted; important recordings backed up", color = Color.White,
                    fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
            OutlinedButton(
                onClick = { initConfirm = true },
                enabled = safeToSend && !primaryMoviePickerRequested,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("1. ENTER MOVIE PICKER (0x01FD)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(if (primaryMoviePickerRequested)
                     "ModeStart queued in this session, recorder acceptance not confirmed."
                 else "First enter MoviePicker. Playback Mode alone does not confirm FileBrowser mode.",
                color = amber, fontSize = 11.sp)
            OutlinedButton(
                onClick = {
                    val queued = onModeGet()
                    actionFeedback = if (queued) "ModeGet queued; actual mode reply pending"
                        else "ModeGet not queued; check PacketLink credit"
                    android.util.Log.i("AtomRemoteMediaU0463", "SEQUENCE_MODEGET queued=$queued")
                },
                enabled = safeToSend && primaryMoviePickerRequested,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("2. CHECK MOVIE PICKER MODE (0x01FF)", fontSize = 11.sp)
            }
            Text("ModeGet reply: ${mediaFileList.modeReadback?.toString() ?: "not received"} (MoviePicker=1).",
                color = muted, fontSize = 11.sp)
            Button(
                onClick = {
                    val queued = onExperimentalBrowse()
                    actionFeedback = if (queued)
                        "FileListGet queued for up to 4 names; actual recorder RX pending"
                    else "FileListGet not queued; check connection/credit"
                    android.util.Log.i("AtomRemoteMediaU0463",
                        "SEQUENCE_FILELIST queued=$queued modeStartQueued=$primaryMoviePickerRequested expectedCount=4")
                },
                enabled = safeToSend && primaryMoviePickerRequested &&
                    (mediaFileList.modeReadback == null || mediaFileList.modeReadback == 1),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF176F61))
            ) {
                Text(if (mediaFileList.pending) "WAITING FOR RECORDER REPLY..."
                    else "3. TEST SSD LIST GET (UP TO 4)",
                    fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (mediaFileList.modeReadback != null && mediaFileList.modeReadback != 1) {
                Text("Recorder did not report MoviePicker mode. FileList disabled.",
                    color = amber, fontSize = 11.sp)
            }
            if (mediaFileList.pending) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = cyan)
                Text("Waiting for an actual FileList response; no automatic retries.",
                    color = amber, fontSize = 11.sp)
            }
            if (rows.isEmpty()) {
                Text("No filenames received from recorder yet. This is not an empty-SSD result.",
                    color = amber, fontSize = 12.sp)
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(rows, key = { it.index }) { file ->
                        val chosen = file.index == selectedIndex
                        Surface(modifier = Modifier.fillMaxWidth().clickable { selectedIndex = file.index },
                            color = if (chosen) Color(0xFF285651) else Color(0xFF243033),
                            shape = RoundedCornerShape(9.dp)) {
                            Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (chosen) "●" else "○", color = cyan)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(file.filename, color = Color.White, fontSize = 12.sp,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("Clip index ${file.index}", color = muted, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { sheetOpen = true },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("OPEN CLIP ACTIONS", fontSize = 11.sp)
                }
            }
            Text(mediaFileList.status, color = muted, fontSize = 10.sp)
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false },
            containerColor = Color(0xFF111C1E), contentColor = Color.White,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SSD FILE BROWSER", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text("Shogun Ultra AG2 | MoviePicker", color = muted, fontSize = 11.sp)
                if (rows.isEmpty()) {
                    Surface(color = Color(0xFF263237), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(15.dp)) {
                            Text("Directory not synchronized", color = amber,
                                fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("No real filename response yet. This does not mean SSD is empty.",
                                color = Color.White, fontSize = 12.sp)
                            Text(mediaFileList.status, color = muted, fontSize = 10.sp)
                        }
                    }
                } else {
                    Text("Choose a real recorder filename", color = muted, fontSize = 11.sp)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(rows, key = { it.index }) { file ->
                            val chosen = file.index == selectedIndex
                            Surface(modifier = Modifier.fillMaxWidth().clickable { selectedIndex = file.index },
                                color = if (chosen) Color(0xFF285651) else Color(0xFF243033),
                                shape = RoundedCornerShape(9.dp)) {
                                Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (chosen) "●" else "○", color = cyan)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(file.filename, color = Color.White, fontSize = 12.sp,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text("Index ${file.index}" +
                                            if (mediaFileList.activeIndex == file.index) " / recorder ACK" else "",
                                            color = muted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                    Button(onClick = { selectedIndex?.let { playConfirm = it } },
                        enabled = clipActionsAllowed && selectedIndex != null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF176F61))) {
                        Text("PLAY SELECTED FILE ON RECORDER", fontSize = 11.sp)
                    }
                    val lastIndex = rows.last().index
                    val moreKnown = mediaFileList.totalFiles == null || lastIndex + 1 < mediaFileList.totalFiles
                    OutlinedButton(onClick = {
                        val queued = onPageRequest(lastIndex + 1)
                        actionFeedback = if (queued) "Page from index ${lastIndex + 1} queued" else
                            "Next page not queued. Wait for previous command to finish."
                    }, enabled = safeToSend && moreKnown, modifier = Modifier.fillMaxWidth()) {
                        Text("LOAD MORE FILES", fontSize = 11.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = operatorPlayback, onCheckedChange = { operatorPlayback = it })
                    Text("Recorder in Playback, recording stopped", color = Color.White,
                        fontSize = 11.sp, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = operatorBackup, onCheckedChange = { operatorBackup = it })
                    Text("SSD inserted and important recordings backed up", color = Color.White,
                        fontSize = 11.sp, modifier = Modifier.weight(1f))
                }
                OutlinedButton(onClick = { initConfirm = true }, enabled = safeToSend && !primaryMoviePickerRequested,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("1. ENTER MOVIE PICKER  (0x01FD)", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { }, enabled = false,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("FILENAME LENGTH SET LOCKED (UNVERIFIED)", fontSize = 11.sp)
                }
                Button(onClick = { scanConfirm = true }, enabled = safeToSend,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF176F61))) {
                    Text("TEST SSD FILE LIST (0x01FB)", fontSize = 12.sp)
                }
                if (mediaFileList.pending) {
                    Text("Waiting for packet-correlated application response. ATT success is not a FileList reply.",
                        color = amber, fontSize = 11.sp)
                    TextButton(onClick = onStopWaiting, modifier = Modifier.fillMaxWidth()) {
                        Text("STOP WAITING", fontSize = 11.sp)
                    }
                }
                if (rows.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = remoteClipControlArmed,
                            onCheckedChange = { remoteClipControlArmed = it })
                        Text("Enable experimental FilePlay/Prev/Next for these recorder indices",
                            color = amber, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    }
                }
                if (actionFeedback.isNotBlank()) Text(actionFeedback, color = amber, fontSize = 11.sp)
                TextButton(onClick = { detailsOpen = !detailsOpen }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (detailsOpen) "HIDE PROTOCOL DETAILS" else "PROTOCOL DETAILS", fontSize = 11.sp)
                }
                if (detailsOpen) {
                    Text("FileList=0x01FB, FilePlay=0x01FC, ModeStart=0x01FD, ModeExit=0x01FE, ModeGet=0x01FF, FilenameSize=0x0202",
                        color = muted, fontSize = 10.sp)
                    Text("RX E0=${mediaFileList.nonLocalApplicationRx}, E8=${mediaFileList.localApplicationRx}, listOpcode=${mediaFileList.fileListOpcodeRx}; last=${mediaFileList.lastApplicationOpcode}; mode=${mediaFileList.modeReadback}",
                        color = muted, fontSize = 10.sp)
                    Text(mediaFileList.status, color = muted, fontSize = 10.sp)
                    OutlinedButton(onClick = {
                        actionFeedback = if (onModeGet()) "ModeGet queued" else "ModeGet not queued"
                    }, enabled = safeToSend, modifier = Modifier.fillMaxWidth()) {
                        Text("MODE GET  (0x01FF)", fontSize = 11.sp)
                    }
                }
                Text("Only source-correlated filenames can be selected. Opcode, route and response compatibility still require an AG2 test.",
                    color = muted, fontSize = 10.sp)
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (initConfirm) {
        AlertDialog(onDismissRequest = { initConfirm = false },
            title = { Text("Enter FileBrowser MoviePicker") },
            text = { Text("Send ModeStart 0x01FD with ModeMsg(mode=1) once. " +
                "This changes recorder mode; make sure recording is stopped. Continue?") },
            confirmButton = { TextButton(onClick = {
                initConfirm = false
                val queued = onModeStart()
                if (queued) primaryMoviePickerRequested = true
                actionFeedback = if (queued) "ModeStart queued, NOT recorder-confirmed" else
                    "ModeStart blocked or already attempted. Reconnect if a fresh test is required."
                android.util.Log.i("AtomRemoteMediaU0463", "SEQUENCE_MODESTART queued=$queued")
            }) { Text("SEND") } },
            dismissButton = { TextButton(onClick = { initConfirm = false }) { Text("CANCEL") } })
    }
    if (scanConfirm) {
        AlertDialog(onDismissRequest = { scanConfirm = false },
            title = { Text("Read SSD file list") },
            text = { Text("Send one FileListQuery 0x01FB (non-local E0). " +
                "Request up to 4 filenames (Mac controller default); live device support unverified. " +
                "No automatic retry or file deletion.") },
            confirmButton = { TextButton(onClick = {
                scanConfirm = false
                val queued = onExperimentalBrowse()
                actionFeedback = if (queued) "Directory request queued; waiting for live RX" else
                    "Directory request blocked; check link and PacketLink credit"
                android.util.Log.i("AtomRemoteMediaU0459", "LIST_UI_REQUEST queued=$queued")
            }) { Text("LOAD") } },
            dismissButton = { TextButton(onClick = { scanConfirm = false }) { Text("CANCEL") } })
    }
    playConfirm?.let { target ->
        val file = rows.firstOrNull { it.index == target }
        AlertDialog(onDismissRequest = { playConfirm = null },
            title = { Text("Play file on recorder?") },
            text = { Text("Send FilePlay 0x01FC for index $target (${file?.filename ?: "unknown"}). " +
                "Remote response/route is experimental. This changes the selected playback clip.") },
            confirmButton = { TextButton(onClick = {
                playConfirm = null
                actionForIndex(target)
            }) { Text("PLAY FILE") } },
            dismissButton = { TextButton(onClick = { playConfirm = null }) { Text("CANCEL") } })
    }
}
