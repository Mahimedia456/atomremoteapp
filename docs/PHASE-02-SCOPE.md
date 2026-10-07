# ATOMOS Remote - Phase 02 Scope

## Goal

Bind the Phase 01 dark UI to a production-oriented Bluetooth LE discovery and pairing lifecycle while keeping proprietary Atomos command UUIDs configurable rather than guessed.

## Delivered

- Single long-lived BLE runtime / manager
- Android BLE permission handling
- iOS Bluetooth permission path through native configuration
- Wait-for-PoweredOn handling
- 10-second device scanning
- Atomos-name filtering with optional Show All Bluetooth mode
- RSSI / signal strength presentation
- BLE connect and GATT discovery
- GATT service + characteristic inspector
- Shareable JSON GATT report
- Atomos pairing lifecycle UI
- Pairing ID comparison UI when validated UUIDs are configured
- Configurable pairing service / characteristic UUIDs through EXPO_PUBLIC environment variables
- Persistent saved devices using AsyncStorage
- Reconnect, remove and re-pair flows
- Demo discovery disabled by default
- Explicit separation of BLE transport connection vs Atomos protocol pairing

## Atomos pairing lifecycle reflected in the UI

1. Put the recorder into Bluetooth Pairing Mode.
2. Discover and connect over BLE.
3. Compare the Pairing ID shown by the recorder and app.
4. Confirm pairing only when the IDs match.
5. Enable Remote mode on the recorder.

## Protocol boundary

Atomos publicly documents the user-facing pairing sequence, but the full GATT service/characteristic contract used by AtomRemote is not published in the public setup documentation reviewed for this phase.

Phase 02 therefore does not ship guessed Atomos UUIDs or pretend that an OS-level BLE connection equals Atomos protocol pairing.

To activate Pairing ID read/accept once validated values are available, configure:

- `EXPO_PUBLIC_ATOMOS_PAIRING_SERVICE_UUID`
- `EXPO_PUBLIC_ATOMOS_PAIRING_CODE_CHARACTERISTIC_UUID`
- `EXPO_PUBLIC_ATOMOS_PAIRING_ACCEPT_CHARACTERISTIC_UUID`
- `EXPO_PUBLIC_ATOMOS_PAIRING_ACCEPT_ASCII`

The pairing screen automatically uses these values when all required UUIDs are present.

## Not part of Phase 02

- Record / Stop protocol commands
- Playback commands
- Monitor tool commands
- Wi-Fi protocol discovery
- Ethernet protocol discovery
- USB host/native accessory commands
- AirGlu command binding
- NDI SDK binding

Those remain isolated for later phases.
