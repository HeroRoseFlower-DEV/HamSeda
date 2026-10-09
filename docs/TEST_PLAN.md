# HamSeda Test Plan

## 1. Automated tests (run in CI)

`./gradlew :app:testDebugUnitTest` — pure JVM, no device needed:

| Area | File | What's verified |
|---|---|---|
| Frame codec | `FrameTest` | roundtrip, 29-byte header, max payload, rejection of bad magic/version/type, truncation, length mismatch, oversize, clock skew |
| Crypto | `CryptoTest` | mutual ECDH key agreement, HKDF determinism, AES-GCM roundtrip, tamper/wrong-key/wrong-AAD rejection, nonce uniqueness, SAS equality, transcript binding, role complementarity, malformed pubkey rejection, key wipe |
| Replay protection | `CryptoTest` (ReplayProtectionTest) | in-order, duplicates, too-old, in-window reorder, far jump, reset |
| Floor control | `FloorControlTest` | request→grant→release, busy deny, pending-vs-peer race, peer lease extension by audio, silent-peer timeout, self-TX cap, late grant ignored |
| Jitter buffer | `JitterBufferTest` | priming, in-order playout, gap→Lost after wait, in-time gap fill, stale drop, queue cap, stats |
| Codecs | `CodecTest` | PCM bit-exact roundtrip, Opus compression ratio, Opus energy preservation + correlation, silence, stream consistency, bad-input rejection |
| Framed socket | `FramedSocketTest` | delimiting, clean EOF → Closed, oversized envelope rejected, truncated frame → Error, oversized write rejected |
| Full session | `SessionHandshakeTest` | handshake→SAS→IN_SESSION over loopback transport, matching SAS, PTT→grant→audio frames→release, SAS-mismatch abort, **MITM tamper → AUTH_MISMATCH**, abrupt transport loss → clean teardown |

`./gradlew :app:lintDebug` must be clean; `./gradlew :app:assembleAndroidTest`
verifies instrumented sources compile (execution needs hardware — see below).

## 2. Manual two-device tests (cannot run on GitHub-hosted CI)

Setup for every case: install `HamSeda-debug-apk` on **two physical phones**,
mobile data OFF, no internet (see README "تضمین آفلاین بودن" for the airplane
procedure and its device variations).

### 2.1 Wi-Fi Direct voice (primary path)
1. Both phones: Discovery → Automatic → Scan → each sees the other.
2. Phone A taps "اتصال امن" on B. Both show the 6-digit SAS.
3. Read the codes aloud; confirm they match on both → session starts.
4. A holds PTT 10 s and speaks; B hears. Release is immediate (< 300 ms
   perceived). B holds PTT; A hears.
5. While A transmits, B presses PTT → B sees "peer busy", A keeps the floor.
6. A walks out of range / disables Wi-Fi → both show "connection lost",
   session ends, keys wiped (no crash, no stuck mic icon).

### 2.2 SAS mismatch (MITM simulation)
1. Start pairing as in 2.1 but tap "کدها فرق دارند" on one phone.
2. Session aborts on both; no audio path ever opens.

### 2.3 Bluetooth Classic voice
1. Both phones: Settings → connection mode → Bluetooth (or Automatic with
   Wi-Fi off). Enable Bluetooth via the system prompt (the app never toggles
   radios itself).
2. Scan → pair → SAS → PTT both directions. Note any audio artifacts vs
   Wi-Fi Direct.

### 2.4 Interruptions & lifecycle
- Incoming phone call during transmission → TX stops instantly (audio focus).
- Plug/unplug wired headset mid-session → route switches, no crash.
- Rotate screen, lock screen, background the app (notification persists),
  foreground again → session survives; Stop from notification ends everything.
- Deny microphone permission → clear recovery message, no nag loop.
- Deny nearby-devices/Bluetooth permission → rationale card, then system
  settings path.
- **Android 17 (API 37) local-network permission:** on a phone running
  Android 17, tap a peer → the app shows the local-network rationale and the
  system prompt. Grant → Wi-Fi Direct voice works. Deny → a clear
  \"permission denied\" error, no silent stall. Revoke mid-session in system
  Settings → session ends cleanly with the transport-lost message.

### 2.5 Offline & network audit
- With the airplane+manual-radio procedure: full session works.
- Practical network check: run the session while a packet capture (e.g.
  `tcpdump` on a rooted device, or a Wi-Fi AP in monitor mode nearby)
  records traffic — assert no packets to public IPs / no DNS for app
  services. (Not an accredited audit; the statement in README is limited to
  this procedure.)

### 2.6 Performance (before any numeric claim)
- Measure end-to-end latency (mouth-to-ear) on real devices before quoting it.
- 10-minute continuous session: watch CPU/battery/thermal in Android Studio
  profiler; note anomalies per device model.

## 3. Version matrix

Manual passes should cover: one device on Android 8/9 (minSdk), one on
Android 12 (Bluetooth permission model), one on Android 13/14 (notification
permission, NEARBY_WIFI_DEVICES), one on the latest stable release. Emulator
runs cannot validate Wi-Fi Direct/Bluetooth audio — they only compile.

## 4. Not automated (and why)

- Real radio discovery/connection (needs two radios + framework).
- AudioRecord/AudioTrack latency & quality (needs hardware + ears).
- Foreground-service microphone behavior under OEM battery savers.
- Wi-Fi Direct group-owner negotiation across vendors.
