package com.atomosremote.nativeapp.audio

/**
 * R27.05: macOS Audio UI initialization calls these empty-payload GET constructors.
 * The AG2 response layout is still NOT hardware verified. No setter is enabled.
 * 0x06B4, 0x06B7 and 0x06BA were previously mislabeled GETs; macOS change
 * handlers call them with selected/slider values, i.e. setter candidates.
 */
internal object AudioR2705MacReadOnlyCommands {
    fun get(key: String): Int? = when (key) {
        "gainLeft", "gainRight" -> 0x06B6 // macOS gain values request (left/right mapping from RX pending)
        "delay" -> 0x06B9            // macOS frame delay current request
        "inputLevel" -> 0x06B3       // macOS input-source current request
        else -> null
    }
}
