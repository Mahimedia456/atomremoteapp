package com.atomosremote.nativeapp.ble

/**
 * WL-P1 / WL-P2 isolated Connect prototype definitions.
 *
 * This file intentionally contains no Bluetooth/GATT operations and no automatic runtime action.
 * WL-P1 only adds this compile-time foundation.
 *
 * WL-P2 may call the pure classification helper from GattClient logging while keeping the
 * existing pairing, PacketLink transport and RemoteApi transport unchanged.
 */
internal object WlConnectPrototype {
    const val PHASE = "WL-P2"
    const val CONNECT_COMMAND_MIN = 0x02FC
    const val CONNECT_COMMAND_MAX = 0x0311

    const val WIFI_SSID_GET = 0x030A
    const val IP_ADDRESS_GET = 0x030C
    const val EUI48_GET = 0x030F
    const val NETWORK_CONFIG_GET = 0x0311

    fun isPotentialConnectResponse(messageType: Int): Boolean {
        val normalized = messageType and 0x7FFF
        return normalized in CONNECT_COMMAND_MIN..CONNECT_COMMAND_MAX ||
            normalized == 0x7FFE ||
            normalized == 0x7FFF
    }
}
