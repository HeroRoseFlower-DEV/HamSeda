package com.hamseda.walkie.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueTrackerTest {
    @Test
    fun roundsQueueCapUpToWholeFramesWhenNeededToReachStartThreshold() {
        // API 31+ can start after two 20 ms frames while retaining the 60 ms queue cap.
        assertEquals(2_880, queueLimitFramesForStartThreshold(2_880, 960, 1_920))
        assertEquals(2_880, queueLimitFramesForStartThreshold(2_880, 960, 2_880))

        // Legacy buffer thresholds may not align with a 20 ms frame boundary.
        assertEquals(3_840, queueLimitFramesForStartThreshold(2_880, 960, 3_072))
        assertEquals(4_800, queueLimitFramesForStartThreshold(2_880, 960, 3_848))
    }

    @Test
    fun tracksPendingFramesAndSubtractsConsumedFrames() {
        val tracker = PlaybackQueueTracker()
        tracker.reset(1_000)
        tracker.recordWrittenFrames(2_880)

        assertEquals(2_880L, tracker.pendingFrames(1_000))
        assertEquals(1_920L, tracker.pendingFrames(1_960))

        tracker.recordWrittenFrames(960)
        assertEquals(1_920L, tracker.pendingFrames(2_920))
    }

    @Test
    fun clampsPendingFramesToZeroAfterUnderrunAdvancement() {
        val tracker = PlaybackQueueTracker()
        tracker.reset(100)
        tracker.recordWrittenFrames(100)

        assertEquals(0L, tracker.pendingFrames(1_000))
        tracker.recordWrittenFrames(40)
        assertEquals(40L, tracker.pendingFrames(1_000))
    }

    @Test
    fun handlesUnsignedPlaybackHeadWraparound() {
        val tracker = PlaybackQueueTracker()
        tracker.reset(-10) // unsigned 0xFFFFFFF6
        tracker.recordWrittenFrames(100)

        assertEquals(100L, tracker.pendingFrames(-10))
        assertEquals(85L, tracker.pendingFrames(5)) // 15 frames consumed across wrap
    }

    @Test
    fun discardsPendingEstimateAfterImplausibleHeadJump() {
        val tracker = PlaybackQueueTracker()
        tracker.reset(0)
        tracker.recordWrittenFrames(1_200)

        assertEquals(0L, tracker.pendingFrames(480_001))
    }
}
