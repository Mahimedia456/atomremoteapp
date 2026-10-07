package com.atomosremote.nativeapp.monitor

data class MonitorZoomCoreState(
    val currentLevel: Int? = null,
    val supportedLevels: List<Int> = emptyList(),
    val capabilitiesKnown: Boolean = false,
    val pendingLevel: Int? = null,
    val status: String = "Zoom state not read yet",
    val readbackTimedOut: Boolean = false
) {
    fun label(level: Int?): String = when (level) {
        0 -> "Off"
        1 -> "1:1"
        2 -> "2:1"
        null -> "Not reported"
        else -> "Zoom $level"
    }
}
