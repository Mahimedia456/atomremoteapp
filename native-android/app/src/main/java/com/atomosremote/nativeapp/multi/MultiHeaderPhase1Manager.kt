package com.atomosremote.nativeapp.multi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.atomosremote.nativeapp.ble.BleAdvertisement
import com.atomosremote.nativeapp.ble.BleScanner
import com.atomosremote.nativeapp.ble.GattClient
import com.atomosremote.nativeapp.data.DeviceIdentityStore
import com.atomosremote.nativeapp.data.SavedAtomosDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** MDP1: independent secondary sessions. Never owns or replaces ViewModel's primary GATT. */
data class Mdp1Recorder(
    val address: String,
    val name: String,
    val status: String = "Saved — not connected",
    val trusted: Boolean = false,
    val ready: Boolean = false,
    val pairingCode: String? = null,
    val canAccept: Boolean = false,
    val error: String? = null
)

data class Mdp1UiState(
    val primaryAddress: String? = null,
    val primaryName: String = "Primary recorder",
    val primaryReady: Boolean = false,
    val saved: List<SavedAtomosDevice> = emptyList(),
    val discovered: List<BleAdvertisement> = emptyList(),
    val secondaries: List<Mdp1Recorder> = emptyList(),
    val selected: Set<String> = emptySet(),
    val scanning: Boolean = false,
    val message: String = "Select READY recorders for the shared Record / Stop / Play / Pause buttons."
)

class MultiHeaderPhase1Manager(context: Context) {
    companion object {
        const val MAX_TOTAL = 5
        private const val TAG = "AtomRemoteMDP1"
    }
    private val app = context.applicationContext
    private val store = DeviceIdentityStore(app)
    private val prefs = app.getSharedPreferences("atomosremote_trusted_tcs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scanner = BleScanner(app)
    private val clients = mutableMapOf<String, GattClient>()
    private val watchers = mutableMapOf<String, MutableList<Job>>()
    private val pairStarted = mutableSetOf<String>()
    private val recoveryScheduled = mutableSetOf<String>()
    private val recoveryJobs = mutableMapOf<String, Job>()
    private val trustedRestoreJobs = mutableMapOf<String, Job>()
    // Actual BLE loss only: at most two trusted restores per recorder in five minutes,
    // with spacing. This prevents endless reconnect loops but recovers later real drops.
    // Recorder selection and all existing Remote controls remain unchanged.
    private val trustedRestoreTimes = mutableMapOf<String, MutableList<Long>>()
    private val trustedRestoreSuppressedUntil = mutableMapOf<String, Long>()
    private val lastSecondaryBle = mutableMapOf<String, String>()
    private val lastSecondaryReady = mutableMapOf<String, Boolean>()
    private val restoreWindowMs = 300_000L
    private val restoreSpacingMs = 15_000L
    private val restoreLimit = 2
    private var scanJob: Job? = null
    private val _ui = MutableStateFlow(Mdp1UiState(saved = store.loadAll()))
    val ui: StateFlow<Mdp1UiState> = _ui
    // Secondary PacketLink can finish or drop READY without a new pairing-state emission.
    // READ-ONLY reconciliation; no auto CONNECT or SET/retry loop.
    init {
        scope.launch {
            while (isActive) {
                clients.toMap().forEach { (address, client) -> refreshStatus(address, client) }
                delay(850L)
            }
        }
    }

    private fun key(address: String): String = address.uppercase(Locale.ROOT)
    private fun isPrimary(address: String): Boolean = key(address) == _ui.value.primaryAddress?.let(::key)
    private fun secondaryBleDead(ble: String): Boolean =
        ble == "disconnected" || ble == "idle" || ble.startsWith("error:") || ble.startsWith("service-error:")
    private data class Trust(val code: String?, val ccid: Long, val bcid: Long)
    private fun trust(address: String): Trust? {
        val id = key(address)
        val ccid = prefs.getLong("$id.ccid", 0L)
        val bcid = prefs.getLong("$id.bcid", 0L)
        return if (ccid != 0L && bcid != 0L) Trust(prefs.getString("$id.code", null), ccid, bcid) else null
    }
    fun syncPrimary(address: String?, name: String, ready: Boolean) {
        val primary = address?.let(::key)
        val old = _ui.value
        // Never allow a second independent GATT to duplicate primary connection.
        if (primary != null && clients.containsKey(primary)) disconnectSecondary(primary)
        val selected = when {
            primary == null -> old.selected
            old.primaryAddress == null && old.selected.isEmpty() -> setOf(primary)
            old.primaryAddress != null && old.primaryAddress != primary ->
                (old.selected - old.primaryAddress) + (if (old.primaryAddress in old.selected) setOf(primary) else emptySet())
            else -> old.selected
        }
        _ui.value = old.copy(primaryAddress = primary, primaryName = name,
            primaryReady = ready, selected = selected)
    }
    fun refreshSaved() { _ui.value = _ui.value.copy(saved = store.loadAll()) }
    private fun note(message: String) {
        Log.i(TAG, message)
        _ui.value = _ui.value.copy(message = message)
    }
    fun toggle(address: String) {
        val id = key(address)
        val st = _ui.value
        val eligible = isPrimary(id) || st.saved.any { key(it.address) == id }
        if (!eligible) { note("Pair and save this recorder before selecting it."); return }
        val next = if (id in st.selected) st.selected - id else st.selected + id
        if (next.size > MAX_TOTAL) { note("Maximum $MAX_TOTAL recorders total."); return }
        _ui.value = st.copy(selected = next)
        Log.i("AtomRemoteMDP2", "SELECT id=$id selected=${id in next} total=${next.size} route=transport-only")
    }
    fun selectAll() {
        val st = _ui.value
        // ALL means all CONNECTED+READY, never disconnected saved recorder IDs.
        val eligible = (listOfNotNull(st.primaryAddress?.takeIf { st.primaryReady }) +
            st.secondaries.filter { it.ready }.map { it.address }).distinct().take(MAX_TOTAL)
        _ui.value = st.copy(selected = eligible.toSet())
        note("Selected ${eligible.size} READY recorder(s). Shared transport buttons now target this set.")
    }
    fun selectPrimaryOnly() {
        _ui.value = _ui.value.copy(selected = listOfNotNull(_ui.value.primaryAddress).toSet())
        note("Primary only. Shared Remote transport buttons target primary.")
    }

    /** MDP2. Transport-only routing; other tools/tabs still primary until Phase 3.
     * Preflight EVERY selected device before sending to ANY device (no partial group on stale READY).
     * A successful enqueue is NOT recorder acknowledgment. Never retries a user command.
     */
    fun dispatchSelectedTransport(command: String, sendPrimary: () -> Boolean): Boolean {
        val st = _ui.value
        Log.i("AtomRemoteMDPF", "ROUTE_CLICK command=$command selected=${st.selected} primary=${st.primaryAddress} " +
            "primarySnapshotReady=${st.primaryReady} sessions=${clients.size}")
        val ids = st.selected.sorted()
        if (command !in setOf("record-start", "record-stop", "play", "pause")) {
            note("Unsupported shared transport command: $command")
            return false
        }
        if (ids.isEmpty()) {
            note("Select at least one READY recorder using + ADD.")
            Log.w("AtomRemoteMDP2", "ROUTE_BLOCK command=$command reason=NO_SELECTION tx=false")
            return false
        }
        val unready = ids.filter { id ->
            // ViewModel primary is authoritative; do not block it on a stale MDP snapshot.
            if (id == st.primaryAddress) false
            else clients[id]?.isOfficialRemoteSessionReadyForCommands() != true
        }
        if (unready.isNotEmpty()) {
            note("Selected recorder not READY. Reconnect before shared $command.")
            Log.w("AtomRemoteMDP2", "ROUTE_BLOCK command=$command reason=UNREADY ids=$unready tx=false")
            return false
        }
        var queued = 0
        val failed = mutableListOf<String>()
        // Primary: preserve exact ViewModel method and its mode-aware UI state handler.
        if (st.primaryAddress != null && st.primaryAddress in ids) {
            val accepted = runCatching { sendPrimary() }.getOrDefault(false)
            if (accepted) queued++ else failed += "primary"
            Log.i("AtomRemoteMDP2", "ROUTE_TARGET command=$command role=primary id=${st.primaryAddress} queued=$accepted")
        }
        for (id in ids) {
            if (id == st.primaryAddress) continue
            val client = clients[id]
            val accepted = client != null && client.isOfficialRemoteSessionReadyForCommands() &&
                runCatching { client.sendOfficialRemoteCommand(command) }.getOrDefault(false)
            if (accepted) queued++ else failed += id
            Log.i("AtomRemoteMDP2", "ROUTE_TARGET command=$command role=secondary id=$id queued=$accepted")
        }
        val summary = "Transport $command: queued=$queued/${ids.size}" +
            if (failed.isEmpty()) " (recorder confirmation pending)" else " failed=${failed.size}"
        note(summary)
        Log.i("AtomRemoteMDP2", "ROUTE_RESULT command=$command selected=${ids.size} queued=$queued failed=$failed recorderConfirmed=false noRetry=true")
        return queued == ids.size
    }

    /** Explicit supported Remote tool routing: never quietly mutate the wrong primary.
     * Secondary SET enqueue is per own GATT/PacketLink; screen GET may still be primary-only.
     * For secondary-only or multi selection do not claim UI readback is per-target verified.
     */
    fun dispatchSelectedTool(tool: String, sendPrimary: () -> Unit,
                             sendSecondary: (GattClient) -> Boolean): Boolean {
        val st = _ui.value
        val ids = st.selected.sorted()
        Log.i("AtomRemoteMDPF", "TOOL_CLICK name=$tool selected=$ids")
        if (ids.isEmpty()) {
            Log.w("AtomRemoteMDPF", "TOOL_BLOCK name=$tool reason=NO_SELECTION tx=false")
            note("Select a recorder first.")
            return false
        }
        val unready = ids.filter { id ->
            id != st.primaryAddress && clients[id]?.isOfficialRemoteSessionReadyForCommands() != true
        }
        if (unready.isNotEmpty()) {
            Log.w("AtomRemoteMDPF", "TOOL_BLOCK name=$tool reason=SECONDARY_UNREADY ids=$unready tx=false")
            note("Reconnect selected recorder before $tool.")
            return false
        }
        var accepted = 0
        ids.forEach { id ->
            if (id == st.primaryAddress) {
                // Primary VM keeps all existing guards and readback behavior.
                val ok = runCatching { sendPrimary(); true }.getOrDefault(false)
                if (ok) accepted++
                Log.i("AtomRemoteMDPF", "TOOL_TARGET name=$tool role=primary id=$id forwarded=$ok")
            } else {
                val ok = clients[id]?.let { client -> runCatching { sendSecondary(client) }.getOrDefault(false) } == true
                if (ok) accepted++
                Log.i("AtomRemoteMDPF", "TOOL_TARGET name=$tool role=secondary id=$id queued=$ok")
            }
        }
        Log.i("AtomRemoteMDPF", "TOOL_RESULT name=$tool selected=${ids.size} forwarded=$accepted " +
            "recorderConfirmed=false noAutoRetry=true")
        if (accepted != ids.size) note("$tool: $accepted/${ids.size} queued; inspect session READY.")
        return accepted == ids.size
    }

    @SuppressLint("MissingPermission")
    fun scan() {
        if (scanJob?.isActive == true) return
        if (!scanner.isEnabled()) { note("Enable Bluetooth and grant Bluetooth/Location permissions."); return }
        _ui.value = _ui.value.copy(scanning = true, discovered = emptyList())
        scanJob = scope.launch {
            val unique = linkedMapOf<String, BleAdvertisement>()
            try {
                withTimeoutOrNull(16_000L) {
                    scanner.scanDiscovery().collect { ad ->
                        val name = ad.name.orEmpty().uppercase(Locale.ROOT)
                        val likely = ad.serviceUuids.any { it.startsWith("9383b854", true) } ||
                            name.contains("ATOMOS") || name.contains("SHOGUN") ||
                            name.contains("NINJA") || name.contains("AIRGLU") ||
                            name.startsWith("AG2")
                        if (likely) {
                            unique[key(ad.address)] = ad
                            _ui.value = _ui.value.copy(discovered = unique.values.toList())
                        }
                    }
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                note("Scan error: ${error.message ?: "Bluetooth unavailable"}")
            } finally {
                _ui.value = _ui.value.copy(scanning = false)
                Log.i(TAG, "SCAN_COMPLETE candidates=${unique.size} primaryPreserved=true")
            }
        }
    }
    fun stopScan() { scanJob?.cancel(); scanJob = null; _ui.value = _ui.value.copy(scanning = false) }

    @SuppressLint("MissingPermission")
    fun connectSaved(saved: SavedAtomosDevice) {
        stopScan() // OEM BLE stacks handle connect more reliably after scanning stops.
        val address = key(saved.address)
        if (isPrimary(address)) { note("Already connected as primary. No duplicate GATT opened."); return }
        val existing = clients[address]
        if (existing != null && existing.isOfficialRemoteSessionReadyForCommands()) {
            refreshStatus(address, existing)
            note("${saved.name} is already PacketLink READY.")
            return
        }
        // Do not close a healthy pairing / PacketLink operation because phase temporarily changed.
        // Reconnect is allowed only after an actual BLE disconnect/error, not 'sending' or 'queued'.
        if (existing != null && !secondaryBleDead(existing.state.value)) {
            refreshStatus(address, existing)
            note("${saved.name} link is active; waiting for PacketLink, no reconnect needed.")
            return
        }
        // Explicit trusted connect may replace a physically disconnected secondary GATT.
        // Never touch ViewModel's primary; only bounded recovery after confirmed BLE loss.
        if (existing != null) {
            Log.w("AtomRemoteMDPF", "SESSION_STALE id=$address explicitTrustedReconnect=true")
            removeClient(address)
        }
        if (clients.size >= MAX_TOTAL - 1) { note("Maximum ${MAX_TOTAL - 1} secondary connections."); return }
        val identity = trust(address)
        if (identity == null) { note("No trusted TCS identity for ${saved.name}; use Scan → Pair."); return }
        val bt = runCatching {
            app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(saved.address)
        }.getOrNull() ?: run { note("Invalid BLE address."); return }
        val client = GattClient(app)
        clients[address] = client
        update(address, saved.name) { it.copy(status = "Connecting", trusted = true) }
        observe(address, client, false)
        val started = runCatching { client.connectTrusted(bt, identity.code, identity.ccid, identity.bcid) }.getOrDefault(false)
        if (!started) { disconnectSecondary(address); note("Trusted reconnect was rejected.") }
        else note("Connecting ${saved.name}; waiting for verified PacketLink + bootstrap.")
    }

    fun reconnectSaved(address: String) {
        val id = key(address)
        if (isPrimary(id)) { note("Primary is managed by the original connection."); return }
        val saved = store.loadAll().firstOrNull { key(it.address) == id }
        if (saved == null) { note("Saved recorder missing; Scan/Pair first."); return }
        Log.i("AtomRemoteMDPF", "USER_RECONNECT id=$id noAutoLoop=true")
        // Explicit user reconnect resets this recorder's bounded recovery allowance.
        trustedRestoreTimes.remove(id)
        trustedRestoreSuppressedUntil.remove(id)
        connectSaved(saved)
    }

    @SuppressLint("MissingPermission")
    fun pairDiscovered(ad: BleAdvertisement) {
        stopScan() // Avoid scan/GATT contention during official 9-digit pairing.
        val address = key(ad.address)
        if (isPrimary(address)) { note("Primary recorder is already connected."); return }
        if (clients.containsKey(address)) { note("Session exists; disconnect that secondary before Pair Again."); return }
        if (clients.size >= MAX_TOTAL - 1) { note("Maximum ${MAX_TOTAL - 1} secondary connections."); return }
        val client = GattClient(app)
        clients[address] = client
        val name = ad.name?.takeIf { it.isNotBlank() } ?: "ATOMOS recorder"
        update(address, name) { it.copy(status = "Starting official TCS pairing") }
        observe(address, client, true)
        runCatching { client.connect(ad.device) }.onFailure {
            disconnectSecondary(address)
            note("Pair failed: ${it.message}")
        }
    }

    fun acceptPair(address: String) {
        val id = key(address)
        val client = clients[id] ?: run { note("Pairing session missing."); return }
        val state = client.tcsPairingState.value
        if (!state.canAccept || state.pairingCode.isNullOrBlank()) {
            note("9-digit code is not ready. Verify the same code on the recorder."); return
        }
        val queued = runCatching { client.acceptOfficialTcsPairing() }.getOrDefault(false)
        note(if (queued) "Acceptance sent; waiting for recorder-confirmed BCID / PacketLink." else "Acceptance did not queue.")
    }

    private fun observe(address: String, client: GattClient, freshPair: Boolean) {
        val jobs = mutableListOf<Job>()
        jobs += scope.launch { client.state.collect { ble ->
            refreshStatus(address, client)
            if (freshPair && ble == "services-ready" && pairStarted.add(address)) {
                val queued = runCatching { client.beginOfficialTcsPairing() }.getOrDefault(false)
                if (!queued) {
                    pairStarted.remove(address)
                    update(address, "Recorder") { it.copy(status = "TCS start failed", error = "Retry pairing after reconnect") }
                }
            }
        } }
        jobs += scope.launch { client.tcsRemoteState.collect { refreshStatus(address, client) } }
        jobs += scope.launch { client.tcsPairingState.collect { pair ->
            refreshStatus(address, client)
            if (freshPair && pair.acceptWritten && pair.ccid != null && pair.bcid != null && pair.ccid != 0L && pair.bcid != 0L) {
                val old = trust(address)
                if (old?.ccid != pair.ccid || old?.bcid != pair.bcid) {
                    prefs.edit().putLong("$address.ccid", pair.ccid).putLong("$address.bcid", pair.bcid)
                        .putString("$address.code", pair.pairingCode).apply()
                }
                val existing = store.loadAll()
                if (existing.none { key(it.address) == address }) {
                    // DeviceIdentityStore.save() rewrites primary by default. Restore it immediately.
                    val priorPrimary = app.getSharedPreferences("atomos_devices", Context.MODE_PRIVATE)
                        .getString("primary_address", null)
                    val name = _ui.value.secondaries.firstOrNull { it.address == address }?.name ?: "ATOMOS recorder"
                    store.save(SavedAtomosDevice(address, name, "generic", preferred = false))
                    if (priorPrimary != null) app.getSharedPreferences("atomos_devices", Context.MODE_PRIVATE)
                        .edit().putString("primary_address", priorPrimary).apply()
                    refreshSaved()
                }
                if (recoveryScheduled.add(address)) {
                    recoveryJobs[address] = scope.launch recovery@ {
                        delay(22_000L)
                        if (clients[address] !== client || client.isOfficialRemoteSessionReadyForCommands() ||
                            (!secondaryBleDead(client.state.value) && client.tcsRemoteState.value.available)) return@recovery
                        val saved = store.loadAll().firstOrNull { key(it.address) == address } ?: return@recovery
                        // One per-session trusted recovery, never primary, never loop.
                        Log.i(TAG, "FIRST_PAIR_LINK_RECOVERY id=$address once=true")
                        removeClient(address)
                        connectSaved(saved)
                    }
                }
            }
        } }
        watchers[address] = jobs
    }
    private fun refreshStatus(address: String, client: GattClient) {
        if (clients[address] !== client) return
        val ble = client.state.value
        val pair = client.tcsPairingState.value
        val link = client.tcsRemoteState.value
        val ready = client.isOfficialRemoteSessionReadyForCommands()
        val previous = _ui.value.secondaries.firstOrNull { it.address == address }?.ready
        if (previous != ready) {
            Log.i("AtomRemoteMDPF", "SESSION_READY_CHANGE id=$address ready=$ready ble=$ble " +
                "tcs=${pair.phase} link=${link.phase} accepted=${pair.acceptWritten}")
        }
        val beforeBle = lastSecondaryBle.put(address, ble)
        if (beforeBle != ble) {
            Log.w("AtomRemoteMDPLink", "BLE_STATE id=$address old=${beforeBle ?: "new"} new=$ble " +
                "reason=${client.disconnectReason.value ?: "none"} " +
                "tcs=${pair.phase} packet=${link.phase} selected=${address in _ui.value.selected}")
        }
        val beforeReady = lastSecondaryReady.put(address, ready)
        if (beforeReady != ready) {
            Log.i("AtomRemoteMDPLink", "READY_STATE id=$address ready=$ready ble=$ble " +
                "reason=${client.disconnectReason.value ?: "none"}")
        }
        if (ready) {
            recoveryJobs.remove(address)?.cancel()
            trustedRestoreJobs.remove(address)?.cancel()
            trustedRestoreSuppressedUntil.remove(address)
        } else if (secondaryBleDead(ble) && trust(address) != null &&
                   trustedRestoreJobs[address]?.isActive != true &&
                   System.currentTimeMillis() >= (trustedRestoreSuppressedUntil[address] ?: 0L)) {
            // Only a verified BLE loss triggers restore; 'sending'/'queued' never do.
            trustedRestoreJobs[address] = scope.launch {
                delay(3_000L)
                if (clients[address] === client && secondaryBleDead(client.state.value)) {
                    val saved = store.loadAll().firstOrNull { key(it.address) == address }
                    if (saved != null) {
                        val now = System.currentTimeMillis()
                        val recent = trustedRestoreTimes.getOrPut(address) { mutableListOf() }
                        recent.removeAll { now - it >= restoreWindowMs }
                        val tooSoon = recent.lastOrNull()?.let { now - it < restoreSpacingMs } ?: false
                        if (recent.size >= restoreLimit || tooSoon) {
                            val until = if (recent.size >= restoreLimit)
                                (recent.first() + restoreWindowMs).coerceAtLeast(now + 5_000L)
                            else now + restoreSpacingMs
                            trustedRestoreSuppressedUntil[address] = until
                            Log.w("AtomRemoteMDPLink", "RESTORE_DEFERRED id=$address attempts=${recent.size} " +
                                "untilEpochMs=$until reason=${client.disconnectReason.value ?: "unknown"}")
                        } else {
                            recent.add(now)
                            Log.w("AtomRemoteMDPLink", "AUTO_TRUSTED_RESTORE id=$address " +
                                "attempt=${recent.size}/$restoreLimit cause=REAL_BLE_LOSS " +
                                "reason=${client.disconnectReason.value ?: "unknown"}")
                            trustedRestoreJobs.remove(address)
                            connectSaved(saved)
                        }
                    }
                }
                if (trustedRestoreJobs[address]?.isActive != true) trustedRestoreJobs.remove(address)
            }
        }
        val phase = when {
            ready -> "PacketLink READY"
            secondaryBleDead(ble) -> "BLE: $ble"
            pair.phase == "error" -> "TCS pairing error"
            link.phase == "error" -> "PacketLink error"
            !pair.acceptWritten && pair.phase != "idle" -> "Pairing: ${pair.phase}"
            link.phase != "idle" -> "Linking: ${link.phase}"
            else -> "BLE: $ble"
        }
        update(address, "Recorder") { it.copy(
            status = phase, trusted = trust(address) != null,
            ready = ready, pairingCode = pair.pairingCode, canAccept = pair.canAccept,
            error = pair.lastError ?: link.lastError
        ) }
        if (ready && previous != true) Log.i(TAG, "READY secondary=$address accepted=true packetPrime=true bootstrap=true")
    }
    private fun update(address: String, name: String, change: (Mdp1Recorder) -> Mdp1Recorder) {
        val old = _ui.value.secondaries
        val existing = old.firstOrNull { it.address == address } ?: Mdp1Recorder(address, name)
        val changed = change(existing)
        if (changed != existing || old.none { it.address == address }) {
            _ui.value = _ui.value.copy(secondaries = old.filterNot { it.address == address } + changed)
        }
    }
    fun disconnectSecondary(address: String) {
        val id = key(address)
        if (isPrimary(id)) return
        removeClient(id)
        recoveryScheduled.remove(id)
        recoveryJobs.remove(id)?.cancel()
        trustedRestoreJobs.remove(id)?.cancel()
        trustedRestoreTimes.remove(id)
        trustedRestoreSuppressedUntil.remove(id)
        lastSecondaryBle.remove(id)
        lastSecondaryReady.remove(id)
        _ui.value = _ui.value.copy(secondaries = _ui.value.secondaries.filterNot { it.address == id })
    }
    private fun removeClient(id: String) {
        watchers.remove(id)?.forEach { it.cancel() }
        clients.remove(id)?.close()
        pairStarted.remove(id)
        lastSecondaryBle.remove(id)
        lastSecondaryReady.remove(id)
    }
    fun close() {
        scanJob?.cancel()
        recoveryJobs.values.forEach { it.cancel() }
        trustedRestoreJobs.values.forEach { it.cancel() }
        watchers.values.flatten().forEach { it.cancel() }
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        scope.cancel()
    }
}
