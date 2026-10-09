package com.hamseda.walkie.proto

import java.util.UUID

/**
 * Wire protocol constants for HamSeda v1.
 *
 * Every datagram on the wire is one [Frame]: a fixed 29-byte header followed
 * by a length-bounded payload. Frames travel inside a length-prefixed
 * envelope handled by [FramedSocket] (4-byte big-endian length + frame).
 *
 * Security properties enforced here (see docs/PROTOCOL.md and SECURITY.md):
 * - explicit protocol version; peers must reject unknown versions
 * - maximum payload size; oversized frames are rejected, never truncated
 * - per-frame sequence numbers + timestamps; replay/out-of-session frames
 *   are dropped by [com.hamseda.walkie.crypto.ReplayProtection]
 * - audio/control frames are AES-256-GCM sealed; the header (except the
 *   mutable length prefix) is authenticated as associated data
 */
object Protocol {
    const val MAGIC_0: Byte = 0x48 // 'H'
    const val MAGIC_1: Byte = 0x53 // 'S'
    const val VERSION: Byte = 1

    /** Fixed header size in bytes: magic(2) + version(1) + type(1) + sessionId(8)
     * + seq(4) + timestamp(8) + codecId(1) + payloadLen(4). */
    const val HEADER_SIZE = 29

    /** Maximum payload bytes per frame. A 20 ms Opus frame at ~24 kbps is
     * ~60 bytes; PCM 20 ms @16 kHz mono is 640 bytes. 4096 leaves wide margin
     * for control messages while bounding memory per frame. */
    const val MAX_PAYLOAD = 4096

    /** Largest acceptable total frame (header + payload). */
    const val MAX_FRAME = HEADER_SIZE + MAX_PAYLOAD

    /** Frames older/newer than this are rejected (clock-skew tolerance). */
    const val MAX_CLOCK_SKEW_MS = 10 * 60 * 1000L

    /** TCP port used by the Wi-Fi Direct group owner for the session socket. */
    const val WIFI_DIRECT_PORT = 8988

    /** RFCOMM service UUID for the Bluetooth Classic transport. Fixed and
     * documented so both peers advertise/connect to the same service. */
    val BLUETOOTH_SERVICE_UUID: UUID =
        UUID.fromString("8f3b2a1c-4d5e-4f6a-9b8c-7d6e5f4a3b2c")

    /** Connection establishment timeouts. */
    const val CONNECT_TIMEOUT_MS = 12_000
    const val SOCKET_TIMEOUT_MS = 8_000

    /** Liveness ping interval / peer considered dead after this silence. */
    const val PING_INTERVAL_MS = 5_000L
    const val PEER_TIMEOUT_MS = 20_000L
}

/** Wire message types. Values are stable; do not renumber. */
object MessageType {
    // --- session establishment (payload rules documented in docs/PROTOCOL.md)
    const val HELLO: Byte = 0x01
    const val KEY_EXCHANGE: Byte = 0x02
    const val KEY_CONFIRM: Byte = 0x03
    const val SAS_CONFIRM: Byte = 0x04

    // --- real-time media (payload = 12-byte nonce + AES-256-GCM ciphertext)
    const val AUDIO: Byte = 0x10

    // --- floor control (half-duplex arbitration; payload = sealed control)
    const val FLOOR_REQUEST: Byte = 0x20
    const val FLOOR_GRANT: Byte = 0x21
    const val FLOOR_RELEASE: Byte = 0x22
    const val FLOOR_DENY: Byte = 0x23

    // --- liveness
    const val PING: Byte = 0x30
    const val PONG: Byte = 0x31

    // --- teardown
    const val DISCONNECT: Byte = 0x40
    const val ERROR: Byte = 0x41

    fun isKnown(type: Byte): Boolean = type in setOf(
        HELLO, KEY_EXCHANGE, KEY_CONFIRM, SAS_CONFIRM, AUDIO,
        FLOOR_REQUEST, FLOOR_GRANT, FLOOR_RELEASE, FLOOR_DENY,
        PING, PONG, DISCONNECT, ERROR
    )

    /** Message types whose payload is sent in cleartext (handshake only). */
    fun isPlaintext(type: Byte): Boolean = type == HELLO || type == KEY_EXCHANGE
}

/** Audio codec identifiers carried in the frame header. */
object CodecId {
    const val OPUS: Byte = 0x00
    const val PCM16: Byte = 0x01
}

/** Floor-control error/deny reason codes (payload of FLOOR_DENY / ERROR). */
object DenyReason {
    const val PEER_BUSY: Byte = 0x01
    const val NOT_AUTHENTICATED: Byte = 0x02
    const val SESSION_ENDED: Byte = 0x03
}

object DisconnectReason {
    const val USER_HANGUP: Byte = 0x01
    const val AUTH_MISMATCH: Byte = 0x02
    const val PROTOCOL_ERROR: Byte = 0x03
    const val TRANSPORT_LOST: Byte = 0x04
    const val PEER_TIMEOUT: Byte = 0x05
}
