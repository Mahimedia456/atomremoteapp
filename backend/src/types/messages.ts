export type AgentHello = {
  type: "agent.hello";
  token: string;
  deviceId: string;
  deviceName?: string;
  model?: string;
  firmware?: string;
};

export type AgentCommand = {
  type: "agent.command";
  requestId: string;
  deviceId: string;
  action: string;
  commandId: number;
  commandHex: string;
  local: boolean;
  payload?: unknown;
};

export type AgentResult = {
  type: "agent.result";
  requestId: string;
  deviceId: string;
  ok: boolean;
  action: string;
  responseId?: string;
  remoteResponse?: string;
  rawPayloadHex?: string;
  data?: unknown;
  error?: string;
};
