export type RemoteApiCommand = {
  action: string;
  commandId: number;
  commandHex: string;
  responseHex?: string;
  hardwareStatus?: "PROVEN_READ" | "PROVEN_WRITE" | "EVIDENCE_ONLY" | "PSEUDO";
  local: boolean;
  kind: "get" | "set" | "action";
  safeForPhase09: boolean;
  transport: "collector" | "appPolling" | "existingGattMethod";
  description: string;
};

export const REMOTE_API = {
  "input.source.get": { action:"input.source.get", commandId:0x0064, commandHex:"0x0064", responseHex:"0x0064", local:false, kind:"get", safeForPhase09:true, transport:"collector", hardwareStatus:"PROVEN_READ", description:"Recorder-backed Input.SourceGet; semantic value uses official InputSource enum names" },
  "input.hdrState.get": { action:"input.hdrState.get", commandId:0x0071, commandHex:"0x0071", responseHex:"0x006D", local:false, kind:"get", safeForPhase09:true, transport:"collector", hardwareStatus:"PROVEN_READ", description:"Recorder-backed Input HDR state getter; InputHDRLogStateMsg field1=hdrEnabled field2=hdrChangeable; AG2 response 0x006D" },
  "input.deviceName.get": { action:"input.deviceName.get", commandId:0x0070, commandHex:"0x0070", responseHex:"0x006C", local:false, kind:"get", safeForPhase09:true, transport:"collector", hardwareStatus:"PROVEN_READ", description:"Recorder-backed Input.DeviceNameGet; AG2 response message type 0x006C" },
  "input.page.get": { action:"input.page.get", commandId:0x0642, commandHex:"0x0642", local:false, kind:"get", safeForPhase09:true, transport:"collector", description:"Recorder-backed granular Input collector" },
  "input.hdr.get": { action:"input.hdr.get", commandId:0x0645, commandHex:"0x0645", local:false, kind:"get", safeForPhase09:true, transport:"collector", description:"Input HDR view sourced from recorder collector" },
  "input.summary.get": { action:"input.summary.get", commandId:0x0648, commandHex:"0x0648", local:false, kind:"get", safeForPhase09:true, transport:"collector", description:"Input/Output summary" },

  "state.application.get": { action:"state.application.get", commandId:0x0640, commandHex:"0x0640", local:false, kind:"get", safeForPhase09:true, transport:"appPolling", description:"ApplicationStateGet" },
  "state.monitor.get": { action:"state.monitor.get", commandId:0x0641, commandHex:"0x0641", local:false, kind:"get", safeForPhase09:true, transport:"appPolling", description:"MonitorPageGet" },
  "state.input.get": { action:"state.input.get", commandId:0x0642, commandHex:"0x0642", local:false, kind:"get", safeForPhase09:true, transport:"appPolling", description:"InputPageGet" },
  "state.record.get": { action:"state.record.get", commandId:0x0643, commandHex:"0x0643", local:false, kind:"get", safeForPhase09:true, transport:"appPolling", description:"RecordPageGet" },
  "state.output.get": { action:"state.output.get", commandId:0x0644, commandHex:"0x0644", local:false, kind:"get", safeForPhase09:true, transport:"appPolling", description:"OutputPageGet" },
  "state.inputHdr.get": { action:"state.inputHdr.get", commandId:0x0645, commandHex:"0x0645", local:false, kind:"get", safeForPhase09:true, transport:"appPolling", description:"InputPageHdrGet" },
  "state.playbackFormat.get": { action:"state.playbackFormat.get", commandId:0x0646, commandHex:"0x0646", local:false, kind:"get", safeForPhase09:true, transport:"appPolling", description:"PlaybackFormatPageGet" },
  "state.audioMeters.get": { action:"state.audioMeters.get", commandId:0x0647, commandHex:"0x0647", local:false, kind:"get", safeForPhase09:true, transport:"appPolling", description:"AudioMetersPageGet" },
  "state.inputOutput.get": { action:"state.inputOutput.get", commandId:0x0648, commandHex:"0x0648", local:false, kind:"get", safeForPhase09:true, transport:"appPolling", description:"InputOutputSummaryGet" },
  "state.monitorAssist.get": { action:"state.monitorAssist.get", commandId:0x0649, commandHex:"0x0649", local:false, kind:"get", safeForPhase09:true, transport:"appPolling", description:"MonitorAssistSummaryStateGet" },

  "monitor.focus.set": { action:"monitor.focus.set", commandId:0x0262, commandHex:"0x0262", local:true, kind:"set", safeForPhase09:true, transport:"existingGattMethod", description:"FocusPeakEnableSet via existing sendOfficialMonitorAssistToggle" },
  "monitor.zebra.set": { action:"monitor.zebra.set", commandId:0x026A, commandHex:"0x026A", local:true, kind:"set", safeForPhase09:true, transport:"existingGattMethod", description:"ZebraEnableSet" },
  "monitor.falseColor.set": { action:"monitor.falseColor.set", commandId:0x026E, commandHex:"0x026E", local:true, kind:"set", safeForPhase09:true, transport:"existingGattMethod", description:"FalseColourEnableSet" },
  "monitor.blueOnly.set": { action:"monitor.blueOnly.set", commandId:0x0270, commandHex:"0x0270", local:true, kind:"set", safeForPhase09:true, transport:"existingGattMethod", description:"BlueOnlyEnableSet" },

  "scopes.enabled.set": { action:"scopes.enabled.set", commandId:0x0258, commandHex:"0x0258", local:true, kind:"set", safeForPhase09:true, transport:"existingGattMethod", description:"WaveformEnableSet" },
  "scopes.mode.set": { action:"scopes.mode.set", commandId:0x025A, commandHex:"0x025A", local:true, kind:"set", safeForPhase09:true, transport:"existingGattMethod", description:"WaveformModeSet" },
  "scopes.size.set": { action:"scopes.size.set", commandId:0x025C, commandHex:"0x025C", local:true, kind:"set", safeForPhase09:true, transport:"existingGattMethod", description:"WaveformSizeSet" },

  "guides.frame.enabled.set": { action:"guides.frame.enabled.set", commandId:0x044E, commandHex:"0x044E", local:true, kind:"set", safeForPhase09:true, transport:"existingGattMethod", description:"FrameGuideSet bool" },
  "guides.safeArea.set": { action:"guides.safeArea.set", commandId:0x0277, commandHex:"0x0277", local:true, kind:"set", safeForPhase09:true, transport:"existingGattMethod", description:"SafeAreaSizeSet using current hardware-corrected mapping" },
  "monitor.anamorphic.set": { action:"monitor.anamorphic.set", commandId:0x0279, commandHex:"0x0279", local:true, kind:"set", safeForPhase09:true, transport:"existingGattMethod", description:"AnamorphicDesqueezeModeSet" },
  "monitor.mode.set": { action:"monitor.mode.set", commandId:0x02BC, commandHex:"0x02BC", local:true, kind:"set", safeForPhase09:true, transport:"existingGattMethod", description:"MonitorModeSet 1 Native, 2 Rec709, 3 HLG, 4 PQ, 5 LUT" }
} satisfies Record<string, RemoteApiCommand>;

export const getRemoteApiCommand = (action: string): RemoteApiCommand | undefined =>
  REMOTE_API[action as keyof typeof REMOTE_API];
