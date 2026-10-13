package com.atomosremote.nativeapp.audio

/**
 * R26.11.4: forensic A/B comparison of already correlated, complete typed 0x8647 bodies.
 * The parser does not claim any audio semantics, dB levels, or channel mapping.
 * Caller must establish strict response type, session validity and fresh hardware RX.
 */
object AudioR26ABDiff {
    data class Change(
        val fieldNumber: Int, val occurrence: Int, val before: String, val after: String
    )
    data class Result(val valid: Boolean, val changed: List<Change>, val status: String)

    fun compare(beforeHex: String, afterHex: String): Result {
        val before = AudioR26WireInspector.inspect(beforeHex)
        val after = AudioR26WireInspector.inspect(afterHex)
        if (before.status != "RAW_PARSED_UNMAPPED" || after.status != "RAW_PARSED_UNMAPPED") {
            return Result(false, emptyList(), "INVALID_INPUT")
        }
        if (before.fields.isEmpty() || after.fields.isEmpty()) {
            return Result(false, emptyList(), "EMPTY_PAGE")
        }
        // Protobuf allows repeated keys. Compare by (number, occurrence), not arbitrary position.
        fun indexed(fields: List<AudioR26WireInspector.Field>): Map<Pair<Int, Int>, String> {
            val seen = mutableMapOf<Int, Int>()
            val result = LinkedHashMap<Pair<Int, Int>, String>()
            for (f in fields) {
                val occurrence = (seen[f.number] ?: 0) + 1
                seen[f.number] = occurrence
                result[f.number to occurrence] = "w${f.wireType}:${f.detail}"
            }
            return result
        }
        val a = indexed(before.fields)
        val b = indexed(after.fields)
        val allKeys = (a.keys + b.keys).toSet().sortedWith(
            compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        val changes = allKeys.mapNotNull { key ->
            val x = a[key] ?: "<absent>"
            val y = b[key] ?: "<absent>"
            if (x == y) null else Change(key.first, key.second, x, y)
        }
        return Result(true, changes, if (changes.isEmpty()) "UNCHANGED_RAW" else "FIELDS_CHANGED")
    }
}
