# Phase 2 — AudioTrack playback and latency

Date: 2026-10-15  
Branch: debug/phase-2-playback-lifecycle  
Base: debug/phase-1-raw-capture (stacked on the open Phase 1 proposal)  
Scope: playback worker lifecycle, AudioTrack failure handling, output-device change observation, and deterministic queue accounting.

## Source findings and changes

1. Playback worker could terminate unexpectedly without clearing the running flag. The playback loop had a finally block for temporary PCM and jitter-buffer cleanup, but an exception escaping from a platform/codec call could leave playbackRunning=true after the thread died. The worker now catches ordinary exceptions, logs a bounded diagnostic, reports playback failure, and clears the running state in finally.
2. Teardown could release AudioTrack while the worker was still alive. Previously teardown joined for 500 ms and released the track regardless of whether the worker had exited. The updated path interrupts pacing sleeps, waits for the worker, and only uses forced stop/release if it is still alive. It keeps the worker reference if termination is still pending, so a subsequent start cannot overlap the old worker's buffer reset.
3. A failed worker could leave a retained track before the next start. Startup now refuses to overlap a live worker and cleans up a retained track after the prior worker has ended before creating a new track.
4. Output device changes are not always delivered as headset-plug broadcasts. An AudioDeviceCallback now observes device additions/removals and re-evaluates the route. The callback is unregistered in release().
5. Queue accounting coverage. Added deterministic tests proving that partial non-blocking writes sum as PCM sample frames and repeated polls of the same playback head do not subtract consumed frames twice.

## Existing protections retained

- AudioTrack MODE_STREAM, 48 kHz mono PCM16 and 20 ms / 960-sample frames.
- Requests low-latency mode only when the native output rate matches the stream rate; checks the mode Android actually grants.
- Buffer sizing and start-threshold-aware queue ceiling remain bounded; three frames for actual low-latency mode, five frames otherwise.
- Non-blocking writes, a bounded submission deadline, queue-pacing timeout, underrun diagnostics, jitter buffering and bounded packet-loss concealment remain in place.
- Route/audio mode restoration remains conditional on capture/playback becoming idle.

## Validation status

- Source changes: applied to this branch.
- Unit tests / lint / debug APK / release APK / instrumented-test compilation: pending this branch's GitHub Actions result.
- Instrumented execution on a device/emulator: not performed by source edits or CI compilation alone.
- Two-phone Wi-Fi Direct and Bluetooth Classic playback tests: NOT RUN here.
- End-to-end latency: NOT MEASURED. AudioTrack.write() duration and queue depth are diagnostic signals, not mouth-to-ear latency.

## Physical-device acceptance checklist

- [ ] At least 20 rapid start/stop/restart cycles without crash, leaked track, stuck route or worker overlap.
- [ ] At least 10 PTT bursts over Wi-Fi Direct and Bluetooth Classic; no old-session audio after stop/reconnect.
- [ ] Plug/unplug wired output and connect/disconnect Bluetooth output during playback; route logs agree with actual audible output.
- [ ] Record actualPerformanceMode, sample rate, buffer/capacity, start threshold, queue limit, underruns, queue pacing, and write timing.
- [ ] Measure end-to-end latency separately using a repeatable physical measurement and record device/API/route.
