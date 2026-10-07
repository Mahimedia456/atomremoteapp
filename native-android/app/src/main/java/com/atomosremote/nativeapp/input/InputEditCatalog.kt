package com.atomosremote.nativeapp.input

/**
 * 20.03.7 Input edit matrix.
 *
 * Static RemoteApi command IDs recovered from the supplied official macOS
 * ATOMOS Remote executable (Contents(10)).
 *
 * 20.03.7.0-7.2:
 *   SourceSet          = 0x0066
 *   HdmiStandardSet    = 0x006A
 *   TriggerSourceSet   = 0x006C
 *   TriggerEnabledSet  = 0x006E
 *
 * 20.03.7.3-7.5:
 *   HdrEnableStateSet  = 0x0072
 *   HdrCameraSet       = 0x0074
 *   HdrGammaSet        = 0x0076
 *   HdrAutoEnableSet   = 0x007A
 *
 * 20.03.7.6-7.8:
 *   HdrGamutSet        = 0x0078
 *   RangeSet           = 0x0082
 *
 * 20.03.7.7 intentionally does NOT invent an ActiveSdiSourceSet or
 * SdiLinkStateSet because the recovered official Input API exposes only the
 * corresponding getters. SDI input selection continues through SourceSet.
 *
 * The UI may offer protocol-family values when a recorder does not return a
 * capability list, but a selection becomes current only after recorder GET
 * readback confirms the requested value.
 */
object InputEditCatalog {
    const val SOURCE_SET = 0x0066
    const val HDMI_STANDARD_SET = 0x006A
    const val TRIGGER_SOURCE_SET = 0x006C
    const val TRIGGER_ENABLED_SET = 0x006E

    const val HDR_ENABLE_STATE_SET = 0x0072
    const val HDR_CAMERA_SET = 0x0074
    const val HDR_GAMMA_SET = 0x0076
    const val HDR_AUTO_ENABLE_SET = 0x007A
    const val HDR_GAMUT_SET = 0x0078
    const val INPUT_RANGE_SET = 0x0082

    data class Option(val value: Int, val label: String)

    val sourceOptions = listOf(
        Option(0, "HDMI"),
        Option(1, "SDI A"),
        Option(2, "SDI B"),
        Option(3, "SDI Dual Link"),
        Option(4, "SDI Quad Link"),
        Option(5, "SDI Multistream"),
        Option(6, "SDI Auto Select"),
        Option(7, "HDMI Multistream")
    )

    val sdiSourceOptions = sourceOptions.filter { it.label.startsWith("SDI") }

    val hdmiStandardOptions = listOf(
        Option(0, "HDMI 1.4"),
        Option(1, "HDMI 2.0"),
        Option(2, "HDMI Compatibility")
    )

    val triggerSourceOptions = listOf(
        Option(0, "None"),
        Option(1, "Panasonic"),
        Option(2, "RED Epic"),
        Option(3, "Sony"),
        Option(4, "Canon"),
        Option(5, "ARRI"),
        Option(6, "HDMI Auto"),
        Option(7, "Timecode"),
        Option(8, "Ikegami"),
        Option(9, "Panasonic Type 3"),
        Option(10, "JVC")
    )

    val hdrCameraOptions = listOf(
        Option(0, "Standard"),
        Option(1, "Sony"),
        Option(2, "Canon"),
        Option(3, "Panasonic"),
        Option(4, "ARRI"),
        Option(5, "JVC"),
        Option(6, "RED"),
        Option(7, "Rec.2100"),
        Option(8, "Fujifilm"),
        Option(9, "Nikon"),
        Option(10, "ProRes RAW"),
        Option(11, "Blackmagic"),
        Option(12, "Z CAM"),
        Option(13, "Sigma"),
        Option(14, "Olympus"),
        Option(15, "Leica"),
        Option(16, "OM Digital")
    )

    val hdrGammaOptions = listOf(
        Option(0, "Rec.709"),
        Option(1, "PQ (ST 2084)"),
        Option(2, "HLG"),
        Option(3, "S-Log"),
        Option(4, "S-Log2"),
        Option(5, "S-Log3"),
        Option(6, "C-Log"),
        Option(7, "C-Log2"),
        Option(8, "C-Log3"),
        Option(9, "J-Log1"),
        Option(10, "F-Log"),
        Option(11, "N-Log"),
        Option(12, "V-Log"),
        Option(13, "Panasonic RAW 10"),
        Option(14, "Log3G10"),
        Option(15, "Log3G12"),
        Option(16, "RED LogFilm"),
        Option(17, "LogC EI 160"),
        Option(18, "LogC EI 200"),
        Option(19, "LogC EI 250"),
        Option(20, "LogC EI 320"),
        Option(21, "LogC EI 400"),
        Option(22, "LogC EI 500"),
        Option(23, "LogC EI 640"),
        Option(24, "LogC EI 800"),
        Option(25, "LogC EI 1000"),
        Option(26, "LogC EI 1280"),
        Option(27, "LogC EI 1600"),
        Option(28, "ProRes RAW"),
        Option(29, "Panasonic RAW"),
        Option(30, "Blackmagic Film"),
        Option(31, "Blackmagic Film 4K"),
        Option(32, "Blackmagic Film 4.6K"),
        Option(33, "Linear RAW"),
        Option(34, "Z-Log2"),
        Option(35, "OM-Log400"),
        Option(36, "Flat"),
        Option(37, "L-Log"),
        Option(38, "Canon R5 12-bit"),
        Option(39, "Canon R5 10-bit")
    )


    val hdrGamutOptions = listOf(
        Option(0, "Rec.709"),
        Option(1, "BT.2020"),
        Option(2, "DCI-P3"),
        Option(3, "DCI-P3+"),
        Option(4, "P3-D65"),
        Option(5, "S-Gamut"),
        Option(6, "S-Gamut3"),
        Option(7, "S-Gamut3.Cine"),
        Option(8, "Canon Cinema"),
        Option(9, "Alexa Wide Gamut"),
        Option(10, "LS300"),
        Option(11, "DragonColor"),
        Option(12, "DragonColor2"),
        Option(13, "REDcolor2"),
        Option(14, "REDcolor3"),
        Option(15, "REDcolor4"),
        Option(16, "REDWideGamut"),
        Option(17, "V-Gamut"),
        Option(18, "Panasonic RAW 3200"),
        Option(19, "Panasonic RAW 4300"),
        Option(20, "Panasonic RAW 5600"),
        Option(21, "ProRes RAW"),
        Option(22, "Nikon RAW"),
        Option(23, "F-Gamut"),
        Option(24, "Z CAM RAW"),
        Option(25, "Fujifilm RAW"),
        Option(26, "Sigma RAW"),
        Option(27, "Olympus RAW"),
        Option(28, "OM Digital RAW")
    )

    val inputRangeOptions = listOf(
        Option(0, "Legal"),
        Option(1, "Full")
    )

    fun valueForLabel(options: List<Option>, label: String): Int? =
        options.firstOrNull { it.label.equals(label.trim(), ignoreCase = true) }?.value
}

data class InputSetterState(
    val busy: Boolean = false,
    val key: String? = null,
    val requestedLabel: String? = null,
    val requestedValue: Int? = null,
    val status: String = "Ready",
    val confirmed: Boolean = false
)
