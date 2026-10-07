import WebSocket from "ws";

const port = Number(process.env.ATOMOS_BRIDGE_PORT || 8787);
const url = process.env.ATOMOS_SIMULATOR_WS || `ws://127.0.0.1:${port}/ws/agent`;
const token = process.env.ATOMOS_AGENT_TOKEN || "change-me-local";
const deviceId = process.env.ATOMOS_SIMULATOR_DEVICE_ID || "ag2-sim";

const ws = new WebSocket(url);

ws.on("open", () => {
  ws.send(JSON.stringify({
    type: "agent.hello",
    token,
    deviceId,
    deviceName: "Shogun Ultra Simulator",
    model: "AG2",
    firmware: "SIM"
  }));
});

ws.on("message", (raw) => {
  const msg = JSON.parse(raw.toString());
  if (msg.type === "agent.ready") {
    console.log(`Simulator ready as ${msg.deviceId}`);
    return;
  }

  if (msg.type !== "agent.command") return;

  const base = {
    type: "agent.result",
    requestId: msg.requestId,
    deviceId,
    ok: true,
    action: msg.action,
    responseId: msg.commandHex,
    remoteResponse: "0x8000",
    rawPayloadHex: "SIMULATED"
  };

  let data: unknown = { simulated: true };
  if (msg.action === "input.page.get") {
    data = {
      source: "HDMI",
      input_status: "connected",
      hdmi_standard: "2.0",
      hdmi_standards: ["1.4", "2.0"],
      sources: ["HDMI", "SDI"],
      trigger_source: "HDMI",
      trigger_sources: ["HDMI", "SDI"],
      trigger_enabled: false,
      trigger_changeable: true,
      device_manufacturer: "Simulator",
      device_name: "Camera",
      sdi_source: null
    };
  } else if (msg.action === "input.hdr.get") {
    data = {
      hdr_enabled: false,
      hdr_camera: null,
      hdr_cameras: [],
      hdr_gamma: "Native",
      hdr_gammas: ["Native", "HLG", "PQ"],
      hdr_gamut: "Native",
      hdr_gamuts: ["Native"],
      hdr_auto_enabled: false,
      hdr_auto_changeable: true,
      range: "Auto",
      hdr_changeable: true
    };
  } else if (msg.action === "input.summary.get") {
    data = { source: "HDMI", format: "2160p", frameRate: 25, simulated: true };
  }

  ws.send(JSON.stringify({ ...base, data }));
});

ws.on("close", () => console.log("Simulator disconnected"));
ws.on("error", (error) => console.error(error));
