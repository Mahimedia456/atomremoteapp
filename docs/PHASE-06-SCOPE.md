# Phase 06 - Monitor Tools

Phase 06 adds a dedicated dark-theme monitoring control surface for compatible Atomos recorders.

## Controls prepared
- Waveform
- Zebra
- Focus Peaking
- False Color

Each tool has separate validated ON and OFF command slots. The UI remains protocol-locked until both payloads are supplied. No proprietary command bytes are guessed.

## Retained from Phase 05
- SDK 57 dependency repair
- React 19.2.3 and React DOM 19.2.3 lockstep
- React Native 0.86.3 baseline
- Telemetry dashboard and history
- Record/Stop safety gate
- Pairing, reconnect, GATT inspector and protocol monitor

## BLE compatibility
This phase uses `@sfourdrinier/react-native-ble-plx` 3.9.3 because that line explicitly targets Expo SDK 57 and React Native 0.86 New Architecture. The upstream 3.5.1 package is removed from this project baseline.
