package com.hamseda.walkie.audio

import com.hamseda.walkie.proto.CodecId
import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import org.concentus.OpusException
import org.concentus.OpusSignal

/**
 * Opus voice codec via the vendored Concentus pure-Java port
 * (see `org/concentus/VENDORED_FROM.md`).
 *
 * Configuration: 16 kHz mono, 20 ms frames, VOIP application, 24 kbps
 * voice target, moderate complexity and constrained VBR. This trades a
 * little bandwidth for clearer speech while avoiding large packet bursts
 * on Bluetooth RFCOMM.
 *
 * The codec is bundled in the APK: nothing is downloaded at runtime.
 */
class OpusCodec(
    private val bitrateBps: Int = DEFAULT_BITRATE_BPS,
) : AudioCodec {
    override val codecId: Byte = CodecId.OPUS
    override val sampleRate: Int = AudioCodec.SAMPLE_RATE
    override val channels: Int = AudioCodec.CHANNELS
    override val frameSamples: Int = AudioCodec.FRAME_SAMPLES

    private var encoder: OpusEncoder? = null
    private var decoder: OpusDecoder? = null
    private val encodeBuf = ByteArray(MAX_PACKET_BYTES)
    private val lock = Any()

    init {
        reset()
    }

    override fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size >= frameSamples) { "need $frameSamples samples, got ${pcm.size}" }
        synchronized(lock) {
            val enc = encoder ?: throw IllegalStateException("codec closed")
            try {
                val n = enc.encode(pcm, 0, frameSamples, encodeBuf, 0, encodeBuf.size)
                require(n > 0) { "opus encoder produced empty packet" }
                return encodeBuf.copyOf(n)
            } catch (e: OpusException) {
                throw IllegalArgumentException("opus encode failed", e)
            }
        }
    }

    override fun decode(data: ByteArray): ShortArray {
        require(data.isNotEmpty()) { "empty opus packet" }
        require(data.size <= MAX_PACKET_BYTES) {
            "Opus packet exceeds maximum size $MAX_PACKET_BYTES"
        }
        synchronized(lock) {
            val dec = decoder ?: throw IllegalStateException("codec closed")
            val out = ShortArray(frameSamples)
            try {
                // decodeFec=false: lost frames are concealed by the player
                // (last-frame repeat with decay), not by the codec.
                dec.decode(data, 0, data.size, out, 0, frameSamples, false)
                return out
            } catch (e: OpusException) {
                throw IllegalArgumentException("opus decode failed", e)
            }
        }
    }

    override fun reset() {
        synchronized(lock) {
            encoder = OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_VOIP)
                .also {
                    it.bitrate = bitrateBps
                    it.complexity = ENCODER_COMPLEXITY
                    it.signalType = OpusSignal.OPUS_SIGNAL_VOICE
                    it.useConstrainedVBR = true
                }
            decoder = OpusDecoder(sampleRate, channels)
        }
    }

    override fun close() {
        synchronized(lock) {
            encoder = null
            decoder = null
        }
    }

    companion object {
        const val DEFAULT_BITRATE_BPS = 24_000
        const val ENCODER_COMPLEXITY = 7
        const val MAX_PACKET_BYTES = 512
    }
}
