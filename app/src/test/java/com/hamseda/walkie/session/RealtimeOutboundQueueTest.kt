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
    fun `audio batches stop at controls and preserve FIFO order`() {
        val q = queue(maxPending = 10, maxAudio = 8)
        assertTrue(q.offer("audio-1"))
        assertTrue(q.offer("audio-2"))
        assertTrue(q.offer("audio-3"))
        assertTrue(q.offer("control-ping"))
        assertTrue(q.offer("audio-4"))

        assertEquals("audio-1", q.poll())
        assertEquals(listOf("audio-2", "audio-3"), q.pollConsecutiveAudio(5))
        assertEquals(1, q.pendingAudioFrames())
        assertEquals("control-ping", q.poll())
        assertEquals("audio-4", q.poll())
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

    @Test
    fun `audio freshness threshold permits boundary but rejects older frames`() {
        val budgetNanos = 120_000_000L

        assertTrue(isAudioFrameExpired(-1L, budgetNanos).not())
        assertTrue(isAudioFrameExpired(budgetNanos - 1L, budgetNanos).not())
        assertTrue(isAudioFrameExpired(budgetNanos, budgetNanos).not())
        assertTrue(isAudioFrameExpired(budgetNanos + 1L, budgetNanos))
    }

    @Test
    fun `sender can account for an expired dequeued audio frame`() {
        val q = queue()
        assertTrue(q.offer("audio-expired"))
        assertEquals("audio-expired", q.poll())

        q.recordDroppedAudioFrame()

        assertEquals(1L, q.droppedAudioFrames)
        assertEquals(0, q.pendingAudioFrames())
    }

    @Test
    fun `discard callback clears evicted sensitive payloads`() {
        val discarded = mutableListOf<ByteArray>()
        val queue = RealtimeOutboundQueue(
            maxPending = 3,
            maxPendingAudio = 1,
            isAudio = { item: Pair<Boolean, ByteArray> -> item.first },
            onDiscard = { item: Pair<Boolean, ByteArray> ->
                item.second.fill(0)
                discarded.add(item.second)
            },
        )
        val old = byteArrayOf(1, 2, 3)
        assertTrue(queue.offer(true to old))
        assertTrue(queue.offer(true to byteArrayOf(4, 5, 6)))
        assertEquals(1, discarded.size)
        assertTrue(old.all { it == 0.toByte() })
    }

}
