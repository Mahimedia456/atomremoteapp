package com.atomosremote.nativeapp.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.atomosremote.nativeapp.ble.BlePermission

/**
 * Startup prerequisites only. No change to BLE discovery, official TCS pairing,
 * PacketLink, recorder transport, backend or connection lifecycle.
 * Returns true once required permissions, Bluetooth, and phone Location are ON.
 * Optional Android 12+ Fine Location denial does not block Nearby Devices.
 */
@Composable
internal fun AtomRemoteStartupGate(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = remember(context) { activityLifecycleOwner(context) }
    val preferences = remember(context) {
        context.getSharedPreferences("atomremote_startup_permissions", Context.MODE_PRIVATE)
    }

    var refresh by remember { mutableStateOf(0) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var bluetoothRequested by rememberSaveable { mutableStateOf(false) }
    var locationRequested by rememberSaveable { mutableStateOf(false) }
    var stepMessage by remember { mutableStateOf("") }

    // Detect permission or system switch changes after returning from Android Settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refresh++
    }
    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refresh++
    }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refresh++
    }
    val appSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refresh++
    }

    // Reading refresh forces re-evaluation even if Settings changed outside Compose.
    val requiredMissing = remember(refresh) {
        BlePermission.requiredScanPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }
    val optionalMissing = remember(refresh) {
        BlePermission.optionalDiscoveryPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }
    val optionalNotOffered = !preferences.getBoolean("optional_location_offered", false)
    // Android 12+ requires COARSE and FINE together in the same request.
    // The Universal BLE v1.2 helper intentionally includes only FINE as optional.
    val offeredLocation = if (optionalNotOffered && optionalMissing.isNotEmpty()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        } else optionalMissing
    } else emptyList()
    val toRequest = (requiredMissing + offeredLocation).distinct()

    fun requestPermissions() {
        if (toRequest.isNotEmpty()) {
            permissionRequested = true
            if (optionalMissing.isNotEmpty()) {
                // Avoid a duplicate location request on the Connect screen if user declines.
                preferences.edit().putBoolean("optional_location_offered", true).apply()
            }
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    if (requiredMissing.isNotEmpty() || (optionalNotOffered && optionalMissing.isNotEmpty() && !permissionRequested)) {
        LaunchedEffect(toRequest.joinToString("|"), permissionRequested) {
            if (!permissionRequested && toRequest.isNotEmpty()) requestPermissions()
        }
        StartupRequirementPage(
            title = "Allow device access",
            explanation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "Atom Remote needs Nearby Devices permission to find and connect to ATOMOS recorders. " +
                    "Fine Location is also requested for compatibility with some Android Bluetooth scanners."
            } else {
                "Atom Remote needs Location permission for Bluetooth LE discovery on this Android version."
            },
            primaryText = "ALLOW PERMISSIONS",
            primaryAction = { requestPermissions() },
            secondaryText = "OPEN APP SETTINGS",
            secondaryAction = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                }
                appSettingsLauncher.launch(intent)
            },
            message = stepMessage
        )
        return false
    }

    val hasBluetooth = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }
    if (!hasBluetooth) {
        StartupRequirementPage(
            title = "Bluetooth LE unavailable",
            explanation = "This device does not report Bluetooth LE support. BLE recorder discovery is not available.",
            primaryText = "OPEN BLUETOOTH SETTINGS",
            primaryAction = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            message = stepMessage
        )
        return false
    }

    // Permission already granted above; reading adapter state is permitted on Android 12+.
    val bluetoothEnabled = remember(refresh) {
        try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }
    }
    fun askForBluetooth() {
        bluetoothRequested = true
        try {
            bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (_: Exception) {
            stepMessage = "Bluetooth prompt unavailable. Open Bluetooth Settings and enable it."
        }
    }
    if (!bluetoothEnabled) {
        LaunchedEffect(bluetoothRequested) {
            if (!bluetoothRequested) askForBluetooth()
        }
        StartupRequirementPage(
            title = "Turn on Bluetooth",
            explanation = "Bluetooth must be ON before Atom Remote can scan and connect to your recorder.",
            primaryText = "TURN ON BLUETOOTH",
            primaryAction = { askForBluetooth() },
            secondaryText = "BLUETOOTH SETTINGS",
            secondaryAction = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            message = stepMessage
        )
        return false
    }

    val locationEnabled = remember(refresh) { phoneLocationEnabled(context) }
    fun openLocationSettings() {
        locationRequested = true
        try {
            locationLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (_: Exception) {
            stepMessage = "Unable to open Location Settings automatically. Enable phone Location manually."
        }
    }
    if (!locationEnabled) {
        LaunchedEffect(locationRequested) {
            if (!locationRequested) openLocationSettings()
        }
        StartupRequirementPage(
            title = "Turn on phone Location",
            explanation = "For reliable recorder discovery across Android manufacturers, switch ON the phone's " +
                "Location setting. Atom Remote does not turn on GPS without your approval.",
            primaryText = "OPEN LOCATION SETTINGS",
            primaryAction = { openLocationSettings() },
            message = stepMessage
        )
        return false
    }
    return true
}

private fun activityLifecycleOwner(context: Context): LifecycleOwner? {
    var current: Context? = context
    while (current != null) {
        if (current is LifecycleOwner) return current
        val next = (current as? ContextWrapper)?.baseContext
        if (next === current) return null
        current = next
    }
    return null
}

private fun phoneLocationEnabled(context: Context): Boolean = try {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    when {
        manager == null -> false
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> manager.isLocationEnabled
        else -> manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
} catch (_: Exception) {
    false
}

@Composable
private fun StartupRequirementPage(
    title: String,
    explanation: String,
    primaryText: String,
    primaryAction: () -> Unit,
    secondaryText: String? = null,
    secondaryAction: (() -> Unit)? = null,
    message: String = ""
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF02090C)) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("ATOM REMOTE", color = Color(0xFF19E7D3), fontSize = 16.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(28.dp))
            Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(explanation, color = Color(0xFFA9BDC1), fontSize = 14.sp)
            if (message.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(message, color = Color(0xFFFFA7A7), fontSize = 12.sp)
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = primaryAction,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF19E7D3), contentColor = Color(0xFF001113))
            ) { Text(primaryText, fontWeight = FontWeight.Bold) }
            if (secondaryAction != null && secondaryText != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = secondaryAction, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Text(secondaryText, color = Color.White)
                }
            }
        }
    }
}
