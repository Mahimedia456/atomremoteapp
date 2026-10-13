package com.atomosremote.nativeapp.network

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * WL-5.0: bounded DNS-SD service-type enumeration for the already-existing Live Feed path.
 *
 * This does not scan TCP/UDP ports and does not connect to the recorder. It only asks the
 * standard mDNS/DNS-SD service-enumeration question and resolves service records that devices
 * voluntarily advertise on the LAN.
 */
internal object MdnsServiceEnumerator {
    private const val MDNS_PORT = 5353
    private const val TYPE_A = 1
    private const val TYPE_PTR = 12
    private const val TYPE_TXT = 16
    private const val TYPE_SRV = 33
    private const val CLASS_IN = 1
    private const val MAX_PACKET = 9000
    private const val ENUM_WINDOW_MS = 900L
    private const val TYPE_WINDOW_MS = 450L
    private const val MAX_TYPES = 24

    data class Result(
        val services: List<NetworkServiceCandidate>,
        val discoveredTypes: List<String>,
        val packetCount: Int
    )

    private data class DnsRecord(
        val name: String,
        val type: Int,
        val clazz: Int,
        val ttl: Long,
        val packet: ByteArray,
        val rdataOffset: Int,
        val rdataLength: Int
    )

    fun enumerate(): Result {
        val group = InetAddress.getByName("224.0.0.251")
        val records = mutableListOf<DnsRecord>()
        var packetCount = 0

        val socket = MulticastSocket(null)
        return try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(MDNS_PORT))
            socket.timeToLive = 1
            runCatching { socket.joinGroup(group) }

            sendQuestion(socket, "_services._dns-sd._udp.local", TYPE_PTR)
            packetCount += receiveWindow(socket, ENUM_WINDOW_MS, records)

            val types = ptrTargets(records)
                .map(::normalizeServiceType)
                .filter { it.endsWith("._tcp.local") || it.endsWith("._udp.local") }
                .distinct()
                .take(MAX_TYPES)

            types.forEach { type ->
                sendQuestion(socket, type, TYPE_PTR)
                packetCount += receiveWindow(socket, TYPE_WINDOW_MS, records)
            }

            val services = buildCandidates(records, types)
            Result(services = services, discoveredTypes = types, packetCount = packetCount)
        } finally {
            runCatching { socket.leaveGroup(group) }
            socket.close()
        }
    }

    private fun sendQuestion(socket: MulticastSocket, name: String, type: Int) {
        val question = buildQuery(name, type)
        socket.send(DatagramPacket(question, question.size, InetAddress.getByName("224.0.0.251"), MDNS_PORT))
    }

    private fun receiveWindow(socket: MulticastSocket, windowMs: Long, out: MutableList<DnsRecord>): Int {
        val deadline = System.currentTimeMillis() + windowMs
        var packets = 0
        while (System.currentTimeMillis() < deadline) {
            socket.soTimeout = min(180L, (deadline - System.currentTimeMillis()).coerceAtLeast(1L)).toInt()
            val buf = ByteArray(MAX_PACKET)
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                continue
            }
            packets += 1
            val bytes = packet.data.copyOf(packet.length)
            out += parseRecords(bytes)
        }
        return packets
    }

    private fun buildQuery(name: String, type: Int): ByteArray {
        val body = mutableListOf<Byte>()
        name.trimEnd('.').split('.').forEach { label ->
            val encoded = label.toByteArray(Charsets.UTF_8)
            body += encoded.size.toByte()
            encoded.forEach(body::add)
        }
        body += 0.toByte()
        body += ((type ushr 8) and 0xff).toByte()
        body += (type and 0xff).toByte()
        body += 0.toByte()
        body += CLASS_IN.toByte()

        val header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(0.toShort()) // id
            putShort(0.toShort()) // flags
            putShort(1.toShort()) // qdcount
            putShort(0.toShort()) // ancount
            putShort(0.toShort()) // nscount
            putShort(0.toShort()) // arcount
        }.array()
        return header + body.toByteArray()
    }

    private fun parseRecords(packet: ByteArray): List<DnsRecord> {
        if (packet.size < 12) return emptyList()
        fun u16(offset: Int): Int = if (offset + 1 < packet.size) {
            ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)
        } else 0
        fun u32(offset: Int): Long = if (offset + 3 < packet.size) {
            ((packet[offset].toLong() and 0xff) shl 24) or
                ((packet[offset + 1].toLong() and 0xff) shl 16) or
                ((packet[offset + 2].toLong() and 0xff) shl 8) or
                (packet[offset + 3].toLong() and 0xff)
        } else 0L

        val qd = u16(4)
        val an = u16(6)
        val ns = u16(8)
        val ar = u16(10)
        var cursor = 12

        repeat(qd) {
            val name = readName(packet, cursor) ?: return@repeat
            cursor = name.second + 4
            if (cursor > packet.size) return emptyList()
        }

        val records = mutableListOf<DnsRecord>()
        repeat(an + ns + ar) {
            val name = readName(packet, cursor) ?: return@repeat
            cursor = name.second
            if (cursor + 10 > packet.size) return@repeat
            val type = u16(cursor)
            val clazz = u16(cursor + 2)
            val ttl = u32(cursor + 4)
            val rdLength = u16(cursor + 8)
            val rdOffset = cursor + 10
            if (rdOffset + rdLength > packet.size) return@repeat
            records += DnsRecord(name.first, type, clazz, ttl, packet, rdOffset, rdLength)
            cursor = rdOffset + rdLength
        }
        return records
    }

    private fun readName(packet: ByteArray, start: Int): Pair<String, Int>? {
        if (start !in packet.indices) return null
        val labels = mutableListOf<String>()
        var cursor = start
        var next = -1
        var hops = 0
        val seen = mutableSetOf<Int>()

        while (cursor in packet.indices && hops++ < 40) {
            if (!seen.add(cursor)) return null
            val len = packet[cursor].toInt() and 0xff
            when {
                len == 0 -> {
                    if (next < 0) next = cursor + 1
                    return labels.joinToString(".") to next
                }
                (len and 0xc0) == 0xc0 -> {
                    if (cursor + 1 >= packet.size) return null
                    val pointer = ((len and 0x3f) shl 8) or (packet[cursor + 1].toInt() and 0xff)
                    if (next < 0) next = cursor + 2
                    cursor = pointer
                }
                len and 0xc0 != 0 -> return null
                else -> {
                    val from = cursor + 1
                    val to = from + len
                    if (to > packet.size) return null
                    labels += String(packet, from, len, Charsets.UTF_8)
                    cursor = to
                }
            }
        }
        return null
    }

    private fun ptrTargets(records: List<DnsRecord>): List<String> = records
        .asSequence()
        .filter { it.type == TYPE_PTR }
        .mapNotNull { readName(it.packet, it.rdataOffset)?.first }
        .toList()

    private fun normalizeServiceType(value: String): String = value.trimEnd('.').lowercase()

    private fun buildCandidates(records: List<DnsRecord>, serviceTypes: List<String>): List<NetworkServiceCandidate> {
        val ptrs = records.filter { it.type == TYPE_PTR }.mapNotNull { r ->
            val target = readName(r.packet, r.rdataOffset)?.first ?: return@mapNotNull null
            Triple(normalizeServiceType(r.name), target, r)
        }
        val srvByName = records.filter { it.type == TYPE_SRV }.groupBy { it.name.lowercase() }
        val txtByName = records.filter { it.type == TYPE_TXT }.groupBy { it.name.lowercase() }
        val aByName = records.filter { it.type == TYPE_A }.groupBy { it.name.lowercase() }

        val out = linkedMapOf<String, NetworkServiceCandidate>()
        serviceTypes.forEach { type ->
            ptrs.filter { it.first == type }.forEach { (_, instance, _) ->
                val srv = srvByName[instance.lowercase()]?.firstOrNull()
                val srvData = srv?.let(::parseSrv)
                val targetHost = srvData?.second
                val hostAddress = targetHost?.let { hostname ->
                    aByName[hostname.lowercase()]?.firstNotNullOfOrNull(::parseA)
                }
                val attrs = txtByName[instance.lowercase()].orEmpty()
                    .flatMap(::parseTxt)
                    .associate { entry ->
                        val idx = entry.indexOf('=')
                        if (idx > 0) entry.substring(0, idx) to entry.substring(idx + 1) else entry to ""
                    }
                val displayName = instance.removeSuffix(".$type").ifBlank { instance }
                val key = "$type|$instance"
                out[key] = NetworkServiceCandidate(
                    key = key,
                    serviceName = displayName,
                    serviceType = if (type.endsWith('.')) type else "$type.",
                    host = hostAddress,
                    port = srvData?.first,
                    attributes = attrs,
                    resolved = hostAddress != null
                )
            }
        }
        return out.values.toList()
    }

    private fun parseSrv(record: DnsRecord): Pair<Int, String>? {
        if (record.rdataLength < 7) return null
        val p = record.packet
        val o = record.rdataOffset
        val port = ((p[o + 4].toInt() and 0xff) shl 8) or (p[o + 5].toInt() and 0xff)
        val target = readName(p, o + 6)?.first ?: return null
        return port to target
    }

    private fun parseA(record: DnsRecord): String? {
        if (record.rdataLength != 4) return null
        return (0 until 4).joinToString(".") { i -> (record.packet[record.rdataOffset + i].toInt() and 0xff).toString() }
    }

    private fun parseTxt(record: DnsRecord): List<String> {
        val out = mutableListOf<String>()
        var cursor = record.rdataOffset
        val end = record.rdataOffset + record.rdataLength
        while (cursor < end) {
            val len = record.packet[cursor].toInt() and 0xff
            cursor += 1
            if (cursor + len > end) break
            out += String(record.packet, cursor, len, Charsets.UTF_8)
            cursor += len
        }
        return out
    }
}
