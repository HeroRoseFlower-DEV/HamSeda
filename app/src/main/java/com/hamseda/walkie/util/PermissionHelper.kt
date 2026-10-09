package com.hamseda.walkie.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Version-aware runtime permission mapping.
 *
 * Wi-Fi Direct discovery/connect:
 * - API 33+: NEARBY_WIFI_DEVICES (declared neverForLocation — no location use)
 * - API 29–32: ACCESS_FINE_LOCATION
 * - API 26–28: ACCESS_COARSE_LOCATION
 *
 * Bluetooth Classic:
 * - API 31+: BLUETOOTH_SCAN (discovery), BLUETOOTH_CONNECT (connect + device
 *   info), BLUETOOTH_ADVERTISE (RFCOMM listen/advertise)
 * - API ≤30: BLUETOOTH + BLUETOOTH_ADMIN (install-time) and location only
 *   where the platform required it for discovery (API 23–30)
 *
 * Microphone: RECORD_AUDIO, requested contextually when a voice session
 * starts. Notifications: POST_NOTIFICATIONS on API 33+ for the foreground
 * service notification.
 */
object PermissionHelper {

    fun missingWifiDirectPermissions(context: Context): List<String> {
        val needed = when {
            Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            Build.VERSION.SDK_INT >= 29 -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        return needed.filter { !isGranted(context, it) }
    }

    fun missingBluetoothPermissions(context: Context, forDiscovery: Boolean): List<String> {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (forDiscovery) needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
            needed += Manifest.permission.BLUETOOTH_ADVERTISE
        } else if (forDiscovery && Build.VERSION.SDK_INT >= 23) {
            // API 23–30 required location for classic discovery.
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        return needed.filter { !isGranted(context, it) }
    }

    fun missingMicrophonePermission(context: Context): List<String> =
        listOf(Manifest.permission.RECORD_AUDIO).filter { !isGranted(context, it) }

    fun missingNotificationPermission(context: Context): List<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.POST_NOTIFICATIONS).filter { !isGranted(context, it) }
        } else emptyList()

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
}
