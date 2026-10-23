package com.atomosremote.nativeapp.audio

/** UI-only readback presenter. Nothing here builds or transmits BLE/RemoteApi commands.
 * Field names are STRUCTURAL INFERENCES from macOS Swift metadata, except
 * Analog Embed f11=0/1, which has 2026-10-14 controlled AG2 calibration.
 * Other fields are not calibrated
 * control semantics and not evidence that any individual GET/SET is supported.
 */
object AudioR2630Readable {
    enum class Origin { CURRENT_TYPED_RX, LAST_VERIFIED_THIS_VIEW, ACTIVE_SESSION_SNAPSHOT, NONE }
    data class Selection(val hex: String, val origin: Origin, val label: String) {
        val available get() = hex.isNotEmpty() && origin != Origin.NONE
        val isCurrentRx get() = origin == Origin.CURRENT_TYPED_RX
    }
    private fun valid(hex: String): Boolean = AudioR26PageSnapshot.decode(hex) != null

    fun select(linked: Boolean, currentTypedRx: String, lastVerifiedInView: String,
               currentSessionSnapshot: String): Selection {
        if (!linked) return Selection("", Origin.NONE, "Recorder disconnected")
        if (valid(currentTypedRx)) return Selection(currentTypedRx, Origin.CURRENT_TYPED_RX,
            "Verified from latest Audio GET")
        if (valid(lastVerifiedInView)) return Selection(lastVerifiedInView,
            Origin.LAST_VERIFIED_THIS_VIEW, "Last verified readback in this Audio view (not fresh)")
        if (valid(currentSessionSnapshot)) return Selection(currentSessionSnapshot,
            Origin.ACTIVE_SESSION_SNAPSHOT, "Cached recorder snapshot (not a new GET)")
        return Selection("", Origin.NONE, "Waiting for recorder Audio response")
    }

    fun primaryRows(rawHex: String): List<Pair<String, String>> {
        return AudioR2640UnifiedContract.readbackRows(rawHex)
    }

    fun compactLabel(key: String, rawHex: String): String? {
        val s = AudioR2640UnifiedContract.readback(rawHex)?.raw ?: return null
        return when (key) {
            "phones" -> "${s.phonesLevel} / ${s.phonesLevelMax} raw*"
            "analogMonitor" -> "${s.monitor} raw*"
            "analogRecord" -> "IDs: ${s.recordEnabledList.joinToString(",")}*"
            "orientation" -> "${s.orientation} raw*"
            "embed" -> AudioR2637_39AnalogEmbed.compactLabel(rawHex)
            else -> null
        }
    }
}
