package com.atomosremote.nativeapp.audio

/** Offline-safe R26.34-36 evidence capture. Does not use BLE or send commands.
 * A field-number correlation is NOT a verified GET/SET payload or channel mapping.
 */
object AudioR2634_36Calibration {
    val targetLabels = listOf(
        "Monitor selector", "Headphones volume", "REC channel selection",
        "Monitor channel selection", "Analog Embed", "Orientation",
        "Mic / Line source", "Mic / Line gain L", "Mic / Line gain R",
        "Audio frame delay", "Speaker level", "Speaker mute", "Other Audio setting"
    )
    data class Observation(
        val target: String,
        val field: Int,
        val before: String,
        val after: String,
        val baselineGeneration: Int,
        val currentGeneration: Int
    )
    data class Assessment(val status: String, val detail: String, val candidateField: Int? = null)
    /** Only a strict one-field delta in a new typed RX may be associated with a named
     * setting explicitly identified by the operator who changed it on the recorder.
     */
    fun observe(target: String, baselineHex: String, currentHex: String,
                baselineGeneration: Int, currentGeneration: Int,
                currentTypedRx: Boolean, collectorRunning: Boolean): Observation? {
        if (target !in targetLabels || baselineGeneration < 0 || currentGeneration <= baselineGeneration ||
            !currentTypedRx || collectorRunning) return null
        val before = AudioR2624_26Protocol.decodeObservedPage(baselineHex) ?: return null
        val after = AudioR2624_26Protocol.decodeObservedPage(currentHex) ?: return null
        val changed = after.fields.filter { before.field(it.number) != it }
        if (changed.size != 1) return null
        val f = changed.single()
        return Observation(target, f.number, before.field(f.number).render(), f.render(),
            baselineGeneration, currentGeneration)
    }
    /** Does not elevate confidence to full semantic/protocol proof regardless of count.
     * Conflicting mappings are surfaced instead of silently choosing a field.
     */
    fun assess(target: String, observations: List<Observation>): Assessment {
        val matches = observations.filter { it.target == target }
        if (matches.isEmpty()) return Assessment("NO_EVIDENCE", "Collect fresh baseline and change only this setting")
        val fields = matches.map { it.field }.distinct()
        if (fields.size != 1) return Assessment("CONFLICT", "Different field numbers changed; investigate before mapping")
        val field = fields.single()
        val elsewhere = observations.any { it.target != target && it.field == field }
        if (elsewhere) return Assessment("CONFLICT", "f$field was also linked to another setting", field)
        return if (matches.map { it.currentGeneration }.distinct().size >= 2)
            Assessment("REPEATED_CORRELATION", "f$field changed on 2+ fresh recorder reads; still NOT a proven GET/SET", field)
        else Assessment("SINGLE_OBSERVATION", "f$field correlated once; repeat with another value", field)
    }
    const val VERIFIED_READ_OPCODE = 0x0647
    /** Codec and setters must stay read-only until wire-proven and tested on AG2. */
    fun isSafeToTransmit(opcode: Int, payload: ByteArray): Boolean =
        opcode == VERIFIED_READ_OPCODE && payload.isEmpty()
    fun isAudioSetAllowed(@Suppress("UNUSED_PARAMETER") opcode: Int,
                          @Suppress("UNUSED_PARAMETER") payload: ByteArray): Boolean = false
}
