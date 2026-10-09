# HamSeda Changelog

## 2026-10-09 — Latest-stack modernization (branch `modernize/latest-stack`)

### Toolchain (all versions verified 2026-10-09 from official sources)

- **AGP 8.13.2 → 9.4.1** (latest stable 9.x, Google Maven). Built-in Kotlin
  enabled: the `org.jetbrains.kotlin.android` plugin is removed everywhere
  (AGP 9 hard-fails if applied); `android.kotlinOptions` migrated to the
  `kotlin { compilerOptions { } }` DSL.
  https://developer.android.com/build/migrate-to-built-in-kotlin
- **Kotlin 2.1.20 → 2.4.21** (latest stable, Maven Central) via the root
  buildscript classpath — the official "higher KGP version" mechanism
  (AGP bundles 2.2.10).
- **Gradle wrapper 8.14.6 → 9.8.1** (latest stable, services.gradle.org)
  with the official SHA-256 checksum in `gradle-wrapper.properties`.
- **Compose BOM 2025.04.00 → 2026.09.00** (pins Compose UI 1.12.1,
  Material3 1.4.0; requires AGP 9 + compileSdk 37).
- **androidx.core:core-ktx 1.16.0 → androidx.core:core 1.19.1**
  (core-ktx 1.19.x is an empty shim depending on `androidx.core:core` —
  verified via Google Maven module metadata; coordinate migrated).
- **lifecycle 2.9.1 → 2.11.0, activity-compose 1.10.1 → 1.13.0,
  datastore 1.1.2 → 1.2.1, coroutines 1.10.2 → 1.11.0.**
- JDK stays 17 (Temurin); compileSdk/targetSdk 37; minSdk 26.

### Transport & platform API modernization

- **Wi-Fi Direct:** removed the deprecated `NetworkInfo` /
  `EXTRA_NETWORK_INFO` connection-state path. The
  `WIFI_P2P_CONNECTION_CHANGED_ACTION` handler now queries
  `requestConnectionInfo()` directly (bounded 5 s) and branches on
  `WifiP2pInfo.groupFormed`.
- **Audio routing:** on API 31+, endpoint selection uses
  `availableCommunicationDevices` + `setCommunicationDevice()` /
  `clearCommunicationDevice()` instead of the legacy speakerphone flag;
  the deprecated `isWiredHeadsetOn` is no longer used on API 31+.
  API 26–30 keeps the legacy path.
- **Receivers:** every context-registered receiver now declares
  `RECEIVER_NOT_EXPORTED` on API 33+ (previously missing on the audio
  route receiver — a `SecurityException` crash on API 34+ when targeting
  API 34+). System broadcasts are still received.
- **Bluetooth:** discovered (non-bonded) peers now expire after a bounded
  120 s TTL; bonded peers are unaffected.

### CI & supply chain

- `gradle/actions/setup-gradle` v6.0.1 → **v6.4.0**; the SHA pin is
  corrected to the real commit SHA (the old pin was the annotated tag
  object, not the commit).
- Added `timeout-minutes: 45`, an unsigned `assembleRelease` build gate,
  and Dependabot (Gradle + GitHub Actions, weekly, no auto-merge).
- Verified all four action pins resolve to the pinned commit SHAs.

### Docs & codec

- New `docs/TECHNOLOGY_DECISIONS.md`: why Wi-Fi Direct + Classic RFCOMM
  (and why not BLE/LE Audio/Nearby/Aware), receiver flags, audio routing,
  crypto, toolchain.
- Vendored Concentus verified byte-identical to upstream (all 124 files'
  blob SHAs match); the only newer upstream commit is Go-only.
- README build matrix now carries exact versions + official source links.

### Tests

- 12 new unit tests (`ModernizationTest`): audio routing decisions,
  route detection priority, Bluetooth peer TTL boundaries.

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
