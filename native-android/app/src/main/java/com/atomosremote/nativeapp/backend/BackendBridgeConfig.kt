package com.atomosremote.nativeapp.backend

object BackendBridgeConfig {
    // For Android emulator use 10.0.2.2. For a physical phone use the PC LAN IP.
    // Example: ws://127.0.0.1:8787/ws/agent
    const val DEFAULT_WS_URL = "ws://127.0.0.1:8787/ws/agent"
    const val DEFAULT_HTTP_URL = "http://127.0.0.1:8787/api/v1"
    const val AGENT_TOKEN = "change-me-local"
    const val DEVICE_ID = "ag2-main"
}
