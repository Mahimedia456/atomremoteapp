# Phase 03 Scope - Shogun / Shogun Ultra Driver Layer

## Included

- Model identification for Shogun and Shogun Ultra
- Capability registry separated from screen code
- Dedicated Shogun and Shogun Ultra drivers
- Unknown/family fallback drivers
- Device session state machine
- BLE control transport boundary
- Validated protocol environment profile
- Driver diagnostics screen
- Updated device controller screen using session/profile state
- Record/stop command contracts without guessed command bytes
- Telemetry contract without guessed binary decoding

## Protocol boundary

Phase 03 does not write guessed bytes to a recorder.

A control write is allowed only when both conditions are true:

1. Validated control service and command characteristic UUIDs are configured.
2. A validated command encoder has been bound to the BLE transport.

Until then, control actions return a clear protocol-pending result and transmit nothing.

## Next phase

Phase 04 binds validated Record / Stop / status mappings to these driver contracts, then adds live recorder state updates and command acknowledgement handling.
