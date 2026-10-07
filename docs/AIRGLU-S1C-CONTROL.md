# ATOMOS Remote Native 15.3 - AirGlu S1C control path

This patch separates three states that were previously conflated:

1. Android BLE/GATT linked
2. AirGlu/S1C control channel detected
3. ATOMOS remote control ready

A BLE GATT connection alone is not treated as a completed ATOMOS remote session.

## Runtime S1C discovery

For ATOMOS/AirGlu-looking BLE devices such as AG/AG2 candidates, the app waits for GATT service discovery and then probes writable characteristics with the documented, non-mutating S1C query:

`#GCMD?\n`

A channel is accepted only if the device returns an S1C response such as `GCMD=`. No hard-coded vendor GATT UUID is assumed.

## Documented S1C commands enabled when the channel is detected

- Query recorder/model: `#GCMD?`
- Firmware: `#GCVR?`
- Battery: `#GCBT?`
- Media: `#GCDV?`
- Timecode: `#TCTM?`
- Transport state: `#GCCT?`
- Record: `#GCCT=2`
- Stop: `#GCCT=1`
- Playback: `#GCCT=3`
- Pause: `#GCCT=4`
- Pairing state: `#BTPR?`
- Paired-device status: `#BTST?`
- Explicit AirGlu connect/pair request: `#BTPR=2`

All lines are terminated with LF as required by the S1C API.

## Monitor controls

Waveform, zebra, peaking, false color, zoom and frame guides remain gated by a validated ATOMOS protocol profile. Those exact monitor-control commands are not defined by the public generic S1C control section used in this patch.
