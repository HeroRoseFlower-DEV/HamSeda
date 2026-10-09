package com.hamseda.walkie.audio

import com.hamseda.walkie.proto.CodecId
import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import org.concentus.OpusException

/**
 * Opus voice codec via the vendored Concentus pure-Java port
 * (see `org/concentus/VENDORED_FROM.md`).
 *
 * Configuration: 16 kHz, mono, 20 ms frames (320 samples),
 * VOIP application, ~20 kbps target bitrate — a low-latency speech
 * setting validated for real-time push-to-talk. A 20 ms frame encodes to
 * roughly 40–60 bytes, leaving ample headroom on both transports.
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
                .also { it.bitrate = bitrateBps }
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
        const val DEFAULT_BITRATE_BPS = 20_000
        const val MAX_PACKET_BYTES = 512
    }
}
