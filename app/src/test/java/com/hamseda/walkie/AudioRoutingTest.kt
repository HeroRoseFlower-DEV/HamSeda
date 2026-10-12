package com.hamseda.walkie

import android.media.AudioDeviceInfo
import com.hamseda.walkie.audio.AudioEngine
import com.hamseda.walkie.audio.deviceTypesForRoute
import com.hamseda.walkie.audio.routeFromDeviceTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRoutingTest {
    @Test
    fun `speaker route prefers the built in speaker`() {
        assertEquals(
            listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            deviceTypesForRoute(AudioEngine.AudioRoute.SPEAKER),
        )
    }

    @Test
    fun `wired route accepts headset and headphones device types`() {
        assertEquals(
            listOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            ),
            deviceTypesForRoute(AudioEngine.AudioRoute.WIRED_HEADSET),
        )
    }

    @Test
    fun `route detection prioritizes wired over bluetooth and speaker`() {
        assertEquals(
            AudioEngine.AudioRoute.WIRED_HEADSET,
            routeFromDeviceTypes(
                setOf(
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                ),
                speakerphoneOn = true,
            ),
        )
    }

    @Test
    fun `route detection honors speaker preference when no headset is present`() {
        assertEquals(
            AudioEngine.AudioRoute.SPEAKER,
            routeFromDeviceTypes(setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), speakerphoneOn = true),
        )
        assertEquals(
            AudioEngine.AudioRoute.EARPIECE,
            routeFromDeviceTypes(setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE), speakerphoneOn = false),
        )
    }
}
