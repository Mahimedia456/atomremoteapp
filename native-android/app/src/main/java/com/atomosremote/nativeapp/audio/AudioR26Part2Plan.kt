package com.atomosremote.nativeapp.audio

/**
 * Evidence and user-facing configuration plan for Part 2: R26.06..R26.11.
 * IMPORTANT: symbolic methods != command numbers. No Audio SET enabled.
 */
data class AudioR26Part2Phase(
    val phase: String,
    val title: String,
    val settingKeys: Set<String>,
    val readMethods: List<String>,
    val setterMethods: List<String>,
    val acceptance: String
)

object AudioR26Part2Plan {
    val phases: List<AudioR26Part2Phase> = listOf(
        AudioR26Part2Phase("R26.06", "Headphones level", setOf("phones"),
            listOf("PhonesLevelGet", "PhonesLevelMinMaxGet"), listOf("PhonesLevelSet"),
            "Nonempty typed Get, proven range, one controlled Set and independently confirmed readback"),
        AudioR26Part2Phase("R26.07", "Analog L/R monitoring", setOf("analogMonitor"),
            listOf("MonitorGet", "MonitorListGet"), listOf("MonitorSet"),
            "Verify L/R route IDs from official list; query actual before and after change"),
        AudioR26Part2Phase("R26.08", "Monitor channels 1 to 12", setOf("ch12", "ch34", "ch56", "ch78", "ch910", "ch1112"),
            listOf("MonitorGet", "MonitorListGet"), listOf("MonitorSet"),
            "Prove all six channel-pair IDs and supported routing options"),
        AudioR26Part2Phase("R26.09", "Audio REC channel arming (not Record transport)",
            setOf("analogRecord", "ch12", "ch34", "ch56", "ch78", "ch910", "ch1112"),
            listOf("RecordEnabledListGet", "RecordValidListGet"), listOf("RecordSet"),
            "Recorder-specific valid/enabled channel masks, safe arm/disarm with readback"),
        AudioR26Part2Phase("R26.10", "Analog channel embed", setOf("embed"),
            listOf("AnalogEmbedGet", "AnalogEmbedListGet"), listOf("AnalogEmbedSet"),
            "Supported embed options and exact payload proven before enabling"),
        AudioR26Part2Phase("R26.11", "Meter orientation and Audio Fill", setOf("orientation", "fill"),
            listOf("OrientationChangeableGet", "OrientationGet", "AudioFillGet"),
            listOf("OrientationSet", "AudioFillSet"),
            "Availability and changed values confirmed on AG2")
    )
    fun forKey(key: String): List<AudioR26Part2Phase> = phases.filter { key in it.settingKeys }
    fun readiness(key: String): String = if (forKey(key).isEmpty()) {
        "Reserved for Mic/Line Part 3 / final supported-device audit"
    } else {
        "READ-ONLY REFERENCE - numeric GET/SET IDs and actual device values not proven"
    }
    const val ACTUAL_AUDIO_SET_ENABLED = false
    const val ACTUAL_AUDIO_LEVELS_DECODED = false
}
