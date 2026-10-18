package com.hamseda.walkie.proto

import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingCodecTest {

    private val id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")

    @Test
    fun `text roundtrip preserves all fields`() {
        val payload = MessagingCodec.encodeText(id, 42L, 1_700_000_000_000L, "سلام دنیا")
        val decoded = MessagingCodec.decodeText(payload)
        assertEquals(id, decoded.msgId)
        assertEquals(42L, decoded.senderSeq)
        assertEquals(1_700_000_000_000L, decoded.timestamp)
        assertEquals("سلام دنیا", decoded.text)
    }

    @Test
    fun `text rejects oversize single frame`() {
        val big = "x".repeat(Protocol.MSG_CHUNK_PLAINTEXT)
        try {
            MessagingCodec.encodeText(id, 1L, 0L, big)
            throw AssertionError("expected rejection")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `chunk splits and reassembles losslessly`() {
        val text = "ab".repeat(3000) // 6000 bytes -> 2 chunks
        val chunks = MessagingCodec.chunk(id, 7L, 99L, text)
        assertEquals(2, chunks.size)
        val decoded = chunks.map { MessagingCodec.decodeChunk(it) }
        assertEquals(0, decoded[0].index)
        assertEquals(1, decoded[1].index)
        assertEquals(2, decoded[0].total)
        val reassembled = decoded.sortedBy { it.index }
            .fold(ByteArray(0)) { acc, c -> acc + c.bytes }
            .toString(Charsets.UTF_8)
        assertEquals(text, reassembled)
        // header fields survive chunking
        assertEquals(id, decoded[0].msgId)
        assertEquals(7L, decoded[0].senderSeq)
    }

    @Test
    fun `chunk rejects empty and over-cap text`() {
        try {
            MessagingCodec.chunk(id, 1L, 0L, "")
            throw AssertionError("expected rejection of empty")
        } catch (e: IllegalArgumentException) {
        }
        try {
            MessagingCodec.chunk(id, 1L, 0L, "x".repeat(Protocol.MSG_MAX_BYTES + 1))
            throw AssertionError("expected rejection of over-cap")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun `chunked frame fits the sealed payload budget`() {
        val text = "y".repeat(Protocol.MSG_MAX_BYTES)
        val chunks = MessagingCodec.chunk(id, 1L, 0L, text)
        assertTrue(chunks.size <= Protocol.MSG_MAX_CHUNKS)
        for (c in chunks) {
            assertTrue(
                "chunk ${c.size} exceeds plaintext budget",
                c.size <= Protocol.MSG_CHUNK_PLAINTEXT,
            )
        }
    }

    @Test
    fun `chunk rejects corrupt index total`() {
        val chunks = MessagingCodec.chunk(id, 1L, 0L, "hello world, this is long enough to chunk ".repeat(200))
        val bad = chunks[0].copyOf()
        // corrupt total to 0
        bad[36] = 0; bad[37] = 0; bad[38] = 0; bad[39] = 0
        try {
            MessagingCodec.decodeChunk(bad)
            throw AssertionError("expected MsgCodecException")
        } catch (e: MsgCodecException) {
        }
    }

    @Test
    fun `id roundtrip`() {
        assertEquals(id, MessagingCodec.decodeId(MessagingCodec.encodeId(id)))
    }

    @Test
    fun `typing roundtrip`() {
        assertTrue(MessagingCodec.decodeTyping(MessagingCodec.encodeTyping(true)))
        assertFalse(MessagingCodec.decodeTyping(MessagingCodec.encodeTyping(false)))
        try {
            MessagingCodec.decodeTyping(byteArrayOf(2))
            throw AssertionError("expected MsgCodecException")
        } catch (e: MsgCodecException) {
        }
    }

    @Test
    fun `hello roundtrip`() {
        assertEquals(123456789L, MessagingCodec.decodeHello(MessagingCodec.encodeHello(123456789L)))
    }

    @Test
    fun `decode rejects truncated payloads`() {
        try {
            MessagingCodec.decodeText(ByteArray(10))
            throw AssertionError("expected MsgCodecException")
        } catch (e: MsgCodecException) {
        }
        try {
            MessagingCodec.decodeId(ByteArray(15))
            throw AssertionError("expected MsgCodecException")
        } catch (e: MsgCodecException) {
        }
    }

    @Test
    fun `encoded chunk header is big-endian`() {
        val chunks = MessagingCodec.chunk(id, 1L, 0L, "z".repeat(5000))
        val first = chunks[0]
        // index at offset 32, total at 36
        val index = ((first[32].toInt() and 0xFF) shl 24) or
            ((first[33].toInt() and 0xFF) shl 16) or
            ((first[34].toInt() and 0xFF) shl 8) or
            (first[35].toInt() and 0xFF)
        assertEquals(0, index)
        assertArrayEquals(
            "msgId must be first 16 bytes",
            MessagingCodec.encodeId(id),
            first.copyOfRange(0, 16),
        )
    }
}
