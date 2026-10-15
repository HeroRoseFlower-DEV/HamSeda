# HamSeda Test Plan

## Microphone leveler validation

On each test phone, compare the same quiet phrase, normal speech, loud phrase,
and room silence with software microphone gain enabled and disabled. Repeat
while connected over Wi-Fi Direct and Bluetooth Classic. Inspect the 50-frame
capture diagnostics (input RMS, average/maximum gain, near-silence count and
enabled flag), and listen for background-noise pumping, clipping, tail
suppression and intelligibility. Run at least ten PTT bursts after a 60-second
session. Synthetic tests do not prove subjective quality on every microphone
or vendor AGC implementation. Software gain defaults on for installs without
an explicit preference, but users can disable it to compare raw input because
a software leveler can amplify noise after hardware AGC/noise suppression.

> **Physical-device baseline:** use [`docs/MAIN_BASELINE_AUDIT.md`](MAIN_BASELINE_AUDIT.md) for the reproducible two-phone setup, attempt counts, evidence capture, performance-measurement method, and result sheet. CI compilation is not a substitute for tests on real radios and audio hardware. Do not mark unrun cases as passed.

## 1. Automated tests (run in CI)

- `SessionSequenceCounterTest`: monotonic sequence values, hard stop before the
  configured safety limit, no wrap while exhausted, and reset for a fresh session.
- Session sequence rollover is intentionally handled by ending the session
  before uint32 exhaustion. This test uses a small injected limit; it does not
  simulate a multi-year live session or physical radio teardown at the guard.

`./gradlew :app:testDebugUnitTest` — pure JVM, no device needed:

| Area | File | What's verified |
|---|---|---|
| Frame codec | `FrameTest` | roundtrip, 29-byte header, max payload, rejection of bad magic/version/type, truncation, length mismatch, oversize; **timestamps NOT validated (HS-08: offline clocks may differ)** |
| Crypto | `CryptoTest` | mutual ECDH key agreement, HKDF determinism, AES-GCM roundtrip, tamper/wrong-key/wrong-AAD rejection, nonce uniqueness, key commitment binds key/nonce/HELLO/salt, SAS equality, transcript binding, role complementarity, malformed pubkey rejection, key wipe |
| Replay protection | `CryptoTest` (ReplayProtectionTest) | in-order, duplicates, too-old, in-window reorder, far jump, reset |
| Floor control | `FloorControlTest` | request→grant→release, busy deny, pending-vs-peer race, peer lease extension by audio, silent-peer timeout, self-TX cap, late grant ignored |
| Jitter buffer | `JitterBufferTest` | priming, in-order playout, gap→Lost after wait, in-time gap fill, stale drop, queue cap, stats; **HS-10: stale/duplicate at full buffer does not evict useful frames** |
| Playback queue tracker | `PlaybackQueueTrackerTest` | pending PCM accounting, underrun clamp, unsigned playback-head wrap, implausible position jump, and queue-limit rounding to the AudioTrack start threshold |
| Codecs (JVM) | `CodecTest` | PCM bit-exact roundtrip, Opus profile defaults, codec factory and bad-input checks that do not require native audio I/O |
| Microphone level control (JVM) | `CaptureGainControllerTest` | boosts quiet active frames, leaves near-silence unamplified, reduces gain on loud transients immediately, and respects the peak ceiling |
| Platform routing and Bluetooth discovery | `ModernizationTest` | deterministic API 31+ audio route priority and expiration of discovered non-bonded peers after the 120 s freshness TTL; bonded devices remain listed |
| Native Opus | `OpusCodecInstrumentedTest` | Actual Android JNI load, runtime libopus version, 20 ms encode/decode, signal correlation/energy, DTX compaction during sustained silence, LBRR packet detection and prior-frame FEC decode, reset and bad-input rejection |
| Framed socket | `FramedSocketTest` | delimiting, clean EOF → Closed, oversized envelope rejected, truncated frame → Error, oversized write rejected |
| Full session | `SessionHandshakeTest` | v5 HELLO→KEY_COMMIT→KEY_EXCHANGE ordering; handshake→SAS→IN_SESSION; PTT→grant→audio→release; SAS mismatch and post-commitment MITM tamper → AUTH_MISMATCH before SAS; abrupt transport loss → clean teardown |
| Security (HS-01/02/05/07) | `SessionSecurityTest` | **bad-tag + huge seq does not poison replay window**; wrong session id dropped; duplicate rejected; concurrent malicious inputs don't race; **concurrent audio has unique monotonic wire seqs**; queue stays bounded; **playback failure → clean rollback (no fake IN_SESSION)**; **handshake/SAS timeouts fire (virtual time)** |

`./gradlew :app:lintDebug` must be clean; `./gradlew :app:assembleAndroidTest`
verifies instrumented sources compile. Run `OpusCodecInstrumentedTest` on an Android
emulator or physical device to verify the packaged native library and codec I/O; host-JVM
tests intentionally do not load Android ABI-specific shared libraries.

## 2. Manual two-device tests (cannot run on GitHub-hosted CI)

### AudioTrack startup-threshold regression

The playback queue ceiling must not be below the threshold Android requires to
start consuming a streaming AudioTrack. The app requests 100 ms native output
capacity. If Android actually grants LOW_LATENCY mode, it caps application-fed
PCM at three 20 ms frames (60 ms); otherwise it keeps up to five frames (100 ms)
to absorb occasional output scheduling/HAL stalls. API 31+ requests a two-frame
start threshold. On API 26–30, the queue ceiling is rounded to a complete 20 ms
PCM frame at or above the effective streaming threshold.

On API 27 and API 36 devices, inspect `playback initialized` for
`actualPerformanceMode`, `bufferFrames`, `capacityFrames`,
`startThresholdFrames`, `queueLimitFrames`, and `bufferResizeResult`.
Confirm ordinary close-range speech no longer triggers
`AudioTrack queue pacing timed out`. The expected queue limit is 4,800 frames
in default mode and 2,880 frames only when LOW_LATENCY is actually active.
Record underruns and audible gaps; absence of the timeout alone does not prove
that playback quality is good.

### Session timing diagnostics

For each connection attempt, retain the lines **session attempt=**,
**session transport connected**, **handshake started**, **session timing**,
and **session ending**. The attempt counter is local to one app process; pair
the two phones' logs by timestamp and selected transport. **setupMs** measures
local elapsed time from attaching a transport to session entry, not radio
round-trip time or mouth-to-ear latency. A value of **-1** means the phase was
not reached or its timestamp was unavailable.


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
9. Repeat cold starts and incoming/outgoing connection attempts. If Android
   reports a formed group before `groupOwnerAddress` is available, verify the
   transport performs bounded connection-info polling (up to 2.5 s) and recovers
   without an immediate socket failure. If the address never appears, verify a
   finite error and confirm a delayed callback cannot resurrect the failed session.

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
4. During repeated discovery cycles, a non-bonded peer not re-seen for 120 s
   disappears from results; a bonded peer remains listed. Seeing a peer again
   refreshes its TTL.

### 2.4 Interruptions & lifecycle
- Incoming phone call during transmission → TX stops instantly (audio focus).
- Plug/unplug wired headset mid-session → route switches, no crash.
- Rotate screen, lock screen, background the app (notification persists),
  foreground again → session survives; Stop from notification ends everything.
- Rapidly start and end playback/session around AudioTrack initialization
  (at least 20 cycles) → teardown wins cleanly; no late playback startup,
  leaked track, stuck audio route, or wake lock after the session returns to IDLE.
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
  limit, and 3-frame initial jitter target. In actual LOW_LATENCY mode the app
  caps pending output PCM at three 20 ms frames (2,880 mono frames); when the
  platform falls back to default mode it allows five frames (4,800 mono frames).
  Inspect `AudioTrack queue pacing` for `waitedFrames`, `maxWaitMs`,
  `maxObservedQueuedFrames`, and the effective `queueLimitFrames`. A queue-pacing timeout
  is a playback failure, not a pass. Compare audibility, perceived gaps,
  underruns, and pacing waits against the prior APK on both transports. This
  bounds queued PCM; it is not a measurement of mouth-to-ear latency.
- Repeat with the screen locked for 5 minutes and verify voice remains smooth;
  the active-session partial CPU wake lock should be acquired and released on
  teardown. Record the battery cost.
- Force sender backpressure in a test build and verify the outbound queue stays
  freshness-first: old pending audio is evicted, recent audio survives, and
  control frames remain queued in order.
- HS-12 regression: during an active PTT session, simulate an RFCOMM write
  watchdog disconnect on one endpoint and a peer-side EOF on the other.
  Both session managers must return to IDLE; microphone capture/playback stop,
  jitter and floor state clear, SAS is discarded, and the error is
  TRANSPORT_LOST. On real phones, repeat this with a naturally occurring write
  stall (do not inject a forced stall during a live/private conversation). For Bluetooth, confirm that a stream
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

## Android 8/9 legacy-device validation (API 26–28)

Run the following before calling the compatibility changes verified. Prefer at
least one API 26/27 phone and one API 28 phone if available; an emulator does
not validate vendor audio HAL behavior.

- First launch with Location permission denied, then granted; confirm the
  rationale/retry route is usable and no discovery call crashes.
- Turn Location Mode off/on while testing Wi-Fi Direct discovery. The app
  should clearly report a platform blocker, not claim peers are being scanned.
- Test Bluetooth Classic discovery and incoming RFCOMM listening on a fresh
  install and with an already-bonded peer. API 26–28 should request the legacy
  location permission for discovery, not Android 12+ Nearby-device permissions.
- Test 10-second PTT in each direction over each available transport. Record
  the capture initialization log and confirm the source is VOICE_COMMUNICATION;
  if the OEM rejects it during build/initialization, MIC_FALLBACK should be
  reported and capture should still work. Confirm audible AEC/noise behavior
  because MIC fallback may sound different.
- Confirm the selected Opus complexity is 4 on API 26–28 and 8 on API 29+,
  with a 32 kbps target and constrained VBR. Compare CPU usage, encode timing,
  intelligibility, clipping, and gaps against the earlier build. This is a
  lower-load real-time profile, not an assumed quality improvement.
- The sender drops audio older than 120 ms before serialization and counts
  it in `droppedAudioTotal`; stale audio must not be replayed after a blocked send.
- Inbound transport flows have no application-side frame backlog: when the
  session consumer slows, the reader must suspend and backpressure the peer
  rather than accumulating up to 64 frames. Verify ordinary floor/control frames
  still arrive and the session does not disconnect due to routine backpressure.
- In `AudioTrack write timing`, compare `nativeAvgMs/nativeMaxMs` (time inside
  native write calls) separately from `avgWaitMs/maxWaitMs` (waiting for output
  capacity). `deadlineTimeouts` should remain zero during ordinary speech.
  In `audio receive timing`, `cadenceIntervals/avgIntervalMs` describe
  within-burst cadence; `interBurstGapsOver250Ms` separates long gaps.
- Lock the screen for 5 minutes and test repeated PTT, stop notification,
  headset insertion/removal, incoming call/audio-focus loss, radio loss, and
  reconnect. Confirm microphone, wake lock, and foreground notification are
  released after leaving the session.

The build can verify code paths and JVM tests, but only an actual API 26–28
phone can validate that device's audio HAL, Bluetooth firmware, Wi-Fi Direct
implementation, battery manager, and speaker routing.

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


### Microphone level and distant speech (physical-device check)

Use the same two phones, transport, room and receiver volume for each run. Hold
PTT and speak the same phrase first at roughly 15–20 cm and then at 60–100 cm
from the transmitting phone; repeat on both devices and both transports. Compare
intelligibility, background-noise pumping, clipping, and the sender's
`capture encode timing` fields: `avgInputRms`, `avgGain`, `maxGain`, and
`nearSilenceFrames`. Also verify quiet-room silence does not become a loud hiss.
This is a comparative manual check, not a calibrated microphone-sensitivity
measurement. A gain increase cannot recover speech whose signal is below the
microphone/noise-suppression floor.
