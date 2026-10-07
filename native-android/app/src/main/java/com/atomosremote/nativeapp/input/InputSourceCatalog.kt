package com.atomosremote.nativeapp.input

object InputSourceCatalog {
    data class Entry(val id: Int, val label: String)

    val protocolFamily = listOf(
        Entry(0, "HDMI"),
        Entry(1, "SDI A"),
        Entry(2, "SDI B"),
        Entry(3, "SDI Dual Link"),
        Entry(4, "SDI Quad Link"),
        Entry(5, "SDI Multistream"),
        Entry(6, "SDI Auto Select"),
        Entry(7, "HDMI Multistream")
    )

    fun sourceId(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        return Regex("""\bID\s+(\d+)\b""")
            .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: raw.trim().toIntOrNull()
    }

    fun labelFor(raw: String?): String {
        if (raw.isNullOrBlank()) return "Not reported"
        val id = sourceId(raw)
        return protocolFamily.firstOrNull { it.id == id }?.label
            ?: raw.substringBefore(" · raw ")
                .substringBefore(" Ã‚Â· raw ")
                .ifBlank { "Not reported" }
    }
}
