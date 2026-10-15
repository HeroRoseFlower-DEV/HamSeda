package com.hamseda.walkie.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionSequenceCounterTest {
    @Test
    fun `allocates monotonically increasing uint32 values`() {
        val counter = SessionSequenceCounter(safetyLimit = 4)

        assertEquals(0L, counter.next())
        assertEquals(1L, counter.next())
        assertEquals(2L, counter.next())
        assertEquals(3L, counter.next())
        assertThrows(SessionSequenceCounter.SequenceExhaustedException::class.java) {
            counter.next()
        }
    }

    @Test
    fun `never wraps and stays exhausted until reset`() {
        val counter = SessionSequenceCounter(safetyLimit = 2)

        assertEquals(0L, counter.next())
        assertEquals(1L, counter.next())
        assertThrows(SessionSequenceCounter.SequenceExhaustedException::class.java) {
            counter.next()
        }
        assertThrows(SessionSequenceCounter.SequenceExhaustedException::class.java) {
            counter.next()
        }
    }

    @Test
    fun `fresh session reset starts sequence at zero`() {
        val counter = SessionSequenceCounter(safetyLimit = 2)
        assertEquals(0L, counter.next())
        assertEquals(1L, counter.next())

        counter.reset()

        assertEquals(0L, counter.next())
        assertEquals(1L, counter.next())
    }

    @Test
    fun `rejects invalid safety limits`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionSequenceCounter(safetyLimit = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionSequenceCounter(safetyLimit = 0x1_0000_0000L)
        }
    }
}
