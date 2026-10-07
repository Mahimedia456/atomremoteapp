# Phase 01 Scope

## Product foundation

Phase 01 establishes a transport-agnostic mobile architecture so a device can be controlled through whichever supported connection path it exposes.

### Connection transports represented in the app

1. Bluetooth LE
2. Wi-Fi
3. Ethernet / LAN
4. USB-C / Wired
5. AirGlu
6. NDI / Network Preview

### What is native-ready now

- BLE permissions and development-build configuration
- BLE manager initialization
- BLE scan
- Atomos-family name filtering
- Android runtime BLE permission request
- Local network status detection
- IP transport abstraction

### What intentionally remains protocol-bound

- Exact Atomos GATT service / characteristic UUID mapping
- Record / stop / playback command payloads
- Recorder telemetry characteristic parsing
- Atomos LAN discovery service signature
- AirGlu control protocol
- USB accessory command protocol
- NDI SDK integration and licensing

These items should only be implemented after obtaining official documentation or validating the protocol against target hardware/firmware.

## Phase 02 target

- Hardware lab pairing tests
- Official/validated BLE service enumeration
- Pairing-code workflow
- Stable device identity model
- Live connect/disconnect state
- Real device capabilities
- Record/stop transport command contract
