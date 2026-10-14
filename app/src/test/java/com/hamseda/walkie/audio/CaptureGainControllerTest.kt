package com.hamseda.walkie.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CaptureGainControllerTest {
    @Test
    fun `quiet active signal is raised over consecutive frames`() {
        val controller = CaptureGainController()
        var frame = constantFrame(500)

        repeat(3) {
            frame = constantFrame(500)
            controller.processInPlace(frame)
        }

        assertTrue("gain should increase for quiet active input", controller.currentGain > 6f)
        assertTrue("output should be substantially louder", abs(frame[0].toInt()) > 3_000)
        assertTrue("gain remains capped", controller.currentGain <= CaptureGainController.DEFAULT_MAX_GAIN)
    }

    @Test
    fun `near silence is not amplified`() {
        val controller = CaptureGainController()
        val silence = ShortArray(960)
        val lowFloor = constantFrame(10)

        controller.processInPlace(silence)
        controller.processInPlace(lowFloor)

        assertEquals(1f, controller.currentGain, 0f)
        assertTrue(silence.all { it.toInt() == 0 })
        assertTrue(lowFloor.all { it.toInt() == 10 })
    }

    @Test
    fun `sudden loud input reduces gain immediately and preserves peak headroom`() {
        val controller = CaptureGainController()
        repeat(4) { controller.processInPlace(constantFrame(400)) }

        val loud = ShortArray(960) { Short.MAX_VALUE }
        controller.processInPlace(loud)

        assertTrue("loud transient should reduce gain immediately", controller.currentGain < 0.2f)
        assertTrue("samples stay below the configured peak ceiling",
            loud.maxOf { abs(it.toInt()) } <= CaptureGainController.DEFAULT_MAX_OUTPUT_PEAK.toInt())
    }

    @Test
    fun `negative full scale samples do not overflow`() {
        val controller = CaptureGainController()
        repeat(4) { controller.processInPlace(constantFrame(400)) }
        val loud = ShortArray(960) { Short.MIN_VALUE }

        controller.processInPlace(loud)

        assertTrue(loud.all { it.toInt() in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt() })
        assertTrue(loud.minOf { it.toInt() } >= -CaptureGainController.DEFAULT_MAX_OUTPUT_PEAK.toInt())
    }

    private fun constantFrame(value: Int) = ShortArray(960) { value.toShort() }
}
