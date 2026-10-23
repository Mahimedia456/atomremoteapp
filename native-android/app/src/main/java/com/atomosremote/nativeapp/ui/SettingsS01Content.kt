package com.atomosremote.nativeapp.ui

// SETTINGS-S01: isolated visual-only Settings UI.
// Critical safety guarantee: NO GATT, PacketLink, backend, Audio, or recorder SET calls.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atomosremote.nativeapp.BuildConfig
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private val SettingsBg = Color(0xFF0D1215)
private val SettingsMuted = Color(0xFF93A1A5)
private val SettingsGreen = Color(0xFF719579)
private val SettingsCyan = Color(0xFF19E7D3)

private enum class SettingsS01Tab(val title: String) {
    TIME_DATE("Time & Date"), TIMECODE("Timecode"), INFO("Info")
}

@Composable
internal fun SettingsS01Content(
    recorderName: String,
    modelCode: String,
    bleAddress: String?,
    gattState: String,
    remoteReady: Boolean
) {
    var tab by remember { mutableStateOf(SettingsS01Tab.TIME_DATE) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(Color(0xFF262D31), RoundedCornerShape(13.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            SettingsS01Tab.values().forEach { item ->
                val selected = tab == item
                Box(
                    modifier = Modifier.weight(1f)
                        .background(
                            if (selected) Color(0xFF42484D) else Color.Transparent,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { tab = item }
                        .padding(vertical = 13.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.title,
                        color = if (selected) Color.White else Color(0xFFC4C9CB),
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        when (tab) {
            SettingsS01Tab.TIME_DATE -> SettingsS01DateTime()
            SettingsS01Tab.TIMECODE -> SettingsS01Timecode()
            SettingsS01Tab.INFO -> SettingsS01Info(recorderName, modelCode, bleAddress, gattState, remoteReady)
        }
        Text(
            "Settings S01 · Visual layout only · No recorder settings are changed",
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            color = Color(0xFF66777C),
            textAlign = TextAlign.Center,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun SettingsS01DateTime() {
    // Phone date/time are shown ONLY as a clearly identified unsent draft.
    var date by remember { mutableStateOf(LocalDate.now()) }
    val phoneTime = remember { LocalTime.now() }
    var hour by remember { mutableIntStateOf(phoneTime.hour) }
    var minute by remember { mutableIntStateOf(phoneTime.minute) }
    var seconds by remember { mutableIntStateOf(phoneTime.second) }
    val daysInMonth = YearMonth.of(date.year, date.monthValue).lengthOfMonth()

    SettingsS01Status("RECORDER DATE / TIME", "Not reported · readback not wired")
    SettingsS01Card {
        Text("Date", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text("Phone local draft, not the recorder clock", color = SettingsMuted, fontSize = 10.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsS01Wheel(
                modifier = Modifier.weight(0.8f),
                title = "Day", selected = date.dayOfMonth.toString(),
                previous = if (date.dayOfMonth > 1) (date.dayOfMonth - 1).toString() else "",
                next = if (date.dayOfMonth < daysInMonth) (date.dayOfMonth + 1).toString() else "",
                onPrevious = { if (date.dayOfMonth > 1) date = date.minusDays(1) },
                onNext = { if (date.dayOfMonth < daysInMonth) date = date.plusDays(1) }
            )
            SettingsS01Wheel(
                modifier = Modifier.weight(1.55f),
                title = "Month", selected = date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                previous = date.minusMonths(1).month.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                next = date.plusMonths(1).month.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                onPrevious = {
                    val nextMonth = date.minusMonths(1)
                    if (nextMonth.year >= 2000) date = nextMonth
                },
                onNext = {
                    val nextMonth = date.plusMonths(1)
                    if (nextMonth.year <= 2100) date = nextMonth
                }
            )
            SettingsS01Wheel(
                modifier = Modifier.weight(1.02f),
                title = "Year", selected = date.year.toString(),
                previous = if (date.year > 2000) (date.year - 1).toString() else "",
                next = if (date.year < 2100) (date.year + 1).toString() else "",
                onPrevious = { if (date.year > 2000) date = date.minusYears(1) },
                onNext = { if (date.year < 2100) date = date.plusYears(1) }
            )
        }
        Spacer(Modifier.height(4.dp))
        Text("Time", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsS01Wheel(
                modifier = Modifier.weight(1f),
                title = "Hour", selected = ((hour + 11) % 12 + 1).toString().padStart(2, '0'),
                previous = (((hour + 23) % 24 + 11) % 12 + 1).toString().padStart(2, '0'),
                next = (((hour + 1) % 24 + 11) % 12 + 1).toString().padStart(2, '0'),
                onPrevious = { hour = (hour + 23) % 24 },
                onNext = { hour = (hour + 1) % 24 }
            )
            SettingsS01Wheel(
                modifier = Modifier.weight(1f),
                title = "Minute", selected = minute.toString().padStart(2, '0'),
                previous = ((minute + 59) % 60).toString().padStart(2, '0'),
                next = ((minute + 1) % 60).toString().padStart(2, '0'),
                onPrevious = { minute = (minute + 59) % 60 },
                onNext = { minute = (minute + 1) % 60 }
            )
            SettingsS01Wheel(
                modifier = Modifier.weight(1f),
                title = "Period", selected = if (hour < 12) "AM" else "PM",
                previous = if (hour < 12) "PM" else "AM",
                next = if (hour < 12) "PM" else "AM",
                onPrevious = { hour = (hour + 12) % 24 },
                onNext = { hour = (hour + 12) % 24 }
            )
        }
        SettingsS01Stepper("Seconds", seconds, { seconds = (seconds + 59) % 60 }, { seconds = (seconds + 1) % 60 })
    }
    SettingsS01DisabledAction("Set time & date", "Requires verified recorder DateSet protocol")
    SettingsS01DisabledAction("Set to current time & date", "Requires verified recorder DateSet protocol")
}

@Composable
private fun SettingsS01Timecode() {
    var hour by remember { mutableIntStateOf(0) }
    var minute by remember { mutableIntStateOf(0) }
    var second by remember { mutableIntStateOf(0) }

    SettingsS01Card {
        Text(
            "TC: --:--:--:--",
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            color = Color.White, textAlign = TextAlign.Center,
            fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp
        )
        Text(
            "Live recorder timecode not reported",
            color = SettingsMuted, modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center, fontSize = 10.sp
        )
    }
    SettingsS01Card {
        SettingsS01InfoRow("Source", "Not reported")
        SettingsS01InfoRow("Auto restart", "Not available")
        SettingsS01InfoRow("Use dropframe", "Not reported")
        Text("Manual timecode draft (not sent)", color = SettingsMuted, fontSize = 10.sp)
        SettingsS01Stepper("Hour", hour, { hour = (hour + 23) % 24 }, { hour = (hour + 1) % 24 })
        SettingsS01Stepper("Minute", minute, { minute = (minute + 59) % 60 }, { minute = (minute + 1) % 60 })
        SettingsS01Stepper("Second", second, { second = (second + 59) % 60 }, { second = (second + 1) % 60 })
        Text(
            "Source list, drop-frame and frame offset require hardware GET evidence. Manual controls only edit this local draft.",
            color = SettingsMuted, fontSize = 10.sp, lineHeight = 15.sp
        )
    }
    SettingsS01DisabledAction("Set timecode", "Requires verified recorder TimecodeSet protocol")
}

@Composable
private fun SettingsS01Info(
    recorderName: String,
    modelCode: String,
    bleAddress: String?,
    gattState: String,
    remoteReady: Boolean
) {
    SettingsS01Card {
        SettingsS01InfoRow("App version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }
    SettingsS01Card {
        SettingsS01InfoRow("Device name", recorderName)
        SettingsS01InfoRow("Model code", modelCode)
        SettingsS01InfoRow("Firmware version", "Not reported")
        SettingsS01InfoRow("Device ID", "Not reported")
        SettingsS01InfoRow("BLE address", bleAddress ?: "Not connected")
        SettingsS01InfoRow("BLE state", gattState.ifBlank { "Not reported" })
        SettingsS01InfoRow("Remote link", if (remoteReady) "Ready" else "Not ready")
        Text(
            "Firmware and Device ID will display only after verified recorder readback. BLE address is not Device ID.",
            color = SettingsMuted, fontSize = 10.sp, lineHeight = 15.sp
        )
    }
    SettingsS01DisabledAction(
        "Factory reset",
        "Disabled to protect recorder settings and stored device configuration",
        destructive = true
    )
}

@Composable
private fun SettingsS01Status(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF10232A),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = SettingsCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, color = SettingsMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SettingsS01Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SettingsBg,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF28373B))
    ) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun SettingsS01Wheel(
    modifier: Modifier,
    title: String,
    selected: String,
    previous: String,
    next: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = SettingsMuted, fontSize = 9.sp)
        Box(
            modifier = Modifier.fillMaxWidth().height(34.dp).clickable(enabled = previous.isNotBlank(), onClick = onPrevious),
            contentAlignment = Alignment.Center
        ) { Text(previous, color = Color(0xFF566064), fontSize = 15.sp, maxLines = 1) }
        Box(
            modifier = Modifier.fillMaxWidth().height(50.dp)
                .background(Color(0xFF34383E), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(selected, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(34.dp).clickable(enabled = next.isNotBlank(), onClick = onNext),
            contentAlignment = Alignment.Center
        ) { Text(next, color = Color(0xFF566064), fontSize = 15.sp, maxLines = 1) }
    }
}

@Composable
private fun SettingsS01Stepper(label: String, number: Int, previous: () -> Unit, next: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), color = Color.White, fontSize = 13.sp)
        Box(
            modifier = Modifier.width(58.dp).height(39.dp)
                .background(Color(0xFF44494E), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(number.toString().padStart(2, '0'), color = Color.White, fontSize = 17.sp)
        }
        SettingsS01StepButton("−", previous)
        SettingsS01StepButton("+", next)
    }
}

@Composable
private fun SettingsS01StepButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(width = 43.dp, height = 39.dp)
            .background(Color(0xFF51565B), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontSize = 23.sp) }
}

@Composable
private fun SettingsS01InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, modifier = Modifier.weight(0.43f), color = Color(0xFFC9D0D3), fontSize = 12.sp)
        Text(value, modifier = Modifier.weight(0.57f), color = Color(0xFF9FAAAE), fontSize = 12.sp)
    }
}

@Composable
private fun SettingsS01DisabledAction(label: String, explanation: String, destructive: Boolean = false) {
    val actionColor = if (destructive) Color(0xFF9E474B) else SettingsGreen
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth().height(52.dp)
                .background(actionColor.copy(alpha = 0.48f), RoundedCornerShape(13.dp))
                .border(1.dp, actionColor.copy(alpha = 0.5f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("$label · Locked", color = Color(0xFFC3CBC8), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(explanation, color = SettingsMuted, fontSize = 10.sp, lineHeight = 14.sp)
    }
}
