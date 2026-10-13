package com.atomosremote.nativeapp.network

import android.util.Log

/**
 * WL-P8 guarded preview receiver skeleton.
 *
 * This class deliberately cannot open a socket in WL-P8.
 * A future phase may implement/open a transport only after a real recorder-advertised
 * media candidate is proven by WL-P7 evidence.
 */
internal class WlPreviewReceiverPrototype {

    data class StartResult(
        val started: Boolean,
        val reason: String,
        val candidate: WlPreviewCandidate? = null
    )

    fun start(candidate: WlPreviewCandidate): StartResult {
        if (!WlPreviewTransportPrototype.ACTIVE_RECEIVER_ENABLED) {
            val reason = "WL-P8 receiver blocked: ACTIVE_RECEIVER_ENABLED=false"
            Log.d("AtomRemoteWLP8", "$reason candidate=$candidate")
            return StartResult(
                started = false,
                reason = reason,
                candidate = candidate
            )
        }

        // Safety boundary:
        // even if the compile-time latch is changed in a future phase,
        // this WL-P8 implementation still contains NO socket/client/decoder code.
        val reason = "WL-P8 receiver implementation intentionally absent"
        Log.d("AtomRemoteWLP8", "$reason candidate=$candidate")
        return StartResult(
            started = false,
            reason = reason,
            candidate = candidate
        )
    }
}
