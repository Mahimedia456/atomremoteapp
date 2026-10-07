# ATOMOS Remote Native Android - Phases 14-15

## Phase 14 - Advanced Control, Presets, Capture, NDI Boundary

- Adds an NDI source browser fed by native Android mDNS/DNS-SD discovery.
- Keeps actual NDI video decoding behind a native `NdiPreviewBridge`. The safe default implementation does not pretend that an NDI SDK is linked.
- Adds an advanced recorder control catalog for Input, Record and Output functions.
- Adds `extraCommands` to the validated protocol profile format so future Atomos SDK/hardware-validated payloads can be mapped without changing the UI.
- Adds recorder presets as ordered lists of validated command keys.
- Adds GATT/protocol capture export to JSON and Android share sheet.
- No guessed proprietary Atomos command payload is transmitted.

## Phase 15 - QA and Release Hardening

- Adds in-app release-readiness checks for BLE hardware, Android runtime permissions, Bluetooth state, network state, GATT readiness, validated protocol status, NDI bridge and AirGlu bridge.
- Adds final PowerShell QA and release-build scripts.
- Adds optional release signing through environment variables without committing keystore material.
- Adds final compatibility matrix and remaining protocol limitations.
- Native app version: `0.15.0-native`.

## Release signing environment variables

Set these only on the build machine when a release keystore is available:

```powershell
$env:ATOMOS_RELEASE_STORE_FILE="E:\keys\atomos-release.jks"
$env:ATOMOS_RELEASE_STORE_PASSWORD="..."
$env:ATOMOS_RELEASE_KEY_ALIAS="atomos"
$env:ATOMOS_RELEASE_KEY_PASSWORD="..."
```

The project does not contain or generate a production signing key.
