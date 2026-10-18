// Top-level build file — HamSeda
//
// Toolchain (verified 2026-10-15 against official release notes and compatibility docs):
//   AGP 9.3.3 — supports compileSdk/targetSdk 37, requires Gradle >= 9.5,
//               and includes the 9.3.2 fix for lint on JDK 17.
//   Gradle 9.8.1 — pinned wrapper; within Kotlin 2.4.x's documented fully-supported range.
//   Kotlin 2.4.21 — current stable Kotlin patch; AGP supplies built-in Android Kotlin
//                   support, while the matching Compose compiler plugin is version-pinned.
//   JDK 17 (Eclipse Temurin) — supported by AGP 9.3.3 and kept aligned with CI.
//   compileSdk 37 / targetSdk 37; minSdk 26 (Android 8.0).
//   Compose BOM 2026.09.00 and direct application/test dependency versions are pinned
//   in gradle/libs.versions.toml; see README's build matrix for the current set.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
