import crypto from "node:crypto";
import { getDevice } from "../state/deviceRegistry.js";
import { getRemoteApiCommand } from "../protocol/remoteApiRegistry.js";
import { waitForResult } from "./pendingRequests.js";
import type { AgentCommand } from "../types/messages.js";

export async function dispatchAction(deviceId: string, action: string, payload?: unknown) {
  const device = getDevice(deviceId);
  if (!device) throw new Error(`Device agent not connected: ${deviceId}`);

  const command = getRemoteApiCommand(action);
  if (!command) throw new Error(`Unknown action: ${action}`);
  if (!command.safeForPhase09) throw new Error(`Action not enabled in Phase 09: ${action}`);

  const requestId = crypto.randomUUID();
  const message: AgentCommand = {
    type: "agent.command",
    requestId,
    deviceId,
    action,
    commandId: command.commandId,
    commandHex: command.commandHex,
    local: command.local,
    payload
  };

  // Real AG2 BLE/PacketLink reads are serialized and can legitimately spend
  // several seconds waiting for the ATT lane before the recorder request even
  // starts. Never let an old 5s environment override expire ahead of the
  // Android collector itself.
  const configuredTimeoutMs = Number(process.env.ATOMOS_REQUEST_TIMEOUT_MS || 30000);
  const timeoutMs = Number.isFinite(configuredTimeoutMs)
    ? Math.max(configuredTimeoutMs, 30000)
    : 30000;
  const resultPromise = waitForResult(requestId, timeoutMs);
  device.socket.send(JSON.stringify(message));
  return resultPromise;
}
