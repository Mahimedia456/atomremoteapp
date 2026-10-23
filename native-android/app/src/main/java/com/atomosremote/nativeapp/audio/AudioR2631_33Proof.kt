package com.atomosremote.nativeapp.audio

/** R26.31-33: audit-backed Audio UI evidence, no GATT, PacketLink, or SET operations.
 * The old 0x0647/0x8647 page GET remains the one hardware-verified request.
 * macOS Mach-O constructors prove method IDs and call-site arguments, NOT
 * serialized wire payloads or AG2 GET-vs-SET behavior.
 */
object AudioR2631_33Proof {
    enum class Level { PAGE_VERIFIED, CONSTRUCTOR_ONLY, WRITE_BLOCKED }
    data class KeyProof(val name: String, val opcode: Int, val observedArgs: Int, val level: Level)
    val individualGets: List<KeyProof> = AudioR2624_26Protocol.getNamedConstructors.map {
        KeyProof(it.name, it.opcode, it.argumentCount, Level.CONSTRUCTOR_ONLY)
    }
    val pageGet = KeyProof("AudioMetersPageGet", 0x0647, 0, Level.PAGE_VERIFIED)

    /** Do NOT infer that a method ending in Get is safe to send. */
    fun txEligible(opcode: Int, payload: ByteArray): Boolean =
        opcode == pageGet.opcode && payload.isEmpty()

    fun methodStatus(name: String): String {
        if (name == pageGet.name) return "0x0647 verified page GET"
        val k = individualGets.firstOrNull { it.name == name }
            ?: return "No byte-verified individual command mapping"
        return "0x${k.opcode.toString(16).uppercase().padStart(4, '0')} " +
            "(${k.observedArgs} macOS call input${if (k.observedArgs == 1) "" else "s"}); " +
            "request body / RX schema NOT VERIFIED"
    }

    data class Comparison(val code: String, val detail: String,
                          val changes: List<String> = emptyList(),
                          val newSampleConfirmed: Boolean = false)

    /** Generation gating ensures a cached payload or a local GATT TX success can
     * NEVER stand in for a newly received 0x8647 typed recorder response.
     */
    fun compare(baselineHex: String, currentHex: String,
                baselineGeneration: Int, currentGeneration: Int,
                currentTypedRx: Boolean, collectorRunning: Boolean): Comparison {
        val old = AudioR2624_26Protocol.decodeObservedPage(baselineHex)
            ?: return Comparison("NO_BASELINE", "Save a verified recorder sample first")
        if (collectorRunning || !currentTypedRx || currentGeneration <= baselineGeneration)
            return Comparison("WAIT_NEW_RX", "After changing one setting on AG2, refresh and wait for a NEW typed 0x8647 response")
        val fresh = AudioR2624_26Protocol.decodeObservedPage(currentHex)
            ?: return Comparison("INVALID_RX", "New sample did not pass strict 11-field validation")
        val changes = fresh.fields.mapNotNull { f ->
            val was = old.field(f.number)
            if (was == f) null else "f${f.number}: ${was.render()} -> ${f.render()}"
        }
        return if (changes.isEmpty())
            Comparison("UNCHANGED", "New recorder RX confirmed: all 11 raw fields identical", newSampleConfirmed = true)
        else Comparison("CHANGED", "New recorder RX confirmed: ${changes.size} protobuf field(s) changed",
            changes = changes, newSampleConfirmed = true)
    }

    data class SupportedPageValue(val name: String, val raw: String, val source: String)
    fun liveConfiguration(hex: String): List<SupportedPageValue> {
        val page = AudioR2624_26Protocol.decodeObservedPage(hex) ?: return emptyList()
        val f = { id: Int -> page.field(id) }
        return listOf(
            SupportedPageValue("Monitor selector", f(1).render(), "metadata inference, f1"),
            SupportedPageValue("Headphones level", "${f(2).render()} / ${f(8).render()}", "metadata inference, f2/f8"),
            SupportedPageValue("REC IDs", f(3).render(), "metadata inference, f3"),
            SupportedPageValue("Valid REC IDs", f(4).render(), "metadata inference, f4"),
            SupportedPageValue("Monitor options", f(5).render(), "metadata inference, f5"),
            SupportedPageValue("Analog embed options", f(6).render(), "metadata inference, f6"),
            SupportedPageValue("Headphones range", "${f(7).render()} to ${f(8).render()}", "metadata inference, f7/f8"),
            SupportedPageValue("Orientation", f(9).render(), "metadata inference, f9"),
            SupportedPageValue("Orientation changeable", f(10).render(), "metadata inference, f10"),
            SupportedPageValue("Analog embed", f(11).render(), "metadata inference, f11")
        )
    }
}
