# ATOMOS Remote Native 16.8 - Pairing Discovery Upgrade

This build is based on the uploaded native Android project and focuses only on the missing AtomRemote BLE pairing evidence.

## Source audit findings

The previous scanner kept device name, MAC, RSSI, service UUIDs and only the first manufacturer-data block. It discarded BLE service-data and the complete raw advertising record. The previous GATT Pairing-ID detector also decoded only UTF-8 text. In addition, repeated Pair-mode tests can leave Android with a cached GATT database even when the recorder exposes a temporary pairing service.

## 16.8 changes

- Capture complete BLE advertising record.
- Capture every manufacturer-specific data block and its company ID.
- Capture every BLE service-data block.
- Preserve pairing evidence across repeated advertisements from the same device.
- Detect human-readable 9-digit Pairing IDs in ASCII/UTF-8/UTF-16LE/UTF-16BE.
- Show conservative binary Pairing-ID candidates for comparison only. Binary candidates are never accepted automatically.
- Request high BLE connection priority and a larger MTU.
- Best-effort refresh of Android's hidden GATT cache before Pair-mode service discovery.
- Rediscover services when Android reports a GATT service-database change.
- Expand bounded vendor notification/read discovery while keeping operations serialized and timed out.
- Export raw advertising metadata, service signature, candidate pairing IDs and GATT events in protocol capture JSON.

## Important protocol boundary

ATOMOS publicly documents the Pairing-ID user flow and remote-control features, but it does not publish the low-level AtomRemote Pairing-ID characteristic, Accept-Code write payload, or Record/Stop command packets. This build does not invent those values.

If 16.8 displays the same Pairing ID as the recorder, the BLE discovery side is solved. The next remaining item is the exact validated Accept-Code transaction.
