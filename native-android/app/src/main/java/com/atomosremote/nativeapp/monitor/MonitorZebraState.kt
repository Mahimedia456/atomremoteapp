package com.atomosremote.nativeapp.monitor

data class MonitorZebraState(
    val enabled: Boolean? = null,
    val threshold: Int? = null,
    val pendingKey: String? = null,
    val status: String = "Zebra state not read yet"
) {
    val busy: Boolean
        get() = pendingKey != null
}
