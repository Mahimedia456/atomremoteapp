package com.atomosremote.nativeapp.ui.settings

/**
 * SETTINGS S04 / OFFLINE CATALOG ONLY.
 * Data comes from user-supplied Contents(8).zip + protocol master + S03 audit.
 * A method name or inferred opcode is NOT a validated device transaction.
 * NO networking, BLE/GATT/PacketLink code and NO TX entry points live here.
 * In particular, existing Display, NDI, media, transport and pairing are frozen.
 */
internal object SettingsS04ProtocolCatalog {
    internal enum class Evidence {
        MACOS_ONLY, INFERRED_OPCODE, ANDROID_SOURCE, ANDROID_BOOTSTRAP, ANDROID_ATTEMPTED
    }

    internal data class Method(
        val sectionKey: String,
        val macGroup: String,
        val officialName: String,
        val role: String,
        val candidateOpcode: Int?,
        val evidence: Evidence
    ) {
        /** Evidence-aware, never implies a recorder-confirmed ID. */
        val opcodeLabel: String
            get() = candidateOpcode?.let { "0x" + it.toString(16).uppercase().padStart(4, '0') + " candidate" }
                ?: "ID unmapped"

        val evidenceLabel: String
            get() = when (evidence) {
                Evidence.MACOS_ONLY -> "macOS method only"
                Evidence.INFERRED_OPCODE -> "inferred / NOT device proven"
                Evidence.ANDROID_SOURCE -> "Android source reference"
                Evidence.ANDROID_BOOTSTRAP -> "Android bootstrap reference"
                Evidence.ANDROID_ATTEMPTED -> "Android TX attempted; no typed RX"
            }

        /** ALL S04 methods are disabled for execution, even known SET names. */
        val transmitAllowed: Boolean get() = false
        val recorderConfirmed: Boolean get() = false
    }

    internal data class Section(
        val key: String,
        val title: String,
        val description: String,
        val methods: List<Method>
    )

    val methods: List<Method> = listOf(
        Method("advanced", "General", "FactoryReset", "ACTION_RESET", null, Evidence.MACOS_ONLY),
        Method("advanced", "General", "PowerOff", "ACTION_OR_OTHER", null, Evidence.MACOS_ONLY),
        Method("advanced", "General", "Reboot", "ACTION_OR_OTHER", null, Evidence.MACOS_ONLY),
        Method("advanced", "General", "RemoteControlExit", "ACTION_OR_OTHER", null, Evidence.MACOS_ONLY),
        Method("date", "General", "DateFormatGet", "GET", null, Evidence.MACOS_ONLY),
        Method("date", "General", "DateFormatSet", "SET", null, Evidence.MACOS_ONLY),
        Method("date", "General", "DateGet", "GET", null, Evidence.MACOS_ONLY),
        Method("date", "General", "DateSet", "SET", null, Evidence.MACOS_ONLY),
        Method("cooling", "General", "FanCoolingModeGet", "GET", null, Evidence.MACOS_ONLY),
        Method("cooling", "General", "FanCoolingModeSet", "SET", null, Evidence.MACOS_ONLY),
        Method("cooling", "General", "FanDimDisplayGet", "GET", null, Evidence.MACOS_ONLY),
        Method("cooling", "General", "FanDimDisplaySet", "SET", null, Evidence.MACOS_ONLY),
        Method("cooling", "General", "FanTurboCoolGet", "GET", null, Evidence.MACOS_ONLY),
        Method("cooling", "General", "FanTurboCoolSet", "SET", null, Evidence.MACOS_ONLY),
        Method("activation", "General", "CodecActivation", "ACTION_OR_OTHER", null, Evidence.MACOS_ONLY),
        Method("device", "General", "DeviceInfoGet", "GET", 0x04B0, Evidence.ANDROID_BOOTSTRAP),
        Method("storage", "General", "FileRecoveryStateGet", "GET", null, Evidence.MACOS_ONLY),
        Method("storage", "General", "MediaChangeIdGet", "GET", null, Evidence.MACOS_ONLY),
        Method("storage", "General", "MediaFormat", "ACTION_OR_OTHER", null, Evidence.MACOS_ONLY),
        Method("storage", "General", "MediaFormatStateGet", "GET", null, Evidence.MACOS_ONLY),
        Method("storage", "General", "MediaInfoGet", "GET", null, Evidence.MACOS_ONLY),
        Method("storage", "General", "MediaStateGet", "GET", null, Evidence.MACOS_ONLY),
        Method("warnings", "General", "ErrorClear", "ACTION_OR_OTHER", null, Evidence.MACOS_ONLY),
        Method("warnings", "General", "ErrorStateGet", "GET", null, Evidence.MACOS_ONLY),
        Method("warnings", "General", "WarningGet", "GET", null, Evidence.MACOS_ONLY),
        Method("warnings", "General", "WarningPress", "ACTION_OR_OTHER", null, Evidence.MACOS_ONLY),
        Method("connect", "Connect", "ConnectModeGet", "GET", 0x02FD, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "ConnectModeSet", "SET", 0x02FC, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "DeviceRebootRequiredGet", "GET", 0x02FE, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "EthConnectedGet", "GET", 0x0302, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "EthModeGet", "GET", 0x030B, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "EthStatusGet", "GET", 0x0303, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "LiveModeEnabledGet", "GET", 0x0300, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "LiveModeSet", "SET", 0x0301, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "LiveModeValidGet", "GET", 0x02FF, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "Eui48Get", "GET", 0x030F, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "IpAddressGet", "GET", 0x030C, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "NetmaskAddressGet", "GET", 0x030D, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "RouterAddressGet", "GET", 0x030E, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "NetworkConfigGet", "GET", 0x0311, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "NetworkConfigSet", "SET", 0x0310, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "WifiConnectedGet", "GET", 0x0307, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "WifiDeviceSet", "SET", 0x0305, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "WifiRequestConn", "ACTION_OR_OTHER", 0x0306, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "WifiSignalLevelGet", "GET", 0x0308, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "WifiSignalStrengthGet", "GET", 0x0309, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "WifiSsidGet", "GET", 0x030A, Evidence.INFERRED_OPCODE),
        Method("connect", "Connect", "WifiStateGet", "GET", 0x0304, Evidence.INFERRED_OPCODE),
        Method("timecode", "Timecode", "OffsetGet", "GET", null, Evidence.MACOS_ONLY),
        Method("timecode", "Timecode", "OffsetSet", "SET", null, Evidence.MACOS_ONLY),
        Method("timecode", "Timecode", "SourceGet", "GET", null, Evidence.MACOS_ONLY),
        Method("timecode", "Timecode", "SourceListGet", "LIST_GET", null, Evidence.MACOS_ONLY),
        Method("timecode", "Timecode", "SourceSet", "SET", null, Evidence.MACOS_ONLY),
        Method("timecode", "Timecode", "TimecodeGet", "GET", null, Evidence.MACOS_ONLY),
        Method("timecode", "Timecode", "TimecodeSet", "SET", null, Evidence.MACOS_ONLY),
        Method("sync", "SyncConfig", "Get", "GET", null, Evidence.MACOS_ONLY),
        Method("sync", "SyncConfig", "NetworkEnableSet", "SET", null, Evidence.MACOS_ONLY),
        Method("sync", "SyncConfig", "NetworkListGet", "LIST_GET", null, Evidence.MACOS_ONLY),
        Method("sync", "SyncConfig", "NetworkRoleSet", "SET", null, Evidence.MACOS_ONLY),
        Method("sync", "SyncConfig", "RecordControlSet", "SET", null, Evidence.MACOS_ONLY),
        Method("sync", "SyncConfig", "RfChannelSet", "SET", null, Evidence.MACOS_ONLY),
        Method("sync", "SyncConfig", "RfRegionSet", "SET", null, Evidence.MACOS_ONLY),
        Method("existing", "Display", "BacklightBrightnessGet", "GET", 0x0321, Evidence.ANDROID_SOURCE),
        Method("existing", "Display", "BacklightBrightnessSet", "SET", 0x0320, Evidence.ANDROID_SOURCE),
        Method("existing", "Display", "FlipEnableGet", "GET", 0x032D, Evidence.ANDROID_SOURCE),
        Method("existing", "Display", "FlipEnableSet", "SET", 0x032C, Evidence.ANDROID_SOURCE),
        Method("existing", "Display", "SupportsGet", "GET", 0x032F, Evidence.ANDROID_SOURCE),
        Method("existing", "Display", "TallyLightEnableGet", "GET", 0x0329, Evidence.ANDROID_SOURCE),
        Method("existing", "Display", "TallyLightEnableSet", "SET", 0x0328, Evidence.ANDROID_SOURCE),
        Method("existing", "Display", "TouchscreenLockOptionEnableGet", "GET", null, Evidence.MACOS_ONLY),
        Method("existing", "Display", "TouchscreenLockOptionEnableSet", "SET", null, Evidence.MACOS_ONLY),
        Method("existing", "Display", "YGainGet", "GET", 0x0327, Evidence.ANDROID_SOURCE),
        Method("existing", "Display", "YGainSet", "SET", 0x0326, Evidence.ANDROID_SOURCE),
        Method("existing", "Display", "YGammaGet", "GET", 0x0325, Evidence.ANDROID_SOURCE),
        Method("existing", "Display", "YGammaSet", "SET", 0x0324, Evidence.ANDROID_SOURCE),
        Method("existing", "Display", "YLiftGet", "GET", 0x0323, Evidence.ANDROID_SOURCE),
        Method("existing", "Display", "YLiftSet", "SET", 0x0322, Evidence.ANDROID_SOURCE),
        Method("existing", "Display", "YSettingsReset", "ACTION_RESET", 0x032E, Evidence.ANDROID_SOURCE),
        Method("activation", "Activation", "ConfirmAndStoreFactoryToken", "ACTION_OR_OTHER", null, Evidence.MACOS_ONLY),
        Method("activation", "Activation", "DeviceAidGet", "GET", null, Evidence.MACOS_ONLY),
        Method("activation", "Activation", "FeatureActivatedGet", "GET", null, Evidence.MACOS_ONLY),
        Method("activation", "Activation", "FeatureActivatedListGet", "LIST_GET", null, Evidence.MACOS_ONLY),
        Method("activation", "Activation", "TransferFactoryTokenChunk", "ACTION_OR_OTHER", null, Evidence.MACOS_ONLY),
        Method("advanced", "Bluetooth", "Connect", "ACTION_OR_OTHER", null, Evidence.MACOS_ONLY),
        Method("advanced", "Bluetooth", "PeerDetailsSet", "SET", null, Evidence.MACOS_ONLY),
        Method("advanced", "AtomX", "ModuleInfoGet", "GET", null, Evidence.MACOS_ONLY),
        Method("advanced", "AtomX", "ModuleListGet", "LIST_GET", null, Evidence.MACOS_ONLY),
        Method("advanced", "AtomX", "MonitorInfoGet", "GET", null, Evidence.MACOS_ONLY),
        Method("advanced", "AtomX", "SyncBatStateGet", "GET", null, Evidence.MACOS_ONLY),
        Method("storage", "FileNaming", "FilenameExampleGet", "GET", null, Evidence.MACOS_ONLY),
        Method("storage", "FileNaming", "FilenameGet", "GET", null, Evidence.MACOS_ONLY),
        Method("storage", "FileNaming", "FilenameReset", "ACTION_RESET", null, Evidence.MACOS_ONLY),
        Method("storage", "FileNaming", "FilenameSet", "SET", null, Evidence.MACOS_ONLY),
        Method("storage", "FileNaming", "FilepathSourceGet", "GET", null, Evidence.MACOS_ONLY),
        Method("storage", "FileNaming", "FilepathSourceListGet", "LIST_GET", null, Evidence.MACOS_ONLY),
        Method("storage", "FileNaming", "FilepathSourceSet", "SET", null, Evidence.MACOS_ONLY),
        Method("existing", "Output", "HdmiTimecodeEnableGet", "GET", 0x00D6, Evidence.ANDROID_ATTEMPTED),
        Method("existing", "Output", "HdmiTimecodeEnableSet", "SET", null, Evidence.MACOS_ONLY),
    )

    val sections: List<Section> = listOf(
        Section("device", "Device information", "Recorder model, identity and firmware", methods.filter { it.sectionKey == "device" }),
        Section("date", "Date and time", "Mac API classes; GET opcode/payload unverified", methods.filter { it.sectionKey == "date" }),
        Section("timecode", "Timecode", "Current value, source list and offset", methods.filter { it.sectionKey == "timecode" }),
        Section("sync", "AirGlu / Sync", "Network role, RF, recording control", methods.filter { it.sectionKey == "sync" }),
        Section("connect", "Wi-Fi / Ethernet / Network", "Connection modes; numeric IDs are candidates", methods.filter { it.sectionKey == "connect" }),
        Section("cooling", "Fan and cooling", "Cooling modes and display dimming", methods.filter { it.sectionKey == "cooling" }),
        Section("warnings", "Warnings and errors", "Read-only states; clear operations locked", methods.filter { it.sectionKey == "warnings" }),
        Section("activation", "Activations and licensing", "Licenses status only; tokens disabled", methods.filter { it.sectionKey == "activation" }),
        Section("storage", "Media, recovery and naming", "Read-only media information; format locked", methods.filter { it.sectionKey == "storage" }),
        Section("advanced", "Advanced system / modules", "AtomX, pairing data, protected system operations", methods.filter { it.sectionKey == "advanced" }),
        Section("existing", "Existing Display and HDMI tools", "Inventory only; working tabs are frozen", methods.filter { it.sectionKey == "existing" }),
    )

    fun sectionForSheet(sheetKey: String): Section? =
        if (sheetKey.startsWith("s04_")) sections.firstOrNull { "s04_" + it.key == sheetKey } else null

    /** Deliberately never dispatch an opcode, even when a method has an ID. */
    fun canTransmitInS04(method: Method): Boolean = method.transmitAllowed
}
