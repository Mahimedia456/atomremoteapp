package com.atomosremote.nativeapp.ble

/**
 * WL-P11 static schema checkpoint recovered from the supplied official macOS app.
 *
 * Strong static alignment:
 *   ConnectLiveModeValidMsg   -> _streamValid
 *   ConnectLiveModeEnabledMsg -> _streamEnabled
 *   ConnectLiveModeMsg        -> _goLive
 *
 * IMPORTANT:
 * The exact protobuf field number/wire bytes are NOT yet proven.
 */
internal object WlLiveModeSchemaEvidence {
    const val PHASE = "WL-P11"

    const val LIVE_MODE_VALID_GET = 0x02FF
    const val LIVE_MODE_ENABLED_GET = 0x0300
    const val LIVE_MODE_SET = 0x0301

    const val VALID_MODEL = "ConnectLiveModeValidMsg"
    const val ENABLED_MODEL = "ConnectLiveModeEnabledMsg"
    const val SETTER_MODEL = "ConnectLiveModeMsg"

    const val VALID_FIELD = "_streamValid"
    const val ENABLED_FIELD = "_streamEnabled"
    const val SETTER_FIELD = "_goLive"

    // Static field-name/type association is strong enough to retain.
    const val SETTER_FIELD_NAME_PROVEN = true

    // Still NOT proven from this optimized binary.
    const val SETTER_FIELD_NUMBER_PROVEN = false
    const val SETTER_WIRE_BYTES_PROVEN = false
    const val SETTER_RESPONSE_TYPE_PROVEN = false
    const val SETTER_PREREQUISITE_SEQUENCE_PROVEN = false

    fun transmissionMayBeAuthorized(): Boolean {
        return SETTER_FIELD_NAME_PROVEN &&
            SETTER_FIELD_NUMBER_PROVEN &&
            SETTER_WIRE_BYTES_PROVEN &&
            SETTER_RESPONSE_TYPE_PROVEN &&
            SETTER_PREREQUISITE_SEQUENCE_PROVEN
    }
}
