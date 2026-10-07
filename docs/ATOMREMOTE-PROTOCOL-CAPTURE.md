# AtomRemote command protocol capture

The native Android app can now establish and recover the Android BLE/GATT link more reliably, but the actual AtomRemote command bytes are proprietary and are not published in the public Atomos setup/user documentation reviewed for this project.

## Fastest path to make Record / Stop / Play / monitor tools live

Use the official **ATOMOS - Remote** app on a Mac with the same recorder and capture the Mac's own Bluetooth HCI traffic.

1. Put the Atomos recorder in `Sync Config > Bluetooth / AirGlu BT > Pair`.
2. On the Mac, install Apple's **Additional Tools for Xcode** and open **PacketLogger**.
3. Start a Bluetooth HCI capture in PacketLogger.
4. Open the official ATOMOS - Remote app on the Mac and connect to the recorder.
5. Perform one action at a time, leaving a few seconds between actions:
   - Record Start
   - Record Stop
   - Play
   - Pause
   - Waveform On / Off
   - Zebra On / Off
   - Focus Peaking On / Off
   - False Color On / Off
   - 1:1 Zoom On / Off
   - Frame Guides On / Off
6. Stop the capture and save/export it.
7. Also export this Android app's `Protocol Capture` JSON after connecting to the same recorder.
8. Provide both captures for mapping.

The mapping target is:
- pairing/control service UUID
- command characteristic UUID
- notify/status characteristic UUID
- write-with-response behavior
- exact payload for each remote action

Once those exact writes are mapped, place them into the app's validated Protocol Profile. The current control engine will then transmit them through the already-built native GATT layer.

Do not guess command payloads. A BLE connection being established does not reveal the recorder's proprietary application protocol by itself.
