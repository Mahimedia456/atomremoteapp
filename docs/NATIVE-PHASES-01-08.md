# ATOMOS Remote Native Android - Phases 01 to 08

## Phase 01 - Native foundation
- Kotlin Android app
- Jetpack Compose dark ATOMOS UI
- Native app package: `com.atomosremote.nativeapp`
- Existing Expo prototype remains untouched

## Phase 02 - Native Bluetooth LE discovery
- Android system Nearby Devices permission is requested automatically when Connect is opened
- Direct `BluetoothLeScanner` low-latency unfiltered scan
- Raw BLE device address, RSSI, local name, advertised service UUIDs and manufacturer data
- All BLE advertisements are shown; unknown/unnamed devices are not hidden

## Phase 03 - GATT connection and hardware mapping
- Direct `BluetoothGatt` LE connection
- Service/characteristic discovery
- READ/WRITE/WRITE_NO_RESPONSE/NOTIFY/INDICATE capability map
- Safe reads of readable characteristics
- Notification/indication subscription
- Raw HEX + printable ASCII event log
- Device-family profiles for Shogun, Shogun Ultra, Shogun CONNECT, Ninja families and generic ATOMOS fallback

## Phase 04 - Pairing and identity foundation
- Pairing ID detector for common 9-digit / `XXX-XXX-XXX` representations found in readable/notified data
- Official-style compare-code UI
- Saved native BLE device address
- Reconnect foundation
- Primary Shogun Ultra metadata included for the user's current recorder:
  - DID: ASGUFLMX92C
  - AID: SGUAkAAAIAaMoIwhcSjD5A5
  - BID: B604
  - AtomOS: 11.18.00
  - AirGlu: AGu2s 2.31;TFP2s 1.6;8

Important: Atomos' proprietary Pair-Accept characteristic/payload is not invented. If the pairing code is not exposed through safe reads/notifications, the validated protocol is still required.

## Phase 05 - Protocol driver engine
- Runtime protocol-profile JSON
- UUID and payload loading
- Explicit `validated=true` requirement
- Transmission remains locked until service UUID, command UUID and a validated command payload exist
- Template at `app/src/main/assets/protocol_profile_template.json`

## Phase 06 - Record and playback command layer
- Record Start
- Record Stop
- Play
- Pause
- Direct native GATT write path
- Write-with-response / write-without-response support
- No guessed proprietary command bytes

## Phase 07 - Telemetry foundation
- Battery parser
- Media remaining parser
- Timecode parser
- Input format parser
- Codec parser
- Record-state parser
- Raw GATT events remain visible for mapping unknown telemetry

## Phase 08 - Monitor controls
- Waveform
- Zebra
- Focus Peaking
- False Color
- 1:1 Zoom
- Frame Guides
- Each command is locked until the corresponding validated payload exists

## What is intentionally not claimed as complete
The public AtomRemote setup documentation explains the user-level pairing workflow, but it does not publish the complete proprietary GATT UUID/packet map needed for third-party command transmission. This native batch removes Expo/React Native from the Bluetooth path and gives direct Android BLE/GATT visibility, but the final Atomos Pair-Accept and remote command payloads still need validated protocol data.

## Next native phases
- Phase 09: Wi-Fi/LAN service discovery, mDNS/DNS-SD and supported network services
- Phase 10: Multi-device / Record All
- Phase 11: AirGlu integration boundary and gang-control mapping
- Phase 12: NDI/network preview integration
- Phase 13+: broader hardware compatibility, reliability, background reconnect, QA and release
