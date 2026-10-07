package com.atomosremote.nativeapp.input

/**
 * 20.03.6.7 HDR Gamma presentation.
 *
 * Presentation-only. It shows the recorder-reported label when present and
 * never invents numeric mappings or sends a PacketLink command.
 */
object InputHdrGammaPresenter {
    fun display(raw: String?): String = sanitize(raw)

    private fun sanitize(raw: String?): String {
        if (raw.isNullOrBlank()) return "Not reported by recorder"

        val cleaned = raw.substringBefore(" · raw ")
            .substringBefore(" Ã‚Â· raw ")
            .split(" · ", " Ã‚Â· ")
            .firstOrNull { part ->
                part.isNotBlank() &&
                    !part.startsWith("type ", ignoreCase = true) &&
                    !part.startsWith("ID ", ignoreCase = true) &&
                    !part.startsWith("raw ", ignoreCase = true)
            }
            ?.trim()

        return cleaned?.takeIf { it.isNotBlank() }
            ?: "Not reported by recorder"
    }
}
