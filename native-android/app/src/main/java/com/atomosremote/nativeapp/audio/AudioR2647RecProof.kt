package com.atomosremote.nativeapp.audio

/** R26.47. Offline-only evidence gate for REC label -> numeric recorder ID learning.
 * This class cannot emit a command; actual recorder GET is the unchanged 0x0647 collector.
 * Operator has to choose a label, change that single physical option, obtain a fresh typed
 * 0x8647 in a NEW collector generation, and explicitly confirm the candidate.
 */
object AudioR2647RecProof {
    data class Candidate(
        val targetKey: String,
        val rawId: Long,
        val switchedOn: Boolean,
        val baselineGeneration: Int,
        val observedGeneration: Int,
        val label: String
    )
    data class Verdict(val candidate: Candidate?, val message: String)

    fun evaluate(targetKey: String, baseline: String, fresh: String,
                 baselineGeneration: Int, freshGeneration: Int,
                 currentTypedRx: Boolean, collectorRunning: Boolean): Verdict {
        if (targetKey !in AudioRecCalibratedChannels.pairKeys)
            return Verdict(null, "Unrecognized channel label")
        if (!currentTypedRx || collectorRunning || baselineGeneration < 0 ||
            freshGeneration <= baselineGeneration)
            return Verdict(null, "Waiting for a new completed typed 0x8647 GET")
        if (baseline.equals(fresh, ignoreCase = true))
            return Verdict(null, "No REC change observed; toggle ONE selected recorder switch, then refresh")
        val evidence = AudioRecCalibratedChannels.uniqueHardwareToggle(baseline, fresh)
        val id = evidence.id ?: return Verdict(null, evidence.message)
        val before = AudioR26PageSnapshot.decode(baseline)
            ?: return Verdict(null, "Baseline not decoded")
        val after = AudioR26PageSnapshot.decode(fresh)
            ?: return Verdict(null, "New readback not decoded")
        val oldValue = id in before.recordEnabledList
        val newValue = id in after.recordEnabledList
        if (oldValue == newValue) return Verdict(null, "REC state unchanged")
        if (id !in after.recordValidList) return Verdict(null, "ID absent from recorder valid set")
        return Verdict(Candidate(targetKey, id, newValue, baselineGeneration, freshGeneration,
            targetKey), "Candidate $targetKey = REC ID $id (${if (newValue) "ON" else "OFF"}). " +
                "Confirm ONLY if the physical switch you touched was $targetKey. No SET sent.")
    }

    /** Duplicate/stale cross-assignments must never override existing evidence. */
    fun canConfirm(candidate: Candidate?, targetKey: String,
                   mappings: Map<String, Long>, linked: Boolean,
                   currentGeneration: Int, currentHex: String): Boolean {
        val c = candidate ?: return false
        if (!linked || c.targetKey != targetKey || c.rawId !in 0L..255L ||
            currentGeneration != c.observedGeneration) return false
        val snapshot = AudioR26PageSnapshot.decode(currentHex) ?: return false
        if (c.rawId !in snapshot.recordValidList ||
            ((c.rawId in snapshot.recordEnabledList) != c.switchedOn)) return false
        return mappings.none { (k, v) -> k != targetKey && v == c.rawId }
    }

    fun canTransmitAnyAudioSet(@Suppress("UNUSED_PARAMETER") opcode: Int,
                               @Suppress("UNUSED_PARAMETER") body: ByteArray): Boolean = false
}
