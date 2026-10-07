package com.atomosremote.nativeapp.input

object InputHdmiStandardCatalog {
    /**
     * Protocol/UI family recovered from the official Input terminology.
     * This does NOT claim both values are hardware-supported by the connected AG2.
     */
    val protocolFamily = listOf("HDMI 1.4", "HDMI 2.0")

    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return "Not reported by recorder"
        val v = raw.trim()
        return when {
            v.contains("2.0", ignoreCase = true) -> "HDMI 2.0"
            v.contains("1.4", ignoreCase = true) -> "HDMI 1.4"
            else -> v
        }
    }
}
