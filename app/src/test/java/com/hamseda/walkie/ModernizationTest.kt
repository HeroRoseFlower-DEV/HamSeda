package com.hamseda.walkie

import android.media.AudioDeviceInfo
import com.hamseda.walkie.audio.AudioEngine
import com.hamseda.walkie.audio.deviceTypesForRoute
import com.hamseda.walkie.audio.routeFromDeviceTypes
import com.hamseda.walkie.transport.BluetoothTransport
import com.hamseda.walkie.transport.stalePeerIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the latest-stack modernization pass:
 * - API 31+ communication-device routing decisions (pure functions)
 * - Bluetooth discovered-peer TTL expiration (pure function)
 *
 * The Android framework calls behind these decisions (setCommunicationDevice,
 * availableCommunicationDevices, startDiscovery) require a device/emulator
 * and are NOT covered here — see docs/TEST_PLAN.md for the hardware matrix.
 */
class ModernizationTest {

    // ------------------------------------------------- audio routing (API 31+)

    @Test
    fun `wired route prefers headset then headphones device types`() {
        val types = deviceTypesForRoute(AudioEngine.AudioRoute.WIRED_HEADSET)
        assertEquals(
            listOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            ),
            types,
        )
    }

    @Test
    fun `bluetooth sco route selects bluetooth sco device type`() {
        assertEquals(
            listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
            deviceTypesForRoute(AudioEngine.AudioRoute.BLUETOOTH_SCO),
        )
    }

    @Test
    fun `speaker and earpiece routes select builtin devices`() {
        assertEquals(
            listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            deviceTypesForRoute(AudioEngine.AudioRoute.SPEAKER),
        )
        assertEquals(
            listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE),
            deviceTypesForRoute(AudioEngine.AudioRoute.EARPIECE),
        )
    }

    @Test
    fun `route detection prioritizes wired over bluetooth sco`() {
        val types = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        assertEquals(
            AudioEngine.AudioRoute.WIRED_HEADSET,
            routeFromDeviceTypes(types, speakerphoneOn = true),
        )
    }

    @Test
    fun `route detection accepts wired headphones variant`() {
        val types = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        assertEquals(
            AudioEngine.AudioRoute.WIRED_HEADSET,
            routeFromDeviceTypes(types, speakerphoneOn = false),
        )
    }

    @Test
    fun `route detection falls back to speaker or earpiece preference`() {
        val types = setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertEquals(
            AudioEngine.AudioRoute.SPEAKER,
            routeFromDeviceTypes(types, speakerphoneOn = true),
        )
        assertEquals(
            AudioEngine.AudioRoute.EARPIECE,
            routeFromDeviceTypes(types, speakerphoneOn = false),
        )
    }

    @Test
    fun `route detection with no devices honors speaker preference`() {
        assertEquals(
            AudioEngine.AudioRoute.SPEAKER,
            routeFromDeviceTypes(emptySet(), speakerphoneOn = true),
        )
        assertEquals(
            AudioEngine.AudioRoute.EARPIECE,
            routeFromDeviceTypes(emptySet(), speakerphoneOn = false),
        )
    }

    // --------------------------------------- bluetooth discovered-peer TTL

    @Test
    fun `stale peers expire exactly after the TTL`() {
        val ttl = BluetoothTransport.FOUND_PEER_TTL_MS
        val now = 1_000_000L
        val lastSeen = mapOf(
            "fresh" to now,
            "boundary" to (now - ttl), // exactly ttl old: NOT stale (strict >)
            "stale" to (now - ttl - 1),
            "ancient" to (now - 10 * ttl),
        )
        val stale = stalePeerIds(lastSeen, now, ttl)
        assertEquals(setOf("stale", "ancient"), stale)
    }

    @Test
    fun `no peers expire when all are fresh`() {
        val ttl = BluetoothTransport.FOUND_PEER_TTL_MS
        val now = 500_000L
        val lastSeen = mapOf("a" to now, "b" to (now - 1_000))
        assertTrue(stalePeerIds(lastSeen, now, ttl).isEmpty())
    }

    @Test
    fun `empty peer map expires nothing`() {
        assertTrue(
            stalePeerIds(emptyMap(), 0L, BluetoothTransport.FOUND_PEER_TTL_MS).isEmpty(),
        )
    }
}
