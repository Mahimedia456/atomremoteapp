package com.atomosremote.nativeapp.audio

/**
 * R26.23: PURE decision rule. The BLE/PacketLink/TCS implementation is unchanged.
 * Not a timer-based disconnect, queue clear, or speculative device command.
 * A vanished MTU queue entry may be re-requested only while ATT is idle.
 */
object AudioR2623MtuOrphanPolicy {
    fun shouldRearm(
        negotiatedMtu: Int,
        wasRequested: Boolean,
        requestState: String,
        attBusy: Boolean,
        hasActiveOperation: Boolean,
        mtuStillQueued: Boolean,
        elapsedMs: Long,
        rearmCount: Int
    ): Boolean = negotiatedMtu < 61 && wasRequested && requestState == "queued" &&
        !attBusy && !hasActiveOperation && !mtuStillQueued &&
        elapsedMs >= 8_000L && rearmCount in 0..1
}
