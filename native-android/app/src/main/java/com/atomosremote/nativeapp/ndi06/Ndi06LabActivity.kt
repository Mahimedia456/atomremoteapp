package com.atomosremote.nativeapp.ndi06

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.ArrayDeque

/** Standalone adb-launchable lab, no modification to the app's existing screens. */
class Ndi06LabActivity : Activity() {
    private lateinit var discovery: NdiDiscovery
    private lateinit var summary: TextView
    private lateinit var messages: TextView
    private lateinit var sourceList: LinearLayout
    private var selected: NdiSource? = null
    private val sources = mutableListOf<NdiSource>()
    private val adapter: NdiReceiverAdapter = LockedNdiReceiverAdapter()
    private val diagnosticLines = ArrayDeque<String>()
    private val networkPermission = "android.permission.ACCESS_LOCAL_NETWORK"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(12, 16, 24))
        }
        val scroller = ScrollView(this).apply { addView(root) }
        setContentView(scroller)
        fun title(value: String, size: Float): TextView = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 14)
            root.addView(this)
        }
        title("ATOM REMOTE  |  NDI LAB", 24f)
        title("NDI-06A: real Android service discovery\nNDI-06B: receiver boundary LOCKED (no SDK media decoder)", 15f)
        summary = title("No discovery yet", 16f)
        Button(this).apply {
            text = "START NDI DISCOVERY"
            setOnClickListener { requestNetworkThenStart() }
            root.addView(this)
        }
        Button(this).apply {
            text = "STOP DISCOVERY"
            setOnClickListener { discovery.stop() }
            root.addView(this)
        }
        Button(this).apply {
            text = "CHECK 06B RECEIVER READINESS"
            setOnClickListener {
                log(NdiReceiverGate.inspect(this@Ndi06LabActivity))
                selected?.let { log(adapter.open(it).reason) }
            }
            root.addView(this)
        }
        title("DISCOVERED NDI SENDERS", 17f)
        sourceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        title("DIAGNOSTIC LOG", 17f)
        messages = title("Ready. Touch Start Discovery.", 13f).apply {
            gravity = Gravity.START
        }
        discovery = NdiDiscovery(this,
            onLog = { log(it) },
            onUpdate = { found ->
                sources.clear()
                sources.addAll(found)
                showSources()
            }
        )
        log("Discovery sends standard mDNS queries only. No recorder commands or video connection.")
        log(NdiReceiverGate.inspect(this))
    }

    private fun requestNetworkThenStart() {
        if (Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(networkPermission) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(networkPermission), 6061)
            return
        }
        discovery.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 6061) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) discovery.start()
            else log("Local network permission denied: cannot discover NDI sources")
        }
    }

    private fun showSources() {
        sourceList.removeAllViews()
        summary.text = if (sources.isEmpty()) "No NDI services resolved yet" else "Resolved NDI services: ${sources.size}"
        for (source in sources) {
            val button = Button(this).apply {
                text = "${source.name}\n${source.host}:${source.advertisedPort}\n" +
                    "TXT: ${source.txt.entries.take(4).joinToString { it.key + "=" + it.value }}"
                setAllCaps(false)
                setOnClickListener {
                    selected = source
                    log("Selected ${source.displayName}. Port is an advertisement, NOT a video URL.")
                    log(adapter.open(source).reason)
                }
            }
            sourceList.addView(button)
        }
    }

    private fun log(message: String) {
        if (diagnosticLines.size >= 24) diagnosticLines.removeFirst()
        diagnosticLines.addLast(message)
        if (::messages.isInitialized) messages.text = diagnosticLines.joinToString("\n")
        android.util.Log.i("AtomRemoteNDI06", message)
    }

    override fun onDestroy() {
        if (::discovery.isInitialized) discovery.stop()
        adapter.close()
        super.onDestroy()
    }
}
