import { Router } from "express";
import { listDevices } from "../state/deviceRegistry.js";
import { REMOTE_API } from "../protocol/remoteApiRegistry.js";
import { dispatchAction } from "../agent/dispatch.js";

export const api = Router();

api.get("/capabilities", (_req, res) => {
  res.json({ ok: true, phase: "07-09", commands: Object.values(REMOTE_API) });
});

api.get("/devices", (_req, res) => res.json({ ok: true, devices: listDevices() }));

async function run(res: any, deviceId: string, action: string, payload?: unknown) {
  try {
    const result = await dispatchAction(deviceId, action, payload);
    const spec = REMOTE_API[action as keyof typeof REMOTE_API];
    const fallbackResponseHex =
      spec && "responseHex" in spec && typeof spec.responseHex === "string"
        ? spec.responseHex
        : undefined;

    res.status(result.ok ? 200 : 502).json({
      ok: result.ok,
      deviceId,
      action,
      commandId: spec?.commandHex,
      responseId: result.responseId ?? fallbackResponseHex,
      remoteResponse: result.remoteResponse,
      rawPayloadHex: result.rawPayloadHex,
      data: result.data,
      error: result.error
    });
  } catch (error) {
    res.status(503).json({ ok:false, deviceId, action, error:error instanceof Error ? error.message : "Command failed" });
  }
}

api.get("/devices/:deviceId/input", (req,res)=>run(res,req.params.deviceId,"input.page.get"));
api.get("/devices/:deviceId/input/hdr", (req,res)=>run(res,req.params.deviceId,"input.hdr.get"));
api.get("/devices/:deviceId/input/summary", (req,res)=>run(res,req.params.deviceId,"input.summary.get"));
api.get("/devices/:deviceId/input/source", (req,res)=>run(res,req.params.deviceId,"input.source.get"));
api.get("/devices/:deviceId/input/hdr-state", (req,res)=>run(res,req.params.deviceId,"input.hdrState.get"));
api.get("/devices/:deviceId/input/device-name", (req,res)=>run(res,req.params.deviceId,"input.deviceName.get"));
api.get("/devices/:deviceId/input/capabilities", (_req,res)=>res.json({
  ok:true,
  phase:"20.03.5.3",
  displayPolicy:"SEMANTIC_NAMES_IN_UI_RAW_IDS_IN_DIAGNOSTICS",
  sessionPolicy:"FROZEN_20.03.4.13_INPUT_CACHE_ONLY_NO_TAB_OR_BACKEND_REFRESH",
  sourceEnumMap:{
    0:"HDMI",
    1:"SDI A",
    2:"SDI B",
    3:"SDI Dual Link",
    4:"SDI Quad Link",
    5:"SDI Multistream",
    6:"SDI Auto Select",
    7:"HDMI Multistream"
  },
  sourceEnumProvenance:"official macOS AtomProtobuf enum order; current value is hardware-confirmed only when returned by AG2",
  hdrStateSchema:{field1:"hdrEnabled",field2:"hdrChangeable"},
  recoveredInputGetters:{
    sourceGet:"0x0064",
    sourceListGet:"0x0065",
    statusGet:"0x0067",
    infoGet:"0x0068",
    hdmiStandardGet:"0x0069",
    triggerSourceGet:"0x006B",
    triggerEnabledGet:"0x006D",
    deviceManufacturerGet:"0x006F",
    deviceNameGet:"0x0070",
    hdrEnableStateGet:"0x0071",
    hdrCameraGet:"0x0073",
    hdrGammaGet:"0x0075",
    hdrGamutGet:"0x0077",
    hdrAutoGet:"0x0079",
    hdrCameraListGet:"0x007B",
    hdrGammaListGet:"0x007C",
    hdrGamutListGet:"0x007D",
    triggerSourceListGet:"0x007E",
    sdiLinkStateGet:"0x007F",
    activeSdiSourceGet:"0x0080",
    rangeGet:"0x0081",
    changeIdGet:"0x0083",
    rawStatusGet:"0x0084",
    cameraMetadataGet:"0x0085",
    hdmiStandardListGet:"0x0086"
  },
  getterIdProvenance:"official macOS RemoteApi.Input symbol order anchored by hardware-proven SourceGet=0x0064, DeviceNameGet=0x0070 and HdrEnableStateGet=0x0071",
  remainingReadPolicy:"COMMAND_ID_RECOVERED_RESPONSE_ID_AND_PAYLOAD_STILL_REQUIRE_HARDWARE_CONFIRMATION",
  policy:"READ_ONLY_UNTIL_EXACT_SET_PLUS_READBACK_IS_PROVEN",
  provenReads:[
    {setting:"Source",commandId:"0x0064",responseId:"0x0064",m_isLocal:false,packetId:"sequential 1..255",status:"PASS"},
    {setting:"HDR State",commandId:"0x0071",responseId:"0x006D",m_isLocal:false,packetId:"sequential 1..255",status:"PASS"},
    {setting:"Device Name",commandId:"0x0070",responseId:"0x006C",m_isLocal:false,packetId:"sequential 1..255",status:"PASS"}
  ],
  macOsEvidencePendingIdRecovery:[
    "Input.SourceSet","Input.TriggerSourceGet","Input.TriggerSourceSet","Input.HdrCameraGet","Input.HdrCameraSet",
    "Input.HdrGammaGet","Input.HdrGammaSet","Input.HdrGamutGet","Input.HdrGamutSet",
    "Input.HdrCameraListGet","Input.HdrGammaListGet","Input.HdrGamutListGet",
    "Input.ActiveSdiSourceGet","Input.CameraMetadataGet"
  ]
}));

const stateRoutes: Record<string,string> = {
  application:"state.application.get", monitor:"state.monitor.get", input:"state.input.get",
  record:"state.record.get", output:"state.output.get", "input-hdr":"state.inputHdr.get",
  "playback-format":"state.playbackFormat.get", "audio-meters":"state.audioMeters.get",
  "input-output":"state.inputOutput.get", "monitor-assist":"state.monitorAssist.get"
};
api.get("/devices/:deviceId/state/:page", (req,res)=> {
  const action = stateRoutes[req.params.page];
  if (!action) return res.status(404).json({ok:false,error:"Unknown state page"});
  return run(res,req.params.deviceId,action);
});

api.post("/devices/:deviceId/monitor/focus", (req,res)=>run(res,req.params.deviceId,"monitor.focus.set",{enabled:Boolean(req.body?.enabled)}));
api.post("/devices/:deviceId/monitor/zebra", (req,res)=>run(res,req.params.deviceId,"monitor.zebra.set",{enabled:Boolean(req.body?.enabled)}));
api.post("/devices/:deviceId/monitor/false-color", (req,res)=>run(res,req.params.deviceId,"monitor.falseColor.set",{enabled:Boolean(req.body?.enabled)}));
api.post("/devices/:deviceId/monitor/blue-only", (req,res)=>run(res,req.params.deviceId,"monitor.blueOnly.set",{enabled:Boolean(req.body?.enabled)}));

api.post("/devices/:deviceId/scopes/enabled", (req,res)=>run(res,req.params.deviceId,"scopes.enabled.set",{enabled:Boolean(req.body?.enabled)}));
api.post("/devices/:deviceId/scopes/mode", (req,res)=>run(res,req.params.deviceId,"scopes.mode.set",{mode:String(req.body?.mode ?? "")}));
api.post("/devices/:deviceId/scopes/size", (req,res)=>run(res,req.params.deviceId,"scopes.size.set",{size:String(req.body?.size ?? "")}));

api.post("/devices/:deviceId/guides/frame", (req,res)=>run(res,req.params.deviceId,"guides.frame.enabled.set",{enabled:Boolean(req.body?.enabled)}));
api.post("/devices/:deviceId/guides/safe-area", (req,res)=>run(res,req.params.deviceId,"guides.safeArea.set",{mode:Number(req.body?.mode)}));
api.post("/devices/:deviceId/monitor/anamorphic", (req,res)=>run(res,req.params.deviceId,"monitor.anamorphic.set",{mode:Number(req.body?.mode)}));
api.post("/devices/:deviceId/monitor/mode", (req,res)=>run(res,req.params.deviceId,"monitor.mode.set",{mode:Number(req.body?.mode)}));
