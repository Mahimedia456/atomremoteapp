package com.atomosremote.nativeapp.audio

/** R27.09: official macOS Audio poll command descriptor +0x10 (request)
 * and +0x2C (expected response) ABI recovered directly from the x86_64 binary.
 * Pure function; no BLE, UI, hardware setters, or numeric-value inference.
 */
object AudioR2709MacDispatch {
    fun expectedReplyBase(request: Int): Int? = when (request) {
        0x06B3 -> 0x06AE // input source poll
        0x06B6 -> 0x06B1 // gain values poll (shared L/R)
        0x06B9 -> 0x06B3 // frame delay poll
        else -> null
    }
    fun normalize(wireOpcode: Int): Int = wireOpcode and 0x0FFF

    fun matches(request: Int, wireOpcode: Int, receivedPacketId: Int,
                requestedPacketId: Int, attWriteConfirmed: Boolean): Boolean {
        val expected = expectedReplyBase(request) ?: return false
        return attWriteConfirmed && requestedPacketId in 1..255 &&
            requestedPacketId == receivedPacketId && normalize(wireOpcode) == expected
    }
}
