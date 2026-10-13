package com.atomosremote.nativeapp.ndi07

import android.graphics.PixelFormat
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private class Ndi14Session {
    val lock = Any()
    var active = false
    var started = false
    var surfaceReady = false
    var restarts = 0
    var lastRestartAt = 0L
}

/**
 * Receiver-only live preview INSIDE original Remote Live Preview box.
 * User ON/OFF only controls local NDI receiving; no recorder TX commands.
 * Disposal (navigating away / source lost / surface destroyed) releases the
 * receiver and surface. Reopening Remote reacquires the stream automatically.
 */
@Composable
fun Ndi07InlinePreview(sourceName: String, enabled: Boolean, modifier: Modifier = Modifier) {
    if (!Ndi07Native.available || sourceName.isBlank()) return
    val context = LocalContext.current
    val view = remember(context, sourceName) {
        SurfaceView(context).apply { holder.setFormat(PixelFormat.RGBA_8888) }
    }
    var status by remember(sourceName) { mutableStateOf("Connecting to NDI sender...") }
    val session = remember(view, sourceName) { Ndi14Session() }

    DisposableEffect(sourceName, view, enabled) {
        synchronized(session.lock) {
            session.active = enabled
            session.started = false
            session.surfaceReady = false
            session.restarts = 0
            session.lastRestartAt = 0L
        }
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                synchronized(session.lock) {
                    session.surfaceReady = holder.surface.isValid
                    if (!session.active || session.started || !holder.surface.isValid) return
                    Log.i("AtomNdi14", "NDI14_START reason=surface_ready source=$sourceName")
                    session.started = Ndi07Native.startPreview(holder.surface, sourceName)
                    Log.i("AtomNdi14", "NDI14_START_RESULT success=${session.started}")
                    if (!session.started) status = "NDI receiver start failed"
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i("AtomNdi14", "NDI14_SURFACE_SIZE w=$width h=$height")
                surfaceCreated(holder)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                synchronized(session.lock) {
                    Log.i("AtomNdi14", "NDI14_STOP reason=surface_destroyed started=${session.started}")
                    session.surfaceReady = false
                    if (session.started) Ndi07Native.stopPreview()
                    session.started = false
                }
            }
        }
        if (enabled) {
            view.holder.addCallback(callback)
            if (view.holder.surface.isValid) callback.surfaceCreated(view.holder)
        } else Log.i("AtomNdi14", "NDI14_PREVIEW_OFF local_receiver_only=true")
        onDispose {
            synchronized(session.lock) {
                Log.i("AtomNdi14", "NDI14_STOP reason=toggle_or_tab started=${session.started}")
                session.active = false
                session.surfaceReady = false
                view.holder.removeCallback(callback)
                if (session.started) Ndi07Native.stopPreview()
                session.started = false
            }
        }
    }
    LaunchedEffect(sourceName, view, enabled) {
        if (!enabled) {
            status = "NDI PREVIEW OFF"
            return@LaunchedEffect
        }
        while (true) {
            status = Ndi07Native.receiverStatus()
            val age = Ndi07Native.videoFrameAgeMs()
            if (age >= 4000L) {
                status = "NDI video waiting ${age / 1000L}s"
                if (session.restarts >= 2) status = "NDI stalled. Toggle OFF then ON to reconnect"
            }
            if (age >= 12000L) {
                val now = SystemClock.elapsedRealtime()
                var recover = false
                synchronized(session.lock) {
                    if (session.active && session.started && session.surfaceReady &&
                        session.restarts < 2 &&
                        (session.lastRestartAt == 0L || now - session.lastRestartAt >= 30000L)) {
                        session.restarts++
                        session.lastRestartAt = now
                        recover = true
                    }
                }
                if (recover) {
                    // Recovery is intentionally bounded: maximum twice per ON session.
                    // NDI itself reconnects automatically; never restart on small stalls.
                    Log.w("AtomNdi14", "NDI14_WATCHDOG_RECOVER ageMs=$age reason=video_stall")
                    withContext(Dispatchers.IO) {
                        synchronized(session.lock) {
                            if (session.active && session.started && session.surfaceReady &&
                                view.holder.surface.isValid) {
                                session.started = Ndi07Native.startPreview(view.holder.surface,sourceName)
                                Log.i("AtomNdi14", "NDI14_WATCHDOG_RESULT started=${session.started} attempt=${session.restarts}")
                            }
                        }
                    }
                } else if (age >= 12000L) {
                    Log.i("AtomNdi14", "NDI14_WATCHDOG_WAIT ageMs=$age attempts=${session.restarts}")
                }
            }
            delay(1500L)
        }
    }
    Box(modifier.fillMaxSize()) {
        if (enabled) AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        if (!enabled || !status.startsWith("LIVE ")) Text(
            status.take(110),
            color = Color(0xFFE9F8F4),
            fontSize = 9.sp,
            modifier = Modifier.align(Alignment.TopStart)
                .padding(7.dp)
                .background(Color(0xD900100F), RoundedCornerShape(5.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp)
        )
    }
}
