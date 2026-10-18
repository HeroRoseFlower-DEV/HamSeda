package com.hamseda.walkie.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OutboxBackoffTest {

    @Test
    fun `base backoff doubles per attempt and caps at 60s`() {
        assertEquals(1_000L, MessagingEngine.baseBackoffMs(1))
        assertEquals(2_000L, MessagingEngine.baseBackoffMs(2))
        assertEquals(4_000L, MessagingEngine.baseBackoffMs(3))
        assertEquals(32_000L, MessagingEngine.baseBackoffMs(6))
        assertEquals(60_000L, MessagingEngine.baseBackoffMs(7))
        assertEquals(60_000L, MessagingEngine.baseBackoffMs(12))
        assertEquals(60_000L, MessagingEngine.baseBackoffMs(100))
    }

    @Test
    fun `jitter stays within one second above base`() {
        val random = Random(42)
        repeat(50) { attempt ->
            val a = (attempt % 12) + 1
            val d = OutboxBackoff.delayMs(a, random)
            val base = MessagingEngine.baseBackoffMs(a)
            assertTrue("delay $d within [base, base+1000)", d in base until base + 1000)
        }
    }

    @Test
    fun `backoff requires attempts of at least 1`() {
        try {
            MessagingEngine.baseBackoffMs(0)
            throw AssertionError("expected rejection")
        } catch (e: IllegalArgumentException) {
        }
    }
}
