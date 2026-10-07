# Native Pairing Fix 16.8

This patch is based on the user's uploaded `native-android.zip` (0.16.7-native).

## What changed

1. Removes the need to expect an Android OS pairing popup. AtomRemote's documented confirmation is an application-level 9-digit Pairing ID.
2. Captures the full BLE advertising record and only accepts an exact ASCII `NNN-NNN-NNN`/9-digit Pairing ID. Arbitrary binary-to-decimal guesses are not treated as pairing codes.
3. Adds the documented AirGlu S1C pairing start sequence after a real S1C channel is confirmed:
   - `#BTPR=1` start pairing search
   - poll `#BTPR?`
   - when `BTPR=2,<Device>` appears, use the existing explicit COMPLETE AIRGLU PAIRING action (`#BTPR=2`).
4. Adds a stale Android bond repair path. Earlier experimental builds could create an Android bond; if the recorder was later `Unpair All`'d, the phone may still retain stale keys.
5. Adds an 8-second BLE connection timeout so irrelevant unnamed advertisements do not stay on CONNECTING forever.

## Important

The public AirGlu2 S1C API is not proof that the private AtomRemote Pairing-ID handshake uses the same channel. This build only sends `BTPR=1` after a genuine S1C response such as `STVS=` confirms the channel.

If the official AtomRemote app shows a Pairing ID but this build still does not, capture the actual recorder Pairing ID and the GATT Inspector / protocol capture. That comparison is required to map the proprietary AtomRemote handshake rather than inventing payloads.
