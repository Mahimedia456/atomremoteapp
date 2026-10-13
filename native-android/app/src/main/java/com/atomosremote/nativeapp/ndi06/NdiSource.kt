package com.atomosremote.nativeapp.ndi06

/** NDI-06A: a DNS-SD advertisement, NOT a proven decodable media endpoint. */
data class NdiSource(
    val name: String,
    val host: String,
    val advertisedPort: Int,
    val txt: Map<String, String>
) {
    val displayName: String get() = name.ifBlank { "Unnamed NDI service" }
}
