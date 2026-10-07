package com.atomosremote.nativeapp.backend

enum class BackendMode { LOCAL, CLOUD }

object BackendRuntimeConfig {
    // Phase 10 default remains LOCAL. Switch to CLOUD only after Vercel relay is deployed and verified.
    const val MODE_NAME = "LOCAL"
    val MODE: BackendMode get() = runCatching { BackendMode.valueOf(MODE_NAME) }.getOrDefault(BackendMode.LOCAL)

    const val LOCAL_WS_URL = "ws://127.0.0.1:8787/ws/agent"
    const val LOCAL_HTTP_URL = "http://127.0.0.1:8787/api/v1"
    const val LOCAL_AGENT_TOKEN = "change-me-local"

    // Example: https://atomremote-relay.vercel.app
    const val CLOUD_BASE_URL = "https://YOUR-ATOMREMOTE-RELAY.vercel.app"
    const val CLOUD_AGENT_TOKEN = "replace-with-cloud-agent-token"
    const val CLOUD_OWNER_TOKEN = "replace-with-cloud-owner-token"

    const val DEVICE_ID = "ag2-main"
}
