package com.hamseda.walkie.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueTrackerTest {
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
