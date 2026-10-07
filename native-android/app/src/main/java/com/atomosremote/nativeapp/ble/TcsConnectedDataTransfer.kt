package com.atomosremote.nativeapp.ble

/**
 * Initial CONNECTED Data Transfer State Machine recovered from the supplied
 * macOS TCSiOSC.framework.
 *
 * Official InitDataModel values recovered from ATOMOS - Remote x86_64:
 *   primary model id = 99 (0x63)
 *   secondary model id = 1
 *   block count = 2
 *
 * This class is transport-agnostic. GattClient owns the BLE UUIDs/ATT queue.
 */
// 18.8.6.1: non-matching/zero cxtic11 model IDs are non-fatal, matching
// official TCSDataTransfer.c OFFERING_PRIMARY behavior.
internal class TcsConnectedDataTransfer {

    enum class Stage {
        DISABLED,
        OFFERING_PRIMARY,
        ACCEPTING_SECONDARY,
        CONNECTED,
        COMPLETE,
        ERROR
    }

    enum class WriteKind {
        OFFER_PRIMARY,
        ACCEPT_SECONDARY,
        MODEL_DATA
    }

    data class WriteRequest(
        val kind: WriteKind,
        val cxtic: Int,
        val value: ByteArray
    )

    data class LinkReadResult(
        val ok: Boolean,
        val peerModelId: Long,
        val message: String
    )

    data class InboundResult(
        val ok: Boolean,
        val final: Boolean,
        val changedBytes: Int,
        val message: String
    )

    companion object {
        const val PRIMARY_MODEL_ID: Long = 99L
        const val SECONDARY_MODEL_ID: Long = 1L
        const val MODEL_BLOCK_COUNT: Int = 2
        const val MODEL_BLOCK_BYTES: Int = 64
        const val MODEL_BYTES: Int = MODEL_BLOCK_COUNT * MODEL_BLOCK_BYTES

        const val CXTIC_DATA_WRITE = 8
        const val CXTIC_DATA_READ = 9
        const val CXTIC_LINK_WRITE = 10
        const val CXTIC_LINK_READ = 11

        private const val HEADER_START = 0x80
        private const val HEADER_FINAL = 0x40
    }

    private var stage: Stage = Stage.DISABLED
    private var cycleBusy = false
    private var deviceNameCountdown = 3

    private val model = ByteArray(MODEL_BYTES)

    private var pendingBlock = -1
    private var pendingOffsets = IntArray(0)
    private var pendingOffsetIndex = 0

    fun reset() {
        stage = Stage.DISABLED
        cycleBusy = false
        deviceNameCountdown = 3
        model.fill(0)
        clearInboundContinuation()
    }

    fun enable() {
        if (stage == Stage.DISABLED || stage == Stage.ERROR) {
            stage = Stage.OFFERING_PRIMARY
            cycleBusy = false
            deviceNameCountdown = 3
            clearInboundContinuation()
        }
    }

    fun stage(): Stage = stage
    fun isEnabled(): Boolean = stage != Stage.DISABLED && stage != Stage.ERROR
    fun isCycleBusy(): Boolean = cycleBusy
    fun isComplete(): Boolean = stage == Stage.COMPLETE

    /**
     * Official CONNECTED_ReadingDeviceName uses a countdown. The recorder name
     * is not re-read on every frame. Start at 3 and read every third DTSM cycle.
     */
    fun beginCycle(): Boolean {
        if (!isEnabled() || cycleBusy || stage == Stage.COMPLETE) return false
        cycleBusy = true
        return true
    }

    fun shouldReadDeviceNameThisCycle(): Boolean {
        deviceNameCountdown -= 1
        if (deviceNameCountdown <= 0) {
            deviceNameCountdown = 3
            return true
        }
        return false
    }

    fun nextWriteRequest(): WriteRequest? {
        if (!cycleBusy) return null
        return when (stage) {
            Stage.OFFERING_PRIMARY -> WriteRequest(
                kind = WriteKind.OFFER_PRIMARY,
                cxtic = CXTIC_LINK_WRITE,
                value = u32Le(PRIMARY_MODEL_ID)
            )

            Stage.ACCEPTING_SECONDARY -> WriteRequest(
                kind = WriteKind.ACCEPT_SECONDARY,
                cxtic = CXTIC_LINK_WRITE,
                value = u32Le(SECONDARY_MODEL_ID)
            )

            Stage.CONNECTED -> WriteRequest(
                kind = WriteKind.MODEL_DATA,
                cxtic = CXTIC_DATA_WRITE,
                // Exact empty/no-local-change output from TCSBleManager_GetNextOutData:
                // start + final, with no changed-data payload.
                value = byteArrayOf(0xC0.toByte())
            )

            else -> null
        }
    }

    /**
     * Called after cxtic10 offer write and cxtic11 read.
     *
     * Official DTSM:
     *   peer == 99 -> CONNECTED
     *   peer == 1  -> ACCEPTING_SECONDARY
     */
    fun onLinkRead(value: ByteArray): LinkReadResult {
        if (value.size < 4) {
            stage = Stage.ERROR
            cycleBusy = false
            return LinkReadResult(false, -1, "cxtic11 expected 4 bytes, got ${value.size}")
        }

        val peer = u32Le(value)
        when (peer) {
            PRIMARY_MODEL_ID -> {
                stage = Stage.CONNECTED
                cycleBusy = false
                return LinkReadResult(true, peer, "primary model accepted")
            }

            SECONDARY_MODEL_ID -> {
                stage = Stage.ACCEPTING_SECONDARY
                cycleBusy = false
                return LinkReadResult(true, peer, "secondary model requested")
            }

            else -> {
                // 18.8.6.1 macOS parity fix:
                // Official dtsm_OFFERING_PRIMARY_DidReadValue does NOT enter an
                // error state when cxtic11 returns neither the primary nor the
                // secondary model id. It simply leaves the state unchanged in
                // OFFERING_PRIMARY and retries on a later CONNECTED cycle.
                //
                // Shogun Ultra commonly returns 0x00000000 while the peer side is
                // not ready yet. Treating zero as fatal resets the DTSM every frame
                // and also resets the official device-name countdown, preventing
                // the state machine from ever maturing.
                stage = Stage.OFFERING_PRIMARY
                cycleBusy = false
                return LinkReadResult(
                    true,
                    peer,
                    "peer model not ready; remain OFFERING_PRIMARY"
                )
            }
        }
    }

    /**
     * Official ACCEPTING_SECONDARY completes after writing model id 1 to cxtic10.
     * The next frame-count/timecode cycle starts CONNECTED model transfer.
     */
    fun onSecondaryAccepted() {
        stage = Stage.CONNECTED
        cycleBusy = false
    }

    fun onModelWriteComplete() {
        // Remain busy. GattClient must now read cxtic9 until FINAL.
    }

    /**
     * Decode TCSBleManager_ReceiveInData framing.
     *
     * Header:
     *   bit 0x80 = new data packet metadata present
     *   bit 0x40 = final
     *
     * On a new data packet:
     *   byte 1 low nibble = 64-byte block index
     *   bytes 2..9       = 64-bit changed-byte mask, little-endian
     *   following bytes  = values for set mask bits
     *
     * Continuation frames can provide remaining changed values without repeating
     * the block/mask metadata.
     */
    fun onInboundData(value: ByteArray): InboundResult {
        if (value.isEmpty()) {
            return failInbound("empty cxtic9 frame")
        }

        val header = value[0].toInt() and 0xFF
        val isStart = header and HEADER_START != 0
        val isFinal = header and HEADER_FINAL != 0

        var cursor = 1
        var changedThisFrame = 0

        if (isStart) {
            clearInboundContinuation()

            // 0xC0 is the official empty/final frame.
            if (isFinal && value.size == 1) {
                stage = Stage.COMPLETE
                cycleBusy = false
                return InboundResult(
                    ok = true,
                    final = true,
                    changedBytes = 0,
                    message = "final empty model frame"
                )
            }

            if (value.size < 10) {
                return failInbound("start frame too short: ${value.size}")
            }

            pendingBlock = value[1].toInt() and 0x0F
            if (pendingBlock !in 0 until MODEL_BLOCK_COUNT) {
                return failInbound("invalid model block $pendingBlock")
            }

            var mask = 0L
            for (i in 0 until 8) {
                mask = mask or ((value[2 + i].toLong() and 0xFFL) shl (8 * i))
            }
            cursor = 10

            val offsets = ArrayList<Int>(64)
            for (bit in 0 until 64) {
                if (((mask ushr bit) and 1L) != 0L) offsets.add(bit)
            }
            pendingOffsets = offsets.toIntArray()
            pendingOffsetIndex = 0
        } else if (pendingBlock < 0) {
            return failInbound("continuation without active block")
        }

        while (cursor < value.size && pendingOffsetIndex < pendingOffsets.size) {
            val absolute = pendingBlock * MODEL_BLOCK_BYTES + pendingOffsets[pendingOffsetIndex]
            if (absolute !in model.indices) {
                return failInbound("model offset out of range: $absolute")
            }
            model[absolute] = value[cursor]
            pendingOffsetIndex += 1
            cursor += 1
            changedThisFrame += 1
        }

        if (cursor < value.size && pendingOffsetIndex >= pendingOffsets.size) {
            // Extra bytes are not silently interpreted as a new structure.
            return failInbound("unexpected trailing model bytes: ${value.size - cursor}")
        }

        if (isFinal) {
            if (pendingOffsetIndex < pendingOffsets.size) {
                return failInbound(
                    "final frame missing ${pendingOffsets.size - pendingOffsetIndex} changed byte(s)"
                )
            }
            clearInboundContinuation()
            stage = Stage.COMPLETE
            cycleBusy = false
            return InboundResult(
                ok = true,
                final = true,
                changedBytes = changedThisFrame,
                message = "initial model synchronization complete"
            )
        }

        return InboundResult(
            ok = true,
            final = false,
            changedBytes = changedThisFrame,
            message = "model continuation required"
        )
    }

    fun failCycle(message: String) {
        stage = Stage.ERROR
        cycleBusy = false
        clearInboundContinuation()
    }

    fun modelSnapshot(): ByteArray = model.copyOf()

    private fun failInbound(message: String): InboundResult {
        failCycle(message)
        return InboundResult(
            ok = false,
            final = false,
            changedBytes = 0,
            message = message
        )
    }

    private fun clearInboundContinuation() {
        pendingBlock = -1
        pendingOffsets = IntArray(0)
        pendingOffsetIndex = 0
    }

    private fun u32Le(value: ByteArray): Long {
        if (value.size < 4) return 0L
        return (value[0].toLong() and 0xFFL) or
            ((value[1].toLong() and 0xFFL) shl 8) or
            ((value[2].toLong() and 0xFFL) shl 16) or
            ((value[3].toLong() and 0xFFL) shl 24)
    }

    private fun u32Le(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte()
    )
}
