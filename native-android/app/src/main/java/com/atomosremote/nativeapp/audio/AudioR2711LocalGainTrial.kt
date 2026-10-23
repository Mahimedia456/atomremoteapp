package com.atomosremote.nativeapp.audio

/** Operator-initiated R27.11 local Gain Left route test ONLY.
 * Does not expose SETs, infer numeric values, or dispatch commands itself.
 * Actual recorder RX must verify the hypothesis.
 */
object AudioR2711LocalGainTrial {
    fun eligible(key: String, requestOpcode: Int): Boolean =
        key == "gainLeft" && requestOpcode == 0x06B6

    fun matches(key: String, requestOpcode: Int, responseOpcode: Int,
                responseLocal: Boolean, attConfirmed: Boolean,
                safeOwner: Boolean, sinceAttMs: Long): Boolean =
        eligible(key, requestOpcode) && responseLocal && attConfirmed && safeOwner &&
            sinceAttMs in 0L..30_000L && (responseOpcode and 0x0FFF) == 0x06B1
}
