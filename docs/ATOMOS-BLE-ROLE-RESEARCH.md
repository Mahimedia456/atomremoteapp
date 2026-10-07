# ATOMOS BLE role research

## Key conclusion

Do not treat every `AG2` / AirGlu Bluetooth advertisement as the AtomRemote recorder-control endpoint.

### Nikon evidence

Nikon's Z50II documentation describes **ATOMOS AirGlu BT options** as Bluetooth pairing to an **UltraSync BLUE** for wireless timecode synchronization. It includes save/delete pairing information and receiving timecode. This is a sync workflow, not an AtomRemote record/playback UI.

Reference:
https://onlinemanual.nikonimglib.com/z50II/en/09-09-09.html

### ATOMOS evidence

ATOMOS documents three related but distinct roles on Shogun Ultra:

1. **Bluetooth LE via AtomRemote App** for recorder remote control.
2. **AirGlu BT** for third-party sync / local Bluetooth integration.
3. **AirGlu RF** for long-range sync and gang control.

Reference:
https://www.atomos.com/explore/shogun-ultra/
https://www.atomos.com/tech-specs/shogun-ultra/

ATOMOS documents AtomRemote as capable of remote recording and playback, plus recorder settings and monitoring tools.

Reference:
https://support.atomos.com/hc/en-us/articles/5692962169103-The-AtomRemote-App
https://support.atomos.com/hc/en-us/articles/5693005870479-AtomRemote-Using-the-app

## Control target for this project

Both applications should ultimately implement:

- Record
- Stop
- Play
- Pause
- Recorder status / media / battery / timecode
- Later: waveform, peaking, zebra, false color, zoom and other AtomRemote controls

The primary target for those controls is the **AtomRemote BLE application session**, not merely an Android/iOS OS bond and not merely an AirGlu timecode pairing.

## What the AirGlu2 S1C manual gives us

The generic AirGlu2 S1C API documents transport commands such as `GCCT?` and `GCCT=n` for attached-device control. This remains useful when a recorder genuinely exposes that S1C control channel, but it should not be assumed to be the same protocol used by the official AtomRemote pairing-ID workflow.

## Required protocol capture

Public ATOMOS documentation explains the user-level Pairing ID flow but does not publish the low-level GATT UUIDs and payloads for the official AtomRemote session.

Therefore both native Android and Expo/iOS should share one validated protocol map once captured:

- AtomRemote pairing service UUID
- pairing-ID characteristic UUID
- accept-pairing characteristic / payload
- control service UUID
- command characteristic UUID
- notification/status UUID
- Record / Stop / Play / Pause payloads

Until these values are validated, the app must not invent recorder-control writes.
