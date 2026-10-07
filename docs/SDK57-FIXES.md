# SDK 57 fixes included in Batch 07-09

This batch repairs the errors seen after Phase 05/06 installation:

1. `app.config.ts` no longer uses the legacy top-level `splash` property. Splash is configured with the `expo-splash-screen` config plugin.
2. `BleRuntime.state()` no longer claims the old BLE `State` enum type. Its return type is inferred from the maintained BLE manager package.
3. React Native animation dependencies are pinned to the SDK 57-compatible pair used by current Expo 57 tooling: `react-native-reanimated 4.5.1` and `react-native-worklets 0.10.1`.
4. Expo-aligned package versions are pinned for safe-area, screens, web, and TypeScript.
5. The installer removes the stale Phase 05/06 `node_modules`, package lock, and `.expo` route cache before reinstalling, then runs Expo compatibility and TypeScript checks.
