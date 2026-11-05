package com.atomosremote.nativeapp.ui.settings

/** Read-only evidence produced by the app's existing official GATT connect preflight.
 *  This is NOT a DeviceInfo protobuf response and BCID is NOT the recorder serial ID.
 *  Evidence is accepted only within a GATT connection whose address matches the selected device.
 */
internal object SettingsB04InfoEvidence {
    internal data class Evidence(
        val selectedPeerConnected: Boolean = false,
        val gattDeviceName: String? = null,
        val pairingBcid: String? = null,
        val preflightPassed: Boolean = false,
        val observedEvents: Int = 0
    )
    private val connect = Regex("""\bGATT connect -> ([0-9A-Fa-f:]{17})\b""")
    private val name = Regex("""\bCONNECT DEVICE NAME <- ([\w.\-]{1,32}) raw=([0-9A-Fa-f]+)\b""")
    private val bcid = Regex("""\bCONNECT BCID <- (0x[0-9A-Fa-f]{8})\b""")
    fun consume(previous: Evidence, expectedAddress: String, newEvents: List<String>): Evidence {
        if (!Regex("""(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$""").matches(expectedAddress)) return Evidence()
        var current = previous
        for (event in newEvents) {
            val connection = connect.find(event)
            if (connection != null) {
                // Start a completely new identity epoch on every GATT connection.
                current = Evidence(selectedPeerConnected = connection.groupValues[1].equals(expectedAddress, true))
                continue
            }
            if (!current.selectedPeerConnected) continue
            val n = name.find(event)
            if (n != null) {
                val hex = n.groupValues[2]
                val raw = try { hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() } catch (_: Exception) { null }
                val decoded = raw?.takeWhile { it != 0.toByte() }?.toByteArray()?.toString(Charsets.US_ASCII)
                if (decoded == n.groupValues[1]) current = current.copy(gattDeviceName = decoded)
            }
            val id = bcid.find(event)
            if (id != null) current = current.copy(pairingBcid = "0x" + id.groupValues[1].substring(2).uppercase())
            if (event.contains("CONNECT PREFLIGHT PASS")) {
                if (current.gattDeviceName != null && current.pairingBcid != null)
                    current = current.copy(preflightPassed = true)
            }
            current = current.copy(observedEvents = current.observedEvents + 1)
        }
        return current
    }
}
