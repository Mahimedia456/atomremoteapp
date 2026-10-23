package com.atomosremote.nativeapp.audio

/**
 * Recorder-backed enabled/valid ID states from the typed 0x8647 page.
 * Per-pair naming requires a real single-toggle hardware calibration.
 * No wire writes or candidate SET payloads are created here.
 */
object AudioRecCalibratedChannels {
    // Exact macOS REC row order only. Numeric recorder IDs are never inferred from order.
    val pairKeys = listOf("analogLeft", "ch12", "ch34", "ch56", "ch78", "ch910", "ch1112", "analogRight")
    data class IdState(val id: Long, val valid: Boolean, val enabled: Boolean)
    data class ToggleEvidence(val id: Long?, val message: String)

    fun idStates(hex: String): List<IdState> {
        val p = AudioR26PageSnapshot.decode(hex) ?: return emptyList()
        if (p.recordValidList.distinct() != p.recordValidList ||
            p.recordEnabledList.distinct() != p.recordEnabledList) return emptyList()
        val valid = p.recordValidList.toSet()
        if (!valid.containsAll(p.recordEnabledList)) return emptyList()
        return valid.sorted().map { IdState(it, true, it in p.recordEnabledList) }
    }

    fun uniqueHardwareToggle(beforeHex: String, afterHex: String): ToggleEvidence {
        val before = AudioR26PageSnapshot.decode(beforeHex)
            ?: return ToggleEvidence(null, "Before page is not verified")
        val after = AudioR26PageSnapshot.decode(afterHex)
            ?: return ToggleEvidence(null, "After page is not verified")
        if (before.recordValidList.toSet() != after.recordValidList.toSet() ||
            before.recordValidList.distinct().size != before.recordValidList.size ||
            after.recordValidList.distinct().size != after.recordValidList.size)
            return ToggleEvidence(null, "Valid recorder ID set changed")
        if (before.recordEnabledList.distinct().size != before.recordEnabledList.size ||
            after.recordEnabledList.distinct().size != after.recordEnabledList.size)
            return ToggleEvidence(null, "Duplicate enabled IDs")
        val valid = after.recordValidList.toSet()
        if (!valid.containsAll(before.recordEnabledList) || !valid.containsAll(after.recordEnabledList))
            return ToggleEvidence(null, "Enabled ID not in valid list")
        if (before.monitor != after.monitor || before.phonesLevel != after.phonesLevel ||
            before.monitorList != after.monitorList || before.analogEmbedList != after.analogEmbedList ||
            before.phonesLevelMin != after.phonesLevelMin || before.phonesLevelMax != after.phonesLevelMax ||
            before.orientation != after.orientation ||
            before.orientationChangeable != after.orientationChangeable ||
            before.analogEmbed != after.analogEmbed)
            return ToggleEvidence(null, "Other Audio field also changed; repeat one-option calibration")
        val oldIds = before.recordEnabledList.toSet()
        val newIds = after.recordEnabledList.toSet()
        val changed = (oldIds - newIds) + (newIds - oldIds)
        if (changed.size != 1)
            return ToggleEvidence(null, "Change exactly one recorder REC option, then refresh")
        val id = changed.single()
        return ToggleEvidence(id, "Recorder-backed single REC toggle: ID $id")
    }

    fun decodeSaved(saved: String?): Map<String, Long> {
        if (saved.isNullOrBlank()) return emptyMap()
        val out = linkedMapOf<String, Long>()
        for (item in saved.split(';')) {
            val parts = item.split('=')
            if (parts.size != 2 || parts[0] !in pairKeys) return emptyMap()
            val id = parts[1].toLongOrNull() ?: return emptyMap()
            if (id !in 0..255 || parts[0] in out || id in out.values) return emptyMap()
            out[parts[0]] = id
        }
        return out.toMap()
    }
    fun encodeSaved(mapping: Map<String, Long>): String {
        require(mapping.keys.all { it in pairKeys } && mapping.values.all { it in 0..255 } &&
            mapping.values.distinct().size == mapping.size)
        return pairKeys.mapNotNull { key -> mapping[key]?.let { "$key=$it" } }.joinToString(";")
    }

    fun mappedStatus(pairKey: String, mapping: Map<String, Long>, hex: String): String {
        val id = mapping[pairKey] ?: return "Needs recorder calibration"
        val states = idStates(hex)
        if (states.isEmpty()) return "ID $id: awaiting fresh recorder GET"
        val match = states.firstOrNull { it.id == id }
            ?: return "ID $id: not available on current recorder"
        return "REC ID $id: " + if (match.enabled) "ON" else "OFF"
    }
}
