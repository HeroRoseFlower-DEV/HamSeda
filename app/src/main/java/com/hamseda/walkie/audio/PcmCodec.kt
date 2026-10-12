package com.hamseda.walkie.audio

import com.hamseda.walkie.proto.CodecId
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pass-through 16-bit little-endian PCM codec.
 *
 * Bandwidth: 48 kHz × 16 bit × mono = 768 kbps — appropriate only for
 * diagnostics over Wi-Fi Direct, not for Bluetooth Classic RFCOMM. Opus is
 * the default and is strongly preferred for voice.
 */
class PcmCodec : AudioCodec {
    override val codecId: Byte = CodecId.PCM16
    override val sampleRate: Int = AudioCodec.SAMPLE_RATE
    override val channels: Int = AudioCodec.CHANNELS
    override val frameSamples: Int = AudioCodec.FRAME_SAMPLES

    override fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size >= frameSamples) { "need $frameSamples samples, got ${pcm.size}" }
        val buf = ByteBuffer.allocate(frameSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameSamples) buf.putShort(pcm[i])
        return buf.array()
    }

    override fun decode(data: ByteArray): ShortArray {
        require(data.size == frameSamples * 2) {
            "PCM frame must be ${frameSamples * 2} bytes, got ${data.size}"
        }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(frameSamples) { buf.short }
    }

    override fun reset() = Unit
    override fun close() = Unit
}
