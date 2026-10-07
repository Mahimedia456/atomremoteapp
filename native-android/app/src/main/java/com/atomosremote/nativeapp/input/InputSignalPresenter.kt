package com.atomosremote.nativeapp.input

object InputSignalPresenter {
    data class SignalView(
        val format: String,
        val codecId: String,
        val available: Boolean,
        val rawAvailable: Boolean
    )

    fun build(inputFormatInfo: String?, inputCodecId: Int?, rawHex: String?): SignalView {
        val format = inputFormatInfo?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Not reported by recorder"
        return SignalView(
            format = format,
            codecId = inputCodecId?.toString() ?: "Not reported",
            available = format != "Not reported by recorder" || inputCodecId != null,
            rawAvailable = !rawHex.isNullOrBlank()
        )
    }
}
