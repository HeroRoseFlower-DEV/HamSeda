# Security & Threat Model — HamSeda

> No independent security audit has been performed on this project. A
> systematic code-level hardening pass (2026-10-09) fixed 11 audited findings
> (HS-01…HS-11, see CHANGELOG) with regression tests, but external review is
> still recommended before high-stakes use. Nothing below claims "fully
> audited" or "100% secure."

## What is protected

Voice and control traffic between two nearby Android phones that have
completed the in-app pairing flow, against:

| Threat | Mitigation |
|---|---|
| Passive interception of radio traffic | AES-256-GCM on every audio/control frame (application layer, independent of WPA2/Bluetooth link encryption) |
| Active man-in-the-middle during pairing | Ephemeral ECDH (P-256) + transcript-bound KEY_CONFIRM + user-compared 6-digit SAS; mismatch aborts the session |
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
2. **Key derivation:** HKDF-SHA-256 (RFC 5869, over platform HMAC-SHA-256)
   with salt = SHA-256(ordered nonces), info = `"HamSeda-v1/session-keys"`,
   output = 32-byte audio key + 32-byte control key + 8-byte session id +
   32-byte SAS seed. Key separation is structural, not by convention.
3. **Encryption:** AES-256-GCM (`AES/GCM/NoPadding`, 128-bit tag), random
   96-bit nonce per frame, header+role as AAD.
4. **Authentication of the exchange:** each side seals
   `SHA-256(ordered pubkeys || ordered nonces)` with the control key. A peer
   that substituted a key or nonce cannot produce a matching transcript.
5. **SAS:** 6 digits = `HMAC-SHA256(sasSeed, "HamSeda-v1/sas") mod 1_000_000`,
   displayed on both phones. The SAS is *detection*, not key material: users
   compare it out-of-band and each confirm. A six-digit code carries ~20 bits;
   it is safe here because it authenticates an already-strong ECDH exchange
   rather than bootstrapping trust from the code itself.
6. **Roles:** sender role (0/1) is derived deterministically by comparing the
   two encoded public keys, and mixed into the AAD — this defeats reflection
   of a sender's own frames back at them.

No custom ciphers, no hard-coded keys, no certificate/pinning schemes (there
is no TLS network to pin to — by design).

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

## Reporting

This is a personal project without a bug-bounty process. If you find a
vulnerability, please open a GitHub issue describing it (without posting
exploit code targeting users) so it can be fixed openly.
