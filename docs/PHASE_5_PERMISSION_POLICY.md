# Phase 5 — Startup permission policy and Android 17 local-network readiness

## Goal

Make the first-run permission policy deterministic and regression-tested across the supported Android API boundaries, with special attention to API 37's `ACCESS_LOCAL_NETWORK` requirement. The main UI must remain behind the existing `StartupPermissionGate` until the Bluetooth discovery/connect, Wi-Fi Direct, local-network (API 37+), and microphone permissions required by the current policy have been granted.

## Implementation

- Extract the version-to-runtime-permission mapping into a pure policy function in `PermissionHelper`; runtime permission state is still checked through Android's permission API.
- Ensure API 37+ adds `ACCESS_LOCAL_NETWORK` to the startup policy while API 26–36 do not request that newer permission.
- Add JVM regression tests at API 26, 29, 31, 33, 36, and 37 boundaries, including a future-version stability check.
- Extend Android instrumentation coverage to assert that the APK declares `ACCESS_LOCAL_NETWORK`.
- Keep `BLUETOOTH_ADVERTISE` out of the startup request; it remains conditional on the user explicitly choosing Bluetooth incoming-wait/discoverable mode.
- Do not add any audio processing, alter the transport protocol, or change cryptographic behavior.

## Validation criteria

- [ ] Unit tests pass, including the new permission-policy boundary tests.
- [ ] Android lint and debug/release APK builds pass.
- [ ] Instrumentation tests pass on the API 35 emulator.
- [ ] The API 37 policy is tested as a pure mapping even when an API 37 emulator is unavailable.
- [ ] Actual API 37 system-dialog behavior, grant/deny/settings recovery, and Wi-Fi Direct TCP connectivity are verified on an API 37 emulator/device before claiming runtime validation.

## Known limits

A pure policy test proves the app's permission list mapping, not the Android 17 platform's enforcement or dialog UX. The API 35 emulator does not prove API 37 local-network access behavior. Physical radio, microphone, audio output, and two-phone end-to-end tests remain separate and must stay **NOT RUN** without device evidence.
