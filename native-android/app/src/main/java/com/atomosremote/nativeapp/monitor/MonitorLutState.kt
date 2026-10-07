package com.atomosremote.nativeapp.monitor

data class MonitorLutState(
    val currentSlot: Int? = null,
    val slotListReported: Boolean = false,
    val previewAvailable: Boolean? = null,
    val previewEnabled: Boolean? = null,
    val recordBurnInAvailable: Boolean? = null,
    val recordBurnInEnabled: Boolean? = null,
    val outputLutAvailable: Boolean? = null,
    val outputLutEnabled: Boolean? = null,
    val requestedSlot: Int? = null,
    val requestedPreviewEnabled: Boolean? = null,
    val requestedRecordBurnInEnabled: Boolean? = null,
    val requestedOutputLutEnabled: Boolean? = null,
    val pendingKey: String? = null,
    val finalReady: Boolean = false,
    val currentSlotQueryCompleted: Boolean = false,
    val status: String = "LUT state not read yet"
) {
    val shownSlot: Int? get() = requestedSlot ?: currentSlot
    val shownPreviewEnabled: Boolean? get() = requestedPreviewEnabled ?: previewEnabled
    val shownRecordBurnInEnabled: Boolean? get() = requestedRecordBurnInEnabled ?: recordBurnInEnabled
    val shownOutputLutEnabled: Boolean? get() = requestedOutputLutEnabled ?: outputLutEnabled

    fun currentLabel(): String = when (shownSlot) {
        null -> "Not reported"
        0 -> "No LUT selected"
        in 1..8 -> "Slot $shownSlot"
        else -> "Slot $shownSlot"
    }
}
