# Native Android Phases 09-13

## Phase 09 - Shogun family
Model-aware capability definitions for Shogun Ultra, Shogun and Shogun CONNECT. The matrix deliberately distinguishes built-in capabilities from firmware/hardware behavior that still requires validation.

## Phase 10 - Ninja family
Accessory-aware profiles for current Ninja/Ninja Ultra with ATOMOS Connect and Ninja V/V+ with AtomX SYNC or ATOMOS Connect.

## Phase 11 - LAN discovery
Android NsdManager based DNS-SD/mDNS discovery plus manual host/port probing. `_ndi._tcp.` is included because NDI uses that Bonjour/DNS-SD service type. Network discovery does not fabricate an Atomos HTTP control endpoint.

## Phase 12 - Multi-device
Native multi-GATT controller with one GattClient per saved recorder. Connect All and command orchestration are provided. Record All / Stop All still require a validated command profile.

## Phase 13 - AirGlu boundary
AirGlu RF workflow configuration and a safe bridge interface. RF transmission stays disabled until Atomos provides/validates a usable native SDK or protocol.

## BLE interoperability repair
The manifest no longer uses `android:usesPermissionFlags="neverForLocation"`. Android documentation warns that this assertion can restrict the types of Bluetooth devices an app can interact with. For maximum recorder discovery across Android OEMs, the connection screen requests Nearby Devices and Fine Location at runtime, without a separate permission-management screen.
