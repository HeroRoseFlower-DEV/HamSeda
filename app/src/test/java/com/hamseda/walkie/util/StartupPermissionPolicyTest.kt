package com.hamseda.walkie.util

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupPermissionPolicyTest {

    @Test
    fun api26_usesLegacyCoarseLocationAndMicrophone() {
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.RECORD_AUDIO,
            ),
            PermissionHelper.startupRuntimePermissionNames(26),
        )
    }

    @Test
    fun api29_usesFineLocationAndMicrophone() {
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.RECORD_AUDIO,
            ),
            PermissionHelper.startupRuntimePermissionNames(29),
        )
    }

    @Test
    fun api31_requiresBluetoothRuntimePermissionsAndLegacyWifiLocation() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.RECORD_AUDIO,
            ),
            PermissionHelper.startupRuntimePermissionNames(31),
        )
    }

    @Test
    fun api33_usesNearbyDevicesInsteadOfLocationAndDoesNotRequireLocalNetwork() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.RECORD_AUDIO,
            ),
            PermissionHelper.startupRuntimePermissionNames(33),
        )
    }

    @Test
    fun api36_doesNotRequireApi37LocalNetworkPermission() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.RECORD_AUDIO,
            ),
            PermissionHelper.startupRuntimePermissionNames(36),
        )
    }

    @Test
    fun api37_requiresLocalNetworkPermissionBeforeMainUi() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
                Manifest.permission.RECORD_AUDIO,
            ),
            PermissionHelper.startupRuntimePermissionNames(37),
        )
    }

    @Test
    fun api37_policyIsStableForFuturePlatformVersions() {
        assertEquals(
            PermissionHelper.startupRuntimePermissionNames(37),
            PermissionHelper.startupRuntimePermissionNames(100),
        )
    }
}
