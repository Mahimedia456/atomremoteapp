package com.atomosremote.nativeapp.advanced

data class AdvancedControlAction(
    val section: String,
    val label: String,
    val commandKey: String,
    val description: String
)

object AdvancedControlCatalog {
    val actions = listOf(
        AdvancedControlAction("INPUT", "Gamma: Log", "input-gamma-log", "Select validated Log gamma/EOTF command"),
        AdvancedControlAction("INPUT", "Gamma: Rec.709", "input-gamma-rec709", "Select validated Rec.709 gamma/EOTF command"),
        AdvancedControlAction("INPUT", "Gamut: Native", "input-gamut-native", "Select validated input gamut command"),
        AdvancedControlAction("RECORD", "ProRes 422 HQ", "record-codec-prores-hq", "Validated recorder codec command"),
        AdvancedControlAction("RECORD", "ProRes 422", "record-codec-prores-422", "Validated recorder codec command"),
        AdvancedControlAction("OUTPUT", "LUT Preview On", "output-lut-preview-on", "Enable validated LUT preview command"),
        AdvancedControlAction("OUTPUT", "LUT Preview Off", "output-lut-preview-off", "Disable validated LUT preview command"),
        AdvancedControlAction("OUTPUT", "HDR Output On", "output-hdr-on", "Enable validated HDR output command"),
        AdvancedControlAction("OUTPUT", "HDR Output Off", "output-hdr-off", "Disable validated HDR output command")
    )
}
