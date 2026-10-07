package com.atomosremote.nativeapp.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** REST reader used by the Input UI once Phase 06 is enabled. */
class BackendInputRepository(
    private val apiBase: String = BackendBridgeConfig.DEFAULT_HTTP_URL,
    private val deviceId: String = BackendBridgeConfig.DEVICE_ID
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun getInput(): JSONObject = get("/devices/$deviceId/input")
    suspend fun getInputHdr(): JSONObject = get("/devices/$deviceId/input/hdr")
    suspend fun getInputSummary(): JSONObject = get("/devices/$deviceId/input/summary")

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(apiBase.trimEnd('/') + path).get().build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Backend HTTP ${response.code}: $text")
            JSONObject(text)
        }
    }
}
