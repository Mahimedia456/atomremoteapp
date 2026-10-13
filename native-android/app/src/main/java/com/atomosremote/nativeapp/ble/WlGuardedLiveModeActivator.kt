package com.atomosremote.nativeapp.ble

/**
 * WL-P10 guarded LiveModeSet activator prototype.
 *
 * This class contains NO GATT write and NO PacketLink call.
 * It refuses to build a setter payload unless WL-P9 official proof flags are true.
 */
internal object WlGuardedLiveModeActivator {

    data class BuildResult(
        val allowed: Boolean,
        val commandId: Int,
        val payload: ByteArray?,
        val reason: String
    )

    fun buildEnableRequest(): BuildResult {
        if (!WlOfficialLiveModeEvidence.setterMayBeBuilt()) {
            return BuildResult(
                allowed = false,
                commandId = WlOfficialLiveModeEvidence.LIVE_MODE_SET,
                payload = null,
                reason = "WL-P10 blocked: exact official LiveModeSet payload/response/sequence not proven"
            )
        }

        // Intentionally unreachable in WL-P10.
        // Do NOT fill guessed protobuf bytes here.
        return BuildResult(
            allowed = false,
            commandId = WlOfficialLiveModeEvidence.LIVE_MODE_SET,
            payload = null,
            reason = "WL-P10 blocked: no proven payload implementation exists"
        )
    }

    fun buildDisableRequest(): BuildResult {
        if (!WlOfficialLiveModeEvidence.setterMayBeBuilt()) {
            return BuildResult(
                allowed = false,
                commandId = WlOfficialLiveModeEvidence.LIVE_MODE_SET,
                payload = null,
                reason = "WL-P10 blocked: exact official LiveModeSet payload/response/sequence not proven"
            )
        }

        return BuildResult(
            allowed = false,
            commandId = WlOfficialLiveModeEvidence.LIVE_MODE_SET,
            payload = null,
            reason = "WL-P10 blocked: no proven payload implementation exists"
        )
    }
}
