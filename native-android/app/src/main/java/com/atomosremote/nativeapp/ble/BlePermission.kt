package com.atomosremote.nativeapp.ble

import android.Manifest
import android.os.Build

/** Foreground BLE central discovery for Android 8.0+ (minimum supported SDK 26). */
object BlePermission {
    /** Advertising is deliberately NOT needed to scan or connect as a BLE central. */
    fun requiredScanPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /**
     * Extra location access is offered for broad OEM compatibility. Do not mark
     * BLUETOOTH_SCAN neverForLocation: that flag can filter certain BLE beacons.
     * A denial on Android 12+ must NOT prevent scanning with Nearby Devices.
     */
    fun optionalDiscoveryPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    } else emptyArray()

    // Kept as a convenience for callers: required first, optional last.
    fun runtimePermissions(): Array<String> = requiredScanPermissions() + optionalDiscoveryPermissions()
}
