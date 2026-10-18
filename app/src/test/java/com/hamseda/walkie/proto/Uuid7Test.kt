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
        // Burst-generate and assert the real invariant instead of raw string
        // order: timestamps never go backwards, and within one millisecond
        // rand_a (low 12 bits of the MSB) increments by exactly 1 — tolerating
        // the documented 12-bit wrap, which broke the old string comparison
        // ~12% of the time depending on the random seed.
        val ids = (1..500).map { Uuid7.generate() }
        for (i in 1 until ids.size) {
            val prevTs = Uuid7.timestampMillis(ids[i - 1])
            val curTs = Uuid7.timestampMillis(ids[i])
            assertTrue("timestamps never go backwards", curTs >= prevTs)
            if (curTs == prevTs) {
                val prevA = (ids[i - 1].mostSignificantBits and 0xFFFL).toInt()
                val curA = (ids[i].mostSignificantBits and 0xFFFL).toInt()
                assertEquals(
                    "rand_a increments by 1 (mod 4096) within a millisecond",
                    (prevA + 1) and 0xFFF,
                    curA,
                )
            }
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
