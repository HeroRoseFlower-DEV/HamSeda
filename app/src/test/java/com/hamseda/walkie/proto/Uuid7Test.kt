package com.hamseda.walkie.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Uuid7Test {

    @Test
    fun `version and variant bits are correct`() {
        repeat(100) {
            val id = Uuid7.generate()
            val version = ((id.mostSignificantBits ushr 12) and 0xFL).toInt()
            val variant = ((id.leastSignificantBits ushr 62) and 0x3L).toInt()
            assertEquals("version must be 7", 7, version)
            assertEquals("variant must be 2 (10xx)", 2, variant)
        }
    }

    @Test
    fun `ids are unique`() {
        val ids = (1..1000).map { Uuid7.generate().toString() }.toSet()
        assertEquals(1000, ids.size)
    }

    @Test
    fun `ids sort in generation order`() {
        val first = Uuid7.generate()
        Thread.sleep(2)
        val second = Uuid7.generate()
        assertTrue(
            "uuid7 must be time-ordered",
            first.toString() < second.toString(),
        )
    }

    @Test
    fun `back-to-back ids in the same millisecond are strictly increasing`() {
        // Burst-generate; the monotonic rand_a guard keeps them ordered even
        // when the millisecond does not advance.
        val ids = (1..500).map { Uuid7.generate().toString() }
        for (i in 1 until ids.size) {
            assertTrue("ids[$i-1] < ids[$i]", ids[i - 1] < ids[i])
        }
    }

    @Test
    fun `embedded timestamp is approximately now`() {
        val before = System.currentTimeMillis()
        val id = Uuid7.generate()
        val after = System.currentTimeMillis()
        val ts = Uuid7.timestampMillis(id)
        assertTrue(ts in before..after)
    }
}
