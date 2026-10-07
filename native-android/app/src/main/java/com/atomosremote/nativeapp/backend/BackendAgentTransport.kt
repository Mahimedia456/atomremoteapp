package com.atomosremote.nativeapp.backend

interface BackendAgentTransport {
    fun start()
    fun stop()
    fun sendResult(result: BackendAgentResult)
}
