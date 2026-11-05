package com.atomosremote.nativeapp.backend

import android.util.Log
import com.atomosremote.nativeapp.ble.GattClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class BackendAgentCoordinator(
    private val gattClient: GattClient,
    private val scope: CoroutineScope,
    private val deviceId: String = BackendRuntimeConfig.DEVICE_ID
) {
    private val commandQueue = Channel<BackendAgentCommand>(Channel.UNLIMITED)
    private var workerJob: Job? = null
    private val transport: BackendAgentTransport = when (BackendRuntimeConfig.MODE) {
        BackendMode.LOCAL -> BackendAgentClient(
            BackendRuntimeConfig.LOCAL_WS_URL, BackendRuntimeConfig.LOCAL_AGENT_TOKEN, deviceId,
            { gattClient.connectedRecorderName.value ?: "ATOMOS Recorder" }, ::handleCommand
        )
        BackendMode.CLOUD -> CloudAgentClient(
            BackendRuntimeConfig.CLOUD_BASE_URL, BackendRuntimeConfig.CLOUD_AGENT_TOKEN, deviceId,
            { gattClient.connectedRecorderName.value ?: "ATOMOS Recorder" }, ::handleCommand
        )
    }

    fun start() {
        if (workerJob?.isActive != true) {
            workerJob = scope.launch {
                for (command in commandQueue) {
                    Log.d("AtomRemoteBackend", "agent execute action=${command.action} requestId=${command.requestId}")
                    executeCommand(command)
                }
            }
        }
        transport.start()
    }

    fun stop() {
        transport.stop()
        workerJob?.cancel()
        workerJob = null
    }

    private fun handleCommand(command: BackendAgentCommand) {
        val queued = commandQueue.trySend(command)
        if (queued.isSuccess) {
            Log.d("AtomRemoteBackend", "agent queued action=${command.action} requestId=${command.requestId}")
        } else {
            transport.sendResult(BackendAgentResult(command.requestId, deviceId, command.action, false, error = "Agent command queue unavailable"))
        }
    }

    private suspend fun executeCommand(command: BackendAgentCommand) {
            when (command.action) {
                "media.filebrowser.evidence.snapshot" -> runFileBrowserEvidenceSnapshot(command)
                "input.page.get","input.hdr.get","input.summary.get" -> runInputCollector(command)
                "input.source.get" -> runInputField(command, "source", "0x0064")
                "input.hdrState.get" -> runInputField(command, "hdrState", "0x006D")
                "input.deviceName.get" -> runInputField(command, "deviceName", "0x006C")
                "state.application.get","state.monitor.get","state.input.get","state.record.get","state.output.get",
                "state.inputHdr.get","state.playbackFormat.get","state.audioMeters.get","state.inputOutput.get","state.monitorAssist.get" -> runStateRefresh(command)
                "monitor.focus.set" -> runBoolean(command) { gattClient.sendOfficialMonitorAssistToggle("focus-assist", it) }
                "monitor.zebra.set" -> runBoolean(command) { gattClient.sendOfficialMonitorAssistToggle("zebra", it) }
                "monitor.falseColor.set" -> runBoolean(command) { gattClient.sendOfficialMonitorAssistToggle("false-color", it) }
                "monitor.blueOnly.set" -> runBoolean(command) { gattClient.sendOfficialMonitorAssistToggle("blue-only", it) }
                "scopes.enabled.set" -> runBoolean(command) { gattClient.sendOfficialScopeEnabled(it) }
                "scopes.mode.set" -> runString(command,"mode") { gattClient.sendOfficialScopeMode(it) }
                "scopes.size.set" -> runString(command,"size") { gattClient.sendOfficialScopeSize(it) }
                "guides.frame.enabled.set" -> runBoolean(command) { gattClient.sendOfficialFrameGuideEnabled(it) }
                "guides.safeArea.set" -> runInt(command,"mode") { gattClient.sendOfficialSafeAreaMode(it) }
                "monitor.anamorphic.set" -> runInt(command,"mode") { gattClient.sendOfficialAnamorphicMode(it) }
                "monitor.mode.set" -> runInt(command,"mode") { gattClient.sendOfficialMonitorMode(it) }
                else -> sendFail(command,"Action not wired in Phase 07-09")
            }
    }

    // U04.54 read-only bridge. A snapshot is evidence of app state, NOT a fresh recorder reply.
    // This action does not call refresh, queueRemotePacket, BLE, FilePlay or ModeStart.
    private fun runFileBrowserEvidenceSnapshot(command: BackendAgentCommand) {
        val s = gattClient.mediaSsdFileListState.value
        val entries = org.json.JSONArray()
        for (row in s.entries.take(256)) {
            entries.put(JSONObject().put("index", row.index).put("filename", row.filename))
        }
        val data = JSONObject()
            .put("origin", "ANDROID_GATT_FILEBROWSER_STATEFLOW_CACHE")
            .put("evidenceTimestampEpochMs", System.currentTimeMillis())
            .put("recorderTxOnThisRequest", false)
            .put("hardwareVerifiedForThisApiCall", false)
            .put("apiResultMeaning", "STATE_SNAPSHOT_RECEIVED_NOT_FRESH_RECORDER_PROOF")
            .put("status", s.status)
            .put("generation", s.generation)
            .put("pending", s.pending)
            .put("requestedStartIndex", s.requestedStartIndex)
            .put("startIndex", s.startIndex)
            .put("totalFiles", s.totalFiles ?: JSONObject.NULL)
            .put("responseOpcode", s.responseOpcode?.let { "0x" + it.toString(16).uppercase().padStart(4, '0') } ?: JSONObject.NULL)
            .put("lastApplicationOpcode", s.lastApplicationOpcode?.let { "0x" + it.toString(16).uppercase().padStart(4, '0') } ?: JSONObject.NULL)
            .put("localApplicationRx", s.localApplicationRx)
            .put("nonLocalApplicationRx", s.nonLocalApplicationRx)
            .put("fileListOpcodeRx", s.fileListOpcodeRx)
            .put("recorderValidatedByExistingState", s.recorderValidated)
            .put("entryCount", s.entries.size)
            .put("entries", entries)
        transport.sendResult(
            BackendAgentResult(
                command.requestId, deviceId, command.action, true,
                data = data, remoteResponse = "cached-filebrowser-state-no-recorder-tx"
            )
        )
    }

    private suspend fun runInputCollector(command:BackendAgentCommand) {
        val before=gattClient.inputCodecCollector.value.generation
        if(!gattClient.refreshGranularInputState()){sendFail(command,"Input collector blocked: recorder not PacketLink-ready");return}
        val s=withTimeoutOrNull(25_000){gattClient.inputCodecCollector.first{it.generation>before && !it.running}}
        if(s==null){sendFail(command,"Input collector timeout");return}
        val collector=runCatching{JSONObject(s.json)}.getOrElse{JSONObject().put("generation",s.generation).put("input",JSONObject(s.inputValues))}
        val data=JSONObject().put("collector",collector).put("inputFormatInfo",gattClient.inputFormatInfo.value).put("inputCodecId",gattClient.inputCodecId.value)
            .put("inputCameraId",gattClient.inputCameraId.value).put("inputGammaId",gattClient.inputGammaId.value).put("inputGamutId",gattClient.inputGamutId.value)
            .put("outputInfo",gattClient.outputInfo.value).put("outputHdrTypeId",gattClient.outputHdrTypeId.value).put("outputGamutId",gattClient.outputGamutId.value)
        transport.sendResult(BackendAgentResult(command.requestId,deviceId,command.action,true,data=data,rawPayloadHex=gattClient.inputOutputRawHex.value,responseId=command.commandHex,remoteResponse="collector-complete"))
    }



    // AGENT7 20.03.5.2 semantic adapter over frozen 20.03.4.13 values.
    // Raw protocol parsing is intentionally outside GattClient.
    private fun rawInputId(value:String):Int? {
        val m=Regex("""\bID\s+(\d+)\b""").find(value)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: value.trim().toIntOrNull()
    }

    private fun cleanInputPrintable(value:String):String {
        val parts=value.split(" · "," Ã‚Â· ")
        return parts.firstOrNull {
            it.isNotBlank() &&
            !it.startsWith("type 0x",ignoreCase=true) &&
            !it.startsWith("ID ",ignoreCase=true) &&
            !it.startsWith("raw ",ignoreCase=true)
        }?.trim() ?: value.substringBefore(" · raw ").substringBefore(" Ã‚Â· raw ").trim()
    }

    private fun inputSemanticData(key:String, value:String):JSONObject {
        val data=JSONObject()
        val id=rawInputId(value)
        when(key){
            "source" -> {
                val names=mapOf(
                    0 to "HDMI", 1 to "SDI A", 2 to "SDI B", 3 to "SDI Dual Link",
                    4 to "SDI Quad Link", 5 to "SDI Multistream",
                    6 to "SDI Auto Select", 7 to "HDMI Multistream"
                )
                data.put("displayName","Input Source")
                data.put("value",names[id] ?: cleanInputPrintable(value))
                if(id!=null) data.put("valueId",id)
                data.put("enumType","InputSource")
                data.put("mappingSource","official-macOS-AtomProtobuf-enum-order")
            }
            "hdrState" -> {
                data.put("displayName","HDR")
                data.put("value",when(id){0->"Off";1->"On";else->cleanInputPrintable(value)})
                if(id!=null) data.put("valueId",id)
                data.put("protobufField","hdrEnabled")
                data.put("mappingSource","official-macOS-InputHDRLogStateMsg")
            }
            "deviceName" -> {
                data.put("displayName","Device Name")
                data.put("value",cleanInputPrintable(value))
                data.put("protobufField","deviceName")
                data.put("mappingSource","official-macOS-InputDeviceNameMsg")
            }
            else -> {
                data.put("displayName",key)
                data.put("value",cleanInputPrintable(value))
                if(id!=null) data.put("valueId",id)
            }
        }
        return data
    }

    private suspend fun runInputField(command:BackendAgentCommand, key:String, responseId:String) {
        // AGENT7 20.03.5.3 BACKEND CACHE-ONLY INPUT
        //
        // Input backend reads must never start a new PacketLink collector. The
        // frozen 20.03.4.13 session already performs its proven one-time Input
        // readback. Backend only projects that hardware-backed snapshot.
        val s=gattClient.inputCodecCollector.value
        val raw=s.inputValues[key]

        if(
            raw.isNullOrBlank() ||
            raw.startsWith("NO_RESPONSE") ||
            raw.startsWith("REJECT") ||
            raw=="Not reported"
        ){
            sendFail(
                command,
                "No hardware-backed cached Input value for $key. Backend did not start a new recorder transaction."
            )
            return
        }

        val semantic=inputSemanticData(key,raw)
        val data=JSONObject()
            .put("setting",key)
            .put("displayName",semantic.optString("displayName",key))
            .put("value",semantic.optString("value",raw))
            .put("generation",s.generation)
            .put("inputResponses",s.inputResponses)
            .put("inputExpected",s.inputExpected)
            .put("hardwareConfirmed",true)
            .put("readbackSource","frozen-20.03.4.13-session-cache")
            .put("collectorReuse",true)
            .put("packetLinkReadyAtResponse",gattClient.tcsRemoteState.value.available)
            .put("editable",false)
            .put("editPolicy","READ_ONLY_UNTIL_EXACT_SET_PLUS_READBACK_IS_PROVEN")

        if(semantic.has("valueId")) data.put("valueId",semantic.getInt("valueId"))
        if(semantic.has("enumType")) data.put("enumType",semantic.getString("enumType"))
        if(semantic.has("protobufField")) data.put("protobufField",semantic.getString("protobufField"))
        if(semantic.has("mappingSource")) data.put("mappingSource",semantic.getString("mappingSource"))

        transport.sendResult(
            BackendAgentResult(
                command.requestId,
                deviceId,
                command.action,
                true,
                data=data,
                rawPayloadHex=gattClient.inputOutputRawHex.value,
                responseId=responseId,
                remoteResponse="frozen-input-cache-readback"
            )
        )
    }


    private suspend fun runStateRefresh(command:BackendAgentCommand) {
        // U04.52: A state snapshot is not proof of recorder readback.
        // GATT/PacketLink TX and all other agent actions remain unchanged.
        val before = gattClient.remoteStateSnapshot.value.generation
        if (!gattClient.refreshOfficialRecorderState()) {
            sendFail(command, "State refresh blocked: recorder not PacketLink-ready")
            return
        }
        val s = withTimeoutOrNull(12_000) {
            gattClient.remoteStateSnapshot.first {
                it.generation > before && (it.cycleComplete || it.pages.isNotEmpty())
            }
        } ?: gattClient.remoteStateSnapshot.value

        // The MAC13 collector can send a general refresh for ANY state action.
        // A fresh snapshot or some other page is NOT proof of this requested page.
        val fresh = s.generation > before
        val requestedPage = if (fresh) s.pages[command.commandId] else null
        val typedPageObserved = requestedPage != null &&
            requestedPage.rawHex.isNotBlank() &&
            requestedPage.responseType != 0xFFFE &&
            requestedPage.responseType != 0xFFFF

        val snapshot = runCatching { JSONObject(s.json) }.getOrElse {
            JSONObject().put("generation", s.generation)
                .put("receivedPages", s.receivedPages)
                .put("cycleComplete", s.cycleComplete)
        }
        val data = JSONObject()
            .put("requestedCommandId", command.commandHex)
            .put("beforeGeneration", before)
            .put("generation", s.generation)
            .put("freshGeneration", fresh)
            .put("cycleComplete", s.cycleComplete)
            .put("receivedPages", s.receivedPages)
            .put("expectedPages", s.expectedPages)
            .put("requestedTypedPageObserved", typedPageObserved)
            // The snapshot has no API-request-to-packet-id correlation.
            .put("packetMatchedToApiRequest", false)
            .put("hardwareVerified", false)
            .put("snapshot", snapshot)

        if (!typedPageObserved) {
            data.put("appPollingPayloadStatus", "NO_FRESH_TYPED_REQUESTED_PAGE")
            data.put("transportStatus", "SNAPSHOT_ONLY_NOT_RECORDER_READBACK")
            transport.sendResult(
                BackendAgentResult(
                    command.requestId, deviceId, command.action, false,
                    data = data,
                    remoteResponse = "state-snapshot-unverified",
                    error = "NO_FRESH_TYPED_RECORDER_PAGE for ${command.commandHex}; " +
                        "pages=${s.receivedPages}/${s.expectedPages}; packet response unverified"
                )
            )
            return
        }

        // Useful recorder-origin typed page was observed but full packet-ID
        // correlation to the specific API call is still not proven.
        data.put("appPollingPayloadStatus", "TYPED_REQUESTED_PAGE_OBSERVED")
        data.put("transportStatus", "TYPED_PAGE_OBSERVED_NOT_API_PACKET_CORRELATED")
        transport.sendResult(
            BackendAgentResult(
                command.requestId, deviceId, command.action, true,
                data = data,
                rawPayloadHex = requestedPage!!.rawHex,
                responseId = "0x${requestedPage.responseType.toString(16).uppercase().padStart(4, '0')}",
                remoteResponse = "typed-page-observed-not-request-correlated"
            )
        )
    }

    private fun runBoolean(c:BackendAgentCommand, fn:(Boolean)->Boolean){ val v=c.payload?.optBoolean("enabled",false)?:false; sendQueued(c,fn(v),JSONObject().put("enabled",v)) }
    private fun runString(c:BackendAgentCommand,key:String,fn:(String)->Boolean){ val v=c.payload?.optString(key,"")?:""; if(v.isBlank()){sendFail(c,"Missing $key");return}; sendQueued(c,fn(v),JSONObject().put(key,v)) }
    private fun runInt(c:BackendAgentCommand,key:String,fn:(Int)->Boolean){ if(c.payload==null||!c.payload.has(key)){sendFail(c,"Missing $key");return}; val v=c.payload.optInt(key,Int.MIN_VALUE); if(v==Int.MIN_VALUE){sendFail(c,"Invalid $key");return}; sendQueued(c,fn(v),JSONObject().put(key,v)) }
    private fun sendQueued(c:BackendAgentCommand,queued:Boolean,data:JSONObject){ transport.sendResult(BackendAgentResult(c.requestId,deviceId,c.action,queued,data=data,responseId=c.commandHex,remoteResponse=if(queued)"queued-to-existing-gatt" else null,error=if(queued)null else "Existing GattClient rejected/blocked command")); Log.d("AtomRemoteBackend","${c.action} queued=$queued") }
    private fun sendFail(c:BackendAgentCommand,error:String)=transport.sendResult(BackendAgentResult(c.requestId,deviceId,c.action,false,error=error))
}
