import type { AgentResult } from "../types/messages.js";

type Pending = {
  resolve: (value: AgentResult) => void;
  reject: (reason: Error) => void;
  timer: NodeJS.Timeout;
};

const pending = new Map<string, Pending>();

export function waitForResult(requestId: string, timeoutMs: number): Promise<AgentResult> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(requestId);
      reject(new Error(`Agent response timeout after ${timeoutMs}ms`));
    }, timeoutMs);

    pending.set(requestId, { resolve, reject, timer });
  });
}

export function resolveResult(result: AgentResult): boolean {
  const item = pending.get(result.requestId);
  if (!item) return false;
  clearTimeout(item.timer);
  pending.delete(result.requestId);
  item.resolve(result);
  return true;
}

export function rejectPendingForDevice(deviceId: string, message: string) {
  for (const [requestId, item] of pending) {
    clearTimeout(item.timer);
    pending.delete(requestId);
    item.reject(new Error(`${message} (${deviceId})`));
  }
}
