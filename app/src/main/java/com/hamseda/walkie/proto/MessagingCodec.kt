package com.hamseda.walkie.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Binary codec for the offline-messaging control frames. Every payload here
 * travels *inside* the session's sealed control channel (AES-256-GCM with
 * the 29-byte frame header as AAD), so this layer adds no crypto of its own —
 * only compact, bounds-checked framing.
 *
 * Wire layouts (all integers big-endian):
 * ```
 * MSG:       msgId(16) | senderSeq(8) | timestamp(8) | textUtf8
 * CHUNK:     msgId(16) | senderSeq(8) | timestamp(8) | index(4) | total(4) | bytes
 * MSG_ACK:   msgId(16)
 * MSG_READ:  msgId(16)
 * TYPING:    state(1)            // 0 = stopped, 1 = typing
 * MSG_HELLO: lastSeenSeq(8)
 * ```
 *
 * Size rules (anti-DoS, mirrored in [Protocol]):
 * - a single text message is at most [Protocol.MSG_MAX_BYTES] UTF-8 bytes;
 *   longer input is rejected by [encodeText] before it touches the wire
 * - [chunk] splits into at most [Protocol.MSG_MAX_CHUNKS] plaintext pieces
 *   of at most [Protocol.MSG_CHUNK_PLAINTEXT] bytes each
 */
object MessagingCodec {
    // ------------------------------------------------------------------ MSG

    /** Encodes one message for a single MSG frame. @throws MsgCodecException if oversize. */
    fun encodeText(msgId: UUID, senderSeq: Long, timestamp: Long, text: String): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        require(textBytes.size <= Protocol.MSG_CHUNK_PLAINTEXT - Header.SIZE) {
            "single-frame text too large: ${textBytes.size}"
        }
        val buf = ByteBuffer.allocate(Header.SIZE + textBytes.size).order(ByteOrder.BIG_ENDIAN)
        Header.write(buf, msgId, senderSeq, timestamp)
        buf.put(textBytes)
        return buf.array()
    }

    /** Splits a long text into CHUNK payloads. @throws MsgCodecException if over [Protocol.MSG_MAX_BYTES]. */
    fun chunk(msgId: UUID, senderSeq: Long, timestamp: Long, text: String): List<ByteArray> {
        check(ChunkHeader.SIZE == Protocol.MSG_CHUNK_HEADER_BYTES) {
            "chunk header size drifted from Protocol constant"
        }
        val textBytes = text.toByteArray(Charsets.UTF_8)
        require(textBytes.isNotEmpty()) { "cannot chunk empty text" }
        require(textBytes.size <= Protocol.MSG_MAX_BYTES) {
            "text exceeds ${Protocol.MSG_MAX_BYTES} bytes: ${textBytes.size}"
        }
        val perChunk = Protocol.MSG_CHUNK_PLAINTEXT - ChunkHeader.SIZE
        val total = (textBytes.size + perChunk - 1) / perChunk
        require(total <= Protocol.MSG_MAX_CHUNKS) { "chunk count $total exceeds limit" }
        return (0 until total).map { index ->
            val from = index * perChunk
            val to = minOf(from + perChunk, textBytes.size)
            val buf = ByteBuffer.allocate(ChunkHeader.SIZE + (to - from))
                .order(ByteOrder.BIG_ENDIAN)
            Header.write(buf, msgId, senderSeq, timestamp)
            buf.putInt(index)
            buf.putInt(total)
            buf.put(textBytes, from, to - from)
            buf.array()
        }
    }

    /** Decodes an MSG payload. */
    fun decodeText(payload: ByteArray): DecodedText {
        val h = Header.read(payload, minSize = Header.SIZE + 1)
        val text = payload.copyOfRange(Header.SIZE, payload.size).toString(Charsets.UTF_8)
        return DecodedText(h.msgId, h.senderSeq, h.timestamp, text)
    }

    // ----------------------------------------------------------------- CHUNK

    /** Decodes a CHUNK payload. */
    fun decodeChunk(payload: ByteArray): DecodedChunk {
        val h = Header.read(payload, minSize = ChunkHeader.SIZE + 1)
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val index = buf.getInt(Header.SIZE)
        val total = buf.getInt(Header.SIZE + 4)
        if (index < 0 || total <= 0 || total > Protocol.MSG_MAX_CHUNKS || index >= total) {
            throw MsgCodecException("bad chunk index/total: $index/$total")
        }
        val bytes = payload.copyOfRange(ChunkHeader.SIZE, payload.size)
        return DecodedChunk(h.msgId, h.senderSeq, h.timestamp, index, total, bytes)
    }

    // ------------------------------------------------------------- ACK/READ

    fun encodeId(msgId: UUID): ByteArray {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(msgId.mostSignificantBits)
        buf.putLong(msgId.leastSignificantBits)
        return buf.array()
    }

    fun decodeId(payload: ByteArray): UUID {
        if (payload.size != 16) throw MsgCodecException("id payload must be 16 bytes")
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        return UUID(buf.long, buf.long)
    }

    // ---------------------------------------------------------------- TYPING

    fun encodeTyping(typing: Boolean): ByteArray = byteArrayOf(if (typing) 1 else 0)

    fun decodeTyping(payload: ByteArray): Boolean {
        if (payload.size != 1 || (payload[0] != 0.toByte() && payload[0] != 1.toByte())) {
            throw MsgCodecException("bad typing payload")
        }
        return payload[0] == 1.toByte()
    }

    // ----------------------------------------------------------------- HELLO

    fun encodeHello(lastSeenSeq: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(lastSeenSeq).array()

    fun decodeHello(payload: ByteArray): Long {
        if (payload.size != 8) throw MsgCodecException("hello payload must be 8 bytes")
        return ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).long
    }

    // ----------------------------------------------------------------- model

    data class DecodedText(
        val msgId: UUID,
        val senderSeq: Long,
        val timestamp: Long,
        val text: String,
    )

    data class DecodedChunk(
        val msgId: UUID,
        val senderSeq: Long,
        val timestamp: Long,
        val index: Int,
        val total: Int,
        val bytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecodedChunk) return false
            return msgId == other.msgId && senderSeq == other.senderSeq &&
                timestamp == other.timestamp && index == other.index &&
                total == other.total && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var r = msgId.hashCode()
            r = 31 * r + senderSeq.hashCode()
            r = 31 * r + timestamp.hashCode()
            r = 31 * r + index
            r = 31 * r + total
            r = 31 * r + bytes.contentHashCode()
            return r
        }
    }

    private object Header {
        const val SIZE = 32 // msgId(16) + senderSeq(8) + timestamp(8)
        fun write(buf: ByteBuffer, msgId: UUID, senderSeq: Long, timestamp: Long) {
            buf.putLong(msgId.mostSignificantBits)
            buf.putLong(msgId.leastSignificantBits)
            buf.putLong(senderSeq)
            buf.putLong(timestamp)
        }

        fun read(payload: ByteArray, minSize: Int): HeaderValues {
            if (payload.size < minSize) throw MsgCodecException("payload too short: ${payload.size}")
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            return HeaderValues(UUID(buf.long, buf.long), buf.long, buf.long)
        }
    }

    private object ChunkHeader {
        // Must stay equal to Protocol.MSG_CHUNK_HEADER_BYTES; the message
        // size cap is derived from it.
        const val SIZE = Header.SIZE + 8 // + index(4) + total(4)
    }

    private data class HeaderValues(val msgId: UUID, val senderSeq: Long, val timestamp: Long)
}

class MsgCodecException(message: String) : Exception(message)
