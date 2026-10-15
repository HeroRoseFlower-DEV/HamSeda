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
    fun encoderComplexityMatchesApiProfile() {
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
    fun inBandFecCanRecoverOnePriorFrameWhenPresent() {
        OpusCodec().use { codec ->
            fun voicedFrame(frameIndex: Int): ShortArray = ShortArray(AudioCodec.FRAME_SAMPLES) { i ->
                val sampleIndex = frameIndex * AudioCodec.FRAME_SAMPLES + i
                val t = sampleIndex.toDouble() / AudioCodec.SAMPLE_RATE
                val value =
                    sin(2.0 * Math.PI * 130.0 * t) * 9_000.0 +
                    sin(2.0 * Math.PI * 260.0 * t) * 5_000.0 +
                    sin(2.0 * Math.PI * 520.0 * t) * 2_500.0
                value.toInt().toShort()
            }

            var foundFec = false
            for (index in 0..80) {
                val pcm = voicedFrame(index)
                val packet = codec.encode(pcm)
                pcm.fill(0)
                val recovered = codec.decodePreviousFrameFec(packet)
                if (recovered != null) {
                    assertEquals(AudioCodec.FRAME_SAMPLES, recovered.size)
                    recovered.fill(0)
                    val current = codec.decode(packet)
                    assertEquals(AudioCodec.FRAME_SAMPLES, current.size)
                    current.fill(0)
                    foundFec = true
                    packet.fill(0)
                    break
                }
                // Keep encoder and decoder histories aligned before the next
                // packet so this test exercises a realistic continuous stream.
                val current = codec.decode(packet)
                current.fill(0)
                packet.fill(0)
            }
            assertTrue("no Opus packet exposed in-band LBRR in the test speech stream", foundFec)
        }
    }

    @Test
    fun silenceResetAndInputValidationRemainStable() {
        OpusCodec().use { codec ->
            val silence = ShortArray(AudioCodec.FRAME_SAMPLES)
            val firstSilencePacket = codec.encode(silence)
            val decodedSilence = codec.decode(firstSilencePacket)
            assertEquals(AudioCodec.FRAME_SAMPLES, decodedSilence.size)
            assertTrue(
                "silence decoded with peak ${decodedSilence.maxOf { kotlin.math.abs(it.toInt()) }}",
                decodedSilence.maxOf { kotlin.math.abs(it.toInt()) } < 200,
            )
            firstSilencePacket.fill(0)

            // DTX is stateful; sustain silence and decode in order to exercise it.
            val dtxPackets = List(20) { codec.encode(silence) }
            try {
                assertTrue(
                    "DTX should compact sustained silence packets: ${dtxPackets.map { it.size }}",
                    dtxPackets.any { it.size <= 10 },
                )
                dtxPackets.forEach { packet ->
                    val decoded = codec.decode(packet)
                    assertEquals(AudioCodec.FRAME_SAMPLES, decoded.size)
                    decoded.fill(0)
                }
            } finally {
                dtxPackets.forEach { it.fill(0) }
            }

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
