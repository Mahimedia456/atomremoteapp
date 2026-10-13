package com.atomosremote.nativeapp.ble

/**
 * WL-P5 guarded LiveMode activation evidence state.
 *
 * No Bluetooth/GATT/PacketLink write exists here.
 * The setter remains forbidden until exact official payload bytes are proven.
 */
internal object WlLiveModeActivationGuard {
    const val PHASE = "WL-P5"

    const val LIVE_MODE_VALID_GET = 0x02FF
    const val LIVE_MODE_ENABLED_GET = 0x0300
    const val LIVE_MODE_SET = 0x0301

    /**
     * Hard lock. WL-P5 does not authorize a setter.
     */
    const val EXACT_SETTER_PAYLOAD_PROVEN = false
    const val SETTER_TX_AUTHORIZED = false

    fun mayTransmitLiveModeSet(): Boolean {
        return EXACT_SETTER_PAYLOAD_PROVEN && SETTER_TX_AUTHORIZED
    }

    fun describe(): String =
        "phase=$PHASE exactPayload=$EXACT_SETTER_PAYLOAD_PROVEN setterAuthorized=$SETTER_TX_AUTHORIZED"
}
