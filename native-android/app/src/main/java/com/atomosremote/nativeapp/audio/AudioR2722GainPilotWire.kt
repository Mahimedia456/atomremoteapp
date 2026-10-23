package com.atomosremote.nativeapp.audio

/** R27.22: candidate macOS 0x06B7 GainSet protobuf.
 * Production feature remains DISABLED. Only explicit 0dB/-1dB pilot allowed.
 * field1: signed Int32 gain, field2: Bool channel; absent default scalars follow SwiftProtobuf.
 */
object AudioR2722GainPilotWire {
    fun gainMessage(rawGain: Int, selector: Boolean, explicitZero: Boolean = false): ByteArray {
        require(rawGain == 0 || rawGain == -2) { "Only known reversible 0dB/-1dB test values" }
        val result = ArrayList<Byte>()
        if (rawGain != 0 || explicitZero) {
            result.add(0x08)
            var n = rawGain.toLong() // negative int32 sign extends into proto int64 varint
            do {
                val part = (n and 0x7f).toInt()
                n = n ushr 7
                result.add((part or if (n != 0L) 0x80 else 0).toByte())
            } while (n != 0L)
        }
        if (selector) { result.add(0x10); result.add(0x01) }
        return result.toByteArray()
    }
}
