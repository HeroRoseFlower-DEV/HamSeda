# HamSeda Changelog

## 2026-10-15 — Stop sessions before wire-sequence rollover

- Replace silent uint32 sequence wrap with a per-session monotonic counter.
- End the session at `0xFFFF0000`, before counter exhaustion; a new authenticated
  session starts a new sequence space. No wire-format change is made.
- Add unit tests for monotonic allocation, exhaustion, reset and invalid limits.

## 2026-10-15 — Add gated, optional microphone leveler

- Add a stateful PCM leveler with a 5,500 RMS target, maximum 8× gain,
  a 30,000 peak ceiling, immediate gain reduction, and smoothed gain increase.
- Do not amplify frames below RMS 160. Add a persisted setting to disable
  software gain; disabled processing leaves PCM samples unchanged and resets
  applied gain to unity.
- Add synthetic JVM tests for quiet input, noise-floor guard, disabled
  processing, loud transients, full-scale safety and gain release to silence.
- Real-device checks of platform AEC/NS/AGC interaction, microphone noise,
  perceived loudness and far-field intelligibility remain required.

## 2026-10-14 — Adaptive microphone input level
- **Software gain control:** normalize low-level active PCM speech before encoding,
  complementing Android's optional vendor AGC rather than relying on it alone.
- **Bounded gain:** gradual gain increases target about 5,500 RMS samples with an
  8x maximum (+18 dB); sudden loud input reduces gain immediately and output
  peaks are held below 30,000 to leave headroom.
- **Near-silence guard:** frames below the configured RMS floor are not amplified
  to avoid making electronic/background noise dominate quiet moments.
- **Diagnostics:** every 50 captured frames report mean input RMS, mean/max
  applied gain, and near-silence frame count, without logging audio content.
- **Validation caveat:** deterministic JVM tests cover gain-up, silence guard,
  rapid reduction and peak safety. Input sensitivity and intelligibility still
  need confirmation on the two physical phones; digital gain cannot recover
  speech that is buried below the microphone's noise floor.


## 2026-10-14 — Adaptive playback headroom
- **Default AudioTrack mode:** request 100 ms native output capacity and permit
  up to five PCM frames (100 ms) in the app-fed queue when LOW_LATENCY mode is
  unavailable. This provides headroom for the 80–90 ms scheduling/HAL spikes
  observed in the logs.
- **Real low-latency mode:** retain a three-frame (60 ms) cap if the platform
  actually reports LOW_LATENCY. Queue-pacing logs now report the effective cap,
  rather than the maximum profile for every device.
- **Trade-off:** default-mode playback can add up to about 40 ms of queued output
  versus the former 60 ms profile. This is intended to reduce underruns; it is
  not a measured claim of end-to-end latency or audible improvement.

## 2026-10-14 — PTT audio backpressure and timing diagnostics
- **Bluetooth freshness under congestion:** limit queued outbound audio from six
  frames to three (about 60 ms). When a socket write stalls, the queue now keeps
  newer speech instead of allowing a long stale backlog to accumulate.
- **Inbound media backpressure:** remove the 64-frame SharedFlow receive backlog
  from both transports. Socket readers now wait for the session collector to
  process each frame, propagating pressure to the peer's bounded sender queue
  rather than allowing up to about 1.28 seconds of stale frames to pile up.
- **Stale-frame guard:** before encryption/serialization, drop any dequeued
  audio frame already older than 120 ms and count it with the existing freshness
  drops. This prevents a delayed RFCOMM write from replaying old queued speech.
- **Opus transport profile:** target 32 kbps instead of 40 kbps and enable
  constrained VBR to reduce packet bursts. Android API 26–28 use encoder
  complexity 4 (from 6) to leave more CPU headroom; API 29+ retain complexity 8.
- **Receive diagnostics:** average cadence now excludes intervals longer than
  250 ms and reports those separately as inter-burst gaps. The maximum raw
  interval remains visible, so true long stalls are not hidden.
- **Playback writer:** replace potentially long blocking AudioTrack writes with
  bounded non-blocking submissions. Native write-call time and time waiting for
  output capacity are logged independently; a write submission that misses its deadline fails within
  250 ms rather than pinning the audio worker indefinitely.
- **Playback queue timeout:** reduce the output-head pacing timeout from 1 s to
  500 ms so an unhealthy output path does not keep stale speech queued for too long.
- **Validation status:** code and regression-test expectations are updated.
  CI must pass before the change is considered build-verified; audible latency,
  intelligibility, and hardware-specific Bluetooth behavior are not claimed
  without physical-device evidence.

## 2026-10-14 — AudioTrack startup-threshold deadlock fix
- **Playback startup:** the queue previously capped at 60 ms even when an
  AudioTrack's default streaming start threshold was its full 64–80 ms buffer.
  This could prevent the playback head from starting, then end the session when
  the queue wait timed out. Reduce the effective PCM buffer where supported,
  set a 40 ms start threshold on API 31+, and round legacy fallback limits up
  to a full PCM-frame boundary at or above the effective start threshold.
- **Validation status:** unit tests cover queue-limit calculation. The new APK
  still needs fresh playback tests on API 27 and API 36; a successful build is
  not proof of audible output or acceptable latency.

## 2026-10-14 — Real-time audio backpressure hardening
- **Android 8/9 audio compatibility:** use Opus encoder complexity 6 on API 26–28
  and complexity 8 on API 29+ to lower CPU work, retaining 48 kHz mono, 20 ms
  frames, and 40 kbps. If a legacy OEM cannot construct/initialize
  VOICE_COMMUNICATION capture, try MIC once as a compatibility fallback.

- **Session lifecycle diagnostics:** correlate connection attempts with a
  process-local attempt counter and monotonic elapsed durations for transport
  connection, handshake start, successful session entry, and teardown. Logs
  deliberately omit peer names, addresses, SAS codes, key material, and audio.


- **Bluetooth RFCOMM write watchdog:** close the matching active socket if a
  blocking stream write remains stalled for 350 ms. This bounds how long the
  sender can remain stuck behind a bad write; the session must reconnect and
  authenticate again after a triggered timeout.
- **Cancellation cleanup:** cancelling a send also initiates cleanup for that
  exact socket instead of leaving a blocking stream write orphaned.
- **Race regression tests:** added deterministic completion/timeout/abort cases
  and a 100-iteration concurrent completion-versus-timeout test.
- **Playback backlog bound (preceding commit):** cap application-fed PCM in
  AudioTrack at three 20 ms frames and report pacing waits separately from
  native write timing.
- **HS-12 transport-loss teardown test:** exercise a secure, active PTT session
  and verify both sides stop capture/playback, clear floor/SAS state, and return
  to IDLE after a socket loss. This tests session recovery, not radio behavior.
- **Validation caveat:** the 350 ms threshold is an experiment for real-device
  validation, not an end-to-end audio latency measurement or proof of improved
  speech quality.

## 2026-10-09 — Security hardening & modernization

### Fixed (HS-01…HS-11, with regression tests)

- **HS-01 (HIGH):** Inbound frames are now AEAD-authenticated *before* the
  replay window, liveness timestamp, or floor state may change. Previously
  `replay.accept()` and `lastPeerSeen` updated before tag verification, and
  out-of-floor audio was dropped without authentication.
- **HS-02 (HIGH):** All outbound messages now flow through a single bounded
  channel (capacity 256) with one consumer that allocates sequences and
  writes in FIFO order. Eliminates the `sendSeq++` race and the per-frame
  coroutine spawn. Audio drops (counted) on overflow; control is never
  expected to overflow.
- **HS-03 (MEDIUM):** Teardown is now one explicit, idempotent, ordered
  routine guarded by a mutex: stop sender → stop audio → cancel jobs →
  wipe keys → disconnect transport → reset state. `close()` (from
  `Service.onDestroy`) does synchronous critical cleanup (audio, keys)
  without depending on the cancelling service scope.
- **HS-04 (MEDIUM):** `FrameException` from `FramedSocket.readFrame()` is now
  caught in both transport reader loops and routed through the common
  disconnect path (previously stranded the transport in CONNECTED).
- **HS-05 (MEDIUM):** `enterSession()` now checks the `startPlayback()`
  result; on failure the session rolls back with `PLAYBACK_UNAVAILABLE`
  instead of a fake IN_SESSION.
- **HS-06 (MEDIUM):** Bluetooth `connect()` now runs on `Dispatchers.IO`
  with a watchdog that *closes* the socket on timeout (which unblocks the
  blocking connect — `withTimeout` alone cannot). An `AtomicReference`
  prevents closing a newer socket.
- **HS-07 (MEDIUM):** Handshake (30 s) and SAS-confirm (120 s) deadlines
  added; both fail closed. A monotonic `sessionGeneration` guards async
  callbacks against stale-session mutation.
- **HS-08 (MEDIUM/LOW):** Wall-clock timestamp validation removed. Two
  offline phones with different manual clocks can now connect; anti-replay
  relies on AEAD + sequences.
- **HS-09 (MEDIUM/LOW):** `AudioRecord.read()` errors are classified:
  `ERROR_DEAD_OBJECT`/invalid-op are fatal (stop, no spin); transient
  errors back off with a bounded retry count (10).
- **HS-10 (LOW):** Jitter buffer validates staleness/duplicates *before*
  evicting; a stale frame at a full buffer no longer evicts a useful frame.
- **HS-11 (LOW):** Bonded-device cache is always reconciled; a successfully
  empty bonded set clears the UI (only permission failure keeps the old
  cache).

### Security / privacy

- Diagnostic log redacts Bluetooth/Wi-Fi MAC addresses before storage (M1).
- `networkSecurityConfig` explicitly disables cleartext traffic (L13).
- GitHub Actions pinned to commit SHAs (M2); `contents: read`,
  concurrency group, APK SHA-256 checksum published (Phase 8).

### Toolchain (verified 2026-10-09)

- AGP 8.10.1 → **8.13.2** (latest 8.x, Google Maven)
- Kotlin 2.0.20 → **2.1.20**
- Compose BOM 2024.10.00 → **2025.04.00**
- core-ktx 1.13.1 → 1.16.0, lifecycle 2.8.6 → 2.9.1,
  activity-compose 1.9.2 → 1.10.1, datastore 1.1.1 → 1.1.2,
  coroutines 1.8.1 → 1.10.2
- Gradle wrapper stays 8.14.6 (satisfies AGP 8.13.2 ≥ 8.13); JDK 17.
- AGP 9.x / Kotlin 2.4.x intentionally not adopted (KGP model change;
  migration not yet validated).

### Tests

- 75 JVM unit tests (was 64): new `SessionSecurityTest` (HS-01/02/05/07),
  new HS-10 jitter-buffer cases, HS-08 clock-skew cases updated.
- All pass; lint clean (see CI).

### Known limitations (not verified)

- No physical-device testing in this pass: Bluetooth/Wi-Fi Direct
  interop, SAS UX, PTT latency, and audio routing still require two real
  phones (see `docs/TEST_PLAN.md` §2).
- No independent security audit; external review recommended.
