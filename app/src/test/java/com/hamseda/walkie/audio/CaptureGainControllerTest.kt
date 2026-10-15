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

        assertTrue("gain should rise for quiet active input", controller.currentGain > 6f)
        assertTrue("output should be substantially louder", abs(frame[0].toInt()) > 3_000)
        assertTrue(controller.currentGain <= CaptureGainController.DEFAULT_MAX_GAIN)
    }

    @Test
    fun `silence and low-level noise are not amplified`() {
        val controller = CaptureGainController()
        val silence = ShortArray(960)
        val noise = constantFrame(100)

        controller.processInPlace(silence)
        controller.processInPlace(noise)

        assertEquals(1f, controller.currentGain, 0f)
        assertTrue(silence.all { it.toInt() == 0 })
        assertTrue(noise.all { abs(it.toInt()) == 100 })
    }

    @Test
    fun `disabled software gain leaves PCM unchanged and resets gain`() {
        val controller = CaptureGainController()
        repeat(3) { controller.processInPlace(constantFrame(500)) }
        assertTrue(controller.currentGain > 1f)

        val raw = constantFrame(700)
        val original = raw.copyOf()
        controller.processInPlace(raw, enabled = false)

        assertEquals(1f, controller.currentGain, 0f)
        assertTrue(raw.contentEquals(original))
        assertEquals(700f, controller.lastInputRms, 0f)
    }

    @Test
    fun `loud transient reduces gain immediately and preserves peak headroom`() {
        val controller = CaptureGainController()
        repeat(4) { controller.processInPlace(constantFrame(400)) }

        val loud = ShortArray(960) { Short.MAX_VALUE }
        controller.processInPlace(loud)

        assertTrue("gain must reduce for a full-scale transient", controller.currentGain < 1f)
        assertTrue(loud.maxOf { abs(it.toInt()) } <=
            CaptureGainController.DEFAULT_MAX_OUTPUT_PEAK.toInt())
    }

    @Test
    fun `negative full scale samples do not overflow`() {
        val controller = CaptureGainController()
        repeat(4) { controller.processInPlace(constantFrame(400)) }
        val loud = ShortArray(960) { Short.MIN_VALUE }

        controller.processInPlace(loud)

        assertTrue(loud.all { it.toInt() in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt() })
        assertTrue(loud.minOf { it.toInt() } >=
            -CaptureGainController.DEFAULT_MAX_OUTPUT_PEAK.toInt())
    }

    @Test
    fun `silent frame after active speech immediately releases software gain`() {
        val controller = CaptureGainController()
        repeat(3) { controller.processInPlace(constantFrame(500)) }
        val silence = ShortArray(960)

        controller.processInPlace(silence)

        assertEquals(1f, controller.currentGain, 0f)
        assertTrue(silence.all { it == 0.toShort() })
    }

    private fun constantFrame(value: Int) =
        ShortArray(960) { if (it % 2 == 0) value.toShort() else (-value).toShort() }
}
