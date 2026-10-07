# Phase 05 - Recorder Status and Telemetry

Phase 05 repairs the Expo SDK 57 dependency tree and expands the recorder status layer.

## Dependency baseline
- Expo SDK 57
- React 19.2.3
- React DOM 19.2.3
- React Native 0.86.3
- React Native Web 0.21.0
- Node.js 22.13.x or newer

The explicit `react-dom` pin prevents npm from selecting React DOM 19.3.x against React 19.2.3.

## App work
- Dedicated recorder telemetry screen
- Battery, media remaining, timecode, input format, resolution, frame rate, codec and signal fields
- Session telemetry history ring buffer
- Fresh/stale/missing health state
- Telemetry completeness indicator
- Raw status ASCII and HEX preview
- Existing Record/Stop safety gate retained

No proprietary Atomos command or status byte layout is guessed. Decoder mappings are applied only to configured/validated status data.
