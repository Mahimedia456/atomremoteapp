package com.atomosremote.nativeapp.ndi07

import android.view.Surface
import android.util.Log

/** Receive-only NDI 07 JNI boundary. Never controls recorder TX, pairing or licensing. */
object Ndi07Native {
    private const val TAG = "AtomNdi07"
    @Volatile private var loaderDetail = "Native libraries not checked"

    // Load native NDI before our JNI shim. This also gives a precise failure reason
    // when a transitive dependency/symbol/API is unavailable on the Android device.
    private val loaded: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            // Preload the locally staged FFmpeg 7 ARM64 codec group in DT_NEEDED order.
            // Their internal ELF SONAMEs are versioned for the NDI runtime.
            // System.loadLibrary resolves the APK packaged (unversioned) filenames.
            System.loadLibrary("c++_shared")
            Log.i(TAG, "NDI07_FFMPEG_CPP=PASS")
            System.loadLibrary("avutil")
            Log.i(TAG, "NDI07_FFMPEG_AVUTIL=PASS")
            System.loadLibrary("swresample")
            Log.i(TAG, "NDI07_FFMPEG_SWRESAMPLE=PASS")
            System.loadLibrary("avcodec")
            Log.i(TAG, "NDI07_FFMPEG_AVCODEC=PASS")
            System.loadLibrary("ndi")
            Log.i(TAG, "NDI07_LOAD_NDI=PASS")
            System.loadLibrary("ndi07receiver")
            Log.i(TAG, "NDI07_LOAD_BRIDGE=PASS")
            loaderDetail = "NDI receiver libraries loaded"
            true
        } catch (e: UnsatisfiedLinkError) {
            loaderDetail = "NDI native load failed: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, "NDI07_LOAD_FAILED: $loaderDetail", e)
            false
        } catch (e: SecurityException) {
            loaderDetail = "NDI native load blocked: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, "NDI07_LOAD_BLOCKED: $loaderDetail", e)
            false
        }
    }

    val available: Boolean get() = loaded
    val loadDiagnostic: String get() { val ok = available; return if (ok) "NDI receiver loaded" else loaderDetail }

    @Synchronized
    fun startPreview(surface: Surface, exactSourceName: String): Boolean {
        if (!available || exactSourceName.isBlank() || !surface.isValid) return false
        return try { nativeStart(surface, exactSourceName) } catch (e: LinkageError) {
            Log.e(TAG, "NDI07_START_LINKAGE_FAILURE", e)
            false
        }
    }

    @Synchronized
    fun stopPreview() {
        if (available) try { nativeStop() } catch (e: LinkageError) {
            Log.e(TAG, "NDI07_STOP_LINKAGE_FAILURE", e)
        }
    }

    fun receiverStatus(): String = if (!available) loadDiagnostic else
        try { nativeStatus() } catch (e: LinkageError) {
            Log.e(TAG, "NDI07_STATUS_LINKAGE_FAILURE", e)
            "NDI native status unavailable"
        }

    private external fun nativeStart(surface: Surface, exactSourceName: String): Boolean
    private external fun nativeStop()
    // V14: only queries age of most recent decoded video frame.
    fun videoFrameAgeMs(): Long = if (!available) -1L else
        try { nativeVideoAgeMs() } catch (e: LinkageError) {
            Log.e(TAG, "NDI14_HEARTBEAT_UNAVAILABLE", e)
            -1L
        }

    private external fun nativeVideoAgeMs(): Long
    /** Android post-decode processing resolution. Does not set recorder NDI TX. */
    fun setAndroidOutputHeight(height: Int): Boolean {
        if (!available || height !in setOf(230, 480, 720, 1080)) return false
        return try { nativeSetAndroidOutputHeight(height) } catch (e: LinkageError) {
            Log.e(TAG, "NDI15_OUTPUT_HEIGHT_LINKAGE_FAILURE", e)
            false
        }
    }
    private external fun nativeSetAndroidOutputHeight(height: Int): Boolean
    private external fun nativeStatus(): String
}
