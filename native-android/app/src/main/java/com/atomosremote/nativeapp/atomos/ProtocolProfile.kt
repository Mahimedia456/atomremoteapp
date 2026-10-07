package com.atomosremote.nativeapp.atomos

import android.util.Base64
import org.json.JSONObject

data class ProtocolCommands(
    val recordStart: ByteArray? = null,
    val recordStop: ByteArray? = null,
    val play: ByteArray? = null,
    val pause: ByteArray? = null,
    val waveformOn: ByteArray? = null,
    val waveformOff: ByteArray? = null,
    val zebraOn: ByteArray? = null,
    val zebraOff: ByteArray? = null,
    val peakingOn: ByteArray? = null,
    val peakingOff: ByteArray? = null,
    val falseColorOn: ByteArray? = null,
    val falseColorOff: ByteArray? = null,
    val zoomOn: ByteArray? = null,
    val zoomOff: ByteArray? = null,
    val guidesOn: ByteArray? = null,
    val guidesOff: ByteArray? = null,
    val extra: Map<String, ByteArray> = emptyMap()
) {
    fun payloadFor(command: String): ByteArray? = when (command) {
        "record-start" -> recordStart
        "record-stop" -> recordStop
        "play" -> play
        "pause" -> pause
        "waveform-on" -> waveformOn
        "waveform-off" -> waveformOff
        "zebra-on" -> zebraOn
        "zebra-off" -> zebraOff
        "peaking-on" -> peakingOn
        "peaking-off" -> peakingOff
        "false-color-on" -> falseColorOn
        "false-color-off" -> falseColorOff
        "zoom-on" -> zoomOn
        "zoom-off" -> zoomOff
        "guides-on" -> guidesOn
        "guides-off" -> guidesOff
        else -> extra[command]
    }

    fun availableKeys(): Set<String> {
        val builtIns = linkedMapOf(
            "record-start" to recordStart,
            "record-stop" to recordStop,
            "play" to play,
            "pause" to pause,
            "waveform-on" to waveformOn,
            "waveform-off" to waveformOff,
            "zebra-on" to zebraOn,
            "zebra-off" to zebraOff,
            "peaking-on" to peakingOn,
            "peaking-off" to peakingOff,
            "false-color-on" to falseColorOn,
            "false-color-off" to falseColorOff,
            "zoom-on" to zoomOn,
            "zoom-off" to zoomOff,
            "guides-on" to guidesOn,
            "guides-off" to guidesOff
        ).filterValues { it != null }.keys
        return builtIns + extra.keys
    }
}

data class ProtocolProfile(
    val name: String,
    val validated: Boolean,
    val serviceUuid: String,
    val commandCharacteristicUuid: String,
    val statusCharacteristicUuid: String?,
    val notifyCharacteristicUuid: String?,
    val writeWithResponse: Boolean,
    val commands: ProtocolCommands
) {
    val canTransmit: Boolean
        get() = validated && serviceUuid.isNotBlank() && commandCharacteristicUuid.isNotBlank()

    companion object {
        fun fromJson(json: String): ProtocolProfile {
            val root = JSONObject(json)
            val commands = root.optJSONObject("commands") ?: JSONObject()
            val extra = root.optJSONObject("extraCommands") ?: JSONObject()

            fun decodeFrom(obj: JSONObject, key: String): ByteArray? {
                val value = obj.optString(key, "").trim()
                return if (value.isBlank()) null else runCatching { Base64.decode(value, Base64.DEFAULT) }.getOrNull()
            }

            val extraCommands = buildMap<String, ByteArray> {
                val keys = extra.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    decodeFrom(extra, key)?.let { put(key, it) }
                }
            }

            return ProtocolProfile(
                name = root.optString("name", "Validated Atomos Profile"),
                validated = root.optBoolean("validated", false),
                serviceUuid = root.optString("serviceUuid", ""),
                commandCharacteristicUuid = root.optString("commandCharacteristicUuid", ""),
                statusCharacteristicUuid = root.optString("statusCharacteristicUuid", "").ifBlank { null },
                notifyCharacteristicUuid = root.optString("notifyCharacteristicUuid", "").ifBlank { null },
                writeWithResponse = root.optBoolean("writeWithResponse", true),
                commands = ProtocolCommands(
                    recordStart = decodeFrom(commands, "recordStartBase64"),
                    recordStop = decodeFrom(commands, "recordStopBase64"),
                    play = decodeFrom(commands, "playBase64"),
                    pause = decodeFrom(commands, "pauseBase64"),
                    waveformOn = decodeFrom(commands, "waveformOnBase64"),
                    waveformOff = decodeFrom(commands, "waveformOffBase64"),
                    zebraOn = decodeFrom(commands, "zebraOnBase64"),
                    zebraOff = decodeFrom(commands, "zebraOffBase64"),
                    peakingOn = decodeFrom(commands, "peakingOnBase64"),
                    peakingOff = decodeFrom(commands, "peakingOffBase64"),
                    falseColorOn = decodeFrom(commands, "falseColorOnBase64"),
                    falseColorOff = decodeFrom(commands, "falseColorOffBase64"),
                    zoomOn = decodeFrom(commands, "zoomOnBase64"),
                    zoomOff = decodeFrom(commands, "zoomOffBase64"),
                    guidesOn = decodeFrom(commands, "guidesOnBase64"),
                    guidesOff = decodeFrom(commands, "guidesOffBase64"),
                    extra = extraCommands
                )
            )
        }
    }
}
