package com.hamseda.walkie

import com.hamseda.walkie.audio.JitterBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {

    private class Clock(var now: Long = 0) {
        fun get(): Long = now
    }

    private fun payload(s: Long) = byteArrayOf(s.toByte())

    @Test
    fun `primes before first playout then plays in order`() {
        val clock = Clock()
        val jb = JitterBuffer(targetFrames = 3, nowMs = clock::get)
        jb.push(0, payload(0))
        jb.push(1, payload(1))
        assertTrue(jb.takeNext() is JitterBuffer.TakeResult.NotReady)
        jb.push(2, payload(2))
        val r = jb.takeNext()
        assertTrue(r is JitterBuffer.TakeResult.Frame)
        assertEquals(0L, (r as JitterBuffer.TakeResult.Frame).packet.seq)
        assertEquals(1L, (jb.takeNext() as JitterBuffer.TakeResult.Frame).packet.seq)
    }

    @Test
    fun `gap becomes Lost after max wait`() {
        val clock = Clock()
        val jb = JitterBuffer(targetFrames = 2, maxWaitMs = 100, nowMs = clock::get)
        jb.push(0, payload(0))
        jb.push(2, payload(2)) // seq 1 missing
        assertEquals(0L, (jb.takeNext() as JitterBuffer.TakeResult.Frame).packet.seq)
        assertTrue(jb.takeNext() is JitterBuffer.TakeResult.NotReady) // waiting
        clock.now += 150
        val lost = jb.takeNext()
        assertTrue(lost is JitterBuffer.TakeResult.Lost)
        assertEquals(1L, (lost as JitterBuffer.TakeResult.Lost).skippedSeq)
        // Stream continues with seq 2 afterwards.
        assertEquals(2L, (jb.takeNext() as JitterBuffer.TakeResult.Frame).packet.seq)
        assertEquals(1L, jb.lost)
    }

    @Test
    fun `late fill of the gap is played if it arrives in time`() {
        val clock = Clock()
        val jb = JitterBuffer(targetFrames = 2, maxWaitMs = 100, nowMs = clock::get)
        jb.push(0, payload(0))
        jb.push(2, payload(2))
        jb.takeNext() // plays 0
        jb.push(1, payload(1)) // gap filled before timeout
        assertEquals(1L, (jb.takeNext() as JitterBuffer.TakeResult.Frame).packet.seq)
        assertEquals(2L, (jb.takeNext() as JitterBuffer.TakeResult.Frame).packet.seq)
    }

    @Test
    fun `stale frames are dropped`() {
        val clock = Clock()
        val jb = JitterBuffer(targetFrames = 1, maxLateMs = 200, nowMs = clock::get)
        jb.push(0, payload(0))
        clock.now += 500 // head goes stale
        jb.push(1, payload(1))
        val r = jb.takeNext()
        // Frame 0 expired; playback starts at 1.
        assertTrue(r is JitterBuffer.TakeResult.Frame)
        assertEquals(1L, (r as JitterBuffer.TakeResult.Frame).packet.seq)
        assertEquals(1L, jb.droppedStale)
    }

    @Test
    fun `queue is capped - no runaway buffering`() {
        val clock = Clock()
        val jb = JitterBuffer(targetFrames = 1000, maxFrames = 10, nowMs = clock::get)
        repeat(50) { jb.push(it.toLong(), payload(it.toLong())) }
        assertTrue(jb.bufferedFrames() <= 10)
    }

    @Test
    fun `stats track receive play drop`() {
        val clock = Clock()
        val jb = JitterBuffer(targetFrames = 1, nowMs = clock::get)
        jb.push(0, payload(0))
        jb.takeNext()
        assertEquals(1L, jb.received)
        assertEquals(1L, jb.played)
    }

    @Test
    fun `hs10 stale frame at full buffer does not evict useful frame`() {
        val clock = Clock()
        val jb = JitterBuffer(targetFrames = 2, maxFrames = 4, nowMs = clock::get)
        // Prime: seqs 0,1 → take 0 → nextSeq=1.
        jb.push(0, payload(0))
        jb.push(1, payload(1))
        val first = jb.takeNext()
        assertTrue(first is JitterBuffer.TakeResult.Frame)
        // Fill to maxFrames=4: queue holds 1,2,3,4.
        jb.push(2, payload(2))
        jb.push(3, payload(3))
        jb.push(4, payload(4))
        assertEquals(4, jb.bufferedFrames())
        val droppedBefore = jb.droppedStale
        // Stale frame (seq 0 < nextSeq=1) at a FULL buffer.
        // Old code evicted the useful seq-1 frame first, then dropped stale.
        // New code drops stale without evicting.
        jb.push(0, payload(0))
        assertEquals(droppedBefore + 1, jb.droppedStale)
        assertEquals(4, jb.bufferedFrames())
        // Seq 1 survived (was not evicted).
        val next = jb.takeNext()
        assertTrue(next is JitterBuffer.TakeResult.Frame)
        assertEquals(1L, (next as JitterBuffer.TakeResult.Frame).packet.seq)
    }

    @Test
    fun `hs10 duplicate at full buffer does not evict`() {
        val clock = Clock()
        val jb = JitterBuffer(targetFrames = 2, maxFrames = 4, nowMs = clock::get)
        jb.push(0, payload(0))
        jb.push(1, payload(1))
        jb.takeNext()
        jb.push(2, payload(2))
        jb.push(3, payload(3))
        jb.push(4, payload(4))
        assertEquals(4, jb.bufferedFrames())
        val droppedBefore = jb.droppedStale
        // Duplicate of queued seq 2.
        jb.push(2, payload(2))
        assertEquals(droppedBefore + 1, jb.droppedStale)
        assertEquals(4, jb.bufferedFrames())
    }
    @Test
    fun `hs12 queue overflow advances past an evicted expected frame`() {
        val clock = Clock()
        val jb = JitterBuffer(targetFrames = 2, maxFrames = 3, maxWaitMs = 100, nowMs = clock::get)
        jb.push(0, payload(0))
        jb.push(1, payload(1))
        jb.push(2, payload(2))
        assertEquals(0L, (jb.takeNext() as JitterBuffer.TakeResult.Frame).packet.seq)
        // Queue is [1,2,3], nextSeq=1. Pushing 4 evicts expected seq 1.
        jb.push(3, payload(3))
        jb.push(4, payload(4))
        val next = jb.takeNext()
        assertTrue("playhead should skip the evicted frame without a timeout", next is JitterBuffer.TakeResult.Frame)
        assertEquals(2L, (next as JitterBuffer.TakeResult.Frame).packet.seq)
    }

}
