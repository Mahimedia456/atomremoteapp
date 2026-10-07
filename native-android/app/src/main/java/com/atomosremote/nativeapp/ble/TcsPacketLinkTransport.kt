package com.atomosremote.nativeapp.ble

/**
 * Byte-for-byte port of the transport rules recovered from the official macOS
 * TCSiOSC TCSPacketLink.c implementation.
 *
 * This class deliberately knows nothing about Android BluetoothGatt. It only
 * models the TCSPacketLink state machine and subpacket framing:
 * DISCONNECTED -> READY -> WRITING -> READING -> READY.
 *
 * Header bits used by TCSiOSC:
 * 0x80 packet-link marker on the first subpacket of a packet-link turn
 * 0x10 first-ever/initial marker (first turn after DidConnect)
 * 0x08 local packet flag
 * 0x04 control callback flag
 * 0x40 final subpacket
 * 0x20 receiver has room / grants peer TX credit
 */
internal class TcsPacketLinkTransport {
    enum class State { DISCONNECTED, READY, WRITING, READING }

    data class OutboundSubpacket(
        val bytes: ByteArray,
        val final: Boolean,
        val payloadBytes: Int,
        val includesPacketPayload: Boolean
    )

    data class ReadResult(
        val needAnotherRead: Boolean,
        val packet: ByteArray?,
        val packetLocal: Boolean,
        val initial: Boolean,
        val control: Boolean,
        val peerCredit: Boolean,
        val txPacketCompletedThisTurn: Boolean
    )

    private var state: State = State.DISCONNECTED
    private var firstTurn = true
    private var peerCredit = false

    private var txPacket = ByteArray(0)
    private var txOffset = 0
    private var txLocal = false
    private var currentWrite: OutboundSubpacket? = null
    private var writeTurnHadPayload = false
    private var writeTurnCompletedPacket = false

    private val rxBuffer = ArrayList<Byte>(160)
    private var rxLocal = false
    private var rxInitial = false
    private var rxControl = false

    fun state(): State = state
    fun peerCredit(): Boolean = peerCredit
    fun hasBufferedPacket(): Boolean = txPacket.isNotEmpty()
    fun bufferedPacketSize(): Int = txPacket.size

    fun didConnect() {
        state = State.READY
        firstTurn = true
        peerCredit = false
        txPacket = ByteArray(0)
        txOffset = 0
        txLocal = false
        currentWrite = null
        writeTurnHadPayload = false
        writeTurnCompletedPacket = false
        rxBuffer.clear()
        rxLocal = false
        rxInitial = false
        rxControl = false
    }

    fun didDisconnect() {
        state = State.DISCONNECTED
        txPacket = ByteArray(0)
        txOffset = 0
        txLocal = false
        currentWrite = null
        rxBuffer.clear()
    }

    /** Mirrors TCSPacketLinkSendPacket. Only one packet may occupy its TX buffer. */
    fun sendPacket(local: Boolean, payload: ByteArray): Boolean {
        if (state == State.DISCONNECTED) return false
        if (txPacket.isNotEmpty()) return false
        if (payload.size > MAX_PACKET_BYTES) return false
        txPacket = payload.copyOf()
        txOffset = 0
        txLocal = local
        return true
    }

    /** Mirrors TCSPacketLinkProcess. It only starts work from READY. */
    fun process(): OutboundSubpacket? {
        if (state != State.READY) return null
        state = State.WRITING
        writeTurnHadPayload = false
        writeTurnCompletedPacket = false
        return buildSubpacket()
    }

    /** Used after a non-final subpacket write completes. */
    fun continueWrite(): OutboundSubpacket? {
        if (state != State.WRITING || currentWrite != null) return null
        return buildSubpacket()
    }

    /** Android refused to start the GATT write. Regenerate the same turn later. */
    fun onWriteStartFailed() {
        if (state != State.WRITING) return
        currentWrite = null
        state = State.READY
        writeTurnHadPayload = false
        writeTurnCompletedPacket = false
    }

    sealed interface WriteCompleteAction {
        data object Read : WriteCompleteAction
        data object WriteNext : WriteCompleteAction
        data object Ignore : WriteCompleteAction
    }

    /** Mirrors TCSPacketLink _plexDidWriteValue. */
    fun onWriteComplete(): WriteCompleteAction {
        val written = currentWrite ?: return WriteCompleteAction.Ignore
        currentWrite = null

        if (written.payloadBytes > 0) {
            txOffset += written.payloadBytes
            writeTurnHadPayload = true
            if (txOffset >= txPacket.size) {
                txPacket = ByteArray(0)
                txOffset = 0

                // 18.8.4 transport fix:
                // HEADER_LOCAL belongs only to the packet that was queued through
                // sendPacket(local=true, ...). Once that packet is completely written,
                // clear the flag so later empty PacketLink process turns serialize as
                // non-local E0 instead of leaking the previous packet's local bit as E8.
                txLocal = false

                writeTurnCompletedPacket = true
            }
        }

        return if (written.final) {
            state = State.READING
            WriteCompleteAction.Read
        } else {
            WriteCompleteAction.WriteNext
        }
    }

    fun onWriteFailed() {
        currentWrite = null
        state = State.READY
    }

    /** Mirrors TCSPacketLink _plexDidReadValue. */
    fun onReadSubpacket(value: ByteArray): ReadResult {
        if (state != State.READING || value.isEmpty()) {
            return ReadResult(false, null, false, false, false, peerCredit, false)
        }

        val header = value[0].toInt() and 0xFF

        // In TCSiOSC only the first subpacket in a PacketLink packet carries bit 0x80.
        // A first subpacket containing payload resets the receive packet buffer.
        if (header and HEADER_PACKET_START != 0) {
            if (value.size >= 2) rxBuffer.clear()
            rxInitial = header and HEADER_INITIAL != 0
            rxControl = header and HEADER_CONTROL != 0
            rxLocal = header and HEADER_LOCAL != 0
        }

        for (i in 1 until value.size) {
            if (rxBuffer.size < MAX_PACKET_BYTES) rxBuffer.add(value[i])
        }

        val final = header and HEADER_FINAL != 0
        if (!final) {
            return ReadResult(
                needAnotherRead = true,
                packet = null,
                packetLocal = rxLocal,
                initial = rxInitial,
                control = rxControl,
                peerCredit = peerCredit,
                txPacketCompletedThisTurn = false
            )
        }

        peerCredit = header and HEADER_CREDIT != 0
        val packet = if (rxBuffer.isNotEmpty()) ByteArray(rxBuffer.size) { rxBuffer[it] } else null
        if (packet != null) rxBuffer.clear()
        val completed = writeTurnCompletedPacket
        writeTurnHadPayload = false
        writeTurnCompletedPacket = false
        state = State.READY

        return ReadResult(
            needAnotherRead = false,
            packet = packet,
            packetLocal = rxLocal,
            initial = rxInitial,
            control = rxControl,
            peerCredit = peerCredit,
            txPacketCompletedThisTurn = completed
        )
    }

    fun onReadFailed() {
        state = State.READY
    }

    private fun buildSubpacket(): OutboundSubpacket {
        check(state == State.WRITING)
        check(currentWrite == null)

        var header = if (txOffset == 0) {
            var firstHeader = if (firstTurn) HEADER_PACKET_START or HEADER_INITIAL else HEADER_PACKET_START
            if (firstTurn) firstTurn = false
            if (txLocal) firstHeader = firstHeader or HEADER_LOCAL
            firstHeader
        } else {
            0
        }

        val remaining = if (txPacket.isEmpty()) 0 else txPacket.size - txOffset
        val payloadBytes = if (remaining > 0 && peerCredit) minOf(MAX_SUBPACKET_PAYLOAD, remaining) else 0
        val final = payloadBytes == remaining

        if (final) {
            header = header or HEADER_FINAL
            if (rxBuffer.isEmpty()) header = header or HEADER_CREDIT
        }

        val frame = ByteArray(payloadBytes + 1)
        frame[0] = header.toByte()
        if (payloadBytes > 0) {
            txPacket.copyInto(frame, destinationOffset = 1, startIndex = txOffset, endIndex = txOffset + payloadBytes)
        }

        return OutboundSubpacket(
            bytes = frame,
            final = final,
            payloadBytes = payloadBytes,
            includesPacketPayload = payloadBytes > 0
        ).also { currentWrite = it }
    }

    companion object {
        private const val MAX_PACKET_BYTES = 160
        private const val MAX_SUBPACKET_PAYLOAD = 159

        private const val HEADER_PACKET_START = 0x80
        private const val HEADER_INITIAL = 0x10
        private const val HEADER_LOCAL = 0x08
        private const val HEADER_CONTROL = 0x04
        private const val HEADER_FINAL = 0x40
        private const val HEADER_CREDIT = 0x20
    }
}
