package com.atomosremote.nativeapp.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atomosremote.nativeapp.viewmodel.RemoteTransportUiController

/**
 * U04.20 guarded UI evidence and lifecycle cleanup on the U04.16 source baseline.
 * FileListGet 0x01FC and FileBrowserIndexMsg(field 1=index) are macOS static facts.
 * 08 00 is an explicitly encoded index-zero *candidate*, NOT AG2-proven.
 * An operator tap uses the EXISTING ViewModel callback/PacketLink GET path.
 * All state-changing, autoplay, browser navigation and FilePlay TX remain locked.
 * Recorder playback and SSD mount MUST be manually observed. Transport-tracked
 * playback state is not a recorder readback. ATT writes are log-only evidence.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun MediaModePlaybackScreen(
    ui: RemoteTransportUiController.State,
    mediaFileList: SsdBrowserState,
    remoteAvailable: Boolean,
    remotePhase: String,
    commandStatus: String?,
    onPlaybackMode: () -> Unit,
    onRecordMode: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSsdListEvidence: (Int) -> Unit,
    onDiagnosticTest: (String) -> Boolean,
    onSsdListTimeout: (Long) -> Unit,
    onSsdListReset: () -> Unit,
    onModeGetEvidence: () -> Unit,
    onModeStart: () -> Unit,
    onMediaStateGetEvidence: () -> Unit,
    onRecover: () -> Unit
) {
    val accent = Color(0xFF19E7D3)
    val warning = Color(0xFFFFCC91)
    val muted = Color(0xFF95A8AA)
    val inFlight = remotePhase in setOf(
        "queued", "sending", "requesting-credit", "awaiting-credit", "awaiting-response", "bootstrapping"
    )
    val ready = remoteAvailable && !ui.locked && !inFlight
    val playback = ui.mode == RemoteTransportUiController.Mode.PLAYBACK
    val canPlay = ready && playback
    var showTechnical by rememberSaveable { mutableStateOf(false) }
    var showLegacyDiagnostics by rememberSaveable { mutableStateOf(false) }
    var operatorPlaybackConfirmed by remember { mutableStateOf(false) }
    var operatorSsdConfirmed by remember { mutableStateOf(false) }
    var singleTestQueued by remember { mutableStateOf(false) }
    var testFeedback by rememberSaveable { mutableStateOf("NOT TESTED") }
    var testGeneration by remember { mutableStateOf(-1L) }
    // U04.20 UI-only evidence. The backend callback returns Unit: a tap is NOT a queued GET.
    var mediaStateGetTapped by rememberSaveable { mutableStateOf(false) }
    var modeGetTapCount by rememberSaveable { mutableStateOf(0) }

    // If Media screen leaves composition during a FileList request, cancel stale UI
    // evidence state without sending anything. No hidden TX, retries or SETs.
    val latestPending = rememberUpdatedState(mediaFileList.pending)
    val latestGeneration = rememberUpdatedState(mediaFileList.generation)
    val latestResetCallback = rememberUpdatedState(onSsdListReset)
    DisposableEffect(Unit) {
        onDispose {
            if (latestPending.value) {
                android.util.Log.w("AtomRemoteMediaU0420",
                    "U0420_SCREEN_LEFT_PENDING generation=${latestGeneration.value} " +
                        "resetEvidenceOnly=true NO_TX=true timeoutNotProven=true")
                latestResetCallback.value.invoke()
            }
        }
    }

    // Existing GATT read-only RX collector closes at 40s. UI cleanup at 41s
    // allows for scheduling tolerance; it does NOT prove ATT or recorder timeout.
    LaunchedEffect(mediaFileList.pending, mediaFileList.generation) {
        if (mediaFileList.pending) {
            kotlinx.coroutines.delay(41000L)
            onSsdListTimeout(mediaFileList.generation)
        }
    }
    LaunchedEffect(playback, remoteAvailable) {
        if (!playback || !remoteAvailable) {
            onSsdListReset()
            operatorPlaybackConfirmed = false
            operatorSsdConfirmed = false
            singleTestQueued = false
            testFeedback = "NOT TESTED"
            testGeneration = -1L
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFF1F4645)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF081517))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text("RECORDER MODE", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Trusted Remote transport. Selecting Record Mode does not start recording.",
                color = muted, fontSize = 11.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRecordMode,
                    enabled = ready && ui.mode != RemoteTransportUiController.Mode.RECORD,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp,
                        if (ui.mode == RemoteTransportUiController.Mode.RECORD) accent else Color(0xFF53686C)),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
                ) { Text("RECORD MODE", fontSize = 11.sp) }
                OutlinedButton(
                    onClick = onPlaybackMode,
                    enabled = ready && !playback,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, if (playback) accent else Color(0xFF53686C)),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
                ) { Text("PLAYBACK MODE", fontSize = 11.sp) }
            }
            val mode = when (ui.mode) {
                RemoteTransportUiController.Mode.RECORD -> "Record (transport-tracked)"
                RemoteTransportUiController.Mode.PLAYBACK -> "Playback (transport-tracked)"
                RemoteTransportUiController.Mode.RETURNING_TO_RECORD -> "Returning to Record"
                else -> "Unknown, no dedicated readback"
            }
            Text("Mode: $mode  |  Link: ${if (remoteAvailable) "available" else "unavailable"}",
                color = accent, fontSize = 11.sp)
            Text("Transport mode is NOT independent recorder mode confirmation.",
                color = warning, fontSize = 10.sp)
            if (ui.pendingAction != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = accent)
                Text("Waiting: ${ui.pendingLabel ?: ui.pendingAction}", color = Color.White, fontSize = 11.sp)
            }
            if (ui.recoveryRequired) {
                Text(ui.error ?: "Remote session recovery required", color = warning, fontSize = 11.sp)
                OutlinedButton(onClick = onRecover, modifier = Modifier.fillMaxWidth()) {
                    Text("RECOVER TRUSTED REMOTE", fontSize = 11.sp)
                }
            }
            if (!commandStatus.isNullOrBlank())
                Text(commandStatus, color = muted, fontSize = 10.sp)
        }
    }

    // These are the existing transport actions, and are not linked to a guessed SSD file index.
    if (playback) {
        U0455MacStylePlaybackDeck(
            mediaFileList = mediaFileList,
            controlsEnabled = canPlay,
            onPlay = onPlay,
            onPause = onPause
        )
        TextButton(
            onClick = { showLegacyDiagnostics = !showLegacyDiagnostics },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (showLegacyDiagnostics) "HIDE LEGACY DIAGNOSTICS" else "SHOW LEGACY DIAGNOSTICS",
                fontSize = 11.sp)
        }
        if (showLegacyDiagnostics) {
        Card(
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF1F4645)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF081517))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SSD FILE BROWSER | U04.20", color = Color.White,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Original Mac directory query: 0x01FB. Legacy 0x01FC index action is unverified.",
                    color = Color.White, fontSize = 11.sp)
                Text("Index 0 candidate: 08 00 | non-local E0 | AG2 acceptance UNVERIFIED",
                    color = warning, fontSize = 11.sp)
                Text("One manual read-only GET. No automatic TX, pagination, FilePlay, ModeStart or SET.",
                    color = muted, fontSize = 11.sp)
                Text("BEFORE TEST: confirm BOTH items on the physical Shogun Ultra AG2.",
                    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = operatorPlaybackConfirmed,
                        onCheckedChange = { operatorPlaybackConfirmed = it },
                        enabled = canPlay && !singleTestQueued)
                    Text("Recorder screen visibly in Playback Mode",
                        modifier = Modifier.weight(1f), color = Color.White, fontSize = 11.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = operatorSsdConfirmed,
                        onCheckedChange = { operatorSsdConfirmed = it },
                        enabled = canPlay && !singleTestQueued)
                    Text("SSD mounted; recorder shows accessible media",
                        modifier = Modifier.weight(1f), color = Color.White, fontSize = 11.sp)
                }
                Text("Manual checks are operator observations, NOT BLE readbacks.",
                    color = muted, fontSize = 10.sp)

                OutlinedButton(
                    onClick = {
                        if (!singleTestQueued && operatorPlaybackConfirmed && operatorSsdConfirmed) {
                            val before = mediaFileList.generation
                            // Existing AtomOS App mapping: filelist-nonlocal -> ViewModel
                            // requestMediaU04FileListRouteOnce(0,false) -> existing GATT GET.
                            android.util.Log.i("AtomRemoteMediaU0416",
                                "U0416_OPERATOR_GATE modeObserved=$operatorPlaybackConfirmed " +
                                    "ssdObserved=$operatorSsdConfirmed transportPlayback=$playback " +
                                    "noAutomaticTx=true")
                            val accepted = onDiagnosticTest("filelist-nonlocal")
                            if (accepted) {
                                singleTestQueued = true
                                testGeneration = before
                                testFeedback = "QUEUED; ATT TX / recorder RX NOT confirmed by UI"
                            } else {
                                testFeedback = "NOT QUEUED; check Playback, PacketLink credit, MTU and busy state"
                            }
                            android.util.Log.i("AtomRemoteMediaU0416",
                                "U0416_TEST_TAP index=0 protoCandidate=0800 route=E0 queued=$accepted " +
                                    "attWrite=LOG_ONLY attAck=LOG_ONLY rx=UNVERIFIED " +
                                    "noAutoRetry=true noSet=true recorderConfirmed=false")
                        }
                    },
                    enabled = false, // U04.48: ambiguous 0x01FC - no live TX
                    modifier = Modifier.fillMaxWidth()
                ) { Text("LEGACY 0x01FC INDEX TEST LOCKED", fontSize = 11.sp) }

                Text("Test: $testFeedback", color = warning, fontSize = 11.sp)
                Text("Evidence 1/4: request QUEUED = ${if (singleTestQueued) "YES" else "NO"}",
                    color = Color.White, fontSize = 11.sp)
                Text("RX collector: 40s; UI pending cleanup: 41s after queue. ADB capture: >=60s.",
                    color = muted, fontSize = 10.sp)
                Text("Evidence 2/4: ATT write/ACK = verify in ADB logs (not in UI state)",
                    color = muted, fontSize = 11.sp)
                Text("Evidence 3/4: application RX is counted separately from E0 credits",
                    color = muted, fontSize = 11.sp)
                Text("Evidence 4/4: matching FileList result/filenames = UNVERIFIED",
                    color = warning, fontSize = 11.sp)
                Text("Index-zero serialization: explicit 08 00 sent in U04.15. " +
                        "Default-zero omission (empty body) remains an OFFLINE candidate, NOT a test button.",
                    color = muted, fontSize = 10.sp)
                if (mediaFileList.pending) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = accent)
                if (singleTestQueued && mediaFileList.generation > testGeneration) {
                    Text("Application RX: E0=${mediaFileList.nonLocalApplicationRx}, " +
                            "E8=${mediaFileList.localApplicationRx}, " +
                            "FileList opcode candidates=${mediaFileList.fileListOpcodeRx}",
                        color = Color.White, fontSize = 11.sp)
                    if (mediaFileList.fileListOpcodeRx > 0) {
                        Text("Candidate 0x01FC RX seen. Confirm packet ID, body and real filenames in ADB log.",
                            color = warning, fontSize = 11.sp)
                    }
                    Text("Window: ${mediaFileList.status}", color = muted, fontSize = 11.sp)
                }
                Text("NO VERIFIED FILENAMES. Silent RX never means the SSD is empty.",
                    color = warning, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("FilePlay 0x01FD and selected-clip playback: TX LOCKED.",
                    color = muted, fontSize = 11.sp)
                if (singleTestQueued && !mediaFileList.pending &&
                    mediaFileList.generation > testGeneration) {
                    OutlinedButton(onClick = {
                        singleTestQueued = false
                        operatorPlaybackConfirmed = false
                        operatorSsdConfirmed = false
                        testFeedback = "RE-ARMED; recorder checks required again"
                        android.util.Log.i("AtomRemoteMediaU0416",
                            "U0416_MANUAL_REARM noTx=true checksReset=true")
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("RE-ARM MANUALLY AFTER REVIEWING LOGS", fontSize = 10.sp)
                    }
                }

                TextButton(onClick = { showTechnical = !showTechnical }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showTechnical) "HIDE READ-ONLY DIAGNOSTICS" else "SHOW READ-ONLY DIAGNOSTICS",
                        fontSize = 11.sp)
                }
                if (showTechnical) {
                    Text("Existing GET paths only; application ACKs have not been AG2-proven.",
                        color = muted, fontSize = 11.sp)
                    OutlinedButton(
                        onClick = {
                            mediaStateGetTapped = true
                            android.util.Log.i("AtomRemoteMediaU0420",
                                "U0420_MEDIASTATE_UI_TAP cmd=0x04B5 callbackReturnsUnit=true " +
                                    "queued=UNKNOWN attAck=UNKNOWN appRx=UNKNOWN noAutoRetry=true")
                            onMediaStateGetEvidence()
                        },
                        enabled = canPlay && !mediaFileList.pending && !mediaStateGetTapped,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("MEDIA STATE GET | 0x04B5 | ONE UI TAP", fontSize = 11.sp)
                    }
                    if (mediaStateGetTapped) {
                        Text("MediaState: UI tap attempted. Existing GattClient one-shot guard " +
                                "can block subsequent requests; actual queue/TX/RX requires logcat. " +
                                "Navigating away does NOT reset that GATT guard.",
                            color = warning, fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            modeGetTapCount += 1
                            android.util.Log.i("AtomRemoteMediaU0420",
                                "U0420_MODEGET_UI_TAP cmd=0x0200 tapCount=$modeGetTapCount " +
                                    "queued=UNKNOWN attAck=UNKNOWN appRx=UNKNOWN")
                            onModeGetEvidence()
                        },
                        enabled = canPlay && !mediaFileList.pending,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("BROWSER MODE GET | 0x0200", fontSize = 11.sp)
                    }
                    Text("Mode GET taps on this screen: $modeGetTapCount (manual taps are not a duplicate bug).",
                        color = muted, fontSize = 10.sp)
                    Text("These GETs have existing session guards. UI tap is not evidence of queue, " +
                            "ATT ACK, recorder acceptance or RX. No E8 trial or new SET.",
                        color = muted, fontSize = 10.sp)
                }
            }
        }
        } // end collapsed legacy diagnostics
    }
}
