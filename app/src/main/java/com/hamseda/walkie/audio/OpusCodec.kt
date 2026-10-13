package com.hamseda.walkie.audio

import android.os.Build
import com.hamseda.walkie.proto.CodecId
import java.io.Closeable

/**
 * Real-time speech codec backed by the official native libopus 1.6.1 build.
 *
 * Profile: 48 kHz mono PCM16, 20 ms frames, VOIP application, 40 kbps,
 * voice signal hint, full-band maximum bandwidth and unconstrained VBR.
 * Android 8/9 use complexity 8 to reduce encoder CPU cost on older hardware;
 * API 29+ keep complexity 10. The upstream source archive is hash-pinned in CMake;
 * the codec is packaged in the APK and does not download anything at runtime.
 *
 * JVM tests can construct this class for protocol wiring, but real encode /
 * decode operations require Android's packaged native library and are covered
 * by OpusCodecInstrumentedTest.
 */
class OpusCodec(
    private val bitrateBps: Int = DEFAULT_BITRATE_BPS,
) : AudioCodec, Closeable {
    override val codecId: Byte = CodecId.OPUS
    override val sampleRate: Int = AudioCodec.SAMPLE_RATE
    override val channels: Int = AudioCodec.CHANNELS
    override val frameSamples: Int = AudioCodec.FRAME_SAMPLES

    private val native = NativeOpusCodec()
    private var encoderHandle: Long = 0L
    private var decoderHandle: Long = 0L
    private val encodeBuf = ByteArray(MAX_PACKET_BYTES)
    private val lock = Any()
    private val selectedEncoderComplexity = recommendedEncoderComplexity(Build.VERSION.SDK_INT)

    /** Actual encoder complexity selected for this Android API level (8 on API 26–28, 10 otherwise). */
    val encoderComplexityInUse: Int get() = selectedEncoderComplexity

    /** Reports the actual version string returned by the linked native library. */
    val implementationVersion: String
        get() = native.version()

    init {
        reset()
    }

    override fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size >= frameSamples) {
            "need $frameSamples samples, got ${pcm.size}"
        }
        synchronized(lock) {
            val enc = encoderHandle
            check(enc != 0L) {
                "Native libopus is unavailable in this runtime; run codec I/O tests on Android."
            }
            val n = native.encode(enc, pcm, frameSamples, encodeBuf)
            require(n > 0) { "opus encode failed (native result=$n)" }
            return encodeBuf.copyOf(n)
        }
    }

    override fun decode(data: ByteArray): ShortArray {
        require(data.isNotEmpty()) { "empty opus packet" }
        require(data.size <= MAX_PACKET_BYTES) {
            "Opus packet exceeds maximum size $MAX_PACKET_BYTES"
        }
        synchronized(lock) {
            val dec = decoderHandle
            check(dec != 0L) {
                "Native libopus is unavailable in this runtime; run codec I/O tests on Android."
            }
            val out = ShortArray(frameSamples)
            // The app sends one 20 ms frame per packet. FEC stays off because
            // TCP/RFCOMM are reliable byte streams and retransmit transport loss.
            val decodedSamples = native.decode(dec, data, out, frameSamples, decodeFec = false)
            require(decodedSamples >= 0) {
                "opus decode failed (native result=$decodedSamples)"
            }
            require(decodedSamples == frameSamples) {
                "expected $frameSamples decoded samples, got $decodedSamples"
            }
            return out
        }
    }

    override fun reset() {
        synchronized(lock) {
            releaseHandlesLocked()

            // Plain JVM tests have no Android ABI-specific .so. They can wire
            // the AudioCodec factory without exercising native audio processing.
            if (!native.isAvailable) return

            val newEncoder = native.createEncoder(
                sampleRate = sampleRate,
                channels = channels,
                bitrateBps = bitrateBps,
                complexity = selectedEncoderComplexity,
            )
            check(newEncoder != 0L) { "libopus could not create an encoder" }

            var newDecoder = 0L
            try {
                newDecoder = native.createDecoder(sampleRate, channels)
                check(newDecoder != 0L) { "libopus could not create a decoder" }
                encoderHandle = newEncoder
                decoderHandle = newDecoder
            } catch (error: Throwable) {
                native.destroyDecoder(newDecoder)
                native.destroyEncoder(newEncoder)
                throw error
            }
        }
    }

    private fun releaseHandlesLocked() {
        val oldEncoder = encoderHandle
        val oldDecoder = decoderHandle
        encoderHandle = 0L
        decoderHandle = 0L
        native.destroyEncoder(oldEncoder)
        native.destroyDecoder(oldDecoder)
    }

    override fun close() {
        synchronized(lock) {
            releaseHandlesLocked()
        }
    }

    companion object {
        const val LIBOPUS_VERSION = "1.6.1"
        const val DEFAULT_BITRATE_BPS = 40_000
        const val ENCODER_COMPLEXITY = 10
        const val LEGACY_ANDROID_ENCODER_COMPLEXITY = 8
        const val MAX_PACKET_BYTES = 1275

        /** Pure selector for the conservative CPU profile used on Oreo/Pie-era devices. */
        fun recommendedEncoderComplexity(apiLevel: Int): Int =
            if (apiLevel in 26..28) LEGACY_ANDROID_ENCODER_COMPLEXITY else ENCODER_COMPLEXITY
    }
}
