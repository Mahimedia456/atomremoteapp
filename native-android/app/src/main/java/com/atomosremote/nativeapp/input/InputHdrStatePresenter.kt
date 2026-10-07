package com.atomosremote.nativeapp.input

/**
 * 20.03.6.5 HDR Enable + HDR Auto presentation.
 *
 * Uses the existing proven HDR state readback. The AG2 payload observed in
 * hardware is protobuf-like: 08 <enabled> 10 <changeable>.
 * HDR Auto is NOT inferred from the changeable flag.
 */
object InputHdrStatePresenter {
    data class View(
        val hdr: String,
        val control: String,
        val hdrAuto: String,
        val confirmed: Boolean
    )

    fun build(rawState: String?, rawAuto: String?, responses: Int): View {
        val enabled = extractId(rawState)
        val changeable = extractChangeable(rawState)

        return View(
            hdr = when (enabled) {
                0 -> "Off"
                1 -> "On"
                else -> "Not reported by recorder"
            },
            control = when (changeable) {
                true -> "Available"
                false -> "Locked by recorder"
                null -> "Not reported by recorder"
            },
            hdrAuto = sanitize(rawAuto) ?: "Not reported by recorder",
            confirmed = responses >= 2 && enabled != null
        )
    }

    private fun extractId(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        return Regex("""\bID\s+(\d+)\b""")
            .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: raw.trim().toIntOrNull()
    }

    private fun extractChangeable(raw: String?): Boolean? {
        if (raw.isNullOrBlank()) return null
        val hex = Regex("""(?:raw|RAW)\s+([0-9A-Fa-f]+)""")
            .find(raw)?.groupValues?.getOrNull(1) ?: return null
        val bytes = hex.chunked(2).mapNotNull { it.toIntOrNull(16) }
        val tagIndex = bytes.indexOf(0x10)
        if (tagIndex >= 0 && bytes.size > tagIndex + 1) {
            return when (bytes[tagIndex + 1]) {
                0 -> false
                1 -> true
                else -> null
            }
        }
        return null
    }

    private fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.substringBefore(" · raw ")
            .substringBefore(" Ã‚Â· raw ")
            .substringAfterLast(" · ")
            .substringAfterLast(" Ã‚Â· ")
            .trim()
            .takeIf {
                it.isNotBlank() &&
                    !it.startsWith("type ", ignoreCase = true) &&
                    !it.startsWith("ID ", ignoreCase = true)
            }
    }
}
