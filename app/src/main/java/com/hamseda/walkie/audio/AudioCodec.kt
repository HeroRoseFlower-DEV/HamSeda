package com.hamseda.walkie.audio

import com.hamseda.walkie.proto.CodecId

/**
 * Voice codec abstraction. All codecs operate on 20 ms mono frames at
 * 16 kHz (320 samples) — the unit the audio engine captures and plays.
 *
 * Two implementations ship:
 * - [OpusCodec] (default): Opus via the vendored Concentus pure-Java port,
 *   ~20 kbps. Comfortable on both Wi-Fi Direct and Bluetooth Classic.
 * - [PcmCodec] (fallback): raw 16-bit PCM, 256 kbps. Documented limitation:
 *   fine on Wi-Fi Direct, heavy for Bluetooth Classic; selectable in
 *   Settings for diagnostics.
 */
interface AudioCodec {
    val codecId: Byte
    val sampleRate: Int
    val channels: Int

    /** Samples per 20 ms frame — always 320 at 16 kHz mono. */
    val frameSamples: Int

    /**
     * Encodes exactly [frameSamples] samples.
     * @throws IllegalArgumentException if [pcm] is shorter than [frameSamples].
     */
    fun encode(pcm: ShortArray): ByteArray

    /**
     * Decodes one encoded frame back to [frameSamples] samples.
     * @throws IllegalArgumentException on malformed input.
     */
    fun decode(data: ByteArray): ShortArray

    /** Resets encoder/decoder state (called on session start). */
    fun reset()

    fun close()

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNELS = 1
        const val FRAME_SAMPLES = SAMPLE_RATE / 50 // 20 ms
        const val FRAME_BYTES_PCM = FRAME_SAMPLES * 2

        fun create(codecId: Byte): AudioCodec = when (codecId) {
            CodecId.OPUS -> OpusCodec()
            CodecId.PCM16 -> PcmCodec()
            else -> throw IllegalArgumentException("unknown codec id: $codecId")
        }
    }
}
