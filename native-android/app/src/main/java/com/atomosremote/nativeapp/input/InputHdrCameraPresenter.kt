package com.atomosremote.nativeapp.input

/**
 * 20.03.6.6 HDR Camera presentation.
 *
 * This phase deliberately does not invent a Camera enum or send a new GET.
 * If a typed Camera value already exists in the session snapshot it is shown
 * by official name; otherwise the UI says it was not reported.
 */
object InputHdrCameraPresenter {
    fun display(raw: String?): String {
        if (raw.isNullOrBlank()) return "Not reported by recorder"
        val cleaned = raw.substringBefore(" · raw ")
            .substringBefore(" Ã‚Â· raw ")
            .split(" · ", " Ã‚Â· ")
            .firstOrNull { part ->
                part.isNotBlank() &&
                    !part.startsWith("type ", ignoreCase = true) &&
                    !part.startsWith("ID ", ignoreCase = true)
            }
            ?.trim()
        return cleaned?.takeIf { it.isNotBlank() } ?: "Not reported by recorder"
    }
}
