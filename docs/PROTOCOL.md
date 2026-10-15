# HamSeda Wire Protocol v5

Transport-agnostic framing over a reliable byte stream (TCP socket for
Wi-Fi Direct, RFCOMM socket stream for Bluetooth Classic). All integers are
big-endian.

## Envelope

Each datagram on the stream is:

```
uint32-be length N || N bytes of frame
```

`1 <= N <= 4125` (29-byte header + max 4096-byte payload). Anything else is a
protocol violation → drop the connection.

## Frame

```
 0..1   magic        0x48 0x53 ("HS")
 2      version      0x05
 3      type         see below
 4..11  sessionId    8 bytes (zeros until keys are derived)
 12..15 seq          uint32, per-direction sequence number
 16..23 timestamp    int64, sender epoch millis
 24     codecId      0x00=Opus, 0x01=PCM16 (AUDIO frames); 0xFF otherwise
 25..28 payloadLen   uint32, 0..4096
 29..   payload      payloadLen bytes
```

Validation: magic, version == 5, known type, payloadLen bounds, exact
`29 + payloadLen` total size. The timestamp is diagnostic only (HS-08) —
it is NOT validated, because two offline phones may have different manual
clock settings. Anti-replay relies on AEAD + sequence numbers.

## Inbound processing order (HS-01)

Every received frame is processed in this strict order:

1. Structural validation (magic, version, type, lengths) — no state changes.
2. Session-ID binding — no state changes.
3. **AEAD authentication/decryption** — no state changes on failure.
4. Replay-window check — mutates only for authenticated frames.
5. Liveness timestamp update — only for authenticated frames.
6. Type-specific dispatch using the authenticated plaintext.

A frame that fails step 3 is dropped and counted, but cannot advance the
replay window, refresh liveness, or affect the floor — even if it would
have been ignored anyway (e.g. audio from a non-floor-holder).

## Outbound ordering (HS-02)

All outgoing messages pass through one bounded FIFO (maximum 64 total
messages) and one sender coroutine. A conflated signal wakes the consumer; it
allocates the uint32 sequence, seals, and writes frames in FIFO order. Audio has
a separate three-frame queue budget (about 60 ms at 20 ms/frame); when full, the
oldest queued audio is evicted. Before encryption/serialization, audio already
older than 120 ms is discarded and counted as a freshness drop. This avoids
replaying stale speech after a blocked/slow socket write. Control messages are
retained where possible and can displace queued audio at the total queue cap.

## Message types

| Value | Name | Payload (plaintext / sealed) |
|---|---|---|
| 0x01 | HELLO | `version(1) || codecPref(1)` — plaintext |
| 0x02 | KEY_EXCHANGE | `ephLen(2) || ephemeral P-256 key || nonce(16) || salt(32) || identityId(16) || identityPubLen(2) || identity P-256 key || sigLen(2) || ECDSA/SHA-256 signature` — plaintext reveal after peer commitment |
| 0x03 | KEY_CONFIRM | `transcriptHash(32) || senderAlreadyPinsReceiver(1)` — sealed (control key). The trust byte is 1 only when the sender already has a valid TOFU pin for the receiver's identity. |
| 0x04 | SAS_CONFIRM | empty — sealed (control key) |
| 0x05 | KEY_COMMIT | 32-byte SHA-256 commitment to ephemeral key, nonce, HELLO, identity ID/key and salt — plaintext |
| 0x10 | AUDIO | `nonce(12) \|\| AES-256-GCM(audio)` — sealed (audio key) |
| 0x20 | FLOOR_REQUEST | empty — sealed (control key) |
| 0x21 | FLOOR_GRANT | `leaseMs(4)` — sealed (control key) |
| 0x22 | FLOOR_RELEASE | empty — sealed (control key) |
| 0x23 | FLOOR_DENY | `reason(1)` — sealed (control key) |
| 0x30 | PING | `timestamp(8)` — sealed (control key) |
| 0x31 | PONG | echoed PING payload — sealed (control key) |
| 0x40 | DISCONNECT | `reason(1)` — sealed (control key) |
| 0x41 | ERROR | reserved — sealed (control key) |

Sealed payloads: `nonce(12) || ciphertext || tag(16)`; the 29-byte header
plus the sender's role byte (0/1, derived by comparing public keys) is the
AES-GCM associated data.

## Session establishment

```
A -> B: HELLO
B -> A: HELLO
A -> B: KEY_COMMIT       (commit to A's ephemeral key, nonce, HELLO, identity ID/key and random salt)
B -> A: KEY_COMMIT       (commit to B's ephemeral key, nonce, HELLO, identity ID/key and random salt)
A -> B: KEY_EXCHANGE     (reveal only after receiving B's commitment)
B -> A: KEY_EXCHANGE     (reveal only after receiving A's commitment)
A,B: verify reveals against commitments using constant-time digest comparison
A,B: derive ECDH/HKDF keys; exchange sealed KEY_CONFIRM transcript hashes plus authenticated trust flags
A,B: skip SAS only if each has pinned the other's identity and both trust flags are 1; otherwise both display the same six-digit SAS and await local confirmation
A -> B: SAS_CONFIRM      (automatic only when mutual TOFU trust is verified; otherwise after local SAS confirmation)
B -> A: SAS_CONFIRM      (automatic only when mutual TOFU trust is verified; otherwise after local SAS confirmation)
=> IN_SESSION (audio enabled)
```

Commit/reveal: KEY_COMMIT is SHA-256 with domain HamSeda-v5/key-commitment over the ephemeral public key, 16-byte nonce, exact two-byte HELLO, 16-byte identity ID, length-prefixed identity-signing public key and fresh 32-byte random salt. The salt is revealed only in KEY_EXCHANGE. The reveal also carries an ECDSA/SHA-256 signature using HamSeda-v5/identity-proof, binding the identity to the full reveal. Commitment, signature and any existing TOFU pin are checked before ECDH or SAS display; mismatches abort as AUTH_MISMATCH. KEY_CONFIRM contains the locally verified transcript hash plus an AEAD-authenticated trust flag. SAS is skipped only when both endpoints verify their pinned peer key and each reports that it already pins the other; if one device has lost a pin, both display and manually compare SAS.

Codec negotiation: `codec = min(prefA, prefB)` from the two HELLOs
(Opus=0x00 wins unless both prefer PCM).

## Audio profile (protocol v5)

- Internal audio is 48 kHz, mono, signed 16-bit PCM.
- Each frame spans 20 ms (960 samples/channel; 1920 bytes in PCM16 mode).
- Opus uses official libopus 1.6.1, a 32 kbps target, voice mode, full-band
  maximum bandwidth, and constrained VBR. Encoder complexity is device-profile
  dependent: 4 on Android 8/9 (API 26–28), 8 on Android 10+ (API 29+).
- PCM16 is diagnostic-only at 768 kbps; prefer Opus for Bluetooth Classic.

Both HELLO payloads are included in a domain-separated KEY_CONFIRM transcript, canonicalized together with the corresponding public key and nonce. A modified codec preference or reassigned nonce changes the transcript and causes the session to fail closed.

## Floor control (half-duplex)

- Self PTT down → FLOOR_REQUEST. Peer grants (FLOOR_GRANT + lease) only if
  the floor is free and it is not itself waiting; else FLOOR_DENY(PEER_BUSY).
- PTT up → FLOOR_RELEASE. Leases: peer 15 s (extended by each audio frame),
  self capped at 60 s. Timeouts release silently-held floors.
- Only the floor holder's AUDIO frames are accepted; others are dropped and
  counted.

## Liveness & teardown

PING every 5 s; silence > 20 s → session ends (PEER_TIMEOUT). DISCONNECT
carries a reason byte (USER_HANGUP / AUTH_MISMATCH / PROTOCOL_ERROR /
TRANSPORT_LOST / PEER_TIMEOUT). Session keys are wiped on teardown.

## Versioning

`version` is checked strictly: any frame with `version != 5` is rejected. Protocol v5 is not wire-compatible with v1–v4; update both phones together. The v5 KEY_CONFIRM transcript includes both exact HELLO payloads (including codec preferences) and each peer's ephemeral public key and nonce. On first contact, both users compare and confirm the SAS before the peer public key is pinned. Later sessions verify the pinned signing key and may skip SAS; removing the pin restores first-contact verification.
