package com.atomosremote.nativeapp.audio

/** R26.40: Consolidated Audio GET/SET capability contract from supplied macOS
 * Contents.zip + controlled AG2 2026-10-14 captures. NO BLE or network code.
 * Existing Audio page GET 0x0647 -> 0x8647 is the ONLY hardware-proven request.
 * A SET or per-setting GET must be blocked unless actual complete request bytes,
 * exact response type, device capability and physical GET-after-SET proof exist.
 */
object AudioR2640UnifiedContract {
    const val PAGE_GET = 0x0647
    const val PAGE_RX = 0x8647
    const val INDEPENDENT_GETS_HARDWARE_CONFIRMED = 0
    const val INDIVIDUAL_SETS_HARDWARE_CONFIRMED = 0
    enum class Proof { HARDWARE_CALIBRATED, RAW_PAGE_INFERRED, NOT_REPORTED }
    enum class Access { READ_ONLY_PAGE, BLOCKED_INDIVIDUAL_GET, BLOCKED_SET }
    data class Page(val raw: AudioR26PageSnapshot.Readback, val rawHex: String) {
        val analogEmbedName: String get() = when(raw.analogEmbed) {
            0L -> "Off"
            1L -> "1/2"
            else -> "Option ${raw.analogEmbed} (unmapped)"
        }
        val fieldsCount: Int get() = 11
    }
    data class Capability(val name: String, val access: Access, val proof: Proof,
                          val commandOpcode: Int? = null, val reason: String)

    fun readback(hex: String): Page? {
        val p = AudioR26PageSnapshot.decode(hex) ?: return null
        return Page(p, hex.lowercase())
    }
    /** The exact eight original Audio UI readback rows; no layout changes.
     * All except Analog Embed 0/1 remain STRUCTURALLY INFERRED, not calibrated.
     */
    fun readbackRows(hex: String): List<Pair<String,String>> {
        val p = readback(hex) ?: return emptyList()
        val s = p.raw
        return listOf(
            "Monitor selector (inferred)" to "${s.monitor} raw",
            "Headphones level (inferred)" to "${s.phonesLevel} / ${s.phonesLevelMax} raw",
            "Record-enabled IDs (inferred)" to s.recordEnabledList.joinToString(", ", "[", "]"),
            "Valid recording IDs (inferred)" to s.recordValidList.joinToString(", ", "[", "]"),
            "Monitor option IDs (inferred)" to s.monitorList.joinToString(", ", "[", "]"),
            "Analog embed options (inferred)" to s.analogEmbedList.joinToString(", ", "[", "]"),
            AudioR2637_39AnalogEmbed.mainPageRow(hex) ?: ("Analog Embed" to "Readback unavailable"),
            "Orientation (inferred)" to "${s.orientation} raw; changeable=${s.orientationChangeable}"
        )
    }
    /** Never equate a successful GATT write with a typed RX, and never equate
     * page RX with a separately verified Mic/Line gain or digital audio meter.
     */
    fun classify(method: String): Capability {
        if (method == "AudioConfigurationPageGet") return Capability(method, Access.READ_ONLY_PAGE,
            Proof.HARDWARE_CALIBRATED, PAGE_GET, "AG2 zero-payload GET and 0x8647 typed RX verified")
        val e = AudioR26RecoveredMap.allKeys.firstOrNull { it.method == method }
            ?: return Capability(method, Access.BLOCKED_INDIVIDUAL_GET, Proof.NOT_REPORTED,
                null, "No verified official Audio method")
        val access = if (e.kind == "SET") Access.BLOCKED_SET else Access.BLOCKED_INDIVIDUAL_GET
        return Capability(method, access, Proof.NOT_REPORTED,
            e.opcode.takeIf{it > 0}, "macOS opcode only; no proven complete protobuf body + correlated AG2 RX")
    }
    /** Used for a strict, auditable privilege decision. Not a BLE send API. */
    fun isSafeReadOnlyWireRequest(opcode: Int, payload: ByteArray): Boolean =
        opcode == PAGE_GET && payload.isEmpty()
    fun isAnyIndividualSetWireProven(@Suppress("UNUSED_PARAMETER") method: String,
                                     @Suppress("UNUSED_PARAMETER") payload: ByteArray): Boolean = false
    fun changedFields(beforeHex: String, afterHex: String): List<Int> {
        val a=readback(beforeHex)?.raw ?: return emptyList()
        val b=readback(afterHex)?.raw ?: return emptyList()
        return listOf(1 to (a.monitor!=b.monitor),2 to (a.phonesLevel!=b.phonesLevel),
            3 to (a.recordEnabledList!=b.recordEnabledList),
            4 to (a.recordValidList!=b.recordValidList),
            5 to (a.monitorList!=b.monitorList),6 to (a.analogEmbedList!=b.analogEmbedList),
            7 to (a.phonesLevelMin!=b.phonesLevelMin),
            8 to (a.phonesLevelMax!=b.phonesLevelMax),
            9 to (a.orientation!=b.orientation),
            10 to (a.orientationChangeable!=b.orientationChangeable),
            11 to (a.analogEmbed!=b.analogEmbed)).filter{it.second}.map{it.first}
    }
}
