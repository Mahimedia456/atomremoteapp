package com.atomosremote.nativeapp.audio

/**
 * A12.24 experimental pilot ONLY. ARM64 and x86_64 FrameDelaySet constructors
 * embed command 0x06BA. ARM64 SwiftProtobuf AudioFrameDelayMsg traverse(visitor:)
 * at 0x1000ec964 dispatches field number 1 for its stored Int32 frameDelay.
 * The AG2 firmware's acceptance of this request remains unverified until a
 * deliberate one-shot physical test followed by existing typed GET 0x86B3.
 */
object AudioA124DelayWire {
    const val COMMAND = 0x06BA
    fun framesPayload(frames: Int): ByteArray {
        require(frames in 0..20) { "Delay range is 0..20 frames" }
        return byteArrayOf(0x08, frames.toByte())
    }
    fun oneFramePayload(): ByteArray = framesPayload(1)
}
