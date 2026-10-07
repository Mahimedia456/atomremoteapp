package com.atomosremote.nativeapp.monitor

/**
 * 20.04.0-20.04.2 Monitor Core.
 *
 * These values are the exact MonitorMode table recovered from the supplied
 * macOS AtomRemote executable. They are protocol-family choices, not a claim
 * that every recorder model accepts every mode.
 */
data class MonitorModeOption(
    val value: Int,
    val label: String
)

data class MonitorCoreSnapshot(
    val mode: Int? = null,
    val ready: Boolean = false,
    val generation: Int = 0,
    val pendingMode: Int? = null,
    val status: String = "Waiting for recorder"
)

object MonitorCoreCatalog {
    val officialModes = listOf(
        MonitorModeOption(1, "Native"),
        MonitorModeOption(2, "Rec.709"),
        MonitorModeOption(3, "HLG"),
        MonitorModeOption(4, "PQ"),
        MonitorModeOption(5, "LUT")
    )

    fun label(mode: Int?): String =
        officialModes.firstOrNull { it.value == mode }?.label
            ?: if (mode == null) "Not reported" else "Mode $mode"
}
