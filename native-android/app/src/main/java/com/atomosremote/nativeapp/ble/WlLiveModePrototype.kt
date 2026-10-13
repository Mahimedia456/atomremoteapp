package com.atomosremote.nativeapp.ble

/**
 * WL-P4 guarded LiveMode activation groundwork.
 *
 * IMPORTANT:
 * - This object performs no GATT / PacketLink write.
 * - SET execution remains hard-disabled by default.
 * - No caller is added in WL-P4.
 * - It only centralizes the recovered command IDs and future activation guard state.
 */
internal object WlLiveModePrototype {
    const val PHASE = "WL-P4"

    const val LIVE_MODE_VALID_GET = 0x02FF
    const val LIVE_MODE_ENABLED_GET = 0x0300
    const val LIVE_MODE_SET = 0x0301

    /**
     * Hard safety latch.
     *
     * Do not change to true until:
     * 1) WL-P3 runtime readback has been analyzed,
     * 2) the exact official LiveModeSet payload is recovered/proven,
     * 3) the user explicitly authorizes the controlled setter test.
     */
    const val SETTER_EXECUTION_ENABLED = false

    fun canExecuteSetter(
        exactPayloadProven: Boolean,
        liveModeValidKnown: Boolean,
        explicitDiagnosticEnable: Boolean
    ): Boolean {
        return SETTER_EXECUTION_ENABLED &&
            exactPayloadProven &&
            liveModeValidKnown &&
            explicitDiagnosticEnable
    }
}
