package com.atomosremote.nativeapp.output

/** OUTPUT03: passive hardware readback cache. No fake defaults or SET calls. */
data class OutputSessionSnapshot(
    val address: String? = null,
    val connected: Boolean = false,
    val outputInfo: String? = null,
    val hdrTypeId: Int? = null,
    val gamutId: Int? = null,
    val rawSummaryAvailable: Boolean = false,
    val status: String = "Waiting for recorder"
)
