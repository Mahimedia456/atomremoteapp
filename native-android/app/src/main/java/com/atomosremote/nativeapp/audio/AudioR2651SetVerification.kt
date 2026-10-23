package com.atomosremote.nativeapp.audio

/** R26.51: one-device physical A/B evidence and future SET receipt verification.
 * Does NOT send BLE, PacketLink, or recorder write messages.
 * A physical recorder change is explicitly NOT proof of an Android SET.
 */
object AudioR2651SetVerification {
    enum class Status {
        NO_BASELINE, WAIT_FRESH_TYPED_RX, INVALID_PAGE, NO_CHANGE,
        WRONG_FIELD, MULTIPLE_FIELDS, MAPPING_REQUIRED, INVALID_REC_DELTA,
        PHYSICAL_CHANGE_CONFIRMED, SET_NOT_PROVEN, UNSUPPORTED
    }
    data class Result(val status: Status, val detail: String,
                      val changedField: Int? = null, val before: String = "", val after: String = "") {
        val physicalEvidence: Boolean get() = status == Status.PHYSICAL_CHANGE_CONFIRMED
    }
    private val recKeys: Set<String> = (AudioRecCalibratedChannels.pairKeys +
        listOf("analogLeft", "analogRight")).toSet()
    private val scalarFields: Map<String, Int> = mapOf(
        "analogMonitor" to 1,
        "phones" to 2,
        "orientation" to 9,
        "embed" to 11
    )
    fun supported(key: String): Boolean = key in recKeys || key in scalarFields
    fun expectedField(key: String): Int? = if (key in recKeys) 3 else scalarFields[key]

    /** A/B is for physically changing ONE setting on AG2, not for sending a SET. */
    fun comparePhysical(
        key: String, baselineHex: String, currentHex: String,
        baselineGeneration: Int, currentGeneration: Int,
        sameDeviceLinked: Boolean, currentTypedRx: Boolean,
        collectorRunning: Boolean, confirmedRecIds: Map<String, Long>
    ): Result {
        val expected = expectedField(key)
            ?: return Result(Status.UNSUPPORTED, "No page field for this individual setting")
        val old = AudioR26PageSnapshot.decode(baselineHex)
            ?: return Result(Status.NO_BASELINE, "Capture a valid fresh 0x8647 baseline")
        if (!sameDeviceLinked || collectorRunning || !currentTypedRx ||
            baselineGeneration < 0 || currentGeneration <= baselineGeneration)
            return Result(Status.WAIT_FRESH_TYPED_RX,
                "Change exactly one option on the physical recorder, then Refresh and wait for a newer typed 0x8647 page")
        val now = AudioR26PageSnapshot.decode(currentHex)
            ?: return Result(Status.INVALID_PAGE, "Latest page does not pass strict 11-field decoding")
        val changes = AudioR2640UnifiedContract.changedFields(baselineHex, currentHex)
        if (changes.isEmpty()) return Result(Status.NO_CHANGE,
            "New typed GET received; physical setting is unchanged")
        if (changes.size != 1) return Result(Status.MULTIPLE_FIELDS,
            "Multiple fields changed (${changes.joinToString()}); cannot attribute safely")
        if (changes.single() != expected) return Result(Status.WRONG_FIELD,
            "Observed f${changes.single()}, expected f$expected for $key", changes.single())
        val (before, after) = when (expected) {
            1 -> old.monitor.toString() to now.monitor.toString()
            2 -> old.phonesLevel.toString() to now.phonesLevel.toString()
            9 -> old.orientation.toString() to now.orientation.toString()
            11 -> old.analogEmbed.toString() to now.analogEmbed.toString()
            3 -> {
                val id = confirmedRecIds[key]
                    ?: return Result(Status.MAPPING_REQUIRED,
                        "Confirm physical REC ID using existing LEARN, Refresh, CONFIRM first", 3)
                if (id !in old.recordValidList || id !in now.recordValidList ||
                    old.recordValidList != now.recordValidList)
                    return Result(Status.INVALID_REC_DELTA,
                        "Saved REC ID not valid on both snapshots", 3)
                val removed = old.recordEnabledList.toSet() - now.recordEnabledList.toSet()
                val added = now.recordEnabledList.toSet() - old.recordEnabledList.toSet()
                if ((removed + added) != setOf(id) || (removed.size + added.size) != 1)
                    return Result(Status.INVALID_REC_DELTA,
                        "REC f3 change does not exclusively toggle the calibrated ID $id", 3)
                (if (id in old.recordEnabledList) "REC ON" else "REC OFF") to
                    (if (id in now.recordEnabledList) "REC ON" else "REC OFF")
            }
            else -> return Result(Status.UNSUPPORTED, "Unexpected field")
        }
        return Result(Status.PHYSICAL_CHANGE_CONFIRMED,
            "Physical recorder $key changed f$expected: $before -> $after. " +
                "This is NOT evidence that Android transmitted a SET.", expected, before, after)
    }

    enum class TxProof { MACOS_OPCODE_ONLY, COMPLETE_WIRE_BYTES, TYPED_ACK, POST_SET_READBACK }
    data class SetGate(val allowed: Boolean, val missing: Set<TxProof>, val detail: String)

    /** No per-control SET has complete AG2 bytes and typed ACK in the evidence supplied.
     * Adding a candidate opcode or a matching *physical* A/B sample cannot open this.
     */
    fun preflightHardwareSet(key: String, originalHex: String): SetGate {
        val hasGet = AudioR26PageSnapshot.decode(originalHex) != null
        val evidence = AudioR2643_45WireProof.proofFor(key)
        return SetGate(false, setOf(TxProof.COMPLETE_WIRE_BYTES, TxProof.TYPED_ACK,
            TxProof.POST_SET_READBACK),
            "${evidence.setter ?: "Unknown SET"} candidate=${evidence.setOpcode?.let { "0x" + it.toString(16) } ?: "none"}; " +
                "pageGET=${if (hasGet) "valid" else "missing"}. " +
                "BLOCKED: full serialized bytes + correlated AG2 reply + post-SET readback absent")
    }
    fun mayTransmit(@Suppress("UNUSED_PARAMETER") key: String,
                    @Suppress("UNUSED_PARAMETER") candidateBytes: ByteArray): Boolean = false
}
