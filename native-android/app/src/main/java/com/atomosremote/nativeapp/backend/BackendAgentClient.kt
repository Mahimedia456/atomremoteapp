package com.atomosremote.nativeapp.backend

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local backend WebSocket agent with persistent reconnect semantics.
 *
 * The Android app may be reinstalled/relaunched, the PC backend may restart,
 * or wireless ADB may briefly interrupt networking. This client therefore keeps
 * one reconnect scheduler alive for the lifetime of the owning ViewModel and
 * re-registers ag2-main whenever the socket is lost.
 */
class BackendAgentClient(
    private val wsUrl: String,
    private val token: String,
    private val deviceId: String,
    private val deviceName: () -> String,
    private val onCommand: (BackendAgentCommand) -> Unit
) : BackendAgentTransport {

    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "AtomRemoteBackendReconnect").apply { isDaemon = true }
    }

    private val connecting = AtomicBoolean(false)

    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopped = true
    @Volatile private var registered = false
    @Volatile private var reconnectFuture: ScheduledFuture<*>? = null

    override fun start() {
        stopped = false
        Log.d(TAG, "agent start deviceId=$deviceId ws=$wsUrl")
        connectNow("start")
        ensureReconnectWatchdog()
    }

    override fun stop() {
        stopped = true
        registered = false
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        socket?.close(1000, "agent stopped")
        socket = null
        connecting.set(false)
        Log.d(TAG, "agent stop deviceId=$deviceId")
    }

    private fun ensureReconnectWatchdog() {
        if (stopped) return
        if (reconnectFuture?.isCancelled == false && reconnectFuture?.isDone == false) return
        reconnectFuture = scheduler.scheduleWithFixedDelay({
            if (!stopped && (socket == null || !registered)) {
                connectNow("watchdog")
            }
        }, 2, 2, TimeUnit.SECONDS)
    }

    private fun connectNow(reason: String) {
        if (stopped) return
        if (!connecting.compareAndSet(false, true)) return

        registered = false
        Log.d(TAG, "agent websocket connecting reason=$reason url=$wsUrl")

        val request = Request.Builder().url(wsUrl).build()
        val newSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connecting.set(false)
                socket = webSocket
                val hello = JSONObject()
                    .put("type", "agent.hello")
                    .put("token", token)
                    .put("deviceId", deviceId)
                    .put("deviceName", deviceName())
                    .put("model", "capability-driven")
                    .toString()

                val sent = webSocket.send(hello)
                Log.d(TAG, "agent websocket open helloSent=$sent deviceId=$deviceId")
                if (!sent) {
                    socket = null
                    registered = false
                    webSocket.cancel()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = JSONObject(text)
                    when (obj.optString("type")) {
                        "agent.command" -> {
                            Log.d(TAG, "agent command action=${obj.optString("action")} requestId=${obj.optString("requestId")}")
                            onCommand(BackendAgentCommand.fromJson(obj))
                        }
                        "agent.ready" -> {
                            registered = true
                            socket = webSocket
                            Log.d(TAG, "agent ready REGISTERED deviceId=$deviceId")
                        }
                        "agent.error" -> {
                            registered = false
                            Log.e(TAG, "agent server error=${obj.optString("error")}")
                        }
                        else -> Unit // Unknown WebSocket message types remain ignored.
                    }
                }.onFailure {
                    Log.e(TAG, "bad websocket message", it)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket) socket = null
                registered = false
                connecting.set(false)
                Log.e(TAG, "agent websocket failure http=${response?.code} message=${t.message}", t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) socket = null
                registered = false
                connecting.set(false)
                Log.w(TAG, "agent websocket closed code=$code reason=$reason")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                registered = false
                Log.w(TAG, "agent websocket closing code=$code reason=$reason")
            }
        })

        socket = newSocket
    }

    override fun sendResult(result: BackendAgentResult) {
        val current = socket
        if (current == null || !registered) {
            Log.e(TAG, "agent result dropped because socket not registered action=${result.action} requestId=${result.requestId}")
            connectNow("send-result-no-socket")
            return
        }

        val sent = current.send(result.toJson().toString())
        Log.d(TAG, "agent result action=${result.action} ok=${result.ok} sent=$sent requestId=${result.requestId}")
        if (!sent) {
            registered = false
            socket = null
            current.cancel()
            connectNow("send-result-failed")
        }
    }

    companion object {
        private const val TAG = "AtomRemoteBackend"
    }
}

data class BackendAgentCommand(
    val requestId: String,
    val deviceId: String,
    val action: String,
    val commandId: Int,
    val commandHex: String,
    val local: Boolean,
    val payload: JSONObject?
) {
    companion object {
        fun fromJson(o: JSONObject) = BackendAgentCommand(
            o.getString("requestId"),
            o.getString("deviceId"),
            o.getString("action"),
            o.getInt("commandId"),
            o.optString("commandHex"),
            o.optBoolean("local", false),
            o.optJSONObject("payload")
        )
    }
}

data class BackendAgentResult(
    val requestId: String,
    val deviceId: String,
    val action: String,
    val ok: Boolean,
    val data: JSONObject? = null,
    val rawPayloadHex: String? = null,
    val responseId: String? = null,
    val remoteResponse: String? = null,
    val error: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", "agent.result")
        .put("requestId", requestId)
        .put("deviceId", deviceId)
        .put("action", action)
        .put("ok", ok)
        .apply {
            data?.let { put("data", it) }
            rawPayloadHex?.let { put("rawPayloadHex", it) }
            responseId?.let { put("responseId", it) }
            remoteResponse?.let { put("remoteResponse", it) }
            error?.let { put("error", it) }
        }
}
