package com.atomosremote.nativeapp.ui.settings

/** B03: strict recorder-command verification gates. Offline catalog evidence is not a TX permit. */
internal object SettingsB03Gate {
    internal enum class Stage { MACOS_METHOD, ANDROID_OPCODE, ATT_TX, RECORDER_TYPED_RX, FIELD_MAPPED, SET_VERIFIED }
    internal data class Entry(
        val name: String, val method: String, val stage: Stage,
        val readOnly: Boolean = false, val hasSetterName: Boolean = false
    ) {
        val getConfirmed: Boolean get() = stage == Stage.FIELD_MAPPED || stage == Stage.SET_VERIFIED
        // A method symbol is NOT evidence of SET opcode/payload or recorder readback.
        val setEnabled: Boolean get() = hasSetterName && !readOnly && stage == Stage.SET_VERIFIED
        val uiLabel: String get() = when {
            getConfirmed -> "Recorder GET confirmed"
            stage == Stage.RECORDER_TYPED_RX -> "Reply received; fields unmapped"
            stage == Stage.ATT_TX -> "GET transmitted; no verified reply"
            else -> "Awaiting validated recorder GET"
        }
    }
    val entries = listOf(
        Entry("Device information", "General.DeviceInfoGet", Stage.ATT_TX, readOnly = true),
        Entry("Date/time", "General.DateGet / DateSet", Stage.MACOS_METHOD, hasSetterName = true),
        Entry("Date format", "General.DateFormatGet / DateFormatSet", Stage.MACOS_METHOD, hasSetterName = true),
        Entry("Timecode", "Timecode.TimecodeGet / TimecodeSet", Stage.MACOS_METHOD, hasSetterName = true),
        Entry("Timecode source", "Timecode.SourceListGet / SourceGet / SourceSet", Stage.MACOS_METHOD, hasSetterName = true),
        Entry("Timecode offset", "Timecode.OffsetGet / OffsetSet", Stage.MACOS_METHOD, hasSetterName = true),
        Entry("Cooling mode", "General.FanCoolingModeGet / FanCoolingModeSet", Stage.MACOS_METHOD, hasSetterName = true),
        Entry("Turbo cool", "General.FanTurboCoolGet / FanTurboCoolSet", Stage.MACOS_METHOD, hasSetterName = true),
        Entry("Dim display", "General.FanDimDisplayGet / FanDimDisplaySet", Stage.MACOS_METHOD, hasSetterName = true),
        Entry("Warnings", "General.WarningGet", Stage.MACOS_METHOD, readOnly = true),
        Entry("Activated features", "Activation.FeatureActivatedListGet", Stage.MACOS_METHOD, readOnly = true)
    )
    fun forbidUnverifiedSet(name: String): Boolean = entries.firstOrNull { it.name == name }?.setEnabled != true
}
