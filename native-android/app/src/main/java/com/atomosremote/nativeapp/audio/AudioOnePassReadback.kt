package com.atomosremote.nativeapp.audio

/** One consolidated Audio/Mic-Line readback summary. No new recorder commands.
 *  AG2 evidence: 0x0647 -> 0x8647. f2/f8 and f3 are raw page fields,
 *  not calibrated independent level/routing GETs. f11 Off/1&2 calibration.
 */
object AudioOnePassReadback {
    data class Snapshot(val valid: Boolean, val headphones: String,
        val recIds: String, val validIds: String, val monitor: String,
        val embedded: String, val micGain: String, val speaker: String,
        val individualWriteSafe: Boolean = false)
    fun decode(hex: String): Snapshot {
        val p = AudioR2640UnifiedContract.readback(hex)?.raw ?: return Snapshot(
            false,"Awaiting verified GET","Awaiting verified GET","Awaiting verified GET",
            "Awaiting verified GET","Awaiting verified GET","Not reported","Not reported")
        val embed = when(p.analogEmbed) { 0L -> "Off";1L -> "1 & 2";else -> "Mode ${p.analogEmbed} raw" }
        return Snapshot(true, "${p.phonesLevel} / ${p.phonesLevelMax} raw",
            p.recordEnabledList.joinToString(", ").ifEmpty { "None reported" },
            p.recordValidList.joinToString(", ").ifEmpty { "None reported" },
            "${p.monitor} raw",embed,"Not reported","Not reported")
    }
    enum class Refresh { DISCONNECTED, VERIFIED_PAGE, SHARED_OWNER_BUSY, WAITING_TYPED_RX, READBACK_PENDING }
    fun refreshState(linked: Boolean, hasVerifiedPage: Boolean, running: Boolean,
                     status: String): Refresh = when {
        !linked -> Refresh.DISCONNECTED
        hasVerifiedPage -> Refresh.VERIFIED_PAGE
        status.contains("Waiting for Input/Output transport owner",ignoreCase=true) ||
          status.contains("owner busy",ignoreCase=true) -> Refresh.SHARED_OWNER_BUSY
        running -> Refresh.WAITING_TYPED_RX
        else -> Refresh.READBACK_PENDING
    }
    fun statusLabel(state: Refresh): String = when(state) {
        Refresh.DISCONNECTED -> "Disconnected. No current recorder readings"
        Refresh.VERIFIED_PAGE -> "Recorder 0x8647 sample available"
        Refresh.SHARED_OWNER_BUSY -> "Audio GET waiting behind active Input/PacketLink request"
        Refresh.WAITING_TYPED_RX -> "Audio GET waiting for recorder response"
        Refresh.READBACK_PENDING -> "Audio page not yet received. Use Refresh"
    }
    fun allowIndividualSet(@Suppress("UNUSED_PARAMETER") key: String) = false
}
