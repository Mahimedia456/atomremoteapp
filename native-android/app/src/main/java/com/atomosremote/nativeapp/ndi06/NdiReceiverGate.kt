package com.atomosremote.nativeapp.ndi06

import android.content.Context
import java.io.File

/**
 * NDI-06B guarded receiver boundary.
 * No SDK redistributables or JNI binding have been supplied or verified.
 * IMPORTANT: the advertised _ndi._tcp port is NOT a raw H.264/HTTP/RTSP URL.
 */
interface NdiReceiverAdapter {
    val hasVerifiedNativeBinding: Boolean
    fun open(source: NdiSource): NdiReceiverResult
    fun close()
}

data class NdiReceiverResult(val mediaStarted: Boolean, val reason: String)

class LockedNdiReceiverAdapter : NdiReceiverAdapter {
    override val hasVerifiedNativeBinding: Boolean = false
    override fun open(source: NdiSource): NdiReceiverResult = NdiReceiverResult(
        false,
        "NDI-06B LOCKED: SDK/JNI receiver not integrated. Selected: ${source.name}. " +
            "No network media connection attempted."
    )
    override fun close() { /* No receiver was opened. */ }
}

object NdiReceiverGate {
    /** File presence is informational only; it does not establish usable codec/JNI support. */
    fun inspect(context: Context): String {
        val dir = context.applicationInfo.nativeLibraryDir ?: ""
        val libraryPresent = dir.isNotBlank() && File(dir, "libndi.so").isFile
        return if (libraryPresent) {
            "libndi.so detected, but JNI ABI/license/API binding is NOT verified. " +
                "Receiver disabled until a reviewed Android SDK adapter is integrated."
        } else {
            "Android NDI native runtime (libndi.so) not packaged. " +
                "Receiver disabled. Discovery can work without a media decoder."
        }
    }
    const val ACTIVE_RECEIVER_ENABLED = false
    const val LIVEMODESET_TRANSMISSION_ENABLED = false
}
