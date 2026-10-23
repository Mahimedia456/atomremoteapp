package com.atomosremote.nativeapp.audio

/** Receive-side only, deliberately narrow Audio GET reply correlation.
 *
 * PacketLink delivery clears its owner before the recorder's standalone
 * Audio page arrives on some AG2 runs. This pure gate does not send packets,
 * change BLE ownership, infer write success, or unlock individual SETs.
 */
object AudioR26LateTypedGate {
    fun accept(
        activeCommand: Int,
        responseType: Int,
        bodyHex: String,
        local: Boolean,
        pendingPurpose: String?,
        physicalWriteMs: Long,
        transportCompleteMs: Long,
        nowMs: Long,
        windowMs: Long
    ): Boolean {
        if (activeCommand != 0x0647 || responseType != 0x8647 || !local) return false
        if (pendingPurpose != null) return false
        if (physicalWriteMs <= 0L || transportCompleteMs <= 0L) return false
        if (transportCompleteMs < physicalWriteMs) return false
        if (windowMs <= 0L || nowMs < transportCompleteMs || nowMs - transportCompleteMs > windowMs) return false
        return AudioR26ProtoCandidates.parsePage(bodyHex) != null
    }
}
