package com.atomosremote.nativeapp.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** A DNS-SD/mDNS service advertised by a device on the current LAN. */
data class NetworkServiceCandidate(
    val key: String,
    val serviceName: String,
    val serviceType: String,
    val host: String? = null,
    val port: Int? = null,
    val attributes: Map<String, String> = emptyMap(),
    val resolved: Boolean = false
)

/** A host seen on the current Wi-Fi/Ethernet LAN, even when it advertises no mDNS service. */
data class LanDeviceCandidate(
    val host: String,
    val displayName: String,
    val sources: Set<String> = emptySet(),
    val openPorts: Set<Int> = emptySet(),
    val serviceTypes: Set<String> = emptySet(),
    val likelyAtomos: Boolean = false,
    val evidence: String = "LAN host",
    val lastSeenMs: Long = System.currentTimeMillis()
)

data class NetworkStatus(
    val connected: Boolean = false,
    val transport: String = "offline",
    val localAddresses: List<String> = emptyList(),
    val message: String = "idle"
)

data class PortProbeResult(
    val host: String,
    val port: Int,
    val reachable: Boolean,
    val latencyMs: Long,
    val message: String
)

class NetworkDiscovery(private val context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
    private val found = ConcurrentHashMap<String, NetworkServiceCandidate>()
    private val hosts = ConcurrentHashMap<String, LanDeviceCandidate>()
    private var scanJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _services = MutableStateFlow<List<NetworkServiceCandidate>>(emptyList())
    val services: StateFlow<List<NetworkServiceCandidate>> = _services

    private val _lanDevices = MutableStateFlow<List<LanDeviceCandidate>>(emptyList())
    val lanDevices: StateFlow<List<LanDeviceCandidate>> = _lanDevices

    private val _status = MutableStateFlow(currentStatus("idle"))
    val status: StateFlow<NetworkStatus> = _status

    companion object {
        // We browse service classes that are useful for ATOMOS/NDI/network-device discovery.
        // NSD cannot wildcard all service types, so same-subnet discovery below complements this list.
        val DEFAULT_SERVICE_TYPES = listOf(
            "_ndi._tcp.",
            "_http._tcp.",
            "_https._tcp.",
            "_rtsp._tcp.",
            "_ftp._tcp.",
            "_ssh._tcp.",
            "_workstation._tcp."
        )
        val DEFAULT_PROBE_PORTS = listOf(80, 443, 554, 5960, 22, 8000, 8080)
        private val QUICK_SCAN_PORTS = listOf(80, 443, 554, 5960, 22, 8080)
    }

    fun refreshStatus(message: String = _status.value.message) {
        _status.value = currentStatus(message)
    }

    /**
     * Starts all safe same-LAN discovery paths in parallel:
     * 1) DNS-SD/mDNS (including NDI), 2) SSDP, 3) neighbour cache, 4) bounded /24 host sweep.
     * Device discovery does not imply a documented ATOMOS IP-control API.
     */
    @Suppress("DEPRECATION")
    fun start(serviceTypes: List<String> = DEFAULT_SERVICE_TYPES) {
        stopInternal(updateStatus = false)
        found.clear()
        hosts.clear()
        _services.value = emptyList()
        _lanDevices.value = emptyList()
        if (!localLanTransportAvailable()) {
            _status.value = currentStatus("Connect the phone to the same Wi-Fi/Ethernet network as the recorder")
            return
        }
        _status.value = currentStatus("discovering same-LAN devices")
        acquireMulticastLock()

        serviceTypes.distinct().forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    _status.value = currentStatus("mDNS + LAN scan running")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    val key = "${serviceInfo.serviceType}|${serviceInfo.serviceName}"
                    found[key] = NetworkServiceCandidate(
                        key = key,
                        serviceName = serviceInfo.serviceName,
                        serviceType = serviceInfo.serviceType
                    )
                    publishServices()
                    resolve(serviceInfo, key)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val key = "${serviceInfo.serviceType}|${serviceInfo.serviceName}"
                    found.remove(key)
                    publishServices()
                }

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    _status.value = currentStatus("NSD failed for $serviceType ($errorCode); LAN scan continues")
                    runCatching { nsd.stopServiceDiscovery(this) }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            listeners[type] = listener
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
        }

        scanJob = scope.launch {
            launch { discoverSsdp() }
            launch { ingestArpNeighbours() }
            launch { boundedSubnetSweep() }
        }
    }

    fun stop() = stopInternal(updateStatus = true)

    private fun stopInternal(updateStatus: Boolean) {
        listeners.values.toList().forEach { listener -> runCatching { nsd.stopServiceDiscovery(listener) } }
        listeners.clear()
        scanJob?.cancel()
        scanJob = null
        scope.coroutineContext.cancelChildren()
        runCatching { multicastLock?.release() }
        multicastLock = null
        if (updateStatus) _status.value = currentStatus("stopped")
    }

    private fun acquireMulticastLock() {
        multicastLock = runCatching {
            wifi.createMulticastLock("atomos-remote-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun resolve(serviceInfo: NsdServiceInfo, key: String) {
        runCatching {
            nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    val current = found[key] ?: return
                    found[key] = current.copy(resolved = false)
                    publishServices()
                }

                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    val attrs = runCatching {
                        resolvedInfo.attributes.mapValues { (_, value) -> value.toString(Charsets.UTF_8) }
                    }.getOrDefault(emptyMap())
                    val host = runCatching { resolvedInfo.host?.hostAddress }.getOrNull()
                    val candidate = NetworkServiceCandidate(
                        key = key,
                        serviceName = resolvedInfo.serviceName,
                        serviceType = resolvedInfo.serviceType,
                        host = host,
                        port = resolvedInfo.port.takeIf { it > 0 },
                        attributes = attrs,
                        resolved = host != null
                    )
                    found[key] = candidate
                    publishServices()
                    if (host != null) {
                        mergeHost(
                            host = host,
                            displayName = resolvedInfo.serviceName,
                            source = "mDNS",
                            ports = candidate.port?.let(::setOf).orEmpty(),
                            serviceTypes = setOf(resolvedInfo.serviceType),
                            textEvidence = buildString {
                                append(resolvedInfo.serviceName)
                                append(' ')
                                append(resolvedInfo.serviceType)
                                append(' ')
                                attrs.forEach { (k, v) -> append(k).append('=').append(v).append(' ') }
                            }
                        )
                    }
                }
            })
        }
    }

    private suspend fun discoverSsdp() = withContext(Dispatchers.IO) {
        val request = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: ssdp:all\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 350
                val target = InetAddress.getByName("239.255.255.250")
                socket.send(DatagramPacket(request, request.size, target, 1900))
                val deadline = System.currentTimeMillis() + 1800L
                while (System.currentTimeMillis() < deadline) {
                    val buffer = ByteArray(4096)
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: Exception) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val headers = text.lineSequence().mapNotNull { line ->
                        val idx = line.indexOf(':')
                        if (idx <= 0) null else line.substring(0, idx).trim().uppercase() to line.substring(idx + 1).trim()
                    }.toMap()
                    val host = headers["LOCATION"]?.let { runCatching { URI(it).host }.getOrNull() }
                        ?: packet.address?.hostAddress
                        ?: continue
                    val label = headers["SERVER"] ?: headers["USN"] ?: "SSDP device"
                    mergeHost(
                        host = host,
                        displayName = label.take(80),
                        source = "SSDP",
                        ports = headers["LOCATION"]?.let { runCatching { URI(it).port }.getOrNull() }?.takeIf { it > 0 }?.let(::setOf).orEmpty(),
                        serviceTypes = setOfNotNull(headers["ST"]),
                        textEvidence = "$label ${headers["ST"].orEmpty()} ${headers["USN"].orEmpty()}"
                    )
                }
            }
        }
    }

    private suspend fun ingestArpNeighbours() = withContext(Dispatchers.IO) {
        runCatching {
            val file = File("/proc/net/arp")
            if (!file.canRead()) return@runCatching
            file.readLines().drop(1).forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                val ip = parts.getOrNull(0) ?: return@forEach
                val mac = parts.getOrNull(3).orEmpty()
                if (isIpv4(ip) && mac != "00:00:00:00:00:00") {
                    mergeHost(ip, "LAN device", "ARP", emptySet(), emptySet(), "Neighbour $mac")
                }
            }
        }
    }

    /**
     * Bounded scan: at most the local /24. This keeps the operation finite even on /16 enterprise networks.
     * It is supplemental to mDNS/SSDP and uses short TCP probes, not an assumption about an ATOMOS API.
     */
    private suspend fun boundedSubnetSweep() = withContext(Dispatchers.IO) {
        val local = activeIpv4() ?: return@withContext
        val octets = local.split('.')
        if (octets.size != 4) return@withContext
        val prefix = "${octets[0]}.${octets[1]}.${octets[2]}."
        val semaphore = Semaphore(36)
        val jobs = (1..254).map { last ->
            async {
                semaphore.withPermit {
                    val host = "$prefix$last"
                    if (host == local) return@withPermit
                    val open = quickOpenPorts(host)
                    val reachable = if (open.isEmpty()) runCatching { InetAddress.getByName(host).isReachable(180) }.getOrDefault(false) else true
                    if (reachable || open.isNotEmpty()) {
                        val reverse = runCatching {
                            InetAddress.getByName(host).canonicalHostName.takeIf { it != host }
                        }.getOrNull()
                        mergeHost(
                            host = host,
                            displayName = reverse ?: "LAN device",
                            source = "subnet",
                            ports = open,
                            serviceTypes = emptySet(),
                            textEvidence = buildString {
                                reverse?.let { append(it).append(' ') }
                                if (open.isNotEmpty()) append("open ports ${open.sorted().joinToString()}")
                            }
                        )
                    }
                }
            }
        }
        jobs.awaitAll()
        _status.value = currentStatus("same-LAN scan complete: ${hosts.size} device(s)")
    }

    private fun quickOpenPorts(host: String): Set<Int> {
        val open = linkedSetOf<Int>()
        QUICK_SCAN_PORTS.forEach { port ->
            val ok = runCatching {
                Socket().use { socket -> socket.connect(InetSocketAddress(host, port), 170) }
            }.isSuccess
            if (ok) open += port
        }
        return open
    }

    suspend fun probeHost(host: String, ports: List<Int> = DEFAULT_PROBE_PORTS): List<PortProbeResult> = withContext(Dispatchers.IO) {
        ports.distinct().filter { it in 1..65535 }.map { port ->
            val start = System.nanoTime()
            val result = runCatching {
                Socket().use { socket -> socket.connect(InetSocketAddress(host, port), 1400) }
            }
            val elapsed = (System.nanoTime() - start) / 1_000_000
            PortProbeResult(host, port, result.isSuccess, elapsed, result.exceptionOrNull()?.message ?: "TCP connection accepted")
        }
    }

    private fun mergeHost(
        host: String,
        displayName: String,
        source: String,
        ports: Set<Int>,
        serviceTypes: Set<String>,
        textEvidence: String
    ) {
        if (!isIpv4(host) && host.contains(':')) return
        val evidenceText = "$displayName $textEvidence ${serviceTypes.joinToString()}".lowercase()
        val likely = listOf("atomos", "shogun", "ninja", "neon", "atomremote").any(evidenceText::contains)
        hosts.compute(host) { _, old ->
            LanDeviceCandidate(
                host = host,
                displayName = chooseName(old?.displayName, displayName, host),
                sources = old?.sources.orEmpty() + source,
                openPorts = old?.openPorts.orEmpty() + ports,
                serviceTypes = old?.serviceTypes.orEmpty() + serviceTypes,
                likelyAtomos = old?.likelyAtomos == true || likely,
                evidence = listOfNotNull(old?.evidence, textEvidence.takeIf { it.isNotBlank() }).distinct().joinToString(" • ").take(180),
                lastSeenMs = System.currentTimeMillis()
            )
        }
        publishHosts()
    }

    private fun chooseName(old: String?, newer: String, host: String): String {
        val weak = setOf("LAN device", "SSDP device", host)
        return when {
            old == null -> newer
            old in weak && newer !in weak -> newer
            else -> old
        }
    }

    private fun publishServices() {
        _services.value = found.values.sortedWith(
            compareByDescending<NetworkServiceCandidate> { it.serviceType.contains("ndi", ignoreCase = true) }
                .thenBy { it.serviceName.lowercase() }
        )
    }

    private fun publishHosts() {
        _lanDevices.value = hosts.values.sortedWith(
            compareByDescending<LanDeviceCandidate> { it.likelyAtomos }
                .thenByDescending { it.sources.contains("mDNS") }
                .thenBy { ipv4SortKey(it.host) }
        )
    }

    private fun currentStatus(message: String): NetworkStatus {
        val network = connectivity.activeNetwork
        val caps = network?.let(connectivity::getNetworkCapabilities)
        val lp = network?.let(connectivity::getLinkProperties)
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
            else -> if (network != null) "Other" else "offline"
        }
        return NetworkStatus(
            connected = network != null,
            transport = transport,
            localAddresses = lp?.linkAddresses?.mapNotNull { it.address.hostAddress }?.distinct().orEmpty(),
            message = message
        )
    }

    private fun activeIpv4(): String? {
        val network = connectivity.activeNetwork ?: return null
        return connectivity.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.mapNotNull { it.hostAddress }
            ?.firstOrNull { !it.startsWith("127.") }
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }

    private fun localLanTransportAvailable(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun ipv4SortKey(host: String): Long {
        val p = host.split('.').mapNotNull { it.toLongOrNull() }
        if (p.size != 4) return Long.MAX_VALUE
        return (p[0] shl 24) + (p[1] shl 16) + (p[2] shl 8) + p[3]
    }
}
