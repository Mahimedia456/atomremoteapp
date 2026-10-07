package com.atomosremote.nativeapp.backend

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class BackendRemoteRepository(
    private val deviceId:String=BackendRuntimeConfig.DEVICE_ID
) {
    private val http=OkHttpClient()
    private val jsonType="application/json; charset=utf-8".toMediaType()
    private val cloud get() = BackendRuntimeConfig.MODE == BackendMode.CLOUD
    private val baseUrl get() = if(cloud) BackendRuntimeConfig.CLOUD_BASE_URL+"/api/v1" else BackendRuntimeConfig.LOCAL_HTTP_URL

    fun getState(page:String):JSONObject=get("/devices/$deviceId/state/$page")
    fun getInput():JSONObject=get("/devices/$deviceId/input")
    fun getInputHdr():JSONObject=get("/devices/$deviceId/input/hdr")
    fun getInputSummary():JSONObject=get("/devices/$deviceId/input/summary")
    fun listDevices():JSONObject=get("/devices")
    fun setFocus(enabled:Boolean)=post("/devices/$deviceId/monitor/focus",JSONObject().put("enabled",enabled))
    fun setZebra(enabled:Boolean)=post("/devices/$deviceId/monitor/zebra",JSONObject().put("enabled",enabled))
    fun setFalseColor(enabled:Boolean)=post("/devices/$deviceId/monitor/false-color",JSONObject().put("enabled",enabled))
    fun setBlueOnly(enabled:Boolean)=post("/devices/$deviceId/monitor/blue-only",JSONObject().put("enabled",enabled))
    fun setScopeEnabled(enabled:Boolean)=post("/devices/$deviceId/scopes/enabled",JSONObject().put("enabled",enabled))
    fun setScopeMode(mode:String)=post("/devices/$deviceId/scopes/mode",JSONObject().put("mode",mode))
    fun setScopeSize(size:String)=post("/devices/$deviceId/scopes/size",JSONObject().put("size",size))
    fun setMonitorMode(mode:Int)=post("/devices/$deviceId/monitor/mode",JSONObject().put("mode",mode))
    fun setSafeArea(mode:Int)=post("/devices/$deviceId/guides/safe-area",JSONObject().put("mode",mode))
    fun setAnamorphic(mode:Int)=post("/devices/$deviceId/monitor/anamorphic",JSONObject().put("mode",mode))

    private fun builder(path:String)=Request.Builder().url(baseUrl+path).apply {
        if(cloud) header("Authorization","Bearer ${BackendRuntimeConfig.CLOUD_OWNER_TOKEN}")
    }
    private fun get(path:String):JSONObject=http.newCall(builder(path).get().build()).execute().use{r->JSONObject(r.body?.string().orEmpty())}
    private fun post(path:String,body:JSONObject):JSONObject=http.newCall(builder(path).post(body.toString().toRequestBody(jsonType)).build()).execute().use{r->JSONObject(r.body?.string().orEmpty())}
}
