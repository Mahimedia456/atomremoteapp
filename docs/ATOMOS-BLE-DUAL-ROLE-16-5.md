# AG2 / AirGlu BT / AtomRemote role correction

Evidence used for 16.5:

- ATOMOS AtomRemote setup instructions say to enter **Pairing Mode in the Bluetooth section of Sync Config**, compare the Pairing ID, accept it in AtomRemote, then enable Remote mode.
- ATOMOS Shogun Ultra specifications list **Bluetooth LE via AtomRemote App** for remote control.
- ATOMOS also describes **AirGlu BT** as third-party sync/app-control Bluetooth functionality.
- Nikon's ATOMOS AirGlu BT documentation demonstrates the third-party timecode-sync role of the same AirGlu Bluetooth ecosystem.

Conclusion: AG2/FCC1 is definitely AirGlu-related, but excluding it from AtomRemote discovery is not justified. 16.5 treats it as a dual-role candidate and performs a fast, non-destructive pairing discovery while Pair mode is active.
