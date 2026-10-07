package com.atomosremote.nativeapp.atomos

data class RecorderTelemetry(
    val batteryPercent: Int? = null,
    val mediaRemaining: String? = null,
    val timecode: String? = null,
    val inputFormat: String? = null,
    val codec: String? = null,
    val recordState: String? = null,
    val raw: String = ""
)

object TelemetryParser {
    private val tcRegex = Regex("\\b\\d{2}:\\d{2}:\\d{2}[:;]\\d{2}\\b")
    private val batteryRegex = Regex("(?i)(?:battery|batt)\\s*[:=]\\s*(\\d{1,3})")
    private val mediaRegex = Regex("(?i)(?:media|remaining|remain)\\s*[:=]\\s*([^;\\n]+)")
    private val codecRegex = Regex("(?i)codec\\s*[:=]\\s*([^;\\n]+)")
    private val inputRegex = Regex("(?i)(?:input|format)\\s*[:=]\\s*([^;\\n]+)")
    private val stateRegex = Regex("(?i)(?:record|rec|state)\\s*[:=]\\s*(recording|standby|stopped|stop|rec)")

    fun parse(raw: String): RecorderTelemetry = RecorderTelemetry(
        batteryPercent = batteryRegex.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100),
        mediaRemaining = mediaRegex.find(raw)?.groupValues?.getOrNull(1)?.trim(),
        timecode = tcRegex.find(raw)?.value,
        inputFormat = inputRegex.find(raw)?.groupValues?.getOrNull(1)?.trim(),
        codec = codecRegex.find(raw)?.groupValues?.getOrNull(1)?.trim(),
        recordState = stateRegex.find(raw)?.groupValues?.getOrNull(1)?.uppercase(),
        raw = raw
    )
}
