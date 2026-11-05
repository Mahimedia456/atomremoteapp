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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

// Settings S02 is readback-first. B47.69 supports a guarded, source-5-only
// integer AirGlu Offset 0..20 experiment with required post-SET GET and physical
// confirmation. Other writes (Date, zone, factory reset) remain locked.
// Neither SwiftProtobuf zero serialization nor positive >2 is hardware proven.
private val S02Cyan = Color(0xFF19E7D3)
private val S02Bg = Color(0xFF071216)
private val S02Row = Color(0xFF09171B)
private val S02Muted = Color(0xFF81969B)
private val S02Border = Color(0xFF1A353C)

// B32: Camera-origin timestamp projected into a USER-SELECTED reference zone.
// This is never a selected-timezone GET. No phone-clock substitute, guessed TX or SET.
private fun b32ReferenceTime(dateGet: String?, referenceId: Int?, formatId: Int?): String? {
    if (dateGet.isNullOrBlank() || referenceId == null) return null
    val zoneName = B31FirmwareZoneCandidates.getOrNull(referenceId) ?: return null
    return try {
        // B21 hardware RX consistently followed UTC-like device DateGet timestamps.
        // This interpretation is preliminary until selected-zone GET is independently proven.
        val raw = LocalDateTime.parse(
            dateGet.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        )
        val zone = java.time.ZoneId.of(zoneName)
        val zoned = raw.toInstant(ZoneOffset.UTC).atZone(zone)
        val clockFormat = if (formatId == 1) "hh:mm:ss a" else "HH:mm:ss"
        zoned.format(DateTimeFormatter.ofPattern(clockFormat, java.util.Locale.US))
    } catch (_: Exception) { null }
}


// B47.62: DateGet is a UTC-LIKE instant, not the selected recorder-zone GET.
// Project it only into an explicitly labelled PHONE/MANUAL reference zone.
// Never substitute the phone clock for recorder data or claim device selected TZ.
private fun b4762ProjectReferenceClock(
    rawGet: String?, referenceZone: String, formatId: Int?, elapsedMs: Long?
): String? {
    if (rawGet.isNullOrBlank() || elapsedMs == null || elapsedMs !in 0L..600000L) return null
    return try {
        val parser = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", java.util.Locale.US)
            .withResolverStyle(java.time.format.ResolverStyle.STRICT)
        val raw = LocalDateTime.parse(rawGet.trim(), parser)
        val instant = raw.toInstant(ZoneOffset.UTC).plusMillis(elapsedMs)
        val pattern = if (formatId == 1) "uuuu-MM-dd hh:mm:ss a" else "uuuu-MM-dd HH:mm:ss"
        instant.atZone(java.time.ZoneId.of(referenceZone))
            .format(DateTimeFormatter.ofPattern(pattern, java.util.Locale.US))
    } catch (_: Exception) { null }
}

// B35: independent, read-only GET-derived editor seed. Never infer live recorder zone.
private fun b35DateTimeFromGet(rawGet: String?, manualZoneId: Int?): LocalDateTime? {
    if (rawGet.isNullOrBlank()) return null
    return try {
        val raw = LocalDateTime.parse(rawGet.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", java.util.Locale.US))
        if (manualZoneId == null) raw
        else {
            val zoneName = B31FirmwareZoneCandidates.getOrNull(manualZoneId) ?: return null
            raw.toInstant(ZoneOffset.UTC).atZone(java.time.ZoneId.of(zoneName)).toLocalDateTime()
        }
    } catch (_: Exception) { null }
}

// B31: Shogun Ultra AtomOS 11.19.0 internal enumeration from B30 dual switch tables.
// This is NOT a live menu readback and these indices are NOT verified transport IDs.
// Keep all recorder timezone GET/SET disabled until independent typed RX and A/B proof.
private val B31FirmwareZoneCandidates = listOf(
    "Australia/Sydney",
    "Australia/Brisbane",
    "Australia/Adelaide",
    "Australia/Darwin",
    "Australia/Perth",
    "ROK",
    "Asia/Tokyo",
    "PRC",
    "Asia/Hong_Kong",
    "Asia/Bangkok",
    "Asia/Dhaka",
    "Asia/Karachi",
    "Asia/Dubai",
    "Africa/Nairobi",
    "Europe/Moscow",
    "Africa/Cairo",
    "Africa/Ndjamena",
    "CET",
    "Europe/Paris",
    "WET",
    "Europe/London",
    "Greenwich",
    "Universal",
    "America/Sao_Paulo",
    "America/Caracas",
    "America/New_York",
    "America/Chicago",
    "America/Denver",
    "America/Los_Angeles",
    "America/Anchorage",
    "Pacific/Honolulu",
    "NZ"
)

// B45.1: Clock comparison is only a candidate discovery tool.
// The recorder gives a UTC-like wire timestamp but not its selected zone.
// Never infer a unique Time Zone from an offset: many regions share UTC offsets,
// DST and recorder-clock skew may invalidate the comparison.
// B45.2: In a zone ambiguity, present both official civil offset and hypothetical
// firmware fixed-standard-offset candidates. This NEVER proves selected recorder zone.
private data class B451ZoneCandidate(
    val name: String, val offset: String, val minuteError: Int,
    val interpretation: String, val actualCivilOffset: String
)

private fun b451FindZoneCandidates(
    dateGet: String?, physicalClock: String, recorderFormat: Int?, amPm: String, ageSeconds: Long
): List<B451ZoneCandidate> {
    if (dateGet.isNullOrBlank() || recorderFormat !in 0..1 || ageSeconds !in 0L..90L) return emptyList()
    val match = Regex("^([0-2][0-9]):([0-5][0-9])$").matchEntire(physicalClock.trim()) ?: return emptyList()
    val h = match.groupValues[1].toIntOrNull() ?: return emptyList()
    val minute = match.groupValues[2].toIntOrNull() ?: return emptyList()
    val hour = if (recorderFormat == 1) {
        if (h !in 1..12 || amPm !in listOf("AM", "PM")) return emptyList()
        (h % 12) + (if (amPm == "PM") 12 else 0)
    } else {
        if (h > 23) return emptyList()
        h
    }
    val instant = try {
        LocalDateTime.parse(dateGet.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", java.util.Locale.US))
            .toInstant(ZoneOffset.UTC).plusSeconds(ageSeconds) // Monotonic elapsed time since actual Date RX, not phone timezone.
    } catch (_: Exception) { return emptyList() }
    val expected = hour * 60 + minute
    fun errorFor(offset: ZoneOffset): Int {
        val time = instant.atOffset(offset).toLocalTime()
        val actual = time.hour * 60 + time.minute
        val distance = kotlin.math.abs(actual - expected)
        return minOf(distance, 1440 - distance)
    }
    return B31FirmwareZoneCandidates.flatMap { name ->
        try {
            val rules = java.time.ZoneId.of(name).rules
            val civil = rules.getOffset(instant)
            val standard = rules.getStandardOffset(instant)
            val out = mutableListOf<B451ZoneCandidate>()
            val civilError = errorFor(civil)
            if (civilError <= 2) out += B451ZoneCandidate(name, civil.id, civilError, "IANA_CIVIL", civil.id)
            // Hypothesis only: some recorder firmware may label Sydney but display UTC+10
            // by ignoring DST. Never confuse this with an actual zone GET.
            if (standard != civil) {
                val standardError = errorFor(standard)
                if (standardError <= 2) out += B451ZoneCandidate(
                    name, standard.id, standardError, "STANDARD_ONLY_DST_UNVERIFIED", civil.id)
            }
            out
        } catch (_: Exception) { emptyList() }
    }.sortedWith(compareBy<B451ZoneCandidate>({it.minuteError}, {it.interpretation != "IANA_CIVIL"}, {it.name}))
}

// B47.1: physical A/B verified 0x844D field1: 0 Record Run, 2 Time of Day,
// 4 HDMI, 5 AirGlu SYNC. ID 1 remains unnamed. Source GET is read-only.
private fun b452SelectedSourceEvidence(evidence: String): String {
    val m = Regex("raw=([0-9A-Fa-f]{16})").find(evidence) ?: return "No correlated UI readback; inspect raw 0x844D log, not a source lock"
    val bytes = m.groupValues[1].uppercase()
    if (!Regex("08[0-9A-F]{2}10[0-9A-F]{2}18[0-9A-F]{2}20[0-9A-F]{2}").matches(bytes))
        return "Unexpected 0x844D fields; raw=$bytes"
    val id = bytes.substring(2, 4).toInt(16)
    val source = when(id) {
        0 -> "Record Run (physical A/B)"
        2 -> "Time of Day (physical A/B)"
        4 -> "HDMI (physical A/B)"
        5 -> "AirGlu SYNC (physical A/B)"
        1 -> "Auto Restart (operator confirmed)"
        else -> "ID $id (unmapped)"
    }
    return "f1=$id | $source | raw=$bytes | no SET"
}

// B47.14: source is never guessed from old event, list GET, or stale state.
// Exact protobuf fast path is grounded by physical 0x844D/0x844E A/B tests.
private fun b4714SourceName(id: Int): String = when (id) {
    0 -> "Record Run"; 1 -> "Auto Restart"; 2 -> "Time of Day"
    4 -> "HDMI"; 5 -> "AirGlu SYNC"; else -> "Unmapped ID $id"
}
private fun b4714SelectedId(evidence: String): Int? {
    val hex = Regex("raw=([0-9A-Fa-f]{16})(?![0-9A-Fa-f])").find(evidence)?.groupValues?.getOrNull(1)
        ?: return null
    if (!Regex("08[0-9A-Fa-f]{2}10[0-9A-Fa-f]{2}18[0-9A-Fa-f]{2}20[0-9A-Fa-f]{2}").matches(hex)) return null
    return hex.substring(2, 4).toIntOrNull(16)
}
// B47.15: parse only observed GET wire responses. Strict protobuf varints,
// avoid assuming f1-f5 are HH:MM:SS:FF. Supports multi-byte fields.
private fun b4715DecodeWire(evidence: String, expectedType: String): Map<Int, Long>? {
    if (!evidence.contains(expectedType, ignoreCase = true)) return null
    val hex = Regex("raw=([0-9A-Fa-f]+)(?![0-9A-Fa-f])").find(evidence)?.groupValues?.getOrNull(1)
        ?: return null
    if (hex.length % 2 != 0 || hex.length > 256) return null
    val bytes = hex.chunked(2).map { it.toIntOrNull(16) ?: return null }
    val values = linkedMapOf<Int, Long>()
    var p = 0
    fun nextVarint(): Long? {
        var value = 0L
        var shift = 0
        for (i in 0..9) {
            if (p >= bytes.size) return null
            val unit = bytes[p++]
            if (i == 9 && unit > 1) return null
            value = value or ((unit and 0x7f).toLong() shl shift)
            if (unit and 0x80 == 0) return value
            shift += 7
        }
        return null
    }
    while (p < bytes.size) {
        val tag = nextVarint() ?: return null
        val field = (tag ushr 3).toInt()
        if ((tag and 7L) != 0L || field !in 1..6 || field in values) return null
        val value = nextVarint() ?: return null
        if (value < 0) return null // no signed-unit interpretation without firmware proof
        values[field] = value
    }
    return values
}
private fun b4715TimecodeFields(evidence: String): Map<Int, Long>? {
    val fields = b4715DecodeWire(evidence, "0x844E") ?: return null
    return if ((1..6).all { it in fields } && fields[6] in listOf(0L, 1L, 2L, 4L, 5L)) fields else null
}
private fun b4714TcGetSourceId(evidence: String): Int? =
    b4715TimecodeFields(evidence)?.get(6)?.toInt()
private fun b4714SourceReadback(selected: String, tc: String): String {
    val id = b4714SelectedId(selected)
    val fromTc = b4714TcGetSourceId(tc)
    return when {
        id != null && fromTc != null && id == fromTc -> "${b4714SourceName(id)} (GETs agree, ID $id)"
        id != null && fromTc != null -> "${b4714SourceName(fromTc)} (TC f6=$fromTc; Source f1=$id MISMATCH)"
        fromTc != null -> "${b4714SourceName(fromTc)} (TC GET f6 only; Source GET unavailable)"
        id != null -> "${b4714SourceName(id)} (selected GET f1 only; TC not confirmed)"
        else -> "Awaiting fresh recorder GET"
    }
}
private fun b4715CounterSnapshot(evidence: String): String {
    val f = b4715TimecodeFields(evidence) ?: return "--:--:--:--:-- (no fresh typed 0x844E)"
    // Raw five-field snapshot only: not a validated HH:MM:SS:FF clock.
    return (1..5).joinToString(":") { n -> f[n].toString().padStart(2, '0') }
}
private fun b4714TcRawFields(evidence: String): String {
    val f = b4715TimecodeFields(evidence) ?: return "No validated 0x844E; press Refresh Timecode GET"
    return (1..6).joinToString(" | ") { n -> "f$n=${f[n]}" }
}
private fun b4715OffsetReadback(evidence: String): String {
    val f = b4715DecodeWire(evidence, "0x844F") ?: return "Not verified in current connection"
    return if (f.keys == setOf(1)) "Recorder f1=${f[1]} (raw GET; frame/unit unverified)"
    else "Raw fields ${f.keys.sorted().joinToString()} (semantics unknown)"
}
// B47.19 Phase 1: the f1..f4 interpretation is strongly supported by nine
// moving 0x844E hardware samples and a minute rollover. Frames/DF remain
// UNVERIFIED. Never synthesize 0x844E from the handset clock or send a SET.
private fun b4719TimecodeDisplay(evidence: String, elapsedMs: Long, advanceSeconds: Boolean): String {
    val f = b4715TimecodeFields(evidence) ?: return "--:--:--:--"
    val h = f[1]?.toInt() ?: return "--:--:--:--"
    val m = f[2]?.toInt() ?: return "--:--:--:--"
    val s = f[3]?.toInt() ?: return "--:--:--:--"
    if (h !in 0..23 || m !in 0..59 || s !in 0..59 || f[4] !in 0L..255L) return "--:--:--:--"
    val safeAge = elapsedMs.coerceIn(0L, 120000L)
    val seconds = (h * 3600L + m * 60L + s + if (advanceSeconds) safeAge / 1000L else 0L) % 86400L
    // The physical f4 values were even and reached 58; the nominal rate and
    // whether f4 holds half-frame ticks remain unknown. Show raw f4 separately.
    return "%02d:%02d:%02d:--".format(java.util.Locale.US,
        seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L)
}
// B47.20: seconds-only operator display. No frame rate or DF inference.
private fun b4720SecondsOnly(evidence: String, ageMs: Long, moving: Boolean): String =
    b4719TimecodeDisplay(evidence, ageMs, moving).take(8)
private fun b4719TimecodeMoved(previous: String, current: String): Boolean {
    val a = b4715TimecodeFields(previous) ?: return false
    val b = b4715TimecodeFields(current) ?: return false
    if (a[6] != b[6]) return false
    val prev = (a[1] ?: 0) * 3600L + (a[2] ?: 0) * 60L + (a[3] ?: 0)
    val next = (b[1] ?: 0) * 3600L + (b[2] ?: 0) * 60L + (b[3] ?: 0)
    val delta = (next - prev + 86400L) % 86400L
    return (delta in 1L..30L) || (delta == 0L && a[4] != b[4])
}
private fun b4719RawFlags(evidence: String): String {
    val f = b4715TimecodeFields(evidence) ?: return "No fresh Timecode GET"
    return "f4=${f[4]} (raw frame-like unit; rate unknown), f5=${f[5]} (flag unknown), source f6=${f[6]}"
}

private fun b4715SelectedSourceMode(selected: String, tc: String): String {
    val sourceId = b4714TcGetSourceId(tc) ?: b4714SelectedId(selected)
    return when (sourceId) {
        0 -> "Record Run selected. Recording-triggered counter logic is recorder-controlled."
        1 -> "Auto Restart selected. Restart behavior is device-controlled; no independent toggle GET verified."
        2 -> "Time of Day selected. Clock-origin interpretation is not yet confirmed by synchronized GET."
        4 -> "HDMI selected. Incoming timecode depends on HDMI input signal."
        5 -> "AirGlu SYNC selected. Sync-specific offset is independent and not yet decoded."
        else -> "No fresh source. Recorder controls/offsets cannot be inferred from a cached mode."
    }
}
// Keep original API-compatible names because diagnostics use these readbacks too.
private fun b471SourceDisplay(evidence: String): String =
    b4714SelectedId(evidence)?.let { b4714SourceName(it) } ?: "Awaiting fresh selected GET"
private fun b4713TimecodeGetSource(evidence: String): String =
    b4714TcGetSourceId(evidence)?.let { "${b4714SourceName(it)} (ID $it, TC GET f6)" }
        ?: "No matching TC GET source in this session"

// Local draft -> UTC preview, purely local. Strict DST validation: ambiguous or
// nonexistent local times cannot be sent or represented as an authorized SET.
private fun b451DraftToUtc(date: java.time.LocalDate?, hour: Int, minute: Int, second: Int, referenceZoneIndex: Int?): String {
    if (date == null || hour !in 0..23 || minute !in 0..59 || second !in 0..59 || referenceZoneIndex == null)
        return "Choose a manual reference zone and valid date/time"
    val name = B31FirmwareZoneCandidates.getOrNull(referenceZoneIndex) ?: return "Unrecognized reference zone"
    return try {
        val zone = java.time.ZoneId.of(name)
        val wall = LocalDateTime.of(date, java.time.LocalTime.of(hour, minute, second))
        val offsets = zone.rules.getValidOffsets(wall)
        if (offsets.size != 1) "DST gap/overlap: cannot resolve UTC without explicit disambiguation"
        else wall.toInstant(offsets.single()).atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", java.util.Locale.US)) + " (LOCAL PREVIEW; NO TX)"
    } catch (_: Exception) { "Could not convert draft to UTC" }
}

// B47.53: Complete read-only 53-field Settings reference. Not live device state.
private data class B4753Field(val id: Int, val section: String, val name: String, val reference: String, val getRequest: String, val recorderSet: String)
private val b4753Catalog = listOf(
    B4753Field(1, "Info", "Model", "Shogun Ultra (observed)", "0x04B0", "N/A"),
    B4753Field(2, "Info", "Device identifier", "ASGUFLMX92C (observed)", "0x04B0", "N/A"),
    B4753Field(3, "Info", "Version string field 2", "11.19.0 (observed)", "0x04B0", "N/A"),
    B4753Field(4, "Info", "Version string field 5", "11.19.0 (observed)", "0x04B0", "N/A"),
    B4753Field(5, "Info", "Additional string field 6", "6 (observed)", "0x04B0", "N/A"),
    B4753Field(6, "Info", "Recorder name", "App-discovered name", "UNVERIFIED", "N/A"),
    B4753Field(7, "Info", "BLE address", "Selected BLE identifier", "UNVERIFIED", "N/A"),
    B4753Field(8, "Info", "Remote link state", "Connected / disconnected", "UNVERIFIED", "N/A"),
    B4753Field(9, "Info", "App version", "Android app package version", "UNVERIFIED", "N/A"),
    B4753Field(10, "Info", "Pairing BCID", "Pairing code only", "UNVERIFIED", "N/A"),
    B4753Field(11, "Info", "Wi-Fi SSID", "Unknown", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(12, "Info", "Wi-Fi signal / status", "Unknown", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(13, "Info", "Ethernet link / mode", "Unknown", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(14, "Info", "IP / netmask / gateway", "Unknown", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(15, "Info", "Connected apps", "Unavailable", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(16, "Info", "Sync state / network role", "Unknown", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(17, "Info", "RF region / channel", "Unknown", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(18, "Info", "Warnings / error state", "Unknown", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(19, "Info", "Activated features / license", "Unknown", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(20, "Info", "Media status / recovery", "Unknown", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(21, "Info", "Power priority", "DC / Battery / Manual", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(22, "Info", "Factory reset", "LOCKED", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(23, "Info", "Reboot / PowerOff", "LOCKED", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(24, "Date & Time", "Date (raw wire)", "Wire 2026-10-30 observed; local date unverified", "0x04B9", "0x04B8 (STATIC)"),
    B4753Field(25, "Date & Time", "Time HH:MM:SS (raw UTC-like)", "Wire examples 08:20:59 / 10:14:51", "0x04B9", "0x04B8 (STATIC)"),
    B4753Field(26, "Date & Time", "Set to phone time", "Local editor draft only", "0x04B9", "0x04B8 (STATIC)"),
    B4753Field(27, "Date & Time", "12/24-hour time format", "0=24h raw 08001000; 1=12h raw 08001001", "0x04BB", "UNVERIFIED"),
    B4753Field(28, "Date & Time", "Date display format", "Date UI style options not independently enumerated", "0x04BB", "UNVERIFIED"),
    B4753Field(29, "Date & Time", "Selected recorder timezone", "Unverified; no remotely read city", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(30, "Date & Time", "Manual reference timezone list", "31 candidate names (NOT wire IDs)", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(31, "Date & Time", "Clock comparison / DST", "Projected from UTC-like RX + manual zone", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(32, "Timecode", "Selected source", "IDs 0=Record Run;1=Auto Restart;2=Time of Day;4=HDMI;5=AirGlu SYNC", "0x044D", "UNVERIFIED"),
    B4753Field(33, "Timecode", "Available source choices", "Record Run / Auto Restart / Time of Day / HDMI / AirGlu SYNC", "0x044C", "N/A"),
    B4753Field(34, "Timecode", "Current HH:MM:SS", "13:21:18 example; frames omitted", "0x044F", "UNVERIFIED"),
    B4753Field(35, "Timecode", "Set timecode", "Editor not transmitting", "0x044F", "UNVERIFIED"),
    B4753Field(36, "Timecode", "Frame counter / frame rate", "Unknown f4 raw, rate unknown", "0x044F", "UNVERIFIED"),
    B4753Field(37, "Timecode", "Drop frame", "Unknown", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(38, "Timecode", "Auto Restart", "Source mode ID1 (not separate switch)", "0x044D", "UNVERIFIED"),
    B4753Field(39, "Timecode", "Record Run", "Source mode ID0", "0x044D", "UNVERIFIED"),
    B4753Field(40, "Timecode", "Time of Day", "Source mode ID2", "0x044D", "UNVERIFIED"),
    B4753Field(41, "Timecode", "HDMI input TC source", "Source mode ID4", "0x044D", "UNVERIFIED"),
    B4753Field(42, "Timecode", "AirGlu SYNC source", "Source mode ID5", "0x044D", "UNVERIFIED"),
    B4753Field(43, "Timecode", "Timecode Offset", "Raw f1=0 example, unit unverified", "0x0451", "UNVERIFIED"),
    B4753Field(44, "Timecode", "AirGlu Sync TC Offset", "Unknown independently", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(45, "Timecode", "HDMI Out Timecode", "Unknown", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(46, "General", "Fan cooling mode", "Auto / Low / Medium (menu reference)", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(47, "General", "Turbo Cool", "On / Off (menu reference)", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(48, "General", "Dim Display", "On / Off (menu reference)", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(49, "General", "Fan speed diagnostic", "Raw RPM/value unknown", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(50, "General", "Recorder temperature", "Unknown", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(51, "General", "Backlight brightness", "Value/range unverified", "0x0321", "0x0320 (STATIC_IN_MASTER)"),
    B4753Field(52, "General", "Screen flip / touchscreen lock", "Unknown", "UNVERIFIED", "UNVERIFIED"),
    B4753Field(53, "General", "Tally light", "Unknown", "UNVERIFIED", "UNVERIFIED"),
)

// B47.72: operator-facing Info and General field catalog, no new wire commands.
// Info 1..5 are existing DeviceInfo typed RX fields; 6..10 are app/link metadata;
// all other Info and General fields are unknown until a correlated recorder GET.
private fun b4772InfoWireValue(id: Int, fields: Map<String, String>): String? = when (id) {
    1 -> fields["model"]
    2 -> fields["deviceId"]
    3 -> fields["deviceField2"]
    4 -> fields["deviceField5"]
    5 -> fields["deviceField6"]
    else -> null
}
private fun b4772InfoProvenance(id: Int): String = when (id) {
    in 1..5 -> "RECORDER GET 0x04B0 -> 0x84B0 (typed reply)"
    in 6..10 -> "APP / LINK METADATA (NOT DEVICEINFO GET)"
    else -> "NOT MAPPED TO A CURRENT RECORDER GET"
}
private fun b4772GeneralProvenance(id: Int): String = when (id) {
    51 -> "0x0321 / 0x0320 STATIC CANDIDATES ONLY; NO TYPED GET"
    else -> "NO VERIFIED RECORDER GET OR SET"
}

/** B47.75: Shift ONLY the recorder calendar date. The typed 0x84B5 reply
 * is UTC-like, not the local date shown on the recorder. The operator supplies
 * the physical baseline DATE, so the date delta does not require a guessed zone.
 * Preserve running raw HH:mm:ss; DateSet remains one-shot experimental.
 * Avoid DST transitions: this pilot cannot verify any zone offset transition.
 */
private fun b4775DateOnlyTarget(
    rawGet: String?, physicalScreenDate: String, targetScreenDate: String?, rxAgeMs: Long?
): String? {
    if (rawGet == null || targetScreenDate == null || rxAgeMs == null || rxAgeMs !in 0L..90000L) return null
    return try {
        val fmt = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", java.util.Locale.US)
            .withResolverStyle(java.time.format.ResolverStyle.STRICT)
        val raw = LocalDateTime.parse(rawGet, fmt)
        val beforeDate = java.time.LocalDate.parse(physicalScreenDate,
            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val afterDate = java.time.LocalDate.parse(targetScreenDate,
            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val shift = java.time.temporal.ChronoUnit.DAYS.between(beforeDate, afterDate)
        if (kotlin.math.abs(shift) != 1L) null
        else raw.plusDays(shift).plusSeconds(rxAgeMs / 1000L).format(fmt)
    } catch (_: Exception) { null }
}

/** B47.74: DATE ONLY. Preserve actual recorder clock seconds from fresh 0x84B5;
 * shift only the calendar day selected on the recorder screen. The physical
 * timezone offset is operator calibrated, NEVER a recorder Timezone GET.
 * A candidate is not proof of SET delivery or hardware acceptance.
 */
private fun b4774DateOnlyTarget(
    rawGet: String?, desiredLocalDate: String, physicalOffsetSeconds: Int?, rxAgeMs: Long?
): String? {
    if (rawGet == null || physicalOffsetSeconds == null || rxAgeMs == null || rxAgeMs !in 0L..90000L) return null
    return try {
        val fmt = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", java.util.Locale.US)
            .withResolverStyle(java.time.format.ResolverStyle.STRICT)
        val rawUtc = LocalDateTime.parse(rawGet, fmt)
        val desiredDate = java.time.LocalDate.parse(desiredLocalDate, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val localBaselineDate = rawUtc.plusSeconds(physicalOffsetSeconds.toLong()).toLocalDate()
        val dateShift = java.time.temporal.ChronoUnit.DAYS.between(localBaselineDate, desiredDate)
        if (kotlin.math.abs(dateShift) != 1L) null
        else rawUtc.plusDays(dateShift).plusSeconds(rxAgeMs / 1000L).format(fmt)
    } catch (_: Exception) { null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsS02Panel(
    recorderName: String,
    modelCode: String,
    bleAddress: String,
    bleState: String,
    linked: Boolean,
    remotePhase: String,
    s05GattEvents: List<String>,
    s05LiveGattAddress: String?,
    s05RefreshExistingBootstrap: () -> Boolean,
    s11RequestDateGet: () -> Boolean,
    s12RequestSettingsGet: (Int) -> Boolean,
    b4767SendAirGluOffsetPilot: (Int, Int, Int, Int) -> Boolean, // Experimental; one-shot gated TX; no ACK assumption.
    s61PreviewDateSetNoTx: (String) -> String, // No transport; packet preview only.
    b4771SendDateSetPilot: (String, String) -> Boolean, // One-shot experimental DateSet, post-SET GET required.
    // S11: live snapshot from existing StateFlow, read-only; avoids 500-entry UI history rollover.
    s11ReadLiveGattEvents: () -> List<String>
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
    var b4761DatePreview by remember(bleAddress, linked) { mutableStateOf("") }
    var use24h by remember { mutableStateOf(true) }
    var tcHour by remember { mutableIntStateOf(0) }
    var tcMinute by remember { mutableIntStateOf(0) }
    var tcSecond by remember { mutableIntStateOf(0) }
    var tcFrame by remember { mutableIntStateOf(0) }
    var tcOffset by remember { mutableIntStateOf(0) }
    var draftDrop by remember { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf<String?>(null) }
    // B47.53: draft-only SET UI. Never queued, never represented as recorder state.
    var b4753FanDraft by remember(bleAddress, linked) { mutableStateOf("Auto") }
    var b4753TurboDraft by remember(bleAddress, linked) { mutableStateOf(false) }
    var b4753DimDraft by remember(bleAddress, linked) { mutableStateOf(false) }
    var b4753TcSourceDraft by remember(bleAddress, linked) { mutableStateOf("Time of Day") }
    // B38: Never restore a previously matched zone as if it were the recorder's current zone.
    // B37 proved DateGet and DateFormatGet, but NO selected-TimeZone GET. A physical
    // recorder zone change cannot be observed through the current verified commands.
    // A manual comparison reference is session-local, never the live recorder selection.
    var b31CandidateId by remember(bleAddress, linked) { mutableStateOf<Int?>(null) }
    // Operator-observed 17:26 vs civil Sydney 18:26: recorder firmware may
    // display standard UTC+10 even while city label is Australia/Sydney.
    // This is DISPLAY ONLY, never a recorder Time Zone GET or SET.
    var b4769SydneyStandard by remember(bleAddress, linked) { mutableStateOf(false) }
    // B47.71.3: Recorder-zone GET is unmapped. Do NOT show a stale Sydney zone
    // after the user changes the physical recorder to Asia/Karachi.
    // Offset is explicitly operator-calibrated from a freshly sampled raw DateGet.
    // This is a session-only physical clock comparison, not a timezone GET/SET.
    var b47713ClockOffsetSeconds by remember(bleAddress, linked) { mutableStateOf<Int?>(null) }
    var b47713PhysicalStamp by remember(bleAddress, linked) { mutableStateOf("") }
    var b47713CalStatus by remember(bleAddress, linked) { mutableStateOf("Not matched: read physical recorder date/time") }
    // B47.71: session-only recorder-clock editor, NOT a device timezone setter.
    // B47.75 simple Date-only sheet: operator types physical date (not zone GET),
    // then chooses -1/+1 day. Reset automatically on disconnect/reconnect.
    var b4775ScreenDate by remember(bleAddress, linked) { mutableStateOf("") }
    var b4775DayDirection by remember(bleAddress, linked) { mutableIntStateOf(0) }
    var b4771DateText by remember(bleAddress, linked) { mutableStateOf("") }
    var b4771TimeText by remember(bleAddress, linked) { mutableStateOf("") }
    var b4771Seeded by remember(bleAddress, linked) { mutableStateOf(false) }
    var b47711PhysicalMatchConfirmed by remember(bleAddress, linked) { mutableStateOf(false) }
    var b4771IdleConsent by remember(bleAddress, linked) { mutableStateOf(false) }
    var b4771ArmedUtc by remember(bleAddress, linked) { mutableStateOf<String?>(null) }
    var b4771PendingUtc by remember(bleAddress, linked) { mutableStateOf<String?>(null) }
    var b4771SentMs by remember(bleAddress, linked) { mutableStateOf(0L) }
    var b4771MatchGet by remember(bleAddress, linked) { mutableStateOf(false) }
    var b4771PhysicalConfirmed by remember(bleAddress, linked) { mutableStateOf(false) }
    var b4771Result by remember(bleAddress, linked) { mutableStateOf("No DateSet sent in this session") }
    // B45.1 physical recorder-clock reference typed by operator, NOT Android time, NOT recorder GET.
    var b451PhysicalClock by remember(bleAddress, linked) { mutableStateOf("") }
    var b452AmPm by remember(bleAddress, linked) { mutableStateOf("") }
    var b451ComparisonRaw by remember(bleAddress, linked) { mutableStateOf<String?>(null) }
    var b451Suggestions by remember(bleAddress, linked) { mutableStateOf(emptyList<B451ZoneCandidate>()) }
    var b451ComparedOnce by remember(bleAddress, linked) { mutableStateOf(false) }
    // B45.3: time sampled when 0x84B5 typed Date GET arrives. Duration only;
    // elapsedRealtime does NOT consult phone date, time or zone.
    var b453LastDateRxMs by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf<Long?>(null) }
    var b453CompareAgeSec by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf<Long?>(null) }

    // B47.1: advanced info evidence stays accessible, default collapsed.
    var b471ShowInfoDiagnostics by remember(bleAddress) { mutableStateOf(false) }
    // B34 UI polish: preserve full GET evidence, collapsed by default.
    var b34ShowDiagnostics by remember(bleAddress) { mutableStateOf(false) }
    val sheetKey = activeSheet

    // B35 editor drafts are isolated from recorder readback; never optimistic UI SET state.
    var b35DraftDate by remember(bleAddress, linked) { mutableStateOf<java.time.LocalDate?>(null) }
    var b35DraftHour by remember(bleAddress, linked) { mutableIntStateOf(-1) }
    var b35DraftMinute by remember(bleAddress, linked) { mutableIntStateOf(-1) }
    var b35DraftSecond by remember(bleAddress, linked) { mutableIntStateOf(-1) }
    var b35DraftFormat by remember(bleAddress, linked) { mutableIntStateOf(-1) }
    var b35DraftZoneIndex by remember(bleAddress, linked) { mutableIntStateOf(-1) }
    var b35Prepared by remember(bleAddress, linked) { mutableStateOf<String?>(null) }

    // B41: Passive evidence from EXISTING TCS cxtic1 read, NOT RemoteApi.TimecodeGet.
    // This raw sync read is not HH:MM:SS:FF and does not identify Source or TC Offset.
    var b41TcsSyncEvidence by remember(bleAddress, linked, s05LiveGattAddress) {
        mutableStateOf("No cxtic1 16-byte sync event in current observation window")
    }
    LaunchedEffect(bleAddress, linked, s05LiveGattAddress) {
        if (!linked || s05LiveGattAddress?.equals(bleAddress, true) != true) return@LaunchedEffect
        repeat(42) {
            val found = s11ReadLiveGattEvents().lastOrNull {
                it.contains("TCS18.9 CONNECTED TIMECODE OK len=16") && it.contains("hex=")
            }
            if (found != null) {
                val raw = found.substringAfter("hex=", "").substringBefore(';').trim()
                    .takeWhile { it in "0123456789abcdefABCDEF" }
                if (raw.length == 32 && !b41TcsSyncEvidence.contains(raw)) {
                    b41TcsSyncEvidence = "TCS sync 16-byte RX: $raw (not decoded TimecodeGet)"
                    android.util.Log.i("AtomRemoteB41", "TCS_SYNC_RX_OBSERVED bytes=16 remoteApiTcGet=UNMAPPED noSET=true")
                }
            }
            kotlinx.coroutines.delay(500L)
        }
    }

    // B04: recorder-side GATT preflight identity. NEVER equate BCID with recorder Device ID.
    // No additional BLE reads or writes; bound to the same selected live GATT address.
    var b04Identity by remember(bleAddress, linked, bleState, s05LiveGattAddress) {
        mutableStateOf(SettingsB04InfoEvidence.Evidence())
    }
    LaunchedEffect(bleAddress, linked, bleState, s05LiveGattAddress) {
        if (!linked || s05LiveGattAddress?.equals(bleAddress, true) != true) return@LaunchedEffect
        var previous = emptyList<String>()
        repeat(45) {
            val snapshot = s11ReadLiveGattEvents()
            val fresh = if (previous.isEmpty()) snapshot else b02EventDelta(previous, snapshot)
            if (fresh.isNotEmpty()) {
                b04Identity = SettingsB04InfoEvidence.consume(b04Identity, bleAddress, fresh)
            }
            previous = snapshot
            kotlinx.coroutines.delay(500L)
        }
    }

    // SETTINGS S05: NEVER attribute the primary GATT stream to another selected device.
    val s05AddressMatch = linked && s05LiveGattAddress?.equals(bleAddress, ignoreCase = true) == true
    // Device-scoped, in-memory evidence from NEW GATT events only.
    var s05LastSeenEvent by remember(bleAddress, linked, bleState, s05LiveGattAddress) { mutableStateOf<String?>(null) }
    var s05Captured by remember(bleAddress, linked, bleState, s05LiveGattAddress) { mutableStateOf<SettingsS05DeviceInfoEvidence.Response?>(null) }
    var s05RefreshStatus by remember(bleAddress, linked, bleState, s05LiveGattAddress) { mutableStateOf("Waiting for recorder GET evidence") }
    var s06Trace by remember(bleAddress, linked, bleState, s05LiveGattAddress) { mutableStateOf(SettingsS06DeviceInfoTrace.Trace()) }
    var s09Lifecycle by remember(bleAddress, linked, bleState, s05LiveGattAddress) { mutableStateOf(SettingsS09DeviceInfoLifecycle.Trace()) }
    // S10 read-only trigger trace: record whether Settings is visible and if the
    // existing GET button is enabled. Never log device addresses or modify GATT.
    val s10GetEnabled = s05AddressMatch && bleAddress != "Not connected"
    var s10PostTapEventSamples by remember(bleAddress, linked, bleState, s05LiveGattAddress) {
        mutableIntStateOf(0)
    }
    var s11LastOutcome by remember(bleAddress, linked, bleState, s05LiveGattAddress) {
        mutableStateOf("Not tested")
    }
    LaunchedEffect(bleAddress, linked, bleState, remotePhase, s05LiveGattAddress) {
        android.util.Log.i("AtomRemoteS10",
            "SETTINGS_PANEL_VISIBLE linked=$linked addressMatch=$s05AddressMatch " +
            "getEnabled=$s10GetEnabled bleState=$bleState remotePhase=$remotePhase")
    }
    LaunchedEffect(bleAddress, linked, bleState, s05LiveGattAddress, s05GattEvents) {
        val tail = s05GattEvents.lastOrNull()
        val lastSeen = s05LastSeenEvent
        // First observation deliberately baselines existing history, avoiding
        // other recorder's buffered RX being shown as this selected device.
        if (lastSeen != null && s05AddressMatch) {
            val fresh = SettingsS05DeviceInfoEvidence.freshAfter(s05GattEvents, lastSeen)
            if (s09Lifecycle.testStarted && fresh.isNotEmpty() && s10PostTapEventSamples < 3) {
                s10PostTapEventSamples += 1
                android.util.Log.i("AtomRemoteS10",
                    "POST_TAP_UI_EVENT_SAMPLE sample=$s10PostTapEventSamples " +
                    "freshCount=${fresh.size} accumulatedEvents=${s05GattEvents.size}")
            }
            // B02: event consumption is handled by the bounded live-after-tap
            // observer. Avoid replaying the same event from Compose history.
            if (!s09Lifecycle.testStarted) s05RefreshStatus = "No GET test running"
            // B01R: the synchronous S11 reason may be DEFERRED_BUSY while
            // the existing official bootstrap starts later. Observe only
            // fresh, device-scoped events; do NOT issue a new BLE request.
            if (s09Lifecycle.testStarted && fresh.isNotEmpty()) {
                val delayedOutcome: String? = when {
                    s06Trace.verified != null ->
                        "DeviceInfo 0x04B0 typed RX verified"
                    fresh.any { it.contains("STATE SYNC COMPLETE sent=") } ->
                        "Bootstrap finished; DeviceInfo typed RX not verified"
                    fresh.any { it.contains("TRANSPORT DELIVERED label=DeviceInfoGet") } ->
                        "DeviceInfo transport delivered; awaiting recorder payload"
                    fresh.any { it.contains("BOOTSTRAP QUERY label=DeviceInfoGet") } ->
                        "DeviceInfo 0x04B0 dispatched; awaiting recorder payload"
                    fresh.any { it.contains("STATE MANUAL REFRESH start ") } ->
                        "Deferred GET started; waiting for DeviceInfo TX"
                    else -> null
                }
                if (delayedOutcome != null && delayedOutcome != s11LastOutcome) {
                    s11LastOutcome = delayedOutcome
                    android.util.Log.i("AtomRemoteB01R", "GET_ASYNC_STATUS $delayedOutcome")
                }
            }
        }
        s05LastSeenEvent = tail
    }
    // S08: decode wire structure only after S06 has correlated a real 0x04B0 reply.
    // An E0 transport tick is never eligible; GATT and SET paths stay untouched.
    val s08Inspection = remember(s05Captured?.rawHex) {
        s05Captured?.let { SettingsS08DeviceInfoFields.inspect(it.rawHex) }
    }

    // B02: asynchronous recorder GET observation. Compose's 500-item event history
    // alone can roll over during MAC13; sample live StateFlow after the tap.
    var b02CaptureEpoch by remember(bleAddress, linked, s05LiveGattAddress) { mutableIntStateOf(0) }
    var b02StartingEvents by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(emptyList<String>()) }
    var b02LiveStatus by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf("No Settings GET requested") }
    var b02QuerySeen by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(false) }
    var b02DeliverySeen by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(false) }
    var b02AsyncEventCount by remember(bleAddress, linked, s05LiveGattAddress) { mutableIntStateOf(0) }
    LaunchedEffect(b02CaptureEpoch, bleAddress, linked, s05LiveGattAddress) {
        if (b02CaptureEpoch == 0 || !s05AddressMatch) return@LaunchedEffect
        var previous = b02StartingEvents
        repeat(95) {
            kotlinx.coroutines.delay(350L)
            if (!s05AddressMatch) return@LaunchedEffect
            val latest = s11ReadLiveGattEvents()
            val fresh = b02EventDelta(previous, latest)
            previous = latest
            if (fresh.isEmpty()) return@repeat
            b02AsyncEventCount += fresh.size
            s06Trace = SettingsS06DeviceInfoTrace.consume(s06Trace, fresh)
            s09Lifecycle = SettingsS09DeviceInfoLifecycle.consume(s09Lifecycle, fresh)
            s06Trace.verified?.let { evidence ->
                s05Captured = SettingsS05DeviceInfoEvidence.Response(evidence.packetId, evidence.rawHex)
            }
            if (fresh.any { it.contains("STATE MANUAL REFRESH start ") || it.contains("MAC13+PAGES START count=") }) {
                b02LiveStatus = "Official bootstrap started; waiting for DeviceInfo query"
                android.util.Log.i("AtomRemoteB02", "SETTINGS_ASYNC_START observed")
            }
            if (fresh.any { it.contains("BOOTSTRAP QUERY label=DeviceInfoGet") || it.contains("MAC13 PACKET label=DeviceInfoGet") }) {
                b02QuerySeen = true
                b02LiveStatus = "0x04B0 request dispatched; recorder data pending"
                android.util.Log.i("AtomRemoteB02", "SETTINGS_ASYNC_DEVICEINFO_DISPATCH observed")
            }
            if (fresh.any { it.contains("TRANSPORT DELIVERED label=DeviceInfoGet") }) {
                b02DeliverySeen = true
                b02LiveStatus = "PacketLink delivered; typed recorder response NOT verified"
                android.util.Log.i("AtomRemoteB02", "SETTINGS_ASYNC_TRANSPORT_DELIVERED observed")
            }
            when {
                s06Trace.verified != null -> b02LiveStatus = "DeviceInfo packet-correlated RX found; schema validation pending"
                s06Trace.rejectionCount > 0 -> b02LiveStatus = "DeviceInfo request rejected; see diagnostics"
                fresh.any { it.contains("MAC13+PAGES DONE") || it.contains("BOOTSTRAP DONE") || it.contains("BOOTSTRAP COMPLETE") } && b02QuerySeen ->
                    b02LiveStatus = "Bootstrap complete; no typed DeviceInfo reply verified"
            }
            if (b02AsyncEventCount > 4000) return@LaunchedEffect
        }
        if (b02QuerySeen && s06Trace.verified == null)
            b02LiveStatus = "GET window complete; no matching DeviceInfo payload confirmed"
    }

    // S09: one existing refresh drives BOTH read-only S09 dispatch and S08 RX evidence tests.
    // Returning true can mean deferred or already-active: never label it transmitted.
    val s09RunDualGetTest: () -> Unit = {
        android.util.Log.i("AtomRemoteS10",
            "GET_BUTTON_TAPPED linked=$linked addressMatch=$s05AddressMatch " +
            "getEnabled=$s10GetEnabled existingEventCount=${s05GattEvents.size}")
        s05Captured = null
        s06Trace = SettingsS06DeviceInfoTrace.Trace()
        s09Lifecycle = SettingsS09DeviceInfoLifecycle.Trace(testStarted = true)
        s10PostTapEventSamples = 0
        s05LastSeenEvent = s05GattEvents.lastOrNull()
        // S11: capture the exact live StateFlow at the synchronous callback boundary,
        // not the Compose parameter (which may be stale or capped at 500 entries).
        val s11Before = s11ReadLiveGattEvents()
        b02StartingEvents = s11Before
        b02QuerySeen = false
        b02DeliverySeen = false
        b02AsyncEventCount = 0
        b02LiveStatus = "GET requested; checking scheduler and recorder RX"
        b02CaptureEpoch += 1
        val accepted = s05RefreshExistingBootstrap()
        val s11After = s11ReadLiveGattEvents()
        val s11LastBefore = s11Before.lastOrNull()
        val s11Pivot = if (s11LastBefore == null) -1 else s11After.indexOfLast { it == s11LastBefore }
        val s11Delta = when {
            s11Pivot >= 0 -> s11After.drop(s11Pivot + 1)
            s11After !== s11Before -> s11After.takeLast(24)
            else -> emptyList()
        }
        val s11Cause = s11Delta.lastOrNull {
            it.contains("STATE REFRESH ") || it.contains("STATE MANUAL REFRESH ") ||
            it.contains("MAC13+PAGES START") || it.contains("MAC13 PACKET label=DeviceInfoGet") ||
            it.contains("BOOTSTRAP QUERY label=DeviceInfoGet")
        }
        val s11Code = when {
            s11Delta.any { it.contains("STATE REFRESH blocked ") } -> "BLOCKED"
            s11Delta.any { it.contains("STATE REFRESH already-active ") } -> "ALREADY_ACTIVE"
            s11Delta.any { it.contains("STATE REFRESH deferred busy ") } -> "DEFERRED_BUSY"
            s11Delta.any { it.contains("MAC13 PACKET label=DeviceInfoGet") } -> "DEVICEINFO_DISPATCH"
            s11Delta.any { it.contains("STATE MANUAL REFRESH start ") } -> "STARTED"
            else -> "NO_IMMEDIATE_REASON"
        }
        s11LastOutcome = when (s11Code) {
            "BLOCKED" -> "Blocked by session readiness"
            "ALREADY_ACTIVE" -> "Official bootstrap already active; no new GET"
            "DEFERRED_BUSY" -> "Queued for retry when transport is free"
            "STARTED" -> "Existing official bootstrap started; await TX/RX"
            "DEVICEINFO_DISPATCH" -> "DeviceInfo request dispatched; await typed RX"
            else -> "No synchronous outcome captured; GET not verified"
        }
        android.util.Log.i("AtomRemoteS11",
            "GET_LIVE_REASON code=$s11Code accepted=$accepted " +
            "eventsBefore=${s11Before.size} eventsAfter=${s11After.size} " +
            "newEvents=${s11Delta.size} " +
            "cause=${(s11Cause ?: "none").take(220)}")
        // Targeted INFO mirror of actual callback events. No new GET/SET is sent.
        s11Delta.filter {
            it.contains("STATE REFRESH ") || it.contains("STATE MANUAL REFRESH ") ||
            it.contains("MAC13+PAGES START") || it.contains("MAC13 PACKET label=DeviceInfoGet") ||
            it.contains("BOOTSTRAP QUERY label=DeviceInfoGet")
        }.takeLast(4).forEach { android.util.Log.i("AtomRemoteS11", "GET_LIVE_EVENT ${it.take(240)}") }
        android.util.Log.i("AtomRemoteS10", "GET_CALLBACK_RETURN accepted=$accepted " +
            "(true may mean deferred or already-active, never typed RX)")
        s05RefreshStatus = if (accepted)
            "Refresh accepted (may be deferred); check S09 dispatch and S08 typed RX below"
        else "Refresh refused by transport; no GET success can be claimed"
    }
    val formattedDraftTime = if (use24h) {
        String.format(java.util.Locale.US, "%02d:%02d:%02d", draftHour, draftMinute, draftSecond)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d:%02d %s", (draftHour + 11) % 12 + 1, draftMinute, draftSecond, if (draftHour < 12) "AM" else "PM")
    }


    // B11 single DateGet: raw typed response evidence only; NOT a confirmed calendar value.
    var b11Trace by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(SettingsB11DateTrace.Trace()) }
    var b11Epoch by remember(bleAddress, linked, s05LiveGattAddress) { mutableIntStateOf(0) }
    var b11Baseline by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(b11Epoch, bleAddress, linked, s05LiveGattAddress) {
        if (b11Epoch == 0 || !s05AddressMatch) return@LaunchedEffect
        var previous = b11Baseline
        repeat(100) {
            kotlinx.coroutines.delay(250L)
            if (!s05AddressMatch) return@LaunchedEffect
            val latest = s11ReadLiveGattEvents()
            val fresh = b02EventDelta(previous, latest)
            previous = latest
            if (fresh.isNotEmpty()) b11Trace = SettingsB11DateTrace.consume(b11Trace, fresh)
        }
        if (b11Trace.rawHex == null && !b11Trace.rejected) {
            b11Trace = b11Trace.copy(status = "Observation ended: no matching Date typed RX confirmed")
        }
    }
    val b11RunDateGet: () -> Unit = {
        val before = s11ReadLiveGattEvents()
        b11Baseline = before
        b11Trace = SettingsB11DateTrace.Trace(status = "DateGet requested; checking transport readiness")
        b11Epoch += 1
        android.util.Log.i("AtomRemoteB11", "DATE_GET_BUTTON_TAPPED linked=$linked addressMatch=$s05AddressMatch")
        val accepted = if (s05AddressMatch) s11RequestDateGet() else false
        android.util.Log.i("AtomRemoteB11", "DATE_GET_BUTTON_RETURN accepted=$accepted notTypedRx=true")
        val delta = b02EventDelta(before, s11ReadLiveGattEvents())
        b11Trace = SettingsB11DateTrace.consume(b11Trace, delta)
        if (!accepted) b11Trace = b11Trace.copy(status = "GET blocked or busy; no Date command claimed as sent")
    }


    // B12: one-tap, sequential READ-ONLY GET probe; never generate an unmapped opcode.
    var b12BatchNonce by remember(bleAddress, linked, s05LiveGattAddress) { mutableIntStateOf(0) }
    var b12BatchRunning by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(false) }
    var b12BatchStatus by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf("Not run") }
    var b12Results by remember(bleAddress, linked, s05LiveGattAddress) {
        mutableStateOf(mapOf("Date" to "Not tested", "Date format" to "Not tested", "Device info" to "Not tested"))
    }
    // B21 Date-only and B37 Date+TimeFormat modes use only proven GETs.
    // No local recorder timezone is inferred from either typed response.
    var b21DateOnly by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(false) }
    var b37ClockOnly by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(false) }
    var b21DateRxPhoneTime by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf<String?>(null) }
    // B15: recorder-derived GET values, reset on connection/session change.
    // An empty map means NO confirmed typed readback (not an inferred default).
    var b15Readbacks by remember(bleAddress, linked, s05LiveGattAddress) {
        mutableStateOf(emptyMap<String, String>())
    }
    // B17: only recorder typed RX; no manual zone, UTC-derived clock, or simulated timezone.
    val b16Format = SettingsB16ClockEvidence.enums(b15Readbacks["dateFormatRaw"])
    // B32: no selected-zone readback exists; this value must ALWAYS be labelled preview.
    val b32TimePreview = b32ReferenceTime(b15Readbacks["dateTime"], b31CandidateId, b16Format?.second)
    // Manual projection may only be shown as a diagnostic comparison, never the recorder's time.
    // It cannot track a physical zone switch until a selected-TimeZone GET is proven.
    val b33TimeDisplay = when {
        b15Readbacks["dateTime"].isNullOrBlank() -> "Waiting for fresh recorder DateGet"
        b31CandidateId == null -> "Select Time Zone"
        b32TimePreview != null -> "$b32TimePreview (MANUAL comparison only)"
        else -> "Recorder zone preview unavailable"
    }
    val b38LiveLocalTime = if (b15Readbacks["dateTime"].isNullOrBlank())
        "Waiting for recorder Date GET"
    else "Unavailable: selected Time Zone GET not mapped"
    val b38LiveZone = "Not readable through verified GET"
    // B47.62: display preference is session-only; never a recorder setting or wire-zone ID.
    var b4762DisplayZoneOverride by remember(bleAddress, linked) { mutableStateOf<String?>(null) }
    var b4762ClockTickMs by remember(bleAddress, linked) {
        mutableStateOf(android.os.SystemClock.elapsedRealtime())
    }
    val b4762DisplayZone = b4762DisplayZoneOverride ?: java.time.ZoneId.systemDefault().id
    val b4762RxAgeMs = b453LastDateRxMs?.let { b4762ClockTickMs - it }
    val b4762LocalPreview = if (s05AddressMatch) b4762ProjectReferenceClock(
        b15Readbacks["dateTime"], b4762DisplayZone, b16Format?.second, b4762RxAgeMs
    ) else null
    LaunchedEffect(b15Readbacks["dateTime"], b31CandidateId, b16Format?.second, s05AddressMatch) {
        if (s05AddressMatch && !b15Readbacks["dateTime"].isNullOrBlank()) {
            android.util.Log.i("AtomRemoteB33",
                "TIME_EVIDENCE rx=0x84B5 raw=${b15Readbacks["dateTime"]} " +
                "referenceId=${b31CandidateId ?: -1} referenceZone=${b31CandidateId?.let { B31FirmwareZoneCandidates[it] } ?: "NONE"} " +
                "timePreview=${b32TimePreview ?: "NONE"} formatId=${b16Format?.second ?: -1} " +
                "liveTimeZoneGet=false deviceSet=false")
        }
    }
    LaunchedEffect(b12BatchNonce, bleAddress, linked, s05LiveGattAddress) {
        if (b12BatchNonce < 1 || !s05AddressMatch) return@LaunchedEffect
        b12BatchRunning = true
        b12BatchStatus = "Running mapped GET requests; SET disabled"
        android.util.Log.i("AtomRemoteB12", "BATCH_START once=$b12BatchNonce linked=$linked addressMatch=$s05AddressMatch")
        try {
            val items = when {
                b21DateOnly -> listOf(Triple("Date", 0x04B9, 0x04B5))
                b37ClockOnly -> listOf(Triple("Date", 0x04B9, 0x04B5), Triple("Date format", 0x04BB, 0x04B7))
                else -> listOf(Triple("Date", 0x04B9, 0x04B5), Triple("Date format", 0x04BB, 0x04B7), Triple("Device info", 0x04B0, 0x04B0))
            }
            for ((name, cmd, candidate) in items) {
                if (!s05AddressMatch) {
                    b12BatchStatus = "Aborted: selected recorder connection changed"
                    break
                }
                b12Results = b12Results + (name to "Checking current session / PacketLink")
                var accepted = false
                var pre = emptyList<String>()
                // Retry only when busy. The underlying GattClient still fail-closes.
                for (trial in 1..10) {
                    pre = s11ReadLiveGattEvents()
                    accepted = s12RequestSettingsGet(cmd)
                    if (accepted) break
                    kotlinx.coroutines.delay(600L)
                }
                if (!accepted) {
                    b12Results = b12Results + (name to "Not sent: transport busy or unavailable")
                    android.util.Log.i("AtomRemoteB12", "BATCH_ITEM_RESULT item=$name result=BLOCKED_NO_TX")
                    continue
                }
                kotlinx.coroutines.delay(2200L)
                val fresh = b02EventDelta(pre, s11ReadLiveGattEvents())
                val nameLabel = when (cmd) { 0x04B9 -> "DateGet"; 0x04BB -> "DateFormatGet"; else -> "DeviceInfoGet" }
                val delivered = fresh.any { it.contains("TRANSPORT DELIVERED label=B12.$nameLabel") }
                val expectedTypes = setOf(candidate, candidate or 0x8000)
                val pattern = Regex("""REMOTE PAYLOAD local=(true|false) packetId=(\d+) payloadLen=(\d+) messageType=(\d+) data=([0-9A-Fa-f]*)""")
                val plausible = fresh.mapNotNull { line ->
                    val m = pattern.find(line) ?: return@mapNotNull null
                    val count = m.groupValues[3].toIntOrNull() ?: return@mapNotNull null
                    val kind = m.groupValues[4].toIntOrNull() ?: return@mapNotNull null
                    val raw = m.groupValues[5]
                    if (kind !in expectedTypes || count <= 0 || count > 2048 || raw.length != count * 2) null
                    else "type=0x${kind.toString(16).uppercase()}, raw=${raw.take(80)}"
                }.firstOrNull()
                // B15: examine the complete (not raw.take(80)) typed payload, in this
                // specific GET's fresh event window. Response packet IDs are AG2-assigned.
                var typed = fresh.mapNotNull { line ->
                    val m = pattern.find(line) ?: return@mapNotNull null
                    if (m.groupValues[1] != "true") return@mapNotNull null
                    val size = m.groupValues[3].toIntOrNull() ?: return@mapNotNull null
                    val kind = m.groupValues[4].toIntOrNull() ?: return@mapNotNull null
                    val hex = m.groupValues[5]
                    if (kind != (candidate or 0x8000) || size !in 1..2048 || hex.length != size * 2) null
                    else SettingsB15TypedRx.decode(cmd, hex)
                }.firstOrNull()
                // B40: Busy PacketLink owners may acknowledge a proven GET without providing
                // a typed reply. Retry ONLY the existing verified Date / Format getters.
                // Max 3 attempts total, with separate fresh RX windows; never SET.
                if ((cmd == 0x04B9 || cmd == 0x04BB) && typed == null && s05AddressMatch) {
                    for (attempt in 2..3) {
                        kotlinx.coroutines.delay(450L)
                        val retryBefore = s11ReadLiveGattEvents()
                        val retryQueued = s12RequestSettingsGet(cmd)
                        android.util.Log.i("AtomRemoteB40", "CLOCK_GET_RETRY label=$name attempt=$attempt queued=$retryQueued cmd=0x${cmd.toString(16).uppercase()} noSET=true")
                        if (!retryQueued) continue
                        kotlinx.coroutines.delay(2200L)
                        val retryFresh = b02EventDelta(retryBefore, s11ReadLiveGattEvents())
                        typed = retryFresh.mapNotNull { event ->
                            val match = pattern.find(event) ?: return@mapNotNull null
                            if (match.groupValues[1] != "true") return@mapNotNull null
                            val byteCount = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
                            val replyType = match.groupValues[4].toIntOrNull() ?: return@mapNotNull null
                            val data = match.groupValues[5]
                            if (replyType != (candidate or 0x8000) || byteCount !in 1..2048 || data.length != byteCount * 2) null
                            else SettingsB15TypedRx.decode(cmd, data)
                        }.firstOrNull()
                        if (typed != null) {
                            android.util.Log.i("AtomRemoteB40", "CLOCK_RETRY_TYPED_PASS label=$name attempt=$attempt cmd=0x${cmd.toString(16).uppercase()} noSET=true")
                            break
                        }
                    }
                }
                if ((cmd == 0x04B9 || cmd == 0x04BB) && typed == null) {
                    android.util.Log.i("AtomRemoteB40", "CLOCK_GET_NO_TYPED_RX label=$name cmd=0x${cmd.toString(16).uppercase()} busyOrNoReply=true selectedZoneGet=UNMAPPED noSET=true")
                }
                val staleKeys = when (cmd) {
                    0x04B9 -> setOf("dateTime")
                    0x04BB -> setOf("dateFormatRaw")
                    else -> setOf("deviceId", "deviceField2", "model", "deviceField5", "deviceField6")
                }
                b15Readbacks = b15Readbacks.filterKeys { it !in staleKeys } + (typed ?: emptyMap())
                if (cmd == 0x04B9) {
                    b453LastDateRxMs = if (typed?.containsKey("dateTime") == true)
                        android.os.SystemClock.elapsedRealtime() else null
                    b21DateRxPhoneTime = if (typed?.containsKey("dateTime") == true) {
                        java.time.OffsetDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss XXX"))
                    } else null
                    android.util.Log.i("AtomRemoteB21", "TIME_GET_FRESH_RX confirmed=${b21DateRxPhoneTime != null} source=0x84B5 utc_like=true zone_get_verified=false SET_DISABLED")
                }
                android.util.Log.i("AtomRemoteB15", "TYPED_READBACK label=$name schemaPass=${typed != null} fieldCount=${typed?.size ?: 0} request=0x${cmd.toString(16).uppercase()} SET_DISABLED")
                val status = when {
                    typed != null -> "Recorder typed RX decoded; ${typed.size} fields (SET locked)"
                    plausible != null -> "Typed reply seen but B15 schema rejected; not confirmed"
                    delivered -> "TX delivered; no typed readback (0x${candidate.toString(16).uppercase()} candidate)"
                    else -> "Queued; delivery/typed RX unconfirmed"
                }
                b12Results = b12Results + (name to status)
                android.util.Log.i("AtomRemoteB12", "BATCH_ITEM_RESULT item=$name delivered=$delivered rawCandidate=${plausible ?: "NONE"}")
                kotlinx.coroutines.delay(350L)
            }
            if (b12BatchStatus.startsWith("Running")) b12BatchStatus = "Batch finished: results below are RX evidence, not confirmed values"
        } finally {
            b12BatchRunning = false
            android.util.Log.i("AtomRemoteB12", "BATCH_END status=$b12BatchStatus")
        }
    }
    val b12StartBatch: () -> Unit = {
        if (!b12BatchRunning && s10GetEnabled) {
            b21DateOnly = false
            b37ClockOnly = false
            b21DateRxPhoneTime = null
            b12Results = mapOf("Date" to "Queued", "Date format" to "Queued", "Device info" to "Queued")
            b15Readbacks = emptyMap()  // Never show the previous GET as current on refresh.
            b453LastDateRxMs = null; b453CompareAgeSec = null
            b12BatchNonce += 1
        }
    }

// B42: macOS x86_64 RemoteApi.Timecode.SourceGet has two matching READ callsites.
// Callsite 0x100049F24 (also 0x10004E3E0) builds request 0x044C / expected 0x844C.
// This is STATIC protocol evidence, not yet validated on Shogun Ultra AG2 hardware.
// One explicit on-tap read-only pilot; do not treat accepted queue as TX or raw RX as a source ID.
var b42SourceNonce by remember(bleAddress, linked, s05LiveGattAddress) { mutableIntStateOf(0) }
var b42PilotBusy by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(false) }
var b42SourceEvidence by remember(bleAddress, linked, s05LiveGattAddress) {
    mutableStateOf("Not tested: macOS 0x044C GET requires recorder verification")
}
val b42RequestSource: () -> Unit = {
    if (!b42PilotBusy && !b12BatchRunning && s10GetEnabled && s05AddressMatch) {
        b42SourceNonce += 1
    }
}
LaunchedEffect(b42SourceNonce, bleAddress, linked, s05LiveGattAddress) {
    if (b42SourceNonce < 1 || !s05AddressMatch || b12BatchRunning) return@LaunchedEffect
    b42PilotBusy = true
    b42SourceEvidence = "Checking read-only SourceGet transport (not confirmed)"
    val before = s11ReadLiveGattEvents()
    try {
        android.util.Log.i("AtomRemoteB42", "TC_SOURCE_PILOT_START cmd=0x044C expected=0x844C macStatic=true recorderVerified=false set=false")
        // No retries or other GETs: one-shot probe, fail-closed by existing Settings route.
        val queued = s12RequestSettingsGet(0x044C)
        android.util.Log.i("AtomRemoteB42", "TC_SOURCE_PILOT_REQUEST_ACCEPTED queued=$queued txNotImplied=true set=false")
        if (!queued) {
            b42SourceEvidence = "BLOCKED: existing Settings GET route did not accept 0x044C; NO TX proven"
            android.util.Log.i("AtomRemoteB42", "TC_SOURCE_PILOT_BLOCKED_NO_TX")
        } else {
            val rx = Regex("""REMOTE PAYLOAD local=(true|false) packetId=(\d+) payloadLen=(\d+) messageType=(\d+) data=([0-9A-Fa-f]*)""")
            var observed: String? = null
            for (window in 1..15) {
                kotlinx.coroutines.delay(400L)
                val fresh = b02EventDelta(before, s11ReadLiveGattEvents())
                val sample = fresh.mapNotNull { event ->
                    val m = rx.find(event) ?: return@mapNotNull null
                    val count = m.groupValues[3].toIntOrNull() ?: return@mapNotNull null
                    val code = m.groupValues[4].toIntOrNull() ?: return@mapNotNull null
                    val raw = m.groupValues[5]
                    if (m.groupValues[1] != "true" || code != 0x844C || count !in 1..256 || raw.length != count * 2) null
                    else "RX 0x844C bytes=$count raw=$raw"
                }.firstOrNull()
                if (sample != null) { observed = sample; break }
            }
            if (observed != null) {
                b42SourceEvidence = "$observed (unmapped, A/B required)"
                android.util.Log.i("AtomRemoteB42", "TC_SOURCE_PILOT_RX_OBSERVED $observed mapping=UNVERIFIED set=false")
            } else {
                b42SourceEvidence = "GET accepted; no matching 0x844C typed RX within capture window"
                android.util.Log.i("AtomRemoteB42", "TC_SOURCE_PILOT_NO_MATCHING_RX expected=0x844C noSet=true")
            }
        }
    } finally {
        b42PilotBusy = false
        android.util.Log.i("AtomRemoteB42", "TC_SOURCE_PILOT_END evidence=$b42SourceEvidence")
    }
}

// B44.1: independent one-shot candidate. 0x044C hardware returned a five-entry
// length-delimited payload on HDMI and Record Run; it appears list-like, not selected-state.
// x86_64 official macOS uses 0x044D in the adjacent read constructor.
// NO SET, NO enum assumption, NO writes except this read-only request.
// Shared in-flight guard, scoped to this matched recorder session.
var b4711Busy by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(false) }
var b4711LiveEvidence by remember(bleAddress, linked, s05LiveGattAddress) {
    mutableStateOf("No recent Timecode GET in this recorder session")
}
var b4714CycleStatus by remember(bleAddress, linked, s05LiveGattAddress) {
    mutableStateOf("Select a source on recorder, then tap Refresh Source")
}
var b44SourceNonce by remember(bleAddress, linked, s05LiveGattAddress) { mutableIntStateOf(0) }
var b44PilotBusy by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(false) }
var b44SourceEvidence by remember(bleAddress, linked, s05LiveGattAddress) {
    mutableStateOf("0x044D GET not tested; selected source UNVERIFIED")
}
val b44RequestSelectedSource: () -> Unit = {
    if (!b44PilotBusy && !b42PilotBusy && !b4711Busy && !b12BatchRunning &&
        s10GetEnabled && s05AddressMatch) b44SourceNonce += 1
}
LaunchedEffect(b44SourceNonce, bleAddress, linked, s05LiveGattAddress) {
    if (b44SourceNonce < 1 || !s05AddressMatch || b12BatchRunning || b42PilotBusy || b4711Busy) return@LaunchedEffect
    b44PilotBusy = true
    // Invalidates previous selections immediately; old ID 1 cannot be shown as current after new tap.
    b44SourceEvidence = "Refreshing selected Source GET; previous RX invalidated"
    b4711LiveEvidence = "Refreshing Timecode GET; previous RX invalidated"
    b4714CycleStatus = "Reading current source from recorder (0x044D then 0x044F)..."
    android.util.Log.i("AtomRemoteB4714", "SOURCE_CYCLE_START noSET=true selectedGET=0x044D timecodeGET=0x044F")
    val rx = Regex("""REMOTE PAYLOAD local=(true|false) packetId=(\d+) payloadLen=(\d+) messageType=(\d+) data=([0-9A-Fa-f]*)""")
    suspend fun awaitFresh(expected: Int, exactSize: Int, snapshot: List<String>): String? {
        for (window in 1..20) {
            kotlinx.coroutines.delay(300L)
            if (!s05AddressMatch) return null
            val fresh = b02EventDelta(snapshot, s11ReadLiveGattEvents())
            val sample = fresh.mapNotNull { line ->
                val m = rx.find(line) ?: return@mapNotNull null
                val count = m.groupValues[3].toIntOrNull() ?: return@mapNotNull null
                val code = m.groupValues[4].toIntOrNull() ?: return@mapNotNull null
                val raw = m.groupValues[5].uppercase(java.util.Locale.US)
                if (m.groupValues[1] != "true" || code != expected || count != exactSize || raw.length != count * 2) null
                else "RX 0x${expected.toString(16).uppercase()} packetId=${m.groupValues[2]} bytes=$count raw=$raw"
            }.firstOrNull()
            if (sample != null) return sample
        }
        return null
    }
    try {
        val sourceBefore = s11ReadLiveGattEvents()
        val sourceQueued = s12RequestSettingsGet(0x044D)
        android.util.Log.i("AtomRemoteB4714", "SELECTED_QUEUE accepted=$sourceQueued queuedNotRx=true")
        if (!sourceQueued) {
            b44SourceEvidence = "0x044D NOT QUEUED; no new recorder readback"
            b4714CycleStatus = "Refresh blocked by connection/MTU/transport. No source claim."
        } else {
            val source = awaitFresh(0x844D, 8, sourceBefore)
            b44SourceEvidence = source ?: "0x044D QUEUED but NO matching 0x844D RX; no cached source"
            android.util.Log.i("AtomRemoteB4714", "SELECTED_RX ${source ?: "NO_RX"}")
            // Different verified read path: 0x844E f6 physically followed HDMI=4/AirGlu=5.
            // Always query sequentially, never in parallel; no SET or auto retry.
            if (s05AddressMatch && s10GetEnabled) {
                val tcBefore = s11ReadLiveGattEvents()
                val tcQueued = s12RequestSettingsGet(0x044F)
                android.util.Log.i("AtomRemoteB4714", "TIMECODE_QUEUE accepted=$tcQueued queuedNotRx=true")
                val timecode = if (tcQueued) awaitFresh(0x844E, 12, tcBefore) else null
                b4711LiveEvidence = timecode ?: "0x044F ${if (tcQueued) "QUEUED, NO 0x844E RX" else "NOT QUEUED"}; no old TC source retained"
                android.util.Log.i("AtomRemoteB4714", "TIMECODE_RX ${timecode ?: "NO_RX"}")
                if (timecode != null) android.util.Log.i("AtomRemoteB4715",
                    "SOURCE_REFRESH_COUNTER_RX ${b4714TcRawFields(timecode)} snapshot=${b4715CounterSnapshot(timecode)} noSet=true")
            }
            val sourceId = b4714SelectedId(b44SourceEvidence)
            val tcId = b4714TcGetSourceId(b4711LiveEvidence)
            b4714CycleStatus = when {
                sourceId != null && tcId != null && sourceId == tcId -> "MATCH: ${b4714SourceName(sourceId)} (ID $sourceId)"
                sourceId != null && tcId != null -> "CONFLICT: Source GET ID $sourceId, Timecode GET ID $tcId; displaying TC GET provenance"
                tcId != null -> "SOURCE GET NO RX; Timecode GET confirms ${b4714SourceName(tcId)} (ID $tcId)"
                sourceId != null -> "SOURCE GET ONLY: ${b4714SourceName(sourceId)} (ID $sourceId); TC not confirmed"
                else -> "NO CORRELATED RECORDER SOURCE RX. Do not show an old source."
            }
        }
        android.util.Log.i("AtomRemoteB4714", "SOURCE_CYCLE_END selectedId=${b4714SelectedId(b44SourceEvidence) ?: "NONE"} tcId=${b4714TcGetSourceId(b4711LiveEvidence) ?: "NONE"} status=$b4714CycleStatus NO_SET=true")
    } finally { b44PilotBusy = false }
}

    // B47.11: Explicit Settings-only one-shot read pilot. The only allowed candidates
    // are macOS-linked 0x044F TimecodeGet and 0x0451 OffsetGet. No SET or automatic TX.
    var b4711Nonce by remember(bleAddress, linked, s05LiveGattAddress) { mutableIntStateOf(0) }
    var b4711Command by remember(bleAddress, linked, s05LiveGattAddress) { mutableIntStateOf(0) }
    var b4711LiveAttempted by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(false) }
    var b4711OffsetAttempted by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(false) }
    var b4711OffsetEvidence by remember(bleAddress, linked, s05LiveGattAddress) {
        mutableStateOf("NOT TESTED; Mac static opcode 0x0451, AG2 RX UNVERIFIED")
    }
    val b4711RequestOneShot: (Int) -> Unit = { command ->
        if ((command == 0x044F || command == 0x0451) &&
            !b4711Busy && !b12BatchRunning && !b42PilotBusy && !b44PilotBusy &&
            s10GetEnabled && s05AddressMatch &&
            true) {
            b4711Command = command
            b4711Nonce += 1
        }
    }
    LaunchedEffect(b4711Nonce, bleAddress, linked, s05LiveGattAddress) {
        if (b4711Nonce < 1 || !s05AddressMatch || b4711Busy || b12BatchRunning ||
            b42PilotBusy || b44PilotBusy || !s10GetEnabled) return@LaunchedEffect
        val command = b4711Command
        if (command != 0x044F && command != 0x0451) return@LaunchedEffect
        b4711Busy = true
        val isLive = command == 0x044F
        val expectedRx = if (isLive) 0x844E else 0x844F
        val label = if (isLive) "TIMECODE" else "OFFSET"
        val before = s11ReadLiveGattEvents()
        try {
            android.util.Log.i("AtomRemoteB4711", "PILOT_START label=$label cmd=0x${command.toString(16).uppercase()} macMethodVerified=true expectedRxCandidate=0x${expectedRx.toString(16).uppercase()} noSet=true oneShot=true")
            val queued = s12RequestSettingsGet(command)
            android.util.Log.i("AtomRemoteB4711", "PILOT_QUEUE label=$label accepted=$queued queueIsNotWireRx=true")
            if (!queued) {
                if (isLive) b4711LiveEvidence = "NOT QUEUED (busy/MTU/route); no recorder RX. Retry only after checking transport."
                else b4711OffsetEvidence = "NOT QUEUED (busy/MTU/route); no recorder RX. Retry only after checking transport."
                android.util.Log.i("AtomRemoteB4711", "PILOT_BLOCKED label=$label NO_CONFIRMED_TX")
            } else {
                if (isLive) b4711LiveAttempted = true else b4711OffsetAttempted = true
                val rx = Regex("""REMOTE PAYLOAD local=(true|false) packetId=(\d+) payloadLen=(\d+) messageType=(\d+) data=([0-9A-Fa-f]*)""")
                var found: String? = null
                for (window in 1..18) {
                    kotlinx.coroutines.delay(400L)
                    if (!s05AddressMatch) break
                    val fresh = b02EventDelta(before, s11ReadLiveGattEvents())
                    val sample = fresh.mapNotNull { event ->
                        val m = rx.find(event) ?: return@mapNotNull null
                        val count = m.groupValues[3].toIntOrNull() ?: return@mapNotNull null
                        val kind = m.groupValues[4].toIntOrNull() ?: return@mapNotNull null
                        val hex = m.groupValues[5].uppercase()
                        if (kind != expectedRx || count !in 1..512 || hex.length != count * 2) null
                        else "CANDIDATE RX type=0x${kind.toString(16).uppercase()} local=${m.groupValues[1]} packetId=${m.groupValues[2]} bytes=$count raw=$hex; NOT TYPED DECODED"
                    }.firstOrNull()
                    if (sample != null) { found = sample; break }
                }
                val result = found ?: "QUEUED; NO MATCHING CANDIDATE RX in UI window. Inspect full raw log."
                if (isLive) b4711LiveEvidence = result else b4711OffsetEvidence = result
                android.util.Log.i("AtomRemoteB4715", if (isLive)
                    "TC_COUNTER_RX ${b4714TcRawFields(result)} snapshot=${b4715CounterSnapshot(result)} noSet=true"
                    else "OFFSET_TYPED_RX ${b4715OffsetReadback(result)} noSet=true")
                android.util.Log.i("AtomRemoteB4711", "PILOT_FINISH label=$label ${if (found != null) "CANDIDATE_RX" else "NO_RX"} evidence=$result noSET=true")
            }
        } finally {
            b4711Busy = false
        }
    }

    // B21: faster READ-ONLY time refresh. Exactly one proven DateGet, not the 3-item batch.
    val b21RefreshTime: () -> Unit = {
        if (!b12BatchRunning && s10GetEnabled) {
            b21DateOnly = true
            b37ClockOnly = false
            b21DateRxPhoneTime = null
            b12Results = b12Results + ("Date" to "Waiting for fresh 0x84B5")
            b15Readbacks = b15Readbacks - "dateTime"
            b12BatchNonce += 1
        }
    }

    // B37 CLOCK GET refresh: DateGet and DateFormatGet are sent sequentially.
    // A physical switch to 24H is reflected ONLY after a fresh 0x84B7 f2=0 RX.
    // A timezone GET is NOT available in this verified BLE mapping.
    val b37RefreshClock: () -> Unit = {
        if (!b12BatchRunning && s10GetEnabled) {
            b21DateOnly = false
            b37ClockOnly = true
            b21DateRxPhoneTime = null
            b12Results = b12Results + ("Date" to "Refreshing") + ("Date format" to "Refreshing")
            b15Readbacks = b15Readbacks - "dateTime" - "dateFormatRaw"
            b453LastDateRxMs = null; b453CompareAgeSec = null
            b12BatchNonce += 1
            android.util.Log.i("AtomRemoteB37", "CLOCK_REFRESH_DATE_AND_FORMAT request=0x04B9,0x04BB selectedTimeZoneGet=UNMAPPED noSET=true")
        }
    }
    LaunchedEffect(b15Readbacks["dateTime"], b16Format?.second, s05AddressMatch) {
        if (s05AddressMatch) android.util.Log.i("AtomRemoteB37",
            "CLOCK_TYPED_RX date=${b15Readbacks["dateTime"] ?: "NO_RX"} formatF2=${b16Format?.second ?: -1} " +
            "format=${when (b16Format?.second) { 0 -> "24H"; 1 -> "12H"; else -> "NOT_VERIFIED" }} " +
            "selectedZoneGet=NOT_VERIFIED recorderSet=false")
    }
    // B05: every recognized GET/SET is represented as a paired proof status;
    // all SET wire routes remain BLOCKED until both sides are recorder-verified.
    val b05MatchedPeer = s05AddressMatch
    // B36: reset a sheet's draft whenever it is reopened, then seed from typed fresh RX.
    LaunchedEffect(sheetKey) {
        when (sheetKey) {
            "date" -> b35DraftDate = null
            "time" -> { b35DraftHour = -1; b35DraftMinute = -1; b35DraftSecond = -1 }
            "dateformat" -> b35DraftFormat = -1
            "timezone" -> b35DraftZoneIndex = -1
        }
        b35Prepared = null
    }
    // B40: opening the Time editor explicitly refreshes the two proven clock
    // getters, independent of old cached values. This is ONE bounded batch.
    LaunchedEffect(sheetKey, bleAddress, linked, s05AddressMatch) {
        if (sheetKey == "time" && s05AddressMatch && !b12BatchRunning) {
            android.util.Log.i("AtomRemoteB40", "TIME_SHEET_OPEN_CLOCK_REFRESH DateGet+DateFormatGet noZoneGuess=true")
            b37RefreshClock()
        }
    }
    // Re-check when an async DateGet or DateFormatGet response arrives AFTER sheet open.
    // Never seed from phone clock and never infer a recorder-selected zone.
    LaunchedEffect(sheetKey, bleAddress, linked, b15Readbacks["dateTime"], b16Format?.second, b31CandidateId) {
        when (sheetKey) {
            "date" -> if (b35DraftDate == null) {
                b35DraftDate = b35DateTimeFromGet(b15Readbacks["dateTime"], b31CandidateId)?.toLocalDate()
            }
            "time" -> if (b35DraftHour < 0) {
                val local = b35DateTimeFromGet(b15Readbacks["dateTime"], b31CandidateId)
                if (b31CandidateId != null && local != null) {
                    b35DraftHour = local.hour
                    b35DraftMinute = local.minute
                    b35DraftSecond = local.second
                }
            }
            "dateformat" -> if (b35DraftFormat < 0) {
                b35DraftFormat = b16Format?.second?.takeIf { it == 0 || it == 1 } ?: -1
            }
            "timezone" -> if (b35DraftZoneIndex < 0 && b31CandidateId != null) {
                b35DraftZoneIndex = b31CandidateId!!
            }
        }
        b35Prepared = null
    }

    LaunchedEffect(b15Readbacks["dateTime"], b16Format?.second, b31CandidateId, s05AddressMatch) {
        if (s05AddressMatch) android.util.Log.i("AtomRemoteB36",
            "GET_STATE date=${if (b15Readbacks["dateTime"] == null) "NONE" else "RX_0x84B5"} " +
            "format=${b16Format?.second ?: -1} zoneSource=USER_MATCHED_REFERENCE " +
            "zoneIndex=${b31CandidateId ?: -1} timePreview=$b33TimeDisplay " +
            "timeZoneGet=false recorderSet=false")
    }
    // B47.1 UI-only phase. Date/Format GET, DeviceInfo bootstrap, and selected
    // source 0x044D are the ONLY connected read paths surfaced here.
    // W-Fi and fan method names are static: no unverified on-wire calls enabled.
    // B47.71: DateSet is accepted only after a new RX dated later than the SET attempt.
    // A successful queue is NOT a recorder ACK. Device clock will continue advancing.
    LaunchedEffect(b15Readbacks["dateTime"], b453LastDateRxMs, b4771PendingUtc) {
        val pending = b4771PendingUtc
        val rxAt = b453LastDateRxMs
        if (pending != null && rxAt != null && rxAt > b4771SentMs) {
            val actual = b15Readbacks["dateTime"]
            val delta = try {
                val fmt = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", java.util.Locale.US)
                val start = LocalDateTime.parse(pending, fmt)
                val observed = LocalDateTime.parse(actual ?: "", fmt)
                kotlin.math.abs(java.time.Duration.between(start, observed).seconds -
                    ((rxAt - b4771SentMs) / 1000L))
            } catch (_: Exception) { Long.MAX_VALUE }
            b4771MatchGet = delta <= 6L
            b4771PhysicalConfirmed = false
            b4771Result = if (b4771MatchGet)
                "FRESH DATE GET WITHIN 6s OF TARGET: check physical recorder clock; ACK not decoded."
            else "POST-SET GET DIFFERS (delta ${if (delta == Long.MAX_VALUE) "unknown" else "$delta s"}); no success claimed."
            android.util.Log.i("AtomRemoteB4771", "DATESET_POST_GET matched=${b4771MatchGet} deltaSeconds=$delta raw=$actual ack=UNVERIFIED")
            b4771PendingUtc = null
        }
    }

    // B36: Preview and drafts are never authoritative SET readbacks.
    // SETTINGS S14: compact Input/Output-style option lists and Material3 sheets.
    // Strictly presentation only: S05-S11 GET evidence pathway remains unchanged;
    // drafts are LOCAL and all unsupported recorder setters stay disabled.
    var settingsTab by remember { mutableStateOf("date") }
    // B47.62.1 compile-only: observe tab after the local tab state is declared.
    LaunchedEffect(settingsTab, bleAddress, linked, s05AddressMatch) {
        while (settingsTab == "date" && linked && s05AddressMatch) {
            b4762ClockTickMs = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(1000L) // display-only; no transport calls
        }
    }
    val settingsTabs = listOf(
        "info" to "Info",
        "date" to "Date/Time",
        "timecode" to "Timecode",
        "general" to "General"
    )
    // B47.24 Info: scoped one-at-a-time GET, one automatic no-RX retry,
    // and five raw typed DeviceInfo fields. Never conflate pairing BCID with device ID.
    val b4723InfoRefreshRequests = remember(bleAddress, linked, s05LiveGattAddress) {
        kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    }
    var b4723InfoBusy by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(false) }
    var b4723InfoStatus by remember(bleAddress, linked, s05LiveGattAddress) {
        mutableStateOf("Open Info with a linked recorder for GET")
    }
    var b4723InfoFields by remember(bleAddress, linked, s05LiveGattAddress) {
        mutableStateOf(emptyMap<String, String>())
    }
    var b4723InfoRaw by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf<String?>(null) }
    var b4723InfoRxType by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf<String?>(null) }
    LaunchedEffect(settingsTab, linked, s05AddressMatch, s10GetEnabled, bleAddress, s05LiveGattAddress) {
        if (settingsTab != "info" || !linked || !s05AddressMatch || !s10GetEnabled) {
            b4723InfoFields = emptyMap()
            b4723InfoRaw = null
            b4723InfoRxType = null
            b4723InfoStatus = "Info GET not active for selected recorder"
            return@LaunchedEffect
        }
        var first = true
        while (settingsTab == "info" && linked && s05AddressMatch && s10GetEnabled) {
            if (first) { kotlinx.coroutines.delay(3500L); first = false }
            else b4723InfoRefreshRequests.receive()
            if (settingsTab != "info" || !s05AddressMatch || !s10GetEnabled) break
            b4723InfoBusy = true
            b4723InfoFields = emptyMap()
            b4723InfoRaw = null
            b4723InfoRxType = null
            b4723InfoStatus = "Getting DeviceInfo from recorder"
            android.util.Log.i("AtomRemoteB4737", "INFO_GET_START scope=info maxTx=2 source=0x04B0 expected=0x84B0 noSET=true")
            try {
                var completed = false
                var txCount = 0
                // First accepted request, then at most one read-only re-request if no typed RX.
                for (cycle in 1..2) {
                    if (settingsTab != "info" || !linked || !s05AddressMatch || !s10GetEnabled) break
                    var queued = false
                    var before = s11ReadLiveGattEvents()
                    for (queueTry in 1..12) {
                        if (settingsTab != "info" || !s05AddressMatch) break
                        if (b12BatchRunning) { kotlinx.coroutines.delay(750L); continue }
                        before = s11ReadLiveGattEvents()
                        queued = s12RequestSettingsGet(0x04B0)
                        android.util.Log.i("AtomRemoteB4724", "INFO_GET_QUEUE cycle=$cycle try=$queueTry accepted=$queued noSET=true")
                        if (queued) { txCount += 1; break }
                        kotlinx.coroutines.delay(650L)
                    }
                    if (!queued) {
                        b4723InfoStatus = "GET queue busy or recorder not ready. Tap Refresh"
                        android.util.Log.i("AtomRemoteB4737", "INFO_GET_QUEUE_BUSY cycle=$cycle status=BLOCKED_NO_TX retryEligible=${cycle == 1} noSET=true")
                        if (cycle == 1 && settingsTab == "info" && linked && s05AddressMatch && s10GetEnabled) {
                            kotlinx.coroutines.delay(1200L)
                            continue
                        }
                        break
                    }
                    val pattern = Regex("""REMOTE PAYLOAD local=(true|false) packetId=(\d+) payloadLen=(\d+) messageType=(\d+) data=([0-9A-Fa-f]*)""")
                    var previous = before
                    for (sample in 1..24) { // 6 seconds per accepted GET; bounded, no SET.
                        kotlinx.coroutines.delay(250L)
                        if (settingsTab != "info" || !s05AddressMatch) break
                        val latest = s11ReadLiveGattEvents()
                        val fresh = b02EventDelta(previous, latest)
                        previous = latest
                        for (event in fresh) {
                            val match = pattern.find(event) ?: continue
                            if (match.groupValues[1] != "true") continue
                            val count = match.groupValues[3].toIntOrNull() ?: continue
                            val kind = match.groupValues[4].toIntOrNull() ?: continue
                            val raw = match.groupValues[5]
                            if (kind != 0x84B0 || count !in 1..2048 || raw.length != count * 2) continue
                            val decoded = SettingsB15TypedRx.decode(0x04B0, raw)
                            b4723InfoRaw = raw
                            b4723InfoRxType = "0x84B0"
                            b4723InfoFields = decoded ?: emptyMap()
                            completed = true
                            b4723InfoStatus = if (!decoded.isNullOrEmpty())
                                "Fresh recorder DeviceInfo: ${decoded.size} typed fields"
                                else "Fresh DeviceInfo raw response: field parser unverified"
                            android.util.Log.i("AtomRemoteB4724", "INFO_GET_RX cycle=$cycle typed=true parsed=${!decoded.isNullOrEmpty()} bytes=$count fieldCount=${decoded?.size ?: 0} noSET=true")
                            break
                        }
                        if (completed) break
                    }
                    if (completed) break
                    android.util.Log.i("AtomRemoteB4724", "INFO_GET_ATTEMPT_END cycle=$cycle status=NO_TYPED_RX")
                    if (cycle == 1 && settingsTab == "info" && linked && s05AddressMatch && s10GetEnabled) {
                        b4723InfoStatus = "No first reply. One bounded automatic DeviceInfo retry"
                        android.util.Log.i("AtomRemoteB4724", "INFO_GET_AUTO_RETRY reason=NO_TYPED_RX noSET=true")
                        kotlinx.coroutines.delay(650L)
                    }
                }
                if (!completed && txCount > 0) b4723InfoStatus = "DeviceInfo requested; no fresh 0x84B0 RX after bounded attempts"
                android.util.Log.i("AtomRemoteB4737", "INFO_GET_END status=${if (completed) "TYPED_RX" else "PARTIAL_NO_TYPED_RX"} txCount=$txCount noSET=true")
            } finally { b4723InfoBusy = false }
        }
    }

    // B47.19 consolidated Phase 1. Entering Timecode or tapping one Refresh
    // runs TC(0x044F), Source(0x044D), Offset(0x0451) sequentially. One modest
    // TC-only periodic resync prevents unbounded local clock drift; it is NOT
    // frame-accurate transport and cannot start recorder playback or recording.
    // B47.21: a single UI GET actor. Manual requests are conflated; they never cancel
    // an in-flight Source/Timecode/Offset read. GET only.
    val b4721RefreshRequests = remember(bleAddress, linked, s05LiveGattAddress) {
        kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    }
    var b4719Busy by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(false) }
    var b4719Status by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf("GET pending") }
    // B47.67: only a *complete* fresh triple GET arms an experimental offset test.
    var b4767TripleGetAtMs by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(0L) }
    var b4767Consent by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(false) }
    var b4767ArmedTarget by remember(bleAddress, linked, s05LiveGattAddress) { mutableIntStateOf(-1) }
    var b4769DraftOffset by remember(bleAddress, linked, s05LiveGattAddress) { mutableIntStateOf(1) }
    var b4769DraftDirty by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(false) }
    var b4769DraftInitialized by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(false) }
    // B47.68: per-command post-SET GET evidence; same recorder session only.
    var b4768PendingTarget by remember(bleAddress, linked, s05LiveGattAddress) { mutableIntStateOf(-1) }
    var b4768PendingBefore by remember(bleAddress, linked, s05LiveGattAddress) { mutableIntStateOf(0) }
    var b4768SentAtMs by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(0L) }
    var b4768PostGetMatched by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(false) }
    var b4768PhysicalConfirmed by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(false) }
    var b4767Result by remember(bleAddress, linked, s05LiveGattAddress) {
        mutableStateOf("AirGlu Offset: read recorder, choose 0..20, then one confirmed SET + fresh GET")
    }
    // B47.85: one visible Apply + mandatory modal confirmation; no automatic TX.
    var b4785ConfirmTarget by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf<Int?>(null) }
    var b4785UiStatus by remember(bleAddress, linked, s05LiveGattAddress) {
        mutableStateOf("Choose a different Offset, tap APPLY; a separate confirmation is required")
    }
    // B47.86: one deliberate Apply starts a NEW complete GET first. No SET happens
    // until the operator confirms the verified baseline in a separate dialog.
    var b4786PrepareTarget by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf<Int?>(null) }
    var b4786PrepareStartMs by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(0L) }
    var b4719AnchorMs by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf<Long?>(null) }
    var b4719TickMs by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(0L) }
    var b4719Advances by remember(bleAddress, linked, s05LiveGattAddress) { mutableStateOf(false) }
    LaunchedEffect(settingsTab, linked, s05AddressMatch, s10GetEnabled, bleAddress, s05LiveGattAddress) {
        // Retain the last typed GET snapshot when navigating between Settings tabs.
        // It belongs to the current recorder only (all evidence state is device-scoped).
        // The Timecode actor always refreshes on re-entry. Never present this cached
        // snapshot as a *fresh* GET after leaving the page or disconnecting.
        if (!linked || !s05AddressMatch || !s10GetEnabled) {
            b4719AnchorMs = null
            b4719Advances = false
            b4719Status = "Recorder disconnected; refresh GET after reconnect"
            b4711LiveEvidence = "No current Timecode GET"
            b44SourceEvidence = "No current Source GET"
            b4711OffsetEvidence = "No current Offset GET"
        }
    }
    LaunchedEffect(settingsTab, linked, s05AddressMatch) {
        while (settingsTab == "timecode" && linked && s05AddressMatch) {
            b4719TickMs = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(100L) // display-only ticker; never sends BLE
        }
    }
    LaunchedEffect(settingsTab, linked, s05AddressMatch, s10GetEnabled, bleAddress, s05LiveGattAddress) {
        if (settingsTab != "timecode" || !linked || !s05AddressMatch || !s10GetEnabled) return@LaunchedEffect
        // Invalidate old queued manual intent after recorder/tab re-entry.
        while (b4721RefreshRequests.tryReceive().isSuccess) { }
        val rx = Regex("""REMOTE PAYLOAD local=(true|false) packetId=(\d+) payloadLen=(\d+) messageType=(\d+) data=([0-9A-Fa-f]*)""")
        suspend fun read(command: Int, expected: Int, label: String): Pair<String?, Long?> {
            if (!s05AddressMatch || settingsTab != "timecode") return null to null
            var queued = false
            var before: List<String> = emptyList()
            var startMs = 0L
            // Rejected queue admission does not imply any TX. Try once more after
            // a bounded settle; never blindly retry a GET whose admission succeeded.
            for (attempt in 1..2) {
                if (!s05AddressMatch || settingsTab != "timecode") return null to null
                before = s11ReadLiveGattEvents()
                startMs = android.os.SystemClock.elapsedRealtime()
                queued = s12RequestSettingsGet(command)
                android.util.Log.i("AtomRemoteB4719", "GET_QUEUE label=$label command=0x${command.toString(16)} accepted=$queued noSET=true")
                if (queued) break
                android.util.Log.i("AtomRemoteB4721", "GET_REJECTED label=$label attempt=$attempt noTXproven=true")
                if (attempt == 1) kotlinx.coroutines.delay(650L)
            }
            if (!queued) return null to null
            repeat(18) {
                kotlinx.coroutines.delay(250L)
                if (!s05AddressMatch || settingsTab != "timecode") return null to null
                val sample = b02EventDelta(before, s11ReadLiveGattEvents()).mapNotNull { line ->
                    val m = rx.find(line) ?: return@mapNotNull null
                    val size = m.groupValues[3].toIntOrNull() ?: return@mapNotNull null
                    val type = m.groupValues[4].toIntOrNull() ?: return@mapNotNull null
                    val raw = m.groupValues[5].uppercase(java.util.Locale.US)
                    if (m.groupValues[1] != "true" || type != expected || size !in 1..64 || raw.length != size * 2) null
                    else "RX 0x${expected.toString(16).uppercase(java.util.Locale.US)} packetId=${m.groupValues[2]} bytes=$size raw=$raw"
                }.firstOrNull()
                if (sample != null) {
                    val receiveMs = android.os.SystemClock.elapsedRealtime()
                    android.util.Log.i("AtomRemoteB4719", "GET_RX label=$label rttMs=${receiveMs-startMs} $sample")
                    return sample to ((startMs + receiveMs) / 2L)
                }
            }
            android.util.Log.i("AtomRemoteB4719", "GET_NO_RX label=$label expected=0x${expected.toString(16)}")
            return null to null
        }
        suspend fun takeTcSample(previous: String): Boolean {
            b4719Status = "Reading recorder Timecode 0x044F"
            val (tc, midpoint) = read(0x044F, 0x844E, "Timecode")
            if (tc == null || midpoint == null || b4715TimecodeFields(tc) == null) {
                b4719Status = "Timecode GET: no verified RX; no local timer started"
                return false
            }
            val mode = b4714TcGetSourceId(tc)
            b4719Advances = when (mode) {
                2, 5 -> true // source-driven seconds estimate; actual frame-rate is not proven
                else -> b4719TimecodeMoved(previous, tc)
            }
            b4711LiveEvidence = tc
            b4719AnchorMs = midpoint
            b4719Status = "Recorder GET confirmed; ${if (b4719Advances) "local seconds estimated" else "paused/unknown"}; frame rate unverified"
            android.util.Log.i("AtomRemoteB4719", "CLOCK_SEED raw=${b4715CounterSnapshot(tc)} f6=${mode ?: -1} estimated=$b4719Advances frameRate=UNKNOWN dropFrame=UNKNOWN noSET=true")
            return true
        }
        suspend fun getAllOnce(reason: String): Boolean {
            var idleAttempts = 0
            while ((b4719Busy || b12BatchRunning || b42PilotBusy || b44PilotBusy || b4711Busy)
                   && settingsTab == "timecode" && linked && s05AddressMatch && idleAttempts < 20) {
                idleAttempts++
                b4719Status = "Waiting for recorder GET queue ($idleAttempts/20)"
                kotlinx.coroutines.delay(450L)
            }
            if (settingsTab != "timecode" || !linked || !s05AddressMatch || !s10GetEnabled) return false
            if (b4719Busy || b12BatchRunning || b42PilotBusy || b44PilotBusy || b4711Busy) {
                b4719Status = "Recorder queue busy. Refresh when ready."
                android.util.Log.i("AtomRemoteB4721", "GET_ALL_SKIPPED reason=$reason busy=true noSET=true")
                return false
            }
            b4719Busy = true
            b4719AnchorMs = null; b4719Advances = false
            b4711LiveEvidence = "Waiting for fresh 0x844E"
            b44SourceEvidence = "Waiting for fresh 0x844D"
            b4711OffsetEvidence = "Waiting for fresh 0x844F"
            try {
                android.util.Log.i("AtomRemoteB4721", "GET_ALL_START reason=$reason singleFlight=true source=0x044D tc=0x044F offset=0x0451 noSET=true")
                val first = takeTcSample("")
                b4719Status = "Reading selected source 0x044D"
                val (source, _) = read(0x044D, 0x844D, "SelectedSource")
                b44SourceEvidence = if (source != null && b4714SelectedId(source) != null) source else "No correlated 0x844D GET"
                b4719Status = "Reading recorder Offset 0x0451"
                val (offset, _) = read(0x0451, 0x844F, "Offset")
                b4711OffsetEvidence = offset ?: "No correlated 0x844F Offset GET"
                if (!first) {
                    for (attempt in 1..2) {
                        if (settingsTab != "timecode" || !linked || !s05AddressMatch) break
                        b4719Status = "Retrying initial Timecode GET ($attempt/2)"
                        kotlinx.coroutines.delay(1200L)
                        val ok = takeTcSample("")
                        android.util.Log.i("AtomRemoteB4721", "AUTO_TC_RETRY attempt=$attempt ${if (ok) "RX_CONFIRMED" else "NO_RX"} noSET=true")
                        if (ok) break
                    }
                } else if (b4714TcGetSourceId(b4711LiveEvidence) in listOf(0, 1, 4)) {
                    val previous = b4711LiveEvidence
                    takeTcSample(previous)
                }
                val tcId = b4714TcGetSourceId(b4711LiveEvidence)
                val selectedId = b4714SelectedId(b44SourceEvidence)
                b4714CycleStatus = when {
                    selectedId != null && tcId != null && selectedId == tcId -> "MATCH: ${b4714SourceName(tcId)} (ID $tcId)"
                    tcId != null && selectedId != null -> "MISMATCH Source GET=$selectedId TC=$tcId; TC f6 preferred"
                    tcId != null -> "TC GET confirms ${b4714SourceName(tcId)} (ID $tcId); Source GET no RX"
                    selectedId != null -> "Source GET only ${b4714SourceName(selectedId)}; TC GET missing"
                    else -> "No correlated Source or Timecode GET"
                }
                val b4767Offset = offset?.let { b4715DecodeWire(it, "0x844F") }
                    ?.takeIf { it.keys == setOf(1) }?.get(1)
                b4767TripleGetAtMs = if (tcId == 5 && selectedId == 5 &&
                    b4767Offset != null && b4767Offset in 0L..20L)
                    android.os.SystemClock.elapsedRealtime() else 0L
                b4767ArmedTarget = -1
                if (b4767TripleGetAtMs > 0L && (!b4769DraftInitialized || !b4769DraftDirty)) {
                    b4769DraftOffset = b4767Offset!!.toInt()
                    b4769DraftInitialized = true
                }
                val complete = tcId != null && selectedId != null && offset != null
                // A newer complete triple GET can support readback, but no explicit ACK is inferred.
                if (b4768PendingTarget in 0..20 && b4768SentAtMs > 0L &&
                    b4767TripleGetAtMs > b4768SentAtMs) {
                    val expected = b4768PendingTarget
                    val got = b4767Offset?.toInt()
                    b4768PostGetMatched = complete && tcId == 5 && selectedId == 5 && got == expected
                    b4768PhysicalConfirmed = false
                    b4767Result = if (b4768PostGetMatched)
                        "POST-SET RECORDER GET MATCH: $b4768PendingBefore to $expected; verify physical screen. ACK not separately decoded."
                    else "POST-SET GET MISMATCH: target=$expected read=${got ?: "NO_RX"}; no success. Check physical recorder, no automatic retry."
                    android.util.Log.i("AtomRemoteB4768", "AIRGLU_POST_SET_READBACK target=$expected prior=$b4768PendingBefore read=$got source=$selectedId tcSource=$tcId matched=$b4768PostGetMatched ack=UNVERIFIED")
                    if (b4768PostGetMatched) {
                        b4769DraftOffset = expected
                        b4769DraftDirty = false
                    }
                    b4768PendingTarget = -1
                    b4768SentAtMs = 0L
                }
                b4719Status = if (complete) "GET ALL complete: Timecode, Source, Offset RX confirmed."
                    else "GET ALL partial: TC=${tcId != null}, Source=${selectedId != null}, Offset=${offset != null}; refresh when idle."
                android.util.Log.i("AtomRemoteB4721", "GET_ALL_END reason=$reason complete=$complete sourceRx=${selectedId != null} timecodeRx=${tcId != null} offsetRx=${offset != null} noSET=true")
                return true
            } finally { b4719Busy = false }
        }
        try {
            var attempted = getAllOnce("entry")
            if (!attempted && settingsTab == "timecode" && linked && s05AddressMatch && s10GetEnabled) {
                kotlinx.coroutines.delay(1250L)
                attempted = getAllOnce("entry-busy-retry")
            }
            // The same single worker owns manual Refresh All and bounded periodic
            // re-anchoring. A manual request cannot cancel a partially completed GET ALL.
            while (settingsTab == "timecode" && linked && s05AddressMatch && s10GetEnabled) {
                val manual = kotlinx.coroutines.withTimeoutOrNull(20000L) {
                    b4721RefreshRequests.receive()
                    true
                } == true
                if (settingsTab != "timecode" || !linked || !s05AddressMatch || !s10GetEnabled) break
                if (manual) {
                    android.util.Log.i("AtomRemoteB4721", "MANUAL_REFRESH_DEQUEUED singleFlight=true")
                    getAllOnce("manual")
                } else if (!b12BatchRunning && !b42PilotBusy && !b44PilotBusy && !b4711Busy) {
                    b4719Busy = true
                    try {
                        val previous = b4711LiveEvidence
                        android.util.Log.i("AtomRemoteB4721", "PERIODIC_RESYNC_START")
                        val ok = takeTcSample(previous)
                        if (!ok) { b4719AnchorMs = null; b4719Advances = false }
                        else if (b4714TcGetSourceId(previous) != b4714TcGetSourceId(b4711LiveEvidence)) {
                            b44SourceEvidence = "Source changed since grouped GET"
                            b4711OffsetEvidence = "Source changed: refresh all for Offset"
                            b4714CycleStatus = "TC source f6 changed; use REFRESH ALL"
                        }
                        android.util.Log.i("AtomRemoteB4721", "PERIODIC_RESYNC_END tcRx=$ok noSET=true")
                    } finally { b4719Busy = false }
                }
            }
        } finally { b4719Busy = false }
    }
    // B47.86: evaluate Apply preparation ONLY after a new *completed* grouped GET.
    // This is preparation for an explicit confirmation dialog, NOT an effect-driven TX.
    LaunchedEffect(b4767TripleGetAtMs, b4719Busy, b12BatchRunning, b4786PrepareTarget, activeSheet) {
        val selected = b4786PrepareTarget ?: return@LaunchedEffect
        if (settingsTab != "timecode" || activeSheet != "b4770offset") {
            b4786PrepareTarget = null
            b4785UiStatus = "Apply preparation cancelled on navigation. No TX."
            return@LaunchedEffect
        }
        if (b4719Busy || b12BatchRunning || b4767TripleGetAtMs <= b4786PrepareStartMs) {
            return@LaunchedEffect
        }
        val selectedId = b4714SelectedId(b44SourceEvidence)
        val tcSource = b4714TcGetSourceId(b4711LiveEvidence)
        val fromGet = if (selectedId == 5 && tcSource == 5)
            b4715DecodeWire(b4711OffsetEvidence, "0x844F")
                ?.takeIf { it.keys == setOf(1) }?.get(1)?.toInt()
        else null
        val good = linked && s05AddressMatch && s10GetEnabled &&
            selectedId == 5 && tcSource == 5 && fromGet in 0..20 &&
            selected in 0..20 && selected != fromGet && b4768PendingTarget == -1
        b4786PrepareTarget = null
        if (good) {
            b4785ConfirmTarget = selected
            b4785UiStatus = "Fresh GET $fromGet, AirGlu Source 5. Confirm $fromGet to $selected; no TX yet."
            android.util.Log.i("AtomRemoteB4786", "OFFSET_PREPARE_READY from=$fromGet target=$selected src=$selectedId tcSrc=$tcSource noTX=true")
        } else {
            b4785UiStatus = "NO TX: fresh GET source/offset mismatch or pending SET. " +
                "Source=$selectedId TC=$tcSource Offset=${fromGet ?: "missing"}; refresh if needed."
            android.util.Log.w("AtomRemoteB4786", "OFFSET_PREPARE_REJECT from=$fromGet target=$selected src=$selectedId tcSrc=$tcSource noTX=true")
        }
    }
    // B37: on Time & Date entry, fetch Date and Time Format together (not one stale format).
    // Strict one-shot, no polling and no recorder SET.
    LaunchedEffect(settingsTab, s05AddressMatch, s10GetEnabled) {
        if (settingsTab == "date" && s05AddressMatch && s10GetEnabled && !b12BatchRunning) {
            b37RefreshClock()
        }
    }
    // B47.55: operator-facing Settings UI. Keep protocol evidence in source/logs,
    // not on the main screen. Only verified recorder GET values are presented.
    S02Group("RECORDER SETTINGS", "Read confirmed values from the connected recorder") {
        S02Info("Connection", if (linked && s05AddressMatch) "Connected" else "Connect recorder first")
        S02Notice("DO: use Refresh in Info, Date/Time or Timecode. Values appear only after recorder GET. " +
            "DON'T: treat local previews or unavailable settings as applied. To change an unsupported setting, use the recorder screen.")
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Color(0xFF27292D), RoundedCornerShape(13.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        settingsTabs.forEach { (tabKey, tabLabel) ->
            val selected = settingsTab == tabKey
            Box(
                modifier = Modifier.weight(1f)
                    .background(if (selected) Color(0xFF414247) else Color.Transparent, RoundedCornerShape(9.dp))
                    .clickable { settingsTab = tabKey }
                    .padding(horizontal = 2.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(tabLabel, color = if (selected) Color.White else S02Muted,
                    fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1)
            }
        }
    }

    when (settingsTab) {
        "info" -> {
            S02Group("RECORDER IDENTITY", "Fresh DeviceInfo 0x84B0 GET  |  Read only") {
                val fw2 = b4723InfoFields["deviceField2"]
                val fw5 = b4723InfoFields["deviceField5"]
                S02Info("DeviceInfo status", b4723InfoStatus)
                S02Info("Model", b4723InfoFields["model"] ?: "Not verified")
                S02Info("Device ID", b4723InfoFields["deviceId"] ?: "Not verified")
                S02Info("Firmware", if (!fw2.isNullOrBlank() && fw2 == fw5) fw2 else "Not verified: field 2 / 5 mismatch or missing")
                Button(onClick = { b4723InfoRefreshRequests.trySend(Unit) },
                    enabled = linked && s05AddressMatch && s10GetEnabled && !b4723InfoBusy,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (b4723InfoBusy) "READING..." else "GET RECORDER DEVICE INFO")
                }
                S02Notice("Tap an individual row to view its source, fresh GET availability and SET status. Values from Android / BLE are explicitly marked as app metadata.")
            }
            S02Group("DEVICE INFO FIELDS", "Recorder GET fields; each row opens its own bottom sheet") {
                b4753Catalog.filter { it.section == "Info" && it.id in 1..5 }.forEach { field ->
                    val value = b4772InfoWireValue(field.id, b4723InfoFields) ?: "Not verified"
                    S02RowItem(field.name, value,
                        if (field.id == 5) "0x84B0 raw field, meaning unverified" else "Recorder 0x84B0 GET | Read only") {
                        activeSheet = "b4772field:${field.id}"
                        android.util.Log.i("AtomRemoteB4772", "SHEET_OPEN info_field=${field.id} noSET=true")
                    }
                }
            }
            S02Group("APP / CONNECTION INFO", "Not recorder DeviceInfo fields") {
                b4753Catalog.filter { it.section == "Info" && it.id in 6..10 }.forEach { field ->
                    val value = when (field.id) {
                        6 -> recorderName.ifBlank { "Unavailable" }
                        7 -> bleAddress.ifBlank { "Unavailable" }
                        8 -> if (linked && s05AddressMatch) "Remote linked" else "Not linked"
                        9 -> appVersion
                        10 -> if (s05AddressMatch && b04Identity.preflightPassed) b04Identity.pairingBcid ?: "Unavailable" else "Unavailable"
                        else -> "Unavailable"
                    }
                    S02RowItem(field.name, value, "App / link metadata | No recorder SET") {
                        activeSheet = "b4772field:${field.id}"
                        android.util.Log.i("AtomRemoteB4772", "SHEET_OPEN info_field=${field.id} noSET=true")
                    }
                }
            }
            S02Group("OTHER DEVICE INFO", "Unavailable fields remain visible, not invented") {
                b4753Catalog.filter { it.section == "Info" && it.id in 11..23 }.forEach { field ->
                    val locked = field.id == 22 || field.id == 23
                    S02RowItem(field.name, if (locked) "LOCKED" else "Unverified",
                        if (locked) "Unsafe system action permanently disabled" else "No mapped GET / SET", danger = locked) {
                        activeSheet = "b4772field:${field.id}"
                        android.util.Log.i("AtomRemoteB4772", "SHEET_OPEN info_field=${field.id} noSET=true")
                    }
                }
            }
        }
        "date" -> {
            S02Group("DATE & TIME", "Recorder GET values • tap a field for its bottom sheet") {
                val raw = b15Readbacks["dateTime"]
                val ageMs = b4762RxAgeMs
                val fresh = linked && s05AddressMatch && raw != null &&
                    ageMs != null && ageMs in 0L..90000L
                val physicalDate = try {
                    java.time.LocalDate.parse(b4775ScreenDate, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                    b4775ScreenDate
                } catch (_: Exception) { null }
                val rawDate = raw?.substringBefore(' ')
                val rawTime = raw?.substringAfter(' ', "")?.takeIf { it.length == 8 }
                S02RowItem("Date", physicalDate ?: "Confirm recorder date", if (physicalDate != null)
                    "GET 0x84B5 available • Date SET pilot in this sheet • physical date is manually confirmed"
                    else "GET 0x84B5 raw: ${rawDate ?: "not received"} • Open to test Date SET") {
                    activeSheet = "b47712date"
                    b4771ArmedUtc = null
                    // B47.84: opening the field requests the EXISTING GET, never a SET.
                    if (linked && s05AddressMatch && s10GetEnabled && !b12BatchRunning) b37RefreshClock()
                    android.util.Log.i("AtomRemoteB4784", "UI_DATE_OPEN autoGet=true setStatus=${b4771Result.take(90)}")
                }
                S02RowItem("Time", rawTime?.let { "RAW $it" } ?: "GET required",
                    "UTC-like wire GET only; not recorder's selected local timezone") {
                    activeSheet = "b47712time"
                    b4771Seeded = false; b4771ArmedUtc = null
                    android.util.Log.i("AtomRemoteB4773", "SHEET_OPEN field=TIME fresh=$fresh")
                }
                S02RowItem("12 / 24-hour format", when (b16Format?.second) {
                    0 -> "24-hour"; 1 -> "12-hour"; else -> "GET required"
                }, "Recorder 0x84B7 f2 • GET available; SET locked") {
                    activeSheet = "dateformat"
                    android.util.Log.i("AtomRemoteB4773", "SHEET_OPEN field=TIME_FORMAT")
                }
                Button(onClick = b37RefreshClock,
                    enabled = linked && s05AddressMatch && s10GetEnabled && !b12BatchRunning,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (b12BatchRunning) "READING RECORDER..." else "GET DATE + TIME + FORMAT")
                }
                S02Notice("Date/time 0x84B5 is UTC-like. Recorder local time cannot be " +
                    "derived without its selected timezone. Date SET uses physical screen DATE " +
                    "plus a one-day delta, never Sydney/Karachi guessing.")
            }
        }
        "timecode" -> {
            S02Group("TIMECODE", "Verified GET values • live seconds • individual GET / SET sheets") {
                val tcFields = b4715TimecodeFields(b4711LiveEvidence)
                val sourceId = b4714SelectedId(b44SourceEvidence)
                val tcSourceId = b4714TcGetSourceId(b4711LiveEvidence)
                val liveCounter = if (tcFields != null) {
                    if (b4719Advances && b4719AnchorMs != null)
                        b4720SecondsOnly(b4711LiveEvidence,
                            (b4719TickMs - b4719AnchorMs!!).coerceIn(0L, 120000L), true)
                    else b4720SecondsOnly(b4711LiveEvidence, 0L, false)
                } else "GET required"
                val offsetRead = b4715DecodeWire(b4711OffsetEvidence, "0x844F")
                    ?.takeIf { it.keys == setOf(1) }?.get(1)
                val isAirGlu = sourceId == 5 && tcSourceId == 5
                S02RowItem("Timecode Counter", liveCounter,
                    if (b4719Advances) "HH:MM:SS running (estimated between recorder GETs) • FF --" else
                        "HH:MM:SS last recorder GET • FF --") {
                    activeSheet = "tc"
                    if (linked && s05AddressMatch && s10GetEnabled && !b4719Busy && !b12BatchRunning)
                        b4721RefreshRequests.trySend(Unit)
                    android.util.Log.i("AtomRemoteB4784", "UI_TC_COUNTER_OPEN get=${tcFields != null} setter=UNVERIFIED noSET=true")
                }
                val sourceText = when {
                    sourceId != null && tcSourceId != null && sourceId != tcSourceId -> "Mismatch"
                    else -> (sourceId ?: tcSourceId)?.let { b4714SourceName(it) } ?: "GET required"
                }
                S02RowItem("Timecode Source", sourceText,
                    "Source GET 0x844D • tap for source choices / SET status") {
                    activeSheet = "source"
                    if (linked && s05AddressMatch && s10GetEnabled && !b4719Busy && !b12BatchRunning)
                        b4721RefreshRequests.trySend(Unit)
                    android.util.Log.i("AtomRemoteB4784", "UI_TC_SOURCE_OPEN id=${sourceId ?: -1} setter=UNVERIFIED noSET=true")
                }
                S02RowItem("TC Offset", offsetRead?.let { "$it (GET)" } ?: "GET required",
                    if (isAirGlu) "AirGlu Source 5 • GET verified • tap to edit 0..20 and test SET"
                    else "Source-specific value • SET disabled unless AirGlu Source 5") {
                    activeSheet = "b4770offset"
                    if (linked && s05AddressMatch && s10GetEnabled && !b4719Busy && !b12BatchRunning)
                        b4721RefreshRequests.trySend(Unit)
                    android.util.Log.i("AtomRemoteB4784", "UI_TC_OFFSET_OPEN source=${sourceId ?: -1} f1=${offsetRead ?: -1} autoGet=true")
                }
                S02RowItem("Use Drop Frame", "Not verified", "Independent GET / SET mapping pending") {
                    activeSheet = "drop"
                }
                S02RowItem("HDMI Out Timecode", "Not verified", "Independent GET / SET mapping pending") {
                    activeSheet = "tchdmi"
                }
                Button(onClick = { b4721RefreshRequests.trySend(Unit) },
                    enabled = linked && s05AddressMatch && s10GetEnabled && !b4719Busy && !b12BatchRunning,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (b4719Busy) "READING TIMECODE..." else "GET COUNTER + SOURCE + OFFSET")
                }
                S02Info("Recorder GET", b4719Status)
                S02Notice("Counter HH:MM:SS advances from recorder snapshots when running. FF is deliberately --; " +
                    "no frame rate is invented. Each field opens its own bottom sheet to edit or see the SET gate.")
            }
        }
        "general" -> {
            S02Group("COOLING & DISPLAY", "Recorder values pending verified typed GET") {
                b4753Catalog.filter { it.section == "General" && it.id in 46..48 }.forEach { field ->
                    S02RowItem(field.name, "Not verified", "GET pending | SET locked | ${field.reference}") {
                        activeSheet = "b4772field:${field.id}"
                        android.util.Log.i("AtomRemoteB4772", "SHEET_OPEN general_field=${field.id} noTX=true")
                    }
                }
                S02Notice("Auto / Low / Medium and On / Off are menu references, NOT current recorder GETs. No General SET will transmit in this UI phase.")
            }
            S02Group("GENERAL DIAGNOSTICS", "Each item has its own bottom sheet") {
                b4753Catalog.filter { it.section == "General" && it.id in 49..53 }.forEach { field ->
                    S02RowItem(field.name, "Not verified", "GET / SET mapping pending") {
                        activeSheet = "b4772field:${field.id}"
                        android.util.Log.i("AtomRemoteB4772", "SHEET_OPEN general_field=${field.id} noTX=true")
                    }
                }
            }
        }
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
                            "b4770info" -> "Device information"; "b47713clockmatch" -> "Match recorder screen time"; "b4770clock" -> "Legacy reference (hidden)"; "b4771datetime" -> "Date & Time · Full batch"; "b47712date" -> "Date · GET / SET / Verify"; "b47712time" -> "Time · GET / SET / Verify"; "b47712dateformat" -> "Date format · GET / SET status"; "b4770offset" -> "AirGlu Offset · Edit & Apply"; "b4753:Info" -> "23 Info fields"; "b4753:Date & Time" -> "8 Date & Time fields"
                            "b4753:Timecode" -> "14 Timecode fields"; "b4753:General" -> "8 General fields"
                            "date" -> "Date"; "time" -> "Time"; "dateformat" -> "Time format (24h / 12h)"; "timezone" -> "Time zone (recorder status)"; "clock" -> "App clock preview"; "tc" -> "Timecode Counter · GET / draft"; "source" -> "Timecode Source · GET / draft"
                            "now" -> "Use phone time"; "tc" -> "Timecode"
                            "source" -> "Timecode source"; "drop" -> "Dropframe"
                            "offset" -> "Timecode offset"; "tcautorestart" -> "Auto Restart"
                            "tchdmi" -> "HDMI Out Timecode"; "tcairgluo" -> "AirGlu Sync TC Offset"
                            "recorder" -> "Recorder"
                            "device" -> "Device information"; "app" -> "Application"
                            "connections" -> "Wi-Fi & Ethernet"; "connectedapps" -> "Connected apps"
                            "airglu" -> "AirGlu & synchronization"; "cooling" -> "Fan & cooling"
                            "warnings" -> "Warnings & errors"; "activation" -> "Activated features"
                            "storage" -> "Storage & recovery"; "advanced" -> "Advanced system"
                            else -> b4753Catalog.firstOrNull { "b4772field:${it.id}" == sheetKey }?.name ?: "Factory reset"
                        },
                        modifier = Modifier.weight(1f), color = Color.White,
                        fontWeight = FontWeight.Bold, fontSize = 19.sp
                    )
                    TextButton(onClick = { activeSheet = null }) {
                        Text("CLOSE", color = S02Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                // Technical method lists and B05 internal proof gates are kept out of
                // the operator UI. The protocol evidence is retained in source/logs.

                when (sheetKey) {
                    "b4772field:1", "b4772field:2", "b4772field:3", "b4772field:4", "b4772field:5", "b4772field:6", "b4772field:7", "b4772field:8", "b4772field:9", "b4772field:10", "b4772field:11", "b4772field:12", "b4772field:13", "b4772field:14", "b4772field:15", "b4772field:16", "b4772field:17", "b4772field:18", "b4772field:19", "b4772field:20", "b4772field:21", "b4772field:22", "b4772field:23", "b4772field:46", "b4772field:47", "b4772field:48", "b4772field:49", "b4772field:50", "b4772field:51", "b4772field:52", "b4772field:53" -> {
                        val field = b4753Catalog.firstOrNull { "b4772field:${it.id}" == sheetKey }
                        if (field != null) {
                            val infoField = field.section == "Info"
                            val isRecorderInfo = infoField && field.id in 1..5
                            val isAppMetadata = infoField && field.id in 6..10
                            val lockedAction = field.id == 22 || field.id == 23
                            val value: String? = when (field.id) {
                                in 1..5 -> b4772InfoWireValue(field.id, b4723InfoFields)
                                6 -> recorderName.takeIf { it.isNotBlank() }
                                7 -> bleAddress.takeIf { it.isNotBlank() }
                                8 -> if (linked && s05AddressMatch) "Remote linked" else "Not linked"
                                9 -> appVersion
                                10 -> if (s05AddressMatch && b04Identity.preflightPassed) b04Identity.pairingBcid else null
                                else -> null
                            }
                            val provenance = if (infoField) b4772InfoProvenance(field.id)
                                else b4772GeneralProvenance(field.id)
                            S02Info("Setting", field.name)
                            S02Info("Current value", value ?: if (lockedAction) "LOCKED" else "NOT VERIFIED")
                            S02Info("Data source", provenance)
                            if (isRecorderInfo) {
                                S02Info("GET status", b4723InfoStatus)
                                S02Info("Wire response", "0x04B0 GET -> 0x84B0 typed RX")
                                if (field.id == 3 || field.id == 4) {
                                    val fw2 = b4723InfoFields["deviceField2"]
                                    val fw5 = b4723InfoFields["deviceField5"]
                                    S02Info("Firmware cross-check", if (!fw2.isNullOrBlank() && fw2 == fw5)
                                        "Matching fields 2 and 5" else "Unverified / mismatch")
                                }
                                if (field.id == 5) S02Notice("Field 6 is a real payload field, but its meaning is not yet confirmed. Do not label it as a firmware feature.")
                                Button(onClick = {
                                    android.util.Log.i("AtomRemoteB4772", "FIELD_GET_REQUEST info_field=${field.id} cmd=0x04B0 noSET=true")
                                    b4723InfoRefreshRequests.trySend(Unit)
                                }, enabled = linked && s05AddressMatch && s10GetEnabled && !b4723InfoBusy,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(if (b4723InfoBusy) "READING..." else "GET FRESH DEVICE INFO")
                                }
                                S02Info("SET", "READ ONLY; no SET or edit available")
                            } else if (isAppMetadata) {
                                S02Info("GET", "App / BLE state; no separate recorder GET")
                                S02Info("SET", "READ ONLY in Settings")
                                S02Notice("This value comes from the running Android app or pairing state, NOT a recorder DeviceInfo response.")
                            } else if (lockedAction) {
                                S02Info("GET", "Not mapped")
                                S02Info("SET", "PERMANENTLY LOCKED")
                                S02Notice("Factory Reset, reboot and Power Off are never transmitted from this screen. Nothing is queued when this sheet is opened.")
                            } else if (infoField) {
                                S02Info("GET", "No verified opcode / typed value")
                                S02Info("SET", "Unavailable")
                                S02Notice("This is a field placeholder for later evidence-based GET research. It does not display a guessed value.")
                            } else {
                                S02Info("Recorder menu reference", field.reference)
                                S02Info("GET", "UNMAPPED / no confirmed typed RX")
                                S02Info("SET", "LOCKED / no validated outgoing command")
                                if (field.id == 46) S02Notice("Fan Cooling options are menu references only. Auto / Low / Medium is NOT a current value.")
                                if (field.id == 47 || field.id == 48) S02Notice("On / Off values are NOT known. No toggle is wired until verified GET and SET readback are available.")
                                if (field.id == 51) S02Notice("0x0321 and 0x0320 are static candidates, not proven brightness GET / SET. They will not be transmitted.")
                                S02Notice("Later per-field test: correlate physical recorder change with a typed GET, identify SET message and ACK, then fresh GET + physical confirmation. No TX in B47.72.")
                            }
                            S02Info("Capture label", "B4772 / ${field.section} / field ${field.id}")
                        }
                    }
                    "b4770info" -> {
                        S02Info("Recorder GET", b4723InfoStatus)
                        S02Info("Model (0x84B0)", b4723InfoFields["model"] ?: "No verified GET")
                        S02Info("Device ID (0x84B0)", b4723InfoFields["deviceId"] ?: "No verified GET")
                        val fw2 = b4723InfoFields["deviceField2"]
                        val fw5 = b4723InfoFields["deviceField5"]
                        S02Info("Firmware (matching fields 2 & 5)",
                            if (!fw2.isNullOrBlank() && fw2 == fw5) fw2 else "Unverified: f2=${fw2 ?: "?"}, f5=${fw5 ?: "?"}")
                        S02Info("Field 6", b4723InfoFields["deviceField6"] ?: "Meaning unverified")
                        S02Info("Recorder name (app metadata)", recorderName.ifBlank { "Unavailable" })
                        Button(onClick = { b4723InfoRefreshRequests.trySend(Unit) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = linked && s05AddressMatch && s10GetEnabled && !b4723InfoBusy) {
                            Text("REFRESH RECORDER DEVICE INFO")
                        }
                        OutlinedButton(onClick = { activeSheet = "reset" },
                            modifier = Modifier.fillMaxWidth()) { Text("FACTORY RESET  ·  LOCKED") }
                        S02Notice("Read-only recorder information. App metadata is not recorder GET. Factory reset cannot send a command.")
                    }
                    "b47713clockmatch" -> {
                        val rawGet = b15Readbacks["dateTime"]
                        val age = b4762RxAgeMs
                        val fresh = rawGet != null && age != null && age in 0L..90000L
                        S02Info("Recorder raw DateGet", if (fresh) "0x84B5 $rawGet" else "Refresh for a fresh recorder timestamp")
                        S02Info("Current match", b47713CalStatus)
                        S02Notice("Recorder timezone is NOT returned by mapped GET. Type the current physical recorder date/time immediately after GET; we calculate only a session-local offset. This is manual calibration, not automatic recorder timezone GET or SET.")
                        Button(onClick = b37RefreshClock, enabled = linked && s05AddressMatch && s10GetEnabled && !b12BatchRunning,
                            modifier = Modifier.fillMaxWidth()) { Text("GET FRESH RECORDER DATE + TIME") }
                        androidx.compose.material3.OutlinedTextField(
                            value = b47713PhysicalStamp,
                            onValueChange = { b47713PhysicalStamp = it.take(19) },
                            label = { Text("Physical recorder YYYY-MM-DD HH:mm:ss") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(onClick = {
                            val parser = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", java.util.Locale.US)
                                .withResolverStyle(java.time.format.ResolverStyle.STRICT)
                            val calibrated = try {
                                val raw = LocalDateTime.parse(rawGet!!.trim(), parser)
                                val physical = LocalDateTime.parse(b47713PhysicalStamp.trim(), parser)
                                val whenPressed = raw.plusSeconds(((age ?: 0L) / 1000L).coerceAtLeast(0L))
                                val deltaSec = java.time.Duration.between(whenPressed, physical).seconds
                                val rounded = (deltaSec / 900.0).let { kotlin.math.round(it).toLong() } * 900L
                                if (rounded !in (-12 * 3600L)..(14 * 3600L) ||
                                    kotlin.math.abs(deltaSec - rounded) > 90L) null else rounded.toInt()
                            } catch (_: Exception) { null }
                            if (calibrated != null && fresh) {
                                b47713ClockOffsetSeconds = calibrated
                                b47713CalStatus = "PHYSICAL SCREEN MATCHED: ${ZoneOffset.ofTotalSeconds(calibrated).id}; manual comparison, not timezone GET"
                                b47711PhysicalMatchConfirmed = false
                                b4771Seeded = false
                                b4771ArmedUtc = null
                                android.util.Log.i("AtomRemoteB47713", "CLOCK_MANUAL_MATCH offsetSeconds=$calibrated source=0x84B5 provenance=OPERATOR_NOT_ZONE_GET noSET=true")
                            } else {
                                b47713CalStatus = "MATCH REJECTED: fresh GET + accurate screen YYYY-MM-DD HH:mm:ss required (within 90 seconds of offset boundary)"
                            }
                        }, enabled = fresh && b47713PhysicalStamp.length == 19,
                            modifier = Modifier.fillMaxWidth()) { Text("USE RECORDER SCREEN TIME  ·  NO SET") }
                        OutlinedButton(onClick = {
                            b47713ClockOffsetSeconds = null
                            b47713CalStatus = "Match cleared. No automatic timezone GET available."
                            b47711PhysicalMatchConfirmed = false
                            b4771ArmedUtc = null
                        }, modifier = Modifier.fillMaxWidth()) { Text("CLEAR OLD MATCH / RECORDER ZONE CHANGED") }
                        S02Info("Provenance", "User-observed recorder screen, NOT device timezone GET; session only")
                    }
                    "b47712date" -> {
                        // ONE bottom sheet. No separate clock-match/timezone sheet in this Date workflow.
                        val raw = b15Readbacks["dateTime"]
                        val age = b4762RxAgeMs
                        val fresh = linked && s05AddressMatch && raw != null &&
                            age != null && age in 0L..90000L
                        val baselineDate = try {
                            java.time.LocalDate.parse(b4775ScreenDate, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                        } catch (_: Exception) { null }
                        val target = baselineDate?.takeIf { b4775DayDirection == 1 || b4775DayDirection == -1 }
                            ?.plusDays(b4775DayDirection.toLong())?.toString()
                        val candidate = if (fresh) b4775DateOnlyTarget(raw, b4775ScreenDate, target, age)
                            else null

                        S02Info("Recorder Date GET", if (fresh) "0x84B5 $raw (UTC-like, NOT recorder-local)"
                            else "Refresh GET. Last raw: ${raw ?: "None"}")
                        S02Notice("DATE SET TEST: 1) GET, 2) check recorder screen date, " +
                            "3) choose previous/next day, 4) idle consent, 5) ARM + CONFIRM. " +
                            "The recorder must visibly change; queued is not success.")
                        Button(onClick = {
                            b4771ArmedUtc = null
                            android.util.Log.i("AtomRemoteB4775", "DATE_FIELD_GET_REQUEST cmd=0x04B9 noSET=true")
                            b37RefreshClock()
                        }, enabled = linked && s05AddressMatch && s10GetEnabled && !b12BatchRunning,
                            modifier = Modifier.fillMaxWidth()) { Text("GET RECORDER DATE") }

                        // B47.84: tap a single button instead of retyping the date every GET.
                        // Wire date is UTC-like, so this is only a proposed draft; user must
                        // compare with the physical recorder date before authorizing a SET.
                        OutlinedButton(onClick = {
                            val rawDay = raw?.substringBefore(' ')?.takeIf {
                                Regex("\\d{4}-\\d{2}-\\d{2}").matches(it)
                            }
                            if (rawDay != null) {
                                b4775ScreenDate = rawDay
                                b4775DayDirection = 0
                                b4771ArmedUtc = null
                                b4771IdleConsent = false
                                android.util.Log.i("AtomRemoteB4784", "DATE_DRAFT_FROM_GET value=$rawDay OPERATOR_CHECK_REQUIRED noTX=true")
                            }
                        }, enabled = fresh, modifier = Modifier.fillMaxWidth()) {
                            Text("LOAD GET DATE AS DRAFT  ·  VERIFY ON RECORDER")
                        }
                        androidx.compose.material3.OutlinedTextField(
                            value = b4775ScreenDate,
                            onValueChange = {
                                b4775ScreenDate = it.take(10)
                                b4775DayDirection = 0
                                b4771ArmedUtc = null
                                b4771IdleConsent = false
                            },
                            label = { Text("Current DATE on RECORDER screen (YYYY-MM-DD)") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        S02Info("Date source", "Typed from PHYSICAL recorder screen; selected timezone is not available via GET")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                b4775DayDirection = -1; b4771ArmedUtc = null
                            }, enabled = baselineDate != null && b4771PendingUtc == null,
                                modifier = Modifier.weight(1f)) { Text("PREVIOUS DAY") }
                            OutlinedButton(onClick = {
                                b4775DayDirection = 1; b4771ArmedUtc = null
                            }, enabled = baselineDate != null && b4771PendingUtc == null,
                                modifier = Modifier.weight(1f)) { Text("NEXT DAY") }
                        }
                        S02Info("Current recorder screen date", baselineDate?.toString() ?: "Enter the actual recorder date")
                        S02Info("New recorder date", target ?: "Choose previous/next day")
                        S02Info("Outgoing DateSet (candidate, not sent)", candidate ?: "GET within 90s, screen date and target required")
                        S02Info("SET result", if (b4771MatchGet && b4771PhysicalConfirmed)
                            "Fresh GET matched + operator confirmed physical recorder DATE" else b4771Result)
                        if (b4771MatchGet && !b4771PhysicalConfirmed) {
                            OutlinedButton(onClick = { b4771PhysicalConfirmed = true },
                                modifier = Modifier.fillMaxWidth()) {
                                Text("RECORDER SCREEN SHOWS NEW DATE: CONFIRM")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = b4771IdleConsent,
                                onCheckedChange = { b4771IdleConsent = it; b4771ArmedUtc = null },
                                enabled = linked && s05AddressMatch && b4771PendingUtc == null)
                            Text("Recorder idle, not recording/syncing. I checked its CURRENT date, " +
                                "have a safe manual restore plan, and am not testing a DST change.",
                                color = S02Muted, fontSize = 11.sp,
                                modifier = Modifier.padding(start = 8.dp))
                        }
                        // B47.75.1: explain disabled Apply instead of silently failing.
                        // Do not use s10GetEnabled for SET: GET availability is a different gate.
                        val uiBlocker = when {
                            !linked || !s05AddressMatch -> "Recorder not connected / address mismatch"
                            b4771PendingUtc != null -> "Previous SET pending. Perform fresh GET or clear local pending"
                            b12BatchRunning || b4719Busy -> "Recorder GET/operation in progress. Retry after it finishes"
                            raw == null -> "Tap GET RECORDER DATE first"
                            age == null || age !in 0L..90000L -> "GET expired (90s). Tap GET again"
                            baselineDate == null -> "Enter physical recorder date as YYYY-MM-DD"
                            target == null -> "Select PREVIOUS DAY or NEXT DAY"
                            candidate == null -> "Date candidate invalid. Refresh GET and verify entered date"
                            !b4771IdleConsent -> "Confirm recorder is idle using the switch"
                            else -> null
                        }
                        S02Info("Apply availability", uiBlocker ?: "Ready: ARM DATE SET")
                        val uiReady = uiBlocker == null
                        OutlinedButton(onClick = { b4771ArmedUtc = target },
                            enabled = uiReady, modifier = Modifier.fillMaxWidth()) {
                            Text("APPLY NEW DATE ${target ?: ""} (ARM)")
                        }
                        if (b4771ArmedUtc != null && target == b4771ArmedUtc) {
                            Button(onClick = {
                                val before = b15Readbacks["dateTime"]
                                val nowAge = b453LastDateRxMs?.let {
                                    android.os.SystemClock.elapsedRealtime() - it
                                }
                                val freshCandidate = b4775DateOnlyTarget(
                                    before, b4775ScreenDate, target, nowAge)
                                val valid = freshCandidate != null && b4771IdleConsent &&
                                    b4771ArmedUtc == target && linked && s05AddressMatch &&
                                    !b12BatchRunning && !b4719Busy && b4771PendingUtc == null
                                val queued = if (valid) b4771SendDateSetPilot(freshCandidate!!, before!!) else false
                                b4771Result = when {
                                    queued -> "SET queued (NOT ACK). Tap GET and check physical date."
                                    !valid -> "BLOCKED in UI: GET within 90s, date, target, idle consent and transport idle required. No TX."
                                    else -> "BLOCKED by recorder transport. Check DATESET_BLOCKED logs. No TX."
                                }
                                if (!queued) android.util.Log.w("AtomRemoteB47751",
                                    "DATE_SET_BLOCKED valid=$valid freshCandidate=${freshCandidate != null} ageMs=$nowAge " +
                                    "linked=$linked addressMatch=$s05AddressMatch batch=$b12BatchRunning busy=$b4719Busy " +
                                    "consent=$b4771IdleConsent target=$target ack=UNVERIFIED noTX=true")
                                if (queued) {
                                    b4771PendingUtc = freshCandidate
                                    b4771SentMs = android.os.SystemClock.elapsedRealtime()
                                    b4771MatchGet = false
                                    b4771PhysicalConfirmed = false
                                }
                                b4771ArmedUtc = null
                                b4771IdleConsent = false
                                android.util.Log.i("AtomRemoteB4775", "DATE_ONLY_APPLY queued=$queued screenBefore=$b4775ScreenDate target=$target rawBefore=$before candidate=$freshCandidate ack=UNVERIFIED")
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("CONFIRM DATE SET ONCE (0x04B8)")
                            }
                        }
                        if (b4771PendingUtc != null) {
                            S02Notice("A candidate was queued, NOT acknowledged. GET Date again, check " +
                                "physical date, and restore original date manually if needed. No automatic retries.")
                            OutlinedButton(onClick = {
                                b4771PendingUtc = null
                                b4771SentMs = 0L
                                b4771Result = "Local pending cleared; no recorder command sent."
                            }, modifier = Modifier.fillMaxWidth()) { Text("CLEAR LOCAL PENDING (NO TX)") }
                        }
                        S02Notice("No selected Timezone GET exists. We do NOT use Sydney, Karachi, " +
                            "phone time, or a 1-hour display guess to construct DATE SET. " +
                            "The existing command 0x04B8 is experimental, and SET ACK remains unverified.")
                    }
                    "b4771datetime", "b47712time" -> {
                        val rawGet = b15Readbacks["dateTime"]
                        val elapsed = b4762RxAgeMs
                        val fresh = rawGet != null && elapsed != null && elapsed in 0L..90000L
                        // Operator has matched the CURRENT recorder wall clock to a fresh
                        // DateGet. The selected recorder timezone is NOT wire-readable.
                        val b47713PhysicalSynced = b47713ClockOffsetSeconds != null
                        val selectedReference = b47713ClockOffsetSeconds?.let {
                            ZoneOffset.ofTotalSeconds(it).id
                        }
                        val reference = if (fresh && selectedReference != null) b4762ProjectReferenceClock(
                            rawGet, selectedReference, 0, elapsed)?.substring(0, 19) else null
                        LaunchedEffect(sheetKey, reference, b4771Seeded) {
                            if (!b4771Seeded && reference != null) {
                                b4771DateText = reference.substring(0, 10)
                                b4771TimeText = reference.substring(11, 19)
                                b4771Seeded = true
                            }
                        }
                        val dateOnlySheet = false // B47.75: DATE has own simple one-sheet path
                        val utcCandidate = if (dateOnlySheet && b47713PhysicalSynced &&
                            b47711PhysicalMatchConfirmed) b4774DateOnlyTarget(
                            rawGet, b4771DateText, b47713ClockOffsetSeconds, elapsed
                        ) else null
                        val dateOnlyCurrentDay = try {
                            val parsed = LocalDateTime.parse(rawGet ?: "", DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", java.util.Locale.US))
                            b47713ClockOffsetSeconds?.let { parsed.plusSeconds(it.toLong()).toLocalDate().toString() }
                        } catch (_: Exception) { null }
                        S02Info("FIELD", when (sheetKey) { "b47712date" -> "DATE: year / month / day"; "b47712time" -> "TIME: HH:mm:ss, 24-hour draft"; else -> "DATE + TIME combined editor" })
                        S02Info("GET command", "0x04B9 => 0x84B5 (typed recorder timestamp)")
                        S02Info("SET command", if (dateOnlySheet) "0x04B8 DATE-only one-shot pilot; ACK not verified" else "LOCKED until Date-only hardware test passes")
                        S02Info("DATE + TIME GET RAW (UTC-like)", if (fresh) "0x84B5 $rawGet" else "Press GET for a fresh recorder reading")
                        S02Info("TIME FORMAT GET", when (b16Format?.second) { 0 -> "24-hour"; 1 -> "12-hour"; else -> "No typed RX" })
                        S02Info("Clock match", b47713CalStatus)
                        S02Info("12 / 24 SET", "LOCKED: SET opcode and ACK unverified; verified GET is available in Format sheet")
                        Button(onClick = {
                            android.util.Log.i("AtomRemoteB47712", "FIELD_GET_REQUEST field=$sheetKey cmdDate=0x04B9 cmdFormat=0x04BB noSET=true")
                            b37RefreshClock()
                        }, enabled = linked && s05AddressMatch && s10GetEnabled && !b12BatchRunning,
                            modifier = Modifier.fillMaxWidth()) { Text("GET FRESH RECORDER DATE + FORMAT") }
                        S02Info("Physical-screen offset reference", selectedReference ?: "MATCH RECORDER SCREEN first")
                        OutlinedButton(onClick = { activeSheet = "b47713clockmatch" }, modifier = Modifier.fillMaxWidth()) {
                            Text("MATCH RECORDER DATE + TIME  ·  NO TIMEZONE GET")
                        }
                        S02Info("Recorder-derived local date + time (REFERENCE ONLY)",
                            reference ?: "GET date/time then select correct reference")
                        S02Info("Physical recorder clock matches this date/time?",
                            if (b47711PhysicalMatchConfirmed) "Operator confirmed for selected offset" else "NOT CONFIRMED; SET remains locked")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = b47711PhysicalMatchConfirmed,
                                onCheckedChange = {
                                    b47711PhysicalMatchConfirmed = it
                                    b4771ArmedUtc = null
                                }, enabled = fresh && b47713PhysicalSynced)
                            Text("I checked the BASELINE date/time against the physical recorder screen",
                                color = S02Muted, fontSize = 11.sp,
                                modifier = Modifier.padding(start = 8.dp))
                        }
                        S02Notice("Recorder timezone cannot be read by mapped GET. Match the physical screen before editing DateSet. If recorder timezone changes, repeat the physical match. DateSet remains experimental; never SET while recording.")
                        if (dateOnlySheet) androidx.compose.material3.OutlinedTextField(
                            value = b4771DateText,
                            onValueChange = { b4771DateText = it.take(10); b4771ArmedUtc = null; b47711PhysicalMatchConfirmed = false },
                            label = { Text("Recorder wall date YYYY-MM-DD (draft)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        if (!dateOnlySheet) S02Info("Time GET (read-only this phase)", reference?.substring(11,19) ?: "Refresh GET / match physical clock")
                        S02Info("Recorder local date from GET", dateOnlyCurrentDay ?: "Physical match / fresh GET required")
                        if (dateOnlySheet) S02Info("Date-only test range", "Only previous or next calendar day; HH:mm:ss follows fresh recorder GET, not a stale phone/editor value")
                        S02Info("Unsent DateSet UTC-like candidate", utcCandidate ?: if (dateOnlySheet) "Choose +/- 1 calendar day, fresh GET, physical match and consent" else "Time SET locked until Date SET verified")
                        S02Info("DateSet pilot status", if (b4771MatchGet && b4771PhysicalConfirmed)
                            "Matched new GET and physical recorder clock (SET ACK unverified)" else b4771Result)
                        if (b4771MatchGet && !b4771PhysicalConfirmed) {
                            OutlinedButton(onClick = { b4771PhysicalConfirmed = true },
                                modifier = Modifier.fillMaxWidth()) {
                                Text("I CONFIRMED DATE + TIME ON RECORDER")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = b4771IdleConsent,
                                onCheckedChange = { b4771IdleConsent = it; b4771ArmedUtc = null },
                                enabled = linked && s05AddressMatch && b4771PendingUtc == null)
                            Text("Idle / not recording; physical recorder clock matches selected reference; manual restore available",
                                color = S02Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                        val writeReady = b4771IdleConsent && b47713PhysicalSynced &&
                            b47711PhysicalMatchConfirmed && fresh &&
                            utcCandidate != null && b4771PendingUtc == null && linked &&
                            s05AddressMatch && !b12BatchRunning && s10GetEnabled
                        if (dateOnlySheet) OutlinedButton(onClick = { b4771ArmedUtc = utcCandidate },
                            enabled = writeReady, modifier = Modifier.fillMaxWidth()) {
                            Text("ARM DATE ONLY (+/- 1 DAY) ONE-SHOT (EXPERIMENTAL)")
                        }
                        if (dateOnlySheet && b4771ArmedUtc != null) {
                            Button(onClick = {
                                val pending = b4771ArmedUtc
                                val before = b15Readbacks["dateTime"]
                                val stillFresh = b453LastDateRxMs?.let {
                                    android.os.SystemClock.elapsedRealtime() - it in 0L..90000L
                                } == true
                                val sendCandidate = b4774DateOnlyTarget(
                                    before, b4771DateText, b47713ClockOffsetSeconds,
                                    b453LastDateRxMs?.let { android.os.SystemClock.elapsedRealtime() - it }
                                )
                                val valid = dateOnlySheet && pending != null &&
                                    sendCandidate != null && before != null && stillFresh && b4771IdleConsent &&
                                    b47713PhysicalSynced && b47711PhysicalMatchConfirmed &&
                                    linked && s05AddressMatch && !b12BatchRunning
                                val queued = if (valid) b4771SendDateSetPilot(sendCandidate!!, before!!) else false
                                b4771Result = when {
                                    queued -> "DATESET CANDIDATE QUEUED; GET now and verify physical clock; ACK unknown."
                                    !valid -> "BLOCKED BY UI: Date-only (+/-1 day), fresh GET, physical recorder match, consent required. No TX."
                                    else -> "BLOCKED BY TRANSPORT (see AtomRemoteB4771 logs); no TX."
                                }
                                if (queued) {
                                    b4771PendingUtc = sendCandidate
                                    b4771SentMs = android.os.SystemClock.elapsedRealtime()
                                    b4771MatchGet = false
                                    b4771PhysicalConfirmed = false
                                }
                                b4771ArmedUtc = null
                                b4771IdleConsent = false
                                android.util.Log.i("AtomRemoteB4774", "DATE_ONLY_UI_ATTEMPT queued=$queued prior=$before selectedDate=$b4771DateText sendCandidate=$sendCandidate ack=UNVERIFIED")
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("CONFIRM DATE ONLY 0x04B8 (NO RETRIES)")
                            }
                        }
                        if (b4771PendingUtc != null) {
                            S02Notice("A SET candidate was queued. GET recorder date/time now. If no change, inspect transport logs; do not resend. The original recorder time can be restored physically if necessary.")
                            OutlinedButton(onClick = {
                                b4771PendingUtc = null
                                b4771SentMs = 0L
                                b4771Result = "Local pending cleared only; no recorder change, reconnect or TX."
                            }, modifier = Modifier.fillMaxWidth()) { Text("CLEAR LOCAL PENDING ONLY · NO TRANSMIT") }
                        }
                        if (!dateOnlySheet) OutlinedButton(onClick = { activeSheet = "dateformat" }, modifier = Modifier.fillMaxWidth()) {
                            Text("12 / 24 HOUR · VERIFIED GET / LOCKED SET")
                        }
                    }
                    "b4770clock" -> {
                        S02Info("Timezone GET / SET", "LOCKED: selected recorder zone wire GET and SET IDs unverified. Manual reference only.")
                        S02Info("Capture stage", "RUN-CAPTURE.cmd / 05_Timezone_Reference")
                        val clockRaw = b15Readbacks["dateTime"]
                        val age = b4762RxAgeMs
                        val fresh = clockRaw != null && age != null && age in 0L..90000L
                        val chosenZone = B31FirmwareZoneCandidates.getOrNull(b31CandidateId ?: -1)
                        val zoneRef = if (chosenZone == "Australia/Sydney" && b4769SydneyStandard) "+10:00" else chosenZone
                        S02Info("Fresh recorder DateGet raw", if (fresh) clockRaw!! else "Refresh Date + Format GET")
                        S02Info("24h / 12h GET", when (b16Format?.second) {
                            0 -> "24-hour"; 1 -> "12-hour"; else -> "No verified GET"
                        })
                        S02Info("Recorder-observed timezone", chosenZone ?: "Not manually confirmed")
                        S02Info("Derived reference display", if (fresh && zoneRef != null)
                            b4762ProjectReferenceClock(clockRaw, zoneRef, b16Format?.second, age)
                                ?: "Projection unavailable" else "Fresh Date GET and chosen reference required")
                        Button(onClick = b37RefreshClock,
                            enabled = linked && s05AddressMatch && s10GetEnabled && !b12BatchRunning,
                            modifier = Modifier.fillMaxWidth()) { Text("GET RECORDER DATE + FORMAT") }
                        OutlinedButton(onClick = {
                            b31CandidateId = B31FirmwareZoneCandidates.indexOf("Australia/Sydney").takeIf { it >= 0 }
                            b451ComparedOnce = false
                        }, modifier = Modifier.fillMaxWidth()) { Text("MY RECORDER SHOWS AUSTRALIA/SYDNEY") }
                        if (chosenZone == "Australia/Sydney") {
                            S02Info("Sydney display interpretation", if (b4769SydneyStandard)
                                "UTC+10 standard reference" else "UTC+11 DST civil reference on 31 Oct 2026")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { b4769SydneyStandard = true },
                                    enabled = !b4769SydneyStandard, modifier = Modifier.weight(1f)) {
                                    Text("STANDARD +10", fontSize = 11.sp)
                                }
                                OutlinedButton(onClick = { b4769SydneyStandard = false },
                                    enabled = b4769SydneyStandard, modifier = Modifier.weight(1f)) {
                                    Text("CIVIL +11", fontSize = 11.sp)
                                }
                            }
                        }
                        OutlinedButton(onClick = { activeSheet = "timezone" }, modifier = Modifier.fillMaxWidth()) {
                            Text("OTHER ZONES / PHYSICAL CLOCK MATCH")
                        }
                        S02Notice("Selected recorder timezone has NO verified GET or SET. These change DISPLAY reference only. " +
                            "Recorder physical date/time and its timezone label remain the source of truth.")
                    }
                    "b4770offset" -> {
                        S02Info("Timecode source", b4714SourceReadback(b44SourceEvidence, b4711LiveEvidence))
                val sourceGet = b4714SelectedId(b44SourceEvidence)
                val counterSource = b4714TcGetSourceId(b4711LiveEvidence)
                val offsetRead = if (sourceGet == 5 && counterSource == 5)
                    b4715DecodeWire(b4711OffsetEvidence, "0x844F")
                        ?.takeIf { it.keys == setOf(1) }?.get(1)
                else null
                val b4767Current = offsetRead?.toInt()?.takeIf { it in 0..20 }
                val b4767Fresh = b4767TripleGetAtMs > 0L &&
                    android.os.SystemClock.elapsedRealtime() - b4767TripleGetAtMs in 0L..60000L
                val b4769Next = b4769DraftOffset.takeIf {
                    b4768PendingTarget == -1 && b4767Current != null &&
                        it in 0..20 && it != b4767Current
                } ?: -1
                val b4770Blockers = listOfNotNull(
                    if (!linked || !s05AddressMatch) "Recorder not connected / mismatched address" else null,
                    if (!s10GetEnabled) "Settings GET transport unavailable" else null,
                    if (sourceGet != 5 || counterSource != 5) "Both source GETs must report AirGlu ID 5" else null,
                    if (b4767Current == null) "No decoded source-5 Offset GET (0x844F f1)" else null,
                    if (!b4767Fresh) "Grouped GET older than 60s / incomplete. Press REFRESH below" else null,
                    if (b4719Busy || b12BatchRunning) "Recorder GET still running; let it complete" else null,
                    if (b4768PendingTarget in 0..20) "Previous SET awaits post-SET GET; refresh before another" else null,
                    if (b4767Current != null && b4769DraftOffset == b4767Current) "Target equals recorder value; choose a different one" else null,
                )
                S02Info("Apply availability", if (b4770Blockers.isEmpty()) "READY: Apply refreshes GET before confirming" else
                    b4770Blockers.joinToString("\n• ", prefix = "• "))
                S02Info("SET gate", if (b4768PendingTarget in 0..20)
                    "POST-SET GET REQUIRED: check recorder screen; do not resend"
                    else if (b4770Blockers.isEmpty()) "TARGET READY. Tap APPLY for fresh GET, then confirm once"
                    else "BLOCKED: ${b4770Blockers.first()} (no TX)")
                OutlinedButton(onClick = { b4721RefreshRequests.trySend(Unit) },
                    enabled = linked && s05AddressMatch && s10GetEnabled && !b4719Busy && !b12BatchRunning,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (b4719Busy) "READING..." else "REFRESH TC + SOURCE + OFFSET")
                }
                S02Info("AirGlu Offset SET status", if (b4768PhysicalConfirmed && b4768PostGetMatched)
                    "READBACK + PHYSICAL SCREEN CONFIRMED; recorder ACK not independently decoded"
                    else b4767Result)
                S02Info("Recorder current Offset GET", b4767Current?.toString() ?: "GET Source 5 + Offset required")
                S02Info("Draft / target offset", "${b4769DraftOffset} (0..20, positive integers only)")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        b4769DraftOffset = (b4769DraftOffset - 1).coerceAtLeast(0)
                        b4769DraftDirty = true; b4767ArmedTarget = -1
                    }, enabled = b4769DraftOffset > 0, modifier = Modifier.weight(1f)) { Text("−", fontSize = 24.sp) }
                    Text("${b4769DraftOffset}", color = S02Cyan, fontSize = 25.sp,
                        fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = {
                        b4769DraftOffset = (b4769DraftOffset + 1).coerceAtMost(20)
                        b4769DraftDirty = true; b4767ArmedTarget = -1
                    }, enabled = b4769DraftOffset < 20, modifier = Modifier.weight(1f)) { Text("+", fontSize = 24.sp) }
                }
                // B47.86: Apply queues a FRESH grouped GET once. Sending requires a
                // SECOND explicit human tap in the confirmation dialog, never auto TX.
                Button(onClick = {
                    val target = b4769DraftOffset
                    val reason = when {
                        !linked || !s05AddressMatch || !s10GetEnabled -> "Recorder disconnected or Settings GET unavailable"
                        b4768PendingTarget in 0..20 -> "Previous SET awaits fresh post-SET verification"
                        target !in 0..20 -> "Target must be 0..20"
                        b4767Current != null && target == b4767Current -> "Target equals recorder GET; choose another value"
                        else -> null
                    }
                    if (reason != null) {
                        b4785UiStatus = "NO TX: $reason"
                        android.util.Log.w("AtomRemoteB4786", "OFFSET_APPLY_PRECHECK_BLOCK current=$b4767Current target=$target reason=$reason noTX=true")
                    } else {
                        b4786PrepareStartMs = android.os.SystemClock.elapsedRealtime()
                        b4786PrepareTarget = target
                        b4785UiStatus = "Preparing $target: reading fresh Counter + Source + Offset. No SET yet."
                        val requested = b4721RefreshRequests.trySend(Unit).isSuccess
                        if (!requested) {
                            b4786PrepareTarget = null
                            b4785UiStatus = "NO TX: GET request could not enter reader; tap Apply again when connected"
                        }
                        android.util.Log.i("AtomRemoteB4786", "OFFSET_PREPARE_TAP target=$target getRequest=$requested noTX=true")
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("APPLY OFFSET TO $b4769DraftOffset (FRESH GET FIRST)")
                }
                S02Info("Offset Apply version", "B47.86 - Fresh GET → Confirm → one experimental SET")
                S02Info("Last Apply action", b4785UiStatus)
                OutlinedButton(onClick = {
                    if (b4767Current != null) {
                        b4786PrepareTarget = null
                        b4769DraftOffset = b4767Current
                        b4769DraftDirty = false
                        b4767ArmedTarget = -1
                        b4785ConfirmTarget = null
                        b4785UiStatus = "Draft reset to GET; no command sent"
                    }
                }, enabled = b4767Current != null, modifier = Modifier.fillMaxWidth()) {
                    Text("RESET DRAFT TO RECORDER GET (NO SEND)")
                }
                S02Notice("Apply 0..20 uses experimental numeric 0x0452 encoding. Physical GET matching has been demonstrated only for 1/2; 0 and 3..20 have NOT been proven on hardware. " +
                    "Source 5 required. Recorder must be idle, not recording and not used for critical sync. No automatic retries. " +
                    "After SET tap GET, verify recorder screen, and restore the original value if necessary.")
                if (b4768PostGetMatched && !b4768PhysicalConfirmed) {
                    OutlinedButton(onClick = { b4768PhysicalConfirmed = true },
                        modifier = Modifier.fillMaxWidth()) {
                        Text("I CONFIRMED SAME OFFSET ON RECORDER SCREEN")
                    }
                }
                // Required second step: explicit human confirmation of recorder idleness
                // and ability to manually restore original physical Offset.
                if (b4785ConfirmTarget != null) {
                    val dialogTarget = b4785ConfirmTarget ?: -1
                    AlertDialog(
                        onDismissRequest = {
                            b4786PrepareTarget = null
                            b4785ConfirmTarget = null
                            b4785UiStatus = "Cancelled before TX"
                        },
                        title = { Text("Confirm AirGlu Offset SET") },
                        text = {
                            Text("Recorder source must be AirGlu ID 5. Original: ${b4767Current ?: "unknown"}. " +
                                "Target: $dialogTarget. Only continue if recorder is idle, not recording or used for sync, " +
                                "and you can manually restore the original value. This experimental command is sent ONCE. " +
                                "Queue admission is NOT a recorder ACK.")
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val target = b4785ConfirmTarget ?: -1
                                val before = b4767Current ?: -1
                                val src = b4714SelectedId(b44SourceEvidence) ?: -1
                                val tcSrc = b4714TcGetSourceId(b4711LiveEvidence) ?: -1
                                val now = android.os.SystemClock.elapsedRealtime()
                                val stillFresh = b4767TripleGetAtMs > 0L &&
                                    now - b4767TripleGetAtMs in 0L..60000L
                                val valid = linked && s05AddressMatch && s10GetEnabled &&
                                    !b4719Busy && !b12BatchRunning && stillFresh &&
                                    src == 5 && tcSrc == 5 && b4768PendingTarget == -1 &&
                                    before in 0..20 && target in 0..20 && before != target &&
                                    target == b4769DraftOffset && b4769Next == target
                                android.util.Log.i("AtomRemoteB4785", "OFFSET_CONFIRM_TAP before=$before target=$target uiReady=$valid source=$src tcSource=$tcSrc fresh=$stillFresh")
                                val queued = if (valid)
                                    b4767SendAirGluOffsetPilot(target, before, src, tcSrc) else false
                                if (queued) {
                                    b4768PendingTarget = target
                                    b4768PendingBefore = before
                                    b4768SentAtMs = now
                                    b4768PostGetMatched = false
                                    b4768PhysicalConfirmed = false
                                    b4767TripleGetAtMs = 0L
                                }
                                b4785UiStatus = when {
                                    !valid -> "UI BLOCKED (NO TX): refresh GET, verify source 5 and try from a new draft"
                                    !queued -> "TRANSPORT REJECTED (NO QUEUE): recorder unchanged. Do not repeat without fresh GET"
                                    else -> "BUFFER QUEUED (not ACK). Refresh GET and check recorder physically; do not repeat"
                                }
                                b4767Result = b4785UiStatus
                                android.util.Log.i("AtomRemoteB4785", "OFFSET_APPLY_RESULT before=$before target=$target uiReady=$valid queued=$queued ack=UNVERIFIED")
                                b4785ConfirmTarget = null
                            }) { Text("SEND ONCE") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                b4785ConfirmTarget = null
                                b4785UiStatus = "Cancelled before TX"
                            }) { Text("CANCEL") }
                        }
                    )
                }
                if (b4768PendingTarget in 0..20 && b4768SentAtMs > 0L &&
                    android.os.SystemClock.elapsedRealtime() - b4768SentAtMs > 30000L) {
                    OutlinedButton(onClick = {
                        b4768PendingTarget = -1
                        b4768SentAtMs = 0L
                        b4768PostGetMatched = false
                        b4768PhysicalConfirmed = false
                        b4767ArmedTarget = -1
                        b4767TripleGetAtMs = 0L
                        b4767Result = "LOCAL PENDING CLEARED, NOT RECORDER RESTORED. Refresh physical Offset GET before any retry."
                        android.util.Log.w("AtomRemoteB4770", "LOCAL_PENDING_CLEARED_NO_RECORDER_SET requiresFreshGET=true")
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("CLEAR STUCK LOCAL PENDING  ·  NO TX")
                    }
                }
                S02Notice("If SET doesn't work, check Apply availability above. " +
                    "0 and 3..20 hardware SET are unverified. Never retry without GET and physical recorder confirmation. " +
                    "No automatic resend; original value must be restored on recorder if necessary.")
                    }
                    "b4762:clockzone" -> {
                        S02Notice("Choose how DateGet is DISPLAYED. This never changes or identifies " +
                            "the selected recorder time zone; the recorder Zone GET/SET is unverified.")
                        S02RowItem("Use phone time zone", java.time.ZoneId.systemDefault().id,
                            "Read-only date projection; no SET") {
                            b4762DisplayZoneOverride = null
                            activeSheet = null
                        }
                        listOf("Asia/Karachi", "Australia/Sydney", "UTC").forEach { zone ->
                            S02RowItem(zone, if (b4762DisplayZone == zone) "Current preview" else "Select",
                                "DateGet reference, NOT recorder selected zone") {
                                b4762DisplayZoneOverride = zone
                                activeSheet = null
                            }
                        }
                        S02Notice("Other display reference zones")
                        B31FirmwareZoneCandidates.filterNot { it in setOf("Asia/Karachi", "Australia/Sydney", "UTC") }.forEach { zone ->
                            S02RowItem(zone, if (b4762DisplayZone == zone) "Current preview" else "Select",
                                "Comparison only; not a recorder change") {
                                b4762DisplayZoneOverride = zone
                                activeSheet = null
                            }
                        }
                    }
                    "b4753:Info"
, "b4753:Date & Time", "b4753:Timecode", "b4753:General" -> {
                        val sectionName = sheetKey?.removePrefix("b4753:") ?: ""
                        S02Notice("Field status only. 'Not verified' means the recorder has not supplied a mapped value. No setting is changed here.")
                        val selectedSourceId = b4714TcGetSourceId(b4711LiveEvidence) ?: b4714SelectedId(b44SourceEvidence)
                        val tcFields = b4715TimecodeFields(b4711LiveEvidence)
                        b4753Catalog.filter { it.section == sectionName }.forEach { field ->
                            val liveValue: String? = when (field.id) {
                                1 -> b4723InfoFields["model"]
                                2 -> b4723InfoFields["deviceId"]
                                3 -> b4723InfoFields["deviceField2"]
                                4 -> b4723InfoFields["deviceField5"]
                                5 -> b4723InfoFields["deviceField6"]
                                6 -> recorderName.takeIf { it.isNotBlank() }
                                7 -> bleAddress.takeIf { it.isNotBlank() }
                                8 -> if (linked && s05AddressMatch) "Remote linked (app state)" else "Not linked (app state)"
                                9 -> appVersion
                                10 -> if (s05AddressMatch && b04Identity.preflightPassed) b04Identity.pairingBcid else null
                                24 -> b15Readbacks["dateTime"]?.substringBefore(' ')
                                25 -> b15Readbacks["dateTime"]?.substringAfter(' ', "")?.takeIf { it.isNotBlank() }
                                27 -> when (b16Format?.second) { 0 -> "24-hour (GET f2=0)"; 1 -> "12-hour AM/PM (GET f2=1)"; else -> null }
                                32 -> selectedSourceId?.let { "${b4714SourceName(it)} (ID $it)" }
                                34, 35 -> if (tcFields != null) "%02d:%02d:%02d".format(java.util.Locale.US, tcFields[1], tcFields[2], tcFields[3]) else null
                                36 -> tcFields?.let { "raw f4=${it[4]}; rate unverified" }
                                38, 39, 40, 41, 42 -> selectedSourceId?.let {
                                    val targetId = when (field.id) { 38 -> 1; 39 -> 0; 40 -> 2; 41 -> 4; else -> 5 }
                                    if (it == targetId) "Selected mode (GET ID $it)" else "Not selected (current GET ID $it)"
                                }
                                43 -> if (b4715DecodeWire(b4711OffsetEvidence, "0x844F") != null) b4715OffsetReadback(b4711OffsetEvidence) else null
                                else -> null
                            }
                            val provenance = if (field.id in 6..10) "APP / GATT METADATA" else if (liveValue != null) "CURRENT VERIFIED / PARSED GET" else "NO VERIFIED CURRENT GET"
                            val label = if (field.id in 6..10) "${field.name} (app)" else field.name
                            S02Info(label, liveValue ?: "Not verified")
                        }
                    }
                    "date" -> {
                        S02Info("Recorder Date GET", b15Readbacks["dateTime"]?.substringBefore(' ') ?: "Awaiting GET")
                        if (b15Readbacks["dateTime"] == null) {
                            S02Notice("Refresh recorder Date GET before preparing a date change.")
                        } else {
                            val rxDate = b35DateTimeFromGet(b15Readbacks["dateTime"], b31CandidateId)?.toLocalDate()
                            val initialDate = b35DraftDate ?: rxDate
                            if (initialDate != null) {
                                val picker = rememberDatePickerState(
                                    initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                                    yearRange = 2000..2099
                                )
                                LaunchedEffect(picker.selectedDateMillis) {
                                    picker.selectedDateMillis?.let { millis ->
                                        b35DraftDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                                    }
                                }
                                DatePicker(state = picker, showModeToggle = true)
                                S02Info("Draft year / month / day", (b35DraftDate ?: initialDate).toString())
                                Button(
                                    onClick = {
                                        val nextDate = (b35DraftDate ?: initialDate).toString()
                                        b35Prepared = "Date $nextDate prepared locally; no command sent"
                                        android.util.Log.i("AtomRemoteB35", "SET_PREPARE key=date value=$nextDate source=typed-GET-cache; noTX=true")
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("PREPARE DATE CHANGE (NO SEND)") }
                            }
                        }
                        Button(onClick = b21RefreshTime, enabled = s10GetEnabled && !b12BatchRunning,
                            modifier = Modifier.fillMaxWidth()) { Text("REFRESH RECORDER DATE GET") }
                        b35Prepared?.let { S02Info("Test status", it) }
                    }
                    "time" -> {
                        S02Info("Reference display time (not selected recorder zone)", b4762LocalPreview ?: "Refresh required")
                        S02Info("B45.1 manual recorder-time projection", b33TimeDisplay)
                        S02Info("Fresh recorder wire timestamp (not local time)", b15Readbacks["dateTime"] ?: "Waiting for 0x84B5")
                        S02Info("Optional MANUAL time comparison (not live GET)", b33TimeDisplay)
                        S02Info("Manual reference only (NOT recorder selected zone)", b31CandidateId?.let { B31FirmwareZoneCandidates[it] } ?: "None")
                        if (b31CandidateId == null || b15Readbacks["dateTime"].isNullOrBlank()) {
                            S02Notice("To show recorder-local time, match its physical Time Zone and wait for verified DateGet. Selected Time Zone GET has not been identified.")
                            Button(onClick = { activeSheet = "timezone" }, modifier = Modifier.fillMaxWidth()) {
                                Text("CHOOSE DISPLAY REFERENCE ZONE (NO SET)")
                            }
                        } else {
                            val local = b35DateTimeFromGet(b15Readbacks["dateTime"], b31CandidateId)
                            if (local != null) {
                                val h = if (b35DraftHour < 0) local.hour else b35DraftHour
                                val m = if (b35DraftMinute < 0) local.minute else b35DraftMinute
                                val sec = if (b35DraftSecond < 0) local.second else b35DraftSecond
                                val recorderFormat = b16Format?.second
                                val is12h = recorderFormat == 1
                                val displayHour = if (is12h) (if (h % 12 == 0) 12 else h % 12) else h
                                S02Info("Recorder Time Format GET", when (recorderFormat) {
                                    1 -> "12-hour (AM / PM)"; 0 -> "24-hour"; else -> "Not confirmed yet: refresh GET"
                                })
                                S02Stepper(if (is12h) "Hour (1 to 12)" else "Hour (0 to 23)", displayHour,
                                    if (is12h) "01 to 12" else "00 to 23",
                                    { b35DraftHour = (h + 23) % 24 }, { b35DraftHour = (h + 1) % 24 })
                                if (is12h) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { b35DraftHour = h % 12 }, modifier = Modifier.weight(1f)) {
                                            Text(if (h < 12) "AM ✓" else "AM")
                                        }
                                        Button(onClick = { b35DraftHour = (h % 12) + 12 }, modifier = Modifier.weight(1f)) {
                                            Text(if (h >= 12) "PM ✓" else "PM")
                                        }
                                    }
                                }
                                S02Stepper("Minute", m, "00 to 59", { b35DraftMinute = (m + 59) % 60 }, { b35DraftMinute = (m + 1) % 60 })
                                S02Stepper("Second", sec, "00 to 59", { b35DraftSecond = (sec + 59) % 60 }, { b35DraftSecond = (sec + 1) % 60 })
                                val draft = if (is12h)
                                    String.format(java.util.Locale.US, "%02d:%02d:%02d %s", displayHour, m, sec, if (h >= 12) "PM" else "AM")
                                    else String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, sec)
                                val normalized24 = String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, sec)
                                S02Info("Time editor draft", draft)
                                S02Info("UTC conversion for staged time (NO SET)", b451DraftToUtc(
                                    b35DraftDate ?: local.toLocalDate(), h, m, sec, b31CandidateId))
                                if (recorderFormat == null) S02Notice("Format GET missing: hour shown in 24H mode until recorder returns 0x84B7. This is not a recorder SET.")
                                Button(onClick = {
                                    b35Prepared = "Time $draft prepared locally; no recorder command sent"
                                    android.util.Log.i("AtomRemoteB36", "SET_PREPARE key=time shown=$draft normalized24=$normalized24 formatId=${recorderFormat ?: -1} zoneReference=${B31FirmwareZoneCandidates[b31CandidateId!!]} noTX=true")
                                }, modifier = Modifier.fillMaxWidth()) { Text("PREPARE TIME CHANGE (NO SEND)") }
                            }
                        }
                        Button(onClick = b37RefreshClock, enabled = s10GetEnabled && !b12BatchRunning,
                            modifier = Modifier.fillMaxWidth()) { Text("REFRESH TIME + 12/24H GET") }
                        b35Prepared?.let { S02Info("Test status", it) }
                    }
                    "b47712dateformat" -> {
                        S02Info("GET command", "0x04BB => 0x84B7 field 1")
                        S02Info("Recorder Date Format GET", b16Format?.first?.let { "Raw f1=$it (format mapping not yet recorder-proven)" } ?: "No fresh format GET")
                        S02Info("Recorder Time Format GET (related f2)", when (b16Format?.second) {
                            0 -> "24-hour"; 1 -> "12-hour"; else -> "Not verified"
                        })
                        Button(onClick = {
                            android.util.Log.i("AtomRemoteB47712", "FIELD_GET_REQUEST field=DATE_FORMAT cmd=0x04BB noSET=true")
                            b37RefreshClock()
                        }, enabled = linked && s05AddressMatch && s10GetEnabled && !b12BatchRunning,
                            modifier = Modifier.fillMaxWidth()) { Text("GET DATE FORMAT + TIME FORMAT") }
                        S02Info("SET status", "LOCKED: DateFormatSet command ID, encoder and recorder ACK unverified")
                        S02Notice("Raw f1 must not be presented as a specific date ordering until physical menu correlation proves its mapping. No DateFormat SET command is sent.")
                        S02Info("Capture stage", "RUN-CAPTURE.cmd / 04_DateFormat_GET")
                    }
                    "dateformat" -> {
                        val formatGet = b16Format?.second
                        S02Info("Recorder time format", when (formatGet) {
                            0 -> "24-hour  ·  VERIFIED GET (f2=0)"
                            1 -> "12-hour  ·  VERIFIED GET (f2=1)"
                            else -> "Not received: GET required"
                        })
                        S02Info("Wire evidence", "GET 0x04BB / RX 0x84B7 / field 2")
                        Button(onClick = {
                            android.util.Log.i("AtomRemoteB4778", "TIME_FORMAT_FRESH_GET_REQUEST cmd=0x04BB noSET=true")
                            b37RefreshClock()
                        }, enabled = linked && s05AddressMatch && s10GetEnabled && !b12BatchRunning,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(if (b12BatchRunning) "GET IN PROGRESS..." else "GET CURRENT 12 / 24 FORMAT")
                        }
                        S02Info("Select 12 / 24 format", "Editor only until recorder SET opcode is verified")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                b35DraftFormat = 0
                                android.util.Log.i("AtomRemoteB4779", "FORMAT_SET_DRAFT target=0 label=24H NO_TX=true")
                            }, modifier = Modifier.weight(1f)) { Text(if (b35DraftFormat == 0) "24H SELECTED" else "24 HOUR") }
                            Button(onClick = {
                                b35DraftFormat = 1
                                android.util.Log.i("AtomRemoteB4779", "FORMAT_SET_DRAFT target=1 label=12H NO_TX=true")
                            }, modifier = Modifier.weight(1f)) { Text(if (b35DraftFormat == 1) "12H SELECTED" else "12 HOUR") }
                        }
                        S02Info("Selected draft", when (b35DraftFormat) {
                            0 -> "24-hour (candidate f2=0)"
                            1 -> "12-hour (candidate f2=1)"
                            else -> "Select a format above"
                        })
                        S02Info("Candidate verification", when {
                            b35DraftFormat !in 0..1 -> "Choose a format"
                            formatGet == null -> "GET required before comparing"
                            b35DraftFormat == formatGet -> "Recorder already reports this format"
                            else -> "Different from recorder GET, SET is not transmitted"
                        })
                        Button(onClick = {
                            android.util.Log.i("AtomRemoteB4779", "FORMAT_SET_PREVIEW current=${formatGet ?: -1} target=$b35DraftFormat getCmd=0x04BB rx=0x84B7 setCommand=UNVERIFIED noTX=true")
                        }, enabled = b35DraftFormat in 0..1 && formatGet != null,
                            modifier = Modifier.fillMaxWidth()) { Text("PREVIEW SET CANDIDATE (NO TX)") }
                        S02Info("Apply status", "NOT AVAILABLE: DateFormatSet wire ID/packet/ACK unverified. No command sent.")
                        S02Notice("Selection is not transmitted. Change the recorder setting physically, then GET again to confirm its real value. No speculative SET command is sent.")
                        S02Info("Next SET evidence", "Capture before, changed and restored 0x84B7 responses. Actual SET implementation requires a verified command.")
                    }
                    "timezone" -> {
                        S02Info("Live selected recorder Time Zone", b38LiveZone)
                        S02Info("Optional manual comparison reference", b31CandidateId?.let { B31FirmwareZoneCandidates[it] } ?: "None")
                        S02Info("Proposed new zone", B31FirmwareZoneCandidates.getOrNull(b35DraftZoneIndex) ?: "Choose below")
                        S02Info("B45.1 wire timestamp 0x84B5 (UTC-like)", b15Readbacks["dateTime"] ?: "Refresh Date GET first")
                        S02Info("Verified time format", when (b16Format?.second) {
                            1 -> "12H (f2=1) : enter 01:00..12:59 and explicitly choose AM or PM"
                            0 -> "24H (f2=0) : enter 00:00..23:59"
                            else -> "Not verified: REFRESH DATE + FORMAT GET before comparing"
                        })
                        if (b16Format?.second == 1) {
                            S02RowItem("Physical recorder AM", if (b452AmPm == "AM") "Selected" else "Choose", "Manual AM/PM, not a recorder setting") {
                                b452AmPm = "AM"; b451ComparedOnce = false
                            }
                            S02RowItem("Physical recorder PM", if (b452AmPm == "PM") "Selected" else "Choose", "Manual AM/PM, not a recorder setting") {
                                b452AmPm = "PM"; b451ComparedOnce = false
                            }
                        }
                        S02Notice("B45.3: CLOCK OFFSET is not a recorder-selected Time Zone. Example UTC+08:00 is shared by Perth, Hong Kong and PRC. All matching names form ONE ambiguous offset group. The recorder city cannot be identified without a separate selected-zone GET.")
                        S02Notice("Sydney has UTC+11 civil DST on 29 Oct 2026, Brisbane UTC+10; if recorder Sydney clock is UTC+10, that is only a standard-offset firmware hypothesis. No SET enabled.")
                        S02Notice("B45.1: Read the HH:mm shown on the PHYSICAL recorder at the same moment as a fresh Date GET. Type it below. Android clock and phone Time Zone are NOT used. This identifies possible offset-matched zones, NOT recorder's selected zone. If clocks differ or GET is stale, comparison is invalid.")
                        androidx.compose.material3.OutlinedTextField(
                            value = b451PhysicalClock,
                            onValueChange = { v -> b451PhysicalClock = v.take(5); b451ComparedOnce = false },
                            label = { Text("Physical recorder wall clock HH:mm (12H or 24H)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(onClick = {
                            val raw = b15Readbacks["dateTime"]
                            // Elapsed age corrects for a GET older than the button press;
                            // reject stale data rather than invent a city from a clock match.
                            val age = b453LastDateRxMs?.let {
                                (android.os.SystemClock.elapsedRealtime() - it) / 1000L
                            }
                            b453CompareAgeSec = age
                            b451Suggestions = if (age != null && age in 0L..90L)
                                b451FindZoneCandidates(raw, b451PhysicalClock, b16Format?.second, b452AmPm, age)
                            else emptyList()
                            b451ComparisonRaw = raw
                            b451ComparedOnce = true
                            android.util.Log.i("AtomRemoteB453", "OFFSET_COMPARE wire=${raw ?: "NO_RX"} enteredWall=$b451PhysicalClock getAgeSec=${age ?: -1} format=${b16Format?.second ?: -1} amPm=$b452AmPm candidateLabels=${b451Suggestions.size} uniqueOffsets=${b451Suggestions.map { it.offset }.distinct().size} selectedZoneGet=false noTX=true")
                        }, enabled = !b15Readbacks["dateTime"].isNullOrBlank() && b16Format?.second in 0..1 &&
                            (b16Format?.second != 1 || b452AmPm in listOf("AM", "PM")) &&
                            Regex("^(?:[01][0-9]|2[0-3]):[0-5][0-9]$").matches(b451PhysicalClock),
                            modifier = Modifier.fillMaxWidth()) { Text("COMPARE CLOCK OFFSET ONLY (NO SET)") }
                        if (b451ComparedOnce) {
                            if (b451ComparisonRaw != b15Readbacks["dateTime"]) {
                                S02Notice("Date GET changed after comparison. Previous candidates STALE. Repeat with a fresh physical-clock sample.")
                            } else if (b453CompareAgeSec?.let { it in 0L..90L } != true) {
                                S02Notice("STALE or missing Date GET. Sample age ${b453CompareAgeSec ?: -1} sec; refresh Date + Format GET, then enter the physical clock within 90 seconds. NO city is inferred.")
                            } else if (b451Suggestions.isEmpty()) {
                                S02Notice("No clock-offset match within 2 minutes. Check 12H AM/PM and recorder wall clock; selected city remains UNKNOWN.")
                            } else {
                                S02Info("Recorder selected Time Zone", "UNKNOWN (not available from verified GET)")
                                S02Info("Clock sample age", "${b453CompareAgeSec ?: -1} seconds since actual 0x84B5 GET RX")
                                S02Info("B45.3 clock-offset matches", "${b451Suggestions.map { it.offset }.distinct().size} distinct offset(s), ${b451Suggestions.size} equivalent labels / hypotheses. NOT confirmed device zones")
                                b451Suggestions.groupBy { it.offset }.forEach { (offset, group) ->
                                    val distinct = group.map { it.name }.distinct()
                                    S02Info("CLOCK OFFSET $offset (one ambiguous group)", "${distinct.size} possible recorder menu labels. Same offset does NOT prove which city is selected.")
                                    S02Notice("Equivalent city labels for offset $offset: ${distinct.joinToString(", ")}. Do NOT auto-select any of these. If recorder physically shows Perth, choose Perth manually below.")
                                    if (group.any { it.interpretation != "IANA_CIVIL" }) {
                                        S02Notice("This group includes STANDARD-ONLY DST hypotheses. The recorder may not use IANA civil DST rules; source zone still cannot be proved.")
                                    }
                                }
                            }
                        }
                        Button(onClick = b37RefreshClock, enabled = s10GetEnabled && !b12BatchRunning,
                            modifier = Modifier.fillMaxWidth()) { Text("REFRESH FRESH RECORDER DATE + FORMAT GET") }
                        S02Notice("MANUAL PHYSICAL LABEL: select the exact city text shown on the recorder from the list below, then USE AS MANUAL COMPARISON. Offset-equivalent Perth/Hong Kong/PRC are NOT independently discovered city selections. SET disabled.")
                        if (b35DraftZoneIndex in B31FirmwareZoneCandidates.indices) {
                            Button(onClick = {
                                b31CandidateId = b35DraftZoneIndex
                                // Session-only comparison: never persist or claim this is live recorder data.
                                b35DraftHour = -1; b35DraftMinute = -1; b35DraftSecond = -1
                                android.util.Log.i("AtomRemoteB38", "ZONE_MANUAL_COMPARISON_ONLY index=$b35DraftZoneIndex zone=${B31FirmwareZoneCandidates[b35DraftZoneIndex]} LIVE_ZONE_GET=UNMAPPED noPersistence=true noTX=true")
                                activeSheet = null
                            }, modifier = Modifier.fillMaxWidth()) { Text("USE AS MANUAL COMPARISON (NO GET/SET)") }
                            Button(onClick = {
                                b35Prepared = "Zone ${B31FirmwareZoneCandidates[b35DraftZoneIndex]} prepared locally; no command sent"
                                android.util.Log.i("AtomRemoteB35", "SET_PREPARE key=timezone internalIndex=$b35DraftZoneIndex name=${B31FirmwareZoneCandidates[b35DraftZoneIndex]} WIRE_INDEX_UNVERIFIED noTX=true")
                            }, modifier = Modifier.fillMaxWidth()) { Text("PREPARE ZONE CHANGE (NO SEND)") }
                        }
                        b35Prepared?.let { S02Info("Test status", it) }
                        B31FirmwareZoneCandidates.forEachIndexed { index, zone ->
                            Column(
                                Modifier.fillMaxWidth().background(S02Row, RoundedCornerShape(10.dp))
                                    .clickable { b35DraftZoneIndex = index; b35Prepared = null }
                                    .padding(horizontal = 13.dp, vertical = 10.dp)
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(zone, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    if (b35DraftZoneIndex == index) Text("SELECTED", color = S02Cyan, fontSize = 10.sp)
                                }
                            }
                        }
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
                        val counterFields = b4715TimecodeFields(b4711LiveEvidence)
                        val counterValue = if (counterFields != null && b4719Advances && b4719AnchorMs != null)
                            b4720SecondsOnly(b4711LiveEvidence,
                                (b4719TickMs - b4719AnchorMs!!).coerceIn(0L, 120000L), true)
                        else if (counterFields != null) b4720SecondsOnly(b4711LiveEvidence, 0L, false)
                        else "GET required"
                        S02Info("Running counter HH:MM:SS", counterValue)
                        S02Info("Raw GET snapshot", b4715CounterSnapshot(b4711LiveEvidence))
                        S02Info("Frame FF", "-- (not calibrated; do not animate)")
                        OutlinedButton(onClick = { b4721RefreshRequests.trySend(Unit) },
                            enabled = linked && s05AddressMatch && s10GetEnabled && !b4719Busy && !b12BatchRunning,
                            modifier = Modifier.fillMaxWidth()) { Text("GET FRESH TIMECODE + SOURCE + OFFSET") }
                        OutlinedButton(onClick = {
                            if (counterFields != null) {
                                tcHour = (counterFields[1] ?: 0).toInt().coerceIn(0, 23)
                                tcMinute = (counterFields[2] ?: 0).toInt().coerceIn(0, 59)
                                tcSecond = (counterFields[3] ?: 0).toInt().coerceIn(0, 59)
                            }
                        }, enabled = counterFields != null,
                            modifier = Modifier.fillMaxWidth()) { Text("LOAD LAST RECORDER GET INTO DRAFT") }
                        S02Stepper("Hours • local draft", tcHour, "00 to 23",
                            { tcHour = (tcHour + 23) % 24 }, { tcHour = (tcHour + 1) % 24 })
                        S02Stepper("Minutes • local draft", tcMinute, "00 to 59",
                            { tcMinute = (tcMinute + 59) % 60 }, { tcMinute = (tcMinute + 1) % 60 })
                        S02Stepper("Seconds • local draft", tcSecond, "00 to 59",
                            { tcSecond = (tcSecond + 59) % 60 }, { tcSecond = (tcSecond + 1) % 60 })
                        S02Info("Unsent Timecode draft", "%02d:%02d:%02d:--".format(
                            java.util.Locale.US, tcHour, tcMinute, tcSecond))
                        S02Info("SET test", "NOT AVAILABLE: 0x0450 payload/ACK unverified. No command sent.")
                        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                            Text("APPLY TIMECODE COUNTER • LOCKED")
                        }
                        S02Notice("Recorder TimecodeSet payload/ACK and post-SET protocol are not verified. " +
                            "GET is real; changing the draft never changes recorder timecode.")
                    }
                    "source" -> {
                        S02Info("Selected source (combined GET evidence)", b4714SourceReadback(b44SourceEvidence, b4711LiveEvidence))
                        OutlinedButton(onClick = { b4721RefreshRequests.trySend(Unit) },
                            enabled = linked && s05AddressMatch && s10GetEnabled && !b4719Busy && !b12BatchRunning,
                            modifier = Modifier.fillMaxWidth()) { Text("GET FRESH TIMECODE + SOURCE + OFFSET") }
                        S02Info("Physical source choices (operator reference)", "Record Run / Auto Restart / Time of Day / HDMI / AirGlu SYNC")
                        S02Info("macOS API symbols", "Timecode.SourceListGet + SourceGet + SourceSet")
                        listOf("Record Run", "Auto Restart", "Time of Day", "HDMI", "AirGlu SYNC").forEach { mode ->
                            Button(onClick = { b4753TcSourceDraft = mode }, modifier = Modifier.fillMaxWidth()) {
                                Text("Draft source: $mode" + if (b4753TcSourceDraft == mode) " (selected)" else "")
                            }
                        }
                        S02Info("Local draft only", b4753TcSourceDraft)
                        S02Info("SET test", "NOT AVAILABLE: SourceSet command/payload unverified. No command sent.")
                        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("SET TIMECODE SOURCE - LOCKED") }

                        S02Notice("Selected SOURCE is reported by verified 0x044D/0x844D. Source SET is still disabled because SET opcode/payload/confirmation are not verified. Source IDs: Record Run=0, Auto Restart=1, Time of Day=2, HDMI=4, AirGlu SYNC=5.")
                    }
                    "drop" -> {
                        S02Info("Use Drop Frame", "Recorder ON/OFF not mapped")
                        S02Info("GET", "No verified GET opcode/typed DF field")
                        S02Info("SET", "LOCKED: no verified encoder/ACK")
                        S02Notice("Drop Frame is not derived from the running counter's raw f4. The physical recorder value is unknown to this app. No ON/OFF is transmitted.")
                    }
                    "offset" -> {
                        S02Info("Recorder Offset GET", b4715OffsetReadback(b4711OffsetEvidence))
                        S02Info("macOS API", "Timecode.OffsetGet / OffsetSet")
                        S02Notice("Typed GET verified in AirGlu source 5. Actual SET is experimental; ACK unknown. Edit only in the guarded Offset sheet.")
                        OutlinedButton(onClick = { activeSheet = "b4770offset" },
                            modifier = Modifier.fillMaxWidth()) { Text("OPEN AIRGLU OFFSET EDITOR") }
                    }
                    "tcautorestart" -> {
                        S02Info("Recorder Auto Restart GET", "NOT VERIFIED")
                        S02Info("Source context", "Record Run / Auto Restart / Time of Day / HDMI / AirGlu SYNC")
                        S02Notice("This is a recorder-screen option only until an exact getter, response schema and setter are confirmed. No toggle writes to the device.")
                    }
                    "tchdmi" -> {
                        S02Info("HDMI Out Timecode", "Recorder ON/OFF not mapped")
                        S02Info("GET", "No verified independent HDMI Out Timecode RX")
                        S02Info("SET", "LOCKED: no verified numeric opcode/payload/ACK")
                        S02Info("macOS symbols", "Output.HdmiTimecodeEnableGet / HdmiTimecodeEnableSet")
                        S02Notice("HDMI Out TC is not HDMI input as Timecode Source. No speculative toggle or wire command.")
                    }
                    "tcairgluo" -> {
                        val b4766SheetSource = b4714SelectedId(b44SourceEvidence)
                        val b4766SheetTcSource = b4714TcGetSourceId(b4711LiveEvidence)
                        val b4766SheetOffset = if (b4766SheetSource == 5 && b4766SheetTcSource == 5)
                            b4715DecodeWire(b4711OffsetEvidence, "0x844F")
                                ?.takeIf { it.keys == setOf(1) }?.get(1)
                        else null
                        S02Info("AirGlu Sync TC Offset GET", b4766SheetOffset?.let {
                            "$it (typed 0x844F f1; source ID 5 confirmed; unit unverified)"
                        } ?: "Not verified for current source; refresh Timecode + Source + Offset")
                        S02Info("Applicable source", "AirGlu Sync (ID 5 from both GETs)")
                        S02Info("Mapped GET", "0x0451 -> 0x844F, f1, source specific; physical A/B 1 -> 2 -> 1")
                        S02Notice("GET mapping verified on source 5. SET pilot remains experimental. " +
                            "0 and 3..20 numeric SETs are unverified; ACK and frame units are unknown.")
                        OutlinedButton(onClick = { activeSheet = "b4770offset" },
                            modifier = Modifier.fillMaxWidth()) { Text("EDIT / APPLY AIRGLU OFFSET") }
                    }
                    "recorder" -> {
                        S02Info("Recorder", recorderName)
                        S02Info("Model", modelCode)
                        S02Info("BLE address", bleAddress)
                        S02Info("BLE connection", bleState)
                        S02Info("Remote link", if (linked) "Ready" else remotePhase.ifBlank { "Not ready" })
                    }
                    "device" -> {
                        S02Info("Recorder", recorderName)
                        S02Info("Selected recorder", bleAddress)
                        S02Info("Live GATT recorder", s05LiveGattAddress ?: "Not available")
                        S02Info("Connection", if (linked) "Remote-ready" else "Not remote-ready")
                        S02Info("GET opcode", "0x04B0 (existing Android bootstrap mapping; response unproven until correlated)")
                        S02Info("S09 refresh path", s09Lifecycle.refreshPath)
                        S02Info("S09 GET dispatch", s09Lifecycle.queryPacketId?.let { "MAC13 DeviceInfo packet $it" } ?: "Not observed")
                        S02Info("GATT device name", if (s05AddressMatch && b04Identity.preflightPassed)
                            b04Identity.gattDeviceName ?: "Not captured" else "Not captured")
                        S02Info("Pairing BCID (NOT serial)", if (s05AddressMatch && b04Identity.preflightPassed)
                            b04Identity.pairingBcid ?: "Not captured" else "Not captured")
                        S02Info("B04 alternative opcode candidate", s06Trace.candidate?.let {
                            "Packet ${it.packetId}, type 0x${it.messageType.toString(16).uppercase()}, ${it.rawHex.length / 2} bytes, ${it.reason}"
                        } ?: "None")
                        S02Info("S09 test result", SettingsS09DeviceInfoLifecycle.describe(s09Lifecycle, s06Trace.verified != null))
                        S02Info("GET requests observed", s06Trace.queryCount.toString())
                        S02Info("E0-only flow events", s06Trace.flowOnlyCount.toString() + " (NOT data)")
                        S02Info("Unrelated/unknown payloads", s06Trace.unrelatedPayloadCount.toString())
                        S02Info("Explicit reject responses", s06Trace.rejectionCount.toString())
                        S02Info("Current in-flight packet", s06Trace.pendingPacket?.toString() ?: "None")
                        S02Info("Last typed message ID", s06Trace.lastReturnedType?.let { "0x" + it.toString(16).uppercase() } ?: "None")
                        S02Info("Typed DeviceInfo GET RX", if (s05Captured != null && s05AddressMatch) "Packet-correlated" else "NOT confirmed")
                        s05Captured?.let { evidence ->
                            S02Info("Response packet ID", evidence.packetId.toString())
                            S02Info("Response message type", "0x04B0")
                            S02Info("Payload size", "${evidence.rawHex.length / 2} bytes")
                            S02Info("Raw response", evidence.rawHex.take(256) + if (evidence.rawHex.length > 256) "..." else "")
                            // S08: field NUMBER and wire FORMAT only; NEVER assign a semantic field name.
                            s08Inspection?.let { inspection ->
                                S02Info("S08 wire inspection", if (inspection.decoded) "${inspection.fields.size} raw fields" else "Opaque / not protobuf-decoded")
                                S02Notice(inspection.note)
                                if (inspection.decoded) {
                                    inspection.fields.take(24).forEach { rawField ->
                                        S02Info("Field #${rawField.number} · ${rawField.kind}", rawField.display)
                                    }
                                    if (inspection.fields.size > 24) S02Notice("Only first 24 wire fields shown; no field names inferred.")
                                }
                            }
                            S02Notice("RX opcode confirmed, but field roles remain unverified. Firmware and Device ID cannot be inferred from unnamed raw fields.")
                        }
                        S02Info("B02 live GET progress", b02LiveStatus)
                        S02Info("DeviceInfo dispatch", if (b02QuerySeen) "Yes, request queued" else "Not observed")
                        S02Info("Transport delivery", if (b02DeliverySeen) "Yes (not a typed reply)" else "Not observed")
                        S02Info("B15 version raw f2", b15Readbacks["deviceField2"] ?: "Not verified")
                        S02Info("B15 version raw f5", b15Readbacks["deviceField5"] ?: "Not verified")
                        S02Info("B15 Device ID f1", b15Readbacks["deviceId"] ?: "Not verified")
                        S02Notice("REFRESH runs the app's EXISTING 13-command Remote GET bootstrap, not a dedicated Settings-only query. No SET or control command is sent. Run only when idle; other tabs and GATT logic are unchanged.")
                        Button(
                            onClick = s09RunDualGetTest,
                            enabled = s10GetEnabled,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("RUN S08 + S09 GET CHECK") }
                        S02Info("Status", s05RefreshStatus)
                    }
                    "app" -> {
                        S02Info("Application", "Atom Remote")
                        S02Info("Version", appVersion)
                    }
                    "reset" -> {
                        Text("Factory reset is disabled. No reset command is wired or sent.", color = Color(0xFFFF7F7F), fontSize = 12.sp)
                    }
                    "connections" -> {
                        S02Info("Wi-Fi SSID", "Not reported")
                        S02Info("Ethernet", "Not reported")
                        S02Info("IP address", "Not reported")
                        S02Notice("macOS Connect methods exist, but their Android wire IDs and replies are not confirmed. No network SET is enabled.")
                    }
                    "connectedapps" -> {
                        S02Info("Connected application list", "Unavailable")
                        S02Notice("NetworkListGet is an AirGlu network list, NOT a list of paired/connected applications. No official app-list getter has been verified.")
                    }
                    "airglu" -> {
                        S02Info("Network role", "Not reported")
                        S02Info("RF region/channel", "Not reported")
                        S02Info("Record synchronization", "Not reported")
                        S02Notice("SyncConfig has GET/list/SET methods in the macOS binary; exact BLE IDs and typed response schemas are pending. No SET transmitted.")
                    }
                    "cooling" -> {
                        S02Info("Fan Cooling", "Not verified")
                        S02Info("Recorder menu options", "Auto / Low / Medium")
                        S02Info("Turbo Cool", "Not verified (On / Off)")
                        S02Info("Dim Display", "Not verified (On / Off)")
                        S02Notice("These are physical recorder menu options, not live readback. Change them on the recorder until an exact GET/SET payload is verified. Nothing is sent from this page.")
                    }
                    "powerpriority" -> {
                        S02Info("Power Priority options (recorder menu)", "DC / Battery / Manual")
                        S02Info("Current Power Priority from recorder", "NOT VERIFIED")
                        S02Info("GET opcode / typed RX", "NOT MAPPED")
                        S02Notice("Power Priority is separate from Cooling Mode and Monitor.PowerModeGet. Do not infer that Monitor PowerMode controls DC/Battery/Manual. NO NEW GET OR SET.")
                    }
                    "warnings" -> {
                        S02Info("Errors", "Not reported")
                        S02Info("Warnings", "Not reported")
                        S02Notice("ErrorClear / WarningPress remain locked until their exact payload and intended user acknowledgement are proven.")
                    }
                    "activation" -> {
                        S02Info("Activated features", "Not reported")
                        S02Info("License status", "Not reported")
                        S02Notice("Read-only feature activation GET mapping pending. Activation tokens and paid NDI are not changed.")
                    }
                    "storage" -> {
                        S02Info("Media state", "Not reported")
                        S02Info("File recovery", "Not reported")
                        S02Info("Naming", "Not reported")
                        S02Notice("Storage and recovery commands are inventoried; formatting, recovery writes and Media tab remain frozen.")
                    }
                    "advanced" -> {
                        S02Info("AtomX modules", "Not reported")
                        S02Info("System actions", "Locked")
                        S02Notice("Reboot, power off, format and factory reset are intentionally disabled.")
                    }
                    else -> {
                        S02Notice("Recorder command mapping is not yet hardware-verified. No guessed settings are sent.")
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


/** Compare bounded event snapshots by longest exact suffix/prefix overlap.
 * Repeated E0 ticks are intentionally not reported as recorder Settings data.
 */
private fun b02EventDelta(previous: List<String>, current: List<String>): List<String> {
    if (current.isEmpty()) return emptyList()
    if (previous.isEmpty()) return current.takeLast(100)
    if (previous === current || previous == current) return emptyList()
    val maxOverlap = minOf(previous.size, current.size)
    for (count in maxOverlap downTo 1) {
        if (previous.subList(previous.size - count, previous.size) == current.subList(0, count)) {
            return current.drop(count)
        }
    }
    // History completely rolled over; fail closed rather than attribute old RX.
    return emptyList()
}
