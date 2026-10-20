package com.hamseda.walkie.messaging

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HELLO advertises a contiguous watermark anchored at seq 1, never the
 * bare max — a gap must not make the sender drop a message that was
 * never actually received.
 *
 * Round-7 I2 changed the anchor: the run must start at 1, not at the
 * first SURVIVING seq. Per-peer seq spaces always start at 1, so a
 * history starting above 1 has a genuine gap at the bottom — it arises
 * when later seqs overtake an earlier one (a transient send failure
 * parks seq 1 in backoff while seqs 2,3 go out on a later drain). The
 * old anchor advertised [2,3] as watermark 3, and the sender then
 * ack-dropped its still-queued seq 1: silent, permanent loss.
 */
class HelloWatermarkTest {

    @Test
    fun `empty history advertises zero`() {
        assertEquals(0L, MessagingEngine.contiguousWatermark(emptyList()))
    }

    @Test
    fun `contiguous history advertises the max`() {
        assertEquals(5L, MessagingEngine.contiguousWatermark(listOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun `gap stops the watermark at the last contiguous seq`() {
        assertEquals(2L, MessagingEngine.contiguousWatermark(listOf(1, 2, 4, 5)))
    }

    @Test
    fun `overtaken first seq advertises zero until it arrives`() {
        // The I2 scenario verbatim: seq 1 was parked in backoff and
        // seqs 2,3 overtook it. Nothing may be advertised — the prefix
        // starting at 1 is empty.
        assertEquals(0L, MessagingEngine.contiguousWatermark(listOf(2, 3)))
    }

    @Test
    fun `history not starting at one advertises zero`() {
        // Replaces the pre-I2 expectation ([7,8,10] -> 8): a run that
        // does not start at seq 1 is NOT contiguous from the sender's
        // perspective — seqs 1..6 may still be queued there.
        assertEquals(0L, MessagingEngine.contiguousWatermark(listOf(7, 8, 10)))
    }

    @Test
    fun `single first entry`() {
        assertEquals(1L, MessagingEngine.contiguousWatermark(listOf(1)))
    }

    @Test
    fun `single entry above one advertises zero`() {
        assertEquals(0L, MessagingEngine.contiguousWatermark(listOf(3)))
    }
}
