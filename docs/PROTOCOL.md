# HamSeda Wire Protocol v1

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
 2      version      0x01
 3      type         see below
 4..11  sessionId    8 bytes (zeros until keys are derived)
 12..15 seq          uint32, per-direction sequence number
 16..23 timestamp    int64, sender epoch millis
 24     codecId      0x00=Opus, 0x01=PCM16 (AUDIO frames); 0xFF otherwise
 25..28 payloadLen   uint32, 0..4096
 29..   payload      payloadLen bytes
```

Validation: magic, version == 1, known type, payloadLen bounds, exact
`29 + payloadLen` total size, `|now - timestamp| <= 10 min`.

## Message types

| Value | Name | Payload (plaintext / sealed) |
|---|---|---|
| 0x01 | HELLO | `version(1) || codecPref(1)` — plaintext |
| 0x02 | KEY_EXCHANGE | `pubLen(2) || X.509 P-256 pubkey || nonce(16)` — plaintext |
| 0x03 | KEY_CONFIRM | `SHA-256(ordered pubkeys \|\| ordered nonces)` — sealed (control key) |
| 0x04 | SAS_CONFIRM | empty — sealed (control key) |
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
A -> B: HELLO            (on transport CONNECTED)
B -> A: HELLO
A -> B: KEY_EXCHANGE     (on first HELLO; duplicates ignored)
B -> A: KEY_EXCHANGE
A: derive keys; A -> B: KEY_CONFIRM
B: derive keys; B -> A: KEY_CONFIRM
A,B: verify transcript; display SAS = HMAC(sasSeed,"HamSeda-v1/sas") mod 1e6
A -> B: SAS_CONFIRM      (after local user confirms match)
B -> A: SAS_CONFIRM      (after local user confirms match)
=> IN_SESSION (audio enabled)
```

Codec negotiation: `codec = min(prefA, prefB)` from the two HELLOs
(Opus=0x00 wins unless both prefer PCM).

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

`version` is checked strictly: any frame with `version != 1` is rejected.
Future versions must negotiate in HELLO before changing framing.
