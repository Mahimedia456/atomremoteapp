# Phase 14 research summary

- Official AtomRemote control for Shogun Ultra is documented for iOS and macOS over Bluetooth LE.
- Official flow: grant Bluetooth access, put recorder in Pairing Mode, search, compare Pairing ID, accept, then enable Remote mode on the recorder.
- Official example Pairing ID format is `716-222-548`.
- Shogun Ultra Wi-Fi/Ethernet are network connectivity paths; Atomos documents AtomRemote remote control specifically over Bluetooth LE.
- Android 12+ requires `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` to exist in the final AndroidManifest and to be requested at runtime. Phase 14 requests them automatically when the Connect screen opens.
- Phase 14 includes a local Expo config plugin that writes the required Bluetooth permissions directly into the generated AndroidManifest during prebuild.
- The public Atomos documentation does not expose the proprietary GATT service UUIDs and pairing-accept/control payloads. Phase 14 therefore performs safe discovery/read operations but does not transmit guessed command bytes.
