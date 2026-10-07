package com.atomosremote.nativeapp.input

/**
 * 20.03.6.14 final read-only presentation rules.
 *
 * Important:
 * - This layer does not send BLE commands.
 * - It does not infer unsupported values.
 * - Repeated/opaque recorder payloads are presented as capability evidence,
 *   not falsely labelled as a current scalar setting.
 */
object InputFinalPresenter {
    data class FinalValue(
        val text: String,
        val verified: Boolean,
        val capabilityOnly: Boolean = false
    )

    fun current(value: String): FinalValue {
        return when {
            value == "Not reported by recorder" ->
                FinalValue("Not reported by recorder", verified = false)

            value == "Recorder value received" ->
                FinalValue("Recorder reported a value", verified = false)

            value == "Recorder metadata received" ->
                FinalValue("Recorder metadata available", verified = false)

            else ->
                FinalValue(value, verified = true)
        }
    }

    fun capability(value: String, rawHex: String?): FinalValue {
        if (value == "Not reported by recorder") {
            return FinalValue("Not reported by recorder", verified = false, capabilityOnly = true)
        }

        if (value != "Recorder value received") {
            return FinalValue(value, verified = true, capabilityOnly = true)
        }

        val count = packedField1Count(rawHex)
        return if (count != null && count > 0) {
            FinalValue(
                "$count recorder option${if (count == 1) "" else "s"} reported",
                verified = true,
                capabilityOnly = true
            )
        } else {
            FinalValue(
                "Recorder capability data received",
                verified = true,
                capabilityOnly = true
            )
        }
    }

    private fun packedField1Count(rawHex: String?): Int? {
        if (rawHex.isNullOrBlank() || rawHex.length < 4) return null
        val bytes = runCatching {
            rawHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }.getOrNull() ?: return null

        if ((bytes[0].toInt() and 0xFF) != 0x0A) return null
        val len = bytes[1].toInt() and 0xFF
        if (len <= 0 || bytes.size < 2 + len) return null

        var i = 2
        val end = 2 + len
        var count = 0
        while (i < end) {
            var shift = 0
            while (i < end && shift <= 28) {
                val b = bytes[i].toInt() and 0xFF
                i += 1
                if ((b and 0x80) == 0) {
                    count += 1
                    break
                }
                shift += 7
            }
        }
        return count.takeIf { it > 0 }
    }
}
