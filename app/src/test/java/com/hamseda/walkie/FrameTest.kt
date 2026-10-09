package com.hamseda.walkie

import com.hamseda.walkie.proto.CodecId
import com.hamseda.walkie.proto.Frame
import com.hamseda.walkie.proto.FrameException
import com.hamseda.walkie.proto.MessageType
import com.hamseda.walkie.proto.Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTest {

    private fun sampleFrame() = Frame(
        type = MessageType.AUDIO,
        sessionId = ByteArray(8) { it.toByte() },
        seq = 42,
        timestamp = System.currentTimeMillis(),
        codecId = CodecId.OPUS,
        payload = ByteArray(60) { (it * 3).toByte() },
    )

    @Test
    fun `encode decode roundtrip preserves every field`() {
        val decoded = Frame.decode(sampleFrame().encode())
        assertEquals(sampleFrame(), decoded)
    }

    @Test
    fun `header is exactly 29 bytes`() {
        assertEquals(29, sampleFrame().headerBytes().size)
        assertEquals(Protocol.HEADER_SIZE, 29)
    }

    @Test
    fun `empty payload is legal`() {
        val f = sampleFrame().copy(payload = ByteArray(0))
        assertEquals(f, Frame.decode(f.encode()))
    }

    @Test
    fun `max payload is accepted`() {
        val f = sampleFrame().copy(payload = ByteArray(Protocol.MAX_PAYLOAD))
        assertEquals(Protocol.MAX_PAYLOAD, Frame.decode(f.encode()).payload.size)
    }

    @Test
    fun `payload over max is rejected at encode time`() {
        val f = sampleFrame().copy(payload = ByteArray(Protocol.MAX_PAYLOAD + 1))
        assertThrows(IllegalArgumentException::class.java) { f.encode() }
    }

    @Test
    fun `bad magic is rejected`() {
        val bytes = sampleFrame().encode()
        bytes[0] = 0x00
        assertThrows(FrameException::class.java) { Frame.decode(bytes) }
    }

    @Test
    fun `unknown version is rejected`() {
        val bytes = sampleFrame().encode()
        bytes[2] = 0x7F
        val e = assertThrows(FrameException::class.java) { Frame.decode(bytes) }
        assertTrue(e.message!!.contains("version"))
    }

    @Test
    fun `unknown message type is rejected`() {
        val bytes = sampleFrame().encode()
        bytes[3] = 0x7F.toByte()
        assertThrows(FrameException::class.java) { Frame.decode(bytes) }
    }

    @Test
    fun `truncated frame is rejected`() {
        val bytes = sampleFrame().encode()
        assertThrows(FrameException::class.java) {
            Frame.decode(bytes.copyOf(bytes.size - 5))
        }
    }

    @Test
    fun `declared payload longer than actual bytes is rejected`() {
        val bytes = sampleFrame().encode()
        // Corrupt the payload length field (bytes 25..28) to claim more.
        bytes[25] = 0x00; bytes[26] = 0x01; bytes[27] = 0x00; bytes[28] = 0x00
        assertThrows(FrameException::class.java) { Frame.decode(bytes) }
    }

    @Test
    fun `oversized envelope is rejected`() {
        val bytes = ByteArray(Protocol.MAX_FRAME + 1)
        assertThrows(FrameException::class.java) { Frame.decode(bytes) }
    }

    @Test
    fun `stale timestamp is rejected`() {
        val f = sampleFrame().copy(timestamp = System.currentTimeMillis() - Protocol.MAX_CLOCK_SKEW_MS - 1000)
        assertThrows(FrameException::class.java) { Frame.decode(f.encode()) }
    }

    @Test
    fun `future timestamp beyond skew is rejected`() {
        val f = sampleFrame().copy(timestamp = System.currentTimeMillis() + Protocol.MAX_CLOCK_SKEW_MS + 1000)
        assertThrows(FrameException::class.java) { Frame.decode(f.encode()) }
    }

    @Test
    fun `session id must be 8 bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            sampleFrame().copy(sessionId = ByteArray(7))
        }
    }

    @Test
    fun `all message types are known`() {
        listOf(
            MessageType.HELLO, MessageType.KEY_EXCHANGE, MessageType.KEY_CONFIRM,
            MessageType.SAS_CONFIRM, MessageType.AUDIO, MessageType.FLOOR_REQUEST,
            MessageType.FLOOR_GRANT, MessageType.FLOOR_RELEASE, MessageType.FLOOR_DENY,
            MessageType.PING, MessageType.PONG, MessageType.DISCONNECT, MessageType.ERROR,
        ).forEach { assertTrue(MessageType.isKnown(it)) }
    }

    @Test
    fun `plaintext types are only hello and key exchange`() {
        assertTrue(MessageType.isPlaintext(MessageType.HELLO))
        assertTrue(MessageType.isPlaintext(MessageType.KEY_EXCHANGE))
        listOf(MessageType.AUDIO, MessageType.FLOOR_REQUEST, MessageType.SAS_CONFIRM).forEach {
            assertTrue(!MessageType.isPlaintext(it))
        }
    }
}
