package com.atomosremote.nativeapp.audio

/** R26.42: Every Audio/Mic-Line setting stays visible even when a hardware RX
 * is delayed or absent. Values never masquerade as GET/SET capability.
 * Readback source: existing 0x0647 -> 0x8647 only.
 */
object AudioR2641Presentation {
    enum class Evidence { AG2_CALIBRATED, PAGE_INFERRED, NOT_REPORTED }
    data class Row(val key: String, val label: String, val value: String,
                   val detail: String, val evidence: Evidence)
    data class Screen(val audio: List<Row>, val micLine: List<Row>, val verified: Boolean,
                      val canWrite: Boolean = false)

    private fun pending(spec: AudioR26Setting): Row {
        val note = if (spec.pane == "MIC_LINE")
            "Individual AG2 Mic/Line GET response not verified"
        else "Awaiting a fresh verified recorder page or individual GET mapping"
        return Row(spec.key, spec.label, "Awaiting GET", note, Evidence.NOT_REPORTED)
    }

    fun decode(hex: String): Screen {
        val readback = AudioR2640UnifiedContract.readback(hex)
        val page = readback?.raw
        fun raw(key: String, value: String, detail: String) =
            Row(key, AudioR26Catalog.find(key)?.label ?: key, value, detail,
                Evidence.PAGE_INFERRED)
        val byKey = mutableMapOf<String, Row>()
        if (page != null) {
            val embedText = when (page.analogEmbed) {
                0L -> "Off"
                1L -> "1 & 2"
                else -> "Mode ${page.analogEmbed} raw"
            }
            byKey["embed"] = Row("embed", "Audio Embedded", embedText,
                if (page.analogEmbed in 0L..1L)
                    "AG2-calibrated f11 from 0x8647; f6 supported IDs: ${page.analogEmbedList.joinToString(", ")}"
                else "Recorder f11 reported; mode label needs hardware calibration",
                if (page.analogEmbed in 0L..1L) Evidence.AG2_CALIBRATED else Evidence.PAGE_INFERRED)
            byKey["phones"] = raw("phones", "${page.phonesLevel} / ${page.phonesLevelMax} raw",
                "Page f2/f8; range/scale interpretation not independently validated")
            byKey["analogMonitor"] = raw("analogMonitor", "${page.monitor} raw",
                "Page f1 monitor selector; choices from f5: ${page.monitorList.joinToString(", ")}. " +
                    "Channel meanings not calibrated")
            byKey["analogRecord"] = raw("analogRecord", "${page.recordEnabledList.size} IDs raw",
                "Page f3 enabled IDs: ${page.recordEnabledList.joinToString(", ")}; " +
                    "page f4 valid IDs: ${page.recordValidList.joinToString(", ")}. " +
                    "These are raw ID lists, NOT calibrated channel-to-index mappings")
            byKey["orientation"] = raw("orientation", "${page.orientation} raw",
                "Page f9 orientation; changeable f10=${page.orientationChangeable}; " +
                    "Vertical/Horizontal enum unverified")
        }
        val audio = AudioR26Catalog.forPane("AUDIO").map { byKey[it.key] ?: pending(it) }
        val micLine = AudioR26Catalog.forPane("MIC_LINE").map(::pending)
        return Screen(audio, micLine, page != null)
    }

    /** Do not authorize a hardware write from a UI option or opcode alone. */
    fun canSendAudioSet(@Suppress("UNUSED_PARAMETER") key: String): Boolean = false
    fun canShowLiveMeters(@Suppress("UNUSED_PARAMETER") hex: String): Boolean = false
}
