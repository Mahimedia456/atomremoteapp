package com.atomosremote.nativeapp.audio

/**
 * Two real AG2 0x8647 page snapshots, strictly decoded and compared.
 * Offline/read-only. Zero packet creation. In particular, differing packed
 * IDs do not establish pair-to-ID mapping until manually calibrated.
 */
object AudioR26SnapshotDelta {
    data class Change(val name: String, val before: String, val after: String)
    data class Result(val baselineVerified: Boolean, val currentVerified: Boolean,
                      val changes: List<Change>)

    fun compare(beforeHex: String, afterHex: String): Result {
        val a = AudioR26PageSnapshot.decode(beforeHex)
        val b = AudioR26PageSnapshot.decode(afterHex)
        if (a == null || b == null) return Result(a != null, b != null, emptyList())
        val changes = mutableListOf<Change>()
        fun put(name: String, old: Any, new: Any) {
            if (old != new) changes += Change(name, old.toString(), new.toString())
        }
        put("f1 Monitor selector", a.monitor, b.monitor)
        put("f2 Headphones level", a.phonesLevel, b.phonesLevel)
        put("f3 REC enabled ID list", a.recordEnabledList, b.recordEnabledList)
        put("f4 REC valid ID list", a.recordValidList, b.recordValidList)
        put("f5 Monitor options", a.monitorList, b.monitorList)
        put("f6 Embed options", a.analogEmbedList, b.analogEmbedList)
        put("f7 Headphones min", a.phonesLevelMin, b.phonesLevelMin)
        put("f8 Headphones max", a.phonesLevelMax, b.phonesLevelMax)
        put("f9 Meters orientation", a.orientation, b.orientation)
        put("f10 Orientation changeable", a.orientationChangeable, b.orientationChangeable)
        put("f11 Analog embed", a.analogEmbed, b.analogEmbed)
        return Result(true, true, changes)
    }
}
