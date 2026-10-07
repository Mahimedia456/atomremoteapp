package com.atomosremote.nativeapp.monitor

data class MonitorDisplayState(
    val backlightPercent: Int? = null,
    val backlightChangeable: Boolean? = null,
    val monitorPageReported: Boolean = false,
    val yLift: Int? = null,
    val yGammaHundredths: Int? = null,
    val yGainHundredths: Int? = null,
    val tallyEnabled: Boolean? = null,
    val flipEnabled: Boolean? = null,
    val supportsReported: Boolean = false,
    val tallySupported: Boolean? = null,
    val flipSupported: Boolean? = null,
    val requestedBacklightPercent: Int? = null,
    val requestedYLift: Int? = null,
    val requestedYGammaHundredths: Int? = null,
    val requestedYGainHundredths: Int? = null,
    val requestedTallyEnabled: Boolean? = null,
    val requestedFlipEnabled: Boolean? = null,
    val pendingKey: String? = null,
    val status: String = "Display state not read yet"
) {
    val shownBacklightPercent: Int? get() = requestedBacklightPercent ?: backlightPercent
    val shownYLift: Int? get() = requestedYLift ?: yLift
    val shownYGammaHundredths: Int? get() = requestedYGammaHundredths ?: yGammaHundredths
    val shownYGainHundredths: Int? get() = requestedYGainHundredths ?: yGainHundredths
    val shownTallyEnabled: Boolean? get() = requestedTallyEnabled ?: tallyEnabled
    val shownFlipEnabled: Boolean? get() = requestedFlipEnabled ?: flipEnabled

    val backlightCapabilityText: String
        get() = when {
            backlightChangeable == false -> "Unavailable on this recorder"
            backlightPercent != null -> "Recorder reported"
            else -> "Waiting for recorder report"
        }

    val flipCapabilityText: String
        get() = when (flipSupported) {
            false -> "Unavailable on this recorder"
            true -> "Supported"
            null -> "Capability not reported yet"
        }

    val tallyCapabilityText: String
        get() = when (tallySupported) {
            false -> "Unavailable on this recorder"
            true -> "Supported"
            null -> "Capability not reported yet"
        }
}
