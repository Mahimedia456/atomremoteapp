package com.atomosremote.nativeapp.monitor

data class MonitorFocusAssistState(
    val enabled: Boolean? = null,
    val type: Int? = null,
    val threshold: Int? = null,
    val supportedThresholds: List<Int> = emptyList(),
    val colour: Int? = null,
    val pendingKey: String? = null,
    val status: String = "Focus Assist state not read yet"
) {
    val busy: Boolean
        get() = pendingKey != null

    fun typeLabel(value: Int?): String = when (value) {
        1 -> "Thin"
        2 -> "Medium"
        3 -> "Thick"
        null -> "Not reported"
        else -> "Type $value"
    }

    fun colourLabel(value: Int?): String = when (value) {
        1 -> "White"
        2 -> "Red"
        3 -> "Green"
        4 -> "Blue"
        5 -> "Yellow"
        6 -> "Cyan"
        7 -> "Magenta"
        null -> "Not reported"
        else -> "Colour $value"
    }
}
