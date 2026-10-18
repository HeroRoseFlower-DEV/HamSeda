package com.hamseda.walkie.proto

import java.util.UUID

/**
 * Wire protocol constants for HamSeda v5.
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
    const val VERSION: Byte = 5

    /** Fixed header size in bytes: magic(2) + version(1) + type(1) + sessionId(8)
     * + seq(4) + timestamp(8) + codecId(1) + payloadLen(4). */
    const val HEADER_SIZE = 29

    /** Maximum payload bytes per frame. A 20 ms Opus frame at ~32 kbps is
     * around 80 bytes; PCM 20 ms @48 kHz mono is 1920 bytes. 4096 leaves wide
     * margin for authenticated media and control messages while bounding memory. */
    const val MAX_PAYLOAD = 4096

    /** Largest acceptable total frame (header + payload). */
    const val MAX_FRAME = HEADER_SIZE + MAX_PAYLOAD

    /**
     * Offline messaging framing. Sealed control frames carry at most
     * MAX_PAYLOAD bytes; the AES-GCM seal costs NONCE(12) + TAG(16) = 28
     * bytes, so a messaging plaintext chunk is capped at 4000 bytes with
     * margin. A single text message may span at most 16 chunks (~62 KiB
     * of text); larger input is rejected before it touches the wire
     * (anti-DoS).
     */
    const val MSG_SEAL_OVERHEAD = 28
    const val MSG_CHUNK_PLAINTEXT = 4000
    /** Chunk header: msgId(16) + senderSeq(8) + timestamp(8) + index(4) + total(4). */
    const val MSG_CHUNK_HEADER_BYTES = 40
    const val MSG_MAX_CHUNKS = 16
    /**
     * Maximum UTF-8 bytes of a single text message (~62 KiB): each chunk
     * frame carries MSG_CHUNK_PLAINTEXT bytes of sealed plaintext including
     * its header, so the text capacity per chunk is (4000 - 40).
     */
    const val MSG_MAX_BYTES =
        (MSG_CHUNK_PLAINTEXT - MSG_CHUNK_HEADER_BYTES) * MSG_MAX_CHUNKS
    /** Drop a partially-reassembled message after this long without progress. */
    const val MSG_REASSEMBLY_TIMEOUT_MS = 10 * 60 * 1000L

    /**
     * HS-08: wall-clock skew is no longer enforced. The frame timestamp is
     * diagnostic metadata only; authentication and anti-replay rely on
     * AEAD + sequence numbers + session binding. Kept for documentation.
     */
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
    /**
     * How long to wait for the peer to accept a Wi-Fi Direct system
     * invitation before failing visibly (the framework gives no callback
     * when the peer simply ignores the prompt).
     */
    const val P2P_INVITE_TIMEOUT_MS = 30_000L

    /** Liveness ping interval / peer considered dead after this silence. */
    const val PING_INTERVAL_MS = 5_000L
    const val PEER_TIMEOUT_MS = 20_000L

    /**
     * Protocol phase deadlines (HS-07). A peer that disappears mid-handshake
     * or never confirms the SAS must cause bounded teardown, not an
     * indefinite spinner.
     */
    const val HANDSHAKE_TIMEOUT_MS = 30_000L
    const val SAS_CONFIRM_TIMEOUT_MS = 120_000L

    /**
     * Transport-level dead-peer watchdog: a peer that dies without FIN
     * leaves a half-open socket the reader would otherwise block on
     * forever. Must exceed the longest legitimate quiet period
     * ([SAS_CONFIRM_TIMEOUT_MS]) so slow SAS comparisons are never killed;
     * in-session silence is already caught faster by [PEER_TIMEOUT_MS].
     */
    const val TRANSPORT_IDLE_TIMEOUT_MS = 150_000L
}

/** Wire message types. Values are stable; do not renumber. */
object MessageType {
    // --- session establishment (payload rules documented in docs/PROTOCOL.md)
    const val HELLO: Byte = 0x01
    const val KEY_EXCHANGE: Byte = 0x02
    const val KEY_CONFIRM: Byte = 0x03
    const val SAS_CONFIRM: Byte = 0x04
    const val KEY_COMMIT: Byte = 0x05

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

    // --- offline text messaging (payload = sealed messaging codec; see
    // MessagingCodec). All ride the E2E-encrypted control channel and the
    // existing per-frame replay window; none is ever plaintext.
    const val MSG: Byte = 0x50
    const val MSG_ACK: Byte = 0x51
    const val MSG_READ: Byte = 0x52
    const val TYPING: Byte = 0x53
    const val CHUNK: Byte = 0x54
    const val MSG_HELLO: Byte = 0x55

    fun isKnown(type: Byte): Boolean = type in setOf(
        HELLO, KEY_EXCHANGE, KEY_CONFIRM, SAS_CONFIRM, KEY_COMMIT, AUDIO,
        FLOOR_REQUEST, FLOOR_GRANT, FLOOR_RELEASE, FLOOR_DENY,
        PING, PONG, DISCONNECT, ERROR,
        MSG, MSG_ACK, MSG_READ, TYPING, CHUNK, MSG_HELLO
    )

    /** Message types whose payload is sent in cleartext (handshake only). */
    fun isPlaintext(type: Byte): Boolean = type == HELLO || type == KEY_COMMIT || type == KEY_EXCHANGE
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
