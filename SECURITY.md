# Security & Threat Model — HamSeda

> No independent security audit has been performed on this project. A
> systematic code-level hardening pass (2026-10-09) fixed 11 audited findings
> (HS-01…HS-11, see CHANGELOG) with regression tests, but external review is
> still recommended before high-stakes use. Nothing below claims "fully
> audited" or "100% secure."

## Diagnostic log redaction

The in-memory diagnostic log applies bounded input length, control-character
normalization, and redaction for MAC/IPv4/IPv6 literals plus explicitly labeled
secret fields such as private/derived/session/audio/control keys, nonce, and SAS
values. This is defense in depth: callers must still never log raw keys, SAS
seeds, plaintext audio, or protocol payloads. Unlabeled arbitrary binary or
encoded secret data cannot be reliably identified by generic text redaction.

## Session sequence exhaustion

Each authenticated session uses a monotonic uint32 wire-sequence counter and
ends before `0xFFFF0000`. It does not wrap sequence values within a session.
A new handshake establishes a fresh session sequence space and fresh session
keys. If the guard is reached, the local endpoint closes the session without
relying on a final encrypted DISCONNECT frame; the transport teardown is the
fallback. This is a defensive boundary, not a rekey protocol.

## What is protected

Voice and control traffic between two nearby Android phones that have
completed the in-app pairing flow, against:

| Threat | Mitigation |
|---|---|
| Passive interception of radio traffic | AES-256-GCM on every audio/control frame (application layer, independent of WPA2/Bluetooth link encryption) |
| Active man-in-the-middle during pairing | Ephemeral ECDH (P-256) + commitment/reveal before share disclosure + transcript-bound KEY_CONFIRM + user-compared 6-digit SAS; mismatch aborts the session |
| Replay / duplication of frames | Per-direction uint32 sequence numbers + 128-frame sliding window; stale/out-of-session frames dropped |
| Unauthenticated state influence (HS-01) | Frames are AEAD-authenticated BEFORE the replay window, liveness, or floor state may change; even ignored frames are authenticated |
| Out-of-order wire injection (HS-02) | Single serialized outbound pipeline: one consumer allocates sequences and writes; no per-frame coroutines |
| Header tampering | The 29-byte frame header (+ sender role) is AES-GCM associated data |
| Device-name spoofing | Bluetooth/Wi-Fi Direct names are display-only, never identity |
| Crashed peer locking the channel | Floor-control leases with timeouts; single transmissions capped |
| Hot-mic accidents | Microphone opens only on explicit PTT press; release stops immediately; no background capture mode exists |
| Key/nonce reuse | Fresh 96-bit random nonce per frame; ephemeral session keys wiped on disconnect |
| Data leakage via logs | No keys, audio, pairing material, or packet contents are logged; MAC addresses are redacted from the diagnostic log |
| Clock-skew DoS (HS-08) | Wall-clock timestamps are diagnostic only; authentication uses AEAD + sequences, so offline phones with different clocks still connect |

## Cryptographic construction

1. **Key exchange:** ephemeral ECDH on NIST P-256 (`KeyPairGenerator("EC")`,
   `ECGenParameterSpec("secp256r1")`, `KeyAgreement("ECDH")`). Keys are
   generated per session and never persisted.
2. **Commitment/reveal (wire protocol v4):** before either public key or nonce is
   revealed, each side sends a domain-separated SHA-256 commitment to its public
   key, nonce, exact HELLO payload and independent 32-byte random salt. KEY_EXCHANGE
   reveals those values only after the peer's commitment arrives. Each reveal is
   verified in constant time before ECDH; mismatch aborts before SAS display.
   This blocks adaptive share selection after observing the other endpoint's reveal.
3. **Key derivation:** HKDF-SHA-256 (RFC 5869, over platform HMAC-SHA-256)
   with salt = SHA-256(ordered nonces), info = `HamSeda-v4/session-keys`,
   output = 32-byte audio key + 32-byte control key + 8-byte session id +
   32-byte SAS seed. Key separation is structural, not by convention.
4. **Encryption:** AES-256-GCM (`AES/GCM/NoPadding`, 128-bit tag), random
   96-bit nonce per frame, header+role as AAD.
5. **Authentication of the exchange (wire protocol v4):** KEY_CONFIRM
   authenticates a SHA-256 transcript with domain separator
   `HamSeda-v4/key-confirm`. The canonical, length-prefixed transcript binds
   each peer's exact two-byte HELLO payload (protocol version + codec preference),
   16-byte nonce, and encoded ECDH public key, ordered deterministically by
   public-key role. Changing the key, nonce, protocol version, or codec
   preference changes the transcript and causes authentication to fail.
6. **SAS:** 6 digits = `HMAC-SHA256(sasSeed, "HamSeda-v4/sas") mod 1_000_000`,
   displayed on both phones. The SAS is a short equality check, not key material.
   Users must compare it out-of-band and each confirm. A six-digit value gives
   about 20 bits of manual verification; commitment/reveal prevents adaptive
   share selection after an honest endpoint's share becomes visible.
7. **Roles:** sender role (0/1) is derived deterministically by comparing the
   two encoded public keys, and mixed into the AAD — this defeats reflection
   of a sender's own frames back at them.

No custom ciphers, no hard-coded keys, no certificate/pinning schemes (there
is no TLS network to pin to — by design).

## Machine-checked cryptographic domains

CI verifies these exact documentation rows against `SessionCrypto.kt`.
The source constants are authoritative; update this table whenever a domain
separator is intentionally changed.

| Source constant | Value |
|---|---|
| `SessionCrypto.INFO` | `HamSeda-v4/session-keys` |
| `SessionCrypto.SAS_INFO` | `HamSeda-v4/sas` |
| `SessionCrypto.COMMIT_INFO` | `HamSeda-v4/key-commitment` |

## What is NOT protected / limitations

- **Compromised phone:** if either endpoint is compromised, the session is
  compromised. Nothing in an app can fix that.
- **OS / baseband / firmware attackers:** out of scope; the app relies on the
  platform's crypto provider and radio stack.
- **Traffic analysis:** an observer can see that two radios are exchanging
  similarly-sized frames at voice cadence. Payload content is encrypted;
  presence/timing is not hidden.
- **SAS discipline:** security against an active MITM depends on the users
  actually comparing the codes carefully. Tapping "match" blindly voids the
  guarantee — the UI text says this explicitly.
- **Bluetooth pairing vs. app pairing:** the app's session authentication is
  independent of (and does not rely on) Bluetooth system pairing.
- **Physical proximity attacks:** an attacker within radio range can jam or
  DoS the link; availability is not guaranteed.
- **No forward secrecy across reinstalls** is a non-issue: keys are ephemeral
  per session and wiped afterwards (`SessionKeys.wipe()` zeroes all material).

## Privacy properties

- No account, no analytics, no ads, no cloud crash reporting, no remote config.
- No audio is recorded, stored, or transmitted except to the authenticated
  peer during an active session.
- Persistent storage holds only non-sensitive UI preferences
  (theme, transport preference, codec choice). No identities, no contact lists.
- The `INTERNET` manifest permission exists solely because Android requires
  it to open local TCP sockets; the app never opens a socket to a public IP.

## Additional hardening (2026-10-12)

- Peer ECDH public keys are rejected unless their parameters match secp256r1/P-256; provider-supported alternate curves are not silently accepted.
- HKDF clears its PRK, per-round intermediate blocks, a single temporary input block (avoiding an extra chained-concatenation copy), and any substitute salt it allocates. Session key derivation clears its combined output and salt after extracting the individual keys, and the temporary SAS HMAC digest is cleared. This is best-effort JVM memory hygiene, not a guarantee against crypto-provider or garbage-collector copies.
- Opus packets are bounded to the local encoder's maximum at both the authenticated session-validation and decoder boundaries.
- The SAS screen blocks screenshots/recent-task snapshots while visible and disables confirmation until a code has actually been generated. BLUETOOTH_ADVERTISE is not requested at startup; it is requested only for the explicit Bluetooth-listen role. Outbound queue payloads, decrypted session buffers, jitter-queue entries, decoded playback scratch, AES-GCM nonce/ciphertext scratch buffers, and ECDH/HKDF temporary material are cleared on their applicable consume/drop/teardown paths.
- Diagnostic text is capped before normalization and address-redaction regexes run.
- The checked-in Gradle Wrapper JAR is SHA-256 checked in CI against the pinned Gradle 9.8.1 checksum (`3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98`). The wrapper distribution is pinned to Gradle 9.8.1 and its ZIP checksum is recorded in `gradle/wrapper/gradle-wrapper.properties` (`dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f`). CI verifies the JAR checksum and validates the pinned distribution before building. Dependabot monitors Gradle and GitHub Actions updates.

## Trust-on-first-use (TOFU) status

TOFU identity pinning is **not implemented**. The current handshake uses
ephemeral session keys and requires users to compare the SAS on every session.
A safe TOFU implementation requires a persistent device identity-signing key,
a local store of peer identity public keys pinned only after a fully
SAS-confirmed session, signature verification on subsequent handshakes, and UI
to identify and reset trusted peers. Pinning the existing ephemeral ECDH key
alone would not provide a stable identity and must not be presented as TOFU.

## Reporting

Please do **not** report security vulnerabilities in public issues, pull
requests, or discussions. The repository owner should enable GitHub Private
Vulnerability Reporting in **Settings → Security and quality → Advanced
Security → Private vulnerability reporting**. Once enabled, use the repository's
**Security → Advisories → Report a vulnerability** flow to send a private report.
Do not include exploit details or sensitive reproduction material in a public
issue while the private channel is unavailable.
