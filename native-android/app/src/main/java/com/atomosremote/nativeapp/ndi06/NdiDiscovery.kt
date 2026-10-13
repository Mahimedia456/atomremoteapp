package com.atomosremote.nativeapp.ndi06

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.LinkedHashMap

/**
 * NDI-06A. Foreground-only Android NSD discovery of _ndi._tcp.
 * This performs ordinary DNS-SD/mDNS discovery (network queries); it NEVER
 * connects to an advertised port, transmits recorder control, or starts media.
 */
class NdiDiscovery(
    context: Context,
    private val onLog: (String) -> Unit,
    private val onUpdate: (List<NdiSource>) -> Unit
) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val services = LinkedHashMap<String, NdiSource>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var listener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var running = false
    private var generation = 0
    private var resolving = false

    fun start() {
        if (running) {
            onLog("Discovery already running")
            return
        }
        generation += 1
        services.clear()
        pending.clear()
        resolving = false
        onUpdate(emptyList())
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        try {
            multicastLock = wifi?.createMulticastLock("AtomRemote_NDI06_Discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            multicastLock = null
            onLog("Multicast lock unavailable: ${e.javaClass.simpleName}")
        }
        val callback = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                main.post { if (running) onLog("Discovery started: $regType") }
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    if (!running) return@post
                    if (!info.serviceType.contains("_ndi._tcp", ignoreCase = true)) return@post
                    onLog("Found advertisement: ${info.serviceName}")
                    pending.addLast(info)
                    resolveNext()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    if (!running) return@post
                    services.remove(info.serviceName)
                    onUpdate(services.values.toList())
                    onLog("Lost: ${info.serviceName}")
                }
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post {
                    onLog("Discovery start failed ($errorCode). Check Wi-Fi and local network permissions")
                    stop()
                }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { onLog("Stop callback ($errorCode)") }
            }
            override fun onDiscoveryStopped(serviceType: String) {
                main.post { onLog("Discovery stopped") }
            }
        }
        listener = callback
        running = true
        try {
            manager.discoverServices("_ndi._tcp.", NsdManager.PROTOCOL_DNS_SD, callback)
            onLog("Searching for NDI sources on local network (no media receiver)")
        } catch (e: Exception) {
            onLog("Discovery could not start: ${e.javaClass.simpleName}: ${e.message ?: ""}")
            stop()
        }
    }

    private fun resolveNext() {
        if (!running || resolving || pending.isEmpty()) return
        val info = pending.removeFirst()
        val currentGeneration = generation
        resolving = true
        try {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(result: NsdServiceInfo) {
                    main.post {
                        if (running && currentGeneration == generation) {
                            val values = LinkedHashMap<String, String>()
                            for ((key, bytes) in result.attributes) {
                                values[key] = String(bytes, StandardCharsets.UTF_8)
                            }
                            val source = NdiSource(
                                result.serviceName,
                                result.host?.hostAddress ?: "unresolved",
                                result.port,
                                values
                            )
                            services[source.name] = source
                            onUpdate(services.values.toList())
                            onLog("Resolved ${source.name} at ${source.host}:${source.advertisedPort}")
                        }
                        resolving = false
                        resolveNext()
                    }
                }
                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        if (running && currentGeneration == generation) {
                            onLog("Resolution failed: ${failed.serviceName}, code=$errorCode")
                        }
                        resolving = false
                        resolveNext()
                    }
                }
            })
        } catch (e: Exception) {
            resolving = false
            onLog("Resolve skipped: ${e.javaClass.simpleName}")
            resolveNext()
        }
    }

    fun stop() {
        if (!running && listener == null) return
        running = false
        generation += 1
        val previous = listener
        listener = null
        pending.clear()
        resolving = false
        if (previous != null) {
            try { manager.stopServiceDiscovery(previous) } catch (_: Exception) { }
        }
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) { }
        multicastLock = null
        onLog("Discovery idle")
    }

    fun isRunning(): Boolean = running
}
