package com.atomosremote.nativeapp.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
 * M05-M07 READ-ONLY media screens. The section buttons are local navigation only.
 * No Media GET is sent until request/reply/payload proof unlocks M04 gate.
 * No playback mode switch, clip playback, file writes, format or recovery actions.
 */
@Composable
fun MediaPhaseM02Screen() {
    var page by rememberSaveable { mutableStateOf("drives") }
    // No hardware adapter has been activated: unknown is the only honest initial state.
    val readback = remember { MediaM05M07Snapshot() }
    val cyan = Color(0xFF42C7DD)

    MediaM57Section("Media", "M05-M07: drive information, browser metadata, naming and recovery readbacks") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("drives" to "DRIVES", "browser" to "FILES", "naming" to "NAMING").forEach { (key, label) ->
                OutlinedButton(
                    onClick = { page = key }, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                    border = BorderStroke(1.dp, if (page == key) cyan else Color(0xFF35474A)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (page == key) Color(0xFF14313A) else Color.Transparent,
                        contentColor = if (page == key) cyan else Color(0xFFB2C1C5))) {
                    Text(label, fontSize = 10.sp)
                }
            }
        }
        Text("Read-only UI. No AG2 Media GET protocol has been approved; no values are assumed.",
            color = Color(0xFFA5B5B9), fontSize = 10.sp)
    }

    when (page) {
        "drives" -> {
            MediaM57Section("Recording Media", "Only recorder-confirmed values will be shown") {
                MediaM57Row("Media state", readback.mediaState.display())
                MediaM57Row("Total capacity", MediaM05M07Format.bytes(readback.capacityBytes))
                MediaM57Row("Free space", MediaM05M07Format.bytes(readback.availableBytes))
                MediaM57Row("Record time remaining", MediaM05M07Format.time(readback.remainingSeconds))
                MediaM57Row("Media change ID", readback.changeId.display())
            }
            MediaM57Section("Media Drives", "Only physically reported drives; empty response is not the same as no drives") {
                if (!readback.drivesVerified) {
                    MediaM57Info("Drive enumeration not reported. No inserted/empty state inferred.")
                } else if (readback.drives.isEmpty()) {
                    MediaM57Info("Recorder-confirmed list contains no drives")
                } else readback.drives.forEachIndexed { index, drive ->
                    MediaM57Row("Drive ${index + 1}", drive.slot.display())
                    MediaM57Row("Inserted", MediaM05M07Format.inserted(drive.inserted))
                    MediaM57Row("Media", drive.mediaName.display())
                    MediaM57Row("Capacity", MediaM05M07Format.bytes(drive.totalBytes))
                    MediaM57Row("Free", MediaM05M07Format.bytes(drive.freeBytes))
                    MediaM57Row("Remaining", MediaM05M07Format.time(drive.estimatedSeconds))
                    if (index != readback.drives.lastIndex) HorizontalDivider(color = Color(0xFF1F353C))
                }
            }
        }
        "browser" -> {
            val browser = readback.browser
            MediaM57Section("File Browser", "M06: directory and clip metadata (not playback control)") {
                MediaM57Row("Directory", browser.currentDirectory.display())
                MediaM57Row("Directory info", browser.directoryInfo.display())
                MediaM57Row("File count", MediaM05M07Format.count(browser.fileCount))
                MediaM57Row("Current index", MediaM05M07Format.count(browser.currentIndex))
                if (!browser.listVerified) MediaM57Info("No verified FileListGet response. Folder/clip listing unavailable.")
                else {
                    if (browser.folders.isEmpty() && browser.clips.isEmpty()) MediaM57Info("Verified folder and clip list is empty")
                    browser.folders.forEach { MediaM57Row("Folder", it.display()) }
                    browser.clips.forEach { clip ->
                        MediaM57Row("Clip", clip.filename.display())
                        MediaM57Row("Path", clip.folder.display())
                        MediaM57Row("Size", MediaM05M07Format.bytes(clip.sizeBytes))
                        MediaM57Row("Duration", MediaM05M07Format.time(clip.durationSeconds))
                        MediaM57Row("Codec", clip.codec.display())
                        MediaM57Row("Resolution", clip.resolution.display())
                        MediaM57Row("Frame rate", clip.frameRate.display())
                        MediaM57Row("Timecode", clip.timecode.display())
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("OPEN FOLDER", fontSize = 10.sp) }
                    OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("PARENT", fontSize = 10.sp) }
                }
                MediaM57Info("Folder navigation and Play/Pause will be enabled in later Playback phases only after verified mode/file commands.")
            }
        }
        "naming" -> {
            val naming = readback.naming
            MediaM57Section("File Naming", "M07: GET values only; no filename SET or Reset") {
                MediaM57Row("Filename", naming.filename.display())
                MediaM57Row("Filename example", naming.filenameExample.display())
                MediaM57Row("Filepath source", naming.filepathSource.display())
                if (!naming.optionsVerified) MediaM57Info("Filepath source options not reported")
                else naming.filepathSourceOptions.forEach { MediaM57Row("Option", it.display()) }
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("EDIT NAMING (LOCKED)", fontSize = 10.sp)
                }
            }
            MediaM57Section("Recovery", "Readback only; no recovery/start/delete writes") {
                MediaM57Row("Recovery state", naming.recoveryState.display())
                MediaM57Row("Last recording", naming.lastRecordingFilename.display())
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("RECOVER FILE (LOCKED)", fontSize = 10.sp)
                }
            }
        }
    }

    MediaM57Section("Media Safety", "M05-M07 protocol capture required before live readbacks") {
        MediaM57Row("Validated AG2 Media GETs", "${MediaM04ProofGate.approvedCount()} approved")
        MediaM57Row("Media GET transport", "Locked")
        MediaM57Row("Recorder mode / playback", "Locked")
        MediaM57Row("Media SET / format", "Locked")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("FORMAT", fontSize = 10.sp) }
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("SECURE FORMAT", fontSize = 10.sp) }
        }
        MediaM57Info("Not reported = unknown. It never means 0, Off, no drive, or no clips.")
    }
}

@Composable
private fun MediaM57Section(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF071216)),
        border = BorderStroke(1.dp, Color(0xFF1A353C))
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFF879FA5), fontSize = 10.sp)
            HorizontalDivider(color = Color(0xFF1A353C))
            content()
        }
    }
}

@Composable
private fun MediaM57Row(name: String, value: String) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF0A1A1E), RoundedCornerShape(9.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = Color(0xFF92A7AC), modifier = Modifier.weight(1f), fontSize = 10.sp)
        Text(value, color = Color(0xFFD0DEE1), modifier = Modifier.weight(1f), fontSize = 10.sp,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MediaM57Info(text: String) {
    Text(text, color = Color(0xFF849CA2), fontSize = 10.sp, lineHeight = 14.sp)
}
