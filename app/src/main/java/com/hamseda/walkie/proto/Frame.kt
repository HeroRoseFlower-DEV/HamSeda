package com.hamseda.walkie.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A single authenticated protocol frame.
 *
 * Layout (big-endian):
 * ```
 *  0..1   magic            0x48 0x53
 *  2      version          0x01
 *  3      type             MessageType.*
 *  4..11  sessionId        8 bytes
 *  12..15 seq              uint32, per-direction sequence number
 *  16..23 timestamp        int64, epoch millis at send time
 *  24     codecId          CodecId.* (meaningful for AUDIO frames)
 *  25..28 payloadLen       uint32, 0..MAX_PAYLOAD
 *  29..   payload          payloadLen bytes
 * ```
 *
 * For sealed (encrypted) message types the payload is
 * `nonce(12) || AES-256-GCM ciphertext`, and the 29-byte header is passed
 * as associated authenticated data, so a tampered header fails authentication.
 */
data class Frame(
    val type: Byte,
    val sessionId: ByteArray,
    val seq: Long,
    val timestamp: Long,
    val codecId: Byte,
    val payload: ByteArray,
) {
    init {
        require(sessionId.size == 8) { "sessionId must be 8 bytes" }
        require(seq in 0..0xFFFFFFFFL) { "seq out of uint32 range" }
    }

    /** Serializes this frame to its exact wire representation. */
    fun encode(): ByteArray {
        require(payload.size <= Protocol.MAX_PAYLOAD) {
            "payload ${payload.size} exceeds MAX_PAYLOAD ${Protocol.MAX_PAYLOAD}"
        }
        val buf = ByteBuffer.allocate(Protocol.HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
        buf.put(Protocol.MAGIC_0)
        buf.put(Protocol.MAGIC_1)
        buf.put(Protocol.VERSION)
        buf.put(type)
        buf.put(sessionId)
        buf.putInt(seq.toInt())
        buf.putLong(timestamp)
        buf.put(codecId)
        buf.putInt(payload.size)
        buf.put(payload)
        return buf.array()
    }

    /** The 29-byte header, used as AES-GCM associated data. */
    fun headerBytes(): ByteArray = encode().copyOf(Protocol.HEADER_SIZE)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return type == other.type &&
            sessionId.contentEquals(other.sessionId) &&
            seq == other.seq &&
            timestamp == other.timestamp &&
            codecId == other.codecId &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.toInt()
        result = 31 * result + sessionId.contentHashCode()
        result = 31 * result + seq.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + codecId.toInt()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        /** Parses and validates one frame from its wire bytes.
         * @throws FrameException if the bytes are malformed or violate limits. */
        fun decode(bytes: ByteArray, nowMs: Long = System.currentTimeMillis()): Frame {
            if (bytes.size < Protocol.HEADER_SIZE) {
                throw FrameException("frame too short: ${bytes.size} < ${Protocol.HEADER_SIZE}")
            }
            if (bytes.size > Protocol.MAX_FRAME) {
                throw FrameException("frame too large: ${bytes.size} > ${Protocol.MAX_FRAME}")
            }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val m0 = buf.get()
            val m1 = buf.get()
            if (m0 != Protocol.MAGIC_0 || m1 != Protocol.MAGIC_1) {
                throw FrameException("bad magic bytes")
            }
            val version = buf.get()
            if (version != Protocol.VERSION) {
                throw FrameException("unsupported protocol version: $version")
            }
            val type = buf.get()
            if (!MessageType.isKnown(type)) {
                throw FrameException("unknown message type: 0x%02x".format(type))
            }
            val sessionId = ByteArray(8).also { buf.get(it) }
            val seq = buf.int.toLong() and 0xFFFFFFFFL
            val timestamp = buf.long
            val codecId = buf.get()
            val payloadLen = buf.int
            if (payloadLen < 0 || payloadLen > Protocol.MAX_PAYLOAD) {
                throw FrameException("payload length out of bounds: $payloadLen")
            }
            if (bytes.size != Protocol.HEADER_SIZE + payloadLen) {
                throw FrameException(
                    "frame size ${bytes.size} != header + declared payload " +
                        "${Protocol.HEADER_SIZE + payloadLen}"
                )
            }
            val skew = kotlin.math.abs(nowMs - timestamp)
            if (skew > Protocol.MAX_CLOCK_SKEW_MS) {
                throw FrameException("timestamp outside skew window: skew=${skew}ms")
            }
            val payload = ByteArray(payloadLen).also { buf.get(it) }
            return Frame(type, sessionId, seq, timestamp, codecId, payload)
        }
    }
}

class FrameException(message: String) : Exception(message)
