# ATOMOS Remote - Final Native Compatibility Matrix

This matrix describes the app architecture and known transport requirements. A listed transport does not imply that proprietary remote-control packets are publicly documented.

| Device profile | BLE discovery | Native GATT | Wi-Fi/LAN discovery | AirGlu boundary | NDI source discovery | Remote writes |
|---|---:|---:|---:|---:|---:|---:|
| Shogun Ultra | Yes | Yes | Yes | Yes | Yes | Requires validated protocol profile |
| Shogun | Yes | Yes | Yes | Yes | Yes | Requires validated protocol profile |
| Shogun CONNECT | Hardware/firmware validation | Yes when BLE exposed | Yes | Yes | Network discovery supported | Requires validated protocol profile |
| Ninja Ultra + ATOMOS Connect | Accessory dependent | Yes when BLE exposed | Accessory dependent | Accessory dependent | Network discovery supported | Requires validated protocol profile |
| Ninja + ATOMOS Connect | Accessory dependent | Yes when BLE exposed | Accessory dependent | Accessory dependent | Network discovery supported | Requires validated protocol profile |
| Ninja V / V+ + AtomX SYNC | Accessory dependent | Yes when BLE exposed | Not assumed | Accessory dependent | Not assumed | Requires validated protocol profile |
| Ninja V / V+ + ATOMOS Connect | Hardware/firmware validation | Yes when BLE exposed | Accessory dependent | Accessory dependent | Network discovery supported | Requires validated protocol profile |
| NEON | Validate by model/firmware | Yes when BLE exposed | Ethernet discovery possible | Not assumed | Network discovery possible | Requires validated protocol profile |
| Generic ATOMOS | Raw discovery only | Yes when BLE exposed | Raw local service discovery | Locked | Raw NDI discovery | Locked until validated |

## Important boundaries

- Bluetooth discovery and GATT inspection are implemented directly with Android APIs.
- Wi-Fi/LAN discovery uses Android NSD/mDNS/DNS-SD and TCP diagnostics. It does not invent an undocumented Atomos HTTP remote API.
- NDI sources can be discovered, but live video decoding requires a separately integrated/licensed native NDI SDK.
- AirGlu workflow state is modeled, but RF transmission remains behind an integration boundary until an official SDK or validated protocol is available.
- Record, stop, playback, monitoring and advanced setting writes are only enabled when a protocol profile is explicitly marked validated and contains the relevant payload.
