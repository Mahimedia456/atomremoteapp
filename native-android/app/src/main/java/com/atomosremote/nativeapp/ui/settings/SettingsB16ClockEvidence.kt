package com.atomosremote.nativeapp.ui.settings

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** B16 display-only, user-reference zone. Never reports a recorder-confirmed time-zone. */
internal object SettingsB16ClockEvidence {
    data class ClockPreview(val rendered: String, val offset: String)
    private val rawDate = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.US)
        .withResolverStyle(ResolverStyle.STRICT)
    private val localDisplay = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z", Locale.US)
    val referenceZones = listOf("Australia/Sydney", "UTC", "Asia/Karachi")

    /** UTC assumption comes from comparison with host timestamps, NOT a timezone GET. */
    fun preview(timestamp: String?, referenceZone: String): ClockPreview? {
        if (timestamp == null || referenceZone !in referenceZones) return null
        return try {
            val parsed = LocalDateTime.parse(timestamp, rawDate)
            val zoned = parsed.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of(referenceZone))
            val offset = "UTC" + (if (zoned.offset.totalSeconds == 0) "+00:00" else zoned.offset.id)
            ClockPreview(zoned.format(localDisplay), offset)
        } catch (_: Exception) { null }
    }

    /** The binary does not establish which protobuf enum is date order vs 12/24h. */
    fun enums(raw: String?): Pair<Int, Int>? {
        val parsed = Regex("f1=(\\d+) / f2=(\\d+)").matchEntire(raw ?: "") ?: return null
        val a = parsed.groupValues[1].toIntOrNull() ?: return null
        val b = parsed.groupValues[2].toIntOrNull() ?: return null
        if (a !in 0..255 || b !in 0..255) return null
        return Pair(a, b)
    }
}
