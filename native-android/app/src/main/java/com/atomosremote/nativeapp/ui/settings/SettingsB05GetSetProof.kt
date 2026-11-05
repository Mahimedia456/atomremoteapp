package com.atomosremote.nativeapp.ui.settings

/**
 * B05 paired GET/SET protocol evidence matrix. This is an executable fail-closed
 * capability gate, NOT a device-command map and NOT an output transport.
 * The macOS binary confirms method names, not BLE request IDs or protobuf fields.
 * Only the existing Android DeviceInfo bootstrap TX is an observed request; it
 * has no validated hardware response. No SET call is reachable from this class.
 */
internal object SettingsB05GetSetProof {
    internal data class Pair(
        val key: String,
        val title: String,
        val group: String,
        val getter: String,
        val setter: String?,
        val knownAndroidGet: Int? = null,
        val getterTypedReadbackVerified: Boolean = false,
        val setterCommandAndPayloadVerified: Boolean = false,
        val postSetFreshGetVerified: Boolean = false
    ) {
        val canSet: Boolean get() = setter != null && getterTypedReadbackVerified &&
            setterCommandAndPayloadVerified && postSetFreshGetVerified
    }
    internal val pairs: List<Pair> = listOf(
        Pair("device_info", "Recorder firmware / Device ID", "device", "General.DeviceInfoGet", null, 0x04B0),
        Pair("date", "Date / time", "date", "General.DateGet", "General.DateSet"),
        Pair("date_format", "Date format", "date", "General.DateFormatGet", "General.DateFormatSet"),
        Pair("timecode_source", "Timecode source", "timecode", "Timecode.SourceGet + SourceListGet", "Timecode.SourceSet"),
        Pair("timecode_value", "Current timecode", "timecode", "Timecode.TimecodeGet", "Timecode.TimecodeSet"),
        Pair("timecode_offset", "Timecode offset", "timecode", "Timecode.OffsetGet", "Timecode.OffsetSet"),
        Pair("fan_cooling", "Fan cooling mode", "cooling", "General.FanCoolingModeGet", "General.FanCoolingModeSet"),
        Pair("fan_turbo", "Turbo cool", "cooling", "General.FanTurboCoolGet", "General.FanTurboCoolSet"),
        Pair("fan_dim", "Dim display", "cooling", "General.FanDimDisplayGet", "General.FanDimDisplaySet")
    )
    internal fun forGroup(group: String): List<Pair> = pairs.filter { it.group == group }
    internal fun find(key: String): Pair? = pairs.firstOrNull { it.key == key }
    // THIS B05 BUILD HAS NO WHITELISTED SET WIRE SCHEMAS. Fail closed regardless
    // of the draft editor, a successful ATT status, or a future UI readback.
    internal fun maySendSet(key: String): Boolean = find(key)?.canSet == true

    internal data class Display(val getStatus: String, val setStatus: String)
    internal fun display(key: String, matchedPeer: Boolean, querySeen: Boolean = false,
                         transportDelivered: Boolean = false, correlatedRawReply: Boolean = false): Display {
        val pair = find(key) ?: return Display("Unknown Settings capability", "BLOCKED: unmapped SET")
        val getStatus = when {
            !matchedPeer -> "Awaiting same-recorder GATT session"
            key == "device_info" && correlatedRawReply -> "Correlated raw response; firmware/ID schema NOT validated"
            key == "device_info" && transportDelivered -> "0x04B0 delivered; typed data NOT received"
            key == "device_info" && querySeen -> "0x04B0 queued; awaiting recorder response"
            key == "device_info" -> "0x04B0 Android command known; hardware RX NOT verified"
            else -> "macOS method found; AG2 GET opcode and typed RX NOT mapped"
        }
        val setStatus = when {
            pair.setter == null -> "READ ONLY: no matching setter"
            pair.canSet -> "Verified gate satisfied; SET implementation separately required"
            else -> "BLOCKED: SET opcode/payload and fresh post-SET GET not proven"
        }
        return Display(getStatus, setStatus)
    }
    internal fun pairedSetCount(): Int = pairs.count { it.setter != null }
    internal fun unlockedSetCount(): Int = pairs.count { it.canSet }
}
