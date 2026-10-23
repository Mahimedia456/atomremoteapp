package com.atomosremote.nativeapp.audio

/** R26.73-81 source-side evidence gate. This file never emits recorder packets.
 * GET 0x0647 / response 0x8647 is verified on AG2; per-setting SET is not.
 */
object AudioR2673BatchAudit {
    const val VERIFIED_GET = 0x0647
    const val VERIFIED_RESPONSE = 0x8647
    data class Capability(val label: String, val getVerified: Boolean, val setVerified: Boolean, val note: String)
    val capabilities = listOf(
        Capability("Audio page", true, false, "Verified typed page response; not an individual SET"),
        Capability("Headphones", false, false, "Individual level getter/setter wire payload pending"),
        Capability("Analog monitor", false, false, "Selector SET mapping and readback pending"),
        Capability("Meters orientation", false, false, "Selector SET mapping and readback pending"),
        Capability("Audio embedded", false, false, "Selector SET mapping and readback pending"),
        Capability("Channel REC", false, false, "Enabled set observable in page; individual channel SET pending"),
        Capability("Ignore Audio Dropouts", false, false, "Individual GET/SET pending")
    )
    fun setAllowed(label: String): Boolean = capabilities.firstOrNull { it.label == label }?.setVerified == true
    fun synopsis(): String = "R26.73-81: Audio GET 0x0647 / RX 0x8647 verified; individual SET not verified"
}
