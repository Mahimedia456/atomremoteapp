package com.atomosremote.nativeapp.atomos

/**
 * Public AirGlu2 / Timecode Systems S1C ASCII control protocol helpers.
 *
 * Commands are documented in the AirGlu2 integration manual. The BLE GATT
 * transport characteristic is intentionally discovered at runtime rather than
 * hard-coded, because the public manual documents the protocol but not the
 * vendor GATT UUIDs.
 */
data class S1cChannel(
    val serviceUuid: String,
    val characteristicUuid: String,
    val writeWithResponse: Boolean
)

data class S1cStatus(
    val state: String = "idle",
    val channel: S1cChannel? = null,
    val deviceModel: String? = null,
    val firmware: String? = null,
    val batteryPercent: Int? = null,
    val transportState: Int? = null,
    val media: String? = null,
    val timecode: String? = null,
    val pairingInfo: String? = null,
    val pairedDevices: String? = null,
    val lastReply: String? = null,
    val error: String? = null
) {
    val pairingApiReady: Boolean get() = channel != null && pairingInfo != null
    val ready: Boolean get() = channel != null && state == "ready" && (deviceModel != null || transportState != null)

    val transportLabel: String
        get() = when (transportState) {
            -1 -> "OFFLINE"
            0 -> "UNKNOWN"
            1 -> "STOPPED"
            2 -> "RECORDING"
            3 -> "PLAYBACK"
            4 -> "PAUSE"
            5 -> "REWIND"
            6 -> "FAST FORWARD"
            else -> "--"
        }
}

object S1cCommands {
    const val PROBE = "#GCMD?\n"
    const val FIRMWARE = "#GCVR?\n"
    const val BATTERY = "#GCBT?\n"
    const val MEDIA = "#GCDV?\n"
    const val TRANSPORT = "#GCCT?\n"
    const val DEVICE_STATIC = "#GCXS?\n"
    const val DEVICE_POLL = "#GCXP?\n"
    const val TIMECODE = "#TCTM?\n"
    const val PAIRING = "#BTPR?\n"
    const val PAIR_START = "#BTPR=1\n"
    const val PAIR_STATUS = "#BTST?\n"
    const val PAIR_CONNECT = "#BTPR=2\n"

    fun transport(value: Int): String = "#GCCT=$value\n"
}

fun extractS1cAscii(event: String): String? {
    val start = event.indexOf('[')
    val end = event.lastIndexOf(']')
    if (start >= 0 && end > start) {
        return event.substring(start + 1, end).trim().takeIf { it.isNotBlank() }
    }
    val marker = event.indexOf("S1C<")
    if (marker >= 0) return event.substring(marker + 4).trim().takeIf { it.isNotBlank() }
    return null
}

fun isS1cReply(text: String): Boolean {
    val value = text.removePrefix("#").trim()
    return listOf(
        "GCMD=", "GCVR=", "GCBT=", "GCDV=", "GCCT=", "GCXS=", "GCXP=",
        "BTPR=", "BTST=", "STVS=", "STSS=", "STSP=", "TCTM="
    ).any { value.startsWith(it) }
}

fun reduceS1cStatus(current: S1cStatus, replyRaw: String): S1cStatus {
    val reply = replyRaw.removePrefix("#").trim()
    var next = current.copy(lastReply = reply, error = null)
    when {
        reply.startsWith("GCMD=") -> {
            val value = reply.substringAfter('=').trim()
            next = next.copy(deviceModel = value.ifBlank { null })
        }
        reply.startsWith("GCVR=") -> next = next.copy(firmware = reply.substringAfter('=').trim().ifBlank { null })
        reply.startsWith("GCBT=") -> next = next.copy(batteryPercent = reply.substringAfter('=').substringBefore(',').trim().toIntOrNull())
        reply.startsWith("GCCT=") -> next = next.copy(transportState = reply.substringAfter('=').substringBefore(',').trim().toIntOrNull())
        reply.startsWith("GCDV=") -> next = next.copy(media = reply.substringAfter('=').trim().ifBlank { null })
        reply.startsWith("TCTM=") -> {
            val raw = reply.substringAfter('=').trim()
            val formatted = if (raw.length >= 8 && raw.take(8).all { it.isDigit() }) {
                "${raw.substring(0,2)}:${raw.substring(2,4)}:${raw.substring(4,6)}:${raw.substring(6,8)}"
            } else raw.ifBlank { null }
            next = next.copy(timecode = formatted)
        }
        reply.startsWith("GCXP=") -> {
            val fields = reply.substringAfter('=').split(',')
            val transport = fields.getOrNull(0)?.trim()?.toIntOrNull()
            val battery = fields.getOrNull(4)?.trim()?.toIntOrNull()
            val mediaText = fields.drop(8).joinToString(",").trim().ifBlank { null }
            next = next.copy(
                transportState = transport ?: next.transportState,
                batteryPercent = battery ?: next.batteryPercent,
                media = mediaText ?: next.media
            )
        }
        reply.startsWith("BTPR=") -> next = next.copy(pairingInfo = reply.substringAfter('=').trim())
        reply.startsWith("BTST=") -> next = next.copy(pairedDevices = reply.substringAfter('=').trim())
    }
    return next
}
