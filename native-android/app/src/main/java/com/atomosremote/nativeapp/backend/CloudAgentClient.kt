package com.atomosremote.nativeapp.backend

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CloudAgentClient(
    private val baseUrl:String,
    private val token:String,
    private val deviceId:String,
    private val deviceName:()->String,
    private val onCommand:(BackendAgentCommand)->Unit
) : BackendAgentTransport {
    private val jsonType="application/json; charset=utf-8".toMediaType()
    private val http=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    private val running=AtomicBoolean(false)
    private var worker:Thread?=null

    override fun start(){
        if(!running.compareAndSet(false,true)) return
        worker=Thread({ loop() },"AtomRemoteCloudAgent").also{it.start()}
    }
    override fun stop(){ running.set(false); worker?.interrupt(); worker=null }

    private fun auth(builder:Request.Builder)=builder.header("Authorization","Bearer $token")
    private fun hello(){
        val body=JSONObject().put("deviceId",deviceId).put("deviceName",deviceName()).put("model","capability-driven")
        val req=auth(Request.Builder().url("$baseUrl/api/agent/hello")).post(body.toString().toRequestBody(jsonType)).build()
        http.newCall(req).execute().use{ if(!it.isSuccessful) throw IllegalStateException("cloud hello HTTP ${it.code}") }
    }
    private fun loop(){
        while(running.get()){
            try{
                hello()
                while(running.get()){
                    val req=auth(Request.Builder().url("$baseUrl/api/agent/poll?deviceId=$deviceId")).get().build()
                    http.newCall(req).execute().use{r->
                        when(r.code){
                            204 -> Unit
                            in 200..299 -> r.body?.string()?.takeIf{it.isNotBlank()}?.let{onCommand(BackendAgentCommand.fromJson(JSONObject(it)))}
                            401 -> throw IllegalStateException("cloud agent unauthorized")
                            else -> Log.w("AtomRemoteBackend","cloud poll HTTP ${r.code}")
                        }
                    }
                    Thread.sleep(600)
                }
            }catch(t:Throwable){
                if(running.get()) Log.e("AtomRemoteBackend","cloud agent loop",t)
                try{Thread.sleep(1800)}catch(_:InterruptedException){}
            }
        }
    }
    override fun sendResult(result:BackendAgentResult){
        Thread {
            runCatching {
                val req=auth(Request.Builder().url("$baseUrl/api/agent/result")).post(result.toJson().toString().toRequestBody(jsonType)).build()
                http.newCall(req).execute().use{ if(!it.isSuccessful) error("result HTTP ${it.code}") }
            }.onFailure{Log.e("AtomRemoteBackend","cloud result upload",it)}
        }.start()
    }
}
