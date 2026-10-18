package com.hamseda.walkie.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the pure transport-recommendation decision table
 * ([recommendTransport]), extracted from [TransportManager] so the policy
 * matrix is testable without Android radios.
 */
class TransportRecommendationTest {

    @Test
    fun wifiDirectOn_winsRegardlessOfBluetooth() {
        assertEquals(
            TransportType.WIFI_DIRECT,
            recommendTransport(
                wifiSupported = true, wifiEnabled = true,
                btSupported = true, btEnabled = true,
            ),
        )
        assertEquals(
            TransportType.WIFI_DIRECT,
            recommendTransport(
                wifiSupported = true, wifiEnabled = true,
                btSupported = false, btEnabled = false,
            ),
        )
    }

    @Test
    fun usableBluetooth_outranksWifiRadioThatIsOff() {
        assertEquals(
            TransportType.BLUETOOTH,
            recommendTransport(
                wifiSupported = true, wifiEnabled = false,
                btSupported = true, btEnabled = true,
            ),
        )
    }

    @Test
    fun wifiHardwarePresent_butNothingUsable_fallsBackToWifiDirect() {
        // The user can still enable the Wi-Fi radio manually.
        assertEquals(
            TransportType.WIFI_DIRECT,
            recommendTransport(
                wifiSupported = true, wifiEnabled = false,
                btSupported = true, btEnabled = false,
            ),
        )
        assertEquals(
            TransportType.WIFI_DIRECT,
            recommendTransport(
                wifiSupported = true, wifiEnabled = false,
                btSupported = false, btEnabled = false,
            ),
        )
    }

    @Test
    fun noWifiHardware_usesBluetooth() {
        assertEquals(
            TransportType.BLUETOOTH,
            recommendTransport(
                wifiSupported = false, wifiEnabled = false,
                btSupported = true, btEnabled = true,
            ),
        )
        assertEquals(
            TransportType.BLUETOOTH,
            recommendTransport(
                wifiSupported = false, wifiEnabled = false,
                btSupported = true, btEnabled = false,
            ),
        )
    }

    @Test
    fun nothingSupported_defaultsToBluetooth() {
        assertEquals(
            TransportType.BLUETOOTH,
            recommendTransport(
                wifiSupported = false, wifiEnabled = false,
                btSupported = false, btEnabled = false,
            ),
        )
    }
}
