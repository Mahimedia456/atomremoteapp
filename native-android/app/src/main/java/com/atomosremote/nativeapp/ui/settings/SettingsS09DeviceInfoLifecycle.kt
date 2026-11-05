package com.atomosremote.nativeapp.ui.settings

/**
 * S09: read-only diagnostic of existing RemoteApi bootstrap GET lifecycle.
 * This DOES NOT request any new opcode or modify GATT, scheduler, SET, NDI, or Remote.
 * Note: E0 is flow credit only, never a typed DeviceInfo response.
 */
internal object SettingsS09DeviceInfoLifecycle {
    internal data class Trace(
        val testStarted: Boolean = false,
        val refreshPath: String = "Not tested",
        val bootstrapStarted: Boolean = false,
        val queryPacketId: Int? = null,
        val transportTickCount: Int = 0,
        val transportDelivered: Boolean = false,
        val payloadTimeout: Boolean = false,
        val unrelatedPayloadCount: Int = 0
    )

    private val mac13Query = Regex("""\bMAC13 PACKET label=DeviceInfoGet cmd=0x4B0 packetId=(\d+)\b""")
    private val query = Regex("""\bBOOTSTRAP QUERY label=DeviceInfoGet cmd=1200 expected=1200 packetId=(\d+)\b""")

    fun consume(previous: Trace, newEvents: List<String>): Trace {
        var result = previous
        for (event in newEvents) {
            if (!result.testStarted) continue
            when {
                event.contains("STATE REFRESH blocked ") -> result = result.copy(refreshPath = "Blocked: official session not ready")
                event.contains("STATE REFRESH already-active ") -> result = result.copy(refreshPath = "Already active: not a fresh GET")
                event.contains("STATE REFRESH deferred busy ") -> result = result.copy(refreshPath = "Deferred: transport busy; waiting for new bootstrap")
                event.contains("STATE MANUAL REFRESH start ") -> result = result.copy(refreshPath = "Started: existing 13-query bootstrap")
                event.contains("MAC13+PAGES START count=") -> result = result.copy(bootstrapStarted = true)
            }
            val match = mac13Query.find(event) ?: query.find(event)
            if (match != null) {
                val id = match.groupValues[1].toIntOrNull()
                if (id != null && id in 1..255) {
                    result = result.copy(
                        refreshPath = "DeviceInfo query dispatched",
                        queryPacketId = id,
                        payloadTimeout = false
                    )
                }
            }
            if (event.contains("FLOW-ONLY RX E0") && event.contains("for DeviceInfoGet")) {
                result = result.copy(transportTickCount = result.transportTickCount + 1)
            }
            if (event.contains("TRANSPORT DELIVERED label=DeviceInfoGet")) {
                result = result.copy(transportDelivered = true)
            }
            if (event.contains("REMOTE PAYLOAD TIMEOUT bootstrap=DeviceInfoGet")) {
                result = result.copy(payloadTimeout = true)
            }
            if (event.startsWith("TCS18.5 ") && event.contains("BOOTSTRAP ASYNC PAYLOAD")) {
                result = result.copy(unrelatedPayloadCount = result.unrelatedPayloadCount + 1)
            }
        }
        return result
    }

    fun describe(trace: Trace, s08TypedResponse: Boolean): String = when {
        !trace.testStarted -> "Run GET check above"
        s08TypedResponse -> "S08 + S09: typed DeviceInfo RX verified"
        trace.payloadTimeout -> "S09: GET timeout; S08: no verified reply"
        trace.queryPacketId != null -> "S09: request sent; S08: matching typed RX still missing"
        trace.refreshPath.startsWith("Blocked:") -> "Cannot test: recorder transport blocked"
        trace.refreshPath.startsWith("Already active:") -> "Wait for current batch to finish; no fresh GET yet"
        trace.refreshPath.startsWith("Deferred:") -> "GET deferred; do not report sent"
        else -> "GET not observed; S08 response unverified"
    }
}
