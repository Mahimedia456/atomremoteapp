package com.atomosremote.nativeapp.media

/**
 * U05.01 Mac x86_64 command-object audit: 0x01F8 expects 0x01F7,
 * 0x01F4 expects 0x01F6. Application replies carry response bit 0x8000.
 * This class is pure, contains no transmitter and does not create a success signal.
 * Both requests are GET candidates whose exact AG2 acceptance remains unverified.
 */
object U0501MacRxContract {
    const val DIRECTORY_GET = 0x01F8
    const val DIRECTORY_RESPONSE = 0x81F7
    const val INDEX_GET = 0x01F4
    const val INDEX_RESPONSE = 0x81F6
    const val MODE_GET = 0x01FF
    const val MODE_RESPONSE = 0x81FF
    const val FILELIST_GET = 0x01FB
    const val FILELIST_RESPONSE = 0x81FB

    fun expectedResponse(request: Int): Int? = when (request) {
        DIRECTORY_GET -> DIRECTORY_RESPONSE
        INDEX_GET -> INDEX_RESPONSE
        MODE_GET -> MODE_RESPONSE
        else -> null
    }

    /** One outstanding request, exact opcode/route and fresh 15s window (gated by GATT).
     * E8 reply packet IDs are recorder-assigned, not necessarily echoes of TX=0.
     * This avoids treating an unrelated response opcode as success.
     */
    fun matches(request: Int, response: Int, packetId: Int, local: Boolean, expectedLocal: Boolean): Boolean =
        expectedResponse(request) == response && local == expectedLocal && (local || packetId == 0)
}
