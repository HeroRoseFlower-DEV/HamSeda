package com.hamseda.walkie.audio

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bounded software microphone leveler applied to PCM after AudioRecord returns
 * samples (and therefore after platform capture effects, when they are active).
 *
 * The RMS gate avoids boosting low-level background noise. This cannot recover
 * speech already buried in the microphone noise floor; the threshold must be
 * validated with real recordings on supported devices.
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

    init {
        require(targetRms > 0f)
        require(maxGain >= 1f)
        require(maxOutputPeak in 1f..Short.MAX_VALUE.toFloat())
        require(minimumActiveRms >= 0f)
        require(gainRiseSmoothing in 0f..1f)
    }

    /**
     * Measures the input and adjusts samples in place when enabled. Gain
     * reduction is immediate; gain increases are smoothed to reduce pumping.
     * When disabled, PCM is unchanged and the applied gain returns to unity.
     */
    fun processInPlace(samples: ShortArray, enabled: Boolean = true): Float {
        if (samples.isEmpty()) {
            lastInputRms = 0f
            if (!enabled) currentGain = 1f
            return currentGain
        }

        var sumSquares = 0.0
        var peak = 0
        for (sample in samples) {
            val value = sample.toInt()
            sumSquares += value.toDouble() * value.toDouble()
            peak = maxOf(peak, abs(value))
        }

        lastInputRms = sqrt(sumSquares / samples.size).toFloat()
        if (!enabled) {
            currentGain = 1f
            return currentGain
        }

        // Below this voice threshold, do not lift the noise floor.
        var desiredGain = if (lastInputRms < minimumActiveRms) {
            1f
        } else {
            min(maxGain, targetRms / lastInputRms.coerceAtLeast(1f))
        }

        // Preserve headroom based on each frame's actual input peak.
        if (peak > 0) {
            desiredGain = min(desiredGain, maxOutputPeak / peak.toFloat())
        }
        desiredGain = desiredGain.coerceAtLeast(0f)

        currentGain = if (desiredGain < currentGain) {
            desiredGain
        } else {
            (currentGain + (desiredGain - currentGain) * gainRiseSmoothing)
                .coerceAtMost(maxGain)
        }

        for (i in samples.indices) {
            val amplified = (samples[i].toInt() * currentGain).toInt()
            samples[i] = amplified
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return currentGain
    }

    companion object {
        const val DEFAULT_TARGET_RMS = 5_500f
        const val DEFAULT_MAX_GAIN = 8f
        const val DEFAULT_MAX_OUTPUT_PEAK = 30_000f
        /** Samples below this RMS stay at unity gain; tune against device recordings. */
        const val MINIMUM_ACTIVE_RMS = 160f
        const val GAIN_RISE_SMOOTHING = 0.55f
    }
}
