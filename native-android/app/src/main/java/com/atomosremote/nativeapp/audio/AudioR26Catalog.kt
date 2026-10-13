package com.atomosremote.nativeapp.audio

/**
 * AUDIO R26: macOS class-name evidence, NOT a recorder opcode table.
 * No numeric Audio GET/SET ID is proven by the workbook. This catalog drives
 * the Android Audio/Mic-Line screen without issuing speculative setter bytes.
 */
data class AudioR26Setting(
    val key: String,
    val label: String,
    val pane: String,
    val macMethods: List<String>,
    val deviceOptions: List<String>,
    val evidenceNote: String
)

object AudioR26Catalog {
    val settings: List<AudioR26Setting> = listOf(
        AudioR26Setting("phones", "Headphones volume", "AUDIO", listOf("PhonesLevelGet", "PhonesLevelMinMaxGet", "PhonesLevelSet"), listOf(), "Volume range must come from recorder, not screenshot thumb position"),
        AudioR26Setting("analogMonitor", "Analog channel monitor", "AUDIO", listOf("MonitorGet", "MonitorListGet", "MonitorSet"), listOf("L", "R"), "Left/right monitoring selection; channel-index payload not mapped"),
        AudioR26Setting("analogRecord", "Analog channel REC L/R", "AUDIO", listOf("RecordEnabledListGet", "RecordValidListGet", "RecordSet"), listOf("L REC", "R REC"), "Two analog recording targets visible in official UI"),
        AudioR26Setting("ch12", "Channels 1-2", "AUDIO", listOf("MonitorGet", "MonitorListGet", "MonitorSet", "RecordEnabledListGet", "RecordValidListGet", "RecordSet"), listOf("Monitor", "REC"), "Recorder-specific valid/enabled list required"),
        AudioR26Setting("ch34", "Channels 3-4", "AUDIO", listOf("MonitorGet", "MonitorListGet", "MonitorSet", "RecordEnabledListGet", "RecordValidListGet", "RecordSet"), listOf("Monitor", "REC"), "Recorder-specific valid/enabled list required"),
        AudioR26Setting("ch56", "Channels 5-6", "AUDIO", listOf("MonitorGet", "MonitorListGet", "MonitorSet", "RecordEnabledListGet", "RecordValidListGet", "RecordSet"), listOf("Monitor", "REC"), "Recorder-specific valid/enabled list required"),
        AudioR26Setting("ch78", "Channels 7-8", "AUDIO", listOf("MonitorGet", "MonitorListGet", "MonitorSet", "RecordEnabledListGet", "RecordValidListGet", "RecordSet"), listOf("Monitor", "REC"), "Recorder-specific valid/enabled list required"),
        AudioR26Setting("ch910", "Channels 9-10", "AUDIO", listOf("MonitorGet", "MonitorListGet", "MonitorSet", "RecordEnabledListGet", "RecordValidListGet", "RecordSet"), listOf("Monitor", "REC"), "Recorder-specific valid/enabled list required"),
        AudioR26Setting("ch1112", "Channels 11-12", "AUDIO", listOf("MonitorGet", "MonitorListGet", "MonitorSet", "RecordEnabledListGet", "RecordValidListGet", "RecordSet"), listOf("Monitor", "REC"), "Recorder-specific valid/enabled list required"),
        AudioR26Setting("embed", "Embed channels", "AUDIO", listOf("AnalogEmbedGet", "AnalogEmbedListGet", "AnalogEmbedSet"), listOf("Off", "1 & 2"), "Additional recorder list options unknown"),
        AudioR26Setting("orientation", "Meters orientation", "AUDIO", listOf("OrientationChangeableGet", "OrientationGet", "OrientationSet"), listOf("Vertical", "Horizontal"), "Orientation controls visible on device"),
        AudioR26Setting("fill", "Audio fill", "AUDIO", listOf("AudioFillGet", "AudioFillSet"), listOf(), "No verified AG2 options or values yet"),
        AudioR26Setting("speaker", "Speaker level", "AUDIO", listOf("IsSpeakerLevelSupported", "SpeakerLevelGet", "SpeakerLevelMinMaxGet", "SpeakerLevelSet"), listOf(), "Show if this recorder reports capability"),
        AudioR26Setting("speakerMute", "Speaker mute", "AUDIO", listOf("IsSpeakerMuteSupported", "SpeakerMuteGet", "SpeakerMuteSet"), listOf("Off", "On"), "Show if this recorder reports capability"),
        AudioR26Setting("dropouts", "Ignore audio dropouts", "AUDIO", listOf(), listOf("Off", "On"), "macOS UI reference only; exact RemoteApi Audio method unproven"),
        AudioR26Setting("gainLeft", "Left gain (dB)", "MIC_LINE", listOf("GainGet", "GainMinMaxGet", "GainSet"), listOf(), "Channel identity and recorder min/max need proof"),
        AudioR26Setting("gainRight", "Right gain (dB)", "MIC_LINE", listOf("GainGet", "GainMinMaxGet", "GainSet"), listOf(), "Channel identity and recorder min/max need proof"),
        AudioR26Setting("delay", "Audio delay (frames)", "MIC_LINE", listOf("FrameDelayGet", "FrameDelayMinMaxGet", "FrameDelaySet"), listOf(), "Range is recorder-reported; 0 in reference is not a default"),
        AudioR26Setting("inputLevel", "Audio-in level", "MIC_LINE", listOf("InputSourceGet", "InputSourceListGet", "InputSourceSet"), listOf("Line", "Mic"), "Association is a mapping candidate until verified on hardware"),
        AudioR26Setting("lineLevel", "Line level", "MIC_LINE", listOf(), listOf(), "Device reference shows -10 dBu, but exact class/value mapping unproven"),
    )
    fun forPane(pane: String): List<AudioR26Setting> = settings.filter { it.pane == pane }
    fun find(key: String): AudioR26Setting? = settings.firstOrNull { it.key == key }
    // Distinct official macOS Audio RemoteApi classes; not wire IDs.
    val coveredMacMethods: Set<String> = settings.flatMap { it.macMethods }.toSet()
}
