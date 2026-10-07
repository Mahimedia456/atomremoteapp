package com.atomosremote.nativeapp.input

/**
 * 20.03.6.9 HDR capability-list presentation.
 *
 * No supported-list command is sent in this batch. Capability availability is
 * therefore explicit: if the recorder has not provided a typed list, the app
 * says "Not reported by recorder" instead of showing guessed options.
 */
object InputHdrCapabilitiesPresenter {
    data class View(
        val camera: String,
        val gamma: String,
        val gamut: String,
        val cameraOptions: String,
        val gammaOptions: String,
        val gamutOptions: String
    )

    fun build(
        camera: String,
        gamma: String,
        gamut: String
    ): View = View(
        camera = camera,
        gamma = gamma,
        gamut = gamut,
        cameraOptions = "Not reported by recorder",
        gammaOptions = "Not reported by recorder",
        gamutOptions = "Not reported by recorder"
    )
}
