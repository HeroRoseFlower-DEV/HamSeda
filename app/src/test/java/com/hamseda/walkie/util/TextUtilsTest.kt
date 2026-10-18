package com.hamseda.walkie.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilsTest {

    @Test
    fun `ascii truncates by bytes`() {
        assertEquals("abc", "abcdef".takeUtf8Bytes(3))
        assertEquals("abc", "abc".takeUtf8Bytes(64))
    }

    @Test
    fun `persian truncates by bytes not chars`() {
        // Each Persian letter is 2 UTF-8 bytes: 64 chars = 128 bytes.
        val persian64 = "م".repeat(64)
        val truncated = persian64.takeUtf8Bytes(64)
        assertEquals(64, truncated.toByteArray(Charsets.UTF_8).size)
        assertEquals("م".repeat(32), truncated)
    }

    @Test
    fun `never splits a multibyte character`() {
        // "مa": bytes are [D9 85 61]; cutting at 2 bytes would split م.
        val cut = "مa".takeUtf8Bytes(2)
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= 2)
        // Decodes cleanly — no replacement characters from a split.
        assertTrue(!cut.contains('�'))
    }

    @Test
    fun `emoji surrogate pairs stay intact`() {
        val s = "a😀b"
        val cut = s.takeUtf8Bytes(5) // 'a'(1) + 😀(4) fits exactly
        assertEquals("a😀", cut)
        val cut2 = s.takeUtf8Bytes(4) // 😀 does not fit after 'a'
        assertEquals("a", cut2)
    }

    @Test
    fun `empty and zero limit`() {
        assertEquals("", "".takeUtf8Bytes(64))
        assertEquals("", "abc".takeUtf8Bytes(0))
    }
}
