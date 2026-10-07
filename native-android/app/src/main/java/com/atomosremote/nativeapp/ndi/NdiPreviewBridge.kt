package com.atomosremote.nativeapp.ndi

import com.atomosremote.nativeapp.network.NetworkServiceCandidate

data class NdiSource(
    val key: String,
    val name: String,
    val host: String?,
    val port: Int?,
    val attributes: Map<String, String>,
    val resolved: Boolean
)

sealed class NdiPreviewState {
    data object Idle : NdiPreviewState()
    data class Ready(val source: NdiSource) : NdiPreviewState()
    data class Unavailable(val message: String) : NdiPreviewState()
}

interface NdiPreviewBridge {
    val available: Boolean
    val implementationName: String
    fun prepare(source: NdiSource): Result<NdiPreviewState>
    fun stop()
}

class SafeNdiPreviewBoundary : NdiPreviewBridge {
    override val available: Boolean = false
    override val implementationName: String = "NDI native SDK bridge not linked"

    override fun prepare(source: NdiSource): Result<NdiPreviewState> = Result.success(
        NdiPreviewState.Unavailable(
            "NDI source discovered (${source.name}), but live video decoding stays locked until the native NDI SDK is integrated and licensed."
        )
    )

    override fun stop() = Unit
}

object NdiSourceRegistry {
    fun fromNetwork(services: List<NetworkServiceCandidate>): List<NdiSource> = services
        .filter { it.serviceType.contains("ndi", ignoreCase = true) }
        .map {
            NdiSource(
                key = it.key,
                name = it.serviceName,
                host = it.host,
                port = it.port,
                attributes = it.attributes,
                resolved = it.resolved
            )
        }
}
