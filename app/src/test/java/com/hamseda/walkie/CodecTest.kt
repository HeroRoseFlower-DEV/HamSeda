package com.hamseda.walkie

import com.hamseda.walkie.audio.AudioCodec
import com.hamseda.walkie.audio.OpusCodec
import com.hamseda.walkie.audio.PcmCodec
import com.hamseda.walkie.proto.CodecId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class CodecTest {

    /** 20 ms of a 440 Hz sine at 16 kHz — deterministic voice-like input. */
    private fun sineFrame(phase: Double = 0.0): ShortArray =
        ShortArray(AudioCodec.FRAME_SAMPLES) { i ->
            (sin(phase + 2.0 * Math.PI * 440.0 * i / AudioCodec.SAMPLE_RATE) * 20000).toInt().toShort()
        }

    @Test
    fun `codec factory creates both codecs`() {
        assertTrue(AudioCodec.create(CodecId.OPUS) is OpusCodec)
        assertTrue(AudioCodec.create(CodecId.PCM16) is PcmCodec)
        assertThrows(IllegalArgumentException::class.java) { AudioCodec.create(0x7F) }
    }

    @Test
    fun `frame constants are 20ms at 16kHz mono`() {
        assertEquals(16000, AudioCodec.SAMPLE_RATE)
        assertEquals(320, AudioCodec.FRAME_SAMPLES)
        assertEquals(640, AudioCodec.FRAME_BYTES_PCM)
    }

    @Test
    fun `pcm roundtrip is bit-exact`() {
        val codec = PcmCodec()
        val pcm = sineFrame()
        assertArrayEquals(pcm, codec.decode(codec.encode(pcm)))
        assertEquals(AudioCodec.FRAME_BYTES_PCM, codec.encode(pcm).size)
    }

    @Test
    fun `pcm rejects short input and bad frames`() {
        val codec = PcmCodec()
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(ShortArray(10))
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode(ByteArray(100))
        }
    }

    @Test
    fun `opus roundtrip preserves the signal shape`() {
        val codec = OpusCodec()
        val pcm = sineFrame()
        val encoded = codec.encode(pcm)
        // ~20 kbps * 20 ms ≈ 50 bytes; assert the compression actually happens.
        assertTrue("opus packet too large: ${encoded.size}", encoded.size < 200)
        val decoded = codec.decode(encoded)
        assertEquals(AudioCodec.FRAME_SAMPLES, decoded.size)
        // Lossy codec: check energy is preserved within a generous bound.
        val energyIn = pcm.sumOf { (it.toInt() * it.toInt()).toDouble() }
        val energyOut = decoded.sumOf { (it.toInt() * it.toInt()).toDouble() }
        val ratio = energyOut / energyIn
        assertTrue("energy ratio out of bounds: $ratio", ratio in 0.25..4.0)
        // And the waveform correlates strongly (not noise).
        var dot = 0.0
        for (i in pcm.indices) dot += pcm[i].toDouble() * decoded[i].toDouble()
        val norm = Math.sqrt(energyIn * energyOut)
        assertTrue("correlation too low: ${dot / norm}", dot / norm > 0.7)
    }

    @Test
    fun `opus handles silence and consecutive frames`() {
        val codec = OpusCodec()
        val silence = ShortArray(AudioCodec.FRAME_SAMPLES)
        val encSilence = codec.encode(silence)
        assertArrayEquals(silence, codec.decode(encSilence))
        // Stream several frames; decoder state must stay consistent.
        repeat(10) { i ->
            val pcm = sineFrame(phase = i * 0.7)
            val decoded = codec.decode(codec.encode(pcm))
            assertEquals(AudioCodec.FRAME_SAMPLES, decoded.size)
        }
    }

    @Test
    fun `opus rejects empty packets`() {
        val codec = OpusCodec()
        assertThrows(IllegalArgumentException::class.java) { codec.decode(ByteArray(0)) }
    }

    @Test
    fun `opus rejects short pcm`() {
        val codec = OpusCodec()
        assertThrows(IllegalArgumentException::class.java) { codec.encode(ShortArray(100)) }
    }
}
