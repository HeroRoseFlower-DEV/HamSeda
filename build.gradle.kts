// Top-level build file — HamSeda
//
// Toolchain (verified 2026-10-09, building green on GitHub Actions):
//   AGP 8.13.2  — latest 8.x stable (Google Maven); supports compileSdk/
//                 targetSdk 37. AGP 9.x changes the Kotlin plugin model
//                 (built-in KGP) and is intentionally not adopted until the
//                 migration is validated.
//   Kotlin 2.1.20 (kotlin-android + compose compiler plugin, same version)
//   Gradle 8.14.6 via the committed wrapper (satisfies AGP 8.13.2's >= 8.13)
//   JDK 17 (Eclipse Temurin) for the Gradle daemon and compilation
//   compileSdk 37 / targetSdk 37 — above Google Play's API 36 requirement
//                 (in force since 2026-08-31); API 37 is stable.
//   minSdk 26 — per the project spec (Android 8.0).
//   Compose BOM 2025.04.00 — verified compatible with Kotlin 2.1.20.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
