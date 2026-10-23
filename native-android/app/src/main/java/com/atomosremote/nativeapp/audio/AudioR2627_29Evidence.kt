package com.atomosremote.nativeapp.audio

/** R26.27-29: pure Audio RX diagnosis. No transport state can be changed here. */
object AudioR2627_29Evidence {
    data class Diagnosis(val kind: String, val title: String, val detail: String)
    fun diagnose(linked: Boolean, running: Boolean, methodStatus: String?,
                 collectorStatus: String, typedCurrentPage: Boolean): Diagnosis {
        if (!linked) return Diagnosis("DISCONNECTED", "Recorder not connected",
            "Current Audio state is unavailable until official TCS pairing and PacketLink are ready.")
        if (typedCurrentPage && methodStatus == "TYPED_RX")
            return Diagnosis("FRESH_RX", "Fresh AG2 Audio response received",
                "GET 0x0647 -> RX 0x8647 from this Audio collector run. Raw configuration fields only.")
        if (methodStatus == "NO_TYPED_RX_AFTER_DELIVERY")
            return Diagnosis("NO_TYPED_RX", "GET delivered, recorder reply not observed",
                "GATT delivery is NOT a 0x8647 response. No fresh settings are displayed. " +
                    "Wait for the existing collector to finish; then retry manually when other pages are idle.")
        if (methodStatus?.startsWith("DEFERRED_") == true)
            return Diagnosis("DEFERRED", "Audio request deferred by shared transport",
                "The existing owner is preserved. Nothing is force-cancelled; retry after other pages settle.")
        if (running) return Diagnosis("RUNNING", "Audio collector running",
            "Waiting for MTU / Input-Output owner or typed response. No parallel Audio request is started.")
        if (methodStatus == "WAITING_TYPED_RX")
            return Diagnosis("WAITING", "Waiting for 0x8647 recorder response",
                "Write acceptance does not prove a typed GET response.")
        return Diagnosis("PENDING", "No fresh Audio response",
            "${collectorStatus.take(96)}. Tap Refresh when paired and idle; no stale values will be presented as current.")
    }
    data class Comparison(val status: String, val changes: List<String>)
    /** No invented delta when a test lacks typed RX. */
    fun compare(before: String?, after: String?): Comparison {
        val a = AudioR2624_26Protocol.decodeObservedPage(before.orEmpty())
        val b = AudioR2624_26Protocol.decodeObservedPage(after.orEmpty())
        if (a == null || b == null) return Comparison("INCONCLUSIVE_NO_VALID_RX", emptyList())
        val diffs = AudioR2624_26Protocol.diff(before!!, after!!)
        return Comparison(if (diffs.isEmpty()) "IDENTICAL" else "CHANGED", diffs)
    }
}
