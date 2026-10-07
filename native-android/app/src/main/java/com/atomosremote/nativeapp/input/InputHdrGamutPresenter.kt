package com.atomosremote.nativeapp.input

/**
 * 20.03.6.8 HDR Gamut presentation.
 *
 * Presentation-only. It shows only a recorder-provided official label.
 */
object InputHdrGamutPresenter {
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
