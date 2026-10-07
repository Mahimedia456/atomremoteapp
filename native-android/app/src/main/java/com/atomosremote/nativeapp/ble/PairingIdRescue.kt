package com.atomosremote.nativeapp.ble

import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/**
 * Pairing-ID recovery for fresh ATOMOS / SHOGUN pairing.
 *
 * Important runtime rules:
 * - Scan advertisements and scan responses are accumulated per BLE address.
 * - Only a 9-digit pairing code is promoted to the UI.
 * - Human-readable codes are accepted immediately.
 * - Binary fallbacks must repeat across packets before they are trusted.
 * - Pairing acceptance is pinned to one BLE address. The app never migrates an
 *   accepted code to another endpoint and never calls Android createBond().
 */
object PairingIdRescue {
    private const val TAG = "ATOMOS-PAIR"
    private const val CACHE_TTL_MS = 25_000L

    private data class Candidate(
        var score: Int,
        var hits: Int,
        var lastSeenMs: Long,
    )

    private data class DeviceEvidence(
        val candidates: MutableMap<String, Candidate> = linkedMapOf(),
        var lastSeenMs: Long = 0L,
    )

    private data class Source(val label: String, val bytes: ByteArray)

    private val cache = ConcurrentHashMap<String, DeviceEvidence>()
    private val nineDigitRegex = Regex("(?<!\\d)(\\d{3})[- ]?(\\d{3})[- ]?(\\d{3})(?!\\d)")
    private val labeledRegex = Regex(
        "(?i)(?:PAIR(?:ING)?(?:\\s*ID)?|CODE|PIN)[^0-9]{0,16}(\\d{3})[- ]?(\\d{3})[- ]?(\\d{3})"
    )

    @Synchronized
    fun observe(result: ScanResult): String? {
        val address = runCatching { result.device.address }.getOrNull() ?: return null
        val record = result.scanRecord
        val sources = mutableListOf<Source>()

        record?.bytes?.takeIf { it.isNotEmpty() }?.let { raw ->
            sources += Source("raw", raw)
            parseAdStructures(raw).forEachIndexed { index, bytes ->
                sources += Source("ad-$index", bytes)
            }
        }

        manufacturerBlocks(record).forEachIndexed { index, bytes ->
            sources += Source("manufacturer-$index", bytes)
        }
        serviceBlocks(record).forEachIndexed { index, bytes ->
            sources += Source("service-$index", bytes)
        }

        record?.deviceName?.toByteArray(Charsets.UTF_8)?.let {
            sources += Source("local-name", it)
        }
        runCatching { result.device.name }.getOrNull()?.toByteArray(Charsets.UTF_8)?.let {
            sources += Source("device-name", it)
        }

        val now = System.currentTimeMillis()
        prune(now)
        val evidence = cache.getOrPut(address) { DeviceEvidence() }
        evidence.lastSeenMs = now

        sources.forEach { source ->
            extractCandidates(source).forEach { (raw, score) ->
                val normalized = normalizeNineDigits(raw) ?: return@forEach
                val existing = evidence.candidates[normalized]
                if (existing == null) {
                    evidence.candidates[normalized] = Candidate(score = score, hits = 1, lastSeenMs = now)
                } else {
                    existing.score = maxOf(existing.score, score)
                    existing.hits += 1
                    existing.lastSeenMs = now
                }
            }
        }

        val winner = evidence.candidates.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Candidate>> { confidence(it.value) }
                    .thenByDescending { it.value.hits }
                    .thenByDescending { it.value.score }
            )
            .firstOrNull()
            ?: return null

        if (!isConfident(winner.value)) return null

        val display = formatNineDigits(winner.key)
        Log.i(
            TAG,
            "Pairing ID captured address=$address id=$display score=${winner.value.score} hits=${winner.value.hits}"
        )
        return display
    }

    fun clear(address: String? = null) {
        if (address == null) cache.clear() else cache.remove(address)
    }

    private fun manufacturerBlocks(record: ScanRecord?): List<ByteArray> {
        val data = record?.manufacturerSpecificData ?: return emptyList()
        return buildList {
            for (i in 0 until data.size()) {
                data.valueAt(i)?.takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
    }

    private fun serviceBlocks(record: ScanRecord?): List<ByteArray> {
        val map: Map<ParcelUuid, ByteArray> = record?.serviceData ?: return emptyList()
        return map.values.filter { it.isNotEmpty() }
    }

    /** Parse the individual AD structures from legacy advertisement / scan-response bytes. */
    private fun parseAdStructures(raw: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var index = 0
        while (index < raw.size) {
            val length = raw[index].toInt() and 0xFF
            if (length == 0) break
            val endExclusive = index + 1 + length
            if (length < 1 || endExclusive > raw.size) break
            val payloadStart = index + 2 // length + AD type
            if (payloadStart < endExclusive) {
                out += raw.copyOfRange(payloadStart, endExclusive)
            }
            index = endExclusive
        }
        return out
    }

    private fun extractCandidates(source: Source): List<Pair<String, Int>> {
        val bytes = source.bytes
        if (bytes.isEmpty()) return emptyList()

        val output = linkedMapOf<String, Int>()
        val textViews = linkedSetOf<String>()
        textViews += printableAscii(bytes)
        textViews += runCatching { bytes.toString(Charsets.UTF_8) }.getOrDefault("")
        textViews += runCatching { bytes.toString(Charsets.US_ASCII) }.getOrDefault("")
        if (bytes.size >= 4 && bytes.size % 2 == 0) {
            textViews += runCatching { bytes.toString(Charsets.UTF_16LE) }.getOrDefault("")
            textViews += runCatching { bytes.toString(Charsets.UTF_16BE) }.getOrDefault("")
        }
        textViews += runCatching { bytes.toString(Charset.forName("ISO-8859-1")) }.getOrDefault("")

        val sourceBoost = when {
            source.label.startsWith("manufacturer") || source.label.startsWith("service") -> 10
            source.label.startsWith("ad-") -> 6
            else -> 0
        }

        textViews.forEach { rawText ->
            val text = rawText.replace('\u0000', ' ').trim()
            if (text.isBlank()) return@forEach

            labeledRegex.findAll(text).forEach { match ->
                putBest(
                    output,
                    match.groupValues[1] + match.groupValues[2] + match.groupValues[3],
                    120 + sourceBoost
                )
            }

            nineDigitRegex.findAll(text).forEach { match ->
                putBest(
                    output,
                    match.groupValues[1] + match.groupValues[2] + match.groupValues[3],
                    96 + sourceBoost
                )
            }
        }

        // ATOMOS documentation shows a 9-digit Pairing ID. Binary interpretations are
        // therefore accepted only when they are capable of representing exactly that form.
        decodePackedBcdNineDigits(bytes)?.let { putBest(output, it, 72 + sourceBoost) }
        decodeWholeUInt32(bytes, ByteOrder.LITTLE_ENDIAN)?.let { putBest(output, it, 58 + sourceBoost) }
        decodeWholeUInt32(bytes, ByteOrder.BIG_ENDIAN)?.let { putBest(output, it, 56 + sourceBoost) }

        return output.map { it.key to it.value }
    }

    private fun putBest(target: MutableMap<String, Int>, raw: String, score: Int) {
        val normalized = normalizeNineDigits(raw) ?: return
        val old = target[normalized] ?: Int.MIN_VALUE
        if (score > old) target[normalized] = score
    }

    private fun normalizeNineDigits(raw: String): String? {
        val digits = raw.filter(Char::isDigit)
        if (digits.length != 9) return null
        if (digits.all { it == '0' }) return null
        return digits
    }

    private fun formatNineDigits(digits: String): String =
        "${digits.substring(0, 3)}-${digits.substring(3, 6)}-${digits.substring(6, 9)}"

    private fun printableAscii(bytes: ByteArray): String = buildString(bytes.size) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(if (value in 32..126) value.toChar() else ' ')
        }
    }

    private fun decodePackedBcdNineDigits(bytes: ByteArray): String? {
        if (bytes.size != 5) return null
        val nibbles = mutableListOf<Int>()
        bytes.forEach { byte ->
            nibbles += (byte.toInt() ushr 4) and 0x0F
            nibbles += byte.toInt() and 0x0F
        }
        val digits = when {
            nibbles.firstOrNull() == 0x0F && nibbles.drop(1).all { it in 0..9 } -> nibbles.drop(1)
            nibbles.lastOrNull() == 0x0F && nibbles.dropLast(1).all { it in 0..9 } -> nibbles.dropLast(1)
            else -> return null
        }
        if (digits.size != 9) return null
        return digits.joinToString("")
    }

    private fun decodeWholeUInt32(bytes: ByteArray, order: ByteOrder): String? {
        if (bytes.size != 4) return null
        val unsigned = ByteBuffer.wrap(bytes).order(order).int.toLong() and 0xFFFF_FFFFL
        if (unsigned !in 1..999_999_999L) return null
        return unsigned.toString().padStart(9, '0')
    }

    private fun confidence(candidate: Candidate): Int =
        candidate.score + minOf(candidate.hits - 1, 4) * 14

    private fun isConfident(candidate: Candidate): Boolean =
        candidate.score >= 90 ||
            (candidate.score >= 70 && candidate.hits >= 2) ||
            (candidate.score >= 55 && candidate.hits >= 3)

    private fun prune(now: Long) {
        cache.entries
            .filter { now - it.value.lastSeenMs > CACHE_TTL_MS }
            .map { it.key }
            .forEach(cache::remove)
    }
}

/**
 * Session gate that prevents the app from causing Android or SHOGUN pairing prompts
 * before the Pairing ID has been captured and explicitly accepted.
 */
object PairingRuntimeGate {
    private const val TAG = "ATOMOS-PAIR"
    private const val ACCEPT_WINDOW_MS = 120_000L

    private val capturedIds = ConcurrentHashMap<String, String>()

    @Volatile private var activeAddress: String? = null
    @Volatile private var acceptedAddress: String? = null
    @Volatile private var acceptedPairingId: String? = null
    @Volatile private var acceptedUntilMs: Long = 0L

    fun observeScanResult(result: ScanResult): String? {
        val address = runCatching { result.device.address }.getOrNull() ?: return null
        val found = PairingIdRescue.observe(result)
        if (found != null) capturedIds[address] = found
        return capturedIds[address]
    }

    fun seedCapturedId(address: String, pairingId: String?) {
        val normalized = normalizeDisplay(pairingId) ?: return
        capturedIds[address] = normalized
    }

    fun beginFreshPairing(address: String, knownPairingId: String? = null) {
        activeAddress = address
        capturedIds.remove(address)
        acceptedAddress = null
        acceptedPairingId = null
        acceptedUntilMs = 0L
        PairingIdRescue.clear(address)
        if (knownPairingId != null) seedCapturedId(address, knownPairingId)
        Log.i(TAG, "Fresh pairing session started address=$address; mutations blocked until code acceptance")
    }

    fun currentPairingId(address: String? = activeAddress): String? =
        address?.let { capturedIds[it] }

    fun currentPairingAddress(): String? = activeAddress

    fun confirmUserAccept(expectedPairingId: String, address: String): Boolean {
        val captured = capturedIds[address] ?: run {
            Log.w(TAG, "Pairing acceptance rejected: no captured Pairing ID for address=$address")
            return false
        }
        val expected = normalizeDisplay(expectedPairingId) ?: return false
        if (!samePairingId(captured, expected)) {
            Log.w(TAG, "Pairing acceptance rejected: displayed code does not match captured code")
            return false
        }

        activeAddress = address
        acceptedAddress = address
        acceptedPairingId = captured
        acceptedUntilMs = System.currentTimeMillis() + ACCEPT_WINDOW_MS
        Log.i(TAG, "User accepted Pairing ID=$captured address=$address")
        return true
    }

    fun isUserConfirmed(address: String? = activeAddress): Boolean {
        val wanted = address ?: return false
        return acceptedAddress == wanted &&
            acceptedPairingId != null &&
            System.currentTimeMillis() <= acceptedUntilMs
    }

    fun isPairingMutationAllowed(address: String?): Boolean =
        address != null && isUserConfirmed(address)

    fun acceptedSessionPairingId(): String? {
        val id = acceptedPairingId ?: return null
        return id.takeIf { System.currentTimeMillis() <= acceptedUntilMs }
    }

    fun acceptedSessionAddress(): String? =
        acceptedAddress?.takeIf { System.currentTimeMillis() <= acceptedUntilMs }


    fun resetAcceptance() {
        acceptedAddress = null
        acceptedPairingId = null
        acceptedUntilMs = 0L
    }

    private fun normalizeDisplay(raw: String?): String? {
        val digits = raw?.filter(Char::isDigit).orEmpty()
        if (digits.length != 9 || digits.all { it == '0' }) return null
        return "${digits.substring(0, 3)}-${digits.substring(3, 6)}-${digits.substring(6, 9)}"
    }

    private fun samePairingId(a: String, b: String): Boolean =
        a.filter(Char::isDigit) == b.filter(Char::isDigit)
}
