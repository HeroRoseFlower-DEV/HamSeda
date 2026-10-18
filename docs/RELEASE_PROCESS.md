# HamSeda official release process

This document describes the release gate. A successful CI build is not, by itself, approval to publish a production release.

## Release artifacts

A successful push of a version tag (v1.2 or v1.2.0, matching the app's versionName) builds and publishes:
- A signed release APK for direct installation.
- A signed Android App Bundle (AAB) for stores that accept bundles.
- SHA256SUMS, containing SHA-256 digests for both downloadable artifacts.

The workflow verifies the APK signature with Android's apksigner, verifies the AAB signature with jarsigner, and checks the generated digests before publishing. The tag must match app/build.gradle.kts versionName; versionCode must be a positive integer and must be increased for every subsequent public release. Never reuse a versionCode for a newer release.

## One-time signing setup

Create a dedicated Android upload/release keystore on a trusted local machine. Keep a secure offline backup. Do not commit the keystore, passwords, exported private keys, or signing material to the repository, issues, pull requests, logs, or release assets.

Add these five repository Actions secrets under Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| HAMSEDA_KEYSTORE_BASE64 | Base64 encoding of the keystore file's bytes (single line) |
| HAMSEDA_KEYSTORE_PASSWORD | Keystore password |
| HAMSEDA_KEY_ALIAS | Alias of the signing key |
| HAMSEDA_KEY_PASSWORD | Key password |
| HAMSEDA_CERT_SHA256 | (Optional but recommended) SHA-256 fingerprint of the release signing certificate, hex without colons. Enables certificate pinning in the release workflow: a mismatched fingerprint fails the release. If unset, pinning is skipped with a warning. |

Get the certificate fingerprint from your keystore with: `keytool -list -v -keystore hamseda-release.jks -alias <alias> | grep SHA256`. Copy only the hex fingerprint (no colons, no spaces) into the secret field.

Encode the keystore locally without uploading it anywhere else; on Linux, use base64 -w 0 hamseda-release.jks to print a single-line value. Copy that output directly into the secret field. Do not paste it into a chat or a repository file. On platforms without -w, use the platform's equivalent single-line Base64 command.

The workflow decodes the keystore into the ephemeral runner's temporary directory with restrictive file permissions, passes passwords only through environment variables, and deletes the temporary keystore in an always() cleanup step. Do not enable shell tracing (set -x) around secrets. GitHub Actions secrets are not available to workflows triggered from untrusted forks; this release workflow is tag-triggered and requires the repository secrets to be configured.

## Release checklist

Before creating a public tag:

1. Review and merge the intended code changes through pull requests. Do not tag a stacked, unreviewed debug branch.
2. Ensure all required CI jobs are green on the exact commit to be tagged: unit tests, lint, debug/release compilation, native ABI and ELF page-alignment checks, and connected instrumentation tests.
3. Complete the two-physical-phone acceptance tests in docs/TEST_PLAN.md, including Wi-Fi Direct, Bluetooth Classic, secure pairing/reconnect, microphone capture, playback, route changes, and background/foreground lifecycle. Record Android versions and device models.
4. Validate the relevant platform-specific permission behavior on API 37 / Android 17 before claiming that gate is passed. The API 35 emulator suite does not prove API 37 runtime enforcement.
5. Review CHANGELOG.md and fill in docs/RELEASE_NOTES_TEMPLATE.md by hand (the release workflow publishes it via --notes-file). Document known limitations; do not claim measured latency, guaranteed range, or independent security certification without evidence.
6. Increase versionCode and set versionName in app/build.gradle.kts; keep the version name consistent with the release tag. Commit this change before tagging.
7. Confirm all five Actions secrets are configured (the four signing secrets, plus HAMSEDA_CERT_SHA256 so certificate pinning actually runs instead of warn-and-skip). Create and push an annotated version tag pointing at the reviewed commit, for example v1.2 for versionName = "1.2".
8. Wait for the Release workflow to finish. Confirm the APK and AAB signatures and SHA256SUMS are attached to the GitHub Release.
9. Download the published APK and checksum manifest independently, run sha256sum --check SHA256SUMS, install on a clean device, and verify that an update from the previous signed build installs without signature mismatch.

## Compatibility matrix policy

Maintain the tested matrix in docs/TEST_PLAN.md. Mark each cell as tested, build-only, or not tested; do not infer physical-device compatibility from minSdk, compilation, or emulator success.

| Platform / path | Evidence required before a compatibility claim |
|---|---|
| API 26–28 | Install/launch, legacy permission flow, Wi-Fi Direct/Bluetooth, microphone and playback |
| API 29–30 | Location-based discovery permission flow and audio foreground behavior |
| API 31–32 | Nearby Bluetooth permission flow, discovery and audio |
| API 33–35 | Nearby Wi-Fi, notification/microphone behavior and connected instrumentation |
| API 36 | Install, permission flow and radio/audio smoke tests on an actual API 36 device |
| API 37+ | Local-network permission grant/deny/retry/settings flow and actual Wi-Fi Direct local-socket enforcement |
| Multiple OEMs | At least one additional vendor where practical; record model and build fingerprint |

## Security and signing boundaries

- Signing secrets are not required for ordinary pull-request CI; ordinary release compilation may remain unsigned.
- Only the tag-triggered release workflow has contents: write; the general Android CI workflow remains read-only.
- The private key is never embedded in the APK/AAB as a resource or committed to Git.
- A signed artifact proves possession of the configured key, not that the app is free of bugs or has passed an independent security audit.
- If the signing key is lost or compromised, stop releases and follow the relevant distribution platform's key-rotation/recovery procedure. Never silently replace the key for an update stream that requires continuity.

## Current gate status

At the time this process was added, Phase 5's permission-policy workflow was still running and API 37 runtime behavior plus physical two-phone radio/audio tests had not been verified. Therefore, this change prepares the release mechanism; it does not declare HamSeda ready for public production release.
