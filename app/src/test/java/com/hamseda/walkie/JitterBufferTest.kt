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
}
