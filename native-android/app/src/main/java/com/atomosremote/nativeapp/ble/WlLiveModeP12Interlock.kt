package com.atomosremote.nativeapp.ble

/**
 * WL-P12 controlled activation interlock.
 *
 * The official setter field name `_goLive` is now statically evidenced, but
 * exact protobuf serialization and expected response type remain unproven.
 *
 * Therefore no setter payload can be emitted in this phase.
 */
internal object WlLiveModeP12Interlock {

    data class Decision(
        val authorized: Boolean,
        val commandId: Int,
        val payload: ByteArray?,
        val reason: String
    )

    fun enableDecision(): Decision = decision("enable")
    fun disableDecision(): Decision = decision("disable")

    private fun decision(action: String): Decision {
        if (!WlLiveModeSchemaEvidence.transmissionMayBeAuthorized()) {
            return Decision(
                authorized = false,
                commandId = WlLiveModeSchemaEvidence.LIVE_MODE_SET,
                payload = null,
                reason = "WL-P12 $action blocked: _goLive field evidenced, but exact wire bytes/response/prerequisite sequence are not proven"
            )
        }

        // Deliberately unreachable in WL-P12.
        return Decision(
            authorized = false,
            commandId = WlLiveModeSchemaEvidence.LIVE_MODE_SET,
            payload = null,
            reason = "WL-P12 $action blocked: no exact official serializer implementation installed"
        )
    }
}
