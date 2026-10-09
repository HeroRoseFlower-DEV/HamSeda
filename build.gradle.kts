// Top-level build file — HamSeda
//
// Toolchain (verified 2026-10-09 from official sources — see README "Build matrix"):
//   AGP 9.4.1    — latest stable 9.x (Google Maven); max API 37.
//                  Built-in Kotlin is enabled by default; the
//                  org.jetbrains.kotlin.android plugin is intentionally NOT
//                  applied anywhere — AGP 9 hard-fails if it is.
//                  https://developer.android.com/build/migrate-to-built-in-kotlin
//   Kotlin 2.4.21 — latest stable (Maven Central), newer than AGP's bundled
//                  2.2.10. Selected via the buildscript classpath below, the
//                  official "Upgrade to a higher KGP version" mechanism:
//                  https://developer.android.com/build/releases/agp-9-0-0-release-notes
//                  (keep in sync with `kotlin` in gradle/libs.versions.toml)
//   Gradle 9.8.1 via the committed wrapper (satisfies AGP 9.4.1's >= 9.6.0)
//   JDK 17 (Eclipse Temurin) for the Gradle daemon and compilation
//   compileSdk 37 / targetSdk 37 — above Google Play's API 36 requirement
//                 (in force since 2026-08-31); API 37 is stable.
//   minSdk 26 — per the project spec (Android 8.0).
//   Compose BOM 2026.09.00 (UI 1.12.1, Material3 1.4.0) — requires AGP 9 +
//                 compileSdk 37.

buildscript {
    dependencies {
        // Official override: use KGP 2.4.21 instead of AGP's bundled 2.2.10.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
