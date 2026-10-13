# Main Branch Baseline Audit and Two-Device Validation

**Status:** source review completed; first physical-device run received; reconnect failures found and targeted fixes committed. Formal 10-attempt baseline remains incomplete.  
**Branch reviewed:** `main`  
**Source snapshot at review start:** `80463e4e6ec095707d0d9de935b20f5668a6a3b9`  
**App version in source:** `1.1` (`versionCode=2`)  
**Wire protocol:** v3  
**Purpose:** make the first validation milestone reproducible and prevent build success from being mistaken for real-radio/audio validation.

> This document does not claim that two-device tests were performed. A repository/CI review cannot verify OEM radio behavior, microphone capture, audio routing, audible quality, range, or end-to-end latency. Fill in the result tables only after running the APK on physical devices.

## 1. Review summary

### Confirmed in the reviewed source and documentation

- The app has separate Wi-Fi Direct and Bluetooth Classic transports and a shared length-prefixed frame layer.
- The session layer implements ephemeral P-256 ECDH, HKDF-SHA-256, AES-GCM-protected session traffic, transcript-bound key confirmation, six-digit short-authentication-string confirmation, replay protection, half-duplex floor control, liveness checks, and bounded handshake/SAS deadlines.
- The audio path uses 48 kHz mono PCM internally and native libopus; the playback path uses a jitter buffer, bounded frame queues, and packet-loss concealment.
- Logging is intended for diagnostics and the security documentation states that keys, audio content, and pairing material are not logged. Inspect the actual log bundle before sharing it anyway.
- Unit-test coverage includes protocol/frame validation, cryptographic primitives, replay behavior, handshake/session transitions, queue bounds, jitter buffering, settings, and audio routing. An Android instrumented test exists for loading and exercising native Opus.
- GitHub Actions runs unit tests, Android lint, a debug APK build, instrumented-test compilation, and checks the packaged native JNI library for the configured ABIs and ELF page alignment.

### Validation gaps found

1. **No physical-device evidence is recorded in CI.** The workflow compiles the instrumented tests but does not run them against two real phones. The workflow cannot establish whether Wi-Fi Direct discovery, Bluetooth RFCOMM, audio focus, OEM routing, or screen-off operation works on a particular device pair.
2. **No completed device baseline report is present.** The existing test plan describes manual tests, but a versioned result sheet with device/build identity, repeat counts, observations, and disposition is needed to compare builds without relying on memory.
3. **Audio performance still requires measurement.** Source diagnostics include initialization information and jitter counters, but CI does not measure mouth-to-ear latency or subjective intelligibility. Do not infer those measurements from codec settings, buffer configuration, or a successful build.
4. **Radio range is not a source-code-only property.** Record distances, environment, line of sight, interruptions, and recovery behavior. Do not claim a range improvement without comparable device measurements.
5. **The security document explicitly says there has not been an independent security audit.** Existing automated tests are valuable evidence, but must not be presented as independent certification.

These are evidence/validation gaps; they are not by themselves proof that the implementation is defective. The source review does not justify blind changes to transport timing, codec parameters, or jitter-buffer thresholds before a baseline is collected.

## 2. Build identity — mandatory for every run

1. Select a **successful** GitHub Actions run for the exact commit being tested:
   [HamSeda Actions](https://github.com/HeroRoseFlower-DEV/HamSeda/actions).
2. Download `HamSeda-debug-apk` and keep `app-debug.apk` with its `.sha256` file.
3. Verify the APK hash on a trusted computer before installing. On a shell with `sha256sum`:
   ```sh
   sha256sum -c app-debug.apk.sha256
   ```
   Run the command from the directory containing the APK and checksum file; if the checksum file uses a different filename, adjust the path or inspect the recorded hash explicitly.
4. Install the **same APK bytes** on both phones. Record the workflow run URL, source commit SHA, APK SHA-256, app version, and protocol version. Do not mix builds in one test pair.
5. Disable mobile data and avoid any Wi-Fi access point/internet route during offline tests. Keep Wi-Fi enabled for Wi-Fi Direct and Bluetooth enabled for Bluetooth Classic. Do not assume airplane mode behaves identically on every OEM; record which radios were manually re-enabled.

## 3. Test environment record

Complete one copy of this section for every device pair.

| Field | Phone A | Phone B |
|---|---|---|
| Manufacturer / model | NOT RECORDED | NOT RECORDED |
| Android version / API level | NOT RECORDED | NOT RECORDED |
| Security patch date | NOT RECORDED | NOT RECORDED |
| App version / versionCode | NOT RECORDED | NOT RECORDED |
| Source commit SHA | NOT RECORDED | NOT RECORDED |
| APK SHA-256 | NOT RECORDED | NOT RECORDED |
| Battery saver / vendor app autostart policy | NOT RECORDED | NOT RECORDED |
| Available audio outputs tested | NOT RECORDED | NOT RECORDED |
| Wi-Fi / Bluetooth firmware notes, if exposed | NOT RECORDED | NOT RECORDED |

**Initial execution status:** Superseded by the first user-supplied device logs in Section 9. Exact APK identity and formal repeat counts are still not recorded.

## 4. Test procedure and release gates

Use a fresh app process before each independent connection attempt. Alternate which phone initiates the connection. For each row, record PASS, FAIL, BLOCKED, or NOT RUN; include the corresponding sanitized log excerpt and a short observation.

### A. Start-up, permission, and discovery

| ID | Procedure | Pass criteria | Result |
|---|---|---|---|
| A1 | Cold-launch the app on each phone. | No crash; startup screen reaches a usable state. | NOT RUN |
| A2 | Grant required permissions on first launch. | Only expected system permission prompts appear; discovery screen explains any remaining blocker. | NOT RUN |
| A3 | Deny a required permission, then retry or open system settings and grant it. | Clear recovery path; no crash or endless prompt loop. | NOT RUN |
| A4 | Test Wi-Fi Direct with system Location Mode both enabled and disabled where relevant to that Android version. | The app reports the actual blocker accurately; no false claim that discovery started. | NOT RUN |
| A5 | Test Bluetooth discovery and the explicit incoming-listen/discoverability flow. | Candidate phone appears; ordinary audio accessories are not presented as phone peers. | NOT RUN |

### B. Connection and authenticated session

Run **10 attempts per transport per device pair**, alternating the initiator (Phone A then Phone B). Preserve failed attempts as well as successful ones.

| ID | Procedure | Pass criteria | Result |
|---|---|---|---|
| B1 | Connect using Wi-Fi Direct only. | Discovery, socket connection, protocol handshake, SAS display, and mutual confirmation complete without a stuck state. | NOT RUN |
| B2 | Repeat using Bluetooth Classic only. | RFCOMM connection and the same authenticated session process complete. | NOT RUN |
| B3 | Compare the six-digit SAS values, then confirm on both phones. | Audio remains unavailable until both users confirm the same displayed code. | NOT RUN |
| B4 | Select “codes differ” on one phone. | Session aborts; neither phone transmits or plays session audio. | NOT RUN |
| B5 | Stop/leave during discovery, connection, handshake, and SAS confirmation. | The old attempt does not resurrect later; UI and transport return to a usable state. | NOT RUN |
| B6 | Repeat connection after a successful session has ended. | Fresh handshake and SAS flow; no stale session keys or prior floor state are reused. | NOT RUN |

**Gate:** for a release candidate, target 10/10 successful baseline connections for each transport on each claimed-supported device pair. If any attempt fails, do not hide it: record the failure stage, error reason, retry outcome, and whether a known OEM limitation explains it.

### C. Voice and audio route

For each transport, perform five PTT transmissions in each direction, first with the phones close together and then at a recorded distance.

| ID | Procedure | Pass criteria | Result |
|---|---|---|---|
| C1 | Hold PTT and speak for 10 seconds, then release. Repeat five times in each direction. | Correct direction, intelligible speech, no stuck microphone, and PTT release stops capture promptly. | NOT RUN |
| C2 | While one side owns the floor, press PTT on the other. | The second side is denied/busy; the first side retains the floor. | NOT RUN |
| C3 | Switch between speaker and any available earpiece/wired/Bluetooth audio route during a session. | The route state matches audible output; no crash, runaway volume, or permanently wrong route after teardown. | NOT RUN |
| C4 | Attach and remove a wired headset during a session, if supported by the device. | Route changes are handled without a crash or stuck playback. | NOT RUN |
| C5 | Trigger audio focus loss (for example, a real incoming cellular call where possible). | Transmission stops safely and the session/audio route recovers or ends cleanly. | NOT RUN |
| C6 | Listen for clipping, robotic artifacts, gaps, echoes, excessive noise, and delayed/replayed old speech. | Each issue is described and tied to transport/device/route; do not mark audio “good” from connectivity alone. | NOT RUN |

For an initial, comparable listening test, use the same short speech passage and speaker distance, record the output route and volume, and compare both APKs on the same hardware. A successful Opus encode/decode unit test is not evidence of good microphone input or audible playback on an OEM device.

### D. Background, interruptions, and cleanup

| ID | Procedure | Pass criteria | Result |
|---|---|---|---|
| D1 | Maintain a 5-minute session with the screen locked. | Voice continues; foreground-service notification remains; no unexplained disconnect. | NOT RUN |
| D2 | Keep a 10-minute session active on each transport. | No crash, runaway queue delay, stuck PTT, or unrecoverable loss of the session. Record battery/thermal observations. | NOT RUN |
| D3 | Disable the active radio during speech, then restore it. | Both sides leave the stale session safely; rejoin requires a new authenticated session. Record whether manual restart is needed. | NOT RUN |
| D4 | Stop via the UI and via the foreground notification. | Microphone, sockets, wake lock, audio focus/effects, and foreground notification are released. | NOT RUN |
| D5 | Background/foreground the app and rotate the screen where applicable. | Session state remains coherent; the UI does not show a false active session after teardown. | NOT RUN |

### E. Range and repeatability

Do this only after A–D pass. For both transports, test at measured distances (for example: 1 m, 5 m, and 10 m where safe and practical), recording indoor/outdoor, walls, line of sight, other nearby radios, and orientation. Repeat each distance three times. Do not compare tests done in materially different environments as if they were a controlled experiment.

Record: connection success, time to establish, audible gaps, disconnects, recovery behavior, approximate distance, and device orientation. Range numbers must describe the tested pair and environment, not a universal HamSeda capability.

## 5. Capturing diagnostics safely

- Export the app's diagnostic log immediately after an attempt and associate it with the run ID, transport, and test ID (for example `B2-Attempt-03`).
- Keep the original private. Before posting anything publicly, inspect and sanitize it again; do not rely solely on automatic redaction.
- Never share APK signing secrets, keystores, private keys, session keys, audio recordings of private conversations, SAS codes from a real session, or unredacted device identifiers.
- Record exact local time and both phones' displayed app/transport states around the failure.
- Do not enable additional logging of audio contents, crypto key material, or full packet payloads to diagnose latency.

## 6. Performance measurements

Do not invent values where no reliable measurement exists. Record each measurement along with method, device pair, transport, audio route, and repetitions.

| Measurement | Method | Result |
|---|---|---|
| Discovery duration | Stopwatch from tapping Scan/Wait to the peer being selectable; 10 attempts. | NOT MEASURED |
| Connection + handshake duration | Stopwatch/log timestamps from connect action to SAS screen; 10 attempts. | NOT MEASURED |
| SAS confirmation to first audible output | Synchronized stopwatch/video/audio recording or a repeatable external measurement setup. | NOT MEASURED |
| Mouth-to-ear latency | A repeatable acoustic method with known recording frame rate/sample rate; state uncertainty. | NOT MEASURED |
| Audio gaps / late speech | Count perceived interruptions in a fixed speech passage and correlate with sanitized jitter diagnostics. | NOT MEASURED |
| Reconnect duration | Time from restoring the radio to a usable, newly authenticated session. | NOT MEASURED |
| Battery/thermal behavior | Record start/end battery level and device thermal state during a fixed 10-minute run. | NOT MEASURED |

**Important distinction:** a network ping/round-trip measurement, if later added, is not the same as mouth-to-ear audio latency. Do not use one as a substitute for the other.

## 7. Final result sheet

Duplicate this block per device pair and software build.

- Build workflow URL:
- Source commit SHA:
- APK SHA-256:
- Device pair:
- Date/time and test operator:
- Transports tested:
- Passed / failed / blocked / not-run case IDs:
- Successful connections / attempts, per transport:
- Crashes or ANRs:
- Worst observed audio issue:
- Measured discovery and connection times:
- Measured mouth-to-ear latency and method:
- Range conditions and results:
- Sanitized diagnostic-log references:
- Known OEM-specific behavior:
- Release recommendation: **NOT DECIDED — await actual results**

## 8. Exit criteria for Priority 1

Priority 1 is complete only when:

1. Both named physical phones and their Android versions are recorded.
2. The exact tested APK is tied to a successful CI workflow, commit SHA, and checksum.
3. The Wi-Fi Direct and Bluetooth test cases have explicit outcomes and failure evidence.
4. Voice, SAS mismatch, permission recovery, interruption, screen-off, and teardown tests have been run.
5. At least one comparable audio-performance baseline is recorded for each transport; any missing measurement remains explicitly marked missing.
6. All failed cases have a disposition (fixed and retested, known limitation, or release-blocking).
7. The report is updated with observed facts only. Never change NOT RUN to PASS based on source inspection or CI compilation alone.


## 9. First physical-device run — user-supplied logs

**Evidence received:** two device logs pasted into the conversation. These are observations from that run, not a statement that all release-gate tests passed.

### Device/build identity available

| Field | Phone A (user's phone) | Phone B |
|---|---|---|
| Android API level from log | API 36 | API 27 |
| App version / versionCode | NOT PROVIDED | NOT PROVIDED |
| Source commit SHA used to build APK | NOT PROVIDED | NOT PROVIDED |
| APK SHA-256 | NOT PROVIDED | NOT PROVIDED |
| Manufacturer / model | NOT encoded in the supplied excerpt | NOT encoded in the supplied excerpt |

The absence of the APK identity means these results cannot yet be attributed conclusively to the exact post-fix main build.

### Observed results

| Test area | Observed evidence | Assessment |
|---|---|---|
| First Wi-Fi Direct session | Both devices establish P2P sockets, complete protocol v3 HELLO/key exchange, and log `session established (codec=0)`. | **PASS once** for establishing a session; audio intelligibility and performance were not measured objectively. |
| First Bluetooth Classic session | RFCOMM socket establishes on both sides and both log `session established (codec=0)`. | **PASS once** for establishing a session; not a 10-attempt baseline. |
| Audio route / playback | Phone A logs `performanceMode=0, bufferFrames=3072`; Phone B logs `performanceMode=0, bufferFrames=3848`, at 48 kHz. | Output mode is normal rather than low-latency. Nominal configured buffer durations are ~64 ms and ~80 ms respectively; these are not mouth-to-ear latency measurements. |
| Bluetooth transmit queue | Phone B reports `realtime audio freshness drops=50, pendingAudio=6, pendingMessages=7` during the Bluetooth session. | **Needs investigation.** At least 50 audio frames were dropped by the freshness policy by the time this cumulative diagnostic fired. The log does not isolate whether the bottleneck is codec work, scheduling, or RFCOMM backpressure. |
| Wi-Fi Direct reconnect — handshake | Phone A logs `received frame type=2 before HELLO`; type 2 is KEY_EXCHANGE. Phone B logs an incoming HELLO and starts its own handshake within a few milliseconds. | **FAIL observed.** Strongly consistent with concurrent handshake startup/inbound-frame processing allowing KEY_EXCHANGE to be queued ahead of the local HELLO. A serialization fix and stress regression test were committed to main. |
| Wi-Fi Direct reconnect — owner listener | Phone A later logs `BindException: bind failed: EADDRINUSE` on the fixed P2P TCP port 8988. | **FAIL observed.** The listener failed to bind. The code now enables address reuse only on the specific P2P interface, keeps a reference available for teardown before binding, and retries transient bind contention within a short bounded window. |
| Wi-Fi Direct reconnect — client side | Phone B logs repeated connection attempts and finally a timeout connecting to the group-owner address on port 8988. | **FAIL observed.** This is consistent with no listener becoming available on the owner during that attempt; it may be the downstream consequence of the owner-side bind failure. |
| Session cleanup | Both devices log session teardown and release of the partial CPU wake lock. | **Observed on these paths.** Separate background, interruption, and long-duration cleanup cases remain untested. |

### Important interpretation notes

- The first Wi-Fi Direct and Bluetooth sessions did establish. The failures occurred on later Wi-Fi Direct reconnection attempts; they should not be reported as if the transports never worked.
- The repeated `capture initialized` messages align with multiple capture-start events (for example, separate PTT transmissions) and are not alone proof of a leak. Correlate them with actual PTT press/release events and verify no microphone remains active after release.
- The reported audio queue drop count is cumulative. It is useful evidence of congestion but does not directly measure loss on the radio, end-to-end delay, or speech intelligibility.
- The `performanceMode=0` log proves the created AudioTrack is not reporting Android's low-latency mode. The configured buffers are comparatively large, but we should not reduce them blindly: the native output rate differs on some devices and must be measured before choosing a safe buffering/resampling strategy.
- The API 27 device uses the legacy Wi-Fi Direct discovery fallback; results on this pair should not be generalized to all supported Android versions.

### Targeted main-branch fixes following these logs

1. **Handshake ordering:** serialize `beginHandshake()` with inbound frame processing so the local HELLO is queued before a racing inbound HELLO can enqueue KEY_EXCHANGE; add a dispatcher-concurrency regression test.
2. **P2P listener rebinding:** use `SO_REUSEADDR` while remaining bound to the specific P2P group-owner address, check the operation generation around bind, publish the server socket before the blocking bind so teardown can close it, and retry transient `EADDRINUSE` for at most 2.5 seconds.
3. **Verification pending:** run the latest main-branch CI and repeat the same two-device reconnect scenario. These code changes have not yet been proven to fix the physical-device symptom until the rebuilt APK is tested on the same devices.

### Remaining release gates

- The exact new APK SHA-256 must be recorded on both phones.
- Repeat at least 10 Wi-Fi Direct connections (alternating initiator) and 10 Bluetooth connections on the same pair.
- Re-test repeated PTT and confirm no freshness-drop growth under normal close-range Bluetooth conditions; if drops recur, add or use timing diagnostics around encode, queue wait, and socket write.
- Measure audio quality and mouth-to-ear latency with a repeatable method, separately for both routes. The values in these logs are not latency results.
- Do not mark the baseline complete until the post-fix build is physically tested.
