package com.atomosremote.nativeapp.monitor

data class MonitorScopeState(
    val enabled: Boolean? = null,
    val mode: Int? = null,
    val size: Int? = null,
    val opacity: Int? = null,
    val brightness: Int? = null,
    val pendingKey: String? = null,
    val status: String = "Scope state not read yet"
) {
    fun modeLabel(value: Int?): String = when (value) {
        0 -> "Luma Parade"
        1 -> "RGB Parade"
        2 -> "Vectorscope"
        3 -> "Vectorscope Zoom"
        null -> "Not reported"
        else -> "Mode $value"
    }

    fun sizeLabel(value: Int?): String = when (value) {
        0 -> "1/4"
        1 -> "1/3"
        2 -> "Full"
        null -> "Not reported"
        else -> "Size $value"
    }
}
