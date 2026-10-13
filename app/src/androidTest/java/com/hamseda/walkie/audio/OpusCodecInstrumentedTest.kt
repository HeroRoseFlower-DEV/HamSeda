package com.hamseda.walkie.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class OpusCodecInstrumentedTest {
    private fun sineFrame(phase: Double = 0.0): ShortArray =
        ShortArray(AudioCodec.FRAME_SAMPLES) { i ->
            (sin(phase + 2.0 * Math.PI * 440.0 * i / AudioCodec.SAMPLE_RATE) * 20_000)
                .toInt().toShort()
        }

    @Test
    fun nativeLibraryIsOfficialLibopus161() {
        OpusCodec().use { codec ->
            assertEquals("libopus ${OpusCodec.LIBOPUS_VERSION}", codec.implementationVersion)
        }
    }

    @Test
    fun encoderComplexityMatchesLegacyApiProfile() {
        OpusCodec().use { codec ->
            assertEquals(
                OpusCodec.recommendedEncoderComplexity(android.os.Build.VERSION.SDK_INT),
                codec.encoderComplexityInUse,
            )
        }
    }

    @Test
    fun encodesAndDecodesOneTwentyMillisecondVoiceFrame() {
        OpusCodec().use { codec ->
            val pcm = sineFrame()
            val encoded = codec.encode(pcm)
            assertTrue("Opus packet should compress speech: ${encoded.size} bytes", encoded.size < 300)

            val decoded = codec.decode(encoded)
            assertEquals(AudioCodec.FRAME_SAMPLES, decoded.size)

            val energyIn = pcm.sumOf { (it.toInt() * it.toInt()).toDouble() }
            val energyOut = decoded.sumOf { (it.toInt() * it.toInt()).toDouble() }
            val ratio = energyOut / energyIn
            assertTrue("energy ratio out of bounds: $ratio", ratio in 0.25..4.0)

            // Compare best correlation across a modest delay window. Opus is lossy
            // and has algorithmic delay, so exact sample-for-sample equality is wrong.
            var best = 0.0
            for (lag in 0..480) {
                var dot = 0.0
                var shiftedEnergy = 0.0
                for (i in pcm.indices) {
                    val sample = if (i + lag < decoded.size) decoded[i + lag].toDouble() else 0.0
                    dot += pcm[i].toDouble() * sample
                    shiftedEnergy += sample * sample
                }
                if (shiftedEnergy > 0.0) best = maxOf(best, dot / sqrt(energyIn * shiftedEnergy))
            }
            assertTrue("correlation too low: $best", best > 0.7)
        }
    }

    @Test
    fun silenceResetAndInputValidationRemainStable() {
        OpusCodec().use { codec ->
            val silence = ShortArray(AudioCodec.FRAME_SAMPLES)
            val decodedSilence = codec.decode(codec.encode(silence))
            assertEquals(AudioCodec.FRAME_SAMPLES, decodedSilence.size)
            assertTrue(
                "silence decoded with peak ${decodedSilence.maxOf { kotlin.math.abs(it.toInt()) }}",
                decodedSilence.maxOf { kotlin.math.abs(it.toInt()) } < 200,
            )

            codec.reset()
            assertEquals(AudioCodec.FRAME_SAMPLES, codec.decode(codec.encode(sineFrame())).size)
            assertThrows(IllegalArgumentException::class.java) { codec.encode(ShortArray(10)) }
            assertThrows(IllegalArgumentException::class.java) { codec.decode(ByteArray(0)) }
            assertThrows(IllegalArgumentException::class.java) {
                codec.decode(ByteArray(OpusCodec.MAX_PACKET_BYTES + 1))
            }
        }
    }
}
