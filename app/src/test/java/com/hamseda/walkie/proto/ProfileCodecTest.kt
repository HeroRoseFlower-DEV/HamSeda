package com.hamseda.walkie.proto

import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCodecTest {

    private val zeroHash = ByteArray(Protocol.PROFILE_HASH_BYTES)
    private val fakeHash = ByteArray(Protocol.PROFILE_HASH_BYTES) { it.toByte() }

    @Test
    fun `profile roundtrip with name and photo hash`() {
        val payload = MessagingCodec.encodeProfile("مهدیار", fakeHash)
        val decoded = MessagingCodec.decodeProfile(payload)
        assertEquals("مهدیار", decoded.name)
        assertArrayEquals(fakeHash, decoded.photoHash)
        assertTrue(decoded.hasPhoto)
    }

    @Test
    fun `profile roundtrip with empty name and zero hash`() {
        val payload = MessagingCodec.encodeProfile("", zeroHash)
        val decoded = MessagingCodec.decodeProfile(payload)
        assertEquals("", decoded.name)
        assertArrayEquals(zeroHash, decoded.photoHash)
        assertFalse(decoded.hasPhoto)
    }

    @Test
    fun `profile rejects overlong name`() {
        val tooLong = "x".repeat(Protocol.PROFILE_MAX_NAME_BYTES + 1)
        try {
            MessagingCodec.encodeProfile(tooLong, zeroHash)
            throw AssertionError("expected rejection")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `profile rejects bad hash length`() {
        try {
            MessagingCodec.encodeProfile("a", ByteArray(16))
            throw AssertionError("expected rejection")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `profile decode rejects truncated payload`() {
        try {
            MessagingCodec.decodeProfile(ByteArray(10))
            throw AssertionError("expected rejection")
        } catch (e: MsgCodecException) {
            // expected
        }
    }

    @Test
    fun `profile decode rejects length mismatch`() {
        val payload = MessagingCodec.encodeProfile("ab", zeroHash)
        payload[0] = 5 // lie about the name length
        try {
            MessagingCodec.decodeProfile(payload)
            throw AssertionError("expected rejection")
        } catch (e: MsgCodecException) {
            // expected
        }
    }

    @Test
    fun `photo chunks split and reassemble losslessly`() {
        val photoId = UUID.randomUUID()
        val bytes = ByteArray(9000) { it.toByte() }
        val chunks = MessagingCodec.chunkPhoto(photoId, bytes)
        assertEquals(3, chunks.size) // 9000 / 3976 -> 3
        val decoded = chunks.map { MessagingCodec.decodePhotoChunk(it) }
        assertEquals(photoId, decoded[0].photoId)
        assertEquals(3, decoded[0].total)
        val reassembled = decoded.sortedBy { it.index }
            .fold(ByteArray(0)) { acc, c -> acc + c.bytes }
        assertArrayEquals(bytes, reassembled)
    }

    @Test
    fun `photo chunk rejects empty and over-cap input`() {
        try {
            MessagingCodec.chunkPhoto(UUID.randomUUID(), ByteArray(0))
            throw AssertionError("expected rejection of empty")
        } catch (e: IllegalArgumentException) {
        }
        try {
            MessagingCodec.chunkPhoto(
                UUID.randomUUID(),
                ByteArray(Protocol.PROFILE_PHOTO_MAX_BYTES + 1),
            )
            throw AssertionError("expected rejection of over-cap")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun `photo chunk decode rejects bad index`() {
        val chunks = MessagingCodec.chunkPhoto(UUID.randomUUID(), ByteArray(100) { 1 })
        val bad = chunks[0].copyOf()
        // Corrupt the index field (bytes 16..20) to total (1).
        bad[16] = 0; bad[17] = 0; bad[18] = 0; bad[19] = 1
        try {
            MessagingCodec.decodePhotoChunk(bad)
            throw AssertionError("expected rejection")
        } catch (e: MsgCodecException) {
            // expected
        }
    }

    @Test
    fun `profile frame types do not collide with messaging types`() {
        val used = setOf(
            MessageType.MSG, MessageType.MSG_ACK, MessageType.MSG_READ,
            MessageType.TYPING, MessageType.CHUNK, MessageType.MSG_HELLO,
        )
        assertFalse(used.contains(MessageType.PROFILE))
        assertFalse(used.contains(MessageType.PROFILE_PHOTO))
        assertTrue(MessageType.isKnown(MessageType.PROFILE))
        assertTrue(MessageType.isKnown(MessageType.PROFILE_PHOTO))
    }
}
