package com.atomosremote.nativeapp.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Settings S02 is a LOCAL-DRAFT, UI-ONLY component.
// NO recorder SET, GATT, PacketLink, date, timecode, reboot or reset writes.
private val S02Cyan = Color(0xFF19E7D3)
private val S02Bg = Color(0xFF071216)
private val S02Row = Color(0xFF09171B)
private val S02Muted = Color(0xFF81969B)
private val S02Border = Color(0xFF1A353C)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsS02Panel(
    recorderName: String,
    modelCode: String,
    bleAddress: String,
    bleState: String,
    linked: Boolean,
    remotePhase: String
) {
    val ctx = LocalContext.current
    val appVersion = remember(ctx.packageName) {
        try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${info.versionName ?: "Unknown"} (${if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()})"
        } catch (_: Exception) { "Not available" }
    }
    val initial = remember { LocalDateTime.now() }
    var draftDate by remember { mutableStateOf(initial.toLocalDate()) }
    var draftHour by remember { mutableIntStateOf(initial.hour) }
    var draftMinute by remember { mutableIntStateOf(initial.minute) }
    var draftSecond by remember { mutableIntStateOf(initial.second) }
    var use24h by remember { mutableStateOf(true) }
    var tcHour by remember { mutableIntStateOf(0) }
    var tcMinute by remember { mutableIntStateOf(0) }
    var tcSecond by remember { mutableIntStateOf(0) }
    var tcFrame by remember { mutableIntStateOf(0) }
    var tcOffset by remember { mutableIntStateOf(0) }
    var draftDrop by remember { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf<String?>(null) }
    val sheetKey = activeSheet
    val formattedDraftTime = if (use24h) {
        String.format(java.util.Locale.US, "%02d:%02d:%02d", draftHour, draftMinute, draftSecond)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d:%02d %s", (draftHour + 11) % 12 + 1, draftMinute, draftSecond, if (draftHour < 12) "AM" else "PM")
    }

    S02Group("TIME & DATE", "Select a setting to edit a local draft") {
        S02RowItem("Recorder date", "Not reported", "Draft: ${draftDate.format(DateTimeFormatter.ofPattern("dd MMM yyyy"))}") { activeSheet = "date" }
        S02RowItem("Recorder time", "Not reported", "Draft: $formattedDraftTime") { activeSheet = "time" }
        S02RowItem("Clock format", "Local preview", if (use24h) "24-hour display" else "12-hour display") { activeSheet = "clock" }
        S02RowItem("Set to phone time", "Local draft only", "No recorder command will be sent") { activeSheet = "now" }
    }

    S02Group("TIMECODE", "Recorder timecode readback and setters are not yet wired") {
        S02RowItem("Current timecode", "Not reported", "Draft: ${s02Tc(tcHour, tcMinute, tcSecond, tcFrame)}") { activeSheet = "tc" }
        S02RowItem("Source", "Not reported", "Source list requires recorder GET") { activeSheet = "source" }
        S02RowItem("Use dropframe", "Not confirmed", "Draft: ${if (draftDrop) "On" else "Off"} - recorder support unknown") { activeSheet = "drop" }
        S02RowItem("Frame offset", "Not reported", "Draft: $tcOffset frames") { activeSheet = "offset" }
    }

    S02Group("INFO", "Recorder and application details") {
        S02RowItem("Recorder", recorderName, if (linked) "Connected" else "Not ready") { activeSheet = "recorder" }
        S02RowItem("Model", modelCode, "Recorder identity") { activeSheet = "recorder" }
        S02RowItem("Firmware", "Not reported", "Waiting for verified DeviceInfo readback") { activeSheet = "device" }
        S02RowItem("Device ID", "Not reported", "Not inferred from BLE address") { activeSheet = "device" }
        S02RowItem("App version", appVersion, "Atom Remote for Android") { activeSheet = "app" }
        S02RowItem("Factory reset", "LOCKED", "Recorder destructive action disabled", danger = true, onClick = { activeSheet = "reset" })
    }

    if (sheetKey != null) {
        ModalBottomSheet(
            onDismissRequest = { activeSheet = null },
            containerColor = Color(0xFF071311),
            contentColor = Color.White,
            dragHandle = {
                Box(
                    Modifier.padding(top = 9.dp, bottom = 10.dp)
                        .size(width = 55.dp, height = 5.dp)
                        .background(Color(0xFF60777D), RoundedCornerShape(99.dp))
                )
            }
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 690.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 17.dp, end = 17.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (sheetKey) {
                            "date" -> "Date"; "time" -> "Time"; "clock" -> "Clock format"
                            "now" -> "Use phone time"; "tc" -> "Timecode"
                            "source" -> "Timecode source"; "drop" -> "Dropframe"
                            "offset" -> "Timecode offset"; "recorder" -> "Recorder"
                            "device" -> "Device information"; "app" -> "Application"
                            else -> "Factory reset"
                        },
                        modifier = Modifier.weight(1f), color = Color.White,
                        fontWeight = FontWeight.Bold, fontSize = 19.sp
                    )
                    TextButton(onClick = { activeSheet = null }) {
                        Text("CLOSE", color = S02Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                S02Notice("Local UI draft only. Recorder SET commands are not enabled in this Settings phase.")
                when (sheetKey) {
                    "date" -> {
                        val dateMillis = remember(draftDate) {
                            draftDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                        }
                        val picker = rememberDatePickerState(initialSelectedDateMillis = dateMillis)
                        DatePicker(
                            state = picker,
                            colors = DatePickerDefaults.colors(containerColor = Color(0xFF071311)),
                            modifier = Modifier.fillMaxWidth()
                        )
                        S02AccentButton("USE SELECTED DATE") {
                            val millis = picker.selectedDateMillis
                            if (millis != null) draftDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            activeSheet = null
                        }
                    }
                    "time" -> {
                        S02Stepper("Hour", draftHour, "00..23", { draftHour = (draftHour + 23) % 24 }, { draftHour = (draftHour + 1) % 24 })
                        S02Stepper("Minute", draftMinute, "00..59", { draftMinute = (draftMinute + 59) % 60 }, { draftMinute = (draftMinute + 1) % 60 })
                        S02Stepper("Second", draftSecond, "00..59", { draftSecond = (draftSecond + 59) % 60 }, { draftSecond = (draftSecond + 1) % 60 })
                    }
                    "clock" -> {
                        S02Toggle("24-hour display", use24h) { use24h = it }
                        Text("This changes the draft display format only.", color = S02Muted, fontSize = 11.sp)
                    }
                    "now" -> {
                        Text("Copy phone date and time to the local editor. This does not set the recorder clock.", color = S02Muted, fontSize = 12.sp)
                        S02AccentButton("COPY PHONE TIME TO DRAFT") {
                            val now = LocalDateTime.now()
                            draftDate = now.toLocalDate()
                            draftHour = now.hour
                            draftMinute = now.minute
                            draftSecond = now.second
                            activeSheet = null
                        }
                    }
                    "tc" -> {
                        S02Stepper("Hours", tcHour, "00..23", { tcHour = (tcHour + 23) % 24 }, { tcHour = (tcHour + 1) % 24 })
                        S02Stepper("Minutes", tcMinute, "00..59", { tcMinute = (tcMinute + 59) % 60 }, { tcMinute = (tcMinute + 1) % 60 })
                        S02Stepper("Seconds", tcSecond, "00..59", { tcSecond = (tcSecond + 59) % 60 }, { tcSecond = (tcSecond + 1) % 60 })
                        S02Stepper("Frames", tcFrame, "Draft: 00..29", { tcFrame = (tcFrame + 29) % 30 }, { tcFrame = (tcFrame + 1) % 30 })
                        S02Notice("Frame range is a draft editor limit, not a verified recorder frame-rate capability.")
                    }
                    "source" -> {
                        S02Info("Selected source", "Not reported")
                        S02Notice("Source options must come from Timecode.SourceListGet. No guessed recorder choices are exposed.")
                    }
                    "drop" -> {
                        S02Toggle("Draft dropframe", draftDrop) { draftDrop = it }
                        S02Notice("Dropframe support depends on recorder frame rate and source; this toggle has no hardware effect.")
                    }
                    "offset" -> {
                        S02Stepper("Draft frame offset", tcOffset, "Local only", { tcOffset = (tcOffset - 1).coerceAtLeast(-999) }, { tcOffset = (tcOffset + 1).coerceAtMost(999) })
                    }
                    "recorder" -> {
                        S02Info("Recorder", recorderName)
                        S02Info("Model", modelCode)
                        S02Info("BLE address", bleAddress)
                        S02Info("BLE connection", bleState)
                        S02Info("Remote link", if (linked) "Ready" else remotePhase.ifBlank { "Not ready" })
                    }
                    "device" -> {
                        S02Info("Firmware", "Not reported")
                        S02Info("Device ID", "Not reported")
                        S02Notice("These fields will show verified recorder readback, not historical reference-app values.")
                    }
                    "app" -> {
                        S02Info("Application", "Atom Remote")
                        S02Info("Version", appVersion)
                    }
                    "reset" -> {
                        Text("Factory reset is disabled. No reset command is wired or sent.", color = Color(0xFFFF7F7F), fontSize = 12.sp)
                    }
                }
                Divider(color = S02Border)
                TextButton(onClick = { activeSheet = null }, modifier = Modifier.fillMaxWidth()) {
                    Text("DONE", color = S02Cyan, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun s02Tc(h: Int, m: Int, s: Int, f: Int): String =
    String.format(java.util.Locale.US, "%02d:%02d:%02d:%02d", h, m, s, f)

@Composable
private fun S02Group(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = S02Bg),
        border = BorderStroke(1.dp, S02Border)
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(22.dp).background(S02Cyan, RoundedCornerShape(99.dp)))
                Spacer(Modifier.width(9.dp))
                Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            }
            Text(subtitle, color = S02Muted, fontSize = 10.sp)
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

@Composable
private fun S02RowItem(title: String, value: String, detail: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(S02Row, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = if (danger) Color(0xFFFF8585) else Color(0xFFE9F2F3), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = S02Muted, fontSize = 9.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(value, color = if (danger) Color(0xFFFF8585) else S02Cyan, fontSize = 10.sp,
            fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.width(90.dp))
        Text("›", color = S02Cyan, fontSize = 21.sp)
    }
}

@Composable
private fun S02Notice(value: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF0B2528), border = BorderStroke(1.dp, S02Border)) {
        Text(value, modifier = Modifier.fillMaxWidth().padding(11.dp), color = Color(0xFF9DBBC0), fontSize = 11.sp)
    }
}

@Composable
private fun S02Info(title: String, value: String) {
    Row(Modifier.fillMaxWidth().background(S02Row, RoundedCornerShape(10.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = S02Muted, fontSize = 11.sp, modifier = Modifier.weight(0.43f))
        Text(value, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(0.57f))
    }
}

@Composable
private fun S02Toggle(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(S02Row, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = Color.White, fontSize = 12.sp)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun S02Stepper(label: String, value: Int, detail: String, minus: () -> Unit, plus: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(S02Row, RoundedCornerShape(10.dp)).padding(11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = S02Muted, fontSize = 9.sp)
        }
        TextButton(onClick = minus) { Text("−", color = S02Cyan, fontSize = 22.sp) }
        Text(value.toString().padStart(2, '0'), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        TextButton(onClick = plus) { Text("+", color = S02Cyan, fontSize = 22.sp) }
    }
}

@Composable
private fun S02AccentButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = S02Cyan, contentColor = Color(0xFF062123))) {
        Text(label, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
    }
}
