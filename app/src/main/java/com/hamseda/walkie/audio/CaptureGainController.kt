package com.hamseda.walkie.audio

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bounded, stateful microphone leveler applied to PCM before encoding.
 *
 * Android's hardware/vendor AGC remains enabled when available, but its behavior
 * is not consistent across devices. This software stage brings quiet active
 * speech toward a useful RMS level while limiting maximum gain and peak level.
 * It deliberately does not amplify frames at the near-silence noise floor.
 *
 * This cannot improve signal-to-noise ratio: speech that is physically buried
 * under microphone noise still requires a closer source or a better microphone.
 */
internal class CaptureGainController(
    private val targetRms: Float = DEFAULT_TARGET_RMS,
    private val maxGain: Float = DEFAULT_MAX_GAIN,
    private val maxOutputPeak: Float = DEFAULT_MAX_OUTPUT_PEAK,
    private val minimumActiveRms: Float = MINIMUM_ACTIVE_RMS,
    private val gainRiseSmoothing: Float = GAIN_RISE_SMOOTHING,
) {
    var currentGain: Float = 1f
        private set

    var lastInputRms: Float = 0f
        private set

    /**
     * Adjusts samples in place and returns the applied linear gain.
     * Gain reduction is immediate for loud transients; gain increases are
     * smoothed over several 20 ms frames to avoid audible pumping.
     */
    fun processInPlace(samples: ShortArray): Float {
        if (samples.isEmpty()) return currentGain

        var sumSquares = 0.0
        var peak = 0
        for (sample in samples) {
            val value = sample.toInt()
            sumSquares += value.toDouble() * value.toDouble()
            peak = maxOf(peak, kotlin.math.abs(value))
        }

        val rms = sqrt(sumSquares / samples.size).toFloat()
        lastInputRms = rms

        // Do not lift the near-silence floor: otherwise quiet-room electronic
        // noise can become as loud as speech when the PTT button is held.
        var desiredGain = if (rms < minimumActiveRms) {
            1f
        } else {
            min(maxGain, targetRms / rms.coerceAtLeast(1f))
        }

        // Preserve headroom for transient consonants and clicks. This ceiling
        // is applied before multiplication, so samples never wrap on conversion.
        if (peak > 0) {
            desiredGain = min(desiredGain, maxOutputPeak / peak.toFloat())
        }
        desiredGain = desiredGain.coerceAtLeast(0f)

        currentGain = if (desiredGain < currentGain) {
            // Fast gain reduction limits clipping and sudden loud bursts.
            desiredGain
        } else {
            // Slow upward adaptation helps quiet or distant speech without
            // magnifying every frame-to-frame fluctuation.
            (currentGain + (desiredGain - currentGain) * gainRiseSmoothing)
                .coerceAtMost(maxGain)
        }

        for (i in samples.indices) {
            val amplified = (samples[i].toInt() * currentGain).toInt()
            samples[i] = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return currentGain
    }

    companion object {
        const val DEFAULT_TARGET_RMS = 5_500f
        const val DEFAULT_MAX_GAIN = 8f // +18.1 dB maximum software gain
        const val DEFAULT_MAX_OUTPUT_PEAK = 30_000f
        const val MINIMUM_ACTIVE_RMS = 24f
        const val GAIN_RISE_SMOOTHING = 0.55f
    }
}
