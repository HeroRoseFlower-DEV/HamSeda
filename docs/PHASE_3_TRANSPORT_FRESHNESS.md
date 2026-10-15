# Phase 3 — Transport receive freshness and stale-session isolation

Date: 2026-10-15  
Branch: `debug/phase-3-transport-freshness`  
Base: `debug/phase-2-playback-lifecycle`  
Scope: prevent a completed Wi-Fi Direct read from being published after its socket/generation has become stale.

## Confirmed source-level issue

The Wi-Fi Direct reader checked its operation generation in the loop condition before calling the blocking `readFrame()`, but its frame-result branch emitted the frame without rechecking the generation or socket identity. A read that completed concurrently with Stop/reconnect could therefore pass the loop condition on the old generation and reach the shared incoming-frame flow after the transport had started changing sessions.

Bluetooth RFCOMM already performs a socket-identity check in its incoming-frame publication path. Wi-Fi Direct did not have an equivalent guard.

## Change made

- Added a small, pure `isCurrentIncomingFrame` predicate that requires the expected generation to match, the exact socket object to remain current, and the transport state to be `CONNECTED` or `AUTHENTICATING`.
- Re-check the guard immediately after Wi-Fi Direct receives a complete frame and before publishing it to the session collector.
- If the guard fails, zero the stale frame byte array, log a bounded reason, and stop the obsolete reader rather than feeding old-session data forward.
- Added JVM regression tests for the valid connected case, handshake/authentication state, generation mismatch, socket replacement, and inactive transport states.
- No wire-protocol fields, framing, cryptography, audio PCM, Opus settings, or microphone processing were changed.

## Validation status

- Source patch and deterministic JVM regression tests: committed to this branch.
- GitHub Actions result: pending until the workflow for this branch completes.
- Physical Wi-Fi Direct reconnect/race test: NOT RUN here. CI cannot force the Android framework/socket race on real devices.

## Acceptance tests

1. Start a Wi-Fi Direct session, exchange frames, stop, and immediately reconnect; confirm no old-session frame is accepted after the new attempt begins.
2. Repeat stop/reconnect while the peer is sending continuously, at least 20 times.
3. Repeat with abrupt peer shutdown and with Stop during handshake/authentication.
4. Verify Bluetooth Classic behavior remains unchanged and that both transports still pass the existing handshake/session tests.
5. Record commit SHA, APK SHA-256, phone models/API levels, transport, attempt count, and sanitized logs. Mark any unexecuted case NOT RUN.
