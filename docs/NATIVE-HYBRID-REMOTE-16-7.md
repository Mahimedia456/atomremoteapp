# ATOMOS Remote Native Hybrid Remote 16.7

## What changed

- Same-LAN Shogun discovery can now launch the Bluetooth remote-pairing flow directly.
- Home and Network screens show **PAIR REMOTE** for an ATOMOS LAN candidate.
- The Connect screen keeps the Wi-Fi-discovered Shogun identity/IP visible while BLE scanning runs.
- AG/AG2/FCC1 remains a Bluetooth pairing/control candidate instead of being discarded as sync-only.
- AirGlu S1C probing now tries both documented LF and CRLF command termination.
- Writable characteristics that support both write-with-response and write-without-response are tested both ways.
- Pairing/control probing checks the documented `BTPR?`, `GCMD?`, `GCCT?` and `STVS?` queries.
- REC / STOP / PLAY / PAUSE unlock only when an actual `GCMD=` or `GCCT=` reply confirms a recorder-control channel.

## Important protocol boundary

The Shogun Ultra being visible at an IP address does not by itself provide an IP remote-control API. Public ATOMOS documentation lists AtomRemote recorder control as Bluetooth LE. The ATOMOS Sync Network Record Control feature is part of the AirGlu sync/gang-control system, not a documented phone-to-recorder Wi-Fi REST API.

The discovered `_ssh._tcp` service / port 22 is treated only as device/network evidence. The app does not attempt SSH login or send guessed network commands.

## Test flow

1. Put phone and Shogun on the same Wi-Fi.
2. On Home or Network Discovery, wait for the Shogun LAN candidate.
3. Tap **PAIR REMOTE** on the Shogun card.
4. On Shogun: Sync Config > Bluetooth / AirGlu BT > Pair.
5. On the BLE list select AG2 / ATOMOS candidate.
6. The app runs fast Pairing ID + S1C discovery.
7. If a documented `GCMD=` or `GCCT=` reply is confirmed, **OPEN REMOTE** unlocks and transport control is enabled.
