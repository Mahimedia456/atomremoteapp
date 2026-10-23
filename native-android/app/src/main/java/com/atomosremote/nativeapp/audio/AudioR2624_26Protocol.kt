package com.atomosremote.nativeapp.audio

/** R26.24-26: macOS constructor evidence + strictly typed, READ-ONLY AG2 Audio page.
 * This module cannot write to a recorder. The BLE/TCS/PacketLink core is untouched.
 * macOS getter names alone do not establish that sending their opcode is read-only.
 */
object AudioR2624_26Protocol {
    const val PAGE_GET = 0x0647
    const val PAGE_RX = 0x8647

    data class ConstructorEvidence(val name: String, val opcode: Int,
                                   val argumentCount: Int, val macOSVA: String)
    // x86_64 call sites/constructor opcode immediates in official macOS binary.
    // Argument counts represent observed *call-site input*, not protobuf tags.
    val getNamedConstructors = listOf(
        ConstructorEvidence("MonitorGet", 0x06A5, 1, "0x10007C0A0"),
        ConstructorEvidence("MonitorListGet", 0x06A7, 2, "0x10007C150"),
        ConstructorEvidence("PhonesLevelGet", 0x06AB, 1, "0x10007C5B0"),
        ConstructorEvidence("OrientationGet", 0x06AF, 1, "0x10007C6C0"),
        ConstructorEvidence("AnalogEmbedGet", 0x06B1, 1, "0x10007C770"),
        ConstructorEvidence("InputSourceGet", 0x06B4, 1, "0x10007C890"),
        ConstructorEvidence("GainGet", 0x06B7, 2, "0x10007CA00"),
        ConstructorEvidence("FrameDelayGet", 0x06BA, 1, "0x10007CC40"),
        ConstructorEvidence("SpeakerLevelGet", 0x06BF, 1, "0x10007CE20"),
        ConstructorEvidence("SpeakerMuteGet", 0x06C3, 1, "0x10007CFA0")
    )

    data class Field(val number: Int, val wire: Int, val scalar: Long? = null,
                     val packed: List<Int>? = null) {
        fun render(): String = if (wire == 0) scalar.toString()
            else packed.orEmpty().joinToString(" ", prefix = "[", postfix = "]")
    }
    data class Page(val fields: List<Field>) {
        fun field(index: Int) = fields.first { it.number == index }
        val fieldCount: Int get() = fields.size
        val rawRows: List<String> get() = fields.map { "f${it.number} ${it.render()}" }
    }

    /** The macOS reflection lists 11 properties, but mapping to numbered tags
     * is a structural inference. Do not present these names as calibrated.
     */
    val inferredMacOSFields = listOf(
        "monitor", "phonesLevel", "recordEnabledList", "recordValidList",
        "monitorList", "analogEmbedList", "phonesLevelMin", "phonesLevelMax",
        "orientation", "orientationChangeable", "analogEmbed"
    )

    /** Parses only the observed f1..f11 AG2 0x8647 wire shape. No dB guesses,
     * no unknown tags silently discarded, no stale cached values accepted.
     * All enum/field semantics are kept RAW pending independent calibration.
     */
    fun decodeObservedPage(hex: String): Page? {
        if (hex.length !in 70..160 || hex.length % 2 != 0 ||
            !hex.all { it in "0123456789abcdefABCDEF" }) return null
        val b = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        var pos = 0
        fun readVarint(): Long? {
            var value = 0L
            var shift = 0
            while (pos < b.size && shift < 64) {
                val c = b[pos++].toInt() and 0xff
                if (shift == 63 && (c and 0x7f) > 1) return null
                value = value or ((c.toLong() and 0x7fL) shl shift)
                if (c and 0x80 == 0) return value
                shift += 7
            }
            return null
        }
        val out = ArrayList<Field>()
        repeat(11) {
            val key = readVarint() ?: return null
            val number = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            if (number !in 1..11 || out.any { it.number == number }) return null
            if (number in listOf(1, 2, 7, 8, 9, 10, 11) && wire == 0) {
                val scalar = readVarint() ?: return null
                if (scalar < 0) return null
                out.add(Field(number, wire, scalar))
            } else if (number in 3..6 && wire == 2) {
                val len = readVarint() ?: return null
                if (len > 16 || len < 0 || len > b.size - pos) return null
                val end = pos + len.toInt()
                val items = mutableListOf<Int>()
                while (pos < end) {
                    val value = readVarint() ?: return null
                    if (pos > end || value !in 0..255) return null
                    items.add(value.toInt())
                }
                out.add(Field(number, wire, packed = items))
            } else return null
        }
        if (pos != b.size || out.size != 11) return null
        val byNumber = out.associateBy { it.number }
        if (byNumber.size != 11) return null
        val min = byNumber[7]?.scalar ?: return null
        val max = byNumber[8]?.scalar ?: return null
        val level = byNumber[2]?.scalar ?: return null
        if (min > max || level !in min..max) return null
        if (byNumber[10]?.scalar !in 0L..1L) return null
        return Page(out.sortedBy { it.number })
    }

    /** This is a real-value raw delta, not an inferred named-setting change. */
    fun diff(previousHex: String, currentHex: String): List<String> {
        val previous = decodeObservedPage(previousHex) ?: return emptyList()
        val current = decodeObservedPage(currentHex) ?: return emptyList()
        return current.fields.mapNotNull { now ->
            val before = previous.field(now.number)
            if (before == now) null
            else "f${now.number}: ${before.render()} -> ${now.render()}"
        }
    }

    /** R26.26 safe command gate: no setter has independently proven exact
     * protobuf serialization, response behavior AND recorder capability.
     * Therefore *every* Audio SET is blocked. There is no BLE sender here.
     */
    fun isRecorderWriteAllowed(opcode: Int, payload: ByteArray): Boolean = false

    /** Only the existing empty-body 0x0647 page request is verified to be a GET.
     * A macOS *Get-named* constructor is not wire-eligible by its name alone.
     */
    fun isVerifiedReadOnlyRequest(opcode: Int, payload: ByteArray): Boolean =
        opcode == PAGE_GET && payload.isEmpty()
}
