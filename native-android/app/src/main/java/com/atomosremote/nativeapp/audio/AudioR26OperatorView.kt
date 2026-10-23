package com.atomosremote.nativeapp.audio

/** Only recorder-backed Audio page interpretation. Does NOT authorize hardware SET.
 * Official macOS fields alone do not prove a channel-ID map, orientation enum,
 * Mic/Line gain or Speaker command serialization.
 */
object AudioR26OperatorView {
    data class Readout(
        val valid: Boolean,
        val phonesLevel: Long?, val phonesMin: Long?, val phonesMax: Long?,
        val phonesFraction: Float,
        val monitorRaw: Long?, val monitorChoices: List<Long>,
        val recEnabledIds: List<Long>, val recValidIds: List<Long>,
        val orientationRaw: Long?, val orientationChangeable: Boolean?,
        val analogEmbed: String,
        val micGainReported: Boolean = false, val speakerReported: Boolean = false,
        val canSendWrite: Boolean = false
    ) {
        val headphonesLabel: String get() = if (phonesLevel == null || phonesMax == null) "Not reported"
            else "$phonesLevel / $phonesMax raw"
        val monitorLabel: String get() = monitorRaw?.let { "Mode $it (raw)" } ?: "Not reported"
        val orientationLabel: String get() = orientationRaw?.let { "Mode $it (raw)" } ?: "Not reported"
        val recEnabledLabel: String get() = if(!valid) "Not reported" else recEnabledIds.joinToString(", ").ifEmpty { "None" }
    }
    fun decode(hex: String): Readout {
        val p = AudioR26PageSnapshot.decode(hex) ?: return Readout(false,null,null,null,0f,null,
            emptyList(), emptyList(), emptyList(), null, null, "Not reported")
        val range = p.phonesLevelMax - p.phonesLevelMin
        val fraction = if (range <= 0L) 0f else
            ((p.phonesLevel - p.phonesLevelMin).toFloat() / range.toFloat()).coerceIn(0f,1f)
        return Readout(true,p.phonesLevel,p.phonesLevelMin,p.phonesLevelMax,fraction,p.monitor,
            p.monitorList,p.recordEnabledList,p.recordValidList,p.orientation,
            p.orientationChangeable == 1L,
            when(p.analogEmbed) {0L -> "Off";1L -> "1 & 2";else -> "Mode ${p.analogEmbed} (raw)"})
    }
    fun orientationOptions() = listOf("Vertical", "Horizontal")
    fun canMapRecIdToPair(@Suppress("UNUSED_PARAMETER") id: Long): Boolean = false
    fun canSendAudioSet(@Suppress("UNUSED_PARAMETER") key: String): Boolean = false
}
