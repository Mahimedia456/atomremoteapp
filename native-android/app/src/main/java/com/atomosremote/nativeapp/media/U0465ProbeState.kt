package com.atomosremote.nativeapp.media

/** U04.65 evidence-only state. A queued command never implies recorder acceptance. */
data class U0465ProbeState(
    val name: String = "none",
    val opcode: Int? = null,
    val generation: Long = 0L,
    val phase: String = "idle", // idle, waiting, rx, timeout, blocked
    val responseOpcode: Int? = null,
    val responsePacketId: Int? = null,
    val responseLocal: Boolean? = null,
    val responseHex: String = "",
    val lastRawAtomMessages: List<String> = emptyList(),
    val consumedNames: List<String> = emptyList(),
    val status: String = "No FileBrowser diagnostics run"
)
