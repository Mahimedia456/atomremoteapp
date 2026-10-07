package com.atomosremote.nativeapp.input

/**
 * 20.03.6.4 Device Identity presentation.
 *
 * Reads only the existing proven Device Name snapshot. No BLE/PacketLink command.
 */
object InputDeviceIdentityPresenter {
    data class View(
        val deviceName: String,
        val recorderName: String,
        val confirmed: Boolean
    )

    fun build(raw: String?, recorderName: String, responses: Int): View {
        val reported = extractPrintableName(raw)
        return View(
            deviceName = reported ?: recorderName.ifBlank { "Not reported by recorder" },
            recorderName = recorderName.ifBlank { "Not reported by recorder" },
            confirmed = responses >= 3 && !raw.isNullOrBlank()
        )
    }

    private fun extractPrintableName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        val cleanedParts = raw.split(" · ", " Ã‚Â· ")
        cleanedParts.firstOrNull { part ->
            part.isNotBlank() &&
                !part.startsWith("type ", ignoreCase = true) &&
                !part.startsWith("ID ", ignoreCase = true) &&
                !part.startsWith("raw ", ignoreCase = true)
        }?.trim()?.takeIf { it.isNotBlank() }?.let { return it }

        val rawHex = Regex("""(?:raw|RAW)\s+([0-9A-Fa-f]+)""")
            .find(raw)?.groupValues?.getOrNull(1) ?: return null
        if (rawHex.length % 2 != 0) return null

        val bytes = rawHex.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }
        if (bytes.size < 2) return null

        // AG2 observed DeviceName payload: 0A <len> <UTF-8 bytes>.
        if ((bytes[0].toInt() and 0xFF) == 0x0A) {
            val length = bytes[1].toInt() and 0xFF
            if (length > 0 && bytes.size >= 2 + length) {
                val text = bytes.subList(2, 2 + length).toByteArray()
                    .toString(Charsets.UTF_8)
                    .trim()
                if (text.isNotBlank() && text.all { !it.isISOControl() }) return text
            }
        }
        return null
    }
}
