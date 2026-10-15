# Phase 1 — Raw Microphone Capture Audit

**Status:** source-level fixes committed; automated CI and physical-device validation pending.  
**Scope:** `AudioRecord` initialization/read → 20 ms PCM frame → `AudioCodec.encode()`. This report does not claim end-to-end audio quality has been verified.

## Confirmed source behavior

- Capture format is 48,000 Hz, mono, signed 16-bit PCM: 960 samples / 1,920 bytes per 20 ms frame.
- The active capture loop does not apply software gain, normalization, filtering, mixing, noise suppression, echo cancellation, or other PCM sample transforms before calling `codec.encode(frame)`.
- Peak amplitude is scanned for the transmit-level UI only; the sample array is not rewritten by that scan.
- The default codec is Opus. Opus is lossy compression, so decoded audio cannot be expected to equal the original PCM bit-for-bit.
- No app-created `AutomaticGainControl`, `NoiseSuppressor`, or `AcousticEchoCanceler` effect is attached by the capture engine. Android/OEM HAL behavior can still differ by device and source.

## Corrections in this phase

1. **Do not overstate `UNPROCESSED` support.** The engine now requests it only when Android's `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` property explicitly advertises support. Otherwise it selects `MIC_FALLBACK`. The selected source and advertised support are logged separately.
2. **Recover from recorder-start failure.** If a recorder initialized as `UNPROCESSED` but `startRecording()` fails, the engine retries with a `MIC_FALLBACK` recorder while holding the capture lifecycle lock. If that also fails, capture fails closed and reports an error.
3. **Do not discard partial positive reads.** The engine now accumulates short positive `AudioRecord.read()` results into the current frame until all 960 samples are present. This avoids silently dropping captured samples between frames.
4. **Keep sample inspection non-mutating.** The peak-level scan is explicitly documented as diagnostics only; it does not modify the PCM frame.
5. **Update validation documentation.** The test plan distinguishes application-level PCM transforms, Android/OEM processing, and Opus compression.

## Findings and remaining limits

| ID | Finding | Status |
|---|---|---|
| P1-AUD-01 | Earlier changelog entries describe a software leveler, but the active current capture path does not apply one. Those entries are historical records, not proof that the current source still applies gain. | Clarified in current changelog and test plan |
| P1-AUD-02 | Unconditional attempts to select `UNPROCESSED` could be mislabeled as raw on devices that do not advertise support. | Fixed |
| P1-AUD-03 | A short positive read was previously discarded instead of being accumulated into the 20 ms frame. | Fixed |
| P1-AUD-04 | If `UNPROCESSED` initialized but failed at start, the engine did not retry `MIC`. | Fixed in source; device validation pending |
| P1-AUD-05 | A source name does not prove that microphone hardware, firmware, or the OEM audio HAL applies no processing. | Platform limitation; must be verified per device |
| P1-AUD-06 | Opus is inherently lossy and is not a bit-exact raw-PCM transport. | Expected behavior; no codec/protocol change in this phase |

## Validation required before marking Phase 1 complete

- [ ] Run `./gradlew :app:testDebugUnitTest`.
- [ ] Run `./gradlew :app:lintDebug`.
- [ ] Build debug and release APKs.
- [ ] Compile instrumented tests; note that compilation is not execution.
- [ ] Install the exact APK on both phones and record SHA-256, model, Android/API version, and selected source.
- [ ] Perform at least ten PTT bursts per transport (Wi-Fi Direct and Bluetooth Classic), including quiet, normal, loud speech, and silence.
- [ ] Confirm immediate capture stop when PTT is released and verify no stale speech is sent afterward.
- [ ] Review sanitized logs for read errors, source fallback, encode timing, queue drops, and transport gaps. Do not log PCM, keys, SAS codes, or personally identifying device details.
- [ ] Compare actual audio quality and latency by ear on both devices. CI cannot validate OEM/HAL processing, intelligibility, or microphone sensitivity.

**No physical-device result is claimed by this report.**