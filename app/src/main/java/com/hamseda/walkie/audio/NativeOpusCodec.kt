package com.hamseda.walkie.audio

import android.os.Build

/**
 * Small, auditable JNI bridge to the official libopus implementation.
 *
 * Host-side JVM unit tests do not load Android .so files; they may construct
 * OpusCodec for protocol wiring tests, but actual encode/decode coverage belongs
 * in OpusCodecInstrumentedTest.
 */
internal class NativeOpusCodec {
    internal val isAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    init {
        if (isAvailable) System.loadLibrary("hamseda_opus_jni")
    }

    fun createEncoder(sampleRate: Int, channels: Int, bitrateBps: Int, complexity: Int): Long {
        check(isAvailable) { "Native libopus is only available on Android." }
        return nativeCreateEncoder(sampleRate, channels, bitrateBps, complexity)
    }

    fun createDecoder(sampleRate: Int, channels: Int): Long {
        check(isAvailable) { "Native libopus is only available on Android." }
        return nativeCreateDecoder(sampleRate, channels)
    }

    fun encode(encoder: Long, pcm: ShortArray, frameSamples: Int, output: ByteArray): Int {
        check(isAvailable) { "Native libopus is only available on Android." }
        return nativeEncode(encoder, pcm, frameSamples, output)
    }

    fun decode(
        decoder: Long,
        packet: ByteArray,
        output: ShortArray,
        frameSamples: Int,
        decodeFec: Boolean,
    ): Int {
        check(isAvailable) { "Native libopus is only available on Android." }
        return nativeDecode(decoder, packet, output, frameSamples, decodeFec)
    }

    fun hasInbandFec(packet: ByteArray): Boolean {
        check(isAvailable) { "Native libopus is only available on Android." }
        return nativeHasInbandFec(packet)
    }

    fun destroyEncoder(encoder: Long) {
        if (isAvailable && encoder != 0L) nativeDestroyEncoder(encoder)
    }

    fun destroyDecoder(decoder: Long) {
        if (isAvailable && decoder != 0L) nativeDestroyDecoder(decoder)
    }

    fun version(): String {
        check(isAvailable) { "Native libopus is only available on Android." }
        return nativeVersion()
    }

    private external fun nativeCreateEncoder(
        sampleRate: Int,
        channels: Int,
        bitrateBps: Int,
        complexity: Int,
    ): Long

    private external fun nativeCreateDecoder(sampleRate: Int, channels: Int): Long

    private external fun nativeEncode(
        encoder: Long,
        pcm: ShortArray,
        frameSamples: Int,
        output: ByteArray,
    ): Int

    private external fun nativeDecode(
        decoder: Long,
        packet: ByteArray,
        output: ShortArray,
        frameSamples: Int,
        decodeFec: Boolean,
    ): Int

    private external fun nativeHasInbandFec(packet: ByteArray): Boolean

    private external fun nativeDestroyEncoder(encoder: Long)
    private external fun nativeDestroyDecoder(decoder: Long)
    private external fun nativeVersion(): String
}
