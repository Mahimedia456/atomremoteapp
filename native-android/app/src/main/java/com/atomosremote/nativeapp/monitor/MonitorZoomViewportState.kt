package com.atomosremote.nativeapp.monitor

data class MonitorZoomViewportState(
    val ready: Boolean = false,
    val pannable: Boolean = false,
    val pendingPan: Boolean = false,
    val status: String = "Viewport not reported"
)
