package com.hamseda.walkie.messaging

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-6 L8: HELLO advertises a contiguous watermark, never the bare
 * max seq — a gap below the max must not make the sender drop a message
 * that was never actually received.
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
    fun `history not starting at one still watermarks from its first seq`() {
        // A peer whose first-ever received seq is 7 (earlier history was
        // pruned/never synced) must not advertise 6: the contiguous run
        // [7,8] is fully held, so 8 is a sound watermark.
        assertEquals(8L, MessagingEngine.contiguousWatermark(listOf(7, 8, 10)))
    }

    @Test
    fun `single entry`() {
        assertEquals(3L, MessagingEngine.contiguousWatermark(listOf(3)))
    }
}
