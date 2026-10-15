# Phase 4 — Emulator-executed instrumented tests

## Goal

Execute Android instrumentation tests in GitHub Actions on an Android runtime. Compiling `androidTest` sources is necessary but insufficient: JNI loading, the packaged native library, Android cryptography providers, and activity lifecycle behavior need an Android process.

## CI implementation

The `Build HamSeda APK` workflow now:

1. Installs Android platform-tools, the emulator, and the API 35 Google APIs x86_64 system image.
2. Creates a clean Pixel 2 AVD, starts it headlessly, and waits for `sys.boot_completed=1` with a bounded timeout.
3. Builds and installs the debug APK, then grants only the runtime permissions required to keep the first-run permission gate from blocking unrelated instrumentation checks.
4. Executes `:app:connectedDebugAndroidTest` after the existing unit tests, lint, APK builds, and `:app:assembleAndroidTest`.
5. Collects logcat, emulator properties, emulator console output, and connected-test reports; uploads available evidence even if a test fails.
6. Shuts down the emulator during cleanup.

The workflow's third-party actions remain pinned to full commit SHAs. The emulator boot and instrumentation steps have bounded timeouts.

## Instrumented coverage

- App package identity.
- Manifest declarations for microphone, nearby Wi-Fi, Bluetooth scan/connect, and wake-lock permissions.
- MainActivity create/recreate/close lifecycle.
- Platform ECDH P-256, key derivation, and AES-GCM seal/open.
- Actual packaged JNI library load and runtime libopus version.
- 20 ms Opus encode/decode, signal energy/correlation, DTX, in-band FEC behavior, reset, and invalid-input rejection.

Existing JVM tests continue to cover protocol/session state machines, replay/security invariants, and transport framing. Emulator tests supplement those tests; they do not replace them.

## Acceptance criteria

- [ ] Workflow reaches the emulator test execution step; it must not stop at instrumentation compilation.
- [ ] Every connected instrumentation test passes.
- [ ] Test result XML and useful emulator logs are retained as a workflow artifact.
- [ ] A failed emulator boot or missing test report fails visibly rather than being reported as a successful test.
- [ ] Physical-device tests remain separately tracked and are never auto-marked passed by emulator results.

## Known limits

- API 35 emulator coverage does not validate Android 17/API 37 `ACCESS_LOCAL_NETWORK` runtime enforcement.
- The emulator cannot establish real Wi-Fi Direct or Bluetooth Classic discovery/range/reconnect behavior between two independent phones.
- Emulator tests do not prove microphone hardware/HAL is free of vendor processing, subjective intelligibility, AudioTrack behavior on OEM devices, or mouth-to-ear latency.
- No software audio gain, filtering, normalization, echo cancellation, noise suppression, or other PCM transformation is introduced by this phase.

## Physical two-phone acceptance checklist

For each case, record both phone models, Android/API levels, APK commit/SHA, transport, timestamped logs, outcome, and notes. Keep any unrun case marked **NOT RUN**.

- [ ] Wi-Fi Direct: discovery, connect, handshake/SAS, bidirectional PTT audio.
- [ ] Bluetooth Classic: discovery/pairing/connect, handshake/SAS, bidirectional PTT audio.
- [ ] Repeat Stop → reconnect at least 10 times; confirm no old-session frame/audio reaches the new session.
- [ ] Run a 60-second session followed by at least 10 PTT bursts; note dropouts and intelligibility.
- [ ] Turn the display off during an active session and confirm the expected bounded behavior.
- [ ] Verify permission denial/regrant flows on a real phone, including API 37 local-network permission where available.
- [ ] Exercise speaker, earpiece, wired headset, and Bluetooth audio output only where the device supports them; record actual route diagnostics.
