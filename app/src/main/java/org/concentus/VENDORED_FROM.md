# Legacy Concentus source — retained for provenance only

The pure-Java Concentus source in this directory is a historical vendored copy
from https://github.com/lostromb/concentus. It is **not** the codec used by the
current app, and it is deliberately excluded from the Android main source set
by `app/build.gradle.kts`.

The production APK uses the official native Xiph `libopus 1.6.1` implementation
built from the pinned upstream archive in `app/src/main/cpp/CMakeLists.txt`.
Its JNI bridge, source hash, and full license notice live alongside that build
configuration. No codec source is downloaded at app runtime.

The legacy Java files remain in the repository to preserve their origin and
license record; do not re-enable them as the production backend or label them
as an up-to-date Opus implementation.
