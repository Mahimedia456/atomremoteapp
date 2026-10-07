package com.atomosremote.nativeapp.monitor

data class MonitorGuideState(
    val frameGuideEnabled: Boolean? = null,
    val format: Int? = null,
    val gridEnabled: Boolean? = null,
    val mode: Int? = null,
    val customRatio: Int? = null,
    val pendingKey: String? = null,
    val status: String = "Guide state not read yet"
) {
    fun formatLabel(value: Int?): String = when (value) {
        0 -> "None"
        1 -> "16:9"
        2 -> "1.91:1"
        3 -> "2.41:1"
        4 -> "1.9:1"
        5 -> "1.85:1"
        6 -> "4:3"
        7 -> "2.35:1"
        8 -> "1:1"
        9 -> "4:5"
        10 -> "9:16"
        12 -> "Custom"
        null -> "Not reported"
        else -> "Preset $value"
    }
}
