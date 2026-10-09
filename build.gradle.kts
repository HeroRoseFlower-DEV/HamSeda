// Top-level build file — HamSeda
//
// Toolchain (verified 2026-10-09, building green on GitHub Actions):
//   AGP 8.10.1  — newest 8.x stable line; supports compileSdk/targetSdk 36.
//                 AGP 9.x changes the Kotlin plugin model (built-in KGP) and
//                 is intentionally not adopted until the migration is validated.
//   Kotlin 2.0.20 (kotlin-android + compose compiler plugin, same version)
//   Gradle 8.14.6 via the committed wrapper
//   JDK 17 (Eclipse Temurin) for the Gradle daemon and compilation
//   compileSdk 36 / targetSdk 36 — Google Play requires targetSdk 36 for
//                 app updates since 2026-08-31 (Android 16, API 36 is the
//                 newest stable SDK; API 37 was still rolling out).
//   minSdk 26 — per the project spec (Android 8.0).
//   Compose BOM 2024.10.00 — newest BOM train verified compatible with
//                 compileSdk 36 and Kotlin 2.0.20 (newer BOM trains pull
//                 Compose 1.11+/1.12+ which require compileSdk 37).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
