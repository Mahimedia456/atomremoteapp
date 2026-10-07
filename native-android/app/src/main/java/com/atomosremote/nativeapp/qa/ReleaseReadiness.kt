package com.atomosremote.nativeapp.qa

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat

data class QaCheck(
    val id: String,
    val label: String,
    val passed: Boolean,
    val detail: String,
    val blocking: Boolean = false
)

data class QaReport(val checks: List<QaCheck>) {
    val blockers: Int get() = checks.count { it.blocking && !it.passed }
    val passed: Int get() = checks.count { it.passed }
    val total: Int get() = checks.size
}

class ReleaseReadinessRunner(private val context: Context) {
    fun run(
        protocolValidated: Boolean,
        savedDeviceCount: Int,
        gattReady: Boolean,
        ndiNativeAvailable: Boolean,
        airGluNativeAvailable: Boolean
    ): QaReport {
        val pm = context.packageManager
        val btManager = context.getSystemService(BluetoothManager::class.java)
        val adapter = btManager?.adapter
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val active = connectivity.activeNetwork
        val caps = active?.let(connectivity::getNetworkCapabilities)
        val networkConnected = active != null && caps != null

        val checks = mutableListOf<QaCheck>()
        checks += QaCheck(
            "ble-feature",
            "Bluetooth LE hardware",
            pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            "Android device must expose Bluetooth LE hardware.",
            blocking = true
        )
        checks += QaCheck(
            "bluetooth-on",
            "Bluetooth enabled",
            adapter?.isEnabled == true,
            if (adapter?.isEnabled == true) "Bluetooth adapter is powered on." else "Turn Bluetooth on before recorder discovery.",
            blocking = true
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val connect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val advertise = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            checks += QaCheck("permission-scan", "Nearby scan permission", scan, "BLUETOOTH_SCAN runtime permission", blocking = true)
            checks += QaCheck("permission-connect", "Nearby connect permission", connect, "BLUETOOTH_CONNECT runtime permission", blocking = true)
            checks += QaCheck("permission-advertise", "Nearby advertise permission", advertise, "BLUETOOTH_ADVERTISE runtime permission for AtomRemote peripheral pairing", blocking = true)
        } else {
            val location = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            checks += QaCheck("permission-location", "BLE location permission", location, "Required by Android 11 and earlier for BLE discovery.", blocking = true)
        }

        checks += QaCheck(
            "network",
            "Network available",
            networkConnected,
            if (networkConnected) {
                when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi connected"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet connected"
                    else -> "A network is connected"
                }
            } else "No active network. BLE remote can still work, but network discovery/NDI will not.",
            blocking = false
        )
        checks += QaCheck("saved-device", "Trusted recorder saved", savedDeviceCount > 0, "$savedDeviceCount saved recorder(s)", blocking = false)
        checks += QaCheck("gatt", "Live GATT session", gattReady, if (gattReady) "Services discovered" else "Connect a recorder to validate live GATT", blocking = false)
        checks += QaCheck(
            "protocol",
            "Validated Atomos protocol",
            protocolValidated,
            if (protocolValidated) "Command writes are unlocked by an explicitly validated profile." else "Control writes intentionally remain locked.",
            blocking = false
        )
        checks += QaCheck(
            "ndi",
            "Native NDI preview bridge",
            ndiNativeAvailable,
            if (ndiNativeAvailable) "NDI decoder linked" else "NDI source discovery works; native video decoder is not linked.",
            blocking = false
        )
        checks += QaCheck(
            "airglu",
            "Native AirGlu RF bridge",
            airGluNativeAvailable,
            if (airGluNativeAvailable) "AirGlu native bridge linked" else "AirGlu workflow profile is present; RF transmit remains locked without SDK/protocol.",
            blocking = false
        )
        return QaReport(checks)
    }
}
