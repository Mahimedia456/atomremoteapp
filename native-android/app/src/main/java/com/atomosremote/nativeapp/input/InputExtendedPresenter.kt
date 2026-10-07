package com.atomosremote.nativeapp.input

object InputExtendedPresenter {
    data class View(
        val hdmiStandard: String,
        val hdmiStandardOptions: String,
        val triggerSource: String,
        val triggerEnabled: String,
        val hdrAuto: String,
        val camera: String,
        val gamma: String,
        val gamut: String,
        val cameraOptions: String,
        val gammaOptions: String,
        val gamutOptions: String,
        val sdiLink: String,
        val activeSdiSource: String,
        val inputRange: String,
        val rawInput: String,
        val cameraMetadata: String,
        val stateStatus: String,
        val rawPayloads: Map<String, String>
    )

    fun build(snapshot: InputExtendedSnapshot): View {
        fun value(key: String): String =
            snapshot.values[key]?.takeIf {
                it.isNotBlank() &&
                    !it.startsWith("NO_RESPONSE") &&
                    !it.startsWith("REJECT")
            } ?: "Not reported by recorder"

        val state = when {
            snapshot.running -> "Reading recorder..."
            snapshot.unchanged -> "Current"
            snapshot.completed > 0 -> "Updated"
            else -> "Not refreshed"
        }

        return View(
            hdmiStandard = value("hdmiStandard"),
            hdmiStandardOptions = value("hdmiStandardList"),
            triggerSource = value("triggerSource"),
            triggerEnabled = value("triggerEnabled"),
            hdrAuto = value("hdrAuto"),
            camera = value("hdrCamera"),
            gamma = value("hdrGamma"),
            gamut = value("hdrGamut"),
            cameraOptions = value("hdrCameraList"),
            gammaOptions = value("hdrGammaList"),
            gamutOptions = value("hdrGamutList"),
            sdiLink = value("sdiLink"),
            activeSdiSource = value("activeSdiSource"),
            inputRange = value("inputRange"),
            rawInput = value("rawStatus"),
            cameraMetadata = value("cameraMetadata"),
            stateStatus = state,
            rawPayloads = snapshot.rawPayloads
        )
    }
}
