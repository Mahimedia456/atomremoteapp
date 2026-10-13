package com.atomosremote.nativeapp.ble

/**
 * WL-P9 official LiveMode activation evidence checkpoint.
 *
 * Recovered from the supplied official macOS ATOMOS Remote application:
 *
 *   LiveModeValidGet   = 0x02FF
 *   LiveModeEnabledGet = 0x0300
 *   LiveModeSet        = 0x0301
 *
 * Typed official models recovered:
 *   ConnectLiveModeValidMsg
 *   ConnectLiveModeEnabledMsg
 *   ConnectLiveModeMsg
 *
 * IMPORTANT:
 * The exact serialized payload bytes for LiveModeSet have NOT been proven.
 * A protobuf-looking payload such as 08 01 must NOT be treated as proven.
 */
internal object WlOfficialLiveModeEvidence {
    const val PHASE = "WL-P9"

    const val LIVE_MODE_VALID_GET = 0x02FF
    const val LIVE_MODE_ENABLED_GET = 0x0300
    const val LIVE_MODE_SET = 0x0301

    const val RESPONSE_CLASS_VALID = "ConnectLiveModeValidMsg"
    const val RESPONSE_CLASS_ENABLED = "ConnectLiveModeEnabledMsg"
    const val SETTER_MESSAGE_CLASS = "ConnectLiveModeMsg"

    const val EXACT_SETTER_PAYLOAD_PROVEN = false
    const val EXACT_SETTER_RESPONSE_TYPE_PROVEN = false
    const val OFFICIAL_ACTIVATION_SEQUENCE_PROVEN = false

    fun setterMayBeBuilt(): Boolean {
        return EXACT_SETTER_PAYLOAD_PROVEN &&
            EXACT_SETTER_RESPONSE_TYPE_PROVEN &&
            OFFICIAL_ACTIVATION_SEQUENCE_PROVEN
    }
}
