# Phase 13 - Permission Repair + AtomRemote Alignment + Network Diagnostics

## Bluetooth / Android permission repair
- Runtime Nearby devices permission center.
- Explicit BLUETOOTH_SCAN and BLUETOOTH_CONNECT status.
- Location shown separately as an OEM BLE compatibility aid.
- In-app Grant Nearby Devices action.
- Open App Settings, Bluetooth Settings and Location Settings actions.
- Scan screen can repair permissions without leaving the flow.
- BLE errors are written to the Diagnostic Console.

## AtomRemote-aligned control layout
The new Remote Control screen mirrors the official AtomRemote information architecture without pretending undocumented command bytes exist:
- Input, Monitor and Output areas.
- Record resolution/codec/frame-rate summary.
- Monitor Assist tools.
- Record / Playback / Overlays transport strip.
- Battery, timecode, media remaining and record-state status.
- Remote-mode guidance after pairing.

Actual device writes remain safety-locked until the Atomos BLE GATT/control protocol is validated.

## Wi-Fi / Ethernet correction
Official AtomRemote control is Bluetooth LE. Shogun Ultra Wi-Fi 6E and 1GbE are treated as network-service transports for cloud, streaming, RemoteView, NDI and diagnostics rather than an invented local remote-control API.

Network diagnostics now show:
- phone IP and network state,
- HTTP/HTTPS scheme,
- recorder IP/hostname and port,
- response status and latency,
- timeout/network failure details,
- persistent diagnostic console entries.

Android cleartext HTTP is enabled for local-IP diagnostics through expo-build-properties, so a clean native rebuild is required once.
