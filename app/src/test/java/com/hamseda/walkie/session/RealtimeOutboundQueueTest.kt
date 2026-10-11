package com.hamseda.walkie.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeOutboundQueueTest {
    private fun queue(maxPending: Int = 8, maxAudio: Int = 3) =
        RealtimeOutboundQueue<String>(
            maxPending = maxPending,
            maxPendingAudio = maxAudio,
            isAudio = { it.startsWith("audio-") },
        )

    @Test
    fun `evicts oldest audio and keeps freshest queued speech`() {
        val q = queue()
        assertTrue(q.offer("audio-1"))
        assertTrue(q.offer("audio-2"))
        assertTrue(q.offer("control-ping"))
        assertTrue(q.offer("audio-3"))
        assertTrue(q.offer("audio-4"))

        assertEquals(1L, q.droppedAudioFrames)
        assertEquals(3, q.pendingAudioFrames())
        assertEquals(4, q.size())
        assertEquals(
            listOf("audio-2", "control-ping", "audio-3", "audio-4"),
            generateSequence { q.poll() }.toList(),
        )
        assertEquals(0, q.pendingAudioFrames())
    }

    @Test
    fun `controls can displace queued audio at total capacity`() {
        val q = queue(maxPending = 3, maxAudio = 2)
        assertTrue(q.offer("audio-1"))
        assertTrue(q.offer("audio-2"))
        assertTrue(q.offer("control-floor-release"))
        assertTrue(q.offer("control-disconnect"))

        assertEquals(1L, q.droppedAudioFrames)
        assertEquals(3, q.size())
        assertEquals(
            listOf("audio-2", "control-floor-release", "control-disconnect"),
            generateSequence { q.poll() }.toList(),
        )
    }

    @Test
    fun `does not discard controls when a control-only queue is full`() {
        val q = queue(maxPending = 3, maxAudio = 2)
        assertTrue(q.offer("control-1"))
        assertTrue(q.offer("control-2"))
        assertTrue(q.offer("control-3"))
        assertFalse(q.offer("audio-1"))

        assertEquals(0, q.pendingAudioFrames())
        assertEquals(3, q.size())
        assertEquals(
            listOf("control-1", "control-2", "control-3"),
            generateSequence { q.poll() }.toList(),
        )
    }
}
