const base = process.env.ATOMOS_VERIFY_BASE || "http://127.0.0.1:8787";
const deviceId = process.env.ATOMOS_SIMULATOR_DEVICE_ID || "ag2-sim";

async function json(path: string) {
  const r = await fetch(base + path);
  const body = await r.json();
  if (!r.ok) throw new Error(`${path} -> HTTP ${r.status}: ${JSON.stringify(body)}`);
  return body;
}

const health = await json("/health");
if (!health.ok) throw new Error("health failed");

const devices = await json("/api/v1/devices");
if (!devices.devices?.some((d: any) => d.deviceId === deviceId)) {
  throw new Error(`Simulator ${deviceId} not connected`);
}

const input = await json(`/api/v1/devices/${deviceId}/input`);
if (!input.ok || input.commandId !== "0x0642") throw new Error("InputPageGet failed");

const hdr = await json(`/api/v1/devices/${deviceId}/input/hdr`);
if (!hdr.ok || hdr.commandId !== "0x0645") throw new Error("InputPageHdrGet failed");

const summary = await json(`/api/v1/devices/${deviceId}/input/summary`);
if (!summary.ok || summary.commandId !== "0x0648") throw new Error("InputOutputSummaryGet failed");

console.log("PHASES 01-03 VERIFY PASS");
console.log(JSON.stringify({ health, input, hdr, summary }, null, 2));
