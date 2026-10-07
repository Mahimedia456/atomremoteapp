package com.atomosremote.nativeapp.ble

import android.Manifest
import android.os.Build

object BlePermission {
    /**
     * We intentionally request Fine Location together with Nearby Devices on Android 12+
     * instead of declaring neverForLocation. Android documents that neverForLocation may
     * restrict the types of Bluetooth devices returned by scans. The app does not derive or
     * store physical location; this is only for broad BLE interoperability across OEM stacks.
     */
    fun runtimePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
