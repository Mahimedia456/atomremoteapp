package com.atomosremote.nativeapp.input

/**
 * Official AtomProtobuf enum names recovered from the supplied macOS
 * ATOMOS Remote binary (Contents(10)).
 *
 * These are display mappings only. They do not send commands or setters.
 * Unknown values are not exposed as numeric IDs in production UI.
 */
object InputOfficialEnumNames {
    private val hdmiStandards = listOf(
        "HDMI 1.4",
        "HDMI 2.0",
        "HDMI Compatibility"
    )

    private val triggerSources = listOf(
        "None",
        "Panasonic",
        "RED Epic",
        "Sony",
        "Canon",
        "ARRI",
        "HDMI Auto",
        "Timecode",
        "Ikegami",
        "Panasonic Type 3",
        "JVC"
    )

    private val cameras = listOf(
        "Standard",
        "Sony",
        "Canon",
        "Panasonic",
        "ARRI",
        "JVC",
        "RED",
        "Rec.2100",
        "Fujifilm",
        "Nikon",
        "ProRes RAW",
        "Blackmagic",
        "Z CAM",
        "Sigma",
        "Olympus",
        "Leica",
        "OM Digital"
    )

    private val gamuts = listOf(
        "Rec.709",
        "BT.2020",
        "DCI-P3",
        "DCI-P3+",
        "P3-D65",
        "S-Gamut",
        "S-Gamut3",
        "S-Gamut3.Cine",
        "Canon Cinema",
        "Alexa Wide Gamut",
        "LS300",
        "DragonColor",
        "DragonColor2",
        "REDcolor2",
        "REDcolor3",
        "REDcolor4",
        "REDWideGamut",
        "V-Gamut",
        "Panasonic RAW 3200",
        "Panasonic RAW 4300",
        "Panasonic RAW 5600",
        "ProRes RAW",
        "Nikon RAW",
        "F-Gamut",
        "Z CAM RAW",
        "Fujifilm RAW",
        "Sigma RAW",
        "Olympus RAW",
        "OM Digital RAW"
    )

    // Exact generated HDRGamma enum order in the official binary.
    private val gammas = listOf(
        "Rec.709",
        "PQ (ST 2084)",
        "HLG",
        "S-Log",
        "S-Log2",
        "S-Log3",
        "C-Log",
        "C-Log2",
        "C-Log3",
        "J-Log1",
        "F-Log",
        "N-Log",
        "V-Log",
        "Panasonic RAW 10",
        "Log3G10",
        "Log3G12",
        "RED LogFilm",
        "LogC EI 160",
        "LogC EI 200",
        "LogC EI 250",
        "LogC EI 320",
        "LogC EI 400",
        "LogC EI 500",
        "LogC EI 640",
        "LogC EI 800",
        "LogC EI 1000",
        "LogC EI 1280",
        "LogC EI 1600",
        "ProRes RAW",
        "Panasonic RAW",
        "Blackmagic Film",
        "Blackmagic Film 4K",
        "Blackmagic Film 4.6K",
        "Linear RAW",
        "Z-Log2",
        "OM-Log400",
        "Flat",
        "L-Log",
        "Canon R5 12-bit",
        "Canon R5 10-bit"
    )

    private val activeSdiSources = listOf("SDI A", "SDI B")
    private val ranges = listOf("Legal", "Full")
    private val rawTypes = listOf("Sony", "Canon", "Panasonic", "HDMI", "Sony 16-bit")

    fun hdmiStandard(value: Int?): String? = lookup(hdmiStandards, value)
    fun triggerSource(value: Int?): String? = lookup(triggerSources, value)
    fun camera(value: Int?): String? = lookup(cameras, value)
    fun gamma(value: Int?): String? = lookup(gammas, value)
    fun gamut(value: Int?): String? = lookup(gamuts, value)
    fun activeSdiSource(value: Int?): String? = lookup(activeSdiSources, value)
    fun range(value: Int?): String? = lookup(ranges, value)
    fun rawType(value: Int?): String? = lookup(rawTypes, value)

    fun enabled(value: Int?): String? = when (value) {
        0 -> "Off"
        1 -> "On"
        else -> null
    }

    fun linkState(value: Int?): String? = when (value) {
        0 -> "Inactive"
        1 -> "Active"
        else -> null
    }

    fun cameraList(values: List<Int>): String? = listNames(cameras, values)
    fun gammaList(values: List<Int>): String? = listNames(gammas, values)
    fun gamutList(values: List<Int>): String? = listNames(gamuts, values)
    fun hdmiStandardList(values: List<Int>): String? = listNames(hdmiStandards, values)

    private fun lookup(names: List<String>, value: Int?): String? {
        if (value == null || value !in names.indices) return null
        return names[value]
    }

    private fun listNames(names: List<String>, values: List<Int>): String? {
        val decoded = values.distinct().mapNotNull { lookup(names, it) }
        return decoded.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}

/**
 * Small protobuf reader used only for read-only Input response presentation.
 * It extracts scalar varints and packed repeated varints without exposing raw IDs.
 */
object InputProtoReader {
    fun fieldVarint(body: ByteArray, targetField: Int): Int? =
        fields(body)[targetField]?.firstOrNull()

    fun fieldVarints(body: ByteArray, targetField: Int): List<Int> =
        fields(body)[targetField].orEmpty()

    fun firstUtf8(body: ByteArray): String? {
        var i = 0
        while (i < body.size) {
            val key = readVarint(body, i) ?: return null
            i = key.second
            val wire = key.first and 0x07
            when (wire) {
                0 -> {
                    val v = readVarint(body, i) ?: return null
                    i = v.second
                }
                2 -> {
                    val lenV = readVarint(body, i) ?: return null
                    val len = lenV.first
                    i = lenV.second
                    if (len < 0 || i + len > body.size) return null
                    val bytes = body.copyOfRange(i, i + len)
                    val text = runCatching { bytes.toString(Charsets.UTF_8).trim() }.getOrNull()
                    if (!text.isNullOrBlank() && text.all { !it.isISOControl() }) return text
                    i += len
                }
                1 -> i += 8
                5 -> i += 4
                else -> return null
            }
        }
        return null
    }

    private fun fields(body: ByteArray): Map<Int, MutableList<Int>> {
        val out = linkedMapOf<Int, MutableList<Int>>()
        var i = 0

        while (i < body.size) {
            val key = readVarint(body, i) ?: break
            i = key.second
            val field = key.first ushr 3
            val wire = key.first and 0x07
            if (field <= 0) break

            when (wire) {
                0 -> {
                    val v = readVarint(body, i) ?: break
                    i = v.second
                    out.getOrPut(field) { mutableListOf() }.add(v.first)
                }
                2 -> {
                    val lenV = readVarint(body, i) ?: break
                    val len = lenV.first
                    i = lenV.second
                    if (len < 0 || i + len > body.size) break
                    val end = i + len

                    // Packed repeated enums are encoded as a run of varints.
                    val packed = mutableListOf<Int>()
                    var p = i
                    var packedValid = true
                    while (p < end) {
                        val v = readVarint(body, p)
                        if (v == null || v.second > end) {
                            packedValid = false
                            break
                        }
                        packed.add(v.first)
                        p = v.second
                    }
                    if (packedValid && p == end && packed.isNotEmpty()) {
                        out.getOrPut(field) { mutableListOf() }.addAll(packed)
                    }
                    i = end
                }
                1 -> i += 8
                5 -> i += 4
                else -> break
            }
        }
        return out
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Int, Int>? {
        var value = 0
        var shift = 0
        var i = start
        while (i < bytes.size && shift <= 28) {
            val b = bytes[i].toInt() and 0xFF
            value = value or ((b and 0x7F) shl shift)
            i += 1
            if ((b and 0x80) == 0) return value to i
            shift += 7
        }
        return null
    }
}
