package com.atomosremote.nativeapp.monitor

data class MonitorAssistEffectsState(
    val falseColourEnabled: Boolean? = null,
    val blueOnlyEnabled: Boolean? = null,
    val settingsChangeable: Boolean? = null,
    val pendingKey: String? = null,
    val status: String = "Exposure tools not read yet"
)
