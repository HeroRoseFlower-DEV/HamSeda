# Phase 0 — Debug Baseline and Findings Register

**Review date:** 2026-10-15  
**Scope:** establish a reproducible starting point for the staged debugging effort. This is a source/documentation/CI baseline, not a claim that every source file has already been audited or that radio/audio behavior has been verified on devices.

## Current source baseline

Values checked against the current default-branch source during this review:

| Item | Current source value |
|---|---|
| Android app version | `1.2` |
| `versionCode` | `3` |
| Wire protocol | `5` |
| Minimum Android API | `26` (Android 8.0) |
| Target / compile SDK | `37` |
| Opus target bitrate | `32,000 bps` |
| Native Opus | `libopus` through JNI |
| Native ABIs configured | `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` |
| CI build checks | unit tests, lint, debug/release APK builds, instrumented-test compilation, JNI ABI and 16 KiB ELF alignment checks |

Authoritative source files: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `.github/workflows/android.yml`, `.github/scripts/verify-consistency.sh`, `app/src/main/java/com/hamseda/walkie/audio/AudioEngine.kt`, and `docs/TEST_PLAN.md`.

## Important interpretation limits

- A successful GitHub Actions run verifies only the tasks actually executed by that workflow. `:app:assembleAndroidTest` compiles instrumented tests; it does **not** run them on an emulator or physical device.
- Unit tests cannot establish microphone/HAL behavior, radio discovery reliability, range, audible intelligibility, mouth-to-ear latency, Bluetooth congestion, or screen-off behavior on a real handset.
- The repository's existing baseline report records earlier two-phone log batches, a remaining Bluetooth freshness-drop concern, and an incomplete formal 10-attempt baseline. Those historical observations must not be presented as results for a newer APK without repeating the test.
- The current audio engine selects Android `UNPROCESSED` capture where it initializes successfully and falls back to `MIC`; it does not perform application-level PCM gain/leveling or attach app-created audio effects in the capture path. Android/OEM microphone hardware and audio HAL processing may still vary by device. Opus encoding is still lossy compression, so the transmitted/reconstructed signal is not bit-identical raw PCM.

## Findings register

Severity means priority for the staged debugging effort, not a claim that a security exploit has been demonstrated.

| ID | Priority | Finding | Evidence / impact | Required action | State |
|---|---|---|---|---|---|
| DBG-001 | P1 | Test-plan text contradicts current microphone-capture behavior. | `docs/TEST_PLAN.md` says the software leveler defaults on for installs without a preference and proposes an enabled/disabled comparison. Current `AudioEngine.kt` explicitly says application processing is disabled and passes the captured PCM frame to the codec without modifying samples. This makes the test instructions misleading. | Update the test plan to verify unchanged PCM before encoding and document the distinction between app DSP, Android/OEM processing, and Opus compression. | Fixed in this branch |
| DBG-002 | P1 | The existing baseline report's version fields describe an older source snapshot. | `docs/MAIN_BASELINE_AUDIT.md` names snapshot `80463e4e6ec095707d0d9de935b20f5668a6a3b9`, app `1.1` / code `2`, and protocol v3, while current build/protocol source is app `1.2` / code `3` / protocol v5. The old values may be correct for that historical snapshot but are unsafe if read as current. | Mark the existing report explicitly historical and direct readers to this current baseline record. Do not rewrite historical test results as if they were run on the current version. | Fixed in this branch |
| DBG-003 | P1 | Real-device validation remains a release/debugging gate. | CI compiles instrumented tests but does not execute them on real devices; the baseline report says the formal 10-attempt run is incomplete and Bluetooth queue freshness drops were observed in earlier logs. | Keep physical-device cases marked NOT RUN until evidence is attached; run the two-phone matrix in Phase 1 onward and record APK SHA-256, device/API, transport, attempt outcome, and sanitized logs. | Open — requires devices |
| DBG-004 | P2 | Open GitHub issue search returned no existing issues for this repository. | There is no issue tracker register to rely on for a complete audit. This does not mean there are no bugs. | Use this document's IDs as the initial audit register; open focused issues only when each finding is confirmed and actionable. | Baseline note |
| DBG-005 | P2 | Branch inventory contains several audio, security, and integration branches. | Branch names alone do not establish whether changes are merged, obsolete, or safe to cherry-pick. | Before taking code from any branch, compare its merge base and diff against `main`, inspect tests, and run regression checks. Do not bulk-merge branches as part of the audit. | Open — branch-by-branch review |

## Phase 0 checklist

- [x] Record app/protocol/platform baseline from current source.
- [x] Inspect the primary CI workflow and consistency-check script.
- [x] Inspect manifest permissions and the microphone capture implementation.
- [x] Compare test-plan claims with current capture implementation.
- [x] Check the existing baseline report's version/snapshot scope.
- [x] Search the repository's open issue/PR tracker and list branches.
- [ ] Run the current workflow and collect its actual status/logs.
- [ ] Complete a file-by-file inventory of every production source and test file.
- [ ] Establish the formal two-device baseline with a known APK SHA-256.
- [ ] Confirm every finding against the relevant implementation and regression test before closing it.

## Phase 1 progress — raw capture

- [x] Inspect the active `AudioRecord.read()` → `AudioCodec.encode()` path for application-level PCM mutation.
- [x] Gate `UNPROCESSED` source selection on Android's advertised support property; log the selected source without claiming that this proves the hardware path is unprocessed.
- [x] Accumulate partial positive reads until a complete 20 ms frame is available instead of discarding the partial samples.
- [x] Update the raw-capture test instructions and add a `MIC_FALLBACK` retry when `UNPROCESSED.startRecording()` fails.
- [ ] Run unit tests, lint, debug/release builds, and instrumented-test compilation on the Phase 1 PR.
- [ ] Validate ten or more PTT bursts on both physical phones and record the exact APK SHA-256.
- [ ] Verify real device routing and OEM/HAL processing behavior; not infer it from source selection alone.

The unchecked items require further repository/tool/device work and must not be assumed complete.

## Required evidence format for subsequent phases

For each defect, record: **ID, exact file/symbol, reproduction steps, expected behavior, observed behavior, root cause, smallest safe patch, regression test, CI result, and physical-device result (or NOT RUN)**. Separate facts confirmed by source/tests from hypotheses requiring device evidence. Avoid including raw audio, keys, SAS codes, personal identifiers, or unredacted device addresses in public logs.
