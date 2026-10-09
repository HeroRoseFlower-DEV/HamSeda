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
 *   info), BLUETOOTH_ADVERTISE only when this phone is made discoverable.
 * - API ≤30: BLUETOOTH + BLUETOOTH_ADMIN (install-time) and location only
 *   where the platform required it for discovery (API 23–30)
 *
 * Microphone: RECORD_AUDIO, requested contextually when a voice session
 * starts. Notifications: POST_NOTIFICATIONS on API 33+ for the foreground
 * service notification.
 *
 * Local network (API 37+): Android 17 blocks local-network access by
 * default for apps targeting API 37 — including TCP connections to Wi-Fi
 * Direct group addresses. ACCESS_LOCAL_NETWORK must be declared and
 * requested at runtime before any P2P socket is opened. Apps targeting
 * API ≤ 36 keep implicit access through INTERNET.
 */
object PermissionHelper {

    /** API level where ACCESS_LOCAL_NETWORK enforcement begins. */
    const val LOCAL_NETWORK_SDK = 37

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
            // Connecting to/listening for RFCOMM peers and reading their
            // device metadata needs CONNECT. ADVERTISE is separate and is
            // requested only immediately before this phone is made visible.
            needed += Manifest.permission.BLUETOOTH_CONNECT
        } else if (forDiscovery && Build.VERSION.SDK_INT >= 23) {
            // API 23–30 required location for classic discovery.
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        return needed.filter { !isGranted(context, it) }
    }

    /** Runtime permission needed only when asking Android to make this phone discoverable. */
    fun missingBluetoothAdvertisePermission(context: Context): List<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_ADVERTISE).filter { !isGranted(context, it) }
        } else emptyList()

    fun missingMicrophonePermission(context: Context): List<String> =
        listOf(Manifest.permission.RECORD_AUDIO).filter { !isGranted(context, it) }

    /**
     * Local-network access (API 37+ only). Required before opening any
     * Wi-Fi Direct TCP socket when targeting API 37 — without it the
     * platform silently blocks connections to P2P group addresses.
     */
    fun missingLocalNetworkPermission(context: Context): List<String> =
        if (Build.VERSION.SDK_INT >= LOCAL_NETWORK_SDK) {
            listOf(Manifest.permission.ACCESS_LOCAL_NETWORK)
                .filter { !isGranted(context, it) }
        } else emptyList()

    /**
     * Everything Wi-Fi Direct needs for a full connect: discovery
     * permissions plus local-network access on API 37+.
     */
    fun missingWifiDirectConnectPermissions(context: Context): List<String> =
        (missingWifiDirectPermissions(context) + missingLocalNetworkPermission(context))
            .distinct()

    fun missingNotificationPermission(context: Context): List<String> =
        // L8: kept for API completeness; currently unused because the app
        // only posts its foreground-service notification (exempt from
        // POST_NOTIFICATIONS on API 33+).
        if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.POST_NOTIFICATIONS).filter { !isGranted(context, it) }
        } else emptyList()

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
}
