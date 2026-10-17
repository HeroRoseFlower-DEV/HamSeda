package com.hamseda.walkie.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingFrameGuardTest {
    @Test
    fun acceptsFrameOnlyForCurrentConnectedSocket() {
        val socket = Any()

        assertTrue(
            isCurrentIncomingFrame(
                expectedGeneration = 7L,
                currentGeneration = 7L,
                expectedSocket = socket,
                currentSocket = socket,
                state = TransportState.CONNECTED,
            ),
        )
    }

    @Test
    fun acceptsHandshakeFramesWhileAuthenticating() {
        val socket = Any()

        assertTrue(
            isCurrentIncomingFrame(
                expectedGeneration = 2L,
                currentGeneration = 2L,
                expectedSocket = socket,
                currentSocket = socket,
                state = TransportState.AUTHENTICATING,
            ),
        )
    }

    @Test
    fun rejectsFrameFromEarlierGeneration() {
        val socket = Any()

        assertFalse(
            isCurrentIncomingFrame(
                expectedGeneration = 4L,
                currentGeneration = 5L,
                expectedSocket = socket,
                currentSocket = socket,
                state = TransportState.CONNECTED,
            ),
        )
    }

    @Test
    fun rejectsFrameWhenSocketWasReplacedEvenIfGenerationMatches() {
        assertFalse(
            isCurrentIncomingFrame(
                expectedGeneration = 9L,
                currentGeneration = 9L,
                expectedSocket = Any(),
                currentSocket = Any(),
                state = TransportState.CONNECTED,
            ),
        )
    }

    @Test
    fun rejectsFrameAfterTransportLeavesActiveStates() {
        val socket = Any()

        for (state in listOf(TransportState.IDLE, TransportState.FAILED, TransportState.UNAVAILABLE)) {
            assertFalse(
                "$state must not publish frames",
                isCurrentIncomingFrame(
                    expectedGeneration = 1L,
                    currentGeneration = 1L,
                    expectedSocket = socket,
                    currentSocket = socket,
                    state = state,
                ),
            )
        }
    }
}
