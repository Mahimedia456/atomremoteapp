import "dotenv/config";
import http from "node:http";
import express from "express";
import cors from "cors";
import { api } from "./api/routes.js";
import { attachAgentWebSocket } from "./agent/websocket.js";

const host = process.env.ATOMOS_BRIDGE_HOST || "0.0.0.0";
const port = Number(process.env.ATOMOS_BRIDGE_PORT || 8787);

const app = express();
app.use(cors());
app.use(express.json({ limit: "256kb" }));

app.get("/health", (_req, res) => {
  res.json({
    ok: true,
    service: "AtomRemote Bridge",
    version: "0.1.0",
    phase: "01-03",
    time: new Date().toISOString()
  });
});

app.use("/api/v1", api);

const server = http.createServer(app);
attachAgentWebSocket(server);

server.listen(port, host, () => {
  console.log(`AtomRemote Bridge listening on http://${host}:${port}`);
  console.log(`Agent WebSocket: ws://${host}:${port}/ws/agent`);
});
