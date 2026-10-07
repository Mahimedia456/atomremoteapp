import type WebSocket from "ws";

export type ConnectedDevice = {
  deviceId: string;
  deviceName: string;
  model?: string;
  firmware?: string;
  connectedAt: string;
  socket: WebSocket;
};

const devices = new Map<string, ConnectedDevice>();

export function registerDevice(device: ConnectedDevice) {
  devices.set(device.deviceId, device);
}

export function removeDevice(deviceId: string) {
  devices.delete(deviceId);
}

export function getDevice(deviceId: string) {
  return devices.get(deviceId);
}

export function listDevices() {
  return [...devices.values()].map(({ socket: _socket, ...rest }) => rest);
}
