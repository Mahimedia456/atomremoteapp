package com.atomosremote.nativeapp.ui

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atomosremote.nativeapp.audio.AudioR26PageSnapshot
import com.atomosremote.nativeapp.audio.AudioR2697MicLineSnapshot
import com.atomosremote.nativeapp.audio.AudioR2714GainReadback
import com.atomosremote.nativeapp.audio.AudioR2716InputLevelReadback
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** B01.36: production UI only; recorder GET controls displayed state.
 * The existing GATT guards own transmission and post-SET verification.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioR2719FinalOptionLists(
    deviceKey: String,
    linked: Boolean,
    pane: String,
    onPane: (String) -> Unit,
    audioBusy: Boolean,
    audioPageHex: String,
    freshTypedAudio: Boolean,
    evidenceFlow: StateFlow<AudioR2697MicLineSnapshot>,
    onAudioRefresh: () -> Unit,
    onGainRefresh: () -> Unit,
    onGainPilot: (Boolean, Boolean) -> Unit,
    onGainValueSet: (Boolean, Int) -> Unit,
    onInputPilot: (Int) -> Unit,
    onInputRefresh: () -> Unit,
    onDelayRefresh: () -> Unit,
    onPhonesTrial: (Int) -> Boolean,
    onA12Pilot: (String, Int) -> Boolean,
    onRecordOneShot: (Int, Boolean) -> Boolean
) {
    val cyan = Color(0xFF51DBE7)
    val muted = Color(0xFF98B0B5)
    val amber = Color(0xFFDCBD86)
    val evidence by evidenceFlow.collectAsState()
    val page = remember(linked, audioPageHex) {
        if (linked) AudioR26PageSnapshot.decode(audioPageHex) else null
    }
    val gainRecord = evidence.controls["gainLeft"]
    val gainReady = linked && gainRecord?.key == "gainLeft" &&
        gainRecord.command == 0x06B6 && gainRecord.responseType == 0x86B1 &&
        gainRecord.state == "CANDIDATE_RX_UNMAPPED"
    val gain = if (gainReady) AudioR2714GainReadback.decodeHex(gainRecord!!.rawHex) else null
    val inputRecord = evidence.controls["inputLevel"]
    val inputReady = linked && inputRecord?.key == "inputLevel" &&
        inputRecord.command == 0x06B3 && inputRecord.responseType == 0x86AE &&
        inputRecord.state == "CANDIDATE_RX_UNMAPPED"
    val inputLevel = if (inputReady) AudioR2716InputLevelReadback.decodeHex(inputRecord!!.rawHex) else null
    val delayRecord = if (linked) evidence.controls["delay"] else null
    val delayFrames = if (delayRecord?.command == 0x06B9 && delayRecord.responseType == 0x86B3 &&
        delayRecord.state == "CANDIDATE_RX_UNMAPPED" &&
        Regex("08[0-9a-fA-F]{2}").matches(delayRecord.rawHex) &&
        delayRecord.rawHex.substring(2).toIntOrNull(16)?.let { it in 0..20 } == true)
        delayRecord.rawHex.substring(2).toInt(16) else null
    val gainBusy = gainRecord?.state in setOf("PENDING_TRANSPORT", "BUFFER_ACCEPTED", "ATT_WRITE_CONFIRMED", "TRANSPORT_DELIVERED")
    val inputBusy = inputRecord?.state in setOf("PENDING_TRANSPORT", "BUFFER_ACCEPTED", "ATT_WRITE_CONFIRMED", "TRANSPORT_DELIVERED")
    val source = page?.monitor?.toInt()
    val monitorText = when (source) {
        null -> "Unavailable"
        0 -> "On"
        else -> "Off"
    }
    val canControl = linked && !audioBusy && page != null
    val recCount = if (page == null) "-- / --" else
        "${page.recordEnabledList.toSet().size} / ${page.recordValidList.toSet().size}"

    // B01.37: priority GET for the shared audio source, followed by the
    // independent Mic/Line GETs. Avoid the old multi-second fixed delays.
    LaunchedEffect(deviceKey, linked, pane) {
        if (linked && deviceKey.isNotBlank()) {
            onAudioRefresh()
            if (pane == "MIC_LINE") {
                delay(180L)
                onGainRefresh()
                onInputRefresh()
                onDelayRefresh()
            }
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("RECORDER AUDIO", fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.Bold)
            Text(when {
                !linked -> "Disconnected"
                audioBusy -> "Refreshing..."
                freshTypedAudio -> "Synced with recorder"
                page != null -> "Last recorder readback"
                else -> "Readback pending"
            }, fontSize = 11.sp, color = muted)
        }
        TextButton(onClick = {
            onAudioRefresh()
            if (pane == "MIC_LINE") {
                onGainRefresh()
                onInputRefresh()
                onDelayRefresh()
            }
        }, enabled = linked && !audioBusy) { Text("Refresh", color = cyan) }
    }
    if (audioBusy) LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth().height(3.dp),
        color = cyan, trackColor = Color(0xFF244044))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("AUDIO" to "Audio", "MIC_LINE" to "Mic / Line").forEach { (key, label) ->
            Surface(modifier = Modifier.weight(1f).clickable { onPane(key) },
                color = if (pane == key) Color(0xFF10383B) else Color(0xFF0A181C),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (pane == key) cyan else Color(0xFF2E4850))) {
                Box(Modifier.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (pane == key) cyan else muted,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }

    var sheet by remember(deviceKey, pane) { mutableStateOf<String?>(null) }
    if (pane == "AUDIO") {
        R2719Section("LEVELS")
        R2719Row("Headphone Level",
            page?.let { "${it.phonesLevel} / ${it.phonesLevelMax}" } ?: "--",
            "Headphone output volume", onClick = { sheet = "phones" })
        R2719Section("CHANNELS")
        R2719Row("Headphone Source & Channel REC",
            page?.let { "🎧 ${R2719SourceLabel(it.monitor.toInt())} · REC $recCount" } ?: "--",
            "Choose headphone source or recording channels", onClick = { sheet = "analogRec" })
        R2719Section("ROUTING")
        R2719Row("Meter Orientation", page?.let {
            when (it.orientation.toInt()) { 0 -> "Vertical"; 1 -> "Horizontal"; else -> "Unavailable" }
        } ?: "--", "Vertical / Horizontal", onClick = { sheet = "orientation" })
        R2719Row("Audio Embedded", page?.let { R2719EmbeddedModeLabel(it.analogEmbed) } ?: "--",
            "Audio embedding pairs", onClick = { sheet = "embed" })
        R2719Row("Ignore Audio Dropouts", "Unavailable",
            "Recorder control not verified", onClick = { sheet = "dropouts" })
    } else {
        R2719Section("MIC / LINE LEVELS")
        R2719Row("Left Gain", gain?.leftLabel() ?: if (gainBusy) "Refreshing..." else "--",
            "−10.0 to +20.0 dB", onClick = { sheet = "gainL" })
        R2719Row("Right Gain", gain?.rightLabel() ?: if (gainBusy) "Refreshing..." else "--",
            "−10.0 to +20.0 dB", onClick = { sheet = "gainR" })
        R2719Section("INPUT & MONITORING")
        R2719Row("Audio In Level", inputLevel?.display() ?: if (inputBusy) "Refreshing..." else "--",
            "Line Level / Mic Level", onClick = { sheet = "inLevel" })
        R2719Row("Headphone Monitor", monitorText,
            "Linked to Audio headphone source", onClick = { sheet = "micMonitor" })
        R2719Row("Audio Delay", delayFrames?.let { "$it fr" } ?: "--",
            "0–20 frames", onClick = { sheet = "audioDelay" })
    }

    if (sheet != null) {
        val opened = sheet.orEmpty()
        // Staged choices are local to this sheet; recorder labels ALWAYS use GET.
        var draft by remember(deviceKey, pane, opened) { mutableIntStateOf(-9999) }
        var pendingKind by remember(deviceKey, pane, opened) { mutableStateOf<String?>(null) }
        var pendingValue by remember(deviceKey, pane, opened) { mutableIntStateOf(0) }
        var pendingRecOn by remember(deviceKey, pane, opened) { mutableStateOf(false) }
        var pendingPreviousHex by remember(deviceKey, pane, opened) { mutableStateOf("") }
        var pendingPreviousAux by remember(deviceKey, pane, opened) { mutableStateOf("") }
        var pendingStartedAtMs by remember(deviceKey, pane, opened) { mutableLongStateOf(0L) }
        var feedback by remember(deviceKey, pane, opened) { mutableStateOf("") }
        // A pending write is not a recorder-confirmed value. GET is authority.
        LaunchedEffect(audioPageHex, gainRecord?.rawHex, inputRecord?.rawHex,
            delayRecord?.rawHex, pendingKind) {
            val kind = pendingKind ?: return@LaunchedEffect
            val actual = when (kind) {
                "source", "micMonitor" -> page?.monitor?.toInt() == pendingValue &&
                    audioPageHex != pendingPreviousHex
                "phones" -> page?.phonesLevel?.toInt() == pendingValue &&
                    audioPageHex != pendingPreviousHex
                "orientation" -> page?.orientation?.toInt() == pendingValue &&
                    audioPageHex != pendingPreviousHex
                "embed" -> page?.analogEmbed?.toInt() == pendingValue &&
                    audioPageHex != pendingPreviousHex
                "rec" -> page != null &&
                    page.recordEnabledList.contains(pendingValue.toLong()) == pendingRecOn &&
                    audioPageHex != pendingPreviousHex
                "gainL" -> gain?.leftRaw?.toInt() == pendingValue &&
                    gainRecord?.rawHex != pendingPreviousAux
                "gainR" -> gain?.rightRaw?.toInt() == pendingValue &&
                    gainRecord?.rawHex != pendingPreviousAux
                "inLevel" -> inputLevel?.rawEnum?.toInt() == pendingValue &&
                    inputRecord?.rawHex != pendingPreviousAux
                "audioDelay" -> delayFrames == pendingValue &&
                    delayRecord?.rawHex != pendingPreviousAux
                else -> false
            }
            if (actual) {
                Log.i("AtomRemoteAudioB0138", "UI_GET_MATCH kind=$kind target=$pendingValue " +
                    "elapsedMs=${android.os.SystemClock.elapsedRealtime() - pendingStartedAtMs} confirmed=true")
                feedback = "Synced with recorder"
                pendingKind = null
                draft = -9999
            }
        }
        LaunchedEffect(pendingKind) {
            val key = pendingKind
            if (key != null) {
                // Read only; let SET transmit before requesting an independent GET.
                if (key in setOf("gainL", "gainR")) {
                    // MicLine gain setter has its own guarded TX window.
                    // One read-only GET is queued AFTER a short settling window;
                    // no GET should preempt the source/REC/other Audio SET paths.
                    delay(2200L)
                    onGainRefresh()
                }
                // B01.38: Never pre-empt a SET. Existing GATT post-SET GET has
                // first priority. Only if it failed to settle for several seconds,
                // request ONE guarded, READ-ONLY recovery refresh.
                delay(4200L)
                if (pendingKind == key && linked && !audioBusy && key != "audioDelay") {
                    when (key) {
                        "source", "micMonitor", "phones", "orientation", "embed", "rec" -> onAudioRefresh()
                        "gainL", "gainR" -> onGainRefresh()
                        "inLevel" -> onInputRefresh()
                        // Delay setter can remain armed while PacketLink is busy;
                        // its own post-SET GET is authoritative. Never pre-empt.
                    }
                    Log.i("AtomRemoteAudioB0138", "UI_READBACK_RECOVERY_REQUEST kind=$key " +
                        "elapsedMs=${android.os.SystemClock.elapsedRealtime() - pendingStartedAtMs} " +
                        "setTx=false")
                }
                delay(3800L)
                if (pendingKind == key) {
                    Log.w("AtomRemoteAudioB0138", "UI_GET_WAIT_TIMEOUT kind=$key " +
                        "elapsedMs=${android.os.SystemClock.elapsedRealtime() - pendingStartedAtMs} " +
                        "readbackUnverified=true noSetRetry=true")
                    feedback = "Recorder readback delayed — refresh to verify"
                    pendingKind = null
                }
            }
        }
        val title = when (opened) {
            "phones" -> "Headphone Level"
            "analogRec" -> "Headphone Source & Channel REC"
            "orientation" -> "Meter Orientation"
            "embed" -> "Audio Embedded"
            "dropouts" -> "Ignore Audio Dropouts"
            "gainL" -> "Left Gain"
            "gainR" -> "Right Gain"
            "inLevel" -> "Audio In Level"
            "micMonitor" -> "Headphone Monitor"
            "audioDelay" -> "Audio Delay"
            else -> "Audio"
        }
        val current = when (opened) {
            "phones" -> page?.phonesLevel?.toInt()
            "orientation" -> page?.orientation?.toInt()
            "embed" -> page?.analogEmbed?.toInt()
            "micMonitor" -> if (source == 0) 0 else if (source != null) 1 else null
            "gainL" -> gain?.leftRaw?.toInt()
            "gainR" -> gain?.rightRaw?.toInt()
            "inLevel" -> inputLevel?.rawEnum?.toInt()
            "audioDelay" -> delayFrames
            else -> null
        }
        val selected = if (draft != -9999) draft else current
        val canSubmit = canControl && pendingKind == null && current != null &&
            selected != null && selected != current
        fun submit(label: String, kind: String, target: Int, recOn: Boolean = false,
                   operation: () -> Boolean) {
            if (pendingKind != null) return
            val accepted = operation()
            Log.i("AtomRemoteAudioB0137", "UI_DIRECT_SET name=$label kind=$kind " +
                "target=$target accepted=$accepted optimistic=false")
            if (accepted) {
                pendingKind = kind
                pendingStartedAtMs = android.os.SystemClock.elapsedRealtime()
                pendingValue = target
                pendingRecOn = recOn
                pendingPreviousHex = audioPageHex
                pendingPreviousAux = when(kind) {
                    "gainL", "gainR" -> gainRecord?.rawHex.orEmpty()
                    "inLevel" -> inputRecord?.rawHex.orEmpty()
                    "audioDelay" -> delayRecord?.rawHex.orEmpty()
                    else -> ""
                }
                feedback = "Updating recorder…"
            } else {
                feedback = "Recorder busy or previous readback pending — refresh and retry"
            }
        }
        fun submitVoid(label: String, kind: String, target: Int, operation: () -> Unit) {
            if (pendingKind != null) return
            operation()
            pendingKind = kind
            pendingStartedAtMs = android.os.SystemClock.elapsedRealtime()
            pendingValue = target
            pendingPreviousAux = when(kind) {
                "gainL", "gainR" -> gainRecord?.rawHex.orEmpty()
                "inLevel" -> inputRecord?.rawHex.orEmpty()
                else -> ""
            }
            pendingPreviousHex = audioPageHex
            feedback = "Checking recorder…"
            Log.i("AtomRemoteAudioB0137", "UI_DIRECT_REQUEST name=$label target=$target " +
                "queuedUnverified=true optimistic=false")
        }
        ModalBottomSheet(onDismissRequest = { sheet = null },
            containerColor = Color(0xFF071B20), contentColor = Color.White) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(if (opened == "analogRec") 0.86f else 0.72f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold)
                Text("Current: " + when(opened) {
                    "phones" -> page?.phonesLevel?.toString() ?: "--"
                    "orientation" -> current?.let { if (it == 0) "Vertical" else if (it == 1) "Horizontal" else "--" } ?: "--"
                    "embed" -> page?.let { R2719EmbeddedModeLabel(it.analogEmbed) } ?: "--"
                    "micMonitor" -> monitorText
                    "gainL" -> gain?.leftLabel() ?: "--"
                    "gainR" -> gain?.rightLabel() ?: "--"
                    "inLevel" -> inputLevel?.display() ?: "--"
                    "audioDelay" -> delayFrames?.let { "$it fr" } ?: "--"
                    "analogRec" -> source?.let { R2719SourceLabel(it) } ?: "--"
                    else -> "Unavailable"
                }, color = cyan, fontSize = 13.sp)
                if (feedback.isNotBlank()) Text(feedback, color = if (pendingKind == null) muted else amber,
                    fontSize = 12.sp)
                if (pendingKind != null) LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp), color = cyan)

                when (opened) {
                    "phones" -> {
                        val min = page?.phonesLevelMin?.toInt() ?: 0
                        val max = page?.phonesLevelMax?.toInt() ?: 0
                        val target = selected ?: min
                        if (page != null && max > min && max <= 127) {
                            Text("Level $target", color = cyan, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            Slider(value = target.coerceIn(min, max).toFloat(),
                                onValueChange = { draft = it.toInt().coerceIn(min, max) },
                                onValueChangeFinished = {
                                    if (target in min..max && target != current && pendingKind == null)
                                        submit("Headphone Level", "phones", target) { onPhonesTrial(target) }
                                },
                                valueRange = min.toFloat()..max.toFloat(),
                                enabled = canControl && pendingKind == null)
                            Text("Release slider to set and verify", color = muted, fontSize = 11.sp)
                        } else Text("Recorder level range unavailable", color = muted)
                    }
                    "orientation" -> {
                        listOf(0 to "Vertical", 1 to "Horizontal").forEach { (id, label) ->
                            R2719Choice(label, selected = current == id,
                                greyedOut = page?.orientationChangeable == 0L || !canControl || pendingKind != null,
                                onClick = {
                                    if (id != current) submit("Meter Orientation", "orientation", id) {
                                        onA12Pilot("orientation", id)
                                    }
                                })
                        }
                    }
                    "embed" -> {
                        listOf(0 to "Off", 1 to "1/2", 2 to "3/4", 3 to "5/6", 4 to "7/8")
                            .forEach { (id, label) ->
                                val enabledMode = id in 0..1 &&
                                    (page?.analogEmbedList?.contains(id.toLong()) == true)
                                R2719Choice(label, selected = current == id,
                                    greyedOut = !enabledMode || !canControl || pendingKind != null,
                                    note = if (!enabledMode) "Unavailable" else "",
                                    onClick = if (enabledMode) ({
                                        if (id != current) submit("Audio Embedded", "embed", id) {
                                            onA12Pilot("embed", id)
                                        }
                                    }) else null)
                            }
                    }
                    "analogRec" -> {
                        Text("🎧 selects one headphone source. REC controls recording independently.",
                            color = muted, fontSize = 11.sp)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("CHANNEL", color = muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text("🎧", color = cyan, modifier = Modifier.width(52.dp))
                            Text("REC", color = cyan, modifier = Modifier.width(116.dp))
                        }
                        val rows = listOf(
                            Triple(0, "Mic / Line", listOf(0, 1)),
                            Triple(1, "REC ID 2", listOf(2)),
                            Triple(2, "Channels 5/6", listOf(3)),
                            Triple(3, "REC ID 4", listOf(4)),
                            Triple(4, "REC ID 7", listOf(7))
                        )
                        rows.forEach { (sourceId, rawLabel, recIds) ->
                            val label = if (sourceId == 0 && page != null)
                                R2719EmbeddedInputLabel(page.analogEmbed) else rawLabel
                            val sourceAvailable = page?.monitorList?.contains(sourceId.toLong()) == true
                            val sourceActive = source == sourceId
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(label, color = Color.White, fontSize = 12.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                                R2719RoundAction("🎧", selected = sourceActive && sourceAvailable,
                                    enabled = canControl && sourceAvailable && !sourceActive && pendingKind == null,
                                    colorOn = Color(0xFF4E6C75), onClick = {
                                        submit("Headphone source $sourceId", "source", sourceId) {
                                            onA12Pilot("headphoneSource", sourceId)
                                        }
                                    })
                                recIds.forEach { recId ->
                                    val valid = page?.recordValidList?.contains(recId.toLong()) == true
                                    val active = page?.recordEnabledList?.contains(recId.toLong()) == true
                                    val recText = if (sourceId == 0)
                                        if (recId == 0) "L REC" else "R REC" else "REC"
                                    R2719RoundAction(recText, selected = valid && active,
                                        enabled = canControl && valid && pendingKind == null,
                                        onClick = {
                                            submit("Channel REC $recId", "rec", recId, !active) {
                                                onRecordOneShot(recId, !active)
                                            }
                                        })
                                }
                                if (recIds.size == 1) Spacer(Modifier.width(44.dp))
                            }
                            HorizontalDivider(color = Color(0xFF1C353C))
                        }
                        Text("Unavailable channel names remain identified by recorder ID.",
                            color = muted, fontSize = 11.sp)
                    }
                    "micMonitor" -> {
                        val isOn = source == 0
                        listOf(0 to "On", 1 to "Off").forEach { (id, label) ->
                            R2719Choice(label, selected = current == id,
                                greyedOut = !canControl || pendingKind != null,
                                onClick = {
                                    if (id != current) submit("Mic/Line Monitor", "micMonitor", id) {
                                        onA12Pilot("headphoneMonitor", id)
                                    }
                                })
                        }
                        Text("On selects headphone source 0; Off selects source 1 only when currently On.",
                            color = muted, fontSize = 11.sp)
                    }
                    "gainL", "gainR" -> {
                        val isRight = opened == "gainR"
                        val target = selected ?: 0
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly) {
                            OutlinedButton(onClick = {
                                val next = (target - 1).coerceAtLeast(-20)
                                if (next != current) submitVoid("Gain", opened, next) {
                                    onGainValueSet(isRight, next)
                                }
                            }, enabled = canControl && gainReady && target > -20 && pendingKind == null) { Text("−") }
                            Text(java.lang.String.format(java.util.Locale.US,"%+.1f dB", target / 2.0),
                                fontSize = 22.sp, color = cyan)
                            OutlinedButton(onClick = {
                                val next = (target + 1).coerceAtMost(40)
                                if (next != current) submitVoid("Gain", opened, next) {
                                    onGainValueSet(isRight, next)
                                }
                            }, enabled = canControl && gainReady && target < 40 && pendingKind == null) { Text("+") }
                        }
                        Text("Each tap sends one 0.5 dB step; wait for recorder GET.",
                            color = muted, fontSize = 11.sp)
                    }
                    "inLevel" -> {
                        listOf(1 to "Line Level", 2 to "Mic Level").forEach { (id, label) ->
                            R2719Choice(label, selected = current == id,
                                greyedOut = !canControl || !inputReady || pendingKind != null,
                                onClick = {
                                    if (id != current) submitVoid("Audio In Level", "inLevel", id) {
                                        onInputPilot(id)
                                    }
                                })
                        }
                    }
                    "audioDelay" -> {
                        val target = selected ?: 0
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly) {
                            OutlinedButton(onClick = {
                                val next = (target - 1).coerceAtLeast(0)
                                if (next != current) submit("Audio Delay", "audioDelay", next) {
                                    onA12Pilot("delay", next)
                                }
                            }, enabled = canControl && delayFrames != null && target > 0 && pendingKind == null) { Text("−") }
                            Text("$target fr", fontSize = 22.sp, color = cyan)
                            OutlinedButton(onClick = {
                                val next = (target + 1).coerceAtMost(20)
                                if (next != current) submit("Audio Delay", "audioDelay", next) {
                                    onA12Pilot("delay", next)
                                }
                            }, enabled = canControl && delayFrames != null && target < 20 && pendingKind == null) { Text("+") }
                        }
                        Text("One frame per tap; verified by independent GET.", color = muted, fontSize = 11.sp)
                    }
                    "dropouts" -> {
                        Text("Recorder readback and SET are not verified for this control.",
                            color = muted, fontSize = 13.sp)
                    }
                }
                Text("The displayed recorder value updates after a confirmed readback.",
                    color = muted, fontSize = 11.sp)
                TextButton(onClick = { sheet = null }) { Text("Close", color = cyan) }
            }
        }
    }
}

@Composable
private fun R2719RoundAction(text: String, selected: Boolean, enabled: Boolean,
    onClick: () -> Unit, colorOn: Color = Color(0xFFB9656B)) {
    Surface(modifier = Modifier.size(44.dp).clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = if (selected) colorOn else Color(0xFF33373A),
        border = BorderStroke(1.dp, if (selected) Color.White else Color(0xFF5B656A))) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = if (selected || enabled) Color.White else Color(0xFF87969A),
                fontWeight = FontWeight.Bold, fontSize = if (text.contains("REC")) 9.sp else 18.sp)
        }
    }
}

@Composable
private fun R2719Section(label: String) {
    Text(label, modifier = Modifier.padding(top = 15.dp, bottom = 3.dp),
        color = Color(0xFF92A9AD), fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun R2719Row(label: String, value: String, subtitle: String, onClick: () -> Unit) {
    val cyan = Color(0xFF51DBE7)
    Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Color(0xFF06161B), shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF30474E))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("◇", color = cyan, fontSize = 21.sp)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = Color.White, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(subtitle, color = Color(0xFF8FA7AB), fontSize = 10.sp)
            }
            Spacer(Modifier.width(7.dp))
            Text(value, color = cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 134.dp))
            Spacer(Modifier.width(8.dp))
            Text("›", color = Color.White, fontSize = 24.sp)
        }
    }
}

@Composable
private fun R2719Choice(label: String, selected: Boolean,
                        greyedOut: Boolean = false, note: String = "",
                        onClick: (() -> Unit)? = null) {
    val cyan = Color(0xFF51DBE7)
    val color = when {
        greyedOut -> Color(0xFF7A8588)
        selected -> cyan
        else -> Color(0xFFCDD8D9)
    }
    Surface(modifier = Modifier.fillMaxWidth().then(
        if (onClick != null && !greyedOut) Modifier.clickable(onClick = onClick) else Modifier
    ), color = if (greyedOut)
        Color(0xFF0A1619) else if (selected) Color(0xFF11383C) else Color(0xFF0A2227),
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, if (selected) cyan else Color(0xFF2C474D))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (selected) "◉" else "○", color = color, fontSize = 16.sp)
            Spacer(Modifier.width(12.dp))
            Text(label, modifier = Modifier.weight(1f), color = color,
                fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (note.isNotEmpty()) Text(note, color = Color(0xFF8FA5A9), fontSize = 10.sp)
        }
    }
}

private fun R2719SourceLabel(id: Int): String = if (id == 0) "Mic / Line" else "Source $id"

// Unmapped values are descriptive candidates, not verified channel names.
private fun R2719EmbeddedModeLabel(raw: Long): String = when (raw) {
    0L -> "Off"
    1L -> "1/2"
    2L -> "3/4 (unverified)"
    3L -> "5/6 (unverified)"
    4L -> "7/8 (unverified)"
    else -> "Embedded ID $raw"
}

private fun R2719EmbeddedInputLabel(raw: Long): String = when (raw) {
    0L -> "Mic / Line L/R"
    1L -> "Embedded 1/2"
    2L -> "Embedded 3/4 (unverified)"
    3L -> "Embedded 5/6 (unverified)"
    4L -> "Embedded 7/8 (unverified)"
    else -> "Input ID $raw"
}
