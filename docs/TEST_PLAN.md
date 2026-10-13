# HamSeda Test Plan

> **Physical-device baseline:** use [`docs/MAIN_BASELINE_AUDIT.md`](MAIN_BASELINE_AUDIT.md) for the reproducible two-phone setup, attempt counts, evidence capture, performance-measurement method, and result sheet. CI compilation is not a substitute for tests on real radios and audio hardware. Do not mark unrun cases as passed.

## 1. Automated tests (run in CI)

`./gradlew :app:testDebugUnitTest` — pure JVM, no device needed:

| Area | File | What's verified |
|---|---|---|
| Frame codec | `FrameTest` | roundtrip, 29-byte header, max payload, rejection of bad magic/version/type, truncation, length mismatch, oversize; **timestamps NOT validated (HS-08: offline clocks may differ)** |
| Crypto | `CryptoTest` | mutual ECDH key agreement, HKDF determinism, AES-GCM roundtrip, tamper/wrong-key/wrong-AAD rejection, nonce uniqueness, SAS equality, transcript binding, role complementarity, malformed pubkey rejection, key wipe |
| Replay protection | `CryptoTest` (ReplayProtectionTest) | in-order, duplicates, too-old, in-window reorder, far jump, reset |
| Floor control | `FloorControlTest` | request→grant→release, busy deny, pending-vs-peer race, peer lease extension by audio, silent-peer timeout, self-TX cap, late grant ignored |
| Jitter buffer | `JitterBufferTest` | priming, in-order playout, gap→Lost after wait, in-time gap fill, stale drop, queue cap, stats; **HS-10: stale/duplicate at full buffer does not evict useful frames** |
| Codecs (JVM) | `CodecTest` | PCM bit-exact roundtrip, Opus profile defaults, codec factory and bad-input checks that do not require native audio I/O |
| Native Opus | `OpusCodecInstrumentedTest` | Actual Android JNI load, runtime libopus version, 20 ms encode/decode, signal correlation/energy, silence, reset and bad-input rejection |
| Framed socket | `FramedSocketTest` | delimiting, clean EOF → Closed, oversized envelope rejected, truncated frame → Error, oversized write rejected |
| Full session | `SessionHandshakeTest` | handshake→SAS→IN_SESSION over loopback transport, matching SAS, PTT→grant→audio frames→release, SAS-mismatch abort, **MITM tamper → AUTH_MISMATCH**, abrupt transport loss → clean teardown |
| Security (HS-01/02/05/07) | `SessionSecurityTest` | **bad-tag + huge seq does not poison replay window**; wrong session id dropped; duplicate rejected; concurrent malicious inputs don't race; **concurrent audio has unique monotonic wire seqs**; queue stays bounded; **playback failure → clean rollback (no fake IN_SESSION)**; **handshake/SAS timeouts fire (virtual time)** |

`./gradlew :app:lintDebug` must be clean; `./gradlew :app:assembleAndroidTest`
verifies instrumented sources compile. Run `OpusCodecInstrumentedTest` on an Android
emulator or physical device to verify the packaged native library and codec I/O; host-JVM
tests intentionally do not load Android ABI-specific shared libraries.

## 2. Manual two-device tests (cannot run on GitHub-hosted CI)

Setup for every case: install `HamSeda-debug-apk` on **two physical phones**,
mobile data OFF, no internet (see README "تضمین آفلاین بودن" for the airplane
procedure and its device variations).

### 2.1 Wi-Fi Direct voice (primary path)
1. Choose **Wi-Fi Direct** explicitly on both phones for this test; avoid Automatic initially because each phone can select a different transport.
2. Phone B: Discovery → select Wi-Fi Direct → tap "Start waiting" and keep the screen open. On Android 13+, the app must log that Wi-Fi Direct incoming-listen mode started.
3. Phone A: Discovery → select Wi-Fi Direct → tap "Scan"; verify B appears, then tap "اتصال امن" on B's peer card. Only A initiates the connection.
4. Do not use two independent outgoing attempts. One phone must remain in Wait for connection so it has an active SessionManager listener ready to accept the socket and secure handshake.
5. Both show the 6-digit SAS. Read the codes aloud; confirm they match on both → session starts.
6. A holds PTT for 10 s and speaks; B hears. Release is immediate (< 300 ms
   perceived). B then holds PTT; A hears.
7. While A transmits, B presses PTT → B sees "peer busy", A keeps the floor.
8. A walks out of range / disables Wi-Fi → both show "connection lost",
   session ends, keys wiped (no crash, no stuck mic icon).

### 2.2 SAS mismatch (MITM simulation)
1. Start pairing as in 2.1 but tap "کدها فرق دارند" on one phone.
2. Session aborts on both; no audio path ever opens.

### 2.3 Bluetooth Classic voice
1. Both phones: Settings → connection mode → Bluetooth. Enable Bluetooth via
   the system prompt (the app never toggles radios itself).
2. Phone B: Discovery → "Start waiting" → accept Android's discoverability
   prompt and leave the screen open. Phone A scans and connects to B.
3. Verify the 6-digit SAS, then test PTT in both directions. Note any audio
   artifacts vs Wi-Fi Direct; ordinary audio accessories (e.g., headphones)
   must not appear as phone candidates.

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
- Confirm diagnostics report the selected AudioTrack performance mode, buffer
  limit, and 3-frame initial jitter target. The playback path also limits
  application-fed PCM already queued in AudioTrack to three 20 ms frames
  (2,880 mono frames at 48 kHz); inspect the `AudioTrack queue pacing` log
  for `waitedFrames`, `maxWaitMs`, `maxObservedQueuedFrames`, and
  `queueLimitFrames`. The limit should be 2,880 frames; a queue-pacing timeout
  is a playback failure, not a pass. Compare audibility, perceived gaps,
  underruns, and pacing waits against the prior APK on both transports. This
  bounds queued PCM; it is not a measurement of mouth-to-ear latency.
- Repeat with the screen locked for 5 minutes and verify voice remains smooth;
  the active-session partial CPU wake lock should be acquired and released on
  teardown. Record the battery cost.
- Force sender backpressure in a test build and verify the outbound queue stays
  freshness-first: old pending audio is evicted, recent audio survives, and
  control frames remain queued in order. For Bluetooth, confirm that a stream
  write completing under 350 ms proceeds normally, while a simulated/observed
  write exceeding 350 ms closes only the matching active socket, emits a
  connection-lost diagnostic, and allows a fresh authenticated reconnect.
  Check that a late return cannot report the timed-out write as delivered.
  Do not deliberately inject a production timeout on a live conversation; use
  a controlled lab/test build for the forced-stall scenario.
- 10-minute continuous session: watch CPU/battery/thermal in Android Studio
  profiler; note anomalies per device model.
- RF range and transmit power are hardware / Android-framework controlled.
  Do not claim that the APK boosts antenna power; measure range, drop rate and
  reconnect behavior on physical devices at increasing distances.

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
