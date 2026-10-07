import type { Server } from "node:http";
import { WebSocketServer, WebSocket } from "ws";
import { z } from "zod";
import { registerDevice, removeDevice } from "../state/deviceRegistry.js";
import { resolveResult, rejectPendingForDevice } from "./pendingRequests.js";
import type { AgentResult } from "../types/messages.js";

const helloSchema = z.object({
  type: z.literal("agent.hello"),
  token: z.string(),
  deviceId: z.string().min(1),
  deviceName: z.string().optional(),
  model: z.string().optional(),
  firmware: z.string().optional()
});

const resultSchema = z.object({
  type: z.literal("agent.result"),
  requestId: z.string(),
  deviceId: z.string(),
  ok: z.boolean(),
  action: z.string(),
  responseId: z.string().optional(),
  remoteResponse: z.string().optional(),
  rawPayloadHex: z.string().optional(),
  data: z.unknown().optional(),
  error: z.string().optional()
});

export function attachAgentWebSocket(server: Server) {
  const wss = new WebSocketServer({ server, path: "/ws/agent" });

  wss.on("connection", (socket: WebSocket) => {
    let registeredDeviceId: string | undefined;

    socket.on("message", (raw) => {
      try {
        const parsed = JSON.parse(raw.toString());

        if (parsed?.type === "agent.hello") {
          const hello = helloSchema.parse(parsed);
          const expected = process.env.ATOMOS_AGENT_TOKEN || "change-me-local";
          if (hello.token !== expected) {
            socket.close(1008, "Unauthorized agent");
            return;
          }

          registeredDeviceId = hello.deviceId;
          registerDevice({
            deviceId: hello.deviceId,
            deviceName: hello.deviceName || hello.deviceId,
            model: hello.model,
            firmware: hello.firmware,
            connectedAt: new Date().toISOString(),
            socket
          });

          socket.send(JSON.stringify({ type: "agent.ready", deviceId: hello.deviceId }));
          return;
        }

        if (parsed?.type === "agent.result") {
          const result = resultSchema.parse(parsed) as AgentResult;
          resolveResult(result);
        }
      } catch (error) {
        socket.send(JSON.stringify({
          type: "agent.error",
          error: error instanceof Error ? error.message : "Invalid agent message"
        }));
      }
    });

    socket.on("close", () => {
      if (registeredDeviceId) {
        removeDevice(registeredDeviceId);
        rejectPendingForDevice(registeredDeviceId, "Agent disconnected");
      }
    });
  });

  return wss;
}
