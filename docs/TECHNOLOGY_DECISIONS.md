# HamSeda Technology Decisions

Decision record for the connectivity, audio, and platform APIs HamSeda uses.
Last reviewed: 2026-10-09. Each section states what the code actually does
— not what the phone hardware might support — with official sources and the
reason the alternative was not chosen.

Mandatory product constraints for every option below: no server, no cloud,
works with no internet, works on phones without Google Play Services where
feasible, secure application-layer peer authentication, low-latency
one-to-one push-to-talk, APK built by GitHub Actions.

---

## 1. Wi-Fi Direct — primary transport (ADOPTED)

- **API family:** `android.net.wifi.p2p.WifiP2pManager` (public Android Wi-Fi
  P2P / Wi-Fi Direct framework API).
- **What the code does** (`transport/WifiDirectTransport.kt`):
  `initialize()` with channel-loss callback → `discoverPeers()` →
  `requestPeers()` → `connect()` with `WifiP2pConfig.Builder` (API 29+;
  legacy constructor + WPS-PBC below that) → group-owner negotiation →
  local TCP socket (`ServerSocket` on the owner, `Socket` to the owner
  address on the client, port 8988) → `FramedSocket` → ECDH handshake →
  SAS confirm → voice.
- **Connection-state signal:** since 2026-10-09 the deprecated
  `EXTRA_NETWORK_INFO`/`NetworkInfo` path is removed; the
  `WIFI_P2P_CONNECTION_CHANGED_ACTION` handler queries
  `requestConnectionInfo()` directly (bounded 5 s) and branches on
  `WifiP2pInfo.groupFormed`. A transport is *connected* only after a valid
  local TCP socket exists; it is *secure/ready* only after the handshake
  and SAS confirmation.
- **What this is NOT:** the API does not pin Wi-Fi 5/6/6E/7. Those are
  radio/PHY capabilities of the device and driver. `WifiP2pManager` API
  level ≠ Wi-Fi standard version. Do not claim a Wi-Fi generation.
- **Sources:**
  - https://developer.android.com/develop/connectivity/wifi/wifi-direct
  - https://developer.android.com/develop/connectivity/wifi/nsd-wifi-direct

### 1a. Wi-Fi Aware / NAN (EVALUATED — not adopted)

- `android.net.wifi.aware.WifiAwareManager` (Neighbor Awareness Networking)
  offers device/service discovery and data paths without a P2P group.
- **Why not:** narrower device support than Wi-Fi Direct (requires
  hardware/driver NAN support; many phones lack it), a second
  discovery+connection state machine to maintain, and no proven benefit
  for this use case — Wi-Fi Direct already provides the needed
  high-bandwidth local socket. Revisit only if device tests show Wi-Fi
  Direct failing on a class of devices where Aware works.
- **Source:** https://developer.android.com/develop/connectivity/wifi/wifi-aware

### 1b. Nearby Connections API (EVALUATED — rejected)

- Google's `com.google.android.gms.nearby.connection` offers P2P with
  simpler UX, but it **requires Google Play Services**.
- **Why not:** violates the no-GMS / works-without-Play-Services
  constraint and the offline-device-coverage goal. Not a drop-in
  substitute; rejected on architecture grounds, not novelty.

### 1c. WifiNetworkSpecifier / Local-Only Hotspot (EVALUATED — rejected)

- These solve *app-requested infrastructure Wi-Fi* scenarios
  (connecting to a known AP), not peer discovery between two equal
  phones. Not substitutes for Wi-Fi Direct P2P.

---

## 2. Bluetooth Classic RFCOMM — fallback transport (ADOPTED)

- **API family:** `android.bluetooth.*` Classic BR/EDR:
  `BluetoothAdapter.startDiscovery()` / `cancelDiscovery()` /
  `bondedDevices`, `BluetoothDevice.createRfcommSocketToServiceRecord(UUID)`,
  `BluetoothAdapter.listenUsingRfcommWithServiceRecord()` +
  `BluetoothServerSocket.accept()`.
- **What this is NOT:** not BLE GATT, not LE Audio. The app does not pin
  "Bluetooth 5.x" — the Bluetooth Core version is a property of the phone
  hardware, Android version, vendor stack and firmware. Android exposes no
  app-level setting to force a Bluetooth version. Do not claim one.
- **Discoverability:** Classic inquiry only finds *discoverable* phones.
  The app requests system-managed discoverability via
  `ACTION_REQUEST_DISCOVERABLE` through the Activity Result API
  (`DiscoveryScreen`), honors the finite duration (300 s max), and never
  enables it silently. Permission (`BLUETOOTH_ADVERTISE`) alone does not
  make a device discoverable.
- **Peer model:** bonded devices, discovered devices (with bounded 120 s
  TTL — stale entries expire), and active connections are separate
  concepts in the data model, UI, and diagnostics.
- **Blocking connect:** `BluetoothSocket.connect()` runs on
  `Dispatchers.IO`; a watchdog *closes the socket* on timeout (closing
  unblocks the pending connect — `withTimeout` alone cannot), guarded by
  an `AtomicReference` so a stale watchdog never closes a newer socket.
- **Sources:**
  - https://developer.android.com/develop/connectivity/bluetooth
  - https://developer.android.com/develop/connectivity/bluetooth/bt-permissions

### 2a. BLE advertising / GATT data channel (EVALUATED — not adopted)

- A BLE GATT channel was considered for discovery/wake-up signaling.
- **Why not for voice:** GATT throughput/latency/MTU behavior is
  unsuitable for a live encrypted voice stream without extensive
  per-device proving (packetization, MTU negotiation, OEM quirks). No
  proven benefit over RFCOMM for this product; adds a second permission
  and lifecycle surface. Not implemented.

### 2b. Bluetooth LE Audio (EVALUATED — not applicable)

- LE Audio is a *system audio profile family* (broadcast/unicast audio to
  headsets), not a generic bidirectional application socket. Android
  exposes no public API to stream app-defined encrypted frames between
  two app instances over LE Audio. Not a candidate; do not claim it.

### 2c. Companion Device Manager (EVALUATED — not adopted)

- `android.companion.CompanionDeviceManager` gives a system-mediated
  association/pairing UX.
- **Why not:** it is an association UI aid, not a replacement for
  general discovery or for RFCOMM data sockets, and it does not remove
  the need for the Classic flow HamSeda already implements. Documented
  here so a future pairing-UX improvement can revisit it.

---

## 3. Broadcast receiver export flags (ADOPTED)

- All context-registered receivers (Bluetooth `ACTION_FOUND` /
  `ACTION_DISCOVERY_FINISHED` / `ACTION_SCAN_MODE_CHANGED`, Wi-Fi P2P
  state/peers/connection broadcasts, audio route broadcasts) use
  `Context.RECEIVER_NOT_EXPORTED` on API 33+.
- **Why:** on API 34+, targeting API 34+, `registerReceiver()` without an
  exported flag throws `SecurityException`. `RECEIVER_NOT_EXPORTED` still
  receives *system* broadcasts — the flag only blocks broadcasts sent by
  *other apps* — so it is both correct and the most restrictive option
  for every receiver in this app.
- **Source:** Android 13/14 behavior changes,
  https://developer.android.com/about/versions/14/behavior-changes-14

---

## 4. Audio routing (ADOPTED)

- Capture/playback use the supported builder APIs: `AudioRecord.Builder`
  / `AudioTrack.Builder` with `VOICE_COMMUNICATION`, 16 kHz mono PCM16,
  20 ms frames. These were already modern; retained.
- **Endpoint selection:** on API 31+, `AudioManager.availableCommunicationDevices`
  + `setCommunicationDevice()` / `clearCommunicationDevice()` explicitly
  choose the route (wired > BT SCO > speaker/earpiece); on API 26–30 the
  legacy `isSpeakerphoneOn` path is kept. `MODE_IN_COMMUNICATION` is set
  in both paths. The deprecated `isWiredHeadsetOn` is no longer used on
  API 31+.
- **Not adopted:** Oboe/AAudio. No measured latency/CPU problem justifies
  the NDK ABI, toolchain, and supply-chain cost; the offline requirement
  is preserved either way. Revisit only with benchmarks.
- **Sources:**
  - https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)
  - https://developer.android.com/reference/android/media/AudioManager#getAvailableCommunicationDevices()

---

## 5. Cryptography (ADOPTED — standard primitives, audited composition)

- Ephemeral ECDH on NIST P-256 → HKDF-SHA-256 key separation (audio key,
  control key, session id, SAS seed) → AES-256-GCM frames with fresh
  96-bit random nonce per frame; the 29-byte frame header is AEAD
  associated data; replay protection updates only after authentication;
  6-digit SAS is compared by both users before audio flows.
- No custom ciphers, no invented protocols, no network/cloud identity.
  Threat model and frame layout: `docs/PROTOCOL.md`, `SECURITY.md`.
- **Vendored codec:** pure-Java Concentus Opus, byte-identical to upstream
  (verified 2026-10-09, see `org/concentus/VENDORED_FROM.md`).

---

## 6. Build toolchain (ADOPTED 2026-10-09)

- AGP 9.4.1 (built-in Kotlin; `org.jetbrains.kotlin.android` removed),
  Kotlin 2.4.21 via buildscript classpath, Gradle 9.8.1, JDK 17,
  Compose BOM 2026.09.00, compileSdk/targetSdk 37, minSdk 26.
- Full version matrix with sources: README "Build matrix".
