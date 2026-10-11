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
    private fun sineFrame(): ShortArray =
        ShortArray(AudioCodec.FRAME_SAMPLES) { i ->
            (sin(2.0 * Math.PI * 440.0 * i / AudioCodec.SAMPLE_RATE) * 20000).toInt().toShort()
        }

    @Test
    fun `codec factory creates both codecs`() {
        // Opus construction is safe on the host JVM; native I/O is covered on Android.
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
        assertThrows(IllegalArgumentException::class.java) { codec.encode(ShortArray(10)) }
        assertThrows(IllegalArgumentException::class.java) { codec.decode(ByteArray(100)) }
    }

    @Test
    fun `opus profile is configured for low-latency speech`() {
        OpusCodec().use { codec ->
            assertEquals(CodecId.OPUS, codec.codecId)
            assertEquals(16000, codec.sampleRate)
            assertEquals(1, codec.channels)
            assertEquals(320, codec.frameSamples)
            assertEquals(24_000, OpusCodec.DEFAULT_BITRATE_BPS)
            assertEquals(7, OpusCodec.ENCODER_COMPLEXITY)
            // The loaded native-library version is checked by the Android instrumentation test.
        }
    }
}
