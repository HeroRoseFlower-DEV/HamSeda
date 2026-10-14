package com.hamseda.walkie.audio

/**
 * Estimates PCM frames still queued in a streaming AudioTrack.
 *
 * AudioTrack.playbackHeadPosition is a signed 32-bit API value. Treat its bits
 * as an unsigned counter so counter wrap is handled correctly. A head jump
 * larger than ten seconds of 48 kHz audio is treated as a track discontinuity;
 * pending content is discarded rather than accumulating stale playback delay.
 */
internal class PlaybackQueueTracker {
    private var pending = 0L
    private var lastHeadPosition: Long? = null

    @Synchronized
    fun reset(playbackHeadPosition: Int = 0) {
        pending = 0L
        lastHeadPosition = unsigned(playbackHeadPosition)
    }

    @Synchronized
    fun recordWrittenFrames(frames: Int) {
        require(frames >= 0) { "frames must not be negative" }
        pending += frames.toLong()
    }

    /**
     * Updates the estimate and returns it. Using head deltas, rather than
     * total-written minus absolute-head, prevents an underrun from poisoning
     * the estimate for all later frames.
     */
    @Synchronized
    fun pendingFrames(playbackHeadPosition: Int): Long {
        val current = unsigned(playbackHeadPosition)
        val previous = lastHeadPosition
        if (previous != null) {
            val consumed = (current - previous) and COUNTER_MASK
            if (consumed > MAX_REASONABLE_HEAD_ADVANCE_FRAMES) {
                pending = 0L
            } else {
                pending = (pending - consumed).coerceAtLeast(0L)
            }
        }
        lastHeadPosition = current
        return pending
    }

    private fun unsigned(position: Int): Long = position.toLong() and COUNTER_MASK

    companion object {
        private const val COUNTER_MASK = 0xFFFF_FFFFL
        private const val MAX_REASONABLE_HEAD_ADVANCE_FRAMES = 48_000L * 10L
    }
}


/**
 * The writer sends complete PCM frames. Its queue ceiling must therefore be
 * rounded up to a frame boundary at or above AudioTrack's streaming start
 * threshold; otherwise playback can wait for head movement before the platform
 * has enough samples buffered to start.
 */
internal fun queueLimitFramesForStartThreshold(
    desiredQueueFrames: Int,
    frameSamples: Int,
    startThresholdFrames: Int,
): Int {
    require(desiredQueueFrames > 0) { "desiredQueueFrames must be positive" }
    require(frameSamples > 0) { "frameSamples must be positive" }
    require(startThresholdFrames > 0) { "startThresholdFrames must be positive" }
    val roundedThreshold = (
        (startThresholdFrames.toLong() + frameSamples - 1L) / frameSamples
        ) * frameSamples
    require(roundedThreshold <= Int.MAX_VALUE) { "rounded threshold exceeds Int range" }
    return maxOf(desiredQueueFrames, roundedThreshold.toInt())
}
