# ATOMOS Remote Native Wi-Fi Discovery 16.6

This patch adds same-LAN discovery alongside BLE without pretending that discovery equals recorder control.

Discovery paths:
- Android NSD / DNS-SD / mDNS for `_ndi._tcp.`, HTTP/HTTPS, RTSP, FTP, SSH and workstation services.
- SSDP M-SEARCH discovery.
- ARP neighbour-cache hints where Android permits reading them.
- A bounded concurrent scan of the phone's current IPv4 /24 using short reachability and common service-port probes.

Home now automatically shows Same Wi-Fi / LAN candidates. Devices with names/TXT/SSDP evidence containing ATOMOS, Shogun, Ninja, NEON or AtomRemote are highlighted as ATOMOS candidates.

Important: Shogun Ultra has Wi-Fi/Ethernet/NDI networking, but public ATOMOS documentation describes AtomRemote control separately over Bluetooth LE. Therefore the app does not send guessed REC/STOP/PLAY/PAUSE packets over IP. Network control will only be enabled after a real ATOMOS service/API is validated.
